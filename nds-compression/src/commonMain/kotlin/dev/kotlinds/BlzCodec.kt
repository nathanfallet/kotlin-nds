package dev.kotlinds

/**
 * Bottom-LZ codec for Nintendo DS ARM9 binaries and overlay files.
 *
 * Ported from the CUE BLZ reference implementation (blz.c) and the simpler
 * arm9dec variant used in the original C++ randomizer.
 */
object BlzCodec {

    private const val BLZ_SHIFT = 1
    private const val BLZ_MASK = 0x80
    private const val BLZ_THRESHOLD = 2
    private const val BLZ_N = 0x1002   // max look-back distance
    private const val BLZ_F = 0x12     // max match length

    // -------------------------------------------------------------------------
    // Decompress
    // -------------------------------------------------------------------------

    /**
     * Decompresses a BLZ-encoded buffer.
     *
     * @param input The BLZ-encoded byte array (footer-based format, no magic byte).
     * @return The decompressed data, or a copy of [input] if it carries
     *   the "not encoded" marker (`inc_len == 0`).
     * @throws IllegalArgumentException if the BLZ footer is malformed.
     */
    fun decompress(input: ByteArray): ByteArray {
        val pakLen = input.size
        val incLen = readU32(input, pakLen - 4)

        if (incLen == 0L) return input.copyOf()

        val hdrLen = input[pakLen - 5].toInt() and 0xFF
        require(hdrLen in 0x08..0x0B) { "BLZ: bad header length 0x${hdrLen.toString(16)}" }

        // enc_len encodes (compressedBytes + hdrLen) in the low 24 bits
        val encLen = readU32(input, pakLen - 8).toInt() and 0x00FFFFFF
        val decLen = pakLen - encLen          // literal prefix
        val compLen = encLen - hdrLen         // actual compressed bytes
        val rawLen = decLen + encLen + incLen.toInt()

        val raw = ByteArray(rawLen)

        // 1. Copy literal prefix verbatim
        input.copyInto(raw, 0, 0, decLen)

        // 2. Extract and invert the compressed bytes
        val comp = input.copyOfRange(decLen, decLen + compLen).also { invertRange(it, 0, it.size) }

        // 3. LZ decode
        var pak = 0
        val pakEnd = compLen
        var rawPos = decLen
        val rawEnd = rawLen

        var flags = 0
        var mask = 0

        outer@ while (rawPos < rawEnd) {
            mask = mask ushr BLZ_SHIFT
            if (mask == 0) {
                if (pak >= pakEnd) break
                flags = comp[pak++].toInt() and 0xFF
                mask = BLZ_MASK
            }

            if (flags and mask == 0) {
                // literal byte
                if (pak >= pakEnd) break
                raw[rawPos++] = comp[pak++]
            } else {
                // back-reference
                if (pak + 1 >= pakEnd) break
                val hi = comp[pak++].toInt() and 0xFF
                val lo = comp[pak++].toInt() and 0xFF
                val token = (hi shl 8) or lo
                var len = (token ushr 12) + BLZ_THRESHOLD + 1
                val dist = (token and 0xFFF) + 3
                if (rawPos + len > rawEnd) len = rawEnd - rawPos
                repeat(len) { i -> raw[rawPos + i] = raw[rawPos + i - dist] }
                rawPos += len
            }
        }

        // 4. Invert the decoded portion (everything after the literal prefix)
        invertRange(raw, decLen, rawLen - decLen)

        return raw
    }

    // -------------------------------------------------------------------------
    // Compress
    // -------------------------------------------------------------------------

    /**
     * Compresses [input] using BLZ.
     *
     * @param input The raw binary data to compress.
     * @param arm9 When `true` the first 0x4000 bytes are left uncompressed
     *   (required for DS ARM9 secure-area binaries). The binary is validated:
     *   magic bytes at 0x00/0x04/0x08 must equal `0xE7FFDEFF`, the half-word at
     *   0x0C must be `0xDEFF`, and the word at 0x7FE must be `0`. The secure-area
     *   CRC-16 (bytes 0x10–0x7FF) is recalculated and written to 0x0E before compression.
     * @return The BLZ-encoded byte array. Returns a "not encoded" marker if compression
     *   would not reduce the size.
     * @throws IllegalArgumentException if [arm9] is `true` but the binary fails validation.
     */
    fun compress(input: ByteArray, arm9: Boolean = false): ByteArray {
        val rawLen = input.size

        val rawBuf = input.copyOf()

        // ARM9 secure-area validation + CRC update (mirrors CUE blz.c behaviour)
        if (arm9) {
            require(rawLen >= 0x4000) {
                "BLZ: ARM9 binary too short ($rawLen bytes, need at least 0x4000)"
            }
            require(readU32(rawBuf, 0x00) == 0xE7FFDEFF) {
                "BLZ: ARM9 secure-area magic missing at +0x00"
            }
            require(readU32(rawBuf, 0x04) == 0xE7FFDEFF) {
                "BLZ: ARM9 secure-area magic missing at +0x04"
            }
            require(readU32(rawBuf, 0x08) == 0xE7FFDEFF) {
                "BLZ: ARM9 secure-area magic missing at +0x08"
            }
            require(readU16(rawBuf, 0x0C) == 0xDEFF) {
                "BLZ: ARM9 secure-area half-word marker missing at +0x0C"
            }
            require(readU16(rawBuf, 0x7FE) == 0) {
                "BLZ: ARM9 end-of-secure-area marker non-zero at +0x7FE"
            }
            // Recalculate and write the CRC16 of the secure area (bytes 0x10..0x7FF)
            val crc = crc16(rawBuf, 0x10, 0x7F0)
            writeU16(rawBuf, 0x0E, crc)
        }

        // How many bytes (from the END of the inverted data) to actually compress
        val rawNew = if (arm9 && rawLen >= 0x4000) rawLen - 0x4000 else rawLen

        val pakBufMax = rawLen + ((rawLen + 7) / 8) + 11
        val pakBuf = ByteArray(pakBufMax)
        invertRange(rawBuf, 0, rawLen)          // work on inverted data

        var pak = 0
        var raw = 0
        val rawEnd = rawNew

        var flgPos = 0
        var mask = 0
        var pakTmp = 0
        var rawTmp = rawLen

        while (raw < rawEnd) {
            mask = mask ushr BLZ_SHIFT
            if (mask == 0) {
                flgPos = pak
                pakBuf[pak++] = 0
                mask = BLZ_MASK
            }

            // Search for the best back-reference
            val maxDist = minOf(raw, BLZ_N)
            var lenBest = BLZ_THRESHOLD
            var posBest = 0
            for (dist in 3..maxDist) {
                var len = 0
                while (len < BLZ_F && raw + len < rawEnd && len < dist &&
                    rawBuf[raw + len] == rawBuf[raw + len - dist]
                ) len++
                if (len > lenBest) {
                    posBest = dist
                    lenBest = len
                    if (lenBest == BLZ_F) break
                }
            }

            pakBuf[flgPos] = (pakBuf[flgPos].toInt() shl 1).toByte()
            if (lenBest > BLZ_THRESHOLD) {
                raw += lenBest
                pakBuf[flgPos] = (pakBuf[flgPos].toInt() or 1).toByte()
                pakBuf[pak++] = (((lenBest - (BLZ_THRESHOLD + 1)) shl 4) or ((posBest - 3) ushr 8)).toByte()
                pakBuf[pak++] = ((posBest - 3) and 0xFF).toByte()
            } else {
                pakBuf[pak++] = rawBuf[raw++]
            }

            // Track the best split point (smallest combined output so far)
            val combined = pak + rawLen - raw
            if (combined < pakTmp + rawTmp) {
                pakTmp = pak
                rawTmp = rawLen - raw
            }
        }

        // Shift out remaining flag bits
        while (mask != 0 && mask != 1) {
            mask = mask ushr BLZ_SHIFT
            pakBuf[flgPos] = (pakBuf[flgPos].toInt() shl 1).toByte()
        }

        val pakLen = pak

        invertRange(rawBuf, 0, rawLen)              // restore original order
        invertRange(pakBuf, 0, pakLen)              // invert compressed stream

        // Decide whether compression is worthwhile
        val compressedSize = ((pakTmp + rawTmp + 3) and -4) + 8
        if (pakTmp == 0 || rawLen + 4 < compressedSize) {
            return buildUncompressed(rawBuf, rawLen)
        }

        return buildCompressed(rawBuf, pakBuf, rawLen, pakLen, pakTmp, rawTmp)
    }

    // -------------------------------------------------------------------------
    // Output builders
    // -------------------------------------------------------------------------

    /**
     * Produces a "not encoded" BLZ output: raw bytes padded to 4-byte alignment,
     * followed by four zero bytes (the `inc_len == 0` sentinel).
     *
     * @param raw The source byte array.
     * @param rawLen Number of bytes from [raw] to include.
     * @return The uncompressed BLZ output.
     */
    private fun buildUncompressed(raw: ByteArray, rawLen: Int): ByteArray {
        val padded = (rawLen + 3) and -4
        val out = ByteArray(padded + 4)
        raw.copyInto(out, 0, 0, rawLen)
        // trailer uint32 = 0 (already zero)
        return out
    }

    /**
     * Assembles the final compressed output:
     *   [ literal-prefix (rawTmp bytes) ]
     *   [ compressed data (pakTmp bytes) ]
     *   [ 0xFF padding to 4-byte alignment ]
     *   [ 3-byte total_size | 1-byte hdr_len | 4-byte ext_size ]
     */
    private fun buildCompressed(
        rawBuf: ByteArray,
        pakBuf: ByteArray,
        rawLen: Int,
        pakLen: Int,
        pakTmp: Int,
        rawTmp: Int,
    ): ByteArray {
        val padding = (4 - (rawTmp + pakTmp) % 4) % 4
        val hdrLen = 8 + padding
        val outLen = rawTmp + pakTmp + hdrLen

        val out = ByteArray(outLen)

        // Literal prefix
        rawBuf.copyInto(out, 0, 0, rawTmp)

        // Compressed data: last pakTmp bytes of pakBuf (which is already inverted)
        pakBuf.copyInto(out, rawTmp, pakLen - pakTmp, pakLen)

        // 0xFF padding
        var pos = rawTmp + pakTmp
        repeat(padding) { out[pos++] = 0xFF.toByte() }

        // Header:  total_size (3 bytes) | hdr_len (1 byte) | ext_size (4 bytes)
        //   total_size = pakTmp + hdrLen
        //   ext_size   = incLen - hdrLen,  where incLen = rawLen - pakTmp - rawTmp
        val totalSize = pakTmp + hdrLen
        val incLen = rawLen - pakTmp - rawTmp
        writeU24(out, pos, totalSize.toLong()); pos += 3
        out[pos++] = hdrLen.toByte()
        writeU32(out, pos, (incLen - hdrLen).toLong())

        return out
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun invertRange(buf: ByteArray, offset: Int, length: Int) {
        var l = offset
        var r = offset + length - 1
        while (l < r) {
            val tmp = buf[l]; buf[l++] = buf[r]; buf[r--] = tmp
        }
    }

    private fun readU16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFF) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                ((buf[offset + 2].toLong() and 0xFF) shl 16) or
                ((buf[offset + 3].toLong() and 0xFF) shl 24)

    private fun writeU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value ushr 8 and 0xFF).toByte()
    }

    /**
     * CRC-16 (polynomial 0x8408, initial value 0xFFFF) used in DS ARM9 secure area.
     * Computes CRC of [len] bytes starting at [buf][offset].
     */
    private fun crc16(buf: ByteArray, offset: Int, len: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + len) {
            var b = buf[i].toInt() and 0xFF
            repeat(8) {
                crc = if ((crc xor b) and 1 != 0) (crc ushr 1) xor 0x8408
                else crc ushr 1
                b = b ushr 1
            }
        }
        return crc and 0xFFFF
    }

    private fun writeU24(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value.ushr(8) and 0xFF).toByte()
        buf[offset + 2] = (value.ushr(16) and 0xFF).toByte()
    }

    private fun writeU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value.ushr(8) and 0xFF).toByte()
        buf[offset + 2] = (value.ushr(16) and 0xFF).toByte()
        buf[offset + 3] = (value.ushr(24) and 0xFF).toByte()
    }
}
