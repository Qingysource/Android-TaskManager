/**
 * ============================================================
 * UI 辅助服务（数据中枢 + 设置真值持有者）
 * ============================================================
 *
 * 生命周期定位：**常驻前台服务（Foreground Service）**。
 * 它不依赖任何 Activity 存活——界面关掉、从最近任务里划掉，它都继续持有上游服务、
 * 轮询与身份状态；界面只是它的视图。拉起方式是 startForegroundService，
 * onCreate/onStartCommand 里立即 startForeground，并返回 START_STICKY（被回收后自动重建）。
 *
 * 责任边界（谁负责什么，别越界）：
 *
 *     MainActivity   只做装配：启动本服务 -> 初始化权限通道 -> 读设置决定首屏
 *          |
 *          v
 *     UIService     全应用唯一的数据中枢
 *          ├── 设置中心     SettingsStore（磁盘 JSON）+ 内存快照 + 单项落盘
 *          ├── 上游维护     拉起/回收 AndroidService、LinuxService，调它们的数据接口
 *          ├── 页面数据区   每个页面要的字段，集中在自己那段注释区域里组装
 *          └── 对外接口     IUIServiceApi / ISettingsChangeSink / IPageDataApi
 *
 * 数据流：
 *     上游服务 --原始数据--> 页面数据区（清洗 / 组装） --IPageDataApi--> 各页面
 *     设置页 --onSettingChanged--> 设置中心 --SettingsStore--> filesDir/config/settings.json
 *     设置中心 --load--> MainActivity 的首屏判定 / 其他页面读默认值
 *
 * 本文件分区（搜索 "① " 到 "⑧ " 可快速跳转）：
 *     ① 进程内单例      ② 运行时状态      ③ 生命周期
 *     ④ 上游数据源维护   ⑤ 设置中心        ⑥ 页面数据区
 *     ⑦ 前台服务（常驻）+ 浮窗管理（OVERLAY v1 · 实验功能）   ⑧ 对外接口实现
 *
 * 命名与契约：接口集中在 Interfaces.kt，本类只实现，不在这里重复声明。
 */
package com.android.taskmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.telephony.TelephonyManager
import android.provider.Settings
import android.util.Log

// 内存转储落盘与时间戳：路径是应用私有目录，文件名带 pid 与时间，便于反复转储不互相覆盖
import java.io.File

import java.text.SimpleDateFormat

import java.util.Date

import java.util.Locale

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf

import com.android.taskmanager.AppStrings
import com.android.taskmanager.R
import com.android.taskmanager.ChannelHealth
import com.android.taskmanager.CredentialDecision
import com.android.taskmanager.CredentialRecord
import com.android.taskmanager.IPageDataApi
import com.android.taskmanager.IPageSearchApi
import com.android.taskmanager.ILinuxDataApi
import com.android.taskmanager.ISettingsChangeSink
import com.android.taskmanager.IOverlayApi
import com.android.taskmanager.IUIServiceApi
import com.android.taskmanager.IUserCredentialApi
import com.android.taskmanager.LinuxCommand
import com.android.taskmanager.DetailColumn
import com.android.taskmanager.DetailColumnId
import com.android.taskmanager.ProcessActionResult
import com.android.taskmanager.ProcessDumpResult
import com.android.taskmanager.ProcessSchedInfo
import com.android.taskmanager.ProcessWaitChain
import com.android.taskmanager.ProcessWaitEntry
import com.android.taskmanager.DetailTable
import com.android.taskmanager.MainActivity
import com.android.taskmanager.PermissionRequestResult
import com.android.taskmanager.ProcessRow
import com.android.taskmanager.AndroidUserSummary
import com.android.taskmanager.AndroidUsersTable
import com.android.taskmanager.AndroidUserAction
import com.android.taskmanager.UserGroupRow
import com.android.taskmanager.UserProcessRow
import com.android.taskmanager.UsersTable
import com.android.taskmanager.SettingKey
import com.android.taskmanager.SettingsApi
import com.android.taskmanager.SettingsCatalog
import com.android.taskmanager.SettingsDefaults
import com.android.taskmanager.SettingsValues
import com.android.taskmanager.ThemeState
import com.android.taskmanager.UpstreamIdentityMode
import com.android.taskmanager.UpstreamOutput
import com.android.taskmanager.UserCredentialSnapshot
import com.android.taskmanager.UserCredentialState
import com.android.taskmanager.normalizeThemeMode

import com.android.taskmanager.data.AndroidService
import com.android.taskmanager.data.AppHistoryRow
import com.android.taskmanager.data.AppHistoryTable
import com.android.taskmanager.data.BackendDisplayText
import com.android.taskmanager.data.ProcessKey
import com.android.taskmanager.data.LinuxService
import com.android.taskmanager.data.LinuxControlCode
import com.android.taskmanager.data.LinuxField
import com.android.taskmanager.data.LinuxPacket
import com.android.taskmanager.data.LinuxPacketStatus
import com.android.taskmanager.data.LinuxProcessRecord
import com.android.taskmanager.data.LinuxProtocol
import com.android.taskmanager.data.CpuUsagePoint
import com.android.taskmanager.data.CpuExtendedSnapshot
import com.android.taskmanager.data.LinuxRequestType
import com.android.taskmanager.data.PerformanceSnapshot
import com.android.taskmanager.data.ProcessScope

import com.android.taskmanager.manager.PageManagers

class UIService : Service(), IUIServiceApi, ISettingsChangeSink, IPageDataApi, IOverlayApi,
    IUserCredentialApi, ILinuxDataApi, IPageSearchApi {

    // ============================================================
    // ① 进程内单例
    //
    // UIService 是 Service，页面拿不到实例，同进程内用静态登记点直连：
    //   MainActivity / 设置页 -> UIService.instanceOrNull()
    // 以后真要做跨进程（android:process 分开），这里会拿到 null，那时才需要 Binder/AIDL。
    // ============================================================

    companion object {

        /** [OVERLAY-v1] 启动浮窗的动作：MainActivity 用它告诉服务"直接进浮窗模式"。 */
        const val ACTION_SHOW_OVERLAY = "com.android.taskmanager.action.SHOW_OVERLAY"

        /** [OVERLAY-v1] 浮窗首屏页面的额外参数名。 */
        const val EXTRA_OVERLAY_PAGE = "overlay_page"

        /** [OVERLAY-v1] 强制以普通界面启动（浮窗没建起来时的兜底）。 */
        const val EXTRA_FORCE_NORMAL = "force_normal"

        /** [OVERLAY-v1] 软重启：收掉浮窗 + 以新任务重建界面（"置顶"改完生效用）。 */
        const val ACTION_RESTART = "com.android.taskmanager.action.RESTART_RUNTIME"

        /** [OVERLAY-v1] 浮窗日志标签：adb logcat -s TMOverlay 即可只看浮窗相关。 */
        private const val TAG = "TMOverlay"

    private const val USER_ACTION_TAG = "TMUserAction"

        /** 详细信息页的页名（与 MainActivity.navigationTitle 同一套文案）。 */
        private const val PAGE_DETAIL = "详细信息"

        /** 性能页的页名（与 MainActivity.navigationTitle 同一套文案）。 */
        private const val PAGE_PERFORMANCE = "性能"

                /**
         * 详细信息页的出厂可见列。
         *
         * 只给 8 列而不是 43 列全开：页面一打开就要能读懂（名称 / 包名 / PID / 状态 /
         * 用户名 / CPU / 内存 / 路径），同时请求掩码也只有这 8 位，快照包更小、更快。
         * 用户勾选后这份布局会落到 [DetailViewStore]，下次打开按存档还原。
         */
        private val DEFAULT_DETAIL_COLUMN_IDS: List<String> = listOf(
            DetailColumnId.NAME,
            DetailColumnId.PACKAGE_NAME,
            DetailColumnId.PID,
            DetailColumnId.STATE,
            DetailColumnId.USER,
            DetailColumnId.CPU_USAGE,
            DetailColumnId.RSS,
            DetailColumnId.EXE_PATH
        )

        /**
         * 详细信息页列宽的拖动范围（dp）。
         *
         * 下限 64：再窄连"PID"这种短数字都要折行，等于把列拖废了；
         * 上限 640：够放 `com.android.settings（设置）` 这类长包名，又不至于一格占掉整屏。
         */
        private const val DETAIL_COLUMN_MIN_WIDTH_DP = 64
        private const val DETAIL_COLUMN_MAX_WIDTH_DP = 640

        /**
         * 置顶相关权限的巡检间隔（悬浮窗权限 + 无障碍权限）。
         *
         * 服务活着的期间一直跑，2 秒一次的开销可以忽略（一次 appops 查询 + 一次无障碍状态）。
         * 不能只在"窗挂着"时跑：权限可能在你还没开窗的时候就被收回，
         * 那样设置里会一直留着"已开启"，直到下次启动才被发现。
         */
        private const val TOP_WINDOW_PERMISSION_CHECK_MS = 2_000L

        /**
         * 启动时自动请求权限的兜底超时。
         *
         * 用户可能把 Shizuku 的授权框 / 面具的 su 框丢在一边不管，
         * 那就永远等不到回调 —— 20 秒后按"未授权"处理，避免身份恢复卡在半路。
         */
        private const val AUTO_CREDENTIAL_TIMEOUT_MS = 20_000L

        /**
         * 结束进程后隔多久才重采一次快照。
         *
         * 不能立刻采：SIGKILL 只是把信号递出去，内核回收进程、/proc/<pid> 消失需要一点时间。
         * 立刻重采往往又把那一行读回来，看起来像"没杀掉"（用户实测的 bug）。
         */
        private const val KILL_REFRESH_DELAY_MS = 250L

        /** 内存转储落到共享存储的哪个目录（`/storage/emulated/<用户 id>/Download`）。 */
        private const val DUMP_DIRECTORY_NAME = "dump"

        /** 当前运行的实例；未启动 / 已销毁时为 null。 */
        @Volatile
        private var instance: UIService? = null

        /** 拿实例，可能为 null（服务还没起来）。 */
        fun instanceOrNull(): UIService? = instance

        /** 拿实例，没起来直接报错（调用方明确知道自己依赖服务时用这个）。 */
        fun requireInstance(): UIService = instance
            ?: error("UIService 尚未启动：请先在 MainActivity.onCreate() 中 startForegroundService")
    }

    // ============================================================
    // ② 运行时状态
    // ============================================================

    /** 设置是否已从磁盘加载并校验完成。 */
    @Volatile
    private var settingsLoaded = false

    /** 当前生效的设置快照（唯一真值，页面通过 settings() / settingOf() 读）。 */
    @Volatile
    private var settingsSnapshot: SettingsValues = SettingsDefaults

    /** 设置的加载来源，见 SettingsStore.Source；给调试页与 channelHealth() 用。 */
    @Volatile
    private var settingsSource: String = SettingsStore.Source.UNKNOWN.name

    /** 上游两个数据服务是否已由本服务拉起。 */
    @Volatile
    private var upstreamStarted = false

    /**
     * 最近一次启动上游时下发的"是否允许提权"。
     *
     * 为什么必须记：上游进程的身份在启动那一刻就定了，改不了只能重建。
     * 冷启动时 root 的可用性是"异步探测"的（su 授权框还没弹、`probed` 还是 false），
     * 所以那次可能先以应用身份起来；等授权真拿到了，如果只比对"身份字符串没变"就跳过重建，
     * 就会出现"设置页显示 root、两个服务其实跑在默认权限"——这一条就是那个 bug 的判据。
     */
    @Volatile
    private var upstreamPrivileged = false

    /** 当前轮询间隔；0 表示没有在刷新。 */
    @Volatile
    private var pollingIntervalMs = 0L

    /**
     * 当前前台页的页名（"进程" / "详细信息" / …）。
     *
     * 刷新调度只看这一页：只有前台的、且真的需要 Linux 数据的页才会触发采集。
     * 页面切走之后它的需求会被 publishLinuxDemand(page, NONE) 撤销，
     * 这里再留一个兜底，避免"后台页还在拉快照"。
     */
    @Volatile
    private var activePageName: String = "进程"

    /**
     * 主界面 Activity 是否在前台（[setForeground] 上报）。
     *
     * 驱动页面内容要"在前台 或 浮窗可见"才做：应用退到后台、屏幕亮着的时候，
     * 定时器继续采数据纯属白烧电——页面根本不会重组，也没人看。
     */
    @Volatile
    private var appForeground: Boolean = false

    /**
     * 本次运行的用户身份（"普通权限" / "Shell(adb)" / "root"）。
     *
     * 会落盘（[CredentialRecord]）：进程重启后先恢复上次身份，
     * 但恢复不是"无条件沿用"——启动时自动重新请求授权，并把身份跟真实能力对齐：
     * 能力还在就继续用；授权被收回 / 设备不再有 su，就自动降级回"普通权限"并写回存档。
     * 这样既能记住用户的习惯，又不会出现"上次是 root，这次没 root 却还按 root 跑"。
     */
    @Volatile
    private var credentialIdentity: String = SettingsCatalog.CREDENTIAL_NORMAL

    /** 上一次成功写进存档的凭据：用来做"没变化就不写盘"的比对（页面每 400ms 轮询也会调快照）。 */
    @Volatile
    private var persistedCredentials: CredentialRecord? = null

    /**
     * 启动时的自动授权流程是否已经走完。
     *
     * 走完之前不做"身份降级"：否则恢复出来的"Shell(adb)"会在授权框还没点的时候
     * 就被判成不可用而抹掉，等用户点了允许又已经降级了。
     */
    @Volatile
    private var autoRequestDone = false

    /**
     * 主线程 Handler：所有"回到界面"的回调（提权结果、快照刷新）都经它排队。
     *
     * 为什么必须有：Shizuku 的授权结果是 Binder 线程回调的，Root 探测在后台线程，
     * 而 Compose 的 mutableStateOf 只能在主线程写，直接回调会导致界面状态错乱。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ============================================================
    // ③ 生命周期
    // ============================================================

    /**
     * 服务创建：这是全进程最早拿到"权威设置"的地方。
     *
     * 顺序很重要：
     * 1) 先加载设置（后面所有初始化都可能依赖它，例如刷新间隔）；
     * 2) 再从凭据存档恢复"上次以什么身份在跑"（只恢复意向，不判定能力）；
     * 3) 然后把两条提权通道自动请求一遍（拿到权限才能以那个身份起来）；
     * 4) 再拉起上游数据服务（此时能力已就绪，不用起来再重启）；
     * 5) 最后让设置生效（主题、轮询）。
     */
    override fun onCreate() {
        super.onCreate()

        instance = this

        // 3.0 第一步就进入前台服务：本服务是整个应用的数据中枢（设置、上游服务、轮询、浮窗），
        //     页面只是它的视图。若由 startForegroundService 拉起，系统要求 5 秒内
        //     startForeground，所以放在最前面，不能等读完设置再说。
        //     （重复调用是幂等的，后续 onStartCommand 里还会再兜一次。）
        ensureForeground()

        // 3.1 设置中心：读盘 + 校验 + 修复（细节都在 SettingsStore 里）
        val result = SettingsStore.load(this)

        settingsSnapshot = result.values
        settingsSource = result.source.name
        settingsLoaded = true

        // 3.2 凭据存档：恢复上次身份（能力是否还在，等 3.3 的结果回来再对齐）
        restoreCredentialIdentity()

        // 3.3 自动请求 Shizuku / Root：用户不用每次进设置页点"请求权限"。
        //     授权本身是持久的（Shizuku / 面具都记在它们自己那边），所以实际只在没授权时弹一次窗。
        autoRequestCredentials()

        // 3.4 上游服务**不在这里抢跑**：
        //     上游进程的身份在启动那一刻就定了，先起服务再纠正，就会出现
        //     "设置页显示 root、两个服务其实跑在默认权限"这种错位（这是修过的 bug）。
        //     所以改成"身份链出结论后再起"，由 attemptCredentialChain 的 finish() 负责首次启动。
        //     这里只留一个兜底：万一链因异常没回调，也要保证服务终究被拉起。
        mainHandler.postDelayed({
            if (!upstreamStarted) {
                Log.i(TAG, "身份链迟迟没有结论，兜底按当前身份启动上游服务")
                startUpstreamServices()
            }
        }, AUTO_CREDENTIAL_TIMEOUT_MS + 2000L)

        // 3.4.1 详细信息页的测试数据不再在启动时铺：改为调试页按钮触发（appendDetailTestRows），
        //        这样表里有多少行，完全等于真实数据或用户主动加的测试数据。

        // 3.4.2 预先读一次详细信息页的视图存档（列布局 + 范围 + 忽略开关）。
        //       属性是 lazy 的，读盘要 filesDir，而属性初始化发生在构造阶段、base context 还没 attach，
        //       所以在服务起来之后主动 touch 一次：首个快照请求才不会带着"默认全部 + 默认忽略"的错值。
        detailVisibleIds.size

        // 3.5 让已加载的设置立刻生效
        applySettingsToRuntime(settingsSnapshot)

        // 3.5.1 置顶权限巡检就此开始（服务活多久就盯多久，见 permissionWatchdog 上的说明）：
        //        悬浮窗权限、无障碍权限都可能被用户在系统里随时收回，而系统不会通知应用。
        startPermissionWatchdog()

        // 3.6 用户凭据：把真实授权状态推一次给界面（设置页"用户凭据"分组的按钮据此显示）
        //     之后每次 credentialSnapshot() 调用都会再推，服务侧一发现授权变化，界面自然跟着变。
        credentialSnapshot()

        // 3.7 页面管理器接上本服务的快照发布口：从这一刻起，每采一帧就有人接着。
        //     管理器是进程级的（横竖屏、浮窗、切页都不会丢历史），服务重建只重接订阅。
        PageManagers.attach(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        // 每次被拉起（含系统粘性重启、设置页发 ACTION_RESTART）都确认一次前台状态：
        // 进程被杀后重建时前台身份不会自动恢复，漏了这一步通知就没了、也更容易再被回收。
        ensureForeground()

        // [OVERLAY-v1] 浮窗模式：MainActivity 只发一个带 action 的启动请求，界面交给这里画
        if (intent?.action == ACTION_SHOW_OVERLAY) {
            showOverlay(intent.getStringExtra(EXTRA_OVERLAY_PAGE) ?: settingsSnapshot.defaultStartPage)
        }

        // [OVERLAY-v1] 软重启请求：界面层只管发，收窗与重建在这里统一做
        if (intent?.action == ACTION_RESTART) {
            restartRuntime()
        }

        // 粘性启动：本服务是"整个应用的数据中枢"，被系统回收后应尽量自动重建
        // （设置、身份、上游服务都能从文件/授权状态恢复，重建即回到可用状态）。
        // 返回 START_STICKY 后，系统重启服务时 intent 为 null，上面的 ensureForeground 照样执行。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 不暴露 Binder：同进程内直接调方法即可；跨进程需求出现时再换成 Binder/AIDL
        return null
    }

    override fun onDestroy() {
        // 退出前把"这次以什么身份在跑"落盘，下次启动先恢复它
        // （身份改动时其实已经写过，这里再兜一次，覆盖"退出时恰好还没写"的情况）
        runCatching { credentialSnapshot() }

        // 先摘掉前台标记：这样下面的 hideOverlay() 只收窗口，不会再去刷新通知文案
        foregroundStarted = false

        // 巡检定时任务必须在这里停掉：Handler 是主线程全局的，留着会持续持有本实例
        stopPermissionWatchdog()

        // [OVERLAY-v1] 服务销毁前必须收掉浮窗，否则会留下一个没有归属的窗口
        hideOverlay()

        // 收掉常驻通知：前台身份必须显式退出，否则"通知还在、服务已没"会让人误判
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }

        // 先回收本服务负责的上游服务，再清状态，避免留下"没人管"的常驻服务
        stopUpstreamServices()

        pollingIntervalMs = 0L

        // 采集线程不再需要：有活也派不出去了
        runCatching { linuxPollExecutor.shutdownNow() }

        // 列布局的排队写入先让它落完（都是几十字节，不会等太久）
        runCatching { detailViewSaveExecutor.shutdown() }

        // 页面管理器摘订阅：只断开与本服务的关系，历史 / 页面状态留在管理器里，
        // 服务重建后重新接上继续攒（同一进程内不丢）。
        PageManagers.detach(this)

        settingsLoaded = false
        instance = null

        super.onDestroy()
    }

    // ============================================================
    // ④ 上游数据源维护（AndroidService / LinuxService）
    //
    // 谁启动：本服务（MainActivity 只启动 UIService）
    // 谁回收：本服务 onDestroy()
    // 怎么取数：Android 侧直接调 GetDataFromAndroidF()；Linux 侧走 v2 请求包
    //           （LinuxService.requestPackage，协议见 data/LinuxService.kt ① 段），
    //           取回原始数据后在这里清洗、组装成页面要的形状（见 ⑥ 页面数据区）
    //
    // Linux 侧还多一层：ELF 后端（libtask_backend.so）的进程由 LinuxService 持有，
    // 启动 / 停止 / 发请求都经本服务转发（见 ⑧ 的"Linux 后端通道"）。
    // 页面不要再自己 ProcessBuilder——那样会出现两套进程、两套输出。
    // 页面也不要自己往管道写命令：对外只有 requestPackage / controlPackage / pingPackage 三种请求。
    //
    // 为什么用 runCatching：单个上游起不来不应该连带 UIService 挂掉，
    // 页面拿到 null 时显示"正在准备数据"即可。
    // ============================================================

    /** 拉起两个上游数据服务（幂等，重复调用不会重复启动）。 */
    private fun startUpstreamServices() {

        if (upstreamStarted) return

        // 优先身份 = 用户当前（可能是恢复出来的）身份，服务据此决定先走 shell 还是 root
        val identityMode = upstreamIdentityMode()

        // 上游要不要提权，**只由用户身份决定**：
        // - root / Shell(adb)：允许提权，服务内部再按能力挑 Shizuku(root) / su / Shizuku(shell)；
        // - 普通权限（AUTO）：不许提权，必须用应用自身 UID。
        //   这里绝不能再看"Shizuku 恰好授权着" —— 那会让设置页显示普通权限、
        //   两个服务却跑成 uid=2000，身份与进程对不上（修过的 bug）。
        //   授权过的通道不影响现在的身份，只影响"以后切到 Shell/root 时能不能用"。
        val privileged = upstreamPrivilegedWanted()

        runCatching {
            startService(
                Intent(this, AndroidService::class.java)
                    .putExtra(AndroidService.EXTRA_PRIVILEGED_MODE, privileged)
                    .putExtra(AndroidService.EXTRA_IDENTITY_MODE, identityMode)
            )
        }

        runCatching {
            startService(
                Intent(this, LinuxService::class.java)
                    .putExtra(LinuxService.EXTRA_PRIVILEGED_MODE, privileged)
                    .putExtra(LinuxService.EXTRA_IDENTITY_MODE, identityMode)
            )
        }

        upstreamStarted = true
        upstreamPrivileged = privileged
    }

    /**
     * 把"用户身份"翻译成上游服务的优先通路。
     *
     * 映射规则（身份即选择，不再按"能力"偷偷提权）：
     * - root        -> ROOT：用户明确选 root，就先 Shizuku(root)，再 su；
     * - Shell(adb)  -> SHELL：先 Shizuku 的 uid 2000；
     * - 普通权限     -> AUTO：配合"不传提权"（见 [upstreamPrivilegedWanted]），
     *   服务会用应用自身 UID。**不再**"按能力优先偷偷提权"——
     *   否则设置页显示普通权限，服务却跑成 uid=2000（修过的 bug）。
     */
    private fun upstreamIdentityMode(): String = when (credentialIdentity) {

        SettingsCatalog.CREDENTIAL_ROOT -> UpstreamIdentityMode.ROOT

        SettingsCatalog.CREDENTIAL_SHELL -> UpstreamIdentityMode.SHELL

        else -> UpstreamIdentityMode.AUTO
    }

    /**
     * 上游该不该用提权身份 —— 唯一的判据是"用户身份"。
     *
     * - root / Shell(adb)：要提权（服务内部再按能力挑通路）；
     * - 普通权限（AUTO）：不提权，用应用自身 UID。
     *
     * 为什么不再看"Shizuku 已授权 / Root 可用"：
     * 授权是"能力"，身份是"选择"。授权过 Shizuku 但身份仍是普通权限时，
     * 若按能力提权，就会出现设置页写"普通权限"、两个服务却跑成 uid=2000 的矛盾。
     */
    private fun upstreamPrivilegedWanted(): Boolean =
        upstreamIdentityMode() != UpstreamIdentityMode.AUTO

    /** 回收两个上游数据服务。 */
    private fun stopUpstreamServices() {
        if (!upstreamStarted) return

        // 先把 ELF 子进程收掉：stopService 是异步的，等它 onDestroy 再收会拖出窗口期，
        // 期间应用已经"该关了"却还留着一个后端进程。
        runCatching { LinuxService.instanceOrNull()?.stopBackend() }

        runCatching { stopService(Intent(this, AndroidService::class.java)) }
        runCatching { stopService(Intent(this, LinuxService::class.java)) }

        upstreamStarted = false
        upstreamPrivileged = false
    }

    /**
     * 上游服务该不该用提权身份：Shizuku 已授权 **或** Root 已确认可用。
     *
     * 两条通路都交给上游服务自己挑（Shizuku 优先，其次 su），这里只决定"开不开权限"，
     * 因为两者的进程身份在启动那一刻就定了，提前判断一次能少起一遍进程。
     *
     * 注意状态时序：UIService 可能比 MainActivity 先创建（startService 是异步的），
     * 此时 ShizukuManager 还没 initialize，status 停在 NOT_INITIALIZED。
     * 不补这一次初始化的话，"上次已经授权、这次启动"会被误判成没授权，
     * 上游会先以应用身份起一遍，直到用户再去设置页点一次请求才纠正。
     */
    private fun usePrivilegedUpstream(): Boolean = try {

        if (ShizukuManager.status.value.state == ShizukuState.NOT_INITIALIZED) {
            ShizukuManager.initialize(applicationContext)
        }

        ShizukuManager.status.value.isAuthorized || RootManager.isAvailable
    } catch (e: Throwable) {
        false
    }

    /**
     * 拿到 Shizuku / Root 之后重建上游服务，让两条通道都换到提权身份。
     *
     * 为什么是"重建"而不是"改参数"：进程身份在启动那一刻就定了，
     * 改不了就只剩重来（LinuxService 收到新模式时会连旧后端一起收掉）。
     */
    private fun restartUpstreamPrivileged() {

        // 身份是"普通权限"就不给上游换提权身份：
        // 这时授权 Shizuku / Root 只是"以后切到 Shell / root 时可用"，不该让当前进程偷偷变成 uid=2000。
        if (!upstreamPrivilegedWanted()) {

            Log.i(TAG, "当前身份为普通权限：不重建上游（授权只影响以后切换身份）")

            return
        }

        if (!usePrivilegedUpstream()) {
            return
        }

        Log.i(TAG, "提权能力就绪：以提权身份重建上游服务")

        stopUpstreamServices()
        startUpstreamServices()
    }

    // ============================================================
    // ④.5 凭据存档：恢复 / 自动授权 / 写回
    //
    // 为什么单独一段：这三件事只在"进程启动"和"身份变动"时发生，
    // 和上游服务的日常取数不是一类逻辑，混在一起会让生命周期函数难读。
    //
    // 存档内容（CredentialRecord）：
    //   - 上次以什么身份在跑（身份）
    //   - 上次 Shizuku 是否拿到过授权、上次 Root 是否可用（授权痕迹）
    //   - 上次 Shizuku 是不是以 root 身份运行的（决定上次的 shell/root 语义）
    // ============================================================

    /** 启动时从存档恢复身份：只恢复"意向"，能力校验交给 [credentialSnapshot]。 */
    private fun restoreCredentialIdentity() {

        val record = try {
            CredentialStore.load(this)
        } catch (e: Throwable) {
            CredentialRecord()
        }

        persistedCredentials = record
        credentialIdentity = record.identity

        UserCredentialState.identity.value = record.identity

        Log.i(
            TAG,
            "凭据存档：上次身份=${record.identity} " +
                "shizuku=${record.shizukuGranted} root=${record.rootGranted} " +
                "shizukuRoot=${record.shizukuRoot}"
        )
    }

    /**
     * 启动时按"上次身份"重新要权限，要不到就逐级回退。
     *
     * 回退链（这是用户要求的行为）：
     * - 上次 root  ：root -> Shell(adb) -> 当前进程（普通权限）
     * - 上次 Shell ：Shell(adb) -> root -> 当前进程（普通权限）
     * - 上次普通权限 / 没有存档：不自动索权，直接用当前进程（尊重"普通权限"这个选择）
     *
     * 每一级都只在"这一级确实还没拿到"时才发请求：
     * - root  : 调 RootManager.requestRoot（后台 `su -c id` 探测，面具会弹授权框）；
     * - shell : 调 Shizuku.requestPermission（Shizuku 会弹自己的授权框）；
     *           如果 Shizuku 服务根本没在跑，这一级直接算失败（我们没有能力替它启动服务），
     *           链继续往下走，用户下次进设置页可以点"打开 Shizuku"把服务启起来。
     *
     * 整条链走完仍没拿到 -> 身份降级为"普通权限"并写回存档，
     * 这样设置页显示的永远是"这一次真实的身份"，而不是上次残留的 root/shell。
     */
    private fun autoRequestCredentials() {

        autoRequestDone = false

        // Shizuku 可能还没 initialize（UIService 会比 MainActivity 更早创建）
        runCatching {
            if (ShizukuManager.status.value.state == ShizukuState.NOT_INITIALIZED) {
                ShizukuManager.initialize(applicationContext)
            }

            RootManager.initialize(applicationContext)
        }

        val restored = credentialIdentity

        if (restored == SettingsCatalog.CREDENTIAL_NORMAL) {

            // 上次就是普通权限：不打扰用户，也不用等谁。
            // 上游服务仍要启动（首次启动的唯一入口在 finish 里，这条路径直接起一次）。
            autoRequestDone = true

            Log.i(TAG, "上次身份为普通权限，不自动索权")

            credentialSnapshot()

            if (!upstreamStarted) startUpstreamServices()

            return
        }

        // Shizuku 的授权判断是同步的（pingBinder + checkSelfPermission），可以直接用；
        // Root 的"是否可用"是缓存结论，冷启动时不可信 —— 所以 root 那一级一律实测（见 attemptCredentialChain）。
        val shizukuReady = runCatching { ShizukuManager.status.value.isAuthorized }.getOrDefault(false)

        val chain = if (restored == SettingsCatalog.CREDENTIAL_ROOT) {
            listOf(SettingsCatalog.CREDENTIAL_ROOT, SettingsCatalog.CREDENTIAL_SHELL)
        } else {
            listOf(SettingsCatalog.CREDENTIAL_SHELL, SettingsCatalog.CREDENTIAL_ROOT)
        }

        attemptCredentialChain(chain, shizukuReady = shizukuReady)
    }

    /**
     * 依次尝试 [chain] 里的身份，拿到第一个就停；全失败则降级为普通权限。
     *
     * @param shizukuReady 进入链之前探到的 Shizuku 授权状态，用来跳过没必要的授权弹窗
     */
    private fun attemptCredentialChain(
        chain: List<String>,
        shizukuReady: Boolean
    ) {

        // 链已经走完（或本来就可用）：收尾
        //
        // 首次启动上游服务的唯一入口就在这里：身份链给出结论之后才起服务，
        // 这样"上游跑成什么身份"和"设置页显示的身份"永远一致。
        fun finish(identity: String, success: Boolean) {

            mainHandler.post {

                val wasStarted = upstreamStarted

                val identityChanged = identity != credentialIdentity

                if (!identityChanged && success) {

                    // 本来就是它，不需要改身份
                    autoRequestDone = true

                    credentialSnapshot()

                    if (!wasStarted) {

                        Log.i(TAG, "身份链确认「$identity」：首次启动上游服务")

                        startUpstreamServices()

                    } else if (!upstreamPrivileged) {

                        // 起了但当时还没提权（冷启动的旧路径）：按该身份重建，别让服务一直跑在默认权限
                        Log.i(TAG, "身份未变（$identity）但上游不是提权启动：按该身份重建上游服务")

                        stopUpstreamServices()
                        startUpstreamServices()
                    }

                    return@post
                }

                credentialIdentity = identity

                UserCredentialState.identity.value = identity

                autoRequestDone = true

                Log.i(TAG, "启动身份链结论：${if (success) "已恢复「$identity」" else "全部不可用，回退当前进程"}")

                // 身份变了：上游服务要按新身份重建（进程身份在启动那刻就定了）
                val snapshot = credentialSnapshot()

                persistCredentials(snapshot, force = true)

                if (!wasStarted) {

                    startUpstreamServices()

                } else if (identityChanged || !upstreamPrivileged) {

                    stopUpstreamServices()
                    startUpstreamServices()
                }
            }
        }

        // 递归地往下走：index 指向当前要在链里尝试的身份
        fun step(index: Int) {

            if (index >= chain.size) {

                finish(SettingsCatalog.CREDENTIAL_NORMAL, success = false)

                return
            }

            val target = chain[index]

            when (target) {

                SettingsCatalog.CREDENTIAL_ROOT -> {

                    // 一律**实测**一次 su，不再相信"上一轮/上次运行"的缓存结论：
                    // - 冷启动时 RootManager 根本没探过（isAvailable=false），信缓存就会误判失败；
                    // - 上次 root 这次被撤销，信缓存就会误判成功 —— 两种误判都会让上游跑错身份。
                    // 已授权时 probeSu 秒回、不弹框；没授权时面具会弹一次，用户在框里点"允许"即可。
                    runCatching {
                        RootManager.requestRoot { granted ->

                            if (granted) {
                                // 拿到真 root：用它
                                finish(SettingsCatalog.CREDENTIAL_ROOT, success = true)
                            } else {
                                step(index + 1)
                            }
                        }
                    }.onFailure {
                        step(index + 1)
                    }
                }

                SettingsCatalog.CREDENTIAL_SHELL -> {

                    if (shizukuReady) {
                        // Shizuku 已授权，但它给的是 shell 还是 root 取决于它的服务端身份
                        finish(
                            if (runCatching { ShizukuManager.status.value.isRoot }.getOrDefault(false)) {
                                SettingsCatalog.CREDENTIAL_ROOT
                            } else {
                                SettingsCatalog.CREDENTIAL_SHELL
                            },
                            success = true
                        )

                        return
                    }

                    // 服务没在跑时 requestPermission 会直接回 NOT_RUNNING，不会弹窗，链会继续往下走
                    runCatching {
                        requestShizukuPermission { result ->

                            if (result == PermissionRequestResult.GRANTED ||
                                result == PermissionRequestResult.ALREADY_GRANTED
                            ) {
                                val nowRoot = runCatching {
                                    ShizukuManager.status.value.isRoot
                                }.getOrDefault(false)

                                // Shizuku 跑成 root 时它给的就是 root 能力，身份记录成 root 才对
                                finish(
                                    if (nowRoot) SettingsCatalog.CREDENTIAL_ROOT
                                    else SettingsCatalog.CREDENTIAL_SHELL,
                                    success = true
                                )
                            } else {
                                step(index + 1)
                            }
                        }
                    }.onFailure {
                        step(index + 1)
                    }
                }

                else -> step(index + 1)
            }
        }

        // 超时兜底：把授权框丢在一边就永远等不到回调，这里到点按"全部失败"收尾
        mainHandler.postDelayed({

            if (!autoRequestDone) {

                Log.i(TAG, "身份链等待授权超时，按当前进程收尾")

                finish(SettingsCatalog.CREDENTIAL_NORMAL, success = false)
            }

        }, AUTO_CREDENTIAL_TIMEOUT_MS)

        step(0)
    }

    /**
     * 身份与能力对齐：恢复出来的身份如果现在已经不可用，就降级回"普通权限"。
     *
     * 触发场景：
     * - 上次是 Shell(adb)，这次 Shizuku 授权被撤回 / 卸载；
     * - 上次是 root，这次设备没 su 了（或面具拒绝）；
     * - 上次是 Shell(adb)，但这次的 Shizuku 改成了以 root 运行（给不出 uid 2000）。
     *
     * 只在启动自动请求走完后调用，避免"授权框还没点就把身份抹掉"。
     */
    private fun downgradeIdentityIfUnavailable(snapshot: UserCredentialSnapshot) {

        val stillAvailable = when (credentialIdentity) {
            SettingsCatalog.CREDENTIAL_NORMAL -> true

            SettingsCatalog.CREDENTIAL_SHELL -> snapshot.shellAvailable

            SettingsCatalog.CREDENTIAL_ROOT -> snapshot.rootGranted

            else -> false
        }

        if (stillAvailable) {
            return
        }

        Log.i(TAG, "上次身份「$credentialIdentity」已不可用，降级为普通权限")

        credentialIdentity = SettingsCatalog.CREDENTIAL_NORMAL

        UserCredentialState.identity.value = SettingsCatalog.CREDENTIAL_NORMAL
    }

    /**
     * 把当前凭据写进存档。
     *
     * 写盘时机：值有变化才写。快照会被页面每 400ms 轮询一次，
     * 每次都写盘等于每 400ms 一次 IO + rename，没必要。
     *
     * @param force true = 跳过"自动授权还没走完"的保护。
     *        用户主动改身份时必须强制写：那次改动是用户的明确选择，
     *        不能因为启动流程还在等授权框就被丢掉。
     */
    private fun persistCredentials(snapshot: UserCredentialSnapshot, force: Boolean = false) {

        // 自动授权还没走完时，granted 位还是"未确认"，写下去会把上次的授权痕迹洗成 false
        if (!autoRequestDone && !force) {
            return
        }

        val record = CredentialRecord(
            identity = credentialIdentity,
            shizukuGranted = snapshot.shizukuGranted,
            rootGranted = snapshot.rootGranted,
            shizukuRoot = snapshot.shizukuRoot
        )

        if (record == persistedCredentials) {
            return
        }

        if (CredentialStore.save(this, record)) {
            persistedCredentials = record
        }
    }

    /** Android 通道当前文本；null = 未拉起或尚未就绪。 */
    private fun androidChannelText(): String? =
        if (upstreamStarted) AndroidService.instanceOrNull()?.GetDataFromAndroidF() else null

    /** Linux 通道当前文本；null = 未拉起或尚未就绪（或缺少 Root/Shizuku 授权）。 */
    private fun linuxChannelText(): String? =
        if (upstreamStarted) LinuxService.instanceOrNull()?.recentOutput() else null

    // ============================================================
    // ⑤ 设置中心
    //
    // 这里是设置的"真值"所在：磁盘（SettingsStore）+ 内存（settingsSnapshot）。
    // - 读：页面调 settings() / settingOf() / startPage()
    // - 写：设置页调 onSettingChanged() -> SettingsStore.update() -> 回写内存快照
    // - 生效：applySettingsToRuntime() 把设置翻译成运行时行为
    // ============================================================

    /**
     * 把一份设置翻译成运行时行为。
     *
     * 目前只处理"主题"和"刷新速度"两件真正能立刻生效的事；
     * 其余项（窗口行为、内存转储选项）没有对应实现，留 TODO。
     */
    private fun applySettingsToRuntime(values: SettingsValues) {
        applyTheme(values.appTheme)

        val interval = pollIntervalMs(values.updateSpeed)

        if (interval > 0L) startPolling(interval) else stopPolling()

        // TODO(预留): 窗口置顶 / 使用时最小化 / 最小化时隐藏 -> 由 Compose 窗口层读取本快照实现
        // TODO(预留): 内存转储四项 -> 等真实的内存转储模块接入后从这里下发
    }

    /**
     * "实时更新速度" -> 轮询间隔（毫秒）；"已暂停"返回 0，定时器直接停。
     *
     * 这是唯一一处把选项文案翻译成数值的地方：页面只传文案，不需要懂毫秒。
     *   高 0.5s / 常规 1s / 低 4s / 已暂停 0
     */
    internal fun pollIntervalMs(speed: String): Long = when (speed) {
        "高" -> 500L
        "常规" -> 1000L
        "低" -> 4000L
        "已暂停" -> 0L

        // 未知文案（配置被手改过、或旧版本残留）按"常规"处理
        else -> 1000L
    }

    // ============================================================
    // ⑥ 页面数据区
    //
    // 每个页面一段。区域里只做三件事：
    //   1) 调上游（AndroidService / LinuxService）拿原始数据；
    //   2) 清洗成页面要的模型（模型定义在 data 包，不要塞进 Composable）；
    //   3) 通过 IPageDataApi 的新方法交出去。
    //
    // 接入新页面的步骤（照抄即可）：
    //   ① 在 Interfaces.kt 的 IPageDataApi 里加取数方法并写清返回模型；
    //   ② 在本类对应页面区域实现：取上游 -> 清洗 -> return；
    //   ③ 页面只调 IPageDataApi 的方法，绝不自己去 new / start 上游服务。
    //
    // 现在各页都只有注释没有字段，因为真实采集逻辑还没进来；
    // 字段就该写在自己那段区域里，不要散到类顶部。
    // ============================================================

    // ── 页面数据区 · 进程（ProcessScreen）──────────────────────────
    //   用途：进程列表、CPU / 内存占用、结束进程
    //   上游：AndroidService.GetDataFromAndroidF()   可见进程（无权限也能读）
    //         LinuxService.requestPackage(QUERY_PROCESS, mask)  完整进程与线程（需 Root / Shizuku）
    //         —— 掩码按"页面要哪几列"取并集，见 ⑨ 区
    //   对外：TODO(预留) IPageDataApi.processes()
    //   状态：占位，暂无字段

    // ── 页面数据区 · 性能（PerformanceScreen）──────────────────────
    //   用途：CPU 页的利用率、当前/基准频率、进程/线程/文件描述符数、开机时长、
    //         处理器插槽/内核/逻辑处理器、三级缓存
    //   上游：LinuxService.deviceSnapshot()（ELF 的 typeCode=5 设备快照，全是 I 行）
    //        —— 处理器商品名与虚拟化方案由 ELF 侧后续补实现，这里只负责把值透出去
    //   对外：IPageDataApi.performanceSnapshot() / performanceChartWindowLabel()
    //         / refreshPerformanceNow()
    //   状态：CPU 与内存两组已接真实数据；页面私有的历史 / 图表偏好在
    //        manager/PerformanceManager.kt 里，本服务只负责采集与发布。
    //
    //   三个"谁负责"的结论（都不要再抢）：
    //   1) 采集节奏：本服务的定时器（间隔 = 设置页的实时更新速度），页面不自己计时；
    //   2) 历史与图表状态：性能页管理器（PerformanceManager），本服务不存页面数据；
    //   3) 发布方式：每帧调用订阅者（[addPerformanceFrameListener]），
    //      本服务不知道任何关于"图表怎么画"的事。

    /**
     * 性能帧订阅者。
     *
     * 采集线程每采到一帧就按序通知一次；订阅者自己决定怎么存（现在只有一个：
     * 性能页管理器，它把点追加进自己的历史队列）。
     *
     * 用 CopyOnWriteArrayList：订阅几乎只发生在创建 / 销毁这种低频时刻，
     * 而通知每 0.5 秒一次、可能同时有多个线程读 —— 写时复制正好把这两件事分开。
     */
    private val performanceFrameListeners =
        java.util.concurrent.CopyOnWriteArrayList<(PerformanceSnapshot, Long) -> Unit>()

    /** 订阅性能帧（管理器在 attach 时调用；同一引用重复订阅只会被记一次）。 */
    fun addPerformanceFrameListener(listener: (PerformanceSnapshot, Long) -> Unit) {

        if (!performanceFrameListeners.contains(listener)) {
            performanceFrameListeners.add(listener)
        }
    }

    /** 退订。服务销毁时由管理器调用（数据留在管理器里，不丢）。 */
    fun removePerformanceFrameListener(listener: (PerformanceSnapshot, Long) -> Unit) {
        performanceFrameListeners.remove(listener)
    }

    /** 把一帧交给所有订阅者。异常不外抛：某个订阅者算错不该打断采集节拍。 */
    private fun notifyPerformanceFrame(snapshot: PerformanceSnapshot, atMs: Long) {

        performanceFrameListeners.forEach { listener ->

            runCatching { listener(snapshot, atMs) }
                .onFailure { Log.w(TAG, "性能帧订阅者处理失败：${it.message}") }
        }
    }

    // ── 页面数据区 · 应用历史记录（ApphistoryScreen）──────────────
    //   用途：按时间线展示应用的资源使用历史
    //   上游：Android 通道（UsageStats，需要 PACKAGE_USAGE_STATS 特殊授权）+ 本服务的采样缓存
    //   对外：TODO(预留) IPageDataApi.appHistory()
    //   状态：占位，暂无字段

    // ── 页面数据区 · 启动应用（StartupAppScreen）──────────────────
    //   用途：开机自启 / 后台拉起项的管理
    //   上游：包管理器查询受保护广播接收器（QUERY_ALL_PACKAGES）+ 系统启动项配置
    //   对外：TODO(预留) IPageDataApi.startupApps()
    //   状态：占位，暂无字段

    // ── 页面数据区 · 用户（UsersScreen）───────────────────────────
    //   用途：系统用户 / 账户列表（是否显示完整账户名由设置 showFullAccountName 决定）
    //   上游：Android 通道（UserManager）+ Linux 通道（/etc/passwd，需权限）
    //   对外：TODO(预留) IPageDataApi.users()
    //   状态：占位，暂无字段

    // ── 页面数据区 · 详细信息（DetailsScreen）─────────────────────
    //   用途：一张"进程属性表"——每一列是一个统计信息，每一行是一个进程
    //   上游：Linux 通道（/proc/<pid>/...）为主，Android 通道补包名 / 目标 API 等
    //   对外：IPageDataApi.detailTable() / appendProcessRow() / replaceProcessRows()
    //         / setDetailColumnVisible() / sortDetailTableBy() / selectDetailRow()
    //         / onDetailRowLongPressed()
    //   状态：表头 43 列已定义、可见列可开关；行数据先用 1 条测试进程（名称 TEST，其余全 1），
    //         等 Linux 服务开放 /proc 遍历后由本区在 tick 里替换（见 refreshDetailRows())
    //
    //   ── 三个"谁负责"的结论（页面不要抢）──
    //   1) 列集合与列宽：UIService。页面只按列宽画格子，不改表头；
    //   2) 排序：UIService。页面点表头只上报列 id，真正重排发生在下一次刷新；
    //   3) 定时刷新：UIService。间隔来自设置"实时更新速度"，见 startPolling() 的 tick。

    /** 全部列的定义：表头文案、列宽、按数值还是按字符串排序。 */
    private val detailColumns: List<DetailColumn> = listOf(
        DetailColumn(DetailColumnId.NAME, AppStrings.s(R.string.dcol_name), numeric = false, width = 180),
        // 包名列比原来宽 60：现在这一格是"包名（应用名）"，例如 com.android.settings（设置）
        DetailColumn(DetailColumnId.PACKAGE_NAME, AppStrings.s(R.string.dcol_package), numeric = false, width = 220),
        DetailColumn(DetailColumnId.PID, "PID", width = 72),
        DetailColumn(DetailColumnId.STATE, AppStrings.s(R.string.dcol_state), numeric = false, width = 88),
        DetailColumn(DetailColumnId.USER, AppStrings.s(R.string.dcol_user), numeric = false, width = 120),
        DetailColumn(DetailColumnId.SESSION, AppStrings.s(R.string.dcol_session), width = 80),
        DetailColumn(DetailColumnId.PGID, AppStrings.s(R.string.dcol_pgid), width = 88),
        DetailColumn(DetailColumnId.CGROUP, AppStrings.s(R.string.dcol_cgroup), numeric = false, width = 180),
        DetailColumn(DetailColumnId.CPU_USAGE, AppStrings.s(R.string.dcol_cpu_usage), width = 96),
        DetailColumn(DetailColumnId.CPU_TIME, AppStrings.s(R.string.dcol_cpu_time), width = 96),
        DetailColumn(DetailColumnId.CPU_CYCLES, AppStrings.s(R.string.dcol_cpu_cycles), width = 104),
        DetailColumn(DetailColumnId.RSS, AppStrings.s(R.string.dcol_rss), width = 120),
        DetailColumn(DetailColumnId.PEAK_RSS, AppStrings.s(R.string.dcol_peak_rss), width = 120),
        DetailColumn(DetailColumnId.RSS_DELTA, AppStrings.s(R.string.dcol_rss_delta), width = 120),
        DetailColumn(DetailColumnId.ACTIVE_PRIVATE_RSS, AppStrings.s(R.string.dcol_active_private), width = 136),
        DetailColumn(DetailColumnId.PRIVATE_RSS, AppStrings.s(R.string.dcol_private), width = 120),
        DetailColumn(DetailColumnId.SHARED_RSS, AppStrings.s(R.string.dcol_shared), width = 120),
        DetailColumn(DetailColumnId.VSS, AppStrings.s(R.string.dcol_vss), width = 120),
        DetailColumn(DetailColumnId.PAGE_FAULTS, AppStrings.s(R.string.dcol_page_faults), width = 120),
        DetailColumn(DetailColumnId.PAGE_FAULTS_DELTA, AppStrings.s(R.string.dcol_page_faults_delta), width = 120),
        DetailColumn(DetailColumnId.NICE, AppStrings.s(R.string.dcol_nice), width = 136),
        DetailColumn(DetailColumnId.ANDROID_PRIORITY, AppStrings.s(R.string.dcol_android_priority), width = 144),
        DetailColumn(DetailColumnId.FD_COUNT, AppStrings.s(R.string.dcol_fd), width = 112),
        DetailColumn(DetailColumnId.THREAD_COUNT, AppStrings.s(R.string.dcol_threads), width = 80),
        DetailColumn(DetailColumnId.IO_READ_COUNT, AppStrings.s(R.string.dcol_io_reads), width = 104),
        DetailColumn(DetailColumnId.IO_WRITE_COUNT, AppStrings.s(R.string.dcol_io_writes), width = 104),
        DetailColumn(DetailColumnId.IO_READ_BYTES, AppStrings.s(R.string.dcol_io_read_bytes), width = 112),
        DetailColumn(DetailColumnId.IO_WRITE_BYTES, AppStrings.s(R.string.dcol_io_write_bytes), width = 112),
        DetailColumn(DetailColumnId.EXE_PATH, AppStrings.s(R.string.dcol_path), numeric = false, width = 220),
        DetailColumn(DetailColumnId.CMDLINE, AppStrings.s(R.string.dcol_cmdline), numeric = false, width = 220),
        DetailColumn(DetailColumnId.SELINUX, AppStrings.s(R.string.dcol_selinux), numeric = false, width = 160),
        DetailColumn(DetailColumnId.TARGET_SDK, AppStrings.s(R.string.dcol_target_sdk), width = 120),
        DetailColumn(DetailColumnId.PLATFORM, AppStrings.s(R.string.dcol_platform), numeric = false, width = 80),
        DetailColumn(DetailColumnId.ARCH, AppStrings.s(R.string.dcol_arch), numeric = false, width = 96),
        DetailColumn(DetailColumnId.IS_ADMIN, AppStrings.s(R.string.dcol_admin), numeric = false, width = 104),
        DetailColumn(DetailColumnId.DESCRIPTION, AppStrings.s(R.string.dcol_desc), numeric = false, width = 160),
        DetailColumn(DetailColumnId.NX_PROTECT, AppStrings.s(R.string.dcol_nx), numeric = false, width = 96),
        DetailColumn(DetailColumnId.GPU_USAGE, AppStrings.s(R.string.dcol_gpu_usage), width = 96),
        DetailColumn(DetailColumnId.GPU_ENGINE, AppStrings.s(R.string.dcol_gpu_engine), numeric = false, width = 96),
        DetailColumn(DetailColumnId.GPU_DEDICATED_MEM, AppStrings.s(R.string.dcol_gpu_dedicated), width = 112),
        DetailColumn(DetailColumnId.GPU_SHARED_MEM, AppStrings.s(R.string.dcol_gpu_shared), width = 112),
        DetailColumn(DetailColumnId.CET_PROTECT, AppStrings.s(R.string.dcol_cet), numeric = false, width = 156),
        DetailColumn(DetailColumnId.ISOLATION, AppStrings.s(R.string.dcol_isolation), numeric = false, width = 96)
    )

    /** 表数据与界面状态的锁：tick 在 Handler 线程写，页面在 UI 线程读。 */
    private val detailLock = Any()

    /** 当前表里的行（一行 = 一个进程）。 */
    private val detailRows: MutableList<ProcessRow> = mutableListOf()

    /**
     * 表里的行是不是"自动快照刷出来的"。
     *
     * false = 有手工数据（调试页按钮加的 Test0 ~ Test10）。
     * 定时刷新只在 true、或表为空时铺数据，绝不覆盖用户手动加的测试行。
     */
    private var detailRowsFromSnapshot: Boolean = false

    /**
     * 当前需要（= 显示）的列 id。
     *
     * 来源优先级：**存档**（用户上次勾选的布局）> 出厂 8 列。
     * 没有存档时 [DetailViewStore.loadPreferences] 会把默认布局直接写盘，所以第一次打开就是 8 列。
     *
     * 本集合同时是"要不要为这一列采样/计算"的依据：没勾的列不进请求掩码，
     * 见 [detailDemandMask]。
     *
     * 用 lazy 而不是直接初始化：读存档要用 filesDir，而属性初始化发生在
     * Service 构造阶段，那时 base context 还没 attach 稳妥。
     */
    private val detailVisibleIds: MutableSet<String> by lazy {
        val prefs = DetailViewStore.loadPreferences(
            context = this,
            knownIds = detailColumns.mapTo(mutableSetOf()) { it.id },
            defaults = DEFAULT_DETAIL_COLUMN_IDS
        )

        // 同一份文件里还存着"范围"和"忽略实时更新速度"，顺手一起装进来，
        // 免得为两个布尔值再读一次盘。
        detailProcessScope = prefs.scope
        detailIgnoreUpdateSpeed = prefs.ignoreUpdateSpeed

        // 用户拖出来的列宽也在这份文件里：装进来后，detailTable() 按它替换出厂宽度
        synchronized(detailLock) {
            detailColumnWidths.clear()
            detailColumnWidths.putAll(prefs.columnWidths)
        }

        prefs.visibleIds
    }

    /**
     * 用户拖表头拖动出来的列宽（列 id -> dp）；没拖过的列不在表里，用列定义里的出厂宽度。
     *
     * 只影响显示宽度，不改列集合、不改排序、不改采样需求。和可见列一起落在
     * detail_view.json（[DetailViewStore]），下次打开照旧。
     */
    private val detailColumnWidths = HashMap<String, Int>()

    /**
     * 详细信息页当前的进程范围（左下角三档）。
     *
     * 它决定"请求里带哪一档"：筛选在后端做，回来的包只含这一档的行。
     */
    @Volatile
    private var detailProcessScope: ProcessScope = ProcessScope.ALL

    /**
     * "忽略实时更新速度"是否生效（true = 详细信息页不自动刷新）。
     *
     * 默认 true：只开着页面不等于"一直要数据"，采集改由页面上的「刷新」按钮触发。
     * 关掉它才回到"按设置页的实时更新速度自动刷"。
     */
    @Volatile
    private var detailIgnoreUpdateSpeed: Boolean = true

    /**
     * 列布局的落盘线程。
     *
     * 单线程 + 队列：连续点两次"确定"时，两次写入必须按顺序落地（谁后点谁生效）。
     * 用独立线程而不是采集线程：[linuxPollExecutor] 里一次快照可能等十几秒，
     * 布局写入不该排在它后面。
     */
    private val detailViewSaveExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "ui-detail-view").apply { isDaemon = true }
        }

    /** 当前排序列（null = 原始顺序）。 */
    private var detailSortColumnId: String? = null

    /** 排序是否"由高到低"。 */
    private var detailSortDescending: Boolean = true

    /** 当前选中的进程（点行得到）。 */
    private var detailSelectedPid: Int? = null

    /** 选中行的启动时刻；与 pid 一起确认"还是同一个进程"（null = 该行没有启动时刻）。 */
    private var detailSelectedStartTicks: Long? = null

    /** 最近一次长按的进程（UIService 侧记录，将来给"进程操作菜单"用）。 */
    private var detailLongPressedPid: Int? = null

    /** 长按那一行的启动时刻（与 [detailLongPressedPid] 配对，防止 PID 复用后操作到别人）。 */
    private var detailLongPressedStartTicks: Long? = null

    /**
     * 表上方的一句来源说明。
     *
     * 启动时表是空的（测试数据只在调试页按钮里加），所以第一句是"等待上游填入"；
     * 之后 append / replace / 调试加测试行都会覆盖它。
     */
    private var detailNote: String = AppStrings.s(R.string.svc_detail_waiting_upstream)

    // ── 页面数据区 · 服务（ServicesScreen）────────────────────────
    //   用途：系统 / 应用服务的运行状态与控制
    //   上游：Android 通道（ActivityManager.getRunningServices）
    //   对外：TODO(预留) IPageDataApi.services()
    //   状态：占位，暂无字段

    // ── 页面数据区 · 调试（DebugScreen）───────────────────────────
    //   用途：通道自检、服务日志、配置导出
    //   上游：本服务自身的状态（channelHealth / SettingsStore.raw）
    //   对外：已提供 channelHealth()；配置原文用 SettingsStore.raw(context)
    //   状态：部分可用（通道健康状态已能拿到）

    // ── 页面数据区 · 设置（SettingsScreen）────────────────────────
    //   用途：设置项的读写
    //   上游：不需要上游，本区就是设置的持有者（见 ⑤ 设置中心）
    //   对外：settings() / settingOf() / startPage()；变更走 onSettingChanged()
    //   状态：已可用（默认值 + 校验 + 持久化 + 单项写回）

    // ============================================================
    // ⑦ 前台服务（常驻） + 浮窗管理（OVERLAY v1 · 实验功能）
    //
    // 定位变了：以前只有浮窗期间才 startForeground，现在**整个服务常驻前台**。
    // 因为本服务是数据中枢（设置持久化 + 上游服务 + 轮询 + 浮窗），页面只是视图；
    // 普通后台 Service 在应用退到后台约一分钟就会被系统回收，之后页面再进来会看到
    // "数据没了 / 身份丢了 / 上游服务也没了"。常驻前台后，界面开不开都不影响它干活。
    //
    // 通知只有一条，浮窗开/关只改文案（见 buildServiceNotification），不再"进/出前台"。
    //
    // ！！回退浮窗时：删掉 OverlayHost.kt / OverlayContent.kt / IOverlayApi /
    // ！！          本类里 overlayHost 及 show/hide/exit 相关成员，
    // ！！          以及 Manifest 的 SYSTEM_ALERT_WINDOW。
    // ！！          前台服务部分**不要一起删**（它已经与服务常驻绑定）。
    // ============================================================

    /** 浮窗宿主（TYPE_APPLICATION_OVERLAY，需要悬浮窗权限）。懒加载：没开浮窗时不创建窗口管理器相关对象。 */
    private val overlayHost: OverlayHost by lazy { OverlayHost(this) }

    /**
     * 无障碍置顶窗口的宿主（TYPE_ACCESSIBILITY_OVERLAY）。
     *
     * 为什么单独一个、而且懒建：
     * 1. 窗口类型不同，WindowManager 的权限校验走的是"无障碍服务"那条路，
     *    所以 Context 必须是 [A11yTopWindowService] 自己（用它 getSystemService(WINDOW_SERVICE)）；
     * 2. 服务实例可能换（用户关掉再打开会重建），Context 跟着换，宿主就必须重建；
     *    重建前先把旧窗收掉，避免留下一个没有归属的窗口。
     */
    private var a11yWindowHost: OverlayHost? = null

    /** [a11yWindowHost] 当初是用哪个服务实例建的（用来判断"服务换人了"）。 */
    private var a11yHostService: A11yTopWindowService? = null

    /** 浮窗当前显示哪一页。必须是 Compose 状态，切页才会触发重组。 */
    private val overlayPageState = mutableStateOf("进程")

    /**
     * 浮窗当前是否真的挂在屏幕上。
     *
     * 单独记一个布尔，而不是每次去问 overlayHost.isShowing：
     * 通知文案要读它，读 overlayHost 会顺带把那一坨窗口管理器对象创建出来。
     */
    private var overlayVisible = false

    /**
     * 当前有没有顶层窗口挂在屏幕上（两条通路算同一个"有没有"）。
     *
     * 通知文案、收窗回原位、退出逻辑都只关心"有没有一只顶层窗口"，
     * 不关心它是悬浮窗还是无障碍窗口——两种窗口的界面与交互本来就一样。
     */
    private fun isTopWindowShowing(): Boolean =
        overlayHost.isShowing || a11yWindowHost?.isShowing == true

    /**
     * 当前挂着的窗口是不是"无障碍那只"。
     *
     * false 有两种意思：挂着的是悬浮窗，或者根本没挂窗——调用方要判断"换通路"时
     * 用 [isTopWindowShowing] 先确认有没有窗口。
     */
    private fun isShowingA11yWindow(): Boolean = a11yWindowHost?.isShowing == true

    /**
     * 窗口还挂着的时候换了通路：当场把旧窗口收掉、按新通路重建一只，页不动。
     *
     * 这就是"已经用悬浮窗跑着，用户在设置里给了无障碍权限"的那条路：
     * 用户期望的是立刻变成无障碍窗口，而不是等下次启动。
     * 页名沿用窗口当前那一页，所以界面看起来只是闪了一下。
     */
    private fun switchTopWindowKind() {
        val page = overlayPageState.value.ifBlank { settingsSnapshot.defaultStartPage }

        Log.i(TAG, "switchTopWindowKind: 换通路重建窗口 page=$page")

        hideOverlay()

        showOverlay(page)
    }

    /**
     * 按当前设置核对"窗口该用哪条通路"，不一致就当场换。
     *
     * 只在两个设置项变化后调用；没有窗口时什么都不做（建窗是启动时的事）。
     * 想换到无障碍通路、但服务还没连上时先不动：那只窗口还能用，
     * 等服务连上会由 [onAccessibilityWindowReady] 补上这次切换。
     */
    private fun syncTopWindowKind() {
        if (!isTopWindowShowing()) return

        val values = settingsSnapshot

        val wantedA11y = values.a11yTopWindow && hasAccessibilityPermission()

        if (wantedA11y == isShowingA11yWindow()) return

        if (wantedA11y && a11yWindowHostOrNull() == null) {
            Log.i(TAG, "syncTopWindowKind: 无障碍服务还没连上，先留着当前窗口，等服务回调再换")

            return
        }

        switchTopWindowKind()
    }

    /**
     * 取（必要时新建）无障碍窗口宿主；无障碍服务没连上时返回 null。
     *
     * 服务实例换了就重建宿主：窗口的"权限凭证"其实就绑在那个服务上，
     * 用旧服务的 Context 去 addView 会被 WindowManager 拒绝。
     */
    private fun a11yWindowHostOrNull(): OverlayHost? {
        val service = A11yTopWindowService.instanceOrNull() ?: return null

        val current = a11yWindowHost

        if (current != null && a11yHostService === service) return current

        current?.hide()

        a11yWindowHost = OverlayHost(service, WindowKind.ACCESSIBILITY_OVERLAY)
        a11yHostService = service

        return a11yWindowHost
    }

    /** 只收无障碍那只窗口（浮窗那只与它无关）。幂等。 */
    private fun hideA11yWindow() {
        a11yWindowHost?.hide()

        a11yWindowHost = null
        a11yHostService = null
    }

    /** 窗口没能建起来：把普通界面拉回来，别让用户面对空屏。 */
    private fun startNormalUiFallback() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_FORCE_NORMAL, true)
            )
        }
    }

    /** 常驻前台通知的渠道 id / 通知 id（整个应用只有这一条，浮窗只是换文案）。 */
    private val serviceChannelId = "tm_service_v1"
    private val serviceNotificationId = 1001

    /**
     * 是否已经成功进入前台。
     *
     * 记住它是为了：① 重复 startForeground 会抛异常；② 更新文案时区分
     * "已经在跑 -> notify 换文字" 与 "还没进前台 -> 直接 startForeground"。
     */
    private var foregroundStarted = false

    /**
     * 确保本服务处于前台状态（幂等）。
     *
     * 为什么必须在最早期调用（onCreate 第一步、每次 onStartCommand）：
     * 若服务由 startForegroundService 拉起，系统只给 5 秒窗口，超时会直接判
     * "did not then call Service.startForeground" 崩掉。
     *
     * 失败不抛：Android 12+ 在应用处于后台时把服务提升为前台会被拒
     * （ForegroundServiceStartNotAllowedException）。这种极少数场景下宁可
     * 让这条服务退出（下次打开应用会再拉起），也不要留下一个没进前台、
     * 5 秒后被系统判 ANR 的进程。
     */
    private fun ensureForeground() {
        if (foregroundStarted) {
            // 已经在跑：只刷新文案（例如刚开了浮窗、或上游身份刚变）
            refreshServiceNotification()

            return
        }

        foregroundStarted = runCatching {
            createServiceChannelIfNeeded()

            val notification = buildServiceNotification()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    serviceNotificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(serviceNotificationId, notification)
            }
        }.onFailure {
            Log.w(TAG, "进入前台失败（${it.javaClass.simpleName}）：降级为普通后台服务", it)
        }.isSuccess

        if (!foregroundStarted) {
            // 没能进前台，说明这次是"后台启动前台服务被系统拒绝"。
            // 主动收掉自己，避免 5 秒后被系统判 ANR；下次用户打开应用会重新拉起。
            Log.w(TAG, "ensureForeground: 未能进入前台，停止本服务以免 ANR")

            runCatching { stopSelf() }
        }
    }

    /** 只改通知文案（浮窗开/关、需要让用户看到最新状态时调用）。 */
    private fun refreshServiceNotification() {
        // 还没进前台就不必提醒：首次进前台由 ensureForeground 负责，
        // 这里返回而不是回调 ensureForeground，避免两者互相递归。
        if (!foregroundStarted) return

        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(serviceNotificationId, buildServiceNotification())
        }
    }

    /** 建常驻通知渠道（Android 8 起通知必须归属渠道；IMPORTANCE_LOW = 不出声、不弹横幅）。 */
    private fun createServiceChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(serviceChannelId) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                serviceChannelId,
                AppStrings.s(R.string.svc_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = AppStrings.s(R.string.svc_channel_desc)
                setShowBadge(false)
            }
        )
    }

    /**
     * 常驻通知（点它回到正常界面）。
     *
     * 文案分两种：浮窗期间说明"正在其他应用上方显示"，普通期间说明"后台采集数据"。
     * 同一条通知、同一个 id，只换文字，避免通知栏里堆两条。
     */
    private fun buildServiceNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, serviceChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val showingOverlay = overlayVisible

        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                if (showingOverlay) AppStrings.s(R.string.svc_notif_overlay_title) else AppStrings.s(R.string.svc_notif_bg_title)
            )
            .setContentText(
                if (showingOverlay) {
                    AppStrings.s(R.string.svc_notif_overlay_text)
                } else {
                    AppStrings.s(R.string.svc_notif_bg_text)
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    /**
     * 置顶权限巡检：确认"设置还开着，但权限已经被收回"。
     *
     * 为什么需要它：用户在系统设置里关掉"显示在其他应用上层"、或在无障碍设置里关掉本应用的
     * 服务时，系统会把窗口撤掉、但**不会通知应用**；如果进程还活着，
     * 设置里仍是 true，用户下次启动就会看到"置顶明明开着，却没有窗口"。
     *
     * 和旧版的区别：旧版只在浮窗挂着的期间巡检（窗收了就停），
     * 但"设置说开着"这件事与"窗挂没挂"无关——用户可能还没开窗就把权限撤了；
     * 所以改成服务活着的期间一直巡检（2 秒一次，查询很轻：一次权限调用 + 一次无障碍状态）。
     *
     * 检查顺序（先收无障碍，再判置顶），与 SettingsStore.applyRuntimeConstraints 保持一致：
     * 1. 无障碍置顶窗口开着，但无障碍权限没了 -> 关掉那条通路；
     *    置顶有悬浮窗权限就继续（窗口换成悬浮窗），没有才连置顶一起关（见 onA11yPermissionLost）；
     * 2. 置顶还开着，但既没有悬浮窗权限、也不能走无障碍豁免 -> 关置顶（见 onOverlayPermissionLost）。
     */
    private val permissionWatchdogHandler = Handler(Looper.getMainLooper())

    private val permissionWatchdog = object : Runnable {
        override fun run() {
            enforceTopWindowPermissions("巡检")

            permissionWatchdogHandler.postDelayed(this, TOP_WINDOW_PERMISSION_CHECK_MS)
        }
    }

    /** 开始巡检（重复调用不会叠加多个定时任务）。 */
    private fun startPermissionWatchdog() {
        permissionWatchdogHandler.removeCallbacks(permissionWatchdog)

        permissionWatchdogHandler.postDelayed(permissionWatchdog, TOP_WINDOW_PERMISSION_CHECK_MS)
    }

    /** 停止巡检（只在服务销毁时调；收窗不再停，否则无障碍那条通路就没人盯了）。 */
    private fun stopPermissionWatchdog() {
        permissionWatchdogHandler.removeCallbacks(permissionWatchdog)
    }

    /**
     * 按当前权限把"置顶"相关状态拉回一致。返回是否改动过（只用于日志）。
     *
     * 判据只有两条，顺序不能换：先判无障碍（它可能让置顶失效），再判悬浮窗（要考虑无障碍豁免）。
     */
    private fun enforceTopWindowPermissions(reason: String): Boolean {
        val values = settingsSnapshot

        // 1. 无障碍权限被收回：选项失去意义，连同置顶一起关（这一步可能已经把置顶关掉）
        if (values.a11yTopWindow && !hasAccessibilityPermission()) {
            onA11yPermissionLost(reason)

            return true
        }

        // 2. 置顶还在，但权限条件不满足了：没有悬浮窗权限，且不能靠无障碍豁免
        if (values.windowAlwaysOnTop && !hasOverlayPermission() && !a11yTopWindowUsable(values)) {
            onOverlayPermissionLost(reason)

            return true
        }

        return false
    }

    /**
     * 无障碍这条通路能不能用来建顶层窗口 = 选项开着 **且** 权限真的在。
     *
     * 两个条件缺一不可：只有权限、选项关着，说明用户要的是悬浮窗通路（仍然要求悬浮窗权限）；
     * 只有选项、权限被撤，通路根本建不出窗。
     *
     * 这个判据同时是"用哪种窗口"的开关：为 true 时 [showOverlay] 建 TYPE_ACCESSIBILITY_OVERLAY
     * 窗口（由无障碍服务自己 addView，层级高于普通悬浮窗、不需要悬浮窗权限）。
     */
    private fun a11yTopWindowUsable(values: SettingsValues): Boolean =
        values.a11yTopWindow && A11yTopWindow.isEnabled(this)

    /**
     * 无障碍权限被收回：只关「使用无障碍权限置顶」这条通路。
     *
     * 置顶本身要不要跟着关，取决于它还有没有别的成立条件：
     * - 有悬浮窗权限 -> 置顶继续有效，窗口当场换成悬浮窗那只（页不动、不回普通界面）。
     *   这跟"用户自己把这条选项关掉"是同一种结果，不该两种入口走成两种样子；
     * - 没有悬浮窗权限 -> 置顶失去依据，跟选项一起关掉，收窗后按老规矩回普通界面。
     *
     * 只有后一种情况才写 LAST_PAGE（那才算一次真正的退出）；回退到悬浮窗时不写，
     * 窗口还在跑、页也没变，留一条"下次回这页"的便条没有意义。
     *
     * @param reason 谁发现的（巡检 / 无障碍服务解绑），只用于日志
     */
    private fun onA11yPermissionLost(reason: String) {
        val wasShowing = isTopWindowShowing()
        val page = overlayPageState.value

        // 先关掉这条通路。存储层的 applyRuntimeConstraints 会把"没有悬浮窗权限
        // 时置顶也失效"一并处理，所以下面以改完的快照为准。
        settingsSnapshot = SettingsStore.update(this, SettingKey.A11Y_TOP_WINDOW, false)

        val keepTopWindow = settingsSnapshot.windowAlwaysOnTop && hasOverlayPermission()

        if (keepTopWindow) {
            Log.w(TAG, "无障碍权限已不在（$reason）：关闭无障碍通路，窗口回退到悬浮窗")

            hideA11yWindow()

            if (wasShowing) {
                overlayVisible = false

                showOverlay(page.ifBlank { settingsSnapshot.defaultStartPage })
            } else {
                refreshServiceNotification()
            }

            return
        }

        Log.w(TAG, "无障碍权限已不在（$reason）：关闭「使用无障碍权限置顶」与「置于顶层」")

        if (wasShowing) {
            SettingsStore.update(this, SettingKey.LAST_PAGE, page)
        }

        hideOverlay()

        settingsSnapshot = SettingsStore.update(this, SettingKey.WINDOW_ALWAYS_ON_TOP, false)
    }

    /**
     * [OVERLAY-v1] 运行中悬浮窗权限被收回：把界面和设置一起退回"不置顶"。
     *
     * 做四件事，顺序有讲究：
     * 1. 记下浮窗当前页（一次性记录），下次普通启动直接回到这一页，用户不会觉得"跳到别处"；
     * 2. 收窗 + 退出前台（窗口已经没资格存在了）；
     * 3. 把「置于顶层」写回 false 并同步内存快照 —— 这是用户报告的那个 bug 的根因，
     *    必须落盘，否则下次启动又会"值 true 但起不来浮窗"；
     * 4. **不主动拉起 Activity**：用户此刻正在系统设置页里关权限，
     *    这时候弹一个全屏界面盖上去非常唐突；他下一次点应用图标时自然就是普通界面。
     *
     * @param reason 谁发现的（巡检 / 建窗失败），只用于日志
     */
    private fun onOverlayPermissionLost(reason: String) {
        Log.w(TAG, "悬浮窗权限已不在（$reason）：收窗并关闭「置于顶层」")

        SettingsStore.update(this, SettingKey.LAST_PAGE, overlayPageState.value)

        hideOverlay()

        settingsSnapshot = SettingsStore.update(this, SettingKey.WINDOW_ALWAYS_ON_TOP, false)
    }

    /**
     * 浮窗里点"退出"：收浮窗 -> 把"置于顶层"改回关 -> 拉起正常界面。
     *
     * 不再引导用户去关权限：只要设置里不置顶，下次启动就是普通界面。
     * 从 Service 启动 Activity 在 Android 10+ 通常受限，
     * 但持有 SYSTEM_ALERT_WINDOW 的应用属于官方豁免场景。
     */
    private fun exitOverlayMode() {
        // [OVERLAY-v1] 退出前先记下浮窗停在哪一页：回普通界面时直接回到这一页，
        // 忽略一次默认起始页（一次性记录，MainActivity 启动时消费并清空）
        SettingsStore.update(this, SettingKey.LAST_PAGE, overlayPageState.value)

        hideOverlay()

        SettingsStore.update(this, SettingKey.WINDOW_ALWAYS_ON_TOP, false)

        settingsSnapshot = SettingsStore.load(this).values

        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * [OVERLAY-v1] 软重启：让"置于顶层"这类需要重新初始化界面的改动立即生效。
     *
     * 为什么不再杀进程（两版都试过，都不行）：
     * 1. "自己 startActivity 再杀自己"：Activity 与进程同生共死，新界面还没起来就被带走；
     * 2. "另开 :restart 中转进程"：本机（HyperOS / Android 15）实测该新进程在初始化阶段
     *    就原生崩溃（SIGSEGV，崩溃栈落在 ART 的 DDM/JDWP 线程），主进程已死、新进程又没起来
     *    → 屏幕上就只剩一个没有窗口的任务，也就是"一直黑屏"；
     * 3. 而"从浮窗退出"这条路（服务里 startActivity 回 MainActivity）一直是稳的。
     *
     * 所以重启复用第 3 条：先收掉浮窗（运行时状态清干净），再用 NEW_TASK | CLEAR_TASK
     * 重新拉起 MainActivity。新实例的 onCreate 会重新读一遍文件里的设置，
     * 读到"置于顶层"就再交给本服务重建浮窗——观感与重启一致，且不依赖新进程。
     */
    private fun restartRuntime() {
        Log.i(TAG, "restartRuntime: 重建界面（不杀进程）")

        hideOverlay()

        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }.onFailure {
            Log.e(TAG, "restartRuntime: 拉起 MainActivity 失败", it)
        }
    }

    // ============================================================
    // ⑧ 对外接口实现
    // ============================================================

    // ---------- IUIServiceApi：界面侧通用能力 ----------

    /**
     * 提交用户反馈（设置页"发送反馈"按钮的落地入口）。
     *
     * TODO(预留): 接入实际上报通道；当前未实现，统一返回 false。
     */
    override fun submitFeedback(content: String): Boolean {
        return false
    }

    /**
     * 启动定时刷新（进程页 / 性能页 / 详细信息页共用同一调度，避免每页各自计时）。
     *
     * 间隔从设置"实时更新速度"来（见 [applySettingsToRuntime]）；"已暂停"传 0，定时器就停。
     *
     * 重复调用不会叠加出多个计时器：每次都先 removeCallbacks 再重新排。
     * 立刻先跑一次，避免"刚切到这一页还要干等一个周期才看到数据"。
     */
    override fun startPolling(intervalMs: Long) {

        pollingIntervalMs = intervalMs

        mainHandler.removeCallbacks(pollTick)

        if (intervalMs > 0L) {
            mainHandler.post(pollTick)
        }
    }

    /** 停止定时刷新。 */
    override fun stopPolling() {

        pollingIntervalMs = 0L

        mainHandler.removeCallbacks(pollTick)
    }

    /**
     * 上报当前前台页。页切走时立刻重排一次：新页的第一次刷新不用等旧周期走完。
     *
     * 顺便把 Linux 需求跟着页一起搬：进详细信息页就登记"它要显示的列"，
     * 离开就撤销——没人在看那一页时，后台不该继续为它采一份快照。
     */
    override fun setActivePage(page: String) {

        val next = page.ifBlank { "进程" }

        if (next == activePageName) return

        activePageName = next

        // 手动模式（忽略实时更新速度）下不登记需求：这一页的采集由「刷新」按钮单独发起，
        // 进了全局并集只会让别的页刷新时顺手把它那几列也采一遍。
        if (next == PAGE_DETAIL && !detailIgnoreUpdateSpeed) {
            linuxDemands[PAGE_DETAIL] = detailDemandMask()
        } else {
            linuxDemands.remove(PAGE_DETAIL)
        }

        // 切到性能页：名称缓存只在这一下清（用户刚看这一页，名字值得重探一次）；
        // 当前帧在性能页管理器里一直留着，切回来直接就有，不必这里再推一次。
        if (next == PAGE_PERFORMANCE) {
            LinuxService.instanceOrNull()?.requestCpuNameRefreshOnce()
        }

        reschedulePolling()
    }

    /**
     * 上报主界面是否在前台（MainActivity 的 onResume / onPause）。
     *
     * 回到前台时立刻补一次刷新：后台期间被门禁挡掉的采集不会自己补上，
     * 否则用户切回来要盯着一张空表等到下一个周期。
     *
     * 方法名带 App：**不能**叫 setForeground，那个同名方法在 android.app.Service 里是 final，
     * 重写它会让本类在加载时报 LinkageError（应用一启动就崩）。
     */
    override fun setAppForeground(foreground: Boolean) {

        if (foreground == appForeground) return

        appForeground = foreground

        if (foreground) reschedulePolling()
    }

    /**
     * 门禁条件变了（切页 / 前后台 / 浮窗显隐）：立刻重排一次定时器，别让旧周期继续按旧条件走。
     *
     * "已暂停"（间隔 0）时什么都不做：定时器本来就该是停的。
     */
    private fun reschedulePolling() {

        val interval = pollingIntervalMs

        if (interval <= 0L) return

        mainHandler.removeCallbacks(pollTick)

        mainHandler.post(pollTick)
    }

    /**
     * 现在允许驱动页面内容吗？
     *
     * 条件是"主界面在前台 **或** 浮窗正显示"，两个都不满足就没人看得见这份数据。
     * 页面自己轮询时也用它做门禁（不在前台就只 delay、不读、不重组）。
     *
     * 前台判据有两路，任一成立即算：
     * - [MainActivity.isResumed]：Activity 自己维护的可见状态（**拉**，不依赖上报时机）；
     * - [appForeground]：Activity 上报过来的值（历史上就在用，保留以不变语义）。
     * 加上"浮窗可见"就是全部条件。首启时上报常常赶不上 Service 创建，
     * 所以不能只信 [appForeground]。
     */
    fun isUiRefreshAllowed(): Boolean = appForeground || overlayVisible || MainActivity.isResumed

    /**
     * 刷新的心跳。
     *
     * 心跳只负责"到点了、派一次活"，**绝不在主线程等应答**：
     * 一次快照要遍历整台机器的 /proc，几百个进程读下来是几十到上百毫秒，
     * 在主线程同步发请求会直接卡住界面。
     *
     * 于是真正的采集搬到 [linuxPollExecutor]，且上一轮没采完就跳过本轮：
     * 请求不堆叠，迟到的旧快照也不会再铺回表上。
     */
    private val pollTick: Runnable = object : Runnable {

        override fun run() {

            mainHandler.removeCallbacks(this)

            val interval = pollingIntervalMs

            // 已暂停（0）：定时器彻底停，不重排
            if (interval <= 0L) return

            mainHandler.postDelayed(this, interval)

            launchEngineRefresh()
        }
    }

    /**
     * 采集线程（单线程）。
     *
     * 为什么单线程就够：快照本来就是"一轮一轮"的，多线程只会互相抢 /proc、
     * 还会让两个不同轮次的数据混进同一张表。
     */
    private val linuxPollExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ui-linux-poll").apply { isDaemon = true }
        }

    /**
     * 上一轮采集还在路上吗（true = 本轮直接跳过，避免请求排队堆积）。
     *
     * 用原子布尔而不是 @Volatile 普通字段："查一下再占位"必须是一步，
     * 否则定时节拍与「刷新」按钮可能同时通过检查，两个采集排进同一个线程。
     */
    private val engineRefreshRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 把一次刷新派给采集线程；重入直接丢弃。 */
    private fun launchEngineRefresh() {

        if (!engineRefreshRunning.compareAndSet(false, true)) return

        linuxPollExecutor.execute {
            try {
                runCatching { refreshActivePage() }
                    .onFailure { Log.w(TAG, "定时刷新失败：${it.message}") }
            } finally {
                engineRefreshRunning.set(false)
            }
        }
    }

    /**
     * 定时驱动一次。分两条通道，门禁不同：
     *
     * 1. **性能页的后台统计**（[refreshPerformanceStatsIfEnabled]）：不受前台/选中约束。
     *    曲线统计必须连续采样，切页、退到后台都不能断——断了切回来就少了那一段历史。
     *
     * 2. **其它页的界面内容**：必须同时满足
     *    - 主界面在前台，或浮窗正在显示（[isUiRefreshAllowed]）；
     *    - 而且它正是被选中的那一页（[activePageName]）。
     *    两条缺一条就不采、不写表：看不见的页刷了也是白烧电。
     */
    private fun refreshActivePage() {

        // ① 性能页统计：豁免前台与被选中这两个条件（设计未落地，先留通道）
        refreshPerformanceStatsIfEnabled()

        // ② 前台页的界面内容：后台/未显示时一概不碰
        if (!isUiRefreshAllowed()) return

        if (effectiveActivePage() != PAGE_DETAIL) return

        // "忽略实时更新速度"开着 = 页面只在该刷的时候刷，定时器不再采。
        // 这正是省性能的地方：开着详细信息页不点刷新，就一个包也不发。
        if (detailIgnoreUpdateSpeed) {

            // 同时把它从"全局需求并集"里摘掉：否则别的页刷新时并集会带上这几列，
            // 后端仍要多读那些文件——手动模式下这部分开销纯属白花。
            linuxDemands.remove(PAGE_DETAIL)

            return
        }

        // 每轮按"当前可见列"重算需求：勾/取消一列，下一轮就只采需要的那些。
        // 这里直接写 map 而不走 publishLinuxDemand()，是为了不每 tick 打一条 Log.i。
        linuxDemands[PAGE_DETAIL] = detailDemandMask()

        val packet = refreshLinuxSnapshot()

        if (packet == null || !packet.isOk) return

        synchronized(detailLock) {

            // 表里有手工填的行（调试页的 Test0~Test10）时，自动快照不覆盖它
            if (detailRows.isNotEmpty() && !detailRowsFromSnapshot) return@synchronized

            detailRowsFromSnapshot = true

            replaceProcessRows(linuxSnapshotToRows(packet))
        }
    }

    /**
     * 现在真正要看的是哪一页。
     *
     * 主界面在前台时以 [MainActivity.currentPage] 为准：那次 `setActivePage()` 很可能
     * 发生在服务实例创建之前（首次启动尤其如此），只信上报会把页名永久停在"进程"。
     * 主界面不在前台就退回最近一次上报的 [activePageName]——浮窗就是这种情形，
     * 它建窗和切页签时都会上报。
     */
    private fun effectiveActivePage(): String {

        if (MainActivity.isResumed) {

            val page = MainActivity.currentPage

            if (page.isNotBlank() && page != activePageName) {
                activePageName = page

                Log.i(TAG, "前台页以 Activity 为准：$page（上报值可能已丢）")
            }
        }

        return activePageName
    }

    /**
     * 详细信息页这一轮要采的掩码 = 它当前要显示的列（隐藏的列不采）。
     *
     * 两列**无论是否勾选都要采**：
     * - 名称列：它是每一行的标题（[ProcessRow.name]），用户没勾也得有，
     *   否则行标题只能退化成 "pid 123"；同时它也是"同 uid 多包"时定包的依据
     *   （后端这列取的是 cmdline 的 argv[0]，即完整进程名）；
     * - cmdline 列：只是给用户看的完整命令行，不参与定包，可以不采。
     *
     * 将来这个页如果新增"只有选中进程才需要的列"，在这里并上即可，其它地方不用动。
     */
    private fun detailDemandMask(): Long {

        val visible = detailColumns
            .filter { it.id in detailVisibleIds }
            .map { it.id }

        return LinuxField.maskOf(visible) or LinuxField.maskOf(listOf(DetailColumnId.NAME))
    }

    /**
     * 性能页的后台采集：采一帧设备快照（当前只有 CPU 那一组）。
     *
     * 采样通道的规矩，三条别混：
     * - **采**（这里）：默认不受前台、不受是否选中约束。性能页要的是一段连续的历史，
     *   切页、退到后台都不能断，断了切回来就少一段。这条默认行为由设置项
     *   「后台统计」（[SettingsValues.backgroundStats]）控制，默认开着。
     * - **后台统计关掉**：看不见界面时这一轮什么都不做——不取快照、不发请求，
     *   省掉 CPU 与电。"看得见"= 普通界面在前台，或顶层窗口（悬浮窗 / 无障碍窗口）正在显示，
     *   判据就是 [isUiRefreshAllowed]；用户主动点「刷新」（force）不受这条限制。
     * - **画**（[pushPerformanceSnapshot]）：只看页面可见不可见。看不见就不写可观察状态，
     *   后台每秒让一个没人在看的组合树重组一次纯属白烧电。
     *
     * "已暂停"（间隔 0）时一次都不采：暂停就是连后台也别动，别偷偷发请求。
     */
    private fun refreshPerformanceStatsIfEnabled(force: Boolean = false) {

        if (pollIntervalMs(settingsSnapshot.updateSpeed) <= 0L) return

        // 后台统计关掉 + 现在没人看得见 -> 不采、不发请求（force 是用户主动刷新，放行）
        if (!force && !settingsSnapshot.backgroundStats && !isUiRefreshAllowed()) return

        val linux = LinuxService.instanceOrNull() ?: return

        val snapshot = linux.deviceSnapshot(linuxRequestTimeoutMs) ?: return

        // Android 层的 SYSTEM 数据并进这一帧：AVF 支持只有 Framework 知道，
        // Linux 那一份 SYSTEM 设备里没有这一格。取不到就保持 null —— 页面整行不出现，
        // 不会显示成"AVF 支持：否"。
        val merged = mergeAndroidSystem(snapshot)

        // 采样时刻在这里取：ELF 那边只回"当前值"，时间轴由本服务统一排，
        // 不依赖跨进程的时钟，也不受一次往返耗时的抖动影响。
        // CPU 与内存两条曲线拿到的是同一个时刻，横轴天然对齐。
        val sampleAt = SystemClock.elapsedRealtime()

        // 交给订阅者（性能页管理器）：入历史、写页面状态都是页面私有的事，
        // 本服务到此为止，不再持有任何"图表要画什么"的数据。
        notifyPerformanceFrame(merged, sampleAt)
    }

    /**
     * 把 Android 侧的 SYSTEM 设备字段并进设备快照帧。
     *
     * 为什么合并在 UIService：它是唯一聚合点（Linux / Android / 未来的 GPU 都在这里汇合），
     * 页面只该看到"填好的那一帧"，不该自己再去问一遍服务。
     *
     * 现在只有 AVF 两项（组件是否存在、支持与否）。AndroidService 没起来时原样返回，
     * 帧里 avfComponentPresent 保持 false，页面据此判断"整行不显示"。
     */
    private fun mergeAndroidSystem(snapshot: PerformanceSnapshot): PerformanceSnapshot {

        val facts = AndroidService.instanceOrNull()?.systemFacts() ?: return snapshot

        return snapshot.copy(
            avfComponentPresent = facts.avfComponentPresent,
            avfSupported = facts.avfSupported
        )
    }

    /**
     * 实时更新速度 -> 图表窗口状态码（**给人看的句子不在这里拼**）。
     *
     *   高 = 30 / 常规 = 60 / 低 = 240（秒）；暂停 = PAUSED。
     *
     * 为什么只发码：这一行要跟着系统语言走，句子必须在 UI 层用 R.string 拼；
     * 服务层只说明"这一档覆盖多少秒"，界面据此取「60 秒内的利用率 %」或短标签「60 秒」。
     *
     * 注意 60 秒这一档**不折成 1 分钟**：秒/分钟的写法是按人眼读起来最顺来的，
     * 不是按数学换算来的，所以 UI 侧用固定资源，而不是从秒数算着拼。
     */
    internal fun performanceChartWindowLabel(speed: String): String = when (speed) {
        "已暂停" -> "PAUSED"
        "高" -> "30"
        "低" -> "240"
        else -> "60"
    }

    override fun performanceChartWindowLabel(): String =
        performanceChartWindowLabel(settingsSnapshot.updateSpeed)

    /**
     * 当前更新速度对应的采集间隔（毫秒）；0 = 已暂停。
     *
     * 给性能页判断"这一帧是不是刚采过"用：进页面（含双击进预览重建子树）时本来会补采
     * 一帧，但如果距上一帧还不足一个间隔，这一帧会和上一帧几乎同时刻，看上去像加速了一拍。
     */
    internal fun performancePollIntervalMs(): Long = pollIntervalMs(settingsSnapshot.updateSpeed)

    override fun refreshPerformanceNow(): Boolean {

        // 已暂停：用户要求不自动更新，这里也别偷偷采一帧
        if (pollIntervalMs(settingsSnapshot.updateSpeed) <= 0L) return false

        val linux = LinuxService.instanceOrNull() ?: return false

        // 这是"用户刚进到本页"的入口，唯一值得重算 CPU 名称的时机；
        // 只清名称缓存，不改变其它字段的刷新承诺（它们仍按更新速度走）。
        linux.requestCpuNameRefreshOnce()

        // 已经有一轮在跑：那一轮结束时自然会把最新帧推上来，这里不排队
        if (!engineRefreshRunning.compareAndSet(false, true)) return true

        linuxPollExecutor.execute {
            try {
                // force = true：用户此刻就在这一页，不必再等可见性判定成立
                refreshPerformanceStatsIfEnabled(force = true)
            } catch (e: Exception) {
                Log.w(TAG, "性能页立即刷新失败：${e.message}")
            } finally {
                engineRefreshRunning.set(false)
            }
        }

        return true
    }

    /**
     * 运行时切换主题：把设置里的主题模式写进进程级共享状态，两处界面各自重组。
     *
     * 为什么服务里能做这件事：主题不是"某个页面的数据"，而是全局外观；
     * 主界面（MainActivity 的 Compose 内容）与浮窗（本服务里的 ComposeView）在同一个进程，
     * 共享同一份 Compose 状态（[ThemeState]），写一次两边都变色，不需要重启应用。
     *
     * 真正的"主题 -> 颜色"映射在 ui/Theme.kt（TaskManagerTheme）里：
     * "使用系统设置"跟随系统深浅色并优先动态取色，设备不支持动态取色时静默回落项目默认色板。
     *
     * 非法值不报错：收敛成"使用系统设置"（纯外观项，没必要为它弹提示）。
     */
    override fun applyTheme(theme: String) {
        ThemeState.mode.value = normalizeThemeMode(theme)
    }

    // ---------- IUserCredentialApi：用户凭据（身份 + 两条提权通道） ----------
    //
    // 这一组接口是"双向实时"的：设置页报选择，本服务判可用性并回推状态。
    // 身份只存在内存里（credentialIdentity），不写盘，理由见字段上的注释。

    /**
     * 取当前凭据快照，并把同一份状态推给界面。
     *
     * 每次都重新读真实授权状态（不返回缓存）：授权可能随时变化，
     * 缓存旧值会让设置页的按钮一直停在"请求权限"。
     */
    override fun credentialSnapshot(): UserCredentialSnapshot {

        // 读缓存值而不是在这里调 refreshStatus：
        // 这个接口被设置页按 400ms 轮询，跑在主线程，而 refreshStatus 里是三次跨进程 binder 调用，
        // 服务端一卡就会连累界面。缓存由 ShizukuManager 的 Binder 监听器维护 ——
        // 用户从 Shizuku 里启动完服务回来时，Sticky 的 OnBinderReceivedListener 会立刻刷新它。
        val shizuku = runCatching { ShizukuManager.status.value }.getOrNull()

        val shizukuGranted = shizuku?.isAuthorized == true

        // Shizuku 以 root 身份运行时，它给的就是 uid 0 的进程身份
        val shizukuRoot = shizukuGranted && shizuku?.isRoot == true

        // Root 能力：真 Root（su），或 Shizuku 本身就是 root
        val rootGranted = runCatching { RootManager.isAvailable }.getOrDefault(false) || shizukuRoot

        // Shell(adb) 只有在"Shizuku 已授权且服务端是 uid 2000"时才成立；
        // Shizuku 跑成 root 时它给不出 shell 身份，这个选项要按未授权处理
        val shellAvailable = shizukuGranted && !shizukuRoot

        // 装没装 / 服务在不在跑：决定设置页按钮是"请求权限"、"打开 Shizuku"还是提示未安装
        val shizukuInstalled = shizuku?.installed == true

        val shizukuRunning = shizuku?.isRunning == true

        // 启动自动请求走完之后才做降级判断：授权框还挂着的时候不能把恢复的身份抹掉
        if (autoRequestDone) {
            downgradeIdentityIfUnavailable(
                UserCredentialSnapshot(
                    identity = credentialIdentity,
                    shizukuGranted = shizukuGranted,
                    shizukuRoot = shizukuRoot,
                    rootGranted = rootGranted,
                    shellAvailable = shellAvailable,
                    shizukuInstalled = shizukuInstalled,
                    shizukuRunning = shizukuRunning
                )
            )
        }

        val snapshot = UserCredentialSnapshot(
            identity = credentialIdentity,
            shizukuGranted = shizukuGranted,
            shizukuRoot = shizukuRoot,
            rootGranted = rootGranted,
            shellAvailable = shellAvailable,
            shizukuInstalled = shizukuInstalled,
            shizukuRunning = shizukuRunning
        )

        UserCredentialState.apply(snapshot)

        // 身份 / 授权痕迹有变化就写进存档（下次启动据此恢复现场）
        persistCredentials(snapshot)

        return snapshot
    }

    /**
     * 判定"用户选的身份"能不能用，并能用就记成本次运行的身份。
     *
     * 规则：
     * - 普通权限：恒可用；
     * - Shell(adb)：要 Shizuku 已授权；
     * - root：要具备 Root（真 Root，或 Shizuku 以 root 身份运行）；
     * - 其它值：参数非法。
     *
     * 回调是同步的（状态都是本地读），但接口允许异步：
     * 将来若改成"先弹授权、再判定"，设置页那边不用改。
     */
    override fun onUserIdentitySelected(
        identity: String,
        onDecision: (CredentialDecision) -> Unit
    ) {
        if (identity !in SettingsCatalog.CREDENTIAL_IDENTITIES) {
            onDecision(CredentialDecision.INVALID)

            return
        }

        val snapshot = credentialSnapshot()

        val decision = when (identity) {
            SettingsCatalog.CREDENTIAL_NORMAL -> CredentialDecision.ALLOWED

            // Shell(adb) 要的是 uid 2000：Shizuku 已授权但跑在 root 上时给不出来，
            // 这种情况按"未授权"处理（界面会弹框并保留原选择），与用户要求一致
            SettingsCatalog.CREDENTIAL_SHELL -> when {
                snapshot.shellAvailable -> CredentialDecision.ALLOWED

                snapshot.shizukuGranted -> CredentialDecision.SHELL_UNAVAILABLE

                else -> CredentialDecision.MISSING_SHIZUKU
            }

            SettingsCatalog.CREDENTIAL_ROOT -> if (snapshot.rootGranted) {
                CredentialDecision.ALLOWED
            } else {
                CredentialDecision.MISSING_ROOT
            }

            else -> CredentialDecision.INVALID
        }

        if (decision == CredentialDecision.ALLOWED) {

            // 记下切换前的"优先通路"，用来判断要不要重建上游（进程身份在启动那一刻就定了）
            val previousMode = upstreamIdentityMode()

            credentialIdentity = identity

            UserCredentialState.identity.value = identity

            // 身份是"用户的选择"，立刻落盘：下次启动先按它恢复
            persistCredentials(snapshot.copy(identity = identity), force = true)

            // 身份换了通路（比如 shell -> root）：已经在跑的上游仍是旧身份，按新身份重建一遍
            if (upstreamStarted && previousMode != upstreamIdentityMode()) {

                Log.i(TAG, "用户身份切换为「$identity」：按新身份重建上游服务")

                stopUpstreamServices()
                startUpstreamServices()
            }
        }

        onDecision(decision)
    }

    /**
     * 用户点了 Shizuku 一栏的"请求权限"：立刻发起一次真实授权。
     *
     * 流程：
     * 1) ShizukuManager.requestPermission 把请求交给 Shizuku（弹窗由它自己画）；
     * 2) 拿到权限（GRANTED / ALREADY_GRANTED）-> 刷新快照 + 以 shell 身份重建上游服务；
     * 3) 没拿到 -> 只刷新快照，并告诉界面"失败"，设置页会弹共享的失败框。
     *
     * 线程：Shizuku 的回调在 Binder 线程，这里统一切回主线程再回调界面
     * （Compose 状态只能在主线程改）。
     */
    override fun requestShizukuPermission(onResult: (PermissionRequestResult) -> Unit) {

        // 已经授权过就不必再弹一次窗，直接按"本来就有"处理
        if (runCatching { ShizukuManager.status.value.isAuthorized }.getOrDefault(false)) {

            credentialSnapshot()

            mainHandler.post { onResult(PermissionRequestResult.ALREADY_GRANTED) }

            return
        }

        val immediate = try {

            ShizukuManager.requestPermission { result ->

                when (result) {
                    ShizukuPermissionResult.GRANTED,
                    ShizukuPermissionResult.ALREADY_GRANTED -> onShizukuGranted(onResult)

                    else -> onPermissionDenied(onResult, "Shizuku 授权被拒绝 / 未完成（$result）")
                }
            }

        } catch (e: Throwable) {

            Log.i(TAG, "requestShizukuPermission 抛异常：${e.javaClass.simpleName}")

            onPermissionDenied(onResult, "Shizuku 授权请求异常：${e.javaClass.simpleName}")

            return
        }

        // REQUESTED = 请求已提交，结果等回调；GRANTED / ALREADY_GRANTED 时回调已同步跑过，
        // 这两类都不能再调一次 onResult，否则界面会收到两次结果
        when (immediate) {

            ShizukuPermissionResult.REQUESTED,
            ShizukuPermissionResult.GRANTED,
            ShizukuPermissionResult.ALREADY_GRANTED -> Unit

            else -> onPermissionDenied(onResult, "Shizuku 不可用（$immediate）")
        }
    }

    /**
     * 打开 Shizuku 管理器应用，让用户启动服务后再回来授权。
     *
     * 我们无法替 Shizuku 启动服务端进程，所以这里只负责"把门打开"：
     * 成功后界面会提示"已在 Shizuku 里启动服务，回来再点请求权限"。
     */
    override fun openShizukuManager(): Boolean {

        val started = runCatching {
            ShizukuManager.launchManagerApp(this)
        }.getOrDefault(false)

        Log.i(TAG, "打开 Shizuku 管理器：$started")

        return started
    }

    /** 用户点了 Root 一栏的"请求权限"：后台探测 su，拿到就把按钮变灰。 */
    override fun requestRootPermission(onResult: (PermissionRequestResult) -> Unit) {

        try {

            RootManager.requestRoot { granted ->

                credentialSnapshot()

                if (granted) {

                    Log.i(TAG, "Root 已可用：重建上游服务，Linux 后端改以 root 身份运行")

                    // 拿到 root 就把两条通道换到提权身份（Shizuku 优先，其次 su）
                    mainHandler.post {
                        restartUpstreamPrivileged()

                        onResult(PermissionRequestResult.GRANTED)
                    }
                } else {

                    onPermissionDenied(onResult, "Root 探测失败（设备无 su 或用户未授权）")
                }
            }

        } catch (e: Throwable) {

            onPermissionDenied(onResult, "Root 探测异常：${e.javaClass.simpleName}")
        }
    }

    /** Shizuku 拿到权限后的统一收尾：刷新快照 -> 上游换提权身份 -> 通知界面。 */
    private fun onShizukuGranted(onResult: (PermissionRequestResult) -> Unit) {

        mainHandler.post {

            credentialSnapshot()

            restartUpstreamPrivileged()

            onResult(PermissionRequestResult.GRANTED)
        }
    }

    /** 任何一条提权通道失败的统一收尾：刷新快照（按钮状态要跟着变）-> 通知界面失败。 */
    private fun onPermissionDenied(
        onResult: (PermissionRequestResult) -> Unit,
        reason: String
    ) {

        Log.i(TAG, reason)

        mainHandler.post {

            credentialSnapshot()

            onResult(PermissionRequestResult.DENIED)
        }
    }

    // ---------- IUIServiceApi：设置中心 ----------

    /** 设置是否已加载完成（true 时 settings() 一定返回校验过的值）。 */
    override val isSettingsReady: Boolean
        get() = settingsLoaded

    /** 当前设置快照；未 ready 时返回工厂默认值，不会返回 null。 */
    override fun settings(): SettingsValues = settingsSnapshot

    /**
     * 按 key 读单项当前值。
     *
     * 用 [SettingsApi.toMap] 取值，保证"当前值"和"持久化键名"始终同一来源。
     */
    override fun settingOf(key: SettingKey): Any {
        val values = settingsSnapshot

        return SettingsApi.toMap(values)[key.storageKey] ?: SettingsApi.defaultValueOf(key)
    }

    /** 已持久化的默认起始页名称（MainActivity 用它决定首屏）。 */
    override fun startPage(): String = settingsSnapshot.defaultStartPage

    // ---------- IUIServiceApi：Linux 后端（ELF）通道转发 ----------
    //
    // 进程归 LinuxService 持有，这里只做转发：
    //   - 页面永远看不到 Process，也就不会出现"两个地方各起一个后端"；
    //   - 服务没起来时统一返回 false / 状态文案，页面据此提示用户，而不是自己补救。
    // 页面数据区要解析后端输出时，也走这里取原始文本（见 ⑥ 分区）。

    /** 启动 ELF 后端（幂等）：成功返回 true，LinuxService 没起来时返回 false。 */
    override fun startLinuxBackend(): Boolean {
        val linux = LinuxService.instanceOrNull() ?: return false

        return linux.startBackend()
    }

    /** 停止 ELF 后端（不改设置，下次用到再起）。 */
    override fun stopLinuxBackend() {
        LinuxService.instanceOrNull()?.stopBackend()
    }

    /**
     * 给后端发一次 v2 心跳，确认通道还通。
     *
     * 通道上只有 REQ/PKG；放开"自由命令"就等于允许绕开 seq 配对往管道里塞行，
     * 应答归属立刻不可控。
     */
    override fun pingLinuxSvc(): Boolean {
        val linux = LinuxService.instanceOrNull() ?: return false

        return linux.ping(linuxRequestTimeoutMs)
    }

    /** 后端最近输出；未拉起时给出明确状态文案（不返回 null，页面不用判空）。 */
    override fun linuxBackendOutput(): String {
        val linux = LinuxService.instanceOrNull()
            ?: return AppStrings.s(R.string.svc_linux_not_ready_starting)

        return linux.recentOutput()
    }

    /** 后端是否在跑。 */
    override val isLinuxBackendRunning: Boolean
        get() = LinuxService.instanceOrNull()?.isBackendRunning == true

    /**
     * 后端是不是以提权身份在跑（Shizuku 或 root 都算）。
     *
     * 名字沿用 isLinuxShellBackend 是为了不改动已有页面代码；
     * 具体身份见 linuxBackendIdentity()。
     */
    override val isLinuxShellBackend: Boolean
        get() = LinuxService.instanceOrNull()?.isShellBackend == true

    /** 后端身份说明文案；Linux 服务没起来时给"未启动"。 */
    override fun linuxBackendIdentity(): String {
        val linux = LinuxService.instanceOrNull() ?: return AppStrings.s(R.string.svc_not_started)

        return linux.backendIdentity()
    }

    // ============================================================
    // ⑨ Linux 数据面（v2 请求包 + 各页需求合并）
    //
    // 分工（三段各管一件事，别混）：
    //   页面      只声明"我要哪些列"（publishLinuxDemand），不直接发请求；
    //   UIService 取并集（mergedLinuxDemand）-> 一次 GetDataFromLinuxSvc -> 缓存快照；
    //   LinuxService 只管按 mask 采集、按协议把包发回来（协议见 data/LinuxService.kt ① 段）。
    //
    // 为什么必须合并：进程页要名称/CPU，详细信息页要几十列，性能页只要汇总。
    // 各页各发一次，同一份 /proc 会被反复扫；合并成一次请求后，
    // 一轮刷新只采集一次，所有页读同一份快照，行与行之间也不会出现"时间不一致"。
    //
    // 谁在前台才要数据：这套设计不改"要不要请求"，只提供能力；
    // 页面进入时 publish、离场时 publish(NONE)，并集自然只在需要的页存在时非零。
    // 性能页如果按设计常驻，就让它一直登记着——它本来就永远需要数据。
    // ============================================================

    /** 各页对 Linux 字段的需求：页名 -> 掩码（同一页只留最新一条）。 */
    private val linuxDemands = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 最近一次成功取到的快照；同一轮刷新内各页共读，页面不用各自再发请求。 */
    @Volatile
    private var linuxSnapshotCache: LinuxPacket? = null

    /** 缓存时间（SystemClock.elapsedRealtime），只用于调试页展示"多久前取的"。 */
    @Volatile
    private var linuxSnapshotAtMs: Long = 0L

    /** 一次请求等应答的上限：真机一次 /proc 遍历是毫秒级，留 4s 只为容忍"刚好在重启后端"。 */
    private val linuxRequestTimeoutMs = 4_000L

    /**
     * 快照请求的串行闸门（single-flight）。
     *
     * 为什么必须有：进程页与详细信息页可能在同一帧里各要一次快照，
     * 两个请求同时在路上时，先发的那个反而可能后回——**旧快照会覆盖新快照**，
     * 表格就会闪一下过期数据（F-17 / "丢弃过期响应"）。
     * 用一把锁让它们排队：第二个请求等第一个结束再发，拿到的永远是最新的那一轮。
     * 代价是同一时刻只扫一遍 /proc，反而比并发更省。
     */
    private val linuxSnapshotLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * 取一次进程快照（同步等应答，调用方必须在 IO 线程）。
     *
     * mask 传 [LinuxField.NONE] 时退化成"取全部字段"——
     * 这是为了让调试页/临时排查不用手拼 43 位，正常调用请按需给掩码。
     *
     * 只有"完整且成功"的包才进缓存：半包 / 失败包可以返回给调用方排错，
     * 但绝不能被下一轮页面读到（否则表格会少行、或混进上一个包的残行）。
     */
    override fun GetDataFromLinuxSvc(mask: Long): LinuxPacket? {

        val linux = LinuxService.instanceOrNull() ?: return null

        linuxSnapshotLock.lock()

        try {
            // NONE -> ALL 的兜底、以及"范围筛选"都在 LinuxService.snapshot 里，
            // 这里只把"当前详细信息页选的范围"透传下去：筛选发生在后端，包只带筛后的行。
            val packet = linux.snapshot(mask, detailProcessScope, linuxRequestTimeoutMs)

            if (packet?.isOk == true) {
                linuxSnapshotCache = packet
                linuxSnapshotAtMs = SystemClock.elapsedRealtime()
            } else if (packet != null) {
                Log.w(
                    TAG,
                    "Linux 快照未采用：${packet.failureReason ?: "未知原因"}（" +
                        "${packet.summary()}）"
                )
            }

            return packet
        } finally {
            linuxSnapshotLock.unlock()
        }
    }

    /**
     * 把一个 Linux 快照包转成详细信息页的行。
     *
     * 这是"Linux 的 P 行 -> 表行"的唯一转换点：43 列按 [detailColumns] 的 id 一一取值，
     * 名称取 name 列，行身份取 pid + start_ticks。
     *
     * 只做转换、不写表：什么时候把快照铺进表由调用方决定（调试页按钮 / 页面刷新），
     * 免得自动覆盖掉用户手动加入的 Test0 ~ Test10 测试行。
     */
    fun linuxSnapshotToRows(packet: LinuxPacket): List<ProcessRow> {

        // Android 侧字段：一轮一次判"哪些 pid 是 Android 框架进程"，之后只对这批进程取字段。
        // 判定与取数都在 AndroidService 里（shell 身份跑一次命令 + 内存查表），
        // 本服务只负责把结果并进行、把不是 Android 的进程写成"隔离状态：否"。
        val android = AndroidService.instanceOrNull()

        // 表是"全机完整"的才敢断言"不在表里 = 不是 Android 进程"；只有本地可见范围时不下结论
        val androidTableComplete = android?.isPackageTableComplete() == true

        val androidPids: Set<Int> = if (android != null) {

            android.prepareRound(packet.processes.map { processKeyOf(it) })
        } else {
            emptySet()
        }

        return packet.processes.map { record ->

            val values = LinkedHashMap<String, String>(detailColumns.size)

            detailColumns.forEach { column ->
                values[column.id] = record.displayOf(column.id)
            }

            // 僵尸进程：后端只发内核风格的名字 `[comm]`，说明文字在这里补，
            // 位置是"名称"这一列；判据是同一行的 state == Z
            values[DetailColumnId.NAME] = BackendDisplayText.processNameText(
                record.fieldOf(DetailColumnId.NAME),
                record.fieldOf(DetailColumnId.STATE)
            )

            // 行所属应用的包名（原始值）：Android 进程才有，给图标槽 / 搜索用
            var packageName: String? = null

            when {
                // Android 框架进程：补齐五格
                android != null && record.pid in androidPids -> {
                    packageName = enrichWithAndroid(values, record, android)
                }

                // uid 取到了、包表也完整、但不在表里 = Linux 原生应用，这五格本来就没有
                android != null && androidTableComplete && record.uid != null -> {
                    values[DetailColumnId.ISOLATION] = BackendDisplayText.processColumnText(
                        DetailColumnId.ISOLATION,
                        BackendDisplayText.ISOLATION_NO
                    )
                }

                // uid 都没取到 / AndroidService 没起来：不知道，保持 Linux 的"暂无数据"
            }

            ProcessRow(
                pid = record.pid,
                name = record.fieldOf(DetailColumnId.NAME) ?: "pid ${record.pid}",
                values = values,
                // 启动时刻：-1 = 后端没给这个字段（不能拿 0 顶替，0 是合法值：init 的 starttime 就是 0）
                startTicks = record.startTicks ?: -1L,
                packageName = packageName
            )
        }
    }

    /**
     * 从 Linux 原始行拼出 Android 侧的查询键。
     *
     * `processName` 用"名称"列的原始值：后端这一列取的是 cmdline 的 argv[0]（完整进程名，
     * 例如 `com.android.systemui`、`com.android.systemui:screenshot`），
     * 而同 uid 多包（uid 1000 下的 systemui / screenrecord 等）**只能靠进程名分辨**，
     * 光有 uid 必然把别人的包名安错。
     *
     * 不用内核 comm 列：comm 被截到 15 字符（`com.android.sys`），对不上包名。
     * 名称列缺失时退回 cmdline 的第一个参数（两者正常情况下是同一个东西）。
     * 僵尸进程的名称是 `[comm]` 形式，方括号开头的一律当"没有进程名"。
     */
    private fun processKeyOf(record: LinuxProcessRecord): ProcessKey {

        val fromName = record.fieldOf(DetailColumnId.NAME)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("[") }

        val processName = fromName ?: record.fieldOf(DetailColumnId.CMDLINE)
            ?.trim()
            ?.substringBefore(' ')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() }

        return ProcessKey(
            pid = record.pid,
            startTicks = record.startTicks ?: -1L,
            uid = record.uid,
            processName = processName
        )
    }

    /**
     * 把 Android 服务才能拿到的字段并进一行。
     *
     * 只补 Linux 侧**明确回 NOT_AVAILABLE** 的五格：
     * package_name / target_sdk / description / android_priority / isolation。
     * 调用方已经先用 [AndroidService.prepareRound] 判过"这一行是 Android 进程"，
     * 所以这里只有 Android 进程会进来，Linux 原生进程根本不查。
     *
     * 调用形态是"一个进程问一次"：AndroidService 与本服务同进程，
     * 这一次调用不是 IPC、不走协议，查询本体是内存里的一次查表
     * （包 / uid 表与包信息都由它自己用 shell 身份预先取好）。
     *
     * 失败语义：这一格本来就没有值（例如不可见包的应用名、拿不到的分档），
     * 一律**保持原样**（Linux 的 `—（暂无数据）`），绝不填 0 或空串冒充有值。
     *
     * @param values  已按 43 列铺好的显示值，就地覆盖
     * @param record  Linux 侧的原始行（pid / startTicks / uid 都是行身份的一部分）
     * @param android 已在跑的 Android 服务实例（由调用方传入，避免每行再取一次）
     * @return 这一行所属应用的**原始包名**（拿不到返回 null）；调用方把它放进
     *         [ProcessRow.packageName]，图标槽按它查图。
     */
    private fun enrichWithAndroid(
        values: MutableMap<String, String>,
        record: LinuxProcessRecord,
        android: AndroidService
    ): String? {

        val supplement = android.queryProcess(processKeyOf(record)) ?: return null

        // 只覆盖确实拿到了的格：null 的字段保持 Linux 的状态文案
        supplement.packageName?.let { pkg ->

            // 包名后面跟上读到的应用名：`com.android.settings（设置）`。
            // 名字读到什么就写什么（中文 / 英文 / 厂商自定义都照原样），不做语言改写；
            // 名字还没补上的那几轮只显示包名，包信息补齐后下一轮自动变成"包名（名字）"。
            val label = supplement.description?.trim()?.takeIf { it.isNotEmpty() && it != pkg }

            values[DetailColumnId.PACKAGE_NAME] = if (label == null) pkg else "$pkg（$label）"
        }
        supplement.targetSdk?.let { values[DetailColumnId.TARGET_SDK] = it.toString() }
        supplement.description?.let { values[DetailColumnId.DESCRIPTION] = it }
        supplement.androidPriority?.let { values[DetailColumnId.ANDROID_PRIORITY] = it.toString() }
        // AndroidService 回的是协议码（ISOLATED / NOT_ISOLATED），落地成界面写法：
        // 只给已隔离的行写 AndroidContainer，其余留空。
        supplement.isolation?.let {
            values[DetailColumnId.ISOLATION] = BackendDisplayText.processColumnText(
                DetailColumnId.ISOLATION,
                it
            )
        }

        return supplement.packageName
    }

    /**
     * 让 Linux 服务做一件事（原始控制码版）。
     *
     * 为什么返回 Boolean 而不返回包：调用方（调试页）关心的就是"成不成"，
     * 失败原因已经写进 LinuxService 的输出缓冲（调试页第二框能看到）。
     */
    override fun ControlLinuxSvc(code: Int, argument: String): Boolean {

        val linux = LinuxService.instanceOrNull() ?: return false

        val packet = linux.controlPackage(code, argument, linuxRequestTimeoutMs)

        return packet?.isOk == true
    }

    /**
     * 语义化命令：枚举 -> LinuxService 的友好方法，调用方不必知道控制码与包格式。
     *
     * ACCEPTED 也算"受理了"：KILL 在当前后端版本只回执不执行，
     * 若把它当成失败，界面会误报"结束失败"。
     */
    override fun CommandToLinuxSvc(
        command: LinuxCommand,
        pid: Int,
        startTicks: Long,
        timeoutMs: Long
    ): Boolean {

        val linux = LinuxService.instanceOrNull() ?: return false

        val timeout = if (timeoutMs > 0L) timeoutMs else linuxRequestTimeoutMs

        val packet = when (command) {
            LinuxCommand.PROCESS_TERMINATE -> linux.killProcess(pid, startTicks, timeout)
            LinuxCommand.REINIT -> linux.reinitialize(timeout)
            LinuxCommand.DUMP_STATE -> linux.dumpState(timeout)
            LinuxCommand.SHUTDOWN -> linux.requestShutdown(timeout)
        }

        return packet != null && (packet.isOk || packet.isAccepted)
    }

    /**
     * 登记某一页需要哪些字段（覆盖式）。
     *
     * 传 [LinuxField.NONE] = 撤销登记（页面离场 / 该页当前不需要 Linux 数据）。
     * 页面切列（详细信息页勾选列）时再调一次即可，不必每帧调。
     */
    override fun publishLinuxDemand(page: String, mask: Long) {

        if (mask == LinuxField.NONE) {
            linuxDemands.remove(page)
        } else {
            linuxDemands[page] = mask
        }

        Log.i(
            TAG,
            "Linux 需求：$page -> ${LinuxField.describe(mask)}；并集 ${LinuxField.describe(mergedLinuxDemand())}"
        )
    }

    /** 当前所有页面需求的并集（0 = 没有页需要 Linux 数据，此时不必请求）。 */
    override fun mergedLinuxDemand(): Long =
        linuxDemands.values.fold(LinuxField.NONE) { acc, mask -> acc or mask }

    /** 最近一次成功快照（可能是 null：还没取过，或取失败）。 */
    override fun cachedLinuxSnapshot(): LinuxPacket? = linuxSnapshotCache

    /** 服务能力自述：协议版本、字段数、支持的掩码、自身 uid —— 用于自检与排错。 */
    override fun linuxCapabilities(): LinuxPacket? {

        val linux = LinuxService.instanceOrNull() ?: return null

        return linux.capabilities(linuxRequestTimeoutMs)
    }

    /**
     * 设备快照的原始包（调试页用）。
     *
     * 与 performanceSnapshot() 的分工：那个只回填 UI 要用的帧，这个把整包交出去，
     * 好让调试页看清设备实例、字段与字段级状态分别是什么。
     */
    override fun linuxDeviceSnapshot(): LinuxPacket? {

        val linux = LinuxService.instanceOrNull() ?: return null

        return linux.deviceSnapshotPacket(linuxRequestTimeoutMs)
    }

    /**
     * 设备扩展快照（typeCode=6）的原始包：设备详细信息页与排错用。
     *
     * 与 [linuxDeviceSnapshot] 分开两路：那个是"整颗处理器一台设备"的性能页口径，
     * 这个是"一簇一台、一核一台"。两路各自有采样基线（后端侧各存一份），
     * 所以这里也不需要跟性能页的采集线程错峰。
     *
     * "已暂停"时不发请求：暂停的语义是"连后台也别动"，页面那边同样按 0 间隔处理。
     */
    fun linuxExtendedDeviceSnapshot(): LinuxPacket? {

        if (pollIntervalMs(settingsSnapshot.updateSpeed) <= 0L) return null

        val linux = LinuxService.instanceOrNull() ?: return null

        return linux.extendedDeviceSnapshotPacket(linuxRequestTimeoutMs)
    }

    /**
     * CPU 扩展信息的一帧（清洗好、可直接渲染的模型），供设备详细信息页渲染。
     *
     * 清洗放在服务侧的理由与性能页一致：页面只该看到"填好的那一帧"，
     * 不自己去拼请求、不自己解析 D/F 行。
     */
    fun cpuExtendedSnapshot(): CpuExtendedSnapshot? =
        CpuExtendedSnapshot.from(linuxExtendedDeviceSnapshot())

    /**
     * 刷新一次快照（幂等入口：轮询与页面"刷新"按钮都走这里，避免各自发请求）。
     *
     * 并集为 0 表示"现在没有任何页需要 Linux 数据"，直接返回缓存，不打扰后端。
     */
    fun refreshLinuxSnapshot(): LinuxPacket? {

        val mask = mergedLinuxDemand()

        if (mask == LinuxField.NONE) return linuxSnapshotCache

        return GetDataFromLinuxSvc(mask)
    }

    /** 快照是多久之前取的（毫秒；0 = 从没取过）。调试页与状态条展示用。 */
    fun linuxSnapshotAgeMs(): Long {

        if (linuxSnapshotAtMs == 0L) return 0L

        return SystemClock.elapsedRealtime() - linuxSnapshotAtMs
    }

    /**
     * 只重启 AndroidService：按当前身份重新拉起，让它的自检结果反映新身份。
     *
     * 为什么 stop 之后要延迟一下再 start：stopService 是异步的，
     * 紧接着 startService 有机会被投递到"正在销毁"的旧实例上，
     * 给它一点时间走完 onDestroy 更干净（onDestroy 里的竞态已在服务侧兜住）。
     */
    override fun restartAndroidService() {

        mainHandler.post {

            runCatching { stopService(Intent(this, AndroidService::class.java)) }

            mainHandler.postDelayed({

                runCatching {
                    startService(
                        Intent(this, AndroidService::class.java)
                            .putExtra(
                                AndroidService.EXTRA_PRIVILEGED_MODE,
                                upstreamPrivilegedWanted()
                            )
                            .putExtra(
                                AndroidService.EXTRA_IDENTITY_MODE,
                                upstreamIdentityMode()
                            )
                    )
                }

                Log.i(TAG, "AndroidService 已按当前身份重启")

            }, 300L)
        }
    }

    // ---------- ISettingsChangeSink：设置页变更的落盘入口 ----------

    /**
     * 关掉"置顶"之后收窗并把普通界面拉回前台。
     *
     * 两个入口共用：设置页关「置于顶层」，以及关「使用无障碍权限置顶」导致置顶失去依据。
     * 做法与点"退出"完全一致：
     * 1. 先记下浮窗当前页（一次性便条，下次普通启动回到这一页）；
     * 2. 收窗；
     * 3. 只有窗真的在的时候才拉起 MainActivity —— 否则用户本来就在普通界面里，
     *    再拉一次会像是"页面闪了一下"。
     */
    private fun collapseTopWindow() {
        val wasShowing = isTopWindowShowing()

        if (wasShowing) {
            SettingsStore.update(this, SettingKey.LAST_PAGE, overlayPageState.value)
        }

        hideOverlay()

        // 在浮窗里关掉置顶：窗已经收了，此时用户会停在桌面上，
        // 必须把普通界面拉回来，否则看起来又是"应用退出了"
        if (wasShowing) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * 接收设置页的单项变更：写盘 -> 更新内存快照 -> 让需要立刻生效的项生效。
     *
     * 页面只负责传 (key, value)，不碰存储；这里是唯一的写入口。
     */
    override fun onSettingChanged(key: SettingKey, value: Any) {
        val next = SettingsStore.update(this, key, value)

        settingsSnapshot = next

        when (key) {
            SettingKey.APP_THEME -> applyTheme(next.appTheme)

            SettingKey.UPDATE_SPEED -> {
                val interval = pollIntervalMs(next.updateSpeed)

                if (interval > 0L) startPolling(interval) else stopPolling()
            }

            // [OVERLAY-v1] 关掉"置于顶层"就立刻收浮窗；开启不在这里弹窗，
            // 按约定是"重启应用后由 MainActivity 以浮窗启动"。
            // 但如果窗口已经在跑（可能是无障碍那只），开 / 关还要顺手核对通路：
            // 比如从无障碍窗口切回"只走悬浮窗"，用户不该等下次启动才看到层级变化。
            SettingKey.WINDOW_ALWAYS_ON_TOP -> {
                if (!next.windowAlwaysOnTop) collapseTopWindow() else syncTopWindowKind()
            }

            // 「使用无障碍权限置顶」= 选哪条通路建顶层窗口。窗口正挂着时要当场换，
            // 不等重启——用户刚授完无障碍权限就该看到窗口变成更高层级那只。
            SettingKey.A11Y_TOP_WINDOW -> {
                if (!next.a11yTopWindow && !next.windowAlwaysOnTop) {
                    // 置顶也跟着掉了：收窗 + 把普通界面拉回来
                    collapseTopWindow()
                } else {
                    syncTopWindowKind()
                }
            }

            // 其余项暂时只有持久化意义，等对应模块接入后再各自生效
            else -> Unit
        }

        // TODO(预留): 把变更广播给其他正在使用该设置的页面
        //            （例如用一个 StateFlow<SettingsValues> 让各页 collect）
        //
        // 性能说明：配置文件只有十几项、约 1KB，主线程同步写可以接受；
        // 如果设置项涨到几十上百项，这里要改成"标脏 + 工作线程合并写"（debounce）。
    }

    // ---------- IOverlayApi：浮窗（OVERLAY v1 · 实验功能） ----------

    /** 是否已被授予悬浮窗权限。 */
    override fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    /**
     * 是否已被授予无障碍权限（本应用的无障碍服务在系统里被启用）。
     *
     * 判定统一走 [A11yTopWindow.isEnabled]：设置页、存储层、本服务用的是同一个答案。
     */
    override fun hasAccessibilityPermission(): Boolean = A11yTopWindow.isEnabled(this)

    /**
     * 无障碍设置页的 Intent。
     *
     * 注意与悬浮窗的区别：无障碍权限没有"直达本应用那一项"的公开入口，
     * 只能把用户送到系统无障碍列表页，由他自己找到本应用并打开开关。
     */
    override fun accessibilityPermissionIntent(): Intent = A11yTopWindow.settingsIntent()

    /** 直达本应用那一项的悬浮窗设置页。 */
    override fun overlayPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )

    /** 浮窗（含无障碍置顶窗口）是否正在显示。 */
    override val isOverlayShowing: Boolean
        get() = isTopWindowShowing()

    /**
     * 顶层窗口的内容：普通悬浮窗与无障碍窗口共用这一个界面。
     *
     * 两种窗口的区别只在"窗口类型与宿主 Context"，界面、页面切换、设置页内嵌模式完全一样，
     * 所以这里只有一份实现——以后改界面也不用改两处。
     */
    private fun topWindowContent(): @Composable (OverlayHost.Actions) -> Unit = { actions ->
        OverlayPageFrame(
            pageName = overlayPageState.value,
            // 窗口里的"设置"页：读当前快照、写回 UIService（由它统一落盘）
            settingsProvider = { settingsSnapshot },
            onSettingChanged = { key, value -> onSettingChanged(key, value) },
            actions = actions,
            onPageSelected = { page ->
                overlayPageState.value = page

                // 窗口切页签与主界面切页是同一件事：前台页变了，刷新目标也跟着变
                setActivePage(page)
            }
        )
    }

    /**
     * 显示顶层窗口：现有页面 → 一个近乎铺满屏幕的窗口。
     *
     * 走哪条通路由「使用无障碍权限置顶」决定（它就是"用哪种窗口"的开关）：
     * - 开着且无障碍权限真在 -> TYPE_ACCESSIBILITY_OVERLAY 窗口，由无障碍服务建，
     *   层级高于普通悬浮窗，且**不需要**悬浮窗权限；
     * - 否则 -> 普通悬浮窗（TYPE_APPLICATION_OVERLAY），要求悬浮窗权限。
     *
     * 顺序：定通路 -> 查权限 -> 挂窗口 -> 成功后把常驻通知换成"运行中"文案。
     */
    override fun showOverlay(page: String): Boolean {
        val useA11yWindow = a11yTopWindowUsable(settingsSnapshot)

        val host: OverlayHost = if (useA11yWindow) {
            val current = a11yWindowHostOrNull()

            if (current == null) {
                // 无障碍服务还没连上（刚开始授权 / 系统还没绑定）：这次不建窗，也**不动设置**。
                // 服务连上后会回调 onAccessibilityWindowReady() 补建，所以这里只把普通界面拉回来，
                // 免得 Activity 已经 finish() 的用户对着一片空屏。
                Log.w(TAG, "showOverlay: 无障碍服务尚未连接，等它连上再建窗")

                startNormalUiFallback()

                return false
            }

            current
        } else {
            if (!hasOverlayPermission()) {
                Log.w(TAG, "showOverlay: 没有悬浮窗权限，放弃建窗")

                // 走到这里说明"设置说要置顶、但权限已经没了"（启动那一刻刚好被收回）。
                // 必须把设置改回关闭，否则每次启动都会白白走一遍这个分支，
                // 设置页还会一直显示"已开启"。
                settingsSnapshot = SettingsStore.update(this, SettingKey.WINDOW_ALWAYS_ON_TOP, false)

                return false
            }

            overlayHost
        }

        overlayPageState.value = page.ifBlank { settingsSnapshot.defaultStartPage }

        // 窗口一出来它就是"前台页"：刷新调度按它的页名走
        setActivePage(overlayPageState.value)

        // 已经有一只窗（另一条通路的残留 / 重启前没来得及收掉的）：先收掉，保证这次是干净重建
        if (overlayHost.isShowing) overlayHost.hide()

        if (a11yWindowHost?.isShowing == true) a11yWindowHost?.hide()

        // 建窗过程中先按"还没显示"算，成功后再置位（通知文案跟着它）
        overlayVisible = false

        Log.i(
            TAG,
            "showOverlay: 开始建窗 page=${overlayPageState.value} 通路=" +
                if (useA11yWindow) "无障碍" else "悬浮窗"
        )

        val shown = try {
            host.show(
                bounds = host.defaultBounds(),
                onClose = { exitOverlayMode() },
                content = topWindowContent()
            )
        } catch (e: Throwable) {
            // 这里是"窗口建不起来"的唯一证据来源：BadTokenException / SecurityException
            // / IllegalArgumentException（type 不被允许）都会落在这里
            Log.e(TAG, "showOverlay: 建窗失败 page=$page", e)

            false
        }

        if (!shown) {
            overlayVisible = false

            refreshServiceNotification()

            if (!useA11yWindow) {
                // 悬浮窗建窗失败：把设置改回关闭，否则用户会停留在"置顶开着却没有窗"的
                // 假状态，下次启动还会再试一遍（而且很可能再失败一次）。
                settingsSnapshot = SettingsStore.update(this, SettingKey.WINDOW_ALWAYS_ON_TOP, false)
            }
            // 无障碍通路失败则保留设置：权限还在，失败多半是服务刚断开或 ROM 拒绝，
            // 服务重连时还能再建一次；把用户的勾选擦掉反而更让人困惑。

            startNormalUiFallback()
        }

        if (shown) {
            // 窗口真的挂上了：通知换成"运行中"（服务本身已经是前台，只是换文案）
            overlayVisible = true

            // 窗口可见 = 允许驱动页面内容：门禁条件刚变，立刻重排
            reschedulePolling()

            refreshServiceNotification()

            // [OVERLAY-v1] 只有窗口真挂上了，"回原位"这条一次性记录才算被消费。
            // 建窗失败会走上面的兜底启动，那条记录必须留着——
            // 否则兜底出来的普通界面会落到默认页（进程），看起来就是"闪回进程页"。
            settingsSnapshot = SettingsStore.update(this, SettingKey.LAST_PAGE, "")

            Log.i(
                TAG,
                "showOverlay: 窗口已显示，page=${overlayPageState.value} 通路=" +
                    if (useA11yWindow) "无障碍" else "悬浮窗"
            )
        }

        return shown
    }

    /**
     * 关闭顶层窗口（幂等，不改设置）。两条通路都收。
     *
     * 服务本身继续常驻前台，只把通知文案换回"后台运行中"。
     * 不再在这里停巡检：巡检盯的是"设置说开着、权限却没了"，
     * 收窗就停会让它失去唯一的发现时机。
     */
    override fun hideOverlay() {
        overlayHost.hide()

        hideA11yWindow()

        overlayVisible = false

        refreshServiceNotification()

        // 窗口收了：如果主界面也不在前台，就该停止驱动页面内容（重排一次让门禁立刻生效）
        reschedulePolling()
    }

    /**
     * 无障碍服务连上了（由 [A11yTopWindowService.onServiceConnected] 调）。
     *
     * 为什么需要这个回调：主界面启动得比系统绑定无障碍服务更早是可能的
     * （刚授权完、或系统重启后），那时建窗会拿不到服务实例而暂时放弃。
     * 这里补一次：设置要求无障碍置顶、窗口又还没挂上，就按当前页再建一次。
     */
    internal fun onAccessibilityWindowReady() {
        val values = settingsSnapshot

        if (!values.windowAlwaysOnTop || !values.a11yTopWindow) return

        // 已经挂的就是无障碍那只：不用重建
        if (isShowingA11yWindow()) return

        // 两种情况都走这里：
        // 1) 还没窗（启动时服务没起来，补建一次）；
        // 2) 挂着的是悬浮窗（用户刚授权，要换成无障碍那只）。
        Log.i(TAG, "onAccessibilityWindowReady: 无障碍服务已连上，补建 / 切到无障碍置顶窗口")

        showOverlay(overlayPageState.value.ifBlank { values.defaultStartPage })
    }

    /**
     * 无障碍服务断开了（由 [A11yTopWindowService.onUnbind] 调）。
     *
     * 系统会把该服务的窗口一起撤掉，但"设置里还开着"这件事不会自动跟着变，所以这里：
     * 1. **先**按同一套判据回退设置（这时窗口状态还是"在显示"，才判得出"该换一只悬浮窗顶上"
     *    还是"置顶彻底失效"）；
     * 2. 再清掉无障碍窗口的宿主状态（系统多半已把窗口撤了，这里幂等清理）；
     * 3. 万一没有别的窗口顶上来（服务只是被系统重绑、权限还在），把可见状态与通知拉回"没窗口"，
     *    等 [onAccessibilityWindowReady] 重建。
     */
    internal fun onAccessibilityWindowGone() {
        Log.i(TAG, "onAccessibilityWindowGone: 无障碍服务断开，按当前设置决定是换通路还是退出置顶")

        enforceTopWindowPermissions("无障碍服务断开")

        hideA11yWindow()

        if (!isTopWindowShowing() && overlayVisible) {
            overlayVisible = false

            refreshServiceNotification()

            reschedulePolling()
        }
    }

    // ---------- IPageDataApi：各页取数 ----------

    /**
     * 两个上游通道的当前状态。
     *
     * 注意：上游服务是异步创建的，刚启动的瞬间可能返回 null（未就绪）。
     * 页面拿到 null 应该显示"正在准备数据"，不要当成错误。
     */
    override fun channelHealth(): ChannelHealth = ChannelHealth(
        androidChannel = androidChannelText(),
        linuxChannel = linuxChannelText(),
        upstreamStarted = upstreamStarted,
        settingsSource = settingsSource
    )

    /**
     * 两个上游服务最近的输出（调试页第二项）。
     *
     * 一次取两个服务的快照，保证调试页看到的两条通道是同一时刻的；
     * 服务没起来时给明确的说明文案，页面不用判空，也不会显示成一片空白。
     */
    override fun upstreamOutput(): UpstreamOutput = UpstreamOutput(
        android = AndroidService.instanceOrNull()?.outputSnapshot()
            ?: AppStrings.s(R.string.svc_android_not_ready_starting),
        linux = LinuxService.instanceOrNull()?.recentOutput()
            ?: AppStrings.s(R.string.svc_linux_not_ready_starting)
    )

    // ---------- IPageDataApi：详细信息页（进程属性表） ----------

    /**
     * 取整张表的快照。
     *
     * 可见列在这里过滤一次就够：页面拿到多少列，表就多宽；页面不需要知道还有哪些列被藏了。
     */
    override fun detailTable(): DetailTable {

        synchronized(detailLock) {

            val visibleColumns = detailColumns.filter { it.id in detailVisibleIds }.map { applyColumnWidth(it) }

            val sortId = detailSortColumnId

            val orderedRows = if (sortId == null) {
                detailRows.toList()
            } else {
                sortDetailRows(detailRows, sortId, detailSortDescending)
            }

            return DetailTable(
                columns = visibleColumns,
                // 全量列定义：给"选择列"对话框画复选框（顺序固定，不随勾选变化）
                allColumns = detailColumns.map { applyColumnWidth(it) },
                rows = orderedRows,
                sortColumnId = sortId,
                sortDescending = detailSortDescending,
                selectedPid = detailSelectedPid,
                selectedStartTicks = detailSelectedStartTicks,
                refreshIntervalMs = pollIntervalMs(settingsSnapshot.updateSpeed),
                // 页面拿这两个值来还原控件状态（范围三档 + 忽略开关），
                // 也让"UIService 是否会自动刷"和页面显示的那一档始终是同一个结论
                processScope = detailProcessScope,
                ignoreUpdateSpeed = detailIgnoreUpdateSpeed,
                note = detailNote
            )
        }
    }

    /**
     * 把列定义里的出厂宽度换成用户拖出来的宽度（没拖过就原样返回）。
     *
     * 调用方都在 [detailLock] 里（[detailTable] 的读取路径），所以这里不再单独加锁。
     */
    private fun applyColumnWidth(column: DetailColumn): DetailColumn {

        val width = detailColumnWidths[column.id] ?: return column

        return if (width == column.width) column else column.copy(width = width)
    }

    /**
     * 用户拖表头列边界改了某一列的宽度。
     *
     * 约定：
     * - 宽度夹在 [DETAIL_COLUMN_MIN_WIDTH_DP]..[DETAIL_COLUMN_MAX_WIDTH_DP]：拖不出"一个字都看不见"的列，
     *   也拖不出宽到把别的列全挤到屏幕外的列；
     * - 拖回和出厂宽度一样就等于"恢复默认"，把这个覆盖删掉（存档里不留冗余项）；
     * - 只改显示宽度：列集合、排序、采样都不动，所以不需要为此重新采一次快照。
     *
     * 注意：没有"一键恢复默认列宽"的入口（用户明确不要这个功能），
     * 想把某列变回去就把它的边界拖回原来的宽度；列宽存档损坏/整份重建时会退回出厂宽度。
     *
     * @return true = 列存在且宽度已记录 + 已排进落盘队列
     */
    override fun setDetailColumnWidth(columnId: String, widthDp: Int): Boolean {

        if (detailColumns.none { it.id == columnId }) return false

        val width = widthDp.coerceIn(DETAIL_COLUMN_MIN_WIDTH_DP, DETAIL_COLUMN_MAX_WIDTH_DP)

        synchronized(detailLock) {

            val defaultWidth = detailColumns.first { it.id == columnId }.width

            if (width == defaultWidth) {
                detailColumnWidths.remove(columnId)
            } else {
                detailColumnWidths[columnId] = width
            }
        }

        saveDetailViewState()

        return true
    }

    /**
     * 填入一行进程（一次调用 = 一排）。
     *
     * 同一进程（pid + startTicks）再填一次就覆盖原行：上游每轮重新采样时不会把表撑成重复行。
     * 同一 pid 但 startTicks 不同 = PID 被复用，按"换了个人"处理：替换旧行，
     * 若当时正选中/长按这一行，选中状态随之失效（不能继续对新进程生效）。
     */
    override fun appendProcessRow(row: ProcessRow): Boolean {

        if (row.pid <= 0) {
            Log.i(TAG, "appendProcessRow 被忽略：pid=${row.pid}")

            return false
        }

        synchronized(detailLock) {

            val index = detailRows.indexOfFirst { it.pid == row.pid }

            if (index >= 0) {

                val old = detailRows[index]

                if (old.startTicks != row.startTicks && old.startTicks >= 0L && row.startTicks >= 0L) {

                    // PID 复用：旧的选中/长按指向的是一个已经不存在的进程
                    if (detailSelectedPid == row.pid && detailSelectedStartTicks == old.startTicks) {
                        detailSelectedPid = null
                        detailSelectedStartTicks = null
                    }

                    if (detailLongPressedPid == row.pid && detailLongPressedStartTicks == old.startTicks) {
                        detailLongPressedPid = null
                        detailLongPressedStartTicks = null
                    }

                    Log.i(TAG, "PID 复用：${row.pid} 的 startTicks ${old.startTicks} -> ${row.startTicks}，旧选中已清除")
                }

                detailRows[index] = row
            } else {
                detailRows.add(row)
            }

            detailNote = AppStrings.s(R.string.svc_rows_from_upstream, detailRows.size)

            // 有手工数据进来了：之后定时刷新只更缓存，不再自动铺表覆盖它
            detailRowsFromSnapshot = false
        }

        return true
    }

    /** 整表替换；空列表 = 清空（表高会跟着变成 0 行）。 */
    override fun replaceProcessRows(rows: List<ProcessRow>) {

        synchronized(detailLock) {

            detailRows.clear()

            rows.forEach { row ->

                if (row.pid <= 0) return@forEach

                val index = detailRows.indexOfFirst { it.pid == row.pid }

                if (index >= 0) {
                    detailRows[index] = row
                } else {
                    detailRows.add(row)
                }
            }

            // 原来选中的进程已经不在了（行被删，或 PID 被别的进程复用）：取消选中，
            // 别让页面高亮一个不存在的行。startTicks 已知时必须一起比。
            if (detailSelectedPid != null &&
                detailRows.none { isSameProcess(it, detailSelectedPid!!, detailSelectedStartTicks) }
            ) {
                detailSelectedPid = null
                detailSelectedStartTicks = null
            }

            detailNote = if (detailRows.isEmpty()) {
                AppStrings.s(R.string.svc_rows_empty_waiting)
            } else {
                AppStrings.s(R.string.svc_rows_count, detailRows.size)
            }
        }
    }

    /**
     * 页面报告"现在需要哪些列的统计信息"（选择列对话框点确定时整份提交）。
     *
     * 这里是**整体替换**：以最后一次提交为准，不做增量合并。
     * 只认设计期就存在的列 id（[detailColumns] 里的），其余忽略——列集合不支持运行时扩展。
     *
     * 正在排序的那一列被取消勾选时顺手取消排序：否则用户会看到"顺序是乱的、表头却没有排序标记"。
     *
     * 为什么要这个接口：UI 只勾 12 列时，UIService 只需为这 12 列采样/计算，
     * 另外 31 列的 /proc 读取与格式化都可以跳过，这是省性能的地方。
     */
    override fun setDetailVisibleColumns(columnIds: Set<String>) {

        synchronized(detailLock) {

            detailVisibleIds.clear()

            columnIds.forEach { id ->
                if (detailColumns.any { it.id == id }) detailVisibleIds.add(id)
            }

            // 「名称」是必备列：不管页面提交了什么，都强制保留。
            // 理由：整张表的每一行都得有个能读的身份，名称列消失后用户只剩数字，
            // 而进程的"定位"（选中/长按/排序）本来就不依赖任何列文字（见 ProcessRow.pid）。
            detailVisibleIds.add(DetailColumnId.NAME)

            val sortId = detailSortColumnId

            if (sortId != null && sortId !in detailVisibleIds) {
                detailSortColumnId = null
            }

            // 每次可见列变化都落盘：下次打开详细信息页按这份布局还原。
            // 独立文件（detail_view.json），设置页的"重置所有设置"不会碰它。
            val snapshot = detailVisibleIds.toSet()
            val scopeSnapshot = detailProcessScope
            val ignoreSnapshot = detailIgnoreUpdateSpeed
            val widthsSnapshot = detailColumnWidths.toMap()

            detailViewSaveExecutor.execute {
                DetailViewStore.save(this, snapshot, scopeSnapshot, ignoreSnapshot, widthsSnapshot)
            }
        }
    }

    /**
     * 切换详细信息页的进程范围（左下角三档）。
     *
     * 两个动作一起做：把新范围写进存档（下次打开还是这一档），
     * 然后立刻按新范围重采一次 —— 否则页面会先显示"上一个范围的旧行"，看起来像筛选没生效。
     */
    override fun setDetailProcessScope(scope: ProcessScope) {

        val effective = ProcessScope.ofWire(scope.wire)

        if (detailProcessScope == effective) return

        detailProcessScope = effective

        // 缓存里是旧范围的行，不能拿它顶新范围
        linuxSnapshotLock.lock()

        try {
            linuxSnapshotCache = null
            linuxSnapshotAtMs = 0L
        } finally {
            linuxSnapshotLock.unlock()
        }

        saveDetailViewState()

        requestDetailRefreshOnce()
    }

    /**
     * 开关"忽略实时更新速度"。
     *
     * true（默认）= 页面不跟着定时器自动采，只有「刷新」按钮会采；
     * false = 回退到"按设置页的实时更新速度自动刷"。
     */
    override fun setDetailIgnoreUpdateSpeed(ignore: Boolean) {

        if (detailIgnoreUpdateSpeed == ignore) return

        detailIgnoreUpdateSpeed = ignore

        saveDetailViewState()

        // 从"手动"切回"自动"：把需求登记回去并先补一次，免得等下一个节拍才有数据；
        // 切到"手动"：把它从全局并集里摘掉，别的页刷新就不再顺手采它那几列。
        if (ignore) {
            linuxDemands.remove(PAGE_DETAIL)
        } else {
            if (effectiveActivePage() == PAGE_DETAIL) {
                linuxDemands[PAGE_DETAIL] = detailDemandMask()
            }

            requestDetailRefreshOnce()
        }
    }

    /**
     * 「刷新」按钮：立即采一次（不管"忽略实时更新速度"开着还是关着）。
     *
     * 采集放到 [linuxPollExecutor]：一次 /proc 遍历是毫秒级，但不能占 UI 线程。
     * @return true = 已排进采集队列；false = 没有 Linux 后端可用
     */
    override fun refreshDetailNow(): Boolean = requestDetailRefreshOnce()

    // ---------- 页标题搜索框（IPageSearchApi） ----------
    //
    // 输入框只有 MainActivity 画一个，关键字存在这里：页面（普通界面 / 浮窗里同一份页面）
    // 都从 pageSearchQuery() 取，不各攒一份。

    /**
     * 各页当前的搜索关键字；没输入过就没有这个 key（读出来当空串）。
     *
     * 用 Compose 的 snapshot map 而不是普通 Map：页面在 composition 里读它，
     * 输入框每敲一个字就会让页面重组、立刻重筛，不需要等下一轮快照。
     */
    private val pageSearchQueries = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** 这一页要不要显示搜索框：性能页是曲线，没有"按名字捞出来"的概念。 */
    override fun pageSupportsSearch(page: String): Boolean = page != "性能"

    override fun setPageSearchQuery(page: String, query: String) {

        if (query.isEmpty()) {
            pageSearchQueries.remove(page)
        } else {
            pageSearchQueries[page] = query
        }
    }

    override fun pageSearchQuery(page: String): String = pageSearchQueries[page] ?: ""

    /**
     * 结束进程之后的延迟刷新。
     *
     * 重复点/连续失败时不叠加：先撤掉上一次还没跑的，再排一个新的。
     */
    private val killRefreshRunnable = Runnable { requestDetailRefreshOnce() }

    /** 排一次"1 秒后重采"（Runnable 复用同一个字段，避免排队堆积）。 */
    private fun scheduleDetailRefreshAfterKill() {

        mainHandler.removeCallbacks(killRefreshRunnable)

        mainHandler.postDelayed(killRefreshRunnable, KILL_REFRESH_DELAY_MS)
    }

    /**
     * 结束进程：pid + 启动时刻双确认 → 交给 Linux 服务发 SIGKILL → 把回执翻成一句话。
     *
     * 成败只认服务端的 status：`ACCEPTED`（只受理没执行）也算失败——
     * 界面上"结束进程"点下去必须有明确结果，不能拿假回执当成功。
     */
    override fun killDetailProcess(pid: Int, startTicks: Long): ProcessActionResult {

        if (pid <= 0) {
            return ProcessActionResult(false, AppStrings.s(R.string.svc_no_process_selected), LinuxPacketStatus.BAD_ARGUMENT)
        }

        // 没有启动时刻（-1）就确认不了"还是同一个进程"：宁可拒绝，也不冒"PID 被复用后杀错人"的风险。
        // 0 是合法值，不能当作"没有"—— init 的 starttime 就是 0。
        if (startTicks < 0L) {
            return ProcessActionResult(
                false,
                AppStrings.s(R.string.svc_no_start_time),
                LinuxPacketStatus.BAD_ARGUMENT
            )
        }

        val linux = LinuxService.instanceOrNull()

        if (linux == null) {
            return ProcessActionResult(false, AppStrings.s(R.string.svc_linux_not_ready), LinuxPacketStatus.UNSUPPORTED)
        }

        val packet = linux.killProcess(pid, startTicks, linuxRequestTimeoutMs)

        if (packet == null) {

            scheduleDetailRefreshAfterKill()

            return ProcessActionResult(false, AppStrings.s(R.string.svc_linux_no_reply), LinuxPacketStatus.INTERNAL)
        }

        if (packet.isOk) {

            // 成功不提示，只刷新；而且隔 1 秒再采 —— 信号刚发出去，/proc 里那一行还在，
            // 立刻采会把它读回来，看起来像没杀掉。
            scheduleDetailRefreshAfterKill()

            return ProcessActionResult(true, "", LinuxPacketStatus.OK)
        }

        val reason = when (packet.status) {

            LinuxPacketStatus.PROCESS_EXITED -> AppStrings.s(R.string.svc_process_exited)

            LinuxPacketStatus.UNSUPPORTED -> AppStrings.s(R.string.svc_not_supported)

            LinuxPacketStatus.BAD_ARGUMENT -> AppStrings.s(R.string.svc_bad_argument)

            LinuxPacketStatus.ACCEPTED -> AppStrings.s(R.string.svc_accepted_only)

            // INTERNAL：信号发不出去。errno 1 = EPERM（权限不够），3 = ESRCH（进程已不在）
            else -> when (packet.info["errno"]?.trim()?.toIntOrNull()) {
                1 -> AppStrings.s(R.string.svc_access_denied)
                3 -> AppStrings.s(R.string.svc_process_exited)
                else -> AppStrings.s(R.string.svc_access_denied)
            }
        }

        // 失败也刷一次（同样延后）：目标可能已经不存在，表格要跟上真实状态
        scheduleDetailRefreshAfterKill()

        return ProcessActionResult(false, reason, packet.status)
    }

    /** 把后端回的 errno 翻成一句给用户看的话（EPERM 与 EACCES 都说"权限不够"）。 */
    private fun errnoReason(errno: Int): String = when (errno) {
        1, 13 -> AppStrings.s(R.string.svc_access_denied)
        2, 3 -> AppStrings.s(R.string.svc_process_exited)
        12 -> AppStrings.s(R.string.svc_out_of_memory)
        22 -> AppStrings.s(R.string.svc_bad_argument)
        30 -> AppStrings.s(R.string.svc_read_only_fs)
        else -> AppStrings.s(R.string.svc_errno_generic, errno)
    }

    /**
     * 结束一整棵进程树。
     *
     * 与单杀共用同一套身份纪律：pid + startTicks 缺一不可；
     * 后端自下而上杀，成功后同样延后 1 秒重采（立刻采会把刚死的行又读回来）。
     */
    override fun killDetailProcessTree(pid: Int, startTicks: Long): ProcessActionResult {

        if (pid <= 0) {
            return ProcessActionResult(false, AppStrings.s(R.string.svc_no_process_selected), LinuxPacketStatus.BAD_ARGUMENT)
        }

        if (startTicks < 0L) {
            return ProcessActionResult(false, AppStrings.s(R.string.svc_no_start_time), LinuxPacketStatus.BAD_ARGUMENT)
        }

        val linux = LinuxService.instanceOrNull()
            ?: return ProcessActionResult(false, AppStrings.s(R.string.svc_linux_not_ready), LinuxPacketStatus.UNSUPPORTED)

        val packet = linux.killProcessTree(pid, startTicks, linuxRequestTimeoutMs)
            ?: return ProcessActionResult(false, AppStrings.s(R.string.svc_linux_no_reply), LinuxPacketStatus.INTERNAL)

        scheduleDetailRefreshAfterKill()

        if (packet.isOk) {
            return ProcessActionResult(true, "", LinuxPacketStatus.OK)
        }

        val reason = when (packet.status) {
            LinuxPacketStatus.PROCESS_EXITED -> AppStrings.s(R.string.svc_process_exited)
            LinuxPacketStatus.UNSUPPORTED -> AppStrings.s(R.string.svc_not_supported)
            LinuxPacketStatus.BAD_ARGUMENT -> AppStrings.s(R.string.svc_bad_argument)
            LinuxPacketStatus.ACCEPTED -> AppStrings.s(R.string.svc_accepted_only)
            else -> errnoReason(packet.info["errno"]?.trim()?.toIntOrNull() ?: 1)
        }

        return ProcessActionResult(false, reason, packet.status)
    }

    /** 设置 nice：身份核对与错误翻译都在后端，这里只负责发命令 + 排一次重采。 */
    override fun setDetailProcessPriority(pid: Int, startTicks: Long, nice: Int): ProcessActionResult {

        if (pid <= 0 || startTicks < 0L) {
            return ProcessActionResult(false, AppStrings.s(R.string.svc_no_start_time), LinuxPacketStatus.BAD_ARGUMENT)
        }

        if (nice !in -20..19) {
            return ProcessActionResult(false, AppStrings.s(R.string.svc_bad_argument), LinuxPacketStatus.BAD_ARGUMENT)
        }

        val linux = LinuxService.instanceOrNull()
            ?: return ProcessActionResult(false, AppStrings.s(R.string.svc_linux_not_ready), LinuxPacketStatus.UNSUPPORTED)

        val packet = linux.setProcessPriority(pid, startTicks, nice, linuxRequestTimeoutMs)
            ?: return ProcessActionResult(false, AppStrings.s(R.string.svc_linux_no_reply), LinuxPacketStatus.INTERNAL)

        // 成功与否都重采一次：成功要看到新的 nice 列，失败也可能是因为进程刚退出
        scheduleDetailRefreshAfterKill()

        if (packet.isOk) {
            return ProcessActionResult(true, "", LinuxPacketStatus.OK)
        }

        val reason = when (packet.status) {
            LinuxPacketStatus.PROCESS_EXITED -> AppStrings.s(R.string.svc_process_exited)
            LinuxPacketStatus.BAD_ARGUMENT -> AppStrings.s(R.string.svc_bad_argument)
            else -> errnoReason(packet.info["errno"]?.trim()?.toIntOrNull() ?: 1)
        }

        return ProcessActionResult(false, reason, packet.status)
    }

    /** 设置 CPU 亲和性（掩码的第 n 位 = CPU n）。 */
    override fun setDetailProcessAffinity(pid: Int, startTicks: Long, mask: Long): ProcessActionResult {

        if (pid <= 0 || startTicks < 0L) {
            return ProcessActionResult(false, AppStrings.s(R.string.svc_no_start_time), LinuxPacketStatus.BAD_ARGUMENT)
        }

        if (mask == 0L) {
            return ProcessActionResult(false, AppStrings.s(R.string.svc_bad_argument), LinuxPacketStatus.BAD_ARGUMENT)
        }

        val linux = LinuxService.instanceOrNull()
            ?: return ProcessActionResult(false, AppStrings.s(R.string.svc_linux_not_ready), LinuxPacketStatus.UNSUPPORTED)

        val packet = linux.setProcessAffinity(pid, startTicks, mask, linuxRequestTimeoutMs)
            ?: return ProcessActionResult(false, AppStrings.s(R.string.svc_linux_no_reply), LinuxPacketStatus.INTERNAL)

        if (packet.isOk) {
            return ProcessActionResult(true, "", LinuxPacketStatus.OK)
        }

        val reason = when (packet.status) {
            LinuxPacketStatus.PROCESS_EXITED -> AppStrings.s(R.string.svc_process_exited)
            LinuxPacketStatus.BAD_ARGUMENT -> AppStrings.s(R.string.svc_bad_argument)
            else -> errnoReason(packet.info["errno"]?.trim()?.toIntOrNull() ?: 1)
        }

        return ProcessActionResult(false, reason, packet.status)
    }

    /** 读当前调度状态；后端没回包就返回 null（对话框会显示"读不到，仍然可以设置"）。 */
    override fun detailProcessSchedInfo(pid: Int): ProcessSchedInfo? {
        val packet = LinuxService.instanceOrNull()?.schedInfo(pid, linuxRequestTimeoutMs) ?: return null

        if (!packet.isOk) return null

        return ProcessSchedInfo(
            nice = packet.info["nice"]?.trim()?.toIntOrNull() ?: 0,
            policy = packet.info["policy"]?.trim()?.toIntOrNull() ?: 0,
            affinity = packet.info["affinity"]?.trim()?.removePrefix("0x")?.toLongOrNull(16) ?: 0L,
            threads = packet.info["threads"]?.trim()?.toIntOrNull() ?: 0,
            cpuCount = packet.info["cpus"]?.trim()?.toIntOrNull() ?: 0
        )
    }

    /**
     * 单独为一行进程查一次全字段（属性页专用）。
     *
     * 直接复用标准快照流程：掩码传 [LinuxField.ALL]，让后端把 43 列全采一遍，
     * 再走 [linuxSnapshotToRows] 照常合成 Android 侧字段（包名 / 目标 SDK / 分档 / 隔离），
     * 最后只从结果里挑出目标那一行 —— 不把整表铺进 UI，页面只多一次后台查询。
     *
     * 代价是这一轮会把整机 /proc 扫一遍（几百行，毫秒级），换来的是：
     * 即使用户在「选择列」里把某列关掉，属性页照样有值。
     */
    override fun detailProcessFullRow(pid: Int, startTicks: Long): ProcessRow? {

        if (pid <= 0) return null

        val packet = GetDataFromLinuxSvc(LinuxField.ALL) ?: return null

        if (!packet.isOk) return null

        val rows = linuxSnapshotToRows(packet)

        // 身份：pid + 启动时刻；启动时刻未知（< 0）时才只比 pid
        return rows.firstOrNull { row ->
            row.pid == pid && (startTicks < 0L || row.startTicks == startTicks)
        }
    }

    // ------------------------------------------------------------
    // 用户页：按属主 uid 分组的账户视图
    // ------------------------------------------------------------

    /** 相邻两次 usersTable() 之间算速率用的基线（IO 累计字节 / TrafficStats 字节数）。 */
    private val usersRateLock = Any()

    private var usersPrevAtMs = 0L

    private val usersPrevIoBytes = HashMap<Int, Long>()

    private val usersPrevNetBytes = HashMap<Int, Long>()

    // ---------- 应用历史记录页：自开账以来的 CPU 增量 + 网络累计 ----------

    /** 应用历史记录页的累加器与基线都只在本锁内读写。 */
    private val appHistoryLock = Any()

    /** 本页轮询间隔：只在页面可见时跑，离开即停，不常驻高频采样。 */
    private val APP_HISTORY_POLL_MS = 2_000L

    /** 进程身份 `pid/startTicks` -> 上一次读到的累计 CPU 秒数（C 后端 cpu_time）。 */
    private val appHistoryProcCpuBase = HashMap<String, Double>()

    /** 包名 -> 自开账以来累计 CPU 毫秒。 */
    private val appHistoryCpuMillis = HashMap<String, Long>()

    /** 一个 uid 的收发字节（某一网络类型、某一时间窗内的合计）。 */
    private class AppHistoryUidBytes(val rx: Long, val tx: Long)

    /** 包名 -> 自开账以来的网络字节（按类型 + 方向）。 */
    private class AppHistoryNetAccum {
        var mobileRx = 0L
        var mobileTx = 0L
        var wifiRx = 0L
        var wifiTx = 0L
    }

    private val appHistoryNetBytes = HashMap<String, AppHistoryNetAccum>()

    /** 开账时刻（wall clock ms）：网络统计窗口的左边界基准。 */
    private var appHistoryBeginMs = 0L

    /**
     * NetworkStats 历史桶的时长：本机（Android 15）为 1 小时，桶边界对齐整点。
     *
     * 服务端只返回"整体落在查询区间内"的桶，落在当前小时的那半个桶会被整个剔掉
     * （实测窗口 [开账, now] 与 [整点, 下一个整点] 都是 buckets=0）。
     */
    private val APP_HISTORY_HOUR_MS = 60L * 60L * 1000L

    /** 是否需要在本轮（后台线程）确认一次 appops GET_USAGE_STATS。 */
    private var appHistoryUsageOpPending = false

    /** 使用情况访问是否已确认 allow；false = 网络列显示「—」。 */
    private var appHistoryUsageOpGranted = false


    /**
     * 组一张「用户」表：一行一个 uid，行内是它的全部进程。
     *
     * 每次都走一次标准快照（只要 8 个字段：名称 / 状态 / 用户 / CPU / RSS / IO 读写 / 可执行路径），
     * 磁盘速率由两次调用之间的 IO 累计增量除以时间差得到；
     * 网络按 uid 问 TrafficStats（进程级拿不到），不支持时字段为 null。
     */
    override fun usersTable(): UsersTable {

        val mask = LinuxField.NAME or LinuxField.STATE or LinuxField.USER or
            LinuxField.CPU_USAGE or LinuxField.RSS or
            LinuxField.IO_READ_BYTES or LinuxField.IO_WRITE_BYTES or
            LinuxField.EXE_PATH

        val linux = LinuxService.instanceOrNull() ?: return UsersTable()

        // 走自己的请求、不写 linuxSnapshotCache：这张表只要 7 个字段，
        // 塞进共享缓存会把详细信息页要用的全字段包换成"缺列"的包。
        // 但要和详细信息页共用同一把快照闸门，避免两轮 /proc 采集互相覆盖应答。
        val packet = run {

            linuxSnapshotLock.lock()

            try {
                linux.snapshot(mask, ProcessScope.ALL, linuxRequestTimeoutMs)
            } finally {
                linuxSnapshotLock.unlock()
            }
        }

        val snapshot = packet?.takeIf { it.isOk } ?: return UsersTable()

        val now = SystemClock.elapsedRealtime()

        // 速率基线在这里读走一份快照：后面算速率时不再碰共享字段（并发调用不会互相改值）
        var elapsedSeconds = 0.0

        var previousIo: Map<Int, Long> = emptyMap()

        var previousNet: Map<Int, Long> = emptyMap()

        synchronized(usersRateLock) {

            elapsedSeconds = if (usersPrevAtMs > 0L) {
                (now - usersPrevAtMs) / 1000.0
            } else {
                0.0
            }

            previousIo = HashMap(usersPrevIoBytes)

            previousNet = HashMap(usersPrevNetBytes)
        }

        val nextIo = HashMap<Int, Long>(snapshot.processes.size)

        val nextNet = HashMap<Int, Long>()

        val grouped = LinkedHashMap<Int, MutableList<UserProcessRow>>()

        val labels = HashMap<Int, String>()

        val suspendedOnly = HashMap<Int, Boolean>()

        // 同一 uid 在循环里会出现很多次：TrafficStats 只问一遍（值可能为 null，用 containsKey 区分）
        val netTotals = HashMap<Int, Long?>()

        // 用户页只算"应用进程"：uid 0（root）和 1000..9999（system / shell / 系统服务）
        // 是 Linux 内核内置账户，不属于任何 Android 用户，不能塞进「机主」。
        // 10000 起才是 Android 应用 uid（user 0 的 appId 10000..99999）。
        val androidService = AndroidService.instanceOrNull()

        val androidPids = androidService?.prepareRound(
            snapshot.processes.map { processKeyOf(it) }
        ) ?: emptySet()

        snapshot.processes.forEach { record ->

            val uid = record.uid ?: -1

            if (uid < 10000) return@forEach

            val processKey = processKeyOf(record)

            val name = record.displayOf(DetailColumnId.NAME)

            val rawState = record.fieldOf(DetailColumnId.STATE).orEmpty()

            val cpu = record.fieldOf(DetailColumnId.CPU_USAGE)?.toDoubleOrNull() ?: 0.0

            val rss = record.fieldOf(DetailColumnId.RSS)?.toLongOrNull() ?: 0L

            // 磁盘：IO 累计字节（读 + 写）；第一次没有基线，速率按 0 处理
            val ioTotal = (record.fieldOf(DetailColumnId.IO_READ_BYTES)?.toLongOrNull() ?: 0L) +
                (record.fieldOf(DetailColumnId.IO_WRITE_BYTES)?.toLongOrNull() ?: 0L)

            nextIo[record.pid] = ioTotal

            val previous = previousIo[record.pid]

            val diskRate = if (elapsedSeconds > 0.0 && previous != null && ioTotal >= previous) {
                ((ioTotal - previous) / elapsedSeconds).toLong()
            } else {
                0L
            }

            // 网络按 uid：进程级没有可靠来源，只有用户行能显示
            val uidNet = if (netTotals.containsKey(uid)) {
                netTotals[uid]
            } else {
                readUidNetworkBytes(uid).also { netTotals[uid] = it }
            }

            if (uidNet != null) nextNet[uid] = uidNet

            if (!labels.containsKey(uid)) {
                labels[uid] = record.fieldOf(DetailColumnId.USER)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: if (uid >= 0) "uid $uid" else AppStrings.s(R.string.users_unknown_user)
            }

            val isSuspended = rawState == "T" || rawState == "t"

            suspendedOnly[uid] = (suspendedOnly[uid] ?: true) && isSuspended

            grouped.getOrPut(uid) { ArrayList() }.add(
                UserProcessRow(
                    pid = record.pid,
                    name = name,
                    state = if (isSuspended) AppStrings.s(R.string.users_process_suspended) else "",
                    cpuPercent = cpu,
                    memoryBytes = rss,
                    diskBytesPerSecond = diskRate,
                    networkBytesPerSecond = null,
                    startTicks = record.startTicks ?: -1L,
                    exePath = record.fieldOf(DetailColumnId.EXE_PATH)?.trim()?.takeIf { it.isNotEmpty() },
                    packageName = if (record.pid in androidPids) {
                        androidService?.queryProcess(processKey)?.packageName
                    } else {
                        null
                    },
                    packageDisplayName = record.fieldOf(DetailColumnId.PACKAGE_NAME)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                )
            )
        }

        // 每个 uid 的网络速率：累计字节的增量 / 两次采样的时间差（首次没有基线 → 0）
        val uidNetRates = HashMap<Int, Long>()

        nextNet.forEach { (uid, total) ->

            val previous = previousNet[uid]

            uidNetRates[uid] = if (elapsedSeconds > 0.0 && previous != null && total >= previous) {
                ((total - previous) / elapsedSeconds).toLong()
            } else {
                0L
            }
        }

        val groups = grouped.map { (uid, processes) ->

            val cpu = processes.sumOf { it.cpuPercent }

            val memory = processes.sumOf { it.memoryBytes }

            val disk = processes.sumOf { it.diskBytesPerSecond }

            val net = uidNetRates[uid]

            UserGroupRow(
                uid = uid,
                userLabel = labels[uid]
                    ?: if (uid >= 0) "uid $uid" else AppStrings.s(R.string.users_unknown_user),
                // 只有整组进程都挂起时才写「已挂起」，否则留空（不冒充"运行中"）
                status = if (suspendedOnly[uid] == true) {
                    AppStrings.s(R.string.users_group_suspended)
                } else {
                    ""
                },
                cpuPercent = cpu,
                memoryBytes = memory,
                diskBytesPerSecond = disk,
                networkBytesPerSecond = if (net != null) net else null,
                processes = processes.sortedWith(
                    compareByDescending<UserProcessRow> { it.cpuPercent }
                        .thenBy { it.name }
                )
            )
        }

        synchronized(usersRateLock) {

            usersPrevAtMs = now

            usersPrevIoBytes.clear()

            usersPrevIoBytes.putAll(nextIo)

            usersPrevNetBytes.clear()

            usersPrevNetBytes.putAll(nextNet)
        }

        return UsersTable(
            groups = groups.sortedWith(
                compareByDescending<UserGroupRow> { it.cpuPercent }
                    .thenBy { it.userLabel }
            ),
            totalCpuPercent = groups.sumOf { it.cpuPercent },
            totalMemoryBytes = groups.sumOf { it.memoryBytes },
            totalDiskBytesPerSecond = groups.sumOf { it.diskBytesPerSecond },
            totalNetworkBytesPerSecond = groups
                .mapNotNull { it.networkBytesPerSecond }
                .takeIf { it.isNotEmpty() }
                ?.sum(),
            refreshIntervalMs = pollIntervalMs(settingsSnapshot.updateSpeed)
        )
    }

    /**
     * 用户页头像原始字节：交给界面解码。
     *
     * 头像文件在 `/data/system/users/<id>/photo.*`，只有 root 通道读得到；
     * 读不到返回 null，界面用通用账户图标兜底。
     */
    fun androidUserAvatar(userId: Int): ByteArray? {
        return AndroidService.instanceOrNull()?.userAvatarPng(userId)
    }

    /**
     * 「用户」页的 Android 视图：只贴 Android 的用户机制，不再提供 Linux 账户视图。
     *
     * Android 已经完全改写了账户模型，内核里只剩系统内置 uid；真正的"用户"是
     * UserManager 里的 userId。进程归属直接用 uid 反推：
     * `userId = uid / 100000`，`appId = uid % 100000`（系统 uid / 隔离进程归 user 0）。
     *
     * 页面只列"正在运行"的用户：当前用户一定在；其它用户要么有进程在跑，要么
     * `UserManager.isUserRunning` 明确为 true，停用的用户不显示——这里是任务管理器，
     * 不是用户管理器。
     *
     * 用户名 / 状态来自 UserManager（隐藏接口，失败就退回"用户 N"，不猜机主 / 工作资料）。
     */
    override fun androidUsersTable(): AndroidUsersTable {

        val linux = usersTable()

        val currentUserId = android.os.Process.myUid() / 100000

        val grouped = LinkedHashMap<Int, MutableList<UserGroupRow>>()

        linux.groups.forEach { group ->

            val userId = androidUserIdOf(group.uid)

            grouped.getOrPut(userId) { ArrayList() }.add(group)
        }

        val metas = androidUserMetas()

        // 只收"当前用户 + 明确在运行的用户 + 确实有进程在跑的用户"
        val userIds = LinkedHashSet<Int>()

        userIds += currentUserId

        metas.filter { it.running }.forEach { userIds += it.id }

        grouped.keys.filter { it >= 0 }.forEach { userIds += it }

        // uid -1 只在一行都读不到属主时出现：保留为「未知用户」，不混进别的账户
        if (grouped.containsKey(-1)) userIds += -1

        val users = userIds.map { userId ->

            val groups = grouped[userId].orEmpty().sortedWith(
                compareByDescending<UserGroupRow> { it.cpuPercent }
                    .thenBy { it.userLabel }
            )

            val cpu = groups.sumOf { it.cpuPercent }

            val memory = groups.sumOf { it.memoryBytes }

            val disk = groups.sumOf { it.diskBytesPerSecond }

            val net = groups.mapNotNull { it.networkBytesPerSecond }
                .takeIf { it.isNotEmpty() }
                ?.sum()

            val meta = metas.firstOrNull { it.id == userId }

            val fallbackLabel = if (userId < 0) {
                AppStrings.s(R.string.users_unknown_user)
            } else {
                AppStrings.s(R.string.users_android_title, userId)
            }

            val label = meta?.name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallbackLabel

            val status = when {
                userId < 0 -> ""
                userId == currentUserId -> AppStrings.s(R.string.users_status_connected)
                else -> AppStrings.s(R.string.users_status_running)
            }

            AndroidUserSummary(
                userId = userId,
                label = label,
                isCurrent = userId == currentUserId,
                processCount = groups.sumOf { it.processes.size },
                appUidCount = groups.size,
                cpuPercent = cpu,
                memoryBytes = memory,
                diskBytesPerSecond = disk,
                networkBytesPerSecond = net,
                groups = groups,
                status = status,
                running = true
            )
        }

        return AndroidUsersTable(
            users = users.sortedWith(
                compareByDescending<AndroidUserSummary> { it.isCurrent }
                    .thenByDescending { it.cpuPercent }
                    .thenBy { it.userId }
            ),
            totalUsers = users.size,
            totalProcessCount = users.sumOf { it.processCount },
            totalCpuPercent = users.sumOf { it.cpuPercent },
            totalMemoryBytes = users.sumOf { it.memoryBytes },
            totalDiskBytesPerSecond = users.sumOf { it.diskBytesPerSecond },
            totalNetworkBytesPerSecond = users
                .mapNotNull { it.networkBytesPerSecond }
                .takeIf { it.isNotEmpty() }
                ?.sum(),
            refreshIntervalMs = linux.refreshIntervalMs
        )
    }

    // ---------- 应用历史记录页：CPU 增量 + 网络累计（自 appHistoryBegin 开账） ----------

    override fun appHistoryBegin() {

        synchronized(appHistoryLock) {

            appHistoryProcCpuBase.clear()
            appHistoryCpuMillis.clear()
            appHistoryNetBytes.clear()

            appHistoryBeginMs = System.currentTimeMillis()

            // 开账时只做"待确认"标记：真正去 shell 里 appops 授予放到后台采样里做
            appHistoryUsageOpPending = true
            appHistoryUsageOpGranted = false
        }
    }

    override fun appHistoryTable(): AppHistoryTable {

        val androidService = AndroidService.instanceOrNull()

        // ---- CPU：C 后端快照，按 pid/startTicks 记基线、采增量，包名归属走 AndroidService ----
        val mask = LinuxField.NAME or LinuxField.CPU_TIME

        val linux = LinuxService.instanceOrNull()

        val packet = linux?.let {

            linuxSnapshotLock.lock()

            try {
                it.snapshot(mask, ProcessScope.ALL, linuxRequestTimeoutMs)
            } finally {
                linuxSnapshotLock.unlock()
            }
        }

        val snapshot = packet?.takeIf { it.isOk }

        val cpuAvailable = snapshot != null

        val runningPackages = HashSet<String>()

        if (snapshot != null && androidService != null) {

            val androidPids = androidService.prepareRound(
                snapshot.processes.map { processKeyOf(it) }
            )

            snapshot.processes.forEach { record ->

                val cpuSec = record.fieldOf(DetailColumnId.CPU_TIME)?.toDoubleOrNull()
                    ?: return@forEach

                val procKey = "${record.pid}/${record.startTicks ?: -1L}"

                val pkg = if (record.pid in androidPids) {
                    androidService.queryProcess(processKeyOf(record))?.packageName
                } else {
                    null
                }

                if (pkg != null) runningPackages += pkg

                synchronized(appHistoryLock) {

                    val prev = appHistoryProcCpuBase[procKey]

                    if (prev == null) {

                        // 第一次见到这个进程：只记基线，不把"进程历史 CPU"算进本页
                        appHistoryProcCpuBase[procKey] = cpuSec

                    } else {

                        val deltaMs = ((cpuSec - prev) * 1000.0)
                            .coerceAtLeast(0.0)
                            .toLong()

                        appHistoryProcCpuBase[procKey] = cpuSec

                        if (pkg != null && deltaMs > 0L) {

                            appHistoryCpuMillis.merge(pkg, deltaMs, Long::plus)
                        }
                    }
                }
            }
        }

        // ---- 网络：NetworkStatsManager 历史统计，按 uid 累加 RX+TX ----
        // 桶按整点对齐、且只返回"已经结算完"的桶：区间没覆盖整个桶时该桶会被剔掉
        // （实测 [开账, now]、[整点, 下一个整点] 都是 buckets=0）。
        // 于是窗口取"开账所在整点 → 最近一个已结算整点"；开账后还没走完一个整点时，
        // 回退成"最近一个已结算的整小时"，保证页面上立刻有真实数字而不是 0 B。
        val networkUsageGranted = ensureAppHistoryUsageAccess(androidService)

        val beginMs = synchronized(appHistoryLock) { appHistoryBeginMs }

        val nowMs = System.currentTimeMillis()

        /** 最近一个已结算的整点边界（当前小时开始时刻）。 */
        val settledMs = nowMs - (nowMs % APP_HISTORY_HOUR_MS)

        /** 开账时刻所在整点。 */
        val openHourMs = beginMs - (beginMs % APP_HISTORY_HOUR_MS)

        val queryStartMs = if (openHourMs < settledMs) openHourMs else settledMs - 3L * APP_HISTORY_HOUR_MS

        val queryEndMs = settledMs

        val queryable = networkUsageGranted && beginMs > 0L

        val mobileRaw: Map<Int, AppHistoryUidBytes>? =
            if (queryable) queryMobileUidBytes(queryStartMs, queryEndMs) else null

        val wifiRaw: Map<Int, AppHistoryUidBytes>? =
            if (queryable) queryUidBytes(ConnectivityManager.TYPE_WIFI, null, queryStartMs, queryEndMs) else null

        // 两种网络各自独立判定：哪一种查不到，该列整列「—」
        val mobileAvailable = mobileRaw != null

        val wifiAvailable = wifiRaw != null

        val networkAvailable = mobileAvailable || wifiAvailable

        if (androidService != null && (mobileRaw != null || wifiRaw != null)) {

            val byPackage = HashMap<String, AppHistoryNetAccum>()

            // 共享 uid 多包时只归第一个候选包，不重复计给全部包
            fun accumulateTotals(source: Map<Int, AppHistoryUidBytes>, mobile: Boolean) {

                source.forEach { (uid, bytes) ->

                    val pkg = androidService.packagesForUid(uid)?.firstOrNull() ?: return@forEach

                    val accum = byPackage.getOrPut(pkg) { AppHistoryNetAccum() }

                    if (mobile) {
                        accum.mobileRx += bytes.rx
                        accum.mobileTx += bytes.tx
                    } else {
                        accum.wifiRx += bytes.rx
                        accum.wifiTx += bytes.tx
                    }
                }
            }

            mobileRaw?.let { accumulateTotals(it, mobile = true) }

            wifiRaw?.let { accumulateTotals(it, mobile = false) }

            synchronized(appHistoryLock) {
                appHistoryNetBytes.clear()
                appHistoryNetBytes.putAll(byPackage)
            }
        } else {

            synchronized(appHistoryLock) { appHistoryNetBytes.clear() }
        }

        // ---- 应用清单：getInstalledApplications（QUERY_ALL_PACKAGES 下含系统应用） ----
        val pm = applicationContext.packageManager

        val entries = try {
            pm.getInstalledApplications(0)
        } catch (_: Exception) {
            emptyList()
        }

        val rows = entries.map { info ->

            val pkg = info.packageName

            val label = runCatching { info.loadLabel(pm)?.toString() }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != pkg } ?: pkg

            val net = synchronized(appHistoryLock) { appHistoryNetBytes[pkg] }

            AppHistoryRow(
                packageName = pkg,
                label = label,
                cpuTimeMillis = synchronized(appHistoryLock) { appHistoryCpuMillis[pkg] ?: 0L },
                mobileRxBytes = if (mobileAvailable) net?.mobileRx ?: 0L else null,
                mobileTxBytes = if (mobileAvailable) net?.mobileTx ?: 0L else null,
                wifiRxBytes = if (wifiAvailable) net?.wifiRx ?: 0L else null,
                wifiTxBytes = if (wifiAvailable) net?.wifiTx ?: 0L else null,
                // Android 无系统级历史通知计数器：恒为 null，界面显示「—」，不伪造 0
                notificationCount = null,
                systemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                running = pkg in runningPackages
            )
        }.sortedWith(
            compareByDescending<AppHistoryRow> { it.cpuTimeMillis }
                .thenByDescending { it.running }
                .thenBy { it.label.lowercase() }
        )

        return AppHistoryTable(
            rows = rows,
            cpuAvailable = cpuAvailable,
            networkAvailable = networkAvailable,
            networkSource = if (networkAvailable) "network_stats" else "",
            refreshIntervalMs = APP_HISTORY_POLL_MS,
            beginMs = beginMs
        )
    }

    /**
     * 确认本应用是否持有 appop GET_USAGE_STATS（按 uid 读网络统计的前提）。
     *
     * 只在 [appHistoryBegin] 之后的第一轮采样里真正去 shell 里授予并回读，
     * 之后直接复用结果；[appHistoryBegin] 每次进入页面会重新标记一次，
     * 所以身份切换 / 页面重进都会重新确认。
     */
    private fun ensureAppHistoryUsageAccess(androidService: AndroidService?): Boolean {

        val pending = synchronized(appHistoryLock) { appHistoryUsageOpPending }

        if (!pending) return synchronized(appHistoryLock) { appHistoryUsageOpGranted }

        val granted = androidService?.ensureUsageStatsAppOp() == true

        synchronized(appHistoryLock) {
            appHistoryUsageOpGranted = granted
            appHistoryUsageOpPending = false
        }

        return granted
    }

    /**
     * 移动网络：先按 `subscriberId = null` 查（持有 PACKAGE_USAGE_STATS 时多数 ROM 接受），
     * 抛异常再退到当前 SIM 的 subscriberId 重试一次；都失败返回 null（该列显示「—」）。
     *
     * 不退回 xt_qtaguid：Android 15 / 该 ROM 上这个 proc 节点已经不存在。
     */
    private fun queryMobileUidBytes(startMs: Long, endMs: Long): Map<Int, AppHistoryUidBytes>? {

        queryUidBytes(ConnectivityManager.TYPE_MOBILE, null, startMs, endMs)?.let { return it }

        val subscriberId = runCatching {
            (applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                ?.subscriberId
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

        return queryUidBytes(ConnectivityManager.TYPE_MOBILE, subscriberId, startMs, endMs)
    }

    /**
     * 一次 NetworkStatsManager 查询（等价于"查所有 uid"的公开入口）：uid -> 收发字节。
     *
     * 用 `queryDetails`（API 29+，语义就是"所有 UID 的明细"）；`queryDetailsForAll`
     * 不在公开 SDK 里（[android-37 android.jar] 上根本没有这个方法），所以不照抄那个名字。
     * 低版本设备没有 queryDetails → 抛错被吞掉，返回 null（该列显示「—」）。
     *
     * 桶的处理：公开的 Bucket 只暴露 state，不暴露 set。
     * 优先用 `STATE_ALL`（聚合口径）；某些 ROM 只给 default / foreground 分桶，
     * 这时才把其它 state 的桶相加，避免同一段流量被算两遍。
     * uid < 0 的行（UID_ALL 汇总行、已卸载 uid、tethering）不归属到任何包。
     *
     * @return null = 查询本身失败（未授权 / 参数不被接受 / 该 API 版本不支持），
     *         调用方按「这一列不可用」处理；空 Map = 查询成功但该窗口没有流量
     */
    private fun queryUidBytes(
        networkType: Int,
        subscriberId: String?,
        startMs: Long,
        endMs: Long
    ): Map<Int, AppHistoryUidBytes>? {

        val nsm = applicationContext.getSystemService(Context.NETWORK_STATS_SERVICE)
            as? NetworkStatsManager ?: return null

        val stats = try {
            nsm.queryDetails(networkType, subscriberId, startMs, endMs)
        } catch (e: Throwable) {
            return null
        }

        val aggregated = HashMap<Int, LongArray>()   // uid -> [rx, tx]，只收 STATE_ALL
        val split = HashMap<Int, LongArray>()        // uid -> [rx, tx]，default / foreground 分桶

        try {

            val bucket = NetworkStats.Bucket()

            while (stats.hasNextBucket()) {

                stats.getNextBucket(bucket)

                val uid = bucket.uid

                if (uid < 0) continue

                val target =
                    if (bucket.state == NetworkStats.Bucket.STATE_ALL) aggregated else split

                val slot = target.getOrPut(uid) { LongArray(2) }

                slot[0] += bucket.rxBytes
                slot[1] += bucket.txBytes
            }

        } catch (e: Throwable) {
            // 读到一半出错：已经读到的桶仍然有用，不整体作废
        } finally {
            runCatching { stats.close() }
        }

        val result = HashMap<Int, AppHistoryUidBytes>(split.size + aggregated.size)

        split.forEach { (uid, v) -> result[uid] = AppHistoryUidBytes(v[0], v[1]) }

        // STATE_ALL 覆盖同名 uid 的分桶合计
        aggregated.forEach { (uid, v) -> result[uid] = AppHistoryUidBytes(v[0], v[1]) }

        return result
    }

    /**
     * 对一个 Android 用户做连接 / 注销 / 切换。
     *
     * 全部委托给子进程执行 `/system/bin/am`：Shizuku 提供 shell 身份，root 提供 uid 0。
     * 不在本进程里反射 ActivityManager —— 那只是把 Binder 调用身份继续留在普通应用 uid，
     * 必然被 INTERACT_ACROSS_USERS_FULL 拒绝。
     */
    override fun androidUserAction(action: AndroidUserAction, userId: Int): ProcessActionResult {

        if (userId < 0) {

            return ProcessActionResult(
                ok = false,
                reason = AppStrings.s(R.string.users_action_invalid_user)
            )
        }

        // 这里**不再**反射 ActivityManager.switchUser / stopUser / startUserInBackground。
        // UIService 无论用户选了什么身份，进程本身仍是普通应用 uid（本机 10423）；
        // Binder.getCallingUid() 看到的也是它，所以直调隐藏 API 只会拿到 SecurityException。
        // 用户操作统一交给子进程里的 `am` / `cmd activity`：
        // Shizuku 给 shell 身份，root 给 uid 0。
        val privileged = runAndroidUserActionPrivileged(action, userId)

        if (privileged == null) {

            return ProcessActionResult(
                ok = false,
                reason = AppStrings.s(R.string.users_action_no_privileged)
            )
        }

        if (privileged.ok) return privileged

        return ProcessActionResult(
            ok = false,
            reason = if (privileged.reason.isNotBlank()) {
                AppStrings.s(R.string.users_action_shell_failed, privileged.reason)
            } else {
                AppStrings.s(R.string.users_action_no_privileged)
            }
        )
    }

    /**
     * 用 Shell(adb) / root 身份执行用户切换。
     *
     * 为什么必须走 `am` / `cmd activity` 而不是再反射：`switchUser` / `stopUser` /
     * `startUserInBackground` 在 ActivityManagerService 里统一检查
     * `INTERACT_ACROSS_USERS_FULL`，普通应用拿不到；而 `com.android.shell` 在本机已被授予
     * `INTERACT_ACROSS_USERS_FULL` 和 `CREATE_USERS`，所以由 shell 身份启动的命令才能过权限检查。
     *
     * @return null = 两条提权通道都不可用；非 null = 命令已执行（可能成功或失败）
     */
    private fun runAndroidUserActionPrivileged(
        action: AndroidUserAction,
        userId: Int
    ): ProcessActionResult? {

        val prefixes = androidUserCommandPrefixes()

        var anyPrefixStarted = false
        var firstFailure: ProcessActionResult? = null

        for (prefix in prefixes) {

            val result = runAndroidUserActionWithPrefix(action, userId, prefix)

            if (result == null) continue

            anyPrefixStarted = true

            if (result.ok) return result

            if (firstFailure == null) firstFailure = result
        }

        return if (anyPrefixStarted) firstFailure else null
    }

    /**
     * 命令入口的两个方案：
     * 1. `/system/bin/am` —— 标准 AOSP 入口；
     * 2. `/system/bin/cmd activity` —— `am` 被 OEM 删掉 / 被 OEM 额外限制时的备用入口。
     *    它直接调用 activity 系统服务的 shell command，能力与 `am` 对齐。
     */
    private fun androidUserCommandPrefixes(): List<List<String>> {

        val prefixes = ArrayList<List<String>>()

        if (File("/system/bin/am").exists()) {
            prefixes.add(listOf("/system/bin/am"))
        }

        if (File("/system/bin/cmd").exists()) {
            prefixes.add(listOf("/system/bin/cmd", "activity"))
        }

        // 两个都探测不到也保留标准入口，让下面返回真实的执行错误，而不是静默无操作
        if (prefixes.isEmpty()) {
            prefixes.add(listOf("/system/bin/am"))
        }

        return prefixes
    }

    private fun runAndroidUserActionWithPrefix(
        action: AndroidUserAction,
        userId: Int,
        prefix: List<String>
    ): ProcessActionResult? {

        Log.i(
            USER_ACTION_TAG,
            "尝试入口 ${prefix.joinToString(" ")}，action=$action user=$userId"
        )

        when (action) {

            AndroidUserAction.CONNECT -> {

                // 先把目标用户拉起来；已经在运行的用户这条命令会报错，但不影响后面的切换
                val start = runPrivilegedCommand(
                    prefix + listOf("start-user", userId.toString()),
                    timeoutSeconds = 8
                ) ?: return null

                val switch = runPrivilegedCommand(
                    prefix + listOf("switch-user", userId.toString()),
                    timeoutSeconds = 8
                ) ?: return null

                return if (switch.ok) {
                    ProcessActionResult(ok = true)
                } else {
                    ProcessActionResult(
                        ok = false,
                        reason = switch.reason.ifBlank { start.reason }
                    )
                }
            }

            AndroidUserAction.STOP -> {

                val stop = runPrivilegedCommand(
                    prefix + listOf("stop-user", userId.toString()),
                    timeoutSeconds = 10
                ) ?: return null

                return if (stop.ok) {
                    ProcessActionResult(ok = true)
                } else {
                    ProcessActionResult(ok = false, reason = stop.reason)
                }
            }

            AndroidUserAction.SWITCH -> {

                val switch = runPrivilegedCommand(
                    prefix + listOf("switch-user", userId.toString()),
                    timeoutSeconds = 8
                ) ?: return null

                return if (switch.ok) {
                    ProcessActionResult(ok = true)
                } else {
                    ProcessActionResult(ok = false, reason = switch.reason)
                }
            }
        }
    }

    private data class PrivilegedCommandResult(
        val ok: Boolean,
        val reason: String = ""
    )

    /**
     * 跑一条提权命令：按用户当前身份决定先试 root 还是 Shizuku(shell)，一条失败再换另一条。
     *
     * root 不再用 `new ProcessBuilder("su","-c",...)` 起一次性进程：那会让 Magisk 每条命令
     * 都当成新进程再弹一次授权框。这里改成复用 RootManager 的长驻 root shell。
     */
    private fun runPrivilegedCommand(
        command: List<String>,
        timeoutSeconds: Long
    ): PrivilegedCommandResult? {

        val preferRoot = credentialIdentity == SettingsCatalog.CREDENTIAL_ROOT

        val channels = if (preferRoot) {
            listOf("root", "shizuku")
        } else {
            listOf("shizuku", "root")
        }

        var anyChannelStarted = false
        var firstFailure: PrivilegedCommandResult? = null

        for (channel in channels) {

            val result = if (channel == "root") {
                runRootPrivilegedCommand(command, timeoutSeconds)
            } else {
                runShizukuPrivilegedCommand(command, timeoutSeconds)
            }

            if (result == null) {
                Log.i(
                    USER_ACTION_TAG,
                    "通道 $channel 不可用，跳过：${command.joinToString(" ")}"
                )
                continue
            }

            anyChannelStarted = true

            Log.i(
                USER_ACTION_TAG,
                "通道 $channel 执行 ${command.joinToString(" ")} -> ok=${result.ok} " +
                    "reason=${result.reason}"
            )

            if (result.ok) return result

            if (firstFailure == null) firstFailure = result
        }

        return if (anyChannelStarted) firstFailure else null
    }

    private fun runShizukuPrivilegedCommand(
        command: List<String>,
        timeoutSeconds: Long
    ): PrivilegedCommandResult? {

        val process = try {

            ShizukuManager.newShellProcess(command)

        } catch (error: Throwable) {

            Log.i(
                USER_ACTION_TAG,
                "Shizuku 起进程异常：${error.javaClass.simpleName} ${error.message ?: ""}"
            )

            null
        } ?: return null

        return awaitPrivilegedCommand(process, timeoutSeconds)
    }

    private fun runRootPrivilegedCommand(
        command: List<String>,
        timeoutSeconds: Long
    ): PrivilegedCommandResult? {

        val result = try {

            RootManager.execCommandForced(command, timeoutSeconds * 1000L)

        } catch (error: Throwable) {

            Log.i(
                USER_ACTION_TAG,
                "root 长驻 shell 执行异常：${error.javaClass.simpleName} ${error.message ?: ""}"
            )

            null
        } ?: return null

        val output = result.output

        if (result.exitCode != 0) {
            return PrivilegedCommandResult(
                ok = false,
                reason = output.ifBlank { "exit ${result.exitCode}" }
            )
        }

        // `am` / `cmd` 有时即使失败也返回 0，错误只写在输出里
        val looksLikeError = output.contains("Error", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true) ||
            output.contains("Permission Denial", ignoreCase = true)

        return if (looksLikeError) {
            PrivilegedCommandResult(ok = false, reason = output)
        } else {
            PrivilegedCommandResult(ok = true)
        }
    }

    private fun awaitPrivilegedCommand(
        process: Process,
        timeoutSeconds: Long
    ): PrivilegedCommandResult {

        val finished = try {

            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        } catch (_: Throwable) {
            process.destroy()
            return PrivilegedCommandResult(
                ok = false,
                reason = AppStrings.s(R.string.users_action_shell_timeout)
            )
        }

        if (!finished) {

            process.destroy()

            return PrivilegedCommandResult(
                ok = false,
                reason = AppStrings.s(R.string.users_action_shell_timeout)
            )
        }

        val output = try {

            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }

            (out + "\n" + err).trim()

        } catch (_: Throwable) {
            ""
        }

        val exit = try {
            process.exitValue()
        } catch (_: Throwable) {
            -1
        }

        if (exit != 0) {
            return PrivilegedCommandResult(
                ok = false,
                reason = output.ifBlank { "exit $exit" }
            )
        }

        // `am` 有时即使失败也返回 0，错误只写在输出里
        val looksLikeError = output.contains("Error", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true) ||
            output.contains("Permission Denial", ignoreCase = true)

        return if (looksLikeError) {
            PrivilegedCommandResult(ok = false, reason = output)
        } else {
            PrivilegedCommandResult(ok = true)
        }
    }

    /** UserManager 里的一个用户：id + 名字 + 是否运行。 */
    private data class AndroidUserMeta(
        val id: Int,
        val name: String?,
        val running: Boolean
    )

    /**
     * 读 UserManager 的用户列表。`getUsers()` / `getUserInfo()` 都是隐藏 / SystemApi，
     * 反射拿不到就返回空列表——页面退回"只按进程 uid 列用户"，不编造账户。
     */
    private fun androidUserMetas(): List<AndroidUserMeta> {

        val manager = getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return emptyList()

        val raw = try {

            val method = UserManager::class.java.getDeclaredMethod("getUsers")

            method.isAccessible = true

            method.invoke(manager) as? List<*>

        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        return raw.mapNotNull { info ->

            if (info == null) return@mapNotNull null

            val id = userInfoField(info, "id") as? Int ?: return@mapNotNull null

            val name = userInfoField(info, "name") as? String

            AndroidUserMeta(
                id = id,
                name = name,
                running = isAndroidUserRunning(manager, id)
            )
        }
    }

    /** 读 UserInfo 的字段：先走 public field，再走 declared field（不同版本字段可见性不一样）。 */
    private fun userInfoField(info: Any, name: String): Any? {

        val type = info.javaClass

        return try {

            type.getField(name).get(info)

        } catch (_: Throwable) {

            try {

                type.getDeclaredField(name).apply { isAccessible = true }.get(info)

            } catch (_: Throwable) {
                null
            }
        }
    }

    /** `UserManager.isUserRunning(int)` 优先，再试 `isUserRunning(UserHandle)`。 */
    private fun isAndroidUserRunning(manager: UserManager, userId: Int): Boolean {

        try {

            val method = UserManager::class.java
                .getDeclaredMethod("isUserRunning", Int::class.javaPrimitiveType!!)

            method.isAccessible = true

            (method.invoke(manager, userId) as? Boolean)?.let { return it }

        } catch (_: Throwable) {
            // 换 UserHandle 版本再试
        }

        return try {

            val handle = UserHandle.getUserHandleForUid(userId * 100000)

            val method = UserManager::class.java
                .getDeclaredMethod("isUserRunning", UserHandle::class.java)

            method.isAccessible = true

            (method.invoke(manager, handle) as? Boolean) ?: false

        } catch (_: Throwable) {
            false
        }
    }

    /** 属主 uid -> Android 用户 id；未知属主（-1）保持 -1，界面单独显示"未知用户"。 */
    private fun androidUserIdOf(uid: Int): Int = when {

        uid < 0 -> -1

        // 系统 uid / 隔离进程（90000-99999）都属于 user 0
        uid < 100000 -> 0

        else -> uid / 100000
    }

    /** 按 uid 读累计网络字节（收发之和）；取不到返回 null（不拿 0 冒充）。 */
    private fun readUidNetworkBytes(uid: Int): Long? {

        // 未知属主（uid 没带上）不参与网络统计
        if (uid < 0) return null

        return try {

            val rx = TrafficStats.getUidRxBytes(uid)

            val tx = TrafficStats.getUidTxBytes(uid)

            // -1 = 该 uid 不支持统计（个别 uid / ROM）；返回 null，由界面显示「—」
            if (rx < 0L || tx < 0L) null else rx + tx

        } catch (_: Exception) {

            null
        }
    }

    /** 读等待链：t0..tN 依次取，缺一个就停（服务端按顺序输出）。 */
    override fun detailProcessWaitChain(pid: Int): ProcessWaitChain? {

        val packet = LinuxService.instanceOrNull()?.waitChain(pid, linuxRequestTimeoutMs) ?: return null

        if (!packet.isOk) return null

        val limit = packet.info["limit"]?.trim()?.toIntOrNull() ?: 0

        val entries = ArrayList<ProcessWaitEntry>()

        for (index in 0 until (limit.takeIf { it > 0 } ?: 64)) {

            val line = packet.info["t$index"] ?: break

            val parts = line.split('\t')

            val tid = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: continue

            entries += ProcessWaitEntry(
                tid = tid,
                state = parts.getOrNull(1)?.trim()?.firstOrNull() ?: '?',
                wchan = parts.getOrNull(2)?.trim().orEmpty().ifEmpty { "-" },
                syscall = parts.getOrNull(3)?.trim().orEmpty().ifEmpty { "-" },
                comm = parts.getOrNull(4)?.trim().orEmpty()
            )
        }

        return ProcessWaitChain(entries = entries, limit = limit)
    }

    /**
     * 写一份精简 native 内存转储。
     *
     * 落在**共享存储**的 Download 下（`/storage/emulated/<用户 id>/Download/…`），
     * 用户拔线用文件管理器就能拿到；`Environment.getExternalStorageDirectory()`
     * 在机主（用户 0）上就是 `/storage/emulated/0`，多用户 / 分身会自动给出对应目录，
     * 不把 0 写死。
     *
     * 后端以 root 身份写同一个路径，所以即使应用自己没拿到 MANAGE_EXTERNAL_STORAGE，
     * 只要是 root / adb 身份照样能落盘；不是 root 时写不进去，errno（EACCES/EPERM）
     * 原样回来，页面按统一口径弹「无法完成该操作。拒绝访问。」
     *
     * 大小直接用后端回包的 bytes，不靠 `File.length()`：Android 11+ 没拿到
     * 全盘权限时，应用读别的目录里的文件会被当成 0 字节，那会把成功的转储误报成空文件。
     */
    override fun dumpDetailProcessMemory(pid: Int, startTicks: Long): ProcessDumpResult {

        if (pid <= 0 || startTicks < 0L) {
            return ProcessDumpResult(false, reason = AppStrings.s(R.string.svc_no_start_time))
        }

        val linux = LinuxService.instanceOrNull()
            ?: return ProcessDumpResult(false, reason = AppStrings.s(R.string.svc_linux_not_ready))

        // 固定放在共享存储根下的 dump/（不是 Download）：目录由 root 后端在写之前自己建，
        // 应用这边不去 mkdir，也不去申请存储权限 —— shell / root 模式下不需要。
        val directory = File(Environment.getExternalStorageDirectory(), DUMP_DIRECTORY_NAME)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val file = File(directory, "taskmanager_${pid}_$stamp.dmp")

        val packet = linux.memoryDump(pid, startTicks, file.absolutePath, LinuxService.MEMORY_DUMP_TIMEOUT_MS)

        if (packet == null) {

            file.delete()

            return ProcessDumpResult(false, reason = AppStrings.s(R.string.svc_linux_no_reply))
        }

        if (!packet.isOk) {

            file.delete()

            val errno = packet.info["errno"]?.trim()?.toIntOrNull() ?: 1

            return ProcessDumpResult(
                ok = false,
                reason = errnoReason(errno),
                // EACCES / EPERM：写不进共享存储，普通权限模式下 UI 会再申请一次全盘访问后重试
                permissionDenied = errno == 1 || errno == 13
            )
        }

        return ProcessDumpResult(
            ok = true,
            path = file.absolutePath,
            bytes = packet.info["bytes"]?.trim()?.toLongOrNull() ?: 0L,
            segments = packet.info["segments"]?.trim()?.toIntOrNull() ?: 0
        )
    }

    /** 这一行能不能做"动手"的操作：pid > 0 且启动时刻已知。 */
    override fun detailProcessIsActionable(pid: Int, startTicks: Long): Boolean =
        pid > 0 && startTicks >= 0L

    /** 查这个包在 Android 侧登记的服务记录（阻塞，UI 必须放后台线程）。 */
    override fun detailProcessServices(pkg: String): String? =
        AndroidService.instanceOrNull()?.serviceRecords(pkg)

    /** 把"列布局 + 列宽 + 范围 + 忽略开关"整份写盘（后台线程，几十字节）。 */
    private fun saveDetailViewState() {

        val ids = detailVisibleIds.toSet()
        val scope = detailProcessScope
        val ignore = detailIgnoreUpdateSpeed

        val widths = synchronized(detailLock) { detailColumnWidths.toMap() }

        detailViewSaveExecutor.execute {
            DetailViewStore.save(this, ids, scope, ignore, widths)
        }
    }

    /**
     * 立刻采一次详细信息的快照并铺进表。
     *
     * 与 [refreshActivePage] 的区别：它不看"页面在不在前台"、也不看忽略开关，
     * 就是一次按需采集。防重入仍由 [engineRefreshRunning] 兜住，连点两次不会发两包。
     */
    private fun requestDetailRefreshOnce(): Boolean {

        if (LinuxService.instanceOrNull() == null) return false

        linuxPollExecutor.execute {

            if (!engineRefreshRunning.compareAndSet(false, true)) return@execute

            try {
                val mask = detailDemandMask()

                if (mask == LinuxField.NONE) return@execute

                val packet = GetDataFromLinuxSvc(mask)

                if (packet?.isOk != true) return@execute

                synchronized(detailLock) {

                    // 与定时刷新同一条规则：表里有手工填的行（调试页的 Test 行）时不覆盖
                    if (detailRows.isNotEmpty() && !detailRowsFromSnapshot) return@synchronized

                    detailRowsFromSnapshot = true

                    replaceProcessRows(linuxSnapshotToRows(packet))
                }
            } finally {
                engineRefreshRunning.set(false)
            }
        }

        return true
    }

    /**
     * [调试页专用] 一次加入 Test0 ~ Test10 共 11 行测试进程。
     *
     * 名称 TestN，除名称外的所有统计值都填 N —— 这样每一列都能看到"由小到大"的差别，
     * 点任意表头排序、勾选/取消列、横向滑动都立刻有可观察的效果。
     *
     * 行标识（ProcessRow.pid）用 9000 + N：必须为正且唯一；
     * 而"PID"列的显示值仍按约定填 N（序号），两者刻意分开，见接口上的说明。
     */
    override fun appendDetailTestRows(): Int {

        var added = 0

        synchronized(detailLock) {

            for (index in 0..10) {

                val values = LinkedHashMap<String, String>(detailColumns.size)

                detailColumns.forEach { column ->
                    values[column.id] = if (column.id == DetailColumnId.NAME) {
                        "Test$index"
                    } else {
                        index.toString()
                    }
                }

                val row = ProcessRow(
                    pid = 9000 + index,
                    name = "Test$index",
                    values = values,
                    // 测试行也给一个唯一启动时刻：与真实快照一样，行身份靠 pid + startTicks
                    startTicks = 1000L + index
                )

                val existing = detailRows.indexOfFirst { it.pid == row.pid }

                if (existing >= 0) {
                    detailRows[existing] = row
                } else {
                    detailRows.add(row)
                    added++
                }
            }

            detailNote = AppStrings.s(R.string.svc_test_rows_note)
        }

        return added
    }

    /**
     * 点了表头某一列：记下"按它排、由高到低"。真正重排在下一次刷新/下一次取快照时生效。
     *
     * 同一列再点一次就翻转升降序 —— 这是 Windows 任务管理器的手感，用户不用再找一个"切换"按钮。
     */
    override fun sortDetailTableBy(columnId: String?, descending: Boolean): Boolean {

        synchronized(detailLock) {

            if (columnId == null) {
                detailSortColumnId = null
                detailSortDescending = true

                return true
            }

            if (detailColumns.none { it.id == columnId }) return false

            // 隐藏的列不允许当排序依据：表里根本看不到它，排出来的顺序无法解释
            if (columnId !in detailVisibleIds) return false

            if (detailSortColumnId == columnId) {
                detailSortDescending = !detailSortDescending
            } else {
                detailSortColumnId = columnId
                detailSortDescending = descending
            }
        }

        return true
    }

    /** 选中某一个进程（点行调用）；传 null 取消选中。 */
    override fun selectDetailRow(pid: Int?, startTicks: Long) {

        synchronized(detailLock) {

            detailSelectedPid = pid

            // 只有真的给了启动时刻才记录（0 是合法值，init 就是 0；-1 = 这行没有 start_ticks）
            detailSelectedStartTicks = if (pid != null && startTicks >= 0L) startTicks else null
        }
    }

    /**
     * 长按某一行 / 某一格时由页面调用：这里只记录"用户长按了哪个进程"。
     *
     * 页面自己弹测试对话框没问题；但选中状态与后续操作必须能从这里追溯，
     * 将来真正的"结束进程 / 查看属性"入口会从这里拿 pid + startTicks，页面不再自己攒一份。
     */
    override fun onDetailRowLongPressed(pid: Int, startTicks: Long) {

        synchronized(detailLock) {

            detailLongPressedPid = pid

            detailLongPressedStartTicks = if (startTicks >= 0L) startTicks else null
        }

        Log.i(TAG, "详细信息页长按进程 pid=$pid startTicks=$startTicks")
    }

    /**
     * 判断"表里这一行是不是选中的那个进程"。
     *
     * 规则：pid 必须相同；若双方都有 startTicks，还必须相同。
     * startTicks 只在一方有值时不做否定判断（否则测试数据/旧快照会永远匹配不上）。
     * 这里用 -1 表示"没有"，0 是合法值（init 的 starttime 就是 0）。
     */
    private fun isSameProcess(row: ProcessRow, pid: Int, startTicks: Long?): Boolean {

        if (row.pid != pid) return false

        if (startTicks == null || startTicks < 0L) return true

        return row.startTicks < 0L || row.startTicks == startTicks
    }

    /**
     * 排序比较：数值列按 double 比；文本列按"自然顺序"比（数字段按数值大小）。
     *
     * 为什么不直接用字符串比较：`Test10` 的字符串序在 `Test9` 之前、
     * `100` 也会排在 `99` 前（"1" < "9"），看起来就是"10 比 9 小"，完全不对。
     *
     * 规矩：
     * - 数值列：`toDoubleOrNull()` 比大小；取不到数值的（空串 / 非数字）**一律排最后**，
     *   且不受升降序影响（否则"降序时空值跑最前"很怪）。
     * - 文本列：自然顺序 —— 把字符串切成"文本段 / 数字段"，数字段按数值比
     *   （先比去掉前导零后的位数，再逐位比，等价于任意长度整数的比较，不会溢出）；
     *   文本段按字符比（忽略大小写，避免 Android 的 abc/ABC 混排看着乱）。
     * - 值相同时按行标识 pid 升序做次级比较：列表顺序稳定，每次刷新不会跳来跳去。
     *   注意这个 tie-break 只在"值真的相等"时生效，且**不跟随升降序反转**。
     */
    private fun compareDetailRows(
        left: ProcessRow,
        right: ProcessRow,
        columnId: String,
        numeric: Boolean,
        descending: Boolean
    ): Int {

        val leftText = left.values[columnId]
        val rightText = right.values[columnId]

        if (numeric) {

            val leftNumber = leftText?.toDoubleOrNull()
            val rightNumber = rightText?.toDoubleOrNull()

            // 空值 / 非数值：始终垫底，与升降序无关
            if (leftNumber == null && rightNumber == null) return left.pid.compareTo(right.pid)
            if (leftNumber == null) return 1
            if (rightNumber == null) return -1

            val compared = leftNumber.compareTo(rightNumber)

            if (compared != 0) return if (descending) -compared else compared

        } else {

            val compared = compareNaturalText(leftText.orEmpty(), rightText.orEmpty())

            if (compared != 0) return if (descending) -compared else compared
        }

        return left.pid.compareTo(right.pid)
    }

    /**
     * 自然顺序比较（人类读法）：`Test2 < Test10`、`abc99 < abc100`、`4096 < 10000`。
     *
     * 数字段不转成 Int/Long（会溢出），而是：
     * 去掉前导零 -> 比位数 -> 位数相同逐字符比。位数多的一定更大，等价于大整数比较。
     */
    private fun compareNaturalText(left: String, right: String): Int {

        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < left.length && rightIndex < right.length) {

            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]

            if (leftChar.isDigit() && rightChar.isDigit()) {

                var leftEnd = leftIndex
                while (leftEnd < left.length && left[leftEnd].isDigit()) leftEnd++

                var rightEnd = rightIndex
                while (rightEnd < right.length && right[rightEnd].isDigit()) rightEnd++

                // 跳过前导零："007" 与 "7" 视为同一个数
                var leftStart = leftIndex
                while (leftStart < leftEnd && left[leftStart] == '0') leftStart++

                var rightStart = rightIndex
                while (rightStart < rightEnd && right[rightStart] == '0') rightStart++

                val leftDigits = leftEnd - leftStart
                val rightDigits = rightEnd - rightStart

                if (leftDigits != rightDigits) return if (leftDigits < rightDigits) -1 else 1

                // 位数相同：逐位比就是数值比（只比有效数字部分）
                for (offset in 0 until leftDigits) {

                    val compared = left[leftStart + offset].compareTo(right[rightStart + offset])

                    if (compared != 0) return if (compared < 0) -1 else 1
                }

                leftIndex = leftEnd
                rightIndex = rightEnd

            } else {

                // 文本段按字符比（统一小写，避免 abc / ABC 混排看着乱）
                val compared = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())

                if (compared != 0) return if (compared < 0) -1 else 1

                leftIndex++
                rightIndex++
            }
        }

        // 一方是另一方的前缀：短的排在前面
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }

    /** 排序：数值列按 double 比（取不到数值的排最后），文本列按自然顺序比。 */
    private fun sortDetailRows(
        rows: List<ProcessRow>,
        columnId: String,
        descending: Boolean
    ): List<ProcessRow> {

        val numeric = detailColumns.firstOrNull { it.id == columnId }?.numeric ?: true

        // 升降序在比较函数内部处理，不用 asReversed()：
        // asReversed 会把"值相同时按 pid 升序"的次级次序也一起翻掉。
        val comparator = Comparator<ProcessRow> { left, right ->
            compareDetailRows(left, right, columnId, numeric, descending)
        }

        return rows.sortedWith(comparator)
    }
}

