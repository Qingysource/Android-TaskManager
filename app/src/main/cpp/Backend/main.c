/* ============================================================
 * main.c — 服务实例入口：进程启动、消息循环、命令分发与协议通信。
 * ============================================================ */

#include "Header.h"

/**
 * 从 stdin 读一行（不含换行符）。
 *
 * 超长行的处理：把剩余字节丢到换行为止，返回 true 但内容已被截断。
 * 这样下一轮不会把"半行"当成一条新请求（F-02）。
 *
 * @param buffer   接收缓冲
 * @param capacity 缓冲长度
 * @return true = 读到一行（可能是空行）；false = EOF / 读失败
 */
static bool read_command_line(char *buffer, int capacity)
{
    if (fgets(buffer, capacity, stdin) == NULL) {
        return false;
    }

    size_t length = strlen(buffer);

    if (length > 0 && buffer[length - 1] == '\n') {
        buffer[length - 1] = '\0';

        return true;
    }

    // 没有换行且已到 EOF：最后一行本来就不带换行，正常返回
    if (feof(stdin)) {
        return true;
    }

    // 行超长：把剩下的内容丢到换行为止
    int ch;

    while ((ch = fgetc(stdin)) != EOF && ch != '\n') {
        /* 丢弃 */
    }

    return true;
}

/** 处理 typeCode=2：回 proto / fields / fieldMask / encoding 等自检信息。 */
static bool handle_query_info(unsigned long long seq, int type)
{
    reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);

    printf("I proto=%d\n", TM_PROTO_VERSION);
    printf("I fields=%d\n", k_field_count);
    printf("I fieldMask=0x%llx\n", TM_FIELD_ALL);
    printf("I encoding=percent\n");
    printf("I statusCodes=0,1,2,3,4,5\n");

    // 设备快照的能力自报：客户端据此判断"这台后端支不支持性能页的 typeCode=5"，
    // 免得对着老版本后端一直发请求、一直收 UNSUPPORTED。
    printf("I deviceSnapshot=%d\n", TM_REQ_DEVICE_SNAPSHOT);

    // 扩展快照的能力自报同上：老客户端不认识这个键就忽略，
    // 新客户端据此决定"要不要让 CPU 扩展信息页去发 typeCode=6"。
    printf("I deviceSnapshotExt=%d\n", TM_REQ_DEVICE_EXTENDED_SNAPSHOT);
    print_info_line("backend", "TaskManager Native Backend");
    printf("I pid=%d\n", (int) getpid());
    printf("I uid=%d\n", (int) getuid());
    printf("I euid=%d\n", (int) geteuid());

    return true;
}

/**
 * 把两个文本参数解析成 (pid, start_ticks)。
 *
 * -1 = 没给启动时刻（客户端拿不到就是拿不到，不能拿 0 顶替；0 是合法值，如 init）。
 * 解析失败时 pid 置 0，让调用方按 BAD_ARGUMENT 处理。
 */
static bool parse_pid_and_start(const char *pid_text, const char *ticks_text,
                                int *pid_out, long long *start_out)
{
    *pid_out = 0;
    *start_out = -1;

    if (pid_text != NULL && pid_text[0] != '\0') {

        char *pid_end = NULL;
        int pid = (int) strtol(pid_text, &pid_end, 10);

        // 整段必须都是数字：不然 "12abc" 会被 strtol 解成 12
        if (pid_end == pid_text || *pid_end != '\0') {
            return false;
        }

        *pid_out = pid;
    }

    if (ticks_text != NULL && ticks_text[0] != '\0') {

        char *ticks_end = NULL;
        long long parsed = (long long) strtoll(ticks_text, &ticks_end, 10);

        if (ticks_end != ticks_text && *ticks_end == '\0' && parsed >= 0) {
            *start_out = parsed;
        }
    }

    return *pid_out > 0 && *start_out >= 0;
}

/**
 * 处理 typeCode=3：`REQ <seq> 3 0x0 <code> [arg] [arg2] [arg3]`，控制码见头部注释第三节。
 *
 * 回执与执行分开表达：ACCEPTED = 已受理但这一版没真做（KILL 只回执）；
 * OK = 已做完；BAD_ARGUMENT / PROCESS_EXITED = 没做成及原因。
 */
static bool handle_control(TmBackend *backend, unsigned long long seq, int type,
                           const char *code_text, const char *argument,
                           const char *argument2, const char *argument3)
{
    if (code_text == NULL || code_text[0] == '\0') {

        reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
        print_info_line("error", "missing control code");

        return true;
    }

    char *end = NULL;
    long parsed_code = strtol(code_text, &end, 10);

    if (end == code_text) {

        reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
        print_info_line("error", "control code is not a number");

        return true;
    }

    int code = (int) parsed_code;

    switch (code) {

        case TM_CTL_KILL_PROCESS: {

            int pid = 0;

            // -1 = 客户端没给启动时刻（拿不到就是拿不到，不能拿 0 顶替）
            long long start_ticks = -1;

            if (argument != NULL && argument[0] != '\0') {
                char *pid_end = NULL;
                pid = (int) strtol(argument, &pid_end, 10);

                // 整段必须都是数字：不然 "12abc" 会被 strtol 解成 12
                if (pid_end == argument || *pid_end != '\0') {
                    pid = 0;
                }
            }

            if (argument2 != NULL && argument2[0] != '\0') {
                char *ticks_end = NULL;
                long long parsed = (long long) strtoll(argument2, &ticks_end, 10);

                // 0 是**合法**启动时刻（开机第 0 个 tick 起的进程，例如 init 的 start_time 就是 0），
                // 所以不能把 0 当"没给"。真正的"没给"是 -1。
                if (ticks_end != argument2 && *ticks_end == '\0' && parsed >= 0) {
                    start_ticks = parsed;
                }
            }

            if (pid <= 0) {
                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "kill needs a positive pid");

                return true;
            }

            // 必须同时给 pid 与 start_ticks：只凭 pid，目标进程退出后 PID 被复用，
            // 就会杀掉另一个进程。缺启动时刻一律拒绝，不做"猜一个"。
            if (start_ticks < 0) {
                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "kill needs pid and start_ticks (start_ticks >= 0)");

                return true;
            }

            // 身份校验：目标必须出现在最近一次快照里，且启动时刻一致。
            // 只比 pid 会在 PID 复用后"杀到另一个人"，所以两者缺一不可。
            // 校验无条件按全量重采：当前快照可能只含某个范围，不能拿"筛过的表"当唯一依据。
            if (g_snapshot.count == 0) {
                collect_snapshot(TM_SCOPE_ALL);
            }

            TmProcStat *target = table_find(&g_snapshot, pid, start_ticks);

            if (target == NULL) {
                reply_package_header(seq, type, 0ULL, TM_STATUS_PROCESS_EXITED, 0);
                print_info_line("error", "no such process in current snapshot (pid or start_ticks mismatch)");

                return true;
            }

            // 身份已由 pid + start_ticks 双重校验（都在最近一次快照里），现在真发信号。
            // 用 SIGKILL：界面上这个动作就叫"结束进程"，语义是立刻终止，不做"请它自己退"的礼貌沟通。
            if (kill(pid, SIGKILL) != 0) {

                // 失败常见原因：不是 root（EPERM）、进程刚好退出（ESRCH）。
                // 原样回 errno，让客户端能说清"为什么没成"，而不是一律"失败"。
                int saved_errno = errno;

                reply_package_header(seq, type, 0ULL, TM_STATUS_INTERNAL, 6);
                printf("I code=%d\n", code);
                printf("I action=kill\n");
                printf("I pid=%d\n", pid);
                printf("I executed=0\n");
                printf("I errno=%d\n", saved_errno);

                {
                    char note_text[96];

                    snprintf(note_text, sizeof(note_text), "kill failed, errno %d", saved_errno);
                    print_info_line("error", note_text);
                }

                return true;
            }

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 5);
            printf("I code=%d\n", code);
            printf("I action=kill\n");
            printf("I pid=%d\n", pid);
            printf("I executed=1\n");
            print_info_line("signal", "SIGKILL sent");

            return true;
        }

        case TM_CTL_REINIT:

            // REINIT：清掉采样基准，下一轮快照重新建立
            baseline_clear();

            backend->baseline_ready = false;

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
            printf("I code=%d\n", code);
            printf("I action=reinit\n");
            print_info_line("baseline", "cleared (next snapshot re-baselines)");

            return true;

        case TM_CTL_DUMP_STATE:

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
            printf("I code=%d\n", code);
            printf("I state=%s\n", backend->running ? "running" : "stopping");
            printf("I commands=%lu\n", backend->command_count);
            printf("I baseline=%s\n", backend->baseline_ready ? "ready" : "none");
            printf("I processes=%d\n", g_snapshot.count);
            printf("I baselineProcesses=%d\n", g_baseline.count);
            printf("I cpuTotalPrev=%lld\n", g_cpu_total_prev);
            printf("I pageSize=%ld\n", g_page_size);
            printf("I clockTicks=%ld\n", g_clock_ticks);
            printf("I proto=%d\n", TM_PROTO_VERSION);

            return true;

        case TM_CTL_SHUTDOWN:

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
            printf("I code=%d\n", code);
            printf("I action=shutdown\n");

            backend->running = false;

            return false;

        case TM_CTL_KILL_TREE: {

            int pid = 0;
            long long start_ticks = -1;

            if (!parse_pid_and_start(argument, argument2, &pid, &start_ticks)) {

                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "kill tree needs pid and start_ticks");

                return true;
            }

            int killed = 0;
            int failed = 0;
            int saved_errno = 0;

            bool ok = pc_kill_tree(pid, start_ticks, &killed, &failed, &saved_errno);

            if (!ok) {

                // ESRCH = 目标已经不在了（身份对不上也算这一类，语义是"不是那个进程了"）
                int status = (saved_errno == ESRCH) ? TM_STATUS_PROCESS_EXITED : TM_STATUS_INTERNAL;

                reply_package_header(seq, type, 0ULL, status, 8);
                printf("I code=%d\n", code);
                printf("I action=kill_tree\n");
                printf("I pid=%d\n", pid);
                printf("I killed=%d\n", killed);
                printf("I failed=%d\n", failed);
                printf("I executed=0\n");
                printf("I errno=%d\n", saved_errno);

                {
                    char note_text[96];

                    snprintf(note_text, sizeof(note_text), "kill tree failed, errno %d", saved_errno);
                    print_info_line("error", note_text);
                }

                return true;
            }

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 7);
            printf("I code=%d\n", code);
            printf("I action=kill_tree\n");
            printf("I pid=%d\n", pid);
            printf("I killed=%d\n", killed);
            printf("I failed=%d\n", failed);
            printf("I executed=1\n");
            printf("I errno=%d\n", saved_errno);

            return true;
        }

        case TM_CTL_SET_PRIORITY: {

            int pid = 0;
            long long start_ticks = -1;

            if (!parse_pid_and_start(argument, argument2, &pid, &start_ticks)) {

                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "set priority needs pid and start_ticks");

                return true;
            }

            int nice_value = (argument3 != NULL) ? atoi(argument3) : 0;
            int saved_errno = 0;

            bool ok = pc_set_priority(pid, start_ticks, nice_value, &saved_errno);

            // 设置后无论成败都把"当前真实 nice"回给界面：被内核夹过也能显示实际值
            int applied = -1;
            int policy = -1;
            unsigned long long mask = 0ULL;
            int threads = 0;

            // 单独一格 errno：读调度状态失败时不能把 setpriority 的错误码盖掉，
            // 否则界面拿到的是"读不到 nice"的原因，而不是"设置失败"的原因。
            int sched_errno = 0;

            pc_sched_info(pid, &applied, &policy, &mask, &threads, &sched_errno);

            if (!ok) {

                int status = (saved_errno == ESRCH) ? TM_STATUS_PROCESS_EXITED : TM_STATUS_INTERNAL;

                reply_package_header(seq, type, 0ULL, status, 7);
                printf("I code=%d\n", code);
                printf("I action=set_priority\n");
                printf("I pid=%d\n", pid);
                printf("I nice=%d\n", nice_value);
                printf("I applied=%d\n", applied);
                printf("I executed=0\n");
                printf("I errno=%d\n", saved_errno);

                return true;
            }

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 5);
            printf("I code=%d\n", code);
            printf("I action=set_priority\n");
            printf("I pid=%d\n", pid);
            printf("I nice=%d\n", applied);
            printf("I executed=1\n");

            return true;
        }

        case TM_CTL_SET_AFFINITY: {

            int pid = 0;
            long long start_ticks = -1;

            if (!parse_pid_and_start(argument, argument2, &pid, &start_ticks)) {

                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "set affinity needs pid and start_ticks");

                return true;
            }

            // 第三段是十六进制掩码（可以带 0x 前缀）：第 n 位 = 允许在 CPU n 上跑
            unsigned long long requested = 0ULL;

            if (argument3 != NULL && argument3[0] != '\0') {

                const char *mask_text = argument3;

                if (mask_text[0] == '0' && (mask_text[1] == 'x' || mask_text[1] == 'X')) {
                    mask_text += 2;
                }

                requested = strtoull(mask_text, NULL, 16);
            }

            int saved_errno = 0;

            bool ok = pc_set_affinity(pid, start_ticks, requested, &saved_errno);

            if (!ok) {

                int status = (saved_errno == ESRCH) ? TM_STATUS_PROCESS_EXITED : TM_STATUS_INTERNAL;

                reply_package_header(seq, type, 0ULL, status, 6);
                printf("I code=%d\n", code);
                printf("I action=set_affinity\n");
                printf("I pid=%d\n", pid);
                printf("I mask=0x%llx\n", requested);
                printf("I executed=0\n");
                printf("I errno=%d\n", saved_errno);

                return true;
            }

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 5);
            printf("I code=%d\n", code);
            printf("I action=set_affinity\n");
            printf("I pid=%d\n", pid);
            printf("I mask=0x%llx\n", requested);
            printf("I executed=1\n");

            return true;
        }

        case TM_CTL_SCHED_INFO: {

            int pid = atoi(argument != NULL ? argument : "0");

            int nice_value = 0;
            int policy = -1;
            unsigned long long mask = 0ULL;
            int threads = 0;
            int saved_errno = 0;

            if (pid <= 0) {

                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "sched info needs a positive pid");

                return true;
            }

            if (!pc_sched_info(pid, &nice_value, &policy, &mask, &threads, &saved_errno)) {

                reply_package_header(seq, type, 0ULL, TM_STATUS_PROCESS_EXITED, 1);
                printf("I errno=%d\n", saved_errno);

                return true;
            }

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 6);
            printf("I pid=%d\n", pid);
            printf("I nice=%d\n", nice_value);
            printf("I policy=%d\n", policy);
            printf("I affinity=0x%llx\n", mask);
            printf("I threads=%d\n", threads);
            printf("I cpus=%d\n", pc_present_cpu_count());

            return true;
        }

        case TM_CTL_WAIT_CHAIN: {

            int pid = atoi(argument != NULL ? argument : "0");

            if (pid <= 0) {

                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "wait chain needs a positive pid");

                return true;
            }

            char (*lines)[PC_WAIT_LINE_MAX] =
                (char (*)[PC_WAIT_LINE_MAX]) malloc(sizeof(char[PC_WAIT_LINE_MAX]) * PC_WAIT_THREAD_MAX);

            if (lines == NULL) {

                reply_package_header(seq, type, 0ULL, TM_STATUS_INTERNAL, 0);
                print_info_line("error", "out of memory");

                return true;
            }

            int count = pc_wait_chain(pid, lines);

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 3 + count);

            printf("I pid=%d\n", pid);
            printf("I threads=%d\n", count);
            printf("I limit=%d\n", PC_WAIT_THREAD_MAX);

            // 每条线程一行：t<序号>=<tid>\t<状态>\t<wchan>\t<syscall>\t<线程名>
            for (int i = 0; i < count; i++) {
                printf("I t%d=%s\n", i, lines[i]);
            }

            free(lines);

            return true;
        }

        case TM_CTL_MEM_DUMP: {

            int pid = 0;
            long long start_ticks = -1;

            if (!parse_pid_and_start(argument, argument2, &pid, &start_ticks) ||
                argument3 == NULL || argument3[0] == '\0') {

                reply_package_header(seq, type, 0ULL, TM_STATUS_BAD_ARGUMENT, 0);
                print_info_line("error", "mem dump needs pid, start_ticks and output path");

                return true;
            }

            long long bytes = 0;
            int segments = 0;
            int saved_errno = 0;

            bool ok = pc_mem_dump(pid, start_ticks, argument3, &bytes, &segments, &saved_errno);

            if (!ok) {

                int status = (saved_errno == ESRCH) ? TM_STATUS_PROCESS_EXITED : TM_STATUS_INTERNAL;

                reply_package_header(seq, type, 0ULL, status, 7);
                printf("I code=%d\n", code);
                printf("I action=mem_dump\n");
                printf("I pid=%d\n", pid);
                printf("I bytes=%lld\n", bytes);
                printf("I segments=%d\n", segments);
                printf("I executed=0\n");
                printf("I errno=%d\n", saved_errno);

                return true;
            }

            reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 7);
            printf("I code=%d\n", code);
            printf("I action=mem_dump\n");
            printf("I pid=%d\n", pid);
            printf("I bytes=%lld\n", bytes);
            printf("I segments=%d\n", segments);
            printf("I executed=1\n");
            print_info_line("path", argument3);

            return true;
        }

        default:

            reply_package_header(seq, type, 0ULL, TM_STATUS_UNSUPPORTED, 0);

            {
                char note_text[48];

                snprintf(note_text, sizeof(note_text), "unknown control code %d", code);
                print_info_line("error", note_text);
            }

            return true;
    }
}

/** 处理心跳：客户端用来确认"进程还活着、管道没断"。 */
static bool handle_ping(unsigned long long seq, int type)
{
    reply_package_header(seq, type, 0ULL, TM_STATUS_OK, 0);
    printf("I pong=1\n");

    return true;
}

/**
 * 解析并处理一条 REQ（格式见头部注释第二节）。
 *
 * 缺字段 / seq 不是正整数 / type 或 mask 不是数字 → 整行丢弃，不回包；
 * 能解析但取值不合法 → 回 BAD_ARGUMENT 或 UNSUPPORTED。
 */
static bool handle_request(TmBackend *backend, const char *line)
{
    char copy[TM_LINE_MAX];

    snprintf(copy, sizeof(copy), "%s", line);

    char *save = NULL;
    char *prefix = strtok_r(copy, " \t", &save);

    if (prefix == NULL || strcmp(prefix, "REQ") != 0) {
        return true;
    }

    char *seq_text = strtok_r(NULL, " \t", &save);
    char *type_text = strtok_r(NULL, " \t", &save);
    char *mask_text = strtok_r(NULL, " \t", &save);

    if (seq_text == NULL || type_text == NULL || mask_text == NULL) {
        return true;
    }

    char *end = NULL;

    unsigned long long seq = strtoull(seq_text, &end, 10);

    if (end == seq_text || seq == 0ULL) {
        return true;
    }

    long type = strtol(type_text, &end, 10);

    if (end == type_text) {
        return true;
    }

    unsigned long long mask = strtoull(mask_text, &end, 16);

    if (end == mask_text) {
        return true;
    }

    // 设备快照：第四段之后全是上下文参数（键=值，可多个），收齐后交给采集侧
    if ((int) type == TM_REQ_DEVICE_SNAPSHOT) {

        const char *arguments[TM_DEVICE_CONTEXT_ARGUMENT_MAX];
        int argument_count = 0;

        char *token = strtok_r(NULL, " \t", &save);

        while (token != NULL && argument_count < TM_DEVICE_CONTEXT_ARGUMENT_MAX) {

            arguments[argument_count++] = token;

            token = strtok_r(NULL, " \t", &save);
        }

        return handle_device_snapshot(seq, (int) type, arguments, argument_count);
    }

    // 设备扩展快照：不带附加参数，也不碰上面那段上下文解析 ——
    // 它自己按集群 / 逐核采集，与设备快照（5）的采样基线互不干扰。
    if ((int) type == TM_REQ_DEVICE_EXTENDED_SNAPSHOT) {
        return handle_extended_snapshot(seq, (int) type);
    }

    // 第四个字段之后：控制类的 code，再往后是参数（参数都不含空格）。
    // KILL 用 `code pid start_ticks`；优先级 / 亲和性 / 转储再用到第三个参数。
    char *code_text = strtok_r(NULL, " \t", &save);

    char *argument = (code_text == NULL) ? NULL : strtok_r(NULL, " \t", &save);

    char *argument2 = (argument == NULL) ? NULL : strtok_r(NULL, " \t", &save);

    char *argument3 = (argument2 == NULL) ? NULL : strtok_r(NULL, " \t", &save);

    switch ((int) type) {

        case TM_REQ_QUERY_PROCESS:
            // 查询进程的第 4 个附加字段是范围（0 全部 / 1 用户 / 2 系统），缺省 = 全部
            return handle_query_process(backend, seq, (int) type, mask, code_text);

        case TM_REQ_QUERY_INFO:
            return handle_query_info(seq, (int) type);

        case TM_REQ_CONTROL:
            return handle_control(backend, seq, (int) type, code_text, argument, argument2, argument3);

        case TM_REQ_PING:
            return handle_ping(seq, (int) type);

        default:
            reply_package_header(seq, (int) type, mask, TM_STATUS_UNSUPPORTED, 0);

            {
                char note_text[48];

                snprintf(note_text, sizeof(note_text), "unsupported request type %d", (int) type);
                print_info_line("error", note_text);
            }

            return true;
    }
}

/**
 * 分发一条命令（已裁掉首尾空白）。
 *
 * 通道上只认 `REQ ...`。其它内容一律静默忽略——不往 stdout 回任何东西，
 * 否则那行会变成"不属于任何包的散行"，把客户端日志搅浑。
 *
 * @param backend 后端状态
 * @param command 命令正文，不含换行
 * @return true = 继续循环；false = 收到退出指令
 */
static bool dispatch_command(TmBackend *backend, const char *command)
{
    if (command == NULL || command[0] == '\0') {
        return true;
    }

    backend->command_count++;

    if (strncmp(command, "REQ ", 4) == 0) {
        return handle_request(backend, command);
    }

    // 非协议行：静默忽略，不回包
    return true;
}

/**
 * 常驻循环：读一行 -> 分发 -> 立刻 flush。
 *
 * 为什么每轮都 flush：管道模式下 stdout 默认带缓冲，
 * 不主动 flush 的话 Android 侧会迟迟读不到字节，看起来像"没输出"。
 *
 * flush 失败（EPIPE：对端已关闭）说明没人在收了，直接退出循环，
 * 不要继续空转（F-09 / F-11）。
 *
 * @param backend 后端状态；running 被置 false 或 stdin 关闭时返回
 */
static void run_message_loop(TmBackend *backend)
{
    char line[TM_LINE_MAX];

    while (backend->running && read_command_line(line, (int) sizeof(line))) {

        dispatch_command(backend, trim_text(line));

        if (fflush(stdout) != 0) {
            // 管道已断：再写也没有意义
            backend->running = false;
        }
    }
}

/**
 * 进程入口：打版本行与 READY，然后进入常驻循环。
 *
 * @return 0 = 正常退出（收到 SHUTDOWN 或 stdin 被关闭）
 */
int main(int argc, char *argv[])
{
    // 本进程不接受启动参数：设备模型的 Android 层事实统一由设备快照请求带入
    // （typeCode=5 的附加参数，见 apply_device_context），这样上层数据变了也不用重启进程。
    (void) argc;
    (void) argv;

    // 父进程先关掉管道、本进程还在往 stdout 写，默认会收到 SIGPIPE 直接死掉；
    // 忽略它，这样写失败只让 printf / fflush 返回错误，循环还能按 EOF 正常收尾。
    signal(SIGPIPE, SIG_IGN);

    // 磁盘四项指标的常驻采样线程：250 ms 一拍、最近 1 秒窗口结算，
    // 快照请求只查表。放在 READY 之前起，第一屏就有一拍可用；
    // 起不来（极少见）会自动退回"请求现算"，功能不丢。
    disk_sampler_start();

    // 页大小与时钟节拍：整个进程只取一次，采集时每进程都省一次 sysconf
    long page_size = sysconf(_SC_PAGESIZE);

    if (page_size > 0) {
        g_page_size = page_size;
    }

    long clock_ticks = sysconf(_SC_CLK_TCK);

    if (clock_ticks > 0) {
        g_clock_ticks = clock_ticks;
    }

    TmBackend backend = {
        .command_count = 0,
        .running = true,
        .baseline_ready = false,
    };

    // 启动标识：ELF 被拉起后第一行一定是它（客户端只把它当日志，不参与协议解析）
    printf("TaskManager Native Backend proto=%d\n", TM_PROTO_VERSION);

    // 就绪握手：这一行之后，客户端才允许发正式请求
    fputs("READY\n", stdout);

    fflush(stdout);

    run_message_loop(&backend);

    fputs("EXIT stdin closed\n", stdout);
    fflush(stdout);

    // 0 = 正常结束；调用方据此区分"按指令退出"与"异常终止"
    return 0;
}
