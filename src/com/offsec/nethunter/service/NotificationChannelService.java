package com.offsec.nethunter.service;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;

import com.offsec.nethunter.Executor.CustomCommandsExecutor;
import com.offsec.nethunter.BuildConfig;
import com.offsec.nethunter.R;

public class NotificationChannelService extends Service {
    public static final String CHANNEL_ID = "NethunterNotifyChannel";
    private static final String TAG = "NotificationService";
    public static final int NOTIFY_ID = 1002;
    public static final String REMINDMOUNTCHROOT = BuildConfig.APPLICATION_ID + ".REMINDMOUNTCHROOT";
    public static final String USENETHUNTER = BuildConfig.APPLICATION_ID + ".USENETHUNTER";
    public static final String DOWNLOADING = BuildConfig.APPLICATION_ID + ".DOWNLOADING";
    public static final String INSTALLING = BuildConfig.APPLICATION_ID + ".INSTALLING";
    public static final String BACKINGUP = BuildConfig.APPLICATION_ID + ".BACKINGUP";
    public static final String CUSTOMCOMMAND_START = BuildConfig.APPLICATION_ID + ".CUSTOMCOMMAND_START";
    public static final String CUSTOMCOMMAND_FINISH = BuildConfig.APPLICATION_ID + ".CUSTOMCOMMAND_FINISH";
    private HandlerThread workerThread;
    private Handler workerHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("NotificationChannelServiceWorker", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "NethunterChannelService",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final Intent work = intent;
        workerHandler.post(() -> {
            if (work != null) {
                handleIntent(work);
            }
            stopSelfResult(startId);
        });
        return START_NOT_STICKY;
    }

    private void handleIntent(@NonNull Intent intent) {
        if (intent.getAction() == null) return;

        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
        notificationManagerCompat.cancelAll();

        Intent resultIntent = new Intent();
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntentWithParentStack(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder;
        switch (intent.getAction()) {
            case REMINDMOUNTCHROOT:
                builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                        .setAutoCancel(true)
                        .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText("Please open nethunter app and navigate to ChrootManager to setup your KaliChroot."))
                        .setContentTitle("KaliChroot is not up or installed")
                        .setContentText("Please navigate to ChrootManager to setup your KaliChroot.")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(resultPendingIntent);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                } else {
                    Log.d(TAG, "POST_NOTIFICATIONS not granted; skipping REMINDMOUNTCHROOT notification.");
                }
                break;
            case USENETHUNTER:
                builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                        .setAutoCancel(true)
                        .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                        .setTimeoutAfter(10_000)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText("Happy hunting!"))
                        .setContentTitle("KaliChroot is UP!")
                        .setContentText("Happy hunting!")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(resultPendingIntent);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                } else {
                    Log.d(TAG, "POST_NOTIFICATIONS not granted; skipping USENETHUNTER notification.");
                }
                break;
            case DOWNLOADING:
                builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                        .setAutoCancel(true)
                        .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                        .setTimeoutAfter(15_000)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText("Please don't kill the app or the download will be cancelled!"))
                        .setContentTitle("Downloading Chroot!")
                        .setContentText("Please don't kill the app or the download will be cancelled!")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(resultPendingIntent);
                notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                break;
            case INSTALLING:
                builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                        .setAutoCancel(true)
                        .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                        .setTimeoutAfter(15_000)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText("Please don't kill the app as it will still keep running on the background! Otherwise you'll need to kill the tar process by yourself."))
                        .setContentTitle("Installing Chroot")
                        .setContentText("Please don't kill the app as it will still keep running on the background! Otherwise you'll need to kill the tar process by yourself.")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(resultPendingIntent);
                notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                break;
            case BACKINGUP:
                builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                        .setAutoCancel(true)
                        .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                        .setTimeoutAfter(15_000)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText("Please don't kill the app as it will still keep running on the background! Otherwise you'll need to kill the tar process by yourself."))
                        .setContentTitle("Creating KaliChroot backup to local storage.")
                        .setContentText("Please don't kill the app as it will still keep running on the background! Otherwise you'll need to kill the tar process by yourself.")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(resultPendingIntent);
                notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                break;
            case CUSTOMCOMMAND_START:
                builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                        .setAutoCancel(false)
                        .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(
                                "Command: \"" + intent.getStringExtra("CMD") +
                                        "\" is being run in background and in " +
                                        intent.getStringExtra("ENV") + " environment."))
                        .setContentTitle("Custom Commands")
                        .setContentText("Command: \"" + intent.getStringExtra("CMD") +
                                "\" is being run in background and in " +
                                intent.getStringExtra("ENV") + " environment.")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(resultPendingIntent);
                notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                break;
            case CUSTOMCOMMAND_FINISH:
                int returnCode = intent.getIntExtra("RETURNCODE", 0);
                String cmd = intent.getStringExtra("CMD");
                String resultString = getResultString(returnCode, cmd);
                builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                        .setAutoCancel(false)
                        .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(resultString))
                        .setContentTitle("Custom Commands")
                        .setContentText(resultString)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(resultPendingIntent);
                notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                break;
        }
    }

    @NonNull
    private static String getResultString(int returnCode, String cmd) {
        String resultString = "";
        if (returnCode == CustomCommandsExecutor.ANDROID_CMD_SUCCESS) {
            resultString = "Return success.\nCommand: \"" + cmd + "\" has been executed in android environment.";
        } else if (returnCode == CustomCommandsExecutor.ANDROID_CMD_FAIL) {
            resultString = "Return error.\nCommand: \"" + cmd + "\" has been executed in android environment.";
        } else if (returnCode == CustomCommandsExecutor.KALI_CMD_SUCCESS) {
            resultString = "Return success.\nCommand: \"" + cmd + "\" has been executed in Kali chroot environment.";
        } else if (returnCode == CustomCommandsExecutor.KALI_CMD_FAIL) {
            resultString = "Return error.\nCommand: \"" + cmd + "\" has been executed in Kali chroot environment.";
        }
        return resultString;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }
}