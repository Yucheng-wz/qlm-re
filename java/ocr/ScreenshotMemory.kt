package com.hnu.pickupassistant.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 「取了吗」【截图对照】—— 原图 Bitmap / 浏览状态缓存 + 生命周期释放（单一真源）。
 *
 * 生命周期契约：
 *  ① 编辑状态常驻内存：Bitmap 存本单例，反复开关预览不重新解码，
 *     第二次 [attach] 命中缓存并累加 cacheHits（fromCache=true 可演示）。
 *  ② 状态记忆：[remember] 保存缩放倍数 / 平移偏移 / 查看区域，[currentState] 恢复。
 *  ③ 保存成功释放：宿主保存写入成功后调用 [release]，回收 Bitmap、
 *     删除临时截图文件、清空所有缓存引用。
 *  ④ 取消关闭释放：点「取消」关闭整个识别弹窗时立即 [release]。
 *  ⑤ 兜底释放：弹窗消失的 DisposableEffect 与 Activity.onDestroy 都调用 [release]，
 *     重复调用幂等。
 *
 * 临时文件：从系统相册 URI 复制一份到应用专属 cacheDir/screenshots（不写媒体存储、
 * 不需要权限）；[release] 删除它。若曾通过 MediaStore 插入临时图，可用
 * [registerMediaUri] 登记，[release] 会一并 ContentResolver.delete 删除。
 *
 * 异常降级：URI 失效、读取/解码失败、OOM 全部捕获（含 OutOfMemoryError 这一 Error），
 * 返回 [LoadResult.Failure]，由预览页展示占位提示，绝不崩溃。
 *
 * @author 焱川 (by 焱川)
 */
object ScreenshotMemory {

    private const val TAG = "QlmScreenshot"
    private const val MAX_DECODE_SIDE = 2048
    private const val MAX_OOM_RETRY = 3

    /** 关闭预览后要恢复的浏览状态：缩放倍数、平移偏移（即查看区域） */
    data class ViewState(
        val scale: Float = 1f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f
    )

    sealed interface LoadResult {
        /** @param fromCache true = 命中内存缓存，未重新解码 */
        data class Ready(
            val bitmap: Bitmap,
            val fromCache: Boolean,
            val cacheHits: Int
        ) : LoadResult
        data class Failure(val reason: String) : LoadResult
    }

    private var bitmap: Bitmap? = null
    private var tempFile: File? = null
    private var sourceUri: Uri? = null
    private var mediaUri: Uri? = null
    private var viewState = ViewState()
    private var cacheHits = 0

    /** 载入（或命中缓存）原图；同一 URI 且 Bitmap 仍可用时直接返回缓存，不触碰磁盘 */
    @Synchronized
    fun attach(context: Context, uri: Uri): LoadResult {
        val cached = bitmap
        if (cached != null && !cached.isRecycled && sourceUri == uri) {
            cacheHits++
            Log.d(TAG, "命中截图缓存（第 $cacheHits 次），跳过解码")
            return LoadResult.Ready(cached, fromCache = true, cacheHits = cacheHits)
        }

        release(context, "attach-new-image")

        val app = context.applicationContext
        return try {
            val dir = File(app.cacheDir, "screenshots").apply { mkdirs() }
            val target = File(dir, "qlm_ocr_preview_${System.currentTimeMillis()}.jpg")

            val stream = app.contentResolver.openInputStream(uri)
                ?: return LoadResult.Failure("原图 URI 已失效，请重新选择截图")
            stream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            tempFile = target
            sourceUri = uri

            // 先只解码边界算 inSampleSize，避免整张大图直接进内存
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_DECODE_SIDE ||
                bounds.outHeight / sample > MAX_DECODE_SIDE
            ) {
                sample *= 2
            }

            var attempts = 0
            while (attempts <= MAX_OOM_RETRY) {
                try {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val decoded = BitmapFactory.decodeFile(target.absolutePath, options)
                    if (decoded == null) {
                        release(app, "decode-null")
                        return LoadResult.Failure("图片解码失败，请重新选择截图")
                    }
                    bitmap = decoded
                    Log.d(
                        TAG,
                        "截图已载入：${decoded.width}x${decoded.height}，sample=$sample，" +
                            "临时文件=${target.name}"
                    )
                    return LoadResult.Ready(decoded, fromCache = false, cacheHits = cacheHits)
                } catch (oom: OutOfMemoryError) {
                    attempts++
                    if (attempts > MAX_OOM_RETRY) {
                        release(app, "oom")
                        return LoadResult.Failure("图片过大，内存不足，已停止加载")
                    }
                    sample *= 2
                } catch (t: Throwable) {
                    Log.e(TAG, "截图解码异常：${t.message}", t)
                    release(app, "decode-error")
                    return LoadResult.Failure("图片加载失败：${t.message ?: "未知错误"}")
                }
            }
            release(app, "oom-exhausted")
            LoadResult.Failure("图片过大，内存不足，已停止加载")
        } catch (t: Throwable) {
            Log.e(TAG, "截图载入异常：${t.message}", t)
            release(app, "attach-error")
            LoadResult.Failure("图片读取失败：${t.message ?: "URI 已失效"}")
        }
    }

    /** 登记一个写入过媒体存储的临时图 URI，释放时一并删除 */
    @Synchronized
    fun registerMediaUri(uri: Uri) {
        mediaUri = uri
    }

    /** 当前内存中的 Bitmap（可能已被回收 / 释放） */
    @Synchronized
    fun peek(): Bitmap? = bitmap?.takeIf { !it.isRecycled }

    /** 关闭预览时保存浏览状态（缩放 / 平移 / 查看区域） */
    @Synchronized
    fun remember(state: ViewState) {
        viewState = state
        Log.d(TAG, "已记忆浏览状态：scale=${state.scale}, offset=(${state.offsetX}, ${state.offsetY})")
    }

    /** 再次打开预览时恢复浏览状态 */
    @Synchronized
    fun currentState(): ViewState = viewState

    /** 缓存命中次数，用于演示「第二次打开命中缓存」 */
    @Synchronized
    fun cacheHitCount(): Int = cacheHits

    /**
     * 释放全部资源（幂等）：recycle Bitmap、删除临时截图、删除已登记的媒体存储 URI、
     * 清空 sourceUri / 浏览状态 / 命中计数。
     */
    @Synchronized
    fun release(context: Context, reason: String) {
        try {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "Bitmap 回收异常：${t.message}", t)
        }
        bitmap = null

        try {
            tempFile?.takeIf { it.exists() }?.delete()
        } catch (t: Throwable) {
            Log.e(TAG, "临时截图删除失败：${t.message}", t)
        }
        tempFile = null

        mediaUri?.let { uri ->
            try {
                context.applicationContext.contentResolver.delete(uri, null, null)
            } catch (t: Throwable) {
                Log.e(TAG, "媒体存储临时文件删除失败：${t.message}", t)
            }
        }
        mediaUri = null

        sourceUri = null
        viewState = ViewState()
        cacheHits = 0
        Log.d(TAG, "截图资源已释放（$reason）")
    }
}
