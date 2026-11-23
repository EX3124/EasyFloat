package com.lzf.easyfloat.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import com.lzf.easyfloat.permission.rom.RomUtils
import kotlin.math.max
import kotlin.math.min

/**
 * @author: liuzhenfeng
 * @function: 屏幕显示相关工具类
 * @date: 2019-05-23  15:23
 */
object DisplayUtils {

    private const val TAG = "DisplayUtils--->"

    fun px2dp(context: Context, pxVal: Float): Int {
        val density = context.resources.displayMetrics.density
        return (pxVal / density + 0.5f).toInt()
    }

    fun dp2px(context: Context, dpVal: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dpVal * density + 0.5f).toInt()
    }

    fun px2sp(context: Context, pxValue: Float): Int {
        val fontScale = context.resources.displayMetrics.scaledDensity
        return (pxValue / fontScale + 0.5f).toInt()
    }

    fun sp2px(context: Context, spValue: Float): Int {
        val fontScale = context.resources.displayMetrics.scaledDensity
        return (spValue * fontScale + 0.5f).toInt()
    }

    /**
     * 获取屏幕宽度（显示宽度，横屏的时候可能会小于物理像素值）
     */
    fun getScreenWidth(context: Context): Int = getScreenWidth(context, getScreenSize(context))

    /**
     * 获取屏幕宽度（复用已经取得的屏幕尺寸，避免重复测量）
     */
    fun getScreenWidth(context: Context, point: Point): Int {
        // Android 12（API 31）起，屏幕尺寸改为按物理屏幕大小 + 旋转角度计算，横屏时 x 一定是长边
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 横屏时导航栏在屏幕侧边，需要减去导航栏的宽度，而不是高度
            return if (point.x > point.y) point.x - getNavigationBarCurrentWidth(context) else point.x
        }

        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        manager.defaultDisplay.getRealMetrics(metrics)
        return if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            metrics.widthPixels
        } else {
            metrics.widthPixels - getNavigationBarCurrentHeight(context)
        }
    }

    /**
     * 获取屏幕高度（物理像素值的高度）
     */
    fun getScreenHeight(context: Context) = getScreenSize(context).y

    /**
     * 获取用于测量屏幕的 Display
     *
     * 必须取 Application 级别的 Display，不能用 Activity 的：
     * Activity 拿到的 Display 带着该 Activity 的兼容性调整（letterbox、固定旋转、尺寸兼容模式），
     * Display#getRotation 会被固定成 Activity 配置里的旋转角度（AOSP：mMayAdjustByFixedRotation），
     * Display#getRealSize、Display#getRealMetrics 也会按 Activity 配置的 maxBounds 返回尺寸
     * （AOSP：shouldReportMaxBounds）。而 Activity 退到后台后系统不会再重新配置它，
     * 于是横屏后读到的仍是竖屏方向，拖动边界一直停留在竖屏范围。
     * Application 的 Resources 没有这层覆盖，Display#getRotation、Display#getMode
     * 直接来自 DisplayManagerService，与 App 是否在前台无关。
     */
    private fun getDisplay(context: Context): Display? {
        val appContext = context.applicationContext ?: context
        val display = try {
            (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (e: Exception) {
            // 个别机型取 Display 时抛异常，交给下面的兜底
            null
        }
        if (display != null) return display
        return try {
            (appContext.getSystemService(Service.WINDOW_SERVICE) as WindowManager).defaultDisplay
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取屏幕宽高
     */
    fun getScreenSize(context: Context): Point {
        val display = getDisplay(context)
        // 极端情况下拿不到 Display，退回旧方式测量
        if (display == null) {
            val windowManager = context.getSystemService(Service.WINDOW_SERVICE) as WindowManager
            val point = Point()
            windowManager.defaultDisplay.getRealSize(point)
            return point
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return getScreenSizeOnS(display)
        }
        val point = Point()
        display.getRealSize(point)
        return point
    }

    /**
     * 获取屏幕宽高（Android 12 及以上）
     *
     * Android 12（API 31）起，Display#getRealSize、Display#getRealMetrics 的行为被调整为
     * 更接近 WindowManager#getMaximumWindowMetrics：返回的是当前 App 配置的 maxBounds，
     * 而不是整块屏幕的大小（AOSP：Display#shouldReportMaxBounds）。当 App 处于兼容模式、
     * 横竖屏被固定，或者退到后台后没有被重新配置时，这里拿到的仍然是旋转前的竖屏尺寸，
     * 于是横屏后的拖动边界一直停留在竖屏范围。
     *
     * 所以这里改用与 App 状态无关的数据来测量屏幕：
     * 1. Display#getMode 返回物理屏幕在自然方向下的宽高，不随旋转变化；
     * 2. Display#getRotation 返回当前屏幕的旋转角度（传入的 Display 是 Application 级别的，
     *    不会被固定成某个 Activity 的旋转角度）；
     * 两者结合即可得到屏幕当前方向的宽高，无论 App 在前台还是后台都成立。
     * 物理尺寸拿不到时，退回 getRealMetrics，并按旋转角度矫正宽高方向。
     *
     * 这里必须加 @SuppressLint("NewApi")：Build.VERSION.SDK_INT 的判断写在调用方 getScreenSize 里，
     * lint 的 NewApi 检查不做跨方法推断，否则 Display#getMode（API 23）、Display.Mode 的
     * physicalWidth/physicalHeight（API 23）都会被判为低于 minSdk(17) 的非法调用。
     * 不能用 android.annotation.RequiresApi：该注解不在公开 SDK 的编译 classpath 中（编译报
     * Unresolved reference），而 androidx.annotation.RequiresApi 需要额外依赖，本库并未引入。
     * 运行期安全性由唯一调用点 getScreenSize() 中的 SDK_INT >= S 判断保证。
     */
    @SuppressLint("NewApi")
    private fun getScreenSizeOnS(display: Display): Point {
        val sideways = isRotationSideways(display)
        val mode = display.mode
        val physicalWidth = mode?.physicalWidth ?: 0
        val physicalHeight = mode?.physicalHeight ?: 0
        // 物理尺寸是自然方向下的宽高，旋转 90°/270° 时需要交换，才能与系统浮窗的坐标系一致
        if (physicalWidth > 0 && physicalHeight > 0) {
            return if (sideways) Point(physicalHeight, physicalWidth)
            else Point(physicalWidth, physicalHeight)
        }

        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return Point(width, height)
        // 宽高方向与旋转角度一致，说明拿到的就是当前方向的尺寸
        if (sideways != (width < height)) return Point(width, height)
        // 方向不一致：拿到的是旋转前的旧数据，按长边/短边重新组合
        val longSide = max(width, height)
        val shortSide = min(width, height)
        return if (sideways) Point(longSide, shortSide) else Point(shortSide, longSide)
    }

    /**
     * 屏幕是否已旋转 90°/270°（横屏方向）
     */
    private fun isRotationSideways(display: Display): Boolean {
        val rotation = display.rotation
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }

    /**
     * 获取状态栏高度
     */
    fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resources = context.resources
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) result = resources.getDimensionPixelSize(resourceId)
        return result
    }

    fun statusBarHeight(view: View) = getStatusBarHeight(view.context.applicationContext)

    /**
     * 获取导航栏真实的高度（可能未显示）
     */
    fun getNavigationBarHeight(context: Context): Int {
        var result = 0
        val resources = context.resources
        val resourceId =
            resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) result = resources.getDimensionPixelSize(resourceId)
        return result
    }

    /**
     * 获取导航栏当前的高度
     */
    fun getNavigationBarCurrentHeight(context: Context) =
        if (hasNavigationBar(context)) getNavigationBarHeight(context) else 0

    /**
     * 获取导航栏当前的宽度（横屏时导航栏在屏幕侧边，占据的是宽度）
     */
    fun getNavigationBarCurrentWidth(context: Context) =
        if (hasNavigationBar(context)) getNavigationBarWidth(context) else 0

    /**
     * 获取导航栏真实的高度（横屏侧边时即为厚度）
     */
    private fun getNavigationBarWidth(context: Context): Int {
        val resources = context.resources
        val resourceId = resources.getIdentifier("navigation_bar_width", "dimen", "android")
        // 部分机型没有 navigation_bar_width，横屏时导航栏的厚度与竖屏高度基本一致
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId)
        else getNavigationBarHeight(context)
    }

    /**
     * 判断虚拟导航栏是否显示
     *
     * @param context 上下文对象
     * @return true(显示虚拟导航栏)，false(不显示或不支持虚拟导航栏)
     */
    fun hasNavigationBar(context: Context) = when {
        getNavigationBarHeight(context) == 0 -> false
        RomUtils.checkIsHuaweiRom() && isHuaWeiHideNav(context) -> false
        RomUtils.checkIsMiuiRom() && isMiuiFullScreen(context) -> false
        RomUtils.checkIsVivoRom() && isVivoFullScreen(context) -> false
        else -> isHasNavigationBar(context)
    }

    /**
     * 不包含导航栏的有效高度（没有导航栏，或者已去除导航栏的高度）
     */
    fun rejectedNavHeight(context: Context): Int {
        val point = getScreenSize(context)
        if (point.x > point.y) return point.y
        return point.y - getNavigationBarCurrentHeight(context)
    }

    /**
     * 华为手机是否隐藏了虚拟导航栏
     * @return true 表示隐藏了，false 表示未隐藏
     */
    private fun isHuaWeiHideNav(context: Context) =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Settings.System.getInt(context.contentResolver, "navigationbar_is_min", 0)
        } else {
            Settings.Global.getInt(context.contentResolver, "navigationbar_is_min", 0)
        } != 0

    /**
     * 小米手机是否开启手势操作
     * @return false 表示使用的是虚拟导航键(NavigationBar)， true 表示使用的是手势， 默认是false
     */
    private fun isMiuiFullScreen(context: Context) =
        Settings.Global.getInt(context.contentResolver, "force_fsg_nav_bar", 0) != 0

    /**
     * Vivo手机是否开启手势操作
     * @return false 表示使用的是虚拟导航键(NavigationBar)， true 表示使用的是手势， 默认是false
     */
    private fun isVivoFullScreen(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, "navigation_gesture_on", 0) != 0

    /**
     * 其他手机根据屏幕真实高度与显示高度是否相同来判断
     */
    private fun isHasNavigationBar(context: Context): Boolean {
        val windowManager: WindowManager =
            context.getSystemService(Service.WINDOW_SERVICE) as WindowManager
        val d = windowManager.defaultDisplay

        val realDisplayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            d.getRealMetrics(realDisplayMetrics)
        }
        val realHeight = realDisplayMetrics.heightPixels
        val realWidth = realDisplayMetrics.widthPixels

        val displayMetrics = DisplayMetrics()
        d.getMetrics(displayMetrics)
        val displayHeight = displayMetrics.heightPixels
        val displayWidth = displayMetrics.widthPixels

        // 部分无良厂商的手势操作，显示高度 + 导航栏高度，竟然大于物理高度，对于这种情况，直接默认未启用导航栏
        if (displayHeight + getNavigationBarHeight(context) > realHeight) return false

        return realWidth - displayWidth > 0 || realHeight - displayHeight > 0
    }

}