package com.hnu.pickupassistant.update

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hnu.pickupassistant.util.LiftedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 「取了吗」Gitee 在线更新 —— Compose 弹窗 + 统一宿主。
 *
 * 严格按需求实现的 UI 规则：
 *  - 展示版本号（versionName / versionCode）与可滚动的更新日志；
 *  - 固定两个按钮【立即更新】【稍后再说】；
 *  - forceUpdate = true 时隐藏【稍后再说】，且返回键 / 点外部 / onDismissRequest
 *    全部失效，弹窗不可关闭，必须完成下载安装；
 *  - 下载中在同一弹窗展示 8dp 圆角进度条、百分比与「已下载 / 总大小」。
 *
 * 配色全部取 MaterialTheme.colorScheme，自动跟随项目浅色 / 深色主题。
 *
 * @author 焱川 (by 焱川)
 */
private val UpdateDialogShape = RoundedCornerShape(16.dp)

/** 下载进度快照；percent < 0 表示总大小未知（不确定进度） */
data class DownloadProgress(
    val percent: Int,
    val receivedBytes: Long,
    val totalBytes: Long
) {
    val indeterminate: Boolean get() = percent < 0
}

/** 与设置弹窗一致的窗口处理：透明背景 + 系统均匀暗化，避免 ROM 方形硬边阴影 */
@Composable
private fun HyperDialogWindowEffect() {
    val dialogView = LocalView.current
    LaunchedEffect(dialogView) {
        var parent: ViewParent? = dialogView.parent
        while (parent != null) {
            if (parent is DialogWindowProvider) {
                val window = parent.window
                window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                window.decorView.elevation = 0f
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0.42f)
                break
            }
            parent = parent.parent
        }
    }
}

/** 16dp 圆角容器，取主题 surface 色 */
@Composable
private fun UpdateDialogSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .widthIn(max = 380.dp),
        shape = UpdateDialogShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
            content()
        }
    }
}

/** 8dp 圆角进度条 + 实时百分比 + 已下载 / 总字节 */
@Composable
private fun DownloadProgressBlock(progress: DownloadProgress?) {
    val progressModifier = Modifier
        .fillMaxWidth()
        .height(8.dp)
        .clip(RoundedCornerShape(8.dp))
    if (progress == null || progress.indeterminate) {
        LinearProgressIndicator(
            modifier = progressModifier,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    } else {
        LinearProgressIndicator(
            progress = { progress.percent / 100f },
            modifier = progressModifier,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (progress == null) {
                "准备下载…"
            } else if (progress.indeterminate) {
                "已下载 ${UpdateManager.formatBytes(progress.receivedBytes)}"
            } else {
                "${UpdateManager.formatBytes(progress.receivedBytes)} / " +
                    UpdateManager.formatBytes(progress.totalBytes)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (progress == null || progress.indeterminate) "—" else "${progress.percent}%",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 固定两个按钮【立即更新】【稍后再说】。
 * forceUpdate = true 时【稍后再说】整块不渲染，只留一个占满宽度的【立即更新】。
 */
@Composable
private fun UpdateActionButtons(
    forceUpdate: Boolean,
    downloading: Boolean,
    onUpdate: () -> Unit,
    onLater: () -> Unit
) {
    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!forceUpdate) {
            Button(
                onClick = onLater,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("稍后再说") }
        }
        Button(
            onClick = onUpdate,
            enabled = !downloading,
            modifier = if (forceUpdate) Modifier.fillMaxWidth() else Modifier.weight(1f),
            shape = RoundedCornerShape(50),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) { Text(if (downloading) "下载中…" else "立即更新") }
    }
}

/**
 * 发现新版本弹窗：版本号 + 更新日志 + 进度条 + 两个按钮。
 *
 * @param forceUpdateInfo 由 info.forceUpdate 决定：true 时弹窗不可关闭
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    progress: DownloadProgress? = null,
    error: String? = null,
    downloading: Boolean = false,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    /** 读取 / 唤起失败时的一键兜底：跳文件管理器定位 APK 手动安装；null = 不显示该入口 */
    onOpenFileManager: (() -> Unit)? = null
) {
    Dialog(
        // 强制更新：onDismissRequest 空实现，无法通过系统关闭
        onDismissRequest = { if (!info.forceUpdate) onLater() },
        properties = DialogProperties(
            dismissOnBackPress = !info.forceUpdate,
            dismissOnClickOutside = !info.forceUpdate,
            usePlatformDefaultWidth = false
        )
    ) {
        HyperDialogWindowEffect()
        UpdateDialogSurface {
            Text(
                text = "发现新版本 v${info.versionName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildList {
                    add("版本号 ${info.versionCode}")
                    if (info.forceUpdate) add("重要更新，需升级后继续使用")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            // 更新日志滚动区：多行日志不会把弹窗撑出屏幕
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Text(
                    text = info.updateLog,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }

            // 下载进度 / 失败原因：未开始下载时整块淡出，不占版面
            AnimatedVisibility(
                visible = downloading || error != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        // 读取 / 唤起失败的一键兜底：跳文件管理器定位 APK 手动安装
                        if (onOpenFileManager != null) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = onOpenFileManager,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "到【下载】文件夹手动安装",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        DownloadProgressBlock(progress)
                    }
                }
            }

            UpdateActionButtons(
                forceUpdate = info.forceUpdate,
                downloading = downloading,
                onUpdate = onUpdate,
                onLater = onLater
            )

            if (downloading && !info.forceUpdate) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "点【稍后再说】可取消下载",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 更新功能总宿主：首页 MainScreen() 里加一行 UpdateHost() 即可。
 *
 * 职责：
 *  ① 收集 UpdateManager.updateInfo（由首页 onCreate 自动检测 / 设置页手动检测写入）；
 *  ② 点【立即更新】启动协程下载，实时刷新进度条；
 *  ③ 下载完成自动唤起系统安装器（无需用户再点一次）；
 *  ④ 点【稍后再说】取消下载并关闭（forceUpdate 时该入口不存在）；
 *  ⑤ 所有异常兜住，界面销毁自动取消下载任务，绝不泄漏、绝不闪退。
 */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val info by UpdateManager.updateInfo.collectAsState()
    val scope = rememberCoroutineScope()

    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    // 最近一次下载成功的 APK：读取 / 唤起失败时用它跳文件管理器手动安装。
    // contentUri 指向公共【下载】文件夹里的那条 MediaStore 记录，优先用它定位。
    var lastApkFile by remember { mutableStateOf<java.io.File?>(null) }
    var lastApkUri by remember { mutableStateOf<Uri?>(null) }

    // 用户去系统设置开启「允许安装未知应用」后返回 App，UpdateManager 会自动接力唤起
    // 安装器；这里同步清掉弹窗残留的权限错误提示，不需要用户重新点【立即更新】
    LaunchedEffect(Unit) {
        UpdateManager.installResumed.collect {
            error = null
            downloading = false
        }
    }

    /** 停止下载任务并清空进度状态 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloading = false
        progress = null
    }

    /** 【稍后再说】：取消下载 → 关闭弹窗（仅非强制更新可走到这里） */
    fun onLater() {
        cancelDownload()
        error = null
        UpdateManager.dismiss()
    }

    /** 【立即更新】：OkHttp 多源回退下载（GitHub 直链 + 国内加速），进度回调刷新弹窗 */
    fun startDownload(current: UpdateInfo) {
        downloadJob?.cancel()
        error = null
        progress = null
        downloading = true
        downloadJob = scope.launch {
            try {
                val result = UpdateManager.downloadApk(
                    context.applicationContext, current
                ) { percent, received, total ->
                    progress = DownloadProgress(percent, received, total)
                }
                when (result) {
                    is DownloadResult.Success -> {
                        downloading = false
                        lastApkFile = result.apkFile
                        lastApkUri = result.contentUri
                        // ★ 下载完成自动唤起系统安装器（优先公共 Download 的 MediaStore Uri）
                        val message = UpdateManager.installApk(
                            context, result.apkFile, result.contentUri
                        )
                        if (message != null) {
                            // 读取 / 唤起失败：提示 + 弹窗里提供「打开文件管理器手动安装」入口
                            error = message
                            LiftedToast.show(message)
                        } else {
                            LiftedToast.show("下载完成，正在打开安装器")
                            // 非强制更新直接收起弹窗；强制更新保留弹窗，
                            // 用户若取消安装还能再点【立即更新】重新唤起
                            if (!current.forceUpdate) {
                                cancelDownload()
                                error = null
                                UpdateManager.dismiss()
                            }
                        }
                    }
                    is DownloadResult.Failed -> {
                        downloading = false
                        error = result.reason // 失败后【立即更新】恢复可点，可原地重试
                    }
                }
            } catch (t: CancellationException) {
                // 用户主动取消 / 界面销毁：状态已在 cancelDownload 里重置，不弹错误
            } catch (t: Throwable) {
                downloading = false
                error = "下载失败，请稍后重试"
            }
        }
    }

    // 宿主离开组合（Activity 销毁）时取消下载，避免持有已销毁 Context
    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    info?.let { current ->
        UpdateAvailableDialog(
            info = current,
            progress = progress,
            error = error,
            downloading = downloading,
            onUpdate = { if (!downloading) startDownload(current) },
            onLater = { onLater() },
            onOpenFileManager = {
                val ok = UpdateManager.openApkInFileManager(
                    context, lastApkFile, lastApkUri
                )
                if (!ok) {
                    LiftedToast.show(UpdateConfig.HINT_FILE_MANAGER_FAILED)
                } else {
                    LiftedToast.show("安装包保存在系统【下载】文件夹")
                }
            }
        )
    }
}
