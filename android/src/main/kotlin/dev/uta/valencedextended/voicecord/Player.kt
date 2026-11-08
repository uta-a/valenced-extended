package dev.uta.valencedextended.voicecord

import app.valenced.Logger
import app.valenced.bridge.ValencedBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * リングへの書き込みを1本にまとめる再生管理。ネイティブが初期化済みのときだけ使う。
 *
 * 再生のたびに世代 [gen] を進め、古いデコードは世代が変わった時点で書き込みをやめる。
 * 世代の確認と nativeWrite / nativeStop は [writeLock] の中で行うので、
 * stop の直後に古い再生のデータがリングへ入ることはなく、リングの producer も常に1本になる（SPSC）。
 */
internal class Player(private val log: Logger, private val bridge: ValencedBridge) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    private val gen = AtomicInteger()
    private val playing = MutableStateFlow(false)
    private val subscribed = AtomicBoolean(false)

    init {
        // JS 側が明示的に購読するまでは呼ばない。未登録メソッドの呼び出しは Discord をクラッシュさせる。
        scope.launch {
            playing.drop(1).collect { p -> if (subscribed.get()) notifyState(p) }
        }
    }

    /** JS は onState を登録してから購読する。現在値も一度通知する。 */
    fun subscribe() {
        subscribed.set(true)
        scope.launch { notifyState(playing.value) }
    }

    fun unsubscribe() {
        subscribed.set(false)
    }

    private suspend fun notifyState(value: Boolean) {
        if (!subscribed.get()) return
        runCatching {
            withTimeout(5.seconds) {
                bridge.callJSMethod("voicecord.onState", listOf(mapOf("playing" to value)))
            }
        }.onFailure { log.d("voicecord.onState not delivered: ${it.message}") }
    }

    /** 前の再生を止めて [open]（取得・デコーダ準備）を行い、成功したらデコードを裏で始める。 */
    suspend fun play(open: () -> DecodeSession): Map<String, Any?> {
        val myGen = newGeneration()
        val session = try {
            withContext(Dispatchers.IO) { open() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("VoiceCord: failed to open source", e)
            if (gen.get() == myGen) playing.value = false
            return mapOf("ok" to false, "error" to (e.message ?: e.javaClass.simpleName))
        }
        if (gen.get() != myGen) {
            session.release()
            return mapOf("ok" to false, "error" to "cancelled")
        }
        var total = 0L
        launchPlayback(myGen) {
            session.run(keepGoing = { gen.get() == myGen }) { pcm, len ->
                total += len
                feed(myGen, pcm, len)
            }
            log.d("VoiceCord: decode finished gen=$myGen samples48k=$total (${total * 1000 / OUT_RATE}ms) current=${gen.get()}")
        }
        return mapOf("ok" to true)
    }

    /** 固定の PCM（48kHz/mono）を流す。疎通確認用。 */
    fun playPcm(pcm: ShortArray) {
        val myGen = newGeneration()
        launchPlayback(myGen) { feed(myGen, pcm, pcm.size) }
    }

    fun stop() {
        newGeneration()
        playing.value = false
    }

    private fun newGeneration(): Int = synchronized(writeLock) {
        gen.incrementAndGet().also { NativeBridge.nativeStop() }
    }

    private fun launchPlayback(myGen: Int, produce: () -> Unit) {
        playing.value = true
        scope.launch {
            try {
                produce()
            } catch (e: Exception) {
                log.e("VoiceCord: decode failed", e)
            }
            // 書き終えたあと、送信側がリングを読み切るまで待ってから停止を知らせる
            while (gen.get() == myGen && NativeBridge.nativeState() and NativeBridge.STATE_PLAYING != 0) delay(100)
            if (gen.get() == myGen) playing.value = false
        }
    }

    /** リングが満杯なら待ちながら pcm[0, len) を書く。世代が変わったら false。 */
    private fun feed(myGen: Int, pcm: ShortArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val w = synchronized(writeLock) {
                if (gen.get() != myGen) return false
                NativeBridge.nativeWrite(pcm, off, len - off)
            }
            off += w
            if (off < len) Thread.sleep(20) // 送信側が 48k サンプル/秒で消費する
        }
        return true
    }
}
