package dev.kotlinds

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

    /**
     * Decodes this STRM file and returns the audio as a standard 16-bit PCM WAV byte array.
     *
     * Supports all three NDS wave types:
     * - PCM8 (type 0): 8-bit signed PCM, upsampled to 16-bit by multiplying by 256.
     * - PCM16 (type 1): 16-bit signed little-endian PCM, copied as-is.
     * - IMA-ADPCM (type 2): 4-bit IMA-ADPCM decoded to 16-bit signed PCM.
     *
     * The returned WAV always has a 44-byte RIFF/WAV header followed by interleaved
     * signed 16-bit little-endian PCM samples. Channel count and sample rate are taken
     * directly from the STRM header fields.
     *
     * @return A byte array containing a complete, playable WAV file.
     * @throws IllegalArgumentException if [data] is too short or has an invalid STRM magic.
     */
    fun toWav(): ByteArray = NdsAudio.strmToWav(data)
}
