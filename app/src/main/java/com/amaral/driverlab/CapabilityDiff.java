package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class CapabilityDiff {
    private CapabilityDiff() {}

    static JSONObject compare(JSONObject system, JSONObject candidate) throws Exception {
        JSONObject diff = new JSONObject();
        diff.put("system_driver", driverIdentity(system));
        diff.put("candidate_driver", driverIdentity(candidate));

        Set<String> systemExtensions = stringSet(system.optJSONArray("extensions"));
        Set<String> candidateExtensions = stringSet(candidate.optJSONArray("extensions"));
        diff.put("extensions_gained", sortedDifference(candidateExtensions, systemExtensions));
        diff.put("extensions_lost", sortedDifference(systemExtensions, candidateExtensions));

        JSONObject featureDiff = compareBooleanMap(
                system.optJSONObject("features"), candidate.optJSONObject("features"));
        diff.put("features_gained", featureDiff.getJSONArray("gained"));
        diff.put("features_lost", featureDiff.getJSONArray("lost"));

        JSONObject limits = compareNumericMap(
                system.optJSONObject("limits"), candidate.optJSONObject("limits"));
        diff.put("limits_increased", limits.getJSONArray("increased"));
        diff.put("limits_decreased", limits.getJSONArray("decreased"));
        diff.put("limits_changed_non_numeric", limits.getJSONArray("changed_non_numeric"));

        JSONObject extensionVersions = compareNumericMap(
                system.optJSONObject("extension_spec_versions"),
                candidate.optJSONObject("extension_spec_versions"));
        diff.put("extension_spec_versions_increased", extensionVersions.getJSONArray("increased"));
        diff.put("extension_spec_versions_decreased", extensionVersions.getJSONArray("decreased"));

        boolean identityChanged = !sameNullable(system, candidate, "driver_id")
                || !sameNullable(system, candidate, "driver_name")
                || !sameNullable(system, candidate, "driver_info")
                || !sameNullable(system, candidate, "driver_version_raw")
                || !sameNullable(system, candidate, "conformance_version");
        diff.put("driver_identity_changed", identityChanged);
        diff.put("summary", summary(diff));
        return diff;
    }

    private static JSONObject driverIdentity(JSONObject source) throws Exception {
        JSONObject identity = new JSONObject();
        copyNullable(source, identity, "gpu_name");
        copyNullable(source, identity, "vendor_id");
        copyNullable(source, identity, "device_id");
        copyNullable(source, identity, "api_version");
        copyNullable(source, identity, "driver_version_raw");
        copyNullable(source, identity, "driver_version_decoded");
        copyNullable(source, identity, "driver_id");
        copyNullable(source, identity, "driver_id_name");
        copyNullable(source, identity, "driver_name");
        copyNullable(source, identity, "driver_info");
        copyNullable(source, identity, "conformance_version");
        copyNullable(source, identity, "mesa_version_major");
        copyNullable(source, identity, "mesa_version_minor");
        return identity;
    }

    private static void copyNullable(JSONObject from, JSONObject to, String key) throws Exception {
        to.put(key, from.has(key) ? from.opt(key) : JSONObject.NULL);
    }

    private static boolean sameNullable(JSONObject left, JSONObject right, String key) {
        Object leftValue = left.has(key) ? left.opt(key) : JSONObject.NULL;
        Object rightValue = right.has(key) ? right.opt(key) : JSONObject.NULL;
        if (leftValue == null || leftValue == JSONObject.NULL) {
            return rightValue == null || rightValue == JSONObject.NULL;
        }
        return leftValue.equals(rightValue);
    }

    private static JSONObject compareBooleanMap(JSONObject system, JSONObject candidate)
            throws Exception {
        JSONObject result = new JSONObject();
        JSONArray gained = new JSONArray();
        JSONArray lost = new JSONArray();
        Set<String> keys = keyUnion(system, candidate);
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        for (String key : sorted) {
            boolean before = system != null && system.optBoolean(key, false);
            boolean after = candidate != null && candidate.optBoolean(key, false);
            if (!before && after) gained.put(key);
            if (before && !after) lost.put(key);
        }
        result.put("gained", gained);
        result.put("lost", lost);
        return result;
    }

    private static JSONObject compareNumericMap(JSONObject system, JSONObject candidate)
            throws Exception {
        JSONObject result = new JSONObject();
        JSONArray increased = new JSONArray();
        JSONArray decreased = new JSONArray();
        JSONArray changedNonNumeric = new JSONArray();
        List<String> keys = new ArrayList<>(keyUnion(system, candidate));
        Collections.sort(keys);
        for (String key : keys) {
            Object before = system == null ? null : system.opt(key);
            Object after = candidate == null ? null : candidate.opt(key);
            if (before instanceof Number && after instanceof Number) {
                double beforeNumber = ((Number) before).doubleValue();
                double afterNumber = ((Number) after).doubleValue();
                if (Double.compare(beforeNumber, afterNumber) == 0) continue;
                JSONObject change = new JSONObject();
                change.put("name", key);
                change.put("system", before);
                change.put("candidate", after);
                (afterNumber > beforeNumber ? increased : decreased).put(change);
            } else if (!jsonEquivalent(before, after)) {
                JSONObject change = new JSONObject();
                change.put("name", key);
                change.put("system", before == null ? JSONObject.NULL : before);
                change.put("candidate", after == null ? JSONObject.NULL : after);
                changedNonNumeric.put(change);
            }
        }
        result.put("increased", increased);
        result.put("decreased", decreased);
        result.put("changed_non_numeric", changedNonNumeric);
        return result;
    }

    private static boolean jsonEquivalent(Object left, Object right) {
        if (left == null || left == JSONObject.NULL) return right == null || right == JSONObject.NULL;
        if (right == null || right == JSONObject.NULL) return false;
        return left.toString().equals(right.toString());
    }

    private static Set<String> keyUnion(JSONObject left, JSONObject right) {
        Set<String> keys = new HashSet<>();
        addKeys(keys, left);
        addKeys(keys, right);
        return keys;
    }

    private static void addKeys(Set<String> destination, JSONObject source) {
        if (source == null) return;
        Iterator<String> iterator = source.keys();
        while (iterator.hasNext()) destination.add(iterator.next());
    }

    private static Set<String> stringSet(JSONArray array) {
        Set<String> values = new HashSet<>();
        if (array == null) return values;
        for (int index = 0; index < array.length(); ++index) {
            String value = array.optString(index, "");
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private static JSONArray sortedDifference(Set<String> left, Set<String> right) {
        List<String> values = new ArrayList<>();
        for (String value : left) if (!right.contains(value)) values.add(value);
        Collections.sort(values);
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static String summary(JSONObject diff) {
        return "Extensões +" + diff.optJSONArray("extensions_gained").length()
                + "/-" + diff.optJSONArray("extensions_lost").length()
                + ", features +" + diff.optJSONArray("features_gained").length()
                + "/-" + diff.optJSONArray("features_lost").length()
                + ", limites ↑" + diff.optJSONArray("limits_increased").length()
                + "/↓" + diff.optJSONArray("limits_decreased").length();
    }
}
