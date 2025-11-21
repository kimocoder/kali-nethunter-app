package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import java.io.File;
import java.util.Arrays;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;

public class VNCFragment extends Fragment {
    private Context context;
    private Activity activity;
    private static final String TAG = "VNCFragment";
    // Debug logging: 0=off, 1=low, 2=medium, 3=high
    private static final String DEFAULT_RESOLUTION = "1080x1920:300ppi";
    private String localhostonly = "";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private String selected_res;
    private String selected_vncres;
    private String selected_vncresCMD = "";
    private String selected_disp;
    private String selected_ppi;
    private String selected_user;
    private String selected_display;
    private String vnc_passwd;
    private boolean showingAdvanced;
    private String prevusr = "kali";
    private String delay_cmd = "";
    private Integer posu;
    private Integer posd = 0;
    private static final int MIN_UID = 9000;
    private static final int MAX_UID = 9999;
    final String BUSYBOX_NH= NhPaths.getBusyboxPath();
    private Boolean iswatch;

    public VNCFragment() {
        // Required empty public constructor
    }

    // Logging wrapper attached to 'NH_SYSTEM_LOGGING' variable ("1" = enabled, "0" = disabled)
    // Howto add logging around this wrapper with the switch;
    //   logDebug(TAG, "Your debug message here");
    //
    private void logDebug(String tag, String message) {
        if (getLoggingLevel() > 0) {
            Log.d(tag, "[Level " + getLoggingLevel() + "] " + message);
        }
    }

    // Central place to compute logging level; change return value or source as needed
    private int getLoggingLevel() {
        return 0; // 0 disables logs; increase to enable
    }

    private void logToast(String message) {
        Toast.makeText(requireActivity().getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    public static VNCFragment newInstance(int sectionNumber) {
        VNCFragment fragment = new VNCFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        logDebug(TAG, "onCreate: Fragment created with savedInstanceState=" + savedInstanceState);
        super.onCreate(savedInstanceState);
        context = getContext();
        activity = getActivity();

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.kex_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.action_info) {
                    Activity activity = getActivity();
                    if (activity != null) {
                        LayoutInflater inflater = LayoutInflater.from(activity);
                        View dialogView = inflater.inflate(R.layout.kex_info_dialog, null);
                        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                                .setView(dialogView)
                                .create();

                        Button closeButton = dialogView.findViewById(R.id.dialog_close_button);
                        closeButton.setOnClickListener(v -> dialog.dismiss());

                        dialog.show();
                        return true;
                    }
                    return true;
                }
                return false;
            }
        }, this, Lifecycle.State.RESUMED);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        logDebug(TAG, "onCreateView: Starting to inflate layout and initialize components");
        final View rootView = inflater.inflate(R.layout.vnc_setup, container, false);
        View AdvancedView = rootView.findViewById(R.id.AdvancedView);
        Button Advanced = rootView.findViewById(R.id.AdvancedButton);
        CheckBox localhostCheckBox = rootView.findViewById(R.id.vnc_checkBox);
        localhostCheckBox.setOnClickListener(v -> vncLocalClick());

        SharedPreferences sharedpreferences = context.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);

        boolean confirm_res = sharedpreferences.getBoolean("confirm_res", false);
        if (confirm_res) {
            confirmDialog();
        }
        showingAdvanced = sharedpreferences.getBoolean("advanced_visible", false);

        boolean localhost = sharedpreferences.getBoolean("localhost", true);
        localhostCheckBox.setChecked(localhost);
        AdvancedView.setVisibility(showingAdvanced ? View.VISIBLE : View.GONE);
        if (showingAdvanced) {
            Advanced.setText(R.string.vnc_hide_advanced_settings);
        } else {
            Advanced.setText(R.string.vnc_show_advanced_settings);
        }
        // Check if the device is a watch
        if (sharedpreferences.getBoolean("running_on_wearos", false)) {
            logDebug(TAG, "onCreateView: detected device type - iswatch=" + iswatch);
            AdvancedView.setVisibility(View.GONE);
            Advanced.setVisibility(View.GONE);
        }
        // Check if the device is a phone or an tablet
        if (sharedpreferences.getBoolean("running_on_phone", false) || sharedpreferences.getBoolean("running_on_tablet", false)) {
            logDebug(TAG, "onCreateView: detected device type - phone or tablet");
            AdvancedView.setVisibility(View.VISIBLE);
            Advanced.setVisibility(View.VISIBLE);
        }

        // Screen size
        int[] size = getScreenSizeCompat(requireContext());
        final int screen_width = size[0];
        final int screen_height = size[1];

        // Because height and width changes on screen rotation, use the largest as width
        String xwidth;
        String xheight;
        if (screen_height > screen_width) {
            xwidth = Integer.toString(screen_height);
            xheight = Integer.toString(screen_width);
        } else {
            xwidth = Integer.toString(screen_width);
            xheight = Integer.toString(screen_height);
        }

        // Detecting watch
        final TextView KexStatus = rootView.findViewById(R.id.status);
        final TextView KexSessions = rootView.findViewById(R.id.sessions);
        iswatch = sharedpreferences.getBoolean("running_on_wearos", false);
        if (iswatch) {
            KexStatus.setText(R.string.vnc_watch_status);
            KexSessions.setText(R.string.vnc_watch_sessions);
        }

        Button StartAudioButton = rootView.findViewById(R.id.vnc_audio);
        Button SetupVNCButton = rootView.findViewById(R.id.set_up_vnc);
        Button StartVNCButton = rootView.findViewById(R.id.start_vnc);
        Button StopVNCButton = rootView.findViewById(R.id.stop_vnc);
        Button OpenVNCButton = rootView.findViewById(R.id.vncClientStart);
        ImageButton RefreshKeX = rootView.findViewById(R.id.refreshKeX);
        Button AddUserButton = rootView.findViewById(R.id.AddUserButton);
        Button DelUserButton = rootView.findViewById(R.id.DelUserButton);
        Button ResetHDMIButton = rootView.findViewById(R.id.reset_hdmi);
        Button AddResolutionButton = rootView.findViewById(R.id.AddResolutionButton);
        Button DelResolutionButton = rootView.findViewById(R.id.DelResolutionButton);
        Button ApplyResolutionButton = rootView.findViewById(R.id.ApplyResolutionButton);
        Button BackupHDMI = rootView.findViewById(R.id.BackupResolutions);
        Button RestoreHDMI = rootView.findViewById(R.id.RestoreResolutions);
        Button AddVNCResolutionButton = rootView.findViewById(R.id.AddVncResolutionButton);
        Button DelVNCResolutionButton = rootView.findViewById(R.id.DelVncResolutionButton);
        Button BackupVNC = rootView.findViewById(R.id.BackupVncResolutions);
        Button RestoreVNC = rootView.findViewById(R.id.RestoreVncResolutions);

        // Add device resolution to vnc-resolution (only first run)
        ShellExecuter exe = new ShellExecuter();
        File vncResFile = new File(NhPaths.APP_SD_FILES_PATH + "/configs/vnc-resolutions");
        String device_res = xwidth + "x" + xheight;
        if (vncResFile.exists() && vncResFile.length() == 0) {
            exe.RunAsRoot(new String[]{"echo \"Auto\"$\"\n\"" + device_res + " > " + vncResFile});
        }

        // HDMI resolution
        File hdmiResFile = new File(NhPaths.APP_SD_FILES_PATH + "/configs/hdmi-resolutions");
        String commandRES = "cat " + hdmiResFile;
        String outputRES = exe.executeCommand(commandRES);
        final String[] resArray = outputRES.split("\n");

        // VNC resolution
        vncResFile = new File(NhPaths.APP_SD_FILES_PATH + "/configs/vnc-resolutions");
        String commandVNCRES = "cat " + vncResFile;
        String outputVNCRES = exe.executeCommand(commandVNCRES);
        final String[] vncresArray = outputVNCRES.split("\n");

        // HDMI Resolution spinner
        Spinner resolution = rootView.findViewById(R.id.resolution);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),android.R.layout.simple_list_item_1, resArray);
        resolution.setAdapter(adapter);

        // VNC Resolution spinner
        Spinner vncresolution = rootView.findViewById(R.id.vncresolution);
        ArrayAdapter<String> vncadapter = new ArrayAdapter<>(requireContext(),android.R.layout.simple_list_item_1, vncresArray);
        vncresolution.setAdapter(vncadapter);

        // Users
        File passwd = new File(NhPaths.CHROOT_PATH() + "/etc/passwd");
        String commandUSR = ("echo root && " + BUSYBOX_NH + " awk -F':' -v \"min=" + MIN_UID + "\" -v \"max=" + MAX_UID + "\" '{ if ( ( $3 >= min && $3 <= max ) || ( $3 >= 100000 && $3 <= 101000 ) ) print $0}' " + passwd + " | " + BUSYBOX_NH + " cut -d: -f1");
        String outputUSR = exe.RunAsRootOutput(commandUSR);
        final String[] userArray = outputUSR.split("\n");
        Arrays.sort(userArray);

        // Last selected user
        prevusr = sharedpreferences.getString("user", "");

        // Users spinner
        Spinner users = rootView.findViewById(R.id.user);
        ArrayAdapter<String> usersadapter = new ArrayAdapter<>(requireContext(),android.R.layout.simple_list_item_1, userArray);
        users.setAdapter(usersadapter);
        Arrays.sort(userArray);
        posu = usersadapter.getPosition(prevusr);
        users.setSelection(posu);

        // Last selected display
        posd = sharedpreferences.getInt("display", 0);

        // Display spinner
        String[] displaylist = new String[]{"1","2","3","4","5","6","7","8","9","10"};
        Spinner displays = rootView.findViewById(R.id.display);
        ArrayAdapter<String> displayadapter = new ArrayAdapter<>(requireContext(),android.R.layout.simple_list_item_1, displaylist);
        displays.setAdapter(displayadapter);
        displays.setSelection(posd);

        // Select User
        users.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                selected_user = parentView.getItemAtPosition(pos).toString();
                sharedpreferences.edit().putString("user", selected_user).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        // Select Display
        displays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int posd, long id) {
                selected_display = parentView.getItemAtPosition(posd).toString();
                sharedpreferences.edit().putInt("display", posd).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        // Select HDMI resolution
        resolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                selected_res = parentView.getItemAtPosition(pos).toString();
                selected_disp = exe.RunAsRootOutput("echo " + selected_res + " | cut -d : -f 1");
                selected_ppi = exe.RunAsRootOutput("echo " + selected_res + " | cut -d : -f 2 | sed 's/ppi//g'");
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        // Last selected resolution
        int prevres = sharedpreferences.getInt("last_kex_res", 0);
        String prevres_string = sharedpreferences.getString("last_kex_res_string", "");
        if (exe.RunAsRootOutput("grep " + prevres_string + " " + NhPaths.APP_SD_FILES_PATH + "/configs/vnc-resolutions").equals(prevres_string)) {
            vncresolution.setSelection(prevres);
        }

        // Select VNC resolution
        vncresolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                selected_vncres = parentView.getItemAtPosition(pos).toString();
                if (selected_vncres.equals("Auto") || selected_vncres.isEmpty()) {
                    selected_vncresCMD = "";

                }
                else selected_vncresCMD = "-geometry " + selected_vncres + " ";
                sharedpreferences.edit().putInt("last_kex_res", pos).apply();
                sharedpreferences.edit().putString("last_kex_res_string", selected_vncres).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        // Immersion switch
        final SwitchCompat immersionSwitch = rootView.findViewById(R.id.immersionSwitch);
        final String immersion = exe.RunAsRootOutput("settings get global policy_control");
        immersionSwitch.setChecked(!immersion.equals("null"));

        immersionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                exe.RunAsRoot(new String[]{"settings put global policy_control immersive.full=*"});
            } else {
                exe.RunAsRoot(new String[]{"settings put global policy_control null"});
            }
        });

        // Checkbox for localhost only
        if (localhostCheckBox.isChecked())
            localhostonly = "-localhost yes ";
        else
            localhostonly = "-localhost no ";
        View.OnClickListener checkBoxListener = v -> {
            if (localhostCheckBox.isChecked()) {
                localhostonly = "-localhost yes ";
                sharedpreferences.edit().putBoolean("localhost", true).apply();

            } else {
                localhostonly = "-localhost no ";
                sharedpreferences.edit().putBoolean("localhost", false).apply();
            }
        };
        localhostCheckBox.setOnClickListener(checkBoxListener);

        // VNC service checkbox
        File kex_init = new File(NhPaths.APP_PATH + "/etc/init.d/99kex");
        final CheckBox vnc_serviceCheckBox = rootView.findViewById(R.id.vnc_serviceCheckBox);
        final String initfile = exe.RunAsRootOutput("cat " + kex_init);

        vnc_serviceCheckBox.setChecked(initfile.contains("vncserver"));

        vnc_serviceCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                File rootvncpasswd = new File(NhPaths.CHROOT_PATH() + "/root/.vnc/passwd");
                String vnc_passwd = exe.RunAsRootOutput("cat " + rootvncpasswd);
                if (vnc_passwd != null && !vnc_passwd.isEmpty()) {
                    String arch_path = exe.RunAsRootOutput("ls " + NhPaths.CHROOT_PATH() + "/usr/lib/ | grep linux-gnu | head -n1");
                    String shebang = "#!/system/bin/sh\n";
                    String kex_prep = "\n# KeX architecture path: " + arch_path + "\n# Commands to run at boot:\nHOME=/root\nUSER=root";
                    String kex_cmd = "su -c '" + NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd LD_PRELOAD=/usr/lib/" + arch_path + "/libgcc_s.so.1 vncserver :1 " + localhostonly + " " + selected_vncresCMD + "'";
                    String fileContents = shebang + "\n" + kex_prep + "\n" + kex_cmd;
                    exe.RunAsRoot(new String[]{
                            "cat > " + kex_init + " <<s0133717hur75\n" + fileContents + "\ns0133717hur75\n",
                            "chmod 700 " + kex_init
                    });
                } else {
                    logToast("Please setup local server first!");
                    vnc_serviceCheckBox.setChecked(false);
                }
            } else {
                exe.RunAsRoot(new String[]{"rm -rf " + kex_init});
            }
        });

        // Delay
        final CheckBox delayCheckBox = rootView.findViewById(R.id.delay_checkBox);
        final EditText delayText = rootView.findViewById(R.id.delay_time);
        final Boolean delay = sharedpreferences.getBoolean("delay", false);
        if (delay.equals(true)) {
            delayCheckBox.setChecked(true);
            delayText.setText(String.valueOf(sharedpreferences.getInt("delaysec", 20)));
            delayText.setEnabled(true);
            delayText.setTextColor(Color.parseColor("#FFFFFF"));
        }

        delayCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                sharedpreferences.edit().putBoolean("delay", true).apply();
                delayText.setEnabled(true);
                delayText.setTextColor(Color.parseColor("#FFFFFF"));
            } else {
                sharedpreferences.edit().putBoolean("delay", false).apply();
                delayText.setEnabled(false);
                delayText.setTextColor(Color.parseColor("#40FFFFFF"));
            }
        });

        // Server status
        RefreshKeX.setOnClickListener(v -> refreshVNC(rootView));
        refreshVNC(rootView);

        // KeX Audio
        addClickListener(StartAudioButton, v -> {
            File audio = new File(NhPaths.CHROOT_PATH() + "/usr/bin/audio");
            if (audio.exists()) {
                logDebug("KeXAudio", "Audio script exists at: " + audio.getAbsolutePath());

                if (StartAudioButton.getText().equals("Enable audio")) {
                    // START logic
                    if (selected_user.equals("root")) {
                        logDebug("KeXAudio", "Running audio enable command as root");
                        run_cmd("echo -ne \"\\033]0;Audio Enable\\007\" && clear && audio start;sleep 2 && exit");
                    } else {
                        logDebug("KeXAudio", "Checking permissions for non-root user: " + selected_user);
                        if (checkUserPermissions(selected_user)) {
                            logDebug("KeXAudio", "Using su to start audio for non-root user");
                            run_cmd("su -c 'echo -ne \"\\033]0;Audio Enable\\007\" && clear && sudo -u " + selected_user + " audio start;sleep 2 && exit'");
                        } else {
                            logDebug("KeXAudio", "User lacks necessary permissions. Permission denied.");
                            logToast("User lacks necessary permissions.");
                            return;
                        }
                    }
                    StartAudioButton.setText(R.string.vnc_disable_audio);
                    refreshVNC(rootView);
                    logDebug("KeXAudio", "Audio enabled for user: " + selected_user);
                    logToast("Audio enabled for user:" + selected_user);
                } else {
                    // STOP logic
                    if (selected_user.equals("root")) {
                        logDebug("KeXAudio", "Running audio disable command as root");
                        run_cmd("echo -ne \"\\033]0;Audio Disable\\007\" && clear && audio stop;sleep 2 && exit");
                    } else {
                        logDebug("KeXAudio", "Disabling audio for non-root user: " + selected_user);
                        if (checkUserPermissions(selected_user)) {
                            logDebug("KeXAudio", "Using su to stop audio for non-root user");
                            run_cmd("su -c 'echo -ne \"\\033]0;Audio Disable\\007\" && clear && sudo -u " + selected_user + " audio stop;sleep 2 && exit'");
                        } else {
                            logDebug("KeXAudio", "User lacks necessary permissions. Permission denied.");
                            logToast("User lacks necessary permissions.");
                            return;
                        }
                    }
                    StartAudioButton.setText(R.string.vnc_enable_audio);
                    refreshVNC(rootView);
                    logDebug("KeXAudio", "Audio disabled for user: " + selected_user);
                    logToast("Audio disabled for user:" + selected_user);
                }
            } else {
                logDebug("KeXAudio", "Audio script not found, attempting installation");
                logToast("Installing missing audio script in chroot..");
                run_cmd("echo -ne \"\\033]0;Kali NetHunter Utils\\007\" && clear;apt update && apt install nethunter-utils;sleep 2 && exit");
            }
        });
        addClickListener(DelResolutionButton, v -> {
            if (!selected_res.equals(DEFAULT_RESOLUTION)) {
                exe.RunAsRoot(new String[]{"sed -i '/^" + selected_res + "$/d' " + hdmiResFile});
                reload();
            } else
                logToast("Can't remove default resolution!");
        });
        addClickListener(SetupVNCButton, v -> {
            String desktop = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd dpkg -l | grep kali-desktop");
            if (desktop.isEmpty()) {
                desktopDialog();
            } else {
                if (iswatch) {
                    logToast("Use password 123456 with root user for KeX on Smartwatch.");
                    run_cmd("echo -ne \"\\033]0;KeX Setup\\007\" && clear;echo 'Setting root:123456 KeX credentials..' && sleep 2 && echo 123456\\n123456\\nn\\n | vncpasswd;echo 'Done! Exiting..' && sleep 2 && exit");
                } else run_cmd("echo -ne \"\\033]0;Setting up Server\\007\" && clear;chmod +x ~/.vnc/xstartup && clear;echo $'\n'\"Please enter your new VNC server password\"$'\n' && " + "if [ \"" + selected_user + "\" == \"root\" ]; then " + "  if [ ! -d /root/.config/tigervnc ]; then mkdir -p -m 0777 /root/.config/tigervnc; fi; " + "  sudo -u root vncpasswd; " + "  if [ ! -f ~/.vnc/passwd ]; then cp -rf ~/.config/tigervnc/passwd ~/.vnc/; fi; " + "else " + " user_uid=$(id -u " + selected_user + "); " + " if [ \"$user_uid\" -eq 100000 ] || [ \"$user_uid\" -eq 9000 ]; then " + " if [ ! -d /home/" + selected_user + "/.config/tigervnc ]; then mkdir -p -m 0777 /home/" + selected_user + "/.config/tigervnc; fi; " + "  sudo -u " + selected_user + " vncpasswd; " + "  if [ ! -f /home/" + selected_user + "/.vnc/passwd ]; then cp -rf /home/" + selected_user + "/.config/tigervnc/passwd /home/" + selected_user + "/.vnc/; fi; " + " fi; " + "fi && sleep 2 && exit"); // since is a kali command we can send it as is
            }
        });
	final TextView VNCstatus = rootView.findViewById(R.id.KeXstatus);
        addClickListener(StartVNCButton, v -> {
	    /* ── NEW: bail out when the server is already running ── */
            if (VNCstatus.getText().toString().equals("RUNNING")) {
                Toast.makeText(requireActivity().getApplicationContext(), "VNC server is already running", Toast.LENGTH_SHORT).show();
                return; //  nothing else is executed
            }
            if (selected_user.equals("root")) {
                File rootvncpasswd = new File(NhPaths.CHROOT_PATH() + "/root/.vnc/passwd");
                vnc_passwd = exe.RunAsRootOutput("cat " + rootvncpasswd);
            } else {
                File uservncpasswd = new File(NhPaths.CHROOT_PATH() + "/home/" + selected_user + "/.vnc/passwd");
                vnc_passwd = exe.RunAsRootOutput("cat " + uservncpasswd);
            }
            if (delayCheckBox.isChecked()) {
                sharedpreferences.edit().putInt("delaysec", Integer.parseInt(delayText.getText().toString())).apply();
                delay_cmd = "echo \"Sleeping for " + delayText.getText().toString() + " seconds to avoid soft reboot\" && sleep " + delayText.getText().toString() + ";";
            }
            if (vnc_passwd.isEmpty()) {
                logToast("Please setup local server first!");
            } else {
                String arch_path = exe.RunAsRootOutput("ls " + NhPaths.CHROOT_PATH() + "/usr/lib/ | grep linux-gnu | head -n1");
                logToast("Starting server.. Please refresh the status in NetHunter app.");
                if (selected_user.equals("root")) {
                        exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service dbus start"});
                        run_cmd("echo -ne \"\\033]0;Starting Server\\007\" && clear;" + delay_cmd + "HOME=/root;USER=root;sudo -u root LD_PRELOAD=/usr/lib/" + arch_path +
                                "/libgcc_s.so.1 nohup vncserver :" + selected_display + " " + localhostonly + "-name \"NetHunter KeX\" " + selected_vncresCMD + " >/dev/null 2>&1 </dev/null;echo \"Server started! Closing terminal..\" && sleep 2 && exit");
                    } else {
                        exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service dbus start"});
                        run_cmd("echo -ne \"\\033]0;Starting Server\\007\" && clear;" + delay_cmd + "HOME=/home/" + selected_user + ";USER=" + selected_user + ";sudo -u " + selected_user + " LD_PRELOAD=/usr/lib/" + arch_path +
                                "/libgcc_s.so.1 nohup vncserver :" + selected_display + " " + localhostonly + "-name \"NetHunter KeX\" " + selected_vncresCMD + " >/dev/null 2>&1 </dev/null;echo \"Server started! Closing terminal..\" && sleep 2 && exit");
                    }
                Log.d(TAG, localhostonly);
            }
        });
        final TextView KeXstatus = rootView.findViewById(R.id.KeXstatus);
        addClickListener(StopVNCButton, v -> {
            if (KeXstatus.getText().toString().equals("STOPPED")) logToast( "There's no active session!");
            else {
                exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd sudo -u " + selected_user + " vncserver -kill :" + selected_display}); // since is a kali command we can send it as is
                dbusDialog();
                refreshVNC(rootView);
                logToast("Stopping display :" + selected_display + " for " + selected_user + " user.");
                }
            });
        addClickListener(OpenVNCButton, v -> {
            intentClickListener_VNC(); // since is a kali command we can send it as is
        });
        addClickListener(Advanced, v -> {
            if (!showingAdvanced) {
                AdvancedView.setVisibility(View.VISIBLE);
                Advanced.setText(R.string.vnc_hide_advanced_settings2);
                showingAdvanced = true;
                sharedpreferences.edit().putBoolean("advanced_visible", true).apply();
            } else {
                AdvancedView.setVisibility(View.GONE);
                Advanced.setText(R.string.vnc_show_advanced_settings);
                showingAdvanced = false;
                sharedpreferences.edit().putBoolean("advanced_visible", false).apply();
            }
        });
        addClickListener(AddUserButton, v -> run_cmd("echo -ne \"\\033]0;New User\\007\" && clear;if [[ $SHELL == *zsh ]];then read \"?Please enter your new username\"$'\n' USER;elif [[ $SHELL == *bash ]];then read -p \"Please enter your new username\"$'\n' USER;fi && adduser --firstuid " + MIN_UID + " --lastuid " + MAX_UID + " $USER; groupmod -g $(id -u $USER) $USER; usermod -aG sudo $USER; usermod -aG inet $USER; usermod -aG sockets $USER; echo \"Please refresh your KeX manager, closing in 2 secs\" && sleep 2 && exit"));
        addClickListener(DelUserButton, v -> {
            if (selected_user.contains("root")) {
                logToast("Can't remove root!");
            } else {
                run_cmd("echo -ne \"\\033]0;Removing User\\007\" && clear;deluser -remove-home " + selected_user + " && sleep 2 && exit");
            }
        });
        addClickListener(ResetHDMIButton, v -> {
            run_cmd_android("wm size reset;wm density reset;am start com.offsec.nethunter/.AppNavHomeActivity -e \":android:show_fragment\" com.offsec.nethunter.VNCFragment;sleep 2 && exit");
            sharedpreferences.edit().putBoolean("confirm_res", false).apply();
        });
        addClickListener(BackupHDMI, v -> {
            exe.RunAsRoot(new String[]{"cp " + hdmiResFile + " " + NhPaths.SD_PATH});
            logToast("Backup successful!");
        });
        addClickListener(RestoreHDMI, v -> {
            String hdmibackup = exe.RunAsRootOutput("cat " + NhPaths.SD_PATH + "/hdmi-resolutions");
            if (hdmibackup.isEmpty()) {
                logToast("Backup file not found!");
            } else {
                exe.RunAsRoot(new String[]{"cp " + NhPaths.SD_PATH + "/hdmi-resolutions " + hdmiResFile});
                reload();
                logToast("Restore successful!");
            }
        });
        addClickListener(AddResolutionButton, v -> openResolutionDialog());
        addClickListener(ApplyResolutionButton, v -> {
            run_cmd_android("wm size " + selected_disp + "; wm density " + selected_ppi + ";am start com.offsec.nethunter/.AppNavHomeActivity -e \":android:show_fragment\" com.offsec.nethunter.VNCFragment;sleep 2 && exit");
            sharedpreferences.edit().putBoolean("confirm_res", true).apply();
        });
        addClickListener(DelResolutionButton, v -> {
            if (!selected_res.equals("1080x1920:300ppi")) {
                exe.RunAsRoot(new String[]{"sed -i '/^" + selected_res + "$/d' " + hdmiResFile});
                reload();
            } else
                logToast("Can't remove default resolution!");
        });
        addClickListener(AddVNCResolutionButton, v -> openVNCResolutionDialog());
        File finalVncResFile = vncResFile;
        addClickListener(DelVNCResolutionButton, v -> {
            if (selected_vncres.equals("Auto")) {
                logToast("Can't remove default resolution!");
            } else if (selected_vncres.equals(device_res)) {
               logToast("Can't remove device resolution!");
            } else {
                exe.RunAsRoot(new String[]{"sed -i '/^" + selected_vncres + "$/d' " + finalVncResFile});
                reload();
            }
        });
        File finalVncResFile1 = vncResFile;
        addClickListener(BackupVNC, v -> {
            exe.RunAsRoot(new String[]{"cp " + finalVncResFile1 + " " + NhPaths.SD_PATH});
            logToast("Backup successful!");
        });
        File finalVncResFile2 = vncResFile;
        addClickListener(RestoreVNC, v -> {
            String vncbackup = exe.RunAsRootOutput("cat " + NhPaths.SD_PATH + "/vnc-resolutions");
            if (vncbackup.isEmpty()) {
                logToast("Backup file not found!");
            } else {
                exe.RunAsRoot(new String[]{"cp " + NhPaths.SD_PATH + "/vnc-resolutions " + finalVncResFile2});
                reload();
                logToast("Restore successful!");
            }
        });

        logDebug(TAG, "onCreateView: Finished initializing components");
        return rootView;
    }

    // Helper for computing screen size compatibly. Uses WindowMetrics on API 30+,
    // and Resources metrics for older devices (no deprecated Display API usage).
    private static int[] getScreenSizeCompat(Context ctx) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final android.view.WindowMetrics windowMetrics = wm.getCurrentWindowMetrics();
            final android.graphics.Rect bounds = windowMetrics.getBounds();
            return new int[]{bounds.width(), bounds.height()};
        } else {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return new int[]{dm.widthPixels, dm.heightPixels};
        }
    }

    // Helper method to check if sudo is available
    private boolean isSuAvailable() {
        try {
            Process process = Runtime.getRuntime().exec("which su");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();
            if (output != null && output.contains("su")) {
                logDebug("KeXAudio", "su is available.");
                return true;
            } else {
                logDebug("KeXAudio", "su is not available in the environment.");
                return false;
            }
        } catch (IOException e) {
            logDebug("KeXAudio", "Error checking for su availability: " + e.getMessage());
            return false;
        }
    }

    // Helper method to check user permissions
    private boolean checkUserPermissions(String user) {
        if (!isSuAvailable()) return false;  // Return early if su is unavailable

        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", BUSYBOX_NH + " chroot " + NhPaths.CHROOT_PATH() + " sudo -l -U " + user});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output;
            while ((output = reader.readLine()) != null) {
                if (output.contains("NOPASSWD")) {
                    Log.d("KeXAudio", "User " + user + " has NOPASSWD sudo permissions.");
                    return true;
                }
            }
            Log.d("KeXAudio", "User " + user + " does not have NOPASSWD sudo permissions.");
            return false;
        } catch (IOException e) {
            Log.e("KeXAudio", "Error checking permissions for user " + user, e);
            return false;
        }
    }

    private void reload() {
        logDebug(TAG, "reload: Reloading the fragment");
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.container, VNCFragment.newInstance(0))
                .addToBackStack(null)
                .commit();
        logDebug(TAG, "reload: Fragment reloaded");
    }

    private void refreshVNC(View VNCFragment) {
        logDebug(TAG, "refreshVNC: refreshing VNC server status");
        final TextView KeXstatus = VNCFragment.findViewById(R.id.KeXstatus);
        final TextView KeXuser = VNCFragment.findViewById(R.id.KeXuser);
        final Button StartAudioButton = VNCFragment.findViewById(R.id.vnc_audio);

        // Server Status
        ShellExecuter exe = new ShellExecuter();
        String kex_userCmd;
        String kex_statusCmd = exe.RunAsRootOutput("pidof Xtigervnc");
        if (kex_statusCmd.isEmpty()) {
            KeXstatus.setText(R.string.vnc_stopped);
            KeXuser.setText(R.string.vnc_kexuser_none);
        }
        else {
            KeXstatus.setText(R.string.vnc_running);
            kex_userCmd = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd ps -ef | grep vnc | grep Xauthority | awk '{gsub(/home/,\"\")} {gsub(/\\//,\"\")} {gsub(/.Xauthority/,\"\")} {print $1 $9}'");
            KeXuser.setText(kex_userCmd);
        }

        // Users
        File passwd = new File(NhPaths.CHROOT_PATH() + "/etc/passwd");
        String commandUSR = ("echo root && " + BUSYBOX_NH + " awk -F':' -v \"min=" + MIN_UID + "\" -v \"max=" + MAX_UID + "\" '{ if ( ( $3 >= min && $3 <= max ) || ( $3 >= 100000 && $3 <= 101000 ) ) print $0}' " + passwd + " | " + BUSYBOX_NH + " cut -d: -f1");
        String outputUSR = exe.RunAsRootOutput(commandUSR);
        final String[] userArray = outputUSR.split("\n");
        Arrays.sort(userArray);
        Spinner users = VNCFragment.findViewById(R.id.user);
        ArrayAdapter<String> usersadapter = new ArrayAdapter<>(requireContext(),android.R.layout.simple_list_item_1, userArray);
        users.setAdapter(usersadapter);
        SharedPreferences sharedpreferences = context.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);
        posd = sharedpreferences.getInt("display", 0);
        Spinner displays = VNCFragment.findViewById(R.id.display);
        displays.setSelection(posd);
        prevusr = sharedpreferences.getString("user", "");
        posu = usersadapter.getPosition(prevusr);
        users.setSelection(posu);
        users.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                selected_user = parentView.getItemAtPosition(pos).toString();
                sharedpreferences.edit().putString("user", selected_user).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                logDebug(TAG, "refreshVNC: No user selected");
            }
        });
        logDebug(TAG, "refreshVNC: userArray=" + Arrays.toString(userArray));

        // Audio button
        String audio = exe.RunAsRootOutput("pidof pulseaudio");
        if (audio.isEmpty()) StartAudioButton.setText(R.string.vnc_enable_audio2);
        else StartAudioButton.setText(R.string.vnc_disable_audio2);
    }

    private void openResolutionDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        LayoutInflater inflater = this.getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.resolutiondialog, null);
        builder.setView(dialogView);
        builder.setTitle("Add a new device resolution (vertical)");
        final EditText width = dialogView.findViewById(R.id.width);
        final EditText height = dialogView.findViewById(R.id.height);
        final EditText density = dialogView.findViewById(R.id.density);
        File hdmiResFile = new File(NhPaths.APP_SD_FILES_PATH + "/configs/hdmi-resolutions");
        ShellExecuter exe = new ShellExecuter();
        builder.setPositiveButton("Add", (dialog, which) -> {
            final String add_width = width.getText().toString();
            final String add_height = height.getText().toString();
            final String add_density = density.getText().toString();
            if (add_width.isEmpty() || add_height.isEmpty() || add_density.isEmpty()) {
                logToast("Please enter the values!");
                openResolutionDialog();
            } else if (Integer.parseInt(width.getText().toString()) > Integer.parseInt(height.getText().toString())){
                MaterialAlertDialogBuilder builder2 = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
                builder2.setTitle("Width is bigger than height!");
                builder2.setMessage("Bigger width is usually only for tablets. Misconfiguration can render the device unresponsive");
                builder2.setPositiveButton("Keep", (dialog2, which1) -> {
                    exe.RunAsRoot(new String[]{"echo " + add_width + "x" + add_height + ":" + add_density + "ppi >> " + hdmiResFile});
                    reload();
                });
                builder2.setNegativeButton("Back", (dialog2, whichButton) -> openResolutionDialog());
                builder2.show();
            } else {
                exe.RunAsRoot(new String[]{"echo " + add_width + "x" + add_height + ":" + add_density + "ppi >> " + hdmiResFile});
                reload();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, whichButton) -> {
        });
        builder.show();
    }

    private void openVNCResolutionDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        LayoutInflater inflater = this.getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.vncresolutiondialog, null);
        builder.setView(dialogView);
        builder.setTitle("Add a new VNC server resolution (horizontal)");
        final EditText width = dialogView.findViewById(R.id.width);
        final EditText height = dialogView.findViewById(R.id.height);
        File vncResFile = new File(NhPaths.APP_SD_FILES_PATH + "/configs/vnc-resolutions");
        ShellExecuter exe = new ShellExecuter();
        builder.setPositiveButton("Add", (dialog, which) -> {
            final String add_width = width.getText().toString();
            final String add_height = height.getText().toString();
            if (add_width.isEmpty() || add_height.isEmpty()) {
                logToast("Please enter the values!");
                openResolutionDialog();
            } else {
                exe.RunAsRoot(new String[]{"echo " + add_width + "x" + add_height + " >> " + vncResFile});
                reload();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, whichButton) -> {
        });
        builder.show();
    }

    private void confirmDialog() {
        SharedPreferences sharedpreferences = context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        final AlertDialog alert = getAlertDialog(sharedpreferences);
        CountDownTimer resetResolution = new CountDownTimer(15000, 1000) {
            @Override
            public void onTick(long l) {
                alert.setMessage("Resetting device resolution in "+ l/1000 + " sec");
            }
            @Override
            public void onFinish() {
                ShellExecuter exe = new ShellExecuter();
                exe.RunAsRoot(new String[]{"wm size reset; wm density reset"});
                sharedpreferences.edit().putBoolean("confirm_res", false).apply();
            }
        }.start();
        alert.setButton(DialogInterface.BUTTON_POSITIVE,"Keep resolution", (dialog, which) -> {
            sharedpreferences.edit().putBoolean("confirm_res", false).apply();
            alert.cancel();
            resetResolution.cancel();
        });
    }

    @NonNull
    private AlertDialog getAlertDialog(SharedPreferences sharedpreferences) {
        final MaterialAlertDialogBuilder confirmbuilder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        confirmbuilder.setTitle("Do you want to keep the resolution?");
        confirmbuilder.setMessage("Loading..");
        confirmbuilder.setPositiveButton("Keep resolution", (dialogInterface, i) -> {
            sharedpreferences.edit().putBoolean("confirm_res", false).apply();
            dialogInterface.cancel();
        });
        final AlertDialog alert = confirmbuilder.create();
        alert.show();
        return alert;
    }

    private void dbusDialog() {
        final MaterialAlertDialogBuilder dbusbuilder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        ShellExecuter exe = new ShellExecuter();
        dbusbuilder.setMessage("Do you want to stop dbus service? If you have no more sessions opened, press Yes.");
        dbusbuilder.setPositiveButton("Yes", (dialogInterface, i) -> exe.RunAsRoot(new String[]{NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd service dbus stop"}));
        dbusbuilder.setNegativeButton("No", (dialog, whichButton) -> {
        });
        dbusbuilder.show();
    }

    private void desktopDialog() {
        final MaterialAlertDialogBuilder dbusbuilder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        dbusbuilder.setMessage("There's no desktop environment installed. Would you like to install kali-desktop-xfce?");
        dbusbuilder.setPositiveButton("Yes", (dialogInterface, i) -> run_cmd("echo -ne \"\\033]0;Installing XFCE\\007\" && clear;apt update && apt install -y kali-desktop-xfce tigervnc-standalone-server dbus-x11;apt clean; echo 'Done! Exiting..' && sleep 2 && exit"));
        dbusbuilder.setNegativeButton("No", (dialog, whichButton) -> {
        });
        dbusbuilder.show();
    }

    private void addClickListener(Button _button, View.OnClickListener onClickListener) {
        _button.setOnClickListener(onClickListener);
    }

    private void intentClickListener_VNC() {
        try {
            if (getView() == null)
                return;
            Intent intent = context.getPackageManager().getLaunchIntentForPackage("com.offsec.nethunter.kex");
            assert intent != null;
            startActivity(intent);
        } catch (Exception e) {
            Log.d("errorLaunching", e.toString());
            NhPaths.showMessage(context, "NetHunter KeX not found!");
        }
    }

    private void vncLocalClick() {
        logDebug(TAG, "vncLocalClick: Localhost checkbox clicked");
        logToast("vncLocalClick triggered");
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd(String cmd) {
        logDebug(TAG, "run_cmd: Executing command: " + cmd);
        @SuppressLint("SdCardPath") Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
        logDebug(TAG, "run_cmd: Command execution started");
    }

    public void run_cmd_android(String cmd) {
        logDebug(TAG, "run_cmd_android: Executing Android command: " + cmd);
        @SuppressLint("SdCardPath") Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/android-su", cmd);
        activity.startActivity(intent);
        logDebug(TAG, "run_cmd_android: Android command execution started");
    }
}
