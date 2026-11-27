package com.android.taskmanager

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.taskmanager.data.ProcessIconCache
import com.android.taskmanager.service.UIService
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用户页已实现（Android 用户视图）。 */
val UsersPageState: PageState = PageState.IMPLEMENTED

/** 非活跃时每秒轮询一次；用户页的数值和详细信息页一样来自后端快照。 */
private const val USERS_IDLE_POLL_MS = 1000L

/**
 * 用户页的列。默认只开 Windows 用户页原有的「状态 / CPU / 内存 / 磁盘 / 网络」，
 * ID、会话、客户端名称、GPU、GPU 引擎先作为可勾选列，Android 没有对应数据时留空。
 */
private enum class UsersColumn(
    val defaultWidthDp: Int,
    val defaultVisible: Boolean,
    val labelRes: Int
) {
    USER(232, true, R.string.users_col_user),
    ID(88, false, R.string.users_col_id),
    SESSION(100, false, R.string.users_col_session),
    CLIENT_NAME(140, false, R.string.users_col_client_name),
    STATUS(92, true, R.string.users_col_status),
    CPU(112, true, R.string.users_col_cpu),
    MEMORY(128, true, R.string.users_col_memory),
    DISK(128, true, R.string.users_col_disk),
    NETWORK(128, true, R.string.users_col_network),
    GPU(100, false, R.string.users_col_gpu),
    GPU_ENGINE(126, false, R.string.users_col_gpu_engine)
}

private const val USERS_COLUMN_MIN_WIDTH_DP = 60
private const val USERS_COLUMN_MAX_WIDTH_DP = 420
private const val USERS_COLUMN_RESIZE_TOUCH_DP = 18

/** 「资源值」子菜单控制的三个指标。 */
private enum class UsersResourceKind {
    MEMORY,
    DISK,
    NETWORK
}

/** 每个指标显示原始值还是百分比。 */
private enum class UsersResourceMode {
    PERCENT,
    VALUE
}

private data class UsersResourceModes(
    val memory: UsersResourceMode = UsersResourceMode.VALUE,
    val disk: UsersResourceMode = UsersResourceMode.VALUE,
    val network: UsersResourceMode = UsersResourceMode.VALUE
) {

    fun modeOf(kind: UsersResourceKind): UsersResourceMode = when (kind) {
        UsersResourceKind.MEMORY -> memory
        UsersResourceKind.DISK -> disk
        UsersResourceKind.NETWORK -> network
    }

    fun with(kind: UsersResourceKind, mode: UsersResourceMode): UsersResourceModes = when (kind) {
        UsersResourceKind.MEMORY -> copy(memory = mode)
        UsersResourceKind.DISK -> copy(disk = mode)
        UsersResourceKind.NETWORK -> copy(network = mode)
    }
}

/**
 * 「用户」页：只贴 Android 的用户机制。
 *
 * 页面结构参考 Windows 任务管理器「用户」选项卡：
 * 表头是汇总（CPU / 内存 / 磁盘 / 网络），下面每个用户一行；
 * 展开后直接是进程行，不再套 Linux uid 分组。
 *
 * 长按用户 → 用户账户菜单；长按进程 → 进程菜单（字母与分隔线都按截图保留）。
 */
@Composable
fun UsersScreen(
    onOpenDetails: (pid: Int, startTicks: Long) -> Unit = { _, _ -> }
) {

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val totalMemoryBytes = remember { readTotalMemoryBytes(context) }

    var table by remember { mutableStateOf(AndroidUsersTable()) }

    var loaded by remember { mutableStateOf(false) }

    var refreshKey by remember { mutableStateOf(0) }

    val expanded = remember { mutableStateMapOf<Int, Boolean>() }

    // 行选择：整表单选。用户行 key=user-<id>，进程行 key=process-<pid>-<startTicks>
    var selectedRowKey by remember { mutableStateOf<String?>(null) }

    var userMenuTarget by remember { mutableStateOf<AndroidUserSummary?>(null) }

    var processMenuTarget by remember { mutableStateOf<UserProcessRow?>(null) }

    // 壳进程的「打开文件所在的位置」二选一：壳文件本身，或所属应用 APK
    var locationTarget by remember { mutableStateOf<UserProcessRow?>(null) }

    var resourceMenuVisible by remember { mutableStateOf(false) }

    var columnMenuVisible by remember { mutableStateOf(false) }

    var resourceModeKind by remember { mutableStateOf<UsersResourceKind?>(null) }

    var resourceModes by remember { mutableStateOf(loadUsersResourceModes(context)) }

    var propertyTarget by remember { mutableStateOf<UserProcessRow?>(null) }

    var killTarget by remember { mutableStateOf<UserProcessRow?>(null) }

    var dumpResult by remember { mutableStateOf<ProcessDumpResult?>(null) }

    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {

        loaded = false

        while (true) {

            val next = withContext(Dispatchers.IO) {
                UIService.instanceOrNull()?.androidUsersTable()
            }

            table = next ?: AndroidUsersTable()

            loaded = true

            delay(USERS_IDLE_POLL_MS)
        }
    }

    val serviceAvailable = loaded

    if (!serviceAvailable) {

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        return
    }

    if (table.users.isEmpty()) {

        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = AppStrings.s(R.string.users_android_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        return
    }

    val maxDiskRate = table.users.maxOfOrNull { it.diskBytesPerSecond } ?: 0L

    val maxNetworkRate = table.users
        .mapNotNull { it.networkBytesPerSecond }
        .maxOrNull() ?: 0L

    var widths by remember { mutableStateOf(loadUsersColumnWidths(context)) }

    var visibleColumns by remember { mutableStateOf(loadVisibleUsersColumns(context)) }

    val columns = UsersColumn.values().filter { it in visibleColumns }

    val horizontalState = rememberScrollState()

    val totalWidth = columns.sumOf { widths[it] ?: it.defaultWidthDp }.dp

    // 横向滚动放在最外层：表头与所有行共用同一个 scrollState，拖表头时列和行一起动。
    // 表头放在 LazyColumn 外面，所以只滚不随下翻上移；LazyColumn 只负责行和纵向滚动。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(horizontalState)
    ) {

        Column(
            modifier = Modifier
                .width(totalWidth)
                .fillMaxHeight()
        ) {

            UsersHeader(
                columns = columns,
                widths = widths,
                cpuPercent = table.totalCpuPercent,
                memoryPercent = memoryPercent(table.totalMemoryBytes, totalMemoryBytes),
                diskRate = table.totalDiskBytesPerSecond,
                networkRate = table.totalNetworkBytesPerSecond,
                onColumnMenu = { columnMenuVisible = true },
                onColumnWidthChange = { column, widthDp ->
                    widths = widths + (column to widthDp)
                },
                onColumnWidthCommit = { column, widthDp ->
                    saveUsersColumnWidth(context, column, widthDp)
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Top
            ) {

                table.users.forEach { user ->

                    val isExpanded = expanded[user.userId] == true

                    item(key = "user-${user.userId}") {

                        UsersUserRow(
                            user = user,
                            expanded = isExpanded,
                            selected = selectedRowKey == "user-${user.userId}",
                            columns = columns,
                            widths = widths,
                            totalMemoryBytes = totalMemoryBytes,
                            maxDiskRate = maxDiskRate,
                            maxNetworkRate = maxNetworkRate,
                            resourceModes = resourceModes,
                            onToggle = {
                                // 读 map 里的当前值，而不是捕获本轮 isExpanded：
                                // pointerInput 的 key 不变时不会重启协程，捕获旧值会导致第二次点击无效
                                expanded[user.userId] = !(expanded[user.userId] == true)
                            },
                            onSelect = {
                                val key = "user-${user.userId}"
                                selectedRowKey = if (selectedRowKey == key) null else key
                            },
                            onLongPress = { userMenuTarget = user }
                        )
                    }

                    if (isExpanded) {

                        val processes = user.groups
                            .flatMap { it.processes }
                            .sortedByDescending { it.cpuPercent }

                        if (processes.isEmpty()) {

                            item(key = "user-${user.userId}-empty") {
                                Text(
                                    text = AppStrings.s(R.string.users_android_no_apps),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .width((widths[UsersColumn.USER] ?: 232).dp)
                                        .padding(start = 40.dp, top = 4.dp, bottom = 8.dp)
                                )
                            }
                        }

                        items(
                            items = processes,
                            key = { process -> "user-${user.userId}-p-${process.pid}-${process.startTicks}" }
                        ) { process ->

                            val processKey = "process-${process.pid}-${process.startTicks}"

                            UsersProcessRow(
                                process = process,
                                userId = user.userId,
                                selected = selectedRowKey == processKey,
                                columns = columns,
                                widths = widths,
                                totalMemoryBytes = totalMemoryBytes,
                                maxDiskRate = maxDiskRate,
                                maxNetworkRate = maxNetworkRate,
                                resourceModes = resourceModes,
                                onSelect = {
                                    selectedRowKey = if (selectedRowKey == processKey) null else processKey
                                },
                                onLongPress = { processMenuTarget = process }
                            )
                        }
                    }
                }

                item(key = "users-bottom-space") {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    userMenuTarget?.let { user ->

        UsersActionDialog(
            onDismiss = { userMenuTarget = null },
            expanded = expanded[user.userId] == true,
            onToggle = {
                expanded[user.userId] = !(expanded[user.userId] == true)
                userMenuTarget = null
            },
            onConnect = {
                userMenuTarget = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runUserAction(user.userId, AndroidUserAction.CONNECT)
                    }
                    if (result.ok) {
                        refreshKey += 1
                    } else {
                        failure = result.reason.ifBlank {
                            AppStrings.s(R.string.details_operation_failed)
                        }
                    }
                }
            },
            onStop = {
                userMenuTarget = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runUserAction(user.userId, AndroidUserAction.STOP)
                    }
                    if (result.ok) {
                        refreshKey += 1
                    } else {
                        failure = result.reason.ifBlank {
                            AppStrings.s(R.string.details_operation_failed)
                        }
                    }
                }
            },
            onSwitch = {
                userMenuTarget = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runUserAction(user.userId, AndroidUserAction.SWITCH)
                    }
                    if (result.ok) {
                        refreshKey += 1
                    } else {
                        failure = result.reason.ifBlank {
                            AppStrings.s(R.string.details_operation_failed)
                        }
                    }
                }
            },
            onManage = {
                userMenuTarget = null
                openUserSettings(context)
            }
        )
    }

    processMenuTarget?.let { process ->

        UsersProcessMenuDialog(
            onDismiss = { processMenuTarget = null },
            locationEnabled = hasUserProcessExecutablePath(process),
            onEndTask = {
                processMenuTarget = null
                killTarget = process
            },
            onResourceValues = {
                processMenuTarget = null
                resourceMenuVisible = true
            },
            onFeedback = {
                processMenuTarget = null
                openFeedback(context)
            },
            onGotoDetails = {
                processMenuTarget = null
                UIService.instanceOrNull()?.selectDetailRow(process.pid, process.startTicks)
                onOpenDetails(process.pid, process.startTicks)
            },
            onOpenLocation = {
                processMenuTarget = null
                // 与详细页一致：原生进程直接打开；壳进程先二选一
                if (isAppProcessExecutable(process.exePath.orEmpty())) {
                    locationTarget = process
                } else {
                    openProcessLocation(context, process)
                }
            },
            onSearch = {
                processMenuTarget = null
                openWebSearch(context, process.name)
            },
            onProperties = {
                processMenuTarget = null
                propertyTarget = process
            },
            onDump = {
                processMenuTarget = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        UIService.instanceOrNull()
                            ?.dumpDetailProcessMemory(process.pid, process.startTicks)
                    }

                    if (result == null) {
                        failure = AppStrings.s(R.string.details_service_not_ready)
                    } else if (!result.ok) {
                        failure = result.reason.ifBlank {
                            AppStrings.s(R.string.details_operation_failed)
                        }
                    } else {
                        dumpResult = result
                    }
                }
            }
        )
    }

    // 壳进程打开位置：二选一，与详细信息页完全一致
    locationTarget?.let { target ->

        val apkPath = remember(target.pid, target.startTicks, target.packageName, target.packageDisplayName) {
            resolveUserProcessApk(context, target)
        }

        UsersLocationDialog(
            packageEnabled = apkPath != null,
            onDismiss = { locationTarget = null },
            onOpenFile = {
                locationTarget = null
                openUserProcessExecutable(context, target)
            },
            onOpenPackage = {
                locationTarget = null
                apkPath?.let { openFilePath(context, it) }
            }
        )
    }

    if (columnMenuVisible) {

        UsersColumnsDialog(
            visibleColumns = visibleColumns,
            onDismiss = { columnMenuVisible = false },
            onToggle = { column ->
                val next = if (column in visibleColumns) {
                    visibleColumns - column
                } else {
                    visibleColumns + column
                } + UsersColumn.USER

                visibleColumns = next
                saveVisibleUsersColumns(context, next)
                columnMenuVisible = false
            },
            onResourceValues = {
                columnMenuVisible = false
                resourceMenuVisible = true
            }
        )
    }

    if (resourceMenuVisible) {

        UsersResourceMenuDialog(
            onDismiss = { resourceMenuVisible = false },
            onPick = { kind ->
                resourceMenuVisible = false
                resourceModeKind = kind
            }
        )
    }

    resourceModeKind?.let { kind ->

        UsersResourceModeDialog(
            kind = kind,
            current = resourceModes.modeOf(kind),
            onDismiss = { resourceModeKind = null },
            onSelect = { mode ->
                resourceModes = resourceModes.with(kind, mode)
                saveUsersResourceMode(context, kind, mode)
                resourceModeKind = null
            }
        )
    }

    propertyTarget?.let { process ->

        UsersPropertiesDialog(
            process = process,
            onDismiss = { propertyTarget = null }
        )
    }

    killTarget?.let { process ->

        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = {
                Text(
                    text = AppStrings.s(R.string.details_end_confirm, process.name),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = AppStrings.s(R.string.details_end_warning_app) +
                        AppStrings.s(R.string.details_end_warning_system),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        killTarget = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                UIService.instanceOrNull()
                                    ?.killDetailProcess(process.pid, process.startTicks)
                            }
                            if (result?.ok == true) {
                                refreshKey += 1
                            } else {
                                failure = result?.reason
                                    ?.takeIf { it.isNotBlank() }
                                    ?: AppStrings.s(R.string.details_service_not_ready)
                            }
                        }
                    }
                ) {
                    Text(AppStrings.s(R.string.details_end_process))
                }
            },
            dismissButton = {
                TextButton(onClick = { killTarget = null }) {
                    Text(AppStrings.s(R.string.common_cancel))
                }
            }
        )
    }

    dumpResult?.let { result ->

        AlertDialog(
            onDismissRequest = { dumpResult = null },
            title = { Text(AppStrings.s(R.string.details_dump_title)) },
            text = {
                Text(
                    if (result.ok) {
                        AppStrings.s(
                            R.string.details_dump_done,
                            result.path,
                            formatBytes(result.bytes),
                            result.segments
                        )
                    } else {
                        AppStrings.s(R.string.details_dump_failed, result.reason)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { dumpResult = null }) {
                    Text(AppStrings.s(R.string.common_ok))
                }
            }
        )
    }

    failure?.let { message ->

        UsersFailureDialog(
            message = message,
            onDismiss = { failure = null }
        )
    }
}

/**
 * 表头：第一行是汇总数值（8% / 91% / 0 / 0），第二行才是列名。
 * 汇总值不是 0 占位：没有网络统计时显示「—」。
 *
 * 表头固定在 LazyColumn 顶部；横向跟表格一起滚。
 * 按住任意列的右边界左右拖 = 改这一列的宽度。
 */
@Composable
private fun UsersHeader(
    columns: List<UsersColumn>,
    widths: Map<UsersColumn, Int>,
    cpuPercent: Double,
    memoryPercent: Double,
    diskRate: Long,
    networkRate: Long?,
    onColumnMenu: () -> Unit,
    onColumnWidthChange: (UsersColumn, Int) -> Unit,
    onColumnWidthCommit: (UsersColumn, Int) -> Unit
) {

    val density = LocalDensity.current

    val latestColumns by rememberUpdatedState(columns)

    val totalWidth = columns.sumOf { widths[it] ?: it.defaultWidthDp }.dp

    val latestWidths by rememberUpdatedState(widths)

    // 拖动协程常驻，回调必须取最新一版，否则连续拖两列时第二列会拿第一次组合的 widths 覆盖回去
    val currentWidthChange by rememberUpdatedState(onColumnWidthChange)
    val currentWidthCommit by rememberUpdatedState(onColumnWidthCommit)
    val currentColumnMenu by rememberUpdatedState(onColumnMenu)

    val resizeTouchPx = with(density) { USERS_COLUMN_RESIZE_TOUCH_DP.dp.toPx() }

    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Column(
        Modifier
            .width(totalWidth)
            .background(MaterialTheme.colorScheme.surfaceVariant)

            // 列边界线：既分开两列，也提示"竖线就是拖宽把手"
            .drawBehind {

                val stroke = 1.dp.toPx()

                var edge = 0f

                latestColumns.forEachIndexed { index, column ->

                    edge += (latestWidths[column] ?: column.defaultWidthDp).dp.toPx()

                    if (index != latestColumns.lastIndex && edge <= size.width) {

                        drawLine(
                            color = dividerColor,
                            start = Offset(edge - stroke / 2f, 0f),
                            end = Offset(edge - stroke / 2f, size.height),
                            strokeWidth = stroke
                        )
                    }
                }
            }

            // 拖宽：按下点落在某列右边界 ±18dp 才接管；没过触摸阈值不消费，横向滚动照常
            .pointerInput(Unit) {

                awaitEachGesture {

                    val down = awaitFirstDown(requireUnconsumed = false)

                    var hitColumn: UsersColumn? = null
                    var hitStartWidth = 0
                    var boundary = 0f

                    for (column in latestColumns) {

                        val widthDp = latestWidths[column] ?: column.defaultWidthDp

                        boundary += widthDp.dp.toPx()

                        if (abs(down.position.x - boundary) <= resizeTouchPx) {
                            hitColumn = column
                            hitStartWidth = widthDp
                            break
                        }
                    }

                    val column = hitColumn ?: return@awaitEachGesture

                    var accumulatedDx = 0f
                    var dragging = false
                    var width = hitStartWidth

                    while (true) {

                        val event = awaitPointerEvent()

                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (change.changedToUpIgnoreConsumed()) break

                        accumulatedDx += change.positionChange().x

                        if (!dragging && abs(accumulatedDx) > viewConfiguration.touchSlop) {
                            dragging = true
                        }

                        if (dragging) {

                            width = (hitStartWidth + accumulatedDx / density.density)
                                .toInt()
                                .coerceIn(USERS_COLUMN_MIN_WIDTH_DP, USERS_COLUMN_MAX_WIDTH_DP)

                            currentWidthChange(column, width)

                            change.consume()
                        }
                    }

                    if (dragging) currentWidthCommit(column, width)
                }
            }
    ) {

        Row(
            Modifier
                .width(totalWidth)
                .height(27.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            columns.forEach { column ->

                val widthDp = widths[column] ?: column.defaultWidthDp

                when (column) {
                    UsersColumn.CPU -> UsersHeaderValueSummary(
                        value = String.format(Locale.getDefault(), "%.0f%%", cpuPercent),
                        widthDp = widthDp
                    )

                    UsersColumn.MEMORY -> UsersHeaderValueSummary(
                        value = String.format(Locale.getDefault(), "%.0f%%", memoryPercent),
                        widthDp = widthDp
                    )

                    UsersColumn.DISK -> UsersHeaderValueSummary(
                        value = formatRatePerSecond(diskRate),
                        widthDp = widthDp
                    )

                    UsersColumn.NETWORK -> UsersHeaderValueSummary(
                        value = networkRate?.let { formatBitsPerSecond(it) }
                            ?: AppStrings.s(R.string.users_no_value),
                        widthDp = widthDp
                    )

                    else -> Spacer(Modifier.width(widthDp.dp))
                }
            }
        }

        // 长按列名这一行打开「选择列」；拖动列宽仍由外层 pointerInput 处理
        Row(
            Modifier
                .width(totalWidth)
                .height(26.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { currentColumnMenu() })
                },
            verticalAlignment = Alignment.CenterVertically
        ) {

            columns.forEach { column ->

                UsersHeaderLabel(
                    text = AppStrings.s(column.labelRes),
                    widthDp = widths[column] ?: column.defaultWidthDp
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun UsersHeaderLabel(
    text: String,
    widthDp: Int
) {

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
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UsersHeaderValueSummary(
    value: String,
    widthDp: Int
) {

    Box(
        Modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .padding(start = 8.dp, end = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {

        Text(
            text = value,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 用户行：chevron + 用户图标 + 名称/进程数 + 状态 + 四项指标。
 * 左侧箭头只负责展开 / 收起；点该行其它区域只做选中；长按弹账户菜单。
 */
@Composable
private fun UsersUserRow(
    user: AndroidUserSummary,
    expanded: Boolean,
    selected: Boolean,
    columns: List<UsersColumn>,
    widths: Map<UsersColumn, Int>,
    totalMemoryBytes: Long,
    maxDiskRate: Long,
    maxNetworkRate: Long,
    resourceModes: UsersResourceModes,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onLongPress: () -> Unit
) {

    val totalWidth = columns.sumOf { widths[it] ?: it.defaultWidthDp }.dp

    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f)
    }

    // pointerInput 只在 key 变化时重启；用 rememberUpdatedState 保证点击时调用的是最新回调，
    // 否则行展开后仍会调用第一次组合时捕获的旧 onToggle，表现为“只能展开、关不上”。
    val currentToggle by rememberUpdatedState(onToggle)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentLongPress by rememberUpdatedState(onLongPress)

    val userWidth = widths[UsersColumn.USER] ?: UsersColumn.USER.defaultWidthDp

    // 主标题：账户名（用户 N）。读不到账户名时 label 本身就是「用户 N」，不再重复追加。
    val fallbackName = AppStrings.s(R.string.users_android_title, user.userId)

    val displayName = if (user.userId < 0 || user.label == fallbackName) {
        user.label
    } else {
        AppStrings.s(R.string.users_user_name, user.label, user.userId)
    }

    Row(
        modifier = Modifier
            .width(totalWidth)
            .height(52.dp)
            .background(background),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // 左侧箭头：只负责展开 / 收起，点它不触发选中；长按仍弹账户菜单
        Box(
            Modifier
                .width(28.dp)
                .fillMaxHeight()
                .pointerInput(user.userId) {
                    detectTapGestures(
                        onTap = { currentToggle() },
                        onLongPress = { currentLongPress() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.Default.KeyboardArrowRight
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 箭头以外的区域：点击选择，长按弹账户菜单
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(user.userId) {
                    detectTapGestures(
                        onTap = { currentSelect() },
                        onLongPress = { currentLongPress() }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {

            Row(
                Modifier
                    .width((userWidth - 28).dp)
                    .fillMaxHeight()
                    .padding(start = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    UsersUserAvatar(userId = user.userId)
                }

                Spacer(Modifier.width(8.dp))

                Column(Modifier.weight(1f)) {

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = AppStrings.s(R.string.users_processes_count, user.processCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            columns.forEach { column ->

                if (column == UsersColumn.USER) return@forEach

                UsersUserDataCell(
                    column = column,
                    user = user,
                    widthDp = widths[column] ?: column.defaultWidthDp,
                    totalMemoryBytes = totalMemoryBytes,
                    maxDiskRate = maxDiskRate,
                    maxNetworkRate = maxNetworkRate,
                    resourceModes = resourceModes
                )
            }
        }
    }
}

/**
 * 用户行头像：只从 root 通道读系统保存的用户头像；
 * 没有 root 或读不到时退回通用账户图标。
 */
@Composable
private fun UsersUserAvatar(userId: Int) {

    var avatar by remember(userId) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(userId) {
        avatar = loadAndroidUserAvatar(userId)
    }

    val bitmap = avatar

    if (bitmap != null) {

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
        )

        return
    }

    Icon(
        imageVector = Icons.Default.SupervisorAccount,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 读某个 Android 用户的头像：只走 root 读系统保存的
 * `/data/system/users/<id>/photo.*`，读不到返回 null，由界面显示通用图标。
 */
private suspend fun loadAndroidUserAvatar(userId: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {

        val raw = runCatching {
            UIService.instanceOrNull()?.androidUserAvatar(userId)
        }.getOrNull()

        if (raw == null || raw.isEmpty()) return@withContext null

        runCatching {
            BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
        }.getOrNull()
    }

/**
 * 进程行：缩进 + 应用图标 + 进程名 + 数值。
 * 状态列留空（和 Windows 一样只对用户标记已连接/运行中）；点击选中，长按弹进程菜单。
 */
@Composable
private fun UsersProcessRow(
    process: UserProcessRow,
    userId: Int,
    selected: Boolean,
    columns: List<UsersColumn>,
    widths: Map<UsersColumn, Int>,
    totalMemoryBytes: Long,
    maxDiskRate: Long,
    maxNetworkRate: Long,
    resourceModes: UsersResourceModes,
    onSelect: () -> Unit,
    onLongPress: () -> Unit
) {

    val totalWidth = columns.sumOf { widths[it] ?: it.defaultWidthDp }.dp

    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }

    // 同一进程 key 不变时 pointerInput 不会重启，长按必须取最新快照，否则菜单里是旧数值
    val currentSelect by rememberUpdatedState(onSelect)
    val currentLongPress by rememberUpdatedState(onLongPress)

    Row(
        modifier = Modifier
            .width(totalWidth)
            .height(44.dp)
            .background(background)
            .pointerInput(process.pid, process.startTicks) {
                detectTapGestures(
                    onTap = { currentSelect() },
                    onLongPress = { currentLongPress() }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {

        Row(
            Modifier
                .width((widths[UsersColumn.USER] ?: UsersColumn.USER.defaultWidthDp).dp)
                .fillMaxHeight()
                .padding(start = 34.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            UsersProcessIcon(process.packageName, userId)

            Spacer(Modifier.width(8.dp))

            Text(
                text = process.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        columns.forEach { column ->

            if (column == UsersColumn.USER) return@forEach

            UsersProcessDataCell(
                column = column,
                process = process,
                widthDp = widths[column] ?: column.defaultWidthDp,
                totalMemoryBytes = totalMemoryBytes,
                maxDiskRate = maxDiskRate,
                maxNetworkRate = maxNetworkRate,
                resourceModes = resourceModes
            )
        }
    }
}

/** 用户行里除「用户」身份列以外的单元格。 */
@Composable
private fun UsersUserDataCell(
    column: UsersColumn,
    user: AndroidUserSummary,
    widthDp: Int,
    totalMemoryBytes: Long,
    maxDiskRate: Long,
    maxNetworkRate: Long,
    resourceModes: UsersResourceModes
) {

    when (column) {
        UsersColumn.ID -> UsersValueCell(
            text = if (user.userId >= 0) user.userId.toString() else "",
            fraction = 0.0,
            widthDp = widthDp
        )

        UsersColumn.STATUS -> UsersStatusCell(
            text = user.status,
            widthDp = widthDp,
            compact = false
        )

        UsersColumn.CPU -> UsersValueCell(
            text = String.format(Locale.getDefault(), "%.0f%%", user.cpuPercent),
            fraction = (user.cpuPercent / 100.0).coerceIn(0.0, 1.0),
            widthDp = widthDp,
            emphasize = user.cpuPercent >= 1.0
        )

        UsersColumn.MEMORY -> UsersValueCell(
            text = if (resourceModes.memory == UsersResourceMode.PERCENT) {
                resourcePercentText(user.memoryBytes, totalMemoryBytes)
            } else {
                formatMemory(user.memoryBytes)
            },
            fraction = fraction(user.memoryBytes, totalMemoryBytes),
            widthDp = widthDp
        )

        UsersColumn.DISK -> UsersValueCell(
            text = if (resourceModes.disk == UsersResourceMode.PERCENT) {
                resourcePercentText(user.diskBytesPerSecond, maxDiskRate)
            } else {
                formatRatePerSecond(user.diskBytesPerSecond)
            },
            fraction = fraction(user.diskBytesPerSecond, maxDiskRate),
            widthDp = widthDp
        )

        UsersColumn.NETWORK -> UsersValueCell(
            text = when {
                user.networkBytesPerSecond == null -> AppStrings.s(R.string.users_no_value)
                resourceModes.network == UsersResourceMode.PERCENT ->
                    resourcePercentText(user.networkBytesPerSecond, maxNetworkRate)
                else -> formatBitsPerSecond(user.networkBytesPerSecond)
            },
            fraction = user.networkBytesPerSecond
                ?.let { fraction(it, maxNetworkRate) } ?: 0.0,
            widthDp = widthDp
        )

        // Android 没有对应概念，列可以显示，但格子留空
        UsersColumn.SESSION,
        UsersColumn.CLIENT_NAME,
        UsersColumn.GPU,
        UsersColumn.GPU_ENGINE -> Spacer(Modifier.width(widthDp.dp))

        UsersColumn.USER -> Unit
    }
}

/** 进程行里除「用户」身份列以外的单元格。 */
@Composable
private fun UsersProcessDataCell(
    column: UsersColumn,
    process: UserProcessRow,
    widthDp: Int,
    totalMemoryBytes: Long,
    maxDiskRate: Long,
    maxNetworkRate: Long,
    resourceModes: UsersResourceModes
) {

    when (column) {
        UsersColumn.ID -> UsersValueCell(
            text = process.pid.toString(),
            fraction = 0.0,
            widthDp = widthDp
        )

        UsersColumn.STATUS -> UsersStatusCell(
            text = process.state,
            widthDp = widthDp,
            compact = true
        )

        UsersColumn.CPU -> UsersValueCell(
            text = String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent),
            fraction = (process.cpuPercent / 100.0).coerceIn(0.0, 1.0),
            widthDp = widthDp,
            emphasize = process.cpuPercent >= 1.0
        )

        UsersColumn.MEMORY -> UsersValueCell(
            text = if (resourceModes.memory == UsersResourceMode.PERCENT) {
                resourcePercentText(process.memoryBytes, totalMemoryBytes)
            } else {
                formatMemory(process.memoryBytes)
            },
            fraction = fraction(process.memoryBytes, totalMemoryBytes),
            widthDp = widthDp
        )

        UsersColumn.DISK -> UsersValueCell(
            text = if (resourceModes.disk == UsersResourceMode.PERCENT) {
                resourcePercentText(process.diskBytesPerSecond, maxDiskRate)
            } else {
                formatRatePerSecond(process.diskBytesPerSecond)
            },
            fraction = fraction(process.diskBytesPerSecond, maxDiskRate),
            widthDp = widthDp
        )

        UsersColumn.NETWORK -> UsersValueCell(
            text = when {
                process.networkBytesPerSecond == null -> AppStrings.s(R.string.users_no_value)
                resourceModes.network == UsersResourceMode.PERCENT ->
                    resourcePercentText(process.networkBytesPerSecond, maxNetworkRate)
                else -> formatBitsPerSecond(process.networkBytesPerSecond)
            },
            fraction = process.networkBytesPerSecond
                ?.let { fraction(it, maxNetworkRate) } ?: 0.0,
            widthDp = widthDp
        )

        UsersColumn.SESSION,
        UsersColumn.CLIENT_NAME,
        UsersColumn.GPU,
        UsersColumn.GPU_ENGINE -> Spacer(Modifier.width(widthDp.dp))

        UsersColumn.USER -> Unit
    }
}

@Composable
private fun UsersStatusCell(
    text: String,
    widthDp: Int,
    compact: Boolean
) {

    Box(
        Modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .padding(start = 8.dp, end = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {

        Text(
            text = text,
            style = if (compact) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 进程图标：Android 包进程按「包名 + Android 用户」取图标（走 [ProcessIconCache] 缓存）。
 *
 * 别的 Android 用户里装的包，在当前用户的 PackageManager 里是查不到的；
 * 必须带上 userId 让缓存层按那个用户的 APK 路径解析。
 */
@Composable
private fun UsersProcessIcon(packageName: String?, userId: Int) {

    var icon by remember(packageName, userId) {
        mutableStateOf(packageName?.let { ProcessIconCache.cached(it, userId) })
    }

    LaunchedEffect(packageName, userId) {

        if (packageName != null && !ProcessIconCache.isKnown(packageName, userId)) {
            icon = withContext(Dispatchers.IO) {
                ProcessIconCache.load(packageName, userId)
            }
        }
    }

    if (icon != null) {

        Image(
            bitmap = icon as ImageBitmap,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        return
    }

    Box(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Apps,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 一个数值格：文字压在从左往右的热度底上。
 * 热度底宽度 = 占表头最大值的比例，给 0 时不画（不拿假底色充数）。
 */
@Composable
private fun UsersValueCell(
    text: String,
    fraction: Double,
    widthDp: Int,
    emphasize: Boolean = false
) {

    Box(
        Modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .padding(horizontal = 5.dp, vertical = 7.dp)
    ) {

        if (fraction > 0.0) {

            Box(
                Modifier
                    .fillMaxWidth(fraction.toFloat().coerceIn(0.0f, 1.0f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (emphasize) 0.20f else 0.10f
                        )
                    )
            )
        }

        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasize) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp, end = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 用户长按菜单：展开(P) / 连接(C) / 注销(I) / 发送消息(S) / 分隔 / 切换用户账户(W) / 管理用户账户(M)。
 *
 * 发送消息按用户要求"先显示但禁用"；功能先留空，后面再补。
 * 切账户与管账户之间那条线必须保留。
 */
@Composable
private fun UsersActionDialog(
    onDismiss: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onSwitch: () -> Unit,
    onManage: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                UsersMenuItem(
                    label = AppStrings.s(
                        if (expanded) R.string.users_menu_collapse else R.string.users_menu_expand
                    ),
                    onClick = onToggle
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_menu_connect),
                    onClick = onConnect
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_menu_stop),
                    onClick = onStop
                )

                // 发送消息：先显示但禁用，等后面做消息功能
                UsersMenuItem(
                    label = AppStrings.s(R.string.users_menu_send_message),
                    enabled = false,
                    onClick = {}
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_menu_switch),
                    onClick = onSwitch
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_menu_manage),
                    onClick = onManage
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/**
 * 进程长按菜单：
 * 结束任务(E) / 资源值(V) > / 提供反馈(B) / ─ / 效能模式(M)灰 / 调试(D)灰 /
 * 创建内存转储文件(C) / ─ / 转到详细信息(G) / 打开文件所在的位置(O) / 在线搜索(S) / 属性(I)
 */
@Composable
private fun UsersProcessMenuDialog(
    onDismiss: () -> Unit,
    locationEnabled: Boolean,
    onEndTask: () -> Unit,
    onResourceValues: () -> Unit,
    onFeedback: () -> Unit,
    onGotoDetails: () -> Unit,
    onOpenLocation: () -> Unit,
    onSearch: () -> Unit,
    onProperties: () -> Unit,
    onDump: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                UsersMenuItem(
                    label = AppStrings.s(R.string.details_menu_end_task),
                    onClick = onEndTask
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_proc_resource),
                    arrow = true,
                    onClick = onResourceValues
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.details_menu_feedback),
                    onClick = onFeedback
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 效能模式 / 调试：按用户要求先置灰，不做假动作
                UsersMenuItem(
                    label = AppStrings.s(R.string.details_menu_efficiency),
                    enabled = false,
                    onClick = {}
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.details_menu_debug),
                    enabled = false,
                    onClick = {}
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.details_menu_dump),
                    onClick = onDump
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_proc_details),
                    onClick = onGotoDetails
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.details_menu_open_location),
                    enabled = locationEnabled,
                    onClick = onOpenLocation
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_proc_search),
                    onClick = onSearch
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_proc_properties),
                    onClick = onProperties
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun ColumnScope.UsersMenuItem(
    label: String,
    enabled: Boolean = true,
    arrow: Boolean = false,
    checked: Boolean = false,
    checkable: Boolean = false,
    onClick: () -> Unit
) {

    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
    ) {

        if (checkable) {

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
        }

        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // TextButton 默认拿 primary（蓝），这里跟其他页的菜单一样用正文黑
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )

        if (arrow) {

            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        }
    }
}

/** 失败提示：与详细信息页一致，红底叹号 + 原因 + 一个「确定」。 */
@Composable
private fun UsersFailureDialog(
    message: String,
    onDismiss: () -> Unit
) {

    AlertDialog(
        // 结果通知：只有点「确定」才关，点空白 / 返回键不消失
        onDismissRequest = { },
        title = {
            Text(
                text = AppStrings.s(R.string.details_operation_failed),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(22.dp)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape
                        ),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(AppStrings.s(R.string.common_ok))
            }
        }
    )
}

/** 长按列头：勾选要显示的列，最后一项进入「资源值」。 */
@Composable
private fun UsersColumnsDialog(
    visibleColumns: Set<UsersColumn>,
    onDismiss: () -> Unit,
    onToggle: (UsersColumn) -> Unit,
    onResourceValues: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.s(R.string.users_select_columns_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                UsersColumn.values()
                    .filter { it != UsersColumn.USER }
                    .forEach { column ->

                        UsersMenuItem(
                            label = AppStrings.s(column.labelRes),
                            checked = column in visibleColumns,
                            checkable = true,
                            onClick = { onToggle(column) }
                        )
                    }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_proc_resource),
                    arrow = true,
                    onClick = onResourceValues
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/** 资源值第一层：内存(M) / 磁盘(K) / 网络(N)，每一项再打开百分比 / 值。 */
@Composable
private fun UsersResourceMenuDialog(
    onDismiss: () -> Unit,
    onPick: (UsersResourceKind) -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.s(R.string.users_proc_resource_title)) },
        text = {
            Column {

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_resource_memory),
                    arrow = true,
                    onClick = { onPick(UsersResourceKind.MEMORY) }
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_resource_disk),
                    arrow = true,
                    onClick = { onPick(UsersResourceKind.DISK) }
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_resource_network),
                    arrow = true,
                    onClick = { onPick(UsersResourceKind.NETWORK) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/** 资源值第二层：只保留百分比 / 值两项，点哪一项立即确认并关闭。 */
@Composable
private fun UsersResourceModeDialog(
    kind: UsersResourceKind,
    current: UsersResourceMode,
    onDismiss: () -> Unit,
    onSelect: (UsersResourceMode) -> Unit
) {

    val title = when (kind) {
        UsersResourceKind.MEMORY -> AppStrings.s(R.string.users_resource_memory)
        UsersResourceKind.DISK -> AppStrings.s(R.string.users_resource_disk)
        UsersResourceKind.NETWORK -> AppStrings.s(R.string.users_resource_network)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_resource_percent),
                    checked = current == UsersResourceMode.PERCENT,
                    checkable = true,
                    onClick = { onSelect(UsersResourceMode.PERCENT) }
                )

                UsersMenuItem(
                    label = AppStrings.s(R.string.users_resource_value),
                    checked = current == UsersResourceMode.VALUE,
                    checkable = true,
                    onClick = { onSelect(UsersResourceMode.VALUE) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/** 属性：当前行已有的字段；没有的写「—」，不拿 0 或空串冒充。 */
@Composable
private fun UsersPropertiesDialog(
    process: UserProcessRow,
    onDismiss: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.s(R.string.users_proc_properties_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                UsersPropertyLine(AppStrings.s(R.string.dcol_name), process.name)

                UsersPropertyLine("PID", process.pid.toString())

                UsersPropertyLine(
                    AppStrings.s(R.string.users_col_status),
                    process.state.ifBlank { AppStrings.s(R.string.users_no_value) }
                )

                UsersPropertyLine(
                    AppStrings.s(R.string.dcol_path),
                    process.exePath?.takeIf { it.isNotBlank() }
                        ?: AppStrings.s(R.string.users_no_value)
                )

                UsersPropertyLine(
                    AppStrings.s(R.string.users_col_cpu),
                    String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent)
                )

                UsersPropertyLine(
                    AppStrings.s(R.string.users_col_memory),
                    formatMemory(process.memoryBytes)
                )

                UsersPropertyLine(
                    AppStrings.s(R.string.users_col_disk),
                    formatRatePerSecond(process.diskBytesPerSecond)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.s(R.string.common_ok))
            }
        }
    )
}

@Composable
private fun UsersPropertyLine(label: String, value: String) {

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {

        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** 连接 / 注销 / 切换：拿不到服务也要给失败原因，不能静默。 */
private fun runUserAction(userId: Int, action: AndroidUserAction): ProcessActionResult {

    val service = UIService.instanceOrNull()
        ?: return ProcessActionResult(
            ok = false,
            reason = AppStrings.s(R.string.users_action_no_service)
        )

    return service.androidUserAction(action, userId)
}

/** 管理用户账户：交给系统自己的多用户设置页，不在应用里做一套假的账户管理。 */
private fun openUserSettings(context: Context) {

    val candidates = listOf(
        Intent("android.settings.USER_SETTINGS")
    )

    candidates.forEach { intent ->

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // 试下一个
        } catch (_: SecurityException) {
            // 试下一个
        }
    }
}

private fun openFeedback(context: Context) {

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SETTINGS_FEEDBACK_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // 没有浏览器就静默，不弹假提示
    } catch (_: SecurityException) {
        // 同上
    }
}

private fun openWebSearch(context: Context, query: String) {

    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.bing.com/search?q=" + Uri.encode(query))
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // 无浏览器就放弃
    } catch (_: SecurityException) {
        // 同上
    }
}

/**
 * 打开文件所在的位置：与详细信息页同一套解析口径。
 *
 * - 以进程自己报出的 EXE_PATH 为准，不拿进程显示名去猜包；
 * - 原生进程直接打开该可执行文件；
 * - `app_process*` / `tango_translator` / `zygote_tango` 这类壳进程，
 *   改为打开该进程所属包的 base.apk（包名取后端归好的 [UserProcessRow.packageName]）；
 * - 没有 EXE_PATH，或壳进程定不出所属包时，什么都不打开，不用壳路径顶替。
 */
private fun openProcessLocation(context: Context, process: UserProcessRow) {

    openFilePath(context, resolveUserProcessFile(context, process))
}

/** 只打开进程自己报出来的可执行文件（壳进程二选一时的第一项）。 */
private fun openUserProcessExecutable(context: Context, process: UserProcessRow) {

    openFilePath(context, process.exePath?.trim().orEmpty())
}

/**
 * 壳进程的「打开位置」二选一：打开壳文件本身，还是打开所属应用的 APK。
 * 与详细信息页的 DetailLocationDialog 保持同一交互与置灰规则。
 */
@Composable
private fun UsersLocationDialog(
    packageEnabled: Boolean,
    onDismiss: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenPackage: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.s(R.string.details_location_choice_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppStrings.s(R.string.details_location_choice_file))
                }

                OutlinedButton(
                    onClick = onOpenPackage,
                    enabled = packageEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppStrings.s(R.string.details_location_choice_package))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.s(R.string.common_cancel))
            }
        }
    )
}

/** 这一行用户页进程应该打开哪个文件；规则与详细信息页的 resolveProcessFile 一致。 */
private fun resolveUserProcessFile(context: Context, process: UserProcessRow): String {

    val exe = process.exePath?.trim().orEmpty()

    if (exe.isEmpty()) return ""

    // 原生 Linux 进程：进程说在哪就在哪
    if (!isAppProcessExecutable(exe)) return exe

    // Android / 转译层进程：壳只是入口，实际落点是所属包的 base.apk
    return resolveUserProcessApk(context, process).orEmpty()
}

/** 壳进程所属应用的 base.apk；没有包名 / 包不可见 / 文件不存在时返回 null。 */
private fun resolveUserProcessApk(context: Context, process: UserProcessRow): String? {

    // 与详细页相同：优先用归好的原始包名，缺失时退回 PACKAGE_NAME 显示值并去掉括号后缀
    val pkg = process.packageName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: process.packageDisplayName
            ?.substringBefore('（')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: return null

    return try {

        val info = context.packageManager.getApplicationInfo(pkg, 0)

        info.sourceDir?.trim()
            ?.takeIf { path -> path.isNotEmpty() && java.io.File(path).exists() }

    } catch (_: Exception) {
        // 包被卸载 / 不可见：找不到对应 APK
        null
    }
}

/**
 * Android 运行时与 OEM ARM32 转译层的壳程序。
 *
 * 用可执行路径判断，不用进程显示名：显示名可能是应用标签或包名，
 * 拿它判断会把普通应用进程误判成壳进程。
 */
private fun isAppProcessExecutable(path: String): Boolean {

    val name = path.substringAfterLast('/')

    if (name.startsWith("app_process")) return true

    return name == "tango_translator" || name == "zygote_tango"
}

/** 进程有没有报出可执行路径：没有路径时「打开文件所在的位置」整项置灰。 */
private fun hasUserProcessExecutablePath(process: UserProcessRow): Boolean =
    process.exePath?.isNotBlank() == true

/**
 * 把一个确定的文件路径交给系统打开，交互与详细信息页一致：
 * 路径不存在 / 空路径静默返回；APK 用安装包 MIME，其余用二进制流，
 * 经 FileProvider + chooser 交给文件管理器或安装器。
 */
private fun openFilePath(context: Context, path: String) {

    if (path.isBlank()) return

    val file = java.io.File(path)

    if (!file.exists()) return

    val mime = if (path.endsWith(".apk", ignoreCase = true)) {
        "application/vnd.android.package-archive"
    } else {
        "application/octet-stream"
    }

    try {

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.processfiles",
            file
        )

        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // chooser 会把子 Intent 的授权转给最终被选中的应用；
        // 再挂一份 ClipData 是为了个别 ROM 上 chooser 只认真实数据 URI 的情况。
        view.clipData = android.content.ClipData.newRawUri("", uri)

        context.startActivity(
            Intent.createChooser(view, AppStrings.s(R.string.details_location_open_with))
        )

    } catch (_: Exception) {
        // 没有应用能接、或 provider 拒绝：静默不打开，不提示
    }
}

private fun guessMimeType(path: String): String {

    val extension = path.substringAfterLast('.', "").lowercase()

    return when (extension) {
        "apk" -> "application/vnd.android.package-archive"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "txt", "log" -> "text/plain"
        "so" -> "application/octet-stream"
        else -> "application/octet-stream"
    }
}

private fun readTotalMemoryBytes(context: Context): Long {

    val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    val info = ActivityManager.MemoryInfo()

    activity?.getMemoryInfo(info)

    return info.totalMem
}

/** 用户页列宽单独存，不跟详细信息页的 43 列混在一起。 */
private const val USERS_WIDTH_PREFS = "users_table_widths"

/** 资源值显示模式（百分比 / 值）单独存，下次进用户页继续生效。 */
private const val USERS_RESOURCE_PREFS = "users_resource_modes"

private fun loadUsersResourceModes(context: Context): UsersResourceModes {

    val prefs = context.getSharedPreferences(USERS_RESOURCE_PREFS, Context.MODE_PRIVATE)

    return UsersResourceModes(
        memory = readUsersResourceMode(prefs.getString("memory", null)),
        disk = readUsersResourceMode(prefs.getString("disk", null)),
        network = readUsersResourceMode(prefs.getString("network", null))
    )
}

private fun readUsersResourceMode(value: String?): UsersResourceMode {

    return if (value == "percent") UsersResourceMode.PERCENT else UsersResourceMode.VALUE
}

private fun saveUsersResourceMode(
    context: Context,
    kind: UsersResourceKind,
    mode: UsersResourceMode
) {

    val key = when (kind) {
        UsersResourceKind.MEMORY -> "memory"
        UsersResourceKind.DISK -> "disk"
        UsersResourceKind.NETWORK -> "network"
    }

    context.getSharedPreferences(USERS_RESOURCE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(key, if (mode == UsersResourceMode.PERCENT) "percent" else "value")
        .apply()
}

private fun resourcePercentText(value: Long, base: Long): String {

    if (base <= 0L) return AppStrings.s(R.string.users_no_value)

    val percent = (value.toDouble() / base.toDouble() * 100.0).coerceIn(0.0, 100.0)

    return String.format(Locale.getDefault(), "%.0f%%", percent)
}

private fun loadUsersColumnWidths(context: Context): Map<UsersColumn, Int> {

    val prefs = context.getSharedPreferences(USERS_WIDTH_PREFS, Context.MODE_PRIVATE)

    return UsersColumn.values().associateWith { column ->

        // 旧版只有 col_0..col_5，迁移到按列名存，避免用户已经拖好的宽度丢掉
        val legacyKey = when (column) {
            UsersColumn.USER -> "col_0"
            UsersColumn.STATUS -> "col_1"
            UsersColumn.CPU -> "col_2"
            UsersColumn.MEMORY -> "col_3"
            UsersColumn.DISK -> "col_4"
            UsersColumn.NETWORK -> "col_5"
            else -> null
        }

        val fallback = legacyKey?.let { prefs.getInt(it, column.defaultWidthDp) }
            ?: column.defaultWidthDp

        prefs.getInt("col_${column.name}", fallback)
            .coerceIn(USERS_COLUMN_MIN_WIDTH_DP, USERS_COLUMN_MAX_WIDTH_DP)
    }
}

private fun saveUsersColumnWidth(context: Context, column: UsersColumn, widthDp: Int) {

    context.getSharedPreferences(USERS_WIDTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt("col_${column.name}", widthDp)
        .apply()
}

private const val USERS_VISIBLE_COLUMNS_PREFS = "users_visible_columns"

private fun loadVisibleUsersColumns(context: Context): Set<UsersColumn> {

    val prefs = context.getSharedPreferences(USERS_VISIBLE_COLUMNS_PREFS, Context.MODE_PRIVATE)

    val saved = prefs.getStringSet("columns", null)
        ?: return UsersColumn.values()
            .filter { it.defaultVisible }
            .toSet()

    val parsed = saved.mapNotNull { name ->
        runCatching { UsersColumn.valueOf(name) }.getOrNull()
    }.toSet()

    return parsed + UsersColumn.USER
}

private fun saveVisibleUsersColumns(context: Context, columns: Set<UsersColumn>) {

    context.getSharedPreferences(USERS_VISIBLE_COLUMNS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet("columns", columns.map { it.name }.toSet())
        .apply()
}

private fun fraction(value: Long, max: Long): Double {

    if (max <= 0L) return 0.0

    return (value.toDouble() / max.toDouble()).coerceIn(0.0, 1.0)
}

private fun memoryPercent(bytes: Long, total: Long): Double {

    if (total <= 0L) return 0.0

    return (bytes.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)
}

private fun formatMemory(bytes: Long): String {

    return when {
        bytes >= 1_073_741_824L -> AppStrings.s(
            R.string.users_memory_gb,
            String.format(Locale.getDefault(), "%.1f", bytes / 1_073_741_824.0)
        )

        else -> AppStrings.s(
            R.string.users_memory_mb,
            String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)
        )
    }
}

private fun formatRatePerSecond(bytesPerSecond: Long): String {

    return AppStrings.s(
        R.string.users_disk_rate,
        String.format(Locale.getDefault(), "%.1f", bytesPerSecond / 1_048_576.0)
    )
}

private fun formatBitsPerSecond(bytesPerSecond: Long): String {

    return AppStrings.s(
        R.string.users_network_rate,
        String.format(Locale.getDefault(), "%.2f", bytesPerSecond * 8.0 / 1_000_000.0)
    )
}

private fun formatBytes(bytes: Long): String {

    return when {
        bytes >= 1_073_741_824L -> String.format(
            Locale.getDefault(),
            "%.2f GB",
            bytes / 1_073_741_824.0
        )

        bytes >= 1_048_576L -> String.format(
            Locale.getDefault(),
            "%.1f MB",
            bytes / 1_048_576.0
        )

        else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    }
}
