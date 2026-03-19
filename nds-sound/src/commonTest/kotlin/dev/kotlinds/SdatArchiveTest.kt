package dev.kotlinds

import kotlin.test.*

/**
 * Unit tests for [SdatArchive] covering both [SdatArchive.unpack] and [SdatArchive.pack].
 *
 * All tests are self-contained: binary test data is constructed programmatically by
 * [buildMinimalSdat] — no real `.sdat` files are required.
 */
class SdatArchiveTest {

    // =========================================================================
    // Minimal SDAT binary builder
    // =========================================================================

    /**
     * Writes a little-endian u32 into [buf] at [off].
     *
     * @param buf Target byte array.
     * @param off Byte offset.
     * @param v Value to write.
     */
    private fun writeU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /**
     * Writes a little-endian u16 into [buf] at [off].
     *
     * @param buf Target byte array.
     * @param off Byte offset.
     * @param v Value to write (lower 16 bits used).
     */
    private fun writeU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    /**
     * Rounds [n] up to the next 32-byte boundary.
     *
     * @param n Value to align.
     * @return Aligned value.
     */
    private fun align32(n: Int): Int = (n + 31) and -32

    /**
     * Builds a minimal but structurally valid SDAT binary containing:
     *   - 1 SSEQ file named [sseqName] with payload [sseqData], bank=1, vol=100, chanPri=64, plrPri=32, players=1
     *   - 1 SBNK file named [sbnkName] with payload [sbnkData], wars=[0, -1, -1, -1]
     *   - 1 SWAR file named [swarName] with payload [swarData]
     *   - 1 STRM file named [strmName] with payload [strmData]
     *
     * When [includeSymb] is false the SYMB block is omitted (symbOffset/Length = 0 in header).
     *
     * @param sseqName Symbolic name for the SSEQ entry (empty = unnamed).
     * @param sseqData Raw payload for the SSEQ file.
     * @param sbnkName Symbolic name for the SBNK entry.
     * @param sbnkData Raw payload for the SBNK file.
     * @param swarName Symbolic name for the SWAR entry.
     * @param swarData Raw payload for the SWAR file.
     * @param strmName Symbolic name for the STRM entry.
     * @param strmData Raw payload for the STRM file.
     * @param includeSymb Whether to include the SYMB block.
     * @return A valid SDAT byte array.
     */
    private fun buildMinimalSdat(
        sseqName: String = "SEQ_MAIN",
        sseqData: ByteArray = byteArrayOf(0x53, 0x53, 0x45, 0x51),
        sbnkName: String = "BANK_MAIN",
        sbnkData: ByteArray = byteArrayOf(0x53, 0x42, 0x4E, 0x4B),
        swarName: String = "WAVE_MAIN",
        swarData: ByteArray = byteArrayOf(0x53, 0x57, 0x41, 0x52),
        strmName: String = "STRM_MAIN",
        strmData: ByteArray = byteArrayOf(0x53, 0x54, 0x52, 0x4D),
        includeSymb: Boolean = true,
    ): ByteArray {
        // ---- File payloads (in FAT order: SSEQ=0, SBNK=1, SWAR=2, STRM=3) -----
        val files = listOf(sseqData, sbnkData, swarData, strmData)

        // ---- SYMB block --------------------------------------------------------
        // slot offsets (8 u32s, relative to SYMB start) — only slots 0,2,3,7
        // SYMB header: magic(4)+size(4)+8 slots(32) = 40 bytes
        val symbBlock: ByteArray = if (includeSymb) {
            val names = mapOf(0 to sseqName, 2 to sbnkName, 3 to swarName, 7 to strmName)
            buildSymbBlockHelper(names)
        } else ByteArray(0)

        // ---- INFO block --------------------------------------------------------
        // INFO header: magic(4)+size(4)+8 slots(32) = 40 bytes
        // We put all 4 slots (SSEQ=0, SBNK=2, SWAR=3, STRM=7), others → offset 0.
        // SSEQ entry = 12 bytes, SBNK = 12, SWAR = 4, STRM = 12
        val infoBlock = buildInfoBlockHelper(
            sseqBank = 1, sseqVol = 100, sseqChanPri = 64, sseqPlrPri = 32, sseqPlayers = 1,
            sbnkWar0 = 0,  // references SWAR index 0
        )

        // ---- FAT block ---------------------------------------------------------
        // magic(4)+size(4)+nFiles(4) + 4 × 16-byte entries
        val fatBlockSize = 12 + files.size * 16
        val fatBlock = ByteArray(fatBlockSize)
        fatBlock[0] = 'F'.code.toByte(); fatBlock[1] = 'A'.code.toByte()
        fatBlock[2] = 'T'.code.toByte(); fatBlock[3] = ' '.code.toByte()

        // ---- Layout computation ------------------------------------------------
        val headerSize = 0x40
        val symbBlockSize = symbBlock.size
        val infoBlockSize = infoBlock.size
        val fileHeaderSize = 16  // magic+size+nFiles+reserved

        val symbOff = if (includeSymb) headerSize else 0
        val infoOff = headerSize + symbBlockSize
        val fatOff = infoOff + infoBlockSize
        val fileOff = fatOff + fatBlockSize
        val fileDataStart = fileOff + fileHeaderSize

        // Compute absolute file offsets
        val absOffsets = IntArray(files.size)
        var cursor = fileDataStart
        for (i in files.indices) {
            absOffsets[i] = cursor
            cursor += align32(files[i].size)
        }
        val totalFileDataSize = cursor - fileDataStart

        // Write FAT
        writeU32(fatBlock, 4, fatBlockSize)
        writeU32(fatBlock, 8, files.size)
        for (i in files.indices) {
            val base = 12 + i * 16
            writeU32(fatBlock, base, absOffsets[i])
            writeU32(fatBlock, base + 4, files[i].size)
        }

        // ---- FILE block --------------------------------------------------------
        val fileBlockSize = fileHeaderSize + totalFileDataSize
        val fileBlock = ByteArray(fileBlockSize)
        fileBlock[0] = 'F'.code.toByte(); fileBlock[1] = 'I'.code.toByte()
        fileBlock[2] = 'L'.code.toByte(); fileBlock[3] = 'E'.code.toByte()
        writeU32(fileBlock, 4, fileBlockSize)
        writeU32(fileBlock, 8, files.size)
        var pos = fileHeaderSize
        for (blob in files) {
            blob.copyInto(fileBlock, pos)
            pos += align32(blob.size)
        }

        // ---- Main header -------------------------------------------------------
        val numBlocks = if (includeSymb) 4 else 3
        val totalSize = headerSize + symbBlockSize + infoBlockSize + fatBlockSize + fileBlockSize

        val header = ByteArray(headerSize)
        header[0] = 'S'.code.toByte(); header[1] = 'D'.code.toByte()
        header[2] = 'A'.code.toByte(); header[3] = 'T'.code.toByte()
        header[4] = 0xFE.toByte(); header[5] = 0xFF.toByte()
        header[6] = 0x01; header[7] = 0x00
        writeU32(header, 0x08, totalSize)
        header[0x0C] = 0x40; header[0x0D] = 0x00
        header[0x0E] = numBlocks.toByte(); header[0x0F] = 0x00
        if (includeSymb) {
            writeU32(header, 0x10, symbOff)
            writeU32(header, 0x14, symbBlockSize)
        }
        writeU32(header, 0x18, infoOff)
        writeU32(header, 0x1C, infoBlockSize)
        writeU32(header, 0x20, fatOff)
        writeU32(header, 0x24, fatBlockSize)
        writeU32(header, 0x28, fileOff)
        writeU32(header, 0x2C, fileBlockSize)

        // ---- Assemble ----------------------------------------------------------
        val out = ByteArray(totalSize)
        var cur = 0
        header.copyInto(out, cur); cur += header.size
        if (includeSymb) {
            symbBlock.copyInto(out, cur); cur += symbBlock.size
        }
        infoBlock.copyInto(out, cur); cur += infoBlock.size
        fatBlock.copyInto(out, cur); cur += fatBlock.size
        fileBlock.copyInto(out, cur)
        return out
    }

    /**
     * Helper that builds a SYMB block from a map of slot-index → name.
     *
     * @param names Map from SYMB slot index (0,2,3,7) to the single name string for that slot.
     * @return Raw SYMB block bytes.
     */
    private fun buildSymbBlockHelper(names: Map<Int, String>): ByteArray {
        // Header: magic(4)+size(4)+8×u32 slot offsets(32) = 40 bytes
        val blockHeader = 40
        var offset = blockHeader

        // For each of the 8 slots, build a record with 1 entry (or skip if absent)
        val slotOffsets = IntArray(8)
        val records = arrayOfNulls<ByteArray>(8)

        for (i in 0 until 8) {
            val name = names[i]
            if (name != null) {
                slotOffsets[i] = offset
                // record: u32 nEntries=1, u32 strOffset, null-terminated string
                val encoded = name.encodeToByteArray()
                val strOff = offset + 8  // offset within SYMB block
                val recSize = 4 + 4 + encoded.size + 1
                val rec = ByteArray(recSize)
                writeU32(rec, 0, 1)
                writeU32(rec, 4, if (name.isEmpty()) 0 else strOff)
                if (name.isNotEmpty()) {
                    encoded.copyInto(rec, 8)
                }
                records[i] = rec
                offset += recSize
            }
        }

        val totalSize = offset
        val block = ByteArray(totalSize)
        block[0] = 'S'.code.toByte(); block[1] = 'Y'.code.toByte()
        block[2] = 'M'.code.toByte(); block[3] = 'B'.code.toByte()
        writeU32(block, 4, totalSize)
        for (i in 0 until 8) writeU32(block, 8 + i * 4, slotOffsets[i])
        for (i in 0 until 8) {
            val rec = records[i]
            if (rec != null) rec.copyInto(block, slotOffsets[i])
        }
        return block
    }

    /**
     * Helper that builds an INFO block for 1 SSEQ, 1 SBNK, 1 SWAR, 1 STRM.
     *
     * @param sseqBank SBNK bank index referenced by the SSEQ.
     * @param sseqVol SSEQ volume.
     * @param sseqChanPri SSEQ channel priority.
     * @param sseqPlrPri SSEQ player priority.
     * @param sseqPlayers SSEQ player bitmask.
     * @param sbnkWar0 SWAR index for the first WAR slot of the SBNK (-1 = unused).
     * @param strmVol STRM volume.
     * @param strmPriority STRM priority.
     * @param strmPlayers STRM player bitmask.
     * @return Raw INFO block bytes.
     */
    private fun buildInfoBlockHelper(
        sseqBank: Int,
        sseqVol: Int,
        sseqChanPri: Int,
        sseqPlrPri: Int,
        sseqPlayers: Int,
        sbnkWar0: Int,
        strmVol: Int = 0,
        strmPriority: Int = 0,
        strmPlayers: Int = 0,
    ): ByteArray {
        // INFO block header: 40 bytes (magic+size+8 slot-offsets)
        val blockHeader = 40
        var off = blockHeader

        // SSEQ slot (slot 0), 1 entry × 12 bytes
        val sseqSlotOff = off
        val sseqRecSize = 4 + 1 * 4 + 1 * 12
        val sseqRec = ByteArray(sseqRecSize)
        writeU32(sseqRec, 0, 1)  // nEntries
        val sseqEntryOffInBlock = sseqSlotOff + 4 + 4
        writeU32(sseqRec, 4, sseqEntryOffInBlock)  // entry offset relative to INFO block
        writeU16(sseqRec, 8, 0)           // fileId = 0 (SSEQ is FAT #0)
        writeU16(sseqRec, 10, 0)           // unk
        writeU16(sseqRec, 12, sseqBank)
        sseqRec[14] = sseqVol.toByte()
        sseqRec[15] = sseqChanPri.toByte()
        sseqRec[16] = sseqPlrPri.toByte()
        sseqRec[17] = sseqPlayers.toByte()
        off += sseqRecSize

        // SBNK slot (slot 2), 1 entry × 12 bytes
        val sbnkSlotOff = off
        val sbnkRecSize = 4 + 1 * 4 + 1 * 12
        val sbnkRec = ByteArray(sbnkRecSize)
        writeU32(sbnkRec, 0, 1)
        val sbnkEntryOffInBlock = sbnkSlotOff + 4 + 4
        writeU32(sbnkRec, 4, sbnkEntryOffInBlock)
        writeU16(sbnkRec, 8, 1)   // fileId = 1 (SBNK is FAT #1)
        writeU16(sbnkRec, 10, 0)
        val war0v = if (sbnkWar0 >= 0) sbnkWar0 else 0xFFFF
        writeU16(sbnkRec, 12, war0v)
        writeU16(sbnkRec, 14, 0xFFFF)
        writeU16(sbnkRec, 16, 0xFFFF)
        writeU16(sbnkRec, 18, 0xFFFF)
        off += sbnkRecSize

        // SWAR slot (slot 3), 1 entry × 4 bytes
        val swarSlotOff = off
        val swarRecSize = 4 + 1 * 4 + 1 * 4
        val swarRec = ByteArray(swarRecSize)
        writeU32(swarRec, 0, 1)
        val swarEntryOffInBlock = swarSlotOff + 4 + 4
        writeU32(swarRec, 4, swarEntryOffInBlock)
        writeU16(swarRec, 8, 2)   // fileId = 2 (SWAR is FAT #2)
        writeU16(swarRec, 10, 0)
        off += swarRecSize

        // STRM slot (slot 7), 1 entry × 12 bytes
        val strmSlotOff = off
        val strmRecSize = 4 + 1 * 4 + 1 * 12
        val strmRec = ByteArray(strmRecSize)
        writeU32(strmRec, 0, 1)
        val strmEntryOffInBlock = strmSlotOff + 4 + 4
        writeU32(strmRec, 4, strmEntryOffInBlock)
        writeU16(strmRec, 8, 3)   // fileId = 3 (STRM is FAT #3)
        writeU16(strmRec, 10, 0)   // unk
        strmRec[12] = strmVol.toByte()
        strmRec[13] = strmPriority.toByte()
        strmRec[14] = strmPlayers.toByte()
        off += strmRecSize

        val totalSize = off
        val block = ByteArray(totalSize)
        block[0] = 'I'.code.toByte(); block[1] = 'N'.code.toByte()
        block[2] = 'F'.code.toByte(); block[3] = 'O'.code.toByte()
        writeU32(block, 4, totalSize)
        // slot offsets
        writeU32(block, 8 + 0 * 4, sseqSlotOff)  // slot 0 = SSEQ
        writeU32(block, 8 + 2 * 4, sbnkSlotOff)  // slot 2 = SBNK
        writeU32(block, 8 + 3 * 4, swarSlotOff)  // slot 3 = SWAR
        writeU32(block, 8 + 7 * 4, strmSlotOff)  // slot 7 = STRM
        sseqRec.copyInto(block, sseqSlotOff)
        sbnkRec.copyInto(block, sbnkSlotOff)
        swarRec.copyInto(block, swarSlotOff)
        strmRec.copyInto(block, strmSlotOff)
        return block
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /** Verifies that [SdatArchive.unpack] rejects input that does not start with `"SDAT"`. */
    @Test
    fun `unpack throws on non-SDAT magic`() {
        val bad = ByteArray(0x40) { 0x00 }
        "NARC".encodeToByteArray().copyInto(bad, 0)
        assertFailsWith<IllegalArgumentException> { SdatArchive.unpack(bad) }
    }

    /** Verifies that [SdatArchive.unpack] rejects input shorter than the 0x40-byte header. */
    @Test
    fun `unpack throws on too-short input`() {
        val bad = ByteArray(0x10)
        assertFailsWith<IllegalArgumentException> { SdatArchive.unpack(bad) }
    }

    /** Verifies that unpacking a minimal SDAT yields exactly one sequence. */
    @Test
    fun `unpack returns correct sequence count`() {
        val sdat = buildMinimalSdat()
        val archive = SdatArchive.unpack(sdat)
        assertEquals(1, archive.sequences.size)
    }

    /** Verifies that the sequence name is read from the SYMB block. */
    @Test
    fun `unpack sequence has correct name from SYMB`() {
        val sdat = buildMinimalSdat(sseqName = "SEQ_MAIN")
        val archive = SdatArchive.unpack(sdat)
        assertEquals("SEQ_MAIN", archive.sequences[0].name)
    }

    /** Verifies that the sequence raw data matches the payload embedded in the SDAT. */
    @Test
    fun `unpack sequence data matches payload`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val sdat = buildMinimalSdat(sseqData = payload)
        val archive = SdatArchive.unpack(sdat)
        assertContentEquals(payload, archive.sequences[0].data)
    }

    /** Verifies that the SBNK WAR cross-references are decoded correctly. */
    @Test
    fun `unpack bank has correct war cross-references`() {
        val sdat = buildMinimalSdat()
        val archive = SdatArchive.unpack(sdat)
        assertEquals(1, archive.banks.size)
        // war slot 0 should be 0 (references SWAR index 0), others -1
        assertEquals(0, archive.banks[0].wars[0])
        assertEquals(-1, archive.banks[0].wars[1])
        assertEquals(-1, archive.banks[0].wars[2])
        assertEquals(-1, archive.banks[0].wars[3])
    }

    /** Verifies that the wave archive name and data are extracted correctly. */
    @Test
    fun `unpack wave archive has correct name and data`() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val sdat = buildMinimalSdat(swarName = "WAVE_MAIN", swarData = payload)
        val archive = SdatArchive.unpack(sdat)
        assertEquals(1, archive.waveArchives.size)
        assertEquals("WAVE_MAIN", archive.waveArchives[0].name)
        assertContentEquals(payload, archive.waveArchives[0].data)
    }

    /** Verifies that the stream name and data are extracted correctly. */
    @Test
    fun `unpack stream has correct name and data`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33)
        val sdat = buildMinimalSdat(strmName = "STRM_MAIN", strmData = payload)
        val archive = SdatArchive.unpack(sdat)
        assertEquals(1, archive.streams.size)
        assertEquals("STRM_MAIN", archive.streams[0].name)
        assertContentEquals(payload, archive.streams[0].data)
    }

    /** Verifies that fallback names are generated when the SYMB block is absent. */
    @Test
    fun `unpack without SYMB assigns fallback names`() {
        val sdat = buildMinimalSdat(includeSymb = false)
        val archive = SdatArchive.unpack(sdat)
        assertEquals("SSEQ_0", archive.sequences[0].name)
        assertEquals("SBNK_0", archive.banks[0].name)
        assertEquals("SWAR_0", archive.waveArchives[0].name)
        assertEquals("STRM_0", archive.streams[0].name)
    }

    /**
     * Verifies that INFO entries whose fileId field equals 0xFFFF are skipped during unpack.
     *
     * We build an SDAT whose SSEQ entry has fileId=0xFFFF; the resulting archive must
     * contain zero sequences.
     */
    @Test
    fun `unpack skips entries with fileId 0xFFFF`() {
        // Build normally, then patch the SSEQ entry fileId in the INFO block to 0xFFFF
        val sdat = buildMinimalSdat().toMutableList()
        // Find "INFO" magic and locate the SSEQ entry
        val data = sdat.toByteArray()
        // Scan for "INFO" magic
        var infoOff = -1
        for (i in 0 until data.size - 4) {
            if (data[i] == 'I'.code.toByte() && data[i + 1] == 'N'.code.toByte() &&
                data[i + 2] == 'F'.code.toByte() && data[i + 3] == 'O'.code.toByte()
            ) {
                infoOff = i; break
            }
        }
        check(infoOff >= 0) { "INFO block not found in test data" }

        // slot 0 = SSEQ record offset relative to INFO start
        val sseqSlotRelOff = readU32LE(data, infoOff + 8 + 0 * 4)
        // entry 0 offset relative to INFO start (stored at sseqSlotRelOff+4)
        val entry0RelOff = readU32LE(data, infoOff + sseqSlotRelOff + 4)
        // fileId is the first u16 of the entry
        val fileIdOff = infoOff + entry0RelOff
        val patched = data.copyOf()
        patched[fileIdOff] = 0xFF.toByte()
        patched[fileIdOff + 1] = 0xFF.toByte()

        val archive = SdatArchive.unpack(patched)
        assertEquals(0, archive.sequences.size)
    }

    /** Verifies that [SdatArchive.sequenceByName] returns the correct entry. */
    @Test
    fun `sequenceByName returns correct entry`() {
        val sdat = buildMinimalSdat(sseqName = "SEQ_MAIN")
        val archive = SdatArchive.unpack(sdat)
        val found = archive.sequenceByName("SEQ_MAIN")
        assertNotNull(found)
        assertEquals("SEQ_MAIN", found.name)
    }

    /** Verifies that [SdatArchive.sequenceByName] returns null for an unknown name. */
    @Test
    fun `sequenceByName returns null for unknown name`() {
        val sdat = buildMinimalSdat()
        val archive = SdatArchive.unpack(sdat)
        assertNull(archive.sequenceByName("NO_SUCH"))
    }

    /** Verifies that a pack→unpack round-trip preserves all sequences. */
    @Test
    fun `pack-unpack round-trip preserves all sequences`() {
        val original = SdatArchive.unpack(buildMinimalSdat(sseqData = byteArrayOf(1, 2, 3)))
        val reparsed = SdatArchive.unpack(SdatArchive.pack(original))
        assertEquals(original.sequences.size, reparsed.sequences.size)
        val o = original.sequences[0]
        val r = reparsed.sequences[0]
        assertEquals(o.name, r.name)
        assertContentEquals(o.data, r.data)
        assertEquals(o.bank, r.bank)
        assertEquals(o.volume, r.volume)
        assertEquals(o.channelPriority, r.channelPriority)
        assertEquals(o.playerPriority, r.playerPriority)
        assertEquals(o.players, r.players)
    }

    /** Verifies that a pack→unpack round-trip preserves all banks including WAR indices. */
    @Test
    fun `pack-unpack round-trip preserves all banks including war indices`() {
        val original = SdatArchive.unpack(buildMinimalSdat(sbnkData = byteArrayOf(0xB1.toByte(), 0xB2.toByte())))
        val reparsed = SdatArchive.unpack(SdatArchive.pack(original))
        assertEquals(original.banks.size, reparsed.banks.size)
        val o = original.banks[0]
        val r = reparsed.banks[0]
        assertEquals(o.name, r.name)
        assertContentEquals(o.data, r.data)
        assertEquals(o.wars, r.wars)
    }

    /** Verifies that a pack→unpack round-trip preserves all wave archives. */
    @Test
    fun `pack-unpack round-trip preserves all wave archives`() {
        val original = SdatArchive.unpack(buildMinimalSdat(swarData = byteArrayOf(0xC1.toByte(), 0xC2.toByte())))
        val reparsed = SdatArchive.unpack(SdatArchive.pack(original))
        assertEquals(original.waveArchives.size, reparsed.waveArchives.size)
        val o = original.waveArchives[0]
        val r = reparsed.waveArchives[0]
        assertEquals(o.name, r.name)
        assertContentEquals(o.data, r.data)
    }

    /** Verifies that a pack→unpack round-trip preserves all streams. */
    @Test
    fun `pack-unpack round-trip preserves all streams`() {
        val original = SdatArchive.unpack(buildMinimalSdat(strmData = byteArrayOf(0xD1.toByte(), 0xD2.toByte())))
        val reparsed = SdatArchive.unpack(SdatArchive.pack(original))
        assertEquals(original.streams.size, reparsed.streams.size)
        val o = original.streams[0]
        val r = reparsed.streams[0]
        assertEquals(o.name, r.name)
        assertContentEquals(o.data, r.data)
        assertEquals(o.volume, r.volume)
        assertEquals(o.priority, r.priority)
        assertEquals(o.players, r.players)
    }

    /** Verifies that non-zero STRM volume/priority/players survive a pack→unpack round-trip. */
    @Test
    fun `pack-unpack round-trip preserves STRM metadata`() {
        val sdat = buildMinimalSdat(
            strmData = byteArrayOf(0xD1.toByte()),
            includeSymb = true,
        )
        // Rebuild with specific STRM metadata via the lower-level helper
        val files = listOf(
            byteArrayOf(0x53, 0x53, 0x45, 0x51),
            byteArrayOf(0x53, 0x42, 0x4E, 0x4B),
            byteArrayOf(0x53, 0x57, 0x41, 0x52),
            byteArrayOf(0xD1.toByte()),
        )
        // Build a minimal SDAT with STRM vol=80 prio=4 players=1
        val symbBlock =
            buildSymbBlockHelper(mapOf(0 to "SEQ_MAIN", 2 to "BANK_MAIN", 3 to "WAVE_MAIN", 7 to "STRM_MAIN"))
        val infoBlock = buildInfoBlockHelper(
            sseqBank = 1, sseqVol = 100, sseqChanPri = 64, sseqPlrPri = 32, sseqPlayers = 1,
            sbnkWar0 = 0,
            strmVol = 80, strmPriority = 4, strmPlayers = 1,
        )
        val fatBlockSize = 12 + files.size * 16
        val fatBlock = ByteArray(fatBlockSize)
        fatBlock[0] = 'F'.code.toByte(); fatBlock[1] = 'A'.code.toByte()
        fatBlock[2] = 'T'.code.toByte(); fatBlock[3] = ' '.code.toByte()
        val headerSize = 0x40
        val fileHeaderSize = 16
        val infoOff = headerSize + symbBlock.size
        val fatOff = infoOff + infoBlock.size
        val fileOff = fatOff + fatBlockSize
        val fileDataStart = fileOff + fileHeaderSize
        val absOffsets = IntArray(files.size)
        var cursor = fileDataStart
        for (i in files.indices) {
            absOffsets[i] = cursor; cursor += align32(files[i].size)
        }
        val totalFileDataSize = cursor - fileDataStart
        writeU32(fatBlock, 4, fatBlockSize)
        writeU32(fatBlock, 8, files.size)
        for (i in files.indices) {
            val b = 12 + i * 16; writeU32(fatBlock, b, absOffsets[i]); writeU32(fatBlock, b + 4, files[i].size)
        }
        val fileBlockSize = fileHeaderSize + totalFileDataSize
        val fileBlock = ByteArray(fileBlockSize)
        fileBlock[0] = 'F'.code.toByte(); fileBlock[1] = 'I'.code.toByte()
        fileBlock[2] = 'L'.code.toByte(); fileBlock[3] = 'E'.code.toByte()
        writeU32(fileBlock, 4, fileBlockSize); writeU32(fileBlock, 8, files.size)
        var pos = fileHeaderSize; for (blob in files) {
            blob.copyInto(fileBlock, pos); pos += align32(blob.size)
        }
        val totalSize = headerSize + symbBlock.size + infoBlock.size + fatBlockSize + fileBlockSize
        val header = ByteArray(headerSize)
        header[0] = 'S'.code.toByte(); header[1] = 'D'.code.toByte()
        header[2] = 'A'.code.toByte(); header[3] = 'T'.code.toByte()
        header[4] = 0xFE.toByte(); header[5] = 0xFF.toByte(); header[6] = 0x01; header[7] = 0x00
        writeU32(header, 0x08, totalSize); header[0x0C] = 0x40; header[0x0E] = 4
        writeU32(header, 0x10, headerSize); writeU32(header, 0x14, symbBlock.size)
        writeU32(header, 0x18, infoOff); writeU32(header, 0x1C, infoBlock.size)
        writeU32(header, 0x20, fatOff); writeU32(header, 0x24, fatBlockSize)
        writeU32(header, 0x28, fileOff); writeU32(header, 0x2C, fileBlockSize)
        val out = ByteArray(totalSize);
        var cur = 0
        header.copyInto(out, cur); cur += header.size
        symbBlock.copyInto(out, cur); cur += symbBlock.size
        infoBlock.copyInto(out, cur); cur += infoBlock.size
        fatBlock.copyInto(out, cur); cur += fatBlock.size
        fileBlock.copyInto(out, cur)

        val original = SdatArchive.unpack(out)
        val reparsed = SdatArchive.unpack(SdatArchive.pack(original))

        assertEquals(80, reparsed.streams[0].volume)
        assertEquals(4, reparsed.streams[0].priority)
        assertEquals(1, reparsed.streams[0].players)
    }

    /** Verifies that [SdatArchive.pack] produces output beginning with the `"SDAT"` magic. */
    @Test
    fun `pack produces valid SDAT magic`() {
        val original = SdatArchive.unpack(buildMinimalSdat())
        val packed = SdatArchive.pack(original)
        assertEquals("SDAT", packed.decodeToString(0, 4))
    }

    /**
     * Verifies that packing an archive with no SYMB block (all fallback names) omits the SYMB block,
     * and that a reparsed archive still has the correct fallback names.
     */
    @Test
    fun `pack without real names omits SYMB block`() {
        val archive = SdatArchive.unpack(buildMinimalSdat(includeSymb = false))
        val packed = SdatArchive.pack(archive)
        // Confirm no "SYMB" magic appears in the output
        val packedStr = packed.decodeToString()
        assertFalse(packedStr.contains("SYMB"), "SYMB block should not be written when all names are fallbacks")
        // And the reparsed archive still assigns fallback names
        val reparsed = SdatArchive.unpack(packed)
        assertEquals("SSEQ_0", reparsed.sequences[0].name)
        assertEquals("SBNK_0", reparsed.banks[0].name)
    }

    /**
     * Verifies that packing an archive where some types have real names but others have empty lists
     * produces a valid SDAT that round-trips correctly (tests the empty-slot SYMB record fix).
     */
    @Test
    fun `pack with empty streams slot still produces valid SDAT`() {
        // Build an archive with no streams, but real names on sequences
        val archive = SdatArchive(
            sequences = listOf(SdatSseqFile("MY_SEQ", byteArrayOf(1, 2), 0, 0, 0, 0, 0, 0)),
            banks = emptyList(),
            waveArchives = emptyList(),
            streams = emptyList(),
        )
        val packed = SdatArchive.pack(archive)
        val reparsed = SdatArchive.unpack(packed)
        assertEquals(1, reparsed.sequences.size)
        assertEquals("MY_SEQ", reparsed.sequences[0].name)
        assertEquals(0, reparsed.streams.size)
    }

    // =========================================================================
    // Small helpers (no dependency on production code utilities)
    // =========================================================================

    /**
     * Reads a little-endian u32 from [buf] at [off].
     *
     * @param buf Source byte array.
     * @param off Byte offset.
     * @return The decoded value as an [Int].
     */
    private fun readU32LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)
}
