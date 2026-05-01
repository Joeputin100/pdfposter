# Add project specific ProGuard rules here.
# This file is referenced from app/build.gradle.kts buildTypes.release.proguardFiles().

# ────────────────────────────────────────────────────────────────────────
# Suppress R8 warnings about transitively-referenced optional classes
# ────────────────────────────────────────────────────────────────────────
# pdfbox-android pulls in JPEG-2000 codec stubs from Gemalto that we never use
# (the app generates PDFs with embedded JPEG/PNG, never JP2). Without these
# -dontwarn rules R8 fails because the stubs reference classes not on the
# runtime classpath.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder

# pdfbox-android also requests an SLF4J static binder. We don't bind one
# (logs go to a NOP impl per SLF4J's fallback), and that's fine.
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.**

# Netty's BlockHound debugger integration is transitively referenced via Ktor
# but is debug-only. Production builds don't need it.
-dontwarn reactor.blockhound.**
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# Coil's GIF / video / network-images decoders are NOT in our dep list
# (we only ship coil-compose + coil-svg). Suppress missing-class warnings
# for those decoders without hiding bugs in the modules we DO use.
-dontwarn coil.gif.**
-dontwarn coil.video.**
-dontwarn coil.network.**

# ────────────────────────────────────────────────────────────────────────
# kotlinx.serialization R8 rules
# https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
# ────────────────────────────────────────────────────────────────────────

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic annotations are read at runtime via reflection.
-keepclasseswithmembers,allowobfuscation class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ────────────────────────────────────────────────────────────────────────
# PDFBox-Android — heavily reflective; keep public surface to be safe.
# ────────────────────────────────────────────────────────────────────────
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ────────────────────────────────────────────────────────────────────────
# Ktor + Netty
# ────────────────────────────────────────────────────────────────────────
-dontwarn io.netty.**
-dontwarn io.ktor.**

# ────────────────────────────────────────────────────────────────────────
# Compose preview tooling — these annotations are used by the IDE,
# not at runtime, but R8 sometimes warns. Safe to drop.
# ────────────────────────────────────────────────────────────────────────
-dontwarn androidx.compose.ui.tooling.preview.**
