package amirz.shade.phonebridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

class PhoneBridgeWebSocketServer(
    private val context: Context,
    port: Int
) : WebSocketServer(InetSocketAddress(port)) {

    init {
        setConnectionLostTimeout(0)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        conn.send(
            bridgeMetadata()
                .put(BridgeProtocol.FIELD_TYPE, BridgeProtocol.TYPE_HELLO)
                .put(BridgeProtocol.FIELD_CLIENT, context.packageName)
                .toString()
        )
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

    override fun onMessage(conn: WebSocket, message: String) {
        val payload = try {
            JSONObject(message)
        } catch (invalid: JSONException) {
            conn.send(
                JSONObject()
                    .put(BridgeProtocol.FIELD_TYPE, BridgeProtocol.TYPE_ERROR)
                    .put(BridgeProtocol.FIELD_MESSAGE, "Malformed request")
                    .toString()
            )
            return
        }
        when (payload.optString(BridgeProtocol.FIELD_TYPE)) {
            BridgeProtocol.TYPE_HELLO,
            BridgeProtocol.TYPE_LIST_APPS -> conn.send(buildAppList())
            BridgeProtocol.TYPE_GET_ICON -> conn.send(
                buildAppIcon(
                    packageName = payload.optString(BridgeProtocol.FIELD_PACKAGE),
                    className = payload.optString(BridgeProtocol.FIELD_CLASS)
                )
            )
            BridgeProtocol.TYPE_LAUNCH_APP -> {
                val packageName = payload.optString(BridgeProtocol.FIELD_PACKAGE)
                val className = payload.optString(BridgeProtocol.FIELD_CLASS)
                if (!launch(packageName, className)) {
                    conn.send(
                        JSONObject()
                            .put(BridgeProtocol.FIELD_TYPE, BridgeProtocol.TYPE_ERROR)
                            .put(BridgeProtocol.FIELD_MESSAGE, "Unable to launch $packageName/$className")
                            .toString()
                    )
                }
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) = Unit

    override fun onStart() {
        setConnectionLostTimeout(0)
    }

    private fun buildAppList(): String {
        val apps = JSONArray()
        for (resolveInfo in queryLauncherActivities()) {
            apps.put(resolveInfo.toJson(context, includeIcon = false))
        }
        return bridgeMetadata()
            .put(BridgeProtocol.FIELD_TYPE, BridgeProtocol.TYPE_APP_LIST)
            .put(BridgeProtocol.FIELD_APPS, apps)
            .toString()
    }

    private fun buildAppIcon(packageName: String, className: String): String {
        val resolveInfo = queryLauncherActivities().firstOrNull {
            it.activityInfo.packageName == packageName && it.activityInfo.name == className
        }
        val payload = bridgeMetadata()
            .put(BridgeProtocol.FIELD_TYPE, BridgeProtocol.TYPE_APP_ICON)
            .put(BridgeProtocol.FIELD_PACKAGE, packageName)
            .put(BridgeProtocol.FIELD_CLASS, className)
        if (resolveInfo != null) {
            payload.put(BridgeProtocol.FIELD_ICON, encodeIcon(resolveInfo.loadIcon(context.packageManager)))
        }
        return payload.toString()
    }

    private fun bridgeMetadata(): JSONObject {
        val adbEndpoint = NetworkUtils.resolveAdbEndpoint()
        val metrics = context.resources.displayMetrics
        return JSONObject()
            .put(BridgeProtocol.FIELD_HOST, adbEndpoint.host)
            .put(BridgeProtocol.FIELD_WS_PORT, BridgeProtocol.DEFAULT_PORT)
            .put(BridgeProtocol.FIELD_ADB_PORT, adbEndpoint.port)
            .put(BridgeProtocol.FIELD_DEVICE_NAME, deviceName())
            .put(BridgeProtocol.FIELD_DEVICE_SERIAL, NetworkUtils.resolveDeviceSerial())
            .put(BridgeProtocol.FIELD_DISPLAY_WIDTH, metrics.widthPixels.coerceAtLeast(1))
            .put(BridgeProtocol.FIELD_DISPLAY_HEIGHT, metrics.heightPixels.coerceAtLeast(1))
            .put(BridgeProtocol.FIELD_DISPLAY_DENSITY_DPI, metrics.densityDpi.coerceAtLeast(1))
    }

    private fun deviceName(): String {
        return listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .map { it.orEmpty().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { android.os.Build.MODEL ?: "Phone" }
    }

    private fun launch(packageName: String, className: String): Boolean {
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            true
        } catch (security: SecurityException) {
            false
        } catch (missing: ActivityNotFoundException) {
            false
        }
    }

    private fun ResolveInfo.toJson(context: Context, includeIcon: Boolean): JSONObject {
        val label = loadLabel(context.packageManager).toString()
        return JSONObject()
            .put(BridgeProtocol.FIELD_PACKAGE, activityInfo.packageName)
            .put(BridgeProtocol.FIELD_CLASS, activityInfo.name)
            .put(BridgeProtocol.FIELD_LABEL, label)
            .apply {
                if (includeIcon) {
                    put(BridgeProtocol.FIELD_ICON, encodeIcon(loadIcon(context.packageManager)))
                }
            }
    }

    private fun encodeIcon(drawable: Drawable): String {
        val bitmap = drawableToBitmap(drawable)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        return when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> {
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE_PX
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE_PX
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    val canvas = Canvas(it)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
        }
    }

    private fun queryLauncherActivities(): List<ResolveInfo> {
        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(queryIntent, 0)
            .sortedBy { it.loadLabel(context.packageManager).toString() }
    }

    private companion object {
        private const val DEFAULT_ICON_SIZE_PX = 256
    }
}
