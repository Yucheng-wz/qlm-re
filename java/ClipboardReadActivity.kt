package com.hnu.pickupassistant

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.hnu.pickupassistant.data.LocalStorageManager

/**
 * 悬浮窗「读取剪贴板」的透明跳板。
 *
 * Android 10+ 只有获得窗口焦点的应用才能读到系统剪贴板，后台 Service 直接读会拿到空值；
 * 但把 MainActivity 拉到前台又会打断用户。这里用一个完全透明、读完即走的 Activity：
 * 只短暂取得焦点（视觉上无感），在后台完成剪贴板导入，不再跳回主界面。
 *
 * 本应用持有 SYSTEM_ALERT_WINDOW 权限，悬浮窗服务可以从后台启动该 Activity。
 */
class ClipboardReadActivity : ComponentActivity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalStorageManager.init(applicationContext)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            readClipboardAndImport()
            finish()
        }
    }

    /** 手动读取语义：不受「一分钟内」和「同一段只处理一次」限制；读取历史多次复制的内容，仅导入取件码 */
    private fun readClipboardAndImport() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipData = clipboard?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val currentText = buildString {
                    for (i in 0 until clipData.itemCount.coerceAtMost(10)) {
                        val itemText = clipData.getItemAt(i).coerceToText(this@ClipboardReadActivity)
                        if (!itemText.isNullOrBlank()) {
                            append(itemText).append("\n")
                        }
                    }
                }
                if (currentText.isNotBlank()) {
                    LocalStorageManager.recordClipboardHistory(currentText)
                }
            }
            // 读取历史多次复制的内容合并导入（旧版批量能力，保留）
            val text = LocalStorageManager.clipboardHistoryText()
            if (text.isBlank()) {
                Toast.makeText(this, "剪贴板为空或无法读取", Toast.LENGTH_SHORT).show()
                return
            }

            // 取件码从合并全文一次批量提取；公司/驿站按每条历史单独识别，
            // 纯取件码记录（如 2-96-8503）公司为空、备注为空，不被旧消息污染
            val result = LocalStorageManager.extractAndImportFromText(
                text,
                maxItems = 10,
                segments = LocalStorageManager.loadClipboardHistory()
            )
            when {
                result.added > 0 -> Toast.makeText(
                    this, "已从剪贴板导入 ${result.added} 个取件码", Toast.LENGTH_SHORT
                ).show()
                result.duplicates > 0 -> Toast.makeText(
                    this, "剪贴板里的取件码都已在列表中", Toast.LENGTH_SHORT
                ).show()
                else -> {
                    // 剪贴板仅导入取件码：未识别到取件码时不读取其它待办信息
                    Toast.makeText(
                        this,
                        "剪贴板中未识别到取件码",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Throwable) {
            Toast.makeText(
                this,
                "读取剪贴板失败：${e.localizedMessage ?: "系统限制"}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
