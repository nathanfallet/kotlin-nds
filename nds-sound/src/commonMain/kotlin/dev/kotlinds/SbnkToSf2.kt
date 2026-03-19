package dev.kotlinds

import kotlin.math.*

/**
 * Converts an NDS SBNK instrument bank + associated SWAR wave archives to a SoundFont 2 (SF2) file.
 *
 * Reference implementation: VGMTrans `NDSInstrSet.cpp` and `SF2File.cpp`.
 */
internal object SbnkToSf2 {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private const val INTR_FREQUENCY = (2728.0 * 64) / 33_513_982.0  // ~0.005210 s/frame

    private val ATTACK_TIME_TABLE = intArrayOf(
        0x00, 0x01, 0x05, 0x0E, 0x1A, 0x26, 0x33, 0x3F,
        0x49, 0x54, 0x5C, 0x64, 0x6D, 0x74, 0x7B, 0x7F,
        0x84, 0x89, 0x8F
    )

    // Hardcoded table from VGMTrans NDSInstrSet.cpp — maps NDS sustain value (0–127) to
    // dB attenuation in tenths (negative values → attenuation).
    private val DECIBEL_SQUARE_TABLE = intArrayOf(
        -481, -480, -480, -480, -480, -480, -480, -480, -480, -460, -442, -425, -410, -396, -383, -371,
        -360, -349, -339, -330, -321, -313, -305, -297, -289, -282, -276, -269, -263, -257, -251, -245,
        -239, -234, -229, -224, -219, -214, -210, -205, -201, -196, -192, -188, -184, -180, -176, -173,
        -169, -165, -162, -158, -155, -152, -149, -145, -142, -139, -136, -133, -130, -127, -125, -122,
        -119, -116, -114, -111, -109, -106, -103, -101,  -99,  -96,  -94,  -91,  -89,  -87,  -85,  -82,
         -80,  -78,  -76,  -74,  -72,  -70,  -68,  -66,  -64,  -62,  -60,  -58,  -56,  -54,  -52,  -50,
         -49,  -47,  -45,  -43,  -42,  -40,  -38,  -36,  -35,  -33,  -31,  -30,  -28,  -27,  -25,  -23,
         -22,  -20,  -19,  -17,  -16,  -14,  -13,  -11,  -10,   -8,   -7,   -6,   -4,   -3,   -1,    0,
    )

    // SF2 generator opcodes (from VGMTrans SF2File.h)
    private const val GEN_REVERB_EFFECTS_SEND = 16
    private const val GEN_PAN = 17
    private const val GEN_ATTACK_VOL_ENV = 34
    private const val GEN_HOLD_VOL_ENV = 35
    private const val GEN_DECAY_VOL_ENV = 36
    private const val GEN_SUSTAIN_VOL_ENV = 37
    private const val GEN_RELEASE_VOL_ENV = 38
    private const val GEN_INSTRUMENT = 41
    private const val GEN_KEY_RANGE = 43
    private const val GEN_VEL_RANGE = 44
    private const val GEN_INITIAL_ATTENUATION = 48
    private const val GEN_COARSE_TUNE = 51
    private const val GEN_FINE_TUNE = 52
    private const val GEN_SAMPLE_ID = 53
    private const val GEN_SAMPLE_MODES = 54
    private const val GEN_OVERRIDING_ROOT_KEY = 58

    private const val GEN_COUNT_PER_REGION = 14  // number of generators emitted per region
    private const val SF2_SAMPLE_PADDING = 46    // zero-sample padding appended to each sample

    private const val PSG_SAMPLE_RATE = 32768
    private const val PSG_FREQ = 440

    // -------------------------------------------------------------------------
    // Internal data classes
    // -------------------------------------------------------------------------

    private data class Region(
        val keyLow: Int,
        val keyHigh: Int,
        val sampleNum: Int,   // -1 for PSG
        val waNum: Int,       // -1 for PSG
        val unityKey: Int,
        val attack: Int,
        val decay: Int,
        val sustain: Int,
        val release: Int,
        val pan: Int,
        val psgType: Int = 0,     // 0=SWAR, 2=PSG duty, 3=PSG noise
        val dutyCycle: Int = 0,
    )

    private data class Instrument(
        val name: String,
        val presetIndex: Int,
        val regions: List<Region>,
    )

    private data class SampleEntry(
        val name: String,
        val pcm16: ShortArray,
        val sampleRate: Int,
        val loopFlag: Boolean,
        val loopStartFrames: Int,
        val loopEndFrames: Int,
        val startFrame: Int,  // absolute start in the smpl chunk (in sample frames)
    )

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    fun convert(bank: SdatSbnkFile, wars: List<SdatSwarFile?>): ByteArray {
        val data = bank.data
        require(data.size >= 0x40) { "SBNK data too small" }
        require(
            data[0] == 'S'.code.toByte() && data[1] == 'B'.code.toByte() &&
                    data[2] == 'N'.code.toByte() && data[3] == 'K'.code.toByte()
        ) { "Not an SBNK file" }

        val numInstruments = readU32(data, 0x38).toInt()
        val instruments = mutableListOf<Instrument>()

        for (i in 0 until minOf(numInstruments, 128)) {
            val ptrOffset = 0x3C + i * 4
            if (ptrOffset + 4 > data.size) break
            val temp = readU32(data, ptrOffset).toInt()
            if (temp == 0) continue

            val instrType = temp and 0xFF
            val dataOff = (temp ushr 8)  // relative to SBNK start
            if (dataOff == 0 || dataOff >= data.size) continue

            val regions = parseRegions(data, instrType, dataOff)
            if (regions.isNotEmpty()) {
                instruments.add(Instrument("instr_$i", i, regions))
            }
        }

        // Decode all unique samples and build lookup maps
        val sampleMap = mutableMapOf<Pair<Int, Int>, Int>()  // (waNum, sampleNum) -> sample index
        val psgMap = mutableMapOf<Int, Int>()                 // dutyCycle (or -1 for noise) -> sample index
        val samples = mutableListOf<SampleEntry>()
        var currentFrame = 0

        for (instr in instruments) {
            for (region in instr.regions) {
                when (region.psgType) {
                    2 -> {
                        val dc = region.dutyCycle.coerceIn(0, 6)
                        if (!psgMap.containsKey(dc)) {
                            val pcm16 = generateDutyCycleWave(dc)
                            samples.add(SampleEntry("psg_duty_$dc", pcm16, PSG_SAMPLE_RATE, true, 0, pcm16.size, currentFrame))
                            psgMap[dc] = samples.size - 1
                            currentFrame += pcm16.size + SF2_SAMPLE_PADDING
                        }
                    }
                    3 -> {
                        if (!psgMap.containsKey(-1)) {
                            val pcm16 = generateNoise()
                            samples.add(SampleEntry("psg_noise", pcm16, PSG_SAMPLE_RATE, false, 0, pcm16.size, currentFrame))
                            psgMap[-1] = samples.size - 1
                            currentFrame += pcm16.size + SF2_SAMPLE_PADDING
                        }
                    }
                    else -> {
                        val key = Pair(region.waNum, region.sampleNum)
                        if (!sampleMap.containsKey(key)) {
                            val war = wars.getOrNull(region.waNum) ?: continue
                            val entry = decodeSwarSample(war.data, region.sampleNum, samples.size, currentFrame)
                                ?: continue
                            sampleMap[key] = samples.size
                            samples.add(entry)
                            currentFrame += entry.pcm16.size + SF2_SAMPLE_PADDING
                        }
                    }
                }
            }
        }

        return buildSf2(bank.name, instruments, samples, sampleMap, psgMap)
    }

    // -------------------------------------------------------------------------
    // SBNK instrument / region parsing
    // -------------------------------------------------------------------------

    private fun parseRegions(data: ByteArray, instrType: Int, dataOff: Int): List<Region> {
        val regions = mutableListOf<Region>()
        when (instrType) {
            0x01 -> {
                // Single region (10 bytes): sampleNum+2, waNum+2, unityKey+attack+decay+sustain+release+pan
                val r = parseSingleRegion(data, dataOff) ?: return regions
                regions.add(r.copy(keyLow = 0, keyHigh = 127))
            }
            0x02 -> {
                // PSG Tone (10 bytes)
                if (dataOff + 10 > data.size) return regions
                val dutyCycle = data[dataOff].toInt() and 0x07
                val art = parseArticData(data, dataOff + 4)
                regions.add(Region(0, 127, -1, -1, 69, art[0], art[1], art[2], art[3], art[4], psgType = 2, dutyCycle = dutyCycle))
            }
            0x03 -> {
                // PSG Noise (10 bytes)
                if (dataOff + 10 > data.size) return regions
                val art = parseArticData(data, dataOff + 4)
                regions.add(Region(0, 127, -1, -1, 45, art[0], art[1], art[2], art[3], art[4], psgType = 3))
            }
            0x10 -> {
                // Drumset (variable): lowKey + highKey + N×12-byte regions
                if (dataOff + 2 > data.size) return regions
                val lowKey = data[dataOff].toInt() and 0xFF
                val highKey = data[dataOff + 1].toInt() and 0xFF
                for (k in lowKey..highKey) {
                    // VGMTrans: sampleNum at rgnOff+2, waNum at rgnOff+4, art at rgnOff+6
                    val rgnOff = dataOff + 2 + (k - lowKey) * 12
                    if (rgnOff + 12 > data.size) break
                    val sampleNum = readU16(data, rgnOff + 2)
                    val waNum = readU16(data, rgnOff + 4)
                    val art = parseArticData(data, rgnOff + 6)
                    regions.add(Region(k, k, sampleNum, waNum, art[0], art[1], art[2], art[3], art[4], art[5]))
                }
            }
            0x11 -> {
                // Multi-region: 8 key-range bytes, then N×12-byte regions
                if (dataOff + 8 > data.size) return regions
                var prevKey = 0
                for (ri in 0 until 8) {
                    val upperKey = data[dataOff + ri].toInt() and 0xFF
                    if (upperKey == 0) break
                    val rgnOff = dataOff + 8 + ri * 12
                    if (rgnOff + 12 > data.size) break
                    val sampleNum = readU16(data, rgnOff + 2)
                    val waNum = readU16(data, rgnOff + 4)
                    val art = parseArticData(data, rgnOff + 6)
                    regions.add(Region(prevKey, upperKey, sampleNum, waNum, art[0], art[1], art[2], art[3], art[4], art[5]))
                    prevKey = upperKey + 1
                }
            }
        }
        return regions
    }

    /** Parses a type-0x01 single-region instrument: sampleNum at +0, waNum at +2, artData at +4. */
    private fun parseSingleRegion(data: ByteArray, off: Int): Region? {
        if (off + 10 > data.size) return null
        val sampleNum = readU16(data, off)
        val waNum = readU16(data, off + 2)
        val art = parseArticData(data, off + 4)
        return Region(0, 127, sampleNum, waNum, art[0], art[1], art[2], art[3], art[4], art[5])
    }

    /** Returns [unityKey, attack, decay, sustain, release, pan] starting at [off]. */
    private fun parseArticData(data: ByteArray, off: Int): IntArray {
        if (off + 6 > data.size) return IntArray(6)
        return intArrayOf(
            data[off].toInt() and 0xFF,       // unityKey
            data[off + 1].toInt() and 0xFF,   // attack
            data[off + 2].toInt() and 0xFF,   // decay
            data[off + 3].toInt() and 0xFF,   // sustain
            data[off + 4].toInt() and 0xFF,   // release
            data[off + 5].toInt() and 0xFF,   // pan
        )
    }

    // -------------------------------------------------------------------------
    // SWAR sample decoding
    // -------------------------------------------------------------------------

    private fun decodeSwarSample(swarData: ByteArray, sampleNum: Int, idx: Int, startFrame: Int): SampleEntry? {
        if (swarData.size < 0x3C) return null
        val nEntries = readU32(swarData, 0x38).toInt()
        if (sampleNum < 0 || sampleNum >= nEntries) return null

        val entryOffset = readU32(swarData, 0x3C + sampleNum * 4).toInt()
        val nextOffset = if (sampleNum < nEntries - 1)
            readU32(swarData, 0x3C + (sampleNum + 1) * 4).toInt()
        else
            swarData.size
        if (entryOffset + 12 > swarData.size) return null

        val waveType = swarData[entryOffset].toInt() and 0xFF
        val loopFlag = swarData[entryOffset + 1].toInt() and 0xFF
        val sampleRate = readU16(swarData, entryOffset + 2)
        val loopStartWords = readU16(swarData, entryOffset + 6)
        val loopLenWords = readU32(swarData, entryOffset + 8).toInt()

        val loopStartBytes = loopStartWords * 4
        val loopLenBytes = loopLenWords * 4

        // Loop points in decoded sample frames
        val loopStartFrames: Int
        val loopEndFrames: Int
        when (waveType) {
            NdsAudio.WAVE_PCM8 -> {
                loopStartFrames = loopStartBytes
                loopEndFrames = loopStartFrames + loopLenBytes
            }
            NdsAudio.WAVE_PCM16 -> {
                loopStartFrames = loopStartBytes / 2
                loopEndFrames = loopStartFrames + loopLenBytes / 2
            }
            NdsAudio.WAVE_ADPCM -> {
                loopStartFrames = loopStartWords * 8  // 32-bit words × 8 nibbles/word
                loopEndFrames = loopStartFrames + loopLenWords * 8
            }
            else -> {
                loopStartFrames = 0
                loopEndFrames = 0
            }
        }

        val firstSampOfs = if (waveType == NdsAudio.WAVE_ADPCM) 4 else 0
        val numSamples: Int = when (waveType) {
            NdsAudio.WAVE_PCM8 -> (loopStartBytes - firstSampOfs) + loopLenBytes
            NdsAudio.WAVE_PCM16 -> (loopStartBytes - firstSampOfs) / 2 + loopLenBytes / 2
            NdsAudio.WAVE_ADPCM -> (loopStartBytes - firstSampOfs) * 2 + loopLenBytes * 2
            else -> 0
        }
        if (numSamples <= 0) return null

        val dataOffset = entryOffset + 0x0C
        val dataSize = minOf(loopStartBytes + loopLenBytes, nextOffset - dataOffset)
        if (dataSize <= 0) return null

        val pcmBytes = ByteArray(numSamples * 2)
        NdsAudio.decodeBlock(
            dest = pcmBytes, destOffset = 0,
            blocks = swarData, blockOffset = dataOffset,
            lenBlockPerChan = dataSize, nSamples = numSamples,
            waveType = waveType, channels = 1,
        )

        val pcm16 = ShortArray(numSamples) { i ->
            val v = (pcmBytes[i * 2].toInt() and 0xFF) or ((pcmBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
            (if (v >= 0x8000) v - 0x10000 else v).toShort()
        }

        return SampleEntry(
            name = "sample_$idx",
            pcm16 = pcm16,
            sampleRate = sampleRate,
            loopFlag = loopFlag != 0,
            loopStartFrames = loopStartFrames.coerceIn(0, numSamples),
            loopEndFrames = loopEndFrames.coerceIn(0, numSamples),
            startFrame = startFrame,
        )
    }

    // -------------------------------------------------------------------------
    // PSG waveform generation
    // -------------------------------------------------------------------------

    private fun generateDutyCycleWave(dutyCycle: Int): ShortArray {
        val ratios = doubleArrayOf(0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875)
        val ratio = ratios[dutyCycle.coerceIn(0, 6)]
        val numSamples = PSG_SAMPLE_RATE / PSG_FREQ
        return ShortArray(numSamples) { i ->
            if (i.toDouble() / numSamples < ratio) 32767 else -32768
        }
    }

    private fun generateNoise(): ShortArray {
        // Galois LFSR matching VGMTrans NDSInstrSet.cpp: polynomial 0x6000, initial state 0x7FFF.
        var lfsr = 0x7FFF
        return ShortArray(PSG_SAMPLE_RATE) {
            val carry = lfsr and 1
            lfsr = lfsr ushr 1
            if (carry != 0) lfsr = lfsr xor 0x6000
            if (carry != 0) -32767 else 32767
        }
    }

    // -------------------------------------------------------------------------
    // ADSR conversion
    // -------------------------------------------------------------------------

    private fun calcAttackTime(attack: Int): Double {
        if (attack == 0x7F) return 0.0
        val realAttack = if (attack >= 0x6D) ATTACK_TIME_TABLE[0x7F - attack] else (0xFF - attack)
        var count = 0
        var env = 0x16980L
        val threshold = 0x16980L / 10
        while (env > threshold) {
            env = (env * realAttack) shr 8
            count++
        }
        return count * INTR_FREQUENCY
    }

    private fun getFallingRate(decayTime: Int): Int = when {
        decayTime == 0x7F -> 0xFFFF
        decayTime == 0x7E -> 0x3C00
        decayTime < 0x32  -> (decayTime * 2 + 1) and 0xFFFF
        else              -> (0x1E00 / (0x7E - decayTime)) and 0xFFFF
    }

    private fun calcFallingTime(value: Int): Double {
        if (value == 0x7F) return 0.001
        val rate = getFallingRate(value)
        return (0x16980.toDouble() / rate) * INTR_FREQUENCY
    }

    private fun calcSf2Sustain(sustain: Int): Int {
        val linear = when (sustain) {
            0x7F -> 1.0
            0    -> 0.0
            else -> 10.0.pow(DECIBEL_SQUARE_TABLE[sustain] / 10.0 / 20.0)
        }
        return if (linear <= 0.0) 1000
        else round(-20.0 * log10(linear) * 10).toInt().coerceIn(0, 1000)
    }

    private fun secondsToTimecents(seconds: Double): Int =
        if (seconds <= 0.0) -12000
        else round(1200.0 * log2(seconds)).toInt().coerceIn(-12000, 8000)

    // -------------------------------------------------------------------------
    // SF2 binary builder
    // -------------------------------------------------------------------------

    private fun buildSf2(
        bankName: String,
        instruments: List<Instrument>,
        samples: List<SampleEntry>,
        sampleMap: Map<Pair<Int, Int>, Int>,
        psgMap: Map<Int, Int>,
    ): ByteArray {
        val infoList = buildList("INFO", buildInfoChunks(bankName))
        val sdtaList = buildList("sdta", buildChunk("smpl", buildSmpl(samples)))
        val pdtaList = buildList("pdta", buildPdta(instruments, samples, sampleMap, psgMap))

        val riffBody = infoList + sdtaList + pdtaList
        val out = ByteArray(12 + riffBody.size)
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        writeU32(out, 4, 4 + riffBody.size)  // RIFF size = "sfbk" tag + data
        out[8] = 's'.code.toByte(); out[9] = 'f'.code.toByte()
        out[10] = 'b'.code.toByte(); out[11] = 'k'.code.toByte()
        riffBody.copyInto(out, 12)
        return out
    }

    private fun buildSmpl(samples: List<SampleEntry>): ByteArray {
        val totalFrames = if (samples.isEmpty()) 0
        else samples.last().startFrame + samples.last().pcm16.size + SF2_SAMPLE_PADDING
        val out = ByteArray(totalFrames * 2)
        for (sample in samples) {
            var pos = sample.startFrame * 2
            for (s in sample.pcm16) {
                val v = s.toInt()
                out[pos] = (v and 0xFF).toByte()
                out[pos + 1] = ((v ushr 8) and 0xFF).toByte()
                pos += 2
            }
            // 46 padding samples already zero
        }
        return out
    }

    private fun buildInfoChunks(bankName: String): ByteArray {
        val ifil = ByteArray(4)
        writeU16(ifil, 0, 2); writeU16(ifil, 2, 1)  // version 2.01

        val isng = "EMU8000\u0000".encodeToByteArray()

        val nameBytes = bankName.encodeToByteArray()
        val inamLen = nameBytes.size + 1
        val inam = ByteArray(if (inamLen % 2 != 0) inamLen + 1 else inamLen)
        nameBytes.copyInto(inam)

        return buildChunk("ifil", ifil) + buildChunk("isng", isng) + buildChunk("INAM", inam)
    }

    private fun buildPdta(
        instruments: List<Instrument>,
        samples: List<SampleEntry>,
        sampleMap: Map<Pair<Int, Int>, Int>,
        psgMap: Map<Int, Int>,
    ): ByteArray {
        val n = instruments.size
        val totalRegions = instruments.sumOf { it.regions.size }
        val totalIgens = totalRegions * GEN_COUNT_PER_REGION + 1

        val phdr = ByteArray((n + 1) * 38)
        val pbag = ByteArray((n + 1) * 4)
        val pmod = ByteArray(10)  // terminal only
        val pgen = ByteArray((n * 2 + 1) * 4)
        val inst = ByteArray((n + 1) * 22)
        val ibag = ByteArray((totalRegions + 1) * 4)
        val imod = ByteArray(10)  // terminal only
        val igen = ByteArray(totalIgens * 4)
        val shdr = ByteArray((samples.size + 1) * 46)

        // --- phdr, pbag, pgen ---
        for ((i, instr) in instruments.withIndex()) {
            val ph = i * 38
            writeFixedString(phdr, ph, instr.name, 20)
            writeU16(phdr, ph + 20, instr.presetIndex)
            writeU16(phdr, ph + 22, 0)  // bank
            writeU16(phdr, ph + 24, i)  // bagNdx

            val pb = i * 4
            writeU16(pbag, pb, i * 2)   // genNdx
            writeU16(pbag, pb + 2, 0)   // modNdx

            val pg = i * 2 * 4
            writeU16(pgen, pg, GEN_REVERB_EFFECTS_SEND); writeU16(pgen, pg + 2, 0)
            writeU16(pgen, pg + 4, GEN_INSTRUMENT); writeU16(pgen, pg + 6, i)
        }
        val phTerm = n * 38
        writeFixedString(phdr, phTerm, "EOP", 20)
        writeU16(phdr, phTerm + 20, 0xFF); writeU16(phdr, phTerm + 22, 0xFF)
        writeU16(phdr, phTerm + 24, n)
        val pbTerm = n * 4
        writeU16(pbag, pbTerm, n * 2)

        // --- inst, ibag, igen ---
        var ibagNdx = 0
        var igenNdx = 0

        for ((i, instr) in instruments.withIndex()) {
            val instBase = i * 22
            writeFixedString(inst, instBase, instr.name, 20)
            writeU16(inst, instBase + 20, ibagNdx)

            for (region in instr.regions) {
                val ibBase = ibagNdx * 4
                writeU16(ibag, ibBase, igenNdx)
                writeU16(ibag, ibBase + 2, 0)

                val sampleIdx = resolveSampleIdx(region, sampleMap, psgMap)
                val sample = samples.getOrNull(sampleIdx)
                val loopFlag = sample?.loopFlag ?: false

                val attackTime = calcAttackTime(region.attack)
                val decayTime = calcFallingTime(region.decay)
                val sf2Sustain = calcSf2Sustain(region.sustain)
                val releaseTime = calcFallingTime(region.release)
                val sf2Pan = round((region.pan / 127.0 - 0.5) * 1000).toInt()

                fun gen(opcode: Int, amount: Int) {
                    writeU16(igen, igenNdx * 4, opcode)
                    writeU16(igen, igenNdx * 4 + 2, amount and 0xFFFF)
                    igenNdx++
                }

                // keyRange (range generator: lo + hi in amount bytes)
                writeU16(igen, igenNdx * 4, GEN_KEY_RANGE)
                igen[igenNdx * 4 + 2] = region.keyLow.toByte()
                igen[igenNdx * 4 + 3] = region.keyHigh.toByte()
                igenNdx++

                // velRange
                writeU16(igen, igenNdx * 4, GEN_VEL_RANGE)
                igen[igenNdx * 4 + 2] = 0
                igen[igenNdx * 4 + 3] = 127
                igenNdx++

                gen(GEN_INITIAL_ATTENUATION, 0)
                gen(GEN_PAN, sf2Pan)
                gen(GEN_SAMPLE_MODES, if (loopFlag) 1 else 0)
                gen(GEN_OVERRIDING_ROOT_KEY, region.unityKey)
                gen(GEN_COARSE_TUNE, 0)
                gen(GEN_FINE_TUNE, 0)
                gen(GEN_ATTACK_VOL_ENV, secondsToTimecents(attackTime))
                gen(GEN_HOLD_VOL_ENV, -12000)
                gen(GEN_DECAY_VOL_ENV, secondsToTimecents(decayTime))
                gen(GEN_SUSTAIN_VOL_ENV, sf2Sustain)
                gen(GEN_RELEASE_VOL_ENV, secondsToTimecents(releaseTime))
                gen(GEN_SAMPLE_ID, sampleIdx)

                ibagNdx++
            }
        }

        // inst terminal
        val instTerm = n * 22
        writeFixedString(inst, instTerm, "EOI", 20)
        writeU16(inst, instTerm + 20, ibagNdx)

        // ibag terminal
        writeU16(ibag, ibagNdx * 4, igenNdx)
        writeU16(ibag, ibagNdx * 4 + 2, 0)
        // igen terminal: all zeros at igenNdx*4 (already zero)

        // --- shdr ---
        for ((i, sample) in samples.withIndex()) {
            val sb = i * 46
            writeFixedString(shdr, sb, sample.name, 20)
            writeU32(shdr, sb + 20, sample.startFrame)
            writeU32(shdr, sb + 24, sample.startFrame + sample.pcm16.size + SF2_SAMPLE_PADDING)
            writeU32(shdr, sb + 28, sample.startFrame + sample.loopStartFrames)
            writeU32(shdr, sb + 32, sample.startFrame + sample.loopEndFrames)
            writeU32(shdr, sb + 36, sample.sampleRate)
            shdr[sb + 40] = 60  // originalPitch (overridden per-region by overridingRootKey)
            shdr[sb + 41] = 0   // pitchCorrection
            writeU16(shdr, sb + 42, 0)  // sampleLink
            writeU16(shdr, sb + 44, 1)  // monoSample
        }
        val shdrTerm = samples.size * 46
        writeFixedString(shdr, shdrTerm, "EOS", 20)

        return buildChunk("phdr", phdr) + buildChunk("pbag", pbag) + buildChunk("pmod", pmod) +
                buildChunk("pgen", pgen) + buildChunk("inst", inst) + buildChunk("ibag", ibag) +
                buildChunk("imod", imod) + buildChunk("igen", igen) + buildChunk("shdr", shdr)
    }

    private fun resolveSampleIdx(
        region: Region,
        sampleMap: Map<Pair<Int, Int>, Int>,
        psgMap: Map<Int, Int>,
    ): Int = when (region.psgType) {
        3 -> psgMap[-1] ?: 0
        2 -> psgMap[region.dutyCycle.coerceIn(0, 6)] ?: 0
        else -> sampleMap[Pair(region.waNum, region.sampleNum)] ?: 0
    }

    // -------------------------------------------------------------------------
    // RIFF chunk helpers
    // -------------------------------------------------------------------------

    private fun buildChunk(tag: String, data: ByteArray): ByteArray {
        val paddedSize = if (data.size % 2 != 0) data.size + 1 else data.size
        val out = ByteArray(8 + paddedSize)
        tag.encodeToByteArray().copyInto(out)
        writeU32(out, 4, data.size)
        data.copyInto(out, 8)
        return out
    }

    private fun buildList(tag: String, data: ByteArray): ByteArray {
        val out = ByteArray(12 + data.size)
        out[0] = 'L'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'S'.code.toByte(); out[3] = 'T'.code.toByte()
        writeU32(out, 4, 4 + data.size)
        tag.encodeToByteArray().copyInto(out, 8)
        data.copyInto(out, 12)
        return out
    }

    private fun writeFixedString(buf: ByteArray, off: Int, s: String, len: Int) {
        val bytes = s.encodeToByteArray()
        bytes.copyInto(buf, off, 0, minOf(bytes.size, len))
    }

    // -------------------------------------------------------------------------
    // Low-level I/O helpers
    // -------------------------------------------------------------------------

    private fun readU16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)

    private fun writeU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun writeU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
