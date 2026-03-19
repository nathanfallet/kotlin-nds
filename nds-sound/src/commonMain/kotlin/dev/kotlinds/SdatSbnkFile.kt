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
     * bank slot 0, `wars[1]` to slot 1, etc.  Unused slots (originally `-1` in [SdatSbnkFile.wars])
     * should be `null`.  The list must have exactly 4 elements (one per bank slot), matching the
     * 0-3 wave-archive indices embedded in the SBNK binary's region data.
     *
     * @param wars Wave archives in bank-slot order (4 elements, `null` for unused slots).
     * @return A byte array containing the complete SF2 file.
     */
    fun toSf2(wars: List<SdatSwarFile?>): ByteArray = SbnkToSf2.convert(this, wars)
}
