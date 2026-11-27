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
 *     virtualization 一律发英文状态码（协议不放本地语言，显示文案由客户端映射）：
 *       ENABLED_GUNYAH / ENABLED_KVM / ENABLED_GENIEZONE / ENABLED_HALLA / DISABLED / UNSUPPORTED
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
 *           exe_path / cmdline / selinux / platform / arch / is_admin / nx_protect
 *   - 归 Android 服务填（回 NOT_AVAILABLE）：package_name / target_sdk /
 *     description / android_priority / isolation —— 这五格是 Android Framework
 *     的事实（包名、目标 SDK、应用名、框架优先级、是否框架管的进程），内核不知道，
 *     由 AndroidService 以 shell / root 身份查询后补进快照，本后端不再冒充
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

#ifndef TM_BACKEND_HEADER_H
#define TM_BACKEND_HEADER_H

/* ---- 外部系统头 ---- */
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

/* ---- 协议常量与公共类型 ---- */

#define TM_LINE_MAX 4096

#define TM_VALUE_MAX 160

#define TM_PROTO_VERSION 3

#define TM_REQ_QUERY_PROCESS 1

#define TM_REQ_QUERY_INFO 2

#define TM_REQ_CONTROL 3

#define TM_REQ_PING 4

#define TM_REQ_DEVICE_SNAPSHOT 5

/**
 * 设备扩展快照：逐集群 / 逐核心的细粒度设备数据（当前只有 CPU 这一组）。
 *
 * 为什么单开一个 typeCode 而不是往 5 里塞：设备快照（5）与进程快照（1）是既有功能，
 * 字段结构与收包条数都已经定型；扩展信息要按集群发多台设备、每核多五六个字段，
 * 塞进 5 会改动它的字段集合与 UI 解析，一处出错两页一起坏。新类型只在
 * handle_request 里多一个分支，5 与 1 的代码一行都不动。
 *
 * 请求不带附加参数：`REQ <seq> 6 0x0`。
 */
#define TM_REQ_DEVICE_EXTENDED_SNAPSHOT 6

#define TM_DEVICE_CONTEXT_ARGUMENT_MAX 12

#define TM_DEVICE_CPU_SAMPLE_MS 200

#define TM_CTL_KILL_PROCESS 1

#define TM_CTL_REINIT 2

#define TM_CTL_DUMP_STATE 3

#define TM_CTL_SHUTDOWN 4

/*
 * 进程控制类动作码（REQ <seq> 3 0x0 <code> <pid> <start_ticks> [arg]）。
 *
 * 全部先核对 pid + start_ticks 才动手：PID 会被复用，只凭 pid 会动到另一个进程。
 */
#define TM_CTL_KILL_TREE 5

#define TM_CTL_SET_PRIORITY 6

#define TM_CTL_SET_AFFINITY 7

#define TM_CTL_SCHED_INFO 8

#define TM_CTL_WAIT_CHAIN 9

#define TM_CTL_MEM_DUMP 10

#define TM_STATUS_OK 0

#define TM_STATUS_UNSUPPORTED 1

#define TM_STATUS_BAD_ARGUMENT 2

#define TM_STATUS_INTERNAL 3

#define TM_STATUS_ACCEPTED 4

#define TM_STATUS_PROCESS_EXITED 5

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

#define TM_FIELD_ALL ((1ULL << 43) - 1)

/**
 * 字段表一项：掩码位 + 输出时的键名。
 * 键名必须与客户端 DetailColumnId 的字符串值相同，客户端直接用键映射到表格列。
 */
typedef struct TmFieldDef {
    unsigned long long mask;

    const char *key;
} TmFieldDef;

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

#define TM_MAX_PROCESSES 8192

#define TM_COMM_MAX 64

#define TM_TEXT_MAX 4096

#define TM_PATH_MAX 512

#define TM_SCOPE_ALL 0

#define TM_SCOPE_USER 1

#define TM_SCOPE_SYSTEM 2

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

#define TM_STAT_CPU_MAX 256

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

#define TM_DEV_TYPE_CPU "CPU"

#define TM_DEV_TYPE_MEMORY "MEMORY"

#define TM_DEV_TYPE_DISK "DISK"

#define TM_DEV_TYPE_NETWORK "NETWORK"

#define TM_DEV_TYPE_GPU "GPU"

#define TM_DEV_TYPE_SYSTEM "SYSTEM"

/** 设备扩展快照里的两种设备类型：一个集群一台，一颗逻辑核一台。 */
#define TM_DEV_TYPE_CPU_CLUSTER "CPU_CLUSTER"

#define TM_DEV_TYPE_CPU_CORE "CPU_CORE"

#define TM_OUT_DEVICE_MAX 64

/**
 * 设备扩展快照的设备条数上限：集群最多 8 个 + 逻辑核最多 64 个，
 * 单开一个宏而不是抬高 TM_OUT_DEVICE_MAX —— 那个 64 是设备快照的容量，
 * 改它会让既有快照的设备缓冲一起变大（栈 / static 占用都跟着涨）。
 */
#define TM_OUT_EXTENDED_DEVICE_MAX 96

/**
 * 一台设备的字段上限。
 *
 * 内存这一台自己就要 26 格（meminfo 那套统计 19 个 + 名称 + 标称容量 /
 * 为硬件保留 / System RAM / 预留区并集 + 规格 + 厂商），原来写 20 时，
 * 排在后面的标称容量、规格、厂商全被这个上限静默丢掉 —— 页面上就是
 * "类型没有、厂商暂无数据"，而底层其实已经读到了。
 */
#define TM_OUT_FIELD_MAX 32

#define TM_OUT_ID_MAX 32

#define TM_OUT_KEY_MAX 40

#define TM_OUT_VALUE_MAX 128

#define TM_OUT_DISK_MAX 16

#define TM_OUT_NETWORK_MAX 24

#define TM_OUT_GPU_MAX 4

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

/**
 * 一次查询的结果：要么有值，要么是一个状态。
 * text 只在 status == TM_QUERY_OK 时有意义。
 */
typedef struct {
    TmQueryStatus status;
    char text[TM_OUT_VALUE_MAX];
} TmQueryValue;

/** 已登记节点的去重表：最多四张卡，每条记两个路径（节点本身 + 它的 device）。 */
typedef struct {
    char path[TM_OUT_GPU_MAX * 2][TM_PATH_MAX];
    int count;
} TmGpuSeen;

#define TM_NET_SAMPLE_MAX 32

#define TM_NET_IFACE_MAX 32

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

#define TM_DISK_SECTOR_BYTES 512LL

#define TM_DISK_SAMPLE_MAX 32

#define TM_DISK_DEVNUM_MAX 24

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

/** 一台盘的上一次采样：块名 + 计数器 + 采样时刻（单调时钟毫秒）。 */
typedef struct {
    char name[64];
    TmBlockStat stat;
    long long at_ms;
    bool valid;
} TmDiskPrev;

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

#define TM_DISK_TICK_MS 250

#define TM_DISK_WINDOW_SAMPLES 17

#define TM_DISK_RATE_SPAN 4

#define TM_DISK_RESPONSE_TARGET_IOS 100

#define TM_DISK_MIN_SPAN_MS 100

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

#define TM_DRAM_TYPE_COUNT 10

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

#define TM_MEM_RANGE_MAX 64

/** vkEnumerateInstanceVersion 的签名：传入 32 位编码版本号。 */
typedef unsigned int (*gpu_vulkan_version_fn)(unsigned int *);

/** 一格字段的读取结果：要么有数值，要么是一个状态（二者互斥）。 */
typedef struct {
    bool has;
    long long number;
    const char *status;
} GpuField;

#include "Protocol.h"

#include "DeviceSnapshot.h"

#include "DeviceExtendedSnapshot.h"

#include "ProcessSnapshot.h"
#include "ProcessControl.h"

#include "CPUDevice.h"

#include "CPUExtended.h"

#include "MemoryDevice.h"

#include "Disk.h"

#include "GPUDevice.h"

#include "NetworkDevice.h"

#include "SystemDevice.h"

#include "Common.h"

#endif /* TM_BACKEND_HEADER_H */
