# External module study — RawTherapee demosaic kernel I/O contract, and the rawler→RT data-structure bridge (array + CFA)

- ID: RAWTRP-DECODE-000003
- Status: Draft
- Priority: P2
- Created: 2026-09-19
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-DECODE-000001.md` (algorithm inventory — this doc is the *data contract* the kernels in that inventory require), `rules/STRUCT/detail/RAWTRP-DECODE-000002.md` (input/I/O layer — the demosaic stage only runs on the RAW path, never on the jpg/png StdImageSource path), `rules/STRUCT/detail/RAWTRP-PIPELN-000001.md` (develop pipeline — demosaic sits between decode and the rest), `rules/STRUCT/detail/DNGLAB-RAWLER-000005.md` (RAW decode cost / parallelism — rawler is the loader we feed from). Also context: the disabled `app/src/binding/cxx/rawtherapee_fotlab/` glue crate (no-patch strategy) targets `RawImageSource::demosaic()` as a whole; this study instead isolates the *kernel* (the convolution that only needs array+CFA), which is the part worth re-hosting without the `final` `RawImageSource` class.

> **Note on naming**: `RAWTRP-` project code (RawTherapee) + six-character `DECODE` category, same as `RAWTRP-DECODE-000001/000002`. Lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to document, not modify.

> **Scope**: the *data contract* of a RawTherapee demosaic kernel — what it reads in (the array + the CFA) and what it writes out — plus how a `rawl::RawImage` loaded by rawler maps onto that contract, so a re-hosted kernel can be fed directly from our own loader without depending on `RawImageSource`. Concretely:
> 1. What does the RawTherapee demosaic **kernel body** (not the `demosaic()` dispatcher) accept? Array shape, element type, CFA form, output shape.
> 2. What does `rawl::RawImage` (post rawler decode) actually provide that satisfies that contract?
> 3. The bridge: the exact field-to-field mapping, the one required transform (`apply_scaling`), and the minimal re-hosted kernel signature.
>
> This is **research only — no code is written or modified.** It is the prerequisite for a later DESIGN item that decides whether/how to re-host an RT kernel behind our own loader.

All RawTherapee paths are inside the pinned `external/RawTherapee/` submodule; all rawler paths inside `external/dnglab/rawler/`; the binding path is `app/src/binding/rust/rawler_fotlab/`. No upstream source is modified.

## 1. What the RawTherapee demosaic kernel body accepts

The kernels come in two flavours. The distinction matters for re-hosting: **some already take `rawData` as an explicit parameter (the clean "array + CFA" template), others read `this->rawData` implicitly (tied to `RawImageSource`).**

### 1.1 The CFA-agnostic array (input) and RGB planes (output)

- **Input array**: `const array2D<float> &rawData` — a single-channel, row-major, **linear float** mosaic; value range **0..1** (already black/white normalised). Declared as the `RawImageSource` member `array2D<float> rawData;` (`rawimagesource.h:86`), which the explicit-parameter kernels take by const-ref.
- **Output planes**: `array2D<float> &red, &green, &blue` — three single-channel planes, each `w*h` (`rawimagesource.h:94-97`). The kernel writes de-Bayerised R/G/B here.
- **`array2D<float>` memory layout** (`rtengine/array2d.h`): a flat `std::vector<float> buffer` plus a `std::vector<float*> rows` of per-row base pointers (`array2d.h:63` `ARRAY2D_BYREFERENCE` flag / constructor at `:97-131`). Effectively `data[row*width + col]`, row-major. ⚠️ A copy-constructor loses the `BYREFERENCE` flag (`array2d.h:147`), so a zero-copy alias obtained via the `ARRAY2D_BYREFERENCE` constructor is *not* preserved across a plain copy — re-hosting must either hand the kernel a buffer it owns or copy explicitly.

### 1.2 The CFA (read via `this->FC(row,col)`, not a parameter)

The CFA is **never passed into the kernel**; kernels call the member `FC(row,col)` on `this`. Two encodings exist (`rtengine/rawimage.h`):

- **Bayer**: `unsigned filters` (32-bit dcraw mask). `FC` is the dcraw bit-extract (`rawimage.h:282`):
  `filters >> ((((row) << 1 & 14) + ((col) & 1)) << 1) & 3` → returns 0/1/2.
- **X-Trans**: `int xtrans[6][6]`. `FC` directly indexes the tile (`rawimage.h:298`): `xtrans[row % 6][col % 6]`.

The mask value for an RGGB 2×2 tile is `0x94949494` (see §3.2).

### 1.3 Two kernel flavours — which ones are re-hostable as-is

| Kernel | Signature site | Reads array from | Re-hostable cleanly? |
| --- | --- | --- | --- |
| `amaze_demosaic_RT` | `rawimagesource.h:281` | **param** `rawData` | ✅ yes |
| `lmmse_interpolate_omp` | `:280` | **param** `rawData` | ✅ yes |
| `vng4_demosaic` | `:278` | **param** `rawData` | ✅ yes |
| `dual_demosaic_RT` | `:282` | **param** `rawData` (+ `isBayer`, `procparams::RAWParams`) | ⚠️ yes, but carries `RAWParams` (so it is a *wrapper*, not a pure kernel) |
| `fast_xtrans_interpolate` | `:305` | **param** `rawData` | ✅ yes (X-Trans) |
| `rcd_demosaic` | `:286` | `this->rawData` | ❌ must be refactored to take a param |
| `igv_interpolate` | `:279` | `this->rawData` | ❌ must be refactored |
| `dcb_demosaic` | `:284` | `this->rawData` | ❌ must be refactored |
| `ahd_demosaic` | `:285` | `this->rawData` | ❌ must be refactored |
| `eahd_demosaic` | `:276` | `this->rawData` | ❌ must be refactored |
| `hphd_demosaic` | `:277` | `this->rawData` | ❌ must be refactored |
| `fast_demosaic` | `:283` | `this->rawData` | ❌ must be refactored |

**Conclusion for re-hosting**: the explicit-parameter kernels (`amaze`, `lmmse`, `vng4`, `fast_xtrans`) are the template. To detach the `this->rawData` kernels from the `final` `RawImageSource` class, the minimal change is to give each the same `const array2D<float>& rawData` parameter and replace its internal `this->rawData` references — *that* is the only edit a re-host needs, and it is what the no-patch strategy deliberately avoids shipping as a `.patch` in this repo (see project memory). The CFA stays as a small `FC` descriptor.

### 1.4 Windowing

`amaze`/`lmmse`/`vng4`/`dual` also take `(winx, winy, winw, winh)` — the de-Bayerised region; borders are filled by `border_interpolate`. Re-hosting can pass `(0,0,W,H)` and border-fill itself, or crop first.

## 2. What `rawl::RawImage` (post rawler decode) provides

`rawl::RawImage` (`external/dnglab/rawler/src/rawimage.rs:202-261`):

| Field | Site | Meaning for the bridge |
| --- | --- | --- |
| `data: RawImageData` | `:243` | `Integer(Vec<u16>)` or `Float(Vec<f32>)`, length `width*height*cpp` (`cpp`=1 for Bayer) — the mosaic array |
| `photometric: RawPhotometricInterpretation` | `:230` | for CFA sensors: `::Cfa(CFAConfig { cfa, .. })` → `cfa: CFA` |
| `width` / `height` / `cpp` | `:214/:216/:218` | dimensions; `cpp==1` for Bayer (X-Trans also 1) |
| `blacklevel` / `whitelevel` | `:224/:226` | needed for `apply_scaling` normalisation |
| `active_area` / `crop_area` | `:232/:234` | ROI hints (see §4.1) |

`CFA` (`external/dnglab/rawler/src/cfa.rs`):

- `color_at(row, col) -> usize` (0=R, 1=G, 2=B) (`cfa.rs:167`); also `cfa_color_at` → `CFAColor` (`:172`) and `flat_pattern()` (`:179`).
- Holds a 2×2 or 6×6 tile; `name` (e.g. `"RGGB"`) available for assertions.
- Bayer → 2×2; X-Trans → 6×6.

### 2.1 The one required transform: `apply_scaling`

The mosaic data is **not** in 0..1 float until `RawImage::apply_scaling` (`rawimage.rs:519`) runs: it converts `data` to `Float` and normalises by black/white so the result lands in 0..1 — exactly RawTherapee's `rawData` magnitude. After that, `take_scaled_pixels` reads the `f32` buffer row-major (`app/src/binding/rust/rawler_fotlab/src/develop.rs:259`, helper at `:256-272`; the contract asserts the scaled data is `f32`).

**This is the existing hand-off point.** In `develop.rs`: `image.apply_scaling()` (`:211`) → `take_scaled_pixels(&mut image)` (`:218`) yields the `Vec<f32>`; `width`/`height`/`photometric` are read alongside (`:217`). Feeding a re-hosted RT kernel = reuse that exact hand-off and pass the `f32` buffer + the CFA, nothing more.

## 3. The bridge — field-to-field mapping (rawler → re-hosted kernel)

| RT kernel needs | From rawler | Mapping |
| --- | --- | --- |
| Array `array2D<float>(W,H)`, row-major, 0..1 f32 | `image.data` (Float after `apply_scaling`), `W*H*1` f32 | Build `array2D<float>(W,H)`; copy the `Vec<f32>` into its buffer. (Zero-copy via `ARRAY2D_BYREFERENCE` is possible but the flag is dropped on copy — see §1.1 — so an owned `array2D` or one explicit copy is the safe choice. ~50 MP ≈ 200 MB/plane, acceptable.) |
| CFA — Bayer `filters` (u32) | `cfa` 2×2 tile, `color_at(r,c)→0/1/2` | Encode per dcraw `FC` bit convention (§3.2). RGGB → `0x94949494`. |
| CFA — X-Trans `xtrans[6][6]` (int) | `cfa` 6×6 tile | `xtrans[r][c] = cfa.color_at(r,c) as int`; **verify 6×6 orientation per camera** (may need transpose/shift). |
| `red`/`green`/`blue` planes | — | Allocate three `array2D<float>(W,H)`; read back after the kernel runs into our downstream (calibrate / colour). |
| `(winx,winy,winw,winh)` | `active_area` (optional) | Pass `(0,0,W,H)` and border-fill ourselves, *or* crop to `active_area` first. |

### 3.1 Minimal re-hosted kernel signature

```cpp
// array + CFA only — no RawImageSource dependency
struct CfaDesc { bool isBayer; unsigned filters; int xtrans[6][6]; };

void demosaic(const array2D<float>& rawData, const CfaDesc& cfa,
              array2D<float>& red, array2D<float>& green, array2D<float>& blue);
```

This is the literal "卷积核需要的数据结构" (the array + the CFA). `amaze`/`lmmse`/`vng4`/`fast_xtrans` already match this shape modulo the `CfaDesc` (their `FC` is currently a `this` member — fold it into the param).

### 3.2 Bayer `filters` encoding (worked example: RGGB)

dcraw `FC` bit: `shift = (((row << 1) & 14) + (col & 1)) << 1`. For the 2×2 tile:

| (row,col) | shift | RGGB colour | bit value |
| --- | --- | --- | --- |
| (0,0) | 0 | R (0) | `0 << 0` |
| (0,1) | 2 | G (1) | `1 << 2 = 0x4` |
| (1,0) | 4 | G (1) | `1 << 4 = 0x10` |
| (1,1) | 6 | B (2) | `2 << 6 = 0x80` |

Sum = `0x4 + 0x10 + 0x80 = 0x94`, replicated across the 32-bit word → **`filters = 0x94949494`**. A generic encoder loops the 4 tile positions and ORs `color << shift`.

## 4. Integration notes (research-level, no action taken)

1. **Coordinates / ROI** — rawler has already cropped to `active_area` (`develop.rs` ROI is `active_area`, see `:234` and the `crop_default` block at `:285`). RT kernels eat the full frame + a window. Bridge either passes `(0,0,W,H)` and self-fills borders, or crops to `active_area` before demosaic. Keep one convention.
2. **White balance order** — RT applies WB on the **mosaic** (per-channel scalar `scaleColors`) *before* demosaic; rawler applies WB *after* demosaic (in `calibrate`). WB is a per-channel scalar and commutes with linear interpolation, so feeding rawler's **un-WB'd 0..1 f32** straight into the RT kernel = demosaic-then-WB, mathematically equivalent. Preserve that choice consistently in the re-host.
3. **Magnitude / precision** — both sides are 0..1 f32 after `apply_scaling`; no extra normalisation needed.
4. **Single-channel assumption** — RT kernels require `cpp == 1`. rawler Bayer/X-Trans are `cpp == 1`; RGB / `LinearRaw` (`cpp == 3`) does not go through demosaic and must be excluded by the caller.
5. **Why not the disabled glue** — the `rawtherapee_fotlab` crate wraps the whole `RawImageSource::demosaic()` (which needs the `final` class + an out-of-band hook, hence disabled, no patch). Re-hosting the *kernel* instead sidesteps both: it is fed purely by array+CFA from our own loader, no `RawImageSource`, no submodule hook.

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` and `external/dnglab/rawler` remain fixed constraints. This document records the kernel I/O contract and the field-to-field bridge as observed in source. It does **not** modify any upstream source and does **not** commit to re-hosting — that is a later DESIGN/STRUCT decision (and, per project policy, any edit to RT kernels to add a `rawData` parameter would be an out-of-band hook, never a `.patch` shipped in this repo).

## Open Questions

- Q1 — For X-Trans, confirm the exact 6×6 orientation rawler's `cfa.color_at` uses vs RawTherapee's `xtrans[row%6][col%6]`; a transpose/shift per camera model may be required. Needs a camera-specific test image.
- Q2 — `dual_demosaic_RT` carries `procparams::RAWParams` (it is a contrast-adaptive *wrapper* over amaze/rcd/vng4, see `RAWTRP-DECODE-000001`). If we re-host the wrapper we must also supply the contrast param; re-hosting the inner kernels alone avoids that.
- Q3 — Decide the ownership model for the `array2D<float>` buffer (owned copy vs a lifetime-bound `BYREFERENCE` view). The copy-constructor drops the reference flag (`array2d.h:147`), so the safe default is an explicit copy; revisit only if the ~200 MB/plane copy becomes a measured bottleneck.
- Q4 — Whether to feed rawler's un-WB'd data (demosaic-then-WB, §4.2) or pre-apply rawler's WB scalar onto the mosaic (WB-then-demosaic) — both are valid; pick one and keep the calibrate stage consistent.

## Change History

- 2026-09-19 — RawTherapee demosaic **kernel I/O contract** + **rawler→RT data bridge** (array + CFA). Confirmed the kernel *body* reads a single-channel row-major `const array2D<float>& rawData` (0..1 linear float; `rawimagesource.h:86`) and writes `array2D<float>& red/green/blue` (`rawimagesource.h:94-97`); CFA is taken from `this->FC()` via either a 32-bit `filters` mask (Bayer, dcraw bit-extract `rawimage.h:282`) or `int xtrans[6][6]` (X-Trans, `rawimage.h:298`) — never a parameter. Split kernels into two flavours: explicit-`rawData`-param (`amaze` `:281`, `lmmse` `:280`, `vng4` `:278`, `fast_xtrans` `:305`) are re-hostable as-is; `this->rawData` readers (`rcd` `:286`, `igv` `:279`, `dcb` `:284`, `ahd` `:285`, `eahd` `:276`, `hphd` `:277`, `fast` `:283`) need the `rawData` param added (the one edit a re-host requires, deliberately not shipped as a patch here). Noted `array2D` copy drops `ARRAY2D_BYREFERENCE` (`array2d.h:147`). Confirmed `rawl::RawImage` (`rawler/src/rawimage.rs:202-261`) provides `data` (`Integer`/`Float`, `W*H*cpp`, `cpp==1` Bayer), `photometric::Cfa(CFAConfig{cfa})`, `width/height/cpp`, `blacklevel/whitelevel`, `active_area/crop_area`; `CFA` (`cfa.rs:167` `color_at→0/1/2`, 2×2 or 6×6). The single required transform is `RawImage::apply_scaling` (`rawimage.rs:519`) → 0..1 f32, already exposed by `take_scaled_pixels` (`rawler_fotlab/src/develop.rs:259`). Gave the field-to-field bridge table and a minimal re-hosted signature `demosaic(const array2D<float>&, const CfaDesc&, array2D<float>&, &, &)`, plus the worked RGGB→`0x94949494` `filters` encoding. Filed as `RAWTRP-DECODE-000003`; row appended to `rules/STRUCT/index.md`.
