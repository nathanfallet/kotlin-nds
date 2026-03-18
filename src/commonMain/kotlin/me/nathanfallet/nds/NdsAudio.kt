package me.nathanfallet.nds

import me.nathanfallet.nds.NdsAudio.WAVE_ADPCM
import me.nathanfallet.nds.NdsAudio.WAVE_PCM16
import me.nathanfallet.nds.NdsAudio.WAVE_PCM8
import me.nathanfallet.nds.NdsAudio.WAV_HEADER_SIZE


/**
 * Internal audio utility object for decoding NDS audio formats (PCM8, PCM16, IMA-ADPCM)
 * and writing standard RIFF WAV files.
 *
 * All output WAV files use signed 16-bit little-endian PCM regardless of the input wave type.
 * PCM8 input is upsampled to 16-bit by multiplying each sample value by 256.
 */
internal object NdsAudio {

    // -------------------------------------------------------------------------
    // Wave type constants (matches NSSAMP_WAVE_* in nssamp.h)
    // -------------------------------------------------------------------------

    /** Signed 8-bit PCM. */
    const val WAVE_PCM8: Int = 0

    /** Signed 16-bit little-endian PCM. */
    const val WAVE_PCM16: Int = 1

    /** 4-bit IMA-ADPCM with per-block header. */
    const val WAVE_ADPCM: Int = 2

    // -------------------------------------------------------------------------
    // IMA-ADPCM tables (from nssamp.c / process_nibble)
    // -------------------------------------------------------------------------

    private val ADPCM_STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14,
        16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658,
        724, 796, 876, 963, 1060, 1166, 1282, 1411,
        1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
        3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
        7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
        32767
    )

    private val IMA_INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    // -------------------------------------------------------------------------
    // WAV header size (44 bytes = RIFF(12) + fmt (24) + data(8))
    // -------------------------------------------------------------------------

    private const val WAV_HEADER_SIZE = 44

    // -------------------------------------------------------------------------
    // WAV builder
    // -------------------------------------------------------------------------

    /**
     * Writes a standard RIFF WAV header into [buf] at offset 0.
     *
     * The WAV always uses PCM format tag (0x0001) and 16-bit signed samples.
     *
     * @param buf The output buffer; must be at least [WAV_HEADER_SIZE] bytes.
     * @param channels Number of audio channels (1 = mono, 2 = stereo, …).
     * @param sampleRate Sample rate in Hz.
     * @param numPcmBytes Total size of the 16-bit PCM sample data that follows.
     */
    private fun writeWavHeader(buf: ByteArray, channels: Int, sampleRate: Int, numPcmBytes: Int) {
        val riffSize = numPcmBytes + WAV_HEADER_SIZE - 8
        // RIFF chunk
        buf[0x00] = 'R'.code.toByte(); buf[0x01] = 'I'.code.toByte()
        buf[0x02] = 'F'.code.toByte(); buf[0x03] = 'F'.code.toByte()
        writeU32(buf, 0x04, riffSize)
        buf[0x08] = 'W'.code.toByte(); buf[0x09] = 'A'.code.toByte()
        buf[0x0A] = 'V'.code.toByte(); buf[0x0B] = 'E'.code.toByte()
        // fmt sub-chunk
        buf[0x0C] = 'f'.code.toByte(); buf[0x0D] = 'm'.code.toByte()
        buf[0x0E] = 't'.code.toByte(); buf[0x0F] = ' '.code.toByte()
        writeU32(buf, 0x10, 16)                                    // fmt chunk size
        writeU16(buf, 0x14, 1)                                     // PCM format
        writeU16(buf, 0x16, channels)
        writeU32(buf, 0x18, sampleRate)
        writeU32(buf, 0x1C, sampleRate * channels * 2)             // byte rate
        writeU16(buf, 0x20, channels * 2)                          // block align
        writeU16(buf, 0x22, 16)                                    // bits per sample
        // data sub-chunk
        buf[0x24] = 'd'.code.toByte(); buf[0x25] = 'a'.code.toByte()
        buf[0x26] = 't'.code.toByte(); buf[0x27] = 'a'.code.toByte()
        writeU32(buf, 0x28, numPcmBytes)
    }

    // -------------------------------------------------------------------------
    // Sample decoder
    // -------------------------------------------------------------------------

    /**
     * Decodes a single NDS audio block into interleaved signed 16-bit PCM samples,
     * writing them to [dest] starting at [destOffset].
     *
     * For PCM8 and PCM16, each channel's block data is laid out contiguously in [blocks]:
     * `[channel-0 block][channel-1 block]...`  Each channel's sub-block is [lenBlockPerChan] bytes.
     * For ADPCM, the same layout applies; the first 4 bytes of each channel's block are the
     * ADPCM block header (s16 initialSample + u8 stepIndex + u8 padding).
     *
     * This mirrors `nsSampDecodeBlock` from nssamp.c.
     *
     * @param dest           Destination PCM output buffer (s16 LE, interleaved channels).
     * @param destOffset     Byte offset within [dest] to start writing.
     * @param blocks         Raw encoded block data (all channels concatenated).
     * @param blockOffset    Byte offset within [blocks] where the data starts.
     * @param lenBlockPerChan Number of bytes per channel in this block.
     * @param nSamples       Number of output samples (per channel) to produce.
     * @param waveType       One of [WAVE_PCM8], [WAVE_PCM16], [WAVE_ADPCM].
     * @param channels       Number of channels.
     */
    fun decodeBlock(
        dest: ByteArray,
        destOffset: Int,
        blocks: ByteArray,
        blockOffset: Int,
        lenBlockPerChan: Int,
        nSamples: Int,
        waveType: Int,
        channels: Int,
    ) {
        // Per-channel ADPCM state
        val adpcmSamp = IntArray(channels)
        val adpcmStep = IntArray(channels)
        val adpcmLow = BooleanArray(channels) { true }
        // transferedSize[ch] = how many bytes have been consumed from that channel's block so far
        val transferred = IntArray(channels)

        // Initialise ADPCM headers (first 4 bytes of each channel's block)
        if (waveType == WAVE_ADPCM) {
            for (ch in 0 until channels) {
                val chBase = blockOffset + ch * lenBlockPerChan
                adpcmSamp[ch] = readS16LE(blocks, chBase)
                adpcmStep[ch] = blocks[chBase + 2].toInt() and 0xFF
                transferred[ch] = 4
                adpcmLow[ch] = true
            }
        }

        var outPos = destOffset
        for (sampleId in 0 until nSamples) {
            for (ch in 0 until channels) {
                val chBase = blockOffset + ch * lenBlockPerChan
                val srcIdx = chBase + transferred[ch]

                val pcm16: Int
                when (waveType) {
                    WAVE_PCM8 -> {
                        // Signed 8-bit: upsample to 16-bit by * 256
                        val s8 = blocks[srcIdx].toInt()   // already sign-extended by Kotlin
                        pcm16 = s8 * 256
                        transferred[ch]++
                    }

                    WAVE_PCM16 -> {
                        pcm16 = readS16LE(blocks, srcIdx)
                        transferred[ch] += 2
                    }

                    WAVE_ADPCM -> {
                        val byteVal = blocks[srcIdx].toInt() and 0xFF
                        val nibble: Int
                        if (adpcmLow[ch]) {
                            // consume low nibble; do NOT advance byte counter yet
                            nibble = byteVal and 0x0F
                            adpcmLow[ch] = false
                        } else {
                            // consume high nibble; NOW advance
                            nibble = (byteVal ushr 4) and 0x0F
                            adpcmLow[ch] = true
                            transferred[ch]++
                        }
                        processNibble(nibble, adpcmStep, adpcmSamp, ch)
                        pcm16 = adpcmSamp[ch]
                    }

                    else -> pcm16 = 0
                }

                // Write s16 LE
                dest[outPos] = (pcm16 and 0xFF).toByte()
                dest[outPos + 1] = ((pcm16 ushr 8) and 0xFF).toByte()
                outPos += 2
            }
        }
    }

    /**
     * Applies one IMA-ADPCM nibble to the running decoder state for channel [ch].
     *
     * Implements the Nitro variant of IMA-ADPCM (slight clipping asymmetry, per GBATEK).
     *
     * @param nibble    4-bit code (0x0–0xF).
     * @param stepIdx   Per-channel step-index array; updated in place.
     * @param samp      Per-channel current-sample array; updated in place.
     * @param ch        Channel index.
     */
    private fun processNibble(nibble: Int, stepIdx: IntArray, samp: IntArray, ch: Int) {
        val code = nibble and 0x0F
        val step = ADPCM_STEP_TABLE[stepIdx[ch]]
        var diff = step ushr 3
        if (code and 1 != 0) diff += step ushr 2
        if (code and 2 != 0) diff += step ushr 1
        if (code and 4 != 0) diff += step
        if (code and 8 != 0) {
            samp[ch] -= diff
            if (samp[ch] < -32767) samp[ch] = -32767
        } else {
            samp[ch] += diff
            if (samp[ch] > 32767) samp[ch] = 32767
        }
        stepIdx[ch] = (stepIdx[ch] + IMA_INDEX_TABLE[code]).coerceIn(0, 88)
    }

    // -------------------------------------------------------------------------
    // STRM → WAV
    // -------------------------------------------------------------------------

    /**
     * Decodes the raw bytes of an NDS STRM file and returns a standard 16-bit PCM WAV.
     *
     * STRM layout:
     * - 0x00 magic "STRM" (0x4D525453), BOM at 0x04, header size 0x0010 at 0x0C
     * - HEAD block at 0x10: waveType(u8) hasLoop(u8) channels(u8) at 0x18, sampleRate(u16) at 0x1C
     * - numSamples(u32) at 0x24, dataOffset(u32) at 0x28, numBlocks(u32) at 0x2C
     * - lenBlockPerChan(u32) at 0x30, samplesPerBlock(u32) at 0x34
     * - lenLastBlockPerChan(u32) at 0x38, samplesPerLastBlock(u32) at 0x3C
     *
     * Block data at [dataOffset]: for each block, channel 0 data followed by channel 1 data, etc.
     *
     * @param data The raw STRM file bytes.
     * @return A WAV byte array with a 44-byte RIFF header followed by 16-bit PCM sample data.
     * @throws IllegalArgumentException if [data] is too small or does not begin with the STRM magic.
     */
    fun strmToWav(data: ByteArray): ByteArray {
        require(data.size >= 0x68) { "STRM data too small (${data.size} < 0x68)" }
        require(readU32(data, 0x00) == 0x4D525453L) { "Not an STRM file (bad magic)" }
        require(readU16(data, 0x04) == 0xFEFF) { "STRM: bad BOM" }
        require(readU16(data, 0x0C) == 0x0010) { "STRM: bad header size" }
        require(readU32(data, 0x10) == 0x44414548L) { "STRM: HEAD block not found" }

        val waveType = data[0x18].toInt() and 0xFF
        val channels = data[0x1A].toInt() and 0xFF
        val sampleRate = readU16(data, 0x1C)
        val numSamples = readU32(data, 0x24).toInt()
        val dataOffset = readU32(data, 0x28).toInt()
        val numBlocks = readU32(data, 0x2C).toInt()
        val lenBlockPerChan = readU32(data, 0x30).toInt()
        val samplesPerBlock = readU32(data, 0x34).toInt()
        val lenLastBlockPerChan = readU32(data, 0x38).toInt()
        val samplesPerLastBlock = readU32(data, 0x3C).toInt()

        require(channels > 0) { "STRM: channels must be > 0" }
        require(numBlocks > 0) { "STRM: numBlocks must be > 0" }

        // Total PCM output: numSamples × channels × 2 bytes (always 16-bit)
        val numPcmBytes = numSamples * channels * 2
        val out = ByteArray(WAV_HEADER_SIZE + numPcmBytes)
        writeWavHeader(out, channels, sampleRate, numPcmBytes)

        var srcOffset = dataOffset
        var dstOffset = WAV_HEADER_SIZE

        // Decode all blocks except the last
        for (blockId in 0 until numBlocks - 1) {
            decodeBlock(
                dest = out,
                destOffset = dstOffset,
                blocks = data,
                blockOffset = srcOffset,
                lenBlockPerChan = lenBlockPerChan,
                nSamples = samplesPerBlock,
                waveType = waveType,
                channels = channels,
            )
            dstOffset += samplesPerBlock * channels * 2
            srcOffset += lenBlockPerChan * channels
        }
        // Decode the last block
        decodeBlock(
            dest = out,
            destOffset = dstOffset,
            blocks = data,
            blockOffset = srcOffset,
            lenBlockPerChan = lenLastBlockPerChan,
            nSamples = samplesPerLastBlock,
            waveType = waveType,
            channels = channels,
        )

        return out
    }

    // -------------------------------------------------------------------------
    // SWAR → list of WAVs
    // -------------------------------------------------------------------------

    /**
     * Decodes all SWAV samples from a raw SWAR (wave-archive) file and returns one WAV per sample.
     *
     * SWAR layout:
     * - 0x00: "SWAR" magic, standard NDS block header (BOM at 0x04, file size at 0x08, …)
     * - 0x10: "DATA" block magic
     * - 0x18: DATA block body begins
     *   - 0x38 (= 0x18 + 0x20): u32 nEntries
     *   - 0x3C (= 0x18 + 0x24): u32 offset[0], offset[1], … (absolute from SWAR start)
     *
     * Each offset points to a 12-byte SWAV header followed by sample data:
     * - +0x00: waveType (u8)
     * - +0x01: hasLoop (u8)
     * - +0x02: sampleRate (u16 LE)
     * - +0x04: time (u16 LE)
     * - +0x06: loopStart (u16 LE) — in 32-bit words
     * - +0x08: loopLen (u32 LE) — in 32-bit words
     * - +0x0C: sample data begins
     *
     * @param data The raw SWAR file bytes.
     * @return A list of WAV byte arrays, one per SWAV entry; empty if nEntries == 0.
     * @throws IllegalArgumentException if [data] is too small or does not begin with "SWAR".
     */
    fun swarToWavList(data: ByteArray): List<ByteArray> {
        // Minimum: 0x3C bytes to read nEntries at 0x38 (the C reference only checks the magic).
        require(data.size >= 0x3C) { "SWAR data too small (${data.size} < 0x3C)" }
        require(
            data[0] == 'S'.code.toByte() && data[1] == 'W'.code.toByte() &&
                    data[2] == 'A'.code.toByte() && data[3] == 'R'.code.toByte()
        ) {
            "Not an SWAR file (bad magic)"
        }

        val nEntries = readU32(data, 0x38).toInt()
        if (nEntries == 0) return emptyList()

        val result = mutableListOf<ByteArray>()
        for (i in 0 until nEntries) {
            val swavOffset = readU32(data, 0x3C + i * 4).toInt()
            // Determine the byte length of this SWAV's data section
            val swavDataEnd: Int = if (i < nEntries - 1) {
                readU32(data, 0x3C + (i + 1) * 4).toInt()
            } else {
                data.size
            }
            result.add(decodeSwarEntry(data, swavOffset, swavDataEnd))
        }
        return result
    }

    /**
     * Decodes one SWAV entry from inside a SWAR byte array.
     *
     * The entry starts at [entryOffset] and ends at [entryEnd] (exclusive).
     *
     * @param data        The raw SWAR file bytes.
     * @param entryOffset Absolute offset within [data] of the 12-byte SWAV header.
     * @param entryEnd    Exclusive end offset (start of next entry, or end of file).
     * @return A WAV byte array for this SWAV sample.
     */
    private fun decodeSwarEntry(data: ByteArray, entryOffset: Int, entryEnd: Int): ByteArray {
        // 12-byte SWAV header
        val waveType = data[entryOffset + 0x00].toInt() and 0xFF
        // hasLoop at +0x01 (unused for decoding the whole sample)
        val sampleRate = readU16(data, entryOffset + 0x02)
        // time at +0x04 (unused)
        val loopStartWords = readU16(data, entryOffset + 0x06)   // u16 in 32-bit words
        val loopLenWords = readU32(data, entryOffset + 0x08).toInt()   // u32 in 32-bit words

        val loopStartInBytes = loopStartWords * 4
        val loopLenInBytes = loopLenWords * 4

        // numSamples calculation mirrors nsSwavCreateFromSamp
        val firstSampOfs = if (waveType == WAVE_ADPCM) 4 else 0
        val numSamples: Int = when (waveType) {
            WAVE_PCM8 -> {
                // 1 byte per sample; loopStart + loopLen in bytes
                (loopStartInBytes - firstSampOfs) + loopLenInBytes
            }

            WAVE_PCM16 -> {
                // 2 bytes per sample
                (loopStartInBytes - firstSampOfs) / 2 + loopLenInBytes / 2
            }

            WAVE_ADPCM -> {
                // nibbles (2 per byte); subtract 4-byte ADPCM header from loopStart
                (loopStartInBytes - firstSampOfs) * 2 + loopLenInBytes * 2
            }

            else -> 0
        }

        val dataOffset = entryOffset + 0x0C
        // Use the actual data size from the header fields, matching nsSwavCreateFromSamp:
        //   dataSize = loopStartInBytes + loopLenInBytes
        // (entryEnd - dataOffset may include trailing padding between entries)
        val dataSize = minOf(loopStartInBytes + loopLenInBytes, entryEnd - dataOffset)

        val numPcmBytes = numSamples * 2   // always 16-bit output
        val out = ByteArray(WAV_HEADER_SIZE + numPcmBytes)
        writeWavHeader(out, 1, sampleRate, numPcmBytes)

        // For SWAV: one block = the entire sample (all data in one call)
        if (numSamples > 0 && dataSize > 0) {
            decodeBlock(
                dest = out,
                destOffset = WAV_HEADER_SIZE,
                blocks = data,
                blockOffset = dataOffset,
                lenBlockPerChan = dataSize,
                nSamples = numSamples,
                waveType = waveType,
                channels = 1,
            )
        }

        return out
    }

    // -------------------------------------------------------------------------
    // Low-level helpers
    // -------------------------------------------------------------------------

    /**
     * Reads a little-endian unsigned 32-bit integer from [buf] at [off].
     *
     * @param buf Source byte array.
     * @param off Byte offset.
     * @return The decoded value as a [Long] in the range 0..4294967295.
     */
    internal fun readU32(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)

    /**
     * Reads a little-endian unsigned 16-bit integer from [buf] at [off].
     *
     * @param buf Source byte array.
     * @param off Byte offset.
     * @return The decoded value as an [Int] in the range 0..65535.
     */
    internal fun readU16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    /**
     * Reads a little-endian signed 16-bit integer from [buf] at [off].
     *
     * @param buf Source byte array.
     * @param off Byte offset.
     * @return The decoded value as an [Int] in the range -32768..32767.
     */
    internal fun readS16LE(buf: ByteArray, off: Int): Int {
        val v = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    /**
     * Writes a little-endian unsigned 32-bit integer [v] into [buf] at [off].
     *
     * @param buf Target byte array.
     * @param off Byte offset.
     * @param v   Value to write.
     */
    private fun writeU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /**
     * Writes a little-endian unsigned 16-bit integer [v] into [buf] at [off].
     *
     * @param buf Target byte array.
     * @param off Byte offset.
     * @param v   Value to write (lower 16 bits).
     */
    private fun writeU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }
}
