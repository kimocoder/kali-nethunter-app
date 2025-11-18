package com.offsec.nethunter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;
import com.offsec.nethunter.utils.PermissionCheck;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import android.net.Uri;

import androidx.viewpager2.adapter.FragmentStateAdapter;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import android.media.AudioManager;
import android.os.Environment;
import android.provider.Settings;

public class BTFragment extends Fragment {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private SharedPreferences sharedpreferences;
    private Activity activity;
    private final ShellExecuter exe = new ShellExecuter();
    private static final String ARG_SECTION_NUMBER = "section_number";

    public static BTFragment newInstance(int sectionNumber) {
        BTFragment fragment = new BTFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    private final ActivityResultLauncher<String> btConnectPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(requireActivity().getApplicationContext(), "Bluetooth connect permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireActivity().getApplicationContext(), "Bluetooth connect permission denied", Toast.LENGTH_SHORT).show();
                }
            });

    // Request multiple permissions in one go when needed (Android 12+ BT + storage/media read)
    private final ActivityResultLauncher<String[]> permissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                // Give a compact summary for any critical denials
                boolean btDenied = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Boolean conn = result.get(Manifest.permission.BLUETOOTH_CONNECT);
                    Boolean scan = result.get(Manifest.permission.BLUETOOTH_SCAN);
                    btDenied = (conn != null && !conn) || (scan != null && !scan);
                }
                boolean mediaDenied = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Boolean audio = result.get(Manifest.permission.READ_MEDIA_AUDIO);
                    mediaDenied = (audio != null && !audio);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Boolean read = result.get(Manifest.permission.READ_EXTERNAL_STORAGE);
                    mediaDenied = (read != null && !read);
                }
                if (btDenied) {
                    Toast.makeText(requireContext(), "Bluetooth permissions denied; some features may be limited.", Toast.LENGTH_SHORT).show();
                }
                if (mediaDenied) {
                    Toast.makeText(requireContext(), "Media permissions denied; file browsing/playback may fail.", Toast.LENGTH_SHORT).show();
                }
            });

    private void ensureRuntimePermissions() {
        ArrayList<String> missing = new ArrayList<>();
        // Centralize Bluetooth-related permissions via PermissionCheck.Permissions
        PermissionCheck.Permissions p = new PermissionCheck.Permissions();
        for (String perm : PermissionCheck.Permissions.BLUETOOTH_PERMISSIONS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        // Media/file read for audio/text selections
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (String perm : p.MEDIA_PERMISSIONS) {
                if (perm.equals(Manifest.permission.READ_MEDIA_AUDIO) &&
                        ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(perm);
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!missing.isEmpty()) {
            permissionsLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void ensureAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(requireContext(), "Grant 'All files access' to enable file browsing from this screen.", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(intent);
                }
            } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Ensure required runtime permissions
        ensureRuntimePermissions();
        ensureAllFilesAccessIfNeeded();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            btConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();
        activity = getActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.bt, container, false);
        TabsPagerAdapter tabsPagerAdapter = new TabsPagerAdapter(this);

        ViewPager2 mViewPager = rootView.findViewById(R.id.pagerBt);
        mViewPager.setAdapter(tabsPagerAdapter);
        mViewPager.setOffscreenPageLimit(4);
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                activity.invalidateOptionsMenu();
            }
        });
        TabLayout tabLayout = rootView.findViewById(R.id.tabLayoutBt);
        new TabLayoutMediator(tabLayout, mViewPager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText("Main Page"); break;
                case 1: tab.setText("Tools"); break;
                case 2: tab.setText("Spoof"); break;
                case 3: tab.setText("Carwhisperer"); break;
                case 4: tab.setText("Bad Bluetooth"); break;
                default: tab.setText("");
            }
        }).attach();
        if (activity != null) {
            sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        }
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (this.getClass() == BTFragment.class) {
            MenuHost menuHost = requireActivity();
            menuHost.addMenuProvider(new MenuProvider() {
                @Override
                public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                    menuInflater.inflate(R.menu.bt, menu);
                }

                @Override
                public boolean onMenuItemSelected(@NonNull MenuItem item) {
                    boolean iswatch = sharedpreferences.getBoolean("running_on_wearos", false);
                    int id = item.getItemId();
                    if (id == R.id.setup) {
                        if (iswatch) RunSetupWatch();
                        else RunSetup();
                        return true;
                    } else if (id == R.id.update) {
                        if (iswatch) {
                            Toast.makeText(requireActivity().getApplicationContext(), "Updates have to be done manually through adb shell. If anything gone wrong at first run, please run Setup again.", Toast.LENGTH_LONG).show();
                        } else {
                            RunUpdate();
                        }
                        return true;
                    }
                    return false;
                }
            }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        }
    }

    public void SetupDialog() {
        if (activity != null) {
            sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        }
        Boolean iswatch = sharedpreferences.getBoolean("running_on_wearos", false);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder.setTitle("Welcome to Bluetooth Arsenal!");
        builder.setMessage("This seems to be the first run. Install the Bluetooth tools?");
        builder.setPositiveButton("Install", (dialog, which) -> {
            if (iswatch) RunSetupWatch();
            else RunSetup();
            sharedpreferences.edit().putBoolean("bt_setup_done", true).apply();
        });
        builder.setNegativeButton("Disable message", (dialog, which) -> {
            dialog.dismiss();
            sharedpreferences.edit().putBoolean("bt_setup_done", true).apply();
        });
        builder.show();
    }

    public void SetupDialogWatch() {
        if (activity != null) {
            sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder.setMessage("This seems to be the first run. Install the Bluetooth tools?");
        builder.setPositiveButton("Yes", (dialog, which) -> {
                RunSetupWatch();
                sharedpreferences.edit().putBoolean("bt_setup_done", true).apply();
        });
        builder.setNegativeButton("No", (dialog, which) -> {
                dialog.dismiss();
                sharedpreferences.edit().putBoolean("bt_setup_done", true).apply();
        });
        builder.show();
    }

    public void RunSetupWatch() {
        if (activity != null) {
            sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        }
        run_cmd("echo -ne \"\\033]0;BT Arsenal Setup\\007\" && clear;" +
                "apt update && apt install flex bc bison pkg-config screen bluetooth bluez bluez-tools bluez-obexd libbluetooth3 sox spooftooph libglib2.0*-dev " +
                "libsystemd-dev python3-dbus python3-bluez python3-pyudev python3-evdev libbluetooth-dev redfang bluelog blueranger espeak -y;" +
                "if [ -f /usr/sbin/bluebinder ]; then echo 'Bluebinder is installed!'; else wget https://raw.githubusercontent.com/yesimxev/bluebinder/master/prebuilt/armhf/bluebinder -P /usr/sbin/ && chmod +x /usr/sbin/bluebinder; fi;" +
                "if [ -f /usr/lib/libgbinder.so.1.1.25 ]; then echo 'libgbinder.so.1.1.25 is installed!'; else wget https://raw.githubusercontent.com/yesimxev/libgbinder/master/prebuilt/armhf/libgbinder.so.1.1.25 -P /usr/lib/ && " +
                " ln -s libgbinder.so.1.1.25 /usr/lib/libgbinder.so.1.1 && ln -s /usr/lib/libgbinder.so.1.1 /usr/lib/libgbinder.so.1 && ln -s /usr/lib/libgbinder.so.1 /usr/lib/libgbinder.so; fi;" +
                "if [ -f /usr/lib/libglibutil.so.1.0.67 ]; then echo 'libglibutil.so.1.0.67 is installed!'; else wget https://raw.githubusercontent.com/yesimxev/libglibutil/master/prebuilt/armhf/libglibutil.so.1.0.67 -P /usr/lib/ && " +
                " ln -s libglibutil.so.1.0.67 /usr/lib/libglibutil.so.1.0 && ln -s /usr/lib/libglibutil.so.1.0 /usr/lib/libglibutil.so.1 && ln -s /usr/lib/libglibutil.so.1 /usr/lib/libglibutil.so; fi;" +
                "if [ -f /usr/bin/carwhisperer ]; then echo 'carwhisperer is installed!'; else wget https://raw.githubusercontent.com/yesimxev/carwhisperer-0.2/master/prebuilt/armhf/carwhisperer -P /usr/bin/ && chmod +x /usr/bin/carwhisperer; fi;" +
                "if [ -f /usr/bin/rfcomm_scan ]; then echo 'rfcomm_scan is installed!'; else wget https://raw.githubusercontent.com/yesimxev/bt_audit/master/prebuilt/armhf/rfcomm_scan -P /usr/bin/ && chmod +x /usr/bin/rfcomm_scan; fi;" +
                "if [ -d /root/carwhisperer ]; then echo '/root/carwhisperer is installed!'; else git clone https://github.com/yesimxev/carwhisperer-0.2 /root/carwhisperer; fi;" +
                "if [ -f /root/badbt/btk_server.py ]; then echo 'BadBT is installed!'; else git clone https://github.com/yesimxev/badbt /root/badbt && cp /root/badbt/org.thanhle.btkbservice.conf /etc/dbus-1/system.d/; fi;" +
                "if [ -f /etc/init.d/bluetooth ] && grep -q 'noplugin=input' /etc/init.d/bluetooth 2>/dev/null; then echo 'Bluetooth service is patched!'; else echo 'Patching Bluetooth service..' && " +
                "sed -i -e 's/# \\?NOPLUGIN_OPTION=.*/NOPLUGIN_OPTION=\"--noplugin=input\"/g' /etc/init.d/bluetooth; fi;" +
                "echo 'Everything is installed!';");
        sharedpreferences.edit().putBoolean("bt_setup_done", true).apply();
    }

    public void RunSetup() {
        if (activity != null) {
            sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        }
        String cmd = "echo -ne \"\\033]0;BT Arsenal Setup\\007\" && clear;" +
                "apt update && apt install flex bc bison pkg-config screen bluetooth bluez bluez-tools bluez-obexd libbluetooth3 sox spooftooph libglib2.0*-dev " +
                "libsystemd-dev python3-dbus python3-bluez python3-pyudev python3-evdev libbluetooth-dev redfang bluelog blueranger -y;" +
                "if [ -f /usr/bin/carwhisperer ] && [ -f /usr/bin/rfcomm_scan ]; then echo 'All scripts are installed!'; else " +
                "git clone https://github.com/yesimxev/carwhisperer-0.2 /root/carwhisperer;" +
                " cd /root/carwhisperer; make && make install; git clone https://github.com/yesimxev/bt_audit /root/bt_audit; cd /root/bt_audit/src; make;" +
                " cp rfcomm_scan /usr/bin/; fi;" +
                "if [ -f /usr/lib/libglibutil.so ]; then echo 'libglibutil is installed!'; else git clone https://github.com/kimocoder/libglibutil /root/libglibutil;" +
                " cd /root/libglibutil; make && make install-dev; fi;" +
                "if [ -f /usr/lib/libgbinder.so ]; then echo 'libgbinder is installed!'; else git clone https://github.com/kimocoder/libgbinder /root/libgbinder;" +
                " cd /root/libgbinder; make && make install-dev; fi;" +
                "if [ -f /usr/sbin/bluebinder ]; then echo 'bluebinder is installed!'; else git clone https://github.com/kimocoder/bluebinder /root/bluebinder;" +
                " cd /root/bluebinder; make && make install; fi;" +
                "if [ -f /root/badbt/btk_server.py ]; then echo 'BadBT is installed!'; else git clone https://github.com/yesimxev/badbt /root/badbt && cp /root/badbt/org.thanhle.btkbservice.conf /etc/dbus-1/system.d/; fi;" +
                "if [ -f /etc/init.d/bluetooth ] && grep -q 'noplugin=input' /etc/init.d/bluetooth 2>/dev/null; then echo 'Bluetooth service is patched!'; else echo 'Patching Bluetooth service..' && " +
                "sed -i -e 's/.*NOPLUGIN_OPTION=\"\"/NOPLUGIN_OPTION=\"--noplugin=input\"/g' /etc/init.d/bluetooth; fi;" +
                "echo 'Everything is installed!'";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("bt_setup_done", true).apply();
    }

    public void RunUpdate() {
        if (activity != null) {
            sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        }
        String cmd = "echo -ne \"\\033]0;BT Arsenal Update\\007\" && clear;" +
                "apt update && apt install screen bluetooth bluez bluez-tools bluez-obexd libbluetooth3 sox spooftooph " +
                "libbluetooth-dev redfang bluelog blueranger libglib2.0*-dev libsystemd-dev python3-dbus python3-bluez python3-pyudev python3-evdev -y;" +
                "if [ -f /usr/bin/carwhisperer ] && [ -f /usr/bin/rfcomm_scan ] && [ -d /root/bluebinder ] && [ -d /root/libgbinder ] && [ -d /root/libglibutil ]; then " +
                " cd /root/carwhisperer/; git pull && make && make install; cd /root/bluebinder/; git pull && make && make install; cd /root/libgbinder/; git pull && make && " +
                " make install-dev; cd /root/libglibutil/; git pull && make && make install-dev; cd /root/bt_audit; git pull; cd src && make; " +
                " cp rfcomm_scan /usr/bin/; cd /root/badbt/; git pull; fi;" +
                "echo 'Done!';";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("bt_setup_done", true).apply();
    }

    public static class TabsPagerAdapter extends FragmentStateAdapter {
        TabsPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:
                    return new MainFragment();
                case 1:
                    return new ToolsFragment();
                case 2:
                    return new SpoofFragment();
                case 3:
                    return new CWFragment();
                default:
                    return new BadBtFragment();
            }
        }

        @Override
        public int getItemCount() {
            return 5;
        }
    }

    public static class MainFragment extends BTFragment {
        private Context context;
        final ShellExecuter exe = new ShellExecuter();
        private String selected_iface;
        String selected_addr;
        String selected_class;
        String selected_name;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
        }

        @Override
        public void onResume(){
            super.onResume();
            Toast.makeText(requireActivity().getApplicationContext(), "Status updated", Toast.LENGTH_SHORT).show();
            // Capture view safely on UI thread, then refresh off the UI thread
            final View root = getView();
            if (root != null) {
                EXEC.execute(() -> refresh(root));
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {

            View rootView = inflater.inflate(R.layout.bt_main, container, false);
            SharedPreferences sharedpreferences = context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);

            // Detecting watch
            final TextView BTMainDesc = rootView.findViewById(R.id.bt_maindesc);
            final TextView BTIface = rootView.findViewById(R.id.bt_if);
            final TextView BTService = rootView.findViewById(R.id.bt_service);

            Boolean iswatch = sharedpreferences.getBoolean("running_on_wearos", false);
            if (iswatch) {
                BTMainDesc.setVisibility(View.GONE);
                BTIface.setText(R.string.bt_interface);
                BTService.setText(R.string.bt_service);
            }

            // First run
            Boolean setupdone = sharedpreferences.getBoolean("bt_setup_done", false);
            if (!setupdone.equals(true)) {
                if (iswatch) SetupDialogWatch();
                else SetupDialog();
            }

            final Spinner ifaces = rootView.findViewById(R.id.hci_interface);

            // Bluebinder or bt_smd
            final TextView Binder = rootView.findViewById(R.id.bluebinder);
            File bt_smd = new File("/sys/module/hci_smd/parameters/hcismd_set");
            if (bt_smd.exists()) {
                Binder.setText(R.string.bt_smd);
            }

            // Bluetooth interfaces
            EXEC.execute(() -> {
                String outputHCI = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig | grep hci | cut -d: -f1");
                ArrayList<String> hciIfaces = new ArrayList<>();
                if (outputHCI.isEmpty()) {
                    hciIfaces.add("None");
                } else {
                    String[] ifacesArray = outputHCI.split("\n");
                    hciIfaces.addAll(Arrays.asList(ifacesArray));
                }
                if (isAdded()) requireActivity().runOnUiThread(() -> ifaces.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, hciIfaces)));
            });

            ifaces.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                    selected_iface = parentView.getItemAtPosition(pos).toString();
                    sharedpreferences.edit().putInt("selected_iface", ifaces.getSelectedItemPosition()).apply();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                    // TODO document why this method is empty
                }
            });

            // Refresh Status
            ImageButton RefreshStatus = rootView.findViewById(R.id.refreshStatus);
            RefreshStatus.setOnClickListener(v -> refresh(rootView));
            // Initial refresh
            refresh(rootView);

            // Internal bluetooth support
            final Button bluebinderButton = rootView.findViewById(R.id.bluebinder_button);
            final Button dbusButton = rootView.findViewById(R.id.dbus_button);
            final Button btButton = rootView.findViewById(R.id.bt_button);
            final Button hciButton = rootView.findViewById(R.id.hci_button);
            File hwbinder = new File("/dev/hwbinder");
            File vhci = new File("/dev/vhci");

            bluebinderButton.setOnClickListener( v -> {
                if (bluebinderButton.getText().equals("Start")) {
                    if (!bt_smd.exists() && !hwbinder.exists() && !vhci.exists()) {
                        final MaterialAlertDialogBuilder confirmbuilder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
                        confirmbuilder.setTitle("Internal bluetooth support disabled");
                        confirmbuilder.setMessage("Your device does not support hwbinder, vhci, or bt_smd. Make sure your kernel config has the recommended drivers enabled in order to use internal bluetooth.");
                        confirmbuilder.setPositiveButton("Sure", (dialogInterface, i) -> {
                            bluebinderButton.setEnabled(false);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                bluebinderButton.setTextColor(getResources().getColor(R.color.translucent_white, requireContext().getTheme()));
                            } else {
                                bluebinderButton.setTextColor(Color.WHITE);
                            }
                            dialogInterface.cancel();
                        });
                        confirmbuilder.setNegativeButton("Try anyway", (dialogInterface, i) -> dialogInterface.cancel());
                        final AlertDialog alert = confirmbuilder.create();
                        alert.show();
                    } else {
                        if (bt_smd.exists()) {
                            exe.RunAsRoot(new String[]{"svc bluetooth disable"});
                            exe.RunAsRoot(new String[]{"echo 0 > " + bt_smd});
                            exe.RunAsRoot(new String[]{"echo 1 > " + bt_smd});
                            exe.RunAsRoot(new String[]{"svc bluetooth enable"});
                        }
                        else {
                            File bluebinder = new File(NhPaths.CHROOT_PATH() + "/usr/sbin/bluebinder");
                            if (bluebinder.exists()) {
                                //Ensure all services are disabled before enabling airplane mode for bluebinder
                                exe.RunAsRoot(new String[]{
                                        //"svc bluetooth disable",
                                        //"svc wifi disable",
                                        "settings put global bluetooth_on 0",
                                        // 10 = STATE_OFF | 12 = STATE_TURNING_OFF
                                        "am broadcast -a android.bluetooth.adapter.action.STATE_CHANGED --ei android.bluetooth.adapter.extra.STATE 10 --ei android.bluetooth.adapter.extra.PREVIOUS_STATE 12",
                                        "settings put global airplane_mode_on 1",
                                        "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
                                        //"pm disable com.android.bluetooth"
                                });

                                // Run the Bluebinder script
                                run_cmd("echo -ne \"\\033]0;Bluebinder\\007\" && clear;bluebinder || bluebinder;exit");
                                Toast.makeText(requireActivity().getApplicationContext(), "Starting bluebinder...", Toast.LENGTH_SHORT).show();

                                // Delay to disable airplane mode and re-enable Wi-Fi after 9 seconds
                                new Handler(Looper.getMainLooper()).postDelayed(() -> exe.RunAsRoot(new String[]{
                                        "settings put global bluetooth_on 1",
                                        // 12 = STATE_ON | 10 = STATE_TURNING_ON
                                        "am broadcast -a android.bluetooth.adapter.action.STATE_CHANGED --ei android.bluetooth.adapter.extra.STATE 12 --ei android.bluetooth.adapter.extra.PREVIOUS_STATE 10",
                                        "settings put global airplane_mode_on 0",
                                        "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
                                }), 9000); // 9000 milliseconds delay*/
                            } else {
                                Toast.makeText(requireActivity().getApplicationContext(), "Bluebinder is not installed. Launching setup..", Toast.LENGTH_SHORT).show();
                                RunSetup();
                            }
                        }
                        refresh(rootView);
                    }
                } else if (bluebinderButton.getText().equals("Stop")) {
                    if (bt_smd.exists()) {
                        exe.RunAsRoot(new String[]{"echo 0 > " + bt_smd});
                    }
                    else {
                        exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd pkill bluebinder;exit"});
                        exe.RunAsRoot(new String[]{"pm enable com.android.bluetooth"});
                        exe.RunAsRoot(new String[]{"svc bluetooth enable"});
                    }
                    refresh(rootView);
                }
            });

            // Services
            dbusButton.setOnClickListener( v -> {
                if (dbusButton.getText().equals("Start")) {
                    exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service dbus start"});
                    refresh(rootView);
                } else if (dbusButton.getText().equals("Stop")) {
                    exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service dbus stop"});
                    refresh(rootView);
                }
            });

            btButton.setOnClickListener( v -> EXEC.execute(() -> {
                String dbus_statusCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service dbus status | grep dbus");
                if ("dbus is running.".equals(dbus_statusCMD)) {
                    boolean start = "Start".contentEquals(btButton.getText());
                    exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service bluetooth " + (start ? "start" : "stop")});
                    if (isAdded()) requireActivity().runOnUiThread(() -> refresh(rootView));
                } else {
                    if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity().getApplicationContext(), "Enable dbus service first!", Toast.LENGTH_SHORT).show());
                }
            }));

            hciButton.setOnClickListener( v -> {
                if (selected_iface == null || selected_iface.equals("None")) {
                    Toast.makeText(requireActivity().getApplicationContext(), "No interface, please refresh or check connections!", Toast.LENGTH_SHORT).show();
                    return;
                }
                EXEC.execute(() -> {
                    // Verify iface exists before issuing hciconfig
                    String present = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig | grep '^" + selected_iface + ":'");
                    if (present.isEmpty()) {
                        if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity().getApplicationContext(), "Selected interface not present", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    if ("Start".contentEquals(hciButton.getText())) {
                        exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig " + selected_iface + " up noscan"});
                    } else {
                        exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig " + selected_iface + " down"});
                    }
                    if (isAdded()) requireActivity().runOnUiThread(() -> refresh(rootView));
                });
            });

            // Scanning
            Button StartScanButton = rootView.findViewById(R.id.start_scan);
            final TextView BTtime = rootView.findViewById(R.id.bt_time);
            ListView targets = rootView.findViewById(R.id.targets);
            ShellExecuter exe = new ShellExecuter();
            File ScanLog = new File(NhPaths.CHROOT_PATH() + "/root/blue.log");
            StartScanButton.setOnClickListener( v -> {
                if (selected_iface == null || selected_iface.equals("None")) {
                    Toast.makeText(requireActivity().getApplicationContext(), "No interface selected!", Toast.LENGTH_SHORT).show();
                    return;
                }
                EXEC.execute(() -> {
                    // Verify iface exists before scanning
                    String present = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig | grep '^" + selected_iface + ":'");
                    if (present.isEmpty()) {
                        if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity().getApplicationContext(), "Selected interface not present", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    String hci_current = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig "+ selected_iface + " | grep 'UP RUNNING' | cut -f2 -d$'\t'");
                    if ("UP RUNNING ".equals(hci_current)) {
                        if (isAdded()) requireActivity().runOnUiThread(() -> {
                            final ArrayList<String> scanning = new ArrayList<>();
                            scanning.add("Scanning..");
                            targets.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, scanning));
                        });
                        exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd rm /root/blue.log"});
                        exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd timeout " + BTtime.getText().toString() + " bluelog -i " + selected_iface + " -ncqo /root/blue.log;hciconfig " + selected_iface + " noscan"});
                        String outputScanLog = exe.RunAsRootOutput("cat " + ScanLog);
                        if (isAdded()) requireActivity().runOnUiThread(() -> {
                            if (!outputScanLog.isEmpty()) {
                                final String[] targetsArray = outputScanLog.split("\n");
                                targets.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, targetsArray));
                            } else {
                                final ArrayList<String> notargets = new ArrayList<>();
                                notargets.add("No devices found");
                                targets.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, notargets));
                            }
                        });
                    } else {
                        if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity().getApplicationContext(), "Interface is down!", Toast.LENGTH_SHORT).show());
                    }
                });
            });

            // Target selection
            targets.setOnItemClickListener((adapterView, view, i, l) -> {
                String selected_target = targets.getItemAtPosition(i).toString();
                if (selected_target.equals("No devices found"))
                    Toast.makeText(requireActivity().getApplicationContext(), "No target!", Toast.LENGTH_SHORT).show();
                else {
                    selected_addr = exe.RunAsRootOutput("echo " + selected_target + " | cut -d , -f 1");
                    selected_class = exe.RunAsRootOutput("echo " + selected_target + " | cut -d , -f 2");
                    selected_name = exe.RunAsRootOutput("echo " + selected_target + " | cut -d , -f 3");
                    PreferencesData.saveString(context, "selected_address", selected_addr);
                    PreferencesData.saveString(context, "selected_class", selected_class);
                    PreferencesData.saveString(context, "selected_name", selected_name);
                    Toast.makeText(requireActivity().getApplicationContext(), "Target selected!", Toast.LENGTH_SHORT).show();
                }
            });
            return rootView;
        }

        // Refresh main
        private void refresh(View BTFragment) {
            final TextView Binderstatus = BTFragment.findViewById(R.id.BinderStatus);
            final TextView DBUSstatus = BTFragment.findViewById(R.id.DBUSstatus);
            final TextView BTstatus = BTFragment.findViewById(R.id.BTstatus);
            final TextView HCIstatus = BTFragment.findViewById(R.id.HCIstatus);
            final Button bluebinderButton = BTFragment.findViewById(R.id.bluebinder_button);
            final Button dbusButton = BTFragment.findViewById(R.id.dbus_button);
            final Button btButton = BTFragment.findViewById(R.id.bt_button);
            final Button hciButton = BTFragment.findViewById(R.id.hci_button);
            final Spinner ifaces = BTFragment.findViewById(R.id.hci_interface);
            SharedPreferences sharedpreferences = context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);

            EXEC.execute(() -> {
                String outputHCI = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig | grep hci | cut -d: -f1");
                String[] ifacesArray = outputHCI.isEmpty() ? new String[]{"None"} : outputHCI.split("\n");
                int lastiface = sharedpreferences.getInt("selected_iface", 0);

                String binder_statusCMD = exe.RunAsRootOutput("pidof bluebinder");
                File bt_smd = new File("/sys/module/hci_smd/parameters/hcismd_set");

                String dbus_statusCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service dbus status | grep dbus");
                String bt_statusCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service bluetooth status | grep bluetooth");

                // Only query hciconfig for selected iface if it exists in list
                String computedHciStatus = "";
                if (selected_iface != null && !selected_iface.equals("None") && outputHCI.contains(selected_iface)) {
                    computedHciStatus = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig "+ selected_iface + " | grep 'UP RUNNING' | cut -f2 -d$'\t'");
                }
                final String hci_statusCMD = computedHciStatus;

                if (isAdded()) requireActivity().runOnUiThread(() -> {
                    ifaces.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, ifacesArray));
                    if (lastiface < ifacesArray.length) ifaces.setSelection(lastiface);

                    if (!bt_smd.exists()) {
                        if (binder_statusCMD.isEmpty()) {
                            Binderstatus.setText(R.string.bt_stopped);
                            bluebinderButton.setText(R.string.bt_start);
                        } else {
                            Binderstatus.setText(R.string.bt_running);
                            bluebinderButton.setText(R.string.bt_stop);
                        }
                    } else {
                        if (outputHCI.contains("hci0")) {
                            Binderstatus.setText(R.string.bt_enabled);
                            bluebinderButton.setText(R.string.bt_stop);
                        } else {
                            Binderstatus.setText(R.string.bt_disabled);
                            bluebinderButton.setText(R.string.bt_start);
                        }
                    }

                    if (dbus_statusCMD.equals("dbus is running.")) {
                        DBUSstatus.setText(R.string.bt_start);
                        dbusButton.setText(R.string.bt_stop);
                    } else {
                        DBUSstatus.setText(R.string.bt_stopped);
                        dbusButton.setText(R.string.bt_start);
                    }

                    if (bt_statusCMD.equals("bluetooth is running.")) {
                        BTstatus.setText(R.string.bt_running);
                        btButton.setText(R.string.bt_stop);
                    } else {
                        BTstatus.setText(R.string.bt_stopped);
                        btButton.setText(R.string.bt_start);
                    }

                    if ("UP RUNNING ".equals(hci_statusCMD)) {
                        HCIstatus.setText(R.string.bt_up);
                        hciButton.setText(R.string.bt_stop);
                    } else {
                        HCIstatus.setText(R.string.bt_down);
                        hciButton.setText(R.string.bt_start);
                    }
                });
            });
        }
    }

    public static class ToolsFragment extends BTFragment {
        private Context context;
        final ShellExecuter exe = new ShellExecuter();
        private String reverse = "";
        private String flood = "";

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.bt_tools, container, false);
            final EditText hci_interface = rootView.findViewById(R.id.hci_interface);
            CheckBox floodCheckBox = rootView.findViewById(R.id.l2ping_flood);
            CheckBox reverseCheckBox = rootView.findViewById(R.id.l2ping_reverse);

            // Target address
            final EditText sdp_address = rootView.findViewById(R.id.sdp_address);

            // Set target
            Button SetTarget = rootView.findViewById(R.id.set_target);

            SetTarget.setOnClickListener( v -> {
                String selected_addr = PreferencesData.getString(context, "selected_address", "");
                sdp_address.setText(selected_addr);
            });

            // L2ping
            Button StartL2ping = rootView.findViewById(R.id.start_l2ping);
            final EditText l2ping_Size = rootView.findViewById(R.id.l2ping_size);
            final EditText l2ping_Count = rootView.findViewById(R.id.l2ping_count);
            final EditText redfang_Range = rootView.findViewById(R.id.redfang_range);
            final EditText redfang_Log = rootView.findViewById(R.id.redfang_log);

            // Checkbox for flood and reverse ping
            floodCheckBox.setOnClickListener( v -> {
                if (floodCheckBox.isChecked())
                    flood = " -f ";
                else
                    flood = "";
            });
            reverseCheckBox.setOnClickListener( v -> {
                if (reverseCheckBox.isChecked())
                    reverse = " -r ";
                else
                    reverse = "";
            });

            StartL2ping.setOnClickListener( v -> {
                String l2ping_target = sdp_address.getText().toString();
                if (!l2ping_target.isEmpty()) {
                    String l2ping_size = l2ping_Size.getText().toString();
                    String l2ping_count = l2ping_Count.getText().toString();
                    String l2ping_interface = hci_interface.getText().toString();
                    run_cmd("echo -ne \"\\033]0;Pinging BT device\\007\" && clear;l2ping -i " + l2ping_interface + " -s " + l2ping_size + " -c " + l2ping_count + flood + reverse + " " + l2ping_target + " && echo \"\nPinging done.");
                } else {
                    Toast.makeText(requireActivity().getApplicationContext(), "No target address!", Toast.LENGTH_SHORT).show();
                }
            });

            // RFComm_scan
            Button StartRFCommscan = rootView.findViewById(R.id.start_rfcommscan);

            StartRFCommscan.setOnClickListener( v -> {
                String sdp_target = sdp_address.getText().toString();
                if (!sdp_target.isEmpty())
                    run_cmd("echo -ne \"\\033]0;RFComm Scan\\007\" && clear;rfcomm_scan " + sdp_target);
                else
                    Toast.makeText(requireActivity().getApplicationContext(), "No target address!", Toast.LENGTH_SHORT).show();
            });

            // Redfang
            Button StartRedfang = rootView.findViewById(R.id.start_redfang);

            StartRedfang.setOnClickListener( v -> {
                String redfang_range = redfang_Range.getText().toString();
                String redfang_logfile = redfang_Log.getText().toString();
                if (!redfang_range.isEmpty())
                    run_cmd("echo -ne \"\\033]0;Redfang\\007\" && clear;fang -r " + redfang_range + " -o " + redfang_logfile);
                else
                    Toast.makeText(requireActivity().getApplicationContext(), "No target range!", Toast.LENGTH_SHORT).show();
            });

            // Blueranger
            Button StartBlueranger = rootView.findViewById(R.id.start_blueranger);
            StartBlueranger.setOnClickListener( v -> {
                String blueranger_target = sdp_address.getText().toString();
                String blueranger_interface = hci_interface.getText().toString();
                if (!blueranger_target.isEmpty())
                    run_cmd("echo -ne \"\\033]0;Blueranger\\007\" && clear;blueranger " + blueranger_interface + " " + blueranger_target);
                else
                    Toast.makeText(requireActivity().getApplicationContext(), "No target address!", Toast.LENGTH_SHORT).show();
            });

            // Start SDP Tool
            Button StartSDPButton = rootView.findViewById(R.id.start_sdp);
            StartSDPButton.setOnClickListener( v -> {
                Toast.makeText(getContext(), "Discovery started..\nCheck the output below", Toast.LENGTH_SHORT).show();
                Executors.newSingleThreadExecutor().execute(() -> startSDPtool(rootView));
            });
            return rootView;
        }

        private void startSDPtool(View BTFragment) {
            final EditText sdp_address = BTFragment.findViewById(R.id.sdp_address);
            final EditText hci_interface = BTFragment.findViewById(R.id.hci_interface);
            final TextView output = BTFragment.findViewById(R.id.SDPoutput);
            ShellExecuter exe = new ShellExecuter();
            String sdp_target = sdp_address.getText().toString();
            String sdp_interface = hci_interface.getText().toString();

            EXEC.execute(() -> {
                if (!sdp_target.isEmpty()) {
                    String CMDout = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd sdptool -i " + sdp_interface + " browse " + sdp_target + " | sed '/^\\[/d' | sed '/^Linux/d'");
                    if (isAdded()) requireActivity().runOnUiThread(() -> output.setText(CMDout));
                } else {
                    if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity().getApplicationContext(), "No target address!", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    public static class SpoofFragment extends BTFragment {
        private Context context;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.bt_spoof, container, false);

            // Selected iface
            final EditText spoof_interface = rootView.findViewById(R.id.spoof_interface);

            // Target address
            final EditText targetAddress = rootView.findViewById(R.id.targetAddress);

            // Target Class
            final EditText targetClass = rootView.findViewById(R.id.targetClass);

            // Target Name
            final EditText targetName = rootView.findViewById(R.id.targetName);

            // Set target
            Button SetTarget = rootView.findViewById(R.id.set_target);

            SetTarget.setOnClickListener(v -> {
                String selected_address = PreferencesData.getString(context, "selected_address", "");
                String selected_class = PreferencesData.getString(context, "selected_class", "");
                String selected_name = PreferencesData.getString(context, "selected_name", "");
                targetAddress.setText(selected_address);
                targetClass.setText(selected_class);
                targetName.setText(selected_name);
            });

            // Refresh
            Button RefreshStatus = rootView.findViewById(R.id.refreshSpoof);
            RefreshStatus.setOnClickListener(v -> refreshSpoof(rootView));

            // Apply
            Button ApplySpoof = rootView.findViewById(R.id.apply_spoof);

            ApplySpoof.setOnClickListener(v -> {
                String target_interface = spoof_interface.getText().toString();
                String target_address = " -a " + targetAddress.getText().toString();
                String target_class = " -c " + targetClass.getText().toString();
                String target_name = " -n \"" + targetName.getText().toString() + "\"";
                if (target_class.equals(" -c ")) target_class = "";
                if (target_name.equals(" -n \"\"")) target_name = "";
                if (target_address.equals(" -a ") && target_name.isEmpty() && target_class.isEmpty()) {
                    Toast.makeText(requireActivity().getApplicationContext(), "Please enter at least one parameter!", Toast.LENGTH_SHORT).show();
                } else {
                    final String target_classname = target_class + target_name;
                    if (!target_address.equals(" -a ")) {
                        run_cmd("echo -ne \"\\033]0;Spoofing Bluetooth\\007\" && clear;echo 'Spooftooph started..';spooftooph -i " + target_interface + target_address +
                                "; sleep 2 && hciconfig " + target_interface + " up && spooftooph -i " + target_interface + target_classname + " && echo '\nBringing interface up with hciconfig..\n\nClass/Name changed.");
                    } else {
                        run_cmd("echo -ne \"\\033]0;Spoofing Bluetooth\\007\" && clear;echo 'Spooftooph started..';spooftooph -i " + target_interface + target_classname + " && echo '\nClass/Name changed.");
                    }
                }
            });
            return rootView;
        }

        private void refreshSpoof(View BTFragment) {
            ShellExecuter exe = new ShellExecuter();
            final EditText spoof_interface = BTFragment.findViewById(R.id.spoof_interface);
            final TextView currentAddress = BTFragment.findViewById(R.id.currentAddress);
            final TextView currentClass = BTFragment.findViewById(R.id.currentClass);
            final TextView currentClassType = BTFragment.findViewById(R.id.currentClassType);
            final TextView currentName = BTFragment.findViewById(R.id.currentName);

            String selectedIface = spoof_interface.getText().toString().trim();
            if (selectedIface.isEmpty()) {
                Toast.makeText(requireActivity().getApplicationContext(), "No interface set!", Toast.LENGTH_SHORT).show();
                return;
            }
            EXEC.execute(() -> {
                // Ensure iface exists
                String present = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig | grep '^" + selectedIface + ":'");
                if (present.isEmpty()) {
                    if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity().getApplicationContext(), "Selected interface not present", Toast.LENGTH_SHORT).show());
                    return;
                }
                String currentAddress_CMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig " + selectedIface + " | awk '/Address/ { print $3 }'");
                if (!currentAddress_CMD.isEmpty()) {
                    String currentClassCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig " + selectedIface + " -a | awk '/Class:/ { print $2 }' | sed '/^Class:/d'");
                    String currentClassTypeCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig " + selectedIface + " -a | awk '/Device Class:/ { print $3, $4, $5 }'");
                    String currentNameCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig " + selectedIface + " -a | grep Name | cut -d\\' -f2");
                    if (isAdded()) requireActivity().runOnUiThread(() -> {
                        currentAddress.setText(currentAddress_CMD);
                        currentClass.setText(currentClassCMD);
                        currentClassType.setText(currentClassTypeCMD);
                        currentName.setText(currentNameCMD);
                    });
                } else {
                    if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireActivity().getApplicationContext(), "Interface is down!", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    public static class CWFragment extends BTFragment {
        private Context context;
        private String selected_mode;
        final ShellExecuter exe = new ShellExecuter();
        private static final String TTS_DIRECTORY = NhPaths.SD_PATH + "/nh_files/CarWhisperer/TTS";
        private static final String TTS_TEMP_FILE = "/root/tts_output.wav";

        // Register activity result launcher for picking audio files
        private final ActivityResultLauncher<Intent> audioPickerLauncher =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && getView() != null) {
                        Intent data = result.getData();
                        Uri uri = data.getData();
                        if (uri != null) {
                            String filePath = uri.getPath();
                            if (filePath != null) {
                                filePath = filePath.replace("/document/primary:", NhPaths.SD_PATH);
                                EditText injectfilename = getView().findViewById(R.id.injectfilename);
                                if (injectfilename != null) injectfilename.setText(filePath);
                            }
                        }
                    }
                });

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.bt_carwhisperer, container, false);
            // TTS Fields
            final Spinner ttsDropdown = rootView.findViewById(R.id.tts_message_dropdown);
            Spinner ttsVoiceSpinner = rootView.findViewById(R.id.tts_voice_spinner);
            Spinner ttsSpeedSpinner = rootView.findViewById(R.id.tts_speed_spinner);
            final EditText ttsInput = rootView.findViewById(R.id.tts_input);
            final Button ttsGenerate = rootView.findViewById(R.id.generate_tts);

            // Hide the TTS input field by default
            ttsInput.setVisibility(View.GONE);

            // Create TTS output directory
            new File(TTS_DIRECTORY).mkdirs();

            // Selected iface
            final EditText cw_interface = rootView.findViewById(R.id.hci_interface);

            // Target address
            final EditText cw_address = rootView.findViewById(R.id.hci_address);

            // Set target
            Button SetTarget = rootView.findViewById(R.id.set_target);
            SetTarget.setOnClickListener( v -> {
                String selected_address = PreferencesData.getString(context, "selected_address", "");
                cw_address.setText(selected_address);
            });

            // Channel
            final EditText hci_channel = rootView.findViewById(R.id.hci_channel);

            // CW Mode
            Spinner cwmode = rootView.findViewById(R.id.cwmode);
            final ArrayList<String> modes = new ArrayList<>();
            modes.add("Listen");
            modes.add("Inject");
            cwmode.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, modes));
            cwmode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                    selected_mode = parentView.getItemAtPosition(pos).toString();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            // Listening
            final EditText listenfilename = rootView.findViewById(R.id.listenfilename);

            // Injecting
            final EditText injectfilename = rootView.findViewById(R.id.injectfilename);
            final Button injectfilebrowse = rootView.findViewById(R.id.injectfilebrowse);
            injectfilebrowse.setOnClickListener( v -> {
                Intent intent = new Intent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("audio/*");
                intent.setAction(Intent.ACTION_GET_CONTENT);
                audioPickerLauncher.launch(Intent.createChooser(intent, "Select audio file"));
            });

            // Populate TTS dropdown
            String[] ttsOptions = getResources().getStringArray(R.array.tts_phrases);
            ArrayAdapter<String> ttsAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, ttsOptions);
            ttsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            ttsDropdown.setAdapter(ttsAdapter);

            // Handle TTS selection
            ttsDropdown.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    String selected = ttsOptions[position];
                    if ("Custom TTS message".equals(selected)) {
                        ttsInput.setVisibility(View.VISIBLE);
                    } else {
                        ttsInput.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    ttsInput.setVisibility(View.GONE);
                }
            });

            // Text-to-Speech Generation
            ttsGenerate.setOnClickListener(v -> {
                String selectedPhrase = ttsDropdown.getSelectedItem().toString();
                String finalInput;

                if (selectedPhrase != null && selectedPhrase.toLowerCase(Locale.ROOT).startsWith("custom")) {
                    finalInput = ttsInput.getText().toString().trim();
                    if (finalInput.isEmpty()) {
                        Toast.makeText(requireActivity().getApplicationContext(), "Enter text to convert", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else {
                    finalInput = selectedPhrase;
                }

                if (selectedPhrase.equals("Choose Message")){
                    Toast.makeText(requireContext(), "Please select a TTS Message", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Get selected voice and speed
                String selectedVoice = ttsVoiceSpinner.getSelectedItem().toString();
                String selectedSpeed = ttsSpeedSpinner.getSelectedItem().toString();

                if (selectedVoice.equals("Voice model") || selectedSpeed.equals("Voice speed")) {
                    Toast.makeText(requireContext(), "Please select both voice model and speed", Toast.LENGTH_SHORT).show();
                    return;
                }

                String outTmp = "/root/tts_output.wav";
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String outFinal = TTS_DIRECTORY + "/tts_" + timestamp + ".wav";

                String escapedInput = finalInput.replace("\"", "\\\"");

                String ttsCmd = "espeak -v " + selectedVoice + " -s " + selectedSpeed + " \"" + escapedInput + "\" -w " + outTmp + " && " +
                        "cp " + outTmp + " " + outFinal + " && chmod 777 " + outFinal + " && sleep 1 && exit";

                run_cmd(ttsCmd);
                Toast.makeText(requireActivity().getApplicationContext(), "TTS audio generated at: " + outFinal, Toast.LENGTH_LONG).show();
                injectfilename.setText(outFinal);
            });

            // Launch
            Button StartCWButton = rootView.findViewById(R.id.start_cw);
            StartCWButton.setOnClickListener( v -> {
                String cw_iface = cw_interface.getText().toString();
                String cw_target = cw_address.getText().toString();
                if (!cw_target.isEmpty()) {
                    String cw_channel = hci_channel.getText().toString();
                    String cw_listenfile = listenfilename.getText().toString();
                    String cw_injectfile = injectfilename.getText().toString();

                    if (selected_mode.equals("Listen")) {
                        run_cmd("echo -ne \"\\033]0;Listening BT audio\\007\" && clear;echo 'Carwhisperer starting..\nReturn to NetHunter to kill, or to listen live!'$'\n';carwhisperer " + cw_iface + " /root/carwhisperer/in.raw /sdcard/rec.raw " + cw_target + " " + cw_channel +
                                " && echo 'Converting to wav to target directory..';sox -t raw -r 8000 -e signed -b 16 /sdcard/rec.raw -r 8000 -b 16 /sdcard/" + cw_listenfile + ";echo Done! || echo 'No convert file!';sleep 3 && exit");
                    } else if (selected_mode.equals("Inject")) {
                        run_cmd("echo -ne \"\\033]0;Injecting BT audio\\007\" && clear;echo 'Carwhisperer starting..';length=$(($(soxi -D '" + cw_injectfile + "' | cut -d. -f1)+8));sox '" + cw_injectfile + "' -r 8000 -b 16 -c 1 tempi.raw && timeout $length " +
                                "carwhisperer " + cw_iface + " tempi.raw tempo.raw " + cw_target + " " + cw_channel + "; rm tempi.raw && rm tempo.raw;echo '\nInjection done.");
                    }
                } else
                    Toast.makeText(requireActivity().getApplicationContext(), "No target address!", Toast.LENGTH_SHORT).show();
            });

            // Stream or play audio
            ImageButton PlayAudioButton = rootView.findViewById(R.id.play_audio);
            ImageButton StopAudioButton = rootView.findViewById(R.id.stop_audio);
            int sampleRate = 22000;
            int minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat af = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            AudioTrack audioTrack;
            audioTrack = new AudioTrack(
                    attrs,
                    af,
                    Math.max(minBuffer, 20000),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            PlayAudioButton.setOnClickListener(v -> {
                String selectedPath = injectfilename.getText().toString().trim();
                File cw_listenfile;
                if (!selectedPath.isEmpty() && new File(selectedPath).exists()) {
                    cw_listenfile = new File(selectedPath);
                } else {
                    cw_listenfile = new File(NhPaths.SD_PATH + "/nh_files/CarWhisperer/rec.raw");
                }

                if (!cw_listenfile.exists() || cw_listenfile.length() == 0) {
                    Toast.makeText(getContext(), "File not found!", Toast.LENGTH_SHORT).show();
                } else {
                    Executors.newSingleThreadExecutor().execute(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            try (InputStream s = Files.newInputStream(cw_listenfile.toPath())) {
                                audioTrack.play();
                                byte[] data = new byte[200];
                                int n;
                                while ((n = s.read(data)) != -1) {
                                    synchronized (audioTrack) {
                                        audioTrack.write(data, 0, n);
                                    }
                                }
                            } catch (IOException e) {
                                Log.e("BTFragment", "Error playing audio (O+)", e);
                             } finally {
                                 audioTrack.release();
                             }
                         } else {
                             try (InputStream s = new FileInputStream(cw_listenfile)) {
                                 audioTrack.play();
                                 byte[] data = new byte[200];
                                 int n;
                                 while ((n = s.read(data)) != -1) {
                                     synchronized (audioTrack) {
                                         audioTrack.write(data, 0, n);
                                     }
                                 }
                            } catch (IOException e) {
                                Log.e("BTFragment", "Error playing audio (pre-O)", e);
                             } finally {
                                 audioTrack.release();
                             }
                         }
                     });
                 }
             });
            StopAudioButton.setOnClickListener(v -> {
                audioTrack.pause();
                        audioTrack.flush();
            });

            return rootView;
        }
    }

    public static class BadBtFragment extends BTFragment {
        private Context context;
        private String selected_badbtmode;
        private String selected_preset;
        private String selected_preset_uac;
        private String selected_prefix;
        private String selected_badbt_class;
        String prefixCMD = "";
        String uacCMD = "";
        final ShellExecuter exe = new ShellExecuter();

        // Register activity result launcher for picking text files
        private final ActivityResultLauncher<Intent> textPickerLauncher =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && getView() != null) {
                        Intent data = result.getData();
                        Uri uri = data.getData();
                        if (uri != null) {
                            String filePath = uri.getPath();
                            if (filePath != null) {
                                filePath = filePath.replace("/document/primary:", NhPaths.SD_PATH);
                                // Read file content via existing shell approach
                                String fileContent = exe.RunAsRootOutput("cat " + filePath);
                                EditText badbtstring = getView().findViewById(R.id.editBadBT);
                                if (badbtstring != null) badbtstring.setText(fileContent);
                            }
                        }
                    }
                });

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
        }

        @Override
        public void onResume(){
            super.onResume();
            Toast.makeText(requireActivity().getApplicationContext(), "Status updated", Toast.LENGTH_SHORT).show();
            // Safely capture current view before dispatching to background
            final View root = getView();
            if (root != null) {
                Executors.newSingleThreadExecutor().execute(() -> refresh_badbt(root));
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.bt_badbt, container, false);
            final Button badbtServerButton = rootView.findViewById(R.id.badbtserver_button);
            SharedPreferences sharedpreferences = context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
            boolean iswatch = requireContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);

            // Watch optimisation
            final TextView BadBTdesc = rootView.findViewById(R.id.badbt_desc);
            if (iswatch) {
                BadBTdesc.setVisibility(View.GONE);
            }

            // Selected iface, name, bdaddr, class
            final EditText badbt_interface = rootView.findViewById(R.id.badbt_interface);
            final EditText badbt_name = rootView.findViewById(R.id.badbt_name);
            final EditText badbt_bdaddr = rootView.findViewById(R.id.badbt_address);
            final EditText badbt_class = rootView.findViewById(R.id.badbt_class);

            // Class spinner
            Spinner badbtclass = rootView.findViewById(R.id.badbt_class_spinner);
            final ArrayList<String> classes = new ArrayList<>();
            classes.add("Keyboard");
            classes.add("Headset");
            classes.add("Speaker");
            classes.add("Mouse");
            classes.add("Printer");
            classes.add("PC");
            classes.add("Mobile");
            classes.add("Custom");
            badbtclass.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, classes));
            badbtclass.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                    selected_prefix = parentView.getItemAtPosition(pos).toString();
                    switch (selected_prefix) {
                        case "Keyboard":
                            badbt_class.setText("0x000540");
                            break;
                        case "Headset":
                            badbt_class.setText("0x000408");
                            break;
                        case "Speaker":
                            badbt_class.setText("0x240414");
                            break;
                        case "Mouse":
                            badbt_class.setText("0x002580");
                            break;
                        case "Printer":
                            badbt_class.setText("0x040680");
                            break;
                        case "PC":
                            badbt_class.setText("0x02010c");
                            break;
                        case "Mobile":
                            badbt_class.setText("0x000204");
                            break;
                        case "Custom":
                            badbt_class.setText("");
                            break;
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            // Refresh
            refresh_badbt(rootView);
            String prevbadbtname = sharedpreferences.getString("badbt-name", "");
            if (!prevbadbtname.isEmpty()) badbt_name.setText(prevbadbtname);
            String prevbadbtiface = sharedpreferences.getString("badbt-iface", "");
            if (!prevbadbtiface.isEmpty()) badbt_interface.setText(prevbadbtiface);
            String prevbadbtaddr = sharedpreferences.getString("badbt-bdaddr", "");
            if (!prevbadbtaddr.isEmpty()) badbt_bdaddr.setText(prevbadbtaddr);
            String prevbadbtclass = sharedpreferences.getString("badbt-class", "");
            if (!prevbadbtclass.isEmpty()) badbt_class.setText(prevbadbtclass);

            // Refresh Status
            ImageButton RefreshBadBTStatus = rootView.findViewById(R.id.refreshBadBTStatus);
            RefreshBadBTStatus.setOnClickListener(v -> refresh_badbt(rootView));

            // String
            final EditText badbt_string = rootView.findViewById(R.id.editBadBT);

            // Services
            badbtServerButton.setOnClickListener( v -> {
                if (badbtServerButton.getText().equals("Start")) {
                    String BadBT_name = badbt_name.getText().toString();
                    String BadBT_iface = badbt_interface.getText().toString();
                    String BadBT_bdaddr = badbt_bdaddr.getText().toString();
                    String BadBT_class = badbt_class.getText().toString();
                    String dbus_statusCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service dbus status | grep dbus");
                    String bt_statusCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service bluetooth status | grep bluetooth");
                    String bt_ifaceCMD = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd hciconfig | grep hci");
                    sharedpreferences.edit().putString("badbt-name", BadBT_name).apply();
                    sharedpreferences.edit().putString("badbt-iface", BadBT_iface).apply();
                    sharedpreferences.edit().putString("badbt-bdaddr", BadBT_bdaddr).apply();
                    sharedpreferences.edit().putString("badbt-class", BadBT_class).apply();

                    if (dbus_statusCMD.equals("dbus is running.") && bt_statusCMD.equals("bluetooth is running.") && !bt_ifaceCMD.isEmpty()) {
                        if (!BadBT_name.isEmpty() && !BadBT_iface.isEmpty() && !BadBT_bdaddr.isEmpty()) {
                            Toast.makeText(requireActivity().getApplicationContext(), "Starting server...", Toast.LENGTH_SHORT).show();
                            run_cmd("echo -ne \"\\033]0;BadBT Server\\007\" && clear;python3 /root/badbt/btk_server.py -n '"
                                    + BadBT_name + "' -i " + BadBT_iface + " -c " + BadBT_class + " -a " + BadBT_bdaddr + "&;sleep 1 && echo 'Starting agent...' && sleep 1 && bluetoothctl --agent NoInputNoOutput && exit");
                            refresh_badbt(rootView);
                        } else {
                            Toast.makeText(requireActivity().getApplicationContext(), "Please enter interface, keyboard name, and address!", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(requireActivity().getApplicationContext(), "Bluetooth interface or service not running!", Toast.LENGTH_LONG).show();
                    }
                } else if (badbtServerButton.getText().equals("Stop")) {
                    exe.RunAsRoot(new String[]{"kill `ps -ef | grep '[btk]_server' | awk {'print $2'}`"});
                    exe.RunAsRoot(new String[]{"pkill bluetoothctl"});
                    refresh_badbt(rootView);
                }
            });

            // Mode
            Spinner badbtmode = rootView.findViewById(R.id.badbtmode);
            View BadBTSettingsView = rootView.findViewById(R.id.badbtsettings_layout);
            final ArrayList<String> modes = new ArrayList<>();
            modes.add("Send strings");
            modes.add("Interactive");
            badbtmode.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, modes));
            badbtmode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                    selected_badbtmode = parentView.getItemAtPosition(pos).toString();
                    if (selected_badbtmode.equals("Interactive")) {
                        BadBTSettingsView.setVisibility(View.GONE);
                    } else if (selected_badbtmode.equals("Send strings")){
                        BadBTSettingsView.setVisibility(View.VISIBLE);
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            // Prefix
            CheckBox uacCheckBox = rootView.findViewById(R.id.uac_bypass);
            View BadBTUACView = rootView.findViewById(R.id.badbtuac_layout);
            Spinner badbtprefix = rootView.findViewById(R.id.badbtprefix);
            Spinner badbtpresets_uac = rootView.findViewById(R.id.badbtpresets_uac);
            final ArrayList<String> presets_uac = new ArrayList<>();
            final ArrayList<String> prefixes = new ArrayList<>();
            prefixes.add("Mobile Home");
            prefixes.add("Mobile Browser");
            prefixes.add("Windows CMD");
            prefixes.add("Mac Terminal");
            prefixes.add("Linux Terminal");
            prefixes.add("None");
            badbtprefix.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, prefixes));
            badbtprefix.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                    selected_prefix = parentView.getItemAtPosition(pos).toString();
                    switch (selected_prefix) {
                        case "Mobile Home":
                            BadBTUACView.setVisibility(View.GONE);
                            prefixCMD = "mobile";
                            uacCheckBox.setChecked(false);
                            presets_uac.clear();
                            presets_uac.add("None");
                            badbtpresets_uac.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, presets_uac));
                            uacCMD = "-";
                            break;
                        case "Mobile Browser":
                            BadBTUACView.setVisibility(View.GONE);
                            prefixCMD = "mobilewww";
                            uacCheckBox.setChecked(false);
                            presets_uac.clear();
                            presets_uac.add("None");
                            badbtpresets_uac.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, presets_uac));
                            uacCMD = "-";
                            break;
                        case "Windows CMD":
                            BadBTUACView.setVisibility(View.VISIBLE);
                            prefixCMD = "windows";
                            break;
                        case "Mac Terminal":
                            BadBTUACView.setVisibility(View.GONE);
                            prefixCMD = "mac";
                            uacCheckBox.setChecked(false);
                            presets_uac.clear();
                            presets_uac.add("None");
                            badbtpresets_uac.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, presets_uac));
                            uacCMD = "-";
                            break;
                        case "Linux Terminal":
                            BadBTUACView.setVisibility(View.GONE);
                            prefixCMD = "linux";
                            uacCheckBox.setChecked(false);
                            presets_uac.clear();
                            presets_uac.add("None");
                            badbtpresets_uac.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, presets_uac));
                            uacCMD = "-";
                            break;
                        case "None":
                            BadBTUACView.setVisibility(View.GONE);
                            uacCMD = "-";
                            uacCheckBox.setChecked(false);
                            presets_uac.clear();
                            presets_uac.add("None");
                            badbtpresets_uac.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, presets_uac));
                            uacCMD = "-";
                            break;
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            // Presets
            Spinner badbtpresets = rootView.findViewById(R.id.badbtpresets);
            // Removed duplicate lookup: use existing badbt_string instead of re-finding editBadBT
            final ArrayList<String> presets = new ArrayList<>();
            presets.add("Rickroll");
            presets.add("Fake Windows Update");
            presets.add("None");
            badbtpresets.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, presets));
            badbtpresets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                    selected_preset = parentView.getItemAtPosition(pos).toString();
                    switch (selected_preset) {
                        case "Rickroll":
                            badbt_string.setText(R.string.bt_badbt_string_rickroll);
                            break;
                        case "Fake Windows Update":
                            badbt_string.setText(R.string.bt_badbt_string_fakeupdate);
                            break;
                        case "None":
                            badbt_string.setText("");
                            break;
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            // UAC
            uacCheckBox.setOnClickListener( v -> {
                if (uacCheckBox.isChecked()) {
                    badbtpresets_uac.setVisibility(View.VISIBLE);
                    presets_uac.clear();
                    presets_uac.add("Windows 7");
                    presets_uac.add("Windows 8");
                    presets_uac.add("Windows 10");
                    presets_uac.add("Windows 11");
                    badbtpresets_uac.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, presets_uac));
                }
                else {
                    presets_uac.clear();
                    presets_uac.add("None");
                    badbtpresets_uac.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, presets_uac));
                    badbtpresets_uac.setVisibility(View.GONE);
                    uacCMD = "-";
                }
            });
            badbtpresets_uac.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                    selected_preset_uac = parentView.getItemAtPosition(pos).toString();
                    switch (selected_preset_uac) {
                        case "Windows 7":
                            uacCMD = "win7";
                            break;
                        case "Windows 8":
                            uacCMD = "win8";
                            break;
                        case "Windows 10":
                            uacCMD = "win10";
                            break;
                        case "Windows 11":
                            uacCMD = "win11";
                            break;
                        case "None":
                            uacCMD = "-";
                            break;
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            // Load from file
            final Button injectStringButton = rootView.findViewById(R.id.injectstringbrowse);
            injectStringButton.setOnClickListener( v -> {
                Intent intent2 = new Intent();
                intent2.addCategory(Intent.CATEGORY_OPENABLE);
                intent2.setType("text/*");
                intent2.setAction(Intent.ACTION_GET_CONTENT);
                textPickerLauncher.launch(Intent.createChooser(intent2, "Select text file"));
            });

            // Start
            Button StartBadBtButton = rootView.findViewById(R.id.start_badbt);
            StartBadBtButton.setOnClickListener( v -> {
                    if (selected_badbtmode.equals("Send strings")) {
                        String BadBT_string = badbt_string.getText().toString();
                        run_cmd("echo -ne \"\\033]0;BadBT Send Strings\\007\" && clear;python3 /root/badbt/send_string.py '" + BadBT_string + "' " + prefixCMD + " " + uacCMD + ";sleep 2 && echo 'Exiting..' && exit");
                        Toast.makeText(requireActivity().getApplicationContext(), "Sending strings..", Toast.LENGTH_SHORT).show();
                        } else if (selected_badbtmode.equals("Interactive")) {
                        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
                        builder.setTitle("Are you sure?");
                        builder.setMessage("Interactive mode will run in NetHunter terminal, but needs a physical keyboard connected as of now.");
                        builder.setPositiveButton("Ok", (dialog, which) -> {
                            run_cmd("echo -ne \"\\033]0;BadBT Client\\007\" && clear;python3 /root/badbt/kb_client.py");
                            Toast.makeText(requireActivity().getApplicationContext(), "Starting keyboard client..", Toast.LENGTH_SHORT).show();
                        });
                        builder.setNegativeButton("Cancel", (dialog, which) -> {
                        });
                        builder.show();
                    }
        });

            return rootView;
        }

        // Refresh badbt
        private void refresh_badbt(View BTFragment) {

            final TextView BadBTServerStatus = BTFragment.findViewById(R.id.BadBTServerStatus);
            final Button badbtserverButton = BTFragment.findViewById(R.id.badbtserver_button);
            context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);

            EXEC.execute(() -> {
                String badbtserver_statusCMD = exe.RunAsRootOutput("ps -ef | grep btk_server");
                boolean running = badbtserver_statusCMD.contains("btk_server.py");
                if (isAdded()) requireActivity().runOnUiThread(() -> {
                    if (!running) {
                        BadBTServerStatus.setText(R.string.bt_stopped);
                        badbtserverButton.setText(R.string.bt_start);
                    } else {
                        BadBTServerStatus.setText(R.string.bt_running);
                        badbtserverButton.setText(R.string.bt_stop);
                    }
                });
            });
        }
    }

    public static class PreferencesData {
        public static void saveString(Context context, String key, String value) {
            if (context != null) {
                SharedPreferences sharedPrefs = context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
                sharedPrefs.edit().putString(key, value).apply();
            }
        }

        public static String getString(Context context, String key, String defaultValue) {
            if (context != null) {
                SharedPreferences sharedPrefs = context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
                return sharedPrefs.getString(key, defaultValue);
            }
            return defaultValue;
        }
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd(String cmd) {
        @SuppressLint("SdCardPath") Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
    }

    // Helper: open TerminalFragment with an initial command; if not possible, fallback to legacy bridge
    private void run_cmd_inapp(@NonNull String cmd) {
        Activity act = getActivity();
        try {
            if (act instanceof androidx.appcompat.app.AppCompatActivity) {
                androidx.appcompat.app.AppCompatActivity app = (androidx.appcompat.app.AppCompatActivity) act;
                TerminalFragment tf = TerminalFragment.newInstanceWithCommand(R.id.terminal_item, cmd);
                app.getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.container, tf)
                        .addToBackStack(null)
                        .commitAllowingStateLoss();
                return;
            }
        } catch (Throwable t) {
            // Ignore and fallback
        }
        run_cmd(cmd);
    }
}
