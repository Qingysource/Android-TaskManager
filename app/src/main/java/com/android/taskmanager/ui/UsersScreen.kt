/**
 * 用户页（骨架已就绪 · 数据未接入）
 *
 * 计划功能：查看系统中的用户 / 账户信息（多用户、UID / GID、当前运行用户、账户名），
 * 可与 Linux 侧 /etc/passwd、进程属主 UID 对照。
 *
 * 预留实现接口：
 * - 数据来源：UserManager（Framework），以及 /proc 下各进程的属主 UID；
 * - 显示格式：由设置项"显示完整账户名"控制是否截断；
 * - 取数入口：UIService 页面数据区（IPageDataApi），页面不要自己调上游。
 *
 * 实现状态：`UsersPageState` 目前是 NOT_IMPLEMENTED。
 *  - MainActivity 的 PageContent() 看到这个值就不渲染本页，
 *    改为显示"此页面不受支持"占位（图标在上、文案在下）；
 *  - 本页写完那天，把下面那个常量改成 PageState.IMPLEMENTED 即可，MainActivity 不用动。
 *
 * TODO: 接入用户数据并实现列表 UI（在下面的 PageShell 正文里替换占位内容）。
 *
 * 说明：文件位于 ui 目录，但包名为 com.android.taskmanager，与 MainActivity 同包。
 */
package com.android.taskmanager

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** 页面实现状态：由页面自己上报，MainActivity 的路由据此决定"渲染页面"还是"显示不支持"。 */
val UsersPageState: PageState = PageState.NOT_IMPLEMENTED

/** 用户页面入口，由 MainActivity 的 PageContent() 调用（状态为 IMPLEMENTED 时才会被调用）。 */
@Composable
fun UsersScreen() {
    // 顶部直接是内容：当前页由应用标题栏 / 横屏页名行统一指示，页面里不再重复画标题
    PageShell() {
        // 基础骨架：用户数据接进来之后，这里替换成三块内容——
        // 1) 主体：用户列表（账户名、UID、GID、是否当前用户）
        // 2) 展开：点某个用户看该用户下正在运行的进程数量
        // 3) 格式：跟随设置里的"显示完整账户名"决定截断还是全名
        Text(
            text = "页面骨架已就绪，用户数据尚未接入。",

            style = MaterialTheme.typography.bodyMedium,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
