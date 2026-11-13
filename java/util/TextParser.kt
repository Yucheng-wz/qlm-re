package com.hnu.pickupassistant.util

import com.hnu.pickupassistant.ocr.PickupRegexRules

/**
 * 取件文本解析：从取件码相关文本中识别「快递公司名称」与「取件地址关键信息」。
 *
 * 文本导入与图片 OCR 导入共用本解析器——OCR 也是先把图片转成文字，
 * 再走同一套「取件码 + 公司 + 地址」识别，因此两类入口天然支持同一功能。
 *
 * 本次迭代：快递公司改为「品牌文字优先、单号前缀兜底」（需求 8-1），
 * 单号前缀匹配前先做 OCR 形近字纠正（J7→JT、Y7→YT）并容忍空格 / 横线。
 */
object TextParser {

    /**
     * 快递公司关键词库：显示用的两字名 → 可识别的关键词（含 Logo 上的英文/品牌写法、全称）。
     * 需求关键词：
     *   极兔=极兔/极兔速递/J&T，邮政=中国邮政/邮政/CHINA POST，
     *   圆通=圆通/圆通速递，中通=中通/中通快递，申通=申通/申通快递，
     *   韵达=韵达/韵达快递，顺丰=顺丰/顺丰速运，京东=京东/京东物流，
     *   百世=百世/百世快递，天天=天天快递。
     * 附加：运单号前缀 JT/YT/SF/JD/ZTO/STO/DP/EMS 仍作为最强承运方信号。
     */
    private val COURIER_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "极兔速递" to listOf("J&T", "极兔速递", "极兔快递", "极兔"),
        "邮政" to listOf("CHINA POST", "CHINAPOST", "中国邮政", "邮政速递", "邮政快递", "邮政包裹", "邮政"),
        "圆通快递" to listOf("圆通速递", "圆通快递", "圆通"),
        "中通快递" to listOf("中通快递", "中通速递", "中通"),
        "申通快递" to listOf("申通快递", "申通速递", "申通"),
        "韵达快递" to listOf("韵达快递", "韵达速递", "韵达"),
        "顺丰" to listOf("顺丰速运", "顺丰快递", "顺丰"),
        "京东物流" to listOf("京东物流", "京东快递", "京东"),
        "百世快递" to listOf("百世快递", "百世速递", "百世"),
        "天天快递" to listOf("天天快递", "天天"),
        "德邦快递" to listOf("德邦快递", "德邦物流", "德邦"),
        "优速快递" to listOf("优速快递", "优速"),
        "丰网速运" to listOf("丰网速运", "丰网"),
        "跨越速运" to listOf("跨越速运", "跨越")
    )

    /** 展平成「关键词 → 两字名」，长词优先（避免「邮政」抢在「中国邮政」前命中） */
    private val COURIER_RULES: List<Pair<String, String>> = COURIER_KEYWORDS
        .flatMap { (name, words) -> words.map { it.uppercase() to name } }
        .sortedByDescending { it.first.length }

    /** 地址类型结尾词：用来在文本中定位「取件地址」片段 */
    private val ADDRESS_TAILS = listOf(
        "驿站", "代收点", "自提点", "快递", "邮政", "支局", "超市", "便利店",
        "服务中心", "快递柜", "营业部", "快递站", "包裹站"
    )

    /** 地址有效性关键词：识别结果必须至少含一个，否则视为 OCR 噪声丢弃（如「属号码」） */
    private val ADDRESS_VALIDATOR = Regex(
        "路|街|道|巷|楼|驿站|快递|邮政|超市|支局|代收|自提|便利|柜|营业|店|所|对面"
    )

    /** 界面噪声词：OCR 会把按钮/标签文字识别进来，统一清掉 */
    private val UI_NOISE = listOf(
        "展开", "收起", "∨", "复制", "拨打电话", "分享取件", "号码保护",
        "退换货", "订阅提醒", "查看全部", "导航"
    )

    /**
     * 识别快递公司名称（两字）；识别不到返回空串。
     *
     * 只从「传入的这一段文本」里找关键词，绝不复用其它条目的结果：
     * 找不到任何关键词就返回空串，由调用方把该条快递字段留空。
     *
     * 优先级：
     *   0) 运单号前缀（最强承运方信号，不受站点名与 OCR 错字干扰）；
     *   1) Logo 专属文字：J&T → 极兔、CHINA POST / 中国邮政 → 邮政；
     *   2) 公司名 + 速递/速运/物流/快运（承运方专属后缀，允许空白容忍 OCR 拆字）；
     *   3) 公司名 + 快递，且前面不是汉字（排除「云集路申通快递」类地名+快递的站点名）；
     *   4) 扩展关键词库（长词优先）：极兔/极兔速递/J&T、中国邮政/邮政/CHINA POST、
     *      圆通速递、中通快递、申通快递、韵达快递、顺丰速运、京东物流、百世快递、天天快递…
     */
    /** 两字简称 → 需求给定的展示名（与 PickupRegexRules 的别名表保持一致） */
    private fun courierFull(short: String): String = when (short) {
        "极兔" -> "极兔速递"
        "圆通" -> "圆通快递"
        "中通" -> "中通快递"
        "申通" -> "申通快递"
        "韵达" -> "韵达快递"
        "京东" -> "京东物流"
        "德邦" -> "德邦快递"
        "百世" -> "百世快递"
        "天天" -> "天天快递"
        "优速" -> "优速快递"
        "丰网" -> "丰网速运"
        "跨越" -> "跨越速运"
        else -> short
    }

    fun detectCourier(text: String): String {
        if (text.isBlank()) return ""
        // 1) Logo 专属英文 / 中文全称（大小写不敏感，容忍全角 & 与任意空白）
        val upper = text.uppercase().replace("＆", "&").replace("﹠", "&")
        if (Regex("J\\s*&\\s*T").containsMatchIn(upper)) return "极兔速递"
        if (Regex("CHINA\\s*POST").containsMatchIn(upper) || upper.contains("中国邮政")) return "邮政"
        // 2) 承运方专属后缀：XX 速递 / 速运 / 物流 / 快运（允许少量空白）
        Regex("(京东|顺丰|中通|圆通|韵达|申通|极兔|邮政|德邦|天天|百世|优速|丰网|跨越)\\s*(?:速递|速运|物流|快运)")
            .find(text)?.let { return courierFull(it.groupValues[1]) }
        // 3) 公司名 + 快递（前面不能是汉字，排除「云集路申通快递」类站点名）
        Regex("(?<![\\u4e00-\\u9fa5])(京东|顺丰|中通|圆通|韵达|申通|极兔|邮政|德邦|天天|百世|优速|丰网|跨越)快递")
            .find(text)?.let { return courierFull(it.groupValues[1]) }
        // 4) 扩展关键词库：长词优先逐条匹配（含 Logo 英文、公司全称、裸两字名）
        val compact = upper.replace(Regex("\\s+"), "")
        for ((keyword, name) in COURIER_RULES) {
            if (upper.contains(keyword) || compact.contains(keyword.replace(" ", ""))) return name
        }
        // 5) 最后才用运单号前缀兜底（需求 8-1：品牌文字优先级高于单号正则）；
        //    先纠正 OCR 形近字 J7→JT / Y7→YT，并容忍前缀与数字间的空格、横线
        val normalized = PickupRegexRules.fixTrackingOcr(text)
        val waybill = Regex(
            "(?:^|[^A-Za-z])(JT|YT|SF|JD|ZTO|STO|DP|EMS)[\\s\\-]?\\d{8,}",
            RegexOption.IGNORE_CASE
        ).find(normalized)
        if (waybill != null) {
            return when (waybill.groupValues[1].uppercase()) {
                "JT" -> "极兔速递"
                "YT" -> "圆通快递"
                "SF" -> "顺丰"
                "JD" -> "京东物流"
                "ZTO" -> "中通快递"
                "STO" -> "申通快递"
                "DP" -> "德邦快递"
                "EMS" -> "EMS"
                else -> ""
            }
        }
        return ""
    }

    /** 识别取件地址关键信息；识别不到返回空串 */
    fun detectAddress(text: String): String {
        if (text.isBlank()) return ""
        val raw = extractAddressRaw(text) ?: return ""
        val cleaned = cleanAddress(raw)
        if (cleaned.length !in 2..20) return ""
        // 有效性验证：不含任何地标/类型词的（OCR 噪声片段）直接丢弃
        if (!ADDRESS_VALIDATOR.containsMatchIn(cleaned)) return ""
        return cleaned
    }

    /**
     * 匹配用户自定义常用驿站地址；命中返回规范化站点名，未命中返回 null。
     *
     * 自定义站点名来自用户本人维护，优先于启发式识别：
     *   1) 文本中直接出现自定义站点名（最可靠）；
     *   2) 与启发式识别到的地址「一致」（互相包含）时，取用户自定义的规范名。
     */
    fun matchCustomStation(text: String, customStations: List<String>): String? {
        if (text.isBlank() || customStations.isEmpty()) return null
        // 1) 文本直接包含站点名
        for (station in customStations) {
            val s = station.trim()
            if (s.isEmpty()) continue
            if (text.contains(s)) return s
        }
        // 2) 与启发式识别地址做一致性比对（互相包含）
        val detected = detectAddress(text)
        if (detected.isNotEmpty()) {
            for (station in customStations) {
                val s = station.trim()
                if (s.isEmpty()) continue
                if (detected.contains(s) || s.contains(detected)) return s
            }
        }
        return null
    }

    /** 提取地址候选片段，按优先级：「地址：」（排除收货地址）→「|」分隔 → 含类型词的短语 */
    private fun extractAddressRaw(text: String): String? {
        // 1) 「地址：」后的内容——必须排除「收货地址」（那是买家自己的地址，不是取件点）
        Regex("(?<!收货)(?:取货|取件|自提|驿站)?地址[：:＝=]\\s*([^\\n|，,；;。！!]+)")
            .find(text)
            ?.let { return it.groupValues[1].trim() }

        // 2) 竖线「|」分隔：通常是「站点名 | 详细地址」，取后段并去掉与前半重复的乡镇前缀
        if (text.contains('|')) {
            val parts = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val head = parts.first()
                val tail = parts.last()
                // 前后片段开头的公共前缀（如「刘桥」）即乡镇名，从 tail 中去掉
                val common = commonPrefixLen(head, tail)
                val stripped = if (common in 2..4 && tail.length - common >= 2) {
                    tail.substring(common).trim()
                } else {
                    tail
                }
                if (stripped.length >= 2) return stripped
            }
        }

        // 3) 兜底：取含类型词的最长短语
        for (tail in ADDRESS_TAILS) {
            val idx = text.indexOf(tail)
            if (idx < 0) continue
            val end = idx + tail.length
            val after = text.substring(end).takeWhile { it !in "，,；;。|\\n" }
            val right = end + after.length
            val head = text.substring(0, idx)
            val start = head.lastIndexOfAny(charArrayOf('|', '，', ',', '；', ';', '。', '\n', '：', ':')) + 1
            val seg = text.substring(start, right).trim()
            if (seg.length >= 2) return seg
        }
        return null
    }

    /** 清理地址：去空白与界面噪声、去品牌词、去方位词尾「头」、去乡镇/区县/校区前缀 */
    private fun cleanAddress(addr: String): String {
        var s = addr.trim()
        // 去所有空白（OCR 拆字噪声；中文地址本身不含空格）
        s = s.replace(Regex("\\s+"), "")
        // 地址后常紧跟「取件码 4-02-213」，把关键字及其后的数字一起截掉，避免混进备注
        s = s.replace(Regex("(取件码|取码|提货码|自提码|包裹码|提取码).*$"), "")
        // 去界面噪声词（按钮 / 标签文字被 OCR 识别进来，如「展开∨」「号码保护」）
        for (w in UI_NOISE) s = s.replace(w, "")
        // 去平台 / 品牌词
        for (w in listOf("菜鸟", "妈妈", "多多", "丰巢", "兔喜")) s = s.replace(w, "")
        // 「北头 / 南头 / 西头 / 东头」→「北 / 南 / 西 / 东」
        s = s.replace(Regex("([北南西东])头"), "$1")
        // 去开头的乡镇 / 区县 / 校区前缀（如「刘桥镇」「淮师大滨湖校区」）
        s = s.replace(Regex("^[\\u4e00-\\u9fa5]{0,8}(镇|乡|县|区|市|校区|大学|学院)"), "")
        // 去开头残留的连接符、结尾残留的展开箭头 / 小写 v
        s = s.trimStart('的', '-', '—', '·', '~', '～')
        s = s.trimEnd('v', 'V', '∨')
        return s.trim()
    }

    private fun commonPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
