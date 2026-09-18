package eu.kanade.tachiyomi.debug.bridge

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.debug.stress.StressHooks
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetLibraryManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference

/**
 * Drives the app from adb by name rather than by tapping at coordinates read off a screenshot: `go settings_webgpu`
 * opens that settings page wherever the app happens to be, `state` says what is on screen, and `pref` reads or writes
 * a preference in the running app.
 *
 * Reached through [DevBridgeProvider], which only exists in a debug build. Everything here refuses to run outside a
 * debug or preview build anyway, so a stray call cannot drive a release.
 *
 * See `local-tests/drive.sh` for the host side.
 */
object DevBridge {

    private const val TIMEOUT_MS = 10_000L
    private const val LIST_LIMIT = 50

    val enabled: Boolean get() = isDebugBuildType || isPreviewBuildType

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    /** The activity on screen, which is what `state` describes and what a reader command needs. */
    private val currentActivity: Activity?
        get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    /** Starts tracking which activity is on screen. Called once, when the provider is created. */
    fun install(application: Application) {
        if (!enabled) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumed = WeakReference(activity)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /**
     * Runs one command, blocking the caller's binder thread until the app has done it. Returns what to print; a
     * failure is a line starting with `error:` rather than an exception, so the caller always gets an answer.
     */
    fun call(context: Context, method: String, arg: String?): String {
        if (!enabled) return "error: not a debug build"
        return try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    when (method) {
                        "destinations" -> DevDestinations.names().joinToString("\n")
                        "go" -> go(arg ?: return@withTimeout "error: go needs a destination")
                        "state" -> state()
                        "pref" -> pref(context, arg ?: return@withTimeout "error: pref needs a key")
                        "library" -> library(arg)
                        "chapters" -> chapters(arg ?: return@withTimeout "error: chapters needs a manga id")
                        else -> "error: unknown command '$method'"
                    }
                }
            }
        } catch (e: Exception) {
            "error: ${e::class.simpleName}: ${e.message}"
        }
    }

    private suspend fun go(destination: String): String {
        val (name, argument) = destination.split(':', limit = 2)
            .let { it[0].trim() to it.getOrNull(1)?.trim() }

        DevDestinations.tabs[name]?.let { tab ->
            // The tabs live under whatever is pushed on top of home, so switching one while a screen is open leaves
            // that screen showing. Asking for a tab means wanting to be on it.
            onMain { StressHooks.navigator?.popUntilRoot() }
            HomeScreen.openTab(tab())
            return "ok: tab $name"
        }

        when (name) {
            "back" -> return onMain {
                val navigator = StressHooks.navigator ?: return@onMain "error: no screen stack"
                if (navigator.canPop) {
                    navigator.pop()
                    "ok: back"
                } else {
                    // Read the name before finishing it: afterwards this is a finishing activity and reads as none.
                    val closing = currentActivity
                    closing?.finish()
                    "ok: closed ${closing?.javaClass?.simpleName ?: "screen"}"
                }
            }
            "home" -> return onMain {
                StressHooks.navigator?.popUntilRoot() ?: return@onMain "error: no screen stack"
                "ok: home"
            }
            "reader" -> return openReader(argument)
        }

        val screen: Screen = DevDestinations.screens[name]?.invoke()
            ?: DevDestinations.parameterised[name]?.invoke(argument.orEmpty())
            ?: return "error: no destination '$name' - try destinations"

        return onMain {
            val navigator = StressHooks.navigator ?: return@onMain "error: the app is not showing its screen stack"
            navigator.push(screen)
            "ok: ${screen::class.simpleName}"
        }
    }

    private suspend fun openReader(argument: String?): String {
        val parts = argument?.split('/', ':')?.mapNotNull { it.trim().toLongOrNull() }
        if (parts == null || parts.size < 2) return "error: reader needs mangaId/chapterId"
        return onMain {
            val activity = StressHooks.activity ?: currentActivity ?: return@onMain "error: the app is not running"
            activity.startActivity(ReaderActivity.newIntent(activity, parts[0], parts[1]))
            "ok: reader ${parts[0]}/${parts[1]}"
        }
    }

    private suspend fun state(): String = onMain {
        val json = JSONObject()
        val activity = currentActivity
        json.put("activity", activity?.javaClass?.simpleName ?: "none")
        // A provider call starts the process by itself, so the app can answer before it has an activity or its
        // dependency graph. Anything reading the library has to wait for this.
        json.put("ready", StressHooks.activity != null)

        StressHooks.navigator?.let { navigator ->
            json.put("screen", navigator.lastItem::class.simpleName)
            json.put("stack", JSONArray(navigator.items.map { it::class.simpleName }))
        }

        (activity as? ReaderActivity)?.let { reader ->
            val readerState = reader.viewModel.state.value
            json.put(
                "reader",
                JSONObject()
                    .put("manga", readerState.manga?.title)
                    .put("chapter", readerState.currentChapter?.chapter?.name)
                    .put("page", readerState.currentPage)
                    .put("pages", readerState.totalPages)
                    .put("viewer", readerState.viewer?.javaClass?.simpleName)
                    .put("menuVisible", readerState.menuVisible),
            )
        }
        json.toString()
    }

    /**
     * The library, so a caller has the ids the `manga:` and `reader:` destinations take without reading the database
     * out from under the running app - which the phone cannot do anyway, having no sqlite3. [query] filters by title.
     */
    private suspend fun library(query: String?): String {
        val entries = Injekt.get<GetLibraryManga>().await()
            .map { it.manga }
            .distinctBy { it.id }
            .let { all ->
                if (query.isNullOrBlank()) all else all.filter { it.title.contains(query, ignoreCase = true) }
            }
            .take(LIST_LIMIT)
        return JSONArray(
            entries.map { manga ->
                JSONObject()
                    .put("id", manga.id)
                    .put("title", manga.title)
                    .put("source", manga.source)
            },
        ).toString()
    }

    /** A manga's chapters, newest first: the other half of what `reader:<mangaId>/<chapterId>` needs. */
    private suspend fun chapters(argument: String): String {
        val mangaId = argument.trim().toLongOrNull() ?: return "error: '$argument' is not a manga id"
        val chapters = Injekt.get<GetChaptersByMangaId>().await(mangaId).take(LIST_LIMIT)
        if (chapters.isEmpty()) return "error: no chapters for manga $mangaId"
        return JSONArray(
            chapters.map { chapter ->
                JSONObject()
                    .put("id", chapter.id)
                    .put("name", chapter.name)
                    .put("number", chapter.chapterNumber)
                    .put("read", chapter.read)
            },
        ).toString()
    }

    /**
     * `key` reads a preference, `key=value` writes one. The value's type follows what is already stored, so a
     * preference the app reads as a boolean cannot be turned into a string that crashes its reader.
     */
    private fun pref(context: Context, argument: String): String {
        val (key, value) = argument.split('=', limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim() }
        if (key.isEmpty()) return "error: pref needs a key"

        val preferences = Injekt.get<PreferenceStore>().getAll()
        if (value == null) {
            return preferences[key]?.let { "$key=$it" } ?: "error: no preference '$key'"
        }

        val current = preferences[key]
        val parsed: Any = when (current) {
            is Boolean -> value.toBooleanStrictOrNull() ?: return "error: '$value' is not true or false"
            is Int -> value.toIntOrNull() ?: return "error: '$value' is not a whole number"
            is Long -> value.toLongOrNull() ?: return "error: '$value' is not a whole number"
            is Float -> value.toFloatOrNull() ?: return "error: '$value' is not a number"
            is String -> value
            null -> value
            else -> return "error: '$key' holds a ${current::class.simpleName}, which this cannot write"
        }

        val editor = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE).edit()
        when (parsed) {
            is Boolean -> editor.putBoolean(key, parsed)
            is Int -> editor.putInt(key, parsed)
            is Long -> editor.putLong(key, parsed)
            is Float -> editor.putFloat(key, parsed)
            else -> editor.putString(key, parsed.toString())
        }
        editor.apply()
        return "ok: $key=$parsed"
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }
}
