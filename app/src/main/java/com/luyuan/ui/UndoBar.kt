package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luyuan.ui.design.LuyuanPalette
import com.luyuan.ui.design.LuyuanRadius
import kotlinx.coroutines.delay

/**
 * 删除撤回条（vc127 重排）：软删不弹确认，先删再给 5 秒撤回。
 * 深绿胶囊 + 细分隔 + 两个文字动作，坐在底栏之上；倒计时结束自动提交。
 */
@Composable
fun UndoBar(
    message: String,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
    durationMs: Long = 5000,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        delay(durationMs)
        onCommit()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(LuyuanRadius.lg),
                clip = false,
                ambientColor = Color(0x33163026),
                spotColor = Color(0x33163026)
            )
            .clip(RoundedCornerShape(LuyuanRadius.lg))
            .background(LuyuanPalette.Green900)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            message,
            color = Color(0xFFE9F1EC),
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "撤回",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(LuyuanRadius.pill))
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
            Text(
                "立即删除",
                color = Color(0xFFF3B9B7),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(LuyuanRadius.pill))
                    .clickable(onClick = onCommit)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    }
}
