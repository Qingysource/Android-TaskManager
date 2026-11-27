/**
 * ============================================================
 * 应用历史记录页的数据模型
 * ============================================================
 *
 * 单独成文件：与 [com.android.taskmanager.Interfaces] 的契约模型分开存放，
 * 避免大契约文件里的解析干扰。
 *
 * 口径（来自用户）：
 * - CPU 时间：同一软件包名下所有进程自本页开账以来的 CPU 增量之和（不是进程存活时长）；
 * - 按流量计费网络 = 移动网络下载 / 上载；不按流量计费网络 = Wi-Fi 下载 / 上载；
 * - 网络统计起点 = 本页开账（IPageDataApi.appHistoryBegin）。
 */
package com.android.taskmanager.data

/**
 * 「应用历史记录」页：一行一个已安装应用（含系统应用）。
 *
 * @param cpuTimeMillis 自开账以来该包全部进程的 CPU 增量累计（毫秒）；
 *                      [AppHistoryTable.cpuAvailable] 为 false 时不参与显示
 * @param mobileRxBytes 移动网络下载累计；null = 网络通道不可用（显示「—」）
 * @param mobileTxBytes 移动网络上载累计；null = 网络通道不可用
 * @param wifiRxBytes   Wi-Fi 下载累计；null = 网络通道不可用
 * @param wifiTxBytes   Wi-Fi 上载累计；null = 网络通道不可用
 * @param notificationCount 历史通知计数；Android 没有系统级历史通知计数器，
 *                      目前恒为 null（界面显示「—」，不伪造 0，也不为此加监听服务）
 * @param systemApp     是否系统应用（PackageManager 的 FLAG_SYSTEM）
 * @param running       当前是否有该包的进程在跑（仅用于排序提示，不影响数值）
 */
data class AppHistoryRow(
    val packageName: String,
    val label: String,
    val cpuTimeMillis: Long = 0L,
    val mobileRxBytes: Long? = null,
    val mobileTxBytes: Long? = null,
    val wifiRxBytes: Long? = null,
    val wifiTxBytes: Long? = null,
    val notificationCount: Long? = null,
    val systemApp: Boolean = false,
    val running: Boolean = false
)

/**
 * 「应用历史记录」页整张表。
 *
 * @param cpuAvailable      CPU 采集通道（Linux 后端）是否可用；false 时所有 CPU 格显示「—」
 * @param networkAvailable  网络计数通道（NetworkStatsManager，走提权 appop）是否可用；
 *                          false 时网络格全部显示「—」
 * @param networkSource     网络计数来源的一句话说明（调试 / 提示用）；空串 = 不显示
 * @param beginMs           本页开账时刻（wall clock ms）；顶部「自 … 以来」文案取这个时刻
 */
data class AppHistoryTable(
    val rows: List<AppHistoryRow> = emptyList(),
    val cpuAvailable: Boolean = false,
    val networkAvailable: Boolean = false,
    val networkSource: String = "",
    val refreshIntervalMs: Long = 0L,
    val beginMs: Long = 0L
)
