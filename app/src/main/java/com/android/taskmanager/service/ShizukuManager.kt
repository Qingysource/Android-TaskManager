/**
 * ============================================================
 * Shizuku 权限通道
 * ============================================================
 *
 * 实现接口：[IShizukuApi]（声明在 Interfaces.kt）。
 *
 * 本文件是项目"提权"能力的入口之一（另一条是 RootManager），
 * 页面不应直接调用 Shizuku API，一律通过本对象。
 *
 * 已实现能力：
 * - 初始化并注册 Binder / 权限监听（支持 Sticky 监听，启动即可拿到当前状态）；
 * - 查询运行状态、运行身份（Root / Shell）、API 版本与自身授权状态；
 * - 申请授权，真实结果通过 callback 异步回传；
 * - 通过 StateFlow 向 UI 暴露实时状态，UI 可直接 collect。
 *
 * 预留实现接口（后续补齐）：
 * - fun exec(command: String): ExecResult   以 Shizuku 身份执行 shell 命令，
 *                                          供进程页 / 服务页 / 用户页取数据；
 * - fun openRemoteService(...)             借 Shizuku Binder 调用系统隐藏 API；
 * - val isUsable: Boolean                  "Root 或 Shell 任一可用"的统一判定，
 *                                          配合 RootManager 决定页面是否可打开。
 *
 * 注意：release() 是单例级别的释放，不要放进 Activity.onDestroy()。
 */
package com.android.taskmanager.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.android.taskmanager.IShizukuApi
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku 服务状态。
 */
enum class ShizukuState {
    NOT_INITIALIZED,
    NOT_RUNNING,
    CONNECTED,
    PERMISSION_GRANTED,
    UNSUPPORTED,
    ERROR
}

/**
 * Shizuku 运行身份。
 */
enum class ShizukuMode {
    UNKNOWN,
    SHELL,
    ROOT
}

/**
 * Shizuku 当前状态信息。
 */
data class ShizukuStatus(
    val state: ShizukuState = ShizukuState.NOT_INITIALIZED,
    val mode: ShizukuMode = ShizukuMode.UNKNOWN,
    val serverUid: Int = -1,
    val apiVersion: Int = -1,
    val hasPermission: Boolean = false,
    /**
     * Shizuku 管理器应用（moe.shizuku.privileged.api）是否已安装。
     *
     * 和 [isRunning] 不是一回事：装了不代表服务在跑（服务要用户手动启动），
     * 界面据此决定提示"去安装"还是"去启动"。
     */
    val installed: Boolean = false
) {
    /**
     * Shizuku 服务是否正在运行。
     *
     * 注意：UNSUPPORTED 也代表 Binder 已连接，
     * 只是当前 Shizuku 服务版本不受 API 支持。
     */
    val isRunning: Boolean
        get() = state == ShizukuState.CONNECTED ||
                state == ShizukuState.PERMISSION_GRANTED ||
                state == ShizukuState.UNSUPPORTED

    /**
     * Binder 是否已连接。
     */
    val isConnected: Boolean
        get() = isRunning

    /**
     * API 是否受支持。
     */
    val isApiSupported: Boolean
        get() = state != ShizukuState.UNSUPPORTED &&
                state != ShizukuState.NOT_RUNNING &&
                state != ShizukuState.NOT_INITIALIZED &&
                state != ShizukuState.ERROR

    /**
     * 应用是否获得 Shizuku 授权。
     */
    val isAuthorized: Boolean
        get() = state == ShizukuState.PERMISSION_GRANTED &&
                hasPermission

    /**
     * 当前是否为 Root 身份。
     */
    val isRoot: Boolean
        get() = mode == ShizukuMode.ROOT

    /**
     * 当前是否为 Shell 身份。
     */
    val isShell: Boolean
        get() = mode == ShizukuMode.SHELL

    /**
     * 已安装但服务没在跑 —— 这时该做的是"打开 Shizuku 让用户点启动"，而不是报权限失败。
     */
    val needsStartService: Boolean
        get() = installed && !isRunning
}

/**
 * Shizuku 权限操作结果。
 */
enum class ShizukuPermissionResult {
    GRANTED,
    DENIED,
    REQUESTED,
    ALREADY_GRANTED,
    NOT_RUNNING,
    UNSUPPORTED,
    ERROR
}

/**
 * Shizuku 管理器。
 *
 * 负责：
 * 1. 初始化 Shizuku API。
 * 2. 监听 Binder 连接和断开。
 * 3. 检查 Shizuku 服务状态。
 * 4. 检查当前应用的授权状态。
 * 5. 请求 Shizuku 授权。
 * 6. 获取 Shizuku 服务的 UID 和 API 版本。
 * 7. 向 UI 提供 StateFlow 状态。
 */
object ShizukuManager : IShizukuApi {

    /** 日志标签：adb logcat -s TMShizuku 只看这条通道。 */
    private const val TAG = "TMShizuku"

    private const val REQUEST_CODE = 1001

    /** Shizuku 管理器应用的包名：用来判断"没装"还是"装了但没启动"。 */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    // ------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------

    private val _status = MutableStateFlow(ShizukuStatus())

    /**
     * UI 层订阅此状态即可获得最新 Shizuku 信息。
     */
    override val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var initialized = false

    /** 应用上下文：refreshStatus 里要查"Shizuku 装没装"，需要 PackageManager。 */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var requestPending = false

    /**
     * 当前权限请求的回调。
     *
     * Shizuku 的权限结果通过异步 Listener 返回。
     */
    @Volatile
    private var permissionCallback:
            ((ShizukuPermissionResult) -> Unit)? = null

    private val lock = Any()

    // ------------------------------------------------------------
    // Binder 监听器
    // ------------------------------------------------------------

    /**
     * Shizuku Binder 已连接。
     *
     * 使用 Sticky Listener：
     * 即使注册时 Binder 已经连接，也会立即通知。
     */
    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            refreshStatus()
        }

    /**
     * Shizuku Binder 已断开。
     */
    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            synchronized(lock) {
                requestPending = false
                permissionCallback = null
            }

            _status.value = ShizukuStatus(
                state = ShizukuState.NOT_RUNNING,
                // 服务断了不代表应用被卸载：这里也要带 installed，否则界面会误报"未安装"
                installed = isManagerInstalled()
            )
        }

    // ------------------------------------------------------------
    // 权限结果监听器
    // ------------------------------------------------------------

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener {
                requestCode,
                grantResult ->

            if (requestCode != REQUEST_CODE) {
                return@OnRequestPermissionResultListener
            }

            val result = if (
                grantResult == PackageManager.PERMISSION_GRANTED
            ) {
                ShizukuPermissionResult.GRANTED
            } else {
                ShizukuPermissionResult.DENIED
            }

            val callback: ((ShizukuPermissionResult) -> Unit)?

            synchronized(lock) {
                requestPending = false
                callback = permissionCallback
                permissionCallback = null
            }

            // 更新最新状态。
            refreshStatus()

            // 通知请求者。
            callback?.invoke(result)
        }

    // ------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------

    /**
     * 初始化 Shizuku 管理器。
     *
     * 应在 Application 或 MainActivity 启动阶段调用。
     */
    override fun initialize(context: Context) {

        synchronized(lock) {

            if (initialized) {
                return
            }

            initialized = true

            // 记住应用上下文：refreshStatus 要查"Shizuku 装没装"，launchManagerApp 要用它起 Intent
            appContext = context.applicationContext

            // 注册 Binder 连接监听器。
            Shizuku.addBinderReceivedListenerSticky(
                binderReceivedListener
            )

            // 注册 Binder 断开监听器。
            Shizuku.addBinderDeadListener(
                binderDeadListener
            )

            // 注册权限结果监听器。
            Shizuku.addRequestPermissionResultListener(
                permissionResultListener
            )
        }

        // 主动检查一次当前状态。
        refreshStatus()
    }

    // ------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------

    /**
     * Shizuku 管理器应用是否已安装。
     *
     * 只查包名，不查服务状态 —— 用它在"未运行"时区分两种提示：
     * 没装 -> 让用户去装；装了没启动 -> 让用户打开它点"启动服务"。
     */
    private fun isManagerInstalled(): Boolean {

        val context = appContext ?: return false

        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把 Shizuku 管理器应用拉到前台。
     *
     * 我们没有办法替用户启动 shizuku_server（那需要 adb / root / 无线调试），
     * 所以这里只做"打开 App"，让用户自己点启动，回来再请求权限。
     */
    override fun launchManagerApp(context: Context): Boolean {

        val intent = try {
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        } catch (e: Exception) {
            null
        } ?: return false

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)

            true
        } catch (e: Exception) {
            Log.i(TAG, "打开 Shizuku 失败：${e.javaClass.simpleName}")

            false
        }
    }

    /**
     * 获取并更新当前 Shizuku 状态。
     *
     * 返回更新后的状态。
     */
    override fun refreshStatus(): ShizukuStatus {

        // 装没装与 Binder 在不在无关：查一次包名，后面每种结果都带上它
        val installed = isManagerInstalled()

        if (!initialized) {
            val result = ShizukuStatus(
                state = ShizukuState.NOT_INITIALIZED,
                installed = installed
            )

            _status.value = result
            return result
        }

        // 检查 Binder 是否存活。
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

        if (!binderAlive) {

            val result = ShizukuStatus(
                state = ShizukuState.NOT_RUNNING,
                installed = installed
            )

            _status.value = result
            return result
        }

        // 检查 Shizuku API 版本兼容性。
        val apiSupported = try {
            !Shizuku.isPreV11()
        } catch (e: Exception) {
            false
        }

        if (!apiSupported) {

            val result = ShizukuStatus(
                state = ShizukuState.UNSUPPORTED,
                installed = installed
            )

            _status.value = result
            return result
        }

        // 获取服务 UID。
        val serverUid = try {
            Shizuku.getUid()
        } catch (e: Exception) {
            -1
        }

        // 获取 Shizuku 服务 API 版本。
        val apiVersion = try {
            Shizuku.getVersion()
        } catch (e: Exception) {
            -1
        }

        // 获取当前应用授权状态。
        val hasPermission = try {
            Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

        // 根据 Shizuku 服务 UID 判断运行身份。
        val mode = when (serverUid) {
            0 -> ShizukuMode.ROOT
            2000 -> ShizukuMode.SHELL
            else -> ShizukuMode.UNKNOWN
        }

        val state = if (hasPermission) {
            ShizukuState.PERMISSION_GRANTED
        } else {
            ShizukuState.CONNECTED
        }

        val result = ShizukuStatus(
            state = state,
            mode = mode,
            serverUid = serverUid,
            apiVersion = apiVersion,
            hasPermission = hasPermission,
            installed = installed
        )

        _status.value = result

        return result
    }

    // ------------------------------------------------------------
    // 权限检查
    // ------------------------------------------------------------

    /**
     * 检查当前应用的 Shizuku 授权状态。
     */
    override fun checkPermission(): ShizukuPermissionResult {

        val current = refreshStatus()

        return when (current.state) {

            ShizukuState.NOT_INITIALIZED,
            ShizukuState.NOT_RUNNING -> {
                ShizukuPermissionResult.NOT_RUNNING
            }

            ShizukuState.UNSUPPORTED -> {
                ShizukuPermissionResult.UNSUPPORTED
            }

            ShizukuState.ERROR -> {
                ShizukuPermissionResult.ERROR
            }

            ShizukuState.PERMISSION_GRANTED -> {
                ShizukuPermissionResult.ALREADY_GRANTED
            }

            ShizukuState.CONNECTED -> {
                ShizukuPermissionResult.DENIED
            }
        }
    }

    // ------------------------------------------------------------
    // 权限申请
    // ------------------------------------------------------------

    /**
     * 请求 Shizuku 授权。
     *
     * REQUESTED 仅代表请求已经提交，
     * 真正的授权结果通过 callback 异步返回。
     */
    override fun requestPermission(
        callback: ((ShizukuPermissionResult) -> Unit)?
    ): ShizukuPermissionResult {

        val current = refreshStatus()

        // Shizuku 服务不可用。
        if (!current.isRunning) {
            return ShizukuPermissionResult.NOT_RUNNING
        }

        // 当前 Shizuku API 不受支持。
        if (!current.isApiSupported) {
            return ShizukuPermissionResult.UNSUPPORTED
        }

        // 已经获得授权。
        if (current.hasPermission) {
            callback?.invoke(
                ShizukuPermissionResult.ALREADY_GRANTED
            )

            return ShizukuPermissionResult.ALREADY_GRANTED
        }

        // 防止重复请求。
        synchronized(lock) {

            if (requestPending) {
                return ShizukuPermissionResult.REQUESTED
            }

            requestPending = true
            permissionCallback = callback
        }

        return try {

            // 提交权限请求。
            Shizuku.requestPermission(REQUEST_CODE)

            // 注意：这里并不代表已经授权。
            ShizukuPermissionResult.REQUESTED

        } catch (e: Exception) {

            val failedCallback: ((ShizukuPermissionResult) -> Unit)?

            synchronized(lock) {
                requestPending = false
                failedCallback = permissionCallback
                permissionCallback = null
            }

            failedCallback?.invoke(
                ShizukuPermissionResult.ERROR
            )

            refreshStatus()

            ShizukuPermissionResult.ERROR
        }
    }

    // ------------------------------------------------------------
    // 权限说明
    // ------------------------------------------------------------

    /**
     * 是否建议向用户展示权限申请说明。
     *
     * 例如：用户之前拒绝了权限请求。
     */
    override fun shouldShowPermissionRationale(): Boolean {

        val current = refreshStatus()

        if (!current.isRunning || !current.isApiSupported) {
            return false
        }

        return try {
            Shizuku.shouldShowRequestPermissionRationale()
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------
    // 服务信息
    // ------------------------------------------------------------

    /**
     * 获取 Shizuku 服务 UID。
     *
     * 0    = Root
     * 2000 = Shell
     * -1   = 无法获取
     */
    override fun getServerUid(): Int {

        val current = refreshStatus()

        if (!current.isRunning || !current.isApiSupported) {
            return -1
        }

        return current.serverUid
    }

    /**
     * 当前 Shizuku 服务是否以 Root 身份运行。
     */
    override fun isRoot(): Boolean {
        return refreshStatus().isRoot
    }

    /**
     * 当前 Shizuku 服务是否以 Shell 身份运行。
     */
    override fun isShell(): Boolean {
        return refreshStatus().isShell
    }

    // ------------------------------------------------------------
    // 以 Shizuku 身份起进程（see IShizukuApi.newShellProcess）
    // ------------------------------------------------------------

    /**
     * 以 Shizuku 的身份启动一个外部进程，返回一个"看起来像普通 Process"的句柄。
     *
     * Shizuku 13.1.5 的公开类上没有起进程的入口（javap -p 能看到 newProcess 是 private），
     * 所以这里按"从最贴近官方实现到最兜底"的顺序试两条路：
     *
     * ① 反射调用 Shizuku 自己的 private newProcess：
     *    它返回官方维护的 ShizukuRemoteProcess（本身就是 Process 的子类，
     *    stdin/stdout/stderr/waitFor/destroy 全都实现了），行为与服务端完全一致。
     *    这是 Android-TaskManager 等实际项目验证过的路径，代价是依赖方法名不改变。
     * ② 官方 aidl 构件的 IShizukuService.newProcess：直接和服务层 transact 拿 IRemoteProcess，
     *    再自己包一层 [ShizukuProcess]。它不依赖私有方法名，但依赖 AIDL 的 transaction 编号，
     *    跨大版本可能对不上，所以只当兜底。
     *
     * 前置条件：Shizuku 在跑、API ≥ 11、本应用已授权；否则直接 null（调用方降级）。
     *
     * 为什么不用 UserService：它要把我们的 Service 组件交给 Shizuku 在它自己的进程里拉起，
     * 需要额外的 AIDL + 组件声明，而"起一个子进程"这件事上面两条路已经能做；
     * 等哪天需要"在 shell 进程里直接调隐藏 API"时再上 UserService 更合适。
     */
    override fun newShellProcess(command: List<String>): Process? {

        if (command.isEmpty()) {
            return null
        }

        val current = refreshStatus()

        if (!current.isRunning || !current.isApiSupported || !current.hasPermission) {
            return null
        }

        // ① 反射：官方 ShizukuRemoteProcess（与参考工程同一条路径）
        reflectiveNewProcess(command)?.let { return it }

        // ② aidl 兜底：自己适配 IRemoteProcess
        return aidlNewProcess(command)
    }

    /**
     * 反射调用 `Shizuku.newProcess(String[], String[], String)`。
     *
     * 为什么值得先试这条：返回的是官方 ShizukuRemoteProcess，行为与服务端一致，
     * 而且参考工程（Android-TaskManager）在真机上就是靠它跑的。
     *
     * 为什么要再包一层 [GuardedProcess]：官方 ShizukuRemoteProcess 在进程还活着时
     * `exitValue()` 抛的是 IllegalArgumentException("process hasn't exited")，
     * 而 java.lang.Process.isAlive() 只认 IllegalThreadStateException —— 于是
     * "问一句还活着吗"就把整个应用崩掉（真机 FATAL EXCEPTION 已实证）。
     * 这里统一翻译成 Process 的约定，调用方（LinuxService / AndroidService）就不用各自防雷。
     *
     * release 构建若开启 R8，需要 keep 住 rikka.shizuku.Shizuku 的这个方法（见项目根 proguard 说明）。
     */
    private fun reflectiveNewProcess(command: List<String>): Process? {

        return try {

            val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            method.isAccessible = true

            (method.invoke(null, command.toTypedArray(), null, null) as? Process)
                ?.let { GuardedProcess(it) }

        } catch (e: Throwable) {

            Log.i(TAG, "反射 newProcess 不可用：${e.javaClass.simpleName} ${e.message ?: ""}")

            null
        }
    }

    /** aidl 兜底：从服务 binder 直接取 IRemoteProcess，再包成 [ShizukuProcess]。 */
    private fun aidlNewProcess(command: List<String>): Process? {

        val binder = try {
            Shizuku.getBinder()
        } catch (e: Throwable) {
            null
        } ?: return null

        val service = try {
            IShizukuService.Stub.asInterface(binder)
        } catch (e: Throwable) {
            null
        } ?: return null

        return try {

            val remote = service.newProcess(
                command.toTypedArray(),
                null,   // env：null = 继承服务端环境变量
                null    // dir：null = 服务端当前工作目录
            ) ?: return null

            ShizukuProcess(remote)

        } catch (e: Throwable) {

            // SecurityException（没授权）、RemoteException（服务端崩了 / 版本对不上）
            // 全部按"这条通路这次不可用"处理，调用方会降级。
            Log.i(TAG, "aidl newProcess 失败：${e.javaClass.simpleName}")

            null
        }
    }

    // ------------------------------------------------------------
    // 释放资源
    // ------------------------------------------------------------

    /**
     * 注销 Shizuku 监听器。
     *
     * 这是整个单例管理器的释放操作，
     * 不应该在普通 Activity.onDestroy() 中调用。
     */
    override fun release() {

        synchronized(lock) {

            if (!initialized) {
                return
            }

            initialized = false

            requestPending = false
            permissionCallback = null

            Shizuku.removeBinderReceivedListener(
                binderReceivedListener
            )

            Shizuku.removeBinderDeadListener(
                binderDeadListener
            )

            Shizuku.removeRequestPermissionResultListener(
                permissionResultListener
            )
        }

        _status.value = ShizukuStatus(
            state = ShizukuState.NOT_INITIALIZED
        )
    }
}

/** 轮询"远端进程还活着吗"的间隔：50ms 兼顾响应速度与 IPC 压力。 */
private const val ALIVE_POLL_INTERVAL_MS = 50L

/**
 * 把任意 [Process] 实现翻译成"守 Process 约定"的包装器。
 *
 * 起因是真机崩溃：Shizuku 官方的 ShizukuRemoteProcess 在进程还活着时
 * `exitValue()` 抛 IllegalArgumentException("process hasn't exited")，
 * 而 java.lang.Process.isAlive() 只把 IllegalThreadStateException 当作"还活着"，
 * 其余异常原样上抛 —— 落到 Compose 的点击回调里就是 FATAL EXCEPTION: main。
 *
 * 这里的约定：
 * - [isAlive]：永远不抛；"退出码拿不到"一律当"已退出"，界面不会因此崩；
 * - [exitValue]：还活着就抛 IllegalThreadStateException（这才是 Process 的要求）；
 * - [waitFor] / [destroy]：任何异常都吞掉，语义退化为"尽力而为"。
 */
private class GuardedProcess(
    private val delegate: Process
) : Process() {

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getErrorStream(): InputStream = delegate.errorStream

    override fun waitFor(): Int {

        return try {
            delegate.waitFor()
        } catch (e: Throwable) {
            -1
        }
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {

        return try {
            delegate.waitFor(timeout, unit)
        } catch (e: Throwable) {

            // 委托实现不认这个重载：退化成轮询 isAlive（本包装器的 isAlive 是安全的）
            val deadline = System.currentTimeMillis() + unit.toMillis(timeout)

            while (System.currentTimeMillis() < deadline) {

                if (!isAlive()) {
                    return true
                }

                try {
                    Thread.sleep(ALIVE_POLL_INTERVAL_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()

                    return !isAlive()
                }
            }

            !isAlive()
        }
    }

    override fun exitValue(): Int {

        return try {
            delegate.exitValue()
        } catch (e: Throwable) {
            // Process 的约定：进程还在跑（或退出码不可得）抛这一个
            throw IllegalThreadStateException(
                "进程仍在运行或退出码不可得：${e.javaClass.simpleName}"
            )
        }
    }

    override fun isAlive(): Boolean {

        return try {
            // 能拿到退出码 = 已经退出
            delegate.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            // 标准约定：还活着
            true
        } catch (e: IllegalArgumentException) {
            // ShizukuRemoteProcess 的私有约定：message 里写"还没退出"就是在跑
            e.message?.contains("hasn't exited") == true
        } catch (e: Throwable) {
            // 远端不可达 / IPC 断了：按"已退出"处理，别让调用方卡在"永远运行中"
            false
        }
    }

    override fun destroy() {

        try {
            delegate.destroy()
        } catch (e: Throwable) {
            // 目标已消失：忽略
        }
    }

    override fun destroyForcibly(): Process {

        try {
            delegate.destroyForcibly()
        } catch (e: Throwable) {

            try {
                delegate.destroy()
            } catch (ignored: Throwable) {
                // 已经没了
            }
        }

        return this
    }
}

/** 把 Shizuku 服务里的 IRemoteProcess 包成标准的 [Process]。
 *
 * 为什么需要这一层：LinuxService 的现有逻辑（Reader 线程读 stdout、往 stdin 写一行、
 * stopBackend 里 waitFor/destroy）全部按 Process 写的，有了这个适配器，
 * "应用身份起的后端"和"shell 身份起的后端"就能走同一套生命周期代码，不必写两套分支。
 *
 * 三条流的实现要点：
 * - 远端返回的是 [ParcelFileDescriptor]，必须用 AutoClose*Stream 包一层，
 *   否则关闭流时不会真正关掉管道（fd 泄漏，反复重启后端会耗尽 fd）；
 * - waitFor/exitValue/destroy/alive 全部转发给远端，进程其实不在我们自己的进程里；
 * - exitValue() 在"还活着"时必须抛 IllegalThreadStateException，
 *   这是 Process 的约定（默认的 waitFor(timeout) 就是靠这个异常来轮询的）。
 */
private class ShizukuProcess(
    private val remote: IRemoteProcess
) : Process() {

    /** 等远端退出时的轮询间隔：够快（一次 stop 最多几百毫秒），也不至于把 IPC 打满。 */
    private val POLL_INTERVAL_MS = 50L

    /** stdin：本地写入 -> 远端进程读到。按需创建，避免提前占 fd。 */
    private val stdinDelegate = lazy {
        wrapOutput(remote.getOutputStream())
            ?: throw IOException("远端进程没有提供 stdin")
    }

    private val inputToRemote: OutputStream by stdinDelegate

    /** stdout：远端进程输出 -> 本地读到。 */
    private val stdoutDelegate = lazy {
        wrapInput(remote.getInputStream())
            ?: throw IOException("远端进程没有提供 stdout")
    }

    private val outputFromRemote: InputStream by stdoutDelegate

    /** stderr：LinuxService 用 redirectErrorStream 的思路，错误流单独保留但通常不读。 */
    private val stderrDelegate = lazy {
        wrapInput(remote.getErrorStream()) ?: EmptyStream
    }

    private val errorFromRemote: InputStream by stderrDelegate

    private fun wrapInput(fd: ParcelFileDescriptor?): InputStream? {

        return fd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    }

    private fun wrapOutput(fd: ParcelFileDescriptor?): OutputStream? {

        return fd?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
    }

    override fun getOutputStream(): OutputStream = inputToRemote

    override fun getInputStream(): InputStream = outputFromRemote

    override fun getErrorStream(): InputStream = errorFromRemote

    override fun waitFor(): Int {

        return try {
            remote.waitFor()
        } catch (e: Exception) {

            // 远端不可达（服务退出 / 版本对不上）：按"已退出但拿不到退出码"处理
            -1
        }
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {

        // 不用远端的 waitForTimeout：它的 unit 参数是服务端自己解析的字符串，
        // 传错枚举名会被拒（不同版本接受的写法也不一样）。这里用"轮询 isAlive"，
        // 语义完全对得上 Process 的约定，也不依赖服务端实现细节。
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)

        while (System.currentTimeMillis() < deadline) {

            if (!isAlive()) {
                return true
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()

                return !isAlive()
            }
        }

        return !isAlive()
    }

    override fun exitValue(): Int {

        return try {
            remote.exitValue()
        } catch (e: Exception) {
            // Process 的约定：进程还在跑就要抛这个异常
            throw IllegalThreadStateException("远端进程仍在运行")
        }
    }

    /**
     * 销毁远端进程，并把本地这三条流关掉。
     *
     * 为什么本地也要关：AutoClose*Stream 持有的是一个本地 fd，
     * 远端退出后本地这半截管道不会自己消失；调试页反复"启动 / 停止"时，
     * 不关就会一行一行累积 fd，最后 newProcess 直接失败。
     */
    override fun destroy() {

        try {
            remote.destroy()
        } catch (e: Exception) {
            // 远端已经不可达（Shizuku 服务退出）：目标进程也随之消失，忽略
        }

        closeQuietly(stdoutDelegate)
        closeQuietly(stdinDelegate)
        closeQuietly(stderrDelegate)
    }

    /** 只在流真的创建过时才关，避免为了关闭反而去建立一条管道。 */
    private fun closeQuietly(delegate: Lazy<*>) {

        if (!delegate.isInitialized()) {
            return
        }

        try {
            (delegate.value as? Closeable)?.close()
        } catch (e: Exception) {
            // 管道可能已被远端关闭：忽略
        }
    }

    override fun isAlive(): Boolean {

        return try {
            remote.alive()
        } catch (e: Exception) {
            false
        }
    }
}

/** stderr 缺失时的空流占位，避免调用方到处判空。 */
private object EmptyStream : InputStream() {

    override fun read(): Int = -1
}