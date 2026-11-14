/**
 * ============================================================
 * 跨模块接口总表（项目内唯一的契约文件）
 * ============================================================
 *
 * 为什么单独一个文件，而不是塞进 MainActivity：
 * - MainActivity 只负责"装配"（启动服务、初始化权限通道、设置 Compose 内容），
 *   再把跨模块约定放进去，入口就同时承担导航与契约两种职责，谁调用谁更看不清；
 * - 契约集中在这里后，"一个模块能给另一个模块什么"一眼可查；
 * - 各模块文件只保留数据模型 + 实现，不再各自声明对外接口，
 *   也就不会出现"看起来像在改别人内部参数"的写法。
 *
 * 命名规范（强制）：
 *   接口名 = I + 归属模块（PascalCase） + 能力后缀
 *   - 归属模块取自"谁实现它"，与实现文件名对应（见下方对照）；
 *   - 缩写保持大写，例：IUI...；
 *   - 成员名不加前缀，保持动词开头（getX / isX / onX / submitX / toX / fromX）。
 *
 * 前缀对照（接口 -> 实现文件）：
 *   ISettingsApi          -> SettingsScreen.kt
 *   ISettingsChangeSink   -> UIService.kt
 *   IUIServiceApi         -> UIService.kt
 *   IShizukuApi           -> ShizukuManager.kt
 *   IRootApi              -> RootManager.kt
 *   ILinuxServiceApi      -> LinuxService.kt
 *   IUserCredentialApi    -> UIService.kt
 *
 * 非接口的自报表（页面 -> 状态）：
 *   PageState             -> 各 ui 页面文件自报（XxxPageState），MainActivity.kt 汇总路由
 *
 * 豁免（按约定不做 I 前缀改造，也不纳入上表）：
 * - UIService 调用 AndroidService / LinuxService 的两个特殊数据接口
 *   （GetDataFromAndroidF / GetDataFromLinux）。它们属于"外部服务主动提供"的口子，
 *   接入真实数据时整体替换，保持原名，避免和规范化后的接口混在一起。
 *
 * 新增接口的步骤：
 * 1. 在本文件声明 interface（I + 模块前缀 + 能力），注释写清用谁、谁实现、边界；
 * 2. 在归属模块文件里让实现类 `: 接口名`，被实现的成员加 override（不要再写默认参数）；
 * 3. 调用方只引用接口或实现对象，不要伸手进对方的内部字段。
 */
package com.android.taskmanager

import android.content.Context
import android.content.Intent
import com.android.taskmanager.service.ShizukuPermissionResult
import com.android.taskmanager.service.ShizukuStatus
import com.android.taskmanager.data.LinuxControlCode
import com.android.taskmanager.data.LinuxField
import com.android.taskmanager.data.LinuxPacket
import com.android.taskmanager.data.LinuxProtocol
import com.android.taskmanager.data.CpuUsagePoint
import com.android.taskmanager.data.LinuxRequestType
import com.android.taskmanager.data.PerformanceSnapshot
import com.android.taskmanager.data.ProcessScope
import kotlinx.coroutines.flow.StateFlow

// ============================================================
// 设置模块（实现：SettingsScreen.kt）
// ============================================================

/**
 * 设置模块对外的只读能力。
 *
 * 谁在调用：UIService（取默认值、按 key 保存、把已保存的值回填给设置页）。
 *
 * 边界：
 * - 只提供默认值、键名与序列化规则；
 * - 不读写磁盘（持久化在 UIService），不持有运行时状态（当前值在设置页）。
 */
interface ISettingsApi {

    /** 全部设置项的默认值快照。 */
    val defaultValues: SettingsValues

    /** 全部键名，按声明顺序。 */
    val keys: List<SettingKey>

    /** 查询单项默认值：String 型返 String，Boolean 型返 Boolean。 */
    fun defaultValueOf(key: SettingKey): Any

    /** 快照 -> "存储键 -> 值"，供持久化。 */
    fun toMap(values: SettingsValues): Map<String, Any>

    /** "存储键 -> 值" -> 快照，缺项 / 类型不符自动回落默认值。 */
    fun fromMap(saved: Map<String, Any?>): SettingsValues
}

/**
 * 设置变更的接收方。
 *
 * 谁实现：UIService（收到页面变更后落盘、并同步给其他使用该设置的页面）。
 *
 * 约定：设置页的 updateXxx() 统一调用本接口，不要绕过它直接改存储。
 * 状态：已声明、已由 UIService 提供占位实现，页面侧尚未接线。
 */
interface ISettingsChangeSink {

    /**
     * 单项设置发生变化。
     *
     * @param key 发生变化的设置项
     * @param value 新值（String 或 Boolean，按 [SettingKey] 对应类型取用）
     */
    fun onSettingChanged(key: SettingKey, value: Any)
}

// ============================================================
// UI 辅助服务（实现：UIService.kt）
// ============================================================

/**
 * UI 辅助服务对外能力。
 *
 * 谁在调用：设置页（反馈上报）、进程页 / 性能页（刷新调度）、全局设置生效逻辑。
 *
 * 状态：接口已定；实现为占位（TODO），调用方可以先接线。
 */
interface IUIServiceApi {

    /** 提交用户反馈，成功返回 true。 */
    fun submitFeedback(content: String): Boolean

    /** 启动定时刷新，进程页与性能页共用同一调度。 */
    fun startPolling(intervalMs: Long)

    /** 停止定时刷新。 */
    fun stopPolling()

    /**
     * 上报"当前前台是哪一页"（页名与 [SettingsCatalog.PAGES] 同一套文案）。
     *
     * 刷新调度只看这一页：只有前台页且它登记了 Linux 需求时，定时器才会真的去采快照；
     * 页切走之后即使定时器还在跑，也不会替后台页拉数据。
     * 主界面切页与浮窗切页签都要上报，否则服务侧无从知道谁在前台。
     */
    fun setActivePage(page: String)

    /**
     * 上报"主界面这个 Activity 是不是在前台"（MainActivity 的 onResume / onPause 调用）。
     *
     * 定时刷新驱动页面内容要同时满足两个条件：**在前台（或浮窗可见）** 且 **是被选中的那一页**。
     * 少了这一位，用户把应用退到后台、屏幕还亮着，定时器照样会替一个看不见的页拉数据。
     * 性能页是唯一豁免：它不受这两条约束（见 UIService.refreshActivePage 里的说明）。
     *
     * 名字**不能**叫 setForeground：android.app.Service 里有一个同签名的 final 方法
     * （API 34 起），重名会让 UIService 在类加载时直接 LinkageError，应用一启动就崩。
     */
    fun setAppForeground(foreground: Boolean)

    /** 运行时切换主题（"使用系统设置 / 浅色 / 深色"）。 */
    fun applyTheme(theme: String)

    // —— 设置中心：UIService 持有真值，页面按需读取 ——

    /** 设置是否已从磁盘加载并校验完成；true 时 [settings] 一定是有效值。 */
    val isSettingsReady: Boolean

    /** 当前生效的设置快照（未 ready 时返回默认值，永远不会返回 null）。 */
    fun settings(): SettingsValues

    /** 按 key 读单项当前值：String 型返 String，Boolean 型返 Boolean。 */
    fun settingOf(key: SettingKey): Any

    /** 已持久化的默认起始页名称（MainActivity 用它决定首屏）。 */
    fun startPage(): String

    // —— Linux 后端（ELF）通道：页面只能从这里间接用 ——
    //
    // 页面 / MainActivity 都不要再自己 ProcessBuilder 起进程：
    // 进程归 LinuxService 持有，本服务做转发，页面只读结果。
    // 约定：sendXxx 返回 false 表示"进程没在跑 / 管道已断"，页面据此提示而不是自行重试起进程。

    /** 启动 ELF 后端（幂等）。true = 进程已在跑。 */
    fun startLinuxBackend(): Boolean

    /** 停止 ELF 后端：走 v2 的 SHUTDOWN 控制包，超时再 destroy。 */
    fun stopLinuxBackend()

    /**
     * 给后端发一次 v2 心跳（`REQ <seq> 4 0x0`），确认"进程还在、管道没断"。
     *
     * 通道上只有 REQ / PKG 两种行，没有"自由发一条命令"的入口：
     * 页面想取什么数据，都必须先有协议里的 typeCode，
     * 否则就会绕开 seq 配对，把应答和别人的请求串在一起。
     *
     * @return true = 后端回了 OK 一个心跳包
     */
    fun pingLinuxSvc(): Boolean

    /** 后端的最近输出（命令回显 + 应答），未启动时返回状态文案而不是 null。 */
    fun linuxBackendOutput(): String

    /** 后端是否在跑。 */
    val isLinuxBackendRunning: Boolean

    /**
     * 后端是不是以提权身份在跑（Shizuku 或 root 都算）。
     *
     * false 也可能是"没运行"，配合 [isLinuxBackendRunning] 判断。
     */
    val isLinuxShellBackend: Boolean

    /** 后端身份说明文案（"shell（Shizuku）" / "root（su）" / "应用自身 UID" / "未启动"）。 */
    fun linuxBackendIdentity(): String

    /**
     * 重启 AndroidService（只这一条通道，不动 LinuxService）。
     *
     * 用途：切换用户身份后，AndroidService 里缓存的身份/自检结果是旧的，
     * 调试页要观察"换身份后它到底以什么在跑"就得把它按当前身份重新拉一遍。
     *
     * 实现约定：先从当前身份算出 privilegedMode，再 stop -> start（带同一参数）；
     * 异步执行，调用方不需要等结果，看上游输出框即可。
     */
    fun restartAndroidService()
}

// ============================================================
// Shizuku 权限通道（实现：ShizukuManager.kt）
// ============================================================

/**
 * Shizuku 提权通道的对外能力。
 *
 * 谁在调用：各功能页（判断能否读取受保护数据）、RootManager 的互补判断。
 *
 * 注意：页面不得绕过本接口直接调用 Shizuku 原始 API。
 */
interface IShizukuApi {

    /** UI 层订阅此状态即可获得最新 Shizuku 信息。 */
    val status: StateFlow<ShizukuStatus>

    /** 初始化：注册 Binder / 权限监听。 */
    fun initialize(context: Context)

    /** 主动刷新并返回当前状态。 */
    fun refreshStatus(): ShizukuStatus

    /** 检查自身授权状态。 */
    fun checkPermission(): ShizukuPermissionResult

    /**
     * 申请授权。返回 REQUESTED 仅代表请求已提交，
     * 真正的结果通过 callback 异步返回。
     */
    fun requestPermission(
        callback: ((ShizukuPermissionResult) -> Unit)? = null
    ): ShizukuPermissionResult

    /** 是否建议向用户展示权限申请说明（例如之前拒绝过）。 */
    fun shouldShowPermissionRationale(): Boolean

    /** Shizuku 服务 UID：0 = Root，2000 = Shell，-1 = 无法获取。 */
    fun getServerUid(): Int

    /** 当前 Shizuku 服务是否以 Root 身份运行。 */
    fun isRoot(): Boolean

    /** 当前 Shizuku 服务是否以 Shell 身份运行。 */
    fun isShell(): Boolean

    /**
     * 把 Shizuku 管理器应用拉到前台，让用户自己去"启动服务"。
     *
     * 为什么需要它：Shizuku 的服务端进程（shizuku_server）只能由用户在 Shizuku 应用里
     * （或通过 adb / 无线调试）启动，本应用**没有能力**替它启动。
     * 所以"没启动"这件事只能这样处理：检测到未运行 -> 打开 Shizuku -> 用户点启动 ->
     * 回来再请求权限。直接回一句"不可以"并不合格。
     *
     * @return true = 已经把 Shizuku 应用拉起来了；false = 没装 / 打不开
     */
    fun launchManagerApp(context: Context): Boolean

    /** 注销监听器（单例级释放，不要放进 Activity.onDestroy）。 */
    fun release()

    /**
     * 以 Shizuku 的身份（shell / root）启动一个外部进程。
     *
     * 为什么需要它：Linux ELF 后端要读别的应用的 /proc、cgroup 等，
     * 以应用自身 UID 起进程权限不够，必须让后端跑在 uid 2000（shell）或 0（root）下。
     *
     * 实现说明：Shizuku 13.x 把"起进程"收进了服务内部的 AIDL，公开的 Shizuku 类里
     * 只剩申请授权；本方法通过官方 aidl 构件里的 IShizukuService.newProcess 取远程进程，
     * 并以一个普通 [Process] 的形态返回（stdin / stdout / stderr / destroy 都可用）。
     *
     * @param command 完整命令行（第一个元素是可执行文件绝对路径，不经过 shell 解析）
     * @return null = 不可用（未授权 / 服务未运行 / 版本不匹配 / 服务拒绝）
     */
    fun newShellProcess(command: List<String>): Process?
}

// ============================================================
// Root 权限通道（实现：RootManager.kt）
// ============================================================

/**
 * Root 提权通道的对外能力。
 *
 * 与 [IShizukuApi] 是"二选一可用"的关系：
 * Root 可用 -> 用 Root；否则 Shizuku 已授权 -> 用 Shizuku；都不可用则禁用依赖页面。
 */
interface IRootApi {

    /** 初始化 Root 通道（检测 su、校验授权、缓存状态）。 */
    fun initialize(context: Context)

    /** 设备当前是否具备可用的 Root 权限。 */
    val isAvailable: Boolean

    /**
     * 申请 / 校验 Root 权限。
     *
     * 实现约定：探测 su 可能被系统弹窗拦住并一直等待，所以必须在后台线程做、
     * 超时就销毁进程按"无权限"处理；结果统一切回主线程后通过 callback 回传。
     *
     * @param onResult true = 确实拿到了 root（id 输出 uid=0）
     */
    fun requestRoot(onResult: (Boolean) -> Unit)

    /**
     * 以 root 身份启动一个外部进程（通过 `su -c`，stdio 正常转发）。
     *
     * 与 [IShizukuApi.newShellProcess] 是并列的两条提权通路：
     * Shizuku 不可用但有 Root 时，Linux 后端就走这一条，以 uid 0 运行。
     *
     * @return null = 没有 root 或 su 起不来；调用方据此降级为应用自身 UID
     */
    fun newRootProcess(command: List<String>): Process?
}

// ============================================================
// Linux 后端通道（实现：LinuxService.kt）
// ============================================================
/**
 * ELF 后端的启动 / 停止 / 收发能力。
 *
 * 谁在调用：只有 UIService（它负责生命周期，并把这些能力转发给页面）。
 * 页面请不要直接拿 LinuxService —— 那等于绕过数据中枢去摸上游实现。
 *
 * 边界：
 * - 本接口只管"进程 + 管道"，不解析数据内容（解析留给 UIService 的页面数据区）；
 * - Process 对象是本实现内部状态，不出现在接口上，外部无法（也不需要）拿到；
 * - 协议是纯文本、一行一条，见 cpp/Backend/main.c 的注释。
 */
interface ILinuxServiceApi {

    /** 后端是否在跑（进程还活着）。 */
    val isBackendRunning: Boolean

    /**
     * 当前后端进程是以谁的权限在跑。
     *
     * true  = 由 Shizuku（shell / root）或 root（su）起的，能读别人的 /proc；
     * false = 应用自身 UID 起的（权限受限）。
     * 具体是哪一种看 [backendIdentity]。
     */
    val isShellBackend: Boolean

    /**
     * 当前后端身份的说明文案，直接给页面显示。
     *
     * "shell（Shizuku）" / "root（su）" / "应用自身 UID" / "未启动"。
     * 为什么给字符串而不给枚举：身份来自两条完全不同的通路（Shizuku binder / su），
     * 页面只需要"是谁在跑"这一句话，不必为此在接口上再铺一层枚举。
     */
    fun backendIdentity(): String

    /** 启动 ELF 后端（幂等）。true = 进程已在跑。 */
    fun startBackend(): Boolean

    /** 停止后端：先发 v2 SHUTDOWN 控制包，超时再 destroy。 */
    fun stopBackend()

    /** 最近的输出快照（命令回显 + 应答）。 */
    fun recentOutput(): String

    // ---------- v2 请求包（见 data/LinuxService.kt 的 ① 协议契约段） ----------

    /**
     * 发一个"查询类"请求包并**等应答**。
     *
     * @param typeCode [LinuxRequestType.QUERY_PROCESS] / [LinuxRequestType.QUERY_INFO]
     * @param mask     要哪些字段（[LinuxField] 位或；QUERY_INFO 时传 0）
     * @param timeoutMs 等应答的上限，超时返回 null（不阻塞调用方的线程池）
     * @return 配对成功的应答包；进程没跑 / 发送失败 / 超时 / 后端先退出 → null
     */
    fun requestPackage(typeCode: Int, mask: Long, timeoutMs: Long): LinuxPacket?

    /**
     * 发一个"控制类"请求包并等回执。
     *
     * @param code    [LinuxControlCode]
     * @param argument 参数（例如 KILL_PROCESS 的 pid）
     * @return 服务回执包；没有后端或超时 → null
     */
    fun controlPackage(code: Int, argument: String, timeoutMs: Long): LinuxPacket?
}

/**
 * Linux 数据面（实现：UIService.kt）。
 *
 * 定位：这是"页面 / 调试页"能看到的唯一 Linux 数据入口，
 * 它背后是 [ILinuxServiceApi] 的请求包收发 + [LinuxProtocol] 的编解码。
 *
 * 为什么要有"需求登记"（[publishLinuxDemand] / [mergedLinuxDemand]）：
 * 各页面要的字段并不相同（进程页要名称与 CPU，详细信息页要几十列，性能页只要汇总），
 * 如果每页各自发请求，同一份 /proc 会被反复扫很多遍。
 * 所以页面只**声明**自己要哪些列，UISvc 取并集后一次请求，
 * 结果缓存在 [cachedLinuxSnapshot] 里，同一轮刷新内所有页读同一份快照。
 *
 * 调用边界：
 * - 页面（含浮窗里的页面）→ 只调本接口，不要直接碰 LinuxService；
 * - LinuxService → 不认识"页面"，它只按 mask 采集，不知道谁要的；
 * - AndroidService 暂不参与本协议（见用户口径：先把 Linux 这条做扎实）。
 */
interface ILinuxDataApi {

    /**
     * 取一次进程快照（**同步等应答**，调用方必须在 IO 线程）。
     *
     * @param mask 要哪些字段，用 [LinuxField] 位或拼，例如
     *             `LinuxField.NAME or LinuxField.PID or LinuxField.RSS`
     * @return 应答包；没有后端 / 超时 / 失败 → null（页面按"暂无数据"显示，不要报错）
     */
    fun GetDataFromLinuxSvc(mask: Long): LinuxPacket?

    /**
     * 让 Linux 服务做一件事（原始控制码版，主要留给调试页）。
     *
     * @param code     [LinuxControlCode]
     * @param argument 参数（KILL_PROCESS 传 "pid startTicks"，其余传空串）
     * @return true = 服务回了成功回执；false = 没后端 / 超时 / 服务侧拒绝
     */
    fun ControlLinuxSvc(code: Int, argument: String): Boolean

    /**
     * 语义化命令入口：调用方不用记控制码，也不用自己拼参数字符串。
     *
     * 与 [ControlLinuxSvc] 的分工：那个是"控制码 + 一个字符串参数"，给调试页照原样发；
     * 业务/页面调用走本方法，参数是带类型的，底层如何组包由 LinuxService 负责。
     *
     * @param command    [LinuxCommand]
     * @param pid        仅 [LinuxCommand.PROCESS_TERMINATE] 用
     * @param startTicks 仅 [LinuxCommand.PROCESS_TERMINATE] 用；0 是合法值（init 就是 0），
     *                   -1 = 没有这个信息。与 pid 一起确认"还是同一个进程"，
     *                   PID 被复用时报失败而不是杀错人
     * @return true = 服务回了 OK 或 ACCEPTED
     */
    fun CommandToLinuxSvc(
        command: LinuxCommand,
        pid: Int = 0,
        startTicks: Long = -1L,
        timeoutMs: Long = 4_000L
    ): Boolean

    /**
     * 登记"这一页需要哪些字段"（覆盖式，同一页重复调用以最后一次为准）。
     *
     * 页面进入时调一次、切列时再调一次即可，不必每帧调。
     * 传 [LinuxField.NONE] 表示"这页当前不需要 Linux 数据"（离场时用它撤销）。
     */
    fun publishLinuxDemand(page: String, mask: Long)

    /** 当前所有页面需求的并集（UISvc 内部刷新时用这个掩码去请求）。 */
    fun mergedLinuxDemand(): Long

    /** 最近一次成功取到的快照（同一轮刷新内各页共读，避免重复请求）。 */
    fun cachedLinuxSnapshot(): LinuxPacket?

    /** 服务能力自述（协议版本、字段数、支持的掩码、自身 uid 等）。 */
    fun linuxCapabilities(): LinuxPacket?
}

/**
 * 语义化控制命令（[ILinuxDataApi.CommandToLinuxSvc] 用）。
 *
 * 为什么是枚举而不是位标志：这些是互斥动作，一次只做一件；
 * 位标志会引出 `PROCESS_TERMINATE or REINIT` 这种没有意义的组合，
 * 还得在每个调用点解释"这个位到底代表什么"。枚举值本身就是文档。
 */
enum class LinuxCommand {

    /** 结束进程：必须同时给 pid 与 startTicks（防 PID 复用认错人）。 */
    PROCESS_TERMINATE,

    /** 丢掉采样基准，下一轮快照重新建立。 */
    REINIT,

    /** 打印后端内部状态。 */
    DUMP_STATE,

    /** 让后端优雅退出。 */
    SHUTDOWN
}

// ============================================================
// 用户凭据（实现：UIService.kt）
// ============================================================

/**
 * 一次"用户身份"选择的判定结果。
 *
 * 为什么要有这个枚举而不是一个 Boolean：
 * 设置页被拒绝后要告诉用户"缺的是哪一条权限"，只给 false 只能弹一句干巴巴的"不行"，
 * 用户还得自己猜去点 Shizuku 还是 Root。
 */
enum class CredentialDecision {

    /** 这个身份当前可用，UIService 已把它记成"本次运行的身份"。 */
    ALLOWED,

    /** 不可用：需要先获得 Shizuku 授权（服务以 shell / adb 身份运行）。 */
    MISSING_SHIZUKU,

    /**
     * 不可用：Shizuku 已授权，但它的服务端是以 root（uid 0）运行的。
     *
     * 这种情况下 Shizuku 给本应用的是 root 能力，拿不到 uid 2000 的 shell 身份，
     * 所以「Shell(adb)」这个选项按"未授权"处理：点它就弹失败框，
     * 用户应该改用「root」（此时 Root 一栏会显示已授权）或「普通权限」。
     */
    SHELL_UNAVAILABLE,

    /** 不可用：需要先获得 Root 权限。 */
    MISSING_ROOT,

    /** 参数非法（不是 [SettingsCatalog.CREDENTIAL_IDENTITIES] 里的任何一个值）。 */
    INVALID
}

/**
 * 用户凭据的当前快照：一个身份 + 两条提权通道的授权状态。
 *
 * 数字段都是"UIService 说了算"，设置页只读不写：
 * - [identity] 当前生效身份（UIService 从 [CredentialRecord] 恢复，改动即时落盘）；
 * - [shizukuGranted] / [rootGranted] 直接决定两个按钮显示"请求权限"还是灰掉的"已授权"。
 */
data class UserCredentialSnapshot(
    val identity: String = SettingsCatalog.CREDENTIAL_NORMAL,
    val shizukuGranted: Boolean = false,
    /**
     * Shizuku 服务端是不是以 root（uid 0）运行。
     *
     * 真机实测：Shizuku 可以跑成 root，这时它给本应用的进程身份就是 root，
     * 而不是 uid 2000 的 shell —— 界面要据此把「Shell(adb)」说清楚。
     */
    val shizukuRoot: Boolean = false,
    /**
     * Root 身份是否可用：真 Root（su），或 Shizuku 以 root 运行时为 true。
     *
     * 语义是"能不能以 uid 0 起进程"，不区分是 su 还是 Shizuku 给的。
     */
    val rootGranted: Boolean = false,
    /**
     * Shell(adb) 身份是否真的可用：Shizuku 已授权 **且** 服务端是 uid 2000。
     *
     * 注意它和 [shizukuGranted] 不是一回事：Shizuku 以 root 运行时 shizukuGranted=true，
     * 但 shellAvailable=false —— 这就是"Shizuku 不写 adb 的原因"。
     */
    val shellAvailable: Boolean = false,
    /**
     * Shizuku 管理器应用是否已安装。
     *
     * 用来区分两种"用不了"：没装（去装） vs 装了但服务没启动（去启动服务），
     * 界面上的按钮文案和失败框引导都靠它决定。
     */
    val shizukuInstalled: Boolean = false,
    /** Shizuku 服务端是否正在运行（Binder 是否连通）。装了不等于在跑。 */
    val shizukuRunning: Boolean = false
)

/**
 * 上游服务"优先用哪个身份起进程"的模式。
 *
 * 由 UIService 按"上次恢复的身份"下发给 AndroidService / LinuxService：
 * - [AUTO]：能力优先（Shizuku → root → 应用自身），也是默认行为；
 * - [SHELL]：只走 Shizuku 的 uid 2000；
 * - [ROOT]：只走 su 的 uid 0。
 *
 * 为什么不让服务自己按能力瞎挑：用户明确选了「root」时，
 * 即使 Shizuku 也授权了，也不该悄悄用 shell 身份起来 —— 那和用户的选择不符。
 */
object UpstreamIdentityMode {

    const val AUTO = "auto"

    const val SHELL = "shell"

    const val ROOT = "root"
}

/**
 * 凭据存档：跨"进程重启"保留"上次以什么身份在跑、两条提权通道拿到过什么"。
 *
 * 为什么单独存档、不塞进 SettingsValues：
 * - 它记录的是**能力事实**（上次真的拿到过 root / Shizuku），不是用户偏好；
 * - "重置设置"不应该顺手抹掉它（抹掉只会导致下次启动又要重新授权）；
 * - 设置文件损坏时它也能独立兜底，不影响设置页的修复逻辑。
 *
 * 存档只用于"启动时恢复现场"，不用于判定可不可用：
 * 真正的可用性每次启动都要重新问 Shizuku / su（授权可能被用户收回）。
 */
data class CredentialRecord(
    /** 上次退出时正在使用的身份（[SettingsCatalog.CREDENTIAL_IDENTITIES] 之一）。 */
    val identity: String = SettingsCatalog.CREDENTIAL_NORMAL,
    /** 上次 Shizuku 是否已授权（用于启动时先尝试静默复用，避免每次弹窗）。 */
    val shizukuGranted: Boolean = false,
    /** 上次是否拿到过 Root 能力（su 或 Shizuku 以 root 运行）。 */
    val rootGranted: Boolean = false,
    /** 上次 Shizuku 服务端是否以 root 运行（决定上次的 shell/root 语义）。 */
    val shizukuRoot: Boolean = false
)

/**
 * 凭据存档的读写接口（实现见 SettingsStore.kt 里的 CredentialStore）。
 *
 * 约定：
 * - load 永不抛异常：文件缺失 / 损坏 / 字段类型不对，一律返回默认值（普通权限 + 未授权）；
 * - save 失败只返回 false，不影响内存里的身份（凭据是"可用性优先"）；
 * - 本接口只负责读写，不判定可用性 —— 判定是 UIService 的事。
 */
interface ICredentialStoreApi {

    fun load(context: android.content.Context): CredentialRecord

    fun save(context: android.content.Context, record: CredentialRecord): Boolean
}

/**
 * 一次"提权请求"的结果，三个入口（Shizuku / Root / 悬浮窗）共用同一套 UI 反馈。
 *
 * 设置页只区分两件事：拿到了（按钮变灰 / 勾上开关）、没拿到（弹"权限请求失败"）。
 */
enum class PermissionRequestResult {

    /** 本次申请成功，权限已经到手。 */
    GRANTED,

    /** 本来就有权限，无需再申请（按钮应当已经是灰的）。 */
    ALREADY_GRANTED,

    /** 被拒绝 / 用户取消 / 服务不在 / 出错 —— 一律按"没拿到"处理。 */
    DENIED
}

/**
 * 用户凭据能力：设置页的"用户凭据"分组与 UIService 之间的唯一通道。
 *
 * 这个接口和别的接口不一样，它是**双向实时**的：
 * - 设置页 -> UIService：[onUserIdentitySelected] 报"用户选了哪个身份"，
 *   UIService 立刻判可用性并把结论回调回来（不可用时设置页弹框、并把选项回退）；
 * - UIService -> 设置页：[credentialSnapshot] 每次调用都会把最新授权状态推给界面，
 *   所以将来 UIService 一旦发现 Shizuku / Root 拿到了权限，
 *   两个按钮会自己从"请求权限"变成灰掉的"已授权"，不需要设置页重新打开。
 *
 * 边界（谁别越界）：
 * - 身份**会落盘**（[CredentialRecord]，见 CredentialStore）：重启后先恢复上次身份，
 *   但恢复前必须重新校验能力，能力没了就自动降级回"普通权限"并写回存档；
 * - 设置页不得自己判断权限（它拿不到 Shizuku / Root 的真实状态），一律问本接口；
 * - 本接口不碰 SettingsStore，也不进 SettingKey / SettingsValues。
 */
interface IUserCredentialApi {

    /**
     * 取当前凭据快照，并把同一份状态推给界面（设置页据此刷新两个按钮）。
     *
     * 实现约定：每次调用都必须重新读一次真实授权状态，不能返回缓存旧值，
     * 否则"授权成功但按钮还是请求权限"这种错会一直挂着。
     */
    fun credentialSnapshot(): UserCredentialSnapshot

    /**
     * 用户把"用户身份"改成了 [identity]：请求判定是否可用。
     *
     * @param identity 必须是 [SettingsCatalog.CREDENTIAL_IDENTITIES] 里的值
     * @param onDecision 判定结论。**可能同步也可能稍后回调**（实现方要去查权限），
     *        设置页必须等这个回调再决定"保留新值"还是"回退旧值"。
     */
    fun onUserIdentitySelected(identity: String, onDecision: (CredentialDecision) -> Unit)

    /**
     * 用户点了 Shizuku 一栏的"请求权限"：立刻向 Shizuku 发起一次真实授权。
     *
     * 实现约定：
     * - 请求是异步的（授权弹窗由 Shizuku 自己画），结果一定通过 [onResult] 回传；
     * - 回调已切回主线程，设置页可以直接改 Compose 状态；
     * - 拿到权限后由 UIService 负责刷新快照，并按 shell 身份重建上游服务
     *   （AndroidService / LinuxService 的进程身份随之一并升级）。
     *
     * @param onResult GRANTED / ALREADY_GRANTED = 拿到了；DENIED = 没拿到，界面弹失败框
     */
    fun requestShizukuPermission(onResult: (PermissionRequestResult) -> Unit)

    /**
     * 用户点了 Root 一栏的"请求权限"。
     *
     * Root 没有"请求授权"的系统弹窗（授权由 su 管理器决定），这里的做法是
     * 后台探测一次 `su -c id`（带超时），探到了就算拿到，然后把按钮变灰。
     *
     * @param onResult 同上；同步语义也一致（回调一定在主线程）
     */
    fun requestRootPermission(onResult: (PermissionRequestResult) -> Unit)

    /**
     * 把 Shizuku 管理器应用打开，让用户自己去"启动服务"再回来授权。
     *
     * 为什么需要它：Shizuku 的 shizuku_server 只能由用户（或 adb）启动，本应用做不到。
     * 所以"服务没在跑"这件事不能只说"不可以"，得给一个可操作的出口 ——
     * 这就是参考工程里"检测到未启动就引导去打开"的做法。
     *
     * @return true = 已经拉起 Shizuku（用户启动服务后再回来点"请求权限"即可）；
     *         false = 没装 / 打不开
     */
    fun openShizukuManager(): Boolean
}

// ============================================================
// 契约数据模型
// ============================================================

/**
 * 各设置项的合法取值白名单。
 *
 * 三个地方共用这一份，改选项只改这里：
 * 1. 设置页的下拉选项（SettingsScreen.kt）；
 * 2. 存储层的加载校验（SettingsStore.kt：文件被手改成非法值 -> 回落默认）；
 * 3. UIService 交给其他页面的设置快照。
 */
object SettingsCatalog {

    /** 应用主题的可选值。 */
    val THEMES: List<String> = listOf("使用系统设置", "浅色", "深色")

    /**
     * 默认起始页的可选值（顺序与 MainActivity 的 TaskPage 声明顺序一致）。
     *
     * 刻意不含"调试"和"设置"：这两页是"看一眼就走"的辅助页，
     * 拿来当启动首屏没有意义，还会让人以为应用一打开就坏了。
     * 这份列表同时是**存储校验白名单**（SettingsStore.allowedValues 引用它），
     * 所以手改 config 把起始页写成这两页也会被判为非法：回落"进程"，并整份回写纠正。
     */
    val START_PAGES: List<String> = listOf(
        "进程", "性能", "应用历史记录", "启动应用", "用户", "详细信息", "服务"
    )

    /**
     * 实时更新速度的可选值（顺序就是设置页下拉里的顺序）。
     *
     * 四档对应 UIService.pollIntervalMs：高 0.5s / 常规 1s / 低 4s / 已暂停（0，不自动刷新）。
     * 页面只认这份文案，不认毫秒——毫秒只在 UIService 里翻译一次。
     */
    val UPDATE_SPEEDS: List<String> = listOf("高", "常规", "低", "已暂停")

    /**
     * "用户凭据"里"用户身份"的可选值。
     *
     * 这三个值**刻意不落盘**：它们不在 SettingKey / SettingsValues 里，也不进 settings.json。
     * 身份是"这次运行以什么权限干活"的运行时能力，不是用户偏好——
     * 重启后一律回到 [CREDENTIAL_NORMAL]，由 UIService 按当前实际授权重新判定。
     *
     * 顺序就是设置页下拉里的顺序（权限由低到高）。
     */
    val CREDENTIAL_IDENTITIES: List<String> = listOf(
        CREDENTIAL_NORMAL,
        CREDENTIAL_SHELL,
        CREDENTIAL_ROOT
    )

    /** 普通权限：只用应用自身权限，不需要任何提权（恒可用）。 */
    const val CREDENTIAL_NORMAL: String = "普通权限"

    /** Shell 身份：需要 Shizuku 已授权（服务以 adb / shell 身份运行）。 */
    const val CREDENTIAL_SHELL: String = "Shell(adb)"

    /** Root 身份：需要设备具备 Root（或 Shizuku 服务以 root 身份运行）。 */
    const val CREDENTIAL_ROOT: String = "root"

    /**
     * 全部页面的名称（顺序与 MainActivity 的 TaskPage 声明顺序一致）。
     *
     * 与 START_PAGES 的区别：这里是"所有能停留的页"，包含"调试""设置"。
     * 只给 last_page（一次性"回原位"记录）做校验用——它记录的是用户真实停留过的页，
     * 必须允许这两页；而"默认起始页"仍然只能用 START_PAGES，那条硬规则不变。
     * 这份列表同时是 SettingsStore.allowedValues 的存储校验白名单。
     */
    val PAGES: List<String> = listOf(
        "进程", "性能", "应用历史记录", "启动应用", "用户", "详细信息", "服务", "调试", "设置"
    )
}

/**
 * 上游数据通道的健康状态（调试页、首次进入时的可用性提示用）。
 */
data class ChannelHealth(

    /** Android Framework 通道返回的原始文本；null = 未就绪。 */
    val androidChannel: String?,

    /** Linux 接口通道返回的原始文本；null = 未就绪。 */
    val linuxChannel: String?,

    /** 两个上游服务是否已由 UIService 拉起。 */
    val upstreamStarted: Boolean,

    /** 设置的加载来源，见 SettingsStore.Source。 */
    val settingsSource: String
)

// ============================================================
// 页面取数（实现：UIService.kt）
// ============================================================

/**
 * 页标题右侧的搜索框（MainActivity 画，内容存 UIService）。
 *
 * 为什么走 UIService 而不是让页面自己攒一份：
 * - 同一个页面可能由两个宿主承载（普通 Activity 界面、悬浮窗里自绘的界面），
 *   输入框只有一个，谁的页面都要能读到同一份关键字；
 * - 页面将来做服务端过滤（把关键字变成快照请求条件）时，只需要读这一个值。
 *
 * 页面侧的用法（示例，详细信息页）：
 * ```
 * val query = UIService.instanceOrNull()?.pageSearchQuery("详细信息").orEmpty()
 * ```
 * 现成的页面都还没实现过滤，接口先留好位置。
 */
interface IPageSearchApi {

    /**
     * 这一页要不要显示搜索框。
     *
     * 性能页返回 false：它的内容是时间序列曲线，不是能按名字捞出来的列表。
     */
    fun pageSupportsSearch(page: String): Boolean

    /**
     * 搜索框内容变了（MainActivity 调用）。空串 = 清空。
     *
     * @param page 页名，取值与 [MainActivity] 的标题一致（"进程" / "详细信息" / …）
     */
    fun setPageSearchQuery(page: String, query: String)

    /**
     * 取这一页当前的搜索关键字。没输入过就是空串（不会返回 null）。
     */
    fun pageSearchQuery(page: String): String
}

/**
 * 各功能页的取数入口。
 *
 * 定位：UIService 是"各页唯一的数据来源"，页面不直接碰 AndroidService / LinuxService。
 *
 * 现状：先只暴露通道健康状态这一个真接口（能立刻验证链路，也给调试页用）；
 * 其余页面（进程 / 性能 / 应用历史 / 启动应用 / 用户 / 详细信息 / 服务）的取数方法
 * 按 UIService.kt 里"页面数据区"的分区逐页补——每补一页，在这里加一个方法并写清
 * 返回模型，绝不要在页面里自己去调上游服务。
 */
interface IPageDataApi {

    /** 两个上游数据通道的当前状态。 */
    fun channelHealth(): ChannelHealth

    /**
     * 两个上游服务各自最近的输出（调试页第二项用）。
     *
     * 为什么合并成一个方法：调试页要"一帧一帧"对比两条通道的时序，
     * 分开两次 IPC 反而会读到不同时刻的快照，看起来像丢行。
     *
     * 实现约定：服务没就绪时返回明确的说明文案，不返回 null。
     */
    fun upstreamOutput(): UpstreamOutput

    // ---------- 性能页：整机快照 ----------
    //
    // 数据链：ELF 的设备快照应答（typeCode=5，全是 I 行）
    //        -> LinuxService.deviceSnapshot()
    //        -> UIService 清洗成 PerformanceSnapshot
    //        -> 这里 -> 性能页。
    //
    // 什么时候采：UIService 的定时器，间隔就是设置页的"实时更新速度"
    //            （高 0.5s / 常规 1s / 低 4s；"已暂停"一次都不采）。
    // 什么时候推 UI：只有性能页看得见（主界面选中它，或浮窗停在它上面）才写可观察状态；
    //              其余时候只更新内部缓存 —— 后台采集不中断，界面重组不白花。

    /**
     * 性能页最近一帧（没采过就是各字段为 null 的空帧）。
     *
     * 这个值本身是 Compose 的可观察状态：页面直接读它就会随新帧自动重组，
     * 不需要页面自己计时轮询。
     */
    fun performanceSnapshot(): PerformanceSnapshot

    /**
     * CPU 利用率的历史采样点，按时间升序，**已按当前窗口裁剪好**。
     *
     * 大图表与左列迷你图共用这一份，只是取点不同（大图全部、迷你图取尾部几个），
     * 所以两个图不可能对不上——它们就不是两份数据。
     *
     * 点的数量不固定：采样间隔跟着"实时更新速度"走（0.5s / 1s / 4s），
     * 所以不要按"每秒一个点"去推算横轴，按每个点的 timeMs 排。
     * "已暂停"时不再产生新点，但已有点照旧返回（暂停前那段曲线还在）。
     */
    fun performanceCpuHistory(): List<CpuUsagePoint>

    /**
     * 图表窗口那一行文案，例如 60 秒以内的利用率% / 利用率 % (已暂停)。
     *
     * **只在切进性能页时调一次**：用户要改实时更新速度必须回设置页，
     * 回来时这次调用自然就把新文案取到了，不需要每帧都重新问设置。
     */
    fun performanceChartWindowLabel(): String

    /**
     * 立刻采一帧（切进性能页时调，避免空等一个周期）。
     *
     * @return true = 已排进采集队列（或已有一轮在跑，跑完就有新帧）；
     *         false = 没有 Linux 后端可用，或处于"已暂停"（暂停不偷偷采）
     */
    fun refreshPerformanceNow(): Boolean

    // ---------- 详细信息页：进程属性表 ----------
    //
    // 这张表的职责划分（设计结论，页面与 UIService 都按它来）：
    // - **列**（表头）由 UIService 定义与决定显不显示：多少列 = 表有多宽；
    // - **行**（进程）由上游填充：填几行 = 表有多高，一行就是一个进程；
    // - **排序**由 UIService 负责：页面只上报"用户点了表头哪一列"，
    //   真正重排在 UIService 下一次刷新时完成（页面只管把新快照画出来）；
    // - **刷新**由 UIService 的定时器负责（间隔 = 设置页的"实时更新速度"），
    //   页面按 detailTable() 轮询拿快照，不自己计时、不自己算排序。

    /**
     * 取整张表的当前快照（已按当前排序列排好、已过滤掉隐藏列）。
     *
     * 页面每次刷新只调这一个方法：列、行、排序、选中、刷新间隔全部在里面。
     */
    fun detailTable(): DetailTable

    /**
     * 填入一行进程（一行 = 一个进程）。
     *
     * "一次填充一排"就是调一次本方法；同一 PID 重复填入按"更新这一行"处理，
     * 不会在表里长出重复行。字段 key 见 [DetailColumnId]，缺的列按空串显示。
     *
     * @return true = 已接收；false = 行非法（PID <= 0）或 UIService 尚未就绪
     */
    fun appendProcessRow(row: ProcessRow): Boolean

    /** 整表替换（适合"一次拿到全部进程"的数据源）；传空列表等于清空表格。 */
    fun replaceProcessRows(rows: List<ProcessRow>)

    /**
     * [调试页专用] 一次加入 Test0 ~ Test10 共 11 行测试进程。
     *
     * 内容约定：名称 TestN，除名称外的**所有统计值都填 N**（N = 0..10）。
     * 用途是压一下"43 列 × 11 行"的表：排序、选中、列显隐、横向滚动都能立刻看到差异。
     *
     * 注意行标识与显示值是两回事：
     * - `ProcessRow.pid`（行标识）用 9000 + N：必须为正且唯一，才能选中/长按/去重；
     * - "PID"这一列的**显示值**按约定填 N，看起来就是 TestN 的编号。
     *
     * 为什么放在调试页而不是启动时自动铺：表里有多少行应该等于真实数据（或用户主动加的），
     * 启动时自动塞假数据会让人分不清"这是真进程还是测试数据"。
     *
     * @return 实际新加入的行数（重复点按钮时已存在的行只会被刷新，不重复计数）
     */
    fun appendDetailTestRows(): Int

    /**
     * 页面报告"表格当前需要哪些列的统计信息"（也就是勾选了哪些列）。
     *
     * 语义是 **UI 的期望集合**，而不是"页面向 UIService 申请权限"：
     * - [DetailTable.columns] 之后只包含这里给的列；
     * - UIService 只需要为这些列做采样 / 计算 / 刷新，其余列可以不算 —— 这是省性能的地方；
     * - 列集合在设计期就固定（43 项，见 [DetailColumnId] 与 UIService 的列定义），
     *   **不支持动态加列**：要加统计信息就改设计、改常量表，而不是运行时扩表。
     * - 「名称」（[DetailColumnId.NAME]）是**必备列**：即使这里没给，UIService 也会补上；
     *   它也不出现在"选择列"对话框的清单里，用户关不掉它。
     *
     * 行定位与列显示无关：选中 / 长按 / 排序去重都靠 [ProcessRow.pid]（行标识），
     * 所以隐藏「PID」这一列，表格也照样知道"选中的是哪个进程"。
     *
     * 整体替换而不是增量：重复调用以最后一次为准。传空集合 = 只剩「名称」列
     * （页面会自己画"暂无可见列"，不会崩）。
     */
    fun setDetailVisibleColumns(columnIds: Set<String>)

    /**
     * 切换进程范围（详细信息页左下角三档：全部 / 用户 / 系统）。
     *
     * 筛选由 Linux 后端做：请求带着范围，应答包只含范围内的行 ——
     * 少的是"第二轮按需读文件"和"发包"两段开销，不只是渲染。
     * 口径：uid >= 10000 为用户进程，其余为系统进程。
     *
     * 切换后 UIService 会立刻按新范围重采一次，并把选择写进存档
     * （独立文件 detail_view.json，不受设置页"重置所有设置"影响）。
     */
    fun setDetailProcessScope(scope: ProcessScope)

    /**
     * "忽略实时更新速度"开关（默认 true = 忽略，也就是页面不自动刷新）。
     *
     * true  ：详细信息页不跟着设置页的"实时更新速度"自动采，只有 [refreshDetailNow] 才采；
     * false ：回到定时自动刷新。
     *
     * 这个开关本身就是性能开关：开着页面不点刷新，一个请求也不发。
     */
    fun setDetailIgnoreUpdateSpeed(ignore: Boolean)

    /**
     * 立即更新一次（页面右下角「刷新」按钮）。
     *
     * 不管"忽略实时更新速度"开着还是关着都立刻采一次；采集在后台线程，
     * 页面只需在下一轮轮询里读到新表（通常 100ms 级完成一包）。
     *
     * @return true = 已排进采集队列；false = Linux 后端还没就绪
     */
    fun refreshDetailNow(): Boolean

    /**
     * 结束选中的进程（页面右下角红色「结束进程」按钮）。
     *
     * 身份必须由 **pid + 启动时刻** 双重确认：只给 pid 时，目标进程退出后 PID 会被复用，
     * 就会杀掉另一个进程。所以这里两个参数缺一不可，缺了直接拒绝，不做"猜一个"。
     *
     * 服务端（ELF）会：在最近一次快照里核对 pid + start_ticks → 发 SIGKILL → 回执。
     * 本方法同步等回执（几十毫秒级），**不要在主线程调**，页面应放到 IO 线程。
     *
     * @param pid        目标进程号（[ProcessRow.pid]，不是表格里的显示文字）
     * @param startTicks 选中行的启动时刻（[ProcessRow.startTicks]；0 是合法值，
     *                   -1 表示没有这个信息，此时不能结束进程）
     * @return 结果；失败时 reason 已经是给用户看的文案，页面直接放进对话框
     */
    fun killDetailProcess(pid: Int, startTicks: Long): ProcessActionResult

    /**
     * 点了表头某一列：按它排序（同一列再点一次 -> 升降序翻转）。
     *
     * @param columnId null = 恢复原始顺序
     * @return true = 这一列存在且当前可见；false = 被忽略（隐藏列 / 不存在的列）
     */
    fun sortDetailTableBy(columnId: String?, descending: Boolean = true): Boolean

    /**
     * 点了某一行：把它设为"当前选中的进程"（传 null 取消选中）。
     *
     * @param pid        行标识
     * @param startTicks 这一行的 start_ticks。PID 会被系统复用，只有 pid + 启动时刻
     *                   才能确认"还是同一个进程"；**0 是合法值**（init 这类开机瞬间起的进程），
     *                   **-1 = 这行没有启动时刻**，此时退回只按 pid 认。
     */
    fun selectDetailRow(pid: Int?, startTicks: Long = -1L)

    /**
     * 长按了某一行（或某一格）：把"用户长按的是哪个进程"报给 UIService。
     *
     * 页面自己弹测试对话框没问题，但**必须**先调本方法：
     * 将来真正的"进程操作菜单"要由 UIService 统一发起，页面不该自己攒一份选中状态。
     */
    fun onDetailRowLongPressed(pid: Int, startTicks: Long = -1L)
}

/**
 * 详细信息页表格：一列（一个统计信息）的定义。
 *
 * 谁提供：[IPageDataApi.detailTable] 里由 UIService 组装；页面只按 [width] 画格子。
 *
 * @param id 稳定 key（排序 / 隐藏都用它；改显示文案不影响调用方），取值见 [DetailColumnId]
 * @param title 表头文字（用户看到的名字）
 * @param numeric 是否按数值排序：true 按 double 比大小，false 按字符串比
 * @param width 列宽（dp）。由 UIService 决定"这一列占多宽"，页面不猜
 */
data class DetailColumn(
    val id: String,
    val title: String,
    val numeric: Boolean = true,
    val width: Int = 96
)

/**
 * 详细信息页表格：一行（一个进程）。
 *
 * 一行 = 一个进程；[values] 的 key 是 [DetailColumn.id]，缺的列显示空串。
 *
 * @param pid 进程号：选中 / 长按 / 排序去重都用它，**不依赖"PID"那一列的文字**
 * @param name 进程短名（用于选中时的标题、对话框文案）
 * @param startTicks 进程启动时刻（start_ticks）。与 [pid] 一起构成行身份：
 *                   PID 会被系统复用，仅凭 pid 认人会认错成另一个进程。
 *                   **0 是合法值**（开机第 0 个 tick 起的进程就是这种，例如 init）；
 *                   **-1 = 未知**（后端没采到这个字段，这类行不能用来结束进程）。
 */
data class ProcessRow(
    val pid: Int,
    val name: String,
    val values: Map<String, String> = emptyMap(),
    val startTicks: Long = -1L
) {
    /**
     * 行身份字符串：`pid/startTicks`（startTicks 未知时退化为 `pid`）。
     *
     * 选中高亮、跨轮刷新比对都用它，不要只比 pid。
     */
    fun identity(): String = if (startTicks >= 0L) "$pid/$startTicks" else "$pid"
}

/**
 * 详细信息页表格的整张快照。
 *
 * 页面每轮刷新拿到一份新的，直接替换上一份即可。
 *
 * @param columns 只包含**当前可见**的列（勾选了哪些就是哪些，所以表宽由 UI 的选择决定）
 * @param allColumns 全部列定义（设计期固定 43 项，顺序也是固定的）。
 *                   给"选择列"对话框画复选框用：页面不需要另外维护一份列清单。
 *                   它不随可见性变化，所以在对话框里可以放心用来做"全量展示"。
 * @param rows 已按 [sortColumnId] 排好序的行（填多少行，表就多高）
 * @param sortColumnId 当前排序的列 id；null = 原始顺序
 * @param sortDescending true = 由高到低
 * @param selectedPid 当前选中的进程号（点行得到；null = 没选）
 * @param selectedStartTicks 选中行的启动时刻（与 selectedPid 一起确认"还是同一个进程"；
 *                           null = 这一行没有启动时刻）
 * @param refreshIntervalMs UIService 建议的刷新间隔（0 = 设置为"已暂停"，只响应操作不自动刷新）
 * @param processScope 当前进程范围（左下角三档的选中项；换档会重新采一次）
 * @param ignoreUpdateSpeed "忽略实时更新速度"是否生效（true = 不自动刷新，只有刷新按钮会采）
 * @param note 数据来源说明（例如"测试数据：仅一行 TEST"、降级原因），页面顶部展示
 */
data class DetailTable(
    val columns: List<DetailColumn> = emptyList(),
    val allColumns: List<DetailColumn> = emptyList(),
    val rows: List<ProcessRow> = emptyList(),
    val sortColumnId: String? = null,
    val sortDescending: Boolean = true,
    val selectedPid: Int? = null,
    val selectedStartTicks: Long? = null,
    val refreshIntervalMs: Long = 0L,
    val processScope: ProcessScope = ProcessScope.ALL,
    val ignoreUpdateSpeed: Boolean = true,
    val note: String = ""
)

/**
 * 「结束进程」这类操作的执行结果。
 *
 * UI 只关心两件事：成没成、没成的话一句能直接显示的原因。
 * 成败不看"请求发出去了"，只看服务端的回执 status（fake 回执必须当失败处理）。
 *
 * @param ok       true = 服务端确认已执行
 * @param reason   失败原因（给"无法完成该操作。"下面那行用；成功时为空）
 * @param status   服务端回执的 status 原值（排错用，0 = OK）
 */
data class ProcessActionResult(
    val ok: Boolean,
    val reason: String = "",
    val status: Int = 0
)

/**
 * 详细信息页的列 id 常量表。
 *
 * 为什么单独放一份：字段名在 UI（表头文案）、UIService（组装）、上游（填值）三处都要写，
 * 手打字符串迟早拼错一个字母，然后表现为"某一列永远是空的"。
 * 这份常量只固定 key，**不固定显示文案**（文案在 UIService 的列定义里）。
 */
object DetailColumnId {

    const val NAME = "name"
    const val PACKAGE_NAME = "package_name"
    const val PID = "pid"
    const val STATE = "state"
    const val USER = "user"
    const val SESSION = "session"
    const val PGID = "pgid"
    const val CGROUP = "cgroup"
    const val CPU_USAGE = "cpu_usage"
    const val CPU_TIME = "cpu_time"
    const val CPU_CYCLES = "cpu_cycles"
    const val RSS = "rss"
    const val PEAK_RSS = "peak_rss"
    const val RSS_DELTA = "rss_delta"
    const val ACTIVE_PRIVATE_RSS = "active_private_rss"
    const val PRIVATE_RSS = "private_rss"
    const val SHARED_RSS = "shared_rss"
    const val VSS = "vss"
    const val PAGE_FAULTS = "page_faults"
    const val PAGE_FAULTS_DELTA = "page_faults_delta"
    const val NICE = "nice"
    const val ANDROID_PRIORITY = "android_priority"
    const val FD_COUNT = "fd_count"
    const val THREAD_COUNT = "thread_count"
    const val IO_READ_COUNT = "io_read_count"
    const val IO_WRITE_COUNT = "io_write_count"
    const val IO_READ_BYTES = "io_read_bytes"
    const val IO_WRITE_BYTES = "io_write_bytes"
    const val EXE_PATH = "exe_path"
    const val CMDLINE = "cmdline"
    const val SELINUX = "selinux"
    const val TARGET_SDK = "target_sdk"
    const val PLATFORM = "platform"
    const val ARCH = "arch"
    const val IS_ADMIN = "is_admin"
    const val DESCRIPTION = "description"
    const val NX_PROTECT = "nx_protect"
    const val GPU_USAGE = "gpu_usage"
    const val GPU_ENGINE = "gpu_engine"
    const val GPU_DEDICATED_MEM = "gpu_dedicated_mem"
    const val GPU_SHARED_MEM = "gpu_shared_mem"
    const val CET_PROTECT = "cet_protect"
    const val ISOLATION = "isolation"
}

/**
 * 两个上游服务的输出快照。
 *
 * - [android] AndroidService 的输出：启动参数、身份探测结果、取数调用记录；
 * - [linux] LinuxService 的输出：ELF 后端的 READY / OK / DATA / ERR 协议应答。
 */
data class UpstreamOutput(
    val android: String = "",
    val linux: String = ""
)

// ============================================================
// 浮窗（实现：UIService.kt · OVERLAY v1 实验功能）
// ============================================================

/**
 * 浮窗能力：把现有页面搬到"其他应用上方"的窗口里。
 *
 * ！！这是可整体回退的实验功能：删掉本接口 + OverlayHost.kt + OverlayContent.kt
 * ！！+ UIService 的 ⑦ 分区即可（清单在 OverlayHost.kt 文件头）。
 *
 * 谁在调用：
 * - 设置页：判断有没有权限、跳系统权限页、提示重启；
 * - MainActivity：判断这次启动要不要走浮窗模式；
 * - UIService：自己维护窗口的显示与关闭。
 */
interface IOverlayApi {

    /**
     * 是否已被授予"显示在其他应用上层"权限（SYSTEM_ALERT_WINDOW）。
     *
     * 注意：这是"特殊应用权限"，没有运行时申请接口，只能引导用户去系统设置里开。
     */
    fun hasOverlayPermission(): Boolean

    /** 跳系统"悬浮窗管理"页的 Intent（带 package:，直达本应用那个开关）。 */
    fun overlayPermissionIntent(): Intent

    /**
     * 显示浮窗。
     *
     * @param page 首屏页面名（"进程" / "性能" / …），空串则用设置里的默认起始页
     * @return 是否成功挂上窗口（没权限 / 系统拒绝时为 false）
     */
    fun showOverlay(page: String): Boolean

    /** 关闭浮窗（不改设置，下次启动仍会按设置重建）。 */
    fun hideOverlay()

    /** 浮窗当前是否正在显示。 */
    val isOverlayShowing: Boolean
}

// ============================================================
// 页面实现状态（实现：各 ui 页面文件自报 + MainActivity.kt 汇总）
// ============================================================

/**
 * 页面实现状态。
 *
 * 谁在用它：MainActivity 的 PageContent() 在进入页面之前先问一次状态，
 * 决定是渲染真实页面、还是渲染占位提示。**页面不清空、不抛异常，只是不被路由进去。**
 *
 * 怎么用：每个页面的文件顶部声明一个 `XxxPageState`，默认 NOT_IMPLEMENTED；
 * 这个页面写完那天，把它改成 IMPLEMENTED 一行即可，MainActivity 不用动。
 * （所以这里刻意不做"接口"，不做运行时反射：一眼能看到每个页的实现进度。）
 *
 * 为什么不合并成布尔：缺权限是"以后可能支持"，没写是"现在不可能支持"，
 * 两种情况的提示文案与后续动作不同，混成一个 false 会误导用户去折腾权限。
 */
enum class PageState {

    /** 已实现：正常渲染页面内容（仍会再过一次设备能力探测，见 isPageSupported）。 */
    IMPLEMENTED,

    /** 尚未实现：MainActivity 只渲染"此页面不受支持"占位，不进入页面。 */
    NOT_IMPLEMENTED,

    /** 已实现但当前设备 / 系统环境不具备能力（例如缺 Root / Shizuku 授权）。 */
    UNAVAILABLE
}
