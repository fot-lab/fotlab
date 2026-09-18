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
  std::string ls(log_space.data(), log_space.size());
  if (!ls.empty()) {
    auto it = LOG_SPACES.find(ls);
    if (it == LOG_SPACES.end()) {
      throw std::runtime_error("grade: unknown log space '" + ls + "'");
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

// Enumerate upstream's LOG_SPACES keys verbatim. Order is whatever the
// unordered_map yields; the Rust caller sorts for a stable UI menu. The map
// itself is the single source of truth — a curve added upstream appears here
// with no glue change.
rust::Vec<rust::String> log_spaces() {
  rust::Vec<rust::String> out;
  out.reserve(LOG_SPACES.size());
  for (const auto& entry : LOG_SPACES) {
    out.push_back(rust::String(entry.first));
  }
  return out;
}
