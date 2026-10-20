package com.luyuan.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 路远设计令牌唯一真源（vc127 · 2026-10-20）。
 *
 * 此前颜色散落 theme.kt / 页面写死、圆角 6/10/12/14/18/22/24 并存、字号 10~17sp 随手写、
 * 阴影从 6dp 到 18dp 不等——同一个 App 里像三个产品拼起来。本文件把「纸 / 墨绿 / 木案金」
 * 的口径收成一套（对齐 D:\Luyuan\DESIGN.md）：
 *
 *   纸底   paper     #F3EFE6   全 App 页面底
 *   瓷片   surface   #FBF8F1   卡片/输入框/抽屉面
 *   凹陷   sunken    #ECE7D9   表头/轨道/二级面
 *   墨绿   green800  #224A3A   主色（按钮、选中、品牌）
 *   木案金 gold      #B08D4F   一屏最多一处：今日 / 当前 / 大数字
 *
 * 规则：页面里不再出现裸色值、裸圆角；动效时长走 LuyuanMotion。
 */
object LuyuanPalette {
    // ---- 底与面 ----
    val Paper = Color(0xFFF3EFE6)
    val Surface = Color(0xFFFBF8F1)
    val SurfaceHigh = Color(0xFFFDFBF6)   // 浮起面（胶囊、抽屉）比卡片再亮半档
    val Sunken = Color(0xFFECE7D9)

    // ---- 墨绿家族 ----
    val Green900 = Color(0xFF163026)
    val Green800 = Color(0xFF224A3A)      // 身份主色
    val Green700 = Color(0xFF2D5A48)
    val Green600 = Color(0xFF2F7D5B)
    val Green500 = Color(0xFF4F8A73)
    val Green100 = Color(0xFFDDE9E1)
    val Green50 = Color(0xFFF0F5F1)

    // ---- 文字四级 ----
    val Ink1 = Color(0xFF1F2E25)
    val Ink2 = Color(0xFF58685F)
    val Ink3 = Color(0xFF8B978F)
    val Ink4 = Color(0xFFAEB7AF)

    // ---- 木案金（稀缺） ----
    val Gold = Color(0xFFB08D4F)
    val GoldSoft = Color(0x24B08D4F)      // rgba(176,141,79,.14)

    // ---- 线（主色透明版，禁纯灰实线） ----
    val Line = Color(0x1A163026)          // 10%
    val LineStrong = Color(0x33163026)    // 20%

    // ---- 语义色（主色 / 浅底） ----
    val Amber = Color(0xFFA9792C)
    val AmberBg = Color(0xFFF7ECD4)
    val Red = Color(0xFFB04A47)
    val RedBg = Color(0xFFF4E1DE)
    val Blue = Color(0xFF2F6088)
    val BlueBg = Color(0xFFDDE9F2)
    val Purple = Color(0xFF5E4A8E)
    val PurpleBg = Color(0xFFE9E4F3)
    val Pink = Color(0xFFA84471)
    val PinkBg = Color(0xFFF8E6EE)

    // 桌面小组件/旧引用兜底
    val WarmBg = Sunken
    val KeiwuGold = Gold
    val DividerStrong = Color(0xFFD8D2C0)
    val GradGreenStart = Green700
    val GradGreenEnd = Color(0xFF1D3F31)
}

/** 间距：4 的倍数。页面左右统一 16，卡内 16，行间 8。 */
object LuyuanSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val huge = 32.dp

    /** 页边距（手机） */
    val gutter = 16.dp

    /**
     * 页面底部留白：底栏 62 + 胶囊悬停区 74 + 呼吸 12 ≈ 148。
     * vc127 修：此前各屏写 80~96，最后一条会被悬浮胶囊压住。
     */
    val contentBottom = 148.dp
}

/** 圆角梯度：8 / 11 / 16 / 20 / 999，唯一合法四档。 */
object LuyuanRadius {
    val sm = 8.dp
    val md = 11.dp
    val lg = 16.dp
    val xl = 20.dp
    val pill = 999.dp

    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val xlShape = RoundedCornerShape(xl)
    val pillShape = RoundedCornerShape(pill)
}

/**
 * 字阶：一屏最多四级（页题 / 卡题 / 正文 / 辅助）。
 * 中文不用负字距；数字统一 tabular（见 LuyuanType.num）。
 */
object LuyuanType {
    /** 页面标题（笔记 / 待办 / 记账…） */
    val page = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp,
        fontWeight = FontWeight.Bold, color = LuyuanPalette.Ink1
    )

    /** 页面副题：日期、一句摘要 */
    val pageSub = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.Medium, color = LuyuanPalette.Ink3
    )

    /** 分段/小节标题（「未读 · 3」「已办」） */
    val section = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold, color = LuyuanPalette.Ink3,
        letterSpacing = 0.3.sp
    )

    /** 卡标题 / 列表标题 */
    val cardTitle = TextStyle(
        fontSize = 15.5.sp, lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold, color = LuyuanPalette.Ink1
    )

    val body = TextStyle(
        fontSize = 14.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Normal, color = LuyuanPalette.Ink1
    )

    val bodySmall = TextStyle(
        fontSize = 13.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Normal, color = LuyuanPalette.Ink2
    )

    val caption = TextStyle(
        fontSize = 11.5.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Normal, color = LuyuanPalette.Ink3
    )

    /** 数值（金额/计数）：等宽数字，防跳动 */
    val num = TextStyle(
        fontSize = 22.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.Bold, color = LuyuanPalette.Ink1
    )

    val numSmall = TextStyle(
        fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold, color = LuyuanPalette.Ink1
    )
}
