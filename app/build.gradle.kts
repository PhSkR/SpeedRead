plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    kotlin("kapt")
}

android {
    namespace = "com.speedread.rsvp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.speedread.rsvp"
        minSdk = 26
        targetSdk = 36
        versionCode = 91
        versionName = "1.14.73"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Sherpa-ONNX ships native .so libs at ~25 MB per ABI (arm64-v8a, armeabi-v7a, x86_64).
        // Every real Android phone is ARM, so we drop x86_64 to keep APK size in check (~30 MB
        // saved). Cost: Android Studio's default emulator image is x86_64 — switch the AVD to an
        // ARM64 image if emulator testing of Neural TTS is needed. The filter applies to ALL
        // native libs in the build, not just Sherpa's, so any future native dependency inherits
        // the same ARM-only constraint automatically.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            // Opt in to Kotlin 2.2 annotation-target change (KT-73255) so Hilt's @ApplicationContext
            // (and similar) apply to both the parameter and the backing field without triggering warnings.
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = false // Don't stop the build on lint errors
    }
    testOptions {
        // Return default values (0/null/false) for Android framework calls in JVM unit tests
        // so ViewModel logic that indirectly touches android.util.Log does not throw
        // "Method not mocked" from the stubbed android.jar used at test-classpath.
        unitTests.isReturnDefaultValues = true
    }
}

// Room schema export. The generated JSON under app/schemas/<db-fqcn>/<version>.json is the
// versioned baseline future migrations are tested against (MigrationTestHelper). Routed
// through KSP rather than kapt so the option does not surface in kapt's "not recognized by
// any processor" warning list.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":rsvp-engine"))
    implementation(project(":data-importers"))
    implementation(project(":core-ui"))
    
    // Core Android
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    
    // UI
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // MediaSessionCompat + MediaStyle notification + MediaButtonReceiver for background TTS.
    implementation("androidx.media:media:1.7.0")
    
    // Architecture Components
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.4")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    
    // Database. Room 2.6+ supports KSP and recommends it over kapt — KSP runs ~2x faster on
    // incremental builds and does not produce kapt's "option not recognized" noise around
    // room.schemaLocation. Hilt remains on kapt below (Hilt-on-KSP is a separate larger
    // migration with its own compatibility matrix).
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    
    // Dependency Injection
    implementation("com.google.dagger:hilt-android:2.57.2")
    kapt("com.google.dagger:hilt-compiler:2.57.2")
    
    // Charts
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    
    // FlexboxLayoutManager for tags
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.5")

    // Pull-to-refresh for the voice catalog list. Standard AndroidX widget; no transitive concerns.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Apache Commons Compress: drives tar.bz2 extraction for the in-app Piper voice catalog
    // (Sherpa-ONNX ships each voice as a vits-piper-<id>.tar.bz2). The artifact pulls a few
    // optional transitive deps (xz, brotli, zstd) we do not need for tar+bzip2; excluding them
    // keeps the APK trimmer and avoids a JVM-only `xz` lib that has no Android variant.
    implementation("org.apache.commons:commons-compress:1.27.1") {
        exclude(group = "org.tukaani", module = "xz")
        exclude(group = "com.github.luben", module = "zstd-jni")
        exclude(group = "org.brotli", module = "dec")
    }

    // Neural TTS runtime (Sherpa-ONNX). The AAR (sherpa-onnx-<ver>.aar) lives in app/libs/ and
    // is linked via the fileTree glob below — no version reference needed. If the AAR is missing
    // from a clean checkout, NeuralTtsEngine.kt's `com.k2fsa.sherpa.onnx.*` imports will fail to
    // resolve at compile time; redownload from https://github.com/k2-fsa/sherpa-onnx/releases
    // (single AAR, not the per-ABI split zip) and place it in app/libs/. See
    // app/libs/README-NEURAL-TTS.md for the full recovery procedure.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.13.8")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
}