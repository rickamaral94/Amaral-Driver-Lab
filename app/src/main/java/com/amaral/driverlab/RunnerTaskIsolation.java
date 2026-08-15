package com.amaral.driverlab;

import android.content.Intent;

/** Keeps disposable native runners out of the application's foreground task. */
final class RunnerTaskIsolation {
    private RunnerTaskIsolation() {}

    static void prepare(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }
}
