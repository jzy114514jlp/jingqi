package cn.jingqi.guard.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Prepares a user-reviewed draft. No network, SMTP credentials, or sent/delivered claims. */
object ReportMail {
    const val ADDRESS = "MOMO20170611@outlook.com"
    const val MAX_ATTACHMENT_BYTES = 15L * 1024 * 1024
    class AttachmentTooLarge : Exception()
    data class Draft(val subject: String, val body: String, val file: File)

    fun prepare(context: Context, items: List<Pair<GuardEvent, Boolean>>): Draft {
        require(items.isNotEmpty()) { "No reports selected" }
        val unique = items.distinctBy { it.first.id }
        val attachments = unique.map { (event, include) -> if (include) Reports.attachments(event) else emptyList() }
        if (attachments.sumOf { files -> files.sumOf { it.length() } } > MAX_ATTACHMENT_BYTES) throw AttachmentTooLarge()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val environment = "净启版本：$version\n手机：${Build.MANUFACTURER} ${Build.MODEL}\nAndroid：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
        val reportTexts = unique.map { (event, include) -> Reports.text(context, event, include) }
        val subject = "净启报告反馈 · ${unique.size} 条${if (unique.any { it.first.demo }) "（含演示记录）" else ""}"
        val body = "你好，我想反馈以下净启使用情况。\n\n补充说明：（可在发送前填写）\n\n$environment\n\n" +
            reportTexts.joinToString("\n\n──────────\n\n") + "\n\nZIP 附件包含所选报告及已勾选的截图，请检查附件后发送。"
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        // Unique export per draft prevents a later export from changing an earlier mail attachment.
        val file = File(dir, "jingqi-feedback-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun writeText(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                writeText("feedback.txt", body)
                unique.forEachIndexed { index, _ ->
                    val prefix = "report-${index + 1}/"
                    val files = attachments[index]
                    val digests = files.joinToString("\n") { f -> "${f.name}: ${Reports.sha256(f)}" }
                    writeText(prefix + "report.txt", reportTexts[index] + if (digests.isEmpty()) "" else "\n\nSHA-256（完整性校验，非时间戳认证）\n$digests")
                    files.forEach { attachment ->
                        zip.putNextEntry(ZipEntry(prefix + attachment.name)); attachment.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
            }
            if (file.length() > MAX_ATTACHMENT_BYTES) throw AttachmentTooLarge()
            return Draft(subject, body, file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun intent(context: Context, draft: Draft): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", draft.file)
        return intentWithAttachment(draft, uri)
    }

    // Keep the standard mail contract separate from the Android-only FileProvider path resolution.
    internal fun intentWithAttachment(draft: Draft, uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
            putExtra(Intent.EXTRA_SUBJECT, draft.subject)
            putExtra(Intent.EXTRA_TEXT, draft.body)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("净启报告附件", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Resolve using mailto handlers while preserving ACTION_SEND's attachment contract.
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$ADDRESS"))
        }
    }

    fun open(context: Context, draft: Draft): Boolean {
        val selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$ADDRESS"))
        if (selector.resolveActivity(context.packageManager) == null) return false
        return openIntent(context, intent(context, draft))
    }

    internal fun openIntent(context: Context, mail: Intent): Boolean {
        if (mail.selector!!.resolveActivity(context.packageManager) == null) return false
        return try {
            val chooser = Intent.createChooser(mail, "选择邮件应用，检查后发送").apply {
                clipData = mail.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) { false }
          catch (_: SecurityException) { false }
    }
}
