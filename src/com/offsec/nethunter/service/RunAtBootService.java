package com.offsec.nethunter.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.offsec.nethunter.AppNavHomeActivity;
import com.offsec.nethunter.BuildConfig;
import com.offsec.nethunter.R;
import com.offsec.nethunter.utils.CheckForRoot;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.util.HashMap;
import java.util.Map;

public class RunAtBootService extends Service {
    private static final String TAG = "Nethunter: Startup";
    private NotificationCompat.Builder n = null;
    private SharedPreferences sharedPreferences;
    private HandlerThread workerThread;
    private Handler workerHandler;

    // Replacement for deprecated enqueueWork
    public static void enqueueWork(Context context, Intent work) {
        work.setClass(context, RunAtBootService.class);
        context.startService(work);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NhPaths.getInstance(getApplicationContext());
        createNotificationChannel();
        sharedPreferences = getApplicationContext()
                .getSharedPreferences(BuildConfig.APPLICATION_ID, MODE_PRIVATE);
        workerThread = new HandlerThread("RunAtBootWorker", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        workerHandler.post(() -> {
            onHandleIntent();
            stopSelf(startId);
        });
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service
    }

    private void doNotification(String contents) {
        if (n == null) {
            n = new NotificationCompat.Builder(getApplicationContext(), AppNavHomeActivity.BOOT_CHANNEL_ID);
        }
        n.setStyle(new NotificationCompat.BigTextStyle().bigText(contents))
                .setContentTitle(TAG)
                .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                .setAutoCancel(true);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(999, n.build());
        }
    }

    // Former onHandleWork path
    protected void onHandleIntent() {
        String isOK = "OK.";
        doNotification("Doing boot checks...");

        HashMap<String, String> hashMap = new HashMap<>();
        hashMap.put("ROOT", "No root access is granted.");
        hashMap.put("BUSYBOX", "No busybox is found.");
        hashMap.put("CHROOT", "Chroot is not yet installed.");

        if (CheckForRoot.isRoot()) hashMap.put("ROOT", isOK);
        if (CheckForRoot.isBusyboxInstalled()) hashMap.put("BUSYBOX", isOK);

        ShellExecuter exe = new ShellExecuter();

        if (sharedPreferences.getBoolean("SELinuxOnBoot", true)) {
            new ShellExecuter().RunAsRootOutput("[ ! \"$(getenforce | grep Permissive)\" ] && setenforce 0");
        }

        Boolean chroot_auto = sharedPreferences.getBoolean("chroot_autostart_enabled", true);
        if (chroot_auto) {
            exe.RunAsRootOutput(NhPaths.BUSYBOX + " run-parts " + NhPaths.APP_INITD_PATH);
            if (exe.RunAsRootReturnValue(NhPaths.APP_SCRIPTS_PATH + "/chrootmgr -c \"status\"") == 0) {
                exe.RunAsRootOutput("rm -rf " + NhPaths.CHROOT_PATH() + "/tmp/.X1*");
                hashMap.put("CHROOT", isOK);
            }
        } else {
            hashMap.put("CHROOT", "autostart disabled.");
        }

        String resultMsg = "Boot completed.\nEveryting is fine and Chroot has been started!";
        for (Map.Entry<String, String> entry : hashMap.entrySet()) {
            if (!entry.getValue().equals(isOK)) {
                if (chroot_auto) resultMsg = "Make sure the above requirements are met.";
                else resultMsg = "Please start chroot as needed.";
                break;
            }
        }
        Boolean iswatch = getBaseContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        if (!iswatch) {
            if (resultMsg.contains("Boot completed.")) {
                exe.RunAsChrootOutput("update-alternatives --set iptables /usr/sbin/iptables-legacy; iptables-save | grep -v \"bpf\" > /sdcard/iptables-default; if ! grep -B1 \"# Completed\" /sdcard/iptables-default | head -n1 | grep -q \"COMMIT\"; then sed -i '/^# Completed/i COMMIT' /sdcard/iptables-default; fi");
            }
        }

        doNotification(
                "Root: " + hashMap.get("ROOT") + "\n" +
                        "Busybox: " + hashMap.get("BUSYBOX") + "\n" +
                        "Chroot: " + hashMap.get("CHROOT") + "\n" +
                        resultMsg
        );
    }

    @Override
    public void onDestroy() {
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    AppNavHomeActivity.BOOT_CHANNEL_ID,
                    "Nethunter Boot Check Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(serviceChannel);
            }
        }
    }
}