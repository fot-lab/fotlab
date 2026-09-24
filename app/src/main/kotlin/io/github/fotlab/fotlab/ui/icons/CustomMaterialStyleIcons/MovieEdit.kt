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

// Movie Edit: the official Material Icons "movie_edit" glyph (AV category, 24dp grid —
// google/material-design-icons src/av/movie_edit/materialicons/24px.svg). The frozen
// material-icons-extended artifact (1.7.x, its final line) never generated it, so the stock
// artwork is reproduced here verbatim as monochrome fill geometry — it recolors through
// Icon(tint = ...) like any stock icon.
//
// Accessor shape mirrors generated Material icons (lazy get() + backing nullable field).

private var _movieEdit: ImageVector? = null

/**
 * Movie Edit glyph for [CustomMaterialStyleIcons.Filled].
 */
public val CustomMaterialStyleIcons.Filled.MovieEdit: ImageVector
    get() {
        if (_movieEdit != null) {
            return _movieEdit!!
        }
        _movieEdit = materialIcon(name = "CustomMaterialStyleIcons.Filled.MovieEdit") {
            // Film-strip clapperboard with a bite taken out by the pencil.
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                strokeAlpha = 1.0f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(4f, 10f)
                horizontalLineTo(22f)                                    // h18
                verticalLineTo(6f)                                       // V6
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)         // c0-1.1-.9-2-2-2
                horizontalLineToRelative(-3f)                            // h-3
                lineToRelative(2f, 4f)                                   // l2 4
                horizontalLineToRelative(-3f)                            // h-3
                lineToRelative(-2f, -4f)                                 // l-2-4
                horizontalLineToRelative(-2f)                            // h-2
                lineToRelative(2f, 4f)                                   // l2 4
                horizontalLineToRelative(-3f)                            // h-3
                lineTo(9f, 4f)                                           // L9 4
                horizontalLineTo(7f)                                     // H7
                lineToRelative(2f, 4f)                                   // l2 4
                horizontalLineTo(6f)                                     // H6
                lineTo(4f, 4f)                                           // L4 4
                curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)     // c-1.1 0-1.99.9-1.99 2
                lineTo(2f, 18f)                                          // L2 18
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)              // c0 1.1.9 2 2 2
                horizontalLineToRelative(8f)                             // h8
                verticalLineToRelative(-2f)                              // v-2
                horizontalLineTo(4f)                                     // H4
                verticalLineToRelative(-8f)                              // v-8
                close()
            }
            // Pencil body.
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                strokeAlpha = 1.0f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(14f, 18.88f)
                lineTo(14f, 21f)
                lineTo(16.12f, 21f)
                lineTo(21.29f, 15.83f)
                lineTo(19.17f, 13.71f)
                close()
            }
            // Pencil tip.
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                strokeAlpha = 1.0f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(22.71f, 13f)
                lineToRelative(-0.71f, -0.71f)                           // l-.71-.71
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f) // c-.39-.39-1.02-.39-1.41 0
                lineToRelative(-0.71f, 0.71f)                           // l-.71.71
                lineTo(22f, 15.12f)                                     // L22 15.12
                lineToRelative(0.71f, -0.71f)                           // l.71-.71
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f) // c.39-.39.39-1.02 0-1.41
                close()
            }
        }
        return _movieEdit!!
    }
