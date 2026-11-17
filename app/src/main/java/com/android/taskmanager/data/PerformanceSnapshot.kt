package com.android.taskmanager.data

import java.util.Locale


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

    /** 虚拟化：后端没报就当不支持。 */
    fun virtualizationText(): String = virtualization ?: "不支持"

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

                processCount = systemFields.intOf("process_count"),

                threadCount = systemFields.intOf("thread_count"),

                fdCount = systemFields.intOf("fd_count"),

                uptimeSeconds = systemFields.longOf("uptime_seconds"),

                sockets = cpuFields.intOf("sockets"),

                cores = cpuFields.intOf("cores"),

                logicalCpus = cpuFields.intOf("logical_cpus"),

                l1CacheKb = cpuFields.intOf("l1_cache_kb"),

                l2CacheKb = cpuFields.intOf("l2_cache_kb"),

                l3CacheKb = cpuFields.intOf("l3_cache_kb")
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

