/**
 * ============================================================
 * 页面骨架（各功能页共用的版式）
 * ============================================================
 *
 * 为什么要有这个文件：
 * 进程 / 性能 / 应用历史记录 / 启动应用 / 用户 / 详细信息 / 服务 七页的版式是同一套
 * ——顶部"图标 + 标题"，下面一行说明，再往下才是正文。版式写在各页里会重复七遍，
 * 改一次标题字号要改七处；抽到这里，各页只关心自己的正文。
 *
 * 不负责的事：
 * - 不做"页面是否受支持"的判断（那是 MainActivity 的 pageStateOf() 的职责）；
 * - 不取数据（数据一律找 UIService 的 IUIServiceApi / IPageDataApi）；
 * - 不做应用标题栏（窄屏 TopAppBar / 宽屏常驻导航都在 MainActivity）。
 *
 * 预留实现接口：
 * - 需要"右上角操作按钮"（刷新 / 排序 / 结束进程）时，在这里加一个 actions 参数，
 *   七页同时获得，不用逐页改；
 * - 需要"顶部状态条"（数据是否就绪 / 权限状态）时，同样在这里加一个可选的 header 槽位。
 */
package com.android.taskmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 功能页统一骨架。
 *
 * 默认形态是"标题行 + 说明 + 正文（整页可滚动）"；
 * 但**各功能页默认不要标题区**（`showHeader = false`）：页面顶部直接就是它该显示的内容
 * （列表、表格、图表）。当前在哪一页由应用标题栏 / 横屏页名行统一指示，页面里再画一遍标题是重复。
 * 唯一保留标题区的是调试页（它的说明文字承担"警告 / 使用须知"的作用）。
 *
 * 可滚动是硬要求：横屏时页面高度会被顶部的 48dp 页名行吃掉一截，
 * 正文稍长就溢出，不给 verticalScroll 用户既看不全也拖不动。
 *
 * @param icon 标题区图标（与导航项一致）；`showHeader = false` 时不需要传
 * @param title 页面名；`showHeader = false` 时不需要传
 * @param subtitle 一句话说明；`showHeader = false` 时不需要传
 * @param showHeader 是否画标题区。默认 false：页面顶部直接是正文内容
 * @param scrollable 正文是否整页纵向滚动（默认 true）。
 *                   详细信息页的表格要"横向 + 纵向"二维滚动，再套一层整页滚动会抢手势、
 *                   高度也会被撑成无限，所以那一页传 false，自己管滚动。
 * @param content 正文；未实现的页面传占位内容即可
 */
@Composable
internal fun PageShell(
    icon: ImageVector? = null,
    title: String = "",
    subtitle: String = "",
    showHeader: Boolean = false,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
            )
            .padding(24.dp),

        verticalArrangement = Arrangement.Top,

        horizontalAlignment = Alignment.Start
    ) {

        if (showHeader && title.isNotEmpty()) {

            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                icon?.let {

                    Icon(
                        imageVector = it,

                        contentDescription = null,

                        modifier = Modifier.size(28.dp),

                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(
                        modifier = Modifier.width(12.dp)
                    )
                }

                Text(
                    text = title,

                    style = MaterialTheme.typography.headlineSmall,

                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            // 一句话说明
            Text(
                text = subtitle,

                style = MaterialTheme.typography.bodyMedium,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )
        }

        // 正文
        content()
    }
}
