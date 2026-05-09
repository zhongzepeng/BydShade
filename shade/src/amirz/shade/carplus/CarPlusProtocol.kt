package amirz.shade.carplus

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Process
import com.android.launcher3.Utilities
import org.json.JSONArray
import org.json.JSONObject

object CarPlusProtocol {
    const val PREF_PHONE_HOST = "pref_carplus_phone_host"
    const val PREF_PHONE_PORT = "pref_carplus_phone_port"
    const val PREF_PHONE_ADB_PORT = "pref_carplus_phone_adb_port"
    const val PREF_PHONE_NAME = "pref_carplus_phone_name"
    const val PREF_PHONE_SERIAL = "pref_carplus_phone_serial"
    const val PREF_PHONE_USB_SERIAL = "pref_carplus_phone_usb_serial"
    const val PREF_PHONE_DISPLAY_WIDTH = "pref_carplus_phone_display_width"
    const val PREF_PHONE_DISPLAY_HEIGHT = "pref_carplus_phone_display_height"
    const val PREF_PHONE_DISPLAY_DENSITY_DPI = "pref_carplus_phone_display_density_dpi"

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 17888
    const val DEFAULT_DISCOVERY_PORT = 17889
    const val DEFAULT_ADB_PORT = 5555

    const val TYPE_HELLO = "hello"
    const val TYPE_LIST_APPS = "list_apps"
    const val TYPE_APP_LIST = "app_list"
    const val TYPE_GET_ICON = "get_icon"
    const val TYPE_APP_ICON = "app_icon"
    const val TYPE_LAUNCH_APP = "launch_app"
    const val TYPE_ERROR = "error"
    const val TYPE_DEVICE_ANNOUNCE = "device_announce"

    const val FIELD_TYPE = "type"
    const val FIELD_CLIENT = "client"
    const val FIELD_APPS = "apps"
    const val FIELD_PACKAGE = "packageName"
    const val FIELD_CLASS = "className"
    const val FIELD_LABEL = "label"
    const val FIELD_ICON = "iconBase64"
    const val FIELD_MESSAGE = "message"
    const val FIELD_HOST = "host"
    const val FIELD_WS_PORT = "wsPort"
    const val FIELD_DISCOVERY_PORT = "discoveryPort"
    const val FIELD_ADB_PORT = "adbPort"
    const val FIELD_DEVICE_NAME = "deviceName"
    const val FIELD_DEVICE_SERIAL = "deviceSerial"
    const val FIELD_DISPLAY_WIDTH = "displayWidth"
    const val FIELD_DISPLAY_HEIGHT = "displayHeight"
    const val FIELD_DISPLAY_DENSITY_DPI = "displayDensityDpi"

    fun endpoint(context: Context): Uri {
        val prefs = Utilities.getPrefs(context)
        val host = readStringPreference(prefs, PREF_PHONE_HOST, DEFAULT_HOST)
        val port = readIntPreference(prefs, PREF_PHONE_PORT, DEFAULT_PORT)
        return Uri.parse("ws://$host:$port")
    }

    fun readPhoneHost(prefs: SharedPreferences): String {
        return readStringPreference(prefs, PREF_PHONE_HOST, DEFAULT_HOST)
    }

    fun readPhonePort(prefs: SharedPreferences): Int {
        return readIntPreference(prefs, PREF_PHONE_PORT, DEFAULT_PORT)
    }

    fun readPhoneAdbPort(prefs: SharedPreferences): Int {
        return readIntPreference(prefs, PREF_PHONE_ADB_PORT, DEFAULT_ADB_PORT)
    }

    fun readPhoneUsbSerial(prefs: SharedPreferences): String {
        val value = readStringPreference(prefs, PREF_PHONE_USB_SERIAL, "")
        return value.takeIf(::isDirectUsbSerial).orEmpty()
    }

    fun readPhoneDisplayWidth(prefs: SharedPreferences): Int {
        return readOptionalIntPreference(prefs, PREF_PHONE_DISPLAY_WIDTH)
    }

    fun readPhoneDisplayHeight(prefs: SharedPreferences): Int {
        return readOptionalIntPreference(prefs, PREF_PHONE_DISPLAY_HEIGHT)
    }

    fun readPhoneDisplayDensityDpi(prefs: SharedPreferences): Int {
        return readOptionalIntPreference(prefs, PREF_PHONE_DISPLAY_DENSITY_DPI)
    }

    fun isDirectUsbSerial(value: String): Boolean {
        return value.isNotBlank() &&
            value.none { it.isWhitespace() } &&
            !value.contains(":") &&
            !value.startsWith("adb-") &&
            !value.startsWith("emulator-")
    }

    private fun readStringPreference(
        prefs: SharedPreferences,
        key: String,
        defaultValue: String
    ): String {
        val value = prefs.all[key]
        return when (value) {
            is String -> value.trim().ifBlank { defaultValue }
            is Number -> value.toString()
            else -> defaultValue
        }
    }

    private fun readIntPreference(
        prefs: SharedPreferences,
        key: String,
        defaultValue: Int
    ): Int {
        val value = prefs.all[key]
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: defaultValue
            else -> defaultValue
        }.coerceAtLeast(1)
    }

    private fun readOptionalIntPreference(
        prefs: SharedPreferences,
        key: String
    ): Int {
        val value = prefs.all[key]
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }

    fun hello(clientName: String): String = JSONObject()
        .put(FIELD_TYPE, TYPE_HELLO)
        .put(FIELD_CLIENT, clientName)
        .toString()

    fun listApps(): String = JSONObject()
        .put(FIELD_TYPE, TYPE_LIST_APPS)
        .toString()

    fun launchApp(descriptor: PhoneAppDescriptor): String = JSONObject()
        .put(FIELD_TYPE, TYPE_LAUNCH_APP)
        .put(FIELD_PACKAGE, descriptor.packageName)
        .put(FIELD_CLASS, descriptor.className)
        .toString()

    fun getIcon(descriptor: PhoneAppDescriptor): String = JSONObject()
        .put(FIELD_TYPE, TYPE_GET_ICON)
        .put(FIELD_PACKAGE, descriptor.packageName)
        .put(FIELD_CLASS, descriptor.className)
        .toString()

    fun parseApps(payload: JSONObject): List<PhoneAppDescriptor> {
        val apps = payload.optJSONArray(FIELD_APPS) ?: JSONArray()
        val descriptors = ArrayList<PhoneAppDescriptor>(apps.length())
        for (index in 0 until apps.length()) {
            val app = apps.optJSONObject(index) ?: continue
            descriptors.add(
                PhoneAppDescriptor(
                    packageName = app.optString(FIELD_PACKAGE),
                    className = app.optString(FIELD_CLASS),
                    label = app.optString(FIELD_LABEL),
                    iconBase64 = app.optString(FIELD_ICON)
                )
            )
        }
        return descriptors
    }
}

data class PhoneAppDescriptor(
    val packageName: String,
    val className: String,
    val label: String,
    val iconBase64: String
) {
    val key: String = "$packageName/$className"
    val windowTitle: String = label.ifBlank { packageName }
    val userHandle = Process.myUserHandle()
    val isSyntheticHomeSession: Boolean = packageName.isBlank() && className.isBlank()

    companion object {
        fun homeSession(deviceName: String): PhoneAppDescriptor {
            return PhoneAppDescriptor(
                packageName = "",
                className = "",
                label = if (deviceName.isBlank()) "Phone Home" else "$deviceName Home",
                iconBase64 = ""
            )
        }
    }
}
