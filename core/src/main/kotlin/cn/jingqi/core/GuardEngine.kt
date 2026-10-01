package cn.jingqi.core

data class ReturnRequest(val source: String, val target: String)

class GuardEngine {
    companion object {
        /** Auto-return window, counted from the first ad observation rather than app launch. */
        const val RETURN_MS = 6_000L
        const val CONFIRM_RETURN_MS = 3_500L
    }
    var source: String? = null
        private set
    var startedAt: Long = 0
        private set
    var adSeen = false
        private set
    // Apps with long loading screens show the ad late; timing from launch let the window expire before the jump.
    private var adSeenAt = 0L
    var skipAttempted = false
        private set
    var skipConfirmed = false
        private set
    private var adVisible = false
    private var clickAt: Long? = null
    private var clickedAd = false
    private var returned = false
    private var pending: ReturnRequest? = null
    private var requestedAt = 0L

    fun reset() {
        source = null; adSeen = false; skipAttempted = false; skipConfirmed = false; adVisible = false
        clickAt = null; clickedAd = false; returned = false; pending = null
    }

    fun enter(pkg: String, now: Long) {
        if (source == pkg) return
        reset(); source = pkg; startedAt = now
    }

    fun elapsed(now: Long) = now - startedAt
    fun observeAd(value: Boolean, now: Long = startedAt) {
        if (value && !adSeen) { adSeen = true; adSeenAt = maxOf(now, startedAt) }
        adVisible = value
    }
    /** True while an observed ad may still trigger an automatic jump worth undoing. */
    fun inReturnWindow(now: Long) = adSeen && now >= startedAt && now - adSeenAt in 0..RETURN_MS
    fun markSkip() { skipAttempted = true }
    fun confirmSkip() { skipConfirmed = true; adVisible = false }
    fun userClicked(now: Long) {
        // A click on the normal app page must never be reclassified as an ad click later.
        clickedAd = (clickAt == null || clickedAd) && adVisible && inReturnWindow(now)
        clickAt = now
    }
    fun wasClickedAfter(time: Long) = clickAt?.let { it >= time } == true

    fun requestReturn(target: String, now: Long, knownTarget: Boolean, enabled: Boolean,
                      excluded: Set<String>, returnOnAdClick: Boolean = false): ReturnRequest? {
        val origin = source ?: return null
        if (!enabled || target == origin || !knownTarget || !adSeen || skipConfirmed || returned || pending != null) return null
        if (!inReturnWindow(now) || origin in excluded || target in excluded) return null
        if (clickAt != null && !(returnOnAdClick && clickedAd)) return null
        return ReturnRequest(origin, target).also { pending = it; requestedAt = now; returned = true }
    }

    fun confirmReturn(pkg: String, now: Long): ReturnRequest? {
        val request = pending ?: return null
        if (now - requestedAt > CONFIRM_RETURN_MS || now < requestedAt) { pending = null; return null }
        return if (pkg == request.source) request.also { pending = null } else null
    }

    fun hasPending(now: Long): Boolean {
        if (pending != null && now - requestedAt > CONFIRM_RETURN_MS) pending = null
        return pending != null
    }
    fun isPending(request: ReturnRequest, now: Long) = hasPending(now) && pending === request
    fun pendingTarget(now: Long) = if (hasPending(now)) pending?.target else null
    fun returnFailed() { pending = null }
}
