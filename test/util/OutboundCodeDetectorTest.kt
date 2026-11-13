package com.hnu.pickupassistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出库码识别单元测试（JUnit4，纯 JVM）。
 *
 * 对应 BUG：「出库码被错误归入待办列表」—— 文本添加 / OCR 在识别不到取件码时
 * 会把整段文字兜底写成待办，出库码因此混进待办。这里把判定规则锁死。
 *
 * @author 焱川 (by 焱川)
 */
class OutboundCodeDetectorTest {

    @Test
    fun xnCode_isOutbound() {
        assertTrue(OutboundCodeDetector.isOutboundCode("XN123456789012"))
        assertTrue(OutboundCodeDetector.isOutboundCode("xn 1234 5678 9012"))
        assertTrue(OutboundCodeDetector.isOutboundCode("XN-1234567890"))
    }

    @Test
    fun keyword_isOutbound() {
        assertTrue(OutboundCodeDetector.isOutboundCode("出库码：123456"))
        assertTrue(OutboundCodeDetector.isOutboundCode("请出示身份码"))
        assertTrue(OutboundCodeDetector.isOutboundCode("取件凭证 889900"))
    }

    @Test
    fun pureLongBarcode_isOutbound() {
        assertTrue(OutboundCodeDetector.isOutboundCode("12345678901234567"))
    }

    @Test
    fun pickupCodes_areNotOutbound() {
        assertFalse(OutboundCodeDetector.isOutboundCode("706-48"))
        assertFalse(OutboundCodeDetector.isOutboundCode("取件码 16-7-3597"))
        assertFalse(OutboundCodeDetector.isOutboundCode("3-7-2593 韵达"))
        assertFalse(OutboundCodeDetector.isOutboundCode(""))
        assertFalse(OutboundCodeDetector.isOutboundCode("买牛奶 明天取"))
    }

    /** 快递单号不是出库码，不能被误拦（否则用户粘贴单号也不给建待办） */
    @Test
    fun trackingNumbers_areNotOutbound() {
        assertFalse(OutboundCodeDetector.isOutboundCode("YT0724531187007"))
        assertFalse(OutboundCodeDetector.isOutboundCode("465829330375829"))
        assertFalse(OutboundCodeDetector.isOutboundCode("JT5544316072358"))
    }

    // ========================================================================
    // 代码型文本：识别不到取件码时同样不建待办（兜底闸）
    // ========================================================================

    @Test
    fun codeLikeText_isDetected() {
        // 单号、条码、各种码串都算代码型文本 → 不建待办
        assertTrue(OutboundCodeDetector.isCodeLikeText("YT0724531187007"))
        assertTrue(OutboundCodeDetector.isCodeLikeText("465829330375829"))
        assertTrue(OutboundCodeDetector.isCodeLikeText("880012345678"))
        assertTrue(OutboundCodeDetector.isCodeLikeText("XN123456789012"))
        // 出库码当然也是代码型文本
        assertTrue(OutboundCodeDetector.isCodeLikeText("12345678901234567"))
    }

    @Test
    fun naturalLanguageTodo_isNotCodeLike() {
        assertFalse(OutboundCodeDetector.isCodeLikeText("买牛奶"))
        assertFalse(OutboundCodeDetector.isCodeLikeText("取快递，下午三点"))
        assertFalse(OutboundCodeDetector.isCodeLikeText(""))
    }

    /** 取件码形状必须放行（那是「待取」的数据，不该被当成出库码 / 代码文本） */
    @Test
    fun pickupShapedText_isNotOutboundNorCodeLike() {
        assertFalse(OutboundCodeDetector.isOutboundCode("5-89-2046"))
        assertFalse(OutboundCodeDetector.isCodeLikeText("5-89-2046"))
        assertFalse(OutboundCodeDetector.isCodeLikeText("706-48"))
    }
}
