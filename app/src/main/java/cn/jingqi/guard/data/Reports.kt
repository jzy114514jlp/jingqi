package cn.jingqi.guard.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Reports {
    fun date(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(time))
    fun label(kind: String) = when (kind) { "SKIP" -> "执行跳过"; "RETURN" -> "确认返回"; "EVIDENCE" -> "页面观察"; else -> "操作提示" }
    fun appName(context: Context, pkg: String): String = when (pkg) {
        "cn.jingqi.demo" -> "净启演示实验室"
        "cn.jingqi.demo.target" -> "模拟购物页"
        else -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
    fun text(context: Context, e: GuardEvent, includeScreenshots: Boolean = true): String = """
        净启 · 本地观察报告
        ${if (e.demo) "【演示场景，不得作为真实投诉材料】" else "【待人工核实，不是违规认定】"}

        时间：${date(e.time)}
        应用：${appName(context, e.source)}
        包名：${e.source}
        事件：${label(e.kind)}
        跳转目标：${e.target.ifBlank { "未记录" }}
        记录编号：${e.id}

        观察说明：
        ${e.detail}

        取证说明：${if (!includeScreenshots) "本次仅发送文字，未附带截图。" else if (attachments(e).isNotEmpty()) "附已保存的原始 PNG 与标注副本；截图为采集时页面，未保证捕获跳转瞬间。" else "未保存截图（未开启、系统不支持、页面已切换或受保护）。"}
        本工具无法读取广告链接的真实来源，无法仅凭节点判断按钮物理尺寸或法律性质。
        分享或发送邮件前请检查个人信息。
        所有自动生成的记录均可在设置中删除，保留期 30 天。
    """.trimIndent()

    fun copy(context: Context, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("净启报告", text))
    }

    /** Original screenshot followed by its marked copy, whichever still exist. */
    fun attachments(e: GuardEvent): List<File> {
        val original = e.screenshot.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        val marked = original?.let { File(it.parentFile, it.nameWithoutExtension + "_marked.png") }?.takeIf { it.exists() }
        return listOfNotNull(original, marked)
    }

    fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count >= 0) { if (count > 0) digest.update(buffer, 0, count); count = input.read(buffer) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun export(context: Context, e: GuardEvent): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "jingqi-${e.id}.zip")
        val attachments = attachments(e)
        val digests = attachments.joinToString("\n") { f -> "${f.name}: ${sha256(f)}" }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("report.txt")); zip.write((text(context, e) + "\n\nSHA-256（完整性校验，非时间戳认证）\n" + digests).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            for (attachment in attachments) {
                zip.putNextEntry(ZipEntry(attachment.name)); attachment.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(context.contentResolver, "净启报告", uri)
        context.startActivity(Intent.createChooser(intent, "分享前请检查截图中的个人信息"))
    }
}
