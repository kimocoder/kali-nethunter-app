package com.offsec.nethunter;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.offsec.nethunter.Executor.CopyBootFilesExecutor;
import com.offsec.nethunter.SQL.CustomCommandsSQL;
import com.offsec.nethunter.SQL.KaliServicesSQL;
import com.offsec.nethunter.SQL.NethunterSQL;
import com.offsec.nethunter.SQL.USBArsenalSQL;
import com.offsec.nethunter.gps.KaliGPSUpdates;
import com.offsec.nethunter.gps.LocationUpdateService;
import com.offsec.nethunter.service.CompatCheckService;
import com.offsec.nethunter.utils.CheckForRoot;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.PermissionCheck;
import com.offsec.nethunter.utils.SharePrefTag;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

public class AppNavHomeActivity extends AppCompatActivity implements KaliGPSUpdates.Provider {
    public final static String TAG = "AppNavHomeActivity";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd KK:mm:ss a zzz", Locale.US);
    public static final String CHROOT_INSTALLED_TAG = "CHROOT_INSTALLED_TAG";
    public static final String GPS_BACKGROUND_FRAGMENT_TAG = "BG_FRAGMENT_TAG";
    public static final String BOOT_CHANNEL_ID = "BOOT_CHANNEL";
    /**
     * Fragment managing the behaviors, interactions and presentation of the navigation drawer.
     */
    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;
    private com.google.android.material.navigation.NavigationView navigationView;
    private com.google.android.material.navigation.NavigationView navigationViewWear;
    private CharSequence mTitle = "NetHunter";
    private final Stack<String> titles = new Stack<>();
    private SharedPreferences prefs;
    public static MenuItem lastSelectedMenuItem;
    private boolean locationUpdatesRequested = false;
    private KaliGPSUpdates.Receiver locationUpdateReceiver;
    private NhPaths nhPaths;
    private PermissionCheck permissionCheck;
    private BroadcastReceiver nethunterReceiver;
    public static Boolean isBackPressEnabled = true;
    private int desiredFragment = -1;
    public CopyBootFilesExecutor copyBootFilesExecutor;
    public static MenuItem customCMDitem;
    private final ShellExecuter exe = new ShellExecuter();
    private volatile boolean rootViewInitialized = false;

    private Thread permissionWaitThread;
    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        Intent nfcIntent = new Intent(this, getClass());
        nfcIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        nfcPendingIntent = PendingIntent.getActivity(
                this, 0, nfcIntent, PendingIntent.FLAG_MUTABLE
        );

        // Initiate the NhPaths singleton class, and it will then keep living until the app dies.
        // Also with its sharepreference listener registered, the CHROOT_PATH variable can be updated immediately on sharepreference changes.
        nhPaths = NhPaths.getInstance(getApplicationContext());

        // Handle the back button press
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isBackPressEnabled) {
                    if (titles.size() > 1) {
                        titles.pop();
                        mTitle = titles.peek();
                        // update menu selection and action bar
                        Menu menuNav = navigationView.getMenu();
                        int i = 0;
                        int mSize = menuNav.size();
                        while (i < mSize) {
                            if (menuNav.getItem(i).getTitle() == mTitle) {
                                MenuItem _current = menuNav.getItem(i);
                                if (lastSelectedMenuItem != _current) {
                                    Intent intent = new Intent(getApplicationContext(), AppNavHomeActivity.class);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    startActivity(intent);
                                    finish();
                                }
                                _current.setChecked(true);
                                i = mSize;
                            }
                            i++;
                        }
                        restoreActionBar();
                        // Do not call super.onBackPressed() here!
                    } else {
                        // If only one title left, finish the activity
                        finish();
                    }
                }
            }
        });

        // We need to run root check here so nothing else doesn't pile up with dialogs
        if (!CheckForRoot.isRoot()) {
            showWarningDialog("NetHunter app cannot be run properly", "Root permission is required!!", true);
        }

        // This function just delays here until root is given.
        // Purpose: don't run anything else until root access is given :)
        new Thread(() -> {
            while (!CheckForRoot.isRoot()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    runOnUiThread(() -> showWarningDialog("NetHunter app cannot be run properly", "Root permission is required!!", true));
                }
            }
        }).start();

        // Initiate the PermissionCheck class.
        permissionCheck = new PermissionCheck(this, getApplicationContext());
        // Register the NetHunter receiver with intent actions.
        nethunterReceiver = new NethunterReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BuildConfig.APPLICATION_ID + ".CHECKCOMPAT");
        intentFilter.addAction(BuildConfig.APPLICATION_ID + ".BACKPRESSED");
        intentFilter.addAction(BuildConfig.APPLICATION_ID + ".CHECKCHROOT");
        intentFilter.addAction("ChrootManager");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.registerReceiver(nethunterReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ContextCompat.registerReceiver(this, nethunterReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        // initiate prefs.
        prefs = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);

        // Start copying the app files to the corresponding path.
        copyBootFilesExecutor = new CopyBootFilesExecutor(getApplicationContext(), this);
        //setContentView(R.layout.base_layout);
        //ProgressBar progressBar = findViewById(R.id.progressBar);
        //copyBootFilesExecutor = new CopyBootFilesExecutor(getApplicationContext(), this, progressBar);
        copyBootFilesExecutor.setListener(new CopyBootFilesExecutor.CopyBootFilesExecutorListener() {
            public void onPrepare() {
                //progressBar.setVisibility(View.VISIBLE);
                //progressBar.setIndeterminate(true);
                //progressBar.setProgress(0);
                //progressBar.setMax(100);
            }

            public void onFinished(String result) {
                //progressBar.setVisibility(View.GONE);
                Log.d(TAG, "CopyBootFilesExecutor finished with result: " + result);
                if (result != null && !result.isEmpty()) {
                    showWarningDialog("NetHunter app cannot be run properly", result, true);
                } else {
                    Log.d(TAG, "Boot files copied successfully.");
                }
            }

            public void onExecutorPrepare() {
                //progressBar.setVisibility(View.GONE);
            }

            public void onExecutorFinished(String result) {
                // Fetch the busybox path again after the busybox_nh is copied.
                //progressBar.setVisibility(View.GONE);
                NhPaths.BUSYBOX = NhPaths.getBusyboxPath();
                // Initialize SQL singletons
                ExecutorService executor = Executors.newFixedThreadPool(4);
                try {
                    executor.execute(() -> NethunterSQL.getInstance(getApplicationContext()));
                    executor.execute(() -> KaliServicesSQL.getInstance(getApplicationContext()));
                    executor.execute(() -> CustomCommandsSQL.getInstance(getApplicationContext()));
                    executor.execute(() -> USBArsenalSQL.getInstance(getApplicationContext()));
                } finally {
                    executor.shutdown();
                }
                // Setup default SharedPreferences
                setDefaultSharePreference();

                // Check for busybox installation
                if (!CheckForRoot.isBusyboxInstalled()) {
                    showWarningDialog("NetHunter app cannot be run properly", "No busybox is detected, please make sure you have busybox installed!!", true);
                }

                // Check if NetHunter terminal app is installed.
                if (getApplicationContext().getPackageManager().getLaunchIntentForPackage("com.offsec.nhterm") == null) {
                    showWarningDialog("NetHunter app cannot be run properly", "NetHunter terminal is not installed yet.", true);
                }

                // Check if all required permissions are granted.
                if (isAllRequiredPermissionsGranted()) {
                    ensureRootView();
                }
            }
        });

        // Request permissions after initialization
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(AppNavHomeActivity.this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            // Android 13+: Wi‑Fi-related ops (scan/connect) require NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(AppNavHomeActivity.this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }
        if (ContextCompat.checkSelfPermission(AppNavHomeActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(AppNavHomeActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!permissionsToRequest.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(permissionsToRequest.toArray(new String[0]), 2001);
        }

        // We must not attempt to copy files unless we have storage permissions
        if (isAllRequiredPermissionsGranted()) {
            copyBootFilesExecutor.execute();
        } else {
            // Wait for permissions on a background thread to avoid ANR
            permissionWaitThread = new Thread(() -> {
                int t = 0;
                while (!permissionCheck.isAllPermitted(PermissionCheck.DEFAULT_PERMISSIONS)) {
                    try {
                        Thread.sleep(1000);
                        t++;
                        Log.d(TAG, "Permissions missing. Waiting ..." + t);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (t >= 10) {
                        break;
                    }
                }
                runOnUiThread(() -> {
                    if (permissionCheck.isAllPermitted(PermissionCheck.DEFAULT_PERMISSIONS)) {
                        copyBootFilesExecutor.execute();
                    } else {
                        showWarningDialog("Permissions required", "Please restart application to finalize setup", true);
                    }
                });
            });
            permissionWaitThread.start();
        }

        int menuFragment = getIntent().getIntExtra("menuFragment", -1);
        if (menuFragment != -1) {
            Log.d(TAG, "menuFragment = " + menuFragment);
            desiredFragment = menuFragment;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                mDrawerLayout.closeDrawers();
            } else {
                mDrawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PermissionCheck.DEFAULT_PERMISSION_RQCODE) {
            boolean allGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                ensureRootView();
            } else {
                showWarningDialog("NetHunter app cannot be run properly", "Please grant all the permission requests from outside the app or restart the app to grant the rest of permissions again.", true);
            }
        }
    }

    @Override
    public boolean onReceiverReattach(KaliGPSUpdates.Receiver receiver) {
        //Log.d(TAG, "onReceiverReattach");
        if (LocationUpdateService.isInstanceCreated()) {
            // there is already a service running, we should re-attach to it
            this.locationUpdateReceiver = receiver;
            Log.d(TAG, "locationService: " + !(locationService==null));
            if (locationService != null) {
                locationService.requestUpdates(locationUpdateReceiver);
            } else {
                onLocationUpdatesRequested(receiver);
            }
            return true;
        }
        return false;
    }

    @Override
    public void onLocationUpdatesRequested(KaliGPSUpdates.Receiver receiver) {
        locationUpdatesRequested = true;
        this.locationUpdateReceiver = receiver;
        Intent intent = new Intent(getApplicationContext(), LocationUpdateService.class);
        bindService(intent, locationServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private LocationUpdateService locationService;
    private boolean updateServiceBound = false;
    private final ServiceConnection locationServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to Update Service, cast the IBinder and get LocalService instance
            LocationUpdateService.ServiceBinder binder = (LocationUpdateService.ServiceBinder) service;
            locationService = binder.getService();
            updateServiceBound = true;
            if (locationUpdatesRequested) {
                locationService.requestUpdates(locationUpdateReceiver);
            }
        }

        public void onServiceDisconnected(ComponentName arg0) {
            updateServiceBound = false;
        }
    };

    @Override
    public void onStopRequested() {
        locationUpdatesRequested = false;
        if (locationService != null) {
            locationService.stopUpdates();
            locationService = null;
        }
        if (updateServiceBound) {
            updateServiceBound = false;
            unbindService(locationServiceConnection);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Run CompatCheck Service
        if (navigationView != null) startService(new Intent(getApplicationContext(), CompatCheckService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(
                    this,
                    nfcPendingIntent,
                    null,
                    null
            );
        }

        // If user just granted MANAGE_EXTERNAL_STORAGE from Settings, finalize the SD sync without re-running full executor
        if (copyBootFilesExecutor != null) {
            copyBootFilesExecutor.resumePendingSyncIfPermitted();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (permissionWaitThread != null) {
            permissionWaitThread.interrupt();
        }
        unregisterReceiver(nethunterReceiver);
        nhPaths.onDestroy();
    }

    private void hideMenuItemIfExists(int index) {
        if (navigationView == null) return;
        Menu menu = navigationView.getMenu();
        if (index >= 0 && index < menu.size()) {
            menu.getItem(index).setVisible(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @ColorInt
    private int safeGetColor(@ColorRes int colorRes, int fallbackArgb) {
        try {
            return ResourcesCompat.getColor(getResources(), colorRes, getTheme());
        } catch (Resources.NotFoundException e) {
            return fallbackArgb;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent == null) return;

        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) ||
                NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

            // Forward NFC intent to NFCFragment
            NFCFragment.dispatchIntent(this, intent);
        }
    }

    private synchronized void ensureRootView() {
        if (rootViewInitialized) return;
        try {
            setRootView();
        } finally {
            rootViewInitialized = true;
        }
    }

    private void setRootView() {
        setContentView(R.layout.base_layout);

        // Bind required views immediately
        mDrawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.navigation_view);

        if (mDrawerLayout == null || navigationView == null) {
            Log.e(TAG, "Missing DrawerLayout or NavigationView in 'base_layout'.");
            showWarningDialog("UI error", "Required views are missing in `base_layout`.", true);
            return;
        }

        // Action bar
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setHomeButtonEnabled(true);
            ab.setDisplayHomeAsUpEnabled(true);
        }

        // Status bar color with safe resolver
        getWindow().setStatusBarColor(safeGetColor(R.color.darkTitle, 0xFF121212));

        Boolean iswatch = getBaseContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);

        // Snowfall enable 2/2
        prefs.edit().putBoolean("snowfall_enabled", false).apply();

        // inapp term enable
        Boolean inappterm;
        inappterm = prefs.getBoolean("inapp_terminal_enabled", false);
        if (!inappterm) hideMenuItemIfExists(2);

        String model = Build.HARDWARE;
        Boolean snowfall;
        if (iswatch) {
            snowfall = prefs.getBoolean("snowfall_enabled", false);

            // Safe index-based hiding
            hideMenuItemIfExists(2);
            hideMenuItemIfExists(3);
            hideMenuItemIfExists(4);
            hideMenuItemIfExists(5);
            if (model.equals("catfish") || model.equals("catshark") || model.equals("catshark-4g")) hideMenuItemIfExists(9);
            hideMenuItemIfExists(10);
            hideMenuItemIfExists(15);
            hideMenuItemIfExists(16);
            hideMenuItemIfExists(19);
            hideMenuItemIfExists(21);
            hideMenuItemIfExists(22);
            hideMenuItemIfExists(23);
            hideMenuItemIfExists(24);
            hideMenuItemIfExists(25);
        } else {
            snowfall = prefs.getBoolean("snowfall_enabled", true);
        }

        View SnowfallView = findViewById(R.id.snowfall);
        if (SnowfallView != null) {
            SnowfallView.setVisibility(snowfall ? View.VISIBLE : View.GONE);
        }

        // Disable USB arsenal for devices without ConfigFS support
        if (!new File("/config/usb_gadget/g1").exists()) {
            Menu menu = navigationView.getMenu();
            if (menu.size() > 7) {
                menu.getItem(7).setVisible(false);
            }
        }

        // Header
        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        LinearLayout navigationHeadView = (LinearLayout) inflater.inflate(R.layout.sidenav_header, navigationView, false);
        navigationView.addHeaderView(navigationHeadView);

        FloatingActionButton readmeButton = navigationHeadView.findViewById(R.id.info_fab);
        if (readmeButton != null) {
            readmeButton.setOnClickListener(v -> showLicense());
        }

        // Build info
        final String buildTime = SDF.format(BuildConfig.BUILD_TIME);
        TextView buildInfo1 = navigationHeadView.findViewById(R.id.buildinfo1);
        TextView buildInfo2 = navigationHeadView.findViewById(R.id.buildinfo2);
        if (buildInfo1 != null) buildInfo1.setText(String.format("Version: %s (%s)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        if (buildInfo2 != null) buildInfo2.setText(String.format("Date: %s", buildTime));

        setupDrawerContent(navigationView);

        // Use allowingStateLoss to avoid IllegalStateException if state was already saved
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, NetHunterFragment.newInstance(R.id.nethunter_item))
                .commitAllowingStateLoss();

        // First menu item title
        Menu nvMenu = navigationView.getMenu();
        if (nvMenu.size() > 0) {
            MenuItem firstMenuItem = nvMenu.getItem(0);
            if (firstMenuItem != null && firstMenuItem.getTitle() != null) {
                titles.push(firstMenuItem.getTitle().toString());
            }
        }

        // Enable chroot-dependent group
        navigationView.getMenu().setGroupEnabled(R.id.chrootDependentGroup, true);

        // Open drawer on first launch
        boolean seenNav = prefs.getBoolean("seenNav", false);
        if (!seenNav) {
            mDrawerLayout.openDrawer(GravityCompat.START);
            prefs.edit().putBoolean("seenNav", true).apply();
        }

        if (lastSelectedMenuItem == null && nvMenu.size() > 0) {
            lastSelectedMenuItem = nvMenu.getItem(0);
            if (lastSelectedMenuItem != null) lastSelectedMenuItem.setChecked(true);
        }

        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, R.string.drawer_opened, R.string.drawer_closed);
        mDrawerLayout.addDrawerListener(mDrawerToggle);
        mDrawerToggle.syncState();

        startService(new Intent(getApplicationContext(), CompatCheckService.class));

        if (desiredFragment != -1) {
            changeDrawer(desiredFragment);
            desiredFragment = -1;
        }
    }

    private void showLicense() {
        String readmeData = String.format("%s\n\n%s\n\n%s",
                getResources().getString(R.string.licenseInfo),
                getResources().getString(R.string.nhwarning),
                getResources().getString(R.string.nhteam));
        final SpannableString readmeText = new SpannableString(readmeData);
        Linkify.addLinks(readmeText, Linkify.WEB_URLS);

        // Ensure a MaterialComponents overlay is applied
        Context themed = new ContextThemeWrapper(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog);

        MaterialAlertDialogBuilder adb = new MaterialAlertDialogBuilder(themed, R.style.DialogStyle);
        adb.setTitle("README INFO")
                .setMessage(readmeText)
                .setNegativeButton("Cancel", (dialog, id) -> dialog.cancel())
                .setCancelable(true);

        AlertDialog ad = adb.create();
        ad.setCancelable(false);
        if (ad.getWindow() != null) {
            ad.getWindow().getAttributes().windowAnimations = R.style.DialogStyle;
        }
        ad.show();
        TextView msg = ad.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private void setupDrawerContent(NavigationView navigationView) {
        navigationView.setNavigationItemSelectedListener(
                menuItem -> {
                    // only change it if is not the same as the last one
                    if (lastSelectedMenuItem != menuItem) {
                        //remove last
                        if(lastSelectedMenuItem != null)
                            lastSelectedMenuItem.setChecked(false);
                        // update for the next
                        lastSelectedMenuItem = menuItem;
                    }
                    //set checked
                    menuItem.setChecked(true);
                    mDrawerLayout.closeDrawers();
                    mTitle = menuItem.getTitle();
                    assert mTitle != null;
                    titles.push(mTitle.toString());

                    int itemId = menuItem.getItemId();
                    changeDrawer(itemId);
                    restoreActionBar();
                    return true;
                });
    }

    private void setupDrawerContentWear(NavigationView navigationViewWear) {
        navigationViewWear.setNavigationItemSelectedListener(
                menuItem -> {
                    // only change it if is not the same as the last one
                    if (lastSelectedMenuItem != menuItem) {
                        //remove last
                        if(lastSelectedMenuItem != null)
                            lastSelectedMenuItem.setChecked(false);
                        // update for the next
                        lastSelectedMenuItem = menuItem;
                    }
                    //set checked
                    menuItem.setChecked(true);
                    mDrawerLayout.closeDrawers();
                    mTitle = menuItem.getTitle();
                    assert mTitle != null;
                    titles.push(mTitle.toString());

                    int itemId = menuItem.getItemId();
                    changeDrawer(itemId);
                    restoreActionBar();
                    return true;
                });
    }

    private void changeDrawer(int itemId) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (itemId == R.id.nethunter_item) {
            changeFragment(fragmentManager, NetHunterFragment.newInstance(itemId));
        } else if (itemId == R.id.can_item) {
            changeFragment(fragmentManager, CARsenalFragment.newInstance(itemId));
        } else if (itemId == R.id.kaliservices_item) {
            changeFragment(fragmentManager, KaliServicesFragment.newInstance(itemId));
        } else if (itemId == R.id.custom_commands_item) {
            changeFragment(fragmentManager, CustomCommandsFragment.newInstance(itemId));
        } else if (itemId == R.id.hid_item) {
            changeFragment(fragmentManager, HidFragment.newInstance(itemId));
        } else if (itemId == R.id.duckhunter_item) {
            changeFragment(fragmentManager, com.offsec.nethunter.DuckHunterFragment.newInstance(itemId));
        } else if (itemId == R.id.eviltwin_item) {
    changeFragment(fragmentManager, new EvilTwinFragment());
        } else if (itemId == R.id.usbarsenal_item) {
            if (exe.RunAsRootReturnValue("ls /config/usb_gadget/g1") == 0) {
                changeFragment(fragmentManager, USBArsenalFragment.newInstance(itemId));
            } else {
                showWarningDialog("", "USB Arsenal (ConfigFS) is only supported by kernels above 4.x. Please note that HID, RNDIS, and Mass Storage should be automatically enabled on older devices with NetHunter patches.", false);
            }
        } else if (itemId == R.id.badusb_item) {
            changeFragment(fragmentManager, BadusbFragment.newInstance(itemId));
        } else if (itemId == R.id.wifipumpkin_item) {
            changeFragment(fragmentManager, WifipumpkinFragment.newInstance(itemId));
        } else if (itemId == R.id.wps_item) {
            changeFragment(fragmentManager, WPSFragment.newInstance(itemId));
        } else if (itemId == R.id.bt_item) {
            changeFragment(fragmentManager, BTFragment.newInstance(itemId));
        } else if (itemId == R.id.nfc_item) {
            changeFragment(fragmentManager, NFCFragment.newInstance(itemId));
        } else if (itemId == R.id.audio_item) {
            changeFragment(fragmentManager, com.offsec.nethunter.AudioFragment.newInstance(itemId));
        } else if (itemId == R.id.macchanger_item) {
            changeFragment(fragmentManager, MacchangerFragment.newInstance(itemId));
        } else if (itemId == R.id.createchroot_item) {
            changeFragment(fragmentManager, ChrootManagerFragment.newInstance(itemId));
        } else if (itemId == R.id.mpc_item) {
            changeFragment(fragmentManager, MPCFragment.newInstance(itemId));
        } else if (itemId == R.id.vnc_item) {
            if (getApplicationContext().getPackageManager().getLaunchIntentForPackage("com.offsec.nethunter.kex") == null) {
                showWarningDialog("", "NetHunter KeX is not installed yet, please install from the store!", false);
            } else {
                changeFragment(fragmentManager, VNCFragment.newInstance(itemId));
            }
        } else if (itemId == R.id.searchsploit_item) {
            changeFragment(fragmentManager, com.offsec.nethunter.SearchSploitFragment.newInstance(itemId));
        } else if (itemId == R.id.terminal_item) {
            changeFragment(fragmentManager, TerminalFragment.newInstance(itemId));
        } else if (itemId == R.id.nmap_item) {
            changeFragment(fragmentManager, NmapFragment.newInstance(itemId));
        } else if (itemId == R.id.pineapple_item) {
            changeFragment(fragmentManager, PineappleFragment.newInstance(itemId));
        } else if (itemId == R.id.gps_item) {
            changeFragment(fragmentManager, KaliGpsServiceFragment.newInstance(itemId));
        } else if (itemId == R.id.settings_item) {
            changeFragment(fragmentManager, SettingsFragment.newInstance(itemId));
        } else if (itemId == R.id.kernel_item) {
            changeFragment(fragmentManager, KernelFragment.newInstance(itemId));
        } else if (itemId == R.id.modules_item) {
            changeFragment(fragmentManager, ModulesFragment.newInstance(itemId));
        } else if (itemId == R.id.set_item) {
            changeFragment(fragmentManager, SETFragment.newInstance(itemId));
        }
    }

    public void restoreActionBar() {
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setHomeButtonEnabled(true);
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setDisplayShowTitleEnabled(true);
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            ab.setTitle(mTitle);
        }
    }

    public void blockActionBar() {
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setHomeButtonEnabled(false);
            ab.setDisplayHomeAsUpEnabled(false);
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }
    }

    public void setDefaultSharePreference() {
        prefs.edit().putString(SharePrefTag.DUCKHUNTER_LANG_SHAREPREF_TAG, "us").apply();
        prefs.edit().putString(SharePrefTag.CHROOT_DEFAULT_BACKUP_SHAREPREF_TAG, NhPaths.APP_SD_FILES_PATH + "/backups/kalifs-backup.tar.gz").apply();
        prefs.edit().putString(SharePrefTag.CHROOT_DEFAULT_STORE_DOWNLOAD_SHAREPREF_TAG, NhPaths.SD_PATH + "/Download").apply();
    }

    private void changeFragment(FragmentManager fragmentManager, Fragment fragment) {
        if (fragmentManager.isStateSaved()) {
            fragmentManager
                    .beginTransaction()
                    .replace(R.id.container, fragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss();
        } else {
            fragmentManager
                    .beginTransaction()
                    .replace(R.id.container, fragment)
                    .addToBackStack(null)
                    .commit();
        }
    }

    private boolean isAllRequiredPermissionsGranted() {
        if (!permissionCheck.isAllPermitted(PermissionCheck.DEFAULT_PERMISSIONS)) {
            permissionCheck.checkPermissions(PermissionCheck.DEFAULT_PERMISSIONS, PermissionCheck.DEFAULT_PERMISSION_RQCODE);
            return false;
        } else {
            return true;
        }
    }

    public void showWarningDialog(String title, String message, boolean NeedToExit) {
        MaterialAlertDialogBuilder warningAD = new MaterialAlertDialogBuilder(this, R.style.DialogStyleCompat);
        warningAD.setCancelable(false);
        warningAD.setTitle(title);
        warningAD.setMessage(message);
        warningAD.setPositiveButton("CLOSE", (dialog, which) -> {
            dialog.dismiss();
            if (NeedToExit)
                finish();
        });
        warningAD.create().show();
    }

    // Main app broadcastReceiver to response for different actions.
    public class NethunterReceiver extends BroadcastReceiver{
        public static final String CHECKCOMPAT = BuildConfig.APPLICATION_ID + ".CHECKCOMPAT";
        public static final String BACKPRESSED = BuildConfig.APPLICATION_ID + ".BACKPRESSED";
        public static final String CHECKCHROOT = BuildConfig.APPLICATION_ID + ".CHECKCHROOT";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null) {
                switch (intent.getAction()) {
                    case CHECKCOMPAT:
                        showWarningDialog("NetHunter app cannot be run properly",
                            intent.getStringExtra("message"),
                            true);
                        break;
                    case BACKPRESSED:
                        isBackPressEnabled = (intent.getBooleanExtra("isEnable", true));
                        if (isBackPressEnabled) {
                            restoreActionBar();
                        } else {
                            blockActionBar();
                        }
                        break;
                    case CHECKCHROOT:
                        try{
                            if (intent.getBooleanExtra("ENABLEFRAGMENT", false)){
                                navigationView.getMenu().setGroupEnabled(R.id.chrootDependentGroup, true);
                            } else {
                                navigationView.getMenu().setGroupEnabled(R.id.chrootDependentGroup, false);
                                if (lastSelectedMenuItem != null &&
                                    lastSelectedMenuItem.getItemId() != R.id.nethunter_item &&
                                    lastSelectedMenuItem.getItemId() != R.id.createchroot_item) {
                                    FragmentManager fragmentManager = getSupportFragmentManager();
                                    changeFragment(fragmentManager, NetHunterFragment.newInstance(R.id.nethunter_item));
                                }
                            }
                        } catch (Exception e) {
                            if (e.getMessage() != null) {
                                Log.e(AppNavHomeActivity.TAG, e.getMessage());
                            } else {
                                Log.e(AppNavHomeActivity.TAG, "e.getMessage is Null.");
                            }
                        }
                        break;
                }
            }
        }
    }
}
