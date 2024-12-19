package com.offsec.nethunter;


import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
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

import com.google.android.material.snackbar.Snackbar;
import com.offsec.nethunter.utils.ShellExecuter;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Collections;
import java.util.Objects;

public class WifiScannerFragment extends Fragment implements WifiteSettingsDialogFragment.SettingsDialogListener {
    private boolean showNetworksWithoutSSID = true;
    public static final String TAG = "WifiScannerFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private ListView wifiNetworksList;
    private final ArrayList<String> arrayList = new ArrayList<>();
    private Context context;
    public Activity activity;
    private final ShellExecuter exe = new ShellExecuter();
    private Boolean iswatch;
    private WifiManager wifiManager;
    private final Handler handler = new Handler();
    private Runnable scanRunnable;
    private SwipeRefreshLayout swipeRefreshLayout;
    private int refreshInterval = 10000; // Default to 10 seconds


    public static WifiScannerFragment newInstance(int sectionNumber) {
        WifiScannerFragment fragment = new WifiScannerFragment();
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
        View rootView = inflater.inflate(R.layout.wifi_scanner, container, false);

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
            scanWifi();
            swipeRefreshLayout.setRefreshing(false);
        });

        // Initialize Bottom Navigation View
        BottomNavigationView bottomNavigationView = rootView.findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            switch (item.getItemId()) {
                case R.id.navigation_home:
                    // Handle home action
                    return true;
                case R.id.navigation_dashboard:
                    // Handle dashboard action
                    return true;
                case R.id.navigation_notifications:
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
        List<String> refreshOptions = Arrays.asList("OFF", "8 seconds", "10 seconds", "15 seconds", "20 seconds");
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

        ArrayAdapter<String> channelAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, wifiChannels);
        channelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wifiChannel.setAdapter(channelAdapter);

        wifiChannel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        // Handle "All Channels"
                        break;
                    case 1:
                        // Handle "2.4 ghz channels"
                        break;
                    case 2:
                        // Handle "5 ghz channels"
                        break;
                    case 3:
                        // Handle "6 ghz channels"
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        // Set OnItemClickListener to hold selection
        BottomNavigationView finalBottomNavigationView = bottomNavigationView;
        wifiNetworksList.setOnItemClickListener((parent, view, position, id) -> {
            for (int i = 0; i < parent.getChildCount(); i++) {
                parent.getChildAt(i).setBackgroundColor(Color.TRANSPARENT); // Reset background color for all items
            }
            view.setBackgroundColor(Color.LTGRAY); // Set background color for selected item

            // Stop scanning for WiFi
            if (scanRunnable != null) {
                handler.removeCallbacks(scanRunnable);
            }

            // Set the scanner time spinner to 'OFF'
            refreshIntervalSpinner.setSelection(0);

            // Enable the "Attack" menu item when a target is selected
            finalBottomNavigationView.getMenu().findItem(R.id.navigation_dashboard).setEnabled(true);
        });

        return rootView;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.toolbar_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                com.offsec.nethunter.WifiteSettingsDialogFragment settingsDialog = new com.offsec.nethunter.WifiteSettingsDialogFragment();
                settingsDialog.setSettingsDialogListener(this);
                settingsDialog.show(getParentFragmentManager(), "SettingsDialog");
                return true;
            case R.id.action_clear:
                clearList();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onSettingsChanged(boolean showNetworksWithoutSSID) {
        this.showNetworksWithoutSSID = showNetworksWithoutSSID;
        scanWifi();
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

        public SettingsDialogListener listener;

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
            LayoutInflater inflater = requireActivity().getLayoutInflater();
            View view = inflater.inflate(R.layout.dialog_settings, null);

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
    }

    private void clearList() {
        arrayList.clear();
        updateListView();
        Snackbar.make(requireView(), "Terminal was cleared", Snackbar.LENGTH_SHORT).show();
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
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), R.layout.wifi_network_item, arrayList) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.wifi_network_item, parent, false);
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
                convertView.setBackgroundColor(Color.TRANSPARENT); // Reset background color for non-selected items
                encryption.setTextColor(Color.parseColor("#FFA500")); // Set text color to orange for non-selected items
                return convertView;
            }
        };
        wifiNetworksList.setAdapter(adapter);
    }

    private void scanWifi() {
        AsyncTask.execute(() -> {
            Activity activity = getActivity();
            assert activity != null;
            {
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
                boolean isIwAvailable = exe.Executer("which iw").trim().length() > 0;

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
                    activity.runOnUiThread(() -> {
                        if (results.isEmpty()) {
                            final ArrayList<String> noTargets = new ArrayList<>();
                            noTargets.add("No nearby WiFi networks");
                            wifiNetworksList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, noTargets));
                        } else {
                            ArrayList<String> scanResults = new ArrayList<>();
                            for (ScanResult result : results) {
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
                }

                // Start WiFi scan
                wifiManager.startScan();
                if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                    return;
                }
                List<ScanResult> results = wifiManager.getScanResults();

                activity.runOnUiThread(() -> {
                    if (results.isEmpty()) {
                        final ArrayList<String> noTargets = new ArrayList<>();
                        noTargets.add("No nearby WiFi networks");
                        wifiNetworksList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, noTargets));
                        Snackbar.make(requireView(), "No nearby WiFi networks", Snackbar.LENGTH_SHORT).show();
                    } else {
                        ArrayList<String> scanResults = new ArrayList<>();
                        for (ScanResult result : results) {
                            StringBuilder resultString = getStringBuilder(result);
                            scanResults.add(resultString.toString());
                        }
                        arrayList.clear();
                        arrayList.addAll(scanResults);
                        sortBySignal(); // Sort by signal strength by default
                        //Snackbar.make(requireView(), "Scan complete", Snackbar.LENGTH_SHORT).show();
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
                    scanWifi();
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