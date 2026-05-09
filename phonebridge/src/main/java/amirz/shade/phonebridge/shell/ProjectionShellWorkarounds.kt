package amirz.shade.phonebridge.shell

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper

@SuppressLint(
    "PrivateApi",
    "BlockedPrivateApi",
    "SoonBlockedPrivateApi",
    "DiscouragedPrivateApi"
)
internal object ProjectionShellWorkarounds {
    private val activityThreadClass: Class<*> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        prepareMainLooper()
        Class.forName("android.app.ActivityThread")
    }

    private val activityThread: Any by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val constructor = activityThreadClass.getDeclaredConstructor()
        constructor.isAccessible = true
        constructor.newInstance().also { instance ->
            val currentActivityThreadField =
                activityThreadClass.getDeclaredField("sCurrentActivityThread")
            currentActivityThreadField.isAccessible = true
            currentActivityThreadField.set(null, instance)
        }
    }

    fun apply() {
        fillConfigurationController()
        fillAppInfo()
        fillAppContext()
    }

    fun systemContext(): Context {
        return activityThreadClass.getDeclaredMethod("getSystemContext")
            .invoke(activityThread) as? Context
            ?: throw IllegalStateException("Unable to obtain system context")
    }

    @Suppress("DEPRECATION")
    private fun prepareMainLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }
    }

    private fun fillAppInfo() {
        runCatching {
            val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindDataConstructor = appBindDataClass.getDeclaredConstructor()
            appBindDataConstructor.isAccessible = true
            val appBindData = appBindDataConstructor.newInstance()

            val applicationInfo = ApplicationInfo().apply {
                packageName = ProjectionShellContext.SHELL_PACKAGE_NAME
            }
            val appInfoField = appBindDataClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(appBindData, applicationInfo)

            val boundApplicationField = activityThreadClass.getDeclaredField("mBoundApplication")
            boundApplicationField.isAccessible = true
            boundApplicationField.set(activityThread, appBindData)
        }
    }

    private fun fillAppContext() {
        runCatching {
            val app = Application()
            val baseField = ContextWrapper::class.java.getDeclaredField("mBase")
            baseField.isAccessible = true
            baseField.set(app, ProjectionShellContext.instance)

            val initialApplicationField = activityThreadClass.getDeclaredField("mInitialApplication")
            initialApplicationField.isAccessible = true
            initialApplicationField.set(activityThread, app)
        }
    }

    private fun fillConfigurationController() {
        if (Build.VERSION.SDK_INT < ANDROID_12) {
            return
        }
        runCatching {
            val controllerClass = Class.forName("android.app.ConfigurationController")
            val internalClass = Class.forName("android.app.ActivityThreadInternal")
            val constructor = controllerClass.getDeclaredConstructor(internalClass)
            constructor.isAccessible = true
            val controller = constructor.newInstance(activityThread)
            val field = activityThreadClass.getDeclaredField("mConfigurationController")
            field.isAccessible = true
            field.set(activityThread, controller)
        }
    }

    private const val ANDROID_12 = 31
}
