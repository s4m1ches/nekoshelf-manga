package komikku.meebook.bridge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class BridgeOpenTargetTest {
    private fun ch(id: Long, read: Boolean = false, page: Long = 0, modified: Long = 0) =
        Chapter.create().copy(id = id, mangaId = 5, read = read, lastPageRead = page, lastModifiedAt = modified)

    @Test
    fun `in-progress chapter opens at its page`() {
        val chapters = listOf(ch(1, read = true), ch(2, page = 7), ch(3))
        decideOpenTarget(true, chapters, chapters[2], 5) shouldBe OpenTarget.Reader(5, 2, 7)
    }

    @Test
    fun `most recently read in-progress chapter wins by history`() {
        val chapters = listOf(ch(1, page = 3), ch(2, page = 9))
        decideOpenTarget(true, chapters, null, 5, mapOf(1L to 200L, 2L to 100L)) shouldBe OpenTarget.Reader(5, 1, 3)
    }

    @Test
    fun `without history the latest modified in-progress chapter wins`() {
        val chapters = listOf(ch(1, page = 3, modified = 10), ch(2, page = 9, modified = 20))
        decideOpenTarget(true, chapters, null, 5) shouldBe OpenTarget.Reader(5, 2, 9)
    }

    @Test
    fun `huge page is not an int`() {
        val chapters = listOf(ch(1, page = Long.MAX_VALUE))
        decideOpenTarget(true, chapters, null, 5) shouldBe OpenTarget.Reader(5, 1, null)
    }

    @Test
    fun `no progress opens next chapter from the start`() {
        val chapters = listOf(ch(1, read = true), ch(2))
        decideOpenTarget(true, chapters, chapters[1], 5) shouldBe OpenTarget.Reader(5, 2, null)
    }

    @Test
    fun `all read opens details`() {
        val chapters = listOf(ch(1, read = true))
        decideOpenTarget(true, chapters, null, 5) shouldBe OpenTarget.Details(5)
    }

    @Test
    fun `missing manga is nothing`() {
        decideOpenTarget(false, emptyList(), null, 5) shouldBe OpenTarget.Nothing
    }

    @Test
    fun `zero chapters opens details`() {
        decideOpenTarget(true, emptyList(), null, 5) shouldBe OpenTarget.Details(5)
    }
}
