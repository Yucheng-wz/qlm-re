package com.hnu.pickupassistant.util

/**
 * 快递运单号识别（只做判定，不做入库）。
 *
 * 规则来自产品需求：
 *  - 取件码优先级最高：文本里出现 `数字-数字` / `数字-数字-数字`（横线兼容空格、全角），
 *    或出现「取件码 / 驿站 / 柜 / 格口」等词，一律不判为单号，交给原有取件码逻辑；
 *  - 强匹配：已知快递公司前缀（SF / YT / JT / JD / EMS / YZ / ZJS / DB…）+ 数字；
 *  - 疑似：纯数字且长度 10~20 位 —— 无法判定快递公司，交用户手动确认，避免误跳转；
 *  - 其它：不判定，继续走待办逻辑。
 */
object TrackingNumberDetector {

    enum class Level { STRONG, SUSPECT }

    data class Result(
        /** 清洗后的单号（大写、去掉空格/横线、纠正形近字符） */
        val number: String,
        val level: Level,
        val courier: String
    )

    /** 取件码：1~3 位数字 - 1~3 位数字（可选再 - 1~4 位数字），横线兼容空格 / 全角 */
    private val PICKUP_CODE = Regex("""\d{1,3}\s*[-—－]\s*\d{1,3}(?:\s*[-—－]\s*\d{1,4})?""")

    /** 出现这些词就按「取件码文本」处理，不抢单号逻辑 */
    private val PICKUP_WORDS = listOf(
        "取件码", "取货码", "提货码", "提货号", "取件号", "签收码",
        "驿站", "号架", "格口", "快递柜", "已到站", "待取件"
    )

    /** 前缀型单号：清洗后整体匹配 */
    private val PREFIX_RULES: List<Pair<Regex, String>> = listOf(
        Regex("^SF\\d{10,16}$") to "顺丰",
        Regex("^YT\\d{10,16}$") to "圆通",
        Regex("^JT\\d{10,16}$") to "极兔",
        Regex("^JD[A-Z]{0,3}\\d{9,16}$") to "京东",
        Regex("^EMS\\d{9,16}$") to "EMS",
        Regex("^YZ\\d{10,16}$") to "邮政",
        Regex("^ZJS\\d{9,16}$") to "宅急送",
        Regex("^DB\\d{9,16}$") to "德邦",
        // 其它两字母前缀 + 数字（国际件、联邦等），长度足够才认
        Regex("^[A-Z]{2}\\d{9,16}$") to "快递"
    )

    /**
     * @return null 表示「不是单号，按原有取件码 / 待办逻辑处理」
     */
    fun detect(raw: String): Result? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 1) 取件码优先：命中取件码格式或取件相关词，直接让位给原有逻辑
        if (PICKUP_CODE.containsMatchIn(text)) return null
        if (PICKUP_WORDS.any { text.contains(it) }) return null

        val normalized = normalize(text)
        if (normalized.length < 8) return null

        // 2) 已知前缀：可直接跳转
        for ((regex, courier) in PREFIX_RULES) {
            if (regex.matches(normalized)) {
                return Result(normalized, Level.STRONG, courier)
            }
        }

        // 3) 纯数字长单号：长度模糊、无法判定快递公司 → 让用户确认（防止把手机号/随手数字当单号）
        if (normalized.length in 10..20 && normalized.all { it.isDigit() }) {
            return Result(normalized, Level.SUSPECT, "")
        }
        return null
    }

    /**
     * 清洗：大写 → 去掉空格 / 横线 / 下划线 / 斜杠 → 纠正形近字符。
     * 形近处理顺序有讲究：先把 J7→JT、Y7→YT 还原成前缀，再把 l/I→1、O→0 归一到数字。
     */
    fun normalize(raw: String): String {
        var s = raw.uppercase().replace(Regex("""[\s\-—－_/]"""), "")
        s = s.replace("J7", "JT").replace("Y7", "YT")
        s = s.replace('L', '1').replace('I', '1').replace('O', '0')
        return s
    }
}
