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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector

// "Center-asterisk matrix" metering glyph: a 20x14 rounded-rect frame (corner radius 1, 1px stroke
// drawn inward) around an 18x12 solid inner rectangle whose centre is punched out by a circle of
// radius 3 (matching the "Matrix / spot" glyph), plus a centre asterisk built from 3 line segments
// of length 4, each rotated 60° from the previous one (total diameter 4), sitting inside the hole.
// All shapes are mutually centre-aligned on the standard 24x24 grid. Hand-authored so the icon stays
// a plain monochrome ImageVector that recolors through Icon(tint = ...) like any stock icon.
//
// Accessor shape mirrors generated Material icons (lazy get() + backing nullable field).

private var _meteringCenterAsteriskMatrix: ImageVector? = null

/**
 * "Center-asterisk matrix" metering glyph for [CustomMaterialStyleIcons.Filled].
 */
public val CustomMaterialStyleIcons.Filled.MeteringCenterAsteriskMatrix: ImageVector
    get() {
        if (_meteringCenterAsteriskMatrix != null) {
            return _meteringCenterAsteriskMatrix!!
        }
        _meteringCenterAsteriskMatrix = materialIcon(name = "CustomMaterialStyleIcons.Filled.MeteringCenterAsteriskMatrix") {
            // Outer frame: 20x14 rounded rect, corner radius 1, 1px stroke drawn inward.
            // Centerline radius 0.5 → outer corner radius = 0.5 + 0.5 (half stroke) = 1.0.
            val frame = Path().apply {
                addRoundRect(RoundRect(Rect(2.5f, 5.5f, 21.5f, 18.5f), CornerRadius(0.5f)))
            }
            addPath(
                frame,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
            )
            // Inner solid 18x12 rectangle with a circular hole (radius 3) at the centre.
            val inner = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(3f, 6f, 21f, 18f))
                addOval(Rect(9f, 9f, 15f, 15f))
            }
            addPath(inner, fill = SolidColor(Color(0xFF000000)))
            // Centre asterisk: 3 segments (length 4, i.e. half-length 2 from centre at (12,12)),
            // rotated 0°, 60° and 120° (each 60° apart, giving a 6-spoke asterisk of diameter 4).
            val asterisk = Path().apply {
                moveTo(10f, 12f); lineTo(14f, 12f)                       // 0°
                moveTo(11f, 10.268f); lineTo(13f, 13.732f)              // 60°
                moveTo(13f, 10.268f); lineTo(11f, 13.732f)              // 120°
            }
            addPath(
                asterisk,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
            )
        }
        return _meteringCenterAsteriskMatrix!!
    }
