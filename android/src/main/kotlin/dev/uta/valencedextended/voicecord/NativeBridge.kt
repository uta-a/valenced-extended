package dev.uta.valencedextended.voicecord

/** libvoicecord.so への JNI ブリッジ。実体は android/src/main/cpp/voicecord/hook.cpp。 */
internal object NativeBridge {
    const val STATE_HOOKED = 1
    const val STATE_PLAYING = 4

    /** 対応表の値を初期化前に一度だけ設定する。別の値での再設定や不正値は false。 */
    @JvmStatic external fun nativeSetOffsets(
        versionCode: Int,
        anchorRva: Long,
        encodeRva: Long,
        deliverRecordedRva: Long,
        getStatsRva: Long,
        ctrlOffset: Int,
        deadlineOffset: Int,
    ): Boolean

    /** 0=OK, 負値=失敗。libdiscord.so の読み込みを待ってフックを張るスレッドを起こす。 */
    @JvmStatic external fun nativeInit(): Int

    /** 対応版判定用に Discord の versionCode を通知する。 */
    @JvmStatic external fun nativeSetVersion(versionCode: Int)

    @JvmStatic external fun nativeSetInject(on: Boolean)

    /** 48kHz/int16/mono の PCM（pcm[off, off+len)）をリングへ投入し、書けたサンプル数を返す。 */
    @JvmStatic external fun nativeWrite(pcm: ShortArray, off: Int, len: Int): Int

    /** リングを空にする（drain 要求）。 */
    @JvmStatic external fun nativeStop()

    @JvmStatic external fun nativeSetParams(gain: Float, duck: Float)

    /** bit0=hooked, bit2=playing。 */
    @JvmStatic external fun nativeState(): Int
}
