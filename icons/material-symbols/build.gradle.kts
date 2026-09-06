plugins {
    id("mihon.library")
    id("mihon.library.compose")

    alias(libs.plugins.valkyrie)
}

android {
    namespace = "mihon.icons.materialsymbols"
}

// KMK -->
// Valkyrie generates into the "main" source set but only wires up its per-variant tasks, so the
// generated accessors have to be put on the compile path by hand.
val valkyrieGeneratedDir = layout.buildDirectory.dir("generated/sources/valkyrie/main/kotlin")

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(
            valkyrieGeneratedDir.get().asFile.also { it.mkdirs() }.absolutePath,
        )
    }
}

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
