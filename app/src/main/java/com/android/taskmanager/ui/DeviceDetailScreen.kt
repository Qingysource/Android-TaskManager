/**
 * ============================================================
 * 设备详细信息页
 * ============================================================
 *
 * 入口：性能页动作区右上角的 Info 图标（[PerformanceDetailEntry]）。
 * 版式骨架走共用的 [DetailCanvasPage]（48dp 标题栏 + 分隔线 + 工作区），
 * 与网卡页长按菜单里的「网络详细信息」是同一套皮。
 *
 * 页面内容（原「CPU 扩展信息」页整体搬进来，从上到下）：
 *   1) 处理器扩展信息：按集群分区，每个集群下每颗逻辑核一个圆角 chip，
 *      chip 里写利用率、当前频率、最大频率、最小频率、设置的最大频率；
 *   2) 核心 Chips：图形下面一排小圆角 chip，点一颗就选中它，
 *      对应的核 chip 变高亮，详情区跟着换成那一颗核；
 *   3) 选中核心详情：把这一颗核的六项逐行写清（没值的格子连原因一起写）。
 *
 * 每处名称（分区标题 / 核 chip / 选择 chip / 详情标题）前面都留了一个方形图标槽，
 * 位置和尺寸是固定的，专门放进程图标；现在设备扩展快照只到"核"这一层、
 * 不带"哪个进程跑在这颗核上"，所以槽里暂时是空的（只有一圈细边），
 * 等进程数据接进来直接往槽里画图，不用再动版式。
 *
 * 三条硬约束：
 * - **不画图、不做统计**：不做历史曲线、不算平均值 / 峰值，只显示这一帧；
 * - **刷新跟设置页**：间隔取 UIService.performancePollIntervalMs()，
 *   即设置页「实时更新速度」那一档（高 0.5s / 常规 1s / 低 4s / 已暂停 0）；
 * - **数据来自设备扩展快照（typeCode=6）**，不是设备快照（typeCode=5）——
 *   后者是整颗处理器一台设备，没有逐核粒度。
 *
 * 为什么刷新循环写在本页而不是服务里：本页只有"当前一帧"要显示，
 * 没有历史可攒（见第一条约束），而且离开本页就该立刻停 ——
 * 页面自己计时最直接，也不给后台轮询加一份常驻负担。
 * 前台门禁用 UIService.isUiRefreshAllowed()：看不见就不发请求。
 */
package com.android.taskmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.android.taskmanager.data.CpuClusterExt
import com.android.taskmanager.data.CpuCoreExt
import com.android.taskmanager.data.CpuExtendedSnapshot
import com.android.taskmanager.service.UIService

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 核 chip 的最小宽度：五行小字（标题 + 利用率 + 四条频率）撑得开的下限。 */
private val CORE_CHIP_MIN_WIDTH = 152.dp

/** 同一行里两个 chip 之间的间距（也用于换行计算的补偿）。 */
private val CORE_CHIP_GAP = 8.dp

/** 底部选择用小 chip 的最小宽度。 */
private val CORE_PILL_MIN_WIDTH = 88.dp

/** 选中态描边宽度：与未选中的 1dp 细边区分得开，又不至于让方块跳动。 */
private val CORE_CHIP_SELECTED_BORDER = 2.dp

/** chip / 分区卡片统一的圆角。 */
private val CHIP_CORNER = 12.dp

/**
 * 名称前面给进程图标留出的方形槽位（核 chip / 分区标题 / 详情标题 / 选择 chip 通用）。
 *
 * 现在还没有"哪颗核上跑着哪个进程"的数据（设备扩展快照 typeCode=6 只到核，不带进程），
 * 所以这里先只留位置：画一个定尺空槽，等数据接进来把图标填进去，
 * 填空不会让文字左右跳动、也不会改变 chip 的列数。
 */
private val ICON_SLOT = 18.dp

/** 选择用小 chip 里的槽位（整体更小一号，跟它 10sp 的字对齐）。 */
private val ICON_SLOT_SMALL = 14.dp

/** 名称与图标槽之间的间距。 */
private val ICON_SLOT_GAP = 6.dp

/**
 * 进程图标的预留槽。
 *
 * 为什么画成一圈虚线感的细边框而不是纯空白：空白看起来像排版缺了一块；
 * 有边框才看得出"这是留给图标的位置"。选中态换成主色边，跟 chip 一起高亮。
 * 将来有图标就把它画在这个槽里（尺寸固定 18dp，不再改布局）。
 */
@Composable
private fun ProcessIconSlot(
    size: Dp,
    selected: Boolean = false
) {

    val outline = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .border(width = 1.dp, color = outline, shape = RoundedCornerShape(4.dp))
    )
}


/**
 * 设备详细信息页。
 *
 * 整页独显（导航栏 / 页名行 / 顶栏都会让位），所以安全区 inset 由调用方补
 * （见 MainActivity 里那段 windowInsetsPadding）。
 *
 * @param onBack 点标题栏返回箭头要做什么（关掉这一页，回到原来的页面）
 */
@Composable
internal fun DeviceDetailPage(onBack: () -> Unit) {

    // 服务是异步创建的：起始页若正好是本页，组合首帧往往取不到实例，
    // 而取过一次之后本页没有别的状态会变、也就不会重组 —— 所以拿不到就一直等。
    val service by produceState<UIService?>(initialValue = UIService.instanceOrNull()) {

        while (value == null) {

            delay(150)

            value = UIService.instanceOrNull()
        }
    }

    // 当前一帧；null = 还没采到（首屏 / 后端未就绪 / 老后端不支持 typeCode=6）
    var frame by remember { mutableStateOf<CpuExtendedSnapshot?>(null) }

    // 设置页那一档对应的间隔（毫秒）；0 = 已暂停
    var intervalMs by remember { mutableStateOf(0L) }

    // 没数据时的一句话说明：只写事实（后端没就绪 / 没报告核心），不猜原因
    var emptyNote by remember { mutableStateOf("正在读取…") }

    // 选中的逻辑核号；null = 还没点过，默认用第一颗
    var selectedCoreId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(service) {

        val svc = service ?: return@LaunchedEffect

        while (true) {

            // 每轮都重新读设置：用户在设置页换了档位，回来这一页不用重建也会跟上
            val interval = svc.performancePollIntervalMs()

            intervalMs = interval

            if (interval > 0L && svc.isUiRefreshAllowed()) {

                // 一次请求要读 /proc/stat 与十几个 sysfs 节点，放到 IO 线程，别占主线程
                val snapshot = withContext(Dispatchers.IO) { svc.cpuExtendedSnapshot() }

                if (snapshot != null) {

                    frame = snapshot

                    emptyNote = ""
                } else if (frame == null) {

                    emptyNote = "还没有数据：后端未就绪，或这台后端不支持设备扩展快照（typeCode=6）"
                }
            }

            // 已暂停时也继续转，只是不采：用户在设置页从"已暂停"改回"高"，
            // 这一页不用退出重进就会自己恢复
            delay(if (interval > 0L) interval else 1_000L)
        }
    }

    val cores = frame?.cores ?: emptyList()

    // 分区表：正常由后端按 CPU_CLUSTER 设备给；万一它只发了核心没发分区
    // （老后端、或分区那几行被丢），就用核心自带的 cluster 号补一份，
    // 保证"每颗核准能归到一个分区里显示"，不出现有核却没有分区的空白
    val clusters = frame?.clusters.orEmpty().ifEmpty {

        cores.map { it.cluster }
            .distinct()
            .sorted()
            .map { index ->
                CpuClusterExt(
                    index = index,
                    policy = cores.firstOrNull { it.cluster == index }?.policy,
                    coreIds = cores.filter { it.cluster == index }.map { it.id }
                )
            }
    }

    // 选中的核：点过就是它；没点过 / 那颗核下线了就退回第一颗
    val selected = cores.firstOrNull { it.id == selectedCoreId } ?: cores.firstOrNull()

    DetailCanvasPage(
        title = "设备详细信息",

        onBack = onBack,

        // 不摆那张可拖动的占位卡片：工作区全是真控件
        cardText = "",

        content = {

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {

                // 一行能放几个核 chip：按可用宽度算，宽屏最多四个。
                // 手算列数而不是用 FlowRow：FlowRow 每行高度自动撑开，
                // chip 内容行数固定时没必要，算出来反而更好对齐。
                val chipColumns = ((maxWidth.value + CORE_CHIP_GAP.value) /
                    (CORE_CHIP_MIN_WIDTH.value + CORE_CHIP_GAP.value))
                    .toInt()
                    .coerceIn(1, 4)

                // 下面那排小 chip 更窄，一行能放的比核 chip 多一倍
                val pillPerRow = ((maxWidth.value + CORE_CHIP_GAP.value) /
                    (CORE_PILL_MIN_WIDTH.value + CORE_CHIP_GAP.value))
                    .toInt()
                    .coerceIn(1, 8)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {

                    RefreshNote(intervalMs = intervalMs, hasService = service != null)

                    Spacer(modifier = Modifier.height(12.dp))

                    // 第一项：处理器扩展信息
                    SectionTitle("处理器扩展信息")

                    Spacer(modifier = Modifier.height(8.dp))

                    if (cores.isEmpty()) {

                        EmptyHint(text = emptyNote.ifEmpty { "还没有数据" })
                    } else {

                        // 分区顺序按簇序号：Cluster 0 在最上面
                        clusters.forEach { cluster ->

                            ClusterSection(
                                cluster = cluster,
                                cores = cores.filter { it.cluster == cluster.index }
                                    .ifEmpty {
                                        cluster.coreIds.mapNotNull { id ->
                                            cores.firstOrNull { it.id == id }
                                        }
                                    },
                                columns = chipColumns,
                                selectedCoreId = selected?.id,
                                onSelect = { selectedCoreId = it }
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 第二项：图形下面那排选择用的小 chips
                    SectionTitle("核心 Chips")

                    Spacer(modifier = Modifier.height(8.dp))

                    if (cores.isNotEmpty()) {

                        CoreSelectorPills(
                            cores = cores,
                            selectedCoreId = selected?.id,
                            perRow = pillPerRow,
                            onSelect = { selectedCoreId = it }
                        )
                    } else {

                        EmptyHint(text = "暂无核心")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 第三项：选中核心的详情（每个字段连"为什么没值"一起写）
                    SectionTitle("选中核心详情")

                    Spacer(modifier = Modifier.height(8.dp))

                    if (selected != null) {

                        SelectedCoreDetail(
                            core = selected,
                            cluster = clusters.firstOrNull { it.index == selected.cluster }
                        )
                    } else {

                        EmptyHint(text = "点上面任意一颗核心")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    )
}

/** 顶部一行：这一页现在按什么节奏刷新（数值来自设置页，不在这里写死）。 */
@Composable
private fun RefreshNote(intervalMs: Long, hasService: Boolean) {

    val text = when {

        !hasService -> "正在连接服务…"

        intervalMs <= 0L -> "实时更新：已暂停（设置页的更新速度为「已暂停」时不采集）"

        else -> "实时更新：每 " + formatInterval(intervalMs) + " 刷新一次"
    }

    Text(
        text = text,

        style = MaterialTheme.typography.bodySmall,

        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 间隔转文案：0.5 秒 / 1 秒 / 4 秒 —— 只在这几个档位出现，不做通用换算。 */
private fun formatInterval(intervalMs: Long): String =
    if (intervalMs % 1000L == 0L) "${intervalMs / 1000L} 秒" else "%.1f 秒".format(intervalMs / 1000.0)

/** 分区 / 详情区的小标题。 */
@Composable
private fun SectionTitle(text: String) {

    Text(
        text = text,

        style = MaterialTheme.typography.titleSmall,

        fontWeight = FontWeight.SemiBold
    )
}

/** 没有数据时的一块说明，不画空壳。 */
@Composable
private fun EmptyHint(text: String) {

    Surface(
        shape = RoundedCornerShape(CHIP_CORNER),

        color = MaterialTheme.colorScheme.surfaceVariant
    ) {

        Text(
            text = text,

            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 一个集群分区：标题 + 说明 + 该簇下的核 chip（按列数换行）。
 *
 * @param cores 这一簇的核；空表时整段不画（后端没报这一簇的成员，摆空框没意义）
 */
@Composable
private fun ClusterSection(
    cluster: CpuClusterExt,
    cores: List<CpuCoreExt>,
    columns: Int,
    selectedCoreId: Int?,
    onSelect: (Int) -> Unit
) {

    if (cores.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),

            verticalAlignment = Alignment.CenterVertically
        ) {

            // 名称前面的图标位（进程图标将来画在这里）
            ProcessIconSlot(size = ICON_SLOT)

            Spacer(modifier = Modifier.width(ICON_SLOT_GAP))

            Text(
                text = cluster.title(),

                style = MaterialTheme.typography.bodyMedium,

                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = cluster.subtitle(),

                style = MaterialTheme.typography.labelSmall,

                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        cores.chunked(columns).forEach { rowCores ->

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {

                rowCores.forEach { core ->

                    CoreChip(
                        core = core,
                        selected = core.id == selectedCoreId,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = CORE_CHIP_GAP),
                        onSelect = onSelect
                    )
                }

                // 这一行没排满时补空位，免得最后一颗被拉成整行宽
                repeat(columns - rowCores.size) {

                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = CORE_CHIP_GAP)
                    )
                }
            }

            Spacer(modifier = Modifier.height(CORE_CHIP_GAP))
        }
    }
}

/**
 * 一颗核的圆角 chip。
 *
 * 内容固定五行：标题 + 利用率大字 + 四条频率。没值写 `—`（原因放详情区，
 * 塞进 chip 会把方块撑成两行、整行高度就乱了）。
 */
@Composable
private fun CoreChip(
    core: CpuCoreExt,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {

    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val outline = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = modifier
            .widthIn(min = CORE_CHIP_MIN_WIDTH)
            .clip(RoundedCornerShape(CHIP_CORNER))
            .background(container)
            .border(
                width = if (selected) CORE_CHIP_SELECTED_BORDER else 1.dp,
                color = outline,
                shape = RoundedCornerShape(CHIP_CORNER)
            )
            .clickable { onSelect(core.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),

            verticalAlignment = Alignment.CenterVertically
        ) {

            // 名称前面留的进程图标位：固定 18dp，选中时边框跟着变主色
            ProcessIconSlot(size = ICON_SLOT, selected = selected)

            Spacer(modifier = Modifier.width(ICON_SLOT_GAP))

            Text(
                text = core.label(),

                style = MaterialTheme.typography.labelMedium,

                fontWeight = FontWeight.SemiBold,

                maxLines = 1,

                overflow = TextOverflow.Ellipsis,

                modifier = Modifier.weight(1f)
            )

            Text(
                text = core.usageText(),

                style = MaterialTheme.typography.titleMedium,

                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        ChipFieldRow(label = "当前", value = core.curText())

        ChipFieldRow(label = "最大", value = core.maxText())

        ChipFieldRow(label = "最小", value = core.minText())

        ChipFieldRow(label = "设置最大", value = core.scalingMaxText())
    }
}

/** chip 里的一行"标签 + 数值"。 */
@Composable
private fun ChipFieldRow(label: String, value: String) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),

        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,

            style = MaterialTheme.typography.labelSmall,

            fontSize = 10.sp,

            color = MaterialTheme.colorScheme.onSurfaceVariant,

            modifier = Modifier.weight(1f),

            maxLines = 1
        )

        Text(
            text = value,

            style = MaterialTheme.typography.labelSmall,

            fontSize = 10.sp,

            fontWeight = FontWeight.Medium,

            textAlign = TextAlign.End,

            maxLines = 1
        )
    }
}

/**
 * 图形下面那排小 chips：点一颗就选中它。
 *
 * 为什么还留这一排：核多时图形里的方块会排成好几行，想找"第 5 颗"要来回扫；
 * 这一排是完整的一份索引，位置固定、顺序固定，选起来最快。
 */
@Composable
private fun CoreSelectorPills(
    cores: List<CpuCoreExt>,
    selectedCoreId: Int?,
    perRow: Int,
    onSelect: (Int) -> Unit
) {

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {

        cores.chunked(perRow).forEach { rowCores ->

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {

                rowCores.forEach { core ->

                    CorePill(
                        core = core,
                        selected = core.id == selectedCoreId,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = CORE_CHIP_GAP),
                        onSelect = onSelect
                    )
                }

                repeat(perRow - rowCores.size) {

                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = CORE_CHIP_GAP)
                    )
                }
            }

            Spacer(modifier = Modifier.height(CORE_CHIP_GAP))
        }
    }
}

/** 一颗选择用小 chip：`CPU 0 · 23%`。 */
@Composable
private fun CorePill(
    core: CpuCoreExt,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {

    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val outline = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Row(
        modifier = modifier
            .widthIn(min = CORE_PILL_MIN_WIDTH)
            .clip(RoundedCornerShape(CHIP_CORNER))
            .background(container)
            .border(
                width = if (selected) CORE_CHIP_SELECTED_BORDER else 1.dp,
                color = outline,
                shape = RoundedCornerShape(CHIP_CORNER)
            )
            .clickable { onSelect(core.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),

        verticalAlignment = Alignment.CenterVertically
    ) {

        // 名称前面的进程图标位（小一号，跟 10sp 的字对齐）
        ProcessIconSlot(size = ICON_SLOT_SMALL, selected = selected)

        Spacer(modifier = Modifier.width(ICON_SLOT_GAP))

        Text(
            text = core.label(),

            style = MaterialTheme.typography.labelMedium,

            fontWeight = FontWeight.SemiBold,

            maxLines = 1,

            modifier = Modifier.weight(1f)
        )

        Text(
            text = core.usageText(),

            style = MaterialTheme.typography.labelMedium,

            fontWeight = FontWeight.SemiBold,

            maxLines = 1
        )
    }
}

/** 选中核心的详情：六项逐行写清，没值的格子连原因一起写。 */
@Composable
private fun SelectedCoreDetail(core: CpuCoreExt, cluster: CpuClusterExt?) {

    Surface(
        modifier = Modifier.fillMaxWidth(),

        shape = RoundedCornerShape(CHIP_CORNER),

        color = MaterialTheme.colorScheme.surfaceVariant
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {

            // 第一行：这颗核在哪个簇、哪个 policy
            val placeText = buildString {

                append(core.label())

                if (cluster != null) {

                    append(" · ")
                    append(cluster.title())
                }

                if (core.policy != null) {

                    append(" · policy")
                    append(core.policy)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),

                verticalAlignment = Alignment.CenterVertically
            ) {

                // 名称前面留的进程图标位
                ProcessIconSlot(size = ICON_SLOT)

                Spacer(modifier = Modifier.width(ICON_SLOT_GAP))

                Text(
                    text = placeText,

                    style = MaterialTheme.typography.bodyMedium,

                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            DetailRow("利用率", core.displayOf("usage_percent"))

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),

                color = MaterialTheme.colorScheme.outlineVariant
            )

            DetailRow("当前频率", core.displayOf("cur_mhz"))

            DetailRow("最大频率", core.displayOf("max_mhz"))

            DetailRow("最小频率", core.displayOf("min_mhz"))

            DetailRow("设置的最大频率", core.displayOf("scaling_max_mhz"))
        }
    }
}

/** 详情里的一行：左标签、右数值；数值右对齐，长状态串自动换行。 */
@Composable
private fun DetailRow(label: String, value: String) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),

        verticalAlignment = Alignment.Top
    ) {

        Text(
            text = label,

            style = MaterialTheme.typography.bodySmall,

            color = MaterialTheme.colorScheme.onSurfaceVariant,

            modifier = Modifier.widthIn(min = 96.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = value,

            style = MaterialTheme.typography.bodySmall,

            fontWeight = FontWeight.Medium,

            textAlign = TextAlign.End
        )
    }
}
