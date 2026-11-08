package dev.uta.valencedextended.voicecord

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

internal const val OUT_RATE = 48_000

/**
 * 音声トラックを選んだ状態の [MediaExtractor]。[run] で 48kHz/int16/mono の PCM を流す。
 * [run] か [release] のどちらかを必ず1回呼ぶ。
 */
internal class DecodeSession private constructor(
    private val extractor: MediaExtractor,
    private val format: MediaFormat,
    private val mime: String,
) {
    companion object {
        /** [configure] で `setDataSource` したうえで、最初の audio トラックを選ぶ。 */
        fun open(configure: (MediaExtractor) -> Unit): DecodeSession {
            val ex = MediaExtractor()
            try {
                configure(ex)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        ex.selectTrack(i)
                        return DecodeSession(ex, f, mime)
                    }
                }
                throw IllegalArgumentException("音声トラックがない")
            } catch (t: Throwable) {
                ex.release()
                throw t
            }
        }
    }

    fun release() = extractor.release()

    /**
     * 最後までデコードする。[keepGoing] が false、または [sink] が false を返したら中断する。
     * [sink] には変換済み PCM の先頭 len サンプルが渡される（配列は次の呼び出しで再利用される）。
     */
    fun run(keepGoing: () -> Boolean, sink: (pcm: ShortArray, len: Int) -> Boolean) {
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var channels = channelCount(format)
            var resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var pcm = ShortArray(0)

            while (keepGoing()) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIx)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // HE-AAC などは出力時点でレート・チャンネル数が確定する
                    val out = codec.outputFormat
                    channels = channelCount(out)
                    val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (rate != resampler.srcRate) resampler = Resampler(rate)
                    continue
                }
                if (outIx < 0) continue

                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                try {
                    if (info.size > 0) {
                        val sb = codec.getOutputBuffer(outIx)!!.apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                        }.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val n = sb.remaining()
                        if (pcm.size < n) pcm = ShortArray(n)
                        sb.get(pcm, 0, n)
                        val frames = downmixInPlace(pcm, n, channels)
                        // process() は out を作り直すことがあるので、先に呼んでから out を読む
                        // （sink(resampler.out, resampler.process(...)) だと古い空配列が渡る）
                        val ok = if (resampler.passthrough) sink(pcm, frames)
                        else {
                            val len = resampler.process(pcm, frames)
                            sink(resampler.out, len)
                        }
                        if (!ok) return
                    }
                } finally {
                    codec.releaseOutputBuffer(outIx, false)
                }
                if (eos) return
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun channelCount(f: MediaFormat) =
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
}

/** インターリーブされた pcm[0, n) を平均でモノラルにし、先頭に詰めてフレーム数を返す。 */
internal fun downmixInPlace(pcm: ShortArray, n: Int, channels: Int): Int {
    if (channels <= 1) return n
    val frames = n / channels
    for (i in 0 until frames) {
        var acc = 0
        val base = i * channels
        for (c in 0 until channels) acc += pcm[base + c]
        pcm[i] = (acc / channels).toShort() // i <= base なので読む前に上書きしない
    }
    return frames
}

/**
 * 48kHz への線形補間リサンプラ。チャンクの境目では前チャンク末尾のサンプルと位相を持ち越すので、
 * 分割して入れても一括で入れても同じ出力になる。
 */
internal class Resampler(val srcRate: Int) {
    val passthrough = srcRate == OUT_RATE

    /**
     * 入力位置を 1/[OUT_RATE] サンプル単位の整数で持つ（今のチャンク先頭を 0、負は前チャンクの末尾側）。
     * 浮動小数で積み上げると、チャンクの分け方で丸め誤差が変わり出力がずれるため。
     */
    private var pos = 0L
    private var prev = 0

    var out = ShortArray(0)
        private set

    /** src[0, n) を変換して [out] の先頭に書き、出力サンプル数を返す。 */
    fun process(src: ShortArray, n: Int): Int {
        if (n == 0) return 0
        val cap = (n.toLong() * OUT_RATE / srcRate).toInt() + 2
        if (out.size < cap) out = ShortArray(cap)
        var k = 0
        while (true) {
            val i = Math.floorDiv(pos, OUT_RATE.toLong()).toInt()
            if (i + 1 > n - 1) break
            val frac = pos - i.toLong() * OUT_RATE
            val a = if (i < 0) prev else src[i].toInt()
            val b = src[i + 1].toInt()
            out[k++] = (a + Math.round((b - a).toDouble() * frac / OUT_RATE)).toInt().toShort()
            pos += srcRate
        }
        pos -= n.toLong() * OUT_RATE
        prev = src[n - 1].toInt()
        return k
    }
}
