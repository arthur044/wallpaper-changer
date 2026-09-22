package io.github.arthur044.wallpaperchanger.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

// Behavior of Poller._should_call_web_api plus the Retry-After push-forward
// done in Poller._resolve_high_res_art.
class ApiThrottleTest {
    private val time = TestTimeSource()
    private val throttle = ApiThrottle(minInterval = 25.seconds, timeSource = time)

    @Test
    fun `first call is allowed immediately`() {
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `blocks until the interval has elapsed`() {
        throttle.tryAcquire()

        time += 24.seconds
        assertFalse(throttle.tryAcquire())

        time += 1.seconds
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `a refused attempt does not restart the interval`() {
        throttle.tryAcquire()
        time += 10.seconds
        throttle.tryAcquire() // refused

        time += 15.seconds
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `retry-after longer than the interval defers the next call to it`() {
        throttle.deferFor(90.seconds)

        time += 89.seconds
        assertFalse(throttle.tryAcquire())

        time += 1.seconds
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `retry-after shorter than the interval still waits the full interval`() {
        throttle.deferFor(5.seconds)

        time += 24.seconds
        assertFalse(throttle.tryAcquire())

        time += 1.seconds
        assertTrue(throttle.tryAcquire())
    }
}
