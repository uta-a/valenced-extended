package dev.uta.valencedextended.voicecord

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ResamplerTest {
    private fun input(n: Int) = ShortArray(n) { ((it * 37) % 2000 - 1000).toShort() }

    private fun runChunks(rate: Int, src: ShortArray, chunk: Int): ShortArray {
        val r = Resampler(rate)
        val out = ArrayList<Short>()
        var off = 0
        while (off < src.size) {
            val n = minOf(chunk, src.size - off)
            val len = r.process(src.copyOfRange(off, off + n), n)
            for (i in 0 until len) out += r.out[i]
            off += n
        }
        return out.toShortArray()
    }

    @Test
    fun `chunked input matches single-shot input across boundaries`() {
        for (rate in listOf(22_050, 44_100, 16_000)) {
            val src = input(4_410)
            assertContentEquals(runChunks(rate, src, src.size), runChunks(rate, src, 333), "rate=$rate")
        }
    }

    @Test
    fun `24k doubles the sample count and interpolates midpoints`() {
        val out = runChunks(24_000, shortArrayOf(0, 100, 200, 300), 2)
        assertContentEquals(shortArrayOf(0, 50, 100, 150, 200, 250), out)
    }

    @Test
    fun `downmix averages interleaved channels`() {
        val pcm = shortArrayOf(100, 300, -200, 0, 32767, 32767)
        assertEquals(3, downmixInPlace(pcm, pcm.size, 2))
        assertContentEquals(shortArrayOf(200, -100, 32767), pcm.copyOf(3))
    }
}
