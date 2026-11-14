/**
 * 进程页（骨架已就绪 · 数据未接入）
 *
 * 计划功能：
 * - 列出正在运行的进程：PID / PPID、进程名、UID、CPU 占用、内存占用、状态；
 * - 支持按 CPU、内存排序与搜索，支持结束进程（需权限）；
 * - 点击某个进程跳转到"详细信息"页。
 *
 * 预留实现接口：
 * - 数据来源：AndroidService.getProcessList()（Framework 接口），
 *             或 LinuxService 遍历 /proc（需 Root / Shizuku 授权）；
 * - 取数入口：UIService 页面数据区（IPageDataApi），页面不要自己调上游；
 * - 权限依赖：ShizukuManager.status 与 RootManager 的可用性判定；
 * - 刷新频率：读取设置页的"实时更新速度"（updateSpeed）。
 *
 * 实现状态：`ProcessPageState` 目前是 NOT_IMPLEMENTED。
 *  - MainActivity 的 PageContent() 看到这个值就不渲染本页，
 *    改为显示"此页面不受支持"占位（图标在上、文案在下）；
 *  - 本页写完那天，把下面那个常量改成 PageState.IMPLEMENTED 即可，MainActivity 不用动。
 *
 * TODO: 接入进程数据源并实现进程列表 UI（在下面的 PageShell 正文里替换占位内容）。
 *
 * 说明：文件位于 ui 目录，但包名为 com.android.taskmanager，与 MainActivity 同包。
 */
package com.android.taskmanager

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * 页面实现状态：由页面自己上报，MainActivity 的路由据此决定"渲染页面"还是"显示不支持"。
 *
 * 之所以放在页面文件里而不是 MainActivity 的 when 里：
 * 谁写这页，谁改这一行——不用再去主活动里翻找对应分支。
 */
val ProcessPageState: PageState = PageState.NOT_IMPLEMENTED

/** 进程页面入口，由 MainActivity 的 PageContent() 调用（状态为 IMPLEMENTED 时才会被调用）。 */
@Composable
fun ProcessScreen() {
    // 顶部直接是内容：当前页由应用标题栏 / 横屏页名行统一指示，页面里不再重复画标题
    PageShell() {
        // 基础骨架：数据接进来之后，这里替换成三块内容——
        // 1) 顶部：搜索框 + 排序方式切换（CPU / 内存 / 名称）
        // 2) 主体：进程列表（PID、进程名、UID、CPU%、内存、状态）
        // 3) 兜底：无权限 / 数据未就绪时的提示与"重新读取"按钮
        Text(
            text = "页面骨架已就绪，进程数据尚未接入。",

            style = MaterialTheme.typography.bodyMedium,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
