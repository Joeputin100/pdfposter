// RC41: Macrobenchmark module — measures cold/warm/hot startup time and
// frame timing on the main poster-builder screen. Output is a JSON report
// dumped to app/build/outputs/connected_android_test_additional_output/
// after each run. Numbers from emulator are coarse (~10-20% noise) but
// stable enough to spot regressions across RC pushes.
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.posterpdf.benchmark"
    compileSdk = 36

    defaultConfig {
        // Macrobenchmark requires API 23+ on the device and the test runner
        // class shipped by androidx.benchmark. minSdk 23 matches the app
        // module's floor.
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Macrobenchmark requires the target app to be NOT debuggable so the
    // measurements aren't skewed by dex layout / JIT debug paths. We point
    // at the release variant of :app.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Macrobench reads the variant `benchmark` from the target app; we
        // proxy that name through here. R8/minify off so the test names
        // stay readable in reports.
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    // junit4 macrobench runner + UiAutomator for tap/scroll interactions
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
}

androidComponents {
    beforeVariants(selector().all()) {
        // Only the `benchmark` variant of this module makes sense to build —
        // the others would give nonsensical numbers (debuggable, dexed, etc).
        it.enable = it.buildType == "benchmark"
    }
}
