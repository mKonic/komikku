package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * KMK: the GPU renderer is off by default and this is where it is offered.
 *
 * Not simply defaulted on. While the switch is off the reader never touches the renderer's class,
 * and loading that class initialises WebGPU on the calling thread, so leaving it off costs a
 * reader who does not want it nothing at all. A device whose driver refuses falls back to the
 * standard reader by itself, but one that merely struggles cannot be detected from here, so this
 * asks rather than assumes.
 */
internal class RendererStep : OnboardingStep {

    override val isComplete: Boolean = true

    private val basePreferences: BasePreferences = Injekt.get()

    @Composable
    override fun Content() {
        val rendererPref = basePreferences.highQualityRenderer()
        val renderer by rendererPref.collectAsState()

        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            Text(stringResource(MR.strings.onboarding_renderer_info))

            SwitchPreferenceWidget(
                // No subtitle: the settings screen's one talks about the settings beneath it, and
                // there are none here. The paragraph above already says what this does.
                title = stringResource(MR.strings.pref_high_quality_renderer),
                checked = renderer,
                onCheckedChanged = { rendererPref.set(it) },
            )
        }
    }
}
