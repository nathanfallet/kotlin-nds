package dev.kotlinds

/**
 * Represents a single Nintendo DS sequence (SSEQ) file extracted from an SDAT archive.
 *
 * @property name The symbolic name from the SYMB block, or a generated fallback such as `"SSEQ_0"`.
 * @property data The raw SSEQ file bytes.
 * @property unk Unknown reserved field (u16) from the INFO entry at offset +2.
 * @property bank The index of the associated SBNK bank.
 * @property volume Playback volume (0–127).
 * @property channelPriority Priority used when allocating hardware channels.
 * @property playerPriority Priority used by the sequence player.
 * @property players Bitmask of allowed players.
 */
data class SdatSseqFile(
    val name: String,
    val data: ByteArray,
    val unk: Int,
    val bank: Int,
    val volume: Int,
    val channelPriority: Int,
    val playerPriority: Int,
    val players: Int,
) {

    /**
     * Converts this SSEQ sequence file to a Standard MIDI File (SMF).
     *
     * The returned byte array is a complete, self-contained MIDI file that can be written
     * directly to disk or passed to a MIDI player.  The file uses 48 ticks per quarter note
     * and is SMF Type 0 (single track) when only the primary track is active, or SMF Type 1
     * (multiple tracks) when additional tracks were opened by the sequence via the `OPEN TRACK`
     * (0x93) command.
     *
     * Conversion behaviour mirrors the `sseq2mid` reference implementation by loveemu:
     * - Notes are emitted as note-on / note-off (velocity 0) pairs.
     * - Tempo changes map to SMF meta event 0x51.
     * - Pan, volume, expression and other continuous controllers map to the corresponding
     *   MIDI CC numbers.
     * - Program changes use bank-select CCs (0 / 32) plus a program-change message.
     * - Tempo changes map to SMF meta event 0x51.
     * - `JUMP` backward loops are repeated [loopCount] times (default 1 = play once, no repeat).
     *
     * @param loopCount Number of times to traverse each `JUMP` backward loop (minimum 1).
     *   Use `1` (default) to play the sequence once straight through; use `2` to hear one loop
     *   repetition; and so on.
     * @return A byte array containing the full SMF file.
     * @throws IllegalArgumentException if [data] does not contain a valid SSEQ file.
     */
    fun toMidi(loopCount: Int = 1): ByteArray = SseqToMidi.convert(data, loopCount)

    /**
     * Convenience wrapper that resolves this sequence's instrument bank and associated wave
     * archives from [archive], then converts the bank to a SoundFont 2 (SF2) file.
     *
     * Pair the returned SF2 with [toMidi] for fully authentic NDS music playback:
     * ```
     * val sf2 = archive.sequences[0].toSf2(archive)
     * val mid = archive.sequences[0].toMidi()
     * File("bgm.sf2").writeBytes(sf2)
     * File("bgm.mid").writeBytes(mid)
     * ```
     *
     * @param archive The SDAT archive that contains this sequence.
     * @return A byte array containing the complete SF2 file.
     */
    fun toSf2(archive: SdatArchive): ByteArray {
        val bank = archive.banksBySlot[this.bank]
            ?: archive.banks.getOrNull(this.bank)
            ?: return ByteArray(0)
        val wars = bank.wars.map { slot ->
            if (slot >= 0) archive.waveArchivesBySlot[slot] ?: archive.waveArchives.getOrNull(slot)
            else null
        }
        return bank.toSf2(wars)
    }

    /**
     * Compares two [SdatSseqFile] instances for structural equality, including [data] content.
     *
     * @param other The object to compare against.
     * @return `true` if all properties are equal.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SdatSseqFile) return false
        return name == other.name &&
                data.contentEquals(other.data) &&
                unk == other.unk &&
                bank == other.bank &&
                volume == other.volume &&
                channelPriority == other.channelPriority &&
                playerPriority == other.playerPriority &&
                players == other.players
    }

    /**
     * Returns a hash code consistent with [equals].
     *
     * @return The computed hash code.
     */
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + unk
        result = 31 * result + bank
        result = 31 * result + volume
        result = 31 * result + channelPriority
        result = 31 * result + playerPriority
        result = 31 * result + players
        return result
    }
}
