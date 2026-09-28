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

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <iostream>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "dcp.h"

using namespace rtengine;

namespace
{

// This sRGB gamma is taken from DNG reference code, with the added linear extension past 1.0, as we run clipless here


double calibrationIlluminantToTemperature(int light)
{
    enum class LightSource {
        UNKNOWN = 0,
        DAYLIGHT = 1,
        FLUORESCENT = 2,
        TUNGSTEN = 3,
        FLASH = 4,
        FINE_WEATHER = 9,
        CLOUDY_WEATHER = 10,
        SHADE = 11,
        DAYLIGHT_FLUORESCENT = 12, // D  5700 - 7100K
        DAYWHITE_FLUORESCENT = 13, // N  4600 - 5500K
        COOL_WHITE_FLUORESCENT = 14, // W  3800 - 4500K
        WHITE_FLUORESCENT = 15, // WW 3250 - 3800K
        WARM_WHITE_FLUORESCENT = 16, // L  2600 - 3250K
        STANDARD_LIGHT_A = 17,
        STANDARD_LIGHT_B = 18,
        STANDARD_LIGHT_C = 19,
        D55 = 20,
        D65 = 21,
        D75 = 22,
        D50 = 23,
        ISO_STUDIO_TUNGSTEN = 24,
        OTHER = 255
    };

    // These temperatures are those found in DNG SDK reference code
    switch (LightSource(light)) {
        case LightSource::STANDARD_LIGHT_A:
        case LightSource::TUNGSTEN: {
            return 2850.0;
        }

        case LightSource::ISO_STUDIO_TUNGSTEN: {
            return 3200.0;
        }

        case LightSource::D50: {
            return 5000.0;
        }

        case LightSource::D55:
        case LightSource::DAYLIGHT:
        case LightSource::FINE_WEATHER:
        case LightSource::FLASH:
        case LightSource::STANDARD_LIGHT_B: {
            return 5500.0;
        }

        case LightSource::D65:
        case LightSource::STANDARD_LIGHT_C:
        case LightSource::CLOUDY_WEATHER: {
            return 6500.0;
        }

        case LightSource::D75:
        case LightSource::SHADE: {
            return 7500.0;
        }

        case LightSource::DAYLIGHT_FLUORESCENT: {
            return (5700.0 + 7100.0) * 0.5;
        }

        case LightSource::DAYWHITE_FLUORESCENT: {
            return (4600.0 + 5500.0) * 0.5;
        }

        case LightSource::COOL_WHITE_FLUORESCENT:
        case LightSource::FLUORESCENT: {
            return (3800.0 + 4500.0) * 0.5;
        }

        case LightSource::WHITE_FLUORESCENT: {
            return (3250.0 + 3800.0) * 0.5;
        }

        case LightSource::WARM_WHITE_FLUORESCENT: {
            return (2600.0 + 3250.0) * 0.5;
        }

        default: {
            return 0.0;
        }
    }
}

double xyCoordToTemperature(const std::array<double, 2>& white_xy)
{
    struct Ruvt {
        double r;
        double u;
        double v;
        double t;
    };

    static const Ruvt temp_table[] = {
        {   0, 0.18006, 0.26352, -0.24341 },
        {  10, 0.18066, 0.26589, -0.25479 },
        {  20, 0.18133, 0.26846, -0.26876 },
        {  30, 0.18208, 0.27119, -0.28539 },
        {  40, 0.18293, 0.27407, -0.30470 },
        {  50, 0.18388, 0.27709, -0.32675 },
        {  60, 0.18494, 0.28021, -0.35156 },
        {  70, 0.18611, 0.28342, -0.37915 },
        {  80, 0.18740, 0.28668, -0.40955 },
        {  90, 0.18880, 0.28997, -0.44278 },
        { 100, 0.19032, 0.29326, -0.47888 },
        { 125, 0.19462, 0.30141, -0.58204 },
        { 150, 0.19962, 0.30921, -0.70471 },
        { 175, 0.20525, 0.31647, -0.84901 },
        { 200, 0.21142, 0.32312, -1.0182 },
        { 225, 0.21807, 0.32909, -1.2168 },
        { 250, 0.22511, 0.33439, -1.4512 },
        { 275, 0.23247, 0.33904, -1.7298 },
        { 300, 0.24010, 0.34308, -2.0637 },
        { 325, 0.24702, 0.34655, -2.4681 },
        { 350, 0.25591, 0.34951, -2.9641 },
        { 375, 0.26400, 0.35200, -3.5814 },
        { 400, 0.27218, 0.35407, -4.3633 },
        { 425, 0.28039, 0.35577, -5.3762 },
        { 450, 0.28863, 0.35714, -6.7262 },
        { 475, 0.29685, 0.35823, -8.5955 },
        { 500, 0.30505, 0.35907, -11.324 },
        { 525, 0.31320, 0.35968, -15.628 },
        { 550, 0.32129, 0.36011, -23.325 },
        { 575, 0.32931, 0.36038, -40.770 },
        { 600, 0.33724, 0.36051, -116.45 }
    };

    double res = 0;

    // Convert to uv space.
    double u = 2.0 * white_xy[0] / (1.5 - white_xy[0] + 6.0 * white_xy[1]);
    double v = 3.0 * white_xy[1] / (1.5 - white_xy[0] + 6.0 * white_xy[1]);

    // Search for line pair coordinate is between.
    double last_dt = 0.0;

    for (uint32_t index = 1; index <= 30; ++index) {
        // Convert slope to delta-u and delta-v, with length 1.
        double du = 1.0;
        double dv = temp_table[index].t;
        double len = sqrt(1.0 + dv * dv);
        du /= len;
        dv /= len;

        // Find delta from black body point to test coordinate.
        double uu = u - temp_table[index].u;
        double vv = v - temp_table[index].v;

        // Find distance above or below line.
        double dt = -uu * dv + vv * du;

        // If below line, we have found line pair.
        if (dt <= 0.0 || index == 30) {
            // Find fractional weight of two lines.
            if (dt > 0.0) {
                dt = 0.0;
            }

            dt = -dt;

            double f;

            if (index == 1) {
                f = 0.0;
            } else {
                f = dt / (last_dt + dt);
            }

            // Interpolate the temperature.
            res = 1.0e6 / (temp_table[index - 1].r * f + temp_table[index].r * (1.0 - f));
            break;
        }

        // Try next line pair.
        last_dt = dt;
    }

    return res;
}

class DCPMetadata
{
private:
    enum TagType {
        INVALID = 0,
        BYTE = 1,
        ASCII = 2,
        SHORT = 3,
        LONG = 4,
        RATIONAL = 5,
        SBYTE = 6,
        UNDEFINED = 7,
        SSHORT = 8,
        SLONG = 9,
        SRATIONAL = 10,
        FLOAT = 11,
        DOUBLE = 12
    };

    enum ByteOrder {
        UNKNOWN = 0,
        INTEL = 0x4949,
        MOTOROLA = 0x4D4D
    };

public:
    explicit DCPMetadata(FILE *file) :
        file_(file),
        order_(UNKNOWN)
    {
    }

    bool parse()
    {
        if (!file_) {
#ifndef NDEBUG
            std::cerr << "ERROR: No file opened." << std::endl;
#endif
            return false;
        }

        setlocale(LC_NUMERIC, "C"); // to set decimal point in sscanf

        // read tiff header
        std::fseek(file_, 0, SEEK_SET);
        std::uint16_t bo;
        std::fread(&bo, 1, 2, file_);
        order_ = ByteOrder(bo);

        get2(); // Skip

        // Seek to IFD
        const std::size_t offset = get4();
        std::fseek(file_, offset, SEEK_SET);

        // First read the IFD directory
        const std::uint16_t numtags = get2();

        if (numtags > 1000) { // KodakIfd has lots of tags, thus 1000 as the limit
            return false;
        }

        for (std::uint16_t i = 0; i < numtags; ++i) {
            Tag tag;
            if (parseTag(tag)) {
                tags_[tag.id] = std::move(tag);
            }
        }

        return true;
    }

    bool find(int id) const
    {
        return tags_.find(id) != tags_.end();
    }

    std::string toString(int id) const
    {
        const Tags::const_iterator tag = tags_.find(id);
        if (tag != tags_.end()) {
            if (tag->second.type == ASCII) {
                return std::string(tag->second.value.begin(), tag->second.value.end()).c_str();
            }
        }
        return {};
    }

    std::int32_t toInt(int id, std::size_t offset = 0, TagType as_type = INVALID) const
    {
        const Tags::const_iterator tag = tags_.find(id);
        if (tag == tags_.end()) {
            return 0;
        }

        if (as_type == INVALID) {
            as_type = tag->second.type;
        }

        switch (as_type) {
            case SBYTE: {
                if (offset < tag->second.value.size()) {
                    return static_cast<signed char>(tag->second.value[offset]);
                }
                return 0;
            }

            case BYTE: {
                if (offset < tag->second.value.size()) {
                    return tag->second.value[offset];
                }
                return 0;
            }

            case SSHORT: {
                if (offset + 1 < tag->second.value.size()) {
                    return static_cast<std::int16_t>(sget2(tag->second.value.data() + offset));
                }
                return 0;
            }

            case SHORT: {
                if (offset + 1 < tag->second.value.size()) {
                    return sget2(tag->second.value.data() + offset);
                }
                return 0;
            }

            case SLONG:
            case LONG: {
                if (offset + 3 < tag->second.value.size()) {
                    return sget4(tag->second.value.data() + offset);
                }
                return 0;
            }

            case SRATIONAL:
            case RATIONAL: {
                if (offset + 7 < tag->second.value.size()) {
                    const std::uint32_t denominator = sget4(tag->second.value.data() + offset + 4);
                    return
                        denominator == 0
                            ? 0
                            : static_cast<std::int32_t>(sget4(tag->second.value.data() + offset)) / denominator;
                }
                return 0;
            }

            case FLOAT: {
                return toDouble(id, offset);
            }

            default: {
                return 0;
            }
        }
    }

    int toShort(int id, std::size_t offset = 0) const
    {
        return toInt(id, offset, SHORT);
    }

    double toDouble(int id, std::size_t offset = 0) const
    {
        const Tags::const_iterator tag = tags_.find(id);
        if (tag == tags_.end()) {
            return 0.0;
        }

        switch (tag->second.type) {
            case SBYTE: {
                if (offset < tag->second.value.size()) {
                    return static_cast<signed char>(tag->second.value[offset]);
                }
                return 0.0;
            }

            case BYTE: {
                if (offset < tag->second.value.size()) {
                    return tag->second.value[offset];
                }
                return 0.0;
            }

            case SSHORT: {
                if (offset + 1 < tag->second.value.size()) {
                    return static_cast<std::int16_t>(sget2(tag->second.value.data() + offset));
                }
                return 0.0;
            }

            case SHORT: {
                if (offset + 1 < tag->second.value.size()) {
                    return sget2(tag->second.value.data() + offset);
                }
                return 0.0;
            }

            case SLONG:
            case LONG: {
                if (offset + 3 < tag->second.value.size()) {
                    return sget4(tag->second.value.data() + offset);
                }
                return 0.0;
            }

            case SRATIONAL:
            case RATIONAL: {
                if (offset + 7 < tag->second.value.size()) {
                    const std::int32_t numerator = sget4(tag->second.value.data() + offset);
                    const std::int32_t denominator = sget4(tag->second.value.data() + offset + 4);
                    return
                        denominator == 0
                            ? 0.0
                            : static_cast<double>(numerator) / static_cast<double>(denominator);
                }
                return 0.0;
            }

            case FLOAT: {
                if (offset + 3 < tag->second.value.size()) {
                    union IntFloat {
                        std::uint32_t i;
                        float f;
                    } conv;

                    conv.i = sget4(tag->second.value.data() + offset);
                    return conv.f;  // IEEE FLOATs are already C format, they just need a recast
                }
                return 0.0;
            }

            default: {
                return 0.0;
            }
        }
    }

    unsigned int getCount(int id) const
    {
        const Tags::const_iterator tag = tags_.find(id);
        if (tag != tags_.end()) {
            return tag->second.count;
        }
        return 0;
    }

private:
    struct Tag {
        int id;
        std::vector<unsigned char> value;
        TagType type;
        unsigned int count;
    };

    using Tags = std::unordered_map<int, Tag>;

    std::uint16_t sget2(const std::uint8_t* s) const
    {
        if (order_ == INTEL) {
            return s[0] | s[1] << 8;
        } else {
            return s[0] << 8 | s[1];
        }
    }

    std::uint32_t sget4(const std::uint8_t* s) const
    {
        if (order_ == INTEL) {
            return s[0] | s[1] << 8 | s[2] << 16 | s[3] << 24;
        } else {
            return s[0] << 24 | s[1] << 16 | s[2] << 8 | s[3];
        }
    }

    std::uint16_t get2()
    {
        std::uint16_t res = std::numeric_limits<std::uint16_t>::max();
        std::fread(&res, 1, 2, file_);
        return sget2(reinterpret_cast<const std::uint8_t*>(&res));
    }

    std::uint32_t get4()
    {
        std::uint32_t res = std::numeric_limits<std::uint32_t>::max();
        std::fread(&res, 1, 4, file_);
        return sget4(reinterpret_cast<const std::uint8_t*>(&res));
    }

    static int getTypeSize(TagType type)
    {
        switch (type) {
            case INVALID:
            case BYTE:
            case ASCII:
            case SBYTE:
            case UNDEFINED: {
                return 1;
            }

            case SHORT:
            case SSHORT: {
                return 2;
            }

            case LONG:
            case SLONG:
            case FLOAT: {
                return 4;
            }

            case RATIONAL:
            case SRATIONAL:
            case DOUBLE: {
                return 8;
            }
        }

        return 1;
    }

    bool parseTag(Tag& tag)
    {
        tag.id = get2();
        tag.type  = TagType(get2());
        tag.count = std::max(1U, get4());

        // Filter out invalid tags
        // Note: The large count is to be able to pass LeafData ASCII tag which can be up to almost 10 megabytes,
        // (only a small part of it will actually be parsed though)
        if (
            tag.type == INVALID
            || tag.type > DOUBLE
            || tag.count > 10 * 1024 * 1024
        ) {
            tag.type = INVALID;
            return false;
        }

        // Store next Tag's position in file
        const std::size_t saved_position = std::ftell(file_) + 4;

        // Load value field (possibly seek before)
        const std::size_t value_size = static_cast<std::size_t>(tag.count) * getTypeSize(tag.type);

        if (value_size > 4) {
            if (std::fseek(file_, get4(), SEEK_SET) == -1) {
                tag.type = INVALID;
                return false;
            }
        }

        // Read value
        tag.value.resize(value_size + 1);
        const std::size_t read = std::fread(tag.value.data(), 1, value_size, file_);
        if (read != value_size) {
            tag.type = INVALID;
            return false;
        }
        tag.value[read] = '\0';

        // Seek back to the saved position
        std::fseek(file_, saved_position, SEEK_SET);

        return true;
    }

    FILE* const file_;

    Tags tags_;
    ByteOrder order_;
};

} // namespace


DCPProfile::DCPProfile(const std::string& filename) :
    has_color_matrix_1(false),
    has_color_matrix_2(false),
    has_forward_matrix_1(false),
    has_forward_matrix_2(false),
    has_tone_curve(false),
    has_baseline_exposure_offset(false),
    will_interpolate(false),
    valid(false),
    baseline_exposure_offset(0.0)
{
    delta_info.hue_step = delta_info.val_step = look_info.hue_step = look_info.val_step = 0;
    constexpr int tiff_float_size = 4;

    enum TagKey {
        TAG_KEY_COLOR_MATRIX_1 = 50721,
        TAG_KEY_COLOR_MATRIX_2 = 50722,
        TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS = 50937,
        TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1 = 50938,
        TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_2 = 50939,
        TAG_KEY_PROFILE_TONE_CURVE = 50940,
        TAG_KEY_PROFILE_TONE_COPYRIGHT = 50942,
        TAG_KEY_CALIBRATION_ILLUMINANT_1 = 50778,
        TAG_KEY_CALIBRATION_ILLUMINANT_2 = 50779,
        TAG_KEY_FORWARD_MATRIX_1 = 50964,
        TAG_KEY_FORWARD_MATRIX_2 = 50965,
        TAG_KEY_PROFILE_LOOK_TABLE_DIMS = 50981, // ProfileLookup is the low quality variant
        TAG_KEY_PROFILE_LOOK_TABLE_DATA = 50982,
        TAG_KEY_PROFILE_HUE_SAT_MAP_ENCODING = 51107,
        TAG_KEY_PROFILE_LOOK_TABLE_ENCODING = 51108,
        TAG_KEY_BASELINE_EXPOSURE_OFFSET = 51109
    };

    static const float adobe_camera_raw_default_curve[] = {
        0.00000f, 0.00078f, 0.00160f, 0.00242f,
        0.00314f, 0.00385f, 0.00460f, 0.00539f,
        0.00623f, 0.00712f, 0.00806f, 0.00906f,
        0.01012f, 0.01122f, 0.01238f, 0.01359f,
        0.01485f, 0.01616f, 0.01751f, 0.01890f,
        0.02033f, 0.02180f, 0.02331f, 0.02485f,
        0.02643f, 0.02804f, 0.02967f, 0.03134f,
        0.03303f, 0.03475f, 0.03648f, 0.03824f,
        0.04002f, 0.04181f, 0.04362f, 0.04545f,
        0.04730f, 0.04916f, 0.05103f, 0.05292f,
        0.05483f, 0.05675f, 0.05868f, 0.06063f,
        0.06259f, 0.06457f, 0.06655f, 0.06856f,
        0.07057f, 0.07259f, 0.07463f, 0.07668f,
        0.07874f, 0.08081f, 0.08290f, 0.08499f,
        0.08710f, 0.08921f, 0.09134f, 0.09348f,
        0.09563f, 0.09779f, 0.09996f, 0.10214f,
        0.10433f, 0.10652f, 0.10873f, 0.11095f,
        0.11318f, 0.11541f, 0.11766f, 0.11991f,
        0.12218f, 0.12445f, 0.12673f, 0.12902f,
        0.13132f, 0.13363f, 0.13595f, 0.13827f,
        0.14061f, 0.14295f, 0.14530f, 0.14765f,
        0.15002f, 0.15239f, 0.15477f, 0.15716f,
        0.15956f, 0.16197f, 0.16438f, 0.16680f,
        0.16923f, 0.17166f, 0.17410f, 0.17655f,
        0.17901f, 0.18148f, 0.18395f, 0.18643f,
        0.18891f, 0.19141f, 0.19391f, 0.19641f,
        0.19893f, 0.20145f, 0.20398f, 0.20651f,
        0.20905f, 0.21160f, 0.21416f, 0.21672f,
        0.21929f, 0.22185f, 0.22440f, 0.22696f,
        0.22950f, 0.23204f, 0.23458f, 0.23711f,
        0.23963f, 0.24215f, 0.24466f, 0.24717f,
        0.24967f, 0.25216f, 0.25465f, 0.25713f,
        0.25961f, 0.26208f, 0.26454f, 0.26700f,
        0.26945f, 0.27189f, 0.27433f, 0.27676f,
        0.27918f, 0.28160f, 0.28401f, 0.28641f,
        0.28881f, 0.29120f, 0.29358f, 0.29596f,
        0.29833f, 0.30069f, 0.30305f, 0.30540f,
        0.30774f, 0.31008f, 0.31241f, 0.31473f,
        0.31704f, 0.31935f, 0.32165f, 0.32395f,
        0.32623f, 0.32851f, 0.33079f, 0.33305f,
        0.33531f, 0.33756f, 0.33981f, 0.34205f,
        0.34428f, 0.34650f, 0.34872f, 0.35093f,
        0.35313f, 0.35532f, 0.35751f, 0.35969f,
        0.36187f, 0.36404f, 0.36620f, 0.36835f,
        0.37050f, 0.37264f, 0.37477f, 0.37689f,
        0.37901f, 0.38112f, 0.38323f, 0.38533f,
        0.38742f, 0.38950f, 0.39158f, 0.39365f,
        0.39571f, 0.39777f, 0.39982f, 0.40186f,
        0.40389f, 0.40592f, 0.40794f, 0.40996f,
        0.41197f, 0.41397f, 0.41596f, 0.41795f,
        0.41993f, 0.42191f, 0.42388f, 0.42584f,
        0.42779f, 0.42974f, 0.43168f, 0.43362f,
        0.43554f, 0.43747f, 0.43938f, 0.44129f,
        0.44319f, 0.44509f, 0.44698f, 0.44886f,
        0.45073f, 0.45260f, 0.45447f, 0.45632f,
        0.45817f, 0.46002f, 0.46186f, 0.46369f,
        0.46551f, 0.46733f, 0.46914f, 0.47095f,
        0.47275f, 0.47454f, 0.47633f, 0.47811f,
        0.47989f, 0.48166f, 0.48342f, 0.48518f,
        0.48693f, 0.48867f, 0.49041f, 0.49214f,
        0.49387f, 0.49559f, 0.49730f, 0.49901f,
        0.50072f, 0.50241f, 0.50410f, 0.50579f,
        0.50747f, 0.50914f, 0.51081f, 0.51247f,
        0.51413f, 0.51578f, 0.51742f, 0.51906f,
        0.52069f, 0.52232f, 0.52394f, 0.52556f,
        0.52717f, 0.52878f, 0.53038f, 0.53197f,
        0.53356f, 0.53514f, 0.53672f, 0.53829f,
        0.53986f, 0.54142f, 0.54297f, 0.54452f,
        0.54607f, 0.54761f, 0.54914f, 0.55067f,
        0.55220f, 0.55371f, 0.55523f, 0.55673f,
        0.55824f, 0.55973f, 0.56123f, 0.56271f,
        0.56420f, 0.56567f, 0.56715f, 0.56861f,
        0.57007f, 0.57153f, 0.57298f, 0.57443f,
        0.57587f, 0.57731f, 0.57874f, 0.58017f,
        0.58159f, 0.58301f, 0.58443f, 0.58583f,
        0.58724f, 0.58864f, 0.59003f, 0.59142f,
        0.59281f, 0.59419f, 0.59556f, 0.59694f,
        0.59830f, 0.59966f, 0.60102f, 0.60238f,
        0.60373f, 0.60507f, 0.60641f, 0.60775f,
        0.60908f, 0.61040f, 0.61173f, 0.61305f,
        0.61436f, 0.61567f, 0.61698f, 0.61828f,
        0.61957f, 0.62087f, 0.62216f, 0.62344f,
        0.62472f, 0.62600f, 0.62727f, 0.62854f,
        0.62980f, 0.63106f, 0.63232f, 0.63357f,
        0.63482f, 0.63606f, 0.63730f, 0.63854f,
        0.63977f, 0.64100f, 0.64222f, 0.64344f,
        0.64466f, 0.64587f, 0.64708f, 0.64829f,
        0.64949f, 0.65069f, 0.65188f, 0.65307f,
        0.65426f, 0.65544f, 0.65662f, 0.65779f,
        0.65897f, 0.66013f, 0.66130f, 0.66246f,
        0.66362f, 0.66477f, 0.66592f, 0.66707f,
        0.66821f, 0.66935f, 0.67048f, 0.67162f,
        0.67275f, 0.67387f, 0.67499f, 0.67611f,
        0.67723f, 0.67834f, 0.67945f, 0.68055f,
        0.68165f, 0.68275f, 0.68385f, 0.68494f,
        0.68603f, 0.68711f, 0.68819f, 0.68927f,
        0.69035f, 0.69142f, 0.69249f, 0.69355f,
        0.69461f, 0.69567f, 0.69673f, 0.69778f,
        0.69883f, 0.69988f, 0.70092f, 0.70196f,
        0.70300f, 0.70403f, 0.70506f, 0.70609f,
        0.70711f, 0.70813f, 0.70915f, 0.71017f,
        0.71118f, 0.71219f, 0.71319f, 0.71420f,
        0.71520f, 0.71620f, 0.71719f, 0.71818f,
        0.71917f, 0.72016f, 0.72114f, 0.72212f,
        0.72309f, 0.72407f, 0.72504f, 0.72601f,
        0.72697f, 0.72794f, 0.72890f, 0.72985f,
        0.73081f, 0.73176f, 0.73271f, 0.73365f,
        0.73460f, 0.73554f, 0.73647f, 0.73741f,
        0.73834f, 0.73927f, 0.74020f, 0.74112f,
        0.74204f, 0.74296f, 0.74388f, 0.74479f,
        0.74570f, 0.74661f, 0.74751f, 0.74842f,
        0.74932f, 0.75021f, 0.75111f, 0.75200f,
        0.75289f, 0.75378f, 0.75466f, 0.75555f,
        0.75643f, 0.75730f, 0.75818f, 0.75905f,
        0.75992f, 0.76079f, 0.76165f, 0.76251f,
        0.76337f, 0.76423f, 0.76508f, 0.76594f,
        0.76679f, 0.76763f, 0.76848f, 0.76932f,
        0.77016f, 0.77100f, 0.77183f, 0.77267f,
        0.77350f, 0.77432f, 0.77515f, 0.77597f,
        0.77680f, 0.77761f, 0.77843f, 0.77924f,
        0.78006f, 0.78087f, 0.78167f, 0.78248f,
        0.78328f, 0.78408f, 0.78488f, 0.78568f,
        0.78647f, 0.78726f, 0.78805f, 0.78884f,
        0.78962f, 0.79040f, 0.79118f, 0.79196f,
        0.79274f, 0.79351f, 0.79428f, 0.79505f,
        0.79582f, 0.79658f, 0.79735f, 0.79811f,
        0.79887f, 0.79962f, 0.80038f, 0.80113f,
        0.80188f, 0.80263f, 0.80337f, 0.80412f,
        0.80486f, 0.80560f, 0.80634f, 0.80707f,
        0.80780f, 0.80854f, 0.80926f, 0.80999f,
        0.81072f, 0.81144f, 0.81216f, 0.81288f,
        0.81360f, 0.81431f, 0.81503f, 0.81574f,
        0.81645f, 0.81715f, 0.81786f, 0.81856f,
        0.81926f, 0.81996f, 0.82066f, 0.82135f,
        0.82205f, 0.82274f, 0.82343f, 0.82412f,
        0.82480f, 0.82549f, 0.82617f, 0.82685f,
        0.82753f, 0.82820f, 0.82888f, 0.82955f,
        0.83022f, 0.83089f, 0.83155f, 0.83222f,
        0.83288f, 0.83354f, 0.83420f, 0.83486f,
        0.83552f, 0.83617f, 0.83682f, 0.83747f,
        0.83812f, 0.83877f, 0.83941f, 0.84005f,
        0.84069f, 0.84133f, 0.84197f, 0.84261f,
        0.84324f, 0.84387f, 0.84450f, 0.84513f,
        0.84576f, 0.84639f, 0.84701f, 0.84763f,
        0.84825f, 0.84887f, 0.84949f, 0.85010f,
        0.85071f, 0.85132f, 0.85193f, 0.85254f,
        0.85315f, 0.85375f, 0.85436f, 0.85496f,
        0.85556f, 0.85615f, 0.85675f, 0.85735f,
        0.85794f, 0.85853f, 0.85912f, 0.85971f,
        0.86029f, 0.86088f, 0.86146f, 0.86204f,
        0.86262f, 0.86320f, 0.86378f, 0.86435f,
        0.86493f, 0.86550f, 0.86607f, 0.86664f,
        0.86720f, 0.86777f, 0.86833f, 0.86889f,
        0.86945f, 0.87001f, 0.87057f, 0.87113f,
        0.87168f, 0.87223f, 0.87278f, 0.87333f,
        0.87388f, 0.87443f, 0.87497f, 0.87552f,
        0.87606f, 0.87660f, 0.87714f, 0.87768f,
        0.87821f, 0.87875f, 0.87928f, 0.87981f,
        0.88034f, 0.88087f, 0.88140f, 0.88192f,
        0.88244f, 0.88297f, 0.88349f, 0.88401f,
        0.88453f, 0.88504f, 0.88556f, 0.88607f,
        0.88658f, 0.88709f, 0.88760f, 0.88811f,
        0.88862f, 0.88912f, 0.88963f, 0.89013f,
        0.89063f, 0.89113f, 0.89163f, 0.89212f,
        0.89262f, 0.89311f, 0.89360f, 0.89409f,
        0.89458f, 0.89507f, 0.89556f, 0.89604f,
        0.89653f, 0.89701f, 0.89749f, 0.89797f,
        0.89845f, 0.89892f, 0.89940f, 0.89987f,
        0.90035f, 0.90082f, 0.90129f, 0.90176f,
        0.90222f, 0.90269f, 0.90316f, 0.90362f,
        0.90408f, 0.90454f, 0.90500f, 0.90546f,
        0.90592f, 0.90637f, 0.90683f, 0.90728f,
        0.90773f, 0.90818f, 0.90863f, 0.90908f,
        0.90952f, 0.90997f, 0.91041f, 0.91085f,
        0.91130f, 0.91173f, 0.91217f, 0.91261f,
        0.91305f, 0.91348f, 0.91392f, 0.91435f,
        0.91478f, 0.91521f, 0.91564f, 0.91606f,
        0.91649f, 0.91691f, 0.91734f, 0.91776f,
        0.91818f, 0.91860f, 0.91902f, 0.91944f,
        0.91985f, 0.92027f, 0.92068f, 0.92109f,
        0.92150f, 0.92191f, 0.92232f, 0.92273f,
        0.92314f, 0.92354f, 0.92395f, 0.92435f,
        0.92475f, 0.92515f, 0.92555f, 0.92595f,
        0.92634f, 0.92674f, 0.92713f, 0.92753f,
        0.92792f, 0.92831f, 0.92870f, 0.92909f,
        0.92947f, 0.92986f, 0.93025f, 0.93063f,
        0.93101f, 0.93139f, 0.93177f, 0.93215f,
        0.93253f, 0.93291f, 0.93328f, 0.93366f,
        0.93403f, 0.93440f, 0.93478f, 0.93515f,
        0.93551f, 0.93588f, 0.93625f, 0.93661f,
        0.93698f, 0.93734f, 0.93770f, 0.93807f,
        0.93843f, 0.93878f, 0.93914f, 0.93950f,
        0.93986f, 0.94021f, 0.94056f, 0.94092f,
        0.94127f, 0.94162f, 0.94197f, 0.94231f,
        0.94266f, 0.94301f, 0.94335f, 0.94369f,
        0.94404f, 0.94438f, 0.94472f, 0.94506f,
        0.94540f, 0.94573f, 0.94607f, 0.94641f,
        0.94674f, 0.94707f, 0.94740f, 0.94774f,
        0.94807f, 0.94839f, 0.94872f, 0.94905f,
        0.94937f, 0.94970f, 0.95002f, 0.95035f,
        0.95067f, 0.95099f, 0.95131f, 0.95163f,
        0.95194f, 0.95226f, 0.95257f, 0.95289f,
        0.95320f, 0.95351f, 0.95383f, 0.95414f,
        0.95445f, 0.95475f, 0.95506f, 0.95537f,
        0.95567f, 0.95598f, 0.95628f, 0.95658f,
        0.95688f, 0.95718f, 0.95748f, 0.95778f,
        0.95808f, 0.95838f, 0.95867f, 0.95897f,
        0.95926f, 0.95955f, 0.95984f, 0.96013f,
        0.96042f, 0.96071f, 0.96100f, 0.96129f,
        0.96157f, 0.96186f, 0.96214f, 0.96242f,
        0.96271f, 0.96299f, 0.96327f, 0.96355f,
        0.96382f, 0.96410f, 0.96438f, 0.96465f,
        0.96493f, 0.96520f, 0.96547f, 0.96574f,
        0.96602f, 0.96629f, 0.96655f, 0.96682f,
        0.96709f, 0.96735f, 0.96762f, 0.96788f,
        0.96815f, 0.96841f, 0.96867f, 0.96893f,
        0.96919f, 0.96945f, 0.96971f, 0.96996f,
        0.97022f, 0.97047f, 0.97073f, 0.97098f,
        0.97123f, 0.97149f, 0.97174f, 0.97199f,
        0.97223f, 0.97248f, 0.97273f, 0.97297f,
        0.97322f, 0.97346f, 0.97371f, 0.97395f,
        0.97419f, 0.97443f, 0.97467f, 0.97491f,
        0.97515f, 0.97539f, 0.97562f, 0.97586f,
        0.97609f, 0.97633f, 0.97656f, 0.97679f,
        0.97702f, 0.97725f, 0.97748f, 0.97771f,
        0.97794f, 0.97817f, 0.97839f, 0.97862f,
        0.97884f, 0.97907f, 0.97929f, 0.97951f,
        0.97973f, 0.97995f, 0.98017f, 0.98039f,
        0.98061f, 0.98082f, 0.98104f, 0.98125f,
        0.98147f, 0.98168f, 0.98189f, 0.98211f,
        0.98232f, 0.98253f, 0.98274f, 0.98295f,
        0.98315f, 0.98336f, 0.98357f, 0.98377f,
        0.98398f, 0.98418f, 0.98438f, 0.98458f,
        0.98478f, 0.98498f, 0.98518f, 0.98538f,
        0.98558f, 0.98578f, 0.98597f, 0.98617f,
        0.98636f, 0.98656f, 0.98675f, 0.98694f,
        0.98714f, 0.98733f, 0.98752f, 0.98771f,
        0.98789f, 0.98808f, 0.98827f, 0.98845f,
        0.98864f, 0.98882f, 0.98901f, 0.98919f,
        0.98937f, 0.98955f, 0.98973f, 0.98991f,
        0.99009f, 0.99027f, 0.99045f, 0.99063f,
        0.99080f, 0.99098f, 0.99115f, 0.99133f,
        0.99150f, 0.99167f, 0.99184f, 0.99201f,
        0.99218f, 0.99235f, 0.99252f, 0.99269f,
        0.99285f, 0.99302f, 0.99319f, 0.99335f,
        0.99351f, 0.99368f, 0.99384f, 0.99400f,
        0.99416f, 0.99432f, 0.99448f, 0.99464f,
        0.99480f, 0.99495f, 0.99511f, 0.99527f,
        0.99542f, 0.99558f, 0.99573f, 0.99588f,
        0.99603f, 0.99619f, 0.99634f, 0.99649f,
        0.99664f, 0.99678f, 0.99693f, 0.99708f,
        0.99722f, 0.99737f, 0.99751f, 0.99766f,
        0.99780f, 0.99794f, 0.99809f, 0.99823f,
        0.99837f, 0.99851f, 0.99865f, 0.99879f,
        0.99892f, 0.99906f, 0.99920f, 0.99933f,
        0.99947f, 0.99960f, 0.99974f, 0.99987f,
        1.00000f
    };

    const std::unique_ptr<std::FILE, std::function<void(std::FILE *)>> file(
        std::fopen(filename.c_str(), "rb"),
        [](std::FILE *file) {
            std::fclose(file);
        });

    if (file == nullptr) {
        printf ("Unable to load DCP profile '%s' !\n", filename.c_str());
        return;
    }

    DCPMetadata md(file.get());
    if (!md.parse()) {
        printf ("Unable to load DCP profile '%s'.\n", filename.c_str());
        return;
    }

    light_source_1 =
        md.find(TAG_KEY_CALIBRATION_ILLUMINANT_1)
            ? md.toShort(TAG_KEY_CALIBRATION_ILLUMINANT_1)
            : -1;
    light_source_2 =
        md.find(TAG_KEY_CALIBRATION_ILLUMINANT_2)
            ? md.toShort(TAG_KEY_CALIBRATION_ILLUMINANT_2)
            : -1;
    temperature_1 = calibrationIlluminantToTemperature(light_source_1);
    temperature_2 = calibrationIlluminantToTemperature(light_source_2);

    const bool has_second_hue_sat = md.find(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_2); // Some profiles have two matrices, but just one huesat

    // Fetch Forward Matrices, if any
    has_forward_matrix_1 = md.find(TAG_KEY_FORWARD_MATRIX_1);

    if (has_forward_matrix_1) {
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 3; ++col) {
                forward_matrix_1[row][col] = md.toDouble(TAG_KEY_FORWARD_MATRIX_1, (col + row * 3) * 8);
            }
        }
    }

    has_forward_matrix_2 = md.find(TAG_KEY_FORWARD_MATRIX_2);

    if (has_forward_matrix_2) {
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 3; ++col) {
                forward_matrix_2[row][col] = md.toDouble(TAG_KEY_FORWARD_MATRIX_2, (col + row * 3) * 8);
            }
        }
    }

    // Color Matrix (one is always there)
    if (!md.find(TAG_KEY_COLOR_MATRIX_1)) {
        std::cerr << "DCP '" << filename.c_str() << "' is missing 'ColorMatrix1'. Skipped." << std::endl;
        return;
    }

    has_color_matrix_1 = true;

    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            color_matrix_1[row][col] = md.toDouble(TAG_KEY_COLOR_MATRIX_1, (col + row * 3) * 8);
        }
    }

    if (md.find(TAG_KEY_PROFILE_LOOK_TABLE_DIMS)) {
        look_info.hue_divisions = md.toInt(TAG_KEY_PROFILE_LOOK_TABLE_DIMS, 0);
        look_info.sat_divisions = md.toInt(TAG_KEY_PROFILE_LOOK_TABLE_DIMS, 4);
        look_info.val_divisions = md.toInt(TAG_KEY_PROFILE_LOOK_TABLE_DIMS, 8);

        look_info.srgb_gamma = md.find(TAG_KEY_PROFILE_LOOK_TABLE_ENCODING) && md.toInt(TAG_KEY_PROFILE_LOOK_TABLE_ENCODING);

        look_info.array_count = md.getCount(TAG_KEY_PROFILE_LOOK_TABLE_DATA) / 3;
        look_table.resize(look_info.array_count);

        for (unsigned int i = 0; i < look_info.array_count; i++) {
            look_table[i].hue_shift = md.toDouble(TAG_KEY_PROFILE_LOOK_TABLE_DATA, (i * 3) * tiff_float_size);
            look_table[i].sat_scale = md.toDouble(TAG_KEY_PROFILE_LOOK_TABLE_DATA, (i * 3 + 1) * tiff_float_size);
            look_table[i].val_scale = md.toDouble(TAG_KEY_PROFILE_LOOK_TABLE_DATA, (i * 3 + 2) * tiff_float_size);
        }

        // Precalculated constants for table application
        look_info.pc.h_scale =
            look_info.hue_divisions < 2
            ? 0.0f
            : static_cast<float>(look_info.hue_divisions) / 6.0f;
        look_info.pc.s_scale = look_info.sat_divisions - 1;
        look_info.pc.v_scale = look_info.val_divisions - 1;
        look_info.pc.max_hue_index0 = look_info.hue_divisions - 1;
        look_info.pc.max_sat_index0 = look_info.sat_divisions - 2;
        look_info.pc.max_val_index0 = look_info.val_divisions - 2;
        look_info.pc.hue_step = look_info.sat_divisions;
        look_info.pc.val_step = look_info.hue_divisions * look_info.pc.hue_step;
    }

    if (md.find(TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS)) {
        delta_info.hue_divisions = md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS, 0);
        delta_info.sat_divisions = md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS, 4);
        delta_info.val_divisions = md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS, 8);

        delta_info.srgb_gamma = md.find(TAG_KEY_PROFILE_HUE_SAT_MAP_ENCODING) && md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_ENCODING);

        delta_info.array_count = md.getCount(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1) / 3;
        deltas_1.resize(delta_info.array_count);

        for (unsigned int i = 0; i < delta_info.array_count; ++i) {
            deltas_1[i].hue_shift = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1, (i * 3) * tiff_float_size);
            deltas_1[i].sat_scale = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1, (i * 3 + 1) * tiff_float_size);
            deltas_1[i].val_scale = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1, (i * 3 + 2) * tiff_float_size);
        }

        delta_info.pc.h_scale =
            delta_info.hue_divisions < 2
            ? 0.0f
            : static_cast<float>(delta_info.hue_divisions) / 6.0f;
        delta_info.pc.s_scale = delta_info.sat_divisions - 1;
        delta_info.pc.v_scale = delta_info.val_divisions - 1;
        delta_info.pc.max_hue_index0 = delta_info.hue_divisions - 1;
        delta_info.pc.max_sat_index0 = delta_info.sat_divisions - 2;
        delta_info.pc.max_val_index0 = delta_info.val_divisions - 2;
        delta_info.pc.hue_step = delta_info.sat_divisions;
        delta_info.pc.val_step = delta_info.hue_divisions * delta_info.pc.hue_step;
    }

    if (light_source_2 != -1) {
        // Second matrix
        has_color_matrix_2 = true;

        const bool cm2 = md.find(TAG_KEY_COLOR_MATRIX_2);

        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 3; ++col) {
                color_matrix_2[row][col] =
                    cm2
                        ? md.toDouble(TAG_KEY_COLOR_MATRIX_2, (col + row * 3) * 8)
                        : color_matrix_1[row][col];
            }
        }

        // Second huesatmap
        if (has_second_hue_sat) {
            deltas_2.resize(delta_info.array_count);

            // Saturation maps. Need to be unwinded.
            for (unsigned int i = 0; i < delta_info.array_count; ++i) {
                deltas_2[i].hue_shift = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_2, (i * 3) * tiff_float_size);
                deltas_2[i].sat_scale = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_2, (i * 3 + 1) * tiff_float_size);
                deltas_2[i].val_scale = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_2, (i * 3 + 2) * tiff_float_size);
            }
        }
    }

    has_baseline_exposure_offset = md.find(TAG_KEY_BASELINE_EXPOSURE_OFFSET);
    if (has_baseline_exposure_offset) {
        baseline_exposure_offset = md.toDouble(TAG_KEY_BASELINE_EXPOSURE_OFFSET);
    }


    will_interpolate = false;

    if (has_forward_matrix_1) {
        if (has_forward_matrix_2) {
            if (forward_matrix_1 != forward_matrix_2) {
                // Common that forward matrices are the same!
                will_interpolate = true;
            }

            if (!deltas_1.empty() && !deltas_2.empty()) {
                // We assume tables are different
                will_interpolate = true;
            }
        }
    }

    if (has_color_matrix_1 && has_color_matrix_2) {
        if (color_matrix_1 != color_matrix_2) {
            will_interpolate = true;
        }

        if (!deltas_1.empty() && !deltas_2.empty()) {
            will_interpolate = true;
        }
    }

    valid = true;
}

DCPProfile::~DCPProfile() = default;

DCPProfile::operator bool() const
{
    return has_color_matrix_1;
}

bool DCPProfile::getHasToneCurve() const
{
    return has_tone_curve;
}

bool DCPProfile::getHasLookTable() const
{
    return !look_table.empty();
}

bool DCPProfile::getHasHueSatMap() const
{
    return !deltas_1.empty();
}

bool DCPProfile::getHasBaselineExposureOffset() const
{
    return has_baseline_exposure_offset;
}

DCPProfile::Illuminants DCPProfile::getIlluminants() const
{
    return {
        light_source_1,
        light_source_2,
        temperature_1,
        temperature_2,
        will_interpolate
    };
}

