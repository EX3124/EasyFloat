package com.lzf.easyfloat.core

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.lzf.easyfloat.data.FloatConfig
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.utils.DisplayUtils
import kotlin.math.max
import kotlin.math.min

/**
 * @author: liuzhenfeng
 * @function: 根据吸附模式，实现相应的拖拽效果
 * @date: 2019-07-05  14:24
 */
internal class TouchUtils(val context: Context, val config: FloatConfig) {

    // 窗口所在的矩形
    private var parentRect: Rect = Rect()

    // 悬浮的父布局高度、宽度
    private var parentHeight = 0
    private var parentWidth = 0

    // 四周坐标边界值
    private var leftBorder = 0
    private var topBorder = 0
    private var rightBorder = 0
    private var bottomBorder = 0

    // 起点坐标
    private var lastX = 0f
    private var lastY = 0f

    // 浮窗各边距离父布局的距离
    private var leftDistance = 0
    private var rightDistance = 0
    private var topDistance = 0
    private var bottomDistance = 0

    // x轴、y轴的最小距离值
    private var minX = 0
    private var minY = 0
    private val location = IntArray(2)
    private var statusBarHeight = 0

    // 屏幕可用高度 - 浮窗自身高度 的剩余高度
    private var emptyHeight = 0

    // 最近一次计算边界时使用的屏幕尺寸，用于判断边界是否已经按当前屏幕计算过
    private var boundaryScreenSize = Point(0, 0)

    // 校正后的屏幕尺寸，以及校正时依据的原始测量值
    //
    // App 在后台横屏时，系统仍然按竖屏返回尺寸，用手指坐标、浮窗自身位置作证据校正后缓存下来。
    // 校正结果必须缓存：如果每次手势、每次布局变化都重新校正一遍，边界就会在“竖屏 → 横屏”
    // 之间反复变化，拖拽过程中浮窗会被不断拉回竖屏区域，表现为位置跳动
    private var correctedScreenSize: Point? = null
    private var correctedFromSize = Point(0, 0)

    // 当前屏幕宽度（含导航栏）。手指的 x 坐标不可能越过它，
    // 一旦越过就说明屏幕尺寸的方向判断错了（典型是 App 在后台时的横屏）
    private var physicalScreenWidth = 0

    // 窗口坐标系相对屏幕坐标系的横向偏移（刘海/挖孔避让造成）
    //
    // 带挖孔的机型上，系统会把浮窗整体右移来做避让，此时 params.x = 0 的浮窗实际停在屏幕左侧的
    // 第 offsetX 个像素上：左边这一段永远拖不到（贴着摄像头孔），而右边界又多算了同样一段，
    // 浮窗能拖出屏幕右侧。所以 90 度（孔在左）和 270 度（孔在右）的表现才会完全不同。
    // 纵向的同类偏移由 statusBarHeight 处理，横向一直没有处理，这里用
    // “窗口的绝对位置 - params.x” 直接测出来，不依赖机型和旋转方向的推断
    private var frameOffsetX = 0
    // 上面这个偏移是为哪个屏幕尺寸测的：同一方向只测一次，避免测量值随窗口坐标一起漂移
    private var frameOffsetSize = Point(0, 0)

    // 手指是否正按在浮窗上（拖拽手势进行中）。
    // 拖拽期间不能重算边界、不能贴边，否则浮窗会被拉到别的位置，表现为“跳”
    var isGestureInProgress = false
        private set

    // 手势结束时的通知：拖拽期间被推迟的边界刷新在这里补做
    var gestureEndCallback: (() -> Unit)? = null

    // 正在执行的吸附动画：旋转屏幕时它的目标坐标是按旧边界算的，必须先取消
    private var sideAnimator: ValueAnimator? = null

    // 主动取消动画（旋转屏幕、重算边界）时，不再回调业务方的 dragEnd
    private var silentCancel = false

    /**
     * 根据吸附模式，实现相应的拖拽效果
     */
    fun updateFloat(
        view: View,
        event: MotionEvent,
        windowManager: WindowManager,
        params: LayoutParams
    ) {
        config.callbacks?.touchEvent(view, event)
        config.floatCallbacks?.builder?.touchEvent?.invoke(view, event)
        // 不可拖拽、或者正在执行动画，不做处理
        if (!config.dragEnable || config.isAnim) {
            config.isDrag = false
            // 动画期间不处理拖拽，但不能让“手势进行中”的标记残留，否则之后的边界刷新会被一直推迟
            if (event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_UP ||
                event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_CANCEL
            ) finishGesture()
            return
        }

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                config.isDrag = false
                // 标记手势开始：拖拽期间不允许重算边界、贴边，否则浮窗会被拉到别的位置（表现为跳动）
                isGestureInProgress = true
                // 记录触摸点的位置
                lastX = event.rawX
                lastY = event.rawY
                // 初始化一些边界数据
                initBoarderValue(view, params)
            }

            MotionEvent.ACTION_MOVE -> {
                // 手指已经越过测量出来的屏幕宽度：说明系统给的是旋转前的尺寸（典型是 App 在后台时的横屏）。
                // 先用触摸点把边界校正过来，否则下面的“过滤边界值之外的拖拽”会直接丢弃这次移动，
                // 浮窗就卡在竖屏范围里拖不动。校正结果会被缓存，不会每次手势都重新校正一遍
                if (physicalScreenWidth > 0 && event.rawX > physicalScreenWidth) {
                    initBoarderValue(view, params, event.rawX.toInt())
                }
                // 过滤边界值之外的拖拽
                // leftBorder/rightBorder 是窗口坐标，手指的 rawX 是屏幕坐标，比较前要先加上横向偏移
                if (event.rawX < leftBorder + frameOffsetX
                    || event.rawX > rightBorder + view.width + frameOffsetX
                    || event.rawY < topBorder || event.rawY > bottomBorder + view.height
                ) {
                    // 丢弃这次移动时必须同步触摸起点：否则这段被丢弃的距离会在手指回到区域内时
                    // 一次性补加回来，浮窗会突然跳一段
                    lastX = event.rawX
                    lastY = event.rawY
                    return
                }

                // 移动值 = 本次触摸值 - 上次触摸值
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                // 忽略过小的移动，防止点击无效
                if (!config.isDrag && dx * dx + dy * dy < 81) return
                config.isDrag = true

                var x = params.x + dx.toInt()
                var y = params.y + dy.toInt()
                // 检测浮窗是否到达边缘
                x = when {
                    x < leftBorder -> leftBorder
                    x > rightBorder -> rightBorder
                    else -> x
                }

                if (config.showPattern == ShowPattern.CURRENT_ACTIVITY) {
                    // 单页面浮窗，设置状态栏不沉浸时，最小高度为状态栏高度
                    if (y < statusBarHeight(view) && !config.immersionStatusBar) y =
                        statusBarHeight(view)
                }

                y = when {
                    y < topBorder -> topBorder
                    // 状态栏沉浸时，最小高度为-statusBarHeight，反之最小高度为0
                    y < 0 -> if (config.immersionStatusBar) {
                        if (y < -statusBarHeight) -statusBarHeight else y
                    } else 0
                    y > bottomBorder -> bottomBorder
                    else -> y
                }

                // 吸附目标一律用真实边界（已经包含刘海横向偏移、导航栏预留）：
                // 否则吸到“屏幕左侧”时会停在摄像头孔右边，吸到右侧又会超出屏幕
                when (config.sidePattern) {
                    SidePattern.LEFT -> x = leftBorder
                    SidePattern.RIGHT -> x = rightBorder
                    SidePattern.TOP -> y = topBorder
                    SidePattern.BOTTOM -> y = bottomBorder

                    SidePattern.AUTO_HORIZONTAL ->
                        x = if (x - leftBorder <= rightBorder - x) leftBorder else rightBorder

                    SidePattern.AUTO_VERTICAL ->
                        y = if (y - topBorder <= bottomBorder - y) topBorder else bottomBorder

                    SidePattern.AUTO_SIDE -> {
                        leftDistance = x - leftBorder
                        rightDistance = rightBorder - x
                        topDistance = y - topBorder
                        bottomDistance = bottomBorder - y

                        minX = min(leftDistance, rightDistance)
                        minY = min(topDistance, bottomDistance)
                        if (minX < minY) {
                            x = if (leftDistance <= rightDistance) leftBorder else rightBorder
                        } else {
                            y = if (topDistance <= bottomDistance) topBorder else bottomBorder
                        }
                    }
                    else -> {
                    }
                }

                // 重新设置坐标信息
                params.x = x
                params.y = y
                windowManager.updateViewLayout(view, params)
                config.callbacks?.drag(view, event)
                config.floatCallbacks?.builder?.drag?.invoke(view, event)
                // 更新上次触摸点的数据
                lastX = event.rawX
                lastY = event.rawY
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 拖拽结束必须复位：之前这里只在下次 ACTION_DOWN 时复位，
                // 导致拖拽过一次之后 isDrag 一直为 true，旋转屏幕时边界刷新会被误判成"拖拽中"而跳过
                val wasDrag = config.isDrag
                config.isDrag = false
                if (!wasDrag) {
                    // 只是点击，没有产生拖拽：同样要结束手势状态，并补做拖拽期间被推迟的边界刷新
                    finishGesture()
                    return
                }
                // 回调拖拽事件的ACTION_UP
                config.callbacks?.drag(view, event)
                config.floatCallbacks?.builder?.drag?.invoke(view, event)
                when (config.sidePattern) {
                    SidePattern.RESULT_LEFT,
                    SidePattern.RESULT_RIGHT,
                    SidePattern.RESULT_TOP,
                    SidePattern.RESULT_BOTTOM,
                    SidePattern.RESULT_HORIZONTAL,
                    SidePattern.RESULT_VERTICAL,
                    SidePattern.RESULT_SIDE -> sideAnim(view, params, windowManager)
                    else -> {
                        config.callbacks?.dragEnd(view)
                        config.floatCallbacks?.builder?.dragEnd?.invoke(view)
                    }
                }
                // 手势结束：此后才允许重算边界/贴边，并补做拖拽期间被推迟的屏幕变化刷新
                finishGesture()
            }

            else -> return
        }
    }

    /**
     * 根据吸附类别，更新浮窗位置
     */
    fun updateFloat(
        view: View,
        params: LayoutParams,
        windowManager: WindowManager
    ) {
        initBoarderValue(view, params)
        sideAnim(view, params, windowManager)
    }

    /**
     * 屏幕可用区域变化（旋转屏幕、折叠屏展开、导航栏切换）后，刷新拖动边界，
     * 并把浮窗拉回屏幕内，再按吸附模式贴边。
     *
     * 边界只在触摸按下时计算，旋转屏幕时没有任何事件，浮窗会停留在旧坐标上：
     * 横屏时看起来没贴边，再转回竖屏时甚至会跑到屏幕外面，所以需要这个入口主动矫正。
     */
    fun updateBoundary(
        view: View,
        params: LayoutParams,
        windowManager: WindowManager
    ): Boolean {
        // 拖拽过程中不改动坐标：手指正在移动浮窗，这里再重算边界并贴边会让它明显跳一下。
        // 拖拽期间发生的变化会被记为待处理，在手指抬起后补做（见 gestureEndCallback）
        if (isGestureInProgress) {
            // 收不到 ACTION_UP 时标记可能残留，此时 config.isDrag 为 false，直接清掉即可
            if (!config.isDrag) isGestureInProgress = false else return true
        }
        // 旋转屏幕时，上一段吸附动画的目标坐标是按旧边界算的，继续执行会把浮窗又拉回屏幕外
        cancelSideAnimSilently()
        // 旋转刚发生时窗口可能还没完成重新布局，此时读到的绝对位置还是旧方向的，不能用来测横向偏移
        initBoarderValue(view, params, measureOffset = false)
        // 先把坐标拉回新的可视区域，避免旋转后浮窗停留在屏幕之外
        val maxX = max(leftBorder, rightBorder)
        // 与 ACTION_MOVE 中的纵向边界处理保持一致：沉浸时最小为 -statusBarHeight，否则为 0
        var minY = if (config.immersionStatusBar) -statusBarHeight(view) else 0
        if (config.showPattern == ShowPattern.CURRENT_ACTIVITY && !config.immersionStatusBar) {
            minY = max(minY, statusBarHeight(view))
        }
        minY = max(minY, topBorder)
        val maxY = max(minY, bottomBorder)
        params.x = params.x.coerceIn(leftBorder, maxX)
        params.y = params.y.coerceIn(minY, maxY)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            // 浮窗已被移除、或系统正在重新布局窗口，交给调用方稍后重试
            return false
        }
        // 吸附模式（含自动吸附）：吸附到新的边缘；未开启吸附时，仅做上面的边界限制
        sideAnim(view, params, windowManager)
        return true
    }

    /**
     * 边界是否已经按当前屏幕尺寸计算过
     *
     * 旋转屏幕的瞬间，部分机型读到的还是旋转前的尺寸，此时算出来的边界仍是旧方向的，
     * 需要等读到正确尺寸后重新计算。新建浮窗、还没计算过边界时视为已是最新。
     */
    fun isBoundaryUpToDate(): Boolean =
        boundaryScreenSize.x == 0 || boundaryScreenSize == currentScreenSize()

    /**
     * 取消正在执行的吸附动画
     *
     * 旋转屏幕后旧的动画目标坐标已经失效，继续执行会把浮窗又拉回屏幕外，
     * 而重新计算边界的坐标又会被它覆盖，表现为"贴边了又被拉出去"。
     */
    private fun cancelSideAnimSilently() {
        val animator = sideAnimator ?: return
        sideAnimator = null
        silentCancel = true
        animator.cancel()
        silentCancel = false
    }

    /**
     * 用屏幕内的证据修正屏幕尺寸的方向
     *
     * 系统给出的是旋转前的（竖屏）尺寸时，长边其实才是屏幕宽度：手指（或浮窗自身的右边缘）
     * 已经越过测量出的宽度，说明当前屏幕的真实宽度不会小于长边，直接把长短边换过来，
     * 让拖动边界覆盖整块屏幕。
     */
    private fun expandScreenSize(size: Point, widthLowerBound: Int): Point {
        if (widthLowerBound <= size.x) return size
        val longSide = max(size.x, size.y)
        val shortSide = min(size.x, size.y)
        if (widthLowerBound <= longSide) return Point(longSide, shortSide)
        // 连长边都不够（例如显示尺寸被缩放），直接以这个下限作为宽度
        return Point(widthLowerBound, shortSide)
    }

    /**
     * 当前生效的屏幕尺寸
     *
     * 校正结果只在系统测量值没有变化时有效：测量值变化说明真的发生了旋转或显示尺寸变化，
     * 此时以新的测量值为准，并丢弃旧的校正结果。
     */
    private fun currentScreenSize(measured: Point = DisplayUtils.getScreenSize(context)): Point {
        val corrected = correctedScreenSize
        if (corrected != null && measured == correctedFromSize) return corrected
        correctedScreenSize = null
        return measured
    }

    /**
     * 按给定屏幕尺寸计算可用高度，规则与 DisplayUtils.rejectedNavHeight 保持一致：
     * 横屏时导航栏在侧边、不占高度，竖屏时需要减去导航栏高度
     */
    private fun parentHeightOf(size: Point): Int =
        if (size.x > size.y) size.y
        else size.y - DisplayUtils.getNavigationBarCurrentHeight(context)

    /**
     * 丢弃屏幕尺寸的校正结果（窗口收到真实的配置变化时调用）
     */
    internal fun invalidateScreenCorrection() {
        correctedScreenSize = null
    }

    /**
     * 重置手势状态（浮窗移除时调用），避免标记残留导致之后的边界刷新被一直推迟
     */
    internal fun resetGestureState() {
        isGestureInProgress = false
        gestureEndCallback = null
    }

    /**
     * 手势结束：清除“手势进行中”的标记，并补做拖拽期间被推迟的屏幕变化刷新
     */
    private fun finishGesture() {
        if (!isGestureInProgress) return
        isGestureInProgress = false
        gestureEndCallback?.invoke()
    }

    /**
     * 测量窗口坐标系相对屏幕坐标系的横向偏移
     *
     * 系统为刘海/挖孔做避让时会把浮窗整体右移，此时 params.x = 0 的浮窗实际停在屏幕左侧的第
     * offsetX 个像素上。用刚取到的窗口绝对位置减去 params.x 就能得到这段偏移：它同时解释了
     * “左边一块区域拖不到”和“右边能拖出屏幕”这两个现象。
     *
     * 同一个屏幕尺寸只测一次：如果系统还会把越界的窗口推回屏幕内，每次手势都重新测量就会把
     * “推回”的距离也累计进偏移量，坐标会越算越偏。
     */
    private fun measureFrameOffset(
        view: View,
        params: LayoutParams,
        size: Point,
        measureOffset: Boolean
    ): Int {
        // 换了方向（或显示尺寸变了）：上一轮测到的偏移不再适用，先按“没有偏移”重新开始
        if (frameOffsetSize != size) {
            frameOffsetX = 0
            frameOffsetSize = size
        }
        // 旋转刚发生、窗口还没完成重新布局时不能测，先沿用已有的值
        if (!measureOffset) return frameOffsetX
        val measured = location[0] - params.x
        // 以下情况都沿用旧值：
        // 布局未完成；窗口坐标已经为负——此时“系统把窗口推回屏幕”的距离会被当成新的偏移，
        // 每按一次就累加一倍，边界会越算越偏；读数非正——窗口没有被推离；读数过大——明显不是挖孔避让。
        // 注意不能把“一次测到 0”当成结论缓存起来：浮窗停在屏幕中间时窗口不与挖孔相交，
        // 系统不会推离它，此时测出的 0 只代表“这一刻没有偏移”，用户把浮窗拖到屏幕边缘后
        // 必须允许重新测量，否则永远测不到真正的偏移量
        if (view.width <= 0 || params.x < 0 || measured <= 0 || measured > max(size.x / 6, 1)) {
            return frameOffsetX
        }
        frameOffsetX = measured
        return frameOffsetX
    }

    /**
     * 初始化边界值等数据
     *
     * @param touchX 拖拽时手指的 x 坐标（0 表示没有触摸信息）。手指不可能超出屏幕，
     * 所以它能用来判断屏幕真实方向：系统给的是旋转前的竖屏尺寸时，手指会越过测量出的宽度。
     */
    private fun initBoarderValue(
        view: View,
        params: LayoutParams,
        touchX: Int = 0,
        // 是否允许用窗口当前的绝对位置测量横向偏移：旋转刚发生时布局可能还是旧方向的，此时不能测
        measureOffset: Boolean = true
    ) {
        // 屏幕宽高需要每次获取，可能会有屏幕旋转、虚拟导航栏的状态变化
        val measuredSize = DisplayUtils.getScreenSize(context)
        // 先取“可能已校正”的尺寸：校正结果只在系统测量值没有变化时有效
        var screenSize = currentScreenSize(measuredSize)
        // 横屏时 x 一定是长边：用手指坐标、浮窗自身的右边缘作为证据把方向纠正过来。
        // 只在测量结果本身是竖向（x < y）时才校正，避免把正常的横屏误判成需要交换
        val evidence = if (touchX > 0) max(touchX, params.x + view.width) else 0
        if (screenSize.x < screenSize.y && evidence > screenSize.x) {
            val correctedSize = expandScreenSize(measuredSize, evidence)
            if (correctedSize != screenSize) {
                // 缓存校正结果，本次旋转周期内一直沿用：否则每次手势、每次布局变化都会
                // 重新算出旧方向的边界，拖拽时浮窗会被反复拉回竖屏区域（表现为跳动）
                correctedScreenSize = correctedSize
                correctedFromSize = measuredSize
                screenSize = correctedSize
            }
        }
        // 记录本次计算边界所用的屏幕尺寸，判断边界是否需要重算时以它为准
        boundaryScreenSize = screenSize
        // 记录当前屏幕宽度（含导航栏），手指越过它才说明方向判断错了
        physicalScreenWidth = screenSize.x
        parentWidth = DisplayUtils.getScreenWidth(context, screenSize)
        // 使用校正后的尺寸时，屏幕高度也必须跟着校正：config.displayHeight 会自己重新测量，
        // 拿到的仍是旧方向的尺寸（横屏时高度会大出一整倍），浮窗能被拖到屏幕之外
        parentHeight = if (screenSize == measuredSize) {
            config.displayHeight.getDisplayRealHeight(context)
        } else {
            parentHeightOf(screenSize)
        }
        // 获取在整个屏幕内的绝对坐标
        view.getLocationOnScreen(location)
        // 通过绝对高度和相对高度比较，判断包含顶部状态栏
        statusBarHeight = if (location[1] > params.y) statusBarHeight(view) else 0
        emptyHeight = parentHeight - view.height - statusBarHeight

        // 刘海/挖孔避让会让 params.x 和屏幕坐标错开，把这段偏移换算掉：
        // config 里的左右边界是屏幕坐标，减去偏移才是窗口坐标（params）下的可用范围
        // 用“本次算边界实际使用的尺寸”做缓存的键：后台横屏时系统测量值仍是竖屏的，
        // 只有校正后的尺寸才能区分竖屏和横屏，否则横屏会沿用竖屏测到的偏移量
        val offsetX = measureFrameOffset(view, params, screenSize, measureOffset)
        leftBorder = max(0, config.leftBorder) - offsetX
        rightBorder = min(parentWidth, config.rightBorder) - view.width - offsetX
        topBorder = if (config.showPattern == ShowPattern.CURRENT_ACTIVITY) {
            // 单页面浮窗，坐标屏幕顶部计算
            if (config.immersionStatusBar) config.topBorder
            else config.topBorder + statusBarHeight(view)
        } else {
            // 系统浮窗，坐标从状态栏底部开始，沉浸时坐标为负
            if (config.immersionStatusBar) config.topBorder - statusBarHeight(view) else config.topBorder
        }
        bottomBorder = if (config.showPattern == ShowPattern.CURRENT_ACTIVITY) {
            // 单页面浮窗，坐标屏幕顶部计算
            if (config.immersionStatusBar)
                min(emptyHeight, config.bottomBorder - view.height)
            else
                min(emptyHeight, config.bottomBorder + statusBarHeight(view) - view.height)
        } else {
            // 系统浮窗，坐标从状态栏底部开始，沉浸时坐标为负
            if (config.immersionStatusBar)
                min(emptyHeight, config.bottomBorder - statusBarHeight(view) - view.height)
            else
                min(emptyHeight, config.bottomBorder - view.height)
        }
    }

    private fun sideAnim(
        view: View,
        params: LayoutParams,
        windowManager: WindowManager
    ) {
        initDistanceValue(params)
        val isX: Boolean
        val end = when (config.sidePattern) {
            SidePattern.RESULT_LEFT -> {
                isX = true
                leftBorder
            }
            SidePattern.RESULT_RIGHT -> {
                isX = true
                params.x + rightDistance
            }
            SidePattern.RESULT_HORIZONTAL -> {
                isX = true
                if (leftDistance < rightDistance) leftBorder else params.x + rightDistance
            }

            SidePattern.RESULT_TOP -> {
                isX = false
                topBorder
            }
            SidePattern.RESULT_BOTTOM -> {
                isX = false
                // 不要轻易使用此相关模式，需要考虑虚拟导航栏的情况
                bottomBorder
            }
            SidePattern.RESULT_VERTICAL -> {
                isX = false
                if (topDistance < bottomDistance) topBorder else bottomBorder
            }

            SidePattern.RESULT_SIDE -> {
                if (minX < minY) {
                    isX = true
                    if (leftDistance < rightDistance) leftBorder else params.x + rightDistance
                } else {
                    isX = false
                    if (topDistance < bottomDistance) topBorder else bottomBorder
                }
            }

            // 固定方向：与 ACTION_MOVE 中的处理保持一致，旋转后仍固定在该方向的边缘
            SidePattern.LEFT -> {
                isX = true
                leftBorder
            }
            SidePattern.RIGHT -> {
                isX = true
                rightBorder
            }
            SidePattern.TOP -> {
                isX = false
                topBorder
            }
            SidePattern.BOTTOM -> {
                isX = false
                bottomBorder
            }

            // 自动吸附：与 ACTION_MOVE 中的吸附规则保持一致，
            // 旋转屏幕、调用 updateFloat 之后同样会贴边，而不是停留在屏幕中间
            SidePattern.AUTO_HORIZONTAL -> {
                isX = true
                if (params.x - leftBorder <= rightBorder - params.x) leftBorder else rightBorder
            }
            SidePattern.AUTO_VERTICAL -> {
                isX = false
                if (params.y - topBorder <= bottomBorder - params.y) topBorder else bottomBorder
            }
            SidePattern.AUTO_SIDE -> {
                val horizontalDistance = min(params.x - leftBorder, rightBorder - params.x)
                val verticalDistance = min(params.y - topBorder, bottomBorder - params.y)
                if (horizontalDistance < verticalDistance) {
                    isX = true
                    if (params.x - leftBorder <= rightBorder - params.x) leftBorder else rightBorder
                } else {
                    isX = false
                    if (params.y - topBorder <= bottomBorder - params.y) topBorder else bottomBorder
                }
            }
            else -> return
        }

        // 上一段动画还在执行时先取消，避免两个动画同时写坐标
        cancelSideAnimSilently()
        val animator = ValueAnimator.ofInt(if (isX) params.x else params.y, end)
        sideAnimator = animator
        animator.addUpdateListener {
            try {
                if (isX) params.x = it.animatedValue as Int else params.y = it.animatedValue as Int
                // 极端情况，还没吸附就调用了关闭浮窗，会导致吸附闪退
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                animator.cancel()
            }
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationRepeat(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
                if (sideAnimator === animator) sideAnimator = null
                dragEnd(view)
            }

            override fun onAnimationCancel(animation: Animator) {
                if (sideAnimator === animator) sideAnimator = null
                dragEnd(view)
            }

            override fun onAnimationStart(animation: Animator) {
                config.isAnim = true
            }
        })
        animator.start()
    }

    private fun dragEnd(view: View) {
        config.isAnim = false
        // 吸附动画结束也意味着这次拖拽彻底结束，同步复位拖拽标记
        config.isDrag = false
        // 动画是被主动取消的（旋转屏幕重新计算边界），不回调业务方
        if (silentCancel) return
        config.callbacks?.dragEnd(view)
        config.floatCallbacks?.builder?.dragEnd?.invoke(view)
    }

    /**
     * 计算一些边界距离数据
     */
    private fun initDistanceValue(params: LayoutParams) {
        leftDistance = params.x - leftBorder
        rightDistance = rightBorder - params.x
        topDistance = params.y - topBorder
        bottomDistance = bottomBorder - params.y

        minX = min(leftDistance, rightDistance)
        minY = min(topDistance, bottomDistance)
    }

    private fun statusBarHeight(view: View) = DisplayUtils.statusBarHeight(view)

}