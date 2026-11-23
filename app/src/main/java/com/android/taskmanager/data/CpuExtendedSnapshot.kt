/**
 * ============================================================
 * CPU 扩展信息的一帧数据（设备扩展快照 typeCode=6）
 * ============================================================
 *
 * 数据链：
 *   ELF 的 typeCode=6 设备扩展快照（D/F 行）
 *   -> LinuxService.extendedDeviceSnapshotPacket()
 *   -> 本文件 from() 解析成集群 + 逐核
 *   -> UIService.cpuExtendedSnapshot()
 *   -> ui/DeviceDetailScreen.kt（设备详细信息页）
 *
 * 为什么与 PerformanceSnapshot 分开：那个是"整颗处理器一台设备"的口径，
 * 这个是"一簇一台、一核一台"。两个包的设备数量、字段名都不一样，
 * 塞进同一个模型会让两边的 null 语义互相污染。
 *
 * 每个字段的 null 含义统一是"后端没给"（回的是 !状态，例如 !NOT_AVAILABLE）：
 * 界面必须显示占位或原因，**不能拿 0 顶替** —— 0% 与"读不到"是两件事。
 */
package com.android.taskmanager.data

import com.android.taskmanager.AppStrings
import com.android.taskmanager.R
import java.util.Locale

/**
 * 一台集群（一个调频域 / 拓扑簇）。
 *
 * @param index    簇序号（0 开始，与页面上的 "Cluster N" 一致）
 * @param policy   对应的 cpufreq policyN；null = 这个簇是按拓扑分的，没有 policy
 * @param coreIds  成员逻辑核号，升序，与后端 core_ids 字段同一个顺序
 */
data class CpuClusterExt(
    val index: Int,
    val policy: Int?,
    val coreIds: List<Int>
) {
    /** 分区标题：`Cluster 0`。 */
    fun title(): String = "Cluster $index"

    /** 分区说明：`policy0 · 4 个核心`；没有 policy 时只剩核数。 */
    fun subtitle(): String {
        val policyText = policy?.let { "policy$it" } ?: AppStrings.s(R.string.cpuext_topology_group)

        return AppStrings.s(R.string.cpuext_cluster_subtitle, policyText, coreIds.size)
    }
}

/**
 * 一颗逻辑核在这一帧里的样子。
 *
 * @param id       逻辑核号（与设备 id cpuN 的 N 一致）
 * @param cluster  所属簇序号
 * @param policy   所属 policyN；null = 没有 policy（读频率时只能靠 cpuN 目录）
 * @param fields   字段键 -> 值（usage_percent / cur_mhz / max_mhz / min_mhz / scaling_max_mhz）
 * @param statuses 字段键 -> 状态；出现这个键说明这一格没有数值
 */
data class CpuCoreExt(
    val id: Int,
    val cluster: Int,
    val policy: Int?,
    val fields: Map<String, String>,
    val statuses: Map<String, String> = emptyMap()
) {
    /** 标题：`CPU 0`。 */
    fun label(): String = "CPU $id"

    /** 利用率原始值（0..100）；null = 这一帧没有基准。 */
    val usagePercent: Float?
        get() = fields["usage_percent"]?.toFloatOrNull()

    /** 利用率文本：`23%`；没值写 `—`。 */
    fun usageText(): String = usagePercent?.let {
        String.format(Locale.US, "%.0f%%", it)
    } ?: "—"

    /** 当前频率文本。 */
    fun curText(): String = mhzText("cur_mhz")

    /** 硬件最大频率文本。 */
    fun maxText(): String = mhzText("max_mhz")

    /** 硬件最小频率文本。 */
    fun minText(): String = mhzText("min_mhz")

    /** 设置的最大频率（scaling_max_freq）文本。 */
    fun scalingMaxText(): String = mhzText("scaling_max_mhz")

    /**
     * 某一格的显示文本：有值给值，没值给 `—（原因）`。
     *
     * 详情区用它，chip 里用紧凑版（只写 `—`，不把原因铺满小方块）。
     */
    fun displayOf(key: String): String {
        val value = fields[key]

        if (value != null) {
            return if (key == "usage_percent") {
                value.toFloatOrNull()?.let { String.format(Locale.US, "%.1f%%", it) } ?: value
            } else {
                mhzText(key)
            }
        }

        val status = statuses[key] ?: return "—"

        return "—（${LinuxFieldStatus.describe(status)}）"
    }

    /**
     * 频率的数值形态：后端统一发 MHz 整数。
     *
     * 显示口径跟性能页一致：>= 1000 MHz 折成 `2.80 GHz`（两位小数），
     * 低于 1000 写 `300 MHz` —— 小核的最低档写成 0.30 GHz 反而不好读。
     */
    private fun mhzText(key: String): String {
        val value = fields[key]?.toIntOrNull()

        if (value == null || value <= 0) {
            return "—"
        }

        return if (value >= 1000) {
            String.format(Locale.US, "%.2f GHz", value / 1000.0)
        } else {
            "$value MHz"
        }
    }
}

/**
 * 一帧 CPU 扩展信息。
 *
 * 只保留"这一帧的事实"，不留历史：页面只做实时显示，不做统计与曲线。
 *
 * @param clusters 集群分区，按簇序号升序
 * @param cores    逐核数据，按逻辑核号升序
 */
data class CpuExtendedSnapshot(
    val clusters: List<CpuClusterExt> = emptyList(),
    val cores: List<CpuCoreExt> = emptyList()
) {
    /** 这一帧有没有可显示的核心；false = 空帧（页面显示占位而不是空白图）。 */
    val isEmpty: Boolean get() = cores.isEmpty()

    /** 从设备扩展快照应答包解析一帧。 */
    companion object {

        /** 空帧：还没采到任何数据。 */
        val EMPTY = CpuExtendedSnapshot()

        fun from(packet: LinuxPacket?): CpuExtendedSnapshot? {

            // 只认"完整且成功"的包：半包 / UNSUPPORTED 一律按没拿到处理，
            // 页面保持上一帧，不会因为一次失败把数字清空
            if (packet == null || !packet.isOk) return null

            if (packet.typeCode != LinuxRequestType.DEVICE_EXTENDED_SNAPSHOT) return null

            val clusters = packet.devicesOf(LinuxProtocol.DEVICE_TYPE_CPU_CLUSTER)
                .mapNotNull { device ->
                    val index = device.fields["cluster"]?.toIntOrNull()
                        ?: device.id.removePrefix("cluster").toIntOrNull()
                        ?: return@mapNotNull null

                    CpuClusterExt(
                        index = index,
                        policy = device.fields["policy"]?.toIntOrNull(),
                        coreIds = device.fields["core_ids"].toIntList()
                    )
                }
                .sortedBy { it.index }

            val cores = packet.devicesOf(LinuxProtocol.DEVICE_TYPE_CPU_CORE)
                .mapNotNull { device ->
                    val id = device.fields["core"]?.toIntOrNull()
                        ?: device.id.removePrefix("cpu").toIntOrNull()
                        ?: return@mapNotNull null

                    CpuCoreExt(
                        id = id,
                        cluster = device.fields["cluster"]?.toIntOrNull() ?: 0,
                        policy = device.fields["policy"]?.toIntOrNull(),
                        fields = device.fields,
                        statuses = device.statuses
                    )
                }
                .sortedBy { it.id }

            return CpuExtendedSnapshot(clusters = clusters, cores = cores)
        }

        /** 逗号分隔的数字串 -> 整数列表，坏项跳过（不猜值）。 */
        private fun String?.toIntList(): List<Int> {
            if (this.isNullOrBlank()) return emptyList()

            return split(",")
                .mapNotNull { it.trim().toIntOrNull() }
        }
    }
}
