plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")

    alias(libs.plugins.valkyrie)
}

android {
    namespace = "mihon.icons.materialsymbols"

    // KMK -->
    // Valkyrie generates into the "main" source set, but only wires its per-variant tasks into the
    // build, so the generated accessors have to be put on the compile path by hand.
    sourceSets.getByName("main").kotlin.srcDir(
        layout.buildDirectory.dir("generated/sources/valkyrie/main/kotlin"),
    )
    // KMK <--
}

// KMK -->
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("generateValkyrieImageVectorMain")
}
// KMK <--

valkyrie {
    packageName = "mihon.icons.materialsymbols"
    generateAtSync = true

    imageVector {
        suppressUnusedReceiverWarning = true
    }

    iconPack {
        name = "MaterialSymbols"
        targetSourceSet = "main"

        nested {
            name = "Rounded"
            sourceFolder = "rounded"
            autoMirror = false
        }

        nested {
            name = "RoundedFilled"
            sourceFolder = "roundedFilled"
            autoMirror = false
        }

        nested {
            name = "AutoMirroredRounded"
            sourceFolder = "autoMirroredRounded"
            autoMirror = true
        }
    }
}

dependencies {
    api(compose.ui)
}
