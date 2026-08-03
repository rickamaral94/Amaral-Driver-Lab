package com.amaral.driverlab;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

final class DeviceSnapshot {
    private DeviceSnapshot() {}

    static JSONObject capture(Context context) {
        JSONObject json = new JSONObject();
        try {
            json.put("manufacturer", Build.MANUFACTURER);
            json.put("brand", Build.BRAND);
            json.put("model", Build.MODEL);
            json.put("device", Build.DEVICE);
            json.put("product", Build.PRODUCT);
            json.put("board", Build.BOARD);
            json.put("hardware", Build.HARDWARE);
            json.put("android_sdk", Build.VERSION.SDK_INT);
            json.put("android_release", Build.VERSION.RELEASE);
            json.put("build_fingerprint", Build.FINGERPRINT);
            JSONObject telemetry = captureTelemetry(context);
            Iterator<String> keys = telemetry.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                json.put(key, telemetry.get(key));
            }
        } catch (JSONException ignored) {
            // JSONObject operations on fixed keys do not fail in normal conditions.
        }
        return json;
    }

    static JSONObject captureTelemetry(Context context) {
        JSONObject json = new JSONObject();
        try {
            json.put("elapsed_realtime_ms", SystemClock.elapsedRealtime());
            json.put("wall_time_ms", System.currentTimeMillis());

            Intent battery = context.registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                json.put("battery_temperature_c",
                        temperature >= 0 ? temperature / 10.0 : JSONObject.NULL);
                json.put("battery_level_percent",
                        level >= 0 && scale > 0 ? level * 100.0 / scale : JSONObject.NULL);
                json.put("battery_voltage_mv",
                        battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
                json.put("battery_status",
                        battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
            }

            BatteryManager batteryManager =
                    (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (batteryManager != null) {
                putProperty(json, "battery_current_now_ua", batteryManager,
                        BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                putProperty(json, "battery_current_average_ua", batteryManager,
                        BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                putProperty(json, "battery_charge_counter_uah", batteryManager,
                        BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                long energy = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
                json.put("battery_energy_counter_nwh",
                        energy == Long.MIN_VALUE ? JSONObject.NULL : energy);
            }

            PowerManager powerManager =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && Build.VERSION.SDK_INT >= 29) {
                json.put("thermal_status", powerManager.getCurrentThermalStatus());
            }

            ActivityManager activityManager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memory);
                json.put("available_memory_bytes", memory.availMem);
                json.put("low_memory", memory.lowMemory);
            }
        } catch (JSONException ignored) {
            // Missing telemetry is represented by absent keys.
        }
        return json;
    }

    private static void putProperty(JSONObject json, String key, BatteryManager manager,
                                    int property) throws JSONException {
        int value = manager.getIntProperty(property);
        json.put(key, value == Integer.MIN_VALUE ? JSONObject.NULL : value);
    }
}
