package com.teslalink.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslalink.app.ui.theme.TL
import com.teslalink.app.vm.DashboardViewModel

/** 时间序列折线：Canvas 手绘，无第三方依赖。带面积渐隐、网格与最新读数。 */
@Composable
fun LineChart(
    title: String,
    points: List<DashboardViewModel.Point>,
    color: Color,
    unit: String = "",
    modifier: Modifier = Modifier,
) {
    val last = points.lastOrNull()?.v
    val minValue = points.minOfOrNull { it.v }
    val maxValue = points.maxOfOrNull { it.v }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title.uppercase(), style = TL.eyebrow)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    last?.let { String.format("%.1f", it) } ?: "--",
                    style = TL.metric.copy(fontSize = 20.sp, lineHeight = 22.sp),
                )
                Text(unit.trim(), style = TL.eyebrow, color = TL.textLow)
            }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
        ) {
            // 三条网格线给出纵轴参照，不喧宾夺主。
            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 8f), 0f)
            listOf(0f, 0.5f, 1f).forEach { f ->
                val y = size.height * f
                drawLine(
                    TL.hairlineSoft,
                    Offset(0f, y),
                    Offset(size.width, y),
                    1f,
                    pathEffect = dash,
                )
            }
            if (points.size < 2) return@Canvas

            // 数据全量保留在 VM 里；画笔只取均匀步长（含首尾）——84dp 高度上视觉无差，
            // 长时间会话（几万个点）也不会把每帧重绘拖垮。
            val drawn: List<DashboardViewModel.Point> = if (points.size <= 1200) points else {
                val out = ArrayList<DashboardViewModel.Point>(1201)
                val step = (points.size - 1).toDouble() / 1199.0
                var lastIdx = -1
                for (i in 0 until 1200) {
                    val idx = (i * step).toInt()
                    if (idx != lastIdx) {
                        out.add(points[idx])
                        lastIdx = idx
                    }
                }
                if (out.last() != points.last()) out.add(points.last())
                out
            }

            val minX = drawn.first().t
            val spanX = (drawn.last().t - minX).coerceAtLeast(1L)
            val minY = drawn.minOf { it.v }
            val maxY = drawn.maxOf { it.v }
            val spanY = (maxY - minY).takeIf { it > 0.001f } ?: 1f
            val stepX = size.width / spanX
            fun x(t: Long) = (t - minX) * stepX
            fun y(v: Float) = size.height - ((v - minY) / spanY) * (size.height - 6f) - 3f

            val line = Path()
            drawn.forEachIndexed { i, p ->
                val px = x(p.t)
                val py = y(p.v)
                if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
            }
            // 面积渐隐：让趋势一眼可读，线本身保持细。
            val area = Path().apply { addPath(line) }
            area.lineTo(x(drawn.last().t), size.height)
            area.lineTo(x(drawn.first().t), size.height)
            area.close()
            drawPath(
                area,
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)),
            )
            drawPath(line, color = color, style = Stroke(width = 2.dp.toPx()))

            val end = drawn.last()
            drawCircle(color.copy(alpha = 0.25f), radius = 7f, center = Offset(x(end.t), y(end.v)))
            drawCircle(color, radius = 3f, center = Offset(x(end.t), y(end.v)))
        }

        Text(
            if (minValue != null && maxValue != null && points.size > 1) {
                "${points.size} 点 · 区间 ${String.format("%.1f", minValue)} ~ ${String.format("%.1f", maxValue)}$unit"
            } else {
                "等待采样…"
            },
            style = TL.mono.copy(fontSize = 10.sp, color = TL.textLow),
        )
    }
}
