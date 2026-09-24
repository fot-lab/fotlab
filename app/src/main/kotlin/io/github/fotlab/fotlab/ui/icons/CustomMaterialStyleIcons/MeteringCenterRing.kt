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

// "Center-ring" metering glyph: a 20x14 rectangle frame (the ~0.5px corner radius is negligible at
// 1px stroke) around a full ring (radius 3). This is the same as the "Center-weighted" glyph with the
// centre dot removed. All shapes are mutually centre-aligned on the standard 24x24 grid.
// Hand-authored so the icon stays a plain monochrome ImageVector that recolors through
// Icon(tint = ...) like any stock icon.
//
// Accessor shape mirrors generated Material icons (lazy get() + backing nullable field).

private var _meteringCenterRing: ImageVector? = null

/**
 * "Center-ring" metering glyph for [CustomMaterialStyleIcons.Filled].
 */
public val CustomMaterialStyleIcons.Filled.MeteringCenterRing: ImageVector
    get() {
        if (_meteringCenterRing != null) {
            return _meteringCenterRing!!
        }
        _meteringCenterRing = materialIcon(name = "CustomMaterialStyleIcons.Filled.MeteringCenterRing") {
            // Outer frame: 20x14 rectangle, 1px stroke.
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
            ) {
                moveTo(2.5f, 5.5f)
                horizontalLineTo(21.5f)
                verticalLineTo(18.5f)
                horizontalLineTo(2.5f)
                close()
            }
            // Ring: full circle, radius 3 (diameter 6), 1px stroke.
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
            ) {
                moveTo(15f, 12f)
                curveTo(15f, 13.6569f, 13.6569f, 15f, 12f, 15f)
                curveTo(10.3431f, 15f, 9f, 13.6569f, 9f, 12f)
                curveTo(9f, 10.3431f, 10.3431f, 9f, 12f, 9f)
                curveTo(13.6569f, 9f, 15f, 10.3431f, 15f, 12f)
                close()
            }
        }
        return _meteringCenterRing!!
    }
