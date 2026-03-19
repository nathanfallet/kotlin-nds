package dev.kotlinds

import kotlin.test.*

class LzssCodecTest {

    // -------------------------------------------------------------------------
    // 1. Round-trip: known pattern
    // -------------------------------------------------------------------------

    @Test
    fun `compress then decompress a known pattern round-trips correctly`() {
        // Pattern that has repetition (compressible) and variety (literal bytes)
        val original = ByteArray(256) { ((it * 17 + 3) % 251).toByte() }
        val compressed = LzssCodec.compress(original)
        val restored = LzssCodec.decompress(compressed)
        assertContentEquals(original, restored, "Round-trip failed for known pattern")
    }

    // -------------------------------------------------------------------------
    // 2. Decompress a hand-crafted minimal valid LZSS buffer
    // -------------------------------------------------------------------------

    @Test
    fun `decompress hand-crafted minimal buffer produces correct output`() {
        // We build a buffer that encodes: "ABC" as three literals.
        //
        // Header:
        //   Byte 0: 0x10 (magic)
        //   Bytes 1-3: 3 (uncompressed size, 24-bit LE)
        //
        // Data:
        //   Flag byte: 0x00  (all 8 bits = 0 → next 8 symbols are literals;
        //                     we only need 3 of them)
        //   Byte: 'A' = 0x41
        //   Byte: 'B' = 0x42
        //   Byte: 'C' = 0x43
        val input = byteArrayOf(
            0x10,               // magic
            0x03, 0x00, 0x00,  // uncompressed size = 3
            0x00,               // flag byte: all literals
            0x41, 0x42, 0x43   // 'A', 'B', 'C'
        )
        val expected = byteArrayOf(0x41, 0x42, 0x43)
        val result = LzssCodec.decompress(input)
        assertContentEquals(expected, result, "Hand-crafted decompression produced wrong output")
    }

    // -------------------------------------------------------------------------
    // 3. Highly repetitive data compresses and round-trips
    // -------------------------------------------------------------------------

    @Test
    fun `compress highly repetitive data round-trips and is smaller than input`() {
        val original = "AAAA".repeat(200).encodeToByteArray()  // 800 bytes, all 'A'
        val compressed = LzssCodec.compress(original)
        val restored = LzssCodec.decompress(compressed)

        assertContentEquals(original, restored, "Round-trip failed for repetitive data")
        assertTrue(
            compressed.size < original.size,
            "Compressed size (${compressed.size}) should be smaller than input (${original.size})"
        )
    }

    // -------------------------------------------------------------------------
    // 4. Pseudo-random data round-trips
    // -------------------------------------------------------------------------

    @Test
    fun `compress then decompress pseudo-random data round-trips correctly`() {
        val original = ByteArray(512) { (it * 13 + 7).toByte() }
        val compressed = LzssCodec.compress(original)
        val restored = LzssCodec.decompress(compressed)
        assertContentEquals(original, restored, "Round-trip failed for pseudo-random data")
    }

    // -------------------------------------------------------------------------
    // 5. Edge case: empty input
    // -------------------------------------------------------------------------

    @Test
    fun `decompress header with size zero returns empty ByteArray`() {
        // A minimal valid header with uncompressed size = 0 and no compressed data.
        val input = byteArrayOf(
            0x10,              // magic
            0x00, 0x00, 0x00   // uncompressed size = 0
        )
        val result = LzssCodec.decompress(input)
        assertEquals(0, result.size, "Decompressing zero-size header should yield empty array")
    }

    @Test
    fun `compress empty ByteArray round-trips to empty ByteArray`() {
        val original = ByteArray(0)
        val compressed = LzssCodec.compress(original)
        val restored = LzssCodec.decompress(compressed)
        assertEquals(0, restored.size, "Round-trip of empty input should yield empty output")
    }

    // -------------------------------------------------------------------------
    // 6. Magic byte check
    // -------------------------------------------------------------------------

    @Test
    fun `decompress throws when magic byte is not 0x10`() {
        val badInput = byteArrayOf(
            0x11,               // wrong magic byte
            0x03, 0x00, 0x00,
            0x00,
            0x41, 0x42, 0x43
        )
        assertFailsWith<IllegalArgumentException>("Should throw on bad magic byte") {
            LzssCodec.decompress(badInput)
        }
    }

    @Test
    fun `decompress throws when input is shorter than 4 bytes`() {
        assertFailsWith<IllegalArgumentException>("Should throw when input is too short") {
            LzssCodec.decompress(byteArrayOf(0x10, 0x03))
        }
    }

    // -------------------------------------------------------------------------
    // 7. Compress produces a valid LZ10 magic header
    // -------------------------------------------------------------------------

    @Test
    fun `compress output starts with magic byte 0x10`() {
        val data = "Hello, Nintendo DS!".encodeToByteArray()
        val compressed = LzssCodec.compress(data)
        assertEquals(0x10, compressed[0].toInt() and 0xFF, "First byte of compressed output must be 0x10")
    }

    @Test
    fun `compress encodes uncompressed size correctly in header`() {
        val data = ByteArray(300) { it.toByte() }
        val compressed = LzssCodec.compress(data)

        // Bytes 1–3 are the 24-bit LE uncompressed size
        val sizeInHeader =
            (compressed[1].toInt() and 0xFF) or
                    ((compressed[2].toInt() and 0xFF) shl 8) or
                    ((compressed[3].toInt() and 0xFF) shl 16)

        assertEquals(300, sizeInHeader, "Header must encode the original uncompressed size")
    }
}
