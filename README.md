# kotlin-nds

Kotlin Multiplatform utilities to work with .nds files

[![License](https://img.shields.io/github/license/nathanfallet/kotlin-nds)](LICENSE)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.nathanfallet.nds/kotlin-nds)](https://klibs.io/project/nathanfallet/kotlin-nds)
[![Issues](https://img.shields.io/github/issues/nathanfallet/kotlin-nds)]()
[![Pull Requests](https://img.shields.io/github/issues-pr/nathanfallet/kotlin-nds)]()

## Features

- **Parse & repack NDS ROMs** — Read ROM headers, ARM binaries, overlays, banner and all filesystem files; serialize
  back with automatic offset and CRC recalculation.
- **NARC archive support** — Unpack and repack NARC containers, with both anonymous (index-based) and named (path-based)
  file access.
- **SDAT archive support** — Unpack and repack SDAT sound archives; decode STRM streams and SWAR wave archives to
  standard WAV files (PCM8, PCM16, IMA-ADPCM); convert SSEQ sequences to standard MIDI files.
- **Compression codecs** — BLZ, LZSS/LZ10, LZ11, RLE, and Huffman (4-bit & 8-bit). Auto-detection dispatches to the
  right codec from the magic byte.
- **Multiplatform** — Runs on JVM, JavaScript (Node.js & browser), and Native (macOS, Linux, iOS, Windows, …).

## Installation

```kotlin
dependencies {
    implementation("me.nathanfallet.nds:kotlin-nds:1.2.0")
}
```

## Usage

### NDS ROMs

```kotlin
val rom = NdsRom.parse(File("game.nds").readBytes())

println(rom.gameTitle)  // e.g. "MY GAME"
println(rom.gameCode)   // e.g. "ABCD"
println(rom.files.keys) // e.g. [a/0/0/0, a/0/0/1, ...]

// Replace files (returns a new NdsRom — original is unchanged)
val modifiedRom = rom.withFile("a/0/3/2", newFileBytes)
    .withFiles(mapOf("a/0/3/3" to anotherFileBytes))
    .withArm9(File("arm9_patched.bin").readBytes())

File("game_modified.nds").writeBytes(modifiedRom.pack())
```

### Overlays

ARM9 and ARM7 overlays are loaded at runtime by the DS firmware. They are accessed by index, matching the order in the
ROM's overlay table:

```kotlin
// Read overlays
val ovl0: ByteArray = rom.arm9Overlays[0]
val ovl1: ByteArray = rom.arm7Overlays[0]

// Replace an overlay (returns a new NdsRom — original is unchanged)
val modifiedRom = rom.withArm9Overlay(0, patchedOverlayBytes)
    .withArm7Overlay(0, patchedArm7OverlayBytes)

File("game_modified.nds").writeBytes(modifiedRom.pack())
```

Overlay files are typically BLZ-compressed — use `BlzCodec` to decompress/recompress them before patching.

### NARC Archives

NARC files bundle multiple assets inside a single ROM file. Two modes are supported:

```kotlin
// Anonymous (index-based) — common in most ROMs
val narcBytes = rom.files["a/0/3/2"]!!
val files: List<ByteArray> = NarcArchive.unpack(narcBytes)
val repacked: ByteArray = NarcArchive.pack(files)

// Named (path-based) — for NARCs that carry a file name table
val named: Map<String, ByteArray> = NarcArchive.unpackNamed(narcBytes)
val repackedNamed: ByteArray = NarcArchive.packNamed(
    mapOf(
        "sprites/player.bin" to playerData,
        "sprites/enemy.bin" to enemyData,
        "data/map.bin" to mapData,
    )
)
```

`unpackNamed` falls back to index keys (`"0"`, `"1"`, …) for anonymous NARCs, so it is safe to use on either type.

### SDAT Archives

SDAT (Sound Data Archive) bundles all sound assets for a DS game: sequences (SSEQ), banks (SBNK),
wave archives (SWAR), and streams (STRM). Each file carries metadata read directly from the INFO block.

```kotlin
val sdatBytes = rom.files["sound/sound_data.sdat"]!!
val archive = SdatArchive.unpack(sdatBytes)

// Access by index
val seq: SdatSseqFile = archive.sequences[0]
val bank: SdatSbnkFile = archive.banks[0]
val war: SdatSwarFile = archive.waveArchives[0]
val strm: SdatStrmFile = archive.streams[0]

println(seq.name)            // e.g. "SEQ_TITLE"
println(seq.bank)            // SBNK index this sequence uses
println(seq.volume)          // playback volume (0–127)
println(seq.channelPriority) // hardware channel priority
println(seq.playerPriority)  // sequence player priority
println(seq.players)         // allowed players bitmask

println(bank.wars)           // List<Int> of SWAR indices, -1 = unused slot

println(strm.volume)         // stream playback volume
println(strm.priority)       // stream priority
println(strm.players)        // stream player bitmask

// Look up by symbolic name (from the SYMB block)
val title: SdatSseqFile? = archive.sequenceByName("SEQ_TITLE")
val drums: SdatSbnkFile? = archive.bankByName("BANK_DRUMS")
val sfx: SdatSwarFile? = archive.waveArchiveByName("WAVE_SFX")
val bgm: SdatStrmFile? = archive.streamByName("STRM_BGM")

// Repack (SYMB block is only written when at least one name differs from the fallback)
val repacked: ByteArray = SdatArchive.pack(archive)
```

The `unk` field on every entry type (`SdatSseqFile.unk`, `SdatSbnkFile.unk`, `SdatSwarFile.unk`,
`SdatStrmFile.unk`) preserves the unknown u16 from the INFO struct so that round-trips are lossless.

#### Converting to audio

Streams and wave archives can be decoded to standard WAV files (16-bit signed PCM). All three NDS
wave encodings are supported: PCM8, PCM16, and IMA-ADPCM. Sequences can be exported to standard
MIDI files.

```kotlin
// STRM → single WAV (may be stereo)
val wav: ByteArray = archive.streams[0].toWav()
File("bgm.wav").writeBytes(wav)

// SWAR → one WAV per instrument sample (always mono)
val wavs: List<ByteArray> = archive.waveArchives[0].toWavList()
wavs.forEachIndexed { i, w -> File("sample_$i.wav").writeBytes(w) }

// SSEQ → standard MIDI file (uses General MIDI instruments, not game sounds)
val mid: ByteArray = archive.sequences[0].toMidi()
File("bgm.mid").writeBytes(mid)
```

### Compression

Most DS files use one of several compression formats identified by a magic byte. Use `NdsCompression` to auto-detect and
decompress:

```kotlin
val raw: ByteArray = NdsCompression.decompress(compressedBytes)

// Check before decompressing
if (NdsCompression.isCompressed(data)) { /* decompress */
}
```

Each codec can also be used directly:

| Codec          | Magic           | Use                            |
|----------------|-----------------|--------------------------------|
| `LzssCodec`    | `0x10`          | LZ10/LZSS — most common format |
| `Lz11Codec`    | `0x11`          | LZ11 — extended lengths        |
| `HuffmanCodec` | `0x24` / `0x28` | Huffman 4-bit / 8-bit          |
| `RleCodec`     | `0x30`          | Run-length encoding            |
| `BlzCodec`     | *(footer)*      | Bottom-LZ — ARM9 and overlays  |

```kotlin
// All codecs share the same interface
val compressed: ByteArray = LzssCodec.compress(data)
val decompressed: ByteArray = LzssCodec.decompress(compressed)

// BLZ: arm9=true validates the secure area and skips the first 0x4000 bytes
val compressedArm9: ByteArray = BlzCodec.compress(arm9Bytes, arm9 = true)
val decompressedArm9: ByteArray = BlzCodec.decompress(compressedArm9)
```

> **Note:** `NdsCompression` does not auto-detect BLZ (it has no magic byte). Use `BlzCodec` directly for ARM9 binaries
> and overlay files.

## Libraries & tools using kotlin-nds

- [pokemon-map-randomizer](https://github.com/nathanfallet/pokemon-map-randomizer): A Kotlin/Compose Multiplatform port
  of hgss-map-randomizer, the original C++ map randomizer for Pokémon HeartGold, SoulSilver, Black 2, and White 2.

If you are using kotlin-nds in your project/library, please let us know by opening a pull request to add it to this
list!
