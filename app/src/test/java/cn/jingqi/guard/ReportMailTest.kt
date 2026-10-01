package cn.jingqi.guard

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import cn.jingqi.guard.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReportMailTest {
    private fun report(id: String, screenshot: Boolean = false): GuardEvent {
        val context = RuntimeEnvironment.getApplication()
        val shot = if (screenshot) File(context.filesDir, "evidence/$id.png").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4))
        }.absolutePath else ""
        return GuardEvent(id = id, kind = "NOTICE", source = "com.example.reader", detail = "报告 $id 的观察", screenshot = shot)
    }

    @Test fun mailPrefillsRecipientSubjectBodyAndAttachmentGrant() {
        val context = RuntimeEnvironment.getApplication()
        val event = report("sample")
        val draft = ReportMail.prepare(context, listOf(event to false))
        // AndroidX 1.15 FileProvider hardcodes '/' in canonical paths and cannot execute on a Windows host.
        // Verify the mail contract here; real FileProvider resolution is a required Android device check.
        val attachment = Uri.parse("content://${context.packageName}.files/reports/${draft.file.name}")
        val mail = ReportMail.intentWithAttachment(draft, attachment)
        assertEquals(Intent.ACTION_SEND, mail.action)
        assertArrayEquals(arrayOf("MOMO20170611@outlook.com"), mail.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertTrue(mail.getStringExtra(Intent.EXTRA_SUBJECT)!!.contains("净启报告反馈"))
        assertTrue(mail.getStringExtra(Intent.EXTRA_TEXT)!!.contains(event.detail))
        assertEquals(Intent.ACTION_SENDTO, mail.selector!!.action)
        assertEquals("mailto:MOMO20170611@outlook.com", mail.selector!!.data.toString())
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, mail.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION") val uri = mail.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme); assertEquals(uri, mail.clipData!!.getItemAt(0).uri)
        assertEquals(attachment, uri); assertTrue(draft.file.length() > 0)
        val provider = context.packageManager.getProviderInfo(android.content.ComponentName(context,
            "androidx.core.content.FileProvider"), 0)
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
    }

    @Test fun multiReportAttachmentIncludesOnlyIndividuallyChosenScreenshots() {
        val context = RuntimeEnvironment.getApplication()
        val first = report("first", true)
        val second = report("second", true)
        val draft = ReportMail.prepare(context, listOf(first to false, second to true))
        ZipFile(draft.file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertFalse(names.any { it.contains("first.png") })
            assertTrue(names.contains("report-2/second.png"))
            val firstText = zip.getInputStream(zip.getEntry("report-1/report.txt")).bufferedReader().readText()
            val secondText = zip.getInputStream(zip.getEntry("report-2/report.txt")).bufferedReader().readText()
            assertTrue(firstText.contains("本次仅发送文字，未附带截图"))
            assertTrue(secondText.contains(Reports.sha256(File(second.screenshot))))
            assertTrue(draft.body.contains(first.detail)); assertTrue(draft.body.contains(second.detail))
        }
    }

    @Test fun laterDraftDoesNotOverwriteEarlierAttachment() {
        val context = RuntimeEnvironment.getApplication()
        val event = report("same", true)
        val first = ReportMail.prepare(context, listOf(event to false))
        val bytes = first.file.readBytes()
        val second = ReportMail.prepare(context, listOf(event to true))
        assertNotEquals(first.file, second.file)
        assertArrayEquals(bytes, first.file.readBytes())
    }

    @Test fun noMailAppReturnsFalseWithoutLaunchingOrClaimingDelivery() {
        val context = RuntimeEnvironment.getApplication()
        val draft = ReportMail.prepare(context, listOf(report("offline") to false))
        assertFalse(ReportMail.open(context, draft))
        assertNull(shadowOf(context).nextStartedActivity)
        assertTrue(draft.file.exists())
    }

    @Test fun installedMailAppOpensReviewChooserWithAttachmentPermission() {
        val context = RuntimeEnvironment.getApplication()
        val draft = ReportMail.prepare(context, listOf(report("review") to false))
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.mail"; name = "ComposeActivity"; exported = true; enabled = true
                applicationInfo = ApplicationInfo().apply { packageName = "com.example.mail"; enabled = true }
            }
        }
        val intent = ReportMail.intentWithAttachment(draft, Uri.parse("content://${context.packageName}.files/reports/${draft.file.name}"))
        shadowOf(context.packageManager).addResolveInfoForIntent(intent.selector!!, info)
        assertTrue(ReportMail.openIntent(context, intent))
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val mail = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertArrayEquals(arrayOf(ReportMail.ADDRESS), mail.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(mail.clipData!!.getItemAt(0).uri, chooser.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun retiringPlatformKeepsGuardPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val raw = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
        raw.edit().putString("server", "http://192.168.1.5:8787").putString("uploadKey", "old-test-key")
            .putStringSet("uploaded", setOf("old-id")).putBoolean("enabled", true)
            .putStringSet("excluded", setOf("com.example.pay")).commit()
        val prefs = Preferences(context)
        assertFalse(raw.contains("server")); assertFalse(raw.contains("uploadKey")); assertFalse(raw.contains("uploaded"))
        assertTrue(prefs.enabled); assertTrue("com.example.pay" in prefs.excluded)
    }

    @Test fun oversizedScreenshotsAreRejectedWithoutDeletingEvidence() {
        val context = RuntimeEnvironment.getApplication()
        val event = report("large", true)
        val file = File(event.screenshot)
        RandomAccessFile(file, "rw").use { it.setLength(ReportMail.MAX_ATTACHMENT_BYTES + 1) }
        assertThrows(ReportMail.AttachmentTooLarge::class.java) { ReportMail.prepare(context, listOf(event to true)) }
        assertTrue(file.exists())
        val draft = ReportMail.prepare(context, listOf(event to false))
        assertTrue(draft.file.length() < 10_000)
    }
}
