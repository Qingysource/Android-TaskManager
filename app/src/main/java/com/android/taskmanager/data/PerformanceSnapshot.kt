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
                    ?.let { buildMemorySnapshot(it) }
            )
        }

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

