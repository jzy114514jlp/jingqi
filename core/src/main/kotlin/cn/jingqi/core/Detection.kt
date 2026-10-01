package cn.jingqi.core

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

data class UiNode(val text: String, val id: String, val bounds: Box, val actionable: Boolean, val enabled: Boolean = true)
data class SkipRule(val packageName: String, val viewId: String, val label: String, val enabled: Boolean = true)
data class Finding(val title: String, val detail: String, val bounds: Box? = null)
/** [rejection] explains why visible skip text was not clicked, so real-device failures can be diagnosed. */
data class Analysis(val isAd: Boolean, val candidate: UiNode?, val countdown: Int?, val findings: List<Finding>,
                    val rejection: String? = null, val touchCandidate: UiNode? = null)

/** Pure decisions: no Android calls or wall-clock dependency. */
object Detector {
    /** Splash ads often start after the app's own logo screen, so observation lasts longer than the ad itself. */
    const val WINDOW_MS = 10_000L
    // Separators such as "跳过 | 3" and trailing arrows such as "跳过 >" are common SDK styles.
    private val skip = Regex("^(?:跳过(?:广告)?|skip(?:\\s+ad)?)(?:\\s*[|丨｜·]?\\s*[（(]?\\s*\\d{1,2}\\s*[sS秒]?\\s*[）)]?)?\\s*[>》›]*$", RegexOption.IGNORE_CASE)
    private val reverseSkip = Regex("^\\d{1,2}\\s*[sS秒]?\\s*[|丨｜·]?\\s*(?:跳过(?:广告)?|skip)\\s*[>》›]*$", RegexOption.IGNORE_CASE)
    private val countdown = Regex("(?:^|\\s|[（(])([0-9]{1,2})\\s*(?:[sS秒])(?:$|\\s|[）)]|跳过)")
    private val chineseGap = Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])")
    private val whitespace = Regex("\\s+")
    private val sdkAdLabel = Regex("^(?:[A-Za-z]{1,4})?广告$")
    private val joinedAdLabel = Regex("^广告[|丨｜·](?:演示|了解详情)$")
    fun isSkip(text: String) = text.trim().replace(chineseGap, "").let { skip.matches(it) || reverseSkip.matches(it) }
    /** Includes SDK labels such as AM广告, but not ordinary text such as 广告设置 or 关闭广告. */
    fun isAdLabel(text: String): Boolean {
        val value = text.trim().replace(whitespace, "")
        return value.equals("Ad", true) || value.equals("Advertisement", true) ||
            sdkAdLabel.matches(value) || joinedAdLabel.matches(value)
    }
    /** Image skip buttons expose no text, but SDK resource names such as `tt_splash_skip_btn` still identify them. */
    fun isSkipId(id: String) = id.substringAfter(":id/").lowercase().let {
        (it.contains("skip") || it.contains("tiaoguo")) && !it.contains("noskip") && !it.contains("unskip")
    }

    fun safeBounds(b: Box, width: Int, height: Int, density: Float): Boolean {
        if (width <= 0 || height <= 0 || density <= 0) return false
        if (b.left < 0 || b.top < 0 || b.right > width || b.bottom > height) return false
        if (b.width < 12 * density || b.height < 10 * density) return false
        if (b.width > minOf(width * .42f, 180 * density) || b.height > minOf(height * .12f, 80 * density)) return false
        return (b.centerX < width * .3 || b.centerX > width * .7) &&
            (b.centerY < height * .25 || b.centerY > height * .75)
    }

    /** Glyph boxes are smaller than the surrounding circular hit area. Only allow this in the upper right. */
    fun safeTextAnchor(b: Box, width: Int, height: Int, density: Float): Boolean {
        if (width <= 0 || height <= 0 || density <= 0) return false
        if (b.left < 0 || b.top < 0 || b.right > width || b.bottom > height) return false
        if (b.width < 12 * density || b.height < 8 * density) return false
        if (b.width > minOf(width * .25f, 120 * density) || b.height > minOf(height * .08f, 48 * density)) return false
        return b.centerX > width * .72 && b.centerY < height * .2
    }

    fun analyze(packageName: String, elapsed: Long, nodes: List<UiNode>, width: Int, height: Int,
                density: Float, rules: List<SkipRule> = emptyList()): Analysis {
        if (elapsed !in 0..WINDOW_MS) return Analysis(false, null, null, emptyList())
        val distinct = nodes.filter { it.text.isNotBlank() }.distinctBy { Pair(it.text.trim(), it.bounds) }
        val explicitAd = distinct.any { isAdLabel(it.text) }
        val skipNodes = distinct.filter { isSkip(it.text) }
        val seconds = distinct.mapNotNull { countdown.find(it.text.trim())?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
        // View ids that already produced a verified skip in this app (built-in or learned on this device).
        val learned = rules.filter { it.enabled && it.packageName == packageName && it.viewId.isNotBlank() }
            .map { it.viewId }.toSet()
        // Text-less clickable corner buttons whose id says "skip" or was learned. OCR nodes carry no real id.
        val imageSkips = nodes.filter { it.text.isBlank() && it.enabled && it.actionable && it.id.isNotBlank() && it.id != "ocr" &&
            (isSkipId(it.id) || it.id in learned) && safeBounds(it.bounds, width, height, density) }
            .distinctBy { Pair(it.id, it.bounds) }
        // Ambiguous labels stay ambiguous even if only one is clickable or sits on a learned id.
        val single = skipNodes.singleOrNull()
        val textCandidate = single?.takeIf { it.enabled && it.actionable && safeBounds(it.bounds, width, height, density) }
        // Image buttons are only considered when no skip text is visible, and must be unique.
        val candidate = textCandidate ?: if (skipNodes.isEmpty()) imageSkips.singleOrNull() else null
        // A custom-drawn circular skip may expose text without any ACTION_CLICK. Never click its full-screen parent.
        val touchCandidate = single?.takeIf { candidate == null && it.enabled && explicitAd &&
            safeTextAnchor(it.bounds, width, height, density) }
        val isAd = skipNodes.any { safeBounds(it.bounds, width, height, density) } || (explicitAd && seconds != null) ||
            imageSkips.isNotEmpty() || touchCandidate != null
        val findings = mutableListOf<Finding>()
        if (isAd && seconds != null && seconds > 5) findings += Finding("倒计时偏长", "界面显示 ${seconds} 秒；仅为页面观察，需人工核实。")
        if (isAd && single != null && (single.bounds.width / density < 32 || single.bounds.height / density < 32))
            findings += Finding("跳过控件偏小", "可访问节点小于 32dp；dp 不是毫米，不能据此认定违规。", single.bounds)
        if (isAd && explicitAd && elapsed >= 4_000 && skipNodes.isEmpty() && imageSkips.isEmpty())
            findings += Finding("未发现可读的跳过控件", "观察 4 秒后仍未在无障碍节点发现跳过文字；可能是图片按钮，需人工核实。")
        val rejection = when {
            candidate != null || touchCandidate != null -> null
            skipNodes.isEmpty() -> if (imageSkips.size > 1) "发现 ${imageSkips.size} 个疑似图片跳过按钮，候选不唯一，未点击。" else null
            single == null -> "发现 ${skipNodes.size} 个跳过文字，候选不唯一，未点击。"
            !single.enabled -> "跳过控件当前禁用，未点击；等待应用自行启用。"
            !safeBounds(single.bounds, width, height, density) -> single.bounds.let {
                "跳过控件不在屏幕角落或尺寸超出范围（${it.width}×${it.height} 像素，中心 ${it.centerX},${it.centerY}，屏幕 ${width}×${height}），未点击。" }
            else -> "跳过文字不可点击，也没有位于角落的小尺寸可点击外层容器，未点击。"
        }
        return Analysis(isAd, candidate, seconds, findings, rejection, touchCandidate)
    }

    /** OCR is never allowed to act on skip text alone, including when its glyph box is very small. */
    fun analyzeOcr(elapsed: Long, nodes: List<UiNode>, width: Int, height: Int, density: Float): Analysis {
        if (elapsed !in 0..WINDOW_MS || nodes.none { isAdLabel(it.text) }) return Analysis(false, null, null, emptyList())
        val analysis = analyze("", elapsed, nodes, width, height, density)
        val target = analysis.candidate ?: analysis.touchCandidate
        return analysis.copy(candidate = target?.copy(actionable = true), touchCandidate = null)
    }

    /** Two separately captured OCR frames must agree on the target, allowing only small recognition jitter. */
    fun confirmOcrTarget(first: UiNode, fresh: Analysis, density: Float): UiNode? {
        val next = fresh.candidate ?: return null
        if (!fresh.isAd || !isSkip(first.text) || !isSkip(next.text) || density <= 0) return null
        val a = first.bounds; val b = next.bounds
        val shift = 6 * density
        if (kotlin.math.abs(a.centerX - b.centerX) > shift || kotlin.math.abs(a.centerY - b.centerY) > shift) return null
        val intersection = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0).toLong() *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val union = a.width.toLong() * a.height + b.width.toLong() * b.height - intersection
        return next.takeIf { union > 0 && intersection.toDouble() / union >= .6 }
    }
}
