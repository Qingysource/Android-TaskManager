/**
 * ============================================================
 * 详细信息页 —— 进程属性二维表格
 * ============================================================
 *
 * 这一页只做"画表格 + 上报操作"，不生产数据、不计时、不排序：
 *
 *   列（表头）     → UIService 定义，显不显示也由它决定（多少列 = 表有多宽）
 *   行（进程）     → UIService 的 detailTable().rows（填多少行 = 表有多高）
 *   排序           → 页面只上报"点了哪一列"，UIService 在下一次刷新时按由高到低重排
 *   定时刷新       → UIService 的 pollTick（间隔 = 设置页"实时更新速度"），
 *                    本页按快照里的 refreshIntervalMs 轮询读，不自己计时
 *
 * 交互（按需求逐条对应）：
 * - 点任意一格：选中它所在的那一整行（也就是那个进程），并上报 selectDetailRow(pid)；
 *   为了"点下去立刻有反应"，本页带一份 pendingSelectedIdentity 乐观高亮，下一轮快照到了以服务值为准；
 * - 长按任意一格：弹出测试对话框，并把 PID 通过 onDetailRowLongPressed(pid) 报给 UIService；
 * - 点表头任意一格：上报 sortDetailTableBy(该列)，下一次刷新时整表按该统计值由高到低重排；
 * - 长按表头任意位置：打开「选择列」对话框（43 个复选框 + 确定）；
 * - 表可能很宽很高：本页关闭了 PageShell 的整页纵向滚动，自己管"横向 + 纵向"两个滚动。
 *
 * 性能上的两条硬约束（别改回去）：
 * 1. 手势挂在"整行/整表头"上，不是每一格挂一个：43 列 × N 格各自 pointerInput 会让每次按下
 *    都要走几百上千个手势检测器，表现就是"点一格很卡"；表头同样只在整行挂一个，
 *    点击位置按固定列宽换算成"第几列"。
 * 2. 每行用 key(row.pid) 包住，刷新时 Compose 只重画变化的行，不会整表重建。
 *
 * 行定位（与"PID"列显不显示无关）：
 * - 每行的身份是 ProcessRow.pid（独立的行标识字段），不是表格里的文字；
 * - 选中 / 长按 / 排序去重 / 状态条显示，全部用它，所以隐藏「PID」列照样能定位进程。
 *
 * 预留实现接口（将来接真数据时要动的地方）：
 * - 数据源接入后不需要改本页：UIService 的 refreshDetailRows() 调 replaceProcessRows() 即可，
 *   本页照样按 refreshIntervalMs 拿快照；
 * - 需要"列显示开关 UI"（勾选框 / 列设置面板）时，调 IPageDataApi.setDetailVisibleColumns(整份集合)，
 *   不需要在本页维护任何列清单（列定义在 UIService，运行时不扩表）；
 * - 需要"右键菜单 / 结束进程 / 打开属性"时，从 onDetailRowLongPressed(pid) 之后由
 *   UIService 统一发起，本页不要自己攒第二份选中状态。
 *
 * 说明：文件位于 ui 目录，但包名为 com.android.taskmanager，与 MainActivity 同包。
 */
package com.android.taskmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.taskmanager.data.ProcessScope
import com.android.taskmanager.service.UIService

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面实现状态：由页面自己上报，MainActivity 的路由据此决定"渲染页面"还是"显示不支持"。 */
val DetailsPageState: PageState = PageState.IMPLEMENTED

/**
 * 轮询快照的上限/下限。
 *
 * "实时更新速度 = 暂停"时 refreshIntervalMs 是 0：这时不刷新数据，但仍要能感知
 * 用户点了表头 / 点了行（那些状态在 UIService 里），所以退化成 800ms 的"轻读"。
 */
private const val DETAIL_IDLE_POLL_MS = 800L

/** 搜索框在本页登记用的页名（与 MainActivity 的标题文案一致）。 */
private const val SEARCH_PAGE_DETAIL = "详细信息"

/** 该进程是否匹配搜索关键字：名称 / 包名（发布者）/ PID 三个口径，忽略大小写。 */
private fun processMatchesQuery(row: ProcessRow, query: String): Boolean {

    if (query.isEmpty()) return true

    val needle = query.lowercase()

    // 名称：优先用表格里「名称」列的值（用户看到的就是它），该列被隐藏时回落到行自带的名字
    val name = (row.values[DetailColumnId.NAME] ?: row.name).lowercase()

    val packageName = row.values[DetailColumnId.PACKAGE_NAME].orEmpty().lowercase()

    // PID：用"显示值"，与表格里看到的一致（真实数据里就是进程 PID）
    val pidText = row.displayPid.toString()

    return name.contains(needle) || packageName.contains(needle) || pidText.contains(needle)
}

/** 一次长按命中的信息（只有行/进程），只用于弹窗展示。 */
private data class LongPressHit(
    val row: ProcessRow
)

/**
 * 给人看的 PID：优先取「PID」列的值，没有就回落到行标识。
 *
 * 为什么要区分：`ProcessRow.pid` 是**行标识**（选中/长按/去重的唯一键），
 * 真实数据里它就等于进程 PID，但调试页的 TestN 行为了不跟真实 pid 撞车、
 * 也为了满足"pid 必须为正"的约束，用了 9000+N 这一段内部编号。
 * 那段编号只在服务内部流转，界面上一律显示「PID」列的值。
 */
private val ProcessRow.displayPid: String
    get() = values[DetailColumnId.PID]?.takeIf { it.isNotBlank() } ?: pid.toString()

/** 详细信息页面入口，由 MainActivity 的 PageContent() 调用（状态为 IMPLEMENTED 时才会被调用）。 */
@Composable
fun DetailsScreen(
    /**
     * 是否由"悬浮窗"承载（OVERLAY v1）。
     *
     * 浮窗没有 Activity 的窗口令牌，`AlertDialog` 会抛 BadTokenException（整个进程崩）。
     * 所以浮窗里一律用"在当前 Compose 窗口内自绘的卡片"代替系统对话框 ——
     * 与设置页同一个做法（见 SettingsScreen 的 hostedInOverlay）。
     */
    hostedInOverlay: Boolean = false
) {

    // 快照：每轮刷新整体替换；内容相同（data class equals）时 Compose 不会触发无谓重组
    var table by remember { mutableStateOf(DetailTable()) }

    var longPressHit by remember { mutableStateOf<LongPressHit?>(null) }

    /**
     * 本地点选（乐观更新）。
     *
     * 为什么需要它：点一下要等下一轮轮询（500ms ~ 3s）才能看到高亮，手感就是"点了没反应"。
     * 这里先本地高亮，同时上报 UIService；下一轮快照到了就以服务值为准并清掉这份草稿。
     */
    var pendingSelectedIdentity by remember { mutableStateOf<String?>(null) }

    // "选择列"对话框是否打开（表头任意位置长按打开）
    var showColumnPicker by remember { mutableStateOf(false) }

    // 正在等用户确认的那一行（红色按钮点了之后先弹确认框，此时还没发命令）
    var killRequest by remember { mutableStateOf<ProcessRow?>(null) }

    // "无法完成该操作"的副标题：非空即显示失败对话框（成功不提示，只刷一次表）
    var killFailure by remember { mutableStateOf<String?>(null) }

    // 结束进程要发 IPC 并等回执（几十毫秒），不能占主线程
    val killScope = rememberCoroutineScope()

    // 轮询：UIService 就绪后每 refreshIntervalMs 取一次快照。
    // 本页不判断"该不该刷新数据"，只按 UIService 给的间隔执行（暂停=0 时走空闲间隔）；
    // 门禁交给 isUiRefreshAllowed()：不在前台、浮窗也没显示时，读到的数据没人看得见，
    // 这一轮就直接跳过——不读、不改状态，Compose 也就不会重组。
    LaunchedEffect(Unit) {

        // 进页面先按需采一次：默认"忽略实时更新速度"= 不自动刷新，
        // 不采这一次表格会一直空着，看起来像坏了。
        var firstRefreshDone = false

        while (true) {

            val api = UIService.instanceOrNull()

            if (api != null && api.isUiRefreshAllowed()) {

                if (!firstRefreshDone) {
                    firstRefreshDone = true

                    api.refreshDetailNow()
                }

                table = api.detailTable()

                // 服务侧已经记下了选中状态，本地草稿可以退场（之后以快照为准）
                pendingSelectedIdentity = null
            }

            val interval = table.refreshIntervalMs

            delay(if (interval > 0L) interval else DETAIL_IDLE_POLL_MS)
        }
    }

    PageShell(
        // 顶部直接就是表格：当前页由应用标题栏 / 横屏页名行统一指示，页面里不再重复画标题
        // 表格自己要"横向 + 纵向"二维滚动，这里不能再套一层整页纵向滚动
        scrollable = false
    ) {

        // ---------- 页标题搜索框的关键字（在 UIService 里，页面只读） ----------
        // 读的是一个 Compose 可观察的值：输入框每敲一个字这里就重组一次，表格立刻重筛。
        val searchQuery = UIService.instanceOrNull()
            ?.pageSearchQuery(SEARCH_PAGE_DETAIL)
            .orEmpty()
            .trim()

        // 筛的是"已经排好序的行"，不重新排序、也不重新请求快照：
        // 关键字只是在显示层过滤，快照本身（以及 UIService 侧的选中状态）保持原样。
        val visibleRows = remember(table.rows, searchQuery) {

            if (searchQuery.isEmpty()) {
                table.rows
            } else {
                table.rows.filter { processMatchesQuery(it, searchQuery) }
            }
        }

        // 选中进程的身份：本地草稿优先（点下去立刻高亮），下一轮快照到了用服务值。
        // 身份是 "pid/startTicks" 而不是纯 pid —— PID 会被系统复用，只比 pid 会高亮到别人身上。
        val selectedIdentity = pendingSelectedIdentity
            ?: table.selectedPid?.let { pid ->
                val startTicks = table.selectedStartTicks

                if (startTicks != null && startTicks >= 0L) "$pid/$startTicks" else "$pid"
            }

        // ---------- 顶部状态条：数据来源说明 + 规模 + 当前排序 + 当前选中 ----------
        DetailStatusBar(
            table = table,
            selectedIdentity = selectedIdentity,
            searchQuery = searchQuery,
            visibleRowCount = visibleRows.size
        )

        // 可被「结束进程」操作的行：pid 与启动时刻**双双**对得上才认，而且必须还在当前筛选结果里。
        // 缺启动时刻（-1）就不是"一个确定的进程"，按钮直接置灰，绝不拿 pid 单独去杀 ——
        // 目标退出后 PID 会被复用，只凭 pid 会结束掉另一个进程。
        // 注意 0 是合法值（init 的 starttime 就是 0），不能当作"没有"。
        val killCandidate = selectedIdentity?.let { identity ->

            val parts = identity.split('/')

            val pid = parts.getOrNull(0)?.toIntOrNull() ?: 0

            val ticks = parts.getOrNull(1)?.toLongOrNull() ?: -1L

            if (pid <= 0 || ticks < 0L) {
                null
            } else {
                // 被筛掉的行不认：看不见的行不该还能点"结束进程"
                visibleRows.firstOrNull { it.pid == pid && it.startTicks == ticks }
            }
        }

        Spacer(
            modifier = Modifier.height(6.dp)
        )

        // ---------- 表格本体：占满剩余高度 ----------
        DetailTableBody(
            table = table,

            rows = visibleRows,

            emptyHint = if (searchQuery.isEmpty()) {
                "表里还没有进程（等待上游填入，或已被清空）"
            } else {
                "没有匹配「$searchQuery」的进程（试试名称、包名或 PID）"
            },

            selectedIdentity = selectedIdentity,

            modifier = Modifier
                .fillMaxWidth()
                // 占满标题区以下的全部高度；行多就纵向滚，列宽就横向滚
                .weight(1f),

            onSortColumn = { columnId ->
                UIService.instanceOrNull()?.sortDetailTableBy(columnId)
            },

            // 表头任意位置长按：打开"选择列"对话框
            onOpenColumnPicker = { showColumnPicker = true },

            onSelectRow = { row ->

                // 先本地高亮（点下去立刻有反应），再报给 UIService
                pendingSelectedIdentity = row.identity()

                UIService.instanceOrNull()?.selectDetailRow(row.pid, row.startTicks)
            },

            onLongPressRow = { row ->

                // 长按也顺带选中这一行，然后把 pid + startTicks 一起报给 UIService（页面不自己攒状态）
                pendingSelectedIdentity = row.identity()

                UIService.instanceOrNull()?.selectDetailRow(row.pid, row.startTicks)

                UIService.instanceOrNull()?.onDetailRowLongPressed(row.pid, row.startTicks)

                longPressHit = LongPressHit(row = row)
            }
        )

        // ---------- 底部工具条：左下范围三档 / 中间忽略开关 / 右下「刷新」与「结束进程」 ----------
        DetailFooterBar(
            scope = table.processScope,

            ignoreUpdateSpeed = table.ignoreUpdateSpeed,

            // 能被"结束进程"操作的行，必须由 pid + 启动时刻双重确认：
            // 只凭 pid 时，目标已退出、PID 被新进程占用，就会结束掉另一个进程。
            killTarget = killCandidate,

            onScopeChange = { scope ->
                UIService.instanceOrNull()?.setDetailProcessScope(scope)
            },

            onToggleIgnoreSpeed = { ignore ->
                UIService.instanceOrNull()?.setDetailIgnoreUpdateSpeed(ignore)
            },

            onRefresh = {
                // 立即采一次（不看"忽略实时更新速度"），随后读表把状态同步过来；
                // 新包落下后下一轮轮询会读到它，不需要页面自己等待。
                UIService.instanceOrNull()?.refreshDetailNow()

                UIService.instanceOrNull()?.detailTable()?.let { table = it }
            },

            onKill = { row ->
                // 点一下只是弹确认框；真正发命令在用户点"结束进程"之后
                killRequest = row
            }
        )
    }

    // ---------- 选择列对话框：勾完点"确定"整份报给 UIService，并立刻刷新表 ----------
    if (showColumnPicker) {

        DetailColumnPickerDialog(
            allColumns = table.allColumns,

            visibleIds = table.columns.map { it.id }.toSet(),

            onDismiss = { showColumnPicker = false },

            onConfirm = { ids ->

                UIService.instanceOrNull()?.setDetailVisibleColumns(ids)

                // 立刻取一次新快照：不用等下一轮轮询，勾完就能看到表变窄/变宽
                UIService.instanceOrNull()?.detailTable()?.let { table = it }

                showColumnPicker = false
            }
        )
    }

    // ---------- 结束进程：确认框（外壳与设置页"需要重启"那个完全一致） ----------
    killRequest?.let { target ->

        DetailActionDialog(
            hostedInOverlay = hostedInOverlay,

            // 点空白/返回键等同"取消"：什么都没发生，不弹失败框
            onDismissRequest = { killRequest = null },

            title = "你希望结束 ${target.name} 吗?",

            body = {
                Text(
                    text = "如果某个打开的程序与此进程关联，则会关闭此程序并且将丢失所有未保存的数据。" +
                        "如果结束某个系统进程，则可能导致系统不稳定。你确定要继续吗？",

                    style = MaterialTheme.typography.bodyMedium
                )
            },

            confirmText = "结束进程",

            onConfirm = {

                killRequest = null

                killScope.launch {

                    val result = withContext(Dispatchers.IO) {
                        UIService.instanceOrNull()?.killDetailProcess(
                            target.pid,
                            target.startTicks
                        ) ?: ProcessActionResult(false, "服务未就绪。")
                    }

                    // 成功什么都不提示：UIService 会在 1 秒后自己重采一次，
                    // 页面轮询跟着更新即可（立刻读表反而会把刚结束的行又读回来）。
                    if (!result.ok) {
                        killFailure = result.reason
                    }
                }
            },

            dismissText = "取消",

            onDismiss = { killRequest = null }
        )
    }

    // ---------- 结束进程：失败提示（同样是设置页那套外壳，内容换成错误说明） ----------
    killFailure?.let { reason ->

        DetailActionDialog(
            hostedInOverlay = hostedInOverlay,

            // 这是结果通知，不看一次不该消失
            onDismissRequest = { },

            title = "无法完成该操作。",

            body = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 错误图标：红底圆形叹号（不依赖图标库，任何主题下都清楚）
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(22.dp)
                            .background(
                                color = MaterialTheme.colorScheme.error,
                                shape = androidx.compose.foundation.shape.CircleShape
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

                    // 比标题小一号：标题是结论，这句是原因
                    Text(
                        text = reason,

                        style = MaterialTheme.typography.bodySmall,

                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },

            confirmText = "确定",

            onConfirm = { killFailure = null },

            // 结果通知只留一个按钮
            dismissText = null,

            onDismiss = { }
        )
    }

    // ---------- 长按测试对话框（内容随便，关键是"上报 PID"这一步已经做了） ----------
    longPressHit?.let { hit ->

        AlertDialog(
            onDismissRequest = { longPressHit = null },

            title = {
                Text(
                    text = "长按测试"
                )
            },

            text = {
                Column {

                    Text(
                        text = "进程：${hit.row.name}（PID=${hit.row.displayPid}）"
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    Text(
                        text = "该 PID 已通过 onDetailRowLongPressed(pid) 上报给 UIService。",

                        style = MaterialTheme.typography.bodySmall,

                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },

            confirmButton = {

                TextButton(
                    onClick = { longPressHit = null }
                ) {
                    Text(
                        text = "确定"
                    )
                }
            }
        )
    }
}

/**
 * 表格上方的一句状态：来源 / 行数 / 列数 / 排序 / 选中。
 *
 * 这些全部来自 UIService 的快照，页面不自行推导。
 */
@Composable
private fun DetailStatusBar(
    table: DetailTable,
    selectedIdentity: String?,
    searchQuery: String,
    visibleRowCount: Int
) {

    val sortedTitle = table.sortColumnId?.let { id ->
        table.columns.firstOrNull { it.id == id }?.title ?: id
    }

    val summary = buildString {

        append("列 ${table.columns.size} / 共 43 项")

        // 筛选中时同时给出"筛出多少 / 一共多少"，否则用户会以为进程真的只剩这几个
        if (searchQuery.isEmpty()) {
            append(" · 行 ${table.rows.size}")
        } else {
            append(" · 行 $visibleRowCount / ${table.rows.size}（筛选：「$searchQuery」）")
        }

        append(" · 排序：")

        if (sortedTitle == null) {
            append("默认顺序")
        } else {
            append(sortedTitle)
            append(if (table.sortDescending) " ↓" else " ↑")
        }

        append(" · 选中：")

        // 选中进程按行身份（pid + startTicks）找，不依赖"PID"这一列是否显示
        val selected = table.rows.firstOrNull { it.identity() == selectedIdentity }

        if (selected == null) {
            append("无")
        } else {
            // 这里显示给人看的是「PID」列的**显示值**，不是行标识本身。
            // 两者刻意分开：测试行的行标识用 9000+N（防与真实进程 pid 撞车、防 pid=0），
            // 那个 9 开头的数只是内部身份，不该出现在用户眼前。
            append("${selected.name}(PID=${selected.displayPid})")
        }
    }

    Column {

        Text(
            text = summary,

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.height(2.dp)
        )

        // 操作提示：这一句是"选择列对话框"的唯一入口说明，长按表头任意位置即可打开
        Text(
            text = "点表头排序（同列再点翻转）· 长按表头任意位置可选择列",

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.primary
        )

        if (table.note.isNotEmpty()) {

            Spacer(
                modifier = Modifier.height(2.dp)
            )

            Text(
                text = table.note,

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 详细信息页的对话框外壳（与设置页的 SettingsDialog 是同一套样子，只换了尺寸细节）。
 *
 * 同一份内容、两种渲染：
 * - 普通界面（hostedInOverlay = false）：AlertDialog，标题 titleLarge；
 * - 浮窗（hostedInOverlay = true）：在**当前 Compose 窗口内**画遮罩 + 卡片，标题 titleMedium。
 *
 * 为什么浮窗必须换一套：AlertDialog 底层是 android.app.Dialog，会另开一个
 * TYPE_APPLICATION 窗口并要求 Activity 的窗口令牌；浮窗是 TYPE_APPLICATION_OVERLAY，
 * 没有 Activity 令牌，直接弹会抛 BadTokenException，整个进程崩掉。
 *
 * 按钮排布：强调色的确认按钮在前，取消在后（与设置页一致）。
 *
 * @param dismissText null = 只有一个确认按钮（结果通知用）
 * @param body 正文；与普通界面共用同一份布局代码
 */
@Composable
private fun DetailActionDialog(
    hostedInOverlay: Boolean,

    onDismissRequest: () -> Unit,

    title: String,

    body: @Composable ColumnScope.() -> Unit,

    confirmText: String,

    onConfirm: () -> Unit,

    dismissText: String?,

    onDismiss: () -> Unit
) {

    if (!hostedInOverlay) {

        AlertDialog(
            onDismissRequest = onDismissRequest,

            title = {
                Text(
                    text = title,

                    style = MaterialTheme.typography.titleLarge
                )
            },

            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    body()
                }
            },

            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onConfirm) {
                        Text(confirmText)
                    }

                    if (dismissText != null) {
                        TextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                    }
                }
            }
        )

        return
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 遮罩层：点击空白处等同"取消"
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable { onDismissRequest() }
        )

        // 卡片层：自己吃掉点击，避免点卡片内容时被遮罩当成取消
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(20.dp)
                .clickable(
                    interactionSource = null,
                    indication = null
                ) {
                }
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,

                    style = MaterialTheme.typography.titleMedium
                )

                body()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    if (dismissText != null) {
                        TextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                    }

                    Button(onClick = onConfirm) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

/**
 * 底部工具条：左下 = 进程范围三档，中间 = "忽略实时更新速度"，右下 = 「刷新」。
 *
 * 宽度自适应：手机竖屏 / 横屏 / 悬浮窗都是同一棵树，按可用宽度决定排一行还是两行
 * （浮窗只有三百来 dp，硬挤一行会折字；折成两行后范围与刷新仍在一行，开关落到第二行）。
 *
 * 三个控件都只上报、不自作判断：选了哪一档、开关开没开，真值都在 UIService，
 * 下一次 detailTable() 会把状态带回来，所以这里不需要自己维护第二份状态。
 */
@Composable
private fun DetailFooterBar(
    scope: ProcessScope,

    ignoreUpdateSpeed: Boolean,

    /** 能被结束的那一行；null = 没有选中，或选中行的 pid + 启动时刻凑不齐 */
    killTarget: ProcessRow?,

    onScopeChange: (ProcessScope) -> Unit,

    onToggleIgnoreSpeed: (Boolean) -> Unit,

    onRefresh: () -> Unit,

    onKill: (ProcessRow) -> Unit
) {

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {

        // 阈值按"三块内容 + 两个按钮"的真实宽度定：宽过它才排一行
        val singleRow = maxWidth >= 520.dp

        if (singleRow) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                ScopeSelector(
                    scope = scope,
                    onScopeChange = onScopeChange,
                    modifier = Modifier.weight(1f)
                )

                IgnoreSpeedToggle(
                    checked = ignoreUpdateSpeed,
                    onCheckedChange = onToggleIgnoreSpeed
                )

                RefreshAction(onRefresh = onRefresh)

                KillAction(killTarget = killTarget, onKill = onKill)
            }
        } else {

            Column {

                ScopeSelector(
                    scope = scope,
                    onScopeChange = onScopeChange,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    IgnoreSpeedToggle(
                        checked = ignoreUpdateSpeed,
                        onCheckedChange = onToggleIgnoreSpeed
                    )

                    // 窄屏（浮窗）时把两个按钮顶到右边，开关留在左边
                    Spacer(modifier = Modifier.weight(1f))

                    RefreshAction(onRefresh = onRefresh)

                    KillAction(killTarget = killTarget, onKill = onKill)
                }
            }
        }
    }
}

/** 三档范围并排：选中的一档加粗 + 主色，未选中的用次级色。 */
@Composable
private fun ScopeSelector(
    scope: ProcessScope,

    onScopeChange: (ProcessScope) -> Unit,

    modifier: Modifier = Modifier
) {

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {

        listOf(ProcessScope.ALL, ProcessScope.USER, ProcessScope.SYSTEM).forEach { item ->

            val selected = item == scope

            Text(
                text = item.label,

                style = MaterialTheme.typography.labelMedium,

                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,

                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },

                // 点已选中的那一档不发请求：换了才有意义，避免每点一次都重采一遍
                modifier = Modifier
                    .clickable { if (!selected) onScopeChange(item) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * "忽略实时更新速度"开关。
 *
 * 勾上（默认）= 详细信息页不跟着设置页的速度自动刷，只有「刷新」按钮会采；
 * 取消勾选 = 回到定时自动刷新。
 */
@Composable
private fun IgnoreSpeedToggle(
    checked: Boolean,

    onCheckedChange: (Boolean) -> Unit
) {

    Row(
        verticalAlignment = Alignment.CenterVertically,

        // 整块可点：只是 40dp 的复选框在浮窗里很难按准
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    ) {

        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )

        Text(
            text = "忽略实时更新速度",

            style = MaterialTheme.typography.labelMedium,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 右下角「刷新」：立即更新一次，不看忽略开关。 */
@Composable
private fun RefreshAction(onRefresh: () -> Unit) {

    TextButton(onClick = onRefresh) {

        Text(
            text = "刷新",

            style = MaterialTheme.typography.labelMedium,

            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 右下角红色「结束进程」。
 *
 * 没有可确认的目标（没选中，或选中行没有启动时刻）时置灰：点不了比"点了却杀错人"安全。
 */
@Composable
private fun KillAction(
    killTarget: ProcessRow?,

    onKill: (ProcessRow) -> Unit
) {

    val target = killTarget

    Button(
        onClick = { if (target != null) onKill(target) },

        enabled = target != null,

        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),

        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.28f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(
            text = "结束进程",

            style = MaterialTheme.typography.labelMedium,

            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 表格本体：横向滚动包一层、纵向滚动包一层。
 *
 * 为什么要两层：横向滚动给子项"无限宽"约束，纵向滚动给"无限高"约束；
 * 两个方向放在同一个 Box 上会互相抢约束，套两层各自约束一个方向最稳。
 */
@Composable
private fun DetailTableBody(
    table: DetailTable,

    /** 要画的行：已排序、且已按搜索关键字筛过（空关键字时与 table.rows 完全一致）。 */
    rows: List<ProcessRow>,

    /** 一行都没有时显示的话（区分"还没数据"与"没搜到"）。 */
    emptyHint: String,

    selectedIdentity: String?,

    modifier: Modifier = Modifier,

    onSortColumn: (String) -> Unit,
    onOpenColumnPicker: () -> Unit,
    onSelectRow: (ProcessRow) -> Unit,
    onLongPressRow: (ProcessRow) -> Unit
) {

    val horizontalState = rememberScrollState()

    val verticalState = rememberScrollState()

    // 没列可画（用户把所有列都取消勾选，或服务还没起来）时给一句明确的话，别画成空白，
    // 并且这里仍然允许长按表头打开"选择列"——否则用户一旦全取消勾选就再也开不回列
    if (table.columns.isEmpty()) {

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = "暂无可见列（服务未就绪，或所有列都被取消勾选）",

                    style = MaterialTheme.typography.bodyMedium,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                Text(
                    text = "长按表头区域打开「选择列」重新勾选",

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.primary,

                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onLongPress = { onOpenColumnPicker() })
                    }
                )
            }
        }

        return
    }

    Box(
        modifier = modifier.horizontalScroll(horizontalState)
    ) {

        Box(
            modifier = Modifier.verticalScroll(verticalState)
        ) {

            Column {

                // 表头（表头也一起横向滚动，跟列始终对齐）
                DetailHeaderRow(
                    table = table,
                    onSortColumn = onSortColumn,
                    onLongPressHeader = onOpenColumnPicker
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 行：有多少进程就画多少行（表高 = 行数）
                // 用 key(行身份) 让 Compose 认住"这一行是哪个进程"：刷新时只重画变化的行，
                // 而不是整表重建（43 列 × N 行的重建代价很直观）。
                // key 用 pid + startTicks：同一 pid 换了进程时，行缓存也跟着换掉。
                rows.forEach { row ->

                    key(row.identity()) {

                        DetailDataRow(
                            table = table,
                            row = row,
                            selectedIdentity = selectedIdentity,
                            onSelectRow = onSelectRow,
                            onLongPressRow = onLongPressRow
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }

                // 空表也要有个能点/能滑的区域，否则用户以为页面卡住了
                if (rows.isEmpty()) {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emptyHint,

                            style = MaterialTheme.typography.bodySmall,

                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 表头一行。
 *
 * 点任意一格 = 请求按该列排序；长按任意位置 = 打开"选择列"对话框。
 *
 * 手势只挂一个（在整行上），点击位置自己换算成"第几列"：
 * 43 个格子各挂一个 pointerInput 的话，每次按下都要让 43 个检测器参与命中测试，
 * 表一大就明显拖慢手感。列宽是固定 dp，按累计宽度换算最省。
 */
@Composable
private fun DetailHeaderRow(
    table: DetailTable,
    onSortColumn: (String) -> Unit,
    onLongPressHeader: () -> Unit
) {

    val density = LocalDensity.current

    // 每一列在表头里的像素区间 [start, end)
    val columnSpansPx = remember(table.columns, density) {

        var accumulated = 0f

        table.columns.map { column ->

            val widthPx = with(density) { column.width.dp.toPx() }

            val start = accumulated

            accumulated += widthPx

            start to accumulated
        }
    }

    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)

            .pointerInput(table.columns) {

                detectTapGestures(

                    onTap = { offset ->

                        val x = offset.x

                        val index = columnSpansPx.indexOfFirst { span ->
                            x >= span.first && x < span.second
                        }

                        if (index >= 0) {
                            onSortColumn(table.columns[index].id)
                        }
                    },

                    onLongPress = { onLongPressHeader() }
                )
            }
    ) {

        table.columns.forEach { column ->

            val isSorted = table.sortColumnId == column.id

            Box(
                modifier = Modifier
                    .width(column.width.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp),

                contentAlignment = Alignment.CenterStart
            ) {

                Text(
                    text = buildString {

                        append(column.title)

                        if (isSorted) {
                            append(if (table.sortDescending) " ↓" else " ↑")
                        }
                    },

                    style = MaterialTheme.typography.labelLarge,

                    fontWeight = if (isSorted) FontWeight.Bold else FontWeight.Medium,

                    color = if (isSorted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 数据一行：一个进程。点任意格 = 选中整行；长按任意格 = 报 pid + startTicks + 弹测试对话框。 */
@Composable
private fun DetailDataRow(
    table: DetailTable,

    row: ProcessRow,

    selectedIdentity: String?,

    onSelectRow: (ProcessRow) -> Unit,
    onLongPressRow: (ProcessRow) -> Unit
) {

    val isSelected = row.identity() == selectedIdentity

    Row(
        modifier = Modifier
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                }
            )

            // 手势挂在**整行**上，而不是每一格挂一个：
            // 43 列 × N 行各挂一个 pointerInput，一张表就有成百上千个手势协程，
            // 每次按下都要让所有候选检测器走一遍，手感就是"点一下要卡半天"。
            // 需求本来就只要"点这一行 = 选中这个进程 / 长按 = 报 PID"，整行一个检测器足够。
            .pointerInput(row.identity()) {

                detectTapGestures(
                    onTap = { onSelectRow(row) },

                    onLongPress = { onLongPressRow(row) }
                )
            }
    ) {

        table.columns.forEach { column ->

            val text = row.values[column.id].orEmpty()

            Box(
                modifier = Modifier
                    .width(column.width.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),

                contentAlignment = Alignment.CenterStart
            ) {

                Text(
                    text = text,

                    style = MaterialTheme.typography.bodySmall,

                    // 数值列统一左对齐：列宽固定 + 等宽感由定宽格子保证，不必再算右对齐
                    color = MaterialTheme.colorScheme.onSurface,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 「选择列」对话框（长按表头任意位置打开）。
 *
 * 版式按设计：
 *   大标题「选择列」
 *   小标题「选择将显示在表上的列。」
 *   43 个复选框（列名，顺序与表头一致，列表本身可滚动）
 *   右下角「确定」
 *
 * 交互约定：
 * - 勾选状态先改本地草稿（draft），点"确定"才生效——避免边勾边抖动表头；
 * - 点"确定"：把整份勾选集合报给 UIService（setDetailVisibleColumns），页面立刻刷新一次快照；
 * - 点外部 / 返回：丢弃草稿，不改表。
 *
 * 为什么复选框用 [DetailTable.allColumns]：列集合在设计期固定 43 项，
 * 不随可见性变化，页面不需要另外维护一份列清单。
 */
@Composable
private fun DetailColumnPickerDialog(
    allColumns: List<DetailColumn>,
    visibleIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {

    // 草稿：对话框每次打开都会重新进入组合，remember 从这里重新取初始值
    var draft by remember { mutableStateOf(visibleIds) }

    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text(
                text = "选择列"
            )
        },

        text = {

            Column {

                // 小标题
                Text(
                    text = "选择将显示在表上的列。",

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                if (allColumns.isEmpty()) {

                    Text(
                        text = "列定义尚未就绪（UIService 没有返回 allColumns）",

                        style = MaterialTheme.typography.bodySmall,

                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                } else {

                    // 43 项放不下：限高 + 内部滚动
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp)
                    ) {

                        items(
                            // 「名称」是必备列：不参与勾选，直接从清单里去掉
                            items = allColumns.filter { it.id != DetailColumnId.NAME },

                            key = { it.id }
                        ) { column ->

                            val checked = column.id in draft

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 整行都能点，别要求用户精准点中那个小方块
                                    .clickable {
                                        draft = if (checked) {
                                            draft - column.id
                                        } else {
                                            draft + column.id
                                        }
                                    }
                                    .padding(vertical = 2.dp),

                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                Checkbox(
                                    checked = checked,

                                    // 点击语义交给整行处理，避免"点方块"和"点行"各触发一次
                                    onCheckedChange = { isChecked ->
                                        draft = if (isChecked) {
                                            draft + column.id
                                        } else {
                                            draft - column.id
                                        }
                                    }
                                )

                                Text(
                                    text = column.title,

                                    style = MaterialTheme.typography.bodyMedium,

                                    modifier = Modifier.padding(start = 8.dp),

                                    maxLines = 1,

                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },

        // 右下角「确定」
        confirmButton = {

            TextButton(
                onClick = { onConfirm(draft) }
            ) {
                Text(
                    text = "确定"
                )
            }
        }
    )
}
