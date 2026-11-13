package com.hnu.pickupassistant.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内轻量提示：替代系统 Toast 在主界面上的显示位置。
 *
 * 系统 Toast 固定贴在屏幕底部（部分 ROM 忽略 gravity/yOffset），会盖住底部输入框与
 * 「剪切板添加 / 识图添加 / 文本添加」按钮。这里只负责把文案投递给主界面，
 * 由 MainScreen 在「列表区域底部、输入区上方」绘制同样风格的白色圆角卡片，
 * 文案、触发时机与业务逻辑均保持原样。
 *
 * replay = 1：主界面尚未完成组合时（例如 onWindowFocusChanged 里自动导入剪贴板）
 * 发出的提示也不会丢失，组合完成后补显示；配合消息 id 避免重组时重复弹出。
 *
 * @author 焱川 (by 焱川)
 */
object LiftedToast {
    data class Message(
        val id: Long,
        val text: String,
        val atMillis: Long = System.currentTimeMillis()
    )

    private val ids = AtomicLong(0)
    private val _messages = MutableSharedFlow<Message>(replay = 1, extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /** 底部提示卡片只承载文案：撤销入口已改为「预删除条目上向右滑动恢复」 */
    fun show(text: String) {
        _messages.tryEmit(Message(ids.incrementAndGet(), text))
    }
}
