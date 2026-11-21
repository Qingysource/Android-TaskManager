package com.android.taskmanager.data

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

    /** 处理器商品名（人看得懂的名字）。由 ELF 侧补实现，现在通常是 null。 */
    val cpuName: String? = null,

    /** 虚拟化方案，形如 已启用(方案名) / 已禁用 / 不支持。由 ELF 侧补实现。 */
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

    /** 二级缓存总量（KB）。 */
    val l2CacheKb: Int? = null,

    /** 三级缓存总量（KB）；null = 这台机器没有 L3。 */
    val l3CacheKb: Int? = null,

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
     * 现在的后端只发 `name` 一个字段（适配器型号），SSID / 连接类型 / 地址 / 信号强度
     * 还没有数据源，页面写"不支持"；发送 / 接收计数没有就写 0。
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

    /** 一级缓存按 KB 显示。 */
    fun l1Text(): String = l1CacheKb?.let { "$it KB" } ?: "—"

    /** 二级缓存按 MB 显示（一位小数）。 */
    fun l2Text(): String = l2CacheKb?.let { megabyteText(it) } ?: "—"

    /** 三级缓存：没有这一级就说"不支持"，不要说 0 MB。 */
    fun l3Text(): String = l3CacheKb?.let { megabyteText(it) } ?: "不支持"

    /**
     * 虚拟化：后端给什么就显示什么（"不支持 / 已禁用 / 已启用(KVM)" 整串直出）。
     *
     * 返回 null = 整行不出现，只有一种情况：本程序自己跑在虚拟机里。
     */
    fun virtualizationText(): String? =
        if (isVirtualMachine) null else (virtualization ?: "不支持")

    /** 虚拟机：只在确实是虚拟机时给"是"，其余一律整行不出现（没有"否"这个状态）。 */
    fun virtualMachineText(): String? = if (isVirtualMachine) "是" else null

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
            true -> "是"
            false -> "否"
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

                l2CacheKb = cpuFields.intOf("l2_cache_kb"),

                l3CacheKb = cpuFields.intOf("l3_cache_kb"),

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
         *   name / media / bus / capacity_bytes / formatted_bytes /
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

                utilizationPercent = device.fields.floatOf("gpu_usage"),

                temperatureC = device.fields.intOf("gpu_temp_c"),

                dedicatedTotalBytes = device.fields.longOf("dedicated_total_bytes"),

                dedicatedUsedBytes = device.fields.longOf("dedicated_used_bytes"),

                sharedTotalBytes = device.fields.longOf("shared_total_bytes"),

                sharedUsedBytes = device.fields.longOf("shared_used_bytes"),

                driverVersion = device.fields.textValueOf("driver_version"),

                driverDate = device.fields.textValueOf("driver_date"),

                // 图形 API：VULKAN / OPENGL / NONE（明确两样都不支持）；整格缺失 = 没查成
                gfxApi = device.fields.textValueOf("gfx_api"),

                gfxApiVersion = device.fields.textValueOf("gfx_api_version"),

                location = device.fields.textValueOf("location"),

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
 * @param manufacturer          颗粒厂商（dt 的 ddr_manufacturer_id 按 JEDEC 编号换成名字，
 *                              如"三星（Samsung）"）；读不到 / 编码不在表里就是 null，页面不画这一行
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
 * @param reservedBytes         为硬件保留的内存
 * @param usedPart              分条图四段的比例（0..1，不用自己凑成 1，显示时会归一）
 * @param modifiedPart
 * @param standbyPart
 * @param freePart
 */
data class MemorySnapshot(
    val totalBytes: Long? = null,
    val nominalBytes: Long? = null,
    val specText: String? = null,
    val manufacturer: String? = null,
    val usedBytes: Long? = null,
    val compressedBytes: Long? = null,
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

        val compressed = compressedBytes?.let { memorySizeText(it) } ?: "—"

        return "${memorySizeText(usedBytes)} ($compressed)"
    }

    fun availableText(): String = memorySizeText(availableBytes)

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

        return "$whole MT/秒"
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
    fun formFactorText(): String = formFactor?.takeIf { it.isNotBlank() } ?: "其他"

    fun reservedText(): String = memorySizeText(reservedBytes)

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
 * @param id            底层给的设备 id（/sys/block 里的名字或序列号一类）：管理器用它做
 *                      缓存键与"磁盘 N"的稳定身份；拿不到就按报文顺序编号
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

        if (rounded <= 0.0) return "0 毫秒"

        val text = if (rounded % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", rounded)
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }

        return "$text 毫秒"
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
        true -> "是"
        false -> "否"
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

        bytesPerSec >= kb * kb * kb -> Triple(kb * kb * kb, "GB/秒", 3)
        bytesPerSec >= kb * kb -> Triple(kb * kb, "MB/秒", 2)
        bytesPerSec >= kb -> Triple(kb, "KB/秒", 1)
        else -> Triple(1.0, "B/秒", 0)
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

    if (bytesPerSec == 0.0) return "0 KB/秒"

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
 * 由 PerformanceManager 决定把哪一类注册成性能页设备 —— 现在只注册 WIFI。
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

        if (!value.isNullOrEmpty()) return value == "WIFI"

        val label = (iface ?: name)?.trim()?.lowercase() ?: return false

        return label.startsWith("wlan") || label.startsWith("wifi") || label.startsWith("wl")
    }

    // ---------- 显示文案 ----------

    /** 页面右上角的适配器名：型号（没有型号时底层发的是接口名），都没有才写"不支持"。 */
    fun nameText(): String = textOrUnsupported(name ?: iface)


    fun ssidText(): String = textOrUnsupported(ssid)

    fun linkTypeText(): String = textOrUnsupported(linkType)

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
internal const val NETWORK_UNSUPPORTED_TEXT = "不支持"

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
 *   2) 明确不支持 —— 写「不支持」（这个平台根本没有这一格，例如共享显存、驱动日期）；
 *   3) 取不到     —— 写「-」（有这一格，这次没读出来）。
 *
 * 键名与 C 侧 build_gpu_devices 一一对应：
 *   name / node / gpu_usage / gpu_temp_c /
 *   dedicated_total_bytes / dedicated_used_bytes /
 *   shared_total_bytes / shared_used_bytes /
 *   driver_version / driver_date / gfx_api / gfx_api_version /
 *   location / reserved_bytes
 */
data class GpuSnapshot(
    val id: String? = null,
    val name: String? = null,
    val node: String? = null,
    val utilizationPercent: Float? = null,
    val temperatureC: Int? = null,
    val dedicatedTotalBytes: Long? = null,
    val dedicatedUsedBytes: Long? = null,
    val sharedTotalBytes: Long? = null,
    val sharedUsedBytes: Long? = null,
    val driverVersion: String? = null,
    val driverDate: String? = null,
    val gfxApi: String? = null,
    val gfxApiVersion: String? = null,
    val location: String? = null,
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

    /**
     * 「Vulkan 版本」这一行要不要摆出来。
     *
     * 两块是**两个独立字段**，不是拼成一行"Vulkan/OpenGL 版本"：
     * 设备支持 Vulkan 时只摆 Vulkan 版本，OpenGL 那一行整行不画（不留"不支持"占位）；
     * 反过来只有 OpenGL 时只摆 OpenGL ES 版本。两样都没查成时才两行一起摆，
     * 各自写"不支持" / "-"，绝不因为"这台机器没这个能力"把整行删掉。
     */
    val showsVulkanRow: Boolean
        get() = gfxApiName() != "OPENGL"

    /** 「OpenGL ES 版本」这一行要不要摆出来（规则与 [showsVulkanRow] 对称）。 */
    val showsOpenGlRow: Boolean
        get() = gfxApiName() != "VULKAN"

    /** Vulkan 版本那一行的取值：支持写版本号，明确不支持写"不支持"，没查成写"-"。 */
    fun vulkanVersionText(): String = when (gfxApiName()) {

        "VULKAN" -> "Vulkan ${gfxApiVersion?.takeIf { it.isNotBlank() } ?: GPU_UNKNOWN_TEXT}"

        "NONE" -> GPU_UNSUPPORTED_TEXT

        else -> GPU_UNKNOWN_TEXT
    }

    /** OpenGL ES 版本那一行的取值，口径同上。 */
    fun openGlVersionText(): String = when (gfxApiName()) {

        "OPENGL" -> "OpenGL ES ${gfxApiVersion?.takeIf { it.isNotBlank() } ?: GPU_UNKNOWN_TEXT}"

        "NONE" -> GPU_UNSUPPORTED_TEXT

        else -> GPU_UNKNOWN_TEXT
    }

    fun locationText(): String = textOrUnsupported(location)

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
internal const val GPU_UNSUPPORTED_TEXT = "不支持"

/** GPU 页里"这次没读出来"的统一文案。 */
internal const val GPU_UNKNOWN_TEXT = "-"

/** 温度这一格的"没有这个能力"专属文案：Windows 那张页就是这么写的（"无"），
 * 与其它字段的"不支持"区分开，免得看起来像"读不到"。 */
internal const val GPU_NO_VALUE_TEXT = "无"

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
