/* ============================================================
 * DeviceExtendedSnapshot.c — 设备扩展快照（typeCode=6）装配与输出。
 * ============================================================ */

#include "Header.h"

/**
  * 处理 typeCode=6：抓一次设备扩展快照，按设备集合发出。
  *
  * 顺序与设备快照一致：先填完所有设备 -> 数清 D 行与 F 行的总条数 ->
  * 发包头 -> 逐块发。条数必须先定：客户端按 count 收满才算完整包。
  *
  * 包内容（没有实例就一条 D 行都不发）：
  *   CPU_CLUSTER : 每个调频域 / 拓扑簇一块，带簇序号、policy、核数、成员核号
  *   CPU_CORE    : 每颗逻辑核一块，带利用率 / 当前频率 / 最大 / 最小 / 设置的最大频率
  *
  * 这条通道是单线程顺序处理，缓冲用 static：不占栈，也不用每次清整块。
  *
  * @param seq  请求序号
  * @param type 请求类型（恒 6）
  */
bool handle_extended_snapshot(unsigned long long seq, int type)
{
    static TmOutDevice devices[TM_OUT_EXTENDED_DEVICE_MAX];

    int device_count = build_cpu_extended_devices(devices, TM_OUT_EXTENDED_DEVICE_MAX);

    // 包头要先知道后面有几行：D 头算一行，每个字段算一行
    int line_count = 0;

    for (int index = 0; index < device_count; index++) {
        line_count += 1 + devices[index].field_count;
    }

    reply_package_header(seq, type, 0ULL, TM_STATUS_OK, line_count);

    for (int index = 0; index < device_count; index++) {
        emit_device_block(&devices[index]);
    }

    return true;
}
