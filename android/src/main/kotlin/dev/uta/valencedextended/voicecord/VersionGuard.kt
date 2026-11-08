package dev.uta.valencedextended.voicecord

import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** フックに必要な RVA と構造体オフセット。ネイティブへ渡した後は変更しない。 */
internal data class VoiceCordOffsets(
    val anchorRva: Long,
    val encodeRva: Long,
    val deliverRecordedRva: Long,
    val getStatsRva: Long,
    val ctrlOffset: Int,
    val deadlineOffset: Int,
)

/** Android に依存しない解析処理。不完全な版は対応表へ入れない。 */
internal fun parseVoiceCordOffsets(text: String): Map<Int, VoiceCordOffsets> {
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return emptyMap()
    val versions = root["com.discord"] as? JsonObject ?: return emptyMap()
    val result = mutableMapOf<Int, VoiceCordOffsets>()
    for ((name, element) in versions) {
        if (name.startsWith('_')) continue
        val entry = element as? JsonObject ?: continue
        val version = (entry["versionCode"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it > 0 } ?: continue
        if ((entry["so"] as? JsonPrimitive)?.content != "libdiscord.so" ||
            (entry["abi"] as? JsonPrimitive)?.content != "arm64-v8a"
        ) continue
        val anchor = entry["anchor"] as? JsonObject ?: continue
        if ((anchor["sym"] as? JsonPrimitive)?.content !=
            "Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory"
        ) continue
        val funcs = entry["funcs"] as? JsonObject ?: continue
        val offsets = entry["offsets"] as? JsonObject ?: continue
        val anchorRva = anchor.hex("rva")?.takeIf { it > 0 } ?: continue
        val encodeRva = funcs.hex("WebRtcOpus_Encode")?.takeIf { it > 0 } ?: continue
        val deliverRva = funcs.hex("AudioDeviceBuffer::DeliverRecordedData")?.takeIf { it > 0 }
            ?: continue
        val statsRva = funcs.hex("Connection::GetStats")?.takeIf { it > 0 } ?: continue
        val ctrlOffset = offsets.hex("connectionCtrlOffset")
            ?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: continue
        val deadlineOffset = offsets.hex("connectionDeadlineOffset")
            ?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: continue
        result[version] = VoiceCordOffsets(
            anchorRva, encodeRva, deliverRva, statsRva, ctrlOffset, deadlineOffset,
        )
    }
    return result
}

private fun JsonObject.hex(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    val value = primitive.content
    if (!value.startsWith("0x") || value.length <= 2) return null
    val digits = value.substring(2)
    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return digits.toLongOrNull(16)
}

/** モジュール APK の対応表を読み込み、表にない版では VoiceCord だけを停止する。 */
internal object VersionGuard {
    private var loaded = false
    @Volatile private var entries: Map<Int, VoiceCordOffsets> = emptyMap()

    val supported: Set<Int> get() = entries.keys

    /** 失敗時も空の対応表で確定し、繰り返し呼ばれても読み直さない。 */
    @Synchronized
    fun load(modulePath: String) {
        if (loaded) return
        entries = runCatching {
            ZipFile(modulePath).use { apk ->
                val asset = apk.getEntry("assets/voicecord/rva.json") ?: return@use emptyMap()
                apk.getInputStream(asset).bufferedReader(Charsets.UTF_8).use {
                    parseVoiceCordOffsets(it.readText())
                }
            }
        }.getOrDefault(emptyMap())
        loaded = true
    }

    fun isSupported(versionCode: Int) = versionCode in entries

    /** libvoicecord.so 読み込み後、nativeInit より前に呼ぶ。 */
    fun applyOffsets(versionCode: Int): Boolean {
        val entry = entries[versionCode] ?: return false
        return NativeBridge.nativeSetOffsets(
            versionCode, entry.anchorRva, entry.encodeRva, entry.deliverRecordedRva,
            entry.getStatsRva, entry.ctrlOffset, entry.deadlineOffset,
        )
    }
}
