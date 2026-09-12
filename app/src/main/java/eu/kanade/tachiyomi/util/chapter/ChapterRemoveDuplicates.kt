package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter

/**
 * Returns a copy of the list with duplicate chapters removed.
 *
 * Only chapters adjacent in the list count as duplicates. Sources that split a series into seasons
 * reuse chapter numbers across them, and grouping every chapter with the same number collapsed
 * whole seasons into one, which sent the reader jumping back (mihonapp/mihon#3623).
 */
fun List<Chapter>.removeDuplicates(currentChapter: Chapter): List<Chapter> {
    fun Chapter.priority() = when {
        id == currentChapter.id -> 2
        scanlator == currentChapter.scanlator -> 1
        else -> 0
    }

    return fold(mutableListOf()) { acc, chapter ->
        val last = acc.lastOrNull()
        when {
            last == null || last.chapterNumber != chapter.chapterNumber -> acc.add(chapter)
            chapter.priority() > last.priority() -> acc[acc.lastIndex] = chapter
            else -> Unit
        }
        acc
    }
}
