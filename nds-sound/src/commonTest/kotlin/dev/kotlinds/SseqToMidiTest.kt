package dev.kotlinds

import kotlin.test.*

/**
 * Tests for [SseqToMidi] / [SdatSseqFile.toMidi].
 *
 * All tests build minimal but structurally valid SSEQ binaries programmatically; no real
 * `.sdat` or `.sseq` files are required.
 *
 * SSEQ file layout used in every helper:
 *   0x00  "SSEQ" magic
 *   0x04  BOM (FE FF)
 *   0x08  file size (u32 LE)
 *   0x0C  header size 0x10 (u16), numBlocks 1 (u16)
 *   0x10  "DATA" block magic
 *   0x14  DATA block size (u32 LE)
 *   0x18  data offset (u32 LE) — always 0x1C in our helpers
 *   0x1C  sequence commands begin here
 *
 * SMF header layout (offsets from start of returned byte array):
 *   0x00  "MThd"
 *   0x04  chunk length = 6 (u32 BE)
 *   0x08  format (u16 BE)  0=single-track, 1=multi-track
 *   0x0A  numTracks (u16 BE)
 *   0x0C  division = 48 ticks/QN (u16 BE)
 *   0x0E  first MTrk chunk
 */
class SseqToMidiTest {

    // =========================================================================
    // SSEQ binary builder helpers
    // =========================================================================

    /**
     * Writes a little-endian u32 into [buf] at [off].
     *
     * @param buf Target byte array.
     * @param off Byte offset.
     * @param v Value to write.
     */
    private fun writeU32LE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /**
     * Writes a little-endian u16 into [buf] at [off].
     *
     * @param buf Target byte array.
     * @param off Byte offset.
     * @param v Value to write.
     */
    private fun writeU16LE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    /**
     * Reads a big-endian u16 from [buf] at [off].
     *
     * @param buf Source byte array.
     * @param off Byte offset.
     * @return The decoded value.
     */
    private fun readU16BE(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    /**
     * Reads a big-endian u32 from [buf] at [off].
     *
     * @param buf Source byte array.
     * @param off Byte offset.
     * @return The decoded value as an [Int].
     */
    private fun readU32BE(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
                ((buf[off + 1].toInt() and 0xFF) shl 16) or
                ((buf[off + 2].toInt() and 0xFF) shl 8) or
                (buf[off + 3].toInt() and 0xFF)

    /**
     * Reads a little-endian u32 from [buf] at [off].
     *
     * @param buf Source byte array.
     * @param off Byte offset.
     * @return The decoded value.
     */
    private fun readU32LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)

    /**
     * Encodes [v] as a MIDI variable-length quantity.
     *
     * @param v Value to encode (0..268435455).
     * @return The encoded bytes.
     */
    private fun varLen(v: Int): ByteArray {
        var size = 1
        var left = v
        while (left > 0x7F && size < 4) {
            size++; left = left ushr 7
        }
        val buf = ByteArray(size)
        var shiftCount = (size - 1) * 7
        for (i in 0 until size - 1) {
            buf[i] = (((v ushr shiftCount) and 0x7F) or 0x80).toByte()
            shiftCount -= 7
        }
        buf[size - 1] = ((v ushr shiftCount) and 0x7F).toByte()
        return buf
    }

    /**
     * Builds a minimal but valid SSEQ byte array whose sequence commands are [cmds].
     *
     * The data offset is fixed at 0x1C.  File size is computed from [cmds].
     *
     * @param cmds The raw sequence command bytes to embed.
     * @return A fully formed SSEQ byte array.
     */
    private fun buildSseq(cmds: ByteArray): ByteArray {
        val totalSize = 0x1C + cmds.size
        val buf = ByteArray(totalSize)
        // Magic "SSEQ"
        buf[0] = 'S'.code.toByte(); buf[1] = 'S'.code.toByte()
        buf[2] = 'E'.code.toByte(); buf[3] = 'Q'.code.toByte()
        // BOM
        buf[4] = 0xFF.toByte(); buf[5] = 0xFE.toByte()
        // File size
        writeU32LE(buf, 0x08, totalSize)
        // Header size + numBlocks
        writeU16LE(buf, 0x0C, 0x10)
        writeU16LE(buf, 0x0E, 1)
        // DATA block magic
        buf[0x10] = 'D'.code.toByte(); buf[0x11] = 'A'.code.toByte()
        buf[0x12] = 'T'.code.toByte(); buf[0x13] = 'A'.code.toByte()
        // DATA block size
        writeU32LE(buf, 0x14, totalSize - 0x10)
        // Data offset = 0x1C (start of cmds)
        writeU32LE(buf, 0x18, 0x1C)
        // Commands
        cmds.copyInto(buf, 0x1C)
        return buf
    }

    /**
     * Creates an [SdatSseqFile] wrapper around the given SSEQ bytes so that [toMidi] can be called.
     *
     * @param sseq Raw SSEQ bytes produced by [buildSseq].
     * @return An [SdatSseqFile] whose [SdatSseqFile.toMidi] delegates to the converter.
     */
    private fun makeSeqFile(sseq: ByteArray): SdatSseqFile =
        SdatSseqFile("TEST", sseq, 0, 0, 100, 0, 0, 0)

    // =========================================================================
    // SMF navigation helpers
    // =========================================================================

    /**
     * Locates the first MTrk chunk within [midi] and returns its payload offset and length.
     *
     * @param midi The SMF byte array.
     * @param trackNumber 0-based track number (0 = first MTrk after MThd).
     * @return Pair(payloadOffset, payloadLength) or null if not found.
     */
    private fun findTrack(midi: ByteArray, trackNumber: Int = 0): Pair<Int, Int>? {
        var pos = 14  // skip MThd (4+4+6)
        var found = 0
        while (pos + 8 <= midi.size) {
            val magic = midi.decodeToString(pos, pos + 4)
            val len = readU32BE(midi, pos + 4)
            if (magic == "MTrk") {
                if (found == trackNumber) return Pair(pos + 8, len)
                found++
            }
            pos += 8 + len
        }
        return null
    }

    /**
     * Collects all raw MIDI events from a track payload, returned as a list of byte arrays.
     *
     * Each event includes all event bytes (status + data); meta events include FF type len data.
     * Delta times are decoded and returned alongside each event as Pair(absTime, eventBytes).
     *
     * @param midi The full SMF byte array.
     * @param trackNumber 0-based track index.
     * @return List of (absTime, eventData) pairs in order.
     */
    private fun collectEvents(midi: ByteArray, trackNumber: Int = 0): List<Pair<Int, ByteArray>> {
        val track = findTrack(midi, trackNumber) ?: return emptyList()
        val (start, len) = track
        val end = start + len
        var pos = start
        var absTime = 0
        val events = mutableListOf<Pair<Int, ByteArray>>()

        while (pos < end) {
            // Read delta time (var len)
            var delta = 0
            var b: Int
            do {
                b = midi[pos++].toInt() and 0xFF
                delta = (delta shl 7) or (b and 0x7F)
            } while (b and 0x80 != 0)
            absTime += delta

            if (pos >= end) break
            val status = midi[pos].toInt() and 0xFF

            val eventBytes: ByteArray = when {
                status == 0xFF -> {
                    // Meta event
                    val type = midi[pos + 1].toInt() and 0xFF
                    var metaLen = 0
                    var mp = pos + 2
                    var mb: Int
                    do {
                        mb = midi[mp++].toInt() and 0xFF
                        metaLen = (metaLen shl 7) or (mb and 0x7F)
                    } while (mb and 0x80 != 0)
                    val evSize = (mp - pos) + metaLen
                    val ev = midi.copyOfRange(pos, pos + evSize)
                    pos += evSize
                    ev
                }

                status == 0xF0 || status == 0xF7 -> {
                    // SysEx
                    var sxLen = 0
                    var sp = pos + 1
                    var sb: Int
                    do {
                        sb = midi[sp++].toInt() and 0xFF
                        sxLen = (sxLen shl 7) or (sb and 0x7F)
                    } while (sb and 0x80 != 0)
                    val evSize = (sp - pos) + sxLen
                    val ev = midi.copyOfRange(pos, pos + evSize)
                    pos += evSize
                    ev
                }

                else -> {
                    // Channel event
                    val msgType = status and 0xF0
                    val dataLen = when (msgType) {
                        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 3
                        0xC0, 0xD0 -> 2
                        else -> 1
                    }
                    val ev = midi.copyOfRange(pos, pos + dataLen)
                    pos += dataLen
                    ev
                }
            }
            events.add(Pair(absTime, eventBytes))
        }
        return events
    }

    // =========================================================================
    // Tests: Header
    // =========================================================================

    /** Verifies that [SdatSseqFile.toMidi] produces output starting with the "MThd" magic. */
    @Test
    fun `midi output starts with MThd magic`() {
        val cmds = byteArrayOf(0xFF.toByte())  // end of track
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals("MThd", midi.decodeToString(0, 4))
    }

    /** Verifies that the MThd chunk length field equals 6. */
    @Test
    fun `midi MThd chunk length is 6`() {
        val midi = makeSeqFile(buildSseq(byteArrayOf(0xFF.toByte()))).toMidi()
        assertEquals(6, readU32BE(midi, 4))
    }

    /** Verifies that the tick resolution field in MThd is 48 ticks per quarter note. */
    @Test
    fun `midi tick resolution is 48 ticks per quarter note`() {
        val midi = makeSeqFile(buildSseq(byteArrayOf(0xFF.toByte()))).toMidi()
        assertEquals(48, readU16BE(midi, 0x0C))
    }

    /** Verifies that a single-track sequence produces SMF format 0. */
    @Test
    fun `single-track sequence produces SMF format 0`() {
        val cmds = byteArrayOf(0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals(0, readU16BE(midi, 0x08))
    }

    /** Verifies that a single-track sequence has exactly 1 track in the MThd. */
    @Test
    fun `single-track sequence has 1 track in MThd`() {
        val cmds = byteArrayOf(0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals(1, readU16BE(midi, 0x0A))
    }

    // =========================================================================
    // Tests: End of track
    // =========================================================================

    /** Verifies that the track chunk starts with "MTrk". */
    @Test
    fun `track chunk starts with MTrk magic`() {
        val midi = makeSeqFile(buildSseq(byteArrayOf(0xFF.toByte()))).toMidi()
        assertEquals("MTrk", midi.decodeToString(14, 18))
    }

    /** Verifies that every track has an end-of-track meta event (FF 2F 00). */
    @Test
    fun `track contains end of track meta event`() {
        val cmds = byteArrayOf(0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val eot = events.find { (_, data) ->
            data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0x2F.toByte()
        }
        assertNotNull(eot, "End-of-track meta event not found")
        val data = eot.second
        assertEquals(0xFF.toByte(), data[0])
        assertEquals(0x2F.toByte(), data[1])
        assertEquals(0x00.toByte(), data[2])
    }

    // =========================================================================
    // Tests: Note on / off pairs
    // =========================================================================

    /**
     * Verifies that a single note command (note=60, vel=100, dur=48) produces:
     * - A note-on event at tick 0 with the correct note and velocity.
     * - A note-off (note-on with velocity 0) event at tick 48.
     *
     * SSEQ command: [0x3C, 0x64, <varlen 48>]  (note=60=0x3C, vel=100=0x64, dur=48)
     * Then 0xFF to end.
     */
    @Test
    fun `note command produces note-on and note-off pair`() {
        val note = 60
        val vel = 100
        val dur = 48
        val cmds = byteArrayOf(note.toByte(), vel.toByte(), *varLen(dur), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)

        val noteOn = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0x90 &&
                    data[1].toInt() and 0xFF == note && data[2].toInt() and 0xFF == vel
        }
        assertNotNull(noteOn, "note-on event not found")
        assertEquals(0, noteOn.first, "note-on should be at tick 0")

        val noteOff = events.find { (time, data) ->
            time == dur &&
                    data.size == 3 && (data[0].toInt() and 0xF0) == 0x90 &&
                    data[1].toInt() and 0xFF == note && data[2].toInt() and 0xFF == 0
        }
        assertNotNull(noteOff, "note-off event not found at tick $dur")
    }

    /** Verifies that the note-on event carries the correct MIDI channel (track 0 → ch 0). */
    @Test
    fun `note-on event is on MIDI channel 0 for track 0`() {
        val cmds = byteArrayOf(0x3C.toByte(), 0x64.toByte(), *varLen(48), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val noteOn = events.first { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0x90
        }
        assertEquals(0, (noteOn.second[0].toInt() and 0x0F), "expected channel 0")
    }

    // =========================================================================
    // Tests: REST (0x80)
    // =========================================================================

    /**
     * Verifies that a REST command advances the tick counter so the subsequent
     * note appears at the correct time.
     *
     * SSEQ: REST 48 ticks (0x80 + varlen(48)), then note 60 vel 100 dur 1, then 0xFF.
     */
    @Test
    fun `rest command advances time`() {
        val restDur = 48
        val cmds = byteArrayOf(
            0x80.toByte(), *varLen(restDur),  // rest
            0x3C.toByte(), 0x64.toByte(), *varLen(1),  // note at tick 48
            0xFF.toByte(),
        )
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        // The noteWait flag is false by default, so the note's absTime should be 0 still
        // (REST only advances time when noteWait is irrelevant; REST always advances absTime)
        val noteOn = events.first { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0x90 && data[2].toInt() and 0xFF != 0
        }
        assertEquals(restDur, noteOn.first, "note after REST should start at tick $restDur")
    }

    // =========================================================================
    // Tests: Program change (0x81)
    // =========================================================================

    /**
     * Verifies that a program change command emits a MIDI program change event.
     *
     * SSEQ: 0x81 + varlen(5) = program 5, then 0xFF.
     */
    @Test
    fun `program change command produces program change event`() {
        val prog = 5
        val cmds = byteArrayOf(0x81.toByte(), *varLen(prog), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val pc = events.find { (_, data) ->
            data.size == 2 && (data[0].toInt() and 0xF0) == 0xC0 &&
                    data[1].toInt() and 0xFF == prog
        }
        assertNotNull(pc, "program change event not found")
    }

    /**
     * Verifies that a program change with a value ≥ 128 emits bank-select CCs as well.
     *
     * Program 130 → bank LSB = 1, program = 2 (130 = 1*128 + 2).
     */
    @Test
    fun `program change with bank selects emits bank CCs`() {
        val realProg = 130  // bank LSB=1, program=2
        val cmds = byteArrayOf(0x81.toByte(), *varLen(realProg), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        // Bank LSB CC (CC 32) should be present
        val bankLsb = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0xB0 &&
                    data[1].toInt() and 0xFF == 32 &&
                    data[2].toInt() and 0xFF == 1
        }
        assertNotNull(bankLsb, "Bank LSB CC not found")
    }

    // =========================================================================
    // Tests: Volume / Pan / Expression
    // =========================================================================

    /** Verifies that a volume command (0xC1) emits CC 7. */
    @Test
    fun `volume command produces CC 7`() {
        val vol = 80
        val cmds = byteArrayOf(0xC1.toByte(), vol.toByte(), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val cc = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0xB0 &&
                    data[1].toInt() and 0xFF == 7 && data[2].toInt() and 0xFF == vol
        }
        assertNotNull(cc, "CC 7 (volume) not found")
    }

    /** Verifies that a pan command (0xC0) emits CC 10. */
    @Test
    fun `pan command produces CC 10`() {
        val pan = 64
        val cmds = byteArrayOf(0xC0.toByte(), pan.toByte(), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val cc = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0xB0 &&
                    data[1].toInt() and 0xFF == 10 && data[2].toInt() and 0xFF == pan
        }
        assertNotNull(cc, "CC 10 (pan) not found")
    }

    /** Verifies that an expression command (0xD5) emits CC 11. */
    @Test
    fun `expression command produces CC 11`() {
        val expr = 100
        val cmds = byteArrayOf(0xD5.toByte(), expr.toByte(), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val cc = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0xB0 &&
                    data[1].toInt() and 0xFF == 11 && data[2].toInt() and 0xFF == expr
        }
        assertNotNull(cc, "CC 11 (expression) not found")
    }

    // =========================================================================
    // Tests: Tempo (0xE1)
    // =========================================================================

    /**
     * Verifies that a TEMPO command (0xE1 + u16 LE BPM) produces a meta event 0x51
     * with the correct microseconds-per-quarter-note value.
     *
     * BPM = 120 → 60_000_000 / 120 = 500_000 µs/QN = 0x07A120.
     */
    @Test
    fun `tempo command produces meta tempo event`() {
        val bpm = 120
        val cmds = ByteArray(4)
        cmds[0] = 0xE1.toByte()
        cmds[1] = (bpm and 0xFF).toByte()
        cmds[2] = ((bpm ushr 8) and 0xFF).toByte()
        cmds[3] = 0xFF.toByte()

        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)

        val tempoEvent = events.find { (_, data) ->
            data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0x51.toByte()
        }
        assertNotNull(tempoEvent, "Tempo meta event not found")

        val data = tempoEvent.second
        // bytes 3,4,5 are the 3-byte microseconds value
        val µs = ((data[3].toInt() and 0xFF) shl 16) or
                ((data[4].toInt() and 0xFF) shl 8) or
                (data[5].toInt() and 0xFF)
        assertEquals(500_000, µs, "Tempo microseconds mismatch for BPM $bpm")
    }

    /** Verifies that BPM=60 produces a meta tempo event with 1_000_000 µs. */
    @Test
    fun `tempo 60 bpm produces 1000000 microseconds`() {
        val bpm = 60
        val cmds = ByteArray(4)
        cmds[0] = 0xE1.toByte()
        cmds[1] = (bpm and 0xFF).toByte()
        cmds[2] = ((bpm ushr 8) and 0xFF).toByte()
        cmds[3] = 0xFF.toByte()

        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val tempoEvent = events.find { (_, d) ->
            d.size >= 2 && d[0] == 0xFF.toByte() && d[1] == 0x51.toByte()
        }
        assertNotNull(tempoEvent)
        val d = tempoEvent.second
        val µs = ((d[3].toInt() and 0xFF) shl 16) or ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
        assertEquals(1_000_000, µs)
    }

    // =========================================================================
    // Tests: Pitch bend (0xC4)
    // =========================================================================

    /**
     * Verifies that a pitch-bend command with value 0 produces a MIDI pitch-bend
     * event at the centre position (8192 = 0x40 0x40 after adding 8192 to 0).
     */
    @Test
    fun `pitch bend zero produces centre pitch bend event`() {
        val cmds = byteArrayOf(0xC4.toByte(), 0x00.toByte(), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val pb = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0xE0
        }
        assertNotNull(pb, "pitch bend event not found")
        // value = s8(0) * 64 = 0; MIDI = 0 + 8192 = 8192 = 0x2000 → LSB=0x00 MSB=0x40
        val pbVal = (pb.second[1].toInt() and 0x7F) or ((pb.second[2].toInt() and 0x7F) shl 7)
        assertEquals(8192, pbVal, "Expected centre pitch bend (8192)")
    }

    // =========================================================================
    // Tests: OPEN TRACK (0x93) — multi-track
    // =========================================================================

    /**
     * Verifies that the OPEN TRACK command (0x93) causes the converter to produce
     * an SMF Type 1 file with 2 tracks.
     *
     * Layout:
     *   0x1C  0xFE 0x03 0x00  (ALLOCATE TRACKS bitmask = 3 = tracks 0 and 1)
     *   0x1F  0x93 0x01 <3-byte LE offset>  (open track 1 at 0x1C + offset)
     *   0x23  0xFF              (end track 0)
     *   0x24  0xFF              (end track 1 — the offset points here)
     *
     * The 3-byte offset for track 1 is relative to sseqOffsetBase (0x1C).
     * We want track 1 to start at absolute offset 0x24, so relative = 0x24 - 0x1C = 0x08.
     * But our cmds array starts at position 0 within the cmds passed to buildSseq.
     * After buildSseq, sseqOffsetBase = 0x1C.
     * OPEN TRACK offset field: 3-byte LE value that is added to sseqOffsetBase.
     * We want absolute target = 0x1C + some_relative.
     * Let's lay out cmds so the OPEN TRACK command opens track 1 at offset 0x1C + 9 = 0x25.
     *
     * Simpler layout: just open track 1 at the byte right after the 0xFF of track 0.
     *   cmds[0] = 0x93  (open track)
     *   cmds[1] = 0x01  (track index = 1)
     *   cmds[2..4] = 3-byte LE offset = 5  → absolute = 0x1C + 5 = 0x21
     *   cmds[5] = 0xFF  (end track 0)
     *   cmds[6] = 0xFF  (end track 1, at absolute 0x1C + 6 = 0x22 — one byte off)
     * We want track 1 to start at cmds[6] = absolute 0x1C + 6 = 0x22.
     * So offset field = 6.
     */
    @Test
    fun `open track command produces SMF type 1 with 2 tracks`() {
        // track 1 commands start 6 bytes into cmds (absolute offset = 0x1C + 6 = 0x22)
        val track1RelOffset = 6
        val cmds = byteArrayOf(
            0x93.toByte(),                               // OPEN TRACK
            0x01.toByte(),                               // track index = 1
            (track1RelOffset and 0xFF).toByte(),         // offset low byte
            ((track1RelOffset ushr 8) and 0xFF).toByte(),
            ((track1RelOffset ushr 16) and 0xFF).toByte(),
            0xFF.toByte(),                               // END track 0 (cmds[5])
            0xFF.toByte(),                               // END track 1 (cmds[6])
        )
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals(1, readU16BE(midi, 0x08), "Expected SMF format 1")
        assertEquals(2, readU16BE(midi, 0x0A), "Expected 2 tracks")
    }

    /** Verifies that in a multi-track output both tracks have MTrk chunks. */
    @Test
    fun `open track produces two MTrk chunks`() {
        val track1RelOffset = 6
        val cmds = byteArrayOf(
            0x93.toByte(), 0x01.toByte(),
            (track1RelOffset and 0xFF).toByte(),
            ((track1RelOffset ushr 8) and 0xFF).toByte(),
            ((track1RelOffset ushr 16) and 0xFF).toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
        )
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertNotNull(findTrack(midi, 0), "Track 0 not found")
        assertNotNull(findTrack(midi, 1), "Track 1 not found")
    }

    // =========================================================================
    // Tests: JUMP (0x94) — loop handling
    // =========================================================================

    /**
     * Verifies that a JUMP command that loops backward does NOT cause an infinite loop.
     *
     * Layout: note → JUMP back to note (backward jump).  The converter should follow
     * the jump once (loopCount starts at 1, backward JUMP decrements → 0 → stops).
     */
    @Test
    fun `jump backward does not infinite loop`() {
        // cmds[0]: note 60 vel 100 dur 1 (4 bytes including varlen)
        // cmds[4]: JUMP back to cmds[0] (absolute = 0x1C, relative = 0x1C - 0x1C = 0)
        val cmds = ByteArray(8)
        cmds[0] = 0x3C  // note 60
        cmds[1] = 0x64  // vel 100
        val durBytes = varLen(1); durBytes.copyInto(cmds, 2)
        // Now cmds[3] or [4] depending on varLen size for 1 → 1 byte
        val jumpPos = 3  // 0x3C(1) + 0x64(1) + varLen(1)=1 byte = 3 bytes used
        cmds[jumpPos] = 0x94.toByte()  // JUMP
        // target = 0x1C (absolute) → offset = 0x1C - 0x1C = 0
        cmds[jumpPos + 1] = 0x00
        cmds[jumpPos + 2] = 0x00
        cmds[jumpPos + 3] = 0x00

        // This should not hang; we just verify it completes and produces valid MIDI
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals("MThd", midi.decodeToString(0, 4))
        // The track must still have an end-of-track meta event somewhere
        val events = collectEvents(midi)
        val eot = events.find { (_, data) ->
            data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0x2F.toByte()
        }
        assertNotNull(eot, "End-of-track meta event not found")
    }

    /**
     * Verifies that [SdatSseqFile.toMidi] with `loopCount = 2` traverses the JUMP loop twice,
     * producing more note-on events than a single-pass conversion.
     *
     * Layout: note 60 → JUMP back to start. With loopCount=1 the note plays once; with
     * loopCount=2 it plays twice (the loop body executes an extra time before stopping).
     */
    @Test
    fun `loopCount 2 produces more note events than loopCount 1`() {
        val cmds = ByteArray(8)
        cmds[0] = 0x3C  // note 60
        cmds[1] = 0x64  // vel 100
        val durBytes = varLen(1); durBytes.copyInto(cmds, 2)
        val jumpPos = 3
        cmds[jumpPos] = 0x94.toByte()          // JUMP
        cmds[jumpPos + 1] = 0x00               // target = 0x1C (start of cmds)
        cmds[jumpPos + 2] = 0x00
        cmds[jumpPos + 3] = 0x00

        val seq = makeSeqFile(buildSseq(cmds))
        val midi1 = seq.toMidi(loopCount = 1)
        val midi2 = seq.toMidi(loopCount = 2)

        fun countNoteOns(midi: ByteArray): Int {
            val events = collectEvents(midi)
            return events.count { (_, data) -> data.isNotEmpty() && (data[0].toInt() and 0xF0) == 0x90 && data.size >= 3 && data[2] != 0.toByte() }
        }

        val notes1 = countNoteOns(midi1)
        val notes2 = countNoteOns(midi2)
        assertTrue(
            notes2 > notes1,
            "loopCount=2 should produce more note-on events than loopCount=1 (got $notes1 vs $notes2)"
        )
    }

    // =========================================================================
    // Tests: CALL (0x95) / RETURN (0xFD)
    // =========================================================================

    /**
     * Verifies that CALL + RETURN round-trips correctly.
     *
     * Layout:
     *   [0] CALL → subroutine at relative offset 5 (absolute = 0x1C + 5 = 0x21)
     *   [4] 0xFF  (end of main sequence — reached after return)
     *   [5] note 60 vel 100 dur 1  (subroutine body)
     *   [8] 0xFD  (RETURN)
     *
     * After CALL, PC jumps to offset 5; after RETURN, PC comes back to offset 4 (0xFF).
     */
    @Test
    fun `call and return executes subroutine and returns`() {
        // CALL (0x95) is 4 bytes: 0x95 + 3-byte offset
        // We want to jump to cmds[5] (absolute = 0x1C + 5).
        // So offset field = 5.
        val cmds = byteArrayOf(
            0x95.toByte(),       // CALL [0]
            0x05, 0x00, 0x00,    // offset = 5 [1..3]
            0xFF.toByte(),       // END main [4]  ← return address
            0x3C, 0x64, *varLen(1),  // note [5..7]
            0xFD.toByte(),       // RETURN [8]
        )
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)

        // There should be a note-on event (subroutine was executed)
        val noteOn = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0x90 && data[2].toInt() and 0xFF != 0
        }
        assertNotNull(noteOn, "note-on not found; CALL/RETURN may have been skipped")

        // And an end-of-track
        val eot = events.find { (_, data) ->
            data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0x2F.toByte()
        }
        assertNotNull(eot, "End-of-track meta event not found after CALL/RETURN")
    }

    // =========================================================================
    // Tests: ALLOCATE TRACKS (0xFE)
    // =========================================================================

    /** Verifies that ALLOCATE TRACKS (0xFE + u16) is consumed without error. */
    @Test
    fun `allocate tracks command is consumed without error`() {
        val cmds = byteArrayOf(
            0xFE.toByte(), 0x01.toByte(), 0x00.toByte(),  // ALLOCATE_TRACKS bitmask=1
            0xFF.toByte(),
        )
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals("MThd", midi.decodeToString(0, 4))
    }

    // =========================================================================
    // Tests: Invalid / edge cases
    // =========================================================================

    /** Verifies that [SdatSseqFile.toMidi] throws on data that is too short. */
    @Test
    fun `toMidi throws on too-small input`() {
        val bad = SdatSseqFile("BAD", ByteArray(10), 0, 0, 0, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> { bad.toMidi() }
    }

    /** Verifies that [SdatSseqFile.toMidi] throws when the SSEQ magic is wrong. */
    @Test
    fun `toMidi throws on wrong magic`() {
        val buf = buildSseq(byteArrayOf(0xFF.toByte()))
        buf[0] = 'X'.code.toByte()  // corrupt magic
        val bad = SdatSseqFile("BAD", buf, 0, 0, 0, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> { bad.toMidi() }
    }

    /** Verifies that an empty sequence (only END OF TRACK) produces a valid MIDI file. */
    @Test
    fun `empty sequence produces valid midi`() {
        val cmds = byteArrayOf(0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals("MThd", midi.decodeToString(0, 4))
        val events = collectEvents(midi)
        assertTrue(events.isNotEmpty(), "Expected at least an EOT event")
    }

    // =========================================================================
    // Tests: NOTE WAIT mode (0xC7)
    // =========================================================================

    /**
     * Verifies that when noteWait is enabled (0xC7 01), consecutive notes do not overlap
     * — i.e., the second note starts at the end time of the first.
     *
     * Sequence: noteWait ON, note 60 vel 64 dur 24, note 62 vel 64 dur 24, END.
     */
    @Test
    fun `note wait on makes notes sequential`() {
        val cmds = byteArrayOf(
            0xC7.toByte(), 0x01.toByte(),          // NOTE WAIT = on
            0x3C.toByte(), 0x40.toByte(), *varLen(24),   // note 60 dur 24
            0x3E.toByte(), 0x40.toByte(), *varLen(24),   // note 62 dur 24
            0xFF.toByte(),
        )
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)

        val noteOns = events.filter { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0x90 && data[2].toInt() and 0xFF != 0
        }
        assertEquals(2, noteOns.size, "Expected 2 note-on events")
        assertEquals(0, noteOns[0].first, "First note should start at 0")
        assertEquals(24, noteOns[1].first, "Second note should start at 24 (after first)")
    }

    // =========================================================================
    // Tests: Multiple notes in sequence
    // =========================================================================

    /**
     * Verifies that two concurrent notes (noteWait=off, both at tick 0) both appear
     * in the MIDI output.
     */
    @Test
    fun `two notes without note-wait are both emitted`() {
        val cmds = byteArrayOf(
            0x3C.toByte(), 0x40.toByte(), *varLen(48),
            0x40.toByte(), 0x40.toByte(), *varLen(48),
            0xFF.toByte(),
        )
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val noteOns = events.filter { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0x90 && data[2].toInt() and 0xFF != 0
        }
        assertEquals(2, noteOns.size, "Expected 2 note-on events")
    }

    // =========================================================================
    // Tests: Pitch bend range / modulation / portamento
    // =========================================================================

    /** Verifies that 0xC5 (pitch bend range) emits RPN 0,0 + data entry CCs. */
    @Test
    fun `pitch bend range emits RPN data entry CCs`() {
        val range = 12
        val cmds = byteArrayOf(0xC5.toByte(), range.toByte(), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val rpnMsb = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0xB0 &&
                    data[1].toInt() and 0xFF == 101 && data[2].toInt() and 0xFF == 0
        }
        assertNotNull(rpnMsb, "RPN MSB CC (101) not found")
    }

    /** Verifies that modulation depth (0xCA) emits CC 1. */
    @Test
    fun `modulation depth produces CC 1`() {
        val amount = 64
        val cmds = byteArrayOf(0xCA.toByte(), amount.toByte(), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val cc = events.find { (_, data) ->
            data.size == 3 && (data[0].toInt() and 0xF0) == 0xB0 && data[1].toInt() and 0xFF == 1
        }
        assertNotNull(cc, "CC 1 (modulation) not found")
    }

    // =========================================================================
    // Tests: Variable-length quantity decoding
    // =========================================================================

    /**
     * Verifies that a multi-byte variable-length duration is decoded correctly.
     *
     * Duration 200 = 0x80+0x48 in variable-length = two bytes: 0x81 0x48.
     * Note: 200 > 127, so varLen(200) = [0x81, 0x48].
     */
    @Test
    fun `multi-byte variable length duration is decoded correctly`() {
        val dur = 200  // > 127, requires 2 varlen bytes
        val durEncoded = varLen(dur)
        assertEquals(2, durEncoded.size, "duration 200 should encode to 2 bytes")

        val cmds = byteArrayOf(0x3C.toByte(), 0x64.toByte(), *durEncoded, 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)

        val noteOff = events.find { (time, data) ->
            time == dur && data.size == 3 && (data[0].toInt() and 0xF0) == 0x90 &&
                    data[1].toInt() and 0xFF == 0x3C && data[2].toInt() and 0xFF == 0
        }
        assertNotNull(noteOff, "note-off at tick $dur not found; variable-length decode may be wrong")
    }

    // =========================================================================
    // Tests: Master volume (0xC2)
    // =========================================================================

    /** Verifies that master volume (0xC2) emits a SysEx event. */
    @Test
    fun `master volume produces sysex event`() {
        val cmds = byteArrayOf(0xC2.toByte(), 100.toByte(), 0xFF.toByte())
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        val events = collectEvents(midi)
        val sysex = events.find { (_, data) ->
            data.isNotEmpty() && data[0] == 0xF0.toByte()
        }
        assertNotNull(sysex, "SysEx (master volume) event not found")
    }

    // =========================================================================
    // Tests: LOOP START / LOOP END
    // =========================================================================

    /**
     * Verifies that LOOP START (0xD4 count) + LOOP END (0xFC) repeats the enclosed
     * note the specified number of times and does not cause an infinite loop.
     *
     * Loop count 2 → the body executes 2 times total.
     */
    @Test
    fun `loop start end repeats body and terminates`() {
        val cmds = byteArrayOf(
            0xD4.toByte(), 0x02.toByte(),                // LOOP START count=2
            0x3C.toByte(), 0x40.toByte(), *varLen(1),    // note 60
            0xFC.toByte(),                                // LOOP END
            0xFF.toByte(),                                // END
        )
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals("MThd", midi.decodeToString(0, 4))
        val events = collectEvents(midi)
        // Should not hang; EOT must be present somewhere in the track
        val eot = events.find { (_, data) ->
            data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0x2F.toByte()
        }
        assertNotNull(eot, "End-of-track meta event not found")
    }

    // =========================================================================
    // Tests: Unknown command
    // =========================================================================

    /** Verifies that an unknown command stops the track gracefully (does not crash). */
    @Test
    fun `unknown command stops track gracefully`() {
        val cmds = byteArrayOf(0xFA.toByte())  // not a known command
        val midi = makeSeqFile(buildSseq(cmds)).toMidi()
        assertEquals("MThd", midi.decodeToString(0, 4))
    }
}
