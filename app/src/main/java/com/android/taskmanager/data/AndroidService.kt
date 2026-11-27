/**
 * ============================================================
 * Android 侧数据服务
 * ============================================================
 *
 * 定位：Android Framework 侧的数据来源。
 * 通过 ActivityManager / Process 等官方接口采集进程、内存、CPU 等信息，
 * 供进程页、性能页、详细信息页使用。
 *
 * 现状：只提供最小的 UID 读取用于验证服务可用，其余为预留接口；
 * 另加了一个输出缓冲（见 [outputSnapshot]），供调试页"上游服务输出"那一栏读取。
 *
 * 身份模式（[EXTRA_PRIVILEGED_MODE]）：
 * UIService 拿到 Shizuku 授权后会以提权模式重启本服务。本服务本身永远跑在应用进程里，
 * 改不了 UID；所谓"提权模式"指的是——它在取数时**被允许**借用一条提权通道
 * 去执行特权命令（[probeIdentity] 就是这条通路的自检），
 * 这样"以提权能力启动安卓服务"才是有意义的：能力走通道，进程还归系统管。
 *
 * 权限口径（用户明确要求，别再改回去）：
 * 本服务不维护"权限从 Shizuku 还是 su 来"这套来源模型，对外只报 uid：
 * - 数据接口报应用进程的 uid（[GetDataFromAndroidF]）；
 * - 自检报 `id` 的输出（里面就是 uid=...），不再加 "Shizuku 自检 / Root 自检" 这种来源前缀。
 * 身份来源、授权状态、按钮可用性一律由 UIService 决定；调试页只是豁免了"可以随便调接口"，
 * 它看到的也只需要是 uid。
 *
 * 命名约定：本类的数据接口按 Interfaces.kt 的豁免条款保留原名（GetDataFromAndroidF），
 * 不做 I 前缀改造——它是外部服务主动提供数据的口子，接入真实数据时整体替换。
 */
package com.android.taskmanager.data

import com.android.taskmanager.AppStrings
import com.android.taskmanager.R
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.android.taskmanager.UpstreamIdentityMode
import com.android.taskmanager.service.RootManager
import com.android.taskmanager.service.ShizukuManager
import java.io.File

/**
 * 以 Service 形式常驻的数据提供者。
 * 生命周期由 UIService 维护（UIService.onCreate() startService / onDestroy() stopService），
 * 且 UIService 是它唯一的调用方——页面不要直接调本服务。
 *
 * 预留实现接口（建议在本类中补齐，供 UI 直接调用）：
 * - getProcessList(): List<ProcessInfo>       通过 ActivityManager.getRunningAppProcesses()
 * - getMemoryInfo(): MemoryInfo               通过 ActivityManager.getMemoryInfo()
 * - getCpuUsage(): Float                      通过 /proc/stat 采样或 Process.getElapsedCpuTime()
 * - getRunningServices(): List<ServiceInfo>   通过 ActivityManager.getRunningServices()
 * 返回的数据模型建议统一定义在 data 包下，供两个 Service 复用。
 */
class AndroidService : Service() {

    // ============================================================
    // 实例登记（同进程调用用）
    //
    // UIService 负责本服务的启动与回收，也是唯一调用方：
    // UIService 通过 instanceOrNull() 直接调用 GetDataFromAndroidF()，
    // 同进程内不需要 Binder。
    // ============================================================

    companion object {

        private const val TAG = "TMAndroid"

        /** 启动参数：true = 允许本服务借提权通道（Shizuku / root）执行特权命令。 */
        const val EXTRA_PRIVILEGED_MODE = "android_service_privileged_mode"

        /**
         * 启动参数：优先用哪个身份（[UpstreamIdentityMode] 的取值）。
         *
         * 由 UIService 按"上次恢复的用户身份"下发：选了 root 就先探 su，选了 shell 就先探 Shizuku。
         */
        const val EXTRA_IDENTITY_MODE = "android_service_identity_mode"

        /** 输出缓冲保留的行数（够调试页看清一次启动的全过程即可）。 */
        private const val KEEP_LINES = 40

        /**
         * 包 / uid 表多久重建一次。
         *
         * 用户装 / 卸 / 更新应用后，表最多这么久更新一次；不挂 PACKAGE_ADDED 广播是为了
         * 避开接收器导出标志的兼容问题，而且重建本身就在后台线程里跑，不值钱。
         */
        private const val UID_INDEX_TTL_MS = 30_000L

        /** 包表巡检线程的检查间隔：只算一下"过期没有"，不做任何 I/O。 */
        private const val INDEX_POLL_MS = 5_000L

        /** 一条 shell 命令的默认超时（读一次 /proc 循环、读一次包信息都在这个量级）。 */
        private const val SHELL_TIMEOUT_MS = 3_000L

        /** 包表那条命令的超时：`cmd package list packages` 在低端机上偶尔要一两秒。 */
        private const val PACKAGE_LIST_TIMEOUT_MS = 4_000L

        /** 单个包的 `dumpsys package` 超时。 */
        private const val META_QUERY_TIMEOUT_MS = 4_000L

        /** 两次包信息查询之间的间隔：别把 dumpsys 打满，用户看不见的活慢一点没关系。 */
        private const val META_QUERY_GAP_MS = 150L

        /**
         * 隔离进程的 uid 段（framework 的 AID_ISOLATED_START..AID_ISOLATED_END）。
         *
         * 真机事实：Chrome 的沙箱渲染进程 `/proc/7608/status` 里写的是 `Uid: 90003`
         * （`ps` 显示成 `u0_i3`），WebView 的沙箱是 90001..909x —— 也就是说这段的起点是
         * **90000**，不是 99000。以前取 99000..99999，90xxx 全部漏判，沙箱进程被当成
         * Linux 原生进程，包名 / 图标 / 优先级整行都丢了。
         *
         * 仍然只是包 / uid 表的补充：这些进程没有独立包表项，靠 uid 段认出"是框架拉起来的"，
         * 再由进程名（`包名:sandboxed_process0` / `包名_zygote`）定包。
         * **isolation 的判据仍然是"框架管不管"，不是"uid 落在哪个段"本身。**
         */
        private const val ISOLATED_UID_START = 90_000
        private const val ISOLATED_UID_END = 99_999

        /** `dumpsys activity processes` 的超时：输出是 MB 级，比普通查询给得宽。 */
        private const val PROC_DUMPSYS_TIMEOUT_MS = 8_000L

        /** 读用户头像文件的超时：图片很小，但提权通道首次建连可能慢一点。 */
        private const val USER_AVATAR_TIMEOUT_MS = 5_000L

        /** 用户名的缓存时间：dumpsys user 只需偶尔跑一次。 */
        private const val USER_NAME_CACHE_MS = 10 * 60_000L
        private const val USER_NAME_TIMEOUT_MS = 3_000L

        /** 框架也没给出归属时，这个"查不到"的结论过多久再重试（进程可能刚起来还没登记）。 */
        private const val PROC_PACKAGE_MISS_TTL_MS = 60_000L

        /** 上面那张归属缓存的上限，超了整体清空（进程会不断换 pid）。 */
        private const val PROC_PACKAGE_CACHE_LIMIT = 512

        /**
         * AVF 的系统特性名（PackageManager.FEATURE_VIRTUALIZATION_FRAMEWORK，API 34 起）。
         *
         * 写字面量而不是引用常量：常量只在 API 34 的编译库里存在，
         * 直接引用会让低版本分支也挂上版本要求。
         */
        private const val AVF_FEATURE = "android.software.virtualization_framework"

        /** AVF 组件的 APEX 目录：在就是这套组件已经装进系统镜像里。 */
        private const val AVF_APEX_PATH = "/apex/com.android.virt"

        /** 当前运行的实例；未启动 / 已销毁时为 null。 */
        @Volatile
        private var running: AndroidService? = null

        /** 由本类在 onCreate / onDestroy 维护，外部只读。 */
        fun instanceOrNull(): AndroidService? = running
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 输出缓冲：调试页第二项读的就是它。加锁因为 reader 线程与主线程都会写。 */
    private val lines = ArrayDeque<String>()

    private val lineLock = Any()

    /** 当前是否处于提权模式（由 UIService 通过启动参数指定）。 */
    @Volatile
    private var privilegedMode: Boolean = false

    /** 优先走哪条通路（auto / shell / root），同样由 UIService 下发。 */
    @Volatile
    private var identityMode: String = UpstreamIdentityMode.AUTO

    /** 用户名的提权缓存：dumpsys user 很轻，但用户页每秒轮询，不能每秒都起 shell。 */
    private val userNameLock = Any()
    private var userNameCache: Map<Int, String> = emptyMap()
    private var userNameCacheAt: Long = 0L

    override fun onCreate() {
        super.onCreate()

        running = this

        record("onCreate pid=${Process.myPid()} uid=${Process.myUid()}")
    }

    override fun onDestroy() {
        // 只在"登记的还是自己"时才清空：授权成功后 UIService 会 stop -> start，
        // stopService 是异步的，旧实例的 onDestroy 可能晚于新实例的 onCreate 执行，
        // 无条件清空会把新实例的登记抹掉，页面从此拿到 null（表现为"服务没有返回参数"）。
        if (running === this) {
            running = null
        }

        record("onDestroy")

        super.onDestroy()
    }

    // 预留：若以后需要跨进程访问，可在此返回 Binder / AIDL 实现；当前不提供绑定。
    override fun onBind(intent: Intent?): IBinder? {
        // 不暴露 Binder：同进程内直接调用方法即可（LinuxService 同样处理）
        return null
    }

    // 服务启动回调：读取身份模式并做一次能力自检
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val wantedPrivileged = intent?.getBooleanExtra(EXTRA_PRIVILEGED_MODE, false) ?: false

        // 优先身份（auto / shell / root）：非法值收敛成 auto
        val wantedIdentityMode = (intent?.getStringExtra(EXTRA_IDENTITY_MODE) ?: UpstreamIdentityMode.AUTO)
            .takeIf {
                it == UpstreamIdentityMode.AUTO ||
                    it == UpstreamIdentityMode.SHELL ||
                    it == UpstreamIdentityMode.ROOT
            } ?: UpstreamIdentityMode.AUTO

        record("onStartCommand privilegedMode=$wantedPrivileged identityMode=$wantedIdentityMode")

        if (wantedPrivileged != privilegedMode || wantedIdentityMode != identityMode) {

            privilegedMode = wantedPrivileged
            identityMode = wantedIdentityMode

            record(
                if (privilegedMode) AppStrings.s(R.string.asvc_identity_switch_privileged, identityMode)
                else AppStrings.s(R.string.asvc_identity_switch_app)
            )
        }

        if (privilegedMode) {
            probeIdentity()
        } else {
            record(AppStrings.s(R.string.asvc_skip_elevation_selfcheck))
        }

        // 预热包 / uid 表（后台线程）：详细信息页转表行时会问"这个 pid 是不是 Android 进程"，
        // 表没建好之前一律答"否"，用户会看到一片"隔离状态：否"，所以服务一起来就建。
        requestUidIndexRefresh()

        // 不粘连启动：进程被杀后不自动重建，避免后台服务被系统反复拉起
        return START_NOT_STICKY
    }

    // ============================================================
    // 进程补充查询（给详细信息页补 Linux 拿不到的那五格）
    //
    // 分工（用户定死的口径，别再改回去）：
    // - Linux / ELF 后端只回答内核侧事实；package_name / target_sdk / description /
    //   android_priority / isolation 这五格它一律回"暂无数据"，不再冒充 Android 数据；
    // - 本服务负责这五格：包 / uid 表与包信息都用 shell（Shizuku 或 root）身份跑命令取，
    //   所以**不需要**再要用户授予"所有应用可见"（QUERY_ALL_PACKAGES）之类的权限。
    //
    // 一轮快照的调用形态（UIService 在采集线程上同步调用）：
    // ① [prepareRound]：先回答"这一轮哪些 pid 是 Android Framework 管的进程"——
    //    判据是这个进程的 uid 出现在包 / uid 表里（不靠 uid 段猜）；
    //    再采这批准进程的优先级分档（直接读 /proc 里的同一个文件，读不到才走 shell）。
    // ② [queryProcess]：一个进程问一次，纯内存查表；100 个 Android 进程也只是 100 次取值。
    //
    // 为什么"先判 Android 再查字段"：Linux 原生进程（uid 不属于任何已装包）没有包名 /
    // 目标 SDK / 应用名这些概念，查了也是空转，还白白起 shell；先出 isolation 结论，
    // YES 才继续取其余四格。
    //
    // 为什么不做防 PID 复用校验：Android 侧拿不到可靠的进程启动时刻，
    // 硬校验只会把活着的进程判成"换了人"。startTicks 由调用方带着走、原样回传，
    // 只用于"把补充字段并回正确的行"，防复用由 Linux 侧保证。
    // ============================================================

    /**
     * 包 / uid 表：一个 uid 可能对应**多个包**（系统组件大量共用 uid 1000 / 1001，
     * 应用分身也是同 uid 多包），所以这里存的是"候选包名列表"，不是一个包名。
     *
     * 为什么必须存列表：以前一个 uid 只留一个包名（putIfAbsent 取第一个），
     * 结果 uid 1000 的 `com.android.systemui` 被显示成了同一 uid 的
     * `com.miui.screenrecord` —— 那是**拿相近的系统组件冒充**，不是真实归属。
     * 现在 uid 只是"缩小候选范围"，真正的包名还要按进程名（cmdline 的 argv[0]）在候选里对号。
     */
    private val uidIndex = java.util.concurrent.atomic.AtomicReference<Map<Int, List<String>>>(emptyMap())

    /**
     * 全机所有包名（与 [uidIndex] 同时重建）。
     *
     * 用途只有一个：隔离进程（uid 90xxx..99xxx）没有包表项，但它的进程名仍带着"我是哪个包的沙箱"
     * （`com.android.chrome_zygote` / `pkg:sandboxed_process0`），要拿全表包名去精确对号。
     */
    private val allPackageNames = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())

    @Volatile
    private var uidIndexAtMs: Long = 0L

    /**
     * 包 / uid 表是不是"全机完整"的（true = shell 身份取的；false = 只有本地可见范围）。
     *
     * 只有完整的表才能断言"这个 uid 不在表里 = 不是 Android 进程"；
     * 本地兜底表只看得见自己那几个包，拿它判"否"会把别的应用全标成 Linux 原生，
     * 那是假结论。不完整时这一格保持"暂无数据"。
     */
    @Volatile
    private var uidIndexComplete: Boolean = false


    /** 保护包表 / 包信息 / 线程句柄这几份共享状态。 */
    private val indexLock = Any()

    @Volatile
    private var indexWorker: Thread? = null

    /** 有没有人催过"马上重建"（服务启动、装 / 卸应用之后）。 */
    private val indexWanted = java.util.concurrent.atomic.AtomicBoolean(true)

    /** 本轮 pid -> 优先级分档（Framework 写进 /proc/<pid>/oom_score_adj 的那个数）。 */
    private val roundPriority = HashMap<Int, Int>()

    private val priorityLock = Any()

    /**
     * 本轮 pid -> 包名。key 存在、值是 null 表示"这一轮**确认**它没有可归属的包"
     * （与"这轮没算过"要分开：后者要退回纯规则判断）。
     */
    private val roundPackages = HashMap<Int, String?>()

    private val roundPackageLock = Any()

    /**
     * 框架自述的进程归属：[prepareRound] 里定不出包的进程，去 `dumpsys activity processes`
     * 每条 ProcessRecord 的 `data=` 目录里取包名，结果按 pid + startTicks 缓存。
     *
     * pkg 为 null 的记录也会缓存，但只保留 [PROC_PACKAGE_MISS_TTL_MS]，之后再查一次。
     */
    private class ProcPackage(val startTicks: Long, val pkg: String?, val atMs: Long)

    private val procPackageCache = HashMap<Int, ProcPackage>()

    private val procPackageLock = Any()

    /**
     * 服务启动时催一次包表重建（[requestUidIndexRefresh] 不阻塞调用线程）。
     */
    private fun requestUidIndexRefresh() {

        indexWanted.set(true)
        ensureIndexWorker()
    }

    /** 巡检线程只建一次：它自己按 TTL 决定要不要重建，不需要每次调用都起线程。 */
    private fun ensureIndexWorker() {

        synchronized(indexLock) {

            if (indexWorker?.isAlive == true) return

            val worker = Thread {

                while (true) {

                    try {

                        val stale = SystemClock.elapsedRealtime() - uidIndexAtMs >= UID_INDEX_TTL_MS

                        if (indexWanted.getAndSet(false) || uidIndex.get().isEmpty() || stale) {
                            rebuildUidIndex()
                        }

                        Thread.sleep(INDEX_POLL_MS)

                    } catch (e: InterruptedException) {

                        return@Thread

                    } catch (e: Throwable) {

                        Log.w(TAG, "包表线程异常：${e.javaClass.simpleName} ${e.message ?: ""}")
                    }
                }
            }.apply {
                isDaemon = true
                name = "TMAndroid-PkgIndex"
            }

            indexWorker = worker

            worker.start()
        }
    }

    /**
     * 一轮快照开始：判"哪些 pid 是 Android 框架进程"，并采本轮优先级。
     *
     * @param records Linux P 行转出来的键（pid + startTicks + uid）
     * @return 判定为 Android 框架进程的 pid；不在集合里的一律是 Linux 原生进程
     */
    fun prepareRound(records: List<ProcessKey>): Set<Int> {

        val index = uidIndex.get()

        // 表还没建好：这一轮一个都判不了（返回空集合 = UIService 这一轮不做补充，
        // 也不会把"隔离状态"写成"否"——那会是假的结论）
        if (index.isEmpty()) return emptySet()

        val androidPids = LinkedHashSet<Int>(records.size)

        for (record in records) {

            val uid = record.uid ?: continue

            if (index.containsKey(uid) || isIsolatedSandbox(uid)) androidPids.add(record.pid)
        }

        collectPriorities(androidPids.toList())

        resolveRoundPackages(records, androidPids, index)

        return androidPids
    }

    /**
     * 这一轮把"每个 Android 进程属于哪个包"定下来（纯内存 + 必要时一次 dumpsys）。
     *
     * 四级（顺序就是可靠性的顺序）：
     * 1. 包表候选 + 进程名对号（[resolvePackage]）——覆盖绝大多数；
     * 2. 对不上的（典型是共享 uid 的系统进程：`.qtidataservices` 跑在 uid 10109 上，
     *    和 `com.qualcomm.qti.cne` / `vendor.qti.hardware.cacert.server` / `vendor.qti.iwlan`
     *    共用 uid，进程名不带包名，光凭 /proc 分辨不出）→ 问框架自己的进程记录；
     * 3. 框架也没记录 / 通道不通 → **uid 兜底**：拿这个 uid 对应的包当归属，
     *    再按这个包取名和图标（用户要求的备用路径）；
     * 4. 连 uid 都没有对应的包（隔离进程 / uid 缺失）→ 记为"没有归属"，界面显示暂无数据，不猜。
     *
     * 为什么问 dumpsys 而不是一上来就按 uid 拍：`dumpsys activity processes` 每条
     * ProcessRecord 头上写着 pid，紧跟的 `data=/data/user_de/0/<包名>` 是这个进程**自己的**
     * 数据目录 —— 共享 uid 时它是唯一能落地的归属证据。uid 兜底只在它也给不出答案时用。
     */
    private fun resolveRoundPackages(
        records: List<ProcessKey>,
        androidPids: Set<Int>,
        index: Map<Int, List<String>>
    ) {

        val complete = uidIndexComplete
        val all = allPackageNames.get()

        val resolved = HashMap<Int, String?>(androidPids.size)
        val pending = ArrayList<ProcessKey>()

        for (record in records) {

            if (record.pid !in androidPids) continue

            val uid = record.uid ?: continue

            val candidates = index[uid]

            val pkg = if (candidates != null) {
                resolvePackage(record.processName, candidates, complete)
            } else {
                // 隔离段：没有包表项，拿全表包名按同一套规则对号
                resolvePackage(record.processName, all, complete)
            }

            if (pkg != null) resolved[record.pid] = pkg else pending.add(record)
        }

        if (pending.isNotEmpty()) {

            val needQuery = ArrayList<ProcessKey>(pending.size)
            val now = SystemClock.elapsedRealtime()

            synchronized(procPackageLock) {

                for (record in pending) {

                    val cached = procPackageCache[record.pid]

                    if (cached != null && cached.startTicks == record.startTicks &&
                        (cached.pkg != null || now - cached.atMs < PROC_PACKAGE_MISS_TTL_MS)
                    ) {
                        resolved[record.pid] = cached.pkg
                    } else {
                        needQuery.add(record)
                    }
                }
            }

            if (needQuery.isNotEmpty()) {

                val fromFramework = readFrameworkPackages(needQuery.map { it.pid }.toSet())
                val at = SystemClock.elapsedRealtime()

                synchronized(procPackageLock) {

                    if (procPackageCache.size > PROC_PACKAGE_CACHE_LIMIT) procPackageCache.clear()

                    for (record in needQuery) {

                        val pkg = fromFramework[record.pid]

                        procPackageCache[record.pid] = ProcPackage(record.startTicks, pkg, at)
                        resolved[record.pid] = pkg
                    }
                }
            }
        }

        // ③ uid 兜底：框架也没给出归属（或通道不通）时，按这个 uid 对应的包认归属。
        //    这是用户明确要求的备用路径——进程名定不出包时，用 uid 查包，再拿这个包的名字和图标。
        //    代价要写清楚：共享 uid（一个 uid 多个包，如 uid 1000 下的 systemui / settings / screenrecord）
        //    只能取候选列表的第一个，**不保证准确**，所以它排在进程名 + 框架记录之后。
        for (record in pending) {

            if (resolved[record.pid] != null) continue

            val uid = record.uid ?: continue

            val candidates = index[uid] ?: continue

            resolved[record.pid] = candidates.first()
        }

        synchronized(roundPackageLock) {

            roundPackages.clear()
            roundPackages.putAll(resolved)
        }
    }

    /**
     * 本轮优先级：读不到分档的进程不进表（界面显示"暂无数据"）。
     *
     * 数值口径：ActivityManager 的 importance 只对"自己进程"可见（getRunningAppProcesses 从
     * Android 5 起只回调用方自己的进程），别的进程的 importance 没有从应用侧读取的公开路径；
     * 于是这里取 Framework 自己写进 /proc/<pid>/oom_score_adj 的 LMK 分档——
     * 那个数是 Android 进程管理给出的结论，不是内核的 nice 值。
     *
     * 读取顺序：① 直接读文件（这个文件世界可读，一百个进程也只要一两毫秒，
     * 比起每轮起一个 shell 进程便宜得多）；② 直接读不到（个别 ROM 收紧权限）才走 shell 循环。
     */
    private fun collectPriorities(pids: List<Int>) {

        if (pids.isEmpty()) {

            synchronized(priorityLock) { roundPriority.clear() }

            return
        }

        val output = readPrioritiesDirect(pids) ?: run {

            val script = "for p in " + pids.joinToString(" ") + "; do " +
                "if read -r v < /proc/\$p/oom_score_adj 2>/dev/null; then echo \"\$p \$v\"; fi; done"

            runShell(script)
        } ?: run {

            synchronized(priorityLock) { roundPriority.clear() }

            return
        }

        val fresh = HashMap<Int, Int>(pids.size)

        output.lineSequence().forEach { raw ->

            val parts = raw.trim().split(' ')

            if (parts.size == 2) {

                val pid = parts[0].toIntOrNull()
                val adj = parts[1].toIntOrNull()

                if (pid != null && adj != null) fresh[pid] = adj
            }
        }

        synchronized(priorityLock) {

            roundPriority.clear()
            roundPriority.putAll(fresh)
        }
    }

    /**
     * 直接读 `/proc/<pid>/oom_score_adj`（应用身份也行：这个文件通常世界可读）。
     *
     * 一个都读不到时返回 null，让调用方按"这轮没数据"处理，而不是当成"全是 0"；
     * 读到一部分就返回那一部分，读不到的进程自然没有这一格。
     */
    private fun readPrioritiesDirect(pids: List<Int>): String? {

        var any = false

        val builder = StringBuilder(pids.size * 16)

        for (pid in pids) {

            val adj = try {
                java.io.File("/proc/$pid/oom_score_adj").readText().trim().toIntOrNull()
            } catch (e: Throwable) {
                null
            } ?: continue

            any = true

            builder.append(pid).append(' ').append(adj).append('\n')
        }

        return if (any) builder.toString() else null
    }

    /**
     * 包 / uid 表是不是全机完整的（见 [uidIndexComplete]）。
     *
     * UIService 用它决定"能把不在表里的进程写成隔离状态：否吗"——
     * 只有完整的表才有资格下这个结论。
     */
    fun isPackageTableComplete(): Boolean = uidIndexComplete

    /**
     * 这个 uid 是不是框架拉起的隔离沙箱进程（多用户时 uid = 用户号 * 100000 + appId）。
     *
     * 真机：chrome 沙箱 90003（`u0_i3`）、webview 沙箱 90001..909x，所以取 90000 起。
     */
    private fun isIsolatedSandbox(uid: Int): Boolean {

        val appId = uid % 100_000

        return appId in ISOLATED_UID_START..ISOLATED_UID_END
    }

    /**
     * 查一个进程的 Android 侧补充字段（纯内存，不跑命令）。
     *
     * @param key pid + startTicks + uid；uid 来自 Linux P 行的 uid 扩展键
     * @return isolation 一定有结论（YES = 框架进程 / NO = Linux 原生）；
     *         uid 缺失时返回 null（这一格谁都不知道，保持 Linux 的"暂无数据"）
     */
    fun queryProcess(key: ProcessKey): ProcessSupplement? {

        val uid = key.uid ?: return null

        val candidates = uidIndex.get()[uid]

        // 不是包表里的 uid：要么是框架拉起的隔离沙箱（仍是 Android 进程），要么是 Linux 原生
        val sandbox = candidates == null && isIsolatedSandbox(uid)

        if (candidates == null && !sandbox) {

            return ProcessSupplement(
                // 表不完整时不下"否"的结论（本地可见范围判不了别人的进程）
                isolation = if (uidIndexComplete) BackendDisplayText.ISOLATION_NO else null
            )
        }

        val priority = synchronized(priorityLock) { roundPriority[key.pid] }

        // 包归属由 prepareRound 统一算好（规则对号 + 框架进程记录兜底），这里只取
        val pkg = packageOfRound(key)

        if (pkg == null) {

            // 是 Android 进程，但定不出是哪个包（共享 uid 且进程名不带包名）：
            // 报"是 Android 进程"，包名留空 —— 不拿同 uid 的别的包冒充
            return ProcessSupplement(
                packageName = null,
                targetSdk = null,
                description = null,
                androidPriority = priority,
                isolation = BackendDisplayText.ISOLATION_YES
            )
        }

        val meta = metaFor(pkg)

        return ProcessSupplement(
            packageName = pkg,
            // 包信息是异步补齐的：第一次见到这个包的那一轮还是 null，下一轮就有了
            targetSdk = meta?.targetSdk,
            description = meta?.label,
            androidPriority = priority,
            isolation = BackendDisplayText.ISOLATION_YES
        )
    }

    /**
     * 取这一轮算好的包归属；没有这一轮的结果（详情页单独问了某个进程）就退回纯规则判断。
     *
     * 返回 null 有两种含义，都按"没有归属"处理：确认没有归属 / 规则对不上。
     */
    private fun packageOfRound(key: ProcessKey): String? {

        var known = false
        var pkg: String? = null

        synchronized(roundPackageLock) {

            if (roundPackages.containsKey(key.pid)) {

                known = true
                pkg = roundPackages[key.pid]
            }
        }

        if (known) return pkg

        val uid = key.uid ?: return null

        val candidates = uidIndex.get()[uid]

        return if (candidates != null) {
            resolvePackage(key.processName, candidates, uidIndexComplete)
        } else if (isIsolatedSandbox(uid)) {
            resolvePackage(key.processName, allPackageNames.get(), uidIndexComplete)
        } else {
            null
        }
    }

    /** `ProcessRecord{hash pid:进程名/uid}` —— pid 写在冒号前。 */
    private val processRecordPid = Regex("ProcessRecord\\{[0-9a-fA-F]+ (\\d+):")

    /**
     * 从框架的进程记录里读"pid -> 包名"，只取被问到的那些 pid。
     *
     * 来源是每条 ProcessRecord 的 `data=` 目录：共享 uid 时包表里只有一个 uid，
     * 而 `data=` 是**这个进程自己的**数据目录（`.qtidataservices` 就是
     * `/data/user_de/0/vendor.qti.hardware.cacert.server`），所以这是能落地的归属证据，
     * 不是按进程名猜。读不到（通道不可用 / 该进程框架没登记）就返回空表，由调用方写"暂无数据"。
     */
    private fun readFrameworkPackages(pids: Set<Int>): Map<Int, String> {

        if (pids.isEmpty()) return emptyMap()

        val output = runShell("dumpsys activity processes 2>/dev/null", PROC_DUMPSYS_TIMEOUT_MS)
            ?: return emptyMap()

        val result = HashMap<Int, String>(pids.size)

        var current = 0

        output.lineSequence().forEach { raw ->

            val line = raw.trim()

            if (line.contains("ProcessRecord{")) {

                current = processRecordPid.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                return@forEach
            }

            if (current == 0 || current !in pids) return@forEach

            val dataDir = line.substringAfter("data=", "").substringBefore(' ').trim()

            if (dataDir.isEmpty()) return@forEach

            val pkg = dataDir.substringAfterLast('/')

            // `/data/user_de/0/<包名>`；拿不到包名形态的目录（null / 数字）就跳过
            if (pkg.isNotEmpty() && pkg != "null" && !pkg.all { it.isDigit() }) {

                result[current] = pkg

                // 一条记录只认第一份 data=
                current = 0
            }
        }

        return result
    }

    /**
     * 在一个 uid 的候选包里，按**进程名**定出这个进程真正属于哪个包。
     *
     * 判据是 `/proc/<pid>/cmdline` 的 argv[0]（Android 框架把它设成进程名），按优先级：
     *
     * 1. **全量表里这个 uid 只对应一个包**：进程名不同也不影响归属。
     *    应用的子进程、应用私有的 App Zygote（如 `com.android.chrome_zygote`）都跑在同一个 uid 下，
     *    这时候"进程名 != 包名"是正常的，不能因为没有同名包就判成"没有归属"；
     * 2. 进程名与候选包名完全相等：`com.android.systemui`；
     * 3. 冒号前缀：`com.android.chrome:sandboxed_process0` / `pkg:remote`；
     * 4. `<包名>_zygote`：App Zygote 的进程名约定（Android 在自己的 zygote 里把
     *    argv[0] 设成 `<包名>_zygote`），只在精确拼得上时采用；
     * 5. `包名.` 前缀：`com.google.android.gms.persistent`、`android.process.acore`。
     *
     * 都对不上就返回 null（宁可写"暂无数据"，也不写一个同 uid 的别的包）。
     * 为什么不能用"uid 只取第一个包"：uid 1000 底下同时住着 systemui / settings /
     * screenrecord 等一堆系统组件，取第一个就是把别人的包名安到这个进程头上。
     *
     * @param processName  进程名（完整；null / 空 = 这一轮没采到）
     * @param candidates   候选包名（第三方优先）；查隔离进程时传的是**全表所有包名**
     * @param tableComplete 候选表是不是全机完整的（本地兜底表不敢做"单包即归属"的推断）
     */
    private fun resolvePackage(
        processName: String?,
        candidates: List<String>,
        tableComplete: Boolean
    ): String? {

        if (candidates.isEmpty()) return null

        // ① 全量表 + 该 uid 只有一个包：就是这个包（应用私有 zygote / 子进程都属于它）
        if (tableComplete && candidates.size == 1) return candidates.first()

        val name = processName?.trim()?.substringBefore(' ')?.trim().orEmpty()

        if (name.isEmpty()) {

            // 名称没采到：只有"全量表 + 单包"才敢定论（上面已处理），这里不再猜
            return null
        }

        candidates.firstOrNull { it == name }?.let { return it }

        // ② `pkg:remote` 这类子进程：冒号前就是包名
        val colonPrefix = name.substringBefore(':')

        candidates.firstOrNull { it == colonPrefix }?.let { return it }

        // ③ 应用私有 zygote：`<包名>_zygote`
        candidates.firstOrNull { name == "${it}_zygote" }?.let { return it }

        // ④ `com.google.android.gms.persistent` / `android.process.acore` 这类老式进程名：
        //    包名是进程名的一段前缀（后面紧跟 `.`）
        candidates.firstOrNull { name.startsWith("$it.") }?.let { return it }

        return null
    }

    // ---------- 包 / uid 表 ----------

    /** `package:<包名> uid:<uid>` 一行；`cmd package list packages -U` 的输出格式。 */
    private val packageUidLine = Regex("package:(\\S+)\\s+uid:(\\d+)")

    /**
     * 重建包 / uid 表：先用 shell 取（覆盖全机所有包，不受可见性限制），
     * shell 通道不可用时退回本地 PackageManager（只能看到本应用可见的那部分，够兜底）。
     */
    private fun rebuildUidIndex() {

        val fromShell = readPackageUidTable()

        val built = if (fromShell.isNotEmpty()) fromShell else readPackageUidTableLocal()

        uidIndex.set(built)
        allPackageNames.set(built.values.flatten().distinct())
        uidIndexAtMs = SystemClock.elapsedRealtime()
        uidIndexComplete = fromShell.isNotEmpty()

        record(
            if (fromShell.isNotEmpty()) {
                AppStrings.s(R.string.asvc_pkg_index_shell, built.size)
            } else {
                AppStrings.s(R.string.asvc_pkg_index_local, built.size)
            }
        )
    }

    /**
     * 用 shell 身份读 `cmd package list packages -U`。
     *
     * 两条命令串在一次 shell 里：先第三方（-3），再全量。
     *
     * **同一个 uid 允许多个包**：系统组件大量共用 uid 1000（systemui、settings、screenrecord
     * 都跑在 system 用户下），应用分身也是同 uid 多包。所以结果按 uid 聚成"候选包名列表"，
     * 第三方包排在前面（用户主动装的那个更可能），但**一个 uid 绝不只保留一个包名**——
     * 只留一个就会出现"systemui 的进程顶着 screenrecord 的包名"这种张冠李戴。
     *
     * 为什么不是 `getInstalledPackages`：Android 11 起普通应用受包可见性限制，
     * 看不到别人的包，正是"为什么包名一列老是空"的根因；shell 身份没有这个限制，
     * 也就不需要向用户索要 QUERY_ALL_PACKAGES。
     */
    private fun readPackageUidTable(): Map<Int, List<String>> {

        // 只查当前用户会漏掉 10 / 11 这类副用户里安装的包：那些进程的 uid 是
        // 1010xxxx，不在当前用户的包表里，图标 / 包名就会全部落空。
        val userIds = runCatching { androidUserNames().keys }
            .getOrDefault(emptySet())
            .plus(Process.myUid() / 100000)
            .sorted()

        if (userIds.isEmpty()) return emptyMap()

        val thirdParty = HashMap<Int, MutableList<String>>(256)
        val all = HashMap<Int, MutableList<String>>(512)

        userIds.forEach { userId ->

            val script = "cmd package list packages -3 -U --user $userId 2>/dev/null; " +
                "echo '@@ALL'; cmd package list packages -U --user $userId 2>/dev/null"

            val output = runShell(script, PACKAGE_LIST_TIMEOUT_MS) ?: return@forEach

            var inAll = false

            output.lineSequence().forEach { raw ->

                val line = raw.trim()

                if (line == "@@ALL") {
                    inAll = true

                    return@forEach
                }

                val match = packageUidLine.find(line) ?: return@forEach

                val pkg = match.groupValues[1]
                val uid = match.groupValues[2].toIntOrNull() ?: return@forEach

                val target = if (inAll) all else thirdParty

                val list = target.getOrPut(uid) { ArrayList(2) }

                if (pkg !in list) list.add(pkg)
            }
        }

        val result = HashMap<Int, List<String>>(all.size + thirdParty.size)

        all.forEach { (uid, list) -> result[uid] = list }

        // 第三方包排到候选列表最前面（同 uid 时优先当成"用户装的那个"）
        thirdParty.forEach { (uid, list) ->

            val existing = result[uid]

            result[uid] = list + (existing ?: emptyList()).filter { it !in list }
        }

        return result
    }

    /** 兜底：本地 PackageManager（受包可见性限制，只认看得见的包）；同样一个 uid 收全部可见包。 */
    private fun readPackageUidTableLocal(): Map<Int, List<String>> {

        val result = HashMap<Int, MutableList<String>>(256)

        val packages = runCatching { packageManager.getInstalledPackages(0) }.getOrNull()
            ?: return emptyMap()

        for (pkg in packages) {

            val info = pkg.applicationInfo ?: continue

            val list = result.getOrPut(info.uid) { ArrayList(2) }

            if (pkg.packageName !in list) list.add(pkg.packageName)
        }

        return result
    }

    // ---------- 包信息（targetSdk / 应用名） ----------

    /** 一个包能回答的两格；两份都可能为 null（例如只有 shell 能看到 targetSdk）。 */
    private data class PkgMeta(
        val targetSdk: Int? = null,
        val label: String? = null
    )

    private val metaCache = HashMap<String, PkgMeta>()

    /** 本地 PackageManager 试过的包（试过且查不到的不再每轮重复抛异常）。 */
    private val localTried = HashSet<String>()

    /** 等 shell 补信息的包队列；[metaQueued] 保证同一个包只排一次。 */
    private val metaQueue = java.util.concurrent.LinkedBlockingQueue<String>()

    private val metaQueued = HashSet<String>()

    @Volatile
    private var metaWorkerStarted = false

    /**
     * 取一个包的两格信息，查不到返回 null（调用方保留"暂无数据"）。
     *
     * 顺序：① 本地 PackageManager——可见包（含本应用、系统里被可见性放行的包）直接就有
     * targetSdk 与应用名，不用起 shell；② 见不到就排进后台队列，用 `dumpsys package` 补。
     */
    private fun metaFor(pkg: String): PkgMeta? {

        synchronized(indexLock) { metaCache[pkg] }?.let { return it }

        val local = readLocalMetaOnce(pkg)

        if (local != null) {

            synchronized(indexLock) { metaCache[pkg] = local }

            return local
        }

        enqueueMeta(pkg)

        return null
    }

    /** 本地 PackageManager 读一次；每包只试一次（不可见的包会抛 NameNotFound，别每轮都抛）。 */
    private fun readLocalMetaOnce(pkg: String): PkgMeta? {

        synchronized(indexLock) {

            if (pkg in localTried) return null

            localTried.add(pkg)
        }

        return try {

            val info = packageManager.getApplicationInfo(pkg, 0)

            val label = runCatching { info.loadLabel(packageManager)?.toString() }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != pkg }

            PkgMeta(targetSdk = info.targetSdkVersion, label = label)

        } catch (e: Exception) {

            null
        }
    }

    /** 把包排进后台补齐队列（队列里的活一条条跑，两条之间留 [META_QUERY_GAP_MS]）。 */
    private fun enqueueMeta(pkg: String) {

        synchronized(indexLock) {

            if (pkg in metaQueued) return

            metaQueued.add(pkg)
        }

        metaQueue.offer(pkg)

        ensureMetaWorker()
    }

    private fun ensureMetaWorker() {

        synchronized(indexLock) {

            if (metaWorkerStarted) return

            metaWorkerStarted = true
        }

        Thread {

            while (true) {

                val pkg = try {
                    metaQueue.take()
                } catch (e: InterruptedException) {
                    return@Thread
                }

                try {

                    val meta = readShellMeta(pkg)

                    if (meta != null) {

                        synchronized(indexLock) { metaCache[pkg] = meta }
                    }

                } catch (e: Throwable) {

                    Log.i(TAG, "包信息查询失败：$pkg ${e.javaClass.simpleName}")
                }

                try {
                    Thread.sleep(META_QUERY_GAP_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "TMAndroid-PkgMeta"
        }.start()
    }

    /**
     * 用 shell 跑 `dumpsys package <包名>`，只捞 targetSdk 与 nonLocalizedLabel 两行。
     *
     * 为什么还要 dumpsys：不可见的包本地 PackageManager 直接报 NameNotFound，
     * 而 targetSdk 与 labelRes 这些事实在 dumpsys 的包信息里；labelRes 指向资源，
     * shell 解析不了资源表，所以能拿到文字名的情况只有"应用自己写了非本地化标签"。
     */
    private fun readShellMeta(pkg: String): PkgMeta? {

        val script = "dumpsys package " + shellQuote(pkg) +
            " 2>/dev/null | grep -E 'targetSdk=|nonLocalizedLabel=' | head -n 4"

        val output = runShell(script, META_QUERY_TIMEOUT_MS) ?: return null

        val targetSdk = Regex("targetSdk=(\\d+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        val label = Regex("nonLocalizedLabel=(.*)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" && it != pkg }

        // 归档兜底：名字写在资源表里的包（绝大多数）dumpsys 只会给 null，得自己解 APK 资源
        val archived = if (label == null || targetSdk == null) archivedMeta(pkg) else null

        val finalTargetSdk = targetSdk ?: archived?.targetSdk
        val finalLabel = label ?: archived?.label

        if (finalTargetSdk == null && finalLabel == null) return null

        return PkgMeta(targetSdk = finalTargetSdk, label = finalLabel)
    }

    /**
     * 从 APK 归档里读应用名与 targetSdk（读不到返回 null）。
     *
     * 为什么要这条：`dumpsys package` 只给 `nonLocalizedLabel` —— 只有把名字写成非本地化字符串的
     * 应用才有值，正常写在资源表里的应用这里是 null。而 APK 里的名字是资源引用，得用这个包自己的
     * 资源表解析；图标已经在走同一套路（[loadApplicationIcon]：`pm path` → 归档解析 → loadIcon），
     * 读名字只是把 loadIcon 换成 loadLabel。
     *
     * 只在包信息后台补齐线程里调用（会过一次 shell 拿 APK 路径），不要在采集线程上调。
     */
    private fun archivedMeta(pkg: String): PkgMeta? {

        val path = apkPathFor(pkg, Process.myUid() / 100000) ?: return null

        val archive = runCatching { packageManager.getPackageArchiveInfo(path, 0) }.getOrNull()
            ?: return null

        val info = archive.applicationInfo ?: return null

        // 归档解析出来的 ApplicationInfo 要补上 sourceDir，否则资源表打不开
        info.sourceDir = path
        info.publicSourceDir = path

        val label = runCatching { info.loadLabel(packageManager)?.toString() }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != pkg }

        return PkgMeta(
            targetSdk = info.targetSdkVersion.takeIf { it > 0 },
            label = label
        )
    }

    // ---------- 应用图标（详细信息页名称前的图标槽） ----------
    //
    // 图标是独立的"图标槽"，只按**包名**取、按包名缓存：同一个包的多个进程共用一张图，
    // 不会一行解码一次。取不到就保持"没有图标"，绝不拿别的图冒充。

    /** 包名 -> 图标；value 为 null 也表示"试过但没拿到"（不重复解码同一个包）。 */
    private val iconCache = HashMap<String, Drawable?>()

    /** 包名 -> APK 路径（拿不到也记 null）。 */
    private val apkPathCache = HashMap<String, String?>()

    /** 图标缓存上限：超过就整表清空（进程相关页用到的包本来也就几十个）。 */
    private val ICON_CACHE_LIMIT = 256

    private val iconLock = Any()

    /**
     * 取一个包的应用图标。
     *
     * 阻塞（可能要过一次 shell 拿 APK 路径 + 解码资源），**调用方必须放在后台线程**；
     * 结果按包名缓存，同一包第二次起直接返回。
     *
     * @return null = 这个包没有可用的图标（不可见且 APK 路径也拿不到）
     */
    /**
     * 查一个包在 Android 侧登记的服务记录（`dumpsys activity services <pkg>`）。
     *
     * 阻塞（要走一次 shell / root 通道），**调用方必须放在后台线程**；
     * 包名先做一次白名单校验，绝不让它变成 shell 注入点。
     *
     * @return 原始文本（截到 [SERVICE_RECORDS_MAX] 字符）；null = 通道不可用
     */
    fun serviceRecords(pkg: String): String? {

        if (pkg.isBlank()) return null

        // 包名只允许这些字符：dumpsys 的参数直接拼进 shell 脚本，必须先把注入面关掉
        if (pkg.any { !(it.isLetterOrDigit() || it == '.' || it == '_' || it == ':') }) return null

        val text = runShell("dumpsys activity services $pkg 2>/dev/null", SERVICE_RECORDS_TIMEOUT_MS)

        return text?.trim()?.take(SERVICE_RECORDS_MAX)
    }

    /** 服务记录文本上限：只给对话框看，几 KB 足够，别把整份 dumpsys 拖进内存。 */
    private val SERVICE_RECORDS_MAX = 6000

    private val SERVICE_RECORDS_TIMEOUT_MS = 6_000L

    /** appops get 的输出形如 `GET_USAGE_STATS: allow; time=+...`，只认 allow。 */
    private val USAGE_STATS_ALLOW_PATTERN =
        Regex("GET_USAGE_STATS\\s*:\\s*allow", RegexOption.IGNORE_CASE)

    private val usageStatsOpLock = Any()

    /** 上次确认时用的身份（root / Shizuku）；换身份就得重新确认一次。 */
    private var usageStatsOpIdentity: String? = null

    private var usageStatsOpGranted: Boolean? = null

    /**
     * 按 uid 反查包名候选（uidIndex 里登记的全部包，含共享 uid 的多个包）。
     *
     * 应用历史记录页把网络计数按 uid 归属到包时用；返回 null = 索引里没有这个 uid
     * （隔离沙箱 / 内核原生 uid），调用方不要把它算给任何包。
     */
    fun packagesForUid(uid: Int): List<String>? = uidIndex.get()[uid]

    /**
     * 确保本应用已拿到「使用情况访问」（appop GET_USAGE_STATS），
     * 这是 NetworkStatsManager 按 uid 读流量统计的前提。
     *
     * 做法：走现成的提权通道执行 `appops set <pkg> GET_USAGE_STATS allow`，
     * 再用 `appops get` 回读确认真的是 allow（不是被 ROM 忽略或写失败）。
     * 结果按当前身份缓存：同一身份下只做一次，身份切换（root ↔ Shizuku）后下次再确认。
     *
     * 阻塞（最多两次 shell 往返），**调用方必须放在后台线程**。
     *
     * @return true = 已确认 allow；false = 提权通道不可用 / 命令失败 / 仍是 deny，
     *         此时网络列必须显示「—」，不要拿 0 冒充
     */
    fun ensureUsageStatsAppOp(): Boolean {

        val identity = identityMode

        synchronized(usageStatsOpLock) {

            if (usageStatsOpIdentity == identity && usageStatsOpGranted == true) return true
        }

        val pkg = packageName

        val output = runShell(
            "appops set $pkg GET_USAGE_STATS allow; " +
                "appops get $pkg GET_USAGE_STATS",
            SHELL_TIMEOUT_MS
        )

        val granted = output != null && USAGE_STATS_ALLOW_PATTERN.containsMatchIn(output)

        synchronized(usageStatsOpLock) {
            usageStatsOpIdentity = identity
            usageStatsOpGranted = granted
        }

        return granted
    }

    fun applicationIcon(pkg: String, userId: Int = Process.myUid() / 100000): Drawable? {

        val cacheKey = "$userId:$pkg"

        synchronized(iconLock) {
            if (iconCache.containsKey(cacheKey)) return iconCache[cacheKey]
        }

        val icon = loadApplicationIcon(pkg, userId)

        synchronized(iconLock) {

            if (iconCache.size >= ICON_CACHE_LIMIT) iconCache.clear()

            iconCache[cacheKey] = icon
        }

        return icon
    }

    /**
     * 图标加载：当前用户可以直接用 PackageManager.loadIcon；
     * 其他 Android 用户里的包在当前用户的 PackageManager 里往往不可见，也拿不到
     * 那个用户的安装路径，所以先按 `pm path --user <id>` 取该用户的 APK 路径，
     * 再用 [android.content.pm.PackageManager.getPackageArchiveInfo] 解析归档并 loadIcon。
     */
    private fun loadApplicationIcon(pkg: String, userId: Int): Drawable? {

        if (userId == Process.myUid() / 100000) {

            try {

                val info = packageManager.getApplicationInfo(pkg, 0)

                info.loadIcon(packageManager)?.let { return it }

            } catch (e: Throwable) {

                // NameNotFoundException：包对本应用不可见，走下面的归档解析
            }
        }

        val path = apkPathFor(pkg, userId) ?: return null

        // getPackageArchiveInfo 回的是 PackageInfo，图标挂在它的 applicationInfo 上
        val archive = try {

            packageManager.getPackageArchiveInfo(path, 0)

        } catch (e: Throwable) {

            null

        } ?: return null

        val info = archive.applicationInfo ?: return null

        // 归档解析出来的 ApplicationInfo 要补上 sourceDir，否则 loadIcon 找不到资源表
        info.sourceDir = path
        info.publicSourceDir = path

        return try {

            info.loadIcon(packageManager)

        } catch (e: Throwable) {

            null
        }
    }

    /** 某个 Android 用户里一个包的 APK 路径；给详细信息页「打开位置」用。 */
    fun applicationApkPath(pkg: String, userId: Int = Process.myUid() / 100000): String? =
        apkPathFor(pkg, userId)

    /** 某个 Android 用户里一个包的 APK 路径；拿不到返回 null，也不会重复问。 */
    private fun apkPathFor(pkg: String, userId: Int): String? {

        val cacheKey = "$userId:$pkg"

        synchronized(iconLock) {
            if (apkPathCache.containsKey(cacheKey)) return apkPathCache[cacheKey]
        }

        // 其他用户里的包必须带 --user，否则 pm 只查当前用户，多半报 not found
        val userArg = if (userId == Process.myUid() / 100000) "" else " --user $userId"

        val output = runShell(
            "pm path$userArg " + shellQuote(pkg),
            SHELL_TIMEOUT_MS
        )

        val path = output?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("package:") && it.endsWith(".apk") }
            ?.removePrefix("package:")

        synchronized(iconLock) { apkPathCache[cacheKey] = path }

        return path
    }

    // ---------- 提权通道执行 ----------

    /**
     * 以提权通道跑一段 sh 脚本，读完整输出；通道不可用 / 超时 / 出错都返回 null。
     *
     * 为什么垫一层 `sh -c`：要干的是"一条命令里做多件事"（循环读 /proc、两条 list 串联、
     * 管道过滤），而 Shizuku 的 newProcess 和 su 的 `-c` 都是"跑一个程序"的语义。
     *
     * 通道顺序与自检一致：优先身份是 root 就先试 root（Shizuku 自己跑成 root 时也算），
     * 否则先试 Shizuku，失败再换另一条。
     */
    private fun runShell(script: String, timeoutMs: Long = SHELL_TIMEOUT_MS): String? {

        val command = listOf("/system/bin/sh", "-c", script)

        val preferRoot = identityMode == UpstreamIdentityMode.ROOT

        val shizukuFitsTarget = !preferRoot ||
            runCatching { ShizukuManager.status.value.isRoot }.getOrDefault(false)

        val first = if (shizukuFitsTarget) {
            execCapture(ShizukuManager.newShellProcess(command), timeoutMs, "shizuku")
        } else {
            rootScript(script, timeoutMs)
        }

        if (first != null) return first

        return if (shizukuFitsTarget) {
            rootScript(script, timeoutMs)
        } else {
            execCapture(ShizukuManager.newShellProcess(command), timeoutMs, "shizuku")
        }
    }

    /**
     * 走 root 通道跑脚本。
     *
     * 用 [RootManager.execScript] 而不是"每条命令起一个 su"：后者会让 root 管理器
     * 每查一次包就弹一次授权框（包表 30 秒重建、每个包的 dumpsys 都会各起一条）。
     * 现在所有脚本复用同一条长驻 root shell，授权一次就不再弹。
     */
    private fun rootScript(script: String, timeoutMs: Long): String? {

        return runCatching { RootManager.execScript(script, timeoutMs) }.getOrNull()
    }

    /**
     * 跑完一个进程并读回 stdout。
     *
     * 为什么"读输出"必须和"等退出"并行：dumpsys 的输出远超管道缓冲（64KB），
     * 先 waitFor 再读会双方死等 —— 子进程写满缓冲阻塞，我们也永远等不到它退出。
     * 这里让读线程先跑，主线只等它读到 EOF 或超时。
     */
    private fun execCapture(process: java.lang.Process?, timeoutMs: Long, channel: String): String? {

        if (process == null) return null

        val output = StringBuilder()

        val reader = Thread {

            try {

                output.append(process.inputStream.bufferedReader().use { it.readText() })

            } catch (e: Throwable) {

                // 远端提前断开：已经读到的部分照样有用
            }
        }.apply {

            isDaemon = true
            name = "TMAndroid-ShellRead"
            start()
        }

        try {

            reader.join(timeoutMs)

            // 还在读 = 子进程没结束（或没关管道）：按超时处理，别把采集线程吊死
            if (reader.isAlive) return null

            return output.toString()

        } catch (e: Throwable) {

            Log.i(TAG, "shell($channel) 失败：${e.javaClass.simpleName}")

            return null

        } finally {

            try {
                process.destroy()
            } catch (e: Throwable) {
                // 已经退出：忽略
            }
        }
    }

    /** 单引号转义，供脚本内部拼命令用（包名一般安全，仍然防一手）。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 读取 Android 侧数据（当前仅用于验证服务可用性）。
     *
     * 预留实现接口：后续用上面类注释列出的方法替换本方法，
     * 由 UI 层按页面需求分别调用。
     */
    fun GetDataFromAndroidF(): String {

        record("GetDataFromAndroidF()")

        // 只报告 uid：本服务不对外解释权限是从 Shizuku 还是 su 借来的，
        // 那套"身份来源"模型只属于 UIService 与设置页（调试页看 uid 就够定位问题）。
        val identity = if (privilegedMode) AppStrings.s(R.string.asvc_data_elevated) else AppStrings.s(R.string.asvc_data_app)

        return AppStrings.s(R.string.asvc_identity_line, Process.myUid(), identity)
    }

    /**
     * 读 Android 用户名字的兜底通道。
     *
     * `UserManager.getUserName()` / `getUsers()` 都要 MANAGE_USERS，普通应用调用会被
     * SecurityException 拒绝，之前拿不到名字就会退回「用户 0」。这里用提权通道执行
     * `dumpsys user`，从 `UserInfo{0:机主:...}` 里解析，结果缓存 10 分钟，
     * 避免用户页每秒轮询都去起一次 shell。
     */
    fun androidUserNames(): Map<Int, String> {

        // 没有提权通道就不要去起 shell：普通应用读了也只会超时
        if (!privilegedMode) return emptyMap()

        val now = SystemClock.elapsedRealtime()

        synchronized(userNameLock) {
            if (now - userNameCacheAt < USER_NAME_CACHE_MS) {
                return userNameCache
            }
        }

        val output = runShell("dumpsys user 2>/dev/null", USER_NAME_TIMEOUT_MS) ?: ""

        val names = HashMap<Int, String>()

        // 一行一条 UserInfo{<id>:<name>:<flags>}，name 本身可能带冒号
        // （例如 "User with : (colon):10"），所以取 id 后的第一个冒号和右括号前的
        // 最后一个冒号，不能只用简单正则切到第一个冒号。
        output.lineSequence().forEach { line ->

            val start = line.indexOf("UserInfo{")
            if (start < 0) return@forEach

            val content = line.substring(start + "UserInfo{".length)
                .substringBefore('}')

            val firstColon = content.indexOf(':')
            val lastColon = content.lastIndexOf(':')

            if (firstColon <= 0 || lastColon <= firstColon) return@forEach

            val id = content.substring(0, firstColon).trim().toIntOrNull()
                ?: return@forEach

            val name = content.substring(firstColon + 1, lastColon).trim()
            if (name.isNotEmpty()) names[id] = name
        }

        synchronized(userNameLock) {
            userNameCache = names
            userNameCacheAt = now
        }

        return names
    }

    /**
     * 读某个 Android 用户的头像原始 PNG/JPEG。
     *
     * Framework 的 `UserManager.getUserIcon()` 是隐藏且受 MANAGE_USERS 保护的接口，
     * 普通应用拿不到；这里只走 root 读系统保存头像的固定路径
     * `/data/system/users/<id>/photo.*`。Shizuku 的 shell 身份读不到这个目录，
     * 所以不再回退到 shell；读失败就返回 null，由界面显示通用账户图标。
     */
    fun userAvatarPng(userId: Int): ByteArray? {

        if (userId < 0 || !privilegedMode) return null

        val output = rootScript(
            "f=/data/system/users/$userId/photo.png; " +
                "[ -f \"\$f\" ] || f=/data/system/users/$userId/photo.jpg; " +
                "[ -f \"\$f\" ] && base64 \"\$f\" 2>/dev/null",
            USER_AVATAR_TIMEOUT_MS
        )?.trim()

        if (output.isNullOrEmpty()) return null

        return runCatching {
            Base64.decode(output, Base64.DEFAULT)
        }.getOrNull()
    }


    // ============================================================
    // SYSTEM 设备数据（Android 层）
    //
    // 这一组是"Linux 内核不知道、只有 Android Framework 能回答"的系统级事实，
    // 对应设备快照里 type=SYSTEM 的那台设备。现在只有 AVF 一项。
    //
    // 为什么分两步：
    // 1) 有没有 AVF 组件：SDK >= 34 且 AVF 的 APEX 目录存在。
    //    没有组件就根本不讨论"支持不支持"，界面上整行都不出现；
    // 2) 组件在的时候才问框架：PackageManager 有没有 virtualization 特性，
    //    有 = 是，没有 = 否。
    //
    // 两步都只与固件有关，各自缓存一次结果，不能每秒都查包管理器。
    // ============================================================

    /**
     * 取 Android 层的 SYSTEM 设备字段。
     *
     * avfComponentPresent 决定这一行出不出来；avfSupported 只在组件存在的设备上才有意义。
     */
    fun systemFacts(): AndroidSystemFacts {

        if (!shouldShowAvf()) {

            return AndroidSystemFacts(avfComponentPresent = false, avfSupported = null)
        }

        return AndroidSystemFacts(
            avfComponentPresent = true,
            avfSupported = avfSupportedOrNull()
        )
    }

    @Volatile
    private var cachedShouldShowAvf: Boolean? = null

    /**
     * 系统里到底有没有 AVF 组件。
     *
     * AVF 从 Android 14 起以 APEX 形式独立升级，目录存在就说明这套组件装在机器里；
     * 低于 14 的系统一律当作没有。
     */
    private fun shouldShowAvf(): Boolean {

        cachedShouldShowAvf?.let { return it }

        val result = Build.VERSION.SDK_INT >= 34 && File(AVF_APEX_PATH).exists()

        cachedShouldShowAvf = result

        return result
    }

    @Volatile
    private var cachedAvfSupported: Boolean? = null

    @Volatile
    private var avfSupportedProbed: Boolean = false

    /**
     * 这台设备是否支持 AVF（只在组件存在时问）。
     *
     * 判据是 PackageManager 的系统特性；查询本身出错时返回 null（界面就不显示结论）。
     */
    private fun avfSupportedOrNull(): Boolean? {

        if (avfSupportedProbed) return cachedAvfSupported

        synchronized(indexLock) {

            if (avfSupportedProbed) return cachedAvfSupported

            val result = runCatching {
                packageManager.hasSystemFeature(AVF_FEATURE)
            }.getOrNull()

            cachedAvfSupported = result
            avfSupportedProbed = true

            record(AppStrings.s(R.string.asvc_avf_supported, result?.let { if (it) AppStrings.s(R.string.data_yes) else AppStrings.s(R.string.data_no) } ?: AppStrings.s(R.string.asvc_not_read)))

            return result
        }
    }

    /** 最近的输出快照（UIService.upstreamOutput() 调它）。 */
    fun outputSnapshot(): String = synchronized(lineLock) {

        if (lines.isEmpty()) {
            return AppStrings.s(R.string.asvc_no_output_yet)
        }

        buildString {

            append(AppStrings.s(R.string.asvc_output_header) + "`n")

            lines.forEach { append(it).append('\n') }
        }
    }

    /**
     * 跑一次 `id`，证明"提权能力通道"真的通了（Shizuku 优先，其次 root）。
     *
     * 放到后台线程：newShellProcess 要过一次 IPC、su 可能等到授权，主线程不该等。
     * 结果不管成功失败都写进输出缓冲，调试页能直接看到结论。
     */
    private fun probeIdentity() {

        Thread {

            val text = try {

                val command = listOf("/system/bin/sh", "-c", "id")

                // 自检顺序只按"目标 uid"排，不按权限来源：
                // - 目标是 uid 0 时，只有 Shizuku 自己跑成 root 才算数，否则先 su；
                // - 目标是 uid 2000 / auto 时，先试 Shizuku（已授权就不用再弹授权框）。
                val preferRoot = identityMode == UpstreamIdentityMode.ROOT

                val shizukuFitsTarget = !preferRoot ||
                    runCatching { ShizukuManager.status.value.isRoot }.getOrDefault(false)

                val first = if (shizukuFitsTarget) probeWithShizuku(command) else probeWithRoot(command)

                val second = if (shizukuFitsTarget) probeWithRoot(command) else probeWithShizuku(command)

                first ?: second ?: AppStrings.s(R.string.asvc_elevation_selfcheck_failed)

            } catch (e: Throwable) {

                AppStrings.s(R.string.asvc_elevation_selfcheck_error, e.javaClass.simpleName, e.message ?: "")
            }

            Log.i(TAG, text)

            mainHandler.post {
                record(text)
            }
        }.apply {

            isDaemon = true
            name = "TMAndroid-IdentityProbe"
        }.start()
    }

    /**
     * 用 Shizuku 通道跑一次 `id`，成功返回"自检：<id 输出>"；不可用时返回 null。
     *
     * 返回 null 只表示"这条通道没走通"，具体原因由调用方拼进最终文案。
     */
    private fun probeWithShizuku(command: List<String>): String? {

        if (!runCatching { ShizukuManager.status.value.isAuthorized }.getOrDefault(false)) {

            record(AppStrings.s(R.string.asvc_shizuku_selfcheck_skipped))

            return null
        }

        val process = try {
            ShizukuManager.newShellProcess(command)
        } catch (e: Throwable) {
            record(AppStrings.s(R.string.asvc_shizuku_selfcheck_error, e.javaClass.simpleName))

            null
        } ?: return null

        // 结论只报 id 输出（里面就是 uid=...），不带"这是从哪条通道来的"
        return AppStrings.s(R.string.asvc_selfcheck, readIdentity(process))
    }

    /** 用 su 通道跑一次 `id`，成功返回"自检：<id 输出>"；不可用时返回 null。 */
    private fun probeWithRoot(command: List<String>): String? {

        if (!runCatching { RootManager.isAvailable }.getOrDefault(false)) {

            record(AppStrings.s(R.string.asvc_root_selfcheck_skipped))

            return null
        }

        // 走长驻 root shell：自检不再额外弹一次授权框，拿到的会话后续查询继续用
        val output = try {
            RootManager.execScript("id", SHELL_TIMEOUT_MS)
        } catch (e: Throwable) {
            record(AppStrings.s(R.string.asvc_root_selfcheck_error, e.javaClass.simpleName))

            null
        } ?: return null

        return AppStrings.s(R.string.asvc_selfcheck, output.trim().replace("\n", " "))
    }

    /** 读一个短命进程的 id 输出并销毁它（自检专用，命令不会久留）。 */
    private fun readIdentity(process: java.lang.Process): String {
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Throwable) {
            AppStrings.s(R.string.asvc_read_output_failed, e.javaClass.simpleName)
        } finally {
            try {
                process.destroy()
            } catch (e: Throwable) {
                // 已退出：忽略
            }
        }

        return output.replace("\n", " ")
    }

    /** 追加一行输出（超过上限丢最旧的）。 */
    private fun record(line: String) {

        val stamped = "[${System.currentTimeMillis() % 100000}] $line"

        synchronized(lineLock) {

            lines.addLast(stamped)

            while (lines.size > KEEP_LINES) {
                lines.removeFirst()
            }
        }
    }
}

// ============================================================
// 进程补充查询的数据形状（同进程直连，不走文本协议）
// ============================================================

/**
 * 一次进程补充查询的键。
 *
 * @param pid        Linux P 行行首的进程号
 * @param startTicks 同一行的 start_ticks（0 合法、-1 未知）。
 *                   只用于让调用方把补充字段并回"哪一行"，本服务不解析、不校验 ——
 *                   Android 侧拿不到可靠的进程启动时刻，硬校验会把活着的进程判成换了人；
 *                   防 PID 复用由 Linux 侧的 pid + start_ticks 保证
 * @param uid        同一行的 uid 扩展键；null = 后端没取到，此时无法定位到应用
 * @param processName 同一行 cmdline（`/proc/<pid>/cmdline`）的第一个参数，也就是进程名。
 *                   **同 uid 多包时靠它定包**（uid 1000 下 systemui 与 screenrecord 并存，
 *                   只看 uid 必然张冠李戴）；没采到时只能是"该 uid 只有一个包"才敢下结论
 */
data class ProcessKey(
    val pid: Int,
    val startTicks: Long,
    val uid: Int? = null,
    val processName: String? = null
)

/**
 * Android 侧补出来的五格（Linux 那侧回的都是 NOT_AVAILABLE）。
 *
 * 取值来源都是 Android Framework 的事实：包的归属、目标 SDK、应用名、框架给的分档优先级；
 * 具体怎么查（shell / 本地 PackageManager）由本服务决定，调用方不需要知道。
 *
 * 每个字段 null = 这一格 Android 也给不出（不可见包的应用名、读不到的分档等），
 * 调用方必须保留 Linux 的"暂无数据"，绝不填 0 或空串冒充有值。
 *
 * [isolation] 一定有结论：YES = 该进程属于 Android Framework 管的进程（uid 在包表里），
 * NO = 纯 Linux 原生进程。这是"要不要继续查另外四格"的判据，不是内核的隔离命名空间。
 */
data class ProcessSupplement(
    val packageName: String? = null,
    val targetSdk: Int? = null,
    val description: String? = null,
    val androidPriority: Int? = null,
    val isolation: String? = null
)

/**
 * Android 层的 SYSTEM 设备字段。
 *
 * 对应设备快照里 type=SYSTEM 的那台设备：Linux 内核不知道、只有 Framework 能回答的
 * 系统级事实。现在只有 AVF 一项，以后开新字段就往这里加，
 * 由 UIService 合并到性能页那一帧（页面只判"有没有值"）。
 */
data class AndroidSystemFacts(
    /**
     * 系统里有没有 AVF 组件（APEX 目录在，且系统是 Android 14 及以上）。
     *
     * false = 界面上整行都不出现，连"AVF 支持: 否"也不给；
     * true  = 显示这一行，结论看 [avfSupported]。
     */
    val avfComponentPresent: Boolean = false,

    /**
     * 是否支持 AVF，只在组件存在时才有意义。
     *
     * true = 是，false = 否，null = 查询出错（这一行先不显示结论）。
     */
    val avfSupported: Boolean? = null
)

