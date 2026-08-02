/* Modified by Sui9x on 2026-06-27 */

package com.mja.reyamf.xposed.ui.window

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.ITaskStackListenerProxy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.DISPLAY_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.view.WindowInsets
import android.widget.ImageButton
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.google.android.material.color.MaterialColors
import com.mja.reyamf.common.getAttr
import com.mja.reyamf.common.onException
import com.mja.reyamf.common.runMain
import com.mja.reyamf.databinding.LeftBackGestureOverlayBinding
import com.mja.reyamf.databinding.RightBackGestureOverlayBinding
import com.mja.reyamf.databinding.WindowAppBinding
import kotlinx.coroutines.withContext
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.services.YAMFManager.config
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.RunMainThreadQueue
import com.mja.reyamf.xposed.utils.TipUtil
import com.mja.reyamf.xposed.utils.animateAlpha
import com.mja.reyamf.xposed.utils.animateResize
import com.mja.reyamf.xposed.utils.animateResize2
import com.mja.reyamf.xposed.utils.animateScaleThenResize
import com.mja.reyamf.xposed.utils.dpToPx
import com.mja.reyamf.xposed.utils.getActivityInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable


@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(
    val context: Context,
    private val flags: Int,
    private val onVirtualDisplayCreated: (Int) -> Unit
) :
    TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    companion object {
        const val TAG = "reYAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "com.mja.reyamf.ui.window.action.ACTION_RESET_ALL_WINDOW"
    }

    lateinit var binding: WindowAppBinding
    lateinit var bindingLeftBackGesture: LeftBackGestureOverlayBinding
    lateinit var bindingRightBackGesture: RightBackGestureOverlayBinding
    private lateinit var virtualDisplay: VirtualDisplay
    private val taskStackListener =
        ITaskStackListenerProxy.newInstance(context.classLoader) { args, method ->
            when (method.name) {
                "onTaskMovedToFront" -> {
                    onTaskMovedToFront(args[0] as ActivityManager.RunningTaskInfo)
                }
                "onTaskDescriptionChanged" -> {
                    onTaskDescriptionChanged(args[0] as ActivityManager.RunningTaskInfo)
                }
                "onTaskRemovalStarted" -> {
                    val taskInfo = args.getOrNull(0) as? ActivityManager.RunningTaskInfo

                    if (taskInfo != null && taskInfo.getObject("displayId") == displayId) {
                        runMain {
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(300)

                                val empty = runCatching {
                                    Instances.activityTaskManager
                                        .getAllRootTaskInfosOnDisplay(displayId)
                                        .none { it.visible }
                                }.getOrDefault(false)

                                if (empty) {
                                    onDestroySafe()
                                }
                            }
                        }
                    }
                }
            }
        }
    private val rotationWatcher = RotationWatcher()
    private val surfaceOnTouchListener = SurfaceOnTouchListener()
    private val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()
    var displayId = -1
    var rotateLock = false
    var isMini = false
    var isCollapsed = false
    private var halfWidth = 0
    private var halfHeight = 0
    lateinit var surfaceView: View
    private var newDpi = calculateDpi(
        config.defaultWindowWidth, config.defaultWindowHeight,
        calculateScreenInches(config.defaultWindowWidth, config.defaultWindowHeight)
    ) - config.reduceDPI
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var isResize: Boolean = true
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var paramsBg = WindowManager.LayoutParams()
    private var backGestureJob: Job? = null
    private var lastClickTime = 0L
    private val DOUBLE_CLICK_TIME_DELTA: Long = 300
    private var isSuperShown = false

    private var mainTaskId: Int = -1
    private var destroyed = false

    private var activeMoveAnchor: View? = null

    private data class SafeRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun getMainDisplaySafeRect(): SafeRect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsets(
                WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.displayCutout()
            )

            val extraTop = 4.dpToPx().toInt()

            // タスクバーを強めに避けたいなら 48〜72。
            // まずは戻りすぎ防止で控えめに 24 推奨。
            val extraBottom = 24.dpToPx().toInt()

            return SafeRect(
                left = bounds.left + insets.left,
                top = bounds.top + insets.top,// + extraTop,
                right = bounds.right - insets.right,
                bottom = bounds.bottom - insets.bottom// - extraBottom
            )
        }

        val dm = context.resources.displayMetrics
        return SafeRect(
            left = 0,
            top = 0,
            right = dm.widthPixels,
            bottom = dm.heightPixels
        )
    }

    private fun getDescendantRectInRoot(child: View): Rect {
        val rect = Rect(0, 0, child.width, child.height)
        binding.root.offsetDescendantRectToMyCoords(child, rect)
        return rect
    }

    private fun getRootBaseLeftTop(lp: WindowManager.LayoutParams): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val dm = context.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

        val rootW = binding.root.width.takeIf { it > 0 } ?: binding.root.measuredWidth
        val rootH = binding.root.height.takeIf { it > 0 } ?: binding.root.measuredHeight

        val horizontalGravity = lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val verticalGravity = lp.gravity and Gravity.VERTICAL_GRAVITY_MASK

        val baseLeft = when (horizontalGravity) {
            Gravity.RIGHT, Gravity.END -> bounds.right - rootW
            Gravity.CENTER_HORIZONTAL -> bounds.left + (bounds.width() - rootW) / 2
            else -> bounds.left
        }

        val baseTop = when (verticalGravity) {
            Gravity.BOTTOM -> bounds.bottom - rootH
            Gravity.CENTER_VERTICAL -> bounds.top + (bounds.height() - rootH) / 2
            else -> bounds.top
        }

        return baseLeft to baseTop
    }

    /**
     * anchor が safeRect 外に出ないように lp.x/y を直接補正する。
     * updateViewLayout 前に呼ぶ用。postしない。
     */
    private fun clampParamsKeepingAnchor(
        lp: WindowManager.LayoutParams,
        anchor: View
    ) {
        if (anchor.width <= 0 || anchor.height <= 0) return
        if (binding.root.width <= 0 || binding.root.height <= 0) return

        val safe = getMainDisplaySafeRect()
        val anchorRect = getDescendantRectInRoot(anchor)
        val (baseLeft, baseTop) = getRootBaseLeftTop(lp)

        val anchorScreenLeft = baseLeft + lp.x + anchorRect.left
        val anchorScreenTop = baseTop + lp.y + anchorRect.top
        val anchorScreenRight = baseLeft + lp.x + anchorRect.right
        val anchorScreenBottom = baseTop + lp.y + anchorRect.bottom

        var dx = 0
        var dy = 0

        if (anchorScreenLeft < safe.left) {
            dx = safe.left - anchorScreenLeft
        } else if (anchorScreenRight > safe.right) {
            dx = safe.right - anchorScreenRight
        }

        if (anchorScreenTop < safe.top) {
            dy = safe.top - anchorScreenTop
        } else if (anchorScreenBottom > safe.bottom) {
            dy = safe.bottom - anchorScreenBottom
        }

        lp.x += dx
        lp.y += dy
    }

    private fun clampParamsKeepingWindowUsable(lp: WindowManager.LayoutParams) {
        if (binding.root.width <= 0 || binding.root.height <= 0) return

        val safe = getMainDisplaySafeRect()
        val (baseLeft, baseTop) = getRootBaseLeftTop(lp)

        val rootW = binding.root.width
        val rootH = binding.root.height

        val left = baseLeft + lp.x
        val right = left + rootW

        var dx = 0
        var dy = 0

        // 横は最低 1/3 残す
        val minVisibleW = (rootW / 3).coerceAtLeast(80.dpToPx().toInt())

        if (right < safe.left + minVisibleW) {
            dx = (safe.left + minVisibleW) - right
        } else if (left > safe.right - minVisibleW) {
            dx = (safe.right - minVisibleW) - left
        }

        /*
         * 縦は「現在操作しているマスク」を境界で止める。
         * bottom操作中なら bottom mask が safe.top/safe.bottom から出ないようにする。
         * top操作中なら top mask が safe.top/safe.bottom から出ないようにする。
         */
        val verticalAnchor = activeMoveAnchor
            ?: runCatching { binding.vBottomMoveMask }.getOrNull()
            ?: return

        val anchorRect = getDescendantRectInRoot(verticalAnchor)

        val anchorTop = baseTop + lp.y + anchorRect.top
        val anchorBottom = baseTop + lp.y + anchorRect.bottom

        if (anchorTop < safe.top) {
            dy = safe.top - anchorTop
        } else if (anchorBottom > safe.bottom) {
            dy = safe.bottom - anchorBottom
        }

        lp.x += dx
        lp.y += dy
    }

    private fun isAnchorVerticallyVisibleInSafe(
        lp: WindowManager.LayoutParams,
        anchor: View
    ): Boolean {
        if (anchor.width <= 0 || anchor.height <= 0) return false

        val safe = getMainDisplaySafeRect()
        val (baseLeft, baseTop) = getRootBaseLeftTop(lp)
        val rect = getDescendantRectInRoot(anchor)

        val top = baseTop + lp.y + rect.top
        val bottom = baseTop + lp.y + rect.bottom

        return bottom > safe.top && top < safe.bottom
    }

    private fun clampAfterExpandIfNoMoveMaskVisible() {
        val lp = binding.root.layoutParams as? WindowManager.LayoutParams ?: return

        val topVisible = runCatching {
            isAnchorVerticallyVisibleInSafe(lp, binding.vTopMoveMask)
        }.getOrDefault(false)

        val bottomVisible = runCatching {
            isAnchorVerticallyVisibleInSafe(lp, binding.vBottomMoveMask)
        }.getOrDefault(false)

        // どちらか見えていれば、復帰直後は補正しない
        if (topVisible || bottomVisible) return

        // 両方見えない時だけ、近い方を安全領域へ戻す
        val safe = getMainDisplaySafeRect()
        val (baseLeft, baseTop) = getRootBaseLeftTop(lp)

        val topRect = getDescendantRectInRoot(binding.vTopMoveMask)
        val bottomRect = getDescendantRectInRoot(binding.vBottomMoveMask)

        val topMaskTop = baseTop + lp.y + topRect.top
        val topMaskBottom = baseTop + lp.y + topRect.bottom
        val bottomMaskTop = baseTop + lp.y + bottomRect.top
        val bottomMaskBottom = baseTop + lp.y + bottomRect.bottom

        val dy = when {
            // 両方とも画面上に消えている
            bottomMaskBottom < safe.top -> {
                safe.top - bottomMaskBottom
            }

            // 両方とも画面下に消えている
            topMaskTop > safe.bottom -> {
                safe.bottom - topMaskTop
            }

            // 保険: 距離が近い方を戻す
            else -> {
                val toTop = kotlin.math.abs(safe.top - bottomMaskBottom)
                val toBottom = kotlin.math.abs(safe.bottom - topMaskTop)

                if (toTop < toBottom) {
                    safe.top - bottomMaskBottom
                } else {
                    safe.bottom - topMaskTop
                }
            }
        }

        lp.y += dy

        runCatching {
            Instances.windowManager.updateViewLayout(binding.root, lp)
        }
    }

    private fun clampBottomBarNow() {
        val lp = binding.root.layoutParams as? WindowManager.LayoutParams ?: return

        clampParamsKeepingAnchor(lp, binding.vBottomMoveMask)

        runCatching {
            Instances.windowManager.updateViewLayout(binding.root, lp)
        }
    }

    private fun clampCollapsedIconNow() {
        val lp = binding.root.layoutParams as? WindowManager.LayoutParams ?: return

        clampParamsKeepingAnchor(lp, binding.cvappIcon)

        runCatching {
            Instances.windowManager.updateViewLayout(binding.root, lp)
        }
    }

    private fun getMoveHandleAnchor(): View {
        return binding.vBottomMoveMask
    }

    private fun clampMoveHandleNow() {
        val lp = binding.root.layoutParams as? WindowManager.LayoutParams ?: return

        if (isCollapsed) {
            clampParamsKeepingAnchor(lp, binding.cvappIcon)
        } else {
            clampParamsKeepingWindowUsable(lp)
        }

        runCatching {
            Instances.windowManager.updateViewLayout(binding.root, lp)
        }
    }
    
    private fun clampResizeHandleNow() {
        val lp = binding.root.layoutParams as? WindowManager.LayoutParams ?: return

        clampParamsKeepingAnchor(lp, binding.ibRightResize)

        runCatching {
            Instances.windowManager.updateViewLayout(binding.root, lp)
        }
    }
    
    private fun setTapOrDragMoveListener(
        view: View,
        onTap: () -> Unit
    ) {
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, event ->
            val lp = binding.root.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false

                    v.isPressed = true
                    moveToTopIfNeed(event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    if (!dragging) {
                        dragging = dx * dx + dy * dy > touchSlop * touchSlop
                    }

                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()

                        if (isCollapsed) {
                            clampParamsKeepingAnchor(lp, binding.cvappIcon)
                        } else {
                            // 今使ってて操作感が良かった方
                            clampParamsKeepingWindowUsable(lp)
                        }

                        runCatching {
                            Instances.windowManager.updateViewLayout(binding.root, lp)
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false

                    if (dragging) {
                        clampMoveHandleNow()
                    } else {
                        v.performClick()
                        onTap()
                    }
                    
                    moveToTopIfNeed(event)

                    dragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false

                    if (dragging) {
                        clampMoveHandleNow()
                    }

                    dragging = false
                    true
                }

                else -> true
            }
        }
    }
    
    /*
    private fun setTapOrDragMoveListener(
        view: View,
        allowWhenSuperShown: Boolean = false,
        onTap: () -> Unit
    ) {
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, event ->
            if (isSuperShown && !allowWhenSuperShown) {
                v.isPressed = false
                return@setOnTouchListener false
            }

            val lp = binding.root.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false

                    v.isPressed = true
                    moveToTopIfNeed(event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    if (!dragging) {
                        dragging = dx * dx + dy * dy > touchSlop * touchSlop
                    }
    
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()

                        if (isCollapsed) {
                            clampParamsKeepingAnchor(lp, binding.cvappIcon)
                        } else {
                        clampParamsKeepingWindowUsable(lp)
                            }

                        runCatching {
                            Instances.windowManager.updateViewLayout(binding.root, lp)
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
    
                    if (dragging) {
                        clampMoveHandleNow()
                    } else {
                        v.performClick()
                        onTap()
                    }

                    dragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false

                    if (dragging) {
                        clampMoveHandleNow()
                    }

                    dragging = false
                    true
                }
    
                else -> true
            }
        }
    }
    */
    
    /*
    private fun bringSidebarServiceToFront() {
        runCatching {
            context.startService(
                Intent(context, com.mja.reyamf.manager.sidebar.SidebarService::class.java).apply {
                    action = com.mja.reyamf.manager.sidebar.SidebarService.ACTION_BRING_TO_FRONT
                }
            )
        }
    }
    */
    
    private fun bringSidebarToFront() {
        runCatching {
            context.sendBroadcast(
                Intent("com.mja.reyamf.manager.sidebar.action.BRING_TO_FRONT").apply {
                    setPackage("com.mja.reyamf")
                }
            )
        }
    }
    
    private fun showSuperMenu() {
        if (isSuperShown) return

        isSuperShown = true
        binding.clSuperLayout.visibility = View.VISIBLE
        binding.clSuperLayout.bringToFront()

        animateAlpha(binding.clSuperLayout, 0f, 1f)
    }

    private fun hideSuperMenu() {
        if (!isSuperShown) return

        isSuperShown = false

        animateAlpha(binding.clSuperLayout, 1f, 0f) {
            binding.clSuperLayout.visibility = View.GONE
        }
    }

    private fun toggleSuperMenu() {
        if (isSuperShown) {
            hideSuperMenu()
        } else {
            showSuperMenu()
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
                lp.apply {
                    x = 0
                    y = 0
                }
                Instances.windowManager.updateViewLayout(binding.root, lp)
                val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, context.resources.displayMetrics).toInt()
                val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300F, context.resources.displayMetrics).toInt()
                binding.vSizePreviewer.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
                surfaceView.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
            }
        }
    }

    init {
        runCatching {
            binding = WindowAppBinding.inflate(LayoutInflater.from(context))
            bindingLeftBackGesture = LeftBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
            bindingRightBackGesture = RightBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "Failed to create new window, did you reboot?", e)
            TipUtil.showToast("Failed to create new window, did you reboot?")
        }.onSuccess {
            doInit()
        }
    }

    private fun doInit() {
        when(config.surfaceView) {
            0 -> {
                surfaceView = binding.viewSurface
                binding.viewTexture.visibility = View.GONE
            }
            1 -> {
                surfaceView = binding.viewTexture
                binding.viewSurface.visibility = View.GONE
            }
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        )

        val displayManager = context.getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        params.y = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                orientation = 0
                config.portraitY
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                orientation = 1
                config.landscapeY
            }
            else -> 0
        }

        params.apply {
            if (orientation == 0) {
                gravity = Gravity.CENTER
                y = -80.dpToPx().toInt()
            } else {
                gravity = Gravity.TOP or Gravity.START
                y = 0
            }
            x = 0
//            this as WindowLayoutParamsHidden
//            privateFlags = privateFlags or WindowLayoutParamsHidden.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
        }

        paramsBg = WindowManager.LayoutParams(
            20.dpToPx().toInt(),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        paramsBg.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        bindingLeftBackGesture.root.let {
            paramsBg.gravity = Gravity.START or Gravity.TOP
            Instances.windowManager.addView(bindingLeftBackGesture.root, paramsBg)
        }

        bindingRightBackGesture.root.let {
            val paramsBgR = WindowManager.LayoutParams(
                20.dpToPx().toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            paramsBgR.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            paramsBgR.gravity = Gravity.END or Gravity.TOP
            Instances.windowManager.addView(bindingRightBackGesture.root, paramsBgR)
        }

        binding.root.let { layout ->
            Instances.windowManager.addView(layout, params)
        }

        binding.rootClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)

            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                if (isCollapsed) {
                    clampCollapsedIconNow()
                } else {
                    clampMoveHandleNow()
                }
            }

            true
        }

        binding.vBottomMoveMask.setOnTouchListener { _, event ->
            activeMoveAnchor = binding.vBottomMoveMask

            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)

            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                clampMoveHandleNow()
                activeMoveAnchor = null
            }

            true
        }

        binding.vTopMoveMask.setOnTouchListener { _, event ->
            activeMoveAnchor = binding.vTopMoveMask

            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)

            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                clampMoveHandleNow()
                activeMoveAnchor = null
            }

            true
        }
        
        setTapOrDragMoveListener(binding.ibBack) {
            val down = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(down, 0)
            val up = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(up, 0)
        }
        
        setTapOrDragMoveListener(binding.ibCloseShortcut) {
            isResize = false
            backGestureJob?.cancel()
            backGestureJob = null

            binding.cvappIcon.visibility = View.INVISIBLE

            CoroutineScope(Dispatchers.IO).launch {
                delay(200)

                withContext(Dispatchers.Main) {
                    animateScaleThenResize(
                        binding.cvParent,
                        1F, 1F,
                        0F, 0F,
                        0.5F, 0.5F,
                        0, 0,
                        context
                    ) {
                        onDestroy()
                    }
                }
            }
        }

        /*
        binding.ibCollapseShortcut.setOnClickListener {
            if (isSuperShown) {
                isSuperShown = false
                animateAlpha(binding.clSuperLayout, 1f, 0f) {
                    binding.clSuperLayout.visibility = View.GONE
                }
            }

            if (!isCollapsed) {
                changeCollapsed()
            }
        }
        */

        setTapOrDragMoveListener(binding.ibCollapseShortcut) {
            if (isMini) return@setTapOrDragMoveListener

            if (!isCollapsed) {
                changeCollapsed()
            }
        }
        
        setTapOrDragMoveListener(binding.cvappIcon) {
            if (isCollapsed) {
                changeCollapsed()
            }
        }

        /*
        binding.ibSuper.setOnClickListener {
            isSuperShown = true
            animateAlpha(binding.clSuperLayout, 0f, 1f)
        }
        */

        setTapOrDragMoveListener(binding.ibSuper) {
            //isSuperShown = true
            //binding.clSuperLayout.visibility = View.VISIBLE
            //animateAlpha(binding.clSuperLayout, 0f, 1f)
            toggleSuperMenu()
        }

        rightResize(binding.ibRightResize)

        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

        binding.ibClose.setOnClickListener {
            isResize = false
            backGestureJob?.cancel()
            backGestureJob = null

            binding.cvappIcon.visibility = View.INVISIBLE

            CoroutineScope(Dispatchers.IO).launch {
                delay(200)

                withContext(Dispatchers.Main) {
                    animateScaleThenResize(
                        binding.cvParent,
                        1F, 1F,
                        0F, 0F,
                        0.5F, 0.5F,
                        0, 0,
                        context
                    ) {
                        onDestroy()
                    }
                }
            }
        }

        binding.ibFullscreen.setOnClickListener {
            animateAlpha(binding.clSuperLayout, 1f, 0f)
            getTopRootTask()?.runCatching {
                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
            }?.onFailure { t ->
                if (t is Error) throw t
                TipUtil.showToast("${t.message}")
            }?.onSuccess {
                binding.ibClose.callOnClick()
            }
        }

        binding.ibMinimize.setOnClickListener {
            isSuperShown = false
            binding.apply {
                animateAlpha(binding.clSuperLayout, 1f, 0f)
                binding.clSuperLayout.visibility = View.GONE
                changeMini()
            }
        }
        
        /*
        binding.ibCollapse.setOnClickListener {
            isSuperShown = false
            binding.apply {
                animateAlpha(binding.clSuperLayout, 1f, 0f)
                binding.clSuperLayout.visibility = View.GONE
                changeCollapsed()
            }
            true
        }
        */
        
        /*
        binding.ibSuperClose.setOnClickListener {
            isSuperShown = false
            animateAlpha(binding.clSuperLayout, 1f, 0f)
        }
        */

        virtualDisplay = Instances.displayManager.createVirtualDisplay(
            "yamf${System.currentTimeMillis()}", config.defaultWindowWidth, config.defaultWindowHeight, newDpi-config.reduceDPI, null, flags
        )
        displayId = virtualDisplay.display.displayId
        (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, if (config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
        Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
        (surfaceView as? TextureView)?.surfaceTextureListener = this
        (surfaceView as? SurfaceView)?.holder?.addCallback(this)
        var failCount = 0
        fun watchRotation() {
            runCatching {
                Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
            }.onFailure {
                failCount++
                Log.d(TAG, "watchRotation: fail $failCount")
                watchRotation()
            }
        }
        watchRotation()
        context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        val width = config.defaultWindowWidth.dpToPx().toInt()
        val height = config.defaultWindowHeight.dpToPx().toInt()
        surfaceView.updateLayoutParams {
            this.width = width
            this.height = height
        }
        binding.vSizePreviewer.updateLayoutParams {
            this.width = width
            this.height = height
        }
        onVirtualDisplayCreated(displayId)

        isResize = false
        binding.cvBackground.post {
            originalWidth = binding.cvBackground.width
            originalHeight = binding.cvBackground.height
            binding.cvBackground.visibility = View.VISIBLE

            binding.cvBackground.radius = config.windowRoundedCorner.dpToPx()
            binding.cvappIcon.radius = config.windowRoundedCorner.dpToPx()


            binding.cvParent.radius = (config.windowRoundedCorner+2).dpToPx()
            originalWidth = binding.cvParent.width
            originalHeight = binding.cvParent.height
            binding.cvParent.visibility = View.VISIBLE

            animateScaleThenResize(
                binding.cvBackground,
                0F, 0F,
                1F, 1F,
                0.5F, 0.5F,
                originalWidth, originalHeight,
                context
            ) {
                setBackgroundWrapContent()

                CoroutineScope(Dispatchers.Main).launch {
                    delay(200)

                    binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                }

                isResize = true
            }
        }

        //TODO: Find me a better alternative for less resource usage instead of polling
        backGestureJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (isMini || isCollapsed) {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.GONE
                        bindingRightBackGesture.root.visibility = View.GONE
                    }
                } else if (displayId == YAMFManager.currentDisplayId) {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.VISIBLE
                        bindingRightBackGesture.root.visibility = View.VISIBLE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.GONE
                        bindingRightBackGesture.root.visibility = View.GONE
                    }
                }
                delay(100)
            }
        }
        bringSidebarToFront()
    }

    private fun onDestroy() {
        context.unregisterReceiver(broadcastReceiver)
        Instances.iWindowManager.removeRotationWatcher(rotationWatcher)
        Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener)
        YAMFManager.removeWindow(displayId)
        virtualDisplay.release()
        Instances.windowManager.removeView(binding.root)
        Instances.windowManager.removeView(bindingLeftBackGesture.root)
        Instances.windowManager.removeView(bindingRightBackGesture.root)
    }

    private fun onDestroySafe() {
        if (destroyed) return
        destroyed = true

        backGestureJob?.cancel()
        backGestureJob = null

        runCatching { context.unregisterReceiver(broadcastReceiver) }
        runCatching { Instances.iWindowManager.removeRotationWatcher(rotationWatcher) }
        runCatching { Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener) }
        runCatching { YAMFManager.removeWindow(displayId) }
        runCatching { virtualDisplay.release() }

        runCatching { Instances.windowManager.removeView(binding.root) }
        runCatching { Instances.windowManager.removeView(bindingLeftBackGesture.root) }
        runCatching { Instances.windowManager.removeView(bindingRightBackGesture.root) }
    }

    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? {
        Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
            if (task.visible)
                return task
        }
        return null
    }

    private fun moveToTop() {
        Instances.windowManager.removeView(bindingLeftBackGesture.root)
        Instances.windowManager.removeView(bindingRightBackGesture.root)
        Instances.windowManager.addView(bindingLeftBackGesture.root, bindingLeftBackGesture.root.layoutParams)
        Instances.windowManager.addView(bindingRightBackGesture.root, bindingRightBackGesture.root.layoutParams)

        Instances.windowManager.removeView(binding.root)
        Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        YAMFManager.moveToTop(displayId)
        
        bringSidebarToFront()
    }

    private fun moveToTopIfNeed(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP && YAMFManager.isTop(displayId).not()) {
            moveToTop()
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        RunMainThreadQueue.add {
            if (taskInfo.isVisible.not()) {
                delay(500) // fixme: use a method that directly determines visibility
            }

            var backgroundColor = 0
            var statusBarColor = 0
            var navigationBarColor = 0
            var taskDescription: ActivityManager.TaskDescription?

            if (Build.VERSION.SDK_INT < 35) {
                val topActivity = taskInfo.topActivity ?: return@add
                taskDescription = Instances.activityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@add
                val activityInfo = (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(topActivity, 0, taskInfo.getObjectAs("userId"))

                backgroundColor = taskDescription.backgroundColor
                statusBarColor = taskDescription.backgroundColor
                navigationBarColor = taskDescription.backgroundColor
                binding.appIcon.setImageDrawable(RoundedDrawable().apply {
                    drawable = runCatching { taskDescription.icon }.getOrNull()?.let { BitmapDrawable(it) } ?: activityInfo.loadIcon(Instances.packageManager)
                    isClipEnabled = true
                    radius = 100
                })
            } else {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningTasks = activityManager.getRunningTasks(5)

                for (task in runningTasks) {
                    if (task.taskId == taskInfo.taskId) {
                        val packageName = task.baseActivity?.packageName
                        try {
                            val packageManager = context.packageManager
                            backgroundColor = task.taskDescription!!.backgroundColor
                            statusBarColor = task.taskDescription!!.backgroundColor
                            navigationBarColor = task.taskDescription!!.backgroundColor
                            binding.appIcon.setImageDrawable(packageManager.getApplicationIcon(
                                packageName!!
                            ))
                        } catch (e: PackageManager.NameNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            if (config.coloredController) {
                val onStateBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(statusBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                //binding.ibClose.imageTintList = ColorStateList.valueOf(onStateBar)
                binding.background.setBackgroundColor(navigationBarColor)

                val onNavigationBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(navigationBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }
                
                binding.ibBack.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibCloseShortcut.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibCollapseShortcut.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibMinimize.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibFullscreen.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibRightResize.imageTintList = ColorStateList.valueOf(onNavigationBar)
            }
        }
    }

    fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.getObject("displayId") == displayId) {
            updateTask(taskInfo)
        }
    }

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.getObject("displayId") == displayId) {
            if(!taskInfo.isVisible){
                return
            }
            updateTask(taskInfo)
        }
    }

    inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain {
                if (rotateLock.not())
                    rotate(rotation)
            }
        }
    }

    fun rotate(rotation: Int) {
        if (rotation == 1 || rotation == 3) {
            val t = halfHeight
            halfHeight = halfWidth
            halfWidth = t
            val surfaceWidth = surfaceView.width
            val surfaceHeight = surfaceView.height
            binding.vSizePreviewer.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
            surfaceView.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (isMini.not() && isCollapsed.not()) {
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width, height, newDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        } else {
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, newDpi)
            surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
        }
        virtualDisplay.surface = Surface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isResize) {
            if (isMini.not()) {
                newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
                virtualDisplay.resize(width, height, newDpi)
                surface.setDefaultBufferSize(width, height)
                halfWidth = width % 2
                halfHeight = height % 2
            } else {
                newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
                virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, newDpi)
                surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {

    }

    // minimizes the floating window a bar-less only-content floating window
    private fun changeMini() {
        isCollapsed = false
        isResize = false

        if (isMini) {
            isMini = false
            isResize = true
            binding.rootClickMask.visibility = View.GONE

            if (surfaceView is SurfaceView) {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                setBackgroundWrapContent()
                setParrentWrapContent()
            } else {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                animateScaleThenResize(
                    binding.cvBackground,
                    0.5F, 0.5F,
                    1F, 1F,
                    0F, 0F,
                    originalWidth, originalHeight,
                    context
                ){
                    setBackgroundWrapContent()
                    setParrentWrapContent()
                    bindingLeftBackGesture.root.visibility = View.VISIBLE
                    bindingRightBackGesture.root.visibility = View.VISIBLE
                }
            }

            binding.ibRightResize.visibility = View.VISIBLE
            binding.ibCloseShortcut.visibility = View.VISIBLE
            binding.ibBack.visibility = View.VISIBLE
            binding.ibCollapseShortcut.visibility = View.VISIBLE
            binding.ibSuper.visibility = View.VISIBLE
            binding.ibResize.visibility = View.VISIBLE
            surfaceView.visibility = View.VISIBLE
            surfaceView.setOnTouchListener(surfaceOnTouchListener)
            surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

            return
        }
        else if (!isMini) {
            binding.rootClickMask.visibility = View.VISIBLE
            isMini = true

            if (config.surfaceView == 1) {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth/2
                    height = originalHeight/2
                }
            } else {
                animateResize(
                    binding.cvBackground,
                    originalWidth, originalWidth/2,
                    originalHeight, originalHeight/2,
                    context
                ){
                    isResize = true
                    bindingLeftBackGesture.root.visibility = View.GONE
                    bindingRightBackGesture.root.visibility = View.GONE
                }
            }

            binding.ibRightResize.visibility = View.GONE
            binding.ibCloseShortcut.visibility = View.GONE
            binding.ibBack.visibility = View.GONE
            binding.ibCollapseShortcut.visibility = View.GONE
            binding.ibSuper.visibility = View.GONE
            binding.ibResize.visibility = View.GONE
            surfaceView.setOnTouchListener(null)
            surfaceView.setOnGenericMotionListener(null)

            return
        }
    }

    private fun setBackgroundWrapContent() {
        val layoutParams = binding.cvBackground.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvBackground.layoutParams = layoutParams
    }

    private fun setParrentWrapContent() {
        val layoutParams = binding.cvParent.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvParent.layoutParams = layoutParams
    }

    private fun changeCollapsed() {
        isResize = false
        if (isCollapsed) {
            binding.rootClickMask.visibility = View.GONE
            expandWindow()
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE

            binding.root.post {
                //clampBottomBarNow()
            }
        } else {
            binding.rootClickMask.visibility = View.GONE
            collapseWindow()
            bindingLeftBackGesture.root.visibility = View.GONE
            bindingRightBackGesture.root.visibility = View.GONE
        }
    }

    private fun expandWindow() {
        isCollapsed = false
        
        setExpandedOnlyViewsVisible(true)
        
        binding.background.visibility = View.VISIBLE
        binding.ibRightResize.visibility = View.VISIBLE

        animateResize2(
            binding.appIcon, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), 0, context) {
            binding.cvappIcon.visibility = View.GONE
            animateResize2(binding.cvBackground, 0, originalWidth, 0, originalHeight, context) {
                setBackgroundWrapContent()
                setParrentWrapContent()
                binding.cvappIcon.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    delay(100)

                    clampAfterExpandIfNoMoveMaskVisible()
                }

                binding.cvappIcon.visibility = View.GONE
                isResize = true
            }
        }
    }

    private fun collapseWindow() {
        isCollapsed = true
        
        setExpandedOnlyViewsVisible(false)

        CoroutineScope(Dispatchers.Main).launch {
            delay(100)

            animateResize2(binding.cvBackground, binding.cvBackground.width, 0, binding.cvBackground.height, 0, context) {
                binding.cvappIcon.visibility = View.VISIBLE
                binding.background.visibility = View.GONE
                binding.ibRightResize.visibility = View.GONE
                animateResize2(
                    binding.appIcon,
                    0, 40.dpToPx().toInt(),
                    0, 40.dpToPx().toInt(),
                    context
                ) {
                    clampCollapsedIconNow()
                }

                isResize = true

                /**
                binding.root.post {
                    clampCollapsedIconNow()
                    binding.root.post {
                        clampCollapsedIconNow()
                    }
                }
                **/
            }
        }
    }
    
    private fun setExpandedOnlyViewsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
    
        binding.ibRightResize.visibility = v
        binding.ibResize.visibility = v
    
        binding.rlBarControllerBottom.visibility = v
        binding.rlBarControllerSide.visibility = v
    }
    
    private fun calculateScreenInches(width: Int, height: Int): Float {
        val x = (width / context.resources.displayMetrics.xdpi).pow(2)
        val y = (height / context.resources.displayMetrics.ydpi).pow(2)

        return sqrt(x + y)
    }

    private fun calculateDpi(width: Int, height: Int, screenSizeInInches: Float): Int {
        val widthSqr = width.toFloat().pow(2)
        val heightSqr = height.toFloat().pow(2)
        val diagonalPixels = sqrt(widthSqr + heightSqr)

        return floor(diagonalPixels / screenSizeInInches).toInt()
    }

    private fun rightResize(ibResize: ImageButton) {
        ibResize.setOnTouchListener(object : View.OnTouchListener {
            var beginX = 0F
            var beginY = 0F
            var beginWidth = 0
            var beginHeight = 0

            var offsetX = 0F
            var offsetY = 0F

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        beginX = event.rawX
                        beginY = event.rawY
                        binding.vSizePreviewer.layoutParams.let {
                            beginWidth = it.width
                            beginHeight = it.height
                        }
                        binding.vSizePreviewer.visibility = View.VISIBLE
                        binding.cvParent.strokeWidth = 0
                    }
                    MotionEvent.ACTION_MOVE -> {
                        offsetX = event.rawX - beginX
                        offsetY = event.rawY - beginY
                        binding.vSizePreviewer.updateLayoutParams {
                            val targetWidth = beginWidth + offsetX.toInt()
                            if (targetWidth > 0)
                                width = targetWidth
                            val targetHeight = beginHeight + offsetY.toInt()
                            if (targetHeight > 0)
                                height = targetHeight
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        binding.vSizePreviewer.post {
                            surfaceView.updateLayoutParams {
                                width = binding.vSizePreviewer.width
                                height = binding.vSizePreviewer.height
                            }
                        }
                        
                        binding.root.post {
                            binding.root.post {
                                clampResizeHandleNow()
                            }
                        }

                        binding.vSizePreviewer.visibility = View.GONE
                        moveToTopIfNeed(event)
                        binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                    }
                }
                return true
            }
        })
    }

    fun forwardMotionEvent(event: MotionEvent) {
        if (!isSuperShown) {
            val newEvent = MotionEvent.obtain(event)
            newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.inputManager.injectInputEvent(newEvent, 0)
            newEvent.recycle()
        }
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            moveToTopIfNeed(event)
            return true
        }
    }

    inner class SurfaceOnGenericMotionListener : View.OnGenericMotionListener {
        override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            return true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        virtualDisplay.surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        newDpi = calculateDpi(width, height, calculateScreenInches(width, height )) - config.reduceDPI
        virtualDisplay.resize(width, height, newDpi)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        virtualDisplay.surface = null
    }

    private val moveGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        var startX = 0
        var startY = 0
        var xAnimation: FlingAnimation? = null
        var yAnimation: FlingAnimation? = null
        var lastX = 0F
        var lastY = 0F
        var last2X = 0F
        var last2Y = 0F

        override fun onDown(e: MotionEvent): Boolean {
            xAnimation?.cancel()
            yAnimation?.cancel()
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            startX = params.x
            startY = params.y
            return true
        }


        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            e1 ?: return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            params.x = (startX + (e2.rawX - e1.rawX)).toInt()
            params.y = (startY + (e2.rawY - e1.rawY)).toInt()
            if (isCollapsed) {
                clampParamsKeepingAnchor(params, binding.cvappIcon)
            } else {
                clampParamsKeepingWindowUsable(params)
            }
            Instances.windowManager.updateViewLayout(binding.root, params)
            last2X = lastX
            last2Y = lastY
            lastX = e2.rawX
            lastY = e2.rawY
            return true
        }

        /*
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            e1 ?: return false
            if (e1.source == InputDevice.SOURCE_MOUSE) return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams

            runCatching {
                if (sign(velocityX) != sign(e2.rawX - last2X)) return@runCatching
                xAnimation = flingAnimationOf({
                    params.x = it.toInt()
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }, {
                    params.x.toFloat()
                })
                    .setStartVelocity(velocityX)
                    .setMinValue(0F)
                    .setMaxValue(context.display.width.toFloat() - binding.root.width)
                xAnimation?.start()
            }
            runCatching {
                if (sign(velocityY) != sign(e2.rawY - last2Y)) return@runCatching
                yAnimation = flingAnimationOf({
                    params.y = it.toInt()
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }, {
                    params.y.toFloat()
                })
                    .setStartVelocity(velocityY)
                    .setMinValue(0F)
                    .setMaxValue(context.display.height.toFloat() - binding.root.height)
                yAnimation?.start()
            }
            return true
        }
        */

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isMini && !isCollapsed) changeMini()
            else if (!isMini && isCollapsed) changeCollapsed()
            return true
        }
    })
}
