/* 
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2015 The OmniROM Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.BatteryManager;

public class BatteryState implements OnSharedPreferenceChangeListener {
    public interface OnBatteryStateListener {
        void onBatteryState(boolean state);
    }

    private Context context = null;
    private OnBatteryStateListener onBatteryStateListener = null;
    private Boolean stateLast = null;

    private int minLevel = 50;
    private boolean chargeOnly = true;

    private final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;
            updateState(level, charging);
        }
    };

    private void updateState(int level, boolean charging) {
        final boolean state = charging && chargeOnly ||
                level >= minLevel && !chargeOnly;

        if (stateLast != null && stateLast == state) return;
        stateLast = state;

        if (onBatteryStateListener == null) return;
        onBatteryStateListener.onBatteryState(state);
    }

    public boolean start(Context context, OnBatteryStateListener onBatteryStateListener,
            int minLevel, boolean chargeOnly) {
        if (this.context != null) return false;
        this.context = context;
        this.onBatteryStateListener = onBatteryStateListener;
        this.minLevel = minLevel;
        this.chargeOnly = chargeOnly;
        context.registerReceiver(receiver, filter);
        return true;
    }

    public boolean stop() {
        if (context != null) {
            context.unregisterReceiver(receiver);
            onBatteryStateListener = null;
            context = null;
            return true;
        }
        return false;
    }

    public Boolean getState() {
        if (stateLast == null)
            return false;
        return stateLast;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        chargeOnly = sharedPreferences.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true);
        minLevel = Integer.parseInt(sharedPreferences.getString(SettingsActivity.PREF_BATTERY_LEVEL, "50"));
    }
}
