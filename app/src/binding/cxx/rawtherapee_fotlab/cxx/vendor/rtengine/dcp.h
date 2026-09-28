/*
*  This file is part of RawTherapee.
*
*  Copyright (c) 2012 Oliver Duis <www.oliverduis.de>
*
*  RawTherapee is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation, either version 3 of the License, or
*  (at your option) any later version.
*
*  RawTherapee is distributed in the hope that it will be useful,
*  but WITHOUT ANY WARRANTY; without even the implied warranty of
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*  GNU General Public License for more details.
*
*  You should have received a copy of the GNU General Public License
*  along with RawTherapee.  If not, see <https://www.gnu.org/licenses/>.
*/

// fotlab: this is a trimmed copy of RawTherapee's dcp.h. The apply / tone-curve
// machinery and the DCPStore singleton have been removed; only the DCP *decode*
// (constructor + inline getters) is kept. Glib::ustring has been replaced with
// std::string so glibmm is not required.

#pragma once

#include <array>
#include <string>
#include <vector>

namespace rtengine
{

class DCPProfile final
{
public:
    struct Illuminants {
        short light_source_1;
        short light_source_2;
        double temperature_1;
        double temperature_2;
        bool will_interpolate;
    };

    using Triple = std::array<double, 3>;
    using Matrix = std::array<Triple, 3>;

    explicit DCPProfile(const std::string& filename);
    ~DCPProfile();

    explicit operator bool() const;

    bool getHasToneCurve() const;
    bool getHasLookTable() const;
    bool getHasHueSatMap() const;
    bool getHasBaselineExposureOffset() const;

    Illuminants getIlluminants() const;

    // --- fotlab inline getters (decode only) ---
    bool getHasColorMatrix1() const { return has_color_matrix_1; }
    bool getHasColorMatrix2() const { return has_color_matrix_2; }
    bool getHasForwardMatrix1() const { return has_forward_matrix_1; }
    bool getHasForwardMatrix2() const { return has_forward_matrix_2; }
    const Matrix& getColorMatrix1() const { return color_matrix_1; }
    const Matrix& getColorMatrix2() const { return color_matrix_2; }
    const Matrix& getForwardMatrix1() const { return forward_matrix_1; }
    const Matrix& getForwardMatrix2() const { return forward_matrix_2; }
    double getBaselineExposureOffsetValue() const { return baseline_exposure_offset; }

private:
    struct HsbModify {
        float hue_shift;
        float sat_scale;
        float val_scale;
    };

    struct HsdTableInfo {
        int hue_divisions;
        int sat_divisions;
        int val_divisions;
        int hue_step;
        int val_step;
        unsigned int array_count;
        bool srgb_gamma;
        struct {
            float h_scale;
            float s_scale;
            float v_scale;
            int max_hue_index0;
            int max_sat_index0;
            int max_val_index0;
            int hue_step;
            int val_step;
        } pc;
    };

    Matrix color_matrix_1;
    Matrix color_matrix_2;
    bool has_color_matrix_1;
    bool has_color_matrix_2;
    bool has_forward_matrix_1;
    bool has_forward_matrix_2;
    bool has_tone_curve;
    bool has_baseline_exposure_offset;
    bool will_interpolate;
    bool valid;
    Matrix forward_matrix_1;
    Matrix forward_matrix_2;
    double temperature_1;
    double temperature_2;
    double baseline_exposure_offset;
    std::vector<HsbModify> deltas_1;
    std::vector<HsbModify> deltas_2;
    std::vector<HsbModify> look_table;
    HsdTableInfo delta_info;
    HsdTableInfo look_info;
    short light_source_1;
    short light_source_2;
};

}
