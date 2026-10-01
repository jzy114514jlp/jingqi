package cn.jingqi.core

import org.junit.Assert.*
import org.junit.Test

class GuardEngineTest {
    private fun engine() = GuardEngine().apply { enter("reader", 100); observeAd(true) }
    private fun request(e: GuardEngine, now: Long = 1_000, excluded: Set<String> = emptySet()) =
        e.requestReturn("shop", now, true, true, excluded)
    @Test fun confirmedAdCanReturnOnlyOnceAndRequiresConfirmation() {
        val e = engine()
        assertEquals(ReturnRequest("reader", "shop"), request(e))
        assertNull(request(e))
        assertNull(e.confirmReturn("shop", 1_100))
        assertNotNull(e.confirmReturn("reader", 1_200))
        assertNull(request(e))
    }
    @Test fun normalAppSwitchNeverTriggers() {
        assertNull(request(GuardEngine().apply { enter("reader", 100) }))
    }
    @Test fun windowExpiryAndClockRegressionAreSafe() {
        assertNull(request(engine(), 6_101))
        assertNull(request(engine(), 90))
    }
    @Test fun clickAndWhitelistProtectIntentionalNavigation() {
        assertNull(request(engine().apply { userClicked(500) }))
        assertNull(request(engine(), excluded = setOf("reader")))
        assertNull(request(engine(), excluded = setOf("shop")))
    }
    @Test fun unknownTargetDisabledGuardAndConfirmedSkipNeverReturn() {
        assertNull(engine().requestReturn("other", 500, false, true, emptySet()))
        assertNull(engine().requestReturn("shop", 500, true, false, emptySet()))
        assertNull(request(engine().apply { markSkip(); confirmSkip() }))
    }
    @Test fun launcherResetClearsSource() {
        val e = engine(); e.reset(); assertNull(request(e))
    }
    @Test fun timeoutDoesNotCountAsSuccess() {
        val e = engine(); request(e); assertNull(e.confirmReturn("reader", 4_501))
        assertFalse(e.hasPending(4_501))
    }
    @Test fun lateAdStartsItsOwnReturnWindow() {
        val e = GuardEngine().apply { enter("reader", 100); observeAd(true, 5_000) }
        assertNotNull(request(e, 10_500))
        val late = GuardEngine().apply { enter("reader", 100); observeAd(true, 5_000) }
        assertNull(request(late, 11_001))
    }
    @Test fun laterAdObservationsDoNotExtendWindow() {
        val e = GuardEngine().apply { enter("reader", 100); observeAd(true, 1_000); observeAd(true, 5_000) }
        assertNull(request(e, 7_001))
        assertFalse(e.inReturnWindow(7_001))
    }
    @Test fun samePackageDoesNotExtendTimeWindow() {
        val e = engine(); e.enter("reader", 5_000); assertEquals(100, e.startedAt)
        assertNull(request(e, 6_101))
    }
    @Test fun attemptedButIneffectiveSkipStillAllowsReturn() {
        assertNotNull(request(engine().apply { markSkip() }))
    }
    @Test fun optedInAdClickReturnsButOptOutPreservesIntentionalClick() {
        val e = engine().apply { userClicked(400) }
        assertNotNull(e.requestReturn("shop", 600, true, true, emptySet(), returnOnAdClick = true))
        assertNull(request(engine().apply { userClicked(400) }))
    }
    @Test fun ordinaryPageClickIsNeverTreatedAsAnAdClick() {
        val e = engine().apply { observeAd(false, 300); userClicked(400) }
        assertNull(e.requestReturn("shop", 600, true, true, emptySet(), true))
        val beforeAd = GuardEngine().apply { enter("reader", 100); userClicked(200); observeAd(true, 300); userClicked(400) }
        assertNull(beforeAd.requestReturn("shop", 600, true, true, emptySet(), true))
    }
    @Test fun adClickCannotOverrideWhitelistDisabledGuardUnknownTargetOrExpiry() {
        fun clicked() = engine().apply { userClicked(400) }
        assertNull(clicked().requestReturn("shop", 600, true, true, setOf("shop"), true))
        assertNull(clicked().requestReturn("shop", 600, true, true, setOf("reader"), true))
        assertNull(clicked().requestReturn("shop", 600, true, false, emptySet(), true))
        assertNull(clicked().requestReturn("shop", 600, false, true, emptySet(), true))
        assertNull(clicked().requestReturn("shop", 6_101, true, true, emptySet(), true))
    }
    @Test fun oldPendingCallbackCannotChangeANewRequest() {
        val e = engine(); val old = request(e)!!
        e.reset(); e.enter("reader", 2_000); e.observeAd(true)
        val fresh = request(e, 2_500)!!
        assertFalse(e.isPending(old, 2_600)); assertTrue(e.isPending(fresh, 2_600))
        e.returnFailed(); assertFalse(e.isPending(fresh, 2_700))
    }
}
