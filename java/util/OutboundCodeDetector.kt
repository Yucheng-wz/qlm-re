package com.hnu.pickupassistant.util

/**
 * 出库码 / 代码型文本识别器（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * ## 修复的真实 BUG
 * 「文本添加」「看见没 OCR」在识别不到取件码时，会把整段文字兜底写成一条**待办**。
 * 用户复制 / 截图的是出库码（XN 码、身份码、条码、纯数字码）时，也会被当成待办塞进待办列表，
 * 造成「出库码识别错误为待办」。
 *
 * 判定分两层：
 *  - [isOutboundCode]：明确是出库码（关键词 / XN 码 / 长条码）→ 提示「这是出库码…」；
 *  - [isCodeLikeText]：只是长得像代码（无中文、含数字、≥5 位，如快递单号）→ 同样不建待办，
 *    但提示语不同，避免把单号说成出库码。
 *
 * 取件码形状（数字-数字[-数字]）一律放行 —— 那是「待取」的数据，不是待办也不是出库码。
 *
 * @author 焱川 (by 焱川)
 */
object OutboundCodeDetector {

    /** 明确指向「出库码」的关键词 */
    private val KEYWORDS: List<String> = listOf(
        "出库码", "出库凭证", "身份码", "取件凭证", "自助出库", "出库取件"
    )

    /** 淘宝出库码（XN 码）：XN + 6~20 位数字，允许中间带空格 / 横线 */
    private val XN_CODE = Regex("""(?i)(?<![A-Z0-9])XN[\s\-]?\d{6,20}(?![A-Z0-9])""")

    /** 纯数字长条码：16~24 位（出库条码常见），不含字母与汉字 */
    private val PURE_BARCODE = Regex("""^\d{16,24}$""")

    /** 取件码形状：数字-数字[-数字]，这类永远不该被判成出库码 / 待办 */
    private val PICKUP_SHAPE = Regex("""^\d{1,4}\s*[-－—–]\s*\d{1,4}(\s*[-－—–]\s*\d{1,6})?$""")

    /** 代码型 token：字母 / 数字 / 横线连写 ≥5 位，中间没有汉字 */
    private val CODE_TOKEN = Regex("""^[A-Za-z0-9\-－—–\s]{5,}$""")

    /** 这段文本是否明显是「出库码」 */
    fun isOutboundCode(text: String): Boolean {
        val raw = text.trim()
        if (raw.isBlank()) return false

        // ① 关键词（整段文本里出现即可）
        if (KEYWORDS.any { raw.contains(it) }) return true

        val compact = raw.replace(Regex("""\s+"""), "")

        // ② XN 码
        if (XN_CODE.containsMatchIn(raw) || XN_CODE.containsMatchIn(compact)) return true

        // ③ 纯数字长条码（16~24 位）
        val hasCjk = raw.any { it in '\u4E00'..'\u9FFF' }
        if (!hasCjk && PURE_BARCODE.matches(compact)) return true

        return false
    }

    /**
     * 这段文本是不是「代码型文本」（无中文、含数字、不像自然语言待办）。
     *
     * 用于兜底闸：识别不到取件码时，这类文本（快递单号、条码、各种码串）
     * 一律不写进待办列表，避免「出库码 / 单号被当成待办」。
     * 真正的待办事项是中文写的（如「买牛奶」「取快递」）。
     */
    fun isCodeLikeText(text: String): Boolean {
        val raw = text.trim()
        if (raw.isBlank()) return false
        // 取件码形状放行：那是「待取」的数据
        if (PICKUP_SHAPE.matches(raw)) return false
        // 含汉字 → 是自然语言待办
        if (raw.any { it in '\u4E00'..'\u9FFF' }) return false
        // 必须含数字，且整体像一个码
        if (!raw.any { it.isDigit() }) return false
        return CODE_TOKEN.matches(raw)
    }

    /** 给用户看的中文提示 */
    const val OUTBOUND_HINT: String = "这是出库码，已忽略（出库码请点【出库码】按钮）"

    /** 代码型文本（单号 / 条码等）的提示 */
    const val CODE_LIKE_HINT: String = "未识别到取件码，代码类文本不添加待办"
}
