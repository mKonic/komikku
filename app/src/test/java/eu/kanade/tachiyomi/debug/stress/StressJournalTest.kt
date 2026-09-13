package eu.kanade.tachiyomi.debug.stress

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The journal is what is left of an eternal run after a crash or a power cut, so what it promises is tested as a
 * reader would find the files: every record readable, none lost to a torn line from an earlier process.
 */
class StressJournalTest {

    @TempDir
    lateinit var dir: File

    private fun readAll(): Pair<List<JsonObject>, Int> {
        var torn = 0
        val records = dir.listFiles { file -> file.name.endsWith(".jsonl") }!!
            .sortedBy { it.name }
            .flatMap { file ->
                file.readLines().mapNotNull { line ->
                    runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: null.also { torn++ }
                }
            }
        return records to torn
    }

    @Test
    fun `every record is on disk after a flush, across rotated segments`() {
        StressJournal(dir, session = 1, segmentBytes = 512).use { journal ->
            repeat(200) { journal.record("step", mapOf("i" to it, "note" to "line\nbreak")) }
            journal.flush()

            val (records, torn) = readAll()
            torn shouldBe 0
            records.map { it["i"]!!.jsonPrimitive.long } shouldContainExactly (0L until 200L).toList()
            dir.listFiles()!!.count { it.name.endsWith(".jsonl") } shouldNotBe 1
        }
    }

    @Test
    fun `writeNow lands immediately, after what was queued`() {
        val journal = StressJournal(dir, session = 1)
        repeat(50) { journal.record("step", mapOf("i" to it)) }
        journal.writeNow("crash", mapOf("error" to IllegalStateException("boom")))

        // No flush and no close: this is what a process killed right after writeNow leaves behind.
        val (records, _) = readAll()
        records.last()["kind"]!!.jsonPrimitive.content shouldBe "crash"
        records.count { it["kind"]!!.jsonPrimitive.content == "step" } shouldBe 50
        journal.close()
    }

    @Test
    fun `a torn line from a dead process never swallows the next one's records`() {
        StressJournal(dir, session = 1).use {
            it.record("step")
            it.flush()
        }
        // The first process died mid-write.
        dir.listFiles()!!.single().appendText("""{"t":1,"session":1,"kind":"ste""")

        StressJournal(dir, session = 2).use { journal ->
            journal.record("resume")
            journal.flush()
        }

        val (records, torn) = readAll()
        torn shouldBe 1
        records.map { it["kind"]!!.jsonPrimitive.content } shouldContainExactly listOf("step", "resume")
    }

    @Test
    fun `the manifest is replaced whole`() {
        val file = File(dir, "run.json")
        val first = StressManifest("run", StressMode.ETERNAL, listOf("screens"), false, 0, 1, "debug", 5_000)
        StressManifest.write(file, first)
        StressManifest.write(file, first.copy(session = 2))

        StressManifest.read(file) shouldBe first.copy(session = 2)
        File(dir, "run.json.tmp").exists() shouldBe false
    }
}
