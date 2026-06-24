package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.BootKali;
import com.offsec.nethunter.utils.ShellExecuter;

public class WearHunterFragment extends Fragment {
    final ShellExecuter exe = new ShellExecuter();

    public static final String TAG = "WearHunterFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private Activity activity;
    private MenuProvider menuProvider;
    private Toast currentToast;
    protected SharedPreferences sharedpreferences;

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
        sharedpreferences = requireContext().getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        activity = getActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.wearhunter, container, false);
        WearHunterFragment.TabsPagerAdapter tabsPagerAdapter = new WearHunterFragment.TabsPagerAdapter(this);
        ViewPager2 mViewPager = rootView.findViewById(R.id.pagerWearHunter);
        mViewPager.setAdapter(tabsPagerAdapter);
        mViewPager.setOffscreenPageLimit(3);
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (isAdded()) {
                    requireActivity().invalidateOptionsMenu();
                }
            }
        });
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!(this instanceof WearHunterFragment.MainFragment)) {
            MenuHost menuHost = requireActivity();
            menuProvider = new MenuProvider() {
                @Override
                public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                    menuInflater.inflate(R.menu.wearhunter, menu);
                }
                @Override
                public boolean onMenuItemSelected(@NonNull MenuItem item) {
                    int id = item.getItemId();
                    if (id == R.id.documentation) { RunDocumentation(); return true; }
                    if (id == R.id.setup) { RunSetup(); return true; }
                    if (id == R.id.update) { RunUpdate(); return true; }
                    if (id == R.id.about) { RunAbout(); return true; }
                    if (id == R.id.adbd_start) { RunADBDStart(); return true; }
                    if (id == R.id.adbd_stop) { RunADBDStop(); return true; }
                    if (id == R.id.adb_start) { RunADBStart(); return true; }
                    if (id == R.id.adb_kill) { RunADBKill(); return true; }

                    return false;
                }
            };
            menuHost.addMenuProvider(menuProvider, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Remove the menu provider to avoid leaks and unexpected behavior
        if (menuProvider != null) {
            MenuHost menuHost = requireActivity();
            menuHost.removeMenuProvider(menuProvider);
            menuProvider = null; // Clear the reference
        }
    }

    public void SetupDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder.setTitle("Welcome to WearHunter!");
        builder.setMessage("In order to make sure everything is working, an initial setup needs to be done.");
        builder.setPositiveButton("Check & Install", (dialog, which) -> {
            RunSetup();
            sharedpreferences.edit().putBoolean("wearhunter_setup_done", true).apply();
        });
        builder.show();
    }

    // Documentation item
    public void RunDocumentation() {
        String url = "https://www.kali.org/docs/nethunter/nethunter-wearhunter/";
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        activity.startActivity(intent);
    }

    public void RunSetup() {
        String cmd = "sudo apt update && sudo apt -y install adb";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("wearhunter_setup_done", true).apply();
    }

    public void RunUpdate() {
        String cmd = "sudo apt update && apt --only-upgrade -y install adb";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("wearhunter_setup_done", true).apply();
    }

    public void RunAbout() {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View dialogView = inflater.inflate(R.layout.wearhunter_about_dialog, null);

        TextView aboutText = dialogView.findViewById(R.id.about_text);

        aboutText.setText(HtmlCompat.fromHtml(
                getString(R.string.about_text), HtmlCompat.FROM_HTML_MODE_LEGACY));
        aboutText.setMovementMethod(LinkMovementMethod.getInstance());

        // Easter egg button setup
        ImageView easterEggButton = dialogView.findViewById(R.id.easter_egg_button);
        MediaPlayer mediaPlayer = MediaPlayer.create(activity, R.raw.secret_alarm);
        final int[] clickCount = {0};

        easterEggButton.setOnClickListener(v -> {
            clickCount[0]++;
            if (clickCount[0] == 3) {
                showToast("Hum??? What's up?");
            }
            if (clickCount[0] == 7) {
                mediaPlayer.start();
                clickCount[0] = 0; // reset after playing sound
            }
        });

        // Create a centered title TextView
        TextView titleView = new TextView(activity);
        titleView.setText(R.string.about_wearhunter);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
        titleView.setPadding(0, padding, 0, padding);

        new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                .setCustomTitle(titleView)
                .setView(dialogView)
                .setNegativeButton("Close", (dialog, id) -> {
                    if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                    mediaPlayer.release();
                    dialog.dismiss();
                })
                .show();
    }

    public void RunADBDStart() {
        exe.RunAsRootOutput("start adbd");
        showToast("ADBD Started!");
    }

    public void RunADBDStop() {
        exe.RunAsRootOutput("stop adbd");
        showToast("ADBD Stopped!");
    }

    public void RunADBStart() {
        String cmd = "adb start-server";
        new BootKali(cmd).run_bg();
        showToast("ADB Server Started!");
    }

    public void RunADBKill() {
        String cmd = "adb kill-server";
        new BootKali(cmd).run_bg();
        showToast("ADB Server Killed!");
    }

    public static class TabsPagerAdapter extends FragmentStateAdapter {
        TabsPagerAdapter(@NonNull Fragment fragment) { super(fragment); }
        @NonNull @Override public Fragment createFragment(int position) { return new WearHunterFragment.MainFragment(); }
        @Override public int getItemCount() { return 1; }
    }

    public static class MainFragment extends WearHunterFragment {
        private TextView WatchIP;
        private TextView WatchPORT;
        private TextView WatchADBCMD;
        private TextView WatchTEXT;
        private TextView WatchAPKPATH;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {

            View rootView = inflater.inflate(R.layout.wearhunter_main, container, false);
            WatchIP = rootView.findViewById(R.id.watch_ip);
            WatchPORT = rootView.findViewById(R.id.watch_port);
            WatchADBCMD = rootView.findViewById(R.id.adb_shell_cmd);
            WatchTEXT = rootView.findViewById(R.id.watch_text);
            WatchAPKPATH = rootView.findViewById(R.id.apk_path_text);

            // First run
            Boolean setupdone = sharedpreferences.getBoolean("wearhunter_setup_done", false);
            if (!setupdone.equals(true))
                SetupDialog();

            // ADB Connect
            Button ADBConnectButton = rootView.findViewById(R.id.button_adb_connect);
            ADBConnectButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty()) {
                    run_cmd("adb connect " + selected_watch_ip + ":" + selected_watch_port);
                } else {
                    showToast("Please ensure that Watch IP/PORT are set!");
                }
            });

            // ADB Disonnect
            Button ADBDisonnectButton = rootView.findViewById(R.id.button_adb_disconnect);
            ADBDisonnectButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty()) {
                    run_cmd("adb disconnect " + selected_watch_ip + ":" + selected_watch_port);
                } else {
                    showToast("Please ensure that Watch IP/PORT are set!");
                }
            });

            // setprop
            Button SetpropButton = rootView.findViewById(R.id.button_adb_setprop);
            SetpropButton.setOnClickListener(v -> {
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_port.isEmpty()) {
                    exe.RunAsRootOutput("setprop service.adb.tcp.port " + selected_watch_port);
                    showToast("Setting property of service.adb.tcp.port to port " + selected_watch_port + "!");
                } else {
                    showToast("Please ensure that Watch PORT are set!");
                }
            });

            // ADB TCPIP
            Button ADBTCPIPButton = rootView.findViewById(R.id.button_adb_tcpip);
            ADBTCPIPButton.setOnClickListener(v -> {
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_port.isEmpty()) {
                    run_cmd("adb tcpip " + selected_watch_port);
                    showToast("Restart ADB in TCP/IP mode!");
                } else {
                    showToast("Please ensure that Watch PORT are set!");
                }
            });

            // ADB Forward adb-hub
            Button ADBForwardHubButton = rootView.findViewById(R.id.button_adb_forward_adbhub);
            ADBForwardHubButton.setOnClickListener(v -> {
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_port.isEmpty()) {
                    run_cmd("adb forward tcp:" + selected_watch_port + " localabstract:/adb-hub");
                    showToast("TCP port " + selected_watch_port + " forwarded to localbastract:/adb-hub!");
                } else {
                    showToast("Please ensure that Watch PORT are set!");
                }
            });

            // Launch NHApp
            Button LaunchNHAppButton = rootView.findViewById(R.id.launch_nh_app);
            LaunchNHAppButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty()) {
                    String launch_nhapp = "adb -s " + selected_watch_ip + ":" + selected_watch_port + " shell am start -n com.offsec.nethunter/.AppNavHomeActivity";
                    new BootKali(launch_nhapp).run_bg();
                    showToast("Spawning Nethunter App on Watch...");
                } else {
                    showToast("Please ensure that Watch IP/PORT are set!");
                }
            });

            // Launch NHTerm
            Button LaunchNHTermButton = rootView.findViewById(R.id.launch_nh_term);
            LaunchNHTermButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty()) {
                    String launch_nhterm = "adb -s " + selected_watch_ip + ":" + selected_watch_port + " shell am start -n com.offsec.nhterm/.ui.term.NeoTermActivity";
                    new BootKali(launch_nhterm).run_bg();
                    showToast("Spawning Nethunter Term on Watch...");
                } else {
                    showToast("Please ensure that Watch IP/PORT are set!");
                }
            });

            // Run ADB Command
            Button ADBShellCmdButton = rootView.findViewById(R.id.run_adb);
            ADBShellCmdButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                String selected_adb_cmd = WatchADBCMD.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty() && !selected_adb_cmd.isEmpty()) {
                    run_cmd("adb -s " + selected_watch_ip + ":" + selected_watch_port + " shell " + selected_adb_cmd);
                } else {
                    showToast("Please ensure that Watch IP/PORT ADB Command are set!");
                }
            });

            // Run ADB Su Command
            Button ADBShellSuCmdButton = rootView.findViewById(R.id.run_as_root_adb);
            ADBShellSuCmdButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                String selected_adb_cmd = WatchADBCMD.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty() && !selected_adb_cmd.isEmpty()) {
                    run_cmd("adb -s " + selected_watch_ip + ":" + selected_watch_port + " shell su -c " + selected_adb_cmd);
                } else {
                    showToast("Please ensure that Watch IP/PORT and ADB Command are set!");
                }
            });

            // Launch Watch NH Shell Interactive
            Button WatchNHShellButton = rootView.findViewById(R.id.interactive_bootkali);
            WatchNHShellButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty()) {
                    run_cmd("adb -s " + selected_watch_ip + ":" + selected_watch_port + " shell -t su -c '/data/data/com.offsec.nethunter/scripts/bootkali'");
                    showToast("Spawning Nethunter Interactive Shell...");
                } else {
                    showToast("Please ensure that Watch IP/PORT are set!");
                }
            });

            // Write Text on Watch
            Button WriteTextButton = rootView.findViewById(R.id.button_write_text);
            WriteTextButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                String selected_watch_text = WatchTEXT.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty() && !selected_watch_text.isEmpty()) {
                    selected_watch_text = selected_watch_text.replace(" ", "%s");
                    String send_text = "adb -s " + selected_watch_ip + ":" + selected_watch_port + " shell input text \"" + selected_watch_text + "\"";
                    new BootKali(send_text).run_bg();
                    showToast("Sending text input on Watch...");
                } else {
                    showToast("Please ensure that your Watch IP/PORT/Text are set!");
                }
            });

            // Send Enter KeyEvent
            Button EnterKeyEventButton = rootView.findViewById(R.id.button_enter_key_event);
            EnterKeyEventButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty()) {
                    String send_enter_keyevent = "adb -s " + selected_watch_ip + ":" + selected_watch_port + " shell input keyevent 66";
                    new BootKali(send_enter_keyevent).run_bg();
                    showToast("Sending Enter KeyEvent on Watch...");
                } else {
                    showToast("Please ensure that Watch IP/PORT are set!");
                }
            });

            // Run ADB Install Apk
            Button ADBInstallButton = rootView.findViewById(R.id.adb_install_apk);
            ADBInstallButton.setOnClickListener(v -> {
                String selected_watch_ip = WatchIP.getText().toString().trim();
                String selected_watch_port = WatchPORT.getText().toString().trim();
                String selected_apk_path = WatchAPKPATH.getText().toString().trim();
                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty() && !selected_apk_path.isEmpty()) {
                    run_cmd("adb -s " + selected_watch_ip + ":" + selected_watch_port + " install " + selected_apk_path);
                } else {
                    showToast("Please ensure that Watch IP/PORT and APK Path are set!");
                }
            });

            return rootView;
        }
    }

    // Simplified Toast function
    public void showToast(String message) {
        if (currentToast != null) {
            currentToast.cancel();
        }
        currentToast = Toast.makeText(requireActivity().getApplicationContext(), message, Toast.LENGTH_LONG);
        currentToast.show();
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd(String cmd) {
        Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
    }
}
