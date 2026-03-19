package dev.kotlinds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NarcArchiveNamedTest {

    @Test
    fun `packNamed unpackNamed round-trip flat structure`() {
        val original = mapOf(
            "file0.bin" to byteArrayOf(1, 2, 3),
            "file1.bin" to byteArrayOf(4, 5, 6),
        )
        val packed = NarcArchive.packNamed(original)
        val unpacked = NarcArchive.unpackNamed(packed)

        assertEquals(original.keys, unpacked.keys)
        for (key in original.keys) {
            assertTrue(original[key]!!.contentEquals(unpacked[key]!!), "Content mismatch for $key")
        }
    }

    @Test
    fun `packNamed unpackNamed round-trip with subdirectory`() {
        val original = mapOf(
            "sprites/player.bin" to byteArrayOf(1, 2),
            "sprites/enemy.bin" to byteArrayOf(3, 4),
            "data/map.bin" to byteArrayOf(5, 6),
        )
        val packed = NarcArchive.packNamed(original)
        val unpacked = NarcArchive.unpackNamed(packed)

        assertEquals(original.keys, unpacked.keys)
        for (key in original.keys) {
            assertTrue(original[key]!!.contentEquals(unpacked[key]!!), "Content mismatch for $key")
        }
    }

    @Test
    fun `anonymous unpack still works`() {
        val original = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val packed = NarcArchive.pack(original)
        val unpacked = NarcArchive.unpack(packed)
        assertEquals(original.size, unpacked.size)
        assertTrue(original[0].contentEquals(unpacked[0]))
        assertTrue(original[1].contentEquals(unpacked[1]))
    }

    @Test
    fun `unpackNamed on anonymous NARC returns index keys`() {
        val packed = NarcArchive.pack(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
        val unpacked = NarcArchive.unpackNamed(packed)
        assertEquals(setOf("0", "1", "2"), unpacked.keys)
    }

    @Test
    fun `packNamed produces valid NARC`() {
        val packed = NarcArchive.packNamed(mapOf("a.bin" to byteArrayOf(0x42)))
        assertEquals("NARC", packed.decodeToString(0, 4))
        assertEquals(0xFE.toByte(), packed[4])
        assertEquals(0xFF.toByte(), packed[5])
        // 3 sections
        assertEquals(0x03.toByte(), packed[14])
    }
}
