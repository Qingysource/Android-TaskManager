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

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.android.taskmanager.UpstreamIdentityMode
import com.android.taskmanager.service.RootManager
import com.android.taskmanager.service.ShizukuManager

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
         * uid 索引的软过期时间。
         *
         * 用户装 / 卸 / 更新应用后，索引最多这么久重建一次；
         * 不挂系统广播是为了避开接收器导出标志的兼容问题（见 [ensureUidIndex]）。
         */
        private const val INDEX_TTL_MS = 60_000L

        /** 隔离进程 uid 段的起止（AID_ISOLATED_START / AID_ISOLATED_END）。 */
        private const val ISOLATED_UID_START = 99000
        private const val ISOLATED_UID_END = 99999

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
                if (privilegedMode) "身份切换：提权模式（优先 $identityMode，可用才生效）"
                else "身份切换：应用自身 UID"
            )
        }

        if (privilegedMode) {
            probeIdentity()
        } else {
            record("跳过提权自检（当前不是提权模式）")
        }

        // 预热 uid 索引：详细页把 Linux 行转表行时是同步调用本服务的 queryProcess，
        // 第一次查询不该让调用方（很可能在刷新路径上）陪着 PackageManager 扫一遍全量包。
        startUidIndexWarmUp()

        // 不粘连启动：进程被杀后不自动重建，避免后台服务被系统反复拉起
        return START_NOT_STICKY
    }

    /** 后台把 uid 索引建起来；失败不影响任何功能，查询时还会再试。 */
    private fun startUidIndexWarmUp() {

        Thread {

            runCatching { ensureUidIndex() }

        }.apply {
            isDaemon = true
            name = "TMAndroid-UidIndex"
        }.start()
    }

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
        val identity = if (privilegedMode) "取数可提权" else "取数仅应用自身"

        return "Android UID: ${Process.myUid()} | $identity"
    }

    // ============================================================
    // 进程补充查询（给详细信息页补 Linux 拿不到的那五格）
    //
    // 为什么是"按 pid 单查"而不是"把 Linux 的整份快照传进来"：
    // Linux 的 P 行本来就是 /proc 的权威结果，再整表搬一次没有任何收益；
    // 真正缺的只有 package_name / target_sdk / description /
    // android_priority / isolation 这五格，一个进程查一次就够。
    //
    // 为什么不做防 PID 复用校验：Android 侧拿不到可靠的进程启动时刻，
    // 硬校验只会把活着的进程判成"换了人"。startTicks 由调用方带着走、
    // 原样回传，它只用于"把补充字段并回正确的行"，防复用由 Linux 侧保证。
    //
    // 线程：调用方（UIService 的采集线程）同步调用，本方法不派发、不回调。
    // ============================================================

    /**
     * 查一个进程的 Android 侧补充字段。
     *
     * @param key pid + startTicks + uid；uid 来自 Linux P 行的 uid 扩展键
     * @return 五格中能确定的那部分；查不到应用身份（uid 缺失 / 不是已装应用）返回 null
     */
    fun queryProcess(key: ProcessKey): ProcessSupplement? {

        val uid = key.uid ?: return null

        val facts = ensureUidIndex()[uid] ?: return null

        // 隔离进程的 uid 由内核固定分配在 99000..99999（AID_ISOLATED_START..END）。
        // 公开 API 只有"当前进程"版本的 Process.isIsolated()，按 uid 段判才看得到别人。
        val isolated = uid in ISOLATED_UID_START..ISOLATED_UID_END

        return ProcessSupplement(
            packageName = facts.packageName,
            targetSdk = facts.targetSdk,
            description = facts.label,
            // 进程调度优先级走 /proc/<pid>/oom_score_adj，归 Linux 侧读（它有权限），
            // Android 侧看不到别的进程的 importance，这里不填，保持"暂无数据"
            androidPriority = null,
            isolation = if (isolated) "已隔离" else "未隔离"
        )
    }

    /** uid -> 应用档案（建一次，查询时 O(1)）。 */
    private data class AppFacts(
        val packageName: String,
        val targetSdk: Int,
        val label: String?,
        val isSystem: Boolean
    )

    private val uidIndexLock = Any()

    @Volatile
    private var uidIndex: Map<Int, AppFacts>? = null

    @Volatile
    private var uidIndexAtMs: Long = 0L

    /**
     * 拿 uid 索引，过期就重建。
     *
     * 为什么不挂 PACKAGE_ADDED 之类的广播：那要动态注册接收器，
     * 还要处理"导出标志"在系统广播上的兼容问题；索引本身只要几毫秒，
     * 用一分钟的软过期就够了 —— 用户装完应用，最迟一分钟后新进程就能认出包名。
     */
    private fun ensureUidIndex(): Map<Int, AppFacts> {

        val cached = uidIndex

        if (cached != null && SystemClock.elapsedRealtime() - uidIndexAtMs < INDEX_TTL_MS) {
            return cached
        }

        synchronized(uidIndexLock) {

            val again = uidIndex

            if (again != null && SystemClock.elapsedRealtime() - uidIndexAtMs < INDEX_TTL_MS) {
                return again
            }

            val built = buildUidIndex()

            uidIndex = built
            uidIndexAtMs = SystemClock.elapsedRealtime()

            return built
        }
    }

    /**
     * 扫一遍已安装包，建 uid -> 应用档案。
     *
     * 注意这**不是**每个进程扫一次：整个索引只建一次，
     * 之后五百个进程查询就是五百次 HashMap 取值，没有 PackageManager 开销。
     *
     * 同一 uid 可能对应多个包（共享 uid、应用分身）：优先保留非系统包，
     * 都是非系统就保留先扫到的，保证同一个 uid 每次查到的包名稳定。
     */
    private fun buildUidIndex(): Map<Int, AppFacts> {

        val result = HashMap<Int, AppFacts>(256)

        val packages = runCatching {
            packageManager.getInstalledPackages(0)
        }.getOrNull() ?: return result

        for (pkg in packages) {

            val info = pkg.applicationInfo ?: continue

            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            val existing = result[info.uid]

            if (existing != null && (!existing.isSystem || isSystem)) {
                continue
            }

            val label = runCatching { info.loadLabel(packageManager)?.toString() }.getOrNull()

            result[info.uid] = AppFacts(
                packageName = pkg.packageName,
                targetSdk = info.targetSdkVersion,
                // 标签与包名相同时不重复显示：那一格留给"真正有描述"的应用
                label = label?.trim()?.takeIf { it.isNotEmpty() && it != pkg.packageName },
                isSystem = isSystem
            )
        }

        record("应用索引就绪：${result.size} 个 uid / ${packages.size} 个包")

        return result
    }

    // ============================================================
    // SYSTEM 设备数据（Android 层）
    //
    // 这一组是"Linux 内核不知道、只有 Android Framework 能回答"的系统级事实，
    // 对应设备快照里 type=SYSTEM 的那台设备。现在只有 AVF 支持一项。
    //
    // 为什么放在 Android 侧：AVF（Android Virtualization Framework）是 Framework
    // 与 bootloader 共同决定的能力，/proc 与 sysfs 里没有可靠答案。
    // ============================================================

    /**
     * 取 Android 层的 SYSTEM 设备字段。
     *
     * 结果只与设备固件有关，探测一次就缓存，不能每秒都起子进程读属性。
     */
    fun systemFacts(): AndroidSystemFacts = AndroidSystemFacts(
        avfSupported = avfSupportedOrNull()
    )

    @Volatile
    private var cachedAvfSupported: Boolean? = null

    @Volatile
    private var avfProbed: Boolean = false

    /**
     * 这台设备是否支持 AVF。
     *
     * 两条独立信号：
     * 1) API 34 起有 `virtualization` 系统服务 —— 那是 AVF 对外的门面，
     *    只有真支持 AVF 的设备才会注册它；
     * 2) 属性 `ro.boot.hypervisor.vm.supported` —— AVF / pKVM 打开时由 bootloader 置 1。
     *
     * @return true = 支持；false = 明确不支持；null = 两条都问不出来（按"不提供"处理）
     */
    private fun avfSupportedOrNull(): Boolean? {

        if (avfProbed) return cachedAvfSupported

        synchronized(uidIndexLock) {

            if (avfProbed) return cachedAvfSupported

            val result = detectAvf()

            cachedAvfSupported = result
            avfProbed = true

            record("AVF 探测：" + (result?.let { if (it) "支持" else "不支持" } ?: "未取到"))

            return result
        }
    }

    /** 真正的探测：先问系统服务，再退到 bootloader 属性。 */
    private fun detectAvf(): Boolean? {

        // 用字符串取名而不是常量：常量是 API 34 才有的，老设备编译期用不到
        if (Build.VERSION.SDK_INT >= 34) {

            val service = runCatching { getSystemService("virtualization") }.getOrNull()

            if (service != null) return true
        }

        val property = readSystemProperty("ro.boot.hypervisor.vm.supported")

        if (property != null) return property == "1"

        return null
    }

    /** 读一条系统属性；读不到（命令不存在、被拦）返回 null，空值也算没读到。 */
    private fun readSystemProperty(name: String): String? = runCatching {

        val process = Runtime.getRuntime().exec(arrayOf("getprop", name))

        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()

        process.waitFor()

        text.ifEmpty { null }

    }.getOrNull()

    /** 最近的输出快照（UIService.upstreamOutput() 调它）。 */
    fun outputSnapshot(): String = synchronized(lineLock) {

        if (lines.isEmpty()) {
            return "（尚无输出）"
        }

        buildString {

            append("== AndroidService 输出 ==\n")

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

                first ?: second ?: "提权自检失败：两条通道都没走通"

            } catch (e: Throwable) {

                "提权自检异常：${e.javaClass.simpleName} ${e.message ?: ""}"
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

            record("Shizuku 自检跳过：未授权")

            return null
        }

        val process = try {
            ShizukuManager.newShellProcess(command)
        } catch (e: Throwable) {
            record("Shizuku 自检异常：${e.javaClass.simpleName}")

            null
        } ?: return null

        // 结论只报 id 输出（里面就是 uid=...），不带"这是从哪条通道来的"
        return "自检：" + readIdentity(process)
    }

    /** 用 su 通道跑一次 `id`，成功返回"自检：<id 输出>"；不可用时返回 null。 */
    private fun probeWithRoot(command: List<String>): String? {

        if (!runCatching { RootManager.isAvailable }.getOrDefault(false)) {

            record("Root 自检跳过：没有 su 或未授权")

            return null
        }

        val process = try {
            RootManager.newRootProcess(command)
        } catch (e: Throwable) {
            record("Root 自检异常：${e.javaClass.simpleName}")

            null
        } ?: return null

        return "自检：" + readIdentity(process)
    }

    /** 读一个短命进程的 id 输出并销毁它（自检专用，命令不会久留）。 */
    private fun readIdentity(process: java.lang.Process): String {
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Throwable) {
            "读输出失败：${e.javaClass.simpleName}"
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
 */
data class ProcessKey(
    val pid: Int,
    val startTicks: Long,
    val uid: Int? = null
)

/**
 * Android 侧补出来的五格（Linux 那侧回的都是 NOT_AVAILABLE）。
 *
 * 每个字段 null = 这一格 Android 也给不出（例如 native daemon 没有应用身份），
 * 调用方必须保留 Linux 的"暂无数据"，绝不填 0 或空串冒充有值。
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
     * true = 支持 AVF；false / null = 都不显示这一行。
     *
     * 用户明确要求：只有"显示"与"不提供"两种状态，界面不存在"AVF 支持：否"。
     */
    val avfSupported: Boolean? = null
)
