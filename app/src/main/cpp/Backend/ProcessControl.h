/* ============================================================
 * ProcessControl.h — 进程控制类操作（结束树 / 优先级 / 亲和性 / 等待链 / 内存转储）
 *
 * 与 ProcessSnapshot 的分工：那边只读 /proc 产数据，这边只"动手"。
 * 所有函数都先做身份校验（pid + start_ticks），校验不过一律返回 false 并给出 errno，
 * 绝不拿 pid 单独动手 —— PID 会被复用，只凭 pid 会动到另一个进程。
 * ============================================================ */

#ifndef TM_PROCESS_CONTROL_H
#define TM_PROCESS_CONTROL_H

#include "Header.h"

/** 等待链最多列多少线程（一个进程几百线程时只列前这么多，界面会显示"还有 N 个")). */
#define PC_WAIT_THREAD_MAX 48

/** 等待链单行长度（tid + 状态 + wchan + syscall + 名字）。 */
#define PC_WAIT_LINE_MAX 220

/** 一次内存转储最多写多少字节（超出部分裁掉，界面会标"已截断"）。 */
#define PC_DUMP_MAX_BYTES (256LL * 1024 * 1024)

/**
 * 读一个进程的身份：pid 对应的 start_ticks（stat 第 22 字段）与 ppid。
 *
 * @return false = /proc/<pid>/stat 读不到或解析不了（进程已退出）
 */
bool pc_read_identity(int pid, long long *start_ticks, int *ppid_out);

/**
 * 结束进程树：先按 /proc 里的 ppid 关系把子孙进程自下而上 SIGKILL，最后杀目标本身。
 *
 * 只杀"目标这一棵子树"，不碰同一进程组的其他进程；深度上限 16 层防环。
 *
 * @param killed      成功的进程数（含目标本身）
 * @param failed      失败数（EPERM 等）
 * @param errno_out   目标本身失败时的 errno；0 = 目标也成功
 */
bool pc_kill_tree(int pid, long long start_ticks, int *killed, int *failed, int *errno_out);

/**
 * 设置 nice 值（-20 最高，19 最低）。需要 CAP_SYS_NICE（root），
 * 非 root 只能"调低"（把 nice 变大），调高会被 EPERM 拒绝 —— 原样回 errno。
 */
bool pc_set_priority(int pid, long long start_ticks, int nice_value, int *errno_out);

/** 设置 CPU 亲和性：mask 的第 n 位 = 允许在 CPU n 上跑。空掩码视为参数错误。 */
bool pc_set_affinity(int pid, long long start_ticks, unsigned long long mask, int *errno_out);

/**
 * 设备有多少个**逻辑 CPU 核心**（不是物理处理器颗数）。
 *
 * 优先读 /sys/devices/system/cpu/present（内核实际枚举出来的逻辑核，
 * 形如 "0-7" 或 "0-3,4-7"），读不到退回 sysconf(_SC_NPROCESSORS_CONF)。
 * 设置相关性列复选框、解析掩码都以它为准。
 *
 * @return >=1；读不到任何信息时返回 1
 */
int pc_present_cpu_count(void);

/**
 * 读当前调度状态：nice、调度策略、亲和性掩码、线程数。
 * 四个出参都可以传 NULL（只关心其中几项时不必都取）。
 */
bool pc_sched_info(int pid, int *nice_value, int *policy, unsigned long long *mask,
                   int *threads, int *errno_out);

/**
 * 读等待链：逐线程取 state / wchan / syscall，填进 lines（每条一行，制表符分隔）。
 *
 * @return 实际填了几行；0 = 读不到（进程已退出或被 SELinux 挡住）
 */
int pc_wait_chain(int pid, char lines[PC_WAIT_THREAD_MAX][PC_WAIT_LINE_MAX]);

/**
 * 写一份精简的 native 内存转储（ELF core，只含可读映射，不含寄存器/线程上下文）。
 *
 * 为什么要"精简"：完整 core 需要 ptrace 附加、暂停线程、抓 NT_PRSTATUS 注释段，
 * 在 Android 上对非自身进程基本会被 SELinux 拒绝；只抓内存页是"能拿到多少写多少"，
 * 对"看某个进程的内存里有什么"这个用途仍然有效。
 *
 * @param bytes     实际写入的段数据字节数
 * @param segments  写了几个段
 * @param errno_out 失败原因（EPERM / ESRCH / EACCES ...）
 */
bool pc_mem_dump(int pid, long long start_ticks, const char *path,
                 long long *bytes, int *segments, int *errno_out);

#endif /* TM_PROCESS_CONTROL_H */
