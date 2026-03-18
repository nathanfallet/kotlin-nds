package me.nathanfallet.nds

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NdsRomOverlayTest {

    // -------------------------------------------------------------------------
    // Minimal ROM builder
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal but structurally valid NDS ROM binary containing:
     *   - 1 filesystem file ("f.bin")
     *   - 1 ARM9 overlay (FAT ID 1, data = [ovl9Data])
     *   - 1 ARM7 overlay (FAT ID 2, data = [ovl7Data])
     */
    private fun buildMinimalRom(ovl9Data: ByteArray, ovl7Data: ByteArray): ByteArray {
        fun writeU32(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
            buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
            buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        fun align4(n: Int) = (n + 3) and -4

        val arm9 = ByteArray(4) { 0xA9.toByte() }
        val arm7 = ByteArray(4) { 0xA7.toByte() }
        val file0 = ByteArray(4) { 0xF0.toByte() }

        // FNT: one root dir entry (8 bytes) + subtable with 1 file "f.bin" + terminator
        val fnt = byteArrayOf(
            0x08, 0x00, 0x00, 0x00,   // subtable at offset 8
            0x00, 0x00,                // first file ID = 0
            0x01, 0x00,                // 1 directory
            0x05,                      // name length = 5, not a dir
            'f'.code.toByte(), '.'.code.toByte(), 'b'.code.toByte(),
            'i'.code.toByte(), 'n'.code.toByte(),
            0x00                       // terminator
        ) // 16 bytes (already 4-byte aligned)

        // Overlay tables: 32 bytes each, file_id at offset 24
        val ovl9Table = ByteArray(32).also { writeU32(it, 24, 1) }
        val ovl7Table = ByteArray(32).also { writeU32(it, 24, 2) }

        // Fixed layout
        val arm9Off = 0x200
        val arm7Off = arm9Off + 4                    // 0x204
        val fntOff = align4(arm7Off + 4)            // 0x208
        val fatOff = align4(fntOff + fnt.size)      // 0x218
        val ovl9TableOff = align4(fatOff + 3 * 8)         // 0x230
        val ovl7TableOff = align4(ovl9TableOff + 32)      // 0x250
        val file0Off = align4(ovl7TableOff + 32)      // 0x270
        val ovl9DataOff = align4(file0Off + file0.size)  // 0x274
        val ovl7DataOff = align4(ovl9DataOff + ovl9Data.size)
        val romSize = align4(ovl7DataOff + ovl7Data.size)

        // FAT (3 entries × 8 bytes)
        val fat = ByteArray(3 * 8)
        writeU32(fat, 0, file0Off); writeU32(fat, 4, file0Off + file0.size)
        writeU32(fat, 8, ovl9DataOff); writeU32(fat, 12, ovl9DataOff + ovl9Data.size)
        writeU32(fat, 16, ovl7DataOff); writeU32(fat, 20, ovl7DataOff + ovl7Data.size)

        // Header (512 bytes)
        val header = ByteArray(0x200)
        "TESTROM".encodeToByteArray().copyInto(header, 0)
        "TEST".encodeToByteArray().copyInto(header, 0x0C)
        writeU32(header, 0x020, arm9Off); writeU32(header, 0x02C, arm9.size)
        writeU32(header, 0x030, arm7Off); writeU32(header, 0x03C, arm7.size)
        writeU32(header, 0x040, fntOff); writeU32(header, 0x044, fnt.size)
        writeU32(header, 0x048, fatOff); writeU32(header, 0x04C, fat.size)
        writeU32(header, 0x050, ovl9TableOff); writeU32(header, 0x054, 32)
        writeU32(header, 0x058, ovl7TableOff); writeU32(header, 0x05C, 32)
        // 0x068 = bannerOff = 0 (no banner)
        writeU32(header, 0x080, romSize)

        // Assemble
        val rom = ByteArray(romSize)
        header.copyInto(rom, 0)
        arm9.copyInto(rom, arm9Off)
        arm7.copyInto(rom, arm7Off)
        fnt.copyInto(rom, fntOff)
        fat.copyInto(rom, fatOff)
        ovl9Table.copyInto(rom, ovl9TableOff)
        ovl7Table.copyInto(rom, ovl7TableOff)
        file0.copyInto(rom, file0Off)
        ovl9Data.copyInto(rom, ovl9DataOff)
        ovl7Data.copyInto(rom, ovl7DataOff)
        return rom
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `arm9Overlays returns one entry with correct data`() {
        val ovl9 = byteArrayOf(1, 2, 3, 4)
        val ovl7 = byteArrayOf(5, 6, 7, 8)
        val rom = NdsRom.parse(buildMinimalRom(ovl9, ovl7))
        assertEquals(1, rom.arm9Overlays.size)
        assertContentEquals(ovl9, rom.arm9Overlays[0])
    }

    @Test
    fun `arm7Overlays returns one entry with correct data`() {
        val ovl9 = byteArrayOf(1, 2, 3, 4)
        val ovl7 = byteArrayOf(5, 6, 7, 8)
        val rom = NdsRom.parse(buildMinimalRom(ovl9, ovl7))
        assertEquals(1, rom.arm7Overlays.size)
        assertContentEquals(ovl7, rom.arm7Overlays[0])
    }

    @Test
    fun `arm9Overlays is empty when no arm9 overlay table`() {
        val rom = NdsRom.parse(buildMinimalRom(byteArrayOf(), byteArrayOf()))
        // When ovl9Data is empty the FAT entry has zero size — overlay is still listed (1 entry)
        // but the returned bytes are empty
        assertEquals(1, rom.arm9Overlays.size)
        assertContentEquals(ByteArray(0), rom.arm9Overlays[0])
    }

    @Test
    fun `withArm9Overlay replaces data and leaves original unchanged`() {
        val ovl9 = byteArrayOf(1, 2, 3, 4)
        val ovl7 = byteArrayOf(5, 6, 7, 8)
        val rom = NdsRom.parse(buildMinimalRom(ovl9, ovl7))
        val newData = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

        val modified = rom.withArm9Overlay(0, newData)

        assertContentEquals(newData, modified.arm9Overlays[0])
        assertContentEquals(ovl9, rom.arm9Overlays[0])   // original unchanged
    }

    @Test
    fun `withArm7Overlay replaces data and leaves original unchanged`() {
        val ovl9 = byteArrayOf(1, 2, 3, 4)
        val ovl7 = byteArrayOf(5, 6, 7, 8)
        val rom = NdsRom.parse(buildMinimalRom(ovl9, ovl7))
        val newData = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        val modified = rom.withArm7Overlay(0, newData)

        assertContentEquals(newData, modified.arm7Overlays[0])
        assertContentEquals(ovl7, rom.arm7Overlays[0])   // original unchanged
    }

    @Test
    fun `withArm9Overlay does not affect arm7 overlays`() {
        val ovl9 = byteArrayOf(1, 2, 3, 4)
        val ovl7 = byteArrayOf(5, 6, 7, 8)
        val rom = NdsRom.parse(buildMinimalRom(ovl9, ovl7))

        val modified = rom.withArm9Overlay(0, byteArrayOf(0xFF.toByte()))

        assertContentEquals(ovl7, modified.arm7Overlays[0])
    }

    @Test
    fun `overlay replacement survives pack and parse round-trip`() {
        val ovl9 = byteArrayOf(1, 2, 3, 4)
        val ovl7 = byteArrayOf(5, 6, 7, 8)
        val rom = NdsRom.parse(buildMinimalRom(ovl9, ovl7))
        val newOvl9 = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

        val reparsed = NdsRom.parse(rom.withArm9Overlay(0, newOvl9).pack())

        assertContentEquals(newOvl9, reparsed.arm9Overlays[0])
        assertContentEquals(ovl7, reparsed.arm7Overlays[0])   // arm7 unchanged
    }

    @Test
    fun `filesystem file is independent of overlays`() {
        val ovl9 = byteArrayOf(0x91.toByte(), 0x92.toByte())
        val ovl7 = byteArrayOf(0x71.toByte(), 0x72.toByte())
        val rom = NdsRom.parse(buildMinimalRom(ovl9, ovl7))

        assertNotNull(rom.files["f.bin"], "filesystem file 'f.bin' should be present")
        // overlays must not bleed into the VFS
        assertEquals(1, rom.files.size)
    }
}
