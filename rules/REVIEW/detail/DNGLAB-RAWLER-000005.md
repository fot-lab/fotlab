# RAW decode cost is set by container and encoding, not by vendor — CR3 is parallel, CR2 and lossless NEF are not

- ID: DNGLAB-RAWLER-000005
- Status: Observation
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/REVIEW/detail/DNGLAB-RAWLER-000001.md` (preview is an unprocessed full-sensor dump), `rules/REVIEW/detail/DNGLAB-RAWLER-000002.md` (do not rewrite rawler — SIMD + rayon are part of why), `rules/REVIEW/detail/FOTLAB-RAWLER-000004.md` (decode-once / develop-reuse across the FFI), `rules/STRUCT/detail/FOTLAB-STUDIO-000001.md` (native media pipeline), `rules/ACTION.md` (Emulator Smoke Test — `RawRoutingTest` decode budget)

## Background & Goal

While verifying that the app really routes camera RAW through rawler rather than falling back to a
Coil preview (`RawRoutingTest`, `rules/ACTION.md` §Test Strategy), the question came up why CR3
appears to load dramatically faster than CR2. The answer turned out to be two *different* effects
that are easy to conflate, and one widely-held assumption that is simply wrong:

1. CR3 is fast to **load/identify** because of its container, not because of its codec.
2. CR3 is fast to **decode** because CRX is block-partitioned and therefore parallelisable.
3. **Lossless NEF is *not* parallel**, contrary to the natural assumption that "NEF is a TIFF with
   strips, so it parallelises". It does not.

This item records both mechanisms, the exact upstream code that implements them, and the general
rule that predicts which formats can use more than one core. The goal is that nobody spends time
trying to "add threads" to a decoder that cannot benefit, and that the decode budget in the smoke
test is understood as single-core-bound for CR2 and NEF.

Upstream is read-only (`external/dnglab`); this item records how to work with it and proposes no
change to its source.

## Finding

### 1. CR3 loads fast because `mdat` is never read

A CR3 is an **ISO BMFF** container (the MP4/HEIF family): `ftyp`, `moov`, `mdat`, plus vendor boxes
and a CR3 XPACKET uuid box. Canon puts *all* metadata in `moov` and all pixels in `mdat`.

The decisive three lines are `external/dnglab/rawler/src/formats/bmff/mdat.rs:18-24`:

```rust
impl<R: Read + Seek> ReadBox<&mut R> for MdatBox {
  fn read_box(reader: &mut R, header: BoxHeader) -> Result<Self> {
    reader.seek(SeekFrom::Start(header.end_offset()))?;   // jump straight past the payload
    Ok(Self { header })                                    // only the header is kept
  }
}
```

`FileBox::parse` (`formats/bmff/mod.rs:149-191`) walks every top-level box and hands `mdat` to
exactly that, so the multi-megabyte pixel payload is skipped with **one seek and is never read**.
Note the asymmetry: unknown *vendor* boxes are read (`VendorBox::read_box`), only `mdat` is exempt.

Consequences that compound:

- Detection is `Bmff::new` + `compatible_brand("crx ")` → `Cr3Decoder`
  (`decoders/mod.rs:959-963`), i.e. it settles from the box tree alone.
- `Cr3Decoder::new` (`decoders/cr3.rs:86-104`) clones the four metadata IFDs straight out of
  `moov.cr3desc`: **CMT1** basic, **CMT2** EXIF, **CMT3** Makernotes, **CMT4** GPS. Together with
  **CCTP** (image-type → trak map), **CMP1** (codec params) and **IAD1** (sensor crop) they are
  already in memory the moment the decoder exists — no second walk over the file.
- `raw_metadata` (`cr3.rs:192-239`) therefore only reads those IFDs plus a tiny CTMD record, and
  `read_cr3_metadata` is memoised in `md_cache` (`cr3.rs:497-500`). **No pixel is decompressed.**
- `raw_image(.., dummy=true)` returns `PixU16::new_uninit(..)` and skips
  `decompress_crx_image` entirely (`cr3.rs:314-322`).
- `RawSource` is `memmap2` with `populate()` plus `WillNeed`/`Sequential` advice
  (`rawsource.rs:31`), and `subview()` returns a `&[u8]` slice into the map (`rawsource.rs:74-79`)
  — zero-copy whenever payload *is* eventually needed.

Our binding already exploits this: `identify` in
`app/src/binding/rust/rawler_fotlab/src/lib.rs:99-110` deliberately uses
`rawler::get_decoder` + `Decoder::raw_metadata` and **not** `rawler::decode_dummy`, because
`decode_dummy` still walks the compressed pixel data to size its output buffer and therefore fails
when fed the 1 MiB sniff header `StudioEngine` provides (`lib.rs:86-93`).

### 2. Parallelism is decided by one question: can you compute where block N starts?

| Encoding | Start of block N | Parallel? |
| --- | --- | --- |
| Fixed-rate bit packing (uncompressed / "unpacked" RAW) | computable: `row × width × bpp / 8` | **yes** |
| Block-partitioned codec (CRX tiles, RW2/RAF strips) | recorded per tile/strip | **yes** |
| Variable-length entropy coding, no resync point (CR2 LJPEG, NEF 34713) | not computable without decoding everything before it | **no** |

This — not the camera brand — is the axis that matters.

### 3. Why CR2 cannot be parallelised

`decoders/cr2.rs:108-121` builds one decoder over one buffer and decodes the whole frame in a
single call:

```rust
let src = file.subview_until_eof(offset as u64)?;         // one block, offset → EOF
let decompressor = LjpegDecompressor::new(src)?;
decompressor.decode(ljpegout.pixels_mut(), 0, width, width, height, dummy)?;
```

It never calls `decompress_strips` / `decompress_lines` / `decompress_chunked` (those names do not
appear in `cr2.rs` at all), so it bypasses the parallel generic helpers in
`decompressors/mod.rs`. `decompressors/ljpeg/` contains **no rayon, thread or spawn primitive**.

The reason is structural, not an oversight: lossless JPEG is a variable-length Huffman stream whose
codes are not byte-aligned, and its predictor depends on the left neighbour and the row above.
Without a restart-marker index (which rawler does not build) you cannot seek into the middle.

CR2 *does* use rayon twice, but both are post-processing and neither runs for a normal full-frame
CR2:

- `cr2.rs:630` `image.par_chunks_mut(width * 3)` — `interpolate_yuv`; the function returns
  immediately when `super_h == 1 && super_v == 1`, which is the full-resolution case.
- `cr2.rs:757` `image.par_chunks_exact_mut(3)` — sRAW/mRAW YUV→RGB with hue correction.

So a full-resolution CR2 gets **zero** parallelism and its wall time is bounded by single-core
Huffman throughput. Adding cores does not help.

### 4. Lossless NEF is sequential too — the assumption to unlearn

The main NEF lossless path is `compression == 34713` → `decode_compressed` → `do_decode`
(`decoders/nef.rs:286-287`, `592-693`). Its core loop (`nef.rs:672-691`) is:

```rust
let mut pump = BitPumpMSB::new(src);            // ONE bit pump over the whole buffer
for row in 0..height {
  pred_up1[row & 1] += htable.huff_decode(&mut pump)?;   // consumes bits sequentially
  pred_up2[row & 1] += htable.huff_decode(&mut pump)?;
  ...
}
```

There is no per-row offset table and `pred_up1/pred_up2` accumulate across rows, so this is
strictly sequential — the same limitation as CR2. **This is the case our own corpus exercises**:
`NIKON D850_Large_ISO_64_14bits_Lossless.NEF` is a "Lossless" NEF.

What *is* parallel is a different branch: the **uncompressed / fixed-rate packed** NEF variants
(`nef.rs:250-282`) which dispatch to `decompress_12be` / `decompress_14le_unpacked` /
`decompress_16le` in `decompressors/packed.rs` (e.g. `packed.rs:451`). Those call
`decompress_lines_fn`, whose body is `out.pixels_mut().par_chunks_mut(width).enumerate()
.try_for_each(|(row, line)| closure(line, row))` (`decompressors/mod.rs:143-153`) — one row per
Rayon task, safe precisely because the row offset is computable. Several of these are additionally
SIMD-multiversioned, e.g. `#[multiversion(targets("x86_64+avx+avx2+fma", "x86+sse",
"aarch64+neon"))]` (`packed.rs:469`).

### 5. What genuinely runs in parallel today

| Format | Unit | Site |
| --- | --- | --- |
| CR3 / CRX | tile × 4 planes | `decompressors/crx/decoder.rs:181-184` (`tiles.par_iter()` × `planes.par_iter()`) |
| RW2 v8 | strips | `decoders/rw2/v8decompressor.rs:389` (`into_par_iter()`) |
| RAF (Fuji) | strips | `decoders/raf/fuji_decompressor.rs:332` (`strips.par_iter()`) |
| NEF / ARW **uncompressed or packed** | rows | `decompressors/packed.rs` → `decompressors/mod.rs:143-153` |

CRX tiling comes from CMP1: `tile_cols = f_width.div_ceil(tile_width)`,
`tile_rows = f_height.div_ceil(tile_height)` (`crx/mod.rs:150-151`), each tile carrying 4 planes,
so CR3 is the only format in our set whose *losslessly compressed* form scales with core count.

## Impact / Conflict

- **No conflict with `DNGLAB-RAWLER-000002`.** That item argues against rewriting rawler partly
  because Rust gives SIMD and rayon; this item shows those benefits are *unevenly distributed* —
  they accrue to CR3 and to unpacked formats, not to CR2 or lossless NEF. The conclusion (keep
  upstream) is unchanged, but "rawler is fast" must not be assumed format-uniformly.
- **No change to upstream** — this records behaviour only, per `REVIEW.md` principle 5.
- **Explains the smoke-test budget.** `RawRoutingTest` allows 8 minutes per sample precisely
  because CR2/NEF decode is single-core-bound and slow on the emulator; only CR3 has headroom from
  extra cores. Treat a CR2/NEF decode timeout as expected cost, not as a routing failure.
- **Shapes any future optimisation.** Parallelising CR2 or lossless NEF requires building an index
  first (a LJPEG restart-marker scan, or a full NEF pre-pass recording row bit offsets). That is a
  full extra pass traded for parallelism — usually a poor deal for a single image.

## Recommendation

1. **Keep `identify` on `get_decoder` + `raw_metadata`** and keep `RawRoutingTest`'s per-format
   decode budget at 8 minutes. Do not lower it for CR2/NEF on the assumption that more runners
   equal more speed — they do not.
2. **Do not attempt to "add threads" to CR2 or lossless NEF.** The correct framing is "these two
   are entropy-bound on one core"; the only honest accelerations are a faster LJPEG/Huffman
   implementation or a pre-scan index, and the latter costs a full pass.
3. **Prefer CR3-like paths where there is a choice**, and set user expectations accordingly: in
   `RawRoutingTest` and in Studio, CR2/NEF full-frame decode will remain the slowest operations
   regardless of device core count.
4. When measuring, **separate load from decode**. Time `identify`/`raw_metadata` and
   `raw_image` separately; the first is nearly free for CR3 (container) and the second is where
   CR3's parallelism shows up.

## Change History

- 2026-09-18 — Review recorded. Established that CR3's fast *load* comes from the ISO BMFF
  container (`MdatBox::read_box` seeks past `mdat`, so the payload is never read; metadata lives in
  `moov` as CMT1-4/CCTP/CMP1/IAD1, captured at construction and memoised in `md_cache`), while its
  fast *decode* comes from CRX being tiled (`tiles.par_iter() × planes.par_iter()` over a
  `ceil(w/tile_w) × ceil(h/tile_h)` grid × 4 planes). Established the general rule that parallelism
  depends on whether a block's start position is computable, and recorded the counter-intuitive
  consequence: CR2 (single lossless-JPEG stream, `cr2.rs:108-121`, `decompressors/ljpeg/` has no
  rayon) **and** lossless NEF (`compression 34713`, one `BitPumpMSB` at `nef.rs:672-691`) are both
  sequential, whereas *uncompressed/packed* NEF+ARW are row-parallel via `decompress_lines_fn`.
  Noted that CR2's two rayon sites (`cr2.rs:630`, `cr2.rs:757`) are sRAW-only post-processing and
  do not run for a full-resolution CR2. Row appended to `rules/REVIEW/index.md`.
