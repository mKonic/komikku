import mihon.buildlogic.Config

plugins {
    id("mihon.library")
}

android {
    namespace = "mihon.telemetry"

    sourceSets {
        getByName("main") {
            if (Config.includeTelemetry) {
                kotlin.srcDirs("src/firebase/kotlin")
            } else {
                kotlin.srcDirs("src/noop/kotlin")
                manifest.srcFile("src/noop/AndroidManifext.xml")
            }
        }
    }
}

dependencies {
    if (Config.includeTelemetry) {
        implementation(platform(libs.firebase.bom))
        // Native crashes too: the reader's decoder and renderer are native, and so were most crashes seen so far
        implementation(libs.firebase.crashlytics.ndk)
        // Crashlytics reads its breadcrumbs, crash-free users and velocity alerts out of Analytics, so a crash
        // without this is a stack trace with no record of what the reader was doing on the way into it
        implementation(libs.firebase.analytics)
    }

    // Better logging (EH)
    implementation(sylibs.xlog)
}
