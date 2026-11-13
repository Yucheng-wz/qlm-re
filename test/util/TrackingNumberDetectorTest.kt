package com.hnu.pickupassistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 运单号识别规则的纯函数单测。
 *
 * 锁死四条产品规则：
 *  1. 取件码优先（`28-5-5402`、含「取件码/驿站」的文本都不判单号）；
 *  2. 已知前缀可直接跳转（SF / YT / JT / JD …）；
 *  3. 纯数字长号只能算「疑似」，必须让用户确认；
 *  4. 清洗规则：大小写、空格横线、形近字符。
 */
class TrackingNumberDetectorTest {

    @Test
    fun `取件码两段式不判为单号`() {
        assertNull(TrackingNumberDetector.detect("28-5"))
        assertNull(TrackingNumberDetector.detect("8 5"))
    }

    @Test
    fun `取件码三段式不判为单号`() {
        assertNull(TrackingNumberDetector.detect("28-5-5402"))
        assertNull(TrackingNumberDetector.detect("5-89-2046"))
        assertNull(TrackingNumberDetector.detect("8—5—2046"))
    }

    @Test
    fun `含取件词的文本不判为单号`() {
        assertNull(TrackingNumberDetector.detect("菜鸟驿站 取件码 SF1234567890123"))
        assertNull(TrackingNumberDetector.detect("您的包裹已到 3 号柜，格口 12"))
    }

    @Test
    fun `顺丰单号强匹配`() {
        val r = TrackingNumberDetector.detect("SF1234567890123")
        assertNotNull(r)
        assertEquals(TrackingNumberDetector.Level.STRONG, r!!.level)
        assertEquals("顺丰", r.courier)
    }

    @Test
    fun `圆通与极兔单号强匹配`() {
        assertEquals("圆通", TrackingNumberDetector.detect("YT1234567890123")?.courier)
        assertEquals("极兔", TrackingNumberDetector.detect("JT1234567890123")?.courier)
    }

    @Test
    fun `京东单号带字母段也可识别`() {
        val r = TrackingNumberDetector.detect("JDVA12345678901")
        assertNotNull(r)
        assertEquals(TrackingNumberDetector.Level.STRONG, r!!.level)
    }

    @Test
    fun `纯数字长号只算疑似`() {
        val r = TrackingNumberDetector.detect("79156630053616")
        assertNotNull(r)
        assertEquals(TrackingNumberDetector.Level.SUSPECT, r!!.level)
        assertEquals("79156630053616", r.number)
    }

    @Test
    fun `普通待办文字不判为单号`() {
        assertNull(TrackingNumberDetector.detect("取完顺便寄件"))
        assertNull(TrackingNumberDetector.detect("买牛奶"))
        assertNull(TrackingNumberDetector.detect("1234"))
    }

    @Test
    fun `清洗大小写空格横线`() {
        val r = TrackingNumberDetector.detect("sf 1234 5678 9012 3")
        assertNotNull(r)
        assertEquals("SF1234567890123", r!!.number)
    }

    @Test
    fun `形近字符纠正 J7 与 Y7`() {
        assertEquals("JT1234567890123", TrackingNumberDetector.normalize("J71234567890123"))
        assertEquals("YT1234567890123", TrackingNumberDetector.normalize("Y71234567890123"))
    }

    @Test
    fun `形近字符纠正 l I O`() {
        assertEquals("SF1234567890123", TrackingNumberDetector.normalize("SFl234567890l23".replace("l", "I")))
        assertEquals("SF1234567890123", TrackingNumberDetector.normalize("SF123456789O123"))
    }
}
