/**
 * ============================================================
 * 浮窗宿主（OVERLAY v1 · 实验功能）
 * ============================================================
 *
 * ！！回退方式：删除本文件 + OverlayContent.kt + IOverlayApi + UIService 的 ⑦ 分区
 * ！！          + Manifest 里的 SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE* / specialUse，
 * ！！          并把设置页"置于顶层"恢复成纯持久化（去掉权限对话框与重启提示）。
 *
 * 做了一件事：把一个 Compose 界面塞进 WindowManager 的浮窗里。
 *
 * Compose 在浮窗里跑，需要宿主提供三个"Owner"（这是唯一麻烦的地方）：
 * - LifecycleOwner           Compose 的 recomposer 靠它启动/销毁；
 * - ViewModelStoreOwner      组件内部要 viewModel 时用；
 * - SavedStateRegistryOwner  页面里的 rememberSaveable 靠它做状态保存。
 * 三个都塞在下面的 ComposeOverlayOwner 里，生命周期由本类手动推进。
 *
 * 窗口参数（v1 定死，改这里就行）：
 * - 类型 TYPE_APPLICATION_OVERLAY（< 26 退化 TYPE_PHONE）；
 * - FLAG_NOT_FOCUSABLE｜FLAG_NOT_TOUCH_MODAL：
 *   触摸能收到、但不会把别的应用的键盘焦点抢走，且浮窗外的点击会穿透到下层应用；
 * - 近乎铺满屏幕但四边留空隙，空隙就是"还能点到系统和其他应用"的逃生口。
 */
package com.android.taskmanager.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner

import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

import kotlin.math.roundToInt

/**
 * 浮窗管理器：负责窗口的创建、移动、缩放与销毁。
 *
 * 注意：一个实例只持有一个浮窗；重复 [show] 不会叠加窗口。
 */
class OverlayHost(private val context: Context) {

    /** 浮窗的像素位置与尺寸（左上角原点，坐标为屏幕坐标）。 */
    data class Bounds(var x: Int, var y: Int, var width: Int, var height: Int)

    /** 交给浮窗内容的动作集合：内容里的把手指针调这些。 */
    class Actions(
        val moveBy: (Float, Float) -> Unit,
        val resizeBy: (Float, Float) -> Unit,
        val close: () -> Unit
    )

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * 当前这一只窗口的 Compose 宿主；[hide] 后置空。
     *
     * 必须"一次窗口一个宿主"、绝不复用：宿主里的 SavedStateRegistry 只允许
     * performRestore 一次（第二次调用直接抛 IllegalStateException），
     * LifecycleRegistry 也不允许从 DESTROYED 回到 RESUMED。
     * v1 最初复用一个实例，结果就是"第一次建窗正常、关掉再开必失败"，
     * 失败后又走兜底启动，看起来像"再点置顶就闪回进程页"。
     */
    private var owner: ComposeOverlayOwner? = null

    private var view: ComposeView? = null

    private var windowParams: WindowManager.LayoutParams? = null

    /** 浮窗当前是否已经挂在 WindowManager 上。 */
    val isShowing: Boolean
        get() = view != null

    // ============================================================
    // 尺寸计算
    // ============================================================

    /**
     * v1 默认尺寸：近乎铺满屏幕，四边留白。
     *
     * 留白是必须的：浮窗盖满整屏又没有实体返回键时，用户会被困在里面出不来；
     * 留出边缝后，点空白处能落到下层应用 / 系统手势区。
     */
    fun defaultBounds(): Bounds {
        val (screenWidth, screenHeight) = screenSize()

        val edge = dp(10)
        val topGap = dp(48)     // 躲开状态栏
        val bottomGap = dp(36)  // 躲开导航栏 / 手势区

        return Bounds(
            x = edge,
            y = topGap,
            width = (screenWidth - edge * 2).coerceAtLeast(dp(320)),
            height = (screenHeight - topGap - bottomGap).coerceAtLeast(dp(240))
        )
    }

    // ============================================================
    // 创建 / 销毁
    // ============================================================

    /**
     * 显示浮窗。
     *
     * @param bounds 初始位置与尺寸（一般用 [defaultBounds]）
     * @param onClose 内容里点"退出"时回调（真正的退出逻辑由 UIService 决定）
     * @param content 浮窗内容；参数 [Actions] 提供拖动 / 缩放 / 关闭
     * @return 是否成功挂上窗口（没有权限或系统拒绝时为 false）
     */
    fun show(
        bounds: Bounds,
        onClose: () -> Unit,
        content: @Composable (Actions) -> Unit
    ): Boolean {
        if (isShowing) return true

        val params = WindowManager.LayoutParams(
            bounds.width,
            bounds.height,
            overlayWindowType(),
            // 能收触摸、但不抢焦点；浮窗外的点击会穿透给下层应用
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.x
            y = bounds.y
        }

        val actions = Actions(
            moveBy = ::moveBy,
            resizeBy = ::resizeBy,
            close = onClose
        )

        // 这一只窗口专用的宿主（不复用上一只的，见 owner 字段的说明）
        val host = ComposeOverlayOwner()

        val composeView = ComposeView(context).apply {
            // 先给窗口刷一层底色：Compose 的首帧要等 recomposer 起来，
            // 中间这段空窗期在深色主题下会露出黑底（看起来像"进去了但什么都没显示"）。
            // 有底色之后，这段时间至少是与应用一致的背景色，首帧一到就被 Surface 盖住。
            setBackgroundColor(windowBackgroundColor())

            // 窗口被移除时把 composition 一起销毁，避免 recomposer 泄漏
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            // 三件套挂到 ViewTree 上，Compose 才认这个宿主
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)

            setContent { content(actions) }

            // 首帧绘制日志：出"黑屏"时用它区分"窗没建上"和"窗建上了但一直没画"
            viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    Log.i(TAG, "浮窗首帧开始绘制")

                    viewTreeObserver.removeOnPreDrawListener(this)

                    return true
                }
            })
        }

        return try {
            host.attach()

            windowManager.addView(composeView, params)

            owner = host
            view = composeView
            windowParams = params

            true
        } catch (e: Exception) {
            // 权限被撤销 / 系统拒绝时 addView 会抛，收掉状态后由调用方提示用户。
            // 这条日志是"建窗失败"最直接的证据（UIService 只看到 Boolean）。
            Log.e(TAG, "建窗失败：addView 抛出异常", e)

            runCatching { windowManager.removeView(composeView) }

            host.detach()

            owner = null
            view = null
            windowParams = null

            false
        }
    }

    /** 移除浮窗（幂等）：视图、宿主、窗口参数一起收掉，下次 [show] 从头再来。 */
    fun hide() {
        val current = view

        if (current != null) runCatching { windowManager.removeView(current) }

        owner?.detach()
        owner = null

        view = null
        windowParams = null
    }

    // ============================================================
    // 拖动 / 缩放
    // ============================================================

    /**
     * 平移窗口。
     *
     * 边界不夹死：允许一部分移出屏幕（贴边时手感更自然），但至少留 80dp 可见，
     * 保证窗口永远不会"拖丢了找不回来"。
     */
    private fun moveBy(dx: Float, dy: Float) {
        val params = windowParams ?: return
        val (screenWidth, screenHeight) = screenSize()

        val visible = dp(80)

        params.x = (params.x + dx.roundToInt()).coerceIn(visible - params.width, screenWidth - visible)
        params.y = (params.y + dy.roundToInt()).coerceIn(0, screenHeight - visible)

        applyLayout()
    }

    /**
     * 缩放窗口。
     *
     * 最小 240×180dp（再小内容就挤成一团），最大不超过屏幕；
     * 右下角、右边缘、下边缘三个把手都走这里，方向由调用方用 dw / dh 区分。
     */
    private fun resizeBy(dw: Float, dh: Float) {
        val params = windowParams ?: return
        val (screenWidth, screenHeight) = screenSize()

        params.width = (params.width + dw.roundToInt()).coerceIn(dp(240), screenWidth)
        params.height = (params.height + dh.roundToInt()).coerceIn(dp(180), screenHeight)

        // 缩放后可能超出屏幕右下角，顺手把位置夹回来
        params.x = params.x.coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))

        applyLayout()
    }

    /** 把最新的 LayoutParams 推给 WindowManager。 */
    private fun applyLayout() {
        val view = this.view ?: return
        val params = this.windowParams ?: return

        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 浮窗 Window 类型：26 起统一用 TYPE_APPLICATION_OVERLAY，之前只有 TYPE_PHONE。 */
    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /** 屏幕的真实像素尺寸（含状态栏与导航栏）。 */
    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds

            bounds.width() to bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            metrics.widthPixels to metrics.heightPixels
        }
    }

    /**
     * 窗口底色的兜底值：取主题里的 windowBackground，取不到就用白色。
     *
     * 只用于 Compose 首帧之前的空窗期，真正的界面配色由内容里的 Surface 决定。
     */
    private fun windowBackgroundColor(): Int {
        val value = android.util.TypedValue()

        val resolved = context.theme.resolveAttribute(android.R.attr.windowBackground, value, true)

        return if (
            resolved &&
            value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
            value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT
        ) {
            value.data
        } else {
            android.graphics.Color.WHITE
        }
    }

    /** dp -> px。 */
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {

        /** 与 UIService 的浮窗日志同一个 tag，方便一条命令看全流程。 */
        const val TAG = "TMOverlay"
    }
}

/**
 * 浮窗里的 Compose 宿主。
 *
 * 只做一件事：把三个 Owner 接口实现出来，并由 [OverlayHost] 手动推进生命周期。
 * 浮窗没有 Activity，所以 CREATED / RESUMED 都得我们自己发。
 */
private class ComposeOverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** 窗口挂上后调用：恢复（空的）保存状态，然后把生命周期推到 RESUMED。 */
    fun attach() {
        savedStateController.performRestore(null)

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** 窗口移除后调用：推到 DESTROYED 并清掉 ViewModel，避免泄漏。 */
    fun detach() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        viewModelStore.clear()
    }
}
