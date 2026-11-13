package com.hnu.pickupassistant.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hnu.pickupassistant.CaptureActivity
import com.hnu.pickupassistant.R
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.util.OcrHelper
import com.hnu.pickupassistant.util.ShizukuHelper

/**
 * 「看见没」的截屏执行体。
 *
 * Android 14 起 MediaProjection 必须在 foregroundServiceType="mediaProjection" 的前台服务里使用，
 * 且必须先 startForeground 再 createVirtualDisplay，因此这里在服务内抓一帧后即释放投屏，
 * 再用本地 ML Kit 模型做 OCR，全程不联网、不保存截图。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_USE_SHIZUKU = "extra_use_shizuku"
        private const val CHANNEL_ID = "see_it_capture"
        private const val NOTIFICATION_ID = 1001
        private const val CAPTURE_TIMEOUT_MS = 10_000L

        /** 走 MediaProjection 授权流程（带 resultCode/data） */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 走 Shizuku 免弹窗抓屏（无需授权数据） */
        fun startWithShizuku(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_USE_SHIZUKU, true)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LocalStorageManager.init(applicationContext)
        startForegroundNotification()

        // 优先 Shizuku 免弹窗抓屏
        val useShizuku = intent?.getBooleanExtra(EXTRA_USE_SHIZUKU, false) == true
        if (useShizuku) {
            startShizukuCapture()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            toast("未获得截屏授权")
            finishNow()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            // Android 14 (API 34) 起，createVirtualDisplay 之前必须先注册 MediaProjection.Callback，
            // 否则直接抛 "Must register a callback before starting the capture session"。
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // 用户在系统投屏管理里撤销授权时兜底收尾
                    mainHandler.post {
                        if (!finished) {
                            toast("截屏授权已撤销")
                            finishNow()
                        }
                    }
                }
            }
            projectionCallback = callback
            projection?.registerCallback(callback, mainHandler)
            startCapture()
            mainHandler.postDelayed({
                if (!finished) {
                    toast("屏幕识别超时，请重试")
                    finishNow()
                }
            }, CAPTURE_TIMEOUT_MS)
        } catch (e: Throwable) {
            toast("屏幕识别启动失败：${e.localizedMessage ?: "系统限制"}")
            finishNow()
        }
        return START_NOT_STICKY
    }

    /** Shizuku 免弹窗抓屏：后台线程抓一帧 → OCR。失败仅提示，不再回退到系统录屏授权（避免 app 跳转与弹窗） */
    private fun startShizukuCapture() {
        val thread = HandlerThread("see-it-shizuku").apply { start() }
        Handler(thread.looper).post {
            val bitmap = ShizukuHelper.captureScreen()
            mainHandler.post {
                if (bitmap == null) {
                    toast("Shizuku 抓屏失败：请确认 Shizuku 已启动并在其「已授权应用」里允许了「取了吗」；若已确认仍失败，请把系统日志中 CaptureService 的报错发我排查")
                    finishNow()
                } else {
                    recognize(bitmap)
                }
            }
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕识别",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "看见没：识别当前屏幕内容" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_see)
            .setContentTitle("看见没")
            .setContentText("正在识别当前屏幕内容…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val thread = HandlerThread("see-it-capture").apply { start() }
        captureThread = thread
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            if (finished) return@setOnImageAvailableListener
            val image = try {
                r.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener

            val bitmap = try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                buffer.rewind()
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val raw = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                raw.copyPixelsFromBuffer(buffer)
                Bitmap.createBitmap(raw, 0, 0, width, height)
            } catch (_: Throwable) {
                null
            } finally {
                image.close()
            }

            // 只取一帧，立刻停止投屏；OCR 结果回来后再结束服务
            mainHandler.post {
                releaseProjection()
                if (bitmap == null) {
                    toast("截屏画面读取失败")
                    finishNow()
                } else {
                    recognize(bitmap)
                }
            }
        }, Handler(thread.looper))

        virtualDisplay = projection?.createVirtualDisplay(
            "SeeItCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            Handler(thread.looper)
        )
    }

    private fun recognize(bitmap: Bitmap) {
        // 裁掉顶部状态栏与底部导航栏后再 OCR，避免时钟/电量/手势条被识别成乱码取件码
        val cropped = cropSystemBars(bitmap)
        OcrHelper.recognizeScreenAndImport(cropped) { outcome ->
            val message = when {
                outcome.codes.added > 0 -> "看见没：已导入 ${outcome.codes.added} 个取件码"
                outcome.codes.duplicates > 0 -> "看见没：画面里的取件码都已在列表中"
                outcome.todoAdded -> "看见没：已添加一条待办"
                outcome.rawText.isBlank() -> "看见没：没有识别到文字"
                else -> "看见没：画面里没有新的取件码或待办"
            }
            toast(message)
            mainHandler.post { finishNow() }
        }
    }

    private fun releaseProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null
        try {
            val callback = projectionCallback
            if (callback != null) {
                projection?.unregisterCallback(callback)
            }
        } catch (_: Throwable) {
        }
        projectionCallback = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
    }

    private fun finishNow() {
        if (finished) return
        finished = true
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 裁掉顶部状态栏与底部导航栏（高度取系统值，失败则按屏幕 6% 兜底），减少 OCR 噪声 */
    private fun cropSystemBars(src: Bitmap): Bitmap {
        val top = getSystemBarHeightPx("status_bar_height")
            .coerceAtMost((src.height * 0.06f).toInt())
            .coerceAtLeast(0)
        val bottom = getSystemBarHeightPx("navigation_bar_height")
            .coerceAtMost((src.height * 0.06f).toInt())
            .coerceAtLeast(0)
        val w = src.width
        val h = src.height
        if (top + bottom >= h) return src
        return try {
            Bitmap.createBitmap(src, 0, top, w, h - top - bottom)
        } catch (_: Throwable) {
            src
        }
    }

    private fun getSystemBarHeightPx(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) {
            try {
                resources.getDimensionPixelSize(id)
            } catch (_: Throwable) {
                0
            }
        } else {
            0
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }
}
