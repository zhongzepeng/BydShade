package amirz.shade.carplus.easycontrol

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import amirz.shade.carplus.CarPlusProtocol
import amirz.shade.carplus.EmbeddedScrcpyKernel
import amirz.shade.carplus.ScrcpySessionConfig
import com.android.launcher3.Utilities
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ProjectionStreamMirrorBackend : EasycontrolMirrorBackend {
    override fun startSession(
        context: Context,
        surface: Surface,
        config: ScrcpySessionConfig,
        callback: EmbeddedScrcpyKernel.SessionCallback
    ): EmbeddedScrcpyKernel.RunningSession {
        return Session(context.applicationContext, surface, config, callback).also { it.start() }
    }

    private class Session(
        private val context: Context,
        private val surface: Surface,
        private val config: ScrcpySessionConfig,
        private val callback: EmbeddedScrcpyKernel.SessionCallback
    ) : EmbeddedScrcpyKernel.RunningSession {
        private val prefs = Utilities.getPrefs(context)
        private val stopped = AtomicBoolean(false)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val controlLock = Any()
        private val transport = AdbProcessTransport(context)
        private val launcher = ProjectionServerLauncher(context)
        private var decodeThread: Thread? = null
        private var controlStream: EasycontrolAdbStream? = null
        private var videoStream: EasycontrolAdbStream? = null
        private var controlOutput: BufferedOutputStream? = null
        private var decoder: MediaCodec? = null
        private var remoteWidth = config.windowBounds.width().coerceAtLeast(1)
        private var remoteHeight = config.windowBounds.height().coerceAtLeast(1)
        private var readyDispatched = false
        private var reportedVideoWidth = 0
        private var reportedVideoHeight = 0
        private var sessionStartAt = 0L

        fun start() {
            sessionStartAt = SystemClock.elapsedRealtime()
            decodeThread = Thread({ run() }, "projection-stream-${config.sessionId.hashCode()}").also { it.start() }
        }

        private fun run() {
            try {
                val startAt = sessionStartAt.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
                val serial = resolveSerial()
                val displayId = EasycontrolSessionStore.takeDisplayId(config.sessionId)
                    ?: throw IllegalStateException("Projection display not prepared")
                val scrcpySocketName = CarProjectionProtocol.scrcpyUpstreamSocketName(config.sessionId)
                transport.connect(serial)
                val afterConnect = SystemClock.elapsedRealtime()
                launcher.start(serial, displayId, config.sessionId)
                val afterLauncherStart = SystemClock.elapsedRealtime()
                videoStream = transport.openLocalAbstract(scrcpySocketName)
                controlStream = transport.openLocalAbstract(scrcpySocketName)
                controlOutput = BufferedOutputStream(controlStream!!.output)
                val afterSocketOpen = SystemClock.elapsedRealtime()
                Log.d(
                    TAG,
                    "Session ${config.sessionId} mirror timings: " +
                        "transportConnect=${afterConnect - startAt}ms, " +
                        "serverStart=${afterLauncherStart - afterConnect}ms, " +
                        "socketOpen=${afterSocketOpen - afterLauncherStart}ms"
                )
                startDecoderLoop()
            } catch (failure: Throwable) {
                if (!stopped.get()) {
                    dispatchError(failure.message ?: "Projection stream startup failed")
                }
            } finally {
                stop()
            }
        }

        private fun startDecoderLoop() {
            val input = DataInputStream(BufferedInputStream(videoStream!!.input))
            val codecId = input.readInt()
            val width = input.readInt()
            val height = input.readInt()
            remoteWidth = width.coerceAtLeast(1)
            remoteHeight = height.coerceAtLeast(1)
            dispatchVideoSizeChanged()
            val mimeType = when (codecId) {
                CODEC_ID_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
                CODEC_ID_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
                else -> throw IllegalStateException("Unsupported video codec id: 0x${codecId.toString(16)}")
            }
            decoder = MediaCodec.createDecoderByType(mimeType).apply {
                val format = MediaFormat.createVideoFormat(mimeType, remoteWidth, remoteHeight)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                configure(format, surface, null, 0)
                start()
            }

            while (!stopped.get()) {
                val ptsAndFlags = input.readLong()
                val packetSize = input.readInt()
                if (packetSize <= 0) {
                    continue
                }
                val data = ByteArray(packetSize)
                input.readFully(data)
                queuePacket(data, ptsAndFlags)
                drainDecoder(false)
            }
        }

        private fun queuePacket(data: ByteArray, ptsAndFlags: Long) {
            val codec = decoder ?: return
            val configPacket = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
            val presentationTimeUs = if (configPacket) 0L else ptsAndFlags and PACKET_PTS_MASK
            var inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_MS)
            while (inputBufferIndex < 0 && !stopped.get()) {
                drainDecoder(false)
                inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_MS)
            }
            if (inputBufferIndex < 0) {
                return
            }
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                ?: throw IllegalStateException("Decoder input buffer is null")
            inputBuffer.clear()
            inputBuffer.put(data)
            var bufferFlags = 0
            if (configPacket) {
                bufferFlags = bufferFlags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            }
            codec.queueInputBuffer(inputBufferIndex, 0, data.size, presentationTimeUs, bufferFlags)
        }

        private fun drainDecoder(endOfStream: Boolean) {
            val codec = decoder ?: return
            if (endOfStream) {
                val inputIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_MS)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
            val info = MediaCodec.BufferInfo()
            while (!stopped.get()) {
                when (val outputIndex = codec.dequeueOutputBuffer(info, OUTPUT_BUFFER_TIMEOUT_MS)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        if (format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            remoteWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                            remoteHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                            dispatchVideoSizeChanged()
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        codec.releaseOutputBuffer(outputIndex, true)
                        dispatchReady()
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return
                        }
                    }
                }
            }
        }

        override fun sendTouch(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int) {
            val width = remoteWidth
            val height = remoteHeight
            if (width <= 0 || height <= 0) {
                return
            }
            val actionIndex = event.actionIndex.coerceAtLeast(0)
            if (actionIndex >= event.pointerCount) {
                return
            }
            val payload = ByteArray(32)
            payload[0] = TYPE_INJECT_TOUCH_EVENT.toByte()
            payload[1] = event.actionMasked.toByte()
            writeLong(payload, 2, event.getPointerId(actionIndex).toLong())
            writeInt(payload, 10, (event.getX(actionIndex) * width / surfaceWidth.coerceAtLeast(1)).roundToInt().coerceIn(0, width))
            writeInt(payload, 14, (event.getY(actionIndex) * height / surfaceHeight.coerceAtLeast(1)).roundToInt().coerceIn(0, height))
            writeShort(payload, 18, width)
            writeShort(payload, 20, height)
            writeShort(payload, 22, (event.getPressure(actionIndex).coerceIn(0f, 1f) * 0xFFFF).roundToInt().coerceIn(0, 0xFFFF))
            writeInt(payload, 24, if (event.actionButton != 0) event.actionButton else event.buttonState)
            writeInt(payload, 28, event.buttonState)
            sendControl(payload)
        }

        override fun sendKey(keyCode: Int) {
            sendKeyAction(KeyEvent.ACTION_DOWN, keyCode)
            sendKeyAction(KeyEvent.ACTION_UP, keyCode)
        }

        override fun resize(bounds: android.graphics.Rect, fullscreen: Boolean) = Unit

        override fun stop() {
            if (!stopped.compareAndSet(false, true)) {
                return
            }
            try {
                drainDecoder(true)
            } catch (_: Throwable) {
            }
            closeQuietly(controlOutput)
            closeQuietly(controlStream)
            closeQuietly(videoStream)
            try {
                decoder?.stop()
            } catch (_: Throwable) {
            }
            try {
                decoder?.release()
            } catch (_: Throwable) {
            }
            decoder = null
            launcher.stop()
            transport.close()
        }

        private fun sendKeyAction(action: Int, keyCode: Int) {
            val payload = ByteArray(14)
            payload[0] = TYPE_INJECT_KEYCODE.toByte()
            payload[1] = action.toByte()
            writeInt(payload, 2, keyCode)
            writeInt(payload, 6, 0)
            writeInt(payload, 10, 0)
            sendControl(payload)
        }

        private fun sendControl(packet: ByteArray) {
            synchronized(controlLock) {
                try {
                    val output = controlOutput ?: return
                    output.write(packet)
                    output.flush()
                } catch (_: SocketException) {
                } catch (_: java.io.IOException) {
                }
            }
        }

        private fun dispatchReady() {
            if (readyDispatched) {
                return
            }
            readyDispatched = true
            val startAt = sessionStartAt.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
            Log.d(TAG, "Session ${config.sessionId} first frame ready in ${SystemClock.elapsedRealtime() - startAt}ms")
            mainHandler.post { callback.onSessionReady() }
        }

        private fun dispatchError(message: String) {
            mainHandler.post { callback.onSessionError(message) }
        }

        private fun dispatchVideoSizeChanged() {
            val width = remoteWidth.coerceAtLeast(1)
            val height = remoteHeight.coerceAtLeast(1)
            if (width == reportedVideoWidth && height == reportedVideoHeight) {
                return
            }
            reportedVideoWidth = width
            reportedVideoHeight = height
            mainHandler.post { callback.onSessionVideoSizeChanged(width, height) }
        }

        private fun resolveSerial(): String {
            val usbSerial = CarPlusProtocol.readPhoneUsbSerial(prefs)
            if (usbSerial.isNotEmpty()) {
                return usbSerial
            }
            val wireless = prefs.getString(CarPlusProtocol.PREF_PHONE_SERIAL, null).orEmpty().trim()
            if (wireless.isNotEmpty()) {
                return wireless
            }
            val host = CarPlusProtocol.readPhoneHost(prefs)
            val adbPort = CarPlusProtocol.readPhoneAdbPort(prefs)
            return "$host:$adbPort"
        }

        private fun closeQuietly(closeable: Any?) {
            try {
                when (closeable) {
                    is java.io.Closeable -> closeable.close()
                    is EasycontrolAdbStream -> closeable.close()
                }
            } catch (_: Throwable) {
            }
        }

        private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = (value ushr 24).toByte()
            buffer[offset + 1] = (value ushr 16).toByte()
            buffer[offset + 2] = (value ushr 8).toByte()
            buffer[offset + 3] = value.toByte()
        }

        private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = (value ushr 8).toByte()
            buffer[offset + 1] = value.toByte()
        }

        private fun writeLong(buffer: ByteArray, offset: Int, value: Long) {
            buffer[offset] = (value ushr 56).toByte()
            buffer[offset + 1] = (value ushr 48).toByte()
            buffer[offset + 2] = (value ushr 40).toByte()
            buffer[offset + 3] = (value ushr 32).toByte()
            buffer[offset + 4] = (value ushr 24).toByte()
            buffer[offset + 5] = (value ushr 16).toByte()
            buffer[offset + 6] = (value ushr 8).toByte()
            buffer[offset + 7] = value.toByte()
        }
    }

    private class ProjectionServerLauncher(
        private val context: Context
    ) {
        private val prefs = Utilities.getPrefs(context)
        private val adbExecutable by lazy(LazyThreadSafetyMode.NONE) {
                    amirz.shade.carplus.scrcpy.EmbeddedAdb.resolveExecutable(
                context = context,
                configuredBinary = EasycontrolPrefs.readAdbBinary(prefs)
            )
        }
        private var process: Process? = null

        fun start(serial: String, displayId: Int, sessionId: String) {
            val serverJar = ensureServerJar()
            val scrcpyScid = CarProjectionProtocol.scrcpyScid(sessionId)
            val scrcpySocketName = CarProjectionProtocol.scrcpyUpstreamSocketName(sessionId)
            ensureServerJarOnDevice(serial, serverJar)
            val args = arrayListOf<String>()
            args += adbCommandPrefix(serial)
            args += "shell"
            args += "CLASSPATH=$REMOTE_SERVER_PATH"
            args += "app_process"
            args += "/"
            args += "com.genymobile.scrcpy.Server"
            args += SERVER_VERSION
            args += "scid=$scrcpyScid"
            args += "log_level=info"
            args += "audio=false"
            args += "control=true"
            args += "video=true"
            args += "tunnel_forward=true"
            args += "send_device_meta=false"
            args += "send_frame_meta=true"
            args += "send_dummy_byte=false"
            args += "send_codec_meta=true"
            args += "clipboard_autosync=false"
            args += "power_on=false"
            args += "power_off_on_close=false"
            args += "video_codec=h264"
            args += "video_bit_rate=20000000"
            args += "max_fps=60"
            args += "display_id=$displayId"
            process = ProcessBuilder(args).redirectErrorStream(true).start()
            waitForScrcpySocket(serial, scrcpySocketName)
        }

        fun stop() {
            process?.destroy()
            process = null
        }

        private fun ensureServerJar(): File {
            val target = File(context.filesDir, "scrcpy-server.jar")
            if (target.isFile && target.length() > 0L) {
                return target
            }
            context.assets.open(ASSET_SERVER_NAME).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            return target
        }

        private fun ensureServerJarOnDevice(serial: String, serverJar: File) {
            val localLength = serverJar.length()
            val cachedLength = pushedServerLengths[serial]
            if (cachedLength == localLength && isServerJarReadyOnDevice(serial, localLength)) {
                return
            }
            val startAt = SystemClock.elapsedRealtime()
            runAdb(serial, "push", serverJar.absolutePath, REMOTE_SERVER_PATH)
            pushedServerLengths[serial] = localLength
            Log.d(TAG, "Pushed scrcpy server for $serial in ${SystemClock.elapsedRealtime() - startAt}ms")
        }

        private fun isServerJarReadyOnDevice(serial: String, expectedLength: Long): Boolean {
            return try {
                val output = runAdb(
                    serial,
                    "shell",
                    "sh",
                    "-c",
                    "if [ -f '$REMOTE_SERVER_PATH' ]; then wc -c < '$REMOTE_SERVER_PATH'; else echo missing; fi"
                ).trim()
                output.toLongOrNull() == expectedLength
            } catch (_: Throwable) {
                false
            }
        }

        private fun runAdb(serial: String, vararg args: String): String {
            val process = ProcessBuilder(adbCommandPrefix(serial) + args.toList())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException(output.ifBlank { "adb ${args.joinToString(" ")} failed" })
            }
            return output
        }

        private fun waitForScrcpySocket(serial: String, socketName: String) {
            repeat(SCRCPY_SOCKET_WAIT_ATTEMPTS) {
                val sockets = runAdb(serial, "shell", "cat", "/proc/net/unix")
                if (sockets.contains("@$socketName")) {
                    return
                }
                val running = process?.isAlive ?: false
                if (!running) {
                    val output = process?.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (
                        output.contains("ClassNotFoundException") ||
                        output.contains("could not find class", ignoreCase = true)
                    ) {
                        pushedServerLengths.remove(serial)
                    }
                    throw IllegalStateException(
                        output.ifBlank { "scrcpy server exited before opening $socketName" }
                    )
                }
                Thread.sleep(SCRCPY_SOCKET_WAIT_DELAY_MS)
            }
            throw IllegalStateException("scrcpy server did not open $socketName")
        }

        private fun adbCommandPrefix(serial: String): List<String> {
            val prefix = ArrayList<String>(6)
            prefix += adbExecutable
            val configuredHost = EasycontrolPrefs.readAdbServerHost(
                prefs,
                DEFAULT_HOST_ADB_SERVER_HOST
            ).trim()
            if (configuredHost.isNotEmpty()) {
                prefix += "-H"
                prefix += configuredHost
                prefix += "-P"
                prefix += EasycontrolPrefs.readAdbServerPort(
                    prefs,
                    DEFAULT_ADB_SERVER_PORT
                ).toString()
            }
            prefix += "-s"
            prefix += serial
            return prefix
        }
    }

    companion object {
        private const val TAG = "ProjectionStream"
        private const val CODEC_ID_H264 = 0x68323634
        private const val CODEC_ID_H265 = 0x68323635
        private const val PACKET_FLAG_CONFIG = Long.MIN_VALUE
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 62
        private const val PACKET_PTS_MASK = PACKET_FLAG_KEY_FRAME - 1
        private const val INPUT_BUFFER_TIMEOUT_MS = 10_000L
        private const val OUTPUT_BUFFER_TIMEOUT_MS = 0L
        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val ASSET_SERVER_NAME = "scrcpy-server.jar"
        private const val REMOTE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val SERVER_VERSION = "3.3.4"
        private const val DEFAULT_HOST_ADB_SERVER_HOST = "10.0.2.2"
        private const val DEFAULT_ADB_SERVER_PORT = 5037
        private const val SCRCPY_SOCKET_WAIT_ATTEMPTS = 20
        private const val SCRCPY_SOCKET_WAIT_DELAY_MS = 250L
        private val pushedServerLengths = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
