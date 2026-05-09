package amirz.shade;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import com.android.launcher3.Launcher;
import com.android.launcher3.AppInfo;
import com.android.launcher3.ItemInfo;
import com.android.launcher3.R;

import amirz.shade.carplus.CarPlusLauncherBridge;
import amirz.shade.customization.ShadeStyle;

public class ShadeLauncher extends Launcher {
    private enum State {
        PAUSED,
        RECREATE_DEFERRED,
        KILL_DEFERRED,
        RESUMED
    }

    private final ShadeLauncherCallbacks mCallbacks;
    private CarPlusLauncherBridge mCarPlusBridge;
    private State mState = State.PAUSED;
    private AlertDialog mDefaultLauncherDialog;

    public ShadeLauncher() {
        super();

        mCallbacks = new ShadeLauncherCallbacks(this);
        setLauncherCallbacks(mCallbacks);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        ShadeRestarter.cancelRestart(this);
        ShadeFont.override(this);
        ShadeStyle.override(this);
        super.onCreate(savedInstanceState);
        mCarPlusBridge = new CarPlusLauncherBridge(this);
        mCarPlusBridge.onCreate();
    }

    @Override
    public void setTheme(int resid) {
        super.setTheme(resid);
        ShadeStyle.overrideShape(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mState == State.KILL_DEFERRED) {
            ShadeRestarter.initiateRestart(this);
        } else if (mState == State.RECREATE_DEFERRED) {
            super.recreate();
        }
        mState = State.RESUMED;
        maybePromptForDefaultLauncher();
    }

    @Override
    public void recreate() {
        if (mState == State.RESUMED) {
            super.recreate();
        } else if (mState != State.KILL_DEFERRED) {
            mState = State.RECREATE_DEFERRED;
        }
    }

    public void kill() {
        if (mState == State.RESUMED) {
            ShadeRestarter.initiateRestart(this);
        } else {
            mState = State.KILL_DEFERRED;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mState = State.PAUSED;
    }

    @Override
    public void onDestroy() {
        if (mDefaultLauncherDialog != null) {
            mDefaultLauncherDialog.dismiss();
            mDefaultLauncherDialog = null;
        }
        if (mCarPlusBridge != null) {
            mCarPlusBridge.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void bindAllApplications(AppInfo[] apps) {
        if (mCarPlusBridge != null) {
            mCarPlusBridge.bindLocalApps(apps);
        } else {
            super.bindAllApplications(apps);
        }
    }

    @Override
    public boolean startActivitySafely(View v, Intent intent, ItemInfo item, String sourceContainer) {
        if (mCarPlusBridge != null && mCarPlusBridge.maybeLaunchRemoteApp(v, item)) {
            return true;
        }
        return super.startActivitySafely(v, intent, item, sourceContainer);
    }

    public ShadeLauncherCallbacks getCallbacks() {
        return mCallbacks;
    }

    private void maybePromptForDefaultLauncher() {
        if (isFinishing() || isDestroyed() || isDefaultLauncher() || isPromptShowing()) {
            return;
        }
        mDefaultLauncherDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.default_launcher_prompt_title)
                .setMessage(R.string.default_launcher_prompt_message)
                .setPositiveButton(R.string.default_launcher_prompt_confirm, (dialog, which) -> {
                    openHomeSettings();
                    mDefaultLauncherDialog = null;
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        mDefaultLauncherDialog = null)
                .setOnDismissListener(dialog -> mDefaultLauncherDialog = null)
                .show();
    }

    private boolean isPromptShowing() {
        return mDefaultLauncherDialog != null && mDefaultLauncherDialog.isShowing();
    }

    private boolean isDefaultLauncher() {
        PackageManager packageManager = getPackageManager();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = packageManager.resolveActivity(homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        return resolveInfo != null
                && resolveInfo.activityInfo != null
                && getPackageName().equals(resolveInfo.activityInfo.packageName);
    }

    private void openHomeSettings() {
        Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
