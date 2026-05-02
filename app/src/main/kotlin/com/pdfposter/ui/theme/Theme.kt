package com.pdfposter.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = BlueprintBlue700,
    onPrimary = PaperWarm,
    primaryContainer = BlueprintBlue100,
    onPrimaryContainer = BlueprintBlue900,
    secondary = TrimOrange500,
    onSecondary = InkBlack,
    secondaryContainer = TrimOrange100,
    onSecondaryContainer = TrimOrange700,
    tertiary = BlueprintBlue300,
    background = PaperWarm,
    onBackground = InkBlack,
    surface = PaperWarm,
    onSurface = InkBlack,
    surfaceVariant = PaperShadow,
    onSurfaceVariant = InkSoft,
)

private val DarkColors = darkColorScheme(
    primary = BlueprintBlue300,
    onPrimary = BlueprintBlue900,
    primaryContainer = BlueprintBlue700,
    onPrimaryContainer = BlueprintBlue50,
    secondary = TrimOrange300,
    onSecondary = InkBlack,
    secondaryContainer = TrimOrange700,
    onSecondaryContainer = TrimOrange100,
    tertiary = BlueprintBlue500,
    background = InkBlack,
    onBackground = PaperWarm,
    surface = InkBlack,
    onSurface = PaperWarm,
    surfaceVariant = InkSoft,
    onSurfaceVariant = PaperShadow,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PDFPosterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // brand-locked by default; flip to true to opt back into Android-12 dynamic theming
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
