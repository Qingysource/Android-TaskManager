/* ============================================================
 * GPUDevice.c — GPU 设备：KGSL / Mali / DRM 发现、显卡字段采集与组装。
 * ============================================================ */

#include "Header.h"

/** 枚举网卡：实现见文件后面的"网卡设备"一节（全接口枚举 + 角色分类）。 */

/**
 * DRM 显卡的厂商名：读 device/uevent 里的 PCI_ID=VVVV:DDDD，只看厂商号。
 *
 * 拿不到真正的型号（Android 内核不导出 marketing name），所以写成
 * "Intel Graphics" 这类厂商级名字 —— 页面右上角那一行至少能看出是哪一家。
 */
static void gpu_drm_vendor_name(const char *device_path, char *out, int capacity)
{
    snprintf(out, (size_t) capacity, "%s", "GPU");

    char path[256];

    snprintf(path, sizeof(path), "%s/device/uevent", device_path);

    char text[512];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return;
    }

    const char *cursor = strstr(text, "PCI_ID=");

    if (cursor == NULL) {
        return;
    }

    cursor += strlen("PCI_ID=");

    if (strlen(cursor) < 4) {
        return;
    }

    if (strncasecmp(cursor, "8086", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "Intel Graphics");
    } else if (strncasecmp(cursor, "10de", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "NVIDIA Graphics");
    } else if (strncasecmp(cursor, "1002", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "AMD Graphics");
    } else if (strncasecmp(cursor, "5143", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "Qualcomm Adreno GPU");
    } else if (strncasecmp(cursor, "1af4", 4) == 0) {
        snprintf(out, (size_t) capacity, "%s", "Virtio GPU");
    }
}

/**
 * 大小写无关的子串查找（只用于 SoC 名字这种小写比较）。
 *
 * 不用 strcasestr：它是 GNU 扩展，bionic 上有，但别的 libc 不一定有；
 * 这里自己拼一份，编译在任何 libc 上都不会冒隐式声明的警告。
 */
static bool gpu_text_contains(const char *text, const char *needle)
{
    if (text == NULL || needle == NULL || needle[0] == '\0') {
        return false;
    }

    char lower_text[256];

    int count = 0;

    for (; text[count] != '\0' && count < (int) sizeof(lower_text) - 1; count++) {
        lower_text[count] = (char) tolower((unsigned char) text[count]);
    }

    lower_text[count] = '\0';

    char lower_needle[32];

    int needle_length = 0;

    for (; needle[needle_length] != '\0' && needle_length < (int) sizeof(lower_needle) - 1;
         needle_length++) {
        lower_needle[needle_length] = (char) tolower((unsigned char) needle[needle_length]);
    }

    lower_needle[needle_length] = '\0';

    return strstr(lower_text, lower_needle) != NULL;
}

/** 大小写无关地找子串，返回命中位置（找不到返回 NULL）；用于在型号里找 Adreno。 */
static const char *gpu_case_find(const char *text, const char *needle)
{
    if (text == NULL || needle == NULL || needle[0] == '\0') {
        return NULL;
    }

    size_t length = strlen(needle);

    for (const char *cursor = text; *cursor != '\0'; cursor++) {

        if (strncasecmp(cursor, needle, length) == 0) {
            return cursor;
        }
    }

    return NULL;
}

/**
 * 高通显卡的规范名：把内核导出的 gpu_model 拼成统一的写法。
 *
 * 内核给的原文什么样都有："Adreno735"、"Adreno (TM) 740"、"KGSL: Adreno 650"，
 * 也有根本不是 Adreno 的（自研代号 JSBY-SFF23 这种）。规则按用户定的来：
 *
 *   1. 原文里有 Adreno —— 取 Adreno 之后的部分当型号尾（去掉 (TM) / (R) 与空格），
 *      前面统一写 Qualcomm(R) Adreno(TM)，得到 "Qualcomm(R) Adreno(TM) 735"；
 *   2. 原文里没有 Adreno —— 不硬套 Adreno，写 "Qualcomm(R) <原文>"，
 *      例如 "Qualcomm(R) JSBY-SFF23"；
 *   3. 原文为空（内核没导出这一格）—— 写 "Qualcomm(R) Adreno(TM)"，不带型号。
 *
 * 为什么只改写法不动内容：型号（735 / 740）是内核给的事实，不能丢也不能编；
 * 这里只补厂商前缀、把 (TM)/(R) 归位，让它和 Windows 那种"厂商 + 系列 + 型号"
 * 的写法一致。
 */
static void gpu_qualcomm_model_name(const char *model, char *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return;
    }

    char text[64];

    snprintf(text, sizeof(text), "%s", (model != NULL) ? model : "");

    trim_disk_text(text);

    const char *adreno = gpu_case_find(text, "adreno");

    if (adreno == NULL) {

        // 不是 Adreno 系列：只加厂商前缀，型号原样保留
        if (text[0] != '\0') {
            snprintf(out, (size_t) capacity, "Qualcomm(R) %s", text);
        } else {
            snprintf(out, (size_t) capacity, "%s", "Qualcomm(R) Adreno(TM)");
        }

        return;
    }

    const char *tail = adreno + strlen("adreno");

    // 跳过 Adreno 与型号之间的分隔：空格、(TM)、(R)、单独的 TM
    bool trimmed = true;

    while (trimmed) {

        trimmed = false;

        while (*tail == ' ' || *tail == '\t') {
            tail++;
        }

        if (strncasecmp(tail, "(TM)", 4) == 0) {
            tail += 4;
            trimmed = true;
        } else if (strncasecmp(tail, "(R)", 3) == 0) {
            tail += 3;
            trimmed = true;
        } else if (strncasecmp(tail, "TM", 2) == 0) {
            tail += 2;
            trimmed = true;
        }
    }

    while (*tail == ' ' || *tail == '\t') {
        tail++;
    }

    // 尾部空白也去掉（内核文件常带换行，已经 trim 过，这里防型号后面还有空格）
    char tail_text[48];

    snprintf(tail_text, sizeof(tail_text), "%s", tail);

    trim_disk_text(tail_text);

    if (tail_text[0] != '\0') {
        snprintf(out, (size_t) capacity, "Qualcomm(R) Adreno(TM) %s", tail_text);
    } else {
        snprintf(out, (size_t) capacity, "%s", "Qualcomm(R) Adreno(TM)");
    }
}

/** 厂商的协议写法：单行无空格，客户端可以直接当标签显示。 */
static const char *gpu_vendor_text(int vendor)
{
    switch (vendor) {

        case TM_GPU_VENDOR_QUALCOMM:
            return "QUALCOMM";

        case TM_GPU_VENDOR_MEDIATEK:
            return "MEDIATEK";

        case TM_GPU_VENDOR_SAMSUNG:
            return "SAMSUNG";

        case TM_GPU_VENDOR_GOOGLE:
            return "GOOGLE";

        case TM_GPU_VENDOR_ARM:
            return "ARM";

        case TM_GPU_VENDOR_IMAGINATION:
            return "IMAGINATION";

        case TM_GPU_VENDOR_NVIDIA:
            return "NVIDIA";

        case TM_GPU_VENDOR_AMD:
            return "AMD";

        case TM_GPU_VENDOR_INTEL:
            return "INTEL";

        case TM_GPU_VENDOR_VIRTIO:
            return "VIRTIO";

        default:
            return "OTHER";
    }
}

/**
 * SoC 是哪一家做的（只在认 Mali 的归属时用得上）。
 *
 * 依据两条，都是系统自己声明的事实：
 *   /sys/devices/soc0/machine        例如 "Qualcomm Technologies, Inc SM8550"
 *                                   或 "MT6893"、"EXYNOS9610"、"Tensor"
 *   getprop ro.soc.manufacturer      Android 的属性，值形如 Qualcomm / MediaTek / Samsung / Google
 *
 * 结果在进程内只算一次：SoC 不会中途换。
 */
static int gpu_soc_vendor(void)
{
    static bool cached = false;

    static int cached_vendor = TM_GPU_VENDOR_UNKNOWN;

    if (cached) {
        return cached_vendor;
    }

    cached = true;

    char text[256];

    text[0] = '\0';

    read_text_file("/sys/devices/soc0/machine", text, (int) sizeof(text));

    if (text[0] == '\0') {
        read_text_file("/sys/devices/soc0/family", text, (int) sizeof(text));
    }

    char prop[128];

    prop[0] = '\0';

    FILE *pipe = popen("getprop ro.soc.manufacturer 2>/dev/null", "r");

    if (pipe != NULL) {

        if (fgets(prop, (int) sizeof(prop), pipe) == NULL) {
            prop[0] = '\0';
        }

        pclose(pipe);
    }

    if (gpu_text_contains(text, "qualcomm") || gpu_text_contains(text, "snapdragon")
        || gpu_text_contains(prop, "qualcomm")) {

        cached_vendor = TM_GPU_VENDOR_QUALCOMM;

    } else if (gpu_text_contains(text, "mediatek") || gpu_text_contains(text, "mt6")
               || gpu_text_contains(text, "mt8") || gpu_text_contains(text, "mt9")
               || gpu_text_contains(prop, "mediatek")) {

        cached_vendor = TM_GPU_VENDOR_MEDIATEK;

    } else if (gpu_text_contains(text, "exynos") || gpu_text_contains(text, "samsung")
               || gpu_text_contains(prop, "samsung")) {

        cached_vendor = TM_GPU_VENDOR_SAMSUNG;

    } else if (gpu_text_contains(text, "tensor") || gpu_text_contains(text, "gs101")
               || gpu_text_contains(text, "gs201") || gpu_text_contains(text, "zum")
               || gpu_text_contains(prop, "google")) {

        cached_vendor = TM_GPU_VENDOR_GOOGLE;
    }

    return cached_vendor;
}

/** 读一条符号链接的最后一段（<node>/device/driver -> mali）。空串表示没有。 */
static void gpu_link_name(const char *path, char *out, int capacity)
{
    out[0] = '\0';

    char target[TM_PATH_MAX];

    target[0] = '\0';

    if (readlink(path, target, sizeof(target) - 1) <= 0) {
        return;
    }

    target[sizeof(target) - 1] = '\0';

    const char *slash = strrchr(target, '/');

    snprintf(out, (size_t) capacity, "%s", (slash != NULL) ? (slash + 1) : target);
}

/** 规范化后的真实路径：读不到就原样返回，保证调用方总有可比对的字符串。 */
static void gpu_real_path(const char *path, char *out, int capacity)
{
    char *resolved = realpath(path, NULL);

    if (resolved != NULL) {

        snprintf(out, (size_t) capacity, "%s", resolved);

        free(resolved);

        return;
    }

    snprintf(out, (size_t) capacity, "%s", path);
}

static bool gpu_seen_has(const TmGpuSeen *seen, const char *real_path)
{
    for (int i = 0; i < seen->count; i++) {

        if (strcmp(seen->path[i], real_path) == 0) {
            return true;
        }
    }

    return false;
}

static void gpu_seen_add(TmGpuSeen *seen, const char *real_path)
{
    if (seen->count >= (int) (sizeof(seen->path) / sizeof(seen->path[0]))) {
        return;
    }

    snprintf(seen->path[seen->count], sizeof(seen->path[0]), "%s", real_path);

    seen->count++;
}

/**
 * 登记一张卡：去重后追加。
 *
 * 去重用的是"真实路径"：/sys/class/misc/mali0/device 与
 * /sys/bus/platform/devices/13000000.mali 指向同一个平台设备，只登记一次。
 *
 * @return 追加后的新条数（被去重时原样返回）
 */
static int gpu_add(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen,
                   const char *node, const char *name, int vendor)
{
    if (count >= capacity) {
        return count;
    }

    char node_real[TM_PATH_MAX];

    gpu_real_path(node, node_real, (int) sizeof(node_real));

    if (gpu_seen_has(seen, node_real)) {
        return count;
    }

    char device_path[TM_PATH_MAX];

    snprintf(device_path, sizeof(device_path), "%s/device", node);

    char device_real[TM_PATH_MAX];

    gpu_real_path(device_path, device_real, (int) sizeof(device_real));

    if (strcmp(device_real, device_path) != 0 && gpu_seen_has(seen, device_real)) {
        return count;
    }

    // id 是稳定索引（gpu0 / gpu1）：哪张卡是哪个由 node 说明
    snprintf(out[count].id, sizeof(out[count].id), "gpu%d", count);

    snprintf(out[count].path, sizeof(out[count].path), "%s", node);

    snprintf(out[count].name, sizeof(out[count].name), "%s", name);

    out[count].vendor = vendor;

    ensure_device_name(out[count].name, (int) sizeof(out[count].name));

    gpu_seen_add(seen, node_real);

    if (strcmp(device_real, device_path) != 0) {
        gpu_seen_add(seen, device_real);
    }

    return count + 1;
}

/**
 * 登记一个 KGSL 3D 节点：枚举与固定路径两趟共用同一套"读型号 + 加入"。
 *
 * stat 只要求路径上的 search 权限，不读目录内容 —— 这正是"目录枚举被 SELinux
 * 挡住、单个节点仍可访问"时还能确认节点存在的原因。节点在、字段读不出来，
 * 由下游各字段各自报状态，不在这里提前放弃。
 */
static int gpu_register_kgsl_node(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen,
                                  const char *path)
{
    struct stat info;

    if (stat(path, &info) != 0) {
        return count;
    }

    // gpu_model 是高通导出的芯片名（Adreno735 / Adreno (TM) 740 这种）。
    // 原文写法不统一，这里过一遍规范名函数，统一成
    // "Qualcomm(R) Adreno(TM) 735"；不是 Adreno 的型号只加厂商前缀。
    // 读不出来就用空型号 —— 名字退成 "Qualcomm(R) Adreno(TM)"，设备照样登记。
    char model[64];

    model[0] = '\0';

    char model_path[TM_PATH_MAX];

    snprintf(model_path, sizeof(model_path), "%s/gpu_model", path);

    read_text_file(model_path, model, (int) sizeof(model));

    trim_disk_text(model);

    char display[TM_OUT_VALUE_MAX];

    gpu_qualcomm_model_name(model, display, (int) sizeof(display));

    return gpu_add(out, capacity, count, seen, path, display, TM_GPU_VENDOR_QUALCOMM);
}

/**
 * 1) 高通：kgsl-3dN（3D 图形核；kgsl-2d 是 2D 合成，不算显卡）。
 *
 * 两条路都走，谁也替代不了谁：
 *   ① 枚举 /sys/class/kgsl —— 内核里到底挂了几个 3D 核，只有列目录才知道；
 *   ② 固定路径 kgsl-3d0 .. kgsl-3d3 —— 枚举依赖"读目录"（readdir）权限，
 *      某些 SELinux 域（典型是 adb shell）允许打开目录里的具体文件、却不允许
 *      读目录列表：opendir 直接失败，可 gpubusy 明明读得动。少了这一步，
 *      就会把一颗能读到利用率的 Adreno 误判成"这台机器没有 GPU"。
 *
 * 两条路的结果按真实路径去重。只有 stat 失败才算"节点不存在"；
 * 真读不出数据时，逐字段报状态，那才是结论。
 */
static int gpu_discover_kgsl(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen)
{
    DIR *dir = opendir("/sys/class/kgsl");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "kgsl-3d", 7) != 0) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "/sys/class/kgsl/%s", entry->d_name);

            count = gpu_register_kgsl_node(out, capacity, count, seen, path);
        }

        closedir(dir);
    }

    static const char *const fixed_nodes[] = {
        "/sys/class/kgsl/kgsl-3d0",
        "/sys/class/kgsl/kgsl-3d1",
        "/sys/class/kgsl/kgsl-3d2",
        "/sys/class/kgsl/kgsl-3d3",
    };

    for (size_t i = 0; i < sizeof(fixed_nodes) / sizeof(fixed_nodes[0]); i++) {
        count = gpu_register_kgsl_node(out, capacity, count, seen, fixed_nodes[i]);
    }

    return count;
}

/**
 * 登记一个 kbase 节点（/sys/class/misc/maliN）：枚举与固定路径共用。
 * gpuinfo 是 kbase 的版本行：Mali-G57 5 cores r0p1 0x9091。
 */
static int gpu_register_mali_misc_node(TmDeviceRef *out, int capacity, int count,
                                       TmGpuSeen *seen, const char *path, int vendor)
{
    struct stat info;

    if (stat(path, &info) != 0) {
        return count;
    }

    char info_text[64];

    info_text[0] = '\0';

    char info_path[TM_PATH_MAX];

    snprintf(info_path, sizeof(info_path), "%s/device/gpuinfo", path);

    read_text_file(info_path, info_text, (int) sizeof(info_text));

    trim_disk_text(info_text);

    if (info_text[0] == '\0') {

        // 有的内核把 gpuinfo 直接摆在 misc 节点下，不挂在 device 里
        snprintf(info_path, sizeof(info_path), "%s/gpuinfo", path);

        read_text_file(info_path, info_text, (int) sizeof(info_text));

        trim_disk_text(info_text);
    }

    return gpu_add(out, capacity, count, seen, path,
                   info_text[0] != '\0' ? info_text : "ARM Mali GPU", vendor);
}

/**
 * 2) Mali 系：/sys/class/misc 下的 maliN。
 *
 * 与 KGSL 同理两趟走：枚举 + mali0 / mali1 固定路径。
 * 目录枚举被挡住时，固定名字照样能取到 gpuinfo —— 能读到就说明这块 GPU 在。
 */
static int gpu_discover_mali_misc(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen)
{
    int vendor = gpu_soc_vendor();

    if (vendor == TM_GPU_VENDOR_UNKNOWN || vendor == TM_GPU_VENDOR_QUALCOMM) {
        vendor = TM_GPU_VENDOR_ARM;
    }

    DIR *dir = opendir("/sys/class/misc");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "mali", 4) != 0) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "/sys/class/misc/%s", entry->d_name);

            count = gpu_register_mali_misc_node(out, capacity, count, seen, path, vendor);
        }

        closedir(dir);
    }

    static const char *const fixed_nodes[] = {
        "/sys/class/misc/mali0",
        "/sys/class/misc/mali1",
    };

    for (size_t i = 0; i < sizeof(fixed_nodes) / sizeof(fixed_nodes[0]); i++) {
        count = gpu_register_mali_misc_node(out, capacity, count, seen, fixed_nodes[i], vendor);
    }

    return count;
}

/**
 * 3) Mali 的平台设备：/sys/bus/platform/devices 下名字带 mali 的那个
 *    （13000000.mali / soc:mali 这种，由 DTS 决定）。
 *
 * 枚举 + 固定兜底：平台设备名没法预先知道，但 kbase 一定会在 /sys/class/misc
 * 下挂 maliN，maliN/device 就是那个平台设备 —— 用它可以绕过 platforms 目录枚举。
 */
static int gpu_discover_mali_platform(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen)
{
    int vendor = gpu_soc_vendor();

    if (vendor == TM_GPU_VENDOR_UNKNOWN || vendor == TM_GPU_VENDOR_QUALCOMM) {
        vendor = TM_GPU_VENDOR_ARM;
    }

    DIR *dir = opendir("/sys/bus/platform/devices");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strstr(entry->d_name, "mali") == NULL) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "/sys/bus/platform/devices/%s", entry->d_name);

            struct stat info;

            if (stat(path, &info) != 0) {
                continue;
            }

            count = gpu_add(out, capacity, count, seen, path, "ARM Mali GPU", vendor);
        }

        closedir(dir);
    }

    static const char *const fixed_nodes[] = {
        "/sys/class/misc/mali0/device",
        "/sys/class/misc/mali1/device",
        "/sys/bus/platform/devices/mali",
        "/sys/bus/platform/devices/mali0",
    };

    for (size_t i = 0; i < sizeof(fixed_nodes) / sizeof(fixed_nodes[0]); i++) {

        struct stat info;

        if (stat(fixed_nodes[i], &info) != 0) {
            continue;
        }

        count = gpu_add(out, capacity, count, seen, fixed_nodes[i], "ARM Mali GPU", vendor);
    }

    return count;
}

/** 现有登记表里有没有某厂商的卡（避免同一颗 GPU 被 KGSL 与 msm 各记一次）。 */
static bool gpu_has_vendor(const TmDeviceRef *out, int count, int vendor)
{
    if (out == NULL) {
        return false;
    }

    for (int k = 0; k < count; k++) {

        if (out[k].vendor == vendor) {
            return true;
        }
    }

    return false;
}

/** DRM 驱动名 -> 厂商；认不出来返回 UNKNOWN（卡片仍然登记，名字退成 "GPU"）。 */
static int gpu_vendor_of_driver(const char *driver)
{
    if (strcasecmp(driver, "amdgpu") == 0 || strcasecmp(driver, "radeon") == 0) {
        return TM_GPU_VENDOR_AMD;
    }

    if (strcasecmp(driver, "nvidia") == 0 || strcasecmp(driver, "nouveau") == 0
        || strcmp(driver, "nvidia-drm") == 0) {
        return TM_GPU_VENDOR_NVIDIA;
    }

    if (strcasecmp(driver, "i915") == 0 || strcasecmp(driver, "xe") == 0) {
        return TM_GPU_VENDOR_INTEL;
    }

    if (strcasecmp(driver, "msm") == 0 || strcasecmp(driver, "msm_drm") == 0
        || strcasecmp(driver, "kgsl") == 0) {
        return TM_GPU_VENDOR_QUALCOMM;
    }

    if (strcasecmp(driver, "virtio_gpu") == 0) {
        return TM_GPU_VENDOR_VIRTIO;
    }

    if (strcasecmp(driver, "panfrost") == 0 || strcasecmp(driver, "panthor") == 0
        || strcasecmp(driver, "kbase") == 0) {
        int vendor = gpu_soc_vendor();
        return (vendor == TM_GPU_VENDOR_UNKNOWN || vendor == TM_GPU_VENDOR_QUALCOMM)
               ? TM_GPU_VENDOR_ARM : vendor;
    }

    if (strcasecmp(driver, "pvrsrvkm") == 0 || strcasecmp(driver, "powervr") == 0) {
        return TM_GPU_VENDOR_IMAGINATION;
    }

    if (strcasecmp(driver, "exynos") == 0 || strstr(driver, "exynos") != NULL) {
        return TM_GPU_VENDOR_SAMSUNG;
    }

    return TM_GPU_VENDOR_UNKNOWN;
}

/**
 * 登记一个 DRM cardN：枚举与固定路径两趟共用。
 *
 * 驱动名读不出来（权限 / 老内核）也照样登记：name 退成 "GPU" 总比整页空白强，
 * 具体字段读不到由下游各自报状态。
 */
static int gpu_register_drm_card(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen,
                                 const char *path)
{
    struct stat info;

    if (stat(path, &info) != 0) {
        return count;
    }

    char driver[64];

    driver[0] = '\0';

    char driver_link[TM_PATH_MAX];

    snprintf(driver_link, sizeof(driver_link), "%s/device/driver", path);

    gpu_link_name(driver_link, driver, (int) sizeof(driver));

    int vendor = gpu_vendor_of_driver(driver);

    // 高通：KGSL 那一趟通常已经登记过同一颗 GPU，这里不再追加；
    // 但老内核 / kgsl 整个不可读时，msm 的 cardN 是唯一线索，必须收下。
    if (vendor == TM_GPU_VENDOR_QUALCOMM
        && gpu_has_vendor(out, count, TM_GPU_VENDOR_QUALCOMM)) {
        return count;
    }

    char name[64];

    gpu_drm_vendor_name(path, name, (int) sizeof(name));

    if (strcmp(name, "GPU") == 0 && driver[0] != '\0') {
        snprintf(name, sizeof(name), "%s (%s)", gpu_vendor_text(vendor), driver);
    }

    return gpu_add(out, capacity, count, seen, path, name, vendor);
}

/**
 * 5) Linux 官方 DRM：/sys/class/drm 下的 cardN。
 *    renderD* 与 cardN 是同一个设备，只按 cardN 登记；
 *    cardN-XXX 是接口连接器，不是显卡。
 *
 * 枚举 + card0..card3 固定路径：DRM 类目录同样是 readdir 的目标，
 * 枚举被挡时固定编号仍然 stat 得到，读到驱动名就认得出厂商。
 */
static int gpu_discover_drm(TmDeviceRef *out, int capacity, int count, TmGpuSeen *seen)
{
    DIR *dir = opendir("/sys/class/drm");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "card", 4) != 0) {
                continue;
            }

            // cardN 后面可能跟 "-HDMI-A-1" 这类连接器，只认纯数字的卡本身
            if (!is_pure_digits(entry->d_name + 4)) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "/sys/class/drm/%s", entry->d_name);

            count = gpu_register_drm_card(out, capacity, count, seen, path);
        }

        closedir(dir);
    }

    static const char *const fixed_cards[] = {
        "/sys/class/drm/card0",
        "/sys/class/drm/card1",
        "/sys/class/drm/card2",
        "/sys/class/drm/card3",
    };

    for (size_t i = 0; i < sizeof(fixed_cards) / sizeof(fixed_cards[0]); i++) {
        count = gpu_register_drm_card(out, capacity, count, seen, fixed_cards[i]);
    }

    return count;
}

/**
 * 枚举图形设备：四趟发现，前面的结果占位，后面的只补空缺。
 *
 * 上限 TM_OUT_GPU_MAX：手机 / 平板最多一两颗 GPU，四张是给 PCI 多卡留的余量。
 */
int enumerate_gpu_devices(TmDeviceRef *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    TmGpuSeen seen;

    memset(&seen, 0, sizeof(seen));

    int count = 0;

    count = gpu_discover_kgsl(out, capacity, count, &seen);

    count = gpu_discover_mali_misc(out, capacity, count, &seen);

    count = gpu_discover_mali_platform(out, capacity, count, &seen);

    count = gpu_discover_drm(out, capacity, count, &seen);

    return count;
}

// ============================================================
// 显卡设备：按厂商取字段
//
// 枚举（enumerate_gpu_devices）定下"这台机器有哪几张卡、各是哪一家"，
// 本节负责把每一格填上。结构是两层：
//
//   填写函数（gpu_field_usage 这类）：只知道"要填哪一格"，不关心厂商；
//   厂商读取函数（gpu_qualcomm_usage 这类）：知道具体文件在哪，读回来交给填写函数。
//
// 顺序一律是"厂商专用接口 -> 通用 DRM 接口 -> 发状态"：
//   高通      gpu_busy_percentage / gpubusy / temp / gpu_model
//   联发科    /sys/module/ged/parameters/gpu_loading、/sys/kernel/ged/hal/gpu_utilization
//   Mali 系   devfreq 的 load（kbase / panfrost / panthor 都导出这一格）
//   通用 DRM  device/gpu_busy_percent、device/mem_info_vram_*、device/mem_info_gtt_*
//   温度      先 device/hwmon/hwmonN/temp1_input，再退回 type 里带 gpu 的热区
//
// 读不到就发 `!状态`（客户端折成 null，页面写"不支持 / -"），不拿 0 顶替。
//
// 图形 API（页面上"DirectX 版本"那一格换成它）：
//   1) 先问 libvulkan 的 vkEnumerateInstanceVersion —— 这是真实版本号；
//   2) 没有再读 ro.opengles.version（十进制 0xMMmm，例如 196610 = 3.2）；
//   3) 两样都查过、都没有，才发 gfx_api=NONE（明确不支持）；
//   4) 连查都查不了（dlopen 与 getprop 都失败）就不发这两格，页面写"-"。
//
// 读不到的格子一律不发：页面按"不支持 / -"渲染，不拿 0 顶替。
// ============================================================

/** 读文件里的第一个整数（允许前导空白与后缀单位，如 "12 %"）。 */
static bool gpu_read_first_int(const char *path, long long *value)
{
    char text[64];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    const char *cursor = text;

    while (*cursor != '\0' && !isdigit((unsigned char) *cursor) && *cursor != '-') {
        cursor++;
    }

    if (*cursor == '\0') {
        return false;
    }

    char digits[32];

    int count = 0;

    if (*cursor == '-') {
        digits[count++] = *cursor++;
    }

    while (isdigit((unsigned char) *cursor) && count < 31) {
        digits[count++] = *cursor++;
    }

    digits[count] = '\0';

    if (value != NULL) {
        *value = strtoll(digits, NULL, 10);
    }

    return true;
}

/** 读一个百分比（夹到 0..100）。读不到返回 false。 */
static bool gpu_read_percent(const char *path, long long *percent)
{
    long long value = 0;

    if (!gpu_read_first_int(path, &value)) {
        return false;
    }

    if (value < 0) value = 0;

    if (value > 100) value = 100;

    if (percent != NULL) {
        *percent = value;
    }

    return true;
}

/** 文本里有没有 gpu 三个字母（忽略大小写）：判断某个热区是不是 GPU 的。 */
static bool gpu_text_has_gpu(const char *text)
{
    char lower[64];

    int count = 0;

    for (; text[count] != '\0' && count < 63; count++) {
        lower[count] = (char) tolower((unsigned char) text[count]);
    }

    lower[count] = '\0';

    return strstr(lower, "gpu") != NULL;
}

/** 检查一个热区：type 带 gpu、且 temp 读得出来，才算 GPU 温度。 */
static bool gpu_thermal_zone_check(const char *zone, long long *celsius)
{
    char path[256];

    snprintf(path, sizeof(path), "/sys/class/thermal/%s/type", zone);

    char type_text[64];

    if (read_text_file(path, type_text, (int) sizeof(type_text)) <= 0) {
        return false;
    }

    if (!gpu_text_has_gpu(type_text)) {
        return false;
    }

    snprintf(path, sizeof(path), "/sys/class/thermal/%s/temp", zone);

    long long milli = 0;

    if (!gpu_read_first_int(path, &milli)) {
        return false;
    }

    *celsius = milli / 1000;

    return true;
}

/**
 * 兜底温度：/sys/class/thermal 里 type 带 gpu 的第一个热区。
 *
 * 两趟走，理由与 GPU 发现一样：热区编号由内核按注册顺序定，不枚举不知道有几条；
 * 但枚举要"读目录"权限，被 SELinux 挡住时，thermal_zone0..15 的固定编号仍能
 * 一个个直接读 type —— 读得到就认得出来，不因为列不了目录就把温度判成没有。
 */
static bool gpu_thermal_zone_temperature(long long *celsius)
{
    DIR *dir = opendir("/sys/class/thermal");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            if (strncmp(entry->d_name, "thermal_zone", 12) != 0) {
                continue;
            }

            if (gpu_thermal_zone_check(entry->d_name, celsius)) {

                closedir(dir);

                return true;
            }
        }

        closedir(dir);
    }

    for (int index = 0; index < 16; index++) {

        char zone[32];

        snprintf(zone, sizeof(zone), "thermal_zone%d", index);

        if (gpu_thermal_zone_check(zone, celsius)) {
            return true;
        }
    }

    return false;
}

/**
 * 物理位置：DRM 节点若有 PCI_SLOT_NAME（domain:bus:device.function），
 * 只把 bus / device / function 三个整数带回去，由客户端拼成本地语言文案；
 * SoC 核显不是 PCI 设备，这三个值就一个都不发。
 */
static bool gpu_pci_location(const char *device_path,
                             unsigned int *out_bus, unsigned int *out_device,
                             unsigned int *out_function)
{
    char path[256];

    snprintf(path, sizeof(path), "%s/device/uevent", device_path);

    char text[512];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    const char *cursor = strstr(text, "PCI_SLOT_NAME=");

    if (cursor == NULL) {
        return false;
    }

    cursor += strlen("PCI_SLOT_NAME=");

    unsigned int domain = 0;

    unsigned int bus = 0;

    unsigned int device = 0;

    unsigned int function = 0;

    if (sscanf(cursor, "%x:%x:%x.%x", &domain, &bus, &device, &function) != 4) {
        return false;
    }

    *out_bus = bus;
    *out_device = device;
    *out_function = function;

    return true;
}

/** 显卡温度：先找 device 下的 hwmon，没有再退回 thermal zone。 */
static bool gpu_read_temperature(const char *device_path, long long *celsius)
{
    for (int index = 0; index < 4; index++) {

        char path[256];

        snprintf(path, sizeof(path), "%s/device/hwmon/hwmon%d/temp1_input", device_path, index);

        long long milli = 0;

        if (gpu_read_first_int(path, &milli)) {

            *celsius = milli / 1000;

            return true;
        }
    }

    return gpu_thermal_zone_temperature(celsius);
}

/**
 * 探测图形 API：Vulkan 与 OpenGL ES **各查各的**，谁有报谁。
 *
 * 以前是二选一（查到 Vulkan 就不再看 OpenGL），但两者并不互斥：手机上基本同时支持
 * （ro.opengles.version 一直都在），页面就该两行都摆出来。所以这里把结果拆成两份，
 * 由调用方各自决定摆哪一行。
 *
 * @param has_result     是否真的查过（false = 一条都没查成，页面写"-"）
 * @param has_vulkan     Vulkan 可用
 * @param vulkan_version 形如 1.1
 * @param has_opengl     OpenGL ES 可用
 * @param opengl_version 形如 3.2
 *
 * 结果在进程内只算一次：这个值跟硬件走，不会中途变化。
 */
static void gpu_detect_gfx_api(bool *has_result,
                               bool *has_vulkan, char *vulkan_version, int vulkan_size,
                               bool *has_opengl, char *opengl_version, int opengl_size)
{
    static bool cached = false;

    static bool cached_has_result = false;

    static bool cached_has_vulkan = false;

    static char cached_vulkan[24] = {0};

    static bool cached_has_opengl = false;

    static char cached_opengl[24] = {0};

    if (!cached) {

        cached = true;

        bool searched = false;

        // 1) Vulkan：loader 给出 32 位版本号（major 在 bit22 起，minor 在 bit12 起）
        void *library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);

        if (library != NULL) {

            searched = true;

            gpu_vulkan_version_fn enumerate =
                (gpu_vulkan_version_fn) dlsym(library, "vkEnumerateInstanceVersion");

            unsigned int encoded = (1u << 22);   // 老 loader 没这个函数：按 Vulkan 1.0 算

            if (enumerate != NULL) {

                encoded = 0;

                if (enumerate(&encoded) != 0) {
                    encoded = 0;
                }
            }

            if (encoded != 0) {

                cached_has_vulkan = true;

                snprintf(cached_vulkan, sizeof(cached_vulkan), "%u.%u",
                         encoded >> 22, (encoded >> 12) & 0x3ffu);
            }

            dlclose(library);
        }

        // 2) OpenGL ES：ro.opengles.version 是十进制 0xMMmm
        FILE *pipe = popen("getprop ro.opengles.version 2>/dev/null", "r");

        if (pipe != NULL) {

            searched = true;

            char text[32] = {0};

            if (fgets(text, (int) sizeof(text), pipe) == NULL) {
                text[0] = '\0';
            }

            pclose(pipe);

            if (text[0] != '\0') {

                int encoded = atoi(text);

                if (encoded > 0) {

                    cached_has_opengl = true;

                    snprintf(cached_opengl, sizeof(cached_opengl), "%d.%d",
                             encoded >> 16, encoded & 0xffff);
                }
            }
        }

        if (searched) {
            cached_has_result = true;
        }
    }

    *has_result = cached_has_result;
    *has_vulkan = cached_has_vulkan;
    *has_opengl = cached_has_opengl;

    snprintf(vulkan_version, (size_t) vulkan_size, "%s", cached_vulkan);

    snprintf(opengl_version, (size_t) opengl_size, "%s", cached_opengl);
}

static GpuField gpu_field_value(long long number)
{
    GpuField field;

    memset(&field, 0, sizeof(field));

    field.has = true;
    field.number = number;

    return field;
}

static GpuField gpu_field_missing(const char *status)
{
    GpuField field;

    memset(&field, 0, sizeof(field));

    field.status = status;

    return field;
}

/**
 * 节点"在不在"：stat 成功说明路径存在（只要求父目录的 search 权限，不读文件内容）。
 *
 * 用途是区分两种"什么都没读到"：
 *   - 路径存在、值读不出来  -> 权限问题，报 ACCESS_DENIED（界面显示 "-"）
 *   - 路径根本不存在        -> 这个平台没导出这一格，报 NOT_AVAILABLE（"不支持"）
 * 绝不能在"目录枚举失败"这一步就把整块判死。
 */
static bool gpu_node_exists(const char *path)
{
    struct stat info;

    return stat(path, &info) == 0;
}

/** 候选路径里只要有一个存在却读不出来，就按权限问题报。 */
static GpuField gpu_missing_from_paths(const char *const *paths, size_t path_count)
{
    for (size_t i = 0; i < path_count; i++) {

        if (paths[i] != NULL && gpu_node_exists(paths[i])) {
            return gpu_field_missing(TM_FIELD_STATUS_ACCESS_DENIED);
        }
    }

    return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
}

/** 一次性读两个整数（gpubusy 那种"忙碌 总计"的格式）。 */
static bool gpu_read_two_ints(const char *path, long long *first, long long *second)
{
    char text[96];

    if (read_text_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    long long a = 0;

    long long b = 0;

    if (sscanf(text, "%lld %lld", &a, &b) != 2) {
        return false;
    }

    *first = a;
    *second = b;

    return true;
}

/** 取某个 devfreq 设备的 load（"22@300000000Hz" 里的 22，夹到 0..100）。 */
static bool gpu_devfreq_read_load(const char *device, long long *percent)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/sys/class/devfreq/%s/load", device);

    long long value = 0;

    if (!gpu_read_first_int(path, &value)) {
        return false;
    }

    if (value < 0) value = 0;

    if (value > 100) value = 100;

    *percent = value;

    return true;
}

/**
 * devfreq 的 load 一格：Mali / panfrost / panthor 都走这个。
 *
 * 格式是 "22@300000000Hz"（占用百分比 @ 当前频率），也见过只写百分比的。
 * 只取 @ 前面那个数，夹到 0..100。
 *
 * 枚举之后还有固定兜底：devfreq 设备名是厂商起的（gpu / mali / mtk_gpu …），
 * 穷举不了，但常见的那几个先直接试一遍 —— 目录枚举被挡时，名字对了就能读到。
 */
static bool gpu_devfreq_load(long long *percent)
{
    DIR *dir = opendir("/sys/class/devfreq");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            bool looks_gpu = strstr(entry->d_name, "gpu") != NULL
                             || strstr(entry->d_name, "mali") != NULL
                             || strstr(entry->d_name, "pan") != NULL;

            if (!looks_gpu) {
                continue;
            }

            if (gpu_devfreq_read_load(entry->d_name, percent)) {

                closedir(dir);

                return true;
            }
        }

        closedir(dir);
    }

    static const char *const fixed_devices[] = {
        "gpu",
        "mali",
        "kgsl-3d0",
        "mtk_gpu",
        "soc:gpu",
        "13000000.mali",
        "5000000.mali"
    };

    for (size_t i = 0; i < sizeof(fixed_devices) / sizeof(fixed_devices[0]); i++) {

        if (gpu_devfreq_read_load(fixed_devices[i], percent)) {
            return true;
        }
    }

    return false;
}

/**
 * 高通：GPU 利用率。
 *
 *   gpu_busy_percentage  直接给百分比（"37 %"），最省事，优先用；
 *   gpubusy              给两个计数（忙碌 / 总计），自己算比例；
 *   device/gpu_busy_percent  个别机型只留了 DRM 这一格。
 *
 * 一份数据三个来源，取第一个能读到的，绝不相加。
 */
static GpuField gpu_qualcomm_usage(const char *base)
{
    char path[TM_PATH_MAX];

    long long percent = 0;

    snprintf(path, sizeof(path), "%s/gpu_busy_percentage", base);

    if (gpu_read_percent(path, &percent)) {
        return gpu_field_value(percent);
    }

    long long busy = 0;

    long long total = 0;

    snprintf(path, sizeof(path), "%s/gpubusy", base);

    if (gpu_read_two_ints(path, &busy, &total) && total > 0 && busy >= 0) {

        long long ratio = busy * 100 / total;

        if (ratio < 0) ratio = 0;

        if (ratio > 100) ratio = 100;

        return gpu_field_value(ratio);
    }

    snprintf(path, sizeof(path), "%s/device/gpu_busy_percent", base);

    if (gpu_read_percent(path, &percent)) {
        return gpu_field_value(percent);
    }

    // 三个候选全没读出来时再分一次状态：节点在 = 权限不够（"-"），
    // 节点根本不存在 = 这平台没导出利用率（"不支持"）。
    char busy_path[TM_PATH_MAX];
    char ratio_path[TM_PATH_MAX];
    char drm_path[TM_PATH_MAX];

    snprintf(busy_path, sizeof(busy_path), "%s/gpu_busy_percentage", base);
    snprintf(ratio_path, sizeof(ratio_path), "%s/gpubusy", base);
    snprintf(drm_path, sizeof(drm_path), "%s/device/gpu_busy_percent", base);

    const char *const usage_candidates[] = { busy_path, ratio_path, drm_path };

    return gpu_missing_from_paths(usage_candidates,
                                  sizeof(usage_candidates) / sizeof(usage_candidates[0]));
}

/** 联发科：GED 框架与它的 hal 节点。两处都试，先读到的算。 */
static GpuField gpu_mediatek_usage(void)
{
    static const char *const paths[] = {
        "/sys/module/ged/parameters/gpu_loading",
        "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/kernel/ged/hal/loading_percentage"
    };

    long long percent = 0;

    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {

        if (gpu_read_percent(paths[i], &percent)) {
            return gpu_field_value(percent);
        }
    }

    return gpu_missing_from_paths(paths, sizeof(paths) / sizeof(paths[0]));
}

/** 通用 DRM（amdgpu / i915 / panfrost 等）：驱动自己导出的占用文件。 */
static GpuField gpu_drm_usage(const char *base)
{
    char path[TM_PATH_MAX];

    long long percent = 0;

    snprintf(path, sizeof(path), "%s/device/gpu_busy_percent", base);

    if (gpu_read_percent(path, &percent)) {
        return gpu_field_value(percent);
    }

    snprintf(path, sizeof(path), "%s/gpu_busy_percent", base);

    if (gpu_read_percent(path, &percent)) {
        return gpu_field_value(percent);
    }

    return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
}

/**
 * 填写函数：GPU 利用率。
 *
 * 顺序：本厂商的专用接口 -> devfreq（Mali 系）-> 通用 DRM -> 状态。
 * 前两层对高通/联发科不适用它们自己的路径已经覆盖，这里只是兜底。
 */
static GpuField gpu_field_usage(const TmDeviceRef *device)
{
    GpuField field;

    switch (device->vendor) {

        case TM_GPU_VENDOR_QUALCOMM:

            field = gpu_qualcomm_usage(device->path);

            if (field.has) {
                return field;
            }

            break;

        case TM_GPU_VENDOR_MEDIATEK:

            field = gpu_mediatek_usage();

            if (field.has) {
                return field;
            }

            break;

        case TM_GPU_VENDOR_SAMSUNG:
        case TM_GPU_VENDOR_GOOGLE:
        case TM_GPU_VENDOR_ARM:
        case TM_GPU_VENDOR_IMAGINATION:
        default:

            break;
    }

    long long percent = 0;

    if (gpu_devfreq_load(&percent)) {
        return gpu_field_value(percent);
    }

    return gpu_drm_usage(device->path);
}

/**
 * 高通温度：kgsl 的 temp。
 *
 * 单位在不同内核版本上不一样（有的直接摄氏度，有的毫摄氏度），按量级猜：
 * ≥200 当毫摄氏度。这是**猜测**，不是读到的元数据；猜错最多差一千倍，
 * 但 200 度以上的手机 GPU 不存在，所以这条界线足够安全。
 */
static GpuField gpu_qualcomm_temperature(const char *base)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/temp", base);

    long long raw = 0;

    if (gpu_read_first_int(path, &raw)) {

        long long celsius = (raw >= 200) ? (raw / 1000) : raw;

        return gpu_field_value(celsius);
    }

    // temp 节点在、值读不出来：权限问题（"-"）；节点不在：这版内核没导出温度
    const char *const temperature_candidates[] = { path };

    return gpu_missing_from_paths(temperature_candidates,
                                  sizeof(temperature_candidates) / sizeof(temperature_candidates[0]));
}

/** 填写函数：GPU 温度（厂商专用 -> hwmon -> 热区 -> 状态）。 */
static GpuField gpu_field_temperature(const TmDeviceRef *device)
{
    if (device->vendor == TM_GPU_VENDOR_QUALCOMM) {

        GpuField field = gpu_qualcomm_temperature(device->path);

        if (field.has) {
            return field;
        }
    }

    long long celsius = 0;

    if (gpu_read_temperature(device->path, &celsius)) {
        return gpu_field_value(celsius);
    }

    return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
}

/** 填写函数：专用显存总量 / 已用（amdgpu 这类有真 VRAM 的卡才有）。 */
static GpuField gpu_field_dedicated(const TmDeviceRef *device, bool used)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/device/mem_info_vram_%s", device->path,
             used ? "used" : "total");

    long long bytes = 0;

    if (gpu_read_first_int(path, &bytes) && bytes >= 0) {
        return gpu_field_value(bytes);
    }

    // 手机上的集成 GPU（Adreno / Mali）没有独立显存，这一格就该空着，
    // 不能拿系统内存或 KGSL 的 vmalloc 冒充
    return gpu_field_missing(TM_FIELD_STATUS_UNSUPPORTED);
}

/** 这个 dma-buf 是不是 GPU 自己申请的：exporter 名里带 mali / ged / mediatek / mtk。 */
static bool gpu_dmabuf_is_gpu_exporter(const char *name)
{
    return gpu_text_contains(name, "mali")
        || gpu_text_contains(name, "ged")
        || gpu_text_contains(name, "mediatek")
        || gpu_text_contains(name, "mtk");
}

/**
 * 通用兜底：汇总 dma-buf 里 GPU 申请的那部分内存。
 *
 * 内核 4.19 起 /sys/kernel/dmabuf/buffers/<inode>/ 每项带 exp_name 与 size（字节），
 * 这是全平台统一的统计口。高通有 KGSL mapped 就先走那边，Mali 设备（联发科 /
 * 三星 / Google）只能靠这一格。只累加 GPU exporter 的条目 —— 相机、显示那些
 * dma-buf 不算进来，所以这个数和"GPU 映射住的内存"同义。
 *
 * 扫目录要读上百个小文件，所以按 1 秒缓存：页面 1Hz 刷新，每秒算一遍足够。
 *
 * @return true = 确实扫到了 GPU exporter 的条目（值可能是 0）；false = 没有这个接口
 */
static bool gpu_dmabuf_gpu_bytes(long long *bytes)
{
    static bool cached = false;

    static bool cached_ok = false;

    static long long cached_bytes = 0;

    static time_t cached_at = 0;

    time_t now = time(NULL);

    if (cached && now == cached_at) {

        *bytes = cached_bytes;

        return cached_ok;
    }

    cached = true;

    cached_at = now;

    cached_ok = false;

    cached_bytes = 0;

    const char *roots[] = {
        "/sys/kernel/dmabuf/buffers",
        "/sys/kernel/dma_buf/buffers",
    };

    for (int root_index = 0; root_index < 2 && !cached_ok; root_index++) {

        DIR *dir = opendir(roots[root_index]);

        if (dir == NULL) {
            continue;
        }

        bool matched = false;

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {

            // 只认 inode 号这类纯数字目录
            if (!is_pure_digits(entry->d_name)) {
                continue;
            }

            char path[TM_PATH_MAX];

            snprintf(path, sizeof(path), "%s/%s/exp_name", roots[root_index], entry->d_name);

            char exporter[64];

            if (read_text_file(path, exporter, (int) sizeof(exporter)) <= 0) {
                continue;
            }

            trim_disk_text(exporter);

            if (!gpu_dmabuf_is_gpu_exporter(exporter)) {
                continue;
            }

            snprintf(path, sizeof(path), "%s/%s/size", roots[root_index], entry->d_name);

            long long size = 0;

            if (gpu_read_first_int(path, &size) && size > 0) {

                cached_bytes += size;

                matched = true;
            }
        }

        closedir(dir);

        cached_ok = matched;
    }

    *bytes = cached_bytes;

    return cached_ok;
}

/**
 * 填写函数：共享显存（DRM 的 gtt -> 高通 KGSL 的 mapped -> dma-buf 汇总 -> 不支持）。
 *
 * 桌面卡走 DRM：<node>/device/mem_info_gtt_total / _used。
 *
 * 手机的 GPU 用系统内存，没有独立显存，能拿到的是"GPU 映射住的内存量"：
 *   高通 —— /sys/class/kgsl/kgsl/mapped，一个字，语义就是 GPU Mapped Memory；
 *   其它（Mali：联发科 / 三星 / Google）—— 没有等价的一格，退到内核统一的
 *   dma-buf 统计，只累加 GPU exporter 申请的缓冲（见 gpu_dmabuf_gpu_bytes）。
 *
 * 总量厂商都不给，按"GPU 能映射的内存池"取 /proc/meminfo 的 MemTotal ——
 * Android 上整块内存都能给 GPU 映射，没有更窄的上限，不拿一半硬套。
 *
 * 两样都读不到就一律"不支持"，不替没有这一格的平台编一个总量出来。
 */
static GpuField gpu_field_shared(const TmDeviceRef *device, bool used)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/device/mem_info_gtt_%s", device->path,
             used ? "used" : "total");

    long long bytes = 0;

    if (gpu_read_first_int(path, &bytes) && bytes >= 0) {
        return gpu_field_value(bytes);
    }

    long long mapped = 0;

    bool has_mapped = gpu_read_first_int("/sys/class/kgsl/kgsl/mapped", &mapped) && mapped >= 0;

    // 高通之外（Mali / 联发科 / 三星 / Google）没有 kgsl：退到通用 dma-buf 汇总
    if (!has_mapped) {
        has_mapped = gpu_dmabuf_gpu_bytes(&mapped);
    }

    if (!has_mapped) {

        // 全都没读出来时再看节点在不在：kgsl 的 mapped、GTT 的 used/total、
        // dma-buf 的 buffers 目录，任何一处存在都说明"这平台有这一格、只是没读到"，
        // 按权限报（界面显示 "-"）；全都找不到才是真不支持。
        char gtt_used_path[TM_PATH_MAX];
        char gtt_total_path[TM_PATH_MAX];

        snprintf(gtt_used_path, sizeof(gtt_used_path), "%s/device/mem_info_gtt_used", device->path);
        snprintf(gtt_total_path, sizeof(gtt_total_path), "%s/device/mem_info_gtt_total", device->path);

        const char *const shared_candidates[] = {
            gtt_used_path,
            gtt_total_path,
            "/sys/class/kgsl/kgsl/mapped",
            "/sys/kernel/dmabuf/buffers",
            "/sys/kernel/dma_buf/buffers",
        };

        GpuField missing = gpu_missing_from_paths(shared_candidates,
                                                  sizeof(shared_candidates) / sizeof(shared_candidates[0]));

        if (missing.status != NULL && strcmp(missing.status, TM_FIELD_STATUS_NOT_AVAILABLE) == 0) {
            return gpu_field_missing(TM_FIELD_STATUS_UNSUPPORTED);
        }

        return missing;
    }

    if (used) {
        return gpu_field_value(mapped);
    }

    static char meminfo[4096];

    if (read_text_file("/proc/meminfo", meminfo, (int) sizeof(meminfo)) <= 0) {
        return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
    }

    long long total = meminfo_bytes(meminfo, "MemTotal");

    if (total > 0) {
        return gpu_field_value(total);
    }

    return gpu_field_missing(TM_FIELD_STATUS_NOT_AVAILABLE);
}

/**
 * 填写函数：内核驱动名与版本。
 *
 * 名字来自 <node>/device/driver 这条链接（mali / amdgpu / kgsl …），
 * 版本来自 /sys/module/<驱动名>/version —— 只有导出 MODULE_VERSION 的驱动才有。
 * 两者分开报：驱动名有、版本没有，是常见情况。
 */
static void gpu_field_driver(const TmDeviceRef *device, char *name, int name_size,
                             char *version, int version_size)
{
    name[0] = '\0';
    version[0] = '\0';

    char link[TM_PATH_MAX];

    snprintf(link, sizeof(link), "%s/device/driver", device->path);

    gpu_link_name(link, name, name_size);

    if (name[0] == '\0') {
        return;
    }

    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "/sys/module/%s/version", name);

    read_text_file(path, version, version_size);

    trim_disk_text(version);
}

/** 整数字段落地：有值写数值，没值写状态。 */
static void gpu_put_int_field(TmOutDevice *device, const char *key, GpuField field)
{
    if (field.has) {
        device_put_int(device, key, field.number, TM_FIELD_STATUS_NOT_AVAILABLE);
    } else {
        device_put_value(device, key, query_value_none(query_status_of(field.status)));
    }
}

/** 文本字段落地：有值写文本，没值写状态。 */
static void gpu_put_text_field(TmOutDevice *device, const char *key,
                               const char *text, const char *status)
{
    if (text != NULL && text[0] != '\0') {
        device_put(device, key, text);
    } else {
        device_put_value(device, key, query_value_none(query_status_of(status)));
    }
}

/**
 * 把显卡实例填进包。
 *
 * 每个实例发：name / node / gpu_usage / gpu_temp_c / dedicated_total_bytes /
 * dedicated_used_bytes / shared_total_bytes / shared_used_bytes（高通走 KGSL
 * mapped，总量按 MemTotal）/ gfx_api / gfx_api_version。
 * 读不到的不发（页面按"不支持 / -"显示）。
 */
int build_gpu_devices(TmOutDevice *out, int capacity,
                             const TmDeviceRef *devices, int count)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    int limit = count;

    if (limit > capacity) {
        limit = capacity;
    }

    bool has_api = false;

    bool has_vulkan = false;

    bool has_opengl = false;

    char vulkan_version[24] = {0};

    char opengl_version[24] = {0};

    gpu_detect_gfx_api(&has_api,
                       &has_vulkan, vulkan_version, (int) sizeof(vulkan_version),
                       &has_opengl, opengl_version, (int) sizeof(opengl_version));

    for (int i = 0; i < limit; i++) {

        device_begin(&out[i], devices[i].id, TM_DEV_TYPE_GPU);

        device_put(&out[i], "name", devices[i].name);

        // 这张卡是从哪个节点发现的：枚举时命中的 sysfs 目录原样发上去
        // （/sys/class/kgsl/kgsl-3d0 、/sys/class/misc/mali0 、/sys/class/drm/card0 …）。
        // 与磁盘的 node（块名 sda）一个作用：让上层与日志能分辨"这是哪一张卡"，
        // 名字抓不到时也能拿它兜住，不至于两行都写「不支持」看不出区别。
        device_put(&out[i], "node", devices[i].path);

        // 厂商：客户端拿它决定"这张卡该按哪套接口去理解"，
        // 也让人一眼看出这台机器上是哪家的 GPU（QUALCOMM / MEDIATEK / ARM …）
        device_put(&out[i], "vendor", gpu_vendor_text(devices[i].vendor));

        gpu_put_int_field(&out[i], "gpu_usage", gpu_field_usage(&devices[i]));

        gpu_put_int_field(&out[i], "gpu_temp_c", gpu_field_temperature(&devices[i]));

        gpu_put_int_field(&out[i], "dedicated_total_bytes",
                          gpu_field_dedicated(&devices[i], false));

        gpu_put_int_field(&out[i], "dedicated_used_bytes",
                          gpu_field_dedicated(&devices[i], true));

        gpu_put_int_field(&out[i], "shared_total_bytes",
                          gpu_field_shared(&devices[i], false));

        gpu_put_int_field(&out[i], "shared_used_bytes",
                          gpu_field_shared(&devices[i], true));

        // 驱动：名字（mali / amdgpu / kgsl）+ 版本（只有导出 MODULE_VERSION 的驱动才有）
        char driver_name[64];

        char driver_version[64];

        gpu_field_driver(&devices[i], driver_name, (int) sizeof(driver_name),
                         driver_version, (int) sizeof(driver_version));

        gpu_put_text_field(&out[i], "driver_version", driver_version,
                           TM_FIELD_STATUS_NOT_AVAILABLE);

        // 驱动日期：Linux / Android 没有可靠的发布日期来源，
        // 文件 mtime 只是 OTA 复制时间、内核版本日期也不是驱动日期，
        // 所以这一格一律明确写"不支持"，不猜一个看起来像日期的值
        device_put_value(&out[i], "driver_date",
                         query_value_none(TM_QUERY_UNSUPPORTED));

        unsigned int pci_bus = 0;

        unsigned int pci_device = 0;

        unsigned int pci_function = 0;

        if (gpu_pci_location(devices[i].path, &pci_bus, &pci_device, &pci_function)) {

            device_put_int(&out[i], "pci_bus", (int) pci_bus, TM_FIELD_STATUS_NOT_AVAILABLE);

            device_put_int(&out[i], "pci_device", (int) pci_device, TM_FIELD_STATUS_NOT_AVAILABLE);

            device_put_int(&out[i], "pci_function", (int) pci_function,
                           TM_FIELD_STATUS_NOT_AVAILABLE);
        }

        // 图形 API：两路各报各的，页面上谁有就摆谁。
        //   有版本串   -> 那一行显示 "Vulkan 1.1" / "OpenGL ES 3.2"
        //   值是 NONE  -> 查过、这台机器确实没有那一样（那一行不摆）
        //   键整格缺失 -> 这次没查成（老 so 或探测失败），由客户端退回旧的二选一
        if (has_api) {

            device_put(&out[i], "vulkan_version", has_vulkan ? vulkan_version : "NONE");

            device_put(&out[i], "opengl_version", has_opengl ? opengl_version : "NONE");

            // 兼容老字段：仍发一份"首选 API"，供复制文本等旧路径使用
            device_put(&out[i], "gfx_api", has_vulkan ? "VULKAN" : (has_opengl ? "OPENGL" : "NONE"));

            if (has_vulkan) {
                device_put(&out[i], "gfx_api_version", vulkan_version);
            } else if (has_opengl) {
                device_put(&out[i], "gfx_api_version", opengl_version);
            }
        }
    }

    return limit;
}
