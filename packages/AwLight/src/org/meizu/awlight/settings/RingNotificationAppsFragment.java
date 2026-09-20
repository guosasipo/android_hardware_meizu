/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.settings;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.meizu.awlight.AwLightApplication;
import org.meizu.awlight.R;
import org.meizu.awlight.manager.AwLightController;

public final class RingNotificationAppsFragment extends SettingsBasePreferenceFragment {
    private final ExecutorService mLoader = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Map<String, SwitchPreferenceCompat> mApps = new LinkedHashMap<>();
    private final AwLightController.Observer mObserver = this::render;
    private AwLightController mController;
    private SwitchPreferenceCompat mSelectAll;
    private PreferenceCategory mAllowed;
    private PreferenceCategory mBlocked;
    private Set<String> mShownBlocked;
    private boolean mDestroyed;

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        mController = AwLightApplication.controller(requireContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        mSelectAll = new SwitchPreferenceCompat(requireContext());
        mSelectAll.setKey("select_all");
        mSelectAll.setTitle(R.string.select_all);
        mSelectAll.setPersistent(false);
        mSelectAll.setIconSpaceReserved(false);
        mSelectAll.setEnabled(false);
        mSelectAll.setOnPreferenceChangeListener((row, value) -> {
            if (!(value instanceof Boolean) || mApps.isEmpty()
                    || !mController.getSnapshot().canEdit) return false;
            mController.setNotificationPackagesAllowed(Set.copyOf(mApps.keySet()), (Boolean) value);
            return true;
        });
        screen.addPreference(mSelectAll);
        Preference loading = new Preference(requireContext());
        loading.setTitle(com.android.internal.R.string.loading);
        loading.setSelectable(false);
        screen.addPreference(loading);
        Context context = requireContext().getApplicationContext();
        mLoader.execute(() -> {
            List<AppEntry> entries = loadApplications(context);
            mMain.post(() -> {
                if (mDestroyed || !isAdded()) return;
                showApplications(entries);
            });
        });
    }

    private static List<AppEntry> loadApplications(Context context) {
        PackageManager packages = context.getPackageManager();
        List<AppEntry> entries = new ArrayList<>();
        try {
            for (ApplicationInfo info : packages.getInstalledApplications(0)) {
                if (Thread.currentThread().isInterrupted()) return entries;
                if (!info.enabled || info.isResourceOverlay()) continue;
                try {
                    entries.add(new AppEntry(info.packageName, info.loadLabel(packages).toString(),
                            info.loadIcon(packages)));
                } catch (RuntimeException ignored) {
                    // Packages can be removed while this list is loading.
                }
            }
            Collator collator = Collator.getInstance(context.getResources()
                    .getConfiguration().getLocales().get(0));
            entries.sort((first, second) -> {
                int order = collator.compare(first.label, second.label);
                return order != 0 ? order : first.packageName.compareTo(second.packageName);
            });
        } catch (RuntimeException ignored) {
            entries.clear();
        }
        return entries;
    }

    private void showApplications(List<AppEntry> entries) {
        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        mApps.clear();
        mShownBlocked = null;
        screen.addPreference(mSelectAll);
        mAllowed = new PreferenceCategory(requireContext());
        mAllowed.setTitle(R.string.enabled);
        mBlocked = new PreferenceCategory(requireContext());
        mBlocked.setTitle(R.string.disabled);
        screen.addPreference(mAllowed);
        screen.addPreference(mBlocked);
        for (AppEntry entry : entries) {
            SwitchPreferenceCompat preference = new SwitchPreferenceCompat(requireContext());
            preference.setKey(entry.packageName);
            preference.setTitle(entry.label);
            preference.setIcon(entry.icon);
            preference.setPersistent(false);
            preference.setOrder(mApps.size());
            preference.setOnPreferenceChangeListener((row, value) -> {
                if (!(value instanceof Boolean)) return false;
                mController.setNotificationPackageAllowed(entry.packageName, (Boolean) value);
                return true;
            });
            mApps.put(entry.packageName, preference);
        }
        if (entries.isEmpty()) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.no_applications);
            empty.setSelectable(false);
            screen.addPreference(empty);
        }
        render(mController.getSnapshot());
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.supported_applications);
        mController.addObserver(mObserver);
    }

    @Override
    public void onPause() {
        mController.removeObserver(mObserver);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        mLoader.shutdownNow();
        super.onDestroy();
    }

    private void render(AwLightController.Snapshot state) {
        if (mAllowed == null || mDestroyed || !isAdded()) return;
        Set<String> blocked = state.preferences.notificationBlockedPackages;
        if (!blocked.equals(mShownBlocked)) {
            RecyclerView list = getView() == null ? null : getListView();
            RecyclerView.LayoutManager layout = list == null ? null : list.getLayoutManager();
            Parcelable position = mShownBlocked == null || layout == null
                    ? null : layout.onSaveInstanceState();
            for (Map.Entry<String, SwitchPreferenceCompat> app : mApps.entrySet()) {
                PreferenceCategory target = blocked.contains(app.getKey()) ? mBlocked : mAllowed;
                SwitchPreferenceCompat row = app.getValue();
                if (row.getParent() == target) continue;
                if (row.getParent() != null) row.getParent().removePreference(row);
                target.addPreference(row);
            }
            mAllowed.setVisible(mAllowed.getPreferenceCount() != 0);
            mBlocked.setVisible(mBlocked.getPreferenceCount() != 0);
            mShownBlocked = blocked;
            if (position != null) list.post(() -> {
                if (getView() != null && getListView() == list
                        && list.getLayoutManager() == layout) layout.onRestoreInstanceState(position);
            });
        }
        boolean allAllowed = !mApps.isEmpty();
        for (Map.Entry<String, SwitchPreferenceCompat> app : mApps.entrySet()) {
            boolean allowed = state.preferences.isNotificationPackageAllowed(app.getKey());
            app.getValue().setChecked(allowed);
            app.getValue().setEnabled(state.canEdit);
            allAllowed &= allowed;
        }
        mSelectAll.setChecked(allAllowed);
        mSelectAll.setEnabled(state.canEdit && !mApps.isEmpty());
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }
}
