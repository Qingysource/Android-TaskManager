package com.android.taskmanager.manager

import android.content.Context

import com.android.taskmanager.service.UIService

/**
 * 页面管理器注册表 —— Screen 拿页面管理器的唯一入口。
 *
 * 生命周期：管理器是**进程级**的（不是跟随某个 Activity 的），因为它们的私有数据
 * （历史点、列布局、用户选择）必须活过横竖屏切换、浮窗、切页，甚至活过 UIService 重建。
 *
 * 谁创建：第一次有人要它时懒创建（Screen 或 UIService 都行），
 *        构造只依赖 applicationContext，不依赖服务是否已经起来。
 * 谁接服务：UIService.onCreate 调 [attach]，把自己的快照发布接口接上去；
 *          UIService.onDestroy 调 [detach]，只摘订阅，数据留在管理器里。
 *
 * 这一层不持有任何 Compose 布局、不 import 任何 Screen 文件 ——
 * 依赖方向永远是 Screen -> Manager -> UIService，反过来不存在。
 */
object PageManagers {

    @Volatile
    private var performance: PerformanceManager? = null

    /** 性能页管理器（懒创建，幂等）。 */
    fun performance(context: Context): PerformanceManager =
        performance ?: synchronized(this) {

            performance ?: PerformanceManager(context.applicationContext).also { performance = it }
        }

    /** UIService 创建时调用：把全局快照的订阅接上。 */
    internal fun attach(service: UIService) {
        performance(service).attach(service)

        // 后续页面管理器（详细信息 / 进程 / 用户 / 启动应用）在各自落地时同样在这里 attach。
        // 现在它们还没有私有数据要订阅，所以不建空壳对象。
    }

    /** UIService 销毁时调用：只摘订阅，不丢已经积累的数据。 */
    internal fun detach(service: UIService) {
        performance?.detach(service)
    }
}
