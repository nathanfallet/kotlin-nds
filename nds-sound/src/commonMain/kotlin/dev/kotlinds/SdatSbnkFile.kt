package dev.kotlinds

/**
 * Represents a single Nintendo DS sound bank (SBNK) file extracted from an SDAT archive.
 *
 * @property name The symbolic name from the SYMB block, or a generated fallback such as `"SBNK_0"`.
 * @property data The raw SBNK file bytes.
 * @property unk Unknown reserved field (u16) from the INFO entry at offset +2.
 * @property wars Indices of the up to four associated SWAR wave archives; -1 indicates an unused slot.
 */
data class SdatSbnkFile(
    val name: String,
    val data: ByteArray,
    val unk: Int,
    val wars: List<Int>,
) {

    /**
     * Compares two [SdatSbnkFile] instances for structural equality, including [data] content.
     *
     * @param other The object to compare against.
     * @return `true` if all properties are equal.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SdatSbnkFile) return false
        return name == other.name &&
                data.contentEquals(other.data) &&
                unk == other.unk &&
                wars == other.wars
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
        result = 31 * result + wars.hashCode()
        return result
    }

    /**
     * Converts this SBNK instrument bank to a SoundFont 2 (SF2) file.
     *
     * The caller must supply the SWAR wave archives in slot order: `wars[0]` corresponds to
     * `this.wars[0]`, etc.  Unused slots (originally `-1` in [SdatSbnkFile.wars]) must be
     * excluded by the caller before passing (i.e., only non-negative war indices are included,
     * mapped to their slot position).
     *
     * @param wars Wave archives in slot order matching [SdatSbnkFile.wars].
     * @return A byte array containing the complete SF2 file.
     */
    fun toSf2(wars: List<SdatSwarFile>): ByteArray = SbnkToSf2.convert(this, wars)
}
