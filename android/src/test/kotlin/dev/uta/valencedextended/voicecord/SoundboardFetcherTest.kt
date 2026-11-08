package dev.uta.valencedextended.voicecord

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundboardFetcherTest {
    @Test
    fun `sound id accepts only 1 to 20 digits`() {
        assertTrue(SoundboardFetcher.isSoundId("1"))
        assertTrue(SoundboardFetcher.isSoundId("1".repeat(20)))
        for (bad in listOf(null, "", "1".repeat(21), "12a", "../1", "1/2", " 1", "１２")) {
            assertFalse(SoundboardFetcher.isSoundId(bad), "accepted: $bad")
        }
    }

    @Test
    fun `eviction keeps the newest files within the count limit and ignores others`() {
        val dir = Files.createTempDirectory("vc").toFile()
        val extra = 3
        val files = (0 until SoundboardFetcher.MAX_CACHE_FILES + extra).map { i ->
            File(dir, "vc_sb_$i.mp3").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000_000L + i * 1000L) }
        }
        val tmp = File(dir, "vc_sb_x.mp3.1.tmp").apply { writeBytes(byteArrayOf(1)); setLastModified(1L) }
        val other = File(dir, "keep.dat").apply { writeBytes(byteArrayOf(1)); setLastModified(1L) }

        SoundboardFetcher.evictCache(dir)

        assertTrue(files.take(extra).none { it.exists() })
        assertTrue(files.drop(extra).all { it.exists() })
        assertTrue(tmp.exists())
        assertTrue(other.exists())
        assertEquals(SoundboardFetcher.MAX_CACHE_FILES, dir.listFiles { f -> f.name.endsWith(".mp3") }!!.size)
        dir.deleteRecursively()
    }
}
