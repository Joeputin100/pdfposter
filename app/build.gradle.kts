plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
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
        versionName = "1.0-rc16"  // RC16 (sensor pulse, no glitter, Clarus centered, post-upscale flow)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "posterpdf"
            keyAlias = "posterpdf"
            keyPassword = "posterpdf"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
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
}
