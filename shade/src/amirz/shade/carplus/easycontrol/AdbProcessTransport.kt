package amirz.shade.carplus.easycontrol

import android.content.Context
import com.android.launcher3.Utilities
import amirz.shade.carplus.CarPlusProtocol
import amirz.shade.carplus.scrcpy.EmbeddedAdb
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class AdbProcessTransport(
    context: Context
) : EasycontrolAdbTransport {
    private val appContext = context.applicationContext
    private val prefs = Utilities.getPrefs(appContext)
    private val adbExecutable by lazy(LazyThreadSafetyMode.NONE) {
        EmbeddedAdb.resolveExecutable(
            context = appContext,
            configuredBinary = EasycontrolPrefs.readAdbBinary(prefs)
        )
    }
    private var connectedSerial: String? = null

    override fun connect(serial: String) {
        connectedSerial = serial
        if (serial.contains(":")) {
            runAdb(null, "connect", serial)
        }
    }

    override fun openLocalAbstract(name: String): EasycontrolAdbStream {
        val serial = connectedSerial ?: throw IllegalStateException("ADB transport is not connected")
        val forwardedPort = reservePort()
        runAdb(serial, "forward", "tcp:$forwardedPort", "localabstract:$name")
        try {
            val endpoint = resolveForwardEndpoint()
            val socket = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(endpoint.host, forwardedPort), CONNECT_TIMEOUT_MS)
            }
            return SocketAdbStream(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                closeAction = {
                    try {
                        socket.close()
                    } finally {
                        runAdbAllowFailure(serial, "forward", "--remove", "tcp:$forwardedPort")
                    }
                }
            )
        } catch (failure: Throwable) {
            runAdbAllowFailure(serial, "forward", "--remove", "tcp:$forwardedPort")
            throw failure
        }
    }

    override fun shell(serial: String, vararg args: String): String {
        return runAdb(serial, "shell", *args)
    }

    override fun push(serial: String, localPath: String, remotePath: String) {
        runAdb(serial, "push", localPath, remotePath)
    }

    override fun close() {
        connectedSerial = null
    }

    private fun runAdb(serial: String?, vararg args: String): String {
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

    private fun runAdbAllowFailure(serial: String?, vararg args: String) {
        try {
            runAdb(serial, *args)
        } catch (_: Throwable) {
        }
    }

    private fun adbCommandPrefix(serial: String?): List<String> {
        val command = ArrayList<String>(6)
        command += adbExecutable
        resolveAdbServerEndpoint()?.let {
            command += "-H"
            command += it.host
            command += "-P"
            command += it.port.toString()
        }
        if (!serial.isNullOrBlank()) {
            command += "-s"
            command += serial
        }
        return command
    }

    private fun resolveAdbServerEndpoint(): AdbServerEndpoint? {
        val configuredHost = EasycontrolPrefs.readAdbServerHost(
            prefs,
            DEFAULT_HOST_ADB_SERVER_HOST
        )
        if (configuredHost.isEmpty()) {
            return null
        }
        return AdbServerEndpoint(
            host = configuredHost,
            port = EasycontrolPrefs.readAdbServerPort(prefs, DEFAULT_ADB_SERVER_PORT)
        )
    }

    private fun resolveForwardEndpoint(): AdbServerEndpoint {
        val serverEndpoint = resolveAdbServerEndpoint()
        if (serverEndpoint != null) {
            return serverEndpoint
        }
        val host = CarPlusProtocol.readPhoneHost(prefs)
        return AdbServerEndpoint(
            host = if (host == DEFAULT_LOCALHOST || host.isBlank()) DEFAULT_LOCALHOST else DEFAULT_LOCALHOST,
            port = DEFAULT_ADB_SERVER_PORT
        )
    }

    private fun reservePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private data class AdbServerEndpoint(
        val host: String,
        val port: Int
    )

    private class SocketAdbStream(
        override val input: InputStream,
        override val output: OutputStream,
        private val closeAction: () -> Unit
    ) : EasycontrolAdbStream {
        override fun close() = closeAction()
    }

    companion object {
        private const val DEFAULT_HOST_ADB_SERVER_HOST = "10.0.2.2"
        private const val DEFAULT_ADB_SERVER_PORT = 5037
        private const val DEFAULT_LOCALHOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 2_000
    }
}
