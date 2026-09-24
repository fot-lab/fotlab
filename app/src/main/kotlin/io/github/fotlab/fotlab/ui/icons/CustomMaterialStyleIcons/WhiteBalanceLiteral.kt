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

// White Balance (literal): just the two letters "WB", no frame and no diagonal. Both glyphs
// are hand-authored stroked geometry so the icon stays a plain monochrome ImageVector on the
// standard 24x24 grid and recolors through Icon(tint = ...) like any stock icon.
//
// Accessor shape mirrors generated Material icons (lazy get() + backing nullable field).

private var _whiteBalanceLiteral: ImageVector? = null

/**
 * Letter-only ("WB") White Balance glyph for [CustomMaterialStyleIcons.Filled].
 */
public val CustomMaterialStyleIcons.Filled.WhiteBalanceLiteral: ImageVector
    get() {
        if (_whiteBalanceLiteral != null) {
            return _whiteBalanceLiteral!!
        }
        _whiteBalanceLiteral = materialIcon(name = "CustomMaterialStyleIcons.Filled.WhiteBalanceLiteral") {
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f,
            ) {
                // W, occupying the left half (x ~4.3..12.5), cap height y ~8.2..15.9.
                moveTo(4.3f, 8.2f)
                lineTo(6.2f, 15.9f)
                lineTo(8.4f, 11.2f)
                lineTo(10.6f, 15.9f)
                lineTo(12.5f, 8.2f)
                // B, occupying the right half (x ~13.7..19.7). Vertical spine first.
                moveTo(13.7f, 8f)
                verticalLineTo(16.2f)
                // Upper bowl: an arch leaving and rejoining the spine.
                moveTo(13.7f, 8.3f)
                curveTo(16.5f, 8.1f, 19.2f, 8.6f, 19.2f, 10.2f)
                curveTo(19.2f, 11.6f, 17.3f, 12.0f, 13.7f, 11.9f)
                // Lower bowl.
                moveTo(13.7f, 12f)
                curveTo(17f, 11.9f, 19.9f, 12.6f, 19.7f, 14.3f)
                curveTo(19.5f, 15.9f, 17.2f, 16.3f, 13.7f, 16.1f)
            }
        }
        return _whiteBalanceLiteral!!
    }
