package com.android.taskmanager.data

import com.android.taskmanager.AppStrings
import com.android.taskmanager.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round


/**
 * 性能页的一帧数据（当前只有 CPU 页要的那一组）。
 *
 * 数据链：
 *   ELF 的 typeCode=5 设备快照（全是 I 行）
 *   -> LinuxService.deviceSnapshot()
 *   -> UIService 清洗成这一帧
 *   -> IPageDataApi.performanceSnapshot()
 *   -> 性能页。
 *
 * 每个字段的 null 含义统一是"后端没给"（后端回的是 !状态，例如 !UNSUPPORTED）：
 * 界面必须显示占位或"不支持"，**不能拿 0 顶替** —— 0% 与"读不到"是两件事。
 *
 * 显示文案的格式化也放在这里：页面与 UISvc 共用一套，免得两边各写一份、
 * 过一阵子一边改了另一边没改，同一格数据在两个地方长得不一样。
 */
data class PerformanceSnapshot(
    /** 整机 CPU 利用率（0..100）。 */
    val cpuUsagePercent: Float? = null,

    /**
     * 内核时间占比（0..100）：总利用率里在内核态（system + irq + softirq）消耗的那部分。
     *
     * 它与 [cpuUsagePercent] 用同一个采样区间做分母，所以永远 <= 利用率；
     * null = 这一帧算不出来（首帧没有基准、计数器回退），图表不上叠内核那段。
     */
    val cpuKernelPercent: Float? = null,

    /** 逐核利用率（0..100），顺序与 [cpuIds] 一一对应；空 = 后端没报。 */
    val logicalUsage: List<Float> = emptyList(),

    /** 逐核内核时间（0..100），与 [logicalUsage] 同一个顺序。 */
    val logicalKernel: List<Float> = emptyList(),

    /** 逻辑核号，例如 [0,1,2,3]；上面两列就是按这个顺序排的。 */
    val cpuIds: List<Int> = emptyList(),

    /** 当前运行频率（MHz）。 */
    val cpuSpeedMhz: Int? = null,

    /** 基准（最大）频率（MHz），和"当前频率"是两件事。 */
    val cpuBaseMhz: Int? = null,

    /**
     * CPU 温度（℃，整数）：底层把 /sys/class/thermal 里 type 含 cpu 的热区求了平均。
     *
     * null 有两种源因，页面对这两种都不显示这一项（不写「-」也不写「不支持」）：
     *   1) 这台机器没导出 CPU 热区（平台不支持）；
     *   2) 有热区但一个都读不出来（没权限）。
     * 换句话说，这一格是"有才出现"，不是"没有也要占位"。
     */
    val cpuTemperatureC: Int? = null,

    /** 处理器商品名（人看得懂的名字）。由 ELF 侧补实现，现在通常是 null。 */
    val cpuName: String? = null,

    /**
     * 虚拟化状态码（协议原生形态，不保证是给人看的文案）：
     * ENABLED_KVM / ENABLED_GUNYAH / ENABLED_GENIEZONE / ENABLED_HALLA / DISABLED / UNSUPPORTED。
     * 显示时走 [BackendDisplayText.virtualizationText]，不要在界面上直接用它。
     */
    val virtualization: String? = null,

    /** 进程总数。 */
    val processCount: Int? = null,

    /** 线程总数。 */
    val threadCount: Int? = null,

    /** 已分配的文件描述符数。 */
    val fdCount: Int? = null,

    /** 开机时长（秒）。 */
    val uptimeSeconds: Long? = null,

    /** 处理器插槽数（通常为 1）。 */
    val sockets: Int? = null,

    /** 物理核心数。 */
    val cores: Int? = null,

    /** 逻辑处理器数（支持超线程时大于核心数）。 */
    val logicalCpus: Int? = null,

    /** 一级缓存总量（KB）。 */
    val l1CacheKb: Int? = null,

    /**
     * L1 没读到时的原因：`ACCESS_DENIED`（有这一级但读不出来，页面写「-」）
     * 或 `NOT_AVAILABLE` / `UNSUPPORTED`（这一级确实不存在，页面写「不支持」）。
     */
    val l1CacheStatus: String? = null,

    /** 二级缓存总量（KB）。 */
    val l2CacheKb: Int? = null,

    /** L2 的状态，含义同 [l1CacheStatus]。 */
    val l2CacheStatus: String? = null,

    /** 三级缓存总量（KB）；null = 这一级没读到，原因看 [l3CacheStatus]。 */
    val l3CacheKb: Int? = null,

    /** L3 的状态，含义同 [l1CacheStatus]。 */
    val l3CacheStatus: String? = null,

    /**
     * 是不是虚拟机客人（本程序自己就跑在虚拟机里）。
     *
     * Linux 侧这一版恒报 0（Android 上没有可靠的检测手段，按约定先固定），
     * 所以正常装机永远是 false。一旦是 true，明细块只留"虚拟机: 是"一行，
     * 虚拟化与 AVF 两行都让位 —— 虚拟机里讨论这些没有意义。
     */
    val isVirtualMachine: Boolean = false,

    /**
     * 系统里有没有 AVF 组件（SDK >= 34 且 /apex/com.android.virt 存在）。
     *
     * 这一格属于 Android 层数据（UIService 从 AndroidService 的 SYSTEM 数据并进来）。
     * false = 整行都不显示，连"AVF 支持: 否"也不出现；
     * true  = 显示，值看 [avfSupported]（true 是 / false 否）。
     */
    val avfComponentPresent: Boolean = false,

    /**
     * AVF（Android Virtualization Framework）支持情况。
     *
     * 只在 [avfComponentPresent] 为 true 时参与显示：
     * true = 支持（"是"），false = 不支持（"否"），null = 查不到，不显示。
     * Linux 侧不知道它，所以 [from] 解析设备快照时永远是 null。
     */
    val avfSupported: Boolean? = null,

    /**
     * 内存页要用的那一组字段（一次设备快照里的 MEMORY 设备块）。
     *
     * 目前没有数据源，[from] 不填它，恒为 null —— 页面按"读不到"渲染（破折号），
     * 不拿 0 冒充。接入时只改 [from] 的取值，页面侧不用动。
     */
    val memory: MemorySnapshot? = null,

    /**
     * 磁盘设备列表（这一帧里报告了几台就几台，一台都没有就是空表）。
     *
     * 为什么是列表：CPU 与内存是单实例（一台机器一份），而磁盘 / 网络 / GPU / NPU
     * 都可能同一类装好几台，也可能一台都没有。页面据此决定"这一栏显不显示"：
     * 空表 = 连入口都不出现，绝不画一个 0% 的空壳。
     *
     * 顺序即展示顺序：管理器按 id 命名（磁盘 0、磁盘 1 …），底层没给 id 时按报文顺序。
     */
    val disks: List<DiskSnapshot> = emptyList(),

    /**
     * 网卡设备列表（WLAN，一台机器可能多块网卡，也可能没有）。
     *
     * 与磁盘同一套规矩：空表 = 性能页连 WLAN 入口都不出现。
     * 后端现在发得全的是：role / iface / name / ipv4 / ipv6 / has_data_address
     * 与收发速率；无线的 SSID / link_type / freq_mhz / signal_percent 有数据源，
     * 移动网络这几格还没有（页面据此整行不画）。读不到的写"不支持"，速率没有写 0。
     */
    val networks: List<NetworkSnapshot> = emptyList(),

    /**
     * 显卡设备列表（一张机器可能核显 + 独显两张，也可能读不到）。
     *
     * 与磁盘 / 网卡同一个规矩：空表 = 性能页连 GPU 入口都不出现。
     * 字段全部可空：显存、驱动、图形 API 这类在 Android 上多数读不到，
     * 页面按「不支持 / -」渲染，不拿 0 冒充。
     */
    val gpus: List<GpuSnapshot> = emptyList(),

    /** 采到这一帧的时刻（SystemClock.elapsedRealtime），只用于判断"是不是新一帧"。 */
    val receivedAtMs: Long = 0L
) {

    // ---------- 显示文案 ----------

    /**
     * 利用率文案。
     *
     * @param decimals 保留几位小数：0（默认，整数）/ 1 / 2，取值来自设置页的"利用率精确度"。
     *        底层数据本身有 0.01% 的精度，这里只决定画几位；不传就是旧行为（整数百分比）。
     */
    fun cpuUsageText(decimals: Int = 0): String =
        cpuUsagePercent?.let { String.format(Locale.US, "%.${decimals.coerceIn(0, 2)}f%%", it) } ?: "—"

    /**
     * 内存使用率文案：占用 / 总量，0..100，与内存图纵轴同一口径（顶端 = 实际内存大小）。
     *
     * 左栏那一行用它。没有内存帧、或总量 / 占用任一缺失就写破折号：
     * 分母不知道的时候百分比没有意义。
     */
    fun memoryUsageText(decimals: Int = 0): String {

        val memory = memory ?: return "—"

        val total = memory.totalBytes ?: return "—"

        val used = memory.usedBytes ?: return "—"

        if (total <= 0L) return "—"

        val percent = used.toDouble() / total.toDouble() * 100.0

        return String.format(Locale.US, "%.${decimals.coerceIn(0, 2)}f%%", percent)
    }

    /** 当前频率：MHz 换成 GHz，两位小数。 */
    fun cpuSpeedText(): String = speedText(cpuSpeedMhz)

    /** 基准速度。 */
    fun cpuBaseText(): String = speedText(cpuBaseMhz)

    /**
     * CPU 温度文案（`45 °C`）。
     *
     * 返回 null = 这一项整块不出现（没热区 / 没权限都算）。与 GPU 温度不同：
     * 那边"读不到"还要占一格写「不支持」（Windows 也是这么摆的），
     * CPU 页这里按约定"有才加一项"，没有就连标签都不画。
     */
    fun cpuTemperatureText(): String? = cpuTemperatureC?.let { "$it °C" }


    /** 一级缓存按 KB 显示。 */
    fun l1Text(): String = cacheText(l1CacheKb, l1CacheStatus) { "$it KB" }

    /** 二级缓存按 MB 显示（一位小数）。 */
    fun l2Text(): String = cacheText(l2CacheKb, l2CacheStatus) { megabyteText(it) }

    /** 三级缓存按 MB 显示（一位小数）。 */
    fun l3Text(): String = cacheText(l3CacheKb, l3CacheStatus) { megabyteText(it) }

    /**
     * 三级缓存共用一套文案，按后端给的状态决定，而不是按级别写死。
     *
     *   有值                        -> 正常格式化（0 也是值，不当作没有）
     *   NOT_AVAILABLE / UNSUPPORTED -> 「不支持」（这一级确实不存在 / 平台不导出缓存信息）
     *   ACCESS_DENIED 及其它        -> 「-」（有这一格、这次没读出来，换个身份可能就读得到）
     *
     * 为什么不再按级别写死：之前是 L1/L2 恒写「—」、L3 恒写「不支持」，
     * 于是「L1 没权限」和「L1 真的不存在」在页面上长得一模一样，
     * 而 L3 明明是没权限也照样写「不支持」——把两种原因混成了一种。
     */
    private fun cacheText(kb: Int?, status: String?, format: (Int) -> String): String {

        if (kb != null) return format(kb)

        return when (status) {

            FIELD_STATUS_NOT_AVAILABLE, FIELD_STATUS_UNSUPPORTED -> CACHE_UNSUPPORTED_TEXT

            else -> CACHE_UNKNOWN_TEXT
        }
    }

    /**
     * 虚拟化：后端给的是状态码（ENABLED_KVM / DISABLED / UNSUPPORTED），
     * 经 [BackendDisplayText.virtualizationText] 翻成本地语言后再显示。
     *
     * 返回 null = 整行不出现，只有一种情况：本程序自己跑在虚拟机里。
     */
    fun virtualizationText(): String? =
        if (isVirtualMachine) null else BackendDisplayText.virtualizationText(virtualization)

    /** 虚拟机：只在确实是虚拟机时给"是"，其余一律整行不出现（没有"否"这个状态）。 */
    fun virtualMachineText(): String? = if (isVirtualMachine) AppStrings.s(R.string.data_yes) else null

    /**
     * AVF 支持文案。
     *
     * 返回 null = 整行不出现：是虚拟机 / 系统里没有 AVF 组件 / 支持情况查不到。
     * 组件在、且结论明确时给"是"或"否"。
     */
    fun avfText(): String? {

        if (isVirtualMachine) return null

        if (!avfComponentPresent) return null

        return when (avfSupported) {
            true -> AppStrings.s(R.string.data_yes)
            false -> AppStrings.s(R.string.data_no)
            null -> null
        }
    }

    /** 开机时长：天:时:分:秒。 */
    fun uptimeText(): String {

        val seconds = uptimeSeconds ?: return "0:00:00:00"

        val days = seconds / 86400L
        val hours = (seconds % 86400L) / 3600L
        val minutes = (seconds % 3600L) / 60L
        val rest = seconds % 60L

        return String.format(Locale.US, "%d:%02d:%02d:%02d", days, hours, minutes, rest)
    }

    fun processCountText(): String = textOf(processCount)

    fun threadCountText(): String = textOf(threadCount)

    fun fdCountText(): String = textOf(fdCount)

    fun socketsText(): String = textOf(sockets)

    fun coresText(): String = textOf(cores)

    fun logicalCpusText(): String = textOf(logicalCpus)

    /** 没有数据统一显示破折号：比显示 0 更诚实，也不会被误读成"占用是 0"。 */
    private fun textOf(value: Int?): String = value?.toString() ?: "—"

    private fun speedText(mhz: Int?): String =
        mhz?.let { String.format(Locale.US, "%.2f GHz", it / 1000.0) } ?: "—"

    private fun megabyteText(kilobytes: Int): String =
        String.format(Locale.US, "%.1f MB", kilobytes / 1024.0)

    companion object {

        /** 空帧：还没采到任何数据（页面各格显示破折号 / 占位）。 */
        val EMPTY: PerformanceSnapshot = PerformanceSnapshot()

        /**
         * 从设备快照应答包解析出一帧。
         *
         * 只认"完整且成功"的包：半包或 UNSUPPORTED 一律返回 null，
         * 页面拿到 null 就保持上一帧，不会因为一次失败把数字清零。
         *
         * 新协议（v3）按设备集合发送：CPU 那台设备带处理器字段，SYSTEM 那台带
         * 进程 / 线程 / 句柄 / 开机时长。这里取各自设备里的字段；
         * 老后端（平铺 I 行）没有 devices，就退回读 info，两种格式都能出数据。
         */
        fun from(packet: LinuxPacket?): PerformanceSnapshot? {

            if (packet == null || !packet.isOk) return null

            if (packet.typeCode != LinuxRequestType.DEVICE_SNAPSHOT) return null

            val cpuFields = packet.deviceOf(LinuxProtocol.DEVICE_TYPE_CPU)?.fields ?: packet.info

            val systemFields = packet.deviceOf(LinuxProtocol.DEVICE_TYPE_SYSTEM)?.fields
                ?: packet.info

            return PerformanceSnapshot(
                // 设备内字段用了短名（speed_mhz），旧格式是 cpu_speed_mhz，两种都认
                cpuUsagePercent = cpuFields.floatOf("usage_percent")
                    ?: cpuFields.floatOf("cpu_usage_percent"),

                cpuKernelPercent = cpuFields.floatOf("kernel_percent"),

                logicalUsage = cpuFields.floatListOf("logical_usage"),

                logicalKernel = cpuFields.floatListOf("logical_kernel"),

                cpuIds = cpuFields.textValueOf("cpu_ids")?.toIntList() ?: emptyList(),

                cpuSpeedMhz = cpuFields.intOf("speed_mhz") ?: cpuFields.intOf("cpu_speed_mhz"),

                cpuBaseMhz = cpuFields.intOf("base_mhz") ?: cpuFields.intOf("cpu_base_mhz"),

                // 温度底层是一位小数的浮点（热区平均），这里四舍五入成整数：
                // CPU 页的统计格全部是整数值，温度也跟同一套字号/写法走。
                // 没有这一格（!状态 或老后端根本不发）就留 null，页面整项不出现。
                cpuTemperatureC = cpuFields.floatOf("cpu_temp_c")?.let { Math.round(it) },

                cpuName = cpuFields.textValueOf("name") ?: cpuFields.textValueOf("cpu_name"),

                virtualization = cpuFields.textValueOf("virtualization"),

                // 0 / 1，缺省按"不是虚拟机"处理；1 与 "true" 都认，方便以后换写法
                isVirtualMachine = cpuFields.textValueOf("is_vm")?.let {
                    it == "1" || it.equals("true", ignoreCase = true)
                } ?: false,

                processCount = systemFields.intOf("process_count"),

                threadCount = systemFields.intOf("thread_count"),

                fdCount = systemFields.intOf("fd_count"),

                uptimeSeconds = systemFields.longOf("uptime_seconds"),

                sockets = cpuFields.intOf("sockets"),

                cores = cpuFields.intOf("cores"),

                logicalCpus = cpuFields.intOf("logical_cpus"),

                l1CacheKb = cpuFields.intOf("l1_cache_kb"),

                // 没读到时的原因：读不了（没权限）还是根本没有这一级，页面按它分「-」和「不支持」
                l1CacheStatus = cpuFields.statusOf("l1_cache_kb"),

                l2CacheKb = cpuFields.intOf("l2_cache_kb"),

                l2CacheStatus = cpuFields.statusOf("l2_cache_kb"),

                l3CacheKb = cpuFields.intOf("l3_cache_kb"),

                l3CacheStatus = cpuFields.statusOf("l3_cache_kb"),

                // 内存整块来自 MEMORY 设备；这一版没有该设备（老后端）就留空，页面显示破折号
                memory = packet.deviceOf(LinuxProtocol.DEVICE_TYPE_MEMORY)?.fields
                    ?.let { buildMemorySnapshot(it) },

                // 磁盘是多实例：一台盘一条 D 行（disk0 / disk1 …），有几台就有几条。
                // 后端约定"没有的设备不发 D 行"，所以空表就是这台机器没有块设备 ——
                // 页面据此连磁盘入口都不显示，不画空壳。
                disks = packet.devicesOf(LinuxProtocol.DEVICE_TYPE_DISK)
                    .map { buildDiskSnapshot(it) },

                // 网卡同样多实例：有网卡才有 N 行，没有就空表（WLAN 入口不出现）。
                networks = packet.devicesOf(LinuxProtocol.DEVICE_TYPE_NETWORK)
                    .map { buildNetworkSnapshot(it) },

                // 显卡同一套：底层报几张就有几个入口，一张都没报就没有 GPU 页。
                gpus = packet.devicesOf(LinuxProtocol.DEVICE_TYPE_GPU)
                    .map { buildGpuSnapshot(it) }
            )
        }

        /**
         * 从一台 DISK 设备的字段拼一条磁盘记录。
         *
         * 与 MEMORY 同一套规矩：读不到的格子（`!状态`）折成 null，页面写破折号；
         * 0 只代表"确实是 0"，不会被拿来冒充没读到。
         *
         * 键名与 C 侧 build_disk_devices 一一对应：
         *   name / node / wwid / media / bus / capacity_bytes / formatted_bytes /
         *   system_disk / page_file / activity_percent / avg_response_ms /
         *   read_bytes_per_sec / write_bytes_per_sec
         *
         * media 与 bus 是两个独立字段（HDD|SSD|UFS|eMMC / NVMe|SCSI|MMC|…），
         * 页面上的 "SSD (NVMe)" 由 DiskSnapshot.typeText() 合成，这里不拼字符串。
         */
        private fun buildDiskSnapshot(device: LinuxDevice): DiskSnapshot {

            val fields = device.fields

            return DiskSnapshot(
                // 设备 id 由后端编（disk0 / disk1 …），管理器拿它做缓存键
                id = device.id,

                name = fields.textValueOf("name"),

                node = fields.textValueOf("node"),

                // 硬件标识（wwid / 序列号）：只做身份，不显示；没发或者是 !状态 时是 null
                wwid = fields.textValueOf("wwid"),

                mediaType = fields.textValueOf("media"),

                busType = fields.textValueOf("bus"),

                capacityBytes = fields.longOf("capacity_bytes"),

                formattedBytes = fields.longOf("formatted_bytes"),

                // 0 / 1 两种写法都认；整机认不出系统盘时后端不发这一格，自然就是 null
                systemDisk = fields.boolOf("system_disk"),

                pageFile = fields.boolOf("page_file"),

                activityPercent = fields.floatOf("activity_percent"),

                avgResponseMs = fields.doubleOf("avg_response_ms"),

                readBytesPerSec = fields.doubleOf("read_bytes_per_sec"),

                writeBytesPerSec = fields.doubleOf("write_bytes_per_sec")
            )
        }

        /**
         * 从一块 NETWORK 设备的字段拼一条网卡记录。
         *
         * 现在的后端（build_named_devices）对 NETWORK 只发 `name` 一格，
         * 其余字段全留 null：页面按"不支持"写，不拿 0 或假字符串糊过去。
         * 以后底层补了字段（ssid / link_type / ipv4 / ipv6 / signal_percent /
         * send_bytes_per_sec / receive_bytes_per_sec），只改这里加映射，页面不用动。
         */
        private fun buildNetworkSnapshot(device: LinuxDevice): NetworkSnapshot =

            NetworkSnapshot(
                // 设备 id 由后端编（network0 / network1 …），管理器拿它做曲线缓存的键
                id = device.id,

                // 接口类型：WIFI / ETHERNET / CELLULAR / OTHER，上层按它决定注册哪一类
                role = device.fields.textValueOf("role"),

                // 内核里的接口名（wlan0 / eth0 / rmnet_data0 …）
                iface = device.fields.textValueOf("iface"),

                // 网卡型号；底层读不到时发的是接口名（不再写占位符）
                name = device.fields.textValueOf("name"),

                ssid = device.fields.textValueOf("ssid"),

                linkType = device.fields.textValueOf("link_type"),

                freqMhz = device.fields.textValueOf("freq_mhz"),

                ipv4 = device.fields.textValueOf("ipv4"),

                ipv6 = device.fields.textValueOf("ipv6"),

                // 底层给的事实："这条链路有没有非链路本地地址"（0/1）。
                // 手机网络靠它筛掉只有 fe80:: 的 rmnet 空壳；老后端不发这一格就是 null。
                hasDataAddress = device.fields.intOf("has_data_address")?.let { it != 0 },

                signalPercent = device.fields.floatOf("signal_percent"),

                sendBytesPerSec = device.fields.doubleOf("send_bytes_per_sec"),

                receiveBytesPerSec = device.fields.doubleOf("receive_bytes_per_sec")
            )

        /**
         * 从一张 GPU 设备的字段拼一条显卡记录。
         *
         * 键名与 C 侧 build_gpu_devices 一一对应；读不到的项在包里是 `!状态`，
         * [valueOf] 折成 null，页面写「不支持 / -」，不拿 0 顶替。
         *
         * 显存两类（专用 / 共享）各有"总量 + 已用"两格：只知道总量时图表画不出占用曲线，
         * 但右上角的刻度照样写得出（4.0 GB），所以两格分开存，不合成一个"占用率"。
         */
        private fun buildGpuSnapshot(device: LinuxDevice): GpuSnapshot =

            GpuSnapshot(
                // 设备 id 由后端编（gpu0 / gpu1 …），管理器拿它做曲线缓存的键
                id = device.id,

                // 显卡商品名；读不到时底层发的是节点名（kgsl-3d0 / card0 …）
                name = device.fields.textValueOf("name"),

                // 内核节点名，详情页没名字时的兜底
                node = device.fields.textValueOf("node"),

                // 厂商标签（QUALCOMM / MEDIATEK / SAMSUNG / GOOGLE / ARM …）：
                // 底层按它决定走哪套接口取字段，这里只留着便于排查
                vendor = device.fields.textValueOf("vendor"),

                utilizationPercent = device.fields.floatOf("gpu_usage"),

                temperatureC = device.fields.intOf("gpu_temp_c"),

                dedicatedTotalBytes = device.fields.longOf("dedicated_total_bytes"),

                dedicatedUsedBytes = device.fields.longOf("dedicated_used_bytes"),

                sharedTotalBytes = device.fields.longOf("shared_total_bytes"),

                sharedUsedBytes = device.fields.longOf("shared_used_bytes"),

                // 图形库：Vulkan / OpenGL ES 两路各一份版本串；
                // "NONE" = 查过、这台机器没有那一样（不是缺失，是明确没有）
                vulkanVersion = device.fields.textValueOf("vulkan_version"),

                openglVersion = device.fields.textValueOf("opengl_version"),

                driverVersion = device.fields.textValueOf("driver_version"),

                driverDate = device.fields.textValueOf("driver_date"),

                // 图形 API：VULKAN / OPENGL / NONE（明确两样都不支持）；整格缺失 = 没查成
                gfxApi = device.fields.textValueOf("gfx_api"),

                gfxApiVersion = device.fields.textValueOf("gfx_api_version"),

                // 物理位置：只有 PCI 卡才有，三个整数各自发；SoC 核显三格全空
                pciBus = device.fields.intOf("pci_bus"),

                pciDevice = device.fields.intOf("pci_device"),

                pciFunction = device.fields.intOf("pci_function"),

                reservedBytes = device.fields.longOf("reserved_bytes")
            )

        /**
         * 从 MEMORY 设备的字段拼一帧内存数据。
         *
         * 键名与 C 侧逐项一致（都带 _bytes，单位字节）；读不到的项在包里是 `!状态`，
         * [valueOf] 会折成 null，页面按破折号显示，不拿 0 顶替。
         *
         * 分条图四段在这里换算成占总量比例，并把「已修改」从「使用中」里扣掉：
         * Dirty / Writeback 本来就属于已用，不扣的话两段会重叠，归一化后
         * 使用中会平白多出一块。
         */
        private fun buildMemorySnapshot(fields: Map<String, String>): MemorySnapshot {

            val total = fields.longOf("total_bytes")

            val usedBytes = fields.longOf("used_bytes")

            val modifiedBytes = fields.longOf("modified_bytes")

            val standbyBytes = fields.longOf("standby_bytes")

            val freeBytes = fields.longOf("free_bytes")

            fun partOf(bytes: Long?): Float? {

                val whole = total ?: return null

                if (bytes == null || whole <= 0L) return null

                return (bytes.toDouble() / whole.toDouble()).toFloat()
            }

            val usedNetBytes = when {
                usedBytes == null -> null
                modifiedBytes == null -> usedBytes
                else -> (usedBytes - modifiedBytes).coerceAtLeast(0L)
            }

            return MemorySnapshot(
                totalBytes = total,

                nominalBytes = fields.longOf("nominal_bytes"),

                specText = fields.textValueOf("spec"),

                manufacturer = fields.textValueOf("manufacturer"),

                usedBytes = usedBytes,

                compressedBytes = fields.longOf("compressed_bytes"),

                compressedStatus = fields.statusOf("compressed_bytes"),

                zramOriginalBytes = fields.longOf("zram_original_bytes"),

                zramCompressedBytes = fields.longOf("zram_compressed_bytes"),

                zramUsedBytes = fields.longOf("zram_used_bytes"),

                zramDisksizeBytes = fields.longOf("zram_disksize_bytes"),

                zramSwapUsedBytes = fields.longOf("zram_swap_used_bytes"),

                swapTotalBytes = fields.longOf("swap_total_bytes"),

                swapUsedBytes = fields.longOf("swap_used_bytes"),

                availableBytes = fields.longOf("available_bytes"),

                committedBytes = fields.longOf("committed_bytes"),

                commitLimitBytes = fields.longOf("commit_limit_bytes"),

                cachedBytes = fields.longOf("cached_bytes"),

                reclaimableKernelBytes = fields.longOf("reclaimable_kernel_bytes"),

                nonReclaimableKernelBytes = fields.longOf("nonreclaimable_kernel_bytes"),

                speedRawText = fields.textValueOf("speed_raw"),

                usedSlots = fields.intOf("used_slots"),

                totalSlots = fields.intOf("total_slots"),

                formFactor = fields.textValueOf("form_factor"),

                reservedBytes = fields.longOf("reserved_bytes"),

                reservedStatus = fields.statusOf("reserved_bytes"),

                usedPart = partOf(usedNetBytes),

                modifiedPart = partOf(modifiedBytes),

                standbyPart = partOf(standbyBytes),

                freePart = partOf(freeBytes)
            )
        }

        /** 去掉状态标记后的原值；没有值（`!` 开头）返回 null。 */
        private fun Map<String, String>.valueOf(key: String): String? {

            val raw = this[key] ?: return null

            if (raw.startsWith("!")) return null

            return raw
        }

        private fun Map<String, String>.floatOf(key: String): Float? =
            valueOf(key)?.toFloatOrNull()

        /**
         * `!STATUS` 里的状态文本（ACCESS_DENIED / UNSUPPORTED …）；这一项有值时返回 null。
         *
         * 和 [valueOf] 的分工：valueOf 只关心"有没有值"，这里关心"为什么没值" ——
         * 没权限和根本不支持要显示成不同的东西。
         */
        private fun Map<String, String>.statusOf(key: String): String? {

            val raw = this[key] ?: return null

            return if (raw.startsWith("!")) raw.substring(1) else null
        }

        private fun Map<String, String>.intOf(key: String): Int? = valueOf(key)?.toIntOrNull()

        /**
         * `0,1,2,3` -> [0,1,2,3]；解析不了的项直接跳过 —— 核号缺失只影响那一格的名字，
         * 不影响其它核的对应关系（值列表按位置对齐，不按核号查找）。
         */
        private fun String.toIntList(): List<Int> =
            split(',').mapNotNull { it.trim().toIntOrNull() }

        /**
         * `12.34,8.20,-1.00` -> [12.34, 8.20, -1.0]。
         *
         * 位置必须保持：-1 是"这一核没值"的哨兵，不能被过滤掉，否则后面每一核都会错位。
         * 整列缺失（后端回 `!状态`）时返回空列表，表示"这一列没报"。
         */
        private fun Map<String, String>.floatListOf(key: String): List<Float> =
            valueOf(key)?.split(',')
                ?.map { it.trim().toFloatOrNull() ?: -1f }
                ?: emptyList()

        private fun Map<String, String>.longOf(key: String): Long? = valueOf(key)?.toLongOrNull()

        /** 速率 / 毫秒这类带小数的量；整数串（`1234`）也认。 */
        private fun Map<String, String>.doubleOf(key: String): Double? =
            valueOf(key)?.toDoubleOrNull()

        /** `1` / `0`（后端写法）与 `true` / `false` 都认；其它一律当没值。 */
        private fun Map<String, String>.boolOf(key: String): Boolean? =
            when (valueOf(key)?.trim()?.lowercase()) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> null
            }

        private fun Map<String, String>.textValueOf(key: String): String? =
            valueOf(key)?.takeIf { it.isNotBlank() }
    }
}

/**
 * 一个 CPU 采样点。
 *
 * 时间用进程内的相对时钟（elapsedRealtime），只用于"点与点之间的间隔"，
 * 不落盘、不跨进程、不做绝对日期——所以精度够用，也不受时区/校时影响。
 *
 * 逐核两列是"同一帧的同一批核"，索引一一对应；某个核这一帧没值写 -1f 占位，
 * 位置不能丢，否则整条曲线会串到别的核上。
 *
 * @param timeMs       采样时刻（毫秒）
 * @param percent      整机利用率，0..100
 * @param kernelPercent 内核时间占比，0..100；null = 这一帧算不出（首帧 / 计数器回退）
 * @param corePercents 逐核利用率；空 = 后端没报逐核
 * @param coreKernels  逐核内核时间；与 corePercents 同序
 */
data class CpuUsagePoint(
    val timeMs: Long,
    val percent: Float,
    val kernelPercent: Float? = null,
    val corePercents: List<Float> = emptyList(),
    val coreKernels: List<Float> = emptyList()
)

/**
 * 内存页要显示的一整份数据。
 *
 * 字段全部可空：底层查不到就是 null，页面显示破折号。
 * **不要用 0 顶替 null** —— "这一项是 0" 与 "这一项没读到" 是两件不同的事，
 * 前者能参与计算，后者不能。
 *
 * 现在还没有数据源：`PerformanceSnapshot.from()` 不解这一块，页面拿到 null 就按空帧渲染。
 * 接入时只往 from() 里填字段，页面与文案函数都不用改。
 *
 * 单位一律用字节存：网络协议、/proc、SMBIOS 出来的都是字节或 KB，
 * 换算成 GB / MB 是显示层的事（按量级自动选单位，见 [memorySizeText]）。
 *
 * @param totalBytes            实际内存大小（MemTotal，内核能用的量，图表顶端刻度）
 * @param nominalBytes          标称容量（高通 dt 的 ddr_size，商品上写的那个数）；Linux 给不出就是 null
 * @param specText              内存规格，如 LPDDR5 / LPDDR4X / DDR3（高通 ddr_device_type 枚举换算）
 * @param manufacturer          颗粒厂商代码（dt 的 ddr_manufacturer_id 按 JEDEC 编号换成英文码，
 *                              如 SAMSUNG / MICRON）；读不到 / 编码不在表里就是 null，页面不画这一行。
 *                              显示时走 [BackendDisplayText.memoryVendorText]
 * @param usedBytes             使用中
 * @param compressedBytes       其中已压缩的部分（Windows 的「使用中(已压缩)」括号里那个）
 * @param availableBytes        可用
 * @param committedBytes        已提交（已用）
 * @param commitLimitBytes      提交上限
 * @param cachedBytes           已缓存
 * @param reclaimableKernelBytes    可回收内核缓存
 * @param nonReclaimableKernelBytes 不可回收内核缓存
 * @param speedRawText          速度的原始文本：底层给纯数字就拼成 "3200 MT/秒"，
 *                              给 "不支持" / "获取失败" 这类现成话术就原样摆出来，不做二次加工
 * @param usedSlots             已使用的插槽数（缺省按 1 算）
 * @param totalSlots            插槽总数（缺省按 1 算）
 * @param formFactor            外观规格（SODIMM / 板载 …）；底层给不出就是 null，页面写"其他"
 * @param reservedBytes         为硬件保留的内存（标称容量 - MemTotal，底层算好）
 * @param reservedStatus        上面那个值读不到时的原因
 * @param usedPart              分条图四段的比例（0..1，不用自己凑成 1，显示时会归一）
 * @param modifiedPart
 * @param standbyPart
 * @param freePart
 */
/** 内存页「不支持」的固定文案：底层明确说 Android 上没有这一项。 */
internal val MEMORY_UNSUPPORTED_TEXT: String get() = AppStrings.s(R.string.data_unsupported)

/** 没权限时的占位：一个横线，意思是"也许有，只是这次没读到"。 */
internal const val MEMORY_ACCESS_DENIED_TEXT = "-"

/** 底层字段状态里的"没权限"标记，只有它要和不支持分开显示。 */
internal const val FIELD_STATUS_ACCESS_DENIED = "ACCESS_DENIED"

/** 后端说"这一格这次没给出来"（这台机器确实没有这一项）。 */
internal const val FIELD_STATUS_NOT_AVAILABLE = "NOT_AVAILABLE"

/** 后端说"平台不导出这项信息"。 */
internal const val FIELD_STATUS_UNSUPPORTED = "UNSUPPORTED"

/** 缓存读不到时的文案：这一级确实不存在 / 平台不导出。 */
internal val CACHE_UNSUPPORTED_TEXT: String get() = AppStrings.s(R.string.data_unsupported)

/** 缓存有这一级但读不出来（多半是权限）：写「-」，表示"也许能拿到"。 */
internal const val CACHE_UNKNOWN_TEXT = "-"

data class MemorySnapshot(
    val totalBytes: Long? = null,
    val nominalBytes: Long? = null,
    val specText: String? = null,
    val manufacturer: String? = null,
    val usedBytes: Long? = null,
    val compressedBytes: Long? = null,

    /** zram 原始数据量（mm_stat 第 1 列 orig_data_size）；null = 没有 zram 或没读到 */
    val zramOriginalBytes: Long? = null,

    /** compressedBytes 读不到时的原因；ACCESS_DENIED 表示设备在、只是没权限 → 显示 "-" */
    val compressedStatus: String? = null,

    /** zram 压缩后数据量（第 2 列 compr_data_size） */
    val zramCompressedBytes: Long? = null,

    /** zram 实际占住的物理内存（第 3 列 mem_used_total，与 compressedBytes 同源同值） */
    val zramUsedBytes: Long? = null,

    /** zram 设备容量上限（disksize 累加），和用量分开 */
    val zramDisksizeBytes: Long? = null,

    /** /proc/swaps 里 zram 已用的交换量（压缩前口径，不能当内存占用） */
    val zramSwapUsedBytes: Long? = null,

    /** 交换空间总量：/proc/swaps 所有设备 Size 列累加（设备配置容量）；null = 没读到 */
    val swapTotalBytes: Long? = null,

    /** 交换空间已用：/proc/swaps 所有设备 Used 列累加（压缩前口径） */
    val swapUsedBytes: Long? = null,

    val availableBytes: Long? = null,
    val committedBytes: Long? = null,
    val commitLimitBytes: Long? = null,
    val cachedBytes: Long? = null,
    val reclaimableKernelBytes: Long? = null,
    val nonReclaimableKernelBytes: Long? = null,
    val speedRawText: String? = null,
    val usedSlots: Int? = null,
    val totalSlots: Int? = null,
    val formFactor: String? = null,
    val reservedBytes: Long? = null,

    /** 为硬件保留的内存读不到时的原因（ACCESS_DENIED = 没权限，其它见底层状态表） */
    val reservedStatus: String? = null,

    val usedPart: Float? = null,
    val modifiedPart: Float? = null,
    val standbyPart: Float? = null,
    val freePart: Float? = null
) {

    // ---------- 显示文案 ----------

    /** 图表右上角：实际内存大小（这一行同时是图表顶端那条线的含义）。 */
    fun totalText(): String = memorySizeText(totalBytes)

    /**
     * 名称行右侧：「标称容量 内存规格」，如 `8.0 GB LPDDR5`；缺哪项就少哪项。
     *
     * 容量优先用标称值（商品上写的 8 GB），拿不到才退到 MemTotal ——
     * 后者被保留区啃过一截，会和标签上的数字对不上。
     */
    fun nameText(): String = buildList {

        (nominalBytes ?: totalBytes)?.let { add(memorySizeText(it)) }

        specText?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }

    }.joinToString(" ").ifEmpty { "—" }

    /**
     * 使用中：主值 + 括号里的已压缩部分。
     *
     * 括号里读不到时写破折号，不写 0 MB：0 表示"这台机器没开压缩内存"，是有效值，
     * 用它顶替"读不到"会把两种情况混成一种。
     */
    fun usedText(): String {

        if (usedBytes == null) return "—"

        val compressed = compressedBytes?.let { memorySizeText(it) }
            ?: if (compressedStatus == FIELD_STATUS_ACCESS_DENIED) MEMORY_ACCESS_DENIED_TEXT else "—"

        return "${memorySizeText(usedBytes)} ($compressed)"
    }

    fun availableText(): String = memorySizeText(availableBytes)

    /**
     * 压缩内存（zram）的三个实测数：原始 / 压缩后 / 实际占用，容量有就一起写。
     *
     * 只在真读到 mm_stat 时才出这一行：没有 zram 或读不到就整行不画
     * （和"速度 / 厂商"同一规矩），不摆一串 0 MB 占位。
     * 实占含分配器碎片与元数据，会比"压缩后数据量"略大 —— 这不是算错。
     */
    fun zramDetailText(): String? {

        val original = zramOriginalBytes ?: return null
        val compressed = zramCompressedBytes ?: return null
        val used = zramUsedBytes ?: return null

        // 四种情况的文案顺序由资源串自己定：中文用"原始X → 压缩Y，实占Z"，英文用 "raw X -> Y, in use Z"
        val capacity = zramDisksizeBytes?.takeIf { it > 0 }

        return if (capacity != null) {
            AppStrings.s(
                R.string.perf_zram_detail_capacity,
                memorySizeText(original),
                memorySizeText(compressed),
                memorySizeText(used),
                memorySizeText(capacity)
            )
        } else {
            AppStrings.s(
                R.string.perf_zram_detail,
                memorySizeText(original),
                memorySizeText(compressed),
                memorySizeText(used)
            )
        }
    }

    /**
     * 交换空间一行显示：`已用/总量 单位`，例 `0.8/4.0 GB`。
     *
     * 与「已提交」同一套写法：同一把尺子（取大的那个定单位），单位只写一次。
     * 缺一个就只报另一个；两个都没读到返回 null，整行不画。
     */
    fun swapText(): String? {

        val used = swapUsedBytes
        val total = swapTotalBytes

        if (used == null && total == null) return null

        if (used == null) return memorySizeText(total!!)

        if (total == null) return memorySizeText(used)

        return rangeText(used, total)
    }

    /**
     * 已提交：`已用/上限`。
     *
     * 两个数统一到同一把尺子 —— 取大的那个定单位，小的换算成它的小数（
     * 比如 `1.2/0.8 GB`），单位只在末尾写一次：
     * 一格宽度有限，写两遍单位会把数字挤出去。
     */
    fun committedText(): String {

        val used = committedBytes ?: return "—"

        val limit = commitLimitBytes ?: return memorySizeText(used)

        return rangeText(used, limit)
    }

    /**
     * 已用/总量，例 `8.4/11.0 GB`：左栏那一行的小字用。
     *
     * 与已提交同一套写法（同一把尺子、单位只写一次）；总量拿不到就只报已用。
     */
    fun amountText(): String {

        val used = usedBytes ?: return "—"

        val total = totalBytes ?: return memorySizeText(used)

        return rangeText(used, total)
    }

    /**
     * 两个字节量并排写成 `a/b 单位`。
     *
     * 单位取两个数里大的那个来定（GB 就按 GB 写，都不到 1 GB 就按 MB 写），
     * 小的那个换算成同一单位的小数，单位只在末尾出现一次：
     * 一格宽度有限，写两遍单位会把数字挤出去。
     */
    private fun rangeText(first: Long, second: Long): String {

        val unitBytes = memoryUnitBytes(maxOf(first, second))

        val digits = if (unitBytes == MEMORY_GB_BYTES) 1 else 0

        val firstText = String.format(
            Locale.US, "%.${digits}f", first.toDouble() / unitBytes.toDouble()
        )

        val secondText = String.format(
            Locale.US, "%.${digits}f", second.toDouble() / unitBytes.toDouble()
        )

        return "$firstText/$secondText ${memoryUnitName(unitBytes)}"
    }

    fun cachedText(): String = memorySizeText(cachedBytes)

    fun reclaimableText(): String = memorySizeText(reclaimableKernelBytes)

    fun nonReclaimableText(): String = memorySizeText(nonReclaimableKernelBytes)

    /**
     * 速度：单位是 MT/秒（每秒百万次传输），不是 MHz，别自作主张换算。
     *
     * 底层可能给不出数字。给了纯数字才补单位，给的是别的话术（不支持、获取失败）
     * 就原样显示 —— 这些也算有效信息，不该被吞掉。
     *
     * 只有"底层压根没给这一项"才返回 null，上层据此整行不显示：
     * 没有数据就别占一行破折号位置。
     */
    fun speedText(): String? {

        val raw = speedRawText?.trim()

        if (raw.isNullOrEmpty()) return null

        val numeric = raw.toDoubleOrNull() ?: return raw

        val whole = if (numeric == numeric.toLong().toDouble()) {
            numeric.toLong().toString()
        } else {
            numeric.toString()
        }

        return AppStrings.s(R.string.perf_unit_mt_per_sec, whole)
    }

    /**
     * 已使用的插槽：`已用/总数`。
     *
     * 没数据时按 1/1 显示（底层不接力时画面也是完整的），
     * 只有总数缺失这种半截情况才拿已用数兜住总数，免得出现 "2/1"。
     */
    fun slotsText(): String {

        val used = usedSlots ?: 1

        val total = totalSlots ?: maxOf(used, 1)

        return "$used/$total"
    }

    /** 外观规格：底层给不出就写"其他"（比"未知"读起来像正常答案），这同样不是错误状态。 */
    fun formFactorText(): String = formFactor?.takeIf { it.isNotBlank() } ?: AppStrings.s(R.string.perf_other)

    /**
     * 为硬件保留的内存：有值写容量；没权限写一个横线（提示"也许能拿到"）；
     * 其余失败（没有 ddr_size、数据矛盾、读失败）统一写"不支持"。
     */
    fun reservedText(): String = when {

        reservedBytes != null -> memorySizeText(reservedBytes)

        reservedStatus == FIELD_STATUS_ACCESS_DENIED -> MEMORY_ACCESS_DENIED_TEXT

        else -> MEMORY_UNSUPPORTED_TEXT
    }

    /**
     * 分条图四段的比例，按「使用中 → 已修改 → 备用 → 可用」的顺序。
     *
     * 缺失的段用默认值补齐，最后归一成合计 1.0（画布的宽度就是总容量）。
     * 数据源还没接，所以现在四段全是默认值 —— 图形能先照设计画出来。
     */
    fun breakdownParts(): List<Float> {

        val parts = listOf(
            usedPart ?: DEFAULT_USED_PART,
            modifiedPart ?: DEFAULT_MODIFIED_PART,
            standbyPart ?: DEFAULT_STANDBY_PART,
            freePart ?: DEFAULT_FREE_PART
        ).map { it.coerceAtLeast(0f) }

        val total = parts.sum()

        if (total <= 0f) return DEFAULT_PARTS

        return parts.map { it / total }
    }

    companion object {

        /** 还没有数据源时的默认四段比例（只为把图画出来看版式，合计 1）。 */
        private const val DEFAULT_USED_PART = 0.45f

        private const val DEFAULT_MODIFIED_PART = 0.05f

        private const val DEFAULT_STANDBY_PART = 0.28f

        private const val DEFAULT_FREE_PART = 0.22f

        val DEFAULT_PARTS: List<Float> = listOf(
            DEFAULT_USED_PART, DEFAULT_MODIFIED_PART, DEFAULT_STANDBY_PART, DEFAULT_FREE_PART
        )
    }
}


/**
 * 一台磁盘设备（一次设备快照里的一条磁盘记录）。
 *
 * 字段按"底层能拿到什么就给什么"排：全部可空 —— 没有数据就是 null，页面写破折号，
 * 不拿 0 冒充（0% 与 读不到 是两件事）。将来 Linux 侧接上后，只需按 id 填这张表，
 * 页面与管理器都不用改。
 *
 * @param id            底层给的设备索引（disk0 / disk1）：只是本帧数据的查找键，
 *                      跟着排序后的枚举位置走；认"是哪一块盘"要看 node（块名）
 * @param name          设备名（型号串），显示在页面右上角；未实现前保持 null -> 破折号
 * @param mediaType     介质类型：HDD / SSD / UFS（底层字段一）
 * @param busType       总线类型：SCSI / NVMe / USB（底层字段二），与介质合成"SSD (NVMe)"
 * @param capacityBytes 容量
 * @param formattedBytes 已格式化容量（分区表算出来的可用部分）
 * @param systemDisk    是否系统盘（true/false；null = 底层没报，显示破折号）
 * @param pageFile      是否存放页面文件（同上）
 * @param activityPercent 活动时间百分比（0..100）：有 I/O 请求在排队的时间占比
 * @param avgResponseMs   平均响应时间（毫秒）
 * @param readBytesPerSec 读取速度（字节/秒），图表与统计块的"读取速度"
 * @param writeBytesPerSec 写入速度（字节/秒），同上
 */
data class DiskSnapshot(
    val id: String? = null,
    val name: String? = null,

    /** 挂在的块设备名（sda / sdb / mmcblk0 / nvme0n1），只用于标题里的括号"磁盘 0 (sda)" */
    val node: String? = null,

    /**
     * 硬件标识（wwid / 序列号）：**认盘的最终依据**。
     *
     * 块名会被内核复用（拔掉 sdb 再插一块还叫 sdb），只有这一格能证明"还是原来那块盘"；
     * 部分平台不提供（null），这时退回 node。
     */
    val wwid: String? = null,
    val mediaType: String? = null,
    val busType: String? = null,
    val capacityBytes: Long? = null,
    val formattedBytes: Long? = null,
    val systemDisk: Boolean? = null,
    val pageFile: Boolean? = null,
    val activityPercent: Float? = null,
    val avgResponseMs: Double? = null,
    val readBytesPerSec: Double? = null,
    val writeBytesPerSec: Double? = null
) {

    // ---------- 显示文案 ----------

    /** 页面右上角的设备名；底层还没给就写破折号（占位不是 0）。 */
    fun nameText(): String = name?.takeIf { it.isNotBlank() }?.trim() ?: "—"

    /**
     * 类型：介质 + 总线合成一条，例 `SSD (NVMe)`、`HDD (SCSI)`、`UFS (SCSI)`。
     *
     * 底层给的是两个独立字段：只给一个就只写那一个，两个都没给写破折号。
     * 括号用半角，与项目里其它"值 (说明)"的写法一致。
     */
    fun typeText(): String {

        val media = mediaType?.takeIf { it.isNotBlank() }?.trim()

        val bus = busType?.takeIf { it.isNotBlank() }?.trim()

        return when {

            media != null && bus != null -> "$media ($bus)"

            media != null -> media

            bus != null -> "($bus)"

            else -> "—"
        }
    }

    /** 活动时间（左栏第三行、统计块第一行）：整数百分比，读不到写破折号。 */
    fun activityText(): String = activityPercent?.let { diskPercentText(it) } ?: "—"

    /**
     * 平均响应时间：**固定 0.1 毫秒一档**。
     *
     * 两条规则都是刻意定死的：
     * 1. 只到 0.1：后端已经把 time_in_queue 的整毫秒量化摊到 0.1 ms 级，
     *    再往下给 0.01 的位数只是把噪声当精度显示；
     * 2. 整数不带小数点：0.0 就写「0 毫秒」，4.0 就写「4 毫秒」——
     *    "0.00 毫秒"这种写法既没信息量又扎眼，和速率的排版习惯保持一致。
     */
    fun responseText(): String {

        val value = avgResponseMs ?: return "—"

        val rounded = round(value * 10.0) / 10.0

        if (rounded <= 0.0) return AppStrings.s(R.string.perf_zero_ms)

        val text = if (rounded % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", rounded)
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }

        return AppStrings.s(R.string.perf_unit_ms, text)
    }

    /** 读取速度：量级自动选单位（KB/秒 / MB/秒 …）。 */
    fun readSpeedText(): String = diskRateText(readBytesPerSec)

    /** 写入速度：同上。 */
    fun writeSpeedText(): String = diskRateText(writeBytesPerSec)

    /** 容量：按量级选 MB / GB / TB，低于 100 的值保留一位小数（60.0 MB）。 */
    fun capacityText(): String = diskCapacityText(capacityBytes)

    /** 已格式化容量：同一套写法。 */
    fun formattedText(): String = diskCapacityText(formattedBytes)

    /** 系统盘：是 / 否 / 破折号。 */
    fun systemDiskText(): String = yesNoText(systemDisk)

    /** 页面文件：是 / 否 / 破折号。 */
    fun pageFileText(): String = yesNoText(pageFile)

    private fun yesNoText(value: Boolean?): String = when (value) {
        true -> AppStrings.s(R.string.data_yes)
        false -> AppStrings.s(R.string.data_no)
        null -> "—"
    }
}

/** 百分比文案：整数（磁盘的活动时间是"有没有在忙"，不需要小数）。 */
internal fun diskPercentText(percent: Float): String =
    String.format(Locale.US, "%.0f%%", percent.coerceIn(0f, 100f))

/**
 * 容量文案（容量 / 已格式化共用）。
 *
 * 单位按量级自动选（TB / GB / MB），数值低于 100 时保留一位小数（`60.0 GB`），
 * 到 100 以上就取整（`512 GB`）—— 这是项目里"大数字不带小数"的既定口径，
 * 也是用户对这两行的明确要求。null 或负数写破折号。
 */
internal fun diskCapacityText(bytes: Long?): String {

    if (bytes == null || bytes < 0L) return "—"

    val unitBytes: Long

    val unitName: String

    when {

        bytes >= 1024L * 1024L * 1024L * 1024L -> {
            unitBytes = 1024L * 1024L * 1024L * 1024L
            unitName = "TB"
        }

        bytes >= 1024L * 1024L * 1024L -> {
            unitBytes = 1024L * 1024L * 1024L
            unitName = "GB"
        }

        else -> {
            unitBytes = 1024L * 1024L
            unitName = "MB"
        }
    }

    val value = bytes.toDouble() / unitBytes.toDouble()

    // 0 是"确实空"，写成 0 而不是 0.0：与速率那边同一条规矩
    if (value <= 0.0) return "0 $unitName"

    val pattern = if (value < 100.0) "%.1f %s" else "%.0f %s"

    return String.format(Locale.US, pattern, value, unitName)
}

/**
 * 速率文案（统计块 / 图表右上角的量程标注共用）。
 *
 * 量级自动选单位（B/秒 / KB/秒 / MB/秒 / GB/秒），1024 进制；
 * 小于 100 的值保留一位小数，100 以上取整；0 写 `0 KB/秒`（真零是有效读数）。
 * null 写破折号。
 */
internal fun diskRateText(bytesPerSec: Double?): String {

    if (bytesPerSec == null || bytesPerSec < 0.0) return "—"

    val kb = 1024.0

    val unit = when {

        bytesPerSec >= kb * kb * kb -> Triple(kb * kb * kb, AppStrings.s(R.string.perf_unit_gbps), 3)
        bytesPerSec >= kb * kb -> Triple(kb * kb, AppStrings.s(R.string.perf_unit_mbps), 2)
        bytesPerSec >= kb -> Triple(kb, AppStrings.s(R.string.perf_unit_kbps), 1)
        else -> Triple(1.0, AppStrings.s(R.string.perf_unit_bps), 0)
    }

    val value = bytesPerSec / unit.first

    if (value <= 0.0) return "0 ${unit.second}"

    // 已经是整数就别留 .0（100 KB/秒 比 100.0 KB/秒 好读，量程标签尤其如此）；
    // 真正带小数的（2.5 MB/秒）才保留一位
    val rounded = round(value)

    val pattern = when {

        value >= 100.0 -> "%.0f %s"

        abs(value - rounded) < 0.05 -> "%.0f %s"

        else -> "%.1f %s"
    }

    return String.format(Locale.US, pattern, value, unit.second)
}

/**
 * 速率文案的"整齐版"：先取整到一位有效数字，再按 [diskRateText] 排版。
 *
 * 只给图上的标注用（量程的 60% 那条线）。60% 未必落在好看的数上 ——
 * 1 MB/秒 的 60% 是 614 KB/秒、2.5 MB/秒 的 60% 是 1.5 MB/秒 ——
 * 这里把尾数收到 1 / 1.5 / 2 / 2.5 / 3 / 4 / 5 / 6 / 8 这些档，
 * 于是标注永远读作 600 KB/秒、1.5 MB/秒，而不是一串小数。
 */
internal fun diskRateTextNice(bytesPerSec: Double?): String {

    if (bytesPerSec == null || bytesPerSec < 0.0) return "—"

    if (bytesPerSec == 0.0) return AppStrings.s(R.string.perf_zero_kbps)

    // 10 的整数次幂：量级
    val magnitude = 10.0.pow(floor(log10(bytesPerSec)))

    val mantissa = bytesPerSec / magnitude

    // 人眼习惯的尾数档；10.0 是给 9.x 进位用的
    val steps = doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)

    val nearest = steps.minByOrNull { abs(it - mantissa) } ?: mantissa

    return diskRateText(nearest * magnitude)
}

/**
 * 字节数 -> 人能读的长度，**按量级自动挑单位**（不是固定 GB 或固定 MB）。
 *
 * 规则与大数字的读法一致：GB 留一位小数（16.0 GB），MB / KB 取整（512 MB），
 * 不足 1 KB 写字节。null 或负数写破折号，不当成 0。
 */
internal fun memorySizeText(bytes: Long?): String {

    if (bytes == null || bytes < 0L) return "—"

    // 0 也按 MB 写：这一格几乎都在 MB 以上量级，写 0 B 会被当成没格式化干净
    if (bytes == 0L) return "0 MB"

    val kilobytes = 1024.0

    return when {

        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f GB", bytes / (kilobytes * kilobytes * kilobytes))

        bytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.0f MB", bytes / (kilobytes * kilobytes))

        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / kilobytes)

        else -> "$bytes B"
    }
}

/** 内存长度的四个尺子，和 [memorySizeText] 用的是同一组 1024 进制。 */
private const val MEMORY_KB_BYTES = 1024L

private const val MEMORY_MB_BYTES = 1024L * 1024L

private const val MEMORY_GB_BYTES = 1024L * 1024L * 1024L

/** 按参考量级挑一把尺子：参考值够大就用大单位，最小到字节。 */
private fun memoryUnitBytes(reference: Long): Long = when {

    reference >= MEMORY_GB_BYTES -> MEMORY_GB_BYTES

    reference >= MEMORY_MB_BYTES -> MEMORY_MB_BYTES

    reference >= MEMORY_KB_BYTES -> MEMORY_KB_BYTES

    else -> 1L
}

/** 尺子对应的单位名，与 [memorySizeText] 输出的那几个写法一致。 */
private fun memoryUnitName(unitBytes: Long): String = when (unitBytes) {

    MEMORY_GB_BYTES -> "GB"

    MEMORY_MB_BYTES -> "MB"

    MEMORY_KB_BYTES -> "KB"

    else -> "B"
}

/**
 * 一块网卡在这一帧里的状态。
 *
 * 字段全可空，含义统一：**null = 读不到**，不是 0。
 * 页面对 null 的处理按用户口径分两档：
 * - 明细行（SSID / 连接类型 / 地址 / 信号强度）→ 写"不支持"；
 * - 速率（发送 / 接收）→ 写 0，因为"这块网卡现在没流量"与"还没有基准"，
 *   在展示上都是 0 Kb，不必区分。
 *
 * [role] 是底层给的事实分型（WIFI / ETHERNET / CELLULAR / OTHER）：
 * 底层把 /sys/class/net 里的接口**全部**报上来（虚拟网卡也算 OTHER），
 * 由 PerformanceManager 决定把哪一类注册成性能页设备 —— 现在注册三类：
 * 无线局域网（WIFI，页面叫 WLAN / Wi-Fi，要连上网），手机移动网络（CELLULAR，
 * 页面叫手机网络，要有地址），有线这一族（ETHERNET 与 OTHER，页面统一叫以太网，
 * 也要有地址）。
 */
data class NetworkSnapshot(
    val id: String? = null,
    val role: String? = null,
    val iface: String? = null,
    val name: String? = null,
    val ssid: String? = null,
    val linkType: String? = null,
    val freqMhz: String? = null,
    val ipv4: String? = null,
    val ipv6: String? = null,
    /**
     * 底层事实：这条链路有没有"真在用"的地址（IPv4 或非链路本地 IPv6）。
     *
     * 三态：null = 底层没发（读失败或老后端），true / false = 底层查过给出的结论。
     * 只有手机网络的注册过滤用这一格 —— 无线那边看的是 SSID（连上网才有）。
     */
    val hasDataAddress: Boolean? = null,
    val signalPercent: Float? = null,
    val sendBytesPerSec: Double? = null,
    val receiveBytesPerSec: Double? = null
) {

    // ---------- 判定 ----------

    /**
     * 是不是无线局域网（802.11）网卡。
     *
     * 两种来源都认：底层给 role=WIFI；或底层没给 role（老后端 / 调试注入）时，
     * 名字里带 wlan / wifi / wl 前缀也算 —— 宁可多认一块，也不要漏掉真网卡。
     */
    fun isWirelessLan(): Boolean {

        val value = role?.trim()?.uppercase()

        if (!value.isNullOrEmpty()) return value == NET_ROLE_WIFI

        val label = (iface ?: name)?.trim()?.lowercase() ?: return false

        return label.startsWith("wlan") || label.startsWith("wifi") || label.startsWith("wl")
    }

    /**
     * 底层给的角色键：去空白、转大写；没给就是 null。
     *
     * 单独抽出来是因为 [isCellular] / [isEthernet] 用同一口径，
     * 免得一处 trim 一处不 trim，出现"同一个 role 两种判定"。
     */
    private fun roleKey(): String? = role?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    /**
     * 是不是手机移动网络（cellular）。
     *
     * 只认底层给的 role=CELLULAR：rmnet / ccmni / pdp / wwan 那一套名字判定在
     * C 侧的 net_role_of() 里，这边不重复猜一遍 —— 两边各写一份规则，
     * 迟早出现"底层说是 WLAN、页面说是手机网络"这种打架。
     */
    fun isCellular(): Boolean = roleKey() == NET_ROLE_CELLULAR

    /**
     * 是不是有线以太网（底层挂在真实总线上的那一种）。
     *
     * 只认 role=ETHERNET；role=OTHER 的那些也算"以太网适配器"，见 [isWired]。
     */
    fun isEthernet(): Boolean = roleKey() == NET_ROLE_ETHERNET

    /** 是不是底层分不出型号的其它接口（tun / ipsec / dummy / 没有 device 链接的有线网口 …）。 */
    fun isOther(): Boolean = roleKey() == NET_ROLE_OTHER

    /**
     * 是不是"以太网这一类"：ETHERNET 与 OTHER 合在一起。
     *
     * 手机 / 平板上的 USB 网卡、扩展坞网口，有的带 device 链接（判成 ETHERNET），
     * 有的没有（落到 OTHER）。对用户来说都是一根网线，页面没必要分两套，
     * 所以类型名统一写「以太网」，序号也在这两类之间连续数（以太网、以太网 1 …）。
     *
     * 需要知道的一层：tun0 / ipsec0 这类 VPN 接口也在 OTHER 里，只要有真地址
     * 就会显示成「以太网」—— 这是"其它适配器也算以太网"这个口径的直接结果。
     * 以后要单独把 VPN 排除，只需在注册过滤里加一条名字前缀判断。
     */
    fun isWired(): Boolean = isEthernet() || isOther()

    /**
     * 这块网卡有没有"正在用"的地址。
     *
     * 优先用底层给的 [hasDataAddress]：它按"非链路本地"判定，能把一堆只挂着 fe80::
     * 的 rmnet 空壳筛掉（手机上的移动网络接口常常有七八个：rmnet_data0..7、rmnet_ipa0、
     * ccmni0 …，全注册会把左栏塞满）。底层没发这一格（读失败 / 老后端）时才退回
     * "有 ipv4 或 ipv6 就算有" —— 老逻辑的口径，宁可多显示一块也不漏掉真的那条。
     */
    fun hasAddress(): Boolean =
        hasDataAddress ?: (!ipv4.isNullOrBlank() || !ipv6.isNullOrBlank())

    /**
     * SSID 这一行要不要出现。
     *
     * 只有无线有 SSID。手机网络与以太网都没有这个概念，整行不画 ——
     * 写成"不支持"只是噪音。以后若要给移动网络补"运营商 / 网络名"，
     * 另起一行，别直接复用 SSID 这行。
     */
    val showsSsidRow: Boolean get() = !isCellular() && !isWired()

    /**
     * 信号强度这一行要不要出现。
     *
     * 现在底层只能读无线的信号（iw 那条路），移动网络与以太网都没有这份数据，
     * 所以这两类整行不画。判据写成"有没有值"而不是按类型写死：
     * 以后底层把 signal_percent 补上来，这行自动出现，这里不用再改。
     */
    val showsSignalRow: Boolean
        get() = signalPercent != null || (!isCellular() && !isWired())

    // ---------- 显示文案 ----------

    /** 页面右上角的适配器名：型号（没有型号时底层发的是接口名），都没有才写"不支持"。 */
    fun nameText(): String = textOrUnsupported(name ?: iface)


    fun ssidText(): String = textOrUnsupported(ssid)

    /**
     * 连接类型。
     *
     * 无线：底层给什么写什么（802.11ax / 802.11ac …），读不到写"不支持"。
     * 手机网络：底层现在拿不到制式（4G / 5G / LTE 要 TelephonyManager 那边的数据，
     * 走的是另一条通路），按产品口径默认写「手机网络」。
     * 以太网：底层不发 link_type（网线也没有 802.11 代际这回事），默认写「以太网」。
     * 等以后底层把 link_type 发上来（比如 "5G NR" / "1000BASE-T"），
     * 第一个分支就直接显示真值 —— 升级点只在这一处。
     */
    fun linkTypeText(): String {

        val value = linkType?.trim()

        return when {
            !value.isNullOrEmpty() -> value
            isCellular() -> NETWORK_CELLULAR_LINK_TEXT
            isWired() -> NETWORK_WIRED_LINK_TEXT
            else -> NETWORK_UNSUPPORTED_TEXT
        }
    }

    fun ipv4Text(): String = textOrUnsupported(ipv4)

    fun ipv6Text(): String = textOrUnsupported(ipv6)

    /** 信号强度：没有百分比就写"不支持"；有就按整数百分比写。 */
    fun signalText(): String = signalPercent?.let { diskPercentText(it) } ?: NETWORK_UNSUPPORTED_TEXT

    /** 发送速率：没有计数就写 0 Kb（不是破折号，与截图一致）。 */
    fun sendText(): String = networkRateText(sendBytesPerSec)

    /** 接收速率：同上。 */
    fun receiveText(): String = networkRateText(receiveBytesPerSec)

    private fun textOrUnsupported(value: String?): String =
        value?.takeIf { it.isNotBlank() }?.trim() ?: NETWORK_UNSUPPORTED_TEXT
}

/** 网卡页里"读不到"的统一文案。模型层与页面层共用一份，免得两处写出不同字。 */
internal val NETWORK_UNSUPPORTED_TEXT: String get() = AppStrings.s(R.string.data_unsupported)

/** 底层 role 字段的取值（与 C 侧 TM_NET_ROLE_* 同一个字，一边改两边都得改）。 */
internal const val NET_ROLE_WIFI = "WIFI"

internal const val NET_ROLE_CELLULAR = "CELLULAR"

internal const val NET_ROLE_ETHERNET = "ETHERNET"

internal const val NET_ROLE_OTHER = "OTHER"

/**
 * 手机网络的"连接类型"默认文案。
 *
 * 底层暂时给不出 4G / 5G / LTE 这类制式，先按产品口径写「手机网络」；
 * 以后底层发了 link_type，[NetworkSnapshot.linkTypeText] 会优先用真值。
 */
internal val NETWORK_CELLULAR_LINK_TEXT: String get() = AppStrings.s(R.string.perf_link_cellular)

/**
 * 以太网的"连接类型"默认文案。
 *
 * 底层对有线接口不发 link_type（没有 802.11 代际，也没读网口速率），
 * 先按产品口径写「以太网」；以后底层发 "1000BASE-T" 之类的真值时会自动盖过去。
 */
internal val NETWORK_WIRED_LINK_TEXT: String get() = AppStrings.s(R.string.perf_link_wired)

/**
 * 网卡速率文案：按量级选 Kb / Mb（抄 Windows 的口径，写"Kbps"），
 * 低于 100 的值留一位小数（`24.0 Kbps`），100 以上取整；
 * null（没有计数）与 0 都写 `0 Kbps`。
 */
internal fun networkRateText(bytesPerSec: Double?): String {

    val bitsPerSec = (bytesPerSec ?: 0.0) * 8.0

    if (bitsPerSec <= 0.0) return "0 Kbps"

    val kilobit = 1000.0

    return when {

        bitsPerSec >= kilobit * 1000.0 -> {

            val value = bitsPerSec / (kilobit * 1000.0)

            if (value < 100.0) String.format(Locale.US, "%.1f Mbps", value)
            else String.format(Locale.US, "%.0f Mbps", value)
        }

        else -> {

            val value = bitsPerSec / kilobit

            if (value < 100.0) String.format(Locale.US, "%.1f Kbps", value)
            else String.format(Locale.US, "%.0f Kbps", value)
        }
    }
}


/**
 * 一张显卡交给页面的数据（只读快照）。
 *
 * 与磁盘 / 网卡同一套规矩：字段都可以读不到。文案约定分三种，不要混：
 *   1) 读到了     —— 直接写值；0% / 0.0 GB 都是合法值，不能被当成"没有"；
 *   2) 明确不支持 —— 写「不支持」（这个平台根本没有这一格，例如驱动日期、专用 GPU 内存）；
 *   3) 取不到     —— 写「-」（有这一格，这次没读出来）。
 *
 * 键名与 C 侧 build_gpu_devices 一一对应：
 *   name / node / vendor / gpu_usage / gpu_temp_c /
 *   dedicated_total_bytes / dedicated_used_bytes /
 *   shared_total_bytes / shared_used_bytes /
 *   driver_version / driver_date / gfx_api / gfx_api_version /
 *   vulkan_version / opengl_version（两路各一份，NONE = 查过但没有） /
 *   pci_bus / pci_device / pci_function / reserved_bytes
 *
 * 共享显存这一格有两个来源：桌面卡的 DRM gtt；手机的 UMA 走高通 KGSL 的
 * `mapped`（含义是 GPU 当前映射住的内存，即 GPU Mapped Memory），
 * 总量按 MemTotal 记 —— 不是厂商给的显存上限，而是能被 GPU 映射的内存池。
 */
data class GpuSnapshot(
    val id: String? = null,
    val name: String? = null,

    /** 这张卡是从哪个 sysfs 节点发现的（/sys/class/kgsl/kgsl-3d0 这类），
     *  名字抓不到时作为适配器名的兜底 */
    val node: String? = null,

    /** 厂商标签（QUALCOMM / MEDIATEK / SAMSUNG / GOOGLE / ARM / IMAGINATION …）：说明这张卡是哪家的 */
    val vendor: String? = null,
    val utilizationPercent: Float? = null,
    val temperatureC: Int? = null,
    val dedicatedTotalBytes: Long? = null,
    val dedicatedUsedBytes: Long? = null,
    val sharedTotalBytes: Long? = null,
    val sharedUsedBytes: Long? = null,

    /** Vulkan 版本串（如 `1.1`）；`NONE` = 查过但没有，null = 这次没查成 */
    val vulkanVersion: String? = null,

    /** OpenGL ES 版本串（如 `3.2`）；`NONE` = 查过但没有，null = 这次没查成 */
    val openglVersion: String? = null,
    val driverVersion: String? = null,
    val driverDate: String? = null,
    val gfxApi: String? = null,
    val gfxApiVersion: String? = null,
    /**
     * PCI 物理位置的三段（后端发的是三个整数，不是现成文案）。
     * 三个都为 null = 不是 PCI 设备（SoC 核显），页面显示「不支持」。
     */
    val pciBus: Int? = null,
    val pciDevice: Int? = null,
    val pciFunction: Int? = null,
    val reservedBytes: Long? = null
) {

    // ---------- 显示文案 ----------

    /** 适配器名：型号没有时底层发的是内核节点名，两个都没有才写「-」。 */
    fun nameText(): String = (name ?: node)?.takeIf { it.isNotBlank() }?.trim() ?: GPU_UNKNOWN_TEXT

    /** 3D 利用率：读数失败写「-」，0% 是合法值。 */
    fun utilizationText(): String =
        utilizationPercent?.let { String.format(Locale.US, "%.0f%%", it.coerceIn(0f, 100f)) }
            ?: GPU_UNKNOWN_TEXT

    /** 温度：读不到就「不支持」（Android 上很多机型不暴露 GPU 热区）。 */
    fun temperatureText(): String = temperatureC?.let { "$it °C" } ?: GPU_NO_VALUE_TEXT

    /** 专用显存总量（图右侧刻度）：`4.0 GB`，读不到写「不支持」。 */
    fun dedicatedTotalText(): String = gpuMemoryText(dedicatedTotalBytes)

    /** 专用显存"已用/总量"（统计格里的 0.0/4.0 GB）。 */
    fun dedicatedUsageText(): String = gpuUsageOf(dedicatedUsedBytes, dedicatedTotalBytes)

    /** 共享显存总量（图右侧刻度）。 */
    fun sharedTotalText(): String = gpuMemoryText(sharedTotalBytes)

    /** 共享显存"已用/总量"。 */
    fun sharedUsageText(): String = gpuUsageOf(sharedUsedBytes, sharedTotalBytes)

    /**
     * 「GPU 内存」那一格：专用 + 共享的合计（Windows 也是这么算的）。
     *
     * 两类里只认有值的那几项；一格都读不到才写「不支持」。
     */
    fun totalMemoryUsageText(): String {

        val total = sumOfKnown(dedicatedTotalBytes, sharedTotalBytes) ?: return GPU_UNSUPPORTED_TEXT

        val used = sumOfKnown(dedicatedUsedBytes, sharedUsedBytes) ?: return GPU_UNSUPPORTED_TEXT

        return "${gpuMemoryValueText(used)}/${gpuMemoryValueText(total)}"
    }

    fun driverVersionText(): String = textOrUnsupported(driverVersion)

    fun driverDateText(): String = textOrUnsupported(driverDate)

    /**
     * 图形 API 版本（原版 Windows 任务管理器的「DirectX 版本」那一格）。
     *
     * Android / Linux 上要换成它们自己的图形库，按优先级：
     *   1) 支持 Vulkan —— `Vulkan 1.1`；
     *   2) 只有 OpenGL —— `OpenGL ES 3.2`；
     *   3) 两样都没有 —— `不支持`；
     *   4) 整格没查成（底层没发这个键） —— `-`。
     */
    fun gfxApiText(): String = when (gfxApiName()) {

        null, "" -> GPU_UNKNOWN_TEXT

        "NONE" -> GPU_UNSUPPORTED_TEXT

        "VULKAN" -> "Vulkan ${gfxApiVersion?.takeIf { it.isNotBlank() } ?: GPU_UNKNOWN_TEXT}"

        "OPENGL" -> "OpenGL ES ${gfxApiVersion?.takeIf { it.isNotBlank() } ?: GPU_UNKNOWN_TEXT}"

        else -> gfxApiName() ?: GPU_UNKNOWN_TEXT
    }

    /** 底层报的图形库名，统一大写去空白，比较时才不用到处 trim。 */
    private fun gfxApiName(): String? = gfxApi?.trim()?.uppercase()

    /** 底层是不是"分字段"报的图形库（新后端才发 vulkan_version / opengl_version）。 */
    private val apiReportedSplit: Boolean
        get() = vulkanVersion != null || openglVersion != null

    /** 分字段报的某一路上有没有值：null = 没查成，`NONE` = 查过但确实没有。 */
    private fun apiPresent(value: String?): Boolean {

        val text = value?.trim()?.uppercase() ?: return false

        return text != "NONE"
    }

    /**
     * 「Vulkan 版本」这一行要不要摆出来。
     *
     * 两行各自独立，底层报了什么就摆什么：
     *   - 两个都有版本 → 两行都摆；
     *   - 只有一样 → 只摆那一样，另一行整行不画；
     *   - 都报 `NONE`（都没有）→ 两行都不摆。
     * 旧后端（只发 gfx_api 一个键）沿用原来的二选一，免得界面版式突变。
     */
    val showsVulkanRow: Boolean
        get() = if (apiReportedSplit) apiPresent(vulkanVersion) else gfxApiName() != "OPENGL"

    /** 「OpenGL ES 版本」这一行要不要摆出来（规则与 [showsVulkanRow] 对称）。 */
    val showsOpenGlRow: Boolean
        get() = if (apiReportedSplit) apiPresent(openglVersion) else gfxApiName() != "VULKAN"

    /** Vulkan 版本那一行的取值：有版本写版本，旧后端退到 gfx_api 的口径。 */
    fun vulkanVersionText(): String = when {

        apiPresent(vulkanVersion) -> "Vulkan ${vulkanVersion?.trim() ?: GPU_UNKNOWN_TEXT}"

        !apiReportedSplit -> gfxApiText()

        else -> GPU_UNKNOWN_TEXT
    }

    /** OpenGL ES 版本那一行的取值，口径同上。 */
    fun openGlVersionText(): String = when {

        apiPresent(openglVersion) -> "OpenGL ES ${openglVersion?.trim() ?: GPU_UNKNOWN_TEXT}"

        !apiReportedSplit -> when (gfxApiName()) {

            "OPENGL" -> "OpenGL ES ${gfxApiVersion?.takeIf { it.isNotBlank() } ?: GPU_UNKNOWN_TEXT}"

            "NONE" -> GPU_UNSUPPORTED_TEXT

            else -> GPU_UNKNOWN_TEXT
        }

        else -> GPU_UNKNOWN_TEXT
    }

    /** 物理位置：由三个整数拼成本地语言文案，缺任何一段都算「不支持」。 */
    fun locationText(): String = BackendDisplayText.pciLocationText(pciBus, pciDevice, pciFunction)

    /** 为硬件保留的内存：Android 上没有这一格，读不到就「不支持」。 */
    fun reservedText(): String = reservedBytes?.let { gpuMemoryValueText(it) } ?: GPU_UNSUPPORTED_TEXT

    private fun textOrUnsupported(value: String?): String =
        value?.takeIf { it.isNotBlank() }?.trim() ?: GPU_UNSUPPORTED_TEXT

    private fun gpuUsageOf(used: Long?, total: Long?): String {

        if (total == null || total <= 0L || used == null || used < 0L) return GPU_UNSUPPORTED_TEXT

        return "${gpuMemoryValueText(used)}/${gpuMemoryValueText(total)}"
    }

    private fun sumOfKnown(first: Long?, second: Long?): Long? {

        if (first == null && second == null) return null

        return (first ?: 0L) + (second ?: 0L)
    }
}

/** GPU 页里"明确不支持"的统一文案。 */
internal val GPU_UNSUPPORTED_TEXT: String get() = AppStrings.s(R.string.data_unsupported)

/** GPU 页里"这次没读出来"的统一文案。 */
internal const val GPU_UNKNOWN_TEXT = "-"

/** 温度这一格的"没有这个能力"专属文案：Windows 那张页就是这么写的（"无"），
 * 与其它字段的"不支持"区分开，免得看起来像"读不到"。 */
internal val GPU_NO_VALUE_TEXT: String get() = AppStrings.s(R.string.perf_none_short)

/**
 * 显存数值：固定按 GB、一位小数（`4.0 GB`），和 Windows 那一页的写法一致。
 *
 * 显存这种量级不会出现几百 MB 的"总量"，固定单位比自动挑单位更好读；
 * 读不到写「不支持」。
 */
internal fun gpuMemoryText(bytes: Long?): String =
    bytes?.takeIf { it >= 0L }?.let { gpuMemoryValueText(it) } ?: GPU_UNSUPPORTED_TEXT

private fun gpuMemoryValueText(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
