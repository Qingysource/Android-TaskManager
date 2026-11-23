/* ============================================================
 * GPUDevice.h — GPU 设备
 * ============================================================ */

#ifndef TM_GPU_DEVICE_H
#define TM_GPU_DEVICE_H

#include "Header.h"

/* ---- 对外接口 ---- */

/**
 * 枚举图形设备：四趟发现，前面的结果占位，后面的只补空缺。
 *
 * 上限 TM_OUT_GPU_MAX：手机 / 平板最多一两颗 GPU，四张是给 PCI 多卡留的余量。
 */
int enumerate_gpu_devices(TmDeviceRef *out, int capacity);

/**
 * 把显卡实例填进包。
 *
 * 每个实例发：name / node / gpu_usage / gpu_temp_c / dedicated_total_bytes /
 * dedicated_used_bytes / shared_total_bytes / shared_used_bytes（高通走 KGSL
 * mapped，总量按 MemTotal）/ gfx_api / gfx_api_version。
 * 读不到的不发（页面按"不支持 / -"显示）。
 */
int build_gpu_devices(TmOutDevice *out, int capacity,
                             const TmDeviceRef *devices, int count);

#endif /* TM_GPU_DEVICE_H */
