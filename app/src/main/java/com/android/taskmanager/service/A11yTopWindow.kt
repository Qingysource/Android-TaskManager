/**
 * ============================================================
 * 无障碍置顶窗口：权限判定 + 建窗用的服务
 * ============================================================
 *
 * 这个文件做三件事：
 * 1. [A11yTopWindow.isEnabled] 判断"本应用的无障碍服务是否已被用户启用"——
 *    设置页勾「使用无障碍权限置顶」、以及「置于顶层」判豁免，都只认这一个答案；
 * 2. [A11yTopWindowService] 一个最小可授权的无障碍服务：系统里必须先有这一项，
 *    用户才能去无障碍设置页把开关打开（没有声明 Service，就没有"权限"这一说）；
 * 3. 记住"当前连着的服务实例"：TYPE_ACCESSIBILITY_OVERLAY 窗口必须由它自己
 *    交给 WindowManager 去 addView（见 UIService 建窗那段），窗口内容与普通浮窗完全一样。
 *
 * 刻意不读屏幕内容：onAccessibilityEvent 空实现，canRetrieveWindowContent=false
 * （配置见 res/xml/a11y_top_window.xml）。顶窗口是纯展示，不需要别人的界面数据。
 *
 * 权限口径（与 SYSTEM_ALERT_WINDOW 的差别）：
 * - 悬浮窗权限：Settings.canDrawOverlays()，系统页是"显示在其他应用上层"；
 * - 无障碍权限：用户必须在系统无障碍设置里手动启用本服务，应用自己申请不了；
 *   两者都没有运行时申请接口，只能引导用户去系统页。
 *
 * 撤销时机：
 * 用户可能在设置页把开关关掉、或卸载/停用服务——系统同样不会通知应用，
 * 所以 UIService 侧要巡检（见 permissionWatchdog），设置页可见期间也要复核。
 * 服务被系统解绑时会主动回调 UIService（onAccessibilityWindowGone），当场收窗 + 关设置。
 */
package com.android.taskmanager.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍权限的唯一判定入口（设置页 / 存储层 / UIService 都走这里）。
 *
 * 为什么不缓存结果：用户随时可能在系统页里开关，缓存只会制造"看起来开着、其实没开"的错位。
 * 查询本身很轻（一次 binder 调用或一次 Secure 读），这几处调用频率都不高，不值得缓存。
 */
object A11yTopWindow {

    private const val TAG = "A11yTopWindow"

    /** 本应用无障碍服务的完整组件名（包名 + 类名），用于匹配系统返回的启用列表。 */
    fun componentName(context: Context): String =
        context.packageName + "/" + A11yTopWindowService::class.java.name

    /**
     * 无障碍服务是否已被用户启用。
     *
     * 两条判据，任意一条成立即视为"有权限"：
     * 1. AccessibilityManager 的已启用服务列表（系统真正认的服务）；
     * 2. Settings.Secure 里的 enabled_accessibility_services 字符串
     *    （刚打开开关、服务还没连上时，第一条可能来不及反映，第二条更早可见）。
     *
     * 查询异常一律按"没有权限"处理：宁可让用户再进一次设置页，
     * 也不要在没权限时假装有（那会让「置于顶层」错误地跳过悬浮窗权限检查）。
     */
    fun isEnabled(context: Context): Boolean {
        val app = context.applicationContext

        // 0. 全局无障碍总开关关着时，服务不可能在跑：Secure 里可能还留着本应用的条目，
        //    不能只凭那一行字符串就当成"有权限"（否则会错误地豁免悬浮窗权限检查）。
        if (isMasterSwitchOff(app)) return false

        if (queryFromManager(app)) return true

        return queryFromSecure(app)
    }

    /** 全局无障碍开关是否明确处于"关闭"状态（查询异常时按"未知"处理，交给后面两条判据）。 */
    private fun isMasterSwitchOff(app: Context): Boolean = try {
        val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

        manager != null && !manager.isEnabled
    } catch (e: Exception) {
        Log.w(TAG, "读取无障碍总开关失败：${e.javaClass.simpleName}")

        false
    }

    /** 跳系统无障碍设置页（没有更细的"直达本服务"公开入口，统一落到列表页）。 */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun queryFromManager(app: Context): Boolean = try {
        val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

        val enabled = manager?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )

        enabled?.any { info ->
            val serviceInfo = info?.resolveInfo?.serviceInfo ?: return@any false

            serviceInfo.packageName == app.packageName &&
                serviceInfo.name.endsWith(A11yTopWindowService::class.java.simpleName)
        } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "读取已启用无障碍服务失败：${e.javaClass.simpleName}")

        false
    }

    private fun queryFromSecure(app: Context): Boolean = try {
        val flat = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected = componentName(app)

        flat.split(':').any { it.equals(expected, ignoreCase = true) }
    } catch (e: Exception) {
        Log.w(TAG, "读取无障碍服务清单失败：${e.javaClass.simpleName}")

        false
    }
}

/**
 * 最小的无障碍服务：只为"能被授权 + 能被用来建顶窗口"而存在。
 *
 * 它做两件事：
 * 1. 提供建窗所需的 Context：TYPE_ACCESSIBILITY_OVERLAY 窗口必须由无障碍服务自己
 *    交给 WindowManager，所以这里记住当前连着的实例（[instanceOrNull]），
 *    UIService 建无障碍窗口时用它构造 OverlayHost；
 * 2. 连接/断开时通知 UIService：连上了可以补建上次没建成的窗口，断开则立刻收窗并关设置。
 *
 * 刻意不做的事：
 * - 不收无障碍事件的内容（canRetrieveWindowContent=false，配置见 res/xml/a11y_top_window.xml）；
 * - onAccessibilityEvent 空实现：顶窗口是纯展示，不需要读别人的界面。
 */
class A11yTopWindowService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yTopWindowService"

        /**
         * 当前连着的服务实例（系统同一时刻只会绑定一个）。
         *
         * 为什么会短暂为 null：用户刚在系统里打开开关、或系统重启后还没绑定时。
         * 建窗方拿到 null 就不要硬试，等服务回调 onServiceConnected 再补。
         */
        @Volatile
        private var active: A11yTopWindowService? = null

        /** 当前连着的服务实例；没连上时返回 null。 */
        fun instanceOrNull(): A11yTopWindowService? = active
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        active = this

        Log.i(TAG, "无障碍服务已连接：可以用来建置顶窗口")

        // 通知数据中枢：如果设置要求无障碍置顶，而上次启动时服务还没起来，这里补建一次
        UIService.instanceOrNull()?.onAccessibilityWindowReady()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本服务不读屏幕内容：事件一律忽略
    }

    override fun onInterrupt() {
        // 系统要求实现的空方法：没有常驻动作可中断
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "无障碍服务已断开（用户撤销或系统停用）")

        active = null

        // 断开即收窗 + 关设置：系统的窗口可能已经被一起移除，但"设置说开着"的状态必须同步掉
        UIService.instanceOrNull()?.onAccessibilityWindowGone()

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        active = null

        super.onDestroy()
    }
}

