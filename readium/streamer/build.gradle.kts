/*
 * Copyright 2018 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

plugins {
    id("readium.library-conventions")
}

android {
    namespace = "org.readium.r2.streamer"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(project(":readium:readium-shared"))

    @Suppress("GradleDependency")
    implementation(libs.timber)
    // AM NOTE: conflicting support libraries, excluding these
    implementation("com.mcxiaoke.koi:core:0.5.5") {
        exclude(module = "support-v4")
    }
    implementation(libs.kotlinx.coroutines.android)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.robolectric)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
