package amirz.shade.carplus.easycontrol

import android.content.SharedPreferences

object EasycontrolPrefs {
    const val DEFAULT_ADB_BINARY = "adb"
    const val PREF_ADB_BINARY = "pref_carplus_adb_binary"
    const val PREF_ADB_SERVER_HOST = "pref_carplus_adb_server_host"
    const val PREF_ADB_SERVER_PORT = "pref_carplus_adb_server_port"

    fun readAdbBinary(prefs: SharedPreferences): String {
        return when (val value = prefs.all[PREF_ADB_BINARY]) {
            is String -> value.trim().ifBlank { DEFAULT_ADB_BINARY }
            else -> DEFAULT_ADB_BINARY
        }
    }

    fun readAdbServerHost(prefs: SharedPreferences, defaultValue: String): String {
        return when (val value = prefs.all[PREF_ADB_SERVER_HOST]) {
            is String -> value.trim().ifBlank { defaultValue }
            else -> defaultValue
        }
    }

    fun readAdbServerPort(prefs: SharedPreferences, defaultValue: Int): Int {
        return when (val value = prefs.all[PREF_ADB_SERVER_PORT]) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: defaultValue
            else -> defaultValue
        }.coerceAtLeast(1)
    }
}
