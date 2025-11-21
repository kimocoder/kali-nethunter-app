package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KernelFragment extends Fragment implements MenuProvider {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    public static final String TAG = "KernelFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private Activity activity;
    private final ShellExecuter exe = new ShellExecuter();
    private static final String KERNEL_URL = "https://gitlab.com/yesimxev/kali-nethunter-kernels/-/raw/main/kernels.txt";
    @SuppressLint("SdCardPath")
    private static final String PUBLIC_SDCARD_PATH = "/sdcard";

    public static KernelFragment newInstance(int sectionNumber) {
        KernelFragment fragment = new KernelFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.kernel, container, false);
        activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);

        // Check for first run
        android.content.SharedPreferences prefs = requireActivity().getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean("kernel_first_run", true);
        if (isFirstRun) {
            showKernelDescription();
            prefs.edit().putBoolean("kernel_first_run", false).apply();
        }

        // Device information
        final TextView Device = rootView.findViewById(R.id.device);
        final TextView Codename = rootView.findViewById(R.id.codename);
        final TextView Android = rootView.findViewById(R.id.android_ver);
        Device.setText(Build.MODEL);
        Codename.setText(Build.DEVICE);
        Android.setText(Build.VERSION.RELEASE);

        // Custom codename
        final Spinner repoSpinner = rootView.findViewById(R.id.repo_list);
        final Button codenamesearchButton = rootView.findViewById(R.id.custom_search);
        EditText customCodename = rootView.findViewById(R.id.custom_codename);

        final String[] codenamesList = exe.RunAsRootOutput("echo None;curl -s https://nethunter.kali.org/kernels.html | sed -n '/<tr class/{n;p;n;p;}' | sed 's/<[^>]*>//g' | sed 'n;/,/!s/^/- /' | paste - - | awk '!x[$0]++' | tail -n +2").split("\n");
        repoSpinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, codenamesList));

        repoSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                String selected_device = parentView.getItemAtPosition(pos).toString();
                if (!selected_device.equals("None")) {
                    String[] selected_codename = selected_device.split("- ");
                    customCodename.setText(selected_codename[1]);
                } else customCodename.setText("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                // No action needed
            }
        });

        codenamesearchButton.setOnClickListener(v -> {
            String CustomCodename = customCodename.getText().toString();
            checkKernel(CustomCodename);
        });

        // Browse
        final Button kernelbrowseButton = rootView.findViewById(R.id.kernelfilebrowse);
        kernelbrowseButton.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            filePickerLauncher.launch(Intent.createChooser(intent, "Select zip file"));
        });

        // Flash
        Button flashButton = rootView.findViewById(R.id.flash_kernel);
        EditText kernelPath = rootView.findViewById(R.id.kernelpath);
        flashButton.setOnClickListener(v -> {
            String kernelfilepath = kernelPath.getText().toString();
            run_cmd_android(NhPaths.APP_SCRIPTS_PATH + "/bin/magic-flash " + kernelfilepath + " | awk 'gsub(/ui_print /,\" \") && !/^ $/'");
        });

        return rootView;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requireActivity().addMenuProvider(this);
        activity = getActivity();
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        EditText kernelPath = requireActivity().findViewById(R.id.kernelpath);
                        String filePath = Objects.requireNonNull(Objects.requireNonNull(result.getData().getData()).getPath())
                                .replace("/document/primary:", PUBLIC_SDCARD_PATH + "/");
                        kernelPath.setText(filePath);
                    } else {
                        Toast.makeText(requireActivity(), "No file selected", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.kernel, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.kernel_info) {
            showKernelDescription();
            return true;
        }
        return false;
    }

    private void showKernelDescription() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder.setTitle("Kernel Information");
        builder.setMessage(getString(R.string.kernel_description));
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private void checkKernel(String custom) {
        executor.execute(() -> {
            String codename = custom.isEmpty() ? Build.DEVICE : custom.replaceAll("[^a-zA-Z0-9_-]", "");
            String version = Build.VERSION.RELEASE;

            String version_text = getString(version);

            String kernel_zip = exe.RunAsRootOutput("curl -s " + KERNEL_URL + " | grep " + codename + " | grep " + version_text + " || echo NA");
            requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireActivity().getApplicationContext(), "Searching for " + codename + " on Android " + version, Toast.LENGTH_SHORT).show()
            );

            if (!kernel_zip.equals("NA")) {
                requireActivity().runOnUiThread(() -> {
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
                    builder.setTitle("Your device is supported!");
                    builder.setMessage("Do you want to check and flash the available kernel(s)?");
                    builder.setPositiveButton("Ok", (dialog, which) -> {
                        if (kernel_zip.contains("\n")) {
                            final String[] kernelsArray = kernel_zip.split("\n");
                            MaterialAlertDialogBuilder builderInner = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
                            builderInner.setTitle("Multiple kernels available. Please select");
                            builderInner.setItems(kernelsArray, (dialog2, which2) -> {
                                String kernelName = kernelsArray[which2];
                                String cmd = "echo -ne \"\\033]0;Flashing Kernel\\007\" && clear;" +
                                        "echo -e '\\e[34mDownloading " + kernelName + "...\\e[0m';" +
                                        "cd " + PUBLIC_SDCARD_PATH + " && " +
                                        "curl " + KERNEL_URL + "/" + kernelName + " > " + kernelName + "; " +
                                        "echo -e '\\e[32mFlashing kernel...\\e[0m';" +
                                        NhPaths.APP_SCRIPTS_PATH + "/bin/magic-flash " + kernelName + " | awk 'gsub(/ui_print /,\" \") && !/^ $/';" +
                                        "echo -e '\\e[32mKernel flash completed.\\e[0m';";
                                run_cmd_android(cmd);
                                requireActivity().runOnUiThread(() ->
                                        Toast.makeText(requireActivity().getApplicationContext(), "Downloading to /sdcard and flashing...", Toast.LENGTH_SHORT).show()
                                );
                            });
                            builderInner.show();
                        } else {
                            String cmd = "echo -ne \"\\033]0;Flashing Kernel\\007\" && clear;" +
                                    "echo -e '\\e[34mDownloading " + kernel_zip + "...\\e[0m';" +
                                    "cd " + PUBLIC_SDCARD_PATH + " && " +
                                    "curl " + KERNEL_URL + "/" + kernel_zip + " > " + kernel_zip + "; " +
                                    "echo -e '\\e[32mFlashing kernel...\\e[0m';" +
                                    NhPaths.APP_SCRIPTS_PATH + "/bin/magic-flash " + kernel_zip + " | awk 'gsub(/ui_print /,\" \") && !/^ $/';" +
                                    "echo -e '\\e[32mKernel flash completed.\\e[0m';";
                            run_cmd_android(cmd);
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(requireActivity().getApplicationContext(), "Downloading to /sdcard and flashing...", Toast.LENGTH_SHORT).show()
                            );
                        }
                    });
                    builder.setNegativeButton("Cancel", (dialog, which) -> {});
                    builder.show();
                });
            } else {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireActivity().getApplicationContext(), "Codename not found for your Android version. Please download kernel manually", Toast.LENGTH_LONG).show()
                );
            }
        });
    }

    @Nullable
    private static String getString(String version) {
        Map<String, String> versionMap = new HashMap<>();
        versionMap.put("5", "lollipop");
        versionMap.put("6", "marshmallow");
        versionMap.put("7", "nougat");
        versionMap.put("8", "oreo");
        versionMap.put("9", "pie");
        versionMap.put("10", "ten");
        versionMap.put("11", "eleven");
        versionMap.put("12", "twelve");
        versionMap.put("13", "thirteen");
        versionMap.put("14", "fourteen");
        versionMap.put("15", "fifteen");
        versionMap.put("16", "sixteen");
        versionMap.put("17", "seventeen");
        String version_text = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            version_text = versionMap.getOrDefault(version, "unknown");
        }
        return version_text;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        requireActivity().removeMenuProvider(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd(String cmd) {
        @SuppressLint("SdCardPath") Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
    }
    public void run_cmd_android(String cmd) {
        @SuppressLint("SdCardPath") Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/android-su", cmd);
        activity.startActivity(intent);
    }
}
