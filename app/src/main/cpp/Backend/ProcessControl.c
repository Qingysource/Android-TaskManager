/* ============================================================
 * ProcessControl.c — 进程控制类操作实现
 *
 * 一切都以"pid + start_ticks 是同一个进程"为前提：每个出口函数在动手前都读一次
 * /proc/<pid>/stat 核对启动时刻，核不上就当进程已经退出，绝不对"复用了 pid 的别人"动手。
 * ============================================================ */

#define _GNU_SOURCE

#include <fcntl.h>
#include <sched.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>

#include "ProcessControl.h"

/** 一次能收集到的子进程上限（正常手机里一个进程树几十个，4096 足够）。 */
#define PC_CHILD_MAX 4096

/** 结束进程树的递归深度上限：防 PID 关系里出现环，也防"杀掉整机"式的意外。 */
#define PC_TREE_MAX_DEPTH 16

/** 内存转储最多写多少个段（maps 行数通常几十到几百）。 */
#define PC_DUMP_SEGMENT_MAX 1024

static int pc_read_file(const char *path, char *buffer, int capacity);

int pc_present_cpu_count(void)
{
    char text[128];

    // 内核枚举出来的逻辑核列表，形如 "0-7" / "0-3,4-7"：这里取最大编号 + 1，
    // 因为亲和性掩码的位号就是 CPU 编号，界面按 0..max 列出复选框才对得上。
    if (pc_read_file("/sys/devices/system/cpu/present", text, sizeof(text)) > 0) {

        int max_index = -1;
        char *cursor = text;

        while (*cursor != '\0') {

            if (*cursor >= '0' && *cursor <= '9') {

                int first = (int) strtol(cursor, &cursor, 10);

                if (*cursor == '-') {

                    cursor++;

                    int last = (int) strtol(cursor, &cursor, 10);

                    if (last > max_index) {
                        max_index = last;
                    }

                } else if (first > max_index) {

                    max_index = first;
                }

            } else {
                cursor++;
            }
        }

        if (max_index >= 0) {
            return (max_index + 1 > 64) ? 64 : (max_index + 1);
        }
    }

    long configured = sysconf(_SC_NPROCESSORS_CONF);

    if (configured > 0) {
        return (configured > 64) ? 64 : (int) configured;
    }

    return 1;
}

/** 从文件读一段文本，截断到 capacity-1，返回读到的字节数；-1 = 打不开。 */
static int pc_read_file(const char *path, char *buffer, int capacity)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);

    if (fd < 0) {
        return -1;
    }

    int total = 0;

    while (total < capacity - 1) {

        ssize_t got = read(fd, buffer + total, (size_t) (capacity - 1 - total));

        if (got <= 0) {
            break;
        }

        total += (int) got;
    }

    close(fd);

    buffer[total] = '\0';

    return total;
}

/**
 * 解析 /proc/<pid>/stat 的尾部字段（comm 之后）。
 *
 * 为什么不用 parse_proc_stat：那个结构不含 ppid，而"进程树"恰恰只需要 ppid；
 * 这里按空格切 token，取需要的几个位置就够，不必再扩公共结构体。
 */
static bool pc_stat_tail(int pid, int *ppid, long long *start_ticks,
                         long long *threads, char *state_char)
{
    char path[64];
    char text[1024];

    snprintf(path, sizeof(path), "/proc/%d/stat", pid);

    if (pc_read_file(path, text, (int) sizeof(text)) <= 0) {
        return false;
    }

    // comm 可能含空格和 ')'，取最后一个 ')' 之后的字段
    char *tail = strrchr(text, ')');

    if (tail == NULL || tail[1] == '\0') {
        return false;
    }

    // 切 token：0=state 1=ppid 17=num_threads 19=starttime（见 Header 里 stat 字段说明）
    char *save = NULL;
    char *token = strtok_r(tail + 1, " \t\n", &save);

    int index = 0;
    int parsed_ppid = -1;
    long long parsed_threads = -1;
    long long parsed_start = -1;
    char parsed_state = '?';

    while (token != NULL) {

        switch (index) {

            case 0:
                parsed_state = token[0];
                break;

            case 1:
                parsed_ppid = atoi(token);
                break;

            case 17:
                parsed_threads = atoll(token);
                break;

            case 19:
                parsed_start = atoll(token);
                break;

            default:
                break;
        }

        // 需要的都拿到了就停，剩下的字段（尤其是末尾几百字节的无关项）不必再切
        if (index >= 19) {
            break;
        }

        index++;
        token = strtok_r(NULL, " \t\n", &save);
    }

    if (parsed_ppid < 0 || parsed_start < 0) {
        return false;
    }

    if (ppid != NULL) *ppid = parsed_ppid;

    if (start_ticks != NULL) *start_ticks = parsed_start;

    if (threads != NULL) *threads = parsed_threads;

    if (state_char != NULL) *state_char = parsed_state;

    return true;
}

bool pc_read_identity(int pid, long long *start_ticks, int *ppid_out)
{
    if (pid <= 0) {
        return false;
    }

    return pc_stat_tail(pid, ppid_out, start_ticks, NULL, NULL);
}

/** 身份核对：pid 当前这一份进程的启动时刻必须与调用方给的一致。 */
static bool pc_same_process(int pid, long long start_ticks)
{
    long long now_start = -1;

    if (!pc_stat_tail(pid, NULL, &now_start, NULL, NULL)) {
        return false;
    }

    return now_start == start_ticks;
}

/** 收集 ppid 等于 parent 的全部子进程 pid。 */
static int pc_children_of(int parent, int *pids, int capacity)
{
    DIR *proc = opendir("/proc");

    if (proc == NULL) {
        return 0;
    }

    int count = 0;

    struct dirent *entry;

    while ((entry = readdir(proc)) != NULL && count < capacity) {

        if (!isdigit((unsigned char) entry->d_name[0])) {
            continue;
        }

        int pid = atoi(entry->d_name);

        if (pid <= 0) {
            continue;
        }

        int ppid = -1;

        if (!pc_stat_tail(pid, &ppid, NULL, NULL, NULL)) {
            continue;
        }

        if (ppid == parent) {
            pids[count++] = pid;
        }
    }

    closedir(proc);

    return count;
}

/** 自下而上杀一棵子树（不检查目标身份，由调用方保证）。 */
static void pc_kill_children(int pid, int depth, int *killed, int *failed, int *errno_out)
{
    if (depth > PC_TREE_MAX_DEPTH) {
        return;
    }

    int *pids = (int *) malloc(sizeof(int) * PC_CHILD_MAX);

    if (pids == NULL) {

        // 分配不出临时表也算这次"没杀干净"，让上层拿到 ENOMEM 而不是当成已完成
        (*failed)++;

        *errno_out = ENOMEM;

        return;
    }

    int count = pc_children_of(pid, pids, PC_CHILD_MAX);

    // 先把每个子进程的孙子杀干净，再杀子进程本身：否则子进程死后它的孩子会被 init 收养，
    // 这一轮就再也找不到它们了（"结束进程树"会变成"结束一层"）。
    for (int i = 0; i < count; i++) {
        pc_kill_children(pids[i], depth + 1, killed, failed, errno_out);
    }

    for (int i = 0; i < count; i++) {

        if (kill(pids[i], SIGKILL) == 0) {

            (*killed)++;

        } else {

            (*failed)++;
            *errno_out = errno;
        }
    }

    free(pids);
}

bool pc_kill_tree(int pid, long long start_ticks, int *killed, int *failed, int *errno_out)
{
    *killed = 0;
    *failed = 0;
    *errno_out = 0;

    if (pid <= 0 || !pc_same_process(pid, start_ticks)) {

        *errno_out = ESRCH;

        return false;
    }

    pc_kill_children(pid, 0, killed, failed, errno_out);

    if (kill(pid, SIGKILL) != 0) {

        *errno_out = errno;

        return false;
    }

    (*killed)++;

    // 树里只要有一个子进程没杀掉（多半是权限不够 EPERM / EACCES），整次操作就算失败：
    // 目标本身已经没了，但界面必须照实弹「无法完成该操作。<原因>」，
    // 不能因为根进程杀成功就静默当成全部完成。
    if (*failed > 0) {

        if (*errno_out == 0) {
            *errno_out = EPERM;
        }

        return false;
    }

    return true;
}

bool pc_set_priority(int pid, long long start_ticks, int nice_value, int *errno_out)
{
    *errno_out = 0;

    if (pid <= 0 || nice_value < -20 || nice_value > 19) {

        *errno_out = EINVAL;

        return false;
    }

    if (!pc_same_process(pid, start_ticks)) {

        *errno_out = ESRCH;

        return false;
    }

    errno = 0;

    if (setpriority(PRIO_PROCESS, (id_t) pid, nice_value) != 0) {

        *errno_out = errno;

        return false;
    }

    // 内核可能把值夹到允许范围（比如普通身份只能往低处调），回读一次让界面显示真实值
    int applied = getpriority(PRIO_PROCESS, (id_t) pid);

    if (applied != nice_value) {
        *errno_out = 0;
    }

    return true;
}

bool pc_set_affinity(int pid, long long start_ticks, unsigned long long mask, int *errno_out)
{
    *errno_out = 0;

    if (pid <= 0 || mask == 0ULL) {

        *errno_out = EINVAL;

        return false;
    }

    if (!pc_same_process(pid, start_ticks)) {

        *errno_out = ESRCH;

        return false;
    }

    long cpu_count = sysconf(_SC_NPROCESSORS_CONF);

    if (cpu_count <= 0) {
        cpu_count = 64;
    }

    cpu_set_t *set = CPU_ALLOC((int) cpu_count);

    if (set == NULL) {

        *errno_out = ENOMEM;

        return false;
    }

    size_t set_size = CPU_ALLOC_SIZE((int) cpu_count);

    CPU_ZERO_S(set_size, set);

    for (int cpu = 0; cpu < (int) cpu_count && cpu < 64; cpu++) {

        if ((mask >> cpu) & 1ULL) {
            CPU_SET_S(cpu, set_size, set);
        }
    }

    errno = 0;

    bool ok = sched_setaffinity(pid, set_size, set) == 0;

    if (!ok) {
        *errno_out = errno;
    }

    CPU_FREE(set);

    return ok;
}

bool pc_sched_info(int pid, int *nice_value, int *policy, unsigned long long *mask,
                   int *threads, int *errno_out)
{
    *errno_out = 0;

    if (pid <= 0) {

        *errno_out = EINVAL;

        return false;
    }

    long long thread_count = -1;

    if (!pc_stat_tail(pid, NULL, NULL, &thread_count, NULL)) {

        *errno_out = ESRCH;

        return false;
    }

    if (threads != NULL) *threads = (int) thread_count;

    errno = 0;

    int nice_now = getpriority(PRIO_PROCESS, (id_t) pid);

    // getpriority 返回 -1 时无法区分"真的是 -1"和"出错"，靠 errno 判断
    if (nice_now == -1 && errno != 0) {

        *errno_out = errno;

        return false;
    }

    if (nice_value != NULL) *nice_value = nice_now;

    int scheduler = sched_getscheduler(pid);

    if (scheduler >= 0 && policy != NULL) *policy = scheduler;

    long cpu_count = sysconf(_SC_NPROCESSORS_CONF);

    if (cpu_count <= 0) {
        cpu_count = 64;
    }

    cpu_set_t *set = CPU_ALLOC((int) cpu_count);

    if (set != NULL) {

        size_t set_size = CPU_ALLOC_SIZE((int) cpu_count);

        CPU_ZERO_S(set_size, set);

        if (sched_getaffinity(pid, set_size, set) == 0 && mask != NULL) {

            unsigned long long bits = 0ULL;

            for (int cpu = 0; cpu < (int) cpu_count && cpu < 64; cpu++) {

                if (CPU_ISSET_S(cpu, set_size, set)) {
                    bits |= (1ULL << cpu);
                }
            }

            *mask = bits;
        }

        CPU_FREE(set);
    }

    return true;
}

int pc_wait_chain(int pid, char lines[PC_WAIT_THREAD_MAX][PC_WAIT_LINE_MAX])
{
    if (pid <= 0) {
        return 0;
    }

    char dir_path[64];

    snprintf(dir_path, sizeof(dir_path), "/proc/%d/task", pid);

    DIR *tasks = opendir(dir_path);

    if (tasks == NULL) {
        return 0;
    }

    int count = 0;

    struct dirent *entry;

    while ((entry = readdir(tasks)) != NULL && count < PC_WAIT_THREAD_MAX) {

        if (!isdigit((unsigned char) entry->d_name[0])) {
            continue;
        }

        int tid = atoi(entry->d_name);

        if (tid <= 0) {
            continue;
        }

        char path[96];
        char buffer[256];

        char state = '?';
        char wchan[64] = "-";
        char syscall_text[40] = "-";
        char comm[32] = "";

        // 状态：/proc/<pid>/task/<tid>/stat 的 comm 之后第一个字段
        snprintf(path, sizeof(path), "%s/%d/stat", dir_path, tid);

        if (pc_read_file(path, buffer, (int) sizeof(buffer)) > 0) {

            char *tail = strrchr(buffer, ')');

            if (tail != NULL && tail[1] != '\0') {
                sscanf(tail + 1, " %c", &state);
            }
        }

        // 内核里正在等什么（需要 root，普通身份读不到就保持 "-"）
        snprintf(path, sizeof(path), "%s/%d/wchan", dir_path, tid);

        if (pc_read_file(path, buffer, (int) sizeof(buffer)) > 0) {

            buffer[strcspn(buffer, "\n")] = '\0';

            if (buffer[0] != '\0') {
                snprintf(wchan, sizeof(wchan), "%s", buffer);
            }
        }

        // 正在跑哪个系统调用（被阻塞在哪个 syscall 上）
        snprintf(path, sizeof(path), "%s/%d/syscall", dir_path, tid);

        if (pc_read_file(path, buffer, (int) sizeof(buffer)) > 0) {

            buffer[strcspn(buffer, "\n")] = '\0';

            if (buffer[0] != '\0') {
                snprintf(syscall_text, sizeof(syscall_text), "%s", buffer);
            }
        }

        snprintf(path, sizeof(path), "%s/%d/comm", dir_path, tid);

        if (pc_read_file(path, buffer, (int) sizeof(buffer)) > 0) {

            buffer[strcspn(buffer, "\n")] = '\0';

            snprintf(comm, sizeof(comm), "%s", buffer);
        }

        snprintf(lines[count], PC_WAIT_LINE_MAX, "%d\t%c\t%s\t%s\t%s",
                 tid, state, wchan, syscall_text, comm);

        count++;
    }

    closedir(tasks);

    return count;
}

// ============================================================
// 内存转储（精简 ELF core）
// ============================================================

/** ELF64 头（只写我们需要的字段，其余填 0）。 */
typedef struct {
    unsigned char ident[16];
    uint16_t type;
    uint16_t machine;
    uint32_t version;
    uint64_t entry;
    uint64_t phoff;
    uint64_t shoff;
    uint32_t flags;
    uint16_t ehsize;
    uint16_t phentsize;
    uint16_t phnum;
    uint16_t shentsize;
    uint16_t shnum;
    uint16_t shstrndx;
} PcElfHeader;

/** ELF64 程序头。 */
typedef struct {
    uint32_t type;
    uint32_t flags;
    uint64_t offset;
    uint64_t vaddr;
    uint64_t paddr;
    uint64_t filesz;
    uint64_t memsz;
    uint64_t align;
} PcPhdr;

/** 一个待转储的映射。 */
typedef struct {
    unsigned long long start;
    unsigned long long end;
    int flags;              // bit0=R bit1=W bit2=X
} PcSegment;

/** maps 权限串转 ELF p_flags。 */
static int pc_segment_flags(const char *perms)
{
    int flags = 0;

    if (perms[0] == 'r') flags |= 4;   // PF_R
    if (perms[1] == 'w') flags |= 2;   // PF_W
    if (perms[2] == 'x') flags |= 1;   // PF_X

    return flags;
}

/** 写满一段（write 可能短写）。 */
static bool pc_write_all(int fd, const void *data, size_t size)
{
    const unsigned char *cursor = (const unsigned char *) data;

    while (size > 0) {

        ssize_t wrote = write(fd, cursor, size);

        if (wrote <= 0) {
            return false;
        }

        cursor += wrote;
        size -= (size_t) wrote;
    }

    return true;
}

/**
 * 逐级创建文件所在目录（等价于 `mkdir -p`）。
 *
 * 转储目标是 /storage/emulated/<用户>/dump，这个目录在全新机器上并不存在；
 * 应用自身的存储权限可能是被拒的，但后端是以 shell / root 身份跑的，建目录这件事
 * 直接在这里做，界面就不必先去申请存储权限。已存在的层级一律放过。
 */
static void pc_ensure_parent_dirs(const char *path)
{
    if (path == NULL) {
        return;
    }

    char buffer[512];

    size_t length = strlen(path);

    if (length == 0 || length >= sizeof(buffer)) {
        return;
    }

    memcpy(buffer, path, length + 1);

    // 从第一层开始逐段建；遇到 file 自己的最后一段就停
    for (char *cursor = buffer + 1; *cursor != '\0'; cursor++) {

        if (*cursor != '/') {
            continue;
        }

        *cursor = '\0';

        mkdir(buffer, 0775);

        *cursor = '/';
    }
}

bool pc_mem_dump(int pid, long long start_ticks, const char *path,
                 long long *bytes, int *segments, int *errno_out)
{
    *bytes = 0;
    *segments = 0;
    *errno_out = 0;

    if (pid <= 0 || path == NULL || path[0] == '\0') {

        *errno_out = EINVAL;

        return false;
    }

    pc_ensure_parent_dirs(path);

    if (!pc_same_process(pid, start_ticks)) {

        *errno_out = ESRCH;

        return false;
    }

    char maps_path[64];

    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);

    // 内存只能从 /proc/<pid>/mem 读，需要 ptrace 级权限（root 下通常可行）
    char mem_path[64];

    snprintf(mem_path, sizeof(mem_path), "/proc/%d/mem", pid);

    int mem_fd = open(mem_path, O_RDONLY | O_CLOEXEC);

    if (mem_fd < 0) {

        *errno_out = errno;

        return false;
    }

    FILE *maps = fopen(maps_path, "r");

    if (maps == NULL) {

        *errno_out = errno;

        close(mem_fd);

        return false;
    }

    PcSegment *list = (PcSegment *) malloc(sizeof(PcSegment) * PC_DUMP_SEGMENT_MAX);

    if (list == NULL) {

        fclose(maps);
        close(mem_fd);

        *errno_out = ENOMEM;

        return false;
    }

    int count = 0;
    long long total = 0;

    char line[512];

    while (fgets(line, (int) sizeof(line), maps) != NULL && count < PC_DUMP_SEGMENT_MAX) {

        unsigned long long start = 0;
        unsigned long long end = 0;
        char perms[8] = {0};

        if (sscanf(line, "%llx-%llx %7s", &start, &end, perms) != 3) {
            continue;
        }

        if (perms[0] != 'r' || end <= start) {
            continue;
        }

        // 超大段（如 4 GB 的保留映射）跳过，别让一次转储把手机存储写满
        long long size = (long long) (end - start);

        if (size <= 0 || size > PC_DUMP_MAX_BYTES) {
            continue;
        }

        if (total + size > PC_DUMP_MAX_BYTES) {
            continue;
        }

        list[count].start = start;
        list[count].end = end;
        list[count].flags = pc_segment_flags(perms);

        total += size;

        count++;
    }

    fclose(maps);

    if (count == 0) {

        free(list);
        close(mem_fd);

        *errno_out = EACCES;

        return false;
    }

    int out_fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);

    if (out_fd < 0) {

        *errno_out = errno;

        free(list);
        close(mem_fd);

        return false;
    }

    // 布局：ELF 头 + N 个程序头 + 各段数据（按 8 字节对齐）
    size_t header_size = sizeof(PcElfHeader) + sizeof(PcPhdr) * (size_t) count;

    PcElfHeader elf;
    memset(&elf, 0, sizeof(elf));

    elf.ident[0] = 0x7f;
    elf.ident[1] = 'E';
    elf.ident[2] = 'L';
    elf.ident[3] = 'F';
    elf.ident[4] = 2;       // ELFCLASS64
    elf.ident[5] = 1;       // ELFDATA2LSB
    elf.ident[6] = 1;       // EV_CURRENT
    elf.type = 4;           // ET_CORE
#if defined(__aarch64__)
    elf.machine = 183;      // EM_AARCH64
#elif defined(__x86_64__)
    elf.machine = 62;       // EM_X86_64
#else
    elf.machine = 0;        // 未知（结构仍可读，只是分析工具会提示不识别）
#endif
    elf.version = 1;
    elf.phoff = sizeof(PcElfHeader);
    elf.ehsize = sizeof(PcElfHeader);
    elf.phentsize = sizeof(PcPhdr);
    elf.phnum = (uint16_t) count;

    if (!pc_write_all(out_fd, &elf, sizeof(elf))) {

        *errno_out = EIO;

        close(out_fd);
        free(list);
        close(mem_fd);

        return false;
    }

    long long offset = (long long) header_size;
    long long written = 0;

    for (int i = 0; i < count; i++) {

        PcPhdr phdr;
        memset(&phdr, 0, sizeof(phdr));

        long long size = (long long) (list[i].end - list[i].start);

        phdr.type = 1;                        // PT_LOAD
        phdr.flags = (uint32_t) list[i].flags;
        phdr.offset = (uint64_t) offset;
        phdr.vaddr = (uint64_t) list[i].start;
        phdr.filesz = (uint64_t) size;
        phdr.memsz = (uint64_t) size;
        phdr.align = 1;

        if (!pc_write_all(out_fd, &phdr, sizeof(phdr))) {

            *errno_out = EIO;

            close(out_fd);
            free(list);
            close(mem_fd);

            return false;
        }

        offset += size;
    }

    // 段数据：读不出来（EIO）的页整段置 0，保证 ELF 结构完整可解析
    unsigned char *buffer = (unsigned char *) malloc(64 * 1024);

    if (buffer == NULL) {

        *errno_out = ENOMEM;

        close(out_fd);
        free(list);
        close(mem_fd);

        return false;
    }

    for (int i = 0; i < count; i++) {

        unsigned long long cursor = list[i].start;

        while (cursor < list[i].end) {

            size_t want = (size_t) (list[i].end - cursor);

            if (want > 64 * 1024) {
                want = 64 * 1024;
            }

            ssize_t got = pread(mem_fd, buffer, want, (off_t) cursor);

            if (got < 0) {
                memset(buffer, 0, want);
                got = (ssize_t) want;
            } else if (got == 0) {
                memset(buffer, 0, want);
                got = (ssize_t) want;
            } else if ((size_t) got < want) {
                memset(buffer + got, 0, want - (size_t) got);
            }

            if (!pc_write_all(out_fd, buffer, want)) {

                *errno_out = EIO;

                free(buffer);
                close(out_fd);
                free(list);
                close(mem_fd);

                return false;
            }

            cursor += want;
            written += want;
        }
    }

    free(buffer);
    free(list);
    close(mem_fd);

    if (close(out_fd) != 0) {

        *errno_out = errno;

        return false;
    }

    *bytes = written;
    *segments = count;

    return true;
}
