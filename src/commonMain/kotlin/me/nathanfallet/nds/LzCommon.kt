package me.nathanfallet.nds

/**
 * Shared internals for LZ-family codecs (LZSS/LZ10 and LZ11).
 *
 * Not part of the public API.
 */
internal object LzCommon {

    /**
     * Greedy match search: scans backwards from [pos] up to [maxDist] bytes and
     * finds the longest run of at least [minMatch] bytes (capped at [maxMatch]).
     *
     * Modular window indexing (`start + len % dist`) lets the search naturally
     * produce overlapping (run-length style) back-references.
     *
     * @param data The input data buffer.
     * @param pos Current write position (start of the lookahead).
     * @param end Exclusive end of the input data.
     * @param minMatch Minimum match length to consider a back-reference worthwhile.
     * @param maxMatch Maximum match length to search for.
     * @param maxDist Maximum look-back distance.
     * @return `Pair(matchLength, matchDistance)`; `matchLength == 0` if no suitable match was found.
     */
    fun findBestMatch(
        data: ByteArray,
        pos: Int,
        end: Int,
        minMatch: Int,
        maxMatch: Int,
        maxDist: Int,
    ): Pair<Int, Int> {
        val windowStart = maxOf(0, pos - maxDist)
        var bestLen = 0
        var bestDist = 0

        for (start in windowStart until pos) {
            val dist = pos - start
            val limit = minOf(maxMatch, end - pos)
            var len = 0
            while (len < limit && data[start + len % dist] == data[pos + len]) len++
            if (len > bestLen) {
                bestLen = len
                bestDist = dist
                if (bestLen == maxMatch) break
            }
        }

        return Pair(if (bestLen >= minMatch) bestLen else 0, bestDist)
    }

    /**
     * Copies [len] bytes from `output[dst - dist]` to `output[dst]`, one byte at a
     * time so that overlapping runs (`dist < len`) are handled correctly.
     * Stops early if the write would exceed [limit].
     *
     * @param output The output buffer (read and written in-place).
     * @param dst Current write position in [output].
     * @param dist Back-reference distance (number of bytes to look back).
     * @param len Number of bytes to copy.
     * @param limit Exclusive upper bound on the write position.
     * @return The new [dst] value after the copy.
     */
    fun copyMatch(output: ByteArray, dst: Int, dist: Int, len: Int, limit: Int): Int {
        val copyEnd = minOf(dst + len, limit)
        val src = dst - dist
        for (i in 0 until copyEnd - dst) output[dst + i] = output[src + i]
        return copyEnd
    }

    // -------------------------------------------------------------------------
    // Shared binary helpers
    // -------------------------------------------------------------------------

    fun readU24(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
                ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                ((buf[offset + 2].toInt() and 0xFF) shl 16)

    fun writeU24(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value ushr 8 and 0xFF).toByte()
        buf[offset + 2] = (value ushr 16 and 0xFF).toByte()
    }
}
