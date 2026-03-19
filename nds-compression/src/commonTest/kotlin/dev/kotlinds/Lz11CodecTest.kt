package dev.kotlinds

import kotlin.test.*

class Lz11CodecTest {

    @Test
    fun `round-trip repetitive pattern`() {
        val original = ByteArray(512) { (it % 16).toByte() }
        assertContentEquals(original, Lz11Codec.decompress(Lz11Codec.compress(original)))
    }

    @Test
    fun `round-trip pseudo-random data`() {
        val original = ByteArray(512) { (it * 13 + 7).toByte() }
        assertContentEquals(original, Lz11Codec.decompress(Lz11Codec.compress(original)))
    }

    @Test
    fun `highly repetitive data compresses smaller and round-trips`() {
        val original = ByteArray(1024) { 0xAB.toByte() }
        val compressed = Lz11Codec.compress(original)
        assertTrue(compressed.size < original.size, "Repetitive data should compress smaller")
        assertContentEquals(original, Lz11Codec.decompress(compressed))
    }

    @Test
    fun `empty input round-trips to empty output`() {
        val compressed = Lz11Codec.compress(ByteArray(0))
        assertEquals(4, compressed.size)
        assertEquals(0x11.toByte(), compressed[0])
        assertContentEquals(ByteArray(0), Lz11Codec.decompress(compressed))
    }

    @Test
    fun `decompress throws on bad magic byte`() {
        assertFailsWith<IllegalArgumentException> {
            Lz11Codec.decompress(ByteArray(8) { it.toByte() })  // byte[0] == 0x00
        }
    }

    @Test
    fun `tier-1 back-reference decompresses correctly`() {
        // Uncompressed: 5 x 'A' (0x41)
        // flag=0x40: bit7=0 (literal), bit6=1 (back-ref)
        // literal: 0x41
        // tier-1 token: len=4 (hi=3), dist=1 (d=0) → byte0=0x30, byte1=0x00
        val compressed = byteArrayOf(
            0x11, 0x05, 0x00, 0x00,
            0x40,
            0x41,
            0x30, 0x00
        )
        assertContentEquals(ByteArray(5) { 0x41 }, Lz11Codec.decompress(compressed))
    }

    @Test
    fun `tier-2 back-reference round-trip`() {
        // Force a match >= 17 bytes
        val original = ByteArray(4) { (it + 1).toByte() } + ByteArray(30) { 0xCC.toByte() }
        assertContentEquals(original, Lz11Codec.decompress(Lz11Codec.compress(original)))
    }

    @Test
    fun `compressed output starts with magic 0x11`() {
        val compressed = Lz11Codec.compress(byteArrayOf(1, 2, 3))
        assertEquals(0x11.toByte(), compressed[0])
    }
}
