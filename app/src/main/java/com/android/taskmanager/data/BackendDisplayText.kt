package com.android.taskmanager.data

import com.android.taskmanager.AppStrings
import com.android.taskmanager.DetailColumnId
import com.android.taskmanager.R
import java.util.Locale

/**
 * 后端状态码 -> 界面文案的唯一映射表。
 *
 * 为什么要有这一层：协议是"英文标识 + 状态码"的，后端只发 SAMSUNG / ENABLED_KVM / R / 1 这种
 * 机器可读的值，不掺任何本地语言 —— 这样多语言适配只改这里，不用回头动 ELF 侧。
 * 界面（以及日志之外的任何给人看的地方）都必须经过本文件，别把原始码直接画到屏幕上。
 *
 * 约定：
 *   - 认得的码 -> 资源文案（跟随系统语言，英文默认 / 简体中文）；
 *   - 不认得的值 -> 原样返回。调试期注入的中文行、以及将来新增的码仍能看到原文，
 *     总比显示成"未知"更难排查要好；
 *   - null / 空串表示"这一格没有值"，由调用方决定占位符（`-` / 不支持），这里不替它决定。
 */
object BackendDisplayText {

    /** 进程隔离状态码（AndroidService 上报，uid 落在 99000..99999 为已隔离）。 */
    const val ISOLATION_YES: String = "ISOLATED"
    const val ISOLATION_NO: String = "NOT_ISOLATED"

    private val UNSUPPORTED_TEXT: String
        get() = AppStrings.s(R.string.data_unsupported)

    // ============================================================
    // CPU：虚拟化
    // ============================================================

    /**
     * 虚拟化状态码 -> 文案。
     *
     * 后端现在只发这几种：ENABLED_<实现名> / DISABLED / UNSUPPORTED。
     * 加新实现只要在 [virtualizationImplText] 里补一行写法（如果需要的话），
     * 没补的实现名会原样嵌进括号里，不会空着。
     */
    fun virtualizationText(code: String?): String {

        val raw = code?.trim().orEmpty()

        if (raw.isEmpty() || raw.equals("UNSUPPORTED", ignoreCase = true)) {
            return UNSUPPORTED_TEXT
        }

        if (raw.equals("DISABLED", ignoreCase = true)) {
            return AppStrings.s(R.string.data_virtualization_disabled)
        }

        val enabledPrefix = "ENABLED_"

        if (raw.startsWith(enabledPrefix, ignoreCase = true)) {

            val impl = raw.substring(enabledPrefix.length)

            return AppStrings.s(
                R.string.data_virtualization_enabled,
                virtualizationImplText(impl)
            )
        }

        // 老格式 / 调试注入：已经是给人看的话，原样放行
        return raw
    }

    /** 实现名的显示写法：官方拼法（大小写敏感的商标）在这里统一，其余原样。 */
    private fun virtualizationImplText(impl: String): String =
        when (impl.uppercase(Locale.US)) {

            "KVM" -> "KVM"

            "GUNYAH" -> "Gunyah"

            "GENIEZONE" -> "GenieZone"

            "HALLA" -> "Halla"

            "AMD-V", "SVM" -> "AMD-V"

            "VT-X", "VMX" -> "VT-x"

            else -> impl.replace('_', ' ')
        }

    // ============================================================
    // 进程：状态 / 位宽 / 布尔量 / 名字
    // ============================================================

    /**
     * 进程状态码 -> 文案。码就是内核 state 的单个字母（与 `ps` 一致），
     * 大小写有意义：`T` 是"已停止"（收到 SIGSTOP），`t` 是"跟踪停止"（被调试器停住）。
     */
    fun processStateText(code: String?): String =
        when (code?.trim()) {

            "R" -> AppStrings.s(R.string.data_state_running)

            "S" -> AppStrings.s(R.string.data_state_sleeping)

            "D" -> AppStrings.s(R.string.data_state_uninterruptible)

            "Z" -> AppStrings.s(R.string.data_state_zombie)

            "T" -> AppStrings.s(R.string.data_state_stopped)

            "t" -> AppStrings.s(R.string.data_state_traced_stop)

            "I" -> AppStrings.s(R.string.data_state_idle)

            "X" -> AppStrings.s(R.string.data_state_exited)

            "UNKNOWN" -> AppStrings.s(R.string.data_state_unknown)

            null, "" -> ""

            else -> code.trim()
        }

    /**
     * 位宽码 -> 文案：后端只发 `32` / `64`，"位 / bit"这个单位由这里补。
     *
     * 纯数字才加工；已经是完整话术（例如 `64-bit` 或调试注入的中文）原样返回。
     */
    fun bitsText(code: String?): String {

        val raw = code?.trim().orEmpty()

        if (raw.isEmpty()) return raw

        return if (raw.all { it.isDigit() }) {
            AppStrings.s(R.string.data_bits, raw)
        } else {
            raw
        }
    }

    /**
     * 布尔码 -> 文案：后端一律发 `1` / `0`，不出现"是 / 否"。
     * 不是 0/1 的值原样返回（兼容还没改完的老字段）。
     */
    fun yesNoText(code: String?): String =
        when (code?.trim()) {

            "1" -> AppStrings.s(R.string.data_yes)

            "0" -> AppStrings.s(R.string.data_no)

            null, "" -> ""

            else -> code.trim()
        }

    /**
     * 进程名：僵尸进程后端只发内核风格的 `[comm]`，这里按 state == Z 补一句说明，
     * 让表格里一眼看出"它为什么杀不死"。其它状态原样返回。
     */
    fun processNameText(name: String?, state: String?): String {

        val raw = name?.trim().orEmpty()

        if (raw.isEmpty()) return raw

        if (state?.trim() != "Z") return raw

        // 名字本身没取到（占位符）时不追加说明，否则会变成"—（暂无数据） (僵尸进程)"
        if (raw.startsWith("—")) return raw

        // 已经带了说明（老后端 / 调试行）就不重复追加。两种语言都要认：
        // 切语言后同一行可能带着上一轮拼好的说明进来。
        if (raw.contains("僵尸") || raw.contains("zombie", ignoreCase = true)) return raw

        return AppStrings.s(R.string.data_zombie_suffix, raw)
    }

    /**
     * 详细信息表里"某一列的值"的统一入口：按列 id 决定用哪张映射表。
     *
     * 调用点只有 [LinuxProcessRecord.displayOf] 一个 —— 表格所有格子都从那里出来，
     * 新加一列状态码只需在这里补一个分支，页面上不用再动。
     */
    fun processColumnText(columnId: String?, value: String?): String {

        val raw = value ?: return ""

        return when (columnId) {

            DetailColumnId.STATE -> processStateText(raw)

            DetailColumnId.PLATFORM -> bitsText(raw)

            DetailColumnId.IS_ADMIN, DetailColumnId.NX_PROTECT, DetailColumnId.CET_PROTECT -> yesNoText(raw)

            DetailColumnId.ISOLATION -> when (raw.trim()) {
                ISOLATION_YES -> AppStrings.s(R.string.data_isolated)
                ISOLATION_NO -> AppStrings.s(R.string.data_not_isolated)
                else -> raw
            }

            else -> raw
        }
    }

    // ============================================================
    // 内存：颗粒厂商
    // ============================================================

    /**
     * 颗粒厂商码（JEDEC 编号换算出来的英文码）-> 文案。
     *
     * 中文用"中文名（English）"的写法，英文只用品牌名。
     * 表里没有的码原样返回：新颗粒上市时先看到码，再决定要不要补名字。
     */
    fun memoryVendorText(code: String?): String {

        val raw = code?.trim().orEmpty()

        if (raw.isEmpty()) return raw

        return when (raw.uppercase(Locale.US)) {

            "SAMSUNG" -> AppStrings.s(R.string.data_vendor_samsung)

            "QIMONDA" -> AppStrings.s(R.string.data_vendor_qimonda)

            "ELPIDA" -> AppStrings.s(R.string.data_vendor_elpida)

            "ETRON" -> AppStrings.s(R.string.data_vendor_etron)

            "NANYA" -> AppStrings.s(R.string.data_vendor_nanya)

            "HYNIX" -> AppStrings.s(R.string.data_vendor_hynix)

            "MOSEL" -> AppStrings.s(R.string.data_vendor_mosel)

            "WINBOND" -> AppStrings.s(R.string.data_vendor_winbond)

            "ESMT" -> AppStrings.s(R.string.data_vendor_esmt)

            "SPANSION" -> AppStrings.s(R.string.data_vendor_spansion)

            "SST" -> AppStrings.s(R.string.data_vendor_sst)

            "ZMOS" -> AppStrings.s(R.string.data_vendor_zmos)

            "INTEL" -> AppStrings.s(R.string.data_vendor_intel)

            "NUMONYX" -> AppStrings.s(R.string.data_vendor_numonyx)

            "MICRON" -> AppStrings.s(R.string.data_vendor_micron)

            else -> raw
        }
    }

    // ============================================================
    // GPU：PCI 物理位置
    // ============================================================

    /**
     * PCI 位置：后端发的是三个整数（bus / device / function），这里拼成本地语言文案。
     *
     * 三段里缺任何一段都算"这台机器没给这一格"——SoC 核显不是 PCI 设备，三格全空。
     * 口径与 Windows 任务管理器一致：`PCI 总线 2、设备 0、功能 0`。
     */
    fun pciLocationText(bus: Int?, device: Int?, function: Int?): String {

        if (bus == null || device == null || function == null) return UNSUPPORTED_TEXT

        return AppStrings.s(R.string.data_pci_location, bus, device, function)
    }
}
