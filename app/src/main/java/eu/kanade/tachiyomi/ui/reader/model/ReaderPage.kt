package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    // SY -->
    /** Value to check if this page is used to as if it was too wide */
    var shiftedPage: Boolean = false,
    /** Value to check if a page is can be doubled up, but can't because the next page is too wide */
    var isolatedPage: Boolean = false,
    // SY <--
    var stream: (() -> InputStream)? = null,

) : Page(index, url, imageUrl, null), ReaderItem {

    open lateinit var chapter: ReaderChapter

    /** Value to check if a page is too wide to be doubled up */
    var fullPage: Boolean = false
        set(value) {
            field = value
            if (value) shiftedPage = false
        }

    // KMK -->
    private val _displayState = MutableStateFlow(DisplayState.Pending)

    /**
     * Whether a viewer has this page's image ready on screen, or has given up on it. [status] only says
     * the bytes arrived; this is set once they decoded (or failed to), which is what the soak test
     * waits on before turning the page.
     */
    val displayState: StateFlow<DisplayState> = _displayState.asStateFlow()

    fun markDisplayed() {
        _displayState.value = DisplayState.Displayed
    }

    fun markDisplayFailed() {
        _displayState.value = DisplayState.Failed
    }

    enum class DisplayState { Pending, Displayed, Failed }
    // KMK <--
}
