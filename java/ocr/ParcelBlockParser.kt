package com.hnu.pickupassistant.ocr

/**
 * 「取了吗」识图取件码 —— 按区块切分的 OCR 文本解析器（纯 Kotlin，可 JVM 单测）。
 *
 * ## 一、为什么要按区块切
 * 真实截图有三种完全不同的布局，写死坐标必挂：
 * ```
 * ① 菜鸟详情页（单包裹）
 *    韵达快递 465843480810859        ← 单号在最上面
 *    淮师大滨湖校区文源楼北菜鸟驿站    ← 驿站名带校区 / 楼栋前缀
 *    安徽省淮北市烈山区烈山镇沱河东路8号
 *    16-7-3597           取件码      ← 码在「取件码」标签前面
 *
 * ② 妈妈驿站多包裹列表
 *    妈妈驿站代收点 / 什贴镇什贴门楼往南100米兔喜超市
 *    取件码 706-48 / 圆通快递 YT0724531187007
 *    取件码 706-10 / 圆通快递 YT0724766525007
 *    支持退换货 兔喜生活超市 / 什贴门楼往南100米   ← 第二个驿站组头
 *    取件码 3-7-2593 / 韵达快递 465829330375829
 *
 * ③ 菜鸟待取件列表
 *    淮师大滨湖校区文源楼北菜鸟驿站
 *    7-3-1217 / 今日到站… / 极兔 JT5544316072358
 *    27-5-1397 / … / 邮政 9840831309908
 * ```
 *
 * ## 二、切分模型（语义 + 关键词，不用固定像素）
 *  - **驿站组头行** = 区块边界：遇到组头关闭上一区块、开启新区块；
 *  - **取件码行**（含「取件码」关键词，或整行只是一个码）= 记录锚点；
 *  - 组头区（区块起点 → 第一个锚点）只用来取「驿站 / 地址」；
 *  - 记录片段（锚点 → 下一个锚点）只用来取「取件码 / 公司 / 单号」；
 *  - **字段不跨区块**：某字段本区块没匹配到就留空，由弹窗手动补。
 *
 * ## 三、两个特殊兜底（都是真实截图逼出来的）
 *  1. 整张图只有 1 条快递时（详情页布局），允许用「第一个组头之上」的订单摘要行
 *     补快递公司 / 单号 —— 单包裹页的运单号就在最顶部，这是同一件包裹的信息；
 *  2. 取件码优先取「字号明显大于整图中位数」的那一行（截图里取件码是绿色加粗大号字），
 *     ML Kit 拿不到颜色，用 boundingBox 高度做字号代理。
 *
 * @author 焱川 (by 焱川)
 */
object ParcelBlockParser {

    /** 一行 OCR 文本 + 行高（像素），行高用于「大号字体优先」判定 */
    data class OcrRow(val text: String, val height: Int = 0)

    /** 一条快递记录的解析结果（字段缺失即为空串） */
    data class ParcelBlockRecord(
        val code: String = "",
        val station: String = "",
        val address: String = "",
        val courier: String = "",
        val trackingNo: String = ""
    )

    /** 一个驿站区块：组头文本 + 区块内若干条记录片段 */
    data class ParcelStationBlock(
        val headerLines: List<String>,
        val records: List<ParcelRecordBlock>
    )

    /** 一条记录片段：从本条「取件码」行到下一条「取件码」行之前（或区块末尾） */
    data class ParcelRecordBlock(val lines: List<String>)

    private data class RowRecord(val rows: List<OcrRow>)
    private data class RowBlock(val header: List<OcrRow>, val records: List<RowRecord>)

    // ========================================================================
    // 常量区
    // ========================================================================

    /** 组头关键词允许出现在行首的前 N 个字内（OCR 偶尔会在前面加角标） */
    private const val HEADER_KEYWORD_MAX_INDEX = 6

    /** 「行尾型」驿站名：关键词距离行尾允许的字数（如「…菜鸟驿站」后面跟「(北店)」） */
    private const val STATION_TAIL_MAX_CHARS = 12

    /** 驿站整行长度上限，超过基本是地址 */
    private const val STATION_LINE_MAX_CHARS = 32

    /** 行首型驿站名向后扩展上限（字数） */
    private const val ENTITY_MAX_EXTRA_CHARS = 8

    /** 驿站名扩展的停止字符 */
    private const val ENTITY_STOP_CHARS = "省市区县镇乡村路街巷号楼栋单元室米往南北东西,，。:：|｜<>"

    /** 取件码行高超过「整图行高中位数 × 该系数」视为大号字体 */
    private const val BIG_TEXT_RATIO = 1.22f

    /**
     * 强地址特征：出现任意一个，说明这行是「地址 / 路线描述」而不是驿站名。
     * 用于挡住「什贴镇什贴门楼往南100米兔喜超市」这种把门店名写在地址末尾的行。
     */
    private val STRONG_ADDRESS_MARKERS: List<String> = listOf(
        "往南", "往北", "往东", "往西", "向南", "向北", "向东", "向西",
        "号", "路", "街", "巷", "弄", "大道", "门楼", "附近", "对面",
        "楼下", "旁", "小区", "公寓", "大厦", "广场", "米"
    )

    /** 以这些词开头的行不是驿站名（地图气泡「当前在菜鸟驿站」、导航提示等） */
    private val NOISE_LINE_PREFIX: List<String> = listOf(
        "当前在", "距您", "导航", "前往", "查看", "去取件", "本店", "附近有", "已为您", "搜索"
    )

    /** 组头行前缀噪声（徽标文案、按钮文案），匹配前先削掉 */
    private val LEADING_NOISE: List<String> = listOf(
        "支持退换货", "七天无理由", "查看详情", "取件地址", "取货地址", "驿站地址", "地址"
    )

    /** 判定「这行是快递公司行」的强化词 */
    private val COURIER_STRONG_WORDS: List<String> = listOf(
        "快递", "速递", "速运", "物流", "包裹", "速达"
    )

    /** 界面噪声词（地址判定时排除） */
    private val NOISE_WORDS: List<String> = listOf(
        "复制", "已取件", "待取件", "展开", "收起", "号码保护", "查看更多",
        "查看详情", "确认取件", "一键取件", "扫码", "刷新", "电话", "删除", "忽略",
        "支持退换货", "编辑", "备注", "全部", "筛选", "搜索", "找人代取", "实景找包裹"
    )

    // ========================================================================
    // 一、对外入口
    // ========================================================================

    /**
     * 纯文本入口：输入 OCR 全文，输出快递对象列表。
     * @param customStations 用户在设置里维护的「常用驿站」名，OCR 命中即作为驿站名
     */
    fun parse(ocrText: String, customStations: List<String> = emptyList()): List<ParcelBlockRecord> =
        parseRows(tokenize(ocrText.split(Regex("""[\r\n]+"""))), customStations)

    /** 行列表入口：供单元测试与无坐标场景使用 */
    fun parseLines(rawLines: List<String>, customStations: List<String> = emptyList()): List<ParcelBlockRecord> =
        parseRows(tokenize(rawLines), customStations)

    /**
     * 带行高入口：OcrTextSorter 的视觉行直接传进来，行高用于「大号字体优先」。
     */
    fun parseRows(rawRows: List<OcrRow>, customStations: List<String> = emptyList()): List<ParcelBlockRecord> {
        // ① 每行先做 OCR 文本归一化（全角 / 异形分隔符 / 码块形近字母），保留行高
        val normalized = rawRows
            .map { OcrRow(OcrTextNormalizer.normalizeLine(it.text).trim(), it.height) }
            .filter { it.text.isNotEmpty() }
        // ② 已签收 / 历史区域熔断：分界线以下的行全部不参与待取解析，
        //    避免「已签收 11-09」被当两段码、下方已签收包裹的公司串到上方
        val cutoff = OcrTextNormalizer.signedCutoffIndex(normalized.map { it.text })
        val rows = if (cutoff != null) normalized.subList(0, cutoff) else normalized
        if (rows.isEmpty()) return emptyList()

        // 第一个组头之上的行（详情页的订单摘要区），只在「整图仅 1 条快递」时启用
        val headerIndices = rows.indices.filter { isStationHeader(rows[it].text, customStations) }
        val leadingRows = if (headerIndices.isEmpty()) emptyList() else rows.subList(0, headerIndices.first())

        val bigThreshold = largeTextThreshold(rows)
        val result = mutableListOf<ParcelBlockRecord>()

        for (block in splitRows(rows, headerIndices)) {
            val headerTexts = block.header.map { it.text }
            val blockStation = extractStation(headerTexts, customStations)
            val blockAddress = extractAddress(headerTexts)

            for (record in block.records) {
                val texts = record.rows.map { it.text }
                val code = extractCodeRows(record.rows, bigThreshold)
                if (code.isBlank()) continue

                // 每条记录先看自己片段里有没有驿站 / 地址（列表页里偶尔每条自带一行），
                // 没有才回落到本区块组头 —— 但绝不回看上一个区块
                val ownStation = extractStation(texts, customStations)
                val ownAddress = extractAddress(texts)

                val candidate = ParcelBlockRecord(
                    code = code,
                    station = ownStation.ifBlank { blockStation },
                    address = if (ownStation.isBlank()) {
                        blockAddress.ifBlank { ownAddress }
                    } else {
                        ownAddress.ifBlank { blockAddress }
                    },
                    courier = extractCourier(texts),
                    trackingNo = extractTrackingNo(texts)
                )
                if (result.none { it.code == candidate.code }) result.add(candidate)
            }
        }

        // 详情页兜底：只有一条快递时，顶部订单摘要行的公司 / 单号属于同一件包裹
        if (result.size == 1) {
            val only = result[0]
            if (only.courier.isBlank() || only.trackingNo.isBlank()) {
                val topTexts = leadingRows.map { it.text }
                result[0] = only.copy(
                    courier = only.courier.ifBlank { extractCourier(topTexts) },
                    trackingNo = only.trackingNo.ifBlank { extractTrackingNo(topTexts) }
                )
            }
        }
        return result
    }

    /**
     * 文本分割工具函数：把按阅读顺序排列的 OCR 行切成「驿站区块」（字符串版，供测试/调试）。
     */
    fun splitIntoBlocks(rawLines: List<String>, customStations: List<String> = emptyList()): List<ParcelStationBlock> {
        val rows = tokenize(rawLines)
        if (rows.isEmpty()) return emptyList()
        val headerIndices = rows.indices.filter { isStationHeader(rows[it].text, customStations) }
        return splitRows(rows, headerIndices).map { block ->
            ParcelStationBlock(
                headerLines = block.header.map { it.text },
                records = block.records.map { ParcelRecordBlock(it.rows.map { row -> row.text }) }
            )
        }
    }

    // ========================================================================
    // 二、切分实现
    // ========================================================================

    /** 统一预处理：文本归一化 + 已签收区域熔断 + 去首尾空白 + 丢空行 */
    private fun tokenize(rawLines: List<String>): List<OcrRow> {
        val normalized = rawLines.map { OcrTextNormalizer.normalizeLine(it).trim() }
        return OcrTextNormalizer.cutoffSignedSection(normalized)
            .filter { it.isNotEmpty() }
            .map { OcrRow(it, 0) }
    }

    /**
     * 核心切分：组头定区块、取件码定记录。
     * 没有组头的文本也形成「无组头区块」，保证纯取件码截图仍能入库。
     */
    private fun splitRows(rows: List<OcrRow>, headerIndices: List<Int>): List<RowBlock> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        if (headerIndices.isEmpty()) {
            ranges.add(0 to rows.size)
        } else {
            if (headerIndices.first() > 0) ranges.add(0 to headerIndices.first())
            headerIndices.forEachIndexed { i, start ->
                ranges.add(start to (headerIndices.getOrNull(i + 1) ?: rows.size))
            }
        }

        val blocks = mutableListOf<RowBlock>()
        for ((start, end) in ranges) {
            val anchors = (start until end).filter { isCodeAnchorLine(rows[it].text) }
            if (anchors.isEmpty()) continue
            val header = rows.subList(start, anchors.first())
            val records = anchors.mapIndexed { i, anchor ->
                RowRecord(rows.subList(anchor, anchors.getOrNull(i + 1) ?: end))
            }
            blocks.add(RowBlock(header, records))
        }
        return blocks
    }

    // ========================================================================
    // 三、字段提取（对外，均只接收「本区块 / 本记录」的文本）
    // ========================================================================

    /**
     * 取件码提取（无行高版本，供测试与纯文本场景）。
     */
    fun extractCode(recordLines: List<String>): String =
        extractCodeRows(recordLines.map { OcrRow(it, 0) }, 0)

    /**
     * 取件码提取（带行高）。
     * 优先级：① 含「取件码」标签的行（标签在前或在后都行）→ ② 行高明显偏大的行 → ③ 第一个合法码。
     * 日期串（2026-11-10 / 1-2-2026）一律排除。
     */
    private fun extractCodeRows(rows: List<OcrRow>, bigThreshold: Int): String {
        // ① 有「取件码」标签的行：标签可能在码前面（取件码 706-48），也可能在码后面（16-7-3597 取件码）
        for (row in rows) {
            // 签收 / 退回行里的「11-09」是日期，绝不能当两段式取件码
            if (OcrTextNormalizer.isSignedOrReturnedLine(row.text)) continue
            val kwIndex = listOf("取件码", "取件碼")
                .map { row.text.indexOf(it) }
                .maxOrNull() ?: -1
            if (kwIndex < 0) continue
            val after = PickupRegexRules.findPickupCode(row.text, kwIndex + 1)
            val anywhere = after ?: PickupRegexRules.findPickupCode(row.text)
            if (anywhere != null) {
                val code = PickupRegexRules.normalizeCode(anywhere)
                if (!isDateLike(code)) return code
            }
        }

        // ② / ③ 无标签：先收集所有候选，优先大号字体那一行
        val candidates = rows.mapNotNull { row ->
            if (OcrTextNormalizer.isSignedOrReturnedLine(row.text)) return@mapNotNull null
            val match = PickupRegexRules.findPickupCode(row.text) ?: return@mapNotNull null
            val code = PickupRegexRules.normalizeCode(match)
            if (isDateLike(code)) null else row to code
        }
        if (candidates.isEmpty()) return ""
        if (bigThreshold > 0) {
            candidates.firstOrNull { it.first.height >= bigThreshold }?.let { return it.second }
        }
        return candidates.first().second
    }

    /**
     * 驿站名称：在本区块组头区里挑「最完整」的一个驿站名。
     *
     * 支持三种真实写法：
     *  1. 行首型：「妈妈驿站代收点」→ 取关键词并向后扩展到地址特征字前；
     *  2. 行尾型：「淮师大滨湖校区文源楼北菜鸟驿站」→ 整行就是全称，必须完整保留，
     *     不能只输出「驿站」或丢掉前缀；
     *  3. logo 合并型：「菜鸟驿站 淮师大滨湖校区文源楼北菜鸟驿站」→ 取最后一个空格分段，
     *     丢掉左侧 logo 文案。
     *
     * 识别不到就返回空串（绝不写「+」这类垃圾进备注），交给用户手填。
     */
    fun extractStation(
        headerLines: List<String>,
        customStations: List<String> = emptyList()
    ): String =
        headerLines
            .mapNotNull { matchStationName(it, customStations).takeIf { name -> name.isNotBlank() } }
            // 驿站名精炼：剥促销角标、滤地图气泡、把「…菜鸟驿」截断补全成「…菜鸟驿站」
            .map { OcrTextNormalizer.refineStationName(it) }
            .filter { it.isNotBlank() && PickupRegexRules.isPlausibleStationName(it) }
            .maxByOrNull { it.length }
            .orEmpty()

    /**
     * 地址：驿站名下面那行长文本就是驿站物理地址。
     * 先看「驿站名所在行的下一行」，不是地址再在组头区里挑最长的地址行；
     * 结果统一做 OCR 形近字纠正（镜→镇、泊→沱）与行尾噪声裁剪（… 电话）。
     */
    fun extractAddress(headerLines: List<String>): String {
        if (headerLines.isEmpty()) return ""
        val stationName = extractStation(headerLines)
        val cleaned = headerLines.map {
            PickupRegexRules.stripTrailingNoise(
                PickupRegexRules.fixOcrTypos(cleanAddress(it))
            )
        }.map { line ->
            // OCR 偶尔把「驿站名 + 地址」并成一行（妈妈驿站代收点 什贴镇…往南100米），
            // 地址里不该再重复一遍驿站名
            if (stationName.isNotBlank() && line.startsWith(stationName) && line.length > stationName.length) {
                line.removePrefix(stationName).trimStart(' ', '，', ',', '：', ':', '｜', '|')
            } else {
                line
            }
        }

        val stationIdx = headerLines.indices.lastOrNull { matchStationName(headerLines[it]).isNotBlank() } ?: -1
        if (stationIdx >= 0) {
            for (i in stationIdx + 1 until cleaned.size) {
                if (looksLikeAddress(cleaned[i])) return cleaned[i]
            }
        }
        val fallback = cleaned.filter { looksLikeAddress(it) }.maxByOrNull { it.length }.orEmpty()
        // 合并行场景：地址原本就在驿站名的同一行，上面循环取不到，退回该行去掉驿站名后的剩余部分
        if (fallback.isBlank() && stationIdx >= 0) {
            val rest = cleaned[stationIdx]
            if (rest.isNotBlank() && looksLikeAddress(rest)) return rest
        }
        return fallback
    }

    /**
     * 快递公司：先认「logo / 品牌文字」，认不到再用单号前缀兜底。
     * 每条记录单独调用，绝不整条列表共用同一个公司名。
     */
    fun extractCourier(recordLines: List<String>): String {
        for (line in recordLines) {
            val brand = brandFromLine(line)
            if (brand.isNotBlank()) return brand
        }
        return courierFromTracking(extractTrackingNo(recordLines))
    }

    /**
     * 物流单号：优先取「快递公司文字后方」的单号（需求规则），否则取本片段第一个单号。
     */
    fun extractTrackingNo(recordLines: List<String>): String {
        val usable = recordLines.filterNot { OcrTextNormalizer.isMaskedPhone(it) }
        val courierIdx = usable.indexOfFirst { brandFromLine(it).isNotBlank() }
        if (courierIdx >= 0) {
            for (i in courierIdx until usable.size) {
                val no = PickupRegexRules.detectTrackingNo(usable[i])
                if (no.isNotBlank()) return no
            }
        }
        for (line in usable) {
            val no = PickupRegexRules.detectTrackingNo(line)
            if (no.isNotBlank()) return no
        }
        return ""
    }

    // ========================================================================
    // 四、内部判定
    // ========================================================================

    /** 是否为驿站组头行（= 区块边界） */
    private fun isStationHeader(line: String, customStations: List<String> = emptyList()): Boolean =
        matchStationName(line, customStations).isNotBlank()

    /**
     * 从一行里解析驿站全称，解析不出返回空串。
     */
    private fun matchStationName(rawLine: String, customStations: List<String> = emptyList()): String {
        // ① 用户自定义「常用驿站」最优先：整行或任一竖线分段里出现即命中
        val customHit = customStations
            .filter { it.isNotBlank() && PickupRegexRules.isPlausibleStationName(it) }
            .filter { rawLine.contains(it) }
            .maxByOrNull { it.length }
        if (customHit != null) return customHit.trim()

        val line = stripLeadingNoise(rawLine)
        if (line.isBlank()) return ""
        if (NOISE_LINE_PREFIX.any { line.startsWith(it) }) return ""

        // ② 竖线「|」分段（类型 D：「菜鸟驿站 | 淮师大…菜鸟驿站>」取竖线后面的全称），
        //    逐段解析后取最完整的一个
        val segments = line.split('|', '｜', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (segments.size > 1) {
            val fromSegments = segments
                .mapNotNull { matchStationNameInSingleLine(it).takeIf { name -> name.isNotBlank() } }
                .maxByOrNull { it.length }
            if (fromSegments != null) return fromSegments
        }
        return matchStationNameInSingleLine(line)
    }

    /** 单行（无竖线）驿站名解析 */
    private fun matchStationNameInSingleLine(line: String): String {

        val keyword = PickupRegexRules.STATION_KEYWORDS.firstOrNull { line.contains(it) } ?: return ""
        val firstIdx = line.indexOf(keyword)
        val lastIdx = line.lastIndexOf(keyword)
        val tail = line.length - (lastIdx + keyword.length)

        // ① 行尾型：关键词落在行尾附近，且整行不是路线 / 地址描述 → 整行（最后一段）就是全称
        if (tail <= STATION_TAIL_MAX_CHARS &&
            line.length <= STATION_LINE_MAX_CHARS &&
            !looksLikeRouteAddress(line)
        ) {
            val name = cleanStationName(lastSegment(line))
            if (name.isNotBlank()) return name
        }

        // ② 行首型：关键词在行首附近 → 向后扩展到地址特征字前
        if (firstIdx <= HEADER_KEYWORD_MAX_INDEX) {
            val name = cleanStationName(expandForward(line, firstIdx, firstIdx + keyword.length))
            if (name.isNotBlank()) return name
        }
        return ""
    }

    /** 削掉组头行前面的徽标 / 按钮文案与括号 */
    private fun stripLeadingNoise(raw: String): String {
        var text = raw.trim()
        text = text.trimStart(' ', '【', '[', '（', '(', '|', '｜', '：', ':', '-', '—', '•', '·')
        var changed = true
        while (changed) {
            changed = false
            for (word in LEADING_NOISE) {
                if (text.startsWith(word) && text.length > word.length) {
                    text = text.removePrefix(word).trimStart(' ', '：', ':', '【', '（', '(')
                    changed = true
                }
            }
        }
        return text.trim()
    }

    /**
     * 取最后一个「含汉字」的空格分段（丢掉左侧 logo 文案与行尾的「>」箭头等符号）。
     * 类型 D 的「菜鸟驿站 | 淮师大…菜鸟驿站 >」里，纯符号的「>」不能被当成名字。
     */
    private fun lastSegment(line: String): String =
        line.split(Regex("""\s+"""))
            .lastOrNull { part -> part.any { it in '\u4E00'.. '\u9FFF' } }
            ?: line

    /** 从关键词结束处向后扩展，遇到空白 / 地址特征字 / 上限即停 */
    private fun expandForward(line: String, start: Int, end: Int): String {
        var cursor = end
        var extra = 0
        while (cursor < line.length) {
            val ch = line[cursor]
            if (ch.isWhitespace() || ch in ENTITY_STOP_CHARS || extra >= ENTITY_MAX_EXTRA_CHARS) break
            cursor++
            extra++
        }
        return line.substring(start, cursor)
    }

    /** 清洗驿站名：去首尾标点与「>」箭头（类型 D 标签行常带） */
    private fun cleanStationName(name: String): String = name
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""^[|｜:：,，.\-—–•·<>《》\s]+"""), "")
        .replace(Regex("""[|｜,，.\-—–•·<>《》\s]+$"""), "")
        .trim()

    /** 这行是不是「地址 / 路线描述」（往南100米、8号、某路…），是则不能当驿站名 */
    private fun looksLikeRouteAddress(line: String): Boolean =
        STRONG_ADDRESS_MARKERS.any { line.contains(it) }

    /**
     * 是否为取件码锚点行：
     *  - 含「取件码」标签（标签可以在码前或码后）；
     *  - 或整行只有一个裸码（OCR 丢了标签的列表页）；
     * 排除物流单号行、日期行、纯按钮行。
     */
    private fun isCodeAnchorLine(line: String): Boolean {
        val text = line.trim()
        // 已签收 / 退回区的日期行不能成为包裹记录起点
        if (OcrTextNormalizer.isSignedOrReturnedLine(text)) return false
        val hasKeyword = text.contains("取件码") || text.contains("取件碼")
        val match = PickupRegexRules.findPickupCode(text)
        if (hasKeyword) {
            // 「查看取件码」「复制取件码」这类按钮不是记录起点
            if (text.contains("查看") || text.contains("复制") || text.contains("获取")) return false
            if (match != null) return !isDateLike(PickupRegexRules.normalizeCode(match))
            return text.length <= 8
        }
        if (match == null || text.length > 24) return false
        if (PickupRegexRules.TRACKING_LETTER.containsMatchIn(text)) return false
        if (PickupRegexRules.TRACKING_DIGITS.containsMatchIn(text)) return false
        return !isDateLike(PickupRegexRules.normalizeCode(match))
    }

    private fun looksLikeAddress(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        if (NOISE_WORDS.any { t == it || (t.length <= 6 && t.contains(it)) }) return false
        if (PickupRegexRules.TRACKING_LETTER.containsMatchIn(t)) return false
        if (PickupRegexRules.TRACKING_DIGITS.containsMatchIn(t)) return false
        if (PickupRegexRules.PICKUP_CODE.containsMatchIn(t) && t.length <= 14) return false
        val stationHit = PickupRegexRules.STATION_KEYWORDS.firstOrNull { t.contains(it) }
        if (stationHit != null && t.length <= stationHit.length + 4) return false
        return PickupRegexRules.ADDRESS_HINTS.any { t.contains(it) }
    }

    private fun cleanAddress(text: String): String = text
        .replace(Regex("""^[\[\(（【]\s*"""), "")
        .replace(Regex("""[\]\)）】]\s*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""^[|｜:：,，.\-—–\s]+"""), "")
        .trim()

    /**
     * 判断一行里的快递品牌是否可信：品牌在行首、行很短、或带「快递/速递」等强化词。
     * 避免把「安徽省…京东大道…」这种地址里的地名当成快递公司。
     */
    private fun brandFromLine(line: String): String {
        val alias = PickupRegexRules.detectCourierAlias(line) ?: return ""
        val index = line.indexOf(alias.keyword, ignoreCase = true)
        if (index in 0..2) return alias.canonical
        if (line.length <= 14) return alias.canonical
        if (COURIER_STRONG_WORDS.any { line.contains(it) }) return alias.canonical
        return ""
    }

    /**
     * 过滤日期串。与 [PickupCodeMatcher] 同一套严格口径：
     * 尾号像年份时**必须**同时满足「中间 ≤12 为月、首段 ≤31 为日」才算日期，
     * 否则 `5-89-2046` 这种真取件码会被误杀、最终落进待办。
     */
    private fun isDateLike(code: String): Boolean {
        val parts = code.split("-")
        if (parts.size < 2) return false
        val head = parts.first()
        val middle = parts[1]
        val last = parts.last()

        if (last.length == 4 && (last.startsWith("19") || last.startsWith("20"))) {
            val looksLikeDayMonth = middle.toIntOrNull()?.let { it in 1..12 } == true &&
                head.toIntOrNull()?.let { it in 1..31 } == true
            if (looksLikeDayMonth) return true
        }
        if (parts.size == 3 && head.length == 4 && (head.startsWith("19") || head.startsWith("20"))) {
            val m = middle.toIntOrNull() ?: 0
            val d = last.toIntOrNull() ?: 0
            if (m in 1..12 && d in 1..31) return true
        }
        return false
    }

    /** 整图行高（中位数）作为字号基准，超过 1.22 倍视为大号字 */
    private fun largeTextThreshold(rows: List<OcrRow>): Int {
        val heights = rows.map { it.height }.filter { it > 0 }.sorted()
        if (heights.size < 4) return 0
        val median = heights[heights.size / 2]
        if (median <= 0) return 0
        return (median * BIG_TEXT_RATIO).toInt().coerceAtLeast(1)
    }

    /** 运单号前缀 → 快递公司 */
    private fun courierFromTracking(trackingNo: String): String {
        if (trackingNo.isBlank()) return ""
        val upper = trackingNo.uppercase()
        return when {
            upper.startsWith("ZTO") || upper.startsWith("ZT") -> "中通快递"
            upper.startsWith("STO") || upper.startsWith("ST") -> "申通快递"
            upper.startsWith("EMS") -> "EMS"
            upper.startsWith("YT") -> "圆通快递"
            upper.startsWith("JT") -> "极兔速递"
            upper.startsWith("SF") -> "顺丰"
            upper.startsWith("JD") -> "京东物流"
            upper.startsWith("YD") -> "韵达快递"
            upper.startsWith("DB") -> "德邦快递"
            upper.startsWith("DN") -> "丹鸟"
            else -> ""
        }
    }
}
