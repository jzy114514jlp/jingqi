package cn.jingqi.guard.ui

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.widget.*
import cn.jingqi.guard.data.*
import java.util.*

class MainActivity : Activity() {
    private val ink = Color.rgb(29, 51, 44)
    private val muted = Color.rgb(113, 128, 119)
    private val green = Color.rgb(23, 105, 84)
    private val paper = Color.rgb(246, 247, 242)
    private val lime = Color.rgb(223, 247, 165)
    private lateinit var prefs: Preferences
    private lateinit var content: LinearLayout
    private var page = 0
    private var events = emptyList<GuardEvent>()
    private var showDemo = false
    private var selecting = false
    private val selected = mutableSetOf<String>()
    private var selectionLabel: TextView? = null
    private var refreshToken = 0
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = value; setTextColor(color); textSize = size * if (prefs.elder) 1.18f else 1f
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun space(parent: LinearLayout, height: Int = 16) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun card(color: Int = Color.WHITE, add: LinearLayout.() -> Unit): LinearLayout {
        return column().apply {
            background = bg(color); setPadding(dp(22), dp(22), dp(22), dp(22)); add()
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
    }
    private fun button(label: String, primary: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = label; textSize = if (prefs.elder) 18f else 15f; isAllCaps = false
        setTextColor(if (primary) Color.WHITE else green); background = bg(if (primary) green else Color.rgb(233, 242, 232), 16)
        minHeight = dp(52); setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { action() }
    }
    private fun action(parent: LinearLayout, label: String, primary: Boolean = true, block: () -> Unit) {
        parent.addView(button(label, primary, block), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); prefs = Preferences(this)
        page = savedInstanceState?.getInt("page") ?: 0; showDemo = savedInstanceState?.getBoolean("demo") ?: false
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); outState.putBoolean("demo", showDemo); super.onSaveInstanceState(outState) }
    private fun refresh() {
        val token = ++refreshToken
        Store.events { list -> runOnUiThread { if (!isFinishing && !isDestroyed && token == refreshToken) { events = list; render() } } }
    }
    private fun granted(): Boolean = (getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager)
        .getEnabledAccessibilityServiceList(-1).any { it.resolveInfo.serviceInfo.packageName == packageName }

    private fun render() {
        val shell = column().apply { setBackgroundColor(paper) }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(12)) }
        header.addView(text("净启", 26f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("JINGQI  /  本地守护", 10f, muted, true))
        shell.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = column().apply { setPadding(dp(20), dp(10), dp(20), dp(16)) }
        scroll.addView(content); shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) { 0 -> home(); 1 -> weekly(); 2 -> evidence(); else -> settings() }
        val nav = LinearLayout(this).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.WHITE) }
        listOf("守护", "周报", "取证", "设置").forEachIndexed { index, title ->
            nav.addView(TextView(this).apply {
                text = title; textSize = if (prefs.elder) 18f else 15f; gravity = Gravity.CENTER
                setTextColor(if (page == index) green else muted); if (page == index) background = bg(Color.rgb(235, 244, 231), 16)
                setTypeface(typeface, if (page == index) Typeface.BOLD else Typeface.NORMAL)
                setOnClickListener { page = index; render() }
                contentDescription = "$title，${if (page == index) "当前页面" else "打开页面"}"
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        shell.addView(nav); setContentView(shell); shell.requestApplyInsets()
    }

    private fun heading(kicker: String, title: String, subtitle: String) {
        content.addView(text(kicker, 11f, green, true)); space(content, 6)
        content.addView(text(title, 28f, bold = true)); space(content, 6)
        content.addView(text(subtitle, 14f, muted)); space(content, 22)
    }
    private fun home() {
        heading("让每一次打开，都更安心", if (prefs.elder) "手机清静一点，\n生活自在一点。" else "把时间，\n还给在意的人。", "每一次打开手机，都不再被广告绑架。")
        val on = prefs.enabled && prefs.consent && granted()
        card(green) {
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val words = column()
            words.addView(text(if (on) "守护已开启" else "等待开启守护", 24f, Color.WHITE, true))
            space(words, 8); words.addView(text(if (on) "安静运行 · 数据留在本机" else "由家人设置，让使用更安心", 13f, Color.rgb(197, 224, 207)))
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(ShieldView(this@MainActivity), LinearLayout.LayoutParams(dp(70), dp(80))); addView(row)
            space(this, 18)
            val toggle = Switch(this@MainActivity).apply {
                text = if (on) "正在守护" else "开启守护"; textSize = if (prefs.elder) 22f else 18f; setTextColor(Color.WHITE)
                minHeight = dp(56); isChecked = on; contentDescription = "净启总开关"
                setOnCheckedChangeListener { _, checked ->
                    if (checked) enable() else { prefs.enabled = false; render() }
                }
            }; addView(toggle)
        }
        if (!on) card {
            addView(text("只需三步，交给净启", 18f, bold = true)); space(this, 14)
            addView(text("01  阅读说明，允许本地辅助操作\n02  在系统中开启「净启 · 开屏守护」\n03  返回这里，确认守护状态", 14f, muted))
            action(this, "开始设置") { enable() }
        }
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val real = events.filter { !it.demo && it.time >= today }
        card {
            addView(text("今天，为你少一点打扰", 17f, bold = true)); space(this, 18)
            stats(this, listOf("${real.count { it.kind == "SKIP" }}" to "执行跳过", "${real.count { it.kind == "RETURN" }}" to "确认返回", "${real.sumOf { it.estimatedSeconds }}s" to "预计节省"))
            space(this, 12); addView(text("仅真实记录 · 时长依据倒计时估算", 11f, muted))
        }
        card(Color.rgb(235, 240, 223)) {
            addView(text("先体验一次安心", 18f, bold = true)); space(this, 8)
            addView(text("在演示实验室体验跳过、自动返回与取证。演示记录单独保存，不计入真实周报。", 14f, muted))
            action(this, "打开演示实验室  →", false) { launchDemo() }
        }
        content.addView(text("无广告  ·  无账号  ·  报告由你选择发送", 12f, muted).apply { gravity = Gravity.CENTER })
    }
    private fun stats(parent: LinearLayout, items: List<Pair<String, String>>) {
        val row = LinearLayout(this)
        items.forEach { (value, label) -> row.addView(column().apply {
            addView(text(value, 28f, green, true)); addView(text(label, 12f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f)) }
        parent.addView(row)
    }
    private fun enable() {
        if (!prefs.consent) {
            AlertDialog.Builder(this).setTitle("开启前，请了解净启的操作")
                .setMessage("净启通过无障碍服务读取前台应用名称和界面控件，识别开屏广告后模拟点击“跳过”，或对已识别开屏广告的跳转执行一次返回。默认开启误触保护，点击这类广告后也会帮助返回；想主动查看广告时可在设置中关闭。返回键无效时可能尝试打开原应用一次并进入首页。\n\n可能识别不准，你可以随时关闭总开关或添加白名单。截图取证与 OCR 默认关闭，需要单独开启。全部处理在本机，净启不申请联网权限；只有你主动分享或在邮件应用中发送时，报告才会交给所选应用。记录保留 30 天。\n\n请仅在了解并同意这些操作后开启系统权限。")
                .setPositiveButton("同意并继续") { _, _ -> prefs.consent = true; prefs.enabled = true; openAccessibility() }
                .setNegativeButton("暂不开启") { _, _ -> render() }.setOnCancelListener { render() }.show()
        } else { prefs.enabled = true; if (!granted()) openAccessibility() else render() }
    }
    private fun openAccessibility() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { toast("请手动进入系统设置 → 无障碍 → 净启") }
    }
    private fun launchDemo() {
        runCatching { startActivity(Intent().setComponent(ComponentName("cn.jingqi.demo", "cn.jingqi.demo.MainActivity"))) }
            .onFailure { AlertDialog.Builder(this).setTitle("请先安装演示 APK").setMessage("同时安装交付目录中的 JingQi-Demo-debug.apk，然后再次打开演示实验室。主应用的真实守护不依赖演示 APK。").setPositiveButton("知道了", null).show() }
    }
    private fun filterSwitch() {
        content.addView(Switch(this).apply { text = "查看演示记录"; textSize = 14f; minHeight = dp(48); isChecked = showDemo
            setOnCheckedChangeListener { _, checked -> showDemo = checked; render() } }); space(content, 12)
    }
    private fun weekly() {
        heading("每一份安心，都有迹可循", "这一周，少些打扰。", "最近 7 天 · ${if (showDemo) "演示数据" else "真实记录"}")
        filterSwitch()
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val list = events.filter { it.demo == showDemo && it.time >= cutoff }
        card { stats(this, listOf("${list.count { it.kind == "SKIP" }}" to "执行跳过", "${list.count { it.kind == "RETURN" }}" to "确认返回", "${list.sumOf { it.estimatedSeconds }}s" to "预计节省")) }
        card {
            addView(text("每天的守护", 18f, bold = true)); space(this)
            val days = (0..6).map { offset -> Calendar.getInstance().apply { timeInMillis = cutoff; add(Calendar.DAY_OF_YEAR, offset) } }
            val counts = days.map { day -> val end = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
                list.count { it.time >= day.timeInMillis && it.time < end && it.kind in setOf("SKIP", "RETURN") } }
            val max = maxOf(1, counts.maxOrNull() ?: 0)
            days.forEachIndexed { i, day ->
                val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(text("${day.get(Calendar.MONTH) + 1}/${day.get(Calendar.DAY_OF_MONTH)}", 12f, muted), LinearLayout.LayoutParams(dp(44), dp(30)))
                val track = FrameLayout(this@MainActivity).apply { background = bg(Color.rgb(239, 242, 235), 4) }
                val bar = View(this@MainActivity).apply { background = bg(green, 4) }
                track.addView(bar, FrameLayout.LayoutParams(0, -1)); track.post { bar.layoutParams = FrameLayout.LayoutParams((track.width * counts[i].toFloat() / max).toInt(), -1) }
                row.addView(track, LinearLayout.LayoutParams(0, dp(10), 1f)); row.addView(text("  ${counts[i]}", 13f, green), LinearLayout.LayoutParams(dp(34), -2)); addView(row)
            }
        }
        card {
            addView(text("最近触发的应用", 18f, bold = true)); space(this)
            val groups = list.filter { it.kind in setOf("SKIP", "RETURN") }.groupBy { it.source }.entries.sortedByDescending { it.value.size }.take(5)
            if (groups.isEmpty()) addView(text("还没有记录。守护开启后，这里会慢慢积累。", 14f, muted))
            groups.forEach { addView(text("${Reports.appName(this@MainActivity, it.key)}  ·  ${it.value.size} 次", 15f)); space(this, 10) }
            action(this, "分享本周摘要", false) {
                val summary = "净启最近 7 天${if (showDemo) "【演示】" else ""}周报\n执行跳过 ${list.count { it.kind == "SKIP" }} 次\n确认返回 ${list.count { it.kind == "RETURN" }} 次\n预计节省 ${list.sumOf { it.estimatedSeconds }} 秒（倒计时估算）\n次数不代表成功率；仅包含本机保留的最近 1000 条记录。"
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary), "分享周报"))
            }
        }
        content.addView(text("未测量成功率；“执行跳过”指系统接受了点击，不保证每次都关闭广告。列表最多展示最近 1000 条记录。", 12f, muted))
    }

    private fun evidence() {
        heading("先留证，再核实", "让遇到的问题，\n有据可查。", "所有报告仅保存在本机，分享由你决定。")
        filterSwitch()
        val list = events.filter { it.demo == showDemo }
        if (list.isEmpty()) card {
            addView(text("还没有观察记录", 20f, bold = true)); space(this)
            addView(text("开启守护后，执行跳过、确认返回和疑似问题会出现在这里。需要截图时，请在设置中单独开启。", 15f, muted))
            action(this, "查看取证设置", false) { page = 3; render() }
        } else if (selecting) {
            selected.retainAll(list.map { it.id }.toSet())
            card(Color.rgb(235, 240, 223)) {
                addView(text("已选择 ${selected.size} 条报告", 17f, bold = true).also { selectionLabel = it }); space(this, 6)
                addView(text("收件人：${ReportMail.ADDRESS}\n只发送你勾选的报告；下一步可选择截图，最后在邮件应用中确认发送。", 13f, muted))
                action(this, "通过邮件发送所选报告") { confirmEmail(list.filter { it.id in selected }) }
                action(this, "取消选择", false) { selecting = false; selected.clear(); render() }
            }
        } else action(content, "选择报告通过邮件发送", false) { selecting = true; render() }
        list.take(80).forEach { e -> card {
            addView(text("${if (e.demo) "演示 · " else ""}${Reports.label(e.kind)}", 12f, green, true)); space(this, 7)
            addView(text(Reports.appName(this@MainActivity, e.source), 18f, bold = true)); space(this, 5)
            addView(text(Reports.date(e.time), 12f, muted)); space(this, 10)
            addView(text(e.detail, 14f, muted))
            if (selecting) addView(CheckBox(this@MainActivity).apply {
                text = "选择此报告"; textSize = if (prefs.elder) 18f else 15f; minHeight = dp(48); isChecked = e.id in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += e.id else selected -= e.id
                    selectionLabel?.text = "已选择 ${selected.size} 条报告"
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            else action(this, "查看报告${if (e.screenshot.isNotBlank()) " · 含截图" else ""}", false) { showReport(e) }
        } }
    }

    /** Lets the user decide, per report, whether its screenshots go along; text-only is the default. */
    private fun confirmEmail(reports: List<GuardEvent>) {
        if (reports.isEmpty()) { toast("请先勾选要发送的报告"); return }
        val body = column().apply { setPadding(dp(22), dp(12), dp(22), dp(4)) }
        body.addView(text("收件人：${ReportMail.ADDRESS}\n\n将准备 ${reports.size} 条报告的文字、应用名称与包名、手机型号和系统版本，并生成 ZIP 附件。接下来打开你选择的邮件应用，由你检查并点击发送。本机记录保持不变。", 14f, muted))
        val withShots = reports.filter { Reports.attachments(it).isNotEmpty() }
        val boxes = withShots.map { e ->
            CheckBox(this).apply { text = "附带截图：${Reports.appName(this@MainActivity, e.source)} · ${Reports.date(e.time)}"; textSize = 14f; minHeight = dp(48) }
        }
        if (boxes.isNotEmpty()) {
            space(body, 10); body.addView(text("截图可能包含账号、消息等个人信息，默认不附带。需要时请逐条勾选（附件最大 15 MB）：", 13f, ink, true))
            boxes.forEach { body.addView(it) }
        }
        AlertDialog.Builder(this).setTitle("通过邮件反馈报告").setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("取消", null).setPositiveButton("打开邮件应用") { _, _ ->
                val shotIds = withShots.filterIndexed { i, _ -> boxes[i].isChecked }.map { it.id }.toSet()
                prepareEmail(reports.map { it to (it.id in shotIds) })
            }.show()
    }
    private fun prepareEmail(items: List<Pair<GuardEvent, Boolean>>) {
        toast("正在准备邮件和报告附件…")
        Store.io.execute {
            runCatching { ReportMail.prepare(this, items) }.onSuccess { draft ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ReportMail.open(this, draft)) {
                        toast("请在邮件应用中检查收件人和附件，再点击发送")
                    } else {
                        AlertDialog.Builder(this).setTitle("暂时无法打开邮件应用")
                            .setMessage("请安装并登录一个邮件应用后重试。收件邮箱：${ReportMail.ADDRESS}\n\n你也可以先复制邮箱和报告文字，再导出 ZIP 作为附件。")
                            .setPositiveButton("复制邮箱和报告") { _, _ -> Reports.copy(this, "收件人：${ReportMail.ADDRESS}\n\n${draft.body}"); toast("邮箱和报告已复制") }
                            .setNeutralButton("导出报告 ZIP") { _, _ -> runCatching { Reports.share(this, draft.file) }.onFailure { toast("未找到可分享的应用") } }
                            .setNegativeButton("关闭", null).show()
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (error is ReportMail.AttachmentTooLarge) toast("附件超过 15 MB，请减少报告数量或取消部分截图后重试")
                        else toast("报告准备失败，请稍后重试。本机记录仍然保留。")
                    }
                }
            }
        }
    }
    private fun showReport(e: GuardEvent) {
        val body = column().apply { setPadding(dp(22), dp(16), dp(22), dp(16)) }
        val report = text(Reports.text(this, e), 14f).apply { setTextIsSelectable(true) }; body.addView(report)
        if (e.screenshot.isNotBlank()) {
            val options = BitmapFactory.Options().apply { inSampleSize = 3 }
            val bitmap = BitmapFactory.decodeFile(e.screenshot, options)
            if (bitmap != null) body.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; contentDescription = "采集时的页面截图" }, LinearLayout.LayoutParams(-1, -2))
        }
        action(body, "复制报告内容", false) { Reports.copy(this, Reports.text(this, e)); toast("报告已复制") }
        action(body, "导出报告与截图 ZIP", false) {
            Store.io.execute { runCatching { Reports.export(this, e) }.onSuccess { f -> runOnUiThread { runCatching { Reports.share(this, f) }.onFailure { toast("未找到可分享的应用") } } }.onFailure { runOnUiThread { toast("导出失败，请重试") } } }
        }
        val dialog = AlertDialog.Builder(this).setTitle("本地观察报告").setView(ScrollView(this).apply { addView(body) }).setPositiveButton("关闭", null).create()
        action(body, "通过邮件发送报告") { dialog.dismiss(); confirmEmail(listOf(e)) }
        dialog.show()
    }
    private fun setting(parent: LinearLayout, title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) {
        parent.addView(Switch(this).apply { text = title; textSize = if (prefs.elder) 19f else 16f; minHeight = dp(54); isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) } })
        parent.addView(text(detail, 12f, muted)); space(parent, 14)
    }
    private fun settings() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "未知"
        heading("由你决定，如何守护", "安心，也要有边界。", "当前版本 $version · Android 本地版")
        card {
            setting(this, "长辈模式", "放大字号；确认返回后使用设备已有的离线中文语音。设备没有离线语音时保持安静。", prefs.elder) { prefs.elder = it; render() }
            setting(this, "防自动跳转", "识别到开屏广告后的 6 秒内，跳往已知目标时尝试返回。返回键无效时尝试打开原应用一次，可能回到首页。", prefs.returnGuard) { prefs.returnGuard = it }
            setting(this, "误触广告后返回", "默认开启，需同时开启防自动跳转。点击已识别的开屏广告后也帮助返回；想主动查看广告时请关闭。普通页面点击和白名单仍放行。", prefs.returnOnAdClick) { prefs.returnOnAdClick = it }
            setting(this, "截图取证", "默认关闭。开启后疑似开屏可能保存完整屏幕（含个人信息），保留 30 天。Android 11 及以上可用。", prefs.screenshots) { enabled ->
                if (enabled && Build.VERSION.SDK_INT < 30) { toast("当前系统不支持无障碍截图"); render() }
                else if (enabled) confirmCapture(false) else prefs.screenshots = false
            }
            setting(this, "本地 OCR 兜底", "默认关闭。使用内置中文模型识别屏幕；图片仅在内存处理，不因 OCR 保存。Android 11 及以上可用。", prefs.ocr) { enabled ->
                if (enabled && Build.VERSION.SDK_INT < 30) { toast("当前系统不支持无障碍截图"); render() }
                else if (enabled) confirmCapture(true) else prefs.ocr = false
            }
        }
        card {
            addView(text("白名单", 18f, bold = true)); space(this, 8)
            addView(text("这些应用不执行点击或返回。可把支付、工作等重要应用加入。", 13f, muted))
            prefs.excluded.sorted().forEach { pkg -> action(this, "${Reports.appName(this@MainActivity, pkg)}  ·  移除", false) { prefs.excluded = prefs.excluded - pkg; render() } }
            action(this, "添加应用", false) { addExcluded() }
        }
        card {
            addView(text("报告反馈", 18f, bold = true)); space(this, 8)
            addView(text(ReportMail.ADDRESS, 16f, green, true).apply { setTextIsSelectable(true) })
            space(this, 8); addView(text("选择报告后，净启会打开邮件应用并预填收件人、正文和 ZIP 附件。截图可选，邮件由你确认发送。", 13f, muted))
            action(this, "复制反馈邮箱", false) { Reports.copy(this@MainActivity, ReportMail.ADDRESS); toast("邮箱已复制") }
            action(this, "选择报告发送", false) { page = 2; selecting = true; selected.clear(); render() }
        }
        card {
            addView(text("服务与数据", 18f, bold = true)); space(this, 10)
            addView(text("无障碍权限：${if (granted()) "已开启" else "未开启"}\n规则策略：保守文字识别＋图片跳过按钮识别\n已学习规则：${Store.learnedCount} 条（OCR 成功跳过后自动记住）\n本地记录：${events.size} 条（最多展示 1000 条）", 14f, muted))
            action(this, "打开系统无障碍设置", false) { openAccessibility() }
            if (Store.learnedCount > 0) action(this, "清除已学习规则", false) {
                AlertDialog.Builder(this@MainActivity).setTitle("清除已学习规则？").setMessage("净启会忘记在本机记住的图片跳过按钮，之后需要重新通过 OCR 识别。事件记录不受影响。")
                    .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> Store.forgetLearned { runOnUiThread { render(); toast("已清除学习规则") } } }.show()
            }
            action(this, "清除全部记录和截图", false) {
                AlertDialog.Builder(this@MainActivity).setTitle("清除本地记录？").setMessage("将删除事件记录、私有截图和报告缓存，已分享到其他应用的文件不受影响。守护会同时暂停，避免继续生成记录。")
                    .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> prefs.enabled = false; Store.clear { runOnUiThread { refresh(); toast("记录已清除，守护已暂停") } } }.show()
            }
        }
        card(Color.rgb(235, 240, 223)) {
            addView(text("能力说明", 17f, bold = true)); space(this, 10)
            addView(text("• 不修改其他应用，不关闭摇一摇传感器。\n• 不保证识别所有广告；不确定时不操作。\n• 截图可能被系统或应用禁止。\n• 启动时间来自前台窗口变化，是近似判断。\n• 正常点击保护依赖系统事件，不能完全推断真实意图。\n• 目前只有本地周报，无远程账号同步。", 13f, muted))
        }
    }
    private fun confirmCapture(ocr: Boolean) {
        AlertDialog.Builder(this).setTitle(if (ocr) "允许在本机识别屏幕？" else "允许保存现场截图？")
            .setMessage(if (ocr) "OCR 会通过无障碍权限截取屏幕并交给内置模型识别。图像不上传，识别后释放；若同时开启截图取证，则取证截图会另行保存。" else "截图可能包含账号、消息等个人信息。文件仅保存在应用私有目录中，分享前请核对。可以随时关闭，并在设置中清除。")
            .setPositiveButton("允许") { _, _ -> if (ocr) prefs.ocr = true else prefs.screenshots = true; render() }
            .setNegativeButton("取消") { _, _ -> render() }.setOnCancelListener { render() }.show()
    }
    private fun addExcluded() {
        Store.io.execute {
            val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct().filter { it != packageName && it !in prefs.excluded }
                .map { it to Reports.appName(this, it) }
                .sortedWith(compareBy(java.text.Collator.getInstance(Locale.CHINA)) { it.second })
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val labels = (listOf("手动输入包名…") + apps.map { "${it.second}\n${it.first}" }).toTypedArray()
                AlertDialog.Builder(this).setTitle("选择不需要守护的应用").setItems(labels) { _, which ->
                    if (which == 0) addExcludedManually() else { prefs.excluded = prefs.excluded + apps[which - 1].first; render() }
                }.setNegativeButton("取消", null).show()
            }
        }
    }
    private fun addExcludedManually() {
        val input = EditText(this).apply { hint = "例如 com.example.app"; inputType = android.text.InputType.TYPE_CLASS_TEXT; setSingleLine() }
        val dialog = AlertDialog.Builder(this).setTitle("添加白名单").setView(input).setNegativeButton("取消", null).setPositiveButton("添加", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)) input.error = "请输入完整应用包名"
            else { prefs.excluded = prefs.excluded + value; dialog.dismiss(); render() }
        } }; dialog.show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private inner class ShieldView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.color = lime; paint.style = Paint.Style.FILL
            val shield = Path().apply { moveTo(w * .5f, h * .08f); lineTo(w * .9f, h * .24f); lineTo(w * .84f, h * .6f); quadTo(w * .74f, h * .82f, w * .5f, h * .95f); quadTo(w * .26f, h * .82f, w * .16f, h * .6f); lineTo(w * .1f, h * .24f); close() }
            canvas.drawPath(shield, paint)
            paint.color = green; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(4).toFloat(); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(Path().apply { moveTo(w * .31f, h * .5f); lineTo(w * .46f, h * .64f); lineTo(w * .7f, h * .37f) }, paint)
        }
    }
}
