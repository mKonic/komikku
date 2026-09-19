package eu.kanade.tachiyomi.data.cache

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * The cover keyers ask whether a manga has a custom cover for every cover on screen, so the answer is remembered
 * rather than stat'd each time. What matters is that it is still the right answer: it is kept against the manga's
 * cover-last-modified, and dropped outright by the two calls that add or remove a custom cover.
 */
class CoverCacheTest {

    private lateinit var dir: File
    private lateinit var cache: CoverCache

    @BeforeEach
    fun setUp() {
        dir = File.createTempFile("cover-cache", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        val context = mockk<Context>(relaxed = true)
        every { context.getExternalFilesDir(any()) } answers {
            File(dir, firstArg<String>()).also { it.mkdirs() }
        }
        cache = CoverCache(context)
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writeCustomCover(mangaId: Long) {
        cache.getCustomCoverFile(mangaId).writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun manga(id: Long) = mockk<Manga>(relaxed = true).also { every { it.id } returns id }

    @Test
    fun `a missing custom cover reads as missing, and one that is there reads as there`() {
        cache.hasCustomCover(1L, coverLastModified = 10L) shouldBe false

        writeCustomCover(1L)

        // A different cover-last-modified is the signal that the cover changed, so it is looked for again.
        cache.hasCustomCover(1L, coverLastModified = 11L) shouldBe true
    }

    @Test
    fun `the answer is remembered while cover-last-modified stays the same`() {
        cache.hasCustomCover(1L, coverLastModified = 10L) shouldBe false

        // Written behind the cache's back and with nothing to say the cover changed: the remembered answer stands.
        writeCustomCover(1L)

        cache.hasCustomCover(1L, coverLastModified = 10L) shouldBe false
    }

    @Test
    fun `saving a custom cover is enough on its own, without a new cover-last-modified`() {
        cache.hasCustomCover(1L, coverLastModified = 10L) shouldBe false

        // What migration does: it gives the target a custom cover and never touches its cover-last-modified.
        cache.setCustomCoverToCache(manga(1L), byteArrayOf(1, 2, 3).inputStream())

        cache.hasCustomCover(1L, coverLastModified = 10L) shouldBe true
    }

    @Test
    fun `deleting a custom cover is enough on its own too`() {
        writeCustomCover(1L)
        cache.hasCustomCover(1L, coverLastModified = 10L) shouldBe true

        cache.deleteCustomCover(1L) shouldBe true

        cache.hasCustomCover(1L, coverLastModified = 10L) shouldBe false
    }

    @Test
    fun `each manga is answered for on its own`() {
        writeCustomCover(2L)

        cache.hasCustomCover(1L, coverLastModified = 10L) shouldBe false
        cache.hasCustomCover(2L, coverLastModified = 10L) shouldBe true
    }

    @Test
    fun `no manga id has no custom cover`() {
        cache.hasCustomCover(null, coverLastModified = 10L) shouldBe false
    }
}
