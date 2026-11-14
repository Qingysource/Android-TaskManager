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
 * 设备快照（typeCode=5）已实现 CPU 那一组（利用率 / 频率 / 进程线程句柄数 / 开机时长 /
 * 插槽内核逻辑处理器 / 三级缓存）；处理器商品名与虚拟化方案由 ELF 侧后续补，
 * 现在这两格回 UNSUPPORTED。
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

/** 协议版本。 */
#define TM_PROTO_VERSION 2

/** 请求类型（LinuxRequestType）。 */
#define TM_REQ_QUERY_PROCESS 1
#define TM_REQ_QUERY_INFO 2
#define TM_REQ_CONTROL 3
#define TM_REQ_PING 4

/**
 * 设备快照：一次抓齐性能页要显示的整机数据（当前只做 CPU 页那一组）。
 *
 * 请求 `REQ <seq> 5 0x0`，无附加参数；应答里没有 P 行，全是 I 行，
 * 所以包头的 count = 随后的 I 行条数（不是 0）——客户端按 count 收满才算完整包。
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

        snprintf(buffer, capacity, "%.1f", cur->cpu_usage_percent);

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

/**
 * 读 /proc/stat 的 cpu 汇总行，得到总 jiffies 与空闲 jiffies（idle + iowait）。
 *
 * @return false = 读不到，或格式不认识
 */
static bool read_cpu_stat_pair(long long *total_out, long long *idle_out)
{
    FILE *file = fopen("/proc/stat", "r");

    if (file == NULL) {
        return false;
    }

    char line[512];

    bool read_ok = fgets(line, (int) sizeof(line), file) != NULL;

    fclose(file);

    if (!read_ok || strncmp(line, "cpu", 3) != 0) {
        return false;
    }

    long long total = 0;
    long long idle = 0;
    const char *cursor = line + 3;

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

        // 第 4 个是 idle、第 5 个是 iowait：两个都算没在干活
        if (slot == 3 || slot == 4) {
            idle += value;
        }

        cursor = end;
    }

    if (total <= 0) {
        return false;
    }

    *total_out = total;
    *idle_out = idle;

    return true;
}

/**
 * 整机 CPU 利用率（%）。
 *
 * 单看某一瞬间的累计值算不出百分比，必须两轮相减。采样点做缓存：
 * 距上次采样已经够了就只用差值，不够才睡一小会儿补足 —— 刷新间隔本来就有 0.5~4 秒，
 * 正常节奏下一次都不用睡。
 *
 * @return -1 = 还没有对比点（第一次调用），或 /proc/stat 读不到
 */
static double read_cpu_usage_percent(void)
{
    static long long prev_total = -1;
    static long long prev_idle = -1;
    static long long prev_ms = 0;

    long long total_now = 0;
    long long idle_now = 0;

    if (!read_cpu_stat_pair(&total_now, &idle_now)) {
        return -1.0;
    }

    // 第一次调用只记基准：这时候睡 200ms 也算不出百分比，纯属白等
    if (prev_total < 0) {

        prev_total = total_now;
        prev_idle = idle_now;
        prev_ms = monotonic_ms();

        return -1.0;
    }

    long long elapsed = monotonic_ms() - prev_ms;

    if (elapsed < TM_DEVICE_CPU_SAMPLE_MS) {
        usleep((useconds_t) ((TM_DEVICE_CPU_SAMPLE_MS - elapsed) * 1000));
    }

    long long total_later = 0;
    long long idle_later = 0;

    if (!read_cpu_stat_pair(&total_later, &idle_later)) {
        return -1.0;
    }

    if (total_later <= prev_total) {
        return -1.0;
    }

    long long delta_total = total_later - prev_total;
    long long delta_idle = idle_later - prev_idle;

    if (delta_idle < 0) {
        delta_idle = 0;
    }

    double percent = (double) (delta_total - delta_idle) * 100.0 / (double) delta_total;

    if (percent < 0.0) {
        percent = 0.0;
    }

    if (percent > 100.0) {
        percent = 100.0;
    }

    prev_total = total_later;
    prev_idle = idle_later;
    prev_ms = monotonic_ms();

    return percent;
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
 * CPU 缓存的数据来源接口（**由 ELF 这一侧实现，本文件不再自己取**）。
 *
 * 为什么留成接口：sysfs 的 /sys/devices/system/cpu/cpu0/cache/ 在部分 OEM 内核上
 * 被裁掉或被 SELinux 拦，读出来是空的；真机上唯一可靠的来源往往只有厂商属性、
 * SoC 型号映射表或别的私有节点，这些只有这一侧能拿到。
 *
 * 约定（三条，照着填就行）：
 * 1. level 取 1 / 2 / 3，分别对应 L1 / L2 / L3；
 * 2. 返回该级缓存的总量，**单位 KB**（L1 若分指令与数据两条，返回两者之和）；
 * 3. 返回 -1 表示"这台设备确实没有这一级"（例如没有 L3），客户端会显示 不支持。
 *
 * 优先级：本函数返回 >= 0 的级，采集侧**不再去碰 sysfs**，以你的值为准；
 * 只有你返回 -1 的级，才会退回去尝试 sysfs（ways × sets × line_size 推算）。
 * 所以这里填了值就一定生效，不会被 sysfs 的探测结果覆盖。
 *
 * 参数说明：level 从 1 开始，不会是 0 或负数；调用频率与性能页采样一致（最快 0.5 秒一次），
 * 如果来源是一次性读取（属性/映射表），建议在函数内部做一次静态缓存，别每次重复查。
 */
static int query_cache_kb_external(int level)
{
    (void) level;

    // TODO(ELF 侧)：按 level 返回 L1 / L2 / L3 的 KB，没有这一级返回 -1
    return -1;
}

/**
 * 一次扫描 cpu0 的 cache/index*，把 L1 / L2 / L3 各自求和（KB）。
 *
 * 为什么一次扫完再分账：三级各扫一遍就是最多 48 次 open，
 * 每帧都做一遍没必要；一次遍历 16 个 index 就够。
 *
 * 三个细节：
 * 1. **index 编号按"读不到就跳过"处理，不 break**：绝大多数内核是 0..N 连续，
 *    但个别内核的编号有空洞，break 会把后面的级一起丢掉。
 * 2. size 读不到时用 ways × sets × line_size 推算 —— 这三个字段比 size 更常在，
 *    也更容易被 SELinux 放行。
 * 3. 同一级有多条（L1 分数据/指令）就相加：页面上的 "L1 缓存" 是总量。
 *
 * @param out_kb 长度 3 的数组，分别接 L1 / L2 / L3；-1 = 这一级不存在
 */
static void probe_cpu_cache_kb(int out_kb[3])
{
    // 先问外部实现：它给了值的级就直接采用，不再碰 sysfs
    for (int level = 1; level <= 3; level++) {
        out_kb[level - 1] = query_cache_kb_external(level);
    }

    int total[3] = { 0, 0, 0 };

    bool found[3] = { false, false, false };

    for (int index = 0; index < 16; index++) {

        char path[TM_PATH_MAX];
        double level_value = 0.0;

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu0/cache/index%d/level", index);

        if (!read_first_number(path, &level_value)) {
            continue;
        }

        int level = (int) level_value;

        if (level < 1 || level > 3) {
            continue;
        }

        // 外部已经给了这一级：跳过，别让 sysfs 的值盖掉它
        if (out_kb[level - 1] >= 0) {
            continue;
        }

        int size_kb = -1;

        char size_text[64];

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu0/cache/index%d/size", index);

        if (read_text_file(path, size_text, (int) sizeof(size_text)) >= 0) {

            trim_text(size_text);

            size_kb = parse_size_kb(size_text);
        }

        // size 没有（或读到 0）就按 ways × sets × line 推
        if (size_kb <= 0) {

            double ways = 0.0;
            double sets = 0.0;
            double line = 0.0;

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu0/cache/index%d/ways_of_associativity", index);

            bool has_ways = read_first_number(path, &ways);

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu0/cache/index%d/number_of_sets", index);

            bool has_sets = read_first_number(path, &sets);

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu0/cache/index%d/coherency_line_size", index);

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

        // 外部值优先；外部没给才用 sysfs 的结果；都没有就 -1（页面显示 不支持）
        if (out_kb[level - 1] >= 0) {
            continue;
        }

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

/** 发一条文本字段：I key=值（值百分号编码，和进程行同一套规则）。 */
static void emit_device_text(const char *key, const char *value)
{
    printf("I %s=", key);

    print_escaped_value(value);

    printf("\n");
}

/** 发一条整数字段；没有值就发 I key=!状态。 */
static void emit_device_int(const char *key, long long value, const char *status)
{
    if (value < 0) {

        printf("I %s=!%s\n", key, status);

        return;
    }

    printf("I %s=%lld\n", key, value);
}

/** 发一条浮点字段（固定小数位）；没有值就发 I key=!状态。 */
static void emit_device_float(const char *key, bool has_value, double value, int digits,
                              const char *status)
{
    if (!has_value) {

        printf("I %s=!%s\n", key, status);

        return;
    }

    char text[64];

    snprintf(text, sizeof(text), "%.*f", digits, value);

    emit_device_text(key, text);
}

/**
 * 处理 typeCode=5：抓一次设备快照（当前只有性能页 CPU 页要的那一组）。
 *
 * 顺序：先全部采齐 -> 数清 I 行条数 -> 发包头 -> 逐行发。
 * 条数必须先定：客户端就是按 count 判断包收满没有。
 */
static bool handle_device_snapshot(unsigned long long seq, int type)
{
    // 整机利用率（%）
    double usage_percent = read_cpu_usage_percent();

    // 频率：kHz -> MHz（客户端再换算成 GHz 显示）
    double cur_khz = 0.0;
    bool has_cur_khz = read_cpu_cur_freq_khz(&cur_khz);

    double max_khz = 0.0;
    bool has_max_khz = read_cpu_max_freq_khz(&max_khz);

    int process_count = count_all_processes();
    int thread_count = count_all_threads();
    int fd_count = read_open_fd_count();

    long long uptime_seconds = read_uptime_seconds();

    int logical_cpus = count_logical_cpus();

    int sockets = -1;
    int cores = -1;
    count_sockets_and_cores(&sockets, &cores);

    // 三级缓存：一次扫描 cpu0 的 cache/index*，L1 / L2 / L3 各自求和；
    // 扫不到时 query_cache_kb_external 兜底（那个函数留给 ELF 这一侧补实现）
    int cache_kb[3] = { -1, -1, -1 };

    probe_cpu_cache_kb(cache_kb);

    int l1_kb = cache_kb[0];
    int l2_kb = cache_kb[1];
    int l3_kb = cache_kb[2];

    // ---- 下面两格留给 ELF 这一侧补实现 ----
    //
    // cpu_name：处理器商品名，要人看得懂（例如 骁龙 8 Gen 3），不要内核里的内部型号。
    // virtualization：虚拟化方案，取值形如 已启用(方案名) / 已禁用 / 不支持。
    //
    // 现在固定回 UNSUPPORTED：客户端把这两格显示成占位内容，不会当成 0 或空串。
    // 补实现时只在这两个变量里填字符串，下面的发包代码一行都不用改。
    const char *cpu_name = NULL;

    const char *virtualization = NULL;

    // 固定 15 行：没值的项也发一行 !状态，条数好数、客户端好对
    const int count = 15;

    reply_package_header(seq, type, 0ULL, TM_STATUS_OK, count);

    emit_device_float("cpu_usage_percent", usage_percent >= 0.0, usage_percent, 1,
                      TM_FIELD_STATUS_NO_BASELINE);

    emit_device_int("cpu_speed_mhz", has_cur_khz ? (long long) (cur_khz / 1000.0 + 0.5) : -1,
                    TM_FIELD_STATUS_NOT_AVAILABLE);

    emit_device_int("cpu_base_mhz", has_max_khz ? (long long) (max_khz / 1000.0 + 0.5) : -1,
                    TM_FIELD_STATUS_NOT_AVAILABLE);

    if (cpu_name != NULL && cpu_name[0] != '\0') {
        emit_device_text("cpu_name", cpu_name);
    } else {
        printf("I cpu_name=!%s\n", TM_FIELD_STATUS_UNSUPPORTED);
    }

    if (virtualization != NULL && virtualization[0] != '\0') {
        emit_device_text("virtualization", virtualization);
    } else {
        printf("I virtualization=!%s\n", TM_FIELD_STATUS_UNSUPPORTED);
    }

    emit_device_int("process_count", process_count, TM_FIELD_STATUS_READ_FAILED);

    emit_device_int("thread_count", thread_count, TM_FIELD_STATUS_READ_FAILED);

    emit_device_int("fd_count", fd_count, TM_FIELD_STATUS_READ_FAILED);

    emit_device_int("uptime_seconds", uptime_seconds, TM_FIELD_STATUS_READ_FAILED);

    emit_device_int("sockets", sockets, TM_FIELD_STATUS_NOT_AVAILABLE);

    emit_device_int("cores", cores, TM_FIELD_STATUS_NOT_AVAILABLE);

    emit_device_int("logical_cpus", logical_cpus, TM_FIELD_STATUS_NOT_AVAILABLE);

    emit_device_int("l1_cache_kb", l1_kb, TM_FIELD_STATUS_NOT_AVAILABLE);

    emit_device_int("l2_cache_kb", l2_kb, TM_FIELD_STATUS_NOT_AVAILABLE);

    emit_device_int("l3_cache_kb", l3_kb, TM_FIELD_STATUS_UNSUPPORTED);

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
