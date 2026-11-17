/**
 * 性能页 —— 硬件切换列（左）+ 当前硬件详情（右）
 * ============================================================
 *
 * 版式（按需求的"左 1 : 右 4"）：
 *
 *   宽屏（横屏 / 宽浮窗，>= 520.dp）
 *   ┌──────────┬─────────────────────────────────────┐
 *   │ ▁▁ CPU   │  CPU                          NAME │
 *   │ 0% 0GHz  │  60 秒内的利用率 %          100%  │
 *   ├──────────┤  ┌───────────────────────────────┐  │
 *   │ ▁▁ 内存  │  │ 历史折线（大图表）             │  │
 *   ├──────────┤  └───────────────────────────────┘  │
 *   │ ▁▁ 磁盘  │  统计块 │ 明细块 │ 预留            │
 *   ├──────────┤                                     │
 *   │ ▁▁ WLAN  │                                     │
 *   ├──────────┤                                     │
 *   │ ▁▁ GPU   │                                     │
 *   └──────────┴─────────────────────────────────────┘
 *
 *   窄屏（竖屏手机 / 窄浮窗，< 520.dp）
 *   ┌─────────────────────────────────────────────────┐
 *   │  名称行 / 窗口文案行                             │
 *   │  ┌───────────────────────────────────────────┐  │
 *   │  │            图表（吃 4 份高度）              │  │
 *   │  └───────────────────────────────────────────┘  │
 *   │  ░ 统计数据面板：统计块(左) │ 明细块(右) ░      │
 *   │                                                 │  ← 主界面竖屏才有这 1 份空白
 *   ├─────────────────────────────────────────────────┤
 *   │  CPU │ 内存 │ 磁盘 │ WLAN │ GPU   ← 只文字      │
 *   └─────────────────────────────────────────────────┘
 *
 * 窄屏细节：图表与底部空白按 4:1 分高度（空白正好是图表的 1/4）；
 * 统计块与明细块并排套一层数据面板背景（图表不套），统计块贴左、明细块贴右。
 * 浮窗不加底部空白（本来就小，高度全给图表），其余与竖屏一致。
 *
 * 窄屏为什么把选择区挪到底部：竖屏缺的是横向空间，左右分栏后详情只剩 200 多 dp，
 * 统计块三块并排根本放不下。挪到底部后详情吃满整行宽度，代价是纵向少约 46.dp。
 * 底部条**只显示硬件名**，不画迷你图和数据小字 —— 这个尺寸下它们读不出信息，只是噪音。
 * 浮窗同理：横着拉宽（>= 520.dp）才是上面那套左右栏，竖着窄就用底部条。
 *
 * 左栏每一行 = 一个硬件入口：
 * - 左边是迷你趋势图（高度跟着这一行的两行文字走，所以看起来"图表和文字一样高"）；
 * - 右边上面是**硬件名大字**，下面是**数据小字**（占用率 + 频率/速度）。
 * - 点一行 = 右区换成这个硬件；选中行用主色容器着色，未选中用透明底。
 *
 * 宽度策略：宽屏严格 1:4；窄屏（< 520.dp）换成"详情在上、硬件条在下"的竖向结构，
 * 硬件条一行五个等宽文字块，不再显示迷你图和数据小字。
 *
 * 数据现状：**只有 CPU 那一组接了真实数据**，来源是
 *   ELF 设备快照（typeCode=5）-> LinuxService.deviceSnapshot()
 *   -> UIService 清洗成 PerformanceSnapshot -> IPageDataApi.performanceSnapshot()。
 * 其余四个硬件（内存 / 磁盘 / WLAN / GPU）仍是写死的 0 值占位，等各自的采集实现。
 *
 * 图表窗口那一行的文案由 UIService 按"实时更新速度"换算（高 30 / 常规 60 / 低 240 秒，
 * 已暂停时整段换成 利用率 % (已暂停)）；页面只在切进来时取一次，之后跟着帧一起显示。
 *
 * 说明：文件位于 ui 目录，但包名为 com.android.taskmanager，与 MainActivity 同包。
 */
package com.android.taskmanager

import android.content.ClipData
import android.content.Context

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.android.taskmanager.data.CpuUsagePoint
import com.android.taskmanager.data.PerformanceSnapshot
import com.android.taskmanager.service.UIService
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/** 页面实现状态：改成 IMPLEMENTED 后，MainActivity 的 PageContent() 才会渲染本页。 */
val PerformancePageState: PageState = PageState.IMPLEMENTED

/** 宽窄屏分界：与项目其它页保持一致（520.dp）。 */
private val PERFORMANCE_WIDE_MIN_WIDTH = 520.dp

/**
 * 图表一屏有多少个横向槽位（照 Windows 任务管理器那张 CPU 图，取 60 个：
 * 以常规速度 1 秒一个点算，刚好覆盖 60 秒）。
 *
 * 槽位数是**固定的**：三个更新档位共用同一组槽位，点数不满时曲线贴右边生长，
 * 满了之后新点从右进、老点从左出。换档位只改变新点进来的快慢，
 * 图的宽度、槽位数、已经采到的曲线都不变。缓存上限在服务侧（CPU_HISTORY_MAX_POINTS）。
 */
private const val PERFORMANCE_CHART_POINTS = 60

/**
 * 图表菜单"复制"的兜底文本：只有 CPU 已经有真实内容（见 [cpuClipboardText]），
 * 内存 / 磁盘 / 网络 / GPU 的复制块等各自的数据齐了再补，现在统一回落到这里。
 *
 * 保留这段占位是为了让菜单项在任何硬件页上都可点、可验证剪贴板通路；
 * 新设备接入时不要改它，而是像 CPU 一样在 [clipboardTextFor] 里加分支。
 */
private const val CHART_MENU_COPY_TEXT = "测试内容"

/** 内核图形口径：整颗处理器的总利用率 / 逐个逻辑处理器。 */
private const val CPU_KERNEL_GRAPH_TOTAL = "TOTAL"
private const val CPU_KERNEL_GRAPH_LOGICAL = "LOGICAL"

/** 窄屏底部硬件条的单项高度：一行文字，按压目标够大就行。 */
private val PERFORMANCE_COMPACT_TAB_HEIGHT = 38.dp

/** 图表时间窗口（秒）。CPU 页用服务给的值，其余四个硬件暂时沿用这个测试值。 */
private const val TEST_CHART_WINDOW_SECONDS = 60

/** 硬件名称占位：CPU 用服务给的处理器商品名，取不到才回落它。 */
private const val TEST_HARDWARE_NAME = "NAME"

/**
 * 左栏的五个硬件入口。
 *
 * 显示名按需求写（CPU / 内存 / 磁盘 / WLAN / GPU）；
 * [testPercent] / [testDetail] / [testName] 是**其余四个硬件写死的测试数据，全部 0**；
 * CPU 那一项不用它们，改由服务给的 PerformanceSnapshot 提供。
 */
private enum class HardwareKind(
    val displayName: String,
    val testName: String,
    val testPercent: String,
    val testDetail: String
) {
    CPU("CPU", TEST_HARDWARE_NAME, "0%", "0.00 GHz"),
    MEMORY("内存", TEST_HARDWARE_NAME, "0%", "0.00 / 0.00 GB"),
    DISK("磁盘", TEST_HARDWARE_NAME, "0%", "0.00 MB/s"),
    WLAN("WLAN", TEST_HARDWARE_NAME, "0%", "0.00 Mbps"),
    GPU("GPU", TEST_HARDWARE_NAME, "0%", "0.00 MHz")
}

/**
 * 右区下半部"统计区"左上角的一个指标：小字标题 + 标题下的大字数据。
 *
 * 例：标题「利用率」/ 数据「0%」。
 *
 * [emphasis] = true 表示这个值是块里的主数据，用更大的字号呈现；第一行（利用率 / 速度）就是这种。
 *
 * [wideWidth] / [narrowWidth] 是这一格的**最小宽度**（宽屏 / 窄屏各一套）。
 * 数值文本每隔一秒都在变（0% → 35%，1.00 GHz → 2.40 GHz），如果格子宽度跟着内容走，
 * 整块就会一直抽动；给出一个覆盖最长取值的下限后，格子宽度就钉死了，只有文字在变。
 */
private data class CpuStat(
    val label: String,
    val value: String,
    val emphasis: Boolean = false,
    val wideWidth: Dp = Dp.Unspecified,
    val narrowWidth: Dp = Dp.Unspecified
)

/**
 * 统计区最左块的三行指标：由一帧数据现算，不写死。
 *
 * 行内是横排的若干指标，行与行之间竖排：
 * 1) 利用率 / 速度（这两个是主数据，字号最大）
 * 2) 进程 / 线程 / 文件描述符
 * 3) 正常运行时间
 *
 * 某一格没数据时，对应文案是破折号 —— 不要用 0 冒充，0% 与 读不到 是两件事。
 */
private fun cpuSummaryRows(
    snapshot: PerformanceSnapshot,
    usageDecimals: Int = 0
): List<List<CpuStat>> = listOf(
    listOf(
        CpuStat(
            "利用率", snapshot.cpuUsageText(usageDecimals), emphasis = true,
            wideWidth = 66.dp, narrowWidth = 54.dp
        ),
        CpuStat(
            "速度", snapshot.cpuSpeedText(), emphasis = true,
            wideWidth = 122.dp, narrowWidth = 98.dp
        )
    ),

    listOf(
        CpuStat("进程", snapshot.processCountText(), wideWidth = 46.dp, narrowWidth = 46.dp),
        CpuStat("线程", snapshot.threadCountText(), wideWidth = 46.dp, narrowWidth = 46.dp),
        CpuStat(
            "文件描述符", snapshot.fdCountText(),
            wideWidth = 62.dp, narrowWidth = 46.dp
        )
    ),

    listOf(
        CpuStat("正常运行时间", snapshot.uptimeText(), wideWidth = 92.dp, narrowWidth = 92.dp)
    )
)

/**
 * 统计区中间块的竖排明细（格式统一为「内容: 数据」，全部小字）。
 *
 * 接口字段与 ELF 侧一一对应：处理器商品名与虚拟化方案由 ELF 后续实现，
 * 现在前者显示占位、后者显示"不支持"，不会被当成 0。
 */
private fun cpuDetailLines(snapshot: PerformanceSnapshot): List<Pair<String, String>> = listOf(
    "基准速度" to snapshot.cpuBaseText(),
    "插槽" to snapshot.socketsText(),
    "内核" to snapshot.coresText(),
    "逻辑处理器" to snapshot.logicalCpusText(),
    "虚拟化" to snapshot.virtualizationText(),
    "L1 缓存" to snapshot.l1Text(),
    "L2 缓存" to snapshot.l2Text(),
    "L3 缓存" to snapshot.l3Text()
)

/**
 * 图表菜单"复制"到底往剪贴板写什么：按当前硬件分发。
 *
 * 现在只有 CPU 有真实内容。其它四种设备照菜单结构也应该有各自的一整块文本，
 * 但它们连采集字段都还没接完，先回落到 [CHART_MENU_COPY_TEXT]。
 *
 * 接入新设备时在这里加分支即可，菜单与剪贴板调用处都不用动。
 */
private fun clipboardTextFor(
    kind: HardwareKind,
    snapshot: PerformanceSnapshot,
    usageDecimals: Int
): String = when (kind) {

    HardwareKind.CPU -> cpuClipboardText(snapshot, usageDecimals)

    // TODO 内存 / 磁盘 / WLAN / GPU 的复制文本：等各自的实例数据能进快照后逐块补上
    else -> CHART_MENU_COPY_TEXT
}

/**
 * CPU 视图"复制"写进剪贴板的文本。
 *
 * 形状照 Windows 任务管理器复制出来的那一份（用户会把它粘到记事本或工单里，
 * 空行与缩进都属于格式的一部分，改动前先对照一遍）：
 *
 * CPU                      设备名，顶格
 * （空行）
 * \t处理器商品名            例：11th Gen Intel(R) Core(TM) i5-11400H @ 2.70GHz
 * （空行）
 * \t基准速度:\t2.69 GHz     规格块：字段名后面带冒号，字段名与值之间是 Tab
 * \t插槽:\t1
 * ...
 * （空行）
 * \t利用率\t9%              实时块：字段名后面不带冒号
 * ...
 * （末尾空行）
 *
 * 每个值都从当前这一帧 [snapshot] 取，换算规则与页面显示完全一致（同一套 xxxText()）；
 * 读不到的字段照页面一样写破折号，不编造 0。
 *
 * @param usageDecimals 利用率保留几位小数，跟页面当前精度走（默认 0，即 9%）
 */
private fun cpuClipboardText(snapshot: PerformanceSnapshot, usageDecimals: Int): String {

    // 商品名还没拿到时用占位名：比留一行空白更像"这里有东西，只是没读到"
    val name = snapshot.cpuName?.takeIf { it.isNotBlank() } ?: TEST_HARDWARE_NAME

    return buildString {

        append("CPU")

        append("\n\n\t").append(name)

        // 规格块：静态信息，开机之后基本不变
        append("\n\n\t基准速度:\t").append(snapshot.cpuBaseText())
        append("\n\t插槽:\t").append(snapshot.socketsText())
        append("\n\t内核:\t").append(snapshot.coresText())
        append("\n\t逻辑处理器:\t").append(snapshot.logicalCpusText())
        append("\n\t虚拟化:\t").append(snapshot.virtualizationText())
        append("\n\tL1 缓存:\t").append(snapshot.l1Text())
        append("\n\tL2 缓存:\t").append(snapshot.l2Text())
        append("\n\tL3 缓存:\t").append(snapshot.l3Text())

        // 实时块：每秒都在变，粘出去就是"复制那一刻"的值
        append("\n\n\t利用率\t").append(snapshot.cpuUsageText(usageDecimals))
        append("\n\t速度\t").append(snapshot.cpuSpeedText())
        append("\n\t正常运行时间\t").append(snapshot.uptimeText())
        append("\n\t进程\t").append(snapshot.processCountText())
        append("\n\t线程\t").append(snapshot.threadCountText())

        // 与页面统计区同名：这一项在 Linux 上是文件描述符，不套用任务管理器的"句柄"
        append("\n\t文件描述符\t").append(snapshot.fdCountText())

        append("\n\n")
    }
}

/**
 * 性能页面入口。
 *
 * @param hostedInOverlay 是否被浮窗承载：影响对话框的实现方式（浮窗没有 Activity 令牌，
 *                        不能用 AlertDialog，必须在窗口内自绘），版式本身不受影响。
 */
@Composable
fun PerformanceScreen(hostedInOverlay: Boolean = false) {

    // 默认选 CPU；rememberSaveable 让切页回来还停在原来那一项
    var selectedName by rememberSaveable { mutableStateOf(HardwareKind.CPU.name) }

    val selected = HardwareKind.entries.firstOrNull { it.name == selectedName } ?: HardwareKind.CPU

    // 同进程内直连服务；服务还没起来时给 null，页面按空帧显示
    val service = UIService.instanceOrNull()

    // 图表窗口文案：只在切进本页时取一次。用户改"实时更新速度"必须先回设置页，
    // 再切回来时这次读取自然拿到新值 —— 不需要每帧都去问设置。
    // 只有这段文字跟着档位变；图本身（点数、宽度、布局）三个档位完全相同。
    var windowLabel by remember { mutableStateOf("$TEST_CHART_WINDOW_SECONDS 秒内的利用率 %") }

    // 利用率显示位数：读设置页的"利用率精确度"（默认 0 位 / 一位 1 位 / 两位 2 位）。
    // 同样在切进本页时取一次 —— 要改它必须先回设置页，回来自然读到新值。
    // 它只影响这几个字怎么画，不影响采集精度，也不影响图表曲线（曲线一直按浮点值画）。
    var usageDecimals by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {

        windowLabel = service?.performanceChartWindowLabel() ?: windowLabel

        usageDecimals = SettingsCatalog.cpuUsageDecimals(
            service?.settings()?.cpuUsagePrecision ?: SettingsDefaults.cpuUsagePrecision
        )

        // 立刻补采一帧：后台可能刚采过，但"切进来的第一屏"不该等一个周期
        service?.refreshPerformanceNow()
    }

    // 读可观察状态：服务每采到一帧就会让本页重组一次
    val snapshot = service?.performanceSnapshot() ?: PerformanceSnapshot.EMPTY

    // 历史点：大图与左列迷你图共用这一份，只有取哪些点、画在哪里不同
    val history = service?.performanceCpuHistory() ?: emptyList()

    // 长按图表弹出的菜单
    var chartDialogVisible by remember { mutableStateOf(false) }

    // 菜单里的两项画图偏好（图表口径、是否显示内核时间）**不放在本页内部**：
    // 只在页面里存，竖屏 / 横屏 / 悬浮窗三个宿主各有一份，图形摘要视图又是另一个
    // 宿主渲染的，结果就是"换一个地方就得重新点一遍"。统一走 ChartViewState，
    // 三处宿主与摘要视图读同一份值，改动立刻互相可见，并且落盘。
    val context = LocalContext.current

    // 首次进本页把落盘值读进来；已读过就是空操作（不再产生 IO）
    remember(context) {
        ChartViewState.ensureLoaded(context.applicationContext)
    }

    // 图表口径（true = 逻辑处理器，false = 总体利用率）与内核时间开关
    val logicalGraph = ChartViewState.logicalGraph
    val showKernelTime = ChartViewState.showKernelTime

    // 双击图表切进摘要视图。
    // 只认 CPU：其余四个硬件现在没有真实曲线，在它们上面双击会弹出一张 CPU 图，容易误解。
    val enterChartSummary: () -> Unit = {
        if (selected == HardwareKind.CPU) {
            ChartSummaryView.active.value = true
        }
    }

    // 图形摘要视图：整页只剩这一页的头部（名称行 / 标签行）与图表，图表吃满其余高度。
    //
    // 谁让位：主界面的导航栏与标题栏由 MainPage 让出，浮窗的标题栏与页签由 OverlayPageFrame 让出；
    // 本页内部则不再画左栏硬件列表（横屏）与底部硬件条（竖屏 / 浮窗），
    // 也不再画图表下面的统计块、明细块与预留区。
    if (ChartSummaryView.active.value) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {

            Text(
                text = "双击图表退出图形摘要视图",

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            // wide 传 true：摘要里没有下方那两块，这个值只影响它们怎么排，传哪个都不影响结果；
            // 传 true 是为了让名称行与标签行按"整行铺开"的排法走。
            HardwareDetail(
                kind = HardwareKind.CPU,

                snapshot = snapshot,

                history = history,

                windowLabel = windowLabel,

                wide = true,

                hostedInOverlay = hostedInOverlay,

                usageDecimals = usageDecimals,

                // 摘要里长按不弹菜单：这里只有一张图，弹出来也没处可用
                onChartLongPress = {},

                onChartDoubleTap = { ChartSummaryView.active.value = false },

                // 摘要沿用进入前选定的两项：图还是那张图，只是铺满整页。
                // 值是进程级共享的，所以从浮窗双击进来也是逐核 / 带内核时间的那一版。
                showKernelTime = showKernelTime,

                logicalGraph = logicalGraph,

                summary = true,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        return
    }

    // 本页自己管左右两块的高度分配，所以 PageShell 关掉整页纵向滚动（否则 Row 的高度会被撑成无限）
    PageShell(scrollable = false) {

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {

            val wide = maxWidth >= PERFORMANCE_WIDE_MIN_WIDTH

            if (wide) {

                // 横屏 / 宽浮窗：左边硬件列（带迷你图与数据小字）+ 右边详情，保持 1:4
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {

                    Column(
                        modifier = Modifier.weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),

                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {

                        HardwareKind.entries.forEach { kind ->

                            val isCpu = kind == HardwareKind.CPU

                            HardwareTab(
                                kind = kind,

                                percentText = if (isCpu) {
                                    snapshot.cpuUsageText(usageDecimals)
                                } else {
                                    kind.testPercent
                                },

                                detailText = if (isCpu) snapshot.cpuSpeedText() else kind.testDetail,

                                // 迷你图取的是同一份历史点的尾部，只是画得小
                                history = if (isCpu) history else emptyList(),

                                selected = kind == selected,

                                onClick = { selectedName = kind.name }
                            )
                        }
                    }

                    Spacer(
                        modifier = Modifier.width(16.dp)
                    )

                    HardwareDetail(
                        kind = selected,

                        snapshot = snapshot,

                        history = if (selected == HardwareKind.CPU) history else emptyList(),

                        windowLabel = windowLabel,

                        wide = true,

                        hostedInOverlay = hostedInOverlay,

                        usageDecimals = usageDecimals,

                        onChartLongPress = { chartDialogVisible = true },

                        onChartDoubleTap = enterChartSummary,

                        showKernelTime = showKernelTime,

                        logicalGraph = logicalGraph,

                        modifier = Modifier.weight(4f)
                            .fillMaxHeight()
                    )
                }
            } else {

                // 竖屏 / 窄浮窗：空间不够左右分栏，就把选择区挪到底部横排，
                // 这里只放文字（不带迷你图与小字），把横向宽度全留给详情
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {

                    HardwareDetail(
                        kind = selected,

                        snapshot = snapshot,

                        history = if (selected == HardwareKind.CPU) history else emptyList(),

                        windowLabel = windowLabel,

                        wide = false,

                        hostedInOverlay = hostedInOverlay,

                        usageDecimals = usageDecimals,

                        onChartLongPress = { chartDialogVisible = true },

                        onChartDoubleTap = enterChartSummary,

                        showKernelTime = showKernelTime,

                        logicalGraph = logicalGraph,

                        modifier = Modifier.weight(1f)
                            .fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    HardwareCompactBar(
                        selected = selected,

                        onSelect = { selectedName = it.name }
                    )
                }
            }
        }
    }

    if (chartDialogVisible) {

        PerformanceChartDialog(
            hostedInOverlay = hostedInOverlay,

            // 菜单读的是全应用唯一那份画图偏好，改一处三处宿主与摘要视图同时变色
            kernelGraphMode = if (logicalGraph) CPU_KERNEL_GRAPH_LOGICAL else CPU_KERNEL_GRAPH_TOTAL,

            showKernelTime = showKernelTime,

            onKernelGraphModeChange = { mode ->
                ChartViewState.setLogicalGraph(context, mode == CPU_KERNEL_GRAPH_LOGICAL)
            },

            onShowKernelTimeChange = { enabled ->
                ChartViewState.setShowKernelTime(context, enabled)
            },

            onEnterSummary = {
                chartDialogVisible = false

                ChartSummaryView.active.value = true
            },

            // 复制内容按当前选中的硬件现算：CPU 是那份完整文本，其余四种暂时还是占位
            copyText = clipboardTextFor(kind = selected, snapshot = snapshot, usageDecimals = usageDecimals),

            onDismiss = { chartDialogVisible = false }
        )
    }
}

/** 利用率换算成 0..1 的绘制比例；没有数据就贴底。 */
private fun usageFraction(snapshot: PerformanceSnapshot): Float =
    (snapshot.cpuUsagePercent ?: 0f).coerceIn(0f, 100f) / 100f

/**
 * 其余四个硬件的统计块占位：结构与 CPU 相同，但各格都是"暂无数据"。
 *
 * 它们各自的采集实现落地后，把这两个函数换成各自的数据源即可，版式一行都不用改。
 */
private fun emptyCpuSummaryRows(): List<List<CpuStat>> = cpuSummaryRows(PerformanceSnapshot.EMPTY)

private fun emptyCpuDetailLines(): List<Pair<String, String>> = cpuDetailLines(PerformanceSnapshot.EMPTY)

/**
 * 左栏的一行：迷你趋势图 + 硬件名大字 + 数据小字。
 *
 * 图形高度只是视觉上"贴着两行字"，所以这里不跟着字体缩放去做精确测量，
 * 固定 22.dp 就已经和 titleMedium + bodySmall 的两行高度相当。
 */
@Composable
private fun HardwareTab(
    kind: HardwareKind,
    percentText: String,
    detailText: String,
    history: List<CpuUsagePoint>,
    selected: Boolean,
    onClick: () -> Unit
) {

    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    }

    val titleColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val detailColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),

        shape = RoundedCornerShape(8.dp),

        color = container
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),

            verticalAlignment = Alignment.CenterVertically
        ) {

            MiniSparkline(
                selected = selected,

                history = history,

                modifier = Modifier.size(width = 34.dp, height = 22.dp)
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Column(
                modifier = Modifier.weight(1f),

                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {

                Text(
                    text = kind.displayName,

                    style = MaterialTheme.typography.titleMedium,

                    fontWeight = FontWeight.Bold,

                    color = titleColor,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "$percentText $detailText",

                    style = MaterialTheme.typography.bodySmall,

                    fontSize = 10.sp,

                    color = detailColor,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 迷你趋势图：与大图同一份历史、同一组横向槽位，只是画布更小。
 *
 * 为什么要跟大图共用槽位：小图是"这一段统计范围"的缩略，范围必须和大图完全一致。
 * 早先它自己取尾部十几个点，低更新速度下只覆盖几十秒，而大图覆盖几分钟 ——
 * 两张图说的不是同一段时间，换硬件时看起来就像数据对不上。
 *
 * 因此这里照抄大图的三条几何：
 * 1) 只画最后 [PERFORMANCE_CHART_POINTS] 个点，多的老点丢掉；
 * 2) 相邻两点落在相邻槽位，横向比例与大图逐槽对应（小图只是整体缩小）；
 * 3) 点数不满时最老的点落在第 (总槽位 - 点数) 个槽上，曲线贴右边生长。
 * 纵向同样是 0..100% 固定比例，所以同一点在两张图里的高低位置比例一致。
 *
 * 视觉上仍与小图原有外观一致：圆角底衬、无网格、细线；差别只在取值范围统一了。
 *
 * 这里只画总利用率一条线，不跟大图的内核时间 / 逻辑处理器口径走：小图 34dp 宽，
 * 再叠内核色带或拆成每核一格就什么都看不清了。它要表达的是"和大图看的是同一段
 * 时间里的同一条总量曲线"，细节留给大图。
 */
@Composable
private fun MiniSparkline(
    selected: Boolean,
    history: List<CpuUsagePoint>,
    modifier: Modifier = Modifier
) {

    val lineColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }

    val trackColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.20f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    // 与大图同一刀切法：多出来的老点丢掉，保证两张图覆盖同一段时间
    val visible = remember(history) {
        if (history.size > PERFORMANCE_CHART_POINTS) {
            history.takeLast(PERFORMANCE_CHART_POINTS)
        } else {
            history
        }
    }

    Canvas(modifier = modifier) {

        drawRoundRect(
            color = trackColor,

            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )

        val inset = 2.dp.toPx()

        val usable = size.height - inset * 2

        /** 百分比 -> 画布纵坐标：0% 贴底，100% 贴顶（与大图同一公式）。 */
        fun yOf(percent: Float): Float =
            size.height - inset - usable * (percent.coerceIn(0f, 100f) / 100f)

        // 槽宽与大图同比例：59 段正好把宽度分完（0 号贴左、59 号贴右）
        val slotWidth = size.width / (PERFORMANCE_CHART_POINTS - 1).toFloat()

        if (visible.size < 2) {

            // 还没攒够点：贴右边缘点一个点，不拉横贯全宽的线
            val fraction = (visible.lastOrNull()?.percent ?: 0f).coerceIn(0f, 100f)

            drawCircle(
                color = lineColor,

                radius = 1.5.dp.toPx(),

                center = Offset(size.width - 2.dp.toPx(), yOf(fraction))
            )

            return@Canvas
        }

        // 点数不满时，最老的点落在第 (总槽位 - 点数) 个槽上，左边空着等后面长满
        val startSlot = PERFORMANCE_CHART_POINTS - visible.size

        val path = Path()

        visible.forEachIndexed { index, point ->

            val x = (startSlot + index) * slotWidth

            val y = yOf(point.percent)

            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,

            color = lineColor,

            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.5.dp.toPx(),

                cap = StrokeCap.Round,

                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

/**
 * 右区：当前硬件的详情。
 *
 * 版面从上到下：
 * 1) 一行：硬件名大字（CPU）在左，硬件名称（现在填占位 NAME）右对齐贴在本区右边缘；
 * 2) 图表标签行：左「60 秒内的利用率 %」，右「100%」——右上的 100% 对应图表最上面那条线；
 * 3) 趋势图（weight(1f) 吃掉剩余高度，窗口拉高拉矮自己伸缩）；
 * 4) 图表下面再分三块：左统计块（利用率/速度、进程/线程/文件描述符、正常运行时间）、
 *    中明细列表（基准速度、插槽、内核、逻辑处理器、虚拟化、L1~L3）、右空白预留区。
 *    预留区不吃固定宽度，靠 weight 吃"前两块排完剩下的地方"，所以前两块宽度一变它就跟着挪。
 *
 * 原来这里有一个很大的「0%」，现在删掉了 —— 占用率只在下面统计块的「利用率」里出现，
 * 那块的字号已经放大到和原来那个 0% 一个量级，避免同一块数据在画面上出现两遍。
 *
 * 窄屏（`wide = false`）时三块横排会挤到字都放不下，改成竖排：左统计块在上、
 * 明细列表在下、预留区收缩为 0（它本来就只是"剩余空间"）。
 */
@Composable
private fun HardwareDetail(
    kind: HardwareKind,
    snapshot: PerformanceSnapshot,
    history: List<CpuUsagePoint>,
    windowLabel: String,
    wide: Boolean,
    hostedInOverlay: Boolean,
    usageDecimals: Int,
    onChartLongPress: () -> Unit,
    onChartDoubleTap: () -> Unit,
    showKernelTime: Boolean = false,
    logicalGraph: Boolean = false,
    summary: Boolean = false,
    modifier: Modifier = Modifier
) {

    val isCpu = kind == HardwareKind.CPU

    // 主界面竖屏：图表下方留出呼吸空间（图表 3 : 留白 1，即图表再缩 1/4 后的比例）。
    // 浮窗不留：窗本来就小，把高度全给图表才好看。
    val reserveBottom = !wide && !hostedInOverlay

    // 有留白时图表 weight 3、留白 weight 1（图表再缩 1/4）；其余情况图表吃满
    val chartWeight = if (reserveBottom) 3f else 1f

    // 名称：CPU 用 ELF 给的处理器商品名（由用户侧补实现），取不到才回落占位
    val nameText = if (isCpu) (snapshot.cpuName ?: TEST_HARDWARE_NAME) else kind.testName

    // 窗口文案：CPU 跟着实时更新速度走，其余硬件先把测试文案摆着
    val chartLabel = if (isCpu) windowLabel else "$TEST_CHART_WINDOW_SECONDS 秒内的利用率 %"

    Column(
        modifier = modifier
    ) {
        // 1) 硬件名 + 右侧的硬件名称（名字贴右边缘，和左边的大字分开）
        Row(
            modifier = Modifier.fillMaxWidth(),

            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = kind.displayName,

                style = MaterialTheme.typography.headlineSmall,

                fontWeight = FontWeight.Bold,

                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text(
                text = nameText,

                modifier = Modifier.weight(1f),

                style = MaterialTheme.typography.bodyMedium,

                color = MaterialTheme.colorScheme.onSurfaceVariant,

                textAlign = TextAlign.End,

                maxLines = 1,

                overflow = TextOverflow.Ellipsis
            )
        }

        // 2) 图表标签行：左侧说明窗口长度，右侧标出 100% 的位置（图表最上面那条线）
        Row(
            modifier = Modifier.fillMaxWidth(),

            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = chartLabel,

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "100%",

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(
            modifier = Modifier.height(4.dp)
        )

        // 3) 趋势图（固定点数铺满全宽，换更新速度不换图）
        //    宽度决定它吃多少高度：宽屏 1 份、竖屏 3 份（留 1 份给底部空白）、浮窗吃满；
        //    摘要视图里下面没有别的块，它直接吃掉整个剩余高度。
        DetailChartPlaceholder(
            points = history,

            fallbackFraction = if (isCpu) usageFraction(snapshot) else 0f,

            // 菜单两项直接决定画法：没勾"显示内核时间"就一行都不多画；
            // 图形口径选了"逻辑处理器"才切成逐核网格，两种口径共用同一份历史。
            showKernelTime = showKernelTime,

            logicalGraph = logicalGraph,

            onLongPress = onChartLongPress,

            onDoubleTap = onChartDoubleTap,

            modifier = Modifier
                .fillMaxWidth()
                .weight(if (summary) 1f else chartWeight)
        )

        // 摘要视图到此为止：统计块 / 明细块 / 底部留白都不画，
        // 名称行与标签行留着，所以画面上仍然是这一页的头部，只是下面全部让给图。
        if (summary) {
            return
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        // 4) 图表下面的两块
        //    横屏：统计 / 明细 / 预留，仍然三栏，只是统计那一栏略加宽（值文本才不会被省略号吃掉）
        //    窄屏与浮窗：统计 / 明细 并排、**各自一块背景板**，都左对齐，明细紧挨着统计块
        if (wide) {

            Row(
                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // 统计块与明细块都按内容取宽：明细紧挨在统计块右边，
                // 谁也不会把对方推远，剩下的宽度全部给末尾的空白预留区
                CpuSummaryBlock(
                    rows = if (isCpu) cpuSummaryRows(snapshot, usageDecimals) else emptyCpuSummaryRows(),

                    wide = true
                )

                CpuDetailList(
                    lines = if (isCpu) cpuDetailLines(snapshot) else emptyCpuDetailLines()
                )

                // 空白预留区：只在宽屏留，靠 weight 吃掉剩余宽度，位置随前两块自动变化
                Spacer(
                    modifier = Modifier.weight(1f)
                )
            }
        } else {

            // 竖屏：两块都按内容取宽（谁也不抢谁的），所以统计块的右边界就停在
            // 最宽那一行的末尾，明细块紧跟其后 —— 两块都完整显示，右侧剩下的留白不占位。
            Row(
                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.spacedBy(10.dp),

                verticalAlignment = Alignment.Top
            ) {

                DataPanel {

                    CpuSummaryBlock(
                        rows = if (isCpu) cpuSummaryRows(snapshot, usageDecimals) else emptyCpuSummaryRows(),

                        wide = false
                    )
                }

                DataPanel {

                    CpuDetailList(
                        lines = if (isCpu) cpuDetailLines(snapshot) else emptyCpuDetailLines()
                    )
                }
            }
        }

        // 底部呼吸空间：与图表的比例是 1 : 3（图表 weight 3、这里 weight 1）
        if (reserveBottom) {

            Spacer(
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 数据面板背景：圆角 + 半透明 surfaceVariant + 内边距。
 *
 * 和图表那块背景同一套视觉语言（同色同透明度、都是 8dp 圆角），
 * 统计块与明细块各套一层 —— 两块高度不同，所以各是各的卡片，不强求等高。
 */
@Composable
private fun DataPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        content()
    }
}

/**
 * 统计区最左块：三行"小字标题 + 标题下的大字数据"。
 *
 * 行内横排、行间竖排；每行的指标数量不同（2 / 3 / 1），所以用数据驱动而不是写死三个 Row。
 */
@Composable
private fun CpuSummaryBlock(
    rows: List<List<CpuStat>>,
    wide: Boolean,
    modifier: Modifier = Modifier
) {

    Column(
        modifier = modifier,

        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        rows.forEach { row ->

            Row(
                // 竖屏块窄，格间距收一档，第二行"文件描述符"那一格才不至于把整块撑出可用宽度；
                // 横屏原本 14dp 在平分铺满后显得两格之间太空，按 2/3 收到 9dp
                horizontalArrangement = Arrangement.spacedBy(if (wide) 9.dp else 10.dp),

                verticalAlignment = Alignment.Bottom
            ) {

                row.forEach { stat ->

                    // 每格按自己的文字宽度排，不平分 —— 平分时格子被撑到整行宽，
                    // 文字左对齐后中间会空出一大段，看起来就是"格间距太大"，
                    // 而 spacedBy 调小了也不起作用。自然宽之后，9dp / 10dp 才是真实间距。
                    CpuStatCell(
                        stat = stat,

                        wide = wide
                    )
                }
            }
        }
    }
}

/**
 * 单个指标：上面小字标题，下面大字数据。
 *
 * 这一格的宽度是**钉死**的（取 stat 里给的 wideWidth / narrowWidth 作最小宽度）：
 * 数值每秒都在变，靠内容撑宽会让整块抖。同时数字用等宽字形（tnum），
 * 位数不变时（35% → 76%）连通一个字形的宽度都不动，只有墨水在换。
 *
 * 字号上限是按"格宽能放下最长取值"算的，不是越大越好看：
 * "1.00 GHz" 放不进就只留下 "1.00 G…"，比小一号字更难看。
 */
@Composable
private fun CpuStatCell(
    stat: CpuStat,
    wide: Boolean,
    modifier: Modifier = Modifier
) {

    val valueStyle = when {
        !stat.emphasis -> MaterialTheme.typography.titleMedium
        wide -> MaterialTheme.typography.headlineMedium
        else -> MaterialTheme.typography.titleLarge
    }

    // 窄屏一格只有几十 dp：标题用更小一档，保证 "文件描述符" 五个字能整串显示
    val labelStyle = if (wide) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.labelSmall
    }

    val fixedWidth = if (wide) stat.wideWidth else stat.narrowWidth

    val cellModifier = if (fixedWidth == Dp.Unspecified) {
        modifier
    } else {
        modifier.widthIn(min = fixedWidth)
    }

    Column(
        modifier = cellModifier
    ) {

        Text(
            text = stat.label,

            style = labelStyle,

            color = MaterialTheme.colorScheme.onSurfaceVariant,

            maxLines = 1,

            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = stat.value,

            style = valueStyle.copy(fontFeatureSettings = "tnum"),

            fontWeight = FontWeight.Bold,

            color = MaterialTheme.colorScheme.onSurface,

            maxLines = 1,

            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 统计区中间块：竖排的「内容: 数据」，全部小字。
 *
 * 标签用弱化色、数据用正常色，靠颜色分层就够读，不需要再加分隔线。
 *
 * 整块宽度**跟着最长的行走**（不 fillMaxWidth）：窄屏下它紧挨在统计块右边，
 * 自己按内容收窄，剩下的水平空间留给统计块 —— 统计块那一排大数字比它更需要宽度。
 */
@Composable
private fun CpuDetailList(
    lines: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {

    Column(
        modifier = modifier,

        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {

        lines.forEach { (label, value) ->

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = "$label:",

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(
                    modifier = Modifier.width(4.dp)
                )

                Text(
                    text = value,

                    // 缓存 / 频率这类值会变，数字用等宽字形，整块就不会跟着抽动
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),

                    color = MaterialTheme.colorScheme.onSurface,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 竖屏 / 窄浮窗底部的硬件选择条：**只有文字**，不带迷你图和数据小字。
 *
 * 为什么另做一套而不是把 [HardwareTab] 压扁：竖屏缺的是横向空间，
 * 一行五个等宽方块只要文字，宽度才够；迷你图在这个尺寸下也读不出信息。
 *
 * 选中项用主色容器着色，和宽屏那一列的视觉语言保持一致。
 */
@Composable
private fun HardwareCompactBar(
    selected: HardwareKind,
    onSelect: (HardwareKind) -> Unit,
    modifier: Modifier = Modifier
) {

    Row(
        modifier = modifier.fillMaxWidth(),

        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {

        HardwareKind.entries.forEach { kind ->

            val isSelected = kind == selected

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(PERFORMANCE_COMPACT_TAB_HEIGHT)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        }
                    )
                    .clickable { onSelect(kind) },

                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = kind.displayName,

                    style = MaterialTheme.typography.bodyMedium,

                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 右区的大趋势图。
 *
 * 画横向网格 + 按历史点连成的折线。网格不是刻度，**最上面那条线就是 100%**：
 * 所以它比其它网格线深一档、也粗一点，和图表上方标签行右端的「100%」是同一个位置。
 *
 * 横轴是**固定槽位**，做法照 Windows 任务管理器的那张 CPU 图：
 *   共 [PERFORMANCE_CHART_POINTS] 个槽，第 59 个槽贴右边缘、第 0 个槽贴左边缘；
 *   采到的点从右往左排，点数不满时左侧留空、曲线贴着右边生长。
 * 这样刚进页面只有一两个点，就只画出右边一小段，不会一上来就拉一条横贯全宽的假线。
 *
 * 点数只看采样攒了多少，不看时间：满 [PERFORMANCE_CHART_POINTS] 个之后新点从右进、
 * 老点从左挤出去，换更新速度既不清数据也不换槽位，图的宽度和点数永远不变。
 * 相邻点之间一律连线，不做断点判断 —— 采样漏一轮也不会把线断开。
 *
 * 两种图形口径（菜单第一项）：
 * - 总体利用率（[logicalGraph] = false）：一张整机曲线；
 * - 逻辑处理器（[logicalGraph] = true）：每个逻辑核一格小图。横向槽位与整机图
 *   共用同一套，所以切图形只换画法，统计区间不变、历史也不清。
 *
 * 内核时间（[showKernelTime]）只有菜单里勾上才画：在曲线下方再画一条更暗的曲线，
 * 并把它与底边之间的区域填色，就是任务管理器里那层"内核"色带。
 * 没勾就一行都不多画，保持原来的纯线条外观。
 *
 * 长按这里弹出图表菜单（[PerformanceChartDialog]）；快速双击则在图形摘要视图与原版式之间切换。
 */
@Composable
private fun DetailChartPlaceholder(
    points: List<CpuUsagePoint>,
    fallbackFraction: Float,
    showKernelTime: Boolean,
    logicalGraph: Boolean,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier
) {

    // 手势：长按弹菜单、快速双击进 / 出图形摘要视图。
    //
    // 用 detectTapGestures 而不是 combinedClickable —— Canvas 没有点击语义，这样最直接，
    // 也不会在浮窗里出令牌问题（它不新开窗口）。
    // 两者能共存：系统先等双击间隔判定，超过长按时长的按压才算长按。
    val gestureModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onLongPress = { onLongPress() },
            onDoubleTap = { onDoubleTap() }
        )
    }

    Box(modifier = modifier.then(gestureModifier)) {

        if (logicalGraph) {

            LogicalCoresChart(
                points = points,
                showKernelTime = showKernelTime,
                modifier = Modifier.fillMaxSize()
            )

        } else {

            TotalUsageChart(
                points = points,
                fallbackFraction = fallbackFraction,
                showKernelTime = showKernelTime,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 内核时间色带：从底边到内核曲线的区域填一层暗色，再描一条细线。
 *
 * 每个槽的值 -1 表示"这一槽没有内核时间"，按 0 画 —— 宁可贴底也不断开，
 * 与"两点之间必须连起来"同一条规矩。
 *
 * @param percents  每个槽的内核占比；-1 = 没值
 * @param startSlot 第一个点落在第几个槽（点数不满时左侧留空）
 * @param slotWidth 槽宽
 * @param left      这块画布的左边（逻辑处理器模式是某一格的左边）
 * @param bottom    这块画布的底边纵坐标
 * @param yOf       百分比 -> 这块画布内的纵坐标
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKernelBand(
    percents: List<Float>,
    startSlot: Int,
    slotWidth: Float,
    left: Float,
    bottom: Float,
    yOf: (Float) -> Float,
    fill: Color,
    stroke: Color
) {
    if (percents.size < 2) {
        return
    }

    val path = Path()

    var any = false

    percents.forEachIndexed { index, value ->

        if (value >= 0f) {
            any = true
        }

        val x = left + (startSlot + index) * slotWidth

        val y = yOf(if (value < 0f) 0f else value)

        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    if (!any) {
        return
    }

    val fillPath = Path()

    fillPath.addPath(path)

    fillPath.lineTo(left + (startSlot + percents.size - 1) * slotWidth, bottom)

    fillPath.lineTo(left + startSlot * slotWidth, bottom)

    fillPath.close()

    drawPath(fillPath, color = fill)

    drawPath(
        path = path,

        color = stroke,

        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
    )
}

/** 总体利用率图：一张图、60 个槽，勾了内核时间就在曲线下方叠一层内核色带。 */
/*
 * 序列的第一个点由 UIService 垫成 0 利用率（真实数据从第二个点开始），
 * 所以这里不要自作主张替首点补值、也不要丢掉"看起来不该有"的首点：
 * 那是曲线从底边起步的唯一来源。
 */
@Composable
private fun TotalUsageChart(
    points: List<CpuUsagePoint>,
    fallbackFraction: Float,
    showKernelTime: Boolean,
    modifier: Modifier = Modifier
) {

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val topLineColor = MaterialTheme.colorScheme.outline
    val lineColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {

        drawRoundRect(
            color = background.copy(alpha = 0.35f),

            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        )

        val rows = 4

        for (index in 0..rows) {

            // index = 0 是顶边 = 100%，其余是背景网格
            val isTop = index == 0

            val y = size.height * index / rows

            drawLine(
                color = if (isTop) topLineColor else gridColor,

                start = Offset(0f, y),

                end = Offset(size.width, y),

                strokeWidth = if (isTop) 1.5.dp.toPx() else 1.dp.toPx(),

                cap = StrokeCap.Butt
            )
        }

        val inset = 2.dp.toPx()

        val usable = size.height - inset * 2

        /** 百分比 -> 画布纵坐标：0% 贴底，100% 贴顶。 */
        val yOf: (Float) -> Float = { percent ->
            size.height - inset - usable * (percent.coerceIn(0f, 100f) / 100f)
        }

        // 一屏只画最后固定个数的点：服务端缓存比这个长，这里兜一层
        val visible = if (points.size > PERFORMANCE_CHART_POINTS) {
            points.takeLast(PERFORMANCE_CHART_POINTS)
        } else {
            points
        }

        // 槽宽：59 段正好把宽度分完（0 号贴左、59 号贴右）
        val slotWidth = size.width / (PERFORMANCE_CHART_POINTS - 1).toFloat()

        if (visible.size < 2) {

            // 单点：贴着右边缘点一下；空数据：拿当前帧利用率占位，不铺一条假线
            val fraction = if (visible.isEmpty()) fallbackFraction else visible.last().percent / 100f

            drawCircle(
                color = lineColor,

                radius = 2.dp.toPx(),

                center = Offset(size.width - 2.dp.toPx(), yOf(fraction.coerceIn(0f, 1f) * 100f))
            )

            // 内核时间也得有个点，否则勾了菜单看不出任何变化
            val kernel = visible.lastOrNull()?.kernelPercent

            if (showKernelTime && kernel != null) {
                drawCircle(
                    color = lineColor.copy(alpha = 0.75f),

                    radius = 1.5.dp.toPx(),

                    center = Offset(size.width - 2.dp.toPx(), yOf(kernel))
                )
            }

            return@Canvas
        }

        // 点数不满时，最老的这一个点落在第 (总数 - 点数) 个槽上：
        // 左边那些槽空着，曲线只占右边一段，随时间推移逐步向左长满。
        val startSlot = PERFORMANCE_CHART_POINTS - visible.size

        // 先画内核（下层），再画总曲线（上层）：总曲线不会被色带盖住
        if (showKernelTime) {

            val kernels = visible.map { point ->

                val kernel = point.kernelPercent ?: return@map -1f

                // 内核不会超过总利用率：协议两边口径相同，这里再夹一次，防脏数据画出穿模
                kernel.coerceIn(0f, point.percent.coerceIn(0f, 100f))
            }

            drawKernelBand(
                percents = kernels,

                startSlot = startSlot,

                slotWidth = slotWidth,

                left = 0f,

                bottom = size.height - inset,

                yOf = yOf,

                fill = lineColor.copy(alpha = 0.28f),

                stroke = lineColor.copy(alpha = 0.75f)
            )
        }

        val path = Path()

        visible.forEachIndexed { index, point ->

            val x = (startSlot + index) * slotWidth

            val y = yOf(point.percent)

            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,

            color = lineColor,

            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.5.dp.toPx(),

                cap = StrokeCap.Round,

                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

/**
 * 逻辑处理器图：每个逻辑核一格小图，网格最多 4 列。
 *
 * 每一格的横向槽位与整机图完全一致（同样是 [PERFORMANCE_CHART_POINTS] 个槽），
 * 所以"总体利用率"与"逻辑处理器"两种口径说的是同一段时间，只是拆细了。
 *
 * 核数取最新一帧的长度：热插拔（核数变了）才换网格排布，不会因为历史里老点少一核就重排。
 *
 * @param showKernelTime 勾了才画每个核自己的内核色带
 */
@Composable
private fun LogicalCoresChart(
    points: List<CpuUsagePoint>,
    showKernelTime: Boolean,
    modifier: Modifier = Modifier
) {

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val topLineColor = MaterialTheme.colorScheme.outline
    val lineColor = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {

        val coreCount = points.lastOrNull { it.corePercents.isNotEmpty() }?.corePercents?.size ?: 0

        // 逐核数据还没到（首帧、老后端）：给一块空底，不要画成"0% 的一条直线"
        if (coreCount <= 0) {

            drawRoundRect(
                color = track.copy(alpha = 0.35f),

                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )

            return@Canvas
        }

        val columns = if (coreCount <= 4) coreCount else 4

        val rows = (coreCount + columns - 1) / columns

        val gap = 3.dp.toPx()

        val cellWidth = (size.width - gap * (columns - 1)) / columns

        val cellHeight = (size.height - gap * (rows - 1)) / rows

        val visible = if (points.size > PERFORMANCE_CHART_POINTS) {
            points.takeLast(PERFORMANCE_CHART_POINTS)
        } else {
            points
        }

        val startSlot = PERFORMANCE_CHART_POINTS - visible.size

        for (core in 0 until coreCount) {

            val column = core % columns

            val row = core / columns

            val left = column * (cellWidth + gap)

            val top = row * (cellHeight + gap)

            drawRoundRect(
                color = track.copy(alpha = 0.35f),

                topLeft = Offset(left, top),

                size = Size(cellWidth, cellHeight),

                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )

            // 每格自己一条 100% 顶线（不是刻度，只是"满格"的位置）
            drawLine(
                color = topLineColor,

                start = Offset(left, top),

                end = Offset(left + cellWidth, top),

                strokeWidth = 1.dp.toPx(),

                cap = StrokeCap.Butt
            )

            val slotWidth = cellWidth / (PERFORMANCE_CHART_POINTS - 1).toFloat()

            /** 这一格内：0% 贴格底，100% 贴格顶。 */
            val yOf: (Float) -> Float = { percent ->
                top + cellHeight - cellHeight * (percent.coerceIn(0f, 100f) / 100f)
            }

            // 这一核的利用率：-1（没值）与缺失都按 0 画，保证不断线也不串位
            val percents = visible.map { point ->
                point.corePercents.getOrNull(core)?.takeIf { it >= 0f } ?: 0f
            }

            if (showKernelTime) {

                val kernels = visible.map { point ->

                    val kernel = point.coreKernels.getOrNull(core) ?: -1f

                    if (kernel < 0f) {
                        -1f
                    } else {
                        // 这一核的内核时间不会超过这一核自己的利用率
                        kernel.coerceIn(0f, point.corePercents.getOrNull(core)?.coerceIn(0f, 100f) ?: 0f)
                    }
                }

                drawKernelBand(
                    percents = kernels,

                    startSlot = startSlot,

                    slotWidth = slotWidth,

                    left = left,

                    bottom = top + cellHeight,

                    yOf = yOf,

                    fill = lineColor.copy(alpha = 0.28f),

                    stroke = lineColor.copy(alpha = 0.75f)
                )
            }

            if (visible.size < 2) {

                drawCircle(
                    color = lineColor,

                    radius = 1.2.dp.toPx(),

                    center = Offset(left + cellWidth - 1.5.dp.toPx(), yOf(percents.lastOrNull() ?: 0f))
                )

                continue
            }

            val path = Path()

            percents.forEachIndexed { index, value ->

                val x = left + (startSlot + index) * slotWidth

                val y = yOf(value)

                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path = path,

                color = lineColor,

                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.dp.toPx(),

                    cap = StrokeCap.Round,

                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
        }
    }
}
/**
 * 长按图表弹出的菜单。
 *
 * 版式照 Windows 任务管理器那张图表选项：**不写标题**，一行行往下排，
 * 项与项之间用横线分组；右括号里的字母是该项的快捷键提示（这一版只显示，暂不接键盘）。
 *
 * 为什么两套实现：普通界面有 Activity 令牌，用 AlertDialog 最省事；
 * 浮窗是 TYPE_APPLICATION_OVERLAY，弹 AlertDialog 会抛 BadTokenException 直接崩进程，
 * 所以改成在当前 Compose 窗口里自己画遮罩 + 卡片。两种观感一致（同项目其它页都是这个做法）。
 *
 * 五项现状：
 * 1) 内核图形口径：选得中、记得住；【逻辑处理器】那条曲线要等逐核数据接到 UI 才真画出来；
 * 2) 显示内核时间：状态留着，画法待定（内核时间还没采集）；
 * 3) 图形摘要视图：整页只剩图表，长按图表退出；
 * 4) 查看：只列出六个目标，点哪个都先关菜单什么也不做 —— 切页接口还没接；
 * 5) 复制：把当前硬件的复制文本写进系统剪贴板。CPU 走 [cpuClipboardText]（与任务管理器
 *    粘贴出来的一致），其余设备暂时是占位文本，等各自数据齐了再补。
 */
@Composable
private fun PerformanceChartDialog(
    hostedInOverlay: Boolean,
    kernelGraphMode: String,
    showKernelTime: Boolean,
    onKernelGraphModeChange: (String) -> Unit,
    onShowKernelTimeChange: (Boolean) -> Unit,
    onEnterSummary: () -> Unit,
    copyText: String,
    onDismiss: () -> Unit
) {

    // 查看的子项默认收着：菜单本来就五行，一上来全摊开太吵
    var viewExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val menu: @Composable () -> Unit = {

        Column(
            modifier = Modifier.widthIn(min = 240.dp)
        ) {

            ChartMenuHeading("将内核图形更改为(H)")

            ChartMenuRow(
                label = "总体利用率(O)",

                marked = kernelGraphMode == CPU_KERNEL_GRAPH_TOTAL,

                indented = true
            ) { onKernelGraphModeChange(CPU_KERNEL_GRAPH_TOTAL) }

            ChartMenuRow(
                label = "逻辑处理器(L)",

                marked = kernelGraphMode == CPU_KERNEL_GRAPH_LOGICAL,

                indented = true
            ) { onKernelGraphModeChange(CPU_KERNEL_GRAPH_LOGICAL) }

            ChartMenuRow(
                label = "显示内核时间(K)",

                marked = showKernelTime
            ) { onShowKernelTimeChange(!showKernelTime) }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),

                color = MaterialTheme.colorScheme.outlineVariant
            )

            ChartMenuRow(
                label = "图形摘要视图(W)"
            ) { onEnterSummary() }

            ChartMenuRow(
                label = "查看(V)",

                trailing = if (viewExpanded) "▾" else "▸"
            ) { viewExpanded = !viewExpanded }

            if (viewExpanded) {

                // 六个目标排两行三列：这个宽度下再摊一列就挤了
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {

                    ChartMenuTarget("CPU(P)", Modifier.weight(1f), onDismiss)

                    ChartMenuTarget("内存(M)", Modifier.weight(1f), onDismiss)

                    ChartMenuTarget("磁盘(D)", Modifier.weight(1f), onDismiss)
                }

                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {

                    ChartMenuTarget("网络(N)", Modifier.weight(1f), onDismiss)

                    ChartMenuTarget("GPU(G)", Modifier.weight(1f), onDismiss)

                    ChartMenuTarget("NPU", Modifier.weight(1f), onDismiss)
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),

                color = MaterialTheme.colorScheme.outlineVariant
            )

            ChartMenuRow(
                label = "复制(C)"
            ) {
                copyChartMenuText(context, copyText)

                onDismiss()
            }
        }
    }

    if (!hostedInOverlay) {

        AlertDialog(
            onDismissRequest = onDismiss,

            text = {
                menu()
            },

            // 这是菜单，不是提示：不摆"确定 / 取消"，点一项就办事、点空白就关
            confirmButton = {}
        )

        return
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        // 遮罩：点空白 = 关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable { onDismiss() }
        )

        // 卡片：自己吃掉点击，别让点内容变成点遮罩
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
                modifier = Modifier.padding(12.dp)
            ) {
                menu()
            }
        }
    }
}

/** 菜单里的分组标题：不可点，只给下面那组选项做说明。 */
@Composable
private fun ChartMenuHeading(text: String) {

    Text(
        text = text,

        style = MaterialTheme.typography.bodyMedium,

        color = MaterialTheme.colorScheme.onSurfaceVariant,

        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
    )
}

/**
 * 菜单里的一行选项。
 *
 * @param label 这一行的文字
 * @param marked 选中标记：单选与复选共用，勾上就在左侧留一个勾
 * @param indented 缩进一级，用于挂在分组标题下面的选项
 * @param trailing 行尾提示字符（查看用箭头表示还能展开）
 * @param onClick 点整行都算，不用非得点到字上
 */
@Composable
private fun ChartMenuRow(
    label: String,
    marked: Boolean = false,
    indented: Boolean = false,
    trailing: String? = null,
    onClick: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = if (indented) 20.dp else 4.dp,

                end = 4.dp,

                top = 8.dp,

                bottom = 8.dp
            ),

        verticalAlignment = Alignment.CenterVertically
    ) {

        // 固定 16.dp 宽：有没有勾都一样宽，选项文字才不会左右跳
        Text(
            text = if (marked) "✓" else "",

            style = MaterialTheme.typography.bodyMedium,

            color = MaterialTheme.colorScheme.primary,

            modifier = Modifier.width(16.dp)
        )

        Text(
            text = label,

            style = MaterialTheme.typography.bodyMedium,

            color = MaterialTheme.colorScheme.onSurface,

            maxLines = 1,

            overflow = TextOverflow.Ellipsis,

            modifier = Modifier.weight(1f)
        )

        if (trailing != null) {

            Text(
                text = trailing,

                style = MaterialTheme.typography.bodyMedium,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 查看展开后的一个目标。
 *
 * 这一版**故意什么都不做**：六个页面还没全部写好，先让菜单能把选项列出来；
 * 以后在这里接 UIService.setActivePage，或直接改本页的选中硬件。
 */
@Composable
private fun ChartMenuTarget(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {

    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),

        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurface,

            maxLines = 1,

            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 把菜单算好的文本写进系统剪贴板。
 *
 * 用系统剪贴板服务而不是 Compose 的剪贴板管理器：浮窗里没有 Activity 令牌，
 * 但剪贴板是系统服务，服务的 Context 一样能写。
 */
private fun copyChartMenuText(context: Context, text: String) {

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager

    if (clipboard == null) {
        return
    }

    clipboard.setPrimaryClip(ClipData.newPlainText("任务管理器", text))
}

// ============================================================
// 性能页图表的画图偏好：一份全应用共享的状态 + 一份落盘
// ============================================================
//
// 这两项只决定"怎么画"：不动采样、不动历史、不动槽位，所以来回切既不丢数据，
// 也不改变图覆盖的时间范围。
//
// 为什么状态必须提到进程级：同一份性能页会在三个地方各实例化一次——主界面竖屏、
// 主界面宽屏（横屏）、悬浮窗，Composable 实例彼此独立；图形摘要视图又是另一个宿主
// 渲染的。状态若只放在页面内部，在浮窗里把口径改成逻辑处理器、回到主界面就又是
// 总体利用率，用户感知就是每个宿主都得重新点一遍。

/**
 * 图表画图偏好的落盘（files/config/chart_view.json）。
 *
 * 存两项：
 * - logical_graph     图表口径：true = 逻辑处理器（每核一格），false = 总体利用率；
 * - show_kernel_time  是否在曲线下方叠内核时间色带。
 *
 * 为什么单独一个文件而不是塞进 settings.json：
 * 1. 这是页面的视图状态，不是设置项：设置页不显示、不管理，重置所有设置也不该抹掉它；
 * 2. settings.json 有严格字段表（SettingKey / diagnose 缺项校验），为两项画图偏好
 *    改整条设置管线不划算；
 * 3. 这份文件坏了只影响图表默认画法，不牵连设置本身的启动与修复流程。
 *
 * 写入方式与 settings.json 一致（.tmp -> rename），失败只返回 false，绝不抛异常。
 */
object ChartViewStore {

    private const val CONFIG_DIR = "config"

    private const val FILE_NAME = "chart_view.json"

    private const val TMP_SUFFIX = ".tmp"

    /** 存档字段：图表口径（true = 逻辑处理器）。 */
    private const val KEY_LOGICAL_GRAPH = "logical_graph"

    /** 存档字段：是否显示内核时间。 */
    private const val KEY_SHOW_KERNEL_TIME = "show_kernel_time"

    /** 一次读出来的两项画图偏好。 */
    class Preferences(

        /** 图表口径：true = 逻辑处理器，false = 总体利用率。 */
        val logicalGraph: Boolean,

        /** 是否叠内核时间色带。 */
        val showKernelTime: Boolean
    )

    fun filePath(context: Context): File = File(File(context.filesDir, CONFIG_DIR), FILE_NAME)

    /**
     * 读两项画图偏好。
     *
     * 承诺：永远给出一份可用的值。没有存档 / 文件损坏 / 字段类型不对，一律退回默认
     * （总体利用率、不画内核时间）——图表默认长什么样，不能因为存档坏了就变个样。
     */
    fun load(context: Context): Preferences {

        val json = try {
            val file = filePath(context)

            if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else null
        } catch (e: Exception) {
            null
        }

        return Preferences(
            logicalGraph = json?.optBoolean(KEY_LOGICAL_GRAPH, false) ?: false,
            showKernelTime = json?.optBoolean(KEY_SHOW_KERNEL_TIME, false) ?: false
        )
    }

    /**
     * 写两项画图偏好。
     *
     * 调用方负责线程：这是几十字节的写盘，但仍然是一次 IO，不放 UI 线程上。
     * 失败只返回 false（磁盘满 / 目录不可写），不抛异常——偏好丢了不该让页面崩。
     */
    fun save(
        context: Context,
        logicalGraph: Boolean,
        showKernelTime: Boolean
    ): Boolean {

        val target = filePath(context)

        val dir = target.parentFile ?: return false

        return try {

            if (!dir.exists() && !dir.mkdirs()) return false

            val json = JSONObject()
                .put(KEY_LOGICAL_GRAPH, logicalGraph)
                .put(KEY_SHOW_KERNEL_TIME, showKernelTime)

            val tmp = File(dir, FILE_NAME + TMP_SUFFIX)

            tmp.writeText(json.toString(2), Charsets.UTF_8)

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)

                tmp.delete()
            }

            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 图表画图偏好：全应用唯一一份。
 *
 * 三处宿主、菜单、图形摘要视图都读同一个 object，任何一处改动会立刻在其余几处生效。
 * 进程重启后第一次进性能页调用 [ensureLoaded] 读回来；之后只动内存，异步写盘。
 */
object ChartViewState {

    /** 图表口径：true = 逻辑处理器（每核一格），false = 总体利用率（默认）。 */
    var logicalGraph by mutableStateOf(false)
        private set

    /** 是否在曲线下方叠内核时间色带；默认不画，菜单里勾了才画。 */
    var showKernelTime by mutableStateOf(false)
        private set

    /** 是否已从磁盘读过一次。 */
    @Volatile
    private var loaded = false

    /** 写盘线程：连续点几下也只排队，不一次点出一个线程。 */
    private val writer = Executors.newSingleThreadExecutor()

    /**
     * 首次读取落盘值（幂等）。
     *
     * 为什么读了就直接改状态、而不是先给默认再异步替换：两项都是布尔，读盘只有
     * 几十字节，同步读一次最省事，也不会出现第一帧画总体利用率、下一帧跳成逐核的闪动。
     * 三处宿主谁先组合谁触发，后到的直接命中 [loaded]。
     */
    @Synchronized
    fun ensureLoaded(context: Context) {

        if (loaded) return

        val preferences = ChartViewStore.load(context.applicationContext)

        logicalGraph = preferences.logicalGraph

        showKernelTime = preferences.showKernelTime

        loaded = true
    }

    /** 改图表口径：内存先改（界面这一帧就重画），再异步写盘。 */
    fun setLogicalGraph(context: Context, value: Boolean) {

        if (logicalGraph == value) return

        logicalGraph = value

        persist(context)
    }

    /** 改显示内核时间：同上。 */
    fun setShowKernelTime(context: Context, value: Boolean) {

        if (showKernelTime == value) return

        showKernelTime = value

        persist(context)
    }

    /**
     * 落盘。
     *
     * 取快照而不是在子线程里读属性：连续两次点击时，让写下的值就是最后一次的值，
     * 也避免读写跨线程的歧义。
     */
    private fun persist(context: Context) {

        val appContext = context.applicationContext

        val logical = logicalGraph

        val kernel = showKernelTime

        writer.execute {
            ChartViewStore.save(appContext, logical, kernel)
        }
    }
}

