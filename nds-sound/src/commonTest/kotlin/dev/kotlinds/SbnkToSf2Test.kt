package dev.kotlinds

import kotlin.test.*

/**
 * Tests for [SbnkToSf2] / [SdatSbnkFile.toSf2] / [SdatSseqFile.toSf2].
 *
 * All test binaries are built programmatically; no real NDS files are needed.
 *
 * SF2 RIFF layout:
 *   0x00  "RIFF"
 *   0x04  size (u32 LE) = 4 + rest
 *   0x08  "sfbk"
 *   0x0C  LIST "INFO" …
 *   …     LIST "sdta" …
 *   …     LIST "pdta" …
 *
 * All multi-byte integers are little-endian.
 */
class SbnkToSf2Test {

    // =========================================================================
    // Helper byte-level utilities
    // =========================================================================

    private fun wu8(buf: ByteArray, off: Int, v: Int) { buf[off] = v.toByte() }
    private fun wu16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte(); buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }
    private fun wu32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte(); buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte(); buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun ru8(buf: ByteArray, off: Int): Int = buf[off].toInt() and 0xFF
    private fun ru16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
    private fun ru32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or ((buf[off + 3].toInt() and 0xFF) shl 24)
    private fun rs16(buf: ByteArray, off: Int): Int {
        val v = ru16(buf, off); return if (v >= 0x8000) v - 0x10000 else v
    }

    // =========================================================================
    // SWAR builder helpers
    // =========================================================================

    /**
     * Builds a minimal SWAR file containing one PCM8 sample of [samples] bytes.
     * PCM8 data: sequential bytes starting at 0, unsigned (biased 128).
     */
    private fun buildSwar(waveType: Int, loopFlag: Int, sampleRate: Int,
                          loopStartWords: Int, loopLenWords: Int, sampleData: ByteArray): ByteArray {
        // SWAR layout:
        //   0x38: nEntries (u32)
        //   0x3C: entry[0] absolute offset (u32)  ← NdsAudio reads here
        //   0x40: SWAV header (12 bytes)
        //   0x4C: sample data
        val swavHeaderOffset = 0x40
        val totalSize = swavHeaderOffset + 12 + sampleData.size
        val buf = ByteArray(totalSize)

        buf[0] = 'S'.code.toByte(); buf[1] = 'W'.code.toByte()
        buf[2] = 'A'.code.toByte(); buf[3] = 'R'.code.toByte()

        wu32(buf, 0x38, 1)                   // nEntries
        wu32(buf, 0x3C, swavHeaderOffset)    // entry[0] offset = 0x40

        val h = swavHeaderOffset
        wu8(buf, h, waveType)
        wu8(buf, h + 1, loopFlag)
        wu16(buf, h + 2, sampleRate)
        wu16(buf, h + 6, loopStartWords)
        wu32(buf, h + 8, loopLenWords)
        sampleData.copyInto(buf, h + 12)
        return buf
    }

    /** SWAR with a trivial PCM16 sample (4 frames, loop covers all). */
    private fun buildPcm16Swar(samples: ShortArray): ByteArray {
        val sampleData = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            wu16(sampleData, i * 2, samples[i].toInt() and 0xFFFF)
        }
        // loopStart=0 words, loopLen=samples.size/2 words (samples in 32-bit words)
        val loopLenWords = (samples.size * 2 + 3) / 4  // round up to word count
        return buildSwar(NdsAudio.WAVE_PCM16, 1, 22050, 0, loopLenWords, sampleData)
    }

    /** SWAR with a trivial PCM8 sample: [value, value, value, value] (4 unsigned bytes, 1 word). */
    private fun buildPcm8Swar(value: Int): ByteArray {
        val sampleData = byteArrayOf(value.toByte(), value.toByte(), value.toByte(), value.toByte())
        return buildSwar(NdsAudio.WAVE_PCM8, 0, 11025, 0, 1, sampleData)
    }

    // =========================================================================
    // SBNK builder helpers
    // =========================================================================

    private fun buildSbnkType01(sampleNum: Int = 0, waNum: Int = 0, unityKey: Int = 60,
                                 attack: Int = 0x7F, decay: Int = 0x7F,
                                 sustain: Int = 0x7F, release: Int = 0x7F, pan: Int = 64): ByteArray {
        // Fixed layout: 128 pointer slots + one type-0x01 instrument data block
        // Instrument data at offset 0x3C + 128*4 = 0x13C (relative to SBNK start)
        val instrDataOff = 0x3C + 128 * 4
        val totalSize = instrDataOff + 10

        val buf = ByteArray(maxOf(totalSize, 0x40))
        buf[0] = 'S'.code.toByte(); buf[1] = 'B'.code.toByte()
        buf[2] = 'N'.code.toByte(); buf[3] = 'K'.code.toByte()
        wu32(buf, 0x38, 1)  // 1 instrument

        // Pointer: type=0x01, offset=instrDataOff (upper 24 bits of a 32-bit word)
        val ptrWord = 0x01 or (instrDataOff shl 8)
        wu32(buf, 0x3C, ptrWord)

        // Instrument data
        val d = instrDataOff
        wu16(buf, d, sampleNum)
        wu16(buf, d + 2, waNum)
        wu8(buf, d + 4, unityKey)
        wu8(buf, d + 5, attack)
        wu8(buf, d + 6, decay)
        wu8(buf, d + 7, sustain)
        wu8(buf, d + 8, release)
        wu8(buf, d + 9, pan)
        return buf
    }

    private fun buildSbnkType10(lowKey: Int, highKey: Int,
                                 sampleNum: Int = 0, waNum: Int = 0, unityKey: Int = 60): ByteArray {
        val nRgns = highKey - lowKey + 1
        val instrDataOff = 0x3C + 128 * 4
        val totalSize = instrDataOff + 2 + nRgns * 12

        val buf = ByteArray(maxOf(totalSize, 0x40))
        buf[0] = 'S'.code.toByte(); buf[1] = 'B'.code.toByte()
        buf[2] = 'N'.code.toByte(); buf[3] = 'K'.code.toByte()
        wu32(buf, 0x38, 1)
        wu32(buf, 0x3C, 0x10 or (instrDataOff shl 8))

        val d = instrDataOff
        wu8(buf, d, lowKey)
        wu8(buf, d + 1, highKey)
        for (i in 0 until nRgns) {
            val r = d + 2 + i * 12
            // +0,+1 unused; +2 sampleNum; +4 waNum; +6 unityKey; +7..+11 ADSR+pan
            wu16(buf, r + 2, sampleNum)
            wu16(buf, r + 4, waNum)
            wu8(buf, r + 6, unityKey)
            wu8(buf, r + 7, 0x7F); wu8(buf, r + 8, 0x7F)
            wu8(buf, r + 9, 0x7F); wu8(buf, r + 10, 0x7F)
            wu8(buf, r + 11, 64)
        }
        return buf
    }

    private fun buildSbnkType11(keyRanges: List<Int>,
                                 sampleNum: Int = 0, waNum: Int = 0, unityKey: Int = 60): ByteArray {
        val nRgns = keyRanges.size
        val instrDataOff = 0x3C + 128 * 4
        val totalSize = instrDataOff + 8 + nRgns * 12

        val buf = ByteArray(maxOf(totalSize, 0x40))
        buf[0] = 'S'.code.toByte(); buf[1] = 'B'.code.toByte()
        buf[2] = 'N'.code.toByte(); buf[3] = 'K'.code.toByte()
        wu32(buf, 0x38, 1)
        wu32(buf, 0x3C, 0x11 or (instrDataOff shl 8))

        val d = instrDataOff
        for ((i, k) in keyRanges.withIndex()) wu8(buf, d + i, k)
        for (ri in 0 until nRgns) {
            val r = d + 8 + ri * 12
            wu16(buf, r + 2, sampleNum)
            wu16(buf, r + 4, waNum)
            wu8(buf, r + 6, unityKey)
            wu8(buf, r + 7, 0x7F); wu8(buf, r + 8, 0x7F)
            wu8(buf, r + 9, 0x7F); wu8(buf, r + 10, 0x7F)
            wu8(buf, r + 11, 64)
        }
        return buf
    }

    // =========================================================================
    // SF2 structure navigation helpers
    // =========================================================================

    /** Returns the byte offset of the first LIST chunk with a matching type tag, or -1. */
    private fun findList(sf2: ByteArray, listType: String): Int {
        var pos = 12  // skip RIFF header
        while (pos + 12 <= sf2.size) {
            val tag = sf2.decodeToString(pos, pos + 4)
            val size = ru32(sf2, pos + 4)
            val type = sf2.decodeToString(pos + 8, pos + 12)
            if (tag == "LIST" && type == listType) return pos
            pos += 8 + size + (size and 1)  // advance past this chunk (pad to even)
        }
        return -1
    }

    /** Returns the byte offset of the first sub-chunk with [tag] inside a LIST at [listOff], or -1. */
    private fun findSubChunk(sf2: ByteArray, listOff: Int, tag: String): Int {
        val listSize = ru32(sf2, listOff + 4)
        var pos = listOff + 12  // skip LIST header + type tag
        val end = listOff + 8 + listSize
        while (pos + 8 <= end) {
            val t = sf2.decodeToString(pos, pos + 4)
            val size = ru32(sf2, pos + 4)
            if (t == tag) return pos
            pos += 8 + size + (size and 1)
        }
        return -1
    }

    /** Returns the data bytes of the sub-chunk with [tag] inside the list at [listOff]. */
    private fun subChunkData(sf2: ByteArray, listOff: Int, tag: String): ByteArray {
        val off = findSubChunk(sf2, listOff, tag)
        assertTrue(off >= 0, "Sub-chunk '$tag' not found in LIST at $listOff")
        val size = ru32(sf2, off + 4)
        return sf2.copyOfRange(off + 8, off + 8 + size)
    }

    /** Returns the data bytes of the named sub-chunk inside LIST "pdta". */
    private fun pdtaChunk(sf2: ByteArray, tag: String): ByteArray {
        val pdta = findList(sf2, "pdta")
        assertTrue(pdta >= 0, "LIST pdta not found")
        return subChunkData(sf2, pdta, tag)
    }

    // =========================================================================
    // Build a minimal SdatSbnkFile / SdatSwarFile
    // =========================================================================

    private fun makeSbnk(data: ByteArray, wars: List<Int> = listOf(0, -1, -1, -1)) =
        SdatSbnkFile("test_bank", data, 0, wars)

    private fun makeSwar(data: ByteArray) = SdatSwarFile("test_war", data, 0)

    // =========================================================================
    // Test 1: SF2 magic and format tag
    // =========================================================================

    @Test
    fun sf2StartsWithRiffSfbk() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        assertEquals("RIFF", sf2.decodeToString(0, 4))
        assertEquals("sfbk", sf2.decodeToString(8, 12))
    }

    // =========================================================================
    // Test 2: INFO chunk present with ifil sub-chunk (version 2.01)
    // =========================================================================

    @Test
    fun infoChunkContainsIfil201() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val infoOff = findList(sf2, "INFO")
        assertTrue(infoOff >= 0, "LIST INFO not found")
        val ifil = subChunkData(sf2, infoOff, "ifil")
        assertEquals(4, ifil.size)
        assertEquals(2, ru16(ifil, 0))  // major
        assertEquals(1, ru16(ifil, 2))  // minor
    }

    // =========================================================================
    // Test 3: sdta chunk present with smpl sub-chunk
    // =========================================================================

    @Test
    fun sdtaChunkContainsSmpl() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val sdta = findList(sf2, "sdta")
        assertTrue(sdta >= 0, "LIST sdta not found")
        val smplOff = findSubChunk(sf2, sdta, "smpl")
        assertTrue(smplOff >= 0, "smpl sub-chunk not found in sdta")
    }

    // =========================================================================
    // Test 4: pdta chunk present with all 9 required sub-chunks
    // =========================================================================

    @Test
    fun pdtaContainsAllRequiredSubChunks() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val pdta = findList(sf2, "pdta")
        assertTrue(pdta >= 0)
        for (tag in listOf("phdr", "pbag", "pmod", "pgen", "inst", "ibag", "imod", "igen", "shdr")) {
            assertTrue(findSubChunk(sf2, pdta, tag) >= 0, "Missing sub-chunk: $tag")
        }
    }

    // =========================================================================
    // Test 5: phdr terminal record has name "EOP"
    // =========================================================================

    @Test
    fun phdrTerminalNameIsEop() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val phdr = pdtaChunk(sf2, "phdr")
        assertTrue(phdr.size >= 38, "phdr too small")
        val lastRecBase = phdr.size - 38
        val termName = phdr.decodeToString(lastRecBase, lastRecBase + 3)
        assertEquals("EOP", termName)
    }

    // =========================================================================
    // Test 6: inst terminal record has name "EOI"
    // =========================================================================

    @Test
    fun instTerminalNameIsEoi() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val inst = pdtaChunk(sf2, "inst")
        assertTrue(inst.size >= 22)
        val lastBase = inst.size - 22
        val termName = inst.decodeToString(lastBase, lastBase + 3)
        assertEquals("EOI", termName)
    }

    // =========================================================================
    // Test 7: shdr terminal record has name "EOS"
    // =========================================================================

    @Test
    fun shdrTerminalNameIsEos() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val shdr = pdtaChunk(sf2, "shdr")
        assertTrue(shdr.size >= 46)
        val lastBase = shdr.size - 46
        val termName = shdr.decodeToString(lastBase, lastBase + 3)
        assertEquals("EOS", termName)
    }

    // =========================================================================
    // Test 8: single-region instrument produces one ibag entry
    // =========================================================================

    @Test
    fun type01ProducesOneIbagEntry() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val ibag = pdtaChunk(sf2, "ibag")
        // 1 instrument × 1 region → 1 ibag entry + 1 terminal = 2 entries × 4 bytes
        assertEquals(8, ibag.size)
    }

    // =========================================================================
    // Test 9: drumset with 3 keys produces 3 ibag entries
    // =========================================================================

    @Test
    fun type10With3KeysProduces3IbagEntries() {
        val sbnk = buildSbnkType10(lowKey = 36, highKey = 38)
        val sf2 = makeSbnk(sbnk).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val ibag = pdtaChunk(sf2, "ibag")
        // 1 instrument × 3 regions → 3 ibag entries + 1 terminal = 4 × 4 bytes
        assertEquals(16, ibag.size)
    }

    // =========================================================================
    // Test 10: multi-region instrument key splits
    // =========================================================================

    @Test
    fun type11TwoRegionsHaveCorrectKeyRanges() {
        val sbnk = buildSbnkType11(keyRanges = listOf(40, 60))
        val sf2 = makeSbnk(sbnk).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val igen = pdtaChunk(sf2, "igen")

        // First region: keyRange generator (opcode 43) at offset 0
        assertEquals(43, ru16(igen, 0))   // keyRange opcode
        assertEquals(0, ru8(igen, 2))     // keyLow = 0
        assertEquals(40, ru8(igen, 3))    // keyHigh = 40

        // Second region starts at GEN_COUNT_PER_REGION * 4 = 56
        val r2 = 14 * 4
        assertEquals(43, ru16(igen, r2))
        assertEquals(41, ru8(igen, r2 + 2))  // keyLow = 40 + 1
        assertEquals(60, ru8(igen, r2 + 3))  // keyHigh = 60
    }

    // =========================================================================
    // Test 11: PCM8 sample decoded to PCM16 (upscaled)
    // =========================================================================

    @Test
    fun pcm8SampleDecodedToUpscaledPcm16() {
        // PCM8 value 128 → signed s8 = 0 → s16 = 0 * 256 = 0
        // PCM8 value 200 → signed s8 = 72 → s16 = 72 * 256 = 18432
        val swarData = buildPcm8Swar(200)
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(swarData)))
        val sdta = findList(sf2, "sdta")
        val smpl = subChunkData(sf2, sdta, "smpl")
        assertTrue(smpl.isNotEmpty())
        // First sample: value 200 as signed byte = 72 (since (200 and 0xFF) - 128 = 72? No:
        // NdsAudio decodes PCM8: s8 = blocks[srcIdx].toInt() (Kotlin signed extend) then * 256
        // For byte value 200 (0xC8): Kotlin reads as signed = -56, so s16 = -56 * 256 = -14336
        val expectedS16 = -14336
        val s16 = rs16(smpl, 0)
        assertEquals(expectedS16, s16)
    }

    // =========================================================================
    // Test 12: PCM16 sample passed through unchanged
    // =========================================================================

    @Test
    fun pcm16SamplePassedThroughUnchanged() {
        val input = shortArrayOf(1000, -2000, 3000, -4000)
        val swarData = buildPcm16Swar(input)
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(swarData)))
        val sdta = findList(sf2, "sdta")
        val smpl = subChunkData(sf2, sdta, "smpl")
        for (i in input.indices) {
            assertEquals(input[i].toInt(), rs16(smpl, i * 2), "Mismatch at sample $i")
        }
    }

    // =========================================================================
    // Test 13: ADPCM sample decoded (spot-check first sample from initial value in header)
    // =========================================================================

    @Test
    fun adpcmSampleDecodedCorrectly() {
        // NDS ADPCM: loopStart must be >= 1 word because the 4-byte ADPCM block header
        // occupies the first word of the data section.
        // Layout: [4-byte ADPCM header][4 bytes loop nibbles]
        // loopStart=1 word → pre-loop = (4-4)*2 = 0 samples; loop = 1*4*2 = 8 samples
        val adpcmData = ByteArray(8)
        // 4-byte ADPCM block header: initialSample (s16 LE) = 1000, stepIdx = 0
        adpcmData[0] = (1000 and 0xFF).toByte()
        adpcmData[1] = ((1000 ushr 8) and 0xFF).toByte()
        adpcmData[2] = 0  // stepIdx
        adpcmData[3] = 0  // reserved
        // 4 bytes of loop nibbles (all 0x00): nibble code=0 → diff=0 → sample stays 1000
        val swar = buildSwar(NdsAudio.WAVE_ADPCM, 0, 22050, 1, 1, adpcmData)
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(swar)))
        val sdta = findList(sf2, "sdta")
        val smpl = subChunkData(sf2, sdta, "smpl")
        assertTrue(smpl.isNotEmpty(), "smpl chunk should not be empty")
        // First decoded sample initialised from ADPCM header initialSample = 1000
        assertEquals(1000, rs16(smpl, 0))
    }

    // =========================================================================
    // Test 14: loopFlag set → sampleModes generator = 1
    // =========================================================================

    @Test
    fun loopFlagSetProducesSampleModes1() {
        val swar = buildPcm16Swar(shortArrayOf(100, 200, 300, 400))  // loopFlag=1 in buildPcm16Swar
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(swar)))
        val igen = pdtaChunk(sf2, "igen")

        // Find sampleModes generator (opcode 54) in first region
        var found = false
        var i = 0
        while (i + 4 <= igen.size) {
            val opcode = ru16(igen, i)
            if (opcode == 54) {
                assertEquals(1, ru16(igen, i + 2), "sampleModes should be 1 for looping sample")
                found = true
                break
            }
            i += 4
        }
        assertTrue(found, "sampleModes generator not found")
    }

    // =========================================================================
    // Test 15: no loop flag → sampleModes = 0
    // =========================================================================

    @Test
    fun noLoopFlagProducesSampleModes0() {
        val swar = buildPcm8Swar(128)  // loopFlag=0
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(swar)))
        val igen = pdtaChunk(sf2, "igen")
        var i = 0
        while (i + 4 <= igen.size) {
            if (ru16(igen, i) == 54) {
                assertEquals(0, ru16(igen, i + 2), "sampleModes should be 0 for non-looping sample")
                return
            }
            i += 4
        }
        fail("sampleModes generator not found")
    }

    // =========================================================================
    // Test 16: unityKey preserved in overridingRootKey generator
    // =========================================================================

    @Test
    fun unityKeyPreservedInOverridingRootKey() {
        val unityKey = 72
        val sf2 = makeSbnk(buildSbnkType01(unityKey = unityKey)).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val igen = pdtaChunk(sf2, "igen")
        var i = 0
        while (i + 4 <= igen.size) {
            if (ru16(igen, i) == 58) {  // overridingRootKey opcode
                assertEquals(unityKey, ru16(igen, i + 2))
                return
            }
            i += 4
        }
        fail("overridingRootKey generator not found")
    }

    // =========================================================================
    // Tests 17–19: pan mapping
    // =========================================================================

    @Test
    fun pan64MapsToCenterSf2Pan0() {
        val sf2 = makeSbnk(buildSbnkType01(pan = 64)).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val panValue = findPanGenerator(sf2)
        // round((64/127.0 - 0.5) * 1000) ≈ round(0.504 * 1000 - 500) = round(4.0) = 4 (not exactly 0 due to asymmetry)
        // Acceptable: near 0
        assertTrue(panValue in -10..10, "Expected pan near 0 for pan=64, got $panValue")
    }

    @Test
    fun pan0MapsToFullLeftSf2PanMinus500() {
        val sf2 = makeSbnk(buildSbnkType01(pan = 0)).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val panValue = findPanGenerator(sf2)
        assertEquals(-500, panValue)
    }

    @Test
    fun pan127MapsToFullRightSf2Pan500() {
        val sf2 = makeSbnk(buildSbnkType01(pan = 127)).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val panValue = findPanGenerator(sf2)
        assertEquals(500, panValue)
    }

    private fun findPanGenerator(sf2: ByteArray): Int {
        val igen = pdtaChunk(sf2, "igen")
        var i = 0
        while (i + 4 <= igen.size) {
            if (ru16(igen, i) == 17) {  // GEN_PAN opcode
                return rs16(igen, i + 2)
            }
            i += 4
        }
        fail("pan generator not found")
    }

    // =========================================================================
    // Test 20: smpl chunk size = (totalSamples + 46 * numSamples) * 2 bytes
    // =========================================================================

    @Test
    fun smplChunkSizeIncludesPaddingPerSample() {
        val input = shortArrayOf(100, 200, 300, 400)  // 4 samples
        val swar = buildPcm16Swar(input)
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(swar)))
        val sdta = findList(sf2, "sdta")
        val smpl = subChunkData(sf2, sdta, "smpl")
        // 4 samples + 46 padding = 50 frames × 2 bytes = 100 bytes
        assertEquals((input.size + 46) * 2, smpl.size)
    }

    // =========================================================================
    // Test 21: shdr sampleRate matches SWAR sampleRate
    // =========================================================================

    @Test
    fun shdrSampleRateMatchesSwar() {
        val expectedRate = 22050
        val swarData = buildSwar(NdsAudio.WAVE_PCM16, 1, expectedRate, 0, 1,
            ByteArray(4) { 0 })
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(swarData)))
        val shdr = pdtaChunk(sf2, "shdr")
        val sampleRate = ru32(shdr, 36)  // sampleRate field at +36 in shdr
        assertEquals(expectedRate, sampleRate)
    }

    // =========================================================================
    // Test 22: SdatSseqFile.toSf2(archive) uses the correct bank
    // =========================================================================

    @Test
    fun sseqToSf2UsesCorrectBank() {
        val swarData = buildPcm8Swar(200)
        val swar = makeSwar(swarData)
        val sbnkData = buildSbnkType01()
        val sbnk = makeSbnk(sbnkData)
        val archive = SdatArchive(
            sequences = listOf(
                SdatSseqFile("seq0", ByteArray(0x1C) { 0.toByte() }, 0, bank = 0, 0, 0, 0, 0)
            ),
            banks = listOf(sbnk),
            waveArchives = listOf(swar),
            streams = emptyList(),
        )
        val sf2 = archive.sequences[0].toSf2(archive)
        // Should produce a valid SF2 (RIFF sfbk header)
        assertEquals("RIFF", sf2.decodeToString(0, 4))
        assertEquals("sfbk", sf2.decodeToString(8, 12))
    }

    // =========================================================================
    // Test 23: missing SWAR slot (waNum out of range) handled gracefully
    // =========================================================================

    @Test
    fun missingWarSlotDoesNotCrash() {
        // Bank references waNum=1 but we only supply 1 SWAR (index 0)
        val sbnk = buildSbnkType01(waNum = 1)
        val sf2 = makeSbnk(sbnk).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        // Should not throw; SF2 produced (possibly with no samples)
        assertEquals("RIFF", sf2.decodeToString(0, 4))
    }

    // =========================================================================
    // Test 24: RIFF size field is consistent with actual output size
    // =========================================================================

    @Test
    fun riffSizeFieldMatchesActualSize() {
        val sf2 = makeSbnk(buildSbnkType01()).toSf2(listOf(makeSwar(buildPcm8Swar(128))))
        val riffSize = ru32(sf2, 4)  // RIFF chunk size = total - 8 (excludes "RIFF" tag + size field)
        assertEquals(sf2.size - 8, riffSize)
    }
}
