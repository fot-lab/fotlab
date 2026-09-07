package io.github.fotlab.fotlab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Theme and shared primitives.
 *
 * This package ships **no** top app bar and **no** drawer component — every
 * feature package builds its own (`FOTLAB-UIXDES-000002` C1).
 */

// Static fallback scheme, used where the platform provides no dynamic colour.
private val Brand = Color(0xFF00658F)
private val OnBrand = Color(0xFFFFFFFF)
private val BrandContainer = Color(0xFFC8E6FF)
private val OnBrandContainer = Color(0xFF001E2C)
private val Neutral = Color(0xFF51606F)
private val OnNeutral = Color(0xFFFFFFFF)
private val NeutralContainer = Color(0xFFD5E4F7)
private val OnNeutralContainer = Color(0xFF0E1D2A)
private val Backdrop = Color(0xFFFBFBFD)
private val OnBackdrop = Color(0xFF1A1C1E)
private val Canvas = Color(0xFFFAF9FC)
private val OnCanvas = Color(0xFF1A1C1E)
private val Danger = Color(0xFFBA1A1A)
private val OnDanger = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = OnBrand,
    primaryContainer = BrandContainer,
    onPrimaryContainer = OnBrandContainer,
    secondary = Neutral,
    onSecondary = OnNeutral,
    secondaryContainer = NeutralContainer,
    onSecondaryContainer = OnNeutralContainer,
    background = Backdrop,
    onBackground = OnBackdrop,
    surface = Canvas,
    onSurface = OnCanvas,
    error = Danger,
    onError = OnDanger,
)

private val DarkColors = darkColorScheme(
    primary = BrandContainer,
    onPrimary = OnBrandContainer,
    secondary = NeutralContainer,
    onSecondary = OnNeutralContainer,
    background = OnBackdrop,
    onBackground = Backdrop,
    surface = OnCanvas,
    onSurface = Canvas,
    error = Danger,
    onError = OnDanger,
)

/**
 * Material3 theme: dynamic colour where the platform provides it, static
 * fallback otherwise (`FOTLAB-UIXDES-000001` R1, Q5).
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
