package amirz.shade.carplus

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import amirz.shade.ShadeLauncher
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR
import java.util.LinkedHashMap

class ScrcpyWindowManager(
    private val launcher: ShadeLauncher
) {
    private val dragLayer = launcher.dragLayer
    private val kernel = EmbeddedScrcpyKernelRegistry.load()
    private val sessions = LinkedHashMap<String, WindowSession>()
    private val density = launcher.resources.displayMetrics.density
    private val prefs = Utilities.getPrefs(launcher)

    fun openWindow(descriptor: PhoneAppDescriptor) {
        sessions[descriptor.key]?.let {
            it.focus()
            return
        }

        val root = ProjectionWindowView(launcher)
        val params = createParams()
        val session = WindowSession(descriptor, root, params)
        sessions[descriptor.key] = session
        root.bind(session)
        dragLayer.addView(root, params)
        root.bringToFront()
    }

    fun closeWindow(key: String) {
        val session = sessions.remove(key) ?: return
        session.stop()
        if (session.root.isAttachedToWindow) {
            dragLayer.removeView(session.root)
        }
    }

    fun destroy() {
        val keys = sessions.keys.toList()
        for (key in keys) {
            closeWindow(key)
        }
    }

    private fun createParams(): FrameLayout.LayoutParams {
        val metrics = DisplayMetrics()
        launcher.windowManager.defaultDisplay.getMetrics(metrics)
        val windowSize = computeWindowSize(
            metrics = metrics,
            videoWidth = CarPlusProtocol.readPhoneDisplayWidth(prefs),
            videoHeight = CarPlusProtocol.readPhoneDisplayHeight(prefs)
        )
        return FrameLayout.LayoutParams(
            windowSize.width,
            windowSize.height,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = (density * 24).toInt()
            topMargin = (density * 96).toInt()
        }
    }

    private fun computeWindowSize(
        metrics: DisplayMetrics,
        videoWidth: Int,
        videoHeight: Int
    ): WindowSize {
        val toolbarHeight = dp(48)
        val minContentWidth = dp(220)
        val minContentHeight = dp(280)
        val maxWindowWidth = (metrics.widthPixels * 0.46f).toInt()
            .coerceAtLeast(minContentWidth)
        val maxWindowHeight = minOf(
            (metrics.heightPixels * 0.82f).toInt(),
            metrics.heightPixels - dp(120)
        ).coerceAtLeast(minContentHeight + toolbarHeight)
        val maxContentHeight = (maxWindowHeight - toolbarHeight).coerceAtLeast(minContentHeight)
        val aspectRatio = when {
            videoWidth > 0 && videoHeight > 0 -> videoWidth.toFloat() / videoHeight.toFloat()
            else -> DEFAULT_PHONE_ASPECT_RATIO
        }
        var contentWidth = maxWindowWidth
        var contentHeight = (contentWidth / aspectRatio).toInt()
        if (contentHeight > maxContentHeight) {
            contentHeight = maxContentHeight
            contentWidth = (contentHeight * aspectRatio).toInt()
        }
        contentWidth = contentWidth.coerceIn(minContentWidth, maxWindowWidth)
        contentHeight = contentHeight.coerceIn(minContentHeight, maxContentHeight)
        return WindowSize(
            width = contentWidth,
            height = contentHeight + toolbarHeight
        )
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private inner class WindowSession(
        val descriptor: PhoneAppDescriptor,
        val root: ProjectionWindowView,
        val params: FrameLayout.LayoutParams
    ) : EmbeddedScrcpyKernel.SessionCallback {

        var runningSession: EmbeddedScrcpyKernel.RunningSession? = null
        var fullscreen = false
        private var launchSequence = 0
        private var starting = false
        private var restoreBounds = Rect(
            params.leftMargin,
            params.topMargin,
            params.leftMargin + params.width,
            params.topMargin + params.height
        )

        fun start(surface: Surface, width: Int, height: Int) {
            if (runningSession != null || starting || kernel == null) {
                root.setStatus(
                    if (kernel == null) {
                        launcher.getString(R.string.carplus_scrcpy_kernel_missing)
                    } else {
                        launcher.getString(R.string.carplus_scrcpy_starting)
                    }
                )
                return
            }

            root.setStatus(launcher.getString(R.string.carplus_scrcpy_starting))
            val bounds = Rect(
                params.leftMargin,
                params.topMargin,
                params.leftMargin + width,
                params.topMargin + height
            )
            val launchId = ++launchSequence
            starting = true
            val config = ScrcpySessionConfig(
                sessionId = descriptor.key,
                descriptor = descriptor,
                fullscreen = fullscreen,
                windowBounds = bounds
            )
            THREAD_POOL_EXECUTOR.execute {
                try {
                    val started = kernel.startSession(
                        context = launcher,
                        surface = surface,
                        config = config,
                        callback = this
                    )
                    MAIN_EXECUTOR.execute {
                        if (launchId != launchSequence || !root.isAttachedToWindow) {
                            started.stop()
                        } else {
                            runningSession = started
                        }
                        starting = false
                    }
                } catch (invalidState: IllegalStateException) {
                    dispatchStartFailure(
                        launchId,
                        invalidState.message ?: launcher.getString(R.string.carplus_scrcpy_start_failed)
                    )
                } catch (unsupported: UnsupportedOperationException) {
                    dispatchStartFailure(
                        launchId,
                        unsupported.message ?: launcher.getString(R.string.carplus_scrcpy_start_failed)
                    )
                }
            }
        }

        fun stop() {
            launchSequence++
            starting = false
            runningSession?.stop()
            runningSession = null
        }

        fun focus() {
            root.bringToFront()
            if (root.isAttachedToWindow) {
                root.layoutParams = params
            }
        }

        fun toggleFullscreen() {
            val metrics = launcher.resources.displayMetrics
            if (fullscreen) {
                params.width = restoreBounds.width()
                params.height = restoreBounds.height()
                params.leftMargin = restoreBounds.left
                params.topMargin = restoreBounds.top
            } else {
                restoreBounds = Rect(
                    params.leftMargin,
                    params.topMargin,
                    params.leftMargin + params.width,
                    params.topMargin + params.height
                )
                params.width = dragLayer.width.takeIf { it > 0 } ?: metrics.widthPixels
                params.height = dragLayer.height.takeIf { it > 0 } ?: metrics.heightPixels
                params.leftMargin = 0
                params.topMargin = 0
            }
            fullscreen = !fullscreen
            root.layoutParams = params
            runningSession?.resize(
                Rect(
                    params.leftMargin,
                    params.topMargin,
                    params.leftMargin + params.width,
                    params.topMargin + params.height
                ),
                fullscreen
            )
        }

        override fun onSessionReady() {
            root.setStatus("")
            if (descriptor.isSyntheticHomeSession) {
                runningSession?.sendKey(KeyEvent.KEYCODE_HOME)
            }
        }

        override fun onSessionError(message: String) {
            root.setStatus(message)
            Toast.makeText(launcher, message, Toast.LENGTH_SHORT).show()
        }

        override fun onSessionVideoSizeChanged(width: Int, height: Int) {
            root.setVideoSize(width, height)
            if (fullscreen || !root.isAttachedToWindow) {
                return
            }
            val metrics = DisplayMetrics()
            launcher.windowManager.defaultDisplay.getMetrics(metrics)
            val windowSize = computeWindowSize(metrics, width, height)
            if (params.width == windowSize.width && params.height == windowSize.height) {
                return
            }
            params.width = windowSize.width
            params.height = windowSize.height
            root.layoutParams = params
        }

        private fun dispatchStartFailure(launchId: Int, message: String) {
            MAIN_EXECUTOR.execute {
                if (launchId != launchSequence) {
                    return@execute
                }
                starting = false
                root.setStatus(message)
                Toast.makeText(launcher, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class ProjectionWindowView(context: Context) : FrameLayout(context) {
        private val titleView = TextView(context)
        private val statusView = TextView(context)
        private val videoFrame = AspectRatioFrameLayout(context)
        private val surfaceView = SurfaceView(context)

        private var session: WindowSession? = null
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragOriginX = 0
        private var dragOriginY = 0

        init {
            setBackgroundColor(Color.argb(235, 17, 17, 17))
            elevation = 24f
            isClickable = true
            isFocusable = true

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(235, 17, 17, 17))
            }

            val toolbar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.argb(255, 28, 28, 28))
                setPadding(dp(12), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER_VERTICAL
            }

            titleView.setTextColor(Color.WHITE)
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val titleParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            toolbar.addView(titleView, titleParams)

            toolbar.addView(controlButton(android.R.drawable.ic_media_previous) {
                dispatchKey(KeyEvent.KEYCODE_BACK)
            })
            toolbar.addView(controlButton(android.R.drawable.ic_menu_view) {
                dispatchKey(KeyEvent.KEYCODE_HOME)
            })
            toolbar.addView(controlButton(android.R.drawable.ic_menu_recent_history) {
                dispatchKey(KeyEvent.KEYCODE_APP_SWITCH)
            })
            toolbar.addView(controlButton(android.R.drawable.ic_menu_crop) {
                session?.toggleFullscreen()
            })
            toolbar.addView(controlButton(android.R.drawable.ic_menu_close_clear_cancel) {
                session?.descriptor?.key?.let(::closeWindow)
            })

            toolbar.setOnTouchListener { _, event ->
                val activeSession = session ?: return@setOnTouchListener false
                updateTouchInterception(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = event.rawX
                        dragStartY = event.rawY
                        dragOriginX = activeSession.params.leftMargin
                        dragOriginY = activeSession.params.topMargin
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        activeSession.params.leftMargin = dragOriginX + (event.rawX - dragStartX).toInt()
                        activeSession.params.topMargin = dragOriginY + (event.rawY - dragStartY).toInt()
                        layoutParams = activeSession.params
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        activeSession.focus()
                        true
                    }
                    else -> false
                }
            }

            val content = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
            }

            videoFrame.addView(
                surfaceView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            content.addView(
                videoFrame,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )

            surfaceView.setZOrderMediaOverlay(true)
            surfaceView.holder.setFormat(PixelFormat.OPAQUE)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    session?.start(holder.surface, videoFrame.width, videoFrame.height)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    session?.runningSession?.resize(
                        Rect(
                            0,
                            0,
                            width.coerceAtLeast(1),
                            height.coerceAtLeast(1)
                        ),
                        session?.fullscreen == true
                    )
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    session?.stop()
                }
            })
            surfaceView.setOnTouchListener { _, event ->
                updateTouchInterception(event)
                val active = session?.runningSession ?: return@setOnTouchListener false
                active.sendTouch(event, surfaceView.width, surfaceView.height)
                true
            }

            statusView.setTextColor(Color.WHITE)
            statusView.setBackgroundColor(Color.argb(160, 0, 0, 0))
            statusView.setPadding(dp(12), dp(8), dp(12), dp(8))
            content.addView(
                statusView,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )

            root.addView(
                toolbar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(
                content,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun bind(session: WindowSession) {
            this.session = session
            titleView.text = session.descriptor.windowTitle
            setVideoSize(
                CarPlusProtocol.readPhoneDisplayWidth(prefs),
                CarPlusProtocol.readPhoneDisplayHeight(prefs)
            )
            setStatus(
                if (kernel == null) {
                    launcher.getString(R.string.carplus_scrcpy_kernel_missing)
                } else {
                    ""
                }
            )
        }

        fun setStatus(message: String) {
            statusView.text = message
            statusView.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        }

        fun setVideoSize(width: Int, height: Int) {
            videoFrame.setVideoSize(width, height)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            updateTouchInterception(ev)
            session?.focus()
            return super.dispatchTouchEvent(ev) || true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            updateTouchInterception(event)
            return true
        }

        private fun controlButton(iconRes: Int, onClick: () -> Unit): ImageButton {
            return ImageButton(context).apply {
                setImageResource(iconRes)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                setOnClickListener { onClick() }
            }
        }

        private fun dispatchKey(keyCode: Int) {
            session?.runningSession?.sendKey(keyCode)
        }

        private fun updateTouchInterception(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_UP -> parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        private fun dp(value: Int): Int = (value * density).toInt()
    }

    private class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {
        private var videoWidth = 0
        private var videoHeight = 0

        fun setVideoSize(width: Int, height: Int) {
            val safeWidth = width.coerceAtLeast(0)
            val safeHeight = height.coerceAtLeast(0)
            if (videoWidth == safeWidth && videoHeight == safeHeight) {
                return
            }
            videoWidth = safeWidth
            videoHeight = safeHeight
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
            val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
            if (maxWidth <= 0 || maxHeight <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val aspectRatio = if (videoWidth > 0 && videoHeight > 0) {
                videoWidth.toFloat() / videoHeight.toFloat()
            } else {
                DEFAULT_PHONE_ASPECT_RATIO
            }
            var measuredWidth = maxWidth
            var measuredHeight = (measuredWidth / aspectRatio).toInt()
            if (measuredHeight > maxHeight) {
                measuredHeight = maxHeight
                measuredWidth = (measuredHeight * aspectRatio).toInt()
            }
            val childWidthSpec = MeasureSpec.makeMeasureSpec(
                measuredWidth.coerceAtLeast(1),
                MeasureSpec.EXACTLY
            )
            val childHeightSpec = MeasureSpec.makeMeasureSpec(
                measuredHeight.coerceAtLeast(1),
                MeasureSpec.EXACTLY
            )
            measureChildren(childWidthSpec, childHeightSpec)
            setMeasuredDimension(measuredWidth.coerceAtLeast(1), measuredHeight.coerceAtLeast(1))
        }
    }

    private data class WindowSize(
        val width: Int,
        val height: Int
    )

    companion object {
        private const val DEFAULT_PHONE_ASPECT_RATIO = 9f / 19.5f
    }
}
