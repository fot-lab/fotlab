// ===========================================================================
// rawtherapee_fotlab — C++ shim implementing DCP/LCP parse + LCP CFA apply.
//
// Thin C++ adapter between the cxx-generated Rust ABI and RawTherapee's
// DCPProfile / LCPProfile / LCPMapper. Mirrors rt_demosaic_shim.cc: validate,
// call RT inside try/catch, propagate errors via an err buffer + i32 rc.
//
// Requires (out-of-band, header-only — no librtengine.a rebuild):
//   dcp.h  : getHasColorMatrix1/2, getHasForwardMatrix1/2, getColorMatrix1/2,
//            getForwardMatrix1/2, getBaselineExposureOffsetValue
//   lcp.h  : getProfileName, getCamera, getLens, getIsRaw, getIsFisheye,
//            getSensorFormatFactor, getPersModelCount
// Plus the demosaic hooks documented in README.md (so librtengine links).
// ===========================================================================

#include "rt_deprofile_shim.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <vector>

#include <glibmm/ustring.h>

#include "dcp.h"
#include "lcp.h"
#include "procparams.h"

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
        rtengine::DCPProfile prof(Glib::ustring(path.c_str()));
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
                     int32_t& pers_model_count, rust::Slice<uint8_t> err) {
    char ebuf[256];
    ebuf[0] = '\0';
    try {
        auto prof = std::make_shared<rtengine::LCPProfile>(Glib::ustring(path.c_str()));

        auto append = [](rust::Vec<uint8_t>& v, const Glib::ustring& s) {
            const char* p = s.c_str();
            for (const char* q = p; *q; ++q) v.push_back(static_cast<uint8_t>(*q));
        };
        append(profile_name, prof->getProfileName());
        append(camera, prof->getCamera());
        append(lens, prof->getLens());
        is_raw = prof->getIsRaw();
        is_fisheye = prof->getIsFisheye();
        sensor_format_factor = prof->getSensorFormatFactor();
        pers_model_count = prof->getPersModelCount();
        return 0;
    } catch (const std::exception& e) {
        std::snprintf(ebuf, sizeof(ebuf), "LCP parse threw: %s", e.what());
    } catch (...) {
        std::snprintf(ebuf, sizeof(ebuf), "LCP parse threw unknown exception");
    }
    set_err(err, ebuf);
    return -1;
}

int32_t rt_apply_lcp_cfa(const std::string& path, float focal_length,
                         float focal_length_35mm, float focus_dist, float aperture,
                         bool vignette, bool distortion, int32_t raw_rotation_deg,
                         int32_t w, int32_t h, rust::Slice<float> pixels,
                         rust::Slice<uint8_t> err) {
    char ebuf[256];
    ebuf[0] = '\0';
    try {
        if (w <= 0 || h <= 0 ||
            static_cast<size_t>(w) * static_cast<size_t>(h) > pixels.size()) {
            std::snprintf(ebuf, sizeof(ebuf), "rt_apply_lcp_cfa: bad buffer %dx%d size %zu",
                          w, h, pixels.size());
            set_err(err, ebuf);
            return -1;
        }

        std::shared_ptr<rtengine::LCPProfile> pProf(
            new rtengine::LCPProfile(Glib::ustring(path.c_str())));

        rtengine::procparams::CoarseTransformParams coarse;
        coarse.rotate = 0;
        coarse.hflip = false;
        coarse.vflip = false;

        // useCADistP = false => CA is skipped; only vignette/distortion are built.
        rtengine::LCPMapper mapper(pProf, focal_length, focal_length_35mm, focus_dist,
                                   aperture, vignette, /* useCADistP = */ false, w, h,
                                   coarse, raw_rotation_deg);

        float* data = pixels.data();
        const int cw = w / 2;
        const int ch = h / 2;

        if (vignette) {
            std::vector<float*> rows;
            rows.reserve(h);
            for (int y = 0; y < h; ++y)
                rows.push_back(data + static_cast<size_t>(y) * w);
            mapper.processVignette(w, h, rows.data());
        }

        if (distortion) {
            // Warp the CFA using RT's distortion model. RT's `correctDistortion`
            // maps one coordinate frame to the other; we treat it as the forward
            // map and INVERT it to render the corrected buffer from the raw CFA.
            // The model is centrosymmetric about the optical axis, so we build a
            // 1-D radius LUT (input radius -> output radius) by sampling on the
            // +x axis, then invert it per output pixel. This is robust to whether
            // RT's helper is the forward or backward mapping, and uses
            // correctDistortion purely as a black box (no private coefficients).
            const int N = 1024;
            const double maxR =
                std::sqrt(static_cast<double>(w) * w + static_cast<double>(h) * h) * 0.5;
            std::vector<double> lut_r(N + 1), lut_R(N + 1);
            for (int k = 0; k <= N; ++k) {
                double r = static_cast<double>(k) / N * maxR;
                double ox = cw + r, oy = ch;  // raw pixel on +x axis at radius r
                mapper.correctDistortion(ox, oy, cw, ch);
                lut_r[k] = r;
                lut_R[k] = std::sqrt((ox - cw) * (ox - cw) + (oy - ch) * (oy - ch));
            }
            auto invRadius = [&](double R) -> double {
                if (R <= lut_R[0]) return lut_r[0];
                if (R >= lut_R[N]) return lut_r[N];
                int lo = 0, hi = N;
                while (hi - lo > 1) {
                    int mid = (lo + hi) / 2;
                    if (lut_R[mid] < R) lo = mid;
                    else hi = mid;
                }
                double t = (R - lut_R[lo]) / (lut_R[hi] - lut_R[lo] + 1e-12);
                return lut_r[lo] + t * (lut_r[hi] - lut_r[lo]);
            };

            std::vector<float> tmp(data, data + static_cast<size_t>(w) * h);
            auto sample = [&](double sx, double sy) -> float {
                if (sx < 0) sx = 0;
                if (sx > w - 1) sx = w - 1;
                if (sy < 0) sy = 0;
                if (sy > h - 1) sy = h - 1;
                int x0 = static_cast<int>(std::floor(sx));
                int y0 = static_cast<int>(std::floor(sy));
                int x1 = x0 + 1 < w ? x0 + 1 : x0;
                int y1 = y0 + 1 < h ? y0 + 1 : y0;
                double fx = sx - x0;
                double fy = sy - y0;
                float v00 = tmp[static_cast<size_t>(y0) * w + x0];
                float v01 = tmp[static_cast<size_t>(y0) * w + x1];
                float v10 = tmp[static_cast<size_t>(y1) * w + x0];
                float v11 = tmp[static_cast<size_t>(y1) * w + x1];
                double top = v00 * (1.0 - fx) + v01 * fx;
                double bot = v10 * (1.0 - fx) + v11 * fx;
                return static_cast<float>(top * (1.0 - fy) + bot * fy);
            };
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    double X = x - cw, Y = y - ch;
                    double R = std::sqrt(X * X + Y * Y);
                    double scale = (R > 1e-6) ? (invRadius(R) / R) : 0.0;
                    double sx = cw + X * scale;
                    double sy = ch + Y * scale;
                    data[static_cast<size_t>(y) * w + x] = sample(sx, sy);
                }
            }
        }
        return 0;
    } catch (const std::exception& e) {
        std::snprintf(ebuf, sizeof(ebuf), "LCP apply threw: %s", e.what());
    } catch (...) {
        std::snprintf(ebuf, sizeof(ebuf), "LCP apply threw unknown exception");
    }
    set_err(err, ebuf);
    return -1;
}
