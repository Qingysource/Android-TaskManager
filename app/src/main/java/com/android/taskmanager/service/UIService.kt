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
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import androidx.compose.runtime.mutableStateOf

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
import com.android.taskmanager.DetailTable
import com.android.taskmanager.MainActivity
import com.android.taskmanager.PermissionRequestResult
import com.android.taskmanager.ProcessRow
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
import com.android.taskmanager.data.LinuxService
import com.android.taskmanager.data.LinuxControlCode
import com.android.taskmanager.data.LinuxField
import com.android.taskmanager.data.LinuxPacket
import com.android.taskmanager.data.LinuxPacketStatus
import com.android.taskmanager.data.LinuxProcessRecord
import com.android.taskmanager.data.LinuxProtocol
import com.android.taskmanager.data.CpuUsagePoint
import com.android.taskmanager.data.LinuxRequestType
import com.android.taskmanager.data.PerformanceSnapshot
import com.android.taskmanager.data.ProcessScope

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

        /** 详细信息页的页名（与 MainActivity.navigationTitle 同一套文案）。 */
        private const val PAGE_DETAIL = "详细信息"

        /** 性能页的页名（与 MainActivity.navigationTitle 同一套文案）。 */
        private const val PAGE_PERFORMANCE = "性能"

        /**
         * CPU 历史点的容量上限。
         *
         * 最长窗口 = 低（240 秒）、最短采样间隔 = 高（0.5 秒），480 点已经够铺满整个窗口；
         * 取 512 是为了留一点余量，不至于因为某次调度抖动多出两三个点就把最早的点挤掉。
         */
        private const val CPU_HISTORY_MAX_POINTS = 512

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
         * [OVERLAY-v1] 浮窗显示期间"权限还在不在"的巡检间隔。
         *
         * 只有浮窗真正挂着时才跑（收窗即停），2 秒一次 appops 查询的开销可以忽略。
         */
        private const val OVERLAY_PERMISSION_CHECK_MS = 2_000L

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

        // 3.6 用户凭据：把真实授权状态推一次给界面（设置页"用户凭据"分组的按钮据此显示）
        //     之后每次 credentialSnapshot() 调用都会再推，服务侧一发现授权变化，界面自然跟着变。
        credentialSnapshot()
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
    //   状态：CPU 这一组已接真实数据；内存 / 磁盘 / WLAN / GPU 仍是页面里的测试值
    //
    //   两个"谁负责"的结论（页面不要抢）：
    //   1) 采集节奏：本服务的定时器（间隔 = 设置页的实时更新速度），页面不自己计时；
    //   2) 图表窗口文案：本服务按实时更新速度换算（高 30 / 常规 60 / 低 240 / 已暂停），
    //      页面只在切进来时取一次。

    /**
     * 性能页最近一帧。
     *
     * 用 Compose 的 mutableState 装：页面在 composition 里读它，写一次就会重组一次，
     * 页面因此不需要自己轮询、也不需要自己拼刷新间隔。
     */
    private val performanceSnapshotState =
        androidx.compose.runtime.mutableStateOf(PerformanceSnapshot.EMPTY)

    /**
     * 最近一次采到的帧（不管性能页看不看得见都留着）。
     *
     * 切回性能页时先把它推上去，用户不用空等下一拍；不可见期间也不写 state，
     * 免得后台每秒都让一个没人在看的页面重组一次。
     */
    @Volatile
    private var performanceLastSnapshot: PerformanceSnapshot? = null

    /**
     * CPU 利用率的历史点，大图表与左列迷你图**共用这一份**。
     *
     * 容量按"最长窗口"算：低 = 240 秒，最短采样间隔 = 高 0.5 秒，
     * 240 / 0.5 = 480 点，取 512 留余量。超过就丢最老的（环形）。
     *
     * 只在采集线程（ui-linux-poll）写；页面只读 [performanceHistoryState]，
     * 所以这个队列不上锁也不会被并发改。
     */
    private val cpuUsageHistory = kotlin.collections.ArrayDeque<CpuUsagePoint>()

    /**
     * 推给页面的那一份（已按当前窗口裁剪）。
     *
     * 用 state 而不是让页面自己去读队列：写一次才重组一次，
     * 和 [performanceSnapshotState] 同拍，两个图的横轴始终对齐。
     */
    private val performanceHistoryState =
        androidx.compose.runtime.mutableStateOf<List<CpuUsagePoint>>(emptyList())

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
        DetailColumn(DetailColumnId.NAME, "名称", numeric = false, width = 180),
        DetailColumn(DetailColumnId.PACKAGE_NAME, "程序包名称", numeric = false, width = 160),
        DetailColumn(DetailColumnId.PID, "PID", width = 72),
        DetailColumn(DetailColumnId.STATE, "状态", numeric = false, width = 88),
        DetailColumn(DetailColumnId.USER, "用户名", numeric = false, width = 120),
        DetailColumn(DetailColumnId.SESSION, "会话ID", width = 80),
        DetailColumn(DetailColumnId.PGID, "进程组ID", width = 88),
        DetailColumn(DetailColumnId.CGROUP, "cgroup标识", numeric = false, width = 180),
        DetailColumn(DetailColumnId.CPU_USAGE, "CPU使用率", width = 96),
        DetailColumn(DetailColumnId.CPU_TIME, "CPU时间", width = 96),
        DetailColumn(DetailColumnId.CPU_CYCLES, "CPU周期数", width = 104),
        DetailColumn(DetailColumnId.RSS, "常驻内存大小", width = 120),
        DetailColumn(DetailColumnId.PEAK_RSS, "峰值常驻内存", width = 120),
        DetailColumn(DetailColumnId.RSS_DELTA, "常驻内存增量", width = 120),
        DetailColumn(DetailColumnId.ACTIVE_PRIVATE_RSS, "活动私有常驻内存", width = 136),
        DetailColumn(DetailColumnId.PRIVATE_RSS, "私有常驻内存", width = 120),
        DetailColumn(DetailColumnId.SHARED_RSS, "共享常驻内存", width = 120),
        DetailColumn(DetailColumnId.VSS, "虚拟内存大小", width = 120),
        DetailColumn(DetailColumnId.PAGE_FAULTS, "页面错误总数", width = 120),
        DetailColumn(DetailColumnId.PAGE_FAULTS_DELTA, "页面错误增量", width = 120),
        DetailColumn(DetailColumnId.NICE, "基本优先级(Linux)", width = 136),
        DetailColumn(DetailColumnId.ANDROID_PRIORITY, "基本优先级(Android)", width = 144),
        DetailColumn(DetailColumnId.FD_COUNT, "文件描述符数", width = 112),
        DetailColumn(DetailColumnId.THREAD_COUNT, "线程数", width = 80),
        DetailColumn(DetailColumnId.IO_READ_COUNT, "I/O读取次数", width = 104),
        DetailColumn(DetailColumnId.IO_WRITE_COUNT, "I/O写入次数", width = 104),
        DetailColumn(DetailColumnId.IO_READ_BYTES, "I/O读取字节", width = 112),
        DetailColumn(DetailColumnId.IO_WRITE_BYTES, "I/O写入字节", width = 112),
        DetailColumn(DetailColumnId.EXE_PATH, "路径名称", numeric = false, width = 220),
        DetailColumn(DetailColumnId.CMDLINE, "命令行", numeric = false, width = 220),
        DetailColumn(DetailColumnId.SELINUX, "安全上下文", numeric = false, width = 160),
        DetailColumn(DetailColumnId.TARGET_SDK, "目标API版本", width = 120),
        DetailColumn(DetailColumnId.PLATFORM, "平台", numeric = false, width = 80),
        DetailColumn(DetailColumnId.ARCH, "体系架构", numeric = false, width = 96),
        DetailColumn(DetailColumnId.IS_ADMIN, "管理员特权", numeric = false, width = 104),
        DetailColumn(DetailColumnId.DESCRIPTION, "描述", numeric = false, width = 160),
        DetailColumn(DetailColumnId.NX_PROTECT, "NX保护", numeric = false, width = 96),
        DetailColumn(DetailColumnId.GPU_USAGE, "GPU使用率", width = 96),
        DetailColumn(DetailColumnId.GPU_ENGINE, "GPU引擎", numeric = false, width = 96),
        DetailColumn(DetailColumnId.GPU_DEDICATED_MEM, "专用GPU内存", width = 112),
        DetailColumn(DetailColumnId.GPU_SHARED_MEM, "共享GPU内存", width = 112),
        DetailColumn(DetailColumnId.CET_PROTECT, "CET控制流强制保护", numeric = false, width = 156),
        DetailColumn(DetailColumnId.ISOLATION, "隔离状态", numeric = false, width = 96)
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

        prefs.visibleIds
    }

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
    private var detailNote: String = "等待上游填入进程（调试页可一键加入 Test0 ~ Test10）"

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

    /** 浮窗宿主。懒加载：没开浮窗时不创建窗口管理器相关对象。 */
    private val overlayHost: OverlayHost by lazy { OverlayHost(this) }

    /** 浮窗当前显示哪一页。必须是 Compose 状态，切页才会触发重组。 */
    private val overlayPageState = mutableStateOf("进程")

    /**
     * 浮窗当前是否真的挂在屏幕上。
     *
     * 单独记一个布尔，而不是每次去问 overlayHost.isShowing：
     * 通知文案要读它，读 overlayHost 会顺带把那一坨窗口管理器对象创建出来。
     */
    private var overlayVisible = false

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
                "后台运行",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "任务管理器数据中枢的常驻通知（关闭界面后仍在采集数据）"
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
                if (showingOverlay) "任务管理器浮窗运行中" else "任务管理器后台运行中"
            )
            .setContentText(
                if (showingOverlay) {
                    "正在其他应用上方显示监控面板"
                } else {
                    "正在采集进程数据，关闭界面后仍会继续"
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    /**
     * [OVERLAY-v1] 浮窗权限巡检：确认"窗口还挂着，但权限已经被收回"。
     *
     * 为什么需要它：用户在系统设置里关掉"显示在其他应用上层"时，
     * 系统会把我们的浮窗撤掉、但**不会通知应用**；如果进程还活着，
     * isShowing 仍是 true、设置里也还是 true，用户下次启动就会看到
     * "置顶明明开着，却没有浮窗"。所以边显示边巡检，一发现就当场回退。
     */
    private val overlayWatchdogHandler = Handler(Looper.getMainLooper())

    private val overlayWatchdog = object : Runnable {
        override fun run() {
            // 窗已经收了：巡检自然结束，不要留下一个空转的定时任务
            if (!overlayHost.isShowing) return

            if (!hasOverlayPermission()) {
                onOverlayPermissionLost("巡检")

                return
            }

            overlayWatchdogHandler.postDelayed(this, OVERLAY_PERMISSION_CHECK_MS)
        }
    }

    /** 开始巡检（重复调用不会叠加多个定时任务）。 */
    private fun startOverlayWatchdog() {
        overlayWatchdogHandler.removeCallbacks(overlayWatchdog)

        overlayWatchdogHandler.postDelayed(overlayWatchdog, OVERLAY_PERMISSION_CHECK_MS)
    }

    /** 停止巡检（收窗 / 服务销毁时都要调，避免 Handler 持有已废弃的实例）。 */
    private fun stopOverlayWatchdog() {
        overlayWatchdogHandler.removeCallbacks(overlayWatchdog)
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

        stopOverlayWatchdog()

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

        // 切到性能页：先把后台已经采到的那一帧推上去，别让用户对着一屏破折号等下一拍。
        // 这里 force = true：切页上报与 Activity 的前台状态未必同一时刻到位。
        if (next == PAGE_PERFORMANCE) {
            performanceLastSnapshot?.let { pushPerformanceSnapshot(it, force = true) }
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
     * 名称列固定并进来：它是每一行的标题（[ProcessRow.name]），
     * 用户没勾它也必须有，否则行标题只能退化成 "pid 123"。
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
     * 采样通道的规矩，两条别混：
     * - **采**（这里）：不受前台、不受是否选中约束。性能页要的是一段连续的历史，
     *   切页、退到后台都不能断，断了切回来就少一段。
     * - **画**（[pushPerformanceSnapshot]）：只看页面可见不可见。看不见就不写可观察状态，
     *   后台每秒让一个没人在看的组合树重组一次纯属白烧电。
     *
     * "已暂停"（间隔 0）时一次都不采：暂停就是连后台也别动，别偷偷发请求。
     */
    private fun refreshPerformanceStatsIfEnabled() {

        if (pollIntervalMs(settingsSnapshot.updateSpeed) <= 0L) return

        val linux = LinuxService.instanceOrNull() ?: return

        val snapshot = linux.deviceSnapshot(linuxRequestTimeoutMs) ?: return

        // 采样时刻在这里取：ELF 那边只回"当前值"，时间轴由本服务自己的点来排，
        // 不依赖跨进程的时钟，也不受一次往返耗时的抖动影响。
        appendCpuUsagePoint(snapshot, SystemClock.elapsedRealtime())

        performanceLastSnapshot = snapshot

        pushPerformanceSnapshot(snapshot)
    }

    /**
     * 往历史队列里追加一个点。
     *
     * 利用率拿不到（第一帧的 NO_BASELINE、或读数失败）就不入队：
     * 断点插一个 0 会在曲线上画出一道根本不存在的陡降。
     */
    private fun appendCpuUsagePoint(snapshot: PerformanceSnapshot, atMs: Long) {

        val percent = snapshot.cpuUsagePercent ?: return

        cpuUsageHistory.addLast(CpuUsagePoint(atMs, percent))

        while (cpuUsageHistory.size > CPU_HISTORY_MAX_POINTS) {
            cpuUsageHistory.removeFirst()
        }
    }

    /**
     * 按当前窗口裁剪历史点。
     *
     * 已暂停（窗口 0）时**不裁**：暂停只是不再采新的，用户切回来仍该看到暂停前那段曲线；
     * 裁成空的等于把数据藏起来，没有必要。
     */
    private fun trimmedCpuHistory(): List<CpuUsagePoint> {

        val points = cpuUsageHistory.toList()

        if (points.isEmpty()) return points

        val windowMs = chartWindowSeconds(settingsSnapshot.updateSpeed) * 1000L

        if (windowMs <= 0L) return points

        val newest = points.last().timeMs

        val cutoff = newest - windowMs

        val start = points.indexOfFirst { it.timeMs >= cutoff }

        return if (start <= 0) points else points.subList(start, points.size)
    }

    /**
     * 把一帧推到界面。
     *
     * @param force true = 不看可见性直接写（用在"用户刚切进性能页"这一下）：
     *              切页上报与 Activity 的前台回调谁先到不确定，这时候等可见性成立
     *              会让用户先看到一屏空数据。
     */
    private fun pushPerformanceSnapshot(snapshot: PerformanceSnapshot, force: Boolean = false) {

        if (!force && !isPerformancePageVisible()) return

        // 时间戳每次都要换：同一个时间戳的两帧在 Compose 眼里是同一个值，不会重组
        performanceSnapshotState.value =
            snapshot.copy(receivedAtMs = SystemClock.elapsedRealtime())

        // 历史点与当前值同一拍推：两个图共用这一份，横轴不会错位
        performanceHistoryState.value = trimmedCpuHistory()
    }

    /** 性能页现在是否看得见：主界面在前台并选中它，或浮窗正停在它上面。 */
    private fun isPerformancePageVisible(): Boolean =
        isUiRefreshAllowed() && effectiveActivePage() == PAGE_PERFORMANCE

    /**
     * 实时更新速度 -> 图表窗口秒数。
     *
     *   高 30 秒 / 常规 60 秒 / 低 4 分钟（240 秒）/ 已暂停 0
     *
     * 未知文案按"常规"处理：配置被手改过时也不至于把窗口算成 0。
     */
    internal fun chartWindowSeconds(speed: String): Int = when (speed) {
        "高" -> 30
        "常规" -> 60
        "低" -> 240
        "已暂停" -> 0
        else -> 60
    }

    /**
     * 实时更新速度 -> 图表标签行文案。
     *
     * 暂停时整段换成"利用率 % (已暂停)"：这时候没有窗口可言，写成 0 秒以内的利用率
     * 反而像"窗口是 0"，容易读错。
     */
    internal fun performanceChartWindowLabel(speed: String): String {

        val seconds = chartWindowSeconds(speed)

        if (seconds <= 0) return "利用率 % (已暂停)"

        return "$seconds 秒以内的利用率%"
    }

    override fun performanceSnapshot(): PerformanceSnapshot = performanceSnapshotState.value

    override fun performanceCpuHistory(): List<CpuUsagePoint> = performanceHistoryState.value

    override fun performanceChartWindowLabel(): String =
        performanceChartWindowLabel(settingsSnapshot.updateSpeed)

    override fun refreshPerformanceNow(): Boolean {

        // 已暂停：用户要求不自动更新，这里也别偷偷采一帧
        if (pollIntervalMs(settingsSnapshot.updateSpeed) <= 0L) return false

        if (LinuxService.instanceOrNull() == null) return false

        // 已经有一轮在跑：那一轮结束时自然会把最新帧推上来，这里不排队
        if (!engineRefreshRunning.compareAndSet(false, true)) return true

        linuxPollExecutor.execute {
            try {
                refreshPerformanceStatsIfEnabled()
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
            ?: return "Linux 服务尚未就绪（UIService 正在启动上游）"

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
        val linux = LinuxService.instanceOrNull() ?: return "未启动"

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
    fun linuxSnapshotToRows(packet: LinuxPacket): List<ProcessRow> =

        packet.processes.map { record ->

            val values = LinkedHashMap<String, String>(detailColumns.size)

            detailColumns.forEach { column ->
                values[column.id] = record.displayOf(column.id)
            }

            // Android 侧字段的接入点（现在什么都不做，见 enrichWithAndroid）
            enrichWithAndroid(values, record)

            ProcessRow(
                pid = record.pid,
                name = record.fieldOf(DetailColumnId.NAME) ?: "pid ${record.pid}",
                values = values,
                // 启动时刻：-1 = 后端没给这个字段（不能拿 0 顶替，0 是合法值：init 的 starttime 就是 0）
                startTicks = record.startTicks ?: -1L
            )
        }

    /**
     * 把 Android 服务才能拿到的字段并进一行。
     *
     * **当前是空实现**：AndroidService 还没有这套查询，这五格保持 Linux 侧给的
     * `!NOT_AVAILABLE`（不是 0，也不是空白）——"没有这个数据"与"值是 0"必须分得开。
     *
     * 将来在这里按 pid / uid 批量向 AndroidService 要
     * package_name / target_sdk / description / android_priority / isolation，
     * 覆盖 values 里对应的键即可：表结构、排序、列选择都不用改。
     */
    private fun enrichWithAndroid(values: MutableMap<String, String>, record: LinuxProcessRecord) {
        // TODO(Android 补字段)：批量查询后覆盖 values；
        //  未实现前保留 Linux 侧的状态标记，别在这里填 "—" 或 "0" 冒充有值。
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
            // 按约定是"重启应用后由 MainActivity 以浮窗启动"
            SettingKey.WINDOW_ALWAYS_ON_TOP -> {
                if (!next.windowAlwaysOnTop) {
                    val wasShowing = overlayHost.isShowing

                    // [OVERLAY-v1] 在浮窗里关掉置顶：先把浮窗当前页记下来，
                    // 回普通界面时"回到原处"（与点"退出"是同一套一次性记录）
                    if (wasShowing) {
                        SettingsStore.update(this, SettingKey.LAST_PAGE, overlayPageState.value)
                    }

                    hideOverlay()

                    // 在浮窗里把"置于顶层"关掉：窗已经收了，此时用户会停在桌面上，
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

    /** 直达本应用那一项的悬浮窗设置页。 */
    override fun overlayPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )

    /** 浮窗是否正在显示。 */
    override val isOverlayShowing: Boolean
        get() = overlayHost.isShowing

    /**
     * 显示浮窗：现有页面 → 一个近乎铺满屏幕的窗口。
     *
     * 顺序：查权限 -> 挂窗口 -> 建窗成功后把常驻通知换成"浮窗运行中"文案。
     * （前台身份在服务创建时就拿到了，这里不再依赖"进前台"，所以建窗失败也不必收回前台。）
     */
    override fun showOverlay(page: String): Boolean {
        if (!hasOverlayPermission()) {
            Log.w(TAG, "showOverlay: 没有悬浮窗权限，放弃建窗")

            // 走到这里说明"设置说要置顶、但权限已经没了"（启动那一刻刚好被收回）。
            // 必须把设置改回关闭，否则每次启动都会白白走一遍这个分支，
            // 设置页还会一直显示"已开启"。
            settingsSnapshot = SettingsStore.update(this, SettingKey.WINDOW_ALWAYS_ON_TOP, false)

            return false
        }

        overlayPageState.value = page.ifBlank { settingsSnapshot.defaultStartPage }

        // 浮窗一出来它就是"前台页"：刷新调度按它的页名走
        setActivePage(overlayPageState.value)

        // 已经有一只窗（例如重启前没来得及收掉的）：先收掉，保证这次是干净重建
        if (overlayHost.isShowing) overlayHost.hide()

        // 建窗过程中先按"还没显示"算，成功后再置位（通知文案跟着它）
        overlayVisible = false

        Log.i(TAG, "showOverlay: 开始建窗 page=${overlayPageState.value}")

        val shown = try {
            overlayHost.show(
                bounds = overlayHost.defaultBounds(),
                onClose = { exitOverlayMode() },
                content = { actions ->
                    OverlayPageFrame(
                        pageName = overlayPageState.value,
                        // 浮窗里的"设置"页：读当前快照、写回 UIService（由它统一落盘）
                        settingsProvider = { settingsSnapshot },
                        onSettingChanged = { key, value -> onSettingChanged(key, value) },
                        actions = actions,
                        onPageSelected = { page ->
                            overlayPageState.value = page

                            // 浮窗切页签与主界面切页是同一件事：前台页变了，刷新目标也跟着变
                            setActivePage(page)
                        }
                    )
                }
            )
        } catch (e: Throwable) {
            // 这里是"浮窗建不起来"的唯一证据来源：BadTokenException / SecurityException
            // / IllegalArgumentException（type 不被允许）都会落在这里
            Log.e(TAG, "showOverlay: 建窗失败 page=$page", e)

            false
        }

        if (!shown) {
            overlayVisible = false

            refreshServiceNotification()

            // 建窗失败也要把设置改回关闭：否则用户会停留在"置顶开着却没有浮窗"的
            // 假状态，下次启动还会再试一遍（而且很可能再失败一次）。
            settingsSnapshot = SettingsStore.update(this, SettingKey.WINDOW_ALWAYS_ON_TOP, false)

            // 浮窗没建起来（addView 被系统拒绝 / 权限刚被收回）：退回普通界面。
            // 否则 Activity 已经 finish()，用户会面对一片空屏，看起来就是"应用退出了"。
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_FORCE_NORMAL, true)
                )
            }
        }

        if (shown) {
            // 浮窗真的挂上了：通知换成"浮窗运行中"（服务本身已经是前台，只是换文案）
            overlayVisible = true

            // 浮窗可见 = 允许驱动页面内容：门禁条件刚变，立刻重排
            reschedulePolling()

            refreshServiceNotification()

            // [OVERLAY-v1] 只有窗口真挂上了，"回原位"这条一次性记录才算被消费。
            // 建窗失败会走上面的兜底启动，那条记录必须留着——
            // 否则兜底出来的普通界面会落到默认页（进程），看起来就是"闪回进程页"。
            settingsSnapshot = SettingsStore.update(this, SettingKey.LAST_PAGE, "")

            // 窗口活着期间盯着权限：用户在系统设置里关掉悬浮窗权限时，
            // 系统只撤窗、不通知应用，只能靠自检（见 overlayWatchdog 上的说明）。
            startOverlayWatchdog()

            Log.i(TAG, "showOverlay: 浮窗已显示，page=${overlayPageState.value}")
        }

        return shown
    }

    /** 关闭浮窗（幂等，不改设置）。服务本身继续常驻前台，只把通知文案换回"后台运行中"。 */
    override fun hideOverlay() {
        // 窗收了就不必再巡检权限（也防止 Handler 继续持有这个实例）
        stopOverlayWatchdog()

        overlayHost.hide()

        overlayVisible = false

        refreshServiceNotification()

        // 浮窗收了：如果主界面也不在前台，就该停止驱动页面内容（重排一次让门禁立刻生效）
        reschedulePolling()
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
            ?: "Android 服务尚未就绪（UIService 正在启动上游）",
        linux = LinuxService.instanceOrNull()?.recentOutput()
            ?: "Linux 服务尚未就绪（UIService 正在启动上游）"
    )

    // ---------- IPageDataApi：详细信息页（进程属性表） ----------

    /**
     * 取整张表的快照。
     *
     * 可见列在这里过滤一次就够：页面拿到多少列，表就多宽；页面不需要知道还有哪些列被藏了。
     */
    override fun detailTable(): DetailTable {

        synchronized(detailLock) {

            val visibleColumns = detailColumns.filter { it.id in detailVisibleIds }

            val sortId = detailSortColumnId

            val orderedRows = if (sortId == null) {
                detailRows.toList()
            } else {
                sortDetailRows(detailRows, sortId, detailSortDescending)
            }

            return DetailTable(
                columns = visibleColumns,
                // 全量列定义：给"选择列"对话框画复选框（顺序固定，不随勾选变化）
                allColumns = detailColumns,
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

            detailNote = "共 ${detailRows.size} 个进程（由上游填入）"

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
                "表内没有进程（等待上游填入）"
            } else {
                "共 ${detailRows.size} 个进程"
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

            detailViewSaveExecutor.execute {
                DetailViewStore.save(this, snapshot, scopeSnapshot, ignoreSnapshot)
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
            return ProcessActionResult(false, "没有选中进程。", LinuxPacketStatus.BAD_ARGUMENT)
        }

        // 没有启动时刻（-1）就确认不了"还是同一个进程"：宁可拒绝，也不冒"PID 被复用后杀错人"的风险。
        // 0 是合法值，不能当作"没有"—— init 的 starttime 就是 0。
        if (startTicks < 0L) {
            return ProcessActionResult(
                false,
                "这个进程没有可用的启动时间，无法确认它是哪一个进程。",
                LinuxPacketStatus.BAD_ARGUMENT
            )
        }

        val linux = LinuxService.instanceOrNull()

        if (linux == null) {
            return ProcessActionResult(false, "Linux 服务尚未就绪。", LinuxPacketStatus.UNSUPPORTED)
        }

        val packet = linux.killProcess(pid, startTicks, linuxRequestTimeoutMs)

        if (packet == null) {

            scheduleDetailRefreshAfterKill()

            return ProcessActionResult(false, "Linux 服务没有回执。", LinuxPacketStatus.INTERNAL)
        }

        if (packet.isOk) {

            // 成功不提示，只刷新；而且隔 1 秒再采 —— 信号刚发出去，/proc 里那一行还在，
            // 立刻采会把它读回来，看起来像没杀掉。
            scheduleDetailRefreshAfterKill()

            return ProcessActionResult(true, "", LinuxPacketStatus.OK)
        }

        val reason = when (packet.status) {

            LinuxPacketStatus.PROCESS_EXITED -> "进程已经退出。"

            LinuxPacketStatus.UNSUPPORTED -> "服务不支持此操作。"

            LinuxPacketStatus.BAD_ARGUMENT -> "参数不正确。"

            LinuxPacketStatus.ACCEPTED -> "服务只受理了请求，没有执行。"

            // INTERNAL：信号发不出去。errno 1 = EPERM（权限不够），3 = ESRCH（进程已不在）
            else -> when (packet.info["errno"]?.trim()?.toIntOrNull()) {
                1 -> "拒绝访问。"
                3 -> "进程已经退出。"
                else -> "拒绝访问。"
            }
        }

        // 失败也刷一次（同样延后）：目标可能已经不存在，表格要跟上真实状态
        scheduleDetailRefreshAfterKill()

        return ProcessActionResult(false, reason, packet.status)
    }

    /** 把"列布局 + 范围 + 忽略开关"整份写盘（后台线程，几十字节）。 */
    private fun saveDetailViewState() {

        val ids = detailVisibleIds.toSet()
        val scope = detailProcessScope
        val ignore = detailIgnoreUpdateSpeed

        detailViewSaveExecutor.execute {
            DetailViewStore.save(this, ids, scope, ignore)
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

            detailNote = "测试数据：Test0 ~ Test10 共 11 个进程（名称 TestN，其余统计值 = N）"
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
