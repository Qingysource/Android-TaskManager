/* ============================================================
 * NetworkDevice.h — 网卡设备
 * ============================================================ */

#ifndef TM_NETWORK_DEVICE_H
#define TM_NETWORK_DEVICE_H

#include "Header.h"

/* ---- 对外接口 ---- */

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
int enumerate_network_devices(TmDeviceRef *out, int capacity);

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
int build_network_devices(TmOutDevice *out, int capacity,
                                 const TmDeviceRef *devices, int count);

#endif /* TM_NETWORK_DEVICE_H */
