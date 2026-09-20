# External module study — the Oklab family in our `external/` dependencies: RawTherapee's matrix-parameterised, unbound RGB↔Oklab pair (D50 XYZ ↔ D65 Oklab via Bradford); Oklch only in colour-science; no Okhsl/Okhsv anywhere

- ID: RAWTRP-SURVEY-000002
- Status: Draft
- Priority: P2
- Created: 2026-09-19
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-SURVEY-000001.md` (working colour space — ProPhoto is built-in #3 and is the natural input to this conversion), `rules/STRUCT/detail/RAWTRP-PIPELN-000001.md` (develop pipeline — where a perceptual stage would have to sit), `rules/REVIEW/detail/FOTLAB-RAWLER-000005.md` (ProPhoto D50 hub), `rules/REVIEW/detail/FOTLAB-RAWLER-000007.md` (D50 ProPhoto → Log is the other colour-space exit), `rules/REVIEW/detail/FOTLAB-RAWLER-000008.md` (rawalchemy boost saturation/contrast is *scene-linear* in ProPhoto — the direct motivation for asking whether a perceptual space is available), `rules/STRUCT/detail/FOTLAB-FOTRAW-000001.md` (RAW IR; no perceptual space in the model today)

> **Note on naming**: this study uses the `RAWTRP-` project code (RawTherapee) and the six-character `SURVEY` category. It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified. The `RAWTRP-` prefix is retained even though §9 widens the survey to the rest of the `external/` dependency set — IDs are permanent (`STRUCT.md` principle 2), so the code is never re-derived from the scope.

> **Scope**: this is a **reference study of the Oklab facilities already present in our dependencies** — what the API is, what the math is, what the white-point and input-scale contracts are, and whether anything uses it. It is **not** a proposal to adopt RawTherapee (GPL-3.0 C++); our native binding is Rust/dnglab. The value is (a) knowing a worked, in-tree Oklab implementation exists if a perceptual space is ever wanted, and (b) the precise input contract it imposes. §1–§8 cover `external/RawTherapee`; §9 extends the survey to the rest of the family (Oklch, Okhsl, Okhsv) across `external/`, since that question spans both RawTherapee and colour-science.

## Background & Goal

`FOTLAB-RAWLER-000008` established that RawAlchemyCpp's "boost" block (saturation + contrast) runs in **linear ProPhoto D50** and is therefore *optically* linear, not perceptually uniform. A perceptual alternative — or a perceptual metric for metering/auto-gain — would need a perceptual space, of which Oklab is the current default candidate. The question answered here is whether RawTherapee already ships such a conversion, and if so in what form.

Answer in one line: **yes, but not under a `ProPhoto↔Oklab` name** — it ships a *matrix-parameterised* `RGB↔Oklab` pair, so passing the ProPhoto matrices yields ProPhoto↔Oklab in both directions; the algorithm is standard Ottosson Oklab wrapped in a D50↔D65 Bradford adaptation; and **nothing in the tree calls it**.

All paths below are inside the pinned `external/RawTherapee/` submodule. No upstream source is modified.

## 1. API surface

`rtengine/color.h:1418-1435` declares four functions:

```cpp
static void xyz2oklab(float X, float Y, float Z, float &L, float &a, float &b);
static void oklab2xyz(float L, float a, float b, float &X, float &Y, float &Z);

template <class T>
static void rgb2oklab(float R, float G, float B, float &L, float &a, float &b, const T ws[3][3])
{ float X,Y,Z; rgbxyz(R,G,B, X,Y,Z, ws); xyz2oklab(X,Y,Z, L,a,b); }

template <class T>
static void oklab2rgb(float L, float a, float b, float &R, float &G, float &B, const T iws[3][3])
{ float X,Y,Z; oklab2xyz(L,a,b, X,Y,Z); xyz2rgb(X,Y,Z, R,G,B, iws); }
```

The decisive detail is the parameter: `const T ws[3][3]` / `iws[3][3]`. The RGB↔Oklab pair is **not hard-coded to sRGB**; the caller supplies the RGB→XYZ matrix (`ws`) and its inverse (`iws`). The template accepts both `float[3][3]` and `double[3][3]`, so the built-in matrix constants can be passed directly. Therefore ProPhoto↔Oklab is obtained by supplying the ProPhoto matrices — no separate function is needed, and none exists.

## 2. The algorithm — standard public Oklab

`rtengine/color.cc:1980-2006` (`xyz2oklab`):

```
M1 (XYZ→LMS)  →  per-channel cube root  →  M2 (LMS'→Lab)  →  (L, a, b)
```

`rtengine/color.cc:2009-2034` (`oklab2xyz`):

```
M2_inv (Lab→LMS')  →  per-channel cube  →  M1_inv (LMS→XYZ)
```

The matrices are Björn Ottosson's published constants, unmodified:

| Matrix | Used by | First row |
| --- | --- | --- |
| `M1` | forward, XYZ→LMS | `0.8189330101, 0.3618667424, -0.1288597137` |
| `M2` | forward, LMS'→Lab | `0.2104542553, 0.7936177850, -0.0040720468` |
| `M2_inv` | inverse, Lab→LMS' | `1.0, 0.39633779, 0.21580376` |
| `M1_inv` | inverse, LMS→XYZ | `1.22701385, -0.55779998, 0.28125615` |

Nonlinearity sits only in the two per-channel steps: the forward uses `xcbrtf` (a **sign-preserving** cube root) at `color.cc:1992`, the inverse uses `SQR(lms[i]) * lms[i]` (a sign-preserving cube) at `color.cc:2019`. Nothing is clamped, so negative LMS (which linear-light wide-gamut data produces routinely) round-trips numerically instead of turning into `NaN`.

## 3. The white-point sandwich — the part that is easy to miss

Oklab is defined for a **D65** reference white, but RawTherapee's internal XYZ is **D50** (its working space defaults to ProPhoto, its Lab is D50, and `Lab2XYZ` scales against `D50x/D50z`). The two functions therefore adapt the white point on entry/exit:

```cpp
void Color::xyz2oklab(...) { XYZ_D50_to_D65(X, Y, Z); ... }   // color.cc:1982
void Color::oklab2xyz(...) { ... XYZ_D65_to_D50(X, Y, Z); }   // color.cc:2033
```

Both helpers (`color.cc:44-71`) are **Bradford** chromatic-adaptation matrices, commented as adapted from darktable, credited to Alberto Griggio:

```
D50→D65: { 0.9555766, -0.0230393,  0.0631636 / -0.0282895, 1.0099416, 0.0210077 / 0.0122982, -0.0204830, 1.3299098 }
D65→D50: { 1.0478112,  0.0228866, -0.0501270 /  0.0295424, 0.9904844, -0.0170491 / ... }
```

Two consequences:

1. The pair's **XYZ contract is D50** — the adaptation to Oklab's native D65 happens inside, and the inverse brings it back to D50. A caller never does the D50↔D65 step itself.
2. This is what makes **ProPhoto D50** the frictionless input: feed linear ProPhoto D50 RGB → `rgbxyz(prophoto_xyz)` gives XYZ D50 → `xyz2oklab` adapts internally. The chain is

   ```
   RGB →(ws)→ XYZ D50 →(Bradford)→ XYZ D65 →(M1, cbrt, M2)→ Oklab
   ```

   and the reverse is the exact mirror.

## 4. ProPhoto matrices are already shipped

`rtengine/iccmatrices.h:175-185`:

```cpp
constexpr double xyz_prophoto[3][3] = {   // XYZ D50 → ProPhoto RGB   (use as iws)
  {0.7976749, 0.1351917, 0.0313534},
  {0.2880402, 0.7118741, 0.0000857},
  {0.0000000, 0.0000000, 0.8252100}
};
constexpr double prophoto_xyz[3][3] = {   // ProPhoto RGB → XYZ D50   (use as ws)
  {1.3459433, -0.2556075, -0.0511118},
  {-0.5445989, 1.5081673, 0.0205351},
  {0.0000000, 0.0000000, 1.2118128}
};
```

So the entire ProPhoto↔Oklab conversion is: two shipped `double[3][3]` constants plus the two templated wrappers. No new colour math is required to obtain it.

Cross-check worth noting: `xyz_prophoto[1][]` = `{0.2880402, 0.7118741, 0.0000857}` is the ProPhoto luma row — the same weight family used as `PROPHOTO_LUMA_*` by RawAlchemyCpp (`FOTLAB-RAWLER-000008`: `0.2880747 / 0.7118632 / 0.0000622`). The two sets differ only in the last digits (different derivation sources), which is a useful sanity signal that both are the ProPhoto RGB luma row rather than sRGB's `0.2126/0.7152/0.0722`.

## 5. Input scale contract — a real gotcha

These functions do **not** normalise their XYZ input. `Lab2XYZ` in the same file multiplies by `65535.f` and by `D50x/D50z` (`color.h:626-636`), because that path uses the 0–65535 convention; `xyz2oklab` does no such scaling. Since the forward step applies a **cube root**, the function is *not* scale-invariant: the implied convention is XYZ normalised so that the reference white has `Y = 1`. (Checked against the constants: for D65 white `XYZ = (0.95047, 1, 1.08883)`, `M1·XYZ ≈ (1.0000, 1.0000, 1.0003)`, so `L ≈ 1` at white.) A caller that feeds 0–65535 XYZ — as other RawTherapee code does — would get a wrong, compressed `L`. This is undocumented in the header, which is one reason the API is easy to misuse.

## 6. Bidirectionality and numeric behaviour

- **Proper pair, not one-way.** Forward and inverse matrices, the cube (root) pair, and the two Bradford directions are all present and mutually inverse, so a full round trip `RGB → Oklab → RGB` exists.
- **Wide gamut / negatives survive.** `xcbrtf` and `SQR(l)·l` are sign-preserving, so out-of-D65-gamut and negative values do not blow up.
- **Not lossless in the strict sense.** Everything is `float`, the inverse matrices are the published rounded inverses (`1.00000001`, `1.00000005`), and there is an extra D50→D65→D50 Bradford round trip. Expect small float error; do not treat the pair as bit-exact.

## 7. Usage status — shipped but unbound

A search across the whole `external/RawTherapee` tree for `rgb2oklab | oklab2rgb | xyz2oklab | oklab2xyz` returns **only the declarations in `color.h` and the definitions in `color.cc` — no call sites anywhere**. The Oklab conversion is a capability the codebase carries but does not route through: no tool, no pipeline stage, no UI reads it. Whatever motivated adding it (a future/experimental tool), it is presently dead-but-ready — which also means there is no in-repo example of the intended `ws`/scale conventions to copy.

## 8. Why this is recorded

- Among the **native (Rust/C++) modules** we might link, it is the only Oklab implementation: neither dnglab/rawler nor RawAlchemyCpp provides one — rawalchemy's saturation is the scene-linear ProPhoto operation of `FOTLAB-RAWLER-000008`, and `FOTLAB-RAWLER-000007` shows its log stage goes to a *vendor* gamut, not to a perceptual space. (This bullet originally read "the only Oklab implementation inside our dependency set"; §9 corrects that — the vendored Python `external/colour` also provides Oklab, and additionally Oklch.)
- If a perceptual stage or perceptual metric is ever specified, the vendoring cost is low and the math is frozen: two 3×3 constants, two Bradford 3×3 constants, one `cbrtf`, one `lms³`, plus the four wrapper lines — with the input-scale contract of §5 as the only trap.
- It is explicitly **not** a plan. `external/RawTherapee` is not wired into the build; adopting it would be a first-party port of published math, not a link against GPL C++.

## 9. The rest of the family — Oklch, Okhsl, Okhsv

A question follows naturally from §1–§8: if a bidirectional RGB↔Oklab pair is available, is RGB↔**Oklch**, or RGB↔**Okhsl** / **Okhsv** (Ottosson's perceptual-picker spaces) also available? Searched by both the space names (`okhsl | okhsv | oklch`) and the characteristic helper names of Ottosson's reference implementation (`compute_max_saturation | find_cusp | find_gamut_intersection | get_ST_max`) across the whole tree.

| Space | Shipped where | Notes |
| --- | --- | --- |
| Oklab | `external/RawTherapee` only (`rtengine/color.h:1418-1435`) | §1–§7 above. |
| Oklch | `external/colour` (colour-science) only | Not hand-written: generated as a **polar** conversion. `colour/models/__init__.py:850-867` declares `COLOURSPACE_MODELS_POLAR_CONVERSIONS`, whose `("Oklab", "Oklch")` entry sits at `:864`; the wrappers are built from it. Semantically `Oklch = (L, C = hypot(a,b), h = atan2(b,a))`. `colour/models/oklab.py` itself exports only `XYZ_to_Oklab` / `Oklab_to_XYZ` plus the four matrices (`oklab.py:38-45`), documented in `docs/colour.models.rst:431-432` as `Oklab_to_Oklch` / `Oklch_to_Oklab`. RawTherapee does **not** name Oklch — though it is three lines away from its own Oklab (§2). |
| Okhsl / Okhsv | **nowhere** | Zero implementation hits in `external/`. The only repository-wide occurrences of the words are documentation: `rules/STRUCT/detail/RAPIDR-SURVEY-000008.md`, which records that RapidRAW uses classic sRGB HSV and *recommends* adopting OKHSL/OKHSV. |

### 9.1 Why Okhsl/Okhsv are not a drop-in extension of Oklab

**Oklab and Oklch are gamut-free**: their coordinates are valid for any RGB gamut, which is exactly why Oklch can be produced generically as a polar form of Oklab (§9, table above). **Okhsl and Okhsv are gamut-normalised**: their `s` — and Okhsv's `v` — are defined *relative to a specific RGB gamut's* maximum at that `(h, l)`. Ottosson's reference implementation achieves this with a gamut-boundary solve: `compute_max_saturation(a, b)` analytically finds the gamut edge, supported by `find_cusp`, `find_gamut_intersection`, `get_ST_max` and `get_Cs`, plus a monotone `toe()` / `toe_inv()` helper. Those analytic/polynomial constants are derived **for sRGB primaries** and do not transfer to another gamut.

Consequences:

- Direction is **not** the obstacle: the reference ships both directions (`srgb_to_okhsl` / `okhsl_to_srgb`, `srgb_to_okhsv` / `okhsv_to_srgb`), so a bidirectional conversion is available *in principle*.
- But the construction is **primaries-specific**, so there is no standard "ProPhoto Okhsl". Bringing Okhsl/Okhsv to our ProPhoto D50 hub (`FOTLAB-RAWLER-000005`, `RAWTRP-SURVEY-000001`) requires either (a) re-deriving the cusp / gamut-boundary solve for the ProPhoto gamut, or (b) normalising against sRGB and accepting that the working hub is ProPhoto — a semantic mismatch between what `s = 1` means and what the pipeline carries.
- This also makes `RAPIDR-SURVEY-000008`'s "formula ≈ 50 lines, no dependencies" precise rather than wrong: that estimate covers the **Oklab / Oklch** layer. The Okhsl/Okhsv gamut machinery is *additional*, and it is precisely the part that carries a gamut dependency.

### 9.2 Practical reading

- Want a perceptual hue/saturation/lightness control **now**: **Oklab + Oklch** are reachable today — RawTherapee for Oklab (§1–§4), colour-science for the polar form. The cost is that Oklch chroma is *not* normalised to a displayable gamut, so the `[0,1]` semantics have to be defined by us.
- Want `s ∈ [0,1]` to mean "as saturated as the target gamut allows": that is **Okhsl/Okhsv, which must be written from scratch**, and the first decision is the **normalisation gamut** (sRGB vs ProPhoto) — a downstream consequence of the working-space decision in `RAWTRP-SURVEY-000001` / `FOTLAB-RAWLER-000005`.

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` remains a fixed constraint. So does the vendored Python `external/colour` surveyed in §9. This study records an existing capability, its exact math, its white-point and input-scale contracts, and the fact that it is unused. No change to either upstream source is specified or permitted. Whether fotlab ever wants a perceptual space (and if so, Oklab/Oklch vs. a gamut-normalised Okhsl/Okhsv vs. an alternative such as Jzazbz/CAM16, and at which pipeline position) is a first-party decision for a later DESIGN/REVIEW item.

## Open Questions

- Q1 — Does anything in fotlab actually need a *perceptual* space? `FOTLAB-RAWLER-000008` shows the boost saturation is deliberately scene-linear; a perceptual control would be a **new** stage rather than a fix to boost, and it would have to sit somewhere meaningful relative to the log boundary of `FOTLAB-RAWLER-000007`.
- Q2 — If yes, is Oklab the right choice, given it is a D65 space being fed from a D50 hub (one Bradford adaptation either way), and given Jzazbz / CAM16 may suit HDR and appearance modelling better?
- Q3 — Where would the conversion live — in the Rust `rawler_fotlab`/`rawalchemy_fotlab` layer (needs a new cxx surface, cf. `FOTLAB-RAWLER-000006`) or first-party in Kotlin (cheap, but another colour-math implementation to keep consistent)?
- Q4 — Should the input-scale contract of §5 be normalised (i.e. any port should take/return a defined `Y=1`-at-white XYZ, or better, take RGB directly) rather than inheriting RawTherapee's undocumented convention?
- Q5 — If a gamut-normalised space (Okhsl/Okhsv, §9) is ever wanted, **which gamut normalises `s`** — sRGB (published constants, usable immediately, but mismatched with a ProPhoto D50 hub) or ProPhoto (needs a bespoke cusp solve)? Or is Oklch with an explicit chroma ceiling a better fit than Okhsl's gamut-normalised `s`?

## Change History

- 2026-09-19 — RawTherapee Oklab study. Documented the four-function API in `rtengine/color.h:1418-1435`: `xyz2oklab` / `oklab2xyz` plus the **matrix-parameterised** templated pair `rgb2oklab(..., const T ws[3][3])` / `oklab2rgb(..., const T iws[3][3])`, so supplying `prophoto_xyz` / `xyz_prophoto` (`iccmatrices.h:175-185`, both D50) yields a bidirectional ProPhoto D50 ↔ Oklab conversion without any dedicated function. The algorithm (`color.cc:1980-2034`) is standard Ottosson Oklab: `M1 → cbrt → M2` forward (`xcbrtf`, sign-preserving) and `M2_inv → cube → M1_inv` inverse (`SQR(l)·l`), with published constants. Key contract finding: the pair adapts XYZ **D50→D65** on entry and **D65→D50** on exit via Bradford matrices (`color.cc:44-71`, credited to darktable/Alberto Griggio), because RawTherapee's internal XYZ is D50 while Oklab is defined at D65 — which is exactly why linear ProPhoto D50 is the frictionless input. Second contract finding: the functions do **not** normalise XYZ, so the implied convention is white at `Y = 1` and the cube root makes the scale significant (unlike the 65535-scaled `Lab2XYZ` in the same file). Also recorded: `xyz_prophoto[1][]` is the ProPhoto luma row, matching RawAlchemyCpp's `PROPHOTO_LUMA_*` family (`FOTLAB-RAWLER-000008`); the conversion is a proper round-trip pair but not bit-exact (float + rounded inverse matrices + double Bradford); and it is **unused** — a tree-wide search for callers returns only the declarations and definitions. Filed as `RAWTRP-SURVEY-000002`; row appended to `rules/STRUCT/index.md`.
- 2026-09-19 — Append (§9): surveyed the rest of the Oklab family across `external/`, prompted by the question "is there an RGB↔Okhsl/Okhsv conversion?". **Neither Okhsl nor Okhsv exists anywhere in the repository or its dependencies** — searched both the space names (`okhsl|okhsv|oklch`) and Ottosson's helper names (`compute_max_saturation|find_cusp|find_gamut_intersection|get_ST_max`); no implementation hits. **Oklch is available, but only in the vendored Python `external/colour`**: it is a programmatically generated *polar* form of Oklab (`colour/models/__init__.py:850-867`, entry `("Oklab","Oklch")` at `:864`; documented at `docs/colour.models.rst:431-432`), while `colour/models/oklab.py:38-45` exports only `XYZ_to_Oklab`/`Oklab_to_XYZ` and the four matrices; RawTherapee names Oklab only. Repository-wide occurrences of "OKHSL/OKHSV" are documentation only (`rules/STRUCT/detail/RAPIDR-SURVEY-000008.md`, which recommends adopting them for RapidRAW). Recorded the structural reason they are not a drop-in extension of Oklab: **Oklab and Oklch are gamut-free, whereas Okhsl/Okhsv are gamut-normalised** — Ottosson's `compute_max_saturation` solves the **sRGB** gamut boundary, so a "ProPhoto Okhsl" would need its own cusp solve, and `RAPIDR-SURVEY-000008`'s "≈50 lines" applies only to the Oklab/Oklch layer. Also in this revision: widened the title and Scope to cover the family across `external/`, corrected §8's "only Oklab implementation in our dependency set" to "only among the native Rust/C++ modules", extended Constraints to cover `external/colour`, and added Q5. Index row title updated to match.
