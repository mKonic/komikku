package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ReaderSettingsScreenModelTest {

    /**
     * The model is built by hand inside the reader, outside any navigator. A Voyager scope there is
     * owned by whichever screen registered last and never ends, so every closed reader stayed in
     * memory through the model's flows. They have to run in the scope the reader hands over.
     */
    @Test
    fun `flows run in the reader's scope and end with it`() {
        val job = SupervisorJob()

        ReaderSettingsScreenModel(
            readerState = MutableStateFlow(ReaderViewModel.State()),
            scope = CoroutineScope(job + Dispatchers.Unconfined),
            onChangeReadingMode = {},
            onChangeOrientation = {},
            preferences = mockk(),
        )

        job.children.count() shouldBe 2
        runBlocking { job.cancelAndJoin() }
        job.children.count() shouldBe 0
    }
}
