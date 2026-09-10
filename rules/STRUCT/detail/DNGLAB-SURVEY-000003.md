# External module study — dnglab rawler: camera metadata (`data/cameras`) propagation through decode → encode

- ID: DNGLAB-SURVEY-000003
- Status: Draft
- Priority: P2
- Created: 2026-09-10
- Owner: —
- Related: `DNGLAB-SURVEY-000001` (workspace / CLI / `dnglab_lib` integration seam), `DNGLAB-SURVEY-000002` (decode pipeline & `RawImage`/`RawImageData` contract)

> **Note on naming**: per the user's request this study file uses the `DNGLAB-` prefix rather than the
> standard `FOTLAB-STRUCT-NNNNNN` ID. It lives under `rules/STRUCT/detail/` because `STRUCT.md`
> principle 5 treats `external/` modules (dnglab, exiftool) as fixed constraints to be documented, not modified.

> This document is a **third deep-dive companion** to `DNGLAB-SURVEY-000002`. That file records the
> decode pipeline and the unified `RawImage`/`RawImageData` contract. This file traces *where the
> per-camera parameters that populate `RawImage.camera` and the output DNG tags actually come from*:
> the `data/cameras/*.toml` database, how it is built into the binary, how a file is looked up against
> it on import, the intermediate `Camera` / `RawLoader` structures, and how those values reach the
> encoder (`DngWriter`).

## Background & Goal

`rawler` does not parse every camera constant out of the raw file — most sensor/color parameters
(CFA pattern, color matrices, active/crop areas, default & best-quality scales, black/white levels)
come from a **static camera database** shipped with the library, at `rawler/data/cameras/`. Understanding
this matters for the first-party native-integration module because:

- `RawImage.camera` is a **looked-up record**, not bytes parsed from the file. The FFI boundary hands
  back a `Camera` that was resolved against the embedded DB at decode time.
- The DNG tags `Make` / `Model` / `CFAPattern` / `ColorMatrix` / `DefaultScale` / `BestQualityScale`
  all originate from `data/cameras`, so the encode stage is a *consumer* of this DB, not a producer.
- The DB is compiled in (see §1). The first-party module cannot hot-swap camera data without rebuilding
  `rawler`.

## 1. Data source & build-time ingest (`data/cameras/*.toml` → `cameras.toml`)

- **Files**: `rawler/data/cameras/<maker>/<model>.toml` — 738 TOML files nested by vendor
  (`canon/`, `fuji/`, `nikon/`, `sony/`, `panasonic/`, `olympus/`, `leica/`, `pentax/`, …). Known but
  intentionally unsupported models carry a `.unsupp` extension instead of `.toml`.
- **One file = one `[[cameras]]` table.** Example (`data/cameras/canon/5d.toml`, full contents):

  ```toml
  make = "Canon"
  model = "Canon EOS 5D"
  clean_make = "Canon"
  clean_model = "EOS 5D"
  color_pattern = "RGGB"
  active_area = [90,34,0,0]
  blackareav = [0, 88]
  blackareah = [2, 30]
  whitepoint = 3692

  [cameras.color_matrix]
  A = [0.7284, -0.1569, -0.0425, -0.6726, 1.4016, 0.2993, -0.0926, 0.1258, 0.7774]
  D65 = [0.6347, -0.0479, -0.0972, -0.8297, 1.5954, 0.248, -0.1967, 0.2132, 0.7649]
  ```

- **Build step** — `rawler/data/join.rs::join_cameras()` globs `./data/cameras/*/**/*.toml`, prepends
  a `[[cameras]]\n` header to each, and concatenates everything into `$OUT_DIR/cameras.toml`
  (`join.rs:18-45`). `join_lenses()` does the same for `data/lenses/`. The toml is parsed purely to
  validate it (a bad file `panic!`s the build).
- **Compile step** — `decoders/mod.rs:142`:

  ```rust
  pub static CAMERAS_TOML: &str = include_str!(concat!(env!("OUT_DIR"), "/cameras.toml"));
  ```

  embeds the **entire** camera database as a `&str` constant into the binary. (Lenses analogously via
  `LENSES_TOML`, `lens.rs:12`.)

> **Key insight**: `data/cameras` is **not** read from disk at runtime — it is statically compiled in.
> Adding or updating a camera = editing a `.toml` + recompiling `rawler`. The first-party module cannot
> inject or override camera entries without rebuilding the crate.

## 2. Runtime query — `RawLoader` & `Camera`

- **`RawLoader`** (`decoders/mod.rs:834`) holds `cameras: HashMap<(String, String, String), Camera>` plus
  `naked: HashMap<usize, Camera>`. It is the global `lazy_static LOADER` (introduced in 000002 §1.1),
  so the DB is parsed **once per process**.
- **`RawLoader::new()`** (`decoders/mod.rs:842-901`):
  - parses `CAMERAS_TOML` into a `toml::Value`;
  - for each `[[cameras]]` entry: `Camera::new()` then `Camera::update_from_toml` (`camera.rs:108`);
    expands `model_aliases` and `modes` (a `[cameras.modes]` subtable) into multiple `Camera` records;
  - inserts into the map keyed by `(make, model, mode)` (`mod.rs:891-898`). Entries with a `filesize`
    also go into `naked` (keyed by size) for header-less files.
- **`Camera`** (`decoders/camera.rs:15`) — the parsed record. Fields: `make` / `model` / `mode`,
  `clean_make` / `clean_model`, `whitepoint`, `blackpoint`, `blackareah` / `blackareav`,
  `color_matrix: HashMap<Illuminant, FlatColorMatrix>`, `cfa`, `plane_color`, `active_area`,
  `crop_area`, `bps`, `real_bps`, `filesize`, `raw_width` / `raw_height`, `highres_width`,
  `default_scale`, `best_quality_scale`, `hints`, `params`. `update_from_toml` (`camera.rs:108-249`)
  maps each toml key; an unknown key `panic!`s (fail-fast, consistent with upstream's panic-on-corrupt
  policy noted in 000002 §1.1).

> Note the two name forms: the DB's `make`/`model` are the **raw** strings used as the lookup key
> (e.g. `"Canon EOS 5D"`); `clean_make`/`clean_model` are the **normalized** strings used only for
> output tags (e.g. `"EOS 5D"`).

## 3. Import-time metadata extraction & lookup

When a file is decoded (`RawLoader::decode`, `decoders/mod.rs:1093`):

1. **Format dispatch** — `get_decoder` (`mod.rs:909`) sniffs magic/container; for TIFF-based files it
   matches the EXIF IFD0 `Make` string to a decoder (`mod.rs:991-1016`). This `Make` is read straight
   from the **file**, not the DB.
2. **Camera lookup** — inside the decoder's `identify()`/`raw_image()`, it calls
   `rawloader.check_supported_with_mode(root_ifd, mode)` (`mod.rs:1076`) (or `_with_everything`). That
   reads `Make` + `Model` from IFD0 (`fetch_tiff_tag!`, trimmed), then
   `check_supported_with_everything(make, model, mode)` (`mod.rs:1064`) does
   `self.cameras.get(&(make, model, mode))` and returns the `Camera` (clone). A miss returns
   `RawlerError::Unsupported`.
   - Example: `cr2.rs:377` — `let camera = rawloader.check_supported_with_mode(tiff.root_ifd(), mode_str)?;`
     then stored as `self.camera` and used throughout the decoder.
   - The lookup key uses the **file's raw `Make`/`Model`** strings, matched against the DB's `make` /
     `model` fields. `clean_*` names never participate in the lookup.
3. **Attach to the intermediate** — the returned `Camera` is attached to the `RawImage`
   (`RawImage::new(camera.clone(), …)`, e.g. `mod.rs:461-473`) and to `RawMetadata`
   (`RawMetadata::new_with_lens(&camera, …)`, `mod.rs:247-257`, `cr2.rs:296`). File EXIF (exposure,
   date, lens) is parsed separately into `RawMetadata.exif`; the two side-channels (camera DB vs. file
   EXIF) meet on `RawImage` / `RawMetadata`.

> **`mode` disambiguation**: `mode` (e.g. Canon sRaw/mRaw) is decoder-inferred, not a single EXIF tag.
> It selects among multiple `[cameras.modes]` subtables that share one `model` in the toml.

## 4. Intermediate data structures

| Struct | File:line | Role |
| --- | --- | --- |
| `Camera` | `decoders/camera.rs:15` | One looked-up camera record (CFA, color matrices, crops, scales, clean names, hints/params) |
| `RawLoader` | `decoders/mod.rs:834` | `HashMap<(make,model,mode), Camera>` + `naked`; global `LOADER` |
| `RawImage.camera` | `rawimage.rs:201` (field listed in 000002 §2.1) | The `Camera` resolved on import, carried into encode |
| `RawImage.clean_make` / `clean_model` | `rawimage.rs` | Copied from `Camera`, used for tag writing |
| `RawMetadata` | `decoders/mod.rs:237` | `model` / `make` = `camera.clean_*`; `exif` = parsed file EXIF |

The toml → `Camera` mapping for `5d.toml` (§1) yields: `make="Canon"`, `model="Canon EOS 5D"`,
`clean_model="EOS 5D"`, `cfa=RGGB`, `active_area=[90,34,0,0]`, `whitepoint=3692`, and a
`color_matrix` keyed by `Illuminant::A` / `Illuminant::D65`.

## 5. How `data/cameras` reaches the encode / output stage

`DngWriter` (`rawler/src/dng/writer.rs`) consumes `RawImage`, which embeds the looked-up `Camera`:

- **`load_base_tags`** (`writer.rs:427-433`): writes `Make` = `clean_make`, `Model` = `clean_model`,
  `UniqueCameraModel` = `clean_make clean_model`.
- **`write_rawimage`** (`writer.rs:222-232`): writes `DefaultScale` from `camera.default_scale` and
  `BestQualityScale` from `camera.best_quality_scale`.
- **`write_rawimage`** (`writer.rs:155-176`): writes `AsShotNeutral` (WB) and `ColorMatrix1` / `ColorMatrix2`
  from `rawimage.color_matrix`, which was sourced from `Camera.color_matrix` at decode time.
- **CFA**: `RawImage.photometric` is built via `CFAConfig::new_from_camera(&self.camera)`
  (e.g. `cr2.rs:261`), so the DNG `CFAPattern` / `CFARepeatPatternDim` / `CFAPlaneColor` originate from
  `Camera.cfa` / `plane_color`.
- **Black/white levels, ActiveArea, DefaultCrop** are likewise copied from `Camera` into `RawImage`
  during decode and then written by `write_rawimage` (`writer.rs:234-272`).

> **End-to-end**: `data/cameras/<maker>/<model>.toml` → `join.rs` build join → `cameras.toml` →
> `include_str!` (`mod.rs:142`) → `RawLoader.cameras` HashMap → lookup by file `Make`/`Model`/`mode`
> (`mod.rs:1064`) → `Camera` attached to `RawImage` → `DngWriter` emits `Make`/`Model`/`CFA`/
> `ColorMatrix`/`DefaultScale`/`BestQualityScale`/levels into the DNG.

## Constraints (STRUCT.md principle 5)

`external/dnglab` is a fixed constraint, and `data/cameras` is part of it. This document records how
the DB is loaded and propagated only; no change to `rawler` source or to the `.toml` files is specified
or permitted here. The FFI boundary, threading model and lifecycle belong in the first-party
native-integration module.

## Relationship to DNGLAB-SURVEY-000001 / 000002

- 000001 establishes *what dnglab is* (workspace, CLI, `dnglab_lib` seam, licensing, trust).
- 000002 establishes *what rawler outputs* (the `RawImage`/`RawImageData` contract + CFA + metadata).
- 000003 establishes *where the per-camera parameters inside `RawImage.camera` come from* and how they
  flow into the encoded DNG — the missing link between the incoming file and the outgoing tags.

## Change History

- 2026-09-10 — Camera-metadata deep-dive. Documented the `data/cameras/*.toml` → `join.rs` →
  `cameras.toml` → `include_str!` (`decoders/mod.rs:142`) compile-time ingest; the `RawLoader`
  `HashMap<(make,model,mode), Camera>` query built in `RawLoader::new` (`mod.rs:842-901`, `:891-898`)
  and resolved via `check_supported_with_everything` / `check_supported_with_mode`
  (`mod.rs:1064`, `:1076`, called from `cr2.rs:377`); the `Camera` / `RawImage.camera` / `RawMetadata`
  intermediate structures; and how `Camera` fields reach `DngWriter` (`load_base_tags` writes
  clean_make/clean_model `writer.rs:427`; DefaultScale/BestQualityScale `:222`; ColorMatrix `:155`; CFA
  via `CFAConfig::new_from_camera` `cr2.rs:261`). Flagged that the DB is a compile-time constant, so the
  first-party module cannot hot-swap camera data without rebuilding rawler.
