package amirz.shade.carplus.easycontrol

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import amirz.shade.carplus.CarPlusProtocol
import amirz.shade.carplus.PhoneAppDescriptor
import com.android.launcher3.Utilities
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class ProjectionRpcClient(
    context: Context,
    private val transport: EasycontrolAdbTransport = AdbProcessTransport(context)
) : Closeable {
    private val appContext = context.applicationContext
    private val prefs = Utilities.getPrefs(appContext)
    private val shellServerLauncher = ProjectionShellServerLauncher(appContext, transport)

    fun createDisplay(bounds: Rect, densityDpi: Int): Int {
        val displaySpec = resolveDisplaySpec(bounds, densityDpi)
        val startAt = SystemClock.elapsedRealtime()
        val payload = request(
            CarProjectionProtocol.createDisplay(
                width = displaySpec.width,
                height = displaySpec.height,
                densityDpi = displaySpec.densityDpi
            )
        )
        Log.d(TAG, "createDisplay completed in ${SystemClock.elapsedRealtime() - startAt}ms")
        return payload.optInt(CarProjectionProtocol.FIELD_DISPLAY_ID, -1)
            .takeIf { it >= 0 }
            ?: throw IllegalStateException("Projection RPC did not return a display id")
    }

    fun setDisplay(displayId: Int) {
        val startAt = SystemClock.elapsedRealtime()
        request(CarProjectionProtocol.setDisplay(displayId))
        Log.d(TAG, "setDisplay($displayId) completed in ${SystemClock.elapsedRealtime() - startAt}ms")
    }

    fun openApp(descriptor: PhoneAppDescriptor, displayId: Int) {
        if (descriptor.isSyntheticHomeSession) {
            return
        }
        val startAt = SystemClock.elapsedRealtime()
        request(CarProjectionProtocol.openApp(descriptor, displayId))
        Log.d(
            TAG,
            "openApp(${descriptor.packageName}/${descriptor.className}) completed in " +
                "${SystemClock.elapsedRealtime() - startAt}ms"
        )
    }

    fun releaseDisplay(displayId: Int) {
        request(CarProjectionProtocol.releaseDisplay(displayId))
    }

    override fun close() {
        transport.close()
    }

    private fun request(message: String): JSONObject {
        val serial = resolveSerial()
        transport.connect(serial)
        shellServerLauncher.ensureAvailable(serial)
        repeat(MAX_REQUEST_ATTEMPTS) { attempt ->
            try {
                return requestOnce(message)
            } catch (failure: Throwable) {
                if (attempt == MAX_REQUEST_ATTEMPTS - 1 || !shouldBootstrapShellServer(failure)) {
                    throw failure
                }
                shellServerLauncher.ensureStarted(serial)
            }
        }
        throw IllegalStateException("Projection RPC request failed")
    }

    private fun requestOnce(message: String): JSONObject {
        transport.openLocalAbstract(CarProjectionProtocol.RPC_SOCKET_NAME).use { stream ->
            val reader = BufferedReader(InputStreamReader(stream.input, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(stream.output, Charsets.UTF_8))
            val hello = reader.readLine().orEmpty()
            if (hello.isBlank()) {
                throw IllegalStateException("Projection shell server did not send hello")
            }
            writer.write(CarProjectionProtocol.hello(appContext.packageName))
            writer.newLine()
            writer.flush()
            val helloResponse = JSONObject(reader.readLine().orEmpty().ifBlank { "{}" })
            if (helloResponse.optString(CarProjectionProtocol.FIELD_TYPE) == CarProjectionProtocol.TYPE_ERROR) {
                throw IllegalStateException(helloResponse.optString(CarProjectionProtocol.FIELD_MESSAGE))
            }
            writer.write(message)
            writer.newLine()
            writer.flush()
            val response = reader.readLine().orEmpty()
            if (response.isBlank()) {
                throw IllegalStateException("Projection shell server closed the RPC stream")
            }
            val payload = JSONObject(response.ifBlank { "{}" })
            if (payload.optString(CarProjectionProtocol.FIELD_TYPE) == CarProjectionProtocol.TYPE_ERROR) {
                throw IllegalStateException(payload.optString(CarProjectionProtocol.FIELD_MESSAGE))
            }
            return payload
        }
    }

    private fun resolveSerial(): String {
        val usbSerial = CarPlusProtocol.readPhoneUsbSerial(prefs)
        if (usbSerial.isNotEmpty()) {
            return usbSerial
        }
        val wireless = prefs.getString(CarPlusProtocol.PREF_PHONE_SERIAL, null).orEmpty().trim()
        if (wireless.isNotEmpty()) {
            return wireless
        }
        val host = CarPlusProtocol.readPhoneHost(prefs)
        val adbPort = CarPlusProtocol.readPhoneAdbPort(prefs)
        return "$host:$adbPort"
    }

    private fun shouldBootstrapShellServer(failure: Throwable): Boolean {
        val message = failure.message.orEmpty()
        return message.contains("did not send hello", ignoreCase = true) ||
            message.contains("closed the RPC stream", ignoreCase = true) ||
            message.contains("Connection refused", ignoreCase = true) ||
            message.contains("actively refused", ignoreCase = true) ||
            message.contains("No such file", ignoreCase = true)
    }

    private fun resolveDisplaySpec(bounds: Rect, densityDpi: Int): DisplaySpec {
        val phoneWidth = CarPlusProtocol.readPhoneDisplayWidth(prefs)
        val phoneHeight = CarPlusProtocol.readPhoneDisplayHeight(prefs)
        val phoneDensityDpi = CarPlusProtocol.readPhoneDisplayDensityDpi(prefs)
        var width = bounds.width().coerceAtLeast(1)
        var height = bounds.height().coerceAtLeast(1)
        if (phoneWidth > 0 && phoneHeight > 0) {
            width = phoneWidth
            height = phoneHeight
            val requestedLandscape = bounds.width() > bounds.height()
            val phoneLandscape = width > height
            if (requestedLandscape != phoneLandscape) {
                val swappedWidth = height
                height = width
                width = swappedWidth
            }
        }
        return DisplaySpec(
            width = width,
            height = height,
            densityDpi = phoneDensityDpi.takeIf { it > 0 } ?: densityDpi.coerceAtLeast(1)
        )
    }

    private class ProjectionShellServerLauncher(
        private val context: Context,
        private val transport: EasycontrolAdbTransport
    ) {
        fun ensureAvailable(serial: String) {
            if (!waitForRpcSocket(serial, attempts = 1)) {
                ensureStarted(serial)
            }
        }

        fun ensureStarted(serial: String) {
            val localShellServer = ensureShellServerArtifact()
            transport.shell(serial, "rm", "-f", REMOTE_SHELL_SERVER_PATH)
            pushShellServer(serial, localShellServer)
            val commands = listOf(
                buildString {
                    append("mkdir -p ")
                    append(REMOTE_SHELL_ENV_PATH)
                    append("/dalvik-cache && ")
                    append("env ANDROID_DATA=")
                    append(REMOTE_SHELL_ENV_PATH)
                    append(' ')
                    append("app_process -Djava.class.path=")
                    append(REMOTE_SHELL_SERVER_PATH)
                    append(" / ")
                    append(SERVER_CLASS)
                    append(" >/dev/null 2>&1 </dev/null &")
                },
                buildString {
                    append("mkdir -p ")
                    append(REMOTE_SHELL_ENV_PATH)
                    append("/dalvik-cache && ")
                    append("setsid env ANDROID_DATA=")
                    append(REMOTE_SHELL_ENV_PATH)
                    append(' ')
                    append("app_process -Djava.class.path=")
                    append(REMOTE_SHELL_SERVER_PATH)
                    append(" / ")
                    append(SERVER_CLASS)
                    append(" >/dev/null 2>&1 </dev/null &")
                },
                buildString {
                    append("mkdir -p ")
                    append(REMOTE_SHELL_ENV_PATH)
                    append("/dalvik-cache && ")
                    append("toybox nohup env ANDROID_DATA=")
                    append(REMOTE_SHELL_ENV_PATH)
                    append(' ')
                    append("app_process -Djava.class.path=")
                    append(REMOTE_SHELL_SERVER_PATH)
                    append(" / ")
                    append(SERVER_CLASS)
                    append(" >/dev/null 2>&1 </dev/null &")
                }
            )
            for (command in commands) {
                transport.shell(serial, command)
                if (waitForRpcSocket(serial, RPC_SOCKET_WAIT_ATTEMPTS)) {
                    return
                }
            }
            Thread.sleep(STARTUP_DELAY_MS)
        }

        private fun ensureShellServerArtifact(): File {
            val target = File(context.filesDir, SHELL_SERVER_ASSET_NAME)
            context.assets.open(SHELL_SERVER_ASSET_NAME).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            return target
        }

        private fun pushShellServer(serial: String, shellServer: File) {
            transport.push(serial, shellServer.absolutePath, REMOTE_SHELL_SERVER_PATH)
        }

        private fun waitForRpcSocket(serial: String, attempts: Int): Boolean {
            repeat(attempts.coerceAtLeast(1)) {
                val sockets = transport.shell(serial, "cat", "/proc/net/unix")
                if (sockets.contains("@${CarProjectionProtocol.RPC_SOCKET_NAME}")) {
                    return true
                }
                Thread.sleep(STARTUP_DELAY_MS)
            }
            return false
        }

        private companion object {
            private const val SERVER_CLASS = "amirz.shade.phonebridge.shell.ProjectionShellServer"
            private const val SHELL_SERVER_ASSET_NAME = "projection-shell-server.jar"
            private const val REMOTE_SHELL_SERVER_PATH = "/data/local/tmp/projection-shell-server.jar"
            private const val REMOTE_SHELL_ENV_PATH = "/data/local/tmp/projection-shell-env"
            private const val STARTUP_DELAY_MS = 500L
            private const val RPC_SOCKET_WAIT_ATTEMPTS = 8
        }
    }

    private companion object {
        private const val TAG = "ProjectionRpcClient"
        private const val MAX_REQUEST_ATTEMPTS = 3
    }

    private data class DisplaySpec(
        val width: Int,
        val height: Int,
        val densityDpi: Int
    )
}
