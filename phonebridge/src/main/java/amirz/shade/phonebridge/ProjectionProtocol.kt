package amirz.shade.phonebridge

object ProjectionProtocol {
    const val RPC_SOCKET_NAME = "shade_projection_rpc"
    const val STREAM_SOCKET_NAME = "shade_projection_stream"
    const val STREAM_VIDEO_SOCKET_NAME = "shade_projection_video"
    const val STREAM_CONTROL_SOCKET_NAME = "shade_projection_control"
    const val SCRCPY_UPSTREAM_SOCKET_NAME = "scrcpy_00000001"

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
}
