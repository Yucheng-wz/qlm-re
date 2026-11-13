package com.hnu.pickupassistant.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.model.ButtonStyles
import com.hnu.pickupassistant.model.PickupItem
import com.hnu.pickupassistant.model.QuickButton
import com.hnu.pickupassistant.model.QuickButtonKind
import com.hnu.pickupassistant.service.FloatingWindowService
import com.hnu.pickupassistant.ui.theme.HyperCard
import com.hnu.pickupassistant.ui.theme.HyperColors
import com.hnu.pickupassistant.ui.theme.HyperPrimaryButton
import com.hnu.pickupassistant.ui.theme.HyperRadius
import com.hnu.pickupassistant.ui.theme.HyperSecondaryButton
import com.hnu.pickupassistant.ui.theme.hyperAdaptiveContent
import com.hnu.pickupassistant.ui.theme.HyperSpacing
import com.hnu.pickupassistant.ui.theme.HyperTextField
import androidx.compose.ui.text.style.TextAlign
import com.hnu.pickupassistant.util.BitmapDecodeHelper
import com.hnu.pickupassistant.util.LiftedToast
import com.hnu.pickupassistant.util.PickupItemFormatter
import com.hnu.pickupassistant.util.OcrHelper
import com.hnu.pickupassistant.util.OutboundCodeDetector
import com.hnu.pickupassistant.util.RouteLauncher
import com.hnu.pickupassistant.util.TrackingLauncher
import com.hnu.pickupassistant.util.TrackingNumberDetector
import com.hnu.pickupassistant.update.UpdateHost
import com.hnu.pickupassistant.ocr.OcrPreviewHost
import com.hnu.pickupassistant.ocr.rememberOcrPreviewFlow
import com.hnu.pickupassistant.util.reportImportResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class
)
@Composable
fun MainScreen(
    snackbarHostState: SnackbarHostState,
    onToggleFloatingWindow: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val uiScope = rememberCoroutineScope()

    // 识图完成不自动弹键盘：选图返回后系统会把焦点还给输入框并恢复 IME。
    // 在按钮点击、选图返回、识别完成、窗口重新获得焦点这几个时点逐层收起，
    // 用户主动点击输入框仍然可以正常唤起键盘。
    fun dismissInput() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        if (lifecycle == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) dismissInput()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }

    val items by LocalStorageManager.pickupItemsFlow.collectAsState()
    val quickButtons by LocalStorageManager.quickButtonsFlow.collectAsState()
    val staticCodeEnabled by LocalStorageManager.staticCodeEnabledFlow.collectAsState()
    val autoReadClipboard by LocalStorageManager.autoReadClipboardFlow.collectAsState()
    val isFloatingRunning by FloatingWindowService.isRunningFlow.collectAsState()

    var manualInput by remember { mutableStateOf("") }
    var manualNote by remember { mutableStateOf("") }
    var noteEditItem by remember { mutableStateOf<PickupItem?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    // 疑似快递单号：弹窗让用户确认是否去菜鸟查询（避免把手机号/随手数字当单号乱跳）
    var pendingTracking by remember { mutableStateOf<String?>(null) }
    // 跳转失败的单号（淘宝与浏览器都打不开时提示，并告知已复制到剪贴板）
    var trackingJumpFailed by remember { mutableStateOf<String?>(null) }
    var showClearPickedConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showCodeImageFile by remember { mutableStateOf<File?>(null) }
    // 一级文件夹折叠状态：只有「待办」「待取」可折叠，货架二级分组固定展开
    var todoExpanded by remember { mutableStateOf(true) }
    var pickupExpanded by remember { mutableStateOf(true) }
    var staticImageVersion by remember { mutableIntStateOf(0) }

    // ===== 滑动删除：预删除倒计时 =====
    // 撤销只保留「预删除状态下条目上方向右滑动恢复」一种方式，
    // 删除后不再弹底部 Toast，也不再提供右滑批量撤销入口。
    /** 统一删除入口：直接移除记录，不弹出任何删除提示 */
    fun performDelete(target: PickupItem) {
        LocalStorageManager.deleteItem(target.id)
    }

    // ===== 预删除：左滑 ≥1/3 → 5 秒倒计时，期间右滑立刻取消 =====
    // 修复：一次可以同时预删除多条（每条各有自己的截止时间），
    // 不再因为又滑了第二条就把第一条的倒计时顶掉。
    var preDeleteDeadlines by remember { mutableStateOf(mapOf<String, Long>()) }
    var preDeleteSecondsMap by remember { mutableStateOf(mapOf<String, Int>()) }

    /** 进入预删除：同一条重复滑过阈值时重新计时；可与其它条目同时倒计时 */
    fun enterPreDelete(target: PickupItem) {
        preDeleteDeadlines = preDeleteDeadlines + (target.id to System.currentTimeMillis() + 5_000L)
    }

    /** 右滑恢复：立刻取消该条的预删除，条目复原、不会删除 */
    fun cancelPreDelete(id: String) {
        preDeleteDeadlines = preDeleteDeadlines - id
        preDeleteSecondsMap = preDeleteSecondsMap - id
    }

    // 按需唤醒的统一定时器：没有任何条目处于预删除时协程**完全不运行**，
    // 待机零 CPU 唤醒（旧实现是 LaunchedEffect(Unit) + while(true) delay(100L) 常驻空转）。
    // key 用「是否有待删除项」：false→true 启动，最后一条取消/到点后 true→false 自动取消。
    val latestItems by rememberUpdatedState(items)
    val latestDeadlines by rememberUpdatedState(preDeleteDeadlines)
    val hasPendingDeletes = preDeleteDeadlines.isNotEmpty()
    LaunchedEffect(hasPendingDeletes) {
        if (!hasPendingDeletes) {
            if (preDeleteSecondsMap.isNotEmpty()) preDeleteSecondsMap = emptyMap()
            return@LaunchedEffect
        }
        while (true) {
            val now = System.currentTimeMillis()
            // rememberUpdatedState：协程里始终读到最新的 deadlines，
            // 期间新滑入第二条 / 右滑取消某条都不会让计时器持有过期快照
            val snapshot = latestDeadlines
            if (snapshot.isEmpty()) break
            val expired = snapshot.filterValues { it <= now }.keys
            if (expired.isNotEmpty()) {
                preDeleteDeadlines = preDeleteDeadlines - expired
                preDeleteSecondsMap = preDeleteSecondsMap - expired
                expired.forEach { id ->
                    latestItems.firstOrNull { it.id == id }?.let { performDelete(it) }
                }
            }
            val seconds = (snapshot - expired).mapValues { (_, deadline) ->
                (((deadline - now + 999L) / 1000L).toInt()).coerceIn(1, 5)
            }
            if (seconds != preDeleteSecondsMap) preDeleteSecondsMap = seconds
            delay(100L)
        }
    }

    // 主界面提示：渲染在列表区域底部、输入区上方，不再压住输入框与底部功能按钮。
    // 用消息 id 去重，避免屏幕旋转等重组时把上一条提示重复弹出。
    var liftedToast by remember { mutableStateOf<LiftedToast.Message?>(null) }
    var shownToastId by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        LiftedToast.messages.collect { message ->
            // 只显示 5 秒内的新消息：主界面尚未组合时缓存的旧提示不再迟到弹出
            val fresh = System.currentTimeMillis() - message.atMillis < 5000L
            if (message.id != shownToastId && fresh) {
                shownToastId = message.id
                liftedToast = message
            } else if (message.id != shownToastId) {
                shownToastId = message.id
            }
        }
    }
    LaunchedEffect(liftedToast?.id) {
        val id = liftedToast?.id ?: return@LaunchedEffect
        // 所有提示统一停留 2.8 秒（底部撤销入口已移除）
        delay(2800L)
        if (liftedToast?.id == id) liftedToast = null
    }

    // ===== 新增：识图预览链路（坐标排序 + 分组解析 + 可编辑预览 + 去重入库）=====
    // 原有 imagePickerLauncher / OcrHelper 识别链路完整保留，只是不再由按钮触发
    val ocrPreviewPickerLauncher = rememberOcrPreviewFlow()

    // 原有：从相册/截图选图，本地离线 OCR 识别取件码并自动入库（保留备用链路）
    @Suppress("UNUSED_VARIABLE")
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            dismissInput()
            LiftedToast.show("正在识别图片中的取件码…")
            OcrHelper.recognizeAndImport(context, uri, manualNote) { result ->
                dismissInput()
                // 覆盖部分 ROM 在窗口恢复后延迟把焦点还给输入框的情况
                uiScope.launch { delay(220L); dismissInput() }
                if (result.added > 0) {
                    manualNote = ""
                    LiftedToast.show("已从图片识别并导入 ${result.added} 个取件码")
                } else {
                    reportImportResult(context, result, "图片识别", lifted = true)
                }
            }
        }
    }

    val unpickedItems = remember(items) { items.filter { !it.isPicked } }
    val pickedItems = remember(items) { items.filter { it.isPicked } }
    // 待办与包裹分开计数：只有待办时不再错误显示「待取包裹 1 件」
    val todoCount = remember(unpickedItems) { unpickedItems.count { it.isTodo } }
    val parcelCount = unpickedItems.size - todoCount
    // 两级文件夹：待办、待取（一级，可折叠）；待取下按货架二级分组（固定展开、不可折叠）
    val todoItems = remember(unpickedItems) { unpickedItems.filter { it.isTodo } }
    val pickupGroupedByShelf = remember(unpickedItems) {
        unpickedItems.filter { !it.isTodo }.groupBy { it.shelfNum }.toSortedMap()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 状态栏由 TopAppBar 自己吃 insets；内容区只避让底部手势小白条
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = HyperRadius.Control,
                    // 澎湃浅色圆角卡片风格：浅色底 + 深色文字，符合系统规范
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                    dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "取了吗",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    // 悬浮窗状态胶囊（短文案，给标题留足空间）
                    Surface(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(HyperRadius.Pill)
                            .clickable { onToggleFloatingWindow() },
                        color = if (isFloatingRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = HyperRadius.Pill
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = if (isFloatingRunning) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    hyperAdaptiveContent(HyperColors.TextPrimary, HyperColors.DarkTextPrimary)
                                },
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (isFloatingRunning) "已开启" else "悬浮窗",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isFloatingRunning) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    hyperAdaptiveContent(HyperColors.TextPrimary, HyperColors.DarkTextPrimary)
                                },
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { showSettingsDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "快捷按钮与出库码设置",
                            tint = hyperAdaptiveContent(HyperColors.TextPrimary, HyperColors.DarkTextPrimary),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.width(HyperSpacing.Screen))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ==================== 功能区（紧凑）====================
            Column(modifier = Modifier.padding(horizontal = HyperSpacing.Screen)) {
                // 核心按钮：横向一行、等分填满、两端对齐
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    quickButtons.forEachIndexed { index, button ->
                        QuickActionButton(
                            button = button,
                            index = index,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (button.kind == QuickButtonKind.IMAGE) {
                                    val file = LocalStorageManager.getQuickButtonImageFile(
                                        context, button.imageFileName
                                    )
                                    if (file.exists()) {
                                        showCodeImageFile = file
                                    } else {
                                        LiftedToast.show("请先在设置里为这个按钮选择二维码图片")
                                    }
                                } else {
                                    RouteLauncher.launchWithFallback(context, button.toRouteConfig())
                                }
                            },
                            onLongClick = {
                                if (staticCodeEnabled && LocalStorageManager.hasStaticBarcodeImage(context)) {
                                    showCodeImageFile = LocalStorageManager.getStaticBarcodeFile(context)
                                } else {
                                    LiftedToast.show("请先在设置中开启静态出库码兜底并选择截图")
                                    showSettingsDialog = true
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 迷你统计条：自适应宽度，统计文字与右侧按钮始终同一行、互不遮挡
                AdaptiveStatsRow(
                    parcelCount = parcelCount,
                    todoCount = todoCount,
                    pickedCount = pickedItems.size,
                    hasPicked = pickedItems.isNotEmpty(),
                    hasItems = items.isNotEmpty(),
                    onClearPicked = { showClearPickedConfirm = true },
                    onClearAll = { showClearAllConfirm = true }
                )
            }

            // ==================== 待取 / 待办显示区（最大化）====================
            // 与上下功能区交界处用半透明渐变过渡，避免一刀切的硬边界
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = HyperSpacing.Screen),
                        contentAlignment = Alignment.Center
                    ) {
                        HyperCard {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Inventory2,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "暂无包裹/待办",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                // 空态副标题：单行完整显示（不换行、不截断）。
                                // 按卡片可用宽度自动微缩字号（上限 11sp、下限 9.5sp），
                                // 系统字体放大到 1.5x~2x 时也不会截断或折行；
                                // 颜色取主题二级文字色（onSurfaceVariant，灰），深浅色自动适配，不硬编码色值。
                                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                    val density = LocalDensity.current
                                    val subtitle = "粘贴文字、识图添加、编辑待办"
                                    // 中文按方块字估算：每字约 1 个字宽，留 4% 余量
                                    val fitSp = with(density) {
                                        (maxWidth.toPx() / subtitle.length.coerceAtLeast(1) * 0.96f).toSp()
                                    }
                                    val subtitleSize = when {
                                        fitSp.value < 9.5f -> 9.5.sp
                                        fitSp.value > 11f -> 11.sp
                                        else -> fitSp
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = subtitleSize,
                                        lineHeight = subtitleSize * 1.35f,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = HyperSpacing.Screen),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp)
                    ) {
                        // ===== 一级文件夹：待办（可折叠，内含不可折叠的待办条目）=====
                        if (todoItems.isNotEmpty()) {
                            item(key = "todo_folder") {
                                FolderHeader(
                                    title = "待办",
                                    count = todoItems.size,
                                    countSuffix = "条",
                                    expanded = todoExpanded,
                                    dotColor = hyperAdaptiveContent(HyperColors.Warning, HyperColors.DarkWarning),
                                    onToggle = { todoExpanded = !todoExpanded }
                                )
                            }
                            if (todoExpanded) {
                                items(items = todoItems, key = { it.id }) { item ->
                                    PickupItemRow(
                                        item = item,
                                        onToggle = { LocalStorageManager.togglePickedStatus(item.id) },
                                        onDelete = { performDelete(item) },
                                        onEditNote = { noteEditItem = item },
                                        isPreDeleting = preDeleteDeadlines.containsKey(item.id),
                                        preDeleteSeconds = preDeleteSecondsMap[item.id] ?: 5,
                                        onEnterPreDelete = { enterPreDelete(item) },
                                        onCancelPreDelete = { cancelPreDelete(item.id) }
                                    )
                                }
                            }
                        }

                        // ===== 一级文件夹：待取（可折叠）→ 货架二级分组（固定展开）=====
                        if (pickupGroupedByShelf.isNotEmpty()) {
                            item(key = "pickup_folder") {
                                FolderHeader(
                                    title = "待取",
                                    count = parcelCount,
                                    countSuffix = "件待拿",
                                    expanded = pickupExpanded,
                                    dotColor = MaterialTheme.colorScheme.primary,
                                    onToggle = { pickupExpanded = !pickupExpanded }
                                )
                            }
                            if (pickupExpanded) {
                                pickupGroupedByShelf.forEach { (shelfNum, shelfItems) ->
                                    // 货架二级分组：固定展开、无折叠箭头、显示本组数量
                                    item(key = "shelf_header_$shelfNum") {
                                        ShelfLabel(shelfNum = shelfNum, count = shelfItems.size)
                                    }
                                    items(
                                        items = shelfItems,
                                        key = { it.id }
                                    ) { item ->
                                        PickupItemRow(
                                            item = item,
                                            onToggle = { LocalStorageManager.togglePickedStatus(item.id) },
                                            onDelete = { performDelete(item) },
                                            onEditNote = { noteEditItem = item },
                                            isPreDeleting = preDeleteDeadlines.containsKey(item.id),
                                            preDeleteSeconds = preDeleteSecondsMap[item.id] ?: 5,
                                            onEnterPreDelete = { enterPreDelete(item) },
                                            onCancelPreDelete = { cancelPreDelete(item.id) }
                                        )
                                    }
                                }
                            }
                        }

                        if (pickedItems.isNotEmpty()) {
                            item(key = "picked_section_header") {
                                Text(
                                    text = "完成 (${pickedItems.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)
                                )
                            }
                            items(
                                items = pickedItems,
                                key = { it.id }
                            ) { item ->
                                PickupItemRow(
                                    item = item,
                                    onToggle = { LocalStorageManager.togglePickedStatus(item.id) },
                                    onDelete = { performDelete(item) },
                                    onEditNote = { noteEditItem = item },
                                    isPreDeleting = preDeleteDeadlines.containsKey(item.id),
                                    preDeleteSeconds = preDeleteSecondsMap[item.id] ?: 5,
                                    onEnterPreDelete = { enterPreDelete(item) },
                                    onCancelPreDelete = { cancelPreDelete(item.id) },
                                    // 已完成条目：左滑 ≥1/3 直接删除，无预删除、无倒计时
                                    instantDelete = true
                                )
                            }
                        }
                    }
                }

                // 顶部渐变遮罩：功能区滑入列表的软过渡
                val scrimColor = MaterialTheme.colorScheme.background
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(scrimColor, scrimColor.copy(alpha = 0f))
                            )
                        )
                )
                // 底部渐变遮罩：列表滑入输入区的软过渡
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(scrimColor.copy(alpha = 0f), scrimColor)
                            )
                        )
                )

                // 提示卡片：钉在列表区底部、输入框上方；不占位、不遮挡底部按钮与输入控件
                LiftedToastHost(
                    message = liftedToast,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = HyperSpacing.Screen,
                            end = HyperSpacing.Screen,
                            bottom = 16.dp
                        )
                )
            }

            // ==================== 底部输入区（快速添加）====================
            // 输入时键盘弹出，用 imePadding 把整条输入栏抬起，避免被输入法遮挡。
            // 备注框：键盘弹出出现、收起隐藏。
            // 关键——备注框高度【直接由键盘 inset 进度驱动】（是 imeInset 的单调连续函数），
            // 与 imePadding 完全同源。这样主输入框位移 = ime 的单调函数，彻底消除「回弹/抽搐」。
            // 进度 >0.5 时备注框全高；进度 0.5→0 时线性缩到 0（提前完成收起/展开）。
            val density = LocalDensity.current
            val imeInset = WindowInsets.ime.getBottom(density)
            var imePeak by remember { mutableIntStateOf(0) }
            if (imeInset > imePeak) imePeak = imeInset
            val progress = if (imePeak > 0) (imeInset.toFloat() / imePeak).coerceIn(0f, 1f) else 0f
            val noteBoxHeight = (60f * ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)).dp
            // 「展开态」= 键盘弹起、备注框出现；只有此时两个输入框才显示纯文本「取消」
            val inputExpanded = noteBoxHeight > 4.dp
            Column(
                modifier = Modifier
                    .padding(horizontal = HyperSpacing.Screen, vertical = 8.dp)
                    .imePadding()
            ) {
                // 主输入框：取件码 / 待办事项（展开时右侧出现纯文本「取消」，一键清空本框）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HyperTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        placeholder = "取件码 / 待办事项",
                        height = 52.dp,
                        modifier = Modifier.weight(1f)
                    )
                    if (inputExpanded) CancelText(onClick = { manualInput = "" })
                }
                // 备注框：高度随键盘 inset 进度连续变化（底部对齐，收缩时从顶部裁切）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(noteBoxHeight),
                    contentAlignment = Alignment.BottomStart
                ) {
                    if (noteBoxHeight > 4.dp) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HyperTextField(
                                    value = manualNote,
                                    onValueChange = { manualNote = it },
                                    placeholder = "备注（选填）",
                                    height = 52.dp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (inputExpanded) CancelText(onClick = { manualNote = "" })
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 添加按钮同一行，整体靠右，高度压缩。
                // 设置里关掉「自动读取剪切板」时，行首多出「剪切板添加」手动按钮（紧凑等宽三等分）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!autoReadClipboard) {
                        HyperSecondaryButton(
                            text = "剪切板添加",
                            compact = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val note = manualNote
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    // 先记入当前剪贴板，保证最新一次也在历史里
                                    val clipData = clipboard?.primaryClip
                                    val currentText = buildString {
                                        if (clipData != null) {
                                            for (i in 0 until clipData.itemCount) {
                                                val t = clipData.getItemAt(i).coerceToText(context)
                                                if (!t.isNullOrBlank()) append(t).append("\n")
                                            }
                                        }
                                    }
                                    if (currentText.isNotBlank()) {
                                        LocalStorageManager.recordClipboardHistory(currentText)
                                    }
                                    // 读取历史多次复制的内容合并导入（旧版批量能力，保留）
                                    val text = LocalStorageManager.clipboardHistoryText()
                                    if (text.isBlank()) {
                                        LiftedToast.show("剪贴板为空或无法读取")
                                    } else {
                                        // 取件码从合并全文一次批量提取；公司/驿站按每条历史单独识别，
                                        // 纯取件码记录不会再被旧消息的「韵达 / 云集路申通快递」污染
                                        val result = LocalStorageManager.extractAndImportFromText(
                                            text,
                                            note,
                                            segments = LocalStorageManager.loadClipboardHistory()
                                        )
                                        when {
                                            result.added > 0 -> {
                                                manualNote = ""
                                                LiftedToast.show("已从剪贴板导入 ${result.added} 个取件码")
                                            }
                                            result.duplicates > 0 -> LiftedToast.show("剪贴板里的取件码都已在列表中")
                                            else -> {
                                                // 剪贴板仅导入取件码：未识别到取件码时不读取其它待办信息
                                                LiftedToast.show("剪贴板中未识别到取件码")
                                            }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    LiftedToast.show("读取剪贴板失败：${e.localizedMessage ?: "系统限制"}")
                                }
                            }
                        )
                    }
                    HyperSecondaryButton(
                        text = "识图添加",
                        icon = Icons.Default.Image,
                        compact = true,
                        modifier = if (autoReadClipboard) Modifier else Modifier.weight(1f),
                        onClick = {
                            // 打开选图前先收起键盘并清焦点，返回后系统不会再把 IME 自动拉起
                            dismissInput()
                            // 改走可编辑预览链路；原有 imagePickerLauncher 保留不删除
                            ocrPreviewPickerLauncher.launch("image/*")
                        }
                    )
                    HyperPrimaryButton(
                        text = "文本添加",
                        icon = Icons.Default.Add,
                        compact = true,
                        modifier = if (autoReadClipboard) Modifier else Modifier.weight(1f),
                        onClick = {
                            val text = manualInput
                            val note = manualNote
                            when {
                                // 兼容旧习惯：只填备注 → 添加一条待办
                                text.isBlank() && note.isNotBlank() -> {
                                    LocalStorageManager.addTodoItem(note)
                                    manualNote = ""
                                    LiftedToast.show("已添加待办")
                                }
                                text.isBlank() -> {
                                    LiftedToast.show("请输入取件码或待办事项")
                                }
                                else -> {
                                    // 先判运单号：命中则不入库、只跳去菜鸟查询
                                    // （取件码优先级最高，检测器内部已让位：命中取件码格式/取件词时返回 null）
                                    val track = TrackingNumberDetector.detect(text)
                                    val trackingHandled = if (track == null) {
                                        false
                                    } else when (track.level) {
                                        TrackingNumberDetector.Level.STRONG -> {
                                            val ok = TrackingLauncher.jumpToAddPackage(context, track.number)
                                            if (!ok) trackingJumpFailed = track.number
                                            true
                                        }
                                        TrackingNumberDetector.Level.SUSPECT -> {
                                            // 纯数字长号无法判定快递公司：弹窗让用户确认，绝不直接跳转
                                            pendingTracking = track.number
                                            true
                                        }
                                    }
                                    if (!trackingHandled) {

                                        val result = LocalStorageManager.extractAndImportFromText(text, note)
                                        when {
                                            result.added > 0 -> {
                                                manualInput = ""
                                                manualNote = ""
                                                LiftedToast.show("成功导入 ${result.added} 个取件码")
                                            }
                                            result.duplicates > 0 -> {
                                                // 已存在的取件码不再弹常驻提示，静默跳过（简短 Toast 告知即可）
                                                manualInput = ""
                                                manualNote = ""
                                                LiftedToast.show("识别到 ${result.recognized} 个取件码，已在待取列表中")
                                            }
                                            // 主框有文字但识别不出取件码 → 当作待办事项添加
                                            // 但出库码 / 代码类文本（条码、单号等）不能进待办，直接拦下
                                            result.recognized == 0 -> {
                                                when {
                                                    OutboundCodeDetector.isOutboundCode(text) -> {
                                                        manualInput = ""
                                                        LiftedToast.show(OutboundCodeDetector.OUTBOUND_HINT)
                                                    }
                                                    OutboundCodeDetector.isCodeLikeText(text) -> {
                                                        manualInput = ""
                                                        LiftedToast.show(OutboundCodeDetector.CODE_LIKE_HINT)
                                                    }
                                                    else -> {
                                                        val todoText = if (note.isBlank()) {
                                                            text.trim()
                                                        } else {
                                                            "${text.trim()} · ${note.trim()}"
                                                        }
                                                        LocalStorageManager.addTodoItem(todoText)
                                                        manualInput = ""
                                                        manualNote = ""
                                                        LiftedToast.show("已添加待办")
                                                    }
                                                }
                                            }
                                            else -> {
                                                LiftedToast.show("未识别到有效取件码（格式如 28-5-5402）")
                                            }
                                        }
                                    }}
                            }
                        }
                    )
                }
            }
        }
    }

    // 左滑过半即直接删除，不再弹二次确认（清除完成 / 清空全部仍保留原有确认弹窗）

    // ==================== 疑似运单号：用户手动确认 ====================
    // 纯数字长号无法判定快递公司，不直接跳转：确认后才跳，取消则按待办保存
    pendingTracking?.let { number ->
        AlertDialog(
            onDismissRequest = { pendingTracking = null },
            title = { Text("疑似快递单号") },
            text = {
                Text("「$number」看起来像快递单号，但无法确定是哪家快递。要去菜鸟查询取件码吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingTracking = null
                    val ok = TrackingLauncher.jumpToAddPackage(context, number)
                    if (!ok) trackingJumpFailed = number
                }) { Text("去查询") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingTracking = null
                    LocalStorageManager.addTodoItem(number)
                    manualInput = ""
                    LiftedToast.show("已添加待办")
                }) { Text("保存为待办") }
            }
        )
    }

    // ==================== 跳转失败兜底 ====================
    trackingJumpFailed?.let { number ->
        AlertDialog(
            onDismissRequest = { trackingJumpFailed = null },
            title = { Text("跳转失败") },
            text = {
                Text("未能打开查询页面，请手动打开淘宝或菜鸟搜索单号：$number")
            },
            confirmButton = {
                TextButton(onClick = { trackingJumpFailed = null }) { Text("知道了") }
            }
        )
    }

    // ==================== 二次确认弹窗：清除完成 ====================
    if (showClearPickedConfirm) {
        AlertDialog(
            onDismissRequest = { showClearPickedConfirm = false },
            shape = HyperRadius.Dialog,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("清除完成") },
            text = { Text("确定要同步删除已完成的待办和已取件的快递吗？共 ${pickedItems.size} 条记录。") },
            confirmButton = {
                Button(
                    onClick = {
                        LocalStorageManager.clearPickedItems()
                        showClearPickedConfirm = false
                    },
                    shape = HyperRadius.Pill
                ) { Text("确定清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearPickedConfirm = false }) { Text("取消") }
            }
        )
    }

    // ==================== 二次确认弹窗：一键清空全部 ====================
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            shape = HyperRadius.Dialog,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("一键清空全部") },
            text = { Text("确定要清空所有未取和已取的 ${items.size} 条记录吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    shape = HyperRadius.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        LocalStorageManager.clearAllItems()
                        showClearAllConfirm = false
                    }
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("取消") }
            }
        )
    }

    // ==================== 单条记录备注编辑弹窗 ====================
    val editingItem = noteEditItem
    if (editingItem != null) {
        // 历史数据里混着自动模板时，草稿只显示用户手写部分；结构化信息在保存时写回字段
        var draftNote by remember(editingItem.id) {
            mutableStateOf(PickupItemFormatter.editableNote(editingItem))
        }
        // 二次修正：驿站 / 公司 / 单号都可直接编辑，历史数据从旧 note 里还原初值
        val legacy = remember(editingItem.id) {
            PickupItemFormatter.parseLegacyNote(editingItem.note)
        }
        var draftStation by remember(editingItem.id) {
            mutableStateOf(editingItem.station.ifBlank { legacy.station })
        }
        var draftCourier by remember(editingItem.id) {
            mutableStateOf(editingItem.courier)
        }
        var draftTracking by remember(editingItem.id) {
            mutableStateOf(editingItem.trackingNo.ifBlank { legacy.trackingNo })
        }
        // 详情页预览同样按「驿站·单号·备注」拼接，且随编辑实时联动
        val detailPreview = if (editingItem.isTodo) {
            draftNote
        } else {
            PickupItemFormatter.join(draftStation, draftTracking, draftNote)
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        AlertDialog(
            onDismissRequest = { noteEditItem = null },
            shape = HyperRadius.Dialog,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(if (editingItem.isTodo) "编辑待办" else "备注 · ${editingItem.displayCode}")
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (detailPreview.isNotBlank()) {
                        Text(
                            text = detailPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    // 二次修正：OCR 识别错字不必删掉重加，直接在这里改
                    if (!editingItem.isTodo) {
                        Text(
                            text = "识别有误可直接修正下面三项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HyperTextField(
                            value = draftStation,
                            onValueChange = { draftStation = it },
                            placeholder = "驿站名称",
                            singleLine = true,
                            height = 52.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        HyperTextField(
                            value = draftCourier,
                            onValueChange = { draftCourier = it },
                            placeholder = "快递公司",
                            singleLine = true,
                            height = 52.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        HyperTextField(
                            value = draftTracking,
                            onValueChange = { draftTracking = it },
                            placeholder = "物流单号",
                            singleLine = true,
                            height = 52.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Text(
                        text = "记一下这是什么物品、帮谁代取或放在哪，留空即清除备注。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HyperTextField(
                        value = draftNote,
                        onValueChange = { draftNote = it },
                        placeholder = "例如：洗衣液 / 帮室友代取 / 上铺",
                        singleLine = false,
                        maxLines = 3,
                        height = 96.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        LocalStorageManager.updateParcelFields(
                            editingItem.id,
                            draftNote,
                            if (editingItem.isTodo) editingItem.station else draftStation,
                            if (editingItem.isTodo) editingItem.courier else draftCourier,
                            if (editingItem.isTodo) editingItem.trackingNo else draftTracking
                        )
                        noteEditItem = null
                    },
                    shape = HyperRadius.Pill
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    // 复制 / 分享导出：文案同样走「驿站·单号·备注」这套拼接规则
                    TextButton(onClick = {
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(
                                "取件码信息",
                                PickupItemFormatter.shareText(editingItem)
                            )
                        )
                        LiftedToast.show("已复制取件码信息")
                    }) { Text("复制") }
                    TextButton(onClick = { noteEditItem = null }) { Text("取消") }
                }
            }
        )
    }

    // ==================== 快捷按钮与静态出库码设置弹窗 ====================
    if (showSettingsDialog) {
        SettingsDialog(
            staticCodeEnabled = staticCodeEnabled,
            onDismiss = { showSettingsDialog = false },
            onStaticImageUpdated = { staticImageVersion++ }
        )
    }

    // ==================== 全屏最高亮度二维码展示弹窗 ====================
    showCodeImageFile?.let { file ->
        FullBrightnessStaticCodeDialog(
            imageFile = file,
            imageVersion = staticImageVersion,
            onDismiss = { showCodeImageFile = null }
        )
    }

    // ==================== 自建 Gitee 静态 JSON 自动更新 ====================
    // 首页 onCreate 自动检测（6 小时限流）+ 设置页【检查更新】手动检测，
    // 结果统一收敛到 UpdateHost 里弹窗；网络/解析异常全部静默，不影响原有业务。
    UpdateHost()

    // ==================== 识图添加：可编辑识别结果预览 ====================
    // 首页【识图添加】走新 launcher，识别结果统一在这里弹预览；
    // 原有 OcrHelper / 系统分享 / 屏幕识别 / 剪贴板等入口全部保留不变。
    OcrPreviewHost()
}

/** 可自定义的快捷按钮：彩色圆角卡片 + 半透明图标容器，点击执行动作，长按显示全局静态兜底码 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickActionButton(
    button: QuickButton,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 用户选过配色预设就按预设走，否则按位置使用蓝 / 橙 / 紫
    val style = ButtonStyles.find(button.styleKey)
    val color = if (style != null) {
        Color(style.colorValue)
    } else {
        when (index) {
            0 -> HyperColors.Primary
            1 -> Color(0xFFFF7A45)
            else -> Color(0xFF7C6BF2)
        }
    }
    val icon = when {
        style != null -> styleIcon(style.iconName)
        button.kind == QuickButtonKind.IMAGE -> Icons.Default.QrCodeScanner
        index == 0 -> Icons.Default.Inventory2
        index == 1 -> Icons.Default.QrCodeScanner
        else -> Icons.Default.Image
    }

    Surface(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = color,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = button.label,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1
            )
        }
    }
}

/** 样式预设对应的通用图标，与具体软件无关 */
private fun styleIcon(name: String): ImageVector = when (name) {
    "qr" -> Icons.Default.QrCodeScanner
    "bag" -> Icons.Default.ShoppingBag
    "store" -> Icons.Default.Store
    "box" -> Icons.Default.Inventory2
    "play" -> Icons.Default.PlayArrow
    else -> Icons.Default.Image
}

/**
 * 顶部统计条：按实际行宽自适应，统计文字与右侧按钮始终同一行、完整可见、互不遮挡。
 *
 * 只用 onSizeChanged 读取行宽 + 纯数值估算，不引入任何测量/子组合 API，
 * 也不对 TextUnit.Unspecified 做运算（项目自定义主题的 labelSmall 未设置
 * lineHeight/letterSpacing，对它们做乘法会在首次组合时抛异常导致闪退）。
 * 超宽时等比缩小汉字字号与按钮宽度，文字↔按钮固定 12dp 安全间距不压缩。
 *
 * @author 焱川 (by 焱川)
 */
@Composable
private fun AdaptiveStatsRow(
    parcelCount: Int,
    todoCount: Int,
    pickedCount: Int,
    hasPicked: Boolean,
    hasItems: Boolean,
    onClearPicked: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    // 深色下把统计文字由浅色主题的深灰/深黑切为高对比亮色，浅色保持原色不变
    val secondaryTextColor = hyperAdaptiveContent(HyperColors.TextSecondary, HyperColors.DarkTextSecondary)
    val primaryTextColor = hyperAdaptiveContent(HyperColors.TextPrimary, HyperColors.DarkTextPrimary)
    val warningColor = hyperAdaptiveContent(HyperColors.Warning, HyperColors.DarkWarning)
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var rowWidthPx by remember { mutableIntStateOf(0) }

    // 估算自然宽度（dp）：统计文字按 sp×fontScale，按钮内边距与间距按 dp
    val digits =
        parcelCount.toString().length + todoCount.toString().length + pickedCount.toString().length
    val statsSp = 150.5f + 9.5f * digits      // 6 汉字 + 2 全角竖线 + 空格 + 各位数字
    val buttonTextSp = 92f                    // 「清除完成」「清空全部」共 8 汉字
    val buttonPaddingDp = 40f                 // 两按钮左右各 10dp
    val fixedGapDp = 18f                      // 12dp 安全间距 + 6dp 按钮间距，不参与压缩
    // 6% 余量吸收字体宽度差异、字距与按钮最小交互尺寸
    val needDp = ((statsSp + buttonTextSp) * density.fontScale + buttonPaddingDp) * 1.06f

    // 首帧用屏幕宽度近似值，layout 完成后由 onSizeChanged 精确修正
    val availableDp = if (rowWidthPx > 0) {
        with(density) { rowWidthPx.toDp().value }
    } else {
        (configuration.screenWidthDp - 32f).coerceAtLeast(240f)
    }
    val scale = ((availableDp - fixedGapDp - 4f) / needDp).coerceIn(0.55f, 1f)

    // 汉字 14sp、数字 16sp 加粗：数字比汉字大两个字号，更突出
    val statsText = buildAnnotatedString {
        withStyle(SpanStyle(color = secondaryTextColor, fontSize = (14f * scale).sp)) { append("待取 ") }
        withStyle(
            SpanStyle(
                color = primaryColor,
                fontWeight = FontWeight.Bold,
                fontSize = (16f * scale).sp
            )
        ) { append("$parcelCount") }
        withStyle(SpanStyle(color = secondaryTextColor, fontSize = (14f * scale).sp)) { append("  ｜  待办 ") }
        withStyle(
            SpanStyle(
                color = warningColor,
                fontWeight = FontWeight.Bold,
                fontSize = (16f * scale).sp
            )
        ) { append("$todoCount") }
        withStyle(SpanStyle(color = secondaryTextColor, fontSize = (14f * scale).sp)) { append("  ｜  完成 ") }
        withStyle(
            SpanStyle(
                color = primaryTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = (16f * scale).sp
            )
        ) { append("$pickedCount") }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidthPx = it.width },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statsText,
            // 实测已保证剩余空间 >= 文字宽度；weight 负责把按钮推到右侧
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible
        )
        // 文字区与按钮之间固定安全间距，不参与压缩
        Spacer(Modifier.width(12.dp))
        SmallPillButton(
            text = "清除完成",
            scale = scale,
            enabled = hasPicked,
            onClick = onClearPicked
        )
        Spacer(Modifier.width(6.dp))
        SmallPillButton(
            text = "清空全部",
            scale = scale,
            enabled = hasItems,
            error = true,
            onClick = onClearAll
        )
    }
}

/** 迷你统计条上的小按钮：胶囊造型、文字级操作 */
@Composable
private fun SmallPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    scale: Float = 1f
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(30.dp),
        shape = HyperRadius.Pill,
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            hyperAdaptiveContent(HyperColors.TextPrimary, HyperColors.DarkTextPrimary)
        }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = (10f * scale).dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                // 只覆盖 fontSize；labelSmall 的 lineHeight / letterSpacing 是
                // TextUnit.Unspecified，不能参与任何算术运算
                style = labelStyle.copy(fontSize = (11f * scale).sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** 一级文件夹标题（待办 / 待取）：点击折叠/展开，标题旁有箭头提示 */
/**
 * 输入区展开态的「取消」：不用按钮样式，只显示纯文本，无背景无水波纹。
 * 点击一键清空对应输入框里已输入的文字；输入区收起（键盘落下）时完全不显示。
 */
@Composable
private fun CancelText(onClick: () -> Unit) {
    Text(
        text = "取消",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 10.dp, end = 2.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    )
}

@Composable
private fun FolderHeader(
    title: String,
    count: Int,
    countSuffix: String,
    expanded: Boolean,
    dotColor: Color,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(start = 4.dp, top = 6.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (expanded) 0f else -90f }
            )
        }
        Text(
            text = "共 $count $countSuffix",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 货架二级分组标签（固定展开、不可折叠、无箭头），显示本组物品数量 */
@Composable
private fun ShelfLabel(shelfNum: Int, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${shelfNum}号架",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = "共 $count 件待拿",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单条记录卡片：左滑「预删除」交互（澎湃 HyperOS 动效风格）。
 *
 * ① 左滑 < 条目宽度 1/3：松手后条目自动柔和弹回原位，无需手动右滑收回；
 * ② 左滑 ≥ 条目宽度 1/3：直接进入预删除状态 —— 底色变淡红半透明、
 *    卡片柔和归位，小字提示「向右滑动恢复 5」并逐秒数字动画倒计时。
 *    （中间「1/3~1/2 停在露出删除按钮」这一档已取消）
 *
 * 预删除 5 秒内向右滑动：立刻取消预删除，条目恢复原本底色、不会删除；
 * 5 秒倒计时结束：自动删除本条记录，不再弹底部 Toast、也没有删除后撤销入口。
 *
 * 判定取「松手瞬间的手指位移」；超过 1/3 触发线后加入橡皮筋阻尼，跟手但不生硬拖出。
 * 回弹 spring 的刚度随回弹距离自适应（短滑轻软、长滑利落），配合提前收敛阈值，
 * 起步跟手、收尾绵软无拖尾，贴合 HyperOS 动效质感。
 * 红色垃圾桶图标与滑出红底保持原有风格：浅色零变化、深色单独提亮。
 * 第二行固定为备注区域（不再展示「X号货架 · 第X层 · 尾号X」）。
 *
 * @author 焱川 (by 焱川)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickupItemRow(
    item: PickupItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEditNote: () -> Unit,
    isPreDeleting: Boolean,
    preDeleteSeconds: Int,
    onEnterPreDelete: () -> Unit,
    onCancelPreDelete: () -> Unit,
    /**
     * true = 左滑 ≥1/3 直接删除（已完成列表用）：无预删除、无倒计时、无恢复；
     * false = 进入 5 秒预删除倒计时（待取 / 待办用）。
     */
    instantDelete: Boolean = false
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var rowWidthPx by remember { mutableFloatStateOf(1f) }
    val offset = remember { Animatable(0f) }
    val numberScale = remember { Animatable(1f) }
    // 手势回调里读取最新预删除状态，避免 pointerInput 闭包持有旧值
    val preDeletingNow by rememberUpdatedState(isPreDeleting)
    val instantDeleteNow by rememberUpdatedState(instantDelete)

    // 预删除配色：浅色 / 深色各自一套「淡红半透明」，其余控件配色不动
    val preDeleteSurface = hyperAdaptiveContent(
        light = Color(0xFFEF5B5B).copy(alpha = 0.13f),
        dark = Color(0xFFFF7B7B).copy(alpha = 0.22f)
    )
    val preDeleteHintText = hyperAdaptiveContent(
        light = Color(0xFFA62D2D),
        dark = Color(0xFFFFC4C4)
    )
    val preDeleteHintBg = hyperAdaptiveContent(
        light = Color(0xFFFFEAEA).copy(alpha = 0.92f),
        dark = Color(0xFF5E2427).copy(alpha = 0.92f)
    )
    // 滑出红底 / 垃圾桶：浅色沿用系统原色（视觉零变化），深色单独提亮
    val swipeBackground = hyperAdaptiveContent(
        light = MaterialTheme.colorScheme.errorContainer,
        dark = Color(0xFF5E2427)
    )
    val swipeIconTint = hyperAdaptiveContent(
        light = MaterialTheme.colorScheme.error,
        dark = Color(0xFFFFA0A0)
    )
    // 底色渐变 + 数字 spring 弹入，贴合 HyperOS 柔和动效
    val cardSurface by animateColorAsState(
        targetValue = if (isPreDeleting) preDeleteSurface else MaterialTheme.colorScheme.surface,
        animationSpec = tween(260)
    )
    LaunchedEffect(isPreDeleting, preDeleteSeconds) {
        if (!isPreDeleting) return@LaunchedEffect
        numberScale.snapTo(0.55f)
        numberScale.animateTo(1f, spring<Float>(dampingRatio = 0.78f, stiffness = 380f))
    }
    // 红底淡入行程：位移 0→18dp 之间线性渐显，避免滑动瞬间硬切色块
    val redFadePx = with(density) { 18.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(item.id) {
                val minRevealPx = with(density) { 12.dp.toPx() }
                // 回弹曲线：距离越远刚度略高、总时长接近一致；0.88 阻尼几乎无过冲，
                // visibilityThreshold 让尾段干脆收敛，避免长距离回弹拖尾
                fun reboundSpec(distancePx: Float) = spring<Float>(
                    dampingRatio = 0.88f,
                    stiffness = (255f + kotlin.math.abs(distancePx) / rowWidthPx * 130f)
                        .coerceIn(255f, 385f),
                    visibilityThreshold = 0.5f
                )
                var totalDrag = 0f
                var startOffset = 0f
                var lastRaw = 0f
                var cancelSent = false
                var wasPreDeleting = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        startOffset = offset.value
                        lastRaw = startOffset
                        totalDrag = 0f
                        cancelSent = false
                        wasPreDeleting = preDeletingNow
                    },
                    onDragEnd = {
                        // 判定取松手瞬间的「手指位移」，不受橡皮筋阻尼影响
                        val finalLeftPx = (-lastRaw).coerceAtLeast(0f)
                        val leftRatio = finalLeftPx / rowWidthPx
                        val didSwipeRight = totalDrag > minRevealPx
                        val spec = reboundSpec(offset.value)
                        scope.launch {
                            when {
                                // ① 预删除中：右滑已在 drag 阶段立刻取消；其它手势不打断倒计时
                                wasPreDeleting || cancelSent -> offset.animateTo(0f, spec)
                                // ② 左滑 >= 1/3
                                leftRatio >= 1f / 3f && !didSwipeRight -> {
                                    if (instantDeleteNow) {
                                        // 已完成列表：直接删除，不进预删除、没有倒计时
                                        onDelete()
                                        offset.snapTo(0f)
                                    } else {
                                        // 待取 / 待办：进入 5 秒预删除，卡片柔和归位
                                        onEnterPreDelete()
                                        offset.animateTo(0f, spec)
                                    }
                                }
                                // ③ 左滑 < 1/3：自动柔和弹回原位
                                else -> offset.animateTo(0f, spec)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f, reboundSpec(offset.value)) }
                    }
                ) { change: PointerInputChange, dragAmount: Float ->
                    totalDrag += dragAmount
                    val raw = (startOffset + totalDrag).coerceIn(-rowWidthPx, rowWidthPx)
                    lastRaw = raw
                    // 全程跟手曲线：过 1/3 触发线后加橡皮筋阻尼，右滑轻度阻尼，
                    // 松手回弹交给自适应 spring，贴近 HyperOS 柔顺手感
                    val triggerWidth = rowWidthPx / 3f
                    val target = when {
                        raw < -triggerWidth -> (-triggerWidth + (raw + triggerWidth) * 0.30f)
                            .coerceAtLeast(-rowWidthPx * 0.65f)
                        raw > 0f -> raw * 0.45f
                        else -> raw
                    }
                    // 预删除计时期间只要开始右滑就立刻取消，不等松手
                    if (preDeletingNow && !cancelSent && totalDrag > minRevealPx) {
                        cancelSent = true
                        onCancelPreDelete()
                    }
                    scope.launch { offset.snapTo(target) }
                    change.consume()
                }
            }
    ) {
        // 左滑露出的红色删除底：保留原有红色垃圾桶图标与红底风格，随位移柔和淡入
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = ((-offset.value) / redFadePx).coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(20.dp))
                .background(swipeBackground)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "左滑删除",
                tint = swipeIconTint
            )
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .clickable { onToggle() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = cardSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 圆形勾选框，替代系统方形 Checkbox
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (item.isPicked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    if (item.isPicked) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已取件",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        // 待办用黑字直接显示事项内容，不再重复显示「待办」两字；有快递公司时显示「码·公司」
                        text = if (item.isTodo) item.note.ifBlank { "待办" } else item.displayCode,
                        fontSize = if (item.isTodo) 17.sp else 20.sp,
                        fontFamily = if (item.isTodo) null else FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (item.isPicked) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (item.isPicked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (!item.isTodo) {
                        // 附属信息统一按「驿站名称·物流单号·用户备注」拼接；
                        // 三段全空时才显示「点击添加备注」。历史数据里的自动模板
                        // 由 PickupItemFormatter 在展示层还原，不改动库中原文。
                        val detail = PickupItemFormatter.displayDetail(item)
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (detail.isBlank()) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    }
                                )
                                .clickable { onEditNote() }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (detail.isBlank()) "点击添加备注" else detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (detail.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                if (item.isTodo) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onEditNote() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑待办",
                            tint = hyperAdaptiveContent(HyperColors.TextPrimary, HyperColors.DarkTextPrimary),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除记录",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 预删除倒计时提示：放到卡片**右上角**（需求：避免与备注文字重叠）。
        // 文案「右滑恢复」+ 圆形数字徽标，方向箭头保留；淡入并轻微下沉、随卡片平移；
        // 不改动卡片内部布局，淡红半透明底色保持原样。
        AnimatedVisibility(
            visible = isPreDeleting,
            enter = fadeIn(animationSpec = tween(140)) + slideInVertically { -it / 3 },
            exit = fadeOut(animationSpec = tween(150)) + slideOutVertically { -it / 3 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 50.dp, top = 10.dp)
        ) {
            Row(
                modifier = Modifier.graphicsLayer { translationX = offset.value },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = preDeleteHintText,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "右滑恢复",
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 0.3.sp,
                    color = preDeleteHintText,
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(preDeleteHintBg.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$preDeleteSeconds",
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = preDeleteHintText,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            scaleX = numberScale.value
                            scaleY = numberScale.value
                        }
                    )
                }
            }
        }
    }
}

/**
 * 全屏二维码展示弹窗（自动把当前窗口亮度拉满至 1.0f，关闭后还原）。
 * imageFile 由调用方指定，因此静态兜底码与各快捷按钮自己的二维码都能复用。
 */
@Composable
private fun FullBrightnessStaticCodeDialog(
    imageFile: File,
    imageVersion: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f
        if (window != null) {
            val params = window.attributes
            params.screenBrightness = 1.0f
            window.attributes = params
        }
        onDispose {
            if (window != null) {
                val params = window.attributes
                params.screenBrightness = originalBrightness
                window.attributes = params
            }
        }
    }

    val bitmap = remember(imageFile.absolutePath, imageVersion) {
        // 高清出库码图按采样解码，避免全尺寸 Bitmap 直接进内存引发 OOM
        if (imageFile.exists()) BitmapDecodeHelper.decodeSampledFile(imageFile.absolutePath) else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .clickable { onDismiss() },
            color = HyperColors.Background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (bitmap != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shape = HyperRadius.Card
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "本地二维码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        )
                    }
                } else {
                    Text(
                        text = "未找到本地二维码图片",
                        color = HyperColors.TextSecondary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = HyperColors.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = "屏幕已临时调至最高亮度 · 点击任意处关闭",
                    color = HyperColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}


/**
 * 提示卡片：HyperOS 轻量化悬浮风格。
 * 不再使用 Material3 Surface —— 它的 shadowElevation / tonalElevation 在部分 ROM 上
 * 会画出隐约的直角轮廓；改为「圆角裁剪 + 半透明底 + 同弧度极细描边」，
 * 四角统一圆润、文字清爽、没有生硬实色块。
 *
 * @author 焱川 (by 焱川)
 */
@Composable
private fun LiftedToastHost(message: LiftedToast.Message?, modifier: Modifier = Modifier) {
    // 保留上一条文案，退出淡出动画期间卡片不会变成空白
    var displayText by remember { mutableStateOf("") }
    if (message != null) displayText = message.text
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(animationSpec = tween(160)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        if (displayText.isNotEmpty()) {
            val shape = RoundedCornerShape(24.dp)
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    // 圆角由背景与描边直接按 shape 绘制，不依赖 clip/graphicsLayer 轮廓，
                    // 动画层在任何 ROM 上都不会退化成直角边框
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        shape = shape
                    )
                    .border(
                        width = 0.6.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        shape = shape
                    )
                    .padding(horizontal = 16.dp, vertical = 11.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = displayText,
                        modifier = Modifier.weight(1f, fill = false),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}
