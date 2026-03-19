package dev.kotlinds


/**
 * Nintendo DS Huffman codec.
 *
 * Supports both the 4-bit (magic `0x24`) and 8-bit (magic `0x28`) variants of the
 * Nintendo DS Huffman compression format as documented in GBATek / CUE references.
 *
 * ## Compressed file layout
 * ```
 * byte  0      : magic  (0x24 = 4-bit, 0x28 = 8-bit)
 * bytes 1-3    : uncompressed size, 24-bit little-endian
 * byte  4      : halfTreeSize  (H) — tree occupies (H+1)*2 bytes
 * bytes 5..5+(H+1)*2-1 : Huffman tree node pairs
 * (padding to next 4-byte boundary)
 * remaining    : compressed data as 32-bit little-endian words, bits consumed MSB-first
 * ```
 *
 * ## Tree node pair format
 * Each node is a pair of two bytes at indices `[2*n, 2*n+1]`:
 * - `leftDesc  = tree[2*n]`
 * - `rightDesc = tree[2*n+1]`
 *
 * For a descriptor byte `d` at node pair index `n`:
 * - bits 5-0  : offset from the **next** pair; child pair index = `n + 1 + (d & 0x3F)`
 * - bit 7 of `leftDesc`  : left child is a leaf
 * - bit 6 of `leftDesc`  : right child is a leaf
 *
 * When the child is a leaf, the symbol value is stored in `tree[childPair * 2]`
 * (first byte of the child pair).
 */
object HuffmanCodec {

    private const val MAGIC_4BIT = 0x24
    private const val MAGIC_8BIT = 0x28

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Decompresses Nintendo DS Huffman-encoded data.
     *
     * Supports both the 4-bit (`0x24`) and 8-bit (`0x28`) variants.
     *
     * @param input Raw compressed byte array, including the 4-byte header.
     * @return Decompressed byte array.
     * @throws IllegalArgumentException if the magic byte is not `0x24` or `0x28`,
     *   or if the input is malformed.
     */
    fun decompress(input: ByteArray): ByteArray {
        require(input.size >= 5) { "Huffman: input too short" }

        val magic = input[0].toInt() and 0xFF
        require(magic == MAGIC_4BIT || magic == MAGIC_8BIT) {
            "Huffman: unknown magic byte 0x${magic.toString(16)}"
        }
        val bitWidth = magic and 0x0F  // 4 or 8

        val uncompSize = readU24(input, 1)

        val halfTreeSize = input[4].toInt() and 0xFF
        val treeBytes = (halfTreeSize + 1) * 2
        val treeStart = 5  // tree data begins here

        require(input.size >= treeStart + treeBytes) { "Huffman: input truncated in tree" }

        // Compressed data starts at the next 4-byte boundary after the tree
        val dataStart = (treeStart + treeBytes + 3) and (-4)
        require(input.size >= dataStart) { "Huffman: input truncated before data" }

        val out = ByteArray(uncompSize)
        var outPos = 0

        // Bit stream state
        var wordBuffer = 0
        var bitsLeft = 0
        var dataPos = dataStart

        fun nextBit(): Int {
            if (bitsLeft == 0) {
                require(dataPos + 3 < input.size) { "Huffman: unexpected end of bitstream" }
                wordBuffer = readU32(input, dataPos).toInt()
                dataPos += 4
                bitsLeft = 32
            }
            bitsLeft--
            return (wordBuffer ushr bitsLeft) and 1
        }

        // For 4-bit mode we accumulate two nibbles to form one output byte
        var nibblePending = false
        var pendingNibble = 0

        while (outPos < uncompSize) {
            // Traverse the tree from the root (pair index 0)
            var nodeIdx = 0
            while (true) {
                val bit = nextBit()
                val leftDesc = input[treeStart + 2 * nodeIdx].toInt() and 0xFF
                val rightDesc = input[treeStart + 2 * nodeIdx + 1].toInt() and 0xFF

                val isLeaf: Boolean
                val childIdx: Int
                if (bit == 0) {
                    // Go left
                    isLeaf = (leftDesc and 0x80) != 0
                    childIdx = nodeIdx + 1 + (leftDesc and 0x3F)
                } else {
                    // Go right
                    isLeaf = (leftDesc and 0x40) != 0
                    childIdx = nodeIdx + 1 + (rightDesc and 0x3F)
                }

                if (isLeaf) {
                    val symbol = input[treeStart + 2 * childIdx].toInt() and 0xFF
                    if (bitWidth == 8) {
                        out[outPos++] = symbol.toByte()
                    } else {
                        // 4-bit: low nibble first, then high nibble (two symbols per output byte)
                        if (!nibblePending) {
                            pendingNibble = symbol and 0x0F
                            nibblePending = true
                        } else {
                            out[outPos++] = (pendingNibble or ((symbol and 0x0F) shl 4)).toByte()
                            nibblePending = false
                        }
                    }
                    break  // restart from root
                } else {
                    nodeIdx = childIdx
                }
            }
        }

        return out
    }

    /**
     * Compresses [input] using 8-bit Nintendo DS Huffman encoding (magic `0x28`).
     *
     * The output is always decompressible by [HuffmanCodec.decompress].  For highly repetitive
     * input the compressed size will be smaller than the input; for already-random
     * or very short data the overhead of the tree may make the output larger.
     *
     * @param input Raw byte array to compress.
     * @return Compressed byte array including the 4-byte header and Huffman tree.
     */
    fun compress(input: ByteArray): ByteArray {
        val uncompSize = input.size

        // --- 1. Count symbol frequencies ---
        val freq = IntArray(256)
        for (b in input) freq[b.toInt() and 0xFF]++

        // --- 2. Build Huffman tree in memory ---
        // Leaves are node indices 0..255; internal nodes start at 256.
        val nodeFreq = IntArray(512)
        val nodeLeft = IntArray(512) { -1 }
        val nodeRight = IntArray(512) { -1 }

        for (i in 0..255) nodeFreq[i] = freq[i]

        // Inline min-heap on node indices keyed by nodeFreq
        val heap = IntArray(512)
        var heapSize = 0

        fun heapPush(node: Int) {
            heap[heapSize] = node
            var i = heapSize++
            while (i > 0) {
                val p = (i - 1) / 2
                if (nodeFreq[heap[p]] > nodeFreq[heap[i]]) {
                    val tmp = heap[p]; heap[p] = heap[i]; heap[i] = tmp
                    i = p
                } else break
            }
        }

        fun heapPop(): Int {
            val top = heap[0]
            heap[0] = heap[--heapSize]
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = 2 * i + 2
                var s = i
                if (l < heapSize && nodeFreq[heap[l]] < nodeFreq[heap[s]]) s = l
                if (r < heapSize && nodeFreq[heap[r]] < nodeFreq[heap[s]]) s = r
                if (s == i) break
                val tmp = heap[i]; heap[i] = heap[s]; heap[s] = tmp
                i = s
            }
            return top
        }

        // Push all symbols present in the input
        val usedSymbols = (0..255).filter { freq[it] > 0 }
        for (s in usedSymbols) heapPush(s)

        // Edge case: only one distinct symbol — add a dummy so the tree has depth >= 1
        if (heapSize < 2) {
            val dummy = if (usedSymbols.isEmpty()) 0 else (usedSymbols[0] + 1) % 256
            nodeFreq[dummy] = 0
            heapPush(dummy)
        }

        var nextInternal = 256
        while (heapSize > 1) {
            val a = heapPop()
            val b = heapPop()
            val n = nextInternal++
            nodeFreq[n] = nodeFreq[a] + nodeFreq[b]
            nodeLeft[n] = a
            nodeRight[n] = b
            heapPush(n)
        }
        val root = heapPop()

        // --- 3. Assign Huffman codes (iterative DFS) ---
        val codeLen = IntArray(256)
        val codeBits = LongArray(256)

        // Explicit stack: Triple(nodeIndex, depth, accumulated bits)
        val dfsStack = ArrayDeque<Triple<Int, Int, Long>>()
        dfsStack.addLast(Triple(root, 0, 0L))
        while (dfsStack.isNotEmpty()) {
            val (node, depth, bits) = dfsStack.removeLast()
            if (node < 256) {
                codeLen[node] = if (depth == 0) 1 else depth
                codeBits[node] = bits
            } else {
                // Push right first so left is processed first (LIFO)
                dfsStack.addLast(Triple(nodeRight[node], depth + 1, (bits shl 1) or 1L))
                dfsStack.addLast(Triple(nodeLeft[node], depth + 1, bits shl 1))
            }
        }

        // --- 4. Encode tree in Nintendo DS binary format ---
        //
        // Layout strategy: BFS over the in-memory tree.  For every internal node at
        // pair index `n` we allocate the NEXT available pair slot for the left child and
        // the one after that for the right child (or in reverse if one of them is also
        // internal and already queued).  This "allocate children as a consecutive block
        // immediately following the current BFS frontier" strategy keeps the 6-bit
        // per-node offset within [0, 63] as long as the total number of symbol pairs
        // reachable within a single step does not exceed 63.
        //
        // Since each internal node has exactly two children, and we allocate both children
        // as a consecutive pair directly after the last allocated slot, the offset from
        // node `n` to its left child is `nextSlot - n - 1` and to its right child is
        // `nextSlot - n`.  For BFS processing order this grows at most by 1 per node,
        // and with up to 255 distinct 8-bit symbols the tree depth stays bounded.
        //
        // DS tree node pair descriptor bytes:
        //   leftDesc  = treeData[2*n]
        //     bit 7: left child is a leaf
        //     bit 6: right child is a leaf
        //     bits 5-0: (left_child_pair_index  - n - 1)
        //   rightDesc = treeData[2*n+1]
        //     bits 5-0: (right_child_pair_index - n - 1)
        //
        // Leaf pairs: treeData[2*leafPairIndex] = symbol byte.

        // We'll do a two-pass BFS:
        //  Pass 1: determine how many pairs we need and assign pair indices.
        //  Pass 2: fill treeData.

        // Pair index assignment:
        //   - internal nodes are queued in BFS order and get pair indices in that order.
        //   - for each internal node at pair `n`, its TWO children get the NEXT two consecutive
        //     pair slots.  If a child is itself internal it will be processed later from the
        //     queue; if it is a leaf it still needs its own pair slot (to store the symbol).
        //
        // This means every child — internal or leaf — is allocated a consecutive pair slot
        // right after the last allocation.  Since children are consecutive, offsets are:
        //   left_offset  = nextSlot - n - 1
        //   right_offset = nextSlot - n      (i.e., left_offset + 1)
        // But we want them relative to the current node (n), so:
        //   left child pair  = slotL
        //   right child pair = slotR = slotL + 1
        //   left_offset  = slotL - n - 1
        //   right_offset = slotR - n - 1 = left_offset + 1

        val pairOf = IntArray(512) { -1 }  // pairOf[nodeIndex] = pair slot
        pairOf[root] = 0
        var nextSlot = 1

        // BFS queue holding internal nodes in order of their pair assignment
        val bfsOrder = mutableListOf<Int>()
        val bfsQ = ArrayDeque<Int>()
        bfsQ.addLast(root)

        while (bfsQ.isNotEmpty()) {
            val n = bfsQ.removeFirst()
            if (n < 256) continue  // leaf — its pair is already assigned, nothing to expand
            bfsOrder.add(n)

            val l = nodeLeft[n]
            val r = nodeRight[n]

            // Allocate two consecutive slots for the two children
            pairOf[l] = nextSlot++
            pairOf[r] = nextSlot++

            // Queue children for further expansion (leaves are no-ops)
            bfsQ.addLast(l)
            bfsQ.addLast(r)
        }

        val totalPairs = nextSlot

        // Post-process: fix any 6-bit offset violations by swapping nodes.
        // The BFS allocation places children far from parents for deep or wide trees.
        // We iteratively swap out-of-range children with closer nodes until all
        // offsets fit in 6 bits (≤ 63).
        run {
            val pairToNode = IntArray(totalPairs) { -1 }
            for (n in pairOf.indices) {
                val p = pairOf[n]; if (p in 0 until totalPairs) pairToNode[p] = n
            }
            // parentOf[nodeIdx] = the internal node that has nodeIdx as a child, or -1 for root
            val parentOf = IntArray(512) { -1 }
            for (n in bfsOrder) {
                parentOf[nodeLeft[n]] = n; parentOf[nodeRight[n]] = n
            }

            var iterations = totalPairs * totalPairs  // generous upper bound
            var anyViolation: Boolean
            do {
                anyViolation = false
                for (parentNode in bfsOrder) {
                    val pp = pairOf[parentNode]
                    for (childNode in listOf(nodeLeft[parentNode], nodeRight[parentNode])) {
                        val cp = pairOf[childNode]
                        if (cp - pp - 1 <= 63) continue          // already fine

                        // Find the nearest swap target within [pp+1, pp+63]
                        var fixed = false
                        for (targetPair in pp + 1..minOf(pp + 63, totalPairs - 1)) {
                            val displaced = pairToNode[targetPair]
                            if (displaced < 0 || displaced == parentNode) continue

                            // displaced's parent must still reach it at the new (farther) position
                            val dp = parentOf[displaced]
                            if (dp >= 0 && cp - pairOf[dp] - 1 > 63) continue

                            // If displaced is an internal node, its children must remain
                            // reachable from its new position (cp > targetPair, so offset shrinks)
                            if (displaced >= 256) {
                                val lc = pairOf[nodeLeft[displaced]]
                                val rc = pairOf[nodeRight[displaced]]
                                if (lc <= cp || rc <= cp) continue  // children would precede parent
                            }

                            // Perform the swap
                            pairOf[childNode] = targetPair
                            pairOf[displaced] = cp
                            pairToNode[targetPair] = childNode
                            pairToNode[cp] = displaced
                            anyViolation = true
                            fixed = true
                            break
                        }
                        require(fixed) {
                            "HuffmanCodec: DS 6-bit tree offset constraint unsatisfiable. " +
                                    "Compress with fewer distinct byte values (current: ${usedSymbols.size})."
                        }
                    }
                }
                iterations--
            } while (anyViolation && iterations > 0)
        }

        val treeData = ByteArray(totalPairs * 2)

        // Fill internal node descriptors
        for (n in bfsOrder) {
            val pIdx = pairOf[n]
            val l = nodeLeft[n]
            val r = nodeRight[n]
            val slotL = pairOf[l]
            val slotR = pairOf[r]

            val leftOffset = slotL - pIdx - 1
            val rightOffset = slotR - pIdx - 1

            var leftDesc = leftOffset
            var rightDesc = rightOffset
            if (l < 256) leftDesc = leftDesc or 0x80   // bit 7: left child is a leaf
            if (r < 256) leftDesc = leftDesc or 0x40   // bit 6: right child is a leaf

            treeData[2 * pIdx] = leftDesc.toByte()
            treeData[2 * pIdx + 1] = rightDesc.toByte()
        }

        // Fill leaf symbol pairs — first byte of the leaf's pair holds its symbol
        for (sym in 0..255) {
            val idx = pairOf[sym]
            if (idx != -1) {
                treeData[2 * idx] = sym.toByte()
                // second byte is unused (zero)
            }
        }

        val halfTreeSize = totalPairs - 1
        require(halfTreeSize <= 255) { "DS Huffman: tree too large ($totalPairs pairs)" }

        // --- 5. Encode data bits ---
        // Bits are packed MSB-first into 32-bit little-endian words.
        // Upper bound on words: each symbol needs at most 32 bits → at most uncompSize u32s.
        val maxWords = uncompSize + 1  // +1 for partial last word
        val words = IntArray(maxWords)
        var wordIdx = 0
        var bitPos = 31  // we fill from bit 31 down to 0

        fun emitBit(bit: Int) {
            if (bitPos < 0) {
                wordIdx++
                bitPos = 31
            }
            if (bit != 0) words[wordIdx] = words[wordIdx] or (1 shl bitPos)
            bitPos--
        }

        for (b in input) {
            val sym = b.toInt() and 0xFF
            val len = codeLen[sym]
            val bits = codeBits[sym]
            for (shift in len - 1 downTo 0) {
                emitBit(((bits ushr shift) and 1L).toInt())
            }
        }
        val totalWords = if (bitPos == 31) wordIdx else wordIdx + 1

        // --- 6. Assemble output ---
        // [header 4 bytes] [halfTreeSize 1 byte] [treeData] [0-pad to 4-byte boundary] [u32 words]
        val headerAndTreeLen = 5 + treeData.size
        val dataStart = (headerAndTreeLen + 3) and (-4)
        val totalSize = dataStart + totalWords * 4

        val out = ByteArray(totalSize)

        out[0] = MAGIC_8BIT.toByte()
        writeU24(out, 1, uncompSize.toLong())
        out[4] = halfTreeSize.toByte()
        treeData.copyInto(out, 5)
        // padding bytes are zero by default

        var outOff = dataStart
        for (wi in 0 until totalWords) {
            writeU32(out, outOff, words[wi].toLong() and 0xFFFFFFFFL)
            outOff += 4
        }

        return out
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun readU24(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
                ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                ((buf[offset + 2].toInt() and 0xFF) shl 16)

    private fun readU32(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFF) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                ((buf[offset + 2].toLong() and 0xFF) shl 16) or
                ((buf[offset + 3].toLong() and 0xFF) shl 24)

    private fun writeU24(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value ushr 8 and 0xFF).toByte()
        buf[offset + 2] = (value ushr 16 and 0xFF).toByte()
    }

    private fun writeU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value ushr 8 and 0xFF).toByte()
        buf[offset + 2] = (value ushr 16 and 0xFF).toByte()
        buf[offset + 3] = (value ushr 24 and 0xFF).toByte()
    }
}
