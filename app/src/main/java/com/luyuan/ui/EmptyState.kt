package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luyuan.ui.design.LuyuanGhostButton
import com.luyuan.ui.design.LuyuanPalette

/**
 * 通用空状态（vc127 重排）：
 * 一枚浅绿圆托图标（不再是一枚裸 46dp 图标悬空），标题一句、说明一句、可选动作一枚。
 * 纪律：空状态必须回答「什么情况 / 放什么 / 点哪」，禁用「暂无数据」。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = LuyuanPalette.Green600,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.padding(vertical = 48.dp, horizontal = 32.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .background(LuyuanPalette.Green50, CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = LuyuanPalette.Ink1,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                fontSize = 13.sp,
                color = LuyuanPalette.Ink3,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            LuyuanGhostButton(text = actionLabel, onClick = onAction)
        }
    }
}

// 六场面图标速查（Material 矢量，禁 emoji；与 v2/icons.html 登记一致）
val EmptyIconNote = Icons.Default.NoteAdd
val EmptyIconSearch = Icons.Default.SearchOff
val EmptyIconLedger = Icons.Default.AccountBalanceWallet
val EmptyIconCourse = Icons.Default.CalendarMonth
val EmptyIconTrash = Icons.Default.DeleteSweep
val EmptyIconPeople = Icons.Default.People
