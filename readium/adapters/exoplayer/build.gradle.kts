/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

plugins {
    id("readium.library-conventions")
}

android {
    namespace = "org.readium.adapter.exoplayer"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(project(":readium:adapters:exoplayer:readium-adapter-exoplayer-audio"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
