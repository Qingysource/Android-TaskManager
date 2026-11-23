/* ============================================================
 * Protocol.h — 协议层
 * ============================================================ */

#ifndef TM_PROTOCOL_H
#define TM_PROTOCOL_H

#include "Header.h"

/* ---- 对外接口 ---- */

/** 状态串转回枚举：老调用点按状态串传参，内部统一成枚举再落地。 */
TmQueryStatus query_status_of(const char *status);

/**
 * 百分号编码输出一个值（与客户端 data/LinuxService.kt ① 段的 escapeValue 逐项一致）。
 * 编码表见头部注释第四节；不编码会把含空格的值（cmdline、路径）拆成两半。
 *
 * @param value 原始值（不可为 NULL）
 */
void print_escaped_value(const char *value);

/** 打印一条附加信息行 `I 键=值`；值同样要百分号编码，否则空格会把一行拆断。 */
void print_info_line(const char *key, const char *value);

/**
 * 打印应答包头 `PKG <seq> 1 <typeCode> <maskHex> <status> <count>`。
 * isFromSvc 恒为 1：这条通道上只有服务端发 PKG。
 *
 * @param count 后面跟的 P 行条数；0 = 没有数据行（信息类 / 控制回执）
 */
void reply_package_header(unsigned long long seq, int type,
                                 unsigned long long mask, int status, int count);

/** 开一个设备：清掉旧字段，记下 id 与类型。 */
void device_begin(TmOutDevice *device, const char *id, const char *type);

/** 造一个只有状态的结果（这一项没有值）。 */
TmQueryValue query_value_none(TmQueryStatus status);

/** 造一个有文本值的结果；文本为空按 UNSUPPORTED 处理，不发空字段。 */
TmQueryValue query_value_text(TmQueryStatus status, const char *text);

/** 造一个整数值的结果：负数按没有值处理（采集函数统一用 -1 表示读不到）。 */
TmQueryValue query_value_int(long long value, TmQueryStatus missing);

/** 造一个浮点值的结果：固定小数位，负数按没有值处理。 */
TmQueryValue query_value_float(bool has_value, double value, int digits,
                                     TmQueryStatus missing);

/**
 * 所有字段落地的唯一出口：状态在这里转成感叹号标记，有值才写数值。
 *
 * 为什么收成一个出口：字段有十几种来源（sysfs、/proc、外部实现、算法差值），
 * 只要每个来源都能说清自己是 TM_QUERY_OK 还是某种失败，输出就永远只有两种形态。
 * 超过字段上限直接丢，宁可少一格也不越界。
 */
void device_put_value(TmOutDevice *device, const char *key, TmQueryValue value);

/** 追加一条有值的文本字段。 */
void device_put(TmOutDevice *device, const char *key, const char *value);

/** 整数字段：负数按没有值处理（采集函数统一用 -1 表示读不到）。 */
void device_put_int(TmOutDevice *device, const char *key, long long value,
                           const char *status);

/** 发一个设备：一行 D 头 + 若干 F 行；字段值走同一套百分号编码。 */
void emit_device_block(const TmOutDevice *device);

#endif /* TM_PROTOCOL_H */
