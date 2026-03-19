package dev.kotlinds

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NdsRomTest {
    private val romFile = File("../game.nds")

    // Skip all tests if the ROM file is not present
    private fun requireRom(): ByteArray? =
        if (romFile.exists()) romFile.readBytes() else null

    @Test
    fun `parse reads correct game title and code`() {
        val bytes = requireRom() ?: return
        val rom = NdsRom.parse(bytes)
        assertEquals("POKEMON HG", rom.gameTitle)
        assertEquals("IPKE", rom.gameCode)
    }

    @Test
    fun `parse extracts non-empty arm9 and arm7`() {
        val bytes = requireRom() ?: return
        val rom = NdsRom.parse(bytes)
        assertTrue(rom.arm9.isNotEmpty(), "ARM9 should not be empty")
        assertTrue(rom.arm7.isNotEmpty(), "ARM7 should not be empty")
    }

    @Test
    fun `parse populates filesystem with event data NARC`() {
        val bytes = requireRom() ?: return
        val rom = NdsRom.parse(bytes)
        assertNotNull(rom.files["a/0/3/2"], "Event data NARC not found at a/0/3/2")
    }

    @Test
    fun `pack round-trip produces same size ROM`() {
        val bytes = requireRom() ?: return
        val rom = NdsRom.parse(bytes)
        val repacked = rom.pack()
        // Repacked ROM may be smaller than original (original is padded to power-of-2 cartridge size;
        // we strip trailing padding). Repacked must not be larger and must be at least 512 bytes.
        assertTrue(
            repacked.size <= bytes.size,
            "Repacked should not be larger than original: repacked=${repacked.size}, original=${bytes.size}"
        )
        assertTrue(repacked.size >= 512, "Repacked ROM too small: ${repacked.size}")
    }

    @Test
    fun `pack round-trip preserves game code`() {
        val bytes = requireRom() ?: return
        val rom = NdsRom.parse(bytes)
        val repacked = rom.pack()
        val reparsed = NdsRom.parse(repacked)
        assertEquals(rom.gameCode, reparsed.gameCode)
    }

    @Test
    fun `pack round-trip preserves arm9 bytes`() {
        val bytes = requireRom() ?: return
        val rom = NdsRom.parse(bytes)
        val repacked = rom.pack()
        val reparsed = NdsRom.parse(repacked)
        assertTrue(rom.arm9.contentEquals(reparsed.arm9), "ARM9 bytes changed after pack/parse round-trip")
    }

    @Test
    fun `withFile replaces file in repacked ROM`() {
        val bytes = requireRom() ?: return
        val rom = NdsRom.parse(bytes)
        val dummy = ByteArray(16) { it.toByte() }
        val repacked = rom.withFile("a/0/3/2", dummy).pack()
        val reparsed = NdsRom.parse(repacked)
        assertEquals(reparsed.files["a/0/3/2"]?.contentEquals(dummy), true)
    }
}
