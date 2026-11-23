/* ============================================================
 * ProcessSnapshot.h — 进程快照
 * ============================================================ */

#ifndef TM_PROCESS_SNAPSHOT_H
#define TM_PROCESS_SNAPSHOT_H

#include "Header.h"

/* ---- 对外接口 ---- */

/** 字段表长度（= 43）。 */
extern const int k_field_count;

/** 本轮快照（每次查询重建）。 */
extern TmProcTable g_snapshot;

/** 上一轮快照 = 下一轮的基准（增量与比率全靠它）。 */
extern TmProcTable g_baseline;

/** 上一轮 /proc/stat 的总 CPU jiffies；<0 = 还没有基准。 */
extern long long g_cpu_total_prev;

/** 页大小与每秒时钟节拍：启动时取一次，避免每个进程都 sysconf 一遍。 */
extern long g_page_size;

extern long g_clock_ticks;

/**
 * 在表里按 pid（可带 start_ticks）找一行。返回 NULL = 没有这个进程。
 *
 * start_ticks > 0 时要求它也必须相同：PID 会被系统复用，
 * 只比 pid 会把"另一个人"当成上一轮的那个进程，增量就算到别人头上。
 */
TmProcStat *table_find(TmProcTable *table, int pid, long long start_ticks);

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
bool parse_proc_stat(const char *text, TmProcStat *out);

/**
 * 第一轮（mini）采样：遍历 /proc，每个进程只读一次 stat。
 *
 * @param scope 范围筛选。选 USER / SYSTEM 时，属主 stat 是筛选本身；
 *              选 ALL 时也会做这一次 stat —— 它同时供 P 行的 uid 扩展键使用，
 *              每进程一次目录 stat 在整轮里只有毫秒级，换来 Android 侧能直接映射包名
 * @return 收录到的进程数；0 = /proc 打不开（几乎没有这种情况）
 */
int collect_snapshot(int scope);

/** 清掉基准（REINIT 用）：下一次快照重新建立，比率类字段会回 NO_BASELINE。 */
void baseline_clear(void);

/**
 * 处理"查询进程快照"。
 *
 * 顺序：第一轮 mini 采样 -> 结算派生字段 -> 先发包头（条数已定）-> 逐行发 -> 提交基准。
 * 包头必须带着准确条数先发：客户端按 count 判断"包收完了没有"。
 */
bool handle_query_process(TmBackend *backend, unsigned long long seq,
                                 int type, unsigned long long mask, const char *scope_text);

#endif /* TM_PROCESS_SNAPSHOT_H */
