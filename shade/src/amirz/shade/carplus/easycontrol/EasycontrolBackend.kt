package amirz.shade.carplus.easycontrol

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import amirz.shade.carplus.EmbeddedScrcpyKernel
import amirz.shade.carplus.PhoneAppDescriptor
import amirz.shade.carplus.ScrcpySessionConfig
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

interface EasycontrolAdbTransport : Closeable {
    fun connect(serial: String)
    fun openLocalAbstract(name: String): EasycontrolAdbStream
    fun shell(serial: String, vararg args: String): String
    fun push(serial: String, localPath: String, remotePath: String)
}

interface EasycontrolAdbStream : Closeable {
    val input: InputStream
    val output: OutputStream
}

interface EasycontrolAppFlowBackend {
    fun onSessionOpening(context: Context, config: ScrcpySessionConfig)
    fun onSessionReady(context: Context, config: ScrcpySessionConfig)
    fun onSessionClosed(context: Context, config: ScrcpySessionConfig)
}

interface EasycontrolMirrorBackend {
    fun startSession(
        context: Context,
        surface: Surface,
        config: ScrcpySessionConfig,
        callback: EmbeddedScrcpyKernel.SessionCallback
    ): EmbeddedScrcpyKernel.RunningSession
}

class NoopEasycontrolAppFlowBackend : EasycontrolAppFlowBackend {
    override fun onSessionOpening(context: Context, config: ScrcpySessionConfig) = Unit

    override fun onSessionReady(context: Context, config: ScrcpySessionConfig) = Unit

    override fun onSessionClosed(context: Context, config: ScrcpySessionConfig) = Unit
}

class EasycontrolRpcAppFlowBackend : EasycontrolAppFlowBackend {
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, ProjectionLaunchState>()

    override fun onSessionOpening(context: Context, config: ScrcpySessionConfig) {
        ProjectionRpcClient(context).use { client ->
            val startAt = SystemClock.elapsedRealtime()
            val displayId = client.createDisplay(
                bounds = config.windowBounds,
                densityDpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(1)
            )
            val afterCreateDisplay = SystemClock.elapsedRealtime()
            client.setDisplay(displayId)
            val afterSetDisplay = SystemClock.elapsedRealtime()
            if (!config.descriptor.isSyntheticHomeSession) {
                client.openApp(config.descriptor, displayId)
            }
            val afterOpenApp = SystemClock.elapsedRealtime()
            sessions[config.sessionId] = ProjectionLaunchState(displayId)
            EasycontrolSessionStore.putDisplayId(config.sessionId, displayId)
            Log.d(
                TAG,
                "Session ${config.sessionId} app flow timings: " +
                    "createDisplay=${afterCreateDisplay - startAt}ms, " +
                    "setDisplay=${afterSetDisplay - afterCreateDisplay}ms, " +
                    "openApp=${afterOpenApp - afterSetDisplay}ms, " +
                    "total=${afterOpenApp - startAt}ms"
            )
        }
    }

    override fun onSessionReady(context: Context, config: ScrcpySessionConfig) {
        val state = sessions[config.sessionId] ?: return
        ProjectionRpcClient(context).use { client ->
            client.setDisplay(state.displayId)
        }
    }

    override fun onSessionClosed(context: Context, config: ScrcpySessionConfig) {
        val state = sessions.remove(config.sessionId) ?: return
        try {
            ProjectionRpcClient(context).use { client ->
                client.releaseDisplay(state.displayId)
            }
        } catch (failure: IllegalStateException) {
            Log.w(TAG, "Failed to release display ${state.displayId} for ${config.sessionId}", failure)
        }
        EasycontrolSessionStore.removeDisplayId(config.sessionId)
    }

    private data class ProjectionLaunchState(
        val displayId: Int
    )

    private companion object {
        private const val TAG = "EasycontrolAppFlow"
    }
}

class EasycontrolEmbeddedKernel(
    private val mirrorBackend: EasycontrolMirrorBackend = ProjectionStreamMirrorBackend(),
    private val appFlowBackend: EasycontrolAppFlowBackend = EasycontrolRpcAppFlowBackend()
) : EmbeddedScrcpyKernel {

    override fun startSession(
        context: Context,
        surface: Surface,
        config: ScrcpySessionConfig,
        callback: EmbeddedScrcpyKernel.SessionCallback
    ): EmbeddedScrcpyKernel.RunningSession {
        appFlowBackend.onSessionOpening(context, config)
        val running = mirrorBackend.startSession(
            context = context,
            surface = surface,
            config = config,
            callback = ForwardingSessionCallback(context, config, callback, appFlowBackend)
        )
        return ForwardingRunningSession(context, config, running, appFlowBackend)
    }

    private class ForwardingSessionCallback(
        private val context: Context,
        private val config: ScrcpySessionConfig,
        private val delegate: EmbeddedScrcpyKernel.SessionCallback,
        private val appFlowBackend: EasycontrolAppFlowBackend
    ) : EmbeddedScrcpyKernel.SessionCallback {

        override fun onSessionReady() {
            appFlowBackend.onSessionReady(context, config)
            delegate.onSessionReady()
        }

        override fun onSessionError(message: String) {
            delegate.onSessionError(message)
        }

        override fun onSessionVideoSizeChanged(width: Int, height: Int) {
            delegate.onSessionVideoSizeChanged(width, height)
        }
    }

    private class ForwardingRunningSession(
        private val context: Context,
        private val config: ScrcpySessionConfig,
        private val delegate: EmbeddedScrcpyKernel.RunningSession,
        private val appFlowBackend: EasycontrolAppFlowBackend
    ) : EmbeddedScrcpyKernel.RunningSession {

        override fun sendTouch(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int) {
            delegate.sendTouch(event, surfaceWidth, surfaceHeight)
        }

        override fun sendKey(keyCode: Int) {
            delegate.sendKey(keyCode)
        }

        override fun resize(bounds: Rect, fullscreen: Boolean) {
            delegate.resize(bounds, fullscreen)
        }

        override fun stop() {
            try {
                delegate.stop()
            } finally {
                appFlowBackend.onSessionClosed(context, config)
            }
        }
    }
}
