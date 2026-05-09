package amirz.shade.carplus

import android.content.Context
import com.android.launcher3.Utilities

data class ScrcpyCommand(
    val executable: String,
    val arguments: List<String>
) {
    fun asProcessArgs(): List<String> = ArrayList<String>(arguments.size + 1).apply {
        add(executable)
        addAll(arguments)
    }
}

object ScrcpyCommandFactory {
    const val PREF_SCRCPY_BINARY = "pref_carplus_scrcpy_binary"
    const val PREF_SCRCPY_BITRATE = "pref_carplus_scrcpy_bitrate"
    const val PREF_SCRCPY_MAX_FPS = "pref_carplus_scrcpy_max_fps"

    private const val DEFAULT_SCRCPY_BINARY = "scrcpy"
    private const val DEFAULT_VIDEO_BITRATE = "20M"
    private const val DEFAULT_MAX_FPS = 60

    fun buildNewDisplayCommand(context: Context, config: ScrcpySessionConfig): ScrcpyCommand {
        val prefs = Utilities.getPrefs(context)
        val executable = prefs.getString(PREF_SCRCPY_BINARY, DEFAULT_SCRCPY_BINARY)
            .orEmpty()
            .ifBlank { DEFAULT_SCRCPY_BINARY }
        val bitrate = prefs.getString(PREF_SCRCPY_BITRATE, DEFAULT_VIDEO_BITRATE)
            .orEmpty()
            .ifBlank { DEFAULT_VIDEO_BITRATE }
        val maxFps = prefs.getInt(PREF_SCRCPY_MAX_FPS, DEFAULT_MAX_FPS)
        val width = config.windowBounds.width().coerceAtLeast(1)
        val height = config.windowBounds.height().coerceAtLeast(1)

        return ScrcpyCommand(
            executable = executable,
            arguments = listOf(
                "--new-display=${width}x$height/240",
                "--start-app=${config.descriptor.packageName}/${config.descriptor.className}",
                "--no-audio",
                "--video-codec=h264",
                "--video-bit-rate=$bitrate",
                "--max-fps=$maxFps",
                "--window-title=${config.descriptor.windowTitle}",
                "--power-off-on-close=false"
            )
        )
    }

    fun buildAttachDisplayCommand(
        context: Context,
        displayId: Int,
        config: ScrcpySessionConfig
    ): ScrcpyCommand {
        val base = buildNewDisplayCommand(context, config)
        return ScrcpyCommand(
            executable = base.executable,
            arguments = listOf("--display=$displayId") + base.arguments.filterNot {
                it.startsWith("--new-display=")
            }
        )
    }
}
