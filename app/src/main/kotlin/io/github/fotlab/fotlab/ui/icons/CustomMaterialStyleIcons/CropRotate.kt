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
                // Top-left arc arrow.
                moveTo(7.47f, 21.49f)
                curveTo(4.2f, 19.93f, 1.86f, 16.76f, 1.5f, 13f)
                horizontalLineTo(0f)
                relativeCurveTo(0.51f, 6.16f, 5.66f, 11f, 11.95f, 11f)
                relativeCurveTo(0.23f, 0f, 0.44f, -0.02f, 0.66f, -0.03f)
                lineTo(8.8f, 20.15f)
                relativeLineTo(-1.33f, 1.34f)
                close()
                // Top-right arc arrow.
                moveTo(12.05f, 0f)
                relativeCurveTo(-0.23f, 0f, -0.44f, 0.02f, -0.66f, 0.04f)
                relativeLineTo(3.81f, 3.81f)
                relativeLineTo(1.33f, -1.33f)
                curveTo(19.8f, 4.07f, 22.14f, 7.24f, 22.5f, 11f)
                horizontalLineTo(24f)
                relativeCurveTo(-0.51f, -6.16f, -5.66f, -11f, -11.95f, -11f)
                close()
                // Crop frame (outer L).
                moveTo(16f, 14f)
                relativeHorizontalLineTo(2f)
                verticalLineTo(8f)
                relativeCurveTo(0f, -1.11f, -0.9f, -2f, -2f, -2f)
                relativeHorizontalLineTo(-6f)
                relativeVerticalLineTo(2f)
                relativeHorizontalLineTo(6f)
                relativeVerticalLineTo(6f)
                close()
                // Inner tick.
                moveTo(8f, 16f)
                verticalLineTo(4f)
                horizontalLineTo(6f)
                relativeVerticalLineTo(2f)
                horizontalLineTo(4f)
                relativeVerticalLineTo(2f)
                relativeHorizontalLineTo(2f)
                relativeVerticalLineTo(8f)
                relativeCurveTo(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                relativeHorizontalLineTo(8f)
                relativeVerticalLineTo(2f)
                relativeHorizontalLineTo(2f)
                relativeVerticalLineTo(-2f)
                relativeHorizontalLineTo(2f)
                relativeVerticalLineTo(-2f)
                horizontalLineTo(8f)
                close()
            }
        }
        return _cropRotate!!
    }
