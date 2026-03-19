package dev.kotlinds

/**
 * Reads and writes Nintendo DS SDAT (Sound Data Archive) container files.
 *
 * SDAT binary layout (all fields little-endian):
 *
 *   [Main header – 0x40 bytes]
 *     0x00  magic        "SDAT"
 *     0x04  BOM          FE FF
 *     0x06  version      01 00
 *     0x08  total size   (u32)
 *     0x0C  header size  40 00
 *     0x0E  numBlocks    (u16)  2–4
 *     0x10  symbOffset   (u32, 0 if absent)
 *     0x14  symbLength   (u32, 0 if absent)
 *     0x18  infoOffset   (u32)
 *     0x1C  infoLength   (u32)
 *     0x20  fatOffset    (u32)
 *     0x24  fatLength    (u32)
 *     0x28  fileOffset   (u32)
 *     0x2C  fileLength   (u32)
 *     0x30  reserved     16 zero bytes
 *
 *   [SYMB block – optional]
 *     0x00  magic "SYMB", 0x04 block size
 *     0x08  8 × u32 record-slot offsets (relative to SYMB block start)
 *           slot 0=SSEQ, 2=SBNK, 3=SWAR, 7=STRM (others unused)
 *     Each slot: u32 nEntries, nEntries × u32 string offsets (rel. to SYMB start; 0=unnamed)
 *     Each string: null-terminated ASCII
 *
 *   [INFO block – required]
 *     0x00  magic "INFO", 0x04 block size
 *     0x08  8 × u32 record-slot offsets (relative to INFO block start)
 *     Each slot: u32 nEntries, nEntries × u32 entry offsets (rel. to INFO start; 0=deleted)
 *     SSEQ entry (12 bytes): u16 fileId, u16 unk, u16 bank, u8 vol, u8 chanPri, u8 plrPri, u8 players, u8[2]
 *     SBNK entry (12 bytes): u16 fileId, u16 unk, u16[4] wars
 *     SWAR entry  (4 bytes): u16 fileId, u16 unk
 *     STRM entry (12 bytes): u16 fileId, u16 unk, u8 vol, u8 prio, u8 players, u8[5]
 *
 *   [FAT block – required]
 *     magic "FAT " (0x46415420)
 *     0x08 u32 nEntries; each entry 16 bytes: u32 absOffset, u32 length, 8 reserved
 *
 *   [FILE block – required]
 *     magic "FILE", 0x08 u32 nFiles, 4 reserved, raw file data
 *
 * @property sequences All SSEQ sequence files in archive order.
 * @property banks All SBNK bank files in archive order.
 * @property waveArchives All SWAR wave-archive files in archive order.
 * @property streams All STRM stream files in archive order.
 */
data class SdatArchive(
    val sequences: List<SdatSseqFile>,
    val banks: List<SdatSbnkFile>,
    val waveArchives: List<SdatSwarFile>,
    val streams: List<SdatStrmFile>,
) {

    /**
     * Finds a sequence by its symbolic name.
     *
     * @param name The name to look up.
     * @return The matching [SdatSseqFile], or `null` if not found.
     */
    fun sequenceByName(name: String): SdatSseqFile? = sequences.firstOrNull { it.name == name }

    /**
     * Finds a bank by its symbolic name.
     *
     * @param name The name to look up.
     * @return The matching [SdatSbnkFile], or `null` if not found.
     */
    fun bankByName(name: String): SdatSbnkFile? = banks.firstOrNull { it.name == name }

    /**
     * Finds a wave archive by its symbolic name.
     *
     * @param name The name to look up.
     * @return The matching [SdatSwarFile], or `null` if not found.
     */
    fun waveArchiveByName(name: String): SdatSwarFile? = waveArchives.firstOrNull { it.name == name }

    /**
     * Finds a stream by its symbolic name.
     *
     * @param name The name to look up.
     * @return The matching [SdatStrmFile], or `null` if not found.
     */
    fun streamByName(name: String): SdatStrmFile? = streams.firstOrNull { it.name == name }

    companion object {

        // Slot indices within the 8-slot SYMB/INFO record tables
        private const val SLOT_SSEQ = 0
        private const val SLOT_SBNK = 2
        private const val SLOT_SWAR = 3
        private const val SLOT_STRM = 7

        // Sentinel values
        private const val FILE_ID_DELETED = 0xFFFF
        private const val WAR_UNUSED = 0xFFFF

        // -------------------------------------------------------------------------
        // Unpack
        // -------------------------------------------------------------------------

        /**
         * Parses a raw SDAT byte array and returns a fully populated [SdatArchive].
         *
         * @param data The raw SDAT bytes.
         * @return An [SdatArchive] containing all sequences, banks, wave archives and streams.
         * @throws IllegalArgumentException if [data] is too short or does not start with `"SDAT"`.
         */
        fun unpack(data: ByteArray): SdatArchive {
            require(data.size >= 0x40) { "SDAT too small" }
            require(data.decodeToString(0, 4) == "SDAT") { "Not an SDAT file" }

            val symbOffset = u32(data, 0x10).toInt()
            val symbLength = u32(data, 0x14).toInt()
            val infoOffset = u32(data, 0x18).toInt()
            val infoLength = u32(data, 0x1C).toInt()
            val fatOffset = u32(data, 0x20).toInt()

            // ---- SYMB block (optional) ------------------------------------------
            val haveSYMB = symbOffset != 0 && symbLength != 0
            val symbNames = Array(8) { emptyList<String>() }
            if (haveSYMB) {
                require(data.decodeToString(symbOffset, symbOffset + 4) == "SYMB") {
                    "Expected SYMB magic at offset $symbOffset"
                }
                for (slot in intArrayOf(SLOT_SSEQ, SLOT_SBNK, SLOT_SWAR, SLOT_STRM)) {
                    val recOff = u32(data, symbOffset + 8 + slot * 4).toInt()
                    symbNames[slot] = readSymbRecord(data, symbOffset, recOff)
                }
            }

            // ---- INFO block ----------------------------------------------------
            require(data.decodeToString(infoOffset, infoOffset + 4) == "INFO") {
                "Expected INFO magic at offset $infoOffset"
            }

            val sseqRecOff = u32(data, infoOffset + 8 + SLOT_SSEQ * 4).toInt()
            val sbnkRecOff = u32(data, infoOffset + 8 + SLOT_SBNK * 4).toInt()
            val swarRecOff = u32(data, infoOffset + 8 + SLOT_SWAR * 4).toInt()
            val strmRecOff = u32(data, infoOffset + 8 + SLOT_STRM * 4).toInt()

            // ---- FAT block -----------------------------------------------------
            require(data.decodeToString(fatOffset, fatOffset + 4) == "FAT ") {
                "Expected 'FAT ' magic at offset $fatOffset"
            }
            val nFatEntries = u32(data, fatOffset + 8).toInt()
            // Each FAT entry: 16 bytes = u32 absOffset + u32 length + 8 reserved
            val fatEntries = Array(nFatEntries) { i ->
                val base = fatOffset + 12 + i * 16
                val absOff = u32(data, base).toInt()
                val len = u32(data, base + 4).toInt()
                Pair(absOff, len)
            }

            fun fileData(fileId: Int): ByteArray {
                val (off, len) = fatEntries[fileId]
                return data.copyOfRange(off, off + len)
            }

            // ---- Resolve SSEQ entries ------------------------------------------
            val sequences = mutableListOf<SdatSseqFile>()
            val sseqCount = u32(data, infoOffset + sseqRecOff).toInt()
            for (i in 0 until sseqCount) {
                val entryOff = u32(data, infoOffset + sseqRecOff + 4 + i * 4).toInt()
                if (entryOff == 0) continue
                val base = infoOffset + entryOff
                val fileId = u16(data, base)
                if (fileId == FILE_ID_DELETED) continue
                val unk = u16(data, base + 2)
                val bank = u16(data, base + 4)
                val volume = data[base + 6].toInt() and 0xFF
                val chanPri = data[base + 7].toInt() and 0xFF
                val plrPri = data[base + 8].toInt() and 0xFF
                val players = data[base + 9].toInt() and 0xFF
                val name = if (haveSYMB && i < symbNames[SLOT_SSEQ].size && symbNames[SLOT_SSEQ][i].isNotEmpty())
                    symbNames[SLOT_SSEQ][i]
                else
                    "SSEQ_$i"
                sequences.add(SdatSseqFile(name, fileData(fileId), unk, bank, volume, chanPri, plrPri, players))
            }

            // ---- Resolve SBNK entries ------------------------------------------
            val banks = mutableListOf<SdatSbnkFile>()
            val sbnkCount = u32(data, infoOffset + sbnkRecOff).toInt()
            for (i in 0 until sbnkCount) {
                val entryOff = u32(data, infoOffset + sbnkRecOff + 4 + i * 4).toInt()
                if (entryOff == 0) continue
                val base = infoOffset + entryOff
                val fileId = u16(data, base)
                if (fileId == FILE_ID_DELETED) continue
                val unk = u16(data, base + 2)
                val wars = List(4) { w ->
                    val v = u16(data, base + 4 + w * 2)
                    if (v == WAR_UNUSED) -1 else v
                }
                val name = if (haveSYMB && i < symbNames[SLOT_SBNK].size && symbNames[SLOT_SBNK][i].isNotEmpty())
                    symbNames[SLOT_SBNK][i]
                else
                    "SBNK_$i"
                banks.add(SdatSbnkFile(name, fileData(fileId), unk, wars))
            }

            // ---- Resolve SWAR entries ------------------------------------------
            val waveArchives = mutableListOf<SdatSwarFile>()
            val swarCount = u32(data, infoOffset + swarRecOff).toInt()
            for (i in 0 until swarCount) {
                val entryOff = u32(data, infoOffset + swarRecOff + 4 + i * 4).toInt()
                if (entryOff == 0) continue
                val base = infoOffset + entryOff
                val fileId = u16(data, base)
                if (fileId == FILE_ID_DELETED) continue
                val unk = u16(data, base + 2)
                val name = if (haveSYMB && i < symbNames[SLOT_SWAR].size && symbNames[SLOT_SWAR][i].isNotEmpty())
                    symbNames[SLOT_SWAR][i]
                else
                    "SWAR_$i"
                waveArchives.add(SdatSwarFile(name, fileData(fileId), unk))
            }

            // ---- Resolve STRM entries ------------------------------------------
            val streams = mutableListOf<SdatStrmFile>()
            val strmCount = u32(data, infoOffset + strmRecOff).toInt()
            for (i in 0 until strmCount) {
                val entryOff = u32(data, infoOffset + strmRecOff + 4 + i * 4).toInt()
                if (entryOff == 0) continue
                val base = infoOffset + entryOff
                val fileId = u16(data, base)
                if (fileId == FILE_ID_DELETED) continue
                val unk = u16(data, base + 2)
                val volume = data[base + 4].toInt() and 0xFF
                val priority = data[base + 5].toInt() and 0xFF
                val players = data[base + 6].toInt() and 0xFF
                val name = if (haveSYMB && i < symbNames[SLOT_STRM].size && symbNames[SLOT_STRM][i].isNotEmpty())
                    symbNames[SLOT_STRM][i]
                else
                    "STRM_$i"
                streams.add(SdatStrmFile(name, fileData(fileId), unk, volume, priority, players))
            }

            return SdatArchive(sequences, banks, waveArchives, streams)
        }

        // -------------------------------------------------------------------------
        // Pack
        // -------------------------------------------------------------------------

        /**
         * Serialises an [SdatArchive] into a valid SDAT byte array.
         *
         * File order within the FAT/FILE blocks: SSEQ files first, then SBNK, SWAR, STRM.
         * Each file blob is padded to a 32-byte boundary before the next one.
         * A SYMB block is included only when at least one entry carries a name that differs
         * from the generated fallback (e.g. `"SSEQ_0"`, `"SBNK_1"`, …). Archives whose every
         * entry uses a fallback name are repacked without a SYMB block, preserving the original
         * structure of archives that never had one.
         *
         * @param archive The archive to serialise.
         * @return A fully formed SDAT byte array.
         */
        fun pack(archive: SdatArchive): ByteArray {
            val seqs = archive.sequences
            val banks = archive.banks
            val wars = archive.waveArchives
            val strms = archive.streams

            // File order: SSEQ → SBNK → SWAR → STRM
            val allFiles: List<ByteArray> =
                seqs.map { it.data } + banks.map { it.data } + wars.map { it.data } + strms.map { it.data }

            val sseqIdBase = 0
            val sbnkIdBase = seqs.size
            val swarIdBase = seqs.size + banks.size
            val strmIdBase = seqs.size + banks.size + wars.size

            // ---- Determine whether SYMB is needed ------------------------------
            // Only write a SYMB block if at least one name differs from the auto-generated
            // fallback (e.g. "SSEQ_0"). If every entry still carries a fallback name the
            // archive originally had no SYMB block and we should not invent one.
            val needSymb = seqs.indices.any { i -> seqs[i].name != "SSEQ_$i" } ||
                    banks.indices.any { i -> banks[i].name != "SBNK_$i" } ||
                    wars.indices.any { i -> wars[i].name != "SWAR_$i" } ||
                    strms.indices.any { i -> strms[i].name != "STRM_$i" }

            // ---- Build SYMB block bytes ----------------------------------------
            val symbBlock: ByteArray = if (needSymb) buildSymbBlock(seqs, banks, wars, strms) else ByteArray(0)

            // ---- Build INFO block bytes ----------------------------------------
            val infoBlock: ByteArray =
                buildInfoBlock(seqs, banks, wars, strms, sseqIdBase, sbnkIdBase, swarIdBase, strmIdBase)

            // ---- Build FAT block bytes -----------------------------------------
            // We need the absolute offsets from the start of the SDAT.
            // Compute section layout first, then write FAT.
            val headerSize = 0x40
            val symbBlockSize = symbBlock.size
            val infoBlockSize = infoBlock.size
            val fatHeaderSize = 12 + allFiles.size * 16   // magic(4)+size(4)+nFiles(4) + entries
            val fileHeaderSize = 16                         // magic(4)+size(4)+nFiles(4)+reserved(4)

            val symbOff = if (needSymb) headerSize else 0
            val infoOff = headerSize + symbBlockSize
            val fatOff = infoOff + infoBlockSize
            val fileOff = fatOff + fatHeaderSize

            // Compute per-file absolute offsets
            val fileAbsOffsets = IntArray(allFiles.size)
            var cursor = fileOff + fileHeaderSize
            for (i in allFiles.indices) {
                fileAbsOffsets[i] = cursor
                cursor += alignN(allFiles[i].size, 32)
            }
            val totalFileDataSize = cursor - (fileOff + fileHeaderSize)

            // ---- Build FAT block -----------------------------------------------
            val fatBlock = ByteArray(fatHeaderSize)
            fatBlock[0] = 'F'.code.toByte(); fatBlock[1] = 'A'.code.toByte()
            fatBlock[2] = 'T'.code.toByte(); fatBlock[3] = ' '.code.toByte()
            writeU32(fatBlock, 4, fatHeaderSize.toLong())
            writeU32(fatBlock, 8, allFiles.size.toLong())
            for (i in allFiles.indices) {
                val base = 12 + i * 16
                writeU32(fatBlock, base, fileAbsOffsets[i].toLong())
                writeU32(fatBlock, base + 4, allFiles[i].size.toLong())
                // 8 reserved bytes already zero
            }

            // ---- Build FILE block -----------------------------------------------
            val fileBlockSize = fileHeaderSize + totalFileDataSize
            val fileBlock = ByteArray(fileBlockSize)
            fileBlock[0] = 'F'.code.toByte(); fileBlock[1] = 'I'.code.toByte()
            fileBlock[2] = 'L'.code.toByte(); fileBlock[3] = 'E'.code.toByte()
            writeU32(fileBlock, 4, fileBlockSize.toLong())
            writeU32(fileBlock, 8, allFiles.size.toLong())
            // bytes 12-15 reserved = 0
            var pos = fileHeaderSize
            for (blob in allFiles) {
                blob.copyInto(fileBlock, pos)
                pos += alignN(blob.size, 32)
            }

            // ---- Assemble main header ------------------------------------------
            val numBlocks = if (needSymb) 4 else 3
            val totalSize = headerSize + symbBlockSize + infoBlockSize + fatHeaderSize + fileBlockSize

            val header = ByteArray(headerSize)
            header[0] = 'S'.code.toByte(); header[1] = 'D'.code.toByte()
            header[2] = 'A'.code.toByte(); header[3] = 'T'.code.toByte()
            header[4] = 0xFE.toByte(); header[5] = 0xFF.toByte()      // BOM
            header[6] = 0x01; header[7] = 0x00                        // version 1
            writeU32(header, 0x08, totalSize.toLong())
            header[0x0C] = 0x40; header[0x0D] = 0x00                 // header size = 0x40
            header[0x0E] = numBlocks.toByte(); header[0x0F] = 0x00

            if (needSymb) {
                writeU32(header, 0x10, symbOff.toLong())
                writeU32(header, 0x14, symbBlockSize.toLong())
            }
            writeU32(header, 0x18, infoOff.toLong())
            writeU32(header, 0x1C, infoBlockSize.toLong())
            writeU32(header, 0x20, fatOff.toLong())
            writeU32(header, 0x24, fatHeaderSize.toLong())
            writeU32(header, 0x28, fileOff.toLong())
            writeU32(header, 0x2C, fileBlockSize.toLong())
            // 0x30–0x3F remain zero (reserved)

            // ---- Final assembly ------------------------------------------------
            val out = ByteArray(totalSize)
            var cur = 0
            header.copyInto(out, cur); cur += header.size
            if (needSymb) {
                symbBlock.copyInto(out, cur); cur += symbBlock.size
            }
            infoBlock.copyInto(out, cur); cur += infoBlock.size
            fatBlock.copyInto(out, cur); cur += fatBlock.size
            fileBlock.copyInto(out, cur)
            return out
        }

        // -------------------------------------------------------------------------
        // SYMB block builder
        // -------------------------------------------------------------------------

        /**
         * Builds a complete SYMB block including magic, size, slot-offset table and string data.
         *
         * @param seqs SSEQ files whose names populate slot 0.
         * @param banks SBNK files whose names populate slot 2.
         * @param wars SWAR files whose names populate slot 3.
         * @param strms STRM files whose names populate slot 7.
         * @return The fully formed SYMB block byte array.
         */
        private fun buildSymbBlock(
            seqs: List<SdatSseqFile>,
            banks: List<SdatSbnkFile>,
            wars: List<SdatSwarFile>,
            strms: List<SdatStrmFile>,
        ): ByteArray {
            // The SYMB block starts with: magic(4) + size(4) + 8 slot-offsets(32) = 40 bytes header
            // All offsets stored in the slot table are relative to the SYMB block start.
            val slotNames = arrayOfNulls<List<String>>(8)
            slotNames[SLOT_SSEQ] = seqs.map { it.name }
            slotNames[SLOT_SBNK] = banks.map { it.name }
            slotNames[SLOT_SWAR] = wars.map { it.name }
            slotNames[SLOT_STRM] = strms.map { it.name }

            // Compute offsets for each slot's record (relative to SYMB block start)
            // Header = 8 (magic+size) + 32 (8 × u32 slot offsets) = 40 bytes
            val blockHeaderSize = 40

            // Build each slot's data: u32 nEntries + nEntries × u32 stringOffset + strings
            // We compute them in two passes: first figure out where each slot's data starts,
            // then build the actual string data.

            data class SlotData(val recordBytes: ByteArray, val offsetInBlock: Int)

            val slots = mutableListOf<SlotData?>()
            var offset = blockHeaderSize
            val slotOffsets = IntArray(8)

            for (i in 0 until 8) {
                val names = slotNames[i]
                if (names == null) {
                    slots.add(null)
                    slotOffsets[i] = 0
                } else if (names.isEmpty()) {
                    // Active slot with no entries: emit a minimal nEntries=0 record so that
                    // the slot offset is non-zero and readers don't misparse the block header.
                    slotOffsets[i] = offset
                    val emptyRecord = ByteArray(4) // nEntries = 0, already zero-initialised
                    slots.add(SlotData(emptyRecord, offset))
                    offset += 4
                } else {
                    slotOffsets[i] = offset
                    // record bytes: u32 nEntries + nEntries × u32 stringOffsets + null-terminated strings
                    // First pass: compute string offsets relative to SYMB block start
                    // stringData starts at: offset + 4 + nEntries*4
                    val stringsBaseInRecord = 4 + names.size * 4
                    val stringOffsets = IntArray(names.size)
                    var strOff = 0
                    for ((j, name) in names.withIndex()) {
                        if (name.isEmpty()) {
                            stringOffsets[j] = 0  // 0 = unnamed
                        } else {
                            stringOffsets[j] = offset + stringsBaseInRecord + strOff
                            strOff += name.encodeToByteArray().size + 1  // +1 for null terminator
                        }
                    }
                    val totalStrBytes = strOff
                    val recordSize = 4 + names.size * 4 + totalStrBytes
                    val recordBytes = ByteArray(recordSize)
                    writeU32(recordBytes, 0, names.size.toLong())
                    for ((j, name) in names.withIndex()) {
                        writeU32(recordBytes, 4 + j * 4, stringOffsets[j].toLong())
                    }
                    var strPos = 4 + names.size * 4
                    for (name in names) {
                        if (name.isNotEmpty()) {
                            val encoded = name.encodeToByteArray()
                            encoded.copyInto(recordBytes, strPos)
                            strPos += encoded.size + 1  // null terminator already zero
                        }
                    }
                    slots.add(SlotData(recordBytes, offset))
                    offset += recordSize
                }
            }

            val totalSize = offset
            val block = ByteArray(totalSize)
            block[0] = 'S'.code.toByte(); block[1] = 'Y'.code.toByte()
            block[2] = 'M'.code.toByte(); block[3] = 'B'.code.toByte()
            writeU32(block, 4, totalSize.toLong())
            for (i in 0 until 8) {
                writeU32(block, 8 + i * 4, slotOffsets[i].toLong())
            }
            for (slot in slots) {
                if (slot != null) {
                    slot.recordBytes.copyInto(block, slot.offsetInBlock)
                }
            }
            return block
        }

        // -------------------------------------------------------------------------
        // INFO block builder
        // -------------------------------------------------------------------------

        /**
         * Builds a complete INFO block including magic, size, slot-offset table and all entry structs.
         *
         * @param seqs SSEQ entries.
         * @param banks SBNK entries.
         * @param wars SWAR entries.
         * @param strms STRM entries.
         * @param sseqIdBase FAT file-ID base for SSEQ files.
         * @param sbnkIdBase FAT file-ID base for SBNK files.
         * @param swarIdBase FAT file-ID base for SWAR files.
         * @param strmIdBase FAT file-ID base for STRM files.
         * @return The fully formed INFO block byte array.
         */
        private fun buildInfoBlock(
            seqs: List<SdatSseqFile>,
            banks: List<SdatSbnkFile>,
            wars: List<SdatSwarFile>,
            strms: List<SdatStrmFile>,
            sseqIdBase: Int,
            sbnkIdBase: Int,
            swarIdBase: Int,
            strmIdBase: Int,
        ): ByteArray {
            // INFO block header: magic(4)+size(4)+8 slot-offsets(32) = 40 bytes
            val blockHeaderSize = 40

            // Build SSEQ record: u32 nEntries + nEntries × u32 entryOffset + entry structs
            // SSEQ entry = 12 bytes
            fun buildSseqRecord(baseInBlock: Int): ByteArray {
                val nEntries = seqs.size
                val recordHeaderSize = 4 + nEntries * 4
                val entrySize = 12
                val recordSize = recordHeaderSize + nEntries * entrySize
                val rec = ByteArray(recordSize)
                writeU32(rec, 0, nEntries.toLong())
                for (i in seqs.indices) {
                    val entryOff = baseInBlock + recordHeaderSize + i * entrySize
                    writeU32(rec, 4 + i * 4, entryOff.toLong())
                    val base = recordHeaderSize + i * entrySize
                    val fileId = sseqIdBase + i
                    writeU16(rec, base, fileId)
                    writeU16(rec, base + 2, seqs[i].unk)
                    writeU16(rec, base + 4, seqs[i].bank)
                    rec[base + 6] = seqs[i].volume.toByte()
                    rec[base + 7] = seqs[i].channelPriority.toByte()
                    rec[base + 8] = seqs[i].playerPriority.toByte()
                    rec[base + 9] = seqs[i].players.toByte()
                    // bytes 10-11 reserved = 0
                }
                return rec
            }

            // SBNK entry = 12 bytes (u16 fileId, u16 unk, u16[4] wars)
            fun buildSbnkRecord(baseInBlock: Int): ByteArray {
                val nEntries = banks.size
                val recordHeaderSize = 4 + nEntries * 4
                val entrySize = 12
                val recordSize = recordHeaderSize + nEntries * entrySize
                val rec = ByteArray(recordSize)
                writeU32(rec, 0, nEntries.toLong())
                for (i in banks.indices) {
                    val entryOff = baseInBlock + recordHeaderSize + i * entrySize
                    writeU32(rec, 4 + i * 4, entryOff.toLong())
                    val base = recordHeaderSize + i * entrySize
                    writeU16(rec, base, sbnkIdBase + i)
                    writeU16(rec, base + 2, banks[i].unk)
                    val wars = banks[i].wars
                    for (w in 0 until 4) {
                        val v = if (w < wars.size && wars[w] >= 0) wars[w] else WAR_UNUSED
                        writeU16(rec, base + 4 + w * 2, v)
                    }
                }
                return rec
            }

            // SWAR entry = 4 bytes (u16 fileId, u16 unk)
            fun buildSwarRecord(baseInBlock: Int): ByteArray {
                val nEntries = wars.size
                val recordHeaderSize = 4 + nEntries * 4
                val entrySize = 4
                val recordSize = recordHeaderSize + nEntries * entrySize
                val rec = ByteArray(recordSize)
                writeU32(rec, 0, nEntries.toLong())
                for (i in wars.indices) {
                    val entryOff = baseInBlock + recordHeaderSize + i * entrySize
                    writeU32(rec, 4 + i * 4, entryOff.toLong())
                    val base = recordHeaderSize + i * entrySize
                    writeU16(rec, base, swarIdBase + i)
                    writeU16(rec, base + 2, wars[i].unk)
                }
                return rec
            }

            // STRM entry = 12 bytes (u16 fileId, u16 unk, u8 vol, u8 prio, u8 players, u8[5])
            fun buildStrmRecord(baseInBlock: Int): ByteArray {
                val nEntries = strms.size
                val recordHeaderSize = 4 + nEntries * 4
                val entrySize = 12
                val recordSize = recordHeaderSize + nEntries * entrySize
                val rec = ByteArray(recordSize)
                writeU32(rec, 0, nEntries.toLong())
                for (i in strms.indices) {
                    val entryOff = baseInBlock + recordHeaderSize + i * entrySize
                    writeU32(rec, 4 + i * 4, entryOff.toLong())
                    val base = recordHeaderSize + i * entrySize
                    writeU16(rec, base, strmIdBase + i)
                    writeU16(rec, base + 2, strms[i].unk)
                    rec[base + 4] = strms[i].volume.toByte()
                    rec[base + 5] = strms[i].priority.toByte()
                    rec[base + 6] = strms[i].players.toByte()
                    // bytes 7-11 reserved = 0
                }
                return rec
            }

            // Compute slot offsets within block
            var off = blockHeaderSize
            val slotOffsets = IntArray(8)
            slotOffsets[SLOT_SSEQ] = off;
            val sseqRec = buildSseqRecord(off); off += sseqRec.size
            slotOffsets[SLOT_SBNK] = off;
            val sbnkRec = buildSbnkRecord(off); off += sbnkRec.size
            slotOffsets[SLOT_SWAR] = off;
            val swarRec = buildSwarRecord(off); off += swarRec.size
            slotOffsets[SLOT_STRM] = off;
            val strmRec = buildStrmRecord(off); off += strmRec.size
            val totalSize = off

            val block = ByteArray(totalSize)
            block[0] = 'I'.code.toByte(); block[1] = 'N'.code.toByte()
            block[2] = 'F'.code.toByte(); block[3] = 'O'.code.toByte()
            writeU32(block, 4, totalSize.toLong())
            for (i in 0 until 8) {
                writeU32(block, 8 + i * 4, slotOffsets[i].toLong())
            }
            sseqRec.copyInto(block, slotOffsets[SLOT_SSEQ])
            sbnkRec.copyInto(block, slotOffsets[SLOT_SBNK])
            swarRec.copyInto(block, slotOffsets[SLOT_SWAR])
            strmRec.copyInto(block, slotOffsets[SLOT_STRM])
            return block
        }

        // -------------------------------------------------------------------------
        // SYMB record reader
        // -------------------------------------------------------------------------

        /**
         * Reads a SYMB record and returns the name for each entry.
         *
         * @param data The full SDAT byte array.
         * @param symbOffset Absolute offset of the SYMB block start within [data].
         * @param recOff Offset of the record relative to the SYMB block start.
         * @return A list of name strings; empty string means unnamed.
         */
        private fun readSymbRecord(data: ByteArray, symbOffset: Int, recOff: Int): List<String> {
            val base = symbOffset + recOff
            val nEntries = u32(data, base).toInt()
            return List(nEntries) { i ->
                val strOff = u32(data, base + 4 + i * 4).toInt()
                if (strOff == 0) {
                    ""
                } else {
                    // Null-terminated string at symbOffset + strOff
                    val start = symbOffset + strOff
                    var end = start
                    while (end < data.size && data[end] != 0.toByte()) end++
                    data.decodeToString(start, end)
                }
            }
        }

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        /**
         * Rounds [n] up to the next multiple of [alignment].
         *
         * @param n The value to align.
         * @param alignment The alignment boundary (must be a power of two).
         * @return The smallest multiple of [alignment] that is ≥ [n].
         */
        private fun alignN(n: Int, alignment: Int): Int = (n + alignment - 1) and -(alignment)

        /**
         * Reads a little-endian unsigned 16-bit integer from [buf] at [off].
         *
         * @param buf The source byte array.
         * @param off The byte offset to read from.
         * @return The decoded value as an [Int] in the range 0..65535.
         */
        private fun u16(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

        /**
         * Reads a little-endian unsigned 32-bit integer from [buf] at [off].
         *
         * @param buf The source byte array.
         * @param off The byte offset to read from.
         * @return The decoded value as a [Long] in the range 0..4294967295.
         */
        private fun u32(buf: ByteArray, off: Int): Long =
            (buf[off].toLong() and 0xFF) or
                    ((buf[off + 1].toLong() and 0xFF) shl 8) or
                    ((buf[off + 2].toLong() and 0xFF) shl 16) or
                    ((buf[off + 3].toLong() and 0xFF) shl 24)

        /**
         * Writes a little-endian unsigned 32-bit integer [v] into [buf] at [off].
         *
         * @param buf The target byte array (must have at least [off]`+4` bytes).
         * @param off The byte offset to write to.
         * @param v The value to encode.
         */
        private fun writeU32(buf: ByteArray, off: Int, v: Long) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = (v.ushr(8) and 0xFF).toByte()
            buf[off + 2] = (v.ushr(16) and 0xFF).toByte()
            buf[off + 3] = (v.ushr(24) and 0xFF).toByte()
        }

        /**
         * Writes a little-endian unsigned 16-bit integer [v] into [buf] at [off].
         *
         * @param buf The target byte array (must have at least [off]`+2` bytes).
         * @param off The byte offset to write to.
         * @param v The value to encode (only the lower 16 bits are used).
         */
        private fun writeU16(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = (v.ushr(8) and 0xFF).toByte()
        }
    }
}
