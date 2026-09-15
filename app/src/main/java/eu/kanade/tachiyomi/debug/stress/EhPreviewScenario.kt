package eu.kanade.tachiyomi.debug.stress

import eu.kanade.tachiyomi.source.PagePreviewSource
import exh.source.EH_SOURCE_ID
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.system.measureTimeMillis

/**
 * Loads one E-Hentai gallery's page previews and times each fetch.
 *
 * Every preview on a gallery page is a window into the same sprite sheet, so the first fetch of a
 * sheet pays for the download and decode while the rest should only pay for a crop. Timing those
 * two apart is what shows whether the sheet is being reused or fetched again for every thumbnail -
 * see `EHentai.ThumbnailPreviewInterceptor`. A run where `repeat_avg_ms` sits near `first_avg_ms`
 * is one where the sheet is being downloaded over and over.
 */
object EhPreviewScenario : StressScenario {
    override val name = "ehpreview"
    override val needsNetwork = true

    /** Enough to cover a sheet several times over without leaning on E-Hentai. */
    private const val MAX_PREVIEWS = 30

    override suspend fun run(context: StressContext, iteration: Int) {
        val source = Injekt.get<SourceManager>().get(EH_SOURCE_ID) as? PagePreviewSource
            ?: throw StressSkip("the E-Hentai source is not enabled")

        val manga = source.getPopularManga(1).mangas.getOrNull(iteration % 5)
            ?: throw StressSkip("no galleries are listed")

        // The chapter list is only read for its last url, so the gallery's own url stands in.
        val previews = source.getPagePreviewList(manga, emptyList(), 1)
            .pagePreviews
            .take(MAX_PREVIEWS)
        if (previews.isEmpty()) throw StressSkip("that gallery has no previews")

        val seen = mutableSetOf<String>()
        var firstMs = 0L
        var firsts = 0
        var repeatMs = 0L
        var repeats = 0
        var failed = 0

        previews.forEach { preview ->
            // Previews carry their sheet in a query parameter; the older markup gives the image
            // directly, and then every preview is its own "sheet" and nothing should repeat.
            val sheet = if (preview.imageUrl.contains(SHEET_PARAM)) {
                preview.imageUrl.substringAfter(SHEET_PARAM).substringBefore('&')
            } else {
                preview.imageUrl
            }
            val firstOfSheet = seen.add(sheet)
            val ms = measureTimeMillis {
                // The body has to be read, not just closed: closing early times the headers and
                // never the sheet, which is the whole cost being measured.
                runCatching { source.fetchPreviewImage(preview).use { it.body.bytes() } }
                    .onFailure { failed++ }
            }
            if (firstOfSheet) {
                firstMs += ms
                firsts++
            } else {
                repeatMs += ms
                repeats++
            }
            context.alive()
        }

        context.step(
            "ehpreview",
            mapOf(
                "previews" to previews.size,
                "sheets" to seen.size,
                "firsts" to firsts,
                "first_avg_ms" to if (firsts > 0) firstMs / firsts else 0,
                "repeats" to repeats,
                "repeat_avg_ms" to if (repeats > 0) repeatMs / repeats else 0,
                "failed" to failed,
            ),
        )
        check(failed < previews.size) { "every preview failed to load" }
    }

    private const val SHEET_PARAM = "imageUrl="
}
