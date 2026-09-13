package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SemVerTest {

    private fun version(text: String) = SemVer.parse(text)!!

    @Test
    fun `numbers compare as numbers, at any size`() {
        (version("v1.10.0") > version("v1.9.0")) shouldBe true
        (version("v12.345.6789") > version("v12.345.6788")) shouldBe true
        (version("99999999999999999999.0.0") > version("9999999999999999999.0.0")) shouldBe true
    }

    @Test
    fun `a release ranks above its own pre-releases`() {
        (version("v1.5.0") > version("v1.5.0-rc.1")) shouldBe true
        (version("v1.5.0-rc.1") > version("v1.4.0")) shouldBe true
    }

    @Test
    fun `pre-release identifiers follow the semver precedence`() {
        // The ordering example from semver.org, item 11.
        listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        )
            .map(::version)
            .zipWithNext()
            .forEach { (lower, higher) -> (lower < higher) shouldBe true }
    }

    @Test
    fun `build metadata and git describe suffixes leave the version as it was`() {
        version("v1.4.0+exp.sha.5114f85").compareTo(version("1.4.0")) shouldBe 0
        version("1.4.0-3-gabc1234-dirty").compareTo(version("1.4.0")) shouldBe 0
    }

    @Test
    fun `anything else is not a version`() {
        SemVer.parse("r1234") shouldBe null
        SemVer.parse("1.4") shouldBe null
        SemVer.parse("01.2.3") shouldBe null
        SemVer.parse("unknown") shouldBe null
    }
}
