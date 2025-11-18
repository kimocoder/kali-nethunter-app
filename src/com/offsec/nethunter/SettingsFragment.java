package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.PermissionCheck;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class SettingsFragment extends Fragment {
    private static final String TAG = "SettingsFragment";
    private Context context;
    private Activity activity;
    private static final String ARG_SECTION_NUMBER = "section_number";
    private String selected_animation;
    private String selected_prompt;
    private static final String PREF_FILE = "com.offsec.nethunter";
    private SharedPreferences sharedpreferences;

    public SettingsFragment() {
        // Required empty public constructor
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean granted = Environment.isExternalStorageManager();
            Log.d(TAG, "MANAGE_EXTERNAL_STORAGE permission check: " + (granted ? "GRANTED" : "DENIED"));
            return granted;
        } else {
            PermissionCheck.Permissions perms = new PermissionCheck.Permissions();
            boolean granted = PermissionCheck.hasPermissions(requireContext(), perms.STORAGE_PERMISSIONS);
            Log.d(TAG, "READ/WRITE_EXTERNAL_STORAGE permission check: " + (granted ? "GRANTED" : "DENIED"));
            return granted;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(TAG, "Requesting MANAGE_EXTERNAL_STORAGE permission");
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } else {
            Log.d(TAG, "Requesting READ/WRITE_EXTERNAL_STORAGE permissions");
            ActivityResultLauncher<String[]> storagePermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean readGranted = Boolean.TRUE.equals(result.getOrDefault(android.Manifest.permission.READ_EXTERNAL_STORAGE, false));
                        boolean writeGranted = Boolean.TRUE.equals(result.getOrDefault(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, false));
                        if (readGranted && writeGranted) {
                            Log.d(TAG, "READ/WRITE_EXTERNAL_STORAGE permissions granted");
                        } else {
                            Log.d(TAG, "READ/WRITE_EXTERNAL_STORAGE permissions denied");
                            Toast.makeText(requireContext(), "Storage permissions are required for this feature.", Toast.LENGTH_SHORT).show();
                        }
                    });
            storagePermissionLauncher.launch(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            });
        }
    }

    public static SettingsFragment newInstance(int sectionNumber) {
        SettingsFragment fragment = new SettingsFragment();
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
        if (activity != null) {
            sharedpreferences = activity.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.bt, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.setup) {
                    RunSetup();
                    return true;
                } else if (id == R.id.update) {
                    RunUpdate();
                    return true;
                } else {
                    return false;
                }
            }
        }, getViewLifecycleOwner());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (container == null) {
            Log.e("SettingsFragment", "Container is null. Cannot inflate layout.");
            return null;
        }

        final View rootView = inflater.inflate(R.layout.settings, container, false);

        // Check and request MANAGE_EXTERNAL_STORAGE for Android 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasStoragePermission()) {
                requestStoragePermission();
            }
        }

        // First run
        if (sharedpreferences != null && !sharedpreferences.getBoolean("animation_setup_done", false)) {
            SetupDialog();
        }

        // Bootanimation spinner
        String[] animations = new String[]{"Classic", "Burning", "New Kali", "ctOS", "Glitch"};
        Spinner animation_spinner = rootView.findViewById(R.id.animation_spinner);
        animation_spinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, animations));

        // Select Animation
        final String[] animation_dir = {""};
        animation_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                final VideoView videoview = rootView.findViewById(R.id.videoView);
                selected_animation = parentView.getItemAtPosition(pos).toString();
                switch (selected_animation) {
                    case "Classic": {
                        String path = null;
                        if (context != null) {
                            path = "android.resource://" + context.getPackageName() + "/" + R.raw.boot_classic;
                        }
                        videoview.setVideoURI(Uri.parse(path));
                        animation_dir[0] = "src";
                        bootanimation_start();
                        break;
                    }
                    case "Burning": {
                        String path = null;
                        if (context != null) {
                            path = "android.resource://" + context.getPackageName() + "/" + R.raw.boot_mk;
                        }
                        videoview.setVideoURI(Uri.parse(path));
                        animation_dir[0] = "src_mk";
                        bootanimation_start();
                        break;
                    }
                    case "New Kali": {
                        String path = null;
                        if (context != null) {
                            path = "android.resource://" + context.getPackageName() + "/" + R.raw.boot_kali;
                        }
                        videoview.setVideoURI(Uri.parse(path));
                        animation_dir[0] = "src_kali";
                        bootanimation_start();
                        break;
                    }
                    case "ctOS": {
                        String path = null;
                        if (context != null) {
                            path = "android.resource://" + context.getPackageName() + "/" + R.raw.boot_ctos;
                        }
                        videoview.setVideoURI(Uri.parse(path));
                        animation_dir[0] = "src_ctos";
                        bootanimation_start();
                        break;
                    }
                    case "Glitch": {
                        String path = null;
                        if (context != null) {
                            path = "android.resource://" + context.getPackageName() + "/" + R.raw.boot_glitch;
                        }
                        videoview.setVideoURI(Uri.parse(path));
                        animation_dir[0] = "src_glitch";
                        bootanimation_start();
                        break;
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        // Convert Checkbox
        CheckBox ConvertCheckbox = rootView.findViewById(R.id.convert);

        // Image and Final size
        EditText ImageWidth = rootView.findViewById(R.id.image_width);
        EditText ImageHeight = rootView.findViewById(R.id.image_height);
        EditText FinalWidth = rootView.findViewById(R.id.final_width);
        EditText FinalHeight = rootView.findViewById(R.id.final_height);
        final Button ImageResMinus = rootView.findViewById(R.id.imageresminus);
        final Button ImageResPlus = rootView.findViewById(R.id.imageresplus);
        final Button FinalResMinus = rootView.findViewById(R.id.finalresminus);
        final Button FinalResPlus = rootView.findViewById(R.id.finalresplus);
        ImageWidth.setEnabled(false);
        ImageHeight.setEnabled(false);
        ImageResMinus.setEnabled(false);
        ImageResPlus.setEnabled(false);
        ConvertCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                ImageWidth.setEnabled(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ImageWidth.setTextColor(getResources().getColor(R.color.white, null));
                }
                ImageHeight.setEnabled(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ImageHeight.setTextColor(getResources().getColor(R.color.white, null));
                }
                ImageResMinus.setEnabled(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ImageResMinus.setTextColor(getResources().getColor(R.color.white, null));
                }
                ImageResPlus.setEnabled(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ImageResPlus.setTextColor(getResources().getColor(R.color.white, null));
                }
            } else {
                ImageWidth.setEnabled(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ImageWidth.setTextColor(getResources().getColor(R.color.translucent_white, null));
                } else {
                    ImageWidth.setTextColor(Color.parseColor("#40FFFFFF"));
                }
                ImageHeight.setEnabled(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ImageHeight.setTextColor(getResources().getColor(R.color.translucent_white, null));
                } else {
                    ImageHeight.setTextColor(Color.parseColor("#40FFFFFF"));
                }
                ImageResMinus.setEnabled(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ImageResMinus.setTextColor(getResources().getColor(R.color.translucent_white, null));
                } else {
                    ImageResMinus.setTextColor(Color.parseColor("#40FFFFFF"));
                }
                ImageResPlus.setEnabled(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ImageResPlus.setTextColor(getResources().getColor(R.color.translucent_white, null));
                } else {
                    ImageResPlus.setTextColor(Color.parseColor("#40FFFFFF"));
                }
            }
        });
        ImageResMinus.setOnClickListener(v -> {
            String imagewidth = ImageWidth.getText().toString();
            int finalValueIW=Integer.parseInt(imagewidth)-108;
            ImageWidth.setText(String.valueOf(finalValueIW));
            String imageheight = ImageHeight.getText().toString();
            int finalValueIH=Integer.parseInt(imageheight)-192;
            ImageHeight.setText(String.valueOf(finalValueIH));
        });
        ImageResPlus.setOnClickListener(v -> {
            String imagewidth = ImageWidth.getText().toString();
            int finalValueIW=Integer.parseInt(imagewidth)+108;
            ImageWidth.setText(String.valueOf(finalValueIW));
            String imageheight = ImageHeight.getText().toString();
            int finalValueIH=Integer.parseInt(imageheight)+192;
            ImageHeight.setText(String.valueOf(finalValueIH));
        });
        FinalResMinus.setOnClickListener(v -> {
            String finalwidth = FinalWidth.getText().toString();
            int finalValueFW=Integer.parseInt(finalwidth)-108;
            FinalWidth.setText(String.valueOf(finalValueFW));
            String finalheight = FinalHeight.getText().toString();
            int finalValueFH=Integer.parseInt(finalheight)-192;
            FinalHeight.setText(String.valueOf(finalValueFH));
        });
        FinalResPlus.setOnClickListener(v -> {
            String finalwidth = FinalWidth.getText().toString();
            int finalValueFW=Integer.parseInt(finalwidth)+108;
            FinalWidth.setText(String.valueOf(finalValueFW));
            String finalheight = FinalHeight.getText().toString();
            int finalValueFH=Integer.parseInt(finalheight)+192;
            FinalHeight.setText(String.valueOf(finalValueFH));
        });

        // Preview Checkbox
        View PreView = rootView.findViewById(R.id.pre_view);
        CheckBox PreviewCheckbox = rootView.findViewById(R.id.preview_checkbox);
        PreviewCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                PreView.setVisibility(View.VISIBLE);
            } else {
                PreView.setVisibility(View.GONE);
            }
        });

        // Screen size
        int screen_height, screen_width;
        WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics windowMetrics = wm.getCurrentWindowMetrics();
            screen_height = windowMetrics.getBounds().height();
            screen_width = windowMetrics.getBounds().width();
        } else {
            // Use Resources display metrics for legacy devices to avoid deprecated Display APIs
            DisplayMetrics displaymetrics = getResources().getDisplayMetrics();
            screen_height = displaymetrics.heightPixels;
            screen_width = displaymetrics.widthPixels;
        }
        final String size = screen_width + "x" + screen_height;
        final TextView ScreenSize = rootView.findViewById(R.id.screen_size);
        ScreenSize.setText(size);

        // Bootanimation path
        EditText BootanimationPath = rootView.findViewById(R.id.bootanimation_path);
        ShellExecuter exe = new ShellExecuter();
        final String FIND_BOOTANIMATION_CMD = "find /product /vendor /system -name \"*ootanimation.zip\"";
        final String FIND_BOOTANIMATION_MOUNT_CMD = "mount | grep ootanimation";
        String bootanimation_path = exe.RunAsRootOutput(FIND_BOOTANIMATION_CMD);
        String bootanimation_mount = exe.RunAsRootOutput(FIND_BOOTANIMATION_MOUNT_CMD);

        if (bootanimation_path.isEmpty()) {
            BootanimationPath.setText(R.string.settings_bootanimation_path);
        } else {
            BootanimationPath.setText(bootanimation_path);
        }

        if (!bootanimation_mount.isEmpty()) {
            Toast.makeText(requireActivity().getApplicationContext(), "Magisk bootanimation detected", Toast.LENGTH_SHORT).show();
        }

        // Make bootanimation
        Button MakeBootAnimationButton = rootView.findViewById(R.id.make_bootanimation);
        EditText FPS = rootView.findViewById(R.id.fps);
        addClickListener(MakeBootAnimationButton, v -> {
            String resizeCMD;
            String imagesCMD;
            String foldersCMD;
            if (ConvertCheckbox.isChecked()) {
                if (selected_animation != null && selected_animation.equals("Burning")) {
                    foldersCMD = "";
                } else {
                    foldersCMD = " new/part1 new/part2";
                }
                resizeCMD = " -resize " + ImageWidth.getText().toString() + "x" + ImageHeight.getText().toString() + " ";
                imagesCMD = " mkdir -p new/part0" + foldersCMD + " && echo 'Converting images...'" +
                        "&& for i in {0000..0100}; do convert" + resizeCMD + animation_dir[0] + "/part0/$i.jpg new/part0/$i.jpg >/dev/null 2>&1; done; echo '[+] part0 done' " +
                        "&& if [ -d new/part1 ]; then for i in {0000..0200}; do convert" + resizeCMD + animation_dir[0] + "/part1/$i.jpg new/part1/$i.jpg >/dev/null 2>&1; done; fi; echo '[+] part1 done' " +
                        "&& if [ -d new/part2 ]; then for i in {0000..0200}; do convert" + resizeCMD + animation_dir[0] + "/part2/$i.jpg new/part2/$i.jpg >/dev/null 2>&1; done; fi; echo '[+] part2 done' ";
            } else {
                imagesCMD = " mkdir new && cp -r " + animation_dir[0] + "/part* new/";
            }
            String finalRES = FinalWidth.getText().toString() + "x" + FinalHeight.getText().toString();
            String finalFPS = FPS.getText().toString();
            run_cmd("echo -ne \"\\033]0;Building animation\\007\" && clear;cd /root/nethunter-bootanimation &&" + imagesCMD + " && cp " + animation_dir[0] +
                    "/desc.txt new/ && sed -i '1s/.*/" + finalRES + " " + finalFPS +"/' new/desc.txt && sed -i 's/x/ /g' new/desc.txt && cd new && zip -0 -FSr -q /sdcard/bootanimation.zip * && cd .. && rm -r new && echo 'Done. Head back to NetHunter to install the bootanimation!'");
        });

        // Install bootanimation
        Button InstallBootAnimationButton = rootView.findViewById(R.id.set_bootanimation);
        addClickListener(InstallBootAnimationButton, v -> {
            File AnimationZip = new File(NhPaths.SD_PATH + "/bootanimation.zip");
            if (AnimationZip.length() == 0)
                Toast.makeText(requireActivity().getApplicationContext(), "Bootanimation zip is not created!!", Toast.LENGTH_SHORT).show();
            else {
                if (!bootanimation_mount.isEmpty()) {
                    String mount_path = exe.RunAsRootOutput("mount | grep \"media/bootanimation\" | awk {'print $3'}");
                    run_cmd_android("echo -ne \"\\033]0;Installing animation\\007\" && clear;grep ' / ' /proc/mounts | grep -qv 'rootfs' || grep -q ' /system_root ' /proc/mounts && SYSTEM=/ || SYSTEM=/system " +
                            "&& mount -o rw,remount " + mount_path + " && cp " + NhPaths.SD_PATH + "/bootanimation.zip " + BootanimationPath.getText().toString() + " " +
                            "&& echo 'Done. Please reboot to check the result!'");
                } else {
                    run_cmd_android("echo -ne \"\\033]0;Installing animation\\007\" && clear;grep ' / ' /proc/mounts | grep -qv 'rootfs' || grep -q ' /system_root ' /proc/mounts && SYSTEM=/ || SYSTEM=/system " +
                            "&& mount -o rw,remount $SYSTEM && cp " + NhPaths.SD_PATH + "/bootanimation.zip " + BootanimationPath.getText().toString() + " " +
                            "&& echo 'Done. Please reboot to check the result!'");
                }
            }
        });

        // Chroot autostart
        Button ChrootButton = rootView.findViewById(R.id.chroot_toogle);
        Boolean chroot_auto = sharedpreferences.getBoolean("chroot_autostart_enabled", true);
        if (chroot_auto) ChrootButton.setText("Disable");
        else ChrootButton.setText("Enable");

        addClickListener(ChrootButton, v -> {
            if (chroot_auto) {
                sharedpreferences.edit().putBoolean("chroot_autostart_enabled", false).apply();
                ChrootButton.setText("Enable");
                Toast.makeText(requireActivity().getApplicationContext(), "Chroot autostart disabled" , Toast.LENGTH_SHORT).show();
            }
            else {
                sharedpreferences.edit().putBoolean("chroot_autostart_enabled", true).apply();
                ChrootButton.setText("Disable");
                Toast.makeText(requireActivity().getApplicationContext(), "Chroot autostart enabled" , Toast.LENGTH_SHORT).show();
            }
        });

        // In app terminal
        Button TermButton = rootView.findViewById(R.id.inapp_term_toogle);
        Boolean inappterm = sharedpreferences.getBoolean("inapp_terminal_enabled", false);
        if (inappterm) TermButton.setText("Disable");
        addClickListener(TermButton, v -> {
            if (inappterm) {
                sharedpreferences.edit().putBoolean("inapp_terminal_enabled", false).apply();
                TermButton.setText("Enable");
                Toast.makeText(requireActivity().getApplicationContext(), "Done! Restart the app to take effect" , Toast.LENGTH_SHORT).show();
            }
            else {
                sharedpreferences.edit().putBoolean("inapp_terminal_enabled", true).apply();
                TermButton.setText("Disable");
                Toast.makeText(requireActivity().getApplicationContext(), "Done! Restart the app to take effect" , Toast.LENGTH_SHORT).show();
            }
        });

        // Backup
        Button BackupButton = rootView.findViewById(R.id.backup);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        addClickListener(BackupButton, v -> {
            String currentDateandTime = sdf.format(new Date());
            exe.RunAsRoot(new String[]{"cd " + NhPaths.SD_PATH + "/nh_files && tar -czvf /sdcard/nh_files_" + currentDateandTime +".tar *"});
            Toast.makeText(requireActivity().getApplicationContext(), "Backup has been saved to /sdcard/nh_files_" + currentDateandTime , Toast.LENGTH_LONG).show();
        });

        // Restore
        final EditText RestoreFileName = rootView.findViewById(R.id.restorefilename);
        final Button RestoreFileBrowse = rootView.findViewById(R.id.restorefilebrowse);
        RestoreFileBrowse.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/x-tar");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            filePickerLauncher.launch(Intent.createChooser(intent, "Select archive file"));
        });

        final Button RestoreButton = rootView.findViewById(R.id.restore);
        RestoreButton.setOnClickListener( v -> {
            String RestoreFilePath = RestoreFileName.getText().toString();
            File RestoreFile = new File(RestoreFilePath);
            if (RestoreFile.length() == 0) {
                Toast.makeText(requireActivity().getApplicationContext(), "Select a backup file to restore!", Toast.LENGTH_SHORT).show();
            } else {
                exe.RunAsRoot(new String[]{"rm -r " + NhPaths.SD_PATH + "/nh_files/* && tar -xvf " + RestoreFilePath + " -C " + NhPaths.SD_PATH + "/nh_files/"});
                Toast.makeText(requireActivity().getApplicationContext(), "nh_files has been successfully restored", Toast.LENGTH_SHORT).show();
            }
        });

        // Uninstall
        final Button UninstallButton = rootView.findViewById(R.id.uninstall_nh);
        File NhSystemApp = new File("/system/app/NetHunter/NetHunter.apk");
        addClickListener(UninstallButton, v -> {
        if (NhSystemApp.length() == 0) {
            Toast.makeText(requireActivity().getApplicationContext(), "NetHunter was not flashed as system app! Please remove it from Android settings.", Toast.LENGTH_LONG).show();
        } else {
            run_cmd_android("echo -ne \"\\033]0;Uninstalling NetHunter\\007\" && clear;grep ' / ' /proc/mounts | grep -qv 'rootfs' || grep -q ' /system_root ' /proc/mounts && SYSTEM=/ || SYSTEM=/system " +
                        "&& mount -o rw,remount $SYSTEM && rm " + NhSystemApp + " && pm clear com.offsec.nethunter && echo 'Done! Reboot your device to complete the process.'");
                }
        });

        // SELinux
        CheckBox SELinuxOnBoot = rootView.findViewById(R.id.selinuxonboot);
        final boolean set_selinux_permissive_on_boot = sharedpreferences.getBoolean("SELinuxOnBoot", true);
        SELinuxOnBoot.setChecked(set_selinux_permissive_on_boot);
        SELinuxOnBoot.setOnCheckedChangeListener((btn, value) -> sharedpreferences.edit().putBoolean("SELinuxOnBoot", value).apply());

        TextView SELinux = rootView.findViewById(R.id.selinux);
        final String selinux_status = exe.RunAsRootOutput("getenforce");
        SELinux.setText(selinux_status);
        final Button SELinuxButton = rootView.findViewById(R.id.selinux_toggle);
        if (selinux_status.equals("Permissive")) SELinuxButton.setText(R.string.settings_set_enforcing);
        else if (selinux_status.equals("Disabled")) {
            SELinuxButton.setText(R.string.settings_selinux_disabled);
            SELinuxButton.setEnabled(false);
            SELinuxButton.setTextColor(Color.parseColor("#40FFFFFF"));
        }
        else SELinuxButton.setText(R.string.settings_set_to_permissive);

        SELinuxButton.setOnClickListener( v -> {
            String selinux_status_now = exe.RunAsRootOutput("getenforce");
            if (selinux_status_now.equals("Permissive")) {
                exe.RunAsRoot(new String[]{"setenforce 1"});
                SELinuxButton.setText(R.string.settings_set_to_permissive);
                SELinux.setText(R.string.settings_enforcing);
                Toast.makeText(requireActivity().getApplicationContext(), "SElinux set to Enforcing done", Toast.LENGTH_SHORT).show();
                sharedpreferences.edit().putBoolean("SElinux", true).apply();
           } else {
                exe.RunAsRoot(new String[]{"setenforce 0"});
                SELinuxButton.setText(R.string.settings_set_to_enforcing);
                SELinux.setText(R.string.settings_permissive);
                Toast.makeText(requireActivity().getApplicationContext(), "SElinux set to Permissive done", Toast.LENGTH_SHORT).show();
                sharedpreferences.edit().putBoolean("SElinux", false).apply();
            }
        });

        // Terminal style
        TextView TerminalStyle = rootView.findViewById(R.id.prompt_type);
        String current_prompt = exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd grep -m1 'PROMPT_ALTERNATIVE=' /root/.zshrc | cut -d = -f 2 | tail -1");
        TerminalStyle.setText(current_prompt);

        // Prompt spinner
        Spinner PromptSpinner = rootView.findViewById(R.id.prompt_spinner);
        String[] Prompts = new String[]{"oneline", "twoline", "backtrack"};
        PromptSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, Prompts));

        // Select prompt
        PromptSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int pos, long id) {
                selected_prompt = parentView.getItemAtPosition(pos).toString();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                selected_prompt = "oneline"; // Default selection
            }
        });

        // Apply prompt
        final Button ApplyPromptButton = rootView.findViewById(R.id.apply_prompt);
        ApplyPromptButton.setOnClickListener( v -> {
            exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_PATH + "/bootkali custom_cmd sed -i '0,/.*PROMPT_ALTERNATIVE=.*/s//PROMPT_ALTERNATIVE=" + selected_prompt + "/' /root/.zshrc");
            Toast.makeText(requireActivity().getApplicationContext(), "Zsh terminal prompt style has been successfully changed", Toast.LENGTH_SHORT).show();
            TerminalStyle.setText(selected_prompt);
        });

        return rootView;
    }

    private void bootanimation_start() {
        final VideoView videoview = requireActivity().findViewById(R.id.videoView);
        videoview.requestFocus();
        videoview.setOnPreparedListener(mp -> {
            videoview.start();
            mp.setLooping(true);
        });
    }

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    ShellExecuter exe = new ShellExecuter();
                    EditText RestoreFileName = requireActivity().findViewById(R.id.restorefilename);
                    String FilePath = Objects.requireNonNull(result.getData().getData()).getPath();
                    FilePath = exe.RunAsRootOutput("echo " + FilePath + " | sed -e 's/\\/document\\/primary:/\\/sdcard\\//g' ");
                    RestoreFileName.setText(FilePath);
                }
            }
    );

    public void SetupDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat);
        builder.setTitle("Welcome to Settings!");
        builder.setMessage("In order to make sure everything is working, an initial setup needs to be done.");
        builder.setPositiveButton("Check & Install", (dialog, which) -> {
            RunSetup();
            sharedpreferences.edit().putBoolean("animation_setup_done", true).apply();
        });
        builder.show();
    }

    public void RunSetup() {
        // Route through in-app TerminalFragment to save memory; fallback to NhTerm bridge if needed
        String cmd = "if [ -f /usr/bin/convert ];then echo 'Imagemagick is installed!'; else " +
                "apt update && apt install make imagemagick -y;fi; if [ -f /root/nethunter-bootanimation ];then echo 'nethunter-bootanimation is installed!'; else " +
                "git clone https://gitlab.com/kalilinux/nethunter/build-scripts/kali-nethunter-bootanimation /root/nethunter-bootanimation;fi; echo 'Everything is ready!'";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("animation_setup_done", true).apply();
    }

    public void RunUpdate() {
        // Route through in-app TerminalFragment to save memory; fallback to NhTerm bridge if needed
        String cmd = "apt update && apt install make imagemagick -y;if [ -d /root/nethunter-bootanimation ];then cd /root/nethunter-bootanimation;git pull" +
                ";fi;";
        run_cmd(cmd);
        sharedpreferences.edit().putBoolean("animation_setup_done", true).apply();
    }

    // Helper: open TerminalFragment with an initial command; if not possible, fallback to legacy bridge
    private void run_cmd_inapp(@NonNull String cmd) {
        Activity act = getActivity();
        try {
            if (act instanceof AppCompatActivity) {
                AppCompatActivity app = (AppCompatActivity) act;
                TerminalFragment tf = TerminalFragment.newInstanceWithCommand(R.id.terminal_item, cmd);
                app.getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.container, tf)
                        .addToBackStack(null)
                        .commitAllowingStateLoss();
                return;
            }
        } catch (Throwable t) {
            Log.d(TAG, "openTerminalWithCommand fallback due to: " + t.getMessage());
        }
        // Fallback to previous behavior using NhTerm bridge
        run_cmd(cmd);
    }

    private void addClickListener(Button _button, View.OnClickListener onClickListener) {
        _button.setOnClickListener(onClickListener);
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
