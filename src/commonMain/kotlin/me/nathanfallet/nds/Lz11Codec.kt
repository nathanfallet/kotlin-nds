package me.nathanfallet.nds

/**
 * LZ11 (LZX) codec for Nintendo DS files.
 *
 * Identified by magic byte `0x11`.  Uses an extended LZ back-reference scheme
 * with three token tiers that allow copy lengths well beyond the 18-byte cap of
 * LZ10, while sharing the same 4096-byte sliding window.
 *
 * Header (4 bytes, little-endian):
 * ```
 * Byte 0      : 0x11
 * Bytes 1–3   : uncompressed size, 24-bit LE
 * ```
 *
 * Flag bytes are processed MSB-first (same as LZ10).  A 1-bit introduces a
 * back-reference token; a 0-bit introduces a literal byte.
 *
 * Back-reference token tiers (chosen by the high nibble of the first token byte):
 * ```
 * hi >= 2  → Tier 1 (2 bytes): len = hi + 1  (3–16),  dist = (lo12) + 1
 * hi == 0  → Tier 2 (3 bytes): len = (b0_lo4 << 4 | b1_hi4) + 17   (17–272)
 * hi == 1  → Tier 3 (4 bytes): len = (b0_lo4 << 12 | b1 << 4 | b2_hi4) + 273
 * ```
 * Distance is always stored as (dist − 1) in the low 12 bits of the last two
 * bytes of the token.
 */
object Lz11Codec {

    private const val MAGIC = 0x11
    private const val MAX_DIST = 4096
    private const val MIN_LEN = 3

    // Tier caps – compress will not search beyond Tier-2 for speed; Tier-3 available
    private const val MAX_LEN_TIER1 = 16
    private const val MAX_LEN_TIER2 = 272
    private const val MAX_LEN_TIER3 = 65808  // (0xF shl 12 | 0xFF shl 4 | 0xF) + 273

    // -------------------------------------------------------------------------
    // Decompress
    // -------------------------------------------------------------------------

    /**
     * Decompresses an LZ11-encoded buffer.
     *
     * @param input The compressed data, starting with the 0x11 magic byte.
     * @return The original uncompressed data.
     * @throws IllegalArgumentException if the magic byte is not 0x11.
     */
    fun decompress(input: ByteArray): ByteArray {
        require(input.size >= 4) { "LZ11: input too short for header" }
        require(input[0].toInt() and 0xFF == MAGIC) {
            "LZ11: invalid magic byte 0x${(input[0].toInt() and 0xFF).toString(16).uppercase()} (expected 0x11)"
        }

        val uncompressedSize = LzCommon.readU24(input, 1)
        if (uncompressedSize == 0) return ByteArray(0)

        val output = ByteArray(uncompressedSize)
        var src = 4
        var dst = 0

        while (dst < uncompressedSize) {
            if (src >= input.size) break
            val flags = input[src++].toInt() and 0xFF

            for (bit in 7 downTo 0) {
                if (dst >= uncompressedSize) break

                if (flags and (1 shl bit) == 0) {
                    // Literal
                    if (src >= input.size) break
                    output[dst++] = input[src++]
                } else {
                    // Back-reference token
                    if (src >= input.size) break
                    val b0 = input[src++].toInt() and 0xFF
                    val hi = b0 ushr 4

                    val len: Int
                    val dist: Int

                    when {
                        hi >= 2 -> {
                            // Tier 1: 2 bytes total
                            if (src >= input.size) break
                            val b1 = input[src++].toInt() and 0xFF
                            len = hi + 1
                            dist = (((b0 and 0xF) shl 8) or b1) + 1
                        }

                        hi == 1 -> {
                            // Tier 3: 4 bytes total
                            if (src + 2 >= input.size) break
                            val b1 = input[src++].toInt() and 0xFF
                            val b2 = input[src++].toInt() and 0xFF
                            val b3 = input[src++].toInt() and 0xFF
                            len = (((b0 and 0xF) shl 12) or (b1 shl 4) or (b2 ushr 4)) + 273
                            dist = (((b2 and 0xF) shl 8) or b3) + 1
                        }

                        else -> {
                            // Tier 2: 3 bytes total (hi == 0)
                            if (src + 1 >= input.size) break
                            val b1 = input[src++].toInt() and 0xFF
                            val b2 = input[src++].toInt() and 0xFF
                            len = (((b0 and 0xF) shl 4) or (b1 ushr 4)) + 17
                            dist = (((b1 and 0xF) shl 8) or b2) + 1
                        }
                    }

                    dst = LzCommon.copyMatch(output, dst, dist, len, uncompressedSize)
                }
            }
        }

        return output
    }

    // -------------------------------------------------------------------------
    // Compress
    // -------------------------------------------------------------------------

    /**
     * Compresses [input] using LZ11.
     *
     * Uses greedy matching via [LzCommon.findBestMatch].  Selects the shortest
     * token tier that can encode the match length.
     *
     * @param input The raw data to compress.
     * @return A valid LZ11 stream, starting with the 0x11 magic byte.
     */
    fun compress(input: ByteArray): ByteArray {
        val uncompressedSize = input.size
        // Upper bound: header + flag byte per 8 symbols + up to 4 bytes per symbol
        val buf = ByteArray(4 + uncompressedSize + (uncompressedSize + 7) / 8 * 5)

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
                    input, src, uncompressedSize, MIN_LEN, MAX_LEN_TIER2, MAX_DIST
                )

                if (matchLen >= MIN_LEN) {
                    flags = flags or (1 shl bit)
                    val d = matchDist - 1  // stored distance (0-based)
                    val l = matchLen

                    when {
                        l <= MAX_LEN_TIER1 -> {
                            // Tier 1: 2 bytes.  hi4 = l-1, lo12 = d
                            buf[dst++] = (((l - 1) shl 4) or (d ushr 8)).toByte()
                            buf[dst++] = (d and 0xFF).toByte()
                        }

                        l <= MAX_LEN_TIER2 -> {
                            // Tier 2: 3 bytes.  first byte hi4 = 0
                            val lf = l - 17
                            buf[dst++] = ((lf ushr 4) and 0x0F).toByte()
                            buf[dst++] = (((lf and 0x0F) shl 4) or (d ushr 8)).toByte()
                            buf[dst++] = (d and 0xFF).toByte()
                        }

                        else -> {
                            // Tier 3: 4 bytes.  first byte hi4 = 1
                            val lf = l - 273
                            buf[dst++] = (0x10 or ((lf ushr 12) and 0x0F)).toByte()
                            buf[dst++] = ((lf ushr 4) and 0xFF).toByte()
                            buf[dst++] = (((lf and 0x0F) shl 4) or (d ushr 8)).toByte()
                            buf[dst++] = (d and 0xFF).toByte()
                        }
                    }
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
