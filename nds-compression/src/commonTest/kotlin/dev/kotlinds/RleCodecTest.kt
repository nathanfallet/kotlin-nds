package dev.kotlinds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RleCodecTest {

    // -------------------------------------------------------------------------
    // Round-trip tests
    // -------------------------------------------------------------------------

    @Test
    fun `round-trip highly repetitive data`() {
        val original = "POKEMON".repeat(50).encodeToByteArray()
        val compressed = RleCodec.compress(original)
        val decompressed = RleCodec.decompress(compressed)
        assertTrue(original.contentEquals(decompressed), "Round-trip failed for repetitive data")
    }

    @Test
    fun `round-trip mixed non-repetitive data`() {
        // Pseudo-random byte sequence with no long runs
        val original = ByteArray(512) { i -> ((i * 97 + 13) xor (i * 31)).toByte() }
        val compressed = RleCodec.compress(original)
        val decompressed = RleCodec.decompress(compressed)
        assertTrue(original.contentEquals(decompressed), "Round-trip failed for mixed data")
    }

    @Test
    fun `round-trip non-repetitive data still correct`() {
        // Every byte is distinct (mod 256), so no RLE runs can be encoded
        val original = ByteArray(256) { i -> i.toByte() }
        val compressed = RleCodec.compress(original)
        val decompressed = RleCodec.decompress(compressed)
        assertTrue(original.contentEquals(decompressed), "Round-trip failed for non-repetitive data")
    }

    // -------------------------------------------------------------------------
    // Compression quality
    // -------------------------------------------------------------------------

    @Test
    fun `compress highly repetitive data produces smaller output`() {
        // 1000 copies of the same byte — RLE should compress this very well
        val original = ByteArray(1000) { 0x41.toByte() }
        val compressed = RleCodec.compress(original)
        assertTrue(
            compressed.size < original.size,
            "Expected compressed size (${compressed.size}) < original size (${original.size})"
        )
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `empty input compresses to header with size zero and decompresses to empty`() {
        val original = ByteArray(0)
        val compressed = RleCodec.compress(original)

        // Header must be exactly 4 bytes for empty input
        assertEquals(4, compressed.size, "Compressed empty input should be 4 bytes (header only)")

        // Magic byte
        assertEquals(0x30, compressed[0].toInt() and 0xFF, "Magic byte should be 0x30")

        // 24-bit little-endian size = 0
        assertEquals(0, compressed[1].toInt() and 0xFF, "Size byte 1 should be 0")
        assertEquals(0, compressed[2].toInt() and 0xFF, "Size byte 2 should be 0")
        assertEquals(0, compressed[3].toInt() and 0xFF, "Size byte 3 should be 0")

        val decompressed = RleCodec.decompress(compressed)
        assertEquals(0, decompressed.size, "Decompressed empty input should have size 0")
    }

    @Test
    fun `decompress throws on wrong magic byte`() {
        val badMagic = byteArrayOf(0x10, 0x05, 0x00, 0x00, 0x04, 0x41)
        assertFailsWith<IllegalArgumentException>("Should throw on wrong magic byte") {
            RleCodec.decompress(badMagic)
        }
    }

    // -------------------------------------------------------------------------
    // Hand-crafted decompression
    // -------------------------------------------------------------------------

    @Test
    fun `decompress hand-crafted run block produces correct output`() {
        // Header: magic=0x30, uncompressed size = 5 (little-endian 24-bit)
        // Body:   flag=0x82 (run block, count = (0x82 & 0x7F) + 3 = 2 + 3 = 5), byte=0x41 ('A')
        // Expected output: "AAAAA" (5 bytes)
        val input = byteArrayOf(
            0x30.toByte(),  // magic
            0x05.toByte(), 0x00.toByte(), 0x00.toByte(), // uncompressed size = 5
            0x82.toByte(),  // run flag: (0x82 & 0x7F) + 3 = 5 repetitions
            0x41.toByte()   // byte 'A'
        )
        val result = RleCodec.decompress(input)
        assertEquals(5, result.size, "Output should be 5 bytes")
        assertTrue(result.all { it == 0x41.toByte() }, "All output bytes should be 'A' (0x41)")
    }

    @Test
    fun `decompress hand-crafted literal block produces correct output`() {
        // Header: magic=0x30, uncompressed size = 3
        // Body:   flag=0x02 (literal block, count = (0x02 & 0x7F) + 1 = 3), then 3 literal bytes
        // Expected output: [0x01, 0x02, 0x03]
        val input = byteArrayOf(
            0x30.toByte(),  // magic
            0x03.toByte(), 0x00.toByte(), 0x00.toByte(), // uncompressed size = 3
            0x02.toByte(),  // literal flag: (0x02 & 0x7F) + 1 = 3 bytes
            0x01.toByte(), 0x02.toByte(), 0x03.toByte()
        )
        val result = RleCodec.decompress(input)
        assertEquals(3, result.size, "Output should be 3 bytes")
        assertEquals(0x01.toByte(), result[0])
        assertEquals(0x02.toByte(), result[1])
        assertEquals(0x03.toByte(), result[2])
    }
}
