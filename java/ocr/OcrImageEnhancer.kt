package com.hnu.pickupassistant.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 识图前图像预处理（吸收自 `Ocr Pro Module China` §3.2 OcrImageEnhancer）。
 *
 * ## 为什么需要
 * 真实取件截图里取件码有三种颜色：菜鸟/支付宝的**纯黑字**、拼多多妈妈驿站的
 * **翠绿字 `#389E0D`**、菜鸟的**橙红字**。普通「去绿 / 简单灰度」会把绿字压浅、消失。
 * 这里用 `min(R,G,B)` 暗通道加权：彩色饱和字的 min 通道都很低，和黑字一起被压深，
 * 白色 / 浅灰卡片底则被推向纯白，OCR 对比度显著提升。
 *
 * 另外做两件事：
 *  - **高清解码**：保持横向分辨率 ≥ 1200px，保证 `JT5544316072358` 这类小字号单号清晰；
 *  - **状态栏屏蔽**：完整竖屏截图顶部 3.8% 的系统时间 / 电量文字常被误识别成号码，
 *    涂白后再送 OCR（局部截图不裁，避免截掉驿站组头）。
 *
 * ## 刻意不做
 * 资料里的「长图 2000px 分片 + 320px 重叠」本轮不接：分片后需要把每片的
 * boundingBox 坐标加 offsetY、并对重叠区重复行去重，任何一处没对齐都会让
 * 多驿站区块切分错乱。这里改为**像素总量上限保护**（超大图跳过增强、只保留高清解码），
 * 既不 OOM 也不改变现有单图解析链路。
 *
 * @author 焱川 (by 焱川)
 */
object OcrImageEnhancer {

    /** OCR 友好的最小横向分辨率 */
    const val MIN_OCR_WIDTH = 1200

    /** 超过这个像素量就不做逐像素增强，避免 IntArray 占用过大导致 OOM */
    private const val MAX_ENHANCE_PIXELS = 6_000_000L

    /** 完整竖屏截图才屏蔽状态栏 */
    private const val FULLSCREEN_RATIO = 1.7f
    private const val STATUS_BAR_RATIO = 0.038f
    private const val STATUS_BAR_MAX_PX = 120f

    /**
     * 暗通道加权 + 分段对比度拉伸，返回灰度值 0~255。
     * **纯函数，不依赖 Android**，用于 JVM 单测锁死「绿字 / 橙字 / 黑字都被压深、白底保持白」。
     */
    fun enhanceLuma(r: Int, g: Int, b: Int): Int {
        val minChannel = min(r, min(g, b))
        val avgChannel = (r + g + b) / 3
        // 彩色饱和字：min 通道很低 → darkScore 低 → 压黑；白底三通道都高 → 推白
        val darkScore = (minChannel * 65 + avgChannel * 35) / 100
        return when {
            darkScore >= 220 -> 255
            darkScore <= 85 -> (darkScore * 0.4f).toInt()
            else -> ((darkScore - 85) * 220 / 135 + 34).coerceIn(0, 255)
        }
    }

    /** 组装 ARGB 灰度像素（避免引用 android.graphics.Color，便于纯 JVM 复用） */
    fun grayPixel(value: Int): Int =
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    /**
     * 从相册 URI 解码出「可用于 OCR」的 Bitmap：
     * 高清解码 → （可选）放大 → 状态栏屏蔽 → 暗通道增强。
     * 任一步失败都返回 null，调用方回退到 `InputImage.fromFilePath` 原始路径。
     *
     * 返回的 Bitmap 由调用方在识别回调结束后 recycle。
     */
    fun decodeEnhanced(context: Context, uri: Uri): Bitmap? {
        return try {
            val app = context.applicationContext
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 采样时保证横向分辨率仍 >= MIN_OCR_WIDTH
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MIN_OCR_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // 低分辨率截图放大到 1350 宽，小字单号才够清晰；高度封顶避免超长图爆内存
        val scaled = if (decoded.width < 1080) {
            val scale = 1350f / decoded.width.toFloat()
            val targetW = (decoded.width * scale).roundToInt()
            val targetH = (decoded.height * scale).roundToInt().coerceAtMost(8000)
            val up = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            if (up != decoded) decoded.recycle()
            up
        } else {
            decoded
        }

        // 只对完整竖屏截图涂白状态栏；多驿站局部截图保持原样
        if (scaled.height.toFloat() / scaled.width.toFloat() >= FULLSCREEN_RATIO) {
            maskTopStatusBar(scaled)
        }
            applyDarkChannelEnhance(scaled)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 逐像素增强。超大图或任何异常都原样返回 source（高清解码成果仍保留）。
     * 成功时原 source 会被 recycle，返回的是新 Bitmap。
     */
    private fun applyDarkChannelEnhance(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w.toLong() * h > MAX_ENHANCE_PIXELS) return source
        return try {
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                pixels[i] = grayPixel(enhanceLuma(r, g, b))
            }
            val out = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            source.recycle()
            out
        } catch (t: Throwable) {
            source
        }
    }

    /** 用纯白涂掉顶部状态栏（时间 / 电量 / 信号图标） */
    private fun maskTopStatusBar(bitmap: Bitmap) {
        val heightPx = (bitmap.height * STATUS_BAR_RATIO).coerceAtMost(STATUS_BAR_MAX_PX)
        val paint = Paint().apply {
            color = grayPixel(255)
            style = Paint.Style.FILL
        }
        Canvas(bitmap).drawRect(0f, 0f, bitmap.width.toFloat(), heightPx, paint)
    }

    /**
     * 「看见没」抓屏入口专用：对**调用方持有的 Bitmap** 做增强。
     *
     * 与 [decodeEnhanced] 的区别：绝不 recycle 传入的 source（屏幕截图由
     * MediaProjection / Shizuku 侧持有），只在副本上做状态栏屏蔽与暗通道增强。
     * 返回的 Bitmap 若 !== source，由调用方在 OCR 回调结束后 recycle。
     */
    fun enhanceForOcr(source: Bitmap): Bitmap {
        if (source.isRecycled) return source
        return try {
            val copy = source.copy(Bitmap.Config.ARGB_8888, false)
            if (copy.height.toFloat() / copy.width.toFloat() >= FULLSCREEN_RATIO) {
                maskTopStatusBar(copy)
            }
            applyDarkChannelEnhance(copy)
        } catch (t: Throwable) {
            source
        }
    }

    /**
     * 长截图分片（参考资料 §3.2）：每片 2000px、相邻片重叠 320px。
     * 返回的**每一片都是新副本**，调用方用完后统一 recycle；
     * 只有一片时返回 `emptyList()`，表示不需要走分片兜底。
     */
    fun buildTiles(source: Bitmap, maxTileH: Int = 2000, overlap: Int = 320): List<Bitmap> {
        if (source.isRecycled || maxTileH <= overlap) return emptyList()
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return emptyList()
        if (h <= maxTileH + 200) return emptyList()

        val tiles = mutableListOf<Bitmap>()
        var y = 0
        while (y < h) {
            val tileH = minOf(maxTileH, h - y)
            try {
                tiles.add(Bitmap.createBitmap(source, 0, y, w, tileH))
            } catch (t: Throwable) {
                tiles.forEach { runCatching { it.recycle() } }
                return emptyList()
            }
            if (y + tileH >= h) break
            y += maxTileH - overlap
        }
        return tiles
    }
}
