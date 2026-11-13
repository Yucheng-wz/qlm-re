package com.hnu.pickupassistant.service

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.hnu.pickupassistant.ClipboardReadActivity
import com.hnu.pickupassistant.R
import com.hnu.pickupassistant.data.LocalStorageManager
import com.hnu.pickupassistant.model.PickupItem
import com.hnu.pickupassistant.model.QuickButtonKind
import com.hnu.pickupassistant.util.BitmapDecodeHelper
import com.hnu.pickupassistant.util.RouteLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮小窗：白色磨砂质感（深浅色自适应），不使用系统级背景模糊。
 *
 * 【磨砂底板】三段纵向渐变（近白、高不透明度、上亮下略暗）+ 左上角径向柔光晕 + 1dp 发丝描边，
 * 颜色全部由 qlm_* 主题令牌派生：浅色为奶油白磨砂板，深色为深灰磨砂 + 极低透明亮边。
 * 刻意【不用】FLAG_BLUR_BEHIND：MIUI / HyperOS 会把它实现成整屏模糊层，桌面全糊且吞触摸。
 *
 * 【动效】入场淡入 + 0.94→1.0 回弹 + 10dp 落位；条目内容变化时逐条错峰淡入上移（封顶 200ms）；
 * 拖动时整体降到 94% 透明度、松手回弹；按钮按压 0.955 收缩回弹；出库码与列表淡入淡出切换；
 * 点击核销有轻震动反馈；退场淡出 + 缩小。
 *
 * 【单窗口架构 —— 关键】
 * 只有「卡片本身」这一个 TYPE_APPLICATION_OVERLAY 窗口，宽高在创建时锁定为卡片大小，
 * 卡片以外的屏幕区域不存在本应用的任何窗口，且窗口带 FLAG_NOT_TOUCH_MODAL，
 * 触摸/滑动事件由系统直接交给桌面或 App 主页：
 *  - 桌面：可以正常点图标、左右滑动、下拉状态栏；
 *  - App 主页：可以正常点按钮、滑列表。
 * 卡片区域内部：只有顶栏与提示行作为拖动把手，按钮/列表各自正常点击与滚动。
 *
 * 【尺寸】卡片窗口宽高在创建时算好并锁定（默认 210×320dp，屏幕更小时按可用空间收缩），
 * 拖动过程只改 LayoutParams.x / y，从不修改 width / height，因此不会出现自适应挤压形变。
 * 出库码预览在固定的内容区内切换可见性，也不会改变窗口尺寸。
 *
 * 【贴边气泡（仿小米悬浮球）】同一个窗口承载两种形态：
 *  - 展开卡片：原样保留全部内容，拖到距屏幕左/右边缘 ≤80dp 处松手 → 吸附该侧并收缩为气泡；
 *  - 贴边气泡：与 APP 图标同形状的圆角方形磨砂气泡（白色半透明宽边描边），
 *    大部分移出屏外只露 32dp，纵向沿用松手位置，不自动上下移动；
 *  - 单击气泡 → 展开回收缩前的横向位置；拖动气泡离开边缘 >48dp 松手 → 自动展开成卡片；
 *  - 停靠时 0.85 半透明，按住/拖动恢复 1.0；形态与坐标写盘，关闭重开、横竖屏重建都能还原。
 * 气泡与卡片都不带系统模糊，窗口外区域零 View，桌面 / App 主页始终可正常操作。
 *
 * 【边界与记忆】拖动时按屏幕可用区域夹取（左/右/上/下都不会被截断）；
 * 位置写入 SharedPreferences，关闭再开恢复上次停靠点；横竖屏切换按新尺寸重算并重夹。
 */
class FloatingWindowService : Service() {

    companion object {
        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private const val PREFS = "floating_window_prefs"
        private const val KEY_X = "float_x"
        private const val KEY_Y = "float_y"
        private const val KEY_MODE = "float_mode"       // expanded / bubble
        private const val KEY_SIDE = "bubble_side"      // -1 左侧 / 1 右侧
        private const val KEY_BUBBLE_Y = "bubble_y"

        // 卡片基准尺寸（dp）：拖动 / 展开出库码都不会改变它
        private const val CARD_W_DP = 210
        private const val CARD_H_DP = 320
        // 卡片高度下限（dp），保证按钮区与至少两条列表可见
        private const val CARD_H_MIN_DP = 200

        // ===== 贴边气泡（仿小米悬浮球）=====
        private const val BUBBLE_DP = 54          // 气泡边长
        private const val BUBBLE_VISIBLE_DP = 32  // 贴边停靠时露在屏幕内侧的宽度
        private const val BUBBLE_CORNER_RATIO = 0.28f // 圆角占边长比例：跟随 App 图标的圆角方形
        private const val BUBBLE_BORDER_DP = 3    // 白色半透明宽边描边宽度
        private const val EDGE_SNAP_DP = 80       // 拖动松手时距边缘 ≤ 该值 → 吸附成气泡
        private const val BUBBLE_PULL_DP = 48     // 气泡被拖离边缘 > 该值 → 松开自动展开成卡片
        private const val DOCKED_BUBBLE_ALPHA = 0.85f

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingWindowService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.stopService(intent)
        }
    }

    /** 主题调色板：颜色全部来自 qlm_* 资源，按深浅模式取不同值，不硬编码色值 */
    private data class Palette(
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val primary: Int,
        val onPrimary: Int,
        /** 磨砂玻璃三段渐变（顶部较实 → 底部较透，透出背景模糊） */
        val glassTop: Int,
        val glassMid: Int,
        val glassBottom: Int,
        /** 左上角柔光晕（径向渐变，模拟环境光打在磨砂玻璃上） */
        val glassGlowStart: Int,
        val glassGlowEnd: Int,
        val glassStroke: Int,
        val itemBg: Int,
        val itemBgPressed: Int,
        /** 贴边气泡的宽边描边：白色半透明，让圆角方形气泡从壁纸上「浮」出来 */
        val bubbleBorder: Int
    )

    private fun buildPalette(): Palette {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val surface = ContextCompat.getColor(this, R.color.qlm_surface)
        val background = ContextCompat.getColor(this, R.color.qlm_background)
        val onSurface = ContextCompat.getColor(this, R.color.qlm_on_surface)
        val onSurfaceVariant = ContextCompat.getColor(this, R.color.qlm_on_surface_variant)
        val primary = ContextCompat.getColor(this, R.color.qlm_primary)
        val onPrimary = ContextCompat.getColor(this, R.color.qlm_on_primary)
        val outline = ContextCompat.getColor(this, R.color.qlm_outline)
        val surfaceVariant = ContextCompat.getColor(this, R.color.qlm_surface_variant)

        // ===== 白色磨砂（不使用系统级背景模糊）=====
        // 重要：曾用 FLAG_BLUR_BEHIND 做「真模糊」，但在 MIUI / HyperOS 上系统会把
        // 整屏背景层一起模糊（桌面全花）并且拦住触摸。因此这里彻底不用系统模糊，
        // 改用「高不透明度近白 + 三段渐变 + 柔光晕」自绘磨砂质感：
        // 卡片看起来是实心奶油白磨砂板，桌面与 App 主页保持原样、完全可操作。
        // 浅色：近白上实下略透（0xF7 / 0xEF / 0xE4），仍保留一丝透视但不脏画面；
        // 深色：深灰磨砂（0xEE / 0xE2 / 0xD2），顶边叠亮字色提亮边缘。
        val topSrc = if (night) ColorUtils.blendARGB(surface, onSurface, 0.12f) else surface
        val midSrc = if (night) surface else ColorUtils.blendARGB(surface, onSurface, 0.02f)
        val bottomSrc = if (night) {
            ColorUtils.blendARGB(surface, background, 0.40f)
        } else {
            ColorUtils.blendARGB(surface, onSurface, 0.06f)
        }
        val glassTop = ColorUtils.setAlphaComponent(topSrc, if (night) 0xEE else 0xF7)
        val glassMid = ColorUtils.setAlphaComponent(midSrc, if (night) 0xE2 else 0xEF)
        val glassBottom = ColorUtils.setAlphaComponent(bottomSrc, if (night) 0xD2 else 0xE4)

        // 柔光晕：浅色用近白 surface 从左上角透出（越往右下越透明）；
        // 深色用近白字色低透明，只做一层若有若无的高光，不刺眼。
        val glowSrc = if (night) onSurface else surface
        val glassGlowStart = ColorUtils.setAlphaComponent(glowSrc, if (night) 0x1F else 0xD9)
        val glassGlowEnd = ColorUtils.setAlphaComponent(glowSrc, 0x00)

        // 发丝描边：浅色用 outline 与近白混成柔和灰边，深色用亮字色极低透明勾边
        val glassStroke = if (night) {
            ColorUtils.setAlphaComponent(onSurface, 0x2A)
        } else {
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, outline, 0.7f), 0xE6)
        }
        // 条目卡片：surfaceVariant 半透明，深浅自动适配（底色已不透，条目用实一点更清晰）
        val itemBg = ColorUtils.setAlphaComponent(surfaceVariant, 0x99)
        val itemBgPressed = ColorUtils.setAlphaComponent(surfaceVariant, 0xC0)

        // 气泡宽边描边：白色半透明（浅色更实一点才压得住壁纸，深色降低一点避免刺眼），
        // 取主题里的近白色（浅色 onPrimary #F6FAFF / 深色 onSurface #F7F9FC），不硬编码色值
        val nearWhite = if (night) onSurface else onPrimary
        val bubbleBorder = ColorUtils.setAlphaComponent(nearWhite, if (night) 0x80 else 0xD9)

        return Palette(
            onSurface, onSurfaceVariant, primary, onPrimary,
            glassTop, glassMid, glassBottom, glassGlowStart, glassGlowEnd,
            glassStroke, itemBg, itemBgPressed, bubbleBorder
        )
    }

    /**
     * 白色柔光磨砂底板：三段渐变 + 左上角径向柔光晕 + 1dp 发丝描边。
     * 所有色值来自 Palette（主题资源派生），深浅色自动切换。
     */
    private fun buildGlassDrawable(p: Palette): Drawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(p.glassTop, p.glassMid, p.glassBottom)
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), p.glassStroke)
        }
        // 柔光：不使用纯白色值，用主题近白色从左上角向外淡出，形成「柔光」而不是镜面高光
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(p.glassGlowStart, p.glassGlowEnd)
            gradientRadius = dp(300).toFloat()
            setGradientCenter(0.20f, 0.08f)
            cornerRadius = dp(24).toFloat()
        }
        return LayerDrawable(arrayOf(base, glow))
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var rootContainer: LinearLayout? = null
    private var listContainer: LinearLayout? = null
    private var titleCountView: TextView? = null
    private var codeImageView: ImageView? = null
    private var tipViewRef: TextView? = null
    private var scrollViewRef: ScrollView? = null
    private var contentFrame: FrameLayout? = null
    private var codeToggleBtn: TextView? = null
    private var showingCode = false

    // 一级文件夹折叠状态：只有「待办」「待取」可折叠，货架二级分组固定展开
    private var todoExpanded = true
    private var pickupExpanded = true

    // 缓存最近一次渲染的未取列表，供折叠/展开与主题切换时原地重绘
    private var currentUnpicked: List<PickupItem> = emptyList()
    private var lastVisible: List<PickupItem> = emptyList()
    // 上一次渲染的条目 id 序列：只有内容真的变了才播放逐条入场动效，避免每次数据刷新都重播
    private var lastRenderedIds: List<String> = emptyList()

    private var palette: Palette? = null
    private var cardParams: WindowManager.LayoutParams? = null

    // 锁定的窗口像素尺寸（创建时算好，拖动/展开出库码都不再变化）
    private var cardWidthPx = 0
    private var cardHeightPx = 0

    // ===== 形态（展开卡片 / 贴边气泡）=====
    private enum class FloatMode { EXPANDED, BUBBLE }

    /** 根容器：同时承载「展开卡片」与「贴边气泡」，切换形态时只改窗口尺寸与可见性 */
    private var windowRoot: FrameLayout? = null
    private var bubbleView: View? = null
    private var bubbleSizePx = 0

    private var mode = FloatMode.EXPANDED
    private var bubbleSide = -1        // -1 左 / 1 右
    private var bubbleY = 0            // 气泡贴边时的窗口顶边 Y
    private var lastCardX = 0          // 收缩前的卡片位置（展开时回到这里）
    private var lastCardY = 0
    private var modeAnimating = false  // 形态切换动画进行中，忽略重复触发

    // 气泡拖动手势状态
    private var bubbleDownRawX = 0f
    private var bubbleDownRawY = 0f
    private var bubbleStartX = 0
    private var bubbleStartY = 0
    private var bubbleDragging = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // 横竖屏切换：按新屏幕可用区域重算尺寸并重夹位置（气泡按新边缘重新贴边）
            val p = cardParams ?: return
            val root = windowRoot ?: return
            updateCardMetrics()
            if (mode == FloatMode.BUBBLE) {
                p.width = bubbleSizePx
                p.height = bubbleSizePx
                bubbleY = clampBubbleY(bubbleY)
                p.x = dockedBubbleX(bubbleSide)
                p.y = bubbleY
            } else {
                p.width = cardWidthPx
                p.height = cardHeightPx
                p.x = clampX(p.x)
                p.y = clampY(p.y)
            }
            try {
                windowManager?.updateViewLayout(root, p)
                persistState()
            } catch (_: Throwable) {
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 统一的按压动效：按下轻微收缩、抬起回弹。
     * 返回 false 不消费事件，点击/长按仍由 View 自身处理。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressFeedback(vararg views: View) {
        for (v in views) {
            v.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate()
                        .scaleX(0.955f).scaleY(0.955f)
                        .setDuration(90).setInterpolator(DecelerateInterpolator()).start()

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(150).setInterpolator(OvershootInterpolator(1.1f)).start()
                }
                false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 气泡触摸：
     *  - 单击 → 展开回完整卡片（回到收缩前的横向位置）；
     *  - 按住拖动 → 气泡跟随移动（拖动中恢复完全不透明）；
     *  - 松开时若已离开边缘超过阈值 → 自动展开成卡片；否则弹簧吸附回最近一侧并恢复 0.85 半透明。
     */
    @SuppressLint("ClickableViewAccessibility")
    private val bubbleTouchListener = object : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val cp = cardParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleDownRawX = event.rawX
                    bubbleDownRawY = event.rawY
                    bubbleStartX = cp.x
                    bubbleStartY = cp.y
                    bubbleDragging = false
                    // 按住即恢复完全不透明，并给一点点按压反馈
                    bubbleView?.alpha = 1f
                    bubbleView?.animate()?.scaleX(0.94f)?.scaleY(0.94f)?.setDuration(90)?.start()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - bubbleDownRawX).toInt()
                    val dy = (event.rawY - bubbleDownRawY).toInt()
                    if (!bubbleDragging && (abs(dx) > dp(4) || abs(dy) > dp(4))) {
                        bubbleDragging = true
                    }
                    if (bubbleDragging) {
                        val (sw, _) = screenSize()
                        // 允许拖到屏幕外一部分（与停靠时露出的宽度一致），避免一开始拖就「跳」一下
                        val minX = dp(BUBBLE_VISIBLE_DP) - bubbleSizePx
                        val maxX = sw - dp(BUBBLE_VISIBLE_DP)
                        cp.x = (bubbleStartX + dx).coerceIn(minX, maxX)
                        cp.y = clampBubbleY(bubbleStartY + dy)
                        try {
                            windowManager?.updateViewLayout(windowRoot, cp)
                        } catch (_: Throwable) {
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bubbleView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                    val (sw, _) = screenSize()
                    val leftGap = cp.x
                    val rightGap = sw - (cp.x + bubbleSizePx)
                    val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                    if (!bubbleDragging && !cancelled) {
                        // 单击气泡：展开回收缩前的横向位置，纵向沿用气泡当前位置
                        expandFromBubble(lastCardX, bubbleY)
                        return true
                    }
                    if (!cancelled && min(leftGap, rightGap) > dp(BUBBLE_PULL_DP)) {
                        // 已拖离边缘足够远：松开自动展开成完整卡片（以气泡中心为锚点）
                        expandFromBubble(
                            cp.x + bubbleSizePx / 2 - cardWidthPx / 2,
                            cp.y + bubbleSizePx / 2 - cardHeightPx / 2
                        )
                    } else {
                        // 仍在边缘附近 / 手势取消：弹簧吸附回最近一侧
                        val side = if (leftGap <= rightGap) -1 else 1
                        springDockBubble(cp.x, cp.y, side, clampBubbleY(cp.y))
                    }
                    return true
                }
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        LocalStorageManager.init(applicationContext)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        palette = buildPalette()
        createFloatingView(animateIn = true, startPos = null)
        _isRunningFlow.value = true

        // 实时监听列表变化与「悬浮窗显示待办」开关；待办默认不显示
        serviceScope.launch {
            combine(
                LocalStorageManager.pickupItemsFlow,
                LocalStorageManager.showTodoInFloatingFlow
            ) { allItems, showTodo -> allItems to showTodo }
                .collectLatest { (allItems, showTodo) ->
                    val visible = allItems.filter {
                        !it.isPicked && (showTodo || !it.isTodo)
                    }
                    lastVisible = visible
                    renderUnpickedItems(visible)
                }
        }

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)
    }

    /** 深浅色模式切换（系统主题变化）时无缝重建卡片 / 气泡配色，保持形态、位置与折叠状态不变 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val root = windowRoot ?: return
        val x = cardParams?.x ?: 0
        val y = cardParams?.y ?: 0
        persistState()
        palette = buildPalette()
        try {
            windowManager?.removeView(root)
        } catch (_: Throwable) {
        }
        windowRoot = null
        rootContainer = null
        bubbleView = null
        // 气泡形态下重建时，展开位置仍用收缩前的卡片坐标
        val expandedPos = if (mode == FloatMode.BUBBLE) lastCardX to lastCardY else x to y
        createFloatingView(animateIn = false, startPos = expandedPos)
        renderUnpickedItems(lastVisible)
    }

    /** 取得物理屏幕尺寸（含系统栏区域），用于边缘夹取与位置记忆默认值 */
    private fun screenSize(): Pair<Int, Int> {
        val wm = windowManager ?: return Pair(1080, 1920)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            Pair(p.x, p.y)
        }
    }

    private fun systemBarHeight(name: String, fallbackDp: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(fallbackDp)
    }

    private fun statusBarHeight(): Int = systemBarHeight("status_bar_height", 24)

    private fun navBarHeight(): Int {
        // 手势导航时该值通常为 0，兜底给一点留白
        val h = systemBarHeight("navigation_bar_height", 0)
        return if (h > 0) h else dp(6)
    }

    /**
     * 计算并锁定卡片窗口的像素宽高：
     * 默认 210×320dp；屏幕更小（如横屏）时按可用空间收缩，保证整卡在屏内、不被截断。
     * 只在创建与屏幕变化时调用，拖动过程中绝不重算。
     */
    private fun updateCardMetrics() {
        val (sw, sh) = screenSize()
        val marginX = dp(6)
        val topLimit = statusBarHeight() + dp(4)
        val bottomLimit = navBarHeight() + dp(6)

        val maxW = max(dp(140), sw - marginX * 2)
        val maxH = max(dp(CARD_H_MIN_DP), sh - topLimit - bottomLimit)

        cardWidthPx = min(dp(CARD_W_DP), maxW)
        cardHeightPx = min(dp(CARD_H_DP), maxH)
        bubbleSizePx = dp(BUBBLE_DP)
    }

    /** 左边界 0 / 右边界 = 屏宽 - 窗口宽，保证整卡不越出屏幕 */
    private fun clampX(x: Int): Int {
        val (sw, _) = screenSize()
        val maxX = max(0, sw - cardWidthPx)
        return x.coerceIn(0, maxX)
    }

    /** 上边界避开状态栏、下边界避开导航栏，保证整卡完整可见 */
    private fun clampY(y: Int): Int {
        val (_, sh) = screenSize()
        val minY = statusBarHeight() + dp(2)
        val maxY = max(minY, sh - cardHeightPx - navBarHeight() - dp(2))
        return y.coerceIn(minY, maxY)
    }

    // ==================== 贴边气泡：位置与状态 ====================

    /** 气泡纵向夹取：避开状态栏与导航栏，上下各留 8dp 安全边距 */
    private fun clampBubbleY(y: Int): Int {
        val (_, sh) = screenSize()
        val minY = statusBarHeight() + dp(8)
        val maxY = max(minY, sh - bubbleSizePx - navBarHeight() - dp(8))
        return y.coerceIn(minY, maxY)
    }

    /** 贴边时气泡窗口的 X：左侧大部分移出屏外、只露 BUBBLE_VISIBLE_DP；右侧同理 */
    private fun dockedBubbleX(side: Int): Int {
        val (sw, _) = screenSize()
        val visible = dp(BUBBLE_VISIBLE_DP)
        return if (side < 0) visible - bubbleSizePx else sw - visible
    }

    /** 形态与位置写盘：展开记卡片坐标，气泡记左右侧 + 纵向位置 + 收缩前的卡片坐标 */
    private fun persistState() {
        val p = cardParams ?: return
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (mode == FloatMode.BUBBLE) {
            sp.putString(KEY_MODE, "bubble")
                .putInt(KEY_SIDE, bubbleSide)
                .putInt(KEY_BUBBLE_Y, bubbleY)
                .putInt(KEY_X, lastCardX)
                .putInt(KEY_Y, lastCardY)
        } else {
            sp.putString(KEY_MODE, "expanded")
                .putInt(KEY_X, p.x)
                .putInt(KEY_Y, p.y)
        }
        sp.apply()
    }

    /** 气泡外观：与卡片同一套磨砂令牌，圆角方形底板（同 App 图标形状）+ 白色半透明宽边 + APP 图标 */
    private fun buildBubbleView(p: Palette): View {
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(p.glassTop, p.glassMid, p.glassBottom)
        ).apply {
            // 与 App 图标同形状：圆角方形（squircle，圆角 ≈ 边长 28%），不再是圆形，
            // 这样一眼能对应上桌面上的自己，也更容易和其他圆形悬浮球区分。
            shape = GradientDrawable.RECTANGLE
            cornerRadius = bubbleSizePx * BUBBLE_CORNER_RATIO
            // 白色半透明宽边：浅色约 85% 白、深色约 50% 白，宽度 3dp，让气泡从壁纸上浮出来
            setStroke(dp(BUBBLE_BORDER_DP), p.bubbleBorder)
        }
        val icon = ImageView(this).apply {
            // 自己的包名一定取得到；万一取不到也不让服务崩，保留空底板
            runCatching { setImageDrawable(packageManager.getApplicationIcon(packageName)) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val margin = dp(8)
        return FrameLayout(this).apply {
            background = bg
            alpha = DOCKED_BUBBLE_ALPHA
            contentDescription = "展开取了吗悬浮窗"
            addView(
                icon,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { setMargins(margin, margin, margin, margin) }
            )
        }
    }

    /**
     * 收缩为贴边气泡：
     * 卡片朝最近边缘缩放淡出（180ms）→ 窗口切成气泡尺寸并贴边 → 气泡回弹淡入（220ms，停靠在 0.85 透明度）。
     */
    private fun collapseToBubble(side: Int, releaseTopY: Int) {
        if (modeAnimating || mode == FloatMode.BUBBLE) return
        val params = cardParams ?: return
        val card = rootContainer ?: return
        val bubble = bubbleView ?: return
        modeAnimating = true

        lastCardX = params.x
        lastCardY = params.y
        bubbleSide = side
        // 气泡纵向沿用拖动松手时的位置（仅做安全区夹取，不自动上下移动）
        bubbleY = clampBubbleY(releaseTopY)
        val targetX = dockedBubbleX(side)
        val edgeDelta = if (side < 0) -dp(14) else dp(14)

        card.pivotX = if (side < 0) 0f else cardWidthPx.toFloat()
        card.pivotY = cardHeightPx * 0.35f
        card.animate()
            .alpha(0f)
            .scaleX(0.5f).scaleY(0.5f)
            .translationX(edgeDelta.toFloat())
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                params.width = bubbleSizePx
                params.height = bubbleSizePx
                params.x = targetX
                params.y = bubbleY
                card.visibility = View.GONE
                card.alpha = 1f
                card.scaleX = 1f
                card.scaleY = 1f
                card.translationX = 0f
                bubble.visibility = View.VISIBLE
                bubble.alpha = 0f
                bubble.scaleX = 0.55f
                bubble.scaleY = 0.55f
                bubble.pivotX = bubbleSizePx / 2f
                bubble.pivotY = bubbleSizePx / 2f
                try {
                    windowManager?.updateViewLayout(windowRoot, params)
                } catch (_: Throwable) {
                }
                mode = FloatMode.BUBBLE
                persistState()
                bubble.animate()
                    .alpha(DOCKED_BUBBLE_ALPHA)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(0.85f))
                    .withEndAction { modeAnimating = false }
                    .start()
            }
            .start()
    }

    /**
     * 从气泡展开为完整卡片：气泡缩小淡出（130ms）→ 窗口切回卡片尺寸与目标坐标 →
     * 卡片从边缘方向回弹展开（260ms）。
     */
    private fun expandFromBubble(targetX: Int, targetY: Int) {
        if (modeAnimating || mode != FloatMode.BUBBLE) return
        val params = cardParams ?: return
        val card = rootContainer ?: return
        val bubble = bubbleView ?: return
        modeAnimating = true

        bubble.animate()
            .alpha(0f)
            .scaleX(0.6f).scaleY(0.6f)
            .setDuration(130)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                val tx = clampX(targetX)
                val ty = clampY(targetY)
                params.width = cardWidthPx
                params.height = cardHeightPx
                params.x = tx
                params.y = ty
                bubble.visibility = View.GONE
                bubble.alpha = DOCKED_BUBBLE_ALPHA
                bubble.scaleX = 1f
                bubble.scaleY = 1f
                card.visibility = View.VISIBLE
                card.alpha = 0f
                card.scaleX = 0.86f
                card.scaleY = 0.86f
                card.translationX = if (bubbleSide < 0) -dp(12).toFloat() else dp(12).toFloat()
                card.pivotX = if (bubbleSide < 0) 0f else cardWidthPx.toFloat()
                card.pivotY = cardHeightPx * 0.35f
                try {
                    windowManager?.updateViewLayout(windowRoot, params)
                } catch (_: Throwable) {
                }
                mode = FloatMode.EXPANDED
                lastCardX = tx
                lastCardY = ty
                persistState()
                card.animate()
                    .alpha(1f)
                    .scaleX(1f).scaleY(1f)
                    .translationX(0f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(0.7f))
                    .withEndAction { modeAnimating = false }
                    .start()
            }
            .start()
    }

    /** 气泡拖动后仍在边缘附近：弹簧动画吸附回最近一侧，并恢复 0.85 半透明 */
    private fun springDockBubble(fromX: Int, fromY: Int, side: Int, toY: Int) {
        val cp = cardParams ?: return
        val bubble = bubbleView ?: return
        modeAnimating = true
        bubbleSide = side
        val toX = dockedBubbleX(side)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(0.5f)
            addUpdateListener { a ->
                val t = a.animatedFraction
                cp.x = (fromX + (toX - fromX) * t).toInt()
                cp.y = (fromY + (toY - fromY) * t).toInt()
                try {
                    windowManager?.updateViewLayout(windowRoot, cp)
                } catch (_: Throwable) {
                }
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                cp.x = toX
                cp.y = toY
                try {
                    windowManager?.updateViewLayout(windowRoot, cp)
                } catch (_: Throwable) {
                }
                bubbleY = toY
                mode = FloatMode.BUBBLE
                persistState()
                bubble.alpha = 1f
                bubble.animate().alpha(DOCKED_BUBBLE_ALPHA).setDuration(140).start()
                modeAnimating = false
            }
        })
        animator.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView(animateIn: Boolean, startPos: Pair<Int, Int>?) {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = palette ?: buildPalette().also { palette = it }

        updateCardMetrics()

        // 读取上次形态：展开坐标 / 贴边侧与气泡纵向位置（关闭再开、横竖屏重建都走这里）
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        bubbleSide = if (sp.getInt(KEY_SIDE, -1) >= 0) 1 else -1
        bubbleY = sp.getInt(KEY_BUBBLE_Y, dp(120))
        lastCardX = sp.getInt(KEY_X, screenSize().first - cardWidthPx - dp(12))
        lastCardY = sp.getInt(KEY_Y, dp(120))
        if (startPos != null) {
            lastCardX = startPos.first
            lastCardY = startPos.second
        }
        mode = if (sp.getString(KEY_MODE, "expanded") == "bubble") FloatMode.BUBBLE else FloatMode.EXPANDED

        // ===== 唯一窗口：白色磨砂卡片（展开）/ 圆角方形气泡（贴边）=====
        // 刻意【不使用】FLAG_BLUR_BEHIND：
        // MIUI / HyperOS 会把「背景模糊」做成整屏模糊层（桌面全部糊掉），且该层会吞掉触摸，
        // 导致悬浮窗弹出时桌面完全不能操作。磨砂质感改为卡片自绘（近白高不透明度 + 渐变 + 柔光）。
        // FLAG_NOT_TOUCH_MODAL：窗口以外的触摸明确交还给下层桌面 / App 主页，绝不拦截。
        val params = WindowManager.LayoutParams(
            if (mode == FloatMode.BUBBLE) bubbleSizePx else cardWidthPx,
            if (mode == FloatMode.BUBBLE) bubbleSizePx else cardHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (mode == FloatMode.BUBBLE) {
                bubbleY = clampBubbleY(bubbleY)
                x = dockedBubbleX(bubbleSide)
                y = bubbleY
            } else {
                x = clampX(lastCardX)
                y = clampY(lastCardY)
            }
        }
        cardParams = params

        // 白色柔光磨砂卡片：三段渐变 + 左上柔光晕 + 发丝描边（圆角统一 24dp，随深浅色切换）
        val cardBackground = buildGlassDrawable(p)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            // 内边距固定，绝不随内容压缩
            setPadding(dp(14), dp(10), dp(14), dp(14))
            layoutParams = FrameLayout.LayoutParams(cardWidthPx, cardHeightPx)
            if (animateIn && mode == FloatMode.EXPANDED) {
                alpha = 0f
                scaleX = 0.94f
                scaleY = 0.94f
                translationY = -dp(10).toFloat()
            }
        }

        // ===== 第一行：拖动把手（标题计数）+ 关闭 × =====
        // 整行都是拖动区域；关闭按钮是子 View，点击优先由自己消费，不受拖动影响
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(30)
        }

        titleCountView = TextView(this).apply {
            text = "待取 0"
            setTextColor(p.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBg = GradientDrawable().apply {
            setColor(p.itemBgPressed)
            cornerRadius = dp(18).toFloat()
        }
        val closeBtn = TextView(this).apply {
            text = "×"
            setTextColor(p.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = closeBg
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            setOnClickListener { dismissWithAnimation() }
        }
        headerRow.addView(titleCountView)
        headerRow.addView(closeBtn)

        // ===== 第二行：出库码 + 读取剪贴板（等宽、固定高度、单行，不可压缩换行）=====
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val codeBtnBg = GradientDrawable().apply {
            setColor(p.primary)
            cornerRadius = dp(16).toFloat()
        }
        val codeBtn = TextView(this).apply {
            text = "出库码"
            setTextColor(p.onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = codeBtnBg
            maxLines = 1
            isSingleLine = true
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(5) }
            setOnClickListener { toggleCodeImage() }
            // 长按强制打开动态出库码（即使静态兜底已开启）
            setOnLongClickListener {
                openDynamicOutboundCode()
                true
            }
        }
        codeToggleBtn = codeBtn

        val clipBtnBg = GradientDrawable().apply {
            setColor(p.itemBg)
            cornerRadius = dp(16).toFloat()
        }
        val clipBtn = TextView(this).apply {
            text = "读取剪贴板"
            setTextColor(p.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = clipBtnBg
            maxLines = 1
            isSingleLine = true
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginStart = dp(5) }
            setOnClickListener { openManualClipboardRead() }
        }

        actionRow.addView(codeBtn)
        actionRow.addView(clipBtn)
        // 关闭 / 出库码 / 读取剪贴板：统一的按压回弹动效
        attachPressFeedback(closeBtn, codeBtn, clipBtn)

        val subtitleTip = TextView(this).apply {
            text = "按住顶部拖动 · 点码核销"
            setTextColor(p.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            isSingleLine = true
            setPadding(0, dp(8), 0, dp(8))
        }
        tipViewRef = subtitleTip

        // 固定内容区（weight=1 撑满剩余高度）：列表与出库码图二选一，切换可见性，不改窗口尺寸
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(6) }
        }

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listContainer)
        scrollViewRef = scrollView

        codeImageView = ImageView(this).apply {
            visibility = View.GONE
            val whiteCard = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(16).toFloat()
            }
            background = whiteCard
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { toggleCodeImage() }
        }

        contentFrame?.addView(scrollView)
        contentFrame?.addView(codeImageView)

        rootContainer?.addView(headerRow)
        rootContainer?.addView(actionRow)
        rootContainer?.addView(subtitleTip)
        rootContainer?.addView(contentFrame)

        // ===== 拖动把手：仅顶栏 + 提示行；列表与按钮各自正常点击 / 滚动 =====
        val dragTouchListener = object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downRawX = 0f
            private var downRawY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val cp = cardParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = cp.x
                        initialY = cp.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - downRawX).toInt()
                        val deltaY = (event.rawY - downRawY).toInt()
                        if (!isDragging && (abs(deltaX) > dp(4) || abs(deltaY) > dp(4))) {
                            isDragging = true
                            // 拖动时轻微降透明度：视觉上「拿起来」了，同时不遮挡底下的内容
                            rootContainer?.animate()?.alpha(0.94f)?.setDuration(120)?.start()
                        }
                        if (isDragging) {
                            // 只改坐标，宽高锁定不变；同时做屏幕边缘夹取，保证整卡不被截断
                            cp.x = clampX(initialX + deltaX)
                            cp.y = clampY(initialY + deltaY)
                            try {
                                windowManager?.updateViewLayout(windowRoot, cp)
                            } catch (_: Throwable) {
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            val (sw, _) = screenSize()
                            val leftGap = cp.x
                            val rightGap = sw - (cp.x + cardWidthPx)
                            if (min(leftGap, rightGap) <= dp(EDGE_SNAP_DP)) {
                                // 松手位置贴近屏幕左右边缘：吸附该侧并收缩成贴边气泡
                                collapseToBubble(if (leftGap <= rightGap) -1 else 1, cp.y)
                            } else {
                                // 离边缘较远：正常停靠，并回弹到不透明
                                persistState()
                                rootContainer?.animate()?.alpha(1f)?.setDuration(160)?.start()
                            }
                        }
                        return isDragging
                    }
                }
                return false
            }
        }

        headerRow.setOnTouchListener(dragTouchListener)
        subtitleTip.setOnTouchListener(dragTouchListener)

        // ===== 根容器：卡片 + 气泡共用同一个窗口，只切换尺寸与可见性 =====
        bubbleView = buildBubbleView(p).apply {
            visibility = if (mode == FloatMode.BUBBLE) View.VISIBLE else View.GONE
        }
        bubbleView?.setOnTouchListener(bubbleTouchListener)
        rootContainer?.visibility = if (mode == FloatMode.BUBBLE) View.GONE else View.VISIBLE

        windowRoot = FrameLayout(this).apply {
            addView(rootContainer)
            addView(bubbleView, FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx))
        }

        try {
            windowManager?.addView(windowRoot, params)
            if (mode == FloatMode.BUBBLE) {
                // 以气泡形态恢复：轻轻弹入
                bubbleView?.let { b ->
                    b.alpha = 0f
                    b.scaleX = 0.6f
                    b.scaleY = 0.6f
                    b.pivotX = bubbleSizePx / 2f
                    b.pivotY = bubbleSizePx / 2f
                    b.animate().alpha(DOCKED_BUBBLE_ALPHA).scaleX(1f).scaleY(1f)
                        .setDuration(240)
                        .setInterpolator(OvershootInterpolator(0.85f))
                        .start()
                }
            } else if (animateIn) {
                // 入场动效：淡入 + 轻微放大回弹 + 自上而下 10dp 落位（始终在窗口内，不裁切不变形）
                rootContainer?.pivotX = cardWidthPx.toFloat()
                rootContainer?.pivotY = 0f
                rootContainer?.animate()
                    ?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.translationY(0f)
                    ?.setDuration(260)
                    ?.setInterpolator(OvershootInterpolator(0.85f))
                    ?.start()
            } else {
                rootContainer?.alpha = 1f
                rootContainer?.translationY = 0f
            }
        } catch (e: Throwable) {
            android.widget.Toast.makeText(
                this, "悬浮窗启动失败：${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT
            ).show()
            stopSelf()
        }
    }

    /** 退场动效：淡出 + 缩小后再移除（同时记忆当前形态与位置） */
    private fun dismissWithAnimation() {
        persistState()
        val visible = if (mode == FloatMode.BUBBLE) bubbleView else rootContainer
        val view = visible ?: run { stopSelf(); return }
        view.animate()
            ?.alpha(0f)?.scaleX(0.9f)?.scaleY(0.9f)
            ?.setDuration(160)
            ?.withEndAction { stopSelf() }
            ?.start()
    }

    /**
     * 主动读取剪贴板。
     * Android 10+ 只允许前台应用/默认输入法读取剪贴板，后台 Service 直接读会拿到空值。
     * 这里启动完全透明的 ClipboardReadActivity 短暂取得焦点完成导入后立刻关闭，
     * 不再把 MainActivity 拉到前台，用户视角全程停留在当前页面。
     * 本应用持有 SYSTEM_ALERT_WINDOW，允许从后台启动该 Activity。
     */
    private fun openManualClipboardRead() {
        try {
            val intent = Intent(this, ClipboardReadActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            startActivity(intent)
        } catch (e: Throwable) {
            android.widget.Toast.makeText(
                this,
                "无法读取剪贴板：${e.localizedMessage ?: "未知错误"}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 打开动态出库码：优先用快捷按钮里的「出库码」路由，找不到则用默认淘宝路由 */
    private fun openDynamicOutboundCode() {
        val routes = LocalStorageManager.quickButtonsFlow.value
            .filter { it.kind == QuickButtonKind.ROUTE }
        val target = routes.firstOrNull { it.label.contains("出库") } ?: routes.getOrNull(1)
        val config = target?.toRouteConfig() ?: LocalStorageManager.taobaoConfigFlow.value
        RouteLauncher.launchWithFallback(this, config)
    }

    /**
     * 点击悬浮窗【出库码】：默认打开动态出库码页面；
     * 只有静态截图已保存且兜底开关开启时，才在悬浮窗内展开本地静态图（最高亮度）。
     * 无论哪种情况，窗口宽高保持不变（出库码图在原固定内容区内切换可见性）。
     */
    private fun toggleCodeImage() {
        if (showingCode) {
            showingCode = false
            codeToggleBtn?.text = "出库码"
            // 出库码淡出、列表淡入，避免硬切闪屏
            codeImageView?.let { code ->
                code.animate().alpha(0f).setDuration(140).withEndAction {
                    code.visibility = View.GONE
                    code.alpha = 1f
                }.start()
            }
            tipViewRef?.visibility = View.VISIBLE
            scrollViewRef?.let { list ->
                list.alpha = 0f
                list.visibility = View.VISIBLE
                list.animate().alpha(1f).setDuration(180).setStartDelay(50).start()
            }
            return
        }
        // 默认走动态出库码；只有「静态截图已保存 + 兜底开关打开」才展示本地静态图
        val staticReady = LocalStorageManager.staticCodeEnabledFlow.value &&
            LocalStorageManager.hasStaticBarcodeImage(this)
        if (!staticReady) {
            openDynamicOutboundCode()
            return
        }
        val file = LocalStorageManager.getStaticBarcodeFile(this)
        // 悬浮窗里的出库码图同样按采样解码，避免高清大图 OOM
        val bitmap = BitmapDecodeHelper.decodeSampledFile(file.absolutePath)
        if (bitmap == null) {
            android.widget.Toast.makeText(this, "本地出库码图片无法读取", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val code = codeImageView ?: return
        code.setImageBitmap(bitmap)
        code.alpha = 0f
        code.visibility = View.VISIBLE
        code.animate().alpha(1f).setDuration(200).start()
        // 列表淡出后隐藏（保持 alpha 复原，下次切回不残留透明）
        scrollViewRef?.animate()?.alpha(0f)?.setDuration(120)?.withEndAction {
            scrollViewRef?.visibility = View.GONE
            scrollViewRef?.alpha = 1f
        }?.start()
        tipViewRef?.visibility = View.GONE
        codeToggleBtn?.text = "收起"
        showingCode = true
    }

    private fun renderUnpickedItems(unpickedItems: List<PickupItem>) {
        val container = listContainer ?: return
        val p = palette ?: buildPalette()
        container.removeAllViews()
        // 内容（条目 id 序列）变化时才播放逐条错峰入场，纯刷新时不重播
        val ids = unpickedItems.map { it.id }
        val animateEnter = ids != lastRenderedIds
        lastRenderedIds = ids
        val showTodo = LocalStorageManager.showTodoInFloatingFlow.value
        val todoCount = unpickedItems.count { it.isTodo }
        val parcelCount = unpickedItems.size - todoCount
        // 「待办 N」文本与设置里的「悬浮窗显示待办」开关绑定：
        // 关闭时不显示待办计数（连「待办 0」也没有），只显示「待取 N」
        titleCountView?.text = if (showTodo) {
            "待取 $parcelCount · 待办 $todoCount"
        } else {
            "待取 $parcelCount"
        }

        if (unpickedItems.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "全部包裹已取完"
                setTextColor(p.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(6))
                alpha = 0f
            }
            container.addView(emptyTv)
            emptyTv.animate().alpha(1f).setDuration(220).start()
            return
        }

        // 逐条错峰入场的序号（每个条目 +1）
        var enterIndex = 0

        currentUnpicked = unpickedItems
        val todoItems = unpickedItems.filter { it.isTodo }
        val pickupItems = unpickedItems.filter { !it.isTodo }
        val pickupByShelf = pickupItems.groupBy { it.shelfNum }.toSortedMap()

        // 构造并添加一条待办/取件条目（点击直接核销，按下有缩放反馈）
        fun addCodeItem(item: PickupItem) {
            val codeTv = TextView(this@FloatingWindowService).apply {
                text = "○  ${if (item.isTodo) item.note else item.displayCode}"
                setTextColor(p.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.MONOSPACE
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = GradientDrawable().apply {
                    setColor(p.itemBg)
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
                layoutParams = lp
                // 点击悬浮窗内的某个取件码，直接标记为 isPicked = true 并从悬浮窗移除
                setOnClickListener {
                    // 核销触感反馈：轻震一下再移除，符合澎湃「操作有回应」的手感
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                        .withEndAction {
                            LocalStorageManager.togglePickedStatus(item.id)
                            animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        }.start()
                }
            }
            container.addView(codeTv)
            if (animateEnter) {
                // 错峰淡入 + 自下而上 6dp 落位；延迟封顶 200ms，长列表也不会等太久
                codeTv.alpha = 0f
                codeTv.translationY = dp(6).toFloat()
                codeTv.animate().alpha(1f).translationY(0f)
                    .setDuration(200)
                    .setStartDelay(min(enterIndex * 26L, 200L))
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            enterIndex++
        }

        // 一级文件夹标题（可点击折叠/展开）
        fun folderBadge(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit): TextView {
            return TextView(this@FloatingWindowService).apply {
                text = (if (expanded) "▾ " else "▸ ") + "$title（$count）"
                setTextColor(p.primary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                isSingleLine = true
                setPadding(dp(4), dp(8), 0, dp(4))
                setOnClickListener { onToggle() }
            }
        }

        // ===== 待办文件夹（可折叠）：内含不可折叠的待办条目 =====
        if (todoItems.isNotEmpty()) {
            container.addView(folderBadge("待办", todoItems.size, todoExpanded) {
                todoExpanded = !todoExpanded
                renderUnpickedItems(currentUnpicked)
            })
            if (todoExpanded) {
                for (item in todoItems) addCodeItem(item)
            }
        }

        // ===== 待取文件夹（可折叠）→ 货架二级分组（固定展开、无箭头）=====
        if (pickupItems.isNotEmpty()) {
            container.addView(folderBadge("待取", pickupItems.size, pickupExpanded) {
                pickupExpanded = !pickupExpanded
                renderUnpickedItems(currentUnpicked)
            })
            if (pickupExpanded) {
                for ((shelfNum, shelfItems) in pickupByShelf) {
                    // 货架标签：固定展开、无折叠箭头、显示本组数量
                    val shelfBadge = TextView(this@FloatingWindowService).apply {
                        text = "  ${shelfNum} 号架（${shelfItems.size}）"
                        setTextColor(p.onSurfaceVariant)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        isSingleLine = true
                        setPadding(dp(12), dp(5), 0, dp(2))
                    }
                    container.addView(shelfBadge)
                    for (item in shelfItems) addCodeItem(item)
                }
            }
        }
    }

    override fun onDestroy() {
        _isRunningFlow.value = false
        serviceScope.cancel()
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        windowRoot?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {
            }
        }
        windowRoot = null
        rootContainer = null
        bubbleView = null
        super.onDestroy()
    }
}
