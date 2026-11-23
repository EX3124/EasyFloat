package com.lzf.easyfloat.core

import android.animation.Animator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.widget.EditText
import com.lzf.easyfloat.WARN_ACTIVITY_NULL
import com.lzf.easyfloat.anim.AnimatorManager
import com.lzf.easyfloat.data.FloatConfig
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.interfaces.OnFloatTouchListener
import com.lzf.easyfloat.utils.DisplayUtils
import com.lzf.easyfloat.utils.InputMethodUtils
import com.lzf.easyfloat.utils.LifecycleUtils
import com.lzf.easyfloat.utils.Logger
import com.lzf.easyfloat.widget.ParentFrameLayout

/**
 * @author: Liuzhenfeng
 * @date: 12/1/20  23:40
 * @Description: 负责具体悬浮窗的创建管理
 */
internal class FloatingWindowHelper(val context: Context, var config: FloatConfig) {

    interface CreateCallback {
        fun onCreate(success: Boolean)
    }

    lateinit var windowManager: WindowManager
    lateinit var params: WindowManager.LayoutParams
    var frameLayout: ParentFrameLayout? = null
    private lateinit var touchUtils: TouchUtils
    private var enterAnimator: Animator? = null
    private var lastLayoutMeasureWidth = -1
    private var lastLayoutMeasureHeight = -1

    // 上一次的深色模式状态：窗口回调和 Application 回调可能重复送达，用它去重
    private var lastNightMode = -1
    // Application 级配置变化监听：浮窗常驻时窗口自身不一定能收到 onConfigurationChanged
    private var appConfigCallbacks: ComponentCallbacks? = null

    // 屏幕旋转/尺寸变化监听，用于旋转后刷新拖动边界
    private var displayListener: DisplayManager.DisplayListener? = null
    // 屏幕变化后的边界刷新：只在边界真正按当前屏幕更新成功后结束，否则继续重试
    private var screenCheckScheduled = false
    private var screenCheckAttempt = 0
    // 拖拽期间不能刷新边界（会把浮窗拉走），先记录下来，手指抬起后补做
    private var refreshAfterGesture = false
    private val screenCheckRunnable = Runnable { checkScreenSizeChanged() }
    // 屏幕变化后的重试间隔（ms），旋转时系统完成窗口重新布局的时机并不固定
    private val screenCheckDelays = longArrayOf(0, 16, 32, 64, 120, 200, 320, 500)

    fun createWindow(callback: CreateCallback) {
        // 如果在onCreate创建单页面浮窗，会存在获取windowToken为空的情况，需要异步创建
        if (config.showPattern == ShowPattern.CURRENT_ACTIVITY && getToken() == null) {
            getActivity()?.findViewById<View>(android.R.id.content)?.run {
                post { callback.onCreate(createWindowInner()) }
                return
            }
            callback.onCreate(false)
            config.callbacks?.createdResult(false, WARN_ACTIVITY_NULL, null)
            config.floatCallbacks?.builder?.createdResult?.invoke(false, WARN_ACTIVITY_NULL, null)
        } else callback.onCreate(createWindowInner())
    }

    private fun createWindowInner(): Boolean = try {
        touchUtils = TouchUtils(context, config)
        // 拖拽期间被推迟的边界刷新，在手指抬起后补做
        touchUtils.gestureEndCallback = { onGestureEnd() }
        lastNightMode = nightModeOf(context.resources.configuration)
        initParams()
        addView()
        config.isShow = true
        true
    } catch (e: Exception) {
        config.callbacks?.createdResult(false, "$e", null)
        config.floatCallbacks?.builder?.createdResult?.invoke(false, "$e", null)
        false
    }

    @SuppressLint("NewApi")
    private fun initParams() {
        windowManager = context.getSystemService(Service.WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams().apply {
            if (config.showPattern == ShowPattern.CURRENT_ACTIVITY) {
                // 设置窗口类型为应用子窗口，和PopupWindow同类型
                type = TYPE_APPLICATION_PANEL
                // 子窗口必须和创建它的Activity的windowToken绑定
                token = getToken()
            } else {
                // 系统全局窗口，可覆盖在任何应用之上，以及单独显示在桌面上
                // 安卓6.0 以后，全局的Window类别，必须使用TYPE_APPLICATION_OVERLAY
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) TYPE_APPLICATION_OVERLAY
                else TYPE_PHONE
            }
            format = PixelFormat.RGBA_8888
            gravity = Gravity.START or Gravity.TOP
            // 设置浮窗以外的触摸事件可以传递给后面的窗口、不自动获取焦点
            flags = if (config.immersionStatusBar)
            // 没有边界限制，允许窗口扩展到屏幕外
                FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS
            else FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE

            // 允许浮窗覆盖刘海/挖孔所在的区域。
            //
            // 系统默认按 LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT 处理：非全屏窗口会被限制在
            // “挖孔安全区”之外。横屏时如果挖孔位于屏幕左侧，系统会把浮窗的窗口坐标原点整体
            // 右移一个挖孔宽度的距离，于是屏幕最左侧那一块（挖孔区）永远拖不到，而右边界又按
            // 未偏移的整屏宽度计算，浮窗能拖出屏幕右侧；挖孔位于右侧时窗口本来就不与挖孔相交，
            // 所以 90° 和 270° 的表现完全不同。显式声明允许覆盖挖孔区之后，窗口坐标原点重新
            // 对齐屏幕左上角，这两个现象会一起消失。
            layoutInDisplayCutoutMode = when {
                // Android 11（API 30）起：不要求窗口全屏，也可以扩展到所有挖孔区域
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                // Android 9、10：只支持扩展到短边方向的挖孔区域
                // （横屏时挖孔位于屏幕左右两侧，正属于短边）
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else -> LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }

            width = if (config.widthMatch) MATCH_PARENT else WRAP_CONTENT
            height = if (config.heightMatch) MATCH_PARENT else WRAP_CONTENT

            if (config.immersionStatusBar && config.heightMatch) {
                height = DisplayUtils.getScreenHeight(context)
            }

            // 如若设置了固定坐标，直接定位
            if (config.locationPair != Pair(0, 0)) {
                x = config.locationPair.first
                y = config.locationPair.second
            }
        }
    }

    private fun getActivity() =
        if (context is Activity) context else LifecycleUtils.getTopActivity()

    private fun getToken(): IBinder? = getActivity()?.window?.decorView?.windowToken

    /**
     * 将自定义的布局，作为xml布局的父布局，添加到windowManager中，
     * 重写自定义布局的touch事件，实现拖拽效果。
     */
    private fun addView() {
        //浮窗父布局ParentFrameLayout与子view的context保持一致
        val rootViewContext = config.layoutView?.run { context } ?: context
        // 创建一个frameLayout作为浮窗布局的父容器
        frameLayout = ParentFrameLayout(rootViewContext, config)
        frameLayout?.tag = config.floatTag
        // 将浮窗布局文件添加到父容器frameLayout中，并返回该浮窗文件
        val floatingView = config.layoutView?.also { frameLayout?.addView(it) }
            ?: LayoutInflater.from(context).inflate(config.layoutId!!, frameLayout, true)
        // 为了避免创建的时候闪一下，我们先隐藏视图，不能直接设置GONE，否则定位会出现问题
        floatingView.visibility = View.INVISIBLE
        // 将frameLayout添加到系统windowManager中
        windowManager.addView(frameLayout, params)

        // 通过重写frameLayout的Touch事件，实现拖拽效果
        frameLayout?.touchListener = object : OnFloatTouchListener {
            override fun onTouch(event: MotionEvent) =
                touchUtils.updateFloat(frameLayout!!, event, windowManager, params)
        }

        // 在浮窗绘制完成的时候，设置初始坐标、执行入场动画
        frameLayout?.layoutListener = object : ParentFrameLayout.OnLayoutListener {
            override fun onLayout() {
                setGravity(frameLayout)
                lastLayoutMeasureWidth = frameLayout?.measuredWidth ?: -1
                lastLayoutMeasureHeight = frameLayout?.measuredHeight ?: -1
                config.apply {
                    // 如果设置了过滤当前页，或者后台显示前台创建、前台显示后台创建，隐藏浮窗，否则执行入场动画
                    if (filterSelf
                        || (showPattern == ShowPattern.BACKGROUND && LifecycleUtils.isForeground())
                        || (showPattern == ShowPattern.FOREGROUND && !LifecycleUtils.isForeground())
                    ) {
                        setVisible(View.GONE)
                        initEditText()
                    } else enterAnim(floatingView)

                    // 设置callbacks
                    layoutView = floatingView
                    invokeView?.invoke(floatingView)
                    callbacks?.createdResult(true, null, floatingView)
                    floatCallbacks?.builder?.createdResult?.invoke(true, null, floatingView)
                }
            }
        }

        setChangedListener()

        // 屏幕旋转、折叠屏展开、深色模式切换等不会触发触摸事件，需要主动监听。
        // 窗口真正收到配置变化时，方向确实变了，丢弃此前按手指坐标做的屏幕尺寸校正
        frameLayout?.configChangedListener = { newConfig ->
            touchUtils.invalidateScreenCorrection()
            onScreenSizeChanged()
            onUiModeChanged(newConfig)
        }
        registerAppConfigListener()
        registerDisplayListener()
    }

    /**
     * 注册屏幕变化监听：Android 12 起横屏后浮窗坐标和尺寸都不会自动刷新，
     * 需要主动重新计算拖动边界，并把浮窗拉回屏幕内。
     */
    private fun registerDisplayListener() {
        if (displayListener != null) return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) onScreenSizeChanged()
            }
        }
        try {
            manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            displayListener = listener
        } catch (e: Exception) {
            Logger.e("屏幕变化监听注册失败：$e")
        }
    }

    /**
     * 注销屏幕变化监听
     */
    private fun unregisterDisplayListener() {
        displayListener?.let {
            try {
                (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.unregisterDisplayListener(it)
            } catch (e: Exception) {
                Logger.e("屏幕变化监听注销失败：$e")
            }
        }
        displayListener = null
    }

    private fun nightModeOf(newConfig: Configuration): Int =
        newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK

    /**
     * 注册 Application 级的配置变化监听。
     *
     * 切换深色模式 / 浅色模式时，浮窗窗口自身不一定能收到 onConfigurationChanged
     * （浮窗常驻、宿主 Activity 已经重建、或者应用在后台时都可能收不到），
     * 这里作为兜底通道，保证 uiMode 变化一定能触发视图刷新。
     * 和窗口回调可能重复送达，内部按 uiMode 去重。
     */
    private fun registerAppConfigListener() {
        if (appConfigCallbacks != null) return
        // Activity 内的浮窗跟随 Activity 生命周期，配置变化时 Activity 会自行重建，无需额外处理
        if (config.showPattern == ShowPattern.CURRENT_ACTIVITY) return
        val application = context.applicationContext as? Application ?: return
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                onUiModeChanged(newConfig)
            }

            override fun onLowMemory() {}
        }
        try {
            application.registerComponentCallbacks(callbacks)
            appConfigCallbacks = callbacks
        } catch (e: Exception) {
            Logger.e("配置变化监听注册失败：$e")
        }
    }

    private fun unregisterAppConfigListener() {
        val callbacks = appConfigCallbacks ?: return
        appConfigCallbacks = null
        try {
            (context.applicationContext as? Application)?.unregisterComponentCallbacks(callbacks)
        } catch (e: Exception) {
            Logger.e("配置变化监听注销失败：$e")
        }
    }

    /**
     * uiMode（深色/浅色）变化后刷新浮窗内容。
     *
     * 浮窗不在 Activity 的布局树里，inflate 时解析出的颜色、drawable、主题属性都已经固化，
     * 不会随配置变化自动刷新，只能重新 inflate 一次内容视图。
     */
    private fun onUiModeChanged(newConfig: Configuration) {
        if (!config.refreshOnConfigChange) return
        val nightMode = nightModeOf(newConfig)
        if (lastNightMode == nightMode) return
        lastNightMode = nightMode
        refreshLayout(newConfig)
    }

    /**
     * 用新配置重新 inflate 浮窗内容。
     *
     * 只替换浮窗内的内容 view，窗口本身不变：位置、尺寸、拖拽监听、显示/隐藏状态全部保留，
     * 因此不会出现 dismiss 后需要用户手动重开的窗口，也不需要重建 WindowManager 窗口。
     * 重建完成后会再次回调宿主的 [FloatConfig.invokeView]，宿主可以在其中重新绑定数据与监听。
     */
    private fun refreshLayout(newConfig: Configuration) {
        val parent = frameLayout ?: return
        // 通过 setLayout(View) 直接传入外部 View 时没有 layoutId，无法自动重建，交由宿主处理
        val layoutId = config.layoutId ?: return
        if (parent.childCount == 0) return

        val oldView = parent.getChildAt(0)
        val oldWidth = oldView.layoutParams?.width ?: -1
        val oldHeight = oldView.layoutParams?.height ?: -1
        val oldVisible = oldView.visibility

        // 先完成新视图的 inflate，再移除旧视图，尽量减少中间出现的空白帧
        val inflateContext = resolveInflateContext(newConfig)
        val newView = LayoutInflater.from(inflateContext).inflate(layoutId, parent, false)
        newView.visibility = oldVisible

        // 只移除内容 view：不会触发 ParentFrameLayout.onDetachedFromWindow 里的 dismiss 回调
        parent.removeAllViews()
        parent.addView(newView)

        config.layoutView = newView
        // 重建后重新回调宿主，让宿主把数据和监听绑定到新视图上
        config.invokeView?.invoke(newView)

        // 恢复重建前的布局参数：用户手动拖拽调整过尺寸时，尺寸保存在内容 view 的
        // LayoutParams 上，重新 inflate 后已经回到 xml 里的默认值
        newView.layoutParams?.let {
            it.width = oldWidth
            it.height = oldHeight
            newView.layoutParams = it
        }

        initEditText()
        parent.requestLayout()
    }

    /**
     * 取一个「按新配置解析资源」的 Context。
     *
     * 浮窗视图的 context 通常是创建浮窗时的 Activity，切换深色模式后该 Activity 可能已经被
     * 销毁重建，或者它持有的 Resources / Theme 仍是旧配置。这里用回调里的新配置显式创建
     * Context，并重新套用浮窗所在 Activity（或应用）的主题，让 inflate 能解析到 values-night
     * 下的颜色、drawable 与主题属性（?attr/xxx）。
     */
    private fun resolveInflateContext(newConfig: Configuration): Context {
        val base = context.applicationContext ?: context
        val configured = if (base.resources.configuration.diff(newConfig) == 0) base
        else base.createConfigurationContext(newConfig)
        val themeRes = themeResId()
        return if (themeRes != 0) ContextThemeWrapper(configured, themeRes) else configured
    }

    /**
     * 浮窗视图使用的主题：优先 Activity 在 manifest 里声明的主题，其次应用级主题。
     *
     * 必须显式拿到主题资源，新 Context 才能按新配置重新解析主题属性。
     */
    private fun themeResId(): Int {
        val activity = context as? Activity
        if (activity != null) {
            try {
                val theme = context.packageManager.getActivityInfo(activity.componentName, 0).theme
                if (theme != 0) return theme
            } catch (e: Exception) {
                Logger.e("获取 Activity 主题失败：$e")
            }
        }
        return try {
            context.packageManager.getApplicationInfo(context.packageName, 0).theme
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 屏幕可用区域变化（旋转、折叠屏展开、导航栏切换）后，重新计算拖动边界并拉回浮窗。
     *
     * 边界原本只在触摸按下时计算，旋转屏幕时没有任何触摸事件：
     * 横屏后浮窗停留在竖屏坐标上（看起来没贴边），转回竖屏时又跑到屏幕外。
     *
     * 旋转过程中系统回调的时机并不统一，第一次回调时可能还没完成窗口重新布局，
     * 或者读到的屏幕尺寸仍是旋转前的值，所以这里不是"回调一次就算做完"，
     * 而是用实际读到的屏幕尺寸复核：只要边界还没按当前屏幕更新过就继续重试，
     * 直到应用成功，避免浮窗停在屏幕外。
     */
    internal fun onScreenSizeChanged() {
        if (screenCheckScheduled) return
        screenCheckScheduled = true
        screenCheckAttempt = 0
        checkScreenSizeChanged()
    }

    private fun checkScreenSizeChanged(): Unit {
        val layout = frameLayout
        if (layout == null || layout.windowToken == null) {
            screenCheckScheduled = false
            return
        }
        // 拖拽过程中不能改动坐标：重算边界 + 贴边会让浮窗明显跳一下，推迟到手指抬起后补做
        if (touchUtils.isGestureInProgress) {
            refreshAfterGesture = true
            screenCheckScheduled = false
            return
        }
        // 窗口还没完成重新布局（旋转瞬间宽高可能为 0），稍后重试
        if (layout.width <= 0 || layout.height <= 0) return retryScreenCheck()
        // 吸附/入场动画进行中先等它结束（重新计算边界时会取消旧动画）；
        // 只有等完整个重试周期仍在动画中（可能是残留状态）才直接接管，避免一直不贴边
        if (config.isAnim && screenCheckAttempt < screenCheckDelays.size - 1) return retryScreenCheck()

        val applied = try {
            touchUtils.updateBoundary(layout, params, windowManager)
        } catch (e: Exception) {
            false
        }
        // 只有边界已经按当前屏幕尺寸算过才算处理完成；
        // 否则说明这一刻拿到的还是旋转前的尺寸，需要继续重试
        if (applied && touchUtils.isBoundaryUpToDate()) {
            screenCheckScheduled = false
            return
        }
        retryScreenCheck()
    }

    private fun retryScreenCheck() {
        val layout = frameLayout
        if (layout == null) {
            screenCheckScheduled = false
            return
        }
        if (screenCheckAttempt >= screenCheckDelays.size) {
            // 本轮重试结束。仍会保留窗口重新布局时的兜底检查（见 setChangedListener）
            screenCheckScheduled = false
            return
        }
        val delay = screenCheckDelays[screenCheckAttempt]
        screenCheckAttempt++
        layout.postDelayed(screenCheckRunnable, delay)
    }

    /**
     * 拖拽手势结束：补做拖拽期间被推迟的屏幕变化刷新
     */
    private fun onGestureEnd() {
        if (!refreshAfterGesture) return
        refreshAfterGesture = false
        onScreenSizeChanged()
    }

    /**
     * 设置布局变化监听，根据变化时的对齐方式，设置浮窗位置
     */
    private fun setChangedListener() {
        frameLayout?.apply {
            // 监听frameLayout布局完成
            viewTreeObserver?.addOnGlobalLayoutListener {
                // 旋转屏幕后窗口会重新布局，这时是重新贴边最可靠的时机；
                // 如果边界还是按旋转前的屏幕尺寸算的，就再刷新一次。
                // 拖拽时每次 updateViewLayout 都会走到这里，此时绝不能改坐标，否则浮窗会跳；
                // 只有边界确实过期（例如拖拽过程中旋转了屏幕）才记为待处理，避免每次拖拽都多做一次刷新
                if (touchUtils.isGestureInProgress) {
                    if (!touchUtils.isBoundaryUpToDate()) refreshAfterGesture = true
                } else if (!screenCheckScheduled && !touchUtils.isBoundaryUpToDate()) {
                    onScreenSizeChanged()
                }
                val filterInvalidVal = lastLayoutMeasureWidth == -1 || lastLayoutMeasureHeight == -1
                val filterEqualVal =
                    lastLayoutMeasureWidth == this.measuredWidth && lastLayoutMeasureHeight == this.measuredHeight
                if (filterInvalidVal || filterEqualVal) {
                    return@addOnGlobalLayoutListener
                }

                // 水平方向
                if (config.layoutChangedGravity.and(Gravity.START) == Gravity.START) {
                    // ignore

                } else if (config.layoutChangedGravity.and(Gravity.END) == Gravity.END) {
                    val diffChangedSize = this.measuredWidth - lastLayoutMeasureWidth
                    params.x = params.x - diffChangedSize

                } else if (config.layoutChangedGravity.and(Gravity.CENTER_HORIZONTAL) == Gravity.CENTER_HORIZONTAL
                    || config.layoutChangedGravity.and(Gravity.CENTER) == Gravity.CENTER
                ) {
                    val diffChangedCenter = lastLayoutMeasureWidth / 2 - this.measuredWidth / 2
                    params.x += diffChangedCenter
                }

                // 垂直方向
                if (config.layoutChangedGravity.and(Gravity.TOP) == Gravity.TOP) {
                    // ignore

                } else if (config.layoutChangedGravity.and(Gravity.BOTTOM) == Gravity.BOTTOM) {
                    val diffChangedSize = this.measuredHeight - lastLayoutMeasureHeight
                    params.y = params.y - diffChangedSize

                } else if (config.layoutChangedGravity.and(Gravity.CENTER_VERTICAL) == Gravity.CENTER_VERTICAL
                    || config.layoutChangedGravity.and(Gravity.CENTER) == Gravity.CENTER
                ) {
                    val diffChangedCenter = lastLayoutMeasureHeight / 2 - this.measuredHeight / 2
                    params.y += diffChangedCenter
                }

                lastLayoutMeasureWidth = this.measuredWidth
                lastLayoutMeasureHeight = this.measuredHeight

                // 更新浮窗位置信息
                windowManager.updateViewLayout(frameLayout, params)
            }
        }
    }

    private fun initEditText() {
        if (config.hasEditText) frameLayout?.let { traverseViewGroup(it) }
    }

    private fun traverseViewGroup(view: View?) {
        view?.let {
            // 遍历ViewGroup，是子view判断是否是EditText，是ViewGroup递归调用
            if (it is ViewGroup) for (i in 0 until it.childCount) {
                val child = it.getChildAt(i)
                if (child is ViewGroup) traverseViewGroup(child) else checkEditText(child)
            } else checkEditText(it)
        }
    }

    private fun checkEditText(view: View) {
        if (view is EditText) InputMethodUtils.initInputMethod(view, config.floatTag)
    }


    /**
     * 设置浮窗的对齐方式，支持上下左右、居中、上中、下中、左中和右中，默认左上角
     * 支持手动设置的偏移量
     */
    @SuppressLint("RtlHardcoded")
    private fun setGravity(view: View?) {
        if (config.locationPair != Pair(0, 0) || view == null) return
        val parentRect = Rect()
        // 获取浮窗所在的矩形：Android 12 起 Display#getRectSize 返回的是 App 窗口区域，
        // 可能停留在旋转前的竖屏尺寸，这里改用统一的屏幕尺寸
        parentRect.right = DisplayUtils.getScreenWidth(context)
        val location = IntArray(2)
        // 获取在整个屏幕内的绝对坐标
        view.getLocationOnScreen(location)
        // 通过绝对高度和相对高度比较，判断包含顶部状态栏
        val statusBarHeight = if (location[1] > params.y) DisplayUtils.statusBarHeight(view) else 0
        val parentBottom =
            config.displayHeight.getDisplayRealHeight(context) - statusBarHeight
        when (config.gravity) {
            // 右上
            Gravity.END, Gravity.END or Gravity.TOP, Gravity.RIGHT, Gravity.RIGHT or Gravity.TOP ->
                params.x = parentRect.right - view.width
            // 左下
            Gravity.START or Gravity.BOTTOM, Gravity.BOTTOM, Gravity.LEFT or Gravity.BOTTOM ->
                params.y = parentBottom - view.height
            // 右下
            Gravity.END or Gravity.BOTTOM, Gravity.RIGHT or Gravity.BOTTOM -> {
                params.x = parentRect.right - view.width
                params.y = parentBottom - view.height
            }
            // 居中
            Gravity.CENTER -> {
                params.x = (parentRect.right - view.width).shr(1)
                params.y = (parentBottom - view.height).shr(1)
            }
            // 上中
            Gravity.CENTER_HORIZONTAL, Gravity.TOP or Gravity.CENTER_HORIZONTAL ->
                params.x = (parentRect.right - view.width).shr(1)
            // 下中
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL -> {
                params.x = (parentRect.right - view.width).shr(1)
                params.y = parentBottom - view.height
            }
            // 左中
            Gravity.CENTER_VERTICAL, Gravity.START or Gravity.CENTER_VERTICAL, Gravity.LEFT or Gravity.CENTER_VERTICAL ->
                params.y = (parentBottom - view.height).shr(1)
            // 右中
            Gravity.END or Gravity.CENTER_VERTICAL, Gravity.RIGHT or Gravity.CENTER_VERTICAL -> {
                params.x = parentRect.right - view.width
                params.y = (parentBottom - view.height).shr(1)
            }
            // 其他情况，均视为左上
            else -> {
            }
        }

        // 设置偏移量
        params.x += config.offsetPair.first
        params.y += config.offsetPair.second

        if (config.immersionStatusBar) {
            if (config.showPattern != ShowPattern.CURRENT_ACTIVITY) {
                params.y -= statusBarHeight
            }
        } else {
            if (config.showPattern == ShowPattern.CURRENT_ACTIVITY) {
                params.y += statusBarHeight
            }
        }
        // 更新浮窗位置信息
        windowManager.updateViewLayout(view, params)
    }

    /**
     * 设置浮窗的可见性
     */
    fun setVisible(visible: Int, needShow: Boolean = true) {
        if (frameLayout == null || frameLayout!!.childCount < 1) return
        // 如果用户主动隐藏浮窗，则该值为false
        config.needShow = needShow
        frameLayout!!.visibility = visible
        val view = frameLayout!!.getChildAt(0)
        if (visible == View.VISIBLE) {
            config.isShow = true
            // 隐藏期间（后台浮窗、悬浮窗被收起）旋转了屏幕，显示时按当前屏幕重新贴边
            if (!touchUtils.isBoundaryUpToDate()) onScreenSizeChanged()
            config.callbacks?.show(view)
            config.floatCallbacks?.builder?.show?.invoke(view)
        } else {
            config.isShow = false
            config.callbacks?.hide(view)
            config.floatCallbacks?.builder?.hide?.invoke(view)
        }
    }

    /**
     * 入场动画
     */
    private fun enterAnim(floatingView: View) {
        if (frameLayout == null || config.isAnim) return
        enterAnimator = AnimatorManager(frameLayout!!, params, windowManager, config)
            .enterAnim()?.apply {
                // 可以延伸到屏幕外，动画结束按需去除该属性，不然旋转屏幕可能置于屏幕外部
                params.flags =
                    FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS

                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationRepeat(animation: Animator) {}

                    override fun onAnimationEnd(animation: Animator) {
                        config.isAnim = false
                        if (!config.immersionStatusBar) {
                            // 不需要延伸到屏幕外了，防止屏幕旋转的时候，浮窗处于屏幕外
                            params.flags = FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE
                        }
                        initEditText()
                    }

                    override fun onAnimationCancel(animation: Animator) {}

                    override fun onAnimationStart(animation: Animator) {
                        floatingView.visibility = View.VISIBLE
                        config.isAnim = true
                    }
                })
                start()
            }
        if (enterAnimator == null) {
            floatingView.visibility = View.VISIBLE
            windowManager.updateViewLayout(frameLayout, params)
        }
    }

    /**
     * 退出动画
     */
    fun exitAnim() {
        if (frameLayout == null || (config.isAnim && enterAnimator == null)) return
        enterAnimator?.cancel()
        val animator: Animator? =
            AnimatorManager(frameLayout!!, params, windowManager, config).exitAnim()
        if (animator == null) remove() else {
            // 二次判断，防止重复调用引发异常
            if (config.isAnim) return
            config.isAnim = true
            params.flags = FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS
            animator.addListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator) {}

                override fun onAnimationEnd(animation: Animator) = remove()

                override fun onAnimationCancel(animation: Animator) {}

                override fun onAnimationStart(animation: Animator) {}
            })
            animator.start()
        }
    }

    /**
     * 退出动画执行结束/没有退出动画，进行回调、移除等操作
     */
    fun remove(force: Boolean = false) = try {
        config.isAnim = false
        // 取消还没执行的屏幕变化检查，避免浮窗移除后继续回调
        screenCheckScheduled = false
        screenCheckAttempt = 0
        refreshAfterGesture = false
        frameLayout?.removeCallbacks(screenCheckRunnable)
        // 清掉手势状态，避免标记残留导致之后的边界刷新被一直推迟
        if (this::touchUtils.isInitialized) touchUtils.resetGestureState()
        unregisterDisplayListener()
        unregisterAppConfigListener()
        FloatingWindowManager.remove(config.floatTag)
        // removeView是异步删除，在Activity销毁的时候会导致窗口泄漏，所以使用removeViewImmediate直接删除view
        windowManager.run { if (force) removeViewImmediate(frameLayout) else removeView(frameLayout) }
    } catch (e: Exception) {
        Logger.e("浮窗关闭出现异常：$e")
    }

    /**
     * 更新浮窗坐标信息
     */
    fun updateFloat(x: Int = -1, y: Int = -1, width: Int = -1, height: Int = -1) {
        frameLayout?.let {
            if (x == -1 && y == -1 && width == -1 && height == -1) {
                // 未指定具体坐标，执行吸附动画
                it.postDelayed({ touchUtils.updateBoundary(it, params, windowManager) }, 200)
            } else {
                if (x != -1) params.x = x
                if (y != -1) params.y = y
                if (width != -1) params.width = width
                if (height != -1) params.height = height
                windowManager.updateViewLayout(it, params)
            }
        }
    }
}

