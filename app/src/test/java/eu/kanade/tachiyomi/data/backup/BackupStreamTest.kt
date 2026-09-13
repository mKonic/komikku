package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test

/**
 * Streaming only pays off if every backup still reads whole: files this writer produces, files written as one
 * message the old way, and files whose entries are not all at the front.
 */
class BackupStreamTest {

    private val parser = ProtoBuf

    private val entries = listOf(
        BackupManga(source = 1, url = "/a", title = "Alpha"),
        BackupManga(source = 2, url = "/b", title = "Beta"),
        BackupManga(source = 1, url = "/c", title = "Gamma"),
    )

    private val rest = Backup(
        backupManga = emptyList(),
        backupCategories = listOf(BackupCategory(name = "Reading", order = 1)),
        backupSources = listOf(BackupSource(name = "One", sourceId = 1), BackupSource(name = "Two", sourceId = 2)),
    )

    private fun Buffer.writeEntries() = apply {
        entries.forEach { BackupStream.writeManga(this, parser.encodeToByteArray(BackupManga.serializer(), it)) }
    }

    private fun Buffer.writeRest() = apply { write(parser.encodeToByteArray(Backup.serializer(), rest)) }

    /** Hands over a few bytes per read, the way a gzip or file stream does, instead of everything at once. */
    private fun trickle(bytes: ByteArray): BufferedSource {
        val data = Buffer().write(bytes)
        return object : Source {
            override fun read(sink: Buffer, byteCount: Long) =
                if (data.exhausted()) -1L else data.read(sink, minOf(byteCount, 3L))

            override fun timeout() = Timeout.NONE

            override fun close() = Unit
        }.buffer()
    }

    private fun titlesIn(bytes: ByteArray) = titlesIn(Buffer().write(bytes))

    private fun titlesIn(source: BufferedSource): List<String> {
        val titles = mutableListOf<String>()
        BackupStream.forEachManga(source) {
            titles += parser.decodeFromByteArray(BackupManga.serializer(), it).title
        }
        return titles
    }

    private fun checkMetadata(bytes: ByteArray) = checkMetadata(Buffer().write(bytes))

    private fun checkMetadata(source: BufferedSource) {
        val (count, backup) = BackupStream.readMetadata(source, parser)
        count shouldBe entries.size
        backup.backupManga.shouldBeEmpty()
        backup.backupCategories.map { it.name } shouldContainExactly listOf("Reading")
        backup.backupSources.map { it.sourceId } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `reads back what the streaming writer wrote`() {
        val bytes = Buffer().writeEntries().writeRest().readByteArray()

        titlesIn(bytes) shouldContainExactly listOf("Alpha", "Beta", "Gamma")
        checkMetadata(bytes)
    }

    @Test
    fun `reads a backup encoded as one message`() {
        val bytes = parser.encodeToByteArray(Backup.serializer(), rest.copyWith(entries))

        titlesIn(bytes) shouldContainExactly listOf("Alpha", "Beta", "Gamma")
        checkMetadata(bytes)
    }

    @Test
    fun `finds entries that come after the other fields`() {
        val bytes = Buffer().writeRest().writeEntries().readByteArray()

        titlesIn(bytes) shouldContainExactly listOf("Alpha", "Beta", "Gamma")
        checkMetadata(bytes)
    }

    @Test
    fun `a backup with nothing but entries still has metadata`() {
        val bytes = Buffer().writeEntries().readByteArray()

        val (count, backup) = BackupStream.readMetadata(Buffer().write(bytes), parser)
        count shouldBe entries.size
        backup.backupCategories.shouldBeEmpty()
    }

    @Test
    fun `reads a stream that hands over a few bytes at a time`() {
        val bytes = Buffer().writeEntries().writeRest().readByteArray()

        titlesIn(trickle(bytes)) shouldContainExactly listOf("Alpha", "Beta", "Gamma")
        checkMetadata(trickle(bytes))
    }

    private fun Backup.copyWith(manga: List<BackupManga>) = Backup(
        backupManga = manga,
        backupCategories = backupCategories,
        backupSources = backupSources,
    )
}
