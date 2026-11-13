package com.hnu.pickupassistant.ocr

import android.content.Context
import com.hnu.pickupassistant.data.LocalStorageManager
import java.util.UUID

/**
 * 「取了吗」识图取件码 —— 解析结果模型 + 入库桥接。
 *
 * ## 本次重构
 * 旧的「读到驿站名就向下沿用 + 向上回看 10 行找组头」逻辑已**整体删除**，
 * 解析工作全部交给 [ParcelBlockParser]：
 *  - 以驿站组头为区块边界、以「取件码」为记录锚点双层切分；
 *  - 每条记录的字段只在自己的区块 / 记录片段内提取，禁止跨区块继承；
 *  - 缺失字段留空，交给预览弹窗手动补全。
 *
 * 本文件只保留三件事：
 *  ① [ParcelCandidate] 预览弹窗的数据模型与展示拼接（备注默认留空，不自动预填）；
 *  ② [parse] / [parseFromPlainText] 把区块解析结果转成候选列表；
 *  ③ [saveCandidates] 复用工程原有的去重入库能力（业务代码零改动）。
 *
 * @author 焱川 (by 焱川)
 */
object ParcelTextParser {

    /**
     * 一条待预览 / 待入库的快递记录。
     *
     * 本次迭代：**不再保存详细地址**，且备注框默认留空。
     * 驿站名称 / 物流单号是独立字段，只用于展示与入库，**绝不自动复制进备注**。
     *
     * @param selected 预览弹窗里的勾选状态：默认 true（弹窗默认全选）
     */
    data class ParcelCandidate(
        val id: String = UUID.randomUUID().toString(),
        val code: String = "",
        val station: String = "",
        val courier: String = "",
        val trackingNo: String = "",
        /** 备注：默认空串，只存用户手动填写的补充说明，可留空 */
        val note: String = "",
        val selected: Boolean = true
    ) {
        /**
         * 展示用附属信息：「驿站名称·物流单号·用户备注」，空段跳过。
         * 仅用于预览 / 导出文本，**不会写回 [note]**。
         */
        fun displayDetail(): String =
            com.hnu.pickupassistant.util.PickupItemFormatter.join(
                station.trim().takeIf { PickupRegexRules.isPlausibleStationName(it) }.orEmpty(),
                trackingNo,
                note
            )

        /** 字段完整度，供同码去重时参考 */
        fun fieldScore(): Int = listOf(station, courier, trackingNo)
            .count { it.isNotBlank() }
    }

    /** 保存结果汇总，供弹窗提示「已保存 N 条 / 跳过重复 M 条」 */
    data class SaveSummary(
        val added: Int = 0,
        val duplicates: Int = 0,
        val invalid: Int = 0
    )

    // ========================================================================
    // 解析入口：视觉行文本 → 区块解析 → 候选列表
    // ========================================================================

    /**
     * 把按阅读顺序排好的 OCR 视觉行交给区块解析器。
     * 行高一并传下去：截图里取件码是绿色加粗大号字，ML Kit 拿不到颜色，
     * 用 boundingBox 高度做字号代理，用来在多个候选码里优先选大号那一行。
     */
    fun parse(sorted: OcrTextSorter.SortedText): List<ParcelCandidate> =
        toCandidates(
            ParcelBlockParser.parseRows(
                sorted.rows.map { ParcelBlockParser.OcrRow(it.text, it.height) },
                customStations()
            )
        )

    /**
     * 纯文本入口（无坐标信息），供剪贴板复用与单元测试。
     * @param customStations 常用驿站名；不传则读取设置里维护的列表（单测传空即可）
     */
    fun parseFromPlainText(
        text: String,
        customStations: List<String> = LocalStorageManager.customStationsFlow.value
    ): List<ParcelCandidate> =
        toCandidates(ParcelBlockParser.parse(text, customStations))

    /** 设置里维护的「常用驿站」名，OCR 命中即作为驿站名 */
    private fun customStations(): List<String> =
        LocalStorageManager.customStationsFlow.value

    private fun toCandidates(records: List<ParcelBlockParser.ParcelBlockRecord>): List<ParcelCandidate> =
        records.map {
            // 备注框默认留空：驿站 / 单号只读展示，不再自动复制进备注
            ParcelCandidate(
                code = it.code,
                station = it.station,
                courier = it.courier,
                trackingNo = it.trackingNo
            )
        }

    // ========================================================================
    // 入库：复用工程原有的去重能力，未改动 LocalStorageManager / 数据库结构
    // ========================================================================

    /**
     * 把预览弹窗里勾选、且用户已修正的记录保存入库。
     * 去重由原有 `extractAndImportFromText(allowDuplicate = false)` 完成；
     * 备注通过 note 写入；快递公司通过 segmentCouriers 逐条覆盖。
     * 两段式取件码在原解析器需要「取件码」关键字锚定，故构造 `取件码 <code>`。
     * 驿站 / 单号走结构化参数单独入库，note 只写用户手动输入的内容。
     */
    fun saveCandidates(
        @Suppress("UNUSED_PARAMETER") context: Context,
        candidates: List<ParcelCandidate>
    ): SaveSummary {
        var added = 0
        var duplicates = 0
        var invalid = 0
        for (candidate in candidates.filter { it.selected }) {
            val code = candidate.code.trim()
            if (code.isBlank() || !PickupRegexRules.PICKUP_CODE.matches(code)) {
                invalid++
                continue
            }
            val segment = "取件码 $code"
            val result = LocalStorageManager.extractAndImportFromText(
                text = segment,
                // 备注 = 用户手动输入（默认空串），驿站 / 单号绝不拼进来
                note = candidate.note.trim(),
                allowDuplicate = false,
                maxItems = 1,
                segments = listOf(segment),
                segmentCouriers = listOf(candidate.courier.trim()),
                segmentStations = listOf(candidate.station.trim()),
                segmentTrackingNos = listOf(candidate.trackingNo.trim())
            )
            if (result.recognized == 0) {
                invalid++
                continue
            }
            added += result.added
            duplicates += result.duplicates
        }
        return SaveSummary(added, duplicates, invalid)
    }
}
