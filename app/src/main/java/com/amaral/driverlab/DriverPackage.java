package com.amaral.driverlab;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

final class DriverPackage {
    final String sha256;
    final String name;
    final String packageVersion;
    final String vendor;
    final String driverVersion;
    final int minApi;
    final String libraryName;
    final File directory;
    final File sourceZip;
    final JSONObject metadata;

    DriverPackage(String sha256, String name, String packageVersion, String vendor,
                  String driverVersion, int minApi, String libraryName, File directory,
                  File sourceZip, JSONObject metadata) {
        this.sha256 = sha256;
        this.name = name;
        this.packageVersion = packageVersion;
        this.vendor = vendor;
        this.driverVersion = driverVersion;
        this.minApi = minApi;
        this.libraryName = libraryName;
        this.directory = directory;
        this.sourceZip = sourceZip;
        this.metadata = metadata;
    }

    String displayName() {
        String version = !packageVersion.isEmpty() ? packageVersion : driverVersion;
        return version.isEmpty() ? name : name + " · " + version;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("sha256", sha256);
        json.put("name", name);
        json.put("packageVersion", packageVersion);
        json.put("vendor", vendor);
        json.put("driverVersion", driverVersion);
        json.put("minApi", minApi);
        json.put("libraryName", libraryName);
        json.put("directory", directory.getAbsolutePath());
        json.put("sourceZip", sourceZip.getAbsolutePath());
        json.put("metadata", metadata);
        return json;
    }

    static DriverPackage fromJson(String encoded) throws JSONException {
        JSONObject json = new JSONObject(encoded);
        return new DriverPackage(
                json.getString("sha256"),
                json.getString("name"),
                json.optString("packageVersion"),
                json.optString("vendor"),
                json.optString("driverVersion"),
                json.optInt("minApi", 28),
                json.getString("libraryName"),
                new File(json.getString("directory")),
                new File(json.getString("sourceZip")),
                json.getJSONObject("metadata"));
    }

    boolean isUsable() {
        return directory.isDirectory()
                && sourceZip.isFile()
                && new File(directory, libraryName).isFile();
    }
}
