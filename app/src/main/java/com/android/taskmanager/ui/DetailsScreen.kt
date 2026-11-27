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
 * 最前面那一列是"进程图标列"（[DETAIL_ICON_COLUMN_WIDTH]）：
 * - 它不属于列定义、不出现在「选择列」里、不参与排序，固定在所有列的最左边；
 * - 表头对应那一格留空，数据行的格子画一个定尺空槽，等图标来源接进来再往里画。
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

// 菜单里几项要真的"对外发生动作"：打开浏览器 / 复制路径 / 申请存储权限，都需要 Context 与系统意图
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider

import com.android.taskmanager.AppStrings
import com.android.taskmanager.R

import java.io.File

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

// 表头的列边界线：直接画在已有的背景层上，不额外占布局（见 DetailHeaderRow）
import androidx.compose.ui.draw.drawBehind

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.taskmanager.data.ProcessIconCache
import com.android.taskmanager.data.ProcessScope
import com.android.taskmanager.service.UIService

import kotlin.math.abs
import kotlin.math.roundToInt

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

/**
 * 搜索框在本页登记用的页名。
 *
 * 这是**登记用的内部标识**，不是界面上显示的文字：UIService 按这个字符串存"这一页的搜索词"，
 * 一旦跟着语言变，切语言后搜索状态就会丢。所以这里保持固定值，标题文案另走 R.string。
 */
private const val SEARCH_PAGE_DETAIL = "详细信息"

/**
 * 表格最前面给进程图标留出的列宽。
 *
 * 这一列不在 [DetailColumn] 里，也就不参与排序、不受「选择列」勾选影响：
 * 它跟着表格一起横向滚动，位置永远在所有列的最左边（也就是名称列之前）。
 * 列宽固定，所以加它之后每一列的 x 起点整体右移这一段，行与表头仍然逐列对齐。
 */
private val DETAIL_ICON_COLUMN_WIDTH = 32.dp

/** 图标槽本身的边长（留白由列宽与居中分担）。 */
private val DETAIL_ICON_SIZE = 20.dp

/**
 * 表头「拖宽把手」的命中宽度（左右各 8dp，共 16dp）。
 *
 * 位置在每一列的**右边界**上：手指按在边界附近往下拖，就是改左边那一列的宽度。
 * 比 8dp 更窄的话手指按不准；更宽的话会把表头中间的点击区吃掉太多。
 */
private val COLUMN_RESIZE_TOUCH_WIDTH = 8.dp

/**
 * 页面侧的列宽范围（与 UIService 的 `DETAIL_COLUMN_MIN_WIDTH_DP/MAX_WIDTH_DP` 保持一致）。
 *
 * 拖动过程中先在页面本地夹一次，保证"看到的值"和"最后存下来的值"永远一样；
 * 真正的权威值仍在 UIService（它落盘前还会再夹一次）。
 */
private const val COLUMN_MIN_WIDTH_DP = 64
private const val COLUMN_MAX_WIDTH_DP = 640

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

    // ---------- 长按操作菜单：一次只开一个"层" ----------
    // 菜单目标（非空 = 菜单打开）
    var actionTarget by remember { mutableStateOf<ProcessRow?>(null) }

    // 结束进程树：先确认（与单杀同一个确认框的外壳）
    var killTreeRequest by remember { mutableStateOf<ProcessRow?>(null) }

    // 设置优先级 / 设置相关性：各自一个无标题选项对话框
    var priorityTarget by remember { mutableStateOf<ProcessRow?>(null) }
    var priorityInfo by remember { mutableStateOf<ProcessSchedInfo?>(null) }

    var affinityTarget by remember { mutableStateOf<ProcessRow?>(null) }
    var affinityInfo by remember { mutableStateOf<ProcessSchedInfo?>(null) }

    // 分析等待链
    var waitTarget by remember { mutableStateOf<ProcessRow?>(null) }
    var waitResult by remember { mutableStateOf<ProcessWaitChain?>(null) }
    var waitLoading by remember { mutableStateOf(false) }

    // 创建内存转储：不再确认，点菜单项直接开始；成功后弹结果框
    var dumpResult by remember { mutableStateOf<ProcessDumpResult?>(null) }

    // 普通权限下转储被 EACCES 拒绝时，跳一次「所有文件访问」页并在授权后自动重试
    var dumpGrantTarget by remember { mutableStateOf<ProcessRow?>(null) }
    var dumpGrantReason by remember { mutableStateOf("") }
    var dumpPermissionAskedPid by remember { mutableStateOf<Int?>(null) }

    // 打开文件所在的位置 / 属性
    var propsTarget by remember { mutableStateOf<ProcessRow?>(null) }

    // 壳进程（app_process / tango_translator / zygote_tango）先问一句：
    // 打开壳文件本身，还是打开它所属应用的 APK
    var locationTarget by remember { mutableStateOf<ProcessRow?>(null) }

    // 属性页：打开时单独做一次"全字段"查询，隐藏列也有值
    var propsFullRow by remember { mutableStateOf<ProcessRow?>(null) }
    var propsLoading by remember { mutableStateOf(false) }

    // 转到服务
    var serviceTarget by remember { mutableStateOf<ProcessRow?>(null) }
    var serviceText by remember { mutableStateOf<String?>(null) }
    var serviceError by remember { mutableStateOf<String?>(null) }

    // 操作的临时提示（"正在执行…" / "已复制"）：非空即显示，不弹对话框
    var actionHint by remember { mutableStateOf<String?>(null) }

    /**
     * 本地点选（乐观更新）。
     *
     * 为什么需要它：点一下要等下一轮轮询（500ms ~ 3s）才能看到高亮，手感就是"点了没反应"。
     * 这里先本地高亮，同时上报 UIService；下一轮快照到了就以服务值为准并清掉这份草稿。
     */
    var pendingSelectedIdentity by remember { mutableStateOf<String?>(null) }

    // AppStrings.s(R.string.details_select_columns)对话框是否打开（表头任意位置长按打开）
    var showColumnPicker by remember { mutableStateOf(false) }

    // 正在等用户确认的那一行（红色按钮点了之后先弹确认框，此时还没发命令）
    var killRequest by remember { mutableStateOf<ProcessRow?>(null) }

    // "无法完成该操作"的副标题：非空即显示失败对话框（成功不提示，只刷一次表）
    var killFailure by remember { mutableStateOf<String?>(null) }

    // 结束进程要发 IPC 并等回执（几十毫秒），不能占主线程
    val killScope = rememberCoroutineScope()

    // 菜单与各种"对外跳页"都要用：统一在这里取一次
    val context = LocalContext.current

    // 转储落盘的实际动作：成功攒一条 dumpResult（成功对话框），
    // 失败一律走 killFailure —— 与结束进程失败共用同一套「无法完成该操作。拒绝访问。」。
    // 点了就直接转，不再弹确认框。
    val performDump: (ProcessRow) -> Unit = { target ->

        killScope.launch {

            val result = withContext(Dispatchers.IO) {
                UIService.instanceOrNull()?.dumpDetailProcessMemory(target.pid, target.startTicks)
                    ?: ProcessDumpResult(false, reason = AppStrings.s(R.string.details_service_not_ready))
            }

            if (result.ok) {

                dumpPermissionAskedPid = null
                dumpGrantTarget = null
                dumpResult = result

            } else if (
                result.permissionDenied &&
                !hasDumpStorageAccess(context) &&
                dumpPermissionAskedPid != target.pid
            ) {

                // 不是 root / shell，应用自己又没有共享存储权限：只再申请一次「所有文件访问」，
                // 拿回来就自动重试这次转储；再失败就按失败弹框，不再反复骚扰。
                dumpPermissionAskedPid = target.pid
                dumpGrantReason = result.reason
                dumpGrantTarget = target

                openDumpStorageSettings(context)

            } else {

                dumpGrantTarget = null

                killFailure = result.reason
            }
        }
    }

    // 从「所有文件访问」系统页回来后自动重试：直接轮询授权状态，
    // 拿到就接着转储（浮窗里没有 ActivityResultRegistryOwner，不能用 launcher）。
    LaunchedEffect(dumpGrantTarget) {

        val target = dumpGrantTarget ?: return@LaunchedEffect

        var waited = 0

        while (waited < DUMP_PERMISSION_WAIT_MS) {

            if (hasDumpStorageAccess(context)) {

                dumpGrantTarget = null

                performDump(target)

                return@LaunchedEffect
            }

            delay(500)

            waited += 500
        }

        dumpGrantTarget = null

        killFailure = dumpGrantReason
    }

    // 轮询：UIService 就绪后每 refreshIntervalMs 取一次快照。
    // 本页不判断"该不该刷新数据"，只按 UIService 给的间隔执行（暂停=0 时走空闲间隔）；
    // 门禁交给 isUiRefreshAllowed()：不在前台、浮窗也没显示时，读到的数据没人看得见，
    // 这一轮就直接跳过——不读、不改状态，Compose 也就不会重组。
    LaunchedEffect(Unit) {

        // 进页面先按需采一次：默认AppStrings.s(R.string.details_ignore_interval)= 不自动刷新，
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

        // 表格上方不再放任何说明文字：来源 / 行数 / 排序 / 选中 / 操作提示全部去掉，
        // 顶部直接就是表头。

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
                // 被筛掉的行不认：看不见的行不该还能点AppStrings.s(R.string.details_end_process)
                visibleRows.firstOrNull { it.pid == pid && it.startTicks == ticks }
            }
        }

        // ---------- 表格本体：占满剩余高度 ----------
        // 不再在表头上方留 Spacer：PageShell 已经只留 8dp 顶部，
        // 这里再加 6dp 会让详细信息页比其它页多一截空隙。
        DetailTableBody(
            table = table,

            rows = visibleRows,

            emptyHint = if (searchQuery.isEmpty()) {
                AppStrings.s(R.string.details_empty)
            } else {
                AppStrings.s(R.string.details_no_match, searchQuery)
            },

            selectedIdentity = selectedIdentity,

            modifier = Modifier
                .fillMaxWidth()
                // 占满标题区以下的全部高度；行多就纵向滚，列宽就横向滚
                .weight(1f),

            onSortColumn = { columnId ->
                UIService.instanceOrNull()?.sortDetailTableBy(columnId)
            },

            // 表头任意位置长按：打开AppStrings.s(R.string.details_select_columns)对话框
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

                actionTarget = row
            },

            // 松手那一刻才落盘：拖动过程只改页面本地预览，避免每帧都写一次档
            onColumnWidthCommit = { columnId, widthDp ->
                UIService.instanceOrNull()?.setDetailColumnWidth(columnId, widthDp)
            }
        )

        // ---------- 底部工具条：左下范围三档 / 中间忽略开关 / 右下「刷新」与「结束进程」 ----------
        DetailFooterBar(
            scope = table.processScope,

            ignoreUpdateSpeed = table.ignoreUpdateSpeed,

            // 能被AppStrings.s(R.string.details_end_process)操作的行，必须由 pid + 启动时刻双重确认：
            // 只凭 pid 时，目标已退出、PID 被新进程占用，就会结束掉另一个进程。
            killTarget = killCandidate,

            onScopeChange = { scope ->
                UIService.instanceOrNull()?.setDetailProcessScope(scope)
            },

            onToggleIgnoreSpeed = { ignore ->
                UIService.instanceOrNull()?.setDetailIgnoreUpdateSpeed(ignore)
            },

            onRefresh = {
                // 立即采一次（不看AppStrings.s(R.string.details_ignore_interval)），随后读表把状态同步过来；
                // 新包落下后下一轮轮询会读到它，不需要页面自己等待。
                UIService.instanceOrNull()?.refreshDetailNow()

                UIService.instanceOrNull()?.detailTable()?.let { table = it }
            },

            onKill = { row ->
                // 点一下只是弹确认框；真正发命令在用户点AppStrings.s(R.string.details_end_process)之后
                killRequest = row
            }
        )
    }

    // ---------- 选择列对话框：勾完点AppStrings.s(R.string.common_ok)整份报给 UIService，并立刻刷新表 ----------
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

            // 点空白/返回键等同AppStrings.s(R.string.common_cancel)：什么都没发生，不弹失败框
            onDismissRequest = { killRequest = null },

            title = AppStrings.s(R.string.details_end_confirm, target.name),

            body = {
                Text(
                    text = AppStrings.s(R.string.details_end_warning_app) +
                        AppStrings.s(R.string.details_end_warning_system),

                    style = MaterialTheme.typography.bodyMedium
                )
            },

            confirmText = AppStrings.s(R.string.details_end_process),

            onConfirm = {

                killRequest = null

                killScope.launch {

                    val result = withContext(Dispatchers.IO) {
                        UIService.instanceOrNull()?.killDetailProcess(
                            target.pid,
                            target.startTicks
                        ) ?: ProcessActionResult(false, AppStrings.s(R.string.details_service_not_ready))
                    }

                    // 成功什么都不提示：UIService 会在 1 秒后自己重采一次，
                    // 页面轮询跟着更新即可（立刻读表反而会把刚结束的行又读回来）。
                    if (!result.ok) {
                        killFailure = result.reason
                    }
                }
            },

            dismissText = AppStrings.s(R.string.common_cancel),

            onDismiss = { killRequest = null }
        )
    }

    // ---------- 结束进程：失败提示（同样是设置页那套外壳，内容换成错误说明） ----------
    killFailure?.let { reason ->

        DetailActionDialog(
            hostedInOverlay = hostedInOverlay,

            // 这是结果通知，不看一次不该消失
            onDismissRequest = { },

            title = AppStrings.s(R.string.details_operation_failed),

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

            confirmText = AppStrings.s(R.string.common_ok),

            onConfirm = { killFailure = null },

            // 结果通知只留一个按钮
            dismissText = null,

            onDismiss = { }
        )
    }

    // =====================================================
    // 长按操作菜单（对齐 Windows 任务管理器那套条目）
    // =====================================================

    /** 菜单里的"动手"项都要求 pid + 启动时刻齐备，缺了就整块置灰。 */
    fun actionable(row: ProcessRow): Boolean =
        UIService.instanceOrNull()?.detailProcessIsActionable(row.pid, row.startTicks) ?: (row.startTicks >= 0L)

    actionTarget?.let { target ->

        val canAct = actionable(target)

        DetailMenuDialog(
            hostedInOverlay = hostedInOverlay,
            actionable = canAct,
            locationEnabled = hasProcessExecutablePath(target),

            onDismiss = { actionTarget = null },

            onPick = { action ->

                actionTarget = null

                when (action) {

                    DetailMenuAction.END_TASK -> killRequest = target

                    DetailMenuAction.END_TREE -> killTreeRequest = target

                    // 与设置页最下面那个「发送反馈」按钮完全同一个动作：打开项目的问题页
                    DetailMenuAction.FEEDBACK -> openUrl(context, SETTINGS_FEEDBACK_URL)

                    DetailMenuAction.PRIORITY -> {

                        priorityInfo = null
                        priorityTarget = target

                        // 当前 nice 在后台读：读不到就让对话框显示"读不到，但仍可设置"
                        killScope.launch {

                            val info = withContext(Dispatchers.IO) {
                                UIService.instanceOrNull()?.detailProcessSchedInfo(target.pid)
                            }

                            if (priorityTarget?.pid == target.pid) priorityInfo = info
                        }
                    }

                    DetailMenuAction.AFFINITY -> {

                        affinityInfo = null
                        affinityTarget = target

                        killScope.launch {

                            val info = withContext(Dispatchers.IO) {
                                UIService.instanceOrNull()?.detailProcessSchedInfo(target.pid)
                            }

                            if (affinityTarget?.pid == target.pid) affinityInfo = info
                        }
                    }

                    DetailMenuAction.WAIT_CHAIN -> {

                        waitResult = null
                        waitLoading = true
                        waitTarget = target

                        killScope.launch {

                            val chain = withContext(Dispatchers.IO) {
                                UIService.instanceOrNull()?.detailProcessWaitChain(target.pid)
                            }

                            if (waitTarget?.pid == target.pid) {

                                waitResult = chain
                                waitLoading = false
                            }
                        }
                    }

                    DetailMenuAction.DUMP -> performDump(target)

                    // 原生进程直接打开；壳进程（app_process / tango_translator / zygote_tango）
                    // 先弹二选一：壳文件本身，还是它所属应用的 APK
                    DetailMenuAction.LOCATION -> {
                        if (isShellProcess(target)) {
                            locationTarget = target
                        } else {
                            openProcessLocation(context, target)
                        }
                    }

                    DetailMenuAction.WEB_SEARCH -> openWebSearch(context, target)

                    DetailMenuAction.PROPERTIES -> {

                        propsTarget = target
                        propsFullRow = null
                        propsLoading = true

                        // 属性页要全部列：单独走一次全字段快照查询，再挑出这一行
                        killScope.launch {

                            val full = withContext(Dispatchers.IO) {
                                UIService.instanceOrNull()?.detailProcessFullRow(target.pid, target.startTicks)
                            }

                            if (propsTarget?.pid == target.pid && propsTarget?.startTicks == target.startTicks) {
                                propsFullRow = full
                                propsLoading = false
                            }
                        }
                    }

                    DetailMenuAction.SERVICE -> {

                        serviceText = null
                        serviceError = null
                        serviceTarget = target

                        val pkg = target.packageName
                            ?: target.values[DetailColumnId.PACKAGE_NAME]?.substringBefore('（')?.trim()

                        if (pkg.isNullOrBlank()) {

                            serviceError = AppStrings.s(R.string.details_service_need_package)

                        } else {

                            killScope.launch {

                                val text = withContext(Dispatchers.IO) {
                                    UIService.instanceOrNull()?.detailProcessServices(pkg)
                                }

                                if (serviceTarget?.pid == target.pid) {

                                    // dumpsys 没有匹配时给的是 "No services matching" 之类的短句，
                                    // 这里只认 ServiceRecord 开头的记录块，其余一律当"没有服务"
                                    serviceText = text
                                        ?.takeIf { it.contains("ServiceRecord") }
                                        ?.trim()

                                    if (serviceText == null) {
                                        serviceError = AppStrings.s(R.string.details_service_empty, pkg)
                                    }
                                }
                            }
                        }
                    }

                    // 两项明确不做的：这里保持"点了也不发生任何事"，条目本身就是灰的
                    // （「UAC 虚拟化」是 Windows 专有机制，已整项移除，不再占一行）
                    DetailMenuAction.DEBUG,
                    DetailMenuAction.EFFICIENCY -> Unit
                }
            }
        )
    }

    // ---------- 打开位置：壳进程二选一 ----------
    locationTarget?.let { target ->

        // APK 只在打开对话框时查一次；查不到就把第二项置灰，不拿壳路径顶替
        val apkPath = remember(target.pid, target.packageName) {
            resolveProcessApk(context, target)
        }

        DetailLocationDialog(
            hostedInOverlay = hostedInOverlay,
            packageEnabled = apkPath != null,
            onDismiss = { locationTarget = null },
            onOpenFile = {
                locationTarget = null
                openProcessExecutable(context, target)
            },
            onOpenPackage = {
                locationTarget = null
                apkPath?.let { openFilePath(context, it) }
            }
        )
    }

    // ---------- 结束进程树：确认 ----------
    killTreeRequest?.let { target ->

        DetailActionDialog(
            hostedInOverlay = hostedInOverlay,

            onDismissRequest = { killTreeRequest = null },

            title = AppStrings.s(R.string.details_end_tree_confirm, target.name),

            body = {
                Text(
                    text = AppStrings.s(R.string.details_end_tree_warning),

                    style = MaterialTheme.typography.bodyMedium
                )
            },

            confirmText = AppStrings.s(R.string.details_end_tree_action),

            onConfirm = {

                killTreeRequest = null

                killScope.launch {

                    val result = withContext(Dispatchers.IO) {
                        UIService.instanceOrNull()?.killDetailProcessTree(target.pid, target.startTicks)
                            ?: ProcessActionResult(false, AppStrings.s(R.string.details_service_not_ready))
                    }

                    if (!result.ok) killFailure = result.reason
                }
            },

            dismissText = AppStrings.s(R.string.common_cancel),

            onDismiss = { killTreeRequest = null }
        )
    }

    // ---------- 设置优先级：无标题选项对话框 ----------
    priorityTarget?.let { target ->

        DetailPriorityDialog(
            hostedInOverlay = hostedInOverlay,
            currentNice = priorityInfo?.nice,
            onDismiss = { priorityTarget = null },
            onPick = { nice ->

                priorityTarget = null

                killScope.launch {

                    val result = withContext(Dispatchers.IO) {
                        UIService.instanceOrNull()?.setDetailProcessPriority(target.pid, target.startTicks, nice)
                            ?: ProcessActionResult(false, AppStrings.s(R.string.details_service_not_ready))
                    }

                    if (!result.ok) killFailure = result.reason
                }
            }
        )
    }

    // ---------- 设置相关性：无标题多选对话框 ----------
    affinityTarget?.let { target ->

        DetailAffinityDialog(
            hostedInOverlay = hostedInOverlay,
            processName = target.name,
            cpuCount = affinityInfo?.cpuCount ?: logicalCpuCount(),
            currentMask = affinityInfo?.affinity ?: 0L,
            onDismiss = { affinityTarget = null },
            onConfirm = { mask ->

                affinityTarget = null

                killScope.launch {

                    val result = withContext(Dispatchers.IO) {
                        UIService.instanceOrNull()?.setDetailProcessAffinity(target.pid, target.startTicks, mask)
                            ?: ProcessActionResult(false, AppStrings.s(R.string.details_service_not_ready))
                    }

                    if (!result.ok) killFailure = result.reason
                }
            }
        )
    }

    // ---------- 分析等待链 ----------
    waitTarget?.let { target ->

        DetailWaitChainDialog(
            hostedInOverlay = hostedInOverlay,
            processName = target.name,
            loading = waitLoading,
            chain = waitResult,
            onDismiss = {
                waitTarget = null
                waitLoading = false
            }
        )
    }

    // ---------- 创建内存转储：成功 ----------
    // 没有确认层：菜单点了就直接转（失败按统一口径弹「无法完成该操作。」）。
    // 失败不走这里：统一进上面的 killFailure（标题「无法完成该操作。」+ 原因）。
    dumpResult?.let { result ->

        DetailActionDialog(
                hostedInOverlay = hostedInOverlay,

                // 结果通知，不看一次不该消失；点空白等同"确定"
                onDismissRequest = { dumpResult = null },

                title = AppStrings.s(R.string.details_dump_progress_title),

                body = {

                    Text(
                        text = AppStrings.s(R.string.details_dump_done_line),

                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = AppStrings.s(R.string.details_dump_location, result.path),

                        style = MaterialTheme.typography.bodySmall,

                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },

                confirmText = AppStrings.s(R.string.common_ok),

                onConfirm = { dumpResult = null },

                // 第二个按钮：直接在系统文件管理器里打开 Download 目录
                dismissText = AppStrings.s(R.string.details_dump_open_location),

                onDismiss = {
                    openDumpFolder(context, result.path)
                    dumpResult = null
                }
        )
    }

    // ---------- 属性 ----------
    propsTarget?.let { target ->

        DetailPropertiesDialog(
            hostedInOverlay = hostedInOverlay,
            table = table,
            row = propsFullRow ?: target,
            loading = propsLoading,
            onDismiss = {
                propsTarget = null
                propsFullRow = null
                propsLoading = false
            }
        )
    }

    // ---------- 转到服务 ----------
    serviceTarget?.let { target ->

        DetailTextDialog(
            hostedInOverlay = hostedInOverlay,

            title = AppStrings.s(R.string.details_service_title),

            body = serviceText ?: serviceError ?: AppStrings.s(R.string.details_service_querying),

            onDismiss = { serviceTarget = null }
        )
    }
}

/** 一次菜单里能做/不能做的动作（对齐 Windows 任务管理器，去掉 Android 不适用的「UAC 虚拟化」后共 13 项）。 */
private enum class DetailMenuAction {
    END_TASK,
    END_TREE,
    FEEDBACK,
    EFFICIENCY,
    PRIORITY,
    AFFINITY,
    WAIT_CHAIN,
    DEBUG,
    DUMP,
    LOCATION,
    WEB_SEARCH,
    PROPERTIES,
    SERVICE
}

/** 打开外部链接：浮窗里没有 Activity 令牌，必须带 NEW_TASK；没有浏览器就什么都不做。 */
private fun openUrl(context: Context, url: String) {

    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (error: ActivityNotFoundException) {
        // 设备上没有能处理这个链接的应用：静默失败（与设置页的反馈按钮一致）
    }
}

/** 在线搜索：优先交给系统的"网页搜索"意图，退化到浏览器打开搜索页。 */
private fun openWebSearch(context: Context, row: ProcessRow) {

    val query = row.packageName ?: row.name

    try {

        context.startActivity(
            Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra("query", query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    } catch (error: ActivityNotFoundException) {

        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            // 两者都没有：什么都不做
        }
    }
}

/** 复制一段文本到剪贴板，并给一条提示（提示用 Toast，浮窗里也能显示）。 */
private fun copyToClipboard(context: Context, text: String, message: String) {

    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    manager?.setPrimaryClip(ClipData.newPlainText("TaskManager", text))

    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * 设备有多少个逻辑 CPU 核心（`/sys/devices/system/cpu/present`，形如 "0-7"）。
 *
 * 相关性复选框要按"逻辑核心编号"列：0..N-1 与内核亲和性掩码的位号一一对应，
 * 不是按物理处理器颗数。读不到这个文件时退回 JVM 看到的可用处理器数。
 */
private fun logicalCpuCount(): Int {

    try {

        val text = java.io.File("/sys/devices/system/cpu/present").readText().trim()

        var max = -1

        var cursor = 0

        while (cursor < text.length) {

            val digitStart = cursor

            while (cursor < text.length && text[cursor].isDigit()) cursor++

            if (digitStart == cursor) {
                cursor++
                continue
            }

            val first = text.substring(digitStart, cursor).toIntOrNull() ?: 0

            if (cursor < text.length && text[cursor] == '-') {

                cursor++

                val secondStart = cursor

                while (cursor < text.length && text[cursor].isDigit()) cursor++

                val last = text.substring(secondStart, cursor).toIntOrNull() ?: first

                if (last > max) max = last

            } else if (first > max) {

                max = first
            }
        }

        if (max >= 0) return (max + 1).coerceIn(1, 64)

    } catch (_: Exception) {
        // 读不到就退回下面那一行
    }

    return Runtime.getRuntime().availableProcessors().coerceIn(1, 64)
}

/** 转储被存储权限拒绝后，等用户从「所有文件访问」页回来的最长时间（毫秒）。 */
private const val DUMP_PERMISSION_WAIT_MS = 30_000

/**
 * 应用进程自己能不能写共享存储。
 *
 * 转储正常是由 root / shell 后端写的，压根不看这里；只有普通权限后端写不进去
 * （EACCES）时，才需要应用拿到「所有文件访问」再重试一次。
 */
private fun hasDumpStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

/** 打开「所有文件访问」授权页；个别 ROM 没有应用级入口时退到总开关页。 */
private fun openDumpStorageSettings(context: Context) {

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val appPage = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:" + context.packageName)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val opened = runCatching { context.startActivity(appPage) }.isSuccess

    if (!opened) {

        val listPage = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(listPage) }
    }
}

/**
 * 这一行进程"打开文件所在的位置"要打开哪个文件。
 *
 * 规则只有一条：**以进程自己报出来的可执行路径为准**。
 * - `/product/...`、`/vendor/...`、`/system/bin/xxx` 这类原生进程：原路径直接打开，
 *   不做任何"猜包 / 猜 apk"的替换；
 * - 路径是 Android 运行时 / 转译层的"壳"时，壳本身不是用户想看的文件，改为去包管理器里
 *   取该进程所属包的 `sourceDir`（base.apk）来打开。这些壳包括：
 *   AOSP 的 `app_process` / `app_process32` / `app_process64`，
 *   OEM ARM32 转译环境的 `tango_translator` / `zygote_tango`；
 * - 没有 EXE_PATH 的进程直接返回空串，调用方据此把菜单项置灰；
 * - 是上面这些壳、但又定不出所属包（例如 zygote 本身）时也返回空串，
 *   宁可什么都不打开，也不把 `/system/bin/...` 的壳当成结果。
 */
private fun resolveProcessFile(context: Context, row: ProcessRow): String {

    val exe = resolveProcessExecutable(row)

    if (exe.isEmpty()) return ""

    // 原生 Linux 进程：进程说在哪就在哪
    if (!isAppProcessExecutable(exe)) return exe

    // Android / 转译层进程：壳只是入口，实际落点是该包的 base.apk
    return resolveProcessApk(context, row).orEmpty()
}

/** 进程自己报出来的可执行路径；没有就返回空串。 */
private fun resolveProcessExecutable(row: ProcessRow): String =
    row.values[DetailColumnId.EXE_PATH]?.trim().orEmpty()

/** 壳进程所属应用的 base.apk；包不可见 / 已卸载 / 文件不存在时返回 null。 */
private fun resolveProcessApk(context: Context, row: ProcessRow): String? {

    val pkg = row.packageName
        ?: row.values[DetailColumnId.PACKAGE_NAME]
            ?.substringBefore('（')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    if (pkg == null) return null

    return try {

        val info = context.packageManager.getApplicationInfo(pkg, 0)

        info.sourceDir?.trim()
            ?.takeIf { it.isNotEmpty() && File(it).exists() }

    } catch (_: Exception) {
        // 包被卸载 / 不可见：找不到对应 APK
        null
    }
}

/**
 * Android 运行时与 OEM ARM32 转译层的壳程序。
 *
 * `app_process*` 是 AOSP zygote 拉进程的入口；`tango_translator` / `zygote_tango`
 * 是部分 OEM（如小米 Tango）给 32 位应用做指令转译时占用的可执行路径。
 * 这些进程真正的"文件"是所属应用的 APK，不是 `/system/bin` 下的壳。
 */
private fun isAppProcessExecutable(path: String): Boolean {

    val name = path.substringAfterLast('/')

    if (name.startsWith("app_process")) return true

    return name == "tango_translator" || name == "zygote_tango"
}

/** 是不是上面这些壳进程：是的话「打开文件位置」先问用户要打开哪一个。 */
private fun isShellProcess(row: ProcessRow): Boolean =
    isAppProcessExecutable(resolveProcessExecutable(row))

/** 进程有没有报出可执行路径：没有的话「打开文件所在的位置」直接置灰。 */
private fun hasProcessExecutablePath(row: ProcessRow): Boolean =
    row.values[DetailColumnId.EXE_PATH]?.isNotBlank() == true

/**
 * 「打开文件所在的位置」：把目标文件交给系统文件管理器 / 安装器。
 *
 * 为什么不是"打开所在目录"：base.apk 在 /data/app 下、原生程序多在 /system 或 /vendor，
 * 这些位置系统文件管理器根本浏览不到；直接把文件本身以 content:// 交出去，
 * 由接收方显示"这是哪个包 / 什么可执行文件"，才真的能打开。
 * 没有明确处理者时带一层 chooser，让用户自己挑文件管理器或查看方式。
 * 打不开（路径不存在 / 没有处理者）就静默结束，不弹任何错误提示。
 */
private fun openProcessLocation(context: Context, row: ProcessRow) {
    openFilePath(context, resolveProcessFile(context, row))
}

/** 只打开进程自己报出来的可执行文件（壳进程二选一时的第一项）。 */
private fun openProcessExecutable(context: Context, row: ProcessRow) {
    openFilePath(context, resolveProcessExecutable(row))
}

/** 把一个确定的文件路径交给系统打开；不存在 / 空路径直接静默返回。 */
private fun openFilePath(context: Context, path: String) {

    // 没有路径 / 文件不存在 / 没有应用能打开：一律静默放弃，不弹错误对话框
    if (path.isBlank()) return

    val file = File(path)

    if (!file.exists()) return

    val mime = if (path.endsWith(".apk", ignoreCase = true)) {
        "application/vnd.android.package-archive"
    } else {
        "application/octet-stream"
    }

    try {

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.processfiles", file)

        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // chooser 会把子 Intent 的授权转给最终被选中的应用；
        // 再挂一份 ClipData 是为了个别 ROM 上 chooser 只认真实数据 URI 的情况。
        view.clipData = ClipData.newRawUri("", uri)

        context.startActivity(
            Intent.createChooser(view, AppStrings.s(R.string.details_location_open_with))
        )

    } catch (_: Exception) {

        // 一个能接的应用都没有（或 provider 拒绝）：静默不打开，不提示
    }
}

/**
 * 「打开文件位置」：把转储所在的共享目录交给系统文件管理器。
 *
 * 共享存储走 ExternalStorageProvider 的 `primary:<相对路径>` 文档 URI，
 * 不依赖任何第三方文件管理器；没有应用能接收这个意图时，退回复制路径。
 */
private fun openDumpFolder(context: Context, filePath: String) {

    val directory = filePath.substringBeforeLast('/', "")

    if (directory.isBlank()) return

    val root = Environment.getExternalStorageDirectory().absolutePath

    val relative = directory.removePrefix(root).trimStart('/')

    if (relative.isBlank()) return

    try {

        val uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$relative"
        )

        val openDirectory = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // 先点名系统"文件"应用：部分 ROM 的通用 VIEW 解析不认目录 URI，
        // 会把选择器弹成 APK 安装 / 邮件之类的无关列表（真机上见过）。
        val filesComponent = ComponentName(
            "com.google.android.documentsui",
            "com.android.documentsui.files.FilesActivity"
        )

        try {
            context.startActivity(Intent(openDirectory).setComponent(filesComponent))
        } catch (_: Exception) {
            context.startActivity(openDirectory)
        }

    } catch (error: Exception) {

        // 没有文件管理器接这个目录 URI：至少把完整路径给到用户，方便自己去找
        copyToClipboard(context, directory, AppStrings.s(R.string.details_location_copied))
    }
}

/** 字节数转人看的单位（转储结果里显示大小用）。 */
private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 详细信息页的对话框外壳（与设置页的 SettingsDialog 是同一套样子，只换了尺寸细节）。
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
        // 遮罩层：点击空白处等同AppStrings.s(R.string.common_cancel)
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
 * 通用对话壳（无标题 / 有标题都走它）。
 *
 * 与 [DetailActionDialog] 的区别：这里标题可以为 null（「设置优先级」「设置相关性」
 * 按要求**没有标题**，只有选项），正文自己决定要不要滚动。
 */
@Composable
private fun DetailSurfaceDialog(
    hostedInOverlay: Boolean,

    onDismissRequest: () -> Unit,

    title: String?,

    body: @Composable ColumnScope.() -> Unit,

    /** 底部按钮区；null = 只放一个「确定」 */
    footer: (@Composable () -> Unit)? = null
) {

    val titleSlot: (@Composable () -> Unit)? = title?.let { text ->
        {
            Text(
                text = text,

                style = MaterialTheme.typography.titleMedium
            )
        }
    }

    val okSlot: @Composable () -> Unit = {
        TextButton(onClick = onDismissRequest) {
            Text(AppStrings.s(R.string.common_ok))
        }
    }

    if (!hostedInOverlay) {

        AlertDialog(
            onDismissRequest = onDismissRequest,

            title = titleSlot,

            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    body()
                }
            },

            confirmButton = {
                if (footer != null) footer() else okSlot()
            }
        )

        return
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable { onDismissRequest() }
        )

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
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                titleSlot?.invoke()

                body()

                if (footer != null) footer() else okSlot()
            }
        }
    }
}

/**
 * 壳进程的「打开位置」二选一：打开壳文件本身，还是打开所属应用的 APK。
 *
 * 只给两项，不带路径括号；APK 定不出来（没有包 / 包不可见）时第二项置灰，
 * 但仍然摆出来，让用户知道这里本来还有这个选择。
 */
@Composable
private fun DetailLocationDialog(
    hostedInOverlay: Boolean,

    packageEnabled: Boolean,

    onDismiss: () -> Unit,

    onOpenFile: () -> Unit,

    onOpenPackage: () -> Unit
) {

    DetailSurfaceDialog(
        hostedInOverlay = hostedInOverlay,

        onDismissRequest = onDismiss,

        title = AppStrings.s(R.string.details_location_choice_title),

        body = {

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
        },

        footer = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.s(R.string.common_cancel))
            }
        }
    )
}

/**
 * 长按进程行弹出的操作菜单（条目与顺序对齐 Windows 任务管理器）。
 *
 * 13 项固定分五组，组间一条细分割线；不做的两项（调试 / 效能模式）
 * 一律置灰并带一行小字说明原因，点了不产生任何动作。
 * （Windows 那套里的「UAC 虚拟化」在 Android 上没有对应机制，整项不再提供。）
 */
@Composable
private fun DetailMenuDialog(
    hostedInOverlay: Boolean,

    /** pid + 启动时刻凑齐了吗：不齐的话所有"动手"项都置灰 */
    actionable: Boolean,

    /** 进程有没有报出可执行路径：没有路径时「打开文件所在的位置」整项置灰 */
    locationEnabled: Boolean,

    onDismiss: () -> Unit,

    onPick: (DetailMenuAction) -> Unit
) {

    val body: @Composable ColumnScope.() -> Unit = {

        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {

            // ---- 第 1 组：结束 ----
            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_end_task),
                enabled = actionable,
                onClick = { onPick(DetailMenuAction.END_TASK) }
            )

            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_end_tree),
                enabled = actionable,
                onClick = { onPick(DetailMenuAction.END_TREE) }
            )

            // 「提供反馈」与结束进程同组（Windows 任务管理器也是放在第一组的）
            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_feedback),
                enabled = true,
                onClick = { onPick(DetailMenuAction.FEEDBACK) }
            )

            DetailMenuDivider()

            // ---- 第 2 组：调度 ----
            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_efficiency),
                enabled = false,
                subtitle = AppStrings.s(R.string.details_menu_disabled_efficiency),
                onClick = { }
            )

            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_priority),
                enabled = actionable,
                onClick = { onPick(DetailMenuAction.PRIORITY) }
            )

            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_affinity),
                enabled = actionable,
                onClick = { onPick(DetailMenuAction.AFFINITY) }
            )

            DetailMenuDivider()

            // ---- 第 3 组：分析 / 调试 / 转储 ----
            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_wait_chain),
                enabled = true,
                onClick = { onPick(DetailMenuAction.WAIT_CHAIN) }
            )

            // 置灰但不写原因：按要求"下面不用提示什么"
            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_debug),
                enabled = false,
                onClick = { }
            )

            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_dump),
                enabled = actionable,
                onClick = { onPick(DetailMenuAction.DUMP) }
            )

            DetailMenuDivider()

            // ---- 第 5 组：其它 ----
            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_open_location),
                enabled = locationEnabled,
                onClick = { onPick(DetailMenuAction.LOCATION) }
            )

            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_web_search),
                enabled = true,
                onClick = { onPick(DetailMenuAction.WEB_SEARCH) }
            )

            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_properties),
                enabled = true,
                onClick = { onPick(DetailMenuAction.PROPERTIES) }
            )

            DetailMenuItem(
                label = AppStrings.s(R.string.details_menu_goto_service),
                enabled = true,
                onClick = { onPick(DetailMenuAction.SERVICE) }
            )
        }
    }

    DetailSurfaceDialog(
        hostedInOverlay = hostedInOverlay,

        onDismissRequest = onDismiss,

        // 菜单本身不放标题：标题会把"第几项"挤下去，也容易和条目混在一起
        title = null,

        body = body,

        footer = null
    )
}

/** 菜单里的一行：整行可点，灰项不吃点击，副标题说明为什么不做。 */
@Composable
private fun DetailMenuItem(
    label: String,
    enabled: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {

        Text(
            text = label,

            style = MaterialTheme.typography.bodyMedium,

            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )

        if (subtitle != null) {

            Text(
                text = subtitle,

                style = MaterialTheme.typography.labelSmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** 组间分割线：与 Windows 菜单里那几条横线对应。 */
@Composable
private fun DetailMenuDivider() {

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),

        thickness = 1.dp,

        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 「设置优先级」：整段 Linux nice 区间 -20..19 都能精确设。
 *
 * 早期版本只有六个固定档（-20/-10/-5/0/5/19），要 -7 这种中间值就设不了。
 * 现在用滑杆按整数步进走满 -20..19，上面实时显示选中的 nice；
 * 下面那排六个按钮只是"常用档一键跳"，不再是唯一可选集合。
 */
@Composable
private fun DetailPriorityDialog(
    hostedInOverlay: Boolean,

    /** 读到的当前 nice；null = 读不到 */
    currentNice: Int?,

    onDismiss: () -> Unit,

    onPick: (Int) -> Unit
) {

    // 常用档：点一下直接跳过去，滑杆仍可继续微调到任意整数
    val presets = remember {
        listOf(
            -20 to R.string.details_priority_realtime,
            -10 to R.string.details_priority_high,
            -5 to R.string.details_priority_above_normal,
            0 to R.string.details_priority_normal,
            5 to R.string.details_priority_below_normal,
            19 to R.string.details_priority_low
        )
    }

    // 读不到当前值就从 0（普通）起步：Linux 的默认 nice
    var nice by remember(currentNice) {
        mutableStateOf((currentNice ?: 0).coerceIn(-20, 19).toFloat())
    }

    val niceInt = nice.roundToInt()

    DetailSurfaceDialog(
        hostedInOverlay = hostedInOverlay,

        onDismissRequest = onDismiss,

        title = AppStrings.s(R.string.details_priority_title),

        body = {

            Text(
                text = if (currentNice == null) {
                    AppStrings.s(R.string.details_priority_read_failed)
                } else {
                    AppStrings.s(R.string.details_priority_current, currentNice)
                },

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = AppStrings.s(R.string.details_priority_value, niceInt),

                style = MaterialTheme.typography.titleMedium
            )

            // -20..19 共 40 个整数点位：steps = 38 让滑杆正好按 1 步进
            Slider(
                value = nice,

                onValueChange = { nice = it },

                valueRange = -20f..19f,

                steps = 38
            )

            Row(
                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Text(
                    text = "-20",

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "19",

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = AppStrings.s(R.string.details_priority_note),

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 常用档快捷键：横着排，窄屏可横向滚
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),

                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {

                presets.forEach { (value, labelRes) ->

                    TextButton(onClick = { nice = value.toFloat() }) {
                        Text(
                            text = AppStrings.s(labelRes),

                            style = MaterialTheme.typography.bodySmall,

                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        },

        footer = {

            Row(
                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {

                TextButton(onClick = onDismiss) {
                    Text(AppStrings.s(R.string.common_cancel))
                }

                Button(onClick = { onPick(niceInt) }) {
                    Text(AppStrings.s(R.string.common_ok))
                }
            }
        }
    )
}

/**
 * 「设置相关性」：Windows 任务管理器同款版式。
 *
 * 标题「处理器相关性」+ 一句"允许哪些处理器运行 <进程名>?"，下面一个「所有处理器」
 * 复选框，再往下是**竖排**的 CPU 0..N-1（与「选择列」对话框一样单列滚动）。
 * 取消勾选某颗 CPU 会自动让「所有处理器」变成未勾；勾上「所有处理器」则全选。
 *
 * @param processName 这一行的进程名，只用于正文那句问话
 * @param cpuCount    系统逻辑 CPU 数（决定列出几个复选框）
 * @param currentMask 当前亲和性；0 = 读不到（按 Linux 初值全选）
 */
@Composable
private fun DetailAffinityDialog(
    hostedInOverlay: Boolean,

    processName: String,

    cpuCount: Int,

    currentMask: Long,

    onDismiss: () -> Unit,

    onConfirm: (Long) -> Unit
) {

    val count = cpuCount.coerceIn(1, 64)

    val allMask = (1L shl count) - 1

    // 读不到当前值时就默认全选：这是 Linux 的初始状态，也不会把进程"锁"在一颗核上
    val initial = if (currentMask != 0L) currentMask and allMask else allMask

    var selected by remember(currentMask, count) { mutableStateOf(initial) }

    val allSelected = selected == allMask

    DetailSurfaceDialog(
        hostedInOverlay = hostedInOverlay,

        onDismissRequest = onDismiss,

        title = AppStrings.s(R.string.details_affinity_title),

        body = {

            Text(
                text = AppStrings.s(R.string.details_affinity_question, processName),

                style = MaterialTheme.typography.bodyMedium
            )

            // 竖排单列，可滚动：核多的机器上也不会把对话框撑出屏幕。
            // 「所有处理器」也放在这一列里，和 CPU 0 一样是普通一项，会跟着一起滚。
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                // 「所有处理器」：勾上=全选，取消=全不选；单颗 CPU 的勾选状态反向影响它
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (allSelected) 0L else allMask
                        },

                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Checkbox(
                        checked = allSelected,

                        onCheckedChange = { checked ->
                            selected = if (checked) allMask else 0L
                        }
                    )

                    Text(
                        text = AppStrings.s(R.string.details_affinity_all_processors),

                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                for (cpu in 0 until count) {

                    val bit = 1L shl cpu

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if ((selected and bit) != 0L) {
                                    selected and bit.inv()
                                } else {
                                    selected or bit
                                }
                            },

                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Checkbox(
                            checked = (selected and bit) != 0L,

                            onCheckedChange = { checked ->
                                selected = if (checked) selected or bit else selected and bit.inv()
                            }
                        )

                        Text(
                            text = AppStrings.s(R.string.details_affinity_cpu, cpu),

                            style = MaterialTheme.typography.bodyMedium,

                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        },

        footer = {

            Row(
                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {

                // 确定在前（保持强调色），取消在后
                Button(
                    onClick = { onConfirm(selected) },

                    // 一颗都不选时不允许提交：内核会直接 EINVAL；不额外提示文字
                    enabled = selected != 0L
                ) {
                    Text(AppStrings.s(R.string.common_ok))
                }

                TextButton(onClick = onDismiss) {
                    Text(AppStrings.s(R.string.common_cancel))
                }
            }
        }
    )
}

/** 选项对话框里的一行：左标签，右侧一个勾（表示"当前值"）。 */
@Composable
private fun DetailOptionRow(
    label: String,
    marked: Boolean,
    onClick: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 10.dp),

        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,

            modifier = Modifier.weight(1f),

            style = MaterialTheme.typography.bodyMedium
        )

        if (marked) {

            Text(
                text = "✓",

                style = MaterialTheme.typography.bodyMedium,

                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 亲和性掩码转成"0,1,2"这样的人读列表（超出范围的高位不列）。 */
private fun cpuMaskText(mask: Long, cpuCount: Int): String {

    val cpus = (0 until cpuCount).filter { (mask and (1L shl it)) != 0L }

    return if (cpus.isEmpty()) "—" else cpus.joinToString(",")
}

/**
 * 「分析等待链」：逐线程列出内核状态 / 等待点 / 当前系统调用。
 *
 * Linux 没有 Windows 那种"谁等谁"的等待链遍历，这里给的是每个线程**卡在哪**，
 * 不做等待关系的推断（推断出来的东西不能当事实显示）。
 */
@Composable
private fun DetailWaitChainDialog(
    hostedInOverlay: Boolean,

    processName: String,

    loading: Boolean,

    chain: ProcessWaitChain?,

    onDismiss: () -> Unit
) {

    DetailSurfaceDialog(
        hostedInOverlay = hostedInOverlay,

        onDismissRequest = onDismiss,

        title = AppStrings.s(R.string.details_wait_title),

        body = {

            Text(
                text = processName,

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {

                loading -> Text(
                    text = AppStrings.s(R.string.details_wait_querying),

                    style = MaterialTheme.typography.bodySmall
                )

                chain == null || chain.entries.isEmpty() -> Text(
                    text = AppStrings.s(R.string.details_wait_empty),

                    style = MaterialTheme.typography.bodySmall
                )

                else -> Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {

                    chain.entries.forEach { entry ->

                        Text(
                            text = AppStrings.s(
                                R.string.details_wait_line,
                                entry.tid,
                                entry.state,
                                entry.wchan
                            ),

                            style = MaterialTheme.typography.bodySmall
                        )

                        Text(
                            text = "${entry.comm}  ·  syscall ${entry.syscall}",

                            style = MaterialTheme.typography.labelSmall,

                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (chain.truncated) {

                        Text(
                            text = AppStrings.s(R.string.details_wait_truncated, chain.entries.size),

                            style = MaterialTheme.typography.labelSmall,

                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

/**
 * 「属性」：把这一行的全部列值平铺出来（包括当前没显示的列）。
 *
 * 数据来源不是表格当前那份行：点开时页面会用 pid + 启动时刻单独查一次全字段快照
 * （[IPageDataApi.detailProcessFullRow]），所以用户把某些列关掉也不影响这里。
 *
 * 每一项都可以长按选中复制（[SelectionContainer]，不额外放复制按钮）。
 *
 * @param loading 全字段查询还没回来：先显示"查询中"，不拿不完整的行冒充结果
 */
@Composable
private fun DetailPropertiesDialog(
    hostedInOverlay: Boolean,

    table: DetailTable,

    row: ProcessRow,

    loading: Boolean,

    onDismiss: () -> Unit
) {

    DetailSurfaceDialog(
        hostedInOverlay = hostedInOverlay,

        onDismissRequest = onDismiss,

        title = AppStrings.s(R.string.details_props_title),

        body = {

            Text(
                text = "${row.name}（PID ${row.displayPid}）",

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (loading) {

                Text(
                    text = AppStrings.s(R.string.details_props_loading),

                    style = MaterialTheme.typography.bodyMedium,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

            } else {

                // 整段包在 SelectionContainer 里：长按任意一项即可选中并复制，
                // 不放"复制"按钮，避免 43 行 × 每行一个按钮
                SelectionContainer {

                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {

                        table.allColumns.forEach { column ->

                            val value = row.values[column.id]

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {

                                Text(
                                    text = column.title,

                                    modifier = Modifier.weight(0.4f),

                                    style = MaterialTheme.typography.labelSmall,

                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    text = value?.takeIf { it.isNotBlank() } ?: "—（暂无数据）",

                                    modifier = Modifier.weight(0.6f),

                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

/** 通用"一段文本"对话框：转储结果、所在目录、服务记录都用它。 */
@Composable
private fun DetailTextDialog(
    hostedInOverlay: Boolean,

    title: String,

    body: String,

    onDismiss: () -> Unit,

    /** 可选的第二个动作（例如「复制目录」）；null = 只有确定 */
    secondaryText: String? = null,

    onSecondary: (() -> Unit)? = null
) {

    DetailSurfaceDialog(
        hostedInOverlay = hostedInOverlay,

        onDismissRequest = onDismiss,

        title = title,

        body = {

            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                Text(
                    text = body,

                    style = MaterialTheme.typography.bodySmall
                )
            }
        },

        footer = if (secondaryText != null && onSecondary != null) {

            {
                Row(
                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {

                    TextButton(onClick = onSecondary) {
                        Text(secondaryText)
                    }

                    TextButton(onClick = onDismiss) {
                        Text(AppStrings.s(R.string.common_ok))
                    }
                }
            }

        } else null
    )
}

/**
 * 底部工具条：左下 = 进程范围三档，中间 = AppStrings.s(R.string.details_ignore_interval)，右下 = 「刷新」。
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
 * AppStrings.s(R.string.details_ignore_interval)开关。
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
            text = AppStrings.s(R.string.details_ignore_interval),

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
            text = AppStrings.s(R.string.common_refresh),

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
            text = AppStrings.s(R.string.details_end_process),

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
    onLongPressRow: (ProcessRow) -> Unit,

    /** 用户松开一根列边界后上报最终宽度（服务端落盘）；拖动过程中只改本地预览。 */
    onColumnWidthCommit: (String, Int) -> Unit
) {

    val horizontalState = rememberScrollState()

    val verticalState = rememberScrollState()

    /**
     * 拖动过程中的列宽预览（列 id -> dp）。
     *
     * 为什么页面也要存一份：UIService 落盘是异步的、下一次快照最快也要一个轮询周期才回来，
     * 而手指拖动必须"跟手"——所以拖动期间用本地这份宽度画，松手那一下才把最终值报给服务。
     * 服务下次给的列定义里已经带着存下来的宽度，两者一致；页面重进时这份预览是空的。
     */
    var widthOverrides by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    // 实际用来画表的列：本地预览优先，没拖过的列用快照里的宽度（也就是存档里的宽度）
    val displayColumns = remember(table.columns, widthOverrides) {

        if (widthOverrides.isEmpty()) {
            table.columns
        } else {
            table.columns.map { column ->
                widthOverrides[column.id]?.let { column.copy(width = it) } ?: column
            }
        }
    }

    val displayTable = remember(table, displayColumns) {
        if (displayColumns == table.columns) table else table.copy(columns = displayColumns)
    }

    // 没列可画（用户把所有列都取消勾选，或服务还没起来）时给一句明确的话，别画成空白，
    // 并且这里仍然允许长按表头打开AppStrings.s(R.string.details_select_columns)——否则用户一旦全取消勾选就再也开不回列
    if (table.columns.isEmpty()) {

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = AppStrings.s(R.string.details_no_visible_columns),

                    style = MaterialTheme.typography.bodyMedium,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                Text(
                    text = AppStrings.s(R.string.details_choose_columns_hint),

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
                    table = displayTable,
                    onSortColumn = onSortColumn,
                    onLongPressHeader = onOpenColumnPicker,

                    // 拖动只改本地预览（跟手），松手才把最终宽度报给 UIService 落盘
                    onColumnWidthChange = { columnId, widthDp, finished ->

                        widthOverrides = widthOverrides + (columnId to widthDp)

                        if (finished) onColumnWidthCommit(columnId, widthDp)
                    }
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
                            table = displayTable,
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
 * 点任意一格 = 请求按该列排序；长按任意位置 = 打开AppStrings.s(R.string.details_select_columns)对话框；
 * **按住某一列的右边界往左右拖** = 改左边那一列的宽度（[onColumnWidthChange]）。
 *
 * 手势只挂一个（在整行上），点击位置自己换算成"第几列"：
 * 43 个格子各挂一个 pointerInput 的话，每次按下都要让 43 个检测器参与命中测试，
 * 表一大就明显拖慢手感。列宽是固定 dp，按累计宽度换算最省。
 *
 * 拖宽检测是**第二个** pointerInput：按下点落在边界附近才接管，超过触摸阈值才 consume。
 * 没超过阈值时不 consume，点击检测器照常收到 down/up，所以"点边界"仍然只是排序。
 */
@Composable
private fun DetailHeaderRow(
    table: DetailTable,
    onSortColumn: (String) -> Unit,
    onLongPressHeader: () -> Unit,

    /** 拖动中每帧上报 (列 id, 目标宽度 dp, 是否松手)。 */
    onColumnWidthChange: (String, Int, Boolean) -> Unit
) {

    val density = LocalDensity.current

    // 每一列在表头里的像素区间 [start, end)
    // 起点从图标列右边算起：点在图标列上换算出来的 index 是 -1，不会误触发排序。
    val iconColumnWidthPx = with(density) { DETAIL_ICON_COLUMN_WIDTH.toPx() }

    val columnSpansPx = remember(table.columns, density) {

        var accumulated = iconColumnWidthPx

        table.columns.map { column ->

            val widthPx = with(density) { column.width.dp.toPx() }

            val start = accumulated

            accumulated += widthPx

            start to accumulated
        }
    }

    // 拖动过程中每帧都会改列宽 -> table 是新对象：手势不能拿 table.columns 当 key（会中途重启），
    // 所以边界在按下那一刻从这份"最新值"里算。
    val latestColumns by rememberUpdatedState(table.columns)

    val resizeTouchPx = with(density) { COLUMN_RESIZE_TOUCH_WIDTH.toPx() }

    // 分割线的颜色：表头底色是 surfaceVariant，用 outlineVariant 画一条浅线才看得出来
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)

            // 列边界分割线：既分开两列，也告诉用户"竖线这里就是拖宽把手"
            // 用 drawBehind 画（不占布局宽度），所以跟行里的格子、跟拖动的命中边界是同一条位置
            .drawBehind {

                val stroke = 1.dp.toPx()

                var edge = DETAIL_ICON_COLUMN_WIDTH.toPx()

                table.columns.forEachIndexed { index, column ->

                    edge += column.width.dp.toPx()

                    // 表头右侧可能还有几千像素宽（43 列），只画落在可见区域里的那些
                    if (edge > size.width) return@forEachIndexed

                    // 最后一列不画：右边没有下一列，画上去像是表格被截断了
                    if (index == table.columns.lastIndex) return@forEachIndexed

                    drawLine(
                        color = dividerColor,
                        start = Offset(edge - stroke / 2f, 0f),
                        end = Offset(edge - stroke / 2f, size.height),
                        strokeWidth = stroke
                    )
                }
            }

            // 拖宽（先挂）：只在"按下点落在某列右边界 ±resizeTouchPx"时接管
            .pointerInput(Unit) {

                awaitEachGesture {

                    val down = awaitFirstDown(requireUnconsumed = false)

                    // 命中哪一列的右边界？返回时把"这一列的 id + 起始宽度"一起带出来
                    var hitId: String? = null
                    var hitStartWidth = 0
                    var boundary = iconColumnWidthPx

                    for (column in latestColumns) {

                        boundary += column.width.dp.toPx()

                        if (abs(down.position.x - boundary) <= resizeTouchPx) {

                            hitId = column.id
                            hitStartWidth = column.width

                            break
                        }
                    }

                    val columnId = hitId ?: return@awaitEachGesture

                    var accumulatedDx = 0f
                    var dragging = false
                    var width = hitStartWidth

                    while (true) {

                        val event = awaitPointerEvent()

                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (change.changedToUpIgnoreConsumed()) break

                        accumulatedDx += change.positionChange().x

                        if (!dragging && abs(accumulatedDx) > viewConfiguration.touchSlop) dragging = true

                        if (dragging) {

                            width = (hitStartWidth + accumulatedDx / density.density)
                                .toInt()
                                .coerceIn(COLUMN_MIN_WIDTH_DP, COLUMN_MAX_WIDTH_DP)

                            onColumnWidthChange(columnId, width, false)

                            // 吃掉位移：横滚容器与点击检测器都不再处理这一段
                            change.consume()
                        }
                    }

                    if (dragging) onColumnWidthChange(columnId, width, true)
                }
            }

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

        // 图标列的表头：留空。这一列没有标题，也不参与排序，
        // 只用来跟下面每一行的图标槽对齐（宽度和行里的那一格完全一致）。
        Box(
            modifier = Modifier
                .width(DETAIL_ICON_COLUMN_WIDTH)
                .padding(vertical = 10.dp)
        )

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

    // 图标按**包名**取一次、之后走缓存：同一个包的多个进程共用一张图，刷新也不会重新解码。
    // 只有 Android 框架进程带包名，Linux 原生进程没有图标来源，空槽照旧。
    val packageName = row.packageName

    var icon by remember(packageName) {
        mutableStateOf(packageName?.let { ProcessIconCache.cached(it) })
    }

    LaunchedEffect(packageName) {

        if (packageName != null && !ProcessIconCache.isKnown(packageName)) {

            icon = withContext(Dispatchers.IO) { ProcessIconCache.load(packageName) }
        }
    }

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

        // 图标列：在名称（以及所有列）之前的第一个格子。
        // Android 进程按包名取应用图标画在槽里；Linux 原生进程没有包名，只画空槽。
        Box(
            modifier = Modifier
                .width(DETAIL_ICON_COLUMN_WIDTH)
                .padding(vertical = 8.dp),

            contentAlignment = Alignment.Center
        ) {

            DetailProcessIconSlot(selected = isSelected, icon = icon)
        }

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
 * 进程图标的槽。
 *
 * 有图标时把图标画在定尺 [DETAIL_ICON_SIZE] 的方框里（圆角裁切，与桌面图标观感一致）；
 * 没图标（Linux 原生进程 / 不可见包取不到图）时只画一个带细边框的空槽，
 * 这样行高不会因为有没有图而抖动，用户也能看出"这里本该有图标"。
 * 选中行时边框换主色，跟整行的高亮一起看。
 */
@Composable
private fun DetailProcessIconSlot(selected: Boolean, icon: ImageBitmap?) {

    if (icon != null) {

        Image(
            bitmap = icon,

            contentDescription = null,

            modifier = Modifier
                .size(DETAIL_ICON_SIZE)
                .clip(RoundedCornerShape(4.dp))
        )

        return
    }

    val outline = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = Modifier
            .size(DETAIL_ICON_SIZE)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = outline,
                shape = RoundedCornerShape(4.dp)
            )
    )
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
 * - 勾选状态先改本地草稿（draft），点AppStrings.s(R.string.common_ok)才生效——避免边勾边抖动表头；
 * - 点AppStrings.s(R.string.common_ok)：把整份勾选集合报给 UIService（setDetailVisibleColumns），页面立刻刷新一次快照；
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
                text = AppStrings.s(R.string.details_select_columns)
            )
        },

        text = {

            Column {

                // 小标题
                Text(
                    text = AppStrings.s(R.string.details_select_columns_desc),

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                if (allColumns.isEmpty()) {

                    Text(
                        text = AppStrings.s(R.string.details_columns_not_ready),

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
                    text = AppStrings.s(R.string.common_ok)
                )
            }
        }
    )
}
