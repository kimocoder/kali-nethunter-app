package com.offsec.nethunter;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.Executor.ChrootManagerExecutor;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.service.CompatCheckService;
import com.offsec.nethunter.service.NotificationChannelService;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.SharePrefTag;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

public class ChrootManagerFragment extends Fragment {
    public static final String TAG = "ChrootManager";
    private MenuProvider menuProvider;
    private static final String ARG_SECTION_NUMBER = "section_number";
    public static final String PRIMARY_IMAGE_SERVER = "kali.download";
    public static final String SECONDARY_IMAGE_SERVER = "image-nethunter.kali.org";
    private static final String IMAGE_DIRECTORY = "/nethunter-images/current/rootfs/";
    private static final String INVALID_PATH_REGEX = "^\\.(.*$)|^\\.\\.(.*$)|^/+(.*$)|^.*/+(.*$)|^$";
    private static final String MINORFULL = "";
    private final Intent backPressedintent = new Intent();
    private TextView mountStatsTextView;
    private TextView baseChrootPathTextView;
    private TextView resultViewerLoggerTextView;
    private Button mountChrootButton;
    private Button unmountChrootButton;
    private Button installChrootButton;
    private Button addMetaPkgButton;
    private Button removeChrootButton;
    private Button backupChrootButton;
    private Button restoreChrootButton;
    private LinearLayout ChrootDesc;
    private ProgressBar taskProgressBar;
    private static SharedPreferences sharedPreferences;
    private ChrootManagerExecutor chrootManagerExecutor;
    private static final int IS_MOUNTED = 0;
    private static final int IS_UNMOUNTED = 1;
    private static final int NEED_TO_INSTALL = 2;
    public static boolean isExecutorRunning = false;
    private Context context;
    private Activity activity;
    private ActivityResultLauncher<Intent> restorePickerLauncher;
    private int currentChrootState = NEED_TO_INSTALL;

    private boolean ensureNhFilesAndBackupDir() {
        try {
            File nh = new File(NhPaths.APP_SD_FILES_PATH);
            File backups = new File(NhPaths.APP_NHFILES_BACKUP_PATH);
            boolean okNh = nh.exists() || nh.mkdirs();
            boolean okBk = backups.exists() || backups.mkdirs();
            if (!okNh || !okBk) {
                if (context != null) {
                    NhPaths.showMessage(context, "Unable to access: " + backups.getAbsolutePath());
                }
                return true;
            }
            return false;
        } catch (Throwable t) {
            if (context != null) NhPaths.showMessage(context, "Storage error: " + t.getMessage());
            return true;
        }
    }

    private String defaultBackupFilename() {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new Date());
        return "kali-chroot-backup-" + ts + ".tar.xz";
    }

    public static ChrootManagerFragment newInstance(int sectionNumber) {
        ChrootManagerFragment fragment = new ChrootManagerFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = getContext();
        activity = getActivity();
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.chroot_manager, container, false);

        if (activity != null) {
            sharedPreferences = activity.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);
        }

        baseChrootPathTextView = rootView.findViewById(R.id.f_chrootmanager_base_path_tv);
        mountStatsTextView = rootView.findViewById(R.id.f_chrootmanager_mountresult_tv);
        resultViewerLoggerTextView = rootView.findViewById(R.id.f_chrootmanager_viewlogger);
        mountChrootButton = rootView.findViewById(R.id.f_chrootmanager_mount_btn);
        unmountChrootButton = rootView.findViewById(R.id.f_chrootmanager_unmount_btn);
        installChrootButton = rootView.findViewById(R.id.f_chrootmanager_install_btn);
        addMetaPkgButton = rootView.findViewById(R.id.f_chrootmanager_addmetapkg_btn);
        removeChrootButton = rootView.findViewById(R.id.f_chrootmanager_removechroot_btn);
        backupChrootButton = rootView.findViewById(R.id.f_chrootmanager_backupchroot_btn);
        restoreChrootButton = rootView.findViewById(R.id.f_chrootmanager_restorechroot_btn);
        taskProgressBar = rootView.findViewById(R.id.f_chrootmanager_progress);
        return rootView;
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        resultViewerLoggerTextView.setMovementMethod(new ScrollingMovementMethod());
        //kaliFolderTextView.setClickable(true);
        if (sharedPreferences != null) {
            baseChrootPathTextView.append(sharedPreferences.getString(SharePrefTag.CHROOT_ARCH_SHAREPREF_TAG, NhPaths.ARCH_FOLDER));
        }
        final LinearLayoutCompat kaliViewFolderlinearLayout = view.findViewById(R.id.f_chrootmanager_viewholder);
        setEditButton();
        setStopKaliButton();
        setStartKaliButton();
        setInstallChrootButton();
        setRemoveChrootButton();
        setAddMetaPkgButton();
        setBackupChrootButton();
        setRestoreChrootButton();

        if (activity != null) {
            SharedPreferences sp = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
            if (sp.getBoolean("running_on_wearos", false)) {
                kaliViewFolderlinearLayout.setVisibility(View.GONE);
            }
        }

        // Add overflow menu with "Check Encryption"
        addOverflowMenu();

        restorePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri fileUri = result.getData().getData();
                        if (context != null && fileUri != null) {
                            String pickedName = getDisplayNameFromUri(context, fileUri);
                            if (pickedName == null || !(pickedName.endsWith(".tar.xz") || pickedName.endsWith(".tar.gz"))) {
                                NhPaths.showMessage(context, "Please select a .tar.xz or .tar.gz archive.");
                                return;
                            }
                            // Ensure external folders exist first
                            if (ensureNhFilesAndBackupDir()) return;
                            File outFile = new File(NhPaths.APP_NHFILES_BACKUP_PATH, pickedName);
                            if (sharedPreferences != null) {
                                sharedPreferences.edit().putString(SharePrefTag.CHROOT_DEFAULT_BACKUP_SHAREPREF_TAG, outFile.getAbsolutePath()).apply();
                            }
                            chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.INSTALL_CHROOT);
                            chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
                                @Override public void onExecutorPrepare() {
                                    showProgress(-1);
                                    context.startService(new Intent(context, NotificationChannelService.class).setAction(NotificationChannelService.INSTALLING));
                                    requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                                    broadcastBackPressedIntent(false);
                                    setAllButtonEnable(false);
                                }
                                @Override public void onExecutorProgressUpdate(int progress) {
                                    showProgress(progress);
                                }
                                @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                                    hideProgress();
                                    broadcastBackPressedIntent(true);
                                    setAllButtonEnable(true);
                                    requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                                    compatCheck();
                                    NhPaths.showMessage(context, "Chroot restored.");
                                    if (mountChrootButton != null) mountChrootButton.performClick();
                                }
                            });
                            resultViewerLoggerTextView.setText("");
                            chrootManagerExecutor.execute(resultViewerLoggerTextView, outFile.getAbsolutePath(), NhPaths.CHROOT_PATH());

                        }
                    }
                }
        );
    }

    private void addOverflowMenu() {
        if (getActivity() == null) return;
        MenuHost menuHost = getActivity();
        // Remove previous provider if any to avoid duplicates
        if (menuProvider != null) {
            menuHost.removeMenuProvider(menuProvider);
        }
        menuProvider = new MenuProvider() {
            @Override public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.chroot_manager, menu);
            }
            @Override public void onPrepareMenu(@NonNull Menu menu) {
                boolean isRunning = currentChrootState == IS_MOUNTED;
                MenuItem backup = menu.findItem(R.id.menu_backup_chroot);
                MenuItem restore = menu.findItem(R.id.menu_restore_chroot);
                // Autostart is now moved to settings
                MenuItem autostart = menu.findItem(R.id.menu_autostart_chroot);
                // Disable when NOT running (also covers not installed); Android grays out disabled items.
                // TODO backup/restore should be available all the time, except backup when chroot is not installed
                //if (backup != null) backup.setEnabled(isRunning);
                //if (restore != null) restore.setEnabled(isRunning);
                if (autostart != null) autostart.setChecked(sharedPreferences.getBoolean(SharePrefTag.CHROOT_AUTOSTART_SHAREPREF_TAG, true));
            }
            @Override public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.menu_check_encryption) {
                    showEncryptionStatusDialog();
                    return true;
                } else if (id == R.id.menu_backup_chroot) {
                    if (unmountChrootButton != null) unmountChrootButton.performClick();
                    if (backupChrootButton != null) backupChrootButton.performClick();
                    return true;
                } else if (id == R.id.menu_restore_chroot) {
                    if (restoreChrootButton != null) restoreChrootButton.performClick();
                    return true;
                } else if (id == R.id.menu_autostart_chroot) {
                    boolean current = sharedPreferences.getBoolean(SharePrefTag.CHROOT_AUTOSTART_SHAREPREF_TAG, true);
                    sharedPreferences.edit().putBoolean(SharePrefTag.CHROOT_AUTOSTART_SHAREPREF_TAG, !current).apply();
                    // Refresh menu
                    if (activity != null) activity.invalidateOptionsMenu();
                    return true;
                }
                return false;
            }
        };
        menuHost.addMenuProvider(menuProvider, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void showEncryptionStatusDialog() {
        Activity act = getActivity();
        if (act == null) return;
        String state = safeGetprop("ro.crypto.state");
        String type = safeGetprop("ro.crypto.type");
        String userdata = safeGetprop("ro.crypto.userdata_block");
        String verity = safeGetprop("ro.boot.verifiedbootstate");

        String friendly;
        if ("encrypted".equalsIgnoreCase(state)) friendly = "Encrypted";
        else if ("unencrypted".equalsIgnoreCase(state)) friendly = "Not encrypted";
        else friendly = "Unknown";

        StringBuilder msg = new StringBuilder();
        msg.append("Data encryption: ").append(friendly).append('\n');
        msg.append("ro.crypto.state: ").append(emptyDash(state)).append('\n');
        msg.append("ro.crypto.type: ").append(emptyDash(type)).append('\n');
        if (!isEmpty(userdata)) msg.append("userdata block: ").append(userdata).append('\n');
        if (!isEmpty(verity)) msg.append("verified boot: ").append(verity).append('\n');

        new MaterialAlertDialogBuilder(act, R.style.DialogStyleCompat)
                .setTitle("Encryption Status")
                .setMessage(msg.toString().trim())
                .setPositiveButton("Close", null)
                .setNegativeButton("Fix", (dialog, which) -> run_cmd_android(NhPaths.APP_SCRIPTS_PATH + "/bootkali_init"))
                .show();
    }

    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private String emptyDash(String s) { return isEmpty(s) ? "-" : s; }

    private String safeGetprop(String key) {
        try {
            // Prefer non-root getprop to avoid su prompts for a simple read
            ShellExecuter sh = new ShellExecuter();
            String v = sh.execute("getprop " + key);
            if (v != null) v = v.trim();
            if (!isEmpty(v)) return v;
            // Fallback to root (some builds restrict getprop)
            v = sh.RunAsRootOutput("getprop " + key);
            return v == null ? "" : v.trim();
        } catch (Exception e) {
            return "";
        }
    }

    @Override public void onStart() {
        super.onStart();
        if (!isExecutorRunning) compatCheck();
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        mountStatsTextView = null;
        baseChrootPathTextView = null;
        resultViewerLoggerTextView = null;
        mountChrootButton = null;
        unmountChrootButton = null;
        installChrootButton = null;
        addMetaPkgButton = null;
        removeChrootButton = null;
        backupChrootButton = null;
        restoreChrootButton = null;
        taskProgressBar = null;
        chrootManagerExecutor = null;
    }

    private void showProgress(int progress) {
        if (taskProgressBar == null) return;
        taskProgressBar.setVisibility(View.VISIBLE);
        if (progress < 0) {
            taskProgressBar.setIndeterminate(true);
        } else {
            taskProgressBar.setIndeterminate(false);
            taskProgressBar.setMax(100);
            taskProgressBar.setProgress(progress);
        }
    }

    private void hideProgress() {
        if (taskProgressBar != null) {
            taskProgressBar.setVisibility(View.GONE);
        }
    }

    private void setEditButton() {
        if (activity == null || sharedPreferences == null) return;
        baseChrootPathTextView.setOnClickListener(v -> {
            MaterialAlertDialogBuilder adb = new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat);
            final AlertDialog ad = adb.create();
            LinearLayout ll = new LinearLayout(activity);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            EditText chrootPathEditText = new EditText(activity);
            TextView available = new TextView(activity);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            p.setMargins(58,0,58,0);
            chrootPathEditText.setLayoutParams(p);
            available.setLayoutParams(p);
            chrootPathEditText.setSingleLine();
            chrootPathEditText.setText(sharedPreferences.getString(SharePrefTag.CHROOT_ARCH_SHAREPREF_TAG, ""));
            available.setTextColor(ContextCompat.getColor(activity, R.color.clearTitle));
            available.setText(String.format(getString(R.string.list_of_available_folders), NhPaths.NH_SYSTEM_PATH));
            File chrootDir = new File(NhPaths.NH_SYSTEM_PATH);
            File[] files = chrootDir.listFiles();
            int idx = 0;
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() && !f.getName().equals("kalifs")) {
                        available.append("    " + (++idx) + ". " + f.getName() + "\n");
                    }
                }
            }
            ll.addView(chrootPathEditText);
            ll.addView(available);
            ad.setTitle("Setup Chroot Path");
            ad.setMessage("The Chroot Path is prefixed to \n\"/data/local/nhsystem/\"");
            ad.setView(ll);
            ad.setButton(DialogInterface.BUTTON_POSITIVE, "Apply", (d,i)->{
                if (chrootPathEditText.getText().toString().matches(INVALID_PATH_REGEX)) {
                    NhPaths.showMessage(activity, "Invalid Name.");
                } else {
                    NhPaths.ARCH_FOLDER = chrootPathEditText.getText().toString();
                    baseChrootPathTextView.setText(getResources().getString(R.string.data_local_nhsystem) + NhPaths.ARCH_FOLDER);
                    sharedPreferences.edit().putString(SharePrefTag.CHROOT_ARCH_SHAREPREF_TAG, NhPaths.ARCH_FOLDER).apply();
                    sharedPreferences.edit().putString(SharePrefTag.CHROOT_PATH_SHAREPREF_TAG, NhPaths.CHROOT_PATH()).apply();
                    new ShellExecuter().RunAsRootOutput("ln -sfn " + NhPaths.CHROOT_PATH() + " " + NhPaths.CHROOT_SYMLINK_PATH);
                    compatCheck();
                }
                d.dismiss();
            });
            ad.show();
        });
    }

    private void setStartKaliButton() {
        mountChrootButton.setOnClickListener(v -> {
            chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.MOUNT_CHROOT);
            chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
                @Override public void onExecutorPrepare() {
                    showProgress(-1);
                    setAllButtonEnable(false);
                }
                @Override public void onExecutorProgressUpdate(int progress) { showProgress(progress); }
                @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                    hideProgress();
                    if (resultCode == 0){
                        setButtonVisibility(IS_MOUNTED);
                        setMountStatsTextView(IS_MOUNTED);
                        setAllButtonEnable(true);
                        compatCheck();
                        context.startService(new Intent(context, NotificationChannelService.class).setAction(NotificationChannelService.USENETHUNTER));
                    }
                }
            });
            resultViewerLoggerTextView.setText("");
            chrootManagerExecutor.execute(resultViewerLoggerTextView);
        });
    }

    private void setStopKaliButton(){
        unmountChrootButton.setOnClickListener(v -> {
            chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.UNMOUNT_CHROOT);
            chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
                @Override public void onExecutorPrepare() {
                    showProgress(-1);
                    setAllButtonEnable(false);
                }
                @Override public void onExecutorProgressUpdate(int progress) { showProgress(progress); }
                @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                    hideProgress();
                    if (resultCode == 0){
                        setMountStatsTextView(IS_UNMOUNTED);
                        setButtonVisibility(IS_UNMOUNTED);
                        setAllButtonEnable(true);
                        compatCheck();
                    }
                }
            });
            resultViewerLoggerTextView.setText("");
            chrootManagerExecutor.execute(resultViewerLoggerTextView);
        });
    }

    private void autoMountChroot() {
        chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.MOUNT_CHROOT);
        chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
            @Override public void onExecutorPrepare() {
                // Auto mount silently, no progress or disable buttons
            }
            @Override public void onExecutorProgressUpdate(int progress) { }
            @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                if (resultCode == 0){
                    currentChrootState = IS_MOUNTED;
                    setButtonVisibility(IS_MOUNTED);
                    setMountStatsTextView(IS_MOUNTED);
                    context.startService(new Intent(context, NotificationChannelService.class).setAction(NotificationChannelService.USENETHUNTER));
                }
            }
        });
        resultViewerLoggerTextView.setText("");
        chrootManagerExecutor.execute(resultViewerLoggerTextView);
    }

    private void setInstallChrootButton() {
        installChrootButton.setOnClickListener(v -> {
            // First-level choice: Download latest image or install from local storage
            CharSequence[] topOptions = new CharSequence[] {
                    getString(R.string.chrootmgr_download_latest),
                    getString(R.string.chrootmgr_install_from_local_storage)
            };
            new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                    .setTitle(R.string.installkalichrootbutton)
                    .setItems(topOptions, (dialog, whichTop) -> {
                        if (whichTop == 0) {
                            String[] variants = {"Minimal", "Full"};
                            new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                                    .setTitle("Select Kali Image")
                                    .setItems(variants, (d2, which) -> {
                                        String arch = getDeviceArch();
                                        String type = (which == 0) ? "minimal" : "full";
                                        String fileName = "kali-nethunter-rootfs-" + type + "-" + arch + ".tar.xz";
                                        // Ensure backup dir exists, and use it as download destination
                                        if (ensureNhFilesAndBackupDir()) return;
                                        File downloadDir = new File(NhPaths.APP_NHFILES_BACKUP_PATH);
                                        File target = new File(downloadDir, fileName);
                                        Runnable run = () -> startDownloadAndRestoreChroot(fileName, downloadDir, type, arch);
                                        if (target.exists()) {
                                            new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                                                    .setTitle("Overwrite File?")
                                                    .setMessage("File exists. Overwrite?")
                                                    .setPositiveButton("Overwrite",(dd,w)->run.run())
                                                    .setNegativeButton("Cancel", null)
                                                    .show();
                                        } else run.run();
                                    })
                                    .show();
                        } else {
                            if (restoreChrootButton != null) restoreChrootButton.performClick();
                        }
                    })
                    .show();
        });
    }

    private void startDownloadAndRestoreChroot(String fileName, File downloadDir, String type, String arch) {
        chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.DOWNLOAD_CHROOT);
        chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
            @Override public void onExecutorPrepare() {
                showProgress(-1);
                requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setAllButtonEnable(false);
            }
            @Override public void onExecutorProgressUpdate(int progress) { showProgress(progress); }
            @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                hideProgress();
                requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setAllButtonEnable(true);
                if (resultCode == 0) {
                    restoreChrootImage(new File(downloadDir, fileName).getAbsolutePath());
                } else {
                    NhPaths.showMessage(context, "Download failed.");
                }
            }
        });
        resultViewerLoggerTextView.setText("");
        String imagePath = IMAGE_DIRECTORY + fileName;
        try {
            chrootManagerExecutor.execute(
                    resultViewerLoggerTextView,
                    PRIMARY_IMAGE_SERVER,
                    imagePath,
                    new File(downloadDir, fileName).getAbsolutePath()
            );
        } catch (Exception e) {
            hideProgress();
            NhPaths.showMessage(context, "Error: " + e.getMessage());
        }
    }

    private void restoreChrootImage(String imagePath) {
        File f = new File(imagePath);
        android.util.Log.d(TAG, "restoreChrootImage: path=" + imagePath +
                " exists=" + f.exists() + " size=" + (f.exists() ? f.length() : -1));
        chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.INSTALL_CHROOT);
        chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
            @Override public void onExecutorPrepare() {
                showProgress(-1);
                requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setAllButtonEnable(false);
            }
            @Override public void onExecutorProgressUpdate(int progress) { showProgress(progress); }
            @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                hideProgress();
                requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setAllButtonEnable(true);
                android.util.Log.d(TAG, "restoreChrootImage finished rc=" + resultCode +
                        " outputLines=" + (resultString == null ? 0 : resultString.size()));
                if (resultString != null && !resultString.isEmpty()) {
                    int from = Math.max(0, resultString.size() - 10);
                    android.util.Log.d(TAG, "Last lines:\n" +
                            android.text.TextUtils.join("\n", resultString.subList(from, resultString.size())));
                }
                if (resultCode == 0) {
                    NhPaths.showMessage(context, "Chroot restored.");
                    compatCheck();
                } else {
                    NhPaths.showMessage(context, "Restore failed (rc=" + resultCode + "). Check logcat tag ChrootMgrExec / ChrootManager.");
                }
            }
        });
        resultViewerLoggerTextView.setText("");
        chrootManagerExecutor.execute(resultViewerLoggerTextView, imagePath, NhPaths.CHROOT_PATH());
    }

    private void setRemoveChrootButton(){
        removeChrootButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                .setTitle("Warning!")
                .setMessage("Remove folder?\n" + NhPaths.CHROOT_PATH())
                .setPositiveButton("I'm sure.", (d,i)-> new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                        .setTitle("Last chance")
                        .setMessage("This cannot be undone.")
                        .setPositiveButton("Delete",(dd,ii)-> {
                            chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.REMOVE_CHROOT);
                            chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
                                @Override public void onExecutorPrepare() {
                                    showProgress(-1);
                                    broadcastBackPressedIntent(false);
                                    setAllButtonEnable(false);
                                }
                                @Override public void onExecutorProgressUpdate(int progress) { showProgress(progress); }
                                @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                                    hideProgress();
                                    broadcastBackPressedIntent(true);
                                    setAllButtonEnable(true);
                                    compatCheck();
                                }
                            });
                            resultViewerLoggerTextView.setText("");
                            chrootManagerExecutor.execute(resultViewerLoggerTextView);
                        })
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Cancel", null)
                .show());
    }

    private void setAddMetaPkgButton() {
        addMetaPkgButton.setOnClickListener(v -> {
            if (activity == null) return;
            // Inflate bottom sheet layout
            LayoutInflater inflater = activity.getLayoutInflater();
            ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
            final View sheet = inflater.inflate(R.layout.chroot_metapackages_bottomsheet, root, false);

            // Wire website button
            View webBtn = sheet.findViewById(R.id.metapackagesWeb);
            if (webBtn != null) {
                webBtn.setOnClickListener(v1 -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://tools.kali.org/kali-metapackages"));
                    startActivity(browserIntent);
                });
            }

            // Prepare bottom sheet dialog
            final com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
            dialog.setContentView(sheet);

            View cancelBtn = sheet.findViewById(R.id.meta_cancel_btn);
            View installBtn = sheet.findViewById(R.id.meta_install_btn);

            if (cancelBtn != null) cancelBtn.setOnClickListener(x -> dialog.dismiss());
            if (installBtn != null) installBtn.setOnClickListener(x -> {
                StringBuilder sb = new StringBuilder();
                LinearLayout list = sheet.findViewById(R.id.metapackageLinearLayout);
                if (list != null) {
                    int children = list.getChildCount();
                    for (int i = 0; i < children; i++) {
                        View child = list.getChildAt(i);
                        if (child instanceof com.google.android.material.checkbox.MaterialCheckBox) {
                            com.google.android.material.checkbox.MaterialCheckBox cb = (com.google.android.material.checkbox.MaterialCheckBox) child;
                            if (cb.isChecked()) {
                                CharSequence txt = cb.getText();
                                if (txt != null && txt.length() > 0) {
                                    sb.append(txt).append(' ');
                                }
                            }
                        }
                    }
                }

                if (sb.length() == 0) {
                    NhPaths.showMessage(context, "Select at least one metapackage.");
                    return;
                }

                try {
                    run_cmd("apt update && apt install " + sb.toString().trim() + " -y && echo \"(You can close the terminal now)\\n\"");
                    dialog.dismiss();
                } catch (Exception e) {
                    NhPaths.showMessage(context, getString(R.string.toast_install_terminal));
                }
            });

            dialog.show();
        });
    }

    private void setBackupChrootButton() {
        backupChrootButton.setOnClickListener(v -> {
            // Ensure external folders exist first
            AlertDialog ad = new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat).create();
            EditText backupFullPathEditText = new EditText(activity);
            LinearLayout ll = new LinearLayout(activity);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            LinearLayout.LayoutParams editTextParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            editTextParams.setMargins(58,40,58,0);
            backupFullPathEditText.setLayoutParams(editTextParams);
            ll.addView(backupFullPathEditText);
            ad.setView(ll);
            ad.setTitle("Backup Chroot");
            ad.setMessage("Backup \"" + NhPaths.CHROOT_PATH() + "\" to:");
            // Default to /sdcard/<timestamped>.tar.xz
            String defaultPath = new File(NhPaths.APP_NHFILES_BACKUP_PATH, defaultBackupFilename()).getAbsolutePath();
            String last = sharedPreferences.getString(SharePrefTag.CHROOT_DEFAULT_BACKUP_SHAREPREF_TAG, defaultPath);
            backupFullPathEditText.setText(last);
            ad.setButton(DialogInterface.BUTTON_POSITIVE, "OK", (dialogInterface, i) -> {
                String out = backupFullPathEditText.getText().toString();
                sharedPreferences.edit().putString(SharePrefTag.CHROOT_DEFAULT_BACKUP_SHAREPREF_TAG, out).apply();
                Runnable runBackup = () -> {
                    chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.BACKUP_CHROOT);
                    chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
                        @Override public void onExecutorPrepare() {
                            showProgress(-1);
                            requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            context.startService(new Intent(context, NotificationChannelService.class).setAction(NotificationChannelService.BACKINGUP));
                            broadcastBackPressedIntent(false);
                            setAllButtonEnable(false);
                        }
                        @Override public void onExecutorProgressUpdate(int progress) { showProgress(progress); }
                        @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                            hideProgress();
                            broadcastBackPressedIntent(true);
                            setAllButtonEnable(true);
                            NhPaths.showMessage(context, "Chroot backup created.");
                            requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        }
                    });
                    resultViewerLoggerTextView.setText("");
                    chrootManagerExecutor.execute(resultViewerLoggerTextView, NhPaths.CHROOT_PATH(), out);
                };
                if (new File(out).exists()) {
                    AlertDialog overwrite = new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                            .setMessage("File exists. Overwrite?")
                            .setPositiveButton("YES",(d1,i1)-> runBackup.run())
                            .setNegativeButton("NO", null)
                            .create();
                    overwrite.show();
                } else runBackup.run();
            });
            ad.show();
        });
    }

    private void showBanner() {
        resultViewerLoggerTextView.setText("");
        chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.ISSUE_BANNER);
        chrootManagerExecutor.execute(resultViewerLoggerTextView, getResources().getString(R.string.aboutchroot));
    }

    private void compatCheck() {
        chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.CHECK_CHROOT);
        chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
            @Override public void onExecutorPrepare() {
                showProgress(-1);
                broadcastBackPressedIntent(false);
            }
            @Override public void onExecutorProgressUpdate(int progress) { showProgress(progress); }
            @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                hideProgress();
                broadcastBackPressedIntent(true);
                currentChrootState = resultCode;
                setButtonVisibility(resultCode);
                setMountStatsTextView(resultCode);
                setAllButtonEnable(true);
                context.startService(new Intent(context, CompatCheckService.class).putExtra("RESULTCODE", resultCode));
                // Refresh menu so Backup/Restore enabled state updates immediately
                if (activity != null) activity.invalidateOptionsMenu();
            }
        });
        resultViewerLoggerTextView.setText("");
        chrootManagerExecutor.execute(resultViewerLoggerTextView, sharedPreferences.getString(SharePrefTag.CHROOT_PATH_SHAREPREF_TAG, ""));
    }

    private void setMountStatsTextView(int MODE) {
        if (MODE == IS_MOUNTED) {
            mountStatsTextView.setTextColor(Color.GREEN);
            mountStatsTextView.setText(R.string.running);
        } else if  (MODE == IS_UNMOUNTED) {
            mountStatsTextView.setTextColor(Color.RED);
            mountStatsTextView.setText(R.string.stopped);
        } else if  (MODE == NEED_TO_INSTALL) {
            resultViewerLoggerTextView.setText("");
            showBanner();
            mountStatsTextView.setTextColor(Color.RED);
            mountStatsTextView.setText(R.string.not_yet_installed);
        }
    }

    private void setButtonVisibility(int MODE) {
        SharedPreferences sp = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        boolean iswatch = sp.getBoolean("running_on_wearos", false);
        switch (MODE) {
            case IS_MOUNTED:
                mountChrootButton.setVisibility(View.GONE);
                unmountChrootButton.setVisibility(View.VISIBLE);
                installChrootButton.setVisibility(View.GONE);
                addMetaPkgButton.setVisibility(iswatch ? View.GONE : View.VISIBLE);
                removeChrootButton.setVisibility(View.GONE);
                // Always keep backup/restore hidden; now accessible via menu
                if (backupChrootButton != null) backupChrootButton.setVisibility(View.GONE);
                if (restoreChrootButton != null) restoreChrootButton.setVisibility(View.GONE);
                break;
            case IS_UNMOUNTED:
                mountChrootButton.setVisibility(View.VISIBLE);
                unmountChrootButton.setVisibility(View.GONE);
                installChrootButton.setVisibility(View.GONE);
                addMetaPkgButton.setVisibility(View.GONE);
                removeChrootButton.setVisibility(View.VISIBLE);
                if (backupChrootButton != null) backupChrootButton.setVisibility(View.GONE);
                if (restoreChrootButton != null) restoreChrootButton.setVisibility(View.GONE);
                break;
            case NEED_TO_INSTALL:
                mountChrootButton.setVisibility(View.GONE);
                unmountChrootButton.setVisibility(View.GONE);
                installChrootButton.setVisibility(View.VISIBLE);
                addMetaPkgButton.setVisibility(View.GONE);
                removeChrootButton.setVisibility(View.GONE);
                if (backupChrootButton != null) backupChrootButton.setVisibility(View.GONE);
                if (restoreChrootButton != null) restoreChrootButton.setVisibility(View.GONE);
                break;
        }
    }

    private void setAllButtonEnable(boolean isEnable) {
        if (mountChrootButton != null) mountChrootButton.setEnabled(isEnable);
        if (unmountChrootButton != null) unmountChrootButton.setEnabled(isEnable);
        if (installChrootButton != null) installChrootButton.setEnabled(isEnable);
        if (addMetaPkgButton != null) addMetaPkgButton.setEnabled(isEnable);
        if (removeChrootButton != null) removeChrootButton.setEnabled(isEnable);
        if (backupChrootButton != null) backupChrootButton.setEnabled(isEnable);
        if (restoreChrootButton != null) restoreChrootButton.setEnabled(isEnable);
    }

    private void broadcastBackPressedIntent(Boolean isEnabled) {
        String pkg = (context != null ? context.getPackageName() : "com.offsec.nethunter");
        backPressedintent.setAction(pkg + ".BACKPRESSED");
        backPressedintent.putExtra("isEnable", isEnabled);
        context.sendBroadcast(backPressedintent);
        updateOptionsMenuState(isEnabled);
    }

    private void initMenuProvider() {
        if (menuProvider != null) return;
        menuProvider = new MenuProvider() {
            @Override public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {}
            @Override public boolean onMenuItemSelected(@NonNull MenuItem item) { return false; }
        };
    }

    private void updateOptionsMenuState(boolean enabled) {
        if (getActivity() == null) return;
        MenuHost host = getActivity();
        initMenuProvider();
        if (enabled) host.addMenuProvider(menuProvider, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        else host.removeMenuProvider(menuProvider);
    }

    private String getDeviceArch() {
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) ? Build.SUPPORTED_ABIS[0] : "arm64";
        if (abi.contains("arm64")) return "arm64";
        if (abi.contains("armeabi")) return "armhf";
        return "arm64";
    }

    private String getDisplayNameFromUri(Context ctx, Uri uri) {
        try (Cursor cursor = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx != -1) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void setRestoreChrootButton() {
        restoreChrootButton.setOnClickListener(v -> {
            File targetDir = new File(NhPaths.CHROOT_PATH());
            boolean hasContent = targetDir.exists() && targetDir.isDirectory() && targetDir.listFiles() != null && Objects.requireNonNull(targetDir.listFiles()).length > 0;
            if (hasContent) {
                new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                        .setTitle("Replace existing chroot?")
                        .setMessage("The target directory is not empty. Remove current chroot before restore?")
                        .setPositiveButton("Remove & Continue", (d, i) -> {
                            chrootManagerExecutor = new ChrootManagerExecutor(ChrootManagerExecutor.REMOVE_CHROOT);
                            chrootManagerExecutor.setListener(new ChrootManagerExecutor.ChrootManagerExecutorListener() {
                                @Override public void onExecutorPrepare() {
                                    showProgress(-1);
                                    requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                                    setAllButtonEnable(false);
                                }
                                @Override public void onExecutorProgressUpdate(int progress) { showProgress(progress); }
                                @Override public void onExecutorFinished(int resultCode, ArrayList<String> resultString) {
                                    hideProgress();
                                    setAllButtonEnable(true);
                                    if (resultCode == 0) {
                                        NhPaths.showMessage(context, "Select backup file");
                                        launchRestorePicker();
                                    } else {
                                        NhPaths.showMessage(context, "Failed to remove existing chroot.");
                                    }
                                    compatCheck();
                                }
                            });
                            resultViewerLoggerTextView.setText("");
                            chrootManagerExecutor.execute(resultViewerLoggerTextView);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                launchRestorePicker();
            }
        });
    }

    private void launchRestorePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-xz", "application/gzip", "application/x-gtar", "application/x-tar"});
        restorePickerLauncher.launch(intent);
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
