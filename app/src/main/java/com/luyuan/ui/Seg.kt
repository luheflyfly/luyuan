package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luyuan.ui.design.LuyuanPalette
import com.luyuan.ui.design.LuyuanRadius

/**
 * 全 App「文字分段签」单源（vc124 立，vc127 补触感）。
 * 选中 = 品牌绿实心白字；未选中 = 纸面底 + 发丝边；36dp 触区、按压 0.96、带 selected 语义。
 */
@Composable
fun LuyuanSeg(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressScale(interaction)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .graphicsLayer { scaleX = press; scaleY = press }
            .background(
                if (active) LuyuanPalette.Green800 else LuyuanPalette.Surface,
                LuyuanRadius.pillShape
            )
            .border(
                1.dp,
                if (active) LuyuanPalette.Green800 else LuyuanPalette.Line,
                LuyuanRadius.pillShape
            )
            .semantics {
                role = Role.Button
                selected = active
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) androidx.compose.ui.graphics.Color.White else LuyuanPalette.Ink2,
            maxLines = 1
        )
    }
}
