package me.nathanfallet.nds

/**
 * Represents a single Nintendo DS stream (STRM) file extracted from an SDAT archive.
 *
 * @property name The symbolic name from the SYMB block, or a generated fallback such as `"STRM_0"`.
 * @property data The raw STRM file bytes.
 * @property unk Unknown reserved field (u16) from the INFO entry at offset +2.
 * @property volume Playback volume (0–127).
 * @property priority Playback priority.
 * @property players Player assignment bitmask.
 */
data class SdatStrmFile(
    val name: String,
    val data: ByteArray,
    val unk: Int,
    val volume: Int,
    val priority: Int,
    val players: Int,
) {

    /**
     * Compares two [SdatStrmFile] instances for structural equality, including [data] content.
     *
     * @param other The object to compare against.
     * @return `true` if all properties are equal.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SdatStrmFile) return false
        return name == other.name &&
                data.contentEquals(other.data) &&
                unk == other.unk &&
                volume == other.volume &&
                priority == other.priority &&
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
        result = 31 * result + volume
        result = 31 * result + priority
        result = 31 * result + players
        return result
    }
}
