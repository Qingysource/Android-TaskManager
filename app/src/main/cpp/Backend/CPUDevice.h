/* ============================================================
 * CPUDevice.h — CPU 设备
 * ============================================================ */

#ifndef TM_CPU_DEVICE_H
#define TM_CPU_DEVICE_H

#include "Header.h"

/* ---- 对外接口 ---- */

/**
 * 采一帧 CPU 利用率：整机与每个逻辑核**共用同一对采样点**，不重复睡。
 *
 * 采样点缓存：距上次采样不够 TM_DEVICE_CPU_SAMPLE_MS 才补睡一会儿。
 * 刷新间隔本来就有 0.5~4 秒，正常节奏下一次都不用睡。
 *
 * @return false = 第一次调用（只记了基准，没有对比点），或 /proc/stat 读不到
 */
bool read_cpu_usage(TmCpuUsage *out);

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
int enumerate_cpu_devices(TmCpuDevice *out, int capacity);

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
extern char g_cpu_name_database[2][TM_PATH_MAX];

/** 实际给了几条名称库路径（0 = 没有库，只能回退 厂商 + 大写型号）。 */
extern int g_cpu_name_database_count;

/** SoC 型号标识（请求键 soc_model），比较时不分大小写，回退拼名时转大写。 */
extern char g_soc_model[TM_OUT_VALUE_MAX];

/** SoC 厂商原始值（请求键 soc_manufacturer），可能形如 QTI / QCOM / MTK / SAMSUNG。 */
extern char g_soc_manufacturer[TM_OUT_VALUE_MAX];

/**
 * 上下文是否变过（名称探测的缓存失效标记）。
 *
 * 快照每帧都会把这三个值带上，但值本身开机不会变；逐帧重读 cpuinfo 与库文件
 * 纯属浪费，所以只在值真的换了才让下次探测重算。
 */
extern bool g_cpu_name_context_dirty;

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
  *   speed_mhz       所有 policy 里 cpuinfo_cur_freq 的最大值（读不到再比 scaling_cur_freq）
  *   base_mhz        全机每颗逻辑核 cpuinfo_max_freq 的最小值（最保守的基准值）
  *   cpu_temp_c      /sys/class/thermal 里 type 含 cpu 的热区求平均（℃）
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
int build_cpu_devices(TmOutDevice *out, int capacity,
                             const TmDeviceRegistry *registry, const TmCpuUsage *usage);

// ============================================================
// 给「设备扩展快照」（typeCode=6）用的几个薄封装
// ============================================================
//
// 这四项在内核采集里本来就有，但都写成 CPUDevice.c 的文件内静态函数：
// 设备快照（5）已经在用它们，**不要为了扩展页把它们改成外部符号**——
// 那样等于给既有快照改链接边界。这里只加一层转发，既不复制解析代码
// （两份解析迟早走形），也不动既有实现。

/** 收集 /sys/devices/system/cpu 下的 cpuN 编号（列目录 → possible → 固定编号三档）。 */
int tm_cpu_collect_ids(int *out, int capacity);

/** 收集 /sys/devices/system/cpu/cpufreq 下的 policyN 编号（列目录 → policy0..7 stat 两档）。 */
int tm_cpu_collect_policies(int *out, int capacity);

/** 读一整轮 /proc/stat（汇总行 + 每个 cpuN）。false = 打不开或一行都没解析出来。 */
bool tm_cpu_read_stat(TmCpuStatTable *out);

/** 两个采样点之间的利用率（%）；-1 = 增量不成立（计数器回退 / 这一核不在两轮里）。 */
double tm_cpu_percent_between(const TmCpuTimes *later, const TmCpuTimes *before);

#endif /* TM_CPU_DEVICE_H */
