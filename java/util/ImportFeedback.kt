package com.hnu.pickupassistant.util

import android.content.Context
import android.widget.Toast
import com.hnu.pickupassistant.data.ImportResult

/**
 * 统一的导入结果提示。区分三种情况，避免把「已存在」误报成「识别不了」。
 * lifted = true 时改走主界面内上移的提示卡片，避免遮挡底部输入区与功能按钮。
 */
fun reportImportResult(
    context: Context,
    result: ImportResult,
    source: String = "",
    lifted: Boolean = false
) {
    val prefix = if (source.isEmpty()) "" else "$source："
    val message = when {
        result.added > 0 -> "${prefix}成功导入 ${result.added} 个取件码"
        result.duplicates > 0 ->
            "${prefix}识别到 ${result.recognized} 个取件码，均已在待取列表中，无需重复添加"
        else -> "${prefix}未识别到有效取件码（支持 货架-层-尾号，如 28-5-5402）"
    }
    if (lifted) {
        LiftedToast.show(message)
    } else {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
