package me.nathanfallet.nds

/**
 * Auto-detects and dispatches Nintendo DS compression formats based on the magic byte.
 *
 * Supported formats and their magic bytes:
 * - `0x10` → [LzssCodec]  (LZ10 / LZSS)
 * - `0x11` → [Lz11Codec]  (LZ11 / LZX)
 * - `0x24` → [HuffmanCodec] (Huffman 4-bit)
 * - `0x28` → [HuffmanCodec] (Huffman 8-bit)
 * - `0x30` → [RleCodec]   (Run-Length Encoding)
 *
 * Note: [BlzCodec] (Bottom-LZ) has no magic byte and must be invoked directly.
 */
object NdsCompression {

    private val MAGIC_BYTES = setOf(0x10, 0x11, 0x24, 0x28, 0x30)

    /**
     * Returns `true` if the first byte of [input] is a recognised DS compression magic byte.
     *
     * @param input The byte array to inspect.
     * @return `true` if the array is non-empty and starts with a known magic byte; `false` otherwise.
     */
    fun isCompressed(input: ByteArray): Boolean =
        input.isNotEmpty() && (input[0].toInt() and 0xFF) in MAGIC_BYTES

    /**
     * Decompresses [input] using the codec matching its magic byte.
     *
     * @param input The compressed data; the first byte must be a recognised magic byte.
     * @return The decompressed data.
     * @throws IllegalArgumentException if [input] is empty or the magic byte is not recognised.
     */
    fun decompress(input: ByteArray): ByteArray {
        require(input.isNotEmpty()) { "NdsCompression: input is empty" }
        return when (val magic = input[0].toInt() and 0xFF) {
            0x10 -> LzssCodec.decompress(input)
            0x11 -> Lz11Codec.decompress(input)
            0x24, 0x28 -> HuffmanCodec.decompress(input)
            0x30 -> RleCodec.decompress(input)
            else -> throw IllegalArgumentException(
                "NdsCompression: unknown magic byte 0x${magic.toString(16).uppercase()}"
            )
        }
    }
}
