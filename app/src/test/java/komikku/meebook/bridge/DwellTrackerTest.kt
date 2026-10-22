package komikku.meebook.bridge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DwellTrackerTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private var now = 0L
    private val tracker = DwellTracker({ now }, { zone })

    private fun at(seconds: Long) {
        now = seconds * 1000
    }

    @Test
    fun `under five seconds is dropped`() {
        at(0)
        tracker.onPageShown("1:0")
        at(4)
        tracker.onPageShown("1:1") shouldBe null
    }

    @Test
    fun `thirty seconds recorded`() {
        at(0)
        tracker.onPageShown("1:0")
        at(4)
        tracker.onPageShown("1:1")
        at(34)
        tracker.onPageShown("1:2")!!.seconds shouldBe 30
    }

    @Test
    fun `capped at 120`() {
        at(0)
        tracker.onPageShown("1:0")
        at(300)
        tracker.onPause()!!.seconds shouldBe 120
    }

    @Test
    fun `second onPause returns null`() {
        at(0)
        tracker.onPageShown("1:0")
        at(60)
        tracker.onPause()!!.seconds shouldBe 60
        at(100)
        tracker.onPause() shouldBe null
    }

    @Test
    fun `across midnight counts to leave day`() {
        val start = LocalDateTime.of(2026, 10, 22, 23, 59, 30).atZone(zone).toInstant().toEpochMilli()
        now = start
        tracker.onPageShown("1:0")
        now = start + 50_000
        tracker.onPause() shouldBe DwellRecord("2026-10-23", 50)
    }

    @Test
    fun `pause then resume then leave after 30s`() {
        at(0)
        tracker.onPageShown("1:0")
        at(10)
        tracker.onPause()
        at(100)
        tracker.onResume("1:0")
        at(130)
        tracker.onPause()!!.seconds shouldBe 30
    }

    @Test
    fun `resume without pause does not reset`() {
        at(0)
        tracker.onPageShown("1:0")
        at(10)
        tracker.onResume("1:0")
        at(40)
        tracker.onPause()!!.seconds shouldBe 40
    }

    @Test
    fun `resume then immediate pause is dropped`() {
        at(0)
        tracker.onPageShown("1:0")
        at(10)
        tracker.onPause()
        at(100)
        tracker.onResume("1:0")
        at(102)
        tracker.onPause() shouldBe null
    }

    @Test
    fun `incognito drops the open page and opens nothing`() {
        at(0)
        tracker.onPageShown("1:0")
        at(30)
        tracker.onPageShown("1:1", incognito = true) shouldBe null
        at(60)
        tracker.onPause() shouldBe null
    }

    @Test
    fun `incognito resume does not reopen tracking`() {
        tracker.onResume("1:0", incognito = true)
        at(30)
        tracker.onPause() shouldBe null
    }

    @Test
    fun `non-incognito page shown behaves as before`() {
        at(0)
        tracker.onPageShown("1:0", incognito = false)
        at(30)
        tracker.onPageShown("1:1", incognito = false) shouldBe DwellRecord("1970-01-01", 30)
    }
}
