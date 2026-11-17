/**
 * ============================================================
 * TaskManager Native Backend —— Linux 服务端（TM-PROTO v2）
 * ============================================================
 *
 * 常驻的请求-应答进程。LinuxService 用 /system/bin/linker64 直接拉起它
 * （不经过 zygote，所以可以按应用自身身份或提权身份运行），
 * 之后双方只通过 stdin / stdout 交换文本行，一行就是一条完整消息。
 *
 * ============================================================
 * 一、启动与握手
 * ============================================================
 * 进程起来后固定先吐两行，客户端见到 READY 才允许发请求：
 *
 *     TaskManager Native Backend proto=2     版本标识，只当日志看
 *     READY                                  就绪，可以发 REQ 了
 *
 * stdin 被关闭，或收到 SHUTDOWN 控制码 → 打印一行 `EXIT stdin closed` 后退出（退出码 0）。
 *
 * ============================================================
 * 二、请求包怎么构成
 * ============================================================
 * 一行一条请求，字段之间用空格分隔，字段个数严格：
 *
 *     REQ <seq> <typeCode> <maskHex> [arg1] [arg2]
 *         seq       正整数，客户端自增；应答原样带回，用来配对请求与应答
 *         typeCode  请求类型，见第三节
 *         maskHex   0x 前缀十六进制字段掩码，只有 typeCode=1 有意义，其余写 0x0
 *         arg1/arg2 只有 typeCode=3（控制）用：控制码 + 它的参数
 *
 * 例：
 *     REQ 7 1 0x5                 查询进程，只要 name 和 pid 两列
 *     REQ 8 2 0x0                 查询服务能力
 *     REQ 9 3 0x0 1 1234          控制：KILL pid=1234
 *     REQ 10 4 0x0                心跳
 *
 * ============================================================
 * 三、五种请求
 * ============================================================
 * typeCode = 1  查询进程快照
 *     参数：maskHex。只输出掩码点到的字段，没点到的字段既不采集也不发送。
 *     maskHex=0x0 视为"全字段"（与客户端 NONE→ALL 的兜底一致）。
 *     maskHex 超出 43 位 → status=BAD_ARGUMENT + I error=mask out of range。
 *
 * typeCode = 2  查询服务能力（客户端自检用）
 *     参数：无。回一组 I 行：proto / fields / fieldMask / encoding /
 *     statusCodes / backend / pid / uid / euid。
 *     两侧协议版本或字段表不同步时，客户端一查就能发现，不必等到读出一堆空列。
 *
 * typeCode = 3  控制，格式 `REQ <seq> 3 0x0 <code> [arg]`
 *     1  KILL <pid> <start_ticks>
 *                     pid 与启动时刻都必给：只凭 pid，目标进程退出后 PID 被复用，
 *                     会杀到另一个进程。校验顺序：
 *                       pid 缺失/≤0，或没给 start_ticks  → BAD_ARGUMENT
 *                       快照里没有这个 pid，或启动时刻不匹配 → PROCESS_EXITED
 *                       都对 → ACCEPTED（本版只回执不真杀，I executed=0）
 *     2  REINIT       清掉采样基准，下一次快照重新建立基准；
 *                     之后 cpu_usage / rss_delta / page_faults_delta 才算得出来。
 *     3  DUMP_STATE   吐内部状态：state / commands / baseline / proto。
 *     4  SHUTDOWN     回执后退出循环，进程结束。
 *     其它 code → status=UNSUPPORTED + I error=unknown control code N。
 *
 * typeCode = 4  心跳
 *     参数：无。回 OK + I pong=1，用来确认"进程还在、管道没断"。
 *
 * typeCode = 5  设备快照（性能页用）
 *     参数：无（掩码写 0x0）。
 *     应答：PKG 的 count = 随后的 I 行条数（这次没有 P 行），然后一组 I 行：
 *       cpu_usage_percent / cpu_speed_mhz / cpu_base_mhz / cpu_name /
 *       virtualization / process_count / thread_count / fd_count /
 *       uptime_seconds / sockets / cores / logical_cpus /
 *       l1_cache_kb / l2_cache_kb / l3_cache_kb
 *     没值的项发 `I key=!状态`（UNSUPPORTED / NOT_AVAILABLE / NO_BASELINE），
 *     客户端按"这一格没数据"处理，绝不当作 0。
 *     cpu_name（处理器商品名）与 virtualization（虚拟化方案）由 ELF 这一侧后续补实现，
 *     现在固定回 UNSUPPORTED，客户端会把这两格显示成占位内容。
 *
 * 未知 typeCode → status=UNSUPPORTED + I error=unsupported request type N。
 *
 * ============================================================
 * 四、应答包怎么构成
 * ============================================================
 *     PKG <seq> 1 <typeCode> <maskHex> <status> <count>
 *     P <pid> k=v k=v …            恰好 count 条
 *     I k=v                        0..n 条附加信息
 *
 *     seq       原样带回请求里的 seq（配对用）
 *     "1"       isFromSvc，恒为 1：这条通道上只有服务端发 PKG
 *     typeCode  原样带回
 *     maskHex   实际生效的掩码；请求里写 0x0 时这里回 0x7FFFFFFFFFF
 *     count     P 行条数（不是字段数、不是字节数）。收满 count 条才算完整包；
 *               信息类 / 控制类应答 count=0
 *
 * status：
 *     0 OK              查询类已采完 / 控制类已执行完
 *     1 UNSUPPORTED     这个能力或类型本版不支持
 *     2 BAD_ARGUMENT    参数不合法（pid、掩码、控制码）
 *     3 INTERNAL        内部错误
 *     4 ACCEPTED        已受理但未执行（KILL 就是这种，客户端不能显示成成功）
 *     5 PROCESS_EXITED  目标进程已经不存在
 *
 * P 行的键值规则：
 *     - 行首 pid 与 start_ticks 是**行身份**，无条件输出、不占 43 位掩码：
 *       PID 会被系统复用，客户端必须两个一起比才认得出"还是不是同一个进程"，
 *       所以即使表格不显示它们，也必须每次都带；
 *     - 之后按字段表顺序输出 `键=值`；
 *     - 值一律百分号编码：空格 %20、换行 %0A、回车 %0D、制表 %09、
 *       % 记 %25、= 记 %3D、! 记 %21；客户端解析后解码。
 *       不编码会把 `cmdline=/x/y 参数` 拆成两半，客户端只拿到半个字段；
 *     - 没有值的字段输出 `键=!状态`：UNSUPPORTED / ACCESS_DENIED /
 *       NOT_AVAILABLE / READ_FAILED / NO_BASELINE / TRUNCATED；
 *     - 绝不拿 0 顶替："GPU 0%" 与"读不到 GPU"是两件事；
 *     - 首轮没有基准时，cpu_usage / rss_delta / page_faults_delta 回 NO_BASELINE。
 *
 * ============================================================
 * 五、字段掩码（43 位，位序与客户端 LinuxField 一一对应）
 * ============================================================
 * 构造 maskHex 时把这些位或起来：
 *
 *     bit0  name                bit1  package_name
 *     bit2  pid                 bit3  state
 *     bit4  user                bit5  session
 *     bit6  pgid                bit7  cgroup
 *     bit8  cpu_usage           bit9  cpu_time
 *     bit10 cpu_cycles          bit11 rss
 *     bit12 peak_rss            bit13 rss_delta
 *     bit14 active_private_rss  bit15 private_rss
 *     bit16 shared_rss          bit17 vss
 *     bit18 page_faults         bit19 page_faults_delta
 *     bit20 nice                bit21 android_priority
 *     bit22 fd_count            bit23 thread_count
 *     bit24 io_read_count       bit25 io_write_count
 *     bit26 io_read_bytes       bit27 io_write_bytes
 *     bit28 exe_path            bit29 cmdline
 *     bit30 selinux             bit31 target_sdk
 *     bit32 platform            bit33 arch
 *     bit34 is_admin            bit35 description
 *     bit36 nx_protect          bit37 gpu_usage
 *     bit38 gpu_engine          bit39 gpu_dedicated_mem
 *     bit40 gpu_shared_mem      bit41 cet_protect
 *     bit42 isolation
 *
 *     全部 43 位 = 0x7FFFFFFFFFF
 *
 * ============================================================
 * 六、异常与边界行为
 * ============================================================
 * - 非 `REQ ` 开头的行：静默忽略，不回任何字节。
 * - 字段不够（缺 seq/type/mask）、seq 为 0 或不是数字、type 或 mask 不是数字：
 *   整行丢弃且不回包。连 seq 都不可信时，回错误包只会给客户端制造无主应答。
 * - type / mask 能解析但不合法：回 BAD_ARGUMENT 或 UNSUPPORTED，因为 seq 可信，
 *   客户端需要知道"这条请求错在哪"。
 * - 一行超过 TM_LINE_MAX（4096 字节）：丢到换行为止，不会被当作两条请求。
 * - 单个值超过 TM_VALUE_MAX（160 字符）：不发半截值，改发 `键=!TRUNCATED`。
 * - stdout 写失败（对端已关管道）：停止循环退出，不空转；SIGPIPE 已忽略。
 *
 * ============================================================
 * 七、当前实现状态
 * ============================================================
 * 采集是**真实实现**：两轮采样（mini 读 /proc/<pid>/stat，full 按掩码补读
 * status / io / smaps_rollup / exe / cmdline / attr / cgroup / maps），
 * 派生值由 compute_derived() 统一结算。
 *
 * 各字段能不能出值：
 *   - 能出：name / pid / state / user / session / pgid / cgroup / cpu_usage / cpu_time /
 *           rss / peak_rss / rss_delta / active_private_rss / private_rss / shared_rss /
 *           vss / page_faults / page_faults_delta / nice / fd_count / thread_count /
 *           io_read_count / io_write_count / io_read_bytes / io_write_bytes /
 *           exe_path / cmdline / selinux / platform / arch / is_admin / nx_protect
 *   - 归 Android 服务填（回 NOT_AVAILABLE）：package_name / target_sdk /
 *     description / android_priority / isolation
 *   - 本版没有（回 UNSUPPORTED）：cpu_cycles（要 perf_event_open）、四个 GPU 字段
 *   - cet_protect：只有 x86 内核的 status 才报 x86_Thread_features，arm64 回 UNSUPPORTED
 *   - 首轮没有基准时 cpu_usage / rss_delta / page_faults_delta 回 NO_BASELINE
 *
 * 权限：提权身份能看到全部进程的全部文件；应用自身身份只能看到自己那几个，
 * /proc/<pid>/io 这类还会被内核判 ACCESS_DENIED——都属于预期，不拿 0 顶替。
 *
 * KILL 仍然只做身份校验（pid + start_ticks 都在最近一次快照里才 ACCEPTED），
 * 不真发信号：测试阶段误杀系统进程代价太大。
 *
 * 设备快照（typeCode=5）按设备集合发送：每个设备一行 D 头（id + type），
 * 后面跟若干行 F 字段。当前实现 CPU（每个插槽一块：利用率 / 频率 / 拓扑 / 三级缓存）、
 * MEMORY（总量与可用量）、DISK / NETWORK / GPU（位置与名字）、SYSTEM（进程线程句柄与开机时长）。
 * 处理器商品名、虚拟化方案、外部缓存容量三个位置留在 query_*_external，现在回 UNSUPPORTED。
 */

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <pwd.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

/** 单行命令的缓冲长度：一条命令最多这么长，超出部分丢弃（防止读到"半行"污染下一条）。 */
#define TM_LINE_MAX 4096

/** 单个字段值的上限：超长值截断后再发，避免一行撑爆客户端解析（F-02）。 */
#define TM_VALUE_MAX 160

// ============================================================
// 协议常量（与客户端 data/LinuxService.kt ① 段必须逐项一致）
//
// 改任何一边都要同步改另一边。QUERY_INFO 会回带 proto / fields / fieldMask /
// encoding，客户端据此核对两侧是不是同一版协议、同一套字段表。
// ============================================================

/**
 * 协议版本。
 *
 * 3：typeCode=5 改为设备集合（D 行 + F 行）。旧版是平铺的 I 行，
 *    客户端两种都能解析，但新版后端只发集合格式。
 */
#define TM_PROTO_VERSION 3

/** 请求类型（LinuxRequestType）。 */
#define TM_REQ_QUERY_PROCESS 1
#define TM_REQ_QUERY_INFO 2
#define TM_REQ_CONTROL 3
#define TM_REQ_PING 4

/**
 * 设备快照：一次抓齐整机各硬件的当前状态，按设备集合发送。
 *
 * 请求 `REQ <seq> 5 0x0`，无附加参数；应答由 D 行（设备头）与 F 行（设备字段）组成，
 * 所以包头的 count = 随后的 D 行与 F 行总条数（不是 0）——客户端按 count 收满才算完整包。
 * 没有某类设备就不发它的 D 行，客户端据此知道这一类型没有实例。
 */
#define TM_REQ_DEVICE_SNAPSHOT 5

/** 设备快照 CPU 利用率的最短采样间隔（毫秒）：两轮 /proc/stat 挨太近，算出来的百分比全是噪声。 */
#define TM_DEVICE_CPU_SAMPLE_MS 200

/** 控制码（LinuxControlCode）。 */
#define TM_CTL_KILL_PROCESS 1
#define TM_CTL_REINIT 2
#define TM_CTL_DUMP_STATE 3
#define TM_CTL_SHUTDOWN 4

/** 应答状态（LinuxPacketStatus）。 */
#define TM_STATUS_OK 0
#define TM_STATUS_UNSUPPORTED 1
#define TM_STATUS_BAD_ARGUMENT 2
#define TM_STATUS_INTERNAL 3
#define TM_STATUS_ACCEPTED 4
#define TM_STATUS_PROCESS_EXITED 5

/** 字段级状态（LinuxFieldStatus）：值不可用时输出 `k=!状态`。 */
#define TM_FIELD_STATUS_UNSUPPORTED "UNSUPPORTED"
#define TM_FIELD_STATUS_ACCESS_DENIED "ACCESS_DENIED"
#define TM_FIELD_STATUS_NOT_AVAILABLE "NOT_AVAILABLE"
#define TM_FIELD_STATUS_READ_FAILED "READ_FAILED"
#define TM_FIELD_STATUS_NO_BASELINE "NO_BASELINE"
#define TM_FIELD_STATUS_TRUNCATED "TRUNCATED"

/**
 * 一次查询的结果状态（**整份 main.c 统一用这一套**）。
 *
 * 为什么单独立一个：sysfs 读不到、/proc 读不到、外部实现没做、这台机器根本没有，
 * 是四种不同的情况。以前用 -1 / false 混着表达，界面上只能显示一个占位符，
 * 排查时分不清是没权限还是没实现。现在每个字段只有两种输出：有值，或者这里的状态。
 *
 * 协议里的字段状态串见 query_status_text()：状态写成 F key=!状态。
 */
typedef enum {
    /** 查到了，值在回填缓冲里有效 */
    TM_QUERY_OK = 0,
    /** 平台或本版后端不支持这项（例如没有 perf_event，或这个接口还没实现） */
    TM_QUERY_UNSUPPORTED,
    /** 去查了但失败：节点打不开、内容解析不出 */
    TM_QUERY_READ_FAILED,
    /** 有这项信息，但当前身份没有权限读（SELinux 或权限不足） */
    TM_QUERY_ACCESS_DENIED,
    /** 这台机器没有这项硬件信息（例如没有 L3 缓存） */
    TM_QUERY_NOT_AVAILABLE,
    /** 需要两轮采样的指标这一轮还没有基准（例如首帧利用率） */
    TM_QUERY_NO_BASELINE,
    /** 值超出缓冲长度，尾部被截断 */
    TM_QUERY_TRUNCATED
} TmQueryStatus;

/** 状态转协议里的字段状态串；TM_QUERY_OK 没有状态串，返回 NULL。 */
static const char *query_status_text(TmQueryStatus status)
{
    switch (status) {
        case TM_QUERY_OK:
            return NULL;

        case TM_QUERY_UNSUPPORTED:
            return TM_FIELD_STATUS_UNSUPPORTED;

        case TM_QUERY_READ_FAILED:
            return TM_FIELD_STATUS_READ_FAILED;

        case TM_QUERY_ACCESS_DENIED:
            return TM_FIELD_STATUS_ACCESS_DENIED;

        case TM_QUERY_NOT_AVAILABLE:
            return TM_FIELD_STATUS_NOT_AVAILABLE;

        case TM_QUERY_NO_BASELINE:
            return TM_FIELD_STATUS_NO_BASELINE;

        case TM_QUERY_TRUNCATED:
            return TM_FIELD_STATUS_TRUNCATED;
    }

    return TM_FIELD_STATUS_NOT_AVAILABLE;
}

/** 状态串转回枚举：老调用点按状态串传参，内部统一成枚举再落地。 */
static TmQueryStatus query_status_of(const char *status)
{
    if (status == NULL) {
        return TM_QUERY_UNSUPPORTED;
    }

    if (strcmp(status, TM_FIELD_STATUS_UNSUPPORTED) == 0) {
        return TM_QUERY_UNSUPPORTED;
    }

    if (strcmp(status, TM_FIELD_STATUS_ACCESS_DENIED) == 0) {
        return TM_QUERY_ACCESS_DENIED;
    }

    if (strcmp(status, TM_FIELD_STATUS_NOT_AVAILABLE) == 0) {
        return TM_QUERY_NOT_AVAILABLE;
    }

    if (strcmp(status, TM_FIELD_STATUS_NO_BASELINE) == 0) {
        return TM_QUERY_NO_BASELINE;
    }

    if (strcmp(status, TM_FIELD_STATUS_TRUNCATED) == 0) {
        return TM_QUERY_TRUNCATED;
    }

    return TM_QUERY_READ_FAILED;
}

/**
 * 字段掩码：一位一个统计项，位表见头部注释第五节。
 *
 * 位序必须与客户端 LinuxField 完全一致（bit0=name …… bit42=isolation），
 * 也与详细信息页 43 列的声明顺序一致，这样"页面要哪几列"可以直接翻译成 maskHex。
 */
#define TM_FIELD_NAME (1ULL << 0)
#define TM_FIELD_PACKAGE_NAME (1ULL << 1)
#define TM_FIELD_PID (1ULL << 2)
#define TM_FIELD_STATE (1ULL << 3)
#define TM_FIELD_USER (1ULL << 4)
#define TM_FIELD_SESSION (1ULL << 5)
#define TM_FIELD_PGID (1ULL << 6)
#define TM_FIELD_CGROUP (1ULL << 7)
#define TM_FIELD_CPU_USAGE (1ULL << 8)
#define TM_FIELD_CPU_TIME (1ULL << 9)
#define TM_FIELD_CPU_CYCLES (1ULL << 10)
#define TM_FIELD_RSS (1ULL << 11)
#define TM_FIELD_PEAK_RSS (1ULL << 12)
#define TM_FIELD_RSS_DELTA (1ULL << 13)
#define TM_FIELD_ACTIVE_PRIVATE_RSS (1ULL << 14)
#define TM_FIELD_PRIVATE_RSS (1ULL << 15)
#define TM_FIELD_SHARED_RSS (1ULL << 16)
#define TM_FIELD_VSS (1ULL << 17)
#define TM_FIELD_PAGE_FAULTS (1ULL << 18)
#define TM_FIELD_PAGE_FAULTS_DELTA (1ULL << 19)
#define TM_FIELD_NICE (1ULL << 20)
#define TM_FIELD_ANDROID_PRIORITY (1ULL << 21)
#define TM_FIELD_FD_COUNT (1ULL << 22)
#define TM_FIELD_THREAD_COUNT (1ULL << 23)
#define TM_FIELD_IO_READ_COUNT (1ULL << 24)
#define TM_FIELD_IO_WRITE_COUNT (1ULL << 25)
#define TM_FIELD_IO_READ_BYTES (1ULL << 26)
#define TM_FIELD_IO_WRITE_BYTES (1ULL << 27)
#define TM_FIELD_EXE_PATH (1ULL << 28)
#define TM_FIELD_CMDLINE (1ULL << 29)
#define TM_FIELD_SELINUX (1ULL << 30)
#define TM_FIELD_TARGET_SDK (1ULL << 31)
#define TM_FIELD_PLATFORM (1ULL << 32)
#define TM_FIELD_ARCH (1ULL << 33)
#define TM_FIELD_IS_ADMIN (1ULL << 34)
#define TM_FIELD_DESCRIPTION (1ULL << 35)
#define TM_FIELD_NX_PROTECT (1ULL << 36)
#define TM_FIELD_GPU_USAGE (1ULL << 37)
#define TM_FIELD_GPU_ENGINE (1ULL << 38)
#define TM_FIELD_GPU_DEDICATED_MEM (1ULL << 39)
#define TM_FIELD_GPU_SHARED_MEM (1ULL << 40)
#define TM_FIELD_CET_PROTECT (1ULL << 41)
#define TM_FIELD_ISOLATION (1ULL << 42)

/** 全部 43 项。 */
#define TM_FIELD_ALL ((1ULL << 43) - 1)

/**
 * 字段表一项：掩码位 + 输出时的键名。
 * 键名必须与客户端 DetailColumnId 的字符串值相同，客户端直接用键映射到表格列。
 */
typedef struct TmFieldDef {
    unsigned long long mask;

    const char *key;
} TmFieldDef;

/** 字段表：顺序即位序；输出时按 mask 过滤，不用逐个 if (mask & XXX)。 */
static const TmFieldDef k_field_table[] = {
    { TM_FIELD_NAME, "name" },
    { TM_FIELD_PACKAGE_NAME, "package_name" },
    { TM_FIELD_PID, "pid" },
    { TM_FIELD_STATE, "state" },
    { TM_FIELD_USER, "user" },
    { TM_FIELD_SESSION, "session" },
    { TM_FIELD_PGID, "pgid" },
    { TM_FIELD_CGROUP, "cgroup" },
    { TM_FIELD_CPU_USAGE, "cpu_usage" },
    { TM_FIELD_CPU_TIME, "cpu_time" },
    { TM_FIELD_CPU_CYCLES, "cpu_cycles" },
    { TM_FIELD_RSS, "rss" },
    { TM_FIELD_PEAK_RSS, "peak_rss" },
    { TM_FIELD_RSS_DELTA, "rss_delta" },
    { TM_FIELD_ACTIVE_PRIVATE_RSS, "active_private_rss" },
    { TM_FIELD_PRIVATE_RSS, "private_rss" },
    { TM_FIELD_SHARED_RSS, "shared_rss" },
    { TM_FIELD_VSS, "vss" },
    { TM_FIELD_PAGE_FAULTS, "page_faults" },
    { TM_FIELD_PAGE_FAULTS_DELTA, "page_faults_delta" },
    { TM_FIELD_NICE, "nice" },
    { TM_FIELD_ANDROID_PRIORITY, "android_priority" },
    { TM_FIELD_FD_COUNT, "fd_count" },
    { TM_FIELD_THREAD_COUNT, "thread_count" },
    { TM_FIELD_IO_READ_COUNT, "io_read_count" },
    { TM_FIELD_IO_WRITE_COUNT, "io_write_count" },
    { TM_FIELD_IO_READ_BYTES, "io_read_bytes" },
    { TM_FIELD_IO_WRITE_BYTES, "io_write_bytes" },
    { TM_FIELD_EXE_PATH, "exe_path" },
    { TM_FIELD_CMDLINE, "cmdline" },
    { TM_FIELD_SELINUX, "selinux" },
    { TM_FIELD_TARGET_SDK, "target_sdk" },
    { TM_FIELD_PLATFORM, "platform" },
    { TM_FIELD_ARCH, "arch" },
    { TM_FIELD_IS_ADMIN, "is_admin" },
    { TM_FIELD_DESCRIPTION, "description" },
    { TM_FIELD_NX_PROTECT, "nx_protect" },
    { TM_FIELD_GPU_USAGE, "gpu_usage" },
    { TM_FIELD_GPU_ENGINE, "gpu_engine" },
    { TM_FIELD_GPU_DEDICATED_MEM, "gpu_dedicated_mem" },
    { TM_FIELD_GPU_SHARED_MEM, "gpu_shared_mem" },
    { TM_FIELD_CET_PROTECT, "cet_protect" },
    { TM_FIELD_ISOLATION, "isolation" },
};

/** 字段表长度（= 43）。 */
static const int k_field_count = (int) (sizeof(k_field_table) / sizeof(k_field_table[0]));

/** 后端运行状态：请求计数、循环开关、采样基准。 */
typedef struct TmBackend {
    /** 已处理的请求条数（DUMP_STATE 与排错用）。 */
    unsigned long command_count;

    /** true = 继续循环；false = 收到退出指令，main() 应结束。 */
    bool running;

    /**
     * 是否已经有过一次完整采样（= 有基准）。
     *
     * 置 false 时，增量 / 比率类字段（CPU 使用率、RSS 增量、缺页增量）回 NO_BASELINE，
     * 因为"没有上一次采样"就算不出差值；拿 0 顶上会让表格显示"这个进程完全不用 CPU"。
     */
    bool baseline_ready;
} TmBackend;

/**
 * 裁掉首尾空白（空格、\t、\r、\n），原地修改并返回有效起点。
 *
 * @param text 可写缓冲区（就地改写，末尾补 '\0'）
 * @return 去掉前导空白后的起点
 */
static char *trim_text(char *text)
{
    if (text == NULL) {
        return text;
    }

    char *begin = text;

    while (*begin != '\0' && isspace((unsigned char) *begin)) {
        begin++;
    }

    char *end = begin + strlen(begin);

    while (end > begin && isspace((unsigned char) end[-1])) {
        end--;
    }

    *end = '\0';

    return begin;
}

/**
 * 从 stdin 读一行（不含换行符）。
 *
 * 超长行的处理：把剩余字节丢到换行为止，返回 true 但内容已被截断。
 * 这样下一轮不会把"半行"当成一条新请求（F-02）。
 *
 * @param buffer   接收缓冲
 * @param capacity 缓冲长度
 * @return true = 读到一行（可能是空行）；false = EOF / 读失败
 */
static bool read_command_line(char *buffer, int capacity)
{
    if (fgets(buffer, capacity, stdin) == NULL) {
        return false;
    }

    size_t length = strlen(buffer);

    if (length > 0 && buffer[length - 1] == '\n') {
        buffer[length - 1] = '\0';

        return true;
    }

    // 没有换行且已到 EOF：最后一行本来就不带换行，正常返回
    if (feof(stdin)) {
        return true;
    }

    // 行超长：把剩下的内容丢到换行为止
    int ch;

    while ((ch = fgetc(stdin)) != EOF && ch != '\n') {
        /* 丢弃 */
    }

    return true;
}

// ============================================================
// v2 请求包：采集 + 组包
//
// 一次快照 = 两轮采样，都在本进程内完成（这是性能最快的地方：不用把 /proc
// 的原始文本跨进程搬一遍）：
//
//   第一轮（mini）：遍历 /proc，每个进程只读一次 /proc/<pid>/stat。
//                   拿到 pid / 名称 / 状态 / 会话 / CPU 累计 / 启动时刻 / RSS / 缺页，
//                   这一轮同时回答两件事："现在有哪些进程"、以及"算增量要哪些基准值"。
//   第二轮（full）：按请求掩码逐进程补读其它文件（status / io / smaps_rollup /
//                   exe / cmdline / attr / cgroup / maps …）。
//                   掩码没点到的字段，一个文件都不读——这是省性能的关键。
//   中间插 compute_derived()：所有"算出来"的字段在一个函数里按顺序结算。
//
// 上一轮的 mini 数据留在 g_baseline 里：CPU 使用率、RSS 增量、缺页增量都要靠它。
// 每轮快照结束时 commit_baseline() 把基准换成"本轮"，
// 两次采样之间退出的进程就在那一刻被清理掉（见该函数说明）。
// ============================================================

/** 一次快照最多收录的进程数（超过就截断，普通系统远达不到）。 */
#define TM_MAX_PROCESSES 8192

/** 进程名上限（内核 comm 固定 16 字节，这里留余量）。 */
#define TM_COMM_MAX 64

/** 单个小文本文件 / 单条路径的读取上限。 */
#define TM_TEXT_MAX 4096

/** 拼 /proc 路径用的缓冲长度。 */
#define TM_PATH_MAX 512

// ---------- 进程范围（详细信息的"全部 / 用户 / 系统"筛选） ----------

/** 不筛：系统里所有可见进程。 */
#define TM_SCOPE_ALL 0

/** 只回用户进程（uid >= TM_UID_APP_START）。 */
#define TM_SCOPE_USER 1

/** 只回系统进程（uid < TM_UID_APP_START）。 */
#define TM_SCOPE_SYSTEM 2

/**
 * Android 给每个应用分配的 uid 起点（AID_APP_START = 10000）。
 *
 * 所以 uid >= 10000 就是"用户装的进程"，其余（root / system / 各种 daemon）算系统进程。
 * 判定只需要一次 stat("/proc/<pid>") 取目录属主，**不读 /proc/<pid>/status** ——
 * 后者是每个进程一整页文本，为了一个 uid 去读它不值得。
 */
#define TM_UID_APP_START 10000L

/**
 * 一个进程在**第一轮（mini）采样**里的基础数据。
 *
 * 全部来自 /proc/<pid>/stat 的**一次读取**，不碰别的文件：
 * 这样"有哪些进程 / 变没变 / CPU 累计多少"的代价是每进程一次 open+read。
 */
typedef struct TmProcStat {
    /** 进程号（= /proc 下的目录名）。 */
    int pid;

    /** 启动时刻（stat 第 22 字段，单位 clock ticks）：与 pid 一起才是进程身份。 */
    long long start_ticks;

    /** 进程短名（stat 第 2 字段 comm）。 */
    char comm[TM_COMM_MAX];

    /** 状态字符（stat 第 3 字段：R / S / D / Z / T / t / I / X）。 */
    char state_char;

    /** 进程组 / 会话（stat 第 5、6 字段）。 */
    long long pgrp;
    long long session;

    /** 线程数（stat 第 20 字段；用它省掉一次 /proc/<pid>/task 目录遍历）。 */
    long long num_threads;

    /** 用户态 / 内核态累计 CPU 时钟节拍（stat 第 14、15 字段）。 */
    long long utime;
    long long stime;

    /** nice 值（stat 第 19 字段，-20 ~ 19）。 */
    long long nice;

    /** 缺页次数（stat 第 10、12 字段）。 */
    long long min_flt;
    long long maj_flt;

    /** 常驻内存页数（stat 第 24 字段）与虚拟内存字节数（stat 第 23 字段）。 */
    long long rss_pages;
    long long vsize_bytes;

    // ---- 以下由 compute_derived() 回填，不是 /proc 原文 ----

    /** CPU 使用率（占整机的百分比）；<0 = 没有上一轮基准。 */
    double cpu_usage_percent;

    /** 累计 CPU 时间（毫秒）。 */
    long long cpu_time_ms;

    /** 常驻内存字节数，以及相对上一轮的变化量。 */
    long long rss_bytes;
    long long rss_delta_bytes;

    /** 缺页总数与相对上一轮的变化量。 */
    long long page_faults;
    long long page_faults_delta;

    /** 本轮是否匹配到了上一轮"同一个进程"（pid 与 start_ticks 都相同）。 */
    bool has_baseline;
} TmProcStat;

/** 一张进程表：本轮快照与上一轮基准共用这个结构。 */
typedef struct TmProcTable {
    TmProcStat *items;
    int count;
    int capacity;
} TmProcTable;

/** 本轮快照（每次查询重建）。 */
static TmProcTable g_snapshot;

/** 上一轮快照 = 下一轮的基准（增量与比率全靠它）。 */
static TmProcTable g_baseline;

/** 上一轮 /proc/stat 的总 CPU jiffies；<0 = 还没有基准。 */
static long long g_cpu_total_prev = -1LL;

/** 页大小与每秒时钟节拍：启动时取一次，避免每个进程都 sysconf 一遍。 */
static long g_page_size = 4096L;
static long g_clock_ticks = 100L;

/** 单进程第二轮用的临时缓冲（进程间复用，逐字段不重复读文件）。 */
static char g_status_text[TM_TEXT_MAX];
static char g_io_text[TM_TEXT_MAX];
static char g_rollup_text[TM_TEXT_MAX];

/** 上面三份缓冲当前属于哪个 pid（-1 = 还没加载）。 */
static int g_extra_pid = -1;

/** 清空一张表（只清条数，容量与内存留着下次用）。 */
static void table_reset(TmProcTable *table)
{
    table->count = 0;
}

/** 往表里追加一条；容量不够时按翻倍扩容。返回 false = 内存不足或已到上限。 */
static bool table_append(TmProcTable *table, const TmProcStat *item)
{
    if (table->count >= TM_MAX_PROCESSES) {
        return false;
    }

    if (table->count >= table->capacity) {

        int next = (table->capacity == 0) ? 256 : table->capacity * 2;

        if (next > TM_MAX_PROCESSES) {
            next = TM_MAX_PROCESSES;
        }

        TmProcStat *grown = (TmProcStat *) realloc(table->items, sizeof(TmProcStat) * (size_t) next);

        if (grown == NULL) {
            return false;
        }

        table->items = grown;
        table->capacity = next;
    }

    table->items[table->count] = *item;
    table->count++;

    return true;
}

/**
 * 在表里按 pid（可带 start_ticks）找一行。返回 NULL = 没有这个进程。
 *
 * start_ticks > 0 时要求它也必须相同：PID 会被系统复用，
 * 只比 pid 会把"另一个人"当成上一轮的那个进程，增量就算到别人头上。
 */
static TmProcStat *table_find(TmProcTable *table, int pid, long long start_ticks)
{
    for (int index = 0; index < table->count; index++) {

        if (table->items[index].pid != pid) {
            continue;
        }

        if (start_ticks > 0 && table->items[index].start_ticks != start_ticks) {
            return NULL;
        }

        return &table->items[index];
    }

    return NULL;
}

/** 目录名是不是纯数字（只认它，/proc/net、/proc/sys 这些都不是进程）。 */
static bool is_pure_digits(const char *text)
{
    if (text == NULL || text[0] == '\0') {
        return false;
    }

    for (const char *cursor = text; *cursor != '\0'; cursor++) {

        if (*cursor < '0' || *cursor > '9') {
            return false;
        }
    }

    return true;
}

/**
 * 读一个文本文件。返回读到的字节数；-1 = 打不开（不存在或权限不足）。
 *
 * 注意：二进制内容（cmdline 的 \0 分隔）也会被原样读进来，
 * 调用方拿到的是"原始字节 + 长度"，不是 C 字符串。
 */
static int read_text_file(const char *path, char *buffer, int capacity)
{
    if (capacity <= 0) {
        return -1;
    }

    FILE *file = fopen(path, "r");

    if (file == NULL) {
        return -1;
    }

    int total = 0;

    while (total < capacity - 1) {

        size_t got = fread(buffer + total, 1, (size_t) (capacity - 1 - total), file);

        if (got == 0) {
            break;
        }

        total += (int) got;
    }

    buffer[total] = '\0';

    fclose(file);

    return total;
}

/** 读符号链接的目标（/proc/<pid>/exe 这类）。返回长度；-1 = 读不到。 */
static int read_link_text(const char *path, char *buffer, int capacity)
{
    if (capacity <= 0) {
        return -1;
    }

    ssize_t got = readlink(path, buffer, (size_t) (capacity - 1));

    if (got < 0) {
        buffer[0] = '\0';

        return -1;
    }

    buffer[got] = '\0';

    return (int) got;
}

/**
 * 在 "键: 值" 形式的文本里找某个键后面的值（取到行尾）。
 *
 * @param text  文本（可为 NULL / 空串）
 * @param key   要匹配的键，含冒号，例如 "VmHWM:"
 * @return true = 找到了（out 里有值）
 */
static bool find_field_value(const char *text, const char *key, char *out, int capacity)
{
    if (text == NULL || text[0] == '\0' || capacity <= 0) {
        return false;
    }

    size_t key_length = strlen(key);
    const char *cursor = text;

    while (*cursor != '\0') {

        if (strncmp(cursor, key, key_length) == 0) {

            const char *value = cursor + key_length;

            while (*value == ' ' || *value == '\t') {
                value++;
            }

            int index = 0;

            while (*value != '\0' && *value != '\n' && index < capacity - 1) {
                out[index++] = *value++;
            }

            out[index] = '\0';

            return index > 0;
        }

        const char *newline = strchr(cursor, '\n');

        if (newline == NULL) {
            break;
        }

        cursor = newline + 1;
    }

    return false;
}

/** 读 /proc/stat 第一行的总 jiffies（user 到 guest 全部相加）；-1 = 读不到。 */
static long long read_cpu_total_jiffies(void)
{
    FILE *file = fopen("/proc/stat", "r");

    if (file == NULL) {
        return -1LL;
    }

    char line[512];

    if (fgets(line, (int) sizeof(line), file) == NULL) {
        fclose(file);

        return -1LL;
    }

    fclose(file);

    if (strncmp(line, "cpu", 3) != 0) {
        return -1LL;
    }

    long long total = 0;
    const char *cursor = line + 3;

    // 前 8 个数字（user / nice / system / idle / iowait / irq / softirq / steal）
    for (int slot = 0; slot < 8; slot++) {

        while (*cursor == ' ' || *cursor == '\t') {
            cursor++;
        }

        if (*cursor == '\0' || *cursor == '\n') {
            break;
        }

        char *end = NULL;

        long long value = strtoll(cursor, &end, 10);

        if (end == cursor) {
            break;
        }

        total += value;
        cursor = end;
    }

    return total;
}

/** 取下一个十进制整数（跳空格），游标前移。取不到就当 0，游标不动。 */
static long long take_long(const char **cursor)
{
    const char *position = *cursor;

    while (*position == ' ' || *position == '\t') {
        position++;
    }

    char *end = NULL;

    long long value = strtoll(position, &end, 10);

    if (end == position) {
        return 0LL;
    }

    *cursor = end;

    return value;
}

/**
 * 解析 /proc/<pid>/stat 一行，填进 [out]。
 *
 * 格式：`pid (comm) state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt
 *        utime stime cutime cstime priority nice threads itrealvalue starttime vsize rss ...`
 *
 * comm 可能含空格甚至 ')'，所以括号按"第一个 '(' 到最后一个 ')'"取。
 *
 * @return false = 这一行不像 stat（进程刚好退出等），调用方跳过它
 */
static bool parse_proc_stat(const char *text, TmProcStat *out)
{
    if (text == NULL || text[0] == '\0') {
        return false;
    }

    const char *name_open = strchr(text, '(');
    const char *name_close = strrchr(text, ')');

    if (name_open == NULL || name_close == NULL || name_close <= name_open) {
        return false;
    }

    memset(out, 0, sizeof(*out));

    out->pid = (int) strtol(text, NULL, 10);

    int name_length = (int) (name_close - name_open - 1);

    if (name_length > TM_COMM_MAX - 1) {
        name_length = TM_COMM_MAX - 1;
    }

    memcpy(out->comm, name_open + 1, (size_t) name_length);
    out->comm[name_length] = '\0';

    const char *cursor = name_close + 1;

    while (*cursor == ' ') {
        cursor++;
    }

    if (*cursor != '\0') {
        out->state_char = *cursor;
        cursor++;
    }

    take_long(&cursor);            // ppid
    out->pgrp = take_long(&cursor);
    out->session = take_long(&cursor);
    take_long(&cursor);            // tty_nr
    take_long(&cursor);            // tpgid
    take_long(&cursor);            // flags
    out->min_flt = take_long(&cursor);
    take_long(&cursor);            // cmin_flt
    out->maj_flt = take_long(&cursor);
    take_long(&cursor);            // cmaj_flt
    out->utime = take_long(&cursor);
    out->stime = take_long(&cursor);
    take_long(&cursor);            // cutime
    take_long(&cursor);            // cstime
    take_long(&cursor);            // priority
    out->nice = take_long(&cursor);
    out->num_threads = take_long(&cursor);
    take_long(&cursor);            // itrealvalue
    out->start_ticks = take_long(&cursor);
    out->vsize_bytes = take_long(&cursor);
    out->rss_pages = take_long(&cursor);

    return out->pid > 0;
}

/** 按 pid 升序排（readdir 顺序是散的，输出按 pid 递增更好读、更好核对）。 */
static int compare_by_pid(const void *left, const void *right)
{
    const TmProcStat *a = (const TmProcStat *) left;
    const TmProcStat *right_stat = (const TmProcStat *) right;

    if (a->pid < right_stat->pid) {
        return -1;
    }

    if (a->pid > right_stat->pid) {
        return 1;
    }

    return 0;
}

/**
 * 一个进程属于哪个范围。
 *
 * @return [TM_SCOPE_USER] / [TM_SCOPE_SYSTEM]；-1 = 目录 stat 不了（刚退出或权限不够），无法判定
 */
static int process_scope_of(int pid)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d", pid);

    struct stat info;

    if (stat(path, &info) != 0) {
        return -1;
    }

    return ((long long) info.st_uid >= TM_UID_APP_START) ? TM_SCOPE_USER : TM_SCOPE_SYSTEM;
}

/**
 * 第一轮（mini）采样：遍历 /proc，每个进程只读一次 stat。
 *
 * @param scope 范围筛选；[TM_SCOPE_ALL] 时连属主 stat 都不做，零额外开销
 * @return 收录到的进程数；0 = /proc 打不开（几乎没有这种情况）
 */
static int collect_snapshot(int scope)
{
    table_reset(&g_snapshot);

    DIR *proc_dir = opendir("/proc");

    if (proc_dir == NULL) {
        return 0;
    }

    struct dirent *entry;

    while ((entry = readdir(proc_dir)) != NULL) {

        if (!is_pure_digits(entry->d_name)) {
            continue;
        }

        int pid = (int) strtol(entry->d_name, NULL, 10);

        // 筛选放在最前面：不合范围的进程连 stat 都不读，
        // 于是"包里的行"和"第二轮要补读的文件"一起省掉了。
        if (scope != TM_SCOPE_ALL && process_scope_of(pid) != scope) {
            continue;
        }

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/stat", pid);

        char text[TM_TEXT_MAX];

        // 读不到 = 这个进程刚退出，或权限不够看它：都不该让整次快照失败
        if (read_text_file(path, text, (int) sizeof(text)) < 0) {
            continue;
        }

        TmProcStat item;

        if (!parse_proc_stat(text, &item)) {
            continue;
        }

        table_append(&g_snapshot, &item);
    }

    closedir(proc_dir);

    if (g_snapshot.count > 1) {
        qsort(g_snapshot.items, (size_t) g_snapshot.count, sizeof(TmProcStat), compare_by_pid);
    }

    return g_snapshot.count;
}

/**
 * 结算所有"算出来"的字段。
 *
 * 故意做成**一个函数、按顺序分区块**，不按字段拆成一堆小函数：
 * 这些派生值共用同样的输入（基准行、整机 CPU 增量、页大小），
 * 拆开只会让参数在函数之间来回搬，读起来更累。
 *
 * @param cur             本轮记录（就地回填）
 * @param base            上一轮同一进程的记录（NULL = 没有基准）
 * @param cpu_total_delta 两轮之间 /proc/stat 总 jiffies 的增量（<=0 = 算不了）
 * @param clock_ticks     每秒时钟节拍（sysconf(_SC_CLK_TCK)）
 */
static void compute_derived(TmProcStat *cur, const TmProcStat *base,
                            long long cpu_total_delta, long clock_ticks)
{
    cur->has_baseline = (base != NULL);

    // ---- 处理 CPU 时间：累计时钟节拍 -> 毫秒 ----
    long long total_ticks = cur->utime + cur->stime;

    cur->cpu_time_ms = (clock_ticks > 0) ? (total_ticks * 1000LL / clock_ticks) : 0LL;

    // ---- 处理 CPU 使用率：本进程累计增量 / 整机累计增量 ----
    cur->cpu_usage_percent = -1.0;

    if (base != NULL && cpu_total_delta > 0) {

        long long before = base->utime + base->stime;
        long long delta = total_ticks - before;

        if (delta < 0) {
            delta = 0;
        }

        cur->cpu_usage_percent = (double) delta * 100.0 / (double) cpu_total_delta;
    }

    // ---- 处理常驻内存：页 -> 字节，再算相对上一轮的变化 ----
    cur->rss_bytes = cur->rss_pages * (long long) g_page_size;

    cur->rss_delta_bytes = 0;

    if (base != NULL) {
        cur->rss_delta_bytes = cur->rss_bytes - base->rss_pages * (long long) g_page_size;
    }

    // ---- 处理缺页：min_flt + maj_flt 为总数，再算增量 ----
    cur->page_faults = cur->min_flt + cur->maj_flt;

    cur->page_faults_delta = 0;

    if (base != NULL) {
        cur->page_faults_delta = cur->page_faults - (base->min_flt + base->maj_flt);
    }
}

/**
 * 把本轮快照提交成下一轮的基准，**同时清理两次采样之间退出的进程**。
 *
 * 为什么是整体替换而不是逐条合并：本轮的进程集合就是事实。
 * 上一轮有、本轮没有的进程一律视为已退出（或 pid 被复用后换了身份），
 * 留着它们只会让增量算到"另一个人"头上，基准表还会越积越大。
 *
 * @param cpu_total_now 本轮读到的整机累计 jiffies（-1 = 没读到，下轮算不了比率）
 */
static void commit_baseline(long long cpu_total_now)
{
    table_reset(&g_baseline);

    for (int index = 0; index < g_snapshot.count; index++) {
        table_append(&g_baseline, &g_snapshot.items[index]);
    }

    g_cpu_total_prev = cpu_total_now;
}

/** 清掉基准（REINIT 用）：下一次快照重新建立，比率类字段会回 NO_BASELINE。 */
static void baseline_clear(void)
{
    table_reset(&g_baseline);

    g_cpu_total_prev = -1LL;
}

/**
 * 百分号编码输出一个值（与客户端 data/LinuxService.kt ① 段的 escapeValue 逐项一致）。
 * 编码表见头部注释第四节；不编码会把含空格的值（cmdline、路径）拆成两半。
 *
 * @param value 原始值（不可为 NULL）
 */
static void print_escaped_value(const char *value)
{
    for (const char *cursor = value; *cursor != '\0'; cursor++) {

        switch (*cursor) {

            case ' ':
                fputs("%20", stdout);
                break;

            case '\n':
                fputs("%0A", stdout);
                break;

            case '\r':
                fputs("%0D", stdout);
                break;

            case '\t':
                fputs("%09", stdout);
                break;

            case '%':
                fputs("%25", stdout);
                break;

            case '=':
                fputs("%3D", stdout);
                break;

            case '!':
                fputs("%21", stdout);
                break;

            default:
                fputc(*cursor, stdout);
                break;
        }
    }
}

/** 打印一条附加信息行 `I 键=值`；值同样要百分号编码，否则空格会把一行拆断。 */
static void print_info_line(const char *key, const char *value)
{
    printf("I %s=", key);

    print_escaped_value(value);

    printf("\n");
}

// ============================================================
// 第二轮（full）：按需读取 / 取值辅助
// ============================================================

/** 状态字符 -> 中文（含义与 ps 的状态字母一致）。 */
static const char *state_text_of(char state)
{
    switch (state) {

        case 'R':
            return "运行";

        case 'S':
            return "休眠";

        case 'D':
            return "不可中断";

        case 'Z':
            return "僵尸";

        case 'T':
            return "已停止";

        case 't':
            return "跟踪停止";

        case 'I':
            return "空闲";

        case 'X':
            return "已退出";

        default:
            return "未知";
    }
}

/** 返回值，同时告诉调用方"这一格有值"。 */
static const char *text_result(const char **status, const char *text)
{
    *status = NULL;

    return text;
}

/** 进程所属 uid：取 /proc/<pid> 目录的所有者，比读 status 少一次文件读取。 */
static bool proc_uid_of(int pid, int *uid)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d", pid);

    struct stat info;

    if (stat(path, &info) != 0) {
        return false;
    }

    *uid = (int) info.st_uid;

    return true;
}

/** uid -> 用户名；查不到返回 NULL（调用方只显示数字 uid）。 */
static const char *user_name_of(int uid)
{
    struct passwd *entry = getpwuid((uid_t) uid);

    if (entry == NULL) {
        return NULL;
    }

    return entry->pw_name;
}

/** 目录里的条目数（fd 数用）；-1 = 打不开。 */
static int count_directory_entries(const char *path)
{
    DIR *dir = opendir(path);

    if (dir == NULL) {
        return -1;
    }

    int count = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }

        count++;
    }

    closedir(dir);

    return count;
}

/**
 * 读 /proc/<pid>/exe 的 ELF 头，得到位数与机器架构。
 *
 * @return false = 读不到（内核线程没有 exe，或权限不足）
 */
static bool elf_info_of(int pid, int *bits, char *arch, int capacity)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d/exe", pid);

    FILE *file = fopen(path, "rb");

    if (file == NULL) {
        return false;
    }

    unsigned char header[20];

    size_t got = fread(header, 1, sizeof(header), file);

    fclose(file);

    if (got < 20 || memcmp(header, "\177ELF", 4) != 0) {
        return false;
    }

    bool little_endian = (header[5] == 1);

    *bits = (header[4] == 2) ? 64 : 32;

    unsigned int machine = little_endian
                           ? (unsigned int) header[18] | ((unsigned int) header[19] << 8)
                           : (unsigned int) header[19] | ((unsigned int) header[18] << 8);

    const char *name = "unknown";

    switch (machine) {

        case 3:
            name = "x86";
            break;

        case 8:
            name = "mips";
            break;

        case 20:
            name = "ppc";
            break;

        case 21:
            name = "ppc64";
            break;

        case 40:
            name = "arm";
            break;

        case 62:
            name = "x86_64";
            break;

        case 183:
            name = "aarch64";
            break;

        case 243:
            name = "riscv";
            break;

        default:
            break;
    }

    snprintf(arch, capacity, "%s", name);

    return true;
}

/**
 * /proc/<pid>/maps 里是否存在"同时可写又可执行"的映射。
 *
 * @return 1 = 有（说明 NX 没真正生效）, 0 = 没有, -1 = 读不到
 */
static int detect_writable_exec(int pid)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d/maps", pid);

    FILE *file = fopen(path, "r");

    if (file == NULL) {
        return -1;
    }

    char line[256];
    int result = 0;

    while (fgets(line, (int) sizeof(line), file) != NULL) {

        // 行格式：地址范围 四位权限 ...
        const char *space = strchr(line, ' ');

        if (space == NULL) {
            continue;
        }

        char perms[8] = {0};
        const char *cursor = space + 1;
        int index = 0;

        while (*cursor != '\0' && *cursor != ' ' && index < 4) {
            perms[index++] = *cursor++;
        }

        perms[index] = '\0';

        if (strchr(perms, 'w') != NULL && strchr(perms, 'x') != NULL) {
            result = 1;
            break;
        }
    }

    fclose(file);

    return result;
}

/** 从 smaps_rollup 文本里取一个 kB 字段；-1 = 没有这一项。 */
static long long smaps_kb_of(const char *text, const char *key)
{
    char value[64];

    if (!find_field_value(text, key, value, (int) sizeof(value))) {
        return -1LL;
    }

    return strtoll(value, NULL, 10);
}

/** 从 /proc/<pid>/io 文本里取一个计数；-1 = 没有这一项。 */
static long long io_value_of(const char *text, const char *key)
{
    char value[64];

    if (!find_field_value(text, key, value, (int) sizeof(value))) {
        return -1LL;
    }

    return strtoll(value, NULL, 10);
}

/**
 * 第二轮采样前的一次性准备：按掩码把"多个字段共用"的文件各读一次。
 *
 * 同一个 pid 内共用，换进程时重读。掩码没点到对应字段时**一个文件都不读**。
 */
static void load_extras(int pid, unsigned long long mask)
{
    if (g_extra_pid == pid) {
        return;
    }

    g_extra_pid = pid;

    g_status_text[0] = '\0';
    g_io_text[0] = '\0';
    g_rollup_text[0] = '\0';

    char path[TM_PATH_MAX];

    // status：峰值内存 VmHWM、CET 的线程特性
    if ((mask & (TM_FIELD_PEAK_RSS | TM_FIELD_CET_PROTECT)) != 0ULL) {

        snprintf(path, sizeof(path), "/proc/%d/status", pid);

        if (read_text_file(path, g_status_text, TM_TEXT_MAX) < 0) {
            g_status_text[0] = '\0';
        }
    }

    // io：四个 I/O 计数
    if ((mask & (TM_FIELD_IO_READ_COUNT | TM_FIELD_IO_WRITE_COUNT |
                 TM_FIELD_IO_READ_BYTES | TM_FIELD_IO_WRITE_BYTES)) != 0ULL) {

        snprintf(path, sizeof(path), "/proc/%d/io", pid);

        if (read_text_file(path, g_io_text, TM_TEXT_MAX) < 0) {
            g_io_text[0] = '\0';
        }
    }

    // smaps_rollup：三个内存细分字段（用汇总文件，不逐个 VMA 扫 smaps）
    if ((mask & (TM_FIELD_ACTIVE_PRIVATE_RSS | TM_FIELD_PRIVATE_RSS |
                 TM_FIELD_SHARED_RSS)) != 0ULL) {

        snprintf(path, sizeof(path), "/proc/%d/smaps_rollup", pid);

        if (read_text_file(path, g_rollup_text, TM_TEXT_MAX) < 0) {
            g_rollup_text[0] = '\0';
        }
    }
}

/**
 * 取某个字段的值（文本形态）。返回 NULL 时用 *out_status 说明为什么没有值。
 *
 * 只有被掩码点到的字段才会走进这里，所以"没点到就不读文件"这个省法成立。
 * 数值字段一律输出**纯数字**（不带单位、不带百分号）：
 * 表格按数值排序，单位写在列名里就够了。
 */
static const char *field_text_of(const TmProcStat *cur, const char *key,
                                 char *buffer, int capacity, const char **out_status)
{
    *out_status = TM_FIELD_STATUS_READ_FAILED;

    // ---------- 第一轮（stat）就已经拿到的字段 ----------

    if (strcmp(key, "name") == 0) {

        // 僵尸进程（state = Z）先单独处理：它已经被杀死了，只是父进程还没回收，
        // pid 与 stat 都还在，而 cmdline 一定是空的（进程的用户空间已释放）。
        // 名字沿用内核线程那套方括号写法（界面上认得出"这是个 kernel 风格的名字"），
        // 再补一句"(僵尸进程)"，说清它为什么杀不死。
        if (cur->state_char == 'Z') {

            if (cur->comm[0] == '\0') {
                *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

                return NULL;
            }

            snprintf(buffer, capacity, "[%s] (僵尸进程)", cur->comm);

            return text_result(out_status, buffer);
        }

        // 为什么不用 stat 里的 comm：内核把 comm 限制在 15 字节（TASK_COMM_LEN - 1），
        // 而 Android 的应用进程名就是完整包名（com.android.taskmanager 有 24 字节），
        // 从 stat 读到的天然是截断值，名称列只会显示半截。
        // cmdline 不截断，所以以 argv[0] 为准；comm 只在没有 cmdline（内核线程）时兜底。
        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/cmdline", cur->pid);

        int length = read_text_file(path, buffer, capacity);

        if (length > 0 && buffer[0] != '\0') {

            // cmdline 用 '\0' 分隔参数，读出来时第一段就是 argv[0]（strrchr 在 C 串上不会越过它）
            const char *slash = strrchr(buffer, '/');

            if (slash != NULL && slash[1] != '\0') {

                // argv[0] 是路径时取最后一段，和 ps 显示内核线程名的习惯一致
                const char *base = slash + 1;
                int base_length = (int) strlen(base);

                memmove(buffer, base, (size_t) base_length);

                buffer[base_length] = '\0';
            }

            return text_result(out_status, buffer);
        }

        if (cur->comm[0] == '\0') {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        // length == 0：文件存在但内容为空 —— 只有内核线程是这样（用户态进程一定有 argv[0]）。
        // 按 ps 的写法包上方括号，让"内核里的东西"和普通进程一眼分得开。
        // length <  0：打不开（权限），不能断定它是不是内核线程，就只给 comm，不乱加括号。
        if (length == 0) {
            snprintf(buffer, capacity, "[%s]", cur->comm);

            return text_result(out_status, buffer);
        }

        return text_result(out_status, cur->comm);
    }

    if (strcmp(key, "pid") == 0) {
        snprintf(buffer, capacity, "%d", cur->pid);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "state") == 0) {
        return text_result(out_status, state_text_of(cur->state_char));
    }

    if (strcmp(key, "session") == 0) {
        snprintf(buffer, capacity, "%lld", cur->session);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "pgid") == 0) {
        snprintf(buffer, capacity, "%lld", cur->pgrp);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "nice") == 0) {
        snprintf(buffer, capacity, "%lld", cur->nice);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "thread_count") == 0) {
        snprintf(buffer, capacity, "%lld", cur->num_threads);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "vss") == 0) {
        snprintf(buffer, capacity, "%lld", cur->vsize_bytes);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "rss") == 0) {
        snprintf(buffer, capacity, "%lld", cur->rss_bytes);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "page_faults") == 0) {
        snprintf(buffer, capacity, "%lld", cur->page_faults);

        return text_result(out_status, buffer);
    }

    // ---------- compute_derived 结算出来的字段 ----------

    if (strcmp(key, "cpu_usage") == 0) {

        if (!cur->has_baseline || cur->cpu_usage_percent < 0.0) {
            *out_status = TM_FIELD_STATUS_NO_BASELINE;

            return NULL;
        }

        // 两位小数：0.01% 的精度，客户端按设置页的"利用率精确度"决定显示几位
        snprintf(buffer, capacity, "%.2f", cur->cpu_usage_percent);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "cpu_time") == 0) {
        snprintf(buffer, capacity, "%.2f", (double) cur->cpu_time_ms / 1000.0);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "rss_delta") == 0) {

        if (!cur->has_baseline) {
            *out_status = TM_FIELD_STATUS_NO_BASELINE;

            return NULL;
        }

        snprintf(buffer, capacity, "%lld", cur->rss_delta_bytes);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "page_faults_delta") == 0) {

        if (!cur->has_baseline) {
            *out_status = TM_FIELD_STATUS_NO_BASELINE;

            return NULL;
        }

        snprintf(buffer, capacity, "%lld", cur->page_faults_delta);

        return text_result(out_status, buffer);
    }

    // ---------- 需要读别的文件才有 ----------

    if (strcmp(key, "user") == 0) {

        int uid = 0;

        if (!proc_uid_of(cur->pid, &uid)) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        const char *name = user_name_of(uid);

        if (name == NULL) {
            snprintf(buffer, capacity, "%d", uid);
        } else {
            snprintf(buffer, capacity, "%s (%d)", name, uid);
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "cgroup") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/cgroup", cur->pid);

        if (read_text_file(path, buffer, capacity) < 0) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        char *newline = strchr(buffer, '\n');

        if (newline != NULL) {
            *newline = '\0';
        }

        // 第一行形如 `层级:控制器:路径`，取第二个冒号之后那段
        char *first = strchr(buffer, ':');
        char *second = (first == NULL) ? NULL : strchr(first + 1, ':');

        if (second == NULL || second[1] == '\0') {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        return text_result(out_status, second + 1);
    }

    if (strcmp(key, "peak_rss") == 0) {

        if (g_status_text[0] == '\0') {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        char raw[64];

        if (!find_field_value(g_status_text, "VmHWM:", raw, (int) sizeof(raw))) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        snprintf(buffer, capacity, "%lld", strtoll(raw, NULL, 10) * 1024LL);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "active_private_rss") == 0 ||
        strcmp(key, "private_rss") == 0 ||
        strcmp(key, "shared_rss") == 0) {

        if (g_rollup_text[0] == '\0') {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        long long private_dirty = smaps_kb_of(g_rollup_text, "Private_Dirty:");
        long long private_clean = smaps_kb_of(g_rollup_text, "Private_Clean:");
        long long shared_dirty = smaps_kb_of(g_rollup_text, "Shared_Dirty:");
        long long shared_clean = smaps_kb_of(g_rollup_text, "Shared_Clean:");

        if (private_dirty < 0 && private_clean < 0 && shared_dirty < 0 && shared_clean < 0) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        long long total_kb = 0;

        if (strcmp(key, "active_private_rss") == 0) {
            // smaps_rollup 没有 Private_Active 这一项：用 Private_Dirty 近似"活跃私有"
            total_kb = (private_dirty < 0) ? 0 : private_dirty;
        } else if (strcmp(key, "private_rss") == 0) {
            total_kb = (private_dirty < 0 ? 0 : private_dirty) + (private_clean < 0 ? 0 : private_clean);
        } else {
            total_kb = (shared_dirty < 0 ? 0 : shared_dirty) + (shared_clean < 0 ? 0 : shared_clean);
        }

        snprintf(buffer, capacity, "%lld", total_kb * 1024LL);

        return text_result(out_status, buffer);
    }

    if (strncmp(key, "io_", 3) == 0) {

        if (g_io_text[0] == '\0') {
            // Android 上普通权限读不到 /proc/<pid>/io（内核只给同 uid / root）
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        const char *io_key = NULL;

        if (strcmp(key, "io_read_count") == 0) {
            io_key = "syscr:";
        } else if (strcmp(key, "io_write_count") == 0) {
            io_key = "syscw:";
        } else if (strcmp(key, "io_read_bytes") == 0) {
            io_key = "read_bytes:";
        } else if (strcmp(key, "io_write_bytes") == 0) {
            io_key = "write_bytes:";
        }

        if (io_key != NULL) {

            long long value = io_value_of(g_io_text, io_key);

            if (value >= 0) {
                snprintf(buffer, capacity, "%lld", value);

                return text_result(out_status, buffer);
            }
        }

        *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

        return NULL;
    }

    if (strcmp(key, "fd_count") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/fd", cur->pid);

        int count = count_directory_entries(path);

        if (count < 0) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        snprintf(buffer, capacity, "%d", count);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "exe_path") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/exe", cur->pid);

        if (read_link_text(path, buffer, capacity) <= 0) {
            // 内核线程没有可执行文件：这是"没有这一项"，不是读取失败
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "cmdline") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/cmdline", cur->pid);

        int length = read_text_file(path, buffer, capacity);

        if (length <= 0) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        // cmdline 的参数之间是 '\0'：换成空格才是一行可读的命令
        for (int index = 0; index < length - 1; index++) {

            if (buffer[index] == '\0') {
                buffer[index] = ' ';
            }
        }

        // 末尾补的 '\0' 与可能残留的空格一起去掉
        buffer[length - 1] = '\0';

        int end = (int) strlen(buffer);

        while (end > 0 && buffer[end - 1] == ' ') {
            buffer[end - 1] = '\0';
            end--;
        }

        if (end == 0) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "selinux") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/attr/current", cur->pid);

        if (read_text_file(path, buffer, capacity) <= 0) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        // 内容可能带结尾换行
        int end = (int) strlen(buffer);

        while (end > 0 && (buffer[end - 1] == '\n' || buffer[end - 1] == '\r')) {
            buffer[end - 1] = '\0';
            end--;
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "platform") == 0 || strcmp(key, "arch") == 0) {

        int bits = 0;
        char arch[32] = {0};

        if (!elf_info_of(cur->pid, &bits, arch, (int) sizeof(arch))) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        if (key[0] == 'p') {
            snprintf(buffer, capacity, "%d 位", bits);
        } else {
            snprintf(buffer, capacity, "%s", arch);
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "is_admin") == 0) {

        int uid = 0;

        if (!proc_uid_of(cur->pid, &uid)) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        return text_result(out_status, uid == 0 ? "是" : "否");
    }

    if (strcmp(key, "nx_protect") == 0) {

        int writable_exec = detect_writable_exec(cur->pid);

        if (writable_exec < 0) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        // 存在"可写又可执行"的映射 = 数据执行保护对这段没生效
        return text_result(out_status, writable_exec == 0 ? "是" : "否");
    }

    if (strcmp(key, "cet_protect") == 0) {

        if (g_status_text[0] == '\0') {
            *out_status = TM_FIELD_STATUS_UNSUPPORTED;

            return NULL;
        }

        char raw[128];

        // 只有 x86 内核会报这一行；arm64 设备上就是"不支持"
        if (!find_field_value(g_status_text, "x86_Thread_features:", raw, (int) sizeof(raw))) {
            *out_status = TM_FIELD_STATUS_UNSUPPORTED;

            return NULL;
        }

        bool shadow_stack = strstr(raw, "shstk") != NULL || strstr(raw, "shadow_stack") != NULL;

        return text_result(out_status, shadow_stack ? "是" : "否");
    }

    // ---------- 本版拿不到 / 归 Android 服务填 ----------

    if (strcmp(key, "package_name") == 0 || strcmp(key, "target_sdk") == 0 ||
        strcmp(key, "description") == 0 || strcmp(key, "android_priority") == 0 ||
        strcmp(key, "isolation") == 0) {

        *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

        return NULL;
    }

    if (strcmp(key, "cpu_cycles") == 0 ||
        strcmp(key, "gpu_usage") == 0 || strcmp(key, "gpu_engine") == 0 ||
        strcmp(key, "gpu_dedicated_mem") == 0 || strcmp(key, "gpu_shared_mem") == 0) {

        *out_status = TM_FIELD_STATUS_UNSUPPORTED;

        return NULL;
    }

    // 字段表里有、这里没实现：明确说"不支持"，绝不拿 0 顶上
    *out_status = TM_FIELD_STATUS_UNSUPPORTED;

    return NULL;
}

/**
 * 打印应答包头 `PKG <seq> 1 <typeCode> <maskHex> <status> <count>`。
 * isFromSvc 恒为 1：这条通道上只有服务端发 PKG。
 *
 * @param count 后面跟的 P 行条数；0 = 没有数据行（信息类 / 控制回执）
 */
static void reply_package_header(unsigned long long seq, int type,
                                 unsigned long long mask, int status, int count)
{
    printf("PKG %llu 1 %d 0x%llx %d %d\n", seq, type, mask, status, count);
}

/**
 * 输出一个进程行 `P <pid> start_ticks=<时刻> k=v k=v …`，只输出被掩码点到的字段。
 *
 * 值一律百分号编码；没值的字段输出 `k=!状态`；超过 TM_VALUE_MAX 标 TRUNCATED。
 * 一个进程一个进程地发：客户端收满 count 行才算一条完整快照。
 */
static void emit_process_row(const TmProcStat *cur, unsigned long long mask)
{
    // 行身份 = pid + start_ticks，两者**无条件输出**（不占 43 位掩码：
    // 掩码决定"表格显示哪几列"，身份是每行都必须有的元数据）。
    // PID 会被系统复用，客户端只比 pid 会认错成另一个进程。
    printf("P %d start_ticks=%lld", cur->pid, cur->start_ticks);

    // 第二轮：先把"多个字段共用"的文件读一份（掩码没点到就不读）
    load_extras(cur->pid, mask);

    char value[TM_TEXT_MAX];

    for (int f = 0; f < k_field_count; f++) {

        if ((mask & k_field_table[f].mask) == 0ULL) {
            continue;
        }

        const char *status = TM_FIELD_STATUS_READ_FAILED;

        const char *text = field_text_of(cur, k_field_table[f].key, value,
                                         (int) sizeof(value), &status);

        if (text == NULL) {
            // 没有值：输出 `k=!状态`，客户端会当成"这一格没值"，不会当成 0
            printf(" %s=!%s", k_field_table[f].key, status);

            continue;
        }

        // 长值截断（cmdline / 路径 / selinux 上下文都可能很长）。
        // 协议里"值"只有两种形态：正常值，或 `!状态`。截断后拿不到完整值，
        // 就明确标 TRUNCATED，绝不把半个值当作完整值发出去。
        if (strlen(text) > TM_VALUE_MAX) {

            printf(" %s=!%s", k_field_table[f].key, TM_FIELD_STATUS_TRUNCATED);

            continue;
        }

        printf(" %s=", k_field_table[f].key);

        print_escaped_value(text);
    }

    printf("\n");
}

/**
 * 处理"查询进程快照"。
 *
 * 顺序：第一轮 mini 采样 -> 结算派生字段 -> 先发包头（条数已定）-> 逐行发 -> 提交基准。
 * 包头必须带着准确条数先发：客户端按 count 判断"包收完了没有"。
 */
static bool handle_query_process(TmBackend *backend, unsigned long long seq,
                                 int type, unsigned long long mask, const char *scope_text)
{
    // 掩码越界（用了第 43 位以上）：这是另一版协议，不能猜，直接回参数错
    if ((mask & ~TM_FIELD_ALL) != 0ULL) {

        reply_package_header(seq, type, mask, TM_STATUS_BAD_ARGUMENT, 0);
        print_info_line("error", "mask out of range (max 43 bits)");

        return true;
    }

    // 掩码为 0：按"全部字段"处理，与 UISvc 里 NONE -> ALL 的兜底保持一致
    if (mask == 0ULL) {
        mask = TM_FIELD_ALL;
    }

    // 第五个字段 = 范围（0 全部 / 1 用户 / 2 系统）。缺省按"全部"，
    // 这样老客户端（不带该字段）行为不变。
    int scope = TM_SCOPE_ALL;

    if (scope_text != NULL && scope_text[0] != '\0') {
        scope = (int) strtol(scope_text, NULL, 10);
    }

    if (scope != TM_SCOPE_ALL && scope != TM_SCOPE_USER && scope != TM_SCOPE_SYSTEM) {
        reply_package_header(seq, type, mask, TM_STATUS_BAD_ARGUMENT, 0);
        print_info_line("error", "bad scope (want 0 = all / 1 = user / 2 = system)");

        return true;
    }

    // 第一轮（mini）：有哪些进程、以及所有算增量要的基准值
    int count = collect_snapshot(scope);

    long long cpu_total_now = read_cpu_total_jiffies();
    long long cpu_total_delta = 0;

    if (g_cpu_total_prev >= 0 && cpu_total_now > g_cpu_total_prev) {
        cpu_total_delta = cpu_total_now - g_cpu_total_prev;
    }

    // 结算：每个进程按"本轮 + 上一轮同一身份"算出派生值
    for (int index = 0; index < g_snapshot.count; index++) {

        TmProcStat *cur = &g_snapshot.items[index];

        // start_ticks 一起匹配：pid 被复用时要当成"另一个人"，不能沿用旧基准
        TmProcStat *base = table_find(&g_baseline, cur->pid, cur->start_ticks);

        compute_derived(cur, base, cpu_total_delta, g_clock_ticks);
    }

    reply_package_header(seq, type, mask, TM_STATUS_OK, count);

    // 回带本轮生效的范围，客户端可据此核对"服务端筛的和我请求的是同一档"
    printf("I scope=%s\n", scope == TM_SCOPE_USER ? "user"
                          : scope == TM_SCOPE_SYSTEM ? "system"
                          : "all");

    // 第二轮（full）：逐进程、按掩码读文件并输出
    for (int index = 0; index < g_snapshot.count; index++) {
        emit_process_row(&g_snapshot.items[index], mask);
    }

    // 本轮即下一轮基准；两次采样之间退出的进程在这里被清理掉
    commit_baseline(cpu_total_now);

    backend->baseline_ready = true;

    return true;
}

// ============================================================
// 设备快照（typeCode=5）
//
// 只采性能页 CPU 页要显示的东西：整机利用率、当前/基准频率、进程/线程/文件描述符数、
// 开机时长、插槽/内核/逻辑处理器数、三级缓存。
//
// 包结构：没有 P 行，全是 I 行，所以包头 count = I 行条数（见头部注释第三节）。
//
// 两格留给 ELF 这一侧补：cpu_name（处理器商品名，要人看得懂，不要内核里的内部型号）
// 与 virtualization（虚拟化方案）。现在回 UNSUPPORTED，客户端按没值处理。
//
// 采集全是只读文件：提权身份能读得更全；cpufreq / cache 节点在部分机型上对应用身份不可读，
// 那就回 !NOT_AVAILABLE，不拿 0 顶替。
// ============================================================

/** 单调毫秒（算两次 CPU 采样隔了多久）。 */
static long long monotonic_ms(void)
{
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return 0LL;
    }

    return (long long) now.tv_sec * 1000LL + (long long) now.tv_nsec / 1000000LL;
}

/** 逻辑 CPU 号上限：/proc/stat 与 sysfs 都按 cpuN 编号，256 足够。 */
#define TM_STAT_CPU_MAX 256

/** 一台芯片最多记多少个逻辑核：8~16 核常见，留 64 够用。 */
#define TM_OUT_LOGICAL_MAX 64

/**
 * 一次 /proc/stat 采样里一行的时间账。
 *
 * total  8 列之和（user nice system idle iowait irq softirq steal）
 * idle   idle + iowait：第 4、5 列
 * kernel system + irq + softirq：在内核态消耗的时间，任务管理器管它叫"内核时间"
 *
 * 内核时间不是独立的一段时间，它本来就包含在 total 里；图表上把它从总利用率里
 * 叠出来用，所以 kernel 必然 <= total - idle。
 */
typedef struct {
    long long total;
    long long idle;
    long long kernel;
} TmCpuTimes;

/** /proc/stat 的一整轮快照：summary 是 cpu 汇总行，cpu[] 按逻辑 CPU 号存放。 */
typedef struct {
    TmCpuTimes summary;
    TmCpuTimes cpu[TM_STAT_CPU_MAX];
    bool seen[TM_STAT_CPU_MAX];
    int max_logical;              /* 见到的最大逻辑 CPU 号 + 1 */
} TmCpuStatTable;

/**
 * 解析一行 cpu 后面的 8 列时间。
 *
 * 第 4 列 idle、第 5 列 iowait 都算空闲；其余列（user nice system irq softirq steal）
 * 都算在干活。guest 那两列是 user 的子集，不能重复加。
 *
 * @return false = 一行里一个数字都没读到
 */
static bool parse_cpu_times(const char *cursor, TmCpuTimes *out)
{
    long long total = 0;
    long long idle = 0;
    long long kernel = 0;

    for (int slot = 0; slot < 8; slot++) {

        while (*cursor == ' ' || *cursor == '\t') {
            cursor++;
        }

        if (*cursor == '\0' || *cursor == '\n') {
            break;
        }

        char *end = NULL;
        long long value = strtoll(cursor, &end, 10);

        if (end == cursor) {
            break;
        }

        total += value;

        // 第 4、5 列（idle / iowait）算空闲
        if (slot == 3 || slot == 4) {
            idle += value;
        }

        // 第 3、6、7 列（system / irq / softirq）算内核时间
        if (slot == 2 || slot == 5 || slot == 6) {
            kernel += value;
        }

        cursor = end;
    }

    if (total <= 0) {
        return false;
    }

    out->total = total;
    out->idle = idle;
    out->kernel = kernel;

    return true;
}

/**
 * 读一整轮 /proc/stat。
 *
 * 为什么要把 cpuN 行也收下来：性能页以后要按芯片画每个逻辑核的利用率，
 * 只读汇总行拿不到。一次读全表，差分时再分别对账。
 *
 * @return false = 文件打不开，或一行都没解析出来
 */
static bool read_cpu_stat_table(TmCpuStatTable *out)
{
    memset(out, 0, sizeof(*out));

    FILE *file = fopen("/proc/stat", "r");

    if (file == NULL) {
        return false;
    }

    char line[512];
    bool any = false;

    while (fgets(line, (int) sizeof(line), file) != NULL) {

        if (strncmp(line, "cpu", 3) != 0) {
            continue;
        }

        TmCpuTimes times;

        // 第 4 个字符是空格 = 汇总行（cpu  123 ...）；否则是 cpuN
        if (line[3] == ' ') {

            if (parse_cpu_times(line + 3, &times)) {

                out->summary = times;
                any = true;
            }

            continue;
        }

        char *end = NULL;
        long logical = strtol(line + 3, &end, 10);

        if (end == line + 3 || logical < 0 || logical >= TM_STAT_CPU_MAX) {
            continue;
        }

        if (!parse_cpu_times(end, &times)) {
            continue;
        }

        out->cpu[logical] = times;
        out->seen[logical] = true;

        if ((int) logical + 1 > out->max_logical) {
            out->max_logical = (int) logical + 1;
        }

        any = true;
    }

    fclose(file);

    return any;
}

/**
 * 两个采样点之间算利用率（%）。
 * @return -1 = 增量不成立（计数器回退、这一核不在两轮里）
 */
static double cpu_percent_between(const TmCpuTimes *later, const TmCpuTimes *before)
{
    if (later->total <= before->total) {
        return -1.0;
    }

    long long delta_total = later->total - before->total;
    long long delta_idle = later->idle - before->idle;

    if (delta_idle < 0) {
        delta_idle = 0;
    }

    if (delta_idle > delta_total) {
        delta_idle = delta_total;
    }

    double percent = (double) (delta_total - delta_idle) * 100.0 / (double) delta_total;

    if (percent < 0.0) {
        percent = 0.0;
    }

    if (percent > 100.0) {
        percent = 100.0;
    }

    return percent;
}

/**
 * 两个采样点之间算内核时间占比（%）。
 *
 * 分母与利用率用同一个 delta_total，所以内核时间永远 <= 总利用率，图表上那条
 * 内核曲线不会冲出总曲线。
 *
 * @return -1 = 这一帧算不出内核时间（增量不成立或计数器回退）
 */
static double cpu_kernel_percent_between(const TmCpuTimes *later, const TmCpuTimes *before)
{
    if (later->total <= before->total) {
        return -1.0;
    }

    long long delta_total = later->total - before->total;
    long long delta_kernel = later->kernel - before->kernel;

    // 计数器回退（热插拔、虚拟机迁移）就当这一帧没有值，不拿 0 顶上
    if (delta_kernel < 0) {
        return -1.0;
    }

    if (delta_kernel > delta_total) {
        delta_kernel = delta_total;
    }

    double percent = (double) delta_kernel * 100.0 / (double) delta_total;

    if (percent > 100.0) {
        percent = 100.0;
    }

    return percent;
}

/**
 * 一帧的 CPU 数据：整机一份，每个逻辑核各一份。
 *
 * percent 是利用率，kernel_percent 是其中在内核态消耗的比例：
 * 两者口径相同（同一个 delta_total 做分母），画在同一张图上时内核那段被叠在底下。
 */
typedef struct {
    double total_percent;                          /* -1 = 这一轮还没有基准 */
    double total_kernel_percent;                   /* -1 = 这一帧没有内核时间 */
    double logical_percent[TM_OUT_LOGICAL_MAX];    /* 按逻辑 CPU 号索引，-1 = 没值 */
    double logical_kernel_percent[TM_OUT_LOGICAL_MAX]; /* 同上，逐核内核时间 */
    int logical_max;                               /* 见到的最大逻辑 CPU 号 + 1 */
} TmCpuUsage;

/**
 * 采一帧 CPU 利用率：整机与每个逻辑核**共用同一对采样点**，不重复睡。
 *
 * 采样点缓存：距上次采样不够 TM_DEVICE_CPU_SAMPLE_MS 才补睡一会儿。
 * 刷新间隔本来就有 0.5~4 秒，正常节奏下一次都不用睡。
 *
 * @return false = 第一次调用（只记了基准，没有对比点），或 /proc/stat 读不到
 */
static bool read_cpu_usage(TmCpuUsage *out)
{
    static TmCpuStatTable prev;
    static bool has_prev = false;
    static long long prev_ms = 0;

    memset(out, 0, sizeof(*out));

    out->total_percent = -1.0;
    out->total_kernel_percent = -1.0;
    out->logical_max = 0;

    for (int i = 0; i < TM_OUT_LOGICAL_MAX; i++) {
        out->logical_percent[i] = -1.0;
        out->logical_kernel_percent[i] = -1.0;
    }

    TmCpuStatTable now;

    if (!read_cpu_stat_table(&now)) {
        return false;
    }

    if (!has_prev) {

        prev = now;
        has_prev = true;
        prev_ms = monotonic_ms();

        return false;
    }

    long long elapsed = monotonic_ms() - prev_ms;

    if (elapsed < TM_DEVICE_CPU_SAMPLE_MS) {
        usleep((useconds_t) ((TM_DEVICE_CPU_SAMPLE_MS - elapsed) * 1000));
    }

    TmCpuStatTable later;

    if (!read_cpu_stat_table(&later)) {
        return false;
    }

    out->total_percent = cpu_percent_between(&later.summary, &prev.summary);

    // 整机内核时间与整机利用率同一对采样点
    out->total_kernel_percent = cpu_kernel_percent_between(&later.summary, &prev.summary);

    int limit = later.max_logical;

    if (limit > TM_OUT_LOGICAL_MAX) {
        limit = TM_OUT_LOGICAL_MAX;
    }

    out->logical_max = limit;

    for (int logical = 0; logical < limit; logical++) {

        // 两轮里都要有这一核：热插拔刚起来的核这一帧先不算
        if (!later.seen[logical] || !prev.seen[logical]) {
            continue;
        }

        out->logical_percent[logical] = cpu_percent_between(&later.cpu[logical],
                                                            &prev.cpu[logical]);

        out->logical_kernel_percent[logical] = cpu_kernel_percent_between(&later.cpu[logical],
                                                                         &prev.cpu[logical]);
    }

    prev = later;
    prev_ms = monotonic_ms();

    return true;
}

/** 读一个只放数字的文件（kHz / 秒 / 个数）。false = 打不开，或开头不是数字。 */
static bool read_first_number(const char *path, double *out)
{
    char text[256];

    if (read_text_file(path, text, (int) sizeof(text)) < 0) {
        return false;
    }

    char *end = NULL;
    double value = strtod(text, &end);

    if (end == text) {
        return false;
    }

    *out = value;

    return true;
}

/** 按候选路径顺序取第一个能读到的数字。 */
static bool read_first_number_of(const char *const *paths, int count, double *out)
{
    for (int index = 0; index < count; index++) {

        if (read_first_number(paths[index], out)) {
            return true;
        }
    }

    return false;
}

/** 当前运行频率（kHz）。 */
static bool read_cpu_cur_freq_khz(double *out)
{
    static const char *const paths[] = {
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq",
    };

    return read_first_number_of(paths, (int) (sizeof(paths) / sizeof(paths[0])), out);
}

/** 基准（最大）频率（kHz）。 */
static bool read_cpu_max_freq_khz(double *out)
{
    static const char *const paths[] = {
        "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq",
    };

    return read_first_number_of(paths, (int) (sizeof(paths) / sizeof(paths[0])), out);
}

/** /sys/devices/system/cpu 下 cpuN 目录的个数 = 逻辑处理器数；-1 = 列不出来。 */
static int count_logical_cpus(void)
{
    DIR *dir = opendir("/sys/devices/system/cpu");

    if (dir == NULL) {
        return -1;
    }

    int count = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        // 只认 cpuN；cpufreq / cpuidle / policyN 这些不是处理器
        if (strncmp(entry->d_name, "cpu", 3) != 0) {
            continue;
        }

        if (!is_pure_digits(entry->d_name + 3)) {
            continue;
        }

        count++;
    }

    closedir(dir);

    return count;
}

/**
 * 数处理器插槽与物理核心。
 *
 * 口径：cpuN/topology 下 physical_package_id 是第几颗芯片（插槽），
 * core_id 是这颗芯片里的第几核。同一颗芯片里 core_id 唯一，所以按
 * 插槽编号 + 核编号这一对去重才是物理核心数 —— 只看 core_id 会把双路机器的两个核算成一个。
 *
 * @param sockets_out 插槽数；一个都没读到给 -1
 * @param cores_out   物理核心数；一个都没读到给 -1
 */
static void count_sockets_and_cores(int *sockets_out, int *cores_out)
{
    *sockets_out = -1;
    *cores_out = -1;

    int logical = count_logical_cpus();

    if (logical <= 0) {
        return;
    }

    char core_keys[256][24];
    int core_key_count = 0;

    int socket_ids[64];
    int socket_id_count = 0;

    for (int cpu = 0; cpu < logical; cpu++) {

        char path[TM_PATH_MAX];

        double package = 0.0;
        bool has_package = false;

        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/topology/physical_package_id", cpu);

        if (read_first_number(path, &package)) {

            has_package = true;

            int id = (int) package;
            bool known = false;

            for (int index = 0; index < socket_id_count; index++) {

                if (socket_ids[index] == id) {
                    known = true;
                    break;
                }
            }

            if (!known && socket_id_count < 64) {
                socket_ids[socket_id_count++] = id;
            }
        }

        double core = 0.0;

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/topology/core_id", cpu);

        if (!read_first_number(path, &core)) {
            continue;
        }

        char key[24];

        snprintf(key, sizeof(key), "%d:%d", has_package ? (int) package : 0, (int) core);

        bool known = false;

        for (int index = 0; index < core_key_count; index++) {

            if (strcmp(core_keys[index], key) == 0) {
                known = true;
                break;
            }
        }

        if (!known && core_key_count < 256) {

            snprintf(core_keys[core_key_count], sizeof(core_keys[core_key_count]), "%s", key);

            core_key_count++;
        }
    }

    if (socket_id_count > 0) {
        *sockets_out = socket_id_count;
    }

    if (core_key_count > 0) {
        *cores_out = core_key_count;
    }
}

/** 解析 32K / 2M / 1024 这种容量文本，统一成 KB；-1 = 不认识。 */
static int parse_size_kb(const char *text)
{
    if (text == NULL || text[0] == '\0') {
        return -1;
    }

    char *end = NULL;
    double value = strtod(text, &end);

    if (end == text) {
        return -1;
    }

    while (*end == ' ' || *end == '\t') {
        end++;
    }

    switch (*end) {

        case 'K':
        case 'k':
            return (int) (value + 0.5);

        case 'M':
        case 'm':
            return (int) (value * 1024.0 + 0.5);

        case 'G':
        case 'g':
            return (int) (value * 1024.0 * 1024.0 + 0.5);

        case '\0':
        case '\n':
        case '\r':
            return (int) (value + 0.5);

        default:
            return -1;
    }
}

/**
 * 【外部实现接口】查某一级缓存的容量。返回值只表示状态，容量从 out_kb 带回。
 *
 * 入参（调用方保证）：
 *   cpu_id      设备序号，和协议里 D 行 id=cpuN 的 N 完全一致，从 0 开始。
 *               一颗芯片（一个插槽）一个序号；单芯片手机永远是 0；双路设备是 0 和 1。
 *   device_path 该实例的采集位置，形如 /sys/devices/system/cpu/cpu0
 *               （拓扑读不到时退化成 cpu0 的目录）。串归调用方所有，只读，别保存指针。
 *   level       1 / 2 / 3，分别对应 L1 / L2 / L3，不会传 0 或负数。
 *
 * 出参（你来填）：
 *   out_kb      该级缓存的总量，单位 KB。L1 若分数据与指令两条，请返回两者之和
 *               （页面上的 L1 缓存是总量口径）。只有返回 TM_QUERY_OK 时这个值才被读取。
 *
 * 返回状态（取值含义见 TmQueryStatus）：
 *   TM_QUERY_OK              查到了，用 out_kb；
 *   TM_QUERY_NOT_AVAILABLE   这台机器确实没有这一级（例如没有 L3），页面显示不支持；
 *   TM_QUERY_UNSUPPORTED     这一版没实现，或平台不提供这项信息；
 *   TM_QUERY_READ_FAILED     去查了但失败（节点打不开、解析不出）；
 *   TM_QUERY_ACCESS_DENIED   有这项信息但当前身份没权限。
 *
 * 调用方约定：先扫这台实例的 cache/index* 算出自己的一份，再调本函数；
 * 本函数返回 OK 才覆盖自己的值，不是 OK 就保留自己的值；
 * 两边都没有，就把本函数的状态发出去（客户端显示占位，不是 0）。
 *
 * 调用频率：与性能页采样一致（最快 0.5 秒一次，每级各调一次）。
 * 如果来源是一次性读取（属性、映射表），建议在函数内部做静态缓存，别每帧重查。
 */
static TmQueryStatus query_cache_kb_external(int cpu_id, const char *device_path, int level,
                                             int *out_kb)
{
    (void) cpu_id;
    (void) device_path;
    (void) level;
    (void) out_kb;

    // TODO(ELF 侧)：查到了返回 TM_QUERY_OK 并把 KB 写进 *out_kb；没有这一级返回 TM_QUERY_NOT_AVAILABLE
    return TM_QUERY_UNSUPPORTED;
}

/**
 * 扫一台处理器实例的 cache/index*，把 L1 / L2 / L3 各自求和（KB）。
 *
 * 为什么一次扫完再分账：三级各扫一遍就是最多 48 次 open，每帧都做一遍没必要，
 * 一次遍历 16 个 index 就够。
 *
 * 三个细节：
 * 1. index 编号读不到就跳过，不 break：多数内核是 0..N 连续，个别内核有空洞，
 *    break 会把后面的级一起丢掉；
 * 2. size 读不到时用 ways × sets × line_size 推算，这三个字段比 size 更常在，
 *    也更容易被 SELinux 放行；
 * 3. 同一级有多条（L1 分数据与指令）就相加，页面上的 L1 缓存是总量。
 *
 * @param cpu_dir 该实例的 sysfs 目录，例如 /sys/devices/system/cpu/cpu0
 * @param out_kb  长度 3，分别接 L1 / L2 / L3；-1 = 这一级没扫到
 */
static void probe_cpu_cache_from_sysfs(const char *cpu_dir, int out_kb[3])
{
    out_kb[0] = -1;
    out_kb[1] = -1;
    out_kb[2] = -1;

    int total[3] = { 0, 0, 0 };

    bool found[3] = { false, false, false };

    for (int index = 0; index < 16; index++) {

        char path[TM_PATH_MAX];
        double level_value = 0.0;

        snprintf(path, sizeof(path), "%s/cache/index%d/level", cpu_dir, index);

        if (!read_first_number(path, &level_value)) {
            continue;
        }

        int level = (int) level_value;

        if (level < 1 || level > 3) {
            continue;
        }

        int size_kb = -1;

        char size_text[64];

        snprintf(path, sizeof(path), "%s/cache/index%d/size", cpu_dir, index);

        if (read_text_file(path, size_text, (int) sizeof(size_text)) >= 0) {

            trim_text(size_text);

            size_kb = parse_size_kb(size_text);
        }

        if (size_kb <= 0) {

            double ways = 0.0;
            double sets = 0.0;
            double line = 0.0;

            snprintf(path, sizeof(path), "%s/cache/index%d/ways_of_associativity", cpu_dir, index);

            bool has_ways = read_first_number(path, &ways);

            snprintf(path, sizeof(path), "%s/cache/index%d/number_of_sets", cpu_dir, index);

            bool has_sets = read_first_number(path, &sets);

            snprintf(path, sizeof(path), "%s/cache/index%d/coherency_line_size", cpu_dir, index);

            bool has_line = read_first_number(path, &line);

            if (has_ways && has_sets && has_line && ways > 0.0 && sets > 0.0 && line > 0.0) {

                size_kb = (int) (ways * sets * line / 1024.0 + 0.5);
            }
        }

        if (size_kb > 0) {

            total[level - 1] += size_kb;

            found[level - 1] = true;
        }
    }

    for (int level = 1; level <= 3; level++) {

        out_kb[level - 1] = found[level - 1] ? total[level - 1] : -1;
    }
}

/** /proc 下的数字目录数 = 进程数（含内核线程）；-1 = 列不出来。 */
static int count_all_processes(void)
{
    DIR *dir = opendir("/proc");

    if (dir == NULL) {
        return -1;
    }

    int count = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        if (is_pure_digits(entry->d_name)) {
            count++;
        }
    }

    closedir(dir);

    return count;
}

/**
 * 全机线程总数：把每个进程的 num_threads 加起来。
 *
 * 不用 /proc/loadavg 的第 4 个数：那个是正在跑的（可运行）任务数，不是线程数。
 *
 * @return -1 = 一个都没读到
 */
static int count_all_threads(void)
{
    DIR *dir = opendir("/proc");

    if (dir == NULL) {
        return -1;
    }

    long long total = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        if (!is_pure_digits(entry->d_name)) {
            continue;
        }

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%s/stat", entry->d_name);

        char text[TM_TEXT_MAX];

        if (read_text_file(path, text, (int) sizeof(text)) < 0) {
            continue;
        }

        TmProcStat stat;

        if (!parse_proc_stat(text, &stat)) {
            continue;
        }

        if (stat.num_threads > 0) {
            total += stat.num_threads;
        }
    }

    closedir(dir);

    return (int) total;
}

/** 全机已分配的文件句柄数：/proc/sys/fs/file-nr 的第一列；-1 = 读不到。 */
static int read_open_fd_count(void)
{
    char text[256];

    if (read_text_file("/proc/sys/fs/file-nr", text, (int) sizeof(text)) < 0) {
        return -1;
    }

    char *end = NULL;
    long value = strtol(text, &end, 10);

    if (end == text || value < 0) {
        return -1;
    }

    return (int) value;
}

/** 开机时长（秒）；-1 = 读不到。 */
static long long read_uptime_seconds(void)
{
    char text[128];

    if (read_text_file("/proc/uptime", text, (int) sizeof(text)) < 0) {
        return -1LL;
    }

    char *end = NULL;
    double value = strtod(text, &end);

    if (end == text || value < 0.0) {
        return -1LL;
    }

    return (long long) value;
}

// ============================================================
// 设备集合模型（typeCode=5）
//
// 旧结构把 CPU / 内存 / 磁盘当成整机唯一的一份平铺字段发送，一台机器两块磁盘、
// 双路处理器就没法表达。现在改成设备集合：每个设备一行头，后面跟着它的字段：
//
//   D id=cpu0 type=CPU
//   F cpu_id=0
//   F usage_percent=12.34
//   F kernel_percent=3.80
//   F speed_mhz=1804
//
// 约定：
// 1. 包头 count = 之后 D 行与 F 行的总条数，客户端按行数收满才算完整包；
// 2. 设备 id 是稳定标识，同类型多个实例靠它区分（cpu0 cpu1 / memory0 / disk0 /
//    network0 / gpu0），序号从 0 开始，一次快照内不变；
// 3. 没有某类设备就不发它的 D 行，客户端看到某类型一条 D 行都没有，
//    就知道这台机器没有该类型的实例（比发 present=false 省一半流量）；
// 4. 字段没有数值时输出 F key=!状态，状态取值与进程行同一套；
// 5. 字段值按百分号编码，与进程行同一套规则。
//
// 采集口径沿用原来那一套：利用率与内核时间都按 /proc/stat 两轮差值算（同一个
// delta_total 做分母），频率读 cpu0 的 cpufreq，缓存扫每台实例自己的 cache/index*，
// 进程线程句柄数扫 /proc。
//
// 字段取值的统一约定（整份文件都遵守，不再有第二种表达方式）：
// 1. 每个字段只有两种形态：有值，或者 F key=!状态；
// 2. 状态取值见 TmQueryStatus：UNSUPPORTED / READ_FAILED / ACCESS_DENIED /
//    NOT_AVAILABLE / NO_BASELINE / TRUNCATED，都是"没有值"，不是 0，也不是空串；
// 3. 处理器商品名 / 虚拟化方案 / 缓存容量三个接口先自己探测一份，再调外部实现，
//    外部返回 TM_QUERY_OK 才覆盖，不是 OK 就用自己的；两边都没有才发状态。
//    三个外部实现的位置见下面的 query_*_external。
// ============================================================

/** 设备类型（字符串直接进协议，客户端按同一批常量匹配）。 */
#define TM_DEV_TYPE_CPU "CPU"
#define TM_DEV_TYPE_MEMORY "MEMORY"
#define TM_DEV_TYPE_DISK "DISK"
#define TM_DEV_TYPE_NETWORK "NETWORK"
#define TM_DEV_TYPE_GPU "GPU"
#define TM_DEV_TYPE_SYSTEM "SYSTEM"

/**
 * 一次快照的设备上限与各类限流。
 *
 * 为什么是 64：Android 的 /sys/class/net 能列出十几个到二十几个接口（rmnet_ 一大家子），
 * 块设备也有若干。上限只给 32 时，磁盘与网卡会把名额吃光，排在最后的 SYSTEM 设备
 * 就整块发不出去，客户端拿不到进程 / 线程 / 句柄 —— 表面症状就是这三项显示占位符。
 * 现在上限抬到 64，磁盘 / 网卡 / GPU 各自限流，SYSTEM 又提前填，三重保证它一定在包里。
 */
#define TM_OUT_DEVICE_MAX 64
#define TM_OUT_FIELD_MAX 20
#define TM_OUT_ID_MAX 32
#define TM_OUT_KEY_MAX 40
#define TM_OUT_VALUE_MAX 128

/** 磁盘 / 网卡 / GPU 各自最多发多少台：再多对页面没有意义，还会挤掉别的设备。 */
#define TM_OUT_DISK_MAX 16
#define TM_OUT_NETWORK_MAX 16
#define TM_OUT_GPU_MAX 4

/** 内存实例上限：/proc/meminfo 只有全局一份，留几个位置给以后的多个内存控制器。 */
#define TM_OUT_MEMORY_MAX 4

/** 设备枚举的分类：只用于找位置，不是协议里的类型字符串。 */
typedef enum {
    TM_DEV_KIND_CPU = 0,
    TM_DEV_KIND_MEMORY,
    TM_DEV_KIND_DISK,
    TM_DEV_KIND_NETWORK,
    TM_DEV_KIND_GPU,
    TM_DEV_KIND_COUNT
} TmDeviceKind;

 /**
  * 一台设备的位置。
  *
  * id   : 发给客户端的设备标识（cpu0 / disk1 ...），一次快照内稳定；
  * path : 采集时读的位置（sysfs 目录或 procfs 文件）；
  * name : 内核里的名字（块名 / 网卡名）；CPU 与内存留空。
  */
typedef struct {
    char id[TM_OUT_ID_MAX];
    char path[TM_PATH_MAX];
    char name[64];
} TmDeviceRef;

/**
 * 一台 CPU 实例 = 一颗芯片（一个插槽）：枚举与采集用这个粒度。
 *
 * 不按核心拆、也不按逻辑处理器拆：核心与逻辑处理器是它内部的数据（logical_ids）。
 * 这只是**采集层**的粒度——输出层会把所有芯片合并成一台 CPU（见 build_cpu_devices），
 * 因为内核把多颗处理器的逻辑核放进了同一个调度池，页面上也只显示一个处理器。
 *
 * id          协议里的设备标识，cpu0 / cpu1
 * device_id   同类型实例序号，与 id 尾巴上的数字一致；采集函数靠它定位
 * package_id  sysfs 的 physical_package_id（读不到时按 0 处理）
 * path        该芯片第一个 cpuN 的 sysfs 目录，缓存 / 频率都从它往下拼
 * logical_ids 这颗芯片包含的逻辑 CPU 号，例如 0,1,2,3
 */
typedef struct {
    char id[TM_OUT_ID_MAX];
    int device_id;
    int package_id;
    char path[TM_PATH_MAX];
    int logical_ids[TM_OUT_LOGICAL_MAX];
    int logical_count;
} TmCpuDevice;

/** 内存 / 磁盘 / 网卡 / GPU 共用的实例结构：这一版只存位置与名字。 */
typedef TmDeviceRef TmPlainDevice;

/**
 * 一次请求用的设备实例表：先枚举，再采集，采集期间不再重枚举。
 *
 * 为什么要这张表：采集函数要能明确"报的是哪一台"。光有一个 cpu_id 数字还不够，
 * 每个函数还得自己再猜一遍它对应哪颗芯片；先把实例枚举成数组，采集函数直接拿
 * 数组元素，里面已经存好 path / package_id / 逻辑核列表，不用重复定位。
 *
 * 五类设备都进包；CPU 在输出层合并成一台，其余四类这一版只发名称。
 */
typedef struct {
    TmCpuDevice cpu[TM_OUT_DEVICE_MAX];
    int cpu_count;

    TmPlainDevice memory[TM_OUT_MEMORY_MAX];
    int memory_count;

    TmPlainDevice disk[TM_OUT_DISK_MAX];
    int disk_count;

    TmPlainDevice network[TM_OUT_NETWORK_MAX];
    int network_count;

    TmPlainDevice gpu[TM_OUT_GPU_MAX];
    int gpu_count;

    unsigned int generation;      /* 拓扑版本：实例集合变了才 +1 */
} TmDeviceRegistry;

/** 一条待发出的字段：值已经格式化好，以感叹号开头表示状态而不是数值。 */
typedef struct {
    char key[TM_OUT_KEY_MAX];
    char value[TM_OUT_VALUE_MAX];
} TmOutField;

/** 一个待发出的设备：一行 D 头 + 若干 F 行。 */
typedef struct {
    char id[TM_OUT_ID_MAX];
    const char *type;
    int field_count;
    TmOutField fields[TM_OUT_FIELD_MAX];
} TmOutDevice;

/** 开一个设备：清掉旧字段，记下 id 与类型。 */
static void device_begin(TmOutDevice *device, const char *id, const char *type)
{
    memset(device, 0, sizeof(*device));

    snprintf(device->id, sizeof(device->id), "%s", id);

    device->type = type;
    device->field_count = 0;
}

/**
 * 一次查询的结果：要么有值，要么是一个状态。
 * text 只在 status == TM_QUERY_OK 时有意义。
 */
typedef struct {
    TmQueryStatus status;
    char text[TM_OUT_VALUE_MAX];
} TmQueryValue;

/** 造一个只有状态的结果（这一项没有值）。 */
static TmQueryValue query_value_none(TmQueryStatus status)
{
    TmQueryValue value;

    memset(&value, 0, sizeof(value));

    value.status = status;

    return value;
}

/** 造一个有文本值的结果；文本为空按 UNSUPPORTED 处理，不发空字段。 */
static TmQueryValue query_value_text(TmQueryStatus status, const char *text)
{
    if (status != TM_QUERY_OK || text == NULL || text[0] == '\0') {
        return query_value_none(status == TM_QUERY_OK ? TM_QUERY_UNSUPPORTED : status);
    }

    TmQueryValue value;

    memset(&value, 0, sizeof(value));

    value.status = TM_QUERY_OK;

    snprintf(value.text, sizeof(value.text), "%s", text);

    return value;
}

/** 造一个整数值的结果：负数按没有值处理（采集函数统一用 -1 表示读不到）。 */
static TmQueryValue query_value_int(long long value, TmQueryStatus missing)
{
    if (value < 0) {
        return query_value_none(missing);
    }

    TmQueryValue result;

    memset(&result, 0, sizeof(result));

    result.status = TM_QUERY_OK;

    snprintf(result.text, sizeof(result.text), "%lld", value);

    return result;
}

/** 造一个浮点值的结果：固定小数位，负数按没有值处理。 */
static TmQueryValue query_value_float(bool has_value, double value, int digits,
                                     TmQueryStatus missing)
{
    if (!has_value) {
        return query_value_none(missing);
    }

    TmQueryValue result;

    memset(&result, 0, sizeof(result));

    result.status = TM_QUERY_OK;

    snprintf(result.text, sizeof(result.text), "%.*f", digits, value);

    return result;
}

/**
 * 所有字段落地的唯一出口：状态在这里转成感叹号标记，有值才写数值。
 *
 * 为什么收成一个出口：字段有十几种来源（sysfs、/proc、外部实现、算法差值），
 * 只要每个来源都能说清自己是 TM_QUERY_OK 还是某种失败，输出就永远只有两种形态。
 * 超过字段上限直接丢，宁可少一格也不越界。
 */
static void device_put_value(TmOutDevice *device, const char *key, TmQueryValue value)
{
    if (device->field_count >= TM_OUT_FIELD_MAX) {
        return;
    }

    TmOutField *field = &device->fields[device->field_count++];

    snprintf(field->key, sizeof(field->key), "%s", key);

    if (value.status != TM_QUERY_OK) {

        const char *status = query_status_text(value.status);

        snprintf(field->value, sizeof(field->value), "!%s",
                 status != NULL ? status : TM_FIELD_STATUS_NOT_AVAILABLE);

        return;
    }

    snprintf(field->value, sizeof(field->value), "%s", value.text);
}

/** 追加一条有值的文本字段。 */
static void device_put(TmOutDevice *device, const char *key, const char *value)
{
    device_put_value(device, key, query_value_text(TM_QUERY_OK, value));
}

/** 整数字段：负数按没有值处理（采集函数统一用 -1 表示读不到）。 */
static void device_put_int(TmOutDevice *device, const char *key, long long value,
                           const char *status)
{
    device_put_value(device, key, query_value_int(value, query_status_of(status)));
}

// ============================================================
// 位置枚举：先把每类设备"在哪"找出来，采集才有地方可读
//
// 每类一个函数，返回条数（0 = 这台机器没有这一类，或 sysfs 不允许读）。
// 同一个函数只负责填 id / path / name，不读任何数据。
// ============================================================

/** 从路径里取最后一段名字：/sys/block/sda -> sda。 */
static void name_from_path(const char *path, char *out, int capacity)
{
    const char *slash = strrchr(path, '/');
    const char *name = (slash != NULL) ? (slash + 1) : path;

    snprintf(out, (size_t) capacity, "%s", name);
}

/**
 * 设备名兜底：这一版只有名称字段，抓不到具体名字时统一写 NAME。
 *
 * 为什么不用空串：空串在协议里是"这个字段有值，但值是空的"，客户端会显示成
 * 一片空白，分不清是没名字还是解析丢了；写 NAME 能明确看出"这个名字没拿到"。
 */
static void ensure_device_name(char *name, int capacity)
{
    if (name[0] == '\0') {
        snprintf(name, (size_t) capacity, "%s", "NAME");
    }
}

/**
 * 这几类接口是内核或厂商造的虚拟网卡，不是硬件，枚举实例时跳过。
 *
 * 为什么要过滤：Android 上 /sys/class/net 里 rmnet_data0 / rmnet_ipa0 这类
 * 接口有十几个，不过滤会把 16 个实例名额占满，真实网卡反而枚举不到。
 */
static bool is_virtual_network_name(const char *name)
{
    static const char *const prefixes[] = {
        "rmnet", "r_rmnet", "rev_rmnet", "dummy", "ifb", "veth", "sit",
        "ip6", "tunl", "gre", "erspan", "p2p", "hwsim", "radio", "clat", "v4-"
    };

    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {

        size_t length = strlen(prefixes[i]);

        if (strncmp(name, prefixes[i], length) == 0) {
            return true;
        }
    }

    return false;
}

/** 块设备里这几类是伪设备（回环 / 内存盘 / 加密映射 / 光驱），不当作真实磁盘。 */
static bool is_pseudo_block_name(const char *name)
{
    static const char *const prefixes[] = { "loop", "ram", "zram", "dm-", "sr" };

    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {

        size_t length = strlen(prefixes[i]);

        if (strncmp(name, prefixes[i], length) == 0) {
            return true;
        }
    }

    return false;
}

 /**
  * 枚举处理器实例：一个 physical_package_id 算一颗芯片（插槽）。
  *
  * 为什么不是按 cpuN 枚举：sysfs 里的 cpuN 是逻辑处理器，几十个核会变成几十个设备，
  * 而客户端要的是"这颗芯片的利用率 / 频率 / 拓扑 / 缓存"。同一颗芯片里的多个核
  * 只记进 cpu_ids 成员列表，不拆成多个设备。
  *
  * @param cpu_ids_out 可选；第 i 个设备的成员逻辑核号列表，形如 0,1,2,3
  * @return 设备条数；拓扑完全读不到时整台机器按一颗芯片处理
  */
 /**
  * 枚举 CPU 设备：一颗芯片一台，同芯片的逻辑核收进 logical_ids。
  *
  * 为什么按 physical_package_id 去重：Android 手机的 8 个核同属一颗芯片，
  * 按核心拆会变成 8 台 CPU 设备，还会把整机利用率重复报 8 次。去重以后
  * 一台设备就是用户理解的一颗处理器；双路设备才有第 2 台。
  *
  * 拓扑读不到时退化：整机当一颗芯片，位置用 cpu0 的目录。
  *
  * @param out      实例数组，由本函数填 id / device_id / package_id / path / logical_ids
  * @param capacity 数组长度
  * @return 实例条数（0 = 这台机器一个核都没有）
  */
static int enumerate_cpu_devices(TmCpuDevice *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    memset(out, 0, sizeof(TmCpuDevice) * (size_t) capacity);

    int logical = count_logical_cpus();

    int package_ids[TM_OUT_DEVICE_MAX];
    int package_count = 0;

    if (logical > 0) {

        for (int cpu = 0; cpu < logical && cpu < TM_STAT_CPU_MAX; cpu++) {

            char path[TM_PATH_MAX];
            double package = 0.0;

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/topology/physical_package_id", cpu);

            if (!read_first_number(path, &package)) {
                continue;
            }

            int package_id = (int) package;
            int slot = -1;

            for (int i = 0; i < package_count; i++) {

                if (package_ids[i] == package_id) {
                    slot = i;
                    break;
                }
            }

            if (slot < 0) {

                if (package_count >= capacity) {
                    break;
                }

                slot = package_count;

                package_ids[slot] = package_id;
                out[slot].package_id = package_id;

                package_count++;
            }

            if (out[slot].logical_count < TM_OUT_LOGICAL_MAX) {
                out[slot].logical_ids[out[slot].logical_count++] = cpu;
            }

            // 位置记这颗芯片第一个出现的 cpuN 目录
            if (out[slot].path[0] == '\0') {

                snprintf(out[slot].path, sizeof(out[slot].path),
                         "/sys/devices/system/cpu/cpu%d", cpu);
            }
        }
    }

    // 拓扑读不到：整机当一颗芯片，位置退到 cpu0
    if (package_count == 0) {

        package_count = 1;

        out[0].package_id = 0;
    }

    for (int i = 0; i < package_count; i++) {

        out[i].device_id = i;

        snprintf(out[i].id, sizeof(out[i].id), "cpu%d", i);

        if (out[i].path[0] == '\0') {

            snprintf(out[i].path, sizeof(out[i].path), "/sys/devices/system/cpu/cpu0");
        }
    }

    return package_count;
}

/**
 * 枚举内存设备：/proc/meminfo 是全局一份，所以固定一台。
 *
 * 名字这一版给 NAME：/proc/meminfo 只有容量与用量，没有型号或厂商字段，
 * 硬编造一个商品名反而误导；等以后有更准的来源再补。
 */
static int enumerate_memory_devices(TmDeviceRef *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    memset(out, 0, sizeof(TmDeviceRef) * (size_t) capacity);

    snprintf(out[0].id, sizeof(out[0].id), "memory0");
    snprintf(out[0].path, sizeof(out[0].path), "/proc/meminfo");

    out[0].name[0] = '\0';

    ensure_device_name(out[0].name, (int) sizeof(out[0].name));

    return 1;
}

/** 枚举块设备：扫 /sys/block，跳过伪设备；name 是块名（sda / sdf ...）。 */
static int enumerate_disk_devices(TmDeviceRef *out, int capacity)
{
    DIR *dir = opendir("/sys/block");

    if (dir == NULL) {
        return 0;
    }

    int count = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        if (entry->d_name[0] == '.') {
            continue;
        }

        if (is_pseudo_block_name(entry->d_name)) {
            continue;
        }

        if (count >= capacity) {
            break;
        }

        snprintf(out[count].id, sizeof(out[count].id), "disk%d", count);

        snprintf(out[count].path, sizeof(out[count].path),
                 "/sys/block/%s", entry->d_name);

        snprintf(out[count].name, sizeof(out[count].name), "%s", entry->d_name);

        ensure_device_name(out[count].name, (int) sizeof(out[count].name));

        count++;
    }

    closedir(dir);

    return count;
}

/** 枚举网卡：扫 /sys/class/net，回环与虚拟接口都不是硬件，跳过。 */
static int enumerate_network_devices(TmDeviceRef *out, int capacity)
{
    DIR *dir = opendir("/sys/class/net");

    if (dir == NULL) {
        return 0;
    }

    int count = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        if (entry->d_name[0] == '.') {
            continue;
        }

        if (strcmp(entry->d_name, "lo") == 0) {
            continue;
        }

        if (is_virtual_network_name(entry->d_name)) {
            continue;
        }

        if (count >= capacity) {
            break;
        }

        snprintf(out[count].id, sizeof(out[count].id), "network%d", count);

        snprintf(out[count].path, sizeof(out[count].path),
                 "/sys/class/net/%s", entry->d_name);

        snprintf(out[count].name, sizeof(out[count].name), "%s", entry->d_name);

        ensure_device_name(out[count].name, (int) sizeof(out[count].name));

        count++;
    }

    closedir(dir);

    return count;
}

/** 枚举图形设备：按几个常见厂商的 sysfs 位置探测，找到哪个用哪个。 */
static int enumerate_gpu_devices(TmDeviceRef *out, int capacity)
{
    static const char *const candidates[] = {
        "/sys/class/kgsl/kgsl-3d0",   // 高通 Adreno
        "/sys/class/misc/mali0",      // ARM Mali
        "/sys/class/drm/card0",       // 通用 DRM 显卡节点
    };

    int count = 0;

    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {

        struct stat info;

        if (stat(candidates[i], &info) != 0) {
            continue;
        }

        if (count >= capacity) {
            break;
        }

        snprintf(out[count].id, sizeof(out[count].id), "gpu%d", count);

        snprintf(out[count].path, sizeof(out[count].path), "%s", candidates[i]);

        name_from_path(candidates[i], out[count].name, (int) sizeof(out[count].name));

        ensure_device_name(out[count].name, (int) sizeof(out[count].name));

        count++;
    }

    return count;
}

/**
 * 枚举全部设备实例，填进这张表。
 *
 * 一次请求只调一次：决定一次采集周期里 DeviceId 与硬件的对应关系，
 * 采集过程中不允许重枚举，否则第 0 台 CPU 可能中途变成另一颗芯片。
 *
 * 非 CPU 的几类这一版只报名称：总量、读写、流量、利用率这些统计字段还没定义，
 * 等定了再从这张表里取值、在 build_named_devices 里补字段。
 *
 * @param registry 输出实例表（调用方一般给 static，避免占栈）
 */
static void registry_refresh(TmDeviceRegistry *registry)
{
    memset(registry, 0, sizeof(*registry));

    registry->cpu_count = enumerate_cpu_devices(registry->cpu, TM_OUT_DEVICE_MAX);
    registry->memory_count = enumerate_memory_devices(registry->memory, TM_OUT_MEMORY_MAX);
    registry->disk_count = enumerate_disk_devices(registry->disk, TM_OUT_DISK_MAX);
    registry->network_count = enumerate_network_devices(registry->network, TM_OUT_NETWORK_MAX);
    registry->gpu_count = enumerate_gpu_devices(registry->gpu, TM_OUT_GPU_MAX);

    // 拓扑版本：实例集合（芯片数、每颗芯片的逻辑核）变了才 +1，热插拔后客户端能察觉
    static unsigned int generation = 0;
    static unsigned int signature = 0;

    unsigned int now_signature = 0;

    now_signature = (unsigned int) registry->cpu_count * 131u
                    + (unsigned int) registry->memory_count;

    now_signature += (unsigned int) registry->disk_count * 17u
                     + (unsigned int) registry->network_count * 7u
                     + (unsigned int) registry->gpu_count * 3u;

    for (int i = 0; i < registry->cpu_count; i++) {

        now_signature = now_signature * 31u + (unsigned int) registry->cpu[i].package_id;
        now_signature = now_signature * 31u + (unsigned int) registry->cpu[i].logical_count;
    }

    if (now_signature != signature) {

        signature = now_signature;
        generation++;
    }

    registry->generation = generation;
}

// ============================================================
// 留给外部实现的三个位置
//
// 三个函数都返回 TmQueryStatus：TM_QUERY_OK 才代表"我有准确值"，
// 其它状态表示没有，客户端显示占位，不会当成 0 或空串。
// 要补实现时直接改函数体，不要改返回值的含义。
// ============================================================

/**
 * 【外部实现接口】处理器商品名。返回值只表示状态，名字从 out 带回。
 *
 * 入参（调用方保证）：
 *   cpu_id      设备序号，和协议里 D 行 id=cpuN 的 N 完全一致，从 0 开始。
 *               一颗芯片（一个插槽）一个序号；单芯片手机永远是 0；双路设备是 0 和 1。
 *               协议里的 cpu_id 字段就是这个值，两边不会对不上。
 *   device_path 该实例的采集位置，形如 /sys/devices/system/cpu/cpu0
 *               （拓扑读不到时退化成 cpu0 的目录）。多芯片时它是这颗芯片第一个
 *               cpuN 目录，可以拿它继续拼子路径，例如 cpu0/cpufreq、cpu0/topology。
 *               串归调用方所有，只读，别改也别保存指针。
 *
 * 出参（你来填）：
 *   out         写 UTF-8 名字的缓冲，要人看得懂的名字（骁龙 8 Gen 3 / Exynos 2400），
 *               不要内核内部型号。
 *   capacity    out 的字节数（含结尾 0）。写满就算截断，调用方按 TM_QUERY_OK 用。
 *
 * 返回状态（取值含义见 TmQueryStatus）：
 *   TM_QUERY_OK              查到了，用 out 里的名字；
 *   TM_QUERY_UNSUPPORTED     这一版没实现，或平台不提供这项信息；
 *   TM_QUERY_READ_FAILED     去查了但失败（节点打不开、内容解析不出）；
 *   TM_QUERY_ACCESS_DENIED   有这项信息但当前身份没权限；
 *   TM_QUERY_NOT_AVAILABLE   这台机器没有这项信息。
 *
 * 调用方约定：先自己从 /proc/cpuinfo 探一份（Hardware / model name），再调本函数；
 * 本函数返回 OK 才覆盖自己的值，不是 OK 就保留自己的值；
 * 两边都没有才把本函数的状态发出去（客户端显示占位，不是空串）。
 */
static TmQueryStatus query_cpu_name_external(int cpu_id, const char *device_path,
                                             char *out, int capacity)
{
    (void) cpu_id;
    (void) device_path;
    (void) out;
    (void) capacity;

    // TODO(ELF 侧)：查到了返回 TM_QUERY_OK 并把名字写进 out；否则返回对应状态
    return TM_QUERY_UNSUPPORTED;
}

/**
 * 【外部实现接口】虚拟化方案。返回值只表示状态，字符串从 out 带回。
 *
 * 入参（调用方保证）：
 *   cpu_id      同上，设备序号（D 行 id=cpuN 的 N）。
 *   device_path 同上，该实例的 sysfs 位置。
 *   cpu_name    刚刚查到的处理器商品名，可能为空串（名字查不到时）。
 *               需要按平台区分虚拟化方案时用它，例如同名 SoC 的衍生型号。
 *               只读，别改，也别保存指针。
 *
 * 出参格式（由你拼好整串，调用方不做任何加工，直接当字段值发出去）：
 *   第一段是基础状态，只能是这三个词之一：已启用 / 已禁用 / 不支持；
 *   第二段是扩展状态，紧跟在基础状态后面、用半角括号括起，可以为空；
 *   扩展状态为空时连括号都不要写，界面据此不显示括号。
 *   例子：
 *     已启用(AMD-V)
 *     已启用(KVM)
 *     已禁用(硬件支持，内核未启用)
 *     不支持
 *
 * 返回状态含义与 query_cpu_name_external 完全一致，同一个 TmQueryStatus。
 *
 * 调用方约定：先自己看 /dev/kvm 与 cpuinfo 的 flags 探一份，再调本函数；
 * 本函数返回 OK 才覆盖，不是 OK 就保留自己的；两边都没有才把状态发出去。
 */
static TmQueryStatus query_virtualization_external(int cpu_id, const char *device_path,
                                                   const char *cpu_name,
                                                   char *out, int capacity)
{
    (void) cpu_id;
    (void) device_path;
    (void) cpu_name;
    (void) out;
    (void) capacity;

    // TODO(ELF 侧)：查到了返回 TM_QUERY_OK 并按上面的格式写 out；否则返回对应状态
    return TM_QUERY_UNSUPPORTED;
}

// ============================================================
// 采集：按设备填字段
// ============================================================

/**
 * 取一行里冒号之后的部分，去掉首尾空白。
 * @return false = 这一行没有冒号，或冒号后面是空的
 */
static bool value_after_colon(const char *line, char *out, int capacity)
{
    const char *colon = strchr(line, ':');

    if (colon == NULL) {
        return false;
    }

    const char *start = colon + 1;

    while (*start == ' ' || *start == '\t') {
        start++;
    }

    snprintf(out, (size_t) capacity, "%s", start);

    int length = (int) strlen(out);

    while (length > 0) {

        char tail = out[length - 1];

        if (tail == '\n' || tail == '\r' || tail == ' ' || tail == '\t') {

            out[length - 1] = '\0';
            length--;

            continue;
        }

        break;
    }

    return length > 0;
}

/**
 * 在 /proc/cpuinfo 的一行里按空白分隔找 token，用于判断 flags 里有没有 vmx / svm / virt。
 * 必须卡边界：直接 strstr 会把 virtualization 里的 virt 也算成扩展支持。
 */
static bool flags_has_token(const char *line, const char *token)
{
    size_t length = strlen(token);

    const char *cursor = line;

    while ((cursor = strstr(cursor, token)) != NULL) {

        bool left_ok = (cursor == line) || (cursor[-1] == ' ' || cursor[-1] == '\t');

        char next = cursor[length];

        bool right_ok = (next == '\0' || next == ' ' || next == '\t'
                         || next == '\n' || next == '\r');

        if (left_ok && right_ok) {
            return true;
        }

        cursor += length;
    }

    return false;
}

/**
 * 内部探测：从 /proc/cpuinfo 取处理器商品名（ARM 用 Hardware，x86 用 model name）。
 *
 * 只读一次并缓存：这个名字开机期间不会变，每帧读 /proc/cpuinfo 没必要。
 * 读不到就返回 UNSUPPORTED，让外部实现去补；不要编造。
 */
static TmQueryValue probe_cpu_name_internal(void)
{
    static bool probed = false;
    static TmQueryValue cached;

    if (probed) {
        return cached;
    }

    probed = true;

    cached = query_value_none(TM_QUERY_UNSUPPORTED);

    FILE *file = fopen("/proc/cpuinfo", "r");

    if (file == NULL) {

        cached = query_value_none(TM_QUERY_READ_FAILED);

        return cached;
    }

    char hardware[TM_OUT_VALUE_MAX];
    char model[TM_OUT_VALUE_MAX];

    hardware[0] = '\0';
    model[0] = '\0';

    char line[256];

    while (fgets(line, sizeof(line), file) != NULL) {

        char value[TM_OUT_VALUE_MAX];

        if (hardware[0] == '\0' && strncmp(line, "Hardware", 8) == 0
            && value_after_colon(line, value, (int) sizeof(value))) {

            snprintf(hardware, sizeof(hardware), "%s", value);

            continue;
        }

        if (model[0] == '\0' && strncmp(line, "model name", 10) == 0
            && value_after_colon(line, value, (int) sizeof(value))) {

            snprintf(model, sizeof(model), "%s", value);
        }
    }

    fclose(file);

    // ARM 的 Hardware 是 SoC 名，比 model name 更贴近处理器商品名
    if (hardware[0] != '\0') {

        cached = query_value_text(TM_QUERY_OK, hardware);

    } else if (model[0] != '\0') {

        cached = query_value_text(TM_QUERY_OK, model);
    }

    return cached;
}

/**
 * 内部探测：虚拟化方案。
 *
 * 判断顺序：
 * 1. /dev/kvm 存在 = 内核已经启用 KVM，报 已启用(KVM)；
 * 2. cpuinfo 的 flags 里有 vmx / svm / virt = 硬件支持，但没看到 /dev/kvm，
 *    报 已禁用(硬件支持，内核未启用)；
 * 3. 都探不到就返回 UNSUPPORTED，交给外部实现判断 —— 不要武断说 不支持。
 */
static TmQueryValue probe_virtualization_internal(void)
{
    static bool probed = false;
    static TmQueryValue cached;

    if (probed) {
        return cached;
    }

    probed = true;

    cached = query_value_none(TM_QUERY_UNSUPPORTED);

    if (access("/dev/kvm", F_OK) == 0) {

        cached = query_value_text(TM_QUERY_OK, "已启用(KVM)");

        return cached;
    }

    FILE *file = fopen("/proc/cpuinfo", "r");

    if (file == NULL) {
        return cached;
    }

    char line[256];

    bool hardware_support = false;

    while (fgets(line, sizeof(line), file) != NULL) {

        if (strncmp(line, "flags", 5) != 0 && strncmp(line, "Features", 8) != 0) {
            continue;
        }

        if (flags_has_token(line, "vmx") || flags_has_token(line, "svm")
            || flags_has_token(line, "virt")) {

            hardware_support = true;

            break;
        }
    }

    fclose(file);

    if (hardware_support) {

        cached = query_value_text(TM_QUERY_OK, "已禁用(硬件支持，内核未启用)");
    }

    return cached;
}

/**
 * 把逻辑核号拼成协议里的 cpu_ids（逗号分隔）。
 *
 * @param ids      逻辑核号数组；顺序由调用方按内核顺序拼好，这里不排序、不打乱
 * @param count    ids 的条数
 * @return false = 一个都没给
 */
static bool format_logical_ids(const int *ids, int count, char *out, int capacity)
{
    if (ids == NULL || count <= 0 || out == NULL || capacity <= 0) {
        return false;
    }

    out[0] = '\0';

    size_t used = 0;

    for (int i = 0; i < count; i++) {

        char item[16];

        if (i == 0) {
            snprintf(item, sizeof(item), "%d", ids[i]);
        } else {
            snprintf(item, sizeof(item), ",%d", ids[i]);
        }

        size_t length = strlen(item);

        if (used + length + 1 >= (size_t) capacity) {
            return used > 0;
        }

        memcpy(out + used, item, length + 1);

        used += length;
    }

    return used > 0;
}

/**
 * 把逐核百分比拼成协议里的一列（logical_usage / logical_kernel 共用）。
 *
 * 顺序与 cpu_ids 严格一一对应：这个列表的第 n 个数就是 cpu_ids 第 n 个核的值。
 * 某一核这一帧没有值（刚热插拔、计数器回退）写 -1.00，占住位置不让后面错位。
 *
 * @param values     按逻辑 CPU 号索引的百分比数组（-1 = 没值）
 * @param max_index  这个数组的有效长度（见到的最大逻辑号 + 1）
 * @param ids        逻辑核号数组（与 cpu_ids 同一份，同一顺序）
 * @param count      ids 的条数
 * @return TM_QUERY_OK = 有值；NOT_AVAILABLE = 一个都没算出来；TRUNCATED = 太长装不下
 */
static TmQueryValue format_logical_percents(const double *values, int max_index,
                                            const int *ids, int count)
{
    if (values == NULL || ids == NULL || count <= 0) {
        return query_value_none(TM_QUERY_NOT_AVAILABLE);
    }

    char text[TM_OUT_VALUE_MAX];

    text[0] = '\0';

    size_t used = 0;
    bool any = false;

    for (int i = 0; i < count; i++) {

        int logical = ids[i];

        double percent = -1.0;

        if (logical >= 0 && logical < max_index && logical < TM_OUT_LOGICAL_MAX) {
            percent = values[logical];
        }

        char item[32];

        if (i == 0) {
            snprintf(item, sizeof(item), "%.2f", percent);
        } else {
            snprintf(item, sizeof(item), ",%.2f", percent);
        }

        size_t length = strlen(item);

        if (used + length + 1 >= sizeof(text)) {
            return query_value_none(TM_QUERY_TRUNCATED);
        }

        memcpy(text + used, item, length + 1);

        used += length;

        if (percent >= 0.0) {
            any = true;
        }
    }

    if (!any) {
        return query_value_none(TM_QUERY_NOT_AVAILABLE);
    }

    return query_value_text(TM_QUERY_OK, text);
}

 /**
  * 填 CPU 设备：永远只填一台，把所有枚举到的物理处理器合并成这一台。
  *
  * 为什么合并：内核把多颗处理器的逻辑核心放进了同一个调度池，任务管理器也只显示
  * 一个处理器；而 /proc/stat 的汇总行本身就是整机口径，按芯片拆不出单颗的利用率。
  * 分成多台只会逼着 UI 为每颗芯片各画一套视图，数据却还是同一份整机的。
  *
  * 合并规则逐字段不同，不要按同一套想：
  *   usage_percent   整机汇总行，本来就是各处理器一起算的，直接用
  *   kernel_percent  内核时间占这一帧的比例，与 usage_percent 同一口径
  *   speed_mhz       不相加：多颗处理器通常同频，取 cpu0 那一份
  *   base_mhz        同上
  *   name            全部同名只写一个；不同名按 "名1 名2" 拼，缺名的写 NAME
  *   virtualization  只问第一颗（主处理器）
  *   sockets         物理处理器实例数（枚举到几颗就是几，不是核心数）
  *   cores           各芯片物理核心总数（内核的插槽 + 核号去重口径）
  *   logical_cpus    合并后的逻辑核总数
  *   l1/l2/l3_cache  各芯片相加（两颗各 1 KB → 2 KB）
  *   cpu_ids         按内核顺序拼：第 0 颗的核在前、第 1 颗的在后，不打乱
  *   logical_usage   逐核利用率，与 cpu_ids 一一对应，同一个顺序
  *   logical_kernel  逐核内核时间，与 cpu_ids 一一对应，同一个顺序
  *
  * 名字 / 虚拟化 / 缓存仍是"自己探测优先，外部返回 OK 才覆盖"，多颗时逐颗问一遍。
  *
  * @param registry 已枚举好的实例表（内部仍按芯片存，合并只发生在输出这一层）
  * @param usage    这一帧的利用率（整机 + 每逻辑核）
  * @return 1 = 填了一台；0 = 没有 CPU 实例或容量不足
  */
static int build_cpu_devices(TmOutDevice *out, int capacity,
                             const TmDeviceRegistry *registry, const TmCpuUsage *usage)
{
    if (out == NULL || registry == NULL || usage == NULL || capacity <= 0
        || registry->cpu_count <= 0) {
        return 0;
    }

    // 先把所有芯片的逻辑核号按内核顺序拼成一份：第 0 颗的在前，第 1 颗的在后
    int merged_ids[TM_OUT_LOGICAL_MAX];

    int merged_count = 0;

    for (int i = 0; i < registry->cpu_count; i++) {

        const TmCpuDevice *device = &registry->cpu[i];

        for (int j = 0; j < device->logical_count; j++) {

            if (merged_count >= TM_OUT_LOGICAL_MAX) {
                break;
            }

            merged_ids[merged_count++] = device->logical_ids[j];
        }
    }

    device_begin(&out[0], "cpu0", TM_DEV_TYPE_CPU);

    // 单实例：这一类只有这一台，序号恒 0
    device_put(&out[0], "cpu_id", "0");

    device_put_value(&out[0], "usage_percent",
                     query_value_float(usage->total_percent >= 0.0,
                                       usage->total_percent, 2,
                                       TM_QUERY_NO_BASELINE));

    // 内核时间：与利用率同一口径（system + irq + softirq 占这一帧的比例）
    device_put_value(&out[0], "kernel_percent",
                     query_value_float(usage->total_kernel_percent >= 0.0,
                                       usage->total_kernel_percent, 2,
                                       TM_QUERY_NO_BASELINE));

    // 频率取第一颗处理器（cpu0 的 cpufreq）；多颗通常同频，不相加
    double cur_khz = 0.0;

    bool has_cur_khz = read_cpu_cur_freq_khz(&cur_khz);

    double max_khz = 0.0;

    bool has_max_khz = read_cpu_max_freq_khz(&max_khz);

    device_put_int(&out[0], "speed_mhz",
                   has_cur_khz ? (long long) (cur_khz / 1000.0 + 0.5) : -1,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "base_mhz",
                   has_max_khz ? (long long) (max_khz / 1000.0 + 0.5) : -1,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    // 名字：逐颗求最终值（外部优先，内部探测兜底），再按"同名只留一个 / 异名拼接"汇总
    TmQueryValue internal_name = probe_cpu_name_internal();

    char primary_name[TM_OUT_VALUE_MAX];

    primary_name[0] = '\0';

    char name_text[TM_OUT_VALUE_MAX];

    name_text[0] = '\0';

    size_t name_used = 0;

    TmQueryStatus name_status = TM_QUERY_UNSUPPORTED;

    bool name_overflow = false;

    for (int i = 0; i < registry->cpu_count && !name_overflow; i++) {

        const TmCpuDevice *device = &registry->cpu[i];

        char one_name[TM_OUT_VALUE_MAX];

        one_name[0] = '\0';

        TmQueryStatus one_status = query_cpu_name_external(device->device_id, device->path,
                                                           one_name, (int) sizeof(one_name));

        bool have_one = false;

        if (one_status == TM_QUERY_OK && one_name[0] != '\0') {

            have_one = true;

            name_status = TM_QUERY_OK;

        } else if (internal_name.status == TM_QUERY_OK) {

            snprintf(one_name, sizeof(one_name), "%s", internal_name.text);

            have_one = true;

            name_status = TM_QUERY_OK;
        }

        // 缺名用 NAME 占位：一颗有名一颗没名，也要能看出它们不一样
        if (!have_one) {
            snprintf(one_name, sizeof(one_name), "%s", "NAME");
        }

        if (i == 0) {

            snprintf(primary_name, sizeof(primary_name), "%s", one_name);

            snprintf(name_text, sizeof(name_text), "%s", one_name);

            name_used = strlen(name_text);

            continue;
        }

        // 与第一颗的名字完全相同就只留一个
        if (strcmp(one_name, name_text) == 0) {
            continue;
        }

        if (name_used + 1 + strlen(one_name) + 1 > sizeof(name_text)) {

            name_overflow = true;

            break;
        }

        name_text[name_used++] = ' ';

        snprintf(name_text + name_used, sizeof(name_text) - name_used, "%s", one_name);

        name_used = strlen(name_text);
    }

    if (name_overflow) {

        device_put_value(&out[0], "name", query_value_none(TM_QUERY_TRUNCATED));

    } else if (name_status == TM_QUERY_OK) {

        device_put_value(&out[0], "name", query_value_text(TM_QUERY_OK, name_text));

    } else {

        device_put_value(&out[0], "name", query_value_none(name_status));
    }

    // 虚拟化只问第一颗（主处理器）：多颗的方案一般一致，各问一遍没有意义
    const TmCpuDevice *primary = &registry->cpu[0];

    TmQueryValue virtualization_value = probe_virtualization_internal();

    char external_virtualization[TM_OUT_VALUE_MAX];

    external_virtualization[0] = '\0';

    TmQueryStatus virtualization_status = query_virtualization_external(
        primary->device_id, primary->path, primary_name,
        external_virtualization, (int) sizeof(external_virtualization));

    if (virtualization_status == TM_QUERY_OK && external_virtualization[0] != '\0') {

        virtualization_value = query_value_text(TM_QUERY_OK, external_virtualization);

    } else if (virtualization_value.status != TM_QUERY_OK) {

        virtualization_value = query_value_none(virtualization_status);
    }

    device_put_value(&out[0], "virtualization", virtualization_value);

    // 插槽数 = 枚举到的物理处理器实例数；cores 走内核的"插槽 + 核号"去重口径
    int sockets = registry->cpu_count;

    int cores = -1;

    int socket_count_ignored = -1;

    count_sockets_and_cores(&socket_count_ignored, &cores);

    int logical_cpus = count_logical_cpus();

    device_put_int(&out[0], "sockets", sockets, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "cores", cores, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "logical_cpus", logical_cpus, TM_FIELD_STATUS_NOT_AVAILABLE);

    // 缓存逐颗求再相加：两颗各 1 KB → 2 KB。某颗某级没值就不算它
    long long cache_sum[3] = { 0, 0, 0 };

    int cache_has[3] = { 0, 0, 0 };

    TmQueryStatus cache_status[3] = {
        TM_QUERY_NOT_AVAILABLE, TM_QUERY_NOT_AVAILABLE, TM_QUERY_NOT_AVAILABLE
    };

    for (int i = 0; i < registry->cpu_count; i++) {

        const TmCpuDevice *device = &registry->cpu[i];

        int cache_kb[3] = { -1, -1, -1 };

        probe_cpu_cache_from_sysfs(device->path, cache_kb);

        for (int level = 1; level <= 3; level++) {

            int external_kb = -1;

            TmQueryStatus status = query_cache_kb_external(device->device_id, device->path,
                                                           level, &external_kb);

            // 外部 OK 才覆盖自己扫到的
            if (status == TM_QUERY_OK && external_kb > 0) {
                cache_kb[level - 1] = external_kb;
            }

            if (cache_kb[level - 1] >= 0) {

                cache_sum[level - 1] += cache_kb[level - 1];

                cache_has[level - 1] = 1;

                cache_status[level - 1] = TM_QUERY_OK;

            } else if (i == 0 && cache_status[level - 1] != TM_QUERY_OK) {

                cache_status[level - 1] = status;
            }
        }
    }

    static const char *const cache_keys[3] = {
        "l1_cache_kb", "l2_cache_kb", "l3_cache_kb"
    };

    for (int level = 1; level <= 3; level++) {

        if (cache_has[level - 1]) {

            device_put_value(&out[0], cache_keys[level - 1],
                             query_value_int(cache_sum[level - 1], TM_QUERY_NOT_AVAILABLE));

            continue;
        }

        device_put_value(&out[0], cache_keys[level - 1],
                         query_value_none(cache_status[level - 1]));
    }

    // 合并后的逻辑核号，例如 0,1,2,3,4,5,6,7
    char member_text[TM_OUT_VALUE_MAX];

    if (format_logical_ids(merged_ids, merged_count, member_text, (int) sizeof(member_text))) {
        device_put(&out[0], "cpu_ids", member_text);
    }

    // 逐核利用率：顺序与 cpu_ids 一一对应
    device_put_value(&out[0], "logical_usage",
                     format_logical_percents(usage->logical_percent, usage->logical_max,
                                             merged_ids, merged_count));

    // 逐核内核时间：与 logical_usage 同一份核号、同一个顺序
    device_put_value(&out[0], "logical_kernel",
                     format_logical_percents(usage->logical_kernel_percent,
                                             usage->logical_max, merged_ids, merged_count));

    return 1;
}

/**
 * 把一类"这一版只报名称"的设备实例填进包。
 *
 * 为什么只发 name：内存总量、磁盘读写、网卡流量、GPU 利用率这些统计口径
 * 还没定，先发一个没有含义的 0 比不发更糟；先把实例与名称报出去，
 * 上层就能先看到"这台机器有几块盘、几张网卡"，等统计字段定了一处补一列即可。
 *
 * @param devices 实例数组（registry 里的 memory / disk / network / gpu）
 * @param count   实例条数
 * @param type    协议里的设备类型串（MEMORY / DISK / NETWORK / GPU）
 * @return 填了几台（受 capacity 限制）
 */
static int build_named_devices(TmOutDevice *out, int capacity,
                               const TmDeviceRef *devices, int count, const char *type)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    int limit = count;

    if (limit > capacity) {
        limit = capacity;
    }

    for (int i = 0; i < limit; i++) {

        device_begin(&out[i], devices[i].id, type);

        // 枚举阶段已经用 NAME 兜过底，这里一定有值
        device_put(&out[i], "name", devices[i].name);
    }

    return limit;
}

/** 填系统信息：进程 / 线程 / 句柄 / 开机时长（不属于任何一类硬件）。 */
static int build_system_device(TmOutDevice *out, int capacity)
{
    if (capacity <= 0) {
        return 0;
    }

    device_begin(&out[0], "system", TM_DEV_TYPE_SYSTEM);

    device_put_int(&out[0], "process_count", count_all_processes(),
                   TM_FIELD_STATUS_READ_FAILED);

    device_put_int(&out[0], "thread_count", count_all_threads(),
                   TM_FIELD_STATUS_READ_FAILED);

    device_put_int(&out[0], "fd_count", read_open_fd_count(),
                   TM_FIELD_STATUS_READ_FAILED);

    device_put_int(&out[0], "uptime_seconds", read_uptime_seconds(),
                   TM_FIELD_STATUS_READ_FAILED);

    return 1;
}

/** 发一个设备：一行 D 头 + 若干 F 行；字段值走同一套百分号编码。 */
static void emit_device_block(const TmOutDevice *device)
{
    printf("D id=%s type=%s\n", device->id, device->type);

    for (int i = 0; i < device->field_count; i++) {

        const TmOutField *field = &device->fields[i];

        // 感叹号开头是状态标记，原样发，不做编码
        if (field->value[0] == '!') {

            printf("F %s=%s\n", field->key, field->value);

            continue;
        }

        printf("F %s=", field->key);

        print_escaped_value(field->value);

        printf("\n");
    }
}

 /**
  * 处理 typeCode=5：抓一次设备快照，按设备集合发出。
  *
  * 顺序：先把各类设备加系统信息全部填进缓冲 -> 数清 D 行与 F 行的总条数 ->
  * 发包头 -> 逐块发。条数必须先定：客户端按 count 收满才算完整包。
  *
  * 包内容（有实例才发，没有的类型一条 D 行都不发）：
  *   CPU     : 永远一块，把所有物理处理器合并；含利用率 / 频率 / 拓扑 / 缓存 / 逐核利用率
  *   MEMORY  : 每个内存设备一块，这一版只有名称
  *   DISK    : 每块磁盘一块，这一版只有块名
  *   NETWORK : 每个网卡一块，这一版只有接口名
  *   GPU     : 每个图形设备一块，这一版只有节点名
  *   SYSTEM  : 进程 / 线程 / 句柄 / 开机时长
  */
static bool handle_device_snapshot(unsigned long long seq, int type)
{
    // 这条通道是单线程顺序处理，缓冲用 static：不占栈，也不用每次清整块
    static TmOutDevice devices[TM_OUT_DEVICE_MAX];
    static TmDeviceRegistry registry;
    static TmCpuUsage usage;

    // 先枚举实例，再采集：一次请求只枚举一次，采集期间 DeviceId 不会漂移
    registry_refresh(&registry);

    // 利用率整机与每逻辑核共用一对采样点；首帧没有对比点，字段会按 NO_BASELINE 发
    read_cpu_usage(&usage);

    int device_count = 0;

    device_count += build_cpu_devices(devices + device_count,
                                      TM_OUT_DEVICE_MAX - device_count,
                                      &registry, &usage);

    // 内存：这一版只报名称（总量 / 可用量等口径还没定）
    device_count += build_named_devices(devices + device_count,
                                        TM_OUT_DEVICE_MAX - device_count,
                                        registry.memory, registry.memory_count,
                                        TM_DEV_TYPE_MEMORY);

    // 系统信息紧跟硬件之后：进程 / 线程 / 句柄三项都在这一台里
    device_count += build_system_device(devices + device_count,
                                        TM_OUT_DEVICE_MAX - device_count);

    // 磁盘 / 网卡 / GPU：这一版只报名称
    device_count += build_named_devices(devices + device_count,
                                        TM_OUT_DEVICE_MAX - device_count,
                                        registry.disk, registry.disk_count,
                                        TM_DEV_TYPE_DISK);

    device_count += build_named_devices(devices + device_count,
                                        TM_OUT_DEVICE_MAX - device_count,
                                        registry.network, registry.network_count,
                                        TM_DEV_TYPE_NETWORK);

    device_count += build_named_devices(devices + device_count,
                                        TM_OUT_DEVICE_MAX - device_count,
                                        registry.gpu, registry.gpu_count,
                                        TM_DEV_TYPE_GPU);

    // 包头要先知道后面有几行：D 头算一行，每个字段算一行
    int line_count = 0;

    for (int i = 0; i < device_count; i++) {
        line_count += 1 + devices[i].field_count;
    }

    reply_package_header(seq, type, 0ULL, TM_STATUS_OK, line_count);

    for (int i = 0; i < device_count; i++) {
        emit_device_block(&devices[i]);
    }

    return true;
}

/** 处理 typeCode=2：回 proto / fields / fieldMask / encoding 等自检信息。 */
static bool handle_query_info(unsigned long long seq, int type)
{
    reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);

    printf("I proto=%d\n", TM_PROTO_VERSION);
    printf("I fields=%d\n", k_field_count);
    printf("I fieldMask=0x%llx\n", TM_FIELD_ALL);
    printf("I encoding=percent\n");
    printf("I statusCodes=0,1,2,3,4,5\n");

    // 设备快照的能力自报：客户端据此判断"这台后端支不支持性能页的 typeCode=5"，
    // 免得对着老版本后端一直发请求、一直收 UNSUPPORTED。
    printf("I deviceSnapshot=%d\n", TM_REQ_DEVICE_SNAPSHOT);
    print_info_line("backend", "TaskManager Native Backend");
    printf("I pid=%d\n", (int) getpid());
    printf("I uid=%d\n", (int) getuid());
    printf("I euid=%d\n", (int) geteuid());

    return true;
}

/**
 * 处理 typeCode=3：`REQ <seq> 3 0x0 <code> [arg] [arg2]`，控制码见头部注释第三节。
 *
 * 回执与执行分开表达：ACCEPTED = 已受理但这一版没真做（KILL 只回执）；
 * OK = 已做完；BAD_ARGUMENT / PROCESS_EXITED = 没做成及原因。
 */
static bool handle_control(TmBackend *backend, unsigned long long seq, int type,
                           const char *code_text, const char *argument,
                           const char *argument2)
{
    if (code_text == NULL || code_text[0] == '\0') {

        reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
        print_info_line("error", "missing control code");

        return true;
    }

    char *end = NULL;
    long parsed_code = strtol(code_text, &end, 10);

    if (end == code_text) {

        reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
        print_info_line("error", "control code is not a number");

        return true;
    }

    int code = (int) parsed_code;

    switch (code) {

        case TM_CTL_KILL_PROCESS: {

            int pid = 0;

            // -1 = 客户端没给启动时刻（拿不到就是拿不到，不能拿 0 顶替）
            long long start_ticks = -1;

            if (argument != NULL && argument[0] != '\0') {
                char *pid_end = NULL;
                pid = (int) strtol(argument, &pid_end, 10);

                // 整段必须都是数字：不然 "12abc" 会被 strtol 解成 12
                if (pid_end == argument || *pid_end != '\0') {
                    pid = 0;
                }
            }

            if (argument2 != NULL && argument2[0] != '\0') {
                char *ticks_end = NULL;
                long long parsed = (long long) strtoll(argument2, &ticks_end, 10);

                // 0 是**合法**启动时刻（开机第 0 个 tick 起的进程，例如 init 的 start_time 就是 0），
                // 所以不能把 0 当"没给"。真正的"没给"是 -1。
                if (ticks_end != argument2 && *ticks_end == '\0' && parsed >= 0) {
                    start_ticks = parsed;
                }
            }

            if (pid <= 0) {
                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "kill needs a positive pid");

                return true;
            }

            // 必须同时给 pid 与 start_ticks：只凭 pid，目标进程退出后 PID 被复用，
            // 就会杀掉另一个进程。缺启动时刻一律拒绝，不做"猜一个"。
            if (start_ticks < 0) {
                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "kill needs pid and start_ticks (start_ticks >= 0)");

                return true;
            }

            // 身份校验：目标必须出现在最近一次快照里，且启动时刻一致。
            // 只比 pid 会在 PID 复用后"杀到另一个人"，所以两者缺一不可。
            // 校验无条件按全量重采：当前快照可能只含某个范围，不能拿"筛过的表"当唯一依据。
            if (g_snapshot.count == 0) {
                collect_snapshot(TM_SCOPE_ALL);
            }

            TmProcStat *target = table_find(&g_snapshot, pid, start_ticks);

            if (target == NULL) {
                reply_package_header(seq, type, 0ULL, TM_STATUS_PROCESS_EXITED, 0);
                print_info_line("error", "no such process in current snapshot (pid or start_ticks mismatch)");

                return true;
            }

            // 身份已由 pid + start_ticks 双重校验（都在最近一次快照里），现在真发信号。
            // 用 SIGKILL：界面上这个动作就叫"结束进程"，语义是立刻终止，不做"请它自己退"的礼貌沟通。
            if (kill(pid, SIGKILL) != 0) {

                // 失败常见原因：不是 root（EPERM）、进程刚好退出（ESRCH）。
                // 原样回 errno，让客户端能说清"为什么没成"，而不是一律"失败"。
                int saved_errno = errno;

                reply_package_header(seq, type, 0ULL, TM_STATUS_INTERNAL, 0);
                printf("I code=%d\n", code);
                printf("I action=kill\n");
                printf("I pid=%d\n", pid);
                printf("I executed=0\n");
                printf("I errno=%d\n", saved_errno);

                {
                    char note_text[96];

                    snprintf(note_text, sizeof(note_text), "kill failed, errno %d", saved_errno);
                    print_info_line("error", note_text);
                }

                return true;
            }

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
            printf("I code=%d\n", code);
            printf("I action=kill\n");
            printf("I pid=%d\n", pid);
            printf("I executed=1\n");
            print_info_line("signal", "SIGKILL sent");

            return true;
        }

        case TM_CTL_REINIT:

            // REINIT：清掉采样基准，下一轮快照重新建立
            baseline_clear();

            backend->baseline_ready = false;

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
            printf("I code=%d\n", code);
            printf("I action=reinit\n");
            print_info_line("baseline", "cleared (next snapshot re-baselines)");

            return true;

        case TM_CTL_DUMP_STATE:

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
            printf("I code=%d\n", code);
            printf("I state=%s\n", backend->running ? "running" : "stopping");
            printf("I commands=%lu\n", backend->command_count);
            printf("I baseline=%s\n", backend->baseline_ready ? "ready" : "none");
            printf("I processes=%d\n", g_snapshot.count);
            printf("I baselineProcesses=%d\n", g_baseline.count);
            printf("I cpuTotalPrev=%lld\n", g_cpu_total_prev);
            printf("I pageSize=%ld\n", g_page_size);
            printf("I clockTicks=%ld\n", g_clock_ticks);
            printf("I proto=%d\n", TM_PROTO_VERSION);

            return true;

        case TM_CTL_SHUTDOWN:

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
            printf("I code=%d\n", code);
            printf("I action=shutdown\n");

            backend->running = false;

            return false;

        default:

            reply_package_header(seq, type, 0ULL, TM_STATUS_UNSUPPORTED, 0);

            {
                char note_text[48];

                snprintf(note_text, sizeof(note_text), "unknown control code %d", code);
                print_info_line("error", note_text);
            }

            return true;
    }
}

/** 处理心跳：客户端用来确认"进程还活着、管道没断"。 */
static bool handle_ping(unsigned long long seq, int type)
{
    reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
    printf("I pong=1\n");

    return true;
}

/**
 * 解析并处理一条 REQ（格式见头部注释第二节）。
 *
 * 缺字段 / seq 不是正整数 / type 或 mask 不是数字 → 整行丢弃，不回包；
 * 能解析但取值不合法 → 回 BAD_ARGUMENT 或 UNSUPPORTED。
 */
static bool handle_request(TmBackend *backend, const char *line)
{
    char copy[TM_LINE_MAX];

    snprintf(copy, sizeof(copy), "%s", line);

    char *save = NULL;
    char *prefix = strtok_r(copy, " \t", &save);

    if (prefix == NULL || strcmp(prefix, "REQ") != 0) {
        return true;
    }

    char *seq_text = strtok_r(NULL, " \t", &save);
    char *type_text = strtok_r(NULL, " \t", &save);
    char *mask_text = strtok_r(NULL, " \t", &save);

    if (seq_text == NULL || type_text == NULL || mask_text == NULL) {
        return true;
    }

    char *end = NULL;

    unsigned long long seq = strtoull(seq_text, &end, 10);

    if (end == seq_text || seq == 0ULL) {
        return true;
    }

    long type = strtol(type_text, &end, 10);

    if (end == type_text) {
        return true;
    }

    unsigned long long mask = strtoull(mask_text, &end, 16);

    if (end == mask_text) {
        return true;
    }

    // 第四个字段之后：控制类的 code，再往后是两个参数（参数都不含空格）。
    // KILL 用 `code pid start_ticks`，其余控制码忽略这两个字段。
    char *code_text = strtok_r(NULL, " \t", &save);

    char *argument = (code_text == NULL) ? NULL : strtok_r(NULL, " \t", &save);

    char *argument2 = (argument == NULL) ? NULL : strtok_r(NULL, " \t", &save);

    switch ((int) type) {

        case TM_REQ_QUERY_PROCESS:
            // 查询进程的第 4 个附加字段是范围（0 全部 / 1 用户 / 2 系统），缺省 = 全部
            return handle_query_process(backend, seq, (int) type, mask, code_text);

        case TM_REQ_QUERY_INFO:
            return handle_query_info(seq, (int) type);

        case TM_REQ_CONTROL:
            return handle_control(backend, seq, (int) type, code_text, argument, argument2);

        case TM_REQ_PING:
            return handle_ping(seq, (int) type);

        case TM_REQ_DEVICE_SNAPSHOT:
            return handle_device_snapshot(seq, (int) type);

        default:
            reply_package_header(seq, (int) type, mask, TM_STATUS_UNSUPPORTED, 0);

            {
                char note_text[48];

                snprintf(note_text, sizeof(note_text), "unsupported request type %d", (int) type);
                print_info_line("error", note_text);
            }

            return true;
    }
}

/**
 * 分发一条命令（已裁掉首尾空白）。
 *
 * 通道上只认 `REQ ...`。其它内容一律静默忽略——不往 stdout 回任何东西，
 * 否则那行会变成"不属于任何包的散行"，把客户端日志搅浑。
 *
 * @param backend 后端状态
 * @param command 命令正文，不含换行
 * @return true = 继续循环；false = 收到退出指令
 */
static bool dispatch_command(TmBackend *backend, const char *command)
{
    if (command == NULL || command[0] == '\0') {
        return true;
    }

    backend->command_count++;

    if (strncmp(command, "REQ ", 4) == 0) {
        return handle_request(backend, command);
    }

    // 非协议行：静默忽略，不回包
    return true;
}

/**
 * 常驻循环：读一行 -> 分发 -> 立刻 flush。
 *
 * 为什么每轮都 flush：管道模式下 stdout 默认带缓冲，
 * 不主动 flush 的话 Android 侧会迟迟读不到字节，看起来像"没输出"。
 *
 * flush 失败（EPIPE：对端已关闭）说明没人在收了，直接退出循环，
 * 不要继续空转（F-09 / F-11）。
 *
 * @param backend 后端状态；running 被置 false 或 stdin 关闭时返回
 */
static void run_message_loop(TmBackend *backend)
{
    char line[TM_LINE_MAX];

    while (backend->running && read_command_line(line, (int) sizeof(line))) {

        dispatch_command(backend, trim_text(line));

        if (fflush(stdout) != 0) {
            // 管道已断：再写也没有意义
            backend->running = false;
        }
    }
}

/**
 * 进程入口：打版本行与 READY，然后进入常驻循环。
 *
 * @return 0 = 正常退出（收到 SHUTDOWN 或 stdin 被关闭）
 */
int main(int argc, char *argv[])
{
    (void) argc;
    (void) argv;

    // 父进程先关掉管道、本进程还在往 stdout 写，默认会收到 SIGPIPE 直接死掉；
    // 忽略它，这样写失败只让 printf / fflush 返回错误，循环还能按 EOF 正常收尾。
    signal(SIGPIPE, SIG_IGN);

    // 页大小与时钟节拍：整个进程只取一次，采集时每进程都省一次 sysconf
    long page_size = sysconf(_SC_PAGESIZE);

    if (page_size > 0) {
        g_page_size = page_size;
    }

    long clock_ticks = sysconf(_SC_CLK_TCK);

    if (clock_ticks > 0) {
        g_clock_ticks = clock_ticks;
    }

    TmBackend backend = {
        .command_count = 0,
        .running = true,
        .baseline_ready = false,
    };

    // 启动标识：ELF 被拉起后第一行一定是它（客户端只把它当日志，不参与协议解析）
    printf("TaskManager Native Backend proto=%d\n", TM_PROTO_VERSION);

    // 就绪握手：这一行之后，客户端才允许发正式请求
    fputs("READY\n", stdout);

    fflush(stdout);

    run_message_loop(&backend);

    fputs("EXIT stdin closed\n", stdout);
    fflush(stdout);

    // 0 = 正常结束；调用方据此区分"按指令退出"与"异常终止"
    return 0;
}
