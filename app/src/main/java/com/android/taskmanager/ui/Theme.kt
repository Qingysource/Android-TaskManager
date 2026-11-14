/**
 * ============================================================
 * 应用主题（配色装配）
 * ============================================================
 *
 * 职责：把设置页里"应用主题"这一项翻译成一个 Compose 的 MaterialTheme。
 * 只做"选颜色"，不做业务：谁想改配色，改这里；页面只管用 MaterialTheme.colorScheme。
 *
 * 三条规则（按优先级从高到低）：
 * 1. 设置 = "使用系统设置" → 跟随系统的深 / 浅色（isSystemInDarkTheme），
 *    并默认使用系统的动态取色（Monet）；系统不支持动态取色时，静默回落到项目默认色板，
 *    **不给任何提示**（用户没要求被告知，弹提示只会干扰）。
 * 2. 设置 = "浅色" / "深色" → 强制该模式，但动态取色策略不变（仍然是"能用就用"）。
 * 3. 设置值非法（配置文件被手改坏）→ 当成"使用系统设置"，不让一个脏值把界面搞成空白。
 *
 * 为什么主题状态放在一个进程级可变状态里，而不是塞进 Activity：
 * - 主界面（MainActivity）和浮窗（UIService 里的 ComposeView）是同一进程里的两处界面，
 *   主题切换必须同时生效，否则"点一下深色，浮窗还是白的"；
 * - 设置页改值走的是 UIService.applyTheme()（Service 拿不到 Activity），
 *   写这个状态就等于同时通知了两处，两边各自重组，不需要重启应用。
 *
 * 回退方式：把两处调用点（MainActivity 的 setContent、OverlayContent 的根）换回 MaterialTheme{ }
 * 即可回到"永远是浅色"的旧行为，本文件可整篇删除。
 */
package com.android.taskmanager

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

import androidx.core.view.WindowCompat

// ============================================================
// 主题取值（与 SettingsCatalog.THEMES 一一对应）
// ============================================================

/** "使用系统设置"：跟随系统的深浅色，并优先使用动态取色。 */
const val THEME_MODE_SYSTEM: String = "使用系统设置"

/** "浅色"：强制浅色。 */
const val THEME_MODE_LIGHT: String = "浅色"

/** "深色"：强制深色。 */
const val THEME_MODE_DARK: String = "深色"

/**
 * 主题模式的进程内共享状态。
 *
 * 写入口只有两个：
 * - MainActivity.onCreate（启动时用设置快照初始化一次）；
 * - UIService.applyTheme（设置页改值时实时切换）。
 *
 * 读入口是所有 Compose 界面：TaskManagerTheme 默认读这里的值，
 * 值一变，读它的界面自动重组——不需要重启应用，也不需要额外的广播。
 */
internal object ThemeState {

    /** 当前主题模式（取值见上方三个常量）。 */
    val mode = mutableStateOf(THEME_MODE_SYSTEM)
}

/**
 * 把任意字符串收敛成三个合法主题模式之一。
 *
 * 非法值（空串 / 手改配置写的别的词）一律当"使用系统设置"：
 * 主题是纯外观项，不值得为它报错或阻断启动。
 */
internal fun normalizeThemeMode(raw: String?): String {
    return when (raw) {
        THEME_MODE_LIGHT -> THEME_MODE_LIGHT

        THEME_MODE_DARK -> THEME_MODE_DARK

        else -> THEME_MODE_SYSTEM
    }
}

// ============================================================
// 项目默认色板
// ============================================================

/**
 * 项目默认浅色色板。
 *
 * 直接取 Material 3 基线色板：这是本应用此前一直在用的观感，
 * 在没有动态取色的设备上必须"看起来跟以前一样"，不能因为加了主题支持就换个颜色。
 */
private val ProjectLightColorScheme: ColorScheme = lightColorScheme()

/**
 * 项目默认深色色板（同样是 M3 基线）。
 *
 * 只在"深色模式 + 设备不支持动态取色"时用到。
 */
private val ProjectDarkColorScheme: ColorScheme = darkColorScheme()

// ============================================================
// 装配
// ============================================================

/**
 * 应用主题：包住所有界面内容，向下的页面就能直接用 MaterialTheme.colorScheme。
 *
 * @param mode 主题模式；默认读 [ThemeState.mode]，所以调用方通常不用传参
 * @param content 被着色的界面
 */
@Composable
internal fun TaskManagerTheme(
    mode: String = ThemeState.mode.value,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val darkTheme = when (normalizeThemeMode(mode)) {
        THEME_MODE_DARK -> true

        THEME_MODE_LIGHT -> false

        // "使用系统设置"：跟随系统的深 / 浅色开关
        else -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = resolveColorScheme(context = context, darkTheme = darkTheme),

        content = content
    )

    // 状态栏 / 导航栏的图标明暗跟着主题走：深色主题要浅色图标，否则图标会和背景糊在一起。
    // 浮窗里的 ComposeView 没有 Activity 窗口，这里自然跳过（浮窗本来就没有状态栏）。
    SyncSystemBarAppearance(darkTheme = darkTheme)
}

/**
 * 把系统栏图标的明暗对齐当前主题。
 *
 * 只改"图标是深还是浅"，不动栏的颜色与布局，所以不会影响现有的边距与横竖屏表现。
 * 任何 ROM 抛异常都吞掉：这是纯外观细节，不能因为它让界面崩。
 */
@Composable
private fun SyncSystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current

    // Android Studio 的预览没有真实窗口，跳过
    if (view.isInEditMode) {
        return
    }

    val window = view.context.findActivity()?.window ?: return

    SideEffect {
        runCatching {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme

                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
}

/** 从一个 Context 往上找 Activity（Compose 里的 Context 常常是包裹过的）。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this

    while (current != null) {
        if (current is Activity) {
            return current
        }

        current = (current as? ContextWrapper)?.baseContext
    }

    return null
}

/**
 * 决定这一帧到底用哪套颜色：**能动态取色就用动态取色，不能就回落项目默认色板**。
 *
 * 动态取色（Monet）从 Android 12（API 31）开始提供，颜色来自用户壁纸。
 * 这里刻意"静默"：
 * - 不支持（低版本）→ 直接用项目默认色板，不提示、不降级说明；
 * - 支持但取色失败（个别 ROM 抛异常）→ runCatching 吞掉，同样回落到项目默认色板。
 *   一条纯外观的取色失败，绝不能把界面搞崩。
 */
private fun resolveColorScheme(context: Context, darkTheme: Boolean): ColorScheme {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamic = runCatching {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }.getOrNull()

        if (dynamic != null) {
            return dynamic
        }
    }

    return if (darkTheme) ProjectDarkColorScheme else ProjectLightColorScheme
}
