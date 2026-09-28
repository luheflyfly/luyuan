package com.luyuan.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.content.ContextCompat
import com.luyuan.R

/**
 * vc111 通知线·统一品牌件。
 * 真机实锤（2026-09-28，vivo V2509A / OriginOS 16 锁屏通知栏截图）：通知卡上的 App 图标
 * 被系统兜底成默认机器人——mipmap-anydpi-v26 自适应叶子图标桌面认，vivo 通知卡不解析它。
 * 修法=每条通知自带 largeIcon（运行时画的绿盘白叶位图），完全不依赖系统图标解析。
 * 全 App 通知构建统一挂 brandLargeIcon()；品牌色统一 BRAND_GREEN。
 */
object NotiStyle {

    /** 品牌深绿（与主题/桌面图标底同族） */
    const val BRAND_GREEN = 0xFF224A3A.toInt()

    @Volatile
    private var cached: Bitmap? = null

    /** 绿色渐变圆盘 + 白叶（ic_stat_luyuan 线条直接叠画）。进程内画一次缓存。 */
    fun brandLargeIcon(ctx: Context): Bitmap {
        cached?.let { return it }
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                0xFF2D5A48.toInt(), 0xFF1D3F31.toInt(), Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        try {
            ContextCompat.getDrawable(ctx, R.drawable.ic_stat_luyuan)?.let { leaf ->
                val inset = (size * 0.24f).toInt()
                leaf.setBounds(inset, inset, size - inset, size - inset)
                leaf.draw(canvas)
            }
        } catch (_: Throwable) {
        }
        cached = bmp
        return bmp
    }
}
