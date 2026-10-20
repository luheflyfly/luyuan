package com.luyuan.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.luyuan.ui.LuyuanMotion
import com.luyuan.ui.design.LuyuanPalette
import com.luyuan.ui.design.LuyuanRadius
import kotlinx.coroutines.flow.collect
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings

/**
 * 左侧两级抽屉（vc127 重写）。
 *
 * 修的旧病：
 * - 旧实现只给了 enter，没有 exit —— 关抽屉走 AnimatedVisibility 默认的 shrinkVertically+fade，
 *   面板一边缩小一边淡出，像被吸走；现在明确 250ms 向左滑出。
 * - 旧遮罩是 32% 纯黑（#52000000），DESIGN 明令深绿遮罩；现在 Green900 45%。
 * - 旧实现不管「减少动画」，双层抽屉也没有层级感；现在系统关动画时瞬开瞬关。
 * - 面板：右圆角 20、绿相投影、纸面 #FBF8F1；设置层比问路远层再宽一点并压深一档，
 *   让「第二层盖在第一层上」一眼可见。
 */
@Composable
fun LuyuanDrawerHost(
    askOpen: Boolean,
    settingsOpen: Boolean,
    onDismiss: () -> Unit,
    onDismissSettings: () -> Unit,
    askContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    // 不能提前 return：提前卸载会让 AnimatedVisibility 的 exit 动画失效（抽屉瞬间消失）。
    // 常驻一个空 Box 的成本可以忽略，面板内容只在 visible 时组合。
    val anyOpen = askOpen || settingsOpen
    val reduce = rememberReduceMotionCompat()
    val scrim = LuyuanPalette.Green900.copy(alpha = 0.45f)

    Box(Modifier.fillMaxSize()) {
        // 遮罩：点空白 = 关最上层
        AnimatedVisibility(
            visible = anyOpen,
            enter = fadeIn(tween(if (reduce) 0 else 250)),
            exit = fadeOut(tween(if (reduce) 0 else 180))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (settingsOpen) onDismissSettings() else onDismiss()
                    }
            )
        }

        // 一层：问路远
        AnimatedVisibility(
            visible = askOpen,
            enter = slideInHorizontally(
                animationSpec = tween(if (reduce) 0 else 320, easing = LuyuanMotion.Spring),
                initialOffsetX = { -it }
            ) + fadeIn(tween(if (reduce) 0 else 160)),
            exit = slideOutHorizontally(
                animationSpec = tween(if (reduce) 0 else 240, easing = LuyuanMotion.Flow),
                targetOffsetX = { -it }
            ) + fadeOut(tween(if (reduce) 0 else 160)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(0.86f)
        ) {
            DrawerPanel(widthFraction = 1f) { askContent() }
        }

        // 二层：设置（更宽，叠在问路远上；露一条边让人知道下面还有一层）
        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInHorizontally(
                animationSpec = tween(if (reduce) 0 else 320, easing = LuyuanMotion.Spring),
                initialOffsetX = { -it }
            ) + fadeIn(tween(if (reduce) 0 else 160)),
            exit = slideOutHorizontally(
                animationSpec = tween(if (reduce) 0 else 240, easing = LuyuanMotion.Flow),
                targetOffsetX = { -it }
            ) + fadeOut(tween(if (reduce) 0 else 160)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(0.92f)
        ) {
            DrawerPanel(widthFraction = 1f) { settingsContent() }
        }
    }
}

@Composable
private fun DrawerPanel(widthFraction: Float, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(topEnd = LuyuanRadius.xl, bottomEnd = LuyuanRadius.xl),
                clip = false,
                ambientColor = Color(0x33163026),
                spotColor = Color(0x55163026)
            )
            .clip(RoundedCornerShape(topEnd = LuyuanRadius.xl, bottomEnd = LuyuanRadius.xl))
            .background(LuyuanPalette.Surface)
    ) {
        // 抽屉内页面自带状态栏留白（各屏自己的 Scaffold），这里不再加
        content()
    }
}

/**
 * 读系统动画开关（Settings.Global.ANIMATOR_DURATION_SCALE）。
 * 与 ui/motion.kt 的 rememberReduceMotion 同源，但抽屉在 shell 层，避免循环依赖直接内联。
 */
@Composable
private fun rememberReduceMotionCompat(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            Settings.Global.getFloat(
                ctx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/** 抽屉打开时：主内容左移 + 压暗（整块壳一起动，底栏/胶囊不会留在原地发亮）。 */
object ShellShift {
    const val AskDx = 26f
    const val SettingsDx = 14f
    const val Dim = 0.55f
}
