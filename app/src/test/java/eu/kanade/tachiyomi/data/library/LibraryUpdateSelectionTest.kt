package eu.kanade.tachiyomi.data.library

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LibraryUpdateSelectionTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a selection far past what work input data holds reads back whole`() {
        val ids = (1L..5_000L).map { it * 7_919 }

        val name = LibraryUpdateSelection.write(dir, ids)

        LibraryUpdateSelection.read(dir, name) shouldBe ids.toSet()
        LibraryUpdateSelection.nameIn(setOf("LibraryUpdate", LibraryUpdateSelection.tag(name))) shouldBe name
    }

    @Test
    fun `a lost selection updates nothing rather than the whole library`() {
        LibraryUpdateSelection.read(dir, "gone").shouldBeEmpty()
    }

    @Test
    fun `pruning keeps only the selections of queued or running updates`() {
        val pending = LibraryUpdateSelection.write(dir, listOf(1L))
        LibraryUpdateSelection.write(dir, listOf(2L))

        LibraryUpdateSelection.prune(dir, listOf("LibraryUpdate", LibraryUpdateSelection.tag(pending)))

        dir.list()!!.toList() shouldContainExactly listOf(pending)
    }
}
