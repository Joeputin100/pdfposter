package com.posterpdf

/**
 * Marks an instrumentation test as runnable ONLY on real Firebase Test Lab
 * hardware — never in the GitHub-Actions emulator battery (test-battery.yml).
 *
 * The emulator battery boots a headless, software-rendered x86 AVD
 * (`-gpu swiftshader_indirect`, no real GPU). Tests that exercise the real
 * on-device LiteRT/GPU ML upscale pipeline (e.g. [UpscalePdfDeviceTest]) can't
 * realistically complete there — they time out or crash — so they belong to the
 * FTL fan-out (ftl-upscale-test.yml) instead.
 *
 * test-battery.yml excludes these via
 * `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.posterpdf.FtlOnly`.
 * AndroidJUnitRunner reads the annotation reflectively at runtime, so this
 * class keeps Kotlin's default RUNTIME retention (do not narrow it).
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class FtlOnly
