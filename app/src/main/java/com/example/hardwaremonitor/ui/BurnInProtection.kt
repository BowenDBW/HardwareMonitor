package com.example.hardwaremonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---- 防烧屏可调参数（OLED 24h 常亮用）-------------------------------------

/** 一个完整周期时长：10 分钟，把「布局 × 色相」全部组合恰好轮一遍 */
const val BURN_IN_CYCLE_MS = 10 * 60 * 1000L

/**
 * 布局变体：左右对换（信息条 统计↔温度、面板 圆点+标题↔数值 换边）
 * 与上下对换（面板倒序）的排列组合。
 */
data class BurnInLayout(val mirror: Boolean, val reverse: Boolean)

private val LAYOUTS = listOf(
    BurnInLayout(mirror = false, reverse = false), // 原样
    BurnInLayout(mirror = true,  reverse = false), // 仅左右对换
    BurnInLayout(mirror = false, reverse = true),  // 仅上下对换
    BurnInLayout(mirror = true,  reverse = true),  // 左右 + 上下同时对换
)

/** 色相档位数：每个布局都要配满这 4 档色相，保证组合不重复、全部轮一遍 */
private const val HUE_COUNT = 4
/** 相邻色相间隔 90°：4 档 = 0° / 90° / 180° / 270° */
private const val HUE_STEP_DEG = 360f / HUE_COUNT
/** 组合总数 = 布局数 × 色相数 = 16 */
private val COMBO_COUNT = LAYOUTS.size * HUE_COUNT
/** 每个组合停留时长 = 10 分钟 ÷ 16 ≈ 37.5 秒 */
private val STEP_MS = BURN_IN_CYCLE_MS / COMBO_COUNT
/** 每个组合再叠加 2px 像素位移（循环 4 个角），进一步摊平亚像素损耗 */
private const val SHIFT_PX = 2

private val PIXEL_SHIFTS = listOf(
    IntOffset(0, 0),
    IntOffset(SHIFT_PX, 0),
    IntOffset(SHIFT_PX, SHIFT_PX),
    IntOffset(0, SHIFT_PX),
)

/** 背景是否用纯黑：OLED 纯黑像素完全熄灭、零损耗；设 false 恢复原暗灰背景 */
const val PURE_BLACK_BACKGROUND = true

/** 内部时钟节拍：秒级即可，画面本身每 500ms 就在重绘 */
private const val TICK_MS = 1000L
/** 内容四周预留边距，保证像素位移时边缘不漏底（位移最大 2px） */
private val BURN_IN_PAD = 4.dp

/** 当前主题配色；强调色随 [hueDeg] 旋转，静止色（灰/白/纯黑）保持不变。 */
data class HwmPalette(
    val bg: Color,
    val grid: Color,
    val text: Color,
    val textDim: Color,
    val cpu: Color,
    val gpu: Color,
    val npu: Color,
    val ram: Color,
)

val LocalHwmPalette = staticCompositionLocalOf { defaultPalette() }

fun defaultPalette(hueDeg: Float = 0f): HwmPalette = HwmPalette(
    bg = if (PURE_BLACK_BACKGROUND) Color.Black else Color(0xFF0E1116),
    grid = Color(0xFF262B34),
    text = Color(0xFFE6E9EF),
    textDim = Color(0xFF8B93A3),
    cpu = rotateHue(Color(0xFF34D399), hueDeg), // 绿
    gpu = rotateHue(Color(0xFF22D3EE), hueDeg), // 青
    npu = rotateHue(Color(0xFFF472B6), hueDeg), // 品红
    ram = rotateHue(Color(0xFFFBBF24), hueDeg), // 琥珀
)

/** 在 sRGB 空间按 HSL 旋转色相（保持饱和度/亮度），灰/白等无色相颜色原样返回。 */
fun rotateHue(color: Color, degrees: Float): Color {
    if (degrees == 0f) return color
    val r = color.red; val g = color.green; val b = color.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return color // 无色相
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    var h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    h = (h / 6f + degrees / 360f) % 1f
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f), color.alpha)
}

/**
 * 防烧屏容器：给 24h 常亮监控页套上，防止 OLED 留残影。
 *
 * 一个 [BURN_IN_CYCLE_MS]（10 分钟）周期 = 4 种布局 × 4 档色相共 16 个组合，
 * 每 [STEP_MS]（≈37.5s）换一个；每个「布局 × 色相」组合在整个周期内只出现一次，
 * 全部恰好轮一遍。每档再叠加 2px 像素位移，摊平亚像素损耗。
 *
 * 背景固定为纯黑：OLED 黑色像素完全关闭，不产生损耗，因此背景不参与轮换。
 */
@Composable
fun BurnInProtection(content: @Composable (BurnInLayout) -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    val combo = ((now / STEP_MS) % COMBO_COUNT).toInt()
    // 组合索引 → (布局, 色相)：布局每 4 档换一个，色相每档换一档 → 16 组合恰好各一次
    val layout = LAYOUTS[combo / HUE_COUNT]
    val hueDeg = (combo % HUE_COUNT) * HUE_STEP_DEG
    val shift = PIXEL_SHIFTS[combo % PIXEL_SHIFTS.size]
    val palette = remember(hueDeg) { defaultPalette(hueDeg) }
    CompositionLocalProvider(LocalHwmPalette provides palette) {
        Box(Modifier.fillMaxSize().background(palette.bg)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(BURN_IN_PAD)
                    .offset { shift }
            ) {
                content(layout)
            }
        }
    }
}
