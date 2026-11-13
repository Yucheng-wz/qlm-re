package com.hnu.pickupassistant.util

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.hnu.pickupassistant.data.ImportResult
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.service.PickupNotificationListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 自动收码（通知 / 短信）公共逻辑。
 *
 * 设计原则：
 *  - 全程本地：只在设备上读取通知正文 / 短信正文，交给现有正则解析后入库，不上传、不保存原文；
 *  - 默认关闭：两个开关都由用户在设置里主动打开，关闭时不注册任何前台服务、不做任何读取；
 *  - 单一出口：识别结果统一走 [LocalStorageManager.extractAndImportFromText]，
 *    因此与剪贴板 / 识图 / 手动输入共用同一套规则与去重逻辑（同码不会重复入库）。
 */
object AutoCapture {

    private const val TAG = "AutoCapture"
    private const val PREFS = "auto_capture_prefs"
    private const val KEY_LAST_SMS_TS = "last_sms_scan_ts"
    private const val SAME_BODY_WINDOW_MS = 2_000L
    private const val MAX_BODY_LENGTH = 2_000

    /** 快速预筛：不含这些词的正文直接跳过，避免对每条通知都跑正则 */
    private val PRE_FILTER_WORDS = arrayOf(
        "取件", "取货", "提货", "取码", "驿", "驿站", "快递", "包裹",
        "柜", "出库", "签收", "代收", "自提", "丰巢", "菜鸟", "妈妈驿站"
    )

    private val throttleLock = Any()
    private var lastBody: String? = null
    private var lastBodyTs = 0L

    // ==================== 权限 / 入口 ====================

    /** 是否已获得「通知使用权」（读系统 Secure 表，与系统设置页显示一致） */
    fun isNotificationAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        if (flat.isBlank()) return false
        val full = ComponentName(context, PickupNotificationListener::class.java).flattenToString()
        val short = "${context.packageName}/.service.PickupNotificationListener"
        return flat.split(":").any { it == full || it == short }
    }

    /** 跳到「通知使用权」设置；Android 11+ 可直达本应用单页 */
    fun openNotificationAccessSettings(context: Context) {
        val cn = ComponentName(context, PickupNotificationListener::class.java).flattenToString()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
        } catch (_: Throwable) {
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "打开通知使用权设置失败: ${e.message}")
        }
    }

    /** 是否已有短信读取权限 */
    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 小米/HyperOS：跳「其他权限 → 通知类短信」。
     * 该权限被拒时系统会静默过滤服务号短信（普通权限检查仍返回已授权），
     * 所以必须引导用户手动打开，否则表现为「权限正常但收不到取件短信」。
     */
    fun openMiuiSmsPermissionEditor(context: Context) {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: Throwable) {
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    fun isMiui(): Boolean = try {
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    } catch (_: Throwable) {
        false
    }

    // ==================== 通知通道 ====================

    /**
     * 处理一条通知：预筛 → 2 秒同文去重 → 交给统一解析入库。
     * @return 实际新增条数（0 表示没识别到 / 全是重复 / 开关关闭）
     */
    fun handleNotification(
        context: Context,
        packageName: String,
        title: String,
        extras: Bundle?
    ): Int {
        if (!LocalStorageManager.notifyCaptureFlow.value) return 0
        if (packageName == context.packageName) return 0
        if (extras == null) return 0

        val body = extractNotificationText(extras)
        if (body.isBlank() || body.length > MAX_BODY_LENGTH) return 0
        if (!looksLikePickup(body)) return 0
        if (isDuplicatedBody(body)) return 0

        val result = try {
            LocalStorageManager.extractAndImportFromText(text = body)
        } catch (e: Throwable) {
            Log.w(TAG, "通知解析失败: ${e.message}")
            return 0
        }
        if (result.added > 0) {
            val source = title.trim().ifBlank { "通知" }
            recordSummary("通知·$source", result.added)
        }
        return result.added
    }

    /**
     * 从通知 extras 里尽力取出正文，四级兜底（很多 App 只写其中一个字段）：
     * 1) EXTRA_BIG_TEXT / EXTRA_TEXT / android.text
     * 2) EXTRA_TEXT_LINES / android.textLines（取最后一条非空）
     * 3) MessagingStyle 的 android.messages（取最后一条的 text）
     */
    fun extractNotificationText(extras: Bundle): String {
        val main = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence("android.text"))
            ?.toString()
        if (!main.isNullOrBlank()) return main

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?: extras.getCharSequenceArray("android.textLines")
        val fromLines = lines?.mapNotNull { it?.toString() }?.lastOrNull { it.isNotBlank() }
        if (!fromLines.isNullOrBlank()) return fromLines

        val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray("android.messages", Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray("android.messages")
        }
        val lastMsgText = messages?.lastOrNull()
            ?.let { it as? Bundle }
            ?.getCharSequence("text")
            ?.toString()
        if (!lastMsgText.isNullOrBlank()) return lastMsgText

        return ""
    }

    // ==================== 短信通道 ====================

    /**
     * 扫描收件箱并导入。
     *
     * @param days 最多回看天数（首次开启建议 1~2 天）
     * @param onlyNew true 时只处理上次扫描之后的新短信（增量），避免每次全量重扫
     */
    fun importFromInbox(context: Context, days: Int = 1, onlyNew: Boolean = true): Int {
        if (!LocalStorageManager.smsCaptureFlow.value) return 0
        if (!hasSmsPermission(context)) return 0

        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastTs = if (onlyNew) sp.getLong(KEY_LAST_SMS_TS, 0L) else 0L
        val floor = System.currentTimeMillis() - days.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        val since = max(lastTs, floor)

        var maxDate = lastTs
        var added = 0
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "date >= ?",
                arrayOf(since.toString()),
                "date ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val body = cursor.getString(1) ?: continue
                    val date = cursor.getLong(2)
                    if (date > maxDate) maxDate = date
                    if (body.length > MAX_BODY_LENGTH) continue
                    if (!looksLikePickup(body)) continue
                    val result: ImportResult = try {
                        LocalStorageManager.extractAndImportFromText(text = body)
                    } catch (_: Throwable) {
                        continue
                    }
                    added += result.added
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "读取短信失败: ${e.message}")
            return 0
        }

        if (maxDate > lastTs) {
            sp.edit().putLong(KEY_LAST_SMS_TS, maxDate).apply()
        }
        if (added > 0) {
            recordSummary("短信", added)
        }
        return added
    }

    /** 用户手动点「立即扫描」时重置游标，回看指定天数 */
    fun forceScanInbox(context: Context, days: Int = 2): Int {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SMS_TS, 0L).apply()
        return importFromInbox(context, days = days, onlyNew = false)
    }

    // ==================== 内部 ====================

    private fun looksLikePickup(body: String): Boolean =
        PRE_FILTER_WORDS.any { body.contains(it, ignoreCase = true) }

    /** 同一条通知可能被系统重复投递（更新同一通知），2 秒内正文完全一样只处理一次 */
    private fun isDuplicatedBody(body: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(throttleLock) {
            if (body == lastBody && now - lastBodyTs < SAME_BODY_WINDOW_MS) return true
            lastBody = body
            lastBodyTs = now
        }
        return false
    }

    private fun recordSummary(source: String, added: Int) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val text = "$time  $source  新增 $added 条"
        LocalStorageManager.recordCaptureSummary(text)
        Log.i(TAG, "自动收码: $text")
    }
}
