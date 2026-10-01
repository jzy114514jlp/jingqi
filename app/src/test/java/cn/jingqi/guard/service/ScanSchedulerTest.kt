package cn.jingqi.guard.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScanSchedulerTest {
    private val looper get() = shadowOf(Looper.getMainLooper())
    private fun waitMs(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    @Test fun finalEventInsideThrottleStillGetsScanned() {
        var visible = false
        var detected = false
        val scheduler = ScanScheduler(Handler(Looper.getMainLooper())) { detected = visible }
        scheduler.start(); looper.idle()
        waitMs(30); visible = true; scheduler.request()
        waitMs(69); assertFalse(detected)
        waitMs(1); assertTrue(detected)
        scheduler.stop()
    }
    @Test fun eventBurstCannotPostponeTheQueuedScan() {
        val times = mutableListOf<Long>()
        val start = SystemClock.uptimeMillis()
        val scheduler = ScanScheduler(Handler(Looper.getMainLooper())) { times += SystemClock.uptimeMillis() - start }
        scheduler.start(); looper.idle()
        repeat(10) { waitMs(10); scheduler.request() }
        assertEquals(listOf(0L, 100L), times)
        scheduler.stop()
    }
    @Test fun staticLateButtonIsPolledAndObservationStopsAfterTenSeconds() {
        var visible = false
        var detected = false
        var scans = 0
        val scheduler = ScanScheduler(Handler(Looper.getMainLooper())) { scans++; if (visible) detected = true }
        scheduler.start(); waitMs(4_251)
        visible = true; waitMs(350); assertTrue(detected)
        waitMs(6_000); val finished = scans
        scheduler.request(); waitMs(2_000); assertEquals(finished, scans)
        scheduler.stop()
    }
    @Test fun stopCancelsQueuedEventsAndNewSessionStartsImmediately() {
        var scans = 0
        val scheduler = ScanScheduler(Handler(Looper.getMainLooper())) { scans++ }
        scheduler.start(); looper.idle(); waitMs(20); scheduler.request(); scheduler.stop()
        waitMs(500); assertEquals(1, scans)
        scheduler.start(); looper.idle(); assertEquals(2, scans)
        scheduler.stop()
    }
    @Test fun completingActionInsideScanStopsFurtherPolling() {
        var scans = 0
        lateinit var scheduler: ScanScheduler
        scheduler = ScanScheduler(Handler(Looper.getMainLooper())) { scans++; scheduler.stop() }
        scheduler.start(); waitMs(2_000); assertEquals(1, scans)
    }
}
