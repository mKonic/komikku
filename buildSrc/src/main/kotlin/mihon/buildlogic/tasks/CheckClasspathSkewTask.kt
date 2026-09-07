package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a module resolves to one version on the compile classpath and a different
 * one on the runtime classpath.
 *
 * The APK is packaged from the runtime classpath, so such a split means the code was type-checked
 * against an API that is not the one actually shipped. Nothing catches it at build time; it
 * surfaces on device as `NoSuchMethodError` / `NoClassDefFoundError` the moment the affected code
 * runs.
 *
 * This is not hypothetical: v1.2.0 shipped with `androidx.compose.material3` pinned to
 * `1.5.0-alpha14` while `org.jetbrains.compose.material3` — a runtime-only transitive of
 * material-kolor, richeditor and aboutlibraries — dragged the runtime classpath up to
 * `1.5.0-alpha22`, where the expressive `FilterChip` overload had swapped a `Dp` parameter for an
 * `Arrangement.Horizontal` and so lost its mangled `FilterChip-Qi0uq5o` name. Global search and
 * the source browse toolbar crashed on sight.
 */
abstract class CheckClasspathSkewTask : DefaultTask() {

    @get:Input
    abstract val compileRoot: Property<ResolvedComponentResult>

    @get:Input
    abstract val runtimeRoot: Property<ResolvedComponentResult>

    @get:Input
    abstract val variantName: Property<String>

    /** Only modules whose group starts with one of these are compared. */
    @get:Input
    abstract val groupPrefixes: ListProperty<String>

    @TaskAction
    fun check() {
        val prefixes = groupPrefixes.get()
        val compile = versionsOf(compileRoot.get(), prefixes)
        val runtime = versionsOf(runtimeRoot.get(), prefixes)

        val skewed = compile.keys
            .intersect(runtime.keys)
            .filter { compile.getValue(it) != runtime.getValue(it) }
            .sorted()

        if (skewed.isEmpty()) return

        val detail = skewed.joinToString("\n") {
            "    $it — compiled against ${compile.getValue(it)}, packaged ${runtime.getValue(it)}"
        }
        throw GradleException(
            "Compile/runtime version skew on the ${variantName.get()} classpath:\n$detail\n\n" +
                "The APK ships the packaged version, so any API that changed between the two fails " +
                "on device instead of here. Pin the module in gradle/*.versions.toml to the version " +
                "the runtime classpath resolves to.",
        )
    }

    private fun versionsOf(
        root: ResolvedComponentResult,
        prefixes: List<String>,
    ): Map<String, String> {
        val versions = mutableMapOf<String, String>()
        val seen = mutableSetOf<ComponentIdentifier>()

        fun walk(node: ResolvedComponentResult) {
            if (!seen.add(node.id)) return
            // A BOM carries constraints, not classes, so its own version cannot break anything at
            // runtime — but it skews everything it constrains, and those modules are reported.
            if (!node.isPlatform()) {
                node.moduleVersion
                    ?.takeIf { id -> prefixes.any { id.group.startsWith(it) } }
                    ?.let { versions["${it.group}:${it.name}"] = it.version }
            }
            node.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { walk(it.selected) }
        }

        walk(root)
        return versions
    }

    private fun ResolvedComponentResult.isPlatform(): Boolean = variants.any { variant ->
        val key = variant.attributes.keySet()
            .firstOrNull { it.name == Category.CATEGORY_ATTRIBUTE.name }
            ?: return@any false
        @Suppress("UNCHECKED_CAST")
        val category = variant.attributes.getAttribute(key as Attribute<Any>)?.toString()
        category == Category.REGULAR_PLATFORM || category == Category.ENFORCED_PLATFORM
    }
}
