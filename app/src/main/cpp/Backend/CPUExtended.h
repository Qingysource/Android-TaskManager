/* ============================================================
 * CPUExtended.h — CPU 扩展信息（设备扩展快照 typeCode=6 的 CPU 部分）
 * ============================================================ */

#ifndef TM_CPU_EXTENDED_H
#define TM_CPU_EXTENDED_H

#include "Header.h"

/* ---- 对外接口 ---- */

/**
 * 填扩展快照的设备集合：先按集群发一台 CPU_CLUSTER，再逐核发 CPU_CORE。
 *
 * 与 [build_cpu_devices]（设备快照 typeCode=5）的区别，三条都要守住：
 *   1) 粒度：这里一颗逻辑核就是一台设备，不再合并成"一颗处理器"；
 *   2) 基线：这里用 CPUExtended.c 自己的一份 /proc/stat 基准，
 *      不推进、也不读取 read_cpu_usage() 的 static 基准 —— 两个采样器的
 *      节奏不同（扩展页跟设置档位、设备快照跟后台轮询），共用基准会让
 *      两个差值互相吃掉时间窗，利用率一会儿偏高、一会儿为 0；
 *   3) 输出：设备快照那一套字段一个都不改，这里只用新的设备类型与新键名。
 *
 * 集群划分（按可信度从高到低）：
 *   1) /sys/devices/system/cpu/cpufreq/policyN/related_cpus（内核给的调频域成员，
 *      实际机器上一个 policy 就是一个簇，最贴近"同步调频的一组核"）；
 *   2) 读不到 related_cpus 时退回 cpuN/topology/{physical_package_id,cluster_id} 分组；
 *   3) 两者都没有时整机当一个集群（宁可给一个粗分组，也不编造簇边界）。
 *
 * @param out      设备数组（调用方一般给 static）
 * @param capacity 数组长度
 * @return 设备条数（集群 + 核心）；0 = 这台机器一颗核都没枚举到
 */
int build_cpu_extended_devices(TmOutDevice *out, int capacity);

#endif /* TM_CPU_EXTENDED_H */
