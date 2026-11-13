package com.hnu.pickupassistant.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.util.AutoCapture

/**
 * 通知自动收码。
 *
 * 只做三件事：拿到通知正文 → 交给 [AutoCapture] 预筛与解析 → 由 LocalStorageManager 统一入库去重。
 * 不申请任何短信权限也能工作，覆盖菜鸟、驿站短信、电商 App 的取件通知。
 *
 * 注意：本服务由系统绑定，不需要（也不应该）自己 startForeground；
 * 开关关闭时直接 return，不做任何读取。
 */
class PickupNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 服务可能在进程冷启动时被系统拉起，这里保证数据层可用
        LocalStorageManager.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val data = sbn ?: return
        try {
            LocalStorageManager.init(applicationContext)
            // 只在用户打开开关时工作；常驻通知（音乐/下载/自己的监听提示）一律跳过
            if (!LocalStorageManager.notifyCaptureFlow.value) return
            if (data.isOngoing) return
            if (data.packageName == packageName) return

            val notification: Notification = data.notification ?: return
            val extras = notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

            AutoCapture.handleNotification(
                context = applicationContext,
                packageName = data.packageName ?: return,
                title = title,
                extras = extras
            )
        } catch (e: Throwable) {
            Log.w(TAG, "处理通知失败: ${e.message}")
        }
    }

    /** 被系统断开时（部分 ROM 会主动摘掉）尝试重绑，恢复监听 */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            if (AutoCapture.isNotificationAccessGranted(applicationContext)) {
                requestRebind(ComponentName(this, PickupNotificationListener::class.java))
                Log.i(TAG, "监听断开，已请求重绑")
            }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "PickupNotifyListener"
    }
}
