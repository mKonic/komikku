package tachiyomi.core.metadata.comicinfo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Execution(ExecutionMode.CONCURRENT)
class ComicInfoDateTest {

    @Test
    fun `a full date is the start of that day`() {
        comicInfo("2024", "7", "15").dateMillis(UTC) shouldBe startOf(2024, 7, 15, UTC)
    }

    @Test
    fun `a missing month and day mean the first`() {
        comicInfo("2024", null, null).dateMillis(UTC) shouldBe startOf(2024, 1, 1, UTC)
        comicInfo("2024", "3", null).dateMillis(UTC) shouldBe startOf(2024, 3, 1, UTC)
    }

    @Test
    fun `no usable year gives no date`() {
        comicInfo(null, "7", "15").dateMillis(UTC) shouldBe null
        comicInfo("", "7", "15").dateMillis(UTC) shouldBe null
        comicInfo("MMXXIV", "7", "15").dateMillis(UTC) shouldBe null
        comicInfo("-1", "7", "15").dateMillis(UTC) shouldBe null
    }

    @Test
    fun `parts that are not a real date give no date`() {
        comicInfo("2023", "2", "29").dateMillis(UTC) shouldBe null
        comicInfo("2024", "13", "1").dateMillis(UTC) shouldBe null
        comicInfo("2024", "0", "1").dateMillis(UTC) shouldBe null
    }

    @Test
    fun `whitespace around a value is tolerated`() {
        comicInfo(" 2024\n", " 7 ", "15 ").dateMillis(UTC) shouldBe startOf(2024, 7, 15, UTC)
    }

    @Test
    fun `the day starts in the zone it is read in`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        comicInfo("2024", "7", "15").dateMillis(tokyo) shouldBe startOf(2024, 7, 15, tokyo)
        (startOf(2024, 7, 15, UTC) - startOf(2024, 7, 15, tokyo)) shouldBe 9 * 60 * 60 * 1000L
    }

    private fun startOf(year: Int, month: Int, day: Int, zone: ZoneId) =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun comicInfo(year: String?, month: String?, day: String?) = ComicInfo(
        title = null,
        series = null,
        number = null,
        summary = null,
        writer = null,
        penciller = null,
        inker = null,
        colorist = null,
        letterer = null,
        coverArtist = null,
        translator = null,
        genre = null,
        tags = null,
        web = null,
        year = year?.let { ComicInfo.Year(it) },
        month = month?.let { ComicInfo.Month(it) },
        day = day?.let { ComicInfo.Day(it) },
        publishingStatus = null,
        categories = null,
        source = null,
        padding = null,
    )

    private companion object {
        val UTC: ZoneId = ZoneOffset.UTC
    }
}
