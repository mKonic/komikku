package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes `shortcuts.xml` into the variant's generated resources with the `applicationId`
 * placeholder resolved.
 *
 * Replaces the `com.github.zellius.shortcut-helper` plugin, which reaches for AGP's removed
 * `AppExtension` and so cannot run on AGP 9.
 */
@CacheableTask
abstract class ReplaceShortcutsPlaceholderTask : DefaultTask() {

    @get:Input
    abstract val applicationId: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shortcutsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun replace() {
        val xmlDir = outputDir.get().asFile.resolve("xml")
        xmlDir.mkdirs()
        xmlDir.resolve("shortcuts.xml").writeText(
            shortcutsFile.get().asFile.readText().replace("\${applicationId}", applicationId.get()),
        )
    }
}
