/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.pride;

import android.app.Activity;
import android.app.ActionBar;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.provider.Settings.System;
import android.support.v4.os.BuildCompat;
import android.util.Log;
import android.view.MenuItem;

import com.android.pride.graphics.IconShapeOverride;
import com.android.pride.topwidget.esteemJawsClient;
import com.android.pride.util.LooperExecuter;

import java.util.List;
import java.util.ArrayList;

/**
 * Settings activity for Launcher. Currently implements the following setting: Allow rotation
 */
public class SettingsActivity extends Activity {

    private static final String ICON_BADGING_PREFERENCE_KEY = "pref_icon_badging";
    // TODO: use Settings.Secure.NOTIFICATION_BADGING
    private static final String NOTIFICATION_BADGING = "notification_badging";
    private static final String DEFAULT_WEATHER_ICON_PACKAGE = "org.esteemrom.esteemjaws";
    private static final String DEFAULT_WEATHER_ICON_PREFIX = "outline";
    private static final String CHRONUS_ICON_PACK_INTENT = "com.dvtonder.chronus.ICON_PACK";
    private static final long WAIT_BEFORE_RESTART = 250;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP|ActionBar.DISPLAY_SHOW_TITLE);

        // Display the fragment as the main content.
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new LauncherSettingsFragment())
                .commit();
    }

    /**
     * This fragment shows the launcher preferences.
     */
    public static class LauncherSettingsFragment extends PreferenceFragment {

        private SystemDisplayRotationLockObserver mRotationLockObserver;
        private IconBadgingObserver mIconBadgingObserver;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY);
            addPreferencesFromResource(R.xml.launcher_preferences);

            ContentResolver resolver = getActivity().getContentResolver();

            // Setup allow rotation preference
            Preference rotationPref = findPreference(Utilities.ALLOW_ROTATION_PREFERENCE_KEY);
            if (getResources().getBoolean(R.bool.allow_rotation)) {
                // Launcher supports rotation by default. No need to show this setting.
                getPreferenceScreen().removePreference(rotationPref);
            } else {
                mRotationLockObserver = new SystemDisplayRotationLockObserver(rotationPref, resolver);

                // Register a content observer to listen for system setting changes while
                // this UI is active.
                resolver.registerContentObserver(
                        Settings.System.getUriFor(System.ACCELEROMETER_ROTATION),
                        false, mRotationLockObserver);

                // Initialize the UI once
                mRotationLockObserver.onChange(true);
                rotationPref.setDefaultValue(Utilities.getAllowRotationDefaultValue(getActivity()));
            }

            Preference iconBadgingPref = findPreference(ICON_BADGING_PREFERENCE_KEY);
            if (!BuildCompat.isAtLeastO()) {
                getPreferenceScreen().removePreference(
                        findPreference(SessionCommitReceiver.ADD_ICON_PREFERENCE_KEY));
                getPreferenceScreen().removePreference(iconBadgingPref);
            } else {
                // Listen to system notification badge settings while this UI is active.
                mIconBadgingObserver = new IconBadgingObserver(iconBadgingPref, resolver);
                resolver.registerContentObserver(
                        Settings.Secure.getUriFor(NOTIFICATION_BADGING),
                        false, mIconBadgingObserver);
                mIconBadgingObserver.onChange(true);
            }

            final ListPreference iconShape = (ListPreference) findPreference(Utilities.ICON_SHAPE_PREFERENCE_KEY);
            iconShape.setSummary(iconShape.getEntry());
            iconShape.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    int index = iconShape.findIndexOfValue((String) newValue);
                    iconShape.setSummary(iconShape.getEntries()[index]);
                    return true;
                }
            });

            final ListPreference iconPack = (ListPreference) findPreference(Utilities.WEATHER_ICON_PACK_PREFERENCE_KEY) ;

            esteemJawsClient weatherClient = new esteemJawsClient(getActivity());
            if (!weatherClient.isesteemJawsServiceInstalled()) {
                PreferenceCategory widgetCategory = (PreferenceCategory) findPreference("widget_category");
                widgetCategory.removePreference(iconPack);
            } else {
                String settingHeaderPackage = Utilities.getWeatherIconPack(getActivity());
                if (settingHeaderPackage == null) {
                    settingHeaderPackage = DEFAULT_WEATHER_ICON_PACKAGE + "." + DEFAULT_WEATHER_ICON_PREFIX;
                }

                List<String> entries = new ArrayList<String>();
                List<String> values = new ArrayList<String>();
                getAvailableWeatherIconPacks(entries, values);
                iconPack.setEntries(entries.toArray(new String[entries.size()]));
                iconPack.setEntryValues(values.toArray(new String[values.size()]));

                int valueIndex = iconPack.findIndexOfValue(settingHeaderPackage);
                if (valueIndex == -1) {
                    // no longer found
                    settingHeaderPackage = DEFAULT_WEATHER_ICON_PACKAGE + "." + DEFAULT_WEATHER_ICON_PREFIX;
                    valueIndex = iconPack.findIndexOfValue(settingHeaderPackage);
                }
                iconPack.setValueIndex(valueIndex >= 0 ? valueIndex : 0);
                iconPack.setSummary(iconPack.getEntry());
                iconPack.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        int valueIndex = iconPack.findIndexOfValue((String)newValue);
                        iconPack.setSummary(iconPack.getEntries()[valueIndex]);
                        return true;
                    }
                });
            }

            final ListPreference eventsPeriod = (ListPreference) findPreference(Utilities.SHOW_EVENTS_PERIOD_PREFERENCE_KEY);
            eventsPeriod.setSummary(eventsPeriod.getEntry());
            eventsPeriod.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    int index = eventsPeriod.findIndexOfValue((String) newValue);
                    eventsPeriod.setSummary(eventsPeriod.getEntries()[index]);
                    return true;
                }
            });

            final ListPreference searchBarLocation = (ListPreference) findPreference(Utilities.SHOW_SEARCH_BAR_LOCATION_PREFERENCE_KEY);
            searchBarLocation.setSummary(searchBarLocation.getEntry());
            searchBarLocation.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    int index = searchBarLocation.findIndexOfValue((String) newValue);
                    searchBarLocation.setSummary(searchBarLocation.getEntries()[index]);
                    return true;
                }
            });

            SwitchPreference leftTabPage = (SwitchPreference) findPreference(Utilities.SHOW_LEFT_TAB_PREFERENCE_KEY);
            if (!isSearchInstalled()) {
                getPreferenceScreen().removePreference(leftTabPage);
            }

            Preference hiddenApp = findPreference(Utilities.KEY_HIDDEN_APPS);
            hiddenApp.setOnPreferenceClickListener(
                preference -> {
                    startActivity(new Intent(getActivity(), MultiSelectRecyclerViewActivity.class));
                    return false;
            });

            final ListPreference gridColumns = (ListPreference) findPreference(Utilities.GRID_COLUMNS);
            gridColumns.setSummary(gridColumns.getEntry());
            gridColumns.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    int index = gridColumns.findIndexOfValue((String) newValue);
                    gridColumns.setSummary(gridColumns.getEntries()[index]);
                    restart(getActivity());
                    return true;
                }
            });

            final ListPreference gridRows = (ListPreference) findPreference(Utilities.GRID_ROWS);
            gridRows.setSummary(gridRows.getEntry());
            gridRows.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    int index = gridRows.findIndexOfValue((String) newValue);
                    gridRows.setSummary(gridRows.getEntries()[index]);
                    restart(getActivity());
                    return true;
                }
            });

            final ListPreference hotseatColumns = (ListPreference) findPreference(Utilities.HOTSEAT_ICONS);
            hotseatColumns.setSummary(hotseatColumns.getEntry());
            hotseatColumns.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    int index = hotseatColumns.findIndexOfValue((String) newValue);
                    hotseatColumns.setSummary(hotseatColumns.getEntries()[index]);
                    restart(getActivity());
                    return true;
                }
            });
        }

        @Override
        public void onDestroy() {
            if (mRotationLockObserver != null) {
                getActivity().getContentResolver().unregisterContentObserver(mRotationLockObserver);
                mRotationLockObserver = null;
            }
            if (mIconBadgingObserver != null) {
                getActivity().getContentResolver().unregisterContentObserver(mIconBadgingObserver);
                mIconBadgingObserver = null;
            }
            super.onDestroy();
        }

        private void getAvailableWeatherIconPacks(List<String> entries, List<String> values) {
            Intent i = new Intent();
            PackageManager packageManager = getActivity().getPackageManager();
            i.setAction("org.esteemrom.WeatherIconPack");
            for (ResolveInfo r : packageManager.queryIntentActivities(i, 0)) {
                String packageName = r.activityInfo.packageName;
                String label = r.activityInfo.loadLabel(getActivity().getPackageManager()).toString();
                if (label == null) {
                    label = r.activityInfo.packageName;
                }
                if (entries.contains(label)) {
                    continue;
                }
                if (packageName.equals(DEFAULT_WEATHER_ICON_PACKAGE)) {
                    values.add(0, r.activityInfo.name);
                } else {
                    values.add(r.activityInfo.name);
                }

                if (packageName.equals(DEFAULT_WEATHER_ICON_PACKAGE)) {
                    entries.add(0, label);
                } else {
                    entries.add(label);
                }
            }
            i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(CHRONUS_ICON_PACK_INTENT);
            for (ResolveInfo r : packageManager.queryIntentActivities(i, 0)) {
                String packageName = r.activityInfo.packageName;
                String label = r.activityInfo.loadLabel(getActivity().getPackageManager()).toString();
                if (label == null) {
                    label = r.activityInfo.packageName;
                }
                if (entries.contains(label)) {
                    continue;
                }
                values.add(packageName + ".weather");

                entries.add(label);
            }
        }

        private boolean isSearchInstalled() {
            return Utilities.isPackageInstalled(getActivity(), LauncherTab.SEARCH_PACKAGE);
        }
    }

    /**
     * Content observer which listens for system auto-rotate setting changes, and enables/disables
     * the launcher rotation setting accordingly.
     */
    private static class SystemDisplayRotationLockObserver extends ContentObserver {

        private final Preference mRotationPref;
        private final ContentResolver mResolver;

        public SystemDisplayRotationLockObserver(
                Preference rotationPref, ContentResolver resolver) {
            super(new Handler());
            mRotationPref = rotationPref;
            mResolver = resolver;
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean enabled = Settings.System.getInt(mResolver,
                    Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
            mRotationPref.setEnabled(enabled);
            mRotationPref.setSummary(enabled
                    ? R.string.allow_rotation_desc : R.string.allow_rotation_blocked_desc);
        }
    }

    /**
     * Content observer which listens for system badging setting changes,
     * and updates the launcher badging setting subtext accordingly.
     */
    private static class IconBadgingObserver extends ContentObserver {

        private final Preference mBadgingPref;
        private final ContentResolver mResolver;

        public IconBadgingObserver(Preference badgingPref, ContentResolver resolver) {
            super(new Handler());
            mBadgingPref = badgingPref;
            mResolver = resolver;
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean enabled = Settings.Secure.getInt(mResolver, NOTIFICATION_BADGING, 1) == 1;
            mBadgingPref.setSummary(enabled
                    ? R.string.icon_badging_desc_on
                    : R.string.icon_badging_desc_off);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return true;
    }

    public static void restart(final Context context) {
        ProgressDialog.show(context, null, context.getString(R.string.state_loading), true, false);
        new LooperExecuter(LauncherModel.getWorkerLooper()).execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(WAIT_BEFORE_RESTART);
                } catch (Exception e) {
                    Log.e("SettingsActivity", "Error waiting", e);
                }

                Intent intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage(context.getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
                AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 50, pendingIntent);

                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }
}
