import mihon.buildlogic.AndroidConfig

plugins {
    // KMK -->
    id("com.android.kotlin.multiplatform.library")
    // KMK <--
    kotlin("multiplatform")
    alias(libs.plugins.moko)
    id("mihon.code.lint")
    id("com.github.ben-manes.versions")
}

kotlin {
    androidLibrary {
        namespace = "tachiyomi.i18n.sy"
        compileSdk = AndroidConfig.COMPILE_SDK
        minSdk = AndroidConfig.MIN_SDK
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.core)
            }
        }
    }
}

multiplatformResources {
    resourcesClassName.set("SYMR")
    resourcesPackage.set("tachiyomi.i18n.sy")
}
