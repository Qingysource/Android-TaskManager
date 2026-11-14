/**
 * ============================================================
 * Root 权限通道
 * ============================================================
 *
 * 实现接口：[IRootApi]（声明在 Interfaces.kt）。
 *
 * 定位：与 ShizukuManager 并列的第二条提权通道。
 * Shizuku 以 ADB / Shell（uid 2000）身份执行，RootManager 直接以 root（uid 0）执行，
 * 用于需要完整权限的场景（读取其他应用的 /proc、内核内存转储等）。
 *
 * 两条通道是"二选一可用"的关系，UI 层应组合判断：
 * Root 可用 -> 走 RootManager；否则 Shizuku 已授权 -> 走 ShizukuManager；都不可用则禁用相关页面。
 *
 * 已实现能力：
 * - [requestRoot]：后台探测 `su -c id`（带超时），结果缓存并通过回调上报；
 * - [isAvailable]：返回上面探测的结论（没探过 = false，不做静默探测）。
 *
 * 预留实现接口（后续补齐）：
 * - fun exec(command: String): ExecResult  以 root 身份执行命令，返回 stdout / stderr / exitCode
 * - fun readFile(path: String): String?    以 root 身份读取受保护文件
 * - val status: StateFlow<RootStatus>      向 UI 暴露 Root 状态（与 ShizukuManager 保持一致的写法）
 *
 * 注意：su 探测会弹授权框并一直等用户点，所以**绝对不能**在主线程同步调用；
 * 本对象统一在后台线程探测、主线程回调。
 */
package com.android.taskmanager.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.taskmanager.IRootApi
import java.util.concurrent.TimeUnit

/** 全局单例：应用内共享同一个 Root 状态，避免重复探测。 */
object RootManager : IRootApi {

    private const val TAG = "TMRoot"

    /** su 探测上限：超过这个时间还没结束（用户在授权框上发呆 / 弹框没弹出来）就按无权限处理。 */
    private const val PROBE_TIMEOUT_MS = 4_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一次探测的结论；false 也表示"从没探过"。 */
    @Volatile
    private var available: Boolean = false

    /** 是否已经探测过（区分"没探过"和"探过但没有"）。 */
    @Volatile
    private var probed: Boolean = false

    /**
     * 初始化 Root 通道。
     *
     * 这里**不做** su 探测：应用一启动就弹 root 授权框非常打扰，
     * 探测推迟到用户真的点"请求权限"那一刻（见 [requestRoot]）。
     * 初始化只重置缓存，保证进程重启后不会沿用上一次的结论。
     */
    override fun initialize(context: Context) {

        probed = false
        available = false
    }

    /** 设备当前是否具备可用的 Root 权限（只反映缓存结论，不会触发授权弹窗）。 */
    override val isAvailable: Boolean
        get() = probed && available

    /**
     * 申请 / 校验 Root 权限：后台跑一次 `su -c id`，输出里有 uid=0 就算拿到。
     *
     * 超时策略：
     * - su 管理器会弹窗让用户确认，用户不点就一直挂着，所以先 waitFor 再读输出；
     * - 超时直接 destroy 进程（同时会把授权框关联的请求结束掉），按"没拿到"上报。
     */
    override fun requestRoot(onResult: (Boolean) -> Unit) {

        Thread {

            val granted = try {
                probeSu()
            } catch (e: Throwable) {

                Log.i(TAG, "su 探测异常：${e.javaClass.simpleName}")

                false
            }

            probed = true
            available = granted

            Log.i(TAG, "root 探测结果 granted=$granted")

            mainHandler.post {
                onResult(granted)
            }
        }.apply {

            isDaemon = true
            name = "TMRoot-Probe"
        }.start()
    }

    /**
     * 真正去跑 su：返回 true = 确实以 uid 0 执行成功。
     */
    private fun probeSu(): Boolean {

        val process = try {
            ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        } catch (e: Throwable) {

            // 设备上没有 su（绝大多数普通机器就是这条）
            return false
        }

        return try {

            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {

                Log.i(TAG, "su 探测超时（用户未授权 / 授权框未弹出）")

                process.destroy()

                return false
            }

            // 进程已经退出，这里读输出不会再阻塞
            val output = process.inputStream.bufferedReader().use { it.readText() }

            output.contains("uid=0")

        } catch (e: Throwable) {

            false

        } finally {

            try {
                process.destroy()
            } catch (e: Throwable) {
                // 已经退出：忽略
            }
        }
    }

    /**
     * 以 root 身份启动一个外部进程：`su -c "<命令>"`，stdio 直接转发给调用方。
     *
     * 为什么用 `su -c` 而不是别的：Magisk / KernelSU 的 su 都是"跑一条命令"的语义，
     * 它自己会把 stdin/stdout/stderr 接到我们给的管道上，所以 LinuxService 那套
     * Reader 线程 / 往管道写 EOF / destroy 的代码可以原样复用。
     *
     * 注意：已经在 [requestRoot] 里拿过授权的话不会再弹窗；没拿过就直接调用会弹一次。
     * 参数按 shell 规则拼成单条字符串（每个参数用单引号包起来，内部的单引号转义）。
     */
    override fun newRootProcess(command: List<String>): Process? {

        if (command.isEmpty() || !isAvailable) {
            return null
        }

        return try {

            // exec：让 sh 用目标程序替换自己，su 的子进程就是目标进程本身，
            // 后面 destroy() 时信号能直达，不会在中间留一层 sh 撑着管道不关。
            val shellCommand = "exec " + command.joinToString(" ") { quoteForShell(it) }

            val process = ProcessBuilder("su", "-c", shellCommand)
                .redirectErrorStream(true)
                .start()

            Log.i(TAG, "以 root 身份启动进程：$shellCommand")

            process

        } catch (e: Throwable) {

            Log.i(TAG, "root 起进程失败：${e.javaClass.simpleName} ${e.message ?: ""}")

            null
        }
    }

    /** 单个参数的单引号转义：' -> '\'' ，这样路径里的特殊字符不会被 shell 展开。 */
    private fun quoteForShell(argument: String): String {

        return "'" + argument.replace("'", "'\\''") + "'"
    }
}
