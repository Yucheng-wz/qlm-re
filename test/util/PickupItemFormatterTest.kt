package com.hnu.pickupassistant.util

import com.hnu.pickupassistant.model.PickupItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** 备注三段拼接 / 历史兼容 / 分享文案的独立单测（纯 JVM） */
class PickupItemFormatterTest {

    private fun item(
        station: String = "",
        trackingNo: String = "",
        note: String = "",
        isTodo: Boolean = false
    ) = PickupItem(
        rawCode = "3-7-2593",
        shelfNum = 3,
        layerNum = 7,
        tailNum = "2593",
        courier = "韵达快递",
        station = station,
        trackingNo = trackingNo,
        note = note,
        isTodo = isTodo
    )

    /** 四组需求示例：空段跳过、不出现开头/结尾/连续分隔符 */
    @Test
    fun displayDetail_fourSpecExamples() {
        assertEquals(
            "淮师大滨湖校区文源楼北菜鸟驿站·EMS1356884918749",
            PickupItemFormatter.displayDetail(
                item(station = "淮师大滨湖校区文源楼北菜鸟驿站", trackingNo = "EMS1356884918749")
            )
        )
        assertEquals(
            "兔喜生活超市·465829330375829·放门口",
            PickupItemFormatter.displayDetail(
                item(station = "兔喜生活超市", trackingNo = "465829330375829", note = "放门口")
            )
        )
        assertEquals(
            "YT0724531187007",
            PickupItemFormatter.displayDetail(item(trackingNo = "YT0724531187007"))
        )
        assertEquals(
            "妈妈驿站代收点·下午取",
            PickupItemFormatter.displayDetail(item(station = "妈妈驿站代收点", note = "下午取"))
        )
    }

    /** 全空 / 待办 / 垃圾驿站名 */
    @Test
    fun displayDetail_edgeCases() {
        assertEquals("", PickupItemFormatter.displayDetail(item()))
        assertEquals("买牛奶", PickupItemFormatter.displayDetail(item(note = "买牛奶", isTodo = true)))
        assertEquals("", PickupItemFormatter.displayDetail(item(station = "+")))
        assertEquals(
            "465829330375829",
            PickupItemFormatter.displayDetail(item(station = "123", trackingNo = "465829330375829"))
        )
    }

    /** 历史数据：旧 note 里的自动模板按新规则重排，用户手写原样保留 */
    @Test
    fun legacyNote_isReassembled() {
        val old = item(note = "【兔喜生活超市】 | 单号：465829330375829")
        assertEquals("兔喜生活超市·465829330375829", PickupItemFormatter.displayDetail(old))
        assertEquals("", PickupItemFormatter.editableNote(old))

        val mixed = item(note = "放门口 | 【兔喜生活超市】 | 单号： 465829330375829")
        assertEquals("兔喜生活超市·465829330375829·放门口", PickupItemFormatter.displayDetail(mixed))
        assertEquals("放门口", PickupItemFormatter.editableNote(mixed))

        // 纯手写备注不能被误判成单号模板
        val plain = item(note = "帮室友代取")
        assertEquals("帮室友代取", PickupItemFormatter.displayDetail(plain))
    }

    /** 分享 / 复制导出：取件码 + 同一套三段拼接；无附属信息时只输出取件码 */
    @Test
    fun shareText_usesSameRule() {
        assertEquals(
            "3-7-2593·韵达快递  兔喜生活超市·465829330375829·放门口",
            PickupItemFormatter.shareText(
                item(station = "兔喜生活超市", trackingNo = "465829330375829", note = "放门口")
            )
        )
        assertEquals("3-7-2593·韵达快递", PickupItemFormatter.shareText(item()))
    }
}
