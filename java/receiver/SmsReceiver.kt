package com.hnu.pickupassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.util.AutoCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 短信自动收码入口。
 *
 * 广播里不直接解析 pdus：一是字段可能不全，二是短信 App 写入收件箱有延迟。
 * 这里只把广播当「有新短信」的信号，稍等 1.2 秒后扫描最近 1 天的收件箱（增量），
 * 统一走 [AutoCapture.importFromInbox] 解析入库，和通知/剪贴板/识图共用同一套规则与去重。
 *
 * 用 goAsync() 把广播生命周期延长到扫描结束，因此无需常驻前台服务、不产生常驻通知。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext
        LocalStorageManager.init(app)

        // 开关关闭 / 没有短信权限：直接忽略，不做任何读取
        if (!LocalStorageManager.smsCaptureFlow.value) return
        if (!AutoCapture.hasSmsPermission(app)) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 等系统把这条短信写进收件箱（实测 200~800ms），避免读不到刚到的短信
                delay(1_200)
                val added = AutoCapture.importFromInbox(app, days = 1)
                if (added > 0) Log.i(TAG, "短信自动收码新增 $added 条")
            } catch (e: Throwable) {
                Log.w(TAG, "短信自动收码失败: ${e.message}")
            } finally {
                try {
                    pending.finish()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
