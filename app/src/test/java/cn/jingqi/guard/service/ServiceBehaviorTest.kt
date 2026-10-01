package cn.jingqi.guard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cn.jingqi.core.GuardEngine
import cn.jingqi.guard.data.Preferences
import cn.jingqi.guard.data.Store
import cn.jingqi.guard.data.GuardDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Supplies the OS-owned active root; all app event, scanning, action and recovery code runs unchanged. */
@Implements(AccessibilityService::class)
class ActiveRootShadow : ShadowAccessibilityService() {
    var activeRoot: AccessibilityNodeInfo? = null
    @Implementation
    protected fun getRootInActiveWindow(): AccessibilityNodeInfo? = activeRoot?.let { AccessibilityNodeInfo.obtain(it) }
    // The real framework returns caller-owned snapshots. Robolectric's default returns the same pooled objects.
    @Implementation
    override fun getWindows(): List<AccessibilityWindowInfo> = super.getWindows().map { AccessibilityWindowInfo.obtain(it) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi", shadows = [ActiveRootShadow::class])
class ServiceBehaviorTest {
    private lateinit var service: JingQiService
    private lateinit var shadow: ActiveRootShadow
    private lateinit var prefs: Preferences
    private val engine get() = ReflectionHelpers.getField<GuardEngine>(service, "engine")
    private val source = "com.baidu.netdisk"
    private val target = "com.taobao.taobao"
    private fun waitMs(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setup() {
        service = Robolectric.buildService(JingQiService::class.java).create().get()
        shadow = Shadow.extract(service)
        ReflectionHelpers.callInstanceMethod<Any?>(service, "onServiceConnected")
        prefs = Preferences(service).apply { consent = true; enabled = true; returnGuard = true; returnOnAdClick = true }
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = source; name = "$source.MainActivity"; exported = true
                applicationInfo = ApplicationInfo().apply { packageName = source }
            }
        }
        shadowOf(service.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(source), info)
    }
    @After fun cleanup() {
        service.onDestroy()
        // Store writes on a real worker: finish them before Robolectric disposes this application's files.
        val done = CountDownLatch(1)
        Store.io.execute { ReflectionHelpers.getField<GuardDatabase>(Store, "db").close(); done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }

    private fun node(pkg: String, text: String = "", clickable: Boolean = false, bounds: Rect = Rect(0, 0, 360, 640)) =
        AccessibilityNodeInfo.obtain().apply {
            packageName = pkg; this.text = text; isVisibleToUser = true; isEnabled = true; isClickable = clickable
            setBoundsInScreen(bounds)
        }
    private fun foreground(pkg: String, ad: Boolean = false, skip: Boolean = false, ambiguous: Boolean = false, nested: Boolean = false) {
        val root = node(pkg)
        if (ad) {
            shadowOf(root).addChild(node(pkg, "广告", bounds = Rect(10, 550, 60, 575)))
            shadowOf(root).addChild(node(pkg, "5s", bounds = Rect(10, 30, 50, 55)))
        }
        if (skip) {
            shadowOf(root).addChild(node(pkg, "跳过 5s", true, Rect(260, 25, 345, 55)).apply {
                viewIdResourceName = "$pkg:id/splash_skip"
                shadowOf(this).setOnPerformActionListener { _, _ -> true }
            })
        }
        if (ambiguous) shadowOf(root).addChild(node(pkg, "跳过", true, Rect(15, 25, 100, 55)))
        if (nested) {
            val container = node(pkg, clickable = true, bounds = Rect(260, 25, 345, 55))
            shadowOf(container).setOnPerformActionListener { _, _ -> true }
            shadowOf(container).addChild(node(pkg, "跳过", bounds = Rect(270, 30, 335, 50)))
            shadowOf(root).addChild(container)
        }
        shadow.activeRoot = root
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply { setRoot(root); setType(AccessibilityWindowInfo.TYPE_APPLICATION); setActive(true); setLayer(1) }
        shadow.setWindows(listOf(window))
    }
    private fun event(type: Int, pkg: String? = source, sourceNode: AccessibilityNodeInfo? = null) {
        service.onAccessibilityEvent(AccessibilityEvent.obtain(type).apply {
            packageName = pkg; className = "$pkg.MainActivity"; eventTime = SystemClock.uptimeMillis()
            if (sourceNode != null) shadowOf(this).setSourceNode(sourceNode)
        })
    }
    private fun enterAd(skip: Boolean = false) {
        foreground(source, ad = true, skip = skip)
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        waitMs(0)
        assertTrue(engine.adSeen)
    }
    private fun jump(clicked: Boolean = true) {
        if (clicked) event(AccessibilityEvent.TYPE_VIEW_CLICKED)
        foreground(target)
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, target)
    }

    @Test fun ineffectiveClickGetsFreshTouchFallbackAfterTwoHundredMilliseconds() {
        enterAd(skip = true)
        waitMs(199); assertTrue(shadow.gesturesDispatched.isEmpty())
        waitMs(1); assertEquals(1, shadow.gesturesDispatched.size)
        waitMs(1_000); assertEquals(1, shadow.gesturesDispatched.size)
    }
    @Test fun ambiguousOrChangedPageCannotReceiveTouchFallback() {
        enterAd(skip = true)
        foreground(source, ad = true, skip = true, ambiguous = true)
        waitMs(200); assertTrue(shadow.gesturesDispatched.isEmpty())
    }
    @Test fun ownClickOnUnlabelledParentDoesNotCancelFallback() {
        foreground(source, ad = true, nested = true)
        val container = shadow.activeRoot!!.getChild(2)
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); waitMs(0)
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, sourceNode = container)
        waitMs(200)
        assertEquals(1, shadow.gesturesDispatched.size)
    }
    @Test fun manualInteractionCancelsTouchFallback() {
        enterAd(skip = true)
        waitMs(50); event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, null)
        waitMs(200); assertTrue(shadow.gesturesDispatched.isEmpty())
    }
    @Test fun accidentalAdClickReturnsAndRootPollingConfirmsWithoutStateEvent() {
        enterAd(); jump()
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadow.globalActionsPerformed)
        assertTrue(engine.hasPending(SystemClock.elapsedRealtime()))
        foreground(source)
        waitMs(150)
        assertFalse(engine.hasPending(SystemClock.elapsedRealtime()))
        waitMs(700); assertNull(shadowOf(service).nextStartedActivity)
    }
    @Test fun backStuckInTargetRestoresOriginalAppOnce() {
        enterAd(); jump()
        waitMs(700)
        assertEquals(source, shadowOf(service).nextStartedActivity?.component?.packageName)
        waitMs(3_000)
        assertNull(shadowOf(service).nextStartedActivity)
        assertEquals(1, shadow.globalActionsPerformed.size)
        assertFalse(engine.hasPending(SystemClock.elapsedRealtime()))
    }
    @Test fun touchingDestinationCancelsRestore() {
        enterAd(); jump()
        event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, null)
        waitMs(1_000); assertNull(shadowOf(service).nextStartedActivity)
    }
    @Test fun differentForegroundOrDisabledGuardCannotRestoreOriginalApp() {
        enterAd(); jump()
        foreground("com.example.other")
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.other")
        waitMs(1_000); assertNull(shadowOf(service).nextStartedActivity)
        assertEquals("com.example.other", engine.source)
    }
    @Test fun disablingGuardDuringPendingReturnCancelsRestore() {
        enterAd(); jump(); prefs.returnGuard = false
        waitMs(1_000); assertNull(shadowOf(service).nextStartedActivity)
        assertFalse(engine.hasPending(SystemClock.elapsedRealtime()))
    }
    @Test fun optOutOrOrdinaryPageClickIsAllowed() {
        prefs.returnOnAdClick = false
        enterAd(); jump()
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        service.onInterrupt(); prefs.returnOnAdClick = true
        foreground(source); event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); waitMs(0)
        jump()
        assertTrue(shadow.globalActionsPerformed.isEmpty())
    }
    private fun ringForeground(ad: Boolean = true, enabled: Boolean = true, topmost: Boolean = true) {
        foreground(source, ad = ad)
        val root = shadow.activeRoot!!
        if (ad) root.getChild(0).text = "AM广告"
        // The full-screen ad is clickable, while its readable skip glyph is not an ACTION_CLICK target.
        root.isClickable = true
        shadowOf(root).addChild(node(source, "跳 过", bounds = Rect(305, 35, 335, 44)).apply { isEnabled = enabled })
        if (!topmost) {
            val covering = AccessibilityWindowInfo.obtain()
            shadowOf(covering).apply {
                setRoot(node("com.example.overlay")); setType(AccessibilityWindowInfo.TYPE_APPLICATION); setLayer(2)
            }
            val behind = AccessibilityWindowInfo.obtain()
            shadowOf(behind).apply { setRoot(root); setType(AccessibilityWindowInfo.TYPE_APPLICATION); setLayer(1) }
            shadow.setWindows(listOf(covering, behind))
        }
    }
    @Test fun nonclickableRingLabelUsesItsOwnCenterWithoutClickingFullScreenAd() {
        ringForeground()
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); waitMs(0)
        assertEquals(1, shadow.gesturesDispatched.size)
        assertTrue(shadowOf(shadow.activeRoot!!).performedActions.isEmpty())
        val stroke = shadow.gesturesDispatched.single().description().getStroke(0)
        val bounds = android.graphics.RectF(); stroke.path.computeBounds(bounds, true)
        assertEquals(320f, bounds.left, .1f); assertEquals(39f, bounds.top, .1f)
        event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); waitMs(1_000)
        assertEquals(1, shadow.gesturesDispatched.size)
    }
    @Test fun ringGestureIsWithheldWithoutAdMarkerOrWhileDisabledOrCovered() {
        ringForeground(ad = false); event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); waitMs(300)
        assertTrue(shadow.gesturesDispatched.isEmpty())
        service.onInterrupt()
        ringForeground(enabled = false); event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); waitMs(300)
        assertTrue(shadow.gesturesDispatched.isEmpty())
        service.onInterrupt()
        ringForeground(topmost = false); event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); waitMs(300)
        assertTrue(shadow.gesturesDispatched.isEmpty())
    }
    @Test fun contentAnimationDoesNotMasqueradeAsUserInteraction() {
        foreground(source, ad = true); event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); waitMs(0)
        val before = ReflectionHelpers.getField<Long>(service, "interactionRevision")
        repeat(10) { event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); waitMs(80) }
        assertEquals(before, ReflectionHelpers.getField<Long>(service, "interactionRevision"))
        event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, null)
        assertEquals(before + 1, ReflectionHelpers.getField<Long>(service, "interactionRevision"))
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        assertEquals(before + 2, ReflectionHelpers.getField<Long>(service, "interactionRevision"))
    }
}
