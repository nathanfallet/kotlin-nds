package me.nathanfallet.nds

/**
 * Represents a single Nintendo DS wave archive (SWAR) file extracted from an SDAT archive.
 *
 * @property name The symbolic name from the SYMB block, or a generated fallback such as `"SWAR_0"`.
 * @property data The raw SWAR file bytes.
 * @property unk Unknown reserved field (u16) from the INFO entry at offset +2.
 */
data class SdatSwarFile(
    val name: String,
    val data: ByteArray,
    val unk: Int,
) {

    /**
     * Compares two [SdatSwarFile] instances for structural equality, including [data] content.
     *
     * @param other The object to compare against.
     * @return `true` if all properties are equal.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SdatSwarFile) return false
        return name == other.name && data.contentEquals(other.data) && unk == other.unk
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
        return result
    }
}
