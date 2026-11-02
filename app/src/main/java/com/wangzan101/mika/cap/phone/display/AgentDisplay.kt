package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Agent 专用的虚拟屏。画面输出到本进程的 ImageReader,截图不依赖无障碍截图接口。
 * 帧处理限速到 ≤10 帧/秒:来帧时若离上次处理不足 100ms 就延后到期再取最新一帧,
 * 期间旧帧由 acquireLatestImage 丢弃。
 */
class AgentDisplay(
    private val shell: IMikaShell,
    width: Int,
    height: Int,
    private var dpi: Int,
) {
    @Volatile var width: Int = width
        private set
    @Volatile var height: Int = height
        private set
    private val thread = HandlerThread("agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    val displayId: Int

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** 实时画面:限速 ≤10 帧/秒,普通预览宽 ≤360，双栏调整虚拟屏后宽 ≤720。 */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // 带行填充的整帧,宽 = rowStride / pixelStride
    private var hasFrame = false
    private var lastProcessedAt = 0L
    private var pending = false
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var previewLimit = 360
    internal val previewWidth get() = minOf(previewLimit, width)
    internal val previewHeight get() = maxOf(1, height * previewWidth / width)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        val id = try {
            shell.createDisplay(reader.surface, width, height, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
    }

    /** 在帧线程上换接收器，保持同一 displayId 和应用任务；失败时继续使用旧画面。 */
    suspend fun resize(newWidth: Int, newHeight: Int, newDpi: Int): Boolean = kotlin.coroutines.suspendCoroutine { continuation ->
        if (released) { continuation.resumeWith(Result.success(false)); return@suspendCoroutine }
        val posted = handler.post {
            if (released) { continuation.resumeWith(Result.success(false)); return@post }
            if (width == newWidth && height == newHeight && dpi == newDpi) {
                continuation.resumeWith(Result.success(true)); return@post
            }
            var next: ImageReader? = null
            val resized = try {
                next = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 2)
                if (!shell.resizeDisplay(displayId, next.surface, newWidth, newHeight, newDpi)) false else {
                    reader.setOnImageAvailableListener(null, null)
                    reader.close()
                    synchronized(frameLock) {
                        previewLimit = 720
                        width = newWidth
                        height = newHeight
                        dpi = newDpi
                        fullFrame?.recycle()
                        fullFrame = null
                        hasFrame = false
                        _frames.value = null
                    }
                    reader = next
                    next = null
                    reader.setOnImageAvailableListener({ schedule() }, handler)
                    schedule()
                    true
                }
            } catch (_: Exception) { false }
            finally { next?.close() }
            continuation.resumeWith(Result.success(resized))
        }
        if (!posted) continuation.resumeWith(Result.success(false))
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                Canvas(preview).drawBitmap(bmp, Rect(0, 0, width, height), Rect(0, 0, previewWidth, previewHeight), paint)
                _frames.value = preview
            }
        } finally {
            image.close()
        }
    }

    /** 最新一帧的整幅 PNG;还没收到画面时返回 null。 */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** OCR 使用原始尺寸的画面副本，避免 PNG 编码和解码；调用方负责 recycle。 */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacks(process)
        // 在帧线程上收尾,避免与正在处理的帧并发
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 100L
    }
}
