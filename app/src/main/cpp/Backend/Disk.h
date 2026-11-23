/* ============================================================
 * Disk.h — 磁盘设备
 * ============================================================ */

#ifndef TM_DISK_H
#define TM_DISK_H

#include "Header.h"

/* ---- 对外接口 ---- */

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
int enumerate_disk_devices(TmDeviceRef *out, int capacity);

/** 起采样线程（传 detached，退出时不需要 join）。起不来就算了，请求侧会自动退回现算。 */
void disk_sampler_start(void);

/** 去掉 sysfs 文本尾部的换行与首尾空白（model / vendor 这类一行小文件都要过一遍）。 */
void trim_disk_text(char *text);

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
int build_disk_devices(TmOutDevice *out, int capacity,
                              const TmDeviceRef *devices, int count);

#endif /* TM_DISK_H */
