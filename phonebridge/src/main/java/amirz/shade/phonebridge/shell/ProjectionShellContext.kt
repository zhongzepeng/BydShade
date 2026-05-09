package amirz.shade.phonebridge.shell

import android.content.Context
import android.content.MutableContextWrapper

internal class ProjectionShellContext private constructor() : MutableContextWrapper(
    ProjectionShellWorkarounds.systemContext()
) {
    override fun getPackageName(): String = SHELL_PACKAGE_NAME

    override fun getOpPackageName(): String = SHELL_PACKAGE_NAME

    @Suppress("unused")
    fun getDeviceId(): Int = 0

    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE_NAME = "com.android.shell"

        val instance: ProjectionShellContext by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ProjectionShellContext()
        }
    }
}
