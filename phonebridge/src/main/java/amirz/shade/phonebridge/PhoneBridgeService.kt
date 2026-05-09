package amirz.shade.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService

class PhoneBridgeService : LifecycleService() {
    private var server: PhoneBridgeWebSocketServer? = null
    private var discoveryBroadcaster: PhoneBridgeDiscoveryBroadcaster? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
        startForeground(1, buildNotification())
        server = PhoneBridgeWebSocketServer(applicationContext, BridgeProtocol.DEFAULT_PORT).also {
            it.start()
        }
        discoveryBroadcaster = PhoneBridgeDiscoveryBroadcaster(applicationContext).also {
            it.start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        server = null
        discoveryBroadcaster?.stop()
        discoveryBroadcaster = null
        isRunning = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.phone_bridge_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.phone_bridge_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.phone_bridge_app_name))
            .setContentText(
                getString(
                    R.string.phone_bridge_endpoint,
                    "ws://${NetworkUtils.findIpv4Address()}:${BridgeProtocol.DEFAULT_PORT} / ${ProjectionProtocol.RPC_SOCKET_NAME}"
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "phone_bridge"

        @JvmStatic
        var isRunning: Boolean = false
            private set
    }
}
