package com.hnu.pickupassistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板假码 / 漏码 BUG 的可执行回归测试（JUnit4，纯 JVM）。
 * 运行：JAVA_HOME=D:/AndroidDev/jdk-17 ./gradlew testDebugUnitTest --no-daemon
 *
 * @author 焱川 (by 焱川)
 */
class PickupCodeMatcherTest {

    private fun codes(text: String): List<String> =
        PickupCodeMatcher.extract(text).map { it.rawCode }

    /** ① 用户反馈的核心场景：日期被当成取件码，真码反而丢失 */
    @Test
    fun dateLikeCode_isRejected_realCodeIsKept() {
        val text = "妈妈驿站 取件码 706-48 有效期 11-10-2026"
        val result = codes(text)
        assertEquals(listOf("706-48"), result)
        assertFalse("11-10-2026 是日期，绝不能出现在识别结果里", result.contains("11-10-2026"))
    }

    @Test
    fun anotherDateLikeCode_isRejected() {
        val text = "取件码 706-48 单号 465829330375829 时间 1-2-2026"
        assertEquals(listOf("706-48"), codes(text))
    }

    /** ② 旧的「三段式优先」会丢掉两段式真码，现在必须全部识别 */
    @Test
    fun mixedTwoAndThreeSegment_allAreImported() {
        val text = "取件码 706-48\n取件码 706-10\n取件码 3-7-2593"
        val result = codes(text)
        assertEquals(3, result.size)
        assertTrue(result.contains("706-48"))
        assertTrue(result.contains("706-10"))
        assertTrue(result.contains("3-7-2593"))
    }

    /** ③ 纯取件码（无「取件码」关键字）仍要能识别，能力不回退 */
    @Test
    fun pureCodeWithoutKeyword_stillRecognized() {
        assertEquals(listOf("2-96-8503"), codes("2-96-8503"))
        assertEquals(listOf("3-7-2593"), codes("3-7-2593"))
    }

    /** ④ 两段式必须有关键字锚定，普通数字-数字（日期片段/金额）不能误判 */
    @Test
    fun twoSegmentRequiresKeyword() {
        assertTrue(codes("金额 12-34 日期 2026-11-10").isEmpty())
        assertTrue(codes("138-1234-5678").isEmpty())
    }

    /** ⑤ 「取件码 3-7-2593」不能被拆出重复的 3-7 */
    @Test
    fun noDuplicateOverlappingMatch() {
        assertEquals(listOf("3-7-2593"), codes("取件码 3-7-2593 韵达 465829330375829"))
    }

    /** ⑥ 层号前导零必须保留（6-04-406 不能被归一化成 6-4-406） */
    @Test
    fun leadingZeroIsPreserved() {
        assertEquals(listOf("6-04-406"), codes("取件码 6-04-406"))
    }

    /** ⑦ 真码后面跟「有效期」不会被日期引导词误杀 */
    @Test
    fun realCodeBeforeDateWord_isNotFiltered() {
        assertEquals(listOf("3-7-2593"), codes("取件码 3-7-2593 有效期 7 天"))
    }

    /** ⑧ 完整快递短信：只导入真实存在的一个码 */
    @Test
    fun realSms_onlyRealCode() {
        val sms = "【兔喜生活超市】取件码 3-7-2593，什贴门楼往南100米，" +
            "韵达快递 465829330375829，签收时间 2026-11-10 10:37"
        assertEquals(listOf("3-7-2593"), codes(sms))
    }

    /** ⑨ 空文本不产生任何码 */
    @Test
    fun blankText_returnsEmpty() {
        assertTrue(codes("").isEmpty())
        assertTrue(codes("   ").isEmpty())
    }

    // ========================================================================
    // 剪贴板多行清单：每行一个裸码（没有「取件码」关键字）也要全部识别
    // ========================================================================

    @Test
    fun multiLineBareCodes_allExtracted() {
        val clipboard = """
            706-48
            706-10
            3-7-2593
        """.trimIndent()
        assertEquals(listOf("3-7-2593", "706-48", "706-10"), codes(clipboard))
    }

    @Test
    fun multiLineBareCodes_withSurroundingText() {
        val clipboard = """
            今天的包裹：
            16-7-3597
            圆通 YT0724531187007
            27-5-1397
            邮政 9840831309908
        """.trimIndent()
        val result = codes(clipboard)
        assertTrue("应识别出 16-7-3597，实际=$result", result.contains("16-7-3597"))
        assertTrue("应识别出 27-5-1397，实际=$result", result.contains("27-5-1397"))
        assertEquals(2, result.size)
    }

    /** 整行裸码也要排除日期 / 电话，不能被「11-10」这种行骗到 */
    @Test
    fun multiLineBareCodes_dateLinesIgnored() {
        val clipboard = """
            2026-11
            11-10
            138-1234
            706-48
        """.trimIndent()
        assertEquals(listOf("706-48"), codes(clipboard))
    }

    /** 同一段文本里既有带关键字的码、又有裸码，不能重复计两次 */
    @Test
    fun mixedKeywordAndBareCodes_noDuplicates() {
        val clipboard = """
            取件码 706-48
            706-48
            3-7-2593
        """.trimIndent()
        val result = codes(clipboard)
        assertEquals(listOf("3-7-2593", "706-48"), result)
    }

    /**
     * 真机反馈的 BUG：`5-89-2046` 被当成日期（尾号 2046 像年份）误杀，
     * 结果一条码都识别不出、文本被兜底塞进待办。
     * 日期判定必须同时满足「中间是月份 ≤12」，真码不能杀。
     */
    @Test
    fun codeEndingWithYearLikeNumber_isNotRejected() {
        assertEquals(listOf("5-89-2046"), codes("5-89-2046"))
        assertEquals(listOf("5-89-2046"), codes("取件码 5-89-2046"))
        // 真日期仍然要挡
        assertEquals(listOf("706-48"), codes("取件码 706-48 时间 1-2-2026"))
    }
}
