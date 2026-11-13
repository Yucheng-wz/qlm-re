package com.hnu.pickupassistant.ocr

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hnu.pickupassistant.R
import com.hnu.pickupassistant.util.LiftedToast
import kotlinx.coroutines.delay

/**
 * 「取了吗」识图取件码 —— 识别结果预览弹窗 + 统一宿主（重构版）。
 *
 *  - 标题栏右上角新增【截图对照】文字按钮（同行 weight，不遮挡标题）；
 *  - 底部主按钮为「保存」（默认全选，取消勾选即跳过该条），已删除「保存勾选」；
 *  - 入场 / 退场动画：淡入 + 轻微缩放，时长与曲线见文件底部常量区；
 *  - 按压反馈：Material3 水波纹 + [pressScale] 缩放动画；
 *  - 表单编辑状态切换 / 列表项选中：animateColorAsState 平滑过渡，禁止瞬间跳变；
 *  - 澎湃风格：弹窗 28dp（三档 dimen）、卡片 18dp、胶囊按钮、充足留白、无装饰线；
 *  - 全部色值取 colorResource（values / values-night 自动切换），零硬编码；
 *  - BoxWithConstraints + 屏幕宽度百分比 + dimen 最大宽度；WindowInsets 避让系统栏；
 *  - 截图生命周期：保存成功 / 取消 / 组合销毁三级释放（详见 [OcrPreviewHost]）。
 *
 * @author 焱川 (by 焱川)
 */
@Composable
fun OcrPreviewHost() {
    val context = LocalContext.current
    val state by OcrPreviewBus.state.collectAsState()

    // 兜底：宿主离开组合（Activity 销毁 / 首页被移除）时释放截图资源
    DisposableEffect(Unit) {
        onDispose { ScreenshotMemory.release(context, "host-dispose") }
    }

    when (val current = state) {
        is OcrPreviewBus.State.Empty -> NoCodeFoundDialog(
            onDismiss = { OcrPreviewBus.dismiss() }
        )

        is OcrPreviewBus.State.Show -> PickupPreviewDialog(
            initial = current.items,
            sourceUri = current.imageUri,
            onDismiss = {
                ScreenshotMemory.release(context, "dialog-closed")
                OcrPreviewBus.dismiss()
            },
            onSave = { list ->
                // 复用原有 LocalStorageManager 的去重能力，业务代码零改动
                val summary = ParcelTextParser.saveCandidates(context, list)
                val message = buildString {
                    if (summary.added > 0) append("已保存 ${summary.added} 条")
                    if (summary.duplicates > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("跳过重复 ${summary.duplicates} 条")
                    }
                    if (summary.invalid > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${summary.invalid} 条格式不支持")
                    }
                    if (isEmpty()) append("没有可保存的记录")
                }
                LiftedToast.show(message)
                // 写入成功后立刻释放 Bitmap、删除临时截图、清空缓存引用
                ScreenshotMemory.release(context, "save-success")
                // 不在这里 dismiss：交给弹窗播完退场动画后回调 onDismiss
            }
        )

        else -> Unit // Idle：不渲染任何弹窗
    }
}

/** 整张图片找不到取件码时的提示弹窗（主题色 + 入场动画 + dimen 自适应） */
@Composable
private fun NoCodeFoundDialog(onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(visible) {
        if (!visible) {
            delay(DIALOG_EXIT_MS.toLong())
            onDismiss()
        }
    }
    Dialog(
        onDismissRequest = { visible = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        PreviewDialogWindowEffect()
        AnimatedVisibility(visible = visible, enter = dialogEnter(), exit = dialogExit()) {
            Surface(
                modifier = Modifier.widthIn(max = dimensionResource(R.dimen.qlm_dialog_max_width)),
                shape = RoundedCornerShape(dimensionResource(R.dimen.qlm_dialog_corner)),
                color = colorResource(R.color.qlm_surface),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(dimensionResource(R.dimen.qlm_dialog_padding))) {
                    Text(
                        text = "未识别到取件码",
                        fontSize = textSpResource(R.dimen.qlm_title_size),
                        fontWeight = FontWeight.Bold,
                        color = colorResource(R.color.qlm_on_surface)
                    )
                    Spacer(Modifier.height(dimensionResource(R.dimen.qlm_item_gap)))
                    Text(
                        text = "图片未识别到取件码，请重新截图",
                        fontSize = textSpResource(R.dimen.qlm_body_size),
                        color = colorResource(R.color.qlm_on_surface_variant)
                    )
                    Spacer(Modifier.height(dimensionResource(R.dimen.qlm_section_gap)))
                    Button(
                        onClick = { visible = false },
                        modifier = Modifier.align(Alignment.End).pressScale(),
                        shape = RoundedCornerShape(50),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorResource(R.color.qlm_primary),
                            contentColor = colorResource(R.color.qlm_on_primary)
                        )
                    ) { Text("知道了") }
                }
            }
        }
    }
}

/**
 * 识别结果预览弹窗。
 *
 * @param initial 解析器给出的候选列表（字段可能为空，允许手动补全）
 * @param sourceUri 原图 URI，供【截图对照】预览使用
 * @param onDismiss 关闭整个弹窗（取消 / 保存完成 / 退场动画结束后）
 * @param onSave 把当前列表交给宿主保存（内部完成去重、提示与资源释放）
 */
@Composable
fun PickupPreviewDialog(
    initial: List<ParcelTextParser.ParcelCandidate>,
    sourceUri: Uri? = null,
    onDismiss: () -> Unit,
    onSave: (List<ParcelTextParser.ParcelCandidate>) -> Unit
) {
    val context = LocalContext.current
    var items by remember(initial) { mutableStateOf(initial) }
    var showPreview by remember { mutableStateOf(false) }

    // 入场 / 退场：淡入 + 轻微缩放入场，淡出 + 轻微缩小退场
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(visible) {
        if (!visible) {
            delay(DIALOG_EXIT_MS.toLong())
            onDismiss()
        }
    }

    /** 兜底：Dialog 内容离开组合时释放截图，防止泄漏（幂等） */
    DisposableEffect(Unit) {
        onDispose { ScreenshotMemory.release(context, "dialog-dispose") }
    }

    /** 按 id 更新某一条的任意字段，避免 LazyColumn 重组时错位 */
    fun update(
        id: String,
        transform: (ParcelTextParser.ParcelCandidate) -> ParcelTextParser.ParcelCandidate
    ) {
        items = items.map { if (it.id == id) transform(it) else it }
    }

    Dialog(
        onDismissRequest = {
            // 点外部 / 返回键等价于取消：立即释放截图再播退场动画
            ScreenshotMemory.release(context, "cancel-outside")
            visible = false
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        PreviewDialogWindowEffect()
        // 屏幕自适应：按可用宽度百分比 + dimen 最大宽度，小屏不挤压、大屏不空旷
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            contentAlignment = Alignment.Center
        ) {
            val maxDialogWidth = dimensionResource(R.dimen.qlm_dialog_max_width)
            val dialogWidth = minOf(maxWidth * DIALOG_WIDTH_RATIO, maxDialogWidth)
            AnimatedVisibility(
                visible = visible,
                enter = dialogEnter(),
                exit = dialogExit()
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = dialogWidth)
                        .heightIn(max = dimensionResource(R.dimen.qlm_dialog_max_height)),
                    shape = RoundedCornerShape(dimensionResource(R.dimen.qlm_dialog_corner)),
                    color = colorResource(R.color.qlm_surface),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(dimensionResource(R.dimen.qlm_dialog_padding))
                    ) {
                        // ---------- 标题栏：标题 + 右上角【截图对照】同一行 ----------
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "识别结果（${items.size} 条）",
                                    fontSize = textSpResource(R.dimen.qlm_title_size),
                                    fontWeight = FontWeight.Bold,
                                    color = colorResource(R.color.qlm_on_surface)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "默认全选，可修正后保存",
                                    fontSize = textSpResource(R.dimen.qlm_label_size),
                                    color = colorResource(R.color.qlm_on_surface_variant)
                                )
                            }
                            TextButton(
                                onClick = {
                                    // 只打开预览，不释放缓存；重复开关会命中 Bitmap 缓存
                                    showPreview = true
                                },
                                modifier = Modifier.pressScale(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = colorResource(R.color.qlm_primary)
                                )
                            ) {
                                Text(
                                    "截图对照",
                                    fontSize = textSpResource(R.dimen.qlm_body_size),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(Modifier.height(dimensionResource(R.dimen.qlm_section_gap)))

                        if (items.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp)
                                    .clip(RoundedCornerShape(dimensionResource(R.dimen.qlm_card_corner)))
                                    .background(colorResource(R.color.qlm_surface_variant))
                                    .padding(dimensionResource(R.dimen.qlm_card_padding)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "已忽略全部条目",
                                    fontSize = textSpResource(R.dimen.qlm_body_size),
                                    color = colorResource(R.color.qlm_on_surface_variant)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = dimensionResource(R.dimen.qlm_list_max_height)),
                                verticalArrangement = Arrangement.spacedBy(
                                    dimensionResource(R.dimen.qlm_item_gap)
                                )
                            ) {
                                items(items, key = { it.id }) { candidate ->
                                    CandidateCard(
                                        candidate = candidate,
                                        onUpdate = { transform -> update(candidate.id, transform) },
                                        onIgnore = { items = items.filter { it.id != candidate.id } }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(dimensionResource(R.dimen.qlm_section_gap)))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                dimensionResource(R.dimen.qlm_item_gap)
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    // 取消：立即释放并删除临时截图，再播退场动画
                                    ScreenshotMemory.release(context, "cancel")
                                    visible = false
                                },
                                modifier = Modifier.weight(0.8f).pressScale()
                            ) {
                                Text(
                                    "取消",
                                    fontSize = textSpResource(R.dimen.qlm_label_size),
                                    color = colorResource(R.color.qlm_primary)
                                )
                            }

                            Button(
                                onClick = {
                                    // 只保存勾选中的条目（弹窗打开时默认全选）
                                    onSave(items.filter { it.selected })
                                    visible = false
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(dimensionResource(R.dimen.qlm_button_height))
                                    .pressScale(),
                                shape = RoundedCornerShape(50),
                                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colorResource(R.color.qlm_primary),
                                    contentColor = colorResource(R.color.qlm_on_primary)
                                )
                            ) {
                                Text("保存", fontSize = textSpResource(R.dimen.qlm_label_size))
                            }
                        }
                    }
                }
            }
        }
    }

    // 截图对照预览：独立全屏 Dialog，关闭只收起预览、不释放 Bitmap
    if (showPreview) {
        ScreenshotPreviewDialog(
            sourceUri = sourceUri,
            onClose = { showPreview = false }
        )
    }
}

/** 单条快递卡片：勾选 / 忽略 / 取件码·驿站·快递公司可编辑 + 物流单号 + 独立备注输入框 */
@Composable
private fun CandidateCard(
    candidate: ParcelTextParser.ParcelCandidate,
    onUpdate: ((ParcelTextParser.ParcelCandidate) -> ParcelTextParser.ParcelCandidate) -> Unit,
    onIgnore: () -> Unit
) {
    // 列表项选中状态：背景色 180ms 平滑过渡，禁止瞬间跳变
    val cardBg by animateColorAsState(
        targetValue = if (candidate.selected) {
            colorResource(R.color.qlm_surface_variant)
        } else {
            colorResource(R.color.qlm_preview_placeholder)
        },
        animationSpec = tween(STATE_TRANSITION_MS),
        label = "candidateSelectedBg"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimensionResource(R.dimen.qlm_card_corner)))
            .background(cardBg)
            .padding(dimensionResource(R.dimen.qlm_card_padding))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = candidate.selected,
                onCheckedChange = { checked -> onUpdate { it.copy(selected = checked) } },
                colors = CheckboxDefaults.colors(
                    checkedColor = colorResource(R.color.qlm_primary),
                    uncheckedColor = colorResource(R.color.qlm_outline),
                    checkmarkColor = colorResource(R.color.qlm_on_primary)
                )
            )
            Text(
                text = "取件码 ${candidate.code.ifBlank { "（空）" }}",
                fontSize = textSpResource(R.dimen.qlm_body_size),
                fontWeight = FontWeight.Bold,
                color = colorResource(R.color.qlm_primary),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onIgnore, modifier = Modifier.pressScale()) {
                Text(
                    "忽略",
                    fontSize = textSpResource(R.dimen.qlm_label_size),
                    color = colorResource(R.color.qlm_error)
                )
            }
        }

        // 字段全部可直接编辑，修正 OCR 错字后再保存（地址字段已取消，只保留驿站名称）
        EditableField(
            value = candidate.code,
            label = "取件码",
            onValueChange = { value -> onUpdate { it.copy(code = value.take(16)) } }
        )
        EditableField(
            value = candidate.station,
            label = "驿站名称",
            onValueChange = { value -> onUpdate { it.copy(station = value) } }
        )
        EditableField(
            value = candidate.courier,
            label = "快递公司",
            onValueChange = { value -> onUpdate { it.copy(courier = value) } }
        )

        if (candidate.trackingNo.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "物流单号 ${candidate.trackingNo}",
                fontSize = textSpResource(R.dimen.qlm_label_size),
                color = colorResource(R.color.qlm_on_surface_variant)
            )
        }

        // 备注输入框：紧跟物流单号下方，**默认留空**，只给用户手写补充说明。
        // 上方「物流单号 xxx」是只读核对行，绝不复制进这个输入框。
        EditableField(
            value = candidate.note,
            label = "备注",
            onValueChange = { value -> onUpdate { it.copy(note = value) } }
        )
    }
}

/** 轻量单行输入框：不自动聚焦；聚焦 / 失焦边框与底色 180ms 平滑过渡 */
@Composable
private fun EditableField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) {
            colorResource(R.color.qlm_primary)
        } else {
            colorResource(R.color.qlm_outline)
        },
        animationSpec = tween(STATE_TRANSITION_MS),
        label = "fieldBorder"
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = textSpResource(R.dimen.qlm_label_size)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = textSpResource(R.dimen.qlm_body_size)
        ),
        shape = RoundedCornerShape(dimensionResource(R.dimen.qlm_control_corner)),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colorResource(R.color.qlm_on_surface),
            unfocusedTextColor = colorResource(R.color.qlm_on_surface),
            focusedContainerColor = colorResource(R.color.qlm_surface),
            unfocusedContainerColor = colorResource(R.color.qlm_surface),
            focusedBorderColor = borderColor,
            unfocusedBorderColor = borderColor,
            cursorColor = colorResource(R.color.qlm_primary),
            focusedLabelColor = colorResource(R.color.qlm_primary),
            unfocusedLabelColor = colorResource(R.color.qlm_text_tertiary)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
    )
}

/**
 * 按钮按压反馈：Material3 Button 自带水波纹（ripple）之外，
 * 再叠加 120ms 的 0.97f 缩放动画，轻量、跟手、无瞬间跳变。
 * 不消费指针事件，clickable 仍能正常收到点击。
 */
@Composable
internal fun Modifier.pressScale(): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(PRESS_MS, easing = FastOutSlowInEasing),
        label = "pressScale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    pressed = event.changes.any { it.pressed }
                }
            }
        }
}

// ============================================================================
// 动画 / 自适应常量区（时长、曲线、比例全部集中可配）
// ============================================================================

/** 弹窗入场：240ms 淡入 + 0.94→1 缩放，FastOutSlowInEasing（减速入场） */
private const val DIALOG_ENTER_MS = 240

/** 弹窗退场：180ms 淡出 + 1→0.96 缩放，FastOutLinearInEasing（加速离场） */
private const val DIALOG_EXIT_MS = 180

/** 按钮按压 / 表单编辑 / 列表选中状态过渡时长 */
private const val PRESS_MS = 120
private const val STATE_TRANSITION_MS = 180

/** 弹窗宽度 = 可用宽度的 92%，再由 dimens 的 qlm_dialog_max_width 封顶 */
private const val DIALOG_WIDTH_RATIO = 0.92f

private fun dialogEnter() =
    fadeIn(tween(DIALOG_ENTER_MS, easing = FastOutSlowInEasing)) +
        scaleIn(
            initialScale = 0.94f,
            animationSpec = tween(DIALOG_ENTER_MS, easing = FastOutSlowInEasing)
        )

private fun dialogExit() =
    fadeOut(tween(DIALOG_EXIT_MS, easing = FastOutLinearInEasing)) +
        scaleOut(
            targetScale = 0.96f,
            animationSpec = tween(DIALOG_EXIT_MS, easing = FastOutLinearInEasing)
        )
