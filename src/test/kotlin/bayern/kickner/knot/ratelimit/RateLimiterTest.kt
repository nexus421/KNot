package bayern.kickner.knot.ratelimit

import bayern.kickner.knot.ratelimit.RateLimiter.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimiterTest {

    private var now = 1_000_000L
    private val limiter = RateLimiter(limitPerMinute = 3, clock = { now })

    private fun exhaust(key: String = "key-a") = repeat(3) { assertEquals(Verdict.ALLOWED, limiter.tryAcquire(key), "request ${it + 1} should be allowed") }

    @Test
    fun `allows requests up to the limit and rejects the next ones`() {
        exhaust()

        assertEquals(Verdict.LIMIT_REACHED, limiter.tryAcquire("key-a"))
        assertEquals(Verdict.REJECTED, limiter.tryAcquire("key-a"))
        assertEquals(Verdict.REJECTED, limiter.tryAcquire("key-a"))
    }

    @Test
    fun `counts every key on its own`() {
        exhaust("key-a")
        limiter.tryAcquire("key-a")

        assertEquals(Verdict.ALLOWED, limiter.tryAcquire("key-b"))
    }

    @Test
    fun `starts a fresh count in the next minute`() {
        exhaust()
        limiter.tryAcquire("key-a")

        now += 60_000
        assertEquals(Verdict.ALLOWED, limiter.tryAcquire("key-a"))
    }

    @Test
    fun `rejected requests do not extend the current window`() {
        exhaust()
        now += 59_000
        assertEquals(Verdict.LIMIT_REACHED, limiter.tryAcquire("key-a"))

        now += 1_000
        assertEquals(Verdict.ALLOWED, limiter.tryAcquire("key-a"))
    }

    @Test
    fun `a limit exceeded in consecutive minutes is reported only once`() {
        exhaust()
        assertEquals(Verdict.LIMIT_REACHED, limiter.tryAcquire("key-a"))

        now += 60_000
        exhaust()
        assertEquals(Verdict.REJECTED, limiter.tryAcquire("key-a"))

        now += 60_000
        exhaust()
        assertEquals(Verdict.REJECTED, limiter.tryAcquire("key-a"))
    }

    @Test
    fun `a minute within the limit re-arms the report`() {
        exhaust()
        limiter.tryAcquire("key-a")

        now += 60_000
        assertEquals(Verdict.ALLOWED, limiter.tryAcquire("key-a"))

        now += 60_000
        exhaust()
        assertEquals(Verdict.LIMIT_REACHED, limiter.tryAcquire("key-a"))
    }

    @Test
    fun `a minute without requests re-arms the report`() {
        exhaust()
        limiter.tryAcquire("key-a")

        now += 120_000
        exhaust()
        assertEquals(Verdict.LIMIT_REACHED, limiter.tryAcquire("key-a"))
    }

    @Test
    fun `a short pause does not count as a released limit`() {
        exhaust()
        limiter.tryAcquire("key-a")

        now += 90_000
        exhaust()
        assertEquals(Verdict.REJECTED, limiter.tryAcquire("key-a"))
    }
}
