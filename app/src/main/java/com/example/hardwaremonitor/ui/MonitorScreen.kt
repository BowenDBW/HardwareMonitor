package com.example.hardwaremonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hardwaremonitor.data.Monitor
import com.example.hardwaremonitor.data.Sample

/** 单个面板的数据与配色，布局轮换时按排列组合重排渲染。 */
private data class PanelSpec(
    val label: String,
    val valueText: String,
    val color: Color,
    val data: List<Float>,
)

@Composable
fun MonitorScreen(monitor: Monitor) {
    val state by monitor.state.collectAsState()
    val history = state.history
    val latest = history.lastOrNull()

    // 防烧屏容器：10 分钟一个周期，轮换 4 布局 × 4 色相共 16 种组合（左右/上下对换等）
    BurnInProtection { layout ->
        val c = LocalHwmPalette.current
        val specs = listOf(
            PanelSpec(
                label = "CPU",
                valueText = latest?.let {
                    "%.0f%% · %s".format(it.cpuPct, it.cpuClustersText)
                } ?: "--",
                color = c.cpu,
                data = history.map { it.cpuPct },
            ),
            PanelSpec(
                label = "GPU",
                valueText = latest?.let {
                    "%.0f%% · %dMHz".format(it.gpuPct, it.gpuFreqMhz)
                } ?: "--",
                color = c.gpu,
                data = history.map { it.gpuPct },
            ),
            PanelSpec(
                label = "NPU",
                valueText = latest?.let { s -> s.npuPct?.let { "%.0f%%".format(it) } ?: "N/A" } ?: "--",
                color = c.npu,
                data = history.map { it.npuPct ?: Float.NaN },
            ),
            PanelSpec(
                label = "RAM",
                valueText = latest?.let {
                    "%.0f%% · %.1f/%.1fGB".format(it.ramPct, it.ramUsedGb, it.ramTotalGb)
                } ?: "--",
                color = c.ram,
                data = history.map { it.ramPct },
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(c.bg)
                .safeDrawingPadding()
        ) {
            InfoBar(latest, mirror = layout.mirror)
            // 上下对换：面板倒序排列
            val order = if (layout.reverse) listOf(3, 2, 1, 0) else listOf(0, 1, 2, 3)
            order.forEach { i ->
                val s = specs[i]
                Panel(
                    label = s.label,
                    valueText = s.valueText,
                    color = s.color,
                    data = s.data,
                    mirror = layout.mirror,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 顶部信息条：左/右镜像时 统计 ↔ 温度 换边。 */
@Composable
private fun InfoBar(sample: Sample?, mirror: Boolean) {
    val c = LocalHwmPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        val stats: @Composable () -> Unit = {
            Row {
                Stat(
                    label = "CPU",
                    pct = sample?.cpuPct,
                    freqText = sample?.cpuClusters?.firstOrNull { it.first == "X3" }
                        ?.let { (_, mhz) -> Sample.formatGhz(mhz) },
                    color = c.cpu,
                )
                Stat(
                    label = "GPU",
                    pct = sample?.gpuPct,
                    freqText = sample?.let { "%dM".format(it.gpuFreqMhz) },
                    color = c.gpu,
                )
                Stat(
                    label = "RAM",
                    pct = sample?.ramPct,
                    freqText = sample?.let { "%.1fG".format(it.ramUsedGb) },
                    color = c.ram,
                )
            }
        }
        val temps: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.End) {
                TempLine("CPU", sample?.tempCpu)
                TempLine("GPU", sample?.tempGpu)
                TempLine("BAT", sample?.tempBattery)
            }
        }
        if (mirror) {
            Box(Modifier.align(Alignment.TopEnd)) { stats() }
            Box(Modifier.align(Alignment.TopStart)) { temps() }
        } else {
            Box(Modifier.align(Alignment.TopStart)) { stats() }
            Box(Modifier.align(Alignment.TopEnd)) { temps() }
        }
    }
}

@Composable
private fun Stat(label: String, pct: Float?, freqText: String?, color: Color) {
    val c = LocalHwmPalette.current
    Column(Modifier.padding(end = 16.dp)) {
        Text(label, fontSize = 10.sp, color = c.textDim, fontWeight = FontWeight.Medium)
        Text(
            text = pct?.let { "${it.toInt()}%" } ?: "N/A",
            fontSize = 21.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = freqText ?: "--",
            fontSize = 9.sp,
            color = c.textDim,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun TempLine(label: String, value: Float?) {
    val c = LocalHwmPalette.current
    Text(
        text = if (value != null) "$label ${value.toInt()}°" else "$label --",
        fontSize = 10.sp,
        color = c.textDim,
        fontFamily = FontFamily.Monospace,
    )
}

/** 单个监控分区：彩色小圆点 + 标题 + 当前值文字 + 折线；[mirror] 时 圆点/标题 ↔ 数值 换边。 */
@Composable
private fun Panel(
    label: String,
    valueText: String,
    color: Color,
    data: List<Float>,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LocalHwmPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mirror) {
                Text(
                    valueText,
                    fontSize = 11.sp,
                    color = c.text,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    label,
                    fontSize = 12.sp,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .background(color, CircleShape)
                )
            } else {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(color, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    fontSize = 12.sp,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    valueText,
                    fontSize = 11.sp,
                    color = c.text,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        LineChart(
            data = data,
            maxValue = 100f,
            color = color,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
