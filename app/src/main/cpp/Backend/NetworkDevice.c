/* ============================================================
 * NetworkDevice.c — 网卡设备：网口枚举、角色判定、地址与吞吐采样。
 * ============================================================ */

#include "Header.h"


static TmNetSample g_net_prev[TM_NET_SAMPLE_MAX];


/** 单调时钟毫秒（速率窗口用，不受系统时间跳变影响）。 */
static long long net_now_ms(void)
{
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return -1;
    }

    return (long long) now.tv_sec * 1000LL + (long long) (now.tv_nsec / 1000000L);
}


/** 路径存在（目录 / 符号链接都算；无线标记有的内核只建链接，不能用 stat 判目录）。 */
static bool net_path_exists(const char *path)
{
    struct stat info;

    return lstat(path, &info) == 0;
}


/** 接口是不是无线（802.11）：wireless 目录或 phy80211 链接任一存在就算。 */
static bool net_is_wireless(const char *iface_path)
{
    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/wireless", iface_path);

    if (net_path_exists(path)) {
        return true;
    }

    snprintf(path, sizeof(path), "%s/phy80211", iface_path);

    return net_path_exists(path);
}


/** 接口名是否以某一组前缀开头（移动网络这类没有 sysfs 标记，只能按名字分）。 */
static bool net_name_has_prefix(const char *name, const char *const *prefixes, size_t count)
{
    if (name == NULL) {
        return false;
    }

    for (size_t i = 0; i < count; i++) {

        size_t length = strlen(prefixes[i]);

        if (strncmp(name, prefixes[i], length) == 0) {
            return true;
        }
    }

    return false;
}


/**
 * 接口角色：只按"能不能在 sysfs 上找到硬件证据"分，不按名字猜。
 *
 *   WIFI      : wireless / phy80211 标记存在（wlan0 / wifi0 / wl* 都归这里）
 *   CELLULAR  : 名字命中 rmnet / ccmni / pdp / wwan 这类移动网络接口
 *   ETHERNET  : 挂在真实总线上（有 device 链接）且不是无线
 *   OTHER     : 其余 —— tun / vpn / docker0 / veth / dummy 这类虚拟接口
 *
 * 注意 OTHER 不等于"虚拟"：判断虚拟要看 device 链接，这里只负责分型，
 * "要不要显示"由上层决定（现在只显示 WIFI）。
 */
static const char *net_role_of(const char *iface_path, const char *iface)
{
    static const char *const cellular_prefixes[] = {
        "rmnet", "ccmni", "pdp", "wwan", "seth", "sr-mham", "v4-rmnet"
    };

    if (net_is_wireless(iface_path)) {
        return TM_NET_ROLE_WIFI;
    }

    if (net_name_has_prefix(iface, cellular_prefixes,
                            sizeof(cellular_prefixes) / sizeof(cellular_prefixes[0]))) {
        return TM_NET_ROLE_CELLULAR;
    }

    char path[TM_PATH_MAX];

    snprintf(path, sizeof(path), "%s/device", iface_path);

    if (net_path_exists(path)) {
        return TM_NET_ROLE_ETHERNET;
    }

    return TM_NET_ROLE_OTHER;
}


/**
 * 读度的入口：把 /sys/class/net 下的**全部**接口枚举出来（只跳过 lo）。
 *
 * 与旧版不同：这里不再过滤 rmnet / tun 这类名字。底层只负责"报事实"，
 * 上层按角色挑要显示的类别；过滤放在这里的话，上层永远拿不到以太网 / 移动网络，
 * 以后要放开就得改底层。
 *
 * 但顺序要保证"真网卡排在前面"：先按角色优先级（WIFI > ETHERNET > CELLULAR > OTHER），
 * 同角色再按接口名字典序，这样 16 个名额（TM_OUT_NETWORK_MAX）不会被 rmnet_ 一大家子占满。
 */
static int compare_network_ref(const void *left, const void *right)
{
    const TmDeviceRef *a = (const TmDeviceRef *) left;
    const TmDeviceRef *b = (const TmDeviceRef *) right;

    int rank_a = 3;
    int rank_b = 3;

    if (strncmp(a->path, "/sys/class/net/", 15) == 0) {

        const char *role_a = net_role_of(a->path, a->name);
        const char *role_b = net_role_of(b->path, b->name);

        rank_a = (strcmp(role_a, TM_NET_ROLE_WIFI) == 0) ? 0
               : (strcmp(role_a, TM_NET_ROLE_ETHERNET) == 0) ? 1
               : (strcmp(role_a, TM_NET_ROLE_CELLULAR) == 0) ? 2 : 3;

        rank_b = (strcmp(role_b, TM_NET_ROLE_WIFI) == 0) ? 0
               : (strcmp(role_b, TM_NET_ROLE_ETHERNET) == 0) ? 1
               : (strcmp(role_b, TM_NET_ROLE_CELLULAR) == 0) ? 2 : 3;
    }

    if (rank_a != rank_b) {
        return (rank_a < rank_b) ? -1 : 1;
    }

    return strcmp(a->name, b->name);
}


/** 登记一块网卡：枚举与固定路径两趟共用（按接口名去重，stat 确认存在）。 */
static int net_add_iface(TmDeviceRef *out, int capacity, int count, const char *iface)
{
    if (iface == NULL || iface[0] == '\0' || iface[0] == '.') {
        return count;
    }

    // 回环不是网卡：它没有硬件，也没有"发送 / 接收"这回事
    if (strcmp(iface, "lo") == 0) {
        return count;
    }

    for (int i = 0; i < count; i++) {

        if (strcmp(out[i].name, iface) == 0) {
            return count;
        }
    }

    if (count >= capacity) {
        return count;
    }

    char probe[TM_PATH_MAX];
    struct stat info;

    snprintf(probe, sizeof(probe), "/sys/class/net/%s", iface);

    if (stat(probe, &info) != 0) {
        return count;
    }

    snprintf(out[count].path, sizeof(out[count].path), "/sys/class/net/%s", iface);

    snprintf(out[count].name, sizeof(out[count].name), "%s", iface);

    return count + 1;
}

/**
 * 枚举网卡：扫 /sys/class/net，只跳过回环。
 *
 * 虚拟接口也枚举（上层要靠角色字段判断），但排序把无线 / 有线 / 移动网络
 * 放在虚拟接口前面，限流时先保住真网卡。
 *
 * 两趟走：枚举 + 常见接口名的固定路径。接口名是内核/厂商起的（wlan0、rmnet_data0
 * 这些），列不了目录时按固定候选逐个 stat，读到就登记 —— 上层再用 isRegisteredNetwork
 * 决定收不收、叫什么名字，底层只负责"这个接口存在"。
 */
int enumerate_network_devices(TmDeviceRef *out, int capacity)
{
    if (out == NULL || capacity <= 0) {
        return 0;
    }

    int count = 0;

    DIR *dir = opendir("/sys/class/net");

    if (dir != NULL) {

        struct dirent *entry;

        while ((entry = readdir(dir)) != NULL) {
            count = net_add_iface(out, capacity, count, entry->d_name);
        }

        closedir(dir);
    }

    static const char *const fixed_ifaces[] = {
        "wlan0",
        "wlan1",
        "eth0",
        "eth1",
        "rmnet0",
        "rmnet_data0",
        "rmnet_data1",
        "ccmni0",
        "pdp0",
        "wwan0",
        "tun0",
    };

    for (size_t i = 0; i < sizeof(fixed_ifaces) / sizeof(fixed_ifaces[0]); i++) {
        count = net_add_iface(out, capacity, count, fixed_ifaces[i]);
    }

    if (count > 1) {
        qsort(out, (size_t) count, sizeof(TmDeviceRef), compare_network_ref);
    }

    for (int i = 0; i < count; i++) {

        // id 是稳定索引（network0 / network1）；具体是哪块网卡由 iface 说明
        snprintf(out[i].id, sizeof(out[i].id), "network%d", i);

        ensure_device_name(out[i].name, (int) sizeof(out[i].name));
    }

    return count;
}


/** 在 "键: 值" 形式的文本里取值，允许行首有空白（iw 的输出每行前面都有制表符）。 */
static bool net_find_value(const char *text, const char *key, char *out, int capacity)
{
    if (text == NULL || text[0] == '\0' || capacity <= 0) {
        return false;
    }

    size_t key_length = strlen(key);

    const char *cursor = text;

    while (*cursor != '\0') {

        const char *line = cursor;

        while (*line == ' ' || *line == '\t') {
            line++;
        }

        if (strncmp(line, key, key_length) == 0) {

            const char *value = line + key_length;

            while (*value == ' ' || *value == '\t') {
                value++;
            }

            int index = 0;

            while (*value != '\0' && *value != '\n' && index < capacity - 1) {
                out[index++] = *value++;
            }

            while (index > 0 && (out[index - 1] == ' ' || out[index - 1] == '\t')) {
                index--;
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


static void net_read_iw_link(const char *iface, TmNetLink *out)
{
    memset(out, 0, sizeof(*out));

    char command[128];

    snprintf(command, sizeof(command), "iw dev %s link 2>/dev/null", iface);

    FILE *file = popen(command, "r");

    if (file == NULL) {
        return;
    }

    char text[2048];

    int total = 0;

    while (total < (int) sizeof(text) - 1) {

        size_t got = fread(text + total, 1, (size_t) (sizeof(text) - 1 - total), file);

        if (got == 0) {
            break;
        }

        total += (int) got;
    }

    text[total] = '\0';

    pclose(file);

    // 没连上时输出是 "Not connected."，三个字段自然都取不到
    net_find_value(text, "SSID:", out->ssid, (int) sizeof(out->ssid));

    net_find_value(text, "freq:", out->freq_mhz, (int) sizeof(out->freq_mhz));

    char signal[64];

    if (net_find_value(text, "signal:", signal, (int) sizeof(signal))) {

        double dbm = 0.0;

        if (sscanf(signal, "%lf", &dbm) == 1) {
            out->has_signal = true;
            out->signal_dbm = dbm;
        }
    }

    /*
     * 连接类型（802.11 代际）藏在这一行的调制名里：
     *   tx bitrate: 866.7 MBit/s 80MHz HE MCS9    -> HE   = 802.11ax（Wi-Fi 6 / 6E）
     *   tx bitrate: 433.3 MBit/s 80MHz VHT MCS9   -> VHT  = 802.11ac（Wi-Fi 5）
     *   tx bitrate: 144.4 MBit/s MCS13 short GI   -> HT   = 802.11n（Wi-Fi 4）
     *   tx bitrate: 54.0 MBit/s OFDM              -> OFDM = 802.11a/g
     * 这是内核唯一会把"当前链路用的是哪一代"写出来的地方。
     */
    char tx[96];

    if (net_find_value(text, "tx bitrate:", tx, (int) sizeof(tx))) {

        if (strstr(tx, "EHT") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11be");

        } else if (strstr(tx, "HE") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11ax");

        } else if (strstr(tx, "VHT") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11ac");

        } else if (strstr(tx, "HT") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11n");

        } else if (strstr(tx, "OFDM") != NULL || strstr(tx, "ERP") != NULL) {

            snprintf(out->link_standard, sizeof(out->link_standard), "802.11g");
        }
    }
}


/**
 * 信号强度百分比：优先用 iw 给的 dBm，退回 /proc/net/wireless 的 level 列。
 *
 * 换算口径与 Windows 一致：percent = 2 × (dBm + 100)，夹到 0..100。
 * （-50 dBm ≈ 100%，-70 dBm ≈ 60%，-90 dBm ≈ 20%。）
 */
static bool net_signal_percent(const char *iface, const TmNetLink *link, double *out)
{
    double dbm = 0.0;

    bool has = false;

    if (link != NULL && link->has_signal) {

        dbm = link->signal_dbm;

        has = true;

    } else {

        char text[4096];

        if (read_text_file("/proc/net/wireless", text, (int) sizeof(text)) > 0) {

            char line[256];

            snprintf(line, sizeof(line), "%s:", iface);

            const char *cursor = text;

            while (*cursor != '\0') {

                const char *start = cursor;

                const char *newline = strchr(cursor, '\n');

                size_t length = (newline != NULL) ? (size_t) (newline - cursor) : strlen(cursor);

                if (strncmp(start, line, strlen(line)) == 0 && length < sizeof(line)) {

                    char row[256];

                    memcpy(row, start, length);

                    row[length] = '\0';

                    // 形如 "wlan0: 0000   51.  -56.   0   0   0   0   0   0"，
                    // 第 2 个数是链路质量，第 3 个是信号电平（dBm）
                    double status = 0.0;
                    double quality = 0.0;
                    double level = 0.0;

                    if (sscanf(row + strlen(line), "%lf %lf %lf", &status, &quality, &level) >= 3) {

                        if (level < 0.0) {
                            dbm = level;
                            has = true;
                        } else if (quality >= 0.0) {
                            *out = quality;
                            return true;
                        }
                    }

                    break;
                }

                if (newline == NULL) {
                    break;
                }

                cursor = newline + 1;
            }
        }
    }

    if (!has) {
        return false;
    }

    double percent = (dbm + 100.0) * 2.0;

    if (percent < 0.0) {
        percent = 0.0;
    }

    if (percent > 100.0) {
        percent = 100.0;
    }

    *out = percent;

    return true;
}


/** IPv6 地址是不是只在本链路有效（链路本地 fe80::/10 与回环 ::1）：这类不算"能用"。 */
static bool net_ipv6_is_local_only(const struct in6_addr *address)
{
    if (address == NULL) {
        return false;
    }

    // 回环 ::1 —— 前 15 个字节全 0，最后一个字节是 1
    bool loopback = true;

    for (int i = 0; i < 15; i++) {

        if (address->s6_addr[i] != 0) {
            loopback = false;
            break;
        }
    }

    if (loopback) {
        return address->s6_addr[15] == 1;
    }

    // 链路本地 fe80::/10：首字节 0xfe，次字节高两位置 10
    return address->s6_addr[0] == 0xfe && (address->s6_addr[1] & 0xc0) == 0x80;
}


/**
 * 接口的 IPv4 / IPv6 各取第一个（getifaddrs 不需要 root，比跑 ip 命令稳）。
 *
 * 顺便回答"这条链路有没有真正在用的地址"，作为事实往上报：
 * IPv4 拿到就算有；IPv6 要看是不是链路本地 —— 手机的 rmnet 接口只要 up 就会分到
 * fe80::，那只能说明"接口起来了"，不代表在传数据。
 * 返回值三态：0 = 没有、1 = 有、-1 = getifaddrs 读失败（读失败不能当成"没有"）。
 */
static int net_read_addresses(const char *iface, char *ipv4, int ipv4_capacity,
                              char *ipv6, int ipv6_capacity)
{
    ipv4[0] = '\0';
    ipv6[0] = '\0';

    struct ifaddrs *list = NULL;

    if (getifaddrs(&list) != 0) {
        return -1;
    }

    int has_data_address = 0;

    for (struct ifaddrs *item = list; item != NULL; item = item->ifa_next) {

        if (item->ifa_addr == NULL || item->ifa_name == NULL) {
            continue;
        }

        if (strcmp(item->ifa_name, iface) != 0) {
            continue;
        }

        if (item->ifa_addr->sa_family == AF_INET) {

            has_data_address = 1;

            if (ipv4[0] == '\0') {

                inet_ntop(AF_INET, &((struct sockaddr_in *) item->ifa_addr)->sin_addr,
                          ipv4, (size_t) ipv4_capacity);
            }

        } else if (item->ifa_addr->sa_family == AF_INET6) {

            struct sockaddr_in6 *address6 = (struct sockaddr_in6 *) item->ifa_addr;

            if (!net_ipv6_is_local_only(&address6->sin6_addr)) {
                has_data_address = 1;
            }

            if (ipv6[0] == '\0') {

                inet_ntop(AF_INET6, &address6->sin6_addr, ipv6, (size_t) ipv6_capacity);
            }
        }
    }

    freeifaddrs(list);

    return has_data_address;
}


/** 按接口名找（或占）一格采样槽。 */
static TmNetSample *net_sample_slot(const char *iface)
{
    static int next = 0;

    for (int i = 0; i < TM_NET_SAMPLE_MAX; i++) {

        if (g_net_prev[i].valid && strcmp(g_net_prev[i].iface, iface) == 0) {
            return &g_net_prev[i];
        }
    }

    TmNetSample *slot = &g_net_prev[next % TM_NET_SAMPLE_MAX];

    next++;

    memset(slot, 0, sizeof(*slot));

    snprintf(slot->iface, sizeof(slot->iface), "%s", iface);

    return slot;
}


/**
 * 收发速率：读统计计数，与上一轮相除得到字节/秒。
 *
 * 与磁盘的 measure_disk_io 同一个口径：首帧没有基准 → has=false（不发 0，发 NO_BASELINE）；
 * 计数器倒退（接口重建）→ 当轮作废并重记基准。
 * 两次请求的间隔就是窗口（UI 更新档位 0.5 / 1 / 4 秒），所以窗口抖动不影响速率本身。
 */
static bool net_measure_rate(const char *iface_path, const char *iface,
                             double *rx_rate, double *tx_rate)
{
    char path[TM_PATH_MAX];

    double rx = 0.0;
    double tx = 0.0;

    snprintf(path, sizeof(path), "%s/statistics/rx_bytes", iface_path);

    bool has_rx = read_first_number(path, &rx);

    snprintf(path, sizeof(path), "%s/statistics/tx_bytes", iface_path);

    bool has_tx = read_first_number(path, &tx);

    if (!has_rx && !has_tx) {
        return false;
    }

    long long now = net_now_ms();

    TmNetSample *slot = net_sample_slot(iface);

    bool had = slot->valid && slot->at_ms > 0 && now > slot->at_ms;

    bool regressed = had && ((has_rx && (unsigned long long) rx < slot->rx_bytes) ||
                             (has_tx && (unsigned long long) tx < slot->tx_bytes));

    if (had && !regressed) {

        double seconds = (double) (now - slot->at_ms) / 1000.0;

        if (seconds > 0.05) {

            if (has_rx) {
                *rx_rate = ((double) (unsigned long long) rx - (double) slot->rx_bytes) / seconds;
            }

            if (has_tx) {
                *tx_rate = ((double) (unsigned long long) tx - (double) slot->tx_bytes) / seconds;
            }

            slot->rx_bytes = (unsigned long long) rx;
            slot->tx_bytes = (unsigned long long) tx;
            slot->at_ms = now;
            slot->valid = true;

            return true;
        }
    }

    // 首帧 / 计数倒退 / 两次采样贴得太近：只重记基准，本轮不给速率
    slot->rx_bytes = (unsigned long long) rx;
    slot->tx_bytes = (unsigned long long) tx;
    slot->at_ms = now;
    slot->valid = true;

    return false;
}

/**
 * 拼一帧里的网卡设备：每块一块，带类型、型号、SSID、地址、信号、收发速率。
 *
 * 字段清单（键名与客户端 buildNetworkSnapshot 一一对应）：
 *   iface          接口名（wlan0 / eth0 / rmnet_data0 …）
 *   role           WIFI / ETHERNET / CELLULAR / OTHER（上层按它决定显不显示）
 *   name           网卡型号；读不到就用接口名（不写占位符 NAME）
 *   ssid           无线 SSID（没连上 / 读不到就不发）
 *   link_type      无线代际（802.11ax / 802.11ac …，同上）
 *   ipv4 / ipv6    地址各取第一个
 *   has_data_address 这条链路有没有"真在用"的地址（0/1；非链路本地才算，手机网络用它在
 *                  上层筛掉一堆只有 fe80:: 的 rmnet 空壳；读失败就不发这一格）
 *   signal_percent 信号强度百分比（没连上不发）
 *   freq_mhz       当前频段（没连上不发）
 *   receive_bytes_per_sec / send_bytes_per_sec  收发速率（首帧 NO_BASELINE）
 */
int build_network_devices(TmOutDevice *out, int capacity,
                                 const TmDeviceRef *devices, int count)
{
    if (out == NULL || devices == NULL || capacity <= 0 || count <= 0) {
        return 0;
    }

    int limit = count;

    if (limit > capacity) {
        limit = capacity;
    }

    for (int i = 0; i < limit; i++) {

        device_begin(&out[i], devices[i].id, TM_DEV_TYPE_NETWORK);

        const char *role = net_role_of(devices[i].path, devices[i].name);

        device_put(&out[i], "role", role);

        device_put(&out[i], "iface", devices[i].name);

        // 型号：/sys/class/net/<if>/device/model 只有挂在真实总线上的网卡才有；
        // 没有就用接口名顶上，页面上至少看得出是哪一块
        char model[96];

        model[0] = '\0';

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "%s/device/model", devices[i].path);

        if (read_text_file(path, model, (int) sizeof(model)) > 0) {

            // 去掉行尾换行与首尾空白
            int length = (int) strlen(model);

            while (length > 0 && (model[length - 1] == '\n' || model[length - 1] == '\r' ||
                                  model[length - 1] == ' ' || model[length - 1] == '\t')) {
                model[--length] = '\0';
            }

            char *head = model;

            while (*head == ' ' || *head == '\t') {
                head++;
            }

            if (head != model) {
                memmove(model, head, strlen(head) + 1);
            }
        }

        device_put(&out[i], "name", (model[0] != '\0') ? model : devices[i].name);

        // 地址
        char ipv4[64];
        char ipv6[96];

        int has_data_address = net_read_addresses(devices[i].name, ipv4, (int) sizeof(ipv4),
                                                  ipv6, (int) sizeof(ipv6));

        if (ipv4[0] != '\0') {
            device_put(&out[i], "ipv4", ipv4);
        }

        if (ipv6[0] != '\0') {
            device_put(&out[i], "ipv6", ipv6);
        }

        // "这条链路是不是真在用"：手机网络有一大串已 up 的 rmnetXX 只挂着 fe80::，
        // 上层拿这一格筛空壳，别让它们占左栏。读失败（-1）就不发，交给上层按地址推断。
        if (has_data_address >= 0) {

            device_put_value(&out[i], "has_data_address",
                             query_value_int(has_data_address ? 1 : 0, TM_QUERY_NOT_AVAILABLE));
        }

        // 无线三项：只有无线接口才有意义（有线 / 移动网络不去跑 iw）
        TmNetLink link;

        memset(&link, 0, sizeof(link));

        double signal = 0.0;

        if (strcmp(role, TM_NET_ROLE_WIFI) == 0) {

            net_read_iw_link(devices[i].name, &link);

            if (link.ssid[0] != '\0') {
                device_put(&out[i], "ssid", link.ssid);
            }

            if (link.link_standard[0] != '\0') {
                device_put(&out[i], "link_type", link.link_standard);
            }

            if (link.freq_mhz[0] != '\0') {
                device_put(&out[i], "freq_mhz", link.freq_mhz);
            }

            if (net_signal_percent(devices[i].name, &link, &signal)) {
                device_put_value(&out[i], "signal_percent",
                                 query_value_float(true, signal, 0, TM_QUERY_NOT_AVAILABLE));
            }
        }

        // 收发速率
        double rx_rate = 0.0;
        double tx_rate = 0.0;

        bool has_rate = net_measure_rate(devices[i].path, devices[i].name, &rx_rate, &tx_rate);

        device_put_int(&out[i], "receive_bytes_per_sec",
                       has_rate ? (long long) rx_rate : -1, TM_FIELD_STATUS_NO_BASELINE);

        device_put_int(&out[i], "send_bytes_per_sec",
                       has_rate ? (long long) tx_rate : -1, TM_FIELD_STATUS_NO_BASELINE);
    }

    return limit;
}
