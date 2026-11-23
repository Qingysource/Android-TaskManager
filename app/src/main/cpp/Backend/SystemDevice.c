/* ============================================================
 * SystemDevice.c — 系统设备：系统级计数与 uptime 等整体状态。
 * ============================================================ */

#include "Header.h"


/** /proc 下的数字目录数 = 进程数（含内核线程）；-1 = 列不出来。 */
static int count_all_processes(void)
{
    DIR *dir = opendir("/proc");

    if (dir == NULL) {
        return -1;
    }

    int count = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        if (is_pure_digits(entry->d_name)) {
            count++;
        }
    }

    closedir(dir);

    return count;
}


/**
 * 全机线程总数：把每个进程的 num_threads 加起来。
 *
 * 不用 /proc/loadavg 的第 4 个数：那个是正在跑的（可运行）任务数，不是线程数。
 *
 * @return -1 = 一个都没读到
 */
static int count_all_threads(void)
{
    DIR *dir = opendir("/proc");

    if (dir == NULL) {
        return -1;
    }

    long long total = 0;
    struct dirent *entry;

    while ((entry = readdir(dir)) != NULL) {

        if (!is_pure_digits(entry->d_name)) {
            continue;
        }

        char path[TM_PATH_MAX];

        snprintf(path, sizeof(path), "/proc/%s/stat", entry->d_name);

        char text[TM_TEXT_MAX];

        if (read_text_file(path, text, (int) sizeof(text)) < 0) {
            continue;
        }

        TmProcStat stat;

        if (!parse_proc_stat(text, &stat)) {
            continue;
        }

        if (stat.num_threads > 0) {
            total += stat.num_threads;
        }
    }

    closedir(dir);

    return (int) total;
}


/** 全机已分配的文件句柄数：/proc/sys/fs/file-nr 的第一列；-1 = 读不到。 */
static int read_open_fd_count(void)
{
    char text[256];

    if (read_text_file("/proc/sys/fs/file-nr", text, (int) sizeof(text)) < 0) {
        return -1;
    }

    char *end = NULL;
    long value = strtol(text, &end, 10);

    if (end == text || value < 0) {
        return -1;
    }

    return (int) value;
}


/** 开机时长（秒）；-1 = 读不到。 */
static long long read_uptime_seconds(void)
{
    char text[128];

    if (read_text_file("/proc/uptime", text, (int) sizeof(text)) < 0) {
        return -1LL;
    }

    char *end = NULL;
    double value = strtod(text, &end);

    if (end == text || value < 0.0) {
        return -1LL;
    }

    return (long long) value;
}

/** 填系统信息：进程 / 线程 / 句柄 / 开机时长（不属于任何一类硬件）。 */
int build_system_device(TmOutDevice *out, int capacity)
{
    if (capacity <= 0) {
        return 0;
    }

    device_begin(&out[0], "system", TM_DEV_TYPE_SYSTEM);

    device_put_int(&out[0], "process_count", count_all_processes(),
                   TM_FIELD_STATUS_READ_FAILED);

    device_put_int(&out[0], "thread_count", count_all_threads(),
                   TM_FIELD_STATUS_READ_FAILED);

    device_put_int(&out[0], "fd_count", read_open_fd_count(),
                   TM_FIELD_STATUS_READ_FAILED);

    device_put_int(&out[0], "uptime_seconds", read_uptime_seconds(),
                   TM_FIELD_STATUS_READ_FAILED);

    return 1;
}
