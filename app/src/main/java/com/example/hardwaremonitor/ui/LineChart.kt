package com.example.hardwaremonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 轻量折线图：深灰网格（0/50/100%）+ 彩色折线。
 * [data] 中的 NaN 值表示缺数（如 NPU 不可用），该处断开折线。
 */
@Composable
fun LineChart(
    data: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    gridColor: Color = LocalHwmPalette.current.grid,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0) return@Canvas

        // 网格线
        for (f in listOf(0f, 0.5f, 1f)) {
            val y = h * (1f - f)
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        if (data.size < 2) return@Canvas
        val maxV = if (maxValue <= 0f) 1f else maxValue
        val step = w / (data.size - 1)
        val path = Path()
        var started = false
        data.forEachIndexed { i, v ->
            val x = i * step
            if (v.isNaN()) {
                started = false
                return@forEachIndexed
            }
            val y = h * (1f - (v.coerceIn(0f, maxV) / maxV))
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
