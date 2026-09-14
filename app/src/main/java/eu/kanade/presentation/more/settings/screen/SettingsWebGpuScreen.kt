package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.core.common.util.lang.withIOContext
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
        // while the screen is open. The first time it is asked, WebGPU is brought up to find out,
        // which loads the native library and opens the device, so it is asked off the main thread.
        // Null until it answers.
        val supported by produceState<Boolean?>(null) {
            value = withIOContext { WebGpuRenderer.isAvailable }
        }
        val useRenderer by basePreferences.highQualityRenderer().collectAsState()

        // Nothing below the switch does anything until the renderer is the one drawing.
        val active = supported == true && useRenderer

        return listOfNotNull(
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.webgpu_unsupported))
                .takeIf { supported == false },
            Preference.PreferenceItem.SwitchPreference(
                preference = basePreferences.highQualityRenderer(),
                title = stringResource(MR.strings.pref_high_quality_renderer),
                subtitle = stringResource(MR.strings.pref_high_quality_renderer_summary),
                enabled = supported == true,
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
            hdrPeak(readerPreferences, active),
            colorLutGroup(readerPreferences, active),
            pagedGroup(readerPreferences, active),
            continuousGroup(readerPreferences, active),
        )
    }

    /**
     * A ceiling on how far past white an HDR page's highlights may go, in stops, so the scale is
     * the panel's rather than the image's: 0 reads every page as SDR, 2 is the library's default.
     */
    @Composable
    private fun hdrPeak(readerPreferences: ReaderPreferences, active: Boolean): Preference {
        val stops by readerPreferences.hdrPeakStops().collectAsState()
        return Preference.PreferenceItem.SliderPreference(
            value = stops,
            valueRange = 0..4,
            title = stringResource(MR.strings.pref_hdr_peak),
            subtitle = stringResource(MR.strings.pref_hdr_peak_summary),
            valueString = if (stops == 0) {
                stringResource(MR.strings.off)
            } else {
                "${1 shl stops}x"
            },
            enabled = active,
            onValueChanged = { readerPreferences.hdrPeakStops().set(it) },
        )
    }

    /**
     * The renderer can run a 3D lookup table over the finished frame, which is how a display
     * profile, a warmer paper white or a film look all get expressed. `.cube` has no MIME type of
     * its own, so the picker has to offer everything and the parse is what rejects a wrong file.
     */
    @Composable
    private fun colorLutGroup(readerPreferences: ReaderPreferences, active: Boolean): Preference {
        val context = LocalContext.current
        val numberFormat = remember { NumberFormat.getPercentInstance() }
        val lut by readerPreferences.colorLut().collectAsState()
        val intensity by readerPreferences.colorLutIntensity().collectAsState()

        // The system caps how many persisted URI grants a package may hold, so the one being
        // replaced goes back rather than piling up a grant per table the user has ever tried.
        val release = { uri: String ->
            if (uri.isNotEmpty()) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri.toUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            Unit
        }

        val chooseLut = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                // Without this the URI stops reading the moment the reader is reopened.
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                release(lut)
                readerPreferences.colorLut().set(it.toString())
            }
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_color_lut),
            enabled = active,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_color_lut_file),
                    subtitle = lut.ifEmpty { stringResource(MR.strings.none) },
                    onClick = { chooseLut.launch(arrayOf("*/*")) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.action_remove),
                    enabled = lut.isNotEmpty(),
                    onClick = {
                        release(lut)
                        readerPreferences.colorLut().delete()
                    },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = intensity,
                    valueRange = 0..100,
                    title = stringResource(MR.strings.pref_color_lut_intensity),
                    valueString = numberFormat.format(intensity / 100f),
                    enabled = lut.isNotEmpty(),
                    onValueChanged = { readerPreferences.colorLutIntensity().set(it) },
                ),
            ),
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
