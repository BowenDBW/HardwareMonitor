# 调研报告：小米13 Pro（骁龙8Gen2）单页 CPU/GPU/NPU/RAM 监控 App

> 状态：**调研完成，未开始实现**。等用户下一步指示。
> 注：本环境无网络（WebSearch/WebFetch 均被拒），以下基于对高通 SM8550 平台的既有知识，最终以实机验证为准。需要实机确认的项目见「§6 未决项」。

## 1. 结论摘要
- **可行**，且大部分数据**不依赖 root**。CPU、GPU、RAM、温度四大块在小米13 Pro 上基本都能拿到；**唯一不确定的是 NPU(Hexagon)**。
- 推荐架构：**普通 App + Magisk su 回退**（先直读 sysfs，权限不足自动 `su -c`），不用改系统、不用 platform 签名。
- 单页 4 个分区折线面板（CPU 占用% + 频率文字叠加、GPU、NPU、RAM）+ 左上占用/频率数字 + 右上温度文字，500ms 轮询，负载极低，完全可行。

## 2. 目标硬件规格（SM8550 / 8 Gen 2）
| 组件 | 规格 | 监控入口 |
|---|---|---|
| CPU | 1×Cortex-X3 @3.19GHz + 4×A715 @2.8GHz + 3×A510 @2.0GHz，**共 8 核** | sysfs cpufreq，每核独立节点 |
| GPU | Adreno 740 @ ~719MHz | kgsl sysfs |
| NPU | Qualcomm Hexagon（DSP/Tensor，8Gen2 不单独叫"NPU"，并入 Hexagon） | **无标准 sysfs 节点**，需探测 |
| RAM | LPDDR5X | ActivityManager（免 root） |

## 3. 数据源逐项调研

### 3.1 CPU 每核频率 — ✅ 可靠，通常免 root
- `/sys/devices/system/cpu/cpu{N}/cpufreq/scaling_cur_freq`（kHz），N=0..7；同目录 `scaling_max_freq`
- world-readable，一般不需要 root
- 注意：离线核心节点缺失或返回 0，需按在线核过滤

### 3.2 CPU 占用% — ✅ 聚合可靠
- `/proc/stat` 两次采样 delta：`1 - idle/total`
- Android 对 `/proc/stat` 的 per-cpu 行是否屏蔽因内核而定；小米高通内核通常可读，但按用户要求 CPU 面板**只用总占用%**，per-core 以频率文字形式叠加，规避此问题

### 3.3 GPU 占用% + 频率 — ✅ 可靠
- 占用：`/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`（"45%"），或 `gpubusy`（"busy idle" 两数比值）
- 频率：`/sys/class/kgsl/kgsl-3d0/gpuclk`（Hz）、`max_gpuclk`
- 高通机型通常可读；个别固件 `gpubusy` 需 root → su 回退覆盖

### 3.4 温度 — ✅ 可靠，需首次实机校准分类
- `/sys/class/thermal/thermal_zone{N}/{type,temp}`，temp 为**毫摄氏度** ÷1000
- 高通 tsens 标准 zone type：`cpu-0-0`/`cpuss-0`/`apc-*`（CPU 侧）、`gpuss-0`（GPU 侧）、`battery` 等；小米内核沿用该命名 → 按前缀分类取最高值
- 个别 zone（如 quiet-therm）需 root → su 回退
- 待实机 dump zone 列表校准（见 §6.2）

### 3.5 RAM — ✅ 可靠，完全免 root
- `ActivityManager.getMemoryInfo()`：used = total - avail，直接算 % 与已用 GB

### 3.6 NPU（Hexagon）— ⚠️ 唯一不确定项
- 8 Gen 2 **没有标准的 "NPU load" 公开节点**；Hexagon 由 remoteproc / ghost / fastrpc 等内核驱动管理
- 可能存在的节点（随厂商内核不同）：`/sys/kernel/debug/remoteproc/...`、名称含 `npu`/`hexagon` 的 debugfs 节点、个别厂商 `msm_npu` 相关
- 方案：root 后探测 `find /sys /sys/kernel/debug -iname '*npu*' -o -iname '*hexagon*'`；找不到则 NPU 面板显示 **N/A**，其余功能不受影响

## 4. 「做成有 root 权限的 app」方案对比
| 方案 | 复杂度 | 说明 | 结论 |
|---|---|---|---|
| **Magisk + su 回退** | ★ 极低 | 普通 app 执行 `su -c 'cat ...'`，Magisk 弹窗授权一次后静默放行 | ✅ **推荐** |
| 系统应用 priv-app | ★★★★ | 需 platform 签名 + 刷入 /system/priv-app，需改 ROM | ❌ 不必要 |
| 免 root 兜底 | — | CPU/GPU/thermal/RAM 大多能直读，NPU 无解 | 作为自动降级路径 |

关键点：**App 本身不需要"是"root**，只要设备装有 Magisk，app 通过 su 拿取 root——这就是"有 root 权限的 app"的最简形态。性能注意：不要每节点 fork 一次 su（每次几十 ms），受限节点合并为**一次** `su -c 'cat a; cat b; ...'` 批量读，500ms 一次毫无压力。

## 5. 技术选型（从简）
- 现有工程已是 Compose 模板（Kotlin 2.2 / AGP 9.3 / minSdk 29 / targetSdk 37），**零新增第三方依赖**：折线图用 Compose Canvas 自绘（不用 MPAndroidChart）
- 单 Activity + 轮询协程（500ms，ring buffer 120 点 ≈ 60s 窗口），`FLAG_KEEP_SCREEN_ON` 常亮
- **不需要声明任何 Android 权限**（读 sysfs + su 均无需）
- 仅前台监控，不做后台 Service，保持最简

## 6. 未决项（需要实机验证 / 用户输入）
1. **NPU 节点未知**（核心未决项）→ 需在已 root 的小米13 Pro 上执行：
   `su -c 'find /sys /sys/kernel/debug \( -iname "*npu*" -o -iname "*hexagon*" \) 2>/dev/null'`
2. **thermal zone 命名校准** → `for z in /sys/class/thermal/thermal_zone*; do echo "$z: $(cat $z/type)"; done`
3. **GPU 节点可读性确认** → `cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`、`cat /sys/class/kgsl/kgsl-3d0/gpuclk`
4. 把以上输出贴回给我 → 更新节点表 → 用户确认后再进入实现阶段

## 7. 实现方案（确认后执行）
单页 UI：深色底，竖排 4 分区面板（各 weight 1f）——
- **CPU**：占用% 折线（0-100%），簇频率文字叠加（X3 / A715 / A510 当前最高频率）
- **GPU**：占用% 折线 + 频率文字叠加（MHz）
- **NPU**：占用% 折线（找不到节点则显示 N/A）
- **RAM**：占用% 折线 + 已用 GB/总量
- 屏幕左上角：当前 CPU%/频率、GPU%、RAM% 数字；右上角：CPU/GPU/电池温度（仅文字）
- 配色：CPU=绿 / GPU=青 / NPU=品红 / RAM=琥珀，深灰网格

新增文件：
```
app/src/main/java/com/example/hardwaremonitor/
├── MainActivity.kt            # 重写：setContent(MonitorScreen)，常亮，lifecycleScope 启停轮询
├── data/Sysfs.kt              # 节点路径 + readFile(直读→su 批量回退) + 一次性节点发现(核数/GPU/thermal/NPU探测)
├── data/Monitor.kt            # Sample 数据类 + 500ms 轮询 + ring buffer + StateFlow
└── ui/LineChart.kt, ui/MonitorScreen.kt
```
验证：`./gradlew assembleDebug` → `adb install` → 实机观察滚动图表与角标数字 → `adb logcat -s HWM` 核对节点发现与数值。

---
**本轮已结束，等待下一步指示。**
