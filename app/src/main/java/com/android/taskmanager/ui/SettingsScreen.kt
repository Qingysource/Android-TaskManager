
/**
 * ============================================================
 * 设置页
 * ============================================================
 *
 * 结构：
 * - SettingsValues       设置项数据模型（一份完整快照）
 * - SettingsDefaults     默认值常量
 * - SettingKey           设置项键名（持久化用的字符串键）
 * - SettingsApi     对外契约：默认值 / 键名 / 序列化，UIService 从这里取
 * - SettingsScreen()     页面本体
 * - SettingsSection      页面分组标题
 * - SettingsExpandableGroup 可折叠设置组
 * - SettingsDropdown     下拉选择设置卡片
 * - SettingsCheckbox     复选框设置
 * - SettingsPermissionRow 权限申请行（Shizuku / Root：请求权限 -> 已授权）
 * - NeutralOutlinedButton 中性描边按钮
 *
 * 数据流（谁也别越界）：
 *     SettingsStore（磁盘 JSON） --load--> 设置快照 --initialValues--> 本页面
 *     页面 updateXxx() --onSettingChanged--> UIService.onSettingChanged --> SettingsStore（落盘）
 *
 * 预留实现接口：
 * 1. 所有设置变更都必须经过 updateXxx() 入口，不要直接改 Compose 状态。
 * 2. 落盘由 UIService 负责：页面只把 (key, value) 交给 onSettingChanged 回调，不碰存储。
 * 3. 选项名单统一走 SettingsCatalog（在 Interfaces.kt），不在这里另写 listOf。
 * 4. 每个 updateXxx 末尾的 TODO 是运行时生效的落点。
 * 5. 发送反馈按钮不走上报接口，直接用系统浏览器打开项目的问题页面（地址见 SETTINGS_FEEDBACK_URL）。
 */

package com.android.taskmanager

import android.content.Intent
import android.net.Uri
import android.provider.Settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import android.content.ActivityNotFoundException

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Speed

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import com.android.taskmanager.AppStrings
import com.android.taskmanager.service.A11yTopWindow
import com.android.taskmanager.service.UIService
import kotlinx.coroutines.delay

// =========================================================
// 设置数据结构
// =========================================================

/**
 * 反馈按钮打开的问题页面地址。换仓库只改这一行。
 *
 * 详细信息页长按菜单里的「提供反馈」也走这一个地址（见 DetailsScreen），
 * 所以是 internal 而不是 private：两处必须是同一个页面，不能各写一份。
 */
internal const val SETTINGS_FEEDBACK_URL = "https://github.com/Qingysource/Android-TaskManager/issues"

/**
 * 一份完整的设置快照。
 *
 * 所有设置项都装在这里，既是页面内的"当前值"，也是 UIService 需要的
 * "默认值"与"回填值"的传输载体（因此不能是 private）。
 *
 * 注意：它只描述结构，不做持久化；读写存储由 UIService 负责。
 */
data class SettingsValues(
    val appTheme: String,
    val defaultStartPage: String,
    val updateSpeed: String,

    /**
     * CPU 利用率显示的小数位数：默认 / 一位 / 两位（见 SettingsCatalog.CPU_USAGE_PRECISIONS）。
     *
     * 只影响显示，不影响采集：底层利用率一直按 0.01% 的精度传上来，
     * 这里决定某几页在屏幕上画几位。默认不保留小数。
     */
    val cpuUsagePrecision: String,

    // —— 窗口管理 ——
    val windowAlwaysOnTop: Boolean,
    val minimizeWhenInUse: Boolean,
    val a11yTopWindow: Boolean,

    // —— 其他选项 ——
    val showFullAccountName: Boolean,
    val showAllProcessHistory: Boolean,
    val confirmEfficiencyMode: Boolean,

    /**
     * 后台统计：应用不在前台时是否继续采样性能数据。
     *
     * 只影响"看不见界面时要不要采"：界面可见（普通界面在前台或顶层窗口在显示）时永远照常采。
     * 关闭后后台不采集、不发请求，省 CPU 与电；默认开启，因为性能页要的是一段连续历史。
     */
    val backgroundStats: Boolean,

    // —— 高级：实时内核内存转储 ——
    val abortOnInsufficientMemory: Boolean,
    val captureHypervisorPages: Boolean,
    val captureNonEssentialPages: Boolean,
    val captureUserPages: Boolean,

    // —— 运行时记录（不在设置页展示、用户改不了）——

    /**
     * 上一次停留的页面名称；空串 = 没有记录。
     *
     * 用途：在"普通界面 <-> 浮窗"之间切换时忽略一次默认起始页，直接回到原来那一页。
     * 写入：普通界面由 MainActivity 写（开置顶那一刻），浮窗由 UIService 写（退出/关置顶）。
     * 消费：MainActivity.onCreate 读一次、用完立刻清空（见 SettingKey.LAST_PAGE）。
     */
    val lastPage: String
)

// =========================================================
// 默认设置
// =========================================================

/**
 * 全局默认值：用户从未修改过某项时该取的值。
 *
 * 外部不要直接读这个常量，统一通过 [SettingsApi] 获取，
 * 以后加字段时才不会漏掉某一处同步。
 */
val SettingsDefaults = SettingsValues(
    // 默认值写的是**内部标识**，不是界面文案：它们会和 settings.json 里的旧值直接比较
    appTheme = SettingsCatalog.THEME_SYSTEM,
    defaultStartPage = SettingsCatalog.PAGE_PROCESSES,
    updateSpeed = SettingsCatalog.SPEED_NORMAL,

    // 默认不保留小数：要看细节的用户自己去设置页改成一位 / 两位
    cpuUsagePrecision = SettingsCatalog.PRECISION_DEFAULT,

    windowAlwaysOnTop = false,
    minimizeWhenInUse = true,
    a11yTopWindow = false,

    showFullAccountName = false,
    showAllProcessHistory = true,
    confirmEfficiencyMode = true,

    // 默认继续在后台采样：图表要连续，关掉是用户主动省电的选择
    backgroundStats = true,

    abortOnInsufficientMemory = true,
    captureHypervisorPages = false,
    captureNonEssentialPages = false,
    captureUserPages = false,

    // 没有"回到原处"的待办：一切按默认起始页来
    lastPage = ""
)

// =========================================================
// 设置项键名
// =========================================================

/**
 * 设置项的唯一键名。
 *
 * [storageKey] 是持久化用的字符串键，一旦发布就不要再改，
 * 否则 UIService 之前存下的数据会对不上。
 *
 * 新增设置项时按顺序同步四处：
 * SettingsValues 字段 -> 本枚举 -> SettingsDefaults -> SettingsApi。
 */
enum class SettingKey(val storageKey: String) {

    APP_THEME("app_theme"),
    DEFAULT_START_PAGE("default_start_page"),
    UPDATE_SPEED("update_speed"),
    CPU_USAGE_PRECISION("cpu_usage_precision"),

    WINDOW_ALWAYS_ON_TOP("window_always_on_top"),
    MINIMIZE_WHEN_IN_USE("minimize_when_in_use"),
    A11Y_TOP_WINDOW("a11y_top_window"),

    SHOW_FULL_ACCOUNT_NAME("show_full_account_name"),
    SHOW_ALL_PROCESS_HISTORY("show_all_process_history"),
    CONFIRM_EFFICIENCY_MODE("confirm_efficiency_mode"),

    /** 后台统计：不在前台时是否继续采样性能数据（默认 true）。 */
    BACKGROUND_STATS("background_stats"),

    ABORT_ON_INSUFFICIENT_MEMORY("abort_on_insufficient_memory"),
    CAPTURE_HYPERVISOR_PAGES("capture_hypervisor_pages"),
    CAPTURE_NON_ESSENTIAL_PAGES("capture_non_essential_pages"),
    CAPTURE_USER_PAGES("capture_user_pages"),

    /**
     * 一次性"回原位"记录：切换浮窗 / 普通界面前停在哪一页。
     *
     * 刻意不叫 default_start_page，因为它不是"默认页"，只是"下一程回到哪"的便条：
     * - 只在"开 / 关置于顶层"那一刻写入；
     * - 被启动流程消费一次后立刻清成 ""，所以它永远不会长期影响首屏。
     * 值域：SettingsCatalog.PAGES 里的 9 个页面名之一，或 ""（没有待办）。
     */
    LAST_PAGE("last_page")
}

// =========================================================
// 设置项对外契约（供 UIService 等外部服务调用）
// =========================================================

/**
 * 设置模块对外的唯一入口。
 *
 * 实现接口：[ISettingsApi]（声明在 Interfaces.kt），本文件不再另起对外 API。
 *
 * 职责边界：
 * - 给外部：默认值、键名、序列化规则；
 * - 不做：读写磁盘（那是 UIService 的事）、不持有运行时状态（当前值在页面的 Compose 状态里）。
 *
 * UIService 侧的典型用法：
 *
 *     // 1. 首次启动、还没有任何已保存数据时，直接用默认值
 *     val values = SettingsApi.defaultValues
 *
 *     // 2. 启动时从存储恢复，缺项 / 类型不符的项自动回落默认值
 *     val values = SettingsApi.fromMap(storage.readAll())
 *
 *     // 3. 打开设置页时把值传进去（不传则用默认值）
 *     SettingsScreen(initialValues = values)
 *
 *     // 4. 收到 UpdateXxx 变更通知后，按单项写回
 *     storage.put(SettingKey.APP_THEME.storageKey, newValue)
 *
 *     // 5. 需要遍历所有设置项时用 keys
 *     SettingsApi.keys.forEach { key -> ... }
 */
object SettingsApi : ISettingsApi {

    /**
     * 全部设置项的默认值快照。
     *
     * 用途：无已保存数据时的兜底，也是"重置设置"的目标值。
     */
    override val defaultValues: SettingsValues
        get() = SettingsDefaults

    /**
     * 全部键名，按枚举声明顺序排列。
     *
     * 用途：持久化时逐项写入、恢复时逐项读取、变更时按 key 定位。
     */
    override val keys: List<SettingKey> = SettingKey.values().toList()

    /**
     * 查询某一项的默认值。
     *
     * String 型返回 String，Boolean 型返回 Boolean，调用方按实际类型取用。
     */
    override fun defaultValueOf(key: SettingKey): Any {
        val d = defaultValues

        return when (key) {
            SettingKey.APP_THEME -> d.appTheme
            SettingKey.DEFAULT_START_PAGE -> d.defaultStartPage
            SettingKey.UPDATE_SPEED -> d.updateSpeed

            SettingKey.CPU_USAGE_PRECISION -> d.cpuUsagePrecision

            SettingKey.WINDOW_ALWAYS_ON_TOP -> d.windowAlwaysOnTop
            SettingKey.MINIMIZE_WHEN_IN_USE -> d.minimizeWhenInUse
            SettingKey.A11Y_TOP_WINDOW -> d.a11yTopWindow

            SettingKey.SHOW_FULL_ACCOUNT_NAME -> d.showFullAccountName
            SettingKey.SHOW_ALL_PROCESS_HISTORY -> d.showAllProcessHistory
            SettingKey.CONFIRM_EFFICIENCY_MODE -> d.confirmEfficiencyMode
            SettingKey.BACKGROUND_STATS -> d.backgroundStats

            SettingKey.ABORT_ON_INSUFFICIENT_MEMORY -> d.abortOnInsufficientMemory
            SettingKey.CAPTURE_HYPERVISOR_PAGES -> d.captureHypervisorPages
            SettingKey.CAPTURE_NON_ESSENTIAL_PAGES -> d.captureNonEssentialPages
            SettingKey.CAPTURE_USER_PAGES -> d.captureUserPages

            SettingKey.LAST_PAGE -> d.lastPage
        }
    }

    /**
     * 把一份设置快照展开成"存储键 -> 值"，供 UIService 写盘。
     *
     * 返回值键一律是 [SettingKey.storageKey]，可直接落 SharedPreferences / DataStore。
     */
    override fun toMap(values: SettingsValues): Map<String, Any> = mapOf(
        SettingKey.APP_THEME.storageKey to values.appTheme,
        SettingKey.DEFAULT_START_PAGE.storageKey to values.defaultStartPage,
        SettingKey.UPDATE_SPEED.storageKey to values.updateSpeed,

        SettingKey.CPU_USAGE_PRECISION.storageKey to values.cpuUsagePrecision,

        SettingKey.WINDOW_ALWAYS_ON_TOP.storageKey to values.windowAlwaysOnTop,
        SettingKey.MINIMIZE_WHEN_IN_USE.storageKey to values.minimizeWhenInUse,
        SettingKey.A11Y_TOP_WINDOW.storageKey to values.a11yTopWindow,

        SettingKey.SHOW_FULL_ACCOUNT_NAME.storageKey to values.showFullAccountName,
        SettingKey.SHOW_ALL_PROCESS_HISTORY.storageKey to values.showAllProcessHistory,
        SettingKey.CONFIRM_EFFICIENCY_MODE.storageKey to values.confirmEfficiencyMode,
        SettingKey.BACKGROUND_STATS.storageKey to values.backgroundStats,

        SettingKey.ABORT_ON_INSUFFICIENT_MEMORY.storageKey to values.abortOnInsufficientMemory,
        SettingKey.CAPTURE_HYPERVISOR_PAGES.storageKey to values.captureHypervisorPages,
        SettingKey.CAPTURE_NON_ESSENTIAL_PAGES.storageKey to values.captureNonEssentialPages,
        SettingKey.CAPTURE_USER_PAGES.storageKey to values.captureUserPages,

        SettingKey.LAST_PAGE.storageKey to values.lastPage
    )

    /**
     * 从持久化数据还原设置快照，供 UIService 把用户已保存的习惯交给设置页。
     *
     * 容错规则：缺项、类型不符的项都回落默认值，不抛异常，
     * 这样新增设置项后旧数据也能直接用。
     */
    override fun fromMap(saved: Map<String, Any?>): SettingsValues {
        val d = defaultValues

        fun readString(key: SettingKey, fallback: String): String =
            saved[key.storageKey] as? String ?: fallback

        fun readBoolean(key: SettingKey, fallback: Boolean): Boolean =
            saved[key.storageKey] as? Boolean ?: fallback

        return SettingsValues(
            appTheme = readString(SettingKey.APP_THEME, d.appTheme),
            defaultStartPage = readString(SettingKey.DEFAULT_START_PAGE, d.defaultStartPage),
            updateSpeed = readString(SettingKey.UPDATE_SPEED, d.updateSpeed),

            cpuUsagePrecision = readString(SettingKey.CPU_USAGE_PRECISION, d.cpuUsagePrecision),

            windowAlwaysOnTop = readBoolean(SettingKey.WINDOW_ALWAYS_ON_TOP, d.windowAlwaysOnTop),
            minimizeWhenInUse = readBoolean(SettingKey.MINIMIZE_WHEN_IN_USE, d.minimizeWhenInUse),
            a11yTopWindow = readBoolean(SettingKey.A11Y_TOP_WINDOW, d.a11yTopWindow),

            showFullAccountName = readBoolean(SettingKey.SHOW_FULL_ACCOUNT_NAME, d.showFullAccountName),
            showAllProcessHistory = readBoolean(
                SettingKey.SHOW_ALL_PROCESS_HISTORY,
                d.showAllProcessHistory
            ),
            confirmEfficiencyMode = readBoolean(
                SettingKey.CONFIRM_EFFICIENCY_MODE,
                d.confirmEfficiencyMode
            ),
            backgroundStats = readBoolean(SettingKey.BACKGROUND_STATS, d.backgroundStats),

            abortOnInsufficientMemory = readBoolean(
                SettingKey.ABORT_ON_INSUFFICIENT_MEMORY,
                d.abortOnInsufficientMemory
            ),
            captureHypervisorPages = readBoolean(
                SettingKey.CAPTURE_HYPERVISOR_PAGES,
                d.captureHypervisorPages
            ),
            captureNonEssentialPages = readBoolean(
                SettingKey.CAPTURE_NON_ESSENTIAL_PAGES,
                d.captureNonEssentialPages
            ),
            captureUserPages = readBoolean(
                SettingKey.CAPTURE_USER_PAGES,
                d.captureUserPages
            ),

            lastPage = readString(SettingKey.LAST_PAGE, d.lastPage)
        )
    }
}

// =========================================================
// 设置页面
// =========================================================

/**
 * 设置页。
 *
 * @param initialValues 页面初始化时使用的设置值。
 *        - 默认值为工厂默认值（[SettingsApi.defaultValues]），即独立打开时的行为；
 *        - 正常情况下由 MainActivity 传入 SettingsStore 校验过的快照。
 *        每次组合都会重新取值：横竖屏切换后页面以它为准重绘，不会沿用上一个方向记下的旧值。
 * @param onSettingChanged 单项设置变更的回调，参数是 (设置项, 新值)。
 *        MainActivity 会把它接到 UIService.onSettingChanged()，由 UIService 统一落盘；
 *        默认空实现，方便单测直接打开页面而不碰磁盘。
 * @param hostedInOverlay 是否被"浮窗"承载（OVERLAY v1）。
 *        - false（默认）：普通界面，对话框用 AlertDialog、选项用 DropdownMenu；
 *        - true：浮窗没有 Activity 的窗口令牌，两者都会崩，于是换成
 *          窗口内自绘的遮罩卡片 + 就地展开的选项列表（不新开任何窗口）。
 *        这个开关只改"怎么弹"，不改任何设置逻辑。
 * @param credentialApi 取"用户凭据"接口（[IUserCredentialApi]）。
 *        默认直连 UIService.instanceOrNull()：普通界面与浮窗在同一个进程，两处都能拿到；
 *        服务没起来时返回 null，此时"用户身份"不做切换（宁可不动，也不猜权限）。
 *        身份选择与两个授权按钮都只走这个接口，页面不自己判断 Shizuku / Root。
 */
/** 页面实现状态：设置页已实现（全部设置项可读写并落盘）。 */
val SettingsPageState: PageState = PageState.IMPLEMENTED

@Composable
fun SettingsScreen(
    initialValues: SettingsValues = SettingsApi.defaultValues,
    onSettingChanged: (SettingKey, Any) -> Unit = { _, _ -> },
    hostedInOverlay: Boolean = false,
    credentialApi: () -> IUserCredentialApi? = { UIService.instanceOrNull() }
) {

    // =====================================================
    // 横竖屏切换时，这份界面状态该以谁为准
    // =====================================================
    // 设置页每一项都是"改一下就立刻落盘"的，存储里的值永远是最新的，
    // 屏幕上的状态只是它的镜像。但 rememberSaveable 会在 Activity 因
    // 横竖屏重建时，把上一个方向记下的旧值恢复回来，于是出现
    // "磁盘里是开着的、屏幕上却是关着的，重启应用又正常"这种自相矛盾。
    //
    // 所以下面这 13 个值都不再"记住"，而是用 remember(layoutEpoch, initialValues)
    // 以"存储快照 + 当前布局"为键重新取一次：方向一变（换分支、换组合槽位）
    // 或者外部把设置改动了，都会重新以存储为准，界面不会再留着旧值。
    //
    // orientation / screenWidthDp 都是基本类型，放进 Pair 参与 remember 的
    // 键比较是稳定可靠的（Configuration 对象本身可能被系统原地改写，不宜直接用）。
    val layoutEpoch = LocalConfiguration.current.let { it.orientation to it.screenWidthDp }

    // =====================================================
    // 外观
    // =====================================================

    var appTheme by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.appTheme)
    }

    // =====================================================
    // 常规
    // =====================================================

    var defaultStartPage by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.defaultStartPage)
    }

    var updateSpeed by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.updateSpeed)
    }

    var cpuUsagePrecision by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.cpuUsagePrecision)
    }

    // =====================================================
    // 窗口管理
    // =====================================================

    var windowExpanded by rememberSaveable {
        mutableStateOf(true)
    }

    var windowAlwaysOnTop by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.windowAlwaysOnTop)
    }

    var minimizeWhenInUse by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.minimizeWhenInUse)
    }

    var a11yTopWindow by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.a11yTopWindow)
    }

    // =====================================================
    // 其他选项
    // =====================================================

    var otherOptionsExpanded by rememberSaveable {
        mutableStateOf(true)
    }

    var showFullAccountName by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.showFullAccountName)
    }

    var showAllProcessHistory by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.showAllProcessHistory)
    }

    var confirmEfficiencyMode by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.confirmEfficiencyMode)
    }

    var backgroundStats by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.backgroundStats)
    }

    // =====================================================
    // 高级：实时内核内存转储选项
    // =====================================================

    var dumpOptionsExpanded by rememberSaveable {
        mutableStateOf(true)
    }

    var abortOnInsufficientMemory by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.abortOnInsufficientMemory)
    }

    var captureHypervisorPages by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.captureHypervisorPages)
    }

    var captureNonEssentialPages by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.captureNonEssentialPages)
    }

    var captureUserPages by remember(layoutEpoch, initialValues) {
        mutableStateOf(initialValues.captureUserPages)
    }

    // =====================================================
    // 用户凭据（身份 + Shizuku / Root 授权）
    //
    // 这一组和上面几组不一样：**没有任何一项落盘**，值全部来自 UIService。
    // - 身份是运行时能力，重启回到"普通权限"；
    // - 两个授权状态由 UIService 推（UserCredentialState），页面只读。
    // 所以这里用 rememberSaveable 的只有"折叠状态"，值本身不 remember：
    // 每次组合直接读共享状态，横竖屏重建、浮窗重画都会拿到服务的最新值。
    // =====================================================

    var credentialExpanded by rememberSaveable {
        mutableStateOf(true)
    }

    /** 取"用户凭据"接口；服务没起来时是 null（此时不切换身份、按钮点了也不发请求）。 */
    val credentialApiRef = credentialApi()

    /** 当前生效身份（服务说了算）。 */
    val credentialIdentity = UserCredentialState.identity.value

    /** 两条提权通道的授权状态：决定按钮显示"请求权限"还是灰掉的"已授权"。 */
    val shizukuGranted = UserCredentialState.shizukuGranted.value
    val rootGranted = UserCredentialState.rootGranted.value

    /** Shizuku 服务端是不是 root 身份（决定它提供的是 root 能力还是 shell 能力）。 */
    val shizukuRoot = UserCredentialState.shizukuRoot.value

    /** Shizuku 装没装 / 服务在不在跑：决定那一行显示"请求权限"还是"打开 Shizuku"。 */
    val shizukuInstalled = UserCredentialState.shizukuInstalled.value
    val shizukuRunning = UserCredentialState.shizukuRunning.value

    // 进入页面时主动取一次快照：把真实授权状态从服务拉到界面。
    // 之后服务侧任何一次 credentialSnapshot() 也会推同样的状态，所以按钮能自己变。
    LaunchedEffect(Unit) {
        credentialApiRef?.credentialSnapshot()
    }

    // 身份被拒绝时的提示：只弹框 + 保持原选择，不改任何状态
    var showIdentityRejectedDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var identityRejectedNote by rememberSaveable {
        mutableStateOf("")
    }

    // =====================================================
    // 重置对话框
    // =====================================================

    var showResetDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // =====================================================
    // [OVERLAY-v1] 悬浮窗权限相关状态（回退时连同下面的对话框一起删）
    // =====================================================

    // 用户勾了"置于顶层"但还没有权限时，弹这个说明对话框
    var showOverlayPermissionDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // 用户勾了"使用无障碍权限置顶"但还没有无障碍权限时，弹这个说明对话框
    // （无障碍权限同样没有运行时申请接口，只能引导用户去系统无障碍设置页手动打开）
    var showA11yPermissionDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // 授权成功后提示"重启应用才生效"（浮窗在启动时创建）
    var showOverlayRestartDialog by rememberSaveable {
        mutableStateOf(false)
    }

    /**
     * 用户点过那个复选框、但当时还没有对应权限（说明框已经把他导向系统页）。
     *
     * 为什么单独记两个标记：授权这件事没有返回值，用户可能在应用内跳转后返回，
     * 也可能自己跑去系统页再回来——没有标记就分不清"他点过、在等权限"和
     * "他根本没想开、只是顺手给了权限"。只有前一种情况才允许自动勾上并落盘。
     * 点"取消"会清掉标记：取消 = 不打算开了。
     */
    var pendingEnableOverlay by rememberSaveable {
        mutableStateOf(false)
    }

    var pendingEnableA11y by rememberSaveable {
        mutableStateOf(false)
    }

    /**
     * 三条提权路径共用的"权限请求失败"弹框：记着这次失败是从哪儿来的。
     *
     * 为什么要记来源而不是只用一个 Boolean：弹框上只有一个"再试一次"，
     * 点下去得知道该重新发起哪一种申请（跳悬浮窗设置页 / 重新要 Shizuku / 重新探 Root）。
     */
    var failedPermissionTarget by rememberSaveable {
        mutableStateOf(PermissionRetryTarget.NONE)
    }

    // 当前界面 Context：用来查权限、跳系统的悬浮窗设置页
    val context = LocalContext.current

    /**
     * 把两条置顶相关选项与真实权限对齐一次。
     *
     * 什么时候跑：① 每次进入本页（含从别的页切回来、横竖屏重建）；
     * ② 页面可见期间每 [TOP_WINDOW_PERMISSION_RECHECK_MS] 一次。
     *
     * 规则只有一条硬要求：**原本勾着的按钮，权限被收回就立刻取消勾选并落盘**。
     * 没勾的一律不动——用户只是给了权限、并没有要求进这些模式，
     * 就不该被自动勾上、更不该被弹"重启生效"。
     *
     * 唯一会"自动勾上"的情形：用户自己点过那个复选框（[pendingEnableOverlay] /
     * [pendingEnableA11y] 记着这次点击），随后确实把权限授了——那是他在等的那个结果，
     * 所以要补上并落盘（与手动勾选走同一条 onSettingChanged）。
     *
     * 两条撤销规则顺序不能换（与 SettingsStore.applyRuntimeConstraints 一致）：
     * 1. 无障碍权限没了 -> 无障碍选项关掉；「置于顶层」只有在**没有悬浮窗权限**时才跟着关
     *    （有悬浮窗权限的话，窗口换成悬浮窗继续置顶，和用户手动关掉这条选项是同一个结果）；
     * 2. 置顶还开着，但既没有悬浮窗权限、也不能靠无障碍豁免 -> 置顶改回关闭。
     *
     * 注意：这里不改"回原位"记录（不写 LAST_PAGE）——用户并没有主动退出浮窗，
     * 只是权限没了，不该给他留下一次"下次回到这页"的便条。
     */
    fun reconcileTopWindowPermissions() {
        val a11yEnabledNow = A11yTopWindow.isEnabled(context)

        // 1. 无障碍权限被收回：先关掉这条通路（置顶要不要跟着关，交给下面的规则 2 判）
        if (a11yTopWindow && !a11yEnabledNow) {
            a11yTopWindow = false

            onSettingChanged(SettingKey.A11Y_TOP_WINDOW, false)

            pendingEnableA11y = false
        }

        val canDrawOverlaysNow = Settings.canDrawOverlays(context)

        // 2. 用户点过复选框、权限也真的到了：补上这次勾选（只认"点过"的意图）
        if (pendingEnableA11y && a11yEnabledNow && !a11yTopWindow) {
            pendingEnableA11y = false

            a11yTopWindow = true

            onSettingChanged(SettingKey.A11Y_TOP_WINDOW, true)
        }

        if (pendingEnableOverlay && canDrawOverlaysNow && !windowAlwaysOnTop) {
            pendingEnableOverlay = false

            windowAlwaysOnTop = true

            onSettingChanged(SettingKey.WINDOW_ALWAYS_ON_TOP, true)

            // 普通界面里勾上还不等于窗口在跑（窗口在启动时建），仍旧提示重启；
            // 浮窗 / 无障碍窗口里本来就有窗口，不打扰
            if (!hostedInOverlay) {
                showOverlayRestartDialog = true
            }
        }

        // 3. 置顶失去依据：没有悬浮窗权限，也不能走无障碍通路
        val a11yUsableNow = a11yTopWindow && a11yEnabledNow

        if (windowAlwaysOnTop && !canDrawOverlaysNow && !a11yUsableNow) {
            windowAlwaysOnTop = false

            onSettingChanged(SettingKey.WINDOW_ALWAYS_ON_TOP, false)
        }
    }

    LaunchedEffect(Unit) {
        // 进页面立刻核一次（从别的页切回来、横竖屏重建都会重跑这段），
        // 之后在页面可见期间持续核：权限可能被用户在系统设置里随时收回，
        // 而系统**不会通知应用**。与 UIService 的巡检是同一套判据，两边谁先发现都行。
        while (true) {
            reconcileTopWindowPermissions()

            delay(TOP_WINDOW_PERMISSION_RECHECK_MS)
        }
    }

    /**
     * [OVERLAY-v1] 从"悬浮窗设置页"返回后的回调。
     *
     * 系统页没有返回值（这个特殊权限不通过 ActivityResult 回传结果），
     * 所以回来后统一交给 [reconcileTopWindowPermissions] 复核：
     * - 拿到了 -> 补上那次勾选并落盘（它还会顺手提示重启，因为窗口在启动时建）；
     * - 没拿到   -> 弹共享的"权限请求失败"框（再试一次 / 取消），勾选保持未勾。
     */
    // [OVERLAY-v1] 浮窗里不能创建 ActivityResultLauncher：
    // 浮窗的 Composition 没有 ActivityResultRegistryOwner，一建就抛异常。
    // 条件只取决于参数 hostedInOverlay（组合期间恒定），所以条件式 remember 是安全的。
    // 浮窗里没有 launcher，跳系统页改用直接 startActivity（见 openOverlayPermissionPage）。
    val overlayPermissionLauncher = if (hostedInOverlay) {
        null
    } else {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) {
            reconcileTopWindowPermissions()

            if (!windowAlwaysOnTop) {
                // 用户在系统页里没打开开关就返回了：按"没拿到权限"处理，
                // 让用户能就地再试一次，而不是只留一个"什么都没发生"的界面
                failedPermissionTarget = PermissionRetryTarget.OVERLAY
            }
        }
    }

    /** 打开系统的悬浮窗权限页（个别 ROM 没有这个 Activity，跳不动也不该崩）。 */
    fun openOverlayPermissionPage() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName)
        )

        val launcher = overlayPermissionLauncher

        if (launcher != null) {
            runCatching { launcher.launch(intent) }

            return
        }

        // 浮窗 / 无障碍窗口里没有 ActivityResult launcher：
        // 直接起系统页（窗口本身可见，属于"有可见窗口"的启动，多数系统放行；
        // 个别 ROM 拦下也只是没跳过去，不会崩）。
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * 从"无障碍设置页"返回后的回调。
     *
     * 无障碍权限也没有返回值（同样不通过 ActivityResult 回传结果），所以回来同样交给
     * [reconcileTopWindowPermissions] 复核：
     * - 拿到了 -> 补上那次勾选并落盘。若此刻正有顶层窗口在跑（走的还是悬浮窗通路），
     *   UIService 收到这次变更后会当场把窗口换成无障碍那只，不用重启；
     * - 没拿到   -> 弹共享的"权限请求失败"框（再试一次 / 取消），勾选保持未勾。
     *
     * 与悬浮窗 launcher 同一套路：浮窗里没有 ActivityResultRegistryOwner，
     * 建 launcher 会抛异常，所以 hostedInOverlay 时置 null（跳转改走直接 startActivity）。
     */
    val a11yPermissionLauncher = if (hostedInOverlay) {
        null
    } else {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) {
            reconcileTopWindowPermissions()

            if (!a11yTopWindow) {
                failedPermissionTarget = PermissionRetryTarget.A11Y
            }
        }
    }

    /**
     * 打开系统的无障碍设置页。
     *
     * 无障碍权限只能由用户在系统页里手动打开（应用无法自行授予），所以这里只跳页面。
     * 个别 ROM 没有这个 Activity（少数定制系统把入口藏起来），跳不动也不该崩。
     */
    fun openA11yPermissionPage() {
        val intent = A11yTopWindow.settingsIntent()

        val launcher = a11yPermissionLauncher

        if (launcher != null) {
            runCatching { launcher.launch(intent) }

            return
        }

        // 浮窗 / 无障碍窗口里没有 launcher：直接起系统页（见 openOverlayPermissionPage 的说明）
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * 点 Shizuku 一栏的"请求权限"：真的向 Shizuku 发起授权。
     *
     * 结果只有两种走法：
     * - 拿到了：服务会推快照，按钮自己变成灰的"已授权"，这里不需要额外提示；
     * - 没拿到：弹共享的失败框，用户可以"再试一次"（就再点一次请求）。
     */
    fun requestShizukuPermission() {
        val api = credentialApiRef

        if (api == null) {
            failedPermissionTarget = PermissionRetryTarget.SHIZUKU

            return
        }

        api.requestShizukuPermission { result ->
            if (result == PermissionRequestResult.DENIED) {
                failedPermissionTarget = PermissionRetryTarget.SHIZUKU
            }
        }
    }

    /** 点 Root 一栏的"请求权限"：后台探测 su，拿不到就弹同一个失败框。 */
    fun requestRootPermission() {
        val api = credentialApiRef

        if (api == null) {
            failedPermissionTarget = PermissionRetryTarget.ROOT

            return
        }

        api.requestRootPermission { result ->
            if (result == PermissionRequestResult.DENIED) {
                failedPermissionTarget = PermissionRetryTarget.ROOT
            }
        }
    }

    /**
     * 失败框上的"再试一次"：按失败来源重新发起那一种申请。
     *
     * 注意这里只是"再来一遍"，不做任何状态回滚：
     * - 悬浮窗：重新跳系统页；
     * - Shizuku / Root：重新调一次请求（成功与否仍由回调决定，失败就再弹框）。
     */
    fun retryFailedPermission() {
        val target = failedPermissionTarget

        // 先收起框，避免用户连点时叠着弹
        failedPermissionTarget = PermissionRetryTarget.NONE

        when (target) {
            PermissionRetryTarget.OVERLAY -> openOverlayPermissionPage()

            PermissionRetryTarget.A11Y -> openA11yPermissionPage()

            PermissionRetryTarget.SHIZUKU -> requestShizukuPermission()

            PermissionRetryTarget.ROOT -> requestRootPermission()

            PermissionRetryTarget.NONE -> Unit
        }
    }

    /**
     * 软重启：让"置于顶层"这类需要重新初始化界面的改动立即生效。
     *
     * 做法：把请求交给 UIService（ACTION_RESTART），由它收掉浮窗、再以新任务拉起 MainActivity。
     * 不杀进程、不起中转进程——两条"杀进程"的路在本机实测都不可用：
     * 1. 自己 startActivity 再杀自己：新界面还没跑起来就被一起带走（只关不启动）；
     * 2. 另开 :restart 中转进程：该新进程在本机初始化阶段就原生崩溃（SIGSEGV，
     *    崩溃栈落在 ART 的 DDM/JDWP 线程），旧进程已死、新进程又没起来 = 黑屏；
     * 3. 而"从浮窗退出"这条（服务里 startActivity 回 MainActivity）一直是稳的。
     *
     * 所以复用第 3 条：清运行时（浮窗）→ 新任务重建界面 → 新实例重新读文件里的设置。
     *
     * 前提：调用前设置必须已经落盘（本流程是"先保存、再重启"）。
     */
    fun restartApplication() {
        val appContext = context.applicationContext

        // 用 startForegroundService：UIService 是常驻前台服务，
        // 若它已被系统回收，这里用普通 startService 从后台拉起会被后台限制拒绝。
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, UIService::class.java).setAction(UIService.ACTION_RESTART)
        )
    }

    // =====================================================
    // 当前设置快照
    // =====================================================

    val currentSettings = SettingsValues(
        appTheme = appTheme,
        defaultStartPage = defaultStartPage,
        updateSpeed = updateSpeed,
        cpuUsagePrecision = cpuUsagePrecision,

        windowAlwaysOnTop = windowAlwaysOnTop,
        minimizeWhenInUse = minimizeWhenInUse,
        a11yTopWindow = a11yTopWindow,

        showFullAccountName = showFullAccountName,
        showAllProcessHistory = showAllProcessHistory,
        confirmEfficiencyMode = confirmEfficiencyMode,
        backgroundStats = backgroundStats,

        abortOnInsufficientMemory = abortOnInsufficientMemory,
        captureHypervisorPages = captureHypervisorPages,
        captureNonEssentialPages = captureNonEssentialPages,
        captureUserPages = captureUserPages,

        // last_page 是"回原位"的运行时便条，不归设置页管、也不在这里展示：
        // 固定按"没有待办"参与比较，避免它把"重置"按钮误判成可点。
        lastPage = ""
    )

    // 当前设置与默认设置不同，才允许重置
    val hasSettingsChanged = currentSettings != SettingsDefaults

    // =====================================================
    // 各设置的统一更新入口
    //
    // 正常操作和重置操作都必须经过这些函数。
    // 每一次变更都会经 onSettingChanged(key, value) 交给 UIService：
    //   UIService 负责落盘（SettingsStore）并让设置生效，页面只维护自己的 Compose 状态。
    // 重置会逐项走这些入口，于是每项都会被完整写一遍，最终状态与默认值严格一致。
    // =====================================================

    fun updateAppTheme(value: String) {
        if (appTheme == value) return

        appTheme = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        //
        // "生效"这一段已经通了，不需要页面自己再调 applyTheme：
        //   onSettingChanged -> MainActivity 的 changeSink / UIService.onSettingChanged
        //   -> ThemeState.mode（进程级共享状态）-> TaskManagerTheme 重组 -> 界面当场换色。
        // 颜色怎么选（跟随系统 / 动态取色 / 回落默认）见 ui/Theme.kt。
        onSettingChanged(SettingKey.APP_THEME, value)
    }

    /**
     * 切换"用户身份"：先问 UIService 能不能用，能用才生效；不能用就弹框，选项留在原处。
     *
     * 为什么不是"先改再回退"：界面显示的就是状态值，改了再退回会闪一下。
     * 这里干脆不改——下拉里从头到尾还是原来那个选项，视觉上天然就是"回退"。
     * （身份不落盘，所以这次"回退"没有任何持久化后果，重启也不会记住被拒绝的选择。）
     */
    fun updateUserIdentity(value: String) {
        if (value == credentialIdentity) return

        val api = credentialApiRef

        // 服务没起来：不知道真实权限，宁可不动，也不猜"应该可以"
        if (api == null) {
            identityRejectedNote = AppStrings.s(R.string.setting_identity_no_service)

            showIdentityRejectedDialog = true

            return
        }

        api.onUserIdentitySelected(value) { decision ->
            when (decision) {
                // 服务判定可用：它已经把新身份推给界面，这里不用再改任何值
                CredentialDecision.ALLOWED -> Unit

                CredentialDecision.MISSING_SHIZUKU -> {
                    identityRejectedNote = AppStrings.s(
                        R.string.setting_identity_need_shizuku,
                        SettingsCatalog.optionLabel(value)
                    )

                    showIdentityRejectedDialog = true
                }

                // Shizuku 已经授权，但它跑在 root 上：给得出 root 能力，给不出 uid 2000 的 shell 身份
                CredentialDecision.SHELL_UNAVAILABLE -> {
                    identityRejectedNote = AppStrings.s(
                        R.string.setting_identity_shell_unavailable,
                        SettingsCatalog.optionLabel(value)
                    )

                    showIdentityRejectedDialog = true
                }

                CredentialDecision.MISSING_ROOT -> {
                    identityRejectedNote = AppStrings.s(
                        R.string.setting_identity_need_root,
                        SettingsCatalog.optionLabel(value)
                    )

                    showIdentityRejectedDialog = true
                }

                CredentialDecision.INVALID -> {
                    identityRejectedNote = AppStrings.s(
                        R.string.setting_identity_invalid,
                        SettingsCatalog.optionLabel(value)
                    )

                    showIdentityRejectedDialog = true
                }
            }
        }
    }

    fun updateDefaultStartPage(value: String) {
        if (defaultStartPage == value) return

        defaultStartPage = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.DEFAULT_START_PAGE, value)

        // TODO(预留): 下次启动时的首屏（MainActivity 读取）
    }

    fun updateUpdateSpeed(value: String) {
        if (updateSpeed == value) return

        updateSpeed = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.UPDATE_SPEED, value)

        // TODO(预留): 现在就能生效：UIService 会换算成轮询间隔
    }

    /**
     * 改"利用率精确度"：只改显示的小数位数，不影响采集。
     *
     * 底层利用率一直是两位小数的精度传上来的（ELF 侧按 0.01% 上报），
     * 这里只是选显示几位，所以改完立刻生效，不需要重启服务、也不需要清历史点。
     */
    fun updateCpuUsagePrecision(value: String) {
        if (cpuUsagePrecision == value) return

        cpuUsagePrecision = value

        // 交给 UIService：落盘（页面不碰存储）
        onSettingChanged(SettingKey.CPU_USAGE_PRECISION, value)
    }

    fun updateWindowAlwaysOnTop(value: Boolean) {
        if (windowAlwaysOnTop == value) return

        // 开"置于顶层"= 要一个顶层窗口，得先确认有没有相应的权限。
        // SYSTEM_ALERT_WINDOW 是"特殊应用权限"，没有运行时申请接口，只能跳系统设置页；
        // 所以这里：不勾选、不落盘、只弹说明，等用户授权返回后由 launcher 补上这次变更。
        //
        // 免悬浮窗权限的唯一情形：无障碍置顶窗口开着 **且** 无障碍权限真的在。
        // 那种情况下窗口走无障碍通路（层级更高），不需要 SYSTEM_ALERT_WINDOW。
        // 用户只是"有权限但把选项关了"不算豁免——选项决定走哪条通路，关掉就回到悬浮窗。
        val a11yUsable = a11yTopWindow && A11yTopWindow.isEnabled(context)

        if (value && !Settings.canDrawOverlays(context) && !a11yUsable) {
            // 记下"用户点过"，这样他真去授了权、回到这页时能补上这次勾选（并落盘）
            pendingEnableOverlay = true

            showOverlayPermissionDialog = true

            return
        }

        windowAlwaysOnTop = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.WINDOW_ALWAYS_ON_TOP, value)

        // 置顶不是立刻生效：窗口由下一次启动时的 MainActivity 决定创建。
        // 只要这次勾选真的能建出窗口（有悬浮窗权限，或走无障碍通路），就提示重启；
        // 两条通路都不满足时这次勾选会被存储层改回关闭，弹重启框只会让人白高兴。
        if (value && (Settings.canDrawOverlays(context) || a11yUsable)) {
            showOverlayRestartDialog = true
        }
    }

    /**
     * [OVERLAY-v1] 撤回"开启置于顶层"。
     *
     * 除了把 WINDOW_ALWAYS_ON_TOP 写回 false，还要清掉 MainActivity 在勾选那一刻
     * 写下的 last_page 便条：否则这次没收尾的一次性记录会漏到下一次普通启动，
     * 让应用莫名其妙停在上次停留的那一页。
     */
    fun cancelAlwaysOnTopChange() {
        onSettingChanged(SettingKey.LAST_PAGE, "")

        updateWindowAlwaysOnTop(false)
    }

    fun updateMinimizeWhenInUse(value: Boolean) {
        if (minimizeWhenInUse == value) return

        minimizeWhenInUse = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.MINIMIZE_WHEN_IN_USE, value)

        // TODO(预留): 使用时最小化，由 Compose 窗口层读取快照
    }

    /**
     * 改"使用无障碍权限置顶"。
     *
     * 这个选项只决定"置顶窗口用哪条通路"，本身不建窗——所以它有两条特殊规则：
     *
     * 开启：
     * - 先查无障碍权限。没有就**不勾、不落盘**，只弹说明框，等用户去系统无障碍页
     *   手动打开本应用的服务（应用无法自行授予），返回后由 launcher 补上这次变更；
     * - 有权限就直接勾上并落盘（像「置于顶层」一样需要保存）。
     *
     * 关闭（含"权限还在但用户主动关掉"）：
     * - 选项本身写回 false；
     * - 如果「置于顶层」当时是靠它豁免悬浮窗权限（即没有悬浮窗权限），那么置顶也会失去依据，
     *   必须一起关掉并收窗——否则会出现"设置里置顶还开着，但权限条件已经不满足"的错位。
     *   有悬浮窗权限时置顶照旧，只是回到"用悬浮窗建顶层窗口"的老通路。
     */
    fun updateA11yTopWindow(value: Boolean) {
        if (a11yTopWindow == value) return

        // 开：先要无障碍权限（与「置于顶层」同样的"先权限、后勾选"顺序）
        if (value && !A11yTopWindow.isEnabled(context)) {
            // 记下"用户点过"：他真去系统页打开了本应用的服务、回到这页时补上这次勾选。
            // 若此刻正有顶层窗口在跑（悬浮窗通路），这次勾选会让 UIService 当场换成无障碍窗口。
            pendingEnableA11y = true

            showA11yPermissionDialog = true

            return
        }

        a11yTopWindow = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.A11Y_TOP_WINDOW, value)

        if (!value) {
            // 关掉这条路：置顶若只靠无障碍豁免（没有悬浮窗权限），就留不住了。
            // 现在无障碍窗口自己也会占着窗口，所以这里必须真的收窗 + 把置顶关掉并落盘
            // （由 UIService 的 collapseTopWindow 负责收窗与拉回普通界面）。
            if (windowAlwaysOnTop && !Settings.canDrawOverlays(context)) {
                updateWindowAlwaysOnTop(false)
            }
        }

        // 这里没有"重启生效"提示：勾选本身不建窗，真正建窗与换通路都由「置于顶层」那条流程负责
        // （窗口已经挂着时，这条选项的改动从下一次启动开始生效）。
    }

    fun updateShowFullAccountName(value: Boolean) {
        if (showFullAccountName == value) return

        showFullAccountName = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.SHOW_FULL_ACCOUNT_NAME, value)

        // TODO(预留): 用户页读取该值决定是否显示全名
    }

    fun updateShowAllProcessHistory(value: Boolean) {
        if (showAllProcessHistory == value) return

        showAllProcessHistory = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.SHOW_ALL_PROCESS_HISTORY, value)

        // TODO(预留): 应用历史页读取该值决定历史范围
    }

    fun updateConfirmEfficiencyMode(value: Boolean) {
        if (confirmEfficiencyMode == value) return

        confirmEfficiencyMode = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.CONFIRM_EFFICIENCY_MODE, value)

        // TODO(预留): 效率模式对话框读取该值
    }

    /**
     * 改"后台统计"。
     *
     * 只决定"看不见界面时还要不要采"：关闭后定时器照跑，但每轮进来先被门禁挡回，
     * 不取快照、不发请求（见 UIService.refreshPerformanceStatsIfEnabled）。
     * 采集是服务侧的常驻行为，所以这里只落盘 + 让服务重排一次定时器即可。
     */
    fun updateBackgroundStats(value: Boolean) {
        if (backgroundStats == value) return

        backgroundStats = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.BACKGROUND_STATS, value)
    }

    fun updateAbortOnInsufficientMemory(value: Boolean) {
        if (abortOnInsufficientMemory == value) return

        abortOnInsufficientMemory = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.ABORT_ON_INSUFFICIENT_MEMORY, value)

        // TODO(预留): 内存转储模块读取该值
    }

    fun updateCaptureNonEssentialPages(value: Boolean) {
        if (captureNonEssentialPages == value) return

        captureNonEssentialPages = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.CAPTURE_NON_ESSENTIAL_PAGES, value)

        // TODO(预留): 内存转储模块读取该值
    }

    fun updateCaptureHypervisorPages(value: Boolean) {
        if (captureHypervisorPages == value) return

        captureHypervisorPages = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.CAPTURE_HYPERVISOR_PAGES, value)

        // TODO(预留): 内存转储模块读取该值
        // 关闭父选项时，通过子选项自己的更新入口清除子选项
        if (!value) {
            updateCaptureNonEssentialPages(false)
        }
    }

    fun updateCaptureUserPages(value: Boolean) {
        if (captureUserPages == value) return

        captureUserPages = value

        // 交给 UIService：落盘 + 生效（页面不碰存储）
        onSettingChanged(SettingKey.CAPTURE_USER_PAGES, value)

        // TODO(预留): 内存转储模块读取该值
    }

    // =====================================================
    // 重置所有设置
    // =====================================================

    fun resetSettings() {
        updateAppTheme(SettingsDefaults.appTheme)
        updateDefaultStartPage(SettingsDefaults.defaultStartPage)
        updateUpdateSpeed(SettingsDefaults.updateSpeed)

        updateCpuUsagePrecision(SettingsDefaults.cpuUsagePrecision)

        updateWindowAlwaysOnTop(SettingsDefaults.windowAlwaysOnTop)
        updateMinimizeWhenInUse(SettingsDefaults.minimizeWhenInUse)
        updateA11yTopWindow(SettingsDefaults.a11yTopWindow)

        updateShowFullAccountName(SettingsDefaults.showFullAccountName)
        updateShowAllProcessHistory(SettingsDefaults.showAllProcessHistory)
        updateConfirmEfficiencyMode(SettingsDefaults.confirmEfficiencyMode)
        updateBackgroundStats(SettingsDefaults.backgroundStats)

        updateAbortOnInsufficientMemory(SettingsDefaults.abortOnInsufficientMemory)
        updateCaptureHypervisorPages(SettingsDefaults.captureHypervisorPages)
        updateCaptureNonEssentialPages(SettingsDefaults.captureNonEssentialPages)
        updateCaptureUserPages(SettingsDefaults.captureUserPages)
    }

    // =====================================================
    // 页面布局
    // =====================================================

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // =================================================
        // 外观
        // =================================================

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSection(title = AppStrings.s(R.string.setting_appearance))

            SettingsDropdown(
                icon = Icons.Outlined.Palette,
                title = AppStrings.s(R.string.setting_theme),
                description = AppStrings.s(R.string.setting_theme_desc),
                selected = appTheme,
                // 选项名单与存储层校验共用 SettingsCatalog，避免两处名单不一致
                options = SettingsCatalog.THEMES,
                onSelected = ::updateAppTheme,
                hostedInOverlay = hostedInOverlay
            )
        }

        // =================================================
        // 常规
        // =================================================

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSection(title = AppStrings.s(R.string.setting_general))

            SettingsDropdown(
                icon = Icons.Outlined.Home,
                title = AppStrings.s(R.string.setting_start_page),
                description = AppStrings.s(R.string.setting_start_page_desc),
                selected = defaultStartPage,
                options = SettingsCatalog.START_PAGES,
                onSelected = ::updateDefaultStartPage,
                hostedInOverlay = hostedInOverlay
            )

            SettingsDropdown(
                icon = Icons.Outlined.Speed,
                title = AppStrings.s(R.string.setting_update_speed),
                description = AppStrings.s(R.string.setting_update_speed_desc),
                selected = updateSpeed,
                options = SettingsCatalog.UPDATE_SPEEDS,
                onSelected = ::updateUpdateSpeed,
                hostedInOverlay = hostedInOverlay
            )

            // 利用率显示几位小数。只影响显示，采集精度固定是 0.01%，
            // 所以改这个选项不会触发重新采样，也不会清掉图表上已经画出来的曲线。
            SettingsDropdown(
                icon = Icons.Outlined.Percent,
                title = AppStrings.s(R.string.setting_precision),
                description = AppStrings.s(R.string.setting_precision_desc),
                selected = cpuUsagePrecision,
                options = SettingsCatalog.CPU_USAGE_PRECISIONS,
                onSelected = ::updateCpuUsagePrecision,
                hostedInOverlay = hostedInOverlay
            )

            // 窗口管理
            SettingsExpandableGroup(
                icon = Icons.Filled.GridView,
                title = AppStrings.s(R.string.setting_window_manage),
                description = AppStrings.s(R.string.setting_window_manage_desc),
                expanded = windowExpanded,
                onExpandedChange = { windowExpanded = it }
            ) {
                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_always_top),
                    description = AppStrings.s(R.string.setting_always_top_desc),
                    checked = windowAlwaysOnTop,
                    onCheckedChange = ::updateWindowAlwaysOnTop
                )

                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_minimize_in_use),
                    description = AppStrings.s(R.string.setting_minimize_in_use_desc),
                    checked = minimizeWhenInUse,
                    onCheckedChange = ::updateMinimizeWhenInUse
                )

                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_a11y_top_window),
                    description = AppStrings.s(R.string.setting_a11y_top_window_desc),
                    checked = a11yTopWindow,
                    onCheckedChange = ::updateA11yTopWindow
                )
            }

            // 其他选项
            SettingsExpandableGroup(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                title = AppStrings.s(R.string.setting_others),
                description = AppStrings.s(R.string.setting_others_desc),
                expanded = otherOptionsExpanded,
                onExpandedChange = { otherOptionsExpanded = it }
            ) {
                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_full_account_name),
                    description = AppStrings.s(R.string.setting_full_account_name_desc),
                    checked = showFullAccountName,
                    onCheckedChange = ::updateShowFullAccountName
                )

                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_all_process_history),
                    description = AppStrings.s(R.string.setting_all_process_history_desc),
                    checked = showAllProcessHistory,
                    onCheckedChange = ::updateShowAllProcessHistory
                )

                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_ask_efficiency),
                    description = AppStrings.s(R.string.setting_ask_efficiency_desc),
                    checked = confirmEfficiencyMode,
                    onCheckedChange = ::updateConfirmEfficiencyMode
                )

                // 最后一个：后台统计
                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_background_stats),
                    description = AppStrings.s(R.string.setting_background_stats_desc),
                    checked = backgroundStats,
                    onCheckedChange = ::updateBackgroundStats
                )
            }
        }

        // =================================================
        // 高级
        // =================================================

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSection(title = AppStrings.s(R.string.setting_advanced))

            SettingsExpandableGroup(
                icon = Icons.Filled.Memory,
                title = AppStrings.s(R.string.setting_kernel_dump),
                description = AppStrings.s(R.string.setting_kernel_dump_desc),
                expanded = dumpOptionsExpanded,
                onExpandedChange = { dumpOptionsExpanded = it }
            ) {
                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_dump_abort_lowmem),
                    description = AppStrings.s(R.string.setting_dump_abort_lowmem_desc),
                    checked = abortOnInsufficientMemory,
                    onCheckedChange = ::updateAbortOnInsufficientMemory
                )

                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_dump_hypervisor),
                    description = AppStrings.s(R.string.setting_dump_hypervisor_desc),
                    checked = captureHypervisorPages,
                    onCheckedChange = ::updateCaptureHypervisorPages
                )

                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_dump_nonessential),
                    description = AppStrings.s(R.string.setting_dump_nonessential_desc),
                    checked = captureNonEssentialPages,
                    enabled = captureHypervisorPages,
                    onCheckedChange = ::updateCaptureNonEssentialPages
                )

                SettingsCheckbox(
                    title = AppStrings.s(R.string.setting_dump_user_pages),
                    description = AppStrings.s(R.string.setting_dump_user_pages_desc),
                    checked = captureUserPages,
                    onCheckedChange = ::updateCaptureUserPages
                )
            }
        }

        // =================================================
        // 用户凭据
        //
        // 位置按需求定：夹在"高级"和"重置设置"之间。
        // 这里不再额外加一行分组小标题：卡片自己的标题就是"用户凭据"，
        // 再加一个同名小标题会变成"用户凭据 / 用户凭据"上下重复。
        // 三项都不落盘（身份是运行时能力，授权状态由服务持有），本组没有任何落盘入口。
        // =================================================

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsExpandableGroup(
                icon = Icons.Filled.Security,
                title = AppStrings.s(R.string.setting_credentials),
                description = AppStrings.s(R.string.setting_credentials_desc),
                expanded = credentialExpanded,
                onExpandedChange = { credentialExpanded = it }
            ) {
                // ① 用户身份：三选一（普通权限 / Shell(adb) / root）
                SettingsDropdown(
                    icon = Icons.Filled.AccountCircle,
                    title = AppStrings.s(R.string.setting_identity),
                    description = AppStrings.s(R.string.setting_identity_desc),
                    selected = credentialIdentity,
                    options = SettingsCatalog.CREDENTIAL_IDENTITIES,
                    onSelected = ::updateUserIdentity,
                    hostedInOverlay = hostedInOverlay
                )

                // ② Shizuku：右侧按钮按状态变化——
                //    已授权 -> 灰掉的"已授权"；没装 -> "请求权限"（点了失败框会提示去安装）；
                //    装了但服务没跑 -> "打开 Shizuku"（我们无法替它启动服务，只能把用户送过去）
                SettingsPermissionRow(
                    title = "Shizuku",

                    description = when {

                        shizukuGranted && shizukuRoot ->
                            AppStrings.s(R.string.setting_shizuku_root_only)

                        shizukuGranted ->
                            AppStrings.s(R.string.setting_shizuku_ready)

                        !shizukuInstalled ->
                            AppStrings.s(R.string.setting_shizuku_missing)

                        !shizukuRunning ->
                            AppStrings.s(R.string.setting_shizuku_not_running)

                        else ->
                            AppStrings.s(R.string.setting_shizuku_need_grant)
                    },

                    granted = shizukuGranted,

                    onRequest = {
                        // 没装 / 服务没跑时，"请求权限"没有意义，直接把用户送到能解决问题的地方
                        if (shizukuInstalled && !shizukuRunning) {
                            credentialApiRef?.openShizukuManager()
                        } else {
                            requestShizukuPermission()
                        }
                    },

                    actionText = if (shizukuInstalled && !shizukuRunning) AppStrings.s(R.string.setting_shizuku_open) else AppStrings.s(R.string.setting_request_perm)
                )

                // ③ Root：后台探测 su，逻辑与 Shizuku 一致（共用同一个失败框）。
                //    Shizuku 以 root 运行时这里同样是"已授权"（root 能力已经拿到）
                SettingsPermissionRow(
                    title = "Root",
                    description = if (shizukuGranted && shizukuRoot && !rootGranted) {
                        AppStrings.s(R.string.setting_root_ready)
                    } else if (shizukuGranted && shizukuRoot) {
                        AppStrings.s(R.string.setting_root_via_shizuku)
                    } else {
                        AppStrings.s(R.string.setting_root_ready)
                    },
                    granted = rootGranted,
                    onRequest = ::requestRootPermission
                )
            }
        }

        // =================================================
        // 重置设置
        // =================================================

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSection(title = AppStrings.s(R.string.setting_reset))

            NeutralOutlinedButton(
                text = AppStrings.s(R.string.setting_reset_desc),
                enabled = hasSettingsChanged,
                onClick = {
                    showResetDialog = true
                }
            )
        }

        // =================================================
        // 反馈
        // =================================================

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSection(title = AppStrings.s(R.string.setting_feedback))

            // 反馈直接跳到项目的问题页面，用户在浏览器里自己开 issue。
            // 浮窗里没有 Activity 令牌，所以必须带 FLAG_ACTIVITY_NEW_TASK，
            // 否则在浮窗中点击会抛异常；设备上没有浏览器时静默失败即可。
            val context = LocalContext.current

            NeutralOutlinedButton(
                text = AppStrings.s(R.string.setting_send_feedback),
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(SETTINGS_FEEDBACK_URL)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    try {
                        context.startActivity(intent)
                    } catch (error: ActivityNotFoundException) {
                        // 没有可处理该链接的应用，什么都不做
                    }
                }
            )
        }
    }

    // =====================================================
    // 重置确认对话框
    // =====================================================

    if (showResetDialog) {
        SettingsDialog(
            hostedInOverlay = hostedInOverlay,
            onDismissRequest = {
                showResetDialog = false
            },
            title = AppStrings.s(R.string.setting_reset_title),
            body = {
                Text(
                    text = AppStrings.s(R.string.setting_reset_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmText = AppStrings.s(R.string.setting_reset_action),
            onConfirm = {
                resetSettings()
                showResetDialog = false
            },
            dismissText = AppStrings.s(R.string.common_cancel),
            onDismiss = {
                showResetDialog = false
            }
        )
    }

    // =====================================================
    // 用户身份被拒绝的提示
    //
    // 触发时机：用户在"用户身份"里选了一个当前没有权限的选项。
    // 结果：只提示，不改任何值——下拉里仍然停在他原来选的那个（这就是"回退"）。
    // =====================================================

    if (showIdentityRejectedDialog) {
        SettingsDialog(
            hostedInOverlay = hostedInOverlay,
            onDismissRequest = {
                showIdentityRejectedDialog = false
            },
            title = AppStrings.s(R.string.setting_rejected_title),
            body = {
                Text(
                    text = identityRejectedNote.ifBlank {
                        AppStrings.s(R.string.setting_rejected_body)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmText = AppStrings.s(R.string.setting_got_it),
            onConfirm = {
                showIdentityRejectedDialog = false
            },

            // 只提示、不改任何值："知道了"与"取消"做的是同一件事，只留一个按钮
            dismissText = null,
            onDismiss = { }
        )
    }

    // =====================================================
    // [OVERLAY-v1] 悬浮窗权限说明对话框
    //
    // 为什么必须有这一步：SYSTEM_ALERT_WINDOW 没有运行时申请接口，
    // 而且部分 ROM 把它藏得很深，用户不知道该去哪开——所以把原因、路径、用途一起讲清楚。
    // =====================================================

    if (showOverlayPermissionDialog) {
        SettingsDialog(
            hostedInOverlay = hostedInOverlay,
            onDismissRequest = {
                showOverlayPermissionDialog = false

                // 关掉说明框 = 这次不打算开了，别让"以后给了权限"把它自动勾上
                pendingEnableOverlay = false
            },
            title = AppStrings.s(R.string.setting_overlay_title),
            body = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = AppStrings.s(R.string.setting_overlay_body_usage),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = AppStrings.s(R.string.setting_overlay_body_steps),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = AppStrings.s(R.string.setting_overlay_body_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmText = AppStrings.s(R.string.setting_overlay_go),
            onConfirm = {
                showOverlayPermissionDialog = false

                // 直达本应用那一项的悬浮窗设置页。
                // pendingEnableOverlay 留着：授权回来后由复核补上这次勾选并落盘。
                openOverlayPermissionPage()
            },
            dismissText = AppStrings.s(R.string.common_cancel),
            onDismiss = {
                showOverlayPermissionDialog = false

                pendingEnableOverlay = false
            }
        )
    }

    // =====================================================
    // 无障碍权限说明对话框
    //
    // 与悬浮窗那个框同一个套路：无障碍权限也没有运行时申请接口，
    // 用户必须自己去系统无障碍设置页把本应用的服务打开——所以照样要把原因、路径、用途讲清楚。
    // 差别只有两处：
    // - 这个权限不是"特殊应用权限"，而是无障碍服务开关，所以跳的是无障碍设置列表页；
    // - 撤销无障碍权限只关掉这条选项；「置于顶层」有悬浮窗权限就继续（窗口换成悬浮窗），
    //   没有才一起关（正文里写清楚了，避免用户以为撤销权限等于退出置顶）。
    // =====================================================

    if (showA11yPermissionDialog) {
        SettingsDialog(
            hostedInOverlay = hostedInOverlay,
            onDismissRequest = {
                showA11yPermissionDialog = false

                // 关掉说明框 = 这次不打算开了，别让"以后给了权限"把它自动勾上
                pendingEnableA11y = false
            },
            title = AppStrings.s(R.string.setting_a11y_title),
            body = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = AppStrings.s(R.string.setting_a11y_body_usage),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = AppStrings.s(R.string.setting_a11y_body_steps),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = AppStrings.s(R.string.setting_a11y_body_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmText = AppStrings.s(R.string.setting_overlay_go),
            onConfirm = {
                showA11yPermissionDialog = false

                // 跳系统的无障碍设置页（浮窗 / 无障碍窗口里没有 launcher，改走直接 startActivity）。
                // pendingEnableA11y 留着：等权限真的开了，复核时补上这次勾选并落盘。
                openA11yPermissionPage()
            },
            dismissText = AppStrings.s(R.string.common_cancel),
            onDismiss = {
                showA11yPermissionDialog = false

                pendingEnableA11y = false
            }
        )
    }

    // =====================================================
    // [OVERLAY-v1] 授权成功后的"重启生效"提示
    // =====================================================

    if (showOverlayRestartDialog) {
        SettingsDialog(
            hostedInOverlay = hostedInOverlay,
            onDismissRequest = {
                // 点空白/返回键等同"取消"：撤回这次更改
                showOverlayRestartDialog = false

                cancelAlwaysOnTopChange()
            },
            title = AppStrings.s(R.string.setting_restart_title),
            body = {
                Text(
                    text = AppStrings.s(R.string.setting_restart_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmText = AppStrings.s(R.string.setting_restart_action),
            onConfirm = {
                // 设置已在 updateWindowAlwaysOnTop 里落盘，这里只负责重启
                showOverlayRestartDialog = false

                restartApplication()
            },
            dismissText = AppStrings.s(R.string.common_cancel),
            onDismiss = {
                showOverlayRestartDialog = false

                // 撤回：走统一更新入口，会把 WINDOW_ALWAYS_ON_TOP 写回 false，
                // 并顺手清掉还没被消费的 last_page 便条
                cancelAlwaysOnTopChange()
            }
        )
    }

    // =====================================================
    // 权限请求失败（三条路径共用的同一个框）
    //
    // 触发来源（[failedPermissionTarget]）：
    // - OVERLAY：勾"置于顶层"-> 说明框 -> 去系统页 -> 回来发现权限还是没开；
    // - SHIZUKU：点"请求权限"-> Shizuku 授权被拒绝 / 服务不在 / 版本不匹配；
    // - ROOT   ：点"请求权限"-> 探测 su 失败（无 su 或用户没授权）。
    //
    // 只给两个选项：再试一次（按来源重新发起那一种申请）/ 取消（什么都不改）。
    // "再试一次"不自动重试到底：每次失败都会重新弹这个框，直到用户取消或真的拿到。
    // =====================================================

    val failedPermissionTargetValue = failedPermissionTarget

    if (failedPermissionTargetValue != PermissionRetryTarget.NONE) {

        // Shizuku 的两种"根本没法申请"要区别对待：
        // - 没装：再怎么点"再试一次"也申请不了，只能告诉用户去装；
        // - 装了但服务没启动：这是我们唯一能给出口的情况 —— 打开 Shizuku 让用户启动服务，再回来申请。
        val shizukuBlocked = failedPermissionTargetValue == PermissionRetryTarget.SHIZUKU &&
            !shizukuInstalled

        val shizukuNeedsStart = failedPermissionTargetValue == PermissionRetryTarget.SHIZUKU &&
            shizukuInstalled && !shizukuRunning

        when {

            shizukuBlocked -> SettingsDialog(
                hostedInOverlay = hostedInOverlay,
                onDismissRequest = {
                    failedPermissionTarget = PermissionRetryTarget.NONE
                },
                title = AppStrings.s(R.string.setting_no_shizuku_title),
                body = {
                    Text(
                        text = AppStrings.s(R.string.setting_no_shizuku_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmText = AppStrings.s(R.string.setting_got_it),
                onConfirm = {
                    failedPermissionTarget = PermissionRetryTarget.NONE
                },

                // 这里只是告诉用户"没有 Shizuku"，关掉就是关掉，不需要第二个按钮
                dismissText = null,
                onDismiss = { }
            )

            shizukuNeedsStart -> SettingsDialog(
                hostedInOverlay = hostedInOverlay,
                onDismissRequest = {
                    failedPermissionTarget = PermissionRetryTarget.NONE
                },
                title = AppStrings.s(R.string.setting_shizuku_stopped_title),
                body = {
                    Text(
                        text = AppStrings.s(R.string.setting_shizuku_stopped_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmText = AppStrings.s(R.string.setting_shizuku_open),
                onConfirm = {
                    credentialApiRef?.openShizukuManager()

                    failedPermissionTarget = PermissionRetryTarget.NONE
                },
                dismissText = AppStrings.s(R.string.common_cancel),
                onDismiss = {
                    failedPermissionTarget = PermissionRetryTarget.NONE
                }
            )

            else -> SettingsDialog(
                hostedInOverlay = hostedInOverlay,
                onDismissRequest = {
                    failedPermissionTarget = PermissionRetryTarget.NONE
                },
                title = AppStrings.s(R.string.setting_perm_failed_title),
                body = {
                    Text(
                        text = when (failedPermissionTargetValue) {
                            PermissionRetryTarget.OVERLAY ->
                                AppStrings.s(R.string.setting_fail_overlay_body)

                            PermissionRetryTarget.A11Y ->
                                AppStrings.s(R.string.setting_fail_a11y_body)

                            PermissionRetryTarget.SHIZUKU ->
                                AppStrings.s(R.string.setting_fail_shizuku_body)

                            PermissionRetryTarget.ROOT ->
                                AppStrings.s(R.string.setting_fail_root_body)

                            PermissionRetryTarget.NONE -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmText = AppStrings.s(R.string.setting_retry),
                onConfirm = {
                    retryFailedPermission()
                },
                dismissText = AppStrings.s(R.string.common_cancel),
                onDismiss = {
                    // 放弃这次申请：把"用户点过"的意图一起清掉，
                    // 免得以后再给权限时被自动勾上
                    failedPermissionTarget = PermissionRetryTarget.NONE

                    pendingEnableOverlay = false
                    pendingEnableA11y = false
                }
            )
        }
    }
}

/**
 * 设置页"权限还在不在"的复核间隔。
 *
 * 3 秒一次、只在设置页可见期间跑：悬浮窗权限与无障碍权限都可能被用户在系统设置里随时收回，
 * 而系统不会通知应用。查询本身很轻（一次 appops 查询 + 一次无障碍状态），
 * 取 3 秒是为了不和 UIService 那条 2 秒巡检撞在一起（两边判据相同，谁先发现都一样）。
 */
private const val TOP_WINDOW_PERMISSION_RECHECK_MS = 3_000L

/**
 * "权限请求失败"框的来源标记。
 *
 * 用它而不是三个独立布尔，是因为三条路径共用**同一个**对话框：
 * 界面上只可能有一个失败框，"再试一次"只要知道这一次是哪条路径失败即可。
 */
private enum class PermissionRetryTarget {


    /** 没有待处理的失败（框不显示）。 */
    NONE,

    /** 悬浮窗（显示在其他应用上层）权限。 */
    OVERLAY,

    /** 无障碍权限（设置页「使用无障碍权限置顶」用）。 */
    A11Y,

    /** Shizuku 授权。 */
    SHIZUKU,

    /** Root 授权。 */
    ROOT
}

// =========================================================
// 设置分组标题
// =========================================================

@Composable
private fun SettingsSection(
    title: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(
            start = 2.dp,
            bottom = 2.dp
        )
    )
}

// =========================================================
// 可折叠设置组
// =========================================================

@Composable
private fun SettingsExpandableGroup(
    icon: ImageVector,
    title: String,
    description: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onExpandedChange(!expanded)
                    }
                    .padding(
                        horizontal = 16.dp,
                        vertical = 14.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 左侧分类图标
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 标题 + 选项概览：无论展开还是折叠都始终显示
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall
                    )

                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 右侧展开/折叠箭头
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.Filled.ExpandMore
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 12.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    content()
                }
            }
        }
    }
}

// =========================================================
// 对话框外壳（普通界面 / 浮窗两种渲染方式）
// =========================================================

/**
 * 设置页的对话框外壳。
 *
 * 同一份内容、两种渲染：
 * - 普通界面（hostedInOverlay = false）：AlertDialog；
 * - 浮窗（hostedInOverlay = true）：在**当前 Compose 窗口内**画一层遮罩 + 卡片。
 *
 * 为什么浮窗必须换一套：AlertDialog 底层是 android.app.Dialog，
 * 它会另开一个 TYPE_APPLICATION 窗口并要求 Activity 的窗口令牌；
 * 浮窗是 TYPE_APPLICATION_OVERLAY，没有 Activity 令牌，直接弹会抛
 * BadTokenException（token null is not for an application），整个进程崩掉。
 * 自绘版本不新开窗口，因此完全不依赖令牌，层级也永远在浮窗内部。
 *
 * @param body 对话框正文（跟普通界面共用同一份布局代码）
 */
@Composable
private fun SettingsDialog(
    hostedInOverlay: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    body: @Composable ColumnScope.() -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    /** null = 只有确认一个按钮（"知道了"与"取消"做同一件事的提示框用这种）。 */
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
                .clickable {
                    onDismissRequest()
                }
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

// =========================================================
// 下拉选择设置卡片
// =========================================================

@Composable
private fun SettingsDropdown(
    icon: ImageVector,
    title: String,
    description: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    hostedInOverlay: Boolean
) {
    var expanded by remember {
        mutableStateOf(false)
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 14.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 左侧图标
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 标题 + 说明
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall
                    )

                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hostedInOverlay) {
                    // 浮窗里不弹 DropdownMenu：它也是独立 Popup 窗口，同样要窗口令牌
                    OutlinedButton(
                        onClick = {
                            expanded = !expanded
                        }
                    ) {
                        Text(SettingsCatalog.optionLabel(selected))
                    }
                } else {
                    Box {
                        OutlinedButton(
                            onClick = {
                                expanded = true
                            }
                        ) {
                            // 按钮上显示的是映射后的文案，selected 本身仍是内部标识
                            Text(SettingsCatalog.optionLabel(selected))
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = {
                                expanded = false
                            }
                        ) {
                            options.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(SettingsCatalog.optionLabel(option))
                                    },
                                    onClick = {
                                        expanded = false
                                        onSelected(option)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 浮窗模式：就地展开选项列表，不开新窗口
            if (hostedInOverlay && expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 10.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    options.forEach { option ->
                        val isSelected = option == selected

                        Text(
                            text = SettingsCatalog.optionLabel(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = false
                                    onSelected(option)
                                }
                                .padding(
                                    horizontal = 6.dp,
                                    vertical = 10.dp
                                )
                        )
                    }
                }
            }
        }
    }
}

// =========================================================
// 权限申请行（用户凭据：Shizuku / Root）
// =========================================================

/**
 * 一行"标题 + 说明 + 右侧按钮"的权限项。
 *
 * 按钮的两种状态都由 UIService 推来的授权状态决定，页面不自己判断：
 * - 未授权：显示"请求权限"，可点，点了走 [onRequest]（接口在 UIService 里，尚未接入真实申请）；
 * - 已授权：显示灰掉的"已授权"，**不可点**（再点一次没有意义，也避免误以为需要重复授权）。
 *
 * 之所以做成"行"而不是复选框：这两项不是"开关偏好"，而是"权限状态 + 去申请"，
 * 用户能做的动作只有一个按钮，用勾选框会让人以为可以随手点上。
 */
@Composable
private fun SettingsPermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
    /**
     * 未授权时的按钮文案。
     *
     * 默认"请求权限"；Shizuku 服务没在跑时改成"打开 Shizuku" ——
     * 因为那种情况下点"请求权限"只会立刻失败，先让用户去启动服务才是正路。
     */
    actionText: String = AppStrings.s(R.string.setting_request_perm)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedButton(
            onClick = onRequest,
            enabled = !granted,
            modifier = Modifier.padding(start = 12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (granted) {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        ) {
            Text(
                text = if (granted) AppStrings.s(R.string.setting_granted) else actionText,
                textAlign = TextAlign.Center
            )
        }
    }
}

// =========================================================
// 复选框设置
// =========================================================

@Composable
private fun SettingsCheckbox(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                onCheckedChange(!checked)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )

            // 设置项说明文字：始终保留
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.38f
                )
            )
        }

        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

// =========================================================
// 中性描边按钮
// =========================================================

@Composable
private fun NeutralOutlinedButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
            }
        )
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center
        )
    }
}

// ============================================================
// 用户凭据的界面状态（UIService <-> 设置页的实时通道）
// ============================================================
//
// 这一小段原来在 ui/UserCredentials.kt 里单独成文件，但它并不是一个页面，
// 也不是什么通用组件：唯一读它的是本文件的"用户凭据"分组，唯一写它的是 UIService
// （每次 credentialSnapshot() 时用 apply 推一份最新状态下来）。所以并回本文件。
//
// 为什么状态必须放在页面外面、进程级：主界面和浮窗里的设置页是两处 Composition，
// 各自 remember 一份就会出现"浮窗里点了授权，主界面那边还是旧按钮"。
// 授权成功是服务先知道，它推下来，两个宿主读到同一份值，按钮自己就变成已授权。

/**
 * 当前生效的凭据状态。
 *
 * 字段都是 Compose 可变状态：读它们的界面会在服务推新值时自动重组，
 * 不需要页面轮询 —— 这也是"按钮能自己变成已授权"的实现方式。
 */
internal object UserCredentialState {

    /** 当前生效的用户身份（[SettingsCatalog.CREDENTIAL_IDENTITIES] 之一）。 */
    val identity = mutableStateOf(SettingsCatalog.CREDENTIAL_NORMAL)

    /** Shizuku 是否已授权（决定 Shizuku 一栏的按钮是"请求权限"还是灰掉的"已授权"）。 */
    val shizukuGranted = mutableStateOf(false)

    /** 是否具备 Root 能力（同上，决定 Root 一栏的按钮）。 */
    val rootGranted = mutableStateOf(false)

    /** Shizuku 服务端是否以 root 运行（决定 Shizuku 一行怎么描述自己的能力）。 */
    val shizukuRoot = mutableStateOf(false)

    /** Shell(adb) 身份是否真的可用（Shizuku 已授权且服务端是 uid 2000）。 */
    val shellAvailable = mutableStateOf(false)

    /** Shizuku 管理器装没装（未安装时按钮改成"请求权限"并在失败框里提示去装）。 */
    val shizukuInstalled = mutableStateOf(false)

    /** Shizuku 服务在不在跑（装了但没启动时按钮改成"打开 Shizuku"）。 */
    val shizukuRunning = mutableStateOf(false)

    /** 把 UIService 给的最新快照整份盖到界面上。 */
    fun apply(snapshot: UserCredentialSnapshot) {

        identity.value = snapshot.identity

        shizukuGranted.value = snapshot.shizukuGranted

        rootGranted.value = snapshot.rootGranted

        shizukuRoot.value = snapshot.shizukuRoot

        shellAvailable.value = snapshot.shellAvailable

        shizukuInstalled.value = snapshot.shizukuInstalled

        shizukuRunning.value = snapshot.shizukuRunning
    }
}


