package cn.jingqi.core

import org.junit.Assert.*
import org.junit.Test

class DetectorTest {
    private val topRight = Box(800, 100, 1000, 180)
    private fun node(text: String = "跳过 5s", b: Box = topRight, click: Boolean = true) = UiNode(text, "skip", b, click)
    private fun analyze(vararg nodes: UiNode, elapsed: Long = 500) = Detector.analyze("demo", elapsed, nodes.toList(), 1080, 2400, 3f)
    @Test fun anchoredTextPreventsArticleClicks() {
        assertTrue(Detector.isSkip("跳过 5s")); assertTrue(Detector.isSkip("Skip Ad"))
        assertFalse(Detector.isSkip("点击跳过这段教程")); assertFalse(Detector.isSkip("关闭"))
    }
    @Test fun commonSdkSkipStylesAreRecognized() {
        listOf("跳过 | 3", "跳过丨5s", "3 | 跳过", "5s 跳过广告", "跳过 >", "跳过广告 3s", "Skip >").forEach {
            assertTrue(it, Detector.isSkip(it))
        }
        listOf("跳过教程 >", "| 跳过", "跳过 123", "不跳过").forEach { assertFalse(it, Detector.isSkip(it)) }
    }
    @Test fun rejectionExplainsWhySkipTextWasNotClicked() {
        assertNull(analyze(node()).rejection)
        assertNull(analyze(node("广告")).rejection)
        assertTrue(analyze(node(), node(b = Box(20, 100, 220, 180))).rejection!!.contains("不唯一"))
        assertTrue(analyze(node(b = Box(400, 1000, 650, 1120))).rejection!!.contains("角落"))
        assertTrue(analyze(node(click = false)).rejection!!.contains("不可点击"))
    }
    @Test fun onlySmallCornerButtonIsSelected() {
        assertNotNull(analyze(node()).candidate)
        assertNull(analyze(node(b = Box(400, 1000, 650, 1120))).candidate)
        assertNull(analyze(node(b = Box(0, 0, 1080, 180))).candidate)
    }
    @Test fun ambiguousAndUnclickableNodesAreRejected() {
        assertNull(analyze(node(), node(b = Box(20, 100, 220, 180))).candidate)
        assertNull(analyze(node(click = false)).candidate)
    }
    @Test fun duplicateLabelAndDescriptionAreDeduplicated() {
        assertNotNull(analyze(node(), node()).candidate)
    }
    @Test fun normalTimerIsNotAdAndExpiredWindowsAreIgnored() {
        assertFalse(analyze(node("5s")).isAd)
        assertTrue(analyze(node(), elapsed = 8_000).isAd)
        assertFalse(analyze(node(), elapsed = Detector.WINDOW_MS + 1).isAd)
    }
    @Test fun observationsDoNotClaimLegalViolations() {
        val result = analyze(node("广告"), node("8s", Box(20, 200, 220, 280)), elapsed = 4_500)
        assertTrue(result.isAd); assertEquals(2, result.findings.size)
    }
    private fun image(id: String, b: Box = topRight, click: Boolean = true) = UiNode("", id, b, click)
    @Test fun skipLikeResourceNamesIdentifyImageButtons() {
        listOf("com.ad:id/tt_splash_skip_btn", "x:id/ksad_skip_view", "y:id/btnSkip", "z:id/iv_tiaoguo").forEach {
            assertTrue(it, Detector.isSkipId(it))
        }
        listOf("a:id/close_btn", "a:id/noskip_tip", "a:id/jump_detail", "").forEach { assertFalse(it, Detector.isSkipId(it)) }
    }
    @Test fun uniqueCornerImageSkipButtonIsSelected() {
        val result = analyze(image("app:id/splash_skip"))
        assertTrue(result.isAd); assertEquals("app:id/splash_skip", result.candidate?.id)
    }
    @Test fun imageButtonsKeepTheSameSafetyChecks() {
        assertNull(analyze(image("app:id/splash_skip", Box(400, 1000, 650, 1120))).candidate)
        assertNull(analyze(image("app:id/splash_skip", click = false)).candidate)
        assertNull(analyze(image("ocr")).candidate)
        val two = analyze(image("app:id/skip_a"), image("app:id/skip_b", Box(20, 100, 220, 180)))
        assertNull(two.candidate); assertTrue(two.rejection!!.contains("图片跳过按钮"))
    }
    @Test fun visibleSkipTextTakesPriorityOverImageButtons() {
        val text = node()
        assertEquals(text, analyze(text, image("app:id/skip_icon", Box(20, 100, 220, 180))).candidate)
        // Two text candidates stay ambiguous; an image button must not break the tie.
        assertNull(analyze(text, node(b = Box(20, 100, 220, 180)), image("app:id/skip_icon", Box(20, 2200, 220, 2280))).candidate)
    }
    @Test fun learnedIdOnlyAppliesToItsOwnApp() {
        val learned = listOf(SkipRule("demo", "app:id/iv_x", ""))
        val button = image("app:id/iv_x")
        assertEquals(button, Detector.analyze("demo", 500, listOf(button), 1080, 2400, 3f, learned).candidate)
        assertNull(Detector.analyze("other", 500, listOf(button), 1080, 2400, 3f, learned).candidate)
        assertNull(analyze(button).candidate)
    }
    @Test fun offscreenAndTinyTargetsAreRejected() {
        assertNull(analyze(node(b = Box(1000, 100, 1200, 180))).candidate)
        assertNull(analyze(node(b = Box(800, 100, 810, 110))).candidate)
    }
}
