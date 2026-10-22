package komikku.meebook.bridge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingLogTest {
    private val log = ReadingLog(InMemoryReadingLogStore())

    @Test
    fun `same day add accumulates`() {
        log.add(DwellRecord("2026-10-23", 10))
        log.add(DwellRecord("2026-10-23", 20))
        log.daily() shouldBe listOf(Triple("2026-10-23", 2, 30))
    }

    @Test
    fun `totals sum across days`() {
        log.add(DwellRecord("2026-10-22", 10))
        log.add(DwellRecord("2026-10-23", 20))
        log.add(DwellRecord("2026-10-23", 5))
        log.totals() shouldBe (3L to 35L)
    }

    @Test
    fun `backfill marks day and leaves pages zero`() {
        log.addBackfill("2026-10-20", 600)
        log.daily() shouldBe listOf(Triple("2026-10-20", 0, 600))
        log.backfilledDays() shouldBe setOf("2026-10-20")
    }

    @Test
    fun `add after backfill clears flag`() {
        log.addBackfill("2026-10-20", 600)
        log.add(DwellRecord("2026-10-20", 10))
        log.backfilledDays() shouldBe emptySet()
    }
}
