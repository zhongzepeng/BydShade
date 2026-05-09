package amirz.shade.phonebridge.shell;

final class ProjectionShellProtocol {
    static final String RPC_SOCKET_NAME = "shade_projection_rpc";
    static final String STREAM_VIDEO_SOCKET_NAME = "shade_projection_video";
    static final String STREAM_CONTROL_SOCKET_NAME = "shade_projection_control";
    static final String SCRCPY_UPSTREAM_SOCKET_NAME = "scrcpy_00000001";

    static final String TYPE_HELLO = "projection_hello";
    static final String TYPE_CREATE_DISPLAY = "projection_create_display";
    static final String TYPE_RELEASE_DISPLAY = "projection_release_display";
    static final String TYPE_OPEN_APP = "projection_open_app";
    static final String TYPE_MOVE_TASK = "projection_move_task";
    static final String TYPE_SET_DISPLAY = "projection_set_display";
    static final String TYPE_ERROR = "projection_error";

    static final String FIELD_TYPE = "type";
    static final String FIELD_CLIENT = "client";
    static final String FIELD_PACKAGE = "packageName";
    static final String FIELD_CLASS = "className";
    static final String FIELD_TASK_ID = "taskId";
    static final String FIELD_DISPLAY_ID = "displayId";
    static final String FIELD_WIDTH = "width";
    static final String FIELD_HEIGHT = "height";
    static final String FIELD_DENSITY_DPI = "densityDpi";
    static final String FIELD_MESSAGE = "message";

    private ProjectionShellProtocol() {
    }
}
