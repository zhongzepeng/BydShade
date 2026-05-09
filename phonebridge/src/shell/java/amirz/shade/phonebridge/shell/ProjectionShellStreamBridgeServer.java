package amirz.shade.phonebridge.shell;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class ProjectionShellStreamBridgeServer implements Closeable {
    private static final long UPSTREAM_IDENTIFY_TIMEOUT_MS = 1500L;
    private static final long UPSTREAM_IDENTIFY_POLL_MS = 25L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lock = new Object();

    private LocalServerSocket upstreamServer;
    private LocalServerSocket videoServer;
    private LocalServerSocket controlServer;
    private Thread upstreamThread;
    private Thread videoThread;
    private Thread controlThread;

    private volatile LocalSocket upstreamFirst;
    private volatile LocalSocket upstreamSecond;
    private volatile LocalSocket downstreamVideo;
    private volatile LocalSocket downstreamControl;

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        upstreamServer = new LocalServerSocket(ProjectionShellProtocol.SCRCPY_UPSTREAM_SOCKET_NAME);
        videoServer = new LocalServerSocket(ProjectionShellProtocol.STREAM_VIDEO_SOCKET_NAME);
        controlServer = new LocalServerSocket(ProjectionShellProtocol.STREAM_CONTROL_SOCKET_NAME);
        upstreamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptUpstreamLoop();
            }
        }, "projection-shell-upstream-accept");
        videoThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptVideoLoop();
            }
        }, "projection-shell-video-accept");
        controlThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptControlLoop();
            }
        }, "projection-shell-control-accept");
        upstreamThread.start();
        videoThread.start();
        controlThread.start();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeQuietly(upstreamServer);
        closeQuietly(videoServer);
        closeQuietly(controlServer);
        upstreamServer = null;
        videoServer = null;
        controlServer = null;
        closeSockets();
        interruptQuietly(upstreamThread);
        interruptQuietly(videoThread);
        interruptQuietly(controlThread);
        upstreamThread = null;
        videoThread = null;
        controlThread = null;
    }

    private void acceptUpstreamLoop() {
        while (running.get()) {
            LocalServerSocket server = upstreamServer;
            if (server == null) {
                return;
            }
            LocalSocket first = acceptQuietly(server);
            if (first == null) {
                continue;
            }
            LocalSocket second = acceptQuietly(server);
            if (second == null) {
                closeQuietly(first);
                continue;
            }
            synchronized (lock) {
                closeQuietly(upstreamFirst);
                closeQuietly(upstreamSecond);
                upstreamFirst = first;
                upstreamSecond = second;
                bridgeLocked();
            }
        }
    }

    private void acceptVideoLoop() {
        while (running.get()) {
            LocalServerSocket server = videoServer;
            if (server == null) {
                return;
            }
            LocalSocket socket = acceptQuietly(server);
            if (socket == null) {
                continue;
            }
            synchronized (lock) {
                closeQuietly(downstreamVideo);
                downstreamVideo = socket;
                bridgeLocked();
            }
        }
    }

    private void acceptControlLoop() {
        while (running.get()) {
            LocalServerSocket server = controlServer;
            if (server == null) {
                return;
            }
            LocalSocket socket = acceptQuietly(server);
            if (socket == null) {
                continue;
            }
            synchronized (lock) {
                closeQuietly(downstreamControl);
                downstreamControl = socket;
                bridgeLocked();
            }
        }
    }

    private void bridgeLocked() {
        LocalSocket first = upstreamFirst;
        LocalSocket second = upstreamSecond;
        LocalSocket video = downstreamVideo;
        LocalSocket control = downstreamControl;
        if (first == null || second == null || video == null || control == null) {
            return;
        }

        LocalSocket[] resolved = resolveUpstreamPair(first, second);
        LocalSocket sourceVideo = resolved[0];
        LocalSocket sourceControl = resolved[1];

        upstreamFirst = null;
        upstreamSecond = null;
        downstreamVideo = null;
        downstreamControl = null;

        startPipe(sourceVideo, video, true);
        startPipe(control, sourceControl, false);
    }

    private LocalSocket[] resolveUpstreamPair(LocalSocket first, LocalSocket second) {
        if (waitForData(first)) {
            return new LocalSocket[] { first, second };
        }
        if (waitForData(second)) {
            return new LocalSocket[] { second, first };
        }
        return new LocalSocket[] { first, second };
    }

    private boolean waitForData(LocalSocket socket) {
        InputStream input;
        try {
            input = socket.getInputStream();
        } catch (Throwable failure) {
            return false;
        }
        long deadline = System.currentTimeMillis() + UPSTREAM_IDENTIFY_TIMEOUT_MS;
        while (running.get() && System.currentTimeMillis() < deadline) {
            try {
                if (input.available() > 0) {
                    return true;
                }
            } catch (Throwable failure) {
                return false;
            }
            try {
                Thread.sleep(UPSTREAM_IDENTIFY_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void startPipe(final LocalSocket source, final LocalSocket target, final boolean flushOutput) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[16 * 1024];
                try {
                    InputStream input = source.getInputStream();
                    OutputStream output = target.getOutputStream();
                    while (running.get()) {
                        int read = input.read(buffer);
                        if (read <= 0) {
                            break;
                        }
                        output.write(buffer, 0, read);
                        if (flushOutput) {
                            output.flush();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(source);
                    closeQuietly(target);
                }
            }
        }, "projection-shell-stream-pipe").start();
    }

    private void closeSockets() {
        closeQuietly(upstreamFirst);
        closeQuietly(upstreamSecond);
        closeQuietly(downstreamVideo);
        closeQuietly(downstreamControl);
        upstreamFirst = null;
        upstreamSecond = null;
        downstreamVideo = null;
        downstreamControl = null;
    }

    private static LocalSocket acceptQuietly(LocalServerSocket server) {
        try {
            return server.accept();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static void interruptQuietly(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void closeQuietly(LocalSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(LocalServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
