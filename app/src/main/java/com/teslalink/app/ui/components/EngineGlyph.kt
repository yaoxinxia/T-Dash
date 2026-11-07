package com.teslalink.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 发动机标：只用圆角矩形和圆手画的机器轮廓（缸体 + 缸盖凸起 + 进水口 + 输出轴 + 皮带轮）。
 * 刻意不借用任何厂商标识或车辆图形——它只表示"这是一台机器"。
 */
@Composable
fun EngineGlyph(color: Color, modifier: Modifier = Modifier, boxSize: Dp = 20.dp) {
    Canvas(modifier.size(boxSize)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.6.dp.toPx()
        fun rr(l: Float, t: Float, rw: Float, rh: Float, r: Float = 2.dp.toPx()) {
            drawRoundRect(
                color,
                Offset(l * w, t * h),
                Size(rw * w, rh * h),
                CornerRadius(r, r),
                style = Stroke(width = stroke),
            )
        }
        rr(0.16f, 0.38f, 0.52f, 0.42f)      // 缸体
        rr(0.26f, 0.20f, 0.28f, 0.18f)      // 缸盖凸起
        rr(0.02f, 0.46f, 0.14f, 0.14f)      // 左侧进水口
        rr(0.66f, 0.52f, 0.10f, 0.16f, 0f)  // 输出轴
        drawCircle(
            color,
            radius = 0.13f * w,
            center = Offset(0.82f * w, 0.60f * h),
            style = Stroke(width = stroke),
        )
    }
}
