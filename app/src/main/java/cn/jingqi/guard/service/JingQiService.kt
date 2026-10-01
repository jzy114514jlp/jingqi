package cn.jingqi.guard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cn.jingqi.core.*
import cn.jingqi.guard.data.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.Locale
import kotlin.math.abs

class JingQiService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val engine = GuardEngine()
    private val scans = ScanScheduler(handler) { scan() }
    private lateinit var prefs: Preferences
    private var epoch = 0
    private var sceneRevision = 0L
    private var lastShot = 0L
    private var ocrAttempts = 0
    private var lastOcr = 0L
    private var ownClick: UiNode? = null
    private var ownClickAt = 0L
    private var gestureInFlight = false
    private var noted = false
    private var rejectionNoted = false
    private var refusalNoted = false
    private val reportedFindings = mutableSetOf<String>()
    private var voice: TextToSpeech? = null
    private var voiceReady = false
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var usedRecognizer = false
    private var launchers = emptySet<String>()
    private val targets = setOf("com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
        "com.android.vending", "com.huawei.appmarket", "com.xiaomi.market", "com.heytap.market",
        "com.bbk.appstore", "com.android.chrome", "com.android.browser", "com.huawei.browser",
        "com.microsoft.emmx", "cn.jingqi.demo.target",
        // Common destinations of shake-to-open and auto-jump splash ads.
        "com.tmall.wireless", "com.taobao.idlefish", "com.taobao.litetao", "com.jd.jdlite", "com.achievo.vipshop",
        "com.shizhuang.duapp", "com.xingin.xhs", "com.eg.android.AlipayGphone", "com.sankuai.meituan",
        "com.sankuai.meituan.takeoutnew", "me.ele", "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker", "com.kuaishou.nebula", "com.baidu.searchbox", "com.baidu.searchbox.lite",
        "ctrip.android.view", "com.Qunar", "com.dragon.read", "com.UCMobile", "com.tencent.mtt", "com.quark.browser",
        "com.oppo.market", "com.huawei.hmos.browser", "com.hihonor.appmarket", "com.mi.globalbrowser")
    // System prompts such as "allow opening X" or security-centre checks shown between the ad and the target app.
    private val transit = setOf("android", "com.android.systemui", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.miui.securitycenter", "com.huawei.systemmanager",
        "com.hihonor.systemmanager", "com.coloros.safecenter", "com.oplus.safecenter", "com.iqoo.secure",
        "com.vivo.permissionmanager", "com.android.intentresolver", "com.miui.securityadd")

    override fun onServiceConnected() {
        prefs = Preferences(this)
        launchers = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        voice = TextToSpeech(this) { status ->
            val tts = voice
            if (status == TextToSpeech.SUCCESS && tts != null) {
                val localVoice = tts.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == "zh" }
                if (localVoice != null) { tts.voice = localVoice; voiceReady = true }
            }
        }
    }

    private fun newSession() {
        scans.stop(); epoch++; noted = false; rejectionNoted = false; refusalNoted = false
        ocrAttempts = 0; lastOcr = 0; ownClick = null; gestureInFlight = false; reportedFindings.clear()
    }
    private fun clearSession() { engine.reset(); newSession() }
    private fun active() = ::prefs.isInitialized && prefs.enabled && prefs.consent
    private fun now() = SystemClock.elapsedRealtime()
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val size = Point()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(size)
        return size.x to size.y
    }
    private fun isSystem(pkg: String): Boolean {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        return pkg == "android" || pkg in launchers || pkg == ime ||
            pkg in setOf("com.android.systemui", "com.android.settings", "com.android.permissioncontroller",
                "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
                "com.android.packageinstaller", "com.android.phone", "com.android.dialer") ||
            pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") || pkg.contains("launcher")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!active()) { clearSession(); return }
        val clock = now()
        // Touch-start events may have no package. An interaction during recovery cancels further actions.
        if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            if (gestureInFlight) return
            if (engine.hasPending(clock)) engine.returnFailed()
            engine.userClicked(clock)
            return
        }
        val pkg = event.packageName?.toString() ?: return
        if (pkg == engine.source || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) sceneRevision++
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (pkg == engine.source && isOwnClick(event)) return
            if (engine.hasPending(clock)) engine.returnFailed()
            if (pkg == engine.source) engine.userClicked(clock)
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg == "cn.jingqi.demo" && event.className?.toString() == "cn.jingqi.demo.MainActivity") { clearSession(); return }
            val destination = if (pkg == packageName && event.className?.toString()?.endsWith("DemoLandingActivity") == true)
                "cn.jingqi.demo.target" else pkg
            if (confirmReturn(destination)) return
            if (engine.hasPending(clock)) {
                // Do not recover over a different app the user has just opened.
                if (destination != engine.source && destination != engine.pendingTarget(clock) && pkg !in transit) clearSession()
                else return
            }
            // Keep the splash session across a brief jump prompt; otherwise the target arrives with no source.
            if (pkg in transit && pkg !in launchers && engine.inReturnWindow(clock)) return
            if (isSystem(pkg) || (pkg == packageName && destination == pkg)) { clearSession(); return }
            if (pkg in prefs.excluded) { clearSession(); return }
            val request = engine.requestReturn(destination, clock, destination in targets, prefs.returnGuard, prefs.excluded, prefs.returnOnAdClick)
            if (request != null) {
                recoverReturn(request)
                return
            }
            if (engine.source != null && destination != engine.source && engine.adSeen && !engine.skipConfirmed &&
                engine.elapsed(clock) <= Detector.WINDOW_MS) {
                val reason = when {
                    !prefs.returnGuard -> "防自动跳转开关已关闭"
                    !engine.inReturnWindow(clock) -> "已超出识别广告后的 6 秒窗口"
                    destination !in targets -> "目标不在已知广告跳转应用列表"
                    !prefs.returnOnAdClick -> "检测到点击，误触广告后返回开关未开启，或本次已尝试返回"
                    else -> "点击发生在普通页面、识别广告之前，或本次已经尝试返回"
                }
                Store.log(GuardEvent(kind = "NOTICE", source = engine.source!!, target = destination,
                    detail = "未自动返回：$reason。进入原应用后 ${engine.elapsed(clock)} 毫秒发生跳转。"))
            }
            if (destination == "cn.jingqi.demo.target") { clearSession(); return }
            if (engine.source != pkg) {
                engine.enter(pkg, clock); newSession()
                scans.start()
            }
        }
        if (pkg == engine.source && !engine.skipAttempted) scans.request()
    }

    @Suppress("DEPRECATION")
    private fun isOwnClick(event: AccessibilityEvent): Boolean {
        val clicked = ownClick ?: return false
        if (SystemClock.uptimeMillis() - ownClickAt !in 0..500 || event.eventTime < ownClickAt) return false
        val node = event.source ?: return false
        return try {
            val rect = Rect(); node.getBoundsInScreen(rect)
            val sameBounds = Box(rect.left, rect.top, rect.right, rect.bottom) == clicked.bounds
            // Click events can name a text-less, id-less parent of the skip label. It is still the exact
            // small target we just operated; a click elsewhere must continue to cancel the fallback.
            sameBounds.also {
                if (it) ownClick = null
            }
        } finally { node.recycle() }
    }

    private fun expectOwnClick(node: UiNode) { ownClick = node; ownClickAt = SystemClock.uptimeMillis() }

    private fun confirmReturn(destination: String): Boolean {
        if (!active() || !prefs.returnGuard || destination in prefs.excluded) { engine.returnFailed(); return false }
        if (foregroundPackage() != destination) return false
        val request = engine.confirmReturn(destination, now()) ?: return false
        Store.log(GuardEvent(kind = "RETURN", source = request.source, target = request.target,
            detail = "已识别开屏广告跳转，返回操作后确认原应用重新进入前台。误触保护${if (prefs.returnOnAdClick) "开启" else "关闭"}；此记录不证明广告违法。"))
        if (prefs.elder && voiceReady) voice?.speak("刚才发生了跳转，已经帮你返回", TextToSpeech.QUEUE_FLUSH, null, "returned")
        return true
    }

    @Suppress("DEPRECATION")
    private fun foregroundPackage(): String? = rootInActiveWindow?.let {
        try { it.packageName?.toString() } finally { it.recycle() }
    }

    /** One Back, then one source-app launch only if the same target still owns the foreground. */
    private fun recoverReturn(request: ReturnRequest) {
        scans.stop()
        val ticket = epoch
        fun allowed(): Boolean {
            if (ticket != epoch || !engine.isPending(request, now())) return false
            if (!active() || !prefs.returnGuard || request.source in prefs.excluded || request.target in prefs.excluded) {
                engine.returnFailed(); return false
            }
            return true
        }
        fun foreground() = foregroundPackage()?.let {
            if (it == packageName && request.target == "cn.jingqi.demo.target") request.target else it
        }
        val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
        Store.log(GuardEvent(kind = "NOTICE", source = request.source, target = request.target,
            detail = "识别到开屏广告跳转，进入原应用后 ${engine.elapsed(now())} 毫秒请求返回；系统${if (accepted) "接受" else "拒绝"}返回键操作，正在确认原应用。"))
        // Back may only close an intermediate page inside the shopping app. Do not repeatedly press Back.
        handler.postDelayed({
            if (!allowed()) return@postDelayed
            val current = foreground()
            if (current == request.source) { confirmReturn(current); return@postDelayed }
            if (current != request.target) return@postDelayed
            val launch = packageManager.getLaunchIntentForPackage(request.source)
            if (launch == null) return@postDelayed
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            runCatching { startActivity(launch) }.onSuccess {
                Store.log(GuardEvent(kind = "NOTICE", source = request.source, target = request.target,
                    detail = "返回键未恢复原应用，已尝试打开原应用一次；可能回到首页，仍需确认前台。"))
            }.onFailure {
                Store.log(GuardEvent(kind = "NOTICE", source = request.source, target = request.target,
                    detail = "返回键未恢复原应用，系统也未允许打开原应用；请手动返回。"))
            }
        }, 700)
        // Some apps do not emit a new state event on resume; confirm by foreground roots as well.
        listOf(150L, 450L, 1_100L, 1_800L, 2_600L, 3_400L).forEach { delay ->
            handler.postDelayed({
                if (!allowed()) return@postDelayed
                if (foreground() == request.source) { confirmReturn(request.source); return@postDelayed }
                if (delay == 3_400L) {
                    engine.returnFailed()
                    Store.log(GuardEvent(kind = "NOTICE", source = request.source, target = request.target,
                        detail = "返回操作后 3.4 秒内未确认原应用恢复；未计入返回次数。请手动返回，可反馈此报告。"))
                }
            }, delay)
        }
    }

    /**
     * [clickTarget] is either [node] itself or a captured clickable ancestor; only [node] is recycled.
     * [topmost] means the node's window is the highest application window, so a tap at its bounds reaches it.
     */
    private data class Captured(val info: UiNode, val node: AccessibilityNodeInfo, val topmost: Boolean,
                                val clickTarget: AccessibilityNodeInfo = node)

    /** Splash ads may sit in a popup or overlay window of the same app rather than the active window. */
    @Suppress("DEPRECATION")
    private fun roots(pkg: String): List<Pair<AccessibilityNodeInfo, Boolean>> {
        val found = mutableListOf<Pair<AccessibilityNodeInfo, Boolean>>()
        val all = runCatching { windows }.getOrNull().orEmpty()
        all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }
            .forEachIndexed { index, window ->
                window.root?.let { if (it.packageName?.toString() == pkg) found += it to (index == 0) else it.recycle() }
            }
        all.forEach { it.recycle() }
        if (found.isEmpty()) rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) found += it to false else it.recycle() }
        return found
    }

    @Suppress("DEPRECATION")
    private fun collectAll(pkg: String): List<Captured> {
        val roots = roots(pkg)
        return roots.flatMap { (root, topmost) -> collect(root, topmost) }.also { roots.forEach { it.first.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun collect(root: AccessibilityNodeInfo, topmost: Boolean): List<Captured> {
        val result = mutableListOf<Captured>()
        val (screenWidth, screenHeight) = screenSize()
        val density = resources.displayMetrics.density
        // Each queued node carries its nearest clickable ancestor.
        val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Captured?>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to null)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val (node, clickableAncestor) = queue.removeFirst()
            if (node.isVisibleToUser) {
                val rect = Rect(); node.getBoundsInScreen(rect)
                val box = Box(rect.left, rect.top, rect.right, rect.bottom)
                val label = node.text?.toString()?.takeIf { it.isNotBlank() } ?: node.contentDescription?.toString().orEmpty()
                val clickable = node.isClickable && node.isEnabled
                // Many real skip buttons are plain text inside a small clickable container. Click through the
                // container and judge its bounds, never a full-screen ad layer, so corner and size checks still
                // apply to what is actually tapped.
                val via = clickableAncestor?.takeIf { !clickable && label.isNotBlank() &&
                    Detector.safeBounds(it.info.bounds, screenWidth, screenHeight, density) }
                val captured = if (via != null) Captured(UiNode(label, via.info.id, via.info.bounds, true), node, topmost, via.node)
                    else Captured(UiNode(label, node.viewIdResourceName.orEmpty(), box, clickable), node, topmost)
                result += captured
                val ancestorForChildren = if (clickable) captured else clickableAncestor
                for (i in 0 until minOf(node.childCount, 80)) node.getChild(i)?.let { queue.add(it to ancestorForChildren) }
            } else node.recycle()
        }
        queue.forEach { it.first.recycle() }
        return result
    }

    @Suppress("DEPRECATION")
    private fun scan() {
        if (!active() || engine.source == null || engine.elapsed(now()) !in 0..Detector.WINDOW_MS || engine.hasPending(now())) return
        if (engine.skipAttempted) { scans.stop(); return }
        val pkg = engine.source ?: return
        if (pkg in prefs.excluded || foregroundPackage() != pkg) return
        val captured = collectAll(pkg)
        if (captured.isEmpty()) return
        try {
            val dm = resources.displayMetrics
            val (screenWidth, screenHeight) = screenSize()
            val result = Detector.analyze(pkg, engine.elapsed(now()), captured.map { it.info },
                screenWidth, screenHeight, dm.density, Store.rules)
            engine.observeAd(result.isAd, now())
            val freshFindings = result.findings.filter { it.title !in reportedFindings }
            if (result.isAd && (freshFindings.isNotEmpty() || (!noted && prefs.screenshots))) {
                noted = true
                reportedFindings += freshFindings.map { it.title }
                val event = GuardEvent(kind = "EVIDENCE", source = pkg,
                    detail = freshFindings.joinToString("\n") { "${it.title}：${it.detail}" }.ifEmpty { "识别到疑似开屏页面，留存本地观察记录。" } +
                        "\n识别时点：进入应用后 ${engine.elapsed(now())} 毫秒；${if (result.candidate != null) "找到可操作的唯一跳过控件" else result.rejection ?: "尚未找到可操作的跳过控件"}。")
                Store.log(event)
                if (prefs.screenshots) captureEvidence(event, result.findings)
            }
            // Record once why visible skip text was left alone, so real-device misses can be diagnosed later.
            if (result.rejection != null && !rejectionNoted && !engine.skipAttempted && engine.elapsed(now()) >= 2_000 &&
                (result.isAd || result.countdown != null)) {
                rejectionNoted = true
                Store.log(GuardEvent(kind = "NOTICE", source = pkg, detail = "识别到跳过文字但未操作：${result.rejection}可将此记录反馈给开发者改进识别。"))
            }
            val hit = result.candidate?.let { candidate -> captured.firstOrNull { it.info == candidate } }
            if (hit != null && !engine.skipAttempted) {
                val seconds = result.countdown?.coerceIn(0, 5) ?: 0
                val started = now()
                expectOwnClick(hit.info)
                when {
                    hit.clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK) -> {
                        engine.markSkip(); scans.stop(); verifySkip(pkg, epoch, hit.info, seconds, started)
                    }
                    // Some ad SDKs refuse accessibility clicks outright to defeat auto-skip tools.
                    hit.topmost -> {
                        engine.markSkip(); scans.stop()
                        tapSkip(pkg, hit.info, seconds, "系统拒绝了无障碍点击", started)
                    }
                    !refusalNoted -> {
                        refusalNoted = true
                        Store.log(GuardEvent(kind = "NOTICE", source = pkg,
                            detail = "系统拒绝了对唯一角落跳过控件的无障碍点击；控件不在顶层窗口，为避免误触未改用模拟触摸。可将此记录反馈给开发者。"))
                    }
                }
            } else if (prefs.ocr && !engine.skipAttempted && ocrAttempts < 3 && now() - lastOcr >= 900 &&
                Build.VERSION.SDK_INT >= 30 && engine.elapsed(now()) > 500) {
                ocrAttempts++; lastOcr = now()
                runOcr(pkg, epoch)
            }
        } finally { captured.forEach { it.node.recycle() } }
    }

    /** Bounds of a skip control still shown near [target], and whether a tap there reaches it. */
    @Suppress("DEPRECATION")
    private fun stillShown(pkg: String, target: Box, imageId: String = ""): Pair<Box, Boolean>? {
        val captured = collectAll(pkg)
        return try {
            // Image buttons have no text to look for, so they are recognised again by their view id.
            captured.firstOrNull { (Detector.isSkip(it.info.text) || (imageId.isNotBlank() && it.info.id == imageId)) &&
                abs(it.info.bounds.centerX - target.centerX) <= target.width / 2 &&
                abs(it.info.bounds.centerY - target.centerY) <= target.height / 2 }?.let { it.info.bounds to it.topmost }
        } finally { captured.forEach { it.node.recycle() } }
    }

    /** Some ad SDKs ignore accessibility clicks and react only to touches: verify, then retry once with a tap. */
    private fun verifySkip(pkg: String, ticket: Int, clicked: UiNode, seconds: Int, started: Long) {
        fun log(kind: String, detail: String) = Store.log(GuardEvent(kind = kind, source = pkg,
            estimatedSeconds = if (kind == "SKIP") seconds else 0, detail = detail))
        handler.postDelayed({
            if (!stillCurrent(pkg, ticket) || engine.hasPending(now())) {
                log("NOTICE", "系统接受了跳过点击，但前台已变化或守护已关闭，无法确认结果；未追加触摸。")
                return@postDelayed
            }
            val remaining = stillShown(pkg, clicked.bounds, if (clicked.text.isBlank()) clicked.id else "") ?: run {
                engine.confirmSkip()
                log("SKIP", "系统接受了唯一角落跳过点击，${now() - started} 毫秒后确认控件消失。首次操作在进入应用后 ${started - engine.startedAt} 毫秒；节省时长按倒计时估算。")
                return@postDelayed
            }
            if (engine.wasClickedAfter(started) || !remaining.second) {
                log("NOTICE", "已点击跳过控件但控件仍在；观测到其他点击或控件不在顶层，未追加触摸。")
                return@postDelayed
            }
            tapSkip(pkg, clicked, seconds, "无障碍点击后控件仍在", started)
        }, 200)
    }

    /** Revalidates the clicked control before a single touch fallback. */
    private fun tapSkip(pkg: String, clicked: UiNode, seconds: Int, cause: String, started: Long) {
        val firstActionElapsed = started - engine.startedAt
        fun log(kind: String, detail: String) = Store.log(GuardEvent(kind = kind, source = pkg,
            estimatedSeconds = if (kind == "SKIP") seconds else 0, detail = detail))
        val ticket = epoch
        if (!stillCurrent(pkg, ticket) || engine.hasPending(now())) return
        // Re-run all uniqueness, size and corner checks on fresh nodes before a coordinate gesture.
        val fresh = freshCandidate(pkg, clicked) ?: run {
            log("NOTICE", "${cause}，但重新检查时没有同位置的唯一顶层跳过控件，未改用模拟触摸。"); return
        }
        val bounds = fresh.bounds
        expectOwnClick(fresh)
        gestureInFlight = true
        val path = Path().apply { moveTo(bounds.centerX.toFloat(), bounds.centerY.toFloat()) }
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 55)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (ticket == epoch) gestureInFlight = false
                    log("SKIP", "${cause}，已重新确认按钮并触摸一次。首次操作在进入应用后 $firstActionElapsed 毫秒，触摸完成距首次操作 ${now() - started} 毫秒；关闭结果需人工核实，时长按倒计时估算。")
                    handler.postDelayed({
                        if (ticket == epoch && stillCurrent(pkg, ticket) &&
                            stillShown(pkg, bounds, if (fresh.text.isBlank()) fresh.id else "") == null) engine.confirmSkip()
                    }, 200)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (ticket == epoch) gestureInFlight = false
                    log("NOTICE", "${cause}，模拟触摸也被系统取消；本次未计入跳过次数。")
                }
            }, handler)
        if (!sent) { gestureInFlight = false; log("NOTICE", "${cause}，系统未接受模拟触摸；本次未计入跳过次数。") }
    }

    @Suppress("DEPRECATION")
    private fun freshCandidate(pkg: String, clicked: UiNode): UiNode? {
        val captured = collectAll(pkg)
        return try {
            val (width, height) = screenSize()
            val candidate = Detector.analyze(pkg, engine.elapsed(now()), captured.map { it.info },
                width, height, resources.displayMetrics.density, Store.rules).candidate ?: return null
            candidate.takeIf { it.bounds == clicked.bounds &&
                (clicked.id.isBlank() || it.id == clicked.id) && captured.any { node -> node.info == it && node.topmost } }
        } finally { captured.forEach { it.node.recycle() } }
    }

    private fun stillCurrent(pkg: String, ticket: Int): Boolean {
        if (!active() || ticket != epoch || pkg != engine.source || pkg in prefs.excluded || engine.elapsed(now()) !in 0..Detector.WINDOW_MS) return false
        if (foregroundPackage() != pkg) return false
        val roots = roots(pkg)
        @Suppress("DEPRECATION") roots.forEach { it.first.recycle() }
        return roots.isNotEmpty()
    }

    private fun screenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30 || now() - lastShot < 450) { callback(null); return }
        lastShot = now()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle(); buffer.close(); callback(bitmap)
                }
                override fun onFailure(errorCode: Int) { callback(null) }
            })
        } catch (_: Exception) { callback(null) }
    }

    private fun captureEvidence(event: GuardEvent, findings: List<Finding>) {
        val ticket = epoch
        val revision = sceneRevision
        val generation = Store.generation
        screenshot { bitmap ->
            if (bitmap == null) return@screenshot
            if (!prefs.screenshots || revision != sceneRevision || !stillCurrent(event.source, ticket)) { bitmap.recycle(); return@screenshot }
            Store.io.execute {
                try {
                    if (generation != Store.generation) return@execute
                    val dir = File(filesDir, "evidence").apply { mkdirs() }
                    val original = File(dir, "${event.id}.png")
                    original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val marked = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(marked)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 5f; style = Paint.Style.STROKE }
                    findings.mapNotNull { it.bounds }.forEach { b -> canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint) }
                    File(dir, "${event.id}_marked.png").outputStream().use { marked.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    marked.recycle()
                    Store.attach(event.id, original.absolutePath)
                } finally { bitmap.recycle() }
            }
        }
    }

    private fun runOcr(pkg: String, ticket: Int) {
        val revision = sceneRevision
        screenshot { bitmap ->
            if (bitmap == null) return@screenshot
            if (!prefs.ocr || revision != sceneRevision || !stillCurrent(pkg, ticket)) { bitmap.recycle(); return@screenshot }
            usedRecognizer = true
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    if (!prefs.ocr || revision != sceneRevision || !stillCurrent(pkg, ticket) || engine.skipAttempted) return@addOnSuccessListener
                    val dm = resources.displayMetrics
                    val (screenWidth, screenHeight) = screenSize()
                    if (bitmap.width != screenWidth || bitmap.height != screenHeight) return@addOnSuccessListener
                    val nodes = text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { r ->
                        UiNode(line.text, "ocr", Box(r.left, r.top, r.right, r.bottom), true)
                    } }
                    // OCR requires an explicit advertisement label in the same image in addition to the skip button.
                    // OCR often merges the label with neighbours ("广告 | 了解详情"), so accept it inside a short line only.
                    if (nodes.none { val t = it.text.trim(); (t.contains("广告") && t.length <= 8) || t.equals("Ad", true) ||
                            t.equals("Advertisement", true) }) return@addOnSuccessListener
                    val result = Detector.analyze(pkg, engine.elapsed(now()), nodes, bitmap.width, bitmap.height, dm.density)
                    val b = result.candidate?.bounds ?: return@addOnSuccessListener
                    engine.observeAd(true, now())
                    engine.markSkip(); scans.stop()
                    val anchor = imageButtonAt(pkg, b.centerX, b.centerY)
                    expectOwnClick(result.candidate!!)
                    gestureInFlight = true
                    val path = Path().apply { moveTo(b.centerX.toFloat(), b.centerY.toFloat()) }
                    val sent = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 55)).build(),
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (ticket == epoch) gestureInFlight = false
                                Store.log(GuardEvent(kind = "SKIP", source = pkg, estimatedSeconds = result.countdown?.coerceIn(0, 5) ?: 0,
                                    detail = "本地 OCR 识别唯一角落跳过文字与广告标记，系统完成了点击手势；广告关闭结果需人工核实。"))
                                anchor?.let { learnIfGone(pkg, it) }
                            }
                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (ticket == epoch) gestureInFlight = false
                                Store.log(GuardEvent(kind = "NOTICE", source = pkg,
                                    detail = "OCR 识别到跳过文字，但系统取消了点击手势；本次未计入跳过次数。"))
                            }
                        }, handler)
                    if (!sent) {
                        gestureInFlight = false
                        Store.log(GuardEvent(kind = "NOTICE", source = pkg, detail = "系统未接受 OCR 点击手势；未计入跳过次数。"))
                    }
                }
                .addOnFailureListener { /* No action on uncertain OCR. */ }
                .addOnCompleteListener { bitmap.recycle() }
        }
    }

    /**
     * The single text-less clickable corner control under an OCR hit. Its view id is what lets later launches skip
     * this image button without waiting for a screenshot. Returns null when the point is covered by none or several.
     */
    @Suppress("DEPRECATION")
    private fun imageButtonAt(pkg: String, x: Int, y: Int): UiNode? {
        val captured = collectAll(pkg)
        return try {
            val (screenWidth, screenHeight) = screenSize()
            val density = resources.displayMetrics.density
            captured.map { it.info }.filter { it.actionable && it.text.isBlank() && it.id.isNotBlank() &&
                x in it.bounds.left..it.bounds.right && y in it.bounds.top..it.bounds.bottom &&
                Detector.safeBounds(it.bounds, screenWidth, screenHeight, density) }
                .distinctBy { it.id to it.bounds }.singleOrNull()
        } finally { captured.forEach { it.node.recycle() } }
    }

    /** Remembers [button] only once the tap demonstrably removed it, so a wrong guess is never learned. */
    private fun learnIfGone(pkg: String, button: UiNode) {
        val ticket = epoch
        handler.postDelayed({
            // Leaving the app or a new launch makes the outcome unknowable, so nothing is learned then.
            if (!active() || pkg in prefs.excluded || ticket != epoch || pkg != engine.source) return@postDelayed
            if (!stillCurrent(pkg, ticket) || stillShown(pkg, button.bounds, button.id) != null) return@postDelayed
            engine.confirmSkip()
            Store.learn(SkipRule(pkg, button.id, ""))
            Store.log(GuardEvent(kind = "NOTICE", source = pkg,
                detail = "已记住此应用的图片跳过按钮（${button.id.substringAfter(":id/")}），下次无需 OCR 即可识别。可在设置中清除已学习规则。"))
        }, 800)
    }

    override fun onInterrupt() { clearSession(); voice?.stop() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); clearSession(); voice?.shutdown()
        if (usedRecognizer) recognizer.close()
        super.onDestroy()
    }
}
