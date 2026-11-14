/**
 * 服务页（骨架已就绪 · 数据未接入）
 *
 * 计划功能：列出应用关联的运行中服务（包名、服务名、所在进程、是否前台），
 * 并支持停止服务。
 *
 * 预留实现接口：
 * - 数据来源：ActivityManager.getRunningServices()，或扫描 /proc 中的进程；
 * - 停止：ActivityManager.stopService() / force-stop（通常需要 Root / Shizuku）；
 * - 取数入口：UIService 页面数据区（IPageDataApi），页面不要自己调上游。
 *
 * 实现状态：`ServicesPageState` 目前是 NOT_IMPLEMENTED。
 *  - MainActivity 的 PageContent() 看到这个值就不渲染本页，
 *    改为显示"此页面不受支持"占位（图标在上、文案在下）；
 *  - 本页写完那天，把下面那个常量改成 PageState.IMPLEMENTED 即可，MainActivity 不用动。
 *
 * TODO: 接入服务数据并实现列表与操作 UI（在下面的 PageShell 正文里替换占位内容）。
 *
 * 说明：文件位于 ui 目录，但包名为 com.android.taskmanager，与 MainActivity 同包。
 */
package com.android.taskmanager

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** 页面实现状态：由页面自己上报，MainActivity 的路由据此决定"渲染页面"还是"显示不支持"。 */
val ServicesPageState: PageState = PageState.NOT_IMPLEMENTED

/** 服务页面入口，由 MainActivity 的 PageContent() 调用（状态为 IMPLEMENTED 时才会被调用）。 */
@Composable
fun ServicesScreen() {
    // 顶部直接是内容：当前页由应用标题栏 / 横屏页名行统一指示，页面里不再重复画标题
    PageShell() {
        // 基础骨架：服务数据接进来之后，这里替换成三块内容——
        // 1) 主体：服务列表（服务名、包名、所在进程 PID、前台标记）
        // 2) 操作：停止服务按钮（失败时给出原因，不要静默吞掉）
        // 3) 兜底：系统限制（Android 7+ 不再公开全部运行服务）时说明数据不完整
        Text(
            text = "页面骨架已就绪，服务数据尚未接入。",

            style = MaterialTheme.typography.bodyMedium,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
