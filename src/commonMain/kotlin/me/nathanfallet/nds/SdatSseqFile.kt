package me.nathanfallet.nds

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
