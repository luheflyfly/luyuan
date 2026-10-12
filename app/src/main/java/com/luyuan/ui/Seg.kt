package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * vc124 视觉对齐：全 App「文字分段签」单源。
 * 此前七处自造（待办三签/学业五签/速查四签/人脉分组/记账月份/录音模式/学业视图卡），
 * 选中态三种逻辑、字号 12/12.5/默认、padding 各一套、还混着两抹绿（primary vs Green700）。
 *
 * 统一规格（取学业页 SegTabs 主形态——信息层级最清楚）：
 * 选中 = 品牌绿实心白字；未选中 = 纸面底+outline 细边；字 12.5sp Bold；全圆角 pill；padding 15/7。
 * 带图标的大块「视图卡」（CourseScreen.ModeChip）形态不同，不并入本组件。
 */
@Composable
fun LuyuanSeg(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        color = if (active) Color.White else LuyuanColors.Ink2,
        modifier = Modifier
            .background(
                if (active) LuyuanColors.Green700 else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(999.dp)
            )
            .border(
                1.dp,
                if (active) LuyuanColors.Green700 else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 7.dp)
    )
}
