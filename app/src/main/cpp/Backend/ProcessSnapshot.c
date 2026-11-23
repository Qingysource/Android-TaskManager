/* ============================================================
 * ProcessSnapshot.c — 进程快照：/proc 采集、增量结算、基准表与进程字段输出。
 * ============================================================ */

#include "Header.h"

/** 字段表：顺序即位序；输出时按 mask 过滤，不用逐个 if (mask & XXX)。 */
static const TmFieldDef k_field_table[] = {
    { TM_FIELD_NAME, "name" },
    { TM_FIELD_PACKAGE_NAME, "package_name" },
    { TM_FIELD_PID, "pid" },
    { TM_FIELD_STATE, "state" },
    { TM_FIELD_USER, "user" },
    { TM_FIELD_SESSION, "session" },
    { TM_FIELD_PGID, "pgid" },
    { TM_FIELD_CGROUP, "cgroup" },
    { TM_FIELD_CPU_USAGE, "cpu_usage" },
    { TM_FIELD_CPU_TIME, "cpu_time" },
    { TM_FIELD_CPU_CYCLES, "cpu_cycles" },
    { TM_FIELD_RSS, "rss" },
    { TM_FIELD_PEAK_RSS, "peak_rss" },
    { TM_FIELD_RSS_DELTA, "rss_delta" },
    { TM_FIELD_ACTIVE_PRIVATE_RSS, "active_private_rss" },
    { TM_FIELD_PRIVATE_RSS, "private_rss" },
    { TM_FIELD_SHARED_RSS, "shared_rss" },
    { TM_FIELD_VSS, "vss" },
    { TM_FIELD_PAGE_FAULTS, "page_faults" },
    { TM_FIELD_PAGE_FAULTS_DELTA, "page_faults_delta" },
    { TM_FIELD_NICE, "nice" },
    { TM_FIELD_ANDROID_PRIORITY, "android_priority" },
    { TM_FIELD_FD_COUNT, "fd_count" },
    { TM_FIELD_THREAD_COUNT, "thread_count" },
    { TM_FIELD_IO_READ_COUNT, "io_read_count" },
    { TM_FIELD_IO_WRITE_COUNT, "io_write_count" },
    { TM_FIELD_IO_READ_BYTES, "io_read_bytes" },
    { TM_FIELD_IO_WRITE_BYTES, "io_write_bytes" },
    { TM_FIELD_EXE_PATH, "exe_path" },
    { TM_FIELD_CMDLINE, "cmdline" },
    { TM_FIELD_SELINUX, "selinux" },
    { TM_FIELD_TARGET_SDK, "target_sdk" },
    { TM_FIELD_PLATFORM, "platform" },
    { TM_FIELD_ARCH, "arch" },
    { TM_FIELD_IS_ADMIN, "is_admin" },
    { TM_FIELD_DESCRIPTION, "description" },
    { TM_FIELD_NX_PROTECT, "nx_protect" },
    { TM_FIELD_GPU_USAGE, "gpu_usage" },
    { TM_FIELD_GPU_ENGINE, "gpu_engine" },
    { TM_FIELD_GPU_DEDICATED_MEM, "gpu_dedicated_mem" },
    { TM_FIELD_GPU_SHARED_MEM, "gpu_shared_mem" },
    { TM_FIELD_CET_PROTECT, "cet_protect" },
    { TM_FIELD_ISOLATION, "isolation" },
};

/** 字段表长度（= 43）。 */
const int k_field_count = (int) (sizeof(k_field_table) / sizeof(k_field_table[0]));

/** 本轮快照（每次查询重建）。 */
TmProcTable g_snapshot;

/** 上一轮快照 = 下一轮的基准（增量与比率全靠它）。 */
TmProcTable g_baseline;

/** 上一轮 /proc/stat 的总 CPU jiffies；<0 = 还没有基准。 */
long long g_cpu_total_prev = -1LL;

/** 页大小与每秒时钟节拍：启动时取一次，避免每个进程都 sysconf 一遍。 */
long g_page_size = 4096L;

long g_clock_ticks = 100L;

/** 单进程第二轮用的临时缓冲（进程间复用，逐字段不重复读文件）。 */
static char g_status_text[TM_TEXT_MAX];

static char g_io_text[TM_TEXT_MAX];

static char g_rollup_text[TM_TEXT_MAX];

/** 上面三份缓冲当前属于哪个 pid（-1 = 还没加载）。 */
static int g_extra_pid = -1;

/** 清空一张表（只清条数，容量与内存留着下次用）。 */
static void table_reset(TmProcTable *table)
{
    table->count = 0;
}

/** 往表里追加一条；容量不够时按翻倍扩容。返回 false = 内存不足或已到上限。 */
static bool table_append(TmProcTable *table, const TmProcStat *item)
{
    if (table->count >= TM_MAX_PROCESSES) {
        return false;
    }

    if (table->count >= table->capacity) {

        int next = (table->capacity == 0) ? 256 : table->capacity * 2;

        if (next > TM_MAX_PROCESSES) {
            next = TM_MAX_PROCESSES;
        }

        TmProcStat *grown = (TmProcStat *) realloc(table->items, sizeof(TmProcStat) * (size_t) next);

        if (grown == NULL) {
            return false;
        }

        table->items = grown;
        table->capacity = next;
    }

    table->items[table->count] = *item;
    table->count++;

    return true;
}

/**
 * 在表里按 pid（可带 start_ticks）找一行。返回 NULL = 没有这个进程。
 *
 * start_ticks > 0 时要求它也必须相同：PID 会被系统复用，
 * 只比 pid 会把"另一个人"当成上一轮的那个进程，增量就算到别人头上。
 */
TmProcStat *table_find(TmProcTable *table, int pid, long long start_ticks)
{
    for (int index = 0; index < table->count; index++) {

        if (table->items[index].pid != pid) {
            continue;
        }

        if (start_ticks > 0 && table->items[index].start_ticks != start_ticks) {
            return NULL;
        }

        return &table->items[index];
    }

    return NULL;
}

/**
 * 在 "键: 值" 形式的文本里找某个键后面的值（取到行尾）。
 *
 * @param text  文本（可为 NULL / 空串）
 * @param key   要匹配的键，含冒号，例如 "VmHWM:"
 * @return true = 找到了（out 里有值）
 */
static bool find_field_value(const char *text, const char *key, char *out, int capacity)
{
    if (text == NULL || text[0] == '\0' || capacity <= 0) {
        return false;
    }

    size_t key_length = strlen(key);
    const char *cursor = text;

    while (*cursor != '\0') {

        if (strncmp(cursor, key, key_length) == 0) {

            const char *value = cursor + key_length;

            while (*value == ' ' || *value == '\t') {
                value++;
            }

            int index = 0;

            while (*value != '\0' && *value != '\n' && index < capacity - 1) {
                out[index++] = *value++;
            }

            out[index] = '\0';

            return index > 0;
        }

        const char *newline = strchr(cursor, '\n');

        if (newline == NULL) {
            break;
        }

        cursor = newline + 1;
    }

    return false;
}

/** 读 /proc/stat 第一行的总 jiffies（user 到 guest 全部相加）；-1 = 读不到。 */
static long long read_cpu_total_jiffies(void)
{
    FILE *file = fopen("/proc/stat", "r");

    if (file == NULL) {
        return -1LL;
    }

    char line[512];

    if (fgets(line, (int) sizeof(line), file) == NULL) {
        fclose(file);

        return -1LL;
    }

    fclose(file);

    if (strncmp(line, "cpu", 3) != 0) {
        return -1LL;
    }

    long long total = 0;
    const char *cursor = line + 3;

    // 前 8 个数字（user / nice / system / idle / iowait / irq / softirq / steal）
    for (int slot = 0; slot < 8; slot++) {

        while (*cursor == ' ' || *cursor == '\t') {
            cursor++;
        }

        if (*cursor == '\0' || *cursor == '\n') {
            break;
        }

        char *end = NULL;

        long long value = strtoll(cursor, &end, 10);

        if (end == cursor) {
            break;
        }

        total += value;
        cursor = end;
    }

    return total;
}

/** 取下一个十进制整数（跳空格），游标前移。取不到就当 0，游标不动。 */
static long long take_long(const char **cursor)
{
    const char *position = *cursor;

    while (*position == ' ' || *position == '\t') {
        position++;
    }

    char *end = NULL;

    long long value = strtoll(position, &end, 10);

    if (end == position) {
        return 0LL;
    }

    *cursor = end;

    return value;
}

/**
 * 解析 /proc/<pid>/stat 一行，填进 [out]。
 *
 * 格式：`pid (comm) state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt
 *        utime stime cutime cstime priority nice threads itrealvalue starttime vsize rss ...`
 *
 * comm 可能含空格甚至 ')'，所以括号按"第一个 '(' 到最后一个 ')'"取。
 *
 * @return false = 这一行不像 stat（进程刚好退出等），调用方跳过它
 */
bool parse_proc_stat(const char *text, TmProcStat *out)
{
    if (text == NULL || text[0] == '\0') {
        return false;
    }

    const char *name_open = strchr(text, '(');
    const char *name_close = strrchr(text, ')');

    if (name_open == NULL || name_close == NULL || name_close <= name_open) {
        return false;
    }

    memset(out, 0, sizeof(*out));

    out->pid = (int) strtol(text, NULL, 10);

    int name_length = (int) (name_close - name_open - 1);

    if (name_length > TM_COMM_MAX - 1) {
        name_length = TM_COMM_MAX - 1;
    }

    memcpy(out->comm, name_open + 1, (size_t) name_length);
    out->comm[name_length] = '\0';

    const char *cursor = name_close + 1;

    while (*cursor == ' ') {
        cursor++;
    }

    if (*cursor != '\0') {
        out->state_char = *cursor;
        cursor++;
    }

    take_long(&cursor);            // ppid
    out->pgrp = take_long(&cursor);
    out->session = take_long(&cursor);
    take_long(&cursor);            // tty_nr
    take_long(&cursor);            // tpgid
    take_long(&cursor);            // flags
    out->min_flt = take_long(&cursor);
    take_long(&cursor);            // cmin_flt
    out->maj_flt = take_long(&cursor);
    take_long(&cursor);            // cmaj_flt
    out->utime = take_long(&cursor);
    out->stime = take_long(&cursor);
    take_long(&cursor);            // cutime
    take_long(&cursor);            // cstime
    take_long(&cursor);            // priority
    out->nice = take_long(&cursor);
    out->num_threads = take_long(&cursor);
    take_long(&cursor);            // itrealvalue
    out->start_ticks = take_long(&cursor);
    out->vsize_bytes = take_long(&cursor);
    out->rss_pages = take_long(&cursor);

    return out->pid > 0;
}

/** 按 pid 升序排（readdir 顺序是散的，输出按 pid 递增更好读、更好核对）。 */
static int compare_by_pid(const void *left, const void *right)
{
    const TmProcStat *a = (const TmProcStat *) left;
    const TmProcStat *right_stat = (const TmProcStat *) right;

    if (a->pid < right_stat->pid) {
        return -1;
    }

    if (a->pid > right_stat->pid) {
        return 1;
    }

    return 0;
}

/**
 * 一个进程属于哪个范围，并顺手把属主 uid 带出来。
 *
 * 为什么要一起取：判范围要 stat("/proc/<pid>") 看属主，
 * 而 P 行的 uid 扩展键要的正是同一个数字。
 * 分成两个函数会在每个进程上多一次 stat，没有任何收益。
 *
 * @param out_uid 非 NULL 时写入目录属主 uid；返回 -1 时保持调用方原值
 * @return [TM_SCOPE_USER] / [TM_SCOPE_SYSTEM]；-1 = 目录 stat 不了（刚退出或权限不够），无法判定
 */
static int process_scope_of(int pid, int *out_uid)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d", pid);

    struct stat info;

    if (stat(path, &info) != 0) {
        return -1;
    }

    if (out_uid != NULL) {
        *out_uid = (int) info.st_uid;
    }

    return ((long long) info.st_uid >= TM_UID_APP_START) ? TM_SCOPE_USER : TM_SCOPE_SYSTEM;
}

/**
 * 第一轮（mini）采样：遍历 /proc，每个进程只读一次 stat。
 *
 * @param scope 范围筛选。选 USER / SYSTEM 时，属主 stat 是筛选本身；
 *              选 ALL 时也会做这一次 stat —— 它同时供 P 行的 uid 扩展键使用，
 *              每进程一次目录 stat 在整轮里只有毫秒级，换来 Android 侧能直接映射包名
 * @return 收录到的进程数；0 = /proc 打不开（几乎没有这种情况）
 */
int collect_snapshot(int scope)
{
    table_reset(&g_snapshot);

    DIR *proc_dir = opendir("/proc");

    if (proc_dir == NULL) {
        return 0;
    }

    struct dirent *entry;

    while ((entry = readdir(proc_dir)) != NULL) {

        if (!is_pure_digits(entry->d_name)) {
            continue;
        }

        int pid = (int) strtol(entry->d_name, NULL, 10);

        // 属主 uid 与范围判定共用同一次 stat：既判"用户 / 系统"，
        // 也留作 P 行的 uid 扩展键（安卓侧按它查包名、targetSdk、应用描述）。
        int owner_uid = 0;
        int owner_scope = process_scope_of(pid, &owner_uid);

        // 筛选放在最前面：不合范围的进程连 stat 文件都不读，
        // 于是"包里的行"和"第二轮要补读的文件"一起省掉了。
        if (scope != TM_SCOPE_ALL && owner_scope != scope) {
            continue;
        }

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/stat", pid);

        char text[TM_TEXT_MAX];

        // 读不到 = 这个进程刚退出，或权限不够看它：都不该让整次快照失败
        if (read_text_file(path, text, (int) sizeof(text)) < 0) {
            continue;
        }

        TmProcStat item;

        if (!parse_proc_stat(text, &item)) {
            continue;
        }

        // parse_proc_stat 会 memset 整个结构，uid 必须在这之后回填：
        // 它是采集阶段 stat 到的事实，不是 /proc/<pid>/stat 里的字段。
        item.uid = owner_uid;
        item.uid_known = (owner_scope >= 0);

        table_append(&g_snapshot, &item);
    }

    closedir(proc_dir);

    if (g_snapshot.count > 1) {
        qsort(g_snapshot.items, (size_t) g_snapshot.count, sizeof(TmProcStat), compare_by_pid);
    }

    return g_snapshot.count;
}

/**
 * 结算所有"算出来"的字段。
 *
 * 故意做成**一个函数、按顺序分区块**，不按字段拆成一堆小函数：
 * 这些派生值共用同样的输入（基准行、整机 CPU 增量、页大小），
 * 拆开只会让参数在函数之间来回搬，读起来更累。
 *
 * @param cur             本轮记录（就地回填）
 * @param base            上一轮同一进程的记录（NULL = 没有基准）
 * @param cpu_total_delta 两轮之间 /proc/stat 总 jiffies 的增量（<=0 = 算不了）
 * @param clock_ticks     每秒时钟节拍（sysconf(_SC_CLK_TCK)）
 */
static void compute_derived(TmProcStat *cur, const TmProcStat *base,
                            long long cpu_total_delta, long clock_ticks)
{
    cur->has_baseline = (base != NULL);

    // ---- 处理 CPU 时间：累计时钟节拍 -> 毫秒 ----
    long long total_ticks = cur->utime + cur->stime;

    cur->cpu_time_ms = (clock_ticks > 0) ? (total_ticks * 1000LL / clock_ticks) : 0LL;

    // ---- 处理 CPU 使用率：本进程累计增量 / 整机累计增量 ----
    cur->cpu_usage_percent = -1.0;

    if (base != NULL && cpu_total_delta > 0) {

        long long before = base->utime + base->stime;
        long long delta = total_ticks - before;

        if (delta < 0) {
            delta = 0;
        }

        cur->cpu_usage_percent = (double) delta * 100.0 / (double) cpu_total_delta;
    }

    // ---- 处理常驻内存：页 -> 字节，再算相对上一轮的变化 ----
    cur->rss_bytes = cur->rss_pages * (long long) g_page_size;

    cur->rss_delta_bytes = 0;

    if (base != NULL) {
        cur->rss_delta_bytes = cur->rss_bytes - base->rss_pages * (long long) g_page_size;
    }

    // ---- 处理缺页：min_flt + maj_flt 为总数，再算增量 ----
    cur->page_faults = cur->min_flt + cur->maj_flt;

    cur->page_faults_delta = 0;

    if (base != NULL) {
        cur->page_faults_delta = cur->page_faults - (base->min_flt + base->maj_flt);
    }
}

/**
 * 把本轮快照提交成下一轮的基准，**同时清理两次采样之间退出的进程**。
 *
 * 为什么是整体替换而不是逐条合并：本轮的进程集合就是事实。
 * 上一轮有、本轮没有的进程一律视为已退出（或 pid 被复用后换了身份），
 * 留着它们只会让增量算到"另一个人"头上，基准表还会越积越大。
 *
 * @param cpu_total_now 本轮读到的整机累计 jiffies（-1 = 没读到，下轮算不了比率）
 */
static void commit_baseline(long long cpu_total_now)
{
    table_reset(&g_baseline);

    for (int index = 0; index < g_snapshot.count; index++) {
        table_append(&g_baseline, &g_snapshot.items[index]);
    }

    g_cpu_total_prev = cpu_total_now;
}

/** 清掉基准（REINIT 用）：下一次快照重新建立，比率类字段会回 NO_BASELINE。 */
void baseline_clear(void)
{
    table_reset(&g_baseline);

    g_cpu_total_prev = -1LL;
}

// ============================================================
// 第二轮（full）：按需读取 / 取值辅助
// ============================================================

/**
 * 状态字符 -> 英文状态码（含义与 ps 的状态字母一致）。
 *
 * 直接沿用内核的单个字母，本身已经是最"标识化"的形式：
 * 客户端按码映射成本地语言（R→运行 / S→休眠 …），协议里不再放中文。
 * 认不出的状态统一发 UNKNOWN，别把原始字母塞给 UI 去猜。
 */
static const char *state_code_of(char state)
{
    switch (state) {

        case 'R': return "R";

        case 'S': return "S";

        case 'D': return "D";

        case 'Z': return "Z";

        case 'T': return "T";

        case 't': return "t";

        case 'I': return "I";

        case 'X': return "X";

        default: return "UNKNOWN";
    }
}

/** 返回值，同时告诉调用方"这一格有值"。 */
static const char *text_result(const char **status, const char *text)
{
    *status = NULL;

    return text;
}

/** 进程所属 uid：取 /proc/<pid> 目录的所有者，比读 status 少一次文件读取。 */
static bool proc_uid_of(int pid, int *uid)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d", pid);

    struct stat info;

    if (stat(path, &info) != 0) {
        return false;
    }

    *uid = (int) info.st_uid;

    return true;
}

/** uid -> 用户名；查不到返回 NULL（调用方只显示数字 uid）。 */
static const char *user_name_of(int uid)
{
    struct passwd *entry = getpwuid((uid_t) uid);

    if (entry == NULL) {
        return NULL;
    }

    return entry->pw_name;
}

/**
 * 读 /proc/<pid>/exe 的 ELF 头，得到位数与机器架构。
 *
 * @return false = 读不到（内核线程没有 exe，或权限不足）
 */
static bool elf_info_of(int pid, int *bits, char *arch, int capacity)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d/exe", pid);

    FILE *file = fopen(path, "rb");

    if (file == NULL) {
        return false;
    }

    unsigned char header[20];

    size_t got = fread(header, 1, sizeof(header), file);

    fclose(file);

    if (got < 20 || memcmp(header, "\177ELF", 4) != 0) {
        return false;
    }

    bool little_endian = (header[5] == 1);

    *bits = (header[4] == 2) ? 64 : 32;

    unsigned int machine = little_endian
                           ? (unsigned int) header[18] | ((unsigned int) header[19] << 8)
                           : (unsigned int) header[19] | ((unsigned int) header[18] << 8);

    const char *name = "unknown";

    switch (machine) {

        case 3:
            name = "x86";
            break;

        case 8:
            name = "mips";
            break;

        case 20:
            name = "ppc";
            break;

        case 21:
            name = "ppc64";
            break;

        case 40:
            name = "arm";
            break;

        case 62:
            name = "x86_64";
            break;

        case 183:
            name = "aarch64";
            break;

        case 243:
            name = "riscv";
            break;

        default:
            break;
    }

    snprintf(arch, capacity, "%s", name);

    return true;
}

/**
 * /proc/<pid>/maps 里是否存在"同时可写又可执行"的映射。
 *
 * @return 1 = 有（说明 NX 没真正生效）, 0 = 没有, -1 = 读不到
 */
static int detect_writable_exec(int pid)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/proc/%d/maps", pid);

    FILE *file = fopen(path, "r");

    if (file == NULL) {
        return -1;
    }

    char line[256];
    int result = 0;

    while (fgets(line, (int) sizeof(line), file) != NULL) {

        // 行格式：地址范围 四位权限 ...
        const char *space = strchr(line, ' ');

        if (space == NULL) {
            continue;
        }

        char perms[8] = {0};
        const char *cursor = space + 1;
        int index = 0;

        while (*cursor != '\0' && *cursor != ' ' && index < 4) {
            perms[index++] = *cursor++;
        }

        perms[index] = '\0';

        if (strchr(perms, 'w') != NULL && strchr(perms, 'x') != NULL) {
            result = 1;
            break;
        }
    }

    fclose(file);

    return result;
}

/** 从 smaps_rollup 文本里取一个 kB 字段；-1 = 没有这一项。 */
static long long smaps_kb_of(const char *text, const char *key)
{
    char value[64];

    if (!find_field_value(text, key, value, (int) sizeof(value))) {
        return -1LL;
    }

    return strtoll(value, NULL, 10);
}

/** 从 /proc/<pid>/io 文本里取一个计数；-1 = 没有这一项。 */
static long long io_value_of(const char *text, const char *key)
{
    char value[64];

    if (!find_field_value(text, key, value, (int) sizeof(value))) {
        return -1LL;
    }

    return strtoll(value, NULL, 10);
}

/**
 * 第二轮采样前的一次性准备：按掩码把"多个字段共用"的文件各读一次。
 *
 * 同一个 pid 内共用，换进程时重读。掩码没点到对应字段时**一个文件都不读**。
 */
static void load_extras(int pid, unsigned long long mask)
{
    if (g_extra_pid == pid) {
        return;
    }

    g_extra_pid = pid;

    g_status_text[0] = '\0';
    g_io_text[0] = '\0';
    g_rollup_text[0] = '\0';

    char path[TM_PATH_MAX];

    // status：峰值内存 VmHWM、CET 的线程特性
    if ((mask & (TM_FIELD_PEAK_RSS | TM_FIELD_CET_PROTECT)) != 0ULL) {

        snprintf(path, sizeof(path), "/proc/%d/status", pid);

        if (read_text_file(path, g_status_text, TM_TEXT_MAX) < 0) {
            g_status_text[0] = '\0';
        }
    }

    // io：四个 I/O 计数
    if ((mask & (TM_FIELD_IO_READ_COUNT | TM_FIELD_IO_WRITE_COUNT |
                 TM_FIELD_IO_READ_BYTES | TM_FIELD_IO_WRITE_BYTES)) != 0ULL) {

        snprintf(path, sizeof(path), "/proc/%d/io", pid);

        if (read_text_file(path, g_io_text, TM_TEXT_MAX) < 0) {
            g_io_text[0] = '\0';
        }
    }

    // smaps_rollup：三个内存细分字段（用汇总文件，不逐个 VMA 扫 smaps）
    if ((mask & (TM_FIELD_ACTIVE_PRIVATE_RSS | TM_FIELD_PRIVATE_RSS |
                 TM_FIELD_SHARED_RSS)) != 0ULL) {

        snprintf(path, sizeof(path), "/proc/%d/smaps_rollup", pid);

        if (read_text_file(path, g_rollup_text, TM_TEXT_MAX) < 0) {
            g_rollup_text[0] = '\0';
        }
    }
}

/**
 * 取某个字段的值（文本形态）。返回 NULL 时用 *out_status 说明为什么没有值。
 *
 * 只有被掩码点到的字段才会走进这里，所以"没点到就不读文件"这个省法成立。
 * 数值字段一律输出**纯数字**（不带单位、不带百分号）：
 * 表格按数值排序，单位写在列名里就够了。
 */
static const char *field_text_of(const TmProcStat *cur, const char *key,
                                 char *buffer, int capacity, const char **out_status)
{
    *out_status = TM_FIELD_STATUS_READ_FAILED;

    // ---------- 第一轮（stat）就已经拿到的字段 ----------

    if (strcmp(key, "name") == 0) {

        // 僵尸进程（state = Z）先单独处理：它已经被杀死了，只是父进程还没回收，
        // pid 与 stat 都还在，而 cmdline 一定是空的（进程的用户空间已释放）。
        // 这里只发内核风格的 "[comm]" 名字本身，不再拼"(僵尸进程)"——
        // 那是本地语言文案，客户端拿 state == Z 自己补（协议里不放中文）。
        if (cur->state_char == 'Z') {

            if (cur->comm[0] == '\0') {
                *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

                return NULL;
            }

            snprintf(buffer, capacity, "[%s]", cur->comm);

            return text_result(out_status, buffer);
        }

        // 为什么不用 stat 里的 comm：内核把 comm 限制在 15 字节（TASK_COMM_LEN - 1），
        // 而 Android 的应用进程名就是完整包名（com.android.taskmanager 有 24 字节），
        // 从 stat 读到的天然是截断值，名称列只会显示半截。
        // cmdline 不截断，所以以 argv[0] 为准；comm 只在没有 cmdline（内核线程）时兜底。
        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/cmdline", cur->pid);

        int length = read_text_file(path, buffer, capacity);

        if (length > 0 && buffer[0] != '\0') {

            // cmdline 用 '\0' 分隔参数，读出来时第一段就是 argv[0]（strrchr 在 C 串上不会越过它）
            const char *slash = strrchr(buffer, '/');

            if (slash != NULL && slash[1] != '\0') {

                // argv[0] 是路径时取最后一段，和 ps 显示内核线程名的习惯一致
                const char *base = slash + 1;
                int base_length = (int) strlen(base);

                memmove(buffer, base, (size_t) base_length);

                buffer[base_length] = '\0';
            }

            return text_result(out_status, buffer);
        }

        if (cur->comm[0] == '\0') {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        // length == 0：文件存在但内容为空 —— 只有内核线程是这样（用户态进程一定有 argv[0]）。
        // 按 ps 的写法包上方括号，让"内核里的东西"和普通进程一眼分得开。
        // length <  0：打不开（权限），不能断定它是不是内核线程，就只给 comm，不乱加括号。
        if (length == 0) {
            snprintf(buffer, capacity, "[%s]", cur->comm);

            return text_result(out_status, buffer);
        }

        return text_result(out_status, cur->comm);
    }

    if (strcmp(key, "pid") == 0) {
        snprintf(buffer, capacity, "%d", cur->pid);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "state") == 0) {
        return text_result(out_status, state_code_of(cur->state_char));
    }

    if (strcmp(key, "session") == 0) {
        snprintf(buffer, capacity, "%lld", cur->session);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "pgid") == 0) {
        snprintf(buffer, capacity, "%lld", cur->pgrp);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "nice") == 0) {
        snprintf(buffer, capacity, "%lld", cur->nice);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "thread_count") == 0) {
        snprintf(buffer, capacity, "%lld", cur->num_threads);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "vss") == 0) {
        snprintf(buffer, capacity, "%lld", cur->vsize_bytes);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "rss") == 0) {
        snprintf(buffer, capacity, "%lld", cur->rss_bytes);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "page_faults") == 0) {
        snprintf(buffer, capacity, "%lld", cur->page_faults);

        return text_result(out_status, buffer);
    }

    // ---------- compute_derived 结算出来的字段 ----------

    if (strcmp(key, "cpu_usage") == 0) {

        if (!cur->has_baseline || cur->cpu_usage_percent < 0.0) {
            *out_status = TM_FIELD_STATUS_NO_BASELINE;

            return NULL;
        }

        // 两位小数：0.01% 的精度，客户端按设置页的"利用率精确度"决定显示几位
        snprintf(buffer, capacity, "%.2f", cur->cpu_usage_percent);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "cpu_time") == 0) {
        snprintf(buffer, capacity, "%.2f", (double) cur->cpu_time_ms / 1000.0);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "rss_delta") == 0) {

        if (!cur->has_baseline) {
            *out_status = TM_FIELD_STATUS_NO_BASELINE;

            return NULL;
        }

        snprintf(buffer, capacity, "%lld", cur->rss_delta_bytes);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "page_faults_delta") == 0) {

        if (!cur->has_baseline) {
            *out_status = TM_FIELD_STATUS_NO_BASELINE;

            return NULL;
        }

        snprintf(buffer, capacity, "%lld", cur->page_faults_delta);

        return text_result(out_status, buffer);
    }

    // ---------- 需要读别的文件才有 ----------

    if (strcmp(key, "user") == 0) {

        // 属主 uid 采集阶段已经 stat 过（P 行那条 uid 扩展键就是它），
        // 这里优先复用；只有采集时没取到，才补一次 stat。
        int uid = cur->uid;

        if (!cur->uid_known && !proc_uid_of(cur->pid, &uid)) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        const char *name = user_name_of(uid);

        if (name == NULL) {
            snprintf(buffer, capacity, "%d", uid);
        } else {
            snprintf(buffer, capacity, "%s (%d)", name, uid);
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "cgroup") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/cgroup", cur->pid);

        if (read_text_file(path, buffer, capacity) < 0) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        char *newline = strchr(buffer, '\n');

        if (newline != NULL) {
            *newline = '\0';
        }

        // 第一行形如 `层级:控制器:路径`，取第二个冒号之后那段
        char *first = strchr(buffer, ':');
        char *second = (first == NULL) ? NULL : strchr(first + 1, ':');

        if (second == NULL || second[1] == '\0') {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        return text_result(out_status, second + 1);
    }

    if (strcmp(key, "android_priority") == 0) {

        // Android 的进程优先级就是 LMK 口径的 oom_score_adj：
        // 负数 = 越不容易被杀（前台 / 常驻），正数 = 越先被回收。
        // 这一格必须由 Linux 侧读：Android Framework 只能看到自己的 importance，
        // 别人的 oom_score_adj 恰恰是提权身份才有权限打开的文件。
        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", cur->pid);

        if (read_text_file(path, buffer, capacity) < 0) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        char *newline = strchr(buffer, '\n');

        if (newline != NULL) {
            *newline = '\0';
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "peak_rss") == 0) {

        if (g_status_text[0] == '\0') {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        char raw[64];

        if (!find_field_value(g_status_text, "VmHWM:", raw, (int) sizeof(raw))) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        snprintf(buffer, capacity, "%lld", strtoll(raw, NULL, 10) * 1024LL);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "active_private_rss") == 0 ||
        strcmp(key, "private_rss") == 0 ||
        strcmp(key, "shared_rss") == 0) {

        if (g_rollup_text[0] == '\0') {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        long long private_dirty = smaps_kb_of(g_rollup_text, "Private_Dirty:");
        long long private_clean = smaps_kb_of(g_rollup_text, "Private_Clean:");
        long long shared_dirty = smaps_kb_of(g_rollup_text, "Shared_Dirty:");
        long long shared_clean = smaps_kb_of(g_rollup_text, "Shared_Clean:");

        if (private_dirty < 0 && private_clean < 0 && shared_dirty < 0 && shared_clean < 0) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        long long total_kb = 0;

        if (strcmp(key, "active_private_rss") == 0) {
            // smaps_rollup 没有 Private_Active 这一项：用 Private_Dirty 近似"活跃私有"
            total_kb = (private_dirty < 0) ? 0 : private_dirty;
        } else if (strcmp(key, "private_rss") == 0) {
            total_kb = (private_dirty < 0 ? 0 : private_dirty) + (private_clean < 0 ? 0 : private_clean);
        } else {
            total_kb = (shared_dirty < 0 ? 0 : shared_dirty) + (shared_clean < 0 ? 0 : shared_clean);
        }

        snprintf(buffer, capacity, "%lld", total_kb * 1024LL);

        return text_result(out_status, buffer);
    }

    if (strncmp(key, "io_", 3) == 0) {

        if (g_io_text[0] == '\0') {
            // Android 上普通权限读不到 /proc/<pid>/io（内核只给同 uid / root）
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        const char *io_key = NULL;

        if (strcmp(key, "io_read_count") == 0) {
            io_key = "syscr:";
        } else if (strcmp(key, "io_write_count") == 0) {
            io_key = "syscw:";
        } else if (strcmp(key, "io_read_bytes") == 0) {
            io_key = "read_bytes:";
        } else if (strcmp(key, "io_write_bytes") == 0) {
            io_key = "write_bytes:";
        }

        if (io_key != NULL) {

            long long value = io_value_of(g_io_text, io_key);

            if (value >= 0) {
                snprintf(buffer, capacity, "%lld", value);

                return text_result(out_status, buffer);
            }
        }

        *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

        return NULL;
    }

    if (strcmp(key, "fd_count") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/fd", cur->pid);

        int count = count_directory_entries(path);

        if (count < 0) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        snprintf(buffer, capacity, "%d", count);

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "exe_path") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/exe", cur->pid);

        if (read_link_text(path, buffer, capacity) <= 0) {
            // 内核线程没有可执行文件：这是"没有这一项"，不是读取失败
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "cmdline") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/cmdline", cur->pid);

        int length = read_text_file(path, buffer, capacity);

        if (length <= 0) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        // cmdline 的参数之间是 '\0'：换成空格才是一行可读的命令
        for (int index = 0; index < length - 1; index++) {

            if (buffer[index] == '\0') {
                buffer[index] = ' ';
            }
        }

        // 末尾补的 '\0' 与可能残留的空格一起去掉
        buffer[length - 1] = '\0';

        int end = (int) strlen(buffer);

        while (end > 0 && buffer[end - 1] == ' ') {
            buffer[end - 1] = '\0';
            end--;
        }

        if (end == 0) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "selinux") == 0) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%d/attr/current", cur->pid);

        if (read_text_file(path, buffer, capacity) <= 0) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        // 内容可能带结尾换行
        int end = (int) strlen(buffer);

        while (end > 0 && (buffer[end - 1] == '\n' || buffer[end - 1] == '\r')) {
            buffer[end - 1] = '\0';
            end--;
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "platform") == 0 || strcmp(key, "arch") == 0) {

        int bits = 0;
        char arch[32] = {0};

        if (!elf_info_of(cur->pid, &bits, arch, (int) sizeof(arch))) {
            *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

            return NULL;
        }

        // platform 只发位宽数字（32 / 64），"位"这个字由客户端按本地语言补；
        // arch 本来就发 e_machine 的英文标识（ELF 里取出来的），原样发
        if (key[0] == 'p') {
            snprintf(buffer, capacity, "%d", bits);
        } else {
            snprintf(buffer, capacity, "%s", arch);
        }

        return text_result(out_status, buffer);
    }

    if (strcmp(key, "is_admin") == 0) {

        int uid = 0;

        if (!proc_uid_of(cur->pid, &uid)) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        // 布尔量一律发 "1" / "0"，不出现"是 / 否"这种本地语言
        return text_result(out_status, uid == 0 ? "1" : "0");
    }

    if (strcmp(key, "nx_protect") == 0) {

        int writable_exec = detect_writable_exec(cur->pid);

        if (writable_exec < 0) {
            *out_status = TM_FIELD_STATUS_ACCESS_DENIED;

            return NULL;
        }

        // 存在"可写又可执行"的映射 = 数据执行保护对这段没生效；布尔量发 "1" / "0"
        return text_result(out_status, writable_exec == 0 ? "1" : "0");
    }

    if (strcmp(key, "cet_protect") == 0) {

        if (g_status_text[0] == '\0') {
            *out_status = TM_FIELD_STATUS_UNSUPPORTED;

            return NULL;
        }

        char raw[128];

        // 只有 x86 内核会报这一行；arm64 设备上就是"不支持"
        if (!find_field_value(g_status_text, "x86_Thread_features:", raw, (int) sizeof(raw))) {
            *out_status = TM_FIELD_STATUS_UNSUPPORTED;

            return NULL;
        }

        bool shadow_stack = strstr(raw, "shstk") != NULL || strstr(raw, "shadow_stack") != NULL;

        // 布尔量发 "1" / "0"
        return text_result(out_status, shadow_stack ? "1" : "0");
    }

    // ---------- 本版拿不到 / 归 Android 服务填 ----------

    if (strcmp(key, "package_name") == 0 || strcmp(key, "target_sdk") == 0 ||
        strcmp(key, "description") == 0 || strcmp(key, "android_priority") == 0 ||
        strcmp(key, "isolation") == 0) {

        *out_status = TM_FIELD_STATUS_NOT_AVAILABLE;

        return NULL;
    }

    if (strcmp(key, "cpu_cycles") == 0 ||
        strcmp(key, "gpu_usage") == 0 || strcmp(key, "gpu_engine") == 0 ||
        strcmp(key, "gpu_dedicated_mem") == 0 || strcmp(key, "gpu_shared_mem") == 0) {

        *out_status = TM_FIELD_STATUS_UNSUPPORTED;

        return NULL;
    }

    // 字段表里有、这里没实现：明确说"不支持"，绝不拿 0 顶上
    *out_status = TM_FIELD_STATUS_UNSUPPORTED;

    return NULL;
}

/**
 * 输出一个进程行 `P <pid> start_ticks=<时刻> k=v k=v …`，只输出被掩码点到的字段。
 *
 * 值一律百分号编码；没值的字段输出 `k=!状态`；超过 TM_VALUE_MAX 标 TRUNCATED。
 * 一个进程一个进程地发：客户端收满 count 行才算一条完整快照。
 */
static void emit_process_row(const TmProcStat *cur, unsigned long long mask)
{
    // 行身份 = pid + start_ticks，两者**无条件输出**（不占 43 位掩码：
    // 掩码决定"表格显示哪几列"，身份是每行都必须有的元数据）。
    // PID 会被系统复用，客户端只比 pid 会认错成另一个进程。
    printf("P %d start_ticks=%lld", cur->pid, cur->start_ticks);

    // 属主 uid：与 start_ticks 同样是行元数据，不占 43 位掩码。
    // Android 侧靠它把 uid 换成包名 / targetSdk / 应用描述，
    // 采集阶段已经 stat 过目录属主，这里只做输出，不再读 /proc。
    if (cur->uid_known) {
        printf(" uid=%d", cur->uid);
    }

    // 第二轮：先把"多个字段共用"的文件读一份（掩码没点到就不读）
    load_extras(cur->pid, mask);

    char value[TM_TEXT_MAX];

    for (int f = 0; f < k_field_count; f++) {

        if ((mask & k_field_table[f].mask) == 0ULL) {
            continue;
        }

        const char *status = TM_FIELD_STATUS_READ_FAILED;

        const char *text = field_text_of(cur, k_field_table[f].key, value,
                                         (int) sizeof(value), &status);

        if (text == NULL) {
            // 没有值：输出 `k=!状态`，客户端会当成"这一格没值"，不会当成 0
            printf(" %s=!%s", k_field_table[f].key, status);

            continue;
        }

        // 长值截断（cmdline / 路径 / selinux 上下文都可能很长）。
        // 协议里"值"只有两种形态：正常值，或 `!状态`。截断后拿不到完整值，
        // 就明确标 TRUNCATED，绝不把半个值当作完整值发出去。
        if (strlen(text) > TM_VALUE_MAX) {

            printf(" %s=!%s", k_field_table[f].key, TM_FIELD_STATUS_TRUNCATED);

            continue;
        }

        printf(" %s=", k_field_table[f].key);

        print_escaped_value(text);
    }

    printf("\n");
}

/**
 * 处理"查询进程快照"。
 *
 * 顺序：第一轮 mini 采样 -> 结算派生字段 -> 先发包头（条数已定）-> 逐行发 -> 提交基准。
 * 包头必须带着准确条数先发：客户端按 count 判断"包收完了没有"。
 */
bool handle_query_process(TmBackend *backend, unsigned long long seq,
                                 int type, unsigned long long mask, const char *scope_text)
{
    // 掩码越界（用了第 43 位以上）：这是另一版协议，不能猜，直接回参数错
    if ((mask & ~TM_FIELD_ALL) != 0ULL) {

        reply_package_header(seq, type, mask, TM_STATUS_BAD_ARGUMENT, 0);
        print_info_line("error", "mask out of range (max 43 bits)");

        return true;
    }

    // 掩码为 0：按"全部字段"处理，与 UISvc 里 NONE -> ALL 的兜底保持一致
    if (mask == 0ULL) {
        mask = TM_FIELD_ALL;
    }

    // 第五个字段 = 范围（0 全部 / 1 用户 / 2 系统）。缺省按"全部"，
    // 这样老客户端（不带该字段）行为不变。
    int scope = TM_SCOPE_ALL;

    if (scope_text != NULL && scope_text[0] != '\0') {
        scope = (int) strtol(scope_text, NULL, 10);
    }

    if (scope != TM_SCOPE_ALL && scope != TM_SCOPE_USER && scope != TM_SCOPE_SYSTEM) {
        reply_package_header(seq, type, mask, TM_STATUS_BAD_ARGUMENT, 0);
        print_info_line("error", "bad scope (want 0 = all / 1 = user / 2 = system)");

        return true;
    }

    // 第一轮（mini）：有哪些进程、以及所有算增量要的基准值
    int count = collect_snapshot(scope);

    long long cpu_total_now = read_cpu_total_jiffies();
    long long cpu_total_delta = 0;

    if (g_cpu_total_prev >= 0 && cpu_total_now > g_cpu_total_prev) {
        cpu_total_delta = cpu_total_now - g_cpu_total_prev;
    }

    // 结算：每个进程按"本轮 + 上一轮同一身份"算出派生值
    for (int index = 0; index < g_snapshot.count; index++) {

        TmProcStat *cur = &g_snapshot.items[index];

        // start_ticks 一起匹配：pid 被复用时要当成"另一个人"，不能沿用旧基准
        TmProcStat *base = table_find(&g_baseline, cur->pid, cur->start_ticks);

        compute_derived(cur, base, cpu_total_delta, g_clock_ticks);
    }

    reply_package_header(seq, type, mask, TM_STATUS_OK, count);

    // 回带本轮生效的范围，客户端可据此核对"服务端筛的和我请求的是同一档"
    printf("I scope=%s\n", scope == TM_SCOPE_USER ? "user"
                          : scope == TM_SCOPE_SYSTEM ? "system"
                          : "all");

    // 第二轮（full）：逐进程、按掩码读文件并输出
    for (int index = 0; index < g_snapshot.count; index++) {
        emit_process_row(&g_snapshot.items[index], mask);
    }

    // 本轮即下一轮基准；两次采样之间退出的进程在这里被清理掉
    commit_baseline(cpu_total_now);

    backend->baseline_ready = true;

    return true;
}
