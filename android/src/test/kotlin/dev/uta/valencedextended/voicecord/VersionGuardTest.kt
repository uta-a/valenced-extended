package dev.uta.valencedextended.voicecord

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionGuardTest {
    private val validEntry = """
        {
          "versionCode": 345009,
          "so": "libdiscord.so",
          "abi": "arm64-v8a",
          "anchor": {
            "sym": "Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory",
            "rva": "0x9d39ec"
          },
          "funcs": {
            "WebRtcOpus_Encode": "0x56b758",
            "AudioDeviceBuffer::DeliverRecordedData": "0x891618",
            "Connection::GetStats": "0x392b18"
          },
          "offsets": {
            "connectionCtrlOffset": "0x10",
            "connectionDeadlineOffset": "0x0"
          }
        }
    """.trimIndent()

    private fun registry(entry: String) = """{"com.discord":{"345.9":$entry}}"""

    @Test
    fun `valid entry parses all required offsets`() {
        assertEquals(
            VoiceCordOffsets(0x9d39ecL, 0x56b758L, 0x891618L, 0x392b18L, 0x10, 0),
            parseVoiceCordOffsets(registry(validEntry))[345009],
        )
    }

    @Test
    fun `malformed and missing function entries are rejected`() {
        val malformed = validEntry.replace("0x56b758", "0xnothex")
        val missing = validEntry.replace("\"WebRtcOpus_Encode\": \"0x56b758\",", "")
        assertTrue(parseVoiceCordOffsets(registry(malformed)).isEmpty())
        assertTrue(parseVoiceCordOffsets(registry(missing)).isEmpty())
        assertTrue(parseVoiceCordOffsets("not json").isEmpty())
    }

    @Test
    fun `comment keys are ignored and invalid entries do not hide valid versions`() {
        val text = """
            {
              "_readme": ["対応表の説明"],
              "com.discord": {
                "_readme": ${validEntry.replace("345009", "123")},
                "invalid": {"versionCode": 999},
                "345.9": $validEntry
              }
            }
        """.trimIndent()
        assertEquals(setOf(345009), parseVoiceCordOffsets(text).keys)
    }

    @Test
    fun `repository registry includes Discord 345009 encode RVA`() {
        val entries = parseVoiceCordOffsets(File("../voicecord/signatures/rva.json").readText())
        assertEquals(0x56b758L, entries[345009]?.encodeRva)
    }
}
