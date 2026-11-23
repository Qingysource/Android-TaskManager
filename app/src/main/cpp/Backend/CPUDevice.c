/* ============================================================
 * CPUDevice.c — CPU 设备：/proc/stat 采样、频率与缓存、拓扑、型号与虚拟化信息。
 * ============================================================ */

#include "Header.h"

static /** collect_cpu_indices 定义在后面（CPU 拓扑那一节），这里先声明。 */
int collect_cpu_indices(int *out, int capacity);

/**
 * 解析一行 cpu 后面的 8 列时间。
 *
 * 第 4 列 idle、第 5 列 iowait 都算空闲；其余列（user nice system irq softirq steal）
 * 都算在干活。guest 那两列是 user 的子集，不能重复加。
 *
 * @return false = 一行里一个数字都没读到
 */
static bool parse_cpu_times(const char *cursor, TmCpuTimes *out)
{
    long long total = 0;
    long long idle = 0;
    long long kernel = 0;

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

        // 第 4、5 列（idle / iowait）算空闲
        if (slot == 3 || slot == 4) {
            idle += value;
        }

        // 第 3、6、7 列（system / irq / softirq）算内核时间
        if (slot == 2 || slot == 5 || slot == 6) {
            kernel += value;
        }

        cursor = end;
    }

    if (total <= 0) {
        return false;
    }

    out->total = total;
    out->idle = idle;
    out->kernel = kernel;

    return true;
}

/**
 * 读一整轮 /proc/stat。
 *
 * 为什么要把 cpuN 行也收下来：性能页以后要按芯片画每个逻辑核的利用率，
 * 只读汇总行拿不到。一次读全表，差分时再分别对账。
 *
 * @return false = 文件打不开，或一行都没解析出来
 */
static bool read_cpu_stat_table(TmCpuStatTable *out)
{
    memset(out, 0, sizeof(*out));

    FILE *file = fopen("/proc/stat", "r");

    if (file == NULL) {
        return false;
    }

    char line[512];
    bool any = false;

    while (fgets(line, (int) sizeof(line), file) != NULL) {

        if (strncmp(line, "cpu", 3) != 0) {
            continue;
        }

        TmCpuTimes times;

        // 第 4 个字符是空格 = 汇总行（cpu  123 ...）；否则是 cpuN
        if (line[3] == ' ') {

            if (parse_cpu_times(line + 3, &times)) {

                out->summary = times;
                any = true;
            }

            continue;
        }

        char *end = NULL;
        long logical = strtol(line + 3, &end, 10);

        if (end == line + 3 || logical < 0 || logical >= TM_STAT_CPU_MAX) {
            continue;
        }

        if (!parse_cpu_times(end, &times)) {
            continue;
        }

        out->cpu[logical] = times;
        out->seen[logical] = true;

        if ((int) logical + 1 > out->max_logical) {
            out->max_logical = (int) logical + 1;
        }

        any = true;
    }

    fclose(file);

    return any;
}

/**
 * 两个采样点之间算利用率（%）。
 * @return -1 = 增量不成立（计数器回退、这一核不在两轮里）
 */
static double cpu_percent_between(const TmCpuTimes *later, const TmCpuTimes *before)
{
    if (later->total <= before->total) {
        return -1.0;
    }

    long long delta_total = later->total - before->total;
    long long delta_idle = later->idle - before->idle;

    if (delta_idle < 0) {
        delta_idle = 0;
    }

    if (delta_idle > delta_total) {
        delta_idle = delta_total;
    }

    double percent = (double) (delta_total - delta_idle) * 100.0 / (double) delta_total;

    if (percent < 0.0) {
        percent = 0.0;
    }

    if (percent > 100.0) {
        percent = 100.0;
    }

    return percent;
}

/**
 * 两个采样点之间算内核时间占比（%）。
 *
 * 分母与利用率用同一个 delta_total，所以内核时间永远 <= 总利用率，图表上那条
 * 内核曲线不会冲出总曲线。
 *
 * @return -1 = 这一帧算不出内核时间（增量不成立或计数器回退）
 */
static double cpu_kernel_percent_between(const TmCpuTimes *later, const TmCpuTimes *before)
{
    if (later->total <= before->total) {
        return -1.0;
    }

    long long delta_total = later->total - before->total;
    long long delta_kernel = later->kernel - before->kernel;

    // 计数器回退（热插拔、虚拟机迁移）就当这一帧没有值，不拿 0 顶上
    if (delta_kernel < 0) {
        return -1.0;
    }

    if (delta_kernel > delta_total) {
        delta_kernel = delta_total;
    }

    double percent = (double) delta_kernel * 100.0 / (double) delta_total;

    if (percent > 100.0) {
        percent = 100.0;
    }

    return percent;
}

/**
 * 采一帧 CPU 利用率：整机与每个逻辑核**共用同一对采样点**，不重复睡。
 *
 * 采样点缓存：距上次采样不够 TM_DEVICE_CPU_SAMPLE_MS 才补睡一会儿。
 * 刷新间隔本来就有 0.5~4 秒，正常节奏下一次都不用睡。
 *
 * @return false = 第一次调用（只记了基准，没有对比点），或 /proc/stat 读不到
 */
bool read_cpu_usage(TmCpuUsage *out)
{
    static TmCpuStatTable prev;
    static bool has_prev = false;
    static long long prev_ms = 0;

    memset(out, 0, sizeof(*out));

    out->total_percent = -1.0;
    out->total_kernel_percent = -1.0;
    out->logical_max = 0;

    for (int i = 0; i < TM_OUT_LOGICAL_MAX; i++) {
        out->logical_percent[i] = -1.0;
        out->logical_kernel_percent[i] = -1.0;
    }

    TmCpuStatTable now;

    if (!read_cpu_stat_table(&now)) {
        return false;
    }

    if (!has_prev) {

        prev = now;
        has_prev = true;
        prev_ms = monotonic_ms();

        return false;
    }

    long long elapsed = monotonic_ms() - prev_ms;

    if (elapsed < TM_DEVICE_CPU_SAMPLE_MS) {
        usleep((useconds_t) ((TM_DEVICE_CPU_SAMPLE_MS - elapsed) * 1000));
    }

    TmCpuStatTable later;

    if (!read_cpu_stat_table(&later)) {
        return false;
    }

    out->total_percent = cpu_percent_between(&later.summary, &prev.summary);

    // 整机内核时间与整机利用率同一对采样点
    out->total_kernel_percent = cpu_kernel_percent_between(&later.summary, &prev.summary);

    int limit = later.max_logical;

    if (limit > TM_OUT_LOGICAL_MAX) {
        limit = TM_OUT_LOGICAL_MAX;
    }

    out->logical_max = limit;

    for (int logical = 0; logical < limit; logical++) {

        // 两轮里都要有这一核：热插拔刚起来的核这一帧先不算
        if (!later.seen[logical] || !prev.seen[logical]) {
            continue;
        }

        out->logical_percent[logical] = cpu_percent_between(&later.cpu[logical],
                                                            &prev.cpu[logical]);

        out->logical_kernel_percent[logical] = cpu_kernel_percent_between(&later.cpu[logical],
                                                                         &prev.cpu[logical]);
    }

    prev = later;
    prev_ms = monotonic_ms();

    return true;
}

/** 读某一颗逻辑核的频率文件：/sys/devices/system/cpu/cpuN/cpufreq/<字段>（kHz）。 */
static bool read_cpu_core_field_khz(int cpu, const char *field, double *out)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/cpufreq/%s", cpu, field);

    return read_first_number(path, out);
}

/** 读一个频率簇的频率文件：/sys/devices/system/cpu/cpufreq/policyN/<字段>（kHz）。 */
static bool read_cpu_policy_field_khz(int policy, const char *field, double *out)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpufreq/policy%d/%s", policy, field);

    return read_first_number(path, out);
}

/**
 * 收集要看的 policyN 编号（/sys/devices/system/cpu/cpufreq/ 下一个 policy 一层簇）。
 *
 * 先列目录；列不了（SELinux 只给 search 不给 readdir）就按 policy0..policy7 固定
 * 编号逐个 stat —— policy 编号是连续的，不该因为列不了目录就当没有。
 *
 * @return 收集到的个数；0 = 一条都没拿到
 */
static int collect_cpu_policy_indices(int *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    int count = 0;

    DIR *dir = opendir("/sys/devices/system/cpu/cpufreq");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            // 只认 policyN；这一层还有别的节点，别当簇
            if (strncmp(entry->d_name, "policy", 6) != 0) {
                continue;
            }

            if (!is_pure_digits(entry->d_name + 6)) {
                continue;
            }

            if (count >= capacity) {
                break;
            }

            out[count++] = atoi(entry->d_name + 6);
        }

        closedir(dir);
    }

    for (int policy = 0; policy < 8 && count < capacity; policy++) {

        struct stat st;

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpufreq/policy%d", policy);

        if (stat(path, &st) != 0) {
            continue;
        }

        bool seen = false;

        for (int index = 0; index < count; index++) {
            if (out[index] == policy) {
                seen = true;
                break;
            }
        }

        if (!seen) {
            out[count++] = policy;
        }
    }

    return count;
}

/** 在全部 policy 上取 <字段> 的最大值；一个都没读到返回 false。 */
static bool read_cpu_policy_field_max(const char *field, double *out)
{
    int policy_ids[16];

    int policy_count = collect_cpu_policy_indices(policy_ids, (int) (sizeof(policy_ids) / sizeof(policy_ids[0])));

    double highest = -1.0;

    for (int index = 0; index < policy_count; index++) {

        double khz = 0.0;

        if (!read_cpu_policy_field_khz(policy_ids[index], field, &khz) || khz <= 0.0) {
            continue;
        }

        if (khz > highest) {
            highest = khz;
        }
    }

    if (highest > 0.0) {
        *out = highest;
        return true;
    }

    return false;
}

/**
 * 当前运行频率（kHz），也就是 UI 上的"速度"。
 *
 * 取值顺序（前一步拿不到才走下一步）：
 *   1. 所有 policy 里 cpuinfo_cur_freq 的最大值；
 *   2. 所有 policy 里 scaling_cur_freq 的最大值；
 *   3. policy 目录整个拿不到（老内核/权限），退回逐核 scaling_cur_freq 取最大，
 *      再不行才用逐核 cpuinfo_cur_freq 取最大。
 *
 * 为什么取最大：big.LITTLE 上小核簇满载也可能只有 1.x GHz，而大核簇正跑在 3.x GHz，
 * 取最大才是"这颗芯片此刻最高跑多快"；平均值会被一堆空闲小核拉低，cpu0 更是常年
 * 落在小核簇里。读不到的核/簇跳过，不按 0 计。
 */
static bool read_cpu_cur_freq_khz(double *out)
{
    if (read_cpu_policy_field_max("cpuinfo_cur_freq", out)) {
        return true;
    }

    if (read_cpu_policy_field_max("scaling_cur_freq", out)) {
        return true;
    }

    int cpu_ids[256];

    int cpu_count = collect_cpu_indices(cpu_ids, (int) (sizeof(cpu_ids) / sizeof(cpu_ids[0])));

    for (int round = 0; round < 2; round++) {

        const char *field = round == 0 ? "scaling_cur_freq" : "cpuinfo_cur_freq";

        double highest = -1.0;

        for (int index = 0; index < cpu_count; index++) {

            double khz = 0.0;

            if (!read_cpu_core_field_khz(cpu_ids[index], field, &khz) || khz <= 0.0) {
                continue;
            }

            if (khz > highest) {
                highest = khz;
            }
        }

        if (highest > 0.0) {
            *out = highest;
            return true;
        }
    }

    // 最后兜底：至少报个数，而不是整格空着
    return read_cpu_core_field_khz(0, "scaling_cur_freq", out);
}

/**
 * 基准速度（kHz）：把每颗逻辑核的 cpuinfo_max_freq 全部收齐，取**最小值**。
 *
 * 为什么用 cpuinfo_max_freq：整机上下只有它是"定值"（硬件 OPP 表写死的那一档），
 * scaling_cur_freq 会随负载抖动，拿来做基准速度每帧都变，没有意义。
 * 为什么取最小：全机的处理器在任何时刻都至少跑得动这一档，是最保守、不会虚报的
 * 基准值——大的那几档只属于大核/超大核，不能当成"整颗芯片都能跑到"来报。
 * 读不到的核跳过，不按 0 计（0 会把基准速度直接拉成 0）。
 */
static bool read_cpu_base_freq_khz(double *out)
{
    int cpu_ids[256];

    int cpu_count = collect_cpu_indices(cpu_ids, (int) (sizeof(cpu_ids) / sizeof(cpu_ids[0])));

    double lowest = -1.0;

    for (int index = 0; index < cpu_count; index++) {

        double khz = 0.0;

        if (!read_cpu_core_field_khz(cpu_ids[index], "cpuinfo_max_freq", &khz) || khz <= 0.0) {
            continue;
        }

        if (lowest < 0.0 || khz < lowest) {
            lowest = khz;
        }
    }

    if (lowest > 0.0) {
        *out = lowest;
        return true;
    }

    // 一颗都读不到：退回 cpu0 的 cpuinfo_max_freq，不掺别的频率口径
    double khz = 0.0;

    if (!read_cpu_core_field_khz(0, "cpuinfo_max_freq", &khz) || khz <= 0.0) {
        return false;
    }

    *out = khz;

    return true;
}

/**
 * 收集要看的 cpuN 编号。
 *
 * 先列 /sys/devices/system/cpu；列不了目录（SELinux 只给 search 不给 readdir）时，
 * 读内核固定文件 possible（"0-7" 就是 8 个逻辑核），再不行就按 cpu0..cpu15 的
 * 固定编号逐个 stat —— CPU 编号本来就是连续的，不该因为列不了目录就数不出核来。
 *
 * @return 收集到的个数；0 = 一条都没拿到
 */
static int collect_cpu_indices(int *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    int count = 0;

    DIR *dir = opendir("/sys/devices/system/cpu");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            // 只认 cpuN；cpufreq / cpuidle / policyN 这些不是处理器
            if (strncmp(entry->d_name, "cpu", 3) != 0) {
                continue;
            }

            if (!is_pure_digits(entry->d_name + 3)) {
                continue;
            }

            if (count >= capacity) {
                break;
            }

            out[count++] = atoi(entry->d_name + 3);
        }

        closedir(dir);
    }

    if (count > 0) {
        return count;
    }

    // 第二档：possible 是内核固定导出的"这颗 SoC 一共有哪些 CPU"，格式 "0-7" / "0-3,4-7"
    char possible[128];

    if (read_text_file("/sys/devices/system/cpu/possible", possible, (int) sizeof(possible)) > 0) {

        const char *cursor = possible;

        while (*cursor != '\0' && count < capacity) {

            if (*cursor < '0' || *cursor > '9') {
                cursor++;
                continue;
            }

            int from = 0;

            while (*cursor >= '0' && *cursor <= '9') {
                from = from * 10 + (*cursor - '0');
                cursor++;
            }

            int to = from;

            if (*cursor == '-') {

                cursor++;

                if (*cursor < '0' || *cursor > '9') {
                    break;
                }

                to = 0;

                while (*cursor >= '0' && *cursor <= '9') {
                    to = to * 10 + (*cursor - '0');
                    cursor++;
                }
            }

            for (int value = from; value <= to && count < capacity; value++) {
                out[count++] = value;
            }
        }
    }

    if (count > 0) {
        return count;
    }

    // 第三档：固定编号。cpu 目录存在就是一颗（拓扑读不读得到另算）
    for (int cpu = 0; cpu < 16 && count < capacity; cpu++) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d", cpu);

        struct stat info;

        if (stat(path, &info) == 0) {
            out[count++] = cpu;
        }
    }

    return count;
}

/** /sys/devices/system/cpu 下 cpuN 的个数 = 逻辑处理器数；-1 = 一条都拿不到。 */
static int count_logical_cpus(void)
{
    int cpu_ids[256];

    int count = collect_cpu_indices(cpu_ids, (int) (sizeof(cpu_ids) / sizeof(cpu_ids[0])));

    return count > 0 ? count : -1;
}

/**
 * 数处理器插槽与物理核心。
 *
 * 优先级（越靠前越可信）：
 *   1) cpuN/topology/core_cpus_list       同一物理核心包含哪些逻辑 CPU
 *   2) cpuN/topology/thread_siblings_list 老内核的同义文件，作用完全相同
 *   3) physical_package_id + cluster_id + core_id  前两者都没有时的备用组合
 *   4) 都拿不到  cores 返回 -1（未知），**不拿逻辑 CPU 数冒充物理核心数**
 *
 * 为什么不再以 core_id 为首选：cluster_id / core_id 是平台相关的拓扑编号，
 * 不同簇里 core_id 会重复（big.LITTLE 的小核簇与大核簇各有 0..3），
 * 只按 package + core_id 去重会把 8 核并成 4 核 —— 这正是旧实现的坑。
 * 内核直接给的"同核心 CPU 集合"才是我们要的那个关系。
 *
 * cluster_id 不是插槽：physical_package_id 表示处理器封装（SoC / 插槽），
 * 手机多簇共用一颗 SoC，package 通常都是 0。
 *
 * 列表会先展开成升序编号再组键（"0-3,5" 与 "0,1,2,3,5" 认作同一个核心），
 * 不靠字符串字面比较。统计口径是"存在的 CPU"（枚举 cpuN 目录），不看 online：
 * 任务管理器要的是这颗芯片有几个物理核心，热插拔下线不该让核心数变少。
 *
 * @param sockets_out 插槽数：读到 package 就按实际去重数，读不到按单 SoC = 1；一个核都没有给 -1
 * @param cores_out   物理核心数；拓扑不可靠或一个都没读到给 -1
 */
static void count_sockets_and_cores(int *sockets_out, int *cores_out)
{
    *sockets_out = -1;

    *cores_out = -1;

    int cpu_ids[256];

    int cpu_count = collect_cpu_indices(cpu_ids, (int) (sizeof(cpu_ids) / sizeof(cpu_ids[0])));

    if (cpu_count <= 0) {
        return;
    }

    // 每个物理核心一条键：有同核心列表时是 "S:0,4"，否则是 "F:0:1:2"（封装:簇:核）
    char core_keys[128][96];

    int core_key_count = 0;

    int socket_ids[64];

    int socket_id_count = 0;

    int cpu_seen = 0;

    // 只要有一台 cpuN 拿不到可靠拓扑，整台机器的核心数就不下结论
    bool topology_failed = false;

    for (int cpu_slot = 0; cpu_slot < cpu_count; cpu_slot++) {

        int cpu = cpu_ids[cpu_slot];

        cpu_seen++;

        char path[TM_PATH_MAX];

        // ---------------- 插槽：physical_package_id ----------------

        double package = 0.0;

        bool has_package = false;

        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/topology/physical_package_id", cpu);

        if (read_first_number(path, &package)) {

            has_package = true;

            int id = (int) package;

            bool known = false;

            for (int index = 0; index < socket_id_count; index++) {

                if (socket_ids[index] == id) {
                    known = true;
                    break;
                }
            }

            if (known) {
                // 已经记过这个封装
            } else if (socket_id_count < (int) (sizeof(socket_ids) / sizeof(socket_ids[0]))) {
                socket_ids[socket_id_count++] = id;
            } else {
                topology_failed = true;
            }
        }

        // ---------------- 核心：同核心 CPU 集合 ----------------

        char list[96];

        list[0] = '\0';

        bool has_list = false;

        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/topology/core_cpus_list", cpu);

        if (read_text_file(path, list, (int) sizeof(list)) > 0) {
            has_list = true;
        }

        if (!has_list) {

            // 老内核叫 thread_siblings_list，内容与 core_cpus_list 同义
            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/topology/thread_siblings_list", cpu);

            if (read_text_file(path, list, (int) sizeof(list)) > 0) {
                has_list = true;
            }
        }

        char key[96];

        key[0] = '\0';

        if (has_list) {

            // 去掉首尾空白（文件末尾常带一个换行）
            int head = 0;

            while (list[head] == ' ' || list[head] == '\t' ||
                   list[head] == '\r' || list[head] == '\n') {
                head++;
            }

            int tail = (int) strlen(list) - 1;

            while (tail >= head && (list[tail] == ' ' || list[tail] == '\t' ||
                                    list[tail] == '\r' || list[tail] == '\n')) {
                tail--;
            }

            // 把 "0-3,5" 展开成升序编号，再拼成键
            int siblings[96];

            int sibling_count = 0;

            bool parsed = head <= tail;

            const char *cursor = list + head;

            while (parsed && cursor <= list + tail && *cursor != '\0') {

                if (*cursor == ',' || *cursor == ' ' || *cursor == '\t') {
                    cursor++;
                    continue;
                }

                if (*cursor < '0' || *cursor > '9') {
                    parsed = false;
                    break;
                }

                int from = 0;

                while (*cursor >= '0' && *cursor <= '9') {
                    from = from * 10 + (*cursor - '0');
                    cursor++;
                }

                int to = from;

                if (*cursor == '-') {

                    cursor++;

                    if (*cursor < '0' || *cursor > '9') {
                        parsed = false;
                        break;
                    }

                    to = 0;

                    while (*cursor >= '0' && *cursor <= '9') {
                        to = to * 10 + (*cursor - '0');
                        cursor++;
                    }
                }

                if (to < from) {
                    parsed = false;
                    break;
                }

                for (int value = from; value <= to; value++) {

                    if (sibling_count >= (int) (sizeof(siblings) / sizeof(siblings[0])) ||
                        value >= (int) (sizeof(siblings) / sizeof(siblings[0]))) {
                        parsed = false;
                        break;
                    }

                    siblings[sibling_count++] = value;
                }
            }

            if (parsed && sibling_count > 0) {

                // 插入排序：集合很小（最多几十个逻辑核），不值得为它引 qsort 的比较函数
                for (int index = 1; index < sibling_count; index++) {

                    int value = siblings[index];

                    int slot = index - 1;

                    while (slot >= 0 && siblings[slot] > value) {
                        siblings[slot + 1] = siblings[slot];
                        slot--;
                    }

                    siblings[slot + 1] = value;
                }

                size_t used = 0;

                used += (size_t) snprintf(key + used, sizeof(key) - used, "S:");

                int previous = -1;

                for (int index = 0; index < sibling_count && used < sizeof(key) - 4; index++) {

                    if (siblings[index] == previous) {
                        continue;
                    }

                    previous = siblings[index];

                    used += (size_t) snprintf(key + used, sizeof(key) - used,
                                              (used <= 2) ? "%d" : ",%d", siblings[index]);
                }

            } else {
                // 读到了但解析不了：别拿这段文本当键，退回备用组合
                has_list = false;
            }
        }

        if (!has_list) {

            // 备用：封装 + 簇 + 核（三者齐全才算可靠）
            double cluster = 0.0;

            double core = 0.0;

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/topology/cluster_id", cpu);

            bool has_cluster = read_first_number(path, &cluster);

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/topology/core_id", cpu);

            bool has_core = read_first_number(path, &core);

            if (!has_cluster || !has_core) {
                topology_failed = true;
                continue;
            }

            snprintf(key, sizeof(key), "F:%d:%d:%d",
                     has_package ? (int) package : 0, (int) cluster, (int) core);
        }

        // ---------------- 对物理核心标识去重 ----------------

        bool known = false;

        for (int index = 0; index < core_key_count; index++) {

            if (strcmp(core_keys[index], key) == 0) {
                known = true;
                break;
            }
        }

        if (known) {
            continue;
        }

        if (core_key_count >= (int) (sizeof(core_keys) / sizeof(core_keys[0])) ||
            key[0] == '\0') {
            topology_failed = true;
            continue;
        }

        snprintf(core_keys[core_key_count], sizeof(core_keys[core_key_count]), "%s", key);

        core_key_count++;
    }

    if (cpu_seen == 0) {
        return;
    }

    // 手机多簇共用一颗 SoC：内核给了 package 就按实际去重数，没给就按单封装
    *sockets_out = (socket_id_count > 0) ? socket_id_count : 1;

    // 有任何一个核的拓扑不可靠，就不报一个"看起来合理"的总数
    if (!topology_failed && core_key_count > 0) {
        *cores_out = core_key_count;
    }
}

/** 解析 32K / 2M / 1024 这种容量文本，统一成 KB；-1 = 不认识。 */
static int parse_size_kb(const char *text)
{
    if (text == NULL || text[0] == '\0') {
        return -1;
    }

    char *end = NULL;
    double value = strtod(text, &end);

    if (end == text) {
        return -1;
    }

    while (*end == ' ' || *end == '\t') {
        end++;
    }

    switch (*end) {

        case 'K':
        case 'k':
            return (int) (value + 0.5);

        case 'M':
        case 'm':
            return (int) (value * 1024.0 + 0.5);

        case 'G':
        case 'g':
            return (int) (value * 1024.0 * 1024.0 + 0.5);

        case '\0':
        case '\n':
        case '\r':
            return (int) (value + 0.5);

        default:
            return -1;
    }
}

/**
 * 【外部实现接口】查某一级缓存的容量。返回值只表示状态，容量从 out_kb 带回。
 *
 * 入参（调用方保证）：
 *   cpu_id      设备序号，和协议里 D 行 id=cpuN 的 N 完全一致，从 0 开始。
 *               一颗芯片（一个插槽）一个序号；单芯片手机永远是 0；双路设备是 0 和 1。
 *   device_path 该实例的采集位置，形如 /sys/devices/system/cpu/cpu0
 *               （拓扑读不到时退化成 cpu0 的目录）。串归调用方所有，只读，别保存指针。
 *   level       1 / 2 / 3，分别对应 L1 / L2 / L3，不会传 0 或负数。
 *
 * 出参（你来填）：
 *   out_kb      该级缓存的总量，单位 KB。L1 若分数据与指令两条，请返回两者之和
 *               （页面上的 L1 缓存是总量口径）。只有返回 TM_QUERY_OK 时这个值才被读取。
 *
 * 返回状态（取值含义见 TmQueryStatus）：
 *   TM_QUERY_OK              查到了，用 out_kb；
 *   TM_QUERY_NOT_AVAILABLE   这台机器确实没有这一级（例如没有 L3），页面显示不支持；
 *   TM_QUERY_UNSUPPORTED     这一版没实现，或平台不提供这项信息；
 *   TM_QUERY_READ_FAILED     去查了但失败（节点打不开、解析不出）；
 *   TM_QUERY_ACCESS_DENIED   有这项信息但当前身份没权限。
 *
 * 调用方约定：先扫这台实例的 cache/index* 算出自己的一份，再调本函数；
 * 本函数返回 OK 才覆盖自己的值，不是 OK 就保留自己的值；
 * 两边都没有，就把本函数的状态发出去（客户端显示占位，不是 0）。
 *
 * 调用频率：与性能页采样一致（最快 0.5 秒一次，每级各调一次）。
 * 如果来源是一次性读取（属性、映射表），建议在函数内部做静态缓存，别每帧重查。
 */
static TmQueryStatus query_cache_kb_external(int cpu_id, const char *device_path, int level,
                                             int *out_kb)
{
    (void) cpu_id;
    (void) device_path;
    (void) level;
    (void) out_kb;

    // TODO(ELF 侧)：查到了返回 TM_QUERY_OK 并把 KB 写进 *out_kb；没有这一级返回 TM_QUERY_NOT_AVAILABLE
    return TM_QUERY_UNSUPPORTED;
}

/**
 * 扫一台处理器实例的 cache/index*，把 L1 / L2 / L3 各自求和（KB）。
 *
 * 为什么一次扫完再分账：三级各扫一遍就是最多 48 次 open，每帧都做一遍没必要，
 * 一次遍历 16 个 index 就够。
 *
 * 三个细节：
 * 1. index 编号读不到就跳过，不 break：多数内核是 0..N 连续，个别内核有空洞，
 *    break 会把后面的级一起丢掉；
 * 2. size 读不到时用 ways × sets × line_size 推算，这三个字段比 size 更常在，
 *    也更容易被 SELinux 放行；
 * 3. 同一级有多条（L1 分数据与指令）就相加，页面上的 L1 缓存是总量。
 *
 * @param cpu_dir   该实例的 sysfs 目录，例如 /sys/devices/system/cpu/cpu0
 * @param out_kb    长度 3，分别接 L1 / L2 / L3；-1 = 这一级没扫到
 * @param out_status 长度 3，说清"为什么没扫到"，三种可能：
 *                   OK             读到了
 *                   ACCESS_DENIED  有这一级，但尺寸读不出来（没权限 / 解析失败）
 *                   NOT_AVAILABLE  内核列出来的缓存里根本没有这一级（例如没有 L3）
 *                   UNSUPPORTED    连 <cpu_dir>/cache 目录都没有（平台不导出缓存信息）
 */
static void probe_cpu_cache_from_sysfs(const char *cpu_dir, int out_kb[3],
                                       TmQueryStatus out_status[3])
{
    out_kb[0] = -1;
    out_kb[1] = -1;
    out_kb[2] = -1;

    out_status[0] = TM_QUERY_NOT_AVAILABLE;
    out_status[1] = TM_QUERY_NOT_AVAILABLE;
    out_status[2] = TM_QUERY_NOT_AVAILABLE;

    int total[3] = { 0, 0, 0 };

    bool found[3] = { false, false, false };

    // 先探 cache 目录本身。这里只用 stat：它只需要父目录的 search 权限，不列目录内容。
    // 某些 SELinux 域允许打开 cache/indexN/level，却不让 readdir —— 用 opendir 判存在
    // 会把"读得到"误判成"读不了"，所以目录在就继续按固定 index 编号往下读。
    char cache_dir[TM_PATH_MAX];

    snprintf(cache_dir, sizeof(cache_dir), "%s/cache", cpu_dir);

    struct stat cache_info;

    if (stat(cache_dir, &cache_info) != 0) {

        bool denied = (errno == EACCES || errno == EPERM);

        for (int level = 0; level < 3; level++) {
            out_status[level] = denied ? TM_QUERY_ACCESS_DENIED : TM_QUERY_UNSUPPORTED;
        }

        return;
    }

    // 这一级在不在：index 里有 level=N 就算"有"，尺寸读不出来另算
    bool seen[3] = { false, false, false };

    for (int index = 0; index < 16; index++) {

        char path[TM_PATH_MAX];
        double level_value = 0.0;

        snprintf(path, sizeof(path), "%s/cache/index%d/level", cpu_dir, index);

        if (!read_first_number(path, &level_value)) {
            continue;
        }

        int level = (int) level_value;

        if (level < 1 || level > 3) {
            continue;
        }

        seen[level - 1] = true;

        int size_kb = -1;

        char size_text[64];

        snprintf(path, sizeof(path), "%s/cache/index%d/size", cpu_dir, index);

        if (read_text_file(path, size_text, (int) sizeof(size_text)) >= 0) {

            trim_text(size_text);

            size_kb = parse_size_kb(size_text);
        }

        if (size_kb <= 0) {

            double ways = 0.0;
            double sets = 0.0;
            double line = 0.0;

            snprintf(path, sizeof(path), "%s/cache/index%d/ways_of_associativity", cpu_dir, index);

            bool has_ways = read_first_number(path, &ways);

            snprintf(path, sizeof(path), "%s/cache/index%d/number_of_sets", cpu_dir, index);

            bool has_sets = read_first_number(path, &sets);

            snprintf(path, sizeof(path), "%s/cache/index%d/coherency_line_size", cpu_dir, index);

            bool has_line = read_first_number(path, &line);

            if (has_ways && has_sets && has_line && ways > 0.0 && sets > 0.0 && line > 0.0) {

                size_kb = (int) (ways * sets * line / 1024.0 + 0.5);
            }
        }

        if (size_kb > 0) {

            total[level - 1] += size_kb;

            found[level - 1] = true;
        }
    }

    for (int level = 1; level <= 3; level++) {

        if (found[level - 1]) {

            out_kb[level - 1] = total[level - 1];
            out_status[level - 1] = TM_QUERY_OK;

        } else if (seen[level - 1]) {

            // 内核明确列了这一级，但尺寸拿不到：这是"读不到"，不是"没有"
            out_status[level - 1] = TM_QUERY_ACCESS_DENIED;

        } else {

            out_status[level - 1] = TM_QUERY_NOT_AVAILABLE;
        }
    }
}

/**
  * 枚举处理器实例：一个 physical_package_id 算一颗芯片（插槽）。
  *
  * 为什么不是按 cpuN 枚举：sysfs 里的 cpuN 是逻辑处理器，几十个核会变成几十个设备，
  * 而客户端要的是"这颗芯片的利用率 / 频率 / 拓扑 / 缓存"。同一颗芯片里的多个核
  * 只记进 cpu_ids 成员列表，不拆成多个设备。
  *
  * @param cpu_ids_out 可选；第 i 个设备的成员逻辑核号列表，形如 0,1,2,3
  * @return 设备条数；拓扑完全读不到时整台机器按一颗芯片处理
  */
 /**
  * 枚举 CPU 设备：一颗芯片一台，同芯片的逻辑核收进 logical_ids。
  *
  * 为什么按 physical_package_id 去重：Android 手机的 8 个核同属一颗芯片，
  * 按核心拆会变成 8 台 CPU 设备，还会把整机利用率重复报 8 次。去重以后
  * 一台设备就是用户理解的一颗处理器；双路设备才有第 2 台。
  *
  * 拓扑读不到时退化：整机当一颗芯片，位置用 cpu0 的目录。
  *
  * @param out      实例数组，由本函数填 id / device_id / package_id / path / logical_ids
  * @param capacity 数组长度
  * @return 实例条数（0 = 这台机器一个核都没有）
  */
int enumerate_cpu_devices(TmCpuDevice *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    memset(out, 0, sizeof(TmCpuDevice) * (size_t) capacity);

    int logical = count_logical_cpus();

    int package_ids[TM_OUT_DEVICE_MAX];
    int package_count = 0;

    if (logical > 0) {

        for (int cpu = 0; cpu < logical && cpu < TM_STAT_CPU_MAX; cpu++) {

            char path[TM_PATH_MAX];
            double package = 0.0;

            snprintf(path, sizeof(path),
                     "/sys/devices/system/cpu/cpu%d/topology/physical_package_id", cpu);

            if (!read_first_number(path, &package)) {
                continue;
            }

            int package_id = (int) package;
            int slot = -1;

            for (int i = 0; i < package_count; i++) {

                if (package_ids[i] == package_id) {
                    slot = i;
                    break;
                }
            }

            if (slot < 0) {

                if (package_count >= capacity) {
                    break;
                }

                slot = package_count;

                package_ids[slot] = package_id;
                out[slot].package_id = package_id;

                package_count++;
            }

            if (out[slot].logical_count < TM_OUT_LOGICAL_MAX) {
                out[slot].logical_ids[out[slot].logical_count++] = cpu;
            }

            // 位置记这颗芯片第一个出现的 cpuN 目录
            if (out[slot].path[0] == '\0') {

                snprintf(out[slot].path, sizeof(out[slot].path),
                         "/sys/devices/system/cpu/cpu%d", cpu);
            }
        }
    }

    // 拓扑读不到：整机当一颗芯片，位置退到 cpu0
    if (package_count == 0) {

        package_count = 1;

        out[0].package_id = 0;
    }

    for (int i = 0; i < package_count; i++) {

        out[i].device_id = i;

        snprintf(out[i].id, sizeof(out[i].id), "cpu%d", i);

        if (out[i].path[0] == '\0') {

            snprintf(out[i].path, sizeof(out[i].path), "/sys/devices/system/cpu/cpu0");
        }
    }

    return package_count;
}

// ============================================================
// 留给外部实现的三个位置
//
// 三个函数都返回 TmQueryStatus：TM_QUERY_OK 才代表"我有准确值"，
// 其它状态表示没有，客户端显示占位，不会当成 0 或空串。
// 要补实现时直接改函数体，不要改返回值的含义。
// ============================================================

/**
 * 【外部实现接口】处理器商品名。返回值只表示状态，名字从 out 带回。
 *
 * 入参（调用方保证）：
 *   cpu_id      设备序号，和协议里 D 行 id=cpuN 的 N 完全一致，从 0 开始。
 *               一颗芯片（一个插槽）一个序号；单芯片手机永远是 0；双路设备是 0 和 1。
 *               协议里的 cpu_id 字段就是这个值，两边不会对不上。
 *   device_path 该实例的采集位置，形如 /sys/devices/system/cpu/cpu0
 *               （拓扑读不到时退化成 cpu0 的目录）。多芯片时它是这颗芯片第一个
 *               cpuN 目录，可以拿它继续拼子路径，例如 cpu0/cpufreq、cpu0/topology。
 *               串归调用方所有，只读，别改也别保存指针。
 *
 * 出参（你来填）：
 *   out         写 UTF-8 名字的缓冲，要人看得懂的名字（骁龙 8 Gen 3 / Exynos 2400），
 *               不要内核内部型号。
 *   capacity    out 的字节数（含结尾 0）。写满就算截断，调用方按 TM_QUERY_OK 用。
 *
 * 返回状态（取值含义见 TmQueryStatus）：
 *   TM_QUERY_OK              查到了，用 out 里的名字；
 *   TM_QUERY_UNSUPPORTED     这一版没实现，或平台不提供这项信息；
 *   TM_QUERY_READ_FAILED     去查了但失败（节点打不开、内容解析不出）；
 *   TM_QUERY_ACCESS_DENIED   有这项信息但当前身份没权限；
 *   TM_QUERY_NOT_AVAILABLE   这台机器没有这项信息。
 *
 * 调用方约定：先自己从 /proc/cpuinfo 探一份（Hardware / model name），再调本函数；
 * 本函数返回 OK 才覆盖自己的值，不是 OK 就保留自己的值；
 * 两边都没有才把本函数的状态发出去（客户端显示占位，不是空串）。
 */
static TmQueryStatus query_cpu_name_external(int cpu_id, const char *device_path,
                                             char *out, int capacity)
{
    (void) cpu_id;
    (void) device_path;
    (void) out;
    (void) capacity;

    // TODO(ELF 侧)：查到了返回 TM_QUERY_OK 并把名字写进 out；否则返回对应状态
    return TM_QUERY_UNSUPPORTED;
}

/**
 * 【外部实现接口】虚拟化方案。返回值只表示状态，字符串从 out 带回。
 *
 * 入参（调用方保证）：
 *   cpu_id      同上，设备序号（D 行 id=cpuN 的 N）。
 *   device_path 同上，该实例的 sysfs 位置。
 *   cpu_name    刚刚查到的处理器商品名，可能为空串（名字查不到时）。
 *               需要按平台区分虚拟化方案时用它，例如同名 SoC 的衍生型号。
 *               只读，别改，也别保存指针。
 *
 * 出参格式（由你拼好整串，调用方不做任何加工，直接当字段值发出去）：
 *   第一段是基础状态，只能是这三个词之一：已启用 / 已禁用 / 不支持；
 *   第二段是扩展状态，紧跟在基础状态后面、用半角括号括起，可以为空；
 *   扩展状态为空时连括号都不要写，界面据此不显示括号。
 *   例子：
 *     已启用(AMD-V)
 *     已启用(KVM)
 *     已禁用(硬件支持，内核未启用)
 *     不支持
 *
 * 返回状态含义与 query_cpu_name_external 完全一致，同一个 TmQueryStatus。
 *
 * 调用方约定：先自己看 /dev/kvm 与 cpuinfo 的 flags 探一份，再调本函数；
 * 本函数返回 OK 才覆盖，不是 OK 就保留自己的；两边都没有才把状态发出去。
 */
static TmQueryStatus query_virtualization_external(int cpu_id, const char *device_path,
                                                   const char *cpu_name,
                                                   char *out, int capacity)
{
    (void) cpu_id;
    (void) device_path;
    (void) cpu_name;
    (void) out;
    (void) capacity;

    // TODO(ELF 侧)：查到了返回 TM_QUERY_OK 并按上面的格式写 out；否则返回对应状态
    return TM_QUERY_UNSUPPORTED;
}

/**
 * 在 /proc/cpuinfo 的一行里按空白分隔找 token，用于判断 flags 里有没有 vmx / svm / virt。
 * 必须卡边界：直接 strstr 会把 virtualization 里的 virt 也算成扩展支持。
 *
 * 目前没有调用点（虚拟化探测已改成只看 /dev 节点，见 probe_virtualization_internal），
 * 保留是因为以后若要判"硬件支持但内核没启用"还会用它；
 * unused 属性只是压掉 -Wunused-function 的告警，不代表它不该留。
 */
__attribute__((unused))
static bool flags_has_token(const char *line, const char *token)
{
    size_t length = strlen(token);

    const char *cursor = line;

    while ((cursor = strstr(cursor, token)) != NULL) {

        bool left_ok = (cursor == line) || (cursor[-1] == ' ' || cursor[-1] == '\t');

        char next = cursor[length];

        bool right_ok = (next == '\0' || next == ' ' || next == '\t'
                         || next == '\n' || next == '\r');

        if (left_ok && right_ok) {
            return true;
        }

        cursor += length;
    }

    return false;
}

// ============================================================
// SoC 名称库（CPUNAME.txt）与厂商规范化
// ============================================================
//
// 内核给的名字经常不能用：ARM 设备的 /proc/cpuinfo 里 Hardware 可能是空的，
// 也可能只写一个 cpu。这种时候改走 Android 侧读到的 SoC 型号：
//   ro.soc.model          SM8635
//   ro.soc.manufacturer   QTI
// 这两个值连同名称库路径，都由 Kotlin 侧在**每次设备快照请求**里带进来
// （见文件头第二节的 typeCode=5 附加参数），不再走进程启动参数：
// 这样设备模型只有一个维护入口，上层数据随时能刷新，本进程也不必重启。
//
// 本块只做三件事：查库、规范化厂商、拼最终展示名。
// 不读系统属性、不猜平台 —— 型号与厂商都是别人给的事实。

/**
 * 名称库路径（请求键 cpu_name_db，最多两个）。
 *
 * Kotlin 侧会同时给两份：外部私有目录一份（shell / root 身份读得到），
 * 应用内部 files 目录一份（应用自身身份读得到）。
 * 查表时按给的顺序试，第一个读得开的为准 —— 换身份起进程也不需要换路径。
 */
char g_cpu_name_database[2][TM_PATH_MAX];

/** 实际给了几条名称库路径（0 = 没有库，只能回退 厂商 + 大写型号）。 */
int g_cpu_name_database_count = 0;

/** SoC 型号标识（请求键 soc_model），比较时不分大小写，回退拼名时转大写。 */
char g_soc_model[TM_OUT_VALUE_MAX] = {0};

/** SoC 厂商原始值（请求键 soc_manufacturer），可能形如 QTI / QCOM / MTK / SAMSUNG。 */
char g_soc_manufacturer[TM_OUT_VALUE_MAX] = {0};

/**
 * 上下文是否变过（名称探测的缓存失效标记）。
 *
 * 快照每帧都会把这三个值带上，但值本身开机不会变；逐帧重读 cpuinfo 与库文件
 * 纯属浪费，所以只在值真的换了才让下次探测重算。
 */
bool g_cpu_name_context_dirty = true;

/**
 * 内核给的这行字是不是一个「能给人看的」处理器名。
 *
 * 空、纯空白、cpu、unknown、armv8、0x0 都算没拿到 —— 安卓设备上这些值很常见，
 * 直接显示出来比显示占位还难看，所以一律当作失败，交给 SoC 那一套去补。
 */
static bool cpu_name_is_usable(const char *text)
{
    if (text == NULL) {
        return false;
    }

    while (*text == ' ' || *text == '\t') {
        text++;
    }

    if (*text == '\0') {
        return false;
    }

    char trimmed[TM_OUT_VALUE_MAX];

    snprintf(trimmed, sizeof(trimmed), "%s", text);

    size_t length = strlen(trimmed);

    while (length > 0) {

        char tail = trimmed[length - 1];

        if (tail != ' ' && tail != '\t' && tail != '\n' && tail != '\r') {
            break;
        }

        trimmed[--length] = '\0';
    }

    if (length == 0) {
        return false;
    }

    if (text_equal_ignore_case(trimmed, "cpu")
        || text_equal_ignore_case(trimmed, "unknown")
        || text_equal_ignore_case(trimmed, "armv8")
        || text_equal_ignore_case(trimmed, "armv7")
        || text_equal_ignore_case(trimmed, "0x0")) {

        return false;
    }

    return true;
}

/**
 * 在 CPUNAME.txt 里按型号查商品名。
 *
 * 文件格式（UTF-8，一型号一行）：
 *   型号 展示名
 * 例如：
 *   sm8635 Qualcomm(R) Snapdragon(R) 8s Gen 3
 * 约定：# 开头是注释，空行忽略，型号不分大小写，
 * 第一个空格之后的整段（含空格）都是展示名。
 *
 * 匹配分两档，整词优先：
 *   1) 整词相等：型号完全对得上，最高优先级；
 *   2) 前缀命中：型号后面还挂着变体尾巴（字母 / 斜杠 / 连字符 / 下划线），
 *      取库里最长的那个前缀。联发科的变体写法（MT6833V/NZA、MT6877V/TTZA、
 *      MT6899Z/ZA）就靠这一档吃下来。数字尾巴不算变体，免得 MT6833 吃掉
 *      一个并不存在的 MT6833x。
 *
 * @param model     要查的型号，例如 SM8635
 * @param out       查到的展示名（调用方给的缓冲）
 * @param capacity  out 的字节数
 * @return true = 命中，out 里是展示名；false = 没库 / 读不了 / 没这个型号
 */
static bool cpu_name_database_lookup(const char *model, char *out, size_t capacity)
{
    out[0] = '\0';

    if (g_cpu_name_database_count == 0 || model == NULL || model[0] == '\0') {
        return false;
    }

    size_t model_length = strlen(model);

    // 前缀命中只是兜底，整词命中要压过它：先扫完所有库文件，
    // 前缀只记最长的那个，整词命中当场定格。
    size_t best_prefix_length = 0;
    char best_name[256];

    best_name[0] = '\0';

    for (int file_index = 0; file_index < g_cpu_name_database_count; file_index++) {

        const char *path = g_cpu_name_database[file_index];

        if (path[0] == '\0' || access(path, R_OK) != 0) {
            continue;
        }

        FILE *file = fopen(path, "r");

        if (file == NULL) {
            continue;
        }

        char line[512];
        bool exact = false;

        while (!exact && fgets(line, sizeof(line), file) != NULL) {

            size_t length = strlen(line);

            while (length > 0) {

                char tail = line[length - 1];

                if (tail != '\n' && tail != '\r') {
                    break;
                }

                line[--length] = '\0';
            }

            char *cursor = line;

            while (*cursor == ' ' || *cursor == '\t') {
                cursor++;
            }

            if (*cursor == '\0' || *cursor == '#') {
                continue;
            }

            // 第一段是型号
            char *token_start = cursor;

            while (*cursor != '\0' && *cursor != ' ' && *cursor != '\t') {
                cursor++;
            }

            char token[64];
            size_t token_length = (size_t) (cursor - token_start);

            if (token_length == 0 || token_length >= sizeof(token)) {
                continue;
            }

            memcpy(token, token_start, token_length);

            token[token_length] = '\0';

            while (*cursor == ' ' || *cursor == '\t') {
                cursor++;
            }

            if (*cursor == '\0') {
                continue;
            }

            if (text_equal_ignore_case(token, model)) {

                snprintf(best_name, sizeof(best_name), "%s", cursor);

                exact = true;

                continue;
            }

            // 变体尾巴的判定：库里的型号得比要查的短、至少 4 个字符、
            // 要查的型号在分界那一位不是数字，再比前缀，取最长的
            if (token_length >= 4 && token_length < model_length
                && !isdigit((unsigned char) model[token_length])
                && token_length > best_prefix_length
                && text_prefix_ignore_case(model, token, token_length)) {

                best_prefix_length = token_length;

                snprintf(best_name, sizeof(best_name), "%s", cursor);
            }
        }

        fclose(file);

        if (exact) {

            snprintf(out, capacity, "%s", best_name);

            return true;
        }
    }

    if (best_prefix_length > 0) {

        snprintf(out, capacity, "%s", best_name);

        return true;
    }

    return false;
}

/**
 * 规范厂商名：QTI / QCOM / Qualcomm 统一成 Qualcomm(R)，MTK 统一成 MediaTek。
 *
 * (R) 用半角括号写在名字里，不依赖字体里的 ® 字形：
 * 这一层的输出要进复制文本、要进等宽显示，纯 ASCII 最稳。
 * 认不出来的厂商原样保留 —— 不要给它硬套一个商标。
 */
static void normalize_soc_manufacturer(const char *manufacturer, char *out, size_t capacity)
{
    out[0] = '\0';

    if (manufacturer == NULL || manufacturer[0] == '\0') {
        return;
    }

    if (text_equal_ignore_case(manufacturer, "QTI")
        || text_equal_ignore_case(manufacturer, "QCOM")
        || text_equal_ignore_case(manufacturer, "Qualcomm")
        || text_equal_ignore_case(manufacturer, "Qualcomm Technologies, Inc")) {

        snprintf(out, capacity, "%s", "Qualcomm(R)");

        return;
    }

    if (text_equal_ignore_case(manufacturer, "MTK")
        || text_equal_ignore_case(manufacturer, "MediaTek")) {

        snprintf(out, capacity, "%s", "MediaTek");

        return;
    }

    if (text_equal_ignore_case(manufacturer, "SAMSUNG")) {

        snprintf(out, capacity, "%s", "Samsung");

        return;
    }

    if (text_equal_ignore_case(manufacturer, "GOOGLE")) {

        snprintf(out, capacity, "%s", "Google");

        return;
    }

    snprintf(out, capacity, "%s", manufacturer);
}

/**
 * 拼 SoC 的最终展示名。
 *
 * 顺序：
 *   1. 名称库命中 → 直接用库里的商品名（Qualcomm(R) Snapdragon(R) 8s Gen 3）；
 *   2. 没命中 → 规范化厂商 + 空格 + 大写型号（Qualcomm(R) SM8635）；
 *   3. 厂商也没有 → 只给大写型号。
 *
 * @return false = 连型号都没有，调用方按没数据发出去
 */
static bool build_soc_display_name(char *out, size_t capacity)
{
    out[0] = '\0';

    if (g_soc_model[0] == '\0') {
        return false;
    }

    if (cpu_name_database_lookup(g_soc_model, out, capacity)) {
        return true;
    }

    char manufacturer[TM_OUT_VALUE_MAX];

    normalize_soc_manufacturer(g_soc_manufacturer, manufacturer, sizeof(manufacturer));

    char model_upper[TM_OUT_VALUE_MAX];

    size_t length = strlen(g_soc_model);

    if (length >= sizeof(model_upper)) {
        length = sizeof(model_upper) - 1;
    }

    for (size_t index = 0; index < length; index++) {
        model_upper[index] = ascii_upper(g_soc_model[index]);
    }

    model_upper[length] = '\0';

    if (manufacturer[0] != '\0') {

        snprintf(out, capacity, "%s %s", manufacturer, model_upper);

    } else {

        snprintf(out, capacity, "%s", model_upper);
    }

    return out[0] != '\0';
}

/**
 * 内部探测：处理器商品名。
 *
 * 两条来源，按顺序试：
 *   1. 内核的 /proc/cpuinfo（ARM 看 Hardware，x86 看 model name）；
 *      这个值不像名字（空 / cpu / unknown / armv8）就当没拿到；
 *   2. Android 的 SoC 型号 + 名称库：先查 CPUNAME.txt，再回退 厂商 + 大写型号。
 *
 * 内核名恰好就是 SoC 型号时，也先拿它查一次库 —— 很多安卓设备只报 SM8635 这种，
 * 库里有商品名就该显示商品名，而不是把型号直接摆出来。
 *
 * 只读一次并缓存：同一份上下文里名字不会变，每帧读文件没必要；
 * 上下文换过（apply_device_context 标记 dirty）才重算。
 */
static TmQueryValue probe_cpu_name_internal(void)
{
    static bool probed = false;
    static TmQueryValue cached;

    if (probed && !g_cpu_name_context_dirty) {
        return cached;
    }

    probed = true;

    g_cpu_name_context_dirty = false;

    cached = query_value_none(TM_QUERY_UNSUPPORTED);

    char hardware[TM_OUT_VALUE_MAX];
    char model_name[TM_OUT_VALUE_MAX];

    hardware[0] = '\0';
    model_name[0] = '\0';

    bool cpuinfo_read = false;

    FILE *file = fopen("/proc/cpuinfo", "r");

    if (file != NULL) {

        cpuinfo_read = true;

        char line[256];

        while (fgets(line, sizeof(line), file) != NULL) {

            char value[TM_OUT_VALUE_MAX];

            if (hardware[0] == '\0' && strncmp(line, "Hardware", 8) == 0
                && value_after_colon(line, value, (int) sizeof(value))) {

                snprintf(hardware, sizeof(hardware), "%s", value);

                continue;
            }

            if (model_name[0] == '\0' && strncmp(line, "model name", 10) == 0
                && value_after_colon(line, value, (int) sizeof(value))) {

                snprintf(model_name, sizeof(model_name), "%s", value);
            }
        }

        fclose(file);
    }

    // ARM 的 Hardware 是 SoC 名，比 model name 更贴近处理器商品名
    const char *kernel_name = hardware[0] != '\0' ? hardware : model_name;

    if (cpu_name_is_usable(kernel_name)) {

        char friendly[TM_OUT_VALUE_MAX];

        if (cpu_name_database_lookup(kernel_name, friendly, sizeof(friendly))) {

            cached = query_value_text(TM_QUERY_OK, friendly);

        } else {

            cached = query_value_text(TM_QUERY_OK, kernel_name);
        }

        return cached;
    }

    // 内核没给可用名字：交给 SoC 那一套补
    char soc_name[TM_OUT_VALUE_MAX];

    if (build_soc_display_name(soc_name, sizeof(soc_name))) {

        cached = query_value_text(TM_QUERY_OK, soc_name);

        return cached;
    }

    // 两边都没拿到：cpuinfo 都打不开就报读失败，否则就是这台机器没这项信息
    if (!cpuinfo_read) {
        cached = query_value_none(TM_QUERY_READ_FAILED);
    }

    return cached;
}

/**
 * 内部探测：虚拟化方案。
 *
 * 只看内核暴露出来的虚拟化字符设备，按平台惯例取第一个存在的：
 *   /dev/gunyah → Gunyah（Qualcomm）
 *   /dev/kvm    → KVM（含 pKVM 形态，Google Pixel 等）
 *   /dev/gzvm   → GenieZone（MediaTek）
 *   /dev/halla  → Halla（Samsung Exynos）
 * 一个都没有 = 不支持。
 *
 * 为什么不再看 /proc/cpuinfo 的 vmx / svm / virt 去报"已禁用"：
 * 这一版没有可靠办法区分"硬件根本没有虚拟化"与"有但内核没启用"，
 * 与其猜一个状态，不如直接说 不支持。已禁用 这个描述留给外部实现
 * （真的有能力判断时由 query_virtualization_external 返回）。
 *
 * 返回值形如 `已启用(KVM)`，整串直接作为字段值发出去，客户端不做加工。
 */
static TmQueryValue probe_virtualization_internal(void)
{
    static bool probed = false;
    static TmQueryValue cached;

    if (probed) {
        return cached;
    }

    probed = true;

    static const char *const k_paths[] = {
        "/dev/gunyah",
        "/dev/kvm",
        "/dev/gzvm",
        "/dev/halla",
    };

    static const char *const k_names[] = {
        "Gunyah",
        "KVM",
        "GenieZone",
        "Halla",
    };

    for (size_t index = 0; index < sizeof(k_paths) / sizeof(k_paths[0]); index++) {

        if (access(k_paths[index], F_OK) != 0) {
            continue;
        }

        char text[64];

        snprintf(text, sizeof(text), "已启用(%s)", k_names[index]);

        cached = query_value_text(TM_QUERY_OK, text);

        return cached;
    }

    cached = query_value_text(TM_QUERY_OK, "不支持");

    return cached;
}

/**
 * 把逻辑核号拼成协议里的 cpu_ids（逗号分隔）。
 *
 * @param ids      逻辑核号数组；顺序由调用方按内核顺序拼好，这里不排序、不打乱
 * @param count    ids 的条数
 * @return false = 一个都没给
 */
static bool format_logical_ids(const int *ids, int count, char *out, int capacity)
{
    if (ids == NULL || count <= 0 || out == NULL || capacity <= 0) {
        return false;
    }

    out[0] = '\0';

    size_t used = 0;

    for (int i = 0; i < count; i++) {

        char item[16];

        if (i == 0) {
            snprintf(item, sizeof(item), "%d", ids[i]);
        } else {
            snprintf(item, sizeof(item), ",%d", ids[i]);
        }

        size_t length = strlen(item);

        if (used + length + 1 >= (size_t) capacity) {
            return used > 0;
        }

        memcpy(out + used, item, length + 1);

        used += length;
    }

    return used > 0;
}

/**
 * 把逐核百分比拼成协议里的一列（logical_usage / logical_kernel 共用）。
 *
 * 顺序与 cpu_ids 严格一一对应：这个列表的第 n 个数就是 cpu_ids 第 n 个核的值。
 * 某一核这一帧没有值（刚热插拔、计数器回退）写 -1.00，占住位置不让后面错位。
 *
 * @param values     按逻辑 CPU 号索引的百分比数组（-1 = 没值）
 * @param max_index  这个数组的有效长度（见到的最大逻辑号 + 1）
 * @param ids        逻辑核号数组（与 cpu_ids 同一份，同一顺序）
 * @param count      ids 的条数
 * @return TM_QUERY_OK = 有值；NOT_AVAILABLE = 一个都没算出来；TRUNCATED = 太长装不下
 */
static TmQueryValue format_logical_percents(const double *values, int max_index,
                                            const int *ids, int count)
{
    if (values == NULL || ids == NULL || count <= 0) {
        return query_value_none(TM_QUERY_NOT_AVAILABLE);
    }

    char text[TM_OUT_VALUE_MAX];

    text[0] = '\0';

    size_t used = 0;
    bool any = false;

    for (int i = 0; i < count; i++) {

        int logical = ids[i];

        double percent = -1.0;

        if (logical >= 0 && logical < max_index && logical < TM_OUT_LOGICAL_MAX) {
            percent = values[logical];
        }

        char item[32];

        if (i == 0) {
            snprintf(item, sizeof(item), "%.2f", percent);
        } else {
            snprintf(item, sizeof(item), ",%.2f", percent);
        }

        size_t length = strlen(item);

        if (used + length + 1 >= sizeof(text)) {
            return query_value_none(TM_QUERY_TRUNCATED);
        }

        memcpy(text + used, item, length + 1);

        used += length;

        if (percent >= 0.0) {
            any = true;
        }
    }

    if (!any) {
        return query_value_none(TM_QUERY_NOT_AVAILABLE);
    }

    return query_value_text(TM_QUERY_OK, text);
}

// ============================================================
// CPU 温度：/sys/class/thermal 里 CPU 相关热区的平均值
//
// 为什么要求平均而不是取第一个：一颗 SoC 常同时导出好几个 CPU 热区
// （如 cpu-0-0 / cpu-1-0 / cpu-therm，分别贴在不同的核簇或同一簇的不同探测点），
// 只取第一个会挑到"某个小核或某个探测点"的读数，跟整颗处理器对不上。
// 平均值才配得上"CPU 温度"这个整机口径，也和页面上 CPU 利用率是整机口径一致。
//
// 认哪个热区：只看 type 里有没有 "cpu"（忽略大小写）。
// 不去猜 "soc" / "tsensN" 这类不带 cpu 字样的命名 —— 像 "soc_thermal" 可能
// 混着 GPU / 调制解调器的热量，猜错比不显示更糟（本页没值是直接整项不显示的）。
// ============================================================

/** type 是否 CPU 相关：转小写后在 type 文本里找 "cpu" 子串。 */
static bool cpu_thermal_text_has_cpu(const char *text)
{
    char lower[64];

    int count = 0;

    for (; text[count] != '\0' && count < 63; count++) {
        lower[count] = (char) tolower((unsigned char) text[count]);
    }

    lower[count] = '\0';

    return strstr(lower, "cpu") != NULL;
}

/**
 * 读一个热区：type 含 cpu、且 temp 读得出来，才把摄氏值累加进 sum / count。
 *
 * temp 的单位按内核约定是毫摄氏度（45000 = 45 ℃）；个别驱动直接写摄氏度（45）。
 * 用 200 作分界：真实 CPU 温度不可能到 200 ℃，所以 >= 200 的值只能是毫摄氏度。
 *
 * 读不到的热区直接跳过，不算进平均（不能拿 0 顶替，否则整颗芯片会被拉低）。
 */
static void cpu_thermal_read_zone(const char *zone, double *sum, int *count)
{
    char path[256];

    snprintf(path, sizeof(path), "/sys/class/thermal/%s/type", zone);

    char type_text[64];

    if (read_text_file(path, type_text, (int) sizeof(type_text)) <= 0) {
        return;
    }

    trim_text(type_text);

    if (!cpu_thermal_text_has_cpu(type_text)) {
        return;
    }

    snprintf(path, sizeof(path), "/sys/class/thermal/%s/temp", zone);

    double raw = 0.0;

    if (!read_first_number(path, &raw)) {
        return;
    }

    double celsius = (raw >= 200.0) ? (raw / 1000.0) : raw;

    *sum += celsius;

    (*count)++;
}

/**
 * CPU 温度：所有 CPU 相关热区的算术平均（摄氏度）。
 *
 * 两趟走：先列目录（正常路径，热区编号由内核按注册顺序定，不枚举不知道有几条）；
 * 目录列不出来（SELinux 挡了读取）或一个都没命中时，再按 thermal_zone0..31 的
 * 固定编号逐个读 type —— 读得到就认得出来，不因为列不了目录就把温度判成没有。
 *
 * @param average 平均值（仅当返回 true 时有意义）
 * @return true = 至少读到一个 CPU 热区；false = 一个都没有
 */
static bool read_cpu_temperature_celsius(double *average)
{
    double sum = 0.0;

    int count = 0;

    DIR *dir = opendir("/sys/class/thermal");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "thermal_zone", 12) != 0) {
                continue;
            }

            cpu_thermal_read_zone(entry->d_name, &sum, &count);
        }

        closedir(dir);
    }

    if (count == 0) {

        for (int index = 0; index < 32; index++) {

            char zone[32];

            snprintf(zone, sizeof(zone), "thermal_zone%d", index);

            cpu_thermal_read_zone(zone, &sum, &count);
        }
    }

    if (count == 0) {
        return false;
    }

    *average = sum / (double) count;

    return true;
}

/**
  * 填 CPU 设备：永远只填一台，把所有枚举到的物理处理器合并成这一台。  *
  * 为什么合并：内核把多颗处理器的逻辑核心放进了同一个调度池，任务管理器也只显示
  * 一个处理器；而 /proc/stat 的汇总行本身就是整机口径，按芯片拆不出单颗的利用率。
  * 分成多台只会逼着 UI 为每颗芯片各画一套视图，数据却还是同一份整机的。
  *
  * 合并规则逐字段不同，不要按同一套想：
  *   usage_percent   整机汇总行，本来就是各处理器一起算的，直接用
  *   kernel_percent  内核时间占这一帧的比例，与 usage_percent 同一口径
  *   speed_mhz       所有 policy 里 cpuinfo_cur_freq 的最大值（读不到再比 scaling_cur_freq）
  *   base_mhz        全机每颗逻辑核 cpuinfo_max_freq 的最小值（最保守的基准值）
  *   cpu_temp_c      /sys/class/thermal 里 type 含 cpu 的热区求平均（℃）

  *   name            全部同名只写一个；不同名按 "名1 名2" 拼，缺名的写 NAME
  *   virtualization  只问第一颗（主处理器）
  *   sockets         物理处理器实例数（枚举到几颗就是几，不是核心数）
  *   cores           各芯片物理核心总数（内核的插槽 + 核号去重口径）
  *   logical_cpus    合并后的逻辑核总数
  *   l1/l2/l3_cache  各芯片相加（两颗各 1 KB → 2 KB）
  *   cpu_ids         按内核顺序拼：第 0 颗的核在前、第 1 颗的在后，不打乱
  *   logical_usage   逐核利用率，与 cpu_ids 一一对应，同一个顺序
  *   logical_kernel  逐核内核时间，与 cpu_ids 一一对应，同一个顺序
  *
  * 名字 / 虚拟化 / 缓存仍是"自己探测优先，外部返回 OK 才覆盖"，多颗时逐颗问一遍。
  *
  * @param registry 已枚举好的实例表（内部仍按芯片存，合并只发生在输出这一层）
  * @param usage    这一帧的利用率（整机 + 每逻辑核）
  * @return 1 = 填了一台；0 = 没有 CPU 实例或容量不足
  */
int build_cpu_devices(TmOutDevice *out, int capacity,
                             const TmDeviceRegistry *registry, const TmCpuUsage *usage)
{
    if (out == NULL || registry == NULL || usage == NULL || capacity <= 0
        || registry->cpu_count <= 0) {
        return 0;
    }

    // 先把所有芯片的逻辑核号按内核顺序拼成一份：第 0 颗的在前，第 1 颗的在后
    int merged_ids[TM_OUT_LOGICAL_MAX];

    int merged_count = 0;

    for (int i = 0; i < registry->cpu_count; i++) {

        const TmCpuDevice *device = &registry->cpu[i];

        for (int j = 0; j < device->logical_count; j++) {

            if (merged_count >= TM_OUT_LOGICAL_MAX) {
                break;
            }

            merged_ids[merged_count++] = device->logical_ids[j];
        }
    }

    device_begin(&out[0], "cpu0", TM_DEV_TYPE_CPU);

    // 单实例：这一类只有这一台，序号恒 0
    device_put(&out[0], "cpu_id", "0");

    device_put_value(&out[0], "usage_percent",
                     query_value_float(usage->total_percent >= 0.0,
                                       usage->total_percent, 2,
                                       TM_QUERY_NO_BASELINE));

    // 内核时间：与利用率同一口径（system + irq + softirq 占这一帧的比例）
    device_put_value(&out[0], "kernel_percent",
                     query_value_float(usage->total_kernel_percent >= 0.0,
                                       usage->total_kernel_percent, 2,
                                       TM_QUERY_NO_BASELINE));

    // 速度：所有 policy 里 cpuinfo_cur_freq 的最大（读不到再比 scaling_cur_freq 最大）
    double cur_khz = 0.0;

    bool has_cur_khz = read_cpu_cur_freq_khz(&cur_khz);

    double base_khz = 0.0;

    // 基准速度：每颗逻辑核 cpuinfo_max_freq 取最小（最保守的一档）
    bool has_base_khz = read_cpu_base_freq_khz(&base_khz);

    device_put_int(&out[0], "speed_mhz",
                   has_cur_khz ? (long long) (cur_khz / 1000.0 + 0.5) : -1,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "base_mhz",
                   has_base_khz ? (long long) (base_khz / 1000.0 + 0.5) : -1,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    // 温度：CPU 相关热区（type 含 cpu）的平均，一位小数。
    // 一个热区都没命中时按原因回状态：thermal 目录都没有 = 平台不支持；
    // 有目录但没有 CPU 热区 = 这台机器没导出 CPU 温度。客户端两种都整项不显示。
    double cpu_temp_c = 0.0;

    bool has_cpu_temp = read_cpu_temperature_celsius(&cpu_temp_c);

    TmQueryStatus cpu_temp_status = TM_QUERY_OK;

    if (!has_cpu_temp) {

        cpu_temp_status = (access("/sys/class/thermal", F_OK) == 0)
            ? TM_QUERY_NOT_AVAILABLE
            : TM_QUERY_UNSUPPORTED;
    }

    device_put_value(&out[0], "cpu_temp_c",
                     query_value_float(has_cpu_temp, cpu_temp_c, 1, cpu_temp_status));

    // 名字：逐颗求最终值（外部优先，内部探测兜底），再按"同名只留一个 / 异名拼接"汇总
    TmQueryValue internal_name = probe_cpu_name_internal();

    char primary_name[TM_OUT_VALUE_MAX];

    primary_name[0] = '\0';

    char name_text[TM_OUT_VALUE_MAX];

    name_text[0] = '\0';

    size_t name_used = 0;

    TmQueryStatus name_status = TM_QUERY_UNSUPPORTED;

    bool name_overflow = false;

    for (int i = 0; i < registry->cpu_count && !name_overflow; i++) {

        const TmCpuDevice *device = &registry->cpu[i];

        char one_name[TM_OUT_VALUE_MAX];

        one_name[0] = '\0';

        TmQueryStatus one_status = query_cpu_name_external(device->device_id, device->path,
                                                           one_name, (int) sizeof(one_name));

        bool have_one = false;

        if (one_status == TM_QUERY_OK && one_name[0] != '\0') {

            have_one = true;

            name_status = TM_QUERY_OK;

        } else if (internal_name.status == TM_QUERY_OK) {

            snprintf(one_name, sizeof(one_name), "%s", internal_name.text);

            have_one = true;

            name_status = TM_QUERY_OK;
        }

        // 缺名用 NAME 占位：一颗有名一颗没名，也要能看出它们不一样
        if (!have_one) {
            snprintf(one_name, sizeof(one_name), "%s", "NAME");
        }

        if (i == 0) {

            snprintf(primary_name, sizeof(primary_name), "%s", one_name);

            snprintf(name_text, sizeof(name_text), "%s", one_name);

            name_used = strlen(name_text);

            continue;
        }

        // 与第一颗的名字完全相同就只留一个
        if (strcmp(one_name, name_text) == 0) {
            continue;
        }

        if (name_used + 1 + strlen(one_name) + 1 > sizeof(name_text)) {

            name_overflow = true;

            break;
        }

        name_text[name_used++] = ' ';

        snprintf(name_text + name_used, sizeof(name_text) - name_used, "%s", one_name);

        name_used = strlen(name_text);
    }

    if (name_overflow) {

        device_put_value(&out[0], "name", query_value_none(TM_QUERY_TRUNCATED));

    } else if (name_status == TM_QUERY_OK) {

        device_put_value(&out[0], "name", query_value_text(TM_QUERY_OK, name_text));

    } else {

        device_put_value(&out[0], "name", query_value_none(name_status));
    }

    // 虚拟化只问第一颗（主处理器）：多颗的方案一般一致，各问一遍没有意义
    const TmCpuDevice *primary = &registry->cpu[0];

    TmQueryValue virtualization_value = probe_virtualization_internal();

    char external_virtualization[TM_OUT_VALUE_MAX];

    external_virtualization[0] = '\0';

    TmQueryStatus virtualization_status = query_virtualization_external(
        primary->device_id, primary->path, primary_name,
        external_virtualization, (int) sizeof(external_virtualization));

    if (virtualization_status == TM_QUERY_OK && external_virtualization[0] != '\0') {

        virtualization_value = query_value_text(TM_QUERY_OK, external_virtualization);

    } else if (virtualization_value.status != TM_QUERY_OK) {

        virtualization_value = query_value_none(virtualization_status);
    }

    device_put_value(&out[0], "virtualization", virtualization_value);

    // 是不是虚拟机客人：这一版没有可靠检测手段，按约定恒为 0。
    // 客户端只在这一格是 1 时显示"虚拟机: 是"，为 0 时整行都不出现
    // （没有"虚拟机: 否"这种说法），并据此决定是否显示虚拟化 / AVF 两行。
    device_put_value(&out[0], "is_vm", query_value_text(TM_QUERY_OK, "0"));

    // 插槽数 = 枚举到的物理处理器实例数；cores 走内核的"插槽 + 核号"去重口径
    int sockets = registry->cpu_count;

    int cores = -1;

    int socket_count_ignored = -1;

    count_sockets_and_cores(&socket_count_ignored, &cores);

    int logical_cpus = count_logical_cpus();

    device_put_int(&out[0], "sockets", sockets, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "cores", cores, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(&out[0], "logical_cpus", logical_cpus, TM_FIELD_STATUS_NOT_AVAILABLE);

    // 缓存逐颗求再相加：两颗各 1 KB → 2 KB。某颗某级没值就不算它
    long long cache_sum[3] = { 0, 0, 0 };

    int cache_has[3] = { 0, 0, 0 };

    TmQueryStatus cache_status[3] = {
        TM_QUERY_NOT_AVAILABLE, TM_QUERY_NOT_AVAILABLE, TM_QUERY_NOT_AVAILABLE
    };

    for (int i = 0; i < registry->cpu_count; i++) {

        const TmCpuDevice *device = &registry->cpu[i];

        int cache_kb[3] = { -1, -1, -1 };

        TmQueryStatus own_status[3] = {
            TM_QUERY_UNSUPPORTED, TM_QUERY_UNSUPPORTED, TM_QUERY_UNSUPPORTED
        };

        probe_cpu_cache_from_sysfs(device->path, cache_kb, own_status);

        for (int level = 1; level <= 3; level++) {

            int external_kb = -1;

            TmQueryStatus status = query_cache_kb_external(device->device_id, device->path,
                                                           level, &external_kb);

            // 外部 OK 才覆盖自己扫到的
            if (status == TM_QUERY_OK && external_kb > 0) {

                cache_kb[level - 1] = external_kb;

                own_status[level - 1] = TM_QUERY_OK;
            }

            if (cache_kb[level - 1] >= 0) {

                cache_sum[level - 1] += cache_kb[level - 1];

                cache_has[level - 1] = 1;

                cache_status[level - 1] = TM_QUERY_OK;

            } else if (i == 0) {

                cache_status[level - 1] = own_status[level - 1];

            } else if (cache_status[level - 1] != TM_QUERY_ACCESS_DENIED
                       && own_status[level - 1] == TM_QUERY_ACCESS_DENIED) {

                // 多颗芯片时取"更能说明问题"的那个：只要有一颗读不到，
                // 就写"-"（读不到），不能因为另一颗没这一级就谎称"不支持"
                cache_status[level - 1] = TM_QUERY_ACCESS_DENIED;
            }
        }
    }

    static const char *const cache_keys[3] = {
        "l1_cache_kb", "l2_cache_kb", "l3_cache_kb"
    };

    for (int level = 1; level <= 3; level++) {

        if (cache_has[level - 1]) {

            device_put_value(&out[0], cache_keys[level - 1],
                             query_value_int(cache_sum[level - 1], TM_QUERY_NOT_AVAILABLE));

            continue;
        }

        device_put_value(&out[0], cache_keys[level - 1],
                         query_value_none(cache_status[level - 1]));
    }

    // 合并后的逻辑核号，例如 0,1,2,3,4,5,6,7
    char member_text[TM_OUT_VALUE_MAX];

    if (format_logical_ids(merged_ids, merged_count, member_text, (int) sizeof(member_text))) {
        device_put(&out[0], "cpu_ids", member_text);
    }

    // 逐核利用率：顺序与 cpu_ids 一一对应
    device_put_value(&out[0], "logical_usage",
                     format_logical_percents(usage->logical_percent, usage->logical_max,
                                             merged_ids, merged_count));

    // 逐核内核时间：与 logical_usage 同一份核号、同一个顺序
    device_put_value(&out[0], "logical_kernel",
                     format_logical_percents(usage->logical_kernel_percent,
                                             usage->logical_max, merged_ids, merged_count));

    return 1;
}

// ============================================================
// 给设备扩展快照（typeCode=6）用的转发层
//
// 这里**只有转发，没有第二份实现**：/proc/stat 的解析、cpuN 与 policyN 的枚举
// 仍然由本文件上面那几份静态函数负责。扩展快照要的是"同一套事实、另一份基线"，
// 基线在 CPUExtended.c 里自己存。
// ============================================================

int tm_cpu_collect_ids(int *out, int capacity)
{
    return collect_cpu_indices(out, capacity);
}

int tm_cpu_collect_policies(int *out, int capacity)
{
    return collect_cpu_policy_indices(out, capacity);
}

bool tm_cpu_read_stat(TmCpuStatTable *out)
{
    return read_cpu_stat_table(out);
}

double tm_cpu_percent_between(const TmCpuTimes *later, const TmCpuTimes *before)
{
    return cpu_percent_between(later, before);
}
