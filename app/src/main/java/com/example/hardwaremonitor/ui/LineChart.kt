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
import androidx.compose.ui.unit.dp

/** 上下界淡线的透明度（很低，仅"勾勒"作用） */
private const val BOUND_LINE_ALPHA = 0.25f
/** 占用阴影的透明度：均匀铺满折线下方，避免低占用时几乎不可见 */
private const val FILL_ALPHA = 0.28f
/** 图表内容左右缩进（dp），避免折线/边界/网格贴图左右边缘 */
private const val H_INSET_DP = 6f

/**
 * 轻量折线图：深灰网格（0/50/100%）+ 上下界淡线 + 彩色折线 + 折线下方的占用阴影。
 *
 * 上下界淡线与阴影都用模块色（低透明度）绘制：色相随防烧屏轮换一起旋转，
 * 且整体位于上下浮动 ±10px 的防烧屏容器内，不会成为固定不动的烧屏点。
 * [data] 中的 NaN 值表示缺数（如 NPU 不可用），该处断开折线与阴影。
 */
@Composable
fun LineChart(
    data: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    gridColor: Color = LocalHwmPalette.current.grid,
    boundColor: Color = color.copy(alpha = BOUND_LINE_ALPHA),
    fillAlpha: Float = FILL_ALPHA,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0) return@Canvas
        val left = H_INSET_DP.dp.toPx()
        val right = w - left
        val drawW = right - left

        // 网格线（含 0%/100% 位置），与内容统一左右缩进
        for (f in listOf(0f, 0.5f, 1f)) {
            val y = h * (1f - f)
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
        }

        // 上下界淡线：很淡地"勾"出 0%/100% 边界
        drawLine(boundColor, Offset(left, 0f), Offset(right, 0f), strokeWidth = 1.5f)
        drawLine(boundColor, Offset(left, h), Offset(right, h), strokeWidth = 1.5f)

        if (data.size < 2) return@Canvas
        val maxV = if (maxValue <= 0f) 1f else maxValue
        val step = drawW / (data.size - 1)

        // 占用阴影：沿折线向下闭合到图底，均匀低透明度铺满（各模块都可见）
        val fillPath = Path()
        var started = false
        var lastX = 0f
        fun closeSegment() {
            if (started) {
                fillPath.lineTo(lastX, h)
                fillPath.close()
                started = false
            }
        }
        data.forEachIndexed { i, v ->
            val x = left + i * step
            if (v.isNaN()) {
                closeSegment()
                return@forEachIndexed
            }
            val y = h * (1f - (v.coerceIn(0f, maxV) / maxV))
            if (!started) {
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
                started = true
            } else {
                fillPath.lineTo(x, y)
            }
            lastX = x
        }
        closeSegment()
        drawPath(fillPath, color.copy(alpha = fillAlpha))

        // 折线本体
        val linePath = Path()
        started = false
        data.forEachIndexed { i, v ->
            val x = left + i * step
            if (v.isNaN()) {
                started = false
                return@forEachIndexed
            }
            val y = h * (1f - (v.coerceIn(0f, maxV) / maxV))
            if (!started) {
                linePath.moveTo(x, y)
                started = true
            } else {
                linePath.lineTo(x, y)
            }
        }
        drawPath(linePath, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
