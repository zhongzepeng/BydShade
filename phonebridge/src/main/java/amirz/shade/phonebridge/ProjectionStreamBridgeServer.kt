package amirz.shade.phonebridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicBoolean

class ProjectionStreamBridgeServer : Closeable {
    private val running = AtomicBoolean(false)
    private var upstreamServer: LocalServerSocket? = null
    private var videoServer: LocalServerSocket? = null
    private var controlServer: LocalServerSocket? = null
    private var upstreamThread: Thread? = null
    private var videoThread: Thread? = null
    private var controlThread: Thread? = null
    private val lock = Any()

    @Volatile
    private var upstreamVideo: LocalSocket? = null
    @Volatile
    private var upstreamControl: LocalSocket? = null
    @Volatile
    private var downstreamVideo: LocalSocket? = null
    @Volatile
    private var downstreamControl: LocalSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        upstreamServer = LocalServerSocket(ProjectionProtocol.SCRCPY_UPSTREAM_SOCKET_NAME)
        videoServer = LocalServerSocket(ProjectionProtocol.STREAM_VIDEO_SOCKET_NAME)
        controlServer = LocalServerSocket(ProjectionProtocol.STREAM_CONTROL_SOCKET_NAME)
        upstreamThread = Thread({ acceptUpstreamLoop() }, "projection-upstream-accept").also { it.start() }
        videoThread = Thread({ acceptVideoLoop() }, "projection-video-accept").also { it.start() }
        controlThread = Thread({ acceptControlLoop() }, "projection-control-accept").also { it.start() }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        upstreamServer?.close()
        videoServer?.close()
        controlServer?.close()
        upstreamServer = null
        videoServer = null
        controlServer = null
        closeSockets()
        upstreamThread?.interrupt()
        videoThread?.interrupt()
        controlThread?.interrupt()
        upstreamThread = null
        videoThread = null
        controlThread = null
    }

    private fun acceptUpstreamLoop() {
        while (running.get()) {
            val server = upstreamServer ?: break
            val first = try {
                server.accept()
            } catch (_: Throwable) {
                null
            } ?: continue
            val second = try {
                server.accept()
            } catch (_: Throwable) {
                closeQuietly(first)
                null
            } ?: continue
            synchronized(lock) {
                closeQuietly(upstreamVideo)
                closeQuietly(upstreamControl)
                upstreamVideo = first
                upstreamControl = second
                bridgeLocked()
            }
        }
    }

    private fun acceptVideoLoop() {
        while (running.get()) {
            val server = videoServer ?: break
            val socket = try {
                server.accept()
            } catch (_: Throwable) {
                null
            } ?: continue
            synchronized(lock) {
                closeQuietly(downstreamVideo)
                downstreamVideo = socket
                bridgeLocked()
            }
        }
    }

    private fun acceptControlLoop() {
        while (running.get()) {
            val server = controlServer ?: break
            val socket = try {
                server.accept()
            } catch (_: Throwable) {
                null
            } ?: continue
            synchronized(lock) {
                closeQuietly(downstreamControl)
                downstreamControl = socket
                bridgeLocked()
            }
        }
    }

    private fun bridgeLocked() {
        val firstUpstream = upstreamVideo ?: return
        val secondUpstream = upstreamControl ?: return
        val targetVideo = downstreamVideo ?: return
        val targetControl = downstreamControl ?: return

        val (sourceVideo, sourceControl) = resolveUpstreamPair(firstUpstream, secondUpstream)
        val bridgedVideo = sourceVideo
        val bridgedControl = sourceControl
        val bridgedTargetVideo = targetVideo
        val bridgedTargetControl = targetControl

        upstreamVideo = null
        upstreamControl = null
        downstreamVideo = null
        downstreamControl = null

        startPipe(bridgedVideo.inputStream, bridgedTargetVideo.outputStream, bridgedVideo, bridgedTargetVideo)
        startPipe(bridgedTargetControl.inputStream, bridgedControl.outputStream, bridgedTargetControl, bridgedControl)
    }

    private fun resolveUpstreamPair(
        first: LocalSocket,
        second: LocalSocket
    ): Pair<LocalSocket, LocalSocket> {
        if (waitForData(first)) {
            return first to second
        }
        if (waitForData(second)) {
            return second to first
        }
        return first to second
    }

    private fun waitForData(socket: LocalSocket): Boolean {
        val input = try {
            BufferedInputStream(socket.inputStream)
        } catch (_: Throwable) {
            return false
        }
        val deadline = System.currentTimeMillis() + UPSTREAM_IDENTIFY_TIMEOUT_MS
        while (running.get() && System.currentTimeMillis() < deadline) {
            try {
                if (input.available() > 0) {
                    return true
                }
            } catch (_: Throwable) {
                return false
            }
            try {
                Thread.sleep(UPSTREAM_IDENTIFY_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun startPipe(
        input: InputStream,
        output: OutputStream,
        first: LocalSocket,
        second: LocalSocket
    ) {
        Thread(
            {
                val buffer = ByteArray(16 * 1024)
                try {
                    while (running.get()) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                } catch (_: Throwable) {
                } finally {
                    closeQuietly(first)
                    closeQuietly(second)
                }
            },
            "projection-stream-pipe"
        ).start()
    }

    private fun closeSockets() {
        closeQuietly(upstreamVideo)
        closeQuietly(upstreamControl)
        closeQuietly(downstreamVideo)
        closeQuietly(downstreamControl)
        upstreamVideo = null
        upstreamControl = null
        downstreamVideo = null
        downstreamControl = null
    }

    private fun closeQuietly(socket: LocalSocket?) {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val UPSTREAM_IDENTIFY_TIMEOUT_MS = 1_500L
        private const val UPSTREAM_IDENTIFY_POLL_MS = 25L
    }
}
