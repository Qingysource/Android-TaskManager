/* ============================================================
 * CPUExtended.c — CPU 扩展信息采集：按集群分组、逐核频率与利用率。
 * ============================================================ */

#include "Header.h"

/**
 * 集群上限：与 cpufreq 的 policy0..policy7 对齐（现有实现也按 0..7 兜底扫描）。
 * 超过这个数说明内核把调频域拆得极细，多出来的核会并进最后一个集群，
 * 不至于丢核心。
 */
#define TM_EXT_CLUSTER_MAX 8

/** 一个集群：成员逻辑核号 + 它对应的 policyN（-1 = 拓扑分组，没有 policy）。 */
typedef struct {
    int index;
    int policy;
    int cpu_ids[TM_OUT_LOGICAL_MAX];
    int cpu_count;
} TmExtCluster;

/** 这一帧的采集结果：集群表 + 逐核利用率（按逻辑核号索引）。 */
typedef struct {
    TmExtCluster cluster[TM_EXT_CLUSTER_MAX];
    int cluster_count;
    TmCpuUsage usage;
} TmExtFrame;

/**
 * 扩展快照自己的 /proc/stat 基准。
 *
 * 为什么必须独立：设备快照（typeCode=5）的 read_cpu_usage() 有自己的 static 基准，
 * 它按后台轮询节奏推进。扩展页跟的是设置页的"实时更新速度"，两个采样器若共用一份
 * 基准，先跑的那个会把时间窗吃掉，后跑的那个拿到的是"上一拍到现在"的一小段，
 * 算出来的百分比忽大忽小甚至为 0。各存一份，互不干扰。
 */
static TmCpuStatTable g_ext_prev;
static bool g_ext_has_prev = false;
static long long g_ext_prev_ms = 0;

/**
 * 解析内核给的一组 CPU 号（`0 1 2 3` / `0-3` / `0-1,4-5` 都认）。
 *
 * 为什么不用 sscanf("%d")：related_cpus 的写法随内核版本变，既可能是空格分隔的
 * 数字，也可能是区间。逐字符扫一遍，两种都能读，也不依赖行尾格式。
 *
 * @return 去重后的条数；0 = 一个数字都没有
 */
static int parse_cpu_list(const char *text, int *out, int capacity)
{
    if (text == NULL || out == NULL || capacity <= 0) {
        return 0;
    }

    int count = 0;

    const char *cursor = text;

    while (*cursor != '\0') {

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

            if (*cursor >= '0' && *cursor <= '9') {

                to = 0;

                while (*cursor >= '0' && *cursor <= '9') {

                    to = to * 10 + (*cursor - '0');

                    cursor++;
                }
            }
        }

        for (int value = from; value <= to && count < capacity; value++) {

            bool seen = false;

            for (int index = 0; index < count; index++) {

                if (out[index] == value) {
                    seen = true;
                    break;
                }
            }

            if (!seen) {
                out[count++] = value;
            }
        }
    }

    return count;
}

/** 把一个 CPU 号加进某个集群（已存在就不重复加）。 */
static void cluster_add_cpu(TmExtCluster *cluster, int cpu)
{
    for (int index = 0; index < cluster->cpu_count; index++) {

        if (cluster->cpu_ids[index] == cpu) {
            return;
        }
    }

    if (cluster->cpu_count < TM_OUT_LOGICAL_MAX) {
        cluster->cpu_ids[cluster->cpu_count++] = cpu;
    }
}

/** 某个 CPU 号是否已经落在任一集群里。 */
static bool clusters_contain(const TmExtFrame *frame, int cpu)
{
    for (int outer = 0; outer < frame->cluster_count; outer++) {

        for (int inner = 0; inner < frame->cluster[outer].cpu_count; inner++) {

            if (frame->cluster[outer].cpu_ids[inner] == cpu) {
                return true;
            }
        }
    }

    return false;
}

/** 按"最小成员核号"排序，让页面上的 Cluster 0 / 1 / 2 与内核编号同向。 */
static void sort_clusters(TmExtFrame *frame)
{
    for (int outer = 0; outer < frame->cluster_count; outer++) {

        for (int inner = outer + 1; inner < frame->cluster_count; inner++) {

            int left = frame->cluster[outer].cpu_ids[0];
            int right = frame->cluster[inner].cpu_ids[0];

            if (right < left) {

                TmExtCluster swap = frame->cluster[outer];

                frame->cluster[outer] = frame->cluster[inner];

                frame->cluster[inner] = swap;
            }
        }
    }

    for (int index = 0; index < frame->cluster_count; index++) {
        frame->cluster[index].index = index;
    }
}

/**
 * 读一个核的频率节点（kHz），读不到退回它所属 policy 的同名节点。
 *
 * 为什么要有 policy 兜底：不少机型只导出 policy 层的 cpufreq 节点，
 * cpuN/cpufreq 整个目录不存在。同一个 policy 内的核共享一档频率，
 * 拿 policy 的值代表它不会错；两个都读不到才算没有这项数据。
 */
static bool read_core_freq_khz(int cpu, int policy, const char *field, double *out)
{
    char path[TM_PATH_MAX];

    double khz = 0.0;

    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/%s", cpu, field);

    if (read_first_number(path, &khz) && khz > 0.0) {

        *out = khz;

        return true;
    }

    if (policy < 0) {
        return false;
    }

    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpufreq/policy%d/%s", policy, field);

    if (read_first_number(path, &khz) && khz > 0.0) {

        *out = khz;

        return true;
    }

    return false;
}

/**
 * 采一帧逐核利用率：与整机汇总行共用同一对采样点（不重复读 /proc/stat）。
 *
 * 首帧只记基准，返回 false，字段按 NO_BASELINE 发 —— 与设备快照的约定一致，
 * 不拿 0 顶替"还不知道"。
 */
static bool read_extended_usage(TmCpuUsage *out)
{
    memset(out, 0, sizeof(*out));

    out->total_percent = -1.0;
    out->total_kernel_percent = -1.0;
    out->logical_max = 0;

    for (int index = 0; index < TM_OUT_LOGICAL_MAX; index++) {

        out->logical_percent[index] = -1.0;
        out->logical_kernel_percent[index] = -1.0;
    }

    TmCpuStatTable now;

    if (!tm_cpu_read_stat(&now)) {
        return false;
    }

    if (!g_ext_has_prev) {

        g_ext_prev = now;

        g_ext_has_prev = true;

        g_ext_prev_ms = monotonic_ms();

        return false;
    }

    long long elapsed = monotonic_ms() - g_ext_prev_ms;

    // 与设备快照同一个最小采样间隔：请求来得太密就补睡一小会儿，
    // 否则 delta_total 太小，百分比会被单个 tick 放大成抖动
    if (elapsed < TM_DEVICE_CPU_SAMPLE_MS) {

        usleep((useconds_t) ((TM_DEVICE_CPU_SAMPLE_MS - elapsed) * 1000));

        if (!tm_cpu_read_stat(&now)) {
            return false;
        }
    }

    int limit = now.max_logical;

    if (limit > TM_OUT_LOGICAL_MAX) {
        limit = TM_OUT_LOGICAL_MAX;
    }

    out->logical_max = limit;

    out->total_percent = tm_cpu_percent_between(&now.summary, &g_ext_prev.summary);

    for (int logical = 0; logical < limit; logical++) {

        // 两轮里都要有这一核：热插拔刚上线的核先不给值
        if (!now.seen[logical] || !g_ext_prev.seen[logical]) {
            continue;
        }

        out->logical_percent[logical] = tm_cpu_percent_between(&now.cpu[logical],
                                                              &g_ext_prev.cpu[logical]);
    }

    g_ext_prev = now;

    g_ext_prev_ms = monotonic_ms();

    return true;
}

/**
 * 按 policyN/related_cpus 建集群表。
 *
 * @return 建出来的集群个数；0 = 一个 policy 都没读到可用成员
 */
static int build_clusters_from_policies(TmExtFrame *frame, const int *cpu_ids, int cpu_count)
{
    int policies[16];

    int policy_count = tm_cpu_collect_policies(policies,
                                               (int) (sizeof(policies) / sizeof(policies[0])));

    if (policy_count <= 0) {
        return 0;
    }

    // policy 编号排序：目录枚举顺序不保证
    for (int outer = 0; outer < policy_count; outer++) {

        for (int inner = outer + 1; inner < policy_count; inner++) {

            if (policies[inner] < policies[outer]) {

                int swap = policies[outer];

                policies[outer] = policies[inner];

                policies[inner] = swap;
            }
        }
    }

    for (int slot = 0; slot < policy_count && frame->cluster_count < TM_EXT_CLUSTER_MAX; slot++) {

        char path[TM_PATH_MAX];

        char text[TM_LINE_MAX];

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpufreq/policy%d/related_cpus",
                 policies[slot]);

        if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
            continue;
        }

        int members[TM_OUT_LOGICAL_MAX];

        int member_count = parse_cpu_list(text, members, TM_OUT_LOGICAL_MAX);

        if (member_count <= 0) {
            continue;
        }

        TmExtCluster *cluster = &frame->cluster[frame->cluster_count];

        memset(cluster, 0, sizeof(*cluster));

        cluster->index = frame->cluster_count;

        cluster->policy = policies[slot];

        frame->cluster_count++;

        for (int index = 0; index < member_count; index++) {

            // 只收真实存在的逻辑核：related_cpus 里理论上都在，
            // 但热插拔 / 内核配置不同时可能列出不存在的号
            for (int cpu_slot = 0; cpu_slot < cpu_count; cpu_slot++) {

                if (cpu_ids[cpu_slot] == members[index]) {

                    cluster_add_cpu(cluster, members[index]);

                    break;
                }
            }
        }
    }

    return frame->cluster_count;
}

/**
 * 按 topology/{physical_package_id, cluster_id} 建集群表（policy 组不出来时的退路）。
 *
 * 键是"封装:簇"，两个都读不到的核各自成一簇（宁可分成细一点，
 * 也不能把不同簇硬并成一个 —— 那是编造簇边界）。
 */
static int build_clusters_from_topology(TmExtFrame *frame, const int *cpu_ids, int cpu_count)
{
    int keys[TM_OUT_LOGICAL_MAX];

    int key_count = 0;

    for (int cpu_slot = 0; cpu_slot < cpu_count; cpu_slot++) {

        int cpu = cpu_ids[cpu_slot];

        char path[TM_PATH_MAX];

        double package = -1.0;

        double cluster = -1.0;

        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/topology/physical_package_id", cpu);

        if (!read_first_number(path, &package)) {
            package = -1.0;
        }

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/topology/cluster_id", cpu);

        if (!read_first_number(path, &cluster)) {
            cluster = -1.0;
        }

        int key;

        if (package >= 0.0 && cluster >= 0.0) {
            key = (int) package * 100 + (int) cluster;
        } else {
            // 读不到拓扑：用核号的负数做键，保证每核独占一簇
            key = -1 - cpu;
        }

        int slot = -1;

        for (int index = 0; index < key_count; index++) {

            if (keys[index] == key) {
                slot = index;
                break;
            }
        }

        if (slot < 0) {

            if (frame->cluster_count >= TM_EXT_CLUSTER_MAX) {
                continue;
            }

            slot = key_count++;

            keys[slot] = key;

            TmExtCluster *cluster = &frame->cluster[frame->cluster_count];

            memset(cluster, 0, sizeof(*cluster));

            cluster->index = frame->cluster_count;

            cluster->policy = -1;

            frame->cluster_count++;
        }

        cluster_add_cpu(&frame->cluster[slot], cpu);
    }

    return frame->cluster_count;
}

/** 把 "0,1,2,3" 拼进一段文本（集群的 core_ids 字段）。 */
static bool format_core_ids(const int *ids, int count, char *out, int capacity)
{
    if (ids == NULL || count <= 0 || out == NULL || capacity <= 0) {
        return false;
    }

    size_t used = 0;

    out[0] = '\0';

    for (int index = 0; index < count; index++) {

        char item[16];

        if (index == 0) {
            snprintf(item, sizeof(item), "%d", ids[index]);
        } else {
            snprintf(item, sizeof(item), ",%d", ids[index]);
        }

        size_t length = strlen(item);

        if (used + length + 1 > (size_t) capacity) {
            return used > 0;
        }

        memcpy(out + used, item, length + 1);

        used += length;
    }

    return used > 0;
}

/**
 * 填一台集群设备的字段。
 *
 * 只报"有几核、哪几核、对应哪个 policy"：集群级的利用率 / 频率是要现算的统计量，
 * 页面上不做统计（用户明确要求"不用统计"），所以这里不伪造一个平均值出来。
 */
static void build_one_cluster(TmOutDevice *device, const TmExtCluster *cluster, int capacity)
{
    char id[TM_OUT_ID_MAX];

    snprintf(id, sizeof(id), "cluster%d", cluster->index);

    device_begin(device, id, TM_DEV_TYPE_CPU_CLUSTER);

    device_put_int(device, "cluster", cluster->index, TM_FIELD_STATUS_NOT_AVAILABLE);

    // policy 号是"这个簇归哪个调频域管"，拓扑分组出来的簇没有 policy
    device_put_int(device, "policy", cluster->policy, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(device, "core_count", cluster->cpu_count, TM_FIELD_STATUS_NOT_AVAILABLE);

    char members[TM_OUT_VALUE_MAX];

    if (format_core_ids(cluster->cpu_ids, cluster->cpu_count, members, capacity)) {
        device_put(device, "core_ids", members);
    }
}

/**
 * 填一颗逻辑核的设备字段：利用率 + 四项频率。
 *
 * 频率口径（这几项各自是什么，别混）：
 *   cur          cpuinfo_cur_freq（读不到退 scaling_cur_freq）：此刻这颗核跑在多少
 *   max          cpuinfo_max_freq：硬件 OPP 表里这颗核最高的一档
 *   min          cpuinfo_min_freq：硬件 OPP 表里最低的一档
 *   scaling_max  scaling_max_freq：当前 policy 设定的上限（用户可以写这个节点限频）
 */
static void build_one_core(TmOutDevice *device, int cpu, int cluster_index, int policy,
                           const TmCpuUsage *usage)
{
    char id[TM_OUT_ID_MAX];

    snprintf(id, sizeof(id), "cpu%d", cpu);

    device_begin(device, id, TM_DEV_TYPE_CPU_CORE);

    device_put_int(device, "cluster", cluster_index, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(device, "core", cpu, TM_FIELD_STATUS_NOT_AVAILABLE);

    device_put_int(device, "policy", policy, TM_FIELD_STATUS_NOT_AVAILABLE);

    double percent = -1.0;

    // 只有这一核在基准表里出现过的帧才有值；剩下的按 NO_BASELINE 发
    if (cpu >= 0 && cpu < usage->logical_max && cpu < TM_OUT_LOGICAL_MAX) {
        percent = usage->logical_percent[cpu];
    }

    device_put_value(device, "usage_percent",
                     query_value_float(percent >= 0.0, percent, 2, TM_QUERY_NO_BASELINE));

    double khz = 0.0;

    bool has_cur = read_core_freq_khz(cpu, policy, "cpuinfo_cur_freq", &khz);

    if (!has_cur) {
        has_cur = read_core_freq_khz(cpu, policy, "scaling_cur_freq", &khz);
    }

    device_put_int(device, "cur_mhz",
                   has_cur ? (long long) (khz / 1000.0 + 0.5) : -1,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    khz = 0.0;

    bool has_max = read_core_freq_khz(cpu, policy, "cpuinfo_max_freq", &khz);

    device_put_int(device, "max_mhz",
                   has_max ? (long long) (khz / 1000.0 + 0.5) : -1,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    khz = 0.0;

    bool has_min = read_core_freq_khz(cpu, policy, "cpuinfo_min_freq", &khz);

    device_put_int(device, "min_mhz",
                   has_min ? (long long) (khz / 1000.0 + 0.5) : -1,
                   TM_FIELD_STATUS_NOT_AVAILABLE);

    khz = 0.0;

    bool has_scaling_max = read_core_freq_khz(cpu, policy, "scaling_max_freq", &khz);

    device_put_int(device, "scaling_max_mhz",
                   has_scaling_max ? (long long) (khz / 1000.0 + 0.5) : -1,
                   TM_FIELD_STATUS_NOT_AVAILABLE);
}

int build_cpu_extended_devices(TmOutDevice *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    int cpu_ids[TM_OUT_LOGICAL_MAX];

    int cpu_count = tm_cpu_collect_ids(cpu_ids, TM_OUT_LOGICAL_MAX);

    if (cpu_count <= 0) {
        return 0;
    }

    static TmExtFrame frame;

    memset(&frame, 0, sizeof(frame));

    read_extended_usage(&frame.usage);

    // 第一优先：policyN 的 related_cpus（调频域成员）
    build_clusters_from_policies(&frame, cpu_ids, cpu_count);

    // 第二优先：拓扑的 封装 + 簇
    if (frame.cluster_count <= 0) {
        build_clusters_from_topology(&frame, cpu_ids, cpu_count);
    }

    // 第三优先：整机一簇
    if (frame.cluster_count <= 0) {

        frame.cluster[0].index = 0;

        frame.cluster[0].policy = -1;

        frame.cluster_count = 1;

        for (int index = 0; index < cpu_count; index++) {
            cluster_add_cpu(&frame.cluster[0], cpu_ids[index]);
        }
    }

    // 前面两种分法都可能漏核（policy 只报了一半、拓扑读不到的那几颗被单列）：
    // 漏掉的并进第一个集群，不丢核心 —— 页面上少一颗核比多一颗更让人看不懂
    for (int index = 0; index < cpu_count; index++) {

        if (!clusters_contain(&frame, cpu_ids[index])) {
            cluster_add_cpu(&frame.cluster[0], cpu_ids[index]);
        }
    }

    sort_clusters(&frame);

    int device_count = 0;

    // 先发集群，后发核心：客户端按顺序渲染时，分区标题自然落在核的前面
    for (int index = 0; index < frame.cluster_count && device_count < capacity; index++) {

        build_one_cluster(&out[device_count], &frame.cluster[index], TM_OUT_VALUE_MAX);

        device_count++;
    }

    for (int cluster_index = 0; cluster_index < frame.cluster_count; cluster_index++) {

        const TmExtCluster *cluster = &frame.cluster[cluster_index];

        for (int inner = 0; inner < cluster->cpu_count && device_count < capacity; inner++) {

            build_one_core(&out[device_count], cluster->cpu_ids[inner], cluster->index,
                           cluster->policy, &frame.usage);

            device_count++;
        }
    }

    return device_count;
}
