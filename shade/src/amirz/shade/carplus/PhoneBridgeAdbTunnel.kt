package amirz.shade.carplus

import android.content.Context
import android.net.Uri
import android.util.Log
import com.android.launcher3.Utilities
import amirz.shade.carplus.easycontrol.EasycontrolPrefs
import amirz.shade.carplus.scrcpy.EmbeddedAdb
import java.io.Closeable
import java.net.ServerSocket

internal class PhoneBridgeAdbTunnel(
    context: Context
) : Closeable {
    private val appContext = context.applicationContext
    private val prefs = Utilities.getPrefs(appContext)
    private val adbExecutable by lazy(LazyThreadSafetyMode.NONE) {
        EmbeddedAdb.resolveExecutable(
            context = appContext,
            configuredBinary = EasycontrolPrefs.readAdbBinary(prefs)
        )
    }

    private var forwardedSerial: String? = null
    private var forwardedPort: Int? = null

    fun open(remotePort: Int): Uri? {
        val usbSerial = resolveUsbSerial()
        if (usbSerial.isEmpty()) {
            close()
            return null
        }

        close()

        val localPort = reservePort()
        return try {
            runAdb(usbSerial, "forward", "tcp:$localPort", "tcp:$remotePort")
            forwardedSerial = usbSerial
            forwardedPort = localPort
            Log.d(TAG, "Using USB bridge serial=$usbSerial localPort=$localPort remotePort=$remotePort")
            Uri.parse("ws://${resolveForwardHost()}:$localPort")
        } catch (_: Throwable) {
            close()
            null
        }
    }

    override fun close() {
        val serial = forwardedSerial
        val port = forwardedPort
        forwardedSerial = null
        forwardedPort = null
        if (!serial.isNullOrBlank() && port != null) {
            runAdbAllowFailure(serial, "forward", "--remove", "tcp:$port")
        }
    }

    private fun resolveForwardHost(): String {
        return EasycontrolPrefs.readAdbServerHost(
            prefs,
            DEFAULT_HOST_ADB_SERVER_HOST
        ).trim().ifEmpty { DEFAULT_LOCALHOST }
    }

    private fun reservePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private fun runAdb(serial: String, vararg args: String): String {
        val process = ProcessBuilder(adbCommandPrefix(serial) + args.toList())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(output.ifBlank { "adb ${args.joinToString(" ")} failed" })
        }
        return output.trim()
    }

    private fun runAdbAllowFailure(serial: String, vararg args: String) {
        try {
            runAdb(serial, *args)
        } catch (_: Throwable) {
        }
    }

    private fun resolveUsbSerial(): String {
        val devices = runHostAdbDevices().orEmpty()
        val directUsbSerial = devices.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices attached") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2 || parts[1] != "device") {
                    return@mapNotNull null
                }
                parts[0]
            }
            .firstOrNull(CarPlusProtocol::isDirectUsbSerial)
            .orEmpty()

        prefs.edit().apply {
            if (directUsbSerial.isNotEmpty()) {
                putString(CarPlusProtocol.PREF_PHONE_USB_SERIAL, directUsbSerial)
            } else {
                remove(CarPlusProtocol.PREF_PHONE_USB_SERIAL)
            }
        }.apply()

        return directUsbSerial
    }

    private fun runHostAdbDevices(): String? {
        return try {
            val process = ProcessBuilder(
                adbExecutable,
                "-H",
                resolveForwardHost(),
                "-P",
                EasycontrolPrefs.readAdbServerPort(prefs, DEFAULT_ADB_SERVER_PORT).toString(),
                "devices",
                "-l"
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) output else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun adbCommandPrefix(serial: String): List<String> {
        val command = ArrayList<String>(8)
        command += adbExecutable
        command += "-H"
        command += resolveForwardHost()
        command += "-P"
        command += EasycontrolPrefs.readAdbServerPort(prefs, DEFAULT_ADB_SERVER_PORT).toString()
        command += "-s"
        command += serial
        return command
    }

    private companion object {
        private const val TAG = "PhoneBridgeAdbTunnel"
        private const val DEFAULT_HOST_ADB_SERVER_HOST = "10.0.2.2"
        private const val DEFAULT_ADB_SERVER_PORT = 5037
        private const val DEFAULT_LOCALHOST = "127.0.0.1"
    }
}
