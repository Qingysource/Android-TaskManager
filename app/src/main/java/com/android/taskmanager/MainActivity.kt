/**
 * ============================================================
 * TaskManager 应用入口
 * ============================================================
 *
 * 职责（只有这四件事，不要再往里加第五件）：
 * 1. 启动快速读取：只取"默认起始页 + 是否置顶"两个值（SettingsStore.loadStartup），
 *    取不到就用默认值（进程页 / 不置顶），不等 UIService；
 * 2. 启动服务：只启动 UIService。AndroidService / LinuxService 由 UIService 自己拉起与回收；
 * 3. 初始化两条权限通道：ShizukuManager（Shizuku、ADB Shell 身份）、RootManager（Root 身份）；
 * 4. 用设置里的"默认起始页"决定首屏，再用 Jetpack Compose 搭界面：
 *    宽屏（>= 600dp）左侧可折叠导航栏 + 内容区；窄屏抽屉导航 + 顶栏。
 *
 * [OVERLAY-v1] 实验分支：设置要求"置于顶层"且已有悬浮窗权限时，本 Activity 不画界面，
 * 而是让 UIService 把同一批页面画进一个近乎全屏的浮窗里，自己立刻结束。
 * 回退方式：删掉本文件里所有带 [OVERLAY-v1] 的段落即可，其余逻辑不受影响。
 *
 * 数据来源约定：
 * - 页面要数据找 UIService（IUIServiceApi / IPageDataApi），不要自己去调上游服务；
 * - 页面改设置走 onSettingChanged 回调，落盘统一由 UIService 负责；
 * - 权限细节不暴露给页面，统一由 ShizukuManager / RootManager 提供。
 */
package com.android.taskmanager

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

import com.android.taskmanager.service.SettingsStore
import com.android.taskmanager.service.UIService
import com.android.taskmanager.service.ShizukuManager
import com.android.taskmanager.service.RootManager

// ============================================================
// Activity Entry
// ============================================================

/**
 * 应用唯一的 Activity。
 *
 * 采用"单 Activity + Compose 多页面"结构：切页只改变 Compose 状态，
 * 不新建 Activity，避免权限通道（Shizuku/Root）与 Service 被重复初始化。
 */
class MainActivity : ComponentActivity() {

    companion object {

        /**
         * 主界面当前是否可见（onResume 置 true，onPause 置 false）。
         *
         * 为什么不只靠上报 UIService：首次启动时 onResume 常常跑在 Service.onCreate 之前，
         * 那一刻 [UIService.instanceOrNull] 还是 null，`setAppForeground(true)` 直接丢掉，
         * 之后也没有任何时机再补一次。结果就是服务一直以为界面在后台，
         * [UIService.isUiRefreshAllowed] 恒为 false —— 详细信息页读不到表，
         * 屏幕上就只剩一句"暂无可见列"。
         *
         * 把状态放在 Activity 的静态字段里，服务改成**拉**这个值，就不再依赖"上报有没有赶上车"。
         */
        @Volatile
        var isResumed: Boolean = false
            private set

        /**
         * 主界面当前停在哪一页（与 [MainActivity.onPageChanged] 同步）。
         *
         * 与 [isResumed] 同样的理由：`setActivePage()` 也可能因为服务实例还没创建而丢，
         * 那服务就会一直按"进程"页算，详细信息页永远没人替它采数据。
         * 服务可以在这里**拉**到真实页名，不再依赖那一次上报是否成功。
         */
        @Volatile
        var currentPage: String = "进程"
            private set
    }

    /**
     * 当前界面停留在哪一页（中文名，与 TaskPage 的导航标题一致）。
     *
     * 用途：打开"置于顶层"时把这一页记成一次性记录（last_page），
     * 让重启后的浮窗直接停在同一页，而不是跳回默认起始页。
     * 由 MainPage 通过 onPageChanged 持续上报。
     */
    private var currentPageName: String = "进程"

    /**
     * 前台状态上报：定时刷新驱动页面内容要求"主界面在前台 或 浮窗可见"。
     *
     * 用 onResume/onPause 而不是 onStart/onStop：分屏、被半透明弹窗盖住这些
     * "还看得见但没焦点"的情况仍算前台，页面该继续更新。
     */
    override fun onResume() {
        super.onResume()

        isResumed = true

        UIService.instanceOrNull()?.setAppForeground(true)
    }

    override fun onPause() {
        isResumed = false

        UIService.instanceOrNull()?.setAppForeground(false)

        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ① 启动快速读取：只要三个值（默认起始页 / 是否置顶 / 一次性"回原位"记录），
        //    读不到就回落默认（进程页、不置顶）。不等 UIService：
        //    Service 是异步创建的，等它只会把首屏往后拖。
        val startup = SettingsStore.loadStartup(this)

        // ①-补 主题：把设置里的"应用主题"先落进进程级共享状态（ThemeState），
        //    这样第一帧 setContent 就已经是对的颜色，不会出现"先白一下再变黑"。
        //    走到浮窗分支也好用：浮窗里的 ComposeView 读的是同一个状态，不用再读盘。
        ThemeState.mode.value = normalizeThemeMode(startup.appTheme)

        // ② [OVERLAY-v1] 浮窗模式判定：设置要求置顶 + 系统已授予悬浮窗权限，
        //    且这次不是"浮窗建失败后的兜底启动"（force_normal）。
        //    少任何一条都退回普通界面——绝不因为权限缺失或浮窗异常把启动卡住。
        val forceNormal = intent?.getBooleanExtra(UIService.EXTRA_FORCE_NORMAL, false) ?: false

        val overlayMode = startup.alwaysOnTop && Settings.canDrawOverlays(this) && !forceNormal

        // ③ 首屏：优先用"一次性回原位"记录（last_page），没有才用默认起始页。
        //    last_page 是"开 / 关置于顶层"那一刻记下的页面，启动时消费一次就清空，
        //    所以它不会变成第二个"默认页"；"默认起始页不准选调试 / 设置"那条规则照旧。
        val pendingPage = startup.lastPage

        val requestedPageName = if (pendingPage.isNotBlank()) pendingPage else startup.startPage

        val startPage = resolveStartPage(
            name = requestedPageName,
            allowAuxiliaryPages = pendingPage.isNotBlank()
        )

        // 最终定下来的页名：浮窗接管时要传的是它，而不是原始配置值
        val startPageName = navigationTitle(startPage)

        // 消费时机分两种，别在这里一刀切：
        // - 普通界面：现在就消费（首屏已经定下来了，记录没用了）；
        // - 浮窗接管：留给 UIService 在"窗口真的挂上"之后再清——
        //   否则建窗失败走兜底启动时记录已经没了，普通界面就会落到默认页（进程）。
        if (pendingPage.isNotBlank() && !overlayMode) {
            SettingsStore.update(this, SettingKey.LAST_PAGE, "")
        }

        // ④ 启动 UIService：它是数据中枢，AndroidService / LinuxService 由它拉起与回收。
        //    浮窗模式下把"请把界面画到浮窗上"的请求一起发过去，
        //    这样不用等 Service 起来后再补一次调用，也不会和 onCreate 抢时序。
        //
        //    用 startForegroundService（ContextCompat 会在 API < 26 上退化为 startService）：
        //    本服务是常驻前台服务，界面关掉后它要继续采集数据、维持上游服务与身份，
        //    普通 startService 起来的是"后台服务"，退到后台一分钟左右就会被系统回收。
        val serviceIntent = Intent(this, UIService::class.java)

        if (overlayMode) {
            serviceIntent.action = UIService.ACTION_SHOW_OVERLAY

            serviceIntent.putExtra(UIService.EXTRA_OVERLAY_PAGE, startPageName)
        }

        ContextCompat.startForegroundService(this, serviceIntent)

        // ⑤ 初始化两条权限通道：Shizuku（ADB / Shell 身份）、Root（uid 0 身份）。
        //    浮窗模式下本 Activity 马上要结束，所以传 applicationContext，
        //    免得管理器长期持有即将销毁的界面 Context。
        val channelContext = if (overlayMode) applicationContext else this

        ShizukuManager.initialize(channelContext)
        RootManager.initialize(channelContext)

        // ⑥ [OVERLAY-v1] 浮窗接管：窗口由 UIService 创建，本 Activity 不画界面。
        //    必须把自己结束掉，否则浮窗后面还压着一份同样的界面，返回时会看到两份。
        if (overlayMode) {
            // 这一支里本 Activity 只是过渡壳：onCreate 不 setContent，
            // 窗口里不会画任何东西，默认主题底色在深色主题下就是"一整片黑"。
            // 万一浮窗建得慢（或建失败），用户看到的就是"黑屏、什么都没有"。
            // 所以先把窗口底色刷成透明：哪怕这层壳多留了一两帧，也不会有黑底。
            //
            // 仍然用 finish() 而不是 finishAndRemoveTask()：后者会把任务一起摘掉。
            // 现在 UIService 是常驻前台服务（startForegroundService 拉起、onCreate 里立即
            // startForeground），本来就不依赖这个任务存活，所以这里只结束过渡壳即可。
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

            finish()

            return
        }

        // ⑦ 页面问"当前设置"时，向 UIService 要（它持有真值）；
        //    服务还没起来就现读一次存储——命中的是同一份缓存，不会重复解析
        val settingsProvider: () -> SettingsValues = {
            UIService.instanceOrNull()?.settings() ?: SettingsStore.load(this).values
        }

        // ⑧ 设置变更统一交给 UIService 落盘；
        //    服务还没起来（首次启动的极短窗口）时退化为直接写文件，保证这次修改不丢
        val changeSink: (SettingKey, Any) -> Unit = { key, value ->
            // [THEME] 主题先本地生效，再交给服务落盘：
            // 改主题是"立刻看得见"的操作，不该等 Service 起来、也不该等一次写盘。
            // 写的是同一个进程级状态（ThemeState），浮窗里的界面会跟着一起变。
            if (key == SettingKey.APP_THEME) {
                ThemeState.mode.value = normalizeThemeMode(value as? String)
            }

            // [OVERLAY-v1] 打开"置于顶层"的那一刻，先把用户所在页记下来：
            // 软重启后浮窗就用这一页当首屏（一次性记录，见 SettingKey.LAST_PAGE）。
            // 只在 true 时记：取消勾选 / 关闭置顶不写，免得留下无用记录。
            //
            // 而且必须"真能开得起浮窗"才记：没有悬浮窗权限时这次勾选不会生效
            // （存储层会把置顶改回 false），此时再留一条 last_page 就变成脏便条，
            // 下次普通启动会莫名其妙停在这一页。
            if (key == SettingKey.WINDOW_ALWAYS_ON_TOP && value == true &&
                Settings.canDrawOverlays(this)
            ) {
                SettingsStore.update(this, SettingKey.LAST_PAGE, currentPageName)
            }

            val service = UIService.instanceOrNull()

            if (service != null) {
                service.onSettingChanged(key, value)
            } else {
                SettingsStore.update(this, key, value)
            }
        }

        // ⑨ 装配 Compose 界面：TaskManagerTheme 负责配色（跟随系统 / 动态取色），
        //    其下承载 MainPage()，内部自行分发各页面。
        //    主题状态是进程级共享的（ThemeState），设置页改主题时这里会自动重组，
        //    不需要重启应用，浮窗那侧也同步变色。
        setContent {
            TaskManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainPage(
                        initialPage = startPage,
                        settingsProvider = settingsProvider,
                        onSettingChanged = changeSink,
                        onPageChanged = { page ->
                            currentPageName = page

                            // 静态字段先在本地记下，服务没起来也不会丢（服务会来"拉"）
                            currentPage = page

                            // 刷新调度只服务前台页：切页要立刻告诉服务，否则它会继续替上一页拉数据
                            UIService.instanceOrNull()?.setActivePage(page)
                        }
                    )
                }
            }
        }
    }
}

// ============================================================
// Navigation Definition
// ============================================================

/**
 * 任务管理器的全部功能页面。
 *
 * 新增页面时必须同步修改下面四处，否则页面无法显示：
 * 1. navigationTitle() —— 页面标题
 * 2. XxxPageState      —— 页面自报的实现状态（写在该页的文件顶部）
 * 3. isPageSupported() —— 设备能力判定
 * 4. PageContent()     —— 页面分发
 */
private enum class TaskPage {
    Processes,
    Performance,
    AppHistory,
    StartupApps,
    Users,
    Details,
    Services,
    Debug,
    Settings
}

/** 导航中的一项：目标页面、标题、副标题与图标。 */
private data class NavigationItem(
    val page: TaskPage,
    val title: String,
    val description: String,
    val icon: ImageVector
)

/** 主功能页面列表（"设置"单独定义，固定放在导航栏底部）。 */
private val primaryNavigationItems = listOf(
    NavigationItem(
        TaskPage.Processes,
        "进程",
        "查看正在运行的进程",
        Icons.Default.Apps
    ),
    NavigationItem(
        TaskPage.Performance,
        "性能",
        "CPU、内存、磁盘与网络",
        Icons.Default.BarChart
    ),
    NavigationItem(
        TaskPage.AppHistory,
        "应用历史记录",
        "查看应用资源使用历史",
        Icons.Default.History
    ),
    NavigationItem(
        TaskPage.StartupApps,
        "启动应用",
        "管理应用启动项",
        Icons.Default.PlayArrow
    ),
    NavigationItem(
        TaskPage.Users,
        "用户",
        "查看系统用户信息",
        Icons.Default.SupervisorAccount
    ),
    NavigationItem(
        TaskPage.Details,
        "详细信息",
        "查看进程详细属性",
        Icons.Default.Info
    ),
    NavigationItem(
        TaskPage.Services,
        "服务",
        "查看应用关联服务",
        Icons.Default.Storage
    ),
    NavigationItem(
        TaskPage.Debug,
        "调试",
        "开发测试与诊断",
        Icons.Default.BugReport
    )
)

private val settingsNavigationItem = NavigationItem(
    TaskPage.Settings,
    "设置",
    "配置任务管理器",
    Icons.Default.Settings
)

// ============================================================
// Start Page Resolution
// ============================================================

/**
 * 把设置里的"默认起始页"（中文页面名）解析成导航枚举。
 *
 * 用 when 而不是按 SettingsCatalog.START_PAGES 的下标取枚举，是因为：
 * 页面名称是持久化数据，改名时这里必须"看得见"地跟着改，而不是悄悄错位。
 * 未知名称（配置文件被手改坏）一律回落进程页——一条设置绝不能把应用卡在空白首屏。
 *
 * 另外：即使有人手改 config 把起始页写成"调试"/"设置"，这里也当作非法参数，
 * 直接回落进程页（存储层 SettingsStore 也会把它修正回"进程"并回写文件，
 * 这里是第二道闸，防止有别的调用方绕开存储层传进来一个裸字符串）。
 *
 * @param allowAuxiliaryPages 是否允许"调试""设置"当首屏。
 *        只有"一次性回原位"（last_page）这条路会传 true——那是有明确来处的落点，
 *        用户上一刻就停在那两页里；默认起始页永远传 false。
 */
private fun resolveStartPage(name: String, allowAuxiliaryPages: Boolean = false): TaskPage {
    return when (name) {
        "性能" -> TaskPage.Performance
        "应用历史记录" -> TaskPage.AppHistory
        "启动应用" -> TaskPage.StartupApps
        "用户" -> TaskPage.Users
        "详细信息" -> TaskPage.Details
        "服务" -> TaskPage.Services

        // "调试" / "设置"：作为默认起始页一律非法（回落进程页）；
        // 只有在"回原位"时才是合法落点
        "调试" -> if (allowAuxiliaryPages) TaskPage.Debug else TaskPage.Processes
        "设置" -> if (allowAuxiliaryPages) TaskPage.Settings else TaskPage.Processes

        else -> TaskPage.Processes
    }
}

// ============================================================
// Page Availability
// ============================================================

/**
 * 汇总每个页面自报的实现状态（页面 -> 状态的一张表）。
 *
 * 状态不在这里判死：真正的值写在各自的页面文件顶部
 * （ProcessPageState / PerformancePageState / …），这里只是把 9 个入口接起来。
 * 这样"某页写完了"只需要改它自己文件里那一行，不用回来动主活动。
 *
 * 新增页面时：TaskPage 加一项 + 这里加一条 + navigationTitle() 加一条，三处对齐。
 */
private fun declaredPageState(page: TaskPage): PageState {
    return when (page) {
        TaskPage.Processes -> ProcessPageState
        TaskPage.Performance -> PerformancePageState
        TaskPage.AppHistory -> ApphistoryPageState
        TaskPage.StartupApps -> StartupAppPageState
        TaskPage.Users -> UsersPageState
        TaskPage.Details -> DetailsPageState
        TaskPage.Services -> ServicesPageState
        TaskPage.Debug -> DebugPageState
        TaskPage.Settings -> SettingsPageState
    }
}

/**
 * 一个页面这一刻到底是什么状态：先看"写没写"，再看"能不能用"。
 *
 * 顺序不能反：
 * 1) 页面自己说 NOT_IMPLEMENTED → 直接就是"不受支持"，不再去做能力探测
 *    （未实现的页谈"缺权限"没有意义，只会误导用户去开权限）；
 * 2) 页面说已实现，但设备能力不足 → UNAVAILABLE（提示与权限有关，文案不同）。
 */
private fun pageStateOf(page: TaskPage): PageState {
    val declared = declaredPageState(page)

    if (declared != PageState.IMPLEMENTED) {
        return declared
    }

    return if (isPageSupported(page)) PageState.IMPLEMENTED else PageState.UNAVAILABLE
}

/**
 * 判断当前设备或系统环境是否支持某个页面（**只管能力，不管实现进度**）。
 *
 * 预留实现接口：
 * 目前所有页面都返回 true，这里后续应改成真实的能力探测，例如：
 * - 进程页 / 服务页 / 用户页：需要 Root 或 Shizuku 已授权（读 ShizukuManager.status）；
 * - 性能页 / 内核内存转储相关：需要读取 /proc 的权限；
 * - 返回 false 时由 PageContent() 渲染 PageUnavailableScreen() 兜底（"页面不可用"）。
 *
 * 注意：一个页面先要"已实现"（PageState.IMPLEMENTED）才会走到这里，
 * 所以这里写 false 的效果是"这页写了，但这台设备用不了"，不是"这页没写"。
 */
private fun isPageSupported(page: TaskPage): Boolean {
    return when (page) {
        TaskPage.Processes,
        TaskPage.Performance,
        TaskPage.AppHistory,
        TaskPage.StartupApps,
        TaskPage.Users,
        TaskPage.Details,
        TaskPage.Services,
        TaskPage.Debug,
        TaskPage.Settings -> true
    }
}

// ============================================================
// Main Window / Navigation Controller
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * 主窗口：按屏幕宽度选择导航形态，并把当前选中的页面交给 PageContent() 渲染。
 *
 * - 宽屏（>= 600dp）：常驻左侧导航栏，可在展开态与图标态之间切换；
 * - 窄屏：抽屉导航 + TopAppBar，抽屉开合由 DrawerState 控制。
 *
 * @param initialPage 首屏页面：MainActivity 按设置里的"默认起始页"解析后传入
 * @param settingsProvider 取"当前设置"的方式：每次进设置页都现取，
 *        保证切页回来看到的不是启动时的旧快照
 * @param onSettingChanged 设置变更回调，MainActivity 已把它接到 UIService 的落盘入口
 */
private fun MainPage(
    initialPage: TaskPage = TaskPage.Processes,
    settingsProvider: () -> SettingsValues = { SettingsApi.defaultValues },
    onSettingChanged: (SettingKey, Any) -> Unit = { _, _ -> },

    /**
     * 当前页变化时上报（参数是页面中文名）。
     *
     * 供 MainActivity 记录"切浮窗 / 回普通界面"的落点；默认空实现，
     * 预览与测试调用不受影响。
     */
    onPageChanged: (String) -> Unit = {}
) {
    var selectedPage by rememberSaveable { mutableStateOf(initialPage) }

    // 页标题右侧的搜索框：输入内容随手报到 UIService，页面自己去取（页面不需要知道这个框在哪）。
    // 换页清空：搜索是"当前这一页的过滤条件"，带到另一页上没有意义。
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // 把当前页持续上报给 Activity（首帧也会报一次）
    LaunchedEffect(selectedPage) {
        onPageChanged(navigationTitle(selectedPage))

        searchQuery = ""

        UIService.instanceOrNull()?.setPageSearchQuery(navigationTitle(selectedPage), "")
    }
    var railExpanded by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 响应式断点：宽度 >= 600dp 视为宽屏（平板、折叠屏展开、桌面模式）
        val isWide = maxWidth >= 600.dp

        if (isWide) {
            Row(Modifier.fillMaxSize()) {
                WideTaskNavigation(
                    selectedPage = selectedPage,
                    expanded = railExpanded,
                    onToggleExpanded = { railExpanded = !railExpanded },
                    onSelectPage = { selectedPage = it }
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // 横屏专用的"当前页"指示。
                    //
                    // 为什么只有这里需要：竖屏有 TopAppBar 显示页名，浮窗有自己的标题栏，
                    // 而宽屏这一支既没有标题栏、折叠态的边栏又只留图标，光看图标分不清在哪一页。
                    //
                    // 为什么放在内容区顶部：边栏是同一行里的兄弟元素（不是浮在内容上），
                    // 展开时内容区会被自动挤到右边，所以这行文字永远不会被边栏盖住；
                    // 也不去动边栏本身，它展开后的图标 + 标题保持原样。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = navigationTitle(selectedPage),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // 搜索框占"页名右边剩下的全部空间"（weight 就是这个意思）
                        if (pageHasSearch(selectedPage)) {
                            Spacer(Modifier.width(12.dp))

                            PageSearchField(
                                query = searchQuery,

                                onQueryChange = { text ->
                                    searchQuery = text

                                    UIService.instanceOrNull()
                                        ?.setPageSearchQuery(navigationTitle(selectedPage), text)
                                },

                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        PageContent(selectedPage, settingsProvider, onSettingChanged)
                    }
                }
            }
        } else {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(280.dp)
                    ) {
                        CompactTaskNavigation(
                            selectedPage = selectedPage,
                            onSelectPage = { page ->
                                selectedPage = page
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = navigationTitle(selectedPage),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (pageHasSearch(selectedPage)) {
                                        Spacer(Modifier.width(8.dp))

                                        PageSearchField(
                                            query = searchQuery,

                                            onQueryChange = { text ->
                                                searchQuery = text

                                                UIService.instanceOrNull()
                                                    ?.setPageSearchQuery(navigationTitle(selectedPage), text)
                                            },

                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        scope.launch { drawerState.open() }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Menu,
                                        contentDescription = "打开导航菜单"
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        PageContent(selectedPage, settingsProvider, onSettingChanged)
                    }
                }
            }
        }
    }
}

// ============================================================
// Wide Navigation Rail
// ============================================================

@Composable
/** 宽屏侧边导航栏：在 240dp 展开态与 80dp 图标态之间做宽度动画切换。 */
private fun WideTaskNavigation(
    selectedPage: TaskPage,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectPage: (TaskPage) -> Unit
) {
    val railWidth by animateDpAsState(
        targetValue = if (expanded) 240.dp else 80.dp,
        label = "navigationRailWidth"
    )

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(railWidth)
            .animateContentSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (expanded) 12.dp else 8.dp),
            horizontalAlignment = if (expanded) {
                Alignment.Start
            } else {
                Alignment.CenterHorizontally
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) {
                    Arrangement.Start
                } else {
                    Arrangement.Center
                }
            ) {
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = if (expanded) "折叠导航菜单" else "展开导航菜单"
                    )
                }

                if (expanded) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "任务管理器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = if (expanded) {
                    Alignment.Start
                } else {
                    Alignment.CenterHorizontally
                }
            ) {
                primaryNavigationItems.forEach { item ->
                    RailDestination(
                        item = item,
                        selected = selectedPage == item.page,
                        expanded = expanded,
                        onClick = { onSelectPage(item.page) }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            RailDestination(
                item = settingsNavigationItem,
                selected = selectedPage == TaskPage.Settings,
                expanded = expanded,
                onClick = { onSelectPage(TaskPage.Settings) }
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
/** 单个导航目的地：展开态渲染带文字的抽屉项，折叠态渲染纯图标项。 */
private fun RailDestination(
    item: NavigationItem,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    if (expanded) {
        NavigationDrawerItem(
            selected = selected,
            onClick = onClick,
            icon = {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null
                )
            },
            label = {
                Text(
                    text = item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )
    } else {
        NavigationRailItem(
            selected = selected,
            onClick = onClick,
            icon = {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title
                )
            },
            label = null,
            alwaysShowLabel = false
        )
    }
}

// ============================================================
// Compact Navigation Drawer
// ============================================================

@Composable
/** 窄屏抽屉内的导航列表（导航项与宽屏共用 primaryNavigationItems，保证两套布局一致）。 */
private fun CompactTaskNavigation(
    selectedPage: TaskPage,
    onSelectPage: (TaskPage) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = "任务管理器",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                start = 16.dp,
                top = 28.dp,
                bottom = 20.dp
            )
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            primaryNavigationItems.forEach { item ->
                NavigationDrawerItem(
                    selected = selectedPage == item.page,
                    onClick = { onSelectPage(item.page) },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null
                        )
                    },
                    label = { Text(item.title) },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        NavigationDrawerItem(
            selected = selectedPage == TaskPage.Settings,
            onClick = { onSelectPage(TaskPage.Settings) },
            icon = {
                Icon(
                    imageVector = settingsNavigationItem.icon,
                    contentDescription = null
                )
            },
            label = { Text(settingsNavigationItem.title) },
            modifier = Modifier.padding(vertical = 2.dp)
        )

        Spacer(Modifier.height(12.dp))
    }
}

/** 页面 -> 顶部标题栏文案的映射。 */
private fun navigationTitle(page: TaskPage): String {
    return when (page) {
        TaskPage.Processes -> "进程"
        TaskPage.Performance -> "性能"
        TaskPage.AppHistory -> "应用历史记录"
        TaskPage.StartupApps -> "启动应用"
        TaskPage.Users -> "用户"
        TaskPage.Details -> "详细信息"
        TaskPage.Services -> "服务"
        TaskPage.Debug -> "调试"
        TaskPage.Settings -> "设置"
    }
}

/**
 * 这一页要不要显示标题右侧的搜索框。
 *
 * 性能页不给：它的内容是时间序列曲线，不是"可以按名字捞出来"的列表，
 * 放一个搜不出东西的框只会让人以为坏了。
 */
private fun pageHasSearch(page: TaskPage): Boolean = page != TaskPage.Performance

/**
 * 页标题右侧的搜索框（36dp 高的一条，不占一整行）。
 *
 * 只负责"收字 + 报字"：输入内容通过 [UIService.setPageSearchQuery] 交给服务，
 * 页面自己去取（[IPageSearchApi.pageSearchQuery]），这里不碰任何过滤逻辑。
 *
 * 空的时候把提示文本画在输入层下面（不是输入内容，点进去就消失）。
 */
@Composable
private fun PageSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(36.dp),

        shape = RoundedCornerShape(18.dp),

        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),

            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,

                contentDescription = null,

                modifier = Modifier.size(18.dp),

                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier.weight(1f)
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "键入要搜索的名称、发布者或者 PID",

                        style = MaterialTheme.typography.bodySmall,

                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),

                        maxLines = 1,

                        overflow = TextOverflow.Ellipsis
                    )
                }

                BasicTextField(
                    value = query,

                    onValueChange = onQueryChange,

                    singleLine = true,

                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),

                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),

                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 有内容才给"清空"，空着的时候不占位置
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))

                Icon(
                    imageVector = Icons.Default.Clear,

                    contentDescription = "清空搜索",

                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onQueryChange("") },

                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================
// Page Dispatcher
// ============================================================

@Composable
/**
 * 页面分发器：把当前选中的 TaskPage 渲染成对应的 Composable。
 *
 * 说明：这里只做路由，不取数据。页面要数据找 UIService（IUIServiceApi / IPageDataApi），
 * 不要在这里、也不要在页面里直接碰 AndroidService / LinuxService。
 *
 * 入口先过一道"状态闸"（pageStateOf）：
 * - NOT_IMPLEMENTED → 只画"此页面不受支持"占位，**不进入页面**（页面体不会被组合）；
 * - UNAVAILABLE     → 画"页面不可用"（这台设备 / 权限环境用不了）；
 * - IMPLEMENTED     → 才会进下面的 when。
 *
 * @param settingsProvider 取当前设置的方式（目前只有设置页用，作为 initialValues）
 * @param onSettingChanged 设置变更回调（透传给设置页）
 */
private fun PageContent(
    page: TaskPage,
    settingsProvider: () -> SettingsValues,
    onSettingChanged: (SettingKey, Any) -> Unit
) {
    when (pageStateOf(page)) {
        PageState.NOT_IMPLEMENTED -> {
            UnsupportedPageScreen()

            return
        }

        PageState.UNAVAILABLE -> {
            PageUnavailableScreen(
                title = navigationTitle(page),
                description = "当前设备或系统环境不支持此页面所需的功能。"
            )

            return
        }

        // 已实现：继续往下渲染真实页面
        PageState.IMPLEMENTED -> Unit
    }

    when (page) {
        TaskPage.Processes -> ProcessScreen()
        TaskPage.Performance -> PerformanceScreen()
        TaskPage.AppHistory -> ApphistoryScreen()
        TaskPage.StartupApps -> StartupAppScreen()
        TaskPage.Users -> UsersScreen()
        TaskPage.Details -> DetailsScreen()
        TaskPage.Services -> ServicesScreen()
        TaskPage.Debug -> DebugScreen()
        TaskPage.Settings -> SettingsScreen(
            initialValues = settingsProvider(),
            onSettingChanged = onSettingChanged
        )
    }
}

// ============================================================
// Page Placeholders（不支持 / 不可用）
// ============================================================

/**
 * "此页面不受支持"占位（页面还没写）。
 *
 * 版式按需求定死：**报错图标在上、横向居中，文字在图标下面**，不加标题、不加副标题——
 * 用户切到没写完的页时，只需要看到一件事：这一页现在用不了，不是坏了。
 *
 * 与 PageUnavailableScreen 的区别：
 * - 本页 = 功能还没做（写代码就能解决）；
 * - PageUnavailableScreen = 功能做了但这台设备缺权限（用户能解决）。
 */
@Composable
private fun UnsupportedPageScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),

        verticalArrangement = Arrangement.Center,

        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Icon(
            imageVector = Icons.Default.Error,

            contentDescription = null,

            modifier = Modifier.size(64.dp),

            tint = MaterialTheme.colorScheme.error
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Text(
            text = "此页面不受支持",

            style = MaterialTheme.typography.titleMedium,

            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
/** 能力不足时的兜底页面（例如缺少 Root / Shizuku 权限，或系统接口不可用）。 */
private fun PageUnavailableScreen(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "$title 页面不可用",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================
// Preview
// ============================================================

@Preview(
    name = "Task Manager - Wide",
    showBackground = true,
    widthDp = 1000,
    heightDp = 700
)
@Composable
private fun MainPageWidePreview() {
    // 预览也走正式的主题装配，避免"预览里看到的颜色和真机不一样"
    TaskManagerTheme {
        Surface(Modifier.fillMaxSize()) {
            MainPage()
        }
    }
}

@Preview(
    name = "Task Manager - Narrow",
    showBackground = true,
    widthDp = 390,
    heightDp = 844
)
@Composable
private fun MainPageNarrowPreview() {
    TaskManagerTheme {
        Surface(Modifier.fillMaxSize()) {
            MainPage()
        }
    }
}
