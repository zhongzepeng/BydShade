package amirz.shade.carplus.scrcpy

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import amirz.shade.carplus.easycontrol.EasycontrolPrefs
import java.io.File
import java.io.FileOutputStream

object EmbeddedAdb {
    private const val ASSET_ROOT = "embedded-adb"
    private const val INSTALL_ROOT = "embedded-adb"
    private const val INSTALL_VERSION = 1
    private const val VERSION_FILE = ".version"
    private const val ASSET_BINARY_NAME = "adb"
    private const val INSTALLED_BINARY_NAME = "adb-bin"
    private const val WRAPPER_NAME = "adb"

    @Synchronized
    fun resolveExecutable(context: Context, configuredBinary: String): String {
        val configured = configuredBinary.trim()
        if (configured.isNotEmpty() && configured != EasycontrolPrefs.DEFAULT_ADB_BINARY) {
            return configured
        }
        return ensureInstalled(context.applicationContext).wrapper.absolutePath
    }

    private fun ensureInstalled(context: Context): InstallPaths {
        val assets = context.assets
        val abi = selectPackagedAbi(assets)
        val root = File(context.filesDir, INSTALL_ROOT)
        val installDir = File(root, abi)
        val paths = InstallPaths(
            directory = installDir,
            binary = File(installDir, INSTALLED_BINARY_NAME),
            wrapper = File(installDir, WRAPPER_NAME),
            libDir = File(installDir, "lib"),
            version = File(installDir, VERSION_FILE)
        )
        val expectedVersion = "$abi:$INSTALL_VERSION"
        if (paths.isComplete() && paths.version.readText() == expectedVersion) {
            return paths
        }

        installDir.deleteRecursively()
        if (!installDir.mkdirs() && !installDir.isDirectory) {
            throw IllegalStateException("Unable to create embedded adb directory: ${installDir.absolutePath}")
        }

        extractAssetTree(
            assets = assets,
            assetPath = "$ASSET_ROOT/$abi",
            outputRoot = installDir,
            relativePath = ""
        )
        createCompatLibraryCopies(paths.libDir)
        writeWrapper(paths)
        markExecutable(paths.binary)
        markExecutable(paths.wrapper)
        paths.version.writeText(expectedVersion)
        return paths
    }

    private fun selectPackagedAbi(assets: AssetManager): String {
        val packagedAbis = assets.list(ASSET_ROOT)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        if (packagedAbis.isEmpty()) {
            throw UnsupportedOperationException("No embedded adb payloads were packaged into this build.")
        }
        return Build.SUPPORTED_ABIS.firstOrNull { packagedAbis.contains(it) }
            ?: throw UnsupportedOperationException(
                "Embedded adb is not packaged for this device ABI. Supported device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}; packaged: ${packagedAbis.joinToString()}."
            )
    }

    private fun extractAssetTree(
        assets: AssetManager,
        assetPath: String,
        outputRoot: File,
        relativePath: String
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            val destination = outputFile(outputRoot, relativePath)
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childRelativePath = if (relativePath.isEmpty()) child else "$relativePath/$child"
            extractAssetTree(assets, childAssetPath, outputRoot, childRelativePath)
        }
    }

    private fun outputFile(outputRoot: File, relativePath: String): File {
        return if (relativePath == ASSET_BINARY_NAME) {
            File(outputRoot, INSTALLED_BINARY_NAME)
        } else {
            File(outputRoot, relativePath)
        }
    }

    private fun createCompatLibraryCopies(libDir: File) {
        copyIfMissing(File(libDir, "libz.so.1"), libDir.listFiles()?.firstOrNull {
            it.name.startsWith("libz.so.")
        })
        copyIfMissing(File(libDir, "libzstd.so.1"), libDir.listFiles()?.firstOrNull {
            it.name.startsWith("libzstd.so.")
        })
    }

    private fun copyIfMissing(target: File, source: File?) {
        if (target.exists() || source == null || !source.isFile) {
            return
        }
        source.copyTo(target, overwrite = true)
    }

    private fun writeWrapper(paths: InstallPaths) {
        val script = """
            |#!/system/bin/sh
            |DIR="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)"
            |export LD_LIBRARY_PATH="${'$'}DIR/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
            |exec "${'$'}DIR/$INSTALLED_BINARY_NAME" "${'$'}@"
        """.trimMargin()
        paths.wrapper.writeText(script)
    }

    private fun markExecutable(file: File) {
        if (!file.setReadable(true, false) || !file.setExecutable(true, false)) {
            throw IllegalStateException("Unable to mark embedded adb executable: ${file.absolutePath}")
        }
    }

    private data class InstallPaths(
        val directory: File,
        val binary: File,
        val wrapper: File,
        val libDir: File,
        val version: File
    ) {
        fun isComplete(): Boolean {
            return directory.isDirectory &&
                libDir.isDirectory &&
                binary.isFile &&
                wrapper.isFile &&
                version.isFile
        }
    }
}
