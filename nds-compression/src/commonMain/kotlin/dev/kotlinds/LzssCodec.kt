package dev.kotlinds

/**
 * LZ10 / LZSS codec for Nintendo DS files.
 *
 * This is the "Type 0x10" variant used in many DS ROMs and NARC archives.
 * The format is a standard LZSS stream with a 4-byte header:
 *
 * ```
 * Byte 0      : 0x10  (magic / compression type)
 * Bytes 1–3   : uncompressed size as a 24-bit little-endian integer
 * Bytes 4+    : compressed data
 * ```
 *
 * Each group of up to 8 symbols is preceded by a flag byte whose bits are
 * read MSB-first.  A 0-bit means a literal byte follows; a 1-bit means a
 * 2-byte back-reference follows:
 *
 * ```
 * [LLLL DDDD] [DDDDDDDD]
 *   L = (match length - 3)  stored in the high 4 bits   → lengths  3–18
 *   D = (match distance - 1) stored in the low 12 bits  → distance 1–4096
 * ```
 */
object LzssCodec {

    private const val MAGIC = 0x10
    private const val MAX_DIST = 4096
    private const val MAX_LEN = 18
    private const val MIN_LEN = 3

    // -------------------------------------------------------------------------
    // Decompress
    // -------------------------------------------------------------------------

    /**
     * Decompresses an LZ10-encoded buffer.
     *
     * @param input The compressed data, starting with the 0x10 magic byte.
     * @return The original uncompressed data.
     * @throws IllegalArgumentException if the magic byte is not 0x10.
     */
    fun decompress(input: ByteArray): ByteArray {
        require(input.size >= 4) { "LZSS: input too short to contain a header" }
        require(input[0].toInt() and 0xFF == MAGIC) {
            "LZSS: invalid magic byte 0x${(input[0].toInt() and 0xFF).toString(16).uppercase()} (expected 0x10)"
        }

        val uncompressedSize = LzCommon.readU24(input, 1)
        val output = ByteArray(uncompressedSize)

        var src = 4
        var dst = 0

        while (dst < uncompressedSize) {
            if (src >= input.size) break
            val flags = input[src++].toInt() and 0xFF

            for (bit in 7 downTo 0) {
                if (dst >= uncompressedSize) break

                if (flags and (1 shl bit) == 0) {
                    if (src >= input.size) break
                    output[dst++] = input[src++]
                } else {
                    if (src + 1 >= input.size) break
                    val hi = input[src++].toInt() and 0xFF
                    val lo = input[src++].toInt() and 0xFF
                    val length = (hi ushr 4) + MIN_LEN
                    val distance = (((hi and 0x0F) shl 8) or lo) + 1
                    dst = LzCommon.copyMatch(output, dst, distance, length, uncompressedSize)
                }
            }
        }

        return output
    }

    // -------------------------------------------------------------------------
    // Compress
    // -------------------------------------------------------------------------

    /**
     * Compresses [input] using the LZ10 / LZSS format.
     *
     * @param input The raw data to compress.
     * @return A valid LZ10 stream, starting with the 0x10 magic byte.
     */
    fun compress(input: ByteArray): ByteArray {
        val uncompressedSize = input.size
        val buf = ByteArray(4 + uncompressedSize + (uncompressedSize + 7) / 8)

        buf[0] = MAGIC.toByte()
        LzCommon.writeU24(buf, 1, uncompressedSize)

        var src = 0
        var dst = 4

        while (src < uncompressedSize) {
            val flagPos = dst++
            var flags = 0

            for (bit in 7 downTo 0) {
                if (src >= uncompressedSize) break

                val (matchLen, matchDist) = LzCommon.findBestMatch(
                    input, src, uncompressedSize, MIN_LEN, MAX_LEN, MAX_DIST
                )

                if (matchLen >= MIN_LEN) {
                    flags = flags or (1 shl bit)
                    val encLen = matchLen - MIN_LEN  // 0–15
                    val encDist = matchDist - 1         // 0–4095
                    buf[dst++] = ((encLen shl 4) or (encDist ushr 8)).toByte()
                    buf[dst++] = (encDist and 0xFF).toByte()
                    src += matchLen
                } else {
                    buf[dst++] = input[src++]
                }
            }

            buf[flagPos] = flags.toByte()
        }

        return buf.copyOf(dst)
    }
}
