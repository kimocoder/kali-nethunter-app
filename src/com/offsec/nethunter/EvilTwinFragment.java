package com.offsec.nethunter;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.ShellExecuter;
import com.offsec.nethunter.utils.NhPaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class EvilTwinFragment extends Fragment {

    // UI Components
    private Spinner interfaceSpinner;
    private Button scanButton;
    private Spinner targetSpinner;
    private TextView channelText;
    private RadioGroup apSourceGroup;
    private EditText apNameInput;
    private RadioGroup internetSourceGroup;
    private Button startButton;
    private Button clearLogsButton;
    private TextView logText;
    
    private final Handler handler = new Handler();
    private List<String> networkSsids = new ArrayList<>();
    private List<String> networkBssids = new ArrayList<>();
    private List<String> networkChannels = new ArrayList<>();
    private boolean isRunning = false;
    private String selectedTargetBSSID;
    private String selectedTargetChannel;
    private String selectedTargetSSID;
    private Thread logReaderThread;
    private Activity activity;
    private SharedPreferences sharedpreferences;
    private NestedScrollView EvilTwinView;
    private ScrollView EvilTwinScroll;

    private Boolean inappterm;
    private static final String PREFS_NAME = "nethunter_prefs";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activity = getActivity();
        sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", 0);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.eviltwin, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize UI components
        interfaceSpinner = view.findViewById(R.id.eviltwin_interface);
        scanButton = view.findViewById(R.id.eviltwin_scan);
        targetSpinner = view.findViewById(R.id.eviltwin_target);
        channelText = view.findViewById(R.id.eviltwin_channel);
        apSourceGroup = view.findViewById(R.id.eviltwin_ap_source);
        apNameInput = view.findViewById(R.id.eviltwin_ap_name);
        internetSourceGroup = view.findViewById(R.id.eviltwin_internet_source);
        startButton = view.findViewById(R.id.eviltwin_start);
        clearLogsButton = view.findViewById(R.id.eviltwin_clear);
        logText = view.findViewById(R.id.eviltwin_log);
        logText.setMovementMethod(new ScrollingMovementMethod());
        EvilTwinView = view.findViewById(R.id.eviltwin_view);
        EvilTwinScroll = view.findViewById(R.id.eviltwin_scroll);

        // Target spinner listener
        targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.d("EvilTwin", "Spinner item selected");
                if (parent == null) {
                    Log.e("EvilTwin", "AdapterView is null");
                    return;
                }
                Object selected = parent.getSelectedItem();
                if (selected == null) {
                    Log.e("EvilTwin", "Selected item is null");
                    return;
                }
                String selectedStr = selected.toString();
                Log.d("EvilTwin", "Selected: " + selectedStr);
                
                String[] parts = selectedStr.split(" \\| ");
                if (parts.length < 2) {
                    Log.e("EvilTwin", "Failed to parse");
                    return;
                }
                String bssid = parts[0];
                String rest = parts[1];
                String[] channelParts = rest.split(" \\[Ch ");
                if (channelParts.length < 2) {
                    Log.e("EvilTwin", "Failed to parse channel");
                    return;
                }
                String ssid = channelParts[0];
                String channel = channelParts[1].replace("]", "");
                
                Log.d("EvilTwin", "BSSID: " + bssid);
                Log.d("EvilTwin", "SSID: " + ssid);
                Log.d("EvilTwin", "Channel: " + channel);
                
                selectedTargetBSSID = bssid;
                selectedTargetChannel = channel;
                selectedTargetSSID = ssid;
                channelText.setText("Channel: " + channel);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                Log.d("EvilTwin", "Nothing selected");
            }
        });

        // Internet source radio button listener
        internetSourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
           // Only auto-detect and wifi options now
           Log.d("EvilTwin", "Internet source selected: " + checkedId);
        });

        // Scan button click listener
        scanButton.setOnClickListener(v -> {
            scanButton.setText("SCANNING... (10s)");
            scanButton.setEnabled(false);
            scanNetworks();
        });

        // Start button click listener (toggle)
        startButton.setOnClickListener(v -> {
            String buttonText = startButton.getText().toString();
            Log.d("EvilTwin", "$2: Button clicked");
            if (buttonText.equals("START")) {
                Log.d("EvilTwin", "$2: Button shows START - calling startAttack()");
                startAttack();
            } else {
                Log.d("EvilTwin", "$2: Button shows STOP - calling stopAttack()");
                stopAttack();
            }
        });

        // Clear button click listener
        clearLogsButton.setOnClickListener(v -> {
            logText.setText("");
            appendLog("Ready. Select options and click START.");
        });

        loadInterfaces();

        // Menu Provider
        MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.eviltwin_menu, menu);
            }
            
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_setup) {
                    RunSetup();
                    return true;
                } else if (id == R.id.action_update) {
                    RunUpdate();
                    return true;
                } else if (id == R.id.action_documentation) {
                    RunDocumentation();
                    return true;
                } else if (id == R.id.action_description) {
                    showEvilTwinDescription();
                    return true;
                }
                return false;
            }
            
            @Override
            public void onMenuClosed(@NonNull Menu menu) {}
            @Override
            public void onPrepareMenu(@NonNull Menu menu) {}
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        // First run dialog

        if (!sharedpreferences.getBoolean("eviltwin_setup_done", false)) {
            SetupDialog();
        }
        if (sharedpreferences.getBoolean("eviltwin_first_run", true)) {
            showEvilTwinDescription();
            sharedpreferences.edit().putBoolean("eviltwin_first_run", false).apply();
        }
    }

    private void loadInterfaces() {
        new Thread(() -> {
            try {
                Log.d("EvilTwin", "loadInterfaces: thread started");
                ShellExecuter exe = new ShellExecuter();
                String command = "for dev in $(iw dev 2>/dev/null | grep Interface | awk '{print $2}' | grep '^wlan' | sort); do phy=$(iw dev $dev info 2>/dev/null | grep wiphy | awk '{print $2}'); if iw phy$phy info 2>/dev/null | grep -q 'Band 2:'; then echo \"$dev (Dual Band)\"; else echo \"$dev (2.4GHz Only)\"; fi; done";
                String output = exe.RunAsRootOutput(command);
                Log.d("EvilTwin", "Command output: " + output);
                if (output == null) {
                    output = "";
                    Log.d("EvilTwin", "Command returned null");
                }
                output = output.trim();
                List<String> interfaces = new ArrayList<>();
                String[] lines = output.split("\n");
                Log.d("EvilTwin", "Split count: " + lines.length);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        Log.d("EvilTwin", trimmed);
                        interfaces.add(trimmed);
                    }
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, interfaces);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                Log.d("EvilTwin", "Updating spinner on UI thread");
                getActivity().runOnUiThread(() -> interfaceSpinner.setAdapter(adapter));
            } catch (Exception e) {
                Log.e("EvilTwin", "Exception: " + e.getMessage());
                appendLog("[-] Failed to load interfaces");
            }
        }).start();
    }

    private void scanNetworks() {
        new Thread(() -> {
            try {
                Log.d("EvilTwin", "Scan thread started");
                ShellExecuter exe = new ShellExecuter();

                // NULL CHECK - Get selected interface safely
                Object selectedObj = interfaceSpinner.getSelectedItem();
                if (selectedObj == null) {
                    appendLog("[-] No interface available. Please check your WiFi adapter.");
                    getActivity().runOnUiThread(() -> {
                        scanButton.setText("SCAN NETWORKS");
                        scanButton.setEnabled(true);
                    });
                    return;
                }
                String selectedInterface = selectedObj.toString();
                // Parse interface name (remove band info)
                if (selectedInterface.contains(" ")) {
                    selectedInterface = selectedInterface.split(" ")[0];
                }
                
                // Log scan start to both logcat and UI
                Log.d("EvilTwin", "Starting scan on interface: " + selectedInterface);
                appendLog("[*] Scanning on " + selectedInterface);
                
                // Delete old scan files
                exe.RunAsRoot("rm -f /sdcard/scan* 2>/dev/null");
                
                Log.d("EvilTwin", "Checking monitor mode for interface: " + selectedInterface);
                
                // Check if interface is already in monitor mode
                String checkType = exe.RunAsRootOutput("iw dev " + selectedInterface + " info 2>/dev/null | grep type | awk '{print $2}'");
                checkType = checkType.trim();
                
                if (checkType.equals("monitor")) {
                    Log.d("EvilTwin", "Interface " + selectedInterface + " is ALREADY in monitor mode. Skipping.");
                    appendLog("[*] Interface " + selectedInterface + " already in monitor mode");
                } else {
                    Log.d("EvilTwin", "Interface " + selectedInterface + " is NOT in monitor mode (current: '" + checkType + "'). Setting monitor mode...");
                    appendLog("[*] Setting monitor mode on " + selectedInterface);
                    
                    try {
                        int ret = -1;
                        if (selectedInterface.equals("wlan0")) {
                            // ICNSS method for wlan0
                            ret = exe.RunAsRootReturnValue("echo 4 > /sys/module/wlan/parameters/con_mode && ifconfig " + selectedInterface + " down && ifconfig " + selectedInterface + " up");
                            if (ret == 0) {
                                Log.d("EvilTwin", "Monitor mode set for wlan0 using ICNSS method");
                                appendLog("[*] Monitor mode enabled on " + selectedInterface);
                            } else {
                                Log.e("EvilTwin", "Failed to set monitor mode on wlan0, return code: " + ret);
                                appendLog("[-] Failed to set monitor mode on " + selectedInterface);
                            }
                        } else {
                            // Normal monitor mode
                            ret = exe.RunAsRootReturnValue("ifconfig " + selectedInterface + " down && iw dev " + selectedInterface + " set type monitor && ifconfig " + selectedInterface + " up");
                            if (ret == 0) {
                                Log.d("EvilTwin", "Monitor mode set for " + selectedInterface);
                                appendLog("[*] Monitor mode enabled on " + selectedInterface);
                            } else {
                                Log.e("EvilTwin", "Failed to set monitor mode on " + selectedInterface + ", return code: " + ret);
                                appendLog("[-] Failed to set monitor mode on " + selectedInterface);
                            }
                        }
                    } catch (Exception e) {
                        Log.e("EvilTwin", "Exception setting monitor mode: " + e.getMessage());
                        appendLog("[-] Error setting monitor mode: " + e.getMessage());
                    }
                }
                
                Log.d("EvilTwin", "Starting Airodump-ng scan...");
                
                // Verify interface is still in monitor mode
                String verifyMode = exe.RunAsRootOutput("iw dev " + selectedInterface + " info 2>/dev/null | grep type | awk '{print $2}'");
                Log.d("EvilTwin", "Before airodump - interface mode: '" + verifyMode + "'");
                exe.RunAsChrootReturnValue("airodump-ng --band abg --output-format csv -w /sdcard/scan " + selectedInterface + " > /dev/null 2>&1 & sleep 10; pkill -9 -f airodump-ng");
                
                Log.d("EvilTwin", "Scan finished, parsing CSV...");
                String csvData = exe.RunAsRootOutput("awk -F, 'BEGIN {is_ap=1} /Station MAC/ {is_ap=0} is_ap && /^[0-9A-Fa-f]{2}:/ {gsub(/^[ \\t]+|[ \\t]+$/, \"\", $14); ssid = ($14 == \"\") ? \"HIDDEN\" : $14; printf \"%s|%s|%s\\n\", $1, $4, ssid}' /sdcard/scan-01.csv");
                
                // Show scan completed in UI log
                appendLog("[*] Scan completed");
                
                Log.d("EvilTwin", "Raw output length: " + (csvData != null ? csvData.length() : 0));
                Log.d("EvilTwin", "Preparing UI bridge...");
                
                List<String> networks = new ArrayList<>();
                ArrayAdapter<String> adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, networks);
                
                Log.d("EvilTwin", "Updating UI spinner...");
                final String finalCsvData = csvData;
                getActivity().runOnUiThread(() -> {
                    Log.d("EvilTwin", "UI updater running");
                    if (finalCsvData == null) return;
                    String[] lines = finalCsvData.split("\n");
                    adapter.clear();
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        String[] parts = trimmed.split("\\|");
                        if (parts.length >= 3) {
                            String bssid = parts[0];
                            String channel = parts[1];
                            String ssid = parts[2];
                            String display = bssid + " | " + ssid + " [Ch " + channel + "]";
                            adapter.add(display);
                        }
                    }
                    targetSpinner.setAdapter(adapter);
                    Log.d("EvilTwin", "UI spinner updated");
                    
                    // Restore scan button
                    scanButton.setText("SCAN NETWORKS");
                    scanButton.setEnabled(true);
                });
                Log.d("EvilTwin", "UI update posted");
            } catch (Exception e) {
                Log.e("EvilTwin", "Scan error: " + e.getMessage());
            }
        }).start();
    }

    private String getApSource() {
        int checkedId = apSourceGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.eviltwin_ap_virtual) {
            return "virtual";
        } else {
            return "existing";
        }
    }

    private String getInternetSource() {
        int checkedId = internetSourceGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.eviltwin_internet_auto) {
            return "auto";
        } else if (checkedId == R.id.eviltwin_internet_wifi) {
            return "wlan0";
        } 
        return "auto";
    }

    private void startAttack() {
        EvilTwinView.post(() -> {
            int targetY = EvilTwinScroll.getBottom();
            EvilTwinScroll.smoothScrollTo(0, targetY);
        });
        // Clear old log file before starting new attack
        try {
            ShellExecuter exeClear = new ShellExecuter();
            exeClear.RunAsRoot("rm -f /sdcard/evil_twin_debug.log");
            Log.d("EvilTwin", "Old log file removed");
        } catch (Exception e) {
            Log.e("EvilTwin", "Failed to remove log: " + e.getMessage());
        }
        
        // Check if PID file exists and process is alive
        File pidFile = new File("/sdcard/evil_twin.pid");
        if (pidFile.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(pidFile));
                String pid = reader.readLine();
                reader.close();
                ShellExecuter exe = new ShellExecuter();
                int result = exe.RunAsChrootReturnValue("kill -0 " + pid + " 2>/dev/null");
                if (result == 0) {
                    appendLog("[!] Attack already running");
                    return;
                }
                pidFile.delete();
            } catch (Exception e) {
                // Ignore
            }
        }
        
        // NULL CHECK - Get selected interface safely
        Object selectedObj = interfaceSpinner.getSelectedItem();
        if (selectedObj == null) {
            appendLog("[-] No interface selected. Please scan and select an interface first.");
            return;
        }
        String selectedInterface = selectedObj.toString();
        // Parse interface name (remove band info)
        if (selectedInterface.contains(" ")) {
            selectedInterface = selectedInterface.split(" ")[0];
        }
        
        if (selectedInterface == null || selectedInterface.isEmpty()) {
            appendLog("[-] Invalid interface selected");
            return;
        }
        if (selectedTargetBSSID == null) {
            appendLog("[-] Select a target network");
            return;
        }
        String apName = apNameInput.getText().toString().trim();
        if (apName.isEmpty()) {
            appendLog("[-] Enter AP interface name");
            return;
        }
        
        String apSource = getApSource();
        String internetSource = getInternetSource();
        
        
        // Build command
        final String command = "cd /eviltwin && python3 module.py --interface " + selectedInterface +
                " --bssid " + selectedTargetBSSID +
                " --channel " + selectedTargetChannel +
                " --ssid \"" + selectedTargetSSID + "\"" +
                " --ap-source " + apSource +
                " --ap-name " + apName +
                " --internet " + internetSource;
             
        String finalCommand = command + " > /sdcard/evil_twin.log 2>&1 & echo $! > /sdcard/evil_twin.pid";
        
        // Log the command and parameters to logcat
        Log.d("EvilTwin", "========================================");
        Log.d("EvilTwin", "FULL COMMAND:");
        Log.d("EvilTwin", finalCommand);
        Log.d("EvilTwin", "========================================");
        Log.d("EvilTwin", "PARAMETERS:");
        Log.d("EvilTwin", "  Interface: " + selectedInterface);
        Log.d("EvilTwin", "  BSSID: " + selectedTargetBSSID);
        Log.d("EvilTwin", "  Channel: " + selectedTargetChannel);
        Log.d("EvilTwin", "  SSID: " + selectedTargetSSID);
        Log.d("EvilTwin", "  AP Source: " + apSource);
        Log.d("EvilTwin", "  AP Name: " + apName);
        Log.d("EvilTwin", "  Internet: " + internetSource);
        Log.d("EvilTwin", "========================================");
        
        appendLog("[*] Starting attack on " + selectedTargetSSID);
        
        // Start attack thread
        new Thread(() -> {
            try {
                Log.d("EvilTwin", "$7: Attack thread started");
                appendLog("[*] Executing attack command...");
                ShellExecuter exe = new ShellExecuter();
                ShellExecuter.ShellResult result = exe.RunAsChrootWithResult(finalCommand);
                Log.d("EvilTwin", "$7: Command exit code: " + result.exitCode);
                appendLog("[*] Attack process started in background");
            } catch (Exception e) {
                Log.e("EvilTwin", "$7: Exception: " + e.getMessage());
                appendLog("[-] Attack failed: " + e.getMessage());
            }
        }).start();
        
        // Start log reader thread
        logReaderThread = new Thread(() -> {
            Log.d("EvilTwin", "$12: Thread started");
            
            // Wait for PID file
            while (true) {
                Log.d("EvilTwin", "$12: Waiting for PID file...");
                File pidCheck = new File("/sdcard/evil_twin.pid");
                if (pidCheck.exists()) {
                    break;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            Log.d("EvilTwin", "$12: PID file found, starting log reader");
            
            long lastPosition = 0;
            while (true) {
                File pidCheck = new File("/sdcard/evil_twin.pid");
                if (!pidCheck.exists()) {
                    Log.d("EvilTwin", "$12: PID file deleted - exiting");
                    Log.d("EvilTwin", "$12: Resetting button to START");
                    getActivity().runOnUiThread(() -> {
                        startButton.setText("START");
                        startButton.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
                    });
                    return;
                }
                
                try {
                    File logFile = new File("/sdcard/evil_twin_debug.log");
                    if (logFile.exists()) {
                        long fileSize = logFile.length();
                        if (fileSize > lastPosition) {
                            RandomAccessFile raf = new RandomAccessFile(logFile, "r");
                            raf.seek(lastPosition);
                            String line;
                            while ((line = raf.readLine()) != null) {
                                if (!line.contains("[DEBUG]")) {
                                    String trimmed = line.trim();
                                    if (!trimmed.isEmpty()) {
                                        final String finalLine = trimmed;
                                        getActivity().runOnUiThread(() -> appendLog(finalLine));
                                    }
                                }
                            }
                            lastPosition = raf.getFilePointer();
                            raf.close();
                        }
                    }
                    Thread.sleep(2000);
                } catch (Exception e) {
                    Log.e("EvilTwin", "$12: Exception: " + e.getMessage());
                }
            }
        });
        logReaderThread.start();
        
        // Change button text to STOP and color to red
        startButton.setText("STOP");
        startButton.setTextColor(0xFFD50000); // Red
    }

    private void stopAttack() {
        appendLog("[*] Stopping attack...");

        try {
            File pidFile = new File("/sdcard/evil_twin.pid");
            if (pidFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(pidFile));
                String pid = reader.readLine();
                reader.close();
                int pidInt = Integer.parseInt(pid);
                
                ShellExecuter exe = new ShellExecuter();
                exe.RunAsChrootWithResult("kill -TERM " + pidInt);
                appendLog("[*] Attack process terminated");
                pidFile.delete();
            }
        } catch (Exception e) {
            appendLog("[-] Error stopping attack: " + e.getMessage());
        }
        
        ShellExecuter exe = new ShellExecuter();
        exe.RunAsChrootWithResult("pkill -f \"python3 module.py\" 2>/dev/null");
        
        // Change button text back to START and color to orange
        startButton.setText("START");
        startButton.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
    }

    private void appendLog(String message) {
        handler.post(() -> {
            String currentText = logText.getText().toString();
            logText.setText(currentText + "\n" + message);
            handler.postDelayed(() -> {
                EvilTwinView.fullScroll(View.FOCUS_DOWN);
            }, 50);
        });
    }

    private void SetupDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder.setTitle(R.string.eviltwin_setup_title);
        builder.setMessage(R.string.eviltwin_setup_message);
        builder.setPositiveButton(R.string.eviltwin_setup_button, (dialog, which) -> {
            RunSetup();
            sharedpreferences.edit().putBoolean("eviltwin_setup_done", true).apply();
        });
        builder.setNegativeButton(R.string.eviltwin_dismiss_button, (dialog, which) -> {
            dialog.dismiss();
            sharedpreferences.edit().putBoolean("eviltwin_setup_done", true).apply();
        });
        builder.show();
    }

    private void RunSetup() {
        String command = "echo -ne \"\\033]0;Evil Twin Setup\\007\" && clear;apt update --fix-missing && apt install -y aircrack-ng hostapd dnsmasq php python3 python3-pip ethtool dnschef iw tshark xtables-addons-common python3-flask python3-requests && git clone https://github.com/dr1408/eviltwin /eviltwin";
        check_which_cmd(command);
        sharedpreferences.edit().remove("eviltwin_setup_done").apply();
    }

    private void RunUpdate() {
        String command = "echo -ne \"\\033]0;Evil Twin Update\\007\" && clear;cd /eviltwin && git pull && apt update --fix-missing && apt install -y aircrack-ng hostapd dnsmasq php python3 python3-pip ethtool dnschef iw tshark xtables-addons-common python3-flask python3-requests";
        check_which_cmd(command);
    }

    private void RunDocumentation() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dr1408/eviltwin"));
        startActivity(intent);
    }

    private void showEvilTwinDescription() {
        MaterialAlertDialogBuilder builder2 = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder2.setTitle("EvilTwin");
        builder2.setMessage(getString(R.string.eviltwin_description));
        builder2.setPositiveButton("OK", null);
        builder2.show();
    }

    public void check_which_cmd(String cmd) {
        SharedPreferences sp = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        inappterm = sp.getBoolean("inapp_terminal_enabled", false);
        if (inappterm) run_cmd_inapp(cmd);
        else run_cmd(cmd);
    }
    private void run_cmd(String command) {
        Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", command);
        activity.startActivity(intent);
    }

    private void run_cmd_inapp(String command) {
        try {
            if (getActivity() instanceof AppCompatActivity) {
                AppCompatActivity appCompatActivity = (AppCompatActivity) getActivity();
                TerminalFragment terminalFragment = TerminalFragment.newInstanceWithCommand(R.id.terminal_item, command);
                appCompatActivity.getSupportFragmentManager().beginTransaction()
                        .replace(R.id.container, terminalFragment)
                        .addToBackStack(null)
                        .commitAllowingStateLoss();
                return;
            }
        } catch (Exception e) {
            // Fall through to external terminal
        }
        run_cmd(command);
    }
}