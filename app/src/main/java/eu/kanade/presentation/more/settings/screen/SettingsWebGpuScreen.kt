package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.NumberFormat

/**
 * KMK: everything the WebGPU reader owns, in one place.
 *
 * The renderer switch used to sit among the advanced reader settings with nothing to configure
 * beside it, while its own settings were reachable only from inside the reader. Both live here now.
 */
object SettingsWebGpuScreen : SearchableSettings {

    private fun readResolve(): Any = SettingsWebGpuScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.webgpu_viewer

    @Composable
    override fun getPreferences(): List<Preference> {
        val basePreferences = remember { Injekt.get<BasePreferences>() }
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }

        // A failed init or a lost device is final for the process, so this answer cannot change
        // while the screen is open.
        val supported = remember { WebGpuRenderer.isAvailable }
        val useRenderer by basePreferences.highQualityRenderer().collectAsState()

        // Nothing below the switch does anything until the renderer is the one drawing.
        val active = supported && useRenderer

        return listOfNotNull(
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.webgpu_unsupported))
                .takeUnless { supported },
            Preference.PreferenceItem.SwitchPreference(
                preference = basePreferences.highQualityRenderer(),
                title = stringResource(MR.strings.pref_high_quality_renderer),
                subtitle = stringResource(MR.strings.pref_high_quality_renderer_summary),
                enabled = supported,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = readerPreferences.upscaler(),
                entries = ReaderPreferences.Upscaling.entries
                    .associateWith { stringResource(it.titleRes) }
                    .toImmutableMap(),
                title = stringResource(MR.strings.pref_upscaling),
                subtitle = stringResource(MR.strings.pref_upscaling_summary),
                enabled = active,
            ),
            pagedGroup(readerPreferences, active),
            continuousGroup(readerPreferences, active),
        )
    }

    @Composable
    private fun pagedGroup(readerPreferences: ReaderPreferences, active: Boolean) =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.pager_viewer),
            enabled = active,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.dualPageView(),
                    entries = ReaderPreferences.DualPageView.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_dual_page_view),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.transitionAnimation(),
                    entries = ReaderPreferences.TransitionAnimation.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_transition_animation),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.cutoutMode(),
                    entries = ReaderPreferences.CutoutMode.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_cutout_mode),
                ),
            ),
        )

    @Composable
    private fun continuousGroup(readerPreferences: ReaderPreferences, active: Boolean): Preference {
        val numberFormat = remember { NumberFormat.getPercentInstance() }
        val minWidth by readerPreferences.continuousMinWidth().collectAsState()
        val gap by readerPreferences.continuousGap().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.webtoon_viewer),
            enabled = active,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SliderPreference(
                    value = minWidth,
                    valueRange = 1..100,
                    title = stringResource(MR.strings.pref_continuous_minwidth),
                    valueString = numberFormat.format(minWidth / 100f),
                    onValueChanged = { readerPreferences.continuousMinWidth().set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = gap,
                    valueRange = 1..100,
                    title = stringResource(MR.strings.pref_continuous_gap),
                    valueString = numberFormat.format(gap / 100f),
                    onValueChanged = { readerPreferences.continuousGap().set(it) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.webtoonDisableZoomOut(),
                    title = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
                ),
            ),
        )
    }
}
