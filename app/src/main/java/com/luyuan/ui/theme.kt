package com.luyuan.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color

// 手机 UI 方案 A「五键直达」主题（2026-09-08 路河拍板）：
// PC v20 同款设计 token——纸白 #faf7f0 基底 + 主深绿 #224a3a，旧蓝系全退（施工合同 §三）。
private val LightColors = lightColorScheme(
    primary = Color(0xFF224A3A),
    onPrimary = Color.White,
    secondary = Color(0xFF2D5A48),
    onSecondary = Color.White,
    background = Color(0xFFFAF7F0),
    onBackground = Color(0xFF1A3329),
    surface = Color.White,
    onSurface = Color(0xFF1A3329),
    surfaceVariant = Color(0xFFF6F3EB),
    onSurfaceVariant = Color(0xFF4A5A52),
    outline = Color(0xFFECE6D8),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFFB91C1C)
)

/** colorScheme 装不下的语义色（徽章/分类彩条/渐变），全 App 唯一定义处，禁止页内写死 */
object LuyuanColors {
    // 绿系（次级强调 / 头像底 / 统计卡渐变）
    val Green900 = Color(0xFF224A3A)
    val Green700 = Color(0xFF2D5A48)
    val Green500 = Color(0xFF4F8A73)
    val Green100 = Color(0xFFE3ECE6)
    val Green50 = Color(0xFFF1F5F2)

    // 正文灰阶
    val Ink1 = Color(0xFF1A3329)
    val Ink2 = Color(0xFF4A5A52)
    val Ink3 = Color(0xFF8A9690)
    val Ink4 = Color(0xFFB8C0BB)

    // 徽章口径（路河抽查项，两端必须一致）：语音=蓝 / 待办·记账=琥珀 / 写作=紫 / 购物·生日=粉 / 提醒=红
    val Amber = Color(0xFFB45309)
    val AmberBg = Color(0xFFFEF3C7)
    val Red = Color(0xFFDC2626)
    val RedBg = Color(0xFFFEE2E2)
    val Blue = Color(0xFF1D4ED8)
    val BlueBg = Color(0xFFDBEAFE)
    val Purple = Color(0xFF6D28D9)
    val PurpleBg = Color(0xFFEDE9FE)
    val Pink = Color(0xFFBE185D)
    val PinkBg = Color(0xFFFCE7F3)

    // 分隔线（强）
    val DividerStrong = Color(0xFFD8D2C0)

    // 暖灰底 / 课务木案金（2026-09-18 检查批收编：此前散落页内写死）
    val WarmBg = Color(0xFFF6F3EB)
    val KeiwuGold = Color(0xFFA8843A)

    // 深绿渐变（统计卡/下一节卡）
    val GradGreenStart = Color(0xFF2D5A48)
    val GradGreenEnd = Color(0xFF1D3F31)

    /** 记账分类彩条：餐饮琥珀 / 学习紫 / 日用绿 / 娱乐粉 / 其他灰（合同 §五-2） */
    fun categoryColor(category: String): Color = when (category) {
        "餐饮" -> Amber
        "学习" -> Purple
        "日用" -> Green500
        "娱乐" -> Pink
        else -> Ink3
    }

    fun categoryBg(category: String): Color = when (category) {
        "餐饮" -> AmberBg
        "学习" -> PurpleBg
        "日用" -> Green100
        "娱乐" -> PinkBg
        else -> Color(0xFFF6F3EB)
    }
}

@Composable
fun LuyuanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
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
