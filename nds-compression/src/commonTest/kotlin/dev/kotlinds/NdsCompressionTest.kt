package dev.kotlinds

import kotlin.test.*

class NdsCompressionTest {

    @Test
    fun `isCompressed returns true for known magic bytes`() {
        for (magic in listOf(0x10, 0x11, 0x24, 0x28, 0x30)) {
            val data = ByteArray(8).also { it[0] = magic.toByte() }
            assertTrue(NdsCompression.isCompressed(data), "Expected true for magic 0x${magic.toString(16)}")
        }
    }

    @Test
    fun `isCompressed returns false for unknown bytes`() {
        for (magic in listOf(0x00, 0x01, 0xFF, 0x20, 0x40)) {
            val data = ByteArray(8).also { it[0] = magic.toByte() }
            assertFalse(NdsCompression.isCompressed(data), "Expected false for byte 0x${magic.toString(16)}")
        }
    }

    @Test
    fun `isCompressed returns false for empty array`() {
        assertFalse(NdsCompression.isCompressed(ByteArray(0)))
    }

    @Test
    fun `decompress dispatches to RleCodec correctly`() {
        val original = "AAAAABBBBB".encodeToByteArray()
        val compressed = RleCodec.compress(original)
        assertContentEquals(original, NdsCompression.decompress(compressed))
    }

    @Test
    fun `decompress dispatches to LzssCodec correctly`() {
        val original = "ABCABCABC".repeat(10).encodeToByteArray()
        val compressed = LzssCodec.compress(original)
        assertContentEquals(original, NdsCompression.decompress(compressed))
    }

    @Test
    fun `decompress dispatches to Lz11Codec correctly`() {
        val original = "XYZXYZXYZ".repeat(10).encodeToByteArray()
        val compressed = Lz11Codec.compress(original)
        assertContentEquals(original, NdsCompression.decompress(compressed))
    }

    @Test
    fun `decompress throws for unknown magic byte`() {
        assertFailsWith<IllegalArgumentException> {
            NdsCompression.decompress(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
    }

    @Test
    fun `decompress throws for empty input`() {
        assertFailsWith<IllegalArgumentException> {
            NdsCompression.decompress(ByteArray(0))
        }
    }
}
