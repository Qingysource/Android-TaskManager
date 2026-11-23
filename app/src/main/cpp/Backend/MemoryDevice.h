/* ============================================================
 * MemoryDevice.h — 内存设备
 * ============================================================ */

#ifndef TM_MEMORY_DEVICE_H
#define TM_MEMORY_DEVICE_H

#include "Header.h"

/* ---- 对外接口 ---- */

/**
 * 枚举内存设备：/proc/meminfo 是全局一份，所以固定一台。
 *
 * 名字这一版给 NAME：/proc/meminfo 只有容量与用量，没有型号或厂商字段，
 * 硬编造一个商品名反而误导；等以后有更准的来源再补。
 */
int enumerate_memory_devices(TmDeviceRef *out, int capacity);

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
long long meminfo_bytes(const char *text, const char *key);

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
int build_memory_devices(TmOutDevice *out, int capacity,
                                const TmDeviceRef *devices, int count);

#endif /* TM_MEMORY_DEVICE_H */
