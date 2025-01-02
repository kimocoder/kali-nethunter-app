package com.offsec.nethunter;

//
// THIS UI AND CODE WAS PROVIDED BY KIMOCODER WITH INSPIRATION FROM YESIMXEV
// CREDITS: WIFITE WAS ORIGINALLY WRITTEN BY DERV82 (HTTPS://GITHUB.COM/DERV82/WIFITE)
// FEEL FREE TO USE IT AND MODIFY IT AS YOU WISH, PARTICIPATE IN THE DEVELOPMENT OF THE TOOL
//
// TODO:
// 1. Remove sort from SettingsDialogFragment and clean out the code
// 2. Change to use "iw" command to scan for networks on all wireless devices (call from app binaries)
//    - Use "iw" command to scan for networks on "wlan1" if available
//    - Use default WiFiManager to scan for networks on "wlan0" if "iw" is not available
// 3. Add USB connection check and prompt user to connect USB if not connected
//    - Add an icon on the toolbar to indicate USB connection status
// 4. When basic functions are done and tested, change the UI to a more
//    console friendly than ListView (e.g. RecyclerView with custom adapter).
//    using % in ListView is not console friendly and makes the console vulnerable to exceptions.
// 5. Implement the "Attack" menu item to launch Wifite, call ShellExecuter to run the commands and display the output in a new fragment (if possible).
//    we would need to add callbacks to the MainActivity to handle the output and display it in a new fragment, with a good console UI.
//    - Add a new fragment to display the output of the Wifite commands
//
// But callbacks and stdin/stdout are not supported in the current version of the app, but is planned for future versions.
// So, we can implement the UI and the basic functions for now and wait for the next version to implement the callbacks and stdin/stdout.
//
// ACTIVE DEVELOPMENT IS ONGOING ON THE NETHUNTER APP, FEEL FREE TO CONTRIBUTE TO THE PROJECT:
//
// Suggestions for WifiteFragment
//        If WifiteFragment is also using AsyncTask, it should be refactored similarly to use Executors. Here is a general approach:
//        Replace AsyncTask with ExecutorService.
//        Use executorService.execute() to run tasks in the background.
//        Use activity.runOnUiThread() to update the UI from the background thread.
//        This approach ensures better performance and maintainability.
//


import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.Spinner;
import android.Manifest;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.viewmodel.CreationExtras;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.offsec.nethunter.utils.ShellExecuter;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Collections;
import java.util.Objects;

public class WifiteScannerFragment extends Fragment implements WifiteSettingFragment.SettingsDialogListener {
    public static final String TAG = "WifiScannerFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ListView wifiNetworksList;
    private final ArrayList<String> arrayList = new ArrayList<>();
    private Context context;
    public Activity activity;
    private final ShellExecuter exe = new ShellExecuter();
    private Boolean iswatch;
    private WifiManager wifiManager;
    private Runnable scanRunnable;
    private int refreshInterval = 10000; // Default to 10 seconds
    private final Handler handler = new Handler();
    private int selectedPosition = -1; // Variable to hold the selected position

    public static WifiteScannerFragment newInstance(int sectionNumber) {
        WifiteScannerFragment fragment = new WifiteScannerFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.context = context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        this.context = null;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (context != null) {
            wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        SwipeRefreshLayout swipeRefreshLayout;
        View rootView = inflater.inflate(R.layout.wifite_ui_scanner, container, false);

        // Initialize Toolbar
        Toolbar toolbar = rootView.findViewById(R.id.toolbar);
        if (toolbar != null) {
            ((AppCompatActivity) requireActivity()).setSupportActionBar(toolbar);
            Objects.requireNonNull(((AppCompatActivity) requireActivity()).getSupportActionBar()).setTitle("WiFi Scanner");
            setHasOptionsMenu(true);
        } else {
            Log.e(TAG, "Toolbar is null");
        }
        setHasOptionsMenu(true);

        // Initialize WifiManager
        wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        // Initialize SwipeRefreshLayout
        swipeRefreshLayout = rootView.findViewById(R.id.swipe_refresh_layout);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            scanWifi(true, "all");
            swipeRefreshLayout.setRefreshing(false);
        });

        // Initialize Bottom Navigation View
        BottomNavigationView bottomNavigationView = rootView.findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            switch (item.getItemId()) {
                case 2131297092:
                    // Handle home action
                    return true;
                case 2131297090:
                    // Handle dashboard action
                    return true;
                case 2131297093:
                    // Handle notifications action
                    return true;
                default:
                    return false;
            }
        });

        // Example: Start scanning
        //startScanning();
        context = getContext();
        activity = getActivity();

        // Disable the "Attack" menu item initially
        bottomNavigationView.getMenu().findItem(R.id.navigation_dashboard).setEnabled(false);

        // Detecting watch
        SharedPreferences sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        iswatch = sharedpreferences.getBoolean("running_on_wearos", false);

        // WIFI Scanner
        Spinner wlanInterface = rootView.findViewById(R.id.wlan_interface);
        Spinner wifiChannel = rootView.findViewById(R.id.wifi_channel);
        wifiNetworksList = rootView.findViewById(R.id.wifi_networks_list);
        ArrayAdapter<String> wifiAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, arrayList);
        wifiNetworksList.setAdapter(wifiAdapter);

        Spinner refreshIntervalSpinner = rootView.findViewById(R.id.refresh_interval);
        List<String> refreshOptions = Arrays.asList("Refresh OFF", "8 seconds", "10 seconds", "15 seconds", "20 seconds");
        ArrayAdapter<String> refreshAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, refreshOptions);
        refreshAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        refreshIntervalSpinner.setAdapter(refreshAdapter);
        refreshIntervalSpinner.setSelection(2); // Set default to '10 seconds'

        refreshIntervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        refreshInterval = 0;
                        break;
                    case 1:
                        refreshInterval = 8000;
                        break;
                    case 2:
                        refreshInterval = 10000;
                        break;
                    case 3:
                        refreshInterval = 15000;
                        break;
                    case 4:
                        refreshInterval = 20000;
                        break;
                    default:
                        refreshInterval = 10000; // Default to 10 seconds
                        break;
                }
                scheduleScan();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        // Populate Spinner with available WiFi adapters
        List<String> wifiAdapters = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback() && networkInterface.getName().startsWith("wlan")) {
                    wifiAdapters.add(networkInterface.getName());
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Error getting network interfaces", e);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, wifiAdapters);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wlanInterface.setAdapter(adapter);

        // Populate Spinner with available WiFi channels
        List<String> wifiChannels = Arrays.asList("All Channels", "2.4 ghz channels", "5 ghz channels", "6 ghz channels");

        ArrayAdapter<String> channelAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, wifiChannels) {
            @Override
            public boolean isEnabled(int position) {
                // Disable the "6 ghz channels" option for now
                return position != 3;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView tv = (TextView) view;
                if (position == 3) {
                    // Set the text color to gray for the disabled item
                    tv.setTextColor(Color.GRAY);
                } else {
                    tv.setTextColor(Color.WHITE);
                }
                return view;
            }
        };
        channelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wifiChannel.setAdapter(channelAdapter);

        wifiChannel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        // Handle "All Channels"
                        scanWifi(true, "all");
                        break;
                    case 1:
                        // Handle "2.4 ghz channels"
                        scanWifi(true, "2.4");
                        break;
                    case 2:
                        // Handle "5 ghz channels"
                        scanWifi(true, "5");
                        break;
                    case 3:
                        // todo: Handle "6 ghz channels" by adding a device check rather.
                        // Handle "6 ghz channels" (disabled for now)
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        // Set OnItemClickListener to hold selection
        wifiNetworksList.setOnItemClickListener((parent, view, position, id) -> {
            if (selectedPosition == position) {
                // Deselect the item
                selectedPosition = -1;
                view.setBackgroundColor(Color.TRANSPARENT);
                bottomNavigationView.getMenu().findItem(R.id.navigation_dashboard).setEnabled(false);
            } else {
                // Select the new item
                selectedPosition = position;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    parent.getChildAt(i).setBackgroundColor(Color.TRANSPARENT); // Reset background color for all items
                }
                view.setBackgroundColor(Color.WHITE); // Set background color for selected item
                bottomNavigationView.getMenu().findItem(R.id.navigation_dashboard).setEnabled(true);
            }

            // Stop scanning for WiFi
            if (scanRunnable != null) {
                handler.removeCallbacks(scanRunnable);
            }

            // Set the scanner time spinner to 'OFF'
            refreshIntervalSpinner.setSelection(0);
            updateListView(); // Refresh the list view to apply the color change
        });

        return rootView;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.wifite_ui_toolbar_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 2131296354:
                WifiteSettingFragment settingsDialog = new WifiteSettingFragment();
                settingsDialog.setSettingsDialogListener(this);
                settingsDialog.show(getParentFragmentManager(), "SettingsDialog");
                return true;
            case 2131296344:
                clearList();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onSettingsChanged(boolean showNetworksWithoutSSID) {
        scanWifi(showNetworksWithoutSSID, "all");
    }

    @NonNull
    @Override
    public CreationExtras getDefaultViewModelCreationExtras() {
        return super.getDefaultViewModelCreationExtras();
    }

    public static class WifiteSettingsDialogFragment extends DialogFragment {
        public interface SettingsDialogListener {
            void onSettingsChanged(boolean showNetworksWithoutSSID);
        }

        private SettingsDialogListener listener;

        @NonNull
        @Override
        public AlertDialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
            LayoutInflater inflater = requireActivity().getLayoutInflater();
            View view = inflater.inflate(R.layout.wifite_ui_settings, null);

            CheckBox checkboxOption = view.findViewById(R.id.checkbox_option);

            builder.setView(view)
                    .setTitle("Settings")
                    .setPositiveButton("OK", (dialog, id) -> {
                        if (listener != null) {
                            listener.onSettingsChanged(checkboxOption.isChecked());
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, id) -> dialog.dismiss());
            return builder.create();
        }

        public void setSettingsDialogListener(SettingsDialogListener listener) {
            this.listener = listener;
        }
    }

    private void clearList() {
        arrayList.clear();
        updateListView();
        Snackbar.make(requireView(), "Terminal was cleared", BaseTransientBottomBar.LENGTH_SHORT).show();
    }

    private void sortBySignal() {
        Collections.sort(arrayList, (a, b) -> {
            int signalA = getSignalFromString(a);
            int signalB = getSignalFromString(b);
            return Integer.compare(signalB, signalA);
        });
        updateListView();
    }

    private int getSignalFromString(String str) {
        // Extract signal strength from the string
        // Assuming the format is "Signal% - Channel - SSID - BSSID - ENC"
        String[] parts = str.split(" - ");
        return Integer.parseInt(parts[0].replace("%", ""));
    }

    private void updateListView() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.wifite_network_items, arrayList) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.wifite_network_items, parent, false);
                }

                String[] parts = Objects.requireNonNull(getItem(position)).split(" - ");
                TextView signal = convertView.findViewById(R.id.signal);
                TextView channel = convertView.findViewById(R.id.channel);
                TextView bssid = convertView.findViewById(R.id.bssid);
                TextView encryption = convertView.findViewById(R.id.encryption);

                if (parts.length > 0) {
                    int signalPercent = Integer.parseInt(parts[0].replace("%", ""));
                    signal.setText(parts[0]);

                    if (signalPercent >= 1 && signalPercent <= 25) {
                        signal.setTextColor(Color.RED);
                    } else if (signalPercent >= 26 && signalPercent <= 45) {
                        signal.setTextColor(Color.YELLOW);
                    } else if (signalPercent > 46) {
                        signal.setTextColor(Color.GREEN);
                    }
                } else {
                    signal.setText("");
                }

                channel.setText(parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "");
                bssid.setText(parts.length > 2 && !parts[2].isEmpty() ? parts[2] : "");
                encryption.setText(parts.length > 3 && !parts[3].isEmpty() ? parts[3] : "");

                // Set text color to red only for the selected item
                if (position == selectedPosition) {
                    signal.setTextColor(Color.RED);
                    channel.setTextColor(Color.RED);
                    bssid.setTextColor(Color.RED);
                    encryption.setTextColor(Color.RED);
                    convertView.setBackgroundColor(Color.WHITE);
                } else {
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                }

                return convertView;
            }
        };
        wifiNetworksList.setAdapter(adapter);
    }

    private void scanWifi(boolean showNetworksWithoutSSID, String all) {
        executorService.execute(() -> {
            Activity activity = getActivity();
            assert activity != null;

            activity.runOnUiThread(() -> {
                // Disabling bluetooth so wifi will be definitely available for scanning
                if (iswatch) {
                    exe.RunAsRoot(new String[]{"svc bluetooth disable;settings put system clockwork_wifi_setting on"});
                } else {
                    exe.RunAsRoot(new String[]{"svc wifi enable"});
                }
                arrayList.clear();
                arrayList.add("Scanning...");
                wifiNetworksList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, arrayList));
                wifiNetworksList.setVisibility(View.VISIBLE);
            });

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            // Check if "iw" binary is available
            boolean isIwAvailable = !exe.Executer("which iw").trim().isEmpty();

            // Check if "wlan1" is available
            boolean isWlan1Available = false;
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    if (networkInterface.isUp() && !networkInterface.isLoopback() && networkInterface.getName().equals("wlan1")) {
                        isWlan1Available = true;
                        break;
                    }
                }
            } catch (SocketException e) {
                Log.e(TAG, "Error getting network interfaces", e);
            }

            if (isIwAvailable && isWlan1Available) {
                // Use "iw" command to scan for networks on "wlan1"
                String[] scanCommand = {"iw", "dev", "wlan1", "scan"};
                String scanResults = exe.Executer(scanCommand); // Ensure Executer returns a String
                activity.runOnUiThread(() -> {
                    if (scanResults.isEmpty()) {
                        final ArrayList<String> noTargets = new ArrayList<>();
                        noTargets.add("No nearby WiFi networks");
                        wifiNetworksList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, noTargets));
                    } else {
                        ArrayList<String> scanResultsList = new ArrayList<>(Arrays.asList(scanResults.split("\n")));
                        arrayList.clear();
                        arrayList.addAll(scanResultsList);
                        sortBySignal(); // Sort by signal strength by default
                    }
                });
            } else {
                // Use default WiFiManager to scan for networks on "wlan0"
                List<ScanResult> results = wifiManager.getScanResults();
                List<ScanResult> finalResults1 = results;
                activity.runOnUiThread(() -> {
                    if (finalResults1.isEmpty()) {
                        final ArrayList<String> noTargets = new ArrayList<>();
                        noTargets.add("No nearby WiFi networks");
                        wifiNetworksList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, noTargets));
                    } else {
                        ArrayList<String> scanResults = new ArrayList<>();
                        for (ScanResult result : finalResults1) {
                            if (showNetworksWithoutSSID || !result.SSID.isEmpty()) {
                                StringBuilder resultString = getStringBuilder(result);
                                scanResults.add(resultString.toString());
                            }
                        }
                        arrayList.clear();
                        arrayList.addAll(scanResults);
                        sortBySignal(); // Sort by signal strength by default
                    }
                });

                // Start WiFi scan
                wifiManager.startScan();
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                    return;
                }
                results = wifiManager.getScanResults();

                List<ScanResult> finalResults = results;
                activity.runOnUiThread(() -> {
                    if (finalResults.isEmpty()) {
                        final ArrayList<String> noTargets = new ArrayList<>();
                        noTargets.add("No nearby WiFi networks");
                        wifiNetworksList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, noTargets));
                        Snackbar.make(requireView(), "No nearby WiFi networks", Snackbar.LENGTH_SHORT).show();
                    } else {
                        ArrayList<String> scanResults = new ArrayList<>();
                        for (ScanResult result : finalResults) {
                            StringBuilder resultString = getStringBuilder(result);
                            scanResults.add(resultString.toString());
                        }
                        arrayList.clear();
                        arrayList.addAll(scanResults);
                        sortBySignal(); // Sort by signal strength by default
                    }
                });

                if (iswatch) {
                    // Re-enabling bluetooth
                    exe.RunAsRoot(new String[]{"svc bluetooth enable"});
                }
            }
        });
    }

    private void scheduleScan() {
        if (scanRunnable != null) {
            handler.removeCallbacks(scanRunnable);
        }
        if (refreshInterval > 0) {
            scanRunnable = new Runnable() {
                @Override
                public void run() {
                    scanWifi(true, "all");
                    handler.postDelayed(this, refreshInterval);
                }
            };
            handler.post(scanRunnable);
        }
    }

    @NonNull
    private StringBuilder getStringBuilder(ScanResult result) {
        int channel = getChannelFromFrequency(result.frequency);
        int signalPercent = getSignalStrengthInPercent(result.level);
        String encryptionType = getEncryptionType(result);
        StringBuilder resultString = new StringBuilder();
        resultString.append(signalPercent).append("% - ");
        resultString.append(channel).append(" - ");
        resultString.append(result.SSID);
        resultString.append(" - ").append(encryptionType);
        return resultString;
    }

    private int getSignalStrengthInPercent(int level) {
        int maxLevel = -30; // Maximum signal level in dBm
        int minLevel = -100; // Minimum signal level in dBm
        return (int) ((level - minLevel) * 100.0 / (maxLevel - minLevel));
    }

    private int getChannelFromFrequency(int frequency) {
        if (frequency >= 2412 && frequency <= 2472) {
            return (frequency - 2407) / 5;
        } else if (frequency == 2484) {
            return 14;
        } else if (frequency >= 5180 && frequency <= 5825) {
            return (frequency - 5000) / 5;
        } else {
            return -1; // Unknown frequency
        }
    }

    private String getEncryptionType(ScanResult result) {
        String capabilities = result.capabilities;
        String encryptionType = "Open";

        if (capabilities.contains("WPA3")) {
            encryptionType = "WPA3";
        } else if (capabilities.contains("WPA2")) {
            encryptionType = "WPA2";
        } else if (capabilities.contains("WPA")) {
            encryptionType = "WPA";
        } else if (capabilities.contains("WEP")) {
            encryptionType = "WEP";
        }

        if (capabilities.contains("WPS")) {
            encryptionType += "+WPS";
        }

        return encryptionType;
    }
}