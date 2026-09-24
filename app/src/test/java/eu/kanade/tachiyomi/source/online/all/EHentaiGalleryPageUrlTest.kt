package eu.kanade.tachiyomi.source.online.all

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EHentaiGalleryPageUrlTest {

    @Test
    fun `drops nw so the redirect cannot swallow the page`() {
        EHentai.galleryPageUrl("https://e-hentai.org/g/3950396/772fd7cf31/?nw=always", 2) shouldBe
            "https://e-hentai.org/g/3950396/772fd7cf31/?p=2"
    }

    @Test
    fun `first page has no p`() {
        EHentai.galleryPageUrl("https://e-hentai.org/g/3950396/772fd7cf31/?nw=always&p=4", 0) shouldBe
            "https://e-hentai.org/g/3950396/772fd7cf31/"
    }

    @Test
    fun `a plain gallery url only gains p`() {
        EHentai.galleryPageUrl("https://exhentai.org/g/1/abc/", 1) shouldBe "https://exhentai.org/g/1/abc/?p=1"
    }
}
