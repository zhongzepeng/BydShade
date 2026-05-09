package amirz.shade.phonebridge.shell;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class ProjectionShellWorkarounds {
    private static final int ANDROID_12 = 31;
    private static final Class<?> ACTIVITY_THREAD_CLASS;
    private static final Object ACTIVITY_THREAD;

    static {
        prepareMainLooper();
        try {
            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
            constructor.setAccessible(true);
            ACTIVITY_THREAD = constructor.newInstance();

            Field currentThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
            currentThreadField.setAccessible(true);
            currentThreadField.set(null, ACTIVITY_THREAD);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ProjectionShellWorkarounds() {
    }

    static void apply() {
        fillConfigurationController();
        fillAppInfo();
        fillAppContext();
    }

    static Context getSystemContext() {
        try {
            Method method = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
            return (Context) method.invoke(ACTIVITY_THREAD);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to obtain system context", e);
        }
    }

    private static void prepareMainLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
    }

    private static void fillAppInfo() {
        try {
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> constructor = appBindDataClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object appBindData = constructor.newInstance();

            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = ProjectionShellContext.SHELL_PACKAGE_NAME;

            Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(appBindData, applicationInfo);

            Field boundApplicationField = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
            boundApplicationField.setAccessible(true);
            boundApplicationField.set(ACTIVITY_THREAD, appBindData);
        } catch (Throwable ignored) {
        }
    }

    private static void fillAppContext() {
        try {
            Application application = new Application();
            Field baseField = ContextWrapper.class.getDeclaredField("mBase");
            baseField.setAccessible(true);
            baseField.set(application, ProjectionShellContext.get());

            Field initialApplicationField = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
            initialApplicationField.setAccessible(true);
            initialApplicationField.set(ACTIVITY_THREAD, application);
        } catch (Throwable ignored) {
        }
    }

    private static void fillConfigurationController() {
        if (Build.VERSION.SDK_INT < ANDROID_12) {
            return;
        }
        try {
            Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> constructor = controllerClass.getDeclaredConstructor(internalClass);
            constructor.setAccessible(true);
            Object controller = constructor.newInstance(ACTIVITY_THREAD);

            Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(ACTIVITY_THREAD, controller);
        } catch (Throwable ignored) {
        }
    }
}
