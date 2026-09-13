package eu.kanade.presentation.more.settings.screen.debug

import android.content.Context
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.ui.reader.soak.ReaderSoakTest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderSoakTestScreen : Screen() {

    companion object {
        const val TITLE = "Reader soak test"
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { Model() }
        val library by screenModel.library.collectAsState()

        var chaptersPerSeries by rememberSaveable { mutableStateOf("3") }
        var rounds by rememberSaveable { mutableStateOf("5") }
        val selected = remember { mutableStateListOf<Long>() }
        LaunchedEffect(Unit) {
            if (selected.isEmpty()) selected.addAll(ReaderSoakTest.pickSeries())
        }

        Scaffold(
            topBar = { AppBar(title = TITLE, navigateUp = navigator::pop, scrollBehavior = it) },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding + PaddingValues(horizontal = 16.dp)) {
                item {
                    Text(
                        text = "Opens the first chapters of the chosen series one after another, each in a " +
                            "fresh reader, and turns every page as soon as the one before it is on screen, for " +
                            "the given number of rounds. One series per reading mode in the library comes " +
                            "ticked. Memory is appended to a CSV in the app's external files folder around " +
                            "every chapter. The reader is incognito for the whole run; leaving a reader stops it.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                item { NumberField("Chapters per series", chaptersPerSeries) { chaptersPerSeries = it } }
                item { NumberField("Rounds", rounds) { rounds = it } }
                item {
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            screenModel.start(
                                context = context,
                                mangaIds = selected.toList(),
                                chaptersPerSeries = chaptersPerSeries.toIntOrNull() ?: 3,
                                rounds = rounds.toIntOrNull() ?: 5,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) {
                        Text("Start with ${selected.size} series")
                    }
                }
                items(library, key = { it.id }) { manga ->
                    val toggle = {
                        if (manga.id in selected) selected.remove(manga.id) else selected.add(manga.id)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { toggle() },
                    ) {
                        Checkbox(checked = manga.id in selected, onCheckedChange = { toggle() })
                        Text(text = manga.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    @Composable
    private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter(Char::isDigit)) },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }

    private class Model(
        private val getLibraryManga: GetLibraryManga = Injekt.get(),
        private val sourceManager: SourceManager = Injekt.get(),
    ) : ScreenModel {

        /** Only series whose source is installed; a reader has nothing to load for the rest. */
        val library = flow {
            val installed = getLibraryManga.await()
                .map { it.manga }
                .distinctBy { it.id }
                .filter { sourceManager.get(it.source) != null }
                .sortedBy { it.title.lowercase() }
            emit(installed)
        }
            .stateIn(ioCoroutineScope, SharingStarted.WhileSubscribed(), emptyList<Manga>())

        fun start(context: Context, mangaIds: List<Long>, chaptersPerSeries: Int, rounds: Int) {
            ioCoroutineScope.launch { ReaderSoakTest.start(context, mangaIds, chaptersPerSeries, rounds) }
        }
    }
}
