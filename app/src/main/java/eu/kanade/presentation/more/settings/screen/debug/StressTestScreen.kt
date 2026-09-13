package eu.kanade.presentation.more.settings.screen.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.debug.stress.StressMode
import eu.kanade.tachiyomi.debug.stress.StressRunner
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.plus

class StressTestScreen : Screen() {

    companion object {
        const val TITLE = "Stress test"
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val status by StressRunner.status.collectAsState()

        var mode by rememberSaveable { mutableStateOf(StressMode.OVERLOAD) }
        var network by rememberSaveable { mutableStateOf(false) }
        var rounds by rememberSaveable { mutableStateOf("1") }
        val selected = remember { mutableStateListOf(*StressRunner.defaultScenarios(network = false).toTypedArray()) }

        Scaffold(
            topBar = { AppBar(title = TITLE, navigateUp = navigator::pop, scrollBehavior = it) },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding + PaddingValues(horizontal = 16.dp)) {
                item {
                    Text(
                        text = "Runs scenarios against the app through its own screens and jobs, and journals " +
                            "memory, main thread lag, failures, stalls, crashes and leaks to the external files " +
                            "folder under stress/. The journal is synced as it is written, and an eternal run picks " +
                            "itself back up whenever the app starts again after dying.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                status?.let { current ->
                    item {
                        val manifest = current.manifest
                        val state = when {
                            current.running -> "running"
                            manifest.active -> "active, not running in this process"
                            else -> "ended: ${manifest.stopReason}"
                        }
                        Text(
                            text = "Run ${manifest.runId}, ${manifest.mode.name.lowercase()}, process lifetime " +
                                "${manifest.session}: $state\n${current.dir.absolutePath}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(current.scenarios.entries.toList(), key = { "status-${it.key}" }) { (name, counts) ->
                        Text(
                            text = "$name: ${counts.passes} passed, ${counts.failures} failed, ${counts.skips} skipped" +
                                (if (counts.running) ", running" else "") +
                                (counts.lastError?.let { "\n  last error: $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item {
                        Button(
                            enabled = current.manifest.active,
                            onClick = { StressRunner.stop("stopped from the debug screen") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text("Stop the run")
                        }
                    }
                }

                item { Text("Mode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(StressMode.entries, key = { "mode-${it.name}" }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { mode = entry },
                    ) {
                        RadioButton(selected = mode == entry, onClick = { mode = entry })
                        Text(
                            when (entry) {
                                StressMode.ONCE -> "Once: each scenario in turn"
                                StressMode.OVERLOAD -> "Overload: every scenario at the same time"
                                StressMode.ETERNAL -> "Eternal: overload that never ends"
                            },
                        )
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { network = !network },
                    ) {
                        Checkbox(checked = network, onCheckedChange = { network = it })
                        Text("Allow the network: online sources, searches and downloads")
                    }
                }
                if (mode != StressMode.ETERNAL) {
                    item {
                        OutlinedTextField(
                            value = rounds,
                            onValueChange = { rounds = it.filter(Char::isDigit) },
                            label = { Text("Rounds") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }

                item { Text("Scenarios", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(StressRunner.scenarios, key = { "scenario-${it.name}" }) { scenario ->
                    val allowed = network || !scenario.needsNetwork
                    val toggle = {
                        if (scenario.name in selected) selected.remove(scenario.name) else selected.add(scenario.name)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = allowed) { toggle() },
                    ) {
                        Checkbox(
                            checked = allowed && scenario.name in selected,
                            enabled = allowed,
                            onCheckedChange = { toggle() },
                        )
                        Text(scenario.name + if (scenario.needsNetwork) " (network)" else "")
                    }
                }
                item {
                    val chosen = StressRunner.scenarios
                        .filter { it.name in selected && (network || !it.needsNetwork) }
                        .map { it.name }
                    Button(
                        enabled = chosen.isNotEmpty(),
                        onClick = { StressRunner.start(context, chosen, mode, network, rounds.toIntOrNull() ?: 1) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) {
                        Text("Start with ${chosen.size} scenarios")
                    }
                }
            }
        }
    }
}
