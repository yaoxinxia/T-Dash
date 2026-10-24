package com.teslalink.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslalink.app.ui.theme.TL
import kotlin.math.cos
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f

/**
 * 270° 弧形表（135°..405°）：数字仪表风格——细弧 + 端点标记 + 外发光，
 * 不再画指针和密集刻度，读数交给居中的大数字。
 *
 * zeroBased = true 时以 0 为基准：正值从零位向弧的末端长、负值从零位向起点长
 * （功率表的动能回收就该这样——回收越大越长，而不是整个量程的又一个刻度）。
 */
@Composable
fun ArcGauge(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    digits: Int = 0,
    color: Color = TL.textHi,
    zeroBased: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val zeroFrac = ((0f - min) / (max - min)).coerceIn(0f, 1f)
    val positiveFrac = (value / max).coerceIn(0f, 1f)
    val negativeFrac = (value / min).coerceIn(0f, 1f)
    Box(modifier.aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.042f
            val radius = (size.minDimension - stroke * 3f) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawArc(
                color = TL.hairline,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val (arcStart, arcSweep) = if (zeroBased && zeroFrac in 0f..1f && min < 0f) {
                if (value >= 0f) {
                    (START_ANGLE + SWEEP_ANGLE * zeroFrac) to (SWEEP_ANGLE * (1f - zeroFrac) * positiveFrac)
                } else {
                    val sweep = SWEEP_ANGLE * zeroFrac * negativeFrac
                    (START_ANGLE + SWEEP_ANGLE * zeroFrac - sweep) to sweep
                }
            } else {
                START_ANGLE to (SWEEP_ANGLE * fraction)
            }
            if (arcSweep > 0f) {
                // 先铺一层柔光，再画清晰弧，避免生硬的高饱和边。
                drawArc(
                    color = color.copy(alpha = 0.16f),
                    startAngle = arcStart,
                    sweepAngle = arcSweep,
                    useCenter = false,
                    style = Stroke(stroke * 2.8f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = arcStart,
                    sweepAngle = arcSweep,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val angle = Math.toRadians((arcStart + arcSweep).toDouble())
                val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawCircle(TL.ink, radius = stroke * 0.9f, center = center + dir * radius)
                drawCircle(color, radius = stroke * 0.45f, center = center + dir * radius)
            }
            // 零点/满点（以及零基准模式的零位）刻度点，给出量程感而不制造噪声。
            val marks = buildList {
                add(0f)
                add(1f)
                if (zeroBased && zeroFrac in 0.001f..0.999f) add(zeroFrac)
            }
            marks.forEach { mark ->
                val a = Math.toRadians((START_ANGLE + SWEEP_ANGLE * mark).toDouble())
                val d = Offset(cos(a).toFloat(), sin(a).toFloat())
                drawCircle(
                    TL.textLow,
                    radius = 2f,
                    center = center + d * (radius + stroke * 1.6f),
                )
            }
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                String.format("%.${digits}f", value),
                style = TL.metric.copy(fontSize = 30.sp, color = color),
                textAlign = TextAlign.Center,
            )
            Text(unit.uppercase(), style = TL.eyebrow)
            Text(label, style = TL.mono.copy(fontSize = 10.sp, color = TL.textLow))
        }
    }
}

/** 270° 进度环：渐变描边，中心内容由调用方给。 */
@Composable
fun RingGauge(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.055f
            val radius = (size.minDimension - stroke * 2.4f) / 2f
            drawArc(
                color = TL.hairline,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f) {
                drawArc(
                    color = color.copy(alpha = 0.14f),
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * p,
                    useCenter = false,
                    style = Stroke(stroke * 2.4f, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.45f), color), center = Offset(size.width / 2f, size.height / 2f)),
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * p,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}
