package com.android.taskmanager.manager

import android.content.Context
import android.os.SystemClock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import com.android.taskmanager.AppStrings
import com.android.taskmanager.R
import com.android.taskmanager.SettingsCatalog
import com.android.taskmanager.SettingsDefaults
import com.android.taskmanager.data.CpuUsagePoint
import com.android.taskmanager.data.DiskSnapshot
import com.android.taskmanager.data.GpuSnapshot
import com.android.taskmanager.data.NetworkSnapshot
import com.android.taskmanager.data.diskRateTextNice
import com.android.taskmanager.data.PerformanceSnapshot
import com.android.taskmanager.data.diskRateText
import com.android.taskmanager.data.networkRateText
import com.android.taskmanager.service.UIService

import org.json.JSONObject

import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 性能页（PerformanceScreen）的页面管理器。
 *
 * 职责边界（这一层定死，后面加页面照这个模式走）：
 * - **它管**：性能页私有的数据 —— CPU / 内存两条历史队列、采样时刻、图表窗口文案、
 *   图表画图偏好（口径与内核时间）、当前选中硬件、图形摘要视图开关；
 * - **它不管**：Compose 布局与绘图（那是 Screen 的事），也不直接读 /proc
 *   （快照由 UIService 统一采集后推给它）。
 *
 * 数据怎么进来：UIService 每采到一帧就通过 [UIService.addPerformanceFrameListener] 通知，
 * 本管理器把点追加进自己的队列，然后写自己的 Compose 状态。Screen 只读这些状态，
 * 于是"页面离开组合"与"历史继续积累"互不影响 —— 这正是页面自己的 remember 做不到的事。
 *
 * 为什么用 Compose 的 mutableStateOf 而不是 StateFlow：本项目的消费者全是 Compose，
 * 状态写一次就只让读到它的那几个组合重组；StateFlow 还要再包一层 collectAsState，
 * 多一道没有收益的转换。对外语义等价：只读属性 + 内部写入。
 */
class PerformanceManager internal constructor(appContext: Context) {

    private val appContext: Context = appContext.applicationContext

    // ==================== 1. 页面数据（Screen 只读） ====================

    /**
     * 无线局域网的界面名称：中国地区写 WLAN，其它地区写 Wi-Fi。
     *
     * 为什么不写在底层：这跟硬件一点关系都没有，纯粹是用户读得顺不顺的问题。
     * Wi-Fi 的官方写法就是 "Wi-Fi"（大写 W、F，中间连字符；WiFi / WIFI 都不是
     * Wi-Fi 联盟认可的写法），而简体中文的 Windows 一直把这一类叫 WLAN。
     * 所以按"设备所在地区"决定文案：地区是中国（CN）就用 WLAN，
     * 否则用 Wi-Fi；国家读不出来时退一步看语言是不是中文。
     *
     * 判定放在管理器里，页面只拿结果：换语言 / 换地区以后只改这一处。
     */
    val networkDisplayName: String = resolveNetworkDisplayName(appContext)

    /** 最近一帧设备快照。没采过就是空帧（各字段 null，页面显示破折号）。 */
    var snapshot by mutableStateOf(PerformanceSnapshot.EMPTY)
        private set

    /** CPU 利用率历史点（大图与左列迷你图共用这一份）。 */
    var cpuHistory by mutableStateOf<List<CpuUsagePoint>>(emptyList())
        private set

    /** 内存使用量历史点，纵轴是占总量的百分比。 */
    var memoryHistory by mutableStateOf<List<CpuUsagePoint>>(emptyList())
        private set

    /**
     * 图表窗口状态码（**不是文案**）：`PAUSED` / `30` / `60` / `240`，单位秒。
     * 界面用 R.string 里的固定句子与短标签显示它，因此切换系统语言后自然跟着变；
     * 跟着设置页的实时更新速度变，每次进性能页取一次。
     */
    var windowCode by mutableStateOf(DEFAULT_WINDOW_SECONDS.toString())
        private set

    /** 利用率显示位数（设置页「利用率精确度」）。只在进页面时取一次。 */
    var usageDecimals by mutableStateOf(0)
        private set

    /**
     * 当前选中的磁盘序号（按这一帧 disks 列表的顺序，0 起）。
     *
     * 磁盘是一类可能多台的设备：选中哪一台是页面状态，由本管理器持有，
     * 切页、横竖屏、浮窗都不会回到第一台。
     */
    var selectedDiskIndex by mutableStateOf(0)
        private set

    /**
     * 每块磁盘的图表缓存（键 = 设备 id；底层没给 id 时用报文序号）。
     *
     * 内容：活动时间曲线、读取/写入速率曲线（已按当前量程归一化成百分比）、
     * 以及当前量程本身。页面切走再回来、换一台磁盘再换回来，曲线都还在，
     * 因为缓存不跟页面组合走。
     */
    var diskCharts by mutableStateOf<Map<String, DiskChartData>>(emptyMap())
        private set

    /**
     * 每块网卡的吞吐量图缓存（键 = 设备 id；底层没给 id 时用序号）。
     *
     * 内容：发送 / 接收两条速率曲线（已按当前量程归一化成百分比）+ 当前量程。
     * 与磁盘同一套规矩：原始值存绝对字节/秒，换量程只重算比例，形状不变。
     */
    var networkCharts by mutableStateOf<Map<String, NetworkChartData>>(emptyMap())
        private set

    /**
     * 每张显卡的图表缓存（键 = 设备 id；底层没给 id 时用序号）。
     *
     * 三张图：3D 利用率（0..100%）、专用显存占用、共享显存占用。
     * 后两张的纵轴是"占该类显存总量的百分比"：总量读不到就一条点都不给（图留空），
     * 绝不拿"没有总量"当成 0%。
     */
    var gpuCharts by mutableStateOf<Map<String, GpuChartData>>(emptyMap())
        private set

    // ==================== 2. 页面私有的视图状态 ====================

    /**
     * 当前选中的硬件（存枚举名）。
     *
     * 不放 rememberSaveable：横竖屏、浮窗、切页是不同宿主 / 不同组合，
     * 页面内的状态每换一个地方都会丢。挑硬件是用户的选择，归管理器持有。
     */
    var selectedKindName by mutableStateOf("CPU")
        private set

    /** 是否处于图形摘要视图（宿主据此让出导航栏 / 标题栏）。 */
    var summaryActive by mutableStateOf(false)

    /**
     * 摘要视图里放大的是哪一张图。
     *
     * false = 主图（CPU / 内存的曲线、磁盘的活动时间）；
     * true  = 第二张图（磁盘的传输速率图）。内存页的分条图只是主图的图例，
     *         双击它进摘要后仍然放主图，所以这里是 false。
     *
     * 归管理器持有：摘要是另一个宿主（MainPage 会让出导航栏），
     * 页面内的 remember 到那边就丢了，双击的是哪张图就传不过去。
     */
    var summarySecondChart by mutableStateOf(false)

    /** 图表口径：true = 逻辑处理器（每核一格），false = 总体利用率。 */
    var logicalGraph by mutableStateOf(false)
        private set

    /** 是否在曲线下方叠内核时间色带。 */
    var showKernelTime by mutableStateOf(false)
        private set

    // ==================== 3. 历史队列 ====================

    private val cpuPoints = ArrayDeque<CpuUsagePoint>()

    private val memoryPoints = ArrayDeque<CpuUsagePoint>()

    /**
     * 每块磁盘的原始缓存。键与 [diskCharts] 一致。
     *
     * 只在采集线程里读写；页面读的是已经算好的 [diskCharts] 快照。
     */
    private val diskBuffers = HashMap<String, DiskBuffers>()

    /** 每块网卡的原始速率缓存。键与 [networkCharts] 一致。 */
    private val networkBuffers = HashMap<String, NetworkBuffers>()

    /** 每张显卡的原始曲线缓存。键与 [gpuCharts] 一致。 */
    private val gpuBuffers = HashMap<String, GpuBuffers>()

    /** 一次从磁盘读过图表偏好没有。 */
    @Volatile
    private var preferencesLoaded = false

    /** 最近一帧的采样时刻（elapsedRealtime，与曲线横轴同一套时钟）；0 = 还没来过帧。 */
    private var lastFrameAtMs = 0L

    /** 写盘线程：连续点几下只排队，不一次点出一个线程。 */
    private val preferenceWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "perf-pref-writer").apply { isDaemon = true }
    }

    // ==================== 4. 与服务的关系 ====================

    @Volatile
    private var service: UIService? = null

    /**
     * 最近一帧**未经调试注入**的原始快照。
     *
     * 为什么要单独存一份：调试页会往快照里加"空设备"（空磁盘 / 空网卡），
     * 那是页面层要看到的东西，不是底层采到的东西。合并结果写进 [snapshot]，
     * 原始帧留在这里 —— 否则下一次合并会把上次注入的空设备再算一遍，越点越多。
     */
    private var baseSnapshot: PerformanceSnapshot = PerformanceSnapshot.EMPTY

    /**
     * 调试页注入的空设备数量（0 = 没有注入）。
     *
     * 这两个值只由调试页的按钮改；改了立刻按当前原始帧重新合并，
     * 所以不用等下一次采样，性能页下一次重组就能看到新入口。
     */
    var debugDiskCount by mutableStateOf(0)
        private set

    var debugNetworkCount by mutableStateOf(0)
        private set

    /** 空显卡的注入数量（与磁盘 / 网卡同一套合并流程）。 */
    var debugGpuCount by mutableStateOf(0)
        private set

    /**
     * 调试开关：把底层**报上来的每一块网卡**都注册成性能页设备，不再按注册规则隐藏。
     *
     * 为的是核对"哪些接口被藏了、藏得对不对"：p2p0、没连网的 wlan0、只有 fe80:: 的
     * rmnetXX、VPN 的 tun0 平时都不在左栏，要看它们的字段就得把规则暂时摘掉。
     * 只覆盖快照里已经有的设备 —— 底层没采集到的（lo 之类）照样不会凭空出现。
     *
     * 生命周期：**只活在这个进程里**，不写 settings.json / 不写任何存储，
     * 应用一关就忘（调试功能本来就是这个口径）。挂在本管理器上而不是某个页面里，
     * 因为管理器是进程级单例：离开调试页、横竖屏切换、甚至 UIService 重建，
     * 这个开关都还在（本类由 PageManagers 持有，生命周期比任何 Screen 都长）。
     */
    var debugShowAllNetworks by mutableStateOf(false)
        private set

    /**
     * 调试页勾选框的落点：立刻按当前原始帧重新合并一次。
     *
     * 名字不叫 setDebugShowAllNetworks：Kotlin 给 var 生成的 JVM setter 已经占用了
     * 那个签名（setDebugShowAllNetworks(Z)V），同名函数会撞成 declaration clash，
     * 所以这个"带副作用"的入口单独起名。
     *
     * 不等下一次采样，勾上 / 取消后性能页下一次重组就能看到效果。
     */
    fun applyDebugShowAllNetworks(showAll: Boolean) {

        if (debugShowAllNetworks == showAll) return

        debugShowAllNetworks = showAll

        publishMergedSnapshot()
    }

    /**
     * 快照订阅回调。
     *
     * 用一个字段而不是每次 new lambda：attach / detach 需要能摘掉同一份引用，
     * 否则重复 onCreate 会让一帧被追加两次（曲线上出现重复点）。
     */
    private val frameListener: (PerformanceSnapshot, Long) -> Unit = { snap, atMs ->
        onFrame(snap, atMs)
    }

    /** UIService 创建时调用：订阅快照、取一次窗口文案与小数位。 */
    internal fun attach(service: UIService) {

        if (this.service === service) return

        this.service?.removePerformanceFrameListener(frameListener)

        this.service = service

        service.addPerformanceFrameListener(frameListener)

        refreshWindowLabel()

        refreshUsageDecimals()
    }

    /** 服务销毁时摘掉订阅。历史留在本对象里，服务重建后继续用（同一进程内不丢）。 */
    internal fun detach(service: UIService) {

        if (this.service !== service) return

        service.removePerformanceFrameListener(frameListener)

        this.service = null
    }

    // ==================== 5. 给 Screen 的入口 ====================

    /**
     * 进页面时调一次：窗口文案、小数位取最新设置，必要时补采一帧（第一屏不等一个周期）。
     *
     * 为什么要按间隔节流，而不是无条件 refreshNow()：
     * 图形摘要视图和普通版是**两个不同的组合分支**（MainActivity 里各有一处
     * PageContent 调用点，摘要要让出导航栏），所以双击进预览、再退出，整棵子树都会重建，
     * 本页的 LaunchedEffect(service) 随之重跑 —— 每进一次就多补一帧。
     * 这一帧和上一帧只隔几十毫秒，追进历史里就是"曲线突然往前窜一格"，
     * 看着就像双击把更新速度调快了。
     *
     * 所以：刚采过（距上一帧不足当前间隔的 90%）就不再补，
     * 只有真正隔了一个周期（切页回来、服务重建）才补这一帧。
     */
    fun onScreenEntered() {

        refreshWindowLabel()

        refreshUsageDecimals()

        val interval = service?.performancePollIntervalMs() ?: 0L

        val sinceLastFrame = SystemClock.elapsedRealtime() - lastFrameAtMs

        val worthSampling = interval > 0L && lastFrameAtMs <= 0L

        if (worthSampling || sinceLastFrame >= interval * 9L / 10L) {

            refreshNow()
        }
    }

    /** 立刻采一帧（转发 UIService 的统一采集入口，页面不直接碰 Linux 服务）。 */
    fun refreshNow(): Boolean = service?.refreshPerformanceNow() ?: false

    /** 现在允许刷新吗（前台 / 浮窗可见）。页面自己计时轮询时拿它做门禁。 */
    fun isRefreshAllowed(): Boolean = service?.isUiRefreshAllowed() ?: false

    /** 首次进页面时把落盘的图表偏好读进来（幂等）。 */
    fun ensureLoaded() {

        if (preferencesLoaded) return

        val preferences = ChartViewStore.load(appContext)

        logicalGraph = preferences.logicalGraph

        showKernelTime = preferences.showKernelTime

        preferencesLoaded = true
    }

    /** 这一帧里有几台磁盘（0 = 连入口都不该出现）。 */
    fun diskCount(): Int = snapshot.disks.size

    /** 磁盘在切换列表里的名字，由管理器统一命名：磁盘 0、磁盘 1 … */
    /**
     * 磁盘的标题：横屏左栏与详情页左上角写"磁盘 0 (sda)"，括号里是块设备名；
     * node 读不到时退回只有序号的那一版（竖屏底部横条就用那一版）。
     */
    fun diskTitle(index: Int, node: String? = null): String {

        val block = node?.trim()?.takeIf { it.isNotBlank() }

        return if (block == null) {
            AppStrings.s(R.string.perf_disk_index, index)
        } else {
            AppStrings.s(R.string.perf_disk_index_block, index, block)
        }
    }

    /**
     * 资源监视器（性能页右上角菜单的第二项）。
     *
     * Android 目前没有系统级的"资源监视器"，这一版只留接口：
     * 菜单项点了什么也不做，也不弹窗。将来要接的时候把实现写进这里，
     * 菜单与调用处都不用改。
     */
    fun openResourceMonitor() {
        // 暂无实现：留空，等 Android 侧有可用的资源监视器再填
    }

    /**
     * 性能页挂进来的「复制当前图表」实现。
     *
     * 菜单画在宿主的页名行（MainActivity），那一行拿不到页面内部的剪贴板逻辑，
     * 也不该自己去拼文本：页面进组合时把自己的函数挂到这儿，宿主只发一次请求。
     * 页面不在组合里时为空，这时点菜单什么也不会发生。
     */
    var copyChartHandler: (() -> Unit)? = null

    /** 宿主请求「复制当前图表」：转交给性能页自己挂上来的实现。 */
    fun requestCopyChart() {
        copyChartHandler?.invoke()
    }

    /** 这一帧里有几块网卡（0 = 连网卡入口都不该出现）。 */
    fun networkCount(): Int = snapshot.networks.size

    /**
     * 一块网卡的"设备类型名"：WLAN / Wi-Fi、手机网络、以太网 …
     *
     * 这是页头左上角、左栏大字、剪贴板标题、摘要视图共用的那一套**正式类型名**。
     * 它和适配器名（wlan0 / rmnet_data0，即 `name` 字段，右栏右上角那个）是两码事：
     * 类型名回答"这是什么设备"，适配器名回答"具体是哪一块"。
     *
     * 无线局域网按地区取舍（中国 WLAN、其它 Wi-Fi，见 [networkDisplayName]）；
     * 手机网络与以太网没有地区差异，写死中文。ETHERNET 与 OTHER 都算以太网，
     * 所以 USB 网卡、扩展坞网口、底层分不出型号的接口会共用「以太网」这个名字；
     * 其中 tun0…tunN 的隧道接口单独标成「以太网（VPN）」（见 [NetworkSnapshot.isVpnTunnel]），
     * 其余以太网设备仍是「以太网」。
     */
    fun networkTypeName(network: NetworkSnapshot?): String = when {

        network?.isCellular() == true -> AppStrings.s(R.string.perf_type_cellular)

        network?.isVpnTunnel() == true -> AppStrings.s(R.string.perf_ethernet_vpn)

        network?.isWired() == true -> AppStrings.s(R.string.perf_type_ethernet)

        // 无线与"底层没给角色"（老后端 / 调试注入）都落到这里
        else -> networkDisplayName
    }

    /**
     * 网卡在切换列表里的名字：类型名 + **同类型内**的序号（第一块不带序号）。
     *
     * 一块无线 + 一块手机网络就是「WLAN」「手机网络」；同类型第二块起才带 1、2 …
     * 为什么第一块不带：手机上通常只有一块无线、一张卡，时时挂个 0 只是噪音，
     * 与磁盘 / GPU 那套"永远从 0 起"刻意不同（序号只在"确实有多块"时才有信息量）。
     * 但一段显示里不能既有"带号"又有"不带号"的同类：一块无线 + 一块手机网络时
     * 两条都不带号，没问题；同类出现第二块时才会看到「WLAN」「WLAN 1」。
     *
     * 这是**页面标题**用的全名：tun 隧道在这里写「以太网（VPN）」。
     * 列表里的短名见 [networkShortTitle]。
     */
    fun networkTitle(networks: List<NetworkSnapshot>, index: Int): String =
        numberedNetworkTitle(networks, index) { networkTypeName(networks.getOrNull(it)) }

    /**
     * 侧栏 / 底部硬件条这类**列表里**的名字：与 [networkTitle] 同一套编号，
     * 但 tun 隧道**不写 (VPN)**，与普通以太网共用「以太网」。
     *
     * 用户口径：列表只是"挑哪一页"，一行里塞「以太网（VPN）」又长又被窄格子截断；
     * VPN 这层信息只在进入页面后由左上角标题（[networkTitle]）给出。
     */
    fun networkShortTitle(networks: List<NetworkSnapshot>, index: Int): String =
        numberedNetworkTitle(networks, index) { networkShortTypeName(networks.getOrNull(it)) }

    /** 列表里的网卡类型名：VPN 与普通以太网都写「以太网」。 */
    fun networkShortTypeName(network: NetworkSnapshot?): String = when {

        network?.isCellular() == true -> AppStrings.s(R.string.perf_type_cellular)

        network?.isWired() == true -> AppStrings.s(R.string.perf_type_ethernet)

        else -> networkDisplayName
    }

    /** 按"同名前缀"编号：第一块不带号，第二块起 1、2 … */
    private fun numberedNetworkTitle(
        networks: List<NetworkSnapshot>,
        index: Int,
        nameOf: (Int) -> String
    ): String {

        val typeName = nameOf(index.coerceAtLeast(0))

        var sameBefore = 0

        for (i in 0 until index.coerceAtLeast(0)) {
            if (nameOf(i) == typeName) sameBefore++
        }

        return if (sameBefore == 0) typeName else "$typeName $sameBefore"
    }

    /** 这一帧里有几张显卡（0 = 连 GPU 入口都不该出现）。 */
    fun gpuCount(): Int = snapshot.gpus.size

    /** 显卡在切换列表里的名字：GPU 0、GPU 1 …（与 Windows 同一套写法）。 */
    fun gpuTitle(index: Int): String = "GPU $index"

    /** 选中某一张显卡（同时把当前硬件切到 GPU）。序号会被夹到合法范围。 */
    fun selectGpu(index: Int) {

        selectedDiskIndex = index.coerceIn(0, (gpuCount() - 1).coerceAtLeast(0))

        selectedKindName = KIND_GPU
    }

    /** 取某一张显卡的图表缓存（页面只读，语义同 [diskChart]）。 */
    fun gpuChart(gpus: List<GpuSnapshot>, index: Int): GpuChartData? {

        val gpu = gpus.getOrNull(index) ?: return null

        val key = chartKeyOf(gpu.node, gpu.id, index)

        return gpuCharts[key]
    }

    /** 选中某一块网卡（同时把当前硬件切到 WLAN）。序号会被夹到合法范围。 */
    fun selectNetwork(index: Int) {

        selectedDiskIndex = index.coerceIn(0, (networkCount() - 1).coerceAtLeast(0))

        selectedKindName = KIND_NETWORK
    }

    /** 选中某一台磁盘（同时把当前硬件切到磁盘）。序号会被夹到合法范围。 */
    fun selectDisk(index: Int) {

        selectedDiskIndex = index.coerceIn(0, (diskCount() - 1).coerceAtLeast(0))

        selectedKindName = KIND_DISK
    }

    /**
     * 选中一个非磁盘的硬件入口（"CPU" / "MEMORY"）。
     *
     * 选中项是两个字段拼出来的：类别名 + 磁盘序号。序号只对磁盘有意义，
     * 但它是共用的一个值 —— 先选"磁盘 5"（序号 = 5）再选内存时，如果不归零，
     * 页面拼出的查找键就是 (MEMORY, 5)，内存那一项是 (MEMORY, 0)，查不到就
     * 退回列表第一项 = CPU。表现正是"从磁盘 5 切内存却切到 CPU，只有先点磁盘 0 才行"。
     *
     * 所以任何非磁盘的选中都必须把序号归零；页面也不再直接写 [selectedKindName]，
     * 一律走这里，免得哪天又漏一处。
     */
    fun selectKind(name: String) {

        selectedKindName = name

        if (name != KIND_DISK && name != KIND_GPU) selectedDiskIndex = 0
    }

    /**
     * 取某块磁盘的图表缓存（页面只读）。
     *
     * @param disks 这一帧的磁盘列表（由调用方从快照里带进来）
     * @param index 第几台
     * @return 没有这一台、或还没有任何采样时是 null（页面按空图渲染）
     */
    fun diskChart(disks: List<DiskSnapshot>, index: Int): DiskChartData? {

        val disk = disks.getOrNull(index) ?: return null

        return diskCharts[diskKeyOf(disk, index)]
    }

    /** 取某块网卡的吞吐量图缓存（页面只读，语义同 [diskChart]）。 */
    fun networkChart(networks: List<NetworkSnapshot>, index: Int): NetworkChartData? {

        val network = networks.getOrNull(index) ?: return null

        val key = chartKeyOf(network.iface, network.id, index)

        return networkCharts[key]
    }

    /** 改图表口径：先改内存（这一帧就重画），再异步写盘。 */
    fun updateLogicalGraph(value: Boolean) {

        if (logicalGraph == value) return

        logicalGraph = value

        persistPreferences()
    }

    /** 改显示内核时间：同上。 */
    fun updateShowKernelTime(value: Boolean) {

        if (showKernelTime == value) return

        showKernelTime = value

        persistPreferences()
    }

    // ==================== 5.1 调试页：注入空设备 ====================

    /**
     * 调试页用：往性能页注入一台"空磁盘"。
     *
     * 为什么走管理器而不是直接改快照：快照是底层采样结果，页面只读；
     * 由管理器在"原始帧 → 页面快照"之间加一层合并，既能造出没有数据的设备实例，
     * 又不会污染底层采集，下一次真实采样到达时空设备依然在（数量存在这里）。
     *
     * 空设备的字段全是默认值：容量 / 类型 / 系统盘 / 页面文件读不到（页面写破折号），
     * 活动时间 0%、平均响应 0 毫秒、读写速率 0 —— 正好把四个指标的真实渲染路径跑一遍。
     */
    fun addDebugEmptyDisk() {

        debugDiskCount += 1

        publishMergedSnapshot()
    }

    /**
     * 调试页用：往性能页注入一块"空网卡"。
     *
     * 空网卡的字段全是 null：明细六行里除适配器名都写"不支持"，
     * 发送 / 接收写 0 Kbps，吞吐量图只有坐标系 —— 与真实网卡（现在也只有 name）同一套渲染。
     */
    fun addDebugEmptyNetwork() {

        debugNetworkCount += 1

        publishMergedSnapshot()
    }

    /**
     * 调试页用：往性能页注入一张"空显卡"。
     *
     * 空显卡的字段全是 null：适配器名、显存、驱动、图形 API 都写「不支持 / -」，
     * 三张图只有坐标系 —— 与真实显卡（多数机型只有利用率与温度）同一套渲染。
     */
    fun addDebugEmptyGpu() {

        debugGpuCount += 1

        publishMergedSnapshot()
    }

    /** 撤掉所有调试注入的空设备（性能页对应入口随之消失）。 */
    fun clearDebugDevices() {

        if (debugDiskCount == 0 && debugNetworkCount == 0 && debugGpuCount == 0) return

        debugDiskCount = 0

        debugNetworkCount = 0

        debugGpuCount = 0

        publishMergedSnapshot()
    }

    /** 调试页显示用：现在注入了几台空设备。 */
    fun debugDeviceText(): String =
        AppStrings.s(
            R.string.dbg_inject_counts,
            debugDiskCount,
            debugNetworkCount,
            debugGpuCount
        )

    /** 按最新原始帧重新合并一次（注入 / 撤销后立即生效，不等下一次采样）。 */
    private fun publishMergedSnapshot() {

        snapshot = mergeDebugDevices(baseSnapshot)
    }

    /**
     * 原始帧 + 调试空设备 = 页面看到的快照，再按"注册规则"筛一遍网卡。
     *
     * 空设备排在真实设备后面：真实磁盘 0/1 的编号不会因为注入而变，
     * 注入的空盘 / 空网卡从索引末尾开始。
     *
     * 这里是"第二个过程"的落点：底层把 /sys/class/net 的全部接口都报上来
     * （含 tun / vpn / rmnet / p2p0 这类），管理器按 [isRegisteredNetwork] 挑出要注册的
     * —— 无线只收"已经连上（有 SSID）"的，手机网络与以太网只收"有地址"的。
     * 底层照旧把这些接口全发上来，注册与否只在 Kotlin 这一层决定。
     * 例外：调试页勾了「显示所有网卡设备」时这道筛整体跳过（见 [debugShowAllNetworks]），
     * 用来核对"哪些被藏了、藏得对不对"。
     */
    private fun mergeDebugDevices(snapshot: PerformanceSnapshot): PerformanceSnapshot {

        val extraDisks = if (debugDiskCount > 0) {
            List(debugDiskCount) { index -> emptyDebugDisk(index) }
        } else {
            emptyList()
        }

        val extraNetworks = if (debugNetworkCount > 0) {
            List(debugNetworkCount) { index -> emptyDebugNetwork(index) }
        } else {
            emptyList()
        }

        val extraGpus = if (debugGpuCount > 0) {
            List(debugGpuCount) { index -> emptyDebugGpu(index) }
        } else {
            emptyList()
        }

        // 调试开关打开时：底层报了什么就注册什么（p2p0 / 未连网的 wlan0 / 只有 fe80:: 的
        // rmnet / VPN 的 tun0 全部放出来），用来核对隐藏规则本身；关掉就恢复常规筛选。
        // 注意只动"注册"这一步：没连网的那条照样显示"不支持"、速率照样是 0，
        // 不会因为被放出来就凭空长出数据。
        //
        // 调试注入的空网卡始终拼在后面、不过这道筛（它们天生没有任何字段，
        // 目的就是把"有一块设备但读不到细节"摆出来）。
        val registeredNetworks = if (debugShowAllNetworks) {
            snapshot.networks
        } else {
            snapshot.networks.filter { isRegisteredNetwork(it) }
        }

        val networks = registeredNetworks + extraNetworks

        return snapshot.copy(
            disks = snapshot.disks + extraDisks,
            networks = networks,
            gpus = snapshot.gpus + extraGpus
        )
    }

    /**
     * 一块网卡要不要注册成性能页设备。
     *
     * - 无线局域网：**必须连上网（有 SSID）才注册**。理由有两层：
     *   一是一堆名义上是无线、实际不干活的接口（p2p0 = Wi-Fi Direct、wifi-aware、
     *   各种虚拟 wlan 分支）也带着 wireless 标记，单看标记分不出来；
     *   二是真正的 wlan0 没连网时既没有 SSID 也没有流量，摆在左栏只是一行 0 Kbps 的空壳。
     *   SSID 由底层 read_iw_link 给出（没连上就不发这一格），所以"SSID 有没有值"
     *   就是"这块无线是不是真连着"的事实判据，不是猜的。
     *   想放宽成"SSID 或 连接类型 任一有值"，只改这一行的条件即可。
     * - 手机移动网络：只收**有地址**的那一条。手机上 rmnet_data0..7 / ccmni0 这类
     *   接口一大串，绝大多数是空壳（没 IP、没流量），全注册会把左栏占满；
     *   真正在通信的那条一定有 IPv4 或 IPv6；
     * - 以太网这一族（ETHERNET + OTHER，后者含 USB 网卡、扩展坞网口、tun/vpn 等）：
     *   同样只收**有地址**的。网线拔了、VPN 断了、扩展坞收掉，地址跟着撤掉，
     *   下一帧就自动从左栏消失；插上再回来。没地址的一律不注册，
     *   免得左栏出现一排"以太网 0 Kbps"空壳；
     * - 其它（这一版已经全部归到上面三类里，else 只是兜底）：不注册。
     */
    private fun isRegisteredNetwork(network: NetworkSnapshot): Boolean = when {

        network.isWirelessLan() -> !network.ssid.isNullOrBlank()

        network.isCellular() -> network.hasAddress()

        network.isWired() -> network.hasAddress()

        else -> false
    }

    /** 空磁盘：只有 id，其它字段留空 / 置 0（"读不到"与"确实是 0"分开表达）。 */
    private fun emptyDebugDisk(index: Int): DiskSnapshot = DiskSnapshot(

        id = "debug-disk-$index",

        // 默认名称统一写 NAME：调试设备本来就没有型号，写 NAME 与真实设备
        // "抓不到名字时的兜底"是同一个值，页面上一眼能认出这是注入出来的
        name = "NAME",

        activityPercent = 0f,

        avgResponseMs = 0.0,

        readBytesPerSec = 0.0,

        writeBytesPerSec = 0.0
    )

    /**
     * 空网卡：只有 id 与角色，其余全 null（明细写"不支持"、速率写 0）。
     *
     * role 给 WIFI 是为了让页面按无线那一套版式画；注意它**不过注册过滤**
     * （[mergeDebugDevices] 里注入的空网卡直接拼在筛完的真实设备后面）——
     * 真实无线现在要求"有 SSID 才注册"，而空网卡天生没有 SSID，要是也走那道筛
     * 就永远显示不出来，而调试注入的目的恰恰是把"有一块网卡但读不到细节"摆出来。
     */
    private fun emptyDebugNetwork(index: Int): NetworkSnapshot = NetworkSnapshot(

        id = "debug-net-$index",

        // 同空磁盘：默认名称 NAME
        name = "NAME",

        role = ROLE_WIFI
    )

    /** 空显卡：只有 id，其余全 null（页面按「不支持 / -」渲染）。 */
    private fun emptyDebugGpu(index: Int): GpuSnapshot = GpuSnapshot(

        id = "debug-gpu-$index",

        // 同空磁盘 / 空网卡：默认名称 NAME（名字取不到时页面本来就写 NAME）
        name = "NAME"
    )

    // ==================== 6. 内部：快照入队 ====================

    /**
     * 一帧到达时的处理：更新当前值、给两条曲线各追加一个点。
     *
     * 采样时刻由 UIService 给（它统一取 SystemClock.elapsedRealtime()），
     * 不在这里再取一次 —— 两条曲线与当前值是同一拍，横轴才对得上。
     */
    private fun onFrame(snapshot: PerformanceSnapshot, atMs: Long) {

        // 原始帧先留一份：调试注入的空设备每次都是从它重新合并的，
        // 合并结果才是页面读到的快照（没有注入时两者就是同一份）
        baseSnapshot = snapshot

        publishMergedSnapshot()

        // 记下来这一帧是什么时候采的：onScreenEntered 用它判断"刚采过没有"
        lastFrameAtMs = atMs

        appendCpuPoint(snapshot, atMs)

        appendMemoryPoint(snapshot, atMs)

        // 磁盘：一台都没有就什么都不做（入口都不出现，自然也没有数据要攒）
        appendDiskFrames(this.snapshot, atMs)

        // 网卡：同一套规矩，两台曲线（发送 / 接收）
        appendNetworkFrames(this.snapshot, atMs)

        // 显卡：3D 利用率 + 专用 / 共享显存占用三条曲线
        appendGpuFrames(this.snapshot, atMs)

        // 用户可能刚在设置页改了更新速度：每帧顺手重算一次文案，变了才写状态
        refreshWindowLabel()
    }

    /**
     * CPU 历史：利用率拿不到（首帧 NO_BASELINE / 读数失败）就不入队。
     * 断点插 0 会在曲线上画出一道根本不存在的陡降。
     */
    private fun appendCpuPoint(snapshot: PerformanceSnapshot, atMs: Long) {

        val percent = snapshot.cpuUsagePercent ?: return

        // 队列为空 = 一段历史刚开始：先垫一个 0 起点，曲线第一笔从底边起步
        if (cpuPoints.isEmpty()) {

            cpuPoints.addLast(
                CpuUsagePoint(
                    timeMs = atMs,
                    percent = 0f,
                    kernelPercent = snapshot.cpuKernelPercent?.let { 0f },
                    corePercents = snapshot.logicalUsage.map { 0f },
                    coreKernels = snapshot.logicalKernel.map { 0f }
                )
            )
        }

        cpuPoints.addLast(
            CpuUsagePoint(
                timeMs = atMs,
                percent = percent,
                kernelPercent = snapshot.cpuKernelPercent,
                corePercents = snapshot.logicalUsage,
                coreKernels = snapshot.logicalKernel
            )
        )

        while (cpuPoints.size > MAX_HISTORY_POINTS) {
            cpuPoints.removeFirst()
        }

        cpuHistory = cpuPoints.toList()
    }

    /**
     * 内存历史：纵轴是"占总量的百分比"，分母用 MemTotal（图表顶端那条线的含义）。
     * used / total 任一缺失就不入队，理由与 CPU 相同。
     */
    private fun appendMemoryPoint(snapshot: PerformanceSnapshot, atMs: Long) {

        val memory = snapshot.memory ?: return

        val total = memory.totalBytes ?: return

        val used = memory.usedBytes ?: return

        if (total <= 0L || used < 0L) return

        val percent = (used.toDouble() / total.toDouble() * 100.0)
            .toFloat()
            .coerceIn(0f, 100f)

        if (memoryPoints.isEmpty()) {

            memoryPoints.addLast(CpuUsagePoint(timeMs = atMs, percent = 0f))
        }

        memoryPoints.addLast(CpuUsagePoint(timeMs = atMs, percent = percent))

        while (memoryPoints.size > MAX_HISTORY_POINTS) {
            memoryPoints.removeFirst()
        }

        memoryHistory = memoryPoints.toList()
    }

    // ==================== 7. 内部：磁盘 ====================

    /**
     * 一帧里的每台磁盘各攒一份曲线。
     *
     * 三件事按顺序做（先算完再交给页面画，这是量程切换不闪、不错位的关键）：
     * 1) 活动时间：一个百分比点，规矩与 CPU 那条一样（首点垫 0、缺值不入队）；
     * 2) 读写速率：存**绝对字节/秒**，量程变化只影响归一化，不动原始值；
     * 3) 量程：先判升档 / 降档，再按新量程把每个点重算成百分比。
     */
    private fun appendDiskFrames(snapshot: PerformanceSnapshot, atMs: Long) {

        val disks = snapshot.disks

        if (disks.isEmpty()) return

        val charts = HashMap<String, DiskChartData>(disks.size)

        disks.forEachIndexed { index, disk ->

            val key = diskKeyOf(disk, index)

            val buffers = diskBuffers.getOrPut(key) { DiskBuffers() }

            // 换盘检测：同一个位置上的硬件标识变了 = 这块盘被顶替了。
            // 旧盘攒下的曲线属于上一块盘，接在新盘后面就是假历史，整段丢掉重画。
            // 只有"两边都有值且不同"才算换盘：读不到标识（null）一律不动，
            // 否则一次读失败就会把正常曲线抹掉。
            val hardwareId = disk.wwid?.takeIf { it.isNotBlank() }

            if (hardwareId != null && buffers.wwid != null && buffers.wwid != hardwareId) {
                buffers.reset()
            }

            if (hardwareId != null) {
                buffers.wwid = hardwareId
            }

            appendDiskActivity(buffers, disk.activityPercent, atMs)

            appendDiskRate(buffers, disk.readBytesPerSec, disk.writeBytesPerSec, atMs)

            charts[key] = DiskChartData(
                activity = buffers.activity.toList(),
                read = buffers.readPoints,
                write = buffers.writePoints,
                rangeBytesPerSec = buffers.rangeBytesPerSec
            )
        }

        diskCharts = charts
    }

    /** 活动时间：与 CPU 利用率同一个规矩，缺值不入队，队列空时垫一个 0 起点。 */
    private fun appendDiskActivity(buffers: DiskBuffers, percent: Float?, atMs: Long) {

        val value = percent?.coerceIn(0f, 100f) ?: return

        if (buffers.activity.isEmpty()) {

            buffers.activity.addLast(CpuUsagePoint(timeMs = atMs, percent = 0f))
        }

        buffers.activity.addLast(CpuUsagePoint(timeMs = atMs, percent = value))

        while (buffers.activity.size > MAX_HISTORY_POINTS) {
            buffers.activity.removeFirst()
        }
    }

    /**
     * 读写速率：两条独立的样本队列（读取 / 写入），互不顶替。
     *
     * 只在一帧里两个值都缺失时整帧丢弃 —— 那种情况是"这一轮没采到"，
     * 插 0 会在两条曲线上同时拉出一道假谷。
     */
    private fun appendDiskRate(buffers: DiskBuffers, read: Double?, write: Double?, atMs: Long) {

        if (read == null && write == null) return

        // 第一帧的速率是"开机以来累计差分的第一次结算"，它代表的是整段累计量、
        // 不是这一拍的瞬时速度，直接画上去左端就会顶到天花板，与其它图（都从 0 起）
        // 对不上。所以队列为空时垫一对 0 样本，并**丢掉这一帧的真实值**，
        // 从第二帧起才画真数据。
        if (buffers.readSamples.isEmpty() && buffers.writeSamples.isEmpty()) {

            buffers.readSamples.addLast(RateSample(atMs, 0.0))

            buffers.writeSamples.addLast(RateSample(atMs, 0.0))

        } else {

            buffers.readSamples.addLast(RateSample(atMs, (read ?: 0.0).coerceAtLeast(0.0)))

            buffers.writeSamples.addLast(RateSample(atMs, (write ?: 0.0).coerceAtLeast(0.0)))
        }

        while (buffers.readSamples.size > MAX_HISTORY_POINTS) {
            buffers.readSamples.removeFirst()
        }

        while (buffers.writeSamples.size > MAX_HISTORY_POINTS) {
            buffers.writeSamples.removeFirst()
        }

        // 先定量程，再归一化：这一步做完，下面拿到的每个点都已经是"新量程下的比例"
        buffers.rangeBytesPerSec = resolveDiskRange(buffers, atMs)

        val range = buffers.rangeBytesPerSec

        var readPoints: List<CpuUsagePoint> = emptyList()

        var writePoints: List<CpuUsagePoint> = emptyList()

        buffers.readSamples.zip(buffers.writeSamples).forEach { (readSample, writeSample) ->

            val writePercent = percentOfRange(writeSample.bytesPerSec, range)

            readPoints = readPoints + CpuUsagePoint(
                timeMs = readSample.timeMs,
                percent = percentOfRange(readSample.bytesPerSec, range),
                // 写入曲线借用 kernelPercent 这一列：图上用"内核时间"那套更暗的样式画，
                // 与磁盘页"写入 = 内核线、读取 = 总利用率线"的约定一致
                kernelPercent = writePercent
            )

            writePoints = writePoints + CpuUsagePoint(
                timeMs = writeSample.timeMs,
                percent = writePercent
            )
        }

        buffers.readPoints = readPoints

        buffers.writePoints = writePoints
    }

    /**
     * 量程（图顶端那条线代表多少字节/秒）。
     *
     * 升档：这一帧的最大速率超过当前量程的 50% 就抬到下一档，
     *       抬到"当前速率不超过新量程一半"为止，留足头部空间；
     * 降档：最近 5 秒的速率都低于当前量程的 10%，就降到
     *       "这 5 秒的峰值不超过它一半"的最大一档，避免图表一直贴底。
     *
     * 量程只影响纵轴标尺：历史点存的是绝对速率，换量程后每个点按新标尺重算，
     * 形状不变、高度等比变化 —— 不会出现"旧点还按旧量程画"的错位。
     */
    private fun resolveDiskRange(buffers: DiskBuffers, atMs: Long): Double =

        resolveRateRange(
            current = buffers.rangeBytesPerSec,
            first = buffers.readSamples,
            second = buffers.writeSamples,
            atMs = atMs
        )

    /**
     * 两条速率序列共用一套量程算法（磁盘读写、网卡收发都是这个形状）。
     *
     * 升档：这一帧的峰值超过当前量程的 50% 就抬到阶梯上的下一档，
     *       抬到"峰值不超过新量程一半"为止，留足头部空间；
     * 降档：最近 5 秒的速率都低于当前量程的 10% 才降，
     *       降到"这 5 秒的峰值不超过它一半"的最大一档，避免图表一直贴底。
     * 量程只影响纵轴标尺：曲线里存的是绝对速率，换量程后按新标尺重算，
     * 形状不变、高度等比变化。
     */
    private fun resolveRateRange(
        current: Double,
        first: ArrayDeque<RateSample>,
        second: ArrayDeque<RateSample>,
        atMs: Long
    ): Double {

        val latest = maxOf(
            first.lastOrNull()?.bytesPerSec ?: 0.0,
            second.lastOrNull()?.bytesPerSec ?: 0.0
        )

        // 升档
        if (latest > current * PROMOTE_FRACTION) {

            val needed = latest / HEADROOM_FRACTION

            // 阶梯一直生成到 1 TB/秒，正常设备顶不到头；真顶到了就停在最后一档，
            // 不再按"当前 × 2 / 需求值"临时造量程 —— 那样上限会冒出 123.3 MB/秒 这类数
            return DISK_RANGE_LADDER.firstOrNull { it >= needed } ?: DISK_RANGE_LADDER.last()
        }

        // 降档：只看最近 5 秒，避免一次空闲就把刚抬上去的量程又缩回来
        val recentFirst = first.filter { atMs - it.timeMs <= DEMOTE_WINDOW_MS }

        val recentSecond = second.filter { atMs - it.timeMs <= DEMOTE_WINDOW_MS }

        if (recentFirst.isEmpty() && recentSecond.isEmpty()) return current

        val peak = maxOf(
            recentFirst.maxOfOrNull { it.bytesPerSec } ?: 0.0,
            recentSecond.maxOfOrNull { it.bytesPerSec } ?: 0.0
        )

        if (peak >= current * DEMOTE_FRACTION) return current

        if (current <= MIN_DISK_RANGE_BYTES_PER_SEC) return current

        return DISK_RANGE_LADDER.lastOrNull { it < current && it >= peak / HEADROOM_FRACTION }
            ?: MIN_DISK_RANGE_BYTES_PER_SEC
    }

    /** 绝对速率 -> 占当前量程的百分比（0..100）。 */
    private fun percentOfRange(bytesPerSec: Double, range: Double): Float {

        if (range <= 0.0) return 0f

        return (bytesPerSec / range * 100.0).toFloat().coerceIn(0f, 100f)
    }

    /**
     * 曲线缓存的键：物理短名优先（磁盘 node=块名、网卡 iface、显卡 node=来源节点），
     * 其次底层 id，最后本帧下标。
     *
     * **wwid 不参与建键**。它是硬件序列号那类字符串，一帧读不到、下一帧又读到，
     * 键就换了：同一块盘的曲线会被判成两台设备，旧的留在原键下再没人取，
     * 屏幕上就表现成曲线突然断掉重来。建键只能用一帧里必然存在、不会凭空消失的东西。
     * 换盘不靠换键来区分，靠 [DiskBuffers.wwid] 的变化把这块盘的曲线清零重画。
     */
    private fun chartKeyOf(identity: String?, id: String?, index: Int): String =
        identity?.takeIf { it.isNotBlank() }
            ?: id?.takeIf { it.isNotBlank() }
            ?: "index:$index"

    private fun diskKeyOf(disk: DiskSnapshot, index: Int): String =
        chartKeyOf(disk.node, disk.id, index)

    /**
     * 一帧里的每张显卡各攒三条曲线。
     *
     * 三条曲线是同一拍入队的：利用率（0..100%）与两类显存的占用率。
     * 显存占用率要"总量"当分母：总量读不到就这一条不入队（画空），
     * 不能把"不知道总量"画成 0%。
     */
    private fun appendGpuFrames(snapshot: PerformanceSnapshot, atMs: Long) {

        val gpus = snapshot.gpus

        if (gpus.isEmpty()) {

            if (gpuCharts.isNotEmpty()) gpuCharts = emptyMap()

            return
        }

        val charts = HashMap<String, GpuChartData>(gpus.size)

        gpus.forEachIndexed { index, gpu ->

            val key = chartKeyOf(gpu.node, gpu.id, index)

            val buffers = gpuBuffers.getOrPut(key) { GpuBuffers() }

            appendPercent(buffers.utilization, gpu.utilizationPercent, atMs)

            appendPercent(buffers.dedicated, gpuFraction(gpu.dedicatedUsedBytes, gpu.dedicatedTotalBytes), atMs)

            appendPercent(buffers.shared, gpuFraction(gpu.sharedUsedBytes, gpu.sharedTotalBytes), atMs)

            charts[key] = GpuChartData(
                utilization = buffers.utilization.toList(),
                dedicated = buffers.dedicated.toList(),
                shared = buffers.shared.toList()
            )
        }

        gpuCharts = charts
    }

    /** 一条百分比曲线入队（与 CPU 利用率同一个规矩：缺值不入队，空队列先垫 0 起点）。 */
    private fun appendPercent(queue: ArrayDeque<CpuUsagePoint>, percent: Float?, atMs: Long) {

        val value = percent?.coerceIn(0f, 100f) ?: return

        if (queue.isEmpty()) {

            queue.addLast(CpuUsagePoint(timeMs = atMs, percent = 0f))
        }

        queue.addLast(CpuUsagePoint(timeMs = atMs, percent = value))

        while (queue.size > MAX_HISTORY_POINTS) {
            queue.removeFirst()
        }
    }

    /** 显存占用量 -> 百分比；总量缺失或 0 时返回 null（这一条不入队）。 */
    private fun gpuFraction(used: Long?, total: Long?): Float? {

        if (used == null || total == null || total <= 0L || used < 0L) return null

        return (used.toDouble() / total.toDouble() * 100.0).toFloat().coerceIn(0f, 100f)
    }

    /**
     * 一帧里的每块网卡各攒两条速率曲线（发送 / 接收）。
     *
     * 与磁盘的速率图完全同一套算法：存绝对字节/秒 → 定量程 → 按量程归一化成百分比，
     * 这样量程升降时曲线形状不变、只等比缩放。
     */
    private fun appendNetworkFrames(snapshot: PerformanceSnapshot, atMs: Long) {

        val networks = snapshot.networks

        if (networks.isEmpty()) {

            // 网卡全没了就把缓存清掉：下次插回来从新曲线开始，不接一段断层
            if (networkCharts.isNotEmpty()) networkCharts = emptyMap()

            return
        }

        val charts = HashMap<String, NetworkChartData>(networks.size)

        networks.forEachIndexed { index, network ->

            val key = chartKeyOf(network.iface, network.id, index)

            val buffers = networkBuffers.getOrPut(key) { NetworkBuffers() }

            appendNetworkRate(buffers, network.sendBytesPerSec, network.receiveBytesPerSec, atMs)

            charts[key] = NetworkChartData(
                send = buffers.sendPoints,
                receive = buffers.receivePoints,
                rangeBytesPerSec = buffers.rangeBytesPerSec
            )
        }

        networkCharts = charts
    }

    /**
     * 收发速率入队：两个值都缺失就整帧丢弃（那是"这一轮没采到"）；
     * 队列为空时垫一对 0 起点并丢掉首帧真实值，与磁盘速率图同一套规矩。
     */
    private fun appendNetworkRate(
        buffers: NetworkBuffers,
        send: Double?,
        receive: Double?,
        atMs: Long
    ) {

        if (send == null && receive == null) return

        // 与磁盘速率图完全同一套规矩：空队列时先垫一对 0，并**丢掉这一帧的真实值**。
        // 第一帧速率是"开机以来累计计数的第一次差分"，代表整段累计量而不是这一拍，
        // 直接画上去左端会顶到天花板，与其它图（都从 0 起）对不上。
        if (buffers.sendSamples.isEmpty() && buffers.receiveSamples.isEmpty()) {

            buffers.sendSamples.addLast(RateSample(atMs, 0.0))

            buffers.receiveSamples.addLast(RateSample(atMs, 0.0))

        } else {

            buffers.sendSamples.addLast(RateSample(atMs, (send ?: 0.0).coerceAtLeast(0.0)))

            buffers.receiveSamples.addLast(RateSample(atMs, (receive ?: 0.0).coerceAtLeast(0.0)))
        }

        while (buffers.sendSamples.size > MAX_HISTORY_POINTS) {
            buffers.sendSamples.removeFirst()
        }

        while (buffers.receiveSamples.size > MAX_HISTORY_POINTS) {
            buffers.receiveSamples.removeFirst()
        }

        buffers.rangeBytesPerSec = resolveRateRange(
            current = buffers.rangeBytesPerSec,
            first = buffers.receiveSamples,
            second = buffers.sendSamples,
            atMs = atMs
        )

        val range = buffers.rangeBytesPerSec

        var sendPoints: List<CpuUsagePoint> = emptyList()

        var receivePoints: List<CpuUsagePoint> = emptyList()

        buffers.sendSamples.zip(buffers.receiveSamples).forEach { (sendSample, receiveSample) ->

            sendPoints = sendPoints + CpuUsagePoint(
                timeMs = sendSample.timeMs,
                percent = percentOfRange(sendSample.bytesPerSec, range)
            )

            receivePoints = receivePoints + CpuUsagePoint(
                timeMs = receiveSample.timeMs,
                percent = percentOfRange(receiveSample.bytesPerSec, range)
            )
        }

        buffers.sendPoints = sendPoints

        buffers.receivePoints = receivePoints
    }

    /** 一张显卡的原始缓存（不进 Compose 状态，只在采集线程里改）。 */
    private class GpuBuffers {

        val utilization = ArrayDeque<CpuUsagePoint>()

        val dedicated = ArrayDeque<CpuUsagePoint>()

        val shared = ArrayDeque<CpuUsagePoint>()
    }

    /** 一块网卡的原始缓存（不进 Compose 状态，只在采集线程里改）。 */
    private class NetworkBuffers {

        val sendSamples = ArrayDeque<RateSample>()

        val receiveSamples = ArrayDeque<RateSample>()

        var rangeBytesPerSec: Double = MIN_DISK_RANGE_BYTES_PER_SEC

        var sendPoints: List<CpuUsagePoint> = emptyList()

        var receivePoints: List<CpuUsagePoint> = emptyList()
    }

    /** 一台磁盘的原始缓存（不进 Compose 状态，只在采集线程里改）。 */
    private class DiskBuffers {

        /**
         * 这一段曲线属于哪块盘（硬件标识 wwid / 序列号）。
         *
         * 不参与建键，只用来发现"这个位置换了盘"。null = 平台没给标识，无从判断。
         */
        var wwid: String? = null

        val activity = ArrayDeque<CpuUsagePoint>()

        val readSamples = ArrayDeque<RateSample>()

        val writeSamples = ArrayDeque<RateSample>()

        var rangeBytesPerSec: Double = MIN_DISK_RANGE_BYTES_PER_SEC

        /** 已归一化的读 / 写曲线，供页面直接画。 */
        var readPoints: List<CpuUsagePoint> = emptyList()

        var writePoints: List<CpuUsagePoint> = emptyList()

        /**
         * 清空这段历史，回到"刚插上一块新盘"的状态。
         *
         * 量程也一并回到最小档：新盘的速率与旧盘无关，接着旧盘的大档会把
         * 纵轴压得看不见起伏。清空后第一帧照常垫 0 起点（见 appendDiskRate）。
         */
        fun reset() {
            activity.clear()
            readSamples.clear()
            writeSamples.clear()
            rangeBytesPerSec = MIN_DISK_RANGE_BYTES_PER_SEC
            readPoints = emptyList()
            writePoints = emptyList()
        }
    }

    /** 一次速率采样：时刻 + 绝对字节/秒。 */
    private data class RateSample(val timeMs: Long, val bytesPerSec: Double)

    // ==================== 8. 内部：设置与文案 ====================

    private fun refreshWindowLabel() {

        val code = service?.performanceChartWindowLabel() ?: return

        if (code != windowCode) {
            windowCode = code
        }
    }

    private fun refreshUsageDecimals() {

        val decimals = SettingsCatalog.cpuUsageDecimals(
            service?.settings()?.cpuUsagePrecision ?: SettingsDefaults.cpuUsagePrecision
        )

        if (decimals != usageDecimals) {
            usageDecimals = decimals
        }
    }

    /** 落盘：取快照再交给写盘线程，连续点击时写下的就是最后一次的值。 */
    private fun persistPreferences() {

        val logical = logicalGraph

        val kernel = showKernelTime

        preferenceWriter.execute {
            ChartViewStore.save(appContext, logical, kernel)
        }
    }

    private companion object {

        /**
         * 队列上限：按最长窗口算 —— 低档 240 秒、最短采样间隔 0.5 秒，
         * 240 / 0.5 = 480 点，取 512 留余量。超过丢最老的。
         */
        const val MAX_HISTORY_POINTS = 512

        /** 服务还没给窗口文案之前的默认值（与常规档一致）。 */
        const val DEFAULT_WINDOW_SECONDS = 60

        /** 当前选中硬件的名字里"磁盘"这一项的取值（存名字不存枚举，与 UI 解耦）。 */
        const val KIND_DISK = "DISK"

        /** 当前选中硬件里"网卡"这一项的取值。 */
        const val KIND_NETWORK = "NETWORK"

        /** 当前选中硬件里"显卡"这一项的取值（多实例，序号要参与选中比较）。 */
        const val KIND_GPU = "GPU"

        /** 协议里"无线局域网接口"的角色取值（与底层 TM_NET_ROLE_WIFI 同一个字）。 */
        const val ROLE_WIFI = "WIFI"

        /** 速率图的最小量程：100 KB/秒（用户的默认值）。 */
        const val MIN_DISK_RANGE_BYTES_PER_SEC = 100.0 * 1024.0

        /** 升档阈值：当前速率超过量程的 50% 就抬。 */
        const val PROMOTE_FRACTION = 0.5

        /** 每次抬档都留一半头部空间。 */
        const val HEADROOM_FRACTION = 0.5

        /** 降档阈值：最近 5 秒都低于量程的 10% 才降。 */
        const val DEMOTE_FRACTION = 0.1

        /** 降档观察窗口：5 秒。 */
        const val DEMOTE_WINDOW_MS = 5_000L

        /**
         * 量程阶梯：1 / 2.5 / 5 × 10^n 个 100 KB，也就是
         * 100 KB、250 KB、500 KB、1 MB、2.5 MB、5 MB、10 MB … 一直到 1 TB/秒。
         *
         * 自动生成而不是手写一张表：表短了会走到"临时造量程"的兜底分支，
         * 那个分支算出来的上限不是整数（123.3 MB/秒），纵轴标签就难看了。
         * 用固定阶梯的意义也是这个：上限永远读作 100 / 250 / 500 这种数。
         */
        val DISK_RANGE_LADDER: List<Double> = buildList {

            var decade = MIN_DISK_RANGE_BYTES_PER_SEC

            while (decade <= 1.0e12) {

                for (step in doubleArrayOf(1.0, 2.5, 5.0)) {
                    add(decade * step)
                }

                decade *= 10.0
            }
        }
    }
}

/**
 * 无线局域网的界面名称：地区在中国就用 WLAN，其它地区用 Wi-Fi。
 *
 * 依据两条：
 * 1. "Wi-Fi" 是 Wi-Fi 联盟的商标，官方写法就是大写 W、F 加中间连字符，
 *    WiFi / WIFI 都不是被认可的写法，所以英文环境写 Wi-Fi；
 * 2. 简体中文的 Windows 任务管理器把这一类叫 WLAN（无线局域网），
 *    中文用户在前几版截图里看到的也是 WLAN，所以中国地区沿用 WLAN。
 *
 * 判定只看地区，不看硬件：语言是 zh-CN / zh-Hans-CN 一类的地区都会落到 CN；
 * 某些设备把国家留空（只给语言），这时才退一步用"语言是中文"判断。
 * 这是用户体验问题，所以放在管理器而不是底层（底层只报 role=WIFI 这个事实）。
 */
private fun resolveNetworkDisplayName(context: Context): String {

    val configuration = context.resources.configuration

    val locales = configuration.locales

    val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]

    val country = locale.country.uppercase(Locale.US)

    val isChina = country == "CN" || country == "CHN" ||
        (country.isEmpty() && locale.language.equals("zh", ignoreCase = true))

    return if (isChina) "WLAN" else "Wi-Fi"
}

/**
 * 图表画图偏好的落盘（性能页私有设置）。
 *
 * 为什么放在管理器这一层而不是页面里：这三处宿主（竖屏 / 横屏 / 浮窗）
 * 与图形摘要视图读的是同一份值，写盘也只该由数据持有者做。
 * 写入方式与 settings.json 一致（.tmp -> rename），失败只返回 false，绝不抛异常。
 */
internal object ChartViewStore {

    private const val CONFIG_DIR = "config"

    private const val FILE_NAME = "chart_view.json"

    private const val TMP_SUFFIX = ".tmp"

    private const val KEY_LOGICAL_GRAPH = "logical_graph"

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
     * （总体利用率、不画内核时间）。
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
     * 调用方负责线程（管理器用单线程写盘）；失败只返回 false，不抛异常。
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
 * 一块磁盘交给页面的图表数据（只读快照）。
 *
 * 曲线已经是"当前量程下的百分比"：页面只负责按 0..100 画，
 * 不需要自己再除一次量程，也就不会出现"图画的时候量程又变了"的错位。
 *
 * @param activity        活动时间曲线（percent）
 * @param read            读取速率曲线（percent；kernelPercent 里带同一时刻的写入比例）
 * @param write           写入速率曲线（percent）
 * @param rangeBytesPerSec 当前量程（图顶端那条线 = 多少字节/秒）
 */
data class DiskChartData(
    val activity: List<CpuUsagePoint>,
    val read: List<CpuUsagePoint>,
    val write: List<CpuUsagePoint>,
    val rangeBytesPerSec: Double
) {

    /** 图右上角的量程文案，例 `100 KB/秒`。 */
    fun rangeText(): String = diskRateText(rangeBytesPerSec)

    /**
     * 60% 那条线旁边的文案：量程的 60%，收成整齐的数（例 `600 KB/秒`、`1.5 MB/秒`）。
     *
     * 线的位置仍按精确的 60% 画，只是文字取整到一位有效数字，
     * 免得 1 MB 的量程标出一条 `614 KB/秒` 这种读起来别扭的数。
     */
    fun sixtyPercentText(): String = diskRateTextNice(rangeBytesPerSec * 0.6)

    companion object {

        /** 还没有采样时的空壳（量程用默认 100 KB/秒，页面据此先画出标尺）。 */
        val EMPTY: DiskChartData = DiskChartData(
            activity = emptyList(),
            read = emptyList(),
            write = emptyList(),
            rangeBytesPerSec = 100.0 * 1024.0
        )
    }
}

/**
 * 一块网卡交给页面的吞吐量图数据（只读快照）。
 *
 * 与 [DiskChartData] 同一个契约：曲线已经是"当前量程下的百分比"，
 * 页面按 0..100 画；量程与 60% 标注直接取这里的文案。
 *
 * @param send            发送速率曲线（percent，粉色那条）
 * @param receive         接收速率曲线（percent，紫色那条）
 * @param rangeBytesPerSec 当前量程（右侧那行「5 Mbps」就是它）
 */
data class NetworkChartData(
    val send: List<CpuUsagePoint>,
    val receive: List<CpuUsagePoint>,
    val rangeBytesPerSec: Double
) {

    /** 图右上角的量程文案，例 `5 Mbps`（按比特算，与网卡的计量习惯一致）。 */
    fun rangeText(): String = networkRateText(rangeBytesPerSec)

    /** 60% 那条线旁边的文案：量程的 60%，按同一套速率写法取整。 */
    fun sixtyPercentText(): String = networkRateText(rangeBytesPerSec * 0.6)

    companion object {

        /** 还没有采样时的空壳（量程默认 100 KB/秒 ≈ 820 Kbps）。 */
        val EMPTY: NetworkChartData = NetworkChartData(
            send = emptyList(),
            receive = emptyList(),
            rangeBytesPerSec = 100.0 * 1024.0
        )
    }
}

/**
 * 一张显卡交给页面的图表数据（只读快照）。
 *
 * 三张图共用同一份"0..100%"的契约：
 * - [utilization] 3D 利用率，纵轴固定 100%，顶端刻度就是 100%；
 * - [dedicated]   专用显存占用率（已用 / 专用总量），顶端刻度是专用总量（4.0 GB）；
 * - [shared]      共享显存占用率，顶端刻度是共享总量（7.9 GB）。
 *
 * 显存那条曲线只在"总量读得到"时才有数据：没有总量就没有分母，
 * 画成 0% 等于告诉用户"显存一个字节都没用"，那是假的。
 */
data class GpuChartData(
    val utilization: List<CpuUsagePoint>,
    val dedicated: List<CpuUsagePoint>,
    val shared: List<CpuUsagePoint>
) {

    companion object {

        /** 还没有采样时的空壳：三张图都只有坐标系。 */
        val EMPTY: GpuChartData = GpuChartData(
            utilization = emptyList(),
            dedicated = emptyList(),
            shared = emptyList()
        )
    }
}
