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

// "Center-asterisk" metering glyph: a 20x14 rectangle frame (the ~0.5px corner radius is negligible
// at 1px stroke) around a centre asterisk built from 3 line segments of length 4, each rotated 60°
// from the previous one (total diameter 4). This is the same as the "Center-spot" glyph with the
// solid dot replaced by the asterisk. All shapes are mutually centre-aligned on the standard 24x24
// grid. Hand-authored so the icon stays a plain monochrome ImageVector that recolors through
// Icon(tint = ...) like any stock icon.
//
// Accessor shape mirrors generated Material icons (lazy get() + backing nullable field).

private var _meteringCenterAsterisk: ImageVector? = null

/**
 * "Center-asterisk" metering glyph for [CustomMaterialStyleIcons.Filled].
 */
public val CustomMaterialStyleIcons.Filled.MeteringCenterAsterisk: ImageVector
    get() {
        if (_meteringCenterAsterisk != null) {
            return _meteringCenterAsterisk!!
        }
        _meteringCenterAsterisk = materialIcon(name = "CustomMaterialStyleIcons.Filled.MeteringCenterAsterisk") {
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
            // Centre asterisk: 3 segments (length 4, i.e. half-length 2 from centre at (12,12)),
            // rotated 0°, 60° and 120° (each 60° apart, giving a 6-spoke asterisk of diameter 4).
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
            ) {
                moveTo(10f, 12f)
                lineTo(14f, 12f)
                moveTo(11f, 10.268f)
                lineTo(13f, 13.732f)
                moveTo(13f, 10.268f)
                lineTo(11f, 13.732f)
            }
        }
        return _meteringCenterAsterisk!!
    }
