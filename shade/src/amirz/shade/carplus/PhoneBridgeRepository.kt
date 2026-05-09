package amirz.shade.carplus

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.launcher3.Utilities
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArraySet

object PhoneBridgeRepository {
    private const val TAG = "PhoneBridgeRepository"
    private const val RECONNECT_DELAY_MS = 3_000L
    private const val APP_LIST_RETRY_DELAY_MS = 2_000L
    private const val APP_LIST_REQUEST_INTERVAL_MS = 750L
    private const val APP_LIST_CACHE_FILE_NAME = "phone-bridge-app-cache.json"
    private const val ICON_CACHE_FILE_NAME = "phone-bridge-icon-cache.json"

    interface Listener {
        fun onPhoneAppsChanged(apps: List<RemotePhoneAppInfo>)
        fun onBridgeConnectionChanged(connected: Boolean) = Unit
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val appListRefreshRunnable = Runnable {
        appListRefreshPosted = false
        ensureAppListAvailable()
    }

    private lateinit var appContext: Context
    private var client: PhoneBridgeClient? = null
    private var discovery: PhoneBridgeDiscovery? = null
    private var adbTunnel: PhoneBridgeAdbTunnel? = null
    private var reconnectPosted = false
    private var appListRefreshPosted = false
    private var desiredConnection = false
    private var connected = false
    private var lastAppListRequestAt = 0L
    private var currentDescriptors: List<PhoneAppDescriptor> = emptyList()
    private var cachedDescriptors: List<PhoneAppDescriptor> = emptyList()
    private val iconCache = LinkedHashMap<String, String>()
    private val pendingIconQueue = ArrayDeque<PhoneAppDescriptor>()
    private var iconRequestInFlightKey: String? = null

    @Volatile
    private var currentApps: List<RemotePhoneAppInfo> = emptyList()

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            loadIconCache()
            loadAppCache()
        }
        if (adbTunnel == null) {
            adbTunnel = PhoneBridgeAdbTunnel(appContext)
        }
        if (discovery == null) {
            discovery = PhoneBridgeDiscovery(appContext, ::onEndpointDiscovered).also { it.start() }
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onPhoneAppsChanged(currentApps)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun connect() {
        desiredConnection = true
        if (!::appContext.isInitialized || client != null) {
            return
        }
        val uri = resolveConnectionUri()
        if (uri == null) {
            scheduleReconnect()
            return
        }
        client = PhoneBridgeClient(appContext, uri).also { it.connect() }
    }

    fun disconnect() {
        desiredConnection = false
        PhoneBridgeRepository.reconnectPosted = false
        cancelAppListRefresh()
        PhoneBridgeRepository.client?.close()
        PhoneBridgeRepository.client = null
        adbTunnel?.close()
        clearApps()
        setConnected(false)
        discovery?.stop()
        discovery = null
    }

    fun launch(descriptor: PhoneAppDescriptor) {
        client?.send(CarPlusProtocol.launchApp(descriptor))
    }

    fun currentRemoteApps(): List<RemotePhoneAppInfo> = currentApps

    private fun publishApps(descriptors: List<PhoneAppDescriptor>) {
        currentDescriptors = descriptors
            .distinctBy { it.key }
            .map { descriptor ->
                val cachedIcon = iconCache[descriptor.key].orEmpty()
                if (descriptor.iconBase64.isBlank() && cachedIcon.isNotBlank()) {
                    descriptor.copy(iconBase64 = cachedIcon)
                } else {
                    descriptor
                }
            }
        cachedDescriptors = currentDescriptors.map { descriptor ->
            descriptor.copy(iconBase64 = "")
        }
        persistAppCache()
        currentApps = currentDescriptors
            .map { RemotePhoneAppInfo.fromDescriptor(appContext, it) }
        cancelAppListRefresh()
        enqueueMissingIcons()
        mainHandler.post {
            for (listener in listeners) {
                listener.onPhoneAppsChanged(currentApps)
            }
        }
    }

    private fun updateConnectionMetadata(payload: JSONObject) {
        if (!::appContext.isInitialized) {
            return
        }
        val deviceName = payload.optString(CarPlusProtocol.FIELD_DEVICE_NAME).trim()
        val host = payload.optString(CarPlusProtocol.FIELD_HOST).trim()
        val wsPort = payload.optInt(CarPlusProtocol.FIELD_WS_PORT, CarPlusProtocol.DEFAULT_PORT)
        val adbPort = payload.optInt(CarPlusProtocol.FIELD_ADB_PORT, CarPlusProtocol.DEFAULT_ADB_PORT)
        val deviceSerial = payload.optString(CarPlusProtocol.FIELD_DEVICE_SERIAL).trim()
        val displayWidth = payload.optInt(CarPlusProtocol.FIELD_DISPLAY_WIDTH, 0).coerceAtLeast(0)
        val displayHeight = payload.optInt(CarPlusProtocol.FIELD_DISPLAY_HEIGHT, 0).coerceAtLeast(0)
        val displayDensityDpi = payload.optInt(CarPlusProtocol.FIELD_DISPLAY_DENSITY_DPI, 0).coerceAtLeast(0)
        val wirelessSerial = if (host.isNotEmpty() && adbPort > 0) "$host:$adbPort" else ""
        if (
            deviceName.isEmpty() &&
            host.isEmpty() &&
            wirelessSerial.isEmpty() &&
            deviceSerial.isEmpty() &&
            displayWidth <= 0 &&
            displayHeight <= 0 &&
            displayDensityDpi <= 0
        ) {
            return
        }
        val prefs = Utilities.getPrefs(appContext)
        prefs.edit().apply {
            if (deviceName.isNotEmpty()) {
                putString(CarPlusProtocol.PREF_PHONE_NAME, deviceName)
            }
            if (host.isNotEmpty()) {
                putString(CarPlusProtocol.PREF_PHONE_HOST, host)
                putInt(CarPlusProtocol.PREF_PHONE_PORT, wsPort)
                putInt(CarPlusProtocol.PREF_PHONE_ADB_PORT, adbPort)
            }
            if (wirelessSerial.isNotEmpty()) {
                putString(CarPlusProtocol.PREF_PHONE_SERIAL, wirelessSerial)
            } else if (deviceSerial.isNotEmpty()) {
                putString(CarPlusProtocol.PREF_PHONE_SERIAL, deviceSerial)
            }
            if (displayWidth > 0) {
                putInt(CarPlusProtocol.PREF_PHONE_DISPLAY_WIDTH, displayWidth)
            }
            if (displayHeight > 0) {
                putInt(CarPlusProtocol.PREF_PHONE_DISPLAY_HEIGHT, displayHeight)
            }
            if (displayDensityDpi > 0) {
                putInt(CarPlusProtocol.PREF_PHONE_DISPLAY_DENSITY_DPI, displayDensityDpi)
            }
        }.apply()
    }

    private fun clearApps() {
        cancelAppListRefresh()
        lastAppListRequestAt = 0L
        currentDescriptors = emptyList()
        pendingIconQueue.clear()
        iconRequestInFlightKey = null
        if (currentApps.isEmpty()) {
            return
        }
        currentApps = emptyList()
        mainHandler.post {
            for (listener in listeners) {
                listener.onPhoneAppsChanged(currentApps)
            }
        }
    }

    private fun restoreCachedApps() {
        if (cachedDescriptors.isEmpty() || currentApps.isNotEmpty()) {
            return
        }
        currentDescriptors = cachedDescriptors.map { descriptor ->
            val cachedIcon = iconCache[descriptor.key].orEmpty()
            if (cachedIcon.isNotBlank()) {
                descriptor.copy(iconBase64 = cachedIcon)
            } else {
                descriptor
            }
        }
        currentApps = currentDescriptors.map { RemotePhoneAppInfo.fromDescriptor(appContext, it) }
        mainHandler.post {
            for (listener in listeners) {
                listener.onPhoneAppsChanged(currentApps)
            }
        }
    }

    private fun onEndpointDiscovered(endpoint: PhoneBridgeEndpoint) {
        if (!::appContext.isInitialized) {
            return
        }
        val prefs = Utilities.getPrefs(appContext)
        val currentHost = CarPlusProtocol.readPhoneHost(prefs)
        val currentPort = CarPlusProtocol.readPhonePort(prefs)
        val currentSerial = prefs.getString(CarPlusProtocol.PREF_PHONE_SERIAL, "")
            .orEmpty()
        val currentUsbSerial = CarPlusProtocol.readPhoneUsbSerial(prefs)
        val endpointUsbSerial = endpoint.adbServerSerial.takeIf(CarPlusProtocol::isDirectUsbSerial).orEmpty()
        val changed = currentHost != endpoint.host ||
            currentPort != endpoint.wsPort ||
            currentSerial != endpoint.serial ||
            currentUsbSerial != endpointUsbSerial
        if (changed && desiredConnection) {
            adbTunnel?.close()
            client?.close()
            client = null
            connect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectPosted || !desiredConnection) {
            return
        }
        reconnectPosted = true
        mainHandler.postDelayed({
            PhoneBridgeRepository.reconnectPosted = false
            PhoneBridgeRepository.client = null
            PhoneBridgeRepository.adbTunnel?.close()
            connect()
        }, RECONNECT_DELAY_MS)
    }

    private fun ensureAppListAvailable(force: Boolean = false) {
        if (!connected) {
            cancelAppListRefresh()
            return
        }
        if (currentApps.isNotEmpty()) {
            cancelAppListRefresh()
            return
        }
        requestAppList(force)
        scheduleAppListRefresh()
    }

    private fun requestAppList(force: Boolean = false) {
        val activeClient = client
        if (activeClient == null || !activeClient.isOpen) {
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastAppListRequestAt < APP_LIST_REQUEST_INTERVAL_MS) {
            return
        }
        lastAppListRequestAt = now
        activeClient.send(CarPlusProtocol.listApps())
    }

    private fun scheduleAppListRefresh() {
        if (appListRefreshPosted || !connected || currentApps.isNotEmpty()) {
            return
        }
        appListRefreshPosted = true
        mainHandler.postDelayed(appListRefreshRunnable, APP_LIST_RETRY_DELAY_MS)
    }

    private fun cancelAppListRefresh() {
        if (!appListRefreshPosted) {
            return
        }
        appListRefreshPosted = false
        mainHandler.removeCallbacks(appListRefreshRunnable)
    }

    private fun enqueueMissingIcons() {
        pendingIconQueue.clear()
        iconRequestInFlightKey = null
        currentDescriptors
            .filter { it.iconBase64.isBlank() }
            .forEach { pendingIconQueue.addLast(it) }
        requestNextIcon()
    }

    private fun requestNextIcon() {
        if (!connected || iconRequestInFlightKey != null) {
            return
        }
        val activeClient = client
        if (activeClient == null || !activeClient.isOpen) {
            return
        }
        while (pendingIconQueue.isNotEmpty()) {
            val descriptor = pendingIconQueue.removeFirst()
            if (iconCache[descriptor.key].isNullOrBlank() && currentDescriptors.any { it.key == descriptor.key }) {
                iconRequestInFlightKey = descriptor.key
                activeClient.send(CarPlusProtocol.getIcon(descriptor))
                return
            }
        }
    }

    private fun applyIconUpdate(payload: JSONObject) {
        val packageName = payload.optString(CarPlusProtocol.FIELD_PACKAGE)
        val className = payload.optString(CarPlusProtocol.FIELD_CLASS)
        val iconBase64 = payload.optString(CarPlusProtocol.FIELD_ICON)
        val key = "$packageName/$className"
        if (iconRequestInFlightKey == key) {
            iconRequestInFlightKey = null
        }
        if (iconBase64.isBlank()) {
            requestNextIcon()
            return
        }
        if (iconCache[key] != iconBase64) {
            iconCache[key] = iconBase64
            persistIconCache()
        }
        val updatedDescriptors = currentDescriptors.map { descriptor ->
            if (descriptor.key == key && descriptor.iconBase64 != iconBase64) {
                descriptor.copy(iconBase64 = iconBase64)
            } else {
                descriptor
            }
        }
        if (updatedDescriptors != currentDescriptors) {
            currentDescriptors = updatedDescriptors
            currentApps = currentDescriptors.map { RemotePhoneAppInfo.fromDescriptor(appContext, it) }
            mainHandler.post {
                for (listener in listeners) {
                    listener.onPhoneAppsChanged(currentApps)
                }
            }
        }
        requestNextIcon()
    }

    private fun loadIconCache() {
        iconCache.clear()
        val file = File(appContext.filesDir, ICON_CACHE_FILE_NAME)
        if (!file.isFile) {
            return
        }
        val payload = try {
            JSONObject(file.readText())
        } catch (_: Throwable) {
            return
        }
        for (key in payload.keys()) {
            val icon = payload.optString(key)
            if (icon.isNotBlank()) {
                iconCache[key] = icon
            }
        }
    }

    private fun persistIconCache() {
        val payload = JSONObject()
        for ((key, value) in iconCache) {
            payload.put(key, value)
        }
        runCatching {
            File(appContext.filesDir, ICON_CACHE_FILE_NAME).writeText(payload.toString())
        }
    }

    private fun loadAppCache() {
        cachedDescriptors = emptyList()
        val file = File(appContext.filesDir, APP_LIST_CACHE_FILE_NAME)
        if (!file.isFile) {
            return
        }
        val payload = try {
            JSONArray(file.readText())
        } catch (_: Throwable) {
            return
        }
        val restored = ArrayList<PhoneAppDescriptor>(payload.length())
        for (index in 0 until payload.length()) {
            val app = payload.optJSONObject(index) ?: continue
            restored += PhoneAppDescriptor(
                packageName = app.optString(CarPlusProtocol.FIELD_PACKAGE),
                className = app.optString(CarPlusProtocol.FIELD_CLASS),
                label = app.optString(CarPlusProtocol.FIELD_LABEL),
                iconBase64 = ""
            )
        }
        cachedDescriptors = restored
    }

    private fun persistAppCache() {
        val payload = JSONArray()
        for (descriptor in cachedDescriptors) {
            payload.put(
                JSONObject()
                    .put(CarPlusProtocol.FIELD_PACKAGE, descriptor.packageName)
                    .put(CarPlusProtocol.FIELD_CLASS, descriptor.className)
                    .put(CarPlusProtocol.FIELD_LABEL, descriptor.label)
            )
        }
        runCatching {
            File(appContext.filesDir, APP_LIST_CACHE_FILE_NAME).writeText(payload.toString())
        }
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) {
            return
        }
        connected = value
        mainHandler.post {
            for (listener in listeners) {
                listener.onBridgeConnectionChanged(value)
            }
        }
    }

    private fun tryBuildUri(endpoint: Uri): URI? {
        return try {
            URI(endpoint.toString())
        } catch (syntax: URISyntaxException) {
            Log.e(TAG, "Invalid WebSocket endpoint: $endpoint", syntax)
            null
        }
    }

    private fun resolveConnectionUri(): URI? {
        val prefs = Utilities.getPrefs(appContext)
        val usbSerial = CarPlusProtocol.readPhoneUsbSerial(prefs)
        val tunnelUri = adbTunnel?.open(CarPlusProtocol.readPhonePort(prefs))
        if (tunnelUri != null) {
            return tryBuildUri(tunnelUri)
        }
        adbTunnel?.close()
        if (usbSerial.isNotEmpty()) {
            Log.w(TAG, "USB serial is available but adb tunnel could not be opened; keeping cached apps and retrying")
            return null
        }
        return tryBuildUri(CarPlusProtocol.endpoint(appContext))
    }

    private class PhoneBridgeClient(
        private val context: Context,
        uri: URI
    ) : WebSocketClient(uri) {
        override fun onOpen(handshakedata: ServerHandshake) {
            setConnectionLostTimeout(0)
            PhoneBridgeRepository.reconnectPosted = false
            PhoneBridgeRepository.setConnected(true)
            PhoneBridgeRepository.restoreCachedApps()
            send(CarPlusProtocol.hello(context.packageName))
            PhoneBridgeRepository.ensureAppListAvailable(force = true)
        }

        override fun onMessage(message: String) {
            val payload = try {
                JSONObject(message)
            } catch (json: JSONException) {
                Log.e(TAG, "Malformed bridge payload: $message", json)
                return
            }
            when (payload.optString(CarPlusProtocol.FIELD_TYPE)) {
                CarPlusProtocol.TYPE_HELLO -> {
                    PhoneBridgeRepository.updateConnectionMetadata(payload)
                    PhoneBridgeRepository.ensureAppListAvailable(force = true)
                }
                CarPlusProtocol.TYPE_LIST_APPS -> PhoneBridgeRepository.ensureAppListAvailable(force = true)
                CarPlusProtocol.TYPE_APP_LIST -> {
                    PhoneBridgeRepository.updateConnectionMetadata(payload)
                    PhoneBridgeRepository.publishApps(CarPlusProtocol.parseApps(payload))
                }
                CarPlusProtocol.TYPE_APP_ICON -> PhoneBridgeRepository.applyIconUpdate(payload)
                CarPlusProtocol.TYPE_ERROR -> Log.e(
                    TAG,
                    "Phone bridge error: ${payload.optString(CarPlusProtocol.FIELD_MESSAGE)}"
                )
            }
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            Log.w(TAG, "Phone bridge closed: $reason ($code)")
            PhoneBridgeRepository.client = null
            PhoneBridgeRepository.setConnected(false)
            PhoneBridgeRepository.scheduleReconnect()
        }

        override fun onError(ex: Exception) {
            Log.e(TAG, "Phone bridge error", ex)
            PhoneBridgeRepository.client = null
            PhoneBridgeRepository.setConnected(false)
            PhoneBridgeRepository.scheduleReconnect()
        }
    }
}
