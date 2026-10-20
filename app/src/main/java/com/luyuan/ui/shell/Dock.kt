package com.luyuan.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luyuan.ui.LuyuanMotion
import com.luyuan.ui.design.LuyuanPalette
import com.luyuan.ui.design.LuyuanRadius
import com.luyuan.ui.design.LuyuanSpace
import com.luyuan.ui.design.LuyuanType
import com.luyuan.ui.rememberPressScale

/** 底栏数据（页面槽位 + 文案 + 图标 + 角标）。 */
data class DockItem(
    val page: Int,
    val label: String,
    val icon: ImageVector,
    val badge: Int = 0
)

object LuyuanDock {
    /** 含系统导航条留白在内的视觉高度基准，胶囊/列表底部留白据此计算。 */
    val contentHeight = 60.dp
}

/**
 * 底栏（vc127 重写，替代 M3 NavigationBar）。
 *
 * 为什么换掉：
 * - M3 NavigationBar 每项 80dp 高、选中态是"上面一块浅色胶囊下面一坨文字"，
 *   在 6~7 个页签时会显得又高又碎；纸面气质也被它的 surface 容器色破坏。
 * - 这里做"纸上一排墨点"：无容器底色、只有顶部一根发丝线；选中项 = 浅绿药丸托着图标，
 *   字号 10sp 全部常显（不做只有选中才出字，避免切换时文字跳动）。
 * - 设置是动作不是页签：单独一枚 36dp 图标 + 左侧细线，和页签区分开。
 */
@Composable
fun LuyuanDock(
    items: List<DockItem>,
    selectedPageProvider: () -> Int,
    onSelect: (Int) -> Unit,
    onSettings: () -> Unit,
    settingsSelected: Boolean = false
) {
    // 页码在底栏自己的组合作用域里读：切页只重组底栏 8 个小项，
    // 不会像旧实现那样把整个 AppRoot（含 Pager 六屏）拖下水（vc123 的教训）。
    val selectedPage = selectedPageProvider()
    Column(
        Modifier
            .fillMaxWidth()
            .background(LuyuanPalette.Paper)
    ) {
        // 发丝顶线：让底栏"贴"在纸面上，而不是漂着
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LuyuanPalette.Line)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp)
                .height(LuyuanDock.contentHeight)
        ) {
            for (item in items) {
                DockTab(
                    item = item,
                    selected = !settingsSelected && item.page == selectedPage,
                    onClick = { onSelect(item.page) },
                    modifier = Modifier.weight(1f)
                )
            }
            // 设置：动作入口，和页签之间留一道细线
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .width(1.dp)
                    .height(18.dp)
                    .background(LuyuanPalette.Line)
            )
            SettingsTab(selected = settingsSelected, onClick = onSettings)
        }
    }
}

@Composable
private fun RowScope.DockTab(
    item: DockItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressScale(interaction)
    val iconTint by animateColorAsState(
        if (selected) LuyuanPalette.Green800 else LuyuanPalette.Ink3,
        tween(LuyuanMotion.Fast), label = "dockIcon"
    )
    val labelColor by animateColorAsState(
        if (selected) LuyuanPalette.Green800 else LuyuanPalette.Ink3,
        tween(LuyuanMotion.Fast), label = "dockLabel"
    )
    val pill by animateColorAsState(
        if (selected) LuyuanPalette.Green100 else Color.Transparent,
        tween(LuyuanMotion.Fast), label = "dockPill"
    )
    val lift by animateFloatAsState(if (selected) 1f else 0f, tween(LuyuanMotion.Move), label = "dockLift")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(LuyuanDock.contentHeight)
            .graphicsLayer { scaleX = press; scaleY = press }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(38.dp)
                    .height(25.dp)
                    .background(pill, RoundedCornerShape(LuyuanRadius.pill))
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    tint = iconTint,
                    modifier = Modifier
                        .size(21.dp)
                        .graphicsLayer { translationY = -1f * lift }
                )
            }
            if (item.badge > 0) {
                BadgeDot(item.badge, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-2).dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            item.label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun SettingsTab(selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressScale(interaction)
    val tint by animateColorAsState(
        if (selected) LuyuanPalette.Green800 else LuyuanPalette.Ink3,
        tween(LuyuanMotion.Fast), label = "gear"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(40.dp)
            .height(LuyuanDock.contentHeight)
            .graphicsLayer { scaleX = press; scaleY = press }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Icon(Icons.Default.Settings, contentDescription = "设置", tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** 角标：14dp 红点托白字；数字超过 9 显示 9+，避免把图标挤歪。 */
@Composable
private fun BadgeDot(count: Int, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(15.dp)
            .shadow(2.dp, CircleShape, clip = false, ambientColor = Color(0x22163026), spotColor = Color(0x22163026))
            .background(LuyuanPalette.Red, CircleShape)
    ) {
        Text(
            if (count > 9) "9+" else "$count",
            color = Color.White,
            fontSize = 8.5.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** 胶囊悬停区高度：底栏 + 悬浮胶囊 + 呼吸。页面列表底部留白统一用它。 */
val LuyuanContentBottom: Dp = LuyuanSpace.contentBottom

@Composable
fun DockPreviewSpacer() {
    Spacer(Modifier.height(LuyuanContentBottom))
}

/** 空实现占位，保证 import 侧稳定（后续 dock 若加"更多"面板在此扩展）。 */
@Suppress("unused")
private fun unusedPlaceholder(): Int = 0

