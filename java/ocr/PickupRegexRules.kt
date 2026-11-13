package com.hnu.pickupassistant.ocr

/**
 * 「取了吗」识图取件码 —— 正则规则、关键词库与 OCR 纠错（纯 Kotlin，无 Android 依赖，方便单测）。
 *
 * 本版针对真实截图（菜鸟详情页 / 菜鸟待取件列表 / 妈妈驿站多包裹列表）做了这些增强：
 *  - 取件码兼容两段 `706-48`、三段 `3-7-2593`，容忍全角横杠与横杠两侧空格，
 *    并排除 `09:00-19:30` 这类时间区间；
 *  - 物流单号分「字母前缀」与「长串纯数字」两类，覆盖 YT / JT / 984 / 465 等真实号段；
 *  - 快递公司改为「别名 → 规范名」映射，长词优先（极兔速递 → 极兔、丹鸟快递 → 丹鸟），
 *    并补上需求要求的 拼多多 / 丹鸟 / 京东；
 *  - 驿站关键词补全（菜鸟驿站 / 妈妈驿站代收点 / 兔喜生活超市 / 多多代收点 / 丰巢 …）；
 *  - 新增 OCR 形近字纠正（镜→镇、泊→沱、菜乌→菜鸟 …）与行尾噪声裁剪（电话 / 复制 / 分享）。
 *
 * @author 焱川 (by 焱川)
 */
object PickupRegexRules {

    // ========================================================================
    // 一、取件码
    // ========================================================================

    /** 需求给出的原始正则，保留作对照：`\d+-\d+(-\d+)*` */
    val PICKUP_CODE_LITERAL = Regex("""\d+-\d+(-\d+)*""")

    /**
     * 实际使用的取件码正则（对原始正则做 OCR 容错增强）：
     *  - 兼容 `-` / `－` / `—` / `–` 四种横杠，以及横杠两侧空格；
     *  - 货架号 1~4 位、层号 1~4 位、可选尾号 1~6 位（前导零保留）；
     *  - 前后否定环视，避免把手机号、订单号、日期中间的一截误判成取件码。
     *
     * 兼容样例：706-48 / 3-7-2593 / 16-7-3597 / 50-5-7691 / 27-5-1444 / 6-04-406
     */
    val PICKUP_CODE = Regex(
        """(?<![\d\-－—–])(\d{1,4})\s*[\-－—–]\s*(\d{1,4})(?:\s*[\-－—–]\s*(\d{1,6}))?(?![\d\-－—–])"""
    )

    /** 把匹配结果规范化成统一的半角横杠格式 */
    fun normalizeCode(match: MatchResult): String {
        val parts = listOfNotNull(
            match.groupValues.getOrNull(1),
            match.groupValues.getOrNull(2),
            match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
        )
        return parts.joinToString("-")
    }

    /**
     * OCR 把短横线识别成空格时还原：「2 4 3665」→「2-4-3665」。
     * 只处理三段、且三段数字总位数 ≥5 的组合，避免「1 2 3」这类噪声被拼成假码。
     */
    private val SPACED_CODE = Regex(
        """(?<![\d\-－—–])(\d{1,4})[ \t]+(\d{1,4})[ \t]+(\d{1,6})(?![\d\-－—–])"""
    )

    fun restoreDashedCodes(text: String): String =
        SPACED_CODE.replace(text) { match ->
            val (a, b, c) = match.destructured
            if (a.length + b.length + c.length >= 5) "$a-$b-$c" else match.value
        }

    /**
     * 在文本中找第一个「像取件码」的匹配，跳过时间区间里的数字。
     *
     * 为什么需要：`周一至周日09:00-19:30` 会被 `\d+-\d+` 命中成 `00-19`。
     * 判定方式是看横杠两端是否紧邻「数字+冒号」的时间结构；
     * `取件码：706-48` 这种中文标签冒号不会被误杀（冒号前是汉字）。
     */
    fun findPickupCode(text: String, fromIndex: Int = 0): MatchResult? {
        for (match in PICKUP_CODE.findAll(text)) {
            if (match.range.first < fromIndex) continue
            if (isTimeLike(text, match)) continue
            return match
        }
        return null
    }

    /** 匹配串是否处在 `09:00-19:30` 这类时间结构里 */
    private fun isTimeLike(text: String, match: MatchResult): Boolean {
        val start = match.range.first
        val end = match.range.last
        val before1 = if (start > 0) text[start - 1] else null
        val before2 = if (start > 1) text[start - 2] else null
        if ((before1 == ':' || before1 == '：') && before2?.isDigit() == true) return true
        val after1 = if (end + 1 < text.length) text[end + 1] else null
        val after2 = if (end + 2 < text.length) text[end + 2] else null
        if ((after1 == ':' || after1 == '：') && after2?.isDigit() == true) return true
        return false
    }

    // ========================================================================
    // 二、物流单号
    // ========================================================================

    /**
     * 字母前缀物流单号：需求点名的 YT / JT 为核心前缀，另补 ZT/ZTO 中通、STO 申通、
     * SF 顺丰、JD 京东、YD 韵达、DB 德邦、DN 丹鸟、EMS 邮政，后接 8~20 位数字。
     *
     * 容错点（需求 8）：
     *  - 忽略大小写（yt0724… 也能认）；
     *  - 前缀与数字之间、数字与数字之间的空格 / 横线都允许，
     *    匹配到后由 [cleanTrackingNo] 统一清掉，避免「YT 0724 5311 87007」漏识别；
     *  - OCR 形近字 J7→JT、Y7→YT 在 [detectTrackingNo] 里先纠正再匹配。
     */
    val TRACKING_LETTER = Regex(
        """(?i)(?<![A-Z0-9])(ZTO|STO|EMS|YT|ZT|JT|SF|JD|ST|YD|DB|DN)([\s\-]?\d){8,20}(?![A-Z0-9])"""
    )

    /** 长串纯数字快递单号：12~20 位（覆盖 465… / 984… 等真实号段），前后不能紧邻其它数字 */
    val TRACKING_DIGITS = Regex("""(?<!\d)\d{12,20}(?!\d)""")

    /**
     * OCR 常见形近字纠正：
     *  - T 看成 7：J7 → JT、Y7 → YT；
     *  - 数字串里的 l / I 看成 1、O 看成 0（只在两个数字之间才纠正，避免误伤英文）。
     */
    private val TRACKING_OCR_FIX: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)J7(?=[\s\-]?\d)""") to "JT",
        Regex("""(?i)Y7(?=[\s\-]?\d)""") to "YT",
        Regex("""(?<=[0-9])[lI](?=[0-9])""") to "1",
        Regex("""(?<=[0-9])[Oo](?=[0-9])""") to "0"
    )

    /** 纠正单号里的 OCR 形近字 */
    fun fixTrackingOcr(text: String): String =
        TRACKING_OCR_FIX.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /** 清掉单号里的空格与横线分隔符（需求：识别前先清除分隔符） */
    fun cleanTrackingNo(raw: String): String =
        raw.replace(Regex("""[\s\-－—–]"""), "")

    /**
     * 从一段文字里提取第一个物流单号，找不到返回空串。
     *
     * 注意：纯数字单号只返回号码本身，**绝不根据位数反推快递公司**
     * （需求 8-4：禁止单凭数字长度强判定公司），公司名只认品牌文字或字母前缀。
     */
    fun detectTrackingNo(text: String): String {
        if (text.isBlank()) return ""
        val normalized = fixTrackingOcr(text)
        val letter = TRACKING_LETTER.find(normalized)?.value
        if (!letter.isNullOrBlank()) {
            val cleaned = cleanTrackingNo(letter)
            // EMS 是承运商代号、不属于单号本身（单号是后面的纯数字）；
            // YT / JT / SF 等前缀属于单号的一部分，保持原样
            return if (cleaned.uppercase().startsWith("EMS")) cleaned.substring(3) else cleaned
        }
        return TRACKING_DIGITS.find(normalized)?.value?.let(::cleanTrackingNo) ?: ""
    }

    // ========================================================================
    // 三、关键词库
    // ========================================================================

    /**
     * 驿站关键词库（长词优先，避免「兔喜超市」抢走「兔喜生活超市」、
     * 「驿站」抢走「菜鸟驿站」这类更长更精确的名称）。
     */
    val STATION_KEYWORDS: List<String> = listOf(
        "菜鸟驿站",
        // 菜鸟小程序会把站点名截断成「…菜鸟驿」/「…菜鸟驿t」，
        // 补一个短关键词让组头仍能命中，随后由 OcrTextNormalizer 补全成「…菜鸟驿站」
        "菜鸟驿",
        "妈妈驿站代收点",
        "妈妈驿站",
        "兔喜生活超市",
        "兔喜代收点",
        "兔喜超市",
        "多多代收点",
        "丰巢快递柜",
        "快递超市",
        "菜鸟裹裹",
        "丰巢",
        "驿站"
    ).sortedByDescending { it.length }

    /**
     * 快递公司「别名 → 规范名」。按别名长度降序，保证「极兔速递」先于「极兔」命中。
     * 规范名用于备注与列表展示，别名覆盖 logo 文字、正文、单号前缀描述等写法。
     */
    data class CourierAlias(val keyword: String, val canonical: String)

    val COURIER_ALIASES: List<CourierAlias> = listOf(
        CourierAlias("邮政快递包裹", "邮政"),
        CourierAlias("中国邮政", "邮政"),
        CourierAlias("CHINA POST", "邮政"),
        CourierAlias("邮政EMS", "EMS"),
        CourierAlias("EMS", "EMS"),
        CourierAlias("邮政", "邮政"),
        CourierAlias("极兔速递", "极兔速递"),
        CourierAlias("极兔快递", "极兔速递"),
        CourierAlias("J&T", "极兔速递"),
        CourierAlias("极兔", "极兔速递"),
        CourierAlias("顺丰速运", "顺丰"),
        CourierAlias("顺丰快递", "顺丰"),
        CourierAlias("顺丰", "顺丰"),
        CourierAlias("京东物流", "京东物流"),
        CourierAlias("京东快递", "京东物流"),
        CourierAlias("京东", "京东物流"),
        CourierAlias("中通快递", "中通快递"),
        CourierAlias("中通速递", "中通快递"),
        CourierAlias("中通", "中通快递"),
        CourierAlias("圆通速递", "圆通快递"),
        CourierAlias("圆通快递", "圆通快递"),
        CourierAlias("圆通", "圆通快递"),
        CourierAlias("申通快递", "申通快递"),
        CourierAlias("申通速递", "申通快递"),
        CourierAlias("申通", "申通快递"),
        CourierAlias("韵达快递", "韵达快递"),
        CourierAlias("韵达速递", "韵达快递"),
        CourierAlias("韵达", "韵达快递"),
        CourierAlias("丹鸟快递", "丹鸟"),
        CourierAlias("丹鸟", "丹鸟"),
        CourierAlias("拼多多", "拼多多"),
        CourierAlias("百世快递", "百世快递"),
        CourierAlias("百世", "百世快递"),
        CourierAlias("德邦快递", "德邦快递"),
        CourierAlias("德邦", "德邦快递")
    ).sortedByDescending { it.keyword.length }

    /** 需求点名的快递品牌（用于文档 / 兜底匹配） */
    val COURIER_KEYWORDS: List<String> = listOf(
        "韵达", "圆通", "中通", "申通", "极兔", "邮政", "京东", "拼多多", "丹鸟", "顺丰"
    )

    /** 命中快递别名（含关键词与规范名），未命中返回 null */
    fun detectCourierAlias(text: String): CourierAlias? =
        COURIER_ALIASES.firstOrNull { text.contains(it.keyword, ignoreCase = true) }

    /** 从一行文字中检测快递公司，返回规范名；找不到返回空串 */
    fun detectCourier(text: String): String = detectCourierAlias(text)?.canonical ?: ""

    /**
     * 地址有效性提示词：一行文字至少命中一个，才可能是驿站地址。
     * 用于过滤 OCR 把按钮、时间、状态文字识别进来的噪声。
     */
    val ADDRESS_HINTS: List<String> = listOf(
        "省", "市", "区", "县", "镇", "乡", "村", "路", "街", "巷", "号", "栋", "幢",
        "单元", "楼", "层", "室", "苑", "花园", "小区", "公寓", "大厦", "广场", "中心",
        "店", "超市", "便利店", "学院", "大学", "学校", "宿舍", "门面", "商铺", "对面",
        "旁", "附近", "楼下", "入口", "出口", "校区", "园区", "地块", "门楼", "米",
        "往南", "往北", "往东", "往西", "向南", "向北", "向东", "向西"
    )

    /** 界面噪声词：这些行不参与地址 / 驿站判定 */
    val NOISE_WORDS: List<String> = listOf(
        "复制", "已取件", "待取件", "展开", "收起", "号码保护", "取件码", "取件",
        "查看更多", "查看详情", "确认取件", "一键取件", "扫码", "刷新", "今天",
        "昨天", "刚刚", "分钟前", "小时前", "已签收", "运输中", "派送中", "联系快递员",
        "拨打电话", "分享", "删除", "编辑", "备注", "全部", "筛选", "搜索",
        "找人代取", "实景找包裹", "好友代取", "查单号添加"
    )

    // ========================================================================
    // 四、OCR 形近字纠正 + 行尾噪声裁剪
    // ========================================================================

    /**
     * OCR 形近字纠正表（真实截图踩过的坑）：
     *  - 镇 → 镜：「烈山镇」被识别成「烈山镜」，必须纠正，否则地址与备注全错；
     *  - 沱 → 泊：「沱河东路」被识别成「泊河东路」；
     *  - 菜鸟 → 菜乌、兔喜 → 兔熹 / 兔囍、妈妈 → 妈蚂。
     * 只在明确的上下文里替换，避免误伤「眼镜店」「镜框」这类词。
     */
    private val OCR_FIX_RULES: List<Pair<Regex, String>> = listOf(
        Regex("镜(?![头框片子店])") to "镇",
        Regex("泊(?=[河东西南北路街桥道])") to "沱",
        Regex("菜乌") to "菜鸟",
        Regex("兔[熹囍嘻]") to "兔喜",
        Regex("[妈蚂][蚂妈]") to "妈妈",
        Regex("驿[占沾]") to "驿站"
    )

    /** 对整段 / 单行文本做 OCR 形近字纠正 */
    fun fixOcrTypos(text: String): String =
        OCR_FIX_RULES.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /**
     * 行尾噪声裁剪：OCR 会把同一视觉行右侧的按钮文字（电话 / 复制 / 分享 / 查看 …）
     * 拼到地址后面，例如「什贴门楼往南100米 电话」。这里循环削掉行尾噪声词。
     */
    private val TRAILING_NOISE: List<String> = listOf(
        "电话", "复制", "分享", "查看", "导航", "求助", "拨打", "联系", "更多",
        "展开", "收起", "定位", "去电", "一键取件", "查看详情", "找人代取", "实景找包裹"
    )

    fun stripTrailingNoise(text: String): String {
        var t = text.trim()
        var changed = true
        while (changed) {
            changed = false
            for (word in TRAILING_NOISE) {
                if (t.endsWith(word) && t.length > word.length + 2) {
                    t = t.removeSuffix(word).trim()
                    changed = true
                }
            }
        }
        return t.trim()
    }

    // ========================================================================
    // 五、字段检测（供 ParcelBlockParser / 旧调用方复用）
    // ========================================================================

    /** 判断一行是否明显是界面噪声 / 无有效信息 */
    fun isNoise(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return true
        if (PICKUP_CODE.containsMatchIn(t) && t.length <= 14) return true
        return NOISE_WORDS.any { t == it || (t.length <= 6 && t.contains(it)) }
    }

    /**
     * 从一行文字中检测驿站名称。真实的分层判定逻辑在 [ParcelBlockParser.extractStation]，
     * 这里保留一个薄封装供旧调用方使用，行为保持一致。
     */
    fun detectStation(text: String): String = ParcelBlockParser.extractStation(listOf(text))

    /**
     * 判断一行是否像地址：命中地址提示词、不是噪声、不是单号、不是纯取件码。
     */
    fun looksLikeAddress(text: String): Boolean {
        val t = text.trim()
        if (isNoise(t) || t.length < 4) return false
        if (TRACKING_LETTER.containsMatchIn(t)) return false
        if (PICKUP_CODE.containsMatchIn(t) && t.length <= 14) return false

        // 驿站标题本身不是地址：例如「兔喜超市」含「超市」，会被地址提示词误中。
        // 只排除「整行基本就是驿站名」的短行；若一行明显更长（如
        // 「取件地址：菜鸟驿站 望江西路 123 号」），仍然保留为有效地址。
        val stationHit = STATION_KEYWORDS.firstOrNull { t.contains(it) }
        if (stationHit != null && t.length <= stationHit.length + 6) return false

        return ADDRESS_HINTS.any { t.contains(it) }
    }

    /** 轻微清洗地址行：去掉首尾噪声标点、合并空白 */
    fun cleanAddress(text: String): String = text
        .replace(Regex("""^[\[\(（【]\s*"""), "")
        .replace(Regex("""[\]\)）】]\s*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""^[|｜:：,，.\-—–\s]+"""), "")
        .trim()

    /**
     * 驿站名有效性校验（需求：识别不到就不加，不许填垃圾）。
     *
     * 用来挡掉「+」「-」「123」「……」这类被误提取出来的串 ——
     * 宁可留空让用户手填，也不要把垃圾写进备注。
     */
    fun isPlausibleStationName(name: String): Boolean {
        val t = name.trim()
        if (t.length < 2) return false
        if (t.length > 30) return false
        // 必须含汉字（驿站名都是中文），纯符号 / 纯数字 / 纯字母都算噪声
        val hasCjk = t.any { it in '\u4E00'..'\u9FFF' }
        if (!hasCjk) return false
        // 全是标点或空格的直接丢
        if (t.none { it.isLetterOrDigit() }) return false
        return true
    }
}
