import mihon.buildlogic.AndroidConfig

plugins {
    // KMK --> AGP 9's new DSL rejects KMP's androidTarget(); its KMP library plugin replaces it
    id("com.android.kotlin.multiplatform.library")
    // KMK <--
    kotlin("multiplatform")
    alias(libs.plugins.moko)
    id("mihon.code.lint")
    id("com.github.ben-manes.versions")
}

kotlin {
    androidLibrary {
        namespace = "tachiyomi.i18n"
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
    resourcesPackage.set("tachiyomi.i18n")
}
