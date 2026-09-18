# Decode-once / develop-reuse across the Kotlin↔Rust FFI — holding a decoded RAW as a UniFFI auto-handle

- ID: FOTLAB-RAWLER-000004
- Status: Approved
- Priority: P1
- Created: 2026-09-18
- Owner: —
- Related: [`FOTLAB-RAWLER-000003`](FOTLAB-RAWLER-000003.md) (develop pipeline design; the consumer of this cache), [`FOTLAB-RAWLER-000001`](FOTLAB-RAWLER-000001.md) (RawImage never crosses the FFI), [`FOTLAB-CRASH-000001`](../../REVIEW/index.md) (panic boundary), `app/src/binding/rust/rawler_fotlab/src/{lib,develop,decode}.rs`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/media/RawlerFotlabDecoder.kt`

## Background & Goal

The raw render path is split into two cost classes:

- **`decode` is slow.** `decode::decode_to_rawimage` runs the full rawler decoder (`rawler::decode`) over the RAW bytes. This is the dominant cost — it parses the container, walks the compressed (LJPEG-92) pixel data, and materialises an uncompressed `RawImage`.
- **`develop` is comparatively cheap.** Once a `RawImage` exists, the scale → exposure → demosaic → calibrate → crop pipeline (`develop.rs`) is per-pixel math over an already-materialised buffer.

The bottleneck we hit: `develop` (and `develop_to_png`) currently **re-run the whole pipeline, decode included, on every call**:

```rust
64:  let mut image = decode_to_rawimage(raw)?;   // app/src/binding/rust/rawler_fotlab/src/develop.rs
```

and the Kotlin side re-reads the entire source file on every develop too:

```kotlin
35:  val bytes = runCatching { open().use { it.readBytes() } }.getOrNull() ?: return null
```

```kotlin
202:  rawDecoder.developToPng(format, algorithm, exposureEv) { resolver.openInputStream(uri) ?: error("cannot open source") }
```

So a user dragging the exposure slider, or switching demosaic algorithm from the Studio bottom bar, pays the full decode cost on *every* interaction.

**Goal:** decode the RAW exactly once, keep the result alive inside the Rust process, and let Kotlin drive repeated `develop` calls that reuse the already-decoded object without re-crossing the FFI with the large pixel buffer and without re-decoding.

## Finding — the three questions, answered

### Q1. How can Kotlin "directly hold" a Rust memory object?

It can't, physically. The JVM heap and Rust's native heap are two separate address spaces, and the JVM garbage collector does not understand Rust `Box`/`Vec` allocations. A Kotlin `Long` that *happens* to contain a native address is not "holding" the object — if Kotlin ever treated it as a pointer and dereferenced it, that would be undefined behaviour.

The correct pattern is the **opaque handle**: Rust keeps the decoded object alive in its own native heap and hands Kotlin a token (a `u64` / opaque pointer-sized integer). Kotlin holds the token; the large pixel buffer *never leaves Rust*. The handle is the only thing that crosses the boundary.

### Q2. How do we "not cross the FFI"?

Reality check: the **function call itself still crosses the FFI** — as long as Kotlin drives the UI and Rust runs the algorithm, each `develop()` invocation is a JVM↔native round-trip and cannot be eliminated while Kotlin remains in control of interaction. What *can* be eliminated is **moving the large data across the boundary**.

Today every develop ships the whole RAW (tens to hundreds of MB) into Rust *and* re-decodes it. With a handle, the only things that cross are the handle (8 bytes) and the small `DevelopParams` record. The slow decode runs once. If a truly zero-FFI design is wanted, the entire develop loop would have to live inside Rust (Kotlin would lose interactive control) — not worth it here. The win is *data* not crossing, not *calls* not crossing.

### Q3. How do we "inject" the held object back into the Rust process on the next develop?

Kotlin passes the handle back; Rust looks the live object up from its registry and reuses it. No re-decode, no re-copy of pixels. The object was always in the Rust process — "injecting" is just handing Rust its own token so it can find the object again.

## Decision

**Adopt the UniFFI auto-handle approach (a `uniffi::Object` wrapping the decoded result), not a hand-rolled `u64` registry.**

Rationale:

- UniFFI already manages an `Arc` registry behind the scenes. Returning `Arc<RawlerImageLoaded>` from a factory gives Kotlin a generated foreign class that holds a `Long` handle; the `RawImage` itself stays in Rust. No manual `Slab`/`HashMap<u64, …>`/`free_handle` plumbing.
- The generated Kotlin class's `finalize` drops the `Arc` automatically → memory is released by GC, so we get lifecycle safety largely for free.
- We wrap the *upstream* `RawImage` inside our own type (`RawlerImageLoaded`) rather than exporting `RawImage` itself. This matches the standing constraint in `FOTLAB-RAWLER-000001` ("RawImage never crosses the FFI; preview is an unprocessed dump") — the rawler type remains internal; only our first-party object is exported. Upstream `external/dnglab/rawler` stays read-only (`FOTLAB-NATIVE-000001` R4).
- It also lets us unify the currently-split `decode_to_png` (grayscale preview) and `develop`/`develop_to_png` onto one object, eliminating the duplicate decode between the preview call and the first develop call.

### Sketch

```rust
// src/loaded.rs — a UniFFI Object; Kotlin holds the generated class (a handle), never the bytes.
#[uniffi::export]
pub struct RawlerImageLoaded { inner: Arc<RawImage> }   // upstream RawImage wrapped, never exported

/// Top-level factory (the only slow step): runs rawler decode once, returns the resident object.
#[uniffi::export]
pub fn decode_rawler_image(raw: &[u8]) -> Result<Arc<RawlerImageLoaded>, RawlerFotlabError> {
    /* empty-check + catch_unwind, then decode_to_rawimage + Arc::new */
}

#[uniffi::export]
impl RawlerImageLoaded {
    /// Grayscale raw-preview PNG from the cached decode — no re-decode (replaces decode_to_png).
    pub fn preview_png(&self) -> Result<Vec<u8>, RawlerFotlabError> { /* clone inner, rawimage_to_fotraw, fotraw_to_png */ }
    /// Develop the cached decode into a linear PNG — no re-decode (replaces develop_to_png).
    pub fn develop_to_png(&self, params: DevelopParams) -> Result<Vec<u8>, RawlerFotlabError> { /* clone inner, develop_image, linearimage_to_png */ }
}

// Stateless free functions now delegate to the object (single source of truth):
//   decode_to_png(raw)     = decode_rawler_image(raw)?.preview_png()
//   develop_to_png(raw, p) = decode_rawler_image(raw)?.develop_to_png(p)
```

Kotlin side (`StudioEngine`): on open, `RawlerFotlabBridge.loadRawlerImage(bytes)` decodes exactly once, then `developRawlerImage(loaded, asShotParams)` renders the as-shot image, where `asShotParams = DevelopParams(exposureEv = null, wb = null)` — `null` tells the pipeline to adopt the decoded as-shot values (see §as-shot). The grayscale `decode_to_png` / `previewRawlerImage` path is retained in the bridge/Rust but is **not** called on open. On demosaic/exposure change, `developRawlerImage(loaded, params)` reuses the same object. No `readBytes()` and no re-decode per interaction. The object is held in `StudioEngine.loadedImage` and released (nulled) on every `setCurrentNode`.

## as-shot 约定（参考 dnglab 缩略图管线）

解码得到的 RAW 自带 as-shot 参数：**白平衡** `wb_coeffs`（rawler `RawImage.wb_coeffs`，RGBE 顺序，几乎总是存在）与 **as-shot 曝光**（已隐含在原始像素里，是 sensor 积分后的结果）。rawler 的 `RawImage` **没有独立的 `exposure` / `exp_scale` 字段**——`exposure_ev` 与 `exp_scale` 只是同一概念的两种表示：`exp_scale = 2^exposure_ev`（对数 stops / 线性倍率）。

参考 **dnglab 输出 DNG 时生成缩略图**的做法：当文件无内嵌预览时，`external/dnglab/rawler/src/dng/convert.rs::generate_preview` 调用 `RawDevelop::default().develop_intermediate(rawimage)`（`imgop/develop.rs:167`）。该管线步骤为 `Rescale → Demosaic → FujiRotate → CropActiveArea → WhiteBalance → Calibrate → CropDefault → SRgb`，**完全不含曝光补偿/亮度增益步骤**；亮度只来自 as-shot 原始像素 + `wb_coeffs` + 色彩矩阵 + sRGB gamma（`develop()` 仅把 intermediate 写成 16-bit TIFF，`RawMetadata` 只用于写 EXIF 标签，不参与亮度）。

因此本 binding 的约定：

- `DevelopParams.exposure_ev: Option<f32>` —— `None` = as-shot（单位增益 1.0，无任何补偿），`Some(ev)` = 用户以 stops 指定的补偿，管线应用线性增益 `2^ev`（`develop.rs`）。这与 dnglab 的 `RawDevelop::default()` 完全对齐（我们输出 linear 而非 sRGB gamma 是有意差异，gamma 由 Kotlin 显示端负责）。
- `DevelopParams.wb: Option<Vec<f32>>` —— `None` = 沿用解码得到的 `wb_coeffs`（as-shot 白平衡）。
- 打开路径（`StudioEngine`）只传 `null/null`，让管线采用 as-shot；后续 demosaic/exposure 变更走 `reDevelop` 用 `Some(...)` 覆盖。
- Kotlin 通过 `RawlerImageLoaded.as_shot_wb()` 读取 as-shot 白平衡，用于向用户展示 as-shot 状态（as-shot 曝光即 unity，无需单独读取字段）。

## Caveats / 注意事项

### 1. Reuse must `clone` — `develop` consumes the `RawImage`

`develop.rs` mutates its input in place: `image.apply_scaling()` rewrites `data` to `Float`, and `take_scaled_pixels` moves the buffer out (`develop.rs:64-73`). So a cached `RawImage` cannot be reused by reference across calls — each `develop` must start from a fresh clone:

```rust
let mut image = (*self.inner).clone();   // clone the decoded RawImage, then scale/demosaic
```

A clone of the decoded `RawImage` is a single `memcpy` of its `Vec` buffers (e.g. ~100 MB for a 50 MP integer raw) — a fraction of the rawler decode cost, so this is an acceptable price for "decode once". For further savings, cache the **already-scaled** `RawImage` (run `apply_scaling` once at decode time, store the scaled form) so each develop only clones the scaled float buffer and goes straight to demosaic.

### 2. Keep the `catch_unwind` panic boundary

Every rawler entry point must stay wrapped in `std::panic::catch_unwind` (`FOTLAB-CRASH-000001`): a rawler `panic!`/`unreachable!`/OOB that unwinds across `extern "C"` is UB and SIGABRTs the process, and Kotlin's `runCatching` cannot catch it. The new `decode` / `develop` / `preview_png` methods must each be hardened inside the same boundary as the existing `lib.rs` functions.

### 3. 线程安全 (concurrent access on `Dispatchers.IO`)

`StudioEngine` runs on `Dispatchers.IO`, which is a thread pool — the `Arc<RawlerImageLoaded>` (and the `RawImage` inside it) may be touched by different threads across calls. `RawImage` is plain data (`Vec`, scalars, `HashMap`) so it is `Send + Sync`; sharing it behind `Arc` is sound. The thing to watch: do not introduce interior mutability that would let two `develop` calls race on the same `RawImage` — keep each call cloning into a local before mutating (see §1). If a manual registry is ever used instead of UniFFI, the registry itself must be behind a `Mutex`.

### 4. 生命周期 — release the handle and enforce a single live handle per loaded file

- **Release on file switch.** When the user opens a *different* node (or `setCurrentNode(null)`), the previously-held `RawlerImageLoaded` must be dropped so its native memory is freed. `StudioEngine.setCurrentNode` nulls `loadedImage` at the very top, before any new decode. With the UniFFI object, dropping the Kotlin reference (and letting GC finalize) releases the `Arc`. Otherwise memory only grows — one full-size `RawImage` retained per opened file.
- **Exactly-one-handle invariant on load/develop.** `StudioEngine` must guarantee that at any moment there is **one and only one** live handle, and it corresponds to the *currently loaded* file (`currentUri`). Concretely:
  - On `setCurrentNode(uri)` for a new/non-null `uri`: free/replace the old handle *before* creating the new one; never accumulate handles across files.
  - On every `develop`/`preview`: assert/guard that the live handle's backing `RawImage` belongs to `currentUri`. A develop that runs against a stale handle (e.g. the file was swapped underneath a still-in-flight coroutine on `Dispatchers.IO`) must either be cancelled or re-decode for the current file — never develop a different file's pixels.
  - This guards the concurrency hazard from §3: an in-flight `develop` coroutine for file A must not observe file B's handle after the user switched to B.

## Impact / Conflict

- **No upstream edit.** `RawImage` is wrapped, not modified; rawler stays read-only (`FOTLAB-NATIVE-000001` R4). Complies with `FOTLAB-RAWLER-000001` (RawImage stays internal to Rust).
- **Removes duplicate decode.** Unifies preview + develop onto one decoded object; the preview call and the first develop call no longer each pay the decode cost.
- **Interaction latency.** Exposure/demosaic changes become develop-only (clone + per-pixel math) instead of full decode + read — the Studio slider/algorithm menu feels instant on large RAWs.
- **Memory.** One decoded `RawImage` (~100 MB–~840 MB depending on sensor/scale form) resident per open file; the lifecycle rules (§4) bound it to exactly one at a time.

## Open Questions

- Cache the raw `RawImage` (clone-per-develop) or the already-scaled `RawImage` (clone a larger float buffer but skip scaling each call)? Decide on measured scaling cost.
- Should `decode` accept a file path and read inside Rust, removing the one-time `readBytes()` from Kotlin entirely? Out of scope for this item; the one-time read is acceptable.

## Change History

- 2026-09-18 — Created and Approved. Analysis of the Kotlin↔Rust FFI bottleneck: decode (rawler full decode) is slow, develop is cheap, but `develop`/`develop_to_png` re-decode on every call and Kotlin re-reads the file each time (`develop.rs:64`, `RawlerFotlabDecoder.kt:35`, `StudioEngine.kt:202`). Answered the three questions (Kotlin cannot physically hold a Rust object → opaque handle; "not crossing FFI" means the large buffer never crosses, not that calls vanish; reuse = pass the handle back so Rust finds its own object). Decided on the UniFFI auto-handle approach (`uniffi::Object` `RawlerImageLoaded` wrapping `Arc<RawImage>`, not a manual `u64` registry) — keeps `RawImage` internal, gives GC-driven release, unifies preview+develop. Recorded caveats: reuse must `clone` (develop consumes the `RawImage`); keep `catch_unwind`; `Send+Sync`/thread-safety on `Dispatchers.IO`; lifecycle — release handle on file switch and enforce exactly-one-handle-per-loaded-file across load/develop. Row appended to `rules/REVIEW/index.md`.
- 2026-09-18 — **Implemented.** Named the type `RawlerImageLoaded` (per request) and built it: new `src/loaded.rs` exposes `decode_rawler_image(raw) -> Result<Arc<RawlerImageLoaded>, _>` (the only slow decode, panic-wrapped) plus `preview_png(&self)` / `develop_to_png(&self, params)` (both clone the cached `RawImage` then reuse the existing `develop.rs` pipeline — `develop_image` extracted as the shared core). The stateless `decode_to_png` / `develop_to_png` free functions now delegate to the object (single decode source of truth; signatures unchanged so existing tests pass). Kotlin side: `RawlerFotlabBridge` gains `loadRawlerImage` / `previewRawlerImage` / `developRawlerImage`; `StudioEngine` holds the object in `loadedImage` (released/nulled at the top of every `setCurrentNode`), decodes once on the raw path and re-develops from the resident object, and uses a monotonic `loadNonce` so an in-flight develop/preview for a superseded file is discarded (never paints a different file's canvas). The type was renamed from the earlier `DecodedRaw` sketch to `RawlerImageLoaded`.
- 2026-09-18 — **Adjusted open path.** On opening a raw file, `StudioEngine` now develops the resident object once with all-default params (`DemosaicAlgorithm.DEFAULT`, `exposure_ev = 0.0`, as-shot WB) to show the as-shot rendered image, instead of calling the grayscale `decode_to_png` / `previewRawlerImage` preview. `decode_to_png` (and `preview_png` / `previewRawlerImage`) code is retained in the Rust bridge, only its call from the open path is dropped. Subsequent demosaic/exposure changes still reuse the same `loadedImage`.
- 2026-09-18 — **as-shot 约定（参考 dnglab 缩略图管线）.** Investigated `external/dnglab/rawler/src/dng/convert.rs::generate_preview` + `imgop/develop.rs::develop_intermediate`: dnglab's generated DNG thumbnail uses `RawDevelop::default()`, whose pipeline has **no exposure-compensation step** — brightness = as-shot raw pixels + `wb_coeffs` + color matrix + sRGB gamma. Confirmed rawler's `RawImage` has **no** `exposure`/`exp_scale` field; as-shot exposure is implicit in the raw data, and `exposure_ev`/`exp_scale` are just log/linear forms of the same concept (`exp_scale = 2^exposure_ev`). Changes: `DevelopParams.exposure_ev` is now `Option<f32>` (`None` = as-shot, unity gain; `Some(ev)` → linear gain `2^ev`); the open path now calls `developRawlerImage(loaded, DevelopParams(exposureEv = null, wb = null))` so the pipeline adopts as-shot values (no hardcoded `0.0`). Added `RawlerImageLoaded::as_shot_wb()` so Kotlin can READ the as-shot white balance (`wb_coeffs`); as-shot exposure needs no separate read (unity). Documented the convention and dnglab reference as §as-shot.
