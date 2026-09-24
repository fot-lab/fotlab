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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path

// White Balance: a rounded square split by a diagonal (top-right to bottom-left), with a
// "W" sitting in the upper-left triangle and a "B" in the lower-right one. The letters are
// hand-authored stroked geometry (the stock Material set has no such glyph), so the whole
// icon stays a plain monochrome ImageVector on the standard 24x24 grid and recolors through
// Icon(tint = ...) like any stock icon.
//
// Accessor shape mirrors generated Material icons (lazy get() + backing nullable field).

private var _whiteBalance: ImageVector? = null

/**
 * White Balance glyph for [CustomMaterialStyleIcons.Filled].
 */
public val CustomMaterialStyleIcons.Filled.WhiteBalance: ImageVector
    get() {
        if (_whiteBalance != null) {
            return _whiteBalance!!
        }
        _whiteBalance = materialIcon(name = "CustomMaterialStyleIcons.Filled.WhiteBalance") {
            // Frame: 16x16 rounded square (corner radius 2) inset by 4, plus the diagonal
            // split. The diagonal ends on the 2-radius corner arc of the rounded rect.
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.45f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f,
            ) {
                moveTo(6f, 4f)
                horizontalLineTo(18f)
                curveTo(19.1046f, 4f, 20f, 4.8954f, 20f, 6f)
                verticalLineTo(18f)
                curveTo(20f, 19.1046f, 19.1046f, 20f, 18f, 20f)
                horizontalLineTo(6f)
                curveTo(4.8954f, 20f, 4f, 19.1046f, 4f, 18f)
                verticalLineTo(6f)
                curveTo(4f, 4.8954f, 4.8954f, 4f, 6f, 4f)
                close()
                moveTo(19.41f, 4.59f)
                lineTo(4.59f, 19.41f)
            }
            // Letters, round caps/joins for a bold glyph look at small sizes.
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.45f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f,
            ) {
                // W, centred on the upper-left triangle centroid (9.33, 9.33).
                moveTo(6.6f, 7.6f)
                lineTo(8f, 11f)
                lineTo(9.4f, 8.5f)
                lineTo(10.8f, 11f)
                lineTo(12.2f, 7.6f)
                // B, centred on the lower-right triangle centroid (14.67, 14.67).
                // Vertical stem shared by both bowls.
                moveTo(12.9f, 13f)
                verticalLineTo(16.6f)
                // Upper bowl.
                moveTo(12.9f, 13.15f)
                horizontalLineTo(15.1f)
                curveTo(16.15f, 13.15f, 16.55f, 13.75f, 16.3f, 14.3f)
                curveTo(16.1f, 14.75f, 15.5f, 14.85f, 14.9f, 14.85f)
                horizontalLineTo(12.9f)
                // Lower bowl.
                moveTo(12.9f, 14.75f)
                horizontalLineTo(15.4f)
                curveTo(16.55f, 14.75f, 16.85f, 15.5f, 16.55f, 16.1f)
                curveTo(16.3f, 16.6f, 15.5f, 16.65f, 14.8f, 16.65f)
                horizontalLineTo(12.9f)
            }
        }
        return _whiteBalance!!
    }
