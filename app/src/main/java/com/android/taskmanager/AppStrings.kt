package com.android.taskmanager

import android.content.Context
import androidx.annotation.StringRes

/**
 * 全局字符串入口（给"拿不到 Context"的层次用）。
 *
 * 为什么需要它：
 *   - Compose 里有 stringResource()，但本项目的文案大量在**普通函数**里拼装
 *     （性能页的明细成对列表、数据层的 displayText、服务里的列标题…），这些地方
 *     既不是 @Composable、也没有 Context 参数；
 *   - 把它们全部改成 @Composable 或逐层传 Resources 会把签名扩散到上百个调用点，
 *     换来的只是"少一个全局对象"。
 *
 * 所以这里只做一件事：持有 Application Context，把 R.string.* 取成当前系统语言的文本。
 * 语言切换走 Android 原生机制（系统语言变了 → 资源重选 → Activity 重建），本类不缓存文本，
 * 每次调用都现取，因此不会读到旧语言。
 *
 * 约定：
 *   - 默认语言（res/values）是英文，中文在 res/values-zh；其它语言自动回落英文；
 *   - 格式化一律走 `%1$s` 这类位置参数，别在代码里用 + 拼句子；
 *   - 本类**不提供**改语言的能力：应用语言永远跟随系统，设置页不给单独的语言开关。
 */
object AppStrings {

    private var appContext: Context? = null

    /** 只由 [TaskManagerApp.onCreate] 调一次；迟到的调用只会覆盖成同一个 Application Context。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 取一条资源文本；[args] 对应 XML 里的 %1$s / %2$d 等位置参数。 */
    fun s(@StringRes id: Int, vararg args: Any): String {
        val context = appContext ?: error("AppStrings 未初始化：Application 没走 TaskManagerApp")

        return if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *args)
        }
    }
}
