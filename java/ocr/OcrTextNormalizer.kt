package com.hnu.pickupassistant.ocr

/**
 * OCR 文本归一化 / 版面噪声治理（纯 Kotlin，不依赖 Android，可 JVM 单测）。
 *
 * 吸收自两份参考资料中**当前工程尚未实现**的部分：
 *  - `Pickup Assistant V1.1 Source` §4.2 OcrPickupCodeParser：全角转半角、
 *    异形分隔符归一、取件码块内形近字母纠偏、日期/脱敏号排除；
 *  - `Ocr Pro Module China` §3.4 PickupLayoutAnalyzer：已签收区域熔断、
 *    签收行不提取取件码、驿站截断补全、泛指词黑名单、促销角标剥离。
 *
 * ## 刻意不做的事（与现有需求冲突，避免回归）
 *  - **不按数字号段反推快递公司**（资料里的 77→申通 / 46→韵达）：
 *    需求明确「禁止单凭数字长度 / 位数强判定公司」，公司只认品牌文字或字母前缀；
 *  - **不加货架/层数上限**（资料里 layer ≤20）：真码 `5-89-2046` 的层号是 89，
 *    2.9.0 专门修过「把 2046 当年份误杀」的问题，加上限会重新误杀；
 *  - 不整份替换现有解析器：只做「行文本预处理 + 熔断 + 驿站名精炼」三层薄接入。
 *
 * @author 焱川 (by 焱川)
 */
object OcrTextNormalizer {

    // ==================== 取件码文本归一化 ====================

    /**
     * 数字之间的异形分隔符（中文「一」、破折号、波浪号、下划线…）统一成 `-`。
     * 用「前后必须是数字」的环视限定，避免把「周一至周日」的「一」改成横杠。
     *
     * **不含冒号**：`09:00-19:30` 这类营业时间必须保持原样，否则
     * [PickupRegexRules.isTimeLike] 的时间排除会失效、时间片段会被当取件码。
     */
    private val DIGIT_SEPARATOR = Regex(
        """(?<=\d)\s*[\-－—–―─~～_·•一]\s*(?=\d)"""
    )

    /**
     * 取件码候选块：`数字/形近字母 - 数字/形近字母 [- 数字/形近字母]`。
     * 只在这类块内部做字母→数字纠偏，绝不改动中文品牌名与字母单号前缀。
     */
    private val CODE_CONFUSABLE_BLOCK = Regex(
        """[0-9OoQqIl|!ZzSsGgBb]{1,4}\s*-\s*[0-9OoQqIl|!ZzSsGgBb]{1,4}(?:\s*-\s*[0-9OoQqIl|!ZzSsGgBb]{3,6})?"""
    )

    /** 全角字符转半角（全角空格单独处理） */
    fun toHalfWidth(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch == '\u3000' -> append(' ')
                ch in '\uFF01'..'\uFF5E' -> append((ch.code - 0xFEE0).toChar())
                else -> append(ch)
            }
        }
    }

    /** 只在数字之间归一分隔符：`2一4一3665` → `2-4-3665`，「周一至周日」不受影响 */
    fun normalizeSeparators(text: String): String = DIGIT_SEPARATOR.replace(text, "-")

    /**
     * 纠偏取件码块里的形近字母：O/o/Q→0、I/l/|/!→1、Z/z→2、S/s→5、G/g→6、B/b→8。
     * 例：`7O6-4B` → `706-48`，`2-4-3S65` → `2-4-3565`。
     * 纯数字码保持原样；`EMS 1356884918749`、`YT0724…` 这类不含横杠的文本不会被匹配。
     */
    fun fixCodeConfusables(text: String): String =
        CODE_CONFUSABLE_BLOCK.replace(text) { match ->
            buildString(match.value.length) {
                for (c in match.value) {
                    append(
                        when (c) {
                            'O', 'o', 'Q', 'q' -> '0'
                            'I', 'l', '|', '!' -> '1'
                            'Z', 'z' -> '2'
                            'S', 's' -> '5'
                            'G', 'g' -> '6'
                            'B', 'b' -> '8'
                            else -> c
                        }
                    )
                }
            }
        }

    /**
     * 单行完整归一化：全角 → 分隔符 → 形近字母 → 空格还原短横线 → OCR 形近汉字纠正。
     * 供 ParcelBlockParser 的 tokenize / parseRows 统一调用。
     */
    fun normalizeLine(raw: String): String {
        if (raw.isBlank()) return ""
        val half = toHalfWidth(raw)
        val separated = normalizeSeparators(half)
        val corrected = fixCodeConfusables(separated)
        val dashed = PickupRegexRules.restoreDashedCodes(corrected)
        return PickupRegexRules.fixOcrTypos(dashed)
    }

    // ==================== 已签收 / 历史区域熔断 ====================

    /** 「近日签收 / 已取出」等分界线关键词 */
    private val SIGNED_CUTOFF_KEYWORDS = listOf(
        "近日签收", "已从代收点取出", "已取出", "运单号查过的包裹", "同学都在买", "福利加码"
    )

    /**
     * 熔断：菜鸟 App 包裹管理页下半部分是「近日签收·N」历史区，
     * 里面的 `11-09` 会被当两段式取件码、里面的「中通快递」会串到上方待取包裹。
     *
     * @return 第一个历史区分界行的下标；null = 没有历史区
     */
    fun signedCutoffIndex(lines: List<String>): Int? {
        for ((index, raw) in lines.withIndex()) {
            val t = raw.replace(Regex("""\s+"""), "")
            // 顶部 Tab 栏「待取件1 追踪中 已签收3 已退回」不是分界线
            if (t.contains("待取件") && t.contains("已签收")) continue
            if (SIGNED_CUTOFF_KEYWORDS.any { t.contains(it) }) return index
            // 「已签收 11-09 17:44签收」这种具体签收行也是分界线
            if (t.startsWith("已签收") && t.contains("签收") && t.length > 6) return index
        }
        return null
    }

    /** 截断到历史区之前（没有历史区时原样返回） */
    fun cutoffSignedSection(lines: List<String>): List<String> {
        val cut = signedCutoffIndex(lines) ?: return lines
        return if (cut <= 0) emptyList() else lines.subList(0, cut)
    }

    /**
     * 签收 / 退回行一律不提取取件码（杜绝 `已签收 11-09` 被当成两段码）。
     * 带「取件码」标签的行不在此列，避免误杀真实码行。
     */
    fun isSignedOrReturnedLine(text: String): Boolean =
        (text.contains("签收") || text.contains("退回") || text.contains("已从代收点取出")) &&
            !text.contains("取件码") && !text.contains("取件碼")

    // ==================== 驿站名称精炼 ====================

    /**
     * 泛指词 / 导航栏黑名单：绝不作为具体驿站名。
     *
     * 注意：**不把「菜鸟驿站」「驿站」「代收点」列入** —— 现有工程的组头切分
     * 允许这些短名作为合法站点（用户可在弹窗里补全），拉黑会让历史用例的
     * 驿站字段变空；只拦地图气泡、导航标题这类明确不是站名的词。
     */
    private val STATION_BLACKLIST = setOf(
        "当前在菜鸟驿站", "我的菜鸟驿站", "菜鸟驿站代收",
        "全部包裹", "待取件", "包裹管理", "快递超市列表"
    )

    /** 促销 / 状态角标：从驿站名里剥掉 */
    private val STATION_NOISE_LABELS = listOf(
        "支持退换货", "绿色公益", "畅通", "求助", "打电话", "电话", "导航",
        "去确认", "复制", "分享", "已入站", "营业时间", "周一至周日"
    )

    /**
     * 精炼驿站名：
     *  ① 剥掉「支持退换货」等角标；② 取最长的中文分段（丢掉左侧 logo）；
     *  ③ 过滤地图气泡 / 泛指词；④ 菜鸟小程序把「…菜鸟驿」截断时补全成「…菜鸟驿站」。
     *  返回空串表示该行不是有效驿站名。
     */
    fun refineStationName(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        for (noise in STATION_NOISE_LABELS) text = text.replace(noise, " ")
        text = text.replace(Regex("""[>＞》…·•|_+]"""), " ").trim()
        if (text.isEmpty()) return ""

        val tokens = text.split(Regex("""\s+""")).filter { it.length >= 2 }
        var best = tokens.maxByOrNull { it.length } ?: text
        if (best in STATION_BLACKLIST || best.startsWith("当前在") || best.startsWith("距您")) {
            return ""
        }
        // 「淮师大滨湖校区文源楼北菜鸟驿」/「…菜鸟驿t」→ 补成完整「菜鸟驿站」
        best = best.replace(Regex("""菜鸟驿[A-Za-z0-9]?$"""), "菜鸟驿站")
        if (best.endsWith("菜鸟驿")) best += "站"
        return best.trim()
    }

    // ==================== 干扰项 ====================

    /** 脱敏手机号：`182****0580`、`195****4262` —— 不是物流单号 */
    fun isMaskedPhone(text: String): Boolean =
        Regex("""\d{3}\*{2,}\d{3,4}""").containsMatchIn(text)

    // ==================== 取件码锚点 / 日期排除 ====================

    /**
     * 日期型假码：`1-2-2026` / `11-10-2026` / `2026-11-10`。
     * 与 2.9.0 修过的口径一致：尾号是年份**且**中间段是月份**且**首段是日，才判日期，
     * 避免把真码 `5-89-2046`（中间 89 不是月份）误杀。
     */
    fun isDateLikeCode(code: String): Boolean {
        val parts = code.split('-')
        if (parts.size < 2) return false
        val last = parts.last()
        if (last.length == 4 && (last.startsWith("19") || last.startsWith("20"))) {
            val month = parts[parts.size - 2].toIntOrNull() ?: return false
            val day = parts.first().toIntOrNull() ?: return false
            return month in 1..12 && day in 1..31
        }
        val head = parts.first()
        return parts.size == 3 && head.length == 4 &&
            (head.startsWith("19") || head.startsWith("20")) &&
            (parts[1].toIntOrNull() ?: 99) <= 12 &&
            (parts[2].toIntOrNull() ?: 99) <= 31
    }

    /**
     * 「这行是不是一个包裹的取件码锚点」。
     *
     * 修复「看见没」抓屏的 BUG：旧 `CODE_LINE` 只认三段式，遇到妈妈驿站
     * `取件码 706-48` 这类两段式就找不到锚点 → splitParcelBlocks 返回 null →
     * 整段兜底识别，多包裹的驿站 / 公司 / 单号互相串味。
     * 现在统一走 [PickupRegexRules.findPickupCode]（两段 / 三段都支持），
     * 并排除时间片段、日期串与已签收行。
     */
    fun isPickupCodeAnchor(text: String): Boolean {
        if (isSignedOrReturnedLine(text)) return false
        val match = PickupRegexRules.findPickupCode(text) ?: return false
        return !isDateLikeCode(PickupRegexRules.normalizeCode(match))
    }

    // ==================== 长截图分片结果合并 ====================

    /**
     * 合并分片 OCR 的行文本，去掉 overlap 区产生的连续重复行。
     *
     * 分片之间有 320px 重叠，同一行文字会被相邻两片各识别一次；
     * 这里只处理「后一片开头」与「前一片末尾」的连续重复段，
     * 不会误删同一屏里本来就重复出现的两个取件码。
     */
    fun mergeTiledLines(chunks: List<List<String>>, overlapWindow: Int = 8): List<String> {
        val result = mutableListOf<String>()
        for (chunk in chunks) {
            var start = 0
            if (result.isNotEmpty()) {
                val tail = result.takeLast(overlapWindow).map { normalizeLine(it).trim() }
                while (start < chunk.size && start < overlapWindow) {
                    val line = normalizeLine(chunk[start]).trim()
                    if (line.isEmpty() || tail.contains(line)) start++ else break
                }
            }
            chunk.subList(start, chunk.size)
                .map { normalizeLine(it).trim() }
                .filter { it.isNotEmpty() }
                .let(result::addAll)
        }
        return result
    }
}
