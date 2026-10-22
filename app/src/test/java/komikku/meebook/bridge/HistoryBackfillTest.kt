package komikku.meebook.bridge

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HistoryBackfillTest {
    private val zone = ZoneId.of("Europe/Moscow")

    private class RecordingStore : ReadingLogStore {
        val inner = InMemoryReadingLogStore()
        val backfills = mutableListOf<Pair<String, Int>>()
        override fun addDay(day: String, pages: Int, seconds: Int) = inner.addDay(day, pages, seconds)
        override fun addBackfill(day: String, seconds: Int) {
            backfills += day to seconds
            inner.addBackfill(day, seconds)
        }
        override fun all() = inner.all()
    }

    private class FakeFlag(var done: Boolean = false) : HistoryBackfill.Flag {
        override fun isDone() = done
        override fun markDone() {
            done = true
        }
    }

    private val store = RecordingStore()
    private val log = ReadingLog(store)

    private fun ms(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // Two rows on 2026-10-20 (60 s + 90 s); one at 01:00 Moscow on 10-21 (still 10-20 in UTC).
    private val history = listOf(
        ms("2026-10-20T10:00:00") to 60_000L,
        ms("2026-10-20T22:00:00") to 90_000L,
        ms("2026-10-21T01:00:00") to 30_000L,
    )

    @Test
    fun `sums seconds per local day of last read`() {
        HistoryBackfill.run(log, history, zone)
        store.backfills shouldBe listOf("2026-10-20" to 150, "2026-10-21" to 30)
        log.backfilledDays() shouldBe setOf("2026-10-20", "2026-10-21")
    }

    @Test
    fun `skips reset and zero-duration rows`() {
        HistoryBackfill.run(log, listOf(0L to 60_000L, ms("2026-10-20T10:00:00") to 0L), zone)
        store.backfills shouldBe emptyList()
    }

    @Test
    fun `does not import days already tracked live`() {
        log.add(DwellRecord("2026-10-21", 40))
        HistoryBackfill.run(log, history, zone)
        store.backfills shouldBe listOf("2026-10-20" to 150)
    }

    @Test
    fun `runs once and sets the flag`() = runTest {
        val flag = FakeFlag()
        HistoryBackfill.runOnce(flag, log, zone) { history } shouldBe true
        flag.done shouldBe true
        HistoryBackfill.runOnce(flag, log, zone) { history } shouldBe false
        store.backfills shouldBe listOf("2026-10-20" to 150, "2026-10-21" to 30)
    }

    @Test
    fun `does not run when the flag is already set`() = runTest {
        var read = false
        HistoryBackfill.runOnce(FakeFlag(done = true), log, zone) {
            read = true
            history
        } shouldBe false
        read shouldBe false
        store.backfills shouldBe emptyList()
    }

    @Test
    fun `empty history leaves the flag unset for a later restore`() = runTest {
        val flag = FakeFlag()
        HistoryBackfill.runOnce(flag, log, zone) { emptyList() } shouldBe false
        flag.done shouldBe false
    }
}
