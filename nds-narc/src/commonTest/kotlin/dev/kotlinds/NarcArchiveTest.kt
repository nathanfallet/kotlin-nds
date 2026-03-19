package dev.kotlinds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NarcArchiveTest {
    @Test
    fun `pack unpack round-trip preserves file contents`() {
        val original = listOf(
            byteArrayOf(1, 2, 3, 4),
            byteArrayOf(10, 20, 30),
            ByteArray(0),
            ByteArray(100) { it.toByte() },
        )
        val packed = NarcArchive.pack(original)
        val unpacked = NarcArchive.unpack(packed)

        assertEquals(original.size, unpacked.size)
        for (i in original.indices) {
            assertTrue(original[i].contentEquals(unpacked[i]), "File $i differs after round-trip")
        }
    }
}
