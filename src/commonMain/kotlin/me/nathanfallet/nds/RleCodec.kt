package me.nathanfallet.nds

import me.nathanfallet.nds.RleCodec.MAX_LITERAL
import me.nathanfallet.nds.RleCodec.MAX_RUN
import me.nathanfallet.nds.RleCodec.MIN_RUN


/**
 * RLE (Run-Length Encoding) codec for Nintendo DS compressed files.
 *
 * Format (from Nintendo_DS_Compressors reference):
 *   - Magic byte: 0x30
 *   - Header (4 bytes): [0x30, sizeLo, sizeMid, sizeHi] (24-bit little-endian uncompressed size)
 *   - Body: sequence of flag+data blocks
 *     - Flag MSB = 0  -> literal block: copy next (flag & 0x7F) + 1 bytes verbatim
 *     - Flag MSB = 1  -> run block:     repeat next byte (flag & 0x7F) + 3 times
 */
object RleCodec {

    private const val MAGIC = 0x30

    // Run-length encoding thresholds / limits
    private const val MIN_RUN = 3       // minimum run length to encode as RLE
    private const val MAX_RUN = 130     // 0x7F + 3
    private const val MAX_LITERAL = 128 // 0x7F + 1

    // -------------------------------------------------------------------------
    // Decompress
    // -------------------------------------------------------------------------

    /**
     * Decompresses an RLE-encoded Nintendo DS buffer.
     *
     * @param input The raw compressed bytes, starting with the 0x30 magic byte.
     * @return The fully decompressed data.
     * @throws IllegalArgumentException if the magic byte is not 0x30 or the
     *   buffer is too short to contain a valid header.
     */
    fun decompress(input: ByteArray): ByteArray {
        require(input.size >= 4) { "RLE: buffer too short to contain a header" }
        require(input[0].toInt() and 0xFF == MAGIC) {
            "RLE: bad magic byte 0x${(input[0].toInt() and 0xFF).toString(16).uppercase()}, expected 0x30"
        }

        // Uncompressed size stored as 24-bit little-endian in bytes 1-3
        val uncompressedSize = readU24(input, 1).toInt()
        val out = ByteArray(uncompressedSize)

        var src = 4 // read cursor (after header)
        var dst = 0 // write cursor

        while (dst < uncompressedSize && src < input.size) {
            val flag = input[src++].toInt() and 0xFF

            if (flag and 0x80 == 0) {
                // Literal block: copy the next (flag & 0x7F) + 1 bytes verbatim
                val count = (flag and 0x7F) + 1
                repeat(count) {
                    if (src < input.size && dst < uncompressedSize) {
                        out[dst++] = input[src++]
                    }
                }
            } else {
                // Run block: repeat the next byte (flag & 0x7F) + 3 times
                val count = (flag and 0x7F) + MIN_RUN
                val byte = if (src < input.size) input[src++] else 0
                repeat(count) {
                    if (dst < uncompressedSize) {
                        out[dst++] = byte
                    }
                }
            }
        }

        return out
    }

    // -------------------------------------------------------------------------
    // Compress
    // -------------------------------------------------------------------------

    /**
     * Compresses [input] using the Nintendo DS RLE format.
     *
     * Encoding strategy:
     *   - Runs of >= [MIN_RUN] identical bytes are emitted as run blocks (up to [MAX_RUN] bytes).
     *   - All other bytes are collected and emitted as literal blocks (up to [MAX_LITERAL] bytes).
     *
     * @param input The raw data to compress.
     * @return The compressed data including the 4-byte header.
     */
    fun compress(input: ByteArray): ByteArray {
        val rawLen = input.size

        // Pre-allocate worst-case output: every byte needs a flag byte -> 2x input + 4-byte header
        val buf = ByteArray(rawLen * 2 + 4)

        // Write 4-byte header
        buf[0] = MAGIC.toByte()
        writeU24(buf, 1, rawLen.toLong())

        var src = 0
        var dst = 4

        while (src < rawLen) {
            // Measure the run of identical bytes starting at src
            val runLen = measureRun(input, src, rawLen)

            if (runLen >= MIN_RUN) {
                // Emit one or more run blocks for this run
                var remaining = runLen
                while (remaining > 0) {
                    val chunk = minOf(remaining, MAX_RUN)
                    // Flag byte: MSB set, lower 7 bits = chunk - MIN_RUN
                    buf[dst++] = (0x80 or (chunk - MIN_RUN)).toByte()
                    buf[dst++] = input[src]
                    src += chunk
                    remaining -= chunk
                }
            } else {
                // Collect literal bytes up to MAX_LITERAL, stopping early when a run of >= MIN_RUN starts
                val litStart = src
                var litEnd = src
                while (litEnd < rawLen && litEnd - litStart < MAX_LITERAL) {
                    val nextRun = measureRun(input, litEnd, rawLen)
                    if (nextRun >= MIN_RUN) break
                    litEnd++
                }
                // Guard: always advance at least one byte to avoid an infinite loop
                if (litEnd == litStart) litEnd = litStart + 1

                val litLen = litEnd - litStart
                // Flag byte: MSB clear, lower 7 bits = litLen - 1
                buf[dst++] = (litLen - 1).toByte()
                input.copyInto(buf, dst, litStart, litEnd)
                dst += litLen
                src = litEnd
            }
        }

        return buf.copyOf(dst)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the number of consecutive identical bytes starting at [pos], capped at [MAX_RUN].
     *
     * @param data The input byte array.
     * @param pos The position to start measuring from.
     * @param end The exclusive end of the search range.
     * @return Run length in `[0, MAX_RUN]`.
     */
    private fun measureRun(data: ByteArray, pos: Int, end: Int): Int {
        if (pos >= end) return 0
        val b = data[pos]
        var len = 1
        while (pos + len < end && len < MAX_RUN && data[pos + len] == b) len++
        return len
    }

    /**
     * Reads a 24-bit little-endian unsigned integer from [buf] at [offset].
     *
     * @param buf The source byte array.
     * @param offset Byte offset to read from.
     * @return The 24-bit value as a [Long].
     */
    private fun readU24(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFF) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                ((buf[offset + 2].toLong() and 0xFF) shl 16)

    /**
     * Writes a 24-bit little-endian value to [buf] at [offset].
     *
     * @param buf The destination byte array.
     * @param offset Byte offset to write to.
     * @param value The value to write (only the lowest 24 bits are used).
     */
    private fun writeU24(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value.ushr(8) and 0xFF).toByte()
        buf[offset + 2] = (value.ushr(16) and 0xFF).toByte()
    }
}
