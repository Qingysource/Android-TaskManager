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
 *   └──────────┴──└───────────────────────────────┘──┘
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
 *   │  CPU │ 内存            ← 只文字                 │
 *   └──────────────────────────────────────────────┘
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
 * 硬件条一行等宽文字块（现在两项），不再显示迷你图和数据小字。
 *
 * 数据现状：CPU 与内存两组都已接真实数据，来源是
 *   ELF 设备快照（typeCode=5）-> LinuxService.deviceSnapshot()
 *   -> UIService 清洗成 PerformanceSnapshot -> 发布给性能页管理器（每帧一次）。
 * 磁盘 / WLAN / GPU 三个入口与它们的占位数据已整体删除，重新设计时再加回（见 [HardwareKind]）。
 *
 * 图表窗口那一行的文案由 UIService 按"实时更新速度"换算（高 30 / 常规 60 / 低 240 秒，
 * 已暂停时整段换成 利用率 % (已暂停)）；文案与历史点都由管理器持有，页面只读。
 *
 * 说明：文件位于 ui 目录，但包名为 com.android.taskmanager，与 MainActivity 同包。
 */
package com.android.taskmanager

import android.content.ClipData
import android.content.Context
import android.widget.Toast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.android.taskmanager.data.CpuUsagePoint
import com.android.taskmanager.data.DiskSnapshot
import com.android.taskmanager.data.GPU_UNSUPPORTED_TEXT
import com.android.taskmanager.data.GpuSnapshot
import com.android.taskmanager.data.MemorySnapshot
import com.android.taskmanager.data.NETWORK_UNSUPPORTED_TEXT
import com.android.taskmanager.data.NetworkSnapshot
import com.android.taskmanager.data.PerformanceSnapshot
import com.android.taskmanager.manager.GpuChartData
import com.android.taskmanager.manager.NetworkChartData
import com.android.taskmanager.manager.DiskChartData
import com.android.taskmanager.manager.PageManagers
import com.android.taskmanager.service.UIService
import kotlinx.coroutines.delay

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
 * 横屏底部 CPU 明细的"基准行数"，即上一版固定的 8 行：
 * 基准速度 / 插槽 / 内核 / 逻辑处理器 / 虚拟化 / L1 缓存 / L2 缓存 / L3 缓存。
 *
 * 后面新增的条件行（虚拟机、AVF 支持）不再参与图表的空间分配：
 * 图表按这 8 行时的尺寸走，多出来的行由底部裁切（见 [HardwareDetail] 的宽屏分支）。
 * 改这里的数字等于改"图表占多大"，只影响横屏。
 */
private const val CPU_DETAIL_BASELINE_ROWS = 8

/** 内核图形口径：整颗处理器的总利用率 / 逐个逻辑处理器。 */
private const val CPU_KERNEL_GRAPH_TOTAL = "TOTAL"
private const val CPU_KERNEL_GRAPH_LOGICAL = "LOGICAL"

/** 窄屏底部硬件条的单项高度：一行文字，按压目标够大就行。 */
private val PERFORMANCE_COMPACT_TAB_HEIGHT = 38.dp

/**
 * 横屏左栏（设备选择菜单）的固定宽度。
 *
 * 这里是**一个常数**，不是 `maxWidth × 比例`：只要它跟着可用宽度算，页面导航菜单
 * 一展开（80dp -> 240dp，正文少 160dp），左栏就会跟着缩几 dp —— 用户看到的就是
 * "菜单展开后设备列表也被压扁了"。
 *
 * 现在宽度写死：导航菜单展开 / 折叠时这一列尺寸一点不变，只是整列跟着往右让位，
 * 少掉的宽度全部由右边的详情吸收（详情是 weight(1f)，自动吃满剩下的宽度）。
 *
 * 140dp 是按"能用但别占地"定的：卡内 12dp 边距 + 6dp 间距 + 40dp 迷你图之后，
 * 还剩约 80dp 给文字，刚好放下内存那行 "0.0/0.0 GB (0%)"（9sp 约 73dp）。
 */
private val PERFORMANCE_SLOT_WIDTH = 140.dp

/** 竖屏底部硬件条每一格的固定宽度；条本身改成横滑，格子不再被平分压缩。 */
private val PERFORMANCE_COMPACT_TAB_WIDTH = 76.dp

/** 硬件名称占位：CPU 用服务给的处理器商品名，取不到才回落它。 */
private const val TEST_HARDWARE_NAME = "NAME"

// ==================== WLAN 页的固定文案 ====================
//
// WLAN 的入口与页内数值已经跟着快照里的网卡实例走（见 [NetworkSnapshot]）：
// 明细读不到写"不支持"、速率没有计数写 0 Kbps，都由模型层算好。
// 这里只留页面自己需要的一份文案：适配器名缺失时的兜底，以及吞吐量图的量程 / 预警线标注。

/** 适配器名没有值时的兜底（与模型层"不支持"同一个字）。 */
private const val NETWORK_UNSUPPORTED = NETWORK_UNSUPPORTED_TEXT

/** 吞吐量图还没有量程时的兜底文案（有数据后由图表对象自己算）。 */
private const val NETWORK_SCALE_TEXT = "5 Mbps"

/**
 * 性能页的硬件入口。
 *
 * 现在有 CPU、内存、磁盘、WLAN 四项：CPU / 内存 / 磁盘接通真实数据，
 * WLAN 按快照里的网卡实例出现（有网卡才有入口），明细与速率暂时写 0 或"不支持"，
 * 等底层真的把网卡计数接上来只替换取值 —— 版式与入口逻辑都已按真实设备走。
 * 本文件里所有按硬件分发的 when 都是穷举写法，漏掉哪一处编译器会直接报出来，
 * 不会悄悄渲染成别人的数据。
 */
private enum class HardwareKind(val displayName: String) {

    CPU("CPU"),

    MEMORY("内存"),

    DISK("磁盘"),

    NETWORK("WLAN"),

    GPU("GPU")
}

/**
 * 同一类可能装好几台的硬件（"选中哪一台"才有意义）。
 *
 * CPU 与内存是单实例，序号不参与选中比较；磁盘与网卡是多实例，
 * 选中项要带上"第几台"，否则页面上有几台网卡时会永远停在第一台。
 */
private val MULTI_DEVICE_KINDS: Set<HardwareKind> =
    setOf(HardwareKind.DISK, HardwareKind.NETWORK, HardwareKind.GPU)

/**
 * 左栏 / 底部条上的一个入口。
 *
 * CPU 与内存是单实例，各占一项；磁盘 / 网卡按这一帧快照里有几台给几项
 * （一台都没有就一项都不给）。名字由管理器统一命名（磁盘 0、磁盘 1 … / WLAN、手机网络 …），
 * 这里只搬运文案，不再自己拼。
 *
 * [diskIndex] 对多实例硬件是"同类里的第几台"（网卡复用它，避免为同样的语义再开一个字段）。
 *
 * 三行文字是统一版式：第一行标题（加粗），第二行数据，第三行留给"多字段设备"
 * （磁盘活动时间；CPU / 内存保持空白行，保证所有入口的迷你图与文字高度一致）。
 */
private data class HardwareSlot(
    val kind: HardwareKind,
    val diskIndex: Int,
    val title: String,

    /** 竖屏底部横条用的短标题（磁盘是"磁盘 0"，不带块设备名：格子窄，写了只剩省略号） */
    val shortTitle: String = title,

    val line2: String,
    val line3: String,
    val history: List<CpuUsagePoint>
)

/** 磁盘页要传给详情区的数据：设备本身 + 两张图的缓存 + 量程。 */
private data class DiskPageData(
    val index: Int,
    val title: String,
    val disk: DiskSnapshot,
    val charts: DiskChartData
)

/**
 * GPU 页要传给详情区的数据：显卡本身 + 三张图的缓存。
 *
 * 三张图共用同一个 0..100% 的画法，顶端刻度各自不同（100% / 专用总量 / 共享总量）。
 */
private data class GpuPageData(
    val index: Int,
    val title: String,
    val gpu: GpuSnapshot,
    val charts: GpuChartData
)

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
 * 3) 正常运行时间，右边紧挨着温度（温度是"有才加"的一项：底层没报就不出现）
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

    // 第三行：正常运行时间，右边可能还有温度
    buildList {

        add(CpuStat("正常运行时间", snapshot.uptimeText(), wideWidth = 92.dp, narrowWidth = 92.dp))

        // 温度：底层真的报了才加这一项（没有就连标签都不画，不留空格子）。
        // 字号 / 行高跟"正常运行时间"完全一致 —— 它俩在同一行，用同一个 CpuStat 渲染。
        snapshot.cpuTemperatureText()?.let { text ->

            add(CpuStat("温度", text, wideWidth = 72.dp, narrowWidth = 62.dp))
        }
    }
)

/**
 * 统计区中间块的竖排明细（格式统一为「内容: 数据」，全部小字）。
 *
 * 接口字段与 ELF 侧一一对应：处理器商品名与虚拟化方案由 ELF 提供，
 * 现在前者显示占位、后者显示"不支持"，不会被当成 0。
 *
 * 最后三行（虚拟机 / 虚拟化 / AVF 支持）的取舍全在数据层：
 * 是虚拟机 → 只留"虚拟机: 是"，另外两行让位；
 * 不是虚拟机 → 给"虚拟化"（不支持 / 已禁用 / 已启用(实现)），
 * 系统里真有 AVF 组件时才再加一行"AVF 支持: 是/否"。
 * 这里只把拿到的文案排进列表，不做任何判断。
 */
private fun cpuDetailLines(snapshot: PerformanceSnapshot): List<Pair<String, String>> = buildList {

    add("基准速度" to snapshot.cpuBaseText())
    add("插槽" to snapshot.socketsText())
    add("内核" to snapshot.coresText())
    add("逻辑处理器" to snapshot.logicalCpusText())

    snapshot.virtualMachineText()?.let { add("虚拟机" to it) }
    snapshot.virtualizationText()?.let { add("虚拟化" to it) }
    snapshot.avfText()?.let { add("AVF 支持" to it) }

    add("L1 缓存" to snapshot.l1Text())
    add("L2 缓存" to snapshot.l2Text())
    add("L3 缓存" to snapshot.l3Text())
}

/**
 * 图表菜单"复制"到底往剪贴板写什么：按当前硬件分发。
 *
 * CPU 与内存各有一整块文本，两者都是穷举分支。
 * 以后接新设备时在这里加一个分支，菜单与剪贴板调用处都不用动。
 */
private fun clipboardTextFor(
    kind: HardwareKind,
    snapshot: PerformanceSnapshot,
    usageDecimals: Int,
    disk: DiskSnapshot?,
    network: NetworkSnapshot?,
    networkLabel: String,
    networkScale: String,
    gpu: GpuSnapshot?
): String = when (kind) {

    HardwareKind.CPU -> cpuClipboardText(snapshot, usageDecimals)

    HardwareKind.MEMORY -> memoryClipboardText(snapshot)

    HardwareKind.DISK -> diskClipboardText(disk)

    HardwareKind.NETWORK -> networkClipboardText(network, networkLabel, networkScale)

    HardwareKind.GPU -> gpuClipboardText(gpu)
}

/**
 * 磁盘视图"复制"写进剪贴板的文本（骨架与 CPU / 内存那份一致）：
 *
 * 磁盘                         标题行
 * （空行）
 * \t名称:\t设备名              规格块：字段名后面带冒号、Tab 分隔
 * \t容量:\t…
 * \t已格式化:\t…
 * \t系统磁盘:\t…
 * \t页面文件:\t…
 * \t类型:\t…
 * （空行）
 * \t活动时间\t…               实时块：字段名后面不带冒号
 * \t平均响应时间\t…
 * \t读取速度\t…
 * \t写入速度\t…
 * （末尾空行）
 *
 * 数值一律走与页面同一套 xxxText()：读不到的照页面写破折号，不编造 0。
 */
private fun diskClipboardText(disk: DiskSnapshot?): String {

    val data = disk ?: DiskSnapshot()

    return buildString {

        appendLine("磁盘")

        appendLine()

        append("\t名称:\t").appendLine(data.nameText())

        append("\t容量:\t").appendLine(data.capacityText())

        append("\t已格式化:\t").appendLine(data.formattedText())

        append("\t系统磁盘:\t").appendLine(data.systemDiskText())

        append("\t页面文件:\t").appendLine(data.pageFileText())

        append("\t类型:\t").appendLine(data.typeText())

        appendLine()

        append("\t活动时间\t").appendLine(data.activityText())

        append("\t平均响应时间\t").appendLine(data.responseText())

        append("\t读取速度\t").appendLine(data.readSpeedText())

        append("\t写入速度\t").appendLine(data.writeSpeedText())

        appendLine()
    }
}

/**
 * 网卡视图（WLAN / 手机网络 / 以太网）"复制"写进剪贴板的文本。
 *
 * 版式与磁盘那份同一个骨架，只是字段换成网卡这一套（发送 / 接收 + 明细行）。
 * 数值一律走 [NetworkSnapshot] 上的同一套文案方法：明细读不到写"不支持"、速率写 0。
 * 手机网络与以太网没有 SSID / 信号强度，这两行整行不写
 * （判据在模型层，底层补数据后自动出现）。
 */
private fun networkClipboardText(
    network: NetworkSnapshot?,
    label: String,
    scaleText: String
): String {

    val data = network ?: NetworkSnapshot()

    return buildString {

        appendLine(label)

        appendLine()

        append("\t适配器名称:\t").appendLine(data.nameText())

        // SSID / 信号强度是无线专有字段：手机网络没有这两样，整行不写
        // （判据在 NetworkSnapshot.showsSsidRow / showsSignalRow）
        if (data.showsSsidRow) {
            append("\tSSID:\t").appendLine(data.ssidText())
        }

        append("\t连接类型:\t").appendLine(data.linkTypeText())

        append("\tIPv4 地址:\t").appendLine(data.ipv4Text())

        append("\tIPv6 地址:\t").appendLine(data.ipv6Text())

        if (data.showsSignalRow) {
            append("\t信号强度:\t").appendLine(data.signalText())
        }

        appendLine()

        append("\t吞吐量\t").appendLine(scaleText)

        append("\t发送\t").appendLine(data.sendText())

        append("\t接收\t").appendLine(data.receiveText())

        appendLine()
    }
}

/**
 * 内存视图"复制"写进剪贴板的文本，形状与 CPU 那份同一个骨架：
 *
 * 内存                      设备名，顶格
 * （空行）
 * \t8.0 GB LPDDR5           名称行：标称容量 + 内存规格
 * （空行）
 * \t速度:\t3200 MT/秒        规格块：字段名后面带冒号，字段名与值之间是 Tab
 * \t已使用的插槽:\t1/1
 * \t外观规格:\t其他
 * \t为硬件保留的内存:\t262 MB
 * （空行）
 * \t可用\t4.3 GB            实时块：字段名后面不带冒号
 * ...
 * （末尾空行）
 *
 * 用词按 Linux 侧的叫法，不照搬任务管理器的 Windows 术语：
 * 分页缓冲池 / 非分页缓冲池在这里是可回收内核缓存 / 不可回收内核缓存。
 * 数值一律走与页面同一套 xxxText()，读不到的照页面写破折号，不编造 0。
 * 速度那一行没数据就整行不写（与页面明细块一致）。
 */
private fun memoryClipboardText(snapshot: PerformanceSnapshot): String {

    val memory = snapshot.memory ?: MemorySnapshot()

    return buildString {

        append("内存")

        append("\n\n\t").append(memory.nameText())

        // 规格块：静态信息，开机之后基本不变
        memory.speedText()?.let { append("\n\t速度:\t").append(it) }
        append("\n\t已使用的插槽:\t").append(memory.slotsText())
        append("\n\t外观规格:\t").append(memory.formFactorText())
        append("\n\t为硬件保留的内存:\t").append(memory.reservedText())

        // 实时块：用 Linux 的名字，顺序照任务管理器（使用中排最后）
        append("\n\n\t可用\t").append(memory.availableText())
        append("\n\t已缓存\t").append(memory.cachedText())
        append("\n\t已提交\t").append(memory.committedText())
        append("\n\t可回收内核缓存\t").append(memory.reclaimableText())
        append("\n\t不可回收内核缓存\t").append(memory.nonReclaimableText())
        append("\n\t使用中(已压缩)\t").append(memory.usedText())

        append("\n\n")
    }
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

        // 与页面同一套取舍：虚拟机优先，非虚拟机才写虚拟化与 AVF
        snapshot.virtualMachineText()?.let { append("\n\t虚拟机:\t").append(it) }
        snapshot.virtualizationText()?.let { append("\n\t虚拟化:\t").append(it) }
        snapshot.avfText()?.let { append("\n\tAVF 支持:\t").append(it) }

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

    // 页面私有的数据与视图状态都由性能页管理器持有（见 manager/PerformanceManager.kt）。
    //
    // 为什么不放在页面里：竖屏 / 横屏 / 浮窗是三个宿主，页面离开组合时 remember 的值
    // 会一起丢掉；而 CPU / 内存两条历史更不该跟着组合走（离开页面就断点）。
    // 管理器是进程级的，三处宿主读同一份：选中项、图表偏好、摘要开关、
    // 还有一直在攒的历史点，切页往返与浮窗都停在原处。
    val context = LocalContext.current

    val manager = remember(context) {
        PageManagers.performance(context.applicationContext)
    }

    // 管理器里存的选中项是两个字段：硬件类别（名字）+ 磁盘序号。
    // 类别只有 CPU / MEMORY / DISK 三个名字，磁盘靠序号区分是哪一台。
    val requestedKindName = manager.selectedKindName

    val requestedDiskIndex = manager.selectedDiskIndex

    // 同进程内直连服务；取不到时按空帧显示。
    //
    // 这里必须"等到实例出现"，不能只在组合的第一帧取一次：服务是异步创建的，
    // 起始页如果就是本页，组合首帧往往还拿不到实例（UIService 正在 onCreate 里跑），
    // 而取一次之后本页没有任何状态会变、也就不会重组，实例再也不会被重新读到——
    // 表现就是"默认启动页设成性能页，图一直不出数据"；从别的页切进来时才碰巧正常。
    // 等实例期间也顺带处理服务被系统回收后重建的情况。
    val service by produceState<UIService?>(initialValue = UIService.instanceOrNull()) {

        while (value == null) {

            delay(150)

            value = UIService.instanceOrNull()
        }
    }

    // 窗口文案 / 利用率小数位都由管理器算好了：它们跟着设置页的档位变，
    // 所以不在页面里缓存 —— 那样改完设置回来还得等一次重组才对上。
    val windowLabel = manager.windowLabel

    val usageDecimals = manager.usageDecimals

    // 服务一出现就接管：读最新设置、补采一帧（第一屏不等一个周期），
    // 同时也请求服务端重算一次 CPU 名称（只清名称缓存，不动刷新节奏）。
    LaunchedEffect(service) {

        if (service == null) return@LaunchedEffect

        manager.onScreenEntered()
    }

    // 读管理器里可观察的状态：每采到一帧，快照与两条历史都会更新，本页自动重组
    val snapshot = manager.snapshot

    // 历史点：大图与左列迷你图共用这一份，只有取哪些点、画在哪里不同
    val history = manager.cpuHistory

    // 内存那条曲线有自己的一份队列（纵轴是占总量的百分比），不能拿 CPU 的点顶替
    val memoryHistory = manager.memoryHistory

    // 这一帧里有哪些入口：CPU、内存各一个（单实例），磁盘 / 网卡按快照里有几台给几项。
    // 一台磁盘都没有时列表里就没有磁盘 —— 空设备不画空壳，连入口都不出现。网卡同理。
    val disks = snapshot.disks

    val networks = snapshot.networks

    val gpus = snapshot.gpus

    // 读一次缓存表：每帧它都会被整体替换，读到它本页就会重组
    val diskCharts = manager.diskCharts

    val slots = buildList {

        add(
            HardwareSlot(
                kind = HardwareKind.CPU,
                diskIndex = 0,
                title = "CPU",
                line2 = "${snapshot.cpuUsageText(usageDecimals)} ${snapshot.cpuSpeedText()}",
                // 第三行留空：CPU 没有第二个字段，但入口高度要与磁盘对齐
                line3 = "",
                history = history
            )
        )

        add(
            HardwareSlot(
                kind = HardwareKind.MEMORY,
                diskIndex = 0,
                title = "内存",
                line2 = "${snapshot.memory?.amountText() ?: "—"} " +
                    "(${snapshot.memoryUsageText(usageDecimals)})",
                line3 = "",
                history = memoryHistory
            )
        )

        disks.forEachIndexed { index, disk ->

            add(
                HardwareSlot(
                    kind = HardwareKind.DISK,
                    diskIndex = index,
                    // 名字由管理器统一编号，并带上块设备名：磁盘 0 (sda)、磁盘 1 (sdb) …
                    title = manager.diskTitle(index, disk.node),
                    // 竖屏横条仍用短标题，保持"磁盘 0 / 磁盘 1"
                    shortTitle = manager.diskTitle(index),
                    line2 = disk.typeText(),
                    line3 = disk.activityText(),
                    history = manager.diskChart(disks, index)?.activity ?: emptyList()
                )
            )
        }

        // 网卡与磁盘同一套规矩：这一帧里有几块网卡就给几个入口，
        // 一块都没有（绝大多数手机在飞航模式 / 无 WLAN 时）就连网卡入口都不出现。
        // 名字由管理器统一编号：按类型命名（WLAN / Wi-Fi、手机网络、以太网），
        // 同类多块才从第二块起带 1、2 …（第一块不带序号），竖屏横条用的也是这份名字。
        networks.forEachIndexed { index, network ->

            add(
                HardwareSlot(
                    kind = HardwareKind.NETWORK,
                    diskIndex = index,
                    // 大字是"设备类型名"（WLAN / 手机网络 / 以太网），
                    // 同类第二块起才带序号（WLAN、WLAN 1 …）
                    title = manager.networkTitle(networks, index),
                    // 第二行是适配器名（wlan0 / rmnet_data0），与右栏右上角那个
                    // name 字段同源；**不要**和上面那行写一样 —— 重复的信息没意义
                    line2 = network.nameText(),
                    line3 = "发送: ${network.sendText()}  接收: ${network.receiveText()}",
                    // 迷你图用接收那一条（与详情页紫色曲线同一个环形缓冲）
                    history = manager.networkChart(networks, index)?.receive ?: emptyList()
                )
            )
        }

        // 显卡与磁盘 / 网卡同一套规矩：这一帧里有几张卡就给几个入口。
        // 名字由管理器统一编号（GPU 0、GPU 1 …），第二行是适配器名，第三行是利用率与温度。
        gpus.forEachIndexed { index, gpu ->

            add(
                HardwareSlot(
                    kind = HardwareKind.GPU,
                    diskIndex = index,
                    title = manager.gpuTitle(index),
                    line2 = gpu.nameText(),
                    line3 = gpu.utilizationText() +
                        (gpu.temperatureC?.let { " ($it °C)" } ?: ""),
                    // 迷你图用 3D 利用率那一条
                    history = manager.gpuChart(gpus, index)?.utilization ?: emptyList()
                )
            )
        }
    }

    // 选中的那一项：先按类别 + 设备序号找；找不到（比如磁盘被拔掉、旧存档认不出）就退回第一个。
    //
    // 序号只对"同类多台"的硬件参与比较（磁盘、网卡）：CPU / 内存只有一台，序号没有意义，
    // 拿它一起比的话，残留的旧序号会让这一项"查不到"，于是退到 CPU（见 selectKind 的注释）。
    val selectedSlot = slots.firstOrNull { slot ->
        slot.kind.name == requestedKindName &&
            (slot.kind !in MULTI_DEVICE_KINDS || slot.diskIndex == requestedDiskIndex)
    } ?: slots.first()

    val selected = selectedSlot.kind

    val selectedDiskIndex = selectedSlot.diskIndex

    // 明细区 / 摘要区用的历史点：跟着当前选中项走
    val detailHistory = selectedSlot.history

    // 磁盘页的整套数据（设备 + 两张图的缓存 + 量程）；当前不是磁盘就是 null
    val diskPageData: DiskPageData? = if (selected == HardwareKind.DISK) {

        val disk = disks.getOrNull(selectedDiskIndex)

        val charts = manager.diskChart(disks, selectedDiskIndex) ?: DiskChartData.EMPTY

        disk?.let { DiskPageData(selectedDiskIndex, selectedSlot.title, it, charts) }

    } else {
        null
    }

    // WLAN 页的数据：当前选中的那块网卡（不是 WLAN 页、或这一帧里没有网卡就是 null，
    // 详情区按"不支持" + 0 渲染，与截图那一版一致）
    val selectedNetwork: NetworkSnapshot? =
        if (selected == HardwareKind.NETWORK) networks.getOrNull(selectedDiskIndex) else null

    // 吞吐量图：两条速率曲线 + 当前量程（由管理器按"超过一半就升档"维护）
    val selectedNetworkChart: NetworkChartData? =
        if (selected == HardwareKind.NETWORK) manager.networkChart(networks, selectedDiskIndex) else null

    // 网卡页的标题（页头左上角 / 摘要 / 剪贴板首行都读这一份）：跟着"选中的那块网卡"
    // 的类型走 —— 选无线就是「WLAN」，选手机网络就是「手机网络」。
    // 以前这里固定用 networkDisplayName，接上手机网络后会把蜂窝页也标成 WLAN。
    val networkPageLabel: String = manager.networkTitle(networks, selectedDiskIndex)

    // GPU 页的整套数据（显卡 + 三张图的缓存）；当前不是 GPU 就是 null
    val gpuPageData: GpuPageData? = if (selected == HardwareKind.GPU) {

        val gpu = gpus.getOrNull(selectedDiskIndex)

        val charts = manager.gpuChart(gpus, selectedDiskIndex) ?: GpuChartData.EMPTY

        gpu?.let { GpuPageData(selectedDiskIndex, selectedSlot.title, it, charts) }

    } else {
        null
    }

    // 长按图表弹出的菜单
    var chartDialogVisible by remember { mutableStateOf(false) }

    // 网络适配器的「网络详细信息」页（占位画布）：只从长按菜单里的那一项进
    var networkDetailVisible by remember { mutableStateOf(false) }

    // 菜单里的两项画图偏好（图表口径、是否显示内核时间）**不放在本页内部**：
    // 页面里的 remember 在竖屏 / 横屏 / 悬浮窗三个宿主各有一份，图形摘要视图又是另一个
    // 宿主渲染的，结果就是"换一个地方就得重新点一遍"。统一由性能页管理器持有，
    // 三处宿主与摘要视图读同一份值，改动立刻互相可见，并且落盘。
    // 首次进本页把落盘的图表偏好读进来；已读过就是空操作（不再产生 IO）
    remember(context) {
        manager.ensureLoaded()
    }

    // 图表口径（true = 逻辑处理器，false = 总体利用率）与内核时间开关
    val logicalGraph = manager.logicalGraph

    val showKernelTime = manager.showKernelTime

    // 双击图表切进摘要视图。
    // 现在页面上只有 CPU 与内存两项，两者都有真实曲线，所以不需要准入判断；
    // 以后再加没有数据的硬件入口时，这里要恢复"只让有曲线的项进摘要"的过滤。
    //
    // 两个入口分开记：摘要里要把被双击的那一张放大（磁盘两张图，主图与速率图各算一张）。
    val enterChartSummary: () -> Unit = {
        manager.summarySecondChart = false
        manager.summaryActive = true
    }

    /**
     * 第二张图双击：磁盘的速率图自己放大；内存的分条图是主图的图例，
     * 双击它进摘要仍然放大主图（摘要里那块分条图本来就跟着一起画）。
     */
    val enterSecondChartSummary: () -> Unit = {
        manager.summarySecondChart = selected == HardwareKind.DISK
        manager.summaryActive = true
    }

    /** 摘要里双击任意一张图都是退出。 */
    val exitChartSummary: () -> Unit = {
        manager.summaryActive = false
    }

    /**
     * 右上角菜单「复制图表」：把当前选中硬件的那份文本写进剪贴板。
     *
     * 文本由页面自己的 clipboardTextFor 现算 —— 与长按图表菜单里的「复制」同一份，
     * 走的是同一个"当前设备"口径（CPU / 内存 / 磁盘 / WLAN / GPU 自动分发），
     * MainActivity 不参与，也不需要它转发。
     */
    val copyCurrentChart: () -> Unit = {

        val text = clipboardTextFor(
            kind = selected,
            snapshot = snapshot,
            usageDecimals = usageDecimals,
            disk = diskPageData?.disk,
            network = selectedNetwork,
            networkLabel = networkPageLabel,
            networkScale = selectedNetworkChart?.rangeText() ?: NETWORK_SCALE_TEXT,
            gpu = gpuPageData?.gpu
        )

        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)

        clipboard?.setPrimaryClip(
            ClipData.newPlainText("性能页图表", text)
        )

        Toast.makeText(context, "已复制当前图表", Toast.LENGTH_SHORT).show()
    }

    // 把这份复制函数挂到管理器上：菜单按钮现在画在宿主的页名行（搜索框那一排），
    // 那里只负责发"复制"请求，真正的文本仍由本页现算，MainActivity 不碰剪贴板。
    SideEffect {
        manager.copyChartHandler = copyCurrentChart
    }

    // 页面离开组合时摘掉，免得宿主在别的页面点到空实现
    DisposableEffect(manager) {
        onDispose { manager.copyChartHandler = null }
    }

    // 图形摘要视图：整页只剩这一页的头部（名称行 / 标签行）与图表，图表吃满其余高度。
    //
    // 谁让位：主界面的导航栏与标题栏由 MainPage 让出，浮窗的标题栏与页签由 OverlayPageFrame 让出；
    // 本页内部则不再画左栏硬件列表（横屏）与底部硬件条（竖屏 / 浮窗），
    // 也不再画图表下面的统计块、明细块与预留区。
    if (manager.summaryActive) {

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
            //
            // kind 必须跟着当前选中项走：内存进摘要要显示内存那一套（名称行、标签行、纵轴），
            // 写死 CPU 会让内存双击后看到一张 CPU 图。
            HardwareDetail(
                kind = selected,

                snapshot = snapshot,

                history = detailHistory,

                windowLabel = windowLabel,

                wide = true,

                hostedInOverlay = hostedInOverlay,

                usageDecimals = usageDecimals,

                // 摘要里长按不弹菜单：这里只有一张图，弹出来也没处可用
                onChartLongPress = {},

                // 主图与第二张图双击都退出摘要
                onChartDoubleTap = exitChartSummary,

                onSecondChartDoubleTap = exitChartSummary,

                // 摘要沿用进入前选定的两项：图还是那张图，只是铺满整页。
                // 值是进程级共享的，所以从浮窗双击进来也是逐核 / 带内核时间的那一版。
                showKernelTime = showKernelTime,

                logicalGraph = logicalGraph,

                diskData = diskPageData,

                gpuData = gpuPageData,

                networkData = selectedNetwork,

                networkChart = selectedNetworkChart,

                networkLabel = networkPageLabel,





                summary = true,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        return
    }

    // 网络适配器的「网络详细信息」页：目前是一块可以自由摆放控件的画布（占位）。
    // 它和主界面右上角那个「设备详细信息」入口不是同一个页面，各走各的。
    if (networkDetailVisible) {

        NetworkDetailCanvasPage(
            onClose = { networkDetailVisible = false }
        )

        return
    }

    // 本页自己管左右两块的高度分配，所以 PageShell 关掉整页纵向滚动（否则 Row 的高度会被撑成无限）
    PageShell(scrollable = false) {

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {

            val wide = maxWidth >= PERFORMANCE_WIDE_MIN_WIDTH

            if (wide) {

                // 横屏 / 宽浮窗：左栏固定 140dp（设备选择菜单）+ 右边详情吃满剩下的宽度。
                //
                // 宽度是常数、不跟 maxWidth 走：页面导航菜单从 80dp 展开到 240dp 时，
                // 正文会少 160dp，按比例算的话左栏也会跟着缩（166 -> 134dp），
                // 内存那行 "0.0/0.0 GB (0%)" 就又挤没了。
                // 现在展开导航只让这一列整体往右让位、自身尺寸不动，损失由详情吸收。

                Row(
                    modifier = Modifier.fillMaxSize()
                ) {

                    val hardwareScroll = rememberScrollState()

                    Box(
                        modifier = Modifier
                            .width(PERFORMANCE_SLOT_WIDTH)
                            .fillMaxHeight()
                    ) {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(hardwareScroll),

                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {

                        slots.forEach { slot ->

                            // 左栏每一格：标题 / 数据 / 第三行 + 迷你图，都按这一项自己的数据取
                            HardwareTab(
                                title = slot.title,

                                line2 = slot.line2,

                                line3 = slot.line3,

                                history = slot.history,

                                selected = slot.kind == selected && slot.diskIndex == selectedDiskIndex,

                                onClick = {
                                    when (slot.kind) {
                                        // 多台同类的硬件要把"第几台"一起记下来
                                        HardwareKind.DISK -> manager.selectDisk(slot.diskIndex)
                                        HardwareKind.NETWORK -> manager.selectNetwork(slot.diskIndex)
                                        HardwareKind.GPU -> manager.selectGpu(slot.diskIndex)
                                        else -> manager.selectKind(slot.kind.name)
                                    }
                                }
                            )
                        }
                    }

                        // 设备多了才出现的细滚动条：贴在左栏右边缘，横屏专用；
                        // 竖屏那条（HardwareCompactBar）是横滑、不画滚动条
                        HardwareScrollbar(
                            state = hardwareScroll,

                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(4.dp)
                                .padding(vertical = 4.dp)
                        )
                    }

                    Spacer(
                        modifier = Modifier.width(16.dp)
                    )

                    HardwareDetail(
                        kind = selected,

                        snapshot = snapshot,

                        history = detailHistory,

                        windowLabel = windowLabel,

                        wide = true,

                        hostedInOverlay = hostedInOverlay,

                        usageDecimals = usageDecimals,

                        onChartLongPress = { chartDialogVisible = true },

                        onChartDoubleTap = enterChartSummary,

                        onSecondChartDoubleTap = enterSecondChartSummary,

                        showKernelTime = showKernelTime,

                        logicalGraph = logicalGraph,

                        diskData = diskPageData,

                        gpuData = gpuPageData,

                        networkData = selectedNetwork,

                        networkChart = selectedNetworkChart,

                        networkLabel = networkPageLabel,





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

                        history = detailHistory,

                        windowLabel = windowLabel,

                        wide = false,

                        hostedInOverlay = hostedInOverlay,

                        usageDecimals = usageDecimals,

                        onChartLongPress = { chartDialogVisible = true },

                        onChartDoubleTap = enterChartSummary,

                        onSecondChartDoubleTap = enterSecondChartSummary,

                        showKernelTime = showKernelTime,

                        logicalGraph = logicalGraph,

                        diskData = diskPageData,

                        // 竖屏这一处此前漏传了 gpuData：GPU 页拿到 null，
                        // 名称、统计格、明细、三条曲线全部退回空值 —— 这正是"竖屏 GPU 页什么都没有、
                        // 横屏正常"的原因。三个宿主（横屏 / 竖屏 / 摘要）必须都传同一份。
                        gpuData = gpuPageData,

                        networkData = selectedNetwork,

                        networkChart = selectedNetworkChart,

                        networkLabel = networkPageLabel,





                        modifier = Modifier.weight(1f)
                            .fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    HardwareCompactBar(
                        slots = slots,
                        selectedKind = selected,
                        selectedDiskIndex = selectedDiskIndex,
                        onSelect = { slot ->
                            when (slot.kind) {
                                HardwareKind.DISK -> manager.selectDisk(slot.diskIndex)
                                HardwareKind.NETWORK -> manager.selectNetwork(slot.diskIndex)
                                HardwareKind.GPU -> manager.selectGpu(slot.diskIndex)
                                else -> manager.selectKind(slot.kind.name)
                            }
                        }
                    )
                }
            }
        }
    }

    if (chartDialogVisible) {

        PerformanceChartDialog(
            hostedInOverlay = hostedInOverlay,

            // 菜单内容按当前硬件裁剪：内核图形那三项只有 CPU 有，内存页不摆它们
            kind = selected,

            // 菜单读的是全应用唯一那份画图偏好，改一处三处宿主与摘要视图同时变色
            kernelGraphMode = if (logicalGraph) CPU_KERNEL_GRAPH_LOGICAL else CPU_KERNEL_GRAPH_TOTAL,

            showKernelTime = showKernelTime,

            onKernelGraphModeChange = { mode ->
                manager.updateLogicalGraph(mode == CPU_KERNEL_GRAPH_LOGICAL)
            },

            onShowKernelTimeChange = { enabled ->
                manager.updateShowKernelTime(enabled)
            },

            onEnterSummary = {
                chartDialogVisible = false

                // 当前这一项有真实曲线（CPU / 内存 / 磁盘都是），直接进摘要
                manager.summaryActive = true
            },

            // 查看只列"设备类型"，不列实例：固定六格（CPU/内存、磁盘/网络、GPU/NPU），
            // 点哪一类就切到那一类的第一个实例（磁盘 -> 磁盘 0，网络 -> 网卡 0，GPU -> GPU 0）。
            // 这一类一台都没读到就置灰：点下去也没有具体设备可定位。
            // NPU 目前底层还没有采集，先固定占位并置灰，等有数据源再把 enabled 打开。
            viewTargets = listOf(
                ChartViewTarget("CPU(P)") { manager.selectKind("CPU") },
                ChartViewTarget("内存(M)") { manager.selectKind("MEMORY") },
                ChartViewTarget("磁盘(D)", enabled = disks.isNotEmpty()) { manager.selectDisk(0) },
                ChartViewTarget("网络(N)", enabled = networks.isNotEmpty()) { manager.selectNetwork(0) },
                ChartViewTarget("GPU(G)", enabled = gpus.isNotEmpty()) { manager.selectGpu(0) },
                ChartViewTarget("NPU", enabled = false) { }
            ),

            // 网络适配器专属的「查看网络详细信息」：只对网卡页出现，进去是一块占位画布
            onViewNetworkDetail = {
                networkDetailVisible = true
            },

            // 复制内容按当前选中的硬件现算：CPU / 内存各有一整块
            copyText = clipboardTextFor(
                kind = selected,
                snapshot = snapshot,
                usageDecimals = usageDecimals,
                disk = diskPageData?.disk,
                network = selectedNetwork,
                networkLabel = networkPageLabel,

                networkScale = selectedNetworkChart?.rangeText() ?: NETWORK_SCALE_TEXT,

                gpu = gpuPageData?.gpu
            ),

            onDismiss = { chartDialogVisible = false }
        )
    }
}

/** 利用率换算成 0..1 的绘制比例；没有数据就贴底。 */
private fun usageFraction(snapshot: PerformanceSnapshot): Float =
    (snapshot.cpuUsagePercent ?: 0f).coerceIn(0f, 100f) / 100f

/**
 * 内存用量换算成 0..1 的绘制比例：已用 / 总容量。
 *
 * 两边的量拿不到就贴底 —— 与 CPU 图同一条规矩：宁可从 0 起，不拿假值撑起一条线。
 */
private fun memoryUsageFraction(memory: MemorySnapshot?): Float {

    val used = memory?.usedBytes ?: return 0f

    val total = memory.totalBytes ?: return 0f

    if (total <= 0L) return 0f

    return (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * 磁盘活动时间换算成 0..1 的绘制比例；没有数据就贴底（与 CPU / 内存同一规矩）。
 */
private fun diskActivityFraction(disk: DiskSnapshot?): Float =
    ((disk?.activityPercent ?: 0f).coerceIn(0f, 100f)) / 100f

/**
 * 内存页的统计区：三行两列，顺序与版式固定（不随数据变）。
 *
 * 第一行 使用中(已压缩) / 可用
 * 第二行 已提交        / 已缓存
 * 第三行 可回收内核缓存 / 不可回收内核缓存
 *
 * 标题里的括号用半角，与明细块同一套规矩；空格宽度按最长取值估算，数字每秒都在变。
 */
private fun memorySummaryRows(memory: MemorySnapshot?): List<List<CpuStat>> {

    val data = memory ?: MemorySnapshot()

    return listOf(
        listOf(
            CpuStat(
                "使用中(已压缩)", data.usedText(),
                wideWidth = 118.dp, narrowWidth = 104.dp
            ),
            CpuStat("可用", data.availableText(), wideWidth = 72.dp, narrowWidth = 62.dp)
        ),

        listOf(
            CpuStat("已提交", data.committedText(), wideWidth = 104.dp, narrowWidth = 92.dp),
            CpuStat("已缓存", data.cachedText(), wideWidth = 72.dp, narrowWidth = 62.dp)
        ),

        listOf(
            CpuStat(
                "可回收内核缓存", data.reclaimableText(),
                wideWidth = 104.dp, narrowWidth = 92.dp
            ),
            CpuStat(
                "不可回收内核缓存", data.nonReclaimableText(),
                wideWidth = 118.dp, narrowWidth = 104.dp
            )
        )
    )
}

/**
 * 内存页的明细块：与 CPU 那边同一个排法（内容: 数据）。
 *
 * 速度这一行只在底层真给了内容时才出现 —— 没有数据就整行不画，
 * 空着一行破折号比少一行更让人以为采坏了。外观规格读不到写"其他"。
 */
private fun memoryDetailLines(memory: MemorySnapshot?): List<Pair<String, String>> {

    val data = memory ?: MemorySnapshot()

    return buildList {

        data.speedText()?.let { add("速度" to it) }

        add("已使用的插槽" to data.slotsText())

        add("外观规格" to data.formFactorText())

        add("为硬件保留的内存" to data.reservedText())

        // zram 三个实测数：真读到 mm_stat 才画这一行
        // （没有压缩内存的机器不该出现一串 0 MB 占位，和"速度"同一规矩）
        data.zramDetailText()?.let { add("压缩内存" to it) }

        // 交换空间：已用/总量 一行收（例 0.8/4.0 GB），排在压缩内存下面
        data.swapText()?.let { add("交换空间" to it) }

        // 厂商这一行只在底层真报出来时才有：它来自 dt 的 ddr_manufacturer_id，
        // 读不到或编码不在表里就整行不画（和"速度"同一规矩，不摆破折号占位）
        data.manufacturer?.takeIf { it.isNotBlank() }?.let { add("厂商" to it.trim()) }
    }
}

/**
 * 磁盘页的明细块：固定五行（容量 / 已格式化 / 系统磁盘 / 页面文件 / 类型）。
 *
 * 五行都要有位置：没有数据写破折号，不删行 —— 磁盘只有一块时用户也需要看到
 * "系统磁盘 / 页面文件"这两项是不是读不到，删掉反而像不支持。
 */
private fun diskDetailLines(disk: DiskSnapshot?): List<Pair<String, String>> {

    val data = disk ?: DiskSnapshot()

    return listOf(
        "容量" to data.capacityText(),
        "已格式化" to data.formattedText(),
        "系统磁盘" to data.systemDiskText(),
        "页面文件" to data.pageFileText(),
        "类型" to data.typeText()
    )
}

/**
 * GPU 页左侧的统计格：**两列、最多三行**，版式与**内存页**那套统计区一致
 * （没有强调字、格宽贴着内容走、列间距与内存页同为 9dp/10dp）。
 *
 *   有专用显存：利用率 / 专用 GPU 内存 → GPU 内存 / 共享 GPU 内存 → 温度
 *   无专用显存：利用率 / 共享 GPU 内存 → GPU 内存 / 温度
 *
 * 字号全部走 CpuStatCell 的"非强调"档（titleMedium + bodySmall 标签），
 * 也就是说这一页每个数值都和内存页的"使用中 / 可用 / 已提交"一样大。
 *
 * 「专用 GPU 内存」是**可选**的：管理器没提供（dedicatedTotalBytes 为空）就整格不画，
 * 它那张图也一起不画；剩下的格**整体上移一排**补位，不留空行。
 * 数据下一秒到了，这一格与图会自动长回来，其余的格也跟着下移回去。
 *
 * 格宽只是"最小值"，取的是最长取值的宽度（"0.0/11.9 GB" 这种），
 * 所以数字跳动时格子不抖；因为贴着内容算，两列之间不会空出半屏。
 */
private fun gpuSummaryRows(gpu: GpuSnapshot?): List<List<CpuStat>> {

    val data = gpu ?: GpuSnapshot()

    val hasDedicated = data.dedicatedTotalBytes != null

    fun cell(label: String, value: String, wide: Dp, narrow: Dp): CpuStat =
        CpuStat(label = label, value = value, wideWidth = wide, narrowWidth = narrow)

    val utilization = cell("利用率", data.utilizationText(), 66.dp, 54.dp)

    val dedicated = cell("专用 GPU 内存", data.dedicatedUsageText(), 122.dp, 108.dp)

    val total = cell("GPU 内存", data.totalMemoryUsageText(), 122.dp, 108.dp)

    val shared = cell("共享 GPU 内存", data.sharedUsageText(), 122.dp, 108.dp)

    val temperature = cell("温度", data.temperatureText(), 72.dp, 62.dp)

    return if (hasDedicated) {
        listOf(
            listOf(utilization, dedicated),
            listOf(total, shared),
            listOf(
                // 温度是第二列的项目：借一个与上面第一列同宽的空白格把它推过去，
                // 标题与值都是空串，只占宽不显示任何字
                CpuStat(label = "", value = "", wideWidth = 122.dp, narrowWidth = 108.dp),
                temperature
            )
        )
    } else {
        // 没有专用显存：共享显存补到第一排第二列，温度补到第二排第二列，不留空行
        listOf(
            listOf(utilization, shared),
            listOf(total, temperature)
        )
    }
}

/**
 * GPU 页右侧的明细块（照截图，五行小字）：
 *
 * 驱动程序版本 / 驱动程序日期 / Vulkan/OpenGL 版本 / 物理位置 / 为硬件保留的内存
 *
 * 最后一行是**可选**的：底层没给 reservedBytes 时整行不出现，
 * 与左栏"专用 GPU 内存"同一个口径（没有就不显示，不写 0 MB 冒充）。
 */
private fun gpuDetailLines(gpu: GpuSnapshot?): List<Pair<String, String>> {

    val data = gpu ?: GpuSnapshot()

    return buildList {

        add("驱动程序版本" to data.driverVersionText())

        add("驱动程序日期" to data.driverDateText())

        // Vulkan 与 OpenGL 是两行独立字段：底层报了什么就摆什么。
        // 两样都报了 → 两行都摆；只报一样 → 另一行整行不画；都没报 → 两行都不摆。
        if (data.showsVulkanRow) {
            add("Vulkan 版本" to data.vulkanVersionText())
        }

        if (data.showsOpenGlRow) {
            add("OpenGL ES 版本" to data.openGlVersionText())
        }

        add("物理位置" to data.locationText())

        // 最后一行是可选的：底层没给 reservedBytes 时整行不出现
        if (data.reservedBytes != null) {
            add("为硬件保留的内存" to data.reservedText())
        }
    }
}

/**
 * GPU 页的三张图：3D 利用率（4 份高）、专用显存（2 份）、共享显存（2 份）。
 *
 * 三张都是 0..100% 的区域图，区别只在顶端刻度文案（由调用方的标签行负责）。
 * 显存两条曲线只有在"总量读得到"时才有点：没有分母就不画线，
 * 而不是画一条贴底的 0% 曲线骗人。
 */
@Composable
private fun GpuChartsSection(
    gpu: GpuSnapshot?,
    charts: GpuChartData,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    onSecondChartDoubleTap: () -> Unit,
    modifier: Modifier = Modifier
) {

    // 显存两条图是有数据才画的：没有总量（分母）就画不出占用率，
    // 硬画一张贴底的空图等于告诉用户"显存没用到"，那是假的。
    val hasDedicated = gpu?.dedicatedTotalBytes != null

    // 共享显存那张图**不隐藏**：没有总量时照样画，线就停在 0 点（空队列 → 贴底直线），
    // 顶端文案由 sharedTotalText() 给"不支持"，不会拿一张贴底图冒充"已用 0"。

    Column(modifier = modifier) {

        DetailChartPlaceholder(
            points = charts.utilization,

            fallbackFraction = 0f,

            showKernelTime = false,

            logicalGraph = false,

            onLongPress = onLongPress,

            onDoubleTap = onDoubleTap,

            modifier = Modifier
                .fillMaxWidth()
                .weight(4f)
        )

        if (hasDedicated) {

            GpuChartHeader(
                label = "专用 GPU 内存",

                right = gpu?.dedicatedTotalText() ?: GPU_UNSUPPORTED_TEXT
            )

            DetailChartPlaceholder(
                points = charts.dedicated,

                fallbackFraction = 0f,

                showKernelTime = false,

                logicalGraph = false,

                onLongPress = onLongPress,

                // 第二张图双击也能进摘要（摘要里仍放主图，与磁盘那张速率图同一套约定）
                onDoubleTap = onSecondChartDoubleTap,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
            )
        }

        run {

            GpuChartHeader(
                label = "共享 GPU 内存",

                right = gpu?.sharedTotalText() ?: GPU_UNSUPPORTED_TEXT
            )

            DetailChartPlaceholder(
                points = charts.shared,

                fallbackFraction = 0f,

                showKernelTime = false,

                logicalGraph = false,

                onLongPress = onLongPress,

                onDoubleTap = onSecondChartDoubleTap,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
            )
        }
    }
}

/** 一张图上面的标尺行：左标签（专用 / 共享 GPU 内存），右刻度（那一类的总量）。 */
@Composable
private fun GpuChartHeader(label: String, right: String) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),

        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.weight(1f)
        )

        Text(
            text = right,

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * GPU 视图"复制"写进剪贴板的文本（骨架与磁盘 / 网卡那两份一致）：
 *
 * GPU                          标题行
 * （空行）
 * \t名称:\t适配器名
 * \t利用率:\t…
 * …（所有属性一行都不少，读不到的照页面写「不支持」/「-」）
 */
private fun gpuClipboardText(gpu: GpuSnapshot?): String {

    val data = gpu ?: GpuSnapshot()

    return buildString {

        appendLine("GPU")

        appendLine()

        append("\t名称:\t").appendLine(data.nameText())

        append("\t利用率:\t").appendLine(data.utilizationText())

        append("\t专用 GPU 内存:\t").appendLine(data.dedicatedUsageText())

        append("\t共享 GPU 内存:\t").appendLine(data.sharedUsageText())

        append("\tGPU 内存:\t").appendLine(data.totalMemoryUsageText())

        append("\t驱动程序版本:\t").appendLine(data.driverVersionText())

        append("\t驱动程序日期:\t").appendLine(data.driverDateText())

        if (data.showsVulkanRow) {
            append("\tVulkan 版本:\t").appendLine(data.vulkanVersionText())
        }

        if (data.showsOpenGlRow) {
            append("\tOpenGL ES 版本:\t").appendLine(data.openGlVersionText())
        }

        append("\t物理位置:\t").appendLine(data.locationText())

        append("\t为硬件保留的内存:\t").appendLine(data.reservedText())

        append("\t温度:\t").appendLine(data.temperatureText())
    }
}

/**
 * 网卡页的明细块：无线六行（适配器名称 / SSID / 连接类型 / IPv4 / IPv6 / 信号强度），
 * 手机网络与以太网四行（去掉 SSID 与信号强度）。
 *
 * 顺序与截图一致；取值一律走 [NetworkSnapshot] 的文案方法 ——
 * 底层现在只发适配器名，其余读不到就写"不支持"，接了字段以后这边不用改。
 * "连接类型"由模型层按类型兜底：手机网络写「手机网络」、以太网写「以太网」，
 * 底层真给了值（制式 / 网口标准）就显示那个值。
 */
private fun networkDetailLines(network: NetworkSnapshot?): List<Pair<String, String>> {

    val data = network ?: NetworkSnapshot()

    return buildList {

        add("适配器名称" to data.nameText())

        if (data.showsSsidRow) add("SSID" to data.ssidText())

        add("连接类型" to data.linkTypeText())

        add("IPv4 地址" to data.ipv4Text())

        add("IPv6 地址" to data.ipv6Text())

        if (data.showsSignalRow) add("信号强度" to data.signalText())
    }
}

/**
 * 左栏的一行：迷你趋势图 + 硬件名大字 + 数据小字。
 *
 * 图形尺寸固定 40 x 22：高度贴着三行字（大字 + 两行小字），横向再拉长一截，
 * 让折线有足够的横向跨度画出起伏；这里不做字体缩放的精确测量，省得字放大后图变形。
 */
@Composable
private fun HardwareTab(
    title: String,
    line2: String,
    line3: String,
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
                .padding(horizontal = 6.dp, vertical = 4.dp),

            verticalAlignment = Alignment.CenterVertically
        ) {

            // 宽高比取 40:22：横向拉长之后走势才看得清起伏，
            // 原来是 34:30，几乎是个方块，曲线在里面挤成一团
            MiniSparkline(
                selected = selected,

                history = history,

                modifier = Modifier.size(width = 40.dp, height = 22.dp)
            )

            Spacer(
                modifier = Modifier.width(6.dp)
            )

            Column(
                modifier = Modifier.weight(1f),

                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {

                Text(
                    text = title,

                    // 左栏在横屏只有 1/5 屏宽，titleMedium 撑不下"磁盘 0 + SSD (NVMe) + 0%"三行，
                    // 降到 titleSmall（14sp）再配 9sp 的两行小字，信息才排得开
                    style = MaterialTheme.typography.titleSmall,

                    fontWeight = FontWeight.Bold,

                    color = titleColor,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    // 第二行：CPU 是"利用率 速度"，内存是"用量 (百分比)"，磁盘是类型
                    text = line2,

                    style = MaterialTheme.typography.bodySmall,

                    fontSize = 9.sp,

                    color = detailColor,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    // 第三行：磁盘写活动时间；CPU / 内存传空串，用 minLines 保住这一行的高度，
                    // 这样所有入口的黄框一样高，切换时版面不跳
                    text = line3,

                    style = MaterialTheme.typography.bodySmall,

                    fontSize = 9.sp,

                    color = detailColor,

                    minLines = 1,

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
 * 这里只画总利用率一条线，不跟大图的内核时间 / 逻辑处理器口径走：小图 40dp 宽，
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
    diskData: DiskPageData? = null,
    gpuData: GpuPageData? = null,
    networkData: NetworkSnapshot? = null,
    networkChart: NetworkChartData? = null,
    networkLabel: String = "WLAN",
    summary: Boolean = false,
    onSecondChartDoubleTap: () -> Unit = {},

    modifier: Modifier = Modifier
) {

    val isCpu = kind == HardwareKind.CPU

    val isMemory = kind == HardwareKind.MEMORY

    val isDisk = kind == HardwareKind.DISK

    val isGpu = kind == HardwareKind.GPU

    val disk = diskData?.disk

    // 当前这张显卡（不是 GPU 页就是 null，下面各文案方法都会退回「不支持 / -」）
    val gpu = gpuData?.gpu

    val gpuChart = gpuData?.charts ?: GpuChartData.EMPTY

    val memory = snapshot.memory

    // 当前这块网卡（不是 WLAN 页就是 null，下面各文案方法都会退回"不支持"/0）
    val network = networkData

    // 逻辑处理器口径是 CPU 专属的画法：它的每一格都靠历史点里的逐核数组 (corePercents)，
    // 而内存历史点里那一列恒为空（内存没有"核"这个概念）。直接把它交给内存图，
    // 结果就是一格都画不出来 —— 看起来正是"内存页主图什么都不显示"。
    // 所以口径在这里收口：只有 CPU 认这个开关，内存永远画整机那条曲线。
    val logicalChart = isCpu && logicalGraph

    // 主界面竖屏：图表下方留出呼吸空间（图表 3 : 留白 1，即图表再缩 1/4 后的比例）。
    // 浮窗不留：窗本来就小，把高度全给图表才好看。
    val reserveBottom = !wide && !hostedInOverlay

    // 图表吃"剩下的全部高度"，不再和底部留白按 3 : 1 分 —— 竖屏图表此前就是这么被压小的：
    // 统计块与明细块是固定高度，图上只剩剩余空间的三分之二，而磁盘那页的明细行更少，
    // 于是 CPU / 内存的主图反而比磁盘的活动时间图还矮。
    // 现在：主图 weight 1（唯一可伸缩的子项），底部留白改成固定 8dp，
    // 图表直接拿到剩余高度；磁盘仍是主图 4、速率图 1，比例 4 : 1 不变。
    val chartWeight = if (isDisk) 4f else 1f

    // 摘要视图里仍然是主图 4、速率图 1：4 : 1 这条关系在预览里也成立。
    // 早先的版本会按"被双击的是哪一张"把大头换给那张（3 换 1），
    // 双击速率图进去就变成下面比上面还高 —— 那正是用户看到的"比例不对"。
    // 摘要只是把整块区域放大，不改两张图之间的比例。
    val mainChartWeight = if (isDisk) chartWeight else 1f

    val rateChartWeight = 1f

    // 横屏底部明细：CPU 那三行可变内容（虚拟机 / 虚拟化 / AVF 支持）会把明细块撑高，
    // 而图表拿的是"剩余高度"，明细一高图就跟着矮 —— 用户看到的就是图表变小。
    // 所以这里以上一版的固定 8 行（[CPU_DETAIL_BASELINE_ROWS]）为基准：
    // 多出来的行不再向上挤压图表，图表拿回那一份高度，多出的行由底部裁切。
    // 竖屏不参与，那里图表下面本来留了空白，不是同一套高度分配。
    val detailLines = when (kind) {

        HardwareKind.CPU -> cpuDetailLines(snapshot)

        HardwareKind.MEMORY -> memoryDetailLines(memory)

        HardwareKind.DISK -> diskDetailLines(disk)

        HardwareKind.NETWORK -> networkDetailLines(network)

        // GPU 右侧那五行：驱动 / API / 位置 / 保留内存（保留内存没有就不出现）
        HardwareKind.GPU -> gpuDetailLines(gpu)
    }

    // 统计区的指标：CPU 与内存各一套；磁盘那张是两行四格、且第二行带颜色竖条，
    // 版式不一样，用专用的 DiskSummaryBlock 画，这里不给它排通用行。
    // WLAN 同理（发送 / 接收两张竖条格），用 NetworkSummaryBlock 画。
    val summaryRows = when (kind) {

        HardwareKind.CPU -> cpuSummaryRows(snapshot, usageDecimals)

        HardwareKind.MEMORY -> memorySummaryRows(memory)

        HardwareKind.DISK -> emptyList()

        HardwareKind.NETWORK -> emptyList()

        HardwareKind.GPU -> gpuSummaryRows(gpu)
    }

    val extraDetailRows = if (wide) {
        (detailLines.size - CPU_DETAIL_BASELINE_ROWS).coerceAtLeast(0)
    } else {
        0
    }

    val density = LocalDensity.current

    // 一行明细占的高度：正文行高（bodySmall）+ 3dp 行间距，与 CpuDetailList 的排法一致
    val extraDetailHeight = with(density) {
        (MaterialTheme.typography.bodySmall.lineHeight.toDp() + 3.dp) * extraDetailRows
    }

    // 名称：CPU 用 ELF 给的处理器商品名（由用户侧补实现），取不到才回落占位；
    // 内存写「容量 规格」（如 16.0 GB LPDDR5），与左边大字同一行、同一字号
    val nameText = when (kind) {
        HardwareKind.CPU -> snapshot.cpuName ?: TEST_HARDWARE_NAME
        HardwareKind.MEMORY -> memory?.nameText() ?: "—"
        // 设备名由底层给（暂未实现），管理器只管把值搬上来；没值写破折号
        HardwareKind.DISK -> disk?.nameText() ?: "—"
        // WLAN 的适配器型号由快照带上来；底层没发就写"不支持"
        HardwareKind.NETWORK -> network?.nameText() ?: NETWORK_UNSUPPORTED

        // GPU 的适配器名（底层给到厂商级名字）；没有就写破折号
        HardwareKind.GPU -> gpu?.nameText() ?: "-"
    }

    // 图表上方那行小字：CPU 跟着实时更新速度写明统计窗口；内存这里改写成"内存使用量"
    val chartLabel = when (kind) {
        HardwareKind.CPU -> windowLabel
        HardwareKind.MEMORY -> "内存使用量"
        HardwareKind.DISK -> "活动时间"
        HardwareKind.NETWORK -> "吞吐量"

        // Windows 那张卡第一张图左上角就是 "3D"（3D 引擎占用）
        HardwareKind.GPU -> "3D"
    }

    // 同一行右端：CPU 写 100%（图的顶端就是满利用率）；内存写实际容量（顶端刻度）；
    // WLAN 预留是吞吐量量程（截图里写作 5 Mbps）
    val chartTopRightText = when (kind) {
        HardwareKind.CPU -> "100%"
        HardwareKind.MEMORY -> memory?.totalText() ?: "—"
        HardwareKind.DISK -> "100%"
        HardwareKind.NETWORK -> networkChart?.rangeText() ?: NETWORK_SCALE_TEXT

        HardwareKind.GPU -> gpu?.utilizationText() ?: "-"
    }

    Column(
        modifier = modifier
    ) {
        // 1) 硬件名 + 右侧的硬件名称（名字贴右边缘，和左边的大字分开）
        Row(
            modifier = Modifier.fillMaxWidth(),

            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                // 磁盘是多实例设备：标题写"磁盘 0 / 磁盘 1"，由管理器编号
                text = when {
                    // 磁盘是多实例设备：标题写"磁盘 0 / 磁盘 1"，由管理器编号
                    isDisk -> diskData?.title ?: kind.displayName
                    // 网卡的名字按类型（同类多块才带序号），由管理器算好传进来：WLAN / 手机网络 / 以太网
                    kind == HardwareKind.NETWORK -> networkLabel
                    // 显卡是多实例设备：标题写"GPU 0 / GPU 1"，序号同样由管理器编号
                    kind == HardwareKind.GPU -> gpuData?.title ?: kind.displayName
                    else -> kind.displayName
                },

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
                text = chartTopRightText,

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
        //
        //    WLAN 这张换成"吞吐量"图：横轴同样是固定槽位，但纵轴是速率量程（不是 0..100%），
        //    并且带一条 60% 预警线 —— 版式照截图来，数据这一版全为 0（不铺假曲线）。
        if (isGpu) {

            // 三张图合起来吃满这一块高度（4 : 2 : 2），与 Windows 那一页的比例一致：
            // 3D 利用率最高，两张显存图各占它一半。
            GpuChartsSection(
                gpu = gpu,

                charts = gpuChart,

                onLongPress = onChartLongPress,

                onDoubleTap = onChartDoubleTap,

                onSecondChartDoubleTap = onSecondChartDoubleTap,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(mainChartWeight)
            )

        } else if (kind == HardwareKind.NETWORK) {

            NetworkThroughputChart(
                chart = networkChart ?: NetworkChartData.EMPTY,

                // 长按弹页面菜单：WLAN 这张图此前只接了双击，长按什么都不出
                onLongPress = onChartLongPress,

                onDoubleTap = onChartDoubleTap,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(mainChartWeight)
            )

        } else {

            DetailChartPlaceholder(
                points = history,

                fallbackFraction = when (kind) {

                    HardwareKind.CPU -> usageFraction(snapshot)

                    HardwareKind.MEMORY -> memoryUsageFraction(memory)

                    HardwareKind.DISK -> diskActivityFraction(disk)

                    // 走到这里说明不是 WLAN（WLAN 已在上面分流），这个分支只为了让 when 穷举
                    HardwareKind.NETWORK -> 0f

                    HardwareKind.GPU -> 0f
                },

                // 菜单两项直接决定画法：没勾"显示内核时间"就一行都不多画；
                // 图形口径选了"逻辑处理器"才切成逐核网格，两种口径共用同一份历史。
                // logicalChart 已经把"内存不认这个口径"收口了（见上面那处），
                // 所以内存页哪怕 CPU 那边开着逐核视图，这里拿到的仍是整机曲线。
                showKernelTime = showKernelTime,

                logicalGraph = logicalChart,

                onLongPress = onChartLongPress,

                onDoubleTap = onChartDoubleTap,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(mainChartWeight)
            )

        }

        // WLAN 图底两角：左窗口、右起点 0（截图里就是"60 秒 / 0"）。
        // 其它页的窗口文案在图表标签行的左边（chartLabel），这里标签行写的是"吞吐量"，
        // 所以窗口长度挪到图底，与磁盘那张图同一套落法。
        if (kind == HardwareKind.NETWORK) {

            ChartCornerRow(left = shortWindowText(windowLabel))
        }

        // 磁盘页的第二张图（正式版式与摘要视图都画）：速率图只有主图的 1/4 高。
        // 主图 weight 3、这张 weight 1 —— 比例固定，不随屏高变化。
        if (isDisk) {

            // 主图底部的两角小字：左窗口、右起点 0（与内存图同一套落法）
            ChartCornerRow(left = shortWindowText(windowLabel))

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            // 速率图的表头：左"磁盘传输速率"，右当前量程（默认 100 KB/秒）
            Row(
                modifier = Modifier.fillMaxWidth(),

                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = "磁盘传输速率",

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = diskData?.charts?.rangeText() ?: DiskChartData.EMPTY.rangeText(),

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            // 读取 = 总利用率那条线的样式；写入 = 内核时间那条线的样式；
            // 60% 处一条横线，旁边标注"量程的 60% 是多少"。
            DiskRateChart(
                charts = diskData?.charts ?: DiskChartData.EMPTY,

                // 第二张图也能双击进摘要（摘要里它自己放大）
                onDoubleTap = onSecondChartDoubleTap,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(rateChartWeight)
            )

            ChartCornerRow(left = shortWindowText(windowLabel))
        }

        // 摘要视图到此为止：统计块 / 明细块 / 底部留白都不画，
        // 名称行与标签行留着，所以画面上仍然是这一页的头部，只是下面全部让给图。
        // 内存是例外：它的分条图（内存组合）跟图一起进摘要 —— 那块是这张图的图例，
        // 删掉之后曲线就只剩一条无从对照的折线。图底两角的小字（窗口 / 起点 0）
        // 仍不进摘要，摘要本来就不显示刻度文字。
        // 磁盘的两张图已经在上面画完（速率图就是主图的一部分），这里直接收尾。
        if (summary) {

            if (isMemory) {

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                // 摘要里双击它也退出（和双击主图同一个动作）
                MemoryBreakdownSection(memory, onDoubleTap = onSecondChartDoubleTap)
            }

            return
        }

        // 4) 内存专属两块：图底两角的小字，和一条「使用中 → 可用」的分条图。
        //
        // 左角是更新频率（压短成"60 秒"这种），右角是统计起点 0：CPU 图把这两件事
        // 分别放在左上（窗口文案）与刻度上，内存图按设计把它们落到图底两角。
        if (isMemory) {

            Row(
                modifier = Modifier.fillMaxWidth(),

                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = shortWindowText(windowLabel),

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "0",

                    style = MaterialTheme.typography.bodySmall,

                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            // 标题 + 分条图在完整版式与摘要视图里是同一块，所以抽成一个函数
            MemoryBreakdownSection(memory, onDoubleTap = onSecondChartDoubleTap)
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        // 4) 图表下面的两块
        //    横屏：统计 / 明细 / 预留，仍然三栏，只是统计那一栏略加宽（值文本才不会被省略号吃掉）
        //    窄屏与浮窗：统计 / 明细 并排、**各自一块背景板**，都左对齐，明细紧挨着统计块
        if (wide) {

            // 底部这块的"自然高度"只在第一帧量一次：之后固定成 自然高度 - 多出的明细行，
            // 图表（weight）就能把那一份高度拿回来。超出的行**不裁**：
            // Row 只是占位高度变小，多出来的内容照常往下画，屏幕上能看见多少是多少。
            // 基准高度必须按"当前这一页"重新量：remember 不认页面时，
            // 从内存 / 磁盘切回 CPU 会沿用上一页那个较小的自然高度（内存的明细行少），
            // 于是图表按 weight 把多出来的高度全吃掉，CPU 那 8 行明细被顶到屏幕外。
            // kind 与磁盘序号作 key，换页即作废、重新量一次；同一页内数据变化仍沿用首帧基准。
            var naturalBottomHeight by remember(kind, diskData?.index) { mutableStateOf<Dp?>(null) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        if (naturalBottomHeight == null) {
                            naturalBottomHeight = with(density) { size.height.toDp() }
                        }
                    }
                    .then(
                        naturalBottomHeight
                            ?.takeIf { extraDetailHeight > 0.dp }
                            ?.let { natural ->
                                Modifier
                                    .height((natural - extraDetailHeight).coerceAtLeast(0.dp))
                                    // 关键：让 Row 的子项按**自然高度**测量，Row 自己只占压缩后的高度。
                                    // 只写 height 的话，Column 会把剩余高度用光，最后一行拿到 0 高度约束
                                    // 直接被压成 0 —— 那就是 L3 缓存"一点都看不到"的原因。
                                    // unbounded 测量后，超出的行照常布局、照常绘制，屏幕能显示多少算多少。
                                    .wrapContentHeight(unbounded = true, align = Alignment.Top)
                            } ?: Modifier
                    ),

                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // 统计块与明细块都按内容取宽：明细紧挨在统计块右边，
                // 谁也不会把对方推远，剩下的宽度全部给末尾的空白预留区
                if (isDisk) {

                    DiskSummaryBlock(disk = disk, wide = true)

                } else if (kind == HardwareKind.NETWORK) {

                    NetworkSummaryBlock(network = network, wide = true)

                } else {

                    CpuSummaryBlock(
                        rows = summaryRows,

                        wide = true
                    )
                }

                CpuDetailList(
                    lines = detailLines
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

                    if (isDisk) {

                        DiskSummaryBlock(disk = disk, wide = false)

                    } else if (kind == HardwareKind.NETWORK) {

                        NetworkSummaryBlock(network = network, wide = false)

                    } else {

                        CpuSummaryBlock(
                            rows = summaryRows,

                            wide = false
                        )
                    }
                }

                DataPanel {

                    CpuDetailList(
                        lines = detailLines
                    )
                }
            }
        }

        // 底部呼吸空间：固定 8dp。
        //
        // 原来是 weight 1（与图表 3 : 1），那等于强制把剩余高度再切掉四分之一 ——
        // 竖屏 CPU / 内存的主图就是因此比磁盘的活动时间图还矮。
        // 改成一个很小的固定留白后，图表（weight 1）独占剩余高度。
        if (reserveBottom) {

            Spacer(
                modifier = Modifier.height(8.dp)
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

/** 窗口文案里的"数字 + 秒 / 分钟"，只在这里用；提成常量免得每次组合都新建一个。 */
private val WINDOW_SECONDS_PATTERN = Regex("(\\d+)\\s*(秒|分钟)")

/**
 * 把图表的窗口文案压短，给内存图左下角用。
 *
 * "60 秒内的利用率 %" -> "60 秒"；"4 分钟内的利用率 %" -> "240 秒"（这一处要的是秒数，
 * 不写"4 分钟"）；"利用率 % (已暂停)" -> "已暂停"。
 *
 * 认不出格式时按常规档的 60 秒写：那是最常见的一档，也好过显示一串原始文案。
 */
private fun shortWindowText(windowLabel: String): String {

    if (windowLabel.contains("已暂停")) {
        return "已暂停"
    }

    val match = WINDOW_SECONDS_PATTERN.find(windowLabel)

    val count = match?.groupValues?.get(1)?.toIntOrNull() ?: return "60 秒"

    return if (match.groupValues[2] == "分钟") "${count * 60} 秒" else "$count 秒"
}

/**
 * 「内存组合」标题 + 分条图，整块固定结构。
 *
 * 两处用它：完整版式里排在图表下方，图形摘要视图里也带着它一起走 ——
 * 分条图本身就是内存图的图例，摘要里少了它，上面那条曲线就只剩一条无从对照的折线。
 *
 * 标题字号与上方「内存使用量」同档（bodySmall），只是定住左端、右边留空。
 */
@Composable
private fun MemoryBreakdownSection(
    memory: MemorySnapshot?,
    onDoubleTap: () -> Unit = {}
) {

    Text(
        text = "内存组合",

        style = MaterialTheme.typography.bodySmall,

        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(
        modifier = Modifier.height(3.dp)
    )

    MemoryBreakdownBar(
        parts = (memory ?: MemorySnapshot()).breakdownParts(),

        onDoubleTap = onDoubleTap,

        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
    )
}

/**
 * 内存分条图：一条与上方图表同宽的横条，按「使用中 / 已修改 / 备用 / 可用」切成四段。
 *
 * 四段比例来自数据（[MemorySnapshot.breakdownParts]，已经归一），整条宽度就是总容量；
 * 最右的"可用"不填充主题色，只留一层极淡的底。
 *
 * 按设计只画图形：图上不标任何文字、不加提示，三条竖线就是全部说明。
 */
@Composable
private fun MemoryBreakdownBar(
    parts: List<Float>,
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {

    val usedColor = MaterialTheme.colorScheme.primary

    val modifiedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

    val standbyColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    val freeColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    val dividerColor = MaterialTheme.colorScheme.background

    // 双击进摘要：分条图是主图的图例，预览里放的仍是主图（摘要会把这一条一起带着画）
    val gesture = Modifier.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .then(gesture)
    ) {

        // 底：最右那一段（可用）不填主题色，只铺一层淡底
        drawRect(color = freeColor, size = size)

        var left = 0f

        parts.take(3).forEachIndexed { index, part ->

            val width = size.width * part

            val color = when (index) {
                0 -> usedColor
                1 -> modifiedColor
                else -> standbyColor
            }

            drawRect(color = color, topLeft = Offset(left, 0f), size = Size(width, size.height))

            // 每段末尾一条竖线：三条线正好把四段分开
            drawLine(
                color = dividerColor,

                start = Offset(left + width, 0f),

                end = Offset(left + width, size.height),

                strokeWidth = 1.dp.toPx()
            )

            left += width
        }
    }
}

/**
 * 统计区最左块：三行"小字标题 + 标题下的大字数据"。
 *
 * 行内横排、行间竖排；每行的指标数量不同（2 / 3 / 1 或 2），所以用数据驱动而不是写死三个 Row。
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
 * 一行等宽方块只要文字，宽度才够；迷你图在这个尺寸下也读不出信息。
 *
 * 选中项用主色容器着色，和宽屏那一列的视觉语言保持一致。
 *
 * 条本身是**横向滚动**、每格固定 76dp：设备一多（CPU + 内存 + 4 块盘），
 * 平分宽度会把每格压到 50dp 出头，"磁盘 3" 就只剩省略号了。
 * 横滑之后格子尺寸恒定，多出来的设备往右拖就能看到；按用户要求不画滚动条。
 */
@Composable
private fun HardwareCompactBar(
    slots: List<HardwareSlot>,
    selectedKind: HardwareKind,
    selectedDiskIndex: Int,
    onSelect: (HardwareSlot) -> Unit,
    modifier: Modifier = Modifier
) {

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),

        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {

        slots.forEach { slot ->

            val isSelected = slot.kind == selectedKind && slot.diskIndex == selectedDiskIndex

            Box(
                modifier = Modifier
                    .width(PERFORMANCE_COMPACT_TAB_WIDTH)
                    .height(PERFORMANCE_COMPACT_TAB_HEIGHT)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        }
                    )
                    .clickable { onSelect(slot) },

                contentAlignment = Alignment.Center
            ) {

                Text(
                    // 竖屏横条用短标题：磁盘仍是"磁盘 0"
                    text = slot.shortTitle,

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
 * 图底两角的小字：左边写窗口长度（压成"60 秒"），右边写统计起点 0。
 *
 * 磁盘页的两张图与内存页用同一套落法，避免同一种信息在三张图上三种位置。
 */
/**
 * 左栏的细滚动条（只在横屏用）。
 *
 * Compose 的 `verticalScroll` 在 Android 上不画任何滚动条，设备多了只能靠"拖动试试"，
 * 所以这里按 [ScrollState] 自己画一条：轨道铺满高度，滑块长度 = 可见比例、位置 = 滚动进度。
 * 不需要滚动时（`maxValue <= 0`）直接什么都不画，正常两台设备时看不到这条线。
 */
@Composable
private fun HardwareScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier
) {

    if (state.maxValue <= 0 || state.viewportSize <= 0) return

    val total = state.viewportSize.toFloat() + state.maxValue.toFloat()

    // 滑块最短也留 8%，否则设备特别多时滑块细得点不着
    val visible = (state.viewportSize.toFloat() / total).coerceIn(0.08f, 1f)

    val progress = (state.value.toFloat() / state.maxValue.toFloat()).coerceIn(0f, 1f)

    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)

    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Canvas(
        modifier = modifier.clip(RoundedCornerShape(2.dp))
    ) {

        drawRoundRect(
            color = trackColor,

            size = size,

            cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
        )

        val thumbHeight = size.height * visible

        drawRoundRect(
            color = thumbColor,

            topLeft = Offset(0f, (size.height - thumbHeight) * progress),

            size = Size(size.width, thumbHeight),

            cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
        )
    }
}

/**
 * 图底两角的小字：左边写窗口长度（压成"60 秒"），右边写统计起点 0。
 */
@Composable
private fun ChartCornerRow(left: String) {

    Row(
        modifier = Modifier.fillMaxWidth(),

        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = left,

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "0",

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 磁盘传输速率图（主图的 1/4 高）。
 *
 * 两条曲线两种样式，和 CPU 那张大图的语言一致：
 * - 读取 = 总利用率那条线（主色实线 + 淡色填充）；
 * - 写入 = 内核时间那条线（更暗的线 + 更暗的填充，画在下层）。
 *
 * 纵轴不是固定 100%：量程由管理器按"超过一半就升档、5 秒都低于 10% 就降档"维护，
 * 传进来的点已经按当前量程归一化，所以这里只管按 0..100 画。
 *
 * 60% 处画一条横线并标注"量程的 60% 是多少"——这条线是升档的预警线：
 * 曲线顶到它，下一帧量程就会抬一档。
 */
@Composable
private fun DiskRateChart(
    charts: DiskChartData,
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val topLineColor = MaterialTheme.colorScheme.outline
    val readColor = MaterialTheme.colorScheme.primary
    val writeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    val background = MaterialTheme.colorScheme.surfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 只有双击：这张图上没有长按菜单（菜单里的两项都是主图的口径）
    val gesture = Modifier.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }

    Box(modifier = modifier.then(gesture)) {

        Canvas(modifier = Modifier.fillMaxSize()) {

            drawRoundRect(
                color = background.copy(alpha = 0.35f),

                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )

            val rows = 4

            for (index in 0..rows) {

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

            val yOf: (Float) -> Float = { percent ->
                size.height - inset - usable * (percent.coerceIn(0f, 100f) / 100f)
            }

            val slotWidth = size.width / (PERFORMANCE_CHART_POINTS - 1).toFloat()

            val readVisible = charts.read.takeLast(PERFORMANCE_CHART_POINTS)

            val writeVisible = charts.write.takeLast(PERFORMANCE_CHART_POINTS)

            // 两条曲线是同一时刻成对入队的，槽位由读取那条来定，写入沿用同一个起点
            val startSlot = PERFORMANCE_CHART_POINTS - readVisible.size

            if (readVisible.size < 2 && writeVisible.size < 2) {

                // 还没攒够线：两条都只点一个点（贴右边缘），不铺一条假线
                drawCircle(
                    color = readColor,
                    radius = 2.dp.toPx(),
                    center = Offset(
                        size.width - 2.dp.toPx(),
                        yOf(readVisible.lastOrNull()?.percent ?: 0f)
                    )
                )

                drawCircle(
                    color = writeColor,
                    radius = 2.dp.toPx(),
                    center = Offset(
                        size.width - 2.dp.toPx(),
                        yOf(writeVisible.lastOrNull()?.percent ?: 0f)
                    )
                )

                return@Canvas
            }

            // 写入先画（下层色带），读取后画（上层曲线）
            if (writeVisible.size >= 2) {

                drawKernelBand(
                    percents = writeVisible.map { it.percent },

                    startSlot = startSlot,

                    slotWidth = slotWidth,

                    left = 0f,

                    bottom = size.height - inset,

                    yOf = yOf,

                    fill = writeColor.copy(alpha = 0.28f),

                    stroke = writeColor
                )
            }

            if (readVisible.size >= 2) {

                val path = Path()

                readVisible.forEachIndexed { index, point ->

                    val x = (startSlot + index) * slotWidth

                    val y = yOf(point.percent)

                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                // 淡填充：与内核色带区分开，同时让两条线在 1/4 高度的小图里也能分清
                val fillPath = Path()

                fillPath.addPath(path)

                fillPath.lineTo(
                    (startSlot + readVisible.size - 1) * slotWidth,
                    size.height - inset
                )

                fillPath.lineTo(startSlot * slotWidth, size.height - inset)

                fillPath.close()

                drawPath(fillPath, color = readColor.copy(alpha = 0.16f))

                drawPath(
                    path = path,

                    color = readColor,

                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(),

                        cap = StrokeCap.Round,

                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
        }

        // 60% 预警线：0.4 的顶部空白 = 60% 处（0 在底、100 在顶）
        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            Spacer(
                modifier = Modifier.weight(0.4f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),

                verticalAlignment = Alignment.CenterVertically
            ) {

                HorizontalDivider(
                    modifier = Modifier.weight(1f),

                    thickness = 1.dp,

                    color = markerColor.copy(alpha = 0.45f)
                )

                Text(
                    text = charts.sixtyPercentText(),

                    style = MaterialTheme.typography.bodySmall,

                    fontSize = 9.sp,

                    color = markerColor,

                    maxLines = 1,

                    modifier = Modifier.padding(start = 4.dp, end = 2.dp)
                )
            }

            Spacer(
                modifier = Modifier.weight(0.6f)
            )
        }
    }
}

/**
 * 磁盘页的统计块：两行四格。
 *
 * 第一行是"忙不忙"（活动时间 / 平均响应时间）；第二行是吞吐（读取 / 写入速度），
 * 两格左边各有一条竖线，颜色直接借用两张曲线的颜色：读取 = 主色，写入 = 内核线的暗色。
 * 竖线让"这格数值对应图上哪条线"一眼就能对上，不用再去看图例。
 */
@Composable
private fun DiskSummaryBlock(
    disk: DiskSnapshot?,
    wide: Boolean
) {

    val data = disk ?: DiskSnapshot()

    val readColor = MaterialTheme.colorScheme.primary

    val writeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        Row(
            horizontalArrangement = Arrangement.spacedBy(if (wide) 18.dp else 12.dp),

            verticalAlignment = Alignment.Top
        ) {

            DiskStatCell("活动时间", data.activityText(), wide)

            DiskStatCell("平均响应时间", data.responseText(), wide)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(if (wide) 18.dp else 12.dp),

            verticalAlignment = Alignment.Top
        ) {

            DiskStatCell("读取速度", data.readSpeedText(), wide, readColor)

            DiskStatCell("写入速度", data.writeSpeedText(), wide, writeColor)
        }
    }
}

/** 统计块里的一格：可选的一条竖色线 + 小字标题 + 正文数据。 */
@Composable
private fun DiskStatCell(
    label: String,
    value: String,
    wide: Boolean,
    barColor: Color? = null
) {

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {

        if (barColor != null) {

            // 竖线横跨"标题 + 正文"高度，看得见但不抢字
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(26.dp)
                    .background(barColor, RoundedCornerShape(1.dp))
            )

            Spacer(
                modifier = Modifier.width(6.dp)
            )
        }

        Column(
            modifier = Modifier.widthIn(min = if (wide) 104.dp else 86.dp)
        ) {

            Text(
                text = label,

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = value,

                style = MaterialTheme.typography.bodyMedium,

                color = MaterialTheme.colorScheme.onSurface,

                maxLines = 1,

                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * WLAN 页的统计块：发送 / 接收两格竖排，每格左边一条竖色线（颜色对应后续图里的两条曲线）。
 *
 * 版式照截图：小字标签在上、大字号数值在下；取值走 [NetworkSnapshot] 的 sendText / receiveText ——
 * 当前底层没有计数，两者都是 `0 Kbps`，接了计数以后这边不用改。
 * 竖线让"这格数对应图里哪条线"一眼能对上，和磁盘统计块同一套语言。
 */
@Composable
private fun NetworkSummaryBlock(network: NetworkSnapshot?, wide: Boolean) {

    val data = network ?: NetworkSnapshot()

    val sendColor = MaterialTheme.colorScheme.error

    val receiveColor = MaterialTheme.colorScheme.primary

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        NetworkStatCell("发送", data.sendText(), sendColor, wide)

        NetworkStatCell("接收", data.receiveText(), receiveColor, wide)
    }
}

/** WLAN 统计块里的一格：竖色线 + 小字标签 + 大字号数值。 */
@Composable
private fun NetworkStatCell(
    label: String,
    value: String,
    barColor: Color,
    wide: Boolean
) {

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .background(barColor, RoundedCornerShape(1.5.dp))
        )

        Spacer(
            modifier = Modifier.width(8.dp)
        )

        Column(
            modifier = Modifier.widthIn(min = if (wide) 96.dp else 78.dp)
        ) {

            Text(
                text = label,

                style = MaterialTheme.typography.bodySmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = value,

                style = MaterialTheme.typography.titleMedium,

                color = MaterialTheme.colorScheme.onSurface,

                maxLines = 1,

                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 无线网卡的吞吐量图（本页的主图表）。
 *
 * 版式照任务管理器的网络页：横轴与其它图共用 [PERFORMANCE_CHART_POINTS] 个固定槽位
 * （新点从右进、老点从左挤出，所以换更新速度不会换图的宽度）；
 * 纵轴不是 0..100%，而是"速率量程"—— 右上角那行写的就是当前量程，
 * 图里 60% 处一条预警线并标出该处的速率（都是管理器算好的文案）。
 *
 * 两条曲线：
 * - 接收 = 主色（紫），画在上层，带淡填充；
 * - 发送 = 错误色（粉），画在下层，带淡填充。
 * 与左栏统计块那两条竖线是同一组颜色，看一眼就能把"哪个数对应哪条线"对上。
 *
 * 数据为空（刚进页面 / 这一帧还没基准）时只画网格与预警线，不铺假曲线。
 */
@Composable
private fun NetworkThroughputChart(
    chart: NetworkChartData,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val topLineColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.error
    val background = MaterialTheme.colorScheme.surfaceVariant
    val receiveColor = MaterialTheme.colorScheme.primary
    val sendColor = MaterialTheme.colorScheme.error

    // 双击进摘要；长按与其它图表一样弹页面菜单（复制 / 资源监视器）
    val gesture = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onDoubleTap = { onDoubleTap() },

            onLongPress = { onLongPress() }
        )
    }

    Box(modifier = modifier.then(gesture)) {

        Canvas(modifier = Modifier.fillMaxSize()) {

            drawRoundRect(
                color = background.copy(alpha = 0.35f),

                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )

            // 横向网格：最上面那条是量程顶端，画得更深一点（与其它两张图同一套语言）
            val rows = 4

            for (index in 0..rows) {

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

            val yOf: (Float) -> Float = { percent ->
                size.height - inset - usable * (percent.coerceIn(0f, 100f) / 100f)
            }

            val slotWidth = size.width / (PERFORMANCE_CHART_POINTS - 1).toFloat()

            val receiveVisible = chart.receive.takeLast(PERFORMANCE_CHART_POINTS)

            val sendVisible = chart.send.takeLast(PERFORMANCE_CHART_POINTS)

            // 两条曲线同一时刻成对入队：槽位由接收那条定，发送沿用同一个起点
            val startSlot = PERFORMANCE_CHART_POINTS - receiveVisible.size

            if (receiveVisible.isEmpty() && sendVisible.isEmpty()) {
                return@Canvas
            }

            // 发送先画（下层），接收后画（上层）
            if (sendVisible.size >= 2) {

                val path = Path()

                sendVisible.forEachIndexed { index, point ->

                    val x = (startSlot + index) * slotWidth

                    val y = yOf(point.percent)

                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                val fillPath = Path()

                fillPath.addPath(path)

                fillPath.lineTo(
                    (startSlot + sendVisible.size - 1) * slotWidth,
                    size.height - inset
                )

                fillPath.lineTo(startSlot * slotWidth, size.height - inset)

                fillPath.close()

                drawPath(fillPath, color = sendColor.copy(alpha = 0.14f))

                drawPath(
                    path = path,

                    color = sendColor.copy(alpha = 0.85f),

                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.2.dp.toPx(),

                        cap = StrokeCap.Round,

                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }

            if (receiveVisible.size >= 2) {

                val path = Path()

                receiveVisible.forEachIndexed { index, point ->

                    val x = (startSlot + index) * slotWidth

                    val y = yOf(point.percent)

                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                val fillPath = Path()

                fillPath.addPath(path)

                fillPath.lineTo(
                    (startSlot + receiveVisible.size - 1) * slotWidth,
                    size.height - inset
                )

                fillPath.lineTo(startSlot * slotWidth, size.height - inset)

                fillPath.close()

                drawPath(fillPath, color = receiveColor.copy(alpha = 0.18f))

                drawPath(
                    path = path,

                    color = receiveColor,

                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(),

                        cap = StrokeCap.Round,

                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
        }

        // 60% 预警线：0.4 的顶部空白 = 60% 处（0 在底、量程顶端在顶）
        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            Spacer(
                modifier = Modifier.weight(0.4f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),

                verticalAlignment = Alignment.CenterVertically
            ) {

                HorizontalDivider(
                    modifier = Modifier.weight(1f),

                    thickness = 1.dp,

                    color = markerColor.copy(alpha = 0.55f)
                )

                Text(
                    text = chart.sixtyPercentText(),

                    style = MaterialTheme.typography.bodySmall,

                    fontSize = 9.sp,

                    color = MaterialTheme.colorScheme.onSurfaceVariant,

                    maxLines = 1,

                    modifier = Modifier.padding(start = 4.dp, end = 2.dp)
                )
            }

            Spacer(
                modifier = Modifier.weight(0.6f)
            )
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
 * 四项现状（按硬件裁剪：内核那两项只在 CPU 页出现）：
 * 1) 内核图形口径：选得中、记得住；【逻辑处理器】那条曲线要等逐核数据接到 UI 才真画出来；
 * 2) 显示内核时间：状态留着，画法待定（内核时间还没采集）；
 * 3) 图形摘要视图：整页只剩图表（内存页还会带上「内存组合」分条图），双击图表退出；
 * 4) 查看：固定六格（CPU/内存、磁盘/网络、GPU/NPU），点哪一格就把本页切到那一类的
 *    第一个实例（磁盘 -> 磁盘 0）；这一类没有实例时该项置灰，NPU 暂无采集也置灰；
 * 5) 复制：把当前硬件的复制文本写进系统剪贴板。CPU 走 [cpuClipboardText]，
 *    内存走 [memoryClipboardText]。
 *
 * 磁盘 / WLAN / GPU 的入口按"类型 + 第一个实例"回到了查看列表；复制文本仍只做 CPU / 内存。
 *
 * 为什么内存页不摆 1) 和 2)：内存既没有总体利用率 / 逻辑处理器这两个口径，
 * 也没有内核时间这一说，摆上去只会在点的时候毫无反应。
 */
@Composable
private fun PerformanceChartDialog(
    hostedInOverlay: Boolean,
    kind: HardwareKind,
    kernelGraphMode: String,
    showKernelTime: Boolean,
    onKernelGraphModeChange: (String) -> Unit,
    onShowKernelTimeChange: (Boolean) -> Unit,
    onEnterSummary: () -> Unit,
    viewTargets: List<ChartViewTarget>,
    onViewNetworkDetail: () -> Unit,
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

            // 内核图形与内核时间是 CPU 专属：内存没有总体利用率、没有逻辑处理器，
            // 也没有内核时间，这三行在内存页整组不画
            if (kind == HardwareKind.CPU) {

                ChartMenuHeading("将图形更改为(H)")

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
            }

            ChartMenuRow(
                label = "图形摘要视图(W)"
            ) { onEnterSummary() }

            ChartMenuRow(
                label = "查看(V)",

                trailing = if (viewExpanded) "▾" else "▸"
            ) { viewExpanded = !viewExpanded }

            if (viewExpanded) {

                // 一行两列、固定三行：CPU/内存、磁盘/网络、GPU/NPU。
                // 点一项是真的切过去（切到该类第一个实例），不是只关菜单。
                viewTargets.chunked(2).forEach { pair ->

                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        pair.forEach { target ->

                            ChartMenuTarget(
                                label = target.label,
                                modifier = Modifier.weight(1f),
                                enabled = target.enabled
                            ) {
                                target.select()
                                onDismiss()
                            }
                        }

                        // 奇数项时补一个空位，避免最后一项被拉成整行宽
                        if (pair.size == 1) {
                            Spacer(
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 网络适配器专属：查看网络详细信息。与「查看(V)」同属一组（中间不画分隔线），
            // 位置在「查看」下、分隔线之上，所以「复制(C)」仍在它下面那一组里。
            if (kind == HardwareKind.NETWORK) {

                ChartMenuRow(
                    label = "查看网络详细信息(D)"
                ) {
                    onViewNetworkDetail()

                    onDismiss()
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

/** 查看列表里的一项：标签 + 能不能点（这一类一个实例都没读到时置灰）；[select] 切到该类的第一个实例。 */
private data class ChartViewTarget(
    val label: String,
    val enabled: Boolean = true,
    val select: () -> Unit
)

/**
 * 查看展开后的一个目标（CPU / 内存 / 磁盘 / 网络 / GPU / NPU 之一）。
 *
 * 点它只把本页的选中设备切成那一类的第一个实例，不跳别的页面，所以不需要"切页接口"。
 * [enabled] = false 的项不响应点击，文字压暗，表示这一类当前没有设备可切。
 */
@Composable
private fun ChartMenuTarget(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {

    Row(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),

        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,

            style = MaterialTheme.typography.bodySmall,

            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },

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

/**
 * 网络适配器的「网络详细信息」页（占位版）。
 *
 * 版式不在这里画：标题栏 + 分隔线 + 可自由摆放控件的工作区，统一走 [DetailCanvasPage]
 * （性能页那个「设备详细信息」入口用的是同一套皮，改一处两边都跟着变）。
 * 工作区中央那张写着「测试页面」的卡片就是现在的全部内容，按住可以拖到任意位置。
 *
 * 与主界面右上角那个「设备详细信息」入口不是同一个页面：这条入口只有网卡页的长按菜单。
 */
@Composable
private fun NetworkDetailCanvasPage(onClose: () -> Unit) {

    DetailCanvasPage(
        title = "网络详细信息",

        onBack = onClose,

        backDescription = "返回性能页"
    )
}
