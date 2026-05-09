package amirz.shade.phonebridge

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale

data class AdbEndpoint(
    val host: String,
    val port: Int
) {
    val serial: String = "$host:$port"
}

object NetworkUtils {
    fun findIpv4Address(): String {
        val candidates = ArrayList<Pair<Int, String>>()
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (networkInterface in interfaces) {
            val interfaceName = networkInterface.name.orEmpty().toLowerCase(Locale.US)
            val addresses = Collections.list(networkInterface.inetAddresses)
            for (address in addresses) {
                val ipv4 = address as? Inet4Address ?: continue
                if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) {
                    continue
                }
                candidates += interfacePriority(interfaceName, ipv4.hostAddress ?: "") to
                    (ipv4.hostAddress ?: "127.0.0.1")
            }
        }
        var best: Pair<Int, String>? = null
        for (candidate in candidates) {
            if (best == null || candidate.first > best!!.first) {
                best = candidate
            }
        }
        return best?.second ?: "127.0.0.1"
    }

    fun resolveAdbEndpoint(): AdbEndpoint {
        val host = findIpv4Address()
        var port: Int? = null
        val propertyNames = listOf(
            "service.adb.tls.port",
            "service.adb.tcp.port",
            "persist.adb.tcp.port"
        )
        for (propertyName in propertyNames) {
            val resolved = readPositiveIntProperty(propertyName)
            if (resolved != null) {
                port = resolved
                break
            }
        }
        val resolvedPort = port ?: BridgeProtocol.DEFAULT_ADB_PORT
        return AdbEndpoint(host, resolvedPort)
    }

    fun resolveDeviceSerial(): String {
        val propertyNames = listOf(
            "ro.serialno",
            "ro.boot.serialno"
        )
        for (propertyName in propertyNames) {
            val value = readSystemProperty(propertyName)
            if (value.isNotBlank() && !value.equals("unknown", ignoreCase = true)) {
                return value
            }
        }
        return ""
    }

    fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")

        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }
            for (interfaceAddress in networkInterface.interfaceAddresses) {
                val broadcast = interfaceAddress.broadcast ?: continue
                if (broadcast is Inet4Address) {
                    targets += broadcast
                }
            }
        }
        return targets.toList()
    }

    private fun interfacePriority(interfaceName: String, host: String): Int {
        var score = 0
        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
            score += 100
        }
        if (interfaceName.startsWith("wlan")) {
            score += 1000
        } else if (interfaceName.startsWith("eth") || interfaceName.startsWith("en")) {
            score += 800
        } else if (interfaceName.startsWith("ap") || interfaceName.startsWith("swlan")) {
            score += 700
        } else if (interfaceName.startsWith("rmnet") || interfaceName.startsWith("ccmni")) {
            score += 100
        }
        return score
    }

    private fun readPositiveIntProperty(name: String): Int? {
        val value = readSystemProperty(name).trim().toIntOrNull() ?: return null
        return value.takeIf { it > 0 }
    }

    private fun readSystemProperty(name: String): String {
        return try {
            val process = ProcessBuilder("getprop", name)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (_: Throwable) {
            ""
        }
    }
}
