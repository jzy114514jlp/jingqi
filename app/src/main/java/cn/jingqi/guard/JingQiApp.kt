package cn.jingqi.guard

import android.app.Application
import cn.jingqi.guard.data.Store

class JingQiApp : Application() {
    override fun onCreate() { super.onCreate(); Store.init(this) }
}
