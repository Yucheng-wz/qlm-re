package com.hnu.pickupassistant.model

/**
 * 快捷按钮的配色与图标预设。
 *
 * 只提供「色块 + 通用图标」，不出现任何软件名称、商标或官方标识；
 * 颜色为常见色值，图标均为 Material 通用图标，由 UI 层按 iconName 映射。
 */
data class ButtonStyle(
    val key: String,
    val colorValue: Long,   // ARGB
    val iconName: String
)

object ButtonStyles {
    val ORANGE = ButtonStyle("orange", 0xFFFF5722L, "qr")
    val RED = ButtonStyle("red", 0xFFE53935L, "bag")
    val DEEP_RED = ButtonStyle("deep_red", 0xFFC62828L, "store")
    val BLUE = ButtonStyle("blue", 0xFF1976D2L, "box")
    val BLACK = ButtonStyle("black", 0xFF212121L, "play")

    val ALL = listOf(ORANGE, RED, DEEP_RED, BLUE, BLACK)

    fun find(key: String): ButtonStyle? = ALL.firstOrNull { it.key == key }
}
