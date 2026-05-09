package amirz.shade.phonebridge

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class PhoneBridgeDiscoveryBroadcaster(
    private val context: Context
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        thread = Thread({ runLoop() }, "phone-bridge-discovery").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        while (running.get()) {
            try {
                broadcastOnce()
                Thread.sleep(3_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Throwable) {
            }
        }
    }

    private fun broadcastOnce() {
        val adbEndpoint = NetworkUtils.resolveAdbEndpoint()
        val payload = JSONObject()
            .put(BridgeProtocol.FIELD_TYPE, BridgeProtocol.TYPE_DEVICE_ANNOUNCE)
            .put(BridgeProtocol.FIELD_CLIENT, context.packageName)
            .put(BridgeProtocol.FIELD_DEVICE_NAME, Build.MODEL ?: "Android")
            .put(BridgeProtocol.FIELD_DEVICE_SERIAL, NetworkUtils.resolveDeviceSerial())
            .put(BridgeProtocol.FIELD_HOST, adbEndpoint.host)
            .put(BridgeProtocol.FIELD_WS_PORT, BridgeProtocol.DEFAULT_PORT)
            .put(BridgeProtocol.FIELD_DISCOVERY_PORT, BridgeProtocol.DEFAULT_DISCOVERY_PORT)
            .put(BridgeProtocol.FIELD_ADB_PORT, adbEndpoint.port)
            .toString()
            .toByteArray(Charsets.UTF_8)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in NetworkUtils.broadcastTargets()) {
                val packet = DatagramPacket(
                    payload,
                    payload.size,
                    target,
                    BridgeProtocol.DEFAULT_DISCOVERY_PORT
                )
                socket.send(packet)
            }
        }
    }
}
