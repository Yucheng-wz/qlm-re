package com.hnu.pickupassistant.ocr

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hnu.pickupassistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 【截图对照】原图预览页 —— 全屏 Compose Dialog。
 *
 * 为什么选 Dialog 而不是新 Activity / Fragment：
 *  ① 不需要新增 Activity 与清单注册，也不必跨组件传 Bitmap；
 *  ② 预览与识别弹窗在同一棵组合树，直接共享 [ScreenshotMemory] 缓存与浏览状态；
 *  ③ 生命周期跟随组合，宿主 DisposableEffect.onDispose 天然兜底释放。
 *
 * 手势：一个 awaitPointerEventScope 手势链同时处理双指缩放(1~5f)、拖动平移 /
 * 上下左右滑动看全图、双击 1f ↔ 2.5f（300ms 内两次轻点）、spring 边界回弹；
 * ContentScale.Fit 按视口等比显示，不拉伸变形。
 * 受限协程作用域内只能调用非 suspend 函数，所以跟手用同步 state 赋值，
 * 动画用 Animatable.animateTo 的逐帧回调写回 state，同样平滑不跳变。
 *
 * 状态记忆：打开时从 [ScreenshotMemory.currentState] 恢复；任何关闭路径
 * （顶部关闭 / 返回键 / 右下角完成）先写回状态再播退场动画，不释放 Bitmap。
 * 只有保存成功 / 取消整个识别弹窗 / Activity 销毁才由宿主 release。
 *
 * 主题与自适应：色值全部 colorResource（values / values-night 自动切换），
 * 圆角 / 留白取 dimens 三档；BoxWithConstraints 取真实视口；
 * WindowInsets.systemBars 避让状态栏 / 导航栏 / 手势条。
 *
 * @author 焱川 (by 焱川)
 */
@Composable
fun ScreenshotPreviewDialog(sourceUri: Uri?, onClose: () -> Unit) {
    // 实时浏览状态：任何关闭路径都用它写回缓存，保证「再打开恢复上次位置与缩放」
    var liveState by remember { mutableStateOf(ScreenshotMemory.currentState()) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(visible) {
        if (!visible) {
            delay(PREVIEW_EXIT_MS.toLong())
            onClose()
        }
    }

    /** 统一关闭路径：先记忆状态，再播退场动画，最后回调宿主收起 Dialog */
    fun requestClose() {
        ScreenshotMemory.remember(liveState)
        visible = false
    }

    // 载入在 IO 线程；同一 URI 第二次打开直接命中缓存，不重新解码
    val loadResult = rememberScreenshotLoad(sourceUri)
    val cacheLabel = when (loadResult) {
        is ScreenshotMemory.LoadResult.Ready ->
            if (loadResult.fromCache) {
                "已命中图片缓存 · 第 ${loadResult.cacheHits} 次打开（未重新加载）"
            } else "原图已载入，可双指缩放 / 拖动查看"
        is ScreenshotMemory.LoadResult.Failure -> loadResult.reason
        null -> "正在载入原图…"
    }

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        PreviewDialogWindowEffect()
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(PREVIEW_ENTER_MS, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.96f,
                    animationSpec = tween(PREVIEW_ENTER_MS, easing = FastOutSlowInEasing)
                ),
            exit = fadeOut(tween(PREVIEW_EXIT_MS, easing = FastOutLinearInEasing)) +
                scaleOut(
                    targetScale = 0.97f,
                    animationSpec = tween(PREVIEW_EXIT_MS, easing = FastOutLinearInEasing)
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorResource(R.color.qlm_preview_background),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PreviewTopBar(cacheLabel = cacheLabel, onClose = { requestClose() })
                        // weight 让图片区吃掉工具条之外的全部剩余空间
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            PreviewContent(
                                loadResult = loadResult,
                                onRequestClose = { requestClose() },
                                onStateChange = { state -> liveState = state }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 组合内载入截图（IO 线程），同一 URI 命中缓存时不重复解码 */
@Composable
private fun rememberScreenshotLoad(sourceUri: Uri?): ScreenshotMemory.LoadResult? {
    val context = LocalContext.current
    var result by remember(sourceUri) {
        mutableStateOf<ScreenshotMemory.LoadResult?>(null)
    }
    LaunchedEffect(sourceUri) {
        result = if (sourceUri == null) {
            ScreenshotMemory.LoadResult.Failure("没有可用的截图")
        } else {
            withContext(Dispatchers.IO) { ScreenshotMemory.attach(context, sourceUri) }
        }
    }
    return result
}

/** 顶部工具条：标题 + 缓存/载入提示 + 关闭（主题色 + dimens） */
@Composable
private fun PreviewTopBar(cacheLabel: String, onClose: () -> Unit) {
    val corner = dimensionResource(R.dimen.qlm_control_corner)
    val pad = dimensionResource(R.dimen.qlm_dialog_padding)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pad, vertical = dimensionResource(R.dimen.qlm_item_gap)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "截图对照",
                fontSize = textSpResource(R.dimen.qlm_title_size),
                fontWeight = FontWeight.Bold,
                color = colorResource(R.color.qlm_on_surface)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = cacheLabel,
                fontSize = textSpResource(R.dimen.qlm_label_size),
                color = colorResource(R.color.qlm_preview_hint)
            )
        }
        Surface(
            shape = RoundedCornerShape(corner),
            color = colorResource(R.color.qlm_surface_variant),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            TextButton(
                onClick = onClose,
                shape = RoundedCornerShape(corner),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colorResource(R.color.qlm_on_surface)
                )
            ) { Text("关闭", fontSize = textSpResource(R.dimen.qlm_body_size)) }
        }
    }
}

/** 图片主体：Ready → 手势区；Failure / 载入中 → 降级占位 */
@Composable
private fun PreviewContent(
    loadResult: ScreenshotMemory.LoadResult?,
    onRequestClose: () -> Unit,
    onStateChange: (ScreenshotMemory.ViewState) -> Unit
) {
    when (loadResult) {
        is ScreenshotMemory.LoadResult.Ready -> {
            val bitmap = loadResult.bitmap
            if (bitmap.isRecycled) {
                PreviewPlaceholder("图片缓存已释放，请重新选择截图", onRequestClose)
            } else {
                ZoomableScreenshot(
                    bitmap = bitmap,
                    onRequestClose = onRequestClose,
                    onStateChange = onStateChange
                )
            }
        }
        is ScreenshotMemory.LoadResult.Failure ->
            PreviewPlaceholder(loadResult.reason, onRequestClose)
        null -> PreviewPlaceholder("正在载入原图…", onRequestClose)
    }
}

/** 加载失败 / 等待时的降级占位：只显示提示与返回，不崩溃 */
@Composable
private fun PreviewPlaceholder(message: String, onRequestClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.qlm_preview_placeholder))
            .padding(dimensionResource(R.dimen.qlm_dialog_padding)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                fontSize = textSpResource(R.dimen.qlm_body_size),
                color = colorResource(R.color.qlm_preview_hint)
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.qlm_section_gap)))
            TextButton(onClick = onRequestClose) {
                Text("返回", color = colorResource(R.color.qlm_primary))
            }
        }
    }
}

/** 可缩放平移的原图区：graphicsLayer 等比 scale + translation，ContentScale.Fit 不拉伸 */
@Suppress("UNUSED_ASSIGNMENT") // 手势循环里 lastCentroid / lastSpread 在下一帧读取，编译器误报
@Composable
private fun ZoomableScreenshot(
    bitmap: Bitmap,
    onRequestClose: () -> Unit,
    onStateChange: (ScreenshotMemory.ViewState) -> Unit
) {
    val density = LocalDensity.current
    val scope: CoroutineScope = rememberCoroutineScope()
    val overscrollPx = with(density) {
        dimensionResource(R.dimen.qlm_overscroll).toPx()
    }
    val saved = remember { ScreenshotMemory.currentState() }

    var scale by remember { mutableStateOf(saved.scale.coerceIn(MIN_SCALE, MAX_SCALE)) }
    var offsetX by remember { mutableStateOf(saved.offsetX) }
    var offsetY by remember { mutableStateOf(saved.offsetY) }

    /** 把当前缩放 / 查看区域同步给宿主，供任何关闭路径写回缓存 */
    fun publishState() {
        onStateChange(ScreenshotMemory.ViewState(scale, offsetX, offsetY))
    }

    LaunchedEffect(Unit) { publishState() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }
        val imgW = bitmap.width.toFloat().coerceAtLeast(1f)
        val imgH = bitmap.height.toFloat().coerceAtLeast(1f)
        val fit = min(viewportW / imgW, viewportH / imgH)

        /** 当前缩放下允许的最大平移量 = 图片超出视口的一半 */
        fun maxOffset(currentScale: Float): Pair<Float, Float> {
            val scaledW = imgW * fit * currentScale
            val scaledH = imgH * fit * currentScale
            return max(0f, (scaledW - viewportW) / 2f) to
                max(0f, (scaledH - viewportH) / 2f)
        }

        /** 边界回弹：spring 逐帧写回 state，两方向并行，平滑不跳变 */
        suspend fun bounceBack() {
            val (mx, my) = maxOffset(scale)
            val targetX = offsetX.coerceIn(-mx, mx)
            val targetY = offsetY.coerceIn(-my, my)
            val spec = spring<Float>(dampingRatio = 0.75f, stiffness = 350f)
            coroutineScope {
                launch {
                    Animatable(offsetX).animateTo(targetX, spec) { offsetX = value }
                }
                launch {
                    Animatable(offsetY).animateTo(targetY, spec) { offsetY = value }
                }
            }
            publishState()
        }

        /** 双击：1f ↔ 2.5f，spring 平滑过渡 */
        fun toggleZoom() {
            scope.launch {
                val target = if (scale > 1.6f) MIN_SCALE else DOUBLE_TAP_SCALE
                Animatable(scale).animateTo(
                    target,
                    spring(dampingRatio = 0.82f, stiffness = 380f)
                ) { scale = value }
                bounceBack()
            }
        }

        /** 跟手变换：围绕双指 centroid 缩放 + 平移，橡皮筋限制越界（非 suspend） */
        fun applyTransform(pan: Offset, zoom: Float, centroid: Offset) {
            val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
            val (mx, my) = maxOffset(newScale)
            val focal = centroid - Offset(viewportW / 2f, viewportH / 2f)
            val nextX = (offsetX - focal.x) * zoom + focal.x + pan.x
            val nextY = (offsetY - focal.y) * zoom + focal.y + pan.y
            scale = newScale
            offsetX = nextX.coerceIn(-mx - overscrollPx, mx + overscrollPx)
            offsetY = nextY.coerceIn(-my - overscrollPx, my + overscrollPx)
            publishState()
        }

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "原图预览，可双指缩放与拖动",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var lastCentroid = Offset.Zero
                        var lastSpread = 0f
                        var gesturing = false
                        var dragged = false
                        var lastTapMs = 0L
                        var lastTapPos = Offset.Zero

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }

                            if (pressed.isEmpty()) {
                                if (gesturing) {
                                    if (!dragged) {
                                        val now = System.currentTimeMillis()
                                        val nearLastTap =
                                            now - lastTapMs < DOUBLE_TAP_TIMEOUT_MS &&
                                                (lastCentroid - lastTapPos).getDistance() < 80f
                                        if (nearLastTap) {
                                            toggleZoom()
                                            lastTapMs = 0L
                                        } else {
                                            lastTapMs = now
                                            lastTapPos = lastCentroid
                                        }
                                    }
                                    scope.launch { bounceBack() }
                                    gesturing = false
                                    dragged = false
                                }
                                continue
                            }

                            val centroid = Offset(
                                pressed.map { it.position.x }.average().toFloat(),
                                pressed.map { it.position.y }.average().toFloat()
                            )
                            val spread = if (pressed.size >= 2) {
                                pressed.map { (it.position - centroid).getDistance() }
                                    .average().toFloat()
                            } else 0f

                            if (!gesturing) {
                                lastCentroid = centroid
                                lastSpread = spread
                                gesturing = true
                                dragged = false
                            } else {
                                val pan = centroid - lastCentroid
                                val zoom = if (pressed.size >= 2 && lastSpread > 0f && spread > 0f) {
                                    (spread / lastSpread).coerceIn(0.8f, 1.25f)
                                } else 1f
                                if (pan.getDistance() > 1f || zoom != 1f) {
                                    dragged = true
                                    applyTransform(pan, zoom, centroid)
                                }
                            }
                            lastCentroid = centroid
                            lastSpread = spread
                            event.changes.forEach { change ->
                                if (change.pressed) change.consume()
                            }
                        }
                    }
                }
        )

        // 右下角「完成」：状态已实时同步给宿主，这里只收起预览
        TextButton(
            onClick = onRequestClose,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(dimensionResource(R.dimen.qlm_card_padding))
        ) {
            Surface(
                shape = RoundedCornerShape(dimensionResource(R.dimen.qlm_control_corner)),
                color = colorResource(R.color.qlm_primary),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    text = "完成",
                    color = colorResource(R.color.qlm_on_primary),
                    fontSize = textSpResource(R.dimen.qlm_body_size),
                    modifier = Modifier.padding(
                        horizontal = dimensionResource(R.dimen.qlm_card_padding),
                        vertical = dimensionResource(R.dimen.qlm_item_gap)
                    )
                )
            }
        }
    }
}

/** dimens 里以 dp 数值定义的字号 → sp，保证 small/normal/large 三档生效 */
@Composable
internal fun textSpResource(id: Int) = dimensionResource(id).value.sp

// ============================================================================
// 动画与手势常量区（时长 / 曲线 / 阈值集中可配）
// ============================================================================
private const val PREVIEW_ENTER_MS = 220
private const val PREVIEW_EXIT_MS = 180
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
