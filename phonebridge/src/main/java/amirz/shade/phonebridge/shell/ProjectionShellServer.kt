package amirz.shade.phonebridge.shell

import amirz.shade.phonebridge.ProjectionProtocol
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.view.Display
import android.view.Surface
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap

class ProjectionShellServer private constructor() {
    private val displays = ConcurrentHashMap<Int, ManagedDisplay>()

    @Volatile
    private var activeDisplayId: Int = -1

    fun runForever() {
        LocalServerSocket(ProjectionProtocol.RPC_SOCKET_NAME).use { serverSocket ->
            while (true) {
                val socket = serverSocket.accept()
                Thread({ ClientHandler(socket).run() }, "projection-shell-client").start()
            }
        }
    }

    private inner class ClientHandler(
        private val socket: LocalSocket
    ) : Closeable {
        fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                writer.write(hello().toString())
                writer.newLine()
                writer.flush()
                while (true) {
                    val line = reader.readLine() ?: break
                    val request = JSONObject(line)
                    val response = handleRequest(request)
                    writer.write(response.toString())
                    writer.newLine()
                    writer.flush()
                }
            } finally {
                close()
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private fun handleRequest(payload: JSONObject): JSONObject {
        return try {
            when (payload.optString(ProjectionProtocol.FIELD_TYPE)) {
                ProjectionProtocol.TYPE_HELLO -> hello()
                    .put(ProjectionProtocol.FIELD_CLIENT, payload.optString(ProjectionProtocol.FIELD_CLIENT))

                ProjectionProtocol.TYPE_CREATE_DISPLAY -> createDisplay(payload)
                ProjectionProtocol.TYPE_RELEASE_DISPLAY -> releaseDisplay(payload)
                ProjectionProtocol.TYPE_OPEN_APP -> openApp(payload)
                ProjectionProtocol.TYPE_MOVE_TASK -> moveTask(payload)
                ProjectionProtocol.TYPE_SET_DISPLAY -> setDisplay(payload)
                else -> error("Unsupported projection request")
            }
        } catch (failure: Throwable) {
            error(failure.message ?: "Projection shell request failed")
        }
    }

    private fun hello(): JSONObject {
        return JSONObject()
            .put(ProjectionProtocol.FIELD_TYPE, ProjectionProtocol.TYPE_HELLO)
            .put(
                "deviceName",
                listOf(Build.MANUFACTURER, Build.MODEL)
                    .map { it.orEmpty().trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                    .ifBlank { Build.MODEL ?: "Phone" }
            )
    }

    private fun createDisplay(payload: JSONObject): JSONObject {
        val width = payload.optInt(ProjectionProtocol.FIELD_WIDTH, 0).coerceAtLeast(1)
        val height = payload.optInt(ProjectionProtocol.FIELD_HEIGHT, 0).coerceAtLeast(1)
        val densityDpi = payload.optInt(ProjectionProtocol.FIELD_DENSITY_DPI, 0).coerceAtLeast(1)
        val display = ProjectionVirtualDisplayManager.create(width, height, densityDpi)
        displays[display.id] = display
        return JSONObject()
            .put(ProjectionProtocol.FIELD_TYPE, ProjectionProtocol.TYPE_CREATE_DISPLAY)
            .put(ProjectionProtocol.FIELD_DISPLAY_ID, display.id)
            .put(ProjectionProtocol.FIELD_WIDTH, display.width)
            .put(ProjectionProtocol.FIELD_HEIGHT, display.height)
            .put(ProjectionProtocol.FIELD_DENSITY_DPI, display.densityDpi)
    }

    private fun releaseDisplay(payload: JSONObject): JSONObject {
        val displayId = payload.optInt(ProjectionProtocol.FIELD_DISPLAY_ID, -1)
        displays.remove(displayId)?.release()
        if (activeDisplayId == displayId) {
            activeDisplayId = -1
        }
        return JSONObject()
            .put(ProjectionProtocol.FIELD_TYPE, ProjectionProtocol.TYPE_RELEASE_DISPLAY)
            .put(ProjectionProtocol.FIELD_DISPLAY_ID, displayId)
    }

    private fun setDisplay(payload: JSONObject): JSONObject {
        val displayId = payload.optInt(ProjectionProtocol.FIELD_DISPLAY_ID, -1)
        if (displayId >= 0 && displayId != Display.DEFAULT_DISPLAY && !displays.containsKey(displayId)) {
            return error("Unknown display: $displayId")
        }
        activeDisplayId = displayId
        return JSONObject()
            .put(ProjectionProtocol.FIELD_TYPE, ProjectionProtocol.TYPE_SET_DISPLAY)
            .put(ProjectionProtocol.FIELD_DISPLAY_ID, displayId)
    }

    private fun openApp(payload: JSONObject): JSONObject {
        val packageName = payload.optString(ProjectionProtocol.FIELD_PACKAGE).trim()
        val className = payload.optString(ProjectionProtocol.FIELD_CLASS).trim()
        val requestedDisplayId = payload.optInt(ProjectionProtocol.FIELD_DISPLAY_ID, -1)
        val displayId = if (requestedDisplayId >= 0) requestedDisplayId else activeDisplayId
        if (packageName.isEmpty() || className.isEmpty()) {
            return error("Package or class is missing")
        }
        val displayFlag = if (displayId >= 0) "--display $displayId " else ""
        val output = runShell("am start ${displayFlag}-n $packageName/$className")
        if (output.contains("Error:", ignoreCase = true) || output.contains("Exception", ignoreCase = true)) {
            return error(output.ifBlank { "Unable to launch $packageName/$className" })
        }
        return JSONObject()
            .put(ProjectionProtocol.FIELD_TYPE, ProjectionProtocol.TYPE_OPEN_APP)
            .put(ProjectionProtocol.FIELD_PACKAGE, packageName)
            .put(ProjectionProtocol.FIELD_CLASS, className)
            .put(ProjectionProtocol.FIELD_DISPLAY_ID, displayId)
    }

    private fun moveTask(payload: JSONObject): JSONObject {
        val taskId = payload.optInt(ProjectionProtocol.FIELD_TASK_ID, -1)
        val displayId = payload.optInt(ProjectionProtocol.FIELD_DISPLAY_ID, -1)
        if (taskId < 0 || displayId < 0) {
            return error("Task id or display id is missing")
        }
        val output = runShell("am display move-stack $taskId $displayId")
        if (output.contains("Error:", ignoreCase = true) || output.contains("Exception", ignoreCase = true)) {
            return error(output.ifBlank { "Unable to move task $taskId to display $displayId" })
        }
        return JSONObject()
            .put(ProjectionProtocol.FIELD_TYPE, ProjectionProtocol.TYPE_MOVE_TASK)
            .put(ProjectionProtocol.FIELD_TASK_ID, taskId)
            .put(ProjectionProtocol.FIELD_DISPLAY_ID, displayId)
    }

    private fun error(message: String): JSONObject {
        return JSONObject()
            .put(ProjectionProtocol.FIELD_TYPE, ProjectionProtocol.TYPE_ERROR)
            .put(ProjectionProtocol.FIELD_MESSAGE, message)
    }

    private fun runShell(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(output.ifBlank { "Command failed: $command" })
        }
        return output
    }

    private data class ManagedDisplay(
        val id: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val surface: Surface,
        val virtualDisplay: VirtualDisplay
    ) {
        fun release() {
            try {
                virtualDisplay.release()
            } finally {
                surface.release()
            }
        }
    }

    private object ProjectionVirtualDisplayManager {
        fun create(width: Int, height: Int, densityDpi: Int): ManagedDisplay {
            val surface = MediaCodec.createPersistentInputSurface()
            val displayManagerClass = Class.forName("android.hardware.display.DisplayManager")
            val constructor = displayManagerClass.getDeclaredConstructor(android.content.Context::class.java)
            constructor.isAccessible = true
            val displayManager = constructor.newInstance(ProjectionShellContext.instance)
            val createVirtualDisplay = displayManagerClass.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType
            )
            val virtualDisplay = createVirtualDisplay.invoke(
                displayManager,
                "shade-projection",
                width,
                height,
                densityDpi,
                surface,
                virtualDisplayFlags()
            ) as? VirtualDisplay ?: throw IllegalStateException("Unable to create virtual display")
            val display = virtualDisplay.display
                ?: throw IllegalStateException("Virtual display has no display")
            return ManagedDisplay(
                id = display.displayId,
                width = width,
                height = height,
                densityDpi = densityDpi,
                surface = surface,
                virtualDisplay = virtualDisplay
            )
        }

        private fun virtualDisplayFlags(): Int {
            var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or
                VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (Build.VERSION.SDK_INT >= ANDROID_13) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                    VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
            }
            return flags
        }

        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1
        private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 shl 1
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val ANDROID_13 = 33
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ProjectionShellWorkarounds.apply()
            ProjectionShellServer().runForever()
        }
    }
}
