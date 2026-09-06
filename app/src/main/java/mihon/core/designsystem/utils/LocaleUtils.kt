package mihon.core.designsystem.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import java.util.Locale

/**
 * The locale the composition is currently rendering in.
 *
 * Read this rather than [Locale.getDefault]: the default is a process-wide value Compose cannot
 * observe, so a screen that formats dates or names with it keeps the old language on screen until
 * something else happens to recompose it. Going through the configuration makes the locale a
 * composition input, and switching app language redraws immediately.
 */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale =
    ConfigurationCompat.getLocales(LocalConfiguration.current).get(0) ?: Locale.getDefault()
