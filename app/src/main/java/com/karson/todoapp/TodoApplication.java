package com.karson.todoapp;

import android.app.Application;
import android.util.Log;

import com.google.firebase.database.FirebaseDatabase;

/**
 * Enables Realtime Database on-disk persistence once at process start — reads then work offline
 * from cache, and writes made offline queue and flush on reconnect. This must run before any
 * other Database usage, which Application.onCreate guarantees.
 */
public class TodoApplication extends Application {
    private static final String TAG = "TodoApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception e) {
            // Thrown only if the database was already touched this process — safe to ignore.
            Log.w(TAG, "setPersistenceEnabled skipped", e);
        }
    }
}
