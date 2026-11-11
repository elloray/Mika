package com.wangzan101.mika.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 浏览器下载列表里的一项:[status] 是系统 DownloadManager 的状态码。 */
data class BrowserDownload(
    val id: Long,
    val title: String,
    val status: Int,
    val soFar: Long,
    val total: Long,
    val mime: String?,
    val reason: Int,
    val updatedAt: Long = 0,
) {
    /** 还在排队、下载或暂停。 */
    val running: Boolean
        get() = status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
    val done: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val failed: Boolean get() = status == DownloadManager.STATUS_FAILED

    /** 知道总大小时的进度百分比。 */
    val percent: Int?
        get() = if (running && total > 0) ((soFar * 100) / total).toInt().coerceIn(0, 100) else null
}

/**
 * 用户浏览器里的下载:匿名请求交给系统 [DownloadManager]，需要 Cookie 的请求由应用下载到系统「下载」目录。
 * 下载的链接只收 http/https(blob:、data: 这类网页自己生成的链接系统下载不了,开始失败)。
 * User-Agent 从标签带过去；Cookie 只随同源请求发送，交给系统的下载不带登录态。
 * 只列浏览器自己排队的下载(记下 id),不会把关于页的更新包等别的下载混进来。
 */
class BrowserDownloads(context: Context, dir: File) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)
    private val ids = DownloadIds(File(dir, IDS_FILE))
    private val authenticated by lazy { AuthenticatedBrowserDownloads(app) }

    /** 开始下载;返回保存的文件名。链接不支持、开始失败或系统没有下载服务时为 null。 */
    suspend fun enqueue(url: String, userAgent: String, contentDisposition: String?, mime: String?): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val suggested = DownloadNames.sanitize(URLUtil.guessFileName(url, contentDisposition, mime))
        val needsCookies = withContext(Dispatchers.Main.immediate) {
            runCatching { !WebTab.cookieManager().getCookie(url).isNullOrBlank() }.getOrNull()
        } ?: return null
        if (needsCookies) return authenticated.enqueue(url, userAgent, suggested, mime)
        val m = manager ?: return null
        val name = DownloadNames.unique(suggested, existingNames(m))
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        mime?.takeIf { it.isNotBlank() }?.let(request::setMimeType)
        userAgent.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        val id = runCatching { m.enqueue(request) }.getOrNull() ?: return null
        ids.add(id)
        return name
    }

    /** 浏览器自己的下载,最新的在前,至多 [limit] 条。 */
    suspend fun list(limit: Int = LIMIT): List<BrowserDownload> = withContext(Dispatchers.IO) {
        val secured = authenticated.list()
        val m = manager ?: return@withContext secured.sortedByDescending { it.updatedAt }.take(limit)
        val wanted = ids.all()
        if (wanted.isEmpty()) return@withContext secured.sortedByDescending { it.updatedAt }.take(limit)
        val system = runCatching {
            m.query(DownloadManager.Query().setFilterById(*wanted)).use { c ->
                val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val title = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val soFar = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val total = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val mime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                val reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val modified = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                val out = ArrayList<BrowserDownload>()
                while (c.moveToNext()) {
                    out += BrowserDownload(
                        id = c.getLong(id),
                        title = c.getString(title).orEmpty().ifBlank { DOWNLOAD },
                        status = c.getInt(status),
                        soFar = c.getLong(soFar).coerceAtLeast(0),
                        total = c.getLong(total),
                        mime = c.getString(mime),
                        reason = c.getInt(reason),
                        updatedAt = c.getLong(modified),
                    )
                }
                ids.pruneMissing(wanted, out.map { it.id }.toSet())
                out.sortedByDescending { it.id }.take(limit)
            }
        }.getOrDefault(emptyList())
        (system + secured).sortedByDescending { it.updatedAt }.take(limit)
    }

    /** 取消下载或删掉记录(系统会把已下载的文件一起删掉)。 */
    fun remove(id: Long) {
        if (id < 0) return authenticated.remove(id)
        runCatching { manager?.remove(id) }
        ids.remove(id)
    }

    /** 已下载文件的 content:// 地址;没有完成时为 null。 */
    fun contentUri(id: Long): Uri? =
        if (id < 0) authenticated.uri(id) else runCatching { manager?.getUriForDownloadedFile(id) }.getOrNull()

    private fun existingNames(m: DownloadManager): Set<String> {
        val names = HashSet<String>()
        runCatching {
            m.query(DownloadManager.Query()).use { c ->
                val i = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                if (i >= 0) while (c.moveToNext()) c.getString(i)?.let(names::add)
            }
        }
        return names
    }

    companion object {
        const val LIMIT = 50
        private const val IDS_FILE = "browser_download_ids.txt"
        private const val DOWNLOAD = "download"
    }
}

/** 浏览器自己排队的下载 id,只列这些,不会混进更新包等别的下载。存在 App 私有目录的一个小文件里。 */
internal class DownloadIds(private val file: File, private val limit: Int = 200) {
    private val ids = LinkedHashSet<Long>()

    init {
        runCatching {
            if (file.isFile) file.readLines().mapNotNull { it.trim().toLongOrNull() }.forEach { ids += it }
        }
    }

    @Synchronized
    fun add(id: Long) {
        ids += id
        while (ids.size > limit) ids.remove(ids.first())
        save()
    }

    @Synchronized
    fun remove(id: Long) {
        if (ids.remove(id)) save()
    }

    @Synchronized
    fun pruneMissing(queried: LongArray, found: Set<Long>) {
        if (ids.removeAll(queried.filterNot(found::contains).toSet())) save()
    }

    @Synchronized
    fun all(): LongArray = ids.toLongArray()

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            // 先写临时文件再改名,写到一半被杀不会留下半个文件
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(ids.joinToString("\n"))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}

/** 下载文件名:去掉路径分隔符和系统不认的字符,太长时截短(保留扩展名);重名时加「 (2)」。 */
object DownloadNames {
    private const val MAX = 100
    private const val BAD = "/\\:*?\"<>|"

    fun sanitize(raw: String): String {
        val cleaned = raw.map { if (it.isISOControl() || it in BAD) '_' else it }.joinToString("").trim().trimEnd('.')
        val name = cleaned.ifEmpty { "download" }
        return truncate(name)
    }

    fun unique(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length)
        var i = 2
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }

    private fun truncate(name: String): String {
        if (name.length <= MAX) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot in 1 until name.length && name.length - dot <= 10) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length)
        return base.take(MAX - ext.length) + ext
    }
}
