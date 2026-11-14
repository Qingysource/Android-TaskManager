/**
 * ============================================================
 * 设置的持久化存储（全应用唯一落盘处）
 * ============================================================
 *
 * 谁在用：
 * - MainActivity：启动时读一次，拿到"保证有效"的设置，用来决定首屏画哪个页面；
 * - UIService：持有设置真值、接收设置页变更、按单项写回；
 * - 其他页面：不直接调本对象，统一走 IUIServiceApi.settings() / settingOf()。
 *
 * 存储位置：filesDir/config/settings.json（UTF-8 纯文本 JSON，带两格缩进）。
 *
 * 为什么是 JSON 文件而不是 SharedPreferences：
 * 1. 设置项会持续增加，需要"整份读出 -> 校验 -> 修复 -> 整份写回"，Pref 只能逐项碰运气；
 * 2. 人类可读，出问题时能直接把文件拉出来看，也便于以后做导入/导出；
 * 3. 支持 .tmp + .bak 的替换流程，崩溃后能回滚，Pref 做不到。
 *
 * 加载策略（核心承诺：永远给出有效数据）：
 * - 文件不存在              -> 用默认值，并把默认配置写成文件；
 * - 文件不可读 / 内容为空   -> 用默认值，并重建文件；
 * - JSON 解析失败（损坏）   -> 用默认值，并重建文件；
 * - 缺项 / 类型不符 / 选项超出白名单 -> 逐项回落默认值，然后把修好的整份回写；
 * - 设置说"开着某功能"但系统已经不给了（目前只有悬浮窗权限被收回 -> 置于顶层强制改回关闭）
 *                            -> 一并修正并回写，避免"设置页显示已开启、实际起不来浮窗"；
 * - 同进程内只读一次盘，之后命中缓存（MainActivity 与 UIService 谁先调都安全）。
 *
 * 线程：全部公开方法 @Synchronized；只在同进程内使用，不涉及跨进程并发。
 */
package com.android.taskmanager.service

import android.content.Context
import android.provider.Settings

import com.android.taskmanager.CredentialRecord
import com.android.taskmanager.DetailColumnId
import com.android.taskmanager.ICredentialStoreApi
import com.android.taskmanager.SettingKey
import com.android.taskmanager.SettingsApi
import com.android.taskmanager.SettingsCatalog
import com.android.taskmanager.SettingsDefaults
import com.android.taskmanager.SettingsValues

import com.android.taskmanager.data.ProcessScope

import org.json.JSONArray
import org.json.JSONObject

import java.io.File

object SettingsStore {

    /** 配置文件所在目录（相对于 filesDir）。 */
    private const val CONFIG_DIR = "config"

    /** 配置文件名。 */
    private const val FILE_NAME = "settings.json"

    /** 写入用的临时文件后缀。 */
    private const val TMP_SUFFIX = ".tmp"

    /** 替换前的备份后缀。 */
    private const val BAK_SUFFIX = ".bak"

    // ============================================================
    // 结果模型
    // ============================================================

    /** 设置是从哪里来的。 */
    enum class Source {

        /** 命中进程内缓存，本次没有读盘。 */
        MEMORY,

        /** 从配置文件读取，内容完整有效。 */
        FILES,

        /** 首次使用：文件不存在，已用默认值创建。 */
        DEFAULTS_CREATED,

        /** 文件不可读 / 解析失败 / 结构损坏：已用默认值重建。 */
        DEFAULTS_RECOVERED,

        /** 尚未加载过（UIService 的初始值）。 */
        UNKNOWN
    }

    /**
     * 一次加载的结果。
     *
     * @param values 校验后的设置快照，一定非空、一定合法
     * @param source 数据来源
     * @param repaired 本次是否发生了修复（回落默认值 / 重建文件 / 回写）
     * @param problems 修复原因清单，便于日志与调试页展示；无修复时为空
     */
    data class LoadResult(
        val values: SettingsValues,
        val source: Source,
        val repaired: Boolean,
        val problems: List<String>
    )

    // ============================================================
    // 进程内缓存
    // ============================================================

    /** 已校验过的设置快照；null = 还没加载过。 */
    @Volatile
    private var cached: SettingsValues? = null

    // ============================================================
    // 路径
    // ============================================================

    /** 配置文件的绝对路径（不存在也可以安全取用）。 */
    fun filePath(context: Context): File = File(File(context.filesDir, CONFIG_DIR), FILE_NAME)

    /** 配置文件是否已存在。 */
    fun exists(context: Context): Boolean = filePath(context).exists()

    /** 把磁盘上的原始文本读出来（调试页展示用，不做解析与校验）。 */
    fun raw(context: Context): String? = try {
        val file = filePath(context)

        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    // ============================================================
    // 启动快速读取（MainActivity 专用）
    // ============================================================

    /**
     * 启动时只需要知道的那几个值。
     *
     * 为什么单独定义一个模型：MainActivity 不该为了"首屏 + 置顶 + 主题"去认识 14 项设置，
     * 更不该 import 整个 SettingsValues。要哪个给哪个，以后加设置项 Main 也不用动。
     */
    data class StartupSettings(

        /** 启动时要显示的页面名称（中文，与设置页下拉的文案一致）。 */
        val startPage: String,

        /** 启动时是否请求窗口置顶。 */
        val alwaysOnTop: Boolean,

        /**
         * 一次性"回原位"记录（空串 = 没有待办）。
         *
         * 非空表示"上一程是在另一侧界面上的，这一程要回到那一页"：
         * MainActivity 用它替代默认起始页，用完立刻清空。
         */
        val lastPage: String,

        /**
         * 应用主题模式（"使用系统设置" / "浅色" / "深色"）。
         *
         * 启动时就要定下来：第一帧界面必须已经是对的颜色，
         * 否则会出现"先白一下再变黑"的闪屏——那条路不值得省一次读盘。
         */
        val appTheme: String
    )

    /** 快速读取失败时的启动默认值（直接取 SettingsDefaults，不另写一份字面量）。 */
    val startupDefaults: StartupSettings = StartupSettings(
        startPage = SettingsDefaults.defaultStartPage,
        alwaysOnTop = SettingsDefaults.windowAlwaysOnTop,
        lastPage = SettingsDefaults.lastPage,
        appTheme = SettingsDefaults.appTheme
    )

    /**
     * 启动快速读取：只吐 MainActivity 要的几个值（首屏 / 置顶 / 回原位 / 主题），出任何意外都给默认值。
     *
     * "快"靠的是三点，而不是少解析几个字段：
     * 1. 复用 [load] 的同一套读盘 / 校验 / 缓存，这里绝不另写第二套解析器
     *    ——两套解析逻辑早晚会不一致，那才是真坑；
     * 2. 第一次调用读一次盘并缓存，UIService 随后 load 直接命中缓存，不重复 IO；
     * 3. 出口只做一次字段映射，不逐项遍历。
     *
     * 失败（filesDir 异常等极端情况）不抛异常、不写盘，直接回落 [startupDefaults]，
     * 保证"一条设置绝不能把应用卡在启动阶段"。
     */
    @Synchronized
    fun loadStartup(context: Context): StartupSettings = try {
        val values = load(context).values

        StartupSettings(
            startPage = values.defaultStartPage,
            alwaysOnTop = values.windowAlwaysOnTop,
            lastPage = values.lastPage,
            appTheme = values.appTheme
        )
    } catch (e: Exception) {
        startupDefaults
    }

    // ============================================================
    // 加载
    // ============================================================

    /**
     * 加载设置：读盘 -> 解析 -> 校验 -> 缺什么补什么 -> 需要时回写。
     *
     * 这是全应用最早也最权威的一次"初始化"，MainActivity 与 UIService 都调它，
     * 第二次开始直接命中缓存，不会再碰磁盘。
     */
    @Synchronized
    fun load(context: Context): LoadResult {
        // 命中缓存也要再校验一次"运行时约束"：权限可能在进程活着的时候被用户收回，
        // 那时候缓存里的值已经过期了。命中缓存 → 不读盘，但仍然比对真实权限。
        cached?.let { hit ->
            val problems = mutableListOf<String>()
            val fixed = applyRuntimeConstraints(context, hit, problems)

            if (fixed != hit) {
                write(context, fixed)

                cached = fixed
            }

            return LoadResult(
                values = fixed,
                source = Source.MEMORY,
                repaired = problems.isNotEmpty(),
                problems = problems
            )
        }

        val file = filePath(context)

        // 1. 文件不存在：用默认值创建，保证下一次启动能读到东西
        if (!file.exists()) {
            val written = write(context, SettingsDefaults)

            cached = SettingsDefaults

            return LoadResult(
                values = SettingsDefaults,
                source = Source.DEFAULTS_CREATED,
                repaired = true,
                problems = listOf(
                    if (written) "配置文件不存在，已用默认值创建" else "配置文件不存在，且默认配置写入失败（本次用内存默认值）"
                )
            )
        }

        // 2. 文件存在但读不出来 / 是空的：按损坏处理
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }

        if (raw.isNullOrBlank()) {
            write(context, SettingsDefaults)

            cached = SettingsDefaults

            return LoadResult(
                values = SettingsDefaults,
                source = Source.DEFAULTS_RECOVERED,
                repaired = true,
                problems = listOf("配置文件不可读或内容为空，已用默认值重建")
            )
        }

        // 3. JSON 解析失败：按损坏处理（手改文件、写入中断、字符集被破坏都会走到这里）
        val saved = decode(raw)

        if (saved == null) {
            write(context, SettingsDefaults)

            cached = SettingsDefaults

            return LoadResult(
                values = SettingsDefaults,
                source = Source.DEFAULTS_RECOVERED,
                repaired = true,
                problems = listOf("配置文件 JSON 解析失败，已用默认值重建")
            )
        }

        // 4. 解析成功：逐项校验，缺项 / 类型不符 / 选项非法全部回落默认值
        val problems = diagnose(saved).toMutableList()

        // 再加上"运行时约束"（例如悬浮窗权限被收回 -> 置于顶层改回关闭）。
        // 这类问题不是"文件坏了"，而是"文件说的和系统现状不一致"，所以单独一步。
        val values = applyRuntimeConstraints(context, sanitize(SettingsApi.fromMap(saved)), problems)

        // 有修复就回写，让磁盘上的内容跟内存一致；没有修复则不动磁盘
        if (problems.isNotEmpty()) write(context, values)

        cached = values

        return LoadResult(
            values = values,
            source = Source.FILES,
            repaired = problems.isNotEmpty(),
            problems = problems
        )
    }

    // ============================================================
    // 保存
    // ============================================================

    /**
     * 整份保存（重置、导入用）。
     *
     * 写盘失败时仍然更新内存快照：让本次会话里页面显示的和"服务认为的"保持一致，
     * 只在返回值里告诉调用方没有持久化成功。
     */
    @Synchronized
    fun save(context: Context, values: SettingsValues): Boolean {
        val safe = sanitize(values)

        cached = safe

        return write(context, safe)
    }

    /**
     * 单项更新（设置页每一次 checkbox / 下拉变更都会走到这里）。
     *
     * 返回更新后的完整快照，调用方直接用，不必再 load 一次。
     * 类型不符时保留原值（例如给 Boolean 项传了 String）。
     */
    @Synchronized
    fun update(context: Context, key: SettingKey, value: Any): SettingsValues {
        val current = load(context).values
        val next = applyRuntimeConstraints(context, sanitize(applyAction(current, key, value)))

        if (next != current) write(context, next)

        cached = next

        return next
    }

    /** 重置为工厂默认值，并落盘（返回重置后的快照）。 */
    @Synchronized
    fun reset(context: Context): SettingsValues {
        save(context, SettingsDefaults)

        return SettingsDefaults
    }

    // ============================================================
    // 内部实现
    // ============================================================

    /** 快照 -> JSON 文本（两格缩进，便于人读）。 */
    private fun encode(values: SettingsValues): String {
        val map = SettingsApi.toMap(values)
        val json = JSONObject()

        map.forEach { (storageKey, value) ->
            json.put(storageKey, value)
        }

        return json.toString(2)
    }

    /** JSON 文本 -> "存储键 -> 值"；解析失败返回 null（由调用方按损坏处理）。 */
    private fun decode(raw: String): Map<String, Any?>? = try {
        val json = JSONObject(raw)

        json.keys().asSequence().associateWith { storageKey ->
            val value = json.opt(storageKey)

            if (value == JSONObject.NULL) null else value
        }
    } catch (e: Exception) {
        // 解析失败 / 顶层不是对象 / 编码损坏，统一按"文件损坏"处理
        null
    }

    /**
     * 找出所有"不能用"的项：缺项、类型不符、选项超出白名单。
     *
     * 只做诊断不做修复，修复交给 [sanitize]，这样"是否修复过"能准确判断，
     * 也方便把问题清单打给调试页。
     */
    private fun diagnose(saved: Map<String, Any?>): List<String> {
        val problems = mutableListOf<String>()

        SettingsApi.keys.forEach { key ->
            if (!saved.containsKey(key.storageKey)) {
                problems += "缺少设置项 ${key.storageKey}"

                return@forEach
            }

            val ok = when (SettingsApi.defaultValueOf(key)) {
                is String -> {
                    val value = saved[key.storageKey]

                    value is String && value in allowedValues(key)
                }

                is Boolean -> saved[key.storageKey] is Boolean

                // 以后加别的类型时在这里补校验分支
                else -> true
            }

            if (!ok) problems += "类型或取值不合法：${key.storageKey}"
        }

        return problems
    }

    /** 某一项的取值白名单；Boolean 项返回空表（不做白名单校验）。 */
    private fun allowedValues(key: SettingKey): List<String> = when (key) {
        SettingKey.APP_THEME -> SettingsCatalog.THEMES
        SettingKey.DEFAULT_START_PAGE -> SettingsCatalog.START_PAGES
        SettingKey.UPDATE_SPEED -> SettingsCatalog.UPDATE_SPEEDS
        SettingKey.LAST_PAGE -> SettingsCatalog.PAGES

        else -> emptyList()
    }

    /**
     * 修正不合法的组合，保证交出去的值一定能被 UI 正确使用。
     *
     * 注意这里只做"纯数据"层面的规则（不需要 Context）：
     * 1. 三个字符串选项必须在白名单内，否则回落默认（防止下拉框显示一个不存在的选项）；
     * 2. "捕获虚拟机监控程序页面"关闭时，"包括非必要页面"必须也为关闭
     *    （与设置页 updateCaptureHypervisorPages 的联动规则保持一致）。
     *
     * 需要查系统状态的规则（例如悬浮窗权限被收回）不在这里，见 [applyRuntimeConstraints]。
     */
    private fun sanitize(values: SettingsValues): SettingsValues {
        var result = values

        if (result.appTheme !in SettingsCatalog.THEMES) {
            result = result.copy(appTheme = SettingsDefaults.appTheme)
        }

        if (result.defaultStartPage !in SettingsCatalog.START_PAGES) {
            result = result.copy(defaultStartPage = SettingsDefaults.defaultStartPage)
        }

        // 更新速度：旧版本存的是"暂停"，档位重排成 高/常规/低/已暂停 后要认这一种写法，
        // 否则老用户的设置会被判非法、静默回落到"常规"。迁移一次就够，之后按新文案校验。
        if (result.updateSpeed == "暂停") {
            result = result.copy(updateSpeed = "已暂停")
        }

        if (result.updateSpeed !in SettingsCatalog.UPDATE_SPEEDS) {
            result = result.copy(updateSpeed = SettingsDefaults.updateSpeed)
        }

        // last_page：空串是合法状态（没有待办）；非空就必须是真实页面名，
        // 否则启动时拿去匹配页签只会匹配不上——直接清成"没有待办"最安全
        if (result.lastPage.isNotBlank() && result.lastPage !in SettingsCatalog.PAGES) {
            result = result.copy(lastPage = SettingsDefaults.lastPage)
        }

        if (!result.captureHypervisorPages && result.captureNonEssentialPages) {
            result = result.copy(captureNonEssentialPages = false)
        }

        return result
    }

    /**
     * 修正"设置说开着、但系统已经不给"的组合（属于运行时约束，不是文件损坏）。
     *
     * 目前一条规则：
     *     置顶（window_always_on_top）= true，但悬浮窗权限已经不在 -> 强制改回 false。
     *
     * 为什么必须在**读盘/缓存命中**时都做：
     * 1. MainActivity 拿这个值决定"要不要以浮窗启动"。权限没了而值仍是 true，
     *    用户看到的就是"设置里明明开着置顶，启动却没有浮窗"——正是这个 bug；
     * 2. 更麻烦的是反向：用户过一阵又把权限授予回来，那次启动会**突然**切进浮窗模式，
     *    用户根本不知道自己还留着这个标记。所以发现没权限就当场清掉，别留隐患；
     * 3. 报的问题清单会进 [LoadResult.problems]，调试页能看见这次是被系统权限纠正的。
     *
     * @param problems 非 null 时把修正原因写进去（由调用方决定要不要提示/回写）
     */
    private fun applyRuntimeConstraints(
        context: Context,
        values: SettingsValues,
        problems: MutableList<String>? = null
    ): SettingsValues {
        if (values.windowAlwaysOnTop && !mayDrawOverlays(context)) {
            problems?.add("悬浮窗权限已不在，已把「置于顶层」改回关闭")

            return values.copy(windowAlwaysOnTop = false)
        }

        return values
    }

    /**
     * 当前有没有悬浮窗权限。
     *
     * 查询本身出错时按"有权限"处理：一次读取异常不该悄悄关掉用户设置的功能，
     * 真正建窗时 [UIService.showOverlay] 还会再兜一次。
     */
    private fun mayDrawOverlays(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (e: Exception) {
        true
    }

    /** 把一项变更应用到快照上；类型不符时保留原值。 */
    private fun applyAction(
        values: SettingsValues,
        key: SettingKey,
        value: Any
    ): SettingsValues {

        fun asString(fallback: String): String = value as? String ?: fallback

        fun asBoolean(fallback: Boolean): Boolean = value as? Boolean ?: fallback

        return when (key) {
            SettingKey.APP_THEME -> values.copy(appTheme = asString(values.appTheme))

            SettingKey.DEFAULT_START_PAGE -> values.copy(defaultStartPage = asString(values.defaultStartPage))

            SettingKey.UPDATE_SPEED -> values.copy(updateSpeed = asString(values.updateSpeed))

            SettingKey.WINDOW_ALWAYS_ON_TOP -> values.copy(windowAlwaysOnTop = asBoolean(values.windowAlwaysOnTop))

            SettingKey.LAST_PAGE -> values.copy(lastPage = asString(values.lastPage))

            SettingKey.MINIMIZE_WHEN_IN_USE -> values.copy(minimizeWhenInUse = asBoolean(values.minimizeWhenInUse))

            SettingKey.HIDE_WHEN_MINIMIZED -> values.copy(hideWhenMinimized = asBoolean(values.hideWhenMinimized))

            SettingKey.SHOW_FULL_ACCOUNT_NAME -> values.copy(showFullAccountName = asBoolean(values.showFullAccountName))

            SettingKey.SHOW_ALL_PROCESS_HISTORY -> values.copy(showAllProcessHistory = asBoolean(values.showAllProcessHistory))

            SettingKey.CONFIRM_EFFICIENCY_MODE -> values.copy(confirmEfficiencyMode = asBoolean(values.confirmEfficiencyMode))

            SettingKey.ABORT_ON_INSUFFICIENT_MEMORY -> values.copy(abortOnInsufficientMemory = asBoolean(values.abortOnInsufficientMemory))

            SettingKey.CAPTURE_HYPERVISOR_PAGES -> values.copy(captureHypervisorPages = asBoolean(values.captureHypervisorPages))

            SettingKey.CAPTURE_NON_ESSENTIAL_PAGES -> values.copy(captureNonEssentialPages = asBoolean(values.captureNonEssentialPages))

            SettingKey.CAPTURE_USER_PAGES -> values.copy(captureUserPages = asBoolean(values.captureUserPages))
        }
    }

    /**
     * 原子性尽可能好的写盘：先写 .tmp，再把旧文件挪成 .bak，最后 rename 顶上。
     *
     * 说明：
     * - 正常流程结束会删掉 .bak，只有进程在中途被杀时才会残留，可以用来手工恢复；
     * - rename 在部分文件系统上可能失败（目标已存在 / 被占用），此时退化为复制覆盖；
     * - TODO(预留): 接入真实数据后如果配置变大，可考虑改成 mmap + 校验和。
     */
    private fun write(context: Context, values: SettingsValues): Boolean {
        val target = filePath(context)
        val dir = target.parentFile ?: return false

        return try {
            if (!dir.exists() && !dir.mkdirs()) return false

            val tmp = File(dir, FILE_NAME + TMP_SUFFIX)

            tmp.writeText(encode(values), Charsets.UTF_8)

            val backup = File(dir, FILE_NAME + BAK_SUFFIX)

            if (target.exists()) {
                if (backup.exists()) backup.delete()

                target.renameTo(backup)
            }

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)

                tmp.delete()
            }

            if (backup.exists()) backup.delete()

            true
        } catch (e: Exception) {
            // 写盘失败不抛给调用方：设置是"可用性优先"，失败时内存值仍然生效
            false
        }
    }
}

// ============================================================
// 凭据存档（ICredentialStoreApi 的实现）
//
// 为什么要单独一份文件，而不是塞进 settings.json：
// - settings.json 有严格的字段表（SettingKey / SettingsApi / diagnose 缺项校验），
//   多一个字段就得改整条设置管线，还要考虑"重置设置"要不要清它；
// - 这里存的是**能力事实**（上次真的拿到过什么权限），不是用户偏好，
//   "重置设置"不该顺手抹掉它；
// - 设置文件损坏时它能独立兜底，不牵连设置页的修复流程。
//
// 文件位置：files/config/credential.json
// 写入方式与 settings.json 一致（.tmp -> rename），失败只返回 false，绝不抛异常。
// ============================================================

object CredentialStore : ICredentialStoreApi {

    /** 与设置同目录，便于"整个 config 一起备份/清理"。 */
    private const val CONFIG_DIR = "config"

    /** 存档文件名。 */
    private const val FILE_NAME = "credential.json"

    private const val TMP_SUFFIX = ".tmp"

    // 存档字段名：写进 JSON 的 key，独立于设置项的 storageKey，避免撞名
    private const val KEY_IDENTITY = "last_identity"
    private const val KEY_SHIZUKU_GRANTED = "shizuku_granted"
    private const val KEY_ROOT_GRANTED = "root_granted"
    private const val KEY_SHIZUKU_ROOT = "shizuku_root"

    /** 存档文件路径。 */
    fun filePath(context: Context): File = File(File(context.filesDir, CONFIG_DIR), FILE_NAME)

    /**
     * 读存档。
     *
     * 永不抛异常：文件不存在 / 解析失败 / 字段类型不对 / 身份值不认识，
     * 一律退回"普通权限 + 未授权"，让调用方（UIService）按"没有历史"处理。
     *
     * 为什么逐个 opt 而不是整体反序列化：单个字段坏掉不该把整份存档作废，
     * 能救回来的（比如两个布尔位）就救回来。
     */
    override fun load(context: Context): CredentialRecord {

        val raw = try {
            val file = filePath(context)

            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            null
        } ?: return CredentialRecord()

        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return CredentialRecord()
        }

        val identity = json.optString(KEY_IDENTITY, SettingsCatalog.CREDENTIAL_NORMAL)
            .takeIf { it in SettingsCatalog.CREDENTIAL_IDENTITIES }
            ?: SettingsCatalog.CREDENTIAL_NORMAL

        return CredentialRecord(
            identity = identity,
            shizukuGranted = json.optBoolean(KEY_SHIZUKU_GRANTED, false),
            rootGranted = json.optBoolean(KEY_ROOT_GRANTED, false),
            shizukuRoot = json.optBoolean(KEY_SHIZUKU_ROOT, false)
        )
    }

    /** 写存档：先写 .tmp 再 rename 顶上，进程被杀也不会留下半份文件。 */
    override fun save(context: Context, record: CredentialRecord): Boolean {

        val target = filePath(context)
        val dir = target.parentFile ?: return false

        return try {

            if (!dir.exists() && !dir.mkdirs()) return false

            // 身份兜底：不认识的值不能写进磁盘，否则下次启动会读到脏值
            val identity = record.identity.takeIf { it in SettingsCatalog.CREDENTIAL_IDENTITIES }
                ?: SettingsCatalog.CREDENTIAL_NORMAL

            val json = JSONObject()
                .put(KEY_IDENTITY, identity)
                .put(KEY_SHIZUKU_GRANTED, record.shizukuGranted)
                .put(KEY_ROOT_GRANTED, record.rootGranted)
                .put(KEY_SHIZUKU_ROOT, record.shizukuRoot)

            val tmp = File(dir, FILE_NAME + TMP_SUFFIX)

            tmp.writeText(json.toString(), Charsets.UTF_8)

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)

                tmp.delete()
            }

            true
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================
// 详细信息页的列布局（独立存档）
// ============================================================
//
// 存什么：用户最后确定的"可见列"（列 id 列表）。下次打开详细信息页按它布局。
//
// 为什么单独一个文件，而不是塞进 settings.json：
// 1. 它是"页面的视图状态"，不是设置项——设置页不显示、不管理、不参与"与默认值比较"；
// 2. "重置所有设置"只能重置设置，不该顺手把用户调好的列布局抹掉；
// 3. 单独文件坏掉也不影响设置本身启动。
//
// 文件位置：files/config/detail_view.json；写入方式与 settings.json 一致（.tmp -> rename）。
// ============================================================

object DetailViewStore {

    private const val CONFIG_DIR = "config"

    private const val FILE_NAME = "detail_view.json"

    private const val TMP_SUFFIX = ".tmp"

    /** 存档字段：可见列 id 数组。 */
    private const val KEY_VISIBLE_COLUMNS = "visible_columns"

    /** 存档字段：进程范围（0 全部 / 2 系统 / 1 用户，与协议里的数字一致）。 */
    private const val KEY_PROCESS_SCOPE = "process_scope"

    /** 存档字段："忽略实时更新速度"是否勾选（true = 页面不自动刷新）。 */
    private const val KEY_IGNORE_SPEED = "ignore_update_speed"

    fun filePath(context: Context): File = File(File(context.filesDir, CONFIG_DIR), FILE_NAME)

    /** 一次读出来的三样东西：可见列、进程范围、"忽略实时更新速度"。 */
    class Preferences(
        val visibleIds: MutableSet<String>,
        val scope: ProcessScope,
        val ignoreUpdateSpeed: Boolean
    )

    /**
     * 读整份视图状态（列布局 + 范围 + 忽略开关）。
     *
     * 承诺：永远返回一份可用的列集合，且**必定包含「名称」列**（每行都要有个能读的身份）。
     * 没有存档 / 存档不可用 / 存档里的列全都不认识时用 [defaults]，并把默认布局立刻写盘——
     * 这样"下次打开还是这个样子"从第一次起就成立。
     *
     * @param knownIds 当前列定义里存在的 id；存档里多出来的旧列一律丢弃
     * @param defaults 没有存档时用的默认列
     */
    fun loadPreferences(
        context: Context,
        knownIds: Set<String>,
        defaults: List<String>
    ): Preferences {

        val fallback = defaults.filter { it in knownIds }
            .ifEmpty { knownIds.toList() }
            .toCollection(LinkedHashSet())

        val json = try {
            val file = filePath(context)

            if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else null
        } catch (e: Exception) {
            null
        }

        val scope = ProcessScope.ofWire(json?.optString(KEY_PROCESS_SCOPE))

        // 默认勾选（= 默认不自动刷新）；存档里没这个键时也按默认走
        val ignoreSpeed = if (json != null && json.has(KEY_IGNORE_SPEED)) {
            json.optBoolean(KEY_IGNORE_SPEED, true)
        } else {
            true
        }

        val saved = json?.optJSONArray(KEY_VISIBLE_COLUMNS)?.let { array ->
            (0 until array.length())
                .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
        }

        val ids = saved?.filter { it in knownIds }?.toCollection(LinkedHashSet())

        if (ids.isNullOrEmpty()) {
            save(context, fallback, scope, ignoreSpeed)

            return Preferences(fallback, scope, ignoreSpeed)
        }

        ids.add(DetailColumnId.NAME)

        return Preferences(ids, scope, ignoreSpeed)
    }

    /**
     * 写整份视图状态。
     *
     * 调用方负责决定线程：这是几十字节的写盘，但仍然是 IO，不要放在 UI 线程上。
     * 失败只返回 false（磁盘满 / 目录不可写），不抛异常——视图状态丢了不该让页面崩。
     */
    fun save(
        context: Context,
        ids: Set<String>,
        scope: ProcessScope,
        ignoreUpdateSpeed: Boolean
    ): Boolean {

        if (ids.isEmpty()) return false

        val target = filePath(context)
        val dir = target.parentFile ?: return false

        return try {

            if (!dir.exists() && !dir.mkdirs()) return false

            val array = JSONArray()

            ids.forEach { array.put(it) }

            val json = JSONObject()
                .put(KEY_VISIBLE_COLUMNS, array)
                .put(KEY_PROCESS_SCOPE, scope.wire)
                .put(KEY_IGNORE_SPEED, ignoreUpdateSpeed)

            val tmp = File(dir, FILE_NAME + TMP_SUFFIX)

            tmp.writeText(json.toString(2), Charsets.UTF_8)

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)

                tmp.delete()
            }

            true
        } catch (e: Exception) {
            false
        }
    }
}
