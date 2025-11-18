package com.offsec.nethunter.utils;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Arrays;

public class PermissionCheck {
    private static final String TAG = "PermissionCheck";
    private final Activity activity;
    private final Context context;
    public static final int DEFAULT_PERMISSION_RQCODE = 1;

    // Unified permission arrays
    public static final String[] DEFAULT_PERMISSIONS = {
            // kimocoder was here: WRITE_EXTERNAL? OR MANAGE_EXTERNAL .. check
            //android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
            // Add more permissions as needed
    };

    // Example: Add more permission groups if needed
    public static final String[] STORAGE_PERMISSIONS = {
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public PermissionCheck(Activity activity, Context context) {
        this.activity = activity;
        this.context = context;
    }

    // Request permissions if not already granted
    public void checkPermissions(String[] permissions, int requestCode) {
        if (!hasPermissions(context, permissions)) {
            ActivityCompat.requestPermissions(activity, permissions, requestCode);
        }
    }

    // Check if all permissions are granted
    public boolean isAllPermitted(String[] permissions) {
        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permission NOT granted: " + permission);
                allGranted = false;
            }
        }
        return allGranted;
    }

    // Utility: Check if permissions are granted (static for use without instance)
    public static boolean hasPermissions(Context context, String[] permissions) {
        Log.d(TAG, "hasPermissions called with context: " + context + ", permissions: " + Arrays.toString(permissions));
        if (context == null || permissions == null) {
            Log.e(TAG, "Context or permissions array is null");
            return true;
        }
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Missing permission: " + permission);
                return false;
            }
        }
        Log.d(TAG, "All permissions granted");
        return true;
    }

    public static void requestPermissions(Activity activity, String[] permissions, int requestCode) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode);
    }

    // Centralized helpers for common groups
    public boolean ensureLocationPermissions(int requestCode) {
        Permissions p = new Permissions();
        if (!hasPermissions(context, p.LOCATION_PERMISSIONS)) {
            requestPermissions(activity, p.LOCATION_PERMISSIONS, requestCode);
            return false;
        }
        return true;
    }

    public boolean ensureBluetoothPermissions(int requestCode) {
        if (!hasPermissions(context, Permissions.BLUETOOTH_PERMISSIONS)) {
            requestPermissions(activity, Permissions.BLUETOOTH_PERMISSIONS, requestCode);
            return false;
        }
        return true;
    }

    public boolean ensureNotificationPermissions(int requestCode) {
        Permissions p = new Permissions();
        if (!hasPermissions(context, p.NOTIFICATION_PERMISSIONS)) {
            requestPermissions(activity, p.NOTIFICATION_PERMISSIONS, requestCode);
            return false;
        }
        return true;
    }

    public boolean ensureMediaPermissions(int requestCode) {
        Permissions p = new Permissions();
        if (!hasPermissions(context, p.MEDIA_PERMISSIONS)) {
            requestPermissions(activity, p.MEDIA_PERMISSIONS, requestCode);
            return false;
        }
        return true;
    }

    public boolean ensureStoragePermissions(int requestCode) {
        Permissions p = new Permissions();
        if (!hasPermissions(context, p.STORAGE_PERMISSIONS)) {
            requestPermissions(activity, p.STORAGE_PERMISSIONS, requestCode);
            return false;
        }
        return true;
    }

    // FROM PERMISSIONS CLASS
    public static class Permissions {
        public static final String TAG = "Permissions";
        public static final int REQUEST_CODE = 1001;
        public static final int REQUEST_CODE_STORAGE = 1002;
        public static final int REQUEST_CODE_LOCATION = 1003;
        public static final int REQUEST_CODE_BLUETOOTH = 1004;
        public static final int REQUEST_CODE_MEDIA = 1005;
        public static final int REQUEST_CODE_AUDIO = 1006;
        public static final int REQUEST_CODE_NOTIFICATION = 1007;
        public static final int REQUEST_CODE_OTHER = 1008;
        public static final int REQUEST_CODE_ALL = 1009;
        public final String[] ALL_PERMISSIONS = {
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.MODIFY_AUDIO_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS
        };

        public static final String[] BLUETOOTH_PERMISSIONS = {
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
        };

        public final String[] LOCATION_PERMISSIONS = {
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        };

        public final String[] STORAGE_PERMISSIONS = {
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
        };

        public final String[] MEDIA_PERMISSIONS = {
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION
        };

        private final String[] AUDIO_PERMISSIONS = {
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.MODIFY_AUDIO_SETTINGS,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
        };

        public final String[] NOTIFICATION_PERMISSIONS = {
                android.Manifest.permission.POST_NOTIFICATIONS
        };

        public final String[] MACCHANGER_PERMISSIONS = {
                android.Manifest.permission.INTERNET,
                android.Manifest.permission.ACCESS_WIFI_STATE,
                android.Manifest.permission.CHANGE_WIFI_STATE,
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                android.Manifest.permission.ACCESS_NETWORK_STATE
        };

        public final String[] OTHER_PERMISSIONS = {
                android.Manifest.permission.WAKE_LOCK,
                android.Manifest.permission.INTERNET
        };
    }
}
