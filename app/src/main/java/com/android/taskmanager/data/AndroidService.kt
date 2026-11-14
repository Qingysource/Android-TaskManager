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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
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

        // 不粘连启动：进程被杀后不自动重建，避免后台服务被系统反复拉起
        return START_NOT_STICKY
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
