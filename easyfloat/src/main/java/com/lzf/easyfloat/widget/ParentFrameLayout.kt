package com.lzf.easyfloat.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.lzf.easyfloat.data.FloatConfig
import com.lzf.easyfloat.interfaces.OnFloatTouchListener
import com.lzf.easyfloat.utils.InputMethodUtils

/**
 * @author: liuzhenfeng
 * @function: 系统浮窗的父布局，对touch事件进行了重新分发
 * @date: 2019-07-10  14:16
 */
@SuppressLint("ViewConstructor")
internal class ParentFrameLayout(
    context: Context,
    private val config: FloatConfig,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var touchListener: OnFloatTouchListener? = null
    var layoutListener: OnLayoutListener? = null

    // 屏幕配置变化（旋转、尺寸、字体、深色模式等）的回调，用于重新计算拖动边界、刷新视图
    var configChangedListener: ((Configuration) -> Unit)? = null
    private var isCreated = false

    // 布局绘制完成的接口，用于通知外部做一些View操作，不然无法获取view宽高
    interface OnLayoutListener {
        fun onLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 初次绘制完成的时候，需要设置对齐方式、坐标偏移量、入场动画
        if (!isCreated) {
            isCreated = true
            layoutListener?.onLayout()
        }
    }

    /**
     * 窗口自身收到的配置变化（屏幕旋转、折叠屏展开等），
     * 用于在 App 处于后台、拿不到 App 级配置回调时也能重新计算边界
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configChangedListener?.invoke(newConfig)
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) touchListener?.onTouch(event)
        // 是拖拽事件就进行拦截，反之不拦截
        // ps：拦截后将不再回调该方法，会交给该view的onTouchEvent进行处理，所以后续事件需要在onTouchEvent中回调
        return config.isDrag || super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) touchListener?.onTouch(event)
        return config.isDrag || super.onTouchEvent(event)
    }

    /**
     * 按键转发到视图的分发方法，在这里关闭输入法
     */
    override fun dispatchKeyEventPreIme(event: KeyEvent?): Boolean {
        if (config.hasEditText && event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            InputMethodUtils.closedInputMethod(config.floatTag)
        }
        return super.dispatchKeyEventPreIme(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        config.callbacks?.dismiss()
        config.floatCallbacks?.builder?.dismiss?.invoke()
    }
}