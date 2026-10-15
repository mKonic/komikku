package exh.md.handlers

import exh.md.dto.MangaPlusLanguage
import exh.md.dto.MangaUpMangaPage
import exh.md.dto.MangaUpPageBlock
import exh.md.dto.MangaUpViewerResponse
import exh.md.dto.toMangaPlusLanguage
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

class MangaDexPageHandlersTest {

    @Test
    fun `Omoi chapter host accepts the old and new names without matching lookalikes`() {
        val handler = AzukiHandler(OkHttpClient(), "Test")

        handler.isChapterUrl("https://www.omoi.com/series/test/chapter/1") shouldBe true
        handler.isChapterUrl("https://azuki.co/chapters/1") shouldBe true
        handler.isChapterUrl("https://omoi.com.evil.example/chapters/1") shouldBe false
        handler.isChapterUrl("https://evil.example/omoi.com/chapters/1") shouldBe false
    }

    @Test
    fun `scrambled K Manga image is recognized by its key and not its CDN host`() {
        val handler = KMangaHandler(OkHttpClient())

        handler.isImageUrl("https://images.example.com/page.webp#seed:123:456") shouldBe true
        handler.isImageUrl("https://images.example.com/page.webp#seed:123:bad") shouldBe false
        handler.isImageUrl("https://images.example.com/page.webp") shouldBe false
    }

    @Test
    fun `MANGA Plus image routing does not match lookalike domains`() {
        val handler = MangaPlusHandler(OkHttpClient())

        handler.isImageUrl("https://images.tokyo-cdn.com/page.jpg") shouldBe true
        handler.isImageUrl("https://tokyo-cdn.com/page.jpg") shouldBe true
        handler.isImageUrl("https://tokyo-cdn.com.evil.example/page.jpg") shouldBe false
    }

    @Test
    fun `Manga UP unencrypted pages decode without key fields`() {
        val response = MangaUpViewerResponse(
            listOf(MangaUpPageBlock(chapterId = 42, pages = listOf(MangaUpMangaPage(url = "/page.webp")))),
        )

        val decoded = ProtoBuf.decodeFromByteArray<MangaUpViewerResponse>(ProtoBuf.encodeToByteArray(response))
        decoded.pageBlocks.single().chapterId shouldBe 42
        decoded.pageBlocks.single().pages.single().key shouldBe null
        decoded.pageBlocks.single().pages.single().iv shouldBe null
    }

    @Test
    fun `Latin American Spanish maps to MANGA Plus Spanish`() {
        "es-419".toMangaPlusLanguage() shouldBe MangaPlusLanguage.SPANISH
        "es-la".toMangaPlusLanguage() shouldBe MangaPlusLanguage.SPANISH
    }
}
