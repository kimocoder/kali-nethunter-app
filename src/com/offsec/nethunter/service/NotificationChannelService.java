package com.offsec.nethunter.service;


import android.Manifest;
import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;

import com.offsec.nethunter.AsyncTask.CustomCommandsAsyncTask;
import com.offsec.nethunter.BuildConfig;
import com.offsec.nethunter.R;

// IntentService Class for pushing nethunter notification
public class NotificationChannelService extends IntentService {
    public static final String CHANNEL_ID = "NethunterNotifyChannel";
    public static final int NOTIFY_ID = 1002;
    public Intent resultIntent = null;
    public PendingIntent resultPendingIntent = null;
    public TaskStackBuilder stackBuilder = null;
    public static final String REMINDMOUNTCHROOT = BuildConfig.APPLICATION_ID + ".REMINDMOUNTCHROOT";
    public static final String USENETHUNTER = BuildConfig.APPLICATION_ID + ".USENETHUNTER";
    public static final String DOWNLOADING = BuildConfig.APPLICATION_ID + ".DOWNLOADING";
    public static final String INSTALLING = BuildConfig.APPLICATION_ID + ".INSTALLING";
    public static final String BACKINGUP = BuildConfig.APPLICATION_ID + ".BACKINGUP";
    public static final String CUSTOMCOMMAND_START = BuildConfig.APPLICATION_ID + ".CUSTOMCOMMAND_START";
    public static final String CUSTOMCOMMAND_FINISH = BuildConfig.APPLICATION_ID + ".CUSTOMCOMMAND_FINISH";

    public NotificationChannelService() {
        super("NotificationChannelService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "NethunterChannelService",
                    NotificationManager.IMPORTANCE_HIGH
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent != null) {
            if (intent.getAction() != null) {
                NotificationCompat.Builder builder;
                NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
                notificationManagerCompat.cancelAll();
                resultIntent = new Intent();
                stackBuilder = TaskStackBuilder.create(this);
                stackBuilder.addNextIntentWithParentStack(resultIntent);
                resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
                        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            // TODO: Consider calling
                            //    ActivityCompat#requestPermissions
                            // here to request the missing permissions, and then overriding
                            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                            //                                          int[] grantResults)
                            // to handle the case where the user grants the permission. See the documentation
                            // for ActivityCompat#requestPermissions for more details.
                            return;
                        }
                        notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                        break;
                    case USENETHUNTER:
                        builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                                .setAutoCancel(true)
                                .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                                .setTimeoutAfter(10000)
                                .setStyle(new NotificationCompat.BigTextStyle().bigText("Happy hunting!"))
                                .setContentTitle("KaliChroot is UP!")
                                .setContentText("Happy hunting!")
                                .setPriority(NotificationCompat.PRIORITY_MAX)
                                .setContentIntent(resultPendingIntent);
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            // TODO: Consider calling
                            //    ActivityCompat#requestPermissions
                            // here to request the missing permissions, and then overriding
                            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                            //                                          int[] grantResults)
                            // to handle the case where the user grants the permission. See the documentation
                            // for ActivityCompat#requestPermissions for more details.
                            return;
                        }
                        notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                        break;
                    case DOWNLOADING:
                        builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                                .setAutoCancel(true)
                                .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                                .setTimeoutAfter(15000)
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
                                .setTimeoutAfter(15000)
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
                                .setTimeoutAfter(15000)
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
                                .setContentText(
                                        "Command: \"" + intent.getStringExtra("CMD") +
                                        "\" is being run in background and in " +
                                        intent.getStringExtra("ENV") + " environment.")
                                .setPriority(NotificationCompat.PRIORITY_MAX)
                                .setContentIntent(resultPendingIntent);
                        notificationManagerCompat.notify(NOTIFY_ID, builder.build());
                        break;
                    case CUSTOMCOMMAND_FINISH:
                        final int returnCode = intent.getIntExtra("RETURNCODE", 0);
                        final String CMD = intent.getStringExtra("CMD");
                        String resultString = getResultString(returnCode, CMD);
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
        }
    }

    @NonNull
    private static String getResultString(int returnCode, String CMD) {
        String resultString = "";
        if (returnCode == CustomCommandsAsyncTask.ANDROID_CMD_SUCCESS) {
            resultString = "Return success.\nCommand: \"" + CMD + "\" has been executed in android environment.";
        } else if (returnCode == CustomCommandsAsyncTask.ANDROID_CMD_FAIL) {
            resultString = "Return error.\nCommand: \"" + CMD + "\" has been executed in android environment.";
        } else if (returnCode == CustomCommandsAsyncTask.KALI_CMD_SUCCESS) {
            resultString = "Return success.\nCommand: \"" + CMD + "\" has been executed in Kali chroot environment.";
        } else if (returnCode == CustomCommandsAsyncTask.KALI_CMD_FAIL) {
            resultString = "Return error.\nCommand: \"" + CMD + "\" has been executed in Kali chroot environment.";
        }
        return resultString;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }
}