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
 * 授权维持（用户明确要求，别再改回去）：
 * 以前每条命令都是 `su -c "..."` 起一个新 su 进程，root 管理器（Magisk / KernelSU / APatch）
 * 若设为"每次请求都提示"，就会变成"启动后一直弹授权框"。现在改成**一条长驻 root shell**：
 * - 第一次用到 root 时起一个 `su`（不跟 `-c`），它会给你一个 root 的 sh，并一直挂着；
 * - 之后所有脚本都往这条 shell 的 stdin 写、从它的 stdout 读，用结束标记切开；
 * - 用户授权一次，本次应用进程存活期间不再弹框（su 进程死了才会重新起一条）。
 *
 * 两套入口的分工：
 * - [execScript]：跑一段 sh 脚本、读完整输出 —— AndroidService 的包表 / dumpsys / 优先级兜底走这里；
 * - [newRootProcess]：需要**长期持有 Process 对象**（Linux ELF 后端）时用，仍是 `su -c` 一次性起进程。
 *
 * 注意：su 探测会弹授权框并一直等用户点，所以**绝对不能**在主线程同步调用；
 * 本对象统一在后台线程探测、主线程回调。
 */
package com.android.taskmanager.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.android.taskmanager.IRootApi
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 全局单例：应用内共享同一个 Root 状态与同一条 root shell，避免重复探测、重复授权。 */
object RootManager : IRootApi {

    private const val TAG = "TMRoot"

    /** su 探测上限：超过这个时间还没结束（用户在授权框上发呆 / 弹框没弹出来）就按无权限处理。 */
    private const val PROBE_TIMEOUT_MS = 6_000L

    /** 起长驻 shell 的等待上限：这里面含"用户点授权框"的时间，给得比普通命令宽。 */
    private const val SESSION_START_TIMEOUT_MS = 12_000L

    /** 会话不可用时，多久之后再试着重建（避免每次调用都重新弹一次框）。 */
    private const val SESSION_RETRY_GAP_MS = 10_000L

    /** 单条脚本的默认超时。 */
    private const val DEFAULT_CMD_TIMEOUT_MS = 3_000L

    /** 读线程遇到 EOF 时投进队列的哨兵：带上不可见字符，正常输出不可能撞上。 */
    private const val EOF_LINE = "\u0000TM_EOF"

    /** 握手 / 结束标记的前缀。 */
    private const val READY_MARKER = "__TM_ROOT_READY__"
    private const val DONE_PREFIX = "__TM_ROOT_DONE_"
    private const val COMMAND_EXIT_PREFIX = "__TM_ROOT_CMD_EXIT__"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一次探测的结论；false 也表示"从没探过"。 */
    @Volatile
    private var available: Boolean = false

    /** 是否已经探测过（区分"没探过"和"探过但没有"）。 */
    @Volatile
    private var probed: Boolean = false

    /** 长驻 root shell；null = 还没起 / 已经断了。所有读写都在 [sessionLock] 里做。 */
    private var session: RootSession? = null

    /** 上一次起会话失败的时刻；在 [SESSION_RETRY_GAP_MS] 内不再重试（免得反复弹框）。 */
    private var lastSessionFailAt: Long = 0L

    private val sessionLock = Any()

    /**
     * 上一次执行失败的原因是不是"长驻 shell 压根没建起来"。
     *
     * 只有这一种情况才退回一次性 `su -c`：会话建好了但某条脚本超时再起 `su` 没有意义，
     * 反而会让 root 管理器再弹一次授权框。
     */
    @Volatile
    private var lastFailureWasSessionStart: Boolean = false

    /** 命令序号：结束标记用它保证唯一，输出里出现同名字符串也不会被误认成结束。 */
    private val commandSeq = AtomicInteger(0)

    /**
     * 初始化 Root 通道。
     *
     * 这里**不做** su 探测：应用一启动就弹 root 授权框非常打扰，
     * 探测推迟到用户真的点"请求权限"那一刻（见 [requestRoot]）。
     *
     * 只重置"探测结论"，**不杀**已有的长驻 root shell：那条进程本身就是用户已经授权过的东西，
     * 重复初始化（设置页改身份、服务重建）时把它关掉，等于让用户白白再点一次授权框。
     * 它真断了（su 自己退出）会话会自己标记为 dead，下次用到时自动重建。
     */
    override fun initialize(context: Context) {

        probed = false
        available = false

        synchronized(sessionLock) {

            if (session?.dead == true) session = null

            lastSessionFailAt = 0L
        }
    }

    /** 设备当前是否具备可用的 Root 权限（只反映缓存结论，不会触发授权弹窗）。 */
    override val isAvailable: Boolean
        get() = probed && available

    /**
     * 申请 / 校验 Root 权限：起一次长驻 root shell（这一步会弹授权框），跑 `id`，输出里有 uid=0 就算拿到。
     *
     * 关键点：**授权框只在这一步弹一次**，拿到的这条 shell 会被一直留着，
     * 后面所有 [execScript] 都复用它——这就是"授予一次、权限维持"的实现。
     *
     * 超时策略：
     * - su 管理器会弹窗让用户确认，用户不点就一直挂着，所以先 waitFor 再读输出；
     * - 超时直接销毁进程（同时会把授权框关联的请求结束掉），按"没拿到"上报。
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

            if (!granted) {

                // 没拿到就把会话清掉，免得留一条半死的 su 进程
                synchronized(sessionLock) {
                    session?.close()
                    session = null
                }
            }

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
     *
     * 先用长驻 shell（授权一次后所有查询复用），起不来再退回一次性 `su -c id`。
     */
    private fun probeSu(): Boolean {

        val viaSession = execLocked("id", PROBE_TIMEOUT_MS, allowStart = true)

        if (viaSession != null) {
            return viaSession.contains("uid=0")
        }

        return probeOneShot()
    }

    /** 一次性 `su -c id`：长驻 shell 这条路径不通时用。 */
    private fun probeOneShot(): Boolean {

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
     * 以 root 身份跑一段 sh 脚本并读完整输出（走长驻 shell，同一时刻只跑一条）。
     *
     * 供 AndroidService 这类"频繁问一句就走"的地方用：这里的调用**不会**每次都弹授权框，
     * 只会在第一次用到 root 时起一次 su（之后再复用）。
     *
     * @param script 交给 root sh 的脚本（可以多行、可以带管道）
     * @param timeoutMs 等这条脚本跑完的上限（超时就杀掉整条会话，下一条命令会重建）
     * @return 脚本的 stdout（结尾带换行）；会话不可用 / 超时 / 通道没授权都返回 null
     */
    fun execScript(script: String, timeoutMs: Long = DEFAULT_CMD_TIMEOUT_MS): String? {

        if (!isAvailable) return null

        val viaSession = execLocked(script, timeoutMs, allowStart = true)

        if (viaSession != null) return viaSession

        // 只有"会话压根没建起来"才退回一次性 su：
        // 会话建起来但这条脚本超时 / 出错，再起一条 `su -c` 只会让管理器又弹一次框，
        // 所以那种情况直接把 null 交给调用方（这一轮按"没数据"处理）。
        return if (lastFailureWasSessionStart) runOneShot(script, timeoutMs) else null
    }

    /** 一次 root 命令的结果：退出码 + 合并后的输出。 */
    data class RootCommandResult(
        val exitCode: Int,
        val output: String
    )

    /**
     * 用**长驻 root shell** 跑一条外部命令，并带回退出码。
     *
     * 存在的意义：用户点的 root 操作不能每条都起一次 `su -c`，否则 Magisk 会把它当成
     * 新进程反复弹授权框。这里第一条命令建一次会话，后续命令全部复用同一条 root sh。
     *
     * 不检查 [isAvailable] 缓存：冷启动后它可能是 false，但设备其实已经授权过 root。
     *
     * @return null = 长驻 root shell 起不来（没有 su / 用户拒绝 / 超时）
     */
    fun execCommandForced(
        command: List<String>,
        timeoutMs: Long
    ): RootCommandResult? {

        if (command.isEmpty()) return null

        // 用 `; echo MARKER$?` 抓住这条命令自己的退出码；外层 execLocked 还会再补一个结束标记
        val script = command.joinToString(" ") { quoteForShell(it) } +
            "; echo " + COMMAND_EXIT_PREFIX + "\$?"

        val raw = execLocked(script, timeoutMs, allowStart = true) ?: return null

        // 会话能执行命令 = root 确实可用，把缓存结论补正，后续数据查询也不用再探测
        probed = true
        available = true

        var exitCode = -1
        val outputLines = ArrayList<String>()

        raw.lineSequence().forEach { line ->
            if (line.startsWith(COMMAND_EXIT_PREFIX)) {
                exitCode = line.removePrefix(COMMAND_EXIT_PREFIX).trim().toIntOrNull() ?: -1
            } else {
                outputLines.add(line)
            }
        }

        return RootCommandResult(
            exitCode = exitCode,
            output = outputLines.joinToString("\n").trim()
        )
    }

    /**
     * 会话执行本体：串行化、清掉上一条残留、用唯一结束标记切分输出。
     *
     * @param allowStart false 时只复用已有会话（自检里用它避免"探测又弹一次框"的意外）
     */
    private fun execLocked(script: String, timeoutMs: Long, allowStart: Boolean): String? {

        synchronized(sessionLock) {

            val live = ensureSessionLocked(if (allowStart) SESSION_START_TIMEOUT_MS else 0L)

            if (live == null) {

                lastFailureWasSessionStart = true

                return null
            }

            lastFailureWasSessionStart = false

            // 上一条命令若被超时截断，队列里可能还剩它的尾巴；这条命令只认自己的输出
            live.drain()

            val marker = DONE_PREFIX + commandSeq.incrementAndGet() + "__"

            if (!live.write(script) || !live.write("echo $marker\$?")) {

                dropSessionLocked(live)

                return null
            }

            val body = StringBuilder()

            val deadline = SystemClock.elapsedRealtime() + timeoutMs

            while (true) {

                val remain = deadline - SystemClock.elapsedRealtime()

                if (remain <= 0L) {

                    Log.i(TAG, "root 脚本超时（${timeoutMs}ms），会话作废")

                    dropSessionLocked(live)

                    return null
                }

                val line = live.poll(remain)

                if (line == null) {

                    Log.i(TAG, "root 脚本读不到输出（${timeoutMs}ms），会话作废")

                    dropSessionLocked(live)

                    return null
                }

                if (line == EOF_LINE) {

                    // su 自己退出了（用户撤权 / 管理器重启）：这条会话作废，下一条命令重建
                    Log.i(TAG, "长驻 root shell 已退出")

                    dropSessionLocked(live)

                    return null
                }

                if (line.startsWith(marker)) break

                body.append(line).append('\n')
            }

            return body.toString()
        }
    }

    /** 取一条活的会话；[startTimeoutMs] <= 0 表示"只复用，不新建"。 */
    private fun ensureSessionLocked(startTimeoutMs: Long): RootSession? {

        session?.let { current ->
            if (!current.dead) return current

            session = null
        }

        if (startTimeoutMs <= 0L) return null

        // 刚失败过就等一会儿再试：连着重起 su 只会连着弹框
        val now = SystemClock.elapsedRealtime()

        if (lastSessionFailAt != 0L && now - lastSessionFailAt < SESSION_RETRY_GAP_MS) return null

        val started = try {

            // 注意：这里只写 "su"，不带 -c —— 要的是一条能反复喂命令的 root sh
            RootSession(ProcessBuilder("su").redirectErrorStream(true).start())

        } catch (e: Throwable) {

            Log.i(TAG, "起长驻 root shell 失败：${e.javaClass.simpleName}")

            null
        }

        if (started == null) {

            lastSessionFailAt = now

            return null
        }

        // 握手：往 root sh 里写一句 echo，读到标记才算 shell 真的在跑
        val ready = started.write("echo $READY_MARKER") && started.await(

            match = { it == READY_MARKER },
            timeoutMs = startTimeoutMs
        )

        if (!ready) {

            Log.i(TAG, "长驻 root shell 握手超时（未授权 / 不支持交互 shell）")

            started.close()

            lastSessionFailAt = SystemClock.elapsedRealtime()

            return null
        }

        lastSessionFailAt = 0L
        session = started

        Log.i(TAG, "长驻 root shell 已建立（本次运行内复用，不再逐条弹授权框）")

        return started
    }

    private fun dropSessionLocked(target: RootSession) {

        target.close()

        if (session === target) {
            session = null
        }
    }

    /**
     * 一次性 `su -c` 执行（会话起不来时的兜底）。
     *
     * 这条路径**会**每次请求一次 su 授权，仅在长驻 shell 不可用时才走。
     */
    private fun runOneShot(script: String, timeoutMs: Long): String? {

        val process = try {

            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()

        } catch (e: Throwable) {

            Log.i(TAG, "su 起进程失败：${e.javaClass.simpleName}")

            return null
        }

        // 读输出必须和"等退出"并行：输出可能超过管道缓冲，先 waitFor 会双方死等
        val collected = StringBuilder()

        val reader = Thread {

            try {

                process.inputStream.bufferedReader().use { input ->

                    while (true) {

                        val line = input.readLine() ?: break

                        synchronized(collected) {
                            collected.append(line).append('\n')
                        }
                    }
                }

            } catch (e: Throwable) {

                // 被 destroy 打断：忽略
            }
        }.apply {

            isDaemon = true
            name = "TMRoot-OneShotReader"
        }

        reader.start()

        return try {

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {

                process.destroy()

                null
            } else {

                // 进程已退出，读线程接下来会读到 EOF 自然结束
                reader.join(300L)

                synchronized(collected) { collected.toString() }
            }

        } catch (e: Throwable) {

            null

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
     * 为什么用 `su -c` 而不是长驻 shell：调用方（Linux ELF 后端）要自己长期持有 stdin/stdout，
     * 一条 sh 里塞两个程序会把管道搅在一起；后端只起一次，多弹一次框可以接受。
     *
     * 参数按 shell 规则拼成单条字符串（每个参数用单引号包起来，内部的单引号转义）。
     */
    override fun newRootProcess(command: List<String>): Process? {

        if (command.isEmpty() || !isAvailable) {
            return null
        }

        return startRootProcess(command)
    }

    private fun startRootProcess(command: List<String>): Process? {

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

    /**
     * 一条长驻 root shell：一个 su 进程 + 一个读线程 + 一个输出队列。
     *
     * 为什么用"读线程 + 阻塞队列"而不是直接 read：读管道会一直阻塞，
     * 上层每条命令都需要超时控制；读线程把行搬进队列后，上层就能带超时地取。
     */
    private class RootSession(val process: Process) {

        private val queue = LinkedBlockingQueue<String>()

        @Volatile
        var dead: Boolean = false
            private set

        private val writer: BufferedWriter? = try {

            BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

        } catch (e: Throwable) {

            null
        }

        init {

            Thread {

                try {

                    BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
                        .use { reader ->

                            while (true) {

                                val line = reader.readLine() ?: break

                                queue.offer(line)
                            }
                        }

                } catch (e: Throwable) {

                    // 管道断了：按会话死亡处理

                } finally {

                    dead = true

                    queue.offer(EOF_LINE)
                }
            }.apply {

                isDaemon = true
                name = "TMRoot-Reader"
            }.start()
        }

        fun write(line: String): Boolean {

            val out = writer ?: return false

            return try {

                out.write(line)
                out.newLine()
                out.flush()

                true

            } catch (e: Throwable) {

                false
            }
        }

        /** 取一行；[timeoutMs] 内没有新行返回 null，读到 EOF 返回 [EOF_LINE]。 */
        fun poll(timeoutMs: Long): String? {

            return try {

                if (timeoutMs <= 0L) queue.poll() else queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

            } catch (e: InterruptedException) {

                null
            }
        }

        /** 丢掉堆积的旧输出（上一条命令被超时截断时留下的尾巴）。 */
        fun drain() {

            queue.clear()
        }

        /**
         * 等到"某一行满足 [match]"为止（握手用）。
         *
         * 前面可能先有 su 自己的横幅（例如管理器的欢迎语），所以这里按行匹配而不是只读第一行。
         *
         * @return true = 见到了目标行；false = 超时 / 会话死亡
         */
        fun await(match: (String) -> Boolean, timeoutMs: Long): Boolean {

            val deadline = SystemClock.elapsedRealtime() + timeoutMs

            while (true) {

                val remain = deadline - SystemClock.elapsedRealtime()

                if (remain <= 0L) return false

                val line = poll(remain) ?: return false

                if (line == EOF_LINE) return false

                if (match(line)) return true
            }
        }

        fun close() {

            dead = true

            try {
                writer?.close()
            } catch (e: Throwable) {
                // 已断：忽略
            }

            try {
                process.destroy()
            } catch (e: Throwable) {
                // 已退出：忽略
            }
        }
    }
}
