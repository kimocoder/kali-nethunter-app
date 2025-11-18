package com.offsec.nethunter.gps;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.StrictMode;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;

import android.util.Log;
import android.widget.RemoteViews;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.offsec.nethunter.AppNavHomeActivity;
import com.offsec.nethunter.R;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.PermissionCheck;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.net.InetAddress;
import java.util.Date;
import java.util.Locale;

/*
 * Service to provide continuous location updates and send NMEA sentences via UDP.
 * No legacy GpsStatus or deprecated Handler() / addNmeaListener() usage remains.
 *
 * TODO:
 * Remove the resetListenersTimerTask “reset on an hour” logic now that the GNSS callbacks are stable.
 * Add unit/instrumentation tests around startLocationUpdates() / stopLocationUpdates() to verify the state transitions—but functionally, guard and lifecycle behavior.
 *
 */
public class LocationUpdateService extends Service {
    private FusedLocationProviderClient fusedLocationClient;
    private static LocationUpdateService instance = null;
    private KaliGPSUpdates.Receiver updateReceiver;
    public static final String CHANNEL_ID = "NethunterLocationUpdateChannel";
    public static final int NOTIFY_ID = 1004;
    private static final String TAG = "LocationUpdateService";
    private static final String notificationTitle = "GPS Provider running";
    private static final String notificationText = "Sending GPS data to udp://127.0.0.1:" + NhPaths.GPS_PORT;
    private String lastLocationSourceReceived = "None";
    private String lastLocationSourcePublished = "None";
    private String lastNotificationText = null;
    private double lastLocationLatitude = 0.0;
    private double lastLocationLongitude = 0.0;
    private int lastLocationSats = 0;
    private double lastLocationAccuracy = 0.0;
    private InetAddress udpDestAddr = null;
    private DatagramSocket dSock = null;
    private Date lastLocationTime = new Date();
    private Handler timerTaskHandler = null;
    private Handler resetListenersTimerTaskHandler = null;
    private final IBinder binder = new ServiceBinder();
    private GnssStatus.Callback gnssStatusCallback;
    private OnNmeaMessageListener nmeaMessageListener;

    // this allows us to check if there is already a LocationUpdateService running without actually attaching to it
    public static boolean isInstanceCreated() {
        return (instance != null);
    }

    // this gets called if we launch via an Intent or from the command line, instead of the app
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        requestUpdates(null); // request updates, but no widget to receive them as we don't have the app running
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initTimers();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "NethunterPersistentChannelService",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setupGnssCallbacks();
        }
    }

    private boolean locationUpdatesStarted = false;
    @RequiresApi(api = Build.VERSION_CODES.M)
    public void startLocationUpdates() {
        if (locationUpdatesStarted)
            return;
        locationUpdatesStarted = true;

        final LocationRequest lr = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L / 2L)
                .setMinUpdateIntervalMillis(100L)
                .setMaxUpdateDelayMillis(600L)
                .setDurationMillis(1000 * 3600 * 2)
                .build();

        Log.d(TAG, "Requesting permissions ..");
        // Only bail out if we are actually missing required location permissions
        if (!PermissionCheck.hasPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        })) {
            Log.d(TAG, "Location permissions not granted in service; startLocationUpdates() will not proceed.");
            return;
        }

        // Initialize fusedLocationClient if not already initialized
        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        }

        // Register with Location services, so we can construct fake NMEA data
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling ActivityCompat#requestPermissions and handling the result
            return;
        }
        fusedLocationClient.requestLocationUpdates(lr, locationListener, null);

        // GNSS callbacks are set up once in onCreate() via setupGnssCallbacks()

        // turn on a Persistent Notification so we can continue to get location updates even when backgrounded
        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
        Intent resultIntent = new Intent(this, AppNavHomeActivity.class);
        resultIntent.putExtra("menuFragment", R.id.gps_item);
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setAutoCancel(false)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentText(notificationText)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setContentTitle(notificationTitle)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(resultPendingIntent);
        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "POST_NOTIFICATIONS not granted; skipping foreground notification update.");
        } else {
            notificationManagerCompat.notify(NOTIFY_ID, notification);
        }

        this.startForeground(NOTIFY_ID, notification);
        Log.d(TAG, "starting Notification Update Timer");
        startTimers();
    }

    private void initTimers() {
        timerTaskHandler = new Handler(getMainLooper());
        resetListenersTimerTaskHandler = new Handler(getMainLooper());
    }

    private void setupGnssCallbacks() {
        LocationManager locationManager = (LocationManager) getSystemService(Service.LOCATION_SERVICE);
        if (locationManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Ensure we hold fine location permission before registering GNSS callbacks
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "setupGnssCallbacks: ACCESS_FINE_LOCATION not granted, skipping GNSS registration");
                return;
            }

            try {
                gnssStatusCallback = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                        // Reserved for future use (e.g., passing to NMEA helper)
                    }
                };
                locationManager.registerGnssStatusCallback(gnssStatusCallback, new Handler(getMainLooper()));
            } catch (SecurityException se) {
                Log.w(TAG, "Failed to register GnssStatusCallback due to missing permission", se);
            }

            try {
                nmeaMessageListener = (message, timestamp) -> {
                    if (!message.startsWith("$GPGGA")) {
                        if ("GPS".equals(lastLocationSourcePublished))
                            sendUdpPacket(message);
                        return;
                    }
                    lastLocationSourceReceived = "NmeaListener";
                    publishLocation(message, "GPS");
                };
                // Use the modern overload with an explicit Handler to avoid deprecated API
                locationManager.addNmeaListener(nmeaMessageListener, new Handler(getMainLooper()));
            } catch (SecurityException se) {
                Log.w(TAG, "Failed to register OnNmeaMessageListener due to missing permission", se);
            }
        }
    }

    private void teardownGnssCallbacks() {
        LocationManager locationManager = (LocationManager) getSystemService(Service.LOCATION_SERVICE);
        if (locationManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            gnssStatusCallback = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && nmeaMessageListener != null) {
            locationManager.removeNmeaListener(nmeaMessageListener);
            nmeaMessageListener = null;
        }
    }

    private void startTimers() {
        timerTask.run();
        // Android will stop sending us updates two hours after the request.
        // So, every hour, we will make a new request
        resetListenersTimerTaskHandler.postDelayed(resetListenersTimerTask, 3600*1000);
        // resetListenersTimerTask.run();
    }

    private void stopTimers() {
        timerTaskHandler.removeCallbacks(timerTask);
        resetListenersTimerTaskHandler.removeCallbacks(resetListenersTimerTask);
    }

    private final Runnable resetListenersTimerTask = () -> {
        // reset our listeners
        Log.d(TAG, "Restarting listeners");
        stopLocationUpdates();
        requestUpdates(null);
    };

    private final Runnable timerTask = new Runnable() {
        @Override
        public void run() {
            try {
                if (lastLocationLatitude != 0.0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        updateNotification();
                    }
                // Log.d(TAG, "TimerTask: " + lastLocationLatitude + ", " + lastLocationLongitude);
            } catch (Exception e) {
                Log.d(TAG, "TimerTask Exception: " + e);
                // e.printStackTrace();
            } finally {
                // once per second
                timerTaskHandler.postDelayed(timerTask, 1000);
            }
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void updateNotification() {
        Date now = new Date();
        long age = (now.getTime() - lastLocationTime.getTime()) / 1000;
        String ageStr;
        if (age <= 10)
            ageStr = "current";
        else if (age < 60)
            ageStr = age + "s";
        else if (age < 3600)
            ageStr = (age / 60) + "m";
        else
            ageStr = (age / 3600) + "h";
        String updatedText = String.format(Locale.getDefault(),"Latitude: %1.5f  Longitude: %1.5f  +/- %1.1fm  Source: %s  Age: %s  Satellites: %d",
                lastLocationLatitude, lastLocationLongitude, lastLocationAccuracy,
                lastLocationSourcePublished, ageStr, lastLocationSats);

        if (updatedText.equals(lastNotificationText))
            return;
        lastNotificationText = updatedText;

        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(getApplicationContext());
        Intent resultIntent = new Intent(this, AppNavHomeActivity.class);
        resultIntent.putExtra("menuFragment", R.id.gps_item);
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntentWithParentStack(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.gps_notification);
        contentView.setTextViewText(R.id.gps_notification_latitude, String.format(Locale.getDefault(),"%1.5f", lastLocationLatitude));
        contentView.setTextViewText(R.id.gps_notification_longitude, String.format(Locale.getDefault(),"%1.5f", lastLocationLongitude));
        contentView.setTextViewText(R.id.gps_notification_accuracy, String.format(Locale.getDefault(),"%1.1fm", lastLocationAccuracy));
        contentView.setTextViewText(R.id.gps_notification_source, lastLocationSourcePublished);
        contentView.setTextViewText(R.id.gps_notification_age, ageStr);
        if (lastLocationSourcePublished.equals("GPS"))
            contentView.setTextViewText(R.id.gps_notification_sats, String.format(Locale.getDefault(),"%d", lastLocationSats));
        else
            contentView.setTextViewText(R.id.gps_notification_sats, "-");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setAutoCancel(false)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContent(contentView)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(contentView)
                .setContentTitle(notificationTitle)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(resultPendingIntent);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "POST_NOTIFICATIONS not granted; skipping foreground notification update.");
        } else {
            notificationManagerCompat.notify(NOTIFY_ID, notification);
        }
        Log.d(TAG, "Notification Sent: " + updatedText);
    }

    private boolean firstupdate = true;
    private final LocationListener locationListener = location -> {
        String nmeaSentence = nmeaSentenceFromLocation(location);

        Log.d(TAG, "Constructed NMEA: "+nmeaSentence);
        // we will only publish these constructed sentences if we aren't currently getting real ones from the NmeaListener
        if(lastLocationSourceReceived.equals("LocationListener"))
            publishLocation(nmeaSentence, "Network");
        lastLocationSourceReceived = "LocationListener";
    };

    private void publishLocation(String nmeaSentence, String source) {
        // Workaround to allow network operations in main thread
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        try {
            String[] fields = nmeaSentence.split(",");
            String latStr = fields[2];
            String ns = fields[3];
            String lonStr = fields[4];
            String ew = fields[5];
            int sats = Integer.parseInt(fields[7]);
            double accuracy = Float.parseFloat(fields[8]) * 19.0; // why 19.0?  see https://gitlab.com/gpsd/gpsd/-/blob/master/libgpsd_core.c, P_UERE_NO_DGPS
            int latDeg = Integer.parseInt(latStr.substring(0, 2));
            double latMin = Float.parseFloat(latStr.substring(2));
            double lat = latDeg + latMin/60.0;
            int lonDeg = Integer.parseInt(lonStr.substring(0, 3));
            double lonMin = Float.parseFloat(lonStr.substring(3));
            double lon = lonDeg + lonMin/60.0;
            if (ns.equalsIgnoreCase("S"))
                lat *= -1;
            if (ew.equalsIgnoreCase("W"))
                lon *= -1;

            synchronized (this) {
                lastLocationLatitude = lat;
                lastLocationLongitude = lon;
            }
            lastLocationSats = sats;
            lastLocationAccuracy = accuracy;
            lastLocationTime = new Date();
            lastLocationSourcePublished = source;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
        }

        if (updateReceiver != null) {
            if (firstupdate) {
                firstupdate = false;
                updateReceiver.onFirstPositionUpdate();
            }
            updateReceiver.onPositionUpdate(nmeaSentence);
        }
        sendUdpPacket(nmeaSentence);
    }

    private void initializeUdpComponents() {
        if (udpDestAddr == null) {
            try {
                udpDestAddr = Inet4Address.getByName("127.0.0.1");
            } catch (UnknownHostException e) {
                Log.d(TAG, "UnknownHostException: " + e);
            }
        }

        if (dSock == null) {
            try {
                dSock = new DatagramSocket();
            } catch (final java.net.SocketException e) {
                Log.d(TAG, "SocketException: " + e);
                dSock = null;
            }
        }
    }

    private void sendUdpPacket(String nmeaSentence) {
        initializeUdpComponents();

        if (udpDestAddr == null || dSock == null) {
            Log.d(TAG, "UDP destination address or socket is null. Packet not sent.");
            return;
        }

        try {
            nmeaSentence += "\n";
            byte[] buf = nmeaSentence.getBytes();
            DatagramPacket packet = new DatagramPacket(buf, buf.length, udpDestAddr, NhPaths.GPS_PORT);
            dSock.send(packet);
        } catch (final IOException e) {
            Log.d(TAG, "IOException: " + e);
            dSock = null;
            udpDestAddr = null;
        }
    }

    private String nmeaSentenceFromLocation(Location location) {
        // from: https://github.com/ya-isakov/blue-nmea-mirror/blob/master/src/Source.java
        String time = formatTime(location);
        String position = formatPosition(location);
        String accuracy = String.format(Locale.getDefault(),"%.4f", location.getAccuracy()/19.0); // why 19.0?  see https://gitlab.com/gpsd/gpsd/-/blob/master/libgpsd_core.c, P_UERE_NO_DGPS
        String innerSentence = String.format("GPGGA,%s,%s,1,%s,%s,%s,,,,", time, position, formatSatellites(location),
                accuracy, formatAltitude(location));

        // Adds checksum and initial $
        String checksum = checksum(innerSentence);
        return "$" + innerSentence + checksum;
    }

    private void stopLocationUpdates() {
        if (!locationUpdatesStarted) {
            return;
        }
        locationUpdatesStarted = false;
        teardownGnssCallbacks();
        fusedLocationClient.removeLocationUpdates(locationListener);
    }

    @Override
    public void onDestroy() {
        instance = null;
        firstupdate = true;
        // stop our Notification update timer
        stopTimers();
        // Always tear down GNSS callbacks to avoid leaks
        teardownGnssCallbacks();
        // Stop location updates if they were started
        stopLocationUpdates();
        super.onDestroy();
    }

    public static final class ServiceBinder extends Binder {
        public LocationUpdateService getService() {
            return instance;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void requestUpdates(KaliGPSUpdates.Receiver receiver) {
        if (receiver != null) {
            this.updateReceiver = receiver;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startLocationUpdates();
        }
    }

    public void stopUpdates() {
        stopSelf();
    }

    // Local helpers for NMEA construction (previously defined below but removed):
    public String formatSatellites(Location location) {
        int satellites = 0;
        Bundle bundle = location.getExtras();
        if (bundle != null) {
            satellites = bundle.getInt("satellites");
        }
        if (satellites > 4) {
            return String.valueOf(satellites);
        }
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            return satellites == 0 ? "" : String.valueOf(satellites);
        }
        return "4";
    }

    public String formatAltitude(Location location) {
        StringBuilder s = new StringBuilder();
        if (location.hasAltitude()) {
            s.append(String.format(Locale.getDefault(),"%.4f,M", location.getAltitude()));
        } else {
            s.append(",");
        }
        return s.toString();
    }

    private String checksum(String s) {
        int checksum = 0;
        for (int i = 0; i < s.length(); i++) {
            checksum ^= s.charAt(i);
        }
        String hex = Integer.toHexString(checksum);
        if (hex.length() == 1) hex = "0" + hex;
        return "*" + hex.toUpperCase();
    }

    public static String formatTime(Location location) {
        // Simple HHmmss formatter without external deps
        Date date = new Date(location.getTime());
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HHmmss", java.util.Locale.US);
        return sdf.format(date);
    }

    public static String formatPosition(Location location) {
        double latitude = location.getLatitude();
        char nsSuffix = latitude < 0 ? 'S' : 'N';
        latitude = Math.abs(latitude);

        double longitude = location.getLongitude();
        char ewSuffix = longitude < 0 ? 'W' : 'E';
        longitude = Math.abs(longitude);

        String lat = String.format(Locale.getDefault(),"%02d%02d.%04d,%c",
                (int) latitude,
                (int) (latitude * 60) % 60,
                (int) (latitude * 60 * 10000) % 10000,
                nsSuffix);
        String lon = String.format(Locale.getDefault(),"%03d%02d.%04d,%c",
                (int) longitude,
                (int) (longitude * 60) % 60,
                (int) (longitude * 60 * 10000) % 10000,
                ewSuffix);
        return lat + "," + lon;
    }

    // keep the rest of the existing methods (updateNotification, locationListener, publishLocation,
    // UDP helpers, nmeaSentenceFromLocation, stopLocationUpdates, requestPostNotificationsPermission, onDestroy) unchanged.
}
