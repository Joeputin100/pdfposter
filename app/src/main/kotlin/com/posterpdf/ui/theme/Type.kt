package com.posterpdf.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.posterpdf.R

// Provider for Compose Google Fonts. The certificate hashes below come from the
// androidx Google Fonts sample and authenticate the Google Fonts request signer.
private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val fraunces = GoogleFont("Fraunces")
private val manrope = GoogleFont("Manrope")

private val FrauncesFamily = FontFamily(
    Font(googleFont = fraunces, fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = fraunces, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = fraunces, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = fraunces, fontProvider = googleFontProvider, weight = FontWeight.Bold),
    Font(googleFont = fraunces, fontProvider = googleFontProvider, weight = FontWeight.Bold, style = FontStyle.Italic),
)

private val ManropeFamily = FontFamily(
    Font(googleFont = manrope, fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = manrope, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = manrope, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = manrope, fontProvider = googleFontProvider, weight = FontWeight.Bold),
)

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold,    fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold,   fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FrauncesFamily, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold,      fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = ManropeFamily,  fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = ManropeFamily,   fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = ManropeFamily,    fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = ManropeFamily,   fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = ManropeFamily,    fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = ManropeFamily,   fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = ManropeFamily,  fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = ManropeFamily,   fontWeight = FontWeight.Medium,   fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)
