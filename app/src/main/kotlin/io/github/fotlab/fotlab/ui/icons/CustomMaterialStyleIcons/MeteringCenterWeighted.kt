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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector

// "Center-weighted" metering glyph: a 20x14 rounded-rect frame (corner radius 1, 1px stroke drawn
// inward) around a full ring (outer radius 3.5, 1px stroke) plus a solid dot of radius 1.5 at the
// centre. All shapes are mutually centre-aligned on the standard 24x24 grid. Hand-authored so the
// icon stays a plain monochrome ImageVector that recolors through Icon(tint = ...) like any stock
// icon.
//
// Accessor shape mirrors generated Material icons (lazy get() + backing nullable field).

private var _meteringCenterWeighted: ImageVector? = null

/**
 * "Center-weighted" metering glyph for [CustomMaterialStyleIcons.Filled].
 */
public val CustomMaterialStyleIcons.Filled.MeteringCenterWeighted: ImageVector
    get() {
        if (_meteringCenterWeighted != null) {
            return _meteringCenterWeighted!!
        }
        _meteringCenterWeighted = materialIcon(name = "CustomMaterialStyleIcons.Filled.MeteringCenterWeighted") {
            // Outer frame: 20x14 rounded rect, corner radius 1, 1px stroke drawn inward.
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
            // Ring: full circle, outer radius 3, diameter 6, 1px stroke (inner radius 2.5).
            val ring = Path().apply { addOval(Rect(9f, 9f, 15f, 15f)) }
            addPath(
                ring,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
            )
            // Centre dot: radius 1.5.
            val dot = Path().apply { addOval(Rect(10.5f, 10.5f, 13.5f, 13.5f)) }
            addPath(dot, fill = SolidColor(Color(0xFF000000)))
        }
        return _meteringCenterWeighted!!
    }
