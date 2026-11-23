/* ============================================================
 * Protocol.c — 协议层：请求解码、状态映射、设备字段与应答包输出格式。
 * ============================================================ */

#include "Header.h"

/** 状态转协议里的字段状态串；TM_QUERY_OK 没有状态串，返回 NULL。 */
static const char *query_status_text(TmQueryStatus status)
{
    switch (status) {
        case TM_QUERY_OK:
            return NULL;

        case TM_QUERY_UNSUPPORTED:
            return TM_FIELD_STATUS_UNSUPPORTED;

        case TM_QUERY_READ_FAILED:
            return TM_FIELD_STATUS_READ_FAILED;

        case TM_QUERY_ACCESS_DENIED:
            return TM_FIELD_STATUS_ACCESS_DENIED;

        case TM_QUERY_NOT_AVAILABLE:
            return TM_FIELD_STATUS_NOT_AVAILABLE;

        case TM_QUERY_NO_BASELINE:
            return TM_FIELD_STATUS_NO_BASELINE;

        case TM_QUERY_TRUNCATED:
            return TM_FIELD_STATUS_TRUNCATED;
    }

    return TM_FIELD_STATUS_NOT_AVAILABLE;
}

/** 状态串转回枚举：老调用点按状态串传参，内部统一成枚举再落地。 */
TmQueryStatus query_status_of(const char *status)
{
    if (status == NULL) {
        return TM_QUERY_UNSUPPORTED;
    }

    if (strcmp(status, TM_FIELD_STATUS_UNSUPPORTED) == 0) {
        return TM_QUERY_UNSUPPORTED;
    }

    if (strcmp(status, TM_FIELD_STATUS_ACCESS_DENIED) == 0) {
        return TM_QUERY_ACCESS_DENIED;
    }

    if (strcmp(status, TM_FIELD_STATUS_NOT_AVAILABLE) == 0) {
        return TM_QUERY_NOT_AVAILABLE;
    }

    if (strcmp(status, TM_FIELD_STATUS_NO_BASELINE) == 0) {
        return TM_QUERY_NO_BASELINE;
    }

    if (strcmp(status, TM_FIELD_STATUS_TRUNCATED) == 0) {
        return TM_QUERY_TRUNCATED;
    }

    return TM_QUERY_READ_FAILED;
}

/**
 * 百分号编码输出一个值（与客户端 data/LinuxService.kt ① 段的 escapeValue 逐项一致）。
 * 编码表见头部注释第四节；不编码会把含空格的值（cmdline、路径）拆成两半。
 *
 * @param value 原始值（不可为 NULL）
 */
void print_escaped_value(const char *value)
{
    for (const char *cursor = value; *cursor != '\0'; cursor++) {

        switch (*cursor) {

            case ' ':
                fputs("%20", stdout);
                break;

            case '\n':
                fputs("%0A", stdout);
                break;

            case '\r':
                fputs("%0D", stdout);
                break;

            case '\t':
                fputs("%09", stdout);
                break;

            case '%':
                fputs("%25", stdout);
                break;

            case '=':
                fputs("%3D", stdout);
                break;

            case '!':
                fputs("%21", stdout);
                break;

            default:
                fputc(*cursor, stdout);
                break;
        }
    }
}

/** 打印一条附加信息行 `I 键=值`；值同样要百分号编码，否则空格会把一行拆断。 */
void print_info_line(const char *key, const char *value)
{
    printf("I %s=", key);

    print_escaped_value(value);

    printf("\n");
}

/**
 * 打印应答包头 `PKG <seq> 1 <typeCode> <maskHex> <status> <count>`。
 * isFromSvc 恒为 1：这条通道上只有服务端发 PKG。
 *
 * @param count 后面跟的 P 行条数；0 = 没有数据行（信息类 / 控制回执）
 */
void reply_package_header(unsigned long long seq, int type,
                                 unsigned long long mask, int status, int count)
{
    printf("PKG %llu 1 %d 0x%llx %d %d\n", seq, type, mask, status, count);
}

/** 开一个设备：清掉旧字段，记下 id 与类型。 */
void device_begin(TmOutDevice *device, const char *id, const char *type)
{
    memset(device, 0, sizeof(*device));

    snprintf(device->id, sizeof(device->id), "%s", id);

    device->type = type;
    device->field_count = 0;
}

/** 造一个只有状态的结果（这一项没有值）。 */
TmQueryValue query_value_none(TmQueryStatus status)
{
    TmQueryValue value;

    memset(&value, 0, sizeof(value));

    value.status = status;

    return value;
}

/** 造一个有文本值的结果；文本为空按 UNSUPPORTED 处理，不发空字段。 */
TmQueryValue query_value_text(TmQueryStatus status, const char *text)
{
    if (status != TM_QUERY_OK || text == NULL || text[0] == '\0') {
        return query_value_none(status == TM_QUERY_OK ? TM_QUERY_UNSUPPORTED : status);
    }

    TmQueryValue value;

    memset(&value, 0, sizeof(value));

    value.status = TM_QUERY_OK;

    snprintf(value.text, sizeof(value.text), "%s", text);

    return value;
}

/** 造一个整数值的结果：负数按没有值处理（采集函数统一用 -1 表示读不到）。 */
TmQueryValue query_value_int(long long value, TmQueryStatus missing)
{
    if (value < 0) {
        return query_value_none(missing);
    }

    TmQueryValue result;

    memset(&result, 0, sizeof(result));

    result.status = TM_QUERY_OK;

    snprintf(result.text, sizeof(result.text), "%lld", value);

    return result;
}

/** 造一个浮点值的结果：固定小数位，负数按没有值处理。 */
TmQueryValue query_value_float(bool has_value, double value, int digits,
                                     TmQueryStatus missing)
{
    if (!has_value) {
        return query_value_none(missing);
    }

    TmQueryValue result;

    memset(&result, 0, sizeof(result));

    result.status = TM_QUERY_OK;

    snprintf(result.text, sizeof(result.text), "%.*f", digits, value);

    return result;
}

/**
 * 所有字段落地的唯一出口：状态在这里转成感叹号标记，有值才写数值。
 *
 * 为什么收成一个出口：字段有十几种来源（sysfs、/proc、外部实现、算法差值），
 * 只要每个来源都能说清自己是 TM_QUERY_OK 还是某种失败，输出就永远只有两种形态。
 * 超过字段上限直接丢，宁可少一格也不越界。
 */
void device_put_value(TmOutDevice *device, const char *key, TmQueryValue value)
{
    if (device->field_count >= TM_OUT_FIELD_MAX) {
        return;
    }

    TmOutField *field = &device->fields[device->field_count++];

    snprintf(field->key, sizeof(field->key), "%s", key);

    if (value.status != TM_QUERY_OK) {

        const char *status = query_status_text(value.status);

        snprintf(field->value, sizeof(field->value), "!%s",
                 status != NULL ? status : TM_FIELD_STATUS_NOT_AVAILABLE);

        return;
    }

    snprintf(field->value, sizeof(field->value), "%s", value.text);
}

/** 追加一条有值的文本字段。 */
void device_put(TmOutDevice *device, const char *key, const char *value)
{
    device_put_value(device, key, query_value_text(TM_QUERY_OK, value));
}

/** 整数字段：负数按没有值处理（采集函数统一用 -1 表示读不到）。 */
void device_put_int(TmOutDevice *device, const char *key, long long value,
                           const char *status)
{
    device_put_value(device, key, query_value_int(value, query_status_of(status)));
}

/** 发一个设备：一行 D 头 + 若干 F 行；字段值走同一套百分号编码。 */
void emit_device_block(const TmOutDevice *device)
{
    printf("D id=%s type=%s\n", device->id, device->type);

    for (int i = 0; i < device->field_count; i++) {

        const TmOutField *field = &device->fields[i];

        // 感叹号开头是状态标记，原样发，不做编码
        if (field->value[0] == '!') {

            printf("F %s=%s\n", field->key, field->value);

            continue;
        }

        printf("F %s=", field->key);

        print_escaped_value(field->value);

        printf("\n");
    }
}
