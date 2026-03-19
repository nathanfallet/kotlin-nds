package dev.kotlinds

import kotlin.test.Test
import kotlin.test.assertTrue

class BlzCodecTest {
    @Test
    fun `compress then decompress round-trip`() {
        val original = ByteArray(1024) { (it * 7 + 13).toByte() }
        val compressed = BlzCodec.compress(original)
        val decompressed = BlzCodec.decompress(compressed)
        assertTrue(original.contentEquals(decompressed), "Round-trip compress/decompress failed")
    }

    @Test
    fun `decompress already-decompressed data returns unchanged`() {
        // Data with inc_len == 0 at the last 4 bytes (not encoded marker)
        val raw = ByteArray(20) { it.toByte() }
        // Write 0 as last 4 bytes
        raw[16] = 0; raw[17] = 0; raw[18] = 0; raw[19] = 0
        val result = BlzCodec.decompress(raw)
        assertTrue(raw.contentEquals(result))
    }

    @Test
    fun `compress produces output decodable by decompress`() {
        val data = "POKEMON".repeat(100).encodeToByteArray()
        val compressed = BlzCodec.compress(data)
        val back = BlzCodec.decompress(compressed)
        assertTrue(data.contentEquals(back), "compress+decompress should reproduce original")
    }
}
