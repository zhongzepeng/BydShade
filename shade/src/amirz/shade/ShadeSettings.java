package amirz.shade;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.launcher3.BuildConfig;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.settings.SettingsActivity;
import com.android.launcher3.util.SystemUiController;

import amirz.shade.carplus.CarPlusProtocol;
import amirz.shade.carplus.easycontrol.EasycontrolPrefs;
import amirz.shade.customization.IconDatabase;
import amirz.shade.customization.IconShapeOverride;
import amirz.shade.customization.ShadeStyle;
import amirz.shade.icons.pack.IconPackManager;
import amirz.shade.settings.DockSearchPrefSetter;
import amirz.shade.settings.FeedProviderPrefSetter;
import amirz.shade.settings.IconPackPrefSetter;
import amirz.shade.settings.ReloadingListPreference;
import amirz.shade.util.AppReloader;

import static amirz.shade.ShadeFont.KEY_FONT;
import static amirz.shade.ShadeLauncherCallbacks.KEY_ENABLE_MINUS_ONE;
import static amirz.shade.ShadeLauncherCallbacks.KEY_FEED_PROVIDER;
import static amirz.shade.customization.ShadeStyle.KEY_THEME;
import static amirz.shade.customization.DockSearch.KEY_DOCK_SEARCH;
import static amirz.shade.customization.IconShapeOverride.KEY_ICON_SHAPE;
import static com.android.launcher3.util.Themes.KEY_DEVICE_THEME;

public class ShadeSettings extends SettingsActivity {
    public interface OnResumePreferenceCallback {
        void onResume();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ShadeFont.override(this);
        ShadeStyle.override(this);
        super.onCreate(savedInstanceState);
        getActionBar().setDisplayHomeAsUpEnabled(true);

        if (Utilities.ATLEAST_OREO && !Utilities.ATLEAST_P) {
            new SystemUiController(getWindow())
                    .updateUiState(SystemUiController.UI_STATE_BASE_WINDOW, true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @SuppressWarnings("unused")
    public static class ShadeSettingsFragment extends SettingsActivity.LauncherSettingsFragment {
        private static final String CATEGORY_STYLE = "category_style";
        private static final String CATEGORY_ABOUT = "category_about";

        private static final String KEY_ICON_PACK = "pref_icon_pack";
        private static final String KEY_APP_VERSION = "pref_app_version";
        private static final String KEY_DONATE = "pref_donate";
        private static final String KEY_PHONE_HOST = CarPlusProtocol.PREF_PHONE_HOST;
        private static final String KEY_PHONE_PORT = CarPlusProtocol.PREF_PHONE_PORT;
        private static final String KEY_PHONE_ADB_PORT = CarPlusProtocol.PREF_PHONE_ADB_PORT;
        private static final String KEY_PHONE_SERIAL = CarPlusProtocol.PREF_PHONE_SERIAL;
        private static final String KEY_PHONE_USB_SERIAL = CarPlusProtocol.PREF_PHONE_USB_SERIAL;
        private static final String KEY_ADB_BINARY = EasycontrolPrefs.PREF_ADB_BINARY;
        private static final String KEY_ADB_SERVER_HOST = EasycontrolPrefs.PREF_ADB_SERVER_HOST;
        private static final String KEY_ADB_SERVER_PORT = EasycontrolPrefs.PREF_ADB_SERVER_PORT;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            migrateEasycontrolPreferences();
            super.onCreatePreferences(savedInstanceState, rootKey);

            final Context context = getActivity();

            // Load the icon pack once to set the correct default icon pack.
            IconPackManager.get(context);

            // Customization
            ReloadingListPreference icons = (ReloadingListPreference) findPreference(KEY_ICON_PACK);
            icons.setValue(IconDatabase.getGlobal(context));
            icons.setOnReloadListener(IconPackPrefSetter::new);
            icons.setOnPreferenceChangeListener((pref, val) -> {
                IconDatabase.clearAll(context);
                IconDatabase.setGlobal(context, (String) val);
                AppReloader.get(context).reload();
                return true;
            });

            PreferenceCategory style = (PreferenceCategory) findPreference(CATEGORY_STYLE);
            Preference iconShapeOverride = findPreference(KEY_ICON_SHAPE);
            if (iconShapeOverride != null) {
                if (Utilities.ATLEAST_OREO) {
                    IconShapeOverride.handlePreferenceUi((ListPreference) iconShapeOverride);
                } else {
                    style.removePreference(iconShapeOverride);
                }
            }

            if (Utilities.ATLEAST_Q) {
                style.removePreference(style.findPreference(KEY_DEVICE_THEME));
            }

            Preference.OnPreferenceChangeListener restart = (pref, val) -> {
                startActivity(getActivity().getIntent()
                                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        ActivityOptions.makeCustomAnimation(
                                context, R.anim.fade_in, R.anim.fade_out).toBundle());
                return true;
            };

            findPreference(KEY_THEME).setOnPreferenceChangeListener(restart);
            findPreference(KEY_FONT).setOnPreferenceChangeListener(restart);

            ReloadingListPreference search =
                    (ReloadingListPreference) findPreference(KEY_DOCK_SEARCH);
            search.setOnReloadListener(DockSearchPrefSetter::new);

            ReloadingListPreference feed =
                    (ReloadingListPreference) findPreference(KEY_FEED_PROVIDER);
            feed.setOnReloadListener(FeedProviderPrefSetter::new);
            feed.setOnPreferenceChangeListener((pref, val) -> {
                Utilities.getPrefs(context).edit()
                        .putBoolean(KEY_ENABLE_MINUS_ONE, !TextUtils.isEmpty((String) val))
                        .apply();
                return true;
            });

            bindTextPreference(KEY_PHONE_HOST, InputType.TYPE_CLASS_TEXT, false);
            bindTextPreference(KEY_PHONE_SERIAL, InputType.TYPE_CLASS_TEXT, true);
            bindTextPreference(KEY_PHONE_USB_SERIAL, InputType.TYPE_CLASS_TEXT, true);
            bindTextPreference(KEY_ADB_BINARY, InputType.TYPE_CLASS_TEXT, false);
            bindTextPreference(KEY_ADB_SERVER_HOST, InputType.TYPE_CLASS_TEXT, false);
            bindPortPreference(KEY_PHONE_PORT);
            bindPortPreference(KEY_PHONE_ADB_PORT);
            bindPortPreference(KEY_ADB_SERVER_PORT);

            // About
            String versionName = BuildConfig.VERSION_NAME;
            PackageManager pm = context.getPackageManager();
            try {
                PackageInfo pi = pm.getPackageInfo(BuildConfig.APPLICATION_ID, 0);
                versionName = pi.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }

            Preference version = findPreference(KEY_APP_VERSION);
            version.setSummary(context.getString(R.string.about_app_version_value,
                    versionName, BuildConfig.BUILD_TYPE));
            Uri intentData = Uri.parse("package:" + BuildConfig.APPLICATION_ID);
            version.setIntent(version.getIntent().setData(intentData));

            Preference donate = findPreference(KEY_DONATE);
            if (pm.queryIntentActivities(donate.getIntent(), 0).isEmpty()) {
                PreferenceCategory about = (PreferenceCategory) findPreference(CATEGORY_ABOUT);
                about.removePreference(donate);
            }
        }

        private void migrateEasycontrolPreferences() {
            Context context = getActivity();
            if (context == null) {
                return;
            }
            SharedPreferences prefs = Utilities.getPrefs(context);
            migrateIntPreferenceToString(prefs, KEY_PHONE_PORT);
            migrateIntPreferenceToString(prefs, KEY_PHONE_ADB_PORT);
            migrateIntPreferenceToString(prefs, KEY_ADB_SERVER_PORT);
        }

        private void migrateIntPreferenceToString(SharedPreferences prefs, String key) {
            Object value = prefs.getAll().get(key);
            if (!(value instanceof Number)) {
                return;
            }
            prefs.edit().putString(key, String.valueOf(((Number) value).intValue())).apply();
        }

        private void bindTextPreference(String key, int inputType, boolean allowBlank) {
            EditTextPreference preference = (EditTextPreference) findPreference(key);
            if (preference == null) {
                return;
            }
            updateTextSummary(preference, allowBlank);
            preference.setOnPreferenceChangeListener((pref, newValue) -> {
                updateSummary(pref, String.valueOf(newValue).trim(), allowBlank);
                return true;
            });
        }

        private void bindPortPreference(String key) {
            EditTextPreference preference = (EditTextPreference) findPreference(key);
            if (preference == null) {
                return;
            }
            updatePortSummary(preference);
            preference.setOnPreferenceChangeListener((pref, newValue) -> {
                String value = String.valueOf(newValue).trim();
                if (TextUtils.isEmpty(value)) {
                    updateSummary(pref, value, true);
                    return true;
                }
                try {
                    int port = Integer.parseInt(value);
                    if (port < 1 || port > 65535) {
                        throw new NumberFormatException();
                    }
                    updateSummary(pref, value, true);
                    return true;
                } catch (NumberFormatException e) {
                    Toast.makeText(
                            getActivity(),
                            R.string.easycontrol_pref_invalid_number,
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }
            });
        }

        private void updateTextSummary(EditTextPreference preference, boolean allowBlank) {
            updateSummary(preference, preference.getText(), allowBlank);
        }

        private void updatePortSummary(EditTextPreference preference) {
            updateSummary(preference, preference.getText(), true);
        }

        private void updateSummary(Preference preference, String value, boolean allowBlank) {
            if (TextUtils.isEmpty(value)) {
                preference.setSummary(allowBlank ? getString(R.string.easycontrol_summary_auto) : "");
                return;
            }
            preference.setSummary(value);
        }

        @Override
        public void onResume() {
            super.onResume();

            PreferenceScreen screen = getPreferenceScreen();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference preference = screen.getPreference(i);
                if (preference instanceof PreferenceCategory) {
                    PreferenceCategory cat = (PreferenceCategory) preference;
                    for (int j = 0; j < cat.getPreferenceCount(); j++) {
                        Preference preference2 = cat.getPreference(j);
                        if (preference2 instanceof OnResumePreferenceCallback) {
                            ((OnResumePreferenceCallback) preference2).onResume();
                        } else if (preference2 instanceof EditTextPreference) {
                            EditTextPreference editTextPreference = (EditTextPreference) preference2;
                            boolean allowBlank = !KEY_PHONE_HOST.equals(preference2.getKey())
                                    && !KEY_ADB_BINARY.equals(preference2.getKey())
                                    && !KEY_ADB_SERVER_HOST.equals(preference2.getKey());
                            updateSummary(preference2, editTextPreference.getText(), allowBlank);
                        }
                    }
                }
            }
        }
    }
}
