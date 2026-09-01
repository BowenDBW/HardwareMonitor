package com.example.hardwaremonitor.data

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/** 单次轮询采样结果。npuPct / 温度 为 null 表示该数据源不可用（如 NPU 无节点）。 */
data class Sample(
    val cpuPct: Float,
    /** 每核当前频率 MHz（离线核为 0） */
    val cpuFreqMhz: List<Int>,
    /** 每个 CPU 簇的 (标签, 簇内在线核最大频率 MHz) */
    val cpuClusters: List<Pair<String, Int>>,
    val gpuPct: Float,
    val gpuFreqMhz: Int,
    val gpuMaxMhz: Int,
    val npuPct: Float?,
    val ramPct: Float,
    val ramUsedGb: Float,
    val ramTotalGb: Float,
    val tempCpu: Float?,
    val tempGpu: Float?,
    val tempBattery: Float?,
) {
    /** 簇频率文字，如 "X3 3.2G · A715 2.8G · A510 1.5G" */
    val cpuClustersText: String
        get() = cpuClusters.joinToString(" · ") { (label, mhz) ->
            "$label ${formatGhz(mhz)}"
        }

    companion object {
        fun formatGhz(mhz: Int): String =
            if (mhz <= 0) "0G" else String.format("%.1fG", mhz / 1000f)
    }
}

data class MonitorState(
    val history: List<Sample> = emptyList(),
    val error: String? = null,
)

/**
 * 500ms 轮询监控器：CPU%/每核频率/GPU/NPU/RAM/温度，维护 120 点（约 60s）ring buffer，
 * 通过 [state] StateFlow 暴露给 UI。仅前台运行，由 MainActivity 在 onStart/onStop 启停。
 */
class Monitor(private val context: Context) {

    private val TAG = "HWM"
    private val sysfs = SysfsReader()
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val history = ArrayDeque<Sample>(HISTORY_LEN)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    // 每核频率节点（discover 后构建）
    private var freqPaths = emptyList<String>()
    private var maxFreqPaths = emptyList<String>()

    // CPU 簇划分：按 max_freq 识别 X3(>3GHz) / A715(2.5-3GHz) / A510(<2.5GHz)
    private var clusters: List<Pair<String, List<Int>>> = listOf("X3" to emptyList(), "A715" to emptyList(), "A510" to emptyList())

    // /proc/stat 基线
    private var prevStat: LongArray? = null

    fun start() {
        if (job != null) return
        sysfs.discover()
        buildCorePaths()
        classifyClusters()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        prevStat = readProcStat() // 建立 CPU% 基线
        job = s.launch {
            while (isActive) {
                val t0 = System.currentTimeMillis()
                try {
                    val sample = sampleNow()
                    synchronized(history) {
                        history.addLast(sample)
                        while (history.size > HISTORY_LEN) history.removeFirst()
                    }
                    _state.value = MonitorState(history.toList())
                    val dt = System.currentTimeMillis() - t0
                    delay((POLL_MS - dt).coerceAtLeast(50L))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "tick failed", e)
                    _state.value = _state.value.copy(error = e.message)
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
    }

    private fun buildCorePaths() {
        val n = sysfs.cpuCores.size
        freqPaths = List(n) { i -> "/sys/devices/system/cpu/cpu${sysfs.cpuCores[i]}/cpufreq/scaling_cur_freq" }
        maxFreqPaths = List(n) { i -> "/sys/devices/system/cpu/cpu${sysfs.cpuCores[i]}/cpufreq/scaling_max_freq" }
    }

    private fun classifyClusters() {
        val n = sysfs.cpuCores.size
        val maxFreqs = sysfs.readMany(maxFreqPaths)
        val groups = linkedMapOf("X3" to mutableListOf<Int>(), "A715" to mutableListOf<Int>(), "A510" to mutableListOf<Int>())
        for (i in 0 until n) {
            val khz = maxFreqs[maxFreqPaths[i]]?.toLongOrNull() ?: 0L
            val ghz = khz / 1_000_000.0
            val label = when {
                ghz >= 3.0 -> "X3"
                ghz >= 2.5 -> "A715"
                else -> "A510"
            }
            groups.getValue(label).add(i)
        }
        clusters = groups.map { (label, cores) -> label to cores }
        Log.i(TAG, "clusters = ${clusters.joinToString(" | ") { (l, c) -> "$l:${c.joinToString(",")}" }}")
    }

    private fun sampleNow(): Sample {
        // 频率 + 各 thermal zone 的 temp 文件（readTemps 需要）
        val reads = sysfs.readMany(freqPaths + maxFreqPaths + sysfs.thermalZones.map { it.first })
        val n = sysfs.cpuCores.size
        val freq = IntArray(n)
        val maxFreq = IntArray(n)
        for (i in 0 until n) {
            freq[i] = (reads[freqPaths[i]]?.toLongOrNull() ?: 0L).div(1000).toInt()       // kHz -> MHz
            maxFreq[i] = (reads[maxFreqPaths[i]]?.toLongOrNull() ?: 0L).div(1000).toInt() // kHz -> MHz
        }
        val clusterMax = clusters.map { (label, cores) ->
            label to (cores.maxOfOrNull { freq[it] } ?: 0)
        }

        val cpuPct = cpuUsagePct()
        val gpuPct = readGpuPct()
        val gpuClk = reads[sysfs.gpuClkPath ?: ""]?.toLongOrNull()?.div(1_000_000)?.toInt() ?: 0
        val gpuMax = reads[sysfs.gpuMaxClkPath ?: ""]?.toLongOrNull()?.div(1_000_000)?.toInt() ?: 0

        val npu = readNpuPct()

        val (ramPct, usedGb, totalGb) = readRam()
        val (tCpu, tGpu, tBattery) = readTemps(reads)

        return Sample(
            cpuPct = cpuPct,
            cpuFreqMhz = freq.toList(),
            cpuClusters = clusterMax,
            gpuPct = gpuPct,
            gpuFreqMhz = gpuClk,
            gpuMaxMhz = gpuMax,
            npuPct = npu,
            ramPct = ramPct,
            ramUsedGb = usedGb,
            ramTotalGb = totalGb,
            tempCpu = tCpu,
            tempGpu = tGpu,
            tempBattery = tBattery,
        )
    }

    // ---- CPU 占用（/proc/stat 两次采样 delta）----
    private fun readProcStat(): LongArray? = try {
        val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return null
        line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.toLongArray()
    } catch (e: Exception) {
        null
    }

    private fun cpuUsagePct(): Float {
        val cur = readProcStat() ?: return 0f
        val prev = prevStat
        prevStat = cur
        if (prev == null || prev.size < 4 || cur.size < 4) return 0f
        val dTotal = cur.sum() - prev.sum()
        val dIdle = (cur[3] + (cur.getOrNull(4) ?: 0L)) - (prev[3] + (prev.getOrNull(4) ?: 0L))
        if (dTotal <= 0) return 0f
        return (1f - dIdle.toFloat() / dTotal) * 100f
    }

    // ---- GPU ----
    private fun readGpuPct(): Float {
        val path = sysfs.gpuBusyPath ?: return 0f
        val raw = sysfs.read(path) ?: return 0f
        val nums = Regex("""\d+""").findAll(raw).map { it.value.toLong() }.toList()
        return when {
            path.endsWith("gpubusy") && nums.size >= 2 && nums[0] + nums[1] > 0 ->
                nums[0].toFloat() * 100f / (nums[0] + nums[1])
            nums.isNotEmpty() -> nums[0].toFloat()
            else -> 0f
        }.coerceIn(0f, 100f)
    }

    // ---- NPU：找到节点且能解析为 0..100 才显示数值，否则 N/A ----
    private fun readNpuPct(): Float? {
        val p = sysfs.npuPath ?: return null
        val raw = sysfs.read(p) ?: return null
        val v = raw.toFloatOrNull() ?: return null
        if (v < 0f || v > 100f) return null
        return v
    }

    // ---- RAM（免 root）----
    private fun readRam(): Triple<Float, Float, Float> {
        val mi = ActivityManager.MemoryInfo()
        try {
            am.getMemoryInfo(mi)
        } catch (e: Exception) {
            return Triple(0f, 0f, 0f)
        }
        val total = mi.totalMem.toDouble()
        val used = (mi.totalMem - mi.availMem).toDouble()
        val pct = if (total > 0) (used / total * 100).toFloat() else 0f
        val gb = 1024f * 1024f * 1024f
        return Triple(pct, (used / gb).toFloat(), (total / gb).toFloat())
    }

    // ---- 温度：按 zone type 前缀分类，取各类最高温（原始值毫摄氏度 ÷1000）----
    private fun readTemps(reads: Map<String, String>): Triple<Float?, Float?, Float?> {
        var cpuT: Float? = null
        var gpuT: Float? = null
        var batT: Float? = null
        for ((path, type) in sysfs.thermalZones) {
            val c = reads[path]?.toFloatOrNull()?.div(1000f) ?: continue
            when {
                type.contains("cpu") || type.startsWith("apc") || type.startsWith("cpuss") ->
                    cpuT = max(cpuT ?: c, c)
                type.contains("gpu") -> gpuT = max(gpuT ?: c, c)
                type == "battery" -> batT = max(batT ?: c, c)
            }
        }
        return Triple(cpuT, gpuT, batT)
    }

    private companion object {
        const val TAG = "HWM"
        const val POLL_MS = 500L
        const val HISTORY_LEN = 120
    }
}
