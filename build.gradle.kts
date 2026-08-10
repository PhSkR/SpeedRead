// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.12.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("com.android.library") version "8.12.2" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
    // KSP runs the Room annotation processor outside of kapt so its schema-location argument
    // does not get broadcast to every kapt processor (which produced the noisy "option not
    // recognized by any processor: [room.schemaLocation]" warning under kapt). Version line
    // tracks the Kotlin version: <kotlin>-<ksp-build>.
    id("com.google.devtools.ksp") version "2.2.20-2.0.2" apply false
}