# 📱 Android TaskManager

[English](#english-version) | [简体中文](#简体中文版)

---

## 简体中文版

一个硬核的 Android 任务管理器：UI 用 **Jetpack Compose (Material 3)**，数据由一个**自研的 C 后端**采集——那份 `libtask_backend.so` 不是 JNI 库，而是被 `/system/bin/linker64` 当成独立进程拉起来的常驻服务，用文本协议跟 Kotlin 一问一答，直接读 `/proc`、`/sys`、`/proc/iomem` 这些真实内核数据。特权有两条通道：**Root（uid 0）** 与 **Shizuku（ADB / shell，uid 2000）**，都没有就降级成应用自身 UID，只能看沙盒内的东西。

### ✨ 已实现的功能

**性能页**（`PerformanceScreen.kt`，单页五种设备，左侧列表 + 右侧统计/曲线/明细）

- **CPU**：总利用率、内核时间、逐逻辑核利用率与内核时间两条堆叠图、型号名、插槽/核心/逻辑核数、L1/L2/L3 缓存、当前频率、虚拟化与 AVF 组件状态
- **内存**：物理内存/可用/已用/缓存/交换，以及**「为硬件保留的内存」**（在 C 侧用 `/proc/iomem` + DT `reserved-memory` + 标称容量与 `MemTotal` 的差额算好再上报，不是 UI 猜的）
- **磁盘**（多实例，`磁盘 0 (sda)` 这种标题）：活动时间占比、读写吞吐、平均响应时间，独立量程
- **WLAN**：发送/接收速率曲线 + 累计流量明细
- **GPU**（多厂商发现：高通 kgsl → 联发科 Mali misc/platform → 通用 DRM；字段按厂商分别读取）：3D 利用率、专用/共享显存、温度、驱动版本与日期、图形 API（Vulkan/OpenGL 分开）、PCI 位置、发现节点
- 每个图表都**从 0 起画**（空队列先垫一个 0 点）；曲线保留 512 点；双击进图形摘要预览；长按或右上角菜单可复制当前图表

**详细信息页**：进程属性二维表（43 列的显隐可自选、点表头按该统计值降序、点格选中整行、长按格弹测试对话框），横向与纵向双滚动

**设置页**：外观（主题/动态取色）、常规（默认起始页、实时更新速度、利用率精确度、窗口管理、置于顶层浮窗）、高级（内核内存转储选项、用户身份、Shizuku 与 Root 状态）、重置设置、反馈

**调试页**：整包设备快照字段转储（可滚动、可复制）、注入空设备用于验证版式

**骨架页（UI 已就位，数据未接入，路由会显示"此页面不受支持"占位）**：进程、应用历史、开机启动、用户、服务

### 🛠️ 技术栈

- **语言**：Kotlin 2.2.10 + C11
- **UI**：Jetpack Compose（BOM 2026.02.01）、Material 3、Material Icons Extended
- **构建**：AGP 9.5.1，compileSdk / targetSdk 37，minSdk 24（Android 7.0）
- **ABI**：`arm64-v8a` + `x86_64`（两种 native 库都会打进 APK）
- **特权通道**：Root（`su -c id` 探测，带超时）与 Shizuku（`dev.rikka.shizuku:api/provider/aidl 13.1.5`；Shizuku 13.x 起 `newProcess` 已收进服务内部 AIDL，只能从那一层拿远程进程）
- **底层**：C 后端 `cpp/Backend/main.c`（`-Os -g0 -Wl,--gc-sections`，约 92 KB 的 ELF），零依赖解析 `/proc` / `/sys`

### 🏗️ 架构

```
UI 层      ui/*.kt                    只管画：不取数、不计时、不排序
              ↓
页面数据    service/UIService.kt       页面数据中枢 + 轮询节拍（间隔来自设置页）
              ↓
设备模型    manager/PerformanceManager.kt  注册/过滤/命名/合并/曲线缓冲（id 是索引，node 是身份）
              ↓
解析层      data/PerformanceSnapshot.kt   把 D 行字段解析成快照（valueOf/statusOf/intOf）
              ↓
协议层      data/LinuxService.kt          起进程、发请求、收 P/I/D 行、按空白与 '=' 切键
              ↓
特权层      service/ShizukuManager.kt / RootManager.kt
              ↓
后端        cpp/Backend/main.c → libtask_backend.so（独立 ELF 进程，请求-应答循环）
```

几条贯穿全局的约定：

- 请求类型：`QUERY_PROCESS`(1) / `QUERY_INFO`(2) / `CONTROL`(3) / `PING`(4) / `DEVICE_SNAPSHOT`(5)；`DEVICE_SNAPSHOT` 的应答全是 `I` 行
- **数据缺失必须有语义**：字段前缀 `!` 表示状态（`UNSUPPORTED` / `ACCESS_DENIED` / `NOT_AVAILABLE` / `READ_FAILED` / `NO_BASELINE` / `TRUNCATED`），UI 据此渲染——`-` 是"有这东西但这次没读到（多半权限）"，`不支持` 是"平台确实没有/算不出"
- 底层只陈述事实，命名与取舍在 Manager，UI 只渲染

### 📱 前置要求与使用

1. Android 7.0 及以上（minSdk 24）。
2. 想看到全量数据，至少满足其一：
   - **Root**：Magisk / KernelSU 等，App 探测 `su -c id` 成功后以 uid 0 执行；
   - **Shizuku**：安装 [Shizuku](https://shizuku.rikka.app/)，通过无线调试或 Root 启动服务，再在设置页授予权限。
3. 两者都没有也能装、能开，但只能读应用自身可见的数据，多数字段会显示 `-` 或 `不支持`。
4. 打开 App → 设置页确认 Shizuku / Root 状态 → 进入"性能"页或"详细信息"页。

### 🚀 构建指南

1. 克隆仓库：`git clone https://github.com/Qingsource/Android-TaskManager.git`
2. 用 Android Studio 打开，等 Gradle 同步完成。
3. 命令行构建：`gradlew.bat :app:assembleDebug --offline`（Windows），产物在 `app/build/outputs/apk/debug/app-debug.apk`。
4. 构建完执行 `gradlew.bat --stop`——命令行 daemon 残留会把提交内存吃满，之后从 Studio 点 Build 会出现 AAPT2 / ninja "启动即退出"的假故障。

> ⚠️ **改 C 代码时必读**：CMake 直接把 ELF 输出到 `src/main/jniLibs/<abi>/`，而 AGP 的 `merge*JniLibFolders` 在构建早期按"上次构建结束时的内容"做快照。所以只改 C 的构建，APK 里仍是旧 ELF。做法是**连续构建两次**，或先删 `app/build/intermediates/{merged_native_libs,stripped_native_libs,merged_jni_libs}` 再构建；验证方式是比对 APK 内 `lib/<abi>/libtask_backend.so` 与 `jniLibs` 下同名文件的哈希。

### 📂 目录

```
app/src/main/
├── cpp/Backend/main.c           # 全部底层采集与协议（单文件，约 350 KB）
├── cpp/CMakeLists.txt           # 输出 libtask_backend.so 到 jniLibs
├── java/com/android/taskmanager/
│   ├── MainActivity.kt          # 导航、响应式布局（宽屏侧栏 / 窄屏 TopAppBar）
│   ├── Interfaces.kt            # IShizukuApi / IRootApi / IPageDataApi / IOverlayApi 等接口
│   ├── data/                    # LinuxService、PerformanceSnapshot、AndroidService
│   ├── manager/                 # PerformanceManager、PageManagers
│   ├── service/                 # UIService、ShizukuManager、RootManager、OverlayHost、SettingsStore
│   └── ui/                      # 各页面 + Theme + PageShell
├── jniLibs/                     # CMake 产出的 libtask_backend.so（构建产物，勿手改）
└── assets/CPUNAME.txt           # CPU 型号名映射表
```

### 📄 许可

MIT License，见 [LICENSE](LICENSE)。

---

## English Version

A hardcore Android task manager. The UI is **Jetpack Compose (Material 3)**; the data comes from a **hand-written C backend** — `libtask_backend.so` is not a JNI library but a long-running ELF launched through `/system/bin/linker64` as its own process, speaking a small line-based protocol with Kotlin while reading real kernel data from `/proc`, `/sys` and `/proc/iomem`. Privileges come from two channels: **Root (uid 0)** or **Shizuku (ADB/shell, uid 2000)**, falling back to the app's own UID (sandboxed view only) when neither is available.

### ✨ What works today

**Performance page** — five device kinds in one page (list on the left, stats + charts + detail rows on the right)

- **CPU**: total utilization, kernel time, per-logical-core utilization and kernel time (two stacked charts), model name, sockets/cores/logical cores, L1/L2/L3 cache, current frequency, virtualization and AVF component status
- **Memory**: total/available/used/cached/swap plus **"memory reserved for hardware"**, computed in C from `/proc/iomem`, the DT `reserved-memory` nodes and the gap between nominal DRAM size and `MemTotal` — not guessed in the UI
- **Disk** (multiple instances, titled like `磁盘 0 (sda)`): activity-time ratio, read/write throughput, average response time, each with its own auto range
- **WLAN**: transmit/receive rate curves plus cumulative traffic details
- **GPU** (multi-vendor discovery: Qualcomm kgsl → MediaTek Mali misc/platform → generic DRM; per-vendor field readers): 3D utilization, dedicated/shared memory, temperature, driver version and date, graphics API (Vulkan and OpenGL reported separately), PCI location, discovery node
- Every chart starts **from zero** (an empty queue is primed with a 0 point); 512 points of history per series; double-tap opens a chart preview; long-press or the top-right overflow menu copies the current chart

**Details page**: a two-dimensional process attribute table — 43 columns you can show/hide, tapping a header sorts descending by that statistic, tapping a cell selects the whole row, long-pressing a cell opens a test dialog; the table scrolls both ways.

**Settings page**: appearance (theme / dynamic color), general (default start page, live update interval, utilization precision, window management / always-on-top overlay), advanced (kernel memory dump options, user identity, Shizuku and Root status), reset, feedback.

**Debug page**: full device-snapshot field dump (scrollable, copyable) and injected empty devices for layout testing.

**Skeleton pages** (UI in place, data not wired up yet, routed to an "unsupported" placeholder): Processes, App History, Startup Apps, Users, Services.

### 🛠️ Tech stack

- **Languages**: Kotlin 2.2.10 + C11
- **UI**: Jetpack Compose (BOM 2026.02.01), Material 3, Material Icons Extended
- **Build**: AGP 9.5.1, compileSdk / targetSdk 37, minSdk 24 (Android 7.0)
- **ABIs**: `arm64-v8a` + `x86_64` (both native libraries ship in the APK)
- **Privilege channels**: Root (probed with `su -c id` under a timeout) and Shizuku (`dev.rikka.shizuku:api/provider/aidl 13.1.5`; since Shizuku 13.x `newProcess` lives inside the service AIDL, so remote processes are obtained from that layer)
- **Backend**: `cpp/Backend/main.c` (`-Os -g0 -Wl,--gc-sections`, ~92 KB ELF), dependency-free parsing of `/proc` and `/sys`

### 🏗️ Architecture

```
UI          ui/*.kt                    draws only: no fetching, no timers, no sorting
              ↓
Page data   service/UIService.kt       page data hub + polling tick (interval from Settings)
              ↓
Models      manager/PerformanceManager.kt  registration/filtering/naming/merging/ring buffers
              ↓
Parsing     data/PerformanceSnapshot.kt    D-line fields → snapshots (valueOf/statusOf/intOf)
              ↓
Protocol    data/LinuxService.kt           spawn process, send request, read P/I/D lines
              ↓
Privilege   service/ShizukuManager.kt / RootManager.kt
              ↓
Backend     cpp/Backend/main.c → libtask_backend.so (standalone ELF, request/response loop)
```

Global conventions:

- Request types: `QUERY_PROCESS`(1) / `QUERY_INFO`(2) / `CONTROL`(3) / `PING`(4) / `DEVICE_SNAPSHOT`(5); a `DEVICE_SNAPSHOT` reply contains `I` lines only
- **Missing data always carries a reason**: a field prefixed with `!` carries a status (`UNSUPPORTED` / `ACCESS_DENIED` / `NOT_AVAILABLE` / `READ_FAILED` / `NO_BASELINE` / `TRUNCATED`). The UI renders `-` for "exists but not readable now (usually permissions)" and "not supported" for "this platform simply has no such value"
- The backend states facts; naming and selection belong to the manager; the UI only renders

### 📱 Prerequisites & usage

1. Android 7.0 or newer (minSdk 24).
2. For the full dataset, have at least one of:
   - **Root** (Magisk / KernelSU etc.): the app probes `su -c id` and runs as uid 0;
   - **Shizuku**: install [Shizuku](https://shizuku.rikka.app/), start it via wireless debugging or Root, then grant permission on the Settings page.
3. With neither, the app still installs and runs but only sees data visible to its own UID; most fields will read `-` or "not supported".
4. Open the app → check Shizuku / Root status in Settings → go to Performance or Details.

### 🚀 Build

1. Clone: `git clone https://github.com/Qingsource/Android-TaskManager.git`
2. Open in Android Studio and let Gradle sync finish.
3. Or from the CLI: `gradlew.bat :app:assembleDebug --offline`; the artifact lands at `app/build/outputs/apk/debug/app-debug.apk`.
4. Run `gradlew.bat --stop` afterwards — a leftover command-line daemon eats the commit charge and makes the next Studio build fail with AAPT2 / ninja "started and exited immediately" symptoms.

> ⚠️ **If you touch C code**: CMake writes the ELF straight into `src/main/jniLibs/<abi>/`, while AGP's `merge*JniLibFolders` snapshots that folder early in a build. A C-only change therefore ships the *previous* ELF. Build **twice** in a row, or delete `app/build/intermediates/{merged_native_libs,stripped_native_libs,merged_jni_libs}` first. Verify by comparing the hash of `lib/<abi>/libtask_backend.so` inside the APK with the file under `jniLibs`.

### 📂 Layout

```
app/src/main/
├── cpp/Backend/main.c           # all low-level collection and the protocol (single file, ~350 KB)
├── cpp/CMakeLists.txt           # emits libtask_backend.so into jniLibs
├── java/com/android/taskmanager/
│   ├── MainActivity.kt          # navigation, responsive layout (wide rail / compact TopAppBar)
│   ├── Interfaces.kt            # IShizukuApi / IRootApi / IPageDataApi / IOverlayApi ...
│   ├── data/                    # LinuxService, PerformanceSnapshot, AndroidService
│   ├── manager/                 # PerformanceManager, PageManagers
│   ├── service/                 # UIService, ShizukuManager, RootManager, OverlayHost, SettingsStore
│   └── ui/                      # screens + Theme + PageShell
├── jniLibs/                     # CMake output, libtask_backend.so (build artifact, do not edit)
└── assets/CPUNAME.txt           # CPU model-name table
```

### 📄 License

MIT License — see [LICENSE](LICENSE).

## ⭐ Support

If you find this project helpful, please give it a star!
