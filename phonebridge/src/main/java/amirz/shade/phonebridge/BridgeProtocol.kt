package amirz.shade.phonebridge

object BridgeProtocol {
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
}
