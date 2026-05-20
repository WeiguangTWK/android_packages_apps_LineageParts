/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.teedebug;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;
import org.lineageos.lineageparts.search.BaseSearchIndexProvider;
import org.lineageos.lineageparts.search.Searchable;
import org.lineageos.lineageparts.widget.PackageListAdapter;
import org.lineageos.lineageparts.widget.PackageListAdapter.PackageItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TeeSoftDebugSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener, Searchable {
    private static final String TAG = "TeeSoftDebugSettings";

    private static final String KEY_ENABLED = "tee_soft_debug_enabled";
    private static final String KEY_MODE = "tee_soft_debug_mode";
    private static final String KEY_KEYBOX_PATH = "tee_soft_debug_keybox_path";
    private static final String KEY_APPLICATIONS = "tee_soft_debug_applications";
    private static final String KEY_SHOW_SYSTEM_APPS = "tee_soft_debug_show_system_apps";
    private static final String KEY_ADD_APP = "tee_soft_debug_add_app";
    private static final String MODE_PATCH = "patch";
    private static final String MODE_AUTO = "auto";
    private static final String MODE_GENERATE = "generate";

    private static final int DIALOG_ADD_APPS = 0;
    private static final String KEYBOX_STORE_DIR = "/data/adbroot/tee_soft_debug";
    private static final String KEYBOX_STORE_FILE = "keybox.xml";
    private static final String RUNTIME_CONFIG_FILE = "config.conf";
    private static final String PROP_NH_OVERRIDE = "persist.sys.nhoverride";
    private static final int MODE_DIR_WORLD_RX_OWNER_W = 0755;
    private static final int MODE_FILE_WORLD_R_OWNER_W = 0644;

    private SwitchPreferenceCompat mEnabledPref;
    private ListPreference mModePref;
    private Preference mKeyboxPathPref;
    private PreferenceGroup mApplicationsPrefList;
    private SwitchPreferenceCompat mShowSystemAppsPref;
    private PackageListAdapter mPackageAdapter;

    private boolean mEnabled;
    private String mMode = MODE_PATCH;
    private String mKeyboxPath = "";
    private boolean mShowSystemApps = false;
    private final Set<String> mTargetPackages = new ArraySet<>();
    private final ActivityResultLauncher<Intent> mDocumentPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                            return;
                        }
                        final Uri uri = result.getData().getData();
                        if (uri == null) {
                            return;
                        }
                        onKeyboxSelected(uri);
                    });

    @Override
    public void onActivityCreated(android.os.Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        addPreferencesFromResource(R.xml.tee_soft_debug_settings);
        if (requireActivity().getActionBar() != null) {
            requireActivity().getActionBar().setTitle(R.string.tee_soft_debug_title);
        }

        final PreferenceScreen prefSet = getPreferenceScreen();

        mEnabledPref = prefSet.findPreference(KEY_ENABLED);
        mEnabledPref.setPersistent(false);
        mEnabledPref.setOnPreferenceChangeListener(this);

        mModePref = prefSet.findPreference(KEY_MODE);
        mModePref.setPersistent(false);
        mModePref.setOnPreferenceChangeListener(this);

        mKeyboxPathPref = prefSet.findPreference(KEY_KEYBOX_PATH);
        mKeyboxPathPref.setOnPreferenceClickListener(pref -> {
            launchDocumentPicker();
            return true;
        });

        mApplicationsPrefList = prefSet.findPreference(KEY_APPLICATIONS);
        mApplicationsPrefList.setOrderingAsAdded(false);
        mShowSystemAppsPref = prefSet.findPreference(KEY_SHOW_SYSTEM_APPS);
        mShowSystemAppsPref.setPersistent(false);
        mShowSystemAppsPref.setOnPreferenceChangeListener(this);

        mPackageAdapter = new PackageListAdapter(getActivity());

        final Preference addPreference = prefSet.findPreference(KEY_ADD_APP);
        addPreference.setOnPreferenceClickListener(preference -> {
            showDialog(DIALOG_ADD_APPS);
            return true;
        });

        loadRuntimeConfigIntoState();
        applyStateToUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadRuntimeConfigIntoState();
        applyStateToUi();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mEnabledPref) {
            mEnabled = (Boolean) newValue;
            persistRuntimeConfig();
            refreshKeyboxPathSummary();
            return true;
        }
        if (preference == mModePref) {
            mMode = normalizeMode(String.valueOf(newValue));
            mModePref.setValue(mMode);
            persistRuntimeConfig();
            refreshModeSummary();
            return true;
        }
        if (preference == mShowSystemAppsPref) {
            mShowSystemApps = (Boolean) newValue;
            mPackageAdapter.setIncludeSystemApps(mShowSystemApps);
            persistRuntimeConfig();
            return true;
        }
        return false;
    }

    @Override
    public Dialog onCreateDialog(int id) {
        if (id != DIALOG_ADD_APPS) {
            return null;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        final ListView list = new ListView(requireActivity());
        list.setAdapter(mPackageAdapter);
        list.setDivider(null);

        builder.setTitle(R.string.choose_app);
        builder.setView(list);

        final Dialog dialog = builder.create();
        list.setOnItemClickListener((parent, view, position, itemId) -> {
            final PackageItem info = (PackageItem) parent.getItemAtPosition(position);
            addTargetPackage(info.packageName);
            dialog.dismiss();
        });
        return dialog;
    }

    private void applyStateToUi() {
        mEnabledPref.setChecked(mEnabled);
        mModePref.setValue(mMode);
        mShowSystemAppsPref.setChecked(mShowSystemApps);
        mPackageAdapter.setIncludeSystemApps(mShowSystemApps);
        refreshModeSummary();
        refreshKeyboxPathSummary();
        refreshTargetApplicationPrefs();
    }

    private void refreshModeSummary() {
        final CharSequence modeEntry = mModePref.getEntry();
        if (!TextUtils.isEmpty(modeEntry)) {
            mModePref.setSummary(modeEntry);
        }
    }

    private void refreshKeyboxPathSummary() {
        if (TextUtils.isEmpty(mKeyboxPath)) {
            mKeyboxPathPref.setSummary(getString(R.string.tee_soft_debug_keybox_path_summary));
        } else {
            mKeyboxPathPref.setSummary(mKeyboxPath);
        }
    }

    private void refreshTargetApplicationPrefs() {
        for (int i = 0; i < mApplicationsPrefList.getPreferenceCount();) {
            final Preference pref = mApplicationsPrefList.getPreference(i);
            if (KEY_ADD_APP.equals(pref.getKey()) || KEY_SHOW_SYSTEM_APPS.equals(pref.getKey())) {
                i++;
                continue;
            }
            mApplicationsPrefList.removePreference(pref);
        }

        final PackageManager pm = getPackageManager();
        final List<String> sortedPkgs = new ArrayList<>(mTargetPackages);
        sortedPkgs.sort(String::compareToIgnoreCase);

        for (String packageName : sortedPkgs) {
            final Preference pref = new Preference(requireContext());
            pref.setKey(packageName);
            pref.setSummary(packageName);
            pref.setPersistent(false);

            try {
                final ApplicationInfo info = pm.getApplicationInfo(packageName,
                        PackageManager.ApplicationInfoFlags.of(0));
                pref.setTitle(info.loadLabel(pm));
                pref.setIcon(info.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException e) {
                pref.setTitle(packageName);
            }

            pref.setOnPreferenceClickListener(clicked -> {
                confirmRemoveTargetPackage(packageName);
                return true;
            });
            mApplicationsPrefList.addPreference(pref);
        }

        maybeShowEmptyHint();
        mPackageAdapter.setExcludedPackages(new HashSet<>(mTargetPackages));
    }

    private void maybeShowEmptyHint() {
        if (mApplicationsPrefList.getPreferenceCount() == 2) {
            final Preference hint = new Preference(requireContext());
            hint.setSummary(R.string.tee_soft_debug_applications_empty);
            hint.setEnabled(false);
            mApplicationsPrefList.addPreference(hint);
        }
    }

    private void addTargetPackage(String packageName) {
        if (mTargetPackages.add(packageName)) {
            persistRuntimeConfig();
            refreshTargetApplicationPrefs();
        }
    }

    private void removeTargetPackage(String packageName) {
        if (mTargetPackages.remove(packageName)) {
            persistRuntimeConfig();
            refreshTargetApplicationPrefs();
        }
    }

    private void confirmRemoveTargetPackage(String packageName) {
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.dialog_delete_title)
                .setMessage(getString(R.string.tee_soft_debug_remove_app_message, packageName))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> removeTargetPackage(packageName))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchDocumentPicker() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/xml");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"text/xml", "application/xml"});
        mDocumentPickerLauncher.launch(intent);
    }

    private void onKeyboxSelected(Uri uri) {
        try {
            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            requireActivity().getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to persist URI permission for " + uri, e);
        }

        try {
            final String keyboxXml = readTextFromUri(uri);
            if (TextUtils.isEmpty(keyboxXml)) {
                Log.e(TAG, "Selected keybox XML is empty.");
                Toast.makeText(requireContext(), R.string.tee_soft_debug_keybox_pick_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }

            final String copiedPath = copyKeyboxToDataDir(uri);
            if (TextUtils.isEmpty(copiedPath)) {
                Toast.makeText(requireContext(), R.string.tee_soft_debug_keybox_pick_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }

            mKeyboxPath = copiedPath;
            persistRuntimeConfig();
            refreshKeyboxPathSummary();
            Toast.makeText(requireContext(),
                    getString(R.string.tee_soft_debug_keybox_pick_success, copiedPath),
                    Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(TAG, "Failed to import keybox XML from URI: " + uri, e);
            Toast.makeText(requireContext(), R.string.tee_soft_debug_keybox_pick_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private String copyKeyboxToDataDir(Uri uri) throws IOException {
        final ContentResolver resolver = requireActivity().getContentResolver();
        final File targetDir = new File(KEYBOX_STORE_DIR);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Log.w(TAG, "Unable to create keybox dir: " + targetDir);
            return null;
        }
        ensurePathPermissions(targetDir.getAbsolutePath(), MODE_DIR_WORLD_RX_OWNER_W);

        final File target = new File(targetDir, KEYBOX_STORE_FILE);
        try (InputStream in = resolver.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target, false)) {
            if (in == null) {
                return null;
            }
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        ensurePathPermissions(target.getAbsolutePath(), MODE_FILE_WORLD_R_OWNER_W);
        return target.getAbsolutePath();
    }

    private String readTextFromUri(Uri uri) throws IOException {
        final ContentResolver resolver = requireActivity().getContentResolver();
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            final byte[] data = in.readAllBytes();
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private void loadRuntimeConfigIntoState() {
        mEnabled = false;
        mMode = MODE_PATCH;
        mKeyboxPath = "";
        mShowSystemApps = false;
        mTargetPackages.clear();

        final File config = new File(KEYBOX_STORE_DIR, RUNTIME_CONFIG_FILE);
        if (!config.exists()) {
            return;
        }

        final String content;
        try (InputStream in = new FileInputStream(config)) {
            final byte[] data = in.readAllBytes();
            content = new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "Failed to read runtime config: " + config, e);
            return;
        }

        for (String line : content.split("\\R")) {
            final int sep = line.indexOf('=');
            if (sep <= 0) {
                continue;
            }
            final String key = line.substring(0, sep).trim();
            final String value = line.substring(sep + 1).trim();
            switch (key) {
                case "enabled":
                    mEnabled = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                case "target_packages":
                    parseTargetPackages(value, mTargetPackages);
                    break;
                case "mode":
                    mMode = normalizeMode(value);
                    break;
                case "keybox_path":
                    mKeyboxPath = value;
                    break;
                case "show_system_apps":
                    mShowSystemApps = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                default:
                    break;
            }
        }
    }

    private void persistRuntimeConfig() {
        final File dir = new File(KEYBOX_STORE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create runtime config dir: " + dir);
            return;
        }
        ensurePathPermissions(dir.getAbsolutePath(), MODE_DIR_WORLD_RX_OWNER_W);

        final File config = new File(dir, RUNTIME_CONFIG_FILE);
        final List<String> sortedPkgs = new ArrayList<>(mTargetPackages);
        sortedPkgs.sort(String::compareToIgnoreCase);
        final String payload =
                "enabled=" + (mEnabled ? 1 : 0) + "\n"
                        + "mode=" + normalizeMode(mMode) + "\n"
                        + "target_packages=" + TextUtils.join("|", sortedPkgs) + "\n"
                        + "keybox_path=" + (mKeyboxPath == null ? "" : mKeyboxPath) + "\n"
                        + "show_system_apps=" + (mShowSystemApps ? 1 : 0) + "\n";
        try (FileOutputStream out = new FileOutputStream(config, false)) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ensurePathPermissions(config.getAbsolutePath(), MODE_FILE_WORLD_R_OWNER_W);
            syncNhOverrideProperty();
        } catch (IOException e) {
            Log.w(TAG, "Failed to write runtime config: " + config, e);
        }
    }

    private void syncNhOverrideProperty() {
        try {
            SystemProperties.set(PROP_NH_OVERRIDE, mEnabled ? "1" : "0");
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to update nhoverride property", e);
        }
    }

    private void ensurePathPermissions(String path, int mode) {
        try {
            Os.chmod(path, mode);
        } catch (ErrnoException e) {
            Log.w(TAG, "Failed to chmod " + path + " to " + Integer.toOctalString(mode), e);
        }
    }

    private static void parseTargetPackages(String raw, Set<String> out) {
        out.clear();
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        final String[] entries = TextUtils.split(raw, "\\|");
        for (String entry : entries) {
            if (!TextUtils.isEmpty(entry)) {
                out.add(entry);
            }
        }
    }

    private static String normalizeMode(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return MODE_PATCH;
        }
        switch (raw.trim().toLowerCase()) {
            case MODE_AUTO:
                return MODE_AUTO;
            case MODE_GENERATE:
                return MODE_GENERATE;
            default:
                return MODE_PATCH;
        }
    }

    private static RuntimeConfigSnapshot loadRuntimeConfigSnapshot() {
        final RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot();
        final File config = new File(KEYBOX_STORE_DIR, RUNTIME_CONFIG_FILE);
        if (!config.exists()) {
            return snapshot;
        }

        final String content;
        try (InputStream in = new FileInputStream(config)) {
            final byte[] data = in.readAllBytes();
            content = new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return snapshot;
        }

        String targets = "";
        for (String line : content.split("\\R")) {
            final int sep = line.indexOf('=');
            if (sep <= 0) {
                continue;
            }
            final String key = line.substring(0, sep).trim();
            final String value = line.substring(sep + 1).trim();
            switch (key) {
                case "enabled":
                    snapshot.enabled = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                case "target_packages":
                    targets = value;
                    break;
                case "keybox_path":
                    snapshot.keyboxPath = value;
                    break;
                default:
                    break;
            }
        }
        snapshot.packageCount = TextUtils.isEmpty(targets) ? 0 : TextUtils.split(targets, "\\|").length;
        return snapshot;
    }

    private static final class RuntimeConfigSnapshot {
        boolean enabled;
        int packageCount;
        String keyboxPath = "";
    }

    public static final SummaryProvider SUMMARY_PROVIDER = (context, key) -> {
        final RuntimeConfigSnapshot snapshot = loadRuntimeConfigSnapshot();
        if (!snapshot.enabled) {
            return context.getString(R.string.disabled);
        }

        if (TextUtils.isEmpty(snapshot.keyboxPath)) {
            return context.getString(R.string.tee_soft_debug_enabled_no_keybox);
        }

        if (snapshot.packageCount <= 0) {
            return context.getString(R.string.tee_soft_debug_enabled_no_apps);
        }

        return context.getResources().getQuantityString(
                R.plurals.tee_soft_debug_enabled_with_apps, snapshot.packageCount, snapshot.packageCount);
    };

    public static final Searchable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {
                @Override
                public Set<String> getNonIndexableKeys(Context context) {
                    return null;
                }
            };
}
