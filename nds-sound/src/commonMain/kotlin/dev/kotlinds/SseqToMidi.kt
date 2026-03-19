package dev.kotlinds

/**
 * Converts NDS SSEQ sequence files to Standard MIDI Files (SMF).
 *
 * This is a faithful Kotlin port of the `sseq2mid` converter by loveemu.
 * The SSEQ format is a compact, event-driven sequencer format used by the
 * Nintendo DS sound hardware (Nitro SDK). This object handles parsing all
 * SSEQ commands and emitting the corresponding SMF events.
 *
 * Tick resolution: 48 ticks per quarter note (matching the reference implementation).
 *
 * Output format:
 * - SMF Type 0 if only track 0 is active.
 * - SMF Type 1 if multiple tracks are used.
 */
internal object SseqToMidi {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private const val TICKS_PER_QUARTER = 48
    private const val MAX_TRACKS = 16
    private const val INVALID_OFFSET = -1

    // MIDI channel events
    private const val NOTE_OFF = 0x80
    private const val NOTE_ON = 0x90
    private const val CONTROL_CHANGE = 0xB0
    private const val PROGRAM_CHANGE = 0xC0
    private const val PITCH_BEND = 0xE0
    private const val META_EVENT = 0xFF

    // CC numbers
    private const val CC_BANK_MSB = 0
    private const val CC_MODULATION = 1
    private const val CC_PORTAMENTO_TIME = 5
    private const val CC_DATA_ENTRY_MSB = 6
    private const val CC_VOLUME = 7
    private const val CC_PAN = 10
    private const val CC_EXPRESSION = 11
    private const val CC_BANK_LSB = 32
    private const val CC_PORTAMENTO = 65
    private const val CC_PORTAMENTO_CTRL = 84
    private const val CC_VIBRATO_RATE = 76
    private const val CC_VIBRATO_DEPTH = 77
    private const val CC_VIBRATO_DELAY = 78
    private const val CC_REVERB = 91
    private const val CC_RPN_LSB = 100
    private const val CC_RPN_MSB = 101
    private const val CC_MONO = 126
    private const val CC_POLY = 127

    // Meta event types
    private const val META_END_OF_TRACK = 0x2F
    private const val META_SET_TEMPO = 0x51
    private const val META_TEXT = 0x01

    // -------------------------------------------------------------------------
    // Data structures
    // -------------------------------------------------------------------------

    /** State for one SSEQ track during conversion. */
    private data class TrackState(
        var loopCount: Int = 0,
        var absTime: Int = 0,
        var noteWait: Boolean = false,
        var curOffset: Int = 0,
        var offsetToTop: Int = 0,
        var offsetToReturn: Int = INVALID_OFFSET,
        // Map from SSEQ byte-offset to absTime at that offset (for JUMP loop points)
        val offsetToAbsTime: MutableMap<Int, Int> = mutableMapOf(),
    )

    /** One MIDI event to be written into a track. */
    private data class MidiEvent(
        val time: Int,
        val isNoteOff: Boolean,  // note-offs sort before other events at same time
        val data: ByteArray,
    )

    /** Mutable list of events for one SMF track. */
    private class SmfTrack {
        val events: MutableList<MidiEvent> = mutableListOf()
        var endTime: Int = 0

        fun insert(time: Int, data: ByteArray, isNoteOff: Boolean = false) {
            events.add(MidiEvent(time, isNoteOff, data))
            if (time > endTime) endTime = time
        }

        /**
         * Returns events sorted by time, note-offs before other events at same tick.
         */
        fun sorted(): List<MidiEvent> = events.sortedWith(
            compareBy<MidiEvent> { it.time }.thenBy { if (it.isNoteOff) 0 else 1 }
        )
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Converts the raw bytes of an SSEQ file to a Standard MIDI File byte array.
     *
     * The produced SMF uses 48 ticks per quarter note and is either Type 0
     * (single track) or Type 1 (multiple tracks) depending on how many SSEQ
     * tracks are activated by the sequence.
     *
     * @param sseq The raw SSEQ file bytes (must start with the SSEQ magic).
     * @param loopCount Number of times to traverse each `JUMP` backward loop (minimum 1).
     * @return A fully formed SMF byte array.
     * @throws IllegalArgumentException if [sseq] is too small or has an invalid magic.
     */
    fun convert(sseq: ByteArray, loopCount: Int = 1): ByteArray {
        require(sseq.size >= 0x1C) { "SSEQ data too small (${sseq.size} < 0x1C)" }
        require(
            sseq[0] == 'S'.code.toByte() && sseq[1] == 'S'.code.toByte() &&
                    sseq[2] == 'E'.code.toByte() && sseq[3] == 'Q'.code.toByte()
        ) { "Not an SSEQ file (bad magic)" }
        require(
            sseq[0x10] == 'D'.code.toByte() && sseq[0x11] == 'A'.code.toByte() &&
                    sseq[0x12] == 'T'.code.toByte() && sseq[0x13] == 'A'.code.toByte()
        ) { "SSEQ: DATA block not found" }

        // The data offset field at 0x18 is the absolute offset within the file
        // where the sequence commands begin.
        val sseqOffsetBase = readU32(sseq, 0x18).toInt()

        // Initialise per-track state
        val tracks = Array(MAX_TRACKS) { TrackState() }
        tracks[0].loopCount = maxOf(1, loopCount)
        tracks[0].absTime = 0
        tracks[0].noteWait = false
        tracks[0].offsetToTop = 0x1C
        tracks[0].curOffset = 0x1C
        tracks[0].offsetToReturn = INVALID_OFFSET

        // One SMF track per SSEQ track
        val smfTracks = Array(MAX_TRACKS) { SmfTrack() }

        // Process each SSEQ track (same order as C reference)
        for (trackIndex in 0 until MAX_TRACKS) {
            val ts = tracks[trackIndex]
            if (ts.loopCount <= 0) continue

            var loopStartCount = 0
            var loopStartOffset = 0
            var loopPointUsed = false

            while (ts.loopCount > 0) {
                val curOffset = ts.curOffset
                val absTime = ts.absTime
                val midiCh = trackIndex  // direct mapping (no modifyChOrder)

                if (curOffset >= sseq.size) {
                    ts.loopCount = 0
                    break
                }

                ts.offsetToAbsTime[curOffset] = absTime

                val statusByte = sseq[curOffset].toInt() and 0xFF
                ts.curOffset++

                var offsetToJump = INVALID_OFFSET

                if (statusByte < 0x80) {
                    // ---- Note ON with duration --------------------------------
                    val note = statusByte
                    val velocity = readU8(sseq, ts.curOffset)
                    ts.curOffset++
                    val duration = readVarLen(sseq, ts.curOffset)
                    ts.curOffset += varLenSize(duration)

                    // Insert note-on
                    smfTracks[trackIndex].insert(
                        ts.absTime,
                        byteArrayOf(
                            (NOTE_ON or (midiCh and 0x0F)).toByte(),
                            note.toByte(),
                            velocity.toByte(),
                        )
                    )
                    // Insert note-off (velocity 0 = note-on with vel 0)
                    smfTracks[trackIndex].insert(
                        ts.absTime + duration,
                        byteArrayOf(
                            (NOTE_ON or (midiCh and 0x0F)).toByte(),
                            note.toByte(),
                            0.toByte(),
                        ),
                        isNoteOff = true,
                    )

                    if (ts.noteWait) {
                        ts.absTime += duration
                    }

                } else {
                    when (statusByte) {

                        0x80 -> {
                            // ---- REST ----------------------------------------
                            val tick = readVarLen(sseq, ts.curOffset)
                            ts.curOffset += varLenSize(tick)
                            ts.absTime += tick
                        }

                        0x81 -> {
                            // ---- PROGRAM CHANGE ----------------------------------
                            val realProgram = readVarLen(sseq, ts.curOffset)
                            ts.curOffset += varLenSize(realProgram)
                            val program = realProgram % 128
                            val bankLsb = (realProgram / 128) % 128
                            val bankMsb = (realProgram / 128 / 128) % 128
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_BANK_MSB, bankMsb)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_BANK_LSB, bankLsb)
                            insertProgram(smfTracks[trackIndex], ts.absTime, midiCh, program)
                        }

                        0x93 -> {
                            // ---- OPEN TRACK -------------------------------------
                            val newTrackIndex = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            val rawOffset = readU24LE(sseq, ts.curOffset)
                            ts.curOffset += 3
                            val absOffset = rawOffset + sseqOffsetBase

                            tracks[newTrackIndex].loopCount = ts.loopCount
                            tracks[newTrackIndex].absTime = ts.absTime
                            tracks[newTrackIndex].offsetToTop = absOffset
                            tracks[newTrackIndex].offsetToReturn = INVALID_OFFSET
                            tracks[newTrackIndex].curOffset = absOffset
                        }

                        0x94 -> {
                            // ---- JUMP -------------------------------------------
                            val rawOffset = readU24LE(sseq, ts.curOffset)
                            ts.curOffset += 3
                            val newOffset = rawOffset + sseqOffsetBase

                            offsetToJump = newOffset

                            if (newOffset >= ts.offsetToTop && newOffset < ts.curOffset) {
                                // Backward jump = loop; consume one loop pass
                                if (!loopPointUsed) {
                                    loopPointUsed = true
                                }
                                ts.loopCount--
                            }
                            // Forward jumps or redirects: just follow the offset
                        }

                        0x95 -> {
                            // ---- CALL -------------------------------------------
                            val rawOffset = readU24LE(sseq, ts.curOffset)
                            ts.curOffset += 3
                            ts.offsetToReturn = ts.curOffset
                            offsetToJump = rawOffset + sseqOffsetBase
                        }

                        0xA0 -> {
                            // ---- RANDOM -----------------------------------------
                            ts.curOffset++ // subCmd
                            ts.curOffset += 2 // randMin (u16)
                            ts.curOffset += 2 // randMax (u16)
                        }

                        0xA1 -> {
                            // ---- FROM VAR ----------------------------------------
                            val subCmd = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            if (subCmd in 0xB0..0xBD) {
                                ts.curOffset++ // extra byte
                            }
                            ts.curOffset++ // varNumber
                        }

                        0xA2 -> {
                            // ---- IF (no-op in reference) --------------------------
                        }

                        in 0xB0..0xBD -> {
                            // ---- VARIABLE ops (u8 varNum + u16 value) -------------
                            ts.curOffset++ // varNumber
                            ts.curOffset += 2 // value (s16 LE)
                        }

                        0xC0 -> {
                            // ---- PAN -----------------------------------------------
                            val pan = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_PAN, pan)
                        }

                        0xC1 -> {
                            // ---- VOLUME --------------------------------------------
                            val vol = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_VOLUME, vol)
                        }

                        0xC2 -> {
                            // ---- MASTER VOLUME (SysEx F0 7F 7F 04 01 00 vv F7) ---
                            val vol = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertMasterVolume(smfTracks[trackIndex], ts.absTime, vol)
                        }

                        0xC3 -> {
                            // ---- TRANSPOSE (RPN 0,2 → DataEntry MSB = 64+transpose)
                            val transpose = readS8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_RPN_MSB, 0)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_RPN_LSB, 2)
                            val dataVal = (64 + transpose).coerceIn(0, 127)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_DATA_ENTRY_MSB, dataVal)
                        }

                        0xC4 -> {
                            // ---- PITCH BEND -----------------------------------------
                            val bend = readS8(sseq, ts.curOffset).toLong() * 64L
                            ts.curOffset++
                            insertPitchBend(smfTracks[trackIndex], ts.absTime, midiCh, bend.toInt())
                        }

                        0xC5 -> {
                            // ---- PITCH BEND RANGE (RPN 0,0 → DataEntry MSB = range)
                            val range = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_RPN_MSB, 0)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_RPN_LSB, 0)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_DATA_ENTRY_MSB, range)
                        }

                        0xC6 -> {
                            // ---- PRIORITY (ignored in MIDI) -----------------------
                            ts.curOffset++
                        }

                        0xC7 -> {
                            // ---- NOTE WAIT (mono/poly) ----------------------------
                            val flg = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(
                                smfTracks[trackIndex], ts.absTime, midiCh,
                                if (flg != 0) CC_MONO else CC_POLY, 0
                            )
                            ts.noteWait = flg != 0
                        }

                        0xC8 -> {
                            // ---- TIE (no MIDI equivalent) -------------------------
                            ts.curOffset++
                        }

                        0xC9 -> {
                            // ---- PORTAMENTO KEY ------------------------------------
                            val key = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_PORTAMENTO_CTRL, key)
                        }

                        0xCA -> {
                            // ---- MODULATION DEPTH ----------------------------------
                            val amount = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_MODULATION, amount)
                        }

                        0xCB -> {
                            // ---- MODULATION SPEED ----------------------------------
                            val amount = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            val mappedVal = (64 + amount / 2).coerceIn(0, 127)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_VIBRATO_RATE, mappedVal)
                        }

                        0xCC -> {
                            // ---- MODULATION TYPE (no direct MIDI equivalent) ------
                            ts.curOffset++
                        }

                        0xCD -> {
                            // ---- MODULATION RANGE ----------------------------------
                            val amount = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            val mappedVal = (64 + amount / 2).coerceIn(0, 127)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_VIBRATO_DEPTH, mappedVal)
                        }

                        0xCE -> {
                            // ---- PORTAMENTO ON/OFF ---------------------------------
                            val flg = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(
                                smfTracks[trackIndex], ts.absTime, midiCh,
                                CC_PORTAMENTO, if (flg == 0) 0 else 127
                            )
                        }

                        0xCF -> {
                            // ---- PORTAMENTO TIME -----------------------------------
                            val time = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_PORTAMENTO_TIME, time)
                        }

                        0xD0 -> {
                            // ---- ATTACK RATE (not mapped in reference) ------------
                            ts.curOffset++
                        }

                        0xD1 -> {
                            // ---- DECAY RATE (not mapped in reference) -------------
                            ts.curOffset++
                        }

                        0xD2 -> {
                            // ---- SUSTAIN RATE (no mapping) -----------------------
                            ts.curOffset++
                        }

                        0xD3 -> {
                            // ---- RELEASE RATE (not mapped in reference) ----------
                            ts.curOffset++
                        }

                        0xD4 -> {
                            // ---- LOOP START ----------------------------------------
                            loopStartCount = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            loopStartOffset = ts.curOffset
                            if (loopStartCount == 0) {
                                loopStartCount = -1  // infinite loop sentinel
                            }
                        }

                        0xD5 -> {
                            // ---- EXPRESSION ----------------------------------------
                            val expression = readU8(sseq, ts.curOffset)
                            ts.curOffset++
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_EXPRESSION, expression)
                        }

                        0xD6 -> {
                            // ---- PRINT VAR (debug, ignored) -----------------------
                            ts.curOffset++
                        }

                        0xE0 -> {
                            // ---- MODULATION DELAY (u16 LE) -------------------------
                            val amount = readU16LE(sseq, ts.curOffset)
                            ts.curOffset += 2
                            val mappedVal = (64 + amount / 2).coerceIn(0, 127)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_VIBRATO_DELAY, mappedVal)
                        }

                        0xE1 -> {
                            // ---- TEMPO (u16 LE, in BPM) ----------------------------
                            val bpm = readU16LE(sseq, ts.curOffset)
                            ts.curOffset += 2
                            insertTempo(smfTracks[trackIndex], ts.absTime, bpm)
                        }

                        0xE3 -> {
                            // ---- SWEEP PITCH (s16 LE) ------------------------------
                            val amount = readS16LE(sseq, ts.curOffset)
                            ts.curOffset += 2
                            // Map to vibrato-delay CC (same as C reference)
                            val mappedVal = amount.coerceIn(0, 127)
                            insertControl(smfTracks[trackIndex], ts.absTime, midiCh, CC_VIBRATO_DELAY, mappedVal)
                        }

                        0xFC -> {
                            // ---- LOOP END ------------------------------------------
                            when {
                                loopStartCount > 0 -> {
                                    loopStartCount--
                                    ts.curOffset = loopStartOffset
                                }

                                loopStartCount == -1 -> {
                                    // Infinite loop in original; treat as end of repeated section
                                    ts.loopCount = 0
                                }
                            }
                        }

                        0xFD -> {
                            // ---- RETURN --------------------------------------------
                            val returnOffset = ts.offsetToReturn
                            ts.offsetToReturn = INVALID_OFFSET
                            if (returnOffset == INVALID_OFFSET) {
                                ts.loopCount = 0
                            } else {
                                offsetToJump = returnOffset
                            }
                        }

                        0xFE -> {
                            // ---- ALLOCATE TRACKS (u16 bitmask, no MIDI output) ---
                            ts.curOffset += 2
                        }

                        0xFF -> {
                            // ---- END OF TRACK ------------------------------------
                            ts.loopCount = 0
                        }

                        else -> {
                            // Unknown command — stop this track
                            ts.loopCount = 0
                        }
                    }
                }

                // Apply jump if set
                if (offsetToJump != INVALID_OFFSET) {
                    ts.curOffset = offsetToJump
                }

                ts.absTime = ts.absTime  // explicit write-back already done via ts.absTime mutations
            }

            // End-of-track timing
            smfTracks[trackIndex].endTime = tracks[trackIndex].absTime
        }

        // ---- Determine which tracks contain data ----------------------------
        // A track is "active" if it has any events (beyond the implicit EOT) OR
        // if its loopCount was ever > 0 (i.e. it was opened).
        // Track 0 is always written. Other tracks are written if they were opened.
        val activeTrackIndices = mutableListOf<Int>()
        for (i in 0 until MAX_TRACKS) {
            if (i == 0 || tracks[i].offsetToTop != 0) {
                activeTrackIndices.add(i)
            }
        }

        // Ensure at least track 0
        if (activeTrackIndices.isEmpty()) activeTrackIndices.add(0)

        val numSmfTracks = activeTrackIndices.size
        val smfFormat = if (numSmfTracks > 1) 1 else 0

        return buildSmf(smfTracks, activeTrackIndices, smfFormat, TICKS_PER_QUARTER)
    }

    // -------------------------------------------------------------------------
    // SMF event insertion helpers
    // -------------------------------------------------------------------------

    private fun insertControl(track: SmfTrack, time: Int, ch: Int, cc: Int, value: Int) {
        val v = value.coerceIn(0, 127)
        track.insert(
            time,
            byteArrayOf(
                (CONTROL_CHANGE or (ch and 0x0F)).toByte(),
                cc.toByte(),
                v.toByte(),
            )
        )
    }

    private fun insertProgram(track: SmfTrack, time: Int, ch: Int, program: Int) {
        track.insert(
            time,
            byteArrayOf(
                (PROGRAM_CHANGE or (ch and 0x0F)).toByte(),
                (program and 0x7F).toByte(),
            )
        )
    }

    private fun insertPitchBend(track: SmfTrack, time: Int, ch: Int, value: Int) {
        val clamped = value.coerceIn(-8192, 8191)
        val v = clamped + 8192
        track.insert(
            time,
            byteArrayOf(
                (PITCH_BEND or (ch and 0x0F)).toByte(),
                (v and 0x7F).toByte(),
                ((v ushr 7) and 0x7F).toByte(),
            )
        )
    }

    private fun insertTempo(track: SmfTrack, time: Int, bpm: Int) {
        // Convert BPM to microseconds per quarter note
        val microSeconds = if (bpm > 0) 60_000_000 / bpm else 500_000
        val data = ByteArray(6)
        data[0] = META_EVENT.toByte()
        data[1] = META_SET_TEMPO.toByte()
        data[2] = 0x03  // length = 3
        data[3] = ((microSeconds ushr 16) and 0xFF).toByte()
        data[4] = ((microSeconds ushr 8) and 0xFF).toByte()
        data[5] = (microSeconds and 0xFF).toByte()
        track.insert(time, data)
    }

    private fun insertMasterVolume(track: SmfTrack, time: Int, volume: Int) {
        // SysEx: F0 7F 7F 04 01 00 vv F7
        val v = volume.coerceIn(0, 127)
        val sysex = byteArrayOf(
            0xF0.toByte(), 0x7F.toByte(), 0x7F.toByte(), 0x04.toByte(),
            0x01.toByte(), 0x00.toByte(), v.toByte(), 0xF7.toByte()
        )
        // SysEx: status=F0, length (varlen), payload
        val len = sysex.size - 1  // exclude the leading 0xF0
        val lenBytes = writeVarLen(len)
        val out = ByteArray(1 + lenBytes.size + len)
        out[0] = 0xF0.toByte()
        lenBytes.copyInto(out, 1)
        sysex.copyInto(out, 1 + lenBytes.size, 1)  // skip leading 0xF0
        track.insert(time, out)
    }

    private fun insertEndOfTrack(track: SmfTrack, time: Int) {
        track.insert(time, byteArrayOf(META_EVENT.toByte(), META_END_OF_TRACK.toByte(), 0x00))
    }

    // -------------------------------------------------------------------------
    // SMF builder
    // -------------------------------------------------------------------------

    /**
     * Assembles a complete Standard MIDI File byte array from the per-track event lists.
     *
     * @param smfTracks The per-track event storage (indexed 0..MAX_TRACKS-1).
     * @param activeTrackIndices The indices of SSEQ tracks to include in the output.
     * @param smfFormat The SMF format (0 = single track, 1 = multi-track).
     * @param ticksPerQN Ticks per quarter note for the MThd chunk.
     * @return A complete SMF byte array.
     */
    private fun buildSmf(
        smfTracks: Array<SmfTrack>,
        activeTrackIndices: List<Int>,
        smfFormat: Int,
        ticksPerQN: Int,
    ): ByteArray {
        // Add end-of-track meta event to each active track
        for (idx in activeTrackIndices) {
            insertEndOfTrack(smfTracks[idx], smfTracks[idx].endTime)
        }

        // Compute sizes
        val trackChunks = activeTrackIndices.map { idx ->
            buildTrackChunk(smfTracks[idx])
        }

        val headerSize = 14  // MThd = 4 magic + 4 length + 2 format + 2 numTracks + 2 division
        val totalSize = headerSize + trackChunks.sumOf { it.size }
        val out = ByteArray(totalSize)
        var pos = 0

        // MThd
        out[pos++] = 'M'.code.toByte()
        out[pos++] = 'T'.code.toByte()
        out[pos++] = 'h'.code.toByte()
        out[pos++] = 'd'.code.toByte()
        // chunk length = 6
        out[pos++] = 0; out[pos++] = 0; out[pos++] = 0; out[pos++] = 6
        // format
        out[pos++] = ((smfFormat ushr 8) and 0xFF).toByte()
        out[pos++] = (smfFormat and 0xFF).toByte()
        // numTracks
        val nt = activeTrackIndices.size
        out[pos++] = ((nt ushr 8) and 0xFF).toByte()
        out[pos++] = (nt and 0xFF).toByte()
        // division (ticks per quarter note)
        out[pos++] = ((ticksPerQN ushr 8) and 0xFF).toByte()
        out[pos++] = (ticksPerQN and 0xFF).toByte()

        // MTrk chunks
        for (chunk in trackChunks) {
            chunk.copyInto(out, pos)
            pos += chunk.size
        }

        return out
    }

    /**
     * Serialises one SMF track into an MTrk chunk (magic + length + delta-time encoded events).
     *
     * @param track The track whose events are to be serialised.
     * @return The MTrk chunk bytes.
     */
    private fun buildTrackChunk(track: SmfTrack): ByteArray {
        val sorted = track.sorted()

        // First pass: compute total data size
        var dataSize = 0
        var prevTime = 0
        for (event in sorted) {
            val delta = (event.time - prevTime).coerceAtLeast(0)
            dataSize += writeVarLen(delta).size + event.data.size
            prevTime = event.time
        }

        // MTrk header: 4 magic + 4 length
        val chunk = ByteArray(8 + dataSize)
        chunk[0] = 'M'.code.toByte()
        chunk[1] = 'T'.code.toByte()
        chunk[2] = 'r'.code.toByte()
        chunk[3] = 'k'.code.toByte()
        chunk[4] = ((dataSize ushr 24) and 0xFF).toByte()
        chunk[5] = ((dataSize ushr 16) and 0xFF).toByte()
        chunk[6] = ((dataSize ushr 8) and 0xFF).toByte()
        chunk[7] = (dataSize and 0xFF).toByte()

        // Second pass: write events
        var pos = 8
        prevTime = 0
        for (event in sorted) {
            val delta = (event.time - prevTime).coerceAtLeast(0)
            val deltaBytes = writeVarLen(delta)
            deltaBytes.copyInto(chunk, pos)
            pos += deltaBytes.size
            event.data.copyInto(chunk, pos)
            pos += event.data.size
            prevTime = event.time
        }

        return chunk
    }

    // -------------------------------------------------------------------------
    // Variable-length quantity helpers (SMF / SSEQ format)
    // -------------------------------------------------------------------------

    /**
     * Reads a variable-length value from [buf] at [offset].
     *
     * The format is identical to the standard MIDI variable-length encoding:
     * bytes are read while the high bit is set; each contributes its low 7 bits.
     * The reference (`smfReadVarLength`) reads the first byte's low 7 bits, then
     * shifts and ORs in subsequent bytes' low 7 bits while the previous byte had
     * the high bit set.
     *
     * @param buf The source byte array.
     * @param offset The starting byte offset.
     * @return The decoded unsigned integer value.
     */
    private fun readVarLen(buf: ByteArray, offset: Int): Int {
        var value = buf[offset].toInt() and 0x7F
        var i = offset
        while (i < buf.size - 1 && buf[i].toInt() and 0x80 != 0) {
            i++
            value = (value shl 7) or (buf[i].toInt() and 0x7F)
        }
        return value
    }

    /**
     * Returns the number of bytes consumed by a variable-length value [v].
     *
     * @param v The decoded value.
     * @return Number of bytes (1–4).
     */
    private fun varLenSize(v: Int): Int {
        var size = 1
        var left = v
        while (left > 0x7F && size < 4) {
            size++
            left = left ushr 7
        }
        return size
    }

    /**
     * Encodes [value] as a MIDI variable-length quantity.
     *
     * @param value The value to encode (0..268435455).
     * @return The encoded byte array (1–4 bytes).
     */
    private fun writeVarLen(value: Int): ByteArray {
        val size = varLenSize(value)
        val buf = ByteArray(size)
        var shiftCount = (size - 1) * 7
        for (i in 0 until size - 1) {
            buf[i] = (((value ushr shiftCount) and 0x7F) or 0x80).toByte()
            shiftCount -= 7
        }
        buf[size - 1] = ((value ushr shiftCount) and 0x7F).toByte()
        return buf
    }

    // -------------------------------------------------------------------------
    // Low-level byte readers
    // -------------------------------------------------------------------------

    private fun readU8(buf: ByteArray, off: Int): Int = buf[off].toInt() and 0xFF

    private fun readS8(buf: ByteArray, off: Int): Int {
        val v = buf[off].toInt() and 0xFF
        return if (v >= 0x80) v - 0x100 else v
    }

    private fun readU16LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun readS16LE(buf: ByteArray, off: Int): Int {
        val v = readU16LE(buf, off)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun readU24LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16)

    private fun readU32(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)
}
