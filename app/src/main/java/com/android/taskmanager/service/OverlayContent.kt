/**
 * ============================================================
 * 浮窗内容（OVERLAY v1 · 实验功能）
 * ============================================================
 *
 * ！！回退方式：删除本文件（与 OverlayHost.kt、IOverlayApi、UIService ⑦ 分区一起删）。
 *
 * 设计意图：浮窗不是第二套界面，而是把现有页面搬进一个窗口里。
 * 页面本体直接调 ui/ 下已经存在的 Composable（ProcessScreen / PerformanceScreen …），
 * 以后这些页面填上真实数据，浮窗里自动就是新样子，不用再实现一遍。
 *
 * v1 的三条硬限制（都是故意的，不是偷懒）：
 * 1. "设置"页只在 hostedInOverlay = true 下呈现：
 *    设置页原本用 AlertDialog / DropdownMenu，它们要 Activity 的窗口令牌，
 *    放在浮窗里会崩；现在 SettingsScreen 增加了"窗口内自绘"模式
 *    （遮罩 + 卡片、选项就地展开），由 [OverlayPageFrame] 传参开启；
 * 2. 标题栏是唯一的拖动把手：整块可拖会和页面里的列表滚动抢手势；
 * 3. 只有右边缘 / 下边缘 / 右下角三个缩放点：左上缩放要反算位置和尺寸，v1 先不做。
 */
package com.android.taskmanager.service

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.taskmanager.ApphistoryScreen
import com.android.taskmanager.DebugScreen
import com.android.taskmanager.DetailsScreen
import com.android.taskmanager.PerformanceScreen
import com.android.taskmanager.ProcessScreen
import com.android.taskmanager.ServicesScreen
import com.android.taskmanager.StartupAppScreen
import com.android.taskmanager.SettingKey
import com.android.taskmanager.SettingsScreen
import com.android.taskmanager.SettingsValues
import com.android.taskmanager.TaskManagerTheme
import com.android.taskmanager.UsersScreen

/**
 * 浮窗里可以切换的页面。
 *
 * "设置"放在最后：它和别的页共用同一份 SettingsScreen，
 * 只是以 hostedInOverlay = true 进入"窗口内对话框"模式，
 * 因此不会去碰 Activity 的窗口令牌，在浮窗里可以安全地改设置。
 */
internal val OVERLAY_PAGES: List<String> = listOf(
    "进程",
    "性能",
    "应用历史记录",
    "启动应用",
    "用户",
    "详细信息",
    "服务",
    "调试",
    "设置"
)

/**
 * 浮窗的完整外观：标题栏（拖动）+ 页面切换 + 页面本体 + 三个缩放把手。
 *
 * @param pageName 当前显示的页面名（"进程" / "性能" …）
 * @param settingsProvider "设置"页的初始快照：每次进入该页时现取，保证读的是最新值
 *        （UIService 才是设置的唯一持有者，浮窗这里只读）
 * @param onSettingChanged "设置"页的变更出口：接到 UIService.onSettingChanged()，由它落盘
 * @param actions 拖动 / 缩放 / 关闭动作，由 OverlayHost 提供
 * @param onPageSelected 切换页面
 */
@Composable
internal fun OverlayPageFrame(
    pageName: String,
    settingsProvider: () -> SettingsValues,
    onSettingChanged: (SettingKey, Any) -> Unit,
    actions: OverlayHost.Actions,
    onPageSelected: (String) -> Unit
) {
    // 浮窗与应用主体共用同一套主题（ThemeState 是进程级状态），
    // 所以在设置页里切"应用主题"，浮窗会当场跟着变色，不需要重开窗口。
    TaskManagerTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(Modifier.fillMaxSize()) {

                Column(Modifier.fillMaxSize()) {

                    // ---------- 标题栏：唯一拖动区 ----------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()

                                    actions.moveBy(dragAmount.x, dragAmount.y)
                                }
                            }
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "任务管理器 · $pageName",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        TextButton(
                            onClick = actions.close,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("退出", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    HorizontalDivider()

                    // ---------- 页面切换（不弹菜单：浮窗里弹窗有风险，用横排按钮） ----------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OVERLAY_PAGES.forEach { name ->
                            val selected = name == pageName

                            TextButton(
                                onClick = { onPageSelected(name) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // ---------- 页面本体：直接复用 ui/ 下现有的 Composable ----------
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        OverlayPageBody(
                            pageName = pageName,
                            settingsProvider = settingsProvider,
                            onSettingChanged = onSettingChanged
                        )
                    }
                }

                // ---------- 缩放把手（右 / 下 / 右下角） ----------
                ResizeHandle(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                ) { dx, _ -> actions.resizeBy(dx, 0f) }

                ResizeHandle(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                ) { _, dy -> actions.resizeBy(0f, dy) }

                ResizeHandle(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp),
                    color = MaterialTheme.colorScheme.primary
                ) { dx, dy -> actions.resizeBy(dx, dy) }
            }
        }
    }
}

/**
 * 页面分发：把页面名换成现有的 Composable。
 *
 * 这里是"浮窗和活动页共用同一份界面"的关键——新增页面时只需要在这儿加一行，
 * 不需要为浮窗再写一套。
 */
@Composable
private fun OverlayPageBody(
    pageName: String,
    settingsProvider: () -> SettingsValues,
    onSettingChanged: (SettingKey, Any) -> Unit
) {
    when (pageName) {
        "性能" -> PerformanceScreen(hostedInOverlay = true)

        "应用历史记录" -> ApphistoryScreen()

        "启动应用" -> StartupAppScreen()

        "用户" -> UsersScreen()

        "详细信息" -> DetailsScreen(hostedInOverlay = true)

        "服务" -> ServicesScreen()

        "调试" -> DebugScreen()

        // "设置"：同一份 SettingsScreen，但走"窗口内对话框"模式
        // （浮窗没有 Activity 窗口令牌，AlertDialog / DropdownMenu 会崩）
        "设置" -> SettingsScreen(
            initialValues = settingsProvider(),
            onSettingChanged = onSettingChanged,
            hostedInOverlay = true
        )

        // 兜底：一律回进程页，绝不把悬浮窗画成空白
        else -> ProcessScreen()
    }
}

/** 一个可以拖的缩放把手；[onDrag] 收到的是原始位移，方向由调用方决定。 */
@Composable
private fun ResizeHandle(
    modifier: Modifier,
    color: Color,
    onDrag: (Float, Float) -> Unit
) {
    Box(
        modifier = modifier
            .background(color)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()

                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    )
}
