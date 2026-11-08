package dev.uta.valencedextended.voicecord

import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Discord サウンドボード音源を sound_id から CDN 取得してキャッシュに置く（VoiceCord-mobile SoundboardFetcher.java の移植）。
 *
 * 外から取ったものをデコーダに読ませるので、関門は移植元のまま固定する:
 * - ホストは cdn.discordapp.com の決まったパスだけ。ID は数字 1〜20 桁だけ（URL とファイル名に入るため）
 * - リダイレクトは追わない。200 以外は失敗
 * - Content-Type が audio/mpeg・audio/ogg 以外は失敗
 * - 8MB まで（宣言値と実受信の両方で確認）。途中切れ・空は保存しない
 * - .tmp に書き切ってから rename で確定する
 *
 * ブロッキングなので IO スレッドから呼ぶ。失敗は [IOException] で返す。
 */
internal object SoundboardFetcher {
    private val SOUND_ID_RE = Regex("^\\d{1,20}$")
    private const val CDN_HOST = "cdn.discordapp.com"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val TIMEOUT_MS = 10_000
    const val MAX_CACHE_FILES = 32
    const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    private const val PREFIX = "vc_sb_"
    private val tmpSeq = AtomicInteger()

    fun isSoundId(id: String?) = id != null && SOUND_ID_RE.matches(id)

    fun fetch(cacheDir: File, id: String): File {
        if (!isSoundId(id)) throw IOException("sound_id の形が不正")
        cacheDir.mkdirs()

        val conn = URL("https://$CDN_HOST/soundboard-sounds/$id").openConnection() as HttpsURLConnection
        var tmp: File? = null
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS

            val status = conn.responseCode
            if (status != 200) throw IOException("HTTP $status")
            val ext = extForContentType(conn.contentType)
                ?: throw IOException("音声ではない Content-Type: ${conn.contentType}")
            val declared = conn.contentLengthLong
            if (declared > MAX_BYTES) throw IOException("大きすぎる（$declared バイト）")

            val finalFile = File(cacheDir, "$PREFIX$id.$ext")
            tmp = File(cacheDir, "$PREFIX$id.$ext.${tmpSeq.incrementAndGet()}.tmp")

            var received = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        received += n
                        if (received > MAX_BYTES) throw IOException("大きすぎる（受信 $received バイト）")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (received == 0L) throw IOException("空の応答")
            if (declared >= 0 && declared != received) throw IOException("受信が途中で切れた（$received/$declared バイト）")

            finalFile.delete()
            if (!tmp.renameTo(finalFile)) throw IOException("rename に失敗")
            tmp = null
            evictCache(cacheDir)
            return finalFile
        } finally {
            tmp?.delete()
            conn.disconnect()
        }
    }

    private fun extForContentType(contentType: String?): String? =
        when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            else -> null
        }

    /** vc_sb_* の確定ファイルを、件数・総バイトの上限に収まるまで古い順に消す（.tmp は取得中なので触らない）。 */
    fun evictCache(cacheDir: File) {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && !f.name.endsWith(".tmp") }
            ?: return
        files.sortBy { it.lastModified() }
        var count = files.size
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (count <= MAX_CACHE_FILES && total <= MAX_CACHE_BYTES) break
            val len = f.length()
            if (f.delete()) {
                count--
                total -= len
            }
        }
    }
}
