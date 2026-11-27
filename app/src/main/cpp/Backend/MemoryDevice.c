/* ============================================================
 * MemoryDevice.c — 内存设备：meminfo、zram/swap 口径、DRAM 规格与保留内存区。
 * ============================================================ */

#include "Header.h"

/**
 * 枚举内存设备：/proc/meminfo 是全局一份，所以固定一台。
 *
 * 名字这一版给 NAME：/proc/meminfo 只有容量与用量，没有型号或厂商字段，
 * 硬编造一个商品名反而误导；等以后有更准的来源再补。
 */
int enumerate_memory_devices(TmDeviceRef *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    memset(out, 0, sizeof(TmDeviceRef) * (size_t) capacity);

    snprintf(out[0].id, sizeof(out[0].id), "memory0");
    snprintf(out[0].path, sizeof(out[0].path), "/proc/meminfo");

    out[0].name[0] = '\0';

    ensure_device_name(out[0].name, (int) sizeof(out[0].name));

    return 1;
}

// ============================================================
// 内存设备：全部走 Linux 通用接口
//
// 分工写在最前面，免得后来的人又去加 Android 私有接口重复采：
//   统计量（总量 / 可用 / 使用中 / 已提交 / 已缓存 / 内核可回收…）只读 /proc/meminfo，
//     Android 的 ActivityManager 给的也是同一份内核数据，再采一遍纯属浪费；
//   压缩量读 /sys/block/zram*\/mm_stat 的 mem_used_total（多设备必须遍历，不能写死 zram0）；
//   规格读 Device Tree 的 ddr_type 属性（高通把这套枚举放在 dt 里）。
//
// Linux 通用接口给不出的（标称容量、插槽数、外观规格、为硬件保留、速度）这里一律不发，
// 由上层按缺省显示，等 Android 侧补 —— 不拿相似指标冒充真数据。
// ============================================================

/**
 * 从 /proc/meminfo 的文本里取一项，换算成字节。
 *
 * 每行形如 `MemTotal:        3908 kB`：键必须从行首开始整段匹配并紧跟冒号，
 * 所以 "Cached" 不会误配到 "SwapCached"。带 kB 单位的乘 1024，不带的按个数返回。
 *
 * @param text meminfo 全文
 * @param key  行首键名，如 MemTotal / Inactive(file)
 * @return 字节数；-1 = 没有这一项，或这一行的值不是数字
 */
long long meminfo_bytes(const char *text, const char *key)
{
    if (text == NULL || key == NULL || key[0] == '\0') {
        return -1;
    }

    size_t key_length = strlen(key);

    const char *cursor = text;

    while (cursor != NULL && cursor[0] != '\0') {

        if (strncmp(cursor, key, key_length) == 0 && cursor[key_length] == ':') {

            const char *number = cursor + key_length + 1;

            while (number[0] == ' ' || number[0] == '\t') {
                number++;
            }

            char *end = NULL;

            long long value = (long long) strtoll(number, &end, 10);

            if (end == number || value < 0) {
                return -1;
            }

            while (end[0] == ' ' || end[0] == '\t') {
                end++;
            }

            if (end[0] == 'k' && end[1] == 'B') {
                return value * 1024LL;
            }

            return value;
        }

        const char *newline = strchr(cursor, '\n');

        if (newline == NULL) {
            break;
        }

        cursor = newline + 1;
    }

    return -1;
}

/**
 * 读一块 zram 的 mm_stat 与 disksize。
 *
 * mm_stat 九列的顺序（内核文档固定）：
 *   orig_data_size compr_data_size mem_used_total mem_limit mem_used_max
 *   same_pages pages_compacted huge_pages huge_pages_since
 * 只用前三列，各有各的含义，不能互相顶替：
 *   1) 压缩前的数据量
 *   2) 压缩后的数据量
 *   3) 实际占住的物理内存（含分配器碎片与元数据）
 *
 * @return true = mm_stat 读到了；false = 这块读不到（权限 / 格式不对）
 */
static bool zram_read_one(const char *base_dir, const char *name, TmZramDirScan *scan)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/%s/mm_stat", base_dir, name);

    char text[256];

    long long orig = 0;
    long long compr = 0;
    long long used = 0;

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    if (sscanf(text, "%lld %lld %lld", &orig, &compr, &used) != 3) {
        return false;
    }

    scan->original += orig;
    scan->compressed += compr;

    if (used > 0) {
        // 多块 zram 相加；0 说明这块还没吃内存，加不加都不影响总和
        scan->used += used;
    }

    snprintf(path, sizeof(path), "%s/%s/disksize", base_dir, name);

    double size = 0.0;

    if (read_first_number(path, &size) && size > 0.0) {
        scan->disksize += (long long) size;
        scan->disksize_read = true;
    }

    return true;
}

/**
 * 扫一个目录（/sys/block 或 /sys/class/block）里的 zram 设备。
 *
 * 两步：先枚举；列不了目录（SELinux 只给 search 不给 readdir）就按 zram0..zram3
 * 固定编号 stat + 读 mm_stat —— 块设备编号连续，单块读得动就够，不该因为
 * 列不了目录就判"这台机器没开压缩内存"。
 */
static void zram_scan_dir(const char *base_dir, TmZramDirScan *scan)
{
    memset(scan, 0, sizeof(*scan));

    DIR *dir = opendir(base_dir);

    if (dir != NULL) {

        scan->dir_listed = true;

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "zram", 4) != 0) {
                continue;
            }

            scan->device_seen = true;

            if (zram_read_one(base_dir, entry->d_name, scan)) {
                scan->value_read = true;
            }
        }

        closedir(dir);

        return;
    }

    for (int index = 0; index < 4; index++) {

        char name[16];

        snprintf(name, sizeof(name), "zram%d", index);

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "%s/%s", base_dir, name);

        struct stat info;

        if (stat(path, &info) != 0) {
            continue;
        }

        scan->device_seen = true;

        if (zram_read_one(base_dir, name, scan)) {
            scan->value_read = true;
        }
    }
}

/**
 * 从 /proc/swaps 里取 zram 设备已用掉的交换量（字节）。
 *
 * 这一行是**压缩前**的数据量，只用来回答"zram 是不是正在当交换用"，
 * 不能当成压缩后占用，也不能除以某个压缩比反推内存 —— 它和 mem_used_total
 * 本来就不是同一个口径（same_pages 之类会让两者对不上）。
 *
 * @return 字节数；-1 = 没有 zram 交换，或这个文件读不到
 */
static long long read_zram_swap_used_bytes(void)
{
    // 多块 zram 也只有几行，2 KB 足够
    static char text[2048];

    if (read_text_file("/proc/swaps", text, (int) sizeof(text)) <= 0) {
        return -1;
    }

    long long total = 0;
    int found = 0;

    char *cursor = text;

    while (cursor != NULL && cursor[0] != '\0') {

        char *newline = strchr(cursor, '\n');

        if (newline != NULL) {
            newline[0] = '\0';
        }

        // 表头是 Filename Type Size Used Priority，不含 zram，会被这一条挡掉
        if (strstr(cursor, "zram") != NULL) {

            char name[64];
            char type[32];
            long long size_kb = 0;
            long long used_kb = 0;

            if (sscanf(cursor, "%63s %31s %lld %lld", name, type, &size_kb, &used_kb) == 4 &&
                used_kb > 0) {

                total += used_kb * 1024LL;
                found = 1;
            }
        }

        if (newline == NULL) {
            break;
        }

        cursor = newline + 1;
    }

    return found ? total : -1;
}

/**
 * 交换空间：/proc/swaps 的 Size / Used 两列，所有 swap 设备一起加（不只 zram）。
 *
 * Size 是设备配置容量（zram 的 disksize、磁盘 swap 分区的分区大小），Used 是
 * 已用交换量（压缩前口径，别拿它当内存占用）。表头那一行 sscanf 不认数字，
 * 自然被挡掉。
 *
 * @return 0 = 读到了（没有设备行时 total / used 都是 0，属于"没配交换"）；
 *         -1 = 文件读不到
 */
static int read_swap_table_bytes(long long *total_out, long long *used_out)
{
    static char text[2048];

    if (read_text_file("/proc/swaps", text, (int) sizeof(text)) <= 0) {
        return -1;
    }

    long long total = 0;
    long long used = 0;

    char *cursor = text;

    while (cursor != NULL && cursor[0] != '\0') {

        char *newline = strchr(cursor, '\n');

        if (newline != NULL) {
            newline[0] = '\0';
        }

        char name[64];
        char type[32];
        long long size_kb = 0;
        long long used_kb = 0;

        if (sscanf(cursor, "%63s %31s %lld %lld", name, type, &size_kb, &used_kb) == 4) {

            total += size_kb * 1024LL;
            used += used_kb * 1024LL;
        }

        if (newline == NULL) {
            break;
        }

        cursor = newline + 1;
    }

    *total_out = total;
    *used_out = used;

    return 0;
}

/**
 * 压缩内存：所有 zram 设备实际占住的物理内存（mm_stat 第 3 列 mem_used_total）。
 *
 * 来源只有两条，都是同一个 inode 的两条路径：
 *   1) /sys/block/zramN/mm_stat
 *   2) /sys/class/block/zramN/mm_stat
 * 两处是同一批设备，**只取其一**累加，绝不两条路径相加（会翻倍）。
 *
 * 刻意不做的两件事：
 *   - 不拿 disksize 当已压缩内存（那是容量上限，不是用量）；
 *   - 不拿 /proc/swaps 的 Used 除以假定压缩比。压缩比随内容在 1:1..4:1 之间浮动，
 *     mem_used_total 还含分配器碎片与元数据，"原始量 / 3"只是猜的，
 *     看着像精确值最误导。读不到就如实报读不到。
 *
 * 状态：
 *   NONE        两处都确认没有 zram（压缩内存 0 是有效结论）
 *   UNREADABLE  看到设备但数值读不到（多半是 SELinux）
 *   UNKNOWN     目录本身都列不了，连有没有都不知道
 */
static void read_zram_info(TmZramInfo *info)
{
    memset(info, 0, sizeof(*info));

    TmZramDirScan block_scan;
    TmZramDirScan class_scan;

    zram_scan_dir("/sys/block", &block_scan);
    zram_scan_dir("/sys/class/block", &class_scan);

    const TmZramDirScan *source = NULL;

    if (block_scan.value_read) {
        source = &block_scan;
    } else if (class_scan.value_read) {
        source = &class_scan;
    }

    if (source != NULL) {

        info->state = TM_ZRAM_OK;
        info->original_bytes = source->original;
        info->compressed_bytes = source->compressed;
        info->used_bytes = source->used;
        info->disksize_bytes = source->disksize;
        info->disksize_known = source->disksize_read;

        return;
    }

    if (block_scan.device_seen || class_scan.device_seen) {
        info->state = TM_ZRAM_UNREADABLE;
        return;
    }

    if (block_scan.dir_listed || class_scan.dir_listed) {
        info->state = TM_ZRAM_NONE;
        return;
    }

    info->state = TM_ZRAM_UNKNOWN;
}

/** 高通内核头文件里 ddr_type 的枚举顺序，值即下标的含义与厂商一致。 */
static const char *const k_dram_type_names[] = {
    NULL,           // 0 没有 DDR，按读不到处理
    "LPDDR1",
    "LPDDR2",
    "DDR2",
    "DDR3",
    "LPDDR3",
    "LPDDR4",
    "LPDDR4X",
    "LPDDR5",
    "LPDDR5X"
};

/**
 * 判断一段 dt 属性是不是"纯文本"，返回可用的文本长度。
 *
 * NUL 必须算成"不是文本"。原因：高通的 ddr_device_type 是 big-endian u32，
 * 形如 00 00 00 08 —— 前三个字节全是 0。早先这里把 0 当可打印字符放过，
 * 于是一路走进字符串分支，strstr 又在第一个 NUL 就停住，永远匹配不到
 * "LPDDR5X" / "LPDDR5"，规格这一项就被整项丢掉（界面上就是"没了"）。
 *
 * 末尾的 NUL 先剥掉再判断：字符串形态的属性是 "LPDDR5\0" 这种以 NUL 收尾的，
 * 不剥就会被误判成二进制。
 *
 * @return 文本长度（>0 才是文本）；0 = 不是文本，按二进制解释
 */
static int dram_text_length(const char *raw, int length)
{
    if (raw == NULL || length <= 0) {
        return 0;
    }

    int trimmed = length;

    while (trimmed > 0 && raw[trimmed - 1] == '\0') {
        trimmed--;
    }

    if (trimmed <= 0) {
        return 0;
    }

    for (int i = 0; i < trimmed; i++) {

        unsigned char ch = (unsigned char) raw[i];

        if (ch < 0x20 || ch > 0x7e) {
            return 0;
        }
    }

    return trimmed;
}

/**
 * 把一段 dt 属性解释成内存规格名。
 *
 * 属性可能是三种形态：
 *   纯文本（compatible 之类的字符串表）—— 转大写后按关键字认，长的先认，
 *     否则 LPDDR5X 会被 LPDDR5 抢先匹配；
 *   文本数字（有些内核把枚举导成 "8"）—— 当枚举值查表；
 *   二进制（高通 ddr_type，big-endian 的 u32）—— 小枚举值只有最后一个字节非零，
 *     取它当下标查表。
 *
 * @return true = 认出来了，out 里是规格名；false = 认不出，调用方不要发这个字段
 */
static bool dram_spec_from_property(const char *raw, int length, char *out, int capacity)
{
    if (raw == NULL || length <= 0 || out == NULL || capacity <= 0) {
        return false;
    }

    int text_length = dram_text_length(raw, length);

    if (text_length > 0) {

        char upper[64];

        int count = text_length < (int) sizeof(upper) - 1 ? text_length : (int) sizeof(upper) - 1;

        for (int i = 0; i < count; i++) {
            upper[i] = (char) toupper((unsigned char) raw[i]);
        }

        upper[count] = '\0';

        static const char *const k_names[] = {
            "LPDDR5X", "LPDDR5", "LPDDR4X", "LPDDR4", "LPDDR3", "LPDDR2", "LPDDR1",
            "DDR4", "DDR3", "DDR2"
        };

        for (int i = 0; i < (int) (sizeof(k_names) / sizeof(k_names[0])); i++) {

            if (strstr(upper, k_names[i]) != NULL) {
                snprintf(out, (size_t) capacity, "%s", k_names[i]);
                return true;
            }
        }

        // 文本形态的枚举值："8" / "0x08" 都认，和二进制分支走同一张表
        char *end = NULL;

        long code = strtol(upper, &end, 0);

        if (end != NULL && *end == '\0') {

            if (code > 0 && code < TM_DRAM_TYPE_COUNT && k_dram_type_names[code] != NULL) {
                snprintf(out, (size_t) capacity, "%s", k_dram_type_names[code]);
                return true;
            }
        }

        return false;
    }

    int value = (int) ((unsigned char) raw[length - 1]);

    if (value > 0 && value < TM_DRAM_TYPE_COUNT && k_dram_type_names[value] != NULL) {
        snprintf(out, (size_t) capacity, "%s", k_dram_type_names[value]);
        return true;
    }

    return false;
}

/**
 * 读一个 Device Tree 属性当 big-endian 的无符号整数。
 *
 * dt 的标量属性固定 4 字节、大端存放，所以 00 00 00 08 就是 8。
 * 只有 4 字节以内才当标量：再长说明这个属性是数组或字符串表，
 * 硬解出来的第一个数没有意义。
 *
 * @return 数值；-1 = 读不到，或长度不像标量
 */
static long long read_dt_u32(const char *path)
{
    unsigned char raw[8];

    int length = read_text_file(path, (char *) raw, (int) sizeof(raw));

    if (length <= 0 || length > 4) {
        return -1;
    }

    long long value = 0;

    for (int i = 0; i < length; i++) {
        value = (value << 8) | (long long) raw[i];
    }

    return value;
}

/**
 * 在 dt 根目录下的 memory* 节点里找某个标量属性（ddr_size 用）。
 *
 * qcom 的内存节点名常带地址后缀（memory@80000000），固定路径读不到时
 * 就靠这一步把容量捞回来 —— 否则名称行只能退到 MemTotal，和商品标称对不上。
 *
 * @return 数值；-1 = 没找到
 */
static long long read_dt_u32_in_memory_nodes(const char *root, const char *property)
{
    if (root == NULL || property == NULL) {
        return -1;
    }

    DIR *dir = opendir(root);

    if (dir == NULL) {
        return -1;
    }

    static char path[160];

    long long value = -1;

    struct dirent *entry = NULL;

    while (value < 0 && (entry = readdir(dir)) != NULL) {

        const char *name = entry->d_name;

        if (name == NULL || strncmp(name, "memory", 6) != 0) {
            continue;
        }

        snprintf(path, sizeof(path), "%s/%s/%s", root, name, property);

        value = read_dt_u32(path);
    }

    closedir(dir);

    return value;
}

/**
 * 内存规格：高通的 ddr_device_type 属性就是这套枚举，值即下标。
 *
 * 除了高通自己的位置，另外几个通用路径也顺手试一下；单个路径读不到时
 * 再扫一遍 memory 节点里的属性名、最后退到 getprop —— 三手准备都在
 * [read_dram_spec_once] 里，读到的结果按"不会变"缓存。
 */
static bool read_dram_spec(char *out, int capacity);

/** 内存颗粒厂商：dt 的 ddr_manufacturer_id 换成英文厂商码；同样带缓存与多路兜底。 */
static bool read_dram_manufacturer(char *out, int capacity);

/** 厂商属性解释函数在下面才定义，这里先声明：目录扫描要跨过定义顺序调用它。 */
static bool dram_manufacturer_from_property(const char *raw, int length, char *out, int capacity);

/**
 * 扫一个 dt 目录，把名字符合规则的属性逐个解释一遍。
 *
 * 为什么要扫目录：不同厂商 / 不同内核把同一个枚举挂在不同的属性名下
 * （ddr_type、ddr_device_type、qcom,ddr-type…），写死路径总有漏的；
 * 目录名本身是稳定的，按关键字捞一遍更耐用。
 *
 * @param manufacturer true = 找厂商属性（名字含 manufactur）；
 *                     false = 找规格属性（名字含 ddr 且含 type，且不是厂商属性）
 * @return true = 有一个属性解释成功，out 里是结果
 */
static bool read_dram_dir_property(const char *directory, bool manufacturer,
                                   char *out, int capacity)
{
    if (directory == NULL || out == NULL || capacity <= 0) {
        return false;
    }

    DIR *dir = opendir(directory);

    if (dir == NULL) {
        return false;
    }

    static char property[64];

    bool found = false;

    struct dirent *entry = NULL;

    while (!found && (entry = readdir(dir)) != NULL) {

        const char *name = entry->d_name;

        if (name == NULL || name[0] == '.') {
            continue;
        }

        if (manufacturer) {

            if (strstr(name, "manufactur") == NULL) {
                continue;
            }

        } else {

            // 规格属性：名字里要有 ddr/dram 之一、还要有 type，且不能是厂商属性
            // （ddr_manufacturer_id 的最后一个字节是厂商码，当成枚举查表会得出错规格）
            // 带 dram 这一支是给联发科留的：MTK 的 dt / 内核节点更常用 dram（dram_type）。
            if ((strstr(name, "ddr") == NULL && strstr(name, "dram") == NULL)
                || strstr(name, "type") == NULL
                || strstr(name, "manufactur") != NULL) {
                continue;
            }
        }

        snprintf(property, sizeof(property), "%s/%s", directory, name);

        static char raw[64];

        int length = read_text_file(property, raw, (int) sizeof(raw));

        if (length <= 0) {
            continue;
        }

        found = manufacturer
                ? dram_manufacturer_from_property(raw, length, out, capacity)
                : dram_spec_from_property(raw, length, out, capacity);
    }

    closedir(dir);

    return found;
}

/**
 * 在 dt 根目录下找所有 memory* 节点，逐个扫一遍。
 *
 * 为什么要这一步：qcom 的 dt 里内存节点常常是 `memory@80000000` 这种带地址后缀的
 * 名字，`/proc/device-tree/memory` 这个固定路径根本不存在 —— 只按固定路径找，
 * 规格 / 厂商就会永远读不到（界面上看到的正是"这两项没了"）。
 */
static bool read_dram_memory_nodes(const char *root, bool manufacturer,
                                   char *out, int capacity)
{
    if (root == NULL || out == NULL || capacity <= 0) {
        return false;
    }

    DIR *dir = opendir(root);

    if (dir == NULL) {
        return false;
    }

    static char node[128];

    bool found = false;

    struct dirent *entry = NULL;

    while (!found && (entry = readdir(dir)) != NULL) {

        const char *name = entry->d_name;

        if (name == NULL || strncmp(name, "memory", 6) != 0) {
            continue;
        }

        snprintf(node, sizeof(node), "%s/%s", root, name);

        found = read_dram_dir_property(node, manufacturer, out, capacity);
    }

    closedir(dir);

    return found;
}

/**
 * 最后一手：从 Android 属性里找。
 *
 * 有些内核不导出 dt 节点（或 SELinux 不放行），但会把同一个枚举抄进
 * ro.boot.* 属性里。getprop 输出形如 `[ro.boot.ddr_type]: [8]`，
 * 只认键名里带 ddr 的行；规格行要求键名带 type，避免把 ddr_size 当成规格。
 *
 * 这一步每个进程只跑一次（结果有缓存），启动时多一次 popen 可以接受。
 */
static bool read_dram_from_getprop(bool manufacturer, char *out, int capacity)
{
    FILE *pipe = popen("getprop 2>/dev/null | grep -i ddr", "r");

    if (pipe == NULL) {
        return false;
    }

    static char line[512];

    bool found = false;

    while (!found && fgets(line, (int) sizeof(line), pipe) != NULL) {

        const char *key_end = strchr(line, ':');

        if (key_end == NULL) {
            continue;
        }

        if (manufacturer) {

            if (strstr(line, "manufactur") == NULL) {
                continue;
            }

        } else {

            if (strstr(line, "type") == NULL || strstr(line, "manufactur") != NULL) {
                continue;
            }
        }

        const char *open = strchr(key_end, '[');
        const char *close = (open != NULL) ? strchr(open, ']') : NULL;

        if (open == NULL || close == NULL || close <= open + 1) {
            continue;
        }

        const char *value = open + 1;
        int length = (int) (close - value);

        found = manufacturer
                ? dram_manufacturer_from_property(value, length, out, capacity)
                : dram_spec_from_property(value, length, out, capacity);
    }

    pclose(pipe);

    return found;
}

/**
 * 联发科一类平台的兜底来源：没有 ddr_device_type 属性，但可能把同一件事写在
 * dram 专属的 proc / debugfs 节点里，或者抄进 chosen/bootargs、内核命令行。
 *
 * 前四条是 MTK dramc 驱动常见的位置（名称各家内核略有出入，存在才读）；
 * 后两条是"启动参数里带 dram 型号"的情形。全部只读文本、只认关键词，
 * 认不出来就整项不发 —— 不猜。
 */
static const char *const k_dram_text_paths[] = {
    "/proc/dram_info",
    "/proc/mtk_dram_info",
    "/proc/dramc",
    "/sys/kernel/debug/dramc/dram_info",
    "/sys/kernel/debug/dramc/dram_type",
    "/sys/kernel/debug/dramc/dramc_dvfs",
    "/proc/device-tree/chosen/bootargs",
    "/proc/cmdline"
};

/** 太长的文本不读（bootargs / dram_info 都在这个量级以内，读多了纯浪费）。 */
#define TM_DRAM_TEXT_MAX 8192

/**
 * 从一段文本里认内存规格：只按关键词认，**不解释数字**。
 *
 * 为什么不复用 [dram_spec_from_property]：那个函数会把数字当"高通枚举下标"查表，
 * 联发科的枚举顺序和高通不一样，硬解出来的数字会标错型号。这里只接受
 * 文本里明确写着 LPDDR5X / LPDDR4X 这类型号名的情况。
 *
 * @return true = 认出来了
 */
static bool dram_spec_from_text(const char *text, char *out, int capacity)
{
    if (text == NULL || out == NULL || capacity <= 0) {
        return false;
    }

    static char upper[TM_DRAM_TEXT_MAX];

    size_t length = strlen(text);

    if (length > sizeof(upper) - 1) {
        length = sizeof(upper) - 1;
    }

    for (size_t i = 0; i < length; i++) {
        upper[i] = (char) toupper((unsigned char) text[i]);
    }

    upper[length] = '\0';

    // 长的先认，否则 LPDDR5X 会被 LPDDR5 抢先匹配
    static const char *const k_names[] = {
        "LPDDR5X", "LPDDR5T", "LPDDR5", "LPDDR4X", "LPDDR4", "LPDDR3", "LPDDR2", "LPDDR1",
        "DDR4", "DDR3", "DDR2"
    };

    for (int i = 0; i < (int) (sizeof(k_names) / sizeof(k_names[0])); i++) {

        if (strstr(upper, k_names[i]) != NULL) {
            snprintf(out, (size_t) capacity, "%s", k_names[i]);
            return true;
        }
    }

    return false;
}

/**
 * 从一段**dram 专属**文本里认颗粒厂商。
 *
 * 只在 dram 专属节点上做这件事：厂商名（SAMSUNG / MICRON …）是普通英文单词，
 * 放到 /proc/cmdline 这种大杂烩里去搜，容易搜到无关的命中，那比不显示更糟。
 *
 * @return true = 认出来了（输出英文厂商码，客户端再映射中文名）
 */
static bool dram_vendor_from_text(const char *text, char *out, int capacity)
{
    if (text == NULL || out == NULL || capacity <= 0) {
        return false;
    }

    static char upper[TM_DRAM_TEXT_MAX];

    size_t length = strlen(text);

    if (length > sizeof(upper) - 1) {
        length = sizeof(upper) - 1;
    }

    for (size_t i = 0; i < length; i++) {
        upper[i] = (char) toupper((unsigned char) text[i]);
    }

    upper[length] = '\0';

    static const struct {
        const char *keyword;
        const char *code;

    } k_vendors[] = {
        { "SAMSUNG", "SAMSUNG" },
        { "SKHYNIX", "HYNIX" },     // 文本里常写 SK hynix / SK Hynix
        { "HYNIX", "HYNIX" },
        { "MICRON", "MICRON" },
        { "NANYA", "NANYA" },
        { "WINBOND", "WINBOND" },
        { "ELPIDA", "ELPIDA" },
        { "QIMONDA", "QIMONDA" },
        { "ESMT", "ESMT" },
        { "ETRON", "ETRON" }
    };

    for (int i = 0; i < (int) (sizeof(k_vendors) / sizeof(k_vendors[0])); i++) {

        if (strstr(upper, k_vendors[i].keyword) != NULL) {
            snprintf(out, (size_t) capacity, "%s", k_vendors[i].code);
            return true;
        }
    }

    return false;
}

/**
 * 扫一遍 [k_dram_text_paths]：规格认关键词，厂商只在 dram 专属节点里认
 * （排除 chosen/bootargs 与 /proc/cmdline 两条大杂烩）。
 */
static bool read_dram_from_text_sources(bool manufacturer, char *out, int capacity)
{
    static char text[TM_DRAM_TEXT_MAX + 1];

    int path_count = (int) (sizeof(k_dram_text_paths) / sizeof(k_dram_text_paths[0]));

    // 厂商只认前几条 dram 专属节点
    int limit = manufacturer ? 6 : path_count;

    for (int i = 0; i < limit; i++) {

        int length = read_text_file(k_dram_text_paths[i], text, (int) sizeof(text));

        if (length <= 0) {
            continue;
        }

        bool found = manufacturer
                     ? dram_vendor_from_text(text, out, capacity)
                     : dram_spec_from_text(text, out, capacity);

        if (found) {
            return true;
        }
    }

    return false;
}

/**
 * 真正读一次规格（结果见 [read_dram_spec] 的缓存）。
 *
 * 顺序：已知 dt 路径 → memory 节点目录扫描 → 文本来源（联发科一类的 dram 节点 /
 * 启动参数）→ getprop。
 * 读不到就整项不发，页面那边名称行只写容量，不摆"未知规格"占位。
 */
static bool read_dram_spec_once(char *out, int capacity)
{
    static const char *const k_paths[] = {
        "/proc/device-tree/memory/ddr_device_type",
        "/proc/device-tree/memory/ddr_type",
        "/proc/device-tree/qcom,ddr-type",
        "/proc/device-tree/qcom,ddr_type",
        "/proc/device-tree/qcom,ddr_device_type",
        "/proc/device-tree/ddr-type",
        "/proc/device-tree/ddr_type",
        "/proc/device-tree/dram/ddr_type",
        "/sys/firmware/devicetree/base/memory/ddr_device_type",
        "/sys/firmware/devicetree/base/memory/ddr_type",
        "/sys/firmware/devicetree/base/qcom,ddr-type",
        "/sys/firmware/devicetree/base/ddr_type"
    };

    static char raw[64];

    for (int i = 0; i < (int) (sizeof(k_paths) / sizeof(k_paths[0])); i++) {

        int length = read_text_file(k_paths[i], raw, (int) sizeof(raw));

        if (length <= 0) {
            continue;
        }

        // 只用到 0..length-1：read_text_file 会在末尾补一个 0，不能算进属性内容
        if (dram_spec_from_property(raw, length, out, capacity)) {
            return true;
        }
    }

    return read_dram_dir_property("/proc/device-tree/memory", false, out, capacity)
           || read_dram_dir_property("/sys/firmware/devicetree/base/memory", false, out, capacity)
           || read_dram_memory_nodes("/proc/device-tree", false, out, capacity)
           || read_dram_memory_nodes("/sys/firmware/devicetree/base", false, out, capacity)
           || read_dram_dir_property("/proc/device-tree", false, out, capacity)
           || read_dram_from_text_sources(false, out, capacity)
           || read_dram_from_getprop(false, out, capacity);
}

/**
 * 内存规格（带缓存）。
 *
 * ddr_device_type / 颗粒型号在开机后不会变，而设备快照是每秒一份 ——
 * 不缓存的话每秒都要扫目录 + 试十几条路径，纯浪费。
 */
static bool read_dram_spec(char *out, int capacity)
{
    static bool resolved = false;
    static bool found = false;
    static char cached[32];

    if (!resolved) {

        resolved = true;

        found = read_dram_spec_once(cached, (int) sizeof(cached));
    }

    if (found && out != NULL && capacity > 0) {
        snprintf(out, (size_t) capacity, "%s", cached);
    }

    return found;
}

/**
 * 内存颗粒厂商代码表：dt 的 ddr_manufacturer_id 就是这套编码（0x01..0xFF，编号是稀疏的）。
 *
 * 编码来自 JEDEC 的 DRAM 厂商编号，和高通内核头文件里的枚举一致；
 * 表里没有的编号（预留 / 新型号）一律返回 NULL —— 上层据此整项不发，
 * 页面连"厂商"这一行都不画，不写"未知厂商"占位。
 *
 * 返回值是**英文厂商码**（SAMSUNG / MICRON …），不是给人看的名字：
 * 协议里不放本地语言，中文名（"三星（Samsung）"这类）由客户端按码映射。
 * 码的拼写取国际通用品牌名，全大写、无空格。
 */
static const char *dram_manufacturer_code(int code)
{
    switch (code) {

        case 0x01: return "SAMSUNG";

        case 0x02: return "QIMONDA";

        case 0x03: return "ELPIDA";

        case 0x04: return "ETRON";

        case 0x05: return "NANYA";

        case 0x06: return "HYNIX";

        case 0x07: return "MOSEL";

        case 0x08: return "WINBOND";

        case 0x09: return "ESMT";

        case 0x0B: return "SPANSION";

        case 0x0C: return "SST";

        case 0x0D: return "ZMOS";

        case 0x0E: return "INTEL";

        case 0xFE: return "NUMONYX";

        case 0xFF: return "MICRON";

        default: return NULL;
    }
}

/**
 * 把一段 dt 属性解释成厂商代码，再换成人看的名字。
 *
 * 属性有两种形态（和 ddr_type 那套是同一个道理）：
 *   二进制 —— u8（一个字节 0x01），或 big-endian 的 u32（00 00 00 01），
 *             两种形态的编码值都在最后一个字节里，取它即可；
 *   纯文本 —— 有些内核把 dt 属性导出成文本（"0x01" / "1"），按整数解析。
 *
 * @return true = 认出来了，out 里是厂商名；false = 读不到 / 编码不在表里
 */
static bool dram_manufacturer_from_property(const char *raw, int length, char *out, int capacity)
{
    if (raw == NULL || length <= 0 || out == NULL || capacity <= 0) {
        return false;
    }

    int text_length = dram_text_length(raw, length);

    int code = -1;

    if (text_length > 0) {

        char text[16];

        int count = text_length < (int) sizeof(text) - 1 ? text_length : (int) sizeof(text) - 1;

        memcpy(text, raw, (size_t) count);

        text[count] = '\0';

        trim_disk_text(text);

        code = (int) strtol(text, NULL, 0);

    } else {

        // u8 与 big-endian u32 的编码值都落在最后一个字节
        code = (int) ((unsigned char) raw[length - 1]);
    }

    const char *code_text = dram_manufacturer_code(code);

    if (code_text == NULL) {
        return false;
    }

    snprintf(out, (size_t) capacity, "%s", code_text);

    return true;
}

/**
 * 真正读一次厂商（结果见 [read_dram_manufacturer] 的缓存）。
 *
 * 顺序：已知 dt 路径（含不带 _id 的别名）→ memory 节点目录扫描 → getprop。
 * 都读不到就整项不发：页面那边"厂商"这一行跟着消失，不摆"未知"占位。
 */
static bool read_dram_manufacturer_once(char *out, int capacity)
{
    static const char *const k_paths[] = {
        "/proc/device-tree/memory/ddr_manufacturer_id",
        "/proc/device-tree/memory/ddr_manufacturer",
        "/proc/device-tree/qcom,ddr-manufacturer-id",
        "/proc/device-tree/qcom,ddr_manufacturer_id",
        "/sys/firmware/devicetree/base/memory/ddr_manufacturer_id",
        "/sys/firmware/devicetree/base/memory/ddr_manufacturer",
        "/sys/firmware/devicetree/base/qcom,ddr-manufacturer-id"
    };

    static char raw[16];

    for (int i = 0; i < (int) (sizeof(k_paths) / sizeof(k_paths[0])); i++) {

        int length = read_text_file(k_paths[i], raw, (int) sizeof(raw));

        if (length <= 0) {
            continue;
        }

        if (dram_manufacturer_from_property(raw, length, out, capacity)) {
            return true;
        }
    }

    return read_dram_dir_property("/proc/device-tree/memory", true, out, capacity)
           || read_dram_dir_property("/sys/firmware/devicetree/base/memory", true, out, capacity)
           || read_dram_memory_nodes("/proc/device-tree", true, out, capacity)
           || read_dram_memory_nodes("/sys/firmware/devicetree/base", true, out, capacity)
           || read_dram_dir_property("/proc/device-tree", true, out, capacity)
           || read_dram_from_text_sources(true, out, capacity)
           || read_dram_from_getprop(true, out, capacity);
}

/**
 * 内存颗粒厂商（带缓存）。
 *
 * 颗粒型号在开机后不会变，设备快照却每秒一份：不缓存就要每秒试七条路径
 * 再扫两个目录，纯浪费。缓存里存的是英文码（SAMSUNG / MICRON …），
 * 中文名由客户端按码映射。
 */
static bool read_dram_manufacturer(char *out, int capacity)
{
    static bool resolved = false;
    static bool found = false;
    static char cached[64];

    if (!resolved) {

        resolved = true;

        found = read_dram_manufacturer_once(cached, (int) sizeof(cached));
    }

    if (found && out != NULL && capacity > 0) {
        snprintf(out, (size_t) capacity, "%s", cached);
    }

    return found;
}

/**
 * 标称容量：高通 dt 的 ddr_size，单位是 GB（8 就是 8 GB）。
 *
 * 为什么不用 MemTotal：那是内核能用的内存，已经被保留区啃掉一截，
 * 拿它当商品标称容量会少一档（8 GB 的机器显示 7.4 GB）。
 *
 * 两个挂载点都试：/proc/device-tree 与 /sys/firmware/devicetree/base
 * 在裁剪过的系统上可能只有一个能读。
 *
 * @return 字节数；-1 = 读不到或值明显不对（0、超过 4 TB）
 */
static long long read_dram_size_bytes(void)
{
    static const char *const k_paths[] = {
        "/proc/device-tree/memory/ddr_size",
        "/sys/firmware/devicetree/base/memory/ddr_size"
    };

    long long gigabytes = -1;

    for (int i = 0; i < (int) (sizeof(k_paths) / sizeof(k_paths[0])); i++) {

        long long value = read_dt_u32(k_paths[i]);

        if (value > 0) {
            gigabytes = value;
            break;
        }
    }

    if (gigabytes <= 0) {

        // 固定路径都没有：退到 memory@… 这类带地址后缀的节点
        long long from_node = read_dt_u32_in_memory_nodes("/proc/device-tree", "ddr_size");

        if (from_node <= 0) {
            from_node = read_dt_u32_in_memory_nodes("/sys/firmware/devicetree/base", "ddr_size");
        }

        if (from_node > 0) {
            gigabytes = from_node;
        }
    }

    if (gigabytes <= 0 || gigabytes > 4096) {
        return -1;
    }

    return gigabytes * 1024LL * 1024LL * 1024LL;
}

/** 32 位大端单元（设备树 reg 的每个 cell 都是 4 字节大端）。 */
static unsigned long long mem_cell32(const unsigned char *raw)
{
    return ((unsigned long long) raw[0] << 24) |
           ((unsigned long long) raw[1] << 16) |
           ((unsigned long long) raw[2] << 8) |
           (unsigned long long) raw[3];
}

/**
 * 按大端 cell 拼一个 64 位值。
 *
 * arm64 的地址 / 长度单元只可能是 1 或 2；其它取值返回 -1 让调用方跳过这一条 ——
 * 猜着拼出来的地址比不算更糟。
 */
static long long mem_be_value(const unsigned char *raw, int cells)
{
    if (cells == 1) {
        return (long long) mem_cell32(raw);
    }

    if (cells == 2) {
        return (long long) ((mem_cell32(raw) << 32) | mem_cell32(raw + 4));
    }

    return -1;
}

/** 描述列是否正好是 label（后面最多带个 \r），避免 "System RAM Bus" 之类混进来。 */
static bool mem_label_exact(const char *desc, int remaining, const char *label)
{
    int length = (int) strlen(label);

    if (remaining < length) {
        return false;
    }

    if (strncmp(desc, label, (size_t) length) != 0) {
        return false;
    }

    if (remaining == length) {
        return true;
    }

    return remaining == length + 1 && desc[length] == '\r';
}

/**
 * 从 /proc/iomem 收集某一类区间。
 *
 * @param label    描述列要完全匹配的名字（"System RAM" / "reserved"）
 * @param indented true 取缩进的子区域，false 取顶格的父区域
 * @param out      结果数组
 * @param capacity 结果数组容量
 * @return 段数；-1 = 没权限（有权限时也许能拿到）；-2 = 读失败
 */
static int mem_collect_iomem(const char *label, bool indented,
                             TmMemRange *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return -2;
    }

    // iomem 一台机器几十行，16 KB 足够；静态缓冲，别在栈上开大数组
    static char text[16384];

    if (read_text_file("/proc/iomem", text, (int) sizeof(text)) <= 0) {

        // 打不开时先分清"没权限"和"根本不存在"：前者以后也许能拿到
        return (access("/proc/iomem", R_OK) != 0) ? -1 : -2;
    }

    int count = 0;
    const char *line = text;

    while (*line != '\0' && count < capacity) {

        const char *line_end = strchr(line, '\n');
        int length = (line_end != NULL) ? (int) (line_end - line) : (int) strlen(line);

        const char *cursor = line;
        const char *limit = line + length;

        int indent = 0;

        while (cursor < limit && (*cursor == ' ' || *cursor == '\t')) {
            cursor++;
            indent++;
        }

        // 父段与子段只取一类：子段本来就在父段里，重复加会把总量翻倍
        if ((indent > 0) == indented && cursor < limit) {

            unsigned long long start = 0;
            unsigned long long stop = 0;

            if (sscanf(cursor, "%llx-%llx", &start, &stop) == 2) {

                const char *colon = memchr(cursor, ':', (size_t) (limit - cursor));

                if (colon != NULL && stop >= start) {

                    const char *desc = colon + 1;

                    while (desc < limit && *desc == ' ') {
                        desc++;
                    }

                    if (desc < limit &&
                        mem_label_exact(desc, (int) (limit - desc), label)) {

                        out[count].start = (long long) start;
                        out[count].size = (long long) (stop - start) + 1;
                        count++;
                    }
                }
            }
        }

        line = (line_end != NULL) ? line_end + 1 : limit;
    }

    return count;
}

/** 区间并集的总字节数：重叠的设备树节点只算一次。 */
static long long mem_union_size(const TmMemRange *ranges, int count)
{
    if (ranges == NULL || count <= 0) {
        return 0;
    }

    TmMemRange sorted[TM_MEM_RANGE_MAX];
    int limit = (count > TM_MEM_RANGE_MAX) ? TM_MEM_RANGE_MAX : count;

    for (int i = 0; i < limit; i++) {
        sorted[i] = ranges[i];
    }

    // 段数只有几十个，插入排序最省事
    for (int i = 1; i < limit; i++) {

        TmMemRange value = sorted[i];
        int j = i - 1;

        while (j >= 0 && sorted[j].start > value.start) {
            sorted[j + 1] = sorted[j];
            j--;
        }

        sorted[j + 1] = value;
    }

    long long total = 0;
    long long current_start = sorted[0].start;
    long long current_end = sorted[0].start + sorted[0].size - 1;

    for (int i = 1; i < limit; i++) {

        long long start = sorted[i].start;
        long long end = start + sorted[i].size - 1;

        if (start <= current_end + 1) {

            if (end > current_end) {
                current_end = end;
            }
        } else {
            total += current_end - current_start + 1;
            current_start = start;
            current_end = end;
        }
    }

    total += current_end - current_start + 1;

    return total;
}

/**
 * 把一段区间裁到 other 表里，命中的片段追加进 out。
 *
 * 用来把设备树的预留区裁到 /proc/iomem 真正登记成 System RAM 的范围内：
 * 只落在 MMIO 空洞里的预留区不是内存，不该算进来。
 *
 * @return 追加了几段
 */
static int mem_append_clipped(TmMemRange *out, int count, int capacity,
                              const TmMemRange *source,
                              const TmMemRange *other, int other_count)
{
    if (out == NULL || source == NULL || other == NULL) {
        return 0;
    }

    int added = 0;

    long long source_start = source->start;
    long long source_end = source_start + source->size - 1;

    for (int i = 0; i < other_count && count + added < capacity; i++) {

        long long other_end = other[i].start + other[i].size - 1;

        long long start = (source_start > other[i].start) ? source_start : other[i].start;
        long long end = (source_end < other_end) ? source_end : other_end;

        if (start <= end) {
            out[count + added].start = start;
            out[count + added].size = end - start + 1;
            added++;
        }
    }

    return added;
}

/** 读设备树的单元数属性（#address-cells / #size-cells）：固定 4 字节大端。 */
static int mem_dt_cell_count(const char *path, int fallback)
{
    unsigned char raw[4];

    int length = read_text_file(path, (char *) raw, (int) sizeof(raw));

    if (length != 4) {
        return fallback;
    }

    return (int) mem_cell32(raw);
}

/**
 * 读一个节点的 reg 属性，拆成若干 (起始地址, 长度)。
 *
 * reg 的布局是 (地址单元 × address-cells, 长度单元 × size-cells) 重复出现，
 * 每个单元 4 字节大端。单元数不是 1 或 2、总长度凑不成整数条，都算解析失败。
 *
 * @return 条目数；0 = 没有 reg 或格式不认识
 */
static int mem_dt_reg(const char *path, int address_cells, int size_cells,
                      TmMemRange *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    unsigned char raw[256];

    int length = read_text_file(path, (char *) raw, (int) sizeof(raw));
    int entry_bytes = (address_cells + size_cells) * 4;

    if (length <= 0 || entry_bytes <= 0 || (length % entry_bytes) != 0) {
        return 0;
    }

    int count = 0;

    for (int offset = 0; offset < length && count < capacity; offset += entry_bytes) {

        const unsigned char *entry = raw + offset;

        long long start = mem_be_value(entry, address_cells);
        long long size = mem_be_value(entry + address_cells * 4, size_cells);

        if (start < 0 || size <= 0) {
            continue;
        }

        out[count].start = start;
        out[count].size = size;
        count++;
    }

    return count;
}

/**
 * 设备树 reserved-memory 里"不可复用"的预留区，与 System RAM 求交后的并集大小。
 *
 * 只算 no-map / 普通 carveout：带 reusable 属性的（CMA、qdss 之类）允许内核回收，
 * 算进去就不是硬件保留了。动态 size 的节点没有固定 reg，直接跳过。
 *
 * @param ram        /proc/iomem 的 System RAM 区间
 * @param ram_count  区间数
 * @param out_bytes  结果（字节）
 * @return 0 = 成功；-1 = 没权限；-2 = 没有这个目录 / 解析不了
 */
static int mem_dt_reserved_bytes(const TmMemRange *ram, int ram_count,
                                 long long *out_bytes)
{
    if (ram == NULL || ram_count <= 0 || out_bytes == NULL) {
        return -2;
    }

    const char *base = "/sys/firmware/devicetree/base/reserved-memory";

    DIR *dir = opendir(base);

    if (dir == NULL) {
        return (errno == EACCES) ? -1 : -2;
    }

    int address_cells = mem_dt_cell_count(
        "/sys/firmware/devicetree/base/reserved-memory/#address-cells", 2);

    int size_cells = mem_dt_cell_count(
        "/sys/firmware/devicetree/base/reserved-memory/#size-cells", 2);

    if (address_cells <= 0 || address_cells > 2 || size_cells <= 0 || size_cells > 2) {
        closedir(dir);
        return -2;
    }

    TmMemRange hits[TM_MEM_RANGE_MAX];
    int hit_count = 0;

    struct dirent *entry = NULL;

    while ((entry = readdir(dir)) != NULL && hit_count < TM_MEM_RANGE_MAX) {

        if (entry->d_name[0] == '.' || entry->d_name[0] == '#') {
            continue;
        }

        char path[PATH_MAX];

        snprintf(path, sizeof(path), "%s/%s", base, entry->d_name);

        struct stat info;

        if (stat(path, &info) != 0 || !S_ISDIR(info.st_mode)) {
            continue;
        }

        // reusable：内核能在约束满足时回收，不算永久保留
        char flag_path[PATH_MAX];

        snprintf(flag_path, sizeof(flag_path), "%s/reusable", path);

        if (access(flag_path, F_OK) == 0) {
            continue;
        }

        snprintf(flag_path, sizeof(flag_path), "%s/reg", path);

        TmMemRange regions[8];

        int region_count = mem_dt_reg(flag_path, address_cells, size_cells,
                                      regions,
                                      (int) (sizeof(regions) / sizeof(regions[0])));

        for (int i = 0; i < region_count && hit_count < TM_MEM_RANGE_MAX; i++) {

            hit_count += mem_append_clipped(hits, hit_count, TM_MEM_RANGE_MAX,
                                            &regions[i], ram, ram_count);
        }
    }

    closedir(dir);

    *out_bytes = mem_union_size(hits, hit_count);

    return 0;
}

/**
 * 填内存设备。一台机器一份 /proc/meminfo，所以只发第一台实例。
 *
 * 所有容量一律字节（kB 在 meminfo_bytes 里就乘掉了），换算成 GB / MB 是显示层的事。
 * 每一项单独发状态：读不到就发感叹号标记，上层据此显示破折号，
 * 绝不拿 0 顶替（0 是有效值，会和"没读到"混在一起）。
 *
 * 分条图四段（使用中 / 已修改 / 备用 / 可用）在这层的口径：
 *   使用中 = MemTotal - MemAvailable（和统计区同一口径）
 *   已修改 = Dirty + Writeback（Linux 没有和 Windows 一致的 Modified 列表，这是近似）
 *   备用   = Inactive(file)（较少活跃的文件页，和 Windows 的 Standby 不是一一对应）
 *   可用   = MemFree（真正的空闲页）
 * 这四项在 Linux 里存在包含关系，比例由上层归一化后再画。
 *
 * 名称行要的是两个不同的量：
 *   标称容量 = dt 的 ddr_size（这台机器卖的是多少 GB）
 *   实际可用 = MemTotal（内核手上还剩多少，图表顶端就是它）
 * 两者差的就是保留区，不能互相顶替。
 */
int build_memory_devices(TmOutDevice *out, int capacity,
                                const TmDeviceRef *devices, int count)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    device_begin(&out[0], devices[0].id, TM_DEV_TYPE_MEMORY);

    device_put(&out[0], "name", devices[0].name);

    // meminfo 只有一两 KB，但缓冲别放栈上：这条通道是单线程，static 够用
    static char meminfo[4096];

    if (read_text_file("/proc/meminfo", meminfo, (int) sizeof(meminfo)) <= 0) {

        // 连 meminfo 都打不开：只报名称，统计项一个都不发
        return 1;
    }

    long long total = meminfo_bytes(meminfo, "MemTotal");
    long long available = meminfo_bytes(meminfo, "MemAvailable");
    long long free_memory = meminfo_bytes(meminfo, "MemFree");
    long long cached = meminfo_bytes(meminfo, "Cached");
    long long sreclaimable = meminfo_bytes(meminfo, "SReclaimable");
    long long sunreclaim = meminfo_bytes(meminfo, "SUnreclaim");
    long long committed = meminfo_bytes(meminfo, "Committed_AS");
    long long commit_limit = meminfo_bytes(meminfo, "CommitLimit");
    long long dirty = meminfo_bytes(meminfo, "Dirty");
    long long writeback = meminfo_bytes(meminfo, "Writeback");
    long long inactive_file = meminfo_bytes(meminfo, "Inactive(file)");

    // 使用中：总量减可用。不用 MemFree —— 那是"彻底空闲"，会把可回收缓存算成已用
    long long used = -1;

    if (total >= 0 && available >= 0) {

        used = total - available;

        if (used < 0) {
            used = -1;
        }
    }

    long long modified = -1;

    if (dirty >= 0 || writeback >= 0) {
        modified = (dirty >= 0 ? dirty : 0) + (writeback >= 0 ? writeback : 0);
    }

    device_put_int(&out[0], "total_bytes", total, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "available_bytes", available, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "used_bytes", used, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "cached_bytes", cached, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "committed_bytes", committed, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "commit_limit_bytes", commit_limit, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "reclaimable_kernel_bytes", sreclaimable,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "nonreclaimable_kernel_bytes", sunreclaim,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    // 压缩内存在 zram 里，meminfo 的 SwapTotal 只是它的上限，不能用。
    // 下面每个数都来自 mm_stat 的实测列，没有任何一项是折算出来的。
    TmZramInfo zram;

    read_zram_info(&zram);

    const char *zram_status = TM_FIELD_STATUS_NOT_AVAILABLE;

    if (zram.state == TM_ZRAM_UNREADABLE || zram.state == TM_ZRAM_UNKNOWN) {
        // 设备在但读不到 / 连目录都列不了：都是"也许能拿到"，不是"没有"
        zram_status = TM_FIELD_STATUS_ACCESS_DENIED;
    }

    long long zram_used = -1;
    long long zram_original = -1;
    long long zram_compressed = -1;

    if (zram.state == TM_ZRAM_OK) {

        zram_used = zram.used_bytes;
        zram_original = zram.original_bytes;
        zram_compressed = zram.compressed_bytes;

    } else if (zram.state == TM_ZRAM_NONE) {

        // 确认没有 zram：0 是有效结论，不是"读不到"
        zram_used = 0;
        zram_original = 0;
        zram_compressed = 0;
    }

    device_put_int(&out[0], "compressed_bytes", zram_used, zram_status);

    device_put_int(&out[0], "zram_original_bytes", zram_original, zram_status);

    device_put_int(&out[0], "zram_compressed_bytes", zram_compressed, zram_status);

    device_put_int(&out[0], "zram_used_bytes", zram_used, zram_status);

    // 设备容量：disksize 是上限，和用量分开报，谁也不顶替谁
    device_put_int(&out[0], "zram_disksize_bytes",
                   zram.disksize_known ? zram.disksize_bytes : -1,
                   zram.disksize_known ? TM_FIELD_STATUS_NOT_AVAILABLE
                                       : (zram.state == TM_ZRAM_NONE
                                          ? TM_FIELD_STATUS_UNSUPPORTED : zram_status));

    // 交换空间：/proc/swaps 的 Size（设备配置容量）与 Used（已用量），
    // 所有 swap 设备一起加，不只认 zram。Used 是压缩前口径，不能当内存占用；
    // 无设备行时两个都是 0（没配交换，有效结论），读不到才报不可用。
    long long swap_total = -1;
    long long swap_used = -1;

    if (read_swap_table_bytes(&swap_total, &swap_used) != 0) {
        swap_total = -1;
        swap_used = -1;
    }

    device_put_int(&out[0], "swap_total_bytes", swap_total, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "swap_used_bytes", swap_used, TM_FIELD_STATUS_NOT_AVAILABLE);

    // zram 的交换用量单独一格（压缩前口径），与上面的全表交换量分开报
    device_put_int(&out[0], "zram_swap_used_bytes", read_zram_swap_used_bytes(),
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    // 分条图另外三段
    device_put_int(&out[0], "modified_bytes", modified, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "standby_bytes", inactive_file, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "free_bytes", free_memory, TM_FIELD_STATUS_NOT_AVAILABLE);

    // 标称容量与规格都来自高通 Device Tree，读不到就整项不发：
    // 名称行那边会用 MemTotal 兜容量、少写规格，不会空着
    long long nominal = read_dram_size_bytes();

    device_put_int(&out[0], "nominal_bytes", nominal, TM_FIELD_STATUS_NOT_AVAILABLE);

    // 为硬件保留的内存：标称容量与 MemTotal 的差额 —— 内核没有接管的那一截。
    //
    //   标称容量从 dt 来，MemTotal 是内核统计的总页数，两者之差包含固件 /
    //   Hypervisor / 各子系统 carveout / 内核镜像自身与页表。
    //   和 Windows 口径不完全等（那边把内核自身算"在用"），但这是 Android 上
    //   能拿到的最接近的差异量，比拿 0 或者拿 CMA 总量冒充强得多。
    //
    // 数据互相矛盾（标称不超过 MemTotal）时不硬算，报"没有基准"，让上层显示不支持。
    long long hardware_reserved = -1;
    const char *hardware_reserved_status = TM_FIELD_STATUS_NOT_AVAILABLE;

    if (nominal <= 0) {

        // 非高通设备没有 ddr_size：物理容量根本不知道，属于"不支持"
        hardware_reserved_status = TM_FIELD_STATUS_UNSUPPORTED;

    } else if (total <= 0) {

        hardware_reserved_status = TM_FIELD_STATUS_READ_FAILED;

    } else if (nominal <= total) {

        hardware_reserved_status = TM_FIELD_STATUS_NO_BASELINE;

    } else {

        hardware_reserved = nominal - total;
    }

    device_put_int(&out[0], "reserved_bytes", hardware_reserved,
                   hardware_reserved_status);

    // 交叉验证（只上调试页，页面不读这两项）：
    //   system_ram_bytes —— /proc/iomem 里顶格 "System RAM" 的地址总量（并集）
    //   reserved_dt_bytes —— 设备树不可复用预留区与上面这段求交之后的并集
    // 差额口径与 carveout 口径本来就不相等，两份数摆在一起才好核对。
    TmMemRange ram_ranges[TM_MEM_RANGE_MAX];

    int ram_count = mem_collect_iomem("System RAM", false, ram_ranges, TM_MEM_RANGE_MAX);

    long long system_ram = (ram_count > 0) ? mem_union_size(ram_ranges, ram_count) : -1;

    device_put_int(&out[0], "system_ram_bytes", system_ram, TM_FIELD_STATUS_NOT_AVAILABLE);

    long long dt_reserved = -1;
    const char *dt_reserved_status = TM_FIELD_STATUS_UNSUPPORTED;

    if (ram_count > 0) {

        dt_reserved_status = TM_FIELD_STATUS_NOT_AVAILABLE;

        if (mem_dt_reserved_bytes(ram_ranges, ram_count, &dt_reserved) == -1) {
            dt_reserved = -1;
            dt_reserved_status = TM_FIELD_STATUS_ACCESS_DENIED;
        }
    }

    device_put_int(&out[0], "reserved_dt_bytes", dt_reserved, dt_reserved_status);

    char spec[32];

    if (read_dram_spec(spec, (int) sizeof(spec))) {
        device_put(&out[0], "spec", spec);
    }

    // 颗粒厂商：dt 的 ddr_manufacturer_id 换成英文厂商码（SAMSUNG / MICRON …）；
    // 读不到或编码不在表里就整项不发，页面那边"厂商"这一行跟着消失（不留"未知"占位）
    char manufacturer[64];

    if (read_dram_manufacturer(manufacturer, (int) sizeof(manufacturer))) {
        device_put(&out[0], "manufacturer", manufacturer);
    }

    return 1;
}
