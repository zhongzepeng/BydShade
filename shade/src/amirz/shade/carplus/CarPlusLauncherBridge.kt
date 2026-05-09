package amirz.shade.carplus

import android.view.View
import amirz.shade.ShadeLauncher
import com.android.launcher3.AppInfo
import com.android.launcher3.ItemInfo
import java.util.LinkedHashMap

class CarPlusLauncherBridge(
    private val launcher: ShadeLauncher
) : PhoneBridgeRepository.Listener {

    private var windowManager: ScrcpyWindowManager? = null
    private var localApps: Array<AppInfo> = emptyArray()

    fun onCreate() {
        if (windowManager == null) {
            windowManager = ScrcpyWindowManager(launcher)
        }
        PhoneBridgeRepository.initialize(launcher.applicationContext)
        PhoneBridgeRepository.addListener(this)
        PhoneBridgeRepository.connect()
    }

    fun onDestroy() {
        PhoneBridgeRepository.removeListener(this)
        PhoneBridgeRepository.disconnect()
        windowManager?.destroy()
        windowManager = null
    }

    fun bindLocalApps(apps: Array<AppInfo>) {
        localApps = apps
        publishMergedApps()
    }

    fun maybeLaunchRemoteApp(view: View?, item: ItemInfo?): Boolean {
        val descriptor = resolveRemoteDescriptor(item) ?: return false
        windowManager?.openWindow(descriptor)
        return true
    }

    override fun onPhoneAppsChanged(apps: List<RemotePhoneAppInfo>) {
        publishMergedApps()
    }

    override fun onBridgeConnectionChanged(connected: Boolean) {
        // Keep the launcher passive on startup. Projection should start only when
        // the user explicitly opens a remote app/window, otherwise transient shell
        // server failures surface as noisy startup errors.
    }

    private fun publishMergedApps() {
        launcher.runOnUiThread {
            val appsView = launcher.appsView ?: return@runOnUiThread
            val deduped = LinkedHashMap<String, AppInfo>(
                localApps.size + PhoneBridgeRepository.currentRemoteApps().size
            )
            for (app in localApps) {
                deduped[componentKeyOf(app)] = app
            }
            for (app in PhoneBridgeRepository.currentRemoteApps()) {
                deduped[componentKeyOf(app)] = app
            }
            appsView.appsStore.setApps(deduped.values.toTypedArray())
        }
    }

    private fun resolveRemoteDescriptor(item: ItemInfo?): PhoneAppDescriptor? {
        val remoteItem = item as? RemotePhoneAppInfo
        if (remoteItem != null) {
            return remoteItem.descriptor
        }

        val appInfo = item as? AppInfo ?: return null
        val intent = appInfo.intent ?: return null
        if (intent.action != RemotePhoneAppInfo.ACTION_LAUNCH_REMOTE_APP) {
            return null
        }

        val packageName = intent.getStringExtra(RemotePhoneAppInfo.EXTRA_REMOTE_PACKAGE)
            .orEmpty()
        val className = intent.getStringExtra(RemotePhoneAppInfo.EXTRA_REMOTE_CLASS)
            .orEmpty()
        if (packageName.isBlank() || className.isBlank()) {
            return null
        }

        return PhoneAppDescriptor(
            packageName = packageName,
            className = className,
            label = appInfo.title?.toString().orEmpty(),
            iconBase64 = ""
        )
    }

    private fun componentKeyOf(app: AppInfo): String {
        val component = app.componentName?.flattenToShortString().orEmpty()
        return "${app.user.hashCode()}:$component"
    }
}
