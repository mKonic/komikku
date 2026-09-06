package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

private val emptyResourcesElement = "<resources>\\s*</resources>|<resources\\s*/>".toRegex()

/**
 * Builds `locales_config.xml` from the translation files that actually carry strings.
 *
 * Lives in `:app` rather than `:i18n` because the AGP Kotlin Multiplatform library plugin has no
 * `preBuild` task to hang generation off, while an application variant can take a generated
 * resource directory directly.
 */
@CacheableTask
abstract class GenerateLocalesConfigTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val locales = stringFiles.files
            .filterNot { it.readText().contains(emptyResourcesElement) }
            .map {
                it.parentFile.name
                    .replace("base", "en")
                    .replace("-r", "-")
                    .replace("+", "-")
            }
            .sorted()
            .joinToString("\n") { "|   <locale android:name=\"$it\"/>" }

        val content = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
        $locales
        |</locale-config>
        """.trimMargin()

        outputDir.get().asFile.resolve("xml").apply { mkdirs() }
            .resolve("locales_config.xml")
            .writeText(content)
    }
}
