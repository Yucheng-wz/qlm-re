package com.hnu.pickupassistant.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.hnu.pickupassistant.data.ImportResult
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.ocr.OcrImageEnhancer
import com.hnu.pickupassistant.ocr.OcrTextNormalizer
import com.hnu.pickupassistant.ocr.ParcelBlockParser

/**
 * 本地离线图片文字识别（ML Kit 中文/拉丁 bundled 模型，模型随 APK 打包，不需要联网）。
 *
 * 多条包裹截图：ML Kit 的每一行文字都带 boundingBox，按「取件码所在行」把整张截图
 * 切成一条条包裹，每条独立识别本条对应的快递公司（优先读左侧 Logo 区域文字，
 * 文字识别作为辅助），各条之间互不继承。找不到关键词的那条快递字段留空。
 *
 * 普通短信 / 无法按包裹切分的截图：回退到原来的整段识别，行为完全不变。
 */
object OcrHelper {

    /** 屏幕识别结果：取件码导入情况 + 是否补了一条待办 + 识别出的原文 */
    data class ScreenImportOutcome(
        val codes: ImportResult,
        val todoAdded: Boolean,
        val rawText: String
    )

    /** ML Kit 的一行文字及其位置，用于按包裹条目切块、定位左侧 Logo */
    private data class OcrLine(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /**
     * 取件码锚点判定已统一委托 [OcrTextNormalizer.isPickupCodeAnchor]：
     * 旧的 CODE_LINE 只认三段式，遇到妈妈驿站「取件码 706-48」这类两段式会
     * 找不到锚点 → splitParcelBlocks 返回 null → 整段兜底识别导致多包裹串味。
     */

    fun recognizeAndImport(
        context: Context,
        uri: Uri,
        note: String = "",
        onResult: (ImportResult) -> Unit
    ) {
        // 与「识图添加」入口共用图像预处理：高清解码 + 暗通道增强 + 状态栏屏蔽；
        // 解码 / 增强失败自动回退到原来的 InputImage.fromFilePath，识图功能不失效。
        var enhanced: Bitmap? = null
        val image = try {
            enhanced = OcrImageEnhancer.decodeEnhanced(context.applicationContext, uri)
            if (enhanced != null) InputImage.fromBitmap(enhanced, 0)
            else InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            runCatching { enhanced?.recycle() }
            enhanced = null
            try {
                InputImage.fromFilePath(context, uri)
            } catch (e2: Exception) {
                Toast.makeText(
                    context,
                    "无法读取图片：${e2.localizedMessage ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        fun releaseEnhanced() {
            enhanced?.let { if (!it.isRecycled) it.recycle() }
            enhanced = null
        }

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // try/finally 保证解析异常时 recognizer 与增强 Bitmap 都能释放
                try {
                    val result = importFromVisionText(visionText, note)
                    onResult(result)
                } catch (t: Throwable) {
                    onResult(ImportResult(0, 0, 0))
                } finally {
                    recognizer.close()
                    releaseEnhanced()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    context,
                    "图片识别失败：${e.localizedMessage ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
                recognizer.close()
                releaseEnhanced()
            }
    }

    /**
     * 「看见没」磁贴专用：对当前屏幕截图做 OCR。
     *  - 画面里有取件码 → 正则批量入库
     *  - 画面里没有取件码但有文字 → 整理成一条待办（同一内容不重复添加）
     */
    fun recognizeScreenAndImport(
        bitmap: Bitmap,
        onResult: (ScreenImportOutcome) -> Unit
    ) {
        // 抓屏 Bitmap 由 MediaProjection / Shizuku 侧持有，只在副本上做增强，绝不 recycle 原图
        val enhanced = OcrImageEnhancer.enhanceForOcr(bitmap)
        fun releaseEnhanced() {
            if (enhanced !== bitmap && !enhanced.isRecycled) enhanced.recycle()
        }
        val image = try {
            InputImage.fromBitmap(enhanced, 0)
        } catch (e: Exception) {
            releaseEnhanced()
            onResult(ScreenImportOutcome(ImportResult(0, 0, 0), false, ""))
            return
        }

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                try {
                    val text = visionText.text
                    val codes = importFromVisionText(visionText, "")
                    var todoAdded = false
                    if (codes.recognized == 0) {
                        val clean = text.trim().replace(Regex("\\s+"), " ").take(120)
                        // 出库码（XN 码 / 身份码 / 条码）与其它代码类文本都不是待办，不能塞进待办列表
                        val codeLike = OutboundCodeDetector.isOutboundCode(clean) ||
                            OutboundCodeDetector.isCodeLikeText(clean)
                        if (clean.isNotBlank() && !codeLike) {
                            val duplicate = LocalStorageManager.pickupItemsFlow.value.any {
                                !it.isPicked && it.isTodo && it.note == clean
                            }
                            if (!duplicate) {
                                todoAdded = LocalStorageManager.addTodoItem(clean)
                            }
                        }
                    }
                    onResult(ScreenImportOutcome(codes, todoAdded, text))
                } catch (t: Throwable) {
                    onResult(ScreenImportOutcome(ImportResult(0, 0, 0), false, ""))
                } finally {
                    recognizer.close()
                    releaseEnhanced()
                }
            }
            .addOnFailureListener {
                onResult(ScreenImportOutcome(ImportResult(0, 0, 0), false, ""))
                recognizer.close()
                releaseEnhanced()
            }
            .addOnCompleteListener {
                // 兜底：任何回调路径都确保 recognizer 关闭、增强副本回收
                runCatching { recognizer.close() }
                releaseEnhanced()
            }
    }

    /** 统一入库入口：能按包裹切分就逐条独立识别，否则保持原来的整段识别 */
    private fun importFromVisionText(visionText: Text, note: String): ImportResult {
        val parsed = splitParcelBlocks(visionText)
        return if (parsed != null) {
            // 取件码从所有包裹块一次批量提取；快递公司 / 驿站 / 单号按每条的独立判定结果覆盖，
            // 空串表示该条没找到 → 字段留空，绝不复用其它行，也绝不写进备注
            LocalStorageManager.extractAndImportFromText(
                visionText.text,
                note,
                segments = parsed.segments,
                segmentCouriers = parsed.couriers,
                segmentStations = parsed.stations,
                segmentTrackingNos = parsed.trackingNos
            )
        } else {
            LocalStorageManager.extractAndImportFromText(visionText.text, note)
        }
    }

    /**
     * 按「包裹条目」切块：
     * 以每个取件码所在行为锚点，该行 + 其下方到下一个取件码行之前的文字
     * （含同一行左侧的 Logo 文字）组成一条包裹，各条互不重叠。
     *
     * 快递公司优先取左侧 Logo 区域的文字（如「J&T 极兔速递」「中国邮政 CHINA POST」），
     * 识别不到再用整条文字兜底；整条也没有关键词就留空。
     *
     * @return [ParcelBlocks]：每条文本 / 快递公司 / 驿站名称 / 物流单号；
     *         截图里没有独立取件码行时返回 null（回退整段识别）
     */
    private fun splitParcelBlocks(visionText: Text): ParcelBlocks? {
        val collected = mutableListOf<OcrLine>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                // 与「识图添加」入口共用同一套 OCR 文本归一化（全角 / 异形分隔符 / 形近字母）
                val t = OcrTextNormalizer.normalizeLine(line.text).trim()
                if (t.isNotEmpty()) {
                    collected.add(OcrLine(t, box.left, box.top, box.right, box.bottom))
                }
            }
        }
        if (collected.isEmpty()) return null
        collected.sortWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        // 已签收 / 历史区域熔断：不让「已签收 11-09」或下方已签收包裹进入待取列表
        val cutoff = OcrTextNormalizer.signedCutoffIndex(collected.map { it.text })
        val lines: List<OcrLine> = if (cutoff != null) collected.subList(0, cutoff) else collected
        if (lines.isEmpty()) return null

        val codeIdx = lines.indices.filter {
            OcrTextNormalizer.isPickupCodeAnchor(lines[it].text)
        }
        if (codeIdx.isEmpty()) return null

        val used = BooleanArray(lines.size)
        val segments = mutableListOf<String>()
        val couriers = mutableListOf<String>()
        val stations = mutableListOf<String>()
        val trackingNos = mutableListOf<String>()

        for (i in codeIdx.indices) {
            val anchorIdx = codeIdx[i]
            val anchor = lines[anchorIdx]
            val nextTop = if (i + 1 < codeIdx.size) lines[codeIdx[i + 1]].top else Int.MAX_VALUE
            // Logo 方块通常比取件码文字略高，容差取码行高度的 1.2 倍（至少 16px）
            val tol = maxOf(16, ((anchor.bottom - anchor.top) * 1.2f).toInt())
            val from = anchor.top - tol
            // 单码截图不要一直卷到图片底部（广告/推荐区），最多覆盖约 8 行
            val cap = anchor.top + maxOf(160, (anchor.bottom - anchor.top) * 8)
            val to = if (nextTop == Int.MAX_VALUE) cap else minOf(nextTop - tol, cap)

            val blockLines = mutableListOf<OcrLine>()
            for (j in lines.indices) {
                if (used[j]) continue
                val l = lines[j]
                if (l.top >= from && l.top < to) {
                    used[j] = true
                    blockLines.add(l)
                }
            }
            if (blockLines.isEmpty()) {
                // 极端情况：锚点行已被前一块吸收，至少保留锚点自身
                used[anchorIdx] = true
                blockLines.add(anchor)
            }

            val blockText = blockLines.joinToString("\n") { it.text }
            // 左侧 Logo 区域：x 明显在取件码左边、且与码行高度相近的行
            val logoText = blockLines
                .filter { it.left < anchor.left - 8 && it.top <= anchor.bottom + tol }
                .joinToString(" ") { it.text }

            // 需求 4：Logo 优先；需求 3：本条找不到就留空，不继承其它条
            val byLogo = if (logoText.isBlank()) "" else TextParser.detectCourier(logoText)
            val byText = TextParser.detectCourier(blockText)
            segments.add(blockText)
            couriers.add(byLogo.ifEmpty { byText })

            // 驿站 / 单号作为结构化字段逐条传递，绝不拼成模板写进备注
            val texts = blockLines.map { it.text }
            stations.add(ParcelBlockParser.extractStation(texts).trim())
            trackingNos.add(ParcelBlockParser.extractTrackingNo(texts).trim())
        }
        return if (segments.isEmpty()) null else ParcelBlocks(segments, couriers, stations, trackingNos)
    }

    /** 逐条包裹的结构化识别结果；备注不在其中（备注只归用户手写） */
    private data class ParcelBlocks(
        val segments: List<String>,
        val couriers: List<String>,
        val stations: List<String>,
        val trackingNos: List<String>
    )
}
