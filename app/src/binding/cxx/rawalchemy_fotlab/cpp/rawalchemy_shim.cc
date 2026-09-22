// Our own declaration of `grade` — spelled relative to the crate root because that
// is exactly how the cxx-generated shim includes it (see build.rs).
#include "cpp/rawalchemy_api.h"

#include "grading_fused.h"
#include "color_data.h"
#include "lut_applier.h"
#include "metering.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>

// Implemented for the `grade` declared in cxxbridge.h (global namespace, the
// default cxx bridge namespace).
//
// The glue is a *thin* pass-through: it starts from upstream's own declared
// defaults (`GradingParams{}`) and overrides ONLY the fields the caller
// explicitly asked for. It deliberately pins no value that upstream owns, so if
// upstream changes a default we follow it automatically — this is the whole
// point of `FOTLAB-RAWLER-000006`.
//
// "Did not ask for this field" is carried across the cxx boundary as an
// out-of-band sentinel, because cxx has no `Option<f32>` / `Option<bool>`:
//   * floats  — quiet NaN means "leave the upstream default"; every finite
//               value (incl. 0.0) is a legitimate override.
//   * boost   — a tri-state int32 (`kBoostUnset` / off / on), so `false` is
//               distinguishable from "unset" (upstream defaults to `true`).
//   * strings — empty means "skip that stage".
namespace {
constexpr int32_t kBoostUnset = -1;
inline bool is_unset(float v) { return std::isnan(v); }
}  // namespace

// ---------------------------------------------------------------------------
// Log-space alias table — the one place the UI name and the engine name meet.
//
// DECOUPLED from the parallel grading engine on purpose: this table is a
// standalone constant, so the enumeration handed to the UI can never be
// affected by how `applyGradingFused` / OpenMP is built or linked (the failure
// that motivated the split — see `rules/REVIEW/detail/ACTION-RAWLER-000007.md`).
//
// Two columns, two audiences:
//   * `canonical` — the key upstream's `LOG_SPACES` map is keyed by
//     (external/RawAlchemyCpp/include/color_data.h). This is the ONLY string
//     that may ever reach `LOG_SPACES`; the submodule is read-only for us.
//   * `display`   — what Kotlin renders. The vendor is spelled out and the
//     curve is named the way the vendor names it, so the user reads
//     "FUJIFILM F-Log2 C" rather than upstream's internal "F-Log2C".
//
// Where the vendors already ship a vendor-prefixed official name (Canon "Canon
// Log 2", ARRI "ARRI LogC3", Sony "S-Log3.Cine") the display column keeps their
// spelling; only the bare ones gain a vendor prefix.
//
// Keep the canonical column in sync with upstream's LOG_SPACES keys (14 curves).
// Row order is irrelevant: the Rust caller sorts for a stable menu, and the
// vendor prefixes make that sort group by vendor as a side effect.
// ---------------------------------------------------------------------------
namespace {
struct LogSpaceAlias {
  const char* canonical;
  const char* display;
};

const LogSpaceAlias kLogSpaceAliases[] = {
    {"F-Log", "FUJIFILM F-Log"},
    {"F-Log2", "FUJIFILM F-Log2"},
    {"F-Log2C", "FUJIFILM F-Log2 C"},
    {"V-Log", "Panasonic V-Log"},
    {"N-Log", "Nikon N-Log"},
    {"L-Log", "Leica L-Log"},
    {"Canon Log 2", "Canon Log 2"},
    {"Canon Log 3", "Canon Log 3"},
    {"S-Log3", "Sony S-Log3"},
    {"S-Log3.Cine", "Sony S-Log3.Cine"},
    {"Arri LogC3", "ARRI LogC3"},
    {"Arri LogC4", "ARRI LogC4"},
    {"Log3G10", "RED Log3G10"},
    {"D-Log", "DJI D-Log"},
};

// Resolve whatever the caller sent to the upstream canonical key, or nullptr
// when it is neither a known display name nor a known canonical one.
//
// Display first, canonical second: the menu's own vocabulary always wins, and
// the canonical fallback keeps values that predate the aliasing working —
// persisted selections from an older build, the raw upstream spelling used
// directly over FFI, and callers that never went through `log_spaces()`.
const char* resolve_log_space(const std::string& name) {
  for (const LogSpaceAlias& alias : kLogSpaceAliases) {
    if (name == alias.display) {
      return alias.canonical;
    }
  }
  for (const LogSpaceAlias& alias : kLogSpaceAliases) {
    if (name == alias.canonical) {
      return alias.canonical;
    }
  }
  return nullptr;
}
}  // namespace

rust::Vec<float> grade(rust::Slice<const float> data,
                       uint32_t width,
                       uint32_t height,
                       rust::Str log_space,
                       rust::Str lut_path,
                       rust::Str metering_mode,
                       float gain,
                       float target_gray,
                       int32_t enable_boost,
                       float saturation,
                       float contrast,
                       float pivot) {
  using namespace rawalchemy;

  const size_t n = static_cast<size_t>(width) * height * 3u;
  if (data.size() != n) {
    throw std::runtime_error("grade: input length != width*height*3");
  }

  ImageBuffer buf(static_cast<int>(width), static_cast<int>(height), 3);
  std::copy(data.data(), data.data() + static_cast<std::ptrdiff_t>(n), buf.data.begin());

  GradingParams p;

  // --- stage 3+4: gamut transform + log encode (optional) ---
  // An empty name mirrors the upstream C API's null `logSpace`: `logSpaceInfo`
  // keeps its nullptr default and `applyGradingFused` then skips BOTH the gamut
  // matrix and the log OETF (`doGamut = (logSpaceInfo != nullptr)`).
  //
  // The name arrives from Kotlin as a *display* name ("FUJIFILM F-Log2 C") and is
  // translated back to the canonical key upstream's table is keyed by ("F-Log2C")
  // right here, so `LOG_SPACES` — and therefore the submodule — stays untouched.
  std::string ls(log_space.data(), log_space.size());
  if (!ls.empty()) {
    const char* canonical = resolve_log_space(ls);
    if (canonical == nullptr) {
      throw std::runtime_error("grade: unknown log space '" + ls + "'");
    }
    auto it = LOG_SPACES.find(canonical);
    if (it == LOG_SPACES.end()) {
      // The alias table lists a curve upstream does not ship: our table drifted.
      throw std::runtime_error("grade: log space '" + ls +
                               "' maps to '" + canonical +
                               "', which upstream does not provide");
    }
    p.logSpaceInfo = &it->second;
  }

  // --- stage 5: optional 3D LUT ---
  // loadCubeLUT throws std::runtime_error on a bad file; the bridge is declared
  // `Result<..>`, so cxx catches it and hands Rust `Err(cxx::Exception)`.
  LUT3D lut;
  std::string lp(lut_path.data(), lut_path.size());
  if (!lp.empty()) {
    lut = loadCubeLUT(lp);
    p.lut = &lut;
  }

  // --- stage 1: gain, in upstream's own shape: metered base × multiplier ---
  // Upstream's file-decoding C API does `gp.gain = computeAutoGain(img, mode) *
  // 2^evOffset` — a metered base times a plain multiplier. We expose exactly that
  // shape, except the multiplier is upstream's raw `GradingParams.gain` value with
  // no EV reinterpretation of our own, and the base is only metered when asked.
  //
  // This is NOT the front end's develop exposure. `DevelopParams.exposure_ev` is
  // applied by rawler to the single-channel mosaic *before* demosaic and never
  // reaches here; leaving both of these unset keeps the exposure exactly as
  // developed (`FOTLAB-RAWLER-000006`).
  std::string mm(metering_mode.data(), metering_mode.size());
  const bool metered = !mm.empty();
  if (metered && !isMeteringModeSupported(mm)) {
    throw std::runtime_error("grade: unsupported metering mode '" + mm + "'");
  }
  // Unity stands in for whichever side the caller left out, so an unset multiplier
  // never overrides anything and an unmetered `gain` is simply the multiplier.
  if (metered || !is_unset(gain)) {
    const float base_gain =
        metered ? computeAutoGain(buf, mm, is_unset(target_gray) ? 0.18f : target_gray) : 1.0f;
    p.gain = base_gain * (is_unset(gain) ? 1.0f : gain);
  }
  // Both unset: `p.gain` keeps the upstream default, i.e. exposure is not touched.

  // --- stage 2: saturation / contrast boost (optional overrides) ---
  if (enable_boost != kBoostUnset) {
    p.enableBoost = (enable_boost != 0);
  }
  if (!is_unset(saturation)) p.saturation = saturation;
  if (!is_unset(contrast)) p.contrast = contrast;
  if (!is_unset(pivot)) p.pivot = pivot;

  applyGradingFused(buf, p);

  rust::Vec<float> out;
  out.reserve(buf.data.size());
  for (float v : buf.data) {
    out.push_back(v);
  }
  return out;
}

// Enumerate the log spaces the grader accepts, as DISPLAY names.
//
// Pulled straight out of `kLogSpaceAliases` (see the top of this file) so the
// list the UI renders and the lookup `grade` performs can never disagree: one
// table, read in two directions.
//
// The Rust caller sorts for a stable UI menu, so order here is irrelevant.
rust::Vec<rust::String> log_spaces() {
  rust::Vec<rust::String> out;
  out.reserve(sizeof(kLogSpaceAliases) / sizeof(kLogSpaceAliases[0]));
  for (const LogSpaceAlias& alias : kLogSpaceAliases) {
    out.push_back(rust::String(alias.display));
  }
  return out;
}
