package com.wangzan101.mika.browser

import android.app.DownloadManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.core.net.NetProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Downloads carrying WebView credentials need control of the headers on each HTTP hop. */
internal class AuthenticatedBrowserDownloads(context: Context) {
    private val resolver = context.contentResolver
    private val packageName = context.packageName
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = browserDownloadClient(NetProxy.shared.client()) { url ->
        runBlocking(Dispatchers.Main.immediate) { WebTab.cookieManager().getCookie(url) }
    }

    private class Transfer(val uri: Uri, val call: Call, initial: BrowserDownload) {
        @Volatile var item = initial
    }

    private val transfers = ConcurrentHashMap<Long, Transfer>()

    // The first use in this process runs before any transfer creates a pending row.
    private val cleanInterrupted by lazy {
        runCatching {
            resolver.query(collection, arrayOf(MediaColumns._ID), queryArgs(pending = true), null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }.orEmpty().forEach { delete(ContentUris.withAppendedId(collection, it)) }
        }
    }

    fun enqueue(url: String, userAgent: String, name: String, mime: String?): String? = runCatching {
        cleanInterrupted
        val call = client.newCall(Request.Builder().url(url).apply {
            if (userAgent.isNotBlank()) header("User-Agent", userAgent)
        }.build())
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, name)
            put(MediaColumns.RELATIVE_PATH, DIRECTORY)
            put(MediaColumns.IS_PENDING, 1)
            mime?.takeIf { it.isNotBlank() }?.let { put(MediaColumns.MIME_TYPE, it) }
        }) ?: return null
        // Negative IDs keep MediaStore rows separate from the system download IDs.
        val id = -ContentUris.parseId(uri)
        val initial = BrowserDownload(id, name, DownloadManager.STATUS_RUNNING, 0, -1, mime, 0,
            updatedAt = System.currentTimeMillis())
        val transfer = Transfer(uri, call, initial)
        transfers[id] = transfer
        scope.launch { download(transfer) }
        name
    }.getOrNull()

    fun list(): List<BrowserDownload> {
        cleanInterrupted
        val active = transfers.values.map { it.item }
        val completed = runCatching {
            resolver.query(collection, COLUMNS, queryArgs(pending = false), null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val bytes = cursor.getLong(2)
                        add(BrowserDownload(-cursor.getLong(0), cursor.getString(1), DownloadManager.STATUS_SUCCESSFUL,
                            bytes, bytes, cursor.getString(3), 0, updatedAt = cursor.getLong(4) * 1_000))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
        return (completed + active).distinctBy { it.id }
    }

    fun remove(id: Long) {
        val transfer = transfers[id]
        if (transfer == null) {
            delete(uri(id))
        } else synchronized(transfer) {
            transfer.call.cancel()
            transfers.remove(id, transfer)
            delete(transfer.uri)
        }
    }

    fun uri(id: Long): Uri = ContentUris.withAppendedId(collection, -id)

    private fun download(transfer: Transfer) {
        val id = transfer.item.id
        try {
            transfer.call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) throw IOException("Download HTTP ${response.code}")
                transfer.item = transfer.item.copy(total = body.contentLength())
                resolver.openOutputStream(transfer.uri)?.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            transfer.item = transfer.item.copy(soFar = transfer.item.soFar + count)
                        }
                    }
                } ?: throw IOException("Cannot open download")
            }
            synchronized(transfer) {
                if (transfer.call.isCanceled()) return
                val published = resolver.update(transfer.uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
                if (published != 1) throw IOException("Cannot publish download")
                transfers.remove(id, transfer)
            }
        } catch (_: Exception) {
            synchronized(transfer) {
                if (transfers[id] !== transfer) return
                delete(transfer.uri)
                transfer.item = transfer.item.copy(status = DownloadManager.STATUS_FAILED,
                    reason = DownloadManager.ERROR_HTTP_DATA_ERROR)
                transfers.values.filter { it.item.failed }.sortedByDescending { it.item.updatedAt }
                    .drop(BrowserDownloads.LIMIT).forEach { transfers.remove(it.item.id, it) }
            }
        }
    }

    private fun delete(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun queryArgs(pending: Boolean) = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
            "${MediaColumns.RELATIVE_PATH}=? AND ${MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaColumns.IS_PENDING}=?")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(DIRECTORY, packageName, if (pending) "1" else "0"))
        if (pending) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }

    private companion object {
        val DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/Mika Browser/"
        val COLUMNS = arrayOf(MediaColumns._ID, MediaColumns.DISPLAY_NAME, MediaColumns.SIZE, MediaColumns.MIME_TYPE, MediaColumns.DATE_ADDED)
    }
}
