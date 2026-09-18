# External module study — RawTherapee working colour space: built-in profiles, custom JSON extension, TRC, illuminant & primaries

- ID: RAWTRP-SURVEY-000001
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-PIPELN-000001.md` (the full develop pipeline; working space is Stage D input→working conversion and the RGB-working buffer of Stages A–I), `rules/STRUCT/detail/DNGLAB-RAWDEV-000001.md` (dnglab `rawler::imgop::develop` — linear-RGB model, no user-selectable working space), `rules/STRUCT/detail/FOTLAB-STUDIO-000001.md` (Studio render contract)

> **Note on naming**: this study file uses the `RAWTRP-` project code (RawTherapee) and the six-character `SURVEY` category. It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified.

> **Scope**: this is a **reference study of how RawTherapee lets the user choose a working colour space** — the built-in primaries, the custom-JSON extension mechanism, the selectable TRC/gamma, and the illuminant/primaries/gamut dimensions. It is **not** a proposal to adopt RawTherapee (GPL-3.0 C++); our native binding is Rust/dnglab. The value is the *configuration surface* a working-colour-space feature must expose, which our `rawler_fotlab` binding currently lacks.

## Background & Goal

`RAWTRP-PIPELN-000001` established that RawTherapee's develop pipeline runs base tone (exposure, curves, saturation, channel mixer, film sim) in an **RGB working space** (`Imagefloat`) before moving most creative tools into CIELAB (`LabImage`). The working space is therefore not a fixed constant — it is a user-facing choice that affects the entire colour math. This document records exactly what choices RawTherapee exposes and where they live in source, so a future fotlab working-colour-space feature can reference the complete configuration surface.

All paths below are inside the pinned `external/RawTherapee/` submodule. No upstream source is modified.

## 1. Built-in working profiles — 12 primaries

The working-profile primaries are hard-coded as XYZ→RGB matrices in `rtengine/iccstore.cc`. The name list and the forward/inverse matrix arrays are declared together at `iccstore.cc:200-202`:

```
const char* wpnames[] = {"sRGB", "Adobe RGB", "ProPhoto", "WideGamut",
                         "JDCmax", "JDCmax stdA", "Beta RGB", "BestRGB",
                         "Rec2020", "ACESp0", "ACESp1", "BruceRGB"};
```

Each name maps to a forward matrix (`wprofiles[]`, XYZ→RGB) and an inverse matrix (`iwprofiles[]`, RGB→XYZ) at the same index. At `ICCStore` construction (`iccstore.cc:404-411`) these are registered into an internal `wProfiles` map via `createFromMatrix`, so lookup is by string name.

| # | Working profile | Notes |
| - | --- | --- |
| 1 | sRGB | IEC 61966-2-1 |
| 2 | Adobe RGB | Adobe RGB (1998) |
| 3 | ProPhoto | Kodak ProPhoto RGB — **pipeline default** (`improcfun.cc:2241` `isProPhoto`) |
| 4 | WideGamut | Adobe Wide Gamut RGB |
| 5 | Rec2020 | BT.2020 / Rec. 2020 |
| 6 | ACESp0 | ACES 2065-1 (AP0) |
| 7 | ACESp1 | ACEScg (AP1) |
| 8 | Beta RGB | Beta RGB |
| 9 | BestRGB | Best RGB |
| 10 | BruceRGB | Bruce RGB |
| 11 | JDCmax | Internal wide-gamut space |
| 12 | JDCmax stdA | JDCmax with Standard A illuminant |

The default is **ProPhoto** — the broadest of the photographic spaces, chosen to avoid clipping during aggressive creative adjustments. If a requested name is not found, `workingSpace()` falls back to `sRGB` (`iccstore.cc:487-495`).

## 2. Custom working spaces — `workingspaces.json`

Beyond the 12 built-in primaries, RawTherapee loads **user-defined working spaces** from a JSON file. The loader is `loadWorkingSpaces(const Glib::ustring &path)` at `iccstore.cc:833`, which reads `workingspaces.json` from two directories at startup (`iccstore.cc:479-480`):

1. `rtICCDir` — the bundled ICC data directory (shipped with the app)
2. `userICCDir` — the per-user ICC directory (user-editable)

Each JSON entry under the top-level `working_spaces` array has:

| Field | Type | Meaning |
| --- | --- | --- |
| `name` | string | Display name; must be unique — duplicates of built-in or earlier entries are skipped (`iccstore.cc:899-901`) |
| `matrix` | 3×3 number array | Direct XYZ→RGB matrix; parsed row-major (`iccstore.cc:905-928`) |
| `file` | string | Path (relative to the JSON dir or absolute) to an ICC profile; the matrix is extracted from it via `computeWorkingSpaceMatrix` (`iccstore.cc:930-937`) |

Exactly one of `matrix` or `file` must be present. The resulting matrix is registered into the same `wProfiles` map as the built-in ones (`iccstore.cc:963`), so a custom space is indistinguishable from a built-in one downstream — it appears in the same UI list and uses the same pipeline code paths.

There is no `workingspaces.json` shipped in the repository tree (only `camconst.json`, `cammatrices.json`, `dcraw.json`, `rt.json` exist under `rtdata/`), so custom spaces are a runtime/user-level extension, not a compile-time fixture.

## 3. Working TRC (tone response curve / gamma)

The working-space transfer function is independently selectable and does **not** have to match the primaries' native gamma. The enum is `ColorManagementParams::WorkingTrc` in `rtengine/procparams.h:1031-1039`:

```cpp
enum class WorkingTrc {
    NONE, CUSTOM, BT709, SRGB, GAMMA_2_2, GAMMA_1_8, LINEAR
};
```

| Value | Meaning |
| --- | --- |
| `NONE` | No TRC (linear passthrough in the working stage) |
| `CUSTOM` | User-defined gamma + slope (the ICC panel exposes gamma/slope/sigma/offset controls; see `icmpanel.cc` event IDs `EvICMgamm`, `EvICMslop`, `EvICMsigmatrc`, `EvICMoffstrc`) |
| `BT709` | BT.709 OETF (gamma ≈ 2.22, linear segment slope 4.5) |
| `SRGB` | sRGB OETF (gamma 2.4, linear segment slope 12.92310) |
| `GAMMA_2_2` | Pure gamma 2.2 |
| `GAMMA_1_8` | Pure gamma 1.8 |
| `LINEAR` | Linear (gamma 1.0) |

The TRC is applied at the output side of the Lab creative stage — `ipf.workingtrc(...)` at `improccoordinator.cc:2281-2282` (see `RAWTRP-PIPELN-000001` Stage L). The working primaries + working TRC together form the **abstract working profile** that the user effectively edits in.

## 4. Additional working-space dimensions

`ColorManagementParams` (procparams.h:1030-1101) exposes three more selectable dimensions that refine the working space:

### 4.1 Gamut limitation (`Wwgamut`, procparams.h:1041-1047)

```
NONE, REC2020, ADOBE, SRGB, DCIP3
```

Clamps the working-space gamut to a smaller envelope. Useful when working in ProPhoto but wanting to guarantee no out-of-gamut colours for a target display/print space.

### 4.2 Illuminant / adaptation white point (`Illuminant`, procparams.h:1049-1062)

```
DEFAULT, D41, D50, D55, D60, D65, D80, D120, STDA,
TUNGSTEN_2000K, TUNGSTEN_1500K, E
```

Selects the reference illuminant for chromatic-adaptation and the working-space white point. D65 is the common photographic default; D50 is the print/ICC standard.

### 4.3 Primaries (`Primaries`, procparams.h:1064-...)

```
DEFAULT, SRGB, ADOBE_RGB, PRO_PHOTO, REC2020, ACES_P1, WIDE_GAMUT, ACES_P0, ...
```

An alternative primaries selector used by the colour-appearance / abstract-profile path. This overlaps with the `workingProfile` string list (§1) but is exposed as a typed enum for the CIE/abstract-profile tooling.

## 5. UI entry point

The user-facing control is the **Working Profile** frame in the ICC management panel, `rtgui/tools/icmpanel.cc:220-239`. A combo box (`wProfNames`) is populated from `ICCStore::getInstance()->getWorkingProfiles()` (`iccstore.cc:734-746`, public wrapper at `iccstore.cc:1155-1158`), which returns **all** entries in the `wProfiles` map — built-in + JSON-loaded. The selected string is stored back to `pp->icm.workingProfile` (`icmpanel.cc:1712`).

The TRC combo (`wTRC`, `icmpanel.cc:241-263`) and the gamma/slope spin controls sit directly below the working-profile selector, so the user configures primaries + TRC in one place.

## 6. How the working space flows through the pipeline

The working-profile string (`params->icm.workingProfile`) is consumed at multiple points in `improcfun.cc`:

| Call site | Purpose |
| --- | --- |
| `improcfun.cc:454` | `workingSpaceMatrix(...)` — forward matrix for input→working conversion (Stage D) |
| `improcfun.cc:1038` | `workingSpaceInverseMatrix(...)` — inverse for working→XYZ when computing Lab |
| `improcfun.cc:1587` | `workingSpaceMatrix(...)` — CLUT/film-sim colour-space alignment |
| `improcfun.cc:2101-2102` | `rgbProc` (Stage J) — both forward and inverse matrices for tone/curve/saturation/channel-mixer math |
| `improcfun.cc:2241` | `isProPhoto` branch — ProPhoto-specific gamut handling |
| `improcfun.cc:4336-4348` | Per-profile saturation/gamut presets (ProPhoto / Adobe RGB / sRGB / WideGamut / Beta RGB / BestRGB / BruceRGB) |

So the working space is not merely a label — the forward/inverse matrices drive every RGB-stage computation, and ProPhoto gets special-cased gamut logic.

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` remains a fixed constraint. This study records the working-colour-space configuration surface (built-in primaries, JSON extension, TRC enum, illuminant/primaries/gamut selectors) and where it is read in the pipeline. No change to RawTherapee source is specified or permitted. Whether/when `rawler_fotlab` exposes a user-selectable working space, and at what fidelity (fixed sRGB vs. selectable primaries vs. full RawTherapee parity), are first-party decisions for a later DESIGN/STRUCT item.

## Open Questions

- Q1 — Does our `rawler_fotlab` develop need a selectable working space at all, or is sRGB/linear sufficient for the Studio preview? (`FOTLAB-STUDIO-000001` R4.)
- Q2 — If selectable, what is the minimum viable set: sRGB / Adobe RGB / ProPhoto / Rec2020, or the full 12 + JSON custom?
- Q3 — dnglab `rawler::imgop::develop` (`DNGLAB-RAWDEV-000001`) develops in linear RGB with no user-facing working-space concept. Do we extend it (Rust-side) or wrap it (first-party) to add one?
- Q4 — TRC independence: should the gamma curve follow the primaries (sRGB→sRGB OETF) or be independently selectable as in RawTherapee?

## Change History

- 2026-09-18 — RawTherapee working-colour-space study. Documented the 12 built-in working profiles (`iccstore.cc:200-202`, default ProPhoto at `improcfun.cc:2241`, sRGB fallback at `iccstore.cc:487-495`), the custom `workingspaces.json` extension loaded from `rtICCDir`/`userICCDir` via `loadWorkingSpaces` (`iccstore.cc:833`, supporting both `matrix` and ICC `file` sources, `iccstore.cc:905-937`), the independently-selectable `WorkingTrc` enum (`procparams.h:1031-1039`: NONE/CUSTOM/BT709/SRGB/GAMMA_2_2/GAMMA_1_8/LINEAR), the gamut/illuminant/primaries dimensions (`procparams.h:1041-1072`), the ICC-panel UI entry (`icmpanel.cc:220-239, 1712`), and the working-space matrix consumption sites in `improcfun.cc` (input→working `:454`, Lab conversion `:1038`, `rgbProc` `:2101-2102`, ProPhoto special case `:2241`, per-profile gamut `:4336-4348`). Filed as `RAWTRP-SURVEY-000001`; row appended to `rules/STRUCT/index.md`.
