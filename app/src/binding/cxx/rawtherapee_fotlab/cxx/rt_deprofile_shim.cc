// ===========================================================================
// rawtherapee_fotlab — C++ shim implementing DCP/LCP *parse* + LCP model
// *decode/interp* (vendored RawTherapee decode only).
//
// This shim is the thin C++ adapter between the cxx-generated Rust ABI and
// RawTherapee's vendored DCPProfile / LCPProfile. It only constructs those
// objects and reads their getters / runs calcParams + prepareParams. The heavy
// binary / XML decode lives in the vendored dcp.cc / lcp.cc; the *apply*
// (vignette / distortion) is re-implemented in Rust (src/lcp.rs) for our
// single-channel CFA pipeline, so this file contains NO apply code and links
// NO librtengine / glibmm / expat — only the self-contained vendored parsers
// plus our expat_minimal. See README.md for the licensing note.
// ===========================================================================

#include "rt_deprofile_shim.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <vector>

#include "dcp.h"
#include "lcp.h"

namespace {

void set_err(rust::Slice<uint8_t> err, const char* msg) {
    if (err.size() == 0) return;
    size_t n = std::strlen(msg);
    if (n >= err.size()) n = err.size() - 1;
    std::memcpy(err.data(), msg, n);
    err.data()[n] = '\0';
}

}  // namespace

int32_t rt_parse_dcp(const std::string& path,
                     rust::Vec<double>& cm1, rust::Vec<double>& cm2,
                     rust::Vec<double>& fm1, rust::Vec<double>& fm2,
                     bool& has_cm1, bool& has_cm2, bool& has_fm1, bool& has_fm2,
                     bool& will_interp, double& temp1, double& temp2, double& baseline,
                     int16_t& light1, int16_t& light2, bool& has_tone, bool& has_look,
                     bool& has_huesat, bool& has_baseline, rust::Slice<uint8_t> err) {
    char ebuf[256];
    ebuf[0] = '\0';
    try {
        rtengine::DCPProfile prof(path);
        if (!prof) {
            std::snprintf(ebuf, sizeof(ebuf), "DCPProfile: invalid or unreadable file");
            set_err(err, ebuf);
            return -1;
        }

        auto fill = [&](const rtengine::DCPProfile::Matrix& m, rust::Vec<double>& v) {
            for (int i = 0; i < 3; ++i)
                for (int j = 0; j < 3; ++j) v.push_back(m[i][j]);
        };
        fill(prof.getColorMatrix1(), cm1);
        fill(prof.getColorMatrix2(), cm2);
        fill(prof.getForwardMatrix1(), fm1);
        fill(prof.getForwardMatrix2(), fm2);

        has_cm1 = prof.getHasColorMatrix1();
        has_cm2 = prof.getHasColorMatrix2();
        has_fm1 = prof.getHasForwardMatrix1();
        has_fm2 = prof.getHasForwardMatrix2();

        rtengine::DCPProfile::Illuminants ill = prof.getIlluminants();
        temp1 = ill.temperature_1;
        temp2 = ill.temperature_2;
        light1 = ill.light_source_1;
        light2 = ill.light_source_2;
        will_interp = ill.will_interpolate;

        baseline = prof.getBaselineExposureOffsetValue();
        has_tone = prof.getHasToneCurve();
        has_look = prof.getHasLookTable();
        has_huesat = prof.getHasHueSatMap();
        has_baseline = prof.getHasBaselineExposureOffset();
        return 0;
    } catch (const std::exception& e) {
        std::snprintf(ebuf, sizeof(ebuf), "DCP parse threw: %s", e.what());
    } catch (...) {
        std::snprintf(ebuf, sizeof(ebuf), "DCP parse threw unknown exception");
    }
    set_err(err, ebuf);
    return -1;
}

int32_t rt_parse_lcp(const std::string& path, rust::Vec<uint8_t>& profile_name,
                     rust::Vec<uint8_t>& camera, rust::Vec<uint8_t>& lens, bool& is_raw,
                     bool& is_fisheye, float& sensor_format_factor,
                     int32_t& pers_model_count, float& focal_length,
                     rust::Slice<uint8_t> err) {
    char ebuf[256];
    ebuf[0] = '\0';
    try {
        rtengine::LCPProfile prof(path);

        auto append = [](rust::Vec<uint8_t>& v, const std::string& s) {
            for (char c : s) v.push_back(static_cast<uint8_t>(c));
        };
        append(profile_name, prof.getProfileName());
        append(camera, prof.getCamera());
        append(lens, prof.getLens());
        is_raw = prof.getIsRaw();
        is_fisheye = prof.getIsFisheye();
        sensor_format_factor = prof.getSensorFormatFactor();
        pers_model_count = prof.getPersModelCount();
        focal_length = prof.getLcpFocalLength();
        return 0;
    } catch (const std::exception& e) {
        std::snprintf(ebuf, sizeof(ebuf), "LCP parse threw: %s", e.what());
    } catch (...) {
        std::snprintf(ebuf, sizeof(ebuf), "LCP parse threw unknown exception");
    }
    set_err(err, ebuf);
    return -1;
}

int32_t rt_compute_lcp_model(const std::string& path,
                             float focal_length, float focal_length_35mm,
                             float focus_dist, float aperture,
                             int32_t raw_rotation_deg, int32_t w, int32_t h,
                             rust::Vec<float>& model, bool& is_fisheye, bool& swap_xy,
                             rust::Slice<uint8_t> err) {
    char ebuf[256];
    ebuf[0] = '\0';
    try {
        // LCPProfile has no operator bool (unlike DCPProfile); a malformed file
        // makes the constructor throw, which the surrounding try/catch handles.
        rtengine::LCPProfile prof(path);

        // Mirror RawTherapee's LCPMapper rotation handling: coarse.rotate == 0 and
        // no crop mirror, so only rawRotationDeg drives the swap / mirror flags
        // (see rtengine/lcp.cc LCPMapper ctor).
        const int rot = ((raw_rotation_deg % 360) + 360) % 360;
        const bool swapXY = (rot == 90 || rot == 270);
        const bool mirrorX = (rot == 90 || rot == 180);
        const bool mirrorY = (rot == 180 || rot == 270);

        // Vignette model: interpolate between frames + prepare for this geometry.
        rtengine::LCPModelCommon mcV;
        prof.calcParams(rtengine::LCPCorrectionMode::VIGNETTE, focal_length, focus_dist,
                        aperture, &mcV, nullptr, nullptr);
        mcV.prepareParams(w, h, focal_length, focal_length_35mm,
                          prof.getSensorFormatFactor(), swapXY, mirrorX, mirrorY);

        // Distortion model: interpolate (base perspective) + prepare.
        rtengine::LCPModelCommon mcD;
        prof.calcParams(rtengine::LCPCorrectionMode::DISTORTION, focal_length, focus_dist,
                        aperture, &mcD, nullptr, nullptr);
        mcD.prepareParams(w, h, focal_length, focal_length_35mm,
                          prof.getSensorFormatFactor(), swapXY, mirrorX, mirrorY);

        // Pack (13 floats) — Rust derives rfx = 1/fx, rfy = 1/fy:
        //   x0, y0, fx, fy, vign0..3, dist0..4
        auto push = [&](float v) { model.push_back(v); };
        push(mcV.x0);
        push(mcV.y0);
        push(mcV.fx);
        push(mcV.fy);
        push(mcV.vign_param[0]);
        push(mcV.vign_param[1]);
        push(mcV.vign_param[2]);
        push(mcV.vign_param[3]);
        push(mcD.param[0]);
        push(mcD.param[1]);
        push(mcD.param[2]);
        push(mcD.param[3]);
        push(mcD.param[4]);

        is_fisheye = prof.getIsFisheye();
        swap_xy = swapXY;
        return 0;
    } catch (const std::exception& e) {
        std::snprintf(ebuf, sizeof(ebuf), "LCP model threw: %s", e.what());
    } catch (...) {
        std::snprintf(ebuf, sizeof(ebuf), "LCP model threw unknown exception");
    }
    set_err(err, ebuf);
    return -1;
}
