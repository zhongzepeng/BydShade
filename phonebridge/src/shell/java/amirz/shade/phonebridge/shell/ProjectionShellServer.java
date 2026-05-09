package amirz.shade.phonebridge.shell;

import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.view.Display;
import android.view.Surface;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentHashMap;

public final class ProjectionShellServer {
    private static final int ANDROID_13 = 33;
    private static final String TRACE_FILE = "/data/local/tmp/projection-shell-server.trace";
    private static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1;
    private static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;

    private final ConcurrentHashMap<Integer, ManagedDisplay> displays = new ConcurrentHashMap<Integer, ManagedDisplay>();
    private volatile int activeDisplayId = -1;

    public static void main(String[] args) throws Exception {
        resetTrace();
        trace("main:entered");
        try {
            ProjectionShellWorkarounds.apply();
            trace("main:workarounds-applied");
            new ProjectionShellServer().runForever();
        } catch (Throwable failure) {
            trace("main:failure:" + failure);
            trace(stackTrace(failure));
            throw failure;
        }
    }

    private void runForever() throws Exception {
        trace("server:binding-socket");
        LocalServerSocket serverSocket = new LocalServerSocket(ProjectionShellProtocol.RPC_SOCKET_NAME);
        trace("server:socket-bound");
        while (true) {
            LocalSocket socket = serverSocket.accept();
            trace("server:client-accepted");
            Thread thread = new Thread(new ClientHandler(socket), "projection-shell-client");
            thread.start();
        }
    }

    private JSONObject handleRequest(JSONObject payload) {
        try {
            String type = payload.optString(ProjectionShellProtocol.FIELD_TYPE);
            if (ProjectionShellProtocol.TYPE_HELLO.equals(type)) {
                return hello().put(ProjectionShellProtocol.FIELD_CLIENT, payload.optString(ProjectionShellProtocol.FIELD_CLIENT));
            }
            if (ProjectionShellProtocol.TYPE_CREATE_DISPLAY.equals(type)) {
                return createDisplay(payload);
            }
            if (ProjectionShellProtocol.TYPE_RELEASE_DISPLAY.equals(type)) {
                return releaseDisplay(payload);
            }
            if (ProjectionShellProtocol.TYPE_OPEN_APP.equals(type)) {
                return openApp(payload);
            }
            if (ProjectionShellProtocol.TYPE_MOVE_TASK.equals(type)) {
                return moveTask(payload);
            }
            if (ProjectionShellProtocol.TYPE_SET_DISPLAY.equals(type)) {
                return setDisplay(payload);
            }
            return error("Unsupported projection request");
        } catch (Throwable failure) {
            return error(failure.getMessage() == null ? "Projection shell request failed" : failure.getMessage());
        }
    }

    private JSONObject hello() throws Exception {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String deviceName = (manufacturer + " " + model).trim();
        if (deviceName.length() == 0) {
            deviceName = Build.MODEL == null ? "Phone" : Build.MODEL;
        }
        return new JSONObject()
                .put(ProjectionShellProtocol.FIELD_TYPE, ProjectionShellProtocol.TYPE_HELLO)
                .put("deviceName", deviceName);
    }

    private JSONObject createDisplay(JSONObject payload) throws Exception {
        trace("request:create-display");
        int width = Math.max(1, payload.optInt(ProjectionShellProtocol.FIELD_WIDTH, 0));
        int height = Math.max(1, payload.optInt(ProjectionShellProtocol.FIELD_HEIGHT, 0));
        int densityDpi = Math.max(1, payload.optInt(ProjectionShellProtocol.FIELD_DENSITY_DPI, 0));
        ManagedDisplay display = ProjectionVirtualDisplayManager.create(width, height, densityDpi);
        displays.put(display.id, display);
        return new JSONObject()
                .put(ProjectionShellProtocol.FIELD_TYPE, ProjectionShellProtocol.TYPE_CREATE_DISPLAY)
                .put(ProjectionShellProtocol.FIELD_DISPLAY_ID, display.id)
                .put(ProjectionShellProtocol.FIELD_WIDTH, display.width)
                .put(ProjectionShellProtocol.FIELD_HEIGHT, display.height)
                .put(ProjectionShellProtocol.FIELD_DENSITY_DPI, display.densityDpi);
    }

    private JSONObject releaseDisplay(JSONObject payload) throws Exception {
        int displayId = payload.optInt(ProjectionShellProtocol.FIELD_DISPLAY_ID, -1);
        ManagedDisplay display = displays.remove(displayId);
        if (display != null) {
            display.release();
        }
        if (activeDisplayId == displayId) {
            activeDisplayId = -1;
        }
        return new JSONObject()
                .put(ProjectionShellProtocol.FIELD_TYPE, ProjectionShellProtocol.TYPE_RELEASE_DISPLAY)
                .put(ProjectionShellProtocol.FIELD_DISPLAY_ID, displayId);
    }

    private JSONObject setDisplay(JSONObject payload) throws Exception {
        int displayId = payload.optInt(ProjectionShellProtocol.FIELD_DISPLAY_ID, -1);
        if (displayId >= 0 && displayId != Display.DEFAULT_DISPLAY && !displays.containsKey(displayId)) {
            return error("Unknown display: " + displayId);
        }
        activeDisplayId = displayId;
        return new JSONObject()
                .put(ProjectionShellProtocol.FIELD_TYPE, ProjectionShellProtocol.TYPE_SET_DISPLAY)
                .put(ProjectionShellProtocol.FIELD_DISPLAY_ID, displayId);
    }

    private JSONObject openApp(JSONObject payload) throws Exception {
        trace("request:open-app");
        String packageName = payload.optString(ProjectionShellProtocol.FIELD_PACKAGE).trim();
        String className = payload.optString(ProjectionShellProtocol.FIELD_CLASS).trim();
        int requestedDisplayId = payload.optInt(ProjectionShellProtocol.FIELD_DISPLAY_ID, -1);
        int displayId = requestedDisplayId >= 0 ? requestedDisplayId : activeDisplayId;
        if (packageName.length() == 0 || className.length() == 0) {
            return error("Package or class is missing");
        }
        String displayFlag = displayId >= 0 ? "--display " + displayId + " " : "";
        String output = runShell("am start " + displayFlag + "-n " + packageName + "/" + className);
        if (containsError(output)) {
            return error(output.length() == 0 ? "Unable to launch " + packageName + "/" + className : output);
        }
        return new JSONObject()
                .put(ProjectionShellProtocol.FIELD_TYPE, ProjectionShellProtocol.TYPE_OPEN_APP)
                .put(ProjectionShellProtocol.FIELD_PACKAGE, packageName)
                .put(ProjectionShellProtocol.FIELD_CLASS, className)
                .put(ProjectionShellProtocol.FIELD_DISPLAY_ID, displayId);
    }

    private JSONObject moveTask(JSONObject payload) throws Exception {
        trace("request:move-task");
        int taskId = payload.optInt(ProjectionShellProtocol.FIELD_TASK_ID, -1);
        int displayId = payload.optInt(ProjectionShellProtocol.FIELD_DISPLAY_ID, -1);
        if (taskId < 0 || displayId < 0) {
            return error("Task id or display id is missing");
        }
        String output = runShell("am display move-stack " + taskId + " " + displayId);
        if (containsError(output)) {
            return error(output.length() == 0 ? "Unable to move task " + taskId + " to display " + displayId : output);
        }
        return new JSONObject()
                .put(ProjectionShellProtocol.FIELD_TYPE, ProjectionShellProtocol.TYPE_MOVE_TASK)
                .put(ProjectionShellProtocol.FIELD_TASK_ID, taskId)
                .put(ProjectionShellProtocol.FIELD_DISPLAY_ID, displayId);
    }

    private JSONObject error(String message) {
        try {
            return new JSONObject()
                    .put(ProjectionShellProtocol.FIELD_TYPE, ProjectionShellProtocol.TYPE_ERROR)
                    .put(ProjectionShellProtocol.FIELD_MESSAGE, message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String runShell(String command) throws Exception {
        Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        String output;
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        try {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            output = builder.toString().trim();
        } finally {
            reader.close();
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(output.length() == 0 ? "Command failed: " + command : output);
        }
        return output;
    }

    private boolean containsError(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        return lower.contains("error:") || lower.contains("exception");
    }

    private static void resetTrace() {
        try {
            PrintWriter writer = new PrintWriter(TRACE_FILE);
            try {
                writer.print("");
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void trace(String message) {
        try {
            FileWriter writer = new FileWriter(TRACE_FILE, true);
            try {
                writer.write(message);
                writer.write('\n');
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }

    private static final class ManagedDisplay {
        final int id;
        final int width;
        final int height;
        final int densityDpi;
        final Surface surface;
        final VirtualDisplay virtualDisplay;

        ManagedDisplay(int id, int width, int height, int densityDpi, Surface surface, VirtualDisplay virtualDisplay) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.surface = surface;
            this.virtualDisplay = virtualDisplay;
        }

        void release() {
            try {
                virtualDisplay.release();
            } finally {
                surface.release();
            }
        }
    }

    private static final class ProjectionVirtualDisplayManager {
        static ManagedDisplay create(int width, int height, int densityDpi) throws Exception {
            Surface surface = MediaCodec.createPersistentInputSurface();
            Class<?> displayManagerClass = Class.forName("android.hardware.display.DisplayManager");
            java.lang.reflect.Constructor<?> constructor = displayManagerClass.getDeclaredConstructor(android.content.Context.class);
            constructor.setAccessible(true);
            Object displayManager = constructor.newInstance(ProjectionShellContext.get());
            java.lang.reflect.Method createVirtualDisplay = displayManagerClass.getMethod(
                    "createVirtualDisplay",
                    String.class,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Surface.class,
                    Integer.TYPE
            );
            VirtualDisplay virtualDisplay = (VirtualDisplay) createVirtualDisplay.invoke(
                    displayManager,
                    "shade-projection",
                    width,
                    height,
                    densityDpi,
                    surface,
                    virtualDisplayFlags()
            );
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                throw new IllegalStateException("Unable to create virtual display");
            }
            return new ManagedDisplay(
                    virtualDisplay.getDisplay().getDisplayId(),
                    width,
                    height,
                    densityDpi,
                    surface,
                    virtualDisplay
            );
        }

        private static int virtualDisplayFlags() {
            int flags = VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
            if (Build.VERSION.SDK_INT >= ANDROID_13) {
                flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED
                        | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                        | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
            }
            return flags;
        }
    }

    private final class ClientHandler implements Runnable, Closeable {
        private final LocalSocket socket;

        ClientHandler(LocalSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                trace("client:handler-start");
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
                try {
                    writer.write(hello().toString());
                    writer.newLine();
                    writer.flush();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        JSONObject response = handleRequest(new JSONObject(line));
                        writer.write(response.toString());
                        writer.newLine();
                        writer.flush();
                    }
                } finally {
                    reader.close();
                    writer.close();
                }
            } catch (Throwable ignored) {
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
