package amirz.shade.carplus

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import java.lang.ClassCastException
import java.lang.ClassNotFoundException
import java.lang.IllegalAccessException
import java.lang.InstantiationException
import java.lang.NoSuchMethodException
import java.lang.reflect.InvocationTargetException

data class ScrcpySessionConfig(
    val sessionId: String,
    val descriptor: PhoneAppDescriptor,
    val fullscreen: Boolean,
    val windowBounds: Rect
)

interface EmbeddedScrcpyKernel {
    fun startSession(
        context: Context,
        surface: Surface,
        config: ScrcpySessionConfig,
        callback: SessionCallback
    ): RunningSession

    interface RunningSession {
        fun sendTouch(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int)
        fun sendKey(keyCode: Int)
        fun resize(bounds: Rect, fullscreen: Boolean)
        fun stop()
    }

    interface SessionCallback {
        fun onSessionReady()
        fun onSessionError(message: String)
        fun onSessionVideoSizeChanged(width: Int, height: Int) = Unit
    }
}

object EmbeddedScrcpyKernelRegistry {
    private const val TAG = "EmbeddedScrcpyKernel"
    private val implementationClasses = listOf(
        "amirz.shade.carplus.easycontrol.EasycontrolEmbeddedKernel"
    )

    fun load(): EmbeddedScrcpyKernel? {
        for (implementationClass in implementationClasses) {
            val loaded = tryLoad(implementationClass)
            if (loaded != null) {
                return loaded
            }
        }
        return null
    }

    private fun tryLoad(implementationClass: String): EmbeddedScrcpyKernel? {
        return try {
            val clazz = Class.forName(implementationClass)
            clazz.getDeclaredConstructor().newInstance() as EmbeddedScrcpyKernel
        } catch (missing: ClassNotFoundException) {
            Log.w(TAG, "No embedded scrcpy kernel implementation found: $implementationClass")
            null
        } catch (missingCtor: NoSuchMethodException) {
            Log.e(TAG, "Embedded scrcpy kernel has no empty constructor", missingCtor)
            null
        } catch (illegalAccess: IllegalAccessException) {
            Log.e(TAG, "Embedded scrcpy kernel constructor is not accessible", illegalAccess)
            null
        } catch (instantiation: InstantiationException) {
            Log.e(TAG, "Embedded scrcpy kernel could not be instantiated", instantiation)
            null
        } catch (invocation: InvocationTargetException) {
            Log.e(TAG, "Embedded scrcpy kernel constructor failed", invocation)
            null
        } catch (cast: ClassCastException) {
            Log.e(TAG, "Embedded scrcpy kernel does not implement EmbeddedScrcpyKernel", cast)
            null
        }
    }
}
