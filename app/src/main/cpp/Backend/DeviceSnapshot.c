/* ============================================================
 * DeviceSnapshot.c — 设备快照装配：请求上下文、设备注册表刷新与设备块输出。
 * ============================================================ */

#include "Header.h"

// ============================================================
// 位置枚举：先把每类设备"在哪"找出来，采集才有地方可读
//
// 每类一个函数，返回条数（0 = 这台机器没有这一类，或 sysfs 不允许读）。
// 同一个函数只负责填 id / path / name，不读任何数据。
// ============================================================

/** 从路径里取最后一段名字：/sys/block/sda -> sda。 */
void name_from_path(const char *path, char *out, int capacity)
{
    const char *slash = strrchr(path, '/');
    const char *name = (slash != NULL) ? (slash + 1) : path;

    snprintf(out, (size_t) capacity, "%s", name);
}

/**
 * 设备名兜底：这一版只有名称字段，抓不到具体名字时统一写 NAME。
 *
 * 为什么不用空串：空串在协议里是"这个字段有值，但值是空的"，客户端会显示成
 * 一片空白，分不清是没名字还是解析丢了；写 NAME 能明确看出"这个名字没拿到"。
 */
void ensure_device_name(char *name, int capacity)
{
    if (name[0] == '\0') {
        snprintf(name, (size_t) capacity, "%s", "NAME");
    }
}

/**
 * 枚举全部设备实例，填进这张表。
 *
 * 一次请求只调一次：决定一次采集周期里 DeviceId 与硬件的对应关系，
 * 采集过程中不允许重枚举，否则第 0 台 CPU 可能中途变成另一颗芯片。
 *
 * 非 CPU 的几类：磁盘 / 网卡各自有专门的 build_* 函数补字段，
 * GPU 这一版只报名称（温度 / 显存还没定口径）。
 *
 * @param registry 输出实例表（调用方一般给 static，避免占栈）
 */
static void registry_refresh(TmDeviceRegistry *registry)
{
    memset(registry, 0, sizeof(*registry));

    registry->cpu_count = enumerate_cpu_devices(registry->cpu, TM_OUT_DEVICE_MAX);
    registry->memory_count = enumerate_memory_devices(registry->memory, TM_OUT_MEMORY_MAX);
    registry->disk_count = enumerate_disk_devices(registry->disk, TM_OUT_DISK_MAX);
    registry->network_count = enumerate_network_devices(registry->network, TM_OUT_NETWORK_MAX);
    registry->gpu_count = enumerate_gpu_devices(registry->gpu, TM_OUT_GPU_MAX);

    // 拓扑版本：实例集合（芯片数、每颗芯片的逻辑核）变了才 +1，热插拔后客户端能察觉
    static unsigned int generation = 0;
    static unsigned int signature = 0;

    unsigned int now_signature = 0;

    now_signature = (unsigned int) registry->cpu_count * 131u
                    + (unsigned int) registry->memory_count;

    now_signature += (unsigned int) registry->disk_count * 17u
                     + (unsigned int) registry->network_count * 7u
                     + (unsigned int) registry->gpu_count * 3u;

    for (int i = 0; i < registry->cpu_count; i++) {

        now_signature = now_signature * 31u + (unsigned int) registry->cpu[i].package_id;
        now_signature = now_signature * 31u + (unsigned int) registry->cpu[i].logical_count;
    }

    if (now_signature != signature) {

        signature = now_signature;
        generation++;
    }

    registry->generation = generation;
}

/**
 * 接收一次设备快照请求的附加参数，刷新设备模型的 Android 层事实。
 *
 * 键名（都不含空格，值按百分号转义）：
 *   soc_model=SM8635
 *   soc_manufacturer=QTI
 *   cpu_name_db=<名称库绝对路径>      可给两次，按出现顺序试
 *   refresh_names=1                  只清名称缓存，不改上下文（进性能页那一下用）
 *
 * 语义是"请求即权威"：这次请求给了上下文键，就以它为全集重建 ——
 * 没给 soc_manufacturer 说明上层这次没拿到厂商，那就按没有处理，
 * 不沿用上一次的旧值（否则旧的错误值会一直粘着）。
 *
 * 上下文键一个都没带时什么都不改：老客户端、或只带 refresh_names 的请求，
 * 都保持上次上下文，不能把已有的库路径冲掉。
 *
 * @param arguments      附加参数数组，形如 键=值
 * @param argument_count 数组长度
 */
static void apply_device_context(const char *const *arguments, int argument_count)
{
    if (arguments == NULL || argument_count <= 0) {
        return;
    }

    static const char *const k_model_key = "soc_model";
    static const char *const k_manufacturer_key = "soc_manufacturer";
    static const char *const k_database_key = "cpu_name_db";
    static const char *const k_refresh_key = "refresh_names";

    char model[TM_OUT_VALUE_MAX];
    char manufacturer[TM_OUT_VALUE_MAX];
    char database[2][TM_PATH_MAX];

    model[0] = '\0';
    manufacturer[0] = '\0';

    int database_count = 0;

    // 这次请求有没有带"上下文"键（三条真数据）；refresh_names 不算
    bool context_given = false;

    bool refresh_requested = false;

    for (int index = 0; index < argument_count; index++) {

        const char *item = arguments[index];

        if (item == NULL) {
            continue;
        }

        const char *separator = strchr(item, '=');

        if (separator == NULL || separator == item) {
            continue;
        }

        size_t key_length = (size_t) (separator - item);

        const char *value = separator + 1;

        if (key_length == strlen(k_model_key) && strncmp(item, k_model_key, key_length) == 0) {

            decode_request_value(value, model, sizeof(model));

            context_given = true;

            continue;
        }

        if (key_length == strlen(k_manufacturer_key)
            && strncmp(item, k_manufacturer_key, key_length) == 0) {

            decode_request_value(value, manufacturer, sizeof(manufacturer));

            context_given = true;

            continue;
        }

        if (key_length == strlen(k_database_key) && strncmp(item, k_database_key, key_length) == 0) {

            if (database_count < (int) (sizeof(database) / sizeof(database[0]))) {

                decode_request_value(value, database[database_count], sizeof(database[0]));

                database_count++;

                context_given = true;
            }

            continue;
        }

        if (key_length == strlen(k_refresh_key) && strncmp(item, k_refresh_key, key_length) == 0) {

            // 只认 1：其它值当作没提出要求，免得客户端误传把缓存一直刷掉
            if (value[0] == '1' && value[1] == '\0') {
                refresh_requested = true;
            }
        }
    }

    // 要求重算的场合：只清名称缓存，不动上下文
    if (refresh_requested) {
        g_cpu_name_context_dirty = true;
    }

    // 没带上下文键：除了上面那一下，什么都不改
    if (!context_given) {
        return;
    }

    // 值没变就不动缓存：快照逐帧都带这几个键，没必要每帧重新读 cpuinfo 与库文件
    bool changed = strcmp(model, g_soc_model) != 0
                   || strcmp(manufacturer, g_soc_manufacturer) != 0
                   || database_count != g_cpu_name_database_count;

    for (int index = 0; index < database_count && !changed; index++) {

        if (strcmp(database[index], g_cpu_name_database[index]) != 0) {
            changed = true;
        }
    }

    if (!changed) {
        return;
    }

    snprintf(g_soc_model, sizeof(g_soc_model), "%s", model);

    snprintf(g_soc_manufacturer, sizeof(g_soc_manufacturer), "%s", manufacturer);

    g_cpu_name_database_count = database_count;

    for (int index = 0; index < database_count; index++) {

        snprintf(g_cpu_name_database[index], sizeof(g_cpu_name_database[0]), "%s",
                 database[index]);
    }

    for (int index = database_count;
         index < (int) (sizeof(g_cpu_name_database) / sizeof(g_cpu_name_database[0])); index++) {

        g_cpu_name_database[index][0] = '\0';
    }

    g_cpu_name_context_dirty = true;
}

/**
  * 处理 typeCode=5：抓一次设备快照，按设备集合发出。
  *
  * 顺序：先把各类设备加系统信息全部填进缓冲 -> 数清 D 行与 F 行的总条数 ->
  * 发包头 -> 逐块发。条数必须先定：客户端按 count 收满才算完整包。
  *
  * 包内容（有实例才发，没有的类型一条 D 行都不发）：
  *   CPU     : 永远一块，把所有物理处理器合并；含利用率 / 频率 / 拓扑 / 缓存 / 逐核利用率
  *   MEMORY  : 一台，含 /proc/meminfo 的统计项、zram 压缩量、Device Tree 里的规格
  *   DISK    : 每块物理盘一块，含型号 / 介质 / 总线 / 容量 / 已格式化 /
 *             系统盘 / 页面文件 / 活动时间 / 平均响应 / 读写速率
  *   NETWORK : 每个接口一块（虚拟接口也报），带角色 / 型号 / SSID / 连接类型 /
  *             地址 / 信号强度 / 收发速率；要显示哪一类由客户端决定
  *   GPU     : 每个图形设备一块，带利用率 / 温度 / 专用显存 / 图形 API 版本
  *   SYSTEM  : 进程 / 线程 / 句柄 / 开机时长
  *
  * 附加参数是本帧的设备模型上下文（见 apply_device_context）：请求即权威，
  * 每帧都按它刷新，所以上层拿到新数据时不必重启本进程。
  *
  * @param arguments      本次请求带的 `键=值` 列表
  * @param argument_count 列表长度
  */
bool handle_device_snapshot(unsigned long long seq, int type,
                                  const char *const *arguments, int argument_count)
{
    // 这条通道是单线程顺序处理，缓冲用 static：不占栈，也不用每次清整块
    static TmOutDevice devices[TM_OUT_DEVICE_MAX];
    static TmDeviceRegistry registry;
    static TmCpuUsage usage;

    // 先吃上下文：CPU 名称要用型号 / 厂商 / 库路径，采集前定下来，这一帧就一致
    apply_device_context(arguments, argument_count);

    // 再枚举实例：一次请求只枚举一次，采集期间 DeviceId 不会漂移
    registry_refresh(&registry);

    // 利用率整机与每逻辑核共用一对采样点；首帧没有对比点，字段会按 NO_BASELINE 发
    read_cpu_usage(&usage);

    int device_count = 0;

    device_count += build_cpu_devices(devices + device_count,
                                      TM_OUT_DEVICE_MAX - device_count,
                                      &registry, &usage);

    // 内存：/proc/meminfo 的统计项 + zram 压缩量 + 设备树里的规格
    device_count += build_memory_devices(devices + device_count,
                                         TM_OUT_DEVICE_MAX - device_count,
                                         registry.memory, registry.memory_count);

    // 系统信息紧跟硬件之后：进程 / 线程 / 句柄三项都在这一台里
    device_count += build_system_device(devices + device_count,
                                        TM_OUT_DEVICE_MAX - device_count);

    // 磁盘：每块物理盘一块，带型号 / 介质 / 总线 / 容量 / 已格式化 / 系统盘 /
    // 页面文件 / 活动时间 / 平均响应 / 读写速率（详见 build_disk_devices 上方的说明）
    device_count += build_disk_devices(devices + device_count,
                                       TM_OUT_DEVICE_MAX - device_count,
                                       registry.disk, registry.disk_count);

    device_count += build_network_devices(devices + device_count,
                                          TM_OUT_DEVICE_MAX - device_count,
                                          registry.network, registry.network_count);

    // 显卡：每张卡一块，带利用率 / 温度 / 专用显存 / 图形 API 版本
    device_count += build_gpu_devices(devices + device_count,
                                      TM_OUT_DEVICE_MAX - device_count,
                                      registry.gpu, registry.gpu_count);

    // 包头要先知道后面有几行：D 头算一行，每个字段算一行
    int line_count = 0;

    for (int i = 0; i < device_count; i++) {
        line_count += 1 + devices[i].field_count;
    }

    reply_package_header(seq, type, 0ULL, TM_STATUS_OK, line_count);

    for (int i = 0; i < device_count; i++) {
        emit_device_block(&devices[i]);
    }

    return true;
}
