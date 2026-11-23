/* ============================================================
 * SystemDevice.h — 系统设备
 * ============================================================ */

#ifndef TM_SYSTEM_DEVICE_H
#define TM_SYSTEM_DEVICE_H

#include "Header.h"

/* ---- 对外接口 ---- */

/** 填系统信息：进程 / 线程 / 句柄 / 开机时长（不属于任何一类硬件）。 */
int build_system_device(TmOutDevice *out, int capacity);

#endif /* TM_SYSTEM_DEVICE_H */
