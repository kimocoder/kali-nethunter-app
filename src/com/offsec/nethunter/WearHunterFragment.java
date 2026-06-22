package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.bridge.Bridge;

public class WearHunterFragment extends Fragment {
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
        View rootView = inflater.inflate(R.layout.wear_hunter, container, false);
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
                    if (id == R.id.setup) { RunSetup(); return true; }
                    if (id == R.id.update) { RunUpdate(); return true; }
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
        builder.setTitle("Welcome to Wear Hunter!");
        builder.setMessage("In order to make sure everything is working, an initial setup needs to be done.");
        builder.setPositiveButton("Check & Install", (dialog, which) -> {
            RunSetup();
            sharedpreferences.edit().putBoolean("wearhunter_setup_done", true).apply();
        });
        builder.show();
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

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {

            View rootView = inflater.inflate(R.layout.wear_hunter_main, container, false);
            WatchIP = rootView.findViewById(R.id.watch_ip);
            WatchPORT = rootView.findViewById(R.id.watch_port);
            WatchADBCMD = rootView.findViewById(R.id.adb_shell_cmd);
            WatchTEXT = rootView.findViewById(R.id.watch_text);

            String selected_watch_ip = WatchIP.getText().toString().trim();
            String selected_watch_port = WatchPORT.getText().toString().trim();
            String selected_adb_cmd = WatchADBCMD.getText().toString().trim();
            String selected_watch_text = WatchTEXT.getText().toString().trim();

            // First run
            Boolean setupdone = sharedpreferences.getBoolean("wearhunter_setup_done", false);
            if (!setupdone.equals(true))
                SetupDialog();

            // ADB Connect
            Button ADBConnectButton = rootView.findViewById(R.id.button_adb_connect);
            ADBConnectButton.setOnClickListener(v -> {

                if (!selected_watch_ip.isEmpty() && !selected_watch_port.isEmpty()) {
                    run_cmd("adb connect " + selected_watch_ip + ":" + selected_watch_port);
                } else {
                    showToast("Please ensure that Watch IP field is set!");
                }
            });

            // ADB Disonnect
            Button ADBDisonnectButton = rootView.findViewById(R.id.button_adb_disconnect);
            ADBDisonnectButton.setOnClickListener(v -> {
                run_cmd("adb disconnect");
            });

            // Run ADB Command
            Button ADBShellCmdButton = rootView.findViewById(R.id.run_adb);
            ADBShellCmdButton.setOnClickListener(v -> {

                if (!selected_adb_cmd.isEmpty()) {
                    run_cmd("adb shell " + selected_adb_cmd);
                } else {
                    showToast("Please ensure that ADB Command field is set!");
                }
            });

            // Run ADB Command
            Button ADBShellSuCmdButton = rootView.findViewById(R.id.run_as_root_adb);
            ADBShellSuCmdButton.setOnClickListener(v -> {

                if (!selected_adb_cmd.isEmpty()) {
                    run_cmd("adb shell su " + selected_adb_cmd);
                } else {
                    showToast("Please ensure that ADB Command field is set!");
                }
            });

            // Launch NHApp
            Button LaunchNHAppButton = rootView.findViewById(R.id.launch_nh_app);
            LaunchNHAppButton.setOnClickListener(v -> {
                run_cmd("adb shell am start -n com.offsec.nethunter/.AppNavHomeActivity");
            });

            // Launch NHTerm
            Button LaunchNHTermButton = rootView.findViewById(R.id.launch_nh_term);
            LaunchNHTermButton.setOnClickListener(v -> {
                run_cmd("adb shell am start -n com.offsec.nhterm/.ui.term.NeoTermActivity");
            });

            // Write Text on Watch
            Button WriteTextButton = rootView.findViewById(R.id.button_write_text);
            WriteTextButton.setOnClickListener(v -> {

                if (!selected_watch_text.isEmpty()) {
                    run_cmd("adb shell input text \"" + selected_watch_text + "\"");
                } else {
                    showToast("Please ensure that your Watch Text set!");
                }
            });

            // Send Enter KeyEvent
            Button EnterKeyEventButton = rootView.findViewById(R.id.button_enter_key_event);
            EnterKeyEventButton.setOnClickListener(v -> {
                run_cmd("adb shell input keyevent 66");
            });

            // Launch Watch NH Shell Interactive
            Button WatchNHShellButton = rootView.findViewById(R.id.interactive_bootkali);
            WatchNHShellButton.setOnClickListener(v -> {
                run_cmd("adb shell su -c /data/data/com.offsec.nethunter/files/scripts/bootkali");
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
