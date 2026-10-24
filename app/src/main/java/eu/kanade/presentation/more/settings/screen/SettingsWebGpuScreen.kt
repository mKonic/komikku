package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuShader
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuShaders
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.DragHandle
import sh.calvin.reorderable.ReorderableColumn
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.NumberFormat
import tachiyomi.core.common.preference.Preference as TachiyomiPreference

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
            hdrPeak(readerPreferences, active),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPreferences.disableDoubleTapGestures(),
                title = stringResource(MR.strings.pref_disable_double_tap_gestures),
                subtitle = stringResource(MR.strings.pref_disable_double_tap_gestures_summary),
                enabled = active,
            ),
            colorLutGroup(readerPreferences, active),
            filtersGroup(readerPreferences, active),
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

    /**
     * User WGSL shaders in the library's mpv-like format: image filters run once per image, tile
     * filters per high quality tile, each list in run order. A file is validated and copied in when
     * picked, so it outlives the picker's grant.
     */
    @Composable
    private fun filtersGroup(readerPreferences: ReaderPreferences, active: Boolean): Preference {
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            WebGpuShaders.prune(context, listOf(readerPreferences.imageFilters(), readerPreferences.tileFilters()))
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_shaders),
            enabled = active,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(stringResource(MR.strings.pref_image_filters)) {
                    ShaderList(MR.strings.pref_image_filters, readerPreferences.imageFilters())
                },
                Preference.PreferenceItem.CustomPreference(stringResource(MR.strings.pref_tile_filters)) {
                    ShaderList(MR.strings.pref_tile_filters, readerPreferences.tileFilters())
                },
            ),
        )
    }

    @Composable
    private fun ShaderList(labelRes: StringResource, pref: TachiyomiPreference<List<WebGpuShader>>) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val filters by pref.collectAsState()
        var error by remember { mutableStateOf<String?>(null) }

        val pickShader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    WebGpuShaders.import(context, uri, pref)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e)
                    error = e.message ?: e.toString()
                }
            }
        }

        error?.let { message ->
            AlertDialog(
                onDismissRequest = { error = null },
                confirmButton = {
                    TextButton(onClick = { error = null }) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                title = { Text(stringResource(MR.strings.image_filter_invalid)) },
                text = {
                    SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = message,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        }

        Column {
            HeadingItem(labelRes)
            ReorderableColumn(
                list = filters,
                onSettle = { from, to ->
                    // The indices are into the list as drawn, which the preference may have moved past.
                    val moved = filters.toMutableList().apply { add(to, removeAt(from)) }
                    val current = pref.get()
                    val files = current.mapTo(HashSet()) { it.file }
                    val kept = moved.filter { it.file in files }
                    val keptFiles = kept.mapTo(HashSet()) { it.file }
                    pref.set(kept + current.filter { it.file !in keptFiles })
                },
            ) { _, shader, _ ->
                key(shader.file) {
                    ReorderableItem {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = SettingsItemsPaddings.Horizontal, end = MaterialTheme.padding.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.DragHandle,
                                contentDescription = null,
                                modifier = Modifier.draggableHandle(),
                            )
                            Text(
                                text = shader.name,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = MaterialTheme.padding.medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { WebGpuShaders.remove(context, pref, shader) }) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Delete,
                                    contentDescription = stringResource(MR.strings.action_delete),
                                )
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    // WGSL has no MIME type of its own, so the picker offers everything and validation rejects the rest.
                    try {
                        pickShader.launch(arrayOf("*/*"))
                    } catch (e: ActivityNotFoundException) {
                        error = e.message ?: e.toString()
                    }
                },
                modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal - MaterialTheme.padding.small),
            ) {
                Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null)
                Text(
                    text = stringResource(MR.strings.action_add_shader),
                    modifier = Modifier.padding(start = MaterialTheme.padding.small),
                )
            }
        }
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
