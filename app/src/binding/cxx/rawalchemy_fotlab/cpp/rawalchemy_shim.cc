#include "rust/cxx.h"
#include "cxxbridge.h"

#include "grading_fused.h"
#include "color_data.h"
#include "lut_applier.h"

#include <cmath>
#include <algorithm>
#include <stdexcept>
#include <string>

// Implemented for the `grade` declared in cxxbridge.h (global namespace, the
// default cxx bridge namespace). Mirrors the parameter assembly that
// RawAlchemyCpp keeps in an anonymous-namespace helper inside its file-decoding
// C API, but driven by an already-decoded linear ProPhoto-D50 buffer.
rust::Vec<float> grade(rust::Slice<const float> data,
                       uint32_t width,
                       uint32_t height,
                       rust::Str log_space,
                       rust::Str lut_path,
                       float ev_offset) {
  using namespace rawalchemy;

  const size_t n = static_cast<size_t>(width) * height * 3u;
  if (data.size() != n) {
    throw std::runtime_error("grade: input length != width*height*3");
  }

  ImageBuffer buf(static_cast<int>(width), static_cast<int>(height), 3);
  std::copy(data.data(), data.data() + static_cast<std::ptrdiff_t>(n), buf.data.begin());

  // LOG_SPACES: unordered_map<string, LogSpaceInfo>. Resolve the requested log
  // space by name (e.g. "F-Log", "S-Log3", "Arri LogC4").
  std::string ls(log_space.data(), log_space.size());
  auto it = LOG_SPACES.find(ls);
  if (it == LOG_SPACES.end()) {
    throw std::runtime_error("grade: unknown log space '" + ls + "'");
  }
  const LogSpaceInfo& info = it->second;

  // Optional 3D LUT ("" => none). loadCubeLUT throws std::runtime_error on a
  // bad file; cxx converts it into a Rust `Err(String)` at the FFI boundary.
  LUT3D lut;
  std::string lp(lut_path.data(), lut_path.size());
  if (!lp.empty()) {
    lut = loadCubeLUT(lp);
  }

  GradingParams p;
  // rawler already applied as-shot exposure, so this is a *relative* tweak.
  p.gain = std::pow(2.0f, ev_offset);
  p.enableBoost = true;
  p.saturation  = 1.25f;
  p.contrast    = 1.10f;
  p.pivot       = 0.18f;
  p.logSpaceInfo = &info;
  p.lut = lut.empty() ? nullptr : &lut;

  applyGradingFused(buf, p);

  rust::Vec<float> out;
  out.reserve(buf.data.size());
  for (float v : buf.data) {
    out.push_back(v);
  }
  return out;
}
