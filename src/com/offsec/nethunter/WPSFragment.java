package com.offsec.nethunter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.util.Log;

public class WPSFragment extends Fragment {
    public static final String TAG = "WPSFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private Spinner ifaceSpinner;
    private String selectedInterface = "wlan0";
    private TextView CustomPIN;
    private TextView DelayTime;
    private Spinner WPSList;
    private CheckBox PixieCheckbox;
    private CheckBox PixieForceCheckbox;
    private CheckBox BruteCheckbox;
    private CheckBox CustomPINCheckbox;
    private CheckBox DelayCMD;
    private CheckBox PbcCMD;
    private final ArrayList<String> arrayList = new ArrayList<>();
    private LinearLayout WPSPinLayout;
    private LinearLayout DelayLayout;
    private Activity activity;
    private final ShellExecuter exe = new ShellExecuter();
    private String selected_network = "";
    private String pixieCMD = "";
    private String pixieforceCMD = "";
    private String bruteCMD = "";
    private String customPINCMD = "";
    private String customPIN = "";
    private String delayCMD = "";
    private String delayTIME = "";
    private String pbcCMD = "";
    private Boolean iswatch;

    public static WearHunterFragment newInstance(int sectionNumber) {
        WearHunterFragment fragment = new WearHunterFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activity = getActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.wps, container, false);

        // Detecting watch
        SharedPreferences sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        iswatch = sharedpreferences.getBoolean("running_on_wearos", false);

        // Enabling wifi in case it's down
        if (iswatch) {
            //firmware may not even be loaded, so let's enable wearos wifi first, then disable, finally up interface
            exe.RunAsRoot(new String[]{"settings put system clockwork_wifi_setting on; ifconfig wlan0 up; settings put system clockwork_wifi_setting off; ifconfig wlan0 up"});
        } else exe.RunAsRoot(new String[]{"svc wifi enable"});

        // Interface spinner setup
        ifaceSpinner = rootView.findViewById(R.id.wps_iface_spinner);
        ExecutorService ifaceExecutor = Executors.newSingleThreadExecutor();
        ifaceExecutor.execute(() -> {
            // Always use the iw binary copied for the detected architecture by CopyBootFiles
            String iwPath = NhPaths.APP_SCRIPTS_BIN_PATH + "/iw";
            Log.d(TAG, "Using iw binary: " + iwPath);

            // Log iw --version output
            //String iwVersion = exe.RunAsRootOutput(iwPath + " --version");
            //Log.d(TAG, "iw version output: " + iwVersion);

            String output = exe.RunAsRootOutput(iwPath + " dev | awk '/Interface/ {print $2}' | grep '^wlan' | sort");
            String[] interfaces = output.trim().isEmpty() ? new String[]{"wlan0"} : output.split("\n");
            if (interfaces.length == 0) interfaces = new String[]{"wlan0"};
            if (isAdded()) {
                String[] finalInterfaces = interfaces;
                requireActivity().runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, finalInterfaces);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    ifaceSpinner.setAdapter(adapter);
                    selectedInterface = finalInterfaces[0];
                    ifaceSpinner.setSelection(0);
                    ifaceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                            selectedInterface = finalInterfaces[position];
                        }
                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {}
                    });
                });
            }
        });

        // WIFI Scanner
        Button scanButton = rootView.findViewById(R.id.scanwps);
        scanButton.setOnClickListener(view -> scanWifi());

        WPSList = rootView.findViewById(R.id.wpslist);
        if (getContext() != null) {
            ArrayAdapter<String> WPSadapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, arrayList);
            WPSList.setAdapter(WPSadapter);
        }

        // Reset interface
        Button resetifaceButton = rootView.findViewById(R.id.resetinterface);
        resetifaceButton.setOnClickListener(view -> {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> requireActivity().runOnUiThread(() -> {
                if (iswatch) exe.RunAsRoot(new String[]{"ifconfig wlan0 down; sleep 1 && ifconfig wlan0 up"});
                else exe.RunAsRoot(new String[]{"svc wifi disable; sleep 1 && svc wifi enable"});
                Toast.makeText(requireActivity().getApplicationContext(), "Done", Toast.LENGTH_SHORT).show();
            }));
        });

        // Select target network
        WPSList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()  {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                String selected_target = WPSList.getItemAtPosition(pos).toString();
                if (selected_target.equals("No nearby WPS networks") || selected_target.equals("Please reset the interface!")){
                    selected_network = "";
                }
                else selected_network = exe.RunAsRootOutput("echo \"" + selected_target + "\" | cut -d ' ' -f 1");
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        // Checkboxes
        PixieCheckbox = rootView.findViewById(R.id.pixie);
        PixieForceCheckbox = rootView.findViewById(R.id.pixieforce);
        BruteCheckbox = rootView.findViewById(R.id.brute);
        CustomPINCheckbox = rootView.findViewById(R.id.custompin);
        CustomPIN = rootView.findViewById(R.id.wpspin);
        DelayCMD = rootView.findViewById(R.id.delay);
        PbcCMD = rootView.findViewById(R.id.pbc);
        WPSPinLayout = rootView.findViewById(R.id.pinlayout);
        DelayLayout = rootView.findViewById(R.id.delaylayout);

        PixieCheckbox.setOnClickListener( v -> {
            if (PixieCheckbox.isChecked())
                pixieCMD = " -K";
            else
                pixieCMD = "";
        });
        PixieForceCheckbox.setOnClickListener( v -> {
            if (PixieForceCheckbox.isChecked())
                pixieforceCMD = " -F";
            else
                pixieforceCMD = "";
        });
        BruteCheckbox.setOnClickListener( v -> {
            if (BruteCheckbox.isChecked())
                bruteCMD = " -B";
            else
                bruteCMD = "";
        });
        CustomPINCheckbox.setOnClickListener( v -> {
            if (CustomPINCheckbox.isChecked()) {
                customPINCMD = " -p ";
                WPSPinLayout.setVisibility(View.VISIBLE);
            }
            else {
                customPINCMD = "";
                customPIN = "";
                WPSPinLayout.setVisibility(View.GONE);
            }
        });
        DelayCMD.setOnClickListener( v -> {
            if (DelayCMD.isChecked()) {
                delayCMD = " -d ";
                DelayLayout.setVisibility(View.VISIBLE);
            }
            else {
                delayCMD = "";
                delayTIME = "";
                DelayLayout.setVisibility(View.GONE);

            }
        });
        PbcCMD.setOnClickListener( v -> {
            if (PbcCMD.isChecked()) {
                pbcCMD = " --pbc";
            }
            else
                pbcCMD = "";
        });

        // Start attack
        Button startButton = rootView.findViewById(R.id.start_oneshot);
        DelayTime = rootView.findViewById(R.id.delaytime);

        startButton.setOnClickListener(v ->  {
            customPIN = CustomPIN.getText().toString();
            delayTIME = DelayTime.getText().toString();
            if (!selected_network.isEmpty()) {
                String cmd = "python3 /sdcard/nh_files/modules/oneshot.py -b " + selected_network +
                        " -i " + selectedInterface + pixieCMD + pixieforceCMD + bruteCMD + customPINCMD + customPIN + delayCMD + delayTIME + pbcCMD;
                run_cmd(cmd);
            }
            else Toast.makeText(requireActivity().getApplicationContext(), "No target selected!", Toast.LENGTH_SHORT).show();
        });

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (iswatch)
            exe.RunAsRoot(new String[]{"settings put system clockwork_wifi_setting on"});
    }

    private void scanWifi() {
        arrayList.clear();
        arrayList.add("Scanning..");
        WPSList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, arrayList));
        WPSList.setVisibility(View.VISIBLE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            // Use the iw binary bundled with the app to scan and extract only WPS-enabled networks as "BSSID SSID"
            String iwPath = NhPaths.APP_SCRIPTS_BIN_PATH + "/iw";
            File awkFile = new File("/system/bin/awk");
            String outputScanLog;

            if (awkFile.exists()) {
                String cmd = iwPath + " dev " + selectedInterface +
                        " scan | awk 'BEGIN{bssid=\"\";ssid=\"\";wps=0} /^BSS /{ if (bssid!=\"\" && ssid!=\"\" && wps==1) {print bssid, ssid} bssid=$2; sub(/\\(.*/, \"\", bssid); ssid=\"\"; wps=0 } /SSID:/{ $1=\"\"; sub(/^ /,\"\"); ssid=$0 } /WPS:/{ wps=1 } END{ if (bssid!=\"\" && ssid!=\"\" && wps==1) {print bssid, ssid} }'";
                outputScanLog = exe.RunAsRootOutput(cmd).trim();
            } else {
                outputScanLog = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd python3 /sdcard/nh_files/modules/oneshot.py -i " + selectedInterface + " -s | grep -E '[0-9])' | tr -s ' ' | cut -d ' ' -f 2-3");
            }
            requireActivity().runOnUiThread(() -> {
                if (outputScanLog.isEmpty()) {
                    final ArrayList<String> notargets = new ArrayList<>();
                    notargets.add("No nearby WPS networks");
                    WPSList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, notargets));
                } else if (outputScanLog.contains("command failed")) {
                    final ArrayList<String> notargets = new ArrayList<>();
                    notargets.add("Please reset the interface!");
                    WPSList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, notargets));
                } else {
                    final String[] targets = outputScanLog.split("\n");
                    ArrayAdapter<String> targetsadapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, targets);
                    WPSList.setAdapter(targetsadapter);
                }
            });
        });
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd(String cmd) {
        Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
    }


    // Helper to route commands through TerminalFragment (saves memory vs external NhTerm)
    private void openTerminalWithCommand(String cmd) {
        if (!isAdded()) return;
        FragmentManager fm = requireActivity().getSupportFragmentManager();
        Fragment term = TerminalFragment.newInstanceWithCommand(R.id.terminal_item, cmd);
        if (fm.isStateSaved()) {
            fm.beginTransaction()
                    .replace(R.id.container, term)
                    .addToBackStack(null)
                    .commitAllowingStateLoss();
        } else {
            fm.beginTransaction()
                    .replace(R.id.container, term)
                    .addToBackStack(null)
                    .commit();
        }
    }
}
