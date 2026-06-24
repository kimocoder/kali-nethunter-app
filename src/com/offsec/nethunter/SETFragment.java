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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.widget.ViewPager2;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.lang.reflect.Method;
import java.text.MessageFormat;

public class SETFragment extends Fragment {
    protected SharedPreferences sharedpreferences;
    private static final String ARG_SECTION_NUMBER = "section_number";
    private MenuProvider menuProvider;
    private Activity activity;


    public static SETFragment newInstance(int sectionNumber) {
        SETFragment fragment = new SETFragment();
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
        View rootView = inflater.inflate(R.layout.set, container, false);
        TabsPagerAdapter tabsPagerAdapter = new TabsPagerAdapter(this);
        ViewPager2 mViewPager = rootView.findViewById(R.id.pagerSet);
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
        if (!(this instanceof MainFragment)) {
            MenuHost menuHost = requireActivity();
            menuProvider = new MenuProvider() {
                @Override
                public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                    menuInflater.inflate(R.menu.set, menu);
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
        builder.setTitle("Welcome to SET!");
        builder.setMessage("In order to make sure everything is working, an initial setup needs to be done.");
        builder.setPositiveButton("Check & Install", (dialog, which) -> {
            RunSetup();
            sharedpreferences.edit().putBoolean("set_setup_done", true).apply();
        });
        builder.show();
    }

    public void RunSetup() {
        String cmd = "if [ -d /root/setoolkit ]; then echo 'SET is already installed'; else git clone https://github.com/yesimxev/social-engineer-toolkit /root/setoolkit && echo 'Successfully installed SET!'; fi";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("set_setup_done", true).apply();
    }

    public void RunUpdate() {
        String cmd = "if [ -d /root/setoolkit ]; then cd /root/setoolkit && git pull; fi";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("set_setup_done", true).apply();
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

    public static class TabsPagerAdapter extends FragmentStateAdapter {
        TabsPagerAdapter(@NonNull Fragment fragment) { super(fragment); }
        @NonNull @Override public Fragment createFragment(int position) { return new MainFragment(); }
        @Override public int getItemCount() { return 1; }
    }

    public static class MainFragment extends SETFragment {
        final ShellExecuter exe = new ShellExecuter();
        private String selected_template;
        private String template_src;
        private String template_tempfile;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {

            View rootView = inflater.inflate(R.layout.set_main, container, false);
            EditText PhishName = rootView.findViewById(R.id.set_name);
            EditText PhishSubject = rootView.findViewById(R.id.set_subject);

            // First run
            Boolean setupdone = sharedpreferences.getBoolean("set_setup_done", false);
            if (!setupdone.equals(true))
                SetupDialog();

            // Templates spinner
            String[] templates = new String[]{"Messenger", "Facebook", "Twitter"};
            Spinner template_spinner = rootView.findViewById(R.id.set_template);
            template_spinner.setAdapter(new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_list_item_1, templates));

            // Select Template
            WebView myBrowser = rootView.findViewById(R.id.mybrowser);
            // Configure WebView for local file preview
            WebSettings ws = myBrowser.getSettings();
            ws.setAllowFileAccess(true);
            ws.setAllowContentAccess(true);
            ws.setDomStorageEnabled(true);
            ws.setLoadsImagesAutomatically(true);
            ws.setJavaScriptEnabled(true);
            // Avoid direct calls to deprecated APIs; invoke via reflection if available
            enableFileUrlAccess(ws);
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            myBrowser.setWebViewClient(new WebViewClient());

            template_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                    selected_template = parentView.getItemAtPosition(pos).toString();
                    switch (selected_template) {
                        case "Messenger":
                            template_src = NhPaths.APP_SD_FILES_PATH + "/configs/set-messenger.html";
                            template_tempfile = "set-messenger.html";
                            PhishSubject.setText(MessageFormat.format("{0} sent you a message on Messenger.", PhishName.getText()));
                            break;
                        case "Facebook":
                            template_src = NhPaths.APP_SD_FILES_PATH + "/configs/set-facebook.html";
                            template_tempfile = "set-facebook.html";
                            PhishSubject.setText(MessageFormat.format("{0} sent you a message on Facebook.", PhishName.getText()));
                            break;
                        case "Twitter":
                            template_src = NhPaths.APP_SD_FILES_PATH + "/configs/set-twitter.html";
                            template_tempfile = "set-twitter.html";
                            PhishSubject.setText(MessageFormat.format("{0} sent you a Direct Message on Twitter!", PhishName.getText()));
                            break;
                    }
                    myBrowser.clearCache(true);
                    // Prefer external storage file; if not readable (scoped storage), fall back to bundled asset
                    File f = new File(template_src);
                    if (f.canRead()) {
                        myBrowser.loadUrl("file://" + template_src);
                    } else {
                        String assetPath = "file:///android_asset/nh_files/configs/" + template_tempfile;
                        myBrowser.loadUrl(assetPath);
                        Toast.makeText(requireContext(), "Previewing bundled template (no external storage access)", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            Button ResetTemplate = rootView.findViewById(R.id.reset_template);
            Button SaveTemplate = rootView.findViewById(R.id.save_template);
            Button LaunchSET = rootView.findViewById(R.id.start_set);

            // Refresh Template
            Button RefreshPreview = rootView.findViewById(R.id.refreshPreview);
            RefreshPreview.setOnClickListener(v -> refresh(rootView));

            // Reset Template
            ResetTemplate.setOnClickListener(v -> {
                myBrowser.clearCache(true);
                myBrowser.loadUrl("file://" + template_src);
            });

            // Save Template
            SaveTemplate.setOnClickListener(v -> {
                refresh(rootView);
                final String template_path = NhPaths.SD_PATH + "/" + template_tempfile;
                String template_final = "/root/setoolkit/src/templates/" + selected_template + ".template";
                String phish_subject = PhishSubject.getText().toString();
                exe.RunAsChrootOutput("echo 'SUBJECT=\"" + phish_subject + "\"' > " + template_final + " && echo 'HTML=\"' >> " + template_final +
                        " && cat " + template_path + " >> " + template_final + " && echo '\\nEND\"' >> " + template_final);
                Toast.makeText(requireActivity().getApplicationContext(), "Successfully saved to SET templates folder", Toast.LENGTH_SHORT).show();
            });

            // Launch SET
            LaunchSET.setOnClickListener(v -> run_cmd("echo -ne \"\\033]0;SET\\007\" && clear;cd /root/setoolkit && ./setoolkit"));
            return rootView;
        }

        private void enableFileUrlAccess(WebSettings settings) {
            try {
                Method m1 = WebSettings.class.getMethod("setAllowFileAccessFromFileURLs", boolean.class);
                m1.setAccessible(true);
                m1.invoke(settings, true);
            } catch (Throwable ignored) { }
            try {
                Method m2 = WebSettings.class.getMethod("setAllowUniversalAccessFromFileURLs", boolean.class);
                m2.setAccessible(true);
                m2.invoke(settings, true);
            } catch (Throwable ignored) { }
        }

        private void refresh(View SETFragmentView) {
            WebView myBrowser = SETFragmentView.findViewById(R.id.mybrowser);
            final String template_path = NhPaths.SD_PATH + "/" + template_tempfile;

            // Setting fields
            EditText PhishLink = SETFragmentView.findViewById(R.id.set_link);
            EditText PhishName = SETFragmentView.findViewById(R.id.set_name);
            EditText PhishPic = SETFragmentView.findViewById(R.id.set_pic);
            EditText PhishSubject = SETFragmentView.findViewById(R.id.set_subject);

            exe.RunAsRoot(new String[]{"cp " + ShellExecuter.shellEscape(template_src) + " " + ShellExecuter.shellEscape(NhPaths.SD_PATH)});

            String phish_link = PhishLink.getText().toString();
            String phish_name = PhishName.getText().toString();
            String phish_pic = PhishPic.getText().toString();

            switch (selected_template) {
                case "Messenger":
                    PhishSubject.setText(MessageFormat.format("{0} sent you a message on Messenger.", PhishName.getText()));
                    break;
                case "Facebook":
                    PhishSubject.setText(MessageFormat.format("{0} sent you a message on Facebook.", PhishName.getText()));
                    break;
                case "Twitter":
                    PhishSubject.setText(MessageFormat.format("{0} sent you a Direct Message on Twitter!", PhishName.getText()));
                    break;
            }

            if (!phish_link.isEmpty()) {
                // Escape the link for use as a sed replacement string (no shell injection).
                // In sed 's/pattern/replacement/g' the replacement needs & / and \ escaped.
                String escapedLink = phish_link
                        .replace("\\", "\\\\")
                        .replace("&", "\\&")
                        .replace("/", "\\/");
                exe.RunAsRoot(new String[]{"sed -i 's/https\\:\\/\\/www.google.com/" + escapedLink + "/g' " + ShellExecuter.shellEscape(template_path)});
            }
            if (!phish_name.isEmpty()) {
                String escapedName = phish_name
                        .replace("\\", "\\\\")
                        .replace("&", "\\&");
                exe.RunAsRoot(new String[]{"sed -i 's/E Corp/" + escapedName + "/g' " + ShellExecuter.shellEscape(template_path)});
            }
            if (!phish_pic.isEmpty()) {
                // Escape the pic URL for use inside a double-quoted sed replacement
                String escapedPic = phish_pic
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("&", "\\&")
                        .replace("|", "\\|");
                exe.RunAsRoot(new String[]{"sed -i \"s|id=\\\"set\\\".*|id=\\\"set\\\" src=\\\"" + escapedPic + "\\\" width=\\\"72\\\">|\" " + ShellExecuter.shellEscape(template_path)});
            }
            myBrowser.clearCache(true);
            myBrowser.loadUrl("file://" + template_path);
        }
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd(String cmd) {
        Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
    }

    public void run_cmd_inapp(String cmd) {
        // Prefer in-app TerminalFragment to save memory
        if (getActivity() instanceof AppCompatActivity) {
            AppCompatActivity app = (AppCompatActivity) getActivity();
            TerminalFragment tf = TerminalFragment.newInstanceWithCommand(R.id.terminal_item, cmd);
            app.getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.container, tf)
                    .addToBackStack(null)
                    .commitAllowingStateLoss();
        } else {
            // Fallback to legacy bridge if fragment manager is unavailable
            @SuppressLint("SdCardPath") Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
            if (isAdded()) {
                requireActivity().startActivity(intent);
            }
        }
    }
}
