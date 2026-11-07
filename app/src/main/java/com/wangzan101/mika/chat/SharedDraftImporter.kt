package com.wangzan101.mika.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.sandbox.Attachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/** 把系统分享的一张内容图片立即复制到私有草稿目录,不依赖之后仍有效的 URI 授权。 */
class SharedDraftImporter(private val context: Context, private val store: SharedDraftStore) {
    suspend fun image(text: String, uri: Uri, declaredType: String?): SharedDraft? {
        var copied: File? = null
        return try {
            withContext(Dispatchers.IO) {
                if (text.length > SharedDraftStore.MAX_TEXT_CHARS || declaredType?.startsWith("image/") != true) return@withContext null
                if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
                // 外部分享不应该借 Mika 自己的私有 provider 指定另一个内部文件;所有收到的内容都从外部 provider 新复制。
                val authority = uri.authority.orEmpty()
                if (authority == context.packageName || authority.startsWith("${context.packageName}.")) return@withContext null
                val resolver = context.contentResolver
                val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: declaredType
                if (!mime.startsWith("image/")) return@withContext null
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() } ?: "img"
                var display: String? = null
                var declaredSize = -1L
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            display = c.getString(0)
                            if (!c.isNull(1)) declaredSize = c.getLong(1)
                        }
                    }
                }
                if (declaredSize > Attachments.MAX_BYTES) return@withContext null
                val name = Attachments.safeName(display ?: "image.$extension")
                val fileName = "${UUID.randomUUID()}.$extension"
                val final = store.imageFile(fileName) ?: return@withContext null
                copied = final
                val tmp = store.imageFile("$fileName.tmp") ?: return@withContext null
                val size = try {
                    resolver.openInputStream(uri)?.use { input ->
                        // 目标文件尚未创建,查它的可用空间会得到 0;copy 会先建好父目录。
                        SharedDraftCopy.copy(input, tmp, final) { tmp.parentFile?.usableSpace ?: 0L }
                    } ?: return@withContext null
                } catch (e: CancellationException) {
                    tmp.delete()
                    final.delete()
                    throw e
                } catch (e: Exception) {
                    tmp.delete()
                    final.delete()
                    return@withContext null
                }
                // 类型声明可以被伪造;只收系统能识别出边界的位图,不在界面线程解全图。
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val readable = try {
                    final.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                    bounds.outWidth > 0 && bounds.outHeight > 0
                } catch (e: Exception) {
                    false
                }
                if (!readable) {
                    final.delete()
                    return@withContext null
                }
                SharedDraft("", text, SharedDraftImage(fileName, name, size))
            }
        } catch (e: CancellationException) {
            // withContext 在复制刚完成、结果尚未交给界面的那一刻也可能被取消;这份副本还没绑到草稿,不能留下。
            copied?.delete()
            throw e
        } catch (e: Exception) {
            copied?.delete()
            null
        }
    }
}

/** 有界复制:每个块都检查取消和空间;失败或取消时不留下半份临时文件。 */
internal object SharedDraftCopy {
    suspend fun copy(
        input: InputStream,
        tmp: File,
        final: File,
        maxBytes: Long = Attachments.MAX_BYTES,
        minFree: Long = Attachments.MIN_FREE,
        freeBytes: () -> Long,
    ): Long {
        try {
            tmp.parentFile?.mkdirs()
            var size = 0L
            tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (freeBytes() < minFree) throw IllegalStateException("storage low")
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > maxBytes) throw IllegalStateException("too large")
                    output.write(buffer, 0, n)
                }
            }
            if (!tmp.renameTo(final)) throw IllegalStateException("cannot finish")
            return size
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
