/**
 * 性能页 —— 硬件切换列（左）+ 当前硬件详情（右）
 * ============================================================
 *
 * 版式（按需求的"左 1 : 右 4"）：
 *
 *   宽屏（横屏 / 宽浮窗，>= 520.dp）
 *   ┌──────────┬─────────────────────────────────────┐
 *   │ ▁▁ CPU   │  CPU                          NAME │
 *   │ 0% 0GHz  │  60 秒以内的利用率%          100%  │
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.android.taskmanager.data.CpuUsagePoint
import com.android.taskmanager.data.PerformanceSnapshot
import com.android.taskmanager.service.UIService

/** 页面实现状态：改成 IMPLEMENTED 后，MainActivity 的 PageContent() 才会渲染本页。 */
val PerformancePageState: PageState = PageState.IMPLEMENTED

/** 宽窄屏分界：与项目其它页保持一致（520.dp）。 */
private val PERFORMANCE_WIDE_MIN_WIDTH = 520.dp

/**
 * 趋势图的固定槽位数（横向格子数）。
 *
 * 三种更新速度下都对得上：高 0.5 秒 × 60 = 30 秒、常规 1 秒 × 60 = 60 秒、
 * 低 4 秒 × 60 = 240 秒。所以不论速度怎么变，横轴始终是"当前窗口内的采样槽"，
 * 新点从右边进来、老点从左边挤出去，点数不够 60 个时曲线靠右、左边留空。
 */
private const val PERFORMANCE_CHART_SLOTS = 60

/** 窄屏底部硬件条的单项高度：一行文字，按压目标够大就行。 */
private val PERFORMANCE_COMPACT_TAB_HEIGHT = 38.dp

/** 图表时间窗口（秒）。CPU 页用服务给的值，其余四个硬件暂时沿用这个测试值。 */
private const val TEST_CHART_WINDOW_SECONDS = 60

/** 硬件名称占位：CPU 用服务给的处理器商品名，取不到才回落它。 */
private const val TEST_HARDWARE_NAME = "NAME"

/** 左列迷你图取尾部多少个点：这个尺寸下再多就挤成一团了。 */
private const val MINI_SPARKLINE_POINTS = 12

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
private fun cpuSummaryRows(snapshot: PerformanceSnapshot): List<List<CpuStat>> = listOf(
    listOf(
        CpuStat(
            "利用率", snapshot.cpuUsageText(), emphasis = true,
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
    var windowLabel by remember { mutableStateOf("$TEST_CHART_WINDOW_SECONDS 秒以内的利用率%") }

    LaunchedEffect(Unit) {

        windowLabel = service?.performanceChartWindowLabel() ?: windowLabel

        // 立刻补采一帧：后台可能刚采过，但"切进来的第一屏"不该等一个周期
        service?.refreshPerformanceNow()
    }

    // 读可观察状态：服务每采到一帧就会让本页重组一次
    val snapshot = service?.performanceSnapshot() ?: PerformanceSnapshot.EMPTY

    // 历史点：大图与左列迷你图共用这一份，只有取哪些点、画在哪里不同
    val history = service?.performanceCpuHistory() ?: emptyList()

    // 长按图表弹出的测试对话框
    var chartDialogVisible by remember { mutableStateOf(false) }

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

                                percentText = if (isCpu) snapshot.cpuUsageText() else kind.testPercent,

                                detailText = if (isCpu) snapshot.cpuSpeedText() else kind.testDetail,

                                // 迷你图取的是同一份历史点的尾部，只是画得小
                                points = if (isCpu) history.map { it.percent } else emptyList(),

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

                        onChartLongPress = { chartDialogVisible = true },

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

                        onChartLongPress = { chartDialogVisible = true },

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

            points = history,

            windowLabel = windowLabel,

            currentText = snapshot.cpuUsageText(),

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
    points: List<Float>,
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

                points = points,

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
 * 迷你趋势图：把同一份历史点的**尾部若干点**画成小折线。
 *
 * 和大图表是同一个数据源（[PerformanceScreen] 里那份 history），
 * 区别只有三处：取点数、尺寸、以及这里不画网格。
 *
 * 纵向比例固定 0..100%：迷你图不标刻度，比例和大图一致才不会"看着像"。
 */
@Composable
private fun MiniSparkline(
    selected: Boolean,
    points: List<Float>,
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

    // 尾部 12 个点就够看出起伏；再多在这个尺寸下也挤成一团
    val tail = remember(points) { points.takeLast(MINI_SPARKLINE_POINTS) }

    Canvas(modifier = modifier) {

        drawRoundRect(
            color = trackColor,

            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )

        if (tail.size < 2) {

            // 还没攒够点：在最后一槽上点一个点，不要拉一条横贯全宽的线
            val fraction = (tail.lastOrNull() ?: 0f).coerceIn(0f, 100f) / 100f

            val y = size.height - 3.dp.toPx() - (size.height - 6.dp.toPx()) * fraction

            drawCircle(
                color = lineColor,

                radius = 1.5.dp.toPx(),

                center = Offset(size.width - 2.dp.toPx(), y)
            )

            return@Canvas
        }

        val inset = 3.dp.toPx()

        val usable = size.height - inset * 2

        // 和大图表同一套规矩：固定槽位、靠右生长，点数不满时左边留空
        val stepX = (size.width - 4.dp.toPx()) / (MINI_SPARKLINE_POINTS - 1).toFloat()

        val startSlot = MINI_SPARKLINE_POINTS - tail.size

        val path = Path()

        tail.forEachIndexed { index, percent ->

            val x = 2.dp.toPx() + (startSlot + index) * stepX

            val y = size.height - inset - usable * (percent.coerceIn(0f, 100f) / 100f)

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
 * 2) 图表标签行：左「60 秒以内的利用率%」，右「100%」——右上的 100% 对应图表最上面那条线；
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
    onChartLongPress: () -> Unit,
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
    val chartLabel = if (isCpu) windowLabel else "$TEST_CHART_WINDOW_SECONDS 秒以内的利用率%"

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

        // 3) 趋势图（同一份历史点画折线；点数不够时退回当前值水平线）
        //    宽度决定它吃多少高度：宽屏 1 份、竖屏 4 份（留 1 份给底部空白）、浮窗吃满
        DetailChartPlaceholder(
            points = history,

            fallbackFraction = if (isCpu) usageFraction(snapshot) else 0f,

            onLongPress = onChartLongPress,

            modifier = Modifier
                .fillMaxWidth()
                .weight(chartWeight)
        )

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
                    rows = if (isCpu) cpuSummaryRows(snapshot) else emptyCpuSummaryRows(),

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
                        rows = if (isCpu) cpuSummaryRows(snapshot) else emptyCpuSummaryRows(),

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
 * 横轴是**固定 60 个槽位**（见 PERFORMANCE_CHART_SLOTS）：新点从最右进来，老点从最左挤出去。
 * 采样间隔跟着实时更新速度变，但槽位数不变，所以三种速度下横轴的含义都正好是
 * 一个完整窗口（30 / 60 / 240 秒）。点数不足 60 个时曲线靠右生长，左边留空，
 * 不会出现"只有两个点却拉成一条横贯全宽的长线"那种假象。
 *
 * 只有一帧时在最后一槽上点一个小圆点 —— 表示"现在"，而不是铺一条水平线冒充历史。
 *
 * 长按这里会弹一个测试对话框（[PerformanceChartDialog]）。
 */
@Composable
private fun DetailChartPlaceholder(
    points: List<CpuUsagePoint>,
    fallbackFraction: Float,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val topLineColor = MaterialTheme.colorScheme.outline
    val lineColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant

    // 长按手势：用 detectTapGestures 而不是 combinedClickable，
    // Canvas 没有点击语义，这样最直接（也不会在浮窗里出令牌问题，它不新开窗口）
    val longPressModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = { onLongPress() })
    }

    Canvas(modifier = modifier.then(longPressModifier)) {

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
        fun yOf(percent: Float): Float =
            size.height - inset - usable * (percent.coerceIn(0f, 100f) / 100f)

        // 横向是**固定槽位**，不是"有多少点就拉多宽"：
        // 第 0 个槽在最左、第 59 个槽在最右，新点从右边进来，老点从左边挤出去。
        // 点数不够 60 个时整段曲线靠右，左侧留空 —— 而不是把两三个点硬拉成一条横跨全宽的线。
        val stepX = size.width / (PERFORMANCE_CHART_SLOTS - 1).toFloat()

        val visible = if (points.size > PERFORMANCE_CHART_SLOTS) {
            points.takeLast(PERFORMANCE_CHART_SLOTS)
        } else {
            points
        }

        val startSlot = PERFORMANCE_CHART_SLOTS - visible.size

        if (visible.size < 2) {

            // 只有一帧（或一帧都没有）：在它该在的那个槽上画一个点，不铺线
            val fraction = if (visible.isEmpty()) {
                fallbackFraction
            } else {
                visible.last().percent / 100f
            }

            val x = (PERFORMANCE_CHART_SLOTS - 1) * stepX - 2.dp.toPx()

            drawCircle(
                color = lineColor,

                radius = 2.dp.toPx(),

                center = Offset(x, yOf(fraction.coerceIn(0f, 1f) * 100f))
            )

            return@Canvas
        }

        val path = Path()

        visible.forEachIndexed { index, point ->

            val x = (startSlot + index) * stepX

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
 * 长按图表弹出的对话框（**内容目前是测试内容**）。
 *
 * 为什么两套实现：普通界面有 Activity 令牌，用 AlertDialog 最省事；
 * 浮窗是 TYPE_APPLICATION_OVERLAY，弹 AlertDialog 会抛 BadTokenException 直接崩进程，
 * 所以改成在当前 Compose 窗口里自己画遮罩 + 卡片。两种观感一致（同项目其它页都是这个做法）。
 */
@Composable
private fun PerformanceChartDialog(
    hostedInOverlay: Boolean,
    points: List<CpuUsagePoint>,
    windowLabel: String,
    currentText: String,
    onDismiss: () -> Unit
) {

    val body: @Composable () -> Unit = {

        Text(
            text = "当前帧利用率：$currentText",

            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "缓存点数：${points.size}",

            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "窗口：$windowLabel",

            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "采样间隔跟随设置页的实时更新速度；点数上限 512，按窗口自动裁掉最老的。",

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (!hostedInOverlay) {

        AlertDialog(
            onDismissRequest = onDismiss,

            title = {
                Text(
                    text = "CPU 图表缓存（测试内容）",

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
                Button(onClick = onDismiss) {
                    Text("知道了")
                }
            }
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

        // 卡片：自己吃掉点击，别让点内容变成"点遮罩"
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
                    text = "CPU 图表缓存（测试内容）",

                    style = MaterialTheme.typography.titleMedium
                )

                body()

                Row(
                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {

                    // 只留一个按钮：这个框是"知道了"性质的提示，再放"关闭"就是两个同义按钮
                    Button(onClick = onDismiss) {
                        Text("知道了")
                    }
                }
            }
        }
    }
}
