package dev.uta.valencedextended.voicecord

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import app.valenced.Logger

/**
 * 音源を端末だけで鳴らす（VC には送らない）。プレビューと、押したときに自分にも聞こえるようにする再生に使う。
 * 同時に鳴らすのは1つだけで、新しく鳴らすと前の再生は止める。
 */
internal class LocalPlayer(private val log: Logger) {
    private var player: MediaPlayer? = null

    @Synchronized
    fun play(ctx: Context, uriString: String, volume: Float) {
        stop()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(ctx, Uri.parse(uriString))
            mp.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
            mp.setOnCompletionListener { release(it) }
            mp.setOnErrorListener { p, what, extra ->
                log.w("VoiceCord: local playback error what=$what extra=$extra")
                release(p)
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (t: Throwable) {
            mp.release()
            throw t
        }
    }

    @Synchronized
    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        runCatching { player?.setVolume(v, v) }
    }

    @Synchronized
    fun stop() {
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        mp.release()
    }

    @Synchronized
    private fun release(mp: MediaPlayer) {
        if (player === mp) player = null
        mp.release()
    }
}
