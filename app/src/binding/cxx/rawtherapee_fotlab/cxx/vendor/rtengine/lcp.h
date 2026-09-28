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

// fotlab: trimmed copy of RawTherapee's lcp.h. The apply machinery
// (LCPStore / LensCorrection / LCPMapper) has been removed — only the LCP
// *decode* (constructor + calcParams + XML handlers) is kept. Glib::ustring has
// been replaced with std::string and expat with a self-contained minimal parser
// (expat_minimal.h) so no external XML library or glibmm is required.

#pragma once

#include <array>
#include <memory>
#include <sstream>
#include <string>

#include "expat_minimal.h"

namespace rtengine
{

enum class LCPCorrectionMode {
    VIGNETTE,
    DISTORTION,
    CA
};

// Perspective model common data, also used for Vignette and Fisheye
class LCPModelCommon final
{
public:
    LCPModelCommon();

    bool empty() const;  // is it empty
    void merge(const LCPModelCommon& a, const LCPModelCommon& b, float facA);
    void prepareParams(
        int fullWidth,
        int fullHeight,
        float focalLength,
        float focalLength35mm,
        float sensorFormatFactor,
        bool swapXY,
        bool mirrorX,
        bool mirrorY
    );

    using Param = std::array<float, 5>;
    using VignParam = std::array<float, 4>;

    float foc_len_x;
    float foc_len_y;
    float img_center_x;
    float img_center_y;
    Param param;  // k1..k5, resp. alpha1..5
    float scale_factor;  // alpha0
    double mean_error;
    bool bad_error;

    // prepared params
    float x0;
    float y0;
    float fx;
    float fy;
    float rfx;
    float rfy;
    VignParam vign_param;
};

class LCPProfile
{
public:
    explicit LCPProfile(const std::string& fname);
    ~LCPProfile();

    void calcParams(
        LCPCorrectionMode mode,
        float focalLength,
        float focusDist,
        float aperture,
        LCPModelCommon* pCorr1,
        LCPModelCommon* pCorr2,
        LCPModelCommon* pCorr3
    ) const; // Interpolates between the persModels frames

    // --- fotlab inline getters (decode only) ---
    std::string getProfileName() const { return profileName; }
    std::string getCamera() const { return camera; }
    std::string getLens() const { return lens; }
    bool getIsRaw() const { return isRaw; }
    bool getIsFisheye() const { return isFisheye; }
    float getSensorFormatFactor() const { return sensorFormatFactor; }
    int getPersModelCount() const { return persModelCount; }

private:
    class LCPPersModel;

    int filterBadFrames(LCPCorrectionMode mode, double maxAvgDevFac, int minFramesLeft);

    void handle_text(const std::string& text);

    static void XMLCALL XmlStartHandler(void* pLCPProfile, const char* el, const char** attr);
    static void XMLCALL XmlTextHandler(void* pLCPProfile, const XML_Char* s, int len);
    static void XMLCALL XmlEndHandler(void* pLCPProfile, const char* el);

    // Temporary data for parsing
    bool inCamProfiles;
    bool firstLIDone;
    bool inPerspect;
    bool inAlternateLensID;
    bool inAlternateLensNames;
    char lastTag[257];
    char inInvalidTag[257];
    LCPPersModel* pCurPersModel;
    LCPModelCommon* pCurCommon;

    std::ostringstream textbuf;

    // The correction frames
    static constexpr int MaxPersModelCount = 3000;
    LCPPersModel* aPersModel[MaxPersModelCount];  // Do NOT use std::list or something, it's buggy in GCC!

    // Common data
    std::string profileName;
    std::string lensPrettyName;
    std::string cameraPrettyName;
    std::string lens;
    std::string camera;  // lens/camera(=model) can be auto-matched with DNG
    bool isRaw;
    bool isFisheye;
    float sensorFormatFactor;
    int persModelCount;
};

}
