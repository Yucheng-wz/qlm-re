package com.hnu.pickupassistant.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 澎湃（HyperOS）风格设计令牌。
 *
 * 设计原则：
 *  - 浅冷灰底 + 纯白卡片，靠色块层次而非阴影区分层级
 *  - 大圆角（卡片 24dp、控件 18dp、按钮全胶囊）
 *  - 主色取自新版 App 图标蓝 #2595F3，低饱和中性色打底
 *  - 分组式设置、克制的强调色、充足留白
 * 所有界面必须从这里取色/取圆角，避免风格割裂。
 */
object HyperColors {
    val Primary = Color(0xFF2595F3)
    val PrimaryDeep = Color(0xFF0F7BDD)
    val PrimarySoft = Color(0xFFE7F2FF)

    val Background = Color(0xFFF4F6FA)
    val Surface = Color(0xFFFCFDFE)
    val OnPrimary = Color(0xFFF6FAFF)
    val OnError = Color(0xFFFDF3F3)
    val Scrim = Color(0x990E1116)
    // 控件 / 输入框底色：比 Background 更深一档，保证在浅底上有清晰可辨的层次。
    // 旧值 #F1F3F8 与背景 #F4F6FA 几乎相同，导致输入框、次按钮看起来像“没有底色”。
    val SurfaceMuted = Color(0xFFE0E6F0)
    val Divider = Color(0xFFE2E7F0)

    // 次要文字必须保证在白卡片 / 浅灰胶囊上有足够对比度。
    // 旧值 #89909E 在浅灰底上偏淡，实测截图里几乎读不清。
    val TextPrimary = Color(0xFF1B1F27)
    val TextSecondary = Color(0xFF5A6270)
    val TextTertiary = Color(0xFF79818F)

    val Success = Color(0xFF2FB37C)
    val SuccessSoft = Color(0xFFE5F7EF)
    val Danger = Color(0xFFEF5B5B)
    val DangerSoft = Color(0xFFFFEAEA)
    val Warning = Color(0xFFF5A623)

    // 深色模式（跟随系统）
    val DarkBackground = Color(0xFF111318)
    val DarkSurface = Color(0xFF1C2027)
    val DarkSurfaceMuted = Color(0xFF2B313B)
    val DarkTextPrimary = Color(0xFFF7F9FC)
    val DarkTextSecondary = Color(0xFFD2DAE7)
    val DarkPrimary = Color(0xFF62B2FF)
    val DarkPrimarySoft = Color(0xFF16324C)
    // 深色模式下的强调橙：原 #F5A623 在深底上偏暗，仅用于文字/计数时提亮
    val DarkWarning = Color(0xFFFFB84D)
}

object HyperRadius {
    val Small: Shape = RoundedCornerShape(10.dp)
    val Control: Shape = RoundedCornerShape(16.dp)
    val Card: Shape = RoundedCornerShape(24.dp)
    val Dialog: Shape = RoundedCornerShape(28.dp)
    val Pill: Shape = RoundedCornerShape(50)
}

object HyperSpacing {
    val Screen = 18.dp
    val Card = 16.dp
    val Item = 12.dp
    val Section = 18.dp
}

private val HyperTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 13.5f.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

private val HyperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val HyperLight = lightColorScheme(
    primary = HyperColors.Primary,
    onPrimary = HyperColors.OnPrimary,
    primaryContainer = HyperColors.PrimarySoft,
    onPrimaryContainer = HyperColors.PrimaryDeep,
    secondary = HyperColors.PrimaryDeep,
    onSecondary = HyperColors.OnPrimary,
    secondaryContainer = HyperColors.PrimarySoft,
    onSecondaryContainer = HyperColors.PrimaryDeep,
    tertiary = HyperColors.Success,
    background = HyperColors.Background,
    onBackground = HyperColors.TextPrimary,
    surface = HyperColors.Surface,
    onSurface = HyperColors.TextPrimary,
    surfaceVariant = HyperColors.SurfaceMuted,
    onSurfaceVariant = HyperColors.TextSecondary,
    surfaceTint = Color.Transparent,
    outline = Color(0xFFD8DEE8),
    outlineVariant = HyperColors.Divider,
    error = HyperColors.Danger,
    onError = HyperColors.OnError,
    errorContainer = HyperColors.DangerSoft,
    onErrorContainer = Color(0xFF9B2525),
    scrim = HyperColors.Scrim
)

private val HyperDark = darkColorScheme(
    primary = HyperColors.DarkPrimary,
    onPrimary = Color(0xFF06263F),
    primaryContainer = HyperColors.DarkPrimarySoft,
    onPrimaryContainer = Color(0xFFCFE8FF),
    secondary = HyperColors.DarkPrimary,
    onSecondary = Color(0xFF06263F),
    secondaryContainer = HyperColors.DarkPrimarySoft,
    onSecondaryContainer = Color(0xFFCFE8FF),
    tertiary = HyperColors.Success,
    background = HyperColors.DarkBackground,
    onBackground = Color(0xFFF7F9FC),
    surface = HyperColors.DarkSurface,
    onSurface = Color(0xFFF7F9FC),
    // 胶囊/输入框/次按钮上的文字：由 #B6BECA 提亮到 #D5DDE9，深灰底上对比度 > 9:1
    surfaceVariant = HyperColors.DarkSurfaceMuted,
    onSurfaceVariant = Color(0xFFD5DDE9),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF66717F),
    // 未勾选圆圈底色：原 #2B313A 与卡片几乎同色，提亮后控件边界可辨
    outlineVariant = Color(0xFF48525F),
    error = Color(0xFFFFA6A6),
    onError = Color(0xFF3B0D0D),
    errorContainer = Color(0xFF4A1E1E),
    onErrorContainer = Color(0xFFFFE2E2),
    scrim = Color(0xB30A0C10)
)

/**
 * 深浅模式自适应内容色（仅用于文字 / 图标，不改变容器底色）。
 *
 * 浅色模式返回原配色，视觉零变化；深色模式切到高对比亮色，
 * 解决「悬浮窗」「清除完成」等深灰胶囊上仍使用浅色主题深黑字、导致文字发灰看不清的问题。
 *
 * @author 焱川 (by 焱川)
 */
@Composable
fun hyperAdaptiveContent(light: Color, dark: Color): Color =
    if (isSystemInDarkTheme()) dark else light

@Composable
fun PickupTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) HyperDark else HyperLight
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // edge-to-edge：内容延伸到状态栏与手势小白条之下，由 Compose Scaffold
            // 的 contentWindowInsets 负责避让，避免底部内容被小白条遮挡。
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = HyperTypography,
        shapes = HyperShapes,
        content = content
    )
}

/** 统一的白色分组卡片：无阴影，靠浅灰背景衬托 */
@Composable
fun HyperCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = HyperRadius.Card,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(HyperSpacing.Card),
            content = content
        )
    }
}

/** 分组小标题 */
@Composable
fun HyperSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        // 分组标题是层级锚点，必须比正文更醒目，不能用浅灰
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** 填充式输入框：无边框、浅灰底、大圆角，贴近系统输入框观感 */
@Composable
fun HyperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    height: Dp = 54.dp,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        },
        singleLine = singleLine,
        maxLines = maxLines,
        shape = HyperRadius.Control,
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = modifier
            .height(height)
            .onFocusChanged { state -> onFocusChanged?.invoke(state.isFocused) },
        colors = TextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent
        )
    )
}

/** 主操作：蓝色胶囊按钮；compact=true 时压缩内边距与字号，供一行三按钮等分排布 */
@Composable
fun HyperPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (compact) 38.dp else 46.dp),
        shape = HyperRadius.Pill,
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        // 减小水平内边距，给中文按钮文案留足空间，避免「更换截图」被截成「更换截」
        contentPadding = PaddingValues(
            horizontal = if (compact) 8.dp else 14.dp,
            vertical = 8.dp
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) 14.dp else 16.dp))
            Spacer(Modifier.width(if (compact) 3.dp else 5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = if (compact) 13.sp else MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** 次操作：浅灰胶囊按钮；compact=true 时压缩内边距与字号，供一行三按钮等分排布 */
@Composable
fun HyperSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (compact) 38.dp else 46.dp),
        shape = HyperRadius.Pill,
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        // 减小水平内边距，给中文按钮文案留足空间，避免「更换截图」被截成「更换截」
        contentPadding = PaddingValues(
            horizontal = if (compact) 8.dp else 14.dp,
            vertical = 8.dp
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) 14.dp else 16.dp))
            Spacer(Modifier.width(if (compact) 3.dp else 5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = if (compact) 13.sp else MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}
