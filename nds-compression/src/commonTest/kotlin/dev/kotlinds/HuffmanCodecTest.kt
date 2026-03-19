package dev.kotlinds

import kotlin.test.*

class HuffmanCodecTest {

    // -------------------------------------------------------------------------
    // Round-trip tests
    // -------------------------------------------------------------------------

    @Test
    fun `8-bit round-trip POKEMON repeated`() {
        val original = "POKEMON".repeat(20).encodeToByteArray()
        val compressed = HuffmanCodec.compress(original)
        val decompressed = HuffmanCodec.decompress(compressed)
        assertContentEquals(original, decompressed, "POKEMON round-trip failed")
    }

    /**
     * Round-trip test using 64 distinct byte values (the maximum the DS 6-bit
     * offset field can address with the BFS-pair layout used by this codec).
     * Each value repeats 4 times for a 256-byte input.
     */
    @Test
    fun `8-bit round-trip 64 distinct byte values`() {
        val original = ByteArray(256) { (it % 64).toByte() }
        val compressed = HuffmanCodec.compress(original)
        val decompressed = HuffmanCodec.decompress(compressed)
        assertContentEquals(original, decompressed, "64-distinct-values round-trip failed")
    }

    @Test
    fun `8-bit round-trip single byte`() {
        val original = byteArrayOf(0x42)
        val compressed = HuffmanCodec.compress(original)
        val decompressed = HuffmanCodec.decompress(compressed)
        assertContentEquals(original, decompressed, "Single-byte round-trip failed")
    }

    @Test
    fun `8-bit round-trip two distinct bytes`() {
        val original = byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00)
        val compressed = HuffmanCodec.compress(original)
        val decompressed = HuffmanCodec.decompress(compressed)
        assertContentEquals(original, decompressed, "Two-byte round-trip failed")
    }

    @Test
    fun `8-bit round-trip longer mixed data`() {
        // 16 distinct byte values repeated over 1024 bytes — well within the 64-symbol limit
        val original = ByteArray(1024) { (it % 16).toByte() }
        val compressed = HuffmanCodec.compress(original)
        val decompressed = HuffmanCodec.decompress(compressed)
        assertContentEquals(original, decompressed, "Mixed-data round-trip failed")
    }

    // -------------------------------------------------------------------------
    // Header / magic checks
    // -------------------------------------------------------------------------

    @Test
    fun `decompress throws on invalid magic byte`() {
        val bad = byteArrayOf(0x10, 0x00, 0x00, 0x00, 0x00)
        assertFailsWith<IllegalArgumentException> { HuffmanCodec.decompress(bad) }
    }

    @Test
    fun `decompress accepts magic 0x28`() {
        val original = "hello".encodeToByteArray()
        val compressed = HuffmanCodec.compress(original)
        assertEquals(0x28, compressed[0].toInt() and 0xFF, "Magic byte should be 0x28")
        val decompressed = HuffmanCodec.decompress(compressed)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun `compressed output encodes uncompressed size in header`() {
        val original = "ABCDE".encodeToByteArray()   // 5 bytes
        val compressed = HuffmanCodec.compress(original)
        // Bytes 1-3 are little-endian 24-bit uncompressed size
        val size = (compressed[1].toInt() and 0xFF) or
                ((compressed[2].toInt() and 0xFF) shl 8) or
                ((compressed[3].toInt() and 0xFF) shl 16)
        assertEquals(original.size, size, "Header uncompressed size mismatch")
    }

    // -------------------------------------------------------------------------
    // Compression ratio
    // -------------------------------------------------------------------------

    @Test
    fun `repetitive data compresses smaller than input`() {
        // Highly repetitive: only one distinct byte value repeated many times
        val original = ByteArray(200) { 0x41 }
        val compressed = HuffmanCodec.compress(original)
        assertTrue(
            compressed.size < original.size,
            "Repetitive data should compress: compressed=${compressed.size}, original=${original.size}"
        )
    }
}
