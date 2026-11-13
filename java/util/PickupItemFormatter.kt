package com.hnu.pickupassistant.util

import com.hnu.pickupassistant.model.PickupItem

/**
 * 「取了吗」附属信息统一格式化工具（纯 Kotlin，不依赖 Android，可 JVM 单测）。
 *
 * ## 本次规则
 * 主页卡片 / 已完成列表 / 详情页 / 分享复制导出，一律按
 * **「驿站名称 · 物流单号 · 用户备注」** 顺序拼接，用「·」分隔：
 *  - 哪一段为空就跳过，不出现开头 / 结尾 / 连续的分隔符；
 *  - 备注框只存用户手写内容，驿站与单号是独立结构化字段，绝不自动拼进 note；
 *  - 待办（isTodo）没有取件码，直接展示事项文本，不参与三段拼接。
 *
 * ## 历史数据兼容（不清空用户手写备注）
 * 2.8.x ~ 2.9.2 会把自动模板写进 note，例如：
 *  `【兔喜生活超市】 | 单号：465829330375829`
 *  `放门口 | 【妈妈驿站代收点】 | 单号：YT0724531187007`
 * 更早版本还带 `地址：xxx` / `快递：xxx` 段。
 * [parseLegacyNote] 会把这些自动段拆出来还原成结构化字段，
 * **剩余段一律视为用户手写备注原样保留**，只改展示、不改库。
 *
 * @author 焱川 (by 焱川)
 */
object PickupItemFormatter {

    /** 三段之间的分隔符（需求指定「·」，不是「 | 」） */
    const val SEPARATOR: String = "·"

    /** 旧自动模板的分段符：半角 / 全角竖线 */
    private val LEGACY_SPLIT = Regex("""[|｜]""")

    /** 「【驿站名】」或「【驿站名】地址：xxx」——后面跟的旧地址段直接丢弃 */
    private val LEGACY_STATION = Regex("""^【(.+?)】(.*)$""")

    /** 「单号：xxx」 */
    private val LEGACY_TRACKING = Regex("""^单号\s*[：:]\s*(.+)$""")

    /** 更早版本的地址段 / 快递段，一律不再展示 */
    private val LEGACY_DROP = listOf(
        Regex("""^地址\s*[：:]"""),
        Regex("""^快递\s*[：:]"""),
        Regex("""^取件地址\s*[：:]""")
    )

    /**
     * 三段拼接：空段跳过，绝不产生多余的「·」。
     * 例：join("", "YT0724531187007", "") → "YT0724531187007"
     *     join("妈妈驿站代收点", "", "下午取") → "妈妈驿站代收点·下午取"
     */
    fun join(station: String, trackingNo: String, note: String): String =
        listOf(station, trackingNo, note)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(SEPARATOR)

    /**
     * 驿站名有效性：至少 2 个字符且含汉字，
     * 拦住 OCR 噪声「+」「-」「123」这类垃圾串（与 PickupRegexRules 同口径）。
     */
    private fun safeStation(raw: String): String = raw.trim().takeIf { name ->
        name.length >= 2 && name.any { it in '\u4E00'..'\u9FFF' }
    }.orEmpty()

    /** 历史备注解析结果 */
    data class LegacyParts(
        val station: String = "",
        val trackingNo: String = "",
        /** 用户手写内容：自动段剥离后剩下的部分，原样保留 */
        val userNote: String = ""
    )

    /**
     * 拆解旧 note 里的自动模板。
     * 无法识别的段全部归为 [LegacyParts.userNote]，不做任何丢弃（除了明确的地址/快递段）。
     */
    fun parseLegacyNote(note: String): LegacyParts {
        if (note.isBlank()) return LegacyParts()
        var station = ""
        var tracking = ""
        val userSegments = mutableListOf<String>()

        for (raw in note.split(LEGACY_SPLIT)) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val stationMatch = LEGACY_STATION.find(part)
            val trackingMatch = LEGACY_TRACKING.find(part)
            when {
                stationMatch != null -> {
                    if (station.isBlank()) station = stationMatch.groupValues[1].trim()
                    // groupValues[2] 是旧版「地址：xxx」，按新规则丢弃
                }
                trackingMatch != null -> {
                    if (tracking.isBlank()) tracking = trackingMatch.groupValues[1].trim()
                }
                LEGACY_DROP.any { it.containsMatchIn(part) } -> Unit // 旧地址 / 快递段不展示
                else -> userSegments.add(part)
            }
        }
        return LegacyParts(station, tracking, userSegments.joinToString(" | "))
    }

    /**
     * 统一的附属信息展示文本。
     * 新数据直接用结构化字段；旧数据（两个结构字段都空）从 note 里还原自动段。
     */
    fun displayDetail(item: PickupItem): String {
        // 待办没有取件码，事项文本就是主体，不走三段拼接
        if (item.isTodo) return item.note.trim()

        val hasStructured = item.station.isNotBlank() || item.trackingNo.isNotBlank()
        val legacy = if (hasStructured) LegacyParts() else parseLegacyNote(item.note)
        val station = safeStation(item.station.trim().ifBlank { legacy.station })
        val tracking = item.trackingNo.trim().ifBlank { legacy.trackingNo }
        val note = if (hasStructured) item.note.trim() else legacy.userNote
        return join(station, tracking, note)
    }

    /**
     * 备注编辑弹窗的初始草稿：
     * 新数据 note 本来就是纯用户手写；历史数据要剥掉自动段，避免把「单号：xxx」
     * 当成用户备注展示出来（但不会清空，结构化信息会在保存时写回字段）。
     */
    fun editableNote(item: PickupItem): String =
        if (item.station.isNotBlank() || item.trackingNo.isNotBlank()) {
            item.note
        } else {
            parseLegacyNote(item.note).userNote
        }

    /**
     * 分享 / 复制到剪贴板的文案：取件码在前，附属信息按同一套三段规则拼接。
     * 无附属信息时只输出取件码，不留多余空格。
     */
    fun shareText(item: PickupItem): String {
        val detail = displayDetail(item)
        return if (detail.isBlank()) item.displayCode
        else "${item.displayCode}  $detail"
    }
}
