package dev.kotlinds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [SdatStrmFile.toWav] and [SdatSwarFile.toWavList].
 *
 * All test STRM and SWAR binaries are constructed programmatically — no real NDS files
 * are required. Tests cover PCM8, PCM16, and IMA-ADPCM wave types, WAV header field
 * correctness, and decoded sample values for known inputs.
 *
 * NDS SWAV word-alignment note: the loopStart and loopLen fields inside a SWAV header are
 * stored in units of 32-bit words (4 bytes). Therefore sample data sizes must be multiples
 * of 4 bytes for exact round-tripping; tests use sample counts chosen to satisfy this.
 */
class SdatAudioTest {

    // =========================================================================
    // Low-level write helpers (self-contained, no production dependency)
    // =========================================================================

    private fun wu32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun wu16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun ru16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun ru32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun rs16(buf: ByteArray, off: Int): Int {
        val v = ru16(buf, off)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    // =========================================================================
    // STRM binary builder
    // =========================================================================

    /**
     * Builds a minimal but valid STRM binary containing exactly one block.
     *
     * The produced STRM has numBlocks=1, with the last-block fields equal to the regular
     * block fields. [blockData] must already be encoded in the given [waveType].
     * For ADPCM, [blockData] includes the 4-byte per-channel block header(s).
     *
     * @param waveType        0=PCM8, 1=PCM16, 2=ADPCM.
     * @param channels        Channel count.
     * @param sampleRate      Sample rate in Hz.
     * @param numSamples      Total samples per channel.
     * @param blockData       Raw encoded data for all channels (channel-0 || channel-1 || …).
     * @param lenBlockPerChan Byte length of one channel's portion of [blockData].
     * @return A valid STRM byte array.
     */
    private fun buildStrm(
        waveType: Int,
        channels: Int,
        sampleRate: Int,
        numSamples: Int,
        blockData: ByteArray,
        lenBlockPerChan: Int,
    ): ByteArray {
        // Place audio data right after the minimum 0x68-byte STRM header.
        val dataOffset = 0x68
        val totalSize = dataOffset + blockData.size
        val buf = ByteArray(totalSize)

        // Main STRM header
        buf[0x00] = 'S'.code.toByte(); buf[0x01] = 'T'.code.toByte()
        buf[0x02] = 'R'.code.toByte(); buf[0x03] = 'M'.code.toByte()
        wu16(buf, 0x04, 0xFEFF)             // BOM
        buf[0x06] = 0x01; buf[0x07] = 0x00 // version
        wu32(buf, 0x08, totalSize)          // file size
        wu16(buf, 0x0C, 0x0010)             // header size field
        buf[0x0E] = 0x01; buf[0x0F] = 0x00 // numSections in main header

        // HEAD block at 0x10
        buf[0x10] = 'H'.code.toByte(); buf[0x11] = 'E'.code.toByte()
        buf[0x12] = 'A'.code.toByte(); buf[0x13] = 'D'.code.toByte()
        wu32(buf, 0x14, 0x58)               // HEAD block size

        buf[0x18] = waveType.toByte()
        buf[0x19] = 0x00                    // hasLoop = false
        buf[0x1A] = channels.toByte()
        buf[0x1B] = 0x00                    // padding
        wu16(buf, 0x1C, sampleRate)
        wu16(buf, 0x1E, 0)                  // time
        wu32(buf, 0x20, 0)                  // loopStart
        wu32(buf, 0x24, numSamples)         // numSamples (total per channel)
        wu32(buf, 0x28, dataOffset)         // dataOffset (absolute)
        wu32(buf, 0x2C, 1)                  // numBlocks = 1
        wu32(buf, 0x30, lenBlockPerChan)    // lenBlockPerChan
        wu32(buf, 0x34, numSamples)         // samplesPerBlock
        wu32(buf, 0x38, lenBlockPerChan)    // lenLastBlockPerChan (same, single block)
        wu32(buf, 0x3C, numSamples)         // samplesPerLastBlock

        blockData.copyInto(buf, dataOffset)
        return buf
    }

    // =========================================================================
    // SWAR binary builder
    // =========================================================================

    /**
     * Builds a minimal SWAR binary containing the given SWAV entries.
     *
     * SWAR layout used:
     * - 0x00–0x17: SWAR file header (magic, BOM, sizes, DATA block magic)
     * - 0x18–0x37: DATA block body reserved (zeroed)
     * - 0x38: nEntries (u32)
     * - 0x3C: absolute offset table (nEntries × u32)
     * - entries follow immediately after the table
     *
     * The total file size is padded to at least 0x3C bytes so the nEntries read passes.
     *
     * @param swavEntries List of (swavHeader, sampleData) pairs; swavHeader must be 12 bytes.
     * @return A valid SWAR byte array.
     */
    private fun buildSwar(swavEntries: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val nEntries = swavEntries.size

        // Absolute offset of the first SWAV entry in the file
        val firstEntryOffset = 0x3C + nEntries * 4

        // Compute per-entry absolute offsets
        val entryOffsets = IntArray(nEntries)
        var cursor = firstEntryOffset
        for (i in swavEntries.indices) {
            entryOffsets[i] = cursor
            cursor += swavEntries[i].first.size + swavEntries[i].second.size
        }
        // Ensure at least 0x3C bytes so the nEntries read at 0x38 works for empty SWARs
        val totalSize = maxOf(cursor, 0x3C)

        val buf = ByteArray(totalSize)

        // SWAR file header (0x00–0x0F)
        buf[0x00] = 'S'.code.toByte(); buf[0x01] = 'W'.code.toByte()
        buf[0x02] = 'A'.code.toByte(); buf[0x03] = 'R'.code.toByte()
        wu16(buf, 0x04, 0xFEFF)
        buf[0x06] = 0x01; buf[0x07] = 0x00
        wu32(buf, 0x08, totalSize)
        wu16(buf, 0x0C, 0x0010)
        buf[0x0E] = 0x01; buf[0x0F] = 0x00

        // DATA block header (0x10–0x17)
        buf[0x10] = 'D'.code.toByte(); buf[0x11] = 'A'.code.toByte()
        buf[0x12] = 'T'.code.toByte(); buf[0x13] = 'A'.code.toByte()
        wu32(buf, 0x14, totalSize - 0x10)

        // nEntries at 0x38
        wu32(buf, 0x38, nEntries)

        // Offset table at 0x3C
        for (i in swavEntries.indices) {
            wu32(buf, 0x3C + i * 4, entryOffsets[i])
        }

        // Write each SWAV header + sample data
        for (i in swavEntries.indices) {
            val (header, samples) = swavEntries[i]
            header.copyInto(buf, entryOffsets[i])
            samples.copyInto(buf, entryOffsets[i] + header.size)
        }

        return buf
    }

    /**
     * Builds a 12-byte SWAV header for a PCM8 or PCM16 sample.
     *
     * The loopLen field is computed as `totalBytes / 4` (integer floor), which means
     * [numSamples] must satisfy `numSamples * bytesPerSample % 4 == 0` for the decoder to
     * decode exactly [numSamples] samples. Callers are responsible for this alignment:
     * PCM8 → multiples of 4 samples; PCM16 → multiples of 2 samples.
     *
     * @param waveType   0=PCM8, 1=PCM16.
     * @param sampleRate Sample rate in Hz.
     * @param numSamples Number of PCM samples (must be 4-byte aligned as above).
     * @return The 12-byte SWAV header.
     */
    private fun buildSwavHeader(waveType: Int, sampleRate: Int, numSamples: Int): ByteArray {
        val bytesPerSample = if (waveType == 0) 1 else 2
        val totalBytes = numSamples * bytesPerSample
        // loopStart = 0 words; loopLen = totalBytes / 4 words (exact, no rounding)
        val loopLenWords = totalBytes / 4
        val hdr = ByteArray(12)
        hdr[0] = waveType.toByte()
        hdr[1] = 0x00                   // hasLoop = false
        wu16(hdr, 2, sampleRate)
        wu16(hdr, 4, 0)                 // time
        wu16(hdr, 6, 0)                 // loopStart in words = 0
        wu32(hdr, 8, loopLenWords)      // loopLen in words
        return hdr
    }

    /**
     * Builds a 12-byte SWAV header for an IMA-ADPCM SWAV entry.
     *
     * The ADPCM data layout expected by the decoder:
     * - 4-byte block header (s16 LE initialSample, u8 stepIndex, u8 padding)
     * - nibble data follows (low nibble first within each byte)
     *
     * loopStart = 1 word (= 4 bytes, pointing past the block header).
     * loopLen = number of nibble-data bytes / 4 (must be integer, i.e. nibbleBytes % 4 == 0).
     * Callers must ensure the nibble-data byte count is a multiple of 4.
     *
     * @param sampleRate  Sample rate in Hz.
     * @param numSamples  Number of ADPCM samples (= number of nibbles; must be even).
     * @return The 12-byte SWAV header.
     */
    private fun buildSwavAdpcmHeader(sampleRate: Int, numSamples: Int): ByteArray {
        // numSamples nibbles → numSamples/2 bytes; must be multiple of 4
        val nibbleBytes = numSamples / 2
        val loopLenWords = nibbleBytes / 4
        val hdr = ByteArray(12)
        hdr[0] = 2.toByte()             // waveType = ADPCM
        hdr[1] = 0x00                   // hasLoop = false
        wu16(hdr, 2, sampleRate)
        wu16(hdr, 4, 0)                 // time
        wu16(hdr, 6, 1)                 // loopStart in words = 1 (skip 4-byte block header)
        wu32(hdr, 8, loopLenWords)      // loopLen in words
        return hdr
    }

    // =========================================================================
    // WAV header verification helper
    // =========================================================================

    /**
     * Asserts that [wav] is a valid 16-bit PCM WAV with the expected metadata.
     *
     * @param wav        The WAV byte array to verify.
     * @param channels   Expected channel count.
     * @param sampleRate Expected sample rate.
     * @param numSamples Expected sample count per channel.
     */
    private fun assertWavHeader(wav: ByteArray, channels: Int, sampleRate: Int, numSamples: Int) {
        assertTrue(wav.size >= 44, "WAV too short: ${wav.size}")
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals("fmt ", wav.decodeToString(12, 16))
        assertEquals(16, ru32(wav, 16), "fmt chunk size")
        assertEquals(1, ru16(wav, 20), "PCM format tag")
        assertEquals(channels, ru16(wav, 22), "channels")
        assertEquals(sampleRate, ru32(wav, 24), "sample rate")
        val expectedByteRate = sampleRate * channels * 2
        assertEquals(expectedByteRate, ru32(wav, 28), "byte rate")
        assertEquals(channels * 2, ru16(wav, 32), "block align")
        assertEquals(16, ru16(wav, 34), "bits per sample")
        assertEquals("data", wav.decodeToString(36, 40))
        val dataBytes = numSamples * channels * 2
        assertEquals(dataBytes, ru32(wav, 40), "data chunk size")
        assertEquals(44 + dataBytes, wav.size, "total WAV size")
    }

    // =========================================================================
    // STRM tests — PCM8
    // =========================================================================

    /** PCM8 STRM: WAV header fields are all correct. */
    @Test
    fun `STRM PCM8 toWav returns correct WAV header`() {
        val samples = byteArrayOf(0x00, 0x40, 0x80.toByte(), 0xFF.toByte())
        val strm = buildStrm(
            waveType = 0, channels = 1, sampleRate = 8000,
            numSamples = 4, blockData = samples, lenBlockPerChan = 4,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        assertWavHeader(wav, channels = 1, sampleRate = 8000, numSamples = 4)
    }

    /** PCM8 STRM: each signed byte is upsampled to s16 by multiplying by 256. */
    @Test
    fun `STRM PCM8 toWav upsamples to 16-bit correctly`() {
        // signed PCM8: 0, 127, -128, -1
        val samples = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val strm = buildStrm(
            waveType = 0, channels = 1, sampleRate = 8000,
            numSamples = 4, blockData = samples, lenBlockPerChan = 4,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        assertEquals(0 * 256, rs16(wav, 44), "sample[0]")
        assertEquals(127 * 256, rs16(wav, 46), "sample[1]")
        assertEquals(-128 * 256, rs16(wav, 48), "sample[2]")
        assertEquals(-1 * 256, rs16(wav, 50), "sample[3]")
    }

    // =========================================================================
    // STRM tests — PCM16
    // =========================================================================

    /** PCM16 STRM: WAV header fields are all correct. */
    @Test
    fun `STRM PCM16 toWav returns correct WAV header`() {
        // 4 samples × 2 bytes = 8 bytes block
        val samples = ByteArray(8)
        wu16(samples, 0, 1000); wu16(samples, 2, 0xFC18)  // -1000 as unsigned u16
        wu16(samples, 4, 32767); wu16(samples, 6, 0)
        val strm = buildStrm(
            waveType = 1, channels = 1, sampleRate = 44100,
            numSamples = 4, blockData = samples, lenBlockPerChan = 8,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        assertWavHeader(wav, channels = 1, sampleRate = 44100, numSamples = 4)
    }

    /** PCM16 STRM: sample values are preserved exactly. */
    @Test
    fun `STRM PCM16 toWav preserves sample values`() {
        val values = shortArrayOf(1000, -1000, 32767, -32768, 0, 256, -256, 1)
        val samples = ByteArray(values.size * 2)
        for (i in values.indices) {
            val v = values[i].toInt()
            samples[i * 2] = (v and 0xFF).toByte()
            samples[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        val strm = buildStrm(
            waveType = 1, channels = 1, sampleRate = 22050,
            numSamples = values.size, blockData = samples, lenBlockPerChan = samples.size,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        for (i in values.indices) {
            assertEquals(values[i].toInt(), rs16(wav, 44 + i * 2), "sample[$i]")
        }
    }

    /** Stereo PCM16 STRM: channels and interleaving are correct. */
    @Test
    fun `STRM PCM16 stereo toWav has correct channel count and interleaving`() {
        // 2 channels, 2 samples per channel → output: L0 R0 L1 R1
        // Channel-0 block data: [100 LE, 200 LE], Channel-1 block data: [300 LE, 400 LE]
        val ch0 = ByteArray(4); wu16(ch0, 0, 100); wu16(ch0, 2, 200)
        val ch1 = ByteArray(4); wu16(ch1, 0, 300); wu16(ch1, 2, 400)
        val blockData = ch0 + ch1
        val strm = buildStrm(
            waveType = 1, channels = 2, sampleRate = 32000,
            numSamples = 2, blockData = blockData, lenBlockPerChan = 4,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        assertWavHeader(wav, channels = 2, sampleRate = 32000, numSamples = 2)
        assertEquals(100, rs16(wav, 44), "L0")
        assertEquals(300, rs16(wav, 46), "R0")
        assertEquals(200, rs16(wav, 48), "L1")
        assertEquals(400, rs16(wav, 50), "R1")
    }

    // =========================================================================
    // STRM tests — IMA-ADPCM
    // =========================================================================

    /** ADPCM STRM: WAV header is correct for a known all-zero block. */
    @Test
    fun `STRM ADPCM toWav returns correct WAV header`() {
        // 4-byte ADPCM block header + 4 nibble bytes = 8 samples
        val blockData = ByteArray(8)  // all zeros: header(4) + nibbles(4)
        val strm = buildStrm(
            waveType = 2, channels = 1, sampleRate = 16000,
            numSamples = 8, blockData = blockData, lenBlockPerChan = 8,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        assertWavHeader(wav, channels = 1, sampleRate = 16000, numSamples = 8)
    }

    /** ADPCM STRM: all-zero nibbles decode to all-zero samples. */
    @Test
    fun `STRM ADPCM toWav all-zero nibbles decode to zero samples`() {
        // initialSample=0, stepIndex=0, all nibbles=0
        // code=0 → diff = step>>3 = 0 (for step=7); code&8=0 → sample += 0 = 0
        // IMA_INDEX_TABLE[0] = -1 → stepIdx stays at 0 (clamped)
        val blockData = ByteArray(8)
        val strm = buildStrm(
            waveType = 2, channels = 1, sampleRate = 16000,
            numSamples = 8, blockData = blockData, lenBlockPerChan = 8,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        for (i in 0 until 8) {
            assertEquals(0, rs16(wav, 44 + i * 2), "sample[$i]")
        }
    }

    /**
     * ADPCM STRM: nibble=4 (code=0b0100) followed by nibble=0.
     *
     * Step index starts at 0, ADPCM_STEP_TABLE[0] = 7.
     * nibble=4=0b0100: diff = 7>>3(=0) + [bit0=0] + [bit1=0] + [bit2=1→+7] = 7; code&8=0 → sample=7
     * IMA_INDEX_TABLE[4]=2 → stepIdx = 2; ADPCM_STEP_TABLE[2]=9
     * nibble=0=0b0000: diff = 9>>3=1; code&8=0 → sample = 7+1 = 8
     */
    @Test
    fun `STRM ADPCM toWav nibble 4 then 0 produces expected samples`() {
        val blockData = ByteArray(8)
        // blockData[0..3] = ADPCM header: initialSample=0 LE, stepIndex=0, padding=0
        // blockData[4] = packed nibbles: low nibble=4, high nibble=0 → byte = 0x04
        blockData[4] = 0x04.toByte()
        // blockData[5..7] = 0 → remaining nibbles = 0
        val strm = buildStrm(
            waveType = 2, channels = 1, sampleRate = 16000,
            numSamples = 8, blockData = blockData, lenBlockPerChan = 8,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        assertEquals(7, rs16(wav, 44), "sample[0]: nibble=4")
        assertEquals(8, rs16(wav, 46), "sample[1]: nibble=0 with step[2]=9")
    }

    /** ADPCM STRM: nibble=8 (code=0b1000) has sign bit set, so sample decreases. */
    @Test
    fun `STRM ADPCM toWav nibble 8 decreases sample by expected amount`() {
        // code=8=0b1000: diff = 7>>3=0; code&8=1 → sample -= 0 = 0 (clamped to -32767 if needed)
        // IMA_INDEX_TABLE[8]=-1 → stepIdx stays at 0
        // Actually diff = step>>3 = 0; sample = 0 - 0 = 0, clamped to -32767 min
        // But sample is 0 - 0 = 0.
        // With nibble=9 (0b1001): diff = 7>>3(=0) + 7>>2(=1) = 1; sample = 0-1 = -1
        val blockData = ByteArray(8)
        blockData[4] = 0x09.toByte()   // low=9, high=0
        val strm = buildStrm(
            waveType = 2, channels = 1, sampleRate = 16000,
            numSamples = 8, blockData = blockData, lenBlockPerChan = 8,
        )
        val wav = SdatStrmFile("T", strm, 0, 0, 0, 0).toWav()
        // nibble=9=0b1001: diff = step>>3 + step>>2 = 0+1=1; code&8=1 → sample = 0-1 = -1
        assertEquals(-1, rs16(wav, 44), "sample[0]: nibble=9")
    }

    // =========================================================================
    // STRM error tests
    // =========================================================================

    /** toWav() throws on data that is too small. */
    @Test
    fun `STRM toWav throws on too-small data`() {
        val bad = ByteArray(0x10)
        assertFailsWith<IllegalArgumentException> {
            SdatStrmFile("BAD", bad, 0, 0, 0, 0).toWav()
        }
    }

    /** toWav() throws on data without STRM magic. */
    @Test
    fun `STRM toWav throws on bad magic`() {
        val bad = ByteArray(0x68)
        "JUNK".encodeToByteArray().copyInto(bad, 0)
        assertFailsWith<IllegalArgumentException> {
            SdatStrmFile("BAD", bad, 0, 0, 0, 0).toWav()
        }
    }

    // =========================================================================
    // SWAR tests — empty and edge cases
    // =========================================================================

    /** An empty SWAR (nEntries=0) returns an empty list. */
    @Test
    fun `SWAR toWavList returns empty list for zero entries`() {
        val swar = buildSwar(emptyList())
        val list = SdatSwarFile("TEST", swar, 0).toWavList()
        assertEquals(0, list.size)
    }

    /** toWavList() throws on data without SWAR magic. */
    @Test
    fun `SWAR toWavList throws on bad magic`() {
        val bad = ByteArray(0x40)
        "JUNK".encodeToByteArray().copyInto(bad, 0)
        assertFailsWith<IllegalArgumentException> {
            SdatSwarFile("BAD", bad, 0).toWavList()
        }
    }

    /** toWavList() throws on data that is too small. */
    @Test
    fun `SWAR toWavList throws on too-small data`() {
        val bad = ByteArray(0x10)
        assertFailsWith<IllegalArgumentException> {
            SdatSwarFile("BAD", bad, 0).toWavList()
        }
    }

    // =========================================================================
    // SWAR tests — PCM8
    // =========================================================================

    /**
     * PCM8 SWAV: WAV header is correct.
     *
     * 4 samples × 1 byte = 4 bytes = 1 word → exact alignment, no rounding needed.
     */
    @Test
    fun `SWAR PCM8 toWavList single entry has correct WAV header`() {
        val numSamples = 4   // 4 bytes = 1 word: exact alignment
        val samples = byteArrayOf(0x00, 0x20, 0x40, 0x60)
        val header = buildSwavHeader(waveType = 0, sampleRate = 11025, numSamples = numSamples)
        val swar = buildSwar(listOf(Pair(header, samples)))
        val list = SdatSwarFile("TEST", swar, 0).toWavList()
        assertEquals(1, list.size)
        assertWavHeader(list[0], channels = 1, sampleRate = 11025, numSamples = numSamples)
    }

    /** PCM8 SWAV: each signed byte is upsampled to s16 by multiplying by 256. */
    @Test
    fun `SWAR PCM8 toWavList upsamples to 16-bit correctly`() {
        // 4 samples: signed PCM8 → 0, 64, -128, -1
        val samples = byteArrayOf(0x00, 0x40, 0x80.toByte(), 0xFF.toByte())
        val header = buildSwavHeader(waveType = 0, sampleRate = 8000, numSamples = 4)
        val swar = buildSwar(listOf(Pair(header, samples)))
        val wav = SdatSwarFile("TEST", swar, 0).toWavList()[0]
        assertEquals(0 * 256, rs16(wav, 44), "sample[0]")
        assertEquals(64 * 256, rs16(wav, 46), "sample[1]")
        assertEquals(-128 * 256, rs16(wav, 48), "sample[2]")
        assertEquals(-1 * 256, rs16(wav, 50), "sample[3]")
    }

    // =========================================================================
    // SWAR tests — PCM16
    // =========================================================================

    /**
     * PCM16 SWAV: WAV header is correct.
     *
     * 4 samples × 2 bytes = 8 bytes = 2 words: exact alignment.
     */
    @Test
    fun `SWAR PCM16 toWavList single entry has correct WAV header`() {
        val numSamples = 4   // 4 * 2 = 8 bytes = 2 words: exact alignment
        val samples = ByteArray(numSamples * 2)
        wu16(samples, 0, 1000); wu16(samples, 2, 0xFC18)
        wu16(samples, 4, 32767); wu16(samples, 6, 0)
        val header = buildSwavHeader(waveType = 1, sampleRate = 44100, numSamples = numSamples)
        val swar = buildSwar(listOf(Pair(header, samples)))
        val list = SdatSwarFile("TEST", swar, 0).toWavList()
        assertEquals(1, list.size)
        assertWavHeader(list[0], channels = 1, sampleRate = 44100, numSamples = numSamples)
    }

    /** PCM16 SWAV: sample values are preserved exactly. */
    @Test
    fun `SWAR PCM16 toWavList preserves sample values`() {
        // 8 samples × 2 bytes = 16 bytes = 4 words: exact alignment
        val values = shortArrayOf(1000, -1000, 0, 32767, -32768, 256, -256, 1)
        val samples = ByteArray(values.size * 2)
        for (i in values.indices) {
            val v = values[i].toInt()
            samples[i * 2] = (v and 0xFF).toByte()
            samples[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        val header = buildSwavHeader(waveType = 1, sampleRate = 22050, numSamples = values.size)
        val swar = buildSwar(listOf(Pair(header, samples)))
        val wav = SdatSwarFile("TEST", swar, 0).toWavList()[0]
        for (i in values.indices) {
            assertEquals(values[i].toInt(), rs16(wav, 44 + i * 2), "sample[$i]")
        }
    }

    // =========================================================================
    // SWAR tests — multiple entries
    // =========================================================================

    /** A SWAR with multiple PCM16 entries returns the correct number of WAVs. */
    @Test
    fun `SWAR toWavList multiple entries returns correct count`() {
        // Use sample counts that give exact 4-byte word alignment (multiples of 2 for PCM16)
        val mk = { n: Int ->
            // n must be even for PCM16 alignment; use 2, 4, 6 → 4, 8, 12 bytes → 1, 2, 3 words
            val s = ByteArray(n * 2)
            Pair(buildSwavHeader(waveType = 1, sampleRate = 8000, numSamples = n), s)
        }
        val swar = buildSwar(listOf(mk(2), mk(4), mk(6)))
        val list = SdatSwarFile("TEST", swar, 0).toWavList()
        assertEquals(3, list.size)
        assertWavHeader(list[0], channels = 1, sampleRate = 8000, numSamples = 2)
        assertWavHeader(list[1], channels = 1, sampleRate = 8000, numSamples = 4)
        assertWavHeader(list[2], channels = 1, sampleRate = 8000, numSamples = 6)
    }

    /** Each SWAV can have a different sample rate; toWavList preserves them independently. */
    @Test
    fun `SWAR toWavList preserves independent sample rates per entry`() {
        val rates = intArrayOf(8000, 22050, 44100)
        val entries = rates.map { rate ->
            // 2 samples × 2 bytes = 4 bytes = 1 word: exact alignment
            val s = ByteArray(4)
            Pair(buildSwavHeader(waveType = 1, sampleRate = rate, numSamples = 2), s)
        }
        val swar = buildSwar(entries)
        val list = SdatSwarFile("TEST", swar, 0).toWavList()
        assertEquals(3, list.size)
        for (i in rates.indices) {
            assertEquals(rates[i], ru32(list[i], 24), "sampleRate[$i]")
        }
    }

    // =========================================================================
    // SWAR tests — ADPCM
    // =========================================================================

    /**
     * ADPCM SWAV: WAV header is correct.
     *
     * 8 nibbles → 4 bytes nibble data = 1 word; loopStart=1 word (4-byte header), loopLen=1 word.
     * numSamples = (loopStartInBytes - 4) * 2 + loopLenInBytes * 2 = (4-4)*2 + 4*2 = 8.
     */
    @Test
    fun `SWAR ADPCM toWavList returns correct WAV header`() {
        // 8 nibbles → 4 nibble bytes; 4-byte ADPCM block header → 8 total bytes
        val numSamples = 8
        val blockData = ByteArray(8)  // 4-byte header (all zero) + 4 nibble bytes (all zero)
        val header = buildSwavAdpcmHeader(sampleRate = 16000, numSamples = numSamples)
        val swar = buildSwar(listOf(Pair(header, blockData)))
        val list = SdatSwarFile("TEST", swar, 0).toWavList()
        assertEquals(1, list.size)
        assertWavHeader(list[0], channels = 1, sampleRate = 16000, numSamples = numSamples)
    }

    /** ADPCM SWAV: all-zero nibbles decode to all-zero samples. */
    @Test
    fun `SWAR ADPCM toWavList all-zero nibbles decode to zero samples`() {
        val numSamples = 8
        val blockData = ByteArray(8)
        val header = buildSwavAdpcmHeader(sampleRate = 16000, numSamples = numSamples)
        val swar = buildSwar(listOf(Pair(header, blockData)))
        val wav = SdatSwarFile("TEST", swar, 0).toWavList()[0]
        for (i in 0 until numSamples) {
            assertEquals(0, rs16(wav, 44 + i * 2), "sample[$i]")
        }
    }

    // =========================================================================
    // Round-trip tests
    // =========================================================================

    /**
     * PCM16 STRM round-trip: encode known values → build STRM → decode → must match exactly.
     */
    @Test
    fun `STRM PCM16 round-trip encodes and decodes sample values exactly`() {
        val values = shortArrayOf(0, 100, -100, 32767, -32768, 1, -1, 256)
        val samples = ByteArray(values.size * 2)
        for (i in values.indices) {
            val v = values[i].toInt()
            samples[i * 2] = (v and 0xFF).toByte()
            samples[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        val strm = buildStrm(
            waveType = 1, channels = 1, sampleRate = 32000,
            numSamples = values.size, blockData = samples, lenBlockPerChan = samples.size,
        )
        val wav = SdatStrmFile("RT", strm, 0, 0, 0, 0).toWav()
        for (i in values.indices) {
            assertEquals(values[i].toInt(), rs16(wav, 44 + i * 2), "rt sample[$i]")
        }
    }

    /**
     * PCM16 SWAR round-trip: encode known values → build SWAR → decode → must match exactly.
     *
     * Uses 8 samples × 2 bytes = 16 bytes = 4 words (exact alignment).
     */
    @Test
    fun `SWAR PCM16 round-trip encodes and decodes sample values exactly`() {
        val values = shortArrayOf(0, 500, -500, 32767, -32768, 1, -1, 128)
        val samples = ByteArray(values.size * 2)
        for (i in values.indices) {
            val v = values[i].toInt()
            samples[i * 2] = (v and 0xFF).toByte()
            samples[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        val header = buildSwavHeader(waveType = 1, sampleRate = 44100, numSamples = values.size)
        val swar = buildSwar(listOf(Pair(header, samples)))
        val wav = SdatSwarFile("RT", swar, 0).toWavList()[0]
        for (i in values.indices) {
            assertEquals(values[i].toInt(), rs16(wav, 44 + i * 2), "rt sample[$i]")
        }
    }
}
