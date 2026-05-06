plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    // RC21: enables auto-symbol upload + Crashlytics initialization. The
    // crashlytics-ktx runtime dep below pairs with this plugin.
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.posterpdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.posterpdf"
        // Bumped from 21 to 23 in Phase B because material3:1.5.0-alpha18
        // (required for the public MaterialExpressiveTheme + MotionScheme APIs)
        // declares minSdkVersion 23 in its manifest. Coverage loss vs. 21:
        // ~0.5% (Android 5.0 + 5.1 only). Revisit if material3 promotes those
        // APIs to a 1.5 stable that supports 21.
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-rc29.1"  // RC29.1 (CCSR bird logo)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            // RC21: read from env vars (KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD).
            // Falls back to "posterpdf" for local dev so anyone cloning the repo
            // can build without env-setup. Until the keystore itself is rotated,
            // the fallback IS the live password — TODO 4 step 1 (rotate keystore)
            // remains open. Env-var support is the prerequisite that lets a
            // future rotation land without re-touching this file.
            //
            // takeIf { isNotEmpty() } guards against GitHub Actions setting the
            // env var to "" when a secret is unset — which would otherwise
            // bypass the ?: fallback and corrupt the keystore decrypt.
            storePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "posterpdf"
            keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "posterpdf"
            keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "posterpdf"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        // RC19: bumped Java 1.8 → 17. The AGP 8.x toolchain handles Java
        // 17 source + bytecode end-to-end (R8/D8 desugar anything not
        // supported on minSdk 23 down to a runnable form), and JDK 17 is
        // the de-facto modern target for Android — what AGP 8 defaults to
        // and what Jetpack libraries certify against. Build JDK is 21
        // (.github/workflows/build-android.yml), which can produce 17
        // bytecode without trouble.
        //
        // Why not Java 21 source/target: AGP 8.5+ supports it, but some
        // 3rd-party libs in our graph (Firebase BOM 32.7.0, AndroidSVG,
        // PDFBox-Android 2.0.27) haven't certified against 21 yet —
        // sticking with 17 keeps the ecosystem boring.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
    buildFeatures {
        compose = true
        // Phase G3: BillingRepository.TEST_MODE flips on BuildConfig.DEBUG, so we
        // need the generated BuildConfig class. (AGP 8 turns it off by default.)
        buildConfig = true
    }
    // Phase G8: keep .tflite uncompressed inside the APK so the runtime can
    // mmap the model directly out of assets (Interpreter.MappedByteBuffer path).
    androidResources {
        noCompress.add("tflite")
    }
}

dependencies {
    val ktor_version = "2.3.6"
    val compose_bom = "2026.04.01"

    implementation(platform("androidx.compose:compose-bom:$compose_bom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.ui:ui-util")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // material3 1.5.0-alpha exposes MaterialExpressiveTheme + MotionScheme.expressive() as
    // public; the 1.4.x line in the BOM still has these APIs marked `internal`. Pinning
    // overrides the BOM constraint for material3 only — rest of Compose stays on stable.
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-svg:2.5.0")
    implementation("com.caverock:androidsvg-aar:1.4")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")

    // Ktor Client
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-android:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")

    // Ktor Server (if used as backend service)
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // PDF Generation
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // H-P2.7: QR code generation for the brand footer (Play Store URL).
    // Pure-Java; produces a BitMatrix we convert to a Bitmap then embed via
    // PDImageXObject in the PDF content stream.
    implementation("com.google.zxing:core:3.5.3")

    // Firebase (Auth + Firestore via REST through BackendApi; Auth used directly)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-common-ktx")
    // RC12b: Cloud Messaging for storage-billing + deletion-warning push.
    implementation("com.google.firebase:firebase-messaging-ktx")
    // RC12b: Firestore client for FCM token registration + storageBilling read.
    implementation("com.google.firebase:firebase-firestore-ktx")
    // RC19: Storage SDK for the AI-upscale upload + Functions SDK for the
    // requestUpscale onCall. The upscale flow needs to put the source bitmap
    // somewhere FAL can fetch (we use gs:// → backend signs it), then invoke
    // the callable that runs the FAL job and stores the result back in GCS.
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-functions-ktx")
    // RC21: Crashlytics for automatic crash + ANR capture. KTX flavor pulls
    // in coroutine-friendly extensions. Auto-initializes via the Firebase
    // Initializer; no custom code needed for crash reporting itself.
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    // RC21: JankStats reads the platform's frame metrics each frame and
    // emits a callback flagging slow / janky frames. We hook it to
    // Crashlytics custom keys (not recordException — jank isn't a crash)
    // so triage of a real crash includes the most recent slow-frame value.
    implementation("androidx.metrics:metrics-performance:1.0.0-beta01")

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // await() bridge for Play Services Tasks -> coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // kotlinx.serialization runtime (the Gradle plugin generates the Serializer code)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Phase G3: Google Play Billing Library v7 (KTX coroutine extensions).
    // Used by app/src/main/kotlin/com/pdfposter/billing/BillingRepository.kt.
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // Phase G8: on-device TFLite x4 super-resolution (ESRGAN-TF2).
    // Substituted from the plan's "Real-ESRGAN x4 INT8" because no canonical
    // TFLite distribution of Real-ESRGAN exists publicly without a custom
    // PyTorch->TFLite conversion. ESRGAN-TF2 is FP32, ~5MB, x4 upscaler.
    //
    // NOTE: tensorflow-lite-gpu is intentionally NOT included. UpscalerOnDevice
    // uses NnApiDelegate, not GPU. Including the gpu artifact pulled in classes
    // (GpuDelegateFactory$Options$GpuBackend) that R8 couldn't resolve in the
    // release minify step — see GH Actions run 25263859798 (2026-05-02). To
    // re-add later: also add `-dontwarn org.tensorflow.lite.gpu.**` to
    // proguard-rules.pro and switch UpscalerOnDevice to GpuDelegate.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // RC21 test battery: ActivityScenario lives in test:core, runner is what
    // AndroidJUnitRunner ships from. Both are required for SmokeTest.kt.
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
