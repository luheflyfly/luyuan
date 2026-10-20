package com.luyuan.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 路远通用组件（vc127）。页面只拼这些件，不再各写一套卡/徽章/标题。
 */

// ---------------------------------------------------------------- 头部

/**
 * 页面头：大标题 + 可选副题 + 右侧动作；返回箭头只在 push 页出现。
 * 取代 M3 TopAppBar（64dp 高、22sp 标题、卡片色底）——那套在纸面上显得"浮"且吃高度。
 */
@Composable
fun LuyuanTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = LuyuanSpace.l, end = LuyuanSpace.s, top = LuyuanSpace.s, bottom = LuyuanSpace.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                LuyuanIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
                Spacer(Modifier.width(2.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = LuyuanType.page,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        subtitle,
                        style = LuyuanType.pageSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                content = actions
            )
        }
    }
}

/** 36dp 触区 / 18dp 图标的轻量动作钮（比 M3 IconButton 的 48dp 更省头部空间）。 */
@Composable
fun LuyuanIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = LuyuanPalette.Ink2,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else LuyuanPalette.Ink4,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

// ---------------------------------------------------------------- 小节

/** 小节标题：「未读 · 3」这类分组头。12sp 墨灰 + 细线延展，靠层级而不是卡片分隔。 */
@Composable
fun LuyuanSection(
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = LuyuanSpace.s, bottom = LuyuanSpace.xs)
    ) {
        Text(title, style = LuyuanType.section)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$count",
                style = LuyuanType.section.copy(
                    color = LuyuanPalette.Green700,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------- 卡

/**
 * 瓷片卡：纸底上一张更亮的纸，1dp 主色透明描边，无投影。
 * 只在"一块可点的内容"上用；纯列表靠留白分组，别每行都套卡。
 */
@Composable
fun LuyuanCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: Dp = LuyuanSpace.l,
    container: Color = LuyuanPalette.Surface,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = BorderStroke(
        1.dp,
        if (selected) LuyuanPalette.Green800 else LuyuanPalette.Line
    )
    val shape = LuyuanRadius.lgShape
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(if (selected) LuyuanPalette.Green50 else container, shape)
        .border(border, shape)
    Column(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base
    ) {
        Column(Modifier.padding(padding)) { content() }
    }
}

/** 浮起面：抽屉 / 胶囊 / 底部弹层。允许一层很淡的绿相投影。 */
@Composable
fun LuyuanFloatingSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = LuyuanRadius.xlShape,
    elevation: Dp = 12.dp,
    color: Color = LuyuanPalette.SurfaceHigh,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color(0x22163026),
                spotColor = Color(0x33163026)
            )
            .background(color, shape)
            .border(1.dp, LuyuanPalette.Line, shape)
    ) { content() }
}

// ---------------------------------------------------------------- 徽章

enum class LuyuanTone(val fg: Color, val bg: Color) {
    Voice(LuyuanPalette.Blue, LuyuanPalette.BlueBg),
    Manual(LuyuanPalette.Green700, LuyuanPalette.Green100),
    Todo(LuyuanPalette.Amber, LuyuanPalette.AmberBg),
    Write(LuyuanPalette.Purple, LuyuanPalette.PurpleBg),
    Shop(LuyuanPalette.Pink, LuyuanPalette.PinkBg),
    Alert(LuyuanPalette.Red, LuyuanPalette.RedBg),
    Gold(LuyuanPalette.Gold, LuyuanPalette.GoldSoft),
    Neutral(LuyuanPalette.Ink2, LuyuanPalette.Sunken)
}

@Composable
fun LuyuanBadge(
    text: String,
    tone: LuyuanTone = LuyuanTone.Neutral,
    modifier: Modifier = Modifier
) {
    Text(
        text,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = tone.fg,
        maxLines = 1,
        modifier = modifier
            .background(tone.bg, RoundedCornerShape(LuyuanRadius.pill))
            .padding(horizontal = 7.dp, vertical = 2.5.dp)
    )
}

/** 极简徽章：只给色点 + 文案，用于行内次要来源标记，别让每张卡挂满药丸。 */
@Composable
fun LuyuanDotLabel(text: String, tone: LuyuanTone, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            Modifier
                .size(5.dp)
                .background(tone.fg, CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = LuyuanType.caption, color = LuyuanPalette.Ink3, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 按钮

@Composable
fun LuyuanPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(LuyuanRadius.pillShape)
            .background(
                if (enabled) Brush.verticalGradient(
                    listOf(LuyuanPalette.Green700, LuyuanPalette.Green900)
                ) else Brush.verticalGradient(
                    listOf(LuyuanPalette.Ink4, LuyuanPalette.Ink4)
                ),
                LuyuanRadius.pillShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun LuyuanGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Color = LuyuanPalette.Ink2
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(LuyuanRadius.pillShape)
            .border(1.dp, LuyuanPalette.LineStrong, LuyuanRadius.pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = tone, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 文字动作（"全部已读"这类），触区补到 36dp。 */
@Composable
fun LuyuanTextAction(
    text: String,
    onClick: () -> Unit,
    tone: Color = LuyuanPalette.Green700,
    modifier: Modifier = Modifier
) {
    Text(
        text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = tone,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(LuyuanRadius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

// ---------------------------------------------------------------- 表单行

@Composable
fun LuyuanSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = LuyuanSpace.l, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LuyuanType.body, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, style = LuyuanType.caption)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LuyuanPalette.Green800,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = LuyuanPalette.Ink4,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** 细分隔线（主色 10%），比 M3 HorizontalDivider 更轻。 */
@Composable
fun LuyuanHairline(modifier: Modifier = Modifier) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LuyuanPalette.Line)
            .then(modifier)
    )
}

/** 空状态的动作钮（可选）。 */
@Composable
fun LuyuanEmptyAction(text: String, onClick: () -> Unit) {
    Spacer(Modifier.height(14.dp))
    LuyuanGhostButton(text = text, onClick = onClick)
}

/** 统一文本风格助手：数值等宽（部分 ROM 的默认字体会让金额跳动）。 */
fun TextStyle.tabular(): TextStyle = copy(
    fontFeatureSettings = "tnum"
)

/** 供页面内部使用的轻量高亮底（今日/当前），金色只在这里出现。 */
@Composable
fun LuyuanTodayWash(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(LuyuanRadius.md))
            .background(LuyuanPalette.GoldSoft)
    )
}

/** 调试/兼容：给旧代码一个"纸面"Surface 底色。 */
@Composable
fun LuyuanPaperSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier) { content() }
}
