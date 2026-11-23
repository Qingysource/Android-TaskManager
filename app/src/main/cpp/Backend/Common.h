/* ============================================================
 * Common.h — 公共工具
 * ============================================================ */

#ifndef TM_COMMON_H
#define TM_COMMON_H

#include "Header.h"

/* ---- 对外接口 ---- */

/**
 * 裁掉首尾空白（空格、\t、\r、\n），原地修改并返回有效起点。
 *
 * @param text 可写缓冲区（就地改写，末尾补 '\0'）
 * @return 去掉前导空白后的起点
 */
char *trim_text(char *text);

/** 目录名是不是纯数字（只认它，/proc/net、/proc/sys 这些都不是进程）。 */
bool is_pure_digits(const char *text);

/**
 * 读一个文本文件。返回读到的字节数；-1 = 打不开（不存在或权限不足）。
 *
 * 注意：二进制内容（cmdline 的 \0 分隔）也会被原样读进来，
 * 调用方拿到的是"原始字节 + 长度"，不是 C 字符串。
 */
int read_text_file(const char *path, char *buffer, int capacity);

/** 读符号链接的目标（/proc/<pid>/exe 这类）。返回长度；-1 = 读不到。 */
int read_link_text(const char *path, char *buffer, int capacity);

/** 目录里的条目数（fd 数用）；-1 = 打不开。 */
int count_directory_entries(const char *path);

// ============================================================
// 设备快照（typeCode=5）
//
// 只采性能页 CPU 页要显示的东西：整机利用率、当前/基准频率、进程/线程/文件描述符数、
// 开机时长、插槽/内核/逻辑处理器数、三级缓存。
//
// 包结构：没有 P 行，全是 I 行，所以包头 count = I 行条数（见头部注释第三节）。
//
// 两格留给 ELF 这一侧补：cpu_name（处理器商品名，要人看得懂，不要内核里的内部型号）
// 与 virtualization（虚拟化方案）。现在回 UNSUPPORTED，客户端按没值处理。
//
// 采集全是只读文件：提权身份能读得更全；cpufreq / cache 节点在部分机型上对应用身份不可读，
// 那就回 !NOT_AVAILABLE，不拿 0 顶替。
// ============================================================

/** 单调毫秒（算两次 CPU 采样隔了多久）。 */
long long monotonic_ms(void);

/** 读一个只放数字的文件（kHz / 秒 / 个数）。false = 打不开，或开头不是数字。 */
bool read_first_number(const char *path, double *out);

// ============================================================
// 采集：按设备填字段
// ============================================================

/**
 * 取一行里冒号之后的部分，去掉首尾空白。
 * @return false = 这一行没有冒号，或冒号后面是空的
 */
bool value_after_colon(const char *line, char *out, int capacity);

/** ASCII 大写，规则同上。 */
char ascii_upper(char c);

/** 两个字符串忽略大小写是否相等；任一为 NULL 都不相等。 */
bool text_equal_ignore_case(const char *left, const char *right);

/**
 * text 是否以 prefix 开头（忽略大小写），prefix 的有效长度是 prefix_length。
 */
bool text_prefix_ignore_case(const char *text, const char *prefix, size_t prefix_length);

/**
 * 把请求里的一项取值解码出来。
 *
 * 协议用空格分字段，所以值里不能有空格：客户端把空格、百分号、等号写成 %XX，
 * 这里只认 %XX 一种转义，其余字符原样搬。
 *
 * @param input    键名之后的部分（可能为空）
 * @param out      解码结果
 * @param capacity out 的字节数；超长的尾部丢弃，不截半个转义序列
 */
void decode_request_value(const char *input, char *out, size_t capacity);

#endif /* TM_COMMON_H */
