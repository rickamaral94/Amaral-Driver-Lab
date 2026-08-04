package com.amaral.driverlab;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

final class QualificationPreflight {
    private QualificationPreflight() {}

    static JSONObject capture(Context context) throws Exception {
        JSONObject snapshot = DeviceSnapshot.capture(context);
        JSONObject evaluation = evaluate(snapshot);
        return new JSONObject()
                .put("captured_at_ms", System.currentTimeMillis())
                .put("device", snapshot)
                .put("evaluation", evaluation)
                .put("recommended_conditions", new JSONObject()
                        .put("minimum_battery_percent", 30)
                        .put("maximum_initial_battery_temperature_c", 40.0)
                        .put("maximum_thermal_status", 3)
                        .put("charging_state_should_remain_constant", true)
                        .put("close_background_apps", true)
                        .put("fixed_performance_profile", true)
                        .put("fixed_fan_profile", true));
    }

    static JSONObject evaluate(JSONObject snapshot) throws Exception {
        JSONArray warnings = new JSONArray();
        JSONArray blockers = new JSONArray();
        double battery = snapshot.optDouble("battery_level_percent", Double.NaN);
        double temperature = snapshot.optDouble("battery_temperature_c", Double.NaN);
        int thermalStatus = snapshot.optInt("thermal_status", -1);
        boolean lowMemory = snapshot.optBoolean("low_memory", false);

        if (Double.isFinite(battery)) {
            if (battery < 20.0) blockers.put("battery_below_20_percent");
            else if (battery < 30.0) warnings.put("battery_below_recommended_30_percent");
        } else warnings.put("battery_level_unavailable");

        if (Double.isFinite(temperature)) {
            if (temperature >= 45.0) blockers.put("initial_temperature_at_or_above_45_c");
            else if (temperature >= 40.0) warnings.put("initial_temperature_at_or_above_40_c");
        } else warnings.put("battery_temperature_unavailable");

        if (thermalStatus >= 5) blockers.put("android_thermal_status_severe_or_higher");
        else if (thermalStatus >= 3) warnings.put("android_thermal_status_elevated");
        if (lowMemory) blockers.put("android_reports_low_memory");
        if (!snapshot.has("available_memory_bytes")) warnings.put("available_memory_unavailable");

        int batteryStatus = snapshot.optInt("battery_status", -1);
        if (batteryStatus == 2 || batteryStatus == 5) {
            warnings.put("device_is_charging_keep_state_constant_during_test");
        }

        return new JSONObject()
                .put("eligible_to_start", blockers.length() == 0)
                .put("ranking_blocked", blockers.length() > 0)
                .put("warnings", warnings)
                .put("blockers", blockers);
    }

    static JSONObject compare(JSONObject initial, JSONObject end) throws Exception {
        JSONArray warnings = new JSONArray();
        JSONArray blockers = new JSONArray();
        JSONObject startDevice = initial.optJSONObject("device");
        if (startDevice == null) startDevice = initial;
        JSONObject endDevice = end.optJSONObject("device");
        if (endDevice == null) endDevice = end;

        double startTemp = startDevice.optDouble("battery_temperature_c", Double.NaN);
        double endTemp = endDevice.optDouble("battery_temperature_c", Double.NaN);
        double deltaTemp = Double.isFinite(startTemp) && Double.isFinite(endTemp)
                ? endTemp - startTemp : Double.NaN;
        if (Double.isFinite(deltaTemp)) {
            if (deltaTemp >= 12.0) blockers.put("temperature_increased_by_12_c_or_more");
            else if (deltaTemp >= 7.0) warnings.put("temperature_increased_by_7_c_or_more");
        }
        int startStatus = startDevice.optInt("battery_status", -1);
        int endStatus = endDevice.optInt("battery_status", -1);
        if (startStatus >= 0 && endStatus >= 0 && startStatus != endStatus) {
            warnings.put("battery_charging_status_changed_during_qualification");
        }
        int finalThermal = endDevice.optInt("thermal_status", -1);
        if (finalThermal >= 5) blockers.put("final_android_thermal_status_severe_or_higher");
        else if (finalThermal >= 3) warnings.put("final_android_thermal_status_elevated");

        return new JSONObject()
                .put("temperature_delta_c", Double.isFinite(deltaTemp) ? deltaTemp : JSONObject.NULL)
                .put("warnings", warnings)
                .put("blockers", blockers)
                .put("ranking_blocked", blockers.length() > 0);
    }
}
