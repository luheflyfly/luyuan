package com.luyuan.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luyuan.ui.design.LuyuanPalette
import com.luyuan.ui.design.LuyuanRadius

/**
 * 主题（vc127 重写）：颜色/字阶/圆角全部来自 ui/design/Tokens.kt，页面不再各写一套。
 * 纸白 #F3EFE6 基底 + 墨绿 #224A3A 主色 + 木案金 #B08D4F 点睛——与电脑工作台同一套口径。
 */
private val LightColors = lightColorScheme(
    primary = LuyuanPalette.Green800,
    onPrimary = Color.White,
    primaryContainer = LuyuanPalette.Green100,
    onPrimaryContainer = LuyuanPalette.Green900,
    secondary = LuyuanPalette.Green700,
    onSecondary = Color.White,
    background = LuyuanPalette.Paper,
    onBackground = LuyuanPalette.Ink1,
    surface = LuyuanPalette.Surface,
    onSurface = LuyuanPalette.Ink1,
    surfaceVariant = LuyuanPalette.Sunken,
    onSurfaceVariant = LuyuanPalette.Ink2,
    outline = Color(0xFFE2DACB),
    outlineVariant = LuyuanPalette.Line,
    error = LuyuanPalette.Red,
    onError = Color.White,
    errorContainer = LuyuanPalette.RedBg,
    onErrorContainer = LuyuanPalette.Red
)

private val LuyuanTypography = Typography(
    // 页面标题（自定义头部用 LuyuanType.page；这里兜底 M3 默认路径）
    headlineMedium = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = LuyuanPalette.Ink1
    ),
    // TopAppBar / 弹窗标题
    titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = LuyuanPalette.Ink1
    ),
    titleMedium = TextStyle(
        fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = LuyuanPalette.Ink1
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = LuyuanPalette.Ink1
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal, color = LuyuanPalette.Ink1
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, color = LuyuanPalette.Ink1
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal, color = LuyuanPalette.Ink2
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = LuyuanPalette.Ink1
    ),
    labelMedium = TextStyle(
        fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = LuyuanPalette.Ink2
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal, color = LuyuanPalette.Ink3
    )
)

private val LuyuanShapes = Shapes(
    extraSmall = LuyuanRadius.smShape,
    small = LuyuanRadius.mdShape,
    medium = LuyuanRadius.lgShape,
    large = LuyuanRadius.lgShape,
    extraLarge = LuyuanRadius.xlShape
)

/** colorScheme 装不下的语义色：全 App 唯一别名处（值全部来自 Tokens）。 */
object LuyuanColors {
    val Green900 = LuyuanPalette.Green900
    val Green800 = LuyuanPalette.Green800
    val Green700 = LuyuanPalette.Green700
    val Green600 = LuyuanPalette.Green600
    val Green500 = LuyuanPalette.Green500
    val Green100 = LuyuanPalette.Green100
    val Green50 = LuyuanPalette.Green50

    val Ink1 = LuyuanPalette.Ink1
    val Ink2 = LuyuanPalette.Ink2
    val Ink3 = LuyuanPalette.Ink3
    val Ink4 = LuyuanPalette.Ink4

    val Amber = LuyuanPalette.Amber
    val AmberBg = LuyuanPalette.AmberBg
    val Red = LuyuanPalette.Red
    val RedBg = LuyuanPalette.RedBg
    val Blue = LuyuanPalette.Blue
    val BlueBg = LuyuanPalette.BlueBg
    val Purple = LuyuanPalette.Purple
    val PurpleBg = LuyuanPalette.PurpleBg
    val Pink = LuyuanPalette.Pink
    val PinkBg = LuyuanPalette.PinkBg

    val Gold = LuyuanPalette.Gold
    val GoldSoft = LuyuanPalette.GoldSoft
    val Line = LuyuanPalette.Line
    val LineStrong = LuyuanPalette.LineStrong

    val DividerStrong = LuyuanPalette.DividerStrong
    val WarmBg = LuyuanPalette.WarmBg
    val KeiwuGold = LuyuanPalette.KeiwuGold
    val GradGreenStart = LuyuanPalette.GradGreenStart
    val GradGreenEnd = LuyuanPalette.GradGreenEnd

    fun categoryColor(category: String): Color = when (category) {
        "餐饮" -> Amber
        "学习" -> Purple
        "日用" -> Green600
        "娱乐" -> Pink
        else -> Ink3
    }

    fun categoryBg(category: String): Color = when (category) {
        "餐饮" -> AmberBg
        "学习" -> PurpleBg
        "日用" -> Green100
        "娱乐" -> PinkBg
        else -> LuyuanPalette.Sunken
    }
}

@Composable
fun LuyuanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = LuyuanTypography,
        shapes = LuyuanShapes,
        content = content
    )
}

/** 键盘高度实测兜底（vc77 起套路；vivo 上 WindowInsets.ime 恒 0 的全 ROM 解）。
 *  MainActivity 的 GlobalLayout 监听写入，任何底部输入的屏（问路远等）直接读。 */
object ImeFallback {
    var measuredPx by mutableIntStateOf(0)
}

/** 底部输入的键盘抬升（vc80）：单源取 max(ime insets, 实测)——insets 可信机型行为不变，
 *  vivo 上由实测抬升。等价 vc77 胶囊同款，收编成公共助手。 */
@Composable
fun imeLiftPadding(): Modifier = Modifier.padding(
    bottom = with(LocalDensity.current) {
        maxOf(WindowInsets.ime.getBottom(this), ImeFallback.measuredPx).toDp()
    }
)
