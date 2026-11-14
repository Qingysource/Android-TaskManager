/**
 * 启动应用页（骨架已就绪 · 数据未接入）
 *
 * 计划功能：列出随系统启动的应用与组件（监听 BOOT_COMPLETED 的广播、前台服务、自启项），
 * 并支持启用 / 禁用。
 *
 * 预留实现接口：
 * - 数据来源：PackageManager 查询 receivers / services 并过滤 BOOT_COMPLETED；
 * - 禁用：PackageManager.setComponentEnabledSetting()（需要权限，通常要 Root / Shizuku）；
 * - 取数入口：UIService 页面数据区（IPageDataApi），页面不要自己调上游。
 *
 * 实现状态：`StartupAppPageState` 目前是 NOT_IMPLEMENTED。
 *  - MainActivity 的 PageContent() 看到这个值就不渲染本页，
 *    改为显示"此页面不受支持"占位（图标在上、文案在下）；
 *  - 本页写完那天，把下面那个常量改成 PageState.IMPLEMENTED 即可，MainActivity 不用动。
 *
 * TODO: 接入启动项数据并实现开关管理（在下面的 PageShell 正文里替换占位内容）。
 *
 * 说明：文件位于 ui 目录，但包名为 com.android.taskmanager，与 MainActivity 同包。
 */
package com.android.taskmanager

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** 页面实现状态：由页面自己上报，MainActivity 的路由据此决定"渲染页面"还是"显示不支持"。 */
val StartupAppPageState: PageState = PageState.NOT_IMPLEMENTED

/** 启动应用页面入口，由 MainActivity 的 PageContent() 调用（状态为 IMPLEMENTED 时才会被调用）。 */
@Composable
fun StartupAppScreen() {
    // 顶部直接是内容：当前页由应用标题栏 / 横屏页名行统一指示，页面里不再重复画标题
    PageShell() {
        // 基础骨架：启动项接进来之后，这里替换成三块内容——
        // 1) 主体：启动项列表（应用名、组件类型、影响程度、启用状态开关）
        // 2) 操作：开关即时生效并回写状态；失败时给出原因（权限 / 系统限制）
        // 3) 兜底：没有 Root / Shizuku 时开关置灰，并说明为什么不能改
        Text(
            text = "页面骨架已就绪，启动项数据尚未接入。",

            style = MaterialTheme.typography.bodyMedium,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
