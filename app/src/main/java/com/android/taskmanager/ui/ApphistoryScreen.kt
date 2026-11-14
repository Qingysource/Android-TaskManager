/**
 * 应用历史记录页（骨架已就绪 · 数据未接入）
 *
 * 计划功能：按时间区间查看应用对 CPU / 内存 / 磁盘 / 网络的资源使用历史。
 *
 * 预留实现接口：
 * - 数据来源：UsageStatsManager（需要 PACKAGE_USAGE_STATS 特殊授权），
 *             或 UIService 定时采样后落库；
 * - 显示范围：由设置项"显示所有进程的历史记录"控制；
 * - 取数入口：UIService 页面数据区（IPageDataApi），页面不要自己调上游。
 *
 * 实现状态：`ApphistoryPageState` 目前是 NOT_IMPLEMENTED。
 *  - MainActivity 的 PageContent() 看到这个值就不渲染本页，
 *    改为显示"此页面不受支持"占位（图标在上、文案在下）；
 *  - 本页写完那天，把下面那个常量改成 PageState.IMPLEMENTED 即可，MainActivity 不用动。
 *
 * TODO: 接入使用统计数据，实现历史列表 / 图表（在下面的 PageShell 正文里替换占位内容）。
 *
 * 说明：文件位于 ui 目录，但包名为 com.android.taskmanager，与 MainActivity 同包。
 */
package com.android.taskmanager

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** 页面实现状态：由页面自己上报，MainActivity 的路由据此决定"渲染页面"还是"显示不支持"。 */
val ApphistoryPageState: PageState = PageState.NOT_IMPLEMENTED

/** 应用历史记录页面入口，由 MainActivity 的 PageContent() 调用（状态为 IMPLEMENTED 时才会被调用）。 */
@Composable
fun ApphistoryScreen() {
    // 顶部直接是内容：当前页由应用标题栏 / 横屏页名行统一指示，页面里不再重复画标题
    PageShell() {
        // 基础骨架：历史数据接进来之后，这里替换成三块内容——
        // 1) 顶部：时间区间选择（今天 / 过去 7 天 / 过去 30 天）
        // 2) 主体：按应用聚合的用量列表（应用名 + 各资源累计）
        // 3) 兜底：没有使用情况访问权限时，提示去系统设置里授予
        Text(
            text = "页面骨架已就绪，历史数据尚未接入。",

            style = MaterialTheme.typography.bodyMedium,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
