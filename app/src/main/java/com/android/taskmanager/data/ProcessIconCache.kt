/**
 * ============================================================
 * 进程图标缓存（详细信息页名称前的图标槽）
 * ============================================================
 *
 * 这里只做两件事：
 * 1. 把 [AndroidService.applicationIcon] 给出的 Drawable 转成 Compose 能画的 [ImageBitmap]；
 * 2. 按**包名**缓存住，同一包的多个进程行共用一张图（不然一屏几十行会反复解码）。
 *
 * 为什么按包名而不是按 pid：图标是"应用"的属性，进程只是它的一个运行实例；
 * 同一个包起 5 个进程不该解码 5 次，也不该出现 5 张大小/边缘不一样的图。
 *
 * 线程约定：[load] 可能过一次 shell（拿 APK 路径）并解码资源，**必须在 IO 线程调用**；
 * [cached] / [isKnown] 是纯内存读，可以在组合里直接调。
 */
package com.android.taskmanager.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

object ProcessIconCache {

    /** 解码成一个多大的位图（图标槽只有 20dp，给 2~3 倍余量足够清晰，又不吃内存）。 */
    private const val ICON_PIXELS = 96

    private val lock = Any()

    /** 包名 -> 图标；value 为 null 表示"查过，这个包没有可用图标"（不重复查）。 */
    private val icons = HashMap<String, ImageBitmap?>()

    /** 缓存上限：超过就整表清空（图标很小，但没必要无限长）。 */
    private const val LIMIT = 256

    /** 已缓存的图标；没查过时返回 null（配合 [isKnown] 区分"没查"与"没有"）。 */
    fun cached(pkg: String): ImageBitmap? = synchronized(lock) { icons[pkg] }

    /** 这个包是不是已经查过了（查过没有图标也算"知道"）。 */
    fun isKnown(pkg: String): Boolean = synchronized(lock) { icons.containsKey(pkg) }

    /** 阻塞加载一个包的图标；查不到返回 null。**只在 IO 线程调**。 */
    fun load(pkg: String): ImageBitmap? {

        synchronized(lock) { if (icons.containsKey(pkg)) return icons[pkg] }

        val bitmap = try {

            drawableToImageBitmap(AndroidService.instanceOrNull()?.applicationIcon(pkg))

        } catch (e: Throwable) {

            null
        }

        synchronized(lock) {

            if (icons.size >= LIMIT) icons.clear()

            icons[pkg] = bitmap
        }

        return bitmap
    }

    /** Drawable -> 定尺位图：按 intrinsic 尺寸画进一张 ARGB 位图，转成 ImageBitmap。 */
    private fun drawableToImageBitmap(drawable: Drawable?): ImageBitmap? {

        if (drawable == null) return null

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_PIXELS
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_PIXELS

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bitmap)

        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)

        return bitmap.asImageBitmap()
    }
}
