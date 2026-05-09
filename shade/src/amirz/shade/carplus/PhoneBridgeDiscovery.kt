package amirz.shade.carplus

import android.content.Context
import amirz.shade.carplus.easycontrol.EasycontrolPrefs
import amirz.shade.carplus.scrcpy.EmbeddedAdb
import com.android.launcher3.Utilities
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

data class PhoneBridgeEndpoint(
    val host: String,
    val wsPort: Int,
    val adbPort: Int,
    val deviceName: String,
    val adbServerSerial: String = ""
) {
    val serial: String = "$host:$adbPort"
}

class PhoneBridgeDiscovery(
    private val context: Context,
    private val onEndpointDiscovered: (PhoneBridgeEndpoint) -> Unit
) {
    private val prefs by lazy(LazyThreadSafetyMode.NONE) { Utilities.getPrefs(context) }
    private val adbExecutable by lazy(LazyThreadSafetyMode.NONE) {
        EmbeddedAdb.resolveExecutable(
            context.applicationContext,
            EasycontrolPrefs.readAdbBinary(prefs)
        )
    }
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null
    private var lastAdbProbeAt = 0L
    private val autoStartedUsbSerials = LinkedHashSet<String>()
    @Volatile
    private var authoritativeHostAdbEndpoint: PhoneBridgeEndpoint? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        thread = Thread({ runLoop() }, "phone-bridge-discovery-listener").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        val packetBuffer = ByteArray(2048)
        while (running.get()) {
            try {
                refreshHostAdbEndpoint(force = true)
                val datagramSocket = DatagramSocket(CarPlusProtocol.DEFAULT_DISCOVERY_PORT).apply {
                    soTimeout = 2_000
                    broadcast = true
                    reuseAddress = true
                }
                socket = datagramSocket
                while (running.get()) {
                    val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                    try {
                        datagramSocket.receive(packet)
                        if (authoritativeHostAdbEndpoint != null) {
                            continue
                        }
                        val endpoint = parseEndpoint(packet) ?: continue
                        persistEndpoint(endpoint)
                        onEndpointDiscovered(endpoint)
                    } catch (_: SocketTimeoutException) {
                        refreshHostAdbEndpoint(force = false)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    private fun parseEndpoint(packet: DatagramPacket): PhoneBridgeEndpoint? {
        val payload = try {
            JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
        } catch (_: JSONException) {
            return null
        }
        if (payload.optString(CarPlusProtocol.FIELD_TYPE) != CarPlusProtocol.TYPE_DEVICE_ANNOUNCE) {
            return null
        }
        val host = payload.optString(CarPlusProtocol.FIELD_HOST)
            .ifBlank { packet.address?.hostAddress ?: "" }
        if (host.isBlank()) {
            return null
        }
        val wsPort = payload.optInt(CarPlusProtocol.FIELD_WS_PORT, CarPlusProtocol.DEFAULT_PORT)
        val adbPort = payload.optInt(CarPlusProtocol.FIELD_ADB_PORT, CarPlusProtocol.DEFAULT_ADB_PORT)
        val deviceName = payload.optString(CarPlusProtocol.FIELD_DEVICE_NAME)
        return PhoneBridgeEndpoint(host, wsPort, adbPort, deviceName)
    }

    private fun persistEndpoint(endpoint: PhoneBridgeEndpoint) {
        prefs.edit()
            .putString(CarPlusProtocol.PREF_PHONE_HOST, endpoint.host)
            .putInt(CarPlusProtocol.PREF_PHONE_PORT, endpoint.wsPort)
            .putInt(CarPlusProtocol.PREF_PHONE_ADB_PORT, endpoint.adbPort)
            .putString(CarPlusProtocol.PREF_PHONE_SERIAL, endpoint.serial)
            .putString(CarPlusProtocol.PREF_PHONE_NAME, endpoint.deviceName)
            .apply {
                if (CarPlusProtocol.isDirectUsbSerial(endpoint.adbServerSerial)) {
                    putString(CarPlusProtocol.PREF_PHONE_USB_SERIAL, endpoint.adbServerSerial)
                } else {
                    remove(CarPlusProtocol.PREF_PHONE_USB_SERIAL)
                }
            }
            .apply()
    }

    private fun refreshHostAdbEndpoint(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAdbProbeAt < HOST_ADB_PROBE_INTERVAL_MS) {
            return
        }
        lastAdbProbeAt = now
        val endpoint = probeHostAdbEndpoint()
        authoritativeHostAdbEndpoint = endpoint
        if (endpoint == null) {
            return
        }
        persistEndpoint(endpoint)
        onEndpointDiscovered(endpoint)
    }

    private fun probeHostAdbEndpoint(): PhoneBridgeEndpoint? {
        val devicesOutput = runHostAdbCommand(null, "devices", "-l") ?: return null
        val deviceSerials = parseConnectedDeviceSerials(devicesOutput)
        autoStartedUsbSerials.retainAll(deviceSerials.toSet())
        for (deviceSerial in deviceSerials) {
            maybeStartPhoneBridgeClient(deviceSerial)
            val host = resolveHostForSerial(deviceSerial) ?: continue
            val adbPort = resolveAdbPortForSerial(deviceSerial)
            val deviceName = resolveDeviceNameForSerial(deviceSerial)
            return PhoneBridgeEndpoint(
                host = host,
                wsPort = CarPlusProtocol.DEFAULT_PORT,
                adbPort = adbPort,
                deviceName = deviceName,
                adbServerSerial = deviceSerial
            )
        }
        return null
    }

    private fun parseConnectedDeviceSerials(output: String): List<String> {
        val candidates = LinkedHashSet<Pair<String, String>>()
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices attached") }
            .forEach { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2 || parts[1] != "device") {
                    return@forEach
                }
                val serial = parts[0]
                if (serial.startsWith("emulator-")) {
                    return@forEach
                }
                candidates += serial to line
            }
        return candidates.sortedWith(
            compareBy<Pair<String, String>> {
                when {
                    Regex("(^|\\s)usb:").containsMatchIn(it.second) -> 0
                    !it.first.contains(":") && !it.first.startsWith("adb-") -> 1
                    it.first.startsWith("adb-") -> 2
                    else -> 3
                }
            }.thenBy { it.first }
        ).map { it.first }
    }

    private fun maybeStartPhoneBridgeClient(deviceSerial: String) {
        if (!CarPlusProtocol.isDirectUsbSerial(deviceSerial) || !autoStartedUsbSerials.add(deviceSerial)) {
            return
        }
        runHostAdbCommand(
            deviceSerial,
            "shell",
            "am",
            "start",
            "-n",
            PHONE_BRIDGE_ACTIVITY_COMPONENT
        )
    }

    private fun resolveHostForSerial(deviceSerial: String): String? {
        val candidates = listOf(
            runHostAdbShell(deviceSerial, "getprop", "dhcp.wlan0.ipaddress"),
            runHostAdbShell(deviceSerial, "getprop", "dhcp.eth0.ipaddress"),
            runHostAdbShell(deviceSerial, "ip", "-f", "inet", "addr", "show", "wlan0"),
            runHostAdbShell(deviceSerial, "ip", "-f", "inet", "addr", "show", "eth0"),
            runHostAdbShell(deviceSerial, "ip", "-f", "inet", "addr", "show")
        )
        for (candidate in candidates) {
            val host = extractIpv4(candidate)
            if (!host.isNullOrBlank()) {
                return host
            }
        }
        return null
    }

    private fun resolveAdbPortForSerial(deviceSerial: String): Int {
        val propertyNames = listOf(
            "service.adb.tls.port",
            "service.adb.tcp.port",
            "persist.adb.tcp.port"
        )
        for (propertyName in propertyNames) {
            val value = runHostAdbShell(deviceSerial, "getprop", propertyName)
                ?.trim()
                ?.toIntOrNull()
            if (value != null && value > 0) {
                return value
            }
        }
        return CarPlusProtocol.DEFAULT_ADB_PORT
    }

    private fun resolveDeviceNameForSerial(deviceSerial: String): String {
        val manufacturer = runHostAdbShell(deviceSerial, "getprop", "ro.product.manufacturer")
            .orEmpty()
            .trim()
        val model = runHostAdbShell(deviceSerial, "getprop", "ro.product.marketname")
            .takeUnless { it.isNullOrBlank() }
            ?: runHostAdbShell(deviceSerial, "getprop", "ro.product.model")
        return listOf(manufacturer, model.orEmpty().trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { deviceSerial }
    }

    private fun extractIpv4(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) {
            return null
        }
        val direct = IPV4_REGEX.find(value)?.value
        return direct?.takeIf { it != "127.0.0.1" && !it.startsWith("169.254.") }
    }

    private fun runHostAdbShell(deviceSerial: String, vararg shellArgs: String): String? {
        return runHostAdbCommand(deviceSerial, "shell", *shellArgs)
    }

    private fun runHostAdbCommand(deviceSerial: String?, vararg args: String): String? {
        return try {
            val command = ArrayList<String>(args.size + 8)
            command += adbExecutable
            command += "-H"
            command += EasycontrolPrefs.readAdbServerHost(prefs, DEFAULT_HOST_ADB_SERVER_HOST)
            command += "-P"
            command += EasycontrolPrefs.readAdbServerPort(
                prefs,
                DEFAULT_ADB_SERVER_SERVER_PORT
            ).toString()
            if (!deviceSerial.isNullOrBlank()) {
                command += "-s"
                command += deviceSerial
            }
            command.addAll(args)
            val process = ProcessBuilder(command)
                .directory(File(context.filesDir, "embedded-adb"))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) output else null
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val HOST_ADB_PROBE_INTERVAL_MS = 6_000L
        private const val DEFAULT_HOST_ADB_SERVER_HOST = "10.0.2.2"
        private const val DEFAULT_ADB_SERVER_SERVER_PORT = 5037
        private const val PHONE_BRIDGE_ACTIVITY_COMPONENT =
            "amirz.shade.phonebridge/amirz.shade.phonebridge.PhoneBridgeActivity"
        private val IPV4_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    }
}
