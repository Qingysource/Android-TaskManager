/**
 * ============================================================
 * 应用历史记录页（Windows 任务管理器 App history 风格）
 * ============================================================
 *
 * 口径（来自用户）：
 * - 列表：本机安装的全部应用，含系统应用（PackageManager.getInstalledApplications，
 *   AndroidManifest 已声明 QUERY_ALL_PACKAGES）；
 * - CPU 时间：同一软件包名下所有进程自本页开账以来的 CPU 增量之和
 *   （C 后端 /proc/<pid>/stat 的 utime+stime 累计值采增量，pid+startTicks 认进程，
 *   进程退出后保留已累加值，不每秒把累计值直接相加）；
 * - 网络语义（按该行 uid 统计，取不到对应分量显示「—」，不拿 0 冒充）：
 *     网络       = 移动(rx+tx) + Wi-Fi(rx+tx)
 *     按流量计费 = 移动(rx+tx)
 *     不按流量计费 = Wi-Fi(rx+tx)
 *     下载       = 移动 rx + Wi-Fi rx
 *     上载       = 移动 tx + Wi-Fi tx
 *   数据源是 NetworkStatsManager.queryDetailsForUid（按 uid），前提是 appop
 *   GET_USAGE_STATS 已授予（进页面时由提权通道 `appops set ... allow` 自动授予）。
 * - 通知：Android 没有系统级历史通知计数，模型里 [AppHistoryRow.notificationCount]
 *   恒为 null，界面显示「—」；不为此列新增权限或通知监听服务。
 * - 性能：只在页面可见时轮询，离开页面即停（LaunchedEffect 随组合结束取消），
 *   不常驻高频采样线程。
 *
 * 列顺序（表头单行）：名称 | CPU 时间 | 网络 | 按流量计费的网络 | 通知
 * | 不按流量计费的网络 | 下载 | 上载。名称固定不可隐藏，其余 7 项可在「选择列」里勾选。
 * 点击表头列头排序（默认 CPU 时间降序；同列再点切换升降序，空值恒排最后）；长按表头这一排打开「选择列」，
 * 点击任一项立即切换并关闭。选择结果持久化在 SharedPreferences
 * （[APP_HISTORY_VISIBLE_COLUMNS_PREFS]），表头 / 行 / 总宽度按可见列动态计算。
 *
 * 取数入口：UIService.appHistoryTable()（IPageDataApi），页面不自己调上游。
 */
package com.android.taskmanager

import android.content.Context
import android.content.Intent
import android.provider.Settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.taskmanager.data.AppHistoryRow
import com.android.taskmanager.data.AppHistoryTable
import com.android.taskmanager.data.ProcessIconCache
import com.android.taskmanager.service.UIService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面实现状态：MainActivity 的路由据此决定渲染还是占位。 */
val ApphistoryPageState: PageState = PageState.IMPLEMENTED

/** 本页轮询间隔：只在页面可见时跑；离开组合即停。 */
private const val APP_HISTORY_POLL_MS = 2_000L

/** 选择列持久化：与用户页同一套做法（SharedPreferences + StringSet）。 */
private const val APP_HISTORY_VISIBLE_COLUMNS_PREFS = "app_history_visible_columns"

private const val APP_HISTORY_VISIBLE_COLUMNS_KEY = "columns"

/**
 * 本页的列定义，枚举顺序就是表头 / 对话框里的展示顺序。
 *
 * @param fixed true = 固定显示、不出现在「选择列」里（只有名称列）
 * @param widthDp 这一列的显示宽度；表头 / 行 / 总宽度都按可见列的宽度现算
 */
private enum class AppHistoryColumn(
    val labelRes: Int,
    val widthDp: Int,
    val fixed: Boolean = false
) {
    NAME(R.string.app_history_col_name, 220, fixed = true),
    CPU(R.string.app_history_col_cpu, 90),
    NETWORK(R.string.app_history_col_network, 90),
    METERED(R.string.app_history_col_metered, 130),
    NOTIFICATION(R.string.app_history_col_notification, 90),
    UNMETERED(R.string.app_history_col_unmetered, 150),
    DOWNLOAD(R.string.app_history_col_download, 90),
    UPLOAD(R.string.app_history_col_upload, 90)
}

/** 旧版本偏好里没有的新增列：迁移时默认可见，但不把用户先前取消的旧列强行打开。 */
private val APP_HISTORY_NEW_DEFAULT_COLUMNS = setOf(
    AppHistoryColumn.NETWORK,
    AppHistoryColumn.NOTIFICATION,
    AppHistoryColumn.DOWNLOAD,
    AppHistoryColumn.UPLOAD
)

/** 应用历史记录页面入口，由 MainActivity 的 PageContent() 调用。 */
@Composable
fun ApphistoryScreen() {

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    var table by remember { mutableStateOf<AppHistoryTable?>(null) }

    var visibleColumns by remember {
        mutableStateOf(loadVisibleAppHistoryColumns(context))
    }

    var columnMenuVisible by remember { mutableStateOf(false) }

    // 行选择：整表单选；点同一行取消，点另一个包替换。轮询刷新后按包名保留选中
    var selectedPackage by remember { mutableStateOf<String?>(null) }

    // 长按某一行弹出的「切换到」菜单目标
    var switchTarget by remember { mutableStateOf<AppHistoryRow?>(null) }

    val listState = rememberLazyListState()

    val horizontalState = rememberScrollState()

    // 默认按 CPU 时间降序；点其他列首次降序、再点升序，空值恒排最后
    var sortColumn by remember { mutableStateOf(AppHistoryColumn.CPU) }
    var sortAscending by remember { mutableStateOf(false) }

    // 每次进页面都从表格左上角开始，不沿用上次横向滚动位置
    LaunchedEffect(Unit) { horizontalState.scrollTo(0) }

    // 改排序后回到第一行；刷新数据不重置，避免用户看列表时被强制拉回顶部
    LaunchedEffect(sortColumn, sortAscending) { listState.scrollToItem(0) }

    val columns = AppHistoryColumn.values().filter { it in visibleColumns }

    val totalWidth = columns.sumOf { it.widthDp }.dp

    // 进入页面开账 + 轮询；离开组合（切走页面）协程自动取消，采样随之停止
    LaunchedEffect(Unit) {

        val service = UIService.instanceOrNull()

        if (service == null) {
            // 服务还没起来：等一轮再进循环，不空转
            delay(APP_HISTORY_POLL_MS)
        } else {
            service.appHistoryBegin()
        }

        while (true) {

            val next = withContext(Dispatchers.IO) {
                UIService.instanceOrNull()?.appHistoryTable()
            }

            if (next != null) table = next

            delay(APP_HISTORY_POLL_MS)
        }
    }

    // 长按表头打开的「选择列」：点一项立即切换并关闭
    if (columnMenuVisible) {

        AppHistoryColumnsDialog(
            visibleColumns = visibleColumns,
            onDismiss = { columnMenuVisible = false },
            onToggle = { column ->

                val next = if (column in visibleColumns) {
                    visibleColumns - column
                } else {
                    visibleColumns + column
                } + AppHistoryColumn.NAME

                visibleColumns = next
                saveVisibleAppHistoryColumns(context, next)
                columnMenuVisible = false
            }
        )
    }

    // 长按行：无标题对话框，上面只有一行「切换到(T)」，下面只有「取消」。
    // 点「切换到」直接拉起该包的活动页；没有活动页的系统服务包也照样走启动流程，
    // 启动失败静默吞掉，不弹错误。
    switchTarget?.let { target ->

        AlertDialog(
            onDismissRequest = { switchTarget = null },

            title = null,

            text = {
                Text(
                    text = AppStrings.s(R.string.app_history_switch_to),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            switchTarget = null
                            launchAppActivity(context, target.packageName)
                        }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },

            confirmButton = {
                TextButton(onClick = { switchTarget = null }) {
                    Text(AppStrings.s(R.string.common_cancel))
                }
            },

            dismissButton = null
        )
    }

    /** 删除使用情况历史记录：清空 CPU / 网络累计、重新开账（时间随之刷新）并刷新界面。 */
    val onDeleteHistory: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) {
                UIService.instanceOrNull()?.appHistoryBegin()
                table = UIService.instanceOrNull()?.appHistoryTable() ?: table
            }
        }
    }

    // 表格要像用户页那样贴住内容区左上角，所以页面骨架不留下左右/顶部内边距；
    // 顶部时间文案和警告条各自单独缩进，表格本身从 x=0 开始。
    PageShell(
        scrollable = false,
        horizontalPadding = 0.dp,
        topPadding = 0.dp,
        bottomPadding = 0.dp
    ) {

        val snapshot = table

        // 顶部两段：开账时刻文案 + 可点击的「删除使用情况历史记录」
        if (snapshot != null) {

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp)
            ) {

                Text(
                    text = AppStrings.s(
                        R.string.app_history_since,
                        formatHistoryTime(snapshot.beginMs)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = onDeleteHistory,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 0.dp,
                        end = 8.dp,
                        top = 2.dp,
                        bottom = 8.dp
                    )
                ) {
                    Text(
                        text = AppStrings.s(R.string.app_history_clear_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 通道不可用提示（红底叹号样式，与用户页失败提示一致）
        snapshot?.let { current ->
            val missing = buildList {
                if (!current.cpuAvailable) add(AppStrings.s(R.string.app_history_cpu_unavailable))
                if (!current.networkAvailable) add(AppStrings.s(R.string.app_history_net_unavailable))
            }
            if (missing.isNotEmpty()) {
                Box(Modifier.padding(horizontal = 24.dp)) {
                    AppHistoryWarningBanner(
                        message = missing.joinToString(" "),
                        actionLabel = if (!current.networkAvailable) {
                            AppStrings.s(R.string.app_history_open_usage_settings)
                        } else {
                            null
                        },
                        onAction = { openUsageAccessSettings(context) }
                    )
                }
            }
        }

        when {

            snapshot == null -> {

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            snapshot.rows.isEmpty() -> {

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = AppStrings.s(R.string.app_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            else -> {

                val displayRows = remember(
                    snapshot.rows,
                    sortColumn,
                    sortAscending,
                    snapshot.cpuAvailable
                ) {
                    sortHistoryRows(
                        rows = snapshot.rows,
                        column = sortColumn,
                        ascending = sortAscending,
                        cpuAvailable = snapshot.cpuAvailable
                    )
                }

                // 表头 + 行：横向滚动；纵向滚动由 LazyColumn 自己管
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalState)
                ) {

                    Column(Modifier.width(totalWidth)) {

                        AppHistoryHeader(
                            columns = columns,
                            totalWidth = totalWidth,
                            sortColumn = sortColumn,
                            sortAscending = sortAscending,
                            onSort = { column ->
                                if (sortColumn == column) {
                                    sortAscending = !sortAscending
                                } else {
                                    sortColumn = column
                                    sortAscending = false
                                }
                            },
                            onLongPress = { columnMenuVisible = true }
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            state = listState
                        ) {

                            items(
                                items = displayRows,
                                key = { row -> row.packageName }
                            ) { row ->

                                AppHistoryDataRow(
                                    row = row,
                                    columns = columns,
                                    totalWidth = totalWidth,
                                    cpuAvailable = snapshot.cpuAvailable,
                                    selected = row.packageName == selectedPackage,
                                    onToggleSelect = {
                                        val pkg = row.packageName
                                        selectedPackage = if (selectedPackage == pkg) null else pkg
                                    },
                                    onLongPress = {
                                        selectedPackage = row.packageName
                                        switchTarget = row
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 表头：与用户页同高（27dp 顶部空行 + 26dp 列名行），列名落在第二行。
 * 这样两页表格表头等高，切换页面时不会一高一低。
 * 点列头 = 按该列排序；长按翻开「选择列」。
 */
@Composable
private fun AppHistoryHeader(
    columns: List<AppHistoryColumn>,
    totalWidth: androidx.compose.ui.unit.Dp,
    sortColumn: AppHistoryColumn,
    sortAscending: Boolean,
    onSort: (AppHistoryColumn) -> Unit,
    onLongPress: () -> Unit
) {

    Column(
        Modifier
            .width(totalWidth)
            .height(53.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {

        // 顶部空行：对齐用户页表头的汇总行高度（用户页这里有 27dp 的汇总数值）
        Row(
            Modifier
                .fillMaxWidth()
                .height(27.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPress() })
                }
        ) {}

        Row(
            Modifier
                .width(totalWidth)
                .height(26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            columns.forEach { column ->

                val active = column == sortColumn

                AppHistoryHeaderCell(
                    column = column,
                    active = active,
                    ascending = sortAscending,
                    onTap = { onSort(column) },
                    onLongPress = onLongPress
                )
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun AppHistoryHeaderCell(
    column: AppHistoryColumn,
    active: Boolean,
    ascending: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {

    Row(
        Modifier
            .width(column.widthDp.dp)
            .fillMaxHeight()
            .padding(start = 8.dp, end = 6.dp)
            .pointerInput(column) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {

        Text(
            text = AppStrings.s(column.labelRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (active) {
            Text(
                text = if (ascending) "↑" else "↓",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 一行数据：紧凑单行（图标 + 应用名，不显示第二行包名）；点击切换选中，整行高亮。 */
@Composable
private fun AppHistoryDataRow(
    row: AppHistoryRow,
    columns: List<AppHistoryColumn>,
    totalWidth: androidx.compose.ui.unit.Dp,
    cpuAvailable: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit
) {

    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }

    Row(
        Modifier
            .width(totalWidth)
            .height(34.dp)
            .background(background)
            .pointerInput(row.packageName) {
                detectTapGestures(
                    onTap = { onToggleSelect() },
                    onLongPress = { onLongPress() }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {

        columns.forEach { column ->

            when (column) {

                AppHistoryColumn.NAME -> AppHistoryNameCell(row)

                else -> AppHistoryCell(
                    text = historyCellText(row, column, cpuAvailable),
                    widthDp = column.widthDp
                )
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

/** 名称格：图标 + 应用名（单行，不显示包名）。 */
@Composable
private fun AppHistoryNameCell(row: AppHistoryRow) {

    Row(
        Modifier
            .width(AppHistoryColumn.NAME.widthDp.dp)
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        AppHistoryIcon(row.packageName)

        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppHistoryCell(text: String, widthDp: Int) {

    Box(
        Modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .padding(start = 8.dp, end = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {

        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 某一行某一列的展示文本。取不到对应分量显示「—」，不拿 0 冒充。
 *
 * 网络语义：
 *  - 网络 = 移动(rx+tx) + Wi-Fi(rx+tx)
 *  - 按流量计费 = 移动(rx+tx)；不按流量计费 = Wi-Fi(rx+tx)
 *  - 下载 = 移动 rx + Wi-Fi rx；上载 = 移动 tx + Wi-Fi tx
 */
private fun historyCellText(
    row: AppHistoryRow,
    column: AppHistoryColumn,
    cpuAvailable: Boolean
): String = when (column) {

    AppHistoryColumn.CPU ->
        if (cpuAvailable) formatCpuMillis(row.cpuTimeMillis) else "—"

    AppHistoryColumn.NETWORK ->
        netCell(nullableSum(row.mobileRxBytes, row.mobileTxBytes, row.wifiRxBytes, row.wifiTxBytes))

    AppHistoryColumn.METERED ->
        netCell(nullableSum(row.mobileRxBytes, row.mobileTxBytes))

    AppHistoryColumn.NOTIFICATION ->
        row.notificationCount?.toString() ?: "—"

    AppHistoryColumn.UNMETERED ->
        netCell(nullableSum(row.wifiRxBytes, row.wifiTxBytes))

    AppHistoryColumn.DOWNLOAD ->
        netCell(nullableSum(row.mobileRxBytes, row.wifiRxBytes))

    AppHistoryColumn.UPLOAD ->
        netCell(nullableSum(row.mobileTxBytes, row.wifiTxBytes))

    AppHistoryColumn.NAME -> row.label
}

/** 几个可空分量求和：全为 null 返回 null（该格「—」）；否则只累加非 null 分量。 */
private fun nullableSum(vararg parts: Long?): Long? {
    if (parts.all { it == null }) return null
    return parts.filterNotNull().sum()
}

/** 网络单格：通道取不到（null）显示「—」，不拿 0 冒充。 */
private fun netCell(bytes: Long?): String =
    if (bytes == null) "—" else formatBytes(bytes)

// ---------- 排序 ----------

/** 某行在某数值列上的取值；null = 空值（「—」），排序时恒排最后。 */
private fun historyNumericValue(
    row: AppHistoryRow,
    column: AppHistoryColumn,
    cpuAvailable: Boolean
): Long? = when (column) {

    AppHistoryColumn.CPU -> if (cpuAvailable) row.cpuTimeMillis else null

    AppHistoryColumn.NETWORK ->
        nullableSum(row.mobileRxBytes, row.mobileTxBytes, row.wifiRxBytes, row.wifiTxBytes)

    AppHistoryColumn.METERED ->
        nullableSum(row.mobileRxBytes, row.mobileTxBytes)

    AppHistoryColumn.NOTIFICATION -> row.notificationCount

    AppHistoryColumn.UNMETERED ->
        nullableSum(row.wifiRxBytes, row.wifiTxBytes)

    AppHistoryColumn.DOWNLOAD ->
        nullableSum(row.mobileRxBytes, row.wifiRxBytes)

    AppHistoryColumn.UPLOAD ->
        nullableSum(row.mobileTxBytes, row.wifiTxBytes)

    AppHistoryColumn.NAME -> null
}

private fun sortHistoryRows(
    rows: List<AppHistoryRow>,
    column: AppHistoryColumn,
    ascending: Boolean,
    cpuAvailable: Boolean
): List<AppHistoryRow> {

    // 名称列按字母排序；其余列按数值大小，空值（null）恒排最后
    if (column == AppHistoryColumn.NAME) {

        val byLabel = compareBy<AppHistoryRow> { it.label.lowercase(Locale.US) }

        return if (ascending) rows.sortedWith(byLabel) else rows.sortedWith(byLabel.reversed())
    }

    val value: (AppHistoryRow) -> Long? = { historyNumericValue(it, column, cpuAvailable) }

    val nullsLast = compareBy<AppHistoryRow> { value(it) == null }

    val numeric = if (ascending) compareBy(value) else compareByDescending(value)

    return rows.sortedWith(nullsLast then numeric)
}

/**
 * 顶部警告条：红底叹号 + 原因（与用户页失败提示的叹号一致，只是改成行内横幅）。
 *
 * [actionLabel] 非空时末尾给一个 TextButton（无提权通道、需要用户手动授权使用情况访问时用）。
 */
@Composable
private fun AppHistoryWarningBanner(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        Box(
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "!",
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )

        if (actionLabel != null && onAction != null) {

            TextButton(onClick = onAction) {

                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/**
 * 打开「使用情况访问」系统设置页（GET_USAGE_STATS 的手动授权入口）；
 * 个别 ROM 没有这个入口时静默失败，不弹窗。
 */
private fun openUsageAccessSettings(context: Context) {

    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(intent) }
}

/**
 * 长按表头：勾选要显示的列（名称固定显示，所以不列出来）。
 * 对话框内按枚举顺序排列；点击任意项立即切换显示并关闭对话框——与用户页的「选择列」一致。
 */
@Composable
private fun AppHistoryColumnsDialog(
    visibleColumns: Set<AppHistoryColumn>,
    onDismiss: () -> Unit,
    onToggle: (AppHistoryColumn) -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.s(R.string.app_history_select_columns_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                AppHistoryColumn.values()
                    .filter { !it.fixed }
                    .forEach { column ->

                        AppHistoryMenuItem(
                            label = AppStrings.s(column.labelRes),
                            checked = column in visibleColumns,
                            onClick = { onToggle(column) }
                        )
                    }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/** 对话框里的勾选行：已启用项左侧一个 ✓（与用户页选择列同款）。 */
@Composable
private fun ColumnScope.AppHistoryMenuItem(
    label: String,
    checked: Boolean,
    onClick: () -> Unit
) {

    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
    ) {

        Box(
            Modifier.width(20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // TextButton 默认拿 primary（蓝），这里跟其他页的菜单一样用正文黑
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 应用图标：缓存命中直接画，未命中去 IO 线程解码；没有图标用 Apps 兜底。 */
@Composable
private fun AppHistoryIcon(packageName: String) {

    var icon by remember(packageName) {
        mutableStateOf(ProcessIconCache.cached(packageName))
    }

    LaunchedEffect(packageName) {

        if (!ProcessIconCache.isKnown(packageName)) {

            icon = withContext(Dispatchers.IO) { ProcessIconCache.load(packageName) }
        }
    }

    if (icon != null) {

        Image(
            bitmap = icon as ImageBitmap,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        return
    }

    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Apps,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

// ---------- 选择列持久化 ----------

/**
 * 长按「切换到」：直接拉起该包的活动页。
 *
 * 系统服务进程 / 没有 launcher 入口的包取不到启动 Intent，这里静默返回；
 * startActivity 失败也全部吞掉，不弹错误——用户要求“启动不了也当启动过了”。
 */
private fun launchAppActivity(context: Context, packageName: String) {

    val launchIntent = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName)
    }.getOrNull() ?: runCatching {
        context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
    }.getOrNull() ?: return

    launchIntent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    )

    runCatching { context.startActivity(launchIntent) }
}

private fun loadVisibleAppHistoryColumns(context: Context): Set<AppHistoryColumn> {

    val prefs = context.getSharedPreferences(
        APP_HISTORY_VISIBLE_COLUMNS_PREFS,
        Context.MODE_PRIVATE
    )

    val saved = prefs.getStringSet(APP_HISTORY_VISIBLE_COLUMNS_KEY, null)
        ?: return AppHistoryColumn.values().toSet()

    // 旧版本持久化过一版「移动/Wi-Fi · 下载/上载」四格枚举名，这里映射回新的分组列，
    // 避免老用户原来勾着的按流量计费 / 不按流量计费网络在迁移后被隐藏。
    val legacyMapping = mapOf(
        "METERED_RX" to AppHistoryColumn.METERED,
        "METERED_TX" to AppHistoryColumn.METERED,
        "WIFI_RX" to AppHistoryColumn.UNMETERED,
        "WIFI_TX" to AppHistoryColumn.UNMETERED
    )

    val parsed = saved.mapNotNull { name ->
        runCatching { AppHistoryColumn.valueOf(name) }.getOrNull()
            ?: legacyMapping[name]
    }.toSet()

    // 迁移：新增列默认可见；用户先前取消勾选的旧列（例如 CPU）保持取消，
    // 绝不再强行打开。
    val migrated = parsed + APP_HISTORY_NEW_DEFAULT_COLUMNS

    // 名称列永远在
    return migrated + AppHistoryColumn.NAME
}

private fun saveVisibleAppHistoryColumns(context: Context, columns: Set<AppHistoryColumn>) {

    context.getSharedPreferences(APP_HISTORY_VISIBLE_COLUMNS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(APP_HISTORY_VISIBLE_COLUMNS_KEY, columns.map { it.name }.toSet())
        .apply()
}

// ---------- 格式化 ----------

/** 开账时刻 → yyyy/MM/dd HH:mm。 */
private fun formatHistoryTime(beginMs: Long): String {

    if (beginMs <= 0L) return "—"

    return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(beginMs))
}

/** CPU 毫秒 → mm:ss（满 1 小时转 h:mm:ss）。 */
private fun formatCpuMillis(millis: Long): String {

    val totalSec = millis / 1000L

    val hours = totalSec / 3600L

    val minutes = (totalSec % 3600L) / 60L

    val secs = totalSec % 60L

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

/** 字节数 → B / KB / MB / GB（两位以内小数）。 */
private fun formatBytes(bytes: Long): String = when {

    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)

    else -> String.format(Locale.US, "%d B", bytes)
}
