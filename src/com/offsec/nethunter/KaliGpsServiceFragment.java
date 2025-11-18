package com.offsec.nethunter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.gps.KaliGPSUpdates;
import com.offsec.nethunter.gps.LocationUpdateService;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.PermissionCheck;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.File;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private CheckBox sdrcheckbox, sdramrcheckbox, sdradsbcheckbox, mousejackcheckbox;
    private ProgressBar gpsSignalStrength;
    private NestedScrollView gpsScrollView;
    private KaliGPSUpdates.Provider gpsProvider = null;
    private TextView gpsTextView;
    private TextInputEditText satellitesEditText;
    private Context context;
    private boolean wantKismet = false;
    private boolean wantHelpView = true;
    private boolean reattachedToRunningService = false;
    private SwitchCompat switch_gps_provider = null;
    private SwitchCompat switch_gpsd = null;
    private final String rtlsdr = "";
    private final String rtlamr = "";
    private final String rtladsb = "";
    private final String mousejack = "";

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

        ActivityResultLauncher<String> backgroundLocationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted && context != null) {
                        Toast.makeText(context, "Background location permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use PermissionCheck group to determine if background location is part of the missing set
            PermissionCheck.Permissions perms = new PermissionCheck.Permissions();
            boolean hasAllLocation = PermissionCheck.hasPermissions(context, perms.LOCATION_PERMISSIONS);
            if (!hasAllLocation && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
                    View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.gps_info_dialog, null, false);
                    AlertDialog dialog = new AlertDialog.Builder(requireContext())
                            .setView(dialogView)
                            .create();

                    Button closeButton = dialogView.findViewById(R.id.gps_dialog_close_button);
                    closeButton.setOnClickListener(v -> dialog.dismiss());

                    dialog.show();
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
        Button button_launch_app = view.findViewById(R.id.gps_button_launch_app);
        ShellExecuter exe = new ShellExecuter();
        EditText wlan_interface = view.findViewById(R.id.wlan_interface);
        EditText bt_interface = view.findViewById(R.id.bt_interface);

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
            if (switch_gps_provider != null && !switch_gps_provider.isChecked()) {
                gpsTextView.append("Android GPS Provider not running!\n");
                switch_gps_provider.setChecked(true);
                startGpsProvider();
            }
            if (switch_gpsd != null && !switch_gpsd.isChecked()) {
                gpsTextView.append("chroot gpsd not running!\n");
                switch_gpsd.setChecked(true);
                startChrootGpsd();
            }

            String wlaniface = wlan_interface != null ? wlan_interface.getText().toString() : "";
            wlaniface = !wlaniface.isEmpty() ? "source=" + wlaniface + "\n" : "";

            String btiface = bt_interface != null ? bt_interface.getText().toString() : "";
            btiface = !btiface.isEmpty() ? "source=" + btiface + "\n" : "";

            String conf = "log_template=%p/%n\nlog_prefix=/captures/kismet/\ngps=gpsd:host=localhost,port=2947\n" +
                    wlaniface +
                    btiface +
                    (sdrcheckbox != null && sdrcheckbox.isChecked() ? "source=rtl433-0\n" : "") +
                    (sdramrcheckbox != null && sdramrcheckbox.isChecked() ? "source=rtlamr-0\n" : "") +
                    (sdradsbcheckbox != null && sdradsbcheckbox.isChecked() ? "source=rtladsb-0\n" : "") +
                    (mousejackcheckbox != null && mousejackcheckbox.isChecked() ? "source=mousejack:name=nRF,channel_hoprate=100/sec\n" : "");

            executor.execute(() -> {
                exe.RunAsRoot(new String[]{"echo \"" + conf + "\" > " + NhPaths.SD_PATH + "/kismet_site.conf"});
                exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd mv /sdcard/kismet_site.conf /etc/kismet/"});
            });
            Toast.makeText(requireActivity().getApplicationContext(), "Starting Kismet.. Web UI will be available at localhost:2501\"", Toast.LENGTH_LONG).show();
            wantKismet = true;
            gpsTextView.append("Kismet will launch after next position received.  Waiting...\n");
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
            String command = "su -c '" + NhPaths.APP_SCRIPTS_PATH + File.separator + "bootkali start_gpsd " + NhPaths.GPS_PORT + "'";
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
            String command = "su -c '" + NhPaths.APP_SCRIPTS_PATH + File.separator + "stop-gpsd'";
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

        // check if gpsd is already running
        check_gpsd();
    }

    private void check_gpsd() {
        ShellExecuter exe = new ShellExecuter();
        String command = "pgrep gpsd";
        Log.d(TAG, "command = " + command);
        String response = exe.RunAsRootOutput(command);
        Log.d(TAG, "response = '" + response + "'");
        setCheckedQuietly(switch_gpsd, !response.isEmpty());
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
            startKismet();
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

    private void startKismet() {
        try {
            run_cmd("/usr/bin/start-kismet");
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
}
