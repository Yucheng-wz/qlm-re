package com.hnu.pickupassistant

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.service.FloatingWindowService
import com.hnu.pickupassistant.ocr.ScreenshotMemory
import com.hnu.pickupassistant.ui.MainScreen
import com.hnu.pickupassistant.ui.theme.PickupTheme
import com.hnu.pickupassistant.update.UpdateManager
import com.hnu.pickupassistant.util.LiftedToast
import com.hnu.pickupassistant.util.OcrHelper
import com.hnu.pickupassistant.util.ShizukuHelper
import com.hnu.pickupassistant.util.reportImportResult
import rikka.shizuku.Shizuku

/**
 * 「取了吗」主界面。
 *
 * @author 焱川 (by 焱川)
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 悬浮窗「读取剪贴板」按钮拉起前台 Activity 时携带的 action */
        const val ACTION_READ_CLIPBOARD = "com.hnu.pickupassistant.ACTION_READ_CLIPBOARD"
    }

    private val snackbarHostState = SnackbarHostState()
    private var pendingManualRead = false

    /**
     * 前台剪贴板监听：只要 App 在前台，每次复制新内容都会立即触发导入，
     * 不再只依赖「获得窗口焦点时读一次」而漏掉使用过程中陆续复制的取件码。
     */
    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        // 每次复制新内容都记入历史（无论是否自动导入），保证能读到历史多次复制的内容
        recordClipboardCurrent()
        if (LocalStorageManager.autoReadClipboardFlow.value) {
            checkClipboardAndAutoImport()
        }
    }

    // 申请 SYSTEM_ALERT_WINDOW 悬浮窗权限回调
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            FloatingWindowService.start(this)
            Toast.makeText(this, "已开启看码悬浮窗", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予悬浮窗权限，无法开启看码小窗", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalStorageManager.init(applicationContext)
        // 首页 onCreate 自动检测 Gitee 在线更新：
        // 内部按 SharedPreferences 限流（最少间隔 6 小时），失败静默放弃，绝不阻塞首屏
        UpdateManager.checkOnLaunch(applicationContext)
        // Shizuku 授权结果监听：授权成功后「看见没」即可免弹窗抓屏
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }

        setContent {
            PickupTheme {
                MainScreen(
                    snackbarHostState = snackbarHostState,
                    onToggleFloatingWindow = { handleToggleFloatingWindow() }
                )
            }
        }
        if (intent?.action == ACTION_READ_CLIPBOARD) pendingManualRead = true
        handleIncomingIntent(intent)
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuHelper.REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Toast.makeText(
                    this,
                    if (granted) "Shizuku 已授权，「看见没」将免弹窗抓屏"
                    else "未获得 Shizuku 授权，仍可用系统录屏授权",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        // 兜底释放【截图对照】的 Bitmap 与临时截图文件，防止 Activity 销毁后内存泄漏
        ScreenshotMemory.release(applicationContext, "activity-destroy")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_READ_CLIPBOARD) pendingManualRead = true
        handleIncomingIntent(intent)
    }

    /**
     * 从「允许安装未知应用」系统设置页返回时，自动接力唤起已下载 APK 的安装器，
     * 不重新下载、也不需要用户再点一次【立即更新】。
     */
    override fun onResume() {
        super.onResume()
        try {
            UpdateManager.resumePendingInstall(applicationContext)
        } catch (t: Throwable) {
            // 接力失败不影响主流程，安装包仍可从【下载】文件夹手动安装
        }
    }

    override fun onStart() {
        super.onStart()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipChangedListener)
    }

    override fun onStop() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.removePrimaryClipChangedListener(clipChangedListener)
        super.onStop()
    }

    /**
     * 系统分享入口：从相册/截图分享图片（OCR 识别取件码），或分享短信文本（正则提取）。
     * 两种都不需要网络，处理后清空 intent，避免旋转屏幕后重复导入。
     */
    private fun handleIncomingIntent(source: Intent?) {
        if (source == null || source.action != Intent.ACTION_SEND) return
        val type = source.type ?: return

        when {
            type.startsWith("image/") -> {
                @Suppress("DEPRECATION")
                val uri = source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                LiftedToast.show("正在识别图片中的取件码…")
                OcrHelper.recognizeAndImport(this, uri) { result ->
                    reportImportResult(this, result, "图片识别", lifted = true)
                }
            }
            type == "text/plain" -> {
                val text = source.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val result = LocalStorageManager.extractAndImportFromText(text)
                reportImportResult(this, result, "分享导入", lifted = true)
            }
            else -> return
        }
        setIntent(Intent())
    }

    /**
     * 模块二（3）：监听 MainActivity 窗口焦点生命周期
     * Android 10 (API 29)+ 规定只有当应用处于前台且获得窗口焦点 (onWindowFocusChanged(true)) 时，
     * 才能正常读取系统剪贴板内容。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (pendingManualRead) {
                // 用户主动点了悬浮窗的「读取剪贴板」：跳过时间窗口与内容去重，立即读一次
                pendingManualRead = false
                readClipboardManually()
            } else {
                checkClipboardAndAutoImport()
            }
        }
    }

    /** 返回 Pair(剪贴板全文, 系统提供的时间戳)；无内容或读不到时返回 null */
    private fun readClipboard(): Pair<String, Long>? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        if (!clipboard.hasPrimaryClip()) return null
        val clipData = clipboard.primaryClip ?: return null
        if (clipData.itemCount <= 0) return null

        val combinedText = buildString {
            for (i in 0 until clipData.itemCount.coerceAtMost(10)) {
                val itemText = clipData.getItemAt(i).coerceToText(this@MainActivity)
                if (!itemText.isNullOrBlank()) {
                    append(itemText).append("\n")
                }
            }
        }
        if (combinedText.isBlank()) return null

        val clipTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                clipData.description?.timestamp ?: 0L
            } catch (_: Throwable) {
                0L
            }
        } else {
            0L
        }
        return combinedText to clipTimestamp
    }

    private fun checkClipboardAndAutoImport() {
        // 设置里关掉「自动读取剪切板」后，前台不再自动导入（手动走「剪切板添加」按钮）
        if (!LocalStorageManager.autoReadClipboardFlow.value) return
        try {
            val (combinedText, clipTimestamp) = readClipboard() ?: return

            // 只导入一分钟内复制的内容，很久以前复制的旧取件码不再自动入库
            if (!LocalStorageManager.isClipboardFresh(combinedText, clipTimestamp)) return
            // 同一段剪贴板只处理一次：新复制的内容能导入，删掉的记录不会被加回来
            if (!LocalStorageManager.isClipboardUnprocessed(combinedText)) return
            LocalStorageManager.markClipboardProcessed(combinedText)

            val result = LocalStorageManager.extractAndImportFromText(combinedText, maxItems = 10)
            if (result.added > 0) {
                // 只保留白色 Toast 提示，不再叠加黑色 Snackbar
                LiftedToast.show("已从剪贴板自动导入 ${result.added} 个取件码")
            }
        } catch (_: Throwable) {
            // 忽略部分定制 ROM 剪贴板安全限制异常
        }
    }

    /** 把当前剪贴板内容记入历史（去重、置顶） */
    private fun recordClipboardCurrent() {
        try {
            val (text, _) = readClipboard() ?: return
            LocalStorageManager.recordClipboardHistory(text)
        } catch (_: Throwable) {
        }
    }

    /**
     * 用户主动触发的剪贴板读取：读取「历史多次复制的内容」合并后一次性批量导入取件码
     * （旧版能力，完整保留）。不受「一分钟内」和「同一段只处理一次」限制；仅导入取件码。
     *
     * 修复解析 bug：把每条历史记录作为独立分段传给解析器——取件码仍从合并全文一次批量提取
     * （批量导入多条历史的能力不变），但快递公司 / 驿站名称按「码所在的那一条记录」识别。
     * 纯取件码记录（如 2-96-8503）内没有公司关键词、没有驿站文字 → 公司为空、备注为空，
     * 不再被旧消息里的「韵达」「云集路申通快递」污染，也不写死任何默认值。
     */
    private fun readClipboardManually() {
        try {
            // 先记入当前剪贴板，保证最新一次也在历史里
            recordClipboardCurrent()
            // 旧版功能：把历史多次复制的内容合并成一段文本，一次导入全部取件码
            val historyText = LocalStorageManager.clipboardHistoryText()
            if (historyText.isBlank()) {
                LiftedToast.show("剪贴板为空或无法读取")
                return
            }
            val result = LocalStorageManager.extractAndImportFromText(
                historyText,
                maxItems = 10,
                segments = LocalStorageManager.loadClipboardHistory()
            )
            when {
                result.added > 0 -> LiftedToast.show("已从剪贴板导入 ${result.added} 个取件码")
                result.duplicates > 0 -> LiftedToast.show("剪贴板里的取件码都已在列表中")
                else -> {
                    // 剪贴板仅导入取件码：未识别到取件码时不读取其它待办信息
                    LiftedToast.show("剪贴板中未识别到取件码")
                }
            }
        } catch (e: Throwable) {
            LiftedToast.show("读取剪贴板失败：${e.localizedMessage ?: "系统限制"}")
        }
    }

    private fun handleToggleFloatingWindow() {
        if (FloatingWindowService.isRunningFlow.value) {
            FloatingWindowService.stop(this)
            return
        }

        if (Settings.canDrawOverlays(this)) {
            FloatingWindowService.start(this)
        } else {
            Toast.makeText(this, "请授予「显示在其他应用的上层」权限以启用看码悬浮窗", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }
}
