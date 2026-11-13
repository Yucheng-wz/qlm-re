package com.hnu.pickupassistant.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 本地图片安全解码：先只读边界算 inSampleSize，再按采样解码，
 * 避免「出库码静态大图 / 用户选的高清截图」一次性全尺寸进内存导致 OOM。
 *
 * 用于 `FullBrightnessStaticCodeDialog`（全屏高亮展示出库码）与
 * `FloatingWindowService`（悬浮窗里的出库码图），两处此前都是
 * `BitmapFactory.decodeFile(path)` 无采样直解。
 *
 * @author 焱川 (by 焱川)
 */
object BitmapDecodeHelper {

    /** 解码后允许的最大像素量（约 4MP，RGB_565 下约 8MB） */
    const val MAX_PIXELS: Long = 4_000_000L

    /** 单边最大像素，避免超长截图被拉成极细长条仍占满内存 */
    const val MAX_EDGE: Int = 2560

    /** OOM 时逐级降采样重试次数 */
    private const val MAX_OOM_RETRY = 3

    /**
     * 纯函数：按 2 的幂计算 inSampleSize，保证解码后的像素量与单边都不超上限。
     * 可 JVM 单测。
     */
    fun sampleSize(
        width: Int,
        height: Int,
        maxPixels: Long = MAX_PIXELS,
        maxEdge: Int = MAX_EDGE
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxEdge ||
            height / sample > maxEdge ||
            (width.toLong() / sample) * (height / sample) > maxPixels
        ) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    /**
     * 带采样 + OOM 重试的文件解码。
     * 使用 RGB_565：出库码 / 条码是黑白图，视觉无损但内存比 ARGB_8888 省一半。
     * 文件不存在、解码失败、OOM 重试耗尽都返回 null，绝不抛异常。
     */
    fun decodeSampledFile(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(path, bounds)
        } catch (t: Throwable) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = sampleSize(bounds.outWidth, bounds.outHeight)
        var attempts = 0
        while (attempts <= MAX_OOM_RETRY) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            try {
                return BitmapFactory.decodeFile(path, opts)
            } catch (oom: OutOfMemoryError) {
                attempts++
                sample *= 2
            } catch (t: Throwable) {
                return null
            }
        }
        return null
    }
}
