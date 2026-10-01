package cn.jingqi.guard.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*

/** An inert, exported simulation target. Accepts no URLs, files, or commands. */
class DemoLandingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 80, 48, 48); setBackgroundColor(Color.rgb(251, 239, 227))
            fitsSystemWindows = true
        }
        layout.addView(TextView(this).apply { text = "模拟购物页"; textSize = 30f; setTextColor(Color.rgb(80, 46, 24)) })
        layout.addView(TextView(this).apply { text = "这是净启演示跳转目标，无真实商品或交易。\n\n自动跳转：守护应帮助你返回。\n普通页面主动点击：守护应放行。\n误点开屏广告：开启误触保护时应返回。"; textSize = 18f; setPadding(0, 30, 0, 50) })
        layout.addView(Button(this).apply { text = "手动返回实验室"; setOnClickListener { finish() } })
        setContentView(layout)
    }
}
