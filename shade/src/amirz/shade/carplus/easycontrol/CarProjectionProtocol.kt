package amirz.shade.carplus.easycontrol

import amirz.shade.carplus.PhoneAppDescriptor
import org.json.JSONObject

object CarProjectionProtocol {
    const val RPC_SOCKET_NAME = "shade_projection_rpc"
    const val STREAM_SOCKET_NAME = "shade_projection_stream"
    const val STREAM_VIDEO_SOCKET_NAME = "shade_projection_video"
    const val STREAM_CONTROL_SOCKET_NAME = "shade_projection_control"

    const val TYPE_HELLO = "projection_hello"
    const val TYPE_LIST_APPS = "projection_list_apps"
    const val TYPE_APP_LIST = "projection_app_list"
    const val TYPE_CREATE_DISPLAY = "projection_create_display"
    const val TYPE_RELEASE_DISPLAY = "projection_release_display"
    const val TYPE_OPEN_APP = "projection_open_app"
    const val TYPE_MOVE_TASK = "projection_move_task"
    const val TYPE_SET_DISPLAY = "projection_set_display"
    const val TYPE_ERROR = "projection_error"

    const val FIELD_TYPE = "type"
    const val FIELD_CLIENT = "client"
    const val FIELD_PACKAGE = "packageName"
    const val FIELD_CLASS = "className"
    const val FIELD_TASK_ID = "taskId"
    const val FIELD_DISPLAY_ID = "displayId"
    const val FIELD_WIDTH = "width"
    const val FIELD_HEIGHT = "height"
    const val FIELD_DENSITY_DPI = "densityDpi"
    const val FIELD_MESSAGE = "message"

    fun hello(clientName: String): String = JSONObject()
        .put(FIELD_TYPE, TYPE_HELLO)
        .put(FIELD_CLIENT, clientName)
        .toString()

    fun listApps(): String = JSONObject()
        .put(FIELD_TYPE, TYPE_LIST_APPS)
        .toString()

    fun createDisplay(width: Int, height: Int, densityDpi: Int): String = JSONObject()
        .put(FIELD_TYPE, TYPE_CREATE_DISPLAY)
        .put(FIELD_WIDTH, width)
        .put(FIELD_HEIGHT, height)
        .put(FIELD_DENSITY_DPI, densityDpi)
        .toString()

    fun releaseDisplay(displayId: Int): String = JSONObject()
        .put(FIELD_TYPE, TYPE_RELEASE_DISPLAY)
        .put(FIELD_DISPLAY_ID, displayId)
        .toString()

    fun openApp(descriptor: PhoneAppDescriptor, displayId: Int): String = JSONObject()
        .put(FIELD_TYPE, TYPE_OPEN_APP)
        .put(FIELD_PACKAGE, descriptor.packageName)
        .put(FIELD_CLASS, descriptor.className)
        .put(FIELD_DISPLAY_ID, displayId)
        .toString()

    fun moveTask(taskId: Int, displayId: Int): String = JSONObject()
        .put(FIELD_TYPE, TYPE_MOVE_TASK)
        .put(FIELD_TASK_ID, taskId)
        .put(FIELD_DISPLAY_ID, displayId)
        .toString()

    fun setDisplay(displayId: Int): String = JSONObject()
        .put(FIELD_TYPE, TYPE_SET_DISPLAY)
        .put(FIELD_DISPLAY_ID, displayId)
        .toString()

    fun scrcpyScid(sessionId: String): String {
        val value = sessionId.hashCode().toLong() and 0x7fffffffL
        return value.toString(16).padStart(8, '0')
    }

    fun scrcpyUpstreamSocketName(sessionId: String): String {
        return "scrcpy_${scrcpyScid(sessionId)}"
    }
}
