package com.hnu.pickupassistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bitmap 采样上限的纯函数单测（解码 IO 部分由构建 + 真机验收覆盖） */
class BitmapDecodeHelperTest {

    @Test
    fun smallImage_needsNoSampling() {
        assertEquals(1, BitmapDecodeHelper.sampleSize(1080, 1920))
        assertEquals(1, BitmapDecodeHelper.sampleSize(720, 1280))
    }

    @Test
    fun largeImage_isDownsampled() {
        // 4000x3000 = 12MP，sample=2 → 2000x1500 = 3MP
        val s = BitmapDecodeHelper.sampleSize(4000, 3000)
        assertEquals(2, s)
        assertTrue((4000L / s) * (3000 / s) <= BitmapDecodeHelper.MAX_PIXELS)
    }

    @Test
    fun longScreenshot_isCappedByEdge() {
        // 1440x12000 单边超 2560，逐级 *2 到 8 → 180x1500
        val s = BitmapDecodeHelper.sampleSize(1440, 12000)
        assertEquals(8, s)
        assertTrue(12000 / s <= BitmapDecodeHelper.MAX_EDGE)
    }

    @Test
    fun invalidBounds_fallbackToOne() {
        assertEquals(1, BitmapDecodeHelper.sampleSize(0, 0))
        assertEquals(1, BitmapDecodeHelper.sampleSize(-1, 100))
    }
}
