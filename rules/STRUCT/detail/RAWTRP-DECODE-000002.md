# External module study — RawTherapee format sniffing, standard-image (jpg/png) input, and CR2/LJPEG decode parallelism

- ID: RAWTRP-DECODE-000002
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-DECODE-000001.md` (demosaicing algorithm inventory — demosaic only runs on RAW, never on the jpg/png path described here), `rules/STRUCT/detail/RAWTRP-PIPELN-000001.md` (develop pipeline — StdImageSource and RawImageSource are the two inputs that feed it), `rules/STRUCT/detail/RAWTRP-SURVEY-000001.md` (working colour space, downstream of decode). Also context: the disabled `app/src/binding/cxx/rawtherapee_fotlab/` glue crate (no-patch strategy — see project memory), which targets `RawImageSource` only.

> **Note on naming**: this study uses the `RAWTRP-` project code (RawTherapee) and the six-character `DECODE` category, same as `RAWTRP-DECODE-000001`. It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified.

> **Scope**: three distinct questions about RawTherapee's input/decode layer, all derived directly from source (not the GUI/CLI behaviour docs):
> 1. Does format *sniffing* recognise jpg / png?
> 2. Can jpg / png actually be *passed in* as a processing input (not just as a thumbnail)?
> 3. For a CR2-class RAW (lossless-JPEG / LJPEG payload), does the decode have parallel acceleration?
>
> It is **not** a proposal to adopt RawTherapee. The value is (a) confirming that jpg/png are valid develop inputs so the no-patch `ImageIO`/StdImageSource route is a real alternative to the disabled RawImageSource glue, and (b) characterising the CR2 LJPEG decode's true parallelism ceiling for any future perf model.

All paths below are inside the pinned `external/RawTherapee/` submodule. No upstream source is modified.

## 1. Format sniffing — extension-based, recognises jpg/png

RawTherapee does **not** sniff by magic bytes (content). It sniffs by **file extension**, lower-cased, in two places.

### 1.1 Thumbnail / preview sniffer — `PreviewImage` (`rtengine/previewimage.cc:43-65`)

```cpp
if (ext.lowercase() == "jpg" || ext.lowercase() == "jpeg") {
    tpp = rtengine::Thumbnail::loadFromImage (...);          // line 43-45
} else if (ext.lowercase() == "png") {
    tpp = rtengine::Thumbnail::loadFromImage (...);          // line 50-51
} else if (ext.lowercase() == "tif" || ext.lowercase() == "tiff") {
    tpp = rtengine::Thumbnail::loadFromImage (...);          // line 56-58
} else {
    tpp = rtengine::Thumbnail::loadQuickFromRaw (...);       // line 65 — everything else → RAW
}
```

So jpg / jpeg / png / tif / tiff are explicitly handled; anything else is treated as RAW. The decision is purely the extension string.

### 1.2 RAW-vs-standard entry gate — `InitialImage::load` (`rtengine/loadinitial.cc:26-34`)

```cpp
InitialImage* InitialImage::load (const Glib::ustring& fname, bool isRaw, ...) {
    if (!isRaw) {
        isrc = new StdImageSource ();     // line 31-32 — non-RAW (jpg/png/tif)
    } else {
        isrc = new RawImageSource ();     // line 34 — RAW
    }
}
```

The `isRaw` boolean is supplied by the caller (GUI/CLI) from the extension/type table; the engine itself branches on it. **Consequence**: a jpg/png with a wrong/renamed extension is *not* recognised as an image — it falls through to the RAW path and fails. This differs from dnglab/rawler, which sniff by magic bytes.

## 2. Passing jpg / png in as a processing input — YES, via `StdImageSource` + `ImageIO`

jpg / png / tif are fully valid **develop inputs**. They bypass the RAW-specific stages but still run the full post-decode pipeline.

### 2.1 Path

| Step | Site | What happens |
| --- | --- | --- |
| Entry select | `loadinitial.cc:31-32` | `!isRaw` → `new StdImageSource()` |
| Load | `StdImageSource::load` (`stdimagesource.cc:113`) | picks `Image8` / `Image16` / `Imagefloat` by depth (`stdimagesource.cc:128/133/142`), then decodes |
| Decode | `img->load(fname)` (`stdimagesource.cc:161`) | delegates to `ImageIO` (`rtengine/imageio.cc`) — JPEG/PNG/TIFF decoder |
| Post-decode | develop pipeline (`RAWTRP-PIPELN-000001`) | runs denoise / sharpen / colour / export as normal |

### 2.2 What is skipped

Because the source is already de-Bayerised RGB (no CFA), the `StdImageSource` path **skips** the RAW-only stages: no demosaic, no white balance, no black-level / flat-field, no `RawImageSource::demosaic()` call. This is exactly why the disabled `rawtherapee_fotlab` glue (which wraps `RawImageSource::demosaic`) is irrelevant for jpg/png — those never reach `RawImageSource`.

### 2.3 Implication for the no-patch strategy

If fotlab ever needs to feed standard images through RawTherapee's *colour / tone / export* stages without RAW, the correct, patch-free surface is `StdImageSource` + `ImageIO` — there is no need to touch `RawImageSource` or ship any `.patch`. The RawImageSource glue is only needed for the demosaic/WB stages that jpg/png never exercise.

## 3. CR2 / LJPEG decode parallelism — YES, but only a 2-section pipeline

CR2's RAW payload is **lossless JPEG (LJPEG)**, decoded by `lossless_jpeg_load_raw()` (`dcraw.cc:952`). For CR2 this loader is wired in `identify()` (`dcraw.cc:10455`, `load_raw = &CLASS lossless_jpeg_load_raw;`).

### 3.1 The parallelism is a producer/consumer 2-section overlap

Inside `lossless_jpeg_load_raw` (`dcraw.cc:952-1002`), the row decode is wrapped in:

```cpp
#pragma omp parallel sections                       // dcraw.cc:964
{
    #pragma omp section                            // dcraw.cc:965 — decode NEXT row
        rp[(jrow + 1)&1] = ljpeg_row (jrow + 1, &jh);
    #pragma omp section                            // dcraw.cc:971 — write CURRENT row to RAW buffer
        for (int jcol=0; jcol < jwide; jcol++) { ... }
}
```

- One thread decodes **row j+1** (`ljpeg_row`, `dcraw.cc:965`) while another thread writes **row j** into the RAW buffer (`dcraw.cc:971`).
- The per-row Huffman decode itself (`ljpeg_row` → `ljpeg_diff`, `dcraw.cc:~901/914/931`) remains **serial** — rows are not decoded in parallel.
- **Effective ceiling ≈ 2×, consumes ~2 cores.** It is *not* a `#pragma omp parallel for schedule(dynamic,16)` that tiles all rows across every core.

### 3.2 Contrast — where RawTherapee *does* tile fully

To avoid over-stating CR2, note the genuine full-row parallelism lives elsewhere in dcraw:

| Site | Parallelism | Applies to |
| --- | --- | --- |
| `dcraw.cc:1648` | `#pragma omp parallel for schedule(dynamic,16)` | **Phase One** curve application (`phase_one_correct`) — *not* CR2 LJPEG |
| `dcraw.cc:1725`, `1744` | `#pragma omp parallel for schedule(dynamic,16)` | Phase One per-half loops |
| `rawimagesource.cc:1232` | `#pragma omp parallel` | **Pixel-shift** — one thread per frame (`riFrames`), i.e. frame-level parallelism for multi-frame CR2 |

So: single-frame CR2 LJPEG row decode = the 2-section pipeline only; multi-frame pixel-shift CR2 = frame-level `omp parallel`; the per-row `omp parallel for` is a Phase One-only path.

## 4. Summary

| Question | Answer | Grounded at |
| --- | --- | --- |
| Sniffing recognises jpg/png? | Yes — by **extension** (jpg/jpeg/png/tif/tiff); not by magic bytes | `previewimage.cc:43-65` |
| Can jpg/png be passed in as input? | Yes — `StdImageSource` → `ImageIO` (`img->load`); skips demosaic/WB/black | `loadinitial.cc:31-32`, `stdimagesource.cc:113/161` |
| CR2 LJPEG decode parallel? | Yes — but only a **2-section** producer/consumer overlap (≈2×, ~2 cores); per-row Huffman is serial; multi-frame pixel-shift is frame-parallel instead | `dcraw.cc:952/964/10455`, `rawimagesource.cc:1232` |

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` remains a fixed constraint. This document records the sniffing rules, the standard-image input path, and the CR2 LJPEG decode parallelism as observed in source. No change to RawTherapee source is specified or permitted. Whether fotlab should accept jpg/png as develop inputs at all, and whether the CR2 LJPEG 2× ceiling matters for our perf budget, are first-party DESIGN/STRUCT decisions for later items.

## Open Questions

- Q1 — The extension-only sniffer means a renamed jpg/png is misclassified. If fotlab feeds RawTherapee programmatically (not through its GUI type table), we must pass the correct `isRaw` flag ourselves rather than rely on sniffing.
- Q2 — `jpeg` (not just `jpg`) is recognised; confirm our glue/CLI passes extensions RawTherapee's table knows (`jpg`/`jpeg`/`png`/`tif`/`tiff`).
- Q3 — The CR2 LJPEG 2× ceiling is a hard cap from the 2-section design. If fotlab's perf model needs more, the lever is multi-frame pixel-shift parallelism (`rawimagesource.cc:1232`) or an upstream SIMDe rework of `ljpeg_row` — neither is in scope for a no-patch integration.

## Change History

- 2026-09-18 — RawTherapee format sniffing & standard-image input & CR2/LJPEG decode parallelism. Confirmed sniffing is **extension-based** (not magic-byte): `PreviewImage` branches on `ext.lowercase()` for `jpg`/`jpeg`, `png`, `tif`/`tiff` → `Thumbnail::loadFromImage`, else → `loadQuickFromRaw` (`previewimage.cc:43-65`); the engine entry gate `InitialImage::load` selects `StdImageSource` (non-RAW) vs `RawImageSource` (RAW) by the caller-supplied `isRaw` (`loadinitial.cc:26-34`). Confirmed jpg/png/tif are real develop inputs via `StdImageSource::load` (`stdimagesource.cc:113`) → `img->load(fname)` → `ImageIO` (`stdimagesource.cc:161`), which skips demosaic/WB/black — so the disabled RawImageSource glue is irrelevant for standard images and the no-patch `StdImageSource`+`ImageIO` route is the correct surface. Confirmed CR2 LJPEG (`lossless_jpeg_load_raw`, `dcraw.cc:952`, wired for CR2 at `dcraw.cc:10455`) is parallel only via a `#pragma omp parallel sections` 2-section producer/consumer overlap (`dcraw.cc:964`) — per-row Huffman decode stays serial, effective ceiling ≈2×; contrasted with the genuine full-row `omp parallel for` at `dcraw.cc:1648` (Phase One curve apply, not CR2) and frame-level `omp parallel` pixel-shift at `rawimagesource.cc:1232`. Filed as `RAWTRP-DECODE-000002`; row appended to `rules/STRUCT/index.md`.
