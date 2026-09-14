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
    }

    // Better logging (EH)
    implementation(sylibs.xlog)
}
