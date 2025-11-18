package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.io.Files;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.fragment.app.FragmentManager;

public class WifipumpkinFragment extends Fragment {
    private SharedPreferences sharedpreferences;
    private static final String TAG = "WifipumpkinFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private String selected_template;
    private Context context;
    private Activity activity;
    final ShellExecuter exe = new ShellExecuter();

    // Minimal shell-quoting for chroot commands
    private static String shQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String template_src;

    private final ActivityResultLauncher<String> pickZipLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                if (!isWp3Installed()) {
                    NhPaths.showMessage(context, "Run setup first.");
                    return;
                }
                // Keep existing logic, but avoid root sed and bootkali wrapper
                String FilePath = Objects.requireNonNull(uri.getPath());

                // Map DocumentsProvider path to external storage path using NhPaths.SD_PATH
                String sdPath = NhPaths.SD_PATH;
                FilePath = FilePath.replace("/document/primary:", sdPath + "/");

                // List files inside the zip and extract python filename (without .py)
                String FilePy = exe.RunAsChrootOutput(
                        "unzip -Z1 " + shQuote(FilePath) + " | grep .py | awk -F'.' '{print $1}'");

                run_cmd("wifipumpkin3 -x \"use misc.custom_captiveflask; install " + FilePy + " " +  FilePath + "; back; exit\";cp -r /root/.config/wifipumpkin3/config/templates/" + FilePy + " /usr/share/wifipumpkin3/config/templates/; exit");
            });

    public static WifipumpkinFragment newInstance(int sectionNumber) {
        WifipumpkinFragment fragment = new WifipumpkinFragment();
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
        View rootView = inflater.inflate(R.layout.wifipumpkin_hostapd, container, false);
        final Button StartButton = rootView.findViewById(R.id.wp3start_button);
        sharedpreferences = context.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        boolean iswatch = requireContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        CheckBox PreviewCheckbox = rootView.findViewById(R.id.preview_checkbox);

        // First run;
        String packages = exe.RunAsChrootOutput("if [[ -f /usr/bin/wifipumpkin3 || -f /usr/bin/dnschef ]];then echo Good;else echo Nope;fi");

        // if (!setupwp3done.equals(true))
        if (packages.equals("Nope")) SetupDialog();

        // Watch optimisation
        final TextView Wp3desc = rootView.findViewById(R.id.wp3_desc);
        if (iswatch) {
            Wp3desc.setVisibility(View.GONE);
        }

        // Selected iface, name, ssid, bssid, channel, wlan0to1
        final EditText APinterface = rootView.findViewById(R.id.ap_interface);
        final EditText NETinterface = rootView.findViewById(R.id.net_interface);
        final EditText SSID = rootView.findViewById(R.id.ssid);
        final EditText BSSID = rootView.findViewById(R.id.bssid);
        final EditText Channel = rootView.findViewById(R.id.channel);
        final CheckBox Wlan0to1Checkbox = rootView.findViewById(R.id.wlan0to1_checkbox);

        // Templates spinner
        boolean wp3Installed = isWp3Installed();
        if (wp3Installed) {
            refresh_wp3_templates(rootView);
        } else {
            setupTemplatesSpinnerEmpty(rootView);
            SetupDialog();
        }
        Spinner TemplatesSpinner = rootView.findViewById(R.id.templates);

        // Select Template
        WebView myBrowser = rootView.findViewById(R.id.mybrowser);
        final String[] TemplateString = {""};
        TemplatesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @SuppressLint("SetJavaScriptEnabled")
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                selected_template = parentView.getItemAtPosition(pos).toString();
                if (selected_template.equals("None")) {
                    PreviewCheckbox.setChecked(false);
                    PreviewCheckbox.setEnabled(false);
                    TemplateString[0] = "";
                } else {
                    PreviewCheckbox.setEnabled(true);
                    if (selected_template.equals("FlaskDemo")) {
                    template_src = NhPaths.CHROOT_PATH() + "/usr/share/wifipumpkin3/config/templates/" + selected_template + "/templates/En/templates/login.html";
                    } else {
                    template_src = NhPaths.CHROOT_PATH() + "/usr/share/wifipumpkin3/config/templates/" + selected_template + "/templates/login.html";
                    }
                    myBrowser.clearCache(true);
                    myBrowser.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                    myBrowser.getSettings().setDomStorageEnabled(true);
                    myBrowser.getSettings().setLoadsImagesAutomatically(true);
                    myBrowser.getSettings().setJavaScriptEnabled(true); // Enable JavaScript Support
                    myBrowser.setWebViewClient(new WebViewClient());
                    myBrowser.getSettings().setAllowFileAccess(true);
                    String data = exe.RunAsRootOutput("cat " + template_src);
                    //myBrowser.loadDataWithBaseURL("file:///" + NhPaths.CHROOT_PATH() + "/usr/share/wifipumpkin3/config/templates/" + selected_template, data, "text/html", "UTF-8", null);
                    myBrowser.loadUrl(template_src);
                    TemplateString[0] = selected_template;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        // Check iptables version
        if (wp3Installed) {
            checkiptables();
        }

        // Check wlan0 AP mode
        String iwPath;
        String abi = Build.SUPPORTED_ABIS[0];
        if (abi.contains("arm64")) {
            iwPath = NhPaths.APP_SCRIPTS_BIN_PATH + "/iw";
        } else {
            iwPath = NhPaths.APP_SCRIPTS_BIN_PATH + "/iw-armeabi";
        }

        // Fallback to system iw if the binary is not usable
        File iwFile = new File(iwPath);
        if (!iwFile.exists() || !iwFile.canExecute()) {
            Log.w(TAG, "Bundled iw binary not usable, falling back to system iw");
            iwPath = "iw";
        }

        Log.d(TAG, "Using iw binary: " + iwPath);

        // Check wlan0 AP mode using selected iw
        TextView APmode = rootView.findViewById(R.id.wlan0ap);
        String Wlan0AP = exe.RunAsRootOutput(iwPath + " list | grep '* AP'");
        if (Wlan0AP.contains("* AP")) {
            APmode.setText(R.string.wp3_ap_mode_supported);
        } else {
            APmode.setText(R.string.wp3_ap_mode_not_supported);
        }

        // Refresh
        if (wp3Installed) {
            refresh_wp3_templates(rootView);
        }
        ImageButton RefreshTemplates = rootView.findViewById(R.id.refreshTemplates);
        RefreshTemplates.setOnClickListener(v -> refresh_wp3_templates(rootView));

        // Load Settings
        String PrevAPiface = exe.RunAsRootOutput("grep ^APIFACE= " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh | awk -F'=' '{print $2}'");
        APinterface.setText(PrevAPiface);
        String PrevNETiface = exe.RunAsRootOutput("grep ^NETIFACE= " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh | awk -F'=' '{print $2}'");
        NETinterface.setText(PrevNETiface);
        String PrevSSID = exe.RunAsRootOutput("grep ^SSID= " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh | awk -F'=' '{print $2}' | tr -d '\"'");
        SSID.setText(PrevSSID);
        String PrevBSSID = exe.RunAsRootOutput("grep ^BSSID= " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh | awk -F'=' '{print $2}'");
        BSSID.setText(PrevBSSID);
        String PrevChannel = exe.RunAsRootOutput("grep ^CHANNEL= " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh | awk -F'=' '{print $2}'");
        Channel.setText(PrevChannel);
        String PrevWlan0to1 = exe.RunAsRootOutput("grep ^WLAN0TO1= " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh | awk -F'=' '{print $2}'");
        Wlan0to1Checkbox.setChecked(PrevWlan0to1.equals("1"));

        // Wlan0to1 Checkbox
        final String[] Wlan0to1_string = {""};

        // Preview Checkbox
        View PreView = rootView.findViewById(R.id.pre_view);
        PreviewCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                PreView.setVisibility(View.VISIBLE);
            } else {
                PreView.setVisibility(View.GONE);
            }
        });

        // Start
        StartButton.setOnClickListener( v -> {
            if (StartButton.getText().equals("Start")) {
                String APiface_string = APinterface.getText().toString();
                String NETiface_string = NETinterface.getText().toString();
                String SSID_string = SSID.getText().toString();
                String BSSID_string = BSSID.getText().toString();
                String Channel_string = Channel.getText().toString();
                if (Wlan0to1Checkbox.isChecked()) {
                    Wlan0to1_string[0] = "1";
                } else {
                    Wlan0to1_string[0] = "0";
                }
                Toast.makeText(requireActivity().getApplicationContext(), "Starting.. type 'exit' into the terminal to stop Wifipumpkin3", Toast.LENGTH_LONG).show();

                exe.RunAsRoot(new String[]{"sed -i '/^APIFACE=/c\\APIFACE=" + APiface_string + "' " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh"});
                exe.RunAsRoot(new String[]{"sed -i '/^NETIFACE=/c\\NETIFACE=" + NETiface_string + "' " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh"});
                exe.RunAsRoot(new String[]{"sed -i '/^SSID=/c\\SSID=\"" + SSID_string + "\"' " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh"});
                exe.RunAsRoot(new String[]{"sed -i '/^BSSID=/c\\BSSID=" + BSSID_string + "' " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh"});
                exe.RunAsRoot(new String[]{"sed -i '/^CHANNEL=/c\\CHANNEL=" + Channel_string + "' " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh"});
                exe.RunAsRoot(new String[]{"sed -i '/^WLAN0TO1=/c\\WLAN0TO1=" + Wlan0to1_string[0] + "' " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh"});
                exe.RunAsRoot(new String[]{"sed -i '/^TEMPLATE=/c\\TEMPLATE=" + TemplateString[0] + "' " + NhPaths.APP_SD_FILES_PATH + "/modules/start-wp3.sh"});
                run_cmd("echo -ne \"\\033]0;Wifipumpkin3\\007\" && clear;bash /sdcard/nh_files/modules/start-wp3.sh");

            } else if (StartButton.getText().equals("Stop")) {
                exe.RunAsRoot(new String[]{"kill `ps -ef | grep '[btk]_server' | awk {'print $2'}`"});
                exe.RunAsRoot(new String[]{"pkill python3"});
                refresh_wp3_templates(rootView);
            }
        });

        // Load from file
        final Button injectStringButton = rootView.findViewById(R.id.templatebrowse);
        injectStringButton.setOnClickListener(v -> pickZipLauncher.launch("application/zip"));
        return rootView;
    }

    private boolean isWp3Installed() {
        try {
            String out = exe.RunAsChrootOutput(
                    "[[ -f /usr/bin/wifipumpkin3 || -f /usr/bin/dnschef ]] && echo 1 || echo 0");
            String v = (out == null) ? "" : out.trim();
            if ("1".equals(v)) {
                //Log.d(TAG, "isWp3Installed: chroot probe -> true");
                return true;
            }
           // Log.d(TAG, "isWp3Installed: chroot probe -> false (v='" + v + "')");
        } catch (Exception e) {
            //Log.w(TAG, "isWp3Installed: chroot probe failed", e);
        }

        try {
            requireActivity().getPackageManager().getPackageInfo("com.offsec.wifipumpkin3", 0);
            //Log.d(TAG, "isWp3Installed: android package present");
            return true;
        } catch (PackageManager.NameNotFoundException ignore) {
            // no-op
        }

        //Log.d(TAG, "isWp3Installed: not installed");
        return false;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.bt, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.setup || id == R.id.update) {
                    RunSetup();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    // First setup
    public void SetupDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        builder.setTitle("Welcome to Wifipumpkin3!");
        builder.setMessage("You have missing packages. Install them now?");
        builder.setPositiveButton("Install", (dialog, which) -> {
            RunSetup();
            sharedpreferences.edit().putBoolean("wp3_setup_done", true).apply();
        });
        builder.setNegativeButton("Disable message", (dialog, which) -> {
            dialog.dismiss();
            sharedpreferences.edit().putBoolean("wp3_setup_done", true).apply();
        });
        builder.show();
    }

    public void RunSetup() {
        sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        String cmd = "apt update && apt install wifipumpkin3 dnschef -y; wp3;";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("wp3_setup_done", true).apply();
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

    // Refresh templates
    private void refresh_wp3_templates(View root) {
        long t0 = System.currentTimeMillis();
        //Log.d(TAG, "refresh_wp3_templates: start");
        try {
            boolean installed = isWp3Installed();
            //Log.d(TAG, "refresh_wp3_templates: isWp3Installed=" + installed);
            if (!installed) {
                //Log.w(TAG, "refresh_wp3_templates: WP3 not installed, using empty spinner");
                setupTemplatesSpinnerEmpty(root);
                return;
            }

            if (root == null) {
                //Log.e(TAG, "refresh_wp3_templates: root view is null");
                return;
            }

            Spinner spinner = root.findViewById(R.id.templates);
            if (spinner == null) {
                //Log.e(TAG, "refresh_wp3_templates: Spinner R.id.templates not found");
                return;
            }

            String cmd = "ls -1 /root/.config/wifipumpkin3/config/templates | sort";
            //Log.d(TAG, "refresh_wp3_templates: executing chroot cmd: " + cmd);
            String out = exe.RunAsChrootOutput(cmd);
            //Log.d(TAG, "refresh_wp3_templates: rawOut=" + (out == null ? "null" : ("len=" + out.length())));

            String trimmed = (out == null) ? "" : out.trim();
            String outputTemplates = trimmed.isEmpty() ? "None" : "None\n" + trimmed;
            String[] items = outputTemplates.split("\n");
            //Log.d(TAG, "refresh_wp3_templates: itemsCount=" + items.length);

            spinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, items));
            //Log.d(TAG, "refresh_wp3_templates: adapter set");
        } catch (Exception e) {
            Log.e(TAG, "refresh_wp3_templates: error", e);
            try {
                setupTemplatesSpinnerEmpty(root);
            } catch (Exception inner) {
                Log.e(TAG, "refresh_wp3_templates: fallback setup failed", inner);
            }
        } finally {
            Log.d(TAG, "refresh_wp3_templates: done in " + (System.currentTimeMillis() - t0) + "ms");
        }
    }

    private void setupTemplatesSpinnerEmpty(View root) {
        //Log.d(TAG, "setupTemplatesSpinnerEmpty: start");
        if (root == null) {
            //Log.e(TAG, "setupTemplatesSpinnerEmpty: root view is null");
            return;
        }
        Spinner spinner = root.findViewById(R.id.templates);
        if (spinner == null) {
            //Log.e(TAG, "setupTemplatesSpinnerEmpty: Spinner R.id.templates not found");
            return;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, new String[]{"None"});
        spinner.setAdapter(adapter);
        //Log.d(TAG, "setupTemplatesSpinnerEmpty: adapter set to ['None']");
    }

    private void checkiptables() {
        if (!isWp3Installed()) return;
        String iptables_ver = exe.RunAsChrootOutput("iptables -V 2>/dev/null | grep iptables");
        if (iptables_ver.equals("iptables v1.6.2")) {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat);
            builder.setTitle("You need to upgrade iptables!");
            builder.setMessage("We appreciate your patience for using Mana with old iptables. It can be finally upgraded.");
            builder.setPositiveButton("Upgrade", (dialog, which) -> run_cmd("echo -ne \"\\033]0;Upgrading iptables\\007\" && clear;" +
                    "apt-mark unhold libip* > /dev/null 2>&1 ; " +
                    "apt-mark unhold libxtables* > /dev/null 2>&1 ; " +
                    "apt-mark unhold iptables* > /dev/null 2>&1 ; " +
                    "apt install iptables -y && sleep 2 && echo 'Done!"));
            builder.setNegativeButton("Close", (dialog, which) -> {
            });
            builder.show();
        }
    }

    public class HostapdFragmentWPE extends Fragment {
        private Context context;
        private String configFilePath;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
            configFilePath = NhPaths.APP_SD_FILES_PATH + "/configs/hostapd-wpe.conf";
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.mana_hostapd_wpe, container, false);

            Button button = rootView.findViewById(R.id.wpe_updateButton);
            Button gencerts = rootView.findViewById(R.id.wpe_generate_certs);
            loadOptions(rootView);

            // Extracted command as a constant
            final String GENERATE_CERTS_CMD = "cd /etc/hostapd-wpe/certs && ./bootstrap";

            gencerts.setOnClickListener(v -> run_cmd(GENERATE_CERTS_CMD));

            button.setOnClickListener(v -> {
                ShellExecuter exe = new ShellExecuter();
                File file = new File(configFilePath);
                String source;
                try {
                    source = Files.asCharSource(file, StandardCharsets.UTF_8).read();
                } catch (IOException e) {
                    NhPaths.showMessage(context, "Failed to read the configuration file.");
                    Log.e(TAG, "Failed to read hostapd-wpe configuration: " + configFilePath, e);
                    return;
                }

                View view = getView();
                if (view == null) {
                    return;
                }

                EditText ifc = view.findViewById(R.id.wpe_ifc);
                EditText bssid = view.findViewById(R.id.wpe_bssid);
                EditText ssid = view.findViewById(R.id.wpe_ssid);
                EditText channel = view.findViewById(R.id.wpe_channel);
                EditText privatekey = view.findViewById(R.id.wpe_private_key);

                source = updateConfig(source, "interface", ifc.getText().toString());
                source = updateConfig(source, "bssid", bssid.getText().toString());
                source = updateConfig(source, "ssid", ssid.getText().toString());
                source = updateConfig(source, "channel", channel.getText().toString());
                source = updateConfig(source, "private_key_passwd", privatekey.getText().toString());

                exe.SaveFileContents(source, configFilePath);
                NhPaths.showMessage(context, "Source updated");
            });
            return rootView;
        }

        private String updateConfig(String source, String key, String value) {
            return source.replaceAll("(?m)^" + key + "=(.*)$", key + "=" + value);
        }

        private void loadOptions(View rootView) {
            final EditText ifc = rootView.findViewById(R.id.wpe_ifc);
            final EditText bssid = rootView.findViewById(R.id.wpe_bssid);
            final EditText ssid = rootView.findViewById(R.id.wpe_ssid);
            final EditText channel = rootView.findViewById(R.id.wpe_channel);
            final EditText privatekey = rootView.findViewById(R.id.wpe_private_key);

            new Thread(() -> {
                ShellExecuter exe = new ShellExecuter();
                Log.d("exe: ", configFilePath);
                String text = exe.ReadFile_SYNC(configFilePath);

                String regExpatInterface = "^interface=(.*)$";
                Pattern patternIfc = Pattern.compile(regExpatInterface, Pattern.MULTILINE);
                final Matcher matcherIfc = patternIfc.matcher(text);

                String regExpatbssid = "^bssid=(.*)$";
                Pattern patternBssid = Pattern.compile(regExpatbssid, Pattern.MULTILINE);
                final Matcher matcherBssid = patternBssid.matcher(text);

                String regExpatssid = "^ssid=(.*)$";
                Pattern patternSsid = Pattern.compile(regExpatssid, Pattern.MULTILINE);
                final Matcher matcherSsid = patternSsid.matcher(text);

                String regExpatChannel = "^channel=(.*)$";
                Pattern patternChannel = Pattern.compile(regExpatChannel, Pattern.MULTILINE);
                final Matcher matcherChannel = patternChannel.matcher(text);

                String regExpatEnablePrivateKey = "^private_key_passwd=(.*)$";
                Pattern patternEnablePrivateKey = Pattern.compile(regExpatEnablePrivateKey, Pattern.MULTILINE);
                final Matcher matcherPrivateKey = patternEnablePrivateKey.matcher(text);

                ifc.post(() -> {
                /*
                 * Interface
                 */
                    if (matcherIfc.find()) {
                        String ifcValue = matcherIfc.group(1);
                        ifc.setText(ifcValue);
                    }
                /*
                 * bssid
                 */
                    if (matcherBssid.find()) {
                        String bssidVal = matcherBssid.group(1);
                        bssid.setText(bssidVal);
                    }
                /*
                 * ssid
                 */
                    if (matcherSsid.find()) {
                        String ssidVal = matcherSsid.group(1);
                        ssid.setText(ssidVal);
                    }
                /*
                 * channel
                 */
                    if (matcherChannel.find()) {
                        String channelVal = matcherChannel.group(1);
                        channel.setText(channelVal);
                    }
                /*
                 * Private Key File
                 */
                    if (matcherPrivateKey.find()) {
                        String PrivateKeyVal = matcherPrivateKey.group(1);
                        privatekey.setText(PrivateKeyVal);
                    }
                });
            }).start();
        }
    }

    public static class DhcpdFragment extends Fragment {
        final ShellExecuter exe = new ShellExecuter();
        private Context context;
        private String configFilePath;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
            configFilePath = NhPaths.CHROOT_PATH() + "/etc/dhcp/dhcpd.conf";
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            final View rootView = inflater.inflate(R.layout.source_short, container, false);

            String description = getResources().getString(R.string.mana_dhcpd);
            TextView desc = rootView.findViewById(R.id.description);
            desc.setText(description);

            EditText source = rootView.findViewById(R.id.source);
            exe.ReadFile_ASYNC(configFilePath, source);
            Button button = rootView.findViewById(R.id.update);
            button.setOnClickListener(v -> {
                boolean isSaved = exe.SaveFileContents(source.getText().toString(), configFilePath);
                if (isSaved) {
                    NhPaths.showMessage(context, "Source updated");
                } else {
                    NhPaths.showMessage(context, "Source not updated");
                }
            });
            return rootView;
        }
    }

    public static class DnsspoofFragment extends Fragment {
        private Context context;
        private String configFilePath;
        final ShellExecuter exe = new ShellExecuter();

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
            configFilePath = NhPaths.CHROOT_PATH() + "/etc/mana-toolkit/dnsspoof.conf";
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.source_short, container, false);
            String description = getResources().getString(R.string.mana_dnsspoof);
            TextView desc = rootView.findViewById(R.id.description);
            desc.setText(description);

            EditText source = rootView.findViewById(R.id.source);
            exe.ReadFile_ASYNC(configFilePath, source);

            Button button = rootView.findViewById(R.id.update);
            button.setOnClickListener(v -> {
                if (getView() == null) {
                    return;
                }
                EditText source1 = getView().findViewById(R.id.source);
                String newSource = source1.getText().toString();
                exe.SaveFileContents(newSource, configFilePath);
                NhPaths.showMessage(context, "Source updated");
            });
            return rootView;
        }
    }

    public static class ManaNatFullFragment extends Fragment {
        private Context context;
        private String configFilePath;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                configFilePath = NhPaths.CHROOT_PATH() + "/usr/share/mana-toolkit/run-mana/start-nat-full-lollipop.sh";
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.source_short, container, false);
            TextView desc = rootView.findViewById(R.id.description);

            desc.setText(getResources().getString(R.string.mana_nat_full));

            EditText source = rootView.findViewById(R.id.source);
            ShellExecuter exe = new ShellExecuter();
            exe.ReadFile_ASYNC(configFilePath, source);
            Button button = rootView.findViewById(R.id.update);
            button.setOnClickListener(v -> {
                if (getView() == null) {
                    return;
                }
                EditText source1 = getView().findViewById(R.id.source);
                String newSource = source1.getText().toString();
                ShellExecuter exe1 = new ShellExecuter();
                exe1.SaveFileContents(newSource, configFilePath);
                NhPaths.showMessage(context, "Source updated");
            });
            return rootView;
        }
    }

    public static class ManaNatSimpleFragment extends Fragment {
        private Context context;
        private String configFilePath;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                configFilePath = NhPaths.CHROOT_PATH() + "/usr/share/mana-toolkit/run-mana/start-nat-simple-lollipop.sh";
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.source_short, container, false);

            String description = getResources().getString(R.string.mana_nat_simple);
            TextView desc = rootView.findViewById(R.id.description);
            desc.setText(description);

            EditText source = rootView.findViewById(R.id.source);
            ShellExecuter exe = new ShellExecuter();
            exe.ReadFile_ASYNC(configFilePath, source);

            Button button = rootView.findViewById(R.id.update);
            button.setOnClickListener(v -> {
                if (getView() == null) {
                    return;
                }
                EditText source1 = getView().findViewById(R.id.source);
                String newSource = source1.getText().toString();
                ShellExecuter exe1 = new ShellExecuter();
                exe1.SaveFileContents(newSource, configFilePath);
                NhPaths.showMessage(context, "Source updated");
            });
            return rootView;
        }
    }

    public static class ManaNatBettercapFragment extends Fragment {
        private Context context;
        private String configFilePath;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
            configFilePath = NhPaths.CHROOT_PATH() + "/usr/bin/start-nat-transproxy-lollipop.sh";
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.source_short, container, false);

            String description = getResources().getString(R.string.mana_bettercap_description);
            TextView desc = rootView.findViewById(R.id.description);
            desc.setText(description);

            EditText source = rootView.findViewById(R.id.source);
            ShellExecuter exe = new ShellExecuter();
            exe.ReadFile_ASYNC(configFilePath, source);

            Button button = rootView.findViewById(R.id.update);
            button.setOnClickListener(v -> {
                if (getView() == null) {
                    return;
                }
                EditText source1 = getView().findViewById(R.id.source);
                String newSource = source1.getText().toString();
                ShellExecuter exe1 = new ShellExecuter();
                exe1.SaveFileContents(newSource, configFilePath);
                NhPaths.showMessage(context, "Source updated");
            });
            return rootView;
        }
    }

    public static class BdfProxyConfigFragment extends Fragment {
        private Context context;
        private String configFilePath;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
            configFilePath = NhPaths.APP_SD_FILES_PATH + "/configs/bdfproxy.cfg";
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.source_short, container, false);

            String description = getResources().getString(R.string.bdfproxy_cfg);
            TextView desc = rootView.findViewById(R.id.description);
            desc.setText(description);
            // use the good one?
            Log.d("BDFPATH", configFilePath);
            EditText source = rootView.findViewById(R.id.source);
            ShellExecuter exe = new ShellExecuter();
            exe.ReadFile_ASYNC(configFilePath, source);

            Button button = rootView.findViewById(R.id.update);
            button.setOnClickListener(v -> {
                if (getView() == null) {
                    return;
                }
                EditText source1 = getView().findViewById(R.id.source);
                String newSource = source1.getText().toString();
                ShellExecuter exe1 = new ShellExecuter();
                exe1.SaveFileContents(newSource, configFilePath);
                NhPaths.showMessage(context, "Source updated");
            });
            return rootView;
        }
    }

    public static class ManaStartNatSimpleBdfFragment extends Fragment {
        private Context context;
        private String configFilePath;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            context = getContext();
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                configFilePath = NhPaths.CHROOT_PATH() + "/usr/share/mana-toolkit/run-mana/start-nat-simple-bdf-lollipop.sh";
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.source_short, container, false);

            String description = getResources().getString(R.string.mana_nat_simple_bdf);
            TextView desc = rootView.findViewById(R.id.description);
            desc.setText(description);
            EditText source = rootView.findViewById(R.id.source);
            ShellExecuter exe = new ShellExecuter();
            exe.ReadFile_ASYNC(configFilePath, source);
            Button button = rootView.findViewById(R.id.update);
            button.setOnClickListener(v -> {
                if (getView() == null) {
                    return;
                }
                EditText source1 = getView().findViewById(R.id.source);
                String newSource = source1.getText().toString();
                ShellExecuter exe1 = new ShellExecuter();
                exe1.SaveFileContents(newSource, configFilePath);
                NhPaths.showMessage(context, "Source updated");
            });
            return rootView;
        }
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd(String cmd) {
        @SuppressLint("SdCardPath") Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
    }
}
