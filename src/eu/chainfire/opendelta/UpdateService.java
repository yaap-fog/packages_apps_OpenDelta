/*
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2015 The OmniROM Project
 * Copyright (C) 2020-2022 Yet Another AOSP Project
 */
/*
 * This file is part of OpenDelta.
 *
 * OpenDelta is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenDelta is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenDelta. If not, see <http://www.gnu.org/licenses/>.
 */

package eu.chainfire.opendelta;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RecoverySystem;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.UpdateEngine;
import android.preference.PreferenceManager;

import eu.chainfire.opendelta.BatteryState.OnBatteryStateListener;
import eu.chainfire.opendelta.DeltaInfo.ProgressListener;
import eu.chainfire.opendelta.NetworkState.OnNetworkStateListener;
import eu.chainfire.opendelta.Scheduler.OnWantUpdateCheckListener;
import eu.chainfire.opendelta.ScreenState.OnScreenStateListener;

public class UpdateService extends Service implements OnNetworkStateListener,
        OnBatteryStateListener, OnScreenStateListener,
        OnWantUpdateCheckListener, OnSharedPreferenceChangeListener {
    private static final int HTTP_READ_TIMEOUT = 30000;
    private static final int HTTP_CONNECTION_TIMEOUT = 30000;

    public static void start(Context context) {
        start(context, null);
    }

    public static void startClearRunningInstall(Context context) {
        start(context, ACTION_CLEAR_INSTALL_RUNNING);
    }

    private static void start(Context context, String action) {
        Intent i = new Intent(context, UpdateService.class);
        i.setAction(action);
        context.startService(i);
    }

    public static PendingIntent alarmPending(Context context, int id) {
        Intent intent = new Intent(context, UpdateService.class);
        intent.setAction(ACTION_ALARM);
        intent.putExtra(EXTRA_ALARM_ID, id);
        return PendingIntent.getService(context, id, intent, PendingIntent.FLAG_MUTABLE);
    }

    public static final String ACTION_SYSTEM_UPDATE_SETTINGS = "android.settings.SYSTEM_UPDATE_SETTINGS";
    public static final String PERMISSION_ACCESS_CACHE_FILESYSTEM = "android.permission.ACCESS_CACHE_FILESYSTEM";
    public static final String PERMISSION_REBOOT = "android.permission.REBOOT";

    public static final String BROADCAST_INTENT = "eu.chainfire.opendelta.intent.BROADCAST_STATE";
    public static final String EXTRA_STATE = "eu.chainfire.opendelta.extra.ACTION_STATE";
    public static final String EXTRA_FILENAME = "eu.chainfire.opendelta.extra.FILENAME";

    public static final String STATE_ACTION_NONE = "action_none";
    public static final String STATE_ACTION_CHECKING = "action_checking";
    public static final String STATE_ACTION_CHECKING_SUM = "action_checking_sum";
    public static final String STATE_ACTION_SEARCHING = "action_searching";
    public static final String STATE_ACTION_SEARCHING_SUM = "action_searching_sum";
    public static final String STATE_ACTION_DOWNLOADING = "action_downloading";
    public static final String STATE_ACTION_DOWNLOADING_PAUSED = "action_downloading_paused";
    public static final String STATE_ACTION_APPLYING = "action_applying";
    public static final String STATE_ACTION_APPLYING_PATCH = "action_applying_patch";
    public static final String STATE_ACTION_APPLYING_SUM = "action_applying_sum";
    public static final String STATE_ACTION_READY = "action_ready";
    public static final String STATE_ACTION_AB_FLASH = "action_ab_flash";
    public static final String STATE_ACTION_AB_FINISHED = "action_ab_finished";
    public static final String STATE_ERROR_DISK_SPACE = "error_disk_space";
    public static final String STATE_ERROR_UNKNOWN = "error_unknown";
    public static final String STATE_ERROR_UNOFFICIAL = "error_unofficial";
    public static final String STATE_ACTION_BUILD = "action_build";
    public static final String STATE_ERROR_DOWNLOAD = "error_download";
    public static final String STATE_ERROR_DOWNLOAD_RESUME = "error_download_resume";
    public static final String STATE_ERROR_CONNECTION = "error_connection";
    public static final String STATE_ERROR_PERMISSIONS = "error_permissions";
    public static final String STATE_ERROR_FLASH = "error_flash";
    public static final String STATE_ERROR_AB_FLASH = "error_ab_flash";
    public static final String STATE_ERROR_FLASH_FILE = "error_flash_file";
    public static final String STATE_ACTION_FLASH_FILE_READY = "action_flash_file_ready";

    public static final String ACTION_CHECK = "eu.chainfire.opendelta.action.CHECK";
    public static final String ACTION_FLASH = "eu.chainfire.opendelta.action.FLASH";
    public static final String ACTION_ALARM = "eu.chainfire.opendelta.action.ALARM";
    private static final String EXTRA_ALARM_ID = "eu.chainfire.opendelta.extra.ALARM_ID";
    private static final String ACTION_NOTIFICATION_DELETED = "eu.chainfire.opendelta.action.NOTIFICATION_DELETED";
    public static final String ACTION_BUILD = "eu.chainfire.opendelta.action.BUILD";
    private static final String ACTION_UPDATE = "eu.chainfire.opendelta.action.UPDATE";
    static final String ACTION_CLEAR_INSTALL_RUNNING =
            "eu.chainfire.opendelta.action.ACTION_CLEAR_INSTALL_RUNNING";
    public static final String ACTION_FLASH_FILE = "eu.chainfire.opendelta.action.FLASH_FILE";
    public static final String ACTION_DOWNLOAD_STOP = "eu.chainfire.opendelta.action.DOWNLOAD_STOP";
    public static final String ACTION_DOWNLOAD_PAUSE = "eu.chainfire.opendelta.action.DOWNLOAD_PAUSE";

    private static final String NOTIFICATION_CHANNEL_ID = "eu.chainfire.opendelta.notification";
    public static final int NOTIFICATION_BUSY = 1;
    public static final int NOTIFICATION_UPDATE = 2;
    public static final int NOTIFICATION_ERROR = 3;

    public static final String PREF_READY_FILENAME_NAME = "ready_filename";
    public static final String PREF_LATEST_CHANGELOG = "latest_changelog";

    public static final String PREF_LAST_CHECK_TIME_NAME = "last_check_time";
    public static final long PREF_LAST_CHECK_TIME_DEFAULT = 0L;

    private static final String PREF_LAST_DOWNLOAD_TIME = "last_spent_download_time";
    private static final String PREF_LAST_SNOOZE_TIME_NAME = "last_snooze_time";
    private static final long PREF_LAST_SNOOZE_TIME_DEFAULT = 0L;
    // we only snooze until a new build
    private static final String PREF_SNOOZE_UPDATE_NAME = "last_snooze_update";

    public static final String PREF_PENDING_REBOOT = "pending_reboot";

    private static final String PREF_CURRENT_AB_FILENAME_NAME = "current_ab_filename";
    public static final String PREF_CURRENT_FILENAME_NAME = "current_filename";
    public static final String PREF_FILE_FLASH = "file_flash";

    private static final long SNOOZE_MS = 24 * AlarmManager.INTERVAL_HOUR;

    public static final String PREF_AUTO_UPDATE_METERED_NETWORKS = "auto_update_metered_networks";

    public static final String PREF_LATEST_FULL_NAME = "latest_full_name";
    public static final String PREF_LATEST_DELTA_NAME = "latest_delta_name";
    public static final String PREF_STOP_DOWNLOAD = "stop_download";
    public static final String PREF_DOWNLOAD_SIZE = "download_size_long";
    public static final String PREF_DELTA_SIGNATURE = "delta_signature";
    public static final String PREF_INITIAL_FILE = "initial_file";

    public static final int PREF_AUTO_DOWNLOAD_DISABLED = 0;
    public static final int PREF_AUTO_DOWNLOAD_CHECK = 1;
    public static final int PREF_AUTO_DOWNLOAD_FULL = 2;

    public static final int PREF_STOP_DOWNLOAD_STOP = 0;
    public static final int PREF_STOP_DOWNLOAD_PAUSE = 1;
    public static final int PREF_STOP_DOWNLOAD_RESUME = 2;


    public static final String PREF_AUTO_DOWNLOAD_CHECK_STRING = String.valueOf(PREF_AUTO_DOWNLOAD_CHECK);
    public static final String PREF_AUTO_DOWNLOAD_DISABLED_STRING = String.valueOf(PREF_AUTO_DOWNLOAD_DISABLED);

    private Config mConfig;

    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private String mState = STATE_ACTION_NONE;

    private NetworkState mNetworkState;
    private BatteryState mBatteryState;
    private ScreenState mScreenState;

    private Scheduler mScheduler;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    private NotificationManager mNotificationManager;
    private boolean mIsUpdateRunning;
    private int mStopDownload = -1;
    private int mFailedUpdateCount;
    private SharedPreferences mPrefs;
    private Notification.Builder mFlashNotificationBuilder;
    private Notification.Builder mDownloadNotificationBuilder;

    // url override
    private boolean mIsUrlOverride;
    private String mSumUrlOvr;

    private long[] mLastProgressTime;
    private final DeltaInfo.ProgressListener mProgressListener = new DeltaInfo.ProgressListener() {
        private String status;

        @Override
        public void onProgress(float progress, long current, long total) {
            long now = SystemClock.elapsedRealtime();
            if (now >= mLastProgressTime[0] + 250L) {
                long ms = SystemClock.elapsedRealtime() - mLastProgressTime[1];
                int sec = (int) (((((float) total / (float) current) * (float) ms) - ms) / 1000f);
                updateState(STATE_ACTION_AB_FLASH, progress, current, total, this.status, ms);
                setFlashNotificationProgress((int) progress, sec);
                mLastProgressTime[0] = now;
            }
        }

        public void setStatus(String status) {
            this.status = status;
        }
    };

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        UpdateService getService() {
            return UpdateService.this;
        }
    }

    private StateCallback mStateCallback;

    public interface StateCallback {
        void updateState(String state, Float progress,
                Long current, Long total, String filename,
                Long ms, int errorCode);
    }

    public void registerStateCallback(StateCallback callback) {
        mStateCallback = callback;
        updateState(mState);
    }

    public void unregisterStateCallback() {
        mStateCallback = null;
    }

    /*
     * Using reflection voodoo instead calling the hidden class directly, to
     * dev/test outside of AOSP tree
     */
    private void setPermissions(String path, int uid) {
        try {
            Class<?> FileUtils = getClassLoader().loadClass(
                    "android.os.FileUtils");
            Method setPermissions = FileUtils.getDeclaredMethod(
                    "setPermissions", String.class, int.class,
                    int.class, int.class);
            setPermissions.invoke(
                    null,
                    path, 420,
                    uid, 2001);
        } catch (Exception e) {
            // A lot of voodoo could go wrong here, return failure instead of
            // crashing
            Logger.ex(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate() {
        super.onCreate();

        mConfig = Config.getInstance(this);

        mWakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(
                        mConfig.getKeepScreenOn() ? PowerManager.SCREEN_DIM_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                : PowerManager.PARTIAL_WAKE_LOCK,
                        "OpenDelta:WakeLock");
        mWifiLock = ((WifiManager) getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL,
                        "OpenDelta:WifiLock");

        mHandlerThread = new HandlerThread("OpenDelta Service Thread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        createNotificationChannel();

        mScheduler = new Scheduler(this, this);
        int autoDownload = getAutoDownloadValue();
        if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            mScheduler.start();
        }
        mNetworkState = new NetworkState();
        mNetworkState.start(this, this);

        mBatteryState = new BatteryState();
        mBatteryState.start(this, this,
                Integer.parseInt(mPrefs.getString(SettingsActivity.PREF_BATTERY_LEVEL, "50")),
                mPrefs.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true));

        mScreenState = new ScreenState();
        mScreenState.start(this, this);

        mPrefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        mNetworkState.stop();
        mBatteryState.stop();
        mScreenState.stop();
        mHandlerThread.quitSafely();

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            performAction(intent);
        }
        return START_STICKY;
    }

    public synchronized void performAction(Intent intent) {
        String action = intent.getAction();
        if (action == null) action = "";
        switch (action) {
            case ACTION_CHECK:
                if (checkPermissions())
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_CHECK);
                break;
            case ACTION_FLASH:
                if (checkPermissions()) {
                    if (Config.isABDevice()) flashABUpdate();
                    else flashUpdate();
                }
                break;
            case ACTION_ALARM:
                mScheduler.alarm(intent.getIntExtra(EXTRA_ALARM_ID, -1));
                break;
            case ACTION_NOTIFICATION_DELETED:
                mPrefs.edit().putLong(PREF_LAST_SNOOZE_TIME_NAME,
                        System.currentTimeMillis()).apply();
                String lastBuild = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
                if (lastBuild != null) {
                    // only snooze until no newer build is available
                    Logger.i("Snoozing notification for " + lastBuild);
                    mPrefs.edit().putString(PREF_SNOOZE_UPDATE_NAME, lastBuild).apply();
                }
                break;
            case ACTION_BUILD:
                if (checkPermissions())
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_FULL);
                break;
            case ACTION_UPDATE:
                autoState(true, PREF_AUTO_DOWNLOAD_CHECK, false);
                break;
            case ACTION_CLEAR_INSTALL_RUNNING:
                ABUpdate.setInstallingUpdate(false, this);
                mIsUpdateRunning = false;
                break;
            case ACTION_FLASH_FILE:
                if (intent.hasExtra(EXTRA_FILENAME)) {
                    String flashFilename = intent.getStringExtra(EXTRA_FILENAME);
                    setFlashFilename(flashFilename);
                }
                break;
            case ACTION_DOWNLOAD_STOP:
                mStopDownload = PREF_STOP_DOWNLOAD_STOP;
                if (mNotificationManager != null)
                    mNotificationManager.cancel(NOTIFICATION_BUSY);
                // if we have a paused download in progress we need to manually stop it
                if (mState.equals(STATE_ERROR_DOWNLOAD_RESUME) ||
                        mState.equals(STATE_ACTION_DOWNLOADING_PAUSED)) {
                    // to do so we just need to remove the file and update state
                    File[] files = new File(mConfig.getPathBase()).listFiles();
                    for (File file : files)
                        if (file.isFile() && file.getName().endsWith(".part"))
                            file.delete();
                    autoState(true, PREF_AUTO_DOWNLOAD_CHECK, false);
                }
                break;
            case ACTION_DOWNLOAD_PAUSE:
                final boolean isPaused = mState.equals(STATE_ACTION_DOWNLOADING_PAUSED) ||
                        mState.equals(STATE_ERROR_DOWNLOAD_RESUME);
                if (isPaused) {
                    // resume
                    mStopDownload = -1;
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_FULL);
                } else {
                    // pause
                    mStopDownload = PREF_STOP_DOWNLOAD_PAUSE;
                    autoState(true, PREF_AUTO_DOWNLOAD_CHECK, false);
                }
                break;
            default:
                autoState(false, PREF_AUTO_DOWNLOAD_CHECK, false);
                break;
        }
    }

    private void updateState(String state) {
        updateState(state, null, null, null, null, null);
    }

    private void updateState(String state, Float progress,
            Long current, Long total, String filename, Long ms) {
        updateState(state, progress, current,  total,  filename,  ms, -1);
    }

    private synchronized void updateState(String state, Float progress,
            Long current, Long total, String filename, Long ms, int errorCode) {
        mState = state;
        if (mStateCallback != null)
            mStateCallback.updateState(state, progress, current,
                    total, filename, ms, errorCode);
    }

    public synchronized String getState() {
        return mState;
    }

    @Override
    public void onNetworkState(boolean state) {
        Logger.d("network state --> %d", state ? 1 : 0);
    }

    @Override
    public void onBatteryState(boolean state) {
        Logger.d("battery state --> %d", state ? 1 : 0);
    }

    @Override
    public void onScreenState(boolean state) {
        Logger.d("screen state --> %d", state ? 1 : 0);
        mScheduler.onScreenState(state);
    }

    @Override
    public boolean onWantUpdateCheck() {
        if (isProgressState(mState)) {
            Logger.i("Blocked scheduler requests while running in state " + mState);
            return false;
        }
        Logger.i("Scheduler requests check for updates");
        int autoDownload = getAutoDownloadValue();
        if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            return checkForUpdates(false, autoDownload);
        }
        return false;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Logger.d("onSharedPreferenceChanged " + key);
        switch (key) {
            case PREF_AUTO_UPDATE_METERED_NETWORKS:
                mNetworkState.setMeteredAllowed(sharedPreferences.getBoolean(
                        PREF_AUTO_UPDATE_METERED_NETWORKS, false));
                break;
            case SettingsActivity.PREF_AUTO_DOWNLOAD:
                int autoDownload = getAutoDownloadValue();
                if (autoDownload == PREF_AUTO_DOWNLOAD_DISABLED)
                    mScheduler.stop();
                else
                    mScheduler.start();
                break;
            default:
                break;
        }
        if (mBatteryState != null)
            mBatteryState.onSharedPreferenceChanged(sharedPreferences, key);
        if (mScheduler != null)
            mScheduler.onSharedPreferenceChanged(sharedPreferences, key);
    }

    private void autoState(boolean userInitiated, int checkOnly, boolean notify) {
        Logger.d("autoState state = " + mState + " userInitiated = " + userInitiated + " checkOnly = " + checkOnly);

        if (isErrorState(mState)) {
            return;
        }

        // Check if a previous update was done already
        if (checkForFinishedUpdate()) return;

        // Check if we're currently installing an A/B update
        if (Config.isABDevice() && ABUpdate.isInstallingUpdate(this)) {
            // resume listening to progress
            final String flashFilename = mPrefs.getString(PREF_CURRENT_AB_FILENAME_NAME, null);
            final String _filename = new File(flashFilename).getName();
            if (mLastProgressTime == null)
                mLastProgressTime = new long[] { 0, SystemClock.elapsedRealtime() };
            mProgressListener.setStatus(_filename);
            updateState(STATE_ACTION_AB_FLASH, 0f, 0L, 100L, _filename, null);
            mIsUpdateRunning = ABUpdate.resume(flashFilename, mProgressListener, this);
            if (!mIsUpdateRunning) {
                stopNotification();
                updateState(STATE_ERROR_AB_FLASH);
            } else {
                newFlashNotification(_filename);
            }
            return;
        }

        String filename = mPrefs.getString(PREF_READY_FILENAME_NAME, null);

        if (filename != null) {
            if (!(new File(filename)).exists()) {
                filename = null;
            }
        }

        // check if we have a .part file that was saved as latest
        String latestFullBuild = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
        if (latestFullBuild != null) {
            File found = null;
            File[] files = new File(mConfig.getPathBase()).listFiles();
            for (File file : files) {
                String currName = file.getName();
                if (file.isFile() && currName.endsWith(".part")) {
                    if (currName.equals(latestFullBuild + ".part"))
                        found = file;
                    else
                        file.delete(); // remove old .part files
                }
            }
            if (found != null) {
                long total = mPrefs.getLong(PREF_DOWNLOAD_SIZE, 1500000000L /* 1.5 GB */);
                final long current = found.length();
                final long lastTime = mPrefs.getLong(PREF_LAST_DOWNLOAD_TIME, 0);
                final float progress = ((float) current / (float) total) * 100f;
                updateState(STATE_ACTION_DOWNLOADING_PAUSED, progress, current, total, latestFullBuild, lastTime);
                // display paused notification with the proper title
                newDownloadNotification(true, getString(R.string.state_action_downloading_paused));
                mDownloadNotificationBuilder.setProgress(100, Math.round(progress), false);
                mNotificationManager.notify(NOTIFICATION_BUSY, mDownloadNotificationBuilder.build());
                return;
            }
        }

        // if the file has been downloaded or creates anytime before
        // this will always be more important
        if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK && filename == null) {
            Logger.d("Checking step done");
            if (!updateAvailable()) {
                Logger.d("System up to date");
                updateState(STATE_ACTION_NONE, null, null, null, null,
                        mPrefs.getLong(PREF_LAST_CHECK_TIME_NAME,
                                PREF_LAST_CHECK_TIME_DEFAULT));
            } else {
                Logger.d("Update available");
                updateState(STATE_ACTION_BUILD, null, null, null, null,
                        mPrefs.getLong(PREF_LAST_CHECK_TIME_NAME,
                                PREF_LAST_CHECK_TIME_DEFAULT));
                if (!userInitiated && notify) {
                    if (!isSnoozeNotification()) {
                        startNotification();
                    } else {
                        Logger.d("notification snoozed");
                    }
                }
            }
            return;
        }

        if (filename == null) {
            Logger.d("System up to date");
            updateState(STATE_ACTION_NONE, null, null, null, null,
                    mPrefs.getLong(PREF_LAST_CHECK_TIME_NAME,
                            PREF_LAST_CHECK_TIME_DEFAULT));
        } else {
            Logger.d("Update found: %s", filename);
            updateState(STATE_ACTION_READY, null, null, null, (new File(
                    filename)).getName(), mPrefs.getLong(
                            PREF_LAST_CHECK_TIME_NAME, PREF_LAST_CHECK_TIME_DEFAULT));

            if (!userInitiated && notify) {
                if (!isSnoozeNotification()) {
                    startNotification();
                } else {
                    Logger.d("notification snoozed");
                }
            }
        }
    }

    private PendingIntent getNotificationIntent(boolean delete) {
        if (delete) {
            Intent notificationIntent = new Intent(this, UpdateService.class);
            notificationIntent.setAction(ACTION_NOTIFICATION_DELETED);
            return PendingIntent.getService(this, 0, notificationIntent, PendingIntent.FLAG_MUTABLE);
        } else {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setAction(ACTION_SYSTEM_UPDATE_SETTINGS);
            return PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_MUTABLE);
        }
    }

    private void startNotification() {
        final String latestFull = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
        if (latestFull == null) {
            return;
        }
        String flashFilename = mPrefs.getString(PREF_READY_FILENAME_NAME, null);
        final boolean readyToFlash = flashFilename != null;
        if (readyToFlash) {
            flashFilename = new File(flashFilename).getName();
            flashFilename.substring(0, flashFilename.lastIndexOf('.'));
        }

        String notifyFileName = readyToFlash ? flashFilename : latestFull.substring(0, latestFull.lastIndexOf('.'));

        mNotificationManager.notify(
                NOTIFICATION_UPDATE,
                (new Notification.Builder(this, NOTIFICATION_CHANNEL_ID))
                .setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(readyToFlash ? getString(R.string.notify_title_flash) : getString(R.string.notify_title_download))
                .setShowWhen(true)
                .setContentIntent(getNotificationIntent(false))
                .setDeleteIntent(getNotificationIntent(true))
                .setContentText(notifyFileName).build());
    }

    private void newFlashNotification(String filename) {
        mFlashNotificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        mFlashNotificationBuilder.setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(getString(R.string.state_action_ab_flash))
                .setShowWhen(true)
                .setOngoing(true)
                .setContentIntent(getNotificationIntent(false))
                .setContentText(filename);
        setFlashNotificationProgress(0, 0);
    }

    private void newDownloadNotification(boolean isPaused, String title) {
        List<Notification.Action> actions = new ArrayList<>();
        // actions
        Intent stopIntent = new Intent(this, UpdateService.class);
        stopIntent.setAction(ACTION_DOWNLOAD_STOP);
        PendingIntent sPI = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_MUTABLE);
        Intent pauseIntent = new Intent(this, UpdateService.class);
        pauseIntent.setAction(ACTION_DOWNLOAD_PAUSE);
        PendingIntent cPI = PendingIntent.getService(this, 0, pauseIntent, PendingIntent.FLAG_MUTABLE);
        actions.add(new Notification.Action.Builder(
            0,
            getResources().getText(R.string.button_stop_text, ""),
            sPI
        ).build());
        actions.add(new Notification.Action.Builder(
            0,
            getResources().getText(isPaused
                    ? R.string.button_resume_text
                    : R.string.button_pause_text),
            cPI
        ).build());
        mDownloadNotificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        mDownloadNotificationBuilder.setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(title)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentIntent(getNotificationIntent(false));
        for (Notification.Action action : actions)
            mDownloadNotificationBuilder.addAction(action);
    }

    private void startABRebootNotification(String filename) {
        String flashFilename = filename;
        flashFilename = new File(flashFilename).getName();
        flashFilename.substring(0, flashFilename.lastIndexOf('.'));

        mNotificationManager.notify(
                NOTIFICATION_UPDATE,
                (new Notification.Builder(this, NOTIFICATION_CHANNEL_ID))
                .setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(getString(R.string.state_action_ab_finished))
                .setShowWhen(true)
                .setContentIntent(getNotificationIntent(false))
                .setDeleteIntent(getNotificationIntent(true))
                .setContentText(flashFilename).build());
    }

    private void stopNotification() {
        mNotificationManager.cancel(NOTIFICATION_UPDATE);
    }

    private void startErrorNotification() {
        String errorStateString = null;
        try {
            errorStateString = getString(getResources().getIdentifier(
                    "state_" + mState, "string", getPackageName()));
        } catch (Exception e) {
            // String for this state could not be found (displays empty string)
            Logger.ex(e);
        }
        if (errorStateString != null) {
            mNotificationManager.notify(
                    NOTIFICATION_ERROR,
                    (new Notification.Builder(this, NOTIFICATION_CHANNEL_ID))
                    .setSmallIcon(R.drawable.stat_notify_error)
                    .setContentTitle(getString(R.string.notify_title_error))
                    .setContentText(errorStateString)
                    .setShowWhen(true)
                    .setContentIntent(getNotificationIntent(false)).build());
        }
    }

    private void stopErrorNotification() {
        mNotificationManager.cancel(NOTIFICATION_ERROR);
    }

    private HttpsURLConnection setupHttpsRequest(String urlStr) {
        return setupHttpsRequest(urlStr, 0);
    }

    private HttpsURLConnection setupHttpsRequest(String urlStr, long offset) {
        URL url;
        HttpsURLConnection urlConnection;
        try {
            url = new URL(urlStr);
            urlConnection = (HttpsURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(HTTP_CONNECTION_TIMEOUT);
            urlConnection.setReadTimeout(HTTP_READ_TIMEOUT);
            urlConnection.setRequestMethod("GET");
            urlConnection.setDoInput(true);
            if (offset > 0)
                urlConnection.setRequestProperty("Range", "bytes=" + offset + "-");
            urlConnection.connect();
            int code = urlConnection.getResponseCode();
            if (code != HttpsURLConnection.HTTP_OK
                    && code != HttpsURLConnection.HTTP_PARTIAL) {
                Logger.d("response: %d", code);
                return null;
            }
            return urlConnection;
        } catch (Exception e) {
            Logger.i("Failed to connect to server");
            Logger.ex(e);
            return null;
        }
    }

    private byte[] downloadUrlMemory(String url) {
        Logger.d("download: %s", url);

        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null) {
                return null;
            }

            int len = urlConnection.getContentLength();
            if ((len >= 0) && (len < 1024 * 1024)) {
                InputStream is = urlConnection.getInputStream();
                int byteInt;
                ByteArrayOutputStream byteArray = new ByteArrayOutputStream();

                while((byteInt = is.read()) >= 0){
                    byteArray.write(byteInt);
                }

                return byteArray.toByteArray();
            }
            return null;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return null;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private String downloadUrlMemoryAsString(String url) {
        Logger.d("download: %s", url);

        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null){
                return null;
            }

            InputStream is = urlConnection.getInputStream();
            ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
            int byteInt;

            while((byteInt = is.read()) >= 0){
                byteArray.write(byteInt);
            }

            byte[] bytes = byteArray.toByteArray();
            if (bytes == null){
                return null;
            }

            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return null;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private boolean downloadUrlFile(String url, File f, String matchSUM,
            DeltaInfo.ProgressListener progressListener) {
        Logger.d("download: %s", url);

        HttpsURLConnection urlConnection = null;
        MessageDigest digest = null;
        if (matchSUM != null) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                // No SHA-256 algorithm support
                Logger.ex(e);
            }
        }

        if (f.exists()) f.delete();

        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null){
                return false;
            }
            long len = urlConnection.getContentLength();
            long recv = 0;
            if ((len > 0) && (len < 4L * 1024L * 1024L * 1024L)) {
                byte[] buffer = new byte[262144];

                InputStream is = urlConnection.getInputStream();
                try (FileOutputStream os = new FileOutputStream(f, false)) {
                    int r;
                    while ((r = is.read(buffer)) > 0) {
                        if (mStopDownload >= 0) {
                            return false;
                        }
                        os.write(buffer, 0, r);
                        if (digest != null)
                            digest.update(buffer, 0, r);

                        recv += r;
                        if (progressListener != null)
                            progressListener.onProgress(
                                    ((float) recv / (float) len) * 100f, recv,
                                    len);
                    }
                }

                if (digest != null) {
                    StringBuilder SUM = new StringBuilder(new BigInteger(1, digest.digest())
                            .toString(16).toLowerCase(Locale.ENGLISH));
                    while (SUM.length() < 64)
                        SUM.insert(0, "0");
                    boolean sumCheck = SUM.toString().equals(matchSUM);
                    Logger.d("SUM=" + SUM + " matchSUM=" + matchSUM);
                    Logger.d("SUM.length=" + SUM.length() +
                            " matchSUM.length=" + matchSUM.length());
                    if (!sumCheck) {
                        Logger.i("SUM check failed for " + url);
                    }
                    return sumCheck;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return false;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private boolean downloadUrlFileUnknownSize(String url, final File f,
            String matchSUM) {
        Logger.d("download: %s", url);

        HttpsURLConnection urlConnection = null;
        InputStream is = null;
        FileOutputStream os = null;
        MessageDigest digest = null;
        long len = 0;
        if (matchSUM != null) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                // No SHA-256 algorithm support
                Logger.ex(e);
            }
        }

        long lastTime = SystemClock.elapsedRealtime();
        long offset = 0;
        if (f.exists()) offset = f.length();

        try {
            final String userFN = f.getName().substring(0, f.getName().length() - 5);
            updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, 0L, userFN, null);
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null) return false;

            len = urlConnection.getContentLength();
            mPrefs.edit().putLong(PREF_DOWNLOAD_SIZE, len).apply();
            if (offset > 0 && offset < len) {
                urlConnection.disconnect();
                urlConnection = setupHttpsRequest(url, offset);
                if (urlConnection == null) return false;
                Logger.d("Resuming download at: " + offset);
            }

            updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, len, userFN, null);

            long freeSpace = (new StatFs(mConfig.getPathBase()))
                    .getAvailableBytes();
            if (freeSpace < len - offset) {
                updateState(STATE_ERROR_DISK_SPACE, null, freeSpace, len, null,
                        null);
                Logger.d("not enough space!");
                return false;
            }

            if (offset > 0)
                lastTime -= mPrefs.getLong(PREF_LAST_DOWNLOAD_TIME, 0);
            final long[] last = new long[] { 0, len, 0, lastTime };
            DeltaInfo.ProgressListener progressListener = new DeltaInfo.ProgressListener() {
                @Override
                public void onProgress(float progress, long current, long total) {
                    current += last[0];
                    total = last[1];
                    progress = ((float) current / (float) total) * 100f;
                    long now = SystemClock.elapsedRealtime();
                    if (now >= last[2] + 250L) {
                        updateState(STATE_ACTION_DOWNLOADING, progress,
                                current, total, userFN, now - last[3]);
                        setDownloadNotificationProgress(progress, current,
                                total,now - last[3]);
                        last[2] = now;
                    }
                }

                public void setStatus(String s){
                    // do nothing
                }
            };

            long recv = offset;
            if ((len > 0) && (len < 4L * 1024L * 1024L * 1024L)) {
                mIsUpdateRunning = true;
                byte[] buffer = new byte[262144];

                is = urlConnection.getInputStream();
                os = new FileOutputStream(f, offset > 0);
                int r;
                while ((r = is.read(buffer)) > 0) {
                    if (mStopDownload >= 0) {
                        return false;
                    }
                    os.write(buffer, 0, r);
                    if (offset == 0 && digest != null)
                        digest.update(buffer, 0, r);

                    recv += r;
                    progressListener.onProgress(
                            ((float) recv / (float) len) * 100f,
                            recv, len);
                }

                StringBuilder SUM;
                if (offset > 0) {
                    SUM = new StringBuilder(getFileSHA256(f, getSUMProgress(STATE_ACTION_CHECKING_SUM, f.getName())));
                } else {
                    if (digest == null) return false;
                    SUM = new StringBuilder(new BigInteger(1, digest.digest())
                            .toString(16).toLowerCase(Locale.ENGLISH));
                }
                while (SUM.length() < 64)
                    SUM.insert(0, "0");
                boolean sumCheck = SUM.toString().equals(matchSUM);
                Logger.d("SUM=" + SUM + " matchSUM=" + matchSUM);
                Logger.d("SUM.length=" + SUM.length() +
                        " matchSUM.length=" + matchSUM.length());
                if (!sumCheck) {
                    mIsUpdateRunning = false;
                    Logger.i("SUM check failed for " + url);
                    // if sum does not match when done, get rid
                    f.delete();
                    updateState(STATE_ERROR_DOWNLOAD);
                }
                return sumCheck;
            }
            return false;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            mIsUpdateRunning = false;
            Logger.ex(e);
            mPrefs.edit().putLong(PREF_LAST_DOWNLOAD_TIME,
                    SystemClock.elapsedRealtime() - lastTime).apply();
            if (urlConnection != null) urlConnection.disconnect();
            try { if (is != null) is.close(); } catch (IOException ignored) {}
            try { if (os != null) os.close(); } catch (IOException ignored) {}
            mNotificationManager.cancel(NOTIFICATION_BUSY);
            return false;
        } finally {
            mIsUpdateRunning = false;
            if (urlConnection != null) urlConnection.disconnect();
            try { if (is != null) is.close(); } catch (IOException ignored) {}
            try { if (os != null) os.close(); } catch (IOException ignored) {}
        }
    }

    private long getUrlDownloadSize(String url) {
        Logger.d("getUrlDownloadSize: %s", url);

        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null){
                return 0;
            }

            return urlConnection.getContentLength();
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return 0;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private boolean isMatchingImage(String fileName) {
        try {
            Logger.d("Image check for file name: " + fileName);
            if (fileName.endsWith(".zip") && fileName.contains(mConfig.getDevice())) {
                String[] parts = fileName.split("-");
                if (parts.length > 1) {
                    Logger.d("isMatchingImage: check " + fileName);
                    String version = parts[1];
                    Version current = new Version(mConfig.getAndroidVersion());
                    Version fileVersion = new Version(version);
                    if (fileVersion.compareTo(current) >= 0) {
                        Logger.d("isMatchingImage: ok " + fileName);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Logger.ex(e);
        }
        return false;
    }

    private List<String> getNewestFullBuild() {
        Logger.d("Checking for latest full build");

        String url = mConfig.getUrlBaseJson();

        String buildData = downloadUrlMemoryAsString(url);
        if (buildData == null || buildData.length() == 0) {
            updateState(STATE_ERROR_DOWNLOAD, null, null, null, url, null);
            mNotificationManager.cancel(NOTIFICATION_BUSY);
            return null;
        }
        JSONObject object;
        try {
            object = new JSONObject(buildData);
            JSONArray updatesList = object.getJSONArray("response");
            String latestBuild = null;
            String urlOverride = null;
            String sumOverride = null;
            for (int i = 0; i < updatesList.length(); i++) {
                if (updatesList.isNull(i)) {
                    continue;
                }
                try {
                    JSONObject build = updatesList.getJSONObject(i);
                    String fileName = new File(build.getString("filename")).getName();
                    String urlOvr = null;
                    String sumOvr = null;
                    if (build.has("url"))
                        urlOvr = build.getString("url");
                    if (build.has("sha256url"))
                        sumOvr = build.getString("sha256url");
                    Logger.d("parsed from json:");
                    Logger.d("fileName= " + fileName);
                    if (isMatchingImage(fileName))
                        latestBuild = fileName;
                    if (urlOvr != null && !urlOvr.equals("")) {
                        urlOverride = urlOvr;
                        Logger.d("url= " + urlOverride);
                    }
                    if (sumOvr != null && !sumOvr.equals("")) {
                        sumOverride = sumOvr;
                        Logger.d("sha256 url= " + sumOverride);
                    }
                } catch (JSONException e) {
                    Logger.ex(e);
                }
            }

            List<String> ret = new ArrayList<>();
            if (latestBuild != null) {
                ret.add(latestBuild);
                if (urlOverride != null) {
                    ret.add(urlOverride);
                    if (sumOverride != null) {
                        ret.add(sumOverride);
                        mIsUrlOverride = true;
                        mSumUrlOvr = sumOverride;
                    }
                }
            }
            return ret;

        } catch (Exception e) {
            Logger.ex(e);
        }
        updateState(STATE_ERROR_UNOFFICIAL, null, null, null, mConfig.getVersion(), null);
        return null;
    }

    private DeltaInfo.ProgressListener getSUMProgress(String state,
                                                      String filename) {
        final long[] last = new long[] { 0, SystemClock.elapsedRealtime() };
        final String _state = state;
        final String _filename = filename;

        return new DeltaInfo.ProgressListener() {
            @Override
            public void onProgress(float progress, long current, long total) {
                long now = SystemClock.elapsedRealtime();
                if (now >= last[0] + 16L) {
                    updateState(_state, progress, current, total, _filename,
                            SystemClock.elapsedRealtime() - last[1]);
                    last[0] = now;
                }
            }
            public void setStatus(String s) {
                // do nothing
            }
        };
    }

    private long sizeOnDisk(long size) {
        // Assuming 256k block size here, should be future-proof for a little
        // bit
        long blocks = (size + 262143L) / 262144L;
        return blocks * 262144L;
    }

    private boolean downloadDeltaFile(String url_base,
            DeltaInfo.FileBase fileBase, DeltaInfo.FileSizeSHA256 match,
            DeltaInfo.ProgressListener progressListener, boolean force) {
        if (fileBase.getTag() == null) {
            if (force || mNetworkState.getState()) {
                String url = url_base + fileBase.getName();
                String fn = mConfig.getPathBase() + fileBase.getName();
                File f = new File(fn);
                Logger.d("download: %s --> %s", url, fn);

                if (downloadUrlFile(url, f, match.getSHA256(), progressListener)) {
                    fileBase.setTag(fn);
                    Logger.d("success");
                    return true;
                } else {
                    f.delete();
                    if (mStopDownload >= 0) {
                        Logger.d("download stopped");
                    } else {
                        updateState(STATE_ERROR_DOWNLOAD, null, null, null,
                                fn, null);
                        Logger.d("download error");
                        mNotificationManager.cancel(NOTIFICATION_BUSY);
                    }
                    return false;
                }
            } else {
                Logger.d("aborting download due to network state");
                return false;
            }
        } else {
            Logger.d("have %s already", fileBase.getName());
            return true;
        }
    }

    private Thread getThreadedProgress(String filename, String display,
            long start, long currentOut, long totalOut) {
        final File _file = new File(filename);
        final String _display = display;
        final long _currentOut = currentOut;
        final long _totalOut = totalOut;
        final long _start = start;

        return new Thread(() -> {
            while (true) {
                try {
                    long current = _currentOut + _file.length();
                    updateState(STATE_ACTION_APPLYING_PATCH,
                            ((float) current / (float) _totalOut) * 100f,
                            current, _totalOut, _display,
                            SystemClock.elapsedRealtime() - _start);

                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    // We're being told to quit
                    break;
                }
            }
        });
    }

    private boolean zipadjust(String filenameIn, String filenameOut,
            long start, long currentOut, long totalOut) {
        Logger.d("zipadjust [%s] --> [%s]", filenameIn, filenameOut);

        // checking file sizes in the background as progress, because these
        // native functions don't have callbacks (yet) to do this

        (new File(filenameOut)).delete();

        Thread progress = getThreadedProgress(filenameOut,
                (new File(filenameIn)).getName(), start, currentOut, totalOut);
        progress.start();

        int ok = Native.zipadjust(filenameIn, filenameOut, 1);

        progress.interrupt();
        try {
            progress.join();
        } catch (InterruptedException e) {
            // We got interrupted in a very short wait, surprising, but not a
            // problem. 'progress' will quit by itself.
            Logger.ex(e);
        }

        Logger.d("zipadjust --> %d", ok);

        return (ok == 1);
    }

    private boolean dedelta(String filenameSource, String filenameDelta,
            String filenameOut, long start, long currentOut, long totalOut) {
        Logger.d("dedelta [%s] --> [%s] --> [%s]", filenameSource,
                filenameDelta, filenameOut);

        // checking file sizes in the background as progress, because these
        // native functions don't have callbacks (yet) to do this

        (new File(filenameOut)).delete();

        Thread progress = getThreadedProgress(filenameOut, (new File(
                filenameDelta)).getName(), start, currentOut, totalOut);
        progress.start();

        int ok = Native.dedelta(filenameSource, filenameDelta, filenameOut);

        progress.interrupt();
        try {
            progress.join();
        } catch (InterruptedException e) {
            // We got interrupted in a very short wait, surprising, but not a
            // problem. 'progress' will quit by itself.
            Logger.ex(e);
        }

        Logger.d("dedelta --> %d", ok);

        return (ok == 1);
    }

    private boolean checkForUpdates(boolean userInitiated, int checkOnly) {
        /*
         * Unless the user is specifically asking to check for updates, we only
         * check for them if we have a connection matching the user's set
         * preferences, we're charging and/or have juice aplenty (>50), and the screen
         * is off
         *
         * if user has enabled checking only we only check the screen state
         * cause the amount of data transferred for checking is not very large
         */

        if ((mNetworkState == null) || (mBatteryState == null)
                || (mScreenState == null))
            return false;

        // Check if a previous update was done already
        if (checkForFinishedUpdate()) return false;

        Logger.d("checkForUpdates checkOnly = " + checkOnly + " mIsUpdateRunning = " + mIsUpdateRunning + " userInitiated = " + userInitiated +
                " mNetworkState.getState() = " + mNetworkState.getState() + " mBatteryState.getState() = " + mBatteryState.getState() +
                " mScreenState.getState() = " + mScreenState.getState());

        if (mIsUpdateRunning) {
            Logger.i("Ignoring request to check for updates - busy");
            return false;
        }

        clearState();
        stopNotification();
        stopErrorNotification();

        // so we have a time even in the error case
        mPrefs.edit().putLong(PREF_LAST_CHECK_TIME_NAME, System.currentTimeMillis()).apply();

        if (!isSupportedVersion()) {
            // TODO - to be more generic this should maybe use the info from getNewestFullBuild
            updateState(STATE_ERROR_UNOFFICIAL, null, null, null, mConfig.getVersion(), null);
            Logger.i("Ignoring request to check for updates - not compatible for update! " + mConfig.getVersion());
            return false;
        }
        if (!mNetworkState.isConnected()) {
            updateState(STATE_ERROR_CONNECTION);
            Logger.i("Ignoring request to check for updates - no data connection");
            return false;
        }
        boolean updateAllowed = false;
        if (!userInitiated) {
            updateAllowed = checkOnly >= PREF_AUTO_DOWNLOAD_CHECK;
            if (checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
                // must confirm to all if we may auto download
                updateAllowed = mNetworkState.getState()
                        && mBatteryState.getState() && isScreenStateEnabled();
                if (!updateAllowed) {
                    // fallback to check only
                    checkOnly = PREF_AUTO_DOWNLOAD_CHECK;
                    updateAllowed = true;
                    Logger.i("Auto-download not possible - fallback to check only");
                }
            }
        }

        if (userInitiated || updateAllowed) {
            Logger.i("Starting check for updates");
            checkForUpdatesAsync(userInitiated, checkOnly);
            return true;
        } else {
            Logger.i("Ignoring request to check for updates");
        }
        return false;
    }

    private long getDeltaDownloadSize(List<DeltaInfo> deltas) {
        updateState(STATE_ACTION_CHECKING);

        long deltaDownloadSize = 0L;
        for (DeltaInfo di : deltas) {
            String fn = mConfig.getPathBase() + di.getUpdate().getName();
            if (di.getUpdate().match(
                    new File(fn),
                    true,
                    getSUMProgress(STATE_ACTION_CHECKING_SUM, di.getUpdate()
                            .getName())) == di.getUpdate().getUpdate()) {
                di.getUpdate().setTag(fn);
            } else {
                deltaDownloadSize += di.getUpdate().getUpdate().getSize();
            }
        }

        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);
        {
            if (mConfig.getApplySignature()) {
                String fn = mConfig.getPathBase()
                        + lastDelta.getSignature().getName();
                if (lastDelta.getSignature().match(
                        new File(fn),
                        true,
                        getSUMProgress(STATE_ACTION_CHECKING_SUM, lastDelta
                                .getSignature().getName())) == lastDelta
                                .getSignature().getUpdate()) {
                    lastDelta.getSignature().setTag(fn);
                } else {
                    deltaDownloadSize += lastDelta.getSignature().getUpdate()
                            .getSize();
                }
            }
        }

        updateState(STATE_ACTION_CHECKING);

        return deltaDownloadSize;
    }

    private long getFullDownloadSize(List<DeltaInfo> deltas) {
        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);
        return lastDelta.getOut().getOfficial().getSize();
    }

    private long getRequiredSpace(List<DeltaInfo> deltas, boolean getFull) {
        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);

        long requiredSpace = 0;
        if (getFull) {
            requiredSpace += sizeOnDisk(lastDelta.getOut().getTag() != null ? 0
                    : lastDelta.getOut().getOfficial().getSize());
        } else {
            // The resulting number will be a tad more than worst case what we
            // actually need, but not dramatically so

            for (DeltaInfo di : deltas) {
                if (di.getUpdate().getTag() == null)
                    requiredSpace += sizeOnDisk(di.getUpdate().getUpdate()
                            .getSize());
            }
            if (mConfig.getApplySignature()) {
                requiredSpace += sizeOnDisk(lastDelta.getSignature()
                        .getUpdate().getSize());
            }

            long biggest = 0;
            for (DeltaInfo di : deltas)
                biggest = Math.max(biggest, sizeOnDisk(di.getUpdate()
                        .getApplied().getSize()));

            requiredSpace += 3 * sizeOnDisk(biggest);
        }

        return requiredSpace;
    }

    private String findInitialFile(List<DeltaInfo> deltas,
            String possibleMatch, boolean[] needsProcessing) {
        // Find the currently flashed ZIP
        Logger.d("findInitialFile possibleMatch = " + possibleMatch);

        DeltaInfo firstDelta = deltas.get(0);

        updateState(STATE_ACTION_SEARCHING);

        String initialFile = null;

        // Check if an original flashable ZIP is in our preferred location
        String expectedLocation = mConfig.getPathBase()
                + firstDelta.getIn().getName();
        Logger.d("findInitialFile expectedLocation = " + expectedLocation);
        DeltaInfo.FileSizeSHA256 match = null;
        if (expectedLocation.equals(possibleMatch)) {
            match = firstDelta.getIn().match(new File(expectedLocation), false,
                    null);
            if (match != null) {
                initialFile = possibleMatch;
            }
        }

        if (match == null) {
            match = firstDelta.getIn().match(
                    new File(expectedLocation),
                    true,
                    getSUMProgress(STATE_ACTION_SEARCHING_SUM, firstDelta
                            .getIn().getName()));
            if (match != null) {
                initialFile = expectedLocation;
            }
        }

        if ((needsProcessing != null) && (needsProcessing.length > 0)) {
            needsProcessing[0] = (initialFile != null)
                    && (match != firstDelta.getIn().getStore());
        }

        return initialFile;
    }

    private boolean downloadFiles(List<DeltaInfo> deltas,
                                  long totalDownloadSize, boolean force) {
        // Download all the files we do not have yet

        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);

        final String[] filename = new String[] { null };
        updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, totalDownloadSize, null,
                null);

        final long[] last = new long[] { 0, totalDownloadSize, 0,
                SystemClock.elapsedRealtime() };
        DeltaInfo.ProgressListener progressListener = new DeltaInfo.ProgressListener() {
            @Override
            public void onProgress(float progress, long current, long total) {
                current += last[0];
                total = last[1];
                progress = ((float) current / (float) total) * 100f;
                long now = SystemClock.elapsedRealtime();
                if (now >= last[2] + 250L) {
                    updateState(STATE_ACTION_DOWNLOADING, progress, current,
                            total, filename[0], SystemClock.elapsedRealtime()
                            - last[3]);
                    setDownloadNotificationProgress(progress, current, total,
                            SystemClock.elapsedRealtime() - last[3]);
                    last[2] = now;
                }
            }
            public void setStatus(String s) {
                // do nothing
            }
        };

        for (DeltaInfo di : deltas) {
            filename[0] = di.getUpdate().getName();
            if (!downloadDeltaFile(mConfig.getUrlBaseUpdate(),
                    di.getUpdate(), di.getUpdate().getUpdate(),
                    progressListener, force)) {
                return false;
            }
            last[0] += di.getUpdate().getUpdate().getSize();
        }

        if (mConfig.getApplySignature()) {
            filename[0] = lastDelta.getSignature().getName();
            if (!downloadDeltaFile(mConfig.getUrlBaseUpdate(),
                    lastDelta.getSignature(), lastDelta.getSignature()
                    .getUpdate(), progressListener, force)) {
                return false;
            }
        }
        updateState(STATE_ACTION_DOWNLOADING, 100f, totalDownloadSize,
                totalDownloadSize, null, null);

        return true;
    }

    private void downloadFullBuild(String url, String sha256Sum,
                                   String imageName) {
        String fn = mConfig.getPathBase() + imageName;
        File f = new File(fn + ".part");
        Logger.d("download: %s --> %s", url, fn);

        // get rid of old .part files if any
        File[] files = new File(mConfig.getPathBase()).listFiles();
        if (files != null) {
            for (File file : files) {
                String currName = file.getName();
                if (file.isFile() && currName.endsWith(".part")
                        && !currName.equals(f.getName())) {
                    file.delete();
                }
            }
        }

        if (downloadUrlFileUnknownSize(url, f, sha256Sum)
                && f.renameTo(new File(fn))) {
            Logger.d("success");
            mPrefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
            mNotificationManager.cancel(NOTIFICATION_BUSY);
        } else {
            if (mStopDownload == PREF_STOP_DOWNLOAD_STOP) {
                f.delete();
                Logger.d("download stopped");
                autoState(false, PREF_AUTO_DOWNLOAD_DISABLED, false);
                mNotificationManager.cancel(NOTIFICATION_BUSY);
            } else if (mStopDownload != PREF_STOP_DOWNLOAD_RESUME &&
                       !mState.equals(STATE_ERROR_DOWNLOAD)) {
                // either pause or error
                final Long current = f.length();
                final Long total = mPrefs.getLong(PREF_DOWNLOAD_SIZE, 1500000000L /* 1.5GB */);
                final Long lastTime = mPrefs.getLong(PREF_LAST_DOWNLOAD_TIME, 0);
                final float progress = ((float) current / (float) total) * 100f;
                final boolean isPause = mStopDownload == PREF_STOP_DOWNLOAD_PAUSE;
                final String newState = isPause ? STATE_ACTION_DOWNLOADING_PAUSED
                                                : STATE_ERROR_DOWNLOAD_RESUME;
                Logger.d("download " + (isPause ? "paused" : "error"));
                updateState(newState, progress, current, total, imageName, lastTime);
                // display paused notification with the proper title
                String title = getString(isPause
                        ? R.string.state_action_downloading_paused
                        : R.string.state_error_download_resume);
                newDownloadNotification(true, title);
                mDownloadNotificationBuilder.setProgress(100, Math.round(progress), false);
                mNotificationManager.notify(NOTIFICATION_BUSY, mDownloadNotificationBuilder.build());
            }
        }
    }

    /**
     * @param url - url to sha256sum file
     * @param fn - file name
     * @return true if sha256sum matches the file
     */
    private boolean checkFullBuildSHA256Sum(String url, String fn) {
        final String latestFullSUM = getLatestFullSHA256Sum(url);
        final File file = new File(fn);
        if (latestFullSUM != null){
            try {
                String fileSUM = getFileSHA256(
                        file,
                        getSUMProgress(STATE_ACTION_CHECKING_SUM,
                        file.getName()));
                boolean sumCheck = fileSUM.equals(latestFullSUM);
                Logger.d("fileSUM=" + fileSUM + " latestFullSUM=" + latestFullSUM);
                Logger.d("fileSUM.length=" + fileSUM.length() +
                        " latestFullSUM.length=" + latestFullSUM.length());
                if (sumCheck) return true;
                Logger.i("fileSUM check failed for " + url);
            } catch(Exception e) {
                // WTH knows what can comes from the server
            }
        }
        return false;
    }

    private String getFileSHA256(File file, ProgressListener progressListener) {
        String ret = null;
        int count = 0;

        long total = file.length();
        if (progressListener != null)
            progressListener.onProgress(getProgress(0, total), 0, total);

        try {
            try (FileInputStream is = new FileInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[1024];
                int r;

                while ((r = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, r);
                    count += r;
                    if (progressListener != null)
                        progressListener.onProgress(getProgress(count, total), count, total);
                }

                ret = new BigInteger(1, digest.digest())
                        .toString(16).toLowerCase(Locale.ENGLISH);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            // No SHA256 support (returns null)
            // The SHA256 of a non-existing file is null
            // Read or close error (returns null)
            Logger.ex(e);
        }

        if (progressListener != null)
            progressListener.onProgress(getProgress(total, total), total, total);

        return ret;
    }

    private boolean applyPatches(List<DeltaInfo> deltas, String initialFile,
            boolean initialFileNeedsProcessing) {
        // Create storeSigned outfile from infile + deltas

        DeltaInfo firstDelta = deltas.get(0);
        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);

        int tempFile = 0;
        String[] tempFiles = new String[] { mConfig.getPathBase() + "temp1",
                mConfig.getPathBase() + "temp2" };
        try {
            long start = SystemClock.elapsedRealtime();
            long current = 0L;
            long total = 0L;

            if (initialFileNeedsProcessing)
                total += firstDelta.getIn().getStore().getSize();
            for (DeltaInfo di : deltas)
                total += di.getUpdate().getApplied().getSize();
            if (mConfig.getApplySignature())
                total += lastDelta.getSignature().getApplied().getSize();

            if (initialFileNeedsProcessing) {
                if (!zipadjust(initialFile, tempFiles[tempFile], start,
                        current, total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null,
                            null);
                    Logger.d("zipadjust error");
                    return false;
                }
                tempFile = (tempFile + 1) % 2;
                current += firstDelta.getIn().getStore().getSize();
            }

            for (DeltaInfo di : deltas) {
                String inFile = tempFiles[(tempFile + 1) % 2];
                if (!initialFileNeedsProcessing && (di == firstDelta))
                    inFile = initialFile;
                String outFile = tempFiles[tempFile];
                if (!mConfig.getApplySignature() && (di == lastDelta))
                    outFile = mConfig.getPathBase()
                    + lastDelta.getOut().getName();

                if (!dedelta(inFile, mConfig.getPathBase()
                        + di.getUpdate().getName(), outFile, start, current,
                        total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null,
                            null);
                    Logger.d("dedelta error");
                    return false;
                }
                tempFile = (tempFile + 1) % 2;
                current += di.getUpdate().getApplied().getSize();
            }

            if (mConfig.getApplySignature()) {
                if (!dedelta(tempFiles[(tempFile + 1) % 2],
                        mConfig.getPathBase()
                        + lastDelta.getSignature().getName(),
                        mConfig.getPathBase() + lastDelta.getOut().getName(),
                        start, current, total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null,
                            null);
                    Logger.d("dedelta error");
                    return false;
                }
            }
        } finally {
            (new File(tempFiles[0])).delete();
            (new File(tempFiles[1])).delete();
        }

        return true;
    }

    private void writeString(OutputStream os, String s)
            throws IOException {
        os.write((s + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String handleUpdateCleanup() throws FileNotFoundException {
        String flashFilename = mPrefs.getString(PREF_READY_FILENAME_NAME, null);
        String initialFile = mPrefs.getString(PREF_INITIAL_FILE, null);
        boolean fileFlash = mPrefs.getBoolean(PREF_FILE_FLASH, false);

        if (flashFilename == null
                || (!fileFlash && !flashFilename.startsWith(mConfig.getPathBase()))
                || !new File(flashFilename).exists()) {
            clearState();
            throw new FileNotFoundException("flashUpdate - no valid file to flash found " + flashFilename);
        }
        // now delete the initial file
        if (initialFile != null
                && new File(initialFile).exists()
                && initialFile.startsWith(mConfig.getPathBase())){
            new File(initialFile).delete();
            Logger.d("flashUpdate - delete initial file");
        }

        return flashFilename;
    }

    protected void onUpdateCompleted(int status, int errorCode) {
        Logger.d("onUpdateCompleted status = " + status);
        stopNotification();
        mIsUpdateRunning = false;
        if (status == UpdateEngine.ErrorCodeConstants.SUCCESS) {
            mPrefs.edit().putBoolean(PREF_PENDING_REBOOT, true).commit();
            String flashFilename = mPrefs.getString(PREF_READY_FILENAME_NAME, null);
            if (flashFilename != null) {
                deleteOldFlashFile(flashFilename);
                mPrefs.edit().putString(PREF_CURRENT_FILENAME_NAME, flashFilename).commit();
            }
            startABRebootNotification(flashFilename);
            updateState(STATE_ACTION_AB_FINISHED);
        } else {
            updateState(STATE_ERROR_AB_FLASH, null, null, null, null, null, errorCode);
        }
    }

    private synchronized void setFlashNotificationProgress(int percent, int sec) {
        // max progress is 100%
        mFlashNotificationBuilder.setProgress(100, percent, false);
        String sub = "0%";
        if (percent > 0) {
            sub = String.format(Locale.ENGLISH,
                                    getString(R.string.notify_eta_remaining),
                                    percent, sec / 60, sec % 60);
        }
        mFlashNotificationBuilder.setSubText(sub);
        mNotificationManager.notify(
                    NOTIFICATION_UPDATE, mFlashNotificationBuilder.build());
    }

    private synchronized void setDownloadNotificationProgress(float progress, long current, long total, long ms) {
        // max progress is 100%
        int percent = Math.round(progress);
        mDownloadNotificationBuilder.setProgress(100, percent, false);
        // long --> int overflows FTL (progress.setXXX)
        boolean progressInK = false;
        if (total > 1024L * 1024L * 1024L) {
            progressInK = true;
            current /= 1024L;
            total /= 1024L;
        }
        String sub = "";
        if ((ms > 500) && (current > 0) && (total > 0)) {
            float kibps = ((float) current / 1024f)
                    / ((float) ms / 1000f);
            if (progressInK)
                kibps *= 1024f;
            int sec = (int) (((((float) total / (float) current) * (float) ms) - ms) / 1000f);
            if (kibps < 1024) {
                sub = String.format(Locale.ENGLISH,
                        "%2d%% · %.0f KiB/s · %02d:%02d",
                        percent, kibps, sec / 60, sec % 60);
            } else {
                sub = String.format(Locale.ENGLISH,
                        "%2d%% · %.0f MiB/s · %02d:%02d",
                        percent, kibps / 1024f, sec / 60, sec % 60);
            }
        }
        if (sub.isEmpty()) sub = String.format(Locale.ENGLISH,
                "%2d%%", percent);
        mDownloadNotificationBuilder.setSubText(sub);
        mNotificationManager.notify(
                NOTIFICATION_BUSY, mDownloadNotificationBuilder.build());
    }

    private void flashABUpdate() {
        Logger.d("flashABUpdate");
        String flashFilename;
        try {
            flashFilename = handleUpdateCleanup();
        } catch (Exception ex) {
            updateState(STATE_ERROR_AB_FLASH);
            mIsUpdateRunning = false;
            Logger.ex(ex);
            return;
        }

        // Save the filename for resuming
        mPrefs.edit().putString(PREF_CURRENT_AB_FILENAME_NAME, flashFilename).commit();

        // Clear the Download size to hide while flashing
        mPrefs.edit().putLong(PREF_DOWNLOAD_SIZE, -1).commit();

        final String _filename = new File(flashFilename).getName();
        updateState(STATE_ACTION_AB_FLASH, 0f, 0L, 100L, _filename, null);

        newFlashNotification(_filename);

        try {
            ZipFile zipFile = new ZipFile(flashFilename);
            boolean isABUpdate = ABUpdate.isABUpdate(zipFile);
            zipFile.close();
            if (isABUpdate) {
                mLastProgressTime = new long[] { 0, SystemClock.elapsedRealtime() };
                mProgressListener.setStatus(_filename);
                mIsUpdateRunning = true;
                if (!ABUpdate.start(flashFilename, mProgressListener, this)) {
                    stopNotification();
                    updateState(STATE_ERROR_AB_FLASH);
                    mIsUpdateRunning = false;
                }
            } else {
                stopNotification();
                updateState(STATE_ERROR_AB_FLASH);
                mIsUpdateRunning = false;
            }
        } catch (Exception ex) {
            Logger.ex(ex);
            mIsUpdateRunning = false;
        }
    }

    @SuppressLint({"SdCardPath", "SetWorldReadable"})
    private void flashUpdate() {
        Logger.d("flashUpdate");
        if (getPackageManager().checkPermission(
                PERMISSION_ACCESS_CACHE_FILESYSTEM, getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point",
                    PERMISSION_ACCESS_CACHE_FILESYSTEM);
            return;
        }

        if (getPackageManager().checkPermission(PERMISSION_REBOOT,
                getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point", PERMISSION_REBOOT);
            return;
        }

        boolean deltaSignature = mPrefs.getBoolean(PREF_DELTA_SIGNATURE, false);
        String flashFilename;
        try {
            flashFilename = handleUpdateCleanup();
        } catch (Exception ex) {
            updateState(STATE_ERROR_FLASH);
            Logger.ex(ex);
            return;
        }

        deleteOldFlashFile(flashFilename);
        mPrefs.edit().putString(PREF_CURRENT_FILENAME_NAME, flashFilename).commit();
        clearState();

        // Remove the path to the storage from the filename, so we get a path
        // relative to the root of the storage
        String path_sd = Environment.getExternalStorageDirectory()
                + File.separator;
        flashFilename = flashFilename.substring(path_sd.length());

        // Find additional ZIPs to flash, strip path to sd
        List<String> extras = mConfig.getFlashAfterUpdateZIPs();
        for (int i = 0; i < extras.size(); i++) {
            extras.set(i, extras.get(i).substring(path_sd.length()));
        }
        Logger.d("flashUpdate - extra files to flash " + extras);


        try {
            // TWRP - OpenRecoveryScript - the recovery will find the correct
            // storage root for the ZIPs, life is nice and easy.
            //
            // Optionally, we're injecting our own signature verification keys
            // and verifying against those. We place these keys in /cache
            // where only privileged apps can edit, contrary to the storage
            // location of the ZIP itself - anyone can modify the ZIP.
            // As such, flashing the ZIP without checking the whole-file
            // signature coming from a secure location would be a security
            // risk.
            if (mConfig.getUseTWRP()) {
                if (mConfig.getInjectSignatureEnable() && deltaSignature) {
                    Logger.d("flashUpdate - create /cache/recovery/keys");

                    try (FileOutputStream os = new FileOutputStream(
                            "/cache/recovery/keys", false)) {
                        writeString(os, mConfig.getInjectSignatureKeys());
                    }
                    setPermissions("/cache/recovery/keys",
                            Process.myUid()  /* AID_CACHE */);
                }

                Logger.d("flashUpdate - create /cache/recovery/openrecoveryscript");

                try (FileOutputStream os = new FileOutputStream(
                        "/cache/recovery/openrecoveryscript", false)) {
                    if (mConfig.getInjectSignatureEnable() && deltaSignature) {
                        writeString(os, "cmd cat /res/keys > /res/keys_org");
                        writeString(os,
                                "cmd cat /cache/recovery/keys > /res/keys");
                        writeString(os, "set tw_signed_zip_verify 1");
                        writeString(os,
                                String.format("install %s", flashFilename));
                        writeString(os, "set tw_signed_zip_verify 0");
                        writeString(os, "cmd cat /res/keys_org > /res/keys");
                        writeString(os, "cmd rm /res/keys_org");
                    } else {
                        writeString(os, "set tw_signed_zip_verify 0");
                        writeString(os,
                                String.format("install %s", flashFilename));
                    }

                    if (!mConfig.getSecureModeCurrent()) {
                        // any program could have placed these ZIPs, so ignore
                        // them in secure mode
                        for (String file : extras) {
                            writeString(os, String.format("install %s", file));
                        }
                    }
                    writeString(os, "wipe cache");
                }

                setPermissions("/cache/recovery/openrecoveryscript",
                        Process.myUid()  /* AID_CACHE */);

                Logger.d("flashUpdate - reboot to recovery");
                ((PowerManager) getSystemService(Context.POWER_SERVICE))
                        .rebootCustom(PowerManager.REBOOT_RECOVERY);
            } else {
                // AOSP recovery and derivatives
                // First copy the file to cache and decrypt it
                // Finally tell RecoverySystem to flash it via recovery
                File dst = new File(path_sd + "ota_package.zip.uncrypt");
                dst.setReadable(true, false);
                dst.setWritable(true, false);
                dst.setExecutable(true, false);
                try {
                    Logger.d("flashUpdate - copying A-only OTA package: "
                            + dst.getAbsolutePath());
                    File src = new File(path_sd + flashFilename);
                    try (FileChannel srcCh = new FileInputStream(src).getChannel();
                         FileChannel dstCh = new FileOutputStream(dst, false).getChannel()) {
                        dstCh.transferFrom(srcCh, 0, srcCh.size());
                        Logger.d("flashUpdate - installing A-only OTA package");
                        RecoverySystem.installPackage(this, dst);
                    }
                } catch (Exception e) {
                    dst.delete();
                    Logger.d("flashUpdate - Could not install OTA package:");
                    Logger.ex(e);
                    updateState(STATE_ERROR_FLASH);
                }
            }
        } catch (Exception e) {
            // We have failed to write something. There's not really anything
            // else to do at this stage than give up. No reason to crash though.
            Logger.ex(e);
            updateState(STATE_ERROR_FLASH);
        }
    }

    private boolean updateAvailable() {
        final String latestFull = mPrefs.getString(UpdateService.PREF_LATEST_FULL_NAME, null);
        final String latestDelta = mPrefs.getString(UpdateService.PREF_LATEST_DELTA_NAME, null);
        return latestFull != null || latestDelta != null;
    }

    private String getLatestFullSHA256Sum(String sumUrl) {
        String urlSuffix = mConfig.getUrlSuffix();
        if (mIsUrlOverride) {
            sumUrl = mSumUrlOvr;
        } else if (urlSuffix.length() > 0) {
            sumUrl += mConfig.getUrlSuffix();
        }
        String latestFullSum = downloadUrlMemoryAsString(sumUrl);
        if (latestFullSum != null) {
            String sumPart = latestFullSum;
            while (sumPart.length() > 64)
                sumPart = sumPart.substring(0, sumPart.length() - 1);
            Logger.d("getLatestFullSHA256Sum - sha256sum = " + sumPart);
            return sumPart;
        }
        return null;
    }

    private float getProgress(long current, long total) {
        if (total == 0)
            return 0f;
        return ((float) current / (float) total) * 100f;
    }

    private boolean isSupportedVersion() {
        return mConfig.isOfficialVersion();
    }

    private int getAutoDownloadValue() {
        String autoDownload = mPrefs.getString(SettingsActivity.PREF_AUTO_DOWNLOAD, getDefaultAutoDownloadValue());
        return Integer.parseInt(autoDownload);
    }

    private String getDefaultAutoDownloadValue() {
        return isSupportedVersion() ? PREF_AUTO_DOWNLOAD_CHECK_STRING : PREF_AUTO_DOWNLOAD_DISABLED_STRING;
    }

    private boolean isScreenStateEnabled() {
        if (mScreenState == null) {
            return false;
        }
        boolean screenStateValue = mScreenState.getState();
        boolean prefValue = mPrefs.getBoolean(SettingsActivity.PREF_SCREEN_STATE_OFF, true);
        if (prefValue) {
            // only when screen off
            return !screenStateValue;
        }
        // always allow
        return true;
    }

    public static boolean isProgressState(String state) {
        return state.equals(UpdateService.STATE_ACTION_DOWNLOADING) ||
                state.equals(UpdateService.STATE_ACTION_SEARCHING) ||
                state.equals(UpdateService.STATE_ACTION_SEARCHING_SUM) ||
                state.equals(UpdateService.STATE_ACTION_CHECKING) ||
                state.equals(UpdateService.STATE_ACTION_CHECKING_SUM) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING_SUM) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING_PATCH) ||
                state.equals(UpdateService.STATE_ACTION_AB_FLASH);
    }

    public static boolean isErrorState(String state) {
        return state.equals(UpdateService.STATE_ERROR_DOWNLOAD) ||
                state.equals(UpdateService.STATE_ERROR_DOWNLOAD_RESUME) ||
                state.equals(UpdateService.STATE_ERROR_DISK_SPACE) ||
                state.equals(UpdateService.STATE_ERROR_UNKNOWN) ||
                state.equals(UpdateService.STATE_ERROR_UNOFFICIAL) ||
                state.equals(UpdateService.STATE_ERROR_CONNECTION) ||
                state.equals(UpdateService.STATE_ERROR_AB_FLASH) ||
                state.equals(UpdateService.STATE_ERROR_FLASH_FILE) ||
                state.equals(UpdateService.STATE_ERROR_FLASH);
    }

    private boolean isSnoozeNotification() {
        // check if we're snoozed, using abs for clock changes
        boolean timeSnooze = Math.abs(System.currentTimeMillis()
                - mPrefs.getLong(PREF_LAST_SNOOZE_TIME_NAME,
                        PREF_LAST_SNOOZE_TIME_DEFAULT)) <= SNOOZE_MS;
        if (timeSnooze) {
            String lastBuild = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
            String snoozeBuild = mPrefs.getString(PREF_SNOOZE_UPDATE_NAME, null);
            if (lastBuild != null && snoozeBuild != null) {
                // only snooze if time snoozed and no newer update available
                if (!lastBuild.equals(snoozeBuild)) {
                    return false;
                }
            }
        }
        return timeSnooze;
    }

    private void clearState() {
        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putString(PREF_LATEST_FULL_NAME, null);
        editor.putString(PREF_LATEST_DELTA_NAME, null);
        editor.putString(PREF_READY_FILENAME_NAME, null);
        editor.putString(PREF_LATEST_CHANGELOG, null);
        editor.putLong(PREF_DOWNLOAD_SIZE, -1);
        editor.putBoolean(PREF_DELTA_SIGNATURE, false);
        editor.putBoolean(PREF_FILE_FLASH, false);
        editor.putString(PREF_INITIAL_FILE, null);
        editor.commit();
    }

    private void shouldShowErrorNotification() {
        boolean dailyAlarm = mPrefs.getString(SettingsActivity.PREF_SCHEDULER_MODE, SettingsActivity.PREF_SCHEDULER_MODE_SMART)
                .equals(SettingsActivity.PREF_SCHEDULER_MODE_DAILY);

        if (dailyAlarm || mFailedUpdateCount >= 4) {
            // if from scheduler show a notification cause user should
            // see that something went wrong
            // if we check only daily always show - if smart mode wait for 4
            // consecutive failure - would be about 24h
            startErrorNotification();
            mFailedUpdateCount = 0;
        }
    }

    private void checkForUpdatesAsync(final boolean userInitiated, final int checkOnly) {
        Logger.d("checkForUpdatesAsync " + getPrefs().getAll());

        updateState(STATE_ACTION_CHECKING);
        mWakeLock.acquire();
        mWifiLock.acquire();

        newDownloadNotification(false,
                getString(R.string.state_action_downloading));

        mHandler.post(() -> {
            boolean downloadFullBuild = false;

            mStopDownload = -1;
            mIsUpdateRunning = true;

            try {
                List<DeltaInfo> deltas = new ArrayList<>();

                String flashFilename = null;
                (new File(mConfig.getPathBase())).mkdir();
                (new File(mConfig.getPathFlashAfterUpdate())).mkdir();

                List<String> latestFullBuildWithUrl = getNewestFullBuild();
                String latestFullBuild;
                // if we don't even find a build on dl no sense to continue
                if (latestFullBuildWithUrl == null || latestFullBuildWithUrl.size() == 0) {
                    Logger.d("no latest build found at " + mConfig.getUrlBaseJson() +
                            " for " + mConfig.getDevice());
                    return;
                }
                latestFullBuild = latestFullBuildWithUrl.get(0);

                String latestFullFetch;
                String latestFullFetchSUM;
                if (latestFullBuildWithUrl.size() < 3) {
                    latestFullFetch = mConfig.getUrlBaseFull() +
                            latestFullBuild + mConfig.getUrlSuffix();
                    latestFullFetchSUM = mConfig.getUrlBaseFullSum() +
                            latestFullBuild + ".sha256sum" + mConfig.getUrlSuffix();
                } else {
                    latestFullFetch = latestFullBuildWithUrl.get(1);
                    latestFullFetchSUM = latestFullBuildWithUrl.get(2);
                }
                Logger.d("latest full build for device " + mConfig.getDevice() + " is " + latestFullFetch);
                mPrefs.edit().putString(PREF_LATEST_FULL_NAME, latestFullBuild).commit();

                // also update the changelog
                final String changelog = downloadUrlMemoryAsString(
                        mConfig.getUrlBaseJson().replace(
                        mConfig.getDevice() + ".json", "Changelog.txt"));
                mPrefs.edit().putString(PREF_LATEST_CHANGELOG, changelog).commit();

                if (!Config.isABDevice()) {
                    // Create a list of deltas to apply to get from our current
                    // version to the latest
                    String fetch = String.format(Locale.ENGLISH, "%s%s.delta",
                            mConfig.getUrlBaseDelta(),
                            mConfig.getFilenameBase());

                    while (true) {
                        DeltaInfo delta = null;
                        byte[] data = downloadUrlMemory(fetch);
                        if (data != null && data.length != 0) {
                            try {
                                delta = new DeltaInfo(data, false);
                            } catch (JSONException | NullPointerException e) {
                                // There's an error in the JSON. Could be bad JSON,
                                // could be a 404 text, etc
                                Logger.ex(e);
                                delta = null;
                            } // Download failed

                        }

                        if (delta == null) {
                            // See if we have a revoked version instead, we
                            // still need it for chaining future deltas, but
                            // will not allow flashing this one
                            data = downloadUrlMemory(fetch.replace(".delta",
                                    ".delta_revoked"));
                            if (data != null && data.length != 0) {
                                try {
                                    delta = new DeltaInfo(data, true);
                                } catch (JSONException | NullPointerException e) {
                                    // There's an error in the JSON. Could be bad
                                    // JSON, could be a 404 text, etc
                                    Logger.ex(e);
                                    delta = null;
                                } // Download failed

                            }

                            // We didn't get a delta or a delta_revoked - end of
                            // the delta availability chain
                            if (delta == null)
                                break;
                        }

                        Logger.d("delta --> [%s]", delta.getOut().getName());
                        fetch = String.format(Locale.ENGLISH, "%s%s.delta",
                                mConfig.getUrlBaseDelta(), delta
                                .getOut().getName().replace(".zip", ""));
                        deltas.add(delta);
                    }
                }

                if (deltas.size() > 0) {
                    // See if we have done past work and have newer ZIPs
                    // than the original of what's currently flashed

                    int last = -1;
                    for (int i = deltas.size() - 1; i >= 0; i--) {
                        DeltaInfo di = deltas.get(i);
                        String fn = mConfig.getPathBase() + di.getOut().getName();
                        if (di.getOut()
                                .match(new File(fn),
                                        true,
                                        getSUMProgress(STATE_ACTION_CHECKING_SUM, di.getOut()
                                                .getName())) != null) {
                            if (latestFullBuild.equals(di.getOut().getName())) {
                                boolean signedFile = di.getOut().isSignedFile(new File(fn));
                                Logger.d("match found (%s): %s", signedFile ? "delta" : "full", di.getOut().getName());
                                flashFilename = fn;
                                last = i;
                                mPrefs.edit().putBoolean(PREF_DELTA_SIGNATURE, signedFile).commit();
                                break;
                            }
                        }
                    }

                    if (last > -1) {
                        deltas.subList(0, last + 1).clear();
                    }
                }

                while ((deltas.size() > 0) && (deltas.get(deltas.size() - 1).isRevoked())) {
                    // Make sure the last delta is not revoked
                    deltas.remove(deltas.size() - 1);
                }

                if (deltas.size() == 0) {
                    // we found a matching zip created from deltas before
                    if (flashFilename != null) {
                        mPrefs.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit();
                        return;
                    }
                    // only full download available
                    final String latestFull = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
                    String currentVersionZip = mConfig.getFilenameBase() +".zip";

                    long currFileDate; // will store current build date as YYYYMMDD
                    long latestFileDate; // will store the latest build date as YYYYMMDD
                    boolean updateAvailable = false;
                    if (latestFull != null) {
                        try {
                            currFileDate = Long.parseLong(currentVersionZip.split("-")[4].substring(0, 8));
                            latestFileDate = Long.parseLong(latestFull.split("-")[4].substring(0, 8));
                            updateAvailable = latestFileDate > currFileDate;
                        } catch (NumberFormatException exception) {
                            // Just incase someone decides to make up his own zip / build name and F's this up
                            Logger.d("Build name malformed");
                            Logger.ex(exception);
                        }
                        downloadFullBuild = updateAvailable;
                    }

                    if (!updateAvailable) {
                        mPrefs.edit().putString(PREF_LATEST_FULL_NAME, null).commit();
                    }

                    if (downloadFullBuild) {
                        if (checkExistingFullBuild(latestFullBuildWithUrl, latestFullFetchSUM))
                            return;
                    }
                    if (updateAvailable && downloadFullBuild) {
                        long size = getUrlDownloadSize(latestFullFetch);
                        mPrefs.edit().putLong(PREF_DOWNLOAD_SIZE, size).commit();
                    }
                    Logger.d("check done: latest full build available = " + mPrefs.getString(PREF_LATEST_FULL_NAME, null) +
                            " : updateAvailable = " + updateAvailable + " : downloadFullBuild = " + downloadFullBuild);

                    if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK) {
                        return;
                    }
                } else {
                    DeltaInfo lastDelta = deltas.get(deltas.size() - 1);
                    flashFilename = mConfig.getPathBase() + lastDelta.getOut().getName();

                    long deltaDownloadSize = getDeltaDownloadSize(deltas);
                    long fullDownloadSize = getFullDownloadSize(deltas);

                    Logger.d("download size --> deltas[%d] vs full[%d]", deltaDownloadSize,
                            fullDownloadSize);

                    // Find the currently flashed ZIP, or a newer one
                    String initialFile;
                    boolean initialFileNeedsProcessing;
                    {
                        boolean[] needsProcessing = new boolean[] {
                                false
                        };
                        initialFile = findInitialFile(deltas, flashFilename, needsProcessing);
                        initialFileNeedsProcessing = needsProcessing[0];
                    }
                    Logger.d("initial: %s", initialFile != null ? initialFile : "not found");

                    // If we don't have a file to start out with, or the
                    // combined deltas get big, just get the latest full ZIP
                    boolean betterDownloadFullBuild = deltaDownloadSize > fullDownloadSize;

                    final String latestFull = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
                    final String latestDelta = flashFilename;

                    String latestDeltaZip = latestDelta != null ? new File(latestDelta).getName() : null;
                    String currentVersionZip = mConfig.getFilenameBase() +".zip";
                    boolean fullUpdatePossible = latestFull != null && Long.parseLong(latestFull.replaceAll("\\D+","")) > Long.parseLong(currentVersionZip.replaceAll("\\D+",""));
                    boolean deltaUpdatePossible = initialFile != null && latestDeltaZip != null && Long.parseLong(latestDeltaZip.replaceAll("\\D+","")) > Long.parseLong(currentVersionZip.replaceAll("\\D+","")) && latestDeltaZip.equals(latestFull);

                    // is the full version newer than what we could create with delta?
                    if (latestFull.compareTo(latestDeltaZip) > 0) {
                        betterDownloadFullBuild = true;
                    }

                    Logger.d("latestDeltaZip = " + latestDeltaZip + " currentVersionZip = " + currentVersionZip + " latestFullZip = " + latestFull);

                    Logger.d("deltaUpdatePossible = " + deltaUpdatePossible + " fullUpdatePossible = " + fullUpdatePossible + " betterDownloadFullBuild = " + betterDownloadFullBuild);

                    if (!deltaUpdatePossible || (betterDownloadFullBuild && fullUpdatePossible)) {
                        downloadFullBuild = true;
                    }
                    boolean updateAvailable = fullUpdatePossible || deltaUpdatePossible;

                    if (!updateAvailable) {
                        mPrefs.edit().putString(PREF_LATEST_DELTA_NAME, null).commit();
                        mPrefs.edit().putString(PREF_LATEST_FULL_NAME, null).commit();
                    } else {
                        if (downloadFullBuild) {
                            mPrefs.edit().putString(PREF_LATEST_DELTA_NAME, null).commit();
                        } else {
                            mPrefs.edit().putString(PREF_LATEST_DELTA_NAME, new File(flashFilename).getName()).commit();
                        }
                    }

                    if (downloadFullBuild) {
                        if (checkExistingFullBuild(latestFullBuildWithUrl, latestFullFetchSUM))
                            return;
                    }
                    if (updateAvailable) {
                        if (deltaUpdatePossible) {
                            mPrefs.edit().putLong(PREF_DOWNLOAD_SIZE, deltaDownloadSize).commit();
                        } else if (downloadFullBuild) {
                            mPrefs.edit().putLong(PREF_DOWNLOAD_SIZE, fullDownloadSize).commit();
                        }
                    }
                    Logger.d("check done: latest valid delta update = " + mPrefs.getString(PREF_LATEST_DELTA_NAME, null) +
                            " : latest full build available = " + mPrefs.getString(PREF_LATEST_FULL_NAME, null) +
                            " : updateAvailable = " + updateAvailable + " : downloadFullBuild = " + downloadFullBuild);

                    long requiredSpace = getRequiredSpace(deltas, downloadFullBuild);
                    long freeSpace = (new StatFs(mConfig.getPathBase())).getAvailableBytes();
                    Logger.d("requiredSpace = " + requiredSpace + " freeSpace = " + freeSpace);

                    if (freeSpace < requiredSpace) {
                        updateState(STATE_ERROR_DISK_SPACE, null, freeSpace, requiredSpace,
                                null, null);
                        Logger.d("not enough space!");
                        return;
                    }

                    if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK) {
                        return;
                    }
                    long downloadSize = downloadFullBuild ? fullDownloadSize : deltaDownloadSize;

                    if (!downloadFullBuild && checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
                        // Download all the files we do not have yet
                        // getFull = false since full download is handled below
                        if (!downloadFiles(deltas, downloadSize, userInitiated))
                            return;

                        // Reconstruct flashable ZIP
                        if (!applyPatches(deltas, initialFile, initialFileNeedsProcessing))
                            return;

                        // Verify using SHA256
                        if (lastDelta.getOut().match(
                                new File(mConfig.getPathBase() + lastDelta.getOut().getName()),
                                true,
                                getSUMProgress(STATE_ACTION_APPLYING_SUM, lastDelta.getOut()
                                        .getName())) == null) {
                            updateState(STATE_ERROR_UNKNOWN);
                            Logger.d("final verification error");
                            return;
                        }
                        Logger.d("final verification complete");

                        // Cleanup
                        for (DeltaInfo di : deltas) {
                            (new File(mConfig.getPathBase() + di.getUpdate().getName())).delete();
                            (new File(mConfig.getPathBase() + di.getSignature().getName())).delete();
                            if (di != lastDelta)
                                (new File(mConfig.getPathBase() + di.getOut().getName())).delete();
                        }
                        // we will not delete initialFile until flashing
                        // else people building images and not flashing for 24h will lose
                        // the possibility to do delta updates
                        if (initialFile != null) {
                            if (initialFile.startsWith(mConfig.getPathBase())) {
                                mPrefs.edit().putString(PREF_INITIAL_FILE, initialFile).commit();
                            }
                        }
                        mPrefs.edit().putBoolean(PREF_DELTA_SIGNATURE, true).commit();
                        mPrefs.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit();
                    }
                }
                if (downloadFullBuild && checkOnly == PREF_AUTO_DOWNLOAD_FULL) {
                    if (userInitiated || mNetworkState.getState()) {
                        String latestFullSUM = getLatestFullSHA256Sum(latestFullFetchSUM);
                        if (latestFullSUM != null) {
                            downloadFullBuild(latestFullFetch, latestFullSUM, latestFullBuild); // download full
                        } else {
                            updateState(STATE_ERROR_DOWNLOAD);
                            Logger.d("aborting download due to sha256sum not found");
                        }
                    } else {
                        updateState(STATE_ERROR_DOWNLOAD);
                        Logger.d("aborting download due to network state");
                    }
                }
            } finally {
                mPrefs.edit().putLong(PREF_LAST_CHECK_TIME_NAME, System.currentTimeMillis()).commit();
                stopForeground(true);
                if (mWifiLock.isHeld()) mWifiLock.release();
                if (mWakeLock.isHeld()) mWakeLock.release();

                if (isErrorState(mState)) {
                    mFailedUpdateCount++;
                    clearState();
                    if (!userInitiated) {
                        shouldShowErrorNotification();
                    }
                } else {
                    mFailedUpdateCount = 0;
                    autoState(userInitiated, checkOnly, true);
                }
                mIsUpdateRunning = false;
            }
        });
    }

    private boolean checkExistingFullBuild(List<String> latestFullBuildWithUrl,
                                           String latestFullFetchSUM) {
        String fn = mConfig.getPathBase() + latestFullBuildWithUrl.get(0);
        File file = new File(fn);
        if (file.exists()) {
            if (checkFullBuildSHA256Sum(latestFullFetchSUM, fn)) {
                Logger.d("match found (full): " + fn);
                // full zip exists and is valid - flash ready state
                mPrefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
                return true;
            }
            // get rid of rubbish
            file.delete();
        }
        return false;
    }

    private boolean checkForFinishedUpdate() {
        final boolean finished = 
                mPrefs.getBoolean(PREF_PENDING_REBOOT, false) ||
                mState.equals(STATE_ACTION_AB_FINISHED) ||
                ABUpdate.isInstallingUpdate(this);
        if (finished) {
            final String lastFilename = mPrefs.getString(PREF_CURRENT_AB_FILENAME_NAME, null);
            mPrefs.edit().putBoolean(PREF_PENDING_REBOOT, false).commit();
            ABUpdate.pokeStatus(lastFilename, this);
        }
        return finished;
    }

    private boolean checkPermissions() {
        if (!Environment.isExternalStorageManager()) {
            Logger.d("checkPermissions failed");
            updateState(STATE_ERROR_PERMISSIONS);
            return false;
        }
        return true;
    }

    private void deleteOldFlashFile(String newFlashFilename) {
        String oldFlashFilename = mPrefs.getString(PREF_CURRENT_FILENAME_NAME, null);
        Logger.d("delete oldFlashFilename " + oldFlashFilename + " " + newFlashFilename);

        if (oldFlashFilename != null && !oldFlashFilename.equals(newFlashFilename)
                && oldFlashFilename.startsWith(mConfig.getPathBase())) {
            File file = new File(oldFlashFilename);
            if (file.exists()) {
                Logger.d("delete oldFlashFilename " + oldFlashFilename);
                file.delete();
            }
        }
    }

    public SharedPreferences getPrefs() {
        return mPrefs;
    }

    public Config getConfig() {
        return mConfig;
    }

    public void setFlashFilename(String flashFilename) {
        Logger.d("Flash file set: %s", flashFilename);
        File fn = new File(flashFilename);
        if (!fn.exists()) {
            updateState(STATE_ERROR_FLASH_FILE);
            return;
        }
        if (!fn.getName().endsWith(".zip")) {
            updateState(STATE_ERROR_FLASH_FILE);
            return;
        }
        Logger.d("Set flash possible: %s", flashFilename);
        mPrefs.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit();
        updateState(STATE_ACTION_FLASH_FILE_READY, null, null, null, (new File(flashFilename)).getName(), null);
    }

    private void createNotificationChannel() {
        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        mNotificationManager.createNotificationChannel(channel);
    }
}
