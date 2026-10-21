package com.offsec.nethunter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.gps.KaliGPSUpdates;
import com.offsec.nethunter.gps.LocationUpdateService;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.core.widget.NestedScrollView;

import java.util.ArrayList;
import java.util.List;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * NOTICE: This code is part of the Kali NetHunter project.
 * It is designed to run on Android devices and provides GPS functionality.
 * It allows users to start and stop GPS providers, view satellite signals,
 * and launch Kismet with the configured settings.
 * <p>
 * This code is distributed under the GPLv3 license.
 * <p>
 * NOTE:
 *   - Added icon to show monitor mode support in wlan interfaces.
 *     TODO: Support switching between Managed, AP and Monitor mode.
 * <p>
 *   - Add support to clear output 'terminal' window.
 *   - Add support to save logs to file.
 *   - Replace the onOptionsMenu with a more modern approach using Toolbar.
 * <p>
 *   - Feature / IDEA; add hcxdumptool support to Kali GPS service, with the NMEA support
 *     it parses GPS data and passively collects WiFi handshakes in high rate.
 * <p>
 *
 *  -- kimocoder at aircrack-ng.org
 *
 */

public class KaliGpsServiceFragment extends Fragment implements KaliGPSUpdates.Receiver {
    private static final String TAG = "KaliGpsServiceFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private CheckBox sdrcheckbox, sdramrcheckbox, sdradsbcheckbox, mousejackcheckbox;
    private ProgressBar gpsSignalStrength;
    private NestedScrollView gpsScrollView;
    private KaliGPSUpdates.Provider gpsProvider = null;
    private TextView gpsTextView;
    private TextInputEditText satellitesEditText;
    private Context context;
    private boolean wantKismet = false;
    private String nexmonPrefix = "";
    private boolean wantHelpView = true;
    private boolean reattachedToRunningService = false;
    private SwitchCompat switch_gps_provider = null;
    private SwitchCompat switch_gpsd = null;
    private final String rtlsdr = "";
    private final String rtlamr = "";
    private final String rtladsb = "";
    private final String mousejack = "";
    SharedPreferences sharedpreferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();


    final ShellExecuter exe = new ShellExecuter();

    public KaliGpsServiceFragment() {
        // Required empty public constructor
    }

    public static KaliGpsServiceFragment newInstance(int sectionNumber) {
        KaliGpsServiceFragment fragment = new KaliGpsServiceFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = getContext();
        sharedpreferences = context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);

        ActivityResultLauncher<String> backgroundLocationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted && context != null) {
                        Toast.makeText(context, "Background location permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        if (context != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireActivity().getApplicationContext(), "Please enable location to \"Allow all the time\", then return to app", Toast.LENGTH_LONG).show();
                backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.gps, container, false);
    }

    private boolean isInternalMonitorModeSupported() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "ls /sys/module/*/parameters/con_mode"});
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void setCheckedQuietly(CompoundButton button, boolean state) {
        button.setTag("quiet");
        button.setChecked(state);
        button.setTag(null);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.gps_menu, menu);
            }

            @Override
            public void onPrepareMenu(@NonNull Menu menu) {
                android.view.MenuItem wifiItem = menu.findItem(R.id.action_wifi_status);
                if (wifiItem != null) {
                    if (isInternalMonitorModeSupported()) {
                        wifiItem.setIcon(R.drawable.ic_wifi_enabled);
                    } else {
                        wifiItem.setIcon(R.drawable.ic_wifi_disabled);
                    }
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_wifi_status) {
                    String msg = isInternalMonitorModeSupported()
                            ? "WIFI: MONITOR MODE: SUPPORTED"
                            : "WIFI: MONITOR MODE: NOT SUPPORTED";
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                    return true;
                } else if (id == R.id.action_info) {
                    showWardrivingDescription();
                    return true;
                } else if (id == R.id.action_settings) {
                    View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.gps_dialog_settings, null, false);
                    AlertDialog dialog = new AlertDialog.Builder(requireContext())
                            .setView(dialogView)
                            .create();

                    Button closeButton = dialogView.findViewById(R.id.dialog_close_button);
                    closeButton.setOnClickListener(v -> dialog.dismiss());

                    dialog.show();
                    return true;
                } else if (id == R.id.action_rtlsdr || id == R.id.action_rtlamr ||
                        id == R.id.action_rtladsb || id == R.id.action_mousejack) {
                    menuItem.setChecked(!menuItem.isChecked());
                    return true;
                } else if (id == R.id.kismet_setup) {
                    RunSetup();
                    return true;
                } else if (id == R.id.kismet_update) {
                    RunUpdate();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        super.onViewCreated(view, savedInstanceState);
        satellitesEditText = view.findViewById(R.id.gps_current_satellites);
        gpsTextView = view.findViewById(R.id.gps_textview);
        gpsTextView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        TextView gpsHelpView = view.findViewById(R.id.gps_help);
        switch_gps_provider = view.findViewById(R.id.switch_gps_provider);
        gpsSignalStrength = view.findViewById(R.id.gps_signal_strength);
        gpsScrollView = view.findViewById(R.id.gps_scroll);
        switch_gpsd = view.findViewById(R.id.switch_gpsd);
        ShellExecuter exe = new ShellExecuter();
        EditText wlan_interface = view.findViewById(R.id.wlan_interface);
        EditText bt_interface = view.findViewById(R.id.bt_interface);
        View watch_padding = view.findViewById(R.id.watch_padding);
        Button button_launch_app = view.findViewById(R.id.gps_button_launch_app);

        //Watch
        Boolean iswatch = sharedpreferences.getBoolean("running_on_wearos", false);
        if (iswatch) {
            watch_padding.setVisibility(View.VISIBLE);
            gpsTextView.setMaxHeight(250);
        }

        // Detect nexmon
        String isnexmon = exe.RunAsRootOutput("strings /vendor/firmware/*bcm* | grep nexmon");
        if (!isnexmon.isEmpty()) {
            Toast.makeText(requireActivity().getApplicationContext(), "Nexmon firmware detected. Make sure monitor mode is enabled.", Toast.LENGTH_LONG).show();
            nexmonPrefix="LD_PRELOAD=/lib/libnexmonkali.so ";
        }

        // First run
        boolean kismetInstalled = isKismetInstalled();
        if (!kismetInstalled) {
            SetupDialog();
        }
        boolean isFirstRun = sharedpreferences.getBoolean("wardriving_first_run", true);
        if (isFirstRun) {
            showWardrivingDescription();
            sharedpreferences.edit().putBoolean("wardriving_first_run", false).apply();
        }

        // Initialize checkboxes
        sdrcheckbox = view.findViewById(R.id.rtlsdr_checkbox);
        sdramrcheckbox = view.findViewById(R.id.rtlamr_checkbox);
        sdradsbcheckbox = view.findViewById(R.id.rtladsb_checkbox);
        mousejackcheckbox = view.findViewById(R.id.mousejack_checkbox);

        button_launch_app.setText(R.string.launch_kismet);
        if (gpsHelpView != null && !wantHelpView) {
            gpsHelpView.setVisibility(View.GONE);
        }
        Log.d(TAG, "reattachedToRunningService: " + reattachedToRunningService);
        if (reattachedToRunningService) {
            if (switch_gps_provider != null) {
                setCheckedQuietly(switch_gps_provider, true);
            }
        }

        check_gpsd();

        if (switch_gps_provider != null) {
            switch_gps_provider.setOnCheckedChangeListener((compoundButton, isChecked) -> {
                if (switch_gps_provider.getTag() != null) return;
                Log.d(TAG, "switch_gps_provider clicked: " + isChecked);
                if (isChecked) {
                    startGpsProvider();
                } else {
                    stopGpsProvider();
                }
            });
        }

        if (switch_gpsd != null) {
            switch_gpsd.setOnCheckedChangeListener((compoundButton, isChecked) -> {
                if (switch_gpsd.getTag() != null) return;
                Log.d(TAG, "switch_gpsd clicked: " + isChecked);
                if (isChecked) {
                    startChrootGpsd();
                } else {
                    stopChrootGpsd();
                }
            });
        }

        button_launch_app.setOnClickListener(view1 -> {
            // NH_PATCH_WD_BTN: toggle Kismet start/stop
            if (button_launch_app.getText().toString()
                    .equals(getString(R.string.launch_kismet))) {
                enableSystemGps();

                if (switch_gps_provider != null && !switch_gps_provider.isChecked()) {
                    gpsTextView.append("Android GPS Provider not running!\n");
                    switch_gps_provider.setChecked(true);
                }
                if (switch_gpsd != null && !switch_gpsd.isChecked()) {
                    gpsTextView.append("chroot gpsd not running!\n");
                    switch_gpsd.setChecked(true);
                }

                String wlaniface = wlan_interface != null ? wlan_interface.getText().toString() : "";
                wlaniface = !wlaniface.isEmpty() ? "source=" + wlaniface + "\n" : "";

                String btiface = bt_interface != null ? bt_interface.getText().toString() : "";
                btiface = !btiface.isEmpty() ? "source=" + btiface + "\n" : "";

                String conf = "log_template=%p/%n\nlog_prefix=/root/captures/\ngps=gpsd:host=localhost,port=2947\n" +
                        wlaniface +
                        btiface +
                        (sdrcheckbox != null && sdrcheckbox.isChecked() ? "source=rtl433-0\n" : "") +
                        (sdramrcheckbox != null && sdramrcheckbox.isChecked() ? "source=rtlamr-0\n" : "") +
                        (sdradsbcheckbox != null && sdradsbcheckbox.isChecked() ? "source=rtladsb-0\n" : "") +
                        (mousejackcheckbox != null && mousejackcheckbox.isChecked() ? "source=mousejack:name=nRF,channel_hoprate=100/sec\n" : "");

                executor.execute(() -> {
                    exe.RunAsRoot(new String[]{"echo \"" + conf + "\" > " + NhPaths.APP_SD_FILES_PATH + "/kismet_site.conf"});
                    exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd mv /sdcard/nh_files/kismet_site.conf /etc/kismet/"});
                });
                Toast.makeText(requireActivity().getApplicationContext(), "Starting terminal.. Web UI will be available in browser at 127.0.0.1:2501\"", Toast.LENGTH_LONG).show();
                wantKismet = true;
                gpsTextView.append("Kismet will launch after next position received.  Waiting...\n");
                gpsTextView.append("*** Web UI will be available in browser at 127.0.0.1:2501 ***\n");

                button_launch_app.setText(R.string.stop_kismet);
            } else {
                stopKismet();
                button_launch_app.setText(R.string.launch_kismet);
            }
        });
    }

    private void startGpsProvider() {
        if (gpsProvider != null) {
            gpsTextView.append("Starting Android GPS Publisher\n");
            gpsTextView.append("GPS NMEA messages will be sent to udp://127.0.0.1:" + NhPaths.GPS_PORT + "\n");
            gpsProvider.onLocationUpdatesRequested(KaliGpsServiceFragment.this);
        }
    }

    private void stopGpsProvider() {
        if (gpsProvider != null) {
            gpsTextView.append("Stopping Android GPS Publisher\n");
            gpsProvider.onStopRequested();
            gpsTextView.post(() -> {
                int scrollAmount = gpsTextView.getLayout().getLineTop(gpsTextView.getLineCount()) - gpsTextView.getHeight();
                gpsTextView.scrollTo(0, Math.max(scrollAmount, 0));
            });
        }
    }

    private void startChrootGpsd() {
        gpsTextView.append("Starting gpsd in Kali chroot\n");
        // do this in a thread because it takes a second or two and lags the UI
        new Thread(() -> {
            ShellExecuter exe = new ShellExecuter();
            String command = NhPaths.APP_SCRIPTS_PATH + File.separator + "bootkali start_gpsd " + NhPaths.GPS_PORT;
            Log.d(TAG, command);
            String response = exe.RunAsRootOutput(command);
            Log.d(TAG, "Response = " + response);
        }).start();
    }

    private void stopChrootGpsd() {
        gpsTextView.append("Stopping gpsd in Kali chroot\n");
        // do this in a thread because it takes a second or two and lags the UI
        new Thread(() -> {
            ShellExecuter exe = new ShellExecuter();
            String command = NhPaths.APP_SCRIPTS_PATH + File.separator + "stop-gpsd";
            Log.d(TAG, command);
            exe.RunAsRootOutput(command);
        }).start();
    }

    private List<Integer> extractSatelliteSnrs(String nmeaSentences) {
        List<Integer> snrs = new ArrayList<>();
        String[] lines = nmeaSentences.split("\n");
        for (String line : lines) {
            if (line.startsWith("$GPGSV") || line.startsWith("$GLGSV")) {
                String[] parts = line.split(",");
                // SNR is at index 7, 11, 15, 19 for each satellite in the sentence
                for (int i = 7; i < parts.length; i += 4) {
                    try {
                        int snr = Integer.parseInt(parts[i]);
                        snrs.add(snr);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return snrs;
    }

    private void updateSatelliteSignalBars(List<Integer> snrs) {
        if (gpsSignalStrength == null) return;
        // gpsSignalStrength.setProgress(snrs.size());
        if (snrs == null || snrs.isEmpty()) {
            gpsSignalStrength.setProgress(0);
            return;
        }
        int avgSnr = 0;
        for (int snr : snrs) {
            avgSnr += snr;
        }
        avgSnr /= snrs.size();
        gpsSignalStrength.setProgress(avgSnr);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        if (LocationUpdateService.isInstanceCreated()) {
            // a LocationUpdateService is already running
            setCheckedQuietly(switch_gps_provider, true);
            // make sure it has a handle to this fragment so it can display updates
            if (this.gpsProvider != null) {
                reattachedToRunningService = this.gpsProvider.onReceiverReattach(this);
            }
        } else {
            setCheckedQuietly(switch_gps_provider, false);
        }

        // check if kismet is already running
        Button button_launch_app = getView().findViewById(R.id.gps_button_launch_app);
        if (isKismetRunning()) button_launch_app.setText(R.string.stop_kismet);

        // check if gpsd is already running
        check_gpsd();
    }

    private void check_gpsd() {
        ShellExecuter exe = new ShellExecuter();
        String command = "ps -ef | grep gpsd";
        Log.d(TAG, "command = " + command);
        String response = exe.RunAsRootOutput(command);
        Log.d(TAG, "response = '" + response + "'");
        setCheckedQuietly(switch_gpsd, response.contains("10110"));
    }

    @Override
    public void onAttach(@NonNull Context context) {
        if (context instanceof KaliGPSUpdates.Provider) {
            this.gpsProvider = (KaliGPSUpdates.Provider) context;
            reattachedToRunningService = this.gpsProvider.onReceiverReattach(this);
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            wantHelpView = false;
        }
        super.onAttach(context);
    }

    @Override
    public void onPositionUpdate(String nmeaSentences) {
        CharSequence charSequence = gpsTextView.getText();
        if (charSequence == null || charSequence.length() == 0) {
            return;
        }
        int maxLines = 20;
        int index = TextUtils.lastIndexOf(charSequence, '\n', charSequence.length() - 1, maxLines);
        if (index > 0) {
            gpsTextView.getEditableText().delete(0, index);
        }

        List<Integer> snrs = extractSatelliteSnrs(nmeaSentences);
        updateSatelliteSignalBars(snrs);

        gpsTextView.append(nmeaSentences + "\n");
        gpsScrollView.post(() -> gpsScrollView.fullScroll(View.FOCUS_DOWN));

        // Extract and display satellite count
        int satelliteCount = extractSatelliteCount(nmeaSentences);
        if (satellitesEditText != null) {
            satellitesEditText.setText(String.valueOf(satelliteCount));
        }

        if (wantKismet) {
            wantKismet = false;
            gpsTextView.append("Launching kismet in NetHunter Terminal\n");
            startKismet(nexmonPrefix);
        }
    }

    // Helper method to extract satellite count from NMEA
    private int extractSatelliteCount(String nmeaSentences) {
        if (nmeaSentences.contains("GPGGA")) {
            String[] parts = nmeaSentences.split(",");
            if (parts.length > 7 && parts[7].matches("\\d+")) {
                return Integer.parseInt(parts[7]);
            }
        }
        return 0;
    }

    @Override
    public void onFirstPositionUpdate() {
        gpsTextView.append("First position received\n");
    }

    private void startKismet(String nexmonPrefix) {
        try {
            run_cmd("echo -ne \"\\033]0;Kismet\\007\" && cmp --silent /sdcard/nh_files/modules/start-kismet /usr/bin/start-kismet || cp /sdcard/nh_files/modules/start-kismet /usr/bin/start-kismet; " +
                    "clear;" + nexmonPrefix + "/usr/bin/start-kismet; exit");
        } catch (Exception e) {
            NhPaths.showMessage(context, getString(R.string.toast_install_terminal));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void run_cmd(String cmd) {
        if (context != null) {
            @SuppressLint("SdCardPath") Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
            context.startActivity(intent);
        }
    }
    // NH_PATCH_WD_GPS: toggle the phone's system location service
    private void enableSystemGps() {
        try {
            ShellExecuter exe = new ShellExecuter();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                exe.RunAsRoot(new String[]{"settings put secure location_mode 3"});
            } else {
                exe.RunAsRoot(new String[]{
                    "settings put secure location_providers_allowed +gps",
                    "settings put secure location_providers_allowed +network"
                });
            }
        } catch (Throwable t) { /* ignore */ }
    }

    private void disableSystemGps() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                exe.RunAsRoot(new String[]{"settings put secure location_mode 0"});
            } else {
                exe.RunAsRoot(new String[]{
                    "settings put secure location_providers_allowed -gps",
                    "settings put secure location_providers_allowed -network"
                });
            }
        } catch (Throwable t) { /* ignore */ }
    }

    // info
    private void showWardrivingDescription() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder.setTitle("Wardriving info");
        builder.setMessage(getString(R.string.gps_description));
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    // First setup
    public void SetupDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder.setTitle("Welcome to Wardriving!");
        builder.setMessage("You have missing packages to run kismet. Install them now?");
        builder.setPositiveButton("Install", (dialog, which) -> {
            RunSetup();
        });
        builder.show();
    }

    public void RunSetup() {
        String cmd = "echo -ne \"\\033]0;Kismet Setup\\007\" && clear && apt update && apt install kismet gpsd python3-venv -y; " +
                "if [ ! -f /root/venv/bin/kismet_analyzer_aplist ];then cd /root && python3 -m venv venv && venv/bin/pip install 'setuptools<82.0.0' 'fastkml<1.0' kismet-analyzer; else echo 'Kismet analyzer is already installed!'; fi; " +
                "echo 'Done! Exiting..' && exit";
        run_cmd(cmd);
    }
    public void RunUpdate() {
        String cmd = "echo -ne \"\\033]0;Kismet Update\\007\" && clear && apt update && apt install kismet gpsd python3-venv -y; " +
                "if [ -f /root/venv/bin/kismet_analyzer_aplist ];then cd /root && venv/bin/pip install -U setuptools kismet-analyzer; else echo 'Kismet analyzer is not installed yet! Please run setup first.'; fi; " +
                "echo 'Done! Exiting..' && exit";
        run_cmd(cmd);
    }

    private boolean isKismetInstalled() {
        try {
            String out = exe.RunAsChrootOutput(
                    "[[ -f /usr/bin/kismet && -f /usr/sbin/gpsd && -f /root/venv/bin/kismet_analyzer_aplist ]] && echo 1 || echo 0");
            String v = (out == null) ? "" : out.trim();
            if ("1".equals(v)) {
                return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    private boolean isKismetRunning() {
        try {
            String out = exe.RunAsChrootOutput(
                    "[[ \"`pidof kismet`\" != \"\" ]] && echo 1 || echo 0");
            String v = (out == null) ? "" : out.trim();
            if ("1".equals(v)) {
                //Log.d(TAG, "isWp3Installed: chroot probe -> true");
                return true;
            }
            // Log.d(TAG, "isWp3Installed: chroot probe -> false (v='" + v + "')");
        } catch (Exception e) {
            //Log.w(TAG, "isWp3Installed: chroot probe failed", e);
        }
        return false;
    }

    // NH_PATCH_WD_STOP
    private void stopKismet() {
        final android.app.Activity _act = getActivity();
        boolean iswatch = sharedpreferences.getBoolean("running_on_wearos", false);
        new Thread(() -> {
            try {
                new ShellExecuter().RunAsRoot(new String[]{"kill -INT `pidof kismet`"});
            } catch (Throwable t) { /* ignore */ }
            if (_act != null) {
                _act.runOnUiThread(() -> {
                    try {
                        if (switch_gps_provider != null && switch_gps_provider.isChecked()) {
                            switch_gps_provider.setChecked(false);
                        }
                    } catch (Throwable t) { /* ignore */ }
                    try {
                        if (switch_gpsd != null && switch_gpsd.isChecked()) {
                            switch_gpsd.setChecked(false);
                        }
                    } catch (Throwable t) { /* ignore */ }
                    try {
                        if (gpsTextView != null) {
                            gpsTextView.append("Kismet stopped.\n");
                            gpsTextView.append("Capture file saved to /sdcard/kismet_captures.\n");
                            if (iswatch) {
                                final MaterialAlertDialogBuilder remove_session_files = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
                                remove_session_files.setMessage("Erase all stored Kismet session files in chroot?");
                                remove_session_files.setPositiveButton("Yes", (dialogInterface, i) -> {
                                    new ShellExecuter().RunAsChrootOutput("while pgrep -x \"kismet\" >/dev/null; do sleep 1; done; wipe -f -i -r /root/captures/Kismet-*");
                                    dialogInterface.cancel();
                                    gpsTextView.append("Kismet session files wiped in chroot.\n");
                                    new ShellExecuter().RunAsRoot(new String[]{"pkill start-kismet"});
                                });
                                remove_session_files.setNegativeButton("No", (dialogInterface, i) -> {
                                    new ShellExecuter().RunAsRoot(new String[]{"pkill start-kismet"});
                                    dialogInterface.cancel();
                                });
                                final AlertDialog alert = remove_session_files.create();
                                alert.show();
                            } else {
                                gpsTextView.append("Return to terminal to finish script.\n");
                            }

                        }
                    } catch (Throwable t) { /* ignore */ }
                });
            }
        }).start();
    }

}
