package com.hnu.pickupassistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.view.ViewParent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.sp
import com.hnu.pickupassistant.CaptureActivity
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.model.ButtonStyles
import com.hnu.pickupassistant.model.QuickButton
import com.hnu.pickupassistant.model.QuickButtonKind
import com.hnu.pickupassistant.model.QuickImportPresets
import com.hnu.pickupassistant.service.ScreenCaptureService
import com.hnu.pickupassistant.update.UpdateManager
import com.hnu.pickupassistant.util.AutoCapture
import com.hnu.pickupassistant.util.TrackingChannel
import com.hnu.pickupassistant.util.ShizukuHelper
import com.hnu.pickupassistant.ui.theme.HyperCard
import com.hnu.pickupassistant.ui.theme.HyperColors
import com.hnu.pickupassistant.ui.theme.HyperPrimaryButton
import com.hnu.pickupassistant.ui.theme.HyperRadius
import com.hnu.pickupassistant.ui.theme.HyperSecondaryButton
import com.hnu.pickupassistant.ui.theme.HyperSectionTitle
import com.hnu.pickupassistant.ui.theme.HyperSpacing
import com.hnu.pickupassistant.ui.theme.HyperTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun SettingsDialog(
    staticCodeEnabled: Boolean,
    onDismiss: () -> Unit,
    onStaticImageUpdated: () -> Unit
) {
    val context = LocalContext.current
    val buttons by LocalStorageManager.quickButtonsFlow.collectAsState()
    val showTodoInFloating by LocalStorageManager.showTodoInFloatingFlow.collectAsState()
    val autoReadClipboard by LocalStorageManager.autoReadClipboardFlow.collectAsState()
    val customStations by LocalStorageManager.customStationsFlow.collectAsState()
    val notifyCapture by LocalStorageManager.notifyCaptureFlow.collectAsState()
    val smsCapture by LocalStorageManager.smsCaptureFlow.collectAsState()
    val lastCapture by LocalStorageManager.lastCaptureFlow.collectAsState()
    val trackingChannel by LocalStorageManager.trackingChannelFlow.collectAsState()
    var newStation by remember { mutableStateOf("") }

    // ===== 自动收码：权限状态（从系统设置返回后需刷新） =====
    var hasNotifyAccess by remember { mutableStateOf(AutoCapture.isNotificationAccessGranted(context)) }
    var hasSmsPermission by remember { mutableStateOf(AutoCapture.hasSmsPermission(context)) }
    val scanScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotifyAccess = AutoCapture.isNotificationAccessGranted(context)
                hasSmsPermission = AutoCapture.hasSmsPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 短信权限申请结果：授权后立即开启开关并回看 2 天
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_SMS] == true
        hasSmsPermission = granted
        if (granted) {
            LocalStorageManager.setSmsCaptureEnabled(true)
            scanScope.launch(Dispatchers.IO) { AutoCapture.forceScanInbox(context, days = 2) }
            Toast.makeText(context, "已开启短信自动收码，正在扫描最近 2 天", Toast.LENGTH_SHORT).show()
        } else {
            LocalStorageManager.setSmsCaptureEnabled(false)
            Toast.makeText(context, "未授权短信权限，短信自动收码未开启", Toast.LENGTH_SHORT).show()
        }
    }

    var isStaticEnabled by remember(staticCodeEnabled) { mutableStateOf(staticCodeEnabled) }
    var hasSavedBarcode by remember {
        mutableStateOf(LocalStorageManager.hasStaticBarcodeImage(context))
    }
    var editorOpen by remember { mutableStateOf(false) }
    var editorInitial by remember { mutableStateOf<QuickButton?>(null) }
    var deleteTarget by remember { mutableStateOf<QuickButton?>(null) }
    var deleteImageConfirm by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val success = LocalStorageManager.saveStaticBarcodeImage(context, uri)
            if (success) {
                hasSavedBarcode = true
                isStaticEnabled = true
                LocalStorageManager.setStaticCodeEnabled(true)
                onStaticImageUpdated()
                Toast.makeText(context, "静态出库码截图已保存", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "保存图片失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        // 方形硬边的真正来源：Dialog 窗口比屏幕窄，窗口内再画一层矩形遮罩时，
        // 窗口内外暗度不同，就形成一圈直角「色块阴影」。这里不再自绘窗口遮罩，
        // 改由系统 FLAG_DIM_BEHIND 均匀暗化整屏；窗口背景与 elevation 也清零。
        val dialogView = LocalView.current
        LaunchedEffect(dialogView) {
            var parent: ViewParent? = dialogView.parent
            while (parent != null) {
                if (parent is DialogWindowProvider) {
                    val window = parent.window
                    window.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    )
                    window.decorView.elevation = 0f
                    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.setDimAmount(0.42f)
                    break
                }
                parent = parent.parent
            }
        }
        val cardBg = MaterialTheme.colorScheme.surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            // 阴影外框：不裁剪，阴影由应用手绘多层外扩圆角矩形，
            // 轮廓 100% 跟随 28dp 圆角，不依赖 ROM 的 elevation（会退化成直角）
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .widthIn(max = 460.dp)
                    .fillMaxHeight(0.92f)
                    .hyperDialogShadow()
            ) {
                // HyperOS 轻量悬浮卡片：100% 不透明纯色 + 统一 28dp 圆角 + 极细描边
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(cardBg, HyperRadius.Dialog)
                        .border(
                            width = 0.6.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                            shape = HyperRadius.Dialog
                        )
                        .clip(HyperRadius.Dialog)
                ) {
                    Column(Modifier.fillMaxSize()) {
                    // ====== 顶部标题栏：标题与「完成」同行，右上角放置完成按钮 ======
                    Column(
                        modifier = Modifier.padding(
                            start = HyperSpacing.Screen,
                            end = HyperSpacing.Screen,
                            top = 24.dp,
                            bottom = 10.dp
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "设置",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "取件 · 出库 · 悬浮窗",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = onDismiss,
                                shape = HyperRadius.Pill,
                                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                            ) {
                                Text("完成", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // ====== 可滚动内容区：纯色背景 + 不渲染内容的柔和渐隐 ======
                    val scrollState = rememberScrollState()
                    val settingsBody: @Composable () -> Unit = {
                // ==================== 静态出库码图片兜底 ====================
                HyperCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "静态图兜底",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "无信号时，长按按钮或点悬浮窗【出库码】全屏高亮展示",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = isStaticEnabled,
                            onCheckedChange = {
                                isStaticEnabled = it
                                LocalStorageManager.setStaticCodeEnabled(it)
                            },
                            colors = HyperSwitchColors()
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (hasSavedBarcode) "状态：已保存本地身份码截图" else "状态：尚未选择截图",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasSavedBarcode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 这两个按钮不放图标：宽度有限，图标会把「更换截图 / 删除截图」挤成截断
                        HyperSecondaryButton(
                            text = if (hasSavedBarcode) "更换截图" else "选择截图",
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        )
                        HyperSecondaryButton(
                            text = "删除截图",
                            enabled = hasSavedBarcode,
                            onClick = { deleteImageConfirm = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ==================== 悬浮窗待办开关（默认关闭） ====================
                SettingsSection("悬浮窗")
                HyperCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "悬浮窗显示待办",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "开启后，待办事项显示在悬浮窗列表",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = showTodoInFloating,
                            onCheckedChange = { LocalStorageManager.setShowTodoInFloating(it) },
                            colors = HyperSwitchColors()
                        )
                    }
                }

                // ==================== 自动读取剪贴板 ====================
                SettingsSection("剪贴板")
                HyperCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自动读取剪贴板",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "复制文本自动导入取件码；关闭则显示手动按钮",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = autoReadClipboard,
                            onCheckedChange = { LocalStorageManager.setAutoReadClipboard(it) },
                            colors = HyperSwitchColors()
                        )
                    }
                }

                // ==================== 自动收码（通知 / 短信，默认关闭） ====================
                SettingsSection("自动收码")
                HyperCard {
                    // ---- 通知通道：不需要短信权限，靠「通知使用权」 ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "通知自动收码",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "从通知里提取取件码，仅本机解析",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = notifyCapture,
                            onCheckedChange = { enabled ->
                                LocalStorageManager.setNotifyCaptureEnabled(enabled)
                                if (enabled && !hasNotifyAccess) {
                                    AutoCapture.openNotificationAccessSettings(context)
                                }
                            },
                            colors = HyperSwitchColors()
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (hasNotifyAccess) "通知使用权 · 已开启" else "通知使用权 · 未开启",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasNotifyAccess) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    if (!hasNotifyAccess) {
                        Spacer(Modifier.height(8.dp))
                        HyperSecondaryButton(
                            text = "去开启通知使用权",
                            compact = true,
                            onClick = { AutoCapture.openNotificationAccessSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))

                    // ---- 短信通道：需要短信读取权限 ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "短信自动收码",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "收到取件短信后自动提取，本地完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = smsCapture,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    LocalStorageManager.setSmsCaptureEnabled(false)
                                } else if (hasSmsPermission) {
                                    LocalStorageManager.setSmsCaptureEnabled(true)
                                    scanScope.launch(Dispatchers.IO) {
                                        AutoCapture.forceScanInbox(context, days = 2)
                                    }
                                } else {
                                    smsPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.RECEIVE_SMS,
                                            Manifest.permission.READ_SMS
                                        )
                                    )
                                }
                            },
                            colors = HyperSwitchColors()
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (hasSmsPermission) "短信权限 · 已授权" else "短信权限 · 未授权",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasSmsPermission) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    if (AutoCapture.isMiui()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "小米机型需开启「通知类短信」权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HyperSecondaryButton(
                            text = if (hasSmsPermission) "立即扫描" else "授权权限",
                            compact = true,
                            onClick = {
                                if (hasSmsPermission) {
                                    scanScope.launch(Dispatchers.IO) {
                                        val added = AutoCapture.forceScanInbox(context, days = 2)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                if (added > 0) "已导入 $added 条取件码" else "最近 2 天没有新的取件短信",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                } else {
                                    smsPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.RECEIVE_SMS,
                                            Manifest.permission.READ_SMS
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (AutoCapture.isMiui()) {
                            HyperSecondaryButton(
                                text = "权限设置",
                                compact = true,
                                onClick = { AutoCapture.openMiuiSmsPermissionEditor(context) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (lastCapture.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "最近：$lastCapture",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ==================== 单号查询渠道 ====================
                SettingsSection("单号查询")
                HyperCard {
                    Text(
                        text = "识别运单号后跳转查询，不存入列表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = trackingChannel == TrackingChannel.TAOBAO,
                            onClick = { LocalStorageManager.setTrackingChannel(TrackingChannel.TAOBAO) },
                            label = { Text("淘宝", maxLines = 1, fontSize = 12.sp) },
                            shape = HyperRadius.Pill,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = null,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = trackingChannel == TrackingChannel.CAINIAO,
                            onClick = { LocalStorageManager.setTrackingChannel(TrackingChannel.CAINIAO) },
                            label = { Text("菜鸟", maxLines = 1, fontSize = 12.sp) },
                            shape = HyperRadius.Pill,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (trackingChannel == TrackingChannel.TAOBAO) {
                            "当前：淘宝 · 菜鸟查件页"
                        } else {
                            "当前：菜鸟 App 主页"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ==================== 常用驿站名称（识别命中自动填备注） ====================
                SettingsSection("常用驿站")
                HyperCard {
                    Text(
                        text = "添加常用驿站，识别命中自动填备注",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    customStations.forEach { station ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = station,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            SmallIconButton(
                                icon = Icons.Default.Delete,
                                desc = "删除驿站",
                                tint = MaterialTheme.colorScheme.error,
                                onClick = { LocalStorageManager.removeCustomStation(station) }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    HyperTextField(
                        value = newStation,
                        onValueChange = { newStation = it },
                        placeholder = "如：云集路申通快递",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    HyperSecondaryButton(
                        text = "添加驿站",
                        onClick = {
                            val s = newStation.trim()
                            if (s.isNotEmpty()) {
                                LocalStorageManager.addCustomStation(s)
                                newStation = ""
                                Toast.makeText(context, "已添加驿站", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ==================== 看见没：控制中心磁贴 ====================
                SettingsSection("屏幕识别")
                HyperCard {
                    Text(
                        text = "下拉控制中心添加「看见没」磁贴，点一下识别屏幕取件码并自动添加。本地识别，不上传不保存截图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    HyperPrimaryButton(
                        text = "试一下看见没",
                        icon = Icons.Default.QrCodeScanner,
                        onClick = {
                            try {
                                when {
                                    // 已授权：免弹窗抓屏
                                    ShizukuHelper.isReady() ->
                                        ScreenCaptureService.startWithShizuku(context)
                                    // Shizuku 已运行但未授权：先申请授权（结果回调里再抓屏）
                                    ShizukuHelper.isShizukuRunning() -> {
                                        if (ShizukuHelper.requestPermission()) {
                                            ScreenCaptureService.startWithShizuku(context)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "请在 Shizuku 弹窗中授权，或稍后重试",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                    // 无 Shizuku：回退 MediaProjection 授权流程
                                    else ->
                                        context.startActivity(
                                            Intent(context, CaptureActivity::class.java)
                                        )
                                }
                            } catch (e: Throwable) {
                                Toast.makeText(
                                    context,
                                    "无法启动屏幕识别：${e.localizedMessage ?: "系统限制"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ==================== 快捷按钮管理 ====================
                SettingsSection("快捷按钮")
                HyperCard {
                    buttons.forEachIndexed { index, button ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val style = ButtonStyles.find(button.styleKey)
                            val btnColor = Color(style?.colorValue ?: 0xFF7C6BF2L)
                            // 澎湃风格：彩色圆角方形图标容器 + 真实图标图形（非纯色圆点占位）
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(btnColor.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (button.kind == QuickButtonKind.IMAGE) {
                                        Icons.Default.QrCodeScanner
                                    } else {
                                        styleIcon(style?.iconName ?: "box")
                                    },
                                    contentDescription = null,
                                    tint = btnColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${index + 1}. ${button.label}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = if (button.kind == QuickButtonKind.ROUTE) "跳转 App 页面" else "本地二维码图片",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SmallIconButton(
                                icon = Icons.Default.KeyboardArrowUp,
                                desc = "上移",
                                enabled = index > 0,
                                onClick = { LocalStorageManager.moveQuickButton(button.id, -1) }
                            )
                            SmallIconButton(
                                icon = Icons.Default.KeyboardArrowDown,
                                desc = "下移",
                                enabled = index < buttons.size - 1,
                                onClick = { LocalStorageManager.moveQuickButton(button.id, 1) }
                            )
                            SmallIconButton(
                                icon = Icons.Default.Edit,
                                desc = "编辑",
                                onClick = {
                                    editorInitial = button
                                    editorOpen = true
                                }
                            )
                            SmallIconButton(
                                icon = Icons.Default.Delete,
                                desc = "删除",
                                tint = MaterialTheme.colorScheme.error,
                                onClick = { deleteTarget = button }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HyperSecondaryButton(
                        text = "+ 添加其他按钮",
                        onClick = {
                            editorInitial = null
                            editorOpen = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = {
                            LocalStorageManager.resetQuickButtons(context)
                            Toast.makeText(context, "已恢复默认按钮", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("恢复默认按钮", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // ==================== 版本更新（新增） ====================
                SettingsSection("版本更新")
                HyperCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                // 先关掉设置弹窗，避免更新弹窗与设置弹窗两层窗口叠在一起；
                                // 手动检测不受 6 小时限流，结果由首页 UpdateHost 统一收弹窗
                                onDismiss()
                                UpdateManager.checkManual(context)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "检查更新",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "检查是否有全新版本🤔",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ==================== 作者署名 ====================
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "by 焱川",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                    }   // 关闭 settingsBody lambda

                    // ====== 实际布局：单一清晰内容 + 纯色渐隐软边 ======
                    // 不再复制整份设置列表做高斯模糊：那份副本正是标题下方
                    // 出现淡字残影 / 疑似主页面文字泄露的根源。
                    Box(modifier = Modifier.weight(1f)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(horizontal = HyperSpacing.Screen)
                                .padding(top = 20.dp, bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(HyperSpacing.Item)
                        ) { settingsBody() }

                        // 顶部/底部：只用纯色渐隐软化滚动切边，不绘制任何文字副本，
                        // 因此标题下方永远是干净纯色，不会出现残影或透字。
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(14.dp)
                                .background(
                                    Brush.verticalGradient(
                                        0f to cardBg,
                                        1f to cardBg.copy(alpha = 0f)
                                    )
                                )
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(14.dp)
                                .background(
                                    Brush.verticalGradient(
                                        0f to cardBg.copy(alpha = 0f),
                                        1f to cardBg
                                    )
                                )
                        )
                    }   // 关闭内容区 Box
                }   // 关闭外层 Column
                }   // 关闭内层卡片 Box
            }   // 关闭阴影外框 Box
        }   // 关闭 Box
    }   // 关闭 Dialog

    // ==================== 新增 / 编辑按钮 ====================
    if (editorOpen) {
        QuickButtonEditorDialog(
            initial = editorInitial,
            onDismiss = { editorOpen = false },
            onSave = { button ->
                LocalStorageManager.upsertQuickButton(button)
                editorOpen = false
                Toast.makeText(context, "按钮已保存", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ==================== 删除按钮确认 ====================
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            shape = HyperRadius.Dialog,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除按钮") },
            text = { Text("确定删除「${target.label}」吗？绑定的本地图片也会一并删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        LocalStorageManager.deleteQuickButton(context, target.id)
                        deleteTarget = null
                        Toast.makeText(context, "已删除按钮", Toast.LENGTH_SHORT).show()
                    },
                    shape = HyperRadius.Pill
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // ==================== 删除静态图确认 ====================
    if (deleteImageConfirm) {
        AlertDialog(
            onDismissRequest = { deleteImageConfirm = false },
            shape = HyperRadius.Dialog,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除静态截图") },
            text = { Text("确定删除已保存的本地身份码截图吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        val ok = LocalStorageManager.deleteStaticBarcodeImage(context)
                        if (ok) {
                            hasSavedBarcode = false
                            isStaticEnabled = false
                            onStaticImageUpdated()
                            Toast.makeText(context, "已删除静态截图", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "删除失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                        deleteImageConfirm = false
                    },
                    shape = HyperRadius.Pill
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteImageConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 设置页分组大标题：明显大于卡片内功能标题，强化层级对比 */
@Composable
private fun SettingsSection(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp)
    )
}

/** 关闭态开关在白卡片上也要看得清，不能用系统默认的极淡灰 */
@Composable
private fun HyperSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = HyperColors.OnPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
    uncheckedTrackColor = Color(0xFFC1C8D4)
)

/** 设置列表里的小圆形图标按钮 */
@Composable
private fun SmallIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 新建 / 编辑快捷按钮。
 * ROUTE：跳转外部 App 指定页面；IMAGE：绑定一张本地二维码截图，点击全屏展示。
 */
@Composable
private fun QuickButtonEditorDialog(
    initial: QuickButton?,
    onDismiss: () -> Unit,
    onSave: (QuickButton) -> Unit
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: QuickButtonKind.ROUTE) }
    var pkg by remember { mutableStateOf(initial?.packageName ?: "") }
    var target by remember { mutableStateOf(initial?.targetActivity ?: "") }
    var fallback by remember { mutableStateOf(initial?.fallbackActivity ?: "") }
    var scheme by remember { mutableStateOf(initial?.schemeUri ?: "") }
    var styleKey by remember { mutableStateOf(initial?.styleKey ?: "") }
    val imageFileName = remember {
        initial?.imageFileName?.takeIf { it.isNotBlank() }
            ?: LocalStorageManager.newQuickButtonImageFileName()
    }
    var hasImage by remember {
        mutableStateOf(LocalStorageManager.hasQuickButtonImage(context, imageFileName))
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            hasImage = LocalStorageManager.saveQuickButtonImage(context, uri, imageFileName)
            Toast.makeText(
                context,
                if (hasImage) "二维码图片已保存" else "保存图片失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 自定义 Dialog：适配软键盘高度 + 表单可上下滚动（键盘弹起时仍能浏览全部输入项）
    Dialog(onDismissRequest = onDismiss) {
        // 让弹窗窗口在键盘弹出时调整尺寸（adjustResize），表单区随之收缩、可滚动
        val dialogView = LocalView.current
        LaunchedEffect(dialogView) {
            var p: ViewParent? = dialogView.parent
            while (p != null) {
                if (p is DialogWindowProvider) {
                    p.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    p.window.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    )
                    p.window.decorView.elevation = 0f
                    p.window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    p.window.setDimAmount(0.42f)
                    break
                }
                p = p.parent
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .widthIn(max = 460.dp)
                    .fillMaxHeight(0.92f)
                    .hyperDialogShadow()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface, HyperRadius.Dialog)
                        .border(
                            width = 0.6.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                            shape = HyperRadius.Dialog
                        )
                        .clip(HyperRadius.Dialog)
                ) {
                    Column(Modifier.fillMaxSize()) {
                    // 标题
                    Text(
                        text = if (initial == null) "添加按钮" else "编辑按钮",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            start = HyperSpacing.Screen,
                            end = HyperSpacing.Screen,
                            top = 22.dp,
                            bottom = 8.dp
                        )
                    )
                    // 表单区：weight(1f) 占满剩余高度、可滚动；聚焦底部输入框时自动滚到键盘上方，
                    // 聚焦上方输入框时可手动下滑查看全部 4 个输入项
                    Box(modifier = Modifier.weight(1f)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = HyperSpacing.Screen)
                                .padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                HyperTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = "按钮名称",
                    modifier = Modifier.fillMaxWidth()
                )

                // 两个类型选项等宽等高，避免长文字换行造成大小不一致
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = kind == QuickButtonKind.ROUTE,
                        onClick = { kind = QuickButtonKind.ROUTE },
                        label = {
                            Text("打开 App 页面", maxLines = 1, fontSize = 12.sp)
                        },
                        shape = HyperRadius.Pill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = kind == QuickButtonKind.IMAGE,
                        onClick = { kind = QuickButtonKind.IMAGE },
                        label = {
                            Text("显示二维码", maxLines = 1, fontSize = 12.sp)
                        },
                        shape = HyperRadius.Pill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = null,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 配色 / 图标预设：只显示色块与通用图标，不标注任何软件名称
                HyperSectionTitle("按钮样式 · 再点一次可恢复默认", modifier = Modifier.padding(bottom = 0.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ButtonStyles.ALL.forEach { style ->
                        val selected = styleKey == style.key
                        Surface(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 0.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = CircleShape
                                )
                                .clickable {
                                    styleKey = if (selected) "" else style.key
                                },
                            color = Color(style.colorValue),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = styleIcon(style.iconName),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "已选中",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (kind == QuickButtonKind.ROUTE) {
                    EditorField("PackageName", pkg) { pkg = it }
                    EditorField("TargetActivity", target) { target = it }
                    EditorField("SchemeUri / Intent Data", scheme) { scheme = it }
                    EditorField("FallbackActivity（兜底）", fallback) { fallback = it }

                    // 2×2 快捷导入网格
                    HyperSectionTitle("配置快捷导入", modifier = Modifier.padding(bottom = 0.dp))
                    QuickImportPresets.ALL.chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowPresets.forEach { preset ->
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            pkg = preset.packageName
                                            target = preset.targetActivity
                                            scheme = preset.schemeUri
                                            fallback = preset.fallbackActivity
                                            Toast.makeText(
                                                context,
                                                preset.hint.ifBlank { "已填入「${preset.label}」配置" },
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = preset.label,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                            if (preset.isDefault) {
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    "默认",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (rowPresets.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    Text(
                        text = "选择一张本地二维码截图，点击按钮即可全屏最高亮度展示。图片只存本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HyperSecondaryButton(
                        text = if (hasImage) "更换图片" else "选择图片",
                        icon = Icons.Default.Image,
                        onClick = { picker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (hasImage) "状态：已保存图片" else "状态：尚未选择图片",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasImage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                        }   // 关闭表单 Column
                    }   // 关闭表单区 Box
                    // 底部按钮：取消 / 保存
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = HyperSpacing.Screen, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(
                            onClick = {
                                val cleanLabel = label.trim()
                                if (cleanLabel.isEmpty()) {
                                    Toast.makeText(context, "请输入按钮名称", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (kind == QuickButtonKind.ROUTE && pkg.trim().isEmpty()) {
                                    Toast.makeText(context, "请填写目标应用包名", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (kind == QuickButtonKind.IMAGE && !hasImage) {
                                    Toast.makeText(context, "请先选择二维码图片", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                onSave(
                                    QuickButton(
                                        id = initial?.id ?: UUID.randomUUID().toString(),
                                        label = cleanLabel,
                                        kind = kind,
                                        packageName = pkg.trim(),
                                        targetActivity = target.trim(),
                                        fallbackActivity = fallback.trim(),
                                        schemeUri = scheme.trim(),
                                        imageFileName = if (kind == QuickButtonKind.IMAGE) imageFileName else "",
                                        styleKey = styleKey
                                    )
                                )
                            },
                            shape = HyperRadius.Pill
                        ) { Text("保存") }
                    }
                }   // 关闭外层 Column
                }   // 关闭内层卡片 Box
            }   // 关闭阴影外框 Box
        }   // 关闭 Box
    }   // 关闭 Dialog
}

/** 编辑弹窗里的字段（小标签 + 填充式输入框） */
@Composable
private fun EditorField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 5.dp)
        )
        HyperTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = label,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 样式预设里的通用图标，均为 Material 图标，与具体软件无关 */
private fun styleIcon(name: String): ImageVector = when (name) {
    "qr" -> Icons.Default.QrCodeScanner
    "bag" -> Icons.Default.ShoppingBag
    "store" -> Icons.Default.Store
    "box" -> Icons.Default.Inventory2
    "play" -> Icons.Default.PlayArrow
    else -> Icons.Default.Image
}


/**
 * 手绘圆角柔和阴影：多层逐渐外扩、半透明叠加的圆角矩形。
 * 完全由应用 DrawScope 绘制，不依赖 View 的 elevation / RenderNode / ROM 窗口阴影，
 * 因此不会出现与圆角不匹配的方形直角硬边，符合 HyperOS 轻量化悬浮观感。
 *
 * @author 焱川 (by 焱川)
 */
private fun Modifier.hyperDialogShadow(): Modifier = drawBehind {
    val radius = 28.dp.toPx()
    val maxExpand = 8.dp.toPx()
    val layers = 6
    for (step in layers downTo 1) {
        val e = maxExpand * step / layers
        drawRoundRect(
            color = HyperColors.Scrim.copy(alpha = 0.014f),
            topLeft = Offset(-e, -e + 1.5f.dp.toPx()),
            size = Size(size.width + e * 2f, size.height + e * 2f),
            cornerRadius = CornerRadius(radius + e, radius + e)
        )
    }
}
