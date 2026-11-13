package com.hnu.pickupassistant.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.Log
import android.view.ViewParent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.hnu.pickupassistant.util.LiftedToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「取了吗」识图取件码 —— 相册图片选择工具 + 识别结果总线。
 *
 * 权限：使用系统相册选择器 `GetContent()`，由系统相册代为授权用户选中的单张图片，
 * App 自身不需要读取相册权限（Android 13+ 官方推荐做法）。
 *
 * 【截图对照】接入点：
 *  - 识别成功后把原图 [Uri] 放进 [State.Show]，供预览页使用；
 *  - 同时在 IO 线程预热 [ScreenshotMemory]，让编辑期间 Bitmap 已常驻内存，
 *    第一次打开预览即命中缓存，反复开关不会重复解码；
 *  - 识别失败 / 无取件码时立即释放可能残留的截图资源。
 *
 * 识别仍用工程已有的 ML Kit 中文 bundled 离线模型，不替换原有 OCR 能力；
 * 本次只在送进 OCR 之前加一层 [OcrImageEnhancer] 图像预处理（高清解码 +
 * 暗通道增强 + 状态栏屏蔽），并用「原图二次识别」做兜底，识图失败会自动回退原路径。
 *
 * @author 焱川 (by 焱川)
 */
object OcrPreviewBus {

    private const val TAG = "QlmOcrPreview"

    /** 预览状态：空闲 / 无取件码 / 展示候选列表（携带原图 URI） */
    sealed interface State {
        object Idle : State
        object Empty : State
        data class Show(
            val items: List<ParcelTextParser.ParcelCandidate>,
            val imageUri: Uri? = null
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 识别成功且有取件码 → 展示可编辑预览列表 */
    fun show(items: List<ParcelTextParser.ParcelCandidate>, imageUri: Uri? = null) {
        _state.value = State.Show(items, imageUri)
    }

    /** 整张图找不到取件码 / 读取或识别失败 → 展示提示弹窗 */
    fun empty() {
        _state.value = State.Empty
    }

    /** 关闭弹窗（取消 / 保存完成 / 知道了）；资源释放由宿主统一负责 */
    fun dismiss() {
        _state.value = State.Idle
    }

    /**
     * 对选中的截图做离线 OCR → 坐标排序 → 区块解析 → 发到预览总线。
     *
     * 本次增强（吸收 `Ocr Pro Module China` §3.2）：
     *  - 主路用 [OcrImageEnhancer] 做高清解码 + 暗通道增强（黑 / 绿 / 橙字通吃）+ 状态栏屏蔽；
     *  - 增强图没认出取件码时，用**原图**再识别一次兜底（增强可能改变个别背景配色）；
     *  - 任一步异常都回退到原来的 `InputImage.fromFilePath` 路径，绝不让识图功能失效；
     *  - 【截图对照】仍用用户选中的原图 URI，增强只作用于 OCR 输入，不影响预览与缓存；
     *  - 识别结束后 recycle 增强 Bitmap，避免内存泄漏。
     *
     * 全流程只在本模块内，不触碰原有 OcrHelper / 原有识图入口。
     */
    fun recognize(context: Context, uri: Uri) {
        LiftedToast.show("正在识别图片中的取件码…")
        val app = context.applicationContext

        var enhanced: Bitmap? = null
        val firstImage: InputImage? = try {
            enhanced = OcrImageEnhancer.decodeEnhanced(app, uri)
            if (enhanced != null) InputImage.fromBitmap(enhanced, 0)
            else InputImage.fromFilePath(app, uri)
        } catch (e: Exception) {
            Log.w(TAG, "增强图不可用，回退原图识别：${e.message}")
            runCatching { enhanced?.recycle() }
            enhanced = null
            try {
                InputImage.fromFilePath(app, uri)
            } catch (e2: Exception) {
                Log.e(TAG, "无法读取图片：${e2.message}", e2)
                null
            }
        }
        if (firstImage == null) {
            ScreenshotMemory.release(app, "image-unreadable")
            empty()
            return
        }

        /** 统一收口：发布结果 + 释放增强 Bitmap，每条识别路径恰好调用一次 */
        fun publish(items: List<ParcelTextParser.ParcelCandidate>) {
            try {
                if (items.isEmpty()) {
                    ScreenshotMemory.release(app, "no-code")
                    empty()
                } else {
                    // 预热截图缓存：编辑期间原图 Bitmap 常驻，首次打开预览即命中缓存
                    CoroutineScope(Dispatchers.IO).launch {
                        ScreenshotMemory.attach(app, uri)
                    }
                    show(items, uri)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "发布识别结果失败：${t.message}", t)
                empty()
            } finally {
                enhanced?.let { if (!it.isRecycled) it.recycle() }
                enhanced = null
            }
        }

        runOcr(firstImage) { first ->
            val items = parseVisionText(first)
            // 快照成 val：避免被 publish 置空后影响分片兜底的智能转换
            val enhancedBase: Bitmap? = enhanced
            when {
                items.isNotEmpty() -> publish(items)
                // 增强图没认出码 → 原图二次识别 → 仍无结果才走长图分片兜底
                enhancedBase != null -> {
                    val raw = try {
                        InputImage.fromFilePath(app, uri)
                    } catch (e: Exception) {
                        Log.w(TAG, "原图兜底读取失败：${e.message}")
                        null
                    }
                    if (raw == null) {
                        recognizeTiled(enhancedBase) { publish(it) }
                    } else {
                        runOcr(raw) { second ->
                            val items2 = parseVisionText(second)
                            if (items2.isNotEmpty()) publish(items2)
                            else recognizeTiled(enhancedBase) { publish(it) }
                        }
                    }
                }
                else -> publish(emptyList())
            }
        }
    }

    /**
     * 长截图分片识别（只在「增强整图 + 原图」都没认出取件码时启用，正常路径零影响）。
     *
     * 每片 2000px、重叠 320px；逐片 OCR 后只合并**文本行顺序**，
     * 由 [OcrTextNormalizer.mergeTiledLines] 去掉重叠区的连续重复行，
     * 再走 `fromPlainText` 解析。这样不需要给 boundingBox 加 offsetY，
     * 也不会破坏现有单图解析链路。所有分片 Bitmap 用完即 recycle。
     */
    private fun recognizeTiled(
        base: Bitmap?,
        onDone: (List<ParcelTextParser.ParcelCandidate>) -> Unit
    ) {
        val tiles = if (base != null && !base.isRecycled) {
            OcrImageEnhancer.buildTiles(base)
        } else {
            emptyList()
        }
        if (tiles.size <= 1) {
            tiles.forEach { runCatching { it.recycle() } }
            onDone(emptyList())
            return
        }
        Log.d(TAG, "整图未识别到取件码，启用长图分片识别：${tiles.size} 片")

        val chunks = mutableListOf<List<String>>()
        fun releaseTiles() = tiles.forEach { runCatching { it.recycle() } }
        fun next(index: Int) {
            if (index >= tiles.size) {
                val merged = OcrTextNormalizer.mergeTiledLines(chunks)
                val result = if (merged.isEmpty()) {
                    emptyList()
                } else {
                    try {
                        ParcelTextParser.parse(
                            OcrTextSorter.fromPlainText(merged.joinToString("\n"))
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "分片结果解析失败：${t.message}", t)
                        emptyList()
                    }
                }
                releaseTiles()
                onDone(result)
                return
            }
            val image = try {
                InputImage.fromBitmap(tiles[index], 0)
            } catch (t: Throwable) {
                Log.w(TAG, "分片 $index 构造 InputImage 失败：${t.message}")
                null
            }
            if (image == null) {
                chunks.add(emptyList())
                next(index + 1)
                return
            }
            runOcr(image) { visionText ->
                try {
                    chunks.add(
                        visionText?.text?.lines()
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?: emptyList()
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "分片 $index 结果处理异常：${t.message}", t)
                    chunks.add(emptyList())
                }
                next(index + 1)
            }
        }
        next(0)
    }

    /** 单次 OCR：保证 recognizer 一定 close，失败返回 null 而不是把异常抛到主线程 */
    private fun runOcr(image: InputImage, onResult: (Text?) -> Unit) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            recognizer.process(image)
                .addOnSuccessListener { onResult(it) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "图片识别失败：${e.message}", e)
                    onResult(null)
                }
                .addOnCompleteListener { recognizer.close() }
        } catch (t: Throwable) {
            Log.e(TAG, "OCR 调用异常：${t.message}", t)
            recognizer.close()
            onResult(null)
        }
    }

    /** 视觉行 → 坐标排序 → 区块解析；解析异常静默返回空列表 */
    private fun parseVisionText(visionText: Text?): List<ParcelTextParser.ParcelCandidate> {
        if (visionText == null) return emptyList()
        return try {
            val sorted = OcrTextSorter.fromVisionText(visionText)
            val effective = if (sorted.rows.isEmpty() && visionText.text.isNotBlank()) {
                OcrTextSorter.fromPlainText(visionText.text)
            } else sorted
            ParcelTextParser.parse(effective)
        } catch (t: Throwable) {
            Log.e(TAG, "解析识别结果失败：${t.message}", t)
            emptyList()
        }
    }
}

/**
 * 首页【识图添加】按钮用的选图 launcher。
 * 选图成功后自动完成识别并弹出预览；识别完成不会自动唤起软键盘。
 */
@Composable
fun rememberOcrPreviewFlow(
    context: Context = LocalContext.current
) = rememberLauncherForActivityResult(contract = GetContent()) { uri: Uri? ->
    if (uri != null) OcrPreviewBus.recognize(context, uri)
}

/**
 * 与设置弹窗 / 更新弹窗一致的窗口处理：清掉 Dialog 背景与 elevation，
 * 改用系统 FLAG_DIM_BEHIND 均匀暗化整屏，避免部分 ROM 出现方形硬边阴影。
 */
@Composable
internal fun PreviewDialogWindowEffect() {
    val dialogView = LocalView.current
    LaunchedEffect(dialogView) {
        var parent: ViewParent? = dialogView.parent
        while (parent != null) {
            if (parent is DialogWindowProvider) {
                val window = parent.window
                window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                window.decorView.elevation = 0f
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0.42f)
                break
            }
            parent = parent.parent
        }
    }
}
