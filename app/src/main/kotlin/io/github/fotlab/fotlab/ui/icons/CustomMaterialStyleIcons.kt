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

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * First-party Material-style system icons.
 *
 * Mirrors the two-level layout of `androidx.compose.material.icons.Icons`:
 * - This file is the single namespace entry point, just like `Icons.kt`.
 * - Concrete glyphs live as one-icon-per-file extension properties in the
 *   `CustomMaterialStyleIcons/` folder next to this file (same package), just like the
 *   official `icons/filled/Menu.kt` files holding `val Icons.Filled.Menu`.
 *
 * Every icon is an [ImageVector] built on the standard 24x24 Material grid with the same
 * lazy `get()` + cached-field accessor as generated Material icons. It is therefore a
 * drop-in replacement anywhere `Icons.Filled.*` is accepted:
 *
 * ```kotlin
 * import io.github.fotlab.fotlab.ui.icons.CustomMaterialStyleIcons
 * import io.github.fotlab.fotlab.ui.icons.WhiteBalance
 *
 * Icon(
 *     imageVector = CustomMaterialStyleIcons.Filled.WhiteBalance,
 *     contentDescription = ...,
 * )
 * ```
 *
 * Glyphs are authored as monochrome geometry (black placeholder fill/stroke, exactly like
 * stock icons), so `Icon(tint = ...)` recolors them identically.
 */
object CustomMaterialStyleIcons {

    /**
     * Filled/baseline style group, mirroring [androidx.compose.material.icons.Icons.Filled].
     */
    object Filled
}
