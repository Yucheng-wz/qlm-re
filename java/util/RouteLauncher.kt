package com.hnu.pickupassistant.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.hnu.pickupassistant.model.RouteConfig

object RouteLauncher {

    private const val LAUNCH_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

    /**
     * 通用三级降级容错跳转方法
     */
    fun launchWithFallback(context: Context, config: RouteConfig) {
        val pkg = config.packageName.trim()
        val targetAct = config.targetActivity.trim()
        val fallbackAct = config.fallbackActivity.trim()
        val scheme = config.schemeUri.trim()

        if (pkg.isEmpty()) {
            Toast.makeText(context, "目标应用包名不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        // ==================== 一级：Component + Data 强匹配 ====================
        if (targetAct.isNotEmpty() && scheme.isNotEmpty()) {
            try {
                val tier1Intent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(pkg, targetAct)
                    data = Uri.parse(scheme)
                    addFlags(LAUNCH_FLAGS)
                }
                context.startActivity(tier1Intent)
                return
            } catch (_: Throwable) {
                // 一级失败，自动进入二级降级
            }
        }

        // ==================== 二级：Package + Data 隐式匹配 ====================
        if (scheme.isNotEmpty()) {
            try {
                val tier2Intent = Intent(Intent.ACTION_VIEW).apply {
                    component = null
                    setPackage(pkg)
                    data = Uri.parse(scheme)
                    addFlags(LAUNCH_FLAGS)
                }
                context.startActivity(tier2Intent)
                return
            } catch (_: Throwable) {
                // 二级失败，自动进入三级兜底
            }
        }

        // ==================== 三级：兜底首页 / 主入口 ====================
        // 3.1 优先尝试配置的 fallbackActivity
        if (fallbackAct.isNotEmpty()) {
            try {
                val tier3ComponentIntent = Intent().apply {
                    component = ComponentName(pkg, fallbackAct)
                    addFlags(LAUNCH_FLAGS)
                }
                context.startActivity(tier3ComponentIntent)
                Toast.makeText(context, "路由失效，已为您打开应用首页", Toast.LENGTH_SHORT).show()
                return
            } catch (_: Throwable) {
                // 继续尝试 getLaunchIntentForPackage
            }
        }

        // 3.2 调用 packageManager.getLaunchIntentForPackage(packageName) 启动目标 App 首页
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(LAUNCH_FLAGS)
                context.startActivity(launchIntent)
                Toast.makeText(context, "路由失效，已为您打开应用首页", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "未检测到安装目标应用 ($pkg)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            Toast.makeText(context, "启动失败：${e.localizedMessage ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }
}
