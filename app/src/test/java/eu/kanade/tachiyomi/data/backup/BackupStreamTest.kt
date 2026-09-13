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

    private fun titlesIn(bytes: ByteArray): List<String> {
        val titles = mutableListOf<String>()
        BackupStream.forEachManga(Buffer().write(bytes)) {
            titles += parser.decodeFromByteArray(BackupManga.serializer(), it).title
        }
        return titles
    }

    private fun checkMetadata(bytes: ByteArray) {
        val (count, backup) = BackupStream.readMetadata(Buffer().write(bytes), parser)
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

    private fun Backup.copyWith(manga: List<BackupManga>) = Backup(
        backupManga = manga,
        backupCategories = backupCategories,
        backupSources = backupSources,
    )
}
