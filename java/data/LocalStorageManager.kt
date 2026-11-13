package com.hnu.pickupassistant.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.hnu.pickupassistant.model.PickupItem
import com.hnu.pickupassistant.model.QuickButton
import com.hnu.pickupassistant.model.QuickButtonKind
import com.hnu.pickupassistant.model.RouteConfig
import com.hnu.pickupassistant.util.TextParser
import com.hnu.pickupassistant.util.TrackingChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 一次导入的结果：识别到的码数、实际新增数、因重复被跳过的数。
 * 用来区分「没识别出格式」与「识别到了但已存在」这两种完全不同的提示。
 */
data class ImportResult(
    val recognized: Int,
    val added: Int,
    val duplicates: Int
)

object LocalStorageManager {
    private const val PREF_NAME = "pickup_assistant_prefs"
    private const val KEY_PICKUP_ITEMS = "key_pickup_items"
    private const val KEY_CAINIAO_CONFIG = "key_cainiao_config"
    private const val KEY_TAOBAO_CONFIG = "key_taobao_config"
    private const val KEY_STATIC_CODE_ENABLED = "key_static_code_enabled"
    private const val KEY_ROUTE_SCHEMA_VERSION = "key_route_schema_version"
    private const val KEY_QUICK_BUTTONS = "key_quick_buttons"
    private const val KEY_SHOW_TODO_FLOATING = "key_show_todo_floating"
    private const val KEY_AUTO_READ_CLIPBOARD = "key_auto_read_clipboard"
    private const val KEY_CLIP_TEXT = "key_last_clip_text"
    private const val KEY_CLIP_SEEN_AT = "key_last_clip_seen_at"
    private const val KEY_CLIP_PROCESSED_TEXT = "key_last_clip_processed_text"
    private const val KEY_CLIP_HISTORY = "key_clip_history"
    private const val KEY_CUSTOM_STATIONS = "key_custom_stations"
    private const val KEY_NOTIFY_CAPTURE = "key_notify_capture_enabled"
    private const val KEY_SMS_CAPTURE = "key_sms_capture_enabled"
    private const val KEY_LAST_CAPTURE = "key_last_capture_summary"
    private const val KEY_TRACKING_CHANNEL = "key_tracking_channel"
    private const val CLIP_HISTORY_MAX = 20
    private const val ROUTE_SCHEMA_VERSION = 2
    private const val STATIC_BARCODE_FILE_NAME = "static_identity_barcode.jpg"
    private const val CLIPBOARD_FRESH_WINDOW_MS = 60_000L

    /**
     * 取件码提取已抽到纯 Kotlin 工具类 `util/PickupCodeMatcher`（无 Android 依赖，可 JVM 单测）：
     *  - 三段式与两段式**同时提取**、按文本区间去重；旧的「三段式优先」会让同一段文本里的
     *    `706-48`、`706-10` 被丢掉，只剩 `3-7-2593`；
     *  - 过滤 `11-10-2026` / `1-2-2026` 这类日期型假码 —— 这是用户反馈
     *    「识别到剪贴板记录里不存在的取件码」的根因；
     *  - 两段式仍要求「取件码 / 取码 / 提货码…」关键字锚定，普通 `数字-数字` 不会被误判。
     * 本类只负责入库与去重，识别规则集中放在可单元测试的纯 Kotlin 类里。
     */

    private lateinit var prefs: SharedPreferences

    private val _pickupItemsFlow = MutableStateFlow<List<PickupItem>>(emptyList())
    val pickupItemsFlow: StateFlow<List<PickupItem>> = _pickupItemsFlow.asStateFlow()

    private val _cainiaoConfigFlow = MutableStateFlow(RouteConfig.DEFAULT_CAINIAO)
    val cainiaoConfigFlow: StateFlow<RouteConfig> = _cainiaoConfigFlow.asStateFlow()

    private val _taobaoConfigFlow = MutableStateFlow(RouteConfig.DEFAULT_TAOBAO)
    val taobaoConfigFlow: StateFlow<RouteConfig> = _taobaoConfigFlow.asStateFlow()

    private val _staticCodeEnabledFlow = MutableStateFlow(false)
    val staticCodeEnabledFlow: StateFlow<Boolean> = _staticCodeEnabledFlow.asStateFlow()

    private val _quickButtonsFlow = MutableStateFlow<List<QuickButton>>(QuickButton.DEFAULTS)
    val quickButtonsFlow: StateFlow<List<QuickButton>> = _quickButtonsFlow.asStateFlow()

    private val _showTodoInFloatingFlow = MutableStateFlow(false)
    val showTodoInFloatingFlow: StateFlow<Boolean> = _showTodoInFloatingFlow.asStateFlow()

    private val _autoReadClipboardFlow = MutableStateFlow(true)
    val autoReadClipboardFlow: StateFlow<Boolean> = _autoReadClipboardFlow.asStateFlow()

    /** 用户自定义常用驿站地址：文本/图片识别匹配到时自动加入备注 */
    private val _customStationsFlow = MutableStateFlow<List<String>>(emptyList())
    val customStationsFlow: StateFlow<List<String>> = _customStationsFlow.asStateFlow()

    /** 通知自动收码开关（默认关闭，需系统「通知使用权」） */
    private val _notifyCaptureFlow = MutableStateFlow(false)
    val notifyCaptureFlow: StateFlow<Boolean> = _notifyCaptureFlow.asStateFlow()

    /** 短信自动收码开关（默认关闭，需短信读取权限） */
    private val _smsCaptureFlow = MutableStateFlow(false)
    val smsCaptureFlow: StateFlow<Boolean> = _smsCaptureFlow.asStateFlow()

    /** 最近一次自动收码摘要，形如「20:15 通知·菜鸟 收 2 条」，仅用于设置页展示 */
    private val _lastCaptureFlow = MutableStateFlow("")
    val lastCaptureFlow: StateFlow<String> = _lastCaptureFlow.asStateFlow()

    /** 单号查询渠道：淘宝（菜鸟添加包裹页）默认，备选菜鸟网页版 */
    private val _trackingChannelFlow = MutableStateFlow(TrackingChannel.TAOBAO)
    val trackingChannelFlow: StateFlow<TrackingChannel> = _trackingChannelFlow.asStateFlow()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            reloadAll()
        }
    }

    private fun reloadAll() {
        _pickupItemsFlow.value = sortItems(loadItemsFromPrefs())
        _cainiaoConfigFlow.value = loadRouteConfig(KEY_CAINIAO_CONFIG, RouteConfig.DEFAULT_CAINIAO)
        _taobaoConfigFlow.value = migrateLegacyTaobaoRoute()
        _staticCodeEnabledFlow.value = prefs.getBoolean(KEY_STATIC_CODE_ENABLED, false)
        _showTodoInFloatingFlow.value = prefs.getBoolean(KEY_SHOW_TODO_FLOATING, false)
        _autoReadClipboardFlow.value = prefs.getBoolean(KEY_AUTO_READ_CLIPBOARD, true)
        _customStationsFlow.value = loadCustomStations()
        _notifyCaptureFlow.value = prefs.getBoolean(KEY_NOTIFY_CAPTURE, false)
        _smsCaptureFlow.value = prefs.getBoolean(KEY_SMS_CAPTURE, false)
        _lastCaptureFlow.value = prefs.getString(KEY_LAST_CAPTURE, "") ?: ""
        _trackingChannelFlow.value = TrackingChannel.fromKey(prefs.getString(KEY_TRACKING_CHANNEL, null))
        // 用户自定义过按钮后，桌面快捷方式/悬浮窗的兜底路由也跟着按钮走；
        // 没自定义过则保留旧版本里单独保存的路由配置，避免升级丢失。
        val hasCustomButtons = prefs.getString(KEY_QUICK_BUTTONS, null) != null
        _quickButtonsFlow.value = loadQuickButtons()
        if (hasCustomButtons) syncLegacyRouteFlows(_quickButtonsFlow.value)
    }

    /**
     * v1 的淘宝出库码路由已失效（SchemeTransformActivity + taobao:// scheme 打不开）。
     * 升级时自动换成 Anywhere- 卡片验证过的新路由；用户自己改过的新式配置保持不动。
     */
    private fun migrateLegacyTaobaoRoute(): RouteConfig {
        val savedVersion = prefs.getInt(KEY_ROUTE_SCHEMA_VERSION, 1)
        val saved = loadRouteConfig(KEY_TAOBAO_CONFIG, RouteConfig.DEFAULT_TAOBAO)
        val isLegacy = saved.targetActivity.contains("SchemeTransformActivity", ignoreCase = true) ||
            saved.schemeUri.startsWith("taobao://")

        val result = if (savedVersion < ROUTE_SCHEMA_VERSION && isLegacy) {
            saveRouteConfig(KEY_TAOBAO_CONFIG, RouteConfig.DEFAULT_TAOBAO)
            RouteConfig.DEFAULT_TAOBAO
        } else {
            saved
        }
        if (savedVersion < ROUTE_SCHEMA_VERSION) {
            prefs.edit().putInt(KEY_ROUTE_SCHEMA_VERSION, ROUTE_SCHEMA_VERSION).apply()
        }
        return result
    }

    /**
     * 排序：未取件在前；同一状态下「待办」置顶；其余按货架 / 层 / 尾号升序。
     */
    fun sortItems(items: List<PickupItem>): List<PickupItem> {
        return items.sortedWith(
            compareBy<PickupItem> { it.isPicked }
                .thenBy { !it.isTodo }
                .thenBy { it.shelfNum }
                .thenBy { it.layerNum }
                .thenBy { it.tailNum }
        )
    }

    /**
     * 从任意文本（剪贴板/短信/手动输入/OCR 结果）中正则全局提取所有取件码并批量入库。
     *
     * @param note 本次导入的所有取件码共用的备注（用户手动输入的备注，不属于自动识别）
     * @param allowDuplicate 为 true 时忽略「已存在」检查（用户主动选择「仍然添加」），
     *                       但仍对同一段文本内重复出现的码做本批次去重
     * @param segments 多条独立文本（如剪贴板历史里的每一次复制、OCR 截图里的每一条包裹）。
     *                 传入时取件码仍从全部记录【一次批量提取】（批量导入能力完整保留），
     *                 但公司名/取件地址按「每个码所在的那一条记录」单独识别，互不串味。
     * @param segmentCouriers 与 segments 一一对应的快递公司（OCR 逐条判定后传入）。
     *                        提供时以它为准：空串表示该条没找到任何公司关键词，字段留空，
     *                        绝不复用其它条的公司名。其它入口不传，行为不变。
     * @param segmentStations 与 segments 一一对应的驿站名称（结构化字段，不写入 note）。
     * @param segmentTrackingNos 与 segments 一一对应的物流单号（结构化字段，不写入 note）。
     */
    @Synchronized
    fun extractAndImportFromText(
        text: String,
        note: String = "",
        allowDuplicate: Boolean = false,
        maxItems: Int = Int.MAX_VALUE,
        segments: List<String>? = null,
        segmentCouriers: List<String>? = null,
        /** 与 segments 一一对应的驿站名称；提供时以它为准，空串表示本条识别不到 */
        segmentStations: List<String>? = null,
        /** 与 segments 一一对应的物流单号；提供时以它为准，空串表示本条识别不到 */
        segmentTrackingNos: List<String>? = null
    ): ImportResult {
        if (text.isBlank()) return ImportResult(0, 0, 0)
        // 未传分段时退化为「整段识别」，与 OCR / 系统分享 / 手动输入等原有入口行为完全一致
        val sources = if (segments.isNullOrEmpty()) {
            listOf(text)
        } else {
            segments.filter { it.isNotBlank() }
        }
        if (sources.isEmpty()) return ImportResult(0, 0, 0)

        val currentList = _pickupItemsFlow.value.toMutableList()
        val existingCodes = if (allowDuplicate) {
            mutableSetOf()
        } else {
            currentList.filter { !it.isPicked }.map { it.rawCode }.toMutableSet()
        }

        var addedCount = 0
        var duplicateCount = 0
        var recognizedCount = 0
        val now = System.currentTimeMillis()
        val cleanNote = note.trim()
        val customStations = _customStationsFlow.value

        // 所有记录共享同一个去重集合与计数：一次调用把多条历史/多条包裹的取件码全部批量导入
        var segmentIndex = -1
        outer@ for (src in sources) {
            segmentIndex++
            val matches = extractCodeMatches(src)
            if (matches.isEmpty()) continue

            // 快递公司 / 驿站 / 单号只从「本条记录」识别，绝不跨记录借用。
            // OCR 入口会逐条给出判定结果（含「本条没找到→空串」），此时以它为准；
            // 纯取件码记录（如 2-96-8503，去掉数字后没有任何汉字/字母关键词）：
            //   文本内不存在任何关键词 → 公司 / 驿站 / 单号全部留空，不写死默认值。
            val contextless = src.none {
                it.code in 0x4E00..0x9FFF || it in 'A'..'Z' || it in 'a'..'z'
            }
            val courier = if (segmentCouriers != null && segmentIndex < segmentCouriers.size) {
                segmentCouriers[segmentIndex]
            } else if (contextless) {
                ""
            } else {
                TextParser.detectCourier(src)
            }
            // ★ 本次调整：驿站名称 / 物流单号存进独立结构化字段，
            //   note 只保留用户手动输入的内容，绝不自动拼入「单号：xxx」。
            //   展示时由 PickupItemFormatter 统一按「驿站·单号·备注」拼接。
            val autoParts = if (contextless) Pair("", "") else buildAutoParts(src, customStations)
            val station = if (segmentStations != null) {
                segmentStations.getOrNull(segmentIndex).orEmpty().trim()
            } else {
                autoParts.first
            }
            val trackingNo = if (segmentTrackingNos != null) {
                segmentTrackingNos.getOrNull(segmentIndex).orEmpty().trim()
            } else {
                autoParts.second
            }

            for (m in matches) {
                if (maxItems < Int.MAX_VALUE && recognizedCount >= maxItems) break@outer
                recognizedCount++
                val shelfNum = m.shelf.toIntOrNull() ?: continue
                val layerNum = m.layer.toIntOrNull() ?: continue
                // rawCode 保留「层号」原文（含前导零，如 6-04-406），避免 04 被归一化成 4 对不上号
                val rawCode = if (m.tail.isEmpty()) "${m.shelf}-${m.layer}" else "${m.shelf}-${m.layer}-${m.tail}"

                if (rawCode in existingCodes) {
                    duplicateCount++
                    continue
                }
                currentList.add(
                    PickupItem(
                        id = UUID.randomUUID().toString(),
                        rawCode = rawCode,
                        shelfNum = shelfNum,
                        layerNum = layerNum,
                        tailNum = m.tail,
                        isPicked = false,
                        timestamp = now + addedCount,
                        note = cleanNote,
                        courier = courier,
                        station = station,
                        trackingNo = trackingNo
                    )
                )
                existingCodes.add(rawCode)
                addedCount++
            }
        }

        if (addedCount > 0) saveAndPublishItems(currentList)
        return ImportResult(
            recognized = recognizedCount,
            added = addedCount,
            duplicates = duplicateCount
        )
    }

    /** 取件码片段：三段式「货架-层-尾号」或两段式「货架-编号」（尾号为空） */
    private data class CodeSegment(val shelf: String, val layer: String, val tail: String)

    /** 提取文本中的所有取件码：委托纯 Kotlin 的 PickupCodeMatcher（含日期假码过滤与区间去重） */
    private fun extractCodeMatches(text: String): List<CodeSegment> =
        com.hnu.pickupassistant.util.PickupCodeMatcher.extract(text)
            .map { CodeSegment(it.shelf, it.layer, it.tail) }

    /**
     * 自动识别本条记录的「驿站名称 / 物流单号」，作为**结构化字段**返回。
     *
     * 不再拼成「【驿站】 | 单号：xxx」写进 note —— 备注字段只归用户手写。
     * 驿站名取完整全称并过有效性校验（「+」这类噪声一律留空），
     * 单号取本条记录里的那一串；识别不到就返回空串，不补假数据。
     */
    private fun buildAutoParts(src: String, customStations: List<String>): Pair<String, String> {
        val lines = src.split("\n")
        val custom = TextParser.matchCustomStation(src, customStations)
            ?.takeIf { com.hnu.pickupassistant.ocr.PickupRegexRules.isPlausibleStationName(it) }
        val station = custom?.takeIf { it.isNotBlank() }
            ?: com.hnu.pickupassistant.ocr.ParcelBlockParser.extractStation(lines)
        val tracking = com.hnu.pickupassistant.ocr.ParcelBlockParser.extractTrackingNo(lines)
        return station.trim() to tracking.trim()
    }

    /** 只填备注、不输入取件码时添加一条显示为「待办」的记录 */
    @Synchronized
    fun addTodoItem(note: String): Boolean {
        val cleanNote = note.trim()
        if (cleanNote.isEmpty()) return false
        val current = _pickupItemsFlow.value.toMutableList()
        current.add(
            PickupItem(
                rawCode = "待办",
                shelfNum = -1,
                layerNum = 0,
                tailNum = "",
                note = cleanNote,
                isTodo = true
            )
        )
        saveAndPublishItems(current)
        return true
    }

    /**
     * 切换单条记录的【已取件 / 未取件】状态
     */
    @Synchronized
    fun togglePickedStatus(itemId: String) {
        val updated = _pickupItemsFlow.value.map { item ->
            if (item.id == itemId) item.copy(isPicked = !item.isPicked) else item
        }
        saveAndPublishItems(updated)
    }

    /**
     * 修改单条记录的备注（清空时传空串即可）。
     *
     * 历史兼容：老数据的 note 里混着「【驿站】 | 单号：xxx」自动模板，
     * 用户第一次编辑备注时把这两段**升级成结构化字段**，note 只留用户手写内容，
     * 既不会把单号再写回备注，也不会丢掉原先识别到的驿站 / 单号。
     */
    @Synchronized
    fun updateNote(itemId: String, note: String) {
        val cleanNote = note.trim()
        val updated = _pickupItemsFlow.value.map { item ->
            if (item.id != itemId) return@map item
            val legacy = if (item.station.isBlank() && item.trackingNo.isBlank()) {
                com.hnu.pickupassistant.util.PickupItemFormatter.parseLegacyNote(item.note)
            } else {
                null
            }
            item.copy(
                note = cleanNote,
                station = item.station.ifBlank { legacy?.station.orEmpty() },
                trackingNo = item.trackingNo.ifBlank { legacy?.trackingNo.orEmpty() }
            )
        }
        saveAndPublishItems(updated)
    }

    /**
     * 备注详情弹窗的「二次修正」：一次写回用户备注 + 驿站名称 + 快递公司 + 物流单号。
     *
     * OCR 自动识别偶尔会有错字，旧版入库后只能删掉重加；现在这四个字段都能在
     * 详情弹窗里直接改，保存后卡片按「驿站·单号·备注」自动刷新。
     * 传空串表示用户主动清空该字段，不做任何旧值回退。
     */
    @Synchronized
    fun updateParcelFields(
        itemId: String,
        note: String,
        station: String,
        courier: String,
        trackingNo: String
    ) {
        val updated = _pickupItemsFlow.value.map { item ->
            if (item.id != itemId) return@map item
            item.copy(
                note = note.trim(),
                station = station.trim(),
                courier = courier.trim(),
                trackingNo = trackingNo.trim()
            )
        }
        saveAndPublishItems(updated)
    }

    @Synchronized
    fun deleteItem(itemId: String) {
        val updated = _pickupItemsFlow.value.filterNot { it.id == itemId }
        saveAndPublishItems(updated)
    }

    /**
     * 撤销滑动删除：把 5 秒撤销窗口内删掉的 (原位置, 记录) 按原位置插回。
     * 多条删除一次性恢复；已存在的 id 直接跳过，避免重复添加。
     * 只用于滑动删除的撤销，「清除完成 / 清空全部」仍保持原有不可撤销语义。
     */
    @Synchronized
    fun restoreDeletedItems(deleted: List<Pair<Int, PickupItem>>) {
        if (deleted.isEmpty()) return
        val current = _pickupItemsFlow.value.toMutableList()
        val existingIds = current.map { it.id }.toHashSet()
        deleted.sortedBy { it.first }.forEach { (index, item) ->
            if (existingIds.add(item.id)) {
                current.add(index.coerceIn(0, current.size), item)
            }
        }
        saveAndPublishItems(current)
    }

    /** 「清除完成」：同步删除已完成的待办与已取件的快递（所有 isPicked 记录） */
    @Synchronized
    fun clearPickedItems() {
        val updated = _pickupItemsFlow.value.filterNot { it.isPicked }
        saveAndPublishItems(updated)
    }

    @Synchronized
    fun clearAllItems() {
        saveAndPublishItems(emptyList())
    }

    private fun saveAndPublishItems(items: List<PickupItem>) {
        val sorted = sortItems(items)
        val jsonArray = JSONArray()
        for (item in sorted) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("rawCode", item.rawCode)
                put("shelfNum", item.shelfNum)
                put("layerNum", item.layerNum)
                put("tailNum", item.tailNum)
                put("isPicked", item.isPicked)
                put("timestamp", item.timestamp)
                put("note", item.note)
                put("isTodo", item.isTodo)
                put("courier", item.courier)
                put("station", item.station)
                put("trackingNo", item.trackingNo)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_PICKUP_ITEMS, jsonArray.toString()).apply()
        _pickupItemsFlow.value = sorted
    }

    private fun loadItemsFromPrefs(): List<PickupItem> {
        val rawJson = prefs.getString(KEY_PICKUP_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(rawJson)
            val list = mutableListOf<PickupItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    PickupItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        rawCode = obj.getString("rawCode"),
                        shelfNum = obj.optInt("shelfNum", 0),
                        layerNum = obj.optInt("layerNum", 0),
                        tailNum = obj.optString("tailNum", ""),
                        isPicked = obj.optBoolean("isPicked", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        // 旧版本数据没有 note / isTodo / courier / station / trackingNo 字段，
                        // optXxx 给默认值保证升级不丢数据；旧 note 里的自动模板由
                        // PickupItemFormatter 在展示层还原，不改动这里存下来的原文
                        note = obj.optString("note", ""),
                        isTodo = obj.optBoolean("isTodo", false),
                        courier = obj.optString("courier", ""),
                        station = obj.optString("station", ""),
                        trackingNo = obj.optString("trackingNo", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 快捷按钮持久化 ====================

    @Synchronized
    fun saveQuickButtons(buttons: List<QuickButton>) {
        val array = JSONArray()
        for (b in buttons) {
            array.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("label", b.label)
                    put("kind", b.kind.name)
                    put("packageName", b.packageName)
                    put("targetActivity", b.targetActivity)
                    put("fallbackActivity", b.fallbackActivity)
                    put("schemeUri", b.schemeUri)
                    put("imageFileName", b.imageFileName)
                    put("styleKey", b.styleKey)
                }
            )
        }
        prefs.edit().putString(KEY_QUICK_BUTTONS, array.toString()).apply()
        _quickButtonsFlow.value = buttons
        syncLegacyRouteFlows(buttons)
    }

    /** 桌面快捷方式与悬浮窗仍用旧的独立路由 flow，保存按钮时按列表顺序同步前两个路由按钮 */
    private fun syncLegacyRouteFlows(buttons: List<QuickButton>) {
        val routes = buttons.filter { it.kind == QuickButtonKind.ROUTE }
        routes.getOrNull(0)?.let { _cainiaoConfigFlow.value = it.toRouteConfig() }
        routes.getOrNull(1)?.let { _taobaoConfigFlow.value = it.toRouteConfig() }
    }

    private fun loadQuickButtons(): List<QuickButton> {
        val raw = prefs.getString(KEY_QUICK_BUTTONS, null)
        if (raw == null) {
            // 从旧版本升级：用已保存的路由配置生成默认按钮，避免丢失用户改过的路由
            val c = _cainiaoConfigFlow.value
            val t = _taobaoConfigFlow.value
            return listOf(
                QuickButton.DEFAULTS[0].copy(
                    packageName = c.packageName,
                    targetActivity = c.targetActivity,
                    fallbackActivity = c.fallbackActivity,
                    schemeUri = c.schemeUri
                ),
                QuickButton.DEFAULTS[1].copy(
                    packageName = t.packageName,
                    targetActivity = t.targetActivity,
                    fallbackActivity = t.fallbackActivity,
                    schemeUri = t.schemeUri
                )
            )
        }
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<QuickButton>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val kind = try {
                    QuickButtonKind.valueOf(o.optString("kind", "ROUTE"))
                } catch (_: Exception) {
                    QuickButtonKind.ROUTE
                }
                list.add(
                    QuickButton(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        label = o.optString("label", "按钮"),
                        kind = kind,
                        packageName = o.optString("packageName", ""),
                        targetActivity = o.optString("targetActivity", ""),
                        fallbackActivity = o.optString("fallbackActivity", ""),
                        schemeUri = o.optString("schemeUri", ""),
                        imageFileName = o.optString("imageFileName", ""),
                        styleKey = o.optString("styleKey", "")
                    )
                )
            }
            if (list.isEmpty()) QuickButton.DEFAULTS else list
        } catch (e: Exception) {
            QuickButton.DEFAULTS
        }
    }

    /** 新增或替换一个按钮（按 id 判断） */
    @Synchronized
    fun upsertQuickButton(button: QuickButton) {
        val current = _quickButtonsFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.id == button.id }
        if (idx >= 0) current[idx] = button else current.add(button)
        saveQuickButtons(current)
    }

    @Synchronized
    fun deleteQuickButton(context: Context, id: String) {
        val target = _quickButtonsFlow.value.firstOrNull { it.id == id }
        if (target != null && target.imageFileName.isNotBlank()) {
            deleteQuickButtonImage(context, target.imageFileName)
        }
        saveQuickButtons(_quickButtonsFlow.value.filterNot { it.id == id })
    }

    /** delta = -1 上移（可把出库码设为第一个按钮），+1 下移 */
    @Synchronized
    fun moveQuickButton(id: String, delta: Int) {
        val current = _quickButtonsFlow.value.toMutableList()
        val from = current.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.size - 1)
        if (to == from) return
        val item = current.removeAt(from)
        current.add(to, item)
        saveQuickButtons(current)
    }

    @Synchronized
    fun resetQuickButtons(context: Context) {
        for (b in _quickButtonsFlow.value) {
            if (b.imageFileName.isNotBlank()) deleteQuickButtonImage(context, b.imageFileName)
        }
        saveQuickButtons(QuickButton.DEFAULTS)
    }

    // ==================== 按钮二维码图片管理 ====================

    fun newQuickButtonImageFileName(): String = "quick_btn_${UUID.randomUUID()}.jpg"

    fun getQuickButtonImageFile(context: Context, fileName: String): File =
        File(context.filesDir, fileName)

    fun hasQuickButtonImage(context: Context, fileName: String): Boolean {
        if (fileName.isBlank()) return false
        val f = getQuickButtonImageFile(context, fileName)
        return f.exists() && f.length() > 0L
    }

    fun saveQuickButtonImage(context: Context, sourceUri: Uri, fileName: String): Boolean {
        if (fileName.isBlank()) return false
        return try {
            val dest = getQuickButtonImageFile(context, fileName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest.exists() && dest.length() > 0L
        } catch (e: Exception) {
            false
        }
    }

    fun deleteQuickButtonImage(context: Context, fileName: String) {
        if (fileName.isBlank()) return
        try {
            getQuickButtonImageFile(context, fileName).delete()
        } catch (_: Throwable) {
        }
    }

    // ==================== 路由配置持久化（桌面快捷方式 / 悬浮窗兜底） ====================

    fun updateRouteConfigs(cainiao: RouteConfig, taobao: RouteConfig) {
        saveRouteConfig(KEY_CAINIAO_CONFIG, cainiao)
        saveRouteConfig(KEY_TAOBAO_CONFIG, taobao)
        _cainiaoConfigFlow.value = cainiao
        _taobaoConfigFlow.value = taobao
    }

    fun resetRouteConfigsToDefault() {
        updateRouteConfigs(RouteConfig.DEFAULT_CAINIAO, RouteConfig.DEFAULT_TAOBAO)
    }

    private fun saveRouteConfig(key: String, config: RouteConfig) {
        val obj = JSONObject().apply {
            put("packageName", config.packageName)
            put("targetActivity", config.targetActivity)
            put("fallbackActivity", config.fallbackActivity)
            put("schemeUri", config.schemeUri)
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun loadRouteConfig(key: String, defaultConfig: RouteConfig): RouteConfig {
        val raw = prefs.getString(key, null) ?: return defaultConfig
        return try {
            val obj = JSONObject(raw)
            RouteConfig(
                packageName = obj.optString("packageName", defaultConfig.packageName),
                targetActivity = obj.optString("targetActivity", defaultConfig.targetActivity),
                fallbackActivity = obj.optString("fallbackActivity", defaultConfig.fallbackActivity),
                schemeUri = obj.optString("schemeUri", defaultConfig.schemeUri)
            )
        } catch (e: Exception) {
            defaultConfig
        }
    }

    // ==================== 静态出库码兜底图片管理 ====================

    fun setStaticCodeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STATIC_CODE_ENABLED, enabled).apply()
        _staticCodeEnabledFlow.value = enabled
    }

    fun getStaticBarcodeFile(context: Context): File {
        return File(context.filesDir, STATIC_BARCODE_FILE_NAME)
    }

    fun hasStaticBarcodeImage(context: Context): Boolean {
        val file = getStaticBarcodeFile(context)
        return file.exists() && file.length() > 0L
    }

    fun saveStaticBarcodeImage(context: Context, sourceUri: Uri): Boolean {
        return try {
            val destFile = getStaticBarcodeFile(context)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.exists() && destFile.length() > 0L
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 悬浮窗待办开关 ====================

    /** 悬浮窗是否显示待办，默认关闭 */
    fun setShowTodoInFloating(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TODO_FLOATING, enabled).apply()
        _showTodoInFloatingFlow.value = enabled
    }

    // ==================== 自动读取剪切板开关 ====================

    /** 是否自动读取剪切板，默认开启。关闭后主界面显示「剪切板添加」手动按钮 */
    fun setAutoReadClipboard(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_READ_CLIPBOARD, enabled).apply()
        _autoReadClipboardFlow.value = enabled
    }

    // ==================== 自动收码（通知 / 短信）====================

    /** 通知自动收码开关，默认关闭。开启前需系统「通知使用权」 */
    fun setNotifyCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_CAPTURE, enabled).apply()
        _notifyCaptureFlow.value = enabled
    }

    /** 短信自动收码开关，默认关闭。开启前需短信读取权限 */
    fun setSmsCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMS_CAPTURE, enabled).apply()
        _smsCaptureFlow.value = enabled
    }

    /** 记录最近一次自动收码摘要（设置页展示，便于用户确认是否生效） */
    fun recordCaptureSummary(summary: String) {
        prefs.edit().putString(KEY_LAST_CAPTURE, summary).apply()
        _lastCaptureFlow.value = summary
    }

    /** 单号查询渠道（淘宝 / 菜鸟网页版），持久化保存 */
    fun setTrackingChannel(channel: TrackingChannel) {
        prefs.edit().putString(KEY_TRACKING_CHANNEL, channel.key).apply()
        _trackingChannelFlow.value = channel
    }

    // ==================== 自定义常用驿站地址 ====================

    /** 读取自定义驿站地址（JSON 数组字符串） */
    private fun loadCustomStations(): List<String> {
        val raw = prefs.getString(KEY_CUSTOM_STATIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i)?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 整体替换自定义驿站地址（去空白、去重） */
    fun setCustomStations(stations: List<String>) {
        val clean = stations.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val arr = JSONArray()
        clean.forEach { arr.put(it) }
        prefs.edit().putString(KEY_CUSTOM_STATIONS, arr.toString()).apply()
        _customStationsFlow.value = clean
    }

    /** 新增一个驿站地址（忽略空串与重复） */
    fun addCustomStation(station: String) {
        val s = station.trim()
        if (s.isEmpty()) return
        val cur = _customStationsFlow.value.toMutableList()
        if (s in cur) return
        cur.add(s)
        setCustomStations(cur)
    }

    /** 删除一个驿站地址 */
    fun removeCustomStation(station: String) {
        val cur = _customStationsFlow.value.toMutableList()
        if (cur.remove(station)) setCustomStations(cur)
    }

    // ==================== 剪贴板新鲜度 ====================

    /**
     * 只接受「一分钟内复制」的剪贴板内容。
     *
     * @param clipTimestamp ClipDescription.getTimestamp()（API 31+）；低版本或厂商未实现时传 0
     *
     * 时间戳的基准可能是 System.currentTimeMillis()，也可能是 SystemClock.elapsedRealtime()，
     * 不同系统版本/ROM 不一致，所以两个基准都算一遍取较小的差值，避免猜错单位。
     * 拿不到时间戳时退化为「本地记录内容 + 首次见到时间」的方案。
     */
    fun isClipboardFresh(text: String, clipTimestamp: Long): Boolean {
        val nowWall = System.currentTimeMillis()
        if (clipTimestamp > 0L) {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val age = minOf(
                kotlin.math.abs(nowWall - clipTimestamp),
                kotlin.math.abs(nowElapsed - clipTimestamp)
            )
            return age <= CLIPBOARD_FRESH_WINDOW_MS
        }

        val lastText = prefs.getString(KEY_CLIP_TEXT, null)
        val seenAt = prefs.getLong(KEY_CLIP_SEEN_AT, 0L)
        val fresh = if (lastText == text) {
            (nowWall - seenAt) <= CLIPBOARD_FRESH_WINDOW_MS
        } else {
            true
        }
        prefs.edit()
            .putString(KEY_CLIP_TEXT, text)
            .putLong(KEY_CLIP_SEEN_AT, nowWall)
            .apply()
        return fresh
    }

    /**
     * 同一段剪贴板内容只处理一次。
     * 配合「每次回到前台都读剪贴板」使用：新复制的内容会正常导入，
     * 而用户手动删掉记录后，旧的那段剪贴板不会再把记录加回来。
     */
    fun isClipboardUnprocessed(text: String): Boolean {
        return prefs.getString(KEY_CLIP_PROCESSED_TEXT, null) != text
    }

    fun markClipboardProcessed(text: String) {
        prefs.edit().putString(KEY_CLIP_PROCESSED_TEXT, text).apply()
    }

    // ==================== 剪贴板历史（读历史多次复制的内容） ====================

    /** 记录一次新复制的内容到历史队列（去重、置顶、最多保留 CLIP_HISTORY_MAX 条） */
    @Synchronized
    fun recordClipboardHistory(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val history = loadClipboardHistory().toMutableList()
        history.remove(t)
        history.add(0, t)
        while (history.size > CLIP_HISTORY_MAX) history.removeAt(history.size - 1)
        val arr = JSONArray()
        history.forEach { arr.put(it) }
        prefs.edit().putString(KEY_CLIP_HISTORY, arr.toString()).apply()
    }

    /** 读取剪贴板历史（最新在前） */
    fun loadClipboardHistory(): List<String> {
        val raw = prefs.getString(KEY_CLIP_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotBlank()) add(s)
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 取历史中最新的 N 条（含当前剪贴板）合并为一段文本，用于导入 */
    fun clipboardHistoryText(limit: Int = CLIP_HISTORY_MAX): String {
        return loadClipboardHistory().take(limit).joinToString("\n")
    }

    /** 删除静态出库码截图，并同步关闭兜底开关 */
    fun deleteStaticBarcodeImage(context: Context): Boolean {
        return try {
            val file = getStaticBarcodeFile(context)
            val ok = !file.exists() || file.delete()
            if (ok) {
                prefs.edit().putBoolean(KEY_STATIC_CODE_ENABLED, false).apply()
                _staticCodeEnabledFlow.value = false
            }
            ok
        } catch (e: Exception) {
            false
        }
    }
}
