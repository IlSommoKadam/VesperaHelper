package com.vaonis.vesperahelper;

import android.app.Application;
import android.util.Log;

/** Marks an interrupted photo sync so the service can resume after crash or kill. */
public final class VesperaHelperApp extends Application {
    private static final String TAG = "VesperaHelper";

    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                PhotoSyncStore.from(this).markInterrupted(this);
                Log.e(TAG, "uncaught, sync marked for resume", error);
            } catch (Exception ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
            else System.exit(10);
        });
        PhotoSyncService.ensure(this);
        RemoteBridge.ensure(this);
    }
}
