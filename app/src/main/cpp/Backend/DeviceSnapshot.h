/* ============================================================
 * DeviceSnapshot.h — 设备快照装配
 * ============================================================ */

#ifndef TM_DEVICE_SNAPSHOT_H
#define TM_DEVICE_SNAPSHOT_H

#include "Header.h"

/* ---- 对外接口 ---- */

// ============================================================
// 位置枚举：先把每类设备"在哪"找出来，采集才有地方可读
//
// 每类一个函数，返回条数（0 = 这台机器没有这一类，或 sysfs 不允许读）。
// 同一个函数只负责填 id / path / name，不读任何数据。
// ============================================================

/** 从路径里取最后一段名字：/sys/block/sda -> sda。 */
void name_from_path(const char *path, char *out, int capacity);

/**
 * 设备名兜底：这一版只有名称字段，抓不到具体名字时统一写 NAME。
 *
 * 为什么不用空串：空串在协议里是"这个字段有值，但值是空的"，客户端会显示成
 * 一片空白，分不清是没名字还是解析丢了；写 NAME 能明确看出"这个名字没拿到"。
 */
void ensure_device_name(char *name, int capacity);

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
bool handle_device_snapshot(unsigned long long seq, int type,
                                  const char *const *arguments, int argument_count);

#endif /* TM_DEVICE_SNAPSHOT_H */
