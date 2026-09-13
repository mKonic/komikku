package eu.kanade.tachiyomi.data.updater

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * An update resumed onto what was left of the previous release's download installed as a corrupt APK: the new
 * release's tail appended to the old one's head. A download may only pick up its own partial file.
 */
class AppUpdateFilesTest {

    @TempDir
    lateinit var cacheDir: File

    private val previous = "https://github.com/mKonic/komikku/releases/download/v1.3.0/Komikku-arm64-v8a-v1.3.0.apk"
    private val next = "https://github.com/mKonic/komikku/releases/download/v1.4.0/Komikku-arm64-v8a-v1.4.0.apk"

    @Test
    fun `a download of another release starts from nothing, its own partial download resumes`() {
        AppUpdateFiles.apk(cacheDir).writeText("v1.3.0, installed from here")
        AppUpdateFiles.partial(cacheDir, previous).writeText("half of v1.3.0")
        AppUpdateFiles.partial(cacheDir, next).writeText("half of v1.4.0")

        AppUpdateFiles.clearOthers(cacheDir, next)

        AppUpdateFiles.apk(cacheDir).exists() shouldBe false
        AppUpdateFiles.partial(cacheDir, previous).exists() shouldBe false
        AppUpdateFiles.partial(cacheDir, next).readText() shouldBe "half of v1.4.0"
    }
}
