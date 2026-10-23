/*
 * Copyright 2026 The FotLab Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.fotlab.fotlab.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.ui.graphics.vector.ImageVector

// Crop & Rotate: the official Material Icons "crop_rotate" glyph from material-icons-extended,
// bridged into the CustomMaterialStyleIcons namespace so the studio style-filter icons stay
// consistent. The geometry itself comes straight from the stock extended artifact (no first-party
// redraw); the genuinely custom glyphs (MovieEdit, metering, white balance, …) remain hand-authored
// in their own files.

/**
 * Crop & Rotate glyph for [CustomMaterialStyleIcons.Filled], backed by the Material extended
 * `crop_rotate` icon.
 */
public val CustomMaterialStyleIcons.Filled.CropRotate: ImageVector
    get() = Icons.Filled.CropRotate
