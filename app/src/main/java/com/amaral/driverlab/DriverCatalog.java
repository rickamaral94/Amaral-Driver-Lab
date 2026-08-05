package com.amaral.driverlab;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only driver catalogue shared by the Phase 13 guided screens. */
final class DriverCatalog {
    private DriverCatalog() {}

    static List<DriverPackage> load(Context context) {
        List<DriverPackage> drivers = new ArrayList<>();
        File root = new File(context.getFilesDir(), "drivers");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories != null) {
            for (File directory : directories) {
                if (directory.getName().startsWith(".partial-")) continue;
                try {
                    DriverPackage driver = DriverPackage.fromJson(
                            ResultFiles.readUtf8(new File(directory, "descriptor.json")));
                    if (driver.isUsable()) drivers.add(driver);
                } catch (Exception ignored) {
                    // Invalid and partial packages are intentionally omitted.
                }
            }
        }
        drivers.sort(Comparator.comparing(DriverPackage::displayName));
        return drivers;
    }

    static DriverPackage findBySha(Context context, String sha) {
        if (sha == null || sha.isEmpty()) return null;
        for (DriverPackage driver : load(context)) {
            if (sha.equals(driver.sha256)) return driver;
        }
        return null;
    }
}
