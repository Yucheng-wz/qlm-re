package com.hnu.pickupassistant.util

/**
 * 「取了吗」取件码提取器（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * ## 修复的真实 BUG
 * 旧实现有两个缺陷，导致「剪贴板里识别出压根不存在的取件码」以及漏识别：
 *
 *  ① **日期被当成取件码**：三段式正则 `(\d{1,3})-(\d{1,2})-(\d{3,6})`
 *     会把「有效期 11-10-2026」「时间 1-2-2026」这类日期匹配成取件码
 *     `11-10-2026`，而剪贴板里根本没有这个码。
 *  ② **三段式优先导致两码全丢**：旧逻辑「只要文本里有任意三段式码，就只返回三段式结果」，
 *     因此同一段文本里 `706-48`、`706-10`、`3-7-2593` 只会导入 `3-7-2593`；
 *     若三段式命中的恰好是①的假日期，真码反而全部丢失、只导入假码。
 *
 * ## 现在的行为
 *  - 三段式与两段式**同时提取**；两段式必须由「取件码/取码/提货码…」关键字锚定，
 *    不会把普通 `数字-数字`（金额、日期片段）误判为码；
 *  - 按文本区间去重：两段式匹配若与某个三段式匹配重叠则丢弃；
 *  - [looksLikeDate] 过滤日期型假码：第三段是四位年份、或紧邻日期分隔符、
 *    或前面紧跟「日期/有效期/截止时间」等引导词；
 *  - 纯取件码记录（如 `2-96-8503`）不要求关键字，能力保持不变。
 *
 * @author 焱川 (by 焱川)
 */
object PickupCodeMatcher {

    /** 取件码片段：三段式「货架-层-尾号」或两段式「货架-编号」（尾号为空） */
    data class CodeSegment(val shelf: String, val layer: String, val tail: String) {
        /** 保留层号原文（含前导零，如 6-04-406），避免归一化后对不上号 */
        val rawCode: String
            get() = if (tail.isEmpty()) "$shelf-$layer" else "$shelf-$layer-$tail"
    }

    /**
     * 三段式取件码：1~3 位货架号、1~2 位层号、3~6 位尾号，
     * 前后不能紧邻数字或连字符（避免订单号、门牌号、更长数字串被切一截）。
     */
    private val THREE_SEG = Regex(
        """(?<![\d\-－—–])(\d{1,3})\s*[\-－—–]\s*(\d{1,2})\s*[\-－—–]\s*(\d{3,6})(?![\d\-－—–])"""
    )

    /**
     * 两段式取件码：必须有「取件码」等关键字锚定，
     * 否则 `2026-11` 之类日期片段会被误判。
     */
    private val TWO_SEG = Regex(
        """(?:取件码|取码|提货码|自提码|包裹码|提取码|取件编号)\s*[:：]?\s*(\d{1,3})\s*[\-－—–]\s*(\d{1,3})(?![\d\-－—–])"""
    )

    /**
     * **整行只有一个裸码**的两段式：用于剪贴板里的多行清单，例如
     * ```
     * 706-48
     * 706-10
     * ```
     * 这种文本没有「取件码」关键字，旧逻辑一条都识别不出来（多行识别失败的根因）。
     * 限制「整行只有码」+ 首段 ≤3 位，既能把多行全部抓出来，
     * 又不会把 `2026-11`、`138-1234` 这类日期/电话误判成取件码。
     */
    private val BARE_LINE_TWO_SEG = Regex(
        """(?m)^[ \t]*(\d{1,3})[ \t]*[\-－—–][ \t]*(\d{1,3})[ \t]*$"""
    )

    /** 出现在匹配串**之前**时说明这段数字是日期/时间，不是取件码 */
    private val DATE_LEADING_WORDS = listOf(
        "日期", "有效期", "截止", "到期", "签收时间", "下单时间", "支付时间",
        "创建时间", "更新时间", "发货时间", "打印时间", "取件时间", "时间:"
    )

    /**
     * 从一段文本里提取全部取件码（剪贴板多行清单也能全局匹配多条）。
     * 返回顺序：三段式 → 两段式（关键字锚定）→ 整行裸码，已按文本区间去重。
     */
    fun extract(text: String): List<CodeSegment> {
        if (text.isBlank()) return emptyList()

        // ① 三段式：先过滤日期型假码
        val three = THREE_SEG.findAll(text)
            .filterNot { looksLikeDate(it, text) }
            .map { match ->
                CodeSegment(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3]
                ) to match.range
            }
            .toList()

        // ② 两段式（关键字锚定）：与三段式区间重叠的丢弃（「取件码 3-7-2593」不应重复取 3-7）
        val threeRanges = three.map { it.second }
        val two = TWO_SEG.findAll(text)
            .filterNot { match ->
                threeRanges.any { r -> overlaps(match.range, r) }
            }
            .map { match ->
                CodeSegment(match.groupValues[1], match.groupValues[2], "") to match.range
            }
            .toList()

        // ③ 整行裸码（剪贴板多行清单）：与前面两类区间重叠的丢弃，且排除日期上下文
        val takenRanges = threeRanges + two.map { it.second }
        val bare = BARE_LINE_TWO_SEG.findAll(text)
            .filterNot { match -> takenRanges.any { r -> overlaps(match.range, r) } }
            .filterNot { match -> looksLikeDateContext(match, text) }
            .filter { match ->
                // 位数下限：两段合起来至少 5 位数字（706-48 ✓；11-10、1-2 这种日期片段 ✗）
                (match.groupValues[1].length + match.groupValues[2].length) >= 5
            }
            .map { match ->
                CodeSegment(match.groupValues[1], match.groupValues[2], "") to match.range
            }
            .toList()

        // 同一段文本里同一个码可能被多种规则命中（如「取件码 706-48」+ 下一行裸写 706-48），按值去重
        return (three + two + bare).map { it.first }.distinctBy { it.rawCode }
    }

    /** 整行裸码是否处在「日期」上下文里（同一行前缀或上一行出现日期引导词） */
    private fun looksLikeDateContext(match: MatchResult, text: String): Boolean {
        val start = (match.range.first - 12).coerceAtLeast(0)
        if (start >= match.range.first) return false
        val prefix = text.substring(start, match.range.first)
        return DATE_LEADING_WORDS.any { prefix.contains(it) }
    }

    /** 两个文本区间是否重叠 */
    private fun overlaps(a: IntRange, b: IntRange): Boolean =
        a.first <= b.last && b.first <= a.last

    /**
     * 判断一个三段式匹配是否是日期/时间而非取件码。
     *
     * 规则：
     *  ① 第三段是 4 位年份（19/20 开头）**且中间段是月份（≤12）、首段是日（≤31）**
     *     → `11-10-2026` / `1-2-2026` 排除；
     *     但 `5-89-2046` 这种真取件码（中间 89 不是月份）**必须保留**，
     *     旧规则只看尾号 2046 像年份就杀掉，导致真码被误判成日期、落进待办；
     *  ② 前后紧邻 `/` 或「年月日」→ 日期串的一部分；
     *  ③ 匹配串**前面** 8 个字符里出现明确的日期引导词 → 排除。
     *     （只看前面，避免「取件码 3-7-2593 有效期 7 天」里的真码被误杀。）
     */
    private fun looksLikeDate(match: MatchResult, text: String): Boolean {
        val head = match.groupValues[1]
        val middle = match.groupValues[2]
        val tail = match.groupValues[3]

        // ① 尾号是年份 → 必须同时满足「中间是月份、首段是日」才算日期
        if (tail.length == 4 && (tail.startsWith("19") || tail.startsWith("20"))) {
            val looksLikeDayMonth = middle.toIntOrNull()?.let { it in 1..12 } == true &&
                head.toIntOrNull()?.let { it in 1..31 } == true
            if (looksLikeDayMonth) return true
        }
        // 首段是年份（2026-11-10）→ 中间、尾段都应是 ≤31 的月/日
        if (head.length == 4 && (head.startsWith("19") || head.startsWith("20"))) {
            val m = middle.toIntOrNull() ?: 0
            val d = tail.toIntOrNull() ?: 0
            if (m in 1..12 && d in 1..31) return true
        }

        val before = text.getOrNull(match.range.first - 1)
        val after = text.getOrNull(match.range.last + 1)
        if (before != null && before in "/年月日") return true
        if (after != null && after in "/年月日") return true

        val start = (match.range.first - 8).coerceAtLeast(0)
        if (start < match.range.first) {
            val prefix = text.substring(start, match.range.first)
            if (DATE_LEADING_WORDS.any { prefix.contains(it) }) return true
        }
        return false
    }
}
