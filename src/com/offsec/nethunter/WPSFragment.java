package com.offsec.nethunter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
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

import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.util.ArrayList;

public class WPSFragment extends Fragment {
    public static final String TAG = "WPSFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private TextView SelectedIface;
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
    private Context context;
    private static Activity activity;
    private NhPaths nh;
    private final ShellExecuter exe = new ShellExecuter();
    private String selected_network;
    private String pixieCMD = "";
    private String pixieforceCMD = "";
    private String bruteCMD = "";
    private String customPINCMD = "";
    private String customPIN = "";
    private String delayCMD = "";
    private String delayTIME = "";
    private String pbcCMD = "";
    private Boolean iswatch;

    public static WPSFragment newInstance(int sectionNumber) {
        WPSFragment fragment = new WPSFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = getContext();
        activity = getActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.wps, container, false);

        //Detecting watch
        SharedPreferences sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        iswatch = sharedpreferences.getBoolean("running_on_wearos", false);

        //WIFI Scanner
        Button scanButton = rootView.findViewById(R.id.scanwps);
        scanButton.setOnClickListener(view -> scanWifi());

        WPSList = rootView.findViewById(R.id.wpslist);
        ArrayAdapter WPSadapter = new ArrayAdapter(getContext(), android.R.layout.simple_list_item_1, arrayList);
        WPSList.setAdapter(WPSadapter);

        //Reset interface on WearOS, thanks to "Auto" enabling, might also needed on phones
        Button resetifaceButton = rootView.findViewById(R.id.resetinterface);
        resetifaceButton.setOnClickListener(view -> {
            if (iswatch) exe.RunAsRoot(new String[]{"settings put system clockwork_wifi_setting off; sleep 1 && settings put system clockwork_wifi_setting on"});
            else exe.RunAsRoot(new String[]{"svc wifi disable; sleep 1 && svc wifi enable"});
        });

        //Select target network
        WPSList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()  {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                String selected_target = WPSList.getItemAtPosition(pos).toString();
                if (selected_target.equals("No nearby WPS networks") || selected_target.equals("Please reset the interface!")){
                    selected_network = "";
                }
                else selected_network = selected_target.split(";")[0];
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        //Checkboxes
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

        //Start attack
        Button startButton = rootView.findViewById(R.id.start_oneshot);
        SelectedIface = rootView.findViewById(R.id.wps_iface);
        DelayTime = rootView.findViewById(R.id.delaytime);

        startButton.setOnClickListener(v ->  {
            String selected_interface = SelectedIface.getText().toString();
            customPIN = CustomPIN.getText().toString();
            delayTIME = DelayTime.getText().toString();
            if (!selected_network.equals("")) {
                if (iswatch) {
                    exe.RunAsRoot(new String[]{"settings put system clockwork_wifi_setting on"});
                } else exe.RunAsRoot(new String[]{"svc wifi enable"});
                run_cmd("python3 /sdcard/nh_files/modules/oneshot.py -b " + selected_network +
                        " -i " + selected_interface + pixieCMD + pixieforceCMD + bruteCMD + customPINCMD + customPIN + delayCMD + delayTIME + pbcCMD);
                //WearOS iface control is weird, hence reset is needed
                if (iswatch)
                    AsyncTask.execute(() -> {
                        getActivity().runOnUiThread(() -> {
                            exe.RunAsRoot(new String[]{"sleep 12 && settings put system clockwork_wifi_setting off; sleep 2 && ip link set wlan0 up"});
                        });
                    });
            }
            else Toast.makeText(getActivity().getApplicationContext(), "No target selected!", Toast.LENGTH_SHORT).show();
        });

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    private void scanWifi() {
        AsyncTask.execute(() -> {
            getActivity().runOnUiThread(() -> {
                //Disabling bluetooth so wifi will be definitely available for scanning
                if (iswatch) {
                    exe.RunAsRoot(new String[]{"svc bluetooth disable; ifconfig wlan0 down; ifconfig wlan0 up; settings put system clockwork_wifi_setting on"});
                }
                else exe.RunAsRoot(new String[]{"svc wifi enable"});
                arrayList.clear();
                arrayList.add("Scanning...");
                WPSList.setAdapter(new ArrayAdapter(getContext(), android.R.layout.simple_list_item_1, arrayList));
                WPSList.setVisibility(View.VISIBLE);
            });
            String outputScanLog = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd python3 /sdcard/nh_files/modules/oneshot.py -i wlan0 -s | grep -E '[0-9])' | awk '{print $2\";\"$3}'");
            getActivity().runOnUiThread(() -> {
                final String[] arrayList = outputScanLog.split("\n");
                ArrayAdapter targetsadapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, arrayList);
                if (outputScanLog.equals("")) {
                    final ArrayList<String> notargets = new ArrayList<>();
                    notargets.add("No nearby WPS networks");
                    WPSList.setAdapter(new ArrayAdapter(getContext(), android.R.layout.simple_list_item_1, notargets));
                } else if (outputScanLog.equals("Error:;command")){
                    final ArrayList<String> notargets = new ArrayList<>();
                    notargets.add("Please reset the interface!");
                    WPSList.setAdapter(new ArrayAdapter(getContext(), android.R.layout.simple_list_item_1, notargets));
                } else {
                    WPSList.setAdapter(targetsadapter);
                }
            });
            if (iswatch) {
                //re-enabling bluetooth
                exe.RunAsRoot(new String[]{"svc bluetooth enable"});
            }
        });
    }

    ////
    // Bridge side functions
    ////

    public static void run_cmd(String cmd) {
        Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
    }
}