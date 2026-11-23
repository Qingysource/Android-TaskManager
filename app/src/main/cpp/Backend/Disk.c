/* ============================================================
 * Disk.c — 磁盘设备：块设备枚举、身份识别、挂载归属与常驻高频采样线程。
 * ============================================================ */

#include "Header.h"

/**
 * 块设备里这几类不是"用户理解的磁盘"，枚举时跳过。
 *
 *   loop / ram / zram : 回环文件与内存盘，没有物理介质
 *   dm- / md / nbd    : 设备映射 / 软 RAID / 网络块设备，底层另有真盘
 *   sr                : 光驱（没有盘片时容量 0）
 *   mtd               : 裸 flash 分区，不是块寻址磁盘
 *   *boot*            : MMC 的 boot0 / boot1 硬件启动区，容量小且不承载文件系统
 *
 * 只按名字过滤还不够（厂商命名的虚拟盘不在这一列），真正的判据在
 * enumerate_disk_devices 里：必须在 sysfs 拓扑中有 device 节点。
 */
static bool is_pseudo_block_name(const char *name)
{
    static const char *const prefixes[] = { "loop", "ram", "zram", "dm-", "sr", "md", "nbd", "mtd" };

    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {

        size_t length = strlen(prefixes[i]);

        if (strncmp(name, prefixes[i], length) == 0) {
            return true;
        }
    }

    if (strstr(name, "boot") != NULL) {
        return true;
    }

    return false;
}

/** 按块名排序：/sys/block 的 readdir 顺序不保证稳定，编号必须建立在名字排序之上。 */
static int compare_disk_name(const void *left, const void *right)
{
    const TmDeviceRef *a = (const TmDeviceRef *) left;
    const TmDeviceRef *b = (const TmDeviceRef *) right;

    return strcmp(a->name, b->name);
}

/** 判一块块设备能不能进磁盘列表，能就填进 out[count]；返回新的 count。 */
static int disk_add_block(TmDeviceRef *out, int capacity, int count, const char *block_name)
{
    if (block_name == NULL || block_name[0] == '\0' || block_name[0] == '.') {
        return count;
    }

    if (is_pseudo_block_name(block_name)) {
        return count;
    }

    // 枚举与固定路径两趟会撞名（sda 既被列目录列到、又被固定路径试到），按块名去重
    for (int i = 0; i < count; i++) {

        if (strcmp(out[i].name, block_name) == 0) {
            return count;
        }
    }

    if (count >= capacity) {
        return count;
    }

    char probe[TM_PATH_MAX];
    struct stat info;

    // 物理盘判据：控制器链接存在
    snprintf(probe, sizeof(probe), "/sys/block/%s/device", block_name);

    if (stat(probe, &info) != 0) {
        return count;
    }

    // 容量为 0 的空槽不算磁盘（读卡器 / 光驱位没有介质）
    double sectors = 0.0;

    snprintf(probe, sizeof(probe), "/sys/block/%s/size", block_name);

    if (!read_first_number(probe, &sectors) || sectors <= 0.0) {
        return count;
    }

    snprintf(out[count].path, sizeof(out[count].path), "/sys/block/%s", block_name);

    snprintf(out[count].name, sizeof(out[count].name), "%s", block_name);

    return count + 1;
}

/**
 * 枚举磁盘：扫 /sys/block，只留物理盘。
 *
 * 两道判据，缺一不可：
 * 1. 名字过滤（见 is_pseudo_block_name），先挡掉回环 / zram / dm / md 这类；
 * 2. 拓扑判据：/sys/block/<dev>/device 必须存在。这个节点是内核把块设备
 *    挂到真实控制器上的链接，虚拟块设备（dm-* / loop / zram）没有它。
 *    厂商自己起的虚拟盘名字不在前缀表里，只能靠这一条挡下来。
 * 另外容量为 0 的空槽（读卡器没插卡）不算一台磁盘，免得页面上多一个 0 MB 的空壳。
 *
 * 两趟走：枚举 + 常见块名的固定路径（sda/sdb、mmcblk0/1、nvme0n1、vda）。
 * 块名由内核按探测顺序分配，列不了目录时固定名字仍能 stat 到 device 与 size，
 * 这样"目录读不了"就不会变成"这台机器没有磁盘"。
 *
 * 编号在**排序之后**给：readdir 顺序不保证跨帧一致，先把名字排序，
 * 再依次编 disk0 / disk1，客户端的"磁盘 0 / 磁盘 1"才不会两帧之间换位置。
 */
int enumerate_disk_devices(TmDeviceRef *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    int count = 0;

    DIR *dir = opendir("/sys/block");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {
            count = disk_add_block(out, capacity, count, entry->d_name);
        }

        closedir(dir);
    }

    static const char *const fixed_blocks[] = {
        "sda",
        "sdb",
        "sdc",
        "mmcblk0",
        "mmcblk1",
        "nvme0n1",
        "nvme1n1",
        "vda",
        "vdb",
    };

    for (size_t i = 0; i < sizeof(fixed_blocks) / sizeof(fixed_blocks[0]); i++) {
        count = disk_add_block(out, capacity, count, fixed_blocks[i]);
    }

    // 按块名排序：枚举顺序跨帧稳定，界面上的"磁盘 0 / 磁盘 1"才不会来回换位
    if (count > 1) {
        qsort(out, (size_t) count, sizeof(TmDeviceRef), compare_disk_name);
    }

    for (int i = 0; i < count; i++) {

        // id 是片段内的稳定索引（disk0 / disk1），只用来查这一帧的数据；
        // 它跟着排序后的枚举位置走，不代表某一块具体的盘。
        // 物理身份由 node 承担（node=块名 sda），客户端按 node 认盘。
        snprintf(out[i].id, sizeof(out[i].id), "disk%d", i);

        ensure_device_name(out[i].name, (int) sizeof(out[i].name));
    }

    return count;
}

/** 读一行的块层计数器；少于 11 列（老内核 / 解析失败）按没有值处理。 */
static bool read_block_stat(const char *path, TmBlockStat *out)
{
    if (path == NULL || out == NULL) {
        return false;
    }

    char text[512];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    TmBlockStat stat;

    memset(&stat, 0, sizeof(stat));

    int got = sscanf(text,
                     "%llu %llu %llu %llu %llu %llu %llu %llu %llu %llu %llu",
                     &stat.reads, &stat.reads_merged, &stat.sectors_read, &stat.read_ms,
                     &stat.writes, &stat.writes_merged, &stat.sectors_written,
                     &stat.write_ms, &stat.in_flight, &stat.busy_ms, &stat.weighted_ms);

    if (got < 11) {
        return false;
    }

    *out = stat;

    return true;
}

/** 进程内的基准表：本进程常驻，两次请求之间的差值就在这里算。 */
static TmDiskPrev g_disk_prev[TM_DISK_SAMPLE_MAX];

static int g_disk_prev_count = 0;

/** 取（没有就新建）某块盘的基准槽；表满时返回 NULL，这一帧不外扩、也不覆盖别人的基准。 */
static TmDiskPrev *disk_prev_slot(const char *name)
{
    if (name == NULL || name[0] == '\0') {
        return NULL;
    }

    for (int i = 0; i < g_disk_prev_count; i++) {

        if (strcmp(g_disk_prev[i].name, name) == 0) {
            return &g_disk_prev[i];
        }
    }

    if (g_disk_prev_count >= TM_DISK_SAMPLE_MAX) {
        return NULL;
    }

    TmDiskPrev *slot = &g_disk_prev[g_disk_prev_count++];

    memset(slot, 0, sizeof(*slot));

    snprintf(slot->name, sizeof(slot->name), "%s", name);

    return slot;
}

/**
 * 用两轮 stat 差值算实时指标。
 *
 * 口径（与页面的四个实时字段一一对应）：
 *   读取速度   = 读取扇区增量 * 512 / 间隔秒
 *   写入速度   = 写入扇区增量 * 512 / 间隔秒
 *   活动时间   = 设备繁忙毫秒增量 / 间隔毫秒 * 100，夹到 0..100
 *   平均响应   = 加权 I/O 毫秒增量 /（读完成 + 写完成）增量；这一帧没有完成量时记 0
 *
 * 首帧（或计数器倒退）不发数值：上层显示占位符，等下一帧再出数据，不编 0。
 */
static TmDiskIo measure_disk_io(const char *block_name, const char *stat_path)
{
    TmDiskIo io;

    memset(&io, 0, sizeof(io));

    TmBlockStat current;

    if (!read_block_stat(stat_path, &current)) {
        return io;
    }

    long long at_ms = monotonic_ms();

    if (at_ms <= 0) {
        return io;
    }

    TmDiskPrev *prev = disk_prev_slot(block_name);

    if (prev == NULL) {
        return io;
    }

    bool has_baseline = prev->valid && prev->at_ms > 0 && at_ms > prev->at_ms;

    // 计数器倒退 = 设备重插 / 内核重置，这一帧不当基准用，重新起算
    if (has_baseline &&
        (current.sectors_read < prev->stat.sectors_read ||
         current.sectors_written < prev->stat.sectors_written ||
         current.reads < prev->stat.reads ||
         current.writes < prev->stat.writes ||
         current.busy_ms < prev->stat.busy_ms ||
         current.weighted_ms < prev->stat.weighted_ms)) {

        has_baseline = false;
    }

    if (has_baseline) {

        double elapsed_ms = (double) (at_ms - prev->at_ms);

        unsigned long long sector_read_delta =
            current.sectors_read - prev->stat.sectors_read;

        unsigned long long sector_write_delta =
            current.sectors_written - prev->stat.sectors_written;

        io.read_bytes_per_sec =
            (double) sector_read_delta * (double) TM_DISK_SECTOR_BYTES / (elapsed_ms / 1000.0);

        io.write_bytes_per_sec =
            (double) sector_write_delta * (double) TM_DISK_SECTOR_BYTES / (elapsed_ms / 1000.0);

        io.has_read = true;
        io.has_write = true;

        double busy_delta = (double) (current.busy_ms - prev->stat.busy_ms);

        double activity = busy_delta * 100.0 / elapsed_ms;

        if (activity < 0.0) {
            activity = 0.0;
        }

        if (activity > 100.0) {
            activity = 100.0;
        }

        io.activity_percent = activity;
        io.has_activity = true;

        unsigned long long completed =
            (current.reads - prev->stat.reads) + (current.writes - prev->stat.writes);

        double weighted_delta = (double) (current.weighted_ms - prev->stat.weighted_ms);

        io.avg_response_ms = (completed > 0)
                             ? (weighted_delta / (double) completed)
                             : 0.0;

        io.has_response = true;
    }

    prev->stat = current;
    prev->at_ms = at_ms;
    prev->valid = true;

    return io;
}

/** 结果表：按块名索引，进程内常驻。 */
static TmDiskRuntime g_disk_rt[TM_DISK_SAMPLE_MAX];

static int g_disk_rt_count = 0;

/** 保护整张 g_disk_rt：线程写，请求线程读，两边都只持有极短时间。 */
static pthread_mutex_t g_disk_rt_lock = PTHREAD_MUTEX_INITIALIZER;

/** 采样线程是否已在跑（只用于避免重复启动）。 */
static bool g_disk_rt_thread_on = false;

/** 取（没有就新建）某块盘的运行时槽；表满时返回 NULL。调用方必须已持锁。 */
static TmDiskRuntime *disk_rt_slot_locked(const char *name)
{
    if (name == NULL || name[0] == '\0') {
        return NULL;
    }

    for (int i = 0; i < g_disk_rt_count; i++) {

        if (strcmp(g_disk_rt[i].name, name) == 0) {
            return &g_disk_rt[i];
        }
    }

    if (g_disk_rt_count >= TM_DISK_SAMPLE_MAX) {
        return NULL;
    }

    TmDiskRuntime *slot = &g_disk_rt[g_disk_rt_count++];

    memset(slot, 0, sizeof(*slot));

    snprintf(slot->name, sizeof(slot->name), "%s", name);

    return slot;
}

/** 找已有的运行时槽（不新建）。调用方必须已持锁。 */
static TmDiskRuntime *disk_rt_find_locked(const char *name)
{
    if (name == NULL || name[0] == '\0') {
        return NULL;
    }

    for (int i = 0; i < g_disk_rt_count; i++) {

        if (strcmp(g_disk_rt[i].name, name) == 0) {
            return &g_disk_rt[i];
        }
    }

    return NULL;
}

/**
 * 取"往回第 age 拍"的采样：age = 0 是最新，1 是上一个，依此类推。
 * 越界（这批数据还没攒够）返回 NULL。调用方必须已持锁。
 */
static const TmDiskSample *disk_rt_age(const TmDiskRuntime *slot, int age)
{
    if (slot == NULL || age < 0 || age >= slot->count) {
        return NULL;
    }

    int index = (slot->next + TM_DISK_WINDOW_SAMPLES - 1 - age) % TM_DISK_WINDOW_SAMPLES;

    return &slot->ring[index];
}

/** 计数器是否倒退（设备重插 / 内核重置）：任何一项变小都算。 */
static bool disk_stat_regressed(const TmBlockStat *before, const TmBlockStat *after)
{
    if (before == NULL || after == NULL) {
        return true;
    }

    return after->sectors_read < before->sectors_read ||
           after->sectors_written < before->sectors_written ||
           after->reads < before->reads ||
           after->writes < before->writes ||
           after->busy_ms < before->busy_ms ||
           after->weighted_ms < before->weighted_ms;
}

/**
 * 用环形缓冲里两个采样点结算四项实时指标（两端取差，不逐段累加）。
 *
 * 为什么两端取差：所有计数器都是单调累加的，两端之差就是整段总量，
 * 逐段累加只会多一层舍入误差，结果一样。
 *
 * 口径（两段窗口长度不同，这是本函数与早期版本唯一的区别）：
 *   读取速度 / 写入速度 / 活动时间 → 固定用**最近 1 秒**（TM_DISK_RATE_SPAN 拍），
 *     与任务管理器一致，也保证图表上的抖动不会被拉平；
 *   平均响应 → 用**自适应窗口**（见 TM_DISK_RESPONSE_TARGET_IOS）：
 *     从"最近 250 ms"开始往前找，找到第一个完成量 ≥ 100 的窗口就用它，
 *     最远拉到环形缓冲的 4 秒。这样"平均"的分辨率能压到 0.1 ms 一档，
 *     而不是被 time_in_queue 的整毫秒 / 一格 jiffy 卡死在 0 / 1 / 2 ms。
 *
 * 窗口内没有完成的 I/O 时平均响应记 0 —— 空转时的平均响应本来就是 0，不是"没有值"。
 * 计数器倒退 = 整轮作废，只留最新点重新起算。
 * 调用方必须已持锁。
 */
static void disk_rt_settle(TmDiskRuntime *slot)
{
    if (slot == NULL || slot->count < 2) {
        return;
    }

    const TmDiskSample *newest = disk_rt_age(slot, 0);

    if (newest == NULL || !newest->valid) {
        return;
    }

    int max_age = slot->count - 1;

    // ---- 第一段：最近 1 秒的速率与活动时间 ----
    int rate_age = max_age > TM_DISK_RATE_SPAN ? TM_DISK_RATE_SPAN : max_age;

    const TmDiskSample *rate_base = disk_rt_age(slot, rate_age);

    if (rate_base == NULL || !rate_base->valid) {
        return;
    }

    // 倒退只查真正要用的那 1 秒窗口：4 秒窗口里 3 秒前有过一次重插，
    // 不该把现在这一秒的正常数据也一起作废。
    if (disk_stat_regressed(&rate_base->stat, &newest->stat)) {

        slot->io_valid = false;

        return;
    }

    long long rate_span_ms = newest->at_ms - rate_base->at_ms;

    if (rate_span_ms < TM_DISK_MIN_SPAN_MS) {
        return;
    }

    const TmBlockStat *rate_before = &rate_base->stat;
    const TmBlockStat *after = &newest->stat;

    TmDiskIo io;

    memset(&io, 0, sizeof(io));

    double seconds = (double) rate_span_ms / 1000.0;

    unsigned long long sector_read = after->sectors_read - rate_before->sectors_read;
    unsigned long long sector_write = after->sectors_written - rate_before->sectors_written;

    io.read_bytes_per_sec = (double) sector_read * (double) TM_DISK_SECTOR_BYTES / seconds;
    io.write_bytes_per_sec = (double) sector_write * (double) TM_DISK_SECTOR_BYTES / seconds;

    io.has_read = true;
    io.has_write = true;

    // 设备忙时 = 窗口内真正在处理 I/O 的毫秒数；两个 I/O 重叠时内核只算一次，
    // 所以这个比例天然落在 0..100，夹一下只是防四舍五入越界。
    double busy = (double) (after->busy_ms - rate_before->busy_ms);

    double activity = busy * 100.0 / (double) rate_span_ms;

    if (activity < 0.0) {
        activity = 0.0;
    }

    if (activity > 100.0) {
        activity = 100.0;
    }

    io.activity_percent = activity;
    io.has_activity = true;

    // ---- 第二段：自适应窗口的平均响应 ----
    // 先看最近 1 秒：一个 I/O 都没完成 = 此刻根本没有"正在被响应"的 I/O，直接给 0。
    // 不能因为窗口里还留着几秒前的旧账就继续显示（负载一停数字再挂 4 秒，看着像假数据）。
    unsigned long long recent_completed =
        (after->reads - rate_before->reads) + (after->writes - rate_before->writes);

    int response_age = 0;

    if (recent_completed > 0) {

        // 有 I/O 才需要精度：从 1 秒开始往前找，找到第一个攒够目标完成量的窗口；
        // 一直不够就用环形缓冲里最长的一段（最多 4 秒）。
        response_age = max_age;

        for (int age = rate_age; age <= max_age; age++) {

            const TmDiskSample *base = disk_rt_age(slot, age);

            if (base == NULL || !base->valid) {
                continue;
            }

            // 这一段里有过计数器倒退（重插 / 内核重置）就跳过它，换更短的一段：
            // 差分会变成无意义的大数
            if (disk_stat_regressed(&base->stat, &newest->stat)) {
                continue;
            }

            unsigned long long done =
                (after->reads - base->stat.reads) + (after->writes - base->stat.writes);

            if (done >= (unsigned long long) TM_DISK_RESPONSE_TARGET_IOS) {

                response_age = age;

                break;
            }
        }
    }

    if (response_age == 0) {

        io.avg_response_ms = 0.0;
        io.has_response = true;

    } else {

        const TmDiskSample *response_base = disk_rt_age(slot, response_age);

        // 兜底选中的最长一段里也可能夹着倒退：那就退回干净的 1 秒窗口
        if (response_base != NULL &&
            disk_stat_regressed(&response_base->stat, &newest->stat)) {

            response_base = rate_base;
        }

        if (response_base != NULL && response_base->valid) {

            unsigned long long completed =
                (after->reads - response_base->stat.reads) +
                (after->writes - response_base->stat.writes);

            // weighted_ms 是每个 I/O 在队列里排队等待的毫秒数之和，除以完成量 = 单个 I/O
            // 的平均队列时间。整数毫秒的量化误差在除法里被完成量稀释，这就是精度来源。
            io.avg_response_ms = (completed > 0)
                                 ? ((double) (after->weighted_ms - response_base->stat.weighted_ms)
                                    / (double) completed)
                                 : 0.0;

            io.has_response = true;
        }
    }

    slot->io = io;
    slot->io_valid = true;
}

/**
 * 采样线程主体：每 TM_DISK_TICK_MS 枚举一次物理盘、读一遍 stat、结算一次。
 *
 * 每轮都重新枚举，是为了热插拔：插上的盘立刻有数据，拔掉的盘只是不再更新
 * （它的槽留在表里，请求侧按"过期"处理，不会再对外发陈旧值）。
 *
 * 全程不碰 stdout：那是主循环的协议通道，线程往里写会污染回包。
 */
static void *disk_sample_thread(void *arg)
{
    (void) arg;

    while (true) {

        TmDeviceRef refs[TM_DISK_SAMPLE_MAX];

        int count = enumerate_disk_devices(refs, TM_DISK_SAMPLE_MAX);

        long long at_ms = monotonic_ms();

        for (int i = 0; i < count; i++) {

            char stat_path[TM_PATH_MAX];

            snprintf(stat_path, sizeof(stat_path), "%s/stat", refs[i].path);

            TmBlockStat stat;

            // 读文件在锁外做：sysfs 偶尔会慢，不能让别人排队等
            if (!read_block_stat(stat_path, &stat)) {
                continue;
            }

            pthread_mutex_lock(&g_disk_rt_lock);

            TmDiskRuntime *slot = disk_rt_slot_locked(refs[i].name);

            if (slot != NULL) {

                slot->ring[slot->next].stat = stat;
                slot->ring[slot->next].at_ms = at_ms;
                slot->ring[slot->next].valid = true;

                slot->next = (slot->next + 1) % TM_DISK_WINDOW_SAMPLES;

                if (slot->count < TM_DISK_WINDOW_SAMPLES) {
                    slot->count++;
                }

                disk_rt_settle(slot);

                slot->updated_ms = at_ms;
            }

            pthread_mutex_unlock(&g_disk_rt_lock);
        }

        usleep(TM_DISK_TICK_MS * 1000);
    }

    return NULL;
}

/** 起采样线程（传 detached，退出时不需要 join）。起不来就算了，请求侧会自动退回现算。 */
void disk_sampler_start(void)
{
    if (g_disk_rt_thread_on) {
        return;
    }

    g_disk_rt_thread_on = true;

    pthread_t thread;

    if (pthread_create(&thread, NULL, disk_sample_thread, NULL) != 0) {

        g_disk_rt_thread_on = false;

        return;
    }

    pthread_detach(thread);
}

/**
 * 查一块盘最近一次结算结果。
 *
 * @param name  块名（sda / nvme0n1 …）
 * @param found 出参：true = 拿到的是新鲜值，false = 没起来 / 还没攒够点 / 已过期
 * @return 四项指标；found=false 时内容全 0，调用方应改用 measure_disk_io 兜底
 */
static TmDiskIo disk_runtime_io(const char *name, bool *found)
{
    TmDiskIo io;

    memset(&io, 0, sizeof(io));

    if (found != NULL) {
        *found = false;
    }

    if (name == NULL || name[0] == '\0') {
        return io;
    }

    long long now = monotonic_ms();

    if (now <= 0) {
        return io;
    }

    pthread_mutex_lock(&g_disk_rt_lock);

    TmDiskRuntime *slot = disk_rt_find_locked(name);

    if (slot != NULL && slot->io_valid && slot->updated_ms > 0 &&
        (now - slot->updated_ms) <= TM_DISK_STALE_MS) {

        io = slot->io;

        if (found != NULL) {
            *found = true;
        }
    }

    pthread_mutex_unlock(&g_disk_rt_lock);

    return io;
}

/** 去掉 sysfs 文本尾部的换行与首尾空白（model / vendor 这类一行小文件都要过一遍）。 */
void trim_disk_text(char *text)
{
    if (text == NULL) {
        return;
    }

    size_t length = strlen(text);

    while (length > 0) {

        char tail = text[length - 1];

        if (tail != '\n' && tail != '\r' && tail != ' ' && tail != '\t') {
            break;
        }

        text[length - 1] = '\0';
        length--;
    }

    size_t start = 0;

    while (text[start] == ' ' || text[start] == '\t') {
        start++;
    }

    if (start > 0) {
        memmove(text, text + start, strlen(text + start) + 1);
    }
}

/**
 * 读一块盘的硬件标识（wwid / 序列号）。
 *
 * 为什么要它：块名（sda / mmcblk0）是内核按枚举顺序分配的，可以复用——拔掉 sdb
 * 再插一块新盘，内核很可能还叫它 sdb。只靠块名认盘的话，新盘会继承旧盘的曲线。
 * 硬件标识写死在盘上（SCSI 的 VPD、NVMe 的 EUI、UFS / MMC 的序列号），换盘必变。
 *
 * 四条路径按顺序试，取第一个读到的：
 *   <block>/device/wwid    SCSI / UFS，形如 t10.ATA_____Samsung_SSD_…
 *   <block>/wwid           NVMe 命名空间，形如 nvme.<eui>.<nsid>
 *   <block>/device/serial  序列号（部分 SATA / MMC 控制器只给这一格）
 *   <block>/serial         同上，少数平台直接挂在块目录下
 *
 * @param status 读不到时写状态：节点全不存在 = UNSUPPORTED（平台不给），
 *               节点在但打不开 = ACCESS_DENIED（没权限，换个身份也许能读）
 * @return 读到返回 true，out 里是去掉首尾空白的标识
 */
static bool disk_read_hardware_id(const char *block_path, char *out, int capacity,
                                  TmQueryStatus *status)
{
    static const char *const suffixes[] = {
        "/device/wwid", "/wwid", "/device/serial", "/serial"
    };

    bool denied = false;

    if (status != NULL) {
        *status = TM_QUERY_UNSUPPORTED;
    }

    if (out == NULL || capacity <= 0 || block_path == NULL) {
        return false;
    }

    out[0] = '\0';

    for (size_t i = 0; i < sizeof(suffixes) / sizeof(suffixes[0]); i++) {

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "%s%s", block_path, suffixes[i]);

        // 路径不存在是常态（例如 NVMe 没有 device/wwid），直接换下一条，不算失败
        if (access(path, F_OK) != 0) {
            continue;
        }

        if (access(path, R_OK) != 0) {
            denied = true;
            continue;
        }

        char text[TM_OUT_VALUE_MAX];

        if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
            continue;
        }

        trim_disk_text(text);

        if (text[0] == '\0') {
            continue;
        }

        snprintf(out, (size_t) capacity, "%s", text);

        if (status != NULL) {
            *status = TM_QUERY_OK;
        }

        return true;
    }

    if (status != NULL) {
        *status = denied ? TM_QUERY_ACCESS_DENIED : TM_QUERY_UNSUPPORTED;
    }

    return false;
}

/** 不区分大小写地找子串（只用于 sysfs 链接路径里的 nvme / ufs / mmc 这些关键字）。 */
static bool text_contains_ci(const char *text, const char *needle)
{
    if (text == NULL || needle == NULL || needle[0] == '\0') {
        return false;
    }

    size_t need = strlen(needle);

    for (const char *cursor = text; cursor[0] != '\0'; cursor++) {

        size_t matched = 0;

        while (matched < need && cursor[matched] != '\0' &&
               tolower((unsigned char) cursor[matched]) == tolower((unsigned char) needle[matched])) {
            matched++;
        }

        if (matched == need) {
            return true;
        }
    }

    return false;
}

/**
 * 读厂商名：`/sys/block/<dev>/device/vendor`。
 *
 * 这是 SCSI 家族的通用节点 —— UFS / SATA / USB 存储都挂在 SCSI 子系统下，都有这个文件
 * （Redmi Turbo 3 上是 SAMSUNG）。NVMe 的设备目录里没有名字型 vendor，
 * 那个位置给的是 PCI 厂商号（0x144d 这种），所以读到 `0x` 开头的值一律丢掉，
 * 宁可页面上只显示型号，也不把十六进制编号当成厂商名摆出来。
 *
 * @param block_path `/sys/block/<dev>` 目录
 * @param out        输出（空串 = 没读到）
 */
static void read_disk_vendor(const char *block_path, char *out, size_t capacity)
{
    if (out == NULL || capacity == 0) {
        return;
    }

    out[0] = '\0';

    if (block_path == NULL || block_path[0] == '\0') {
        return;
    }

    char path[TM_PATH_MAX];
    char text[TM_OUT_VALUE_MAX];

    text[0] = '\0';

    snprintf(path, sizeof(path), "%s/device/vendor", block_path);

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return;
    }

    trim_disk_text(text);

    // 0x144d / 0X144D：PCI 厂商号，不是名字，直接用它是错的
    if (text[0] == '\0' || (text[0] == '0' && (text[1] == 'x' || text[1] == 'X'))) {
        return;
    }

    snprintf(out, capacity, "%s", text);
}

/**
 * 存储接口类型：NVMe / UFS / 其他（其他返回空串，调用方就不加前缀）。
 *
 * 判据是**设备拓扑**，不是块名。要读的是 `/sys/block/<dev>` **自己**那条链接：
 *
 *   ../../devices/platform/soc/1d84000.ufshc/host0/target0:0:0/0:0:0:0
 *                                   ^^^^^^ 高通把 UFS 主机控制器叫 ufshc
 *
 * NVMe 的路径长这样：
 *
 *   ../../devices/pci0000:00/0000:00:06.0/nvme/nvme0
 *
 * 所以顺着这条链接的文本找控制器名就够，不用一层层往上退目录：
 *   - 含 nvme                  → NVMe
 *   - 含 ufshc / ufshcd / ufs  → UFS
 *
 * **不能**拿 `subsystem = scsi` 当 UFS 判据：UFS / SATA / USB 存储都呈现为 SCSI 设备，
 * 只看子系统会把 SATA 硬盘也认成 UFS。
 *
 * 链接读不到（老内核 / 权限）时退回块名前缀：nvme0n1 这种一眼就是 NVMe。
 *
 * @param block_path `/sys/block/<dev>` 目录
 * @param block_name 块名（sda / nvme0n1 / mmcblk0 …）
 * @param out        输出前缀（空串 = 不加）
 */
static void disk_interface_type(const char *block_path, const char *block_name,
                                char *out, size_t capacity)
{
    if (out == NULL || capacity == 0) {
        return;
    }

    out[0] = '\0';

    char path[TM_PATH_MAX];
    char block_link[TM_PATH_MAX];
    char device_link[TM_PATH_MAX];

    block_link[0] = '\0';
    device_link[0] = '\0';

    if (block_path != NULL && block_path[0] != '\0') {

        // 关键证据在这里：/sys/block/<dev> 自己就是一条链接，文本带着整条控制器路径，
        // 例如 Android 上 UFS 盘的
        //   ../devices/platform/soc/1d84000.ufshc/host0/target0:0:0/0:0:0:0/block/sda
        //                                          ^^^^^^ 就是 UFS Host Controller
        //
        // 而 <dev>/device 只是"在同一层里指回自己"：文本是 ../../../0:0:0:0，
        // 里面没有控制器名 —— 上一版只查那条链接，所以 UFS 永远认不出来。
        // （NVMe 的 <dev>/device 恰好指向 ../nvme0，才碰巧能认出来。）
        read_link_text(block_path, block_link, (int) sizeof(block_link));

        snprintf(path, sizeof(path), "%s/device", block_path);

        read_link_text(path, device_link, (int) sizeof(device_link));
    }

    // 两条链接一起查：块链接给控制器路径，device 链接给 SCSI 家族 / NVMe 的那一层
    bool nvme = text_contains_ci(block_link, "nvme") || text_contains_ci(device_link, "nvme");

    bool ufs = text_contains_ci(block_link, "ufshc") || text_contains_ci(block_link, "ufshcd") ||
               text_contains_ci(device_link, "ufshc") || text_contains_ci(device_link, "ufshcd") ||
               text_contains_ci(block_link, "ufs") || text_contains_ci(device_link, "ufs");

    if (nvme) {

        snprintf(out, capacity, "NVMe");
        return;
    }

    if (ufs) {

        snprintf(out, capacity, "UFS");
        return;
    }

    if (block_name != NULL && strncmp(block_name, "nvme", 4) == 0) {
        snprintf(out, capacity, "NVMe");
    }
}

/** 不区分大小写地判断 text 是否以 prefix 开头（拼名字时用来防重复厂商名）。 */
static bool starts_with_ci(const char *text, const char *prefix)
{
    if (text == NULL || prefix == NULL) {
        return false;
    }

    size_t index = 0;

    for (; prefix[index] != '\0'; index++) {

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
 * 这块盘是不是 USB 总线接的（就是类型文案里那对括号的 (USB)）。
 *
 * 为什么不能只看 device 链接：链到哪一层取决于内核拓扑，部分 USB 桥 / UAS 设备
 * 的链接落在 scsi_host 那一层，路径里根本没有 "usb" 这一段，于是只按链接判的话
 * 移动硬盘会被写成 (SCSI)。这里补三条兜底，任意一条命中就算 USB：
 *   1) device 链接的文本里有 usb（最常见，保留原有判据）；
 *   2) 把链接解析成绝对路径后再查一次（相对路径有时要在解析后才露出 usb1 那一段）；
 *   3) device/uevent 里的驱动程序名是 usb-storage / uas / usb 一类。
 * 三条都不中才算"不是 USB"，交给上层继续判 SCSI / 其它。
 */
static bool disk_is_usb_attached(const char *block_path, const char *device_target)
{
    if (block_path == NULL || block_path[0] == '\0') {
        return false;
    }

    if (device_target != NULL && text_contains_ci(device_target, "usb")) {
        return true;
    }

    char path[TM_PATH_MAX];

    // realpath 的 buffer 必须 >= PATH_MAX，否则强化的 libc 会在编译期直接报错

    char resolved[PATH_MAX];

    snprintf(path, sizeof(path), "%s/device", block_path);

    resolved[0] = '\0';

    if (realpath(path, resolved) != NULL && text_contains_ci(resolved, "usb")) {
        return true;
    }

    snprintf(path, sizeof(path), "%s/device/uevent", block_path);

    char uevent[512];

    if (read_text_file(path, uevent, (int) sizeof(uevent)) > 0) {

        if (text_contains_ci(uevent, "usb-storage") ||
            text_contains_ci(uevent, "driver=uas") ||
            text_contains_ci(uevent, "driver=usb")) {
            return true;
        }
    }

    return false;
}

/** 往名字缓冲区尾部追加一段，段间自动补一个空格；越界就截断，不越界写。 */
static void disk_name_append(char *buffer, size_t capacity, size_t *used, const char *part)
{
    if (buffer == NULL || used == NULL || part == NULL || part[0] == '\0' || capacity == 0) {
        return;
    }

    if (*used >= capacity - 1) {
        return;
    }

    int written = snprintf(buffer + *used, capacity - *used, (*used == 0) ? "%s" : " %s", part);

    if (written <= 0) {
        return;
    }

    size_t added = (size_t) written;

    *used = (*used + added >= capacity) ? (capacity - 1) : (*used + added);
}

/** 由 /proc/self/mountinfo 的 major:minor 反查块名（dm-1 / sda5 / mmcblk0p2 …）。 */
static bool block_name_of_devnum(const char *devnum, char *out, int capacity)
{
    if (devnum == NULL || devnum[0] == '\0' || capacity <= 0) {
        return false;
    }

    char path[TM_PATH_MAX];
    char link[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/sys/dev/block/%s", devnum);

    if (read_link_text(path, link, (int) sizeof(link)) <= 0) {
        return false;
    }

    name_from_path(link, out, capacity);

    return out[0] != '\0';
}

/**
 * 把一个块名归到某台物理盘。
 *
 * 三级匹配：
 * 1. 名字就是物理盘（sda / nvme0n1 / mmcblk0）；
 * 2. 是某台盘的分区（/sys/block/<disk>/<part>/dev 存在即成立）；
 * 3. dm-* / md 这类映射设备，跟着 slaves 递归到底层（限 4 层，防环）。
 *
 * @return 磁盘下标；-1 = 这个块设备不归任何一台物理盘（loop / zram / OTA 映射等）
 */
static int disk_index_of_block(const char *block_name,
                               const TmDeviceRef *disks, int disk_count, int depth)
{
    if (block_name == NULL || block_name[0] == '\0' || disks == NULL || disk_count <= 0) {
        return -1;
    }

    for (int i = 0; i < disk_count; i++) {

        if (strcmp(disks[i].name, block_name) == 0) {
            return i;
        }
    }

    for (int i = 0; i < disk_count; i++) {

        // 分区判据是"<盘>/<块名>/dev 存在"，这一条用 stat 就够，
        // 不需要列出盘目录 —— 所以列不了目录也不会把分区认丢。
        char probe[TM_PATH_MAX];
        struct stat info;

        snprintf(probe, sizeof(probe), "/sys/block/%s/%s/dev", disks[i].name, block_name);

        if (stat(probe, &info) == 0) {
            return i;
        }
    }

    if (depth < 4 &&
        (strncmp(block_name, "dm-", 3) == 0 || strncmp(block_name, "md", 2) == 0)) {

        char dir[TM_PATH_MAX];

        snprintf(dir, sizeof(dir), "/sys/block/%s/slaves", block_name);

        DIR *slaves = opendir(dir);

        if (slaves == NULL) {
            return -1;
        }

        int resolved = -1;

        struct dirent *entry;

        while ((entry = readdir(slaves)) != NULL) {

            if (entry->d_name[0] == '.') {
                continue;
            }

            resolved = disk_index_of_block(entry->d_name, disks, disk_count, depth + 1);

            if (resolved >= 0) {
                break;
            }
        }

        closedir(slaves);

        return resolved;
    }

    return -1;
}

/**
 * 扫一遍挂载表与 swap 表，填三件只有"整机视角"才知道的事。
 *
 * 已格式化容量：把每个已挂载文件系统的总容量（statvfs 的 blocks * frsize）
 * 累加到它所属的物理盘。同一块设备挂多次（/data 与 /data/user/0 这种）只算一次，
 * 否则容量会被重复累加。没有任何挂载命中时该盘记 -1，页面显示破折号。
 *
 * 系统盘：按 /system -> /vendor -> /data -> / 的顺序找第一个能落到物理盘的挂载。
 * Android 的 / 常常是 overlay（匿名块设备），所以把系统 / 厂商 / 数据分区排在前面。
 *
 * 页面文件：/proc/swaps 里的 swap 分区如果落在某台盘上就是它；zram 不归任何物理盘，
 * 所以纯 zram 的机器这一项是"否"，这才是实情。
 *
 * @param formatted       每台盘累加出来的字节数（调用方先清零）
 * @param formatted_known 每台盘是否有挂载命中（0/1）
 * @param system_disk     输出系统盘下标；-1 = 认不出来
 * @param page_file       每台盘是否承载 swap（0/1）
 */
static void collect_disk_mounts(const TmDeviceRef *disks, int disk_count,
                                long long *formatted, int *formatted_known,
                                int *system_disk, int *page_file)
{
    *system_disk = -1;

    static const char *const system_mounts[TM_DISK_SYSTEM_MOUNT_MAX] = {
        "/system", "/vendor", "/data", "/"
    };

    char system_dev[TM_DISK_SYSTEM_MOUNT_MAX][32];

    memset(system_dev, 0, sizeof(system_dev));

    // 同一个块设备（dm-3 / sda5 …）只累加一次容量
    char seen[TM_DISK_DEVNUM_MAX][32];

    memset(seen, 0, sizeof(seen));

    int seen_count = 0;

    FILE *file = fopen("/proc/self/mountinfo", "r");

    if (file != NULL) {

        char line[TM_LINE_MAX];

        while (fgets(line, (int) sizeof(line), file) != NULL) {

            char devnum[32];
            char mountpoint[TM_PATH_MAX];

            // 前两列是 mount id 与 parent id，第三列 major:minor，第五列挂载点
            if (sscanf(line, "%*s %*s %31s %*s %511s", devnum, mountpoint) != 2) {
                continue;
            }

            // 0:xx 是匿名块设备：overlay / tmpfs / proc / sysfs 全在这里，都不是磁盘
            if (strncmp(devnum, "0:", 2) == 0) {
                continue;
            }

            for (int k = 0; k < TM_DISK_SYSTEM_MOUNT_MAX; k++) {

                if (system_dev[k][0] == '\0' && strcmp(mountpoint, system_mounts[k]) == 0) {
                    snprintf(system_dev[k], sizeof(system_dev[k]), "%s", devnum);
                }
            }

            int already = 0;

            for (int s = 0; s < seen_count; s++) {

                if (strcmp(seen[s], devnum) == 0) {
                    already = 1;
                    break;
                }
            }

            if (already) {
                continue;
            }

            if (seen_count < TM_DISK_DEVNUM_MAX) {
                snprintf(seen[seen_count], sizeof(seen[0]), "%s", devnum);
                seen_count++;
            }

            char block_name[64];

            if (!block_name_of_devnum(devnum, block_name, (int) sizeof(block_name))) {
                continue;
            }

            int index = disk_index_of_block(block_name, disks, disk_count, 0);

            if (index < 0) {
                continue;
            }

            struct statvfs vfs;

            if (statvfs(mountpoint, &vfs) != 0) {
                continue;
            }

            long long unit = (vfs.f_frsize > 0) ? (long long) vfs.f_frsize : (long long) vfs.f_bsize;

            if (unit <= 0) {
                continue;
            }

            long long total = (long long) vfs.f_blocks * unit;

            if (total <= 0) {
                continue;
            }

            formatted[index] += total;
            formatted_known[index] = 1;
        }

        fclose(file);
    }

    for (int k = 0; k < TM_DISK_SYSTEM_MOUNT_MAX && *system_disk < 0; k++) {

        if (system_dev[k][0] == '\0') {
            continue;
        }

        char block_name[64];

        if (!block_name_of_devnum(system_dev[k], block_name, (int) sizeof(block_name))) {
            continue;
        }

        *system_disk = disk_index_of_block(block_name, disks, disk_count, 0);
    }

    file = fopen("/proc/swaps", "r");

    if (file != NULL) {

        char line[TM_LINE_MAX];

        while (fgets(line, (int) sizeof(line), file) != NULL) {

            // 表头是 Filename...，数据行一定是设备路径或文件路径
            if (line[0] != '/') {
                continue;
            }

            char filename[TM_PATH_MAX];

            if (sscanf(line, "%511s", filename) != 1) {
                continue;
            }

            char block_name[64];

            name_from_path(filename, block_name, (int) sizeof(block_name));

            int index = disk_index_of_block(block_name, disks, disk_count, 0);

            if (index >= 0) {
                page_file[index] = 1;
            }
        }

        fclose(file);
    }
}

/**
 * 把磁盘实例填进包：每台盘一块，字段与页面一一对应。
 *
 * 字段（键名与客户端 PerformanceSnapshot 的解析逐项一致）：
 *   name              型号串（读不到退回块名，不发空串）
 *   media             介质：HDD / SSD / UFS / eMMC
 *   bus               总线：NVMe / SCSI / MMC / VIRTIO / USB
 *   capacity_bytes    容量（字节）
 *   formatted_bytes   已格式化容量（该盘上已挂载文件系统的容量之和）
 *   system_disk       1/0；整机认不出系统盘时这一格不发，页面显示破折号
 *   page_file         1/0
 *   activity_percent  活动时间 0..100
 *   avg_response_ms   平均响应毫秒
 *   read_bytes_per_sec / write_bytes_per_sec 读写速度（字节/秒）
 */
int build_disk_devices(TmOutDevice *out, int capacity,
                              const TmDeviceRef *devices, int count)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    int limit = count;

    if (limit > capacity) {
        limit = capacity;
    }

    if (limit > TM_OUT_DISK_MAX) {
        limit = TM_OUT_DISK_MAX;
    }

    long long formatted[TM_OUT_DISK_MAX];
    int formatted_known[TM_OUT_DISK_MAX];
    int page_file[TM_OUT_DISK_MAX];

    for (int i = 0; i < TM_OUT_DISK_MAX; i++) {
        formatted[i] = 0;
        formatted_known[i] = 0;
        page_file[i] = 0;
    }

    int system_disk = -1;

    collect_disk_mounts(devices, limit, formatted, formatted_known, &system_disk, page_file);

    for (int i = 0; i < limit; i++) {

        TmOutDevice *device = &out[i];

        device_begin(device, devices[i].id, TM_DEV_TYPE_DISK);

        char path[TM_PATH_MAX];

        // 介质与总线分开报：只看块名前缀会把 UFS（挂在 SCSI 子系统下）当成 SATA SSD，
        // 所以判据是 device 链接指向的控制器目录 + queue/rotational。
        char target[TM_PATH_MAX];

        target[0] = '\0';

        snprintf(path, sizeof(path), "%s/device", devices[i].path);

        read_link_text(path, target, (int) sizeof(target));

        // 接口类型（NVMe / UFS / 其他）：判据同样落在这条链接上，
        // 具体规则集中在 disk_interface_type() 里，免得介质判定和名字前缀各写一套
        char interface_type[TM_OUT_VALUE_MAX];

        disk_interface_type(devices[i].path, devices[i].name, interface_type,
                            sizeof(interface_type));

        // 设备名 = 接口前缀 + 厂商 + 型号，例如：
        //   NVMe SAMSUNG MZVLQ512HBLU-00B00
        //   UFS  SAMSUNG KLUEG4RHHF-F0G1
        //   SAMSUNG NUEWBJ-DEWDC     （既不是 NVMe 也不是 UFS：不加前缀）
        // 型号里已经带了厂商名（NVMe 的 model 常常就是 "SAMSUNG MZVLQ…"）时不再重复拼一遍
        char model[TM_OUT_VALUE_MAX];

        char vendor[TM_OUT_VALUE_MAX];

        model[0] = '\0';
        vendor[0] = '\0';

        snprintf(path, sizeof(path), "%s/device/model", devices[i].path);

        if (read_text_file(path, model, (int) sizeof(model)) > 0) {
            trim_disk_text(model);
        }

        read_disk_vendor(devices[i].path, vendor, sizeof(vendor));

        char display[TM_OUT_VALUE_MAX * 2];

        display[0] = '\0';

        size_t display_used = 0;

        disk_name_append(display, sizeof(display), &display_used, interface_type);

        if (vendor[0] != '\0' && !starts_with_ci(model, vendor)) {
            disk_name_append(display, sizeof(display), &display_used, vendor);
        }

        disk_name_append(display, sizeof(display), &display_used, model);

        // 型号与厂商都缺（比如 /sys 被裁过的老机器）就退回块名 sda，右上角不至于空着
        device_put(device, "name", display[0] != '\0' ? display : devices[i].name);

        // 块设备名（sda / sdb / mmcblk0 / nvme0n1）：页面标题写"磁盘 0 (sda)"用。
        // 它与 name 是两件事：name 是"厂商 + 型号"，不含块名（只有型号都缺时才退回块名）。
        device_put(device, "node", devices[i].name);

        // 硬件标识（wwid / 序列号）：页面不显示，只给上层曲线缓存做最高优先级的键，
        // 调试页转储字段时能看见，便于核对"这块盘到底认成了谁"。
        // 读不到也照发一格状态：字段不删，让人一眼看出是平台不给还是没权限。
        char hardware_id[TM_OUT_VALUE_MAX];

        TmQueryStatus hardware_status = TM_QUERY_UNSUPPORTED;

        if (disk_read_hardware_id(devices[i].path, hardware_id, (int) sizeof(hardware_id),
                                  &hardware_status)) {
            device_put_value(device, "wwid", query_value_text(TM_QUERY_OK, hardware_id));
        } else {
            device_put_value(device, "wwid", query_value_none(hardware_status));
        }

        double rotational = 0.0;

        int has_rotation = 0;

        snprintf(path, sizeof(path), "%s/queue/rotational", devices[i].path);

        if (read_first_number(path, &rotational)) {
            has_rotation = 1;
        }

        const char *media = NULL;
        const char *bus = NULL;

        if (text_contains_ci(target, "nvme") || strncmp(devices[i].name, "nvme", 4) == 0) {

            bus = "NVMe";
            media = "SSD";

        } else if (text_contains_ci(target, "ufs")) {

            // UFS 走 SCSI 子系统，块名是 sdX：总线照实报 SCSI，介质报 UFS
            media = "UFS";
            bus = "SCSI";

        } else if (text_contains_ci(target, "mmc") || text_contains_ci(target, "sdhci")) {

            media = "eMMC";
            bus = "MMC";

        } else if (text_contains_ci(target, "virtio")) {

            bus = "VIRTIO";

        } else if (disk_is_usb_attached(devices[i].path, target)) {

            // 移动硬盘 / U 盘：括号里写 (USB)；介质仍由 queue/rotational 判 SSD / HDD
            bus = "USB";

        } else if (text_contains_ci(target, "scsi") || strncmp(devices[i].name, "sd", 2) == 0) {

            bus = "SCSI";
        }

        if (media == NULL && has_rotation) {
            media = (rotational > 0.5) ? "HDD" : "SSD";
        }

        if (media != NULL) {
            device_put(device, "media", media);
        }

        if (bus != NULL) {
            device_put(device, "bus", bus);
        }

        // 容量：size 的 512 字节扇区数
        double sectors = 0.0;

        long long capacity_bytes = -1;

        snprintf(path, sizeof(path), "%s/size", devices[i].path);

        if (read_first_number(path, &sectors) && sectors > 0.0 && sectors < 1.0e15) {
            capacity_bytes = (long long) (sectors * (double) TM_DISK_SECTOR_BYTES);
        }

        device_put_int(device, "capacity_bytes", capacity_bytes, TM_FIELD_STATUS_NOT_AVAILABLE);

        // 已格式化容量：没有挂载命中就是没有值，不拿 0 顶替
        device_put_int(device, "formatted_bytes",
                       formatted_known[i] ? formatted[i] : -1,
                       TM_FIELD_STATUS_NOT_AVAILABLE);

        // 系统盘：整机认不出来就整格不发（页面显示破折号，而不是"否"）
        if (system_disk >= 0) {
            device_put(device, "system_disk", (i == system_disk) ? "1" : "0");
        }

        device_put(device, "page_file", page_file[i] ? "1" : "0");

        // 实时四项：优先取后台采样线程结算好的值 —— 它是固定 250 ms 一拍、
        // 按最近 1 秒窗口算的，与 UI 选的是高 / 常规 / 低档完全无关。
        // 线程没起来或值已过期，才退回老办法（用相邻两次请求的差值现算）。
        bool runtime_ready = false;

        TmDiskIo io = disk_runtime_io(devices[i].name, &runtime_ready);

        if (!runtime_ready) {

            snprintf(path, sizeof(path), "%s/stat", devices[i].path);

            io = measure_disk_io(devices[i].name, path);
        }

        device_put_value(device, "activity_percent",
                         query_value_float(io.has_activity, io.activity_percent, 2,
                                           TM_QUERY_NO_BASELINE));

        // 平均响应发两位小数：自适应窗口已经把分辨率压到 0.1 ms 一档，
        // 协议侧再只发一位就等于把精度又截掉了。
        device_put_value(device, "avg_response_ms",
                         query_value_float(io.has_response, io.avg_response_ms, 2,
                                           TM_QUERY_NO_BASELINE));

        device_put_int(device, "read_bytes_per_sec",
                       io.has_read ? (long long) io.read_bytes_per_sec : -1,
                       TM_FIELD_STATUS_NO_BASELINE);

        device_put_int(device, "write_bytes_per_sec",
                       io.has_write ? (long long) io.write_bytes_per_sec : -1,
                       TM_FIELD_STATUS_NO_BASELINE);
    }

    return limit;
}
