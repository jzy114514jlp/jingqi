package cn.jingqi.demo

import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun d(n: Int) = (n * density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(24), d(28), d(24), d(24)); setBackgroundColor(Color.rgb(246, 247, 242)) }
        column.addView(TextView(this).apply { text = "净启\n演示实验室"; textSize = 32f; setTextColor(Color.rgb(23, 105, 84)); setTypeface(typeface, Typeface.BOLD) })
        column.addView(TextView(this).apply { text = "可控场景 · 不展示真实广告\n请先在主应用开启守护。所有结果标为演示，不计入真实统计。"; textSize = 15f; setPadding(0, d(18), 0, d(22)) })
        val scenarios = listOf(
            Triple("skip", "01  自动跳过", "唯一角落按钮；预期自动进入完成页"),
            Triple("jump", "02  自动跳转后返回", "1.8 秒后模拟跳转；预期自动返回"),
            Triple("intent", "03  正常点击放行", "普通页面主动打开模拟购物页；预期不拦截"),
            Triple("evidence", "04  截图与报告", "8 秒倒计时、无可读关闭按钮"),
            Triple("ambiguous", "05  多候选不点击", "同时有两个跳过按钮；预期不自动操作"),
            Triple("ocr", "06  图片文字 OCR", "画布中的按钮；需单独开启 OCR"),
            Triple("nested", "07  容器内跳过文字", "文字本身不可点、外层小容器可点；预期自动跳过"),
            Triple("touch", "08  只认真实触摸", "忽略无障碍点击，只响应触摸；预期改用模拟触摸后跳过"),
            Triple("refuse", "09  拒绝无障碍点击", "直接拒绝无障碍点击；预期立即改用模拟触摸后跳过"),
            Triple("adclick", "10  误点广告后返回", "点击模拟广告；开启误触保护时预期返回，关闭时放行"),
            Triple("ring", "11  圆环跳过文字", "可读文字不可点；识别广告标记后触摸圆心"),
            Triple("ringocr", "12  圆环图片跳过", "动画画布，无文字节点；需 Android 11+ 和本地 OCR")
        )
        scenarios.forEach { (mode, title, description) ->
            column.addView(Button(this).apply {
                text = title; textSize = 17f; isAllCaps = false; minHeight = d(56); setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.rgb(23, 105, 84)); cornerRadius = d(14).toFloat() }
                setOnClickListener { startActivity(Intent(this@MainActivity, SplashActivity::class.java).putExtra("mode", mode)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(12) })
            column.addView(TextView(this).apply { text = description; textSize = 13f; setPadding(d(4), d(6), d(4), d(8)) })
        }
        column.addView(Button(this).apply { text = "返回净启查看演示记录"; setOnClickListener {
            runCatching { startActivity(Intent().setComponent(ComponentName("cn.jingqi.guard", "cn.jingqi.guard.ui.MainActivity"))) }
                .onFailure { Toast.makeText(this@MainActivity, "请先安装净启主应用", Toast.LENGTH_LONG).show() }
        } })
        val scroll = ScrollView(this).apply { addView(column); fitsSystemWindows = true }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }
}

class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var mode = "skip"
    private var jumped = false
    private var leftForTarget = false
    private var complete = false
    private lateinit var frame: FrameLayout
    private fun d(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 18f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(23, 70, 53)); gravity = Gravity.CENTER }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "skip"
        jumped = savedInstanceState?.getBoolean("jumped") ?: false
        complete = savedInstanceState?.getBoolean("complete") ?: false
        frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(233, 240, 220)); fitsSystemWindows = true }
        setContentView(frame)
        if (complete) { showComplete("本场景已结束"); return }
        if (mode == "ocr" || mode == "ringocr") {
            frame.addView(CanvasAd(this, mode == "ringocr"), FrameLayout.LayoutParams(-1, -1))
        } else {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(d(28), d(80), d(28), d(24)) }
            body.addView(label(if (mode == "intent") "普通页面\n自由浏览。" else "留一点时间，\n给生活。", 38f))
            body.addView(label(if (mode == "intent") "这里没有开屏广告" else "净启模拟广告 · 无真实商品", 15f).apply { setPadding(0, d(18), 0, d(26)) })
            if (mode == "intent") body.addView(Button(this).apply { text = "我主动打开模拟购物页"; setOnClickListener { openTarget() } })
            if (mode == "adclick") body.addView(Button(this).apply { text = "模拟误点广告"; setOnClickListener { openTarget() } })
            if (mode == "jump") body.addView(label("即将模拟无点击自动跳转…", 15f))
            if (mode == "ambiguous") body.addView(label("两个候选按钮，净启应保持不动", 15f))
            if (mode == "evidence") body.addView(label("演示：倒计时偏长且无可读跳过控件\n请等待记录后返回查看报告", 15f))
            frame.addView(body, FrameLayout.LayoutParams(-1, -1))
            if (mode != "intent") {
                frame.addView(label(if (mode == "ring") "AM广告" else "广告", 13f), FrameLayout.LayoutParams(d(60), d(36), Gravity.BOTTOM or Gravity.LEFT).apply { leftMargin = d(12); bottomMargin = d(38) })
                val counter = label(if (mode == "evidence") "8s" else "5s", 16f)
                frame.addView(counter, FrameLayout.LayoutParams(d(70), d(40), Gravity.TOP or Gravity.LEFT).apply { leftMargin = d(16); topMargin = d(38) })
            }
            if (mode == "skip" || mode == "ambiguous") addSkip(Gravity.TOP or Gravity.RIGHT)
            if (mode == "ambiguous") addSkip(Gravity.BOTTOM or Gravity.RIGHT)
            if (mode == "nested") addNestedSkip()
            if (mode == "touch" || mode == "refuse") addTouchOnlySkip(refuseAccessibility = mode == "refuse")
            if (mode == "ring") addRingSkip()
        }
        if (mode == "jump" && !jumped) handler.postDelayed({ if (!isFinishing && !complete) { jumped = true; openTarget() } }, 1_800)
        handler.postDelayed({ if (!isFinishing && !complete && !leftForTarget) showComplete("演示计时结束\n请到净启查看演示记录") }, if (mode == "evidence") 8_000 else 9_000)
    }
    private fun addSkip(gravityValue: Int) {
        frame.addView(Button(this).apply {
            id = if (gravityValue and Gravity.TOP == Gravity.TOP) R.id.skip_ad else View.generateViewId()
            text = "跳过"; textSize = 16f; isAllCaps = false; setOnClickListener { showComplete("已触发跳过按钮\n广告页面已关闭") }
        }, FrameLayout.LayoutParams(d(90), d(48), gravityValue).apply { rightMargin = d(16); topMargin = d(36); bottomMargin = d(40) })
    }
    /** Common real-world shape: a non-clickable label inside a small clickable container. */
    private fun addNestedSkip() {
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.argb(140, 0, 0, 0)); cornerRadius = d(24).toFloat() }
            isClickable = true; setOnClickListener { showComplete("已触发容器内跳过\n广告页面已关闭") }
        }
        container.addView(TextView(this).apply { text = "跳过 5"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1))
        frame.addView(container, FrameLayout.LayoutParams(d(90), d(40), Gravity.TOP or Gravity.RIGHT).apply { rightMargin = d(16); topMargin = d(36) })
    }
    /**
     * Like some ad SDKs: only a real touch closes the ad. Accessibility clicks are accepted but ignored, or with
     * [refuseAccessibility] refused outright.
     */
    private fun addTouchOnlySkip(refuseAccessibility: Boolean) {
        frame.addView(object : TextView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) { performClick(); showComplete("模拟触摸已触发跳过\n广告页面已关闭") }
                return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
                if (refuseAccessibility && action == AccessibilityNodeInfo.ACTION_CLICK) false
                else super.performAccessibilityAction(action, arguments)
        }.apply {
            text = "跳过"; textSize = 16f; gravity = Gravity.CENTER; isClickable = true; setTextColor(Color.rgb(23, 70, 53))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = d(20).toFloat() }
        }, FrameLayout.LayoutParams(d(90), d(44), Gravity.TOP or Gravity.RIGHT).apply { rightMargin = d(16); topMargin = d(36) })
    }
    private fun openTarget() {
        leftForTarget = true
        runCatching { startActivity(Intent().setComponent(ComponentName("cn.jingqi.guard", "cn.jingqi.guard.ui.DemoLandingActivity"))) }
            .onFailure { leftForTarget = false; Toast.makeText(this, "请先安装净启主应用", Toast.LENGTH_LONG).show() }
    }
    /** The ring handles touches but exposes no click action; only the small child label is readable. */
    private fun addRingSkip() {
        val ring = object : FrameLayout(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(58, 180, 215); style = Paint.Style.STROKE; strokeWidth = d(3).toFloat()
            }
            private val oval = RectF()
            init { setWillNotDraw(false) }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                oval.set(d(3).toFloat(), d(3).toFloat(), (width - d(3)).toFloat(), (height - d(3)).toFloat())
                canvas.drawArc(oval, -90f, 360f * (1f - (SystemClock.uptimeMillis() % 5_000) / 5_000f), false, paint)
                if (!complete) postInvalidateDelayed(80)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) { performClick(); showComplete("圆环跳过已触发\n广告页面已关闭") }
                return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }
        ring.addView(label("跳过", 14f).apply { includeFontPadding = false }, FrameLayout.LayoutParams(d(40), d(18), Gravity.CENTER))
        frame.addView(ring, FrameLayout.LayoutParams(d(56), d(56), Gravity.TOP or Gravity.RIGHT).apply { rightMargin = d(16); topMargin = d(36) })
    }
    override fun onResume() {
        super.onResume()
        if (leftForTarget) { leftForTarget = false; showComplete("已回到原应用\n请查看净启是否记录了“确认返回”") }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("jumped", jumped); outState.putBoolean("complete", complete); super.onSaveInstanceState(outState)
    }
    private fun showComplete(message: String) {
        complete = true; handler.removeCallbacksAndMessages(null); frame.removeAllViews()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(d(24), d(40), d(24), d(24)) }
        body.addView(label(message, 25f)); body.addView(label("这是受控演示，不能代替第三方应用实测。", 14f).apply { setPadding(0, d(28), 0, d(28)) })
        body.addView(Button(this).apply { text = "回到场景列表"; setOnClickListener { finish() } })
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private inner class CanvasAd(context: Context, private val ring: Boolean = false) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hit = RectF()
        private val announceAnimation = object : Runnable {
            override fun run() {
                if (!complete && isAttachedToWindow) {
                    frame.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    handler.postDelayed(this, 80)
                }
            }
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (ring) handler.post(announceAnimation) }
        override fun onDetachedFromWindow() { handler.removeCallbacks(announceAnimation); super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(233, 240, 220)); paint.color = Color.rgb(23, 70, 53); paint.textSize = d(16).toFloat()
            canvas.drawText(if (ring) "AM广告" else "广告", d(20).toFloat(), d(70).toFloat(), paint)
            hit.set(width - d(if (ring) 72 else 106).toFloat(), d(38).toFloat(), width - d(16).toFloat(), d(if (ring) 94 else 88).toFloat())
            paint.color = Color.WHITE
            if (ring) canvas.drawOval(hit, paint) else canvas.drawRoundRect(hit, d(16).toFloat(), d(16).toFloat(), paint)
            if (ring) {
                paint.color = Color.rgb(58, 180, 215); paint.style = Paint.Style.STROKE; paint.strokeWidth = d(3).toFloat()
                canvas.drawArc(hit, -90f, 360f * (1f - (SystemClock.uptimeMillis() % 5_000) / 5_000f), false, paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = Color.rgb(23, 70, 53); paint.textSize = d(if (ring) 18 else 22).toFloat()
            val textWidth = paint.measureText("跳过")
            canvas.drawText("跳过", hit.centerX() - textWidth / 2, hit.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            paint.textSize = d(32).toFloat(); canvas.drawText("清静一点。", d(42).toFloat(), height * .45f, paint)
            paint.textSize = d(15).toFloat(); canvas.drawText("图片文字场景 · 请开启本地 OCR", d(26).toFloat(), height * .55f, paint)
            if (ring && !complete) postInvalidateDelayed(80)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP && hit.contains(event.x, event.y)) { performClick(); showComplete("画布跳过按钮已触发"); return true }
            return event.action == MotionEvent.ACTION_DOWN
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
