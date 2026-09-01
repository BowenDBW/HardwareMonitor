package com.example.hardwaremonitor.data

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * sysfs / proc 节点读取器。
 *
 * 读取策略：优先直读；权限不足的节点合并为**一次** `su -c 'cat a; cat b; ...'` 批量回退，
 * 避免每个节点 fork 一次 su（每次几十毫秒）。路径在构造后一次性探测，轮询期只读不找。
 */
class SysfsReader {

    private companion object {
        const val TAG = "HWM"
        const val SEP = "__HM_SEP__"
        const val MAX_CORES = 16
    }

    // ---- 一次性探测结果 ----
    var cpuCores: IntArray = IntArray(8) { it }
        private set
    var gpuBusyPath: String? = null
        private set
    var gpuClkPath: String? = null
        private set
    var gpuMaxClkPath: String? = null
        private set
    var npuPath: String? = null
        private set
    /** (tempFilePath, zoneType) 列表，type 用于分类 cpu/gpu/battery 温度 */
    var thermalZones: List<Pair<String, String>> = emptyList()
        private set

    private val restricted = mutableSetOf<String>()
    private var suChecked = false
    private var suAvailable = false

    /** 探测 CPU 核数 / GPU / thermal / NPU 节点，只在启动时调用一次。 */
    fun discover() {
        // CPU 核数：cpufreq 目录存在即视为在线能力
        val cores = mutableListOf<Int>()
        for (i in 0 until MAX_CORES) {
            if (File("/sys/devices/system/cpu/cpu$i/cpufreq").exists()) cores += i
        }
        if (cores.isNotEmpty()) cpuCores = cores.toIntArray()
        Log.i(TAG, "discover: cpuCores=${cpuCores.joinToString(",")}")

        // GPU (Adreno, kgsl)
        val kgsl = "/sys/class/kgsl/kgsl-3d0"
        gpuBusyPath = listOf("$kgsl/gpu_busy_percentage", "$kgsl/gpubusy")
            .firstOrNull { read(it) != null }
        gpuClkPath = probe("$kgsl/gpuclk")
        gpuMaxClkPath = probe("$kgsl/max_gpuclk")
        Log.i(TAG, "discover: gpuBusy=$gpuBusyPath gpuClk=$gpuClkPath maxClk=$gpuMaxClkPath")

        // Thermal zones（存 temp 文件路径，温度值在该文件内，单位毫摄氏度）
        thermalZones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.mapNotNull { z ->
                val type = try { File(z, "type").readText().trim() } catch (e: Exception) { "" }
                if (type.isEmpty()) null else (File(z, "temp").absolutePath to type)
            } ?: emptyList()
        Log.i(TAG, "discover: thermalZones=${thermalZones.map { it.second }}")

        // NPU / Hexagon 探测（需 root 的 debugfs）
        npuPath = probeNpu()
        Log.i(TAG, "discover: npu=$npuPath")
    }

    /** 读取单节点原始文本（trim），失败返回 null。 */
    fun read(path: String): String? = readMany(listOf(path))[path]

    /**
     * 批量读取。返回 path -> trimmed text 的映射（读不到的路径不出现在结果中）。
     * 直读失败的已存在路径标记为 restricted，合并为一次 su 批量读。
     */
    fun readMany(paths: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        if (paths.isEmpty()) return out

        val needRoot = ArrayList<String>()
        for (p in paths) {
            if (p !in restricted) {
                val v = directRead(p)
                if (v != null) {
                    out[p] = v
                    continue
                }
                if (File(p).exists()) restricted += p
            }
            needRoot += p
        }
        if (needRoot.isNotEmpty()) out.putAll(suBatchRead(needRoot))
        return out
    }

    // ---- 内部 ----

    /** 探测单个文件是否存在且可读（不关心内容语义）。 */
    private fun probe(path: String): String? = if (read(path) != null) path else null

    private fun directRead(path: String): String? {
        val f = File(path)
        if (!f.exists()) return null
        return try {
            f.readText().trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** 合并受限节点为一次 su 调用，输出以 SEP 分隔逐个解析。 */
    private fun suBatchRead(paths: List<String>): Map<String, String> {
        if (!checkSu()) return emptyMap()
        val script = paths.joinToString(";") { "cat '$it'; echo $SEP" }
        val raw = runSuRaw(script) ?: return emptyMap()
        val out = HashMap<String, String>()
        val parts = raw.split(SEP)
        paths.forEachIndexed { i, p ->
            if (i < parts.size) {
                val seg = parts[i].lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                if (seg != null) out[p] = seg
            }
        }
        return out
    }

    /** 用 root 在 /sys 与 debugfs 中找 NPU/Hexagon 相关节点。找不到返回 null（面板显示 N/A）。 */
    private fun probeNpu(): String? {
        if (!checkSu()) return null
        val script = "find /sys /sys/kernel/debug \\( -iname '*npu*' -o -iname '*hexagon*' \\) -type f 2>/dev/null"
        val raw = runSuRaw(script) ?: return null
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        if (lines.isEmpty()) return null
        Log.i(TAG, "discover: NPU candidates = ${lines.joinToString(", ")}")
        val loadLike = Regex("load|util|usage|pct|percent|busy", RegexOption.IGNORE_CASE)
        return lines.firstOrNull { loadLike.containsMatchIn(it) }
            ?: lines.firstOrNull { it.contains("remoteproc") }
            ?: lines.first()
    }

    private fun checkSu(): Boolean {
        if (suChecked) return suAvailable
        suChecked = true
        val out = runSuRaw("echo hm_ok", timeoutMs = 6000)
        suAvailable = out?.contains("hm_ok") == true
        Log.i(TAG, "su available = $suAvailable")
        return suAvailable
    }

    /** 执行 `su -c script`，返回 stdout trim 文本；超时销毁进程防 su 弹窗挂起。 */
    private fun runSuRaw(script: String, timeoutMs: Long = 1500): String? {
        val proc = try {
            ProcessBuilder("su", "-c", script).start()
        } catch (e: Exception) {
            Log.w(TAG, "su spawn failed", e)
            return null
        }
        val out = AtomicReference("")
        val outThread = Thread {
            try { out.set(proc.inputStream.readBytes().decodeToString()) } catch (_: Exception) {}
        }
        val errThread = Thread {
            try { proc.errorStream.readBytes() } catch (_: Exception) {}
        }
        outThread.start(); errThread.start()
        try { outThread.join(timeoutMs) } catch (_: InterruptedException) {}
        errThread.join(timeoutMs)
        if (outThread.isAlive) proc.destroy() // 超时仍未读完 → 进程可能挂起（授权弹窗等）
        try { proc.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
        proc.destroy()
        return out.get().trim().ifEmpty { null }
    }
}
