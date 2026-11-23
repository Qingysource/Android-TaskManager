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
 *       virtualization / is_vm / process_count / thread_count / fd_count /
 *       uptime_seconds / sockets / cores / logical_cpus /
 *       l1_cache_kb / l2_cache_kb / l3_cache_kb
 *     没值的项发 `I key=!状态`（UNSUPPORTED / NOT_AVAILABLE / NO_BASELINE），
 *     客户端按"这一格没数据"处理，绝不当作 0。
 *     cpu_name（处理器商品名）由 ELF 这一侧后续补实现，现在固定回 UNSUPPORTED。
 *     virtualization 的取值是"整串状态"，客户端不做加工直接显示：
 *       不支持 / 已禁用 / 已启用(Gunyah) / 已启用(KVM) / 已启用(GenieZone) / 已启用(Halla)
 *     判定见 probe_virtualization_internal（只看 /dev 节点）。
 *     is_vm 恒为 0：客户端见到 1 才显示"虚拟机"，见到 0 时虚拟化与 AVF 两行才显示。
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
 *     - uid 也是行元数据（不进 43 位掩码）：它是 /proc/<pid> 目录属主。
 *       Android 侧要拿它把进程映射回"哪个应用装的"（包名 / targetSdk / 应用描述），
 *       而这一轮采集判 scope 时已经 stat 过属主，带上它不增加额外文件读取；
 *       取不到（目录已消失或被 SELinux 挡住）时整个键都不输出，客户端按"没有"处理；
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
 *           exe_path / cmdline / selinux / platform / arch / is_admin / nx_protect /
 *           android_priority（= /proc/<pid>/oom_score_adj，见 field_text_of）
 *   - 归 Android 服务填（回 NOT_AVAILABLE）：package_name / target_sdk /
 *     description / isolation
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
 * MEMORY（总量与可用量）、DISK（每块物理盘一块：型号 / 介质 / 总线 / 容量 / 已格式化 /
 * 系统盘 / 页面文件 / 活动时间 / 平均响应 / 读写速率）、NETWORK / GPU（位置与名字）、
 * SYSTEM（进程线程句柄与开机时长）。
 * 处理器商品名、虚拟化方案、外部缓存容量三个位置留在 query_*_external，现在回 UNSUPPORTED。
 *
 * 线程模型：主线程只跑"读 stdin -> 分发 -> 回包"；另有一条**常驻采样线程**
 * （main() 里 disk_sampler_start() 起，detached）每 250 ms 读一遍各盘 stat，
 * 用最近 1 秒的滑动窗口结算磁盘四项实时指标，结果放在互斥保护的表里。
 * 快照请求只查这张表 —— UI 的更新档位（0.5 / 1 / 4 秒）不再决定采样窗口，只决定读表频率。
 * 线程起不来或结果过期时，请求侧退回"用相邻两次请求差值现算"，功能降级但不失效。
 */

#include <ctype.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <ifaddrs.h>
#include <limits.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <pwd.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
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
 * 请求 `REQ <seq> 5 0x0 [键=值 ...]`。第四段之后的都是**本帧上下文参数**，
 * 由上层把只有应用层才知道的设备事实带进来（目前是 SoC 型号 / 厂商 / 名称库路径，
 * 后续给虚拟化与 AVF 这类字段留位）。值里不能有空格，特殊字符写成 %XX。
 * 一个参数都不带也能正常出快照，只是用上一次的上下文。
 *
 * 应答由 D 行（设备头）与 F 行（设备字段）组成，所以包头的 count = 随后的
 * D 行与 F 行总条数（不是 0）——客户端按 count 收满才算完整包。
 * 没有某类设备就不发它的 D 行，客户端据此知道这一类型没有实例。
 */
#define TM_REQ_DEVICE_SNAPSHOT 5

/**
 * 设备快照请求最多收几个附加参数（`键=值`）。
 *
 * 目前用到三个键（soc_model / soc_manufacturer / cpu_name_db，最后一个可给两次），
 * 留出余量给后续 Android 侧的设备字段，比如虚拟化与 AVF 这类只有应用层才知道的信息。
 */
#define TM_DEVICE_CONTEXT_ARGUMENT_MAX 12

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

    /**
     * 进程属主 uid（stat("/proc/<pid>") 的 st_uid）。
     *
     * 采集阶段只 stat 一次，同时用于三处：范围筛选（用户 / 系统）、
     * `user` 字段的用户名解析、以及 P 行的 uid 扩展键（Android 侧按它映射包名）。
     */
    int uid;

    /** uid 是否取到；false = 目录已消失或权限不够，P 行不输出 uid 键。 */
    bool uid_known;

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
 * 一个进程属于哪个范围，并顺手把属主 uid 带出来。
 *
 * 为什么要一起取：判范围要 stat("/proc/<pid>") 看属主，
 * 而 P 行的 uid 扩展键要的正是同一个数字。
 * 分成两个函数会在每个进程上多一次 stat，没有任何收益。
 *
 * @param out_uid 非 NULL 时写入目录属主 uid；返回 -1 时保持调用方原值
 * @return [TM_SCOPE_USER] / [TM_SCOPE_SYSTEM]；-1 = 目录 stat 不了（刚退出或权限不够），无法判定
 */
static int process_scope_of(int pid, int *out_uid)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d", pid);

    struct stat info;

    if (stat(path, &info) != 0) {
        return -1;
    }

    if (out_uid != NULL) {
        *out_uid = (int) info.st_uid;
    }

    return ((long long) info.st_uid >= TM_UID_APP_START) ? TM_SCOPE_USER : TM_SCOPE_SYSTEM;
}

/**
 * 第一轮（mini）采样：遍历 /proc，每个进程只读一次 stat。
 *
 * @param scope 范围筛选。选 USER / SYSTEM 时，属主 stat 是筛选本身；
 *              选 ALL 时也会做这一次 stat —— 它同时供 P 行的 uid 扩展键使用，
 *              每进程一次目录 stat 在整轮里只有毫秒级，换来 Android 侧能直接映射包名
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

        // 属主 uid 与范围判定共用同一次 stat：既判"用户 / 系统"，
        // 也留作 P 行的 uid 扩展键（安卓侧按它查包名、targetSdk、应用描述）。
        int owner_uid = 0;
        int owner_scope = process_scope_of(pid, &owner_uid);

        // 筛选放在最前面：不合范围的进程连 stat 文件都不读，
        // 于是"包里的行"和"第二轮要补读的文件"一起省掉了。
        if (scope != TM_SCOPE_ALL && owner_scope != scope) {
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

        // parse_proc_stat 会 memset 整个结构，uid 必须在这之后回填：
        // 它是采集阶段 stat 到的事实，不是 /proc/<pid>/stat 里的字段。
        item.uid = owner_uid;
        item.uid_known = (owner_scope >= 0);

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

        // 属主 uid 采集阶段已经 stat 过（P 行那条 uid 扩展键就是它），
        // 这里优先复用；只有采集时没取到，才补一次 stat。
        int uid = cur->uid;

        if (!cur->uid_known && !proc_uid_of(cur->pid, &uid)) {
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

    if (strcmp(key, "android_priority") == 0) {

        // Android 的进程优先级就是 LMK 口径的 oom_score_adj：
        // 负数 = 越不容易被杀（前台 / 常驻），正数 = 越先被回收。
        // 这一格必须由 Linux 侧读：Android Framework 只能看到自己的 importance，
        // 别人的 oom_score_adj 恰恰是提权身份才有权限打开的文件。
        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", cur->pid);

        if (read_text_file(path, buffer, capacity) < 0) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        char *newline = strchr(buffer, '\n');

        if (newline != NULL) {
            *newline = '\0';
        }

        return text_result(out_status, buffer);
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

    // 属主 uid：与 start_ticks 同样是行元数据，不占 43 位掩码。
    // Android 侧靠它把 uid 换成包名 / targetSdk / 应用描述，
    // 采集阶段已经 stat 过目录属主，这里只做输出，不再读 /proc。
    if (cur->uid_known) {
        printf(" uid=%d", cur->uid);
    }

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

/**
 * 收集要看的 cpuN 编号。
 *
 * 先列 /sys/devices/system/cpu；列不了目录（SELinux 只给 search 不给 readdir）时，
 * 读内核固定文件 possible（"0-7" 就是 8 个逻辑核），再不行就按 cpu0..cpu15 的
 * 固定编号逐个 stat —— CPU 编号本来就是连续的，不该因为列不了目录就数不出核来。
 *
 * @return 收集到的个数；0 = 一条都没拿到
 */
static int collect_cpu_indices(int *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    int count = 0;

    DIR *dir = opendir("/sys/devices/system/cpu");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            // 只认 cpuN；cpufreq / cpuidle / policyN 这些不是处理器
            if (strncmp(entry->d_name, "cpu", 3) != 0) {
                continue;
            }

            if (!is_pure_digits(entry->d_name + 3)) {
                continue;
            }

            if (count >= capacity) {
                break;
            }

            out[count++] = atoi(entry->d_name + 3);
        }

        closedir(dir);
    }

    if (count > 0) {
        return count;
    }

    // 第二档：possible 是内核固定导出的"这颗 SoC 一共有哪些 CPU"，格式 "0-7" / "0-3,4-7"
    char possible[128];

    if (read_text_file("/sys/devices/system/cpu/possible", possible, (int) sizeof(possible)) > 0) {

        const char *cursor = possible;

        while (*cursor != '\0' && count < capacity) {

            if (*cursor < '0' || *cursor > '9') {
                cursor++;
                continue;
            }

            int from = 0;

            while (*cursor >= '0' && *cursor <= '9') {
                from = from * 10 + (*cursor - '0');
                cursor++;
            }

            int to = from;

            if (*cursor == '-') {

                cursor++;

                if (*cursor < '0' || *cursor > '9') {
                    break;
                }

                to = 0;

                while (*cursor >= '0' && *cursor <= '9') {
                    to = to * 10 + (*cursor - '0');
                    cursor++;
                }
            }

            for (int value = from; value <= to && count < capacity; value++) {
                out[count++] = value;
            }
        }
    }

    if (count > 0) {
        return count;
    }

    // 第三档：固定编号。cpu 目录存在就是一颗（拓扑读不读得到另算）
    for (int cpu = 0; cpu < 16 && count < capacity; cpu++) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d", cpu);

        struct stat info;

        if (stat(path, &info) == 0) {
            out[count++] = cpu;
        }
    }

    return count;
}

/** /sys/devices/system/cpu 下 cpuN 的个数 = 逻辑处理器数；-1 = 一条都拿不到。 */
static int count_logical_cpus(void)
{
    int cpu_ids[256];

    int count = collect_cpu_indices(cpu_ids, (int) (sizeof(cpu_ids) / sizeof(cpu_ids[0])));

    return count > 0 ? count : -1;
}

/**
 * 数处理器插槽与物理核心。
 *
 * 优先级（越靠前越可信）：
 *   1) cpuN/topology/core_cpus_list       同一物理核心包含哪些逻辑 CPU
 *   2) cpuN/topology/thread_siblings_list 老内核的同义文件，作用完全相同
 *   3) physical_package_id + cluster_id + core_id  前两者都没有时的备用组合
 *   4) 都拿不到  cores 返回 -1（未知），**不拿逻辑 CPU 数冒充物理核心数**
 *
 * 为什么不再以 core_id 为首选：cluster_id / core_id 是平台相关的拓扑编号，
 * 不同簇里 core_id 会重复（big.LITTLE 的小核簇与大核簇各有 0..3），
 * 只按 package + core_id 去重会把 8 核并成 4 核 —— 这正是旧实现的坑。
 * 内核直接给的"同核心 CPU 集合"才是我们要的那个关系。
 *
 * cluster_id 不是插槽：physical_package_id 表示处理器封装（SoC / 插槽），
 * 手机多簇共用一颗 SoC，package 通常都是 0。
 *
 * 列表会先展开成升序编号再组键（"0-3,5" 与 "0,1,2,3,5" 认作同一个核心），
 * 不靠字符串字面比较。统计口径是"存在的 CPU"（枚举 cpuN 目录），不看 online：
 * 任务管理器要的是这颗芯片有几个物理核心，热插拔下线不该让核心数变少。
 *
 * @param sockets_out 插槽数：读到 package 就按实际去重数，读不到按单 SoC = 1；一个核都没有给 -1
 * @param cores_out   物理核心数；拓扑不可靠或一个都没读到给 -1
 */
static void count_sockets_and_cores(int *sockets_out, int *cores_out)
{
    *sockets_out = -1;

    *cores_out = -1;

    int cpu_ids[256];

    int cpu_count = collect_cpu_indices(cpu_ids, (int) (sizeof(cpu_ids) / sizeof(cpu_ids[0])));

    if (cpu_count <= 0) {
        return;
    }

    // 每个物理核心一条键：有同核心列表时是 "S:0,4"，否则是 "F:0:1:2"（封装:簇:核）
    char core_keys[128][96];

    int core_key_count = 0;

    int socket_ids[64];

    int socket_id_count = 0;

    int cpu_seen = 0;

    // 只要有一台 cpuN 拿不到可靠拓扑，整台机器的核心数就不下结论
    bool topology_failed = false;

    for (int cpu_slot = 0; cpu_slot < cpu_count; cpu_slot++) {

        int cpu = cpu_ids[cpu_slot];

        cpu_seen++;

        char path[TM_PATH_MAX];

        // ---------------- 插槽：physical_package_id ----------------

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

            if (known) {
                // 已经记过这个封装
            } else if (socket_id_count < (int) (sizeof(socket_ids) / sizeof(socket_ids[0]))) {
                socket_ids[socket_id_count++] = id;
            } else {
                topology_failed = true;
            }
        }

        // ---------------- 核心：同核心 CPU 集合 ----------------

        char list[96];

        list[0] = '\0';

        bool has_list = false;

        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/topology/core_cpus_list", cpu);

        if (read_text_file(path, list, (int) sizeof(list)) > 0) {
            has_list = true;
        }

        if (!has_list) {

            // 老内核叫 thread_siblings_list，内容与 core_cpus_list 同义
            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/topology/thread_siblings_list", cpu);

            if (read_text_file(path, list, (int) sizeof(list)) > 0) {
                has_list = true;
            }
        }

        char key[96];

        key[0] = '\0';

        if (has_list) {

            // 去掉首尾空白（文件末尾常带一个换行）
            int head = 0;

            while (list[head] == ' ' || list[head] == '\t' ||
                   list[head] == '\r' || list[head] == '\n') {
                head++;
            }

            int tail = (int) strlen(list) - 1;

            while (tail >= head && (list[tail] == ' ' || list[tail] == '\t' ||
                                    list[tail] == '\r' || list[tail] == '\n')) {
                tail--;
            }

            // 把 "0-3,5" 展开成升序编号，再拼成键
            int siblings[96];

            int sibling_count = 0;

            bool parsed = head <= tail;

            const char *cursor = list + head;

            while (parsed && cursor <= list + tail && *cursor != '\0') {

                if (*cursor == ',' || *cursor == ' ' || *cursor == '\t') {
                    cursor++;
                    continue;
                }

                if (*cursor < '0' || *cursor > '9') {
                    parsed = false;
                    break;
                }

                int from = 0;

                while (*cursor >= '0' && *cursor <= '9') {
                    from = from * 10 + (*cursor - '0');
                    cursor++;
                }

                int to = from;

                if (*cursor == '-') {

                    cursor++;

                    if (*cursor < '0' || *cursor > '9') {
                        parsed = false;
                        break;
                    }

                    to = 0;

                    while (*cursor >= '0' && *cursor <= '9') {
                        to = to * 10 + (*cursor - '0');
                        cursor++;
                    }
                }

                if (to < from) {
                    parsed = false;
                    break;
                }

                for (int value = from; value <= to; value++) {

                    if (sibling_count >= (int) (sizeof(siblings) / sizeof(siblings[0])) ||
                        value >= (int) (sizeof(siblings) / sizeof(siblings[0]))) {
                        parsed = false;
                        break;
                    }

                    siblings[sibling_count++] = value;
                }
            }

            if (parsed && sibling_count > 0) {

                // 插入排序：集合很小（最多几十个逻辑核），不值得为它引 qsort 的比较函数
                for (int index = 1; index < sibling_count; index++) {

                    int value = siblings[index];

                    int slot = index - 1;

                    while (slot >= 0 && siblings[slot] > value) {
                        siblings[slot + 1] = siblings[slot];
                        slot--;
                    }

                    siblings[slot + 1] = value;
                }

                size_t used = 0;

                used += (size_t) snprintf(key + used, sizeof(key) - used, "S:");

                int previous = -1;

                for (int index = 0; index < sibling_count && used < sizeof(key) - 4; index++) {

                    if (siblings[index] == previous) {
                        continue;
                    }

                    previous = siblings[index];

                    used += (size_t) snprintf(key + used, sizeof(key) - used,
                                              (used <= 2) ? "%d" : ",%d", siblings[index]);
                }

            } else {
                // 读到了但解析不了：别拿这段文本当键，退回备用组合
                has_list = false;
            }
        }

        if (!has_list) {

            // 备用：封装 + 簇 + 核（三者齐全才算可靠）
            double cluster = 0.0;

            double core = 0.0;

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/topology/cluster_id", cpu);

            bool has_cluster = read_first_number(path, &cluster);

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/topology/core_id", cpu);

            bool has_core = read_first_number(path, &core);

            if (!has_cluster || !has_core) {
                topology_failed = true;
                continue;
            }

            snprintf(key, sizeof(key), "F:%d:%d:%d",
                     has_package ? (int) package : 0, (int) cluster, (int) core);
        }

        // ---------------- 对物理核心标识去重 ----------------

        bool known = false;

        for (int index = 0; index < core_key_count; index++) {

            if (strcmp(core_keys[index], key) == 0) {
                known = true;
                break;
            }
        }

        if (known) {
            continue;
        }

        if (core_key_count >= (int) (sizeof(core_keys) / sizeof(core_keys[0])) ||
            key[0] == '\0') {
            topology_failed = true;
            continue;
        }

        snprintf(core_keys[core_key_count], sizeof(core_keys[core_key_count]), "%s", key);

        core_key_count++;
    }

    if (cpu_seen == 0) {
        return;
    }

    // 手机多簇共用一颗 SoC：内核给了 package 就按实际去重数，没给就按单封装
    *sockets_out = (socket_id_count > 0) ? socket_id_count : 1;

    // 有任何一个核的拓扑不可靠，就不报一个"看起来合理"的总数
    if (!topology_failed && core_key_count > 0) {
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
 * @param cpu_dir   该实例的 sysfs 目录，例如 /sys/devices/system/cpu/cpu0
 * @param out_kb    长度 3，分别接 L1 / L2 / L3；-1 = 这一级没扫到
 * @param out_status 长度 3，说清"为什么没扫到"，三种可能：
 *                   OK             读到了
 *                   ACCESS_DENIED  有这一级，但尺寸读不出来（没权限 / 解析失败）
 *                   NOT_AVAILABLE  内核列出来的缓存里根本没有这一级（例如没有 L3）
 *                   UNSUPPORTED    连 <cpu_dir>/cache 目录都没有（平台不导出缓存信息）
 */
static void probe_cpu_cache_from_sysfs(const char *cpu_dir, int out_kb[3],
                                       TmQueryStatus out_status[3])
{
    out_kb[0] = -1;
    out_kb[1] = -1;
    out_kb[2] = -1;

    out_status[0] = TM_QUERY_NOT_AVAILABLE;
    out_status[1] = TM_QUERY_NOT_AVAILABLE;
    out_status[2] = TM_QUERY_NOT_AVAILABLE;

    int total[3] = { 0, 0, 0 };

    bool found[3] = { false, false, false };

    // 先探 cache 目录本身。这里只用 stat：它只需要父目录的 search 权限，不列目录内容。
    // 某些 SELinux 域允许打开 cache/indexN/level，却不让 readdir —— 用 opendir 判存在
    // 会把"读得到"误判成"读不了"，所以目录在就继续按固定 index 编号往下读。
    char cache_dir[TM_PATH_MAX];

    snprintf(cache_dir, sizeof(cache_dir), "%s/cache", cpu_dir);

    struct stat cache_info;

    if (stat(cache_dir, &cache_info) != 0) {

        bool denied = (errno == EACCES || errno == EPERM);

        for (int level = 0; level < 3; level++) {
            out_status[level] = denied ? TM_QUERY_ACCESS_DENIED : TM_QUERY_UNSUPPORTED;
        }

        return;
    }

    // 这一级在不在：index 里有 level=N 就算"有"，尺寸读不出来另算
    bool seen[3] = { false, false, false };

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

        seen[level - 1] = true;

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

        if (found[level - 1]) {

            out_kb[level - 1] = total[level - 1];
            out_status[level - 1] = TM_QUERY_OK;

        } else if (seen[level - 1]) {

            // 内核明确列了这一级，但尺寸拿不到：这是"读不到"，不是"没有"
            out_status[level - 1] = TM_QUERY_ACCESS_DENIED;

        } else {

            out_status[level - 1] = TM_QUERY_NOT_AVAILABLE;
        }
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

/**
 * 网卡上限 24：Android 的 /sys/class/net 能列出十几个到二十几个接口
 * （rmnet_ 一大家子 + wlan0 + 隧道）。现在底层"全收、只排序不过滤"，
 * 名额给足才不会把真网卡挤掉；排序保证无线 / 有线 / 移动网络排在虚拟接口前面。
 */
#define TM_OUT_NETWORK_MAX 24

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
/**
 * 显卡厂商：发现的时候定下来，采集的时候决定"这一格该找谁去读"。
 *
 * 为什么必须分厂商：同样是"GPU 利用率"，各家把文件放在完全不同的地方 ——
 *   高通   /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage（KGSL 专用）
 *   联发科 /sys/module/ged/parameters/gpu_loading（GED 框架）与 /sys/kernel/ged/hal/gpu_utilization
 *   Mali   devfreq 的 load（kbase / panfrost / panthor 都有）
 *   通用   <card>/device/gpu_busy_percent（amdgpu / i915 这类 PCI 驱动）
 * 不知道是哪家的卡，就只能把所有这些路径挨个试一遍，既慢又容易读到别人的数据。
 */
typedef enum {
    TM_GPU_VENDOR_UNKNOWN = 0,
    TM_GPU_VENDOR_QUALCOMM,
    TM_GPU_VENDOR_MEDIATEK,
    TM_GPU_VENDOR_SAMSUNG,
    TM_GPU_VENDOR_GOOGLE,
    TM_GPU_VENDOR_ARM,          /* 认不出是哪家 SoC 的 Mali */
    TM_GPU_VENDOR_IMAGINATION,  /* PowerVR */
    TM_GPU_VENDOR_NVIDIA,
    TM_GPU_VENDOR_AMD,
    TM_GPU_VENDOR_INTEL,
    TM_GPU_VENDOR_VIRTIO
} TmGpuVendor;

typedef struct {
    char id[TM_OUT_ID_MAX];
    char path[TM_PATH_MAX];
    char name[64];

    /** 厂商（TmGpuVendor）：只有显卡用；其余几类留 0，采集时不读它 */
    int vendor;
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
 * 块设备里这几类不是"用户理解的磁盘"，枚举时跳过。
 *
 *   loop / ram / zram : 回环文件与内存盘，没有物理介质
 *   dm- / md / nbd    : 设备映射 / 软 RAID / 网络块设备，底层另有真盘
 *   sr                : 光驱（没有盘片时容量 0）
 *   mtd               : 裸 flash 分区，不是块寻址磁盘
 *   *boot*            : MMC 的 boot0 / boot1 硬件启动区，容量小且不承载文件系统
 *
 * 只按名字过滤还不够（厂商命名的虚拟盘不在这一列），真正的判据在
 * enumerate_disk_devices 里：必须在 sysfs 拓扑中有 device 节点。
 */
static bool is_pseudo_block_name(const char *name)
{
    static const char *const prefixes[] = { "loop", "ram", "zram", "dm-", "sr", "md", "nbd", "mtd" };

    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {

        size_t length = strlen(prefixes[i]);

        if (strncmp(name, prefixes[i], length) == 0) {
            return true;
        }
    }

    if (strstr(name, "boot") != NULL) {
        return true;
    }

    return false;
}

/** 按块名排序：/sys/block 的 readdir 顺序不保证稳定，编号必须建立在名字排序之上。 */
static int compare_disk_name(const void *left, const void *right)
{
    const TmDeviceRef *a = (const TmDeviceRef *) left;
    const TmDeviceRef *b = (const TmDeviceRef *) right;

    return strcmp(a->name, b->name);
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

/** 判一块块设备能不能进磁盘列表，能就填进 out[count]；返回新的 count。 */
static int disk_add_block(TmDeviceRef *out, int capacity, int count, const char *block_name)
{
    if (block_name == NULL || block_name[0] == '\0' || block_name[0] == '.') {
        return count;
    }

    if (is_pseudo_block_name(block_name)) {
        return count;
    }

    // 枚举与固定路径两趟会撞名（sda 既被列目录列到、又被固定路径试到），按块名去重
    for (int i = 0; i < count; i++) {

        if (strcmp(out[i].name, block_name) == 0) {
            return count;
        }
    }

    if (count >= capacity) {
        return count;
    }

    char probe[TM_PATH_MAX];
    struct stat info;

    // 物理盘判据：控制器链接存在
    snprintf(probe, sizeof(probe), "/sys/block/%s/device", block_name);

    if (stat(probe, &info) != 0) {
        return count;
    }

    // 容量为 0 的空槽不算磁盘（读卡器 / 光驱位没有介质）
    double sectors = 0.0;

    snprintf(probe, sizeof(probe), "/sys/block/%s/size", block_name);

    if (!read_first_number(probe, &sectors) || sectors <= 0.0) {
        return count;
    }

    snprintf(out[count].path, sizeof(out[count].path), "/sys/block/%s", block_name);

    snprintf(out[count].name, sizeof(out[count].name), "%s", block_name);

    return count + 1;
}

/**
 * 枚举磁盘：扫 /sys/block，只留物理盘。
 *
 * 两道判据，缺一不可：
 * 1. 名字过滤（见 is_pseudo_block_name），先挡掉回环 / zram / dm / md 这类；
 * 2. 拓扑判据：/sys/block/<dev>/device 必须存在。这个节点是内核把块设备
 *    挂到真实控制器上的链接，虚拟块设备（dm-* / loop / zram）没有它。
 *    厂商自己起的虚拟盘名字不在前缀表里，只能靠这一条挡下来。
 * 另外容量为 0 的空槽（读卡器没插卡）不算一台磁盘，免得页面上多一个 0 MB 的空壳。
 *
 * 两趟走：枚举 + 常见块名的固定路径（sda/sdb、mmcblk0/1、nvme0n1、vda）。
 * 块名由内核按探测顺序分配，列不了目录时固定名字仍能 stat 到 device 与 size，
 * 这样"目录读不了"就不会变成"这台机器没有磁盘"。
 *
 * 编号在**排序之后**给：readdir 顺序不保证跨帧一致，先把名字排序，
 * 再依次编 disk0 / disk1，客户端的"磁盘 0 / 磁盘 1"才不会两帧之间换位置。
 */
static int enumerate_disk_devices(TmDeviceRef *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    int count = 0;

    DIR *dir = opendir("/sys/block");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {
            count = disk_add_block(out, capacity, count, entry->d_name);
        }

        closedir(dir);
    }

    static const char *const fixed_blocks[] = {
        "sda",
        "sdb",
        "sdc",
        "mmcblk0",
        "mmcblk1",
        "nvme0n1",
        "nvme1n1",
        "vda",
        "vdb",
    };

    for (size_t i = 0; i < sizeof(fixed_blocks) / sizeof(fixed_blocks[0]); i++) {
        count = disk_add_block(out, capacity, count, fixed_blocks[i]);
    }

    // 按块名排序：枚举顺序跨帧稳定，界面上的"磁盘 0 / 磁盘 1"才不会来回换位
    if (count > 1) {
        qsort(out, (size_t) count, sizeof(TmDeviceRef), compare_disk_name);
    }

    for (int i = 0; i < count; i++) {

        // id 是片段内的稳定索引（disk0 / disk1），只用来查这一帧的数据；
        // 它跟着排序后的枚举位置走，不代表某一块具体的盘。
        // 物理身份由 node 承担（node=块名 sda），客户端按 node 认盘。
        snprintf(out[i].id, sizeof(out[i].id), "disk%d", i);

        ensure_device_name(out[i].name, (int) sizeof(out[i].name));
    }

    return count;
}

/** 枚举网卡：实现见文件后面的"网卡设备"一节（全接口枚举 + 角色分类）。 */

/**
 * DRM 显卡的厂商名：读 device/uevent 里的 PCI_ID=VVVV:DDDD，只看厂商号。
 *
 * 拿不到真正的型号（Android 内核不导出 marketing name），所以写成
 * "Intel Graphics" 这类厂商级名字 —— 页面右上角那一行至少能看出是哪一家。
 */
static void gpu_drm_vendor_name(const char *device_path, char *out, int capacity)
{
    snprintf(out, (size_t) capacity, "%s", "GPU");

    char path[256];

    snprintf(path, sizeof(path), "%s/device/uevent", device_path);

    char text[512];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return;
    }

    const char *cursor = strstr(text, "PCI_ID=");

    if (cursor == NULL) {
        return;
    }

    cursor += strlen("PCI_ID=");

    if (strlen(cursor) < 4) {
        return;
    }

    if (strncasecmp(cursor, "8086", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "Intel Graphics");
    } else if (strncasecmp(cursor, "10de", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "NVIDIA Graphics");
    } else if (strncasecmp(cursor, "1002", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "AMD Graphics");
    } else if (strncasecmp(cursor, "5143", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "Qualcomm Adreno GPU");
    } else if (strncasecmp(cursor, "1af4", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "Virtio GPU");
    }
}

/* ============================================================
 * 显卡发现（GPU Discovery）
 *
 * 为什么不能只认 /sys/class/drm/card0：Android 上显卡驱动分好几套体系，
 * 各家的节点位置不同；而且同一颗 GPU 常常同时出现在厂商节点与 DRM 节点下
 * （一颗 Adreno 既有 kgsl-3d0，又有 msm 的 card0）。只按 DRM 找会漏掉
 * KGSL 那套字段，只按厂商找又会漏掉纯 DRM 驱动的设备。
 *
 * 发现顺序（先专用的、后通用的，专用的信息更全）：
 *   1. 高通      /sys/class/kgsl/kgsl-3dN          KGSL，Adreno 专用
 *   2. 联发科/三星/Google /sys/class/misc/maliN     kbase，靠 SoC 判具体是谁
 *   3. ARM       /sys/bus/platform/devices 里名字带 mali 的设备（裸 Mali 平台设备）
 *   4. 通用 DRM  /sys/class/drm/cardN               panfrost / panthor / amdgpu / i915 …
 *
 * 去重：同一个 GPU 被两条路径发现时只留一条 —— 用 realpath 比对节点与它的
 * device 目录；高通还额外按厂商去重（kgsl 与 msm 的 card 是同一颗 GPU）。
 *
 * 厂商怎么认：专用节点直接定；Mali 要看 SoC 是谁（同一套 kbase 被 MTK / 三星 /
 * Google 都在用），读 /sys/devices/soc0/machine，再退回 getprop ro.soc.manufacturer；
 * DRM 看 device/driver 的驱动名与 uevent 里的 PCI 厂商号。
 * ============================================================ */

/** 文本清理与大小写无关的查找：实现在磁盘一节，这里先声明（同一套规则不重复写）。 */
static void trim_disk_text(char *text);

/**
 * 大小写无关的子串查找（只用于 SoC 名字这种小写比较）。
 *
 * 不用 strcasestr：它是 GNU 扩展，bionic 上有，但别的 libc 不一定有；
 * 这里自己拼一份，编译在任何 libc 上都不会冒隐式声明的警告。
 */
static bool gpu_text_contains(const char *text, const char *needle)
{
    if (text == NULL || needle == NULL || needle[0] == '\0') {
        return false;
    }

    char lower_text[256];

    int count = 0;

    for (; text[count] != '\0' && count < (int) sizeof(lower_text) - 1; count++) {
        lower_text[count] = (char) tolower((unsigned char) text[count]);
    }

    lower_text[count] = '\0';

    char lower_needle[32];

    int needle_length = 0;

    for (; needle[needle_length] != '\0' && needle_length < (int) sizeof(lower_needle) - 1;
         needle_length++) {
        lower_needle[needle_length] = (char) tolower((unsigned char) needle[needle_length]);
    }

    lower_needle[needle_length] = '\0';

    return strstr(lower_text, lower_needle) != NULL;
}

/** 大小写无关地找子串，返回命中位置（找不到返回 NULL）；用于在型号里找 Adreno。 */
static const char *gpu_case_find(const char *text, const char *needle)
{
    if (text == NULL || needle == NULL || needle[0] == '\0') {
        return NULL;
    }

    size_t length = strlen(needle);

    for (const char *cursor = text; *cursor != '\0'; cursor++) {

        if (strncasecmp(cursor, needle, length) == 0) {
            return cursor;
        }
    }

    return NULL;
}

/**
 * 高通显卡的规范名：把内核导出的 gpu_model 拼成统一的写法。
 *
 * 内核给的原文什么样都有："Adreno735"、"Adreno (TM) 740"、"KGSL: Adreno 650"，
 * 也有根本不是 Adreno 的（自研代号 JSBY-SFF23 这种）。规则按用户定的来：
 *
 *   1. 原文里有 Adreno —— 取 Adreno 之后的部分当型号尾（去掉 (TM) / (R) 与空格），
 *      前面统一写 Qualcomm(R) Adreno(TM)，得到 "Qualcomm(R) Adreno(TM) 735"；
 *   2. 原文里没有 Adreno —— 不硬套 Adreno，写 "Qualcomm(R) <原文>"，
 *      例如 "Qualcomm(R) JSBY-SFF23"；
 *   3. 原文为空（内核没导出这一格）—— 写 "Qualcomm(R) Adreno(TM)"，不带型号。
 *
 * 为什么只改写法不动内容：型号（735 / 740）是内核给的事实，不能丢也不能编；
 * 这里只补厂商前缀、把 (TM)/(R) 归位，让它和 Windows 那种"厂商 + 系列 + 型号"
 * 的写法一致。
 */
static void gpu_qualcomm_model_name(const char *model, char *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return;
    }

    char text[64];

    snprintf(text, sizeof(text), "%s", (model != NULL) ? model : "");

    trim_disk_text(text);

    const char *adreno = gpu_case_find(text, "adreno");

    if (adreno == NULL) {

        // 不是 Adreno 系列：只加厂商前缀，型号原样保留
        if (text[0] != '\0') {
            snprintf(out, (size_t) capacity, "Qualcomm(R) %s", text);
        } else {
            snprintf(out, (size_t) capacity, "%s", "Qualcomm(R) Adreno(TM)");
        }

        return;
    }

    const char *tail = adreno + strlen("adreno");

    // 跳过 Adreno 与型号之间的分隔：空格、(TM)、(R)、单独的 TM
    bool trimmed = true;

    while (trimmed) {

        trimmed = false;

        while (*tail == ' ' || *tail == '\t') {
            tail++;
        }

        if (strncasecmp(tail, "(TM)", 4) == 0) {
            tail += 4;
            trimmed = true;
        } else if (strncasecmp(tail, "(R)", 3) == 0) {
            tail += 3;
            trimmed = true;
        } else if (strncasecmp(tail, "TM", 2) == 0) {
            tail += 2;
            trimmed = true;
        }
    }

    while (*tail == ' ' || *tail == '\t') {
        tail++;
    }

    // 尾部空白也去掉（内核文件常带换行，已经 trim 过，这里防型号后面还有空格）
    char tail_text[48];

    snprintf(tail_text, sizeof(tail_text), "%s", tail);

    trim_disk_text(tail_text);

    if (tail_text[0] != '\0') {
        snprintf(out, (size_t) capacity, "Qualcomm(R) Adreno(TM) %s", tail_text);
    } else {
        snprintf(out, (size_t) capacity, "%s", "Qualcomm(R) Adreno(TM)");
    }
}

/** 厂商的协议写法：单行无空格，客户端可以直接当标签显示。 */
static const char *gpu_vendor_text(int vendor)
{
    switch (vendor) {

        case TM_GPU_VENDOR_QUALCOMM:
            return "QUALCOMM";

        case TM_GPU_VENDOR_MEDIATEK:
            return "MEDIATEK";

        case TM_GPU_VENDOR_SAMSUNG:
            return "SAMSUNG";

        case TM_GPU_VENDOR_GOOGLE:
            return "GOOGLE";

        case TM_GPU_VENDOR_ARM:
            return "ARM";

        case TM_GPU_VENDOR_IMAGINATION:
            return "IMAGINATION";

        case TM_GPU_VENDOR_NVIDIA:
            return "NVIDIA";

        case TM_GPU_VENDOR_AMD:
            return "AMD";

        case TM_GPU_VENDOR_INTEL:
            return "INTEL";

        case TM_GPU_VENDOR_VIRTIO:
            return "VIRTIO";

        default:
            return "OTHER";
    }
}

/**
 * SoC 是哪一家做的（只在认 Mali 的归属时用得上）。
 *
 * 依据两条，都是系统自己声明的事实：
 *   /sys/devices/soc0/machine        例如 "Qualcomm Technologies, Inc SM8550"
 *                                   或 "MT6893"、"EXYNOS9610"、"Tensor"
 *   getprop ro.soc.manufacturer      Android 的属性，值形如 Qualcomm / MediaTek / Samsung / Google
 *
 * 结果在进程内只算一次：SoC 不会中途换。
 */
static int gpu_soc_vendor(void)
{
    static bool cached = false;

    static int cached_vendor = TM_GPU_VENDOR_UNKNOWN;

    if (cached) {
        return cached_vendor;
    }

    cached = true;

    char text[256];

    text[0] = '\0';

    read_text_file("/sys/devices/soc0/machine", text, (int) sizeof(text));

    if (text[0] == '\0') {
        read_text_file("/sys/devices/soc0/family", text, (int) sizeof(text));
    }

    char prop[128];

    prop[0] = '\0';

    FILE *pipe = popen("getprop ro.soc.manufacturer 2>/dev/null", "r");

    if (pipe != NULL) {

        if (fgets(prop, (int) sizeof(prop), pipe) == NULL) {
            prop[0] = '\0';
        }

        pclose(pipe);
    }

    if (gpu_text_contains(text, "qualcomm") || gpu_text_contains(text, "snapdragon")
        || gpu_text_contains(prop, "qualcomm")) {

        cached_vendor = TM_GPU_VENDOR_QUALCOMM;

    } else if (gpu_text_contains(text, "mediatek") || gpu_text_contains(text, "mt6")
               || gpu_text_contains(text, "mt8") || gpu_text_contains(text, "mt9")
               || gpu_text_contains(prop, "mediatek")) {

        cached_vendor = TM_GPU_VENDOR_MEDIATEK;

    } else if (gpu_text_contains(text, "exynos") || gpu_text_contains(text, "samsung")
               || gpu_text_contains(prop, "samsung")) {

        cached_vendor = TM_GPU_VENDOR_SAMSUNG;

    } else if (gpu_text_contains(text, "tensor") || gpu_text_contains(text, "gs101")
               || gpu_text_contains(text, "gs201") || gpu_text_contains(text, "zum")
               || gpu_text_contains(prop, "google")) {

        cached_vendor = TM_GPU_VENDOR_GOOGLE;
    }

    return cached_vendor;
}

/** 读一条符号链接的最后一段（<node>/device/driver -> mali）。空串表示没有。 */
static void gpu_link_name(const char *path, char *out, int capacity)
{
    out[0] = '\0';

    char target[TM_PATH_MAX];

    target[0] = '\0';

    if (readlink(path, target, sizeof(target) - 1) <= 0) {
        return;
    }

    target[sizeof(target) - 1] = '\0';

    const char *slash = strrchr(target, '/');

    snprintf(out, (size_t) capacity, "%s", (slash != NULL) ? (slash + 1) : target);
}

/** 规范化后的真实路径：读不到就原样返回，保证调用方总有可比对的字符串。 */
static void gpu_real_path(const char *path, char *out, int capacity)
{
    char *resolved = realpath(path, NULL);

    if (resolved != NULL) {

        snprintf(out, (size_t) capacity, "%s", resolved);

        free(resolved);

        return;
    }

    snprintf(out, (size_t) capacity, "%s", path);
}

/** 已登记节点的去重表：最多四张卡，每条记两个路径（节点本身 + 它的 device）。 */
typedef struct {
    char path[TM_OUT_GPU_MAX * 2][TM_PATH_MAX];
    int count;
} TmGpuSeen;

static bool gpu_seen_has(const TmGpuSeen *seen, const char *real_path)
{
    for (int i = 0; i < seen->count; i++) {

        if (strcmp(seen->path[i], real_path) == 0) {
            return true;
        }
    }

    return false;
}

static void gpu_seen_add(TmGpuSeen *seen, const char *real_path)
{
    if (seen->count >= (int) (sizeof(seen->path) / sizeof(seen->path[0]))) {
        return;
    }

    snprintf(seen->path[seen->count], sizeof(seen->path[0]), "%s", real_path);

    seen->count++;
}

/**
 * 登记一张卡：去重后追加。
 *
 * 去重用的是"真实路径"：/sys/class/misc/mali0/device 与
 * /sys/bus/platform/devices/13000000.mali 指向同一个平台设备，只登记一次。
 *
 * @return 追加后的新条数（被去重时原样返回）
 */
static int gpu_add(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen,
                   const char *node, const char *name, int vendor)
{
    if (count >= capacity) {
        return count;
    }

    char node_real[TM_PATH_MAX];

    gpu_real_path(node, node_real, (int) sizeof(node_real));

    if (gpu_seen_has(seen, node_real)) {
        return count;
    }

    char device_path[TM_PATH_MAX];

    snprintf(device_path, sizeof(device_path), "%s/device", node);

    char device_real[TM_PATH_MAX];

    gpu_real_path(device_path, device_real, (int) sizeof(device_real));

    if (strcmp(device_real, device_path) != 0 && gpu_seen_has(seen, device_real)) {
        return count;
    }

    // id 是稳定索引（gpu0 / gpu1）：哪张卡是哪个由 node 说明
    snprintf(out[count].id, sizeof(out[count].id), "gpu%d", count);

    snprintf(out[count].path, sizeof(out[count].path), "%s", node);

    snprintf(out[count].name, sizeof(out[count].name), "%s", name);

    out[count].vendor = vendor;

    ensure_device_name(out[count].name, (int) sizeof(out[count].name));

    gpu_seen_add(seen, node_real);

    if (strcmp(device_real, device_path) != 0) {
        gpu_seen_add(seen, device_real);
    }

    return count + 1;
}

/**
 * 登记一个 KGSL 3D 节点：枚举与固定路径两趟共用同一套"读型号 + 加入"。
 *
 * stat 只要求路径上的 search 权限，不读目录内容 —— 这正是"目录枚举被 SELinux
 * 挡住、单个节点仍可访问"时还能确认节点存在的原因。节点在、字段读不出来，
 * 由下游各字段各自报状态，不在这里提前放弃。
 */
static int gpu_register_kgsl_node(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen,
                                  const char *path)
{
    struct stat info;

    if (stat(path, &info) != 0) {
        return count;
    }

    // gpu_model 是高通导出的芯片名（Adreno735 / Adreno (TM) 740 这种）。
    // 原文写法不统一，这里过一遍规范名函数，统一成
    // "Qualcomm(R) Adreno(TM) 735"；不是 Adreno 的型号只加厂商前缀。
    // 读不出来就用空型号 —— 名字退成 "Qualcomm(R) Adreno(TM)"，设备照样登记。
    char model[64];

    model[0] = '\0';

    char model_path[TM_PATH_MAX];

    snprintf(model_path, sizeof(model_path), "%s/gpu_model", path);

    read_text_file(model_path, model, (int) sizeof(model));

    trim_disk_text(model);

    char display[TM_OUT_VALUE_MAX];

    gpu_qualcomm_model_name(model, display, (int) sizeof(display));

    return gpu_add(out, capacity, count, seen, path, display, TM_GPU_VENDOR_QUALCOMM);
}

/**
 * 1) 高通：kgsl-3dN（3D 图形核；kgsl-2d 是 2D 合成，不算显卡）。
 *
 * 两条路都走，谁也替代不了谁：
 *   ① 枚举 /sys/class/kgsl —— 内核里到底挂了几个 3D 核，只有列目录才知道；
 *   ② 固定路径 kgsl-3d0 .. kgsl-3d3 —— 枚举依赖"读目录"（readdir）权限，
 *      某些 SELinux 域（典型是 adb shell）允许打开目录里的具体文件、却不允许
 *      读目录列表：opendir 直接失败，可 gpubusy 明明读得动。少了这一步，
 *      就会把一颗能读到利用率的 Adreno 误判成"这台机器没有 GPU"。
 *
 * 两条路的结果按真实路径去重。只有 stat 失败才算"节点不存在"；
 * 真读不出数据时，逐字段报状态，那才是结论。
 */
static int gpu_discover_kgsl(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen)
{
    DIR *dir = opendir("/sys/class/kgsl");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "kgsl-3d", 7) != 0) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "/sys/class/kgsl/%s", entry->d_name);

            count = gpu_register_kgsl_node(out, capacity, count, seen, path);
        }

        closedir(dir);
    }

    static const char *const fixed_nodes[] = {
        "/sys/class/kgsl/kgsl-3d0",
        "/sys/class/kgsl/kgsl-3d1",
        "/sys/class/kgsl/kgsl-3d2",
        "/sys/class/kgsl/kgsl-3d3",
    };

    for (size_t i = 0; i < sizeof(fixed_nodes) / sizeof(fixed_nodes[0]); i++) {
        count = gpu_register_kgsl_node(out, capacity, count, seen, fixed_nodes[i]);
    }

    return count;
}

/**
 * 登记一个 kbase 节点（/sys/class/misc/maliN）：枚举与固定路径共用。
 * gpuinfo 是 kbase 的版本行：Mali-G57 5 cores r0p1 0x9091。
 */
static int gpu_register_mali_misc_node(TmDeviceRef *out, int capacity, int count,
                                       TmGpuSeen *seen, const char *path, int vendor)
{
    struct stat info;

    if (stat(path, &info) != 0) {
        return count;
    }

    char info_text[64];

    info_text[0] = '\0';

    char info_path[TM_PATH_MAX];

    snprintf(info_path, sizeof(info_path), "%s/device/gpuinfo", path);

    read_text_file(info_path, info_text, (int) sizeof(info_text));

    trim_disk_text(info_text);

    if (info_text[0] == '\0') {

        // 有的内核把 gpuinfo 直接摆在 misc 节点下，不挂在 device 里
        snprintf(info_path, sizeof(info_path), "%s/gpuinfo", path);

        read_text_file(info_path, info_text, (int) sizeof(info_text));

        trim_disk_text(info_text);
    }

    return gpu_add(out, capacity, count, seen, path,
                   info_text[0] != '\0' ? info_text : "ARM Mali GPU", vendor);
}

/**
 * 2) Mali 系：/sys/class/misc 下的 maliN。
 *
 * 与 KGSL 同理两趟走：枚举 + mali0 / mali1 固定路径。
 * 目录枚举被挡住时，固定名字照样能取到 gpuinfo —— 能读到就说明这块 GPU 在。
 */
static int gpu_discover_mali_misc(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen)
{
    int vendor = gpu_soc_vendor();

    if (vendor == TM_GPU_VENDOR_UNKNOWN || vendor == TM_GPU_VENDOR_QUALCOMM) {
        vendor = TM_GPU_VENDOR_ARM;
    }

    DIR *dir = opendir("/sys/class/misc");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "mali", 4) != 0) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "/sys/class/misc/%s", entry->d_name);

            count = gpu_register_mali_misc_node(out, capacity, count, seen, path, vendor);
        }

        closedir(dir);
    }

    static const char *const fixed_nodes[] = {
        "/sys/class/misc/mali0",
        "/sys/class/misc/mali1",
    };

    for (size_t i = 0; i < sizeof(fixed_nodes) / sizeof(fixed_nodes[0]); i++) {
        count = gpu_register_mali_misc_node(out, capacity, count, seen, fixed_nodes[i], vendor);
    }

    return count;
}

/**
 * 3) Mali 的平台设备：/sys/bus/platform/devices 下名字带 mali 的那个
 *    （13000000.mali / soc:mali 这种，由 DTS 决定）。
 *
 * 枚举 + 固定兜底：平台设备名没法预先知道，但 kbase 一定会在 /sys/class/misc
 * 下挂 maliN，maliN/device 就是那个平台设备 —— 用它可以绕过 platforms 目录枚举。
 */
static int gpu_discover_mali_platform(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen)
{
    int vendor = gpu_soc_vendor();

    if (vendor == TM_GPU_VENDOR_UNKNOWN || vendor == TM_GPU_VENDOR_QUALCOMM) {
        vendor = TM_GPU_VENDOR_ARM;
    }

    DIR *dir = opendir("/sys/bus/platform/devices");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strstr(entry->d_name, "mali") == NULL) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "/sys/bus/platform/devices/%s", entry->d_name);

            struct stat info;

            if (stat(path, &info) != 0) {
                continue;
            }

            count = gpu_add(out, capacity, count, seen, path, "ARM Mali GPU", vendor);
        }

        closedir(dir);
    }

    static const char *const fixed_nodes[] = {
        "/sys/class/misc/mali0/device",
        "/sys/class/misc/mali1/device",
        "/sys/bus/platform/devices/mali",
        "/sys/bus/platform/devices/mali0",
    };

    for (size_t i = 0; i < sizeof(fixed_nodes) / sizeof(fixed_nodes[0]); i++) {

        struct stat info;

        if (stat(fixed_nodes[i], &info) != 0) {
            continue;
        }

        count = gpu_add(out, capacity, count, seen, fixed_nodes[i], "ARM Mali GPU", vendor);
    }

    return count;
}

/** 现有登记表里有没有某厂商的卡（避免同一颗 GPU 被 KGSL 与 msm 各记一次）。 */
static bool gpu_has_vendor(const TmDeviceRef *out, int count, int vendor)
{
    if (out == NULL) {
        return false;
    }

    for (int k = 0; k < count; k++) {

        if (out[k].vendor == vendor) {
            return true;
        }
    }

    return false;
}

/** DRM 驱动名 -> 厂商；认不出来返回 UNKNOWN（卡片仍然登记，名字退成 "GPU"）。 */
static int gpu_vendor_of_driver(const char *driver)
{
    if (strcasecmp(driver, "amdgpu") == 0 || strcasecmp(driver, "radeon") == 0) {
        return TM_GPU_VENDOR_AMD;
    }

    if (strcasecmp(driver, "nvidia") == 0 || strcasecmp(driver, "nouveau") == 0
        || strcmp(driver, "nvidia-drm") == 0) {
        return TM_GPU_VENDOR_NVIDIA;
    }

    if (strcasecmp(driver, "i915") == 0 || strcasecmp(driver, "xe") == 0) {
        return TM_GPU_VENDOR_INTEL;
    }

    if (strcasecmp(driver, "msm") == 0 || strcasecmp(driver, "msm_drm") == 0
        || strcasecmp(driver, "kgsl") == 0) {
        return TM_GPU_VENDOR_QUALCOMM;
    }

    if (strcasecmp(driver, "virtio_gpu") == 0) {
        return TM_GPU_VENDOR_VIRTIO;
    }

    if (strcasecmp(driver, "panfrost") == 0 || strcasecmp(driver, "panthor") == 0
        || strcasecmp(driver, "kbase") == 0) {
        int vendor = gpu_soc_vendor();
        return (vendor == TM_GPU_VENDOR_UNKNOWN || vendor == TM_GPU_VENDOR_QUALCOMM)
               ? TM_GPU_VENDOR_ARM : vendor;
    }

    if (strcasecmp(driver, "pvrsrvkm") == 0 || strcasecmp(driver, "powervr") == 0) {
        return TM_GPU_VENDOR_IMAGINATION;
    }

    if (strcasecmp(driver, "exynos") == 0 || strstr(driver, "exynos") != NULL) {
        return TM_GPU_VENDOR_SAMSUNG;
    }

    return TM_GPU_VENDOR_UNKNOWN;
}

/**
 * 登记一个 DRM cardN：枚举与固定路径两趟共用。
 *
 * 驱动名读不出来（权限 / 老内核）也照样登记：name 退成 "GPU" 总比整页空白强，
 * 具体字段读不到由下游各自报状态。
 */
static int gpu_register_drm_card(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen,
                                 const char *path)
{
    struct stat info;

    if (stat(path, &info) != 0) {
        return count;
    }

    char driver[64];

    driver[0] = '\0';

    char driver_link[TM_PATH_MAX];

    snprintf(driver_link, sizeof(driver_link), "%s/device/driver", path);

    gpu_link_name(driver_link, driver, (int) sizeof(driver));

    int vendor = gpu_vendor_of_driver(driver);

    // 高通：KGSL 那一趟通常已经登记过同一颗 GPU，这里不再追加；
    // 但老内核 / kgsl 整个不可读时，msm 的 cardN 是唯一线索，必须收下。
    if (vendor == TM_GPU_VENDOR_QUALCOMM
        && gpu_has_vendor(out, count, TM_GPU_VENDOR_QUALCOMM)) {
        return count;
    }

    char name[64];

    gpu_drm_vendor_name(path, name, (int) sizeof(name));

    if (strcmp(name, "GPU") == 0 && driver[0] != '\0') {
        snprintf(name, sizeof(name), "%s (%s)", gpu_vendor_text(vendor), driver);
    }

    return gpu_add(out, capacity, count, seen, path, name, vendor);
}

/**
 * 5) Linux 官方 DRM：/sys/class/drm 下的 cardN。
 *    renderD* 与 cardN 是同一个设备，只按 cardN 登记；
 *    cardN-XXX 是接口连接器，不是显卡。
 *
 * 枚举 + card0..card3 固定路径：DRM 类目录同样是 readdir 的目标，
 * 枚举被挡时固定编号仍然 stat 得到，读到驱动名就认得出厂商。
 */
static int gpu_discover_drm(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen)
{
    DIR *dir = opendir("/sys/class/drm");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "card", 4) != 0) {
                continue;
            }

            // cardN 后面可能跟 "-HDMI-A-1" 这类连接器，只认纯数字的卡本身
            if (!is_pure_digits(entry->d_name + 4)) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "/sys/class/drm/%s", entry->d_name);

            count = gpu_register_drm_card(out, capacity, count, seen, path);
        }

        closedir(dir);
    }

    static const char *const fixed_cards[] = {
        "/sys/class/drm/card0",
        "/sys/class/drm/card1",
        "/sys/class/drm/card2",
        "/sys/class/drm/card3",
    };

    for (size_t i = 0; i < sizeof(fixed_cards) / sizeof(fixed_cards[0]); i++) {
        count = gpu_register_drm_card(out, capacity, count, seen, fixed_cards[i]);
    }

    return count;
}

/**
 * 枚举图形设备：四趟发现，前面的结果占位，后面的只补空缺。
 *
 * 上限 TM_OUT_GPU_MAX：手机 / 平板最多一两颗 GPU，四张是给 PCI 多卡留的余量。
 */
static int enumerate_gpu_devices(TmDeviceRef *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    TmGpuSeen seen;

    memset(&seen, 0, sizeof(seen));

    int count = 0;

    count = gpu_discover_kgsl(out, capacity, count, &seen);

    count = gpu_discover_mali_misc(out, capacity, count, &seen);

    count = gpu_discover_mali_platform(out, capacity, count, &seen);

    count = gpu_discover_drm(out, capacity, count, &seen);

    return count;
}

/**
 * 网卡枚举的实现在文件后面的"网卡设备"一节（全接口枚举 + 角色分类）：
 * 这里先声明，registry_refresh 才敢在这里先调。
 */
static int enumerate_network_devices(TmDeviceRef *out, int capacity);

/**
 * 枚举全部设备实例，填进这张表。
 *
 * 一次请求只调一次：决定一次采集周期里 DeviceId 与硬件的对应关系，
 * 采集过程中不允许重枚举，否则第 0 台 CPU 可能中途变成另一颗芯片。
 *
 * 非 CPU 的几类：磁盘 / 网卡各自有专门的 build_* 函数补字段，
 * GPU 这一版只报名称（温度 / 显存还没定口径）。
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
 *
 * 目前没有调用点（虚拟化探测已改成只看 /dev 节点，见 probe_virtualization_internal），
 * 保留是因为以后若要判"硬件支持但内核没启用"还会用它；
 * unused 属性只是压掉 -Wunused-function 的告警，不代表它不该留。
 */
__attribute__((unused))
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

// ============================================================
// SoC 名称库（CPUNAME.txt）与厂商规范化
// ============================================================
//
// 内核给的名字经常不能用：ARM 设备的 /proc/cpuinfo 里 Hardware 可能是空的，
// 也可能只写一个 cpu。这种时候改走 Android 侧读到的 SoC 型号：
//   ro.soc.model          SM8635
//   ro.soc.manufacturer   QTI
// 这两个值连同名称库路径，都由 Kotlin 侧在**每次设备快照请求**里带进来
// （见文件头第二节的 typeCode=5 附加参数），不再走进程启动参数：
// 这样设备模型只有一个维护入口，上层数据随时能刷新，本进程也不必重启。
//
// 本块只做三件事：查库、规范化厂商、拼最终展示名。
// 不读系统属性、不猜平台 —— 型号与厂商都是别人给的事实。

/**
 * 名称库路径（请求键 cpu_name_db，最多两个）。
 *
 * Kotlin 侧会同时给两份：外部私有目录一份（shell / root 身份读得到），
 * 应用内部 files 目录一份（应用自身身份读得到）。
 * 查表时按给的顺序试，第一个读得开的为准 —— 换身份起进程也不需要换路径。
 */
static char g_cpu_name_database[2][TM_PATH_MAX];

/** 实际给了几条名称库路径（0 = 没有库，只能回退 厂商 + 大写型号）。 */
static int g_cpu_name_database_count = 0;

/** SoC 型号标识（请求键 soc_model），比较时不分大小写，回退拼名时转大写。 */
static char g_soc_model[TM_OUT_VALUE_MAX] = {0};

/** SoC 厂商原始值（请求键 soc_manufacturer），可能形如 QTI / QCOM / MTK / SAMSUNG。 */
static char g_soc_manufacturer[TM_OUT_VALUE_MAX] = {0};

/**
 * 上下文是否变过（名称探测的缓存失效标记）。
 *
 * 快照每帧都会把这三个值带上，但值本身开机不会变；逐帧重读 cpuinfo 与库文件
 * 纯属浪费，所以只在值真的换了才让下次探测重算。
 */
static bool g_cpu_name_context_dirty = true;

/**
 * ASCII 小写。
 * 只认 A-Z：型号与厂商都是 ASCII，中文名字里的字节原样返回，不会被改坏。
 */
static char ascii_lower(char c)
{
    if (c >= 'A' && c <= 'Z') {
        return (char) (c + ('a' - 'A'));
    }

    return c;
}

/** ASCII 大写，规则同上。 */
static char ascii_upper(char c)
{
    if (c >= 'a' && c <= 'z') {
        return (char) (c - ('a' - 'A'));
    }

    return c;
}

/**
 * 一个十六进制字符转数值（大小写都认）。
 *
 * 只给请求参数的百分号转义用；不是十六进制字符返回 -1。
 */
static int ascii_hex_digit(char c)
{
    if (c >= '0' && c <= '9') {
        return (int) (c - '0');
    }

    if (c >= 'a' && c <= 'f') {
        return (int) (c - 'a') + 10;
    }

    if (c >= 'A' && c <= 'F') {
        return (int) (c - 'A') + 10;
    }

    return -1;
}

/** 两个字符串忽略大小写是否相等；任一为 NULL 都不相等。 */
static bool text_equal_ignore_case(const char *left, const char *right)
{
    if (left == NULL || right == NULL) {
        return false;
    }

    while (*left != '\0' && *right != '\0') {

        if (ascii_lower(*left) != ascii_lower(*right)) {
            return false;
        }

        left++;
        right++;
    }

    return *left == *right;
}

/**
 * 内核给的这行字是不是一个「能给人看的」处理器名。
 *
 * 空、纯空白、cpu、unknown、armv8、0x0 都算没拿到 —— 安卓设备上这些值很常见，
 * 直接显示出来比显示占位还难看，所以一律当作失败，交给 SoC 那一套去补。
 */
static bool cpu_name_is_usable(const char *text)
{
    if (text == NULL) {
        return false;
    }

    while (*text == ' ' || *text == '\t') {
        text++;
    }

    if (*text == '\0') {
        return false;
    }

    char trimmed[TM_OUT_VALUE_MAX];

    snprintf(trimmed, sizeof(trimmed), "%s", text);

    size_t length = strlen(trimmed);

    while (length > 0) {

        char tail = trimmed[length - 1];

        if (tail != ' ' && tail != '\t' && tail != '\n' && tail != '\r') {
            break;
        }

        trimmed[--length] = '\0';
    }

    if (length == 0) {
        return false;
    }

    if (text_equal_ignore_case(trimmed, "cpu")
        || text_equal_ignore_case(trimmed, "unknown")
        || text_equal_ignore_case(trimmed, "armv8")
        || text_equal_ignore_case(trimmed, "armv7")
        || text_equal_ignore_case(trimmed, "0x0")) {

        return false;
    }

    return true;
}

/**
 * text 是否以 prefix 开头（忽略大小写），prefix 的有效长度是 prefix_length。
 */
static bool text_prefix_ignore_case(const char *text, const char *prefix, size_t prefix_length)
{
    for (size_t index = 0; index < prefix_length; index++) {

        if (text[index] == '\0') {
            return false;
        }

        if (tolower((unsigned char) text[index]) != tolower((unsigned char) prefix[index])) {
            return false;
        }
    }

    return true;
}

/**
 * 在 CPUNAME.txt 里按型号查商品名。
 *
 * 文件格式（UTF-8，一型号一行）：
 *   型号 展示名
 * 例如：
 *   sm8635 Qualcomm(R) Snapdragon(R) 8s Gen 3
 * 约定：# 开头是注释，空行忽略，型号不分大小写，
 * 第一个空格之后的整段（含空格）都是展示名。
 *
 * 匹配分两档，整词优先：
 *   1) 整词相等：型号完全对得上，最高优先级；
 *   2) 前缀命中：型号后面还挂着变体尾巴（字母 / 斜杠 / 连字符 / 下划线），
 *      取库里最长的那个前缀。联发科的变体写法（MT6833V/NZA、MT6877V/TTZA、
 *      MT6899Z/ZA）就靠这一档吃下来。数字尾巴不算变体，免得 MT6833 吃掉
 *      一个并不存在的 MT6833x。
 *
 * @param model     要查的型号，例如 SM8635
 * @param out       查到的展示名（调用方给的缓冲）
 * @param capacity  out 的字节数
 * @return true = 命中，out 里是展示名；false = 没库 / 读不了 / 没这个型号
 */
static bool cpu_name_database_lookup(const char *model, char *out, size_t capacity)
{
    out[0] = '\0';

    if (g_cpu_name_database_count == 0 || model == NULL || model[0] == '\0') {
        return false;
    }

    size_t model_length = strlen(model);

    // 前缀命中只是兜底，整词命中要压过它：先扫完所有库文件，
    // 前缀只记最长的那个，整词命中当场定格。
    size_t best_prefix_length = 0;
    char best_name[256];

    best_name[0] = '\0';

    for (int file_index = 0; file_index < g_cpu_name_database_count; file_index++) {

        const char *path = g_cpu_name_database[file_index];

        if (path[0] == '\0' || access(path, R_OK) != 0) {
            continue;
        }

        FILE *file = fopen(path, "r");

        if (file == NULL) {
            continue;
        }

        char line[512];
        bool exact = false;

        while (!exact && fgets(line, sizeof(line), file) != NULL) {

            size_t length = strlen(line);

            while (length > 0) {

                char tail = line[length - 1];

                if (tail != '\n' && tail != '\r') {
                    break;
                }

                line[--length] = '\0';
            }

            char *cursor = line;

            while (*cursor == ' ' || *cursor == '\t') {
                cursor++;
            }

            if (*cursor == '\0' || *cursor == '#') {
                continue;
            }

            // 第一段是型号
            char *token_start = cursor;

            while (*cursor != '\0' && *cursor != ' ' && *cursor != '\t') {
                cursor++;
            }

            char token[64];
            size_t token_length = (size_t) (cursor - token_start);

            if (token_length == 0 || token_length >= sizeof(token)) {
                continue;
            }

            memcpy(token, token_start, token_length);

            token[token_length] = '\0';

            while (*cursor == ' ' || *cursor == '\t') {
                cursor++;
            }

            if (*cursor == '\0') {
                continue;
            }

            if (text_equal_ignore_case(token, model)) {

                snprintf(best_name, sizeof(best_name), "%s", cursor);

                exact = true;

                continue;
            }

            // 变体尾巴的判定：库里的型号得比要查的短、至少 4 个字符、
            // 要查的型号在分界那一位不是数字，再比前缀，取最长的
            if (token_length >= 4 && token_length < model_length
                && !isdigit((unsigned char) model[token_length])
                && token_length > best_prefix_length
                && text_prefix_ignore_case(model, token, token_length)) {

                best_prefix_length = token_length;

                snprintf(best_name, sizeof(best_name), "%s", cursor);
            }
        }

        fclose(file);

        if (exact) {

            snprintf(out, capacity, "%s", best_name);

            return true;
        }
    }

    if (best_prefix_length > 0) {

        snprintf(out, capacity, "%s", best_name);

        return true;
    }

    return false;
}

/**
 * 规范厂商名：QTI / QCOM / Qualcomm 统一成 Qualcomm(R)，MTK 统一成 MediaTek。
 *
 * (R) 用半角括号写在名字里，不依赖字体里的 ® 字形：
 * 这一层的输出要进复制文本、要进等宽显示，纯 ASCII 最稳。
 * 认不出来的厂商原样保留 —— 不要给它硬套一个商标。
 */
static void normalize_soc_manufacturer(const char *manufacturer, char *out, size_t capacity)
{
    out[0] = '\0';

    if (manufacturer == NULL || manufacturer[0] == '\0') {
        return;
    }

    if (text_equal_ignore_case(manufacturer, "QTI")
        || text_equal_ignore_case(manufacturer, "QCOM")
        || text_equal_ignore_case(manufacturer, "Qualcomm")
        || text_equal_ignore_case(manufacturer, "Qualcomm Technologies, Inc")) {

        snprintf(out, capacity, "%s", "Qualcomm(R)");

        return;
    }

    if (text_equal_ignore_case(manufacturer, "MTK")
        || text_equal_ignore_case(manufacturer, "MediaTek")) {

        snprintf(out, capacity, "%s", "MediaTek");

        return;
    }

    if (text_equal_ignore_case(manufacturer, "SAMSUNG")) {

        snprintf(out, capacity, "%s", "Samsung");

        return;
    }

    if (text_equal_ignore_case(manufacturer, "GOOGLE")) {

        snprintf(out, capacity, "%s", "Google");

        return;
    }

    snprintf(out, capacity, "%s", manufacturer);
}

/**
 * 拼 SoC 的最终展示名。
 *
 * 顺序：
 *   1. 名称库命中 → 直接用库里的商品名（Qualcomm(R) Snapdragon(R) 8s Gen 3）；
 *   2. 没命中 → 规范化厂商 + 空格 + 大写型号（Qualcomm(R) SM8635）；
 *   3. 厂商也没有 → 只给大写型号。
 *
 * @return false = 连型号都没有，调用方按没数据发出去
 */
static bool build_soc_display_name(char *out, size_t capacity)
{
    out[0] = '\0';

    if (g_soc_model[0] == '\0') {
        return false;
    }

    if (cpu_name_database_lookup(g_soc_model, out, capacity)) {
        return true;
    }

    char manufacturer[TM_OUT_VALUE_MAX];

    normalize_soc_manufacturer(g_soc_manufacturer, manufacturer, sizeof(manufacturer));

    char model_upper[TM_OUT_VALUE_MAX];

    size_t length = strlen(g_soc_model);

    if (length >= sizeof(model_upper)) {
        length = sizeof(model_upper) - 1;
    }

    for (size_t index = 0; index < length; index++) {
        model_upper[index] = ascii_upper(g_soc_model[index]);
    }

    model_upper[length] = '\0';

    if (manufacturer[0] != '\0') {

        snprintf(out, capacity, "%s %s", manufacturer, model_upper);

    } else {

        snprintf(out, capacity, "%s", model_upper);
    }

    return out[0] != '\0';
}

/**
 * 把请求里的一项取值解码出来。
 *
 * 协议用空格分字段，所以值里不能有空格：客户端把空格、百分号、等号写成 %XX，
 * 这里只认 %XX 一种转义，其余字符原样搬。
 *
 * @param input    键名之后的部分（可能为空）
 * @param out      解码结果
 * @param capacity out 的字节数；超长的尾部丢弃，不截半个转义序列
 */
static void decode_request_value(const char *input, char *out, size_t capacity)
{
    if (out == NULL || capacity == 0) {
        return;
    }

    size_t used = 0;

    for (const char *cursor = input; cursor != NULL && *cursor != '\0' && used + 1 < capacity; cursor++) {

        if (*cursor == '%' && cursor[1] != '\0' && cursor[2] != '\0') {

            int high = ascii_hex_digit(cursor[1]);
            int low = ascii_hex_digit(cursor[2]);

            if (high >= 0 && low >= 0) {

                out[used++] = (char) (high * 16 + low);

                cursor += 2;

                continue;
            }
        }

        out[used++] = *cursor;
    }

    out[used] = '\0';
}

/**
 * 接收一次设备快照请求的附加参数，刷新设备模型的 Android 层事实。
 *
 * 键名（都不含空格，值按百分号转义）：
 *   soc_model=SM8635
 *   soc_manufacturer=QTI
 *   cpu_name_db=<名称库绝对路径>      可给两次，按出现顺序试
 *   refresh_names=1                  只清名称缓存，不改上下文（进性能页那一下用）
 *
 * 语义是"请求即权威"：这次请求给了上下文键，就以它为全集重建 ——
 * 没给 soc_manufacturer 说明上层这次没拿到厂商，那就按没有处理，
 * 不沿用上一次的旧值（否则旧的错误值会一直粘着）。
 *
 * 上下文键一个都没带时什么都不改：老客户端、或只带 refresh_names 的请求，
 * 都保持上次上下文，不能把已有的库路径冲掉。
 *
 * @param arguments      附加参数数组，形如 键=值
 * @param argument_count 数组长度
 */
static void apply_device_context(const char *const *arguments, int argument_count)
{
    if (arguments == NULL || argument_count <= 0) {
        return;
    }

    static const char *const k_model_key = "soc_model";
    static const char *const k_manufacturer_key = "soc_manufacturer";
    static const char *const k_database_key = "cpu_name_db";
    static const char *const k_refresh_key = "refresh_names";

    char model[TM_OUT_VALUE_MAX];
    char manufacturer[TM_OUT_VALUE_MAX];
    char database[2][TM_PATH_MAX];

    model[0] = '\0';
    manufacturer[0] = '\0';

    int database_count = 0;

    // 这次请求有没有带"上下文"键（三条真数据）；refresh_names 不算
    bool context_given = false;

    bool refresh_requested = false;

    for (int index = 0; index < argument_count; index++) {

        const char *item = arguments[index];

        if (item == NULL) {
            continue;
        }

        const char *separator = strchr(item, '=');

        if (separator == NULL || separator == item) {
            continue;
        }

        size_t key_length = (size_t) (separator - item);

        const char *value = separator + 1;

        if (key_length == strlen(k_model_key) && strncmp(item, k_model_key, key_length) == 0) {

            decode_request_value(value, model, sizeof(model));

            context_given = true;

            continue;
        }

        if (key_length == strlen(k_manufacturer_key)
            && strncmp(item, k_manufacturer_key, key_length) == 0) {

            decode_request_value(value, manufacturer, sizeof(manufacturer));

            context_given = true;

            continue;
        }

        if (key_length == strlen(k_database_key) && strncmp(item, k_database_key, key_length) == 0) {

            if (database_count < (int) (sizeof(database) / sizeof(database[0]))) {

                decode_request_value(value, database[database_count], sizeof(database[0]));

                database_count++;

                context_given = true;
            }

            continue;
        }

        if (key_length == strlen(k_refresh_key) && strncmp(item, k_refresh_key, key_length) == 0) {

            // 只认 1：其它值当作没提出要求，免得客户端误传把缓存一直刷掉
            if (value[0] == '1' && value[1] == '\0') {
                refresh_requested = true;
            }
        }
    }

    // 要求重算的场合：只清名称缓存，不动上下文
    if (refresh_requested) {
        g_cpu_name_context_dirty = true;
    }

    // 没带上下文键：除了上面那一下，什么都不改
    if (!context_given) {
        return;
    }

    // 值没变就不动缓存：快照逐帧都带这几个键，没必要每帧重新读 cpuinfo 与库文件
    bool changed = strcmp(model, g_soc_model) != 0
                   || strcmp(manufacturer, g_soc_manufacturer) != 0
                   || database_count != g_cpu_name_database_count;

    for (int index = 0; index < database_count && !changed; index++) {

        if (strcmp(database[index], g_cpu_name_database[index]) != 0) {
            changed = true;
        }
    }

    if (!changed) {
        return;
    }

    snprintf(g_soc_model, sizeof(g_soc_model), "%s", model);

    snprintf(g_soc_manufacturer, sizeof(g_soc_manufacturer), "%s", manufacturer);

    g_cpu_name_database_count = database_count;

    for (int index = 0; index < database_count; index++) {

        snprintf(g_cpu_name_database[index], sizeof(g_cpu_name_database[0]), "%s",
                 database[index]);
    }

    for (int index = database_count;
         index < (int) (sizeof(g_cpu_name_database) / sizeof(g_cpu_name_database[0])); index++) {

        g_cpu_name_database[index][0] = '\0';
    }

    g_cpu_name_context_dirty = true;
}

/**
 * 内部探测：处理器商品名。
 *
 * 两条来源，按顺序试：
 *   1. 内核的 /proc/cpuinfo（ARM 看 Hardware，x86 看 model name）；
 *      这个值不像名字（空 / cpu / unknown / armv8）就当没拿到；
 *   2. Android 的 SoC 型号 + 名称库：先查 CPUNAME.txt，再回退 厂商 + 大写型号。
 *
 * 内核名恰好就是 SoC 型号时，也先拿它查一次库 —— 很多安卓设备只报 SM8635 这种，
 * 库里有商品名就该显示商品名，而不是把型号直接摆出来。
 *
 * 只读一次并缓存：同一份上下文里名字不会变，每帧读文件没必要；
 * 上下文换过（apply_device_context 标记 dirty）才重算。
 */
static TmQueryValue probe_cpu_name_internal(void)
{
    static bool probed = false;
    static TmQueryValue cached;

    if (probed && !g_cpu_name_context_dirty) {
        return cached;
    }

    probed = true;

    g_cpu_name_context_dirty = false;

    cached = query_value_none(TM_QUERY_UNSUPPORTED);

    char hardware[TM_OUT_VALUE_MAX];
    char model_name[TM_OUT_VALUE_MAX];

    hardware[0] = '\0';
    model_name[0] = '\0';

    bool cpuinfo_read = false;

    FILE *file = fopen("/proc/cpuinfo", "r");

    if (file != NULL) {

        cpuinfo_read = true;

        char line[256];

        while (fgets(line, sizeof(line), file) != NULL) {

            char value[TM_OUT_VALUE_MAX];

            if (hardware[0] == '\0' && strncmp(line, "Hardware", 8) == 0
                && value_after_colon(line, value, (int) sizeof(value))) {

                snprintf(hardware, sizeof(hardware), "%s", value);

                continue;
            }

            if (model_name[0] == '\0' && strncmp(line, "model name", 10) == 0
                && value_after_colon(line, value, (int) sizeof(value))) {

                snprintf(model_name, sizeof(model_name), "%s", value);
            }
        }

        fclose(file);
    }

    // ARM 的 Hardware 是 SoC 名，比 model name 更贴近处理器商品名
    const char *kernel_name = hardware[0] != '\0' ? hardware : model_name;

    if (cpu_name_is_usable(kernel_name)) {

        char friendly[TM_OUT_VALUE_MAX];

        if (cpu_name_database_lookup(kernel_name, friendly, sizeof(friendly))) {

            cached = query_value_text(TM_QUERY_OK, friendly);

        } else {

            cached = query_value_text(TM_QUERY_OK, kernel_name);
        }

        return cached;
    }

    // 内核没给可用名字：交给 SoC 那一套补
    char soc_name[TM_OUT_VALUE_MAX];

    if (build_soc_display_name(soc_name, sizeof(soc_name))) {

        cached = query_value_text(TM_QUERY_OK, soc_name);

        return cached;
    }

    // 两边都没拿到：cpuinfo 都打不开就报读失败，否则就是这台机器没这项信息
    if (!cpuinfo_read) {
        cached = query_value_none(TM_QUERY_READ_FAILED);
    }

    return cached;
}

/**
 * 内部探测：虚拟化方案。
 *
 * 只看内核暴露出来的虚拟化字符设备，按平台惯例取第一个存在的：
 *   /dev/gunyah → Gunyah（Qualcomm）
 *   /dev/kvm    → KVM（含 pKVM 形态，Google Pixel 等）
 *   /dev/gzvm   → GenieZone（MediaTek）
 *   /dev/halla  → Halla（Samsung Exynos）
 * 一个都没有 = 不支持。
 *
 * 为什么不再看 /proc/cpuinfo 的 vmx / svm / virt 去报"已禁用"：
 * 这一版没有可靠办法区分"硬件根本没有虚拟化"与"有但内核没启用"，
 * 与其猜一个状态，不如直接说 不支持。已禁用 这个描述留给外部实现
 * （真的有能力判断时由 query_virtualization_external 返回）。
 *
 * 返回值形如 `已启用(KVM)`，整串直接作为字段值发出去，客户端不做加工。
 */
static TmQueryValue probe_virtualization_internal(void)
{
    static bool probed = false;
    static TmQueryValue cached;

    if (probed) {
        return cached;
    }

    probed = true;

    static const char *const k_paths[] = {
        "/dev/gunyah",
        "/dev/kvm",
        "/dev/gzvm",
        "/dev/halla",
    };

    static const char *const k_names[] = {
        "Gunyah",
        "KVM",
        "GenieZone",
        "Halla",
    };

    for (size_t index = 0; index < sizeof(k_paths) / sizeof(k_paths[0]); index++) {

        if (access(k_paths[index], F_OK) != 0) {
            continue;
        }

        char text[64];

        snprintf(text, sizeof(text), "已启用(%s)", k_names[index]);

        cached = query_value_text(TM_QUERY_OK, text);

        return cached;
    }

    cached = query_value_text(TM_QUERY_OK, "不支持");

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

    // 是不是虚拟机客人：这一版没有可靠检测手段，按约定恒为 0。
    // 客户端只在这一格是 1 时显示"虚拟机: 是"，为 0 时整行都不出现
    // （没有"虚拟机: 否"这种说法），并据此决定是否显示虚拟化 / AVF 两行。
    device_put_value(&out[0], "is_vm", query_value_text(TM_QUERY_OK, "0"));

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

        TmQueryStatus own_status[3] = {
            TM_QUERY_UNSUPPORTED, TM_QUERY_UNSUPPORTED, TM_QUERY_UNSUPPORTED
        };

        probe_cpu_cache_from_sysfs(device->path, cache_kb, own_status);

        for (int level = 1; level <= 3; level++) {

            int external_kb = -1;

            TmQueryStatus status = query_cache_kb_external(device->device_id, device->path,
                                                           level, &external_kb);

            // 外部 OK 才覆盖自己扫到的
            if (status == TM_QUERY_OK && external_kb > 0) {

                cache_kb[level - 1] = external_kb;

                own_status[level - 1] = TM_QUERY_OK;
            }

            if (cache_kb[level - 1] >= 0) {

                cache_sum[level - 1] += cache_kb[level - 1];

                cache_has[level - 1] = 1;

                cache_status[level - 1] = TM_QUERY_OK;

            } else if (i == 0) {

                cache_status[level - 1] = own_status[level - 1];

            } else if (cache_status[level - 1] != TM_QUERY_ACCESS_DENIED
                       && own_status[level - 1] == TM_QUERY_ACCESS_DENIED) {

                // 多颗芯片时取"更能说明问题"的那个：只要有一颗读不到，
                // 就写"-"（读不到），不能因为另一颗没这一级就谎称"不支持"
                cache_status[level - 1] = TM_QUERY_ACCESS_DENIED;
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

// ============================================================
// 网卡设备：Linux 通用接口
//
// 整件事分两个过程，这一节只做第一个，第二个在客户端（PerformanceManager）：
//   1) 底层（本节）：把 /sys/class/net 下的接口**全部**收上来（虚拟网卡也收），
//      按 sysfs 拓扑与接口名分类成 WIFI / ETHERNET / CELLULAR / OTHER，
//      再采它们的地址与收发字节。协议里只报"事实"，不替上层决定"哪一类要显示"。
//   2) 上层：按角色决定把哪些注册成性能页设备 —— 现在只注册 WIFI（WLAN / Wi-Fi 页），
//      以太网、移动网络以后放开时，客户端只改过滤条件，底层不用动。
//
// 数据来源（每一项都能指到具体文件，页面上不猜）：
//   /sys/class/net/<if>                         接口存在
//   /sys/class/net/<if>/wireless                存在 = 无线接口（cfg80211 打的标记）
//   /sys/class/net/<if>/phy80211                存在 = 有 802.11 物理层（同上，有的内核只建这个）
//   /sys/class/net/<if>/statistics/rx_bytes     累计接收字节（两轮差值算速率）
//   /sys/class/net/<if>/statistics/tx_bytes     累计发送字节（同上）
//   /sys/class/net/<if>/device                  存在 = 挂在真实总线上的硬件网卡
//   /proc/net/wireless                          信号强度（第 3 列 level，单位 dBm）
//   iw dev <if> link                            SSID / 频段 / 信号 / 协商速率（root 才有，读不到就置空）
//   getifaddrs()                                IPv4 / IPv6 地址（不需要 root）
//
// 接口名归一：内核给 wlan0 / wifi0 / wl* 都算同一种无线接口，不看名字猜类型，
// 只看 wireless / phy80211 这两个标记 —— 名字只用来给移动网络这类没有标记的分型。
// ============================================================

/** 收发字节的上次采样表：按接口名对齐，够放这类机器上全部接口。 */
#define TM_NET_SAMPLE_MAX 32

/** 接口名缓冲：Linux 接口名上限 15 字符，留足余量。 */
#define TM_NET_IFACE_MAX 32

/** 网卡角色的协议取值（客户端按同一批字符串匹配，不按大小写折叠）。 */
#define TM_NET_ROLE_WIFI "WIFI"
#define TM_NET_ROLE_ETHERNET "ETHERNET"
#define TM_NET_ROLE_CELLULAR "CELLULAR"
#define TM_NET_ROLE_OTHER "OTHER"

/** 一次网卡采样：累计字节 + 时刻，速率靠相邻两轮差值。 */
typedef struct {
    char iface[TM_NET_IFACE_MAX];
    unsigned long long rx_bytes;
    unsigned long long tx_bytes;
    long long at_ms;
    bool valid;
} TmNetSample;

static TmNetSample g_net_prev[TM_NET_SAMPLE_MAX];

/** 单调时钟毫秒（速率窗口用，不受系统时间跳变影响）。 */
static long long net_now_ms(void)
{
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return -1;
    }

    return (long long) now.tv_sec * 1000LL + (long long) (now.tv_nsec / 1000000L);
}

/** 路径存在（目录 / 符号链接都算；无线标记有的内核只建链接，不能用 stat 判目录）。 */
static bool net_path_exists(const char *path)
{
    struct stat info;

    return lstat(path, &info) == 0;
}

/** 接口是不是无线（802.11）：wireless 目录或 phy80211 链接任一存在就算。 */
static bool net_is_wireless(const char *iface_path)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/wireless", iface_path);

    if (net_path_exists(path)) {
        return true;
    }

    snprintf(path, sizeof(path), "%s/phy80211", iface_path);

    return net_path_exists(path);
}

/** 接口名是否以某一组前缀开头（移动网络这类没有 sysfs 标记，只能按名字分）。 */
static bool net_name_has_prefix(const char *name, const char *const *prefixes, size_t count)
{
    if (name == NULL) {
        return false;
    }

    for (size_t i = 0; i < count; i++) {

        size_t length = strlen(prefixes[i]);

        if (strncmp(name, prefixes[i], length) == 0) {
            return true;
        }
    }

    return false;
}

/**
 * 接口角色：只按"能不能在 sysfs 上找到硬件证据"分，不按名字猜。
 *
 *   WIFI      : wireless / phy80211 标记存在（wlan0 / wifi0 / wl* 都归这里）
 *   CELLULAR  : 名字命中 rmnet / ccmni / pdp / wwan 这类移动网络接口
 *   ETHERNET  : 挂在真实总线上（有 device 链接）且不是无线
 *   OTHER     : 其余 —— tun / vpn / docker0 / veth / dummy 这类虚拟接口
 *
 * 注意 OTHER 不等于"虚拟"：判断虚拟要看 device 链接，这里只负责分型，
 * "要不要显示"由上层决定（现在只显示 WIFI）。
 */
static const char *net_role_of(const char *iface_path, const char *iface)
{
    static const char *const cellular_prefixes[] = {
        "rmnet", "ccmni", "pdp", "wwan", "seth", "sr-mham", "v4-rmnet"
    };

    if (net_is_wireless(iface_path)) {
        return TM_NET_ROLE_WIFI;
    }

    if (net_name_has_prefix(iface, cellular_prefixes,
                            sizeof(cellular_prefixes) / sizeof(cellular_prefixes[0]))) {
        return TM_NET_ROLE_CELLULAR;
    }

    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/device", iface_path);

    if (net_path_exists(path)) {
        return TM_NET_ROLE_ETHERNET;
    }

    return TM_NET_ROLE_OTHER;
}

/**
 * 读度的入口：把 /sys/class/net 下的**全部**接口枚举出来（只跳过 lo）。
 *
 * 与旧版不同：这里不再过滤 rmnet / tun 这类名字。底层只负责"报事实"，
 * 上层按角色挑要显示的类别；过滤放在这里的话，上层永远拿不到以太网 / 移动网络，
 * 以后要放开就得改底层。
 *
 * 但顺序要保证"真网卡排在前面"：先按角色优先级（WIFI > ETHERNET > CELLULAR > OTHER），
 * 同角色再按接口名字典序，这样 16 个名额（TM_OUT_NETWORK_MAX）不会被 rmnet_ 一大家子占满。
 */
static int compare_network_ref(const void *left, const void *right)
{
    const TmDeviceRef *a = (const TmDeviceRef *) left;
    const TmDeviceRef *b = (const TmDeviceRef *) right;

    int rank_a = 3;
    int rank_b = 3;

    if (strncmp(a->path, "/sys/class/net/", 15) == 0) {

        const char *role_a = net_role_of(a->path, a->name);
        const char *role_b = net_role_of(b->path, b->name);

        rank_a = (strcmp(role_a, TM_NET_ROLE_WIFI) == 0) ? 0
               : (strcmp(role_a, TM_NET_ROLE_ETHERNET) == 0) ? 1
               : (strcmp(role_a, TM_NET_ROLE_CELLULAR) == 0) ? 2 : 3;

        rank_b = (strcmp(role_b, TM_NET_ROLE_WIFI) == 0) ? 0
               : (strcmp(role_b, TM_NET_ROLE_ETHERNET) == 0) ? 1
               : (strcmp(role_b, TM_NET_ROLE_CELLULAR) == 0) ? 2 : 3;
    }

    if (rank_a != rank_b) {
        return (rank_a < rank_b) ? -1 : 1;
    }

    return strcmp(a->name, b->name);
}

/** 登记一块网卡：枚举与固定路径两趟共用（按接口名去重，stat 确认存在）。 */
static int net_add_iface(TmDeviceRef *out, int capacity, int count, const char *iface)
{
    if (iface == NULL || iface[0] == '\0' || iface[0] == '.') {
        return count;
    }

    // 回环不是网卡：它没有硬件，也没有"发送 / 接收"这回事
    if (strcmp(iface, "lo") == 0) {
        return count;
    }

    for (int i = 0; i < count; i++) {

        if (strcmp(out[i].name, iface) == 0) {
            return count;
        }
    }

    if (count >= capacity) {
        return count;
    }

    char probe[TM_PATH_MAX];
    struct stat info;

    snprintf(probe, sizeof(probe), "/sys/class/net/%s", iface);

    if (stat(probe, &info) != 0) {
        return count;
    }

    snprintf(out[count].path, sizeof(out[count].path), "/sys/class/net/%s", iface);

    snprintf(out[count].name, sizeof(out[count].name), "%s", iface);

    return count + 1;
}

/**
 * 枚举网卡：扫 /sys/class/net，只跳过回环。
 *
 * 虚拟接口也枚举（上层要靠角色字段判断），但排序把无线 / 有线 / 移动网络
 * 放在虚拟接口前面，限流时先保住真网卡。
 *
 * 两趟走：枚举 + 常见接口名的固定路径。接口名是内核/厂商起的（wlan0、rmnet_data0
 * 这些），列不了目录时按固定候选逐个 stat，读到就登记 —— 上层再用 isRegisteredNetwork
 * 决定收不收、叫什么名字，底层只负责"这个接口存在"。
 */
static int enumerate_network_devices(TmDeviceRef *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    int count = 0;

    DIR *dir = opendir("/sys/class/net");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {
            count = net_add_iface(out, capacity, count, entry->d_name);
        }

        closedir(dir);
    }

    static const char *const fixed_ifaces[] = {
        "wlan0",
        "wlan1",
        "eth0",
        "eth1",
        "rmnet0",
        "rmnet_data0",
        "rmnet_data1",
        "ccmni0",
        "pdp0",
        "wwan0",
        "tun0",
    };

    for (size_t i = 0; i < sizeof(fixed_ifaces) / sizeof(fixed_ifaces[0]); i++) {
        count = net_add_iface(out, capacity, count, fixed_ifaces[i]);
    }

    if (count > 1) {
        qsort(out, (size_t) count, sizeof(TmDeviceRef), compare_network_ref);
    }

    for (int i = 0; i < count; i++) {

        // id 是稳定索引（network0 / network1）；具体是哪块网卡由 iface 说明
        snprintf(out[i].id, sizeof(out[i].id), "network%d", i);

        ensure_device_name(out[i].name, (int) sizeof(out[i].name));
    }

    return count;
}

/** 在 "键: 值" 形式的文本里取值，允许行首有空白（iw 的输出每行前面都有制表符）。 */
static bool net_find_value(const char *text, const char *key, char *out, int capacity)
{
    if (text == NULL || text[0] == '\0' || capacity <= 0) {
        return false;
    }

    size_t key_length = strlen(key);

    const char *cursor = text;

    while (*cursor != '\0') {

        const char *line = cursor;

        while (*line == ' ' || *line == '\t') {
            line++;
        }

        if (strncmp(line, key, key_length) == 0) {

            const char *value = line + key_length;

            while (*value == ' ' || *value == '\t') {
                value++;
            }

            int index = 0;

            while (*value != '\0' && *value != '\n' && index < capacity - 1) {
                out[index++] = *value++;
            }

            while (index > 0 && (out[index - 1] == ' ' || out[index - 1] == '\t')) {
                index--;
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

/**
 * 一次无线链路信息（iw dev <if> link 的输出）。
 *
 * 为什么要跑外部命令：SSID / 频段 / 协商速率这三项内核没有统一 sysfs 文件，
 * 只有 cfg80211 的 netlink 接口能给；iw 就是那个接口的命令行壳子。
 * 后台以 root / shell 跑，读得到就读，读不到（没 iw / 没权限 / 没连上）就一律留空，
 * 不拿"未知"这种假值糊页面。
 */
typedef struct {
    char ssid[64];
    char freq_mhz[16];
    char link_standard[16];
    bool has_signal;
    double signal_dbm;
} TmNetLink;

static void net_read_iw_link(const char *iface, TmNetLink *out)
{
    memset(out, 0, sizeof(*out));

    char command[128];

    snprintf(command, sizeof(command), "iw dev %s link 2>/dev/null", iface);

    FILE *file = popen(command, "r");

    if (file == NULL) {
        return;
    }

    char text[2048];

    int total = 0;

    while (total < (int) sizeof(text) - 1) {

        size_t got = fread(text + total, 1, (size_t) (sizeof(text) - 1 - total), file);

        if (got == 0) {
            break;
        }

        total += (int) got;
    }

    text[total] = '\0';

    pclose(file);

    // 没连上时输出是 "Not connected."，三个字段自然都取不到
    net_find_value(text, "SSID:", out->ssid, (int) sizeof(out->ssid));

    net_find_value(text, "freq:", out->freq_mhz, (int) sizeof(out->freq_mhz));

    char signal[64];

    if (net_find_value(text, "signal:", signal, (int) sizeof(signal))) {

        double dbm = 0.0;

        if (sscanf(signal, "%lf", &dbm) == 1) {
            out->has_signal = true;
            out->signal_dbm = dbm;
        }
    }

    /*
     * 连接类型（802.11 代际）藏在这一行的调制名里：
     *   tx bitrate: 866.7 MBit/s 80MHz HE MCS9    -> HE   = 802.11ax（Wi-Fi 6 / 6E）
     *   tx bitrate: 433.3 MBit/s 80MHz VHT MCS9   -> VHT  = 802.11ac（Wi-Fi 5）
     *   tx bitrate: 144.4 MBit/s MCS13 short GI   -> HT   = 802.11n（Wi-Fi 4）
     *   tx bitrate: 54.0 MBit/s OFDM              -> OFDM = 802.11a/g
     * 这是内核唯一会把"当前链路用的是哪一代"写出来的地方。
     */
    char tx[96];

    if (net_find_value(text, "tx bitrate:", tx, (int) sizeof(tx))) {

        if (strstr(tx, "EHT") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11be");

        } else if (strstr(tx, "HE") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11ax");

        } else if (strstr(tx, "VHT") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11ac");

        } else if (strstr(tx, "HT") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11n");

        } else if (strstr(tx, "OFDM") != NULL || strstr(tx, "ERP") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11g");
        }
    }
}

/**
 * 信号强度百分比：优先用 iw 给的 dBm，退回 /proc/net/wireless 的 level 列。
 *
 * 换算口径与 Windows 一致：percent = 2 × (dBm + 100)，夹到 0..100。
 * （-50 dBm ≈ 100%，-70 dBm ≈ 60%，-90 dBm ≈ 20%。）
 */
static bool net_signal_percent(const char *iface, const TmNetLink *link, double *out)
{
    double dbm = 0.0;

    bool has = false;

    if (link != NULL && link->has_signal) {

        dbm = link->signal_dbm;

        has = true;

    } else {

        char text[4096];

        if (read_text_file("/proc/net/wireless", text, (int) sizeof(text)) > 0) {

            char line[256];

            snprintf(line, sizeof(line), "%s:", iface);

            const char *cursor = text;

            while (*cursor != '\0') {

                const char *start = cursor;

                const char *newline = strchr(cursor, '\n');

                size_t length = (newline != NULL) ? (size_t) (newline - cursor) : strlen(cursor);

                if (strncmp(start, line, strlen(line)) == 0 && length < sizeof(line)) {

                    char row[256];

                    memcpy(row, start, length);

                    row[length] = '\0';

                    // 形如 "wlan0: 0000   51.  -56.   0   0   0   0   0   0"，
                    // 第 2 个数是链路质量，第 3 个是信号电平（dBm）
                    double status = 0.0;
                    double quality = 0.0;
                    double level = 0.0;

                    if (sscanf(row + strlen(line), "%lf %lf %lf", &status, &quality, &level) >= 3) {

                        if (level < 0.0) {
                            dbm = level;
                            has = true;
                        } else if (quality >= 0.0) {
                            *out = quality;
                            return true;
                        }
                    }

                    break;
                }

                if (newline == NULL) {
                    break;
                }

                cursor = newline + 1;
            }
        }
    }

    if (!has) {
        return false;
    }

    double percent = (dbm + 100.0) * 2.0;

    if (percent < 0.0) {
        percent = 0.0;
    }

    if (percent > 100.0) {
        percent = 100.0;
    }

    *out = percent;

    return true;
}

/** IPv6 地址是不是只在本链路有效（链路本地 fe80::/10 与回环 ::1）：这类不算"能用"。 */
static bool net_ipv6_is_local_only(const struct in6_addr *address)
{
    if (address == NULL) {
        return false;
    }

    // 回环 ::1 —— 前 15 个字节全 0，最后一个字节是 1
    bool loopback = true;

    for (int i = 0; i < 15; i++) {

        if (address->s6_addr[i] != 0) {
            loopback = false;
            break;
        }
    }

    if (loopback) {
        return address->s6_addr[15] == 1;
    }

    // 链路本地 fe80::/10：首字节 0xfe，次字节高两位置 10
    return address->s6_addr[0] == 0xfe && (address->s6_addr[1] & 0xc0) == 0x80;
}

/**
 * 接口的 IPv4 / IPv6 各取第一个（getifaddrs 不需要 root，比跑 ip 命令稳）。
 *
 * 顺便回答"这条链路有没有真正在用的地址"，作为事实往上报：
 * IPv4 拿到就算有；IPv6 要看是不是链路本地 —— 手机的 rmnet 接口只要 up 就会分到
 * fe80::，那只能说明"接口起来了"，不代表在传数据。
 * 返回值三态：0 = 没有、1 = 有、-1 = getifaddrs 读失败（读失败不能当成"没有"）。
 */
static int net_read_addresses(const char *iface, char *ipv4, int ipv4_capacity,
                              char *ipv6, int ipv6_capacity)
{
    ipv4[0] = '\0';
    ipv6[0] = '\0';

    struct ifaddrs *list = NULL;

    if (getifaddrs(&list) != 0) {
        return -1;
    }

    int has_data_address = 0;

    for (struct ifaddrs *item = list; item != NULL; item = item->ifa_next) {

        if (item->ifa_addr == NULL || item->ifa_name == NULL) {
            continue;
        }

        if (strcmp(item->ifa_name, iface) != 0) {
            continue;
        }

        if (item->ifa_addr->sa_family == AF_INET) {

            has_data_address = 1;

            if (ipv4[0] == '\0') {

                inet_ntop(AF_INET, &((struct sockaddr_in *) item->ifa_addr)->sin_addr,
                          ipv4, (size_t) ipv4_capacity);
            }

        } else if (item->ifa_addr->sa_family == AF_INET6) {

            struct sockaddr_in6 *address6 = (struct sockaddr_in6 *) item->ifa_addr;

            if (!net_ipv6_is_local_only(&address6->sin6_addr)) {
                has_data_address = 1;
            }

            if (ipv6[0] == '\0') {

                inet_ntop(AF_INET6, &address6->sin6_addr, ipv6, (size_t) ipv6_capacity);
            }
        }
    }

    freeifaddrs(list);

    return has_data_address;
}

/** 按接口名找（或占）一格采样槽。 */
static TmNetSample *net_sample_slot(const char *iface)
{
    static int next = 0;

    for (int i = 0; i < TM_NET_SAMPLE_MAX; i++) {

        if (g_net_prev[i].valid && strcmp(g_net_prev[i].iface, iface) == 0) {
            return &g_net_prev[i];
        }
    }

    TmNetSample *slot = &g_net_prev[next % TM_NET_SAMPLE_MAX];

    next++;

    memset(slot, 0, sizeof(*slot));

    snprintf(slot->iface, sizeof(slot->iface), "%s", iface);

    return slot;
}

/**
 * 收发速率：读统计计数，与上一轮相除得到字节/秒。
 *
 * 与磁盘的 measure_disk_io 同一个口径：首帧没有基准 → has=false（不发 0，发 NO_BASELINE）；
 * 计数器倒退（接口重建）→ 当轮作废并重记基准。
 * 两次请求的间隔就是窗口（UI 更新档位 0.5 / 1 / 4 秒），所以窗口抖动不影响速率本身。
 */
static bool net_measure_rate(const char *iface_path, const char *iface,
                             double *rx_rate, double *tx_rate)
{
    char path[TM_PATH_MAX];

    double rx = 0.0;
    double tx = 0.0;

    snprintf(path, sizeof(path), "%s/statistics/rx_bytes", iface_path);

    bool has_rx = read_first_number(path, &rx);

    snprintf(path, sizeof(path), "%s/statistics/tx_bytes", iface_path);

    bool has_tx = read_first_number(path, &tx);

    if (!has_rx && !has_tx) {
        return false;
    }

    long long now = net_now_ms();

    TmNetSample *slot = net_sample_slot(iface);

    bool had = slot->valid && slot->at_ms > 0 && now > slot->at_ms;

    bool regressed = had && ((has_rx && (unsigned long long) rx < slot->rx_bytes) ||
                             (has_tx && (unsigned long long) tx < slot->tx_bytes));

    if (had && !regressed) {

        double seconds = (double) (now - slot->at_ms) / 1000.0;

        if (seconds > 0.05) {

            if (has_rx) {
                *rx_rate = ((double) (unsigned long long) rx - (double) slot->rx_bytes) / seconds;
            }

            if (has_tx) {
                *tx_rate = ((double) (unsigned long long) tx - (double) slot->tx_bytes) / seconds;
            }

            slot->rx_bytes = (unsigned long long) rx;
            slot->tx_bytes = (unsigned long long) tx;
            slot->at_ms = now;
            slot->valid = true;

            return true;
        }
    }

    // 首帧 / 计数倒退 / 两次采样贴得太近：只重记基准，本轮不给速率
    slot->rx_bytes = (unsigned long long) rx;
    slot->tx_bytes = (unsigned long long) tx;
    slot->at_ms = now;
    slot->valid = true;

    return false;
}

/**
 * 拼一帧里的网卡设备：每块一块，带类型、型号、SSID、地址、信号、收发速率。
 *
 * 字段清单（键名与客户端 buildNetworkSnapshot 一一对应）：
 *   iface          接口名（wlan0 / eth0 / rmnet_data0 …）
 *   role           WIFI / ETHERNET / CELLULAR / OTHER（上层按它决定显不显示）
 *   name           网卡型号；读不到就用接口名（不写占位符 NAME）
 *   ssid           无线 SSID（没连上 / 读不到就不发）
 *   link_type      无线代际（802.11ax / 802.11ac …，同上）
 *   ipv4 / ipv6    地址各取第一个
 *   has_data_address 这条链路有没有"真在用"的地址（0/1；非链路本地才算，手机网络用它在
 *                  上层筛掉一堆只有 fe80:: 的 rmnet 空壳；读失败就不发这一格）
 *   signal_percent 信号强度百分比（没连上不发）
 *   freq_mhz       当前频段（没连上不发）
 *   receive_bytes_per_sec / send_bytes_per_sec  收发速率（首帧 NO_BASELINE）
 */
static int build_network_devices(TmOutDevice *out, int capacity,
                                 const TmDeviceRef *devices, int count)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    int limit = count;

    if (limit > capacity) {
        limit = capacity;
    }

    for (int i = 0; i < limit; i++) {

        device_begin(&out[i], devices[i].id, TM_DEV_TYPE_NETWORK);

        const char *role = net_role_of(devices[i].path, devices[i].name);

        device_put(&out[i], "role", role);

        device_put(&out[i], "iface", devices[i].name);

        // 型号：/sys/class/net/<if>/device/model 只有挂在真实总线上的网卡才有；
        // 没有就用接口名顶上，页面上至少看得出是哪一块
        char model[96];

        model[0] = '\0';

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "%s/device/model", devices[i].path);

        if (read_text_file(path, model, (int) sizeof(model)) > 0) {

            // 去掉行尾换行与首尾空白
            int length = (int) strlen(model);

            while (length > 0 && (model[length - 1] == '\n' || model[length - 1] == '\r' ||
                                  model[length - 1] == ' ' || model[length - 1] == '\t')) {
                model[--length] = '\0';
            }

            char *head = model;

            while (*head == ' ' || *head == '\t') {
                head++;
            }

            if (head != model) {
                memmove(model, head, strlen(head) + 1);
            }
        }

        device_put(&out[i], "name", (model[0] != '\0') ? model : devices[i].name);

        // 地址
        char ipv4[64];
        char ipv6[96];

        int has_data_address = net_read_addresses(devices[i].name, ipv4, (int) sizeof(ipv4),
                                                  ipv6, (int) sizeof(ipv6));

        if (ipv4[0] != '\0') {
            device_put(&out[i], "ipv4", ipv4);
        }

        if (ipv6[0] != '\0') {
            device_put(&out[i], "ipv6", ipv6);
        }

        // "这条链路是不是真在用"：手机网络有一大串已 up 的 rmnetXX 只挂着 fe80::，
        // 上层拿这一格筛空壳，别让它们占左栏。读失败（-1）就不发，交给上层按地址推断。
        if (has_data_address >= 0) {

            device_put_value(&out[i], "has_data_address",
                             query_value_int(has_data_address ? 1 : 0, TM_QUERY_NOT_AVAILABLE));
        }

        // 无线三项：只有无线接口才有意义（有线 / 移动网络不去跑 iw）
        TmNetLink link;

        memset(&link, 0, sizeof(link));

        double signal = 0.0;

        if (strcmp(role, TM_NET_ROLE_WIFI) == 0) {

            net_read_iw_link(devices[i].name, &link);

            if (link.ssid[0] != '\0') {
                device_put(&out[i], "ssid", link.ssid);
            }

            if (link.link_standard[0] != '\0') {
                device_put(&out[i], "link_type", link.link_standard);
            }

            if (link.freq_mhz[0] != '\0') {
                device_put(&out[i], "freq_mhz", link.freq_mhz);
            }

            if (net_signal_percent(devices[i].name, &link, &signal)) {
                device_put_value(&out[i], "signal_percent",
                                 query_value_float(true, signal, 0, TM_QUERY_NOT_AVAILABLE));
            }
        }

        // 收发速率
        double rx_rate = 0.0;
        double tx_rate = 0.0;

        bool has_rate = net_measure_rate(devices[i].path, devices[i].name, &rx_rate, &tx_rate);

        device_put_int(&out[i], "receive_bytes_per_sec",
                       has_rate ? (long long) rx_rate : -1, TM_FIELD_STATUS_NO_BASELINE);

        device_put_int(&out[i], "send_bytes_per_sec",
                       has_rate ? (long long) tx_rate : -1, TM_FIELD_STATUS_NO_BASELINE);
    }

    return limit;
}


//
// 每一项都能指到具体文件，页面不猜：
//   /sys/block/<dev>/stat              活动时间 / 平均响应 / 读写速率（两轮差值）
//   /sys/block/<dev>/size               容量（单位是 512 字节扇区）
//   /sys/block/<dev>/queue/rotational   1 = 旋转介质，0 = 非旋转
//   /sys/block/<dev>/device             指向控制器目录的链接：判 NVMe / SCSI / UFS / MMC
//   /sys/block/<dev>/device/model       型号串，页面上右侧那个设备名
//   /proc/self/mountinfo                已格式化容量 / 系统盘（按 major:minor 反查块名）
//   /proc/swaps                         页面文件（Android 上就是 Swap 分区）
//
// 两个容易踩的点：
// 1. stat 里的扇区按 512 字节算，与设备实际 logical_block_size 无关；
// 2. dm-* / md 这类映射设备必须跟着 slaves 走到物理盘，只看挂载点会认错盘。
// ============================================================

/** stat 里扇区计数的换算：内核固定按 512 字节一个扇区统计。 */
#define TM_DISK_SECTOR_BYTES 512LL

/** 每帧最多记多少台盘的上次采样：比 TM_OUT_DISK_MAX 留一点余量，编号变了也不丢基准。 */
#define TM_DISK_SAMPLE_MAX 32

/** 一次挂载扫描最多认多少个块设备号（去重用）。 */
#define TM_DISK_DEVNUM_MAX 24

/** 系统盘候选挂载点：先找系统镜像所在的分区，再退到数据分区。 */
#define TM_DISK_SYSTEM_MOUNT_MAX 4

/** /sys/block/<dev>/stat 的 11 列累积计数器。 */
typedef struct {
    unsigned long long reads;
    unsigned long long reads_merged;
    unsigned long long sectors_read;
    unsigned long long read_ms;
    unsigned long long writes;
    unsigned long long writes_merged;
    unsigned long long sectors_written;
    unsigned long long write_ms;
    unsigned long long in_flight;
    unsigned long long busy_ms;
    unsigned long long weighted_ms;
} TmBlockStat;

/** 读一行的块层计数器；少于 11 列（老内核 / 解析失败）按没有值处理。 */
static bool read_block_stat(const char *path, TmBlockStat *out)
{
    if (path == NULL || out == NULL) {
        return false;
    }

    char text[512];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    TmBlockStat stat;

    memset(&stat, 0, sizeof(stat));

    int got = sscanf(text,
                     "%llu %llu %llu %llu %llu %llu %llu %llu %llu %llu %llu",
                     &stat.reads, &stat.reads_merged, &stat.sectors_read, &stat.read_ms,
                     &stat.writes, &stat.writes_merged, &stat.sectors_written,
                     &stat.write_ms, &stat.in_flight, &stat.busy_ms, &stat.weighted_ms);

    if (got < 11) {
        return false;
    }

    *out = stat;

    return true;
}

/** 一台盘的上一次采样：块名 + 计数器 + 采样时刻（单调时钟毫秒）。 */
typedef struct {
    char name[64];
    TmBlockStat stat;
    long long at_ms;
    bool valid;
} TmDiskPrev;

/** 进程内的基准表：本进程常驻，两次请求之间的差值就在这里算。 */
static TmDiskPrev g_disk_prev[TM_DISK_SAMPLE_MAX];

static int g_disk_prev_count = 0;

/** 取（没有就新建）某块盘的基准槽；表满时返回 NULL，这一帧不外扩、也不覆盖别人的基准。 */
static TmDiskPrev *disk_prev_slot(const char *name)
{
    if (name == NULL || name[0] == '\0') {
        return NULL;
    }

    for (int i = 0; i < g_disk_prev_count; i++) {

        if (strcmp(g_disk_prev[i].name, name) == 0) {
            return &g_disk_prev[i];
        }
    }

    if (g_disk_prev_count >= TM_DISK_SAMPLE_MAX) {
        return NULL;
    }

    TmDiskPrev *slot = &g_disk_prev[g_disk_prev_count++];

    memset(slot, 0, sizeof(*slot));

    snprintf(slot->name, sizeof(slot->name), "%s", name);

    return slot;
}

/** 一帧里由 stat 差值算出来的四项实时指标；has_xxx = false 表示这一帧没有基准。 */
typedef struct {
    bool has_activity;
    bool has_response;
    bool has_read;
    bool has_write;
    double activity_percent;
    double avg_response_ms;
    double read_bytes_per_sec;
    double write_bytes_per_sec;
} TmDiskIo;

/**
 * 用两轮 stat 差值算实时指标。
 *
 * 口径（与页面的四个实时字段一一对应）：
 *   读取速度   = 读取扇区增量 * 512 / 间隔秒
 *   写入速度   = 写入扇区增量 * 512 / 间隔秒
 *   活动时间   = 设备繁忙毫秒增量 / 间隔毫秒 * 100，夹到 0..100
 *   平均响应   = 加权 I/O 毫秒增量 /（读完成 + 写完成）增量；这一帧没有完成量时记 0
 *
 * 首帧（或计数器倒退）不发数值：上层显示占位符，等下一帧再出数据，不编 0。
 */
static TmDiskIo measure_disk_io(const char *block_name, const char *stat_path)
{
    TmDiskIo io;

    memset(&io, 0, sizeof(io));

    TmBlockStat current;

    if (!read_block_stat(stat_path, &current)) {
        return io;
    }

    long long at_ms = monotonic_ms();

    if (at_ms <= 0) {
        return io;
    }

    TmDiskPrev *prev = disk_prev_slot(block_name);

    if (prev == NULL) {
        return io;
    }

    bool has_baseline = prev->valid && prev->at_ms > 0 && at_ms > prev->at_ms;

    // 计数器倒退 = 设备重插 / 内核重置，这一帧不当基准用，重新起算
    if (has_baseline &&
        (current.sectors_read < prev->stat.sectors_read ||
         current.sectors_written < prev->stat.sectors_written ||
         current.reads < prev->stat.reads ||
         current.writes < prev->stat.writes ||
         current.busy_ms < prev->stat.busy_ms ||
         current.weighted_ms < prev->stat.weighted_ms)) {

        has_baseline = false;
    }

    if (has_baseline) {

        double elapsed_ms = (double) (at_ms - prev->at_ms);

        unsigned long long sector_read_delta =
            current.sectors_read - prev->stat.sectors_read;

        unsigned long long sector_write_delta =
            current.sectors_written - prev->stat.sectors_written;

        io.read_bytes_per_sec =
            (double) sector_read_delta * (double) TM_DISK_SECTOR_BYTES / (elapsed_ms / 1000.0);

        io.write_bytes_per_sec =
            (double) sector_write_delta * (double) TM_DISK_SECTOR_BYTES / (elapsed_ms / 1000.0);

        io.has_read = true;
        io.has_write = true;

        double busy_delta = (double) (current.busy_ms - prev->stat.busy_ms);

        double activity = busy_delta * 100.0 / elapsed_ms;

        if (activity < 0.0) {
            activity = 0.0;
        }

        if (activity > 100.0) {
            activity = 100.0;
        }

        io.activity_percent = activity;
        io.has_activity = true;

        unsigned long long completed =
            (current.reads - prev->stat.reads) + (current.writes - prev->stat.writes);

        double weighted_delta = (double) (current.weighted_ms - prev->stat.weighted_ms);

        io.avg_response_ms = (completed > 0)
                             ? (weighted_delta / (double) completed)
                             : 0.0;

        io.has_response = true;
    }

    prev->stat = current;
    prev->at_ms = at_ms;
    prev->valid = true;

    return io;
}

// ============================================================
// 磁盘四项指标的常驻高频采样
//
// 为什么不能"请求来了现算"：
//   现算的差值窗口 = 两次快照请求的间隔，而那个间隔由用户选的更新速度决定
//   （高 0.5 秒 / 常规 1 秒 / 低 4 秒）。常规档 1 秒窗口下，一次几百毫秒的突发读写
//   被摊进整整一秒，读出来的是平均值而不是"此刻多快"；低档 4 秒更粗。所以同一个
//   动作只有高档看着准 —— 问题不在 UI 画得快慢，而在采样窗口被 UI 节拍绑住了。
//
// 现在的做法（把采样与上报解耦）：
//   进程自己起一条后台线程，固定每 TM_DISK_TICK_MS 读一次 /sys/block/<dev>/stat，
//   用**最近 1 秒**的滑动窗口结算四项指标，结果放进互斥保护的表里；
//   快照请求（REQ typeCode=5）只查表，把最新结算值原样发出去。
//   于是：UI 想 4 秒刷一次也行，拿到的仍是"最近 1 秒"的真实速率。
//
// 为什么后台线程能跑起来：
//   本进程是常驻的（main -> run_message_loop），绝大部分时间阻塞在 fgets(stdin)
//   等命令，这段时间完全空着。pthread 起来之后与主循环互不干扰：主循环读管道、线程
//   读 sysfs，两者只在这张结果表上相遇，用一把互斥锁保护。
//
// 取不到值时的退路：线程没起来（创建失败）或结果已过期（>3 拍没更新，比如设备卡住），
//   build_disk_devices 会退回原来的 measure_disk_io()，功能降级但不失效。
// ============================================================

/** 采样节拍：250 ms。四拍 = 1 秒窗口，与 UI 任何一档刷新速度都无关。 */
#define TM_DISK_TICK_MS 250

/**
 * 环形缓冲能留多少拍的原始采样。
 *
 * 速率与活动时间只用最近 1 秒（见 TM_DISK_RATE_SPAN）；留 17 拍（4 秒）是为了
 * **平均响应时间**能自适应拉长窗口 —— 原因见 TM_DISK_RESPONSE_TARGET_IOS。
 */
#define TM_DISK_WINDOW_SAMPLES 17

/** 速率 / 活动时间固定用最近 4 个间隔 = 1 秒。 */
#define TM_DISK_RATE_SPAN 4

/**
 * 平均响应时间的精度目标：窗口里至少要有这么多个"已完成 I/O"。
 *
 * 精度天花板在协议本身：/sys/block/<dev>/stat 的 time_in_queue（第 11 列）是
 * **整数毫秒**，而且底层按 jiffy 累加（HZ=100 时一格就是 10 ms）。单个 I/O 的真实
 * 0.4 ms 在计数器里可能是 +0 也可能是 +10，永远看不出来。
 *
 * 但**平均**能救：N 个 I/O 的总误差不超过一格（G 毫秒），平均值的最小刻度就是
 * G / N。要 0.1 ms 的刻度，就需要 N ≥ G / 0.1 = 100（按最差的 10 ms 一格算）。
 * 所以这里以"攒够 100 个完成量"为窗口选择依据：
 *   - 盘忙时 1 秒就有几百上千个 I/O → 窗口就停在 1 秒附近，值也不钝；
 *   - 盘闲时（每秒只有几个 I/O）才把窗口拉到最多 4 秒，宁可慢一点也别给出
 *     只有 0 / 1 / 2 ms 这种一级一跳的假精度。
 */
#define TM_DISK_RESPONSE_TARGET_IOS 100

/** 结算最少要跨多少毫秒：太短的窗口里 busy_ms 只有几个 tick，噪声比信号大。 */
#define TM_DISK_MIN_SPAN_MS 100

/** 结果的有效期：超过这个时长没被线程更新，就当没有值（设备卡死时不发陈旧数据）。 */
#define TM_DISK_STALE_MS (TM_DISK_TICK_MS * 3)

/** 一次原始采样：11 列计数器 + 采样时刻。 */
typedef struct {
    TmBlockStat stat;
    long long at_ms;
    bool valid;
} TmDiskSample;

/** 一台盘的上次采样表：环形缓冲里最近 17 拍 + 最近一次结算结果。 */
typedef struct {
    char name[64];
    TmDiskSample ring[TM_DISK_WINDOW_SAMPLES];
    int count;                 // 已攒了几个点，最大 TM_DISK_WINDOW_SAMPLES
    int next;                  // 下一个写入位置
    TmDiskIo io;               // 最近一次结算的四项指标
    bool io_valid;             // false = 还没攒够两个点，或计数器刚倒退
    long long updated_ms;      // 最近一次成功采样的时刻
} TmDiskRuntime;

/** 结果表：按块名索引，进程内常驻。 */
static TmDiskRuntime g_disk_rt[TM_DISK_SAMPLE_MAX];

static int g_disk_rt_count = 0;

/** 保护整张 g_disk_rt：线程写，请求线程读，两边都只持有极短时间。 */
static pthread_mutex_t g_disk_rt_lock = PTHREAD_MUTEX_INITIALIZER;

/** 采样线程是否已在跑（只用于避免重复启动）。 */
static bool g_disk_rt_thread_on = false;

/** 取（没有就新建）某块盘的运行时槽；表满时返回 NULL。调用方必须已持锁。 */
static TmDiskRuntime *disk_rt_slot_locked(const char *name)
{
    if (name == NULL || name[0] == '\0') {
        return NULL;
    }

    for (int i = 0; i < g_disk_rt_count; i++) {

        if (strcmp(g_disk_rt[i].name, name) == 0) {
            return &g_disk_rt[i];
        }
    }

    if (g_disk_rt_count >= TM_DISK_SAMPLE_MAX) {
        return NULL;
    }

    TmDiskRuntime *slot = &g_disk_rt[g_disk_rt_count++];

    memset(slot, 0, sizeof(*slot));

    snprintf(slot->name, sizeof(slot->name), "%s", name);

    return slot;
}

/** 找已有的运行时槽（不新建）。调用方必须已持锁。 */
static TmDiskRuntime *disk_rt_find_locked(const char *name)
{
    if (name == NULL || name[0] == '\0') {
        return NULL;
    }

    for (int i = 0; i < g_disk_rt_count; i++) {

        if (strcmp(g_disk_rt[i].name, name) == 0) {
            return &g_disk_rt[i];
        }
    }

    return NULL;
}

/**
 * 取"往回第 age 拍"的采样：age = 0 是最新，1 是上一个，依此类推。
 * 越界（这批数据还没攒够）返回 NULL。调用方必须已持锁。
 */
static const TmDiskSample *disk_rt_age(const TmDiskRuntime *slot, int age)
{
    if (slot == NULL || age < 0 || age >= slot->count) {
        return NULL;
    }

    int index = (slot->next + TM_DISK_WINDOW_SAMPLES - 1 - age) % TM_DISK_WINDOW_SAMPLES;

    return &slot->ring[index];
}

/** 计数器是否倒退（设备重插 / 内核重置）：任何一项变小都算。 */
static bool disk_stat_regressed(const TmBlockStat *before, const TmBlockStat *after)
{
    if (before == NULL || after == NULL) {
        return true;
    }

    return after->sectors_read < before->sectors_read ||
           after->sectors_written < before->sectors_written ||
           after->reads < before->reads ||
           after->writes < before->writes ||
           after->busy_ms < before->busy_ms ||
           after->weighted_ms < before->weighted_ms;
}

/**
 * 用环形缓冲里两个采样点结算四项实时指标（两端取差，不逐段累加）。
 *
 * 为什么两端取差：所有计数器都是单调累加的，两端之差就是整段总量，
 * 逐段累加只会多一层舍入误差，结果一样。
 *
 * 口径（两段窗口长度不同，这是本函数与早期版本唯一的区别）：
 *   读取速度 / 写入速度 / 活动时间 → 固定用**最近 1 秒**（TM_DISK_RATE_SPAN 拍），
 *     与任务管理器一致，也保证图表上的抖动不会被拉平；
 *   平均响应 → 用**自适应窗口**（见 TM_DISK_RESPONSE_TARGET_IOS）：
 *     从"最近 250 ms"开始往前找，找到第一个完成量 ≥ 100 的窗口就用它，
 *     最远拉到环形缓冲的 4 秒。这样"平均"的分辨率能压到 0.1 ms 一档，
 *     而不是被 time_in_queue 的整毫秒 / 一格 jiffy 卡死在 0 / 1 / 2 ms。
 *
 * 窗口内没有完成的 I/O 时平均响应记 0 —— 空转时的平均响应本来就是 0，不是"没有值"。
 * 计数器倒退 = 整轮作废，只留最新点重新起算。
 * 调用方必须已持锁。
 */
static void disk_rt_settle(TmDiskRuntime *slot)
{
    if (slot == NULL || slot->count < 2) {
        return;
    }

    const TmDiskSample *newest = disk_rt_age(slot, 0);

    if (newest == NULL || !newest->valid) {
        return;
    }

    int max_age = slot->count - 1;

    // ---- 第一段：最近 1 秒的速率与活动时间 ----
    int rate_age = max_age > TM_DISK_RATE_SPAN ? TM_DISK_RATE_SPAN : max_age;

    const TmDiskSample *rate_base = disk_rt_age(slot, rate_age);

    if (rate_base == NULL || !rate_base->valid) {
        return;
    }

    // 倒退只查真正要用的那 1 秒窗口：4 秒窗口里 3 秒前有过一次重插，
    // 不该把现在这一秒的正常数据也一起作废。
    if (disk_stat_regressed(&rate_base->stat, &newest->stat)) {

        slot->io_valid = false;

        return;
    }

    long long rate_span_ms = newest->at_ms - rate_base->at_ms;

    if (rate_span_ms < TM_DISK_MIN_SPAN_MS) {
        return;
    }

    const TmBlockStat *rate_before = &rate_base->stat;
    const TmBlockStat *after = &newest->stat;

    TmDiskIo io;

    memset(&io, 0, sizeof(io));

    double seconds = (double) rate_span_ms / 1000.0;

    unsigned long long sector_read = after->sectors_read - rate_before->sectors_read;
    unsigned long long sector_write = after->sectors_written - rate_before->sectors_written;

    io.read_bytes_per_sec = (double) sector_read * (double) TM_DISK_SECTOR_BYTES / seconds;
    io.write_bytes_per_sec = (double) sector_write * (double) TM_DISK_SECTOR_BYTES / seconds;

    io.has_read = true;
    io.has_write = true;

    // 设备忙时 = 窗口内真正在处理 I/O 的毫秒数；两个 I/O 重叠时内核只算一次，
    // 所以这个比例天然落在 0..100，夹一下只是防四舍五入越界。
    double busy = (double) (after->busy_ms - rate_before->busy_ms);

    double activity = busy * 100.0 / (double) rate_span_ms;

    if (activity < 0.0) {
        activity = 0.0;
    }

    if (activity > 100.0) {
        activity = 100.0;
    }

    io.activity_percent = activity;
    io.has_activity = true;

    // ---- 第二段：自适应窗口的平均响应 ----
    // 先看最近 1 秒：一个 I/O 都没完成 = 此刻根本没有"正在被响应"的 I/O，直接给 0。
    // 不能因为窗口里还留着几秒前的旧账就继续显示（负载一停数字再挂 4 秒，看着像假数据）。
    unsigned long long recent_completed =
        (after->reads - rate_before->reads) + (after->writes - rate_before->writes);

    int response_age = 0;

    if (recent_completed > 0) {

        // 有 I/O 才需要精度：从 1 秒开始往前找，找到第一个攒够目标完成量的窗口；
        // 一直不够就用环形缓冲里最长的一段（最多 4 秒）。
        response_age = max_age;

        for (int age = rate_age; age <= max_age; age++) {

            const TmDiskSample *base = disk_rt_age(slot, age);

            if (base == NULL || !base->valid) {
                continue;
            }

            // 这一段里有过计数器倒退（重插 / 内核重置）就跳过它，换更短的一段：
            // 差分会变成无意义的大数
            if (disk_stat_regressed(&base->stat, &newest->stat)) {
                continue;
            }

            unsigned long long done =
                (after->reads - base->stat.reads) + (after->writes - base->stat.writes);

            if (done >= (unsigned long long) TM_DISK_RESPONSE_TARGET_IOS) {

                response_age = age;

                break;
            }
        }
    }

    if (response_age == 0) {

        io.avg_response_ms = 0.0;
        io.has_response = true;

    } else {

        const TmDiskSample *response_base = disk_rt_age(slot, response_age);

        // 兜底选中的最长一段里也可能夹着倒退：那就退回干净的 1 秒窗口
        if (response_base != NULL &&
            disk_stat_regressed(&response_base->stat, &newest->stat)) {

            response_base = rate_base;
        }

        if (response_base != NULL && response_base->valid) {

            unsigned long long completed =
                (after->reads - response_base->stat.reads) +
                (after->writes - response_base->stat.writes);

            // weighted_ms 是每个 I/O 在队列里排队等待的毫秒数之和，除以完成量 = 单个 I/O
            // 的平均队列时间。整数毫秒的量化误差在除法里被完成量稀释，这就是精度来源。
            io.avg_response_ms = (completed > 0)
                                 ? ((double) (after->weighted_ms - response_base->stat.weighted_ms)
                                    / (double) completed)
                                 : 0.0;

            io.has_response = true;
        }
    }

    slot->io = io;
    slot->io_valid = true;
}

/**
 * 采样线程主体：每 TM_DISK_TICK_MS 枚举一次物理盘、读一遍 stat、结算一次。
 *
 * 每轮都重新枚举，是为了热插拔：插上的盘立刻有数据，拔掉的盘只是不再更新
 * （它的槽留在表里，请求侧按"过期"处理，不会再对外发陈旧值）。
 *
 * 全程不碰 stdout：那是主循环的协议通道，线程往里写会污染回包。
 */
static void *disk_sample_thread(void *arg)
{
    (void) arg;

    while (true) {

        TmDeviceRef refs[TM_DISK_SAMPLE_MAX];

        int count = enumerate_disk_devices(refs, TM_DISK_SAMPLE_MAX);

        long long at_ms = monotonic_ms();

        for (int i = 0; i < count; i++) {

            char stat_path[TM_PATH_MAX];

            snprintf(stat_path, sizeof(stat_path), "%s/stat", refs[i].path);

            TmBlockStat stat;

            // 读文件在锁外做：sysfs 偶尔会慢，不能让别人排队等
            if (!read_block_stat(stat_path, &stat)) {
                continue;
            }

            pthread_mutex_lock(&g_disk_rt_lock);

            TmDiskRuntime *slot = disk_rt_slot_locked(refs[i].name);

            if (slot != NULL) {

                slot->ring[slot->next].stat = stat;
                slot->ring[slot->next].at_ms = at_ms;
                slot->ring[slot->next].valid = true;

                slot->next = (slot->next + 1) % TM_DISK_WINDOW_SAMPLES;

                if (slot->count < TM_DISK_WINDOW_SAMPLES) {
                    slot->count++;
                }

                disk_rt_settle(slot);

                slot->updated_ms = at_ms;
            }

            pthread_mutex_unlock(&g_disk_rt_lock);
        }

        usleep(TM_DISK_TICK_MS * 1000);
    }

    return NULL;
}

/** 起采样线程（传 detached，退出时不需要 join）。起不来就算了，请求侧会自动退回现算。 */
static void disk_sampler_start(void)
{
    if (g_disk_rt_thread_on) {
        return;
    }

    g_disk_rt_thread_on = true;

    pthread_t thread;

    if (pthread_create(&thread, NULL, disk_sample_thread, NULL) != 0) {

        g_disk_rt_thread_on = false;

        return;
    }

    pthread_detach(thread);
}

/**
 * 查一块盘最近一次结算结果。
 *
 * @param name  块名（sda / nvme0n1 …）
 * @param found 出参：true = 拿到的是新鲜值，false = 没起来 / 还没攒够点 / 已过期
 * @return 四项指标；found=false 时内容全 0，调用方应改用 measure_disk_io 兜底
 */
static TmDiskIo disk_runtime_io(const char *name, bool *found)
{
    TmDiskIo io;

    memset(&io, 0, sizeof(io));

    if (found != NULL) {
        *found = false;
    }

    if (name == NULL || name[0] == '\0') {
        return io;
    }

    long long now = monotonic_ms();

    if (now <= 0) {
        return io;
    }

    pthread_mutex_lock(&g_disk_rt_lock);

    TmDiskRuntime *slot = disk_rt_find_locked(name);

    if (slot != NULL && slot->io_valid && slot->updated_ms > 0 &&
        (now - slot->updated_ms) <= TM_DISK_STALE_MS) {

        io = slot->io;

        if (found != NULL) {
            *found = true;
        }
    }

    pthread_mutex_unlock(&g_disk_rt_lock);

    return io;
}

/** 去掉 sysfs 文本尾部的换行与首尾空白（model / vendor 这类一行小文件都要过一遍）。 */
static void trim_disk_text(char *text)
{
    if (text == NULL) {
        return;
    }

    size_t length = strlen(text);

    while (length > 0) {

        char tail = text[length - 1];

        if (tail != '\n' && tail != '\r' && tail != ' ' && tail != '\t') {
            break;
        }

        text[length - 1] = '\0';
        length--;
    }

    size_t start = 0;

    while (text[start] == ' ' || text[start] == '\t') {
        start++;
    }

    if (start > 0) {
        memmove(text, text + start, strlen(text + start) + 1);
    }
}

/**
 * 读一块盘的硬件标识（wwid / 序列号）。
 *
 * 为什么要它：块名（sda / mmcblk0）是内核按枚举顺序分配的，可以复用——拔掉 sdb
 * 再插一块新盘，内核很可能还叫它 sdb。只靠块名认盘的话，新盘会继承旧盘的曲线。
 * 硬件标识写死在盘上（SCSI 的 VPD、NVMe 的 EUI、UFS / MMC 的序列号），换盘必变。
 *
 * 四条路径按顺序试，取第一个读到的：
 *   <block>/device/wwid    SCSI / UFS，形如 t10.ATA_____Samsung_SSD_…
 *   <block>/wwid           NVMe 命名空间，形如 nvme.<eui>.<nsid>
 *   <block>/device/serial  序列号（部分 SATA / MMC 控制器只给这一格）
 *   <block>/serial         同上，少数平台直接挂在块目录下
 *
 * @param status 读不到时写状态：节点全不存在 = UNSUPPORTED（平台不给），
 *               节点在但打不开 = ACCESS_DENIED（没权限，换个身份也许能读）
 * @return 读到返回 true，out 里是去掉首尾空白的标识
 */
static bool disk_read_hardware_id(const char *block_path, char *out, int capacity,
                                  TmQueryStatus *status)
{
    static const char *const suffixes[] = {
        "/device/wwid", "/wwid", "/device/serial", "/serial"
    };

    bool denied = false;

    if (status != NULL) {
        *status = TM_QUERY_UNSUPPORTED;
    }

    if (out == NULL || capacity <= 0 || block_path == NULL) {
        return false;
    }

    out[0] = '\0';

    for (size_t i = 0; i < sizeof(suffixes) / sizeof(suffixes[0]); i++) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "%s%s", block_path, suffixes[i]);

        // 路径不存在是常态（例如 NVMe 没有 device/wwid），直接换下一条，不算失败
        if (access(path, F_OK) != 0) {
            continue;
        }

        if (access(path, R_OK) != 0) {
            denied = true;
            continue;
        }

        char text[TM_OUT_VALUE_MAX];

        if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
            continue;
        }

        trim_disk_text(text);

        if (text[0] == '\0') {
            continue;
        }

        snprintf(out, (size_t) capacity, "%s", text);

        if (status != NULL) {
            *status = TM_QUERY_OK;
        }

        return true;
    }

    if (status != NULL) {
        *status = denied ? TM_QUERY_ACCESS_DENIED : TM_QUERY_UNSUPPORTED;
    }

    return false;
}

/** 不区分大小写地找子串（只用于 sysfs 链接路径里的 nvme / ufs / mmc 这些关键字）。 */
static bool text_contains_ci(const char *text, const char *needle)
{
    if (text == NULL || needle == NULL || needle[0] == '\0') {
        return false;
    }

    size_t need = strlen(needle);

    for (const char *cursor = text; cursor[0] != '\0'; cursor++) {

        size_t matched = 0;

        while (matched < need && cursor[matched] != '\0' &&
               tolower((unsigned char) cursor[matched]) == tolower((unsigned char) needle[matched])) {
            matched++;
        }

        if (matched == need) {
            return true;
        }
    }

    return false;
}

/**
 * 读厂商名：`/sys/block/<dev>/device/vendor`。
 *
 * 这是 SCSI 家族的通用节点 —— UFS / SATA / USB 存储都挂在 SCSI 子系统下，都有这个文件
 * （Redmi Turbo 3 上是 SAMSUNG）。NVMe 的设备目录里没有名字型 vendor，
 * 那个位置给的是 PCI 厂商号（0x144d 这种），所以读到 `0x` 开头的值一律丢掉，
 * 宁可页面上只显示型号，也不把十六进制编号当成厂商名摆出来。
 *
 * @param block_path `/sys/block/<dev>` 目录
 * @param out        输出（空串 = 没读到）
 */
static void read_disk_vendor(const char *block_path, char *out, size_t capacity)
{
    if (out == NULL || capacity == 0) {
        return;
    }

    out[0] = '\0';

    if (block_path == NULL || block_path[0] == '\0') {
        return;
    }

    char path[TM_PATH_MAX];
    char text[TM_OUT_VALUE_MAX];

    text[0] = '\0';

    snprintf(path, sizeof(path), "%s/device/vendor", block_path);

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return;
    }

    trim_disk_text(text);

    // 0x144d / 0X144D：PCI 厂商号，不是名字，直接用它是错的
    if (text[0] == '\0' || (text[0] == '0' && (text[1] == 'x' || text[1] == 'X'))) {
        return;
    }

    snprintf(out, capacity, "%s", text);
}

/**
 * 存储接口类型：NVMe / UFS / 其他（其他返回空串，调用方就不加前缀）。
 *
 * 判据是**设备拓扑**，不是块名。要读的是 `/sys/block/<dev>` **自己**那条链接：
 *
 *   ../../devices/platform/soc/1d84000.ufshc/host0/target0:0:0/0:0:0:0
 *                                   ^^^^^^ 高通把 UFS 主机控制器叫 ufshc
 *
 * NVMe 的路径长这样：
 *
 *   ../../devices/pci0000:00/0000:00:06.0/nvme/nvme0
 *
 * 所以顺着这条链接的文本找控制器名就够，不用一层层往上退目录：
 *   - 含 nvme                  → NVMe
 *   - 含 ufshc / ufshcd / ufs  → UFS
 *
 * **不能**拿 `subsystem = scsi` 当 UFS 判据：UFS / SATA / USB 存储都呈现为 SCSI 设备，
 * 只看子系统会把 SATA 硬盘也认成 UFS。
 *
 * 链接读不到（老内核 / 权限）时退回块名前缀：nvme0n1 这种一眼就是 NVMe。
 *
 * @param block_path `/sys/block/<dev>` 目录
 * @param block_name 块名（sda / nvme0n1 / mmcblk0 …）
 * @param out        输出前缀（空串 = 不加）
 */
static void disk_interface_type(const char *block_path, const char *block_name,
                                char *out, size_t capacity)
{
    if (out == NULL || capacity == 0) {
        return;
    }

    out[0] = '\0';

    char path[TM_PATH_MAX];
    char block_link[TM_PATH_MAX];
    char device_link[TM_PATH_MAX];

    block_link[0] = '\0';
    device_link[0] = '\0';

    if (block_path != NULL && block_path[0] != '\0') {

        // 关键证据在这里：/sys/block/<dev> 自己就是一条链接，文本带着整条控制器路径，
        // 例如 Android 上 UFS 盘的
        //   ../devices/platform/soc/1d84000.ufshc/host0/target0:0:0/0:0:0:0/block/sda
        //                                          ^^^^^^ 就是 UFS Host Controller
        //
        // 而 <dev>/device 只是"在同一层里指回自己"：文本是 ../../../0:0:0:0，
        // 里面没有控制器名 —— 上一版只查那条链接，所以 UFS 永远认不出来。
        // （NVMe 的 <dev>/device 恰好指向 ../nvme0，才碰巧能认出来。）
        read_link_text(block_path, block_link, (int) sizeof(block_link));

        snprintf(path, sizeof(path), "%s/device", block_path);

        read_link_text(path, device_link, (int) sizeof(device_link));
    }

    // 两条链接一起查：块链接给控制器路径，device 链接给 SCSI 家族 / NVMe 的那一层
    bool nvme = text_contains_ci(block_link, "nvme") || text_contains_ci(device_link, "nvme");

    bool ufs = text_contains_ci(block_link, "ufshc") || text_contains_ci(block_link, "ufshcd") ||
               text_contains_ci(device_link, "ufshc") || text_contains_ci(device_link, "ufshcd") ||
               text_contains_ci(block_link, "ufs") || text_contains_ci(device_link, "ufs");

    if (nvme) {

        snprintf(out, capacity, "NVMe");
        return;
    }

    if (ufs) {

        snprintf(out, capacity, "UFS");
        return;
    }

    if (block_name != NULL && strncmp(block_name, "nvme", 4) == 0) {
        snprintf(out, capacity, "NVMe");
    }
}

/** 不区分大小写地判断 text 是否以 prefix 开头（拼名字时用来防重复厂商名）。 */
static bool starts_with_ci(const char *text, const char *prefix)
{
    if (text == NULL || prefix == NULL) {
        return false;
    }

    size_t index = 0;

    for (; prefix[index] != '\0'; index++) {

        if (text[index] == '\0') {
            return false;
        }

        if (tolower((unsigned char) text[index]) != tolower((unsigned char) prefix[index])) {
            return false;
        }
    }

    return true;
}

/**
 * 这块盘是不是 USB 总线接的（就是类型文案里那对括号的 (USB)）。
 *
 * 为什么不能只看 device 链接：链到哪一层取决于内核拓扑，部分 USB 桥 / UAS 设备
 * 的链接落在 scsi_host 那一层，路径里根本没有 "usb" 这一段，于是只按链接判的话
 * 移动硬盘会被写成 (SCSI)。这里补三条兜底，任意一条命中就算 USB：
 *   1) device 链接的文本里有 usb（最常见，保留原有判据）；
 *   2) 把链接解析成绝对路径后再查一次（相对路径有时要在解析后才露出 usb1 那一段）；
 *   3) device/uevent 里的驱动程序名是 usb-storage / uas / usb 一类。
 * 三条都不中才算"不是 USB"，交给上层继续判 SCSI / 其它。
 */
static bool disk_is_usb_attached(const char *block_path, const char *device_target)
{
    if (block_path == NULL || block_path[0] == '\0') {
        return false;
    }

    if (device_target != NULL && text_contains_ci(device_target, "usb")) {
        return true;
    }

    char path[TM_PATH_MAX];

    // realpath 的 buffer 必须 >= PATH_MAX，否则强化的 libc 会在编译期直接报错

    char resolved[PATH_MAX];

    snprintf(path, sizeof(path), "%s/device", block_path);

    resolved[0] = '\0';

    if (realpath(path, resolved) != NULL && text_contains_ci(resolved, "usb")) {
        return true;
    }

    snprintf(path, sizeof(path), "%s/device/uevent", block_path);

    char uevent[512];

    if (read_text_file(path, uevent, (int) sizeof(uevent)) > 0) {

        if (text_contains_ci(uevent, "usb-storage") ||
            text_contains_ci(uevent, "driver=uas") ||
            text_contains_ci(uevent, "driver=usb")) {
            return true;
        }
    }

    return false;
}

/** 往名字缓冲区尾部追加一段，段间自动补一个空格；越界就截断，不越界写。 */
static void disk_name_append(char *buffer, size_t capacity, size_t *used, const char *part)
{
    if (buffer == NULL || used == NULL || part == NULL || part[0] == '\0' || capacity == 0) {
        return;
    }

    if (*used >= capacity - 1) {
        return;
    }

    int written = snprintf(buffer + *used, capacity - *used, (*used == 0) ? "%s" : " %s", part);

    if (written <= 0) {
        return;
    }

    size_t added = (size_t) written;

    *used = (*used + added >= capacity) ? (capacity - 1) : (*used + added);
}

/** 由 /proc/self/mountinfo 的 major:minor 反查块名（dm-1 / sda5 / mmcblk0p2 …）。 */
static bool block_name_of_devnum(const char *devnum, char *out, int capacity)
{
    if (devnum == NULL || devnum[0] == '\0' || capacity <= 0) {
        return false;
    }

    char path[TM_PATH_MAX];
    char link[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/sys/dev/block/%s", devnum);

    if (read_link_text(path, link, (int) sizeof(link)) <= 0) {
        return false;
    }

    name_from_path(link, out, capacity);

    return out[0] != '\0';
}

/**
 * 把一个块名归到某台物理盘。
 *
 * 三级匹配：
 * 1. 名字就是物理盘（sda / nvme0n1 / mmcblk0）；
 * 2. 是某台盘的分区（/sys/block/<disk>/<part>/dev 存在即成立）；
 * 3. dm-* / md 这类映射设备，跟着 slaves 递归到底层（限 4 层，防环）。
 *
 * @return 磁盘下标；-1 = 这个块设备不归任何一台物理盘（loop / zram / OTA 映射等）
 */
static int disk_index_of_block(const char *block_name,
                               const TmDeviceRef *disks, int disk_count, int depth)
{
    if (block_name == NULL || block_name[0] == '\0' || disks == NULL || disk_count <= 0) {
        return -1;
    }

    for (int i = 0; i < disk_count; i++) {

        if (strcmp(disks[i].name, block_name) == 0) {
            return i;
        }
    }

    for (int i = 0; i < disk_count; i++) {

        // 分区判据是"<盘>/<块名>/dev 存在"，这一条用 stat 就够，
        // 不需要列出盘目录 —— 所以列不了目录也不会把分区认丢。
        char probe[TM_PATH_MAX];
        struct stat info;

        snprintf(probe, sizeof(probe), "/sys/block/%s/%s/dev", disks[i].name, block_name);

        if (stat(probe, &info) == 0) {
            return i;
        }
    }

    if (depth < 4 &&
        (strncmp(block_name, "dm-", 3) == 0 || strncmp(block_name, "md", 2) == 0)) {

        char dir[TM_PATH_MAX];

        snprintf(dir, sizeof(dir), "/sys/block/%s/slaves", block_name);

        DIR *slaves = opendir(dir);

        if (slaves == NULL) {
            return -1;
        }

        int resolved = -1;

        struct dirent *entry;

        while ((entry = readdir(slaves)) != NULL) {

            if (entry->d_name[0] == '.') {
                continue;
            }

            resolved = disk_index_of_block(entry->d_name, disks, disk_count, depth + 1);

            if (resolved >= 0) {
                break;
            }
        }

        closedir(slaves);

        return resolved;
    }

    return -1;
}

/**
 * 扫一遍挂载表与 swap 表，填三件只有"整机视角"才知道的事。
 *
 * 已格式化容量：把每个已挂载文件系统的总容量（statvfs 的 blocks * frsize）
 * 累加到它所属的物理盘。同一块设备挂多次（/data 与 /data/user/0 这种）只算一次，
 * 否则容量会被重复累加。没有任何挂载命中时该盘记 -1，页面显示破折号。
 *
 * 系统盘：按 /system -> /vendor -> /data -> / 的顺序找第一个能落到物理盘的挂载。
 * Android 的 / 常常是 overlay（匿名块设备），所以把系统 / 厂商 / 数据分区排在前面。
 *
 * 页面文件：/proc/swaps 里的 swap 分区如果落在某台盘上就是它；zram 不归任何物理盘，
 * 所以纯 zram 的机器这一项是"否"，这才是实情。
 *
 * @param formatted       每台盘累加出来的字节数（调用方先清零）
 * @param formatted_known 每台盘是否有挂载命中（0/1）
 * @param system_disk     输出系统盘下标；-1 = 认不出来
 * @param page_file       每台盘是否承载 swap（0/1）
 */
static void collect_disk_mounts(const TmDeviceRef *disks, int disk_count,
                                long long *formatted, int *formatted_known,
                                int *system_disk, int *page_file)
{
    *system_disk = -1;

    static const char *const system_mounts[TM_DISK_SYSTEM_MOUNT_MAX] = {
        "/system", "/vendor", "/data", "/"
    };

    char system_dev[TM_DISK_SYSTEM_MOUNT_MAX][32];

    memset(system_dev, 0, sizeof(system_dev));

    // 同一个块设备（dm-3 / sda5 …）只累加一次容量
    char seen[TM_DISK_DEVNUM_MAX][32];

    memset(seen, 0, sizeof(seen));

    int seen_count = 0;

    FILE *file = fopen("/proc/self/mountinfo", "r");

    if (file != NULL) {

        char line[TM_LINE_MAX];

        while (fgets(line, (int) sizeof(line), file) != NULL) {

            char devnum[32];
            char mountpoint[TM_PATH_MAX];

            // 前两列是 mount id 与 parent id，第三列 major:minor，第五列挂载点
            if (sscanf(line, "%*s %*s %31s %*s %511s", devnum, mountpoint) != 2) {
                continue;
            }

            // 0:xx 是匿名块设备：overlay / tmpfs / proc / sysfs 全在这里，都不是磁盘
            if (strncmp(devnum, "0:", 2) == 0) {
                continue;
            }

            for (int k = 0; k < TM_DISK_SYSTEM_MOUNT_MAX; k++) {

                if (system_dev[k][0] == '\0' && strcmp(mountpoint, system_mounts[k]) == 0) {
                    snprintf(system_dev[k], sizeof(system_dev[k]), "%s", devnum);
                }
            }

            int already = 0;

            for (int s = 0; s < seen_count; s++) {

                if (strcmp(seen[s], devnum) == 0) {
                    already = 1;
                    break;
                }
            }

            if (already) {
                continue;
            }

            if (seen_count < TM_DISK_DEVNUM_MAX) {
                snprintf(seen[seen_count], sizeof(seen[0]), "%s", devnum);
                seen_count++;
            }

            char block_name[64];

            if (!block_name_of_devnum(devnum, block_name, (int) sizeof(block_name))) {
                continue;
            }

            int index = disk_index_of_block(block_name, disks, disk_count, 0);

            if (index < 0) {
                continue;
            }

            struct statvfs vfs;

            if (statvfs(mountpoint, &vfs) != 0) {
                continue;
            }

            long long unit = (vfs.f_frsize > 0) ? (long long) vfs.f_frsize : (long long) vfs.f_bsize;

            if (unit <= 0) {
                continue;
            }

            long long total = (long long) vfs.f_blocks * unit;

            if (total <= 0) {
                continue;
            }

            formatted[index] += total;
            formatted_known[index] = 1;
        }

        fclose(file);
    }

    for (int k = 0; k < TM_DISK_SYSTEM_MOUNT_MAX && *system_disk < 0; k++) {

        if (system_dev[k][0] == '\0') {
            continue;
        }

        char block_name[64];

        if (!block_name_of_devnum(system_dev[k], block_name, (int) sizeof(block_name))) {
            continue;
        }

        *system_disk = disk_index_of_block(block_name, disks, disk_count, 0);
    }

    file = fopen("/proc/swaps", "r");

    if (file != NULL) {

        char line[TM_LINE_MAX];

        while (fgets(line, (int) sizeof(line), file) != NULL) {

            // 表头是 Filename...，数据行一定是设备路径或文件路径
            if (line[0] != '/') {
                continue;
            }

            char filename[TM_PATH_MAX];

            if (sscanf(line, "%511s", filename) != 1) {
                continue;
            }

            char block_name[64];

            name_from_path(filename, block_name, (int) sizeof(block_name));

            int index = disk_index_of_block(block_name, disks, disk_count, 0);

            if (index >= 0) {
                page_file[index] = 1;
            }
        }

        fclose(file);
    }
}

/**
 * 把磁盘实例填进包：每台盘一块，字段与页面一一对应。
 *
 * 字段（键名与客户端 PerformanceSnapshot 的解析逐项一致）：
 *   name              型号串（读不到退回块名，不发空串）
 *   media             介质：HDD / SSD / UFS / eMMC
 *   bus               总线：NVMe / SCSI / MMC / VIRTIO / USB
 *   capacity_bytes    容量（字节）
 *   formatted_bytes   已格式化容量（该盘上已挂载文件系统的容量之和）
 *   system_disk       1/0；整机认不出系统盘时这一格不发，页面显示破折号
 *   page_file         1/0
 *   activity_percent  活动时间 0..100
 *   avg_response_ms   平均响应毫秒
 *   read_bytes_per_sec / write_bytes_per_sec 读写速度（字节/秒）
 */
static int build_disk_devices(TmOutDevice *out, int capacity,
                              const TmDeviceRef *devices, int count)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    int limit = count;

    if (limit > capacity) {
        limit = capacity;
    }

    if (limit > TM_OUT_DISK_MAX) {
        limit = TM_OUT_DISK_MAX;
    }

    long long formatted[TM_OUT_DISK_MAX];
    int formatted_known[TM_OUT_DISK_MAX];
    int page_file[TM_OUT_DISK_MAX];

    for (int i = 0; i < TM_OUT_DISK_MAX; i++) {
        formatted[i] = 0;
        formatted_known[i] = 0;
        page_file[i] = 0;
    }

    int system_disk = -1;

    collect_disk_mounts(devices, limit, formatted, formatted_known, &system_disk, page_file);

    for (int i = 0; i < limit; i++) {

        TmOutDevice *device = &out[i];

        device_begin(device, devices[i].id, TM_DEV_TYPE_DISK);

        char path[TM_PATH_MAX];

        // 介质与总线分开报：只看块名前缀会把 UFS（挂在 SCSI 子系统下）当成 SATA SSD，
        // 所以判据是 device 链接指向的控制器目录 + queue/rotational。
        char target[TM_PATH_MAX];

        target[0] = '\0';

        snprintf(path, sizeof(path), "%s/device", devices[i].path);

        read_link_text(path, target, (int) sizeof(target));

        // 接口类型（NVMe / UFS / 其他）：判据同样落在这条链接上，
        // 具体规则集中在 disk_interface_type() 里，免得介质判定和名字前缀各写一套
        char interface_type[TM_OUT_VALUE_MAX];

        disk_interface_type(devices[i].path, devices[i].name, interface_type,
                            sizeof(interface_type));

        // 设备名 = 接口前缀 + 厂商 + 型号，例如：
        //   NVMe SAMSUNG MZVLQ512HBLU-00B00
        //   UFS  SAMSUNG KLUEG4RHHF-F0G1
        //   SAMSUNG NUEWBJ-DEWDC     （既不是 NVMe 也不是 UFS：不加前缀）
        // 型号里已经带了厂商名（NVMe 的 model 常常就是 "SAMSUNG MZVLQ…"）时不再重复拼一遍
        char model[TM_OUT_VALUE_MAX];

        char vendor[TM_OUT_VALUE_MAX];

        model[0] = '\0';
        vendor[0] = '\0';

        snprintf(path, sizeof(path), "%s/device/model", devices[i].path);

        if (read_text_file(path, model, (int) sizeof(model)) > 0) {
            trim_disk_text(model);
        }

        read_disk_vendor(devices[i].path, vendor, sizeof(vendor));

        char display[TM_OUT_VALUE_MAX * 2];

        display[0] = '\0';

        size_t display_used = 0;

        disk_name_append(display, sizeof(display), &display_used, interface_type);

        if (vendor[0] != '\0' && !starts_with_ci(model, vendor)) {
            disk_name_append(display, sizeof(display), &display_used, vendor);
        }

        disk_name_append(display, sizeof(display), &display_used, model);

        // 型号与厂商都缺（比如 /sys 被裁过的老机器）就退回块名 sda，右上角不至于空着
        device_put(device, "name", display[0] != '\0' ? display : devices[i].name);

        // 块设备名（sda / sdb / mmcblk0 / nvme0n1）：页面标题写"磁盘 0 (sda)"用。
        // 它与 name 是两件事：name 是"厂商 + 型号"，不含块名（只有型号都缺时才退回块名）。
        device_put(device, "node", devices[i].name);

        // 硬件标识（wwid / 序列号）：页面不显示，只给上层曲线缓存做最高优先级的键，
        // 调试页转储字段时能看见，便于核对"这块盘到底认成了谁"。
        // 读不到也照发一格状态：字段不删，让人一眼看出是平台不给还是没权限。
        char hardware_id[TM_OUT_VALUE_MAX];

        TmQueryStatus hardware_status = TM_QUERY_UNSUPPORTED;

        if (disk_read_hardware_id(devices[i].path, hardware_id, (int) sizeof(hardware_id),
                                  &hardware_status)) {
            device_put_value(device, "wwid", query_value_text(TM_QUERY_OK, hardware_id));
        } else {
            device_put_value(device, "wwid", query_value_none(hardware_status));
        }

        double rotational = 0.0;

        int has_rotation = 0;

        snprintf(path, sizeof(path), "%s/queue/rotational", devices[i].path);

        if (read_first_number(path, &rotational)) {
            has_rotation = 1;
        }

        const char *media = NULL;
        const char *bus = NULL;

        if (text_contains_ci(target, "nvme") || strncmp(devices[i].name, "nvme", 4) == 0) {

            bus = "NVMe";
            media = "SSD";

        } else if (text_contains_ci(target, "ufs")) {

            // UFS 走 SCSI 子系统，块名是 sdX：总线照实报 SCSI，介质报 UFS
            media = "UFS";
            bus = "SCSI";

        } else if (text_contains_ci(target, "mmc") || text_contains_ci(target, "sdhci")) {

            media = "eMMC";
            bus = "MMC";

        } else if (text_contains_ci(target, "virtio")) {

            bus = "VIRTIO";

        } else if (disk_is_usb_attached(devices[i].path, target)) {

            // 移动硬盘 / U 盘：括号里写 (USB)；介质仍由 queue/rotational 判 SSD / HDD
            bus = "USB";

        } else if (text_contains_ci(target, "scsi") || strncmp(devices[i].name, "sd", 2) == 0) {

            bus = "SCSI";
        }

        if (media == NULL && has_rotation) {
            media = (rotational > 0.5) ? "HDD" : "SSD";
        }

        if (media != NULL) {
            device_put(device, "media", media);
        }

        if (bus != NULL) {
            device_put(device, "bus", bus);
        }

        // 容量：size 的 512 字节扇区数
        double sectors = 0.0;

        long long capacity_bytes = -1;

        snprintf(path, sizeof(path), "%s/size", devices[i].path);

        if (read_first_number(path, &sectors) && sectors > 0.0 && sectors < 1.0e15) {
            capacity_bytes = (long long) (sectors * (double) TM_DISK_SECTOR_BYTES);
        }

        device_put_int(device, "capacity_bytes", capacity_bytes, TM_FIELD_STATUS_NOT_AVAILABLE);

        // 已格式化容量：没有挂载命中就是没有值，不拿 0 顶替
        device_put_int(device, "formatted_bytes",
                       formatted_known[i] ? formatted[i] : -1,
                       TM_FIELD_STATUS_NOT_AVAILABLE);

        // 系统盘：整机认不出来就整格不发（页面显示破折号，而不是"否"）
        if (system_disk >= 0) {
            device_put(device, "system_disk", (i == system_disk) ? "1" : "0");
        }

        device_put(device, "page_file", page_file[i] ? "1" : "0");

        // 实时四项：优先取后台采样线程结算好的值 —— 它是固定 250 ms 一拍、
        // 按最近 1 秒窗口算的，与 UI 选的是高 / 常规 / 低档完全无关。
        // 线程没起来或值已过期，才退回老办法（用相邻两次请求的差值现算）。
        bool runtime_ready = false;

        TmDiskIo io = disk_runtime_io(devices[i].name, &runtime_ready);

        if (!runtime_ready) {

            snprintf(path, sizeof(path), "%s/stat", devices[i].path);

            io = measure_disk_io(devices[i].name, path);
        }

        device_put_value(device, "activity_percent",
                         query_value_float(io.has_activity, io.activity_percent, 2,
                                           TM_QUERY_NO_BASELINE));

        // 平均响应发两位小数：自适应窗口已经把分辨率压到 0.1 ms 一档，
        // 协议侧再只发一位就等于把精度又截掉了。
        device_put_value(device, "avg_response_ms",
                         query_value_float(io.has_response, io.avg_response_ms, 2,
                                           TM_QUERY_NO_BASELINE));

        device_put_int(device, "read_bytes_per_sec",
                       io.has_read ? (long long) io.read_bytes_per_sec : -1,
                       TM_FIELD_STATUS_NO_BASELINE);

        device_put_int(device, "write_bytes_per_sec",
                       io.has_write ? (long long) io.write_bytes_per_sec : -1,
                       TM_FIELD_STATUS_NO_BASELINE);
    }

    return limit;
}

// ============================================================
// 内存设备：全部走 Linux 通用接口
//
// 分工写在最前面，免得后来的人又去加 Android 私有接口重复采：
//   统计量（总量 / 可用 / 使用中 / 已提交 / 已缓存 / 内核可回收…）只读 /proc/meminfo，
//     Android 的 ActivityManager 给的也是同一份内核数据，再采一遍纯属浪费；
//   压缩量读 /sys/block/zram*\/mm_stat 的 mem_used_total（多设备必须遍历，不能写死 zram0）；
//   规格读 Device Tree 的 ddr_type 属性（高通把这套枚举放在 dt 里）。
//
// Linux 通用接口给不出的（标称容量、插槽数、外观规格、为硬件保留、速度）这里一律不发，
// 由上层按缺省显示，等 Android 侧补 —— 不拿相似指标冒充真数据。
// ============================================================

/**
 * 从 /proc/meminfo 的文本里取一项，换算成字节。
 *
 * 每行形如 `MemTotal:        3908 kB`：键必须从行首开始整段匹配并紧跟冒号，
 * 所以 "Cached" 不会误配到 "SwapCached"。带 kB 单位的乘 1024，不带的按个数返回。
 *
 * @param text meminfo 全文
 * @param key  行首键名，如 MemTotal / Inactive(file)
 * @return 字节数；-1 = 没有这一项，或这一行的值不是数字
 */
static long long meminfo_bytes(const char *text, const char *key)
{
    if (text == NULL || key == NULL || key[0] == '\0') {
        return -1;
    }

    size_t key_length = strlen(key);

    const char *cursor = text;

    while (cursor != NULL && cursor[0] != '\0') {

        if (strncmp(cursor, key, key_length) == 0 && cursor[key_length] == ':') {

            const char *number = cursor + key_length + 1;

            while (number[0] == ' ' || number[0] == '\t') {
                number++;
            }

            char *end = NULL;

            long long value = (long long) strtoll(number, &end, 10);

            if (end == number || value < 0) {
                return -1;
            }

            while (end[0] == ' ' || end[0] == '\t') {
                end++;
            }

            if (end[0] == 'k' && end[1] == 'B') {
                return value * 1024LL;
            }

            return value;
        }

        const char *newline = strchr(cursor, '\n');

        if (newline == NULL) {
            break;
        }

        cursor = newline + 1;
    }

    return -1;
}

/**
 * zram 一族的扫描结果（针对一个 sysfs 目录）。
 *
 * 三个 bool 就是"三态"本身：目录列没列成、有没有看到设备、有没有读到数值。
 * 必须分开记 —— 合成一个返回值就会出现"设备在但读不到"被当成"没有 zram"。
 */
typedef struct {
    bool dir_listed;      // 目录枚举成功，能确认里面有没有 zram
    bool device_seen;     // 见到 / 探测到 zram 设备存在
    bool value_read;      // 至少一块读到了 mm_stat
    long long original;   // orig_data_size 累加
    long long compressed; // compr_data_size 累加
    long long used;       // mem_used_total 累加
    long long disksize;   // disksize 累加
    bool disksize_read;
} TmZramDirScan;

/**
 * 读一块 zram 的 mm_stat 与 disksize。
 *
 * mm_stat 九列的顺序（内核文档固定）：
 *   orig_data_size compr_data_size mem_used_total mem_limit mem_used_max
 *   same_pages pages_compacted huge_pages huge_pages_since
 * 只用前三列，各有各的含义，不能互相顶替：
 *   1) 压缩前的数据量
 *   2) 压缩后的数据量
 *   3) 实际占住的物理内存（含分配器碎片与元数据）
 *
 * @return true = mm_stat 读到了；false = 这块读不到（权限 / 格式不对）
 */
static bool zram_read_one(const char *base_dir, const char *name, TmZramDirScan *scan)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/%s/mm_stat", base_dir, name);

    char text[256];

    long long orig = 0;
    long long compr = 0;
    long long used = 0;

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    if (sscanf(text, "%lld %lld %lld", &orig, &compr, &used) != 3) {
        return false;
    }

    scan->original += orig;
    scan->compressed += compr;

    if (used > 0) {
        // 多块 zram 相加；0 说明这块还没吃内存，加不加都不影响总和
        scan->used += used;
    }

    snprintf(path, sizeof(path), "%s/%s/disksize", base_dir, name);

    double size = 0.0;

    if (read_first_number(path, &size) && size > 0.0) {
        scan->disksize += (long long) size;
        scan->disksize_read = true;
    }

    return true;
}

/**
 * 扫一个目录（/sys/block 或 /sys/class/block）里的 zram 设备。
 *
 * 两步：先枚举；列不了目录（SELinux 只给 search 不给 readdir）就按 zram0..zram3
 * 固定编号 stat + 读 mm_stat —— 块设备编号连续，单块读得动就够，不该因为
 * 列不了目录就判"这台机器没开压缩内存"。
 */
static void zram_scan_dir(const char *base_dir, TmZramDirScan *scan)
{
    memset(scan, 0, sizeof(*scan));

    DIR *dir = opendir(base_dir);

    if (dir != NULL) {

        scan->dir_listed = true;

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "zram", 4) != 0) {
                continue;
            }

            scan->device_seen = true;

            if (zram_read_one(base_dir, entry->d_name, scan)) {
                scan->value_read = true;
            }
        }

        closedir(dir);

        return;
    }

    for (int index = 0; index < 4; index++) {

        char name[16];

        snprintf(name, sizeof(name), "zram%d", index);

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "%s/%s", base_dir, name);

        struct stat info;

        if (stat(path, &info) != 0) {
            continue;
        }

        scan->device_seen = true;

        if (zram_read_one(base_dir, name, scan)) {
            scan->value_read = true;
        }
    }
}

/** zram 汇总状态：确认没有 / 精确读到 / 设备在但读不到 / 连有没有都不知道。 */
#define TM_ZRAM_NONE 0
#define TM_ZRAM_OK 1
#define TM_ZRAM_UNREADABLE 2
#define TM_ZRAM_UNKNOWN 3

typedef struct {
    int state;
    long long original_bytes;
    long long compressed_bytes;
    long long used_bytes;
    long long disksize_bytes;
    bool disksize_known;
} TmZramInfo;

/**
 * 从 /proc/swaps 里取 zram 设备已用掉的交换量（字节）。
 *
 * 这一行是**压缩前**的数据量，只用来回答"zram 是不是正在当交换用"，
 * 不能当成压缩后占用，也不能除以某个压缩比反推内存 —— 它和 mem_used_total
 * 本来就不是同一个口径（same_pages 之类会让两者对不上）。
 *
 * @return 字节数；-1 = 没有 zram 交换，或这个文件读不到
 */
static long long read_zram_swap_used_bytes(void)
{
    // 多块 zram 也只有几行，2 KB 足够
    static char text[2048];

    if (read_text_file("/proc/swaps", text, (int) sizeof(text)) <= 0) {
        return -1;
    }

    long long total = 0;
    int found = 0;

    char *cursor = text;

    while (cursor != NULL && cursor[0] != '\0') {

        char *newline = strchr(cursor, '\n');

        if (newline != NULL) {
            newline[0] = '\0';
        }

        // 表头是 Filename Type Size Used Priority，不含 zram，会被这一条挡掉
        if (strstr(cursor, "zram") != NULL) {

            char name[64];
            char type[32];
            long long size_kb = 0;
            long long used_kb = 0;

            if (sscanf(cursor, "%63s %31s %lld %lld", name, type, &size_kb, &used_kb) == 4 &&
                used_kb > 0) {

                total += used_kb * 1024LL;
                found = 1;
            }
        }

        if (newline == NULL) {
            break;
        }

        cursor = newline + 1;
    }

    return found ? total : -1;
}

/**
 * 交换空间：/proc/swaps 的 Size / Used 两列，所有 swap 设备一起加（不只 zram）。
 *
 * Size 是设备配置容量（zram 的 disksize、磁盘 swap 分区的分区大小），Used 是
 * 已用交换量（压缩前口径，别拿它当内存占用）。表头那一行 sscanf 不认数字，
 * 自然被挡掉。
 *
 * @return 0 = 读到了（没有设备行时 total / used 都是 0，属于"没配交换"）；
 *         -1 = 文件读不到
 */
static int read_swap_table_bytes(long long *total_out, long long *used_out)
{
    static char text[2048];

    if (read_text_file("/proc/swaps", text, (int) sizeof(text)) <= 0) {
        return -1;
    }

    long long total = 0;
    long long used = 0;

    char *cursor = text;

    while (cursor != NULL && cursor[0] != '\0') {

        char *newline = strchr(cursor, '\n');

        if (newline != NULL) {
            newline[0] = '\0';
        }

        char name[64];
        char type[32];
        long long size_kb = 0;
        long long used_kb = 0;

        if (sscanf(cursor, "%63s %31s %lld %lld", name, type, &size_kb, &used_kb) == 4) {

            total += size_kb * 1024LL;
            used += used_kb * 1024LL;
        }

        if (newline == NULL) {
            break;
        }

        cursor = newline + 1;
    }

    *total_out = total;
    *used_out = used;

    return 0;
}

/**
 * 压缩内存：所有 zram 设备实际占住的物理内存（mm_stat 第 3 列 mem_used_total）。
 *
 * 来源只有两条，都是同一个 inode 的两条路径：
 *   1) /sys/block/zramN/mm_stat
 *   2) /sys/class/block/zramN/mm_stat
 * 两处是同一批设备，**只取其一**累加，绝不两条路径相加（会翻倍）。
 *
 * 刻意不做的两件事：
 *   - 不拿 disksize 当已压缩内存（那是容量上限，不是用量）；
 *   - 不拿 /proc/swaps 的 Used 除以假定压缩比。压缩比随内容在 1:1..4:1 之间浮动，
 *     mem_used_total 还含分配器碎片与元数据，"原始量 / 3"只是猜的，
 *     看着像精确值最误导。读不到就如实报读不到。
 *
 * 状态：
 *   NONE        两处都确认没有 zram（压缩内存 0 是有效结论）
 *   UNREADABLE  看到设备但数值读不到（多半是 SELinux）
 *   UNKNOWN     目录本身都列不了，连有没有都不知道
 */
static void read_zram_info(TmZramInfo *info)
{
    memset(info, 0, sizeof(*info));

    TmZramDirScan block_scan;
    TmZramDirScan class_scan;

    zram_scan_dir("/sys/block", &block_scan);
    zram_scan_dir("/sys/class/block", &class_scan);

    const TmZramDirScan *source = NULL;

    if (block_scan.value_read) {
        source = &block_scan;
    } else if (class_scan.value_read) {
        source = &class_scan;
    }

    if (source != NULL) {

        info->state = TM_ZRAM_OK;
        info->original_bytes = source->original;
        info->compressed_bytes = source->compressed;
        info->used_bytes = source->used;
        info->disksize_bytes = source->disksize;
        info->disksize_known = source->disksize_read;

        return;
    }

    if (block_scan.device_seen || class_scan.device_seen) {
        info->state = TM_ZRAM_UNREADABLE;
        return;
    }

    if (block_scan.dir_listed || class_scan.dir_listed) {
        info->state = TM_ZRAM_NONE;
        return;
    }

    info->state = TM_ZRAM_UNKNOWN;
}

/** 高通内核头文件里 ddr_type 的枚举顺序，值即下标的含义与厂商一致。 */
static const char *const k_dram_type_names[] = {
    NULL,           // 0 没有 DDR，按读不到处理
    "LPDDR1",
    "LPDDR2",
    "DDR2",
    "DDR3",
    "LPDDR3",
    "LPDDR4",
    "LPDDR4X",
    "LPDDR5",
    "LPDDR5X"
};

#define TM_DRAM_TYPE_COUNT 10

/**
 * 把一段 dt 属性解释成内存规格名。
 *
 * 属性可能是两种形态：
 *   纯文本（compatible 之类的字符串表）—— 转大写后按关键字认，长的先认，
 *     否则 LPDDR5X 会被 LPDDR5 抢先匹配；
 *   二进制（高通 ddr_type，big-endian 的 u32）—— 小枚举值只有最后一个字节非零，
 *     取它当下标查表。
 *
 * @return true = 认出来了，out 里是规格名；false = 认不出，调用方不要发这个字段
 */
static bool dram_spec_from_property(const char *raw, int length, char *out, int capacity)
{
    if (raw == NULL || length <= 0 || out == NULL || capacity <= 0) {
        return false;
    }

    bool printable = true;

    for (int i = 0; i < length; i++) {

        unsigned char ch = (unsigned char) raw[i];

        if (ch != 0 && (ch < 0x20 || ch > 0x7e)) {
            printable = false;
            break;
        }
    }

    if (printable) {

        char upper[64];

        int count = length < (int) sizeof(upper) - 1 ? length : (int) sizeof(upper) - 1;

        for (int i = 0; i < count; i++) {
            upper[i] = (char) toupper((unsigned char) raw[i]);
        }

        upper[count] = '\0';

        static const char *const k_names[] = {
            "LPDDR5X", "LPDDR5", "LPDDR4X", "LPDDR4", "LPDDR3", "LPDDR2", "LPDDR1",
            "DDR4", "DDR3", "DDR2"
        };

        for (int i = 0; i < (int) (sizeof(k_names) / sizeof(k_names[0])); i++) {

            if (strstr(upper, k_names[i]) != NULL) {
                snprintf(out, (size_t) capacity, "%s", k_names[i]);
                return true;
            }
        }

        return false;
    }

    int value = (int) ((unsigned char) raw[length - 1]);

    if (value > 0 && value < TM_DRAM_TYPE_COUNT && k_dram_type_names[value] != NULL) {
        snprintf(out, (size_t) capacity, "%s", k_dram_type_names[value]);
        return true;
    }

    return false;
}

/**
 * 读一个 Device Tree 属性当 big-endian 的无符号整数。
 *
 * dt 的标量属性固定 4 字节、大端存放，所以 00 00 00 08 就是 8。
 * 只有 4 字节以内才当标量：再长说明这个属性是数组或字符串表，
 * 硬解出来的第一个数没有意义。
 *
 * @return 数值；-1 = 读不到，或长度不像标量
 */
static long long read_dt_u32(const char *path)
{
    unsigned char raw[8];

    int length = read_text_file(path, (char *) raw, (int) sizeof(raw));

    if (length <= 0 || length > 4) {
        return -1;
    }

    long long value = 0;

    for (int i = 0; i < length; i++) {
        value = (value << 8) | (long long) raw[i];
    }

    return value;
}

/**
 * 内存规格：高通的 ddr_device_type 属性就是这套枚举，值即下标。
 *
 * 除了高通自己的位置，另外几个通用路径也顺手试一下，读不到就整项不发。
 * 值是二进制（big-endian u32），所以走 dram_spec_from_property 的二进制分支。
 */
static bool read_dram_spec(char *out, int capacity)
{
    static const char *const k_paths[] = {
        "/proc/device-tree/memory/ddr_device_type",
        "/proc/device-tree/qcom,ddr-type",
        "/proc/device-tree/qcom,ddr_type",
        "/proc/device-tree/ddr-type",
        "/proc/device-tree/ddr_type",
        "/proc/device-tree/dram/ddr_type",
        "/proc/device-tree/memory/ddr_type",
        "/sys/firmware/devicetree/base/memory/ddr_device_type",
        "/sys/firmware/devicetree/base/qcom,ddr-type",
        "/sys/firmware/devicetree/base/ddr_type"
    };

    static char raw[64];

    for (int i = 0; i < (int) (sizeof(k_paths) / sizeof(k_paths[0])); i++) {

        int length = read_text_file(k_paths[i], raw, (int) sizeof(raw));

        if (length <= 0) {
            continue;
        }

        // 只用到 0..length-1：read_text_file 会在末尾补一个 0，不能算进属性内容
        if (dram_spec_from_property(raw, length, out, capacity)) {
            return true;
        }
    }

    return false;
}

/**
 * 内存颗粒厂商代码表：dt 的 ddr_manufacturer_id 就是这套编码（0x01..0xFF，编号是稀疏的）。
 *
 * 编码来自 JEDEC 的 DRAM 厂商编号，和高通内核头文件里的枚举一致；
 * 表里没有的编号（预留 / 新型号）一律返回 NULL —— 上层据此整项不发，
 * 页面连"厂商"这一行都不画，不写"未知厂商"占位。
 */
static const char *dram_manufacturer_name(int code)
{
    switch (code) {

        case 0x01: return "三星（Samsung）";

        case 0x02: return "奇梦达（Qimonda）";

        case 0x03: return "尔必达（Elpida）";

        case 0x04: return "钰创（Etron）";

        case 0x05: return "南亚科技（Nanya）";

        case 0x06: return "海力士（Hynix）";

        case 0x07: return "茂矽电子（Mosel）";

        case 0x08: return "华邦电子（Winbond）";

        case 0x09: return "晶豪科技（ESMT）";

        case 0x0B: return "飞索半导体（Spansion）";

        case 0x0C: return "超捷半导体（SST）";

        case 0x0D: return "智原科技（ZMOS）";

        case 0x0E: return "英特尔（Intel）";

        case 0xFE: return "美光旗下（Numonyx）";

        case 0xFF: return "美光（Micron）";

        default: return NULL;
    }
}

/**
 * 把一段 dt 属性解释成厂商代码，再换成人看的名字。
 *
 * 属性有两种形态（和 ddr_type 那套是同一个道理）：
 *   二进制 —— u8（一个字节 0x01），或 big-endian 的 u32（00 00 00 01），
 *             两种形态的编码值都在最后一个字节里，取它即可；
 *   纯文本 —— 有些内核把 dt 属性导出成文本（"0x01" / "1"），按整数解析。
 *
 * @return true = 认出来了，out 里是厂商名；false = 读不到 / 编码不在表里
 */
static bool dram_manufacturer_from_property(const char *raw, int length, char *out, int capacity)
{
    if (raw == NULL || length <= 0 || out == NULL || capacity <= 0) {
        return false;
    }

    bool printable = true;

    for (int i = 0; i < length; i++) {

        unsigned char ch = (unsigned char) raw[i];

        if (ch < 0x20 || ch > 0x7e) {
            printable = false;
            break;
        }
    }

    int code = -1;

    if (printable) {

        char text[16];

        int count = length < (int) sizeof(text) - 1 ? length : (int) sizeof(text) - 1;

        memcpy(text, raw, (size_t) count);

        text[count] = '\0';

        trim_disk_text(text);

        code = (int) strtol(text, NULL, 0);

    } else {

        // u8 与 big-endian u32 的编码值都落在最后一个字节
        code = (int) ((unsigned char) raw[length - 1]);
    }

    const char *name = dram_manufacturer_name(code);

    if (name == NULL) {
        return false;
    }

    snprintf(out, (size_t) capacity, "%s", name);

    return true;
}

/**
 * 内存颗粒厂商：先查 /proc/device-tree，再查 /sys/firmware/devicetree/base。
 *
 * 两个路径都试一遍：Android 上 /proc/device-tree 通常是 /sys/firmware/devicetree/base
 * 的软链，但被裁过的系统 / SELinux 可能只放行其中一个。
 *
 * @return true = 读到了且编码认得出来；false = 调用方不要发这个字段
 */
static bool read_dram_manufacturer(char *out, int capacity)
{
    static const char *const k_paths[] = {
        "/proc/device-tree/memory/ddr_manufacturer_id",
        "/sys/firmware/devicetree/base/memory/ddr_manufacturer_id"
    };

    static char raw[16];

    for (int i = 0; i < (int) (sizeof(k_paths) / sizeof(k_paths[0])); i++) {

        int length = read_text_file(k_paths[i], raw, (int) sizeof(raw));

        if (length <= 0) {
            continue;
        }

        if (dram_manufacturer_from_property(raw, length, out, capacity)) {
            return true;
        }
    }

    return false;
}

/**
 * 标称容量：高通 dt 的 ddr_size，单位是 GB（8 就是 8 GB）。
 *
 * 为什么不用 MemTotal：那是内核能用的内存，已经被保留区啃掉一截，
 * 拿它当商品标称容量会少一档（8 GB 的机器显示 7.4 GB）。
 *
 * @return 字节数；-1 = 读不到或值明显不对（0、超过 4 TB）
 */
static long long read_dram_size_bytes(void)
{
    long long gigabytes = read_dt_u32("/proc/device-tree/memory/ddr_size");

    if (gigabytes <= 0 || gigabytes > 4096) {
        return -1;
    }

    return gigabytes * 1024LL * 1024LL * 1024LL;
}

// ============================================================
// 硬件保留内存：物理地址区间那一条线
//
// 任务管理器里的"为硬件保留的内存"= 物理内存里不能交给操作系统普通管理的那一截。
// Android 没有现成的计数器，只能自己拼：
//
//   1) 标称容量（高通 dt 的 ddr_size）是商品上写的那个数；
//   2) MemTotal 是内核真正接管的部分；
//   3) 两者之差就是固件 / Hypervisor / 各子系统 carveout / 内核镜像自身这些
//      "内核没拿到"的字节数 —— 页面显示的就是它；
//   4) /proc/iomem 的 System RAM 与设备树 reserved-memory 再算一份
//      "按地址区间对得上的预留量"，口径不同，只给调试页交叉验证用。
//
// 为什么不直接把设备树里所有 reg 相加：带 reusable 的区域（CMA、qdss 这类）
// 允许内核回收，动态 size 的节点又没有固定地址，直接相加会把能用的内存
// 也算成保留。
// ============================================================

/** 一段物理地址区间（闭区间，单位字节）。 */
typedef struct {
    long long start;
    long long size;
} TmMemRange;

/** 区间表容量：System RAM 的段数加上设备树预留区，一页装得下。 */
#define TM_MEM_RANGE_MAX 64

/** 32 位大端单元（设备树 reg 的每个 cell 都是 4 字节大端）。 */
static unsigned long long mem_cell32(const unsigned char *raw)
{
    return ((unsigned long long) raw[0] << 24) |
           ((unsigned long long) raw[1] << 16) |
           ((unsigned long long) raw[2] << 8) |
           (unsigned long long) raw[3];
}

/**
 * 按大端 cell 拼一个 64 位值。
 *
 * arm64 的地址 / 长度单元只可能是 1 或 2；其它取值返回 -1 让调用方跳过这一条 ——
 * 猜着拼出来的地址比不算更糟。
 */
static long long mem_be_value(const unsigned char *raw, int cells)
{
    if (cells == 1) {
        return (long long) mem_cell32(raw);
    }

    if (cells == 2) {
        return (long long) ((mem_cell32(raw) << 32) | mem_cell32(raw + 4));
    }

    return -1;
}

/** 描述列是否正好是 label（后面最多带个 \r），避免 "System RAM Bus" 之类混进来。 */
static bool mem_label_exact(const char *desc, int remaining, const char *label)
{
    int length = (int) strlen(label);

    if (remaining < length) {
        return false;
    }

    if (strncmp(desc, label, (size_t) length) != 0) {
        return false;
    }

    if (remaining == length) {
        return true;
    }

    return remaining == length + 1 && desc[length] == '\r';
}

/**
 * 从 /proc/iomem 收集某一类区间。
 *
 * @param label    描述列要完全匹配的名字（"System RAM" / "reserved"）
 * @param indented true 取缩进的子区域，false 取顶格的父区域
 * @param out      结果数组
 * @param capacity 结果数组容量
 * @return 段数；-1 = 没权限（有权限时也许能拿到）；-2 = 读失败
 */
static int mem_collect_iomem(const char *label, bool indented,
                             TmMemRange *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return -2;
    }

    // iomem 一台机器几十行，16 KB 足够；静态缓冲，别在栈上开大数组
    static char text[16384];

    if (read_text_file("/proc/iomem", text, (int) sizeof(text)) <= 0) {

        // 打不开时先分清"没权限"和"根本不存在"：前者以后也许能拿到
        return (access("/proc/iomem", R_OK) != 0) ? -1 : -2;
    }

    int count = 0;
    const char *line = text;

    while (*line != '\0' && count < capacity) {

        const char *line_end = strchr(line, '\n');
        int length = (line_end != NULL) ? (int) (line_end - line) : (int) strlen(line);

        const char *cursor = line;
        const char *limit = line + length;

        int indent = 0;

        while (cursor < limit && (*cursor == ' ' || *cursor == '\t')) {
            cursor++;
            indent++;
        }

        // 父段与子段只取一类：子段本来就在父段里，重复加会把总量翻倍
        if ((indent > 0) == indented && cursor < limit) {

            unsigned long long start = 0;
            unsigned long long stop = 0;

            if (sscanf(cursor, "%llx-%llx", &start, &stop) == 2) {

                const char *colon = memchr(cursor, ':', (size_t) (limit - cursor));

                if (colon != NULL && stop >= start) {

                    const char *desc = colon + 1;

                    while (desc < limit && *desc == ' ') {
                        desc++;
                    }

                    if (desc < limit &&
                        mem_label_exact(desc, (int) (limit - desc), label)) {

                        out[count].start = (long long) start;
                        out[count].size = (long long) (stop - start) + 1;
                        count++;
                    }
                }
            }
        }

        line = (line_end != NULL) ? line_end + 1 : limit;
    }

    return count;
}

/** 区间并集的总字节数：重叠的设备树节点只算一次。 */
static long long mem_union_size(const TmMemRange *ranges, int count)
{
    if (ranges == NULL || count <= 0) {
        return 0;
    }

    TmMemRange sorted[TM_MEM_RANGE_MAX];
    int limit = (count > TM_MEM_RANGE_MAX) ? TM_MEM_RANGE_MAX : count;

    for (int i = 0; i < limit; i++) {
        sorted[i] = ranges[i];
    }

    // 段数只有几十个，插入排序最省事
    for (int i = 1; i < limit; i++) {

        TmMemRange value = sorted[i];
        int j = i - 1;

        while (j >= 0 && sorted[j].start > value.start) {
            sorted[j + 1] = sorted[j];
            j--;
        }

        sorted[j + 1] = value;
    }

    long long total = 0;
    long long current_start = sorted[0].start;
    long long current_end = sorted[0].start + sorted[0].size - 1;

    for (int i = 1; i < limit; i++) {

        long long start = sorted[i].start;
        long long end = start + sorted[i].size - 1;

        if (start <= current_end + 1) {

            if (end > current_end) {
                current_end = end;
            }
        } else {
            total += current_end - current_start + 1;
            current_start = start;
            current_end = end;
        }
    }

    total += current_end - current_start + 1;

    return total;
}

/**
 * 把一段区间裁到 other 表里，命中的片段追加进 out。
 *
 * 用来把设备树的预留区裁到 /proc/iomem 真正登记成 System RAM 的范围内：
 * 只落在 MMIO 空洞里的预留区不是内存，不该算进来。
 *
 * @return 追加了几段
 */
static int mem_append_clipped(TmMemRange *out, int count, int capacity,
                              const TmMemRange *source,
                              const TmMemRange *other, int other_count)
{
    if (out == NULL || source == NULL || other == NULL) {
        return 0;
    }

    int added = 0;

    long long source_start = source->start;
    long long source_end = source_start + source->size - 1;

    for (int i = 0; i < other_count && count + added < capacity; i++) {

        long long other_end = other[i].start + other[i].size - 1;

        long long start = (source_start > other[i].start) ? source_start : other[i].start;
        long long end = (source_end < other_end) ? source_end : other_end;

        if (start <= end) {
            out[count + added].start = start;
            out[count + added].size = end - start + 1;
            added++;
        }
    }

    return added;
}

/** 读设备树的单元数属性（#address-cells / #size-cells）：固定 4 字节大端。 */
static int mem_dt_cell_count(const char *path, int fallback)
{
    unsigned char raw[4];

    int length = read_text_file(path, (char *) raw, (int) sizeof(raw));

    if (length != 4) {
        return fallback;
    }

    return (int) mem_cell32(raw);
}

/**
 * 读一个节点的 reg 属性，拆成若干 (起始地址, 长度)。
 *
 * reg 的布局是 (地址单元 × address-cells, 长度单元 × size-cells) 重复出现，
 * 每个单元 4 字节大端。单元数不是 1 或 2、总长度凑不成整数条，都算解析失败。
 *
 * @return 条目数；0 = 没有 reg 或格式不认识
 */
static int mem_dt_reg(const char *path, int address_cells, int size_cells,
                      TmMemRange *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    unsigned char raw[256];

    int length = read_text_file(path, (char *) raw, (int) sizeof(raw));
    int entry_bytes = (address_cells + size_cells) * 4;

    if (length <= 0 || entry_bytes <= 0 || (length % entry_bytes) != 0) {
        return 0;
    }

    int count = 0;

    for (int offset = 0; offset < length && count < capacity; offset += entry_bytes) {

        const unsigned char *entry = raw + offset;

        long long start = mem_be_value(entry, address_cells);
        long long size = mem_be_value(entry + address_cells * 4, size_cells);

        if (start < 0 || size <= 0) {
            continue;
        }

        out[count].start = start;
        out[count].size = size;
        count++;
    }

    return count;
}

/**
 * 设备树 reserved-memory 里"不可复用"的预留区，与 System RAM 求交后的并集大小。
 *
 * 只算 no-map / 普通 carveout：带 reusable 属性的（CMA、qdss 之类）允许内核回收，
 * 算进去就不是硬件保留了。动态 size 的节点没有固定 reg，直接跳过。
 *
 * @param ram        /proc/iomem 的 System RAM 区间
 * @param ram_count  区间数
 * @param out_bytes  结果（字节）
 * @return 0 = 成功；-1 = 没权限；-2 = 没有这个目录 / 解析不了
 */
static int mem_dt_reserved_bytes(const TmMemRange *ram, int ram_count,
                                 long long *out_bytes)
{
    if (ram == NULL || ram_count <= 0 || out_bytes == NULL) {
        return -2;
    }

    const char *base = "/sys/firmware/devicetree/base/reserved-memory";

    DIR *dir = opendir(base);

    if (dir == NULL) {
        return (errno == EACCES) ? -1 : -2;
    }

    int address_cells = mem_dt_cell_count(
        "/sys/firmware/devicetree/base/reserved-memory/#address-cells", 2);

    int size_cells = mem_dt_cell_count(
        "/sys/firmware/devicetree/base/reserved-memory/#size-cells", 2);

    if (address_cells <= 0 || address_cells > 2 || size_cells <= 0 || size_cells > 2) {
        closedir(dir);
        return -2;
    }

    TmMemRange hits[TM_MEM_RANGE_MAX];
    int hit_count = 0;

    struct dirent *entry = NULL;

    while ((entry = readdir(dir)) != NULL && hit_count < TM_MEM_RANGE_MAX) {

        if (entry->d_name[0] == '.' || entry->d_name[0] == '#') {
            continue;
        }

        char path[PATH_MAX];

        snprintf(path, sizeof(path), "%s/%s", base, entry->d_name);

        struct stat info;

        if (stat(path, &info) != 0 || !S_ISDIR(info.st_mode)) {
            continue;
        }

        // reusable：内核能在约束满足时回收，不算永久保留
        char flag_path[PATH_MAX];

        snprintf(flag_path, sizeof(flag_path), "%s/reusable", path);

        if (access(flag_path, F_OK) == 0) {
            continue;
        }

        snprintf(flag_path, sizeof(flag_path), "%s/reg", path);

        TmMemRange regions[8];

        int region_count = mem_dt_reg(flag_path, address_cells, size_cells,
                                      regions,
                                      (int) (sizeof(regions) / sizeof(regions[0])));

        for (int i = 0; i < region_count && hit_count < TM_MEM_RANGE_MAX; i++) {

            hit_count += mem_append_clipped(hits, hit_count, TM_MEM_RANGE_MAX,
                                            &regions[i], ram, ram_count);
        }
    }

    closedir(dir);

    *out_bytes = mem_union_size(hits, hit_count);

    return 0;
}

/**
 * 填内存设备。一台机器一份 /proc/meminfo，所以只发第一台实例。
 *
 * 所有容量一律字节（kB 在 meminfo_bytes 里就乘掉了），换算成 GB / MB 是显示层的事。
 * 每一项单独发状态：读不到就发感叹号标记，上层据此显示破折号，
 * 绝不拿 0 顶替（0 是有效值，会和"没读到"混在一起）。
 *
 * 分条图四段（使用中 / 已修改 / 备用 / 可用）在这层的口径：
 *   使用中 = MemTotal - MemAvailable（和统计区同一口径）
 *   已修改 = Dirty + Writeback（Linux 没有和 Windows 一致的 Modified 列表，这是近似）
 *   备用   = Inactive(file)（较少活跃的文件页，和 Windows 的 Standby 不是一一对应）
 *   可用   = MemFree（真正的空闲页）
 * 这四项在 Linux 里存在包含关系，比例由上层归一化后再画。
 *
 * 名称行要的是两个不同的量：
 *   标称容量 = dt 的 ddr_size（这台机器卖的是多少 GB）
 *   实际可用 = MemTotal（内核手上还剩多少，图表顶端就是它）
 * 两者差的就是保留区，不能互相顶替。
 */
static int build_memory_devices(TmOutDevice *out, int capacity,
                                const TmDeviceRef *devices, int count)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    device_begin(&out[0], devices[0].id, TM_DEV_TYPE_MEMORY);

    device_put(&out[0], "name", devices[0].name);

    // meminfo 只有一两 KB，但缓冲别放栈上：这条通道是单线程，static 够用
    static char meminfo[4096];

    if (read_text_file("/proc/meminfo", meminfo, (int) sizeof(meminfo)) <= 0) {

        // 连 meminfo 都打不开：只报名称，统计项一个都不发
        return 1;
    }

    long long total = meminfo_bytes(meminfo, "MemTotal");
    long long available = meminfo_bytes(meminfo, "MemAvailable");
    long long free_memory = meminfo_bytes(meminfo, "MemFree");
    long long cached = meminfo_bytes(meminfo, "Cached");
    long long sreclaimable = meminfo_bytes(meminfo, "SReclaimable");
    long long sunreclaim = meminfo_bytes(meminfo, "SUnreclaim");
    long long committed = meminfo_bytes(meminfo, "Committed_AS");
    long long commit_limit = meminfo_bytes(meminfo, "CommitLimit");
    long long dirty = meminfo_bytes(meminfo, "Dirty");
    long long writeback = meminfo_bytes(meminfo, "Writeback");
    long long inactive_file = meminfo_bytes(meminfo, "Inactive(file)");

    // 使用中：总量减可用。不用 MemFree —— 那是"彻底空闲"，会把可回收缓存算成已用
    long long used = -1;

    if (total >= 0 && available >= 0) {

        used = total - available;

        if (used < 0) {
            used = -1;
        }
    }

    long long modified = -1;

    if (dirty >= 0 || writeback >= 0) {
        modified = (dirty >= 0 ? dirty : 0) + (writeback >= 0 ? writeback : 0);
    }

    device_put_int(&out[0], "total_bytes", total, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "available_bytes", available, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "used_bytes", used, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "cached_bytes", cached, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "committed_bytes", committed, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "commit_limit_bytes", commit_limit, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "reclaimable_kernel_bytes", sreclaimable,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "nonreclaimable_kernel_bytes", sunreclaim,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    // 压缩内存在 zram 里，meminfo 的 SwapTotal 只是它的上限，不能用。
    // 下面每个数都来自 mm_stat 的实测列，没有任何一项是折算出来的。
    TmZramInfo zram;

    read_zram_info(&zram);

    const char *zram_status = TM_FIELD_STATUS_NOT_AVAILABLE;

    if (zram.state == TM_ZRAM_UNREADABLE || zram.state == TM_ZRAM_UNKNOWN) {
        // 设备在但读不到 / 连目录都列不了：都是"也许能拿到"，不是"没有"
        zram_status = TM_FIELD_STATUS_ACCESS_DENIED;
    }

    long long zram_used = -1;
    long long zram_original = -1;
    long long zram_compressed = -1;

    if (zram.state == TM_ZRAM_OK) {

        zram_used = zram.used_bytes;
        zram_original = zram.original_bytes;
        zram_compressed = zram.compressed_bytes;

    } else if (zram.state == TM_ZRAM_NONE) {

        // 确认没有 zram：0 是有效结论，不是"读不到"
        zram_used = 0;
        zram_original = 0;
        zram_compressed = 0;
    }

    device_put_int(&out[0], "compressed_bytes", zram_used, zram_status);

    device_put_int(&out[0], "zram_original_bytes", zram_original, zram_status);

    device_put_int(&out[0], "zram_compressed_bytes", zram_compressed, zram_status);

    device_put_int(&out[0], "zram_used_bytes", zram_used, zram_status);

    // 设备容量：disksize 是上限，和用量分开报，谁也不顶替谁
    device_put_int(&out[0], "zram_disksize_bytes",
                   zram.disksize_known ? zram.disksize_bytes : -1,
                   zram.disksize_known ? TM_FIELD_STATUS_NOT_AVAILABLE
                                       : (zram.state == TM_ZRAM_NONE
                                          ? TM_FIELD_STATUS_UNSUPPORTED : zram_status));

    // 交换空间：/proc/swaps 的 Size（设备配置容量）与 Used（已用量），
    // 所有 swap 设备一起加，不只认 zram。Used 是压缩前口径，不能当内存占用；
    // 无设备行时两个都是 0（没配交换，有效结论），读不到才报不可用。
    long long swap_total = -1;
    long long swap_used = -1;

    if (read_swap_table_bytes(&swap_total, &swap_used) != 0) {
        swap_total = -1;
        swap_used = -1;
    }

    device_put_int(&out[0], "swap_total_bytes", swap_total, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "swap_used_bytes", swap_used, TM_FIELD_STATUS_NOT_AVAILABLE);

    // zram 的交换用量单独一格（压缩前口径），与上面的全表交换量分开报
    device_put_int(&out[0], "zram_swap_used_bytes", read_zram_swap_used_bytes(),
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    // 分条图另外三段
    device_put_int(&out[0], "modified_bytes", modified, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "standby_bytes", inactive_file, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "free_bytes", free_memory, TM_FIELD_STATUS_NOT_AVAILABLE);

    // 标称容量与规格都来自高通 Device Tree，读不到就整项不发：
    // 名称行那边会用 MemTotal 兜容量、少写规格，不会空着
    long long nominal = read_dram_size_bytes();

    device_put_int(&out[0], "nominal_bytes", nominal, TM_FIELD_STATUS_NOT_AVAILABLE);

    // 为硬件保留的内存：标称容量与 MemTotal 的差额 —— 内核没有接管的那一截。
    //
    //   标称容量从 dt 来，MemTotal 是内核统计的总页数，两者之差包含固件 /
    //   Hypervisor / 各子系统 carveout / 内核镜像自身与页表。
    //   和 Windows 口径不完全等（那边把内核自身算"在用"），但这是 Android 上
    //   能拿到的最接近的差异量，比拿 0 或者拿 CMA 总量冒充强得多。
    //
    // 数据互相矛盾（标称不超过 MemTotal）时不硬算，报"没有基准"，让上层显示不支持。
    long long hardware_reserved = -1;
    const char *hardware_reserved_status = TM_FIELD_STATUS_NOT_AVAILABLE;

    if (nominal <= 0) {

        // 非高通设备没有 ddr_size：物理容量根本不知道，属于"不支持"
        hardware_reserved_status = TM_FIELD_STATUS_UNSUPPORTED;

    } else if (total <= 0) {

        hardware_reserved_status = TM_FIELD_STATUS_READ_FAILED;

    } else if (nominal <= total) {

        hardware_reserved_status = TM_FIELD_STATUS_NO_BASELINE;

    } else {

        hardware_reserved = nominal - total;
    }

    device_put_int(&out[0], "reserved_bytes", hardware_reserved,
                   hardware_reserved_status);

    // 交叉验证（只上调试页，页面不读这两项）：
    //   system_ram_bytes —— /proc/iomem 里顶格 "System RAM" 的地址总量（并集）
    //   reserved_dt_bytes —— 设备树不可复用预留区与上面这段求交之后的并集
    // 差额口径与 carveout 口径本来就不相等，两份数摆在一起才好核对。
    TmMemRange ram_ranges[TM_MEM_RANGE_MAX];

    int ram_count = mem_collect_iomem("System RAM", false, ram_ranges, TM_MEM_RANGE_MAX);

    long long system_ram = (ram_count > 0) ? mem_union_size(ram_ranges, ram_count) : -1;

    device_put_int(&out[0], "system_ram_bytes", system_ram, TM_FIELD_STATUS_NOT_AVAILABLE);

    long long dt_reserved = -1;
    const char *dt_reserved_status = TM_FIELD_STATUS_UNSUPPORTED;

    if (ram_count > 0) {

        dt_reserved_status = TM_FIELD_STATUS_NOT_AVAILABLE;

        if (mem_dt_reserved_bytes(ram_ranges, ram_count, &dt_reserved) == -1) {
            dt_reserved = -1;
            dt_reserved_status = TM_FIELD_STATUS_ACCESS_DENIED;
        }
    }

    device_put_int(&out[0], "reserved_dt_bytes", dt_reserved, dt_reserved_status);

    char spec[32];

    if (read_dram_spec(spec, (int) sizeof(spec))) {
        device_put(&out[0], "spec", spec);
    }

    // 颗粒厂商：dt 的 ddr_manufacturer_id 换成中文名；读不到或编码不在表里就整项不发，
    // 页面那边"厂商"这一行跟着消失（不留"未知"占位）
    char manufacturer[64];

    if (read_dram_manufacturer(manufacturer, (int) sizeof(manufacturer))) {
        device_put(&out[0], "manufacturer", manufacturer);
    }

    return 1;
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

// ============================================================
// 显卡设备：按厂商取字段
//
// 枚举（enumerate_gpu_devices）定下"这台机器有哪几张卡、各是哪一家"，
// 本节负责把每一格填上。结构是两层：
//
//   填写函数（gpu_field_usage 这类）：只知道"要填哪一格"，不关心厂商；
//   厂商读取函数（gpu_qualcomm_usage 这类）：知道具体文件在哪，读回来交给填写函数。
//
// 顺序一律是"厂商专用接口 -> 通用 DRM 接口 -> 发状态"：
//   高通      gpu_busy_percentage / gpubusy / temp / gpu_model
//   联发科    /sys/module/ged/parameters/gpu_loading、/sys/kernel/ged/hal/gpu_utilization
//   Mali 系   devfreq 的 load（kbase / panfrost / panthor 都导出这一格）
//   通用 DRM  device/gpu_busy_percent、device/mem_info_vram_*、device/mem_info_gtt_*
//   温度      先 device/hwmon/hwmonN/temp1_input，再退回 type 里带 gpu 的热区
//
// 读不到就发 `!状态`（客户端折成 null，页面写"不支持 / -"），不拿 0 顶替。
//
// 图形 API（页面上"DirectX 版本"那一格换成它）：
//   1) 先问 libvulkan 的 vkEnumerateInstanceVersion —— 这是真实版本号；
//   2) 没有再读 ro.opengles.version（十进制 0xMMmm，例如 196610 = 3.2）；
//   3) 两样都查过、都没有，才发 gfx_api=NONE（明确不支持）；
//   4) 连查都查不了（dlopen 与 getprop 都失败）就不发这两格，页面写"-"。
//
// 读不到的格子一律不发：页面按"不支持 / -"渲染，不拿 0 顶替。
// ============================================================

/** 读文件里的第一个整数（允许前导空白与后缀单位，如 "12 %"）。 */
static bool gpu_read_first_int(const char *path, long long *value)
{
    char text[64];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    const char *cursor = text;

    while (*cursor != '\0' && !isdigit((unsigned char) *cursor) && *cursor != '-') {
        cursor++;
    }

    if (*cursor == '\0') {
        return false;
    }

    char digits[32];

    int count = 0;

    if (*cursor == '-') {
        digits[count++] = *cursor++;
    }

    while (isdigit((unsigned char) *cursor) && count < 31) {
        digits[count++] = *cursor++;
    }

    digits[count] = '\0';

    if (value != NULL) {
        *value = strtoll(digits, NULL, 10);
    }

    return true;
}

/** 读一个百分比（夹到 0..100）。读不到返回 false。 */
static bool gpu_read_percent(const char *path, long long *percent)
{
    long long value = 0;

    if (!gpu_read_first_int(path, &value)) {
        return false;
    }

    if (value < 0) value = 0;

    if (value > 100) value = 100;

    if (percent != NULL) {
        *percent = value;
    }

    return true;
}

/** 文本里有没有 gpu 三个字母（忽略大小写）：判断某个热区是不是 GPU 的。 */
static bool gpu_text_has_gpu(const char *text)
{
    char lower[64];

    int count = 0;

    for (; text[count] != '\0' && count < 63; count++) {
        lower[count] = (char) tolower((unsigned char) text[count]);
    }

    lower[count] = '\0';

    return strstr(lower, "gpu") != NULL;
}

/** 检查一个热区：type 带 gpu、且 temp 读得出来，才算 GPU 温度。 */
static bool gpu_thermal_zone_check(const char *zone, long long *celsius)
{
    char path[256];

    snprintf(path, sizeof(path), "/sys/class/thermal/%s/type", zone);

    char type_text[64];

    if (read_text_file(path, type_text, (int) sizeof(type_text)) <= 0) {
        return false;
    }

    if (!gpu_text_has_gpu(type_text)) {
        return false;
    }

    snprintf(path, sizeof(path), "/sys/class/thermal/%s/temp", zone);

    long long milli = 0;

    if (!gpu_read_first_int(path, &milli)) {
        return false;
    }

    *celsius = milli / 1000;

    return true;
}

/**
 * 兜底温度：/sys/class/thermal 里 type 带 gpu 的第一个热区。
 *
 * 两趟走，理由与 GPU 发现一样：热区编号由内核按注册顺序定，不枚举不知道有几条；
 * 但枚举要"读目录"权限，被 SELinux 挡住时，thermal_zone0..15 的固定编号仍能
 * 一个个直接读 type —— 读得到就认得出来，不因为列不了目录就把温度判成没有。
 */
static bool gpu_thermal_zone_temperature(long long *celsius)
{
    DIR *dir = opendir("/sys/class/thermal");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "thermal_zone", 12) != 0) {
                continue;
            }

            if (gpu_thermal_zone_check(entry->d_name, celsius)) {

                closedir(dir);

                return true;
            }
        }

        closedir(dir);
    }

    for (int index = 0; index < 16; index++) {

        char zone[32];

        snprintf(zone, sizeof(zone), "thermal_zone%d", index);

        if (gpu_thermal_zone_check(zone, celsius)) {
            return true;
        }
    }

    return false;
}

/**
 * 物理位置：DRM 节点若有 PCI_SLOT_NAME（domain:bus:device.function），
 * 写成 "PCI 总线 2、设备 0、功能 0"；SoC 核显不是 PCI 设备，这一格就不发。
 */
static void gpu_pci_location(const char *device_path, char *out, int capacity)
{
    out[0] = '\0';

    char path[256];

    snprintf(path, sizeof(path), "%s/device/uevent", device_path);

    char text[512];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return;
    }

    const char *cursor = strstr(text, "PCI_SLOT_NAME=");

    if (cursor == NULL) {
        return;
    }

    cursor += strlen("PCI_SLOT_NAME=");

    unsigned int domain = 0;

    unsigned int bus = 0;

    unsigned int device = 0;

    unsigned int function = 0;

    if (sscanf(cursor, "%x:%x:%x.%x", &domain, &bus, &device, &function) != 4) {
        return;
    }

    snprintf(out, (size_t) capacity, "PCI 总线 %u、设备 %u、功能 %u", bus, device, function);
}

/** 显卡温度：先找 device 下的 hwmon，没有再退回 thermal zone。 */
static bool gpu_read_temperature(const char *device_path, long long *celsius)
{
    for (int index = 0; index < 4; index++) {

        char path[256];

        snprintf(path, sizeof(path), "%s/device/hwmon/hwmon%d/temp1_input", device_path, index);

        long long milli = 0;

        if (gpu_read_first_int(path, &milli)) {

            *celsius = milli / 1000;

            return true;
        }
    }

    return gpu_thermal_zone_temperature(celsius);
}

/** vkEnumerateInstanceVersion 的签名：传入 32 位编码版本号。 */
typedef unsigned int (*gpu_vulkan_version_fn)(unsigned int *);

/**
 * 探测图形 API：Vulkan 与 OpenGL ES **各查各的**，谁有报谁。
 *
 * 以前是二选一（查到 Vulkan 就不再看 OpenGL），但两者并不互斥：手机上基本同时支持
 * （ro.opengles.version 一直都在），页面就该两行都摆出来。所以这里把结果拆成两份，
 * 由调用方各自决定摆哪一行。
 *
 * @param has_result     是否真的查过（false = 一条都没查成，页面写"-"）
 * @param has_vulkan     Vulkan 可用
 * @param vulkan_version 形如 1.1
 * @param has_opengl     OpenGL ES 可用
 * @param opengl_version 形如 3.2
 *
 * 结果在进程内只算一次：这个值跟硬件走，不会中途变化。
 */
static void gpu_detect_gfx_api(bool *has_result,
                               bool *has_vulkan, char *vulkan_version, int vulkan_size,
                               bool *has_opengl, char *opengl_version, int opengl_size)
{
    static bool cached = false;

    static bool cached_has_result = false;

    static bool cached_has_vulkan = false;

    static char cached_vulkan[24] = {0};

    static bool cached_has_opengl = false;

    static char cached_opengl[24] = {0};

    if (!cached) {

        cached = true;

        bool searched = false;

        // 1) Vulkan：loader 给出 32 位版本号（major 在 bit22 起，minor 在 bit12 起）
        void *library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);

        if (library != NULL) {

            searched = true;

            gpu_vulkan_version_fn enumerate =
                (gpu_vulkan_version_fn) dlsym(library, "vkEnumerateInstanceVersion");

            unsigned int encoded = (1u << 22);   // 老 loader 没这个函数：按 Vulkan 1.0 算

            if (enumerate != NULL) {

                encoded = 0;

                if (enumerate(&encoded) != 0) {
                    encoded = 0;
                }
            }

            if (encoded != 0) {

                cached_has_vulkan = true;

                snprintf(cached_vulkan, sizeof(cached_vulkan), "%u.%u",
                         encoded >> 22, (encoded >> 12) & 0x3ffu);
            }

            dlclose(library);
        }

        // 2) OpenGL ES：ro.opengles.version 是十进制 0xMMmm
        FILE *pipe = popen("getprop ro.opengles.version 2>/dev/null", "r");

        if (pipe != NULL) {

            searched = true;

            char text[32] = {0};

            if (fgets(text, (int) sizeof(text), pipe) == NULL) {
                text[0] = '\0';
            }

            pclose(pipe);

            if (text[0] != '\0') {

                int encoded = atoi(text);

                if (encoded > 0) {

                    cached_has_opengl = true;

                    snprintf(cached_opengl, sizeof(cached_opengl), "%d.%d",
                             encoded >> 16, encoded & 0xffff);
                }
            }
        }

        if (searched) {
            cached_has_result = true;
        }
    }

    *has_result = cached_has_result;
    *has_vulkan = cached_has_vulkan;
    *has_opengl = cached_has_opengl;

    snprintf(vulkan_version, (size_t) vulkan_size, "%s", cached_vulkan);

    snprintf(opengl_version, (size_t) opengl_size, "%s", cached_opengl);
}

/** 一格字段的读取结果：要么有数值，要么是一个状态（二者互斥）。 */
typedef struct {
    bool has;
    long long number;
    const char *status;
} GpuField;

static GpuField gpu_field_value(long long number)
{
    GpuField field;

    memset(&field, 0, sizeof(field));

    field.has = true;
    field.number = number;

    return field;
}

static GpuField gpu_field_missing(const char *status)
{
    GpuField field;

    memset(&field, 0, sizeof(field));

    field.status = status;

    return field;
}

/**
 * 节点"在不在"：stat 成功说明路径存在（只要求父目录的 search 权限，不读文件内容）。
 *
 * 用途是区分两种"什么都没读到"：
 *   - 路径存在、值读不出来  -> 权限问题，报 ACCESS_DENIED（界面显示 "-"）
 *   - 路径根本不存在        -> 这个平台没导出这一格，报 NOT_AVAILABLE（"不支持"）
 * 绝不能在"目录枚举失败"这一步就把整块判死。
 */
static bool gpu_node_exists(const char *path)
{
    struct stat info;

    return stat(path, &info) == 0;
}

/** 候选路径里只要有一个存在却读不出来，就按权限问题报。 */
static GpuField gpu_missing_from_paths(const char *const *paths, size_t path_count)
{
    for (size_t i = 0; i < path_count; i++) {

        if (paths[i] != NULL && gpu_node_exists(paths[i])) {
            return gpu_field_missing(TM_FIELD_STATUS_ACCESS_DENIED);
        }
    }

    return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
}

/** 一次性读两个整数（gpubusy 那种"忙碌 总计"的格式）。 */
static bool gpu_read_two_ints(const char *path, long long *first, long long *second)
{
    char text[96];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    long long a = 0;

    long long b = 0;

    if (sscanf(text, "%lld %lld", &a, &b) != 2) {
        return false;
    }

    *first = a;
    *second = b;

    return true;
}

/** 取某个 devfreq 设备的 load（"22@300000000Hz" 里的 22，夹到 0..100）。 */
static bool gpu_devfreq_read_load(const char *device, long long *percent)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/sys/class/devfreq/%s/load", device);

    long long value = 0;

    if (!gpu_read_first_int(path, &value)) {
        return false;
    }

    if (value < 0) value = 0;

    if (value > 100) value = 100;

    *percent = value;

    return true;
}

/**
 * devfreq 的 load 一格：Mali / panfrost / panthor 都走这个。
 *
 * 格式是 "22@300000000Hz"（占用百分比 @ 当前频率），也见过只写百分比的。
 * 只取 @ 前面那个数，夹到 0..100。
 *
 * 枚举之后还有固定兜底：devfreq 设备名是厂商起的（gpu / mali / mtk_gpu …），
 * 穷举不了，但常见的那几个先直接试一遍 —— 目录枚举被挡时，名字对了就能读到。
 */
static bool gpu_devfreq_load(long long *percent)
{
    DIR *dir = opendir("/sys/class/devfreq");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            bool looks_gpu = strstr(entry->d_name, "gpu") != NULL
                             || strstr(entry->d_name, "mali") != NULL
                             || strstr(entry->d_name, "pan") != NULL;

            if (!looks_gpu) {
                continue;
            }

            if (gpu_devfreq_read_load(entry->d_name, percent)) {

                closedir(dir);

                return true;
            }
        }

        closedir(dir);
    }

    static const char *const fixed_devices[] = {
        "gpu",
        "mali",
        "kgsl-3d0",
        "mtk_gpu",
        "soc:gpu",
        "13000000.mali",
        "5000000.mali"
    };

    for (size_t i = 0; i < sizeof(fixed_devices) / sizeof(fixed_devices[0]); i++) {

        if (gpu_devfreq_read_load(fixed_devices[i], percent)) {
            return true;
        }
    }

    return false;
}

/**
 * 高通：GPU 利用率。
 *
 *   gpu_busy_percentage  直接给百分比（"37 %"），最省事，优先用；
 *   gpubusy              给两个计数（忙碌 / 总计），自己算比例；
 *   device/gpu_busy_percent  个别机型只留了 DRM 这一格。
 *
 * 一份数据三个来源，取第一个能读到的，绝不相加。
 */
static GpuField gpu_qualcomm_usage(const char *base)
{
    char path[TM_PATH_MAX];

    long long percent = 0;

    snprintf(path, sizeof(path), "%s/gpu_busy_percentage", base);

    if (gpu_read_percent(path, &percent)) {
        return gpu_field_value(percent);
    }

    long long busy = 0;

    long long total = 0;

    snprintf(path, sizeof(path), "%s/gpubusy", base);

    if (gpu_read_two_ints(path, &busy, &total) && total > 0 && busy >= 0) {

        long long ratio = busy * 100 / total;

        if (ratio < 0) ratio = 0;

        if (ratio > 100) ratio = 100;

        return gpu_field_value(ratio);
    }

    snprintf(path, sizeof(path), "%s/device/gpu_busy_percent", base);

    if (gpu_read_percent(path, &percent)) {
        return gpu_field_value(percent);
    }

    // 三个候选全没读出来时再分一次状态：节点在 = 权限不够（"-"），
    // 节点根本不存在 = 这平台没导出利用率（"不支持"）。
    char busy_path[TM_PATH_MAX];
    char ratio_path[TM_PATH_MAX];
    char drm_path[TM_PATH_MAX];

    snprintf(busy_path, sizeof(busy_path), "%s/gpu_busy_percentage", base);
    snprintf(ratio_path, sizeof(ratio_path), "%s/gpubusy", base);
    snprintf(drm_path, sizeof(drm_path), "%s/device/gpu_busy_percent", base);

    const char *const usage_candidates[] = { busy_path, ratio_path, drm_path };

    return gpu_missing_from_paths(usage_candidates,
                                  sizeof(usage_candidates) / sizeof(usage_candidates[0]));
}

/** 联发科：GED 框架与它的 hal 节点。两处都试，先读到的算。 */
static GpuField gpu_mediatek_usage(void)
{
    static const char *const paths[] = {
        "/sys/module/ged/parameters/gpu_loading",
        "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/kernel/ged/hal/loading_percentage"
    };

    long long percent = 0;

    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {

        if (gpu_read_percent(paths[i], &percent)) {
            return gpu_field_value(percent);
        }
    }

    return gpu_missing_from_paths(paths, sizeof(paths) / sizeof(paths[0]));
}

/** 通用 DRM（amdgpu / i915 / panfrost 等）：驱动自己导出的占用文件。 */
static GpuField gpu_drm_usage(const char *base)
{
    char path[TM_PATH_MAX];

    long long percent = 0;

    snprintf(path, sizeof(path), "%s/device/gpu_busy_percent", base);

    if (gpu_read_percent(path, &percent)) {
        return gpu_field_value(percent);
    }

    snprintf(path, sizeof(path), "%s/gpu_busy_percent", base);

    if (gpu_read_percent(path, &percent)) {
        return gpu_field_value(percent);
    }

    return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
}

/**
 * 填写函数：GPU 利用率。
 *
 * 顺序：本厂商的专用接口 -> devfreq（Mali 系）-> 通用 DRM -> 状态。
 * 前两层对高通/联发科不适用它们自己的路径已经覆盖，这里只是兜底。
 */
static GpuField gpu_field_usage(const TmDeviceRef *device)
{
    GpuField field;

    switch (device->vendor) {

        case TM_GPU_VENDOR_QUALCOMM:

            field = gpu_qualcomm_usage(device->path);

            if (field.has) {
                return field;
            }

            break;

        case TM_GPU_VENDOR_MEDIATEK:

            field = gpu_mediatek_usage();

            if (field.has) {
                return field;
            }

            break;

        case TM_GPU_VENDOR_SAMSUNG:
        case TM_GPU_VENDOR_GOOGLE:
        case TM_GPU_VENDOR_ARM:
        case TM_GPU_VENDOR_IMAGINATION:
        default:

            break;
    }

    long long percent = 0;

    if (gpu_devfreq_load(&percent)) {
        return gpu_field_value(percent);
    }

    return gpu_drm_usage(device->path);
}

/**
 * 高通温度：kgsl 的 temp。
 *
 * 单位在不同内核版本上不一样（有的直接摄氏度，有的毫摄氏度），按量级猜：
 * ≥200 当毫摄氏度。这是**猜测**，不是读到的元数据；猜错最多差一千倍，
 * 但 200 度以上的手机 GPU 不存在，所以这条界线足够安全。
 */
static GpuField gpu_qualcomm_temperature(const char *base)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/temp", base);

    long long raw = 0;

    if (gpu_read_first_int(path, &raw)) {

        long long celsius = (raw >= 200) ? (raw / 1000) : raw;

        return gpu_field_value(celsius);
    }

    // temp 节点在、值读不出来：权限问题（"-"）；节点不在：这版内核没导出温度
    const char *const temperature_candidates[] = { path };

    return gpu_missing_from_paths(temperature_candidates,
                                  sizeof(temperature_candidates) / sizeof(temperature_candidates[0]));
}

/** 填写函数：GPU 温度（厂商专用 -> hwmon -> 热区 -> 状态）。 */
static GpuField gpu_field_temperature(const TmDeviceRef *device)
{
    if (device->vendor == TM_GPU_VENDOR_QUALCOMM) {

        GpuField field = gpu_qualcomm_temperature(device->path);

        if (field.has) {
            return field;
        }
    }

    long long celsius = 0;

    if (gpu_read_temperature(device->path, &celsius)) {
        return gpu_field_value(celsius);
    }

    return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
}

/** 填写函数：专用显存总量 / 已用（amdgpu 这类有真 VRAM 的卡才有）。 */
static GpuField gpu_field_dedicated(const TmDeviceRef *device, bool used)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/device/mem_info_vram_%s", device->path,
             used ? "used" : "total");

    long long bytes = 0;

    if (gpu_read_first_int(path, &bytes) && bytes >= 0) {
        return gpu_field_value(bytes);
    }

    // 手机上的集成 GPU（Adreno / Mali）没有独立显存，这一格就该空着，
    // 不能拿系统内存或 KGSL 的 vmalloc 冒充
    return gpu_field_missing(TM_FIELD_STATUS_UNSUPPORTED);
}

/** 这个 dma-buf 是不是 GPU 自己申请的：exporter 名里带 mali / ged / mediatek / mtk。 */
static bool gpu_dmabuf_is_gpu_exporter(const char *name)
{
    return gpu_text_contains(name, "mali")
        || gpu_text_contains(name, "ged")
        || gpu_text_contains(name, "mediatek")
        || gpu_text_contains(name, "mtk");
}

/**
 * 通用兜底：汇总 dma-buf 里 GPU 申请的那部分内存。
 *
 * 内核 4.19 起 /sys/kernel/dmabuf/buffers/<inode>/ 每项带 exp_name 与 size（字节），
 * 这是全平台统一的统计口。高通有 KGSL mapped 就先走那边，Mali 设备（联发科 /
 * 三星 / Google）只能靠这一格。只累加 GPU exporter 的条目 —— 相机、显示那些
 * dma-buf 不算进来，所以这个数和"GPU 映射住的内存"同义。
 *
 * 扫目录要读上百个小文件，所以按 1 秒缓存：页面 1Hz 刷新，每秒算一遍足够。
 *
 * @return true = 确实扫到了 GPU exporter 的条目（值可能是 0）；false = 没有这个接口
 */
static bool gpu_dmabuf_gpu_bytes(long long *bytes)
{
    static bool cached = false;

    static bool cached_ok = false;

    static long long cached_bytes = 0;

    static time_t cached_at = 0;

    time_t now = time(NULL);

    if (cached && now == cached_at) {

        *bytes = cached_bytes;

        return cached_ok;
    }

    cached = true;

    cached_at = now;

    cached_ok = false;

    cached_bytes = 0;

    const char *roots[] = {
        "/sys/kernel/dmabuf/buffers",
        "/sys/kernel/dma_buf/buffers",
    };

    for (int root_index = 0; root_index < 2 && !cached_ok; root_index++) {

        DIR *dir = opendir(roots[root_index]);

        if (dir == NULL) {
            continue;
        }

        bool matched = false;

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            // 只认 inode 号这类纯数字目录
            if (!is_pure_digits(entry->d_name)) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "%s/%s/exp_name", roots[root_index], entry->d_name);

            char exporter[64];

            if (read_text_file(path, exporter, (int) sizeof(exporter)) <= 0) {
                continue;
            }

            trim_disk_text(exporter);

            if (!gpu_dmabuf_is_gpu_exporter(exporter)) {
                continue;
            }

            snprintf(path, sizeof(path), "%s/%s/size", roots[root_index], entry->d_name);

            long long size = 0;

            if (gpu_read_first_int(path, &size) && size > 0) {

                cached_bytes += size;

                matched = true;
            }
        }

        closedir(dir);

        cached_ok = matched;
    }

    *bytes = cached_bytes;

    return cached_ok;
}

/**
 * 填写函数：共享显存（DRM 的 gtt -> 高通 KGSL 的 mapped -> dma-buf 汇总 -> 不支持）。
 *
 * 桌面卡走 DRM：<node>/device/mem_info_gtt_total / _used。
 *
 * 手机的 GPU 用系统内存，没有独立显存，能拿到的是"GPU 映射住的内存量"：
 *   高通 —— /sys/class/kgsl/kgsl/mapped，一个字，语义就是 GPU Mapped Memory；
 *   其它（Mali：联发科 / 三星 / Google）—— 没有等价的一格，退到内核统一的
 *   dma-buf 统计，只累加 GPU exporter 申请的缓冲（见 gpu_dmabuf_gpu_bytes）。
 *
 * 总量厂商都不给，按"GPU 能映射的内存池"取 /proc/meminfo 的 MemTotal ——
 * Android 上整块内存都能给 GPU 映射，没有更窄的上限，不拿一半硬套。
 *
 * 两样都读不到就一律"不支持"，不替没有这一格的平台编一个总量出来。
 */
static GpuField gpu_field_shared(const TmDeviceRef *device, bool used)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/device/mem_info_gtt_%s", device->path,
             used ? "used" : "total");

    long long bytes = 0;

    if (gpu_read_first_int(path, &bytes) && bytes >= 0) {
        return gpu_field_value(bytes);
    }

    long long mapped = 0;

    bool has_mapped = gpu_read_first_int("/sys/class/kgsl/kgsl/mapped", &mapped) && mapped >= 0;

    // 高通之外（Mali / 联发科 / 三星 / Google）没有 kgsl：退到通用 dma-buf 汇总
    if (!has_mapped) {
        has_mapped = gpu_dmabuf_gpu_bytes(&mapped);
    }

    if (!has_mapped) {

        // 全都没读出来时再看节点在不在：kgsl 的 mapped、GTT 的 used/total、
        // dma-buf 的 buffers 目录，任何一处存在都说明"这平台有这一格、只是没读到"，
        // 按权限报（界面显示 "-"）；全都找不到才是真不支持。
        char gtt_used_path[TM_PATH_MAX];
        char gtt_total_path[TM_PATH_MAX];

        snprintf(gtt_used_path, sizeof(gtt_used_path), "%s/device/mem_info_gtt_used", device->path);
        snprintf(gtt_total_path, sizeof(gtt_total_path), "%s/device/mem_info_gtt_total", device->path);

        const char *const shared_candidates[] = {
            gtt_used_path,
            gtt_total_path,
            "/sys/class/kgsl/kgsl/mapped",
            "/sys/kernel/dmabuf/buffers",
            "/sys/kernel/dma_buf/buffers",
        };

        GpuField missing = gpu_missing_from_paths(shared_candidates,
                                                  sizeof(shared_candidates) / sizeof(shared_candidates[0]));

        if (missing.status != NULL && strcmp(missing.status, TM_FIELD_STATUS_NOT_AVAILABLE) == 0) {
            return gpu_field_missing(TM_FIELD_STATUS_UNSUPPORTED);
        }

        return missing;
    }

    if (used) {
        return gpu_field_value(mapped);
    }

    static char meminfo[4096];

    if (read_text_file("/proc/meminfo", meminfo, (int) sizeof(meminfo)) <= 0) {
        return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
    }

    long long total = meminfo_bytes(meminfo, "MemTotal");

    if (total > 0) {
        return gpu_field_value(total);
    }

    return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
}

/**
 * 填写函数：内核驱动名与版本。
 *
 * 名字来自 <node>/device/driver 这条链接（mali / amdgpu / kgsl …），
 * 版本来自 /sys/module/<驱动名>/version —— 只有导出 MODULE_VERSION 的驱动才有。
 * 两者分开报：驱动名有、版本没有，是常见情况。
 */
static void gpu_field_driver(const TmDeviceRef *device, char *name, int name_size,
                             char *version, int version_size)
{
    name[0] = '\0';
    version[0] = '\0';

    char link[TM_PATH_MAX];

    snprintf(link, sizeof(link), "%s/device/driver", device->path);

    gpu_link_name(link, name, name_size);

    if (name[0] == '\0') {
        return;
    }

    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/sys/module/%s/version", name);

    read_text_file(path, version, version_size);

    trim_disk_text(version);
}

/** 整数字段落地：有值写数值，没值写状态。 */
static void gpu_put_int_field(TmOutDevice *device, const char *key, GpuField field)
{
    if (field.has) {
        device_put_int(device, key, field.number, TM_FIELD_STATUS_NOT_AVAILABLE);
    } else {
        device_put_value(device, key, query_value_none(query_status_of(field.status)));
    }
}

/** 文本字段落地：有值写文本，没值写状态。 */
static void gpu_put_text_field(TmOutDevice *device, const char *key,
                               const char *text, const char *status)
{
    if (text != NULL && text[0] != '\0') {
        device_put(device, key, text);
    } else {
        device_put_value(device, key, query_value_none(query_status_of(status)));
    }
}

/**
 * 把显卡实例填进包。
 *
 * 每个实例发：name / node / gpu_usage / gpu_temp_c / dedicated_total_bytes /
 * dedicated_used_bytes / shared_total_bytes / shared_used_bytes（高通走 KGSL
 * mapped，总量按 MemTotal）/ gfx_api / gfx_api_version。
 * 读不到的不发（页面按"不支持 / -"显示）。
 */
static int build_gpu_devices(TmOutDevice *out, int capacity,
                             const TmDeviceRef *devices, int count)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    int limit = count;

    if (limit > capacity) {
        limit = capacity;
    }

    bool has_api = false;

    bool has_vulkan = false;

    bool has_opengl = false;

    char vulkan_version[24] = {0};

    char opengl_version[24] = {0};

    gpu_detect_gfx_api(&has_api,
                       &has_vulkan, vulkan_version, (int) sizeof(vulkan_version),
                       &has_opengl, opengl_version, (int) sizeof(opengl_version));

    for (int i = 0; i < limit; i++) {

        device_begin(&out[i], devices[i].id, TM_DEV_TYPE_GPU);

        device_put(&out[i], "name", devices[i].name);

        // 这张卡是从哪个节点发现的：枚举时命中的 sysfs 目录原样发上去
        // （/sys/class/kgsl/kgsl-3d0 、/sys/class/misc/mali0 、/sys/class/drm/card0 …）。
        // 与磁盘的 node（块名 sda）一个作用：让上层与日志能分辨"这是哪一张卡"，
        // 名字抓不到时也能拿它兜住，不至于两行都写「不支持」看不出区别。
        device_put(&out[i], "node", devices[i].path);

        // 厂商：客户端拿它决定"这张卡该按哪套接口去理解"，
        // 也让人一眼看出这台机器上是哪家的 GPU（QUALCOMM / MEDIATEK / ARM …）
        device_put(&out[i], "vendor", gpu_vendor_text(devices[i].vendor));

        gpu_put_int_field(&out[i], "gpu_usage", gpu_field_usage(&devices[i]));

        gpu_put_int_field(&out[i], "gpu_temp_c", gpu_field_temperature(&devices[i]));

        gpu_put_int_field(&out[i], "dedicated_total_bytes",
                          gpu_field_dedicated(&devices[i], false));

        gpu_put_int_field(&out[i], "dedicated_used_bytes",
                          gpu_field_dedicated(&devices[i], true));

        gpu_put_int_field(&out[i], "shared_total_bytes",
                          gpu_field_shared(&devices[i], false));

        gpu_put_int_field(&out[i], "shared_used_bytes",
                          gpu_field_shared(&devices[i], true));

        // 驱动：名字（mali / amdgpu / kgsl）+ 版本（只有导出 MODULE_VERSION 的驱动才有）
        char driver_name[64];

        char driver_version[64];

        gpu_field_driver(&devices[i], driver_name, (int) sizeof(driver_name),
                         driver_version, (int) sizeof(driver_version));

        gpu_put_text_field(&out[i], "driver_version", driver_version,
                           TM_FIELD_STATUS_NOT_AVAILABLE);

        // 驱动日期：Linux / Android 没有可靠的发布日期来源，
        // 文件 mtime 只是 OTA 复制时间、内核版本日期也不是驱动日期，
        // 所以这一格一律明确写"不支持"，不猜一个看起来像日期的值
        device_put_value(&out[i], "driver_date",
                         query_value_none(TM_QUERY_UNSUPPORTED));

        char location[64];

        gpu_pci_location(devices[i].path, location, (int) sizeof(location));

        if (location[0] != '\0') {
            device_put(&out[i], "location", location);
        }

        // 图形 API：两路各报各的，页面上谁有就摆谁。
        //   有版本串   -> 那一行显示 "Vulkan 1.1" / "OpenGL ES 3.2"
        //   值是 NONE  -> 查过、这台机器确实没有那一样（那一行不摆）
        //   键整格缺失 -> 这次没查成（老 so 或探测失败），由客户端退回旧的二选一
        if (has_api) {

            device_put(&out[i], "vulkan_version", has_vulkan ? vulkan_version : "NONE");

            device_put(&out[i], "opengl_version", has_opengl ? opengl_version : "NONE");

            // 兼容老字段：仍发一份"首选 API"，供复制文本等旧路径使用
            device_put(&out[i], "gfx_api", has_vulkan ? "VULKAN" : (has_opengl ? "OPENGL" : "NONE"));

            if (has_vulkan) {
                device_put(&out[i], "gfx_api_version", vulkan_version);
            } else if (has_opengl) {
                device_put(&out[i], "gfx_api_version", opengl_version);
            }
        }
    }

    return limit;
}

 /**
  * 处理 typeCode=5：抓一次设备快照，按设备集合发出。
  *
  * 顺序：先把各类设备加系统信息全部填进缓冲 -> 数清 D 行与 F 行的总条数 ->
  * 发包头 -> 逐块发。条数必须先定：客户端按 count 收满才算完整包。
  *
  * 包内容（有实例才发，没有的类型一条 D 行都不发）：
  *   CPU     : 永远一块，把所有物理处理器合并；含利用率 / 频率 / 拓扑 / 缓存 / 逐核利用率
  *   MEMORY  : 一台，含 /proc/meminfo 的统计项、zram 压缩量、Device Tree 里的规格
  *   DISK    : 每块物理盘一块，含型号 / 介质 / 总线 / 容量 / 已格式化 /
 *             系统盘 / 页面文件 / 活动时间 / 平均响应 / 读写速率
  *   NETWORK : 每个接口一块（虚拟接口也报），带角色 / 型号 / SSID / 连接类型 /
  *             地址 / 信号强度 / 收发速率；要显示哪一类由客户端决定
  *   GPU     : 每个图形设备一块，带利用率 / 温度 / 专用显存 / 图形 API 版本
  *   SYSTEM  : 进程 / 线程 / 句柄 / 开机时长
  *
  * 附加参数是本帧的设备模型上下文（见 apply_device_context）：请求即权威，
  * 每帧都按它刷新，所以上层拿到新数据时不必重启本进程。
  *
  * @param arguments      本次请求带的 `键=值` 列表
  * @param argument_count 列表长度
  */
static bool handle_device_snapshot(unsigned long long seq, int type,
                                  const char *const *arguments, int argument_count)
{
    // 这条通道是单线程顺序处理，缓冲用 static：不占栈，也不用每次清整块
    static TmOutDevice devices[TM_OUT_DEVICE_MAX];
    static TmDeviceRegistry registry;
    static TmCpuUsage usage;

    // 先吃上下文：CPU 名称要用型号 / 厂商 / 库路径，采集前定下来，这一帧就一致
    apply_device_context(arguments, argument_count);

    // 再枚举实例：一次请求只枚举一次，采集期间 DeviceId 不会漂移
    registry_refresh(&registry);

    // 利用率整机与每逻辑核共用一对采样点；首帧没有对比点，字段会按 NO_BASELINE 发
    read_cpu_usage(&usage);

    int device_count = 0;

    device_count += build_cpu_devices(devices + device_count,
                                      TM_OUT_DEVICE_MAX - device_count,
                                      &registry, &usage);

    // 内存：/proc/meminfo 的统计项 + zram 压缩量 + 设备树里的规格
    device_count += build_memory_devices(devices + device_count,
                                         TM_OUT_DEVICE_MAX - device_count,
                                         registry.memory, registry.memory_count);

    // 系统信息紧跟硬件之后：进程 / 线程 / 句柄三项都在这一台里
    device_count += build_system_device(devices + device_count,
                                        TM_OUT_DEVICE_MAX - device_count);

    // 磁盘：每块物理盘一块，带型号 / 介质 / 总线 / 容量 / 已格式化 / 系统盘 /
    // 页面文件 / 活动时间 / 平均响应 / 读写速率（详见 build_disk_devices 上方的说明）
    device_count += build_disk_devices(devices + device_count,
                                       TM_OUT_DEVICE_MAX - device_count,
                                       registry.disk, registry.disk_count);

    device_count += build_network_devices(devices + device_count,
                                          TM_OUT_DEVICE_MAX - device_count,
                                          registry.network, registry.network_count);

    // 显卡：每张卡一块，带利用率 / 温度 / 专用显存 / 图形 API 版本
    device_count += build_gpu_devices(devices + device_count,
                                      TM_OUT_DEVICE_MAX - device_count,
                                      registry.gpu, registry.gpu_count);

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

    // 设备快照：第四段之后全是上下文参数（键=值，可多个），收齐后交给采集侧
    if ((int) type == TM_REQ_DEVICE_SNAPSHOT) {

        const char *arguments[TM_DEVICE_CONTEXT_ARGUMENT_MAX];
        int argument_count = 0;

        char *token = strtok_r(NULL, " \t", &save);

        while (token != NULL && argument_count < TM_DEVICE_CONTEXT_ARGUMENT_MAX) {

            arguments[argument_count++] = token;

            token = strtok_r(NULL, " \t", &save);
        }

        return handle_device_snapshot(seq, (int) type, arguments, argument_count);
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
    // 本进程不接受启动参数：设备模型的 Android 层事实统一由设备快照请求带入
    // （typeCode=5 的附加参数，见 apply_device_context），这样上层数据变了也不用重启进程。
    (void) argc;
    (void) argv;

    // 父进程先关掉管道、本进程还在往 stdout 写，默认会收到 SIGPIPE 直接死掉；
    // 忽略它，这样写失败只让 printf / fflush 返回错误，循环还能按 EOF 正常收尾。
    signal(SIGPIPE, SIG_IGN);

    // 磁盘四项指标的常驻采样线程：250 ms 一拍、最近 1 秒窗口结算，
    // 快照请求只查表。放在 READY 之前起，第一屏就有一拍可用；
    // 起不来（极少见）会自动退回"请求现算"，功能不丢。
    disk_sampler_start();

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
