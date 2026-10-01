package cn.jingqi.guard.service

import android.os.Handler
import android.os.SystemClock
import cn.jingqi.core.Detector

/** A throttled event scan always has a trailing run; a burst never postpones an already queued scan. */
internal class ScanScheduler(private val handler: Handler, private val scan: () -> Unit) {
    private var started = 0L
    private var lastScan: Long? = null
    private var due: Long? = null
    private var running = false
    private var generation = 0
    private val task = Runnable { runScan() }

    fun start() {
        stop()
        running = true
        started = SystemClock.uptimeMillis()
        lastScan = null
        request()
    }

    fun stop() {
        running = false
        generation++
        handler.removeCallbacks(task)
        due = null
    }

    fun request() {
        val now = SystemClock.uptimeMillis()
        schedule(maxOf(now, lastScan?.plus(100) ?: now))
    }

    private fun schedule(time: Long) {
        if (!running || time - started > Detector.WINDOW_MS || due?.let { it <= time } == true) return
        handler.removeCallbacks(task)
        due = time
        handler.postAtTime(task, time)
    }

    private fun runScan() {
        due = null
        val now = SystemClock.uptimeMillis()
        if (!running || now - started > Detector.WINDOW_MS) { stop(); return }
        val ticket = generation
        lastScan = now
        scan()
        if (ticket != generation) return
        // Cover static and late-rendered splash buttons, even when the SDK sends no new content event.
        val interval = if (now - started < 2_000) 150L else 350L
        schedule(SystemClock.uptimeMillis() + interval)
    }
}
