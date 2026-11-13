package com.hnu.pickupassistant.ocr

import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * 「取了吗」识图取件码 —— 文字块 Y 坐标排序工具类。
 *
 * 解决问题：ML Kit 返回的 `visionText.text` 是按识别分块顺序拼的，
 * 一张多条快递的截图里，文字顺序经常是乱的（左侧 Logo、右侧取件码、
 * 下一条的地址互相穿插），直接按整段文本正则解析会造成字段错位、
 * 只识别到最后一条、驿站名称/地址/单号抓不到。
 *
 * 本工具类把每一个带 boundingBox 的文字行还原成**页面真实阅读顺序**：
 *   ① 收集 textBlocks → lines（无 lines 时用 elements 兜底）；
 *   ② 按 top（Y 坐标）升序排列；
 *   ③ top 足够接近的行聚类成同一个「视觉行」，行内按 left（X 坐标）升序合并；
 *   ④ 输出合并后的视觉行列表 + 未合并的原始行列表。
 *
 * 不替换原有 OCR 能力：仍用工程已有的
 * `TextRecognition.getClient(ChineseTextRecognizerOptions...)` bundled 离线模型。
 *
 * @author 焱川 (by 焱川)
 */
object OcrTextSorter {

    /** 一行 OCR 文字及其在图片中的位置（像素坐标，原点为图片左上角） */
    data class TextLine(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerY: Int get() = (top + bottom) / 2
        val height: Int get() = (bottom - top).coerceAtLeast(1)
        val width: Int get() = (right - left).coerceAtLeast(1)
    }

    /**
     * 排序结果。
     * @param rows 合并后的视觉行（已按 Y 从上到下、同行按 X 从左到右），供分组解析使用
     * @param rawLines 未合并的原始行（同样已排序），供「左侧 Logo 优先判定快递公司」使用
     */
    data class SortedText(
        val rows: List<TextLine>,
        val rawLines: List<TextLine>
    ) {
        /** 调试/日志用：还原成按阅读顺序排列的纯文本 */
        fun toPlainText(): String = rows.joinToString("\n") { it.text }
    }

    /**
     * 从 ML Kit 识别结果中提取所有文字行并还原阅读顺序。
     * 识别结果为空或全部没有 boundingBox 时返回空列表。
     */
    fun fromVisionText(visionText: Text): SortedText {
        val raw = mutableListOf<TextLine>()
        for (block in visionText.textBlocks) {
            val lines = block.lines
            if (lines.isEmpty()) {
                // 少数机型 / ML Kit 版本整个 block 没拆出 line，用 block 自身兜底
                val box = block.boundingBox ?: continue
                val t = block.text.trim()
                if (t.isNotEmpty()) {
                    raw.add(TextLine(t, box.left, box.top, box.right, box.bottom))
                }
                continue
            }
            for (line in lines) {
                val box = line.boundingBox ?: continue
                val t = line.text.trim()
                if (t.isNotEmpty()) {
                    raw.add(TextLine(t, box.left, box.top, box.right, box.bottom))
                }
            }
        }
        if (raw.isEmpty()) return SortedText(emptyList(), emptyList())

        // ① Y 坐标从上到下；同一高度再按 X 从左到右
        val sortedRaw = raw.sortedWith(compareBy<TextLine> { it.top }.thenBy { it.left })
        // ② 聚类成视觉行并合并
        val rows = groupIntoRows(sortedRaw)
        return SortedText(rows = rows, rawLines = sortedRaw)
    }

    /**
     * 纯文本兜底入口：没有坐标信息时（例如从剪贴板拿到多行文本），
     * 按原始行顺序伪造递增的 top，保证分组解析逻辑可以复用。
     */
    fun fromPlainText(text: String): SortedText {
        val rows = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, line ->
                TextLine(line, 0, index * 40, line.length * 12, index * 40 + 32)
            }
        return SortedText(rows = rows, rawLines = rows)
    }

    /** 对外提供：把任意 TextLine 列表按阅读顺序排序（Y 优先，同行 X 次之）。 */
    fun sort(lines: List<TextLine>): List<TextLine> =
        lines.sortedWith(compareBy<TextLine> { it.top }.thenBy { it.left })

    /**
     * 把 top 接近的行聚类成同一个视觉行。
     * 容差取「当前视觉行最大行高 * 0.6」，最小 8px，能容忍 OCR 对同一行
     * 不同字号（Logo 小字、取件码大字）造成的 top 轻微偏移。
     */
    private fun groupIntoRows(sortedRaw: List<TextLine>): List<TextLine> {
        val groups = mutableListOf<MutableList<TextLine>>()
        for (line in sortedRaw) {
            val target = groups.lastOrNull { group ->
                val rowHeight = group.maxOf { it.height }
                val tolerance = maxOf(8, (rowHeight * 0.6f).toInt())
                abs(group.first().top - line.top) <= tolerance
            }
            if (target != null) target.add(line) else groups.add(mutableListOf(line))
        }
        return groups.map { group ->
            val parts = group.sortedBy { it.left }
            TextLine(
                text = parts.joinToString(" ") { it.text },
                left = parts.minOf { it.left },
                top = parts.minOf { it.top },
                right = parts.maxOf { it.right },
                bottom = parts.maxOf { it.bottom }
            )
        }.sortedWith(compareBy<TextLine> { it.top }.thenBy { it.left })
    }
}
