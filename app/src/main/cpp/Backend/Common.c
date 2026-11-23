/* ============================================================
 * Common.c — 公共工具：文本/路径读写、数字解析、大小写与比较辅助。
 * ============================================================ */

#include "Header.h"

/**
 * 裁掉首尾空白（空格、\t、\r、\n），原地修改并返回有效起点。
 *
 * @param text 可写缓冲区（就地改写，末尾补 '\0'）
 * @return 去掉前导空白后的起点
 */
char *trim_text(char *text)
{
    if (text == NULL) {
        return text;
    }

    char *begin = text;

    while (*begin != '\0' && isspace((unsigned char) *begin)) {
        begin++;
    }

    char *end = begin + strlen(begin);

    while (end > begin && isspace((unsigned char) end[-1])) {
        end--;
    }

    *end = '\0';

    return begin;
}

/** 目录名是不是纯数字（只认它，/proc/net、/proc/sys 这些都不是进程）。 */
bool is_pure_digits(const char *text)
{
    if (text == NULL || text[0] == '\0') {
        return false;
    }

    for (const char *cursor = text; *cursor != '\0'; cursor++) {

        if (*cursor < '0' || *cursor > '9') {
            return false;
        }
    }

    return true;
}

/**
 * 读一个文本文件。返回读到的字节数；-1 = 打不开（不存在或权限不足）。
 *
 * 注意：二进制内容（cmdline 的 \0 分隔）也会被原样读进来，
 * 调用方拿到的是"原始字节 + 长度"，不是 C 字符串。
 */
int read_text_file(const char *path, char *buffer, int capacity)
{
    if (capacity <= 0) {
        return -1;
    }

    FILE *file = fopen(path, "r");

    if (file == NULL) {
        return -1;
    }

    int total = 0;

    while (total < capacity - 1) {

        size_t got = fread(buffer + total, 1, (size_t) (capacity - 1 - total), file);

        if (got == 0) {
            break;
        }

        total += (int) got;
    }

    buffer[total] = '\0';

    fclose(file);

    return total;
}

/** 读符号链接的目标（/proc/<pid>/exe 这类）。返回长度；-1 = 读不到。 */
int read_link_text(const char *path, char *buffer, int capacity)
{
    if (capacity <= 0) {
        return -1;
    }

    ssize_t got = readlink(path, buffer, (size_t) (capacity - 1));

    if (got < 0) {
        buffer[0] = '\0';

        return -1;
    }

    buffer[got] = '\0';

    return (int) got;
}

/** 目录里的条目数（fd 数用）；-1 = 打不开。 */
int count_directory_entries(const char *path)
{
    DIR *dir = opendir(path);

    if (dir == NULL) {
        return -1;
    }

    int count = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }

        count++;
    }

    closedir(dir);

    return count;
}

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
long long monotonic_ms(void)
{
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return 0LL;
    }

    return (long long) now.tv_sec * 1000LL + (long long) now.tv_nsec / 1000000LL;
}

/** 读一个只放数字的文件（kHz / 秒 / 个数）。false = 打不开，或开头不是数字。 */
bool read_first_number(const char *path, double *out)
{
    char text[256];

    if (read_text_file(path, text, (int) sizeof(text)) < 0) {
        return false;
    }

    char *end = NULL;
    double value = strtod(text, &end);

    if (end == text) {
        return false;
    }

    *out = value;

    return true;
}

// ============================================================
// 采集：按设备填字段
// ============================================================

/**
 * 取一行里冒号之后的部分，去掉首尾空白。
 * @return false = 这一行没有冒号，或冒号后面是空的
 */
bool value_after_colon(const char *line, char *out, int capacity)
{
    const char *colon = strchr(line, ':');

    if (colon == NULL) {
        return false;
    }

    const char *start = colon + 1;

    while (*start == ' ' || *start == '\t') {
        start++;
    }

    snprintf(out, (size_t) capacity, "%s", start);

    int length = (int) strlen(out);

    while (length > 0) {

        char tail = out[length - 1];

        if (tail == '\n' || tail == '\r' || tail == ' ' || tail == '\t') {

            out[length - 1] = '\0';
            length--;

            continue;
        }

        break;
    }

    return length > 0;
}


/**
 * ASCII 小写。
 * 只认 A-Z：型号与厂商都是 ASCII，中文名字里的字节原样返回，不会被改坏。
 */
static char ascii_lower(char c)
{
    if (c >= 'A' && c <= 'Z') {
        return (char) (c + ('a' - 'A'));
    }

    return c;
}

/** ASCII 大写，规则同上。 */
char ascii_upper(char c)
{
    if (c >= 'a' && c <= 'z') {
        return (char) (c - ('a' - 'A'));
    }

    return c;
}


/**
 * 一个十六进制字符转数值（大小写都认）。
 *
 * 只给请求参数的百分号转义用；不是十六进制字符返回 -1。
 */
static int ascii_hex_digit(char c)
{
    if (c >= '0' && c <= '9') {
        return (int) (c - '0');
    }

    if (c >= 'a' && c <= 'f') {
        return (int) (c - 'a') + 10;
    }

    if (c >= 'A' && c <= 'F') {
        return (int) (c - 'A') + 10;
    }

    return -1;
}

/** 两个字符串忽略大小写是否相等；任一为 NULL 都不相等。 */
bool text_equal_ignore_case(const char *left, const char *right)
{
    if (left == NULL || right == NULL) {
        return false;
    }

    while (*left != '\0' && *right != '\0') {

        if (ascii_lower(*left) != ascii_lower(*right)) {
            return false;
        }

        left++;
        right++;
    }

    return *left == *right;
}

/**
 * text 是否以 prefix 开头（忽略大小写），prefix 的有效长度是 prefix_length。
 */
bool text_prefix_ignore_case(const char *text, const char *prefix, size_t prefix_length)
{
    for (size_t index = 0; index < prefix_length; index++) {

        if (text[index] == '\0') {
            return false;
        }

        if (tolower((unsigned char) text[index]) != tolower((unsigned char) prefix[index])) {
            return false;
        }
    }

    return true;
}

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
void decode_request_value(const char *input, char *out, size_t capacity)
{
    if (out == NULL || capacity == 0) {
        return;
    }

    size_t used = 0;

    for (const char *cursor = input; cursor != NULL && *cursor != '\0' && used + 1 < capacity; cursor++) {

        if (*cursor == '%' && cursor[1] != '\0' && cursor[2] != '\0') {

            int high = ascii_hex_digit(cursor[1]);
            int low = ascii_hex_digit(cursor[2]);

            if (high >= 0 && low >= 0) {

                out[used++] = (char) (high * 16 + low);

                cursor += 2;

                continue;
            }
        }

        out[used++] = *cursor;
    }

    out[used] = '\0';
}
