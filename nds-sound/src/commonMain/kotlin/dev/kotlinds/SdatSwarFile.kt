package dev.kotlinds

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

    /**
     * Decodes every SWAV sample in this SWAR wave archive and returns one WAV byte array per sample.
     *
     * Each returned WAV is a complete, playable mono 16-bit PCM file. Supports all three NDS
     * wave types (PCM8, PCM16, IMA-ADPCM); PCM8 is upsampled to 16-bit by multiplying by 256.
     *
     * The list order matches the SWAV entry order inside the SWAR DATA block.
     *
     * @return A list of WAV byte arrays, one per SWAV entry; empty if the archive has no entries.
     * @throws IllegalArgumentException if [data] is too short or does not begin with "SWAR".
     */
    fun toWavList(): List<ByteArray> = NdsAudio.swarToWavList(data)
}
