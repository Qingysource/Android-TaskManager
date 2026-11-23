package com.android.taskmanager

import android.app.Application

/**
 * 应用入口对象：目前只负责把 Application Context 交给 [AppStrings]。
 *
 * 语言本身不需要在这里设置：Android 在进程/配置变化时就已经按系统语言
 * 选好了 res/values-<语言>，本类不调任何 setLocale / setApplicationLocales。
 * 应用语言 = 系统语言；系统是英文（或任何未翻译语言）就用 res/values 的英文，
 * 系统是中文就用 res/values-zh。设置页不提供单独的语言开关。
 */
class TaskManagerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        AppStrings.init(this)
    }
}
