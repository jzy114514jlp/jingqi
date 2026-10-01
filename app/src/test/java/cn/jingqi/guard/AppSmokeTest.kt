package cn.jingqi.guard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cn.jingqi.guard.data.*
import cn.jingqi.guard.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppSmokeTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun allFourPagesStartWithEmptyDataAndProtectionOff() {
        val cleared = CountDownLatch(1)
        Store.clear { cleared.countDown() }
        assertTrue(cleared.await(10, TimeUnit.SECONDS))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val ready = CountDownLatch(1)
        Store.io.execute { ready.countDown() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Preferences(activity).enabled)
        assertFalse(Preferences(activity).screenshots)
        assertFalse(Preferences(activity).ocr)
        fun find(label: String) = views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == label }
        assertNotNull(find("等待开启守护"))
        find("周报").performClick(); assertNotNull(find("每天的守护"))
        find("取证").performClick(); assertNotNull(find("还没有观察记录"))
        find("设置").performClick(); assertNotNull(find("白名单"))
        assertNotNull(find("报告反馈")); assertNotNull(find("MOMO20170611@outlook.com"))
        assertTrue(Preferences(activity).returnOnAdClick)
        find("误触广告后返回").performClick(); assertFalse(Preferences(activity).returnOnAdClick)
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().none {
            it.text.contains("开发者平台") || it.text.contains("上传码") || it.text.contains("平台地址")
        })
        find("长辈模式").performClick(); assertTrue(Preferences(activity).elder)
        controller.pause().stop().destroy()
    }

    @Test fun manifestHasNoNetworkPermissionAndDisablesBackup() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET))
        assertFalse(info.requestedPermissions.orEmpty().contains(Manifest.permission.ACCESS_NETWORK_STATE))
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        // ML Kit telemetry must have no backend to upload through.
        val discovery = context.packageManager.getServiceInfo(android.content.ComponentName(context,
            "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery"), PackageManager.GET_META_DATA)
        assertTrue(discovery.metaData?.keySet().orEmpty().none { it.startsWith("backend:") })
    }

    @Test fun reportZipContainsActualObservationAndMarksDemo() {
        val context = RuntimeEnvironment.getApplication()
        val event = GuardEvent(kind = "EVIDENCE", source = "cn.jingqi.demo", detail = "8 秒倒计时，待人工核实")
        val file = Reports.export(context, event)
        ZipFile(file).use { zip ->
            assertEquals(1, zip.size())
            val text = zip.getInputStream(zip.getEntry("report.txt")).bufferedReader().readText()
            assertTrue(text.contains("演示场景")); assertTrue(text.contains(event.id)); assertTrue(text.contains("未保存截图"))
        }
    }
}
