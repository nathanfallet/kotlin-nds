package me.nathanfallet.nds

import me.nathanfallet.nds.NarcArchive.unpack
import me.nathanfallet.nds.NarcArchive.unpackNamed


/**
 * Reads and writes Nintendo DS NARC container files.
 *
 * NARC binary layout (all fields little-endian):
 *
 *   [Main header – 16 bytes]
 *     0x00  magic      "NARC"
 *     0x04  BOM        FE FF
 *     0x06  version    00 01
 *     0x08  file size  (u32)
 *     0x0C  hdr size   10 00
 *     0x0E  sections   03 00
 *
 *   [BTAF – file allocation table block]
 *     0x00  magic      "BTAF"
 *     0x04  size       (u32, includes this 8-byte header)
 *     0x08  num files  (u32)
 *     0x0C  entries    numFiles × { start u32, end u32 }   (relative to GMIF data start)
 *
 *   [BTNF – file name table block]
 *     0x00  magic      "BTNF"
 *     0x04  size       (u32)
 *     0x08  FNT data   (minimal single-root entry for anonymous NARCs)
 *
 *   [GMIF – file image block]
 *     0x00  magic      "GMIF"
 *     0x04  size       (u32, includes this 8-byte header)
 *     0x08  file data  (concatenated)
 */
object NarcArchive {

    // -------------------------------------------------------------------------
    // Unpack
    // -------------------------------------------------------------------------

    /**
     * Extracts all files from a NARC container.
     *
     * @param data The raw NARC bytes.
     * @return The files in their original order; index equals the file ID.
     * @throws IllegalArgumentException if the data is not a valid NARC container.
     */
    fun unpack(data: ByteArray): List<ByteArray> {
        require(data.size >= 16) { "NARC too small" }
        require(data.decodeToString(0, 4) == "NARC") { "Not a NARC file" }

        // Main header
        val headerSize = u16(data, 0x0C)
        check(headerSize == 16) { "Unexpected NARC header size: $headerSize" }

        // Find sections
        var pos = headerSize
        var btafData: ByteArray? = null
        var gmifDataStart = -1

        repeat(3) {
            val magic = data.decodeToString(pos, pos + 4)
            val size = u32(data, pos + 4).toInt()
            when (magic) {
                "BTAF" -> btafData = data.copyOfRange(pos + 8, pos + size)
                "GMIF" -> gmifDataStart = pos + 8
            }
            pos += size
        }

        val fat = checkNotNull(btafData) { "BTAF section not found" }
        check(gmifDataStart >= 0) { "GMIF section not found" }

        val numFiles = u32(fat, 0).toInt()
        return List(numFiles) { i ->
            val start = u32(fat, 4 + i * 8).toInt()
            val end = u32(fat, 4 + i * 8 + 4).toInt()
            if (start >= end) ByteArray(0)
            else data.copyOfRange(gmifDataStart + start, gmifDataStart + end)
        }
    }

    // -------------------------------------------------------------------------
    // Pack
    // -------------------------------------------------------------------------

    /**
     * Packs a list of files into an anonymous NARC container.
     * Files are stored in the given order and accessed by index (no name table).
     *
     * @param files The file data to pack, in file-ID order.
     * @return A valid NARC byte array.
     */
    fun pack(files: List<ByteArray>): ByteArray {
        val numFiles = files.size

        // Build GMIF data (files concatenated, each 4-byte aligned)
        val gmifData = buildGmifData(files)

        // Build FAT entries (offsets into GMIF data)
        val fatEntries = ByteArray(numFiles * 8)
        var offset = 0
        for ((i, file) in files.withIndex()) {
            writeU32(fatEntries, i * 8, offset.toLong())
            writeU32(fatEntries, i * 8 + 4, (offset + file.size).toLong())
            offset += align4(file.size)
        }

        // BTAF section
        val btafSize = 8 + 4 + fatEntries.size
        val btaf = ByteArray(btafSize)
        btaf[0] = 'B'.code.toByte(); btaf[1] = 'T'.code.toByte()
        btaf[2] = 'A'.code.toByte(); btaf[3] = 'F'.code.toByte()
        writeU32(btaf, 4, btafSize.toLong())
        writeU32(btaf, 8, numFiles.toLong())
        fatEntries.copyInto(btaf, 12)

        // BTNF section — minimal anonymous FNT
        val fnt = buildMinimalFnt()
        val btnfSize = 8 + fnt.size
        val btnf = ByteArray(btnfSize)
        btnf[0] = 'B'.code.toByte(); btnf[1] = 'T'.code.toByte()
        btnf[2] = 'N'.code.toByte(); btnf[3] = 'F'.code.toByte()
        writeU32(btnf, 4, btnfSize.toLong())
        fnt.copyInto(btnf, 8)

        // GMIF section
        val gmifSize = 8 + gmifData.size
        val gmif = ByteArray(gmifSize)
        gmif[0] = 'G'.code.toByte(); gmif[1] = 'M'.code.toByte()
        gmif[2] = 'I'.code.toByte(); gmif[3] = 'F'.code.toByte()
        writeU32(gmif, 4, gmifSize.toLong())
        gmifData.copyInto(gmif, 8)

        // Main header
        val totalSize = 16 + btafSize + btnfSize + gmifSize
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'A'.code.toByte()
        header[2] = 'R'.code.toByte(); header[3] = 'C'.code.toByte()
        header[4] = 0xFE.toByte(); header[5] = 0xFF.toByte()   // BOM
        header[6] = 0x00; header[7] = 0x01             // version
        writeU32(header, 8, totalSize.toLong())
        header[12] = 0x10; header[13] = 0x00            // header size = 16
        header[14] = 0x03; header[15] = 0x00            // 3 sections

        // Assemble
        val out = ByteArray(totalSize)
        var cur = 0
        for (part in listOf(header, btaf, btnf, gmif)) {
            part.copyInto(out, cur); cur += part.size
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Named NARC — unpack
    // -------------------------------------------------------------------------

    /**
     * Unpacks a NARC into a map of path → file data.
     *
     * For anonymous NARCs (minimal FNT) the keys are the decimal file indices
     * (`"0"`, `"1"`, …). For named NARCs the keys are slash-separated paths such
     * as `"sprites/player.ncgr"`.
     *
     * @param data The raw NARC bytes.
     * @return A map from path (or index string) to file contents.
     * @throws IllegalArgumentException if the data is not a valid NARC container.
     */
    fun unpackNamed(data: ByteArray): Map<String, ByteArray> {
        require(data.size >= 16) { "NARC too small" }
        require(data.decodeToString(0, 4) == "NARC") { "Not a NARC file" }

        val headerSize = u16(data, 0x0C)
        var pos = headerSize

        var btafData: ByteArray? = null
        var btnfData: ByteArray? = null
        var gmifDataStart = -1

        repeat(3) {
            val magic = data.decodeToString(pos, pos + 4)
            val size = u32(data, pos + 4).toInt()
            when (magic) {
                "BTAF" -> btafData = data.copyOfRange(pos + 8, pos + size)
                "BTNF" -> btnfData = data.copyOfRange(pos + 8, pos + size)
                "GMIF" -> gmifDataStart = pos + 8
            }
            pos += size
        }

        val fat = checkNotNull(btafData) { "BTAF section not found" }
        val fnt = checkNotNull(btnfData) { "BTNF section not found" }
        check(gmifDataStart >= 0) { "GMIF section not found" }

        val numFiles = u32(fat, 0).toInt()
        val rawFiles = List(numFiles) { i ->
            val start = u32(fat, 4 + i * 8).toInt()
            val end = u32(fat, 4 + i * 8 + 4).toInt()
            if (start >= end) ByteArray(0)
            else data.copyOfRange(gmifDataStart + start, gmifDataStart + end)
        }

        // Detect anonymous NARC: subtable starts with 0x00 terminator immediately
        val rootSubtableOff = u32(fnt, 0).toInt()
        val isAnonymous = fnt.size <= rootSubtableOff || fnt[rootSubtableOff] == 0x00.toByte()
        if (isAnonymous) {
            return rawFiles.mapIndexed { i, bytes -> i.toString() to bytes }.toMap()
        }

        // Parse FNT — build path for each file ID
        val filePaths = Array(numFiles) { "" }
        parseFnt(fnt, numFiles, filePaths)

        return rawFiles.mapIndexed { i, bytes -> filePaths[i] to bytes }.toMap()
    }

    /**
     * Walks the FNT and populates [filePaths] with the full path for each file ID.
     *
     * @param fnt Raw FNT bytes from the BTNF section.
     * @param numFiles Total number of files (length of [filePaths]).
     * @param filePaths Output array indexed by file ID; each element is set to the resolved path.
     */
    private fun parseFnt(fnt: ByteArray, numFiles: Int, filePaths: Array<String>) {
        // Read number of directories from root entry (bytes 6-7)
        val numDirs = u16(fnt, 6)

        // Each directory entry is 8 bytes: subtableOffset(u32), firstFileId(u16), parentOrTotal(u16)
        // Build a list of (subtableOffset, firstFileId, parentDirIndex)
        data class DirEntry(val subtableOff: Int, val firstFileId: Int, val parentIdx: Int)

        val dirs = Array(numDirs) { i ->
            val base = i * 8
            DirEntry(
                subtableOff = u32(fnt, base).toInt(),
                firstFileId = u16(fnt, base + 4),
                parentIdx = if (i == 0) -1 else (u16(fnt, base + 6) - 0xF000)
            )
        }

        // Build dir paths (root = "")
        val dirPaths = Array(numDirs) { "" }
        // BFS order — root's children will always have lower-indexed parents already resolved
        for (i in 1 until numDirs) {
            val parent = dirs[i].parentIdx
            val parentPath = dirPaths[parent]
            // We need the name of dir i from parent's subtable
            // Scan the parent's subtable for the entry whose dirId == 0xF000 + i
            val targetId = 0xF000 + i
            var off = dirs[parent].subtableOff
            outer@ while (off < fnt.size) {
                val b = fnt[off++].toInt() and 0xFF
                if (b == 0x00) break
                val nameLen: Int
                val isDir: Boolean
                if (b <= 0x7F) {
                    nameLen = b; isDir = false
                } else {
                    nameLen = b - 0x80; isDir = true
                }
                val name = fnt.decodeToString(off, off + nameLen)
                off += nameLen
                if (isDir) {
                    val dirId = u16(fnt, off)
                    off += 2
                    if (dirId == targetId) {
                        dirPaths[i] = if (parentPath.isEmpty()) name else "$parentPath/$name"
                        break@outer
                    }
                }
            }
        }

        // Now walk each directory's subtable and assign paths to files
        for (i in 0 until numDirs) {
            val dirPath = dirPaths[i]
            var fileId = dirs[i].firstFileId
            var off = dirs[i].subtableOff
            while (off < fnt.size) {
                val b = fnt[off++].toInt() and 0xFF
                if (b == 0x00) break
                if (b <= 0x7F) {
                    // File entry
                    val name = fnt.decodeToString(off, off + b)
                    off += b
                    if (fileId < filePaths.size) {
                        filePaths[fileId] = if (dirPath.isEmpty()) name else "$dirPath/$name"
                        fileId++
                    }
                } else {
                    // Directory entry — skip name + 2-byte dir ID
                    off += (b - 0x80) + 2
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Named NARC — pack
    // -------------------------------------------------------------------------

    /**
     * Packs a named map of files into a NARC container with a full file name table (FNT).
     *
     * Keys are slash-separated paths (e.g. `"sprites/player.bin"`). Files within the same
     * directory are stored in sorted order. The resulting NARC can be unpacked with either
     * [unpack] (index-based) or [unpackNamed] (path-based).
     *
     * @param files Map of slash-separated paths to file contents.
     * @return A valid NARC byte array with a populated FNT.
     */
    fun packNamed(files: Map<String, ByteArray>): ByteArray {
        if (files.isEmpty()) return pack(emptyList())

        // Build directory tree
        // dirChildren: dirIndex → sorted list of (name, isDir, fileIdOrDirIdx)
        data class Entry(val name: String, val isDir: Boolean, val id: Int)

        val dirNames = mutableListOf("")           // index 0 = root ""
        val dirParent = mutableListOf(-1)
        val dirEntries = mutableListOf<MutableList<Entry>>(mutableListOf())

        // Sort paths so directory parents are created before children
        val sortedPaths = files.keys.sorted()
        val fileOrder = mutableListOf<String>()     // file ID order

        fun ensureDir(segments: List<String>): Int {
            var current = 0
            for (seg in segments) {
                val existing = dirEntries[current].indexOfFirst { it.isDir && it.name == seg }
                if (existing >= 0) {
                    current = dirEntries[current][existing].id
                } else {
                    val newIdx = dirNames.size
                    dirNames.add(seg)
                    dirParent.add(current)
                    dirEntries.add(mutableListOf())
                    dirEntries[current].add(Entry(seg, true, newIdx))
                    current = newIdx
                }
            }
            return current
        }

        for (path in sortedPaths) {
            val parts = path.split("/")
            val dirIdx = ensureDir(parts.dropLast(1))
            val fileName = parts.last()
            val fileId = fileOrder.size
            fileOrder.add(path)
            dirEntries[dirIdx].add(Entry(fileName, false, fileId))
        }

        // Sort each directory's entries: dirs first (by name), then files (by name)
        for (entries in dirEntries) {
            entries.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }

        // Rebuild fileId assignment in the sorted order
        val fileIdMap = mutableMapOf<String, Int>()
        var nextFileId = 0
        fun assignIds(dirIdx: Int) {
            for (e in dirEntries[dirIdx]) {
                if (!e.isDir) {
                    // find path
                    val dirPath = buildDirPath(dirIdx, dirNames, dirParent)
                    val fullPath = if (dirPath.isEmpty()) e.name else "$dirPath/${e.name}"
                    fileIdMap[fullPath] = nextFileId++
                }
            }
            for (e in dirEntries[dirIdx]) {
                if (e.isDir) assignIds(e.id)
            }
        }
        assignIds(0)

        // Ordered file list for GMIF
        val orderedFiles = Array<ByteArray>(files.size) { ByteArray(0) }
        for ((path, bytes) in files) {
            val id = fileIdMap[path] ?: continue
            orderedFiles[id] = bytes
        }

        // Build FNT
        val numDirs = dirNames.size
        val dirFirstFileId = IntArray(numDirs)
        // Compute firstFileId per directory
        var fid = 0
        fun computeFirstFileIds(dirIdx: Int) {
            dirFirstFileId[dirIdx] = fid
            for (e in dirEntries[dirIdx]) {
                if (!e.isDir) fid++
            }
            for (e in dirEntries[dirIdx]) {
                if (e.isDir) computeFirstFileIds(e.id)
            }
        }
        computeFirstFileIds(0)

        // Build subtables
        val subtables = Array(numDirs) { ByteArray(0) }
        for (i in 0 until numDirs) {
            val buf = mutableListOf<Byte>()
            for (e in dirEntries[i]) {
                if (!e.isDir) {
                    val nameBytes = e.name.encodeToByteArray()
                    buf.add(nameBytes.size.toByte())
                    buf.addAll(nameBytes.toList())
                } else {
                    val nameBytes = e.name.encodeToByteArray()
                    buf.add((0x80 + nameBytes.size).toByte())
                    buf.addAll(nameBytes.toList())
                    val dirId = 0xF000 + e.id
                    buf.add((dirId and 0xFF).toByte())
                    buf.add((dirId ushr 8).toByte())
                }
            }
            buf.add(0x00)  // end of subtable
            // Pad to 4-byte alignment
            while (buf.size % 4 != 0) buf.add(0xFF.toByte())
            subtables[i] = buf.toByteArray()
        }

        // Directory table: numDirs × 8 bytes
        val dirTableSize = numDirs * 8
        // Compute subtable offsets (from FNT start)
        val subtableOffsets = IntArray(numDirs)
        var off = dirTableSize
        for (i in 0 until numDirs) {
            subtableOffsets[i] = off
            off += subtables[i].size
        }
        val fntSize = off
        // Pad fntSize to 4-byte boundary
        val fntPadded = align4(fntSize)

        val fnt = ByteArray(fntPadded) { 0xFF.toByte() }
        // Write directory table
        for (i in 0 until numDirs) {
            val base = i * 8
            writeU32(fnt, base, subtableOffsets[i].toLong())
            fnt[base + 4] = (dirFirstFileId[i] and 0xFF).toByte()
            fnt[base + 5] = (dirFirstFileId[i] ushr 8).toByte()
            val util = if (i == 0) numDirs else (0xF000 + dirParent[i])
            fnt[base + 6] = (util and 0xFF).toByte()
            fnt[base + 7] = (util ushr 8).toByte()
        }
        // Write subtables
        for (i in 0 until numDirs) {
            subtables[i].copyInto(fnt, subtableOffsets[i])
        }

        return assembleNarc(orderedFiles.toList(), fnt)
    }

    private fun buildDirPath(dirIdx: Int, dirNames: List<String>, dirParent: List<Int>): String {
        if (dirIdx == 0) return ""
        val parent = buildDirPath(dirParent[dirIdx], dirNames, dirParent)
        return if (parent.isEmpty()) dirNames[dirIdx] else "$parent/${dirNames[dirIdx]}"
    }

    /**
     * Assembles a complete NARC from an ordered file list and a pre-built FNT byte array.
     *
     * @param files Ordered file data (index = file ID).
     * @param fnt Pre-built FNT bytes to embed in the BTNF section.
     * @return A valid NARC byte array.
     */
    private fun assembleNarc(files: List<ByteArray>, fnt: ByteArray): ByteArray {
        val numFiles = files.size
        val gmifData = buildGmifData(files)

        val fatEntries = ByteArray(numFiles * 8)
        var offset = 0
        for ((i, file) in files.withIndex()) {
            writeU32(fatEntries, i * 8, offset.toLong())
            writeU32(fatEntries, i * 8 + 4, (offset + file.size).toLong())
            offset += align4(file.size)
        }

        val btafSize = 8 + 4 + fatEntries.size
        val btaf = ByteArray(btafSize).also {
            it[0] = 'B'.code.toByte(); it[1] = 'T'.code.toByte()
            it[2] = 'A'.code.toByte(); it[3] = 'F'.code.toByte()
            writeU32(it, 4, btafSize.toLong())
            writeU32(it, 8, numFiles.toLong())
            fatEntries.copyInto(it, 12)
        }

        val btnfSize = 8 + fnt.size
        val btnf = ByteArray(btnfSize).also {
            it[0] = 'B'.code.toByte(); it[1] = 'T'.code.toByte()
            it[2] = 'N'.code.toByte(); it[3] = 'F'.code.toByte()
            writeU32(it, 4, btnfSize.toLong())
            fnt.copyInto(it, 8)
        }

        val gmifSize = 8 + gmifData.size
        val gmif = ByteArray(gmifSize).also {
            it[0] = 'G'.code.toByte(); it[1] = 'M'.code.toByte()
            it[2] = 'I'.code.toByte(); it[3] = 'F'.code.toByte()
            writeU32(it, 4, gmifSize.toLong())
            gmifData.copyInto(it, 8)
        }

        val totalSize = 16 + btafSize + btnfSize + gmifSize
        val header = ByteArray(16).also {
            it[0] = 'N'.code.toByte(); it[1] = 'A'.code.toByte()
            it[2] = 'R'.code.toByte(); it[3] = 'C'.code.toByte()
            it[4] = 0xFE.toByte(); it[5] = 0xFF.toByte()
            it[6] = 0x00; it[7] = 0x01
            writeU32(it, 8, totalSize.toLong())
            it[12] = 0x10; it[13] = 0x00
            it[14] = 0x03; it[15] = 0x00
        }

        val out = ByteArray(totalSize)
        var cur = 0
        for (part in listOf(header, btaf, btnf, gmif)) {
            part.copyInto(out, cur); cur += part.size
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Concatenates file data, padding each entry to a 4-byte boundary.
     *
     * @param files The files to concatenate.
     * @return A single byte array with all file data, each entry 4-byte aligned.
     */
    private fun buildGmifData(files: List<ByteArray>): ByteArray {
        val totalSize = files.sumOf { align4(it.size) }
        val buf = ByteArray(totalSize)
        var pos = 0
        for (file in files) {
            file.copyInto(buf, pos)
            pos += align4(file.size)
        }
        return buf
    }

    /**
     * Returns a minimal 12-byte FNT for an anonymous (index-only) NARC:
     *   - 8-byte root directory entry pointing to a single-byte subtable
     *   - 1-byte end-of-subtable marker (0x00)
     *   - 3 bytes padding to maintain 4-byte alignment
     */
    private fun buildMinimalFnt(): ByteArray {
        val fnt = ByteArray(12)
        // Root entry: subtable offset = 8 (after the 8-byte root entry), firstFileId = 0, totalDirs = 1
        writeU32(fnt, 0, 8L)     // subtable offset (from FNT start)
        fnt[4] = 0x00; fnt[5] = 0x00  // first file ID = 0
        fnt[6] = 0x01; fnt[7] = 0x00  // total directories = 1
        // subtable: just end-of-subtable marker
        fnt[8] = 0x00
        // fnt[9..11] = 0xFF padding
        fnt[9] = 0xFF.toByte(); fnt[10] = 0xFF.toByte(); fnt[11] = 0xFF.toByte()
        return fnt
    }

    private fun align4(n: Int) = (n + 3) and -4

    private fun u16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)

    private fun writeU32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = (v.ushr(8) and 0xFF).toByte()
        buf[off + 2] = (v.ushr(16) and 0xFF).toByte()
        buf[off + 3] = (v.ushr(24) and 0xFF).toByte()
    }
}
