/*
 * Copyright 2026 The FotLab Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.fotlab.fotlab.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path

// Crop Rotate: the official Material Icons "crop_rotate" glyph (image category, 24dp grid —
// google/material-design-icons src/image/crop_rotate/materialicons/24px.svg). Reproduced here
// verbatim as monochrome fill geometry, exactly like MovieEdit, because the frozen
// material-icons-extended artifact is intentionally not a project dependency (first-party-only
// UI stack, FOTLAB-UIXDES-000001). It recolors through Icon(tint = ...) like any stock icon.
//
// Accessor shape mirrors generated Material icons (lazy get() + backing nullable field).

private var _cropRotate: ImageVector? = null

/**
 * Crop Rotate glyph for [CustomMaterialStyleIcons.Filled].
 */
public val CustomMaterialStyleIcons.Filled.CropRotate: ImageVector
    get() {
        if (_cropRotate != null) {
            return _cropRotate!!
        }
        _cropRotate = materialIcon(name = "CustomMaterialStyleIcons.Filled.CropRotate") {
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                strokeAlpha = 1.0f,
                pathFillType = PathFillType.NonZero,
            ) {
                addPathNodes(
                    "M7.47 21.49C4.2 19.93 1.86 16.76 1.5 13H0c.51 6.16 5.66 11 11.95 11 " +
                        ".23 0 .44-.02.66-.03L8.8 20.15l-1.33 1.34zM12.05 0c-.23 0-.44.02-" +
                        ".66.04l3.81 3.81 1.33-1.33C19.8 4.07 22.14 7.24 22.5 11H24c-.51-6.16" +
                        "-5.66-11-11.95-11zM16 14h2V8c0-1.11-.9-2-2-2h-6v2h6v6zm-8 2V4H6v2H4v2h2" +
                        "v8c0 1.1.89 2 2 2h8v2h2v-2h2v-2H8z",
                )
            }
        }
        return _cropRotate!!
    }
