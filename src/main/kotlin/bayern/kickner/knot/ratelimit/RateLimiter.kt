package bayern.kickner.knot.ratelimit

private const val WINDOW_MILLIS = 60_000L

/**
 * In-memory fixed-window rate limiter: at most [limitPerMinute] acquisitions per key within a minute.
 * The window of a key starts with its first request and is replaced by a fresh one once it has expired.
 * State lives only in memory — a restart starts over, which is fine for its purpose (damping a leaked key).
 *
 * Besides allowing or rejecting, the limiter tells when a key *starts* exceeding its limit ([Verdict.LIMIT_REACHED]),
 * so that a single notification can be sent per episode: a key that keeps exceeding the limit minute after minute
 * is reported once; the report is re-armed after a full minute within the limit or without any requests.
 *
 * @param clock Current time in epoch milliseconds; injectable for tests.
 */
class RateLimiter(private val limitPerMinute: Int, private val clock: () -> Long = System::currentTimeMillis) {

    enum class Verdict {
        /** Within the limit. */
        ALLOWED,

        /** Over the limit, and the first rejection since the key was last within it — worth a notification. */
        LIMIT_REACHED,

        /** Over the limit; the episode was already reported. */
        REJECTED
    }

    private class Window(val startedAt: Long) {
        var count = 0
    }

    private class State(var window: Window) {
        /** True once LIMIT_REACHED was reported and no calm minute has passed since. */
        var reported = false
    }

    private val states = HashMap<String, State>()

    /**
     * Counts a request for [key] and judges it. Rejected requests are counted too, but do not extend the window.
     */
    @Synchronized
    fun tryAcquire(key: String): Verdict {
        val now = clock()
        val state = states.getOrPut(key) { State(Window(now)) }

        val expired = now - state.window.startedAt >= WINDOW_MILLIS
        if (expired) {
            // A minute within the limit, or one without any request at all, ends the reported episode
            val calm = state.window.count <= limitPerMinute || now - state.window.startedAt >= 2 * WINDOW_MILLIS
            if (calm) state.reported = false
            state.window = Window(now)
        }

        state.window.count++
        return when {
            state.window.count <= limitPerMinute -> Verdict.ALLOWED
            state.reported -> Verdict.REJECTED
            else -> {
                state.reported = true
                Verdict.LIMIT_REACHED
            }
        }
    }
}
