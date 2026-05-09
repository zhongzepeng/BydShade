package amirz.shade.phonebridge.shell;

import android.content.Context;
import android.content.MutableContextWrapper;

final class ProjectionShellContext extends MutableContextWrapper {
    static final String SHELL_PACKAGE_NAME = "com.android.shell";

    private static final ProjectionShellContext INSTANCE = new ProjectionShellContext();

    static ProjectionShellContext get() {
        return INSTANCE;
    }

    private ProjectionShellContext() {
        super(ProjectionShellWorkarounds.getSystemContext());
    }

    @Override
    public String getPackageName() {
        return SHELL_PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return SHELL_PACKAGE_NAME;
    }

    public int getDeviceId() {
        return 0;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
