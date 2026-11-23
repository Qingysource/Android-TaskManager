/* ============================================================
 * DeviceExtendedSnapshot.h — 设备扩展快照装配（typeCode=6）
 * ============================================================ */

#ifndef TM_DEVICE_EXTENDED_SNAPSHOT_H
#define TM_DEVICE_EXTENDED_SNAPSHOT_H

#include "Header.h"

/* ---- 对外接口 ---- */

/**
 * 处理 typeCode=6：抓一次设备扩展快照，按设备集合发出。
 *
 * 与 [handle_device_snapshot]（typeCode=5）的关系：**并列，不是替换**。
 * 这里不读设备快照的注册表、不碰它的上下文参数、不改它的字段集合，
 * 只把扩展那一路的数据（现在只有 CPU 的集群 / 逐核）按同一套 D/F 行格式发出去。
 * 这样任何一侧出问题都不会牵连另一侧。
 *
 * 包结构：没有 P 行、没有 I 行，全是 D/F 行，包头 count = D 行 + F 行总条数。
 * 一台设备都没有时回 OK + count=0（客户端据此知道"这台机器没报任何核心"，
 * 而不是等一个超时）。
 *
 * @param seq  请求序号
 * @param type 请求类型（这里恒为 6，原样回带给客户端配对）
 * @return true = 继续服务循环
 */
bool handle_extended_snapshot(unsigned long long seq, int type);

#endif /* TM_DEVICE_EXTENDED_SNAPSHOT_H */
