/**
 * ============================================================
 * Linux 通道：协议契约 + 数据服务（合并在同一文件）
 * ============================================================
 *
 * 为什么把原先独立的协议文件并进来：
 * 这两半本来就是一件事的两面——协议描述"一行文本长什么样"，本服务负责"把这一行
 * 写进管道、再把读回来的一行还原成结构"。拆成两个文件时，改协议要跳到另一个文件，
 * 改收发又要回来对照；字段表 / 转义规则 / 状态码这三处只要有一边漏同步，
 * 表现就是"表格少一行""某个值变成 0""状态码对不上"，而这类错误在真机上极难定位。
 * 现在契约与收发同处一个文件：改协议必然看见收发，改收发必然看见协议。
 * 唯一的"另一半"是 cpp/Backend/main.c，那边的字段表与掩码位序必须与这里逐位一致。
 *
 * 文件结构（两段，别再拆开）：
 *   ① 协议契约：字段位掩码 / 状态码 / 字段状态 / 请求类型 / 控制码 /
 *      进程记录 / 应答包 / 编解码（LinuxProtocol 对象，纯函数，方便单测）
 *   ② LinuxService：ELF 后端的持有者——起停进程、读写管道、pending 配对、
 *      READY 门槛、输出缓存
 *
 * 对外可见的东西没变：类型名、object 名、方法名与语义都与合并前一致，
 * 只是包名随本服务落在 com.android.taskmanager.data。
 */

package com.android.taskmanager.data

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

import com.android.taskmanager.DetailColumnId
import com.android.taskmanager.ILinuxServiceApi
import com.android.taskmanager.UpstreamIdentityMode
import com.android.taskmanager.service.RootManager
import com.android.taskmanager.service.ShizukuManager

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ============================================================
// ① 协议契约（TM-PROTO v2）
// ============================================================

// ============================================================

/**
 * Linux 侧字段掩码。
 *
 * 用法（UISvc 构造需求时就是这样拼）：
 * ```
 * val mask = LinuxField.NAME or LinuxField.PID or LinuxField.RSS
 * GetDataFromLinuxSvc(mask)
 * ```
 *
 * 位序与 [DetailColumnId] 的声明顺序**一一对应**（bit0 = name ... bit42 = isolation），
 * 这样"页面要哪几列"能直接翻译成掩码，不需要额外维护一张映射表。
 */
object LinuxField {

    const val NONE = 0L

    const val NAME = 1L shl 0
    const val PACKAGE_NAME = 1L shl 1
    const val PID = 1L shl 2
    const val STATE = 1L shl 3
    const val USER = 1L shl 4
    const val SESSION = 1L shl 5
    const val PGID = 1L shl 6
    const val CGROUP = 1L shl 7
    const val CPU_USAGE = 1L shl 8
    const val CPU_TIME = 1L shl 9
    const val CPU_CYCLES = 1L shl 10
    const val RSS = 1L shl 11
    const val PEAK_RSS = 1L shl 12
    const val RSS_DELTA = 1L shl 13
    const val ACTIVE_PRIVATE_RSS = 1L shl 14
    const val PRIVATE_RSS = 1L shl 15
    const val SHARED_RSS = 1L shl 16
    const val VSS = 1L shl 17
    const val PAGE_FAULTS = 1L shl 18
    const val PAGE_FAULTS_DELTA = 1L shl 19
    const val NICE = 1L shl 20
    const val ANDROID_PRIORITY = 1L shl 21
    const val FD_COUNT = 1L shl 22
    const val THREAD_COUNT = 1L shl 23
    const val IO_READ_COUNT = 1L shl 24
    const val IO_WRITE_COUNT = 1L shl 25
    const val IO_READ_BYTES = 1L shl 26
    const val IO_WRITE_BYTES = 1L shl 27
    const val EXE_PATH = 1L shl 28
    const val CMDLINE = 1L shl 29
    const val SELINUX = 1L shl 30
    const val TARGET_SDK = 1L shl 31
    const val PLATFORM = 1L shl 32
    const val ARCH = 1L shl 33
    const val IS_ADMIN = 1L shl 34
    const val DESCRIPTION = 1L shl 35
    const val NX_PROTECT = 1L shl 36
    const val GPU_USAGE = 1L shl 37
    const val GPU_ENGINE = 1L shl 38
    const val GPU_DEDICATED_MEM = 1L shl 39
    const val GPU_SHARED_MEM = 1L shl 40
    const val CET_PROTECT = 1L shl 41
    const val ISOLATION = 1L shl 42

    /** 全部 43 项（bit0..bit42 全置位）。 */
    const val ALL: Long = (1L shl 43) - 1

    /** 有效位宽：掩码不能越过这一位（越过就是"另一版协议"，按参数错处理）。 */
    const val BITS = 43

    /**
     * 位序 -> 字段键名（与 [DetailColumnId] 的字符串值完全相同）。
     *
     * 为什么留这张表：解析应答时要反过来查"第 N 位叫什么"；
     * 页面显示、调试页打印也都用它，避免两处各写一份字符串。
     */
    val KEYS: List<String> = listOf(
        DetailColumnId.NAME,
        DetailColumnId.PACKAGE_NAME,
        DetailColumnId.PID,
        DetailColumnId.STATE,
        DetailColumnId.USER,
        DetailColumnId.SESSION,
        DetailColumnId.PGID,
        DetailColumnId.CGROUP,
        DetailColumnId.CPU_USAGE,
        DetailColumnId.CPU_TIME,
        DetailColumnId.CPU_CYCLES,
        DetailColumnId.RSS,
        DetailColumnId.PEAK_RSS,
        DetailColumnId.RSS_DELTA,
        DetailColumnId.ACTIVE_PRIVATE_RSS,
        DetailColumnId.PRIVATE_RSS,
        DetailColumnId.SHARED_RSS,
        DetailColumnId.VSS,
        DetailColumnId.PAGE_FAULTS,
        DetailColumnId.PAGE_FAULTS_DELTA,
        DetailColumnId.NICE,
        DetailColumnId.ANDROID_PRIORITY,
        DetailColumnId.FD_COUNT,
        DetailColumnId.THREAD_COUNT,
        DetailColumnId.IO_READ_COUNT,
        DetailColumnId.IO_WRITE_COUNT,
        DetailColumnId.IO_READ_BYTES,
        DetailColumnId.IO_WRITE_BYTES,
        DetailColumnId.EXE_PATH,
        DetailColumnId.CMDLINE,
        DetailColumnId.SELINUX,
        DetailColumnId.TARGET_SDK,
        DetailColumnId.PLATFORM,
        DetailColumnId.ARCH,
        DetailColumnId.IS_ADMIN,
        DetailColumnId.DESCRIPTION,
        DetailColumnId.NX_PROTECT,
        DetailColumnId.GPU_USAGE,
        DetailColumnId.GPU_ENGINE,
        DetailColumnId.GPU_DEDICATED_MEM,
        DetailColumnId.GPU_SHARED_MEM,
        DetailColumnId.CET_PROTECT,
        DetailColumnId.ISOLATION
    )

    /** 把掩码翻成 `name|pid|rss` 这样的可读串（日志 / 调试页用）。 */
    fun describe(mask: Long): String {
        if (mask == NONE) return "none"
        if (mask == ALL) return "all"

        return KEYS.filterIndexed { bit, _ -> (mask and (1L shl bit)) != 0L }
            .joinToString("|")
    }

    /** 位序 -> 列 id；越界返回 null（解析时遇到服务端多给的位，宁可忽略也不崩）。 */
    fun keyOfBit(bit: Int): String? = KEYS.getOrNull(bit)

    /**
     * 把一列集合（页面要显示的列）翻成掩码。
     *
     * `DetailColumnId` 的字符串与 [KEYS] 同源，所以这里按名字查位序即可。
     */
    fun maskOf(columnIds: Collection<String>): Long {
        var mask = 0L

        for (id in columnIds) {
            val bit = KEYS.indexOf(id)

            if (bit >= 0) mask = mask or (1L shl bit)
        }

        return mask
    }

    /** 这个掩码是否只用了 43 个有效位（多了就是非法参数，服务端与客户端都要拦）。 */
    fun isValid(mask: Long): Boolean = (mask and ALL.inv()) == 0L
}

// ============================================================
// 状态码（应答级 + 字段级）
// ============================================================

/**
 * 应答包头里的 status。
 *
 * 为什么要区分 [ACCEPTED] 与 [OK]：
 * "我收到了、但我还没真做" 和 "已经做完了" 是两件事。
 * 控制类请求只回 [ACCEPTED] 时，界面绝不能显示"操作成功"——那是假回执（见 F-14）。
 * KILL_PROCESS 现在会真发 SIGKILL：成功回 [OK]，信号发不出去回 [INTERNAL]，
 * 具体原因在 `I errno=<n>` 行里。
 */
object LinuxPacketStatus {

    /** 请求已执行且成功。 */
    const val OK = 0

    /** 服务不支持这种请求 / 这个控制码。 */
    const val UNSUPPORTED = 1

    /** 参数错（seq / mask / 控制码 / pid 不合法）。 */
    const val BAD_ARGUMENT = 2

    /** 服务内部错误（采集失败、内存不足等）。 */
    const val INTERNAL = 3

    /** 请求已受理，但动作尚未执行（异步排队 / 当前构建只是回执）。 */
    const val ACCEPTED = 4

    /** 目标进程在动作执行前就已不存在。 */
    const val PROCESS_EXITED = 5

    fun nameOf(status: Int): String = when (status) {
        OK -> "OK"
        UNSUPPORTED -> "UNSUPPORTED"
        BAD_ARGUMENT -> "BAD_ARGUMENT"
        INTERNAL -> "INTERNAL"
        ACCEPTED -> "ACCEPTED"
        PROCESS_EXITED -> "PROCESS_EXITED"
        else -> "UNKNOWN($status)"
    }

    /** 解析出这个 status 是否合法（0..5）。非法值说明包头被写坏了，整包要丢。 */
    fun isValid(status: Int): Boolean = status in OK..PROCESS_EXITED
}

/**
 * 字段级状态：一个统计值"为什么没有数值"。
 *
 * 这些状态不是错误码的另一种写法，它们回答的是"这一格该显示什么"：
 * [UNSUPPORTED] 显示"—（不支持）"，[ACCESS_DENIED] 显示"—（无权限）"，
 * 而真正的 0 才显示 0。把两者混起来（拿 0 顶替）会让用户以为进程真的不用 GPU。
 */
object LinuxFieldStatus {

    /** 取到了有效数值（正常情况，不会出现在 `!` 标记里）。 */
    const val SUCCESS = "SUCCESS"

    /** 当前平台 / 内核 / 驱动没有这个指标（例如没有 perf_event 时的 CPU 周期数）。 */
    const val UNSUPPORTED = "UNSUPPORTED"

    /** 有指标但读不了（SELinux 拦截、非本 uid 的 /proc/[pid]/io 等）。 */
    const val ACCESS_DENIED = "ACCESS_DENIED"

    /** 这一刻没有数据（进程刚退出、/proc 项被清掉）。 */
    const val NOT_AVAILABLE = "NOT_AVAILABLE"

    /** 读失败（解析不了、文件截断）。 */
    const val READ_FAILED = "READ_FAILED"

    /** 只有一次采样，算不出增量 / 比率（CPU 使用率、RSS 增量、缺页增量）。 */
    const val NO_BASELINE = "NO_BASELINE"

    /** 值太长被截断（真实实现里 cmdline / 路径可能超长）。 */
    const val TRUNCATED = "TRUNCATED"

    /** 给界面看的一行说明；未知状态原样返回，便于排查谁加了新状态没登记。 */
    fun describe(status: String): String = when (status) {
        SUCCESS -> "正常"
        UNSUPPORTED -> "不支持"
        ACCESS_DENIED -> "无权限"
        NOT_AVAILABLE -> "暂无数据"
        READ_FAILED -> "读取失败"
        NO_BASELINE -> "缺少基准采样"
        TRUNCATED -> "已截断"
        else -> status
    }
}

// ============================================================
// 请求类型 / 控制码
// ============================================================

/**
 * 请求包里 typeCode 的取值（十进制，不是掩码位）。
 *
 * 为什么"查询"与"控制"要分成不同 typeCode：
 * 它们要服务做的是两类事——查询是"把数据读出来"（用 [LinuxField] 掩码描述要哪些），
 * 控制是"让服务做动作"（用 [LinuxControlCode] 描述做什么）。
 * 若都塞进掩码，就会出现"第 30 位既表示 selinux 又表示杀进程"这种混乱。
 */
/**
 * 进程范围筛选（详细信息页左下角那三档）。
 *
 * 筛选在**后端做**：请求里带上范围，应答包只含范围内的行 ——
 * 这样省的不只是渲染，而是"第二轮按需读文件"和"发包本身"两段开销。
 *
 * 判定口径（与 ps / Android Task Manager 一致）：Android 给每个应用分配的 uid
 * 从 10000 起，因此 uid >= 10000 = 用户进程，其余（root / system / daemon）= 系统进程。
 * 服务端只用 stat("/proc/<pid>") 取属主，不读一整页 status。
 */
enum class ProcessScope(val wire: String, val label: String) {

    ALL("0", "全部进程"),
    USER("1", "用户进程"),
    SYSTEM("2", "系统进程");

    companion object {

        /** 协议里的十进制数字 -> 范围；不认识的值按"全部"处理（宁可多回，不可少回）。 */
        fun ofWire(text: String?): ProcessScope = when (text?.trim()) {
            USER.wire -> USER
            SYSTEM.wire -> SYSTEM
            else -> ALL
        }
    }
}

object LinuxRequestType {

    /** 查询进程快照：按 mask 描述要哪些字段。 */
    const val QUERY_PROCESS = 1

    /** 查询服务能力：协议版本、字段数、支持的掩码、自身 uid 等，一次性拿全。 */
    const val QUERY_INFO = 2

    /** 控制类：让服务做动作（重启采集、结束某个 pid、优雅退出……）。 */
    const val CONTROL = 3

    /** 心跳：只确认"进程活着、管道没断"。 */
    const val PING = 4

    /**
     * 设备快照：一次抓齐性能页要显示的整机数据（当前只做 CPU 页那一组）。
     *
     * 请求 `REQ <seq> 5 0x0`，没有附加参数。应答里**没有 P 行、全是 I 行**，
     * 所以包头 count 记的是 I 行条数（不是 0）—— 收满 count 条 I 行才算一个完整包。
     */
    const val DEVICE_SNAPSHOT = 5

    /** 合法范围（解析时用来拒绝未知 typeCode 的脏包）。 */
    val VALID: IntRange = QUERY_PROCESS..DEVICE_SNAPSHOT

    fun nameOf(typeCode: Int): String = when (typeCode) {
        QUERY_PROCESS -> "QUERY_PROCESS"
        QUERY_INFO -> "QUERY_INFO"
        CONTROL -> "CONTROL"
        PING -> "PING"
        DEVICE_SNAPSHOT -> "DEVICE_SNAPSHOT"
        else -> "UNKNOWN($typeCode)"
    }
}

/**
 * 控制类的动作码（`REQ <seq> 3 0x0 <code> [arg]`）。
 *
 * 命名口径：这里用的是"动作"，不是字段，所以不复用 [LinuxField] 的位。
 * 用户最初设想的 `ControlLinuxSvc(PROCESS_TERMINAL | XXX)` 会把两类语义混在
 * 同一个数字里，参数（进程号）也没地方放；这里改成"动作码 + 参数"两个字段。
 */
object LinuxControlCode {

    /** 结束某个进程：arg = pid（预留给"结束进程"功能）。 */
    const val KILL_PROCESS = 1

    /** 重建采集上下文：丢弃缓存、重新探一遍 /proc 能力（arg 为空）。 */
    const val REINIT = 2

    /** 让服务把内部状态吐出来（调试用，arg 为空）。 */
    const val DUMP_STATE = 3

    /** 让服务优雅退出：后端收包后回执，再自己收循环。 */
    const val SHUTDOWN = 4

    /** 合法范围。 */
    val VALID: IntRange = KILL_PROCESS..SHUTDOWN

    fun nameOf(code: Int): String = when (code) {
        KILL_PROCESS -> "KILL_PROCESS"
        REINIT -> "REINIT"
        DUMP_STATE -> "DUMP_STATE"
        SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($code)"
    }
}

// ============================================================
// 包结构（服务侧组包 / 客户端解析的共同形状）
// ============================================================

/**
 * 一个进程在包里的样子。
 *
 * @param pid      行首那个 pid（**始终存在**，哪怕请求掩码里没点 PID —— 它是行标识，
 *                 不然"没有 pid 的行"没法归位；这与"PID 列是否显示"是两件事）。
 *                 注意：PID 会被系统复用，将来接真实采集时同一个 pid 可能是另一个进程，
 *                 所以协议允许 P 行额外带 `start_ticks`（放在 [fields] 里，不占 43 列中的位）。
 * @param fields   字段键 -> 字段值（已解码；键名同 [DetailColumnId]，也可能含协议扩展键）
 * @param statuses 字段键 -> 字段级状态（[LinuxFieldStatus]）；
 *                 出现这个键就说明**这一格没有数值**，界面应显示状态而不是 0
 */
data class LinuxProcessRecord(
    val pid: Int,
    val fields: Map<String, String>,
    val statuses: Map<String, String> = emptyMap()
) {
    /** 有数值才返回；返回 null 表示"没值"（不要拿 0 顶替，见 [statusOf]）。 */
    fun fieldOf(key: String): String? = fields[key]

    /** 这一格为什么没值；null = 正常有值。 */
    fun statusOf(key: String): String? = statuses[key]

    /**
     * 给界面直接显示的一格文本：有值给值，没值给"—（原因）"。
     *
     * 表格里所有"取值"都应走这里，别再用 `fields[key] ?: "0"`——
     * 那正是"把读失败伪装成 0"的来源。
     */
    fun displayOf(key: String): String {
        val value = fields[key]

        if (value != null) return value

        val status = statuses[key] ?: return "—"

        return "—（${LinuxFieldStatus.describe(status)}）"
    }

    /**
     * 进程启动时刻（/proc/<pid>/stat 第 22 字段，单位 clock ticks）。
     *
     * PID 会被系统复用，`pid + startTicks` 才是一个进程的稳定身份：
     * 选中、结束进程、与上一轮快照比对都必须带上它。没采到时为 null。
     */
    val startTicks: Long?
        get() = fields[LinuxProtocol.PROC_KEY_START_TICKS]?.toLongOrNull()
}

/**
 * 一个完整应答包。
 *
 * @param seq       请求序号（与发出去的 REQ 对上）
 * @param isFromSvc true = 服务发出的包
 * @param typeCode  [LinuxRequestType]
 * @param mask      本次实际请求的字段掩码（服务原样回带，便于客户端确认"我要的和它是同一份"）
 * @param status    [LinuxPacketStatus]
 * @param processes 进程记录（查询类才有，可能是空列表）
 * @param info      附加信息行（能力、说明、错误原因）
 * @param raw       原始行（排错用，调试页可以整段贴出来）
 * @param complete  这个包是否完整：count 条 P 行全部收到才 true。
 *                  超时 / 管道中断导致的半包一定为 false —— 半包**不能**当成快照使用，
 *                  否则表格会少几行、或者混进上一个包的残行（F-06/F-17）。
 */
data class LinuxPacket(
    val seq: Long,
    val isFromSvc: Boolean,
    val typeCode: Int,
    val mask: Long,
    val status: Int,
    val processes: List<LinuxProcessRecord> = emptyList(),
    val info: Map<String, String> = emptyMap(),
    val raw: List<String> = emptyList(),
    val complete: Boolean = true
) {
    /** 只有"完整 + 成功"才是可用的数据包。 */
    val isOk: Boolean get() = status == LinuxPacketStatus.OK && isFromSvc && complete

    /** 已受理但没执行（不要当成成功）。 */
    val isAccepted: Boolean get() = status == LinuxPacketStatus.ACCEPTED

    /** 失败 / 不完整时的一句话原因，直接可以显示。 */
    val failureReason: String?
        get() = when {
            !complete -> "包不完整"
            status != LinuxPacketStatus.OK -> LinuxPacketStatus.nameOf(status)
            !isFromSvc -> "来向不是服务"
            else -> null
        }

    fun infoOf(key: String): String? = info[key]

    /** 一句话摘要（日志 / 调试页用）：`#3 QUERY_PROCESS ok P=1 mask=name|pid` */
    fun summary(): String {
        val statusText = if (status == LinuxPacketStatus.OK) "ok" else LinuxPacketStatus.nameOf(status)
        val completeText = if (complete) "" else " incomplete"
        val maskText = LinuxField.describe(mask)

        return "#$seq ${LinuxRequestType.nameOf(typeCode)} $statusText$completeText " +
            "P=${processes.size} mask=$maskText"
    }
}

// ============================================================
// 编解码（纯函数，方便单测与复用）
// ============================================================

/**
 * 协议编解码。
 *
 * 这里只做"字符串 <-> 结构"的转换，**不碰管道、不碰线程**：
 * 真正的收发在 LinuxService（它持有 Process 与读线程）。
 * 分开的好处是：协议出错时只看这一个文件，通信出错时只看 LinuxService。
 */
object LinuxProtocol {

    /** 协议版本。 */
    const val VERSION = 2

    /** 请求前缀 / 应答前缀（C 侧同名宏，见 main.c）。 */
    const val REQ_PREFIX = "REQ"
    const val PKG_PREFIX = "PKG"
    const val PROC_PREFIX = "P"
    const val INFO_PREFIX = "I"

    /** 后端就绪通知（进程起来、可以收请求）。它不属于任何包，单独一行。 */
    const val READY_LINE = "READY"

    /** 单行长度上限：超过它的行按"坏行"丢弃，不让一行超大文本拖垮解析（F-02）。 */
    const val MAX_LINE_LENGTH = 8192

    /**
     * P 行恒带的身份键：进程启动时刻。
     *
     * 它不占 43 位掩码（掩码是"表格要显示哪几列"，而身份是每行都需要的元数据），
     * 所以服务端无条件输出它；客户端拿 [LinuxProcessRecord.startTicks] 直接读。
     */
    const val PROC_KEY_START_TICKS = "start_ticks"

    /** 应答头解析结果（P 行还没到齐时先存着）。 */
    data class PacketHeader(
        val seq: Long,
        val isFromSvc: Boolean,
        val typeCode: Int,
        val mask: Long,
        val status: Int,
        val count: Int
    )

    /**
     * 组一个查询请求（不带换行，发送方负责补）。
     *
     * @param mask 要哪些字段（[LinuxField] 位或）
     * @param extra 附加条件（当前协议未使用，留空）
     */
    fun buildQuery(seq: Long, typeCode: Int, mask: Long, extra: String = ""): String {

        val body = "$REQ_PREFIX $seq $typeCode 0x${java.lang.Long.toHexString(mask)}"

        return if (extra.isBlank()) body else "$body $extra"
    }

    /**
     * 组一个控制请求：`REQ <seq> 3 0x0 <code> [arg]`
     *
     * 掩码位固定 0x0：控制类不看字段集合，避免"掩码里有垃圾位"被判成参数错。
     */
    fun buildControl(seq: Long, code: Int, argument: String = ""): String {

        val body = "$REQ_PREFIX $seq ${LinuxRequestType.CONTROL} 0x0 $code"

        return if (argument.isBlank()) body else "$body $argument"
    }

    /** 组一个心跳请求：`REQ <seq> 4 0x0`。 */
    fun buildPing(seq: Long): String =
        "$REQ_PREFIX $seq ${LinuxRequestType.PING} 0x0"

    /**
     * 组一个"结束进程"控制请求：`REQ <seq> 3 0x0 1 <pid> <start_ticks>`。
     *
     * 必须同时带 pid 与启动时刻：只给 pid 时，如果目标进程已经退出、PID 被新进程占用，
     * 服务端会杀掉**另一个**进程。服务端用 pid+start_ticks 不匹配时回 PROCESS_EXITED。
     */
    fun buildKill(seq: Long, pid: Int, startTicks: Long): String =
        "$REQ_PREFIX $seq ${LinuxRequestType.CONTROL} 0x0 " +
            "${LinuxControlCode.KILL_PROCESS} $pid $startTicks"

    /**
     * 解析应答头：`PKG <seq> <isFromSvc> <typeCode> <maskHex> <status> <count>`。
     *
     * 严格策略（F-01 / F-07）：任何一项不合法都返回 null，整行当"不是协议行"，
     * 调用方把它留在原始日志里。宁可丢一个坏包，也不能把坏数据喂进表格。
     *
     * 具体校验：
     * - 字段数必须正好 ≥ 7（多出来的当扩展字段，忽略）；
     * - seq > 0；isFromSvc 必须为 1；typeCode ∈ 1..5；status ∈ 0..5；count ≥ 0；
     * - mask 只能占 43 位以内。
     */
    fun parseHeader(line: String): PacketHeader? {

        if (!line.startsWith("$PKG_PREFIX ")) return null

        val parts = line.trim().split(Regex("\\s+"))

        if (parts.size < 7) return null

        val seq = parts[1].toLongOrNull() ?: return null

        if (seq <= 0L) return null

        val isFromSvc = parts[2].toIntOrNull() ?: return null

        // 这条通道上服务才会发 PKG；收到 0（或别的值）说明包头被写坏，丢弃
        if (isFromSvc != 1) return null

        val typeCode = parts[3].toIntOrNull() ?: return null

        if (typeCode !in LinuxRequestType.VALID) return null

        val mask = parseHexLong(parts[4]) ?: return null

        if (!LinuxField.isValid(mask)) return null

        val status = parts[5].toIntOrNull() ?: return null

        if (!LinuxPacketStatus.isValid(status)) return null

        val count = parts[6].toIntOrNull() ?: return null

        if (count < 0) return null

        return PacketHeader(
            seq = seq,
            isFromSvc = true,
            typeCode = typeCode,
            mask = mask,
            status = status,
            count = count
        )
    }

    /**
     * 解析进程行：`P <pid> <key>=<value> <key>=<value> ...`
     *
     * 值里不会再有空格（后端已按百分号编码，见文件头的"值的编码"），
     * 所以这里可以放心按空白切分——比"猜下一个 `k=` 在哪"可靠得多。
     *
     * 未知键（协议扩展出来的，如 `start_ticks`）照原样保留进 [LinuxProcessRecord.fields]；
     * 键名不合法（含非 `[A-Za-z0-9_]` 字符）才当脏数据丢掉。
     */
    fun parseProcessLine(line: String): LinuxProcessRecord? {

        if (!line.startsWith("$PROC_PREFIX ")) return null

        val tokens = line.substring(PROC_PREFIX.length).trim().split(Regex("\\s+"))

        if (tokens.isEmpty()) return null

        val pid = tokens[0].toIntOrNull() ?: return null

        if (pid <= 0) return null

        val fields = LinkedHashMap<String, String>()
        val statuses = LinkedHashMap<String, String>()

        for (token in tokens.drop(1)) {

            val equals = token.indexOf('=')

            if (equals <= 0) continue

            val key = token.substring(0, equals)

            if (!isValidKey(key)) continue

            val raw = token.substring(equals + 1)

            // `!状态码` = 这一格没有数值（不是 0），见 LinuxFieldStatus
            if (raw.startsWith("!")) {
                statuses[key] = raw.substring(1)

                continue
            }

            fields[key] = unescapeValue(raw)
        }

        return LinuxProcessRecord(pid, fields, statuses)
    }

    /** 解析信息行：`I <key>=<value>`（值可能被百分号编码，这里一并解码）。 */
    fun parseInfoLine(line: String): Pair<String, String>? {

        if (!line.startsWith("$INFO_PREFIX ")) return null

        val token = line.substring(INFO_PREFIX.length).trim().split(Regex("\\s+")).firstOrNull()
            ?: return null

        val equals = token.indexOf('=')

        if (equals <= 0) return null

        val key = token.substring(0, equals)

        if (!isValidKey(key)) return null

        return key to unescapeValue(token.substring(equals + 1))
    }

    /** 键名合法性：只用 ASCII 的 `[A-Za-z0-9_]`，其余一律当脏数据（F-07）。 */
    fun isValidKey(key: String): Boolean {
        if (key.isEmpty()) return false

        return key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    }

    /**
     * 值 -> 一行安全的 token（百分号编码）。
     *
     * 与 C 侧 `tm_escape_value()` 必须逐项一致；改了这边也要改那边。
     * 不编码的字符：字母、数字、以及 `/ : . - + _ ( ) [ ] < > , @ #` 这类常见符号。
     */
    fun escapeValue(value: String): String {

        val builder = StringBuilder(value.length + 8)

        for (ch in value) {
            when (ch) {
                ' ' -> builder.append("%20")
                '\n' -> builder.append("%0A")
                '\r' -> builder.append("%0D")
                '\t' -> builder.append("%09")
                '%' -> builder.append("%25")
                '=' -> builder.append("%3D")
                '!' -> builder.append("%21")
                else -> builder.append(ch)
            }
        }

        return builder.toString()
    }

    /** [escapeValue] 的逆运算（宽容：不认识的 `%XX` 原样保留，坏数据不抛异常）。 */
    fun unescapeValue(value: String): String {

        if (value.indexOf('%') < 0) return value

        val builder = StringBuilder(value.length)

        var index = 0

        while (index < value.length) {

            val ch = value[index]

            if (ch == '%' && index + 2 < value.length) {

                val code = value.substring(index + 1, index + 3).toIntOrNull(16)

                if (code != null) {
                    builder.append(code.toInt().toChar())

                    index += 3

                    continue
                }
            }

            builder.append(ch)

            index++
        }

        return builder.toString()
    }

    /** 解析 `0x...` 或纯十六进制。C 侧用 `%llx` 打出来带 0x，这里两种都吃。 */
    fun parseHexLong(text: String): Long? {

        val clean = text.trim().removePrefix("0x").removePrefix("0X")

        if (clean.isEmpty()) return null

        return clean.toLongOrNull(16)
    }
}


// ============================================================
// ② 数据服务（ELF 后端的唯一持有者）
// ============================================================

/**
 * ============================================================
 * Linux 侧数据服务（ELF 后端的唯一持有者）
 * ============================================================
 *
 * 定位：Linux 接口（/proc、/sys、自定义 ELF 后端）的数据来源，与 AndroidService 互补。
 * Android 公开 API 取不到的信息（完整进程与线程列表、cgroup、内核内存、
 * 受保护进程的 cmdline/maps 等）必须走 Linux 接口，通常要求 Root 或 Shizuku（Shell 身份）。
 *
 * 职责：进程的启动 / 停止 / 收发全部收在本服务，页面不碰 ProcessBuilder：
 *
 *     UIService（生命周期 + 对外接口）
 *         └── LinuxService（起停进程、读写管道、缓存输出）
 *               └── libtask_backend.so（ELF，常驻的请求-应答循环，见 cpp/Backend/main.c）
 *
 * 数据通道（v2 请求包，协议见本文件 ① 段）：
 * - 启动：两种身份
 *     ① 应用自身 UID：ProcessBuilder("/system/bin/linker64", nativeLibraryDir/libtask_backend.so)
 *     ② shell / root：走 ShizukuManager.newShellProcess(...)，
 *        UIService 授权成功后通过 EXTRA_PRIVILEGED_MODE 让本服务用这条身份起进程
 * - 发送：往进程 stdin 写一行 `REQ <seq> <typeCode> <mask> [arg]`（只有这一种行）
 * - 接收：后台线程逐行读 stdout；`READY` 是就绪通知，`PKG` / `P` / `I` 是应答包
 * - 配对：每个请求分配自增 seq，应答回带同一 seq（见 consumeProtocolLine）
 * - 取值：recentOutput() 给调试页看原始文本；结构化数据走 requestPackage() / controlPackage()
 * 页面与 MainActivity 都不要在本类之外持有 Process——那是本服务的私有实现细节。
 *
 * 命名约定：本类的数据接口按 Interfaces.kt 的豁免条款保留原名（recentOutput），
 * 其余管理接口走 ILinuxServiceApi（Interfaces.kt）。
 *
 * 可靠性约定（对应协议审查清单里的 F-02 / F-06 / F-12 / F-13）：
 * - 未收到 READY 之前不接受正式请求（后端可能还没进读取循环）；
 * - 一行超过 [LinuxProtocol.MAX_LINE_LENGTH] 直接丢弃，不让坏行污染解析；
 * - 包只在"收满 count 条 P 行"时才标记 complete；半包不会被当成快照。
 *  *
 * 预留实现接口（建议在本类中补齐）：
 * - readProcFile(path: String): String?          读取 /proc 下的文本（status、cmdline 等）
 * - listProcesses(): List<ProcessInfo>           遍历 /proc/<pid> 组装进程列表
 * - getMemInfo(): Map<String, Long>              解析 /proc/meminfo
 * - getThreads(pid: Int): List<ThreadInfo>       遍历 /proc/<pid>/task
 * - execute(command: String): ExecResult         需要提权时转交 RootManager / ShizukuManager
 * - 增量快照（Full / Delta）：等完整快照稳定后再做，别现在上
 *
 * 权限口径（用户明确要求，别再改回去）：
 * 本服务**不维护权限来源模型**，不认识"这是 Shizuku 还是面具 su"这套东西。
 * 它只认两件事：本进程最终跑成 uid 0、uid 2000，还是应用自身 uid；
 * "用户上次选了什么身份、Shizuku 是否以 root 运行、按钮该不该灰"全部由 UIService 决定并下发
 * （EXTRA_PRIVILEGED_MODE + EXTRA_IDENTITY_MODE 只是"要不要提权、目标 uid 大概是多少"的通信参数）。
 * 页面更不该来找本服务问权限 —— 那属于 UIService 与设置页的事。
 */

class LinuxService : Service(), ILinuxServiceApi {

    // ============================================================
    // 实例登记（同进程调用用）
    //
    // UIService 负责本服务的启动与回收，也是唯一调用方：
    // UIService 通过 instanceOrNull() 直接调用本类的方法，
    // 同进程内不需要 Binder。
    // ============================================================

    companion object {

        /** 打进 APK 的 ELF 名字（见 cpp/CMakeLists.txt 的 OUTPUT_NAME/SUFFIX）。 */
        private const val ELF_NAME = "libtask_backend.so"

        /** 用 linker64 直接拉起 ELF，绕过 zygote。 */
        private const val LINKER = "/system/bin/linker64"

        /** 输出环形缓冲保留多少行（页面只看最近这些，避免无限增长）。 */
        private const val KEEP_LINES = 40

        /** 日志标签：adb logcat -s TMLinux 只看 Linux 后端相关。 */
        private const val TAG = "TMLinux"

        /** 友好接口的默认等待上限：一次 /proc 遍历在真机上是毫秒级，这里只是留缓冲。 */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 4_000L

        /** 启动参数：true = 允许本服务用提权通道（Shizuku / root）拉起后端。 */
        const val EXTRA_PRIVILEGED_MODE = "linux_service_privileged_mode"

        /**
         * 启动参数：优先用哪个身份起后端（[UpstreamIdentityMode] 的取值）。
         *
         * 和 [EXTRA_PRIVILEGED_MODE] 的分工：那个是"允不允许提权"，这个是"优先走哪条"，
         * 由 UIService 按"上次恢复的用户身份"给出（用户选了 root 就别悄悄用 shell）。
         */
        const val EXTRA_IDENTITY_MODE = "linux_service_identity_mode"

        /** 当前运行的实例；未启动 / 已销毁时为 null。 */
        @Volatile
        private var running: LinuxService? = null

        /** 由本类在 onCreate / onDestroy 维护，外部只读。 */
        fun instanceOrNull(): LinuxService? = running
    }

    // ============================================================
    // 运行时状态
    // ============================================================

    /** 后端进程；null = 没起过，或被 stopBackend() 收掉了。 */
    @Volatile
    private var backend: Process? = null

    /** 读 stdout 的后台线程（进程退出时会自己结束）。 */
    @Volatile
    private var reader: Thread? = null

    /** 最近若干行输出（命令回显 + 后端应答），加锁保护。 */
    private val lines = ArrayDeque<String>()

    private val lineLock = Any()

    /**
     * 是否允许用提权身份起后端（由 UIService 通过 [EXTRA_PRIVILEGED_MODE] 指定）。
     *
     * 具体走 Shizuku 还是 root 由本服务按当时能力自己挑（见 startBackend）。
     * 只在"启动这一件事"上生效：进程起来之后身份就固定了，
     * 所以 UIService 在授权成功后必须重启本服务（连带重启后端）。
     */
    @Volatile
    private var privilegedMode: Boolean = false

    /**
     * 优先走哪条提权通路（[UpstreamIdentityMode]：auto / shell / root）。
     *
     * 由 UIService 下发，反映"用户上次选的身份"；见 startBackend 里的通路顺序。
     */
    @Volatile
    private var identityMode: String = UpstreamIdentityMode.AUTO

    /** 当前后端进程实际是不是以提权身份起的。 */
    @Volatile
    private var backendPrivileged: Boolean = false

    /** 当前后端身份的说明文案（写进输出首行，页面直接展示）。 */
    @Volatile
    private var backendIdentityText: String = "未启动"

    /**
     * 后端是否已经打过 `READY`（就绪握手，F-12）。
     *
     * 为什么必须有这道门槛：进程"起来了"不等于"能收请求"。
     * ELF 的启动段要先打印标识与身份、再进读取循环；这之前写进去的字节虽然会被管道缓冲住，
     * 但如果启动段就失败退出，请求会永远等不到应答，看起来像"服务不返回数据"。
     * 所以：没 READY 时不发正式请求，只在心跳里试探。
     */
    @Volatile
    private var backendReady: Boolean = false

    /**
     * 服务正在销毁。
     *
     * 用来堵一个竞态：自动启动走后台线程（起进程要等 IPC / 授权框），
     * 而 stopUpstreamServices 会先 stopBackend 再 stopService。
     * 如果后台线程恰好在 stopBackend 之后、onDestroy 之前把进程起来，就会留下孤儿进程。
     * 所以自动启动线程收尾时要再看一眼这个标志，必要时把刚起的后端立刻收掉。
     */
    @Volatile
    private var shuttingDown: Boolean = false

    // ============================================================
    // v2 请求包：在途请求登记（请求 - 应答配对）
    //
    // 背景：管道是双向异步的，服务端可能晚一点才回，也可能同时有多个请求在路上
    // （进程页刷新的同时调试页点了一下查询）。所以每个请求分配一个自增 seq，
    // 并登记到 pending；读线程收到带同一 seq 的 PKG 头时，才把结果交给等它的那个线程。
    // ============================================================

    /** 一个在途请求的接收槽：头部一到就记下来，P/I 行往里追加，收满 count 后放行。 */
    private class PendingRequest {

        /** 头部还没到时占位用（也用于日志）。 */
        val typeCode: Int

        /** 本次要的字段，只用于日志。 */
        val mask: Long

        /** 结果一到就 countDown，唤醒 requestPackage 里等应答的线程。 */
        val latch = CountDownLatch(1)

        /** 解析好的包（线程安全靠 latch 的 happens-before 保证）。 */
        @Volatile
        var packet: LinuxPacket? = null

        /** 是否已经收尾（防止"count=0 的包头"和"最后一条 P 行"重复触发）。 */
        @Volatile
        var finished: Boolean = false

        @Volatile
        var header: LinuxProtocol.PacketHeader? = null

        val processes = ArrayList<LinuxProcessRecord>()
        val info = LinkedHashMap<String, String>()
        val raw = ArrayList<String>()

        constructor(typeCode: Int, mask: Long) {
            this.typeCode = typeCode
            this.mask = mask
        }
    }

    /** 保护 nextSeq / pending / activePending 三件套。 */
    private val requestLock = Any()

    /** 请求序号，从 1 开始递增（0 保留给"无序号"的突发调试）。 */
    private var nextSeq = 0L

    /** seq -> 在途请求。 */
    private val pending = LinkedHashMap<Long, PendingRequest>()

    /**
     * 当前正在接收数据行的包。
     *
     * 服务端是"一条请求处理完再处理下一条"的顺序模型，所以同一时刻只会有一个包在收，
     * 用单槽即可；真出现并发（协议升级）再换成按 seq 的 Map。
     */
    private var activePending: PendingRequest? = null

    // ============================================================
    // 生命周期（由 UIService 驱动）
    // ============================================================

    override fun onCreate() {
        super.onCreate()

        shuttingDown = false

        running = this
    }

    override fun onDestroy() {
        shuttingDown = true

        // 服务被回收时一定把子进程带走，否则会留下一个没人管的 ELF
        stopBackend()

        // 只在"登记的还是自己"时才清空。
        // 为什么要判：授权成功后 UIService 会 stop -> start（换提权身份），
        // stopService 是异步的，旧实例的 onDestroy 可能晚于新实例的 onCreate 执行；
        // 无条件清空会把新实例的登记一起抹掉，之后 instanceOrNull() 永远是 null，
        // 表现就是调试页第二框永远"尚未就绪"、"服务没有返回任何参数"。
        if (running === this) {
            running = null
        }

        super.onDestroy()
    }

    // 预留：若以后需要跨进程访问，可在此返回 Binder / AIDL 实现；当前不提供绑定。
    override fun onBind(intent: Intent?): IBinder? {
        // 不暴露 Binder：同进程内直接调用方法即可（AndroidService 同样处理）
        return null
    }

    // 服务启动回调：读取身份模式；模式变了就把旧后端收掉，下次启动用新身份
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val wantedPrivileged = intent?.getBooleanExtra(EXTRA_PRIVILEGED_MODE, false) ?: false

        // 优先身份：非法值收敛成 auto（能力优先），不因为一个参数写错就不起后端
        val wantedIdentityMode = (intent?.getStringExtra(EXTRA_IDENTITY_MODE) ?: UpstreamIdentityMode.AUTO)
            .takeIf {
                it == UpstreamIdentityMode.AUTO ||
                    it == UpstreamIdentityMode.SHELL ||
                    it == UpstreamIdentityMode.ROOT
            } ?: UpstreamIdentityMode.AUTO

        // 这次下发的身份和上次不一样，说明"用户换身份了"
        val modeChanged = wantedPrivileged != privilegedMode || wantedIdentityMode != identityMode

        if (modeChanged) {

            privilegedMode = wantedPrivileged
            identityMode = wantedIdentityMode

            append(
                if (privilegedMode) "身份模式：优先提权（$identityMode），失败回退应用身份"
                else "身份模式：应用自身 UID"
            )
        }

        // ------------------------------------------------------------
        // 初始化 / 身份切换后：自动保证"当前跑着一个后端"，不再需要调试页点按钮
        //
        // 顺序必须是"先杀旧再起新"：旧 ELF 是上一种身份起的，留着它，
        // 界面上就会出现"身份显示普通权限、进程其实还是 root"这种自相矛盾的状态。
        //
        // 必须放到后台线程：startBackend 里的 Shizuku newProcess / su 都是跨进程调用，
        // su 还可能挂着面具的授权框等用户点，主线程被它拖住就是 ANR。
        // ------------------------------------------------------------
        Thread {
            runCatching {

                if (modeChanged && isBackendRunning) {

                    append("身份模式变化：先回收旧身份的后端，再按新身份重启")

                    stopBackend()
                }

                // 幂等：已经在跑就直接返回；上一轮被杀/崩了的话这里会重新拉起
                if (!isBackendRunning) {
                    startBackend()
                }

                // 起进程期间服务可能已经被销毁（换身份时 UIService 会 stop -> start）：
                // 这种情况马上收掉，别把"旧身份"的后端留在后台
                if (shuttingDown) {
                    stopBackend()
                }
            }
        }.apply {

            isDaemon = true
            name = "TMLinux-AutoStart"
        }.start()

        // 不粘连启动：进程被杀后不自动重建，避免后台服务被系统反复拉起
        return START_NOT_STICKY
    }

    // ============================================================
    // ILinuxServiceApi：ELF 后端的启动 / 停止 / 收发
    // ============================================================

    /** 后端是否在跑（进程还活着）。 */
    override val isBackendRunning: Boolean
        get() = isProcessAlive(backend)

    /**
     * 安全地问一句"这进程还活着吗"。
     *
     * 为什么不能直接写 process.isAlive：Process.isAlive() 靠"exitValue() 抛
     * IllegalThreadStateException"来判断存活，而有些实现（典型是 Shizuku 的
     * ShizukuRemoteProcess）在进程还活着时抛的是 IllegalArgumentException("process hasn't exited")，
     * 基类接不住这个异常 —— 它就一路炸到主线程，把整个应用带走（真机崩溃栈已实证）。
     * 这里统一兜住：任何异常都当作"拿不到存活状态"，宁可当它没在跑，也不能让界面崩。
     */
    private fun isProcessAlive(process: Process?): Boolean {

        return try {
            process?.isAlive == true
        } catch (e: Throwable) {
            Log.w(TAG, "存活判断失败：${e.javaClass.simpleName} ${e.message ?: ""}")
            false
        }
    }

    /**
     * 当前后端是不是"以提权身份"在跑（uid 0 / uid 2000 都算，false = 应用自身 UID）。
     *
     * 名字保留 isShellBackend 是为了不牵动已有的接口与页面代码，
     * 语义按"非应用身份"理解；具体 uid 由 [backendIdentity] 给出。
     */
    override val isShellBackend: Boolean
        get() = backendPrivileged && isBackendRunning

    /**
     * 当前后端的身份标识，只报最终 uid（"uid=0" / "uid=2000" / "uid=<应用自身>"）。
     *
     * 口径（来自用户）：服务只负责"我是谁在跑"，不对外解释权限是从 Shizuku 还是 su 借来的 ——
     * 那套权限来源模型只留在 UIService（以及设置页 / 调试页的展示层）。
     */
    override fun backendIdentity(): String {

        return if (isBackendRunning) backendIdentityText else "$backendIdentityText（已退出）"
    }

    /**
     * 启动 ELF 后端（幂等：已经在跑就直接返回 true）。
     *
     * 提权模式下按"能力最强到最兜底"的顺序选身份：
     * ① Shizuku 已授权 -> ShizukuManager.newShellProcess，后端跑在 uid 2000 / 0；
     * ② 没有 Shizuku 但 Root 可用 -> RootManager.newRootProcess（`su -c`），后端跑在 uid 0；
     * ③ 两条都没有（或都起不来）-> ProcessBuilder，用应用自身 UID 起。
     *
     * 每一级失败都会把原因写进输出缓冲，调试页第二框能直接看到是谁在跑、为什么降级。
     *
     * @return true = 进程已成功拉起；false = 找不到 ELF 或启动失败（原因已记入输出缓冲）
     */
    @Synchronized
    override fun startBackend(): Boolean {
        val current = backend

        if (isProcessAlive(current)) {
            return true
        }

        // 上一次可能已经崩了：先清理残留，再重新起
        stopBackend()

        val elf = File(applicationInfo.nativeLibraryDir, ELF_NAME)

        if (!elf.exists()) {
            append("找不到 ELF: ${elf.absolutePath}")

            return false
        }

        val command = listOf(LINKER, elf.absolutePath)

        // ---- 提权通路：只按"目标身份是 uid 0 还是 uid 2000"来选，不管权限从哪来 ----
        //
        // 本次迭代的重要口径（来自用户）：
        //   本服务不认识"Shizuku / 面具 su"这套权限模型，只知道"我要以 uid 0 还是 uid 2000 跑"。
        //   所以 ROOT 目标下，Shizuku（当它自己跑成 root 时）与 su 都是**合法的 root 通路**，
        //   两条都要试 —— 只试 su 就是之前"Shizuku 的 root 能起安卓服务、起不了 Linux 服务"的根因。
        //
        // shell：只接受 uid 2000，只能走 Shizuku；
        // root ：接受 uid 0，Shizuku(root) 与 su 都行（顺序：先用已授权的 Shizuku，再 su）；
        // auto ：能力优先，Shizuku（shell 或 root 都算）-> su -> 应用身份。
        if (privilegedMode) {

            when (identityMode) {

                UpstreamIdentityMode.SHELL -> {

                    if (startWithShizuku(command, elf, requireRoot = false)) return true

                    append("目标身份 uid=2000，Shizuku 没能起进程，回退应用身份")
                }

                UpstreamIdentityMode.ROOT -> {

                    if (startWithShizuku(command, elf, requireRoot = true)) return true

                    if (startWithRoot(command, elf)) return true

                    append("目标身份 uid=0，Shizuku(root) 与 su 都没能起进程，回退应用身份")
                }

                else -> {

                    if (startWithShizuku(command, elf, requireRoot = false)) return true

                    if (startWithRoot(command, elf)) return true
                }
            }
        }

        // ---- 兜底：应用自身 UID ----
        return try {
            val process = ProcessBuilder(LINKER, elf.absolutePath)
                .redirectErrorStream(true)
                .start()

            backend = process
            backendPrivileged = false
            backendIdentityText = "uid=${android.os.Process.myUid()}"

            append("已启动 ${elf.absolutePath}")

            startReader(process)

            Log.i(TAG, "Linux backend 已启动: ${elf.absolutePath}")

            true
        } catch (e: Exception) {
            append("启动失败: ${e.message}")

            Log.e(TAG, "Linux backend 启动失败", e)

            false
        }
    }

    /**
     * 用 Shizuku 的进程身份起后端（uid 2000 = shell，或 Shizuku 跑成 root 时的 uid 0）。
     *
     * @param requireRoot true = 只接受 uid 0（Shizuku 自己跑成 root 时才满足）；
     *                    false = 接受 Shizuku 能给出的任何身份（shell 或 root）
     * @return true = 已起并开始读输出；false = 这条通路不可用（原因已写入输出缓冲）
     */
    private fun startWithShizuku(command: List<String>, elf: File, requireRoot: Boolean): Boolean {

        val status = try {
            ShizukuManager.status.value
        } catch (e: Throwable) {
            null
        }

        if (status?.isAuthorized != true) {
            append("Shizuku 未授权，跳过这条通路")

            return false
        }

        // 目标 uid 0 但 Shizuku 自己是 shell：它给不出 root，这条通路不满足目标，跳过（不算失败原因）
        if (requireRoot && !status.isRoot) {
            append("目标是 uid=0，但 Shizuku 当前是 uid=2000，跳过这条通路")

            return false
        }

        var failReason = ""

        val shellProcess = try {
            ShizukuManager.newShellProcess(command)
        } catch (e: Throwable) {
            failReason = "${e.javaClass.simpleName}: ${e.message ?: ""}"

            null
        }

        if (shellProcess == null) {
            // 把异常原文带上：root 身份起 ELF 时被 SELinux / 权限挡掉，就靠这一行定位
            append(
                "Shizuku 起进程失败" + if (failReason.isEmpty()) "（返回 null）" else "（$failReason）"
            )

            return false
        }

        backend = shellProcess
        backendPrivileged = true

        // 这里只报最终 uid：调试页需要知道"进程是谁在跑"，不需要知道权限从哪条通道借来的
        backendIdentityText = if (status.isRoot) "uid=0" else "uid=2000"

        append("已以 ${backendIdentityText} 启动 ${elf.absolutePath}")

        startReader(shellProcess)

        Log.i(TAG, "Linux backend 已以 ${backendIdentityText} 启动")

        return true
    }

    /**
     * 用 su 的 root 身份起后端（`su -c exec ...`，uid 0）。
     *
     * @return true = 已起并开始读输出；false = 这条通路不可用（原因已写入输出缓冲）
     */
    private fun startWithRoot(command: List<String>, elf: File): Boolean {

        val rootAvailable = try {
            RootManager.isAvailable
        } catch (e: Throwable) {
            false
        }

        if (!rootAvailable) {
            append("Root 不可用（没有 su 或未授权），跳过这条通路")

            return false
        }

        var failReason = ""

        val rootProcess = try {
            RootManager.newRootProcess(command)
        } catch (e: Throwable) {
            failReason = "${e.javaClass.simpleName}: ${e.message ?: ""}"

            null
        }

        if (rootProcess == null) {
            append(
                "Root 起进程失败" + if (failReason.isEmpty()) "（返回 null）" else "（$failReason）"
            )

            return false
        }

        backend = rootProcess
        backendPrivileged = true

        // 只报 uid：本服务不区分"这是面具 su 还是别的"，调试页也只需要看到进程的 uid
        backendIdentityText = "uid=0"

        append("已以 uid=0 启动 ${elf.absolutePath}")

        startReader(rootProcess)

        Log.i(TAG, "Linux backend 已以 uid=0 启动")

        return true
    }

    /**
     * 停止后端：先礼后兵——发一个 v2 SHUTDOWN 控制包让循环自己退出，超时再 destroy。
     *
     * 走包协议而不是直接 destroy 的好处：这次退出也会被回执，
     * 日志里能分清"按指令退出"与"被强杀"。
     * 这里**不等**回执（stopBackend 可能在主线程上被调用），发完就给 300ms 收尾。
     */
    @Synchronized
    override fun stopBackend() {
        val process = backend ?: return

        backend = null

        backendReady = false

        runCatching {
            if (isProcessAlive(process)) {
                // seq 用一个本地临时号：这里不等应答，不和 pending 抢号
                val seq = synchronized(requestLock) { ++nextSeq }

                process.outputStream.write(
                    (LinuxProtocol.buildControl(seq, LinuxControlCode.SHUTDOWN) + "\n").toByteArray()
                )
                process.outputStream.flush()
            }
        }

        runCatching {
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                process.destroy()
            }
        }

        runCatching {
            if (isProcessAlive(process)) {
                process.destroyForcibly()
            }
        }

        reader?.interrupt()
        reader = null

        // 后端都已收掉，在途请求不可能再有应答了：立刻放行
        failAllPending("后端已停止")

        append("已请求停止 Linux backend（SHUTDOWN 控制包）")
    }

    /**
     * 往后端 stdin 写一行协议文本（内部原语，只给本类的请求方法用）。
     *
     * 为什么收成 private：谁都能绕开 seq 配对往管道里塞一行，应答就分不清是谁的（F-04）。
     * 写管道这件事只发生在这里，对外只有 requestPackage / controlPackage / pingPackage。
     *
     * @param line 一整行协议文本（不要带换行，本方法会补）
     * @return true = 写成功；false = 进程没在跑 / 管道已断 / 写失败
     */
    @Synchronized
    private fun writeLine(line: String): Boolean {
        val process = backend ?: return false

        // 这里就是真机上崩掉的那一行：直接用 isAlive 会被 Shizuku 的异常实现带崩，必须走安全判断
        if (!isProcessAlive(process)) {
            return false
        }

        return try {
            process.outputStream.write((line + "\n").toByteArray())
            process.outputStream.flush()

            // 命令也记一份，页面看到的是"我说了什么 / 它回了什么"
            append("> $line")

            true
        } catch (e: Exception) {
            // 写失败（EPIPE / 管道已关闭 / 对端退出）：这一轮请求不可能有应答了
            append("发送失败: ${e.message}")

            failAllPending("写入管道失败")

            false
        }
    }

    /** 最近输出（含命令回显与后端应答），页面通过 UIService 转发读取。 */
    override fun recentOutput(): String = snapshot()

    // ============================================================
    // 数据接口（v2 请求包；协议细节见本文件 ① 段）
    // ============================================================

    /**
     * 发一个查询请求并等待应答（同步）。
     *
     * 流程：分配 seq -> 登记接收槽 -> 写一行 `REQ ...` -> 等读线程把包收满 -> 取出结果。
     * 超时 / 后端退出 / 发送失败都返回 null，**不抛异常**：
     * 页面拿不到数据只该显示"暂无数据"，不该因此崩掉。
     *
     * @param timeoutMs 建议 3000~5000ms：一次 /proc 遍历在真机上只有毫秒级，
     *                  留这么长只是为了容忍"刚好在重启后端"的瞬间。
     */
    override fun requestPackage(typeCode: Int, mask: Long, timeoutMs: Long): LinuxPacket? {

        if (!isBackendReady) {
            append("请求推迟：后端尚未就绪（未收到 READY）")

            return null
        }

        val seq = nextSeqOf()

        return exchange(seq, LinuxProtocol.buildQuery(seq, typeCode, mask), typeCode, mask, timeoutMs)
    }

    /**
     * 发一个控制请求并等待回执（同步）。
     *
     * 与查询共用一条管道与同一套 seq 配对：服务端按到达顺序处理，
     * 控制命令会排在之前发出的查询之后执行，不会插队破坏数据一致性。
     */
    override fun controlPackage(code: Int, argument: String, timeoutMs: Long): LinuxPacket? {

        if (!isProcessAlive(backend)) {
            append("控制失败：后端没有在运行（${LinuxControlCode.nameOf(code)}）")

            return null
        }

        // 控制类不看 READY：SHUTDOWN / DUMP_STATE 在"半启动"状态下也有意义
        val seq = nextSeqOf()

        return exchange(
            seq,
            LinuxProtocol.buildControl(seq, code, argument),
            LinuxRequestType.CONTROL,
            0L,
            timeoutMs
        )
    }

    /**
     * 发心跳并等回执。
     *
     * 用途有两个：调试页的"通道是否还通"自检；以及 READY 迟迟不来时的存活探测。
     */
    fun pingPackage(timeoutMs: Long): LinuxPacket? {

        if (!isProcessAlive(backend)) return null

        val seq = nextSeqOf()

        return exchange(
            seq,
            LinuxProtocol.buildPing(seq),
            LinuxRequestType.PING,
            0L,
            timeoutMs
        )
    }

    // ---------- 友好接口：调用方（UIService）只用这一层，不碰协议细节 ----------

    /**
     * 取一帧设备快照（性能页用；当前只有 CPU 那一组字段）。
     *
     * 请求措辞：`REQ <seq> 5 0x0`，没有附加参数。应答全是 I 行，没有 P 行，
     * 所以这里不需要掩码，也不做范围筛选。
     *
     * @return 帧数据；后端没就绪 / 超时 / 回 UNSUPPORTED（老版本后端）→ null
     */
    fun deviceSnapshot(timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): PerformanceSnapshot? {

        if (!isBackendReady) {
            append("设备快照推迟：后端尚未就绪（未收到 READY）")

            return null
        }

        val packet = requestPackage(LinuxRequestType.DEVICE_SNAPSHOT, 0L, timeoutMs)

        if (packet != null && !packet.isOk) {
            append("设备快照未采用：${packet.failureReason ?: packet.summary()}")
        }

        return PerformanceSnapshot.from(packet)
    }

    /**
     * 取一次进程快照。
     *
     * 请求措辞：`REQ <seq> 1 <maskHex> <scope>` —— 第 5 个字段是范围，
     * 服务端在 mini 阶段就按它过滤，所以包里**只有范围内的行**。
     *
     * @param mask  要哪些统计项（`LinuxField.NAME or LinuxField.PID or ...`）；
     *              传 [LinuxField.NONE] 表示全都要
     * @param scope 全部 / 用户 / 系统（见 [ProcessScope]）
     * @return 完整应答包；没有后端 / 超时 → null
     */
    fun snapshot(
        mask: Long,
        scope: ProcessScope = ProcessScope.ALL,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): LinuxPacket? {

        if (!isBackendReady) {
            append("请求推迟：后端尚未就绪（未收到 READY）")

            return null
        }

        val effective = if (mask == LinuxField.NONE) LinuxField.ALL else mask

        val seq = nextSeqOf()

        return exchange(
            seq,
            LinuxProtocol.buildQuery(seq, LinuxRequestType.QUERY_PROCESS, effective, scope.wire),
            LinuxRequestType.QUERY_PROCESS,
            effective,
            timeoutMs
        )
    }

    /** 服务能力自述（协议版本 / 字段数 / 支持掩码 / 自身 uid），客户端自检用。 */
    fun capabilities(timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): LinuxPacket? =
        requestPackage(LinuxRequestType.QUERY_INFO, LinuxField.NONE, timeoutMs)

    /** 通道存活探测：true = 后端回了一个 OK 心跳。 */
    fun ping(timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): Boolean =
        pingPackage(timeoutMs)?.isOk == true

    /**
     * 结束进程。
     *
     * @param pid        目标进程号
     * @param startTicks 该进程的启动时刻（快照里的 start_ticks），用来确认"还是同一个进程"。
     *                   **0 是合法值**（开机第 0 个 tick 起的进程，如 init）；负数 = 没有这个信息
     * @return 回执包；参数不全 / 没有后端 → null
     */
    fun killProcess(pid: Int, startTicks: Long, timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): LinuxPacket? {

        if (pid <= 0 || startTicks < 0L) {
            append("KILL 被拒：需要 pid 与 start_ticks（pid=$pid, startTicks=$startTicks）")

            return null
        }

        if (!isProcessAlive(backend)) {
            append("KILL 失败：后端没有在运行（pid=$pid）")

            return null
        }

        val seq = nextSeqOf()

        return exchange(
            seq,
            LinuxProtocol.buildKill(seq, pid, startTicks),
            LinuxRequestType.CONTROL,
            0L,
            timeoutMs
        )
    }

    /** 让后端丢掉采样基准，下一轮快照重新建立（增量字段会先回一轮 NO_BASELINE）。 */
    fun reinitialize(timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): LinuxPacket? =
        controlPackage(LinuxControlCode.REINIT, "", timeoutMs)

    /** 后端内部状态（已处理请求数、基准状态等）。 */
    fun dumpState(timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): LinuxPacket? =
        controlPackage(LinuxControlCode.DUMP_STATE, "", timeoutMs)

    /** 请求后端优雅退出（回执后自己收循环）；调用方随后再 destroy 进程。 */
    fun requestShutdown(timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): LinuxPacket? =
        controlPackage(LinuxControlCode.SHUTDOWN, "", timeoutMs)

    /** 后端是否已经进读取循环（收到 READY）。 */
    val isBackendReady: Boolean
        get() = isBackendRunning && backendReady

    /** 分配请求序号（seq 0 保留给"无序号"的意思，从 1 开始）。 */
    private fun nextSeqOf(): Long = synchronized(requestLock) { ++nextSeq }

    /**
     * 请求-应答的公共实现：登记槽 -> 写管道 -> 等 latch -> 收尾。
     *
     * 抽出来的原因：查询与控制原本是两份几乎一样的代码，改一处漏一处就会出现
     * "查询有超时清理、控制没有"这类泄漏（pending 表越用越大）。
     *
     * @param seq 本次请求序号（已由调用方分配并写进 [requestText]）
     * @return 配对成功的包；发送失败 / 超时 / 后端先退出 → null
     */
    private fun exchange(
        seq: Long,
        requestText: String,
        typeCode: Int,
        mask: Long,
        timeoutMs: Long
    ): LinuxPacket? {

        if (!isProcessAlive(backend)) {
            append("请求失败：后端没有在运行（${LinuxRequestType.nameOf(typeCode)}）")

            return null
        }

        val slot = PendingRequest(typeCode, mask)

        synchronized(requestLock) { pending[seq] = slot }

        if (!writeLine(requestText)) {
            synchronized(requestLock) { pending.remove(seq) }

            return null
        }

        val answered = try {
            slot.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // 调用方线程被中断（例如页面退出、线程池关闭）：恢复中断标记并按"没拿到"返回
            Thread.currentThread().interrupt()

            false
        }

        synchronized(requestLock) {
            pending.remove(seq)

            if (activePending === slot) activePending = null
        }

        if (!answered) {
            append(
                "请求超时（seq=$seq ${LinuxRequestType.nameOf(typeCode)} " +
                    "mask=${LinuxField.describe(mask)}）"
            )

            return null
        }

        val packet = slot.packet

        append("应答 ${packet?.summary() ?: "#$seq（空包）"}")

        return packet
    }

    /**
     * 读线程每收到一行就调一次：认出 `READY` / `PKG` / `P` / `I`，喂给对应的接收槽。
     *
     * 不认识的行走原路——已经进了原始输出缓冲（调试页照旧可见），不影响协议。
     * 重要原则：**协议行只在"能确定归属"时才处理**，宁可丢一行脏数据，
     * 也不能把别人的 P 行塞进当前包（那会让表格混进别的进程，F-17）。
     */
    private fun consumeProtocolLine(line: String) {

        // 0) 就绪握手：`READY` 单独一行，表示后端已经进读取循环（F-12）
        if (line.trim() == LinuxProtocol.READY_LINE) {

            backendReady = true

            append("后端已就绪（READY）")

            return
        }

        // ① 包头：`PKG <seq> <isFromSvc> <type> <mask> <status> <count>`
        val header = LinuxProtocol.parseHeader(line)

        if (header != null) {

            val slot = synchronized(requestLock) { pending[header.seq] }

            if (slot == null) {
                // 没人等这个 seq：可能是超时后迟到的应答，记一笔就扔，别影响后续流程
                append("忽略无主应答（seq=${header.seq}）：请求已超时或已被取消")

                return
            }

            // 上一个包还没收满就来了新包头：说明服务端少发了 P 行。
            // 这里必须先把它按"不完整"收尾，否则它的 P 行会被算到新包头名下。
            val interrupted = synchronized(requestLock) {
                val previous = activePending

                activePending = null

                previous
            }

            if (interrupted != null && !interrupted.finished) {

                append(
                    "上一个包未收满（seq=${interrupted.header?.seq} " +
                        "已收 ${interrupted.processes.size}/${interrupted.header?.count}），按不完整收尾"
                )

                finishSlot(interrupted)
            }

            synchronized(requestLock) {
                slot.header = header
                slot.raw.add(line)
                activePending = slot
            }

            // count=0 的包（QUERY_INFO、PING、控制回执）到此就已经完整了。
            // 设备快照例外：它的 count 记的是"后面 I 行的条数"，见到包头只是开头；
            // 但失败包（UNSUPPORTED 等）本来就没有 I 行，见到包头就得收尾，
            // 否则要白等满一个超时。
            if (header.typeCode == LinuxRequestType.DEVICE_SNAPSHOT) {

                if (header.status != LinuxPacketStatus.OK || header.count <= 0) {
                    finishSlot(slot)
                }
            } else if (header.count <= 0) {
                finishSlot(slot)
            }

            return
        }

        val current = synchronized(requestLock) { activePending } ?: return

        // ② 进程行：`P <pid> k=v k=v ...`
        if (line.startsWith("${LinuxProtocol.PROC_PREFIX} ")) {

            val record = LinuxProtocol.parseProcessLine(line)

            if (record == null) {
                append("进程行解析失败，已跳过：$line")

                return
            }

            val reached = synchronized(requestLock) {
                current.processes.add(record)
                current.raw.add(line)

                val count = current.header?.count ?: 0

                current.processes.size >= count
            }

            if (reached) finishSlot(current)

            return
        }

        // ③ 信息行：`I k=v`
        if (line.startsWith("${LinuxProtocol.INFO_PREFIX} ")) {

            val pair = LinuxProtocol.parseInfoLine(line)

            if (pair == null) return

            var reached = false

            synchronized(requestLock) {
                current.info[pair.first] = pair.second
                current.raw.add(line)

                val header = current.header

                // 设备快照没有 P 行，靠"收满 count 条 I 行"判断收完
                reached = header != null &&
                    header.typeCode == LinuxRequestType.DEVICE_SNAPSHOT &&
                    current.info.size >= header.count
            }

            if (reached) finishSlot(current)

            return
        }

        // 其它行（启动标识、身份自检、错误提示……）只留在原始日志里
    }

    /**
     * 收尾一个接收槽：组装成 [LinuxPacket]、放行等待方。
     *
     * 幂等：包头（count=0）与最后一条 P 行可能都触发一次，只有第一次生效。
     * complete 的判据是"收满 count 条 P 行"：少一条就是半包，调用方必须知道（F-06）。
     */
    private fun finishSlot(slot: PendingRequest) {

        val header = slot.header ?: return

        val processes: List<LinuxProcessRecord>
        val info: Map<String, String>
        val raw: List<String>

        synchronized(requestLock) {

            if (slot.finished) return

            slot.finished = true

            if (activePending === slot) activePending = null

            processes = slot.processes.toList()
            info = slot.info.toMap()
            raw = slot.raw.toList()
        }

        // 完整判据：进程包看 P 行够不够 count；设备快照看 I 行够不够 count
        val complete = if (header.typeCode == LinuxRequestType.DEVICE_SNAPSHOT) {
            info.size >= header.count
        } else {
            processes.size == header.count
        }

        if (!complete) {

            val gotCount = if (header.typeCode == LinuxRequestType.DEVICE_SNAPSHOT) {
                info.size
            } else {
                processes.size
            }

            append(
                "包不完整（seq=${header.seq}）：收到 $gotCount 条，" +
                    "包头声称 ${header.count} 条"
            )
        }

        slot.packet = LinuxPacket(
            seq = header.seq,
            isFromSvc = header.isFromSvc,
            typeCode = header.typeCode,
            mask = header.mask,
            status = header.status,
            processes = processes,
            info = info,
            raw = raw,
            complete = complete
        )

        slot.latch.countDown()
    }

    /**
     * 后端没了 / 正在停：把所有在途请求立刻放行（返回 null），
     * 免得页面线程还要白等满一个超时。
     */
    private fun failAllPending(reason: String) {

        val slots = synchronized(requestLock) {

            val list = pending.values.toList()

            pending.clear()

            activePending = null

            list
        }

        if (slots.isEmpty()) return

        append("$reason：在途请求 ${slots.size} 个，已全部放行（返回空）")

        slots.forEach { it.latch.countDown() }
    }

    // ============================================================
    // 内部实现
    // ============================================================

    /** 把当前输出缓冲拼成给人看的文本；空缓冲时给出明确状态。 */
    private fun snapshot(): String {
        val recent = synchronized(lineLock) {
            lines.toList()
        }

        if (recent.isEmpty()) {
            return if (isBackendRunning) "Linux Backend 已启动，暂无输出" else "Linux Backend 未启动"
        }

        val header = when {

            !isBackendRunning -> "Linux Backend 已退出（上次身份：$backendIdentityText）"

            backendPrivileged -> "Linux Backend 运行中（身份：$backendIdentityText）"

            else -> "Linux Backend 运行中（身份：应用自身 UID）"
        }

        return (listOf(header) + recent).joinToString("\n")
    }

    /**
     * 后台读 stdout：一行一行往缓冲里追加，进程结束（EOF）时线程自然退出。
     *
     * 三个保护（对应 F-02 / F-10 / F-11）：
     * - 每起一次进程都把 ready 归零：上一轮进程的 READY 不能继承给新进程；
     * - 超长行直接丢弃：一行几 MB 的脏数据会把解析器和日志缓冲一起拖垮；
     * - 读到 EOF = 通道已断：立刻放行在途请求，别让页面白等一个超时。
     */
    private fun startReader(process: Process) {
        backendReady = false

        synchronized(requestLock) { activePending = null }

        val thread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break

                        if (line.length > LinuxProtocol.MAX_LINE_LENGTH) {
                            append(
                                "丢弃超长行（${line.length} 字节，上限 " +
                                    "${LinuxProtocol.MAX_LINE_LENGTH}）"
                            )

                            continue
                        }

                        // 先进原始日志（调试页与排错用），再做协议解析（v2 请求包）
                        append(line)

                        consumeProtocolLine(line)
                    }
                }
            } catch (e: Exception) {
                append("读取输出中断: ${e.message}")
            }

            // 管道读完 = 后端没了：把还在等应答的请求立刻放行，别让页面白等超时
            backendReady = false

            failAllPending("后端输出管道已关闭")

            // 走到这里说明管道读完：要么后端按 SHUTDOWN 退出，要么被 destroy，要么崩了
            append("Linux Backend 已退出（exitCode=${runCatching { process.exitValue() }.getOrDefault(-1)}）")

            Log.i(TAG, "Linux backend 输出管道已关闭")
        }

        thread.name = "LinuxBackendReader"
        thread.isDaemon = true
        thread.start()

        reader = thread
    }

    /** 追加一行到环形缓冲（线程安全）。 */
    private fun append(line: String) {
        synchronized(lineLock) {
            lines.addLast(line)

            while (lines.size > KEEP_LINES) {
                lines.removeFirst()
            }
        }
    }
}
