package com.teslalink.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token：分层深色座舱。
 *
 * 深度靠「更亮的表面」表达而不是阴影；正文对比度按 WCAG AA；
 * 所有数字使用等宽字形（tnum），避免实时数据跳动。
 */
object TL {
    // 层次（surface）
    val ink = Color(0xFF080A0D)          // 页面底
    val base = Color(0xFF0E1116)         // 一级容器
    val surface = Color(0xFF141920)      // 卡片
    val raised = Color(0xFF1B222A)       // 卡片内控件
    val sunken = Color(0xFF0B0E12)       // 日志/图表底

    // 描边
    val hairline = Color(0xFF232C36)
    val hairlineSoft = Color(0xFF1A2129)

    // 文字
    val textHi = Color(0xFFE8EEF5)
    val textMid = Color(0xFF9BAABA)
    val textLow = Color(0xFF647382)

    // 语义色（座舱里用饱和度略低的色，夜里不刺眼）
    val accent = Color(0xFF4C8DFF)       // 信息 / 主操作
    val accentSoft = Color(0xFF16233A)
    val good = Color(0xFF3BC46B)         // 就绪 / 成功
    val warn = Color(0xFFFFB020)         // 注意 / 未知偏向
    val bad = Color(0xFFF04A50)          // 错误 / 危险
    val brand = Color(0xFFE8323C)        // Tesla 红，只用于极少数强调
    val data = Color(0xFF35C6E0)         // 实时数据

    // 圆角
    val rCard = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    val rTile = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    val rPill = androidx.compose.foundation.shape.CircleShape

    // 常用文字预设
    val eyebrow = TextStyle(
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        color = textLow,
    )
    val metric = TextStyle(
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        color = textHi,
        fontFeatureSettings = "tnum",
    )
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        color = Color(0xFFC9D6E2),
    )
}

private val Typography = Typography(
    displayLarge = TextStyle(
        fontSize = 64.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.5).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
    bodyLarge = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        color = TL.textMid,
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 10.5.sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = "tnum",
    ),
)

private val Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
)

private val Scheme = darkColorScheme(
    primary = TL.accent,
    onPrimary = Color(0xFF061021),
    primaryContainer = TL.accentSoft,
    onPrimaryContainer = TL.textHi,
    secondary = TL.data,
    onSecondary = Color(0xFF04171C),
    secondaryContainer = Color(0xFF142830),
    onSecondaryContainer = TL.textHi,
    tertiary = TL.good,
    onTertiary = Color(0xFF04170C),
    background = TL.ink,
    onBackground = TL.textHi,
    surface = TL.base,
    onSurface = TL.textHi,
    surfaceVariant = TL.surface,
    onSurfaceVariant = TL.textMid,
    outline = TL.hairline,
    outlineVariant = TL.hairlineSoft,
    error = TL.bad,
    onError = Color(0xFF210607),
)

/** 座舱类 app 只出深色：夜间在车里看，浅色主题会刺眼且与仪表盘硬编码底色冲突。 */
@Composable
fun TeslaLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
