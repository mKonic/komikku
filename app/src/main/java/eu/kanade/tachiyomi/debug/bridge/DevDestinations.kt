package eu.kanade.tachiyomi.debug.bridge

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedScreen
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsBrowseScreen
import eu.kanade.presentation.more.settings.screen.SettingsConnectionScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.SettingsDownloadScreen
import eu.kanade.presentation.more.settings.screen.SettingsEhScreen
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.more.settings.screen.SettingsMangadexScreen
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.more.settings.screen.SettingsSecurityScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.SettingsWebGpuScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen

/**
 * Every place [DevBridge] can be told to go, by name - what "go settings_webgpu" means. Tabs are the bottom bar's
 * destinations, screens are pushed on top of whatever is showing, and the ones taking an argument are written
 * `name:argument` (`manga:42`, `reader:42/1337`, `search:witch`).
 *
 * A destination belongs here as soon as driving the app to it by hand is a chore; the point is that the app knows the
 * way, rather than a caller re-deriving it from taps on a screenshot.
 */
internal object DevDestinations {

    /** Bottom bar destinations, by name. */
    val tabs: Map<String, () -> HomeScreen.Tab> = mapOf(
        "library" to { HomeScreen.Tab.Library() },
        "updates" to { HomeScreen.Tab.Updates },
        "history" to { HomeScreen.Tab.History },
        "browse" to { HomeScreen.Tab.Browse(toExtensions = false) },
        "sources" to { HomeScreen.Tab.Browse(toExtensions = false) },
        "extensions" to { HomeScreen.Tab.Browse(toExtensions = true) },
        "more" to { HomeScreen.Tab.More(toDownloads = false) },
        "downloads" to { HomeScreen.Tab.More(toDownloads = true) },
        "library_update_errors" to { HomeScreen.Tab.More(toDownloads = false, toLibraryUpdateErrors = true) },
    )

    /** Screens pushed onto the stack, by name. */
    val screens: Map<String, () -> Screen> = mapOf(
        "settings" to { SettingsScreen() },
        "settings_appearance" to { SettingsAppearanceScreen },
        "settings_library" to { SettingsLibraryScreen },
        "settings_reader" to { SettingsReaderScreen },
        "settings_webgpu" to { SettingsWebGpuScreen },
        "settings_downloads" to { SettingsDownloadScreen },
        "settings_tracking" to { SettingsTrackingScreen },
        "settings_connection" to { SettingsConnectionScreen },
        "settings_browse" to { SettingsBrowseScreen },
        "settings_data" to { SettingsDataScreen },
        "settings_security" to { SettingsSecurityScreen },
        "settings_advanced" to { SettingsAdvancedScreen },
        "settings_eh" to { SettingsEhScreen },
        "settings_mangadex" to { SettingsMangadexScreen },
        "about" to { AboutScreen() },
        "debug_info" to { DebugInfoScreen() },
        "download_queue" to { DownloadQueueScreen },
        "categories" to { CategoryScreen() },
        "stats" to { StatsScreen() },
        "extension_stores" to { ExtensionStoresScreen() },
    )

    /** Destinations that need an argument, by name, as `name:argument`. */
    val parameterised: Map<String, (String) -> Screen?> = mapOf(
        "manga" to { arg -> arg.toLongOrNull()?.let { MangaScreen(it) } },
        "search" to { arg -> arg.takeIf { it.isNotBlank() }?.let { GlobalSearchScreen(it) } },
    )

    /** Names a caller can ask for, for the bridge's `destinations` listing. */
    fun names(): List<String> = buildList {
        tabs.keys.forEach { add("tab: $it") }
        screens.keys.forEach { add("screen: $it") }
        parameterised.keys.forEach { add("screen: $it:<id>") }
        add("screen: reader:<mangaId>/<chapterId>")
        add("action: back")
        add("action: home")
    }
}
