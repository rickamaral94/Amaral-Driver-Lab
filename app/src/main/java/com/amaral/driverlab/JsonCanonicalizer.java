package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

final class JsonCanonicalizer {
    private JsonCanonicalizer() {}

    static String canonicalize(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return canonicalObject((JSONObject) value);
        if (value instanceof JSONArray) return canonicalArray((JSONArray) value);
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return JSONObject.quote(String.valueOf(value));
    }

    static String sha256(Object value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalize(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
        return hex.toString();
    }

    static String sha256WithoutKey(JSONObject value, String key) throws Exception {
        JSONObject copy = new JSONObject(value.toString());
        copy.remove(key);
        return sha256(copy);
    }

    private static String canonicalObject(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys);
        StringBuilder output = new StringBuilder("{");
        for (int index = 0; index < keys.size(); ++index) {
            if (index > 0) output.append(',');
            String key = keys.get(index);
            output.append(JSONObject.quote(key)).append(':')
                    .append(canonicalize(object.opt(key)));
        }
        return output.append('}').toString();
    }

    private static String canonicalArray(JSONArray array) {
        StringBuilder output = new StringBuilder("[");
        for (int index = 0; index < array.length(); ++index) {
            if (index > 0) output.append(',');
            output.append(canonicalize(array.opt(index)));
        }
        return output.append(']').toString();
    }
}
