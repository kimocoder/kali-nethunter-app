package com.offsec.nethunter.Executor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.offsec.nethunter.AppNavHomeActivity;
import com.offsec.nethunter.R;
import com.offsec.nethunter.utils.CheckForRoot;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.SharePrefTag;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CopyBootFilesExecutor {
    public static final String TAG = "CopyBootFilesExecutor";
    private String objects = "";
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private String lastMessage = "";
    private WeakReference<TextView> progressMessageRef = new WeakReference<>(null);
    private final Runnable progressRunnable = () -> {
        // Update dialog message (UI) and log to logcat
        updateProgressDialogMessage(lastMessage);
        logDebug(TAG, "Progress: " + lastMessage);
    };
    private final String buildTime;
    private Boolean shouldRun;
    private final Activity activity;
    private WeakReference<AlertDialog> progressDialogRef;
    private CopyBootFilesExecutorListener listener;
    private static final String result = "";
    private final SharedPreferences prefs;
    private final ShellExecuter exe = new ShellExecuter();
    private final WeakReference<Context> context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AssetManager assetManager;
    public final int NH_SYSTEM_LOGGING = 0;

    private void logDebug(String tag, String message, Throwable throwable) {
        return;
    }

    private void logToast(String message) {
        mainHandler.post(() ->
                Toast.makeText(requireActivity().getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    private Context requireActivity() {
        Context ctx = context.get();
        if (ctx == null) {
            throw new IllegalStateException("Context reference is null. Ensure the executor is initialized with a valid context.");
        }
        return ctx;
    }

    public CopyBootFilesExecutor(Context context, Activity activity) {
        this.context = new WeakReference<>(context);
        this.activity = activity;
        this.progressDialogRef = new WeakReference<>(null);
        this.assetManager = context.getAssets();
        this.prefs = context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd KK:mm:ss a zzz", Locale.getDefault());
        this.buildTime = sdf.format(new Date());
        this.shouldRun = true;
    }

    public void execute() {
        mainHandler.post(this::onPreExecute);
        executor.execute(() -> {
            String res = doInBackground();
            mainHandler.post(() -> onPostExecute(res));
        });
    }

    private void onPreExecute() {
        boolean filesCopied = prefs.getBoolean("files_copied", false);
        if (!filesCopied) {
            logDebug(TAG, "COPYING NEW FILES", null);
            // Inflate and show a Material-styled progress dialog
            android.view.View content = activity.getLayoutInflater().inflate(
                    com.offsec.nethunter.R.layout.dialog_progress_material, null, false);
            TextView msgView = content.findViewById(com.offsec.nethunter.R.id.progress_message);
            TextView titleView = content.findViewById(com.offsec.nethunter.R.id.progress_title);
            CircularProgressIndicator spinner = content.findViewById(com.offsec.nethunter.R.id.progress_indicator);
            if (spinner != null) {
                spinner.setIndeterminate(true);
                spinner.show();
            }
            if (titleView != null) titleView.setText(R.string.new_app_build_detected);
            if (msgView != null) msgView.setText(R.string.copying_new_files);
            this.progressMessageRef = new WeakReference<>(msgView);

            AlertDialog dialog = new MaterialAlertDialogBuilder(activity, com.offsec.nethunter.R.style.DialogStyleCompat)
                    .setView(content)
                    .setCancelable(false)
                    .create();
            dialog.show();
            setProgressDialog(dialog);
        } else {
            logDebug(TAG, "NO NEW FILES TO COPY. Skipping file copy.", null);
            shouldRun = false;
        }
        if (listener != null) {
            listener.onExecutorPrepare();
        }
    }

    private void setProgressDialog(AlertDialog dialog) {
        this.progressDialogRef = new WeakReference<>(dialog);
    }

    private void logDebug(String message) {
        logDebug(TAG, message, null);
    }

    private String doInBackground() {
        if (!shouldRun) {
            return result;
        }
        if (!CheckForRoot.isRoot()) {
            prefs.edit().putBoolean(AppNavHomeActivity.CHROOT_INSTALLED_TAG, false).apply();
            return "Root permission is required!!";
        } else {
            logDebug("Proceeding with copy and symlink operations.");
        }

        logDebug("COPYING FILES....");
        publishProgress("Copying scripts and updating app files...");
        copyAssetFolder("etc/init.d", NhPaths.APP_INITD_PATH);
        copyAssetFolder("scripts", NhPaths.APP_SCRIPTS_PATH);
        copyAssetFolder("nh_files", NhPaths.APP_NHFILES_PATH);

        publishProgress("Fixing permissions for new files");
        setPermissions(NhPaths.APP_SCRIPTS_PATH, NhPaths.APP_INITD_PATH);

        // Ensure busybox_nh exists and is executable before any subsequent usage
        ensureBusyboxNh();

        publishProgress("Checking for encrypted /data....");
        CheckEncrypted();

        publishProgress("Checking for bootkali symlinks....");
        SymlinkScriptsToSystemBin();
        Symlink("bootkali");
        Symlink("bootkali_bash");
        Symlink("bootkali_init");
        Symlink("bootkali_login");
        Symlink("killkali");
        Symlink("busybox_nh");
        Symlink("curl");
        Symlink("iw");

        disableMagiskNotification();

        prefs.edit()
                .putBoolean("files_copied", true)
                .putString(TAG, buildTime)
                .putInt(SharePrefTag.VERSION_CODE_TAG, getVersionCodeSafe())
                .apply();

        publishProgress("Checking for chroot....");
        String command = "if [ -d " + NhPaths.CHROOT_PATH() + " ];then echo 1; fi";
        if ("1".equals(exe.RunAsRootOutput(command))) {
            prefs.edit().putBoolean(AppNavHomeActivity.CHROOT_INSTALLED_TAG, true).apply();
            publishProgress("Chroot Found!");
            publishProgress(exe.RunAsRootOutput(
                    NhPaths.BUSYBOX + " mount -o remount,suid /data && chmod +s " +
                            NhPaths.CHROOT_PATH() + "/usr/bin/sudo" +
                            " && echo \"Initial setup done!\""));
        } else {
            publishProgress("Chroot not Found, install it in Chroot Manager");
        }

        publishProgress("Installing additional apps....");
        installApks(NhPaths.APP_SD_FILES_PATH + "/cache/apk/");

        // After installing APKs, ensure we can write to external storage and sync nh_files to SD
        if (!checkStoragePermission()) {
            return "Permission required to manage external storage.";
        }
        publishProgress("Syncing nh_files to /sdcard/nh_files ...");
        syncNhFilesToSdcard();

        File nhFilesDir = new File(NhPaths.SD_PATH, "nh_files");
        if (nhFilesDir.exists() && nhFilesDir.isDirectory()) {
            logDebug("\"nh_files\" successfully copied to: " + nhFilesDir.getAbsolutePath());
        } else {
            logDebug("\"nh_files\" directory does NOT exist at: " + nhFilesDir.getAbsolutePath());
            publishProgress("Failed to copy nh_files to SD card!");
        }
        return result;
    }

    private void ensureBusyboxNh() {
        try {
            if (!CheckForRoot.isRoot()) {
                logDebug(TAG, "Root not available; skipping busybox_nh ensure.");
                return;
            }
            final String source = NhPaths.APP_SCRIPTS_BIN_PATH + "/busybox_nh";
            final String target = "/system/bin/busybox_nh";

            // Ensure source exists and is executable (0755)
            exe.RunAsRootReturnValue("chmod 755 " + source);

            // Best-effort remount (may fail on modern Android; ignore errors)
            exe.RunAsRoot(new String[]{
                    "mount -o remount,rw /",
                    "mount -o remount,rw /system",
                    "mount -o remount,rw /system/bin"
            });

            // Recreate symlink if missing or wrong
            String checkCmd = "[ -L " + target + " ] && [ \"$(readlink " + target + ")\" = \"" + source + "\" ]";
            if (exe.RunAsRootReturnValue(checkCmd) != 0) {
                exe.RunAsRootReturnValue("rm -f " + target);
                Symlink("busybox_nh");
            }
            logDebug(TAG, "busybox_nh symlink ensured at: " + target);
        } catch (Exception e) {
            logDebug(TAG, "ensureBusyboxNh() failed: " + e.getMessage(), e);
        }
    }

    private void setPermissions(String... paths) {
        // Use 0755 so root shell and other processes can execute the scripts during early app lifecycle
        for (String path : paths) {
            exe.RunAsRoot("find " + path + " -type d -exec chmod 755 {} \\;");
            exe.RunAsRoot("find " + path + " -type f -exec chmod 755 {} \\;");
        }
    }

    private void installApks(String folderPath) {
        for (String apk : FetchFiles(folderPath)) {
            if (!apk.endsWith(".apk")) continue;
            String src = folderPath + "/" + apk;
            String safeSrc = "'" + src.replace("'", "'\\''") + "'";
            String safeName = "'" + apk.replace("'", "'\\''") + "'";
            String cmd = "mv " + safeSrc + " /data/local/tmp/ && pm install -r --user 0 /data/local/tmp/" + safeName +
                    " && rm -f /data/local/tmp/" + safeName;
            if (exe.RunAsRootReturnValue(cmd) != 0) {
                logDebug("Failed to install APK: " + src);
            }
        }
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Mark that we need to resume SD sync once the user grants the permission
                prefs.edit().putBoolean("pending_sd_sync", true).apply();
                // Prefer per‑app All files access page; fall back to the generic one if needed
                Runnable launch = () -> {
                    try {
                        Toast.makeText(requireActivity().getApplicationContext(), "Please allow storage access and return to app", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        activity.startActivity(intent);
                    }
                };
                mainHandler.post(launch);
                return false;
            }
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return true; // install-time permission
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                mainHandler.post(() -> ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001));
                return false;
            }
        }
        return true;
    }

    // Public method: if the user has just granted the All files access, finish the SD card sync only.
    public void resumePendingSyncIfPermitted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean pending = prefs.getBoolean("pending_sd_sync", false);
            if (!pending) return;
            if (!Environment.isExternalStorageManager()) return;

            // Clear the flag before starting work to avoid re-entry
            prefs.edit().putBoolean("pending_sd_sync", false).apply();

            publishProgress("Finalizing setup: syncing nh_files to SD card...");
            new Thread(() -> {
                try {
                    syncNhFilesToSdcard();
                    File nhFilesDir = new File(NhPaths.SD_PATH, "nh_files");
                    if (nhFilesDir.exists() && nhFilesDir.isDirectory()) {
                        logDebug("\"nh_files\" successfully copied to: " + nhFilesDir.getAbsolutePath());
                    } else {
                        logDebug("\"nh_files\" directory does NOT exist at: " + nhFilesDir.getAbsolutePath());
                        publishProgress("Failed to copy nh_files to SD card!");
                    }
                } catch (Exception e) {
                    logDebug(TAG, "resumePendingSyncIfPermitted error: " + e.getMessage(), e);
                }
            }).start();
        }
    }

    private void copyAssetFolder(String assetFolder, String destFolder) {
        try {
            copyAssetFolderRecursive(assetFolder, destFolder);
        } catch (IOException e) {
            logDebug("Error copying asset folder: " + assetFolder + " to " + destFolder, String.valueOf(e));
        }
    }

    private void copyAssetFolderRecursive(String assetFolder, String destFolder) throws IOException {
        String[] assets = assetManager.list(assetFolder);
        if (assets == null || assets.length == 0) {
            copyAssetFile(assetFolder, destFolder);
        } else {
            File dir = new File(destFolder);
            if (!dir.exists() && !dir.mkdirs()) {
                logDebug("Failed to create directory: " + destFolder + ". Check storage permissions and path.");
                return;
            }
            for (String asset : assets) {
                String assetPath = assetFolder + "/" + asset;
                String destPath = destFolder + "/" + asset;
                copyAssetFolderRecursive(assetPath, destPath);
            }
        }
    }

    private void copyAssetFile(String assetFile, String destFile) {
        try {
            String[] children = assetManager.list(assetFile);
            if (assetFile.endsWith("/placeholder") || assetFile.equals("placeholder") ||
                    assetFile.endsWith("/replaceholder") || assetFile.equals("replaceholder") ||
                    (children != null && children.length > 0)) {
                logDebug("Skipping placeholder, replaceholder, or directory asset: " + assetFile);
                return;
            }

            File outFile = new File(renameAssetIfneeded(destFile));
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logDebug("Failed to create parent directories for: " + outFile.getAbsolutePath());
                return;
            }
            if (outFile.exists() && !outFile.delete()) {
                logDebug("File is busy and cannot be overwritten: " + outFile.getAbsolutePath());
                return;
            }

            try (InputStream in = assetManager.open(assetFile);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                logDebug("Copied asset file: " + assetFile + " to " + outFile.getAbsolutePath());
            }

            // Apply 0755-like permissions for anything under /scripts/ (includes chrootmgr and bin/)
            if (destFile.contains("/scripts/")) {
                boolean permissionsSet = outFile.setReadable(true, false)
                        && outFile.setExecutable(true, false)
                        && outFile.setWritable(true, true);
                logDebug(permissionsSet
                        ? "Set 0755 permissions for: " + outFile.getAbsolutePath()
                        : "Failed to set permissions for: " + outFile.getAbsolutePath());
            }
        } catch (IOException e) {
            logDebug("Error copying asset file: " + assetFile + " to " + destFile, String.valueOf(e));
        } catch (SecurityException e) {
            logDebug("Security exception while copying asset file: " + assetFile + " to " + destFile, String.valueOf(e));
        }
    }

    private void logDebug(String tag, String message) {
        logDebug(tag, message, null);
    }

    private void CheckEncrypted() {
        // Robust detection of encrypted /data and application of compatibility fixes inside chroot
        try {
            logDebug("Checking if /data is encrypted...");
            boolean encrypted = isDeviceEncrypted();
            logDebug("Encrypted status: " + encrypted);

            // Ensure chroot exists before attempting any fix
            String chrootPath = NhPaths.CHROOT_PATH();
            if (exe.RunAsRootReturnValue("[ -d " + chrootPath + " ]") != 0) {
                logDebug("Chroot path not found: " + chrootPath);
                return;
            }

            // Always apply core Android compatibility environment regardless of encryption status
            logDebug("Applying core Android compatibility environment inside chroot...");

            // Ensure /tmp and /var/tmp exist and are sticky world-writable
            chrootExec("mkdir -p /tmp /var/tmp && chmod 1777 /tmp /var/tmp", "Ensure tmp directories with 1777 perms");

            // Persist TMPDIR=/tmp for interactive and non-interactive shells
            chrootExec(
                    "mkdir -p /etc/profile.d && sh -c \"printf '%s\\n' 'export TMPDIR=/tmp' > /etc/profile.d/99-android-tmpdir.sh\" && chmod 644 /etc/profile.d/99-android-tmpdir.sh",
                    "Install TMPDIR export in /etc/profile.d"
            );
            // Fallback for shells not sourcing /etc/profile
            chrootExec(
                    "sh -c \"grep -qxF 'export TMPDIR=/tmp' /root/.profile || echo 'export TMPDIR=/tmp' >> /root/.profile\"",
                    "Ensure TMPDIR in /root/.profile"
            );

            if (!encrypted) {
                logDebug("Device is not encrypted. Skipping encrypted-data specific fixes.");
                return;
            }

            logDebug("Applying encrypted-device compatibility fixes inside chroot...");

            // 1) Disable APT sandbox which often breaks on Android (seccomp/user namespaces)
            chrootExec("mkdir -p /etc/apt/apt.conf.d", "Ensure apt.conf.d exists");
            chrootExec(
                    "sh -c \"printf '%s\\n' 'APT::Sandbox::User \\\"root\\\";' > /etc/apt/apt.conf.d/01-android-nosandbox\"",
                    "Write 01-android-nosandbox apt config");

            // 2) Ensure _apt can reach network by being in inet (GID 3003)
            chrootExec(
                    "sh -c 'GN=$(getent group 3003 | cut -d: -f1 || true); " +
                            "if [ -z \"$GN\" ]; then " +
                            "  if getent group aid_inet >/dev/null 2>&1; then GN=aid_inet; else groupadd -g 3003 -o aid_inet || true; fi; " +
                            "fi; " +
                            "if id -u _apt >/dev/null 2>&1; then usermod -a -G \"$GN\" _apt || true; fi'",
                    "Ensure _apt is in a group with GID 3003 (inet/aid_inet)"
            );

            // 3) Comment out pam_keyinit occurrences which can cause issues under Android
            chrootExec("sed -i 's/pam_keyinit\\.so/& # disabled on Android/' /etc/pam.d/*", "Patch pam_keyinit in /etc/pam.d/*");

            // Validation logs
            chrootExec("getent group 3003 || getent group aid_inet || true", "Show group with gid 3003 or aid_inet info");
            chrootExec("id _apt || true", "Show _apt user info after group change");
            chrootExec("grep -m1 'APT::Sandbox::User' /etc/apt/apt.conf.d/01-android-nosandbox || true", "Verify apt nosandbox config");
        } catch (Exception e) {
            logDebug(TAG, "CheckEncrypted() encountered an error: " + e.getMessage(), e);
        }
    }

    // Determine if device storage is encrypted (FBE/Full-Disk) using multiple signals
    private boolean isDeviceEncrypted() {
        try {
            String state = exe.RunAsRootOutput("getprop ro.crypto.state");
            if (state != null) state = state.trim();
            String type = exe.RunAsRootOutput("getprop ro.crypto.type");
            if (type != null) type = type.trim();
            String dataMount = exe.RunAsRootOutput("mount | grep ' /data '");
            if (dataMount == null) dataMount = "";

            boolean propEncrypted = "encrypted".equalsIgnoreCase(state);
            boolean fbe = type != null && type.equalsIgnoreCase("file");
            boolean mountHints = dataMount.contains("dm-crypt") || dataMount.contains("fscrypt") || dataMount.contains("fileencryption") || dataMount.contains("inlinecrypt");

            logDebug("ro.crypto.state=\"" + (state == null ? "" : state) + "\" ro.crypto.type=\"" + (type == null ? "" : type) + "\"");
            logDebug("mount /data => " + dataMount.replace('\n',' '));
            return propEncrypted || fbe || mountHints;
        } catch (Exception e) {
            logDebug(TAG, "isDeviceEncrypted() error: " + e.getMessage(), e);
            // Conservative default: assume not encrypted on error
            return false;
        }
    }

    private void Symlink(String filename) {
        if (!(filename.startsWith("bootkali") || filename.equals("killkali") || filename.equals("busybox_nh") || filename.equals("curl") || filename.equals("iw"))) {
            logDebug("Skipping symlink/copy for: " + filename);
            return;
        }
        File target = new File("/system/bin/" + filename);
        logDebug("Checking for " + filename + " presence....");
        if (target.exists()) return;

        // Skip early if /system is read-only
        String mountInfo = exe.RunAsRootOutput("mount | grep ' /system ' || true");
        if (mountInfo != null && mountInfo.contains(" ro,")) {
            logDebug("/system is mounted read-only. Cannot create symlink for: " + filename);
            return;
        }

        String targetPath = "/system/bin/" + filename;
        int rc;
        switch (filename) {
            case "busybox_nh": {
                String sourcePath = NhPaths.APP_SCRIPTS_BIN_PATH + "/busybox_nh";
                logDebug("command output: ln -s " + sourcePath + " " + targetPath);
                rc = exe.RunAsRootReturnValue("ln -s " + sourcePath + " " + targetPath);
                break;
            }
            case "iw": {
                String sourcePath = NhPaths.APP_SCRIPTS_BIN_PATH + "/iw";
                logDebug("command output: ln -s " + sourcePath + " " + targetPath);
                rc = exe.RunAsRootReturnValue("ln -s " + sourcePath + " " + targetPath);
                break;
            }
            case "curl": {
                String sourcePath = NhPaths.APP_SCRIPTS_BIN_PATH + "/curl";
                logDebug("command output: ln -s " + sourcePath + " " + targetPath);
                rc = exe.RunAsRootReturnValue("ln -s " + sourcePath + " " + targetPath);
                break;
            }
            default: {
                String sourcePath = NhPaths.APP_SCRIPTS_PATH + "/" + filename;
                logDebug("command output: ln -s " + sourcePath + " " + targetPath);
                rc = exe.RunAsRootReturnValue("ln -s " + sourcePath + " " + targetPath);
                break;
            }
        }
        if (rc != 0) {
            logDebug("Failed to create symlink for: " + filename);
        }
    }

    private void SymlinkScriptsToSystemBin() {
        exe.RunAsRoot(new String[]{
                "mount -o remount,rw /",
                "mount -o remount,rw /system",
                "mount -o remount,rw /system/bin",
                "mount -o remount,rw /system/xbin"
        });

        File scriptsDir = new File(NhPaths.APP_SCRIPTS_PATH);
        File[] scripts = scriptsDir.listFiles();
        if (scripts != null) {
            for (File script : scripts) {
                if (script.isFile()) {
                    String scriptName = script.getName();
                    if (!(scriptName.startsWith("bootkali") || scriptName.equals("killkali"))) {
                        logDebug("Skipping symlink for: " + scriptName);
                        continue;
                    }
                    String targetPath = "/system/bin/" + scriptName;
                    String sourcePath = script.getAbsolutePath();

                    String mountInfo = exe.RunAsRootOutput("mount | grep ' /system ' || true");
                    if (mountInfo != null && mountInfo.contains("ro,")) {
                        logDebug("/system is mounted read-only. Cannot create symlink for: " + scriptName);
                        continue;
                    }

                    String linkCheck = exe.RunAsRootOutput("ls -l " + targetPath + " | grep '" + sourcePath + "' || true");
                    if (linkCheck != null && linkCheck.contains(sourcePath)) {
                        logDebug("Symlink already exists for: " + scriptName);
                        continue;
                    }

                    int rmResult = exe.RunAsRootReturnValue("rm -f " + targetPath);
                    if (rmResult != 0) {
                        logDebug("Failed to remove existing file at " + targetPath + ". rmResult=" + rmResult);
                        continue;
                    }

                    int lnResult = exe.RunAsRootReturnValue("ln -s " + sourcePath + " " + targetPath);
                    if (lnResult == 0) {
                        logDebug("Symlinked " + sourcePath + " to " + targetPath);
                    } else {
                        logDebug("Failed to symlink " + sourcePath + " to " + targetPath + ". lnResult=" + lnResult);
                    }
                }
            }
        }
    }

    // Helper to get the device's primary ABI without using deprecated fields on modern SDKs
    private String getPrimaryAbi() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            return Build.SUPPORTED_ABIS[0] != null ? Build.SUPPORTED_ABIS[0] : "";
        }
        return "";
    }

    private String renameAssetIfneeded(String asset) {
        String cpuAbi = getPrimaryAbi();

        if (asset.matches("^.*-arm64$")) {
            if (cpuAbi.equals("arm64-v8a")) {
                return asset.replaceAll("-arm64$", "");
            }
        } else if (asset.matches("^.*-armeabi$") && !cpuAbi.equals("arm64-v8a")) {
            return asset.replaceAll("-armeabi$", "");
        }
        return asset;
    }

    private ArrayList<String> FetchFiles(String folder) {
        logDebug("Fetching files from " + folder);
        ArrayList<String> files = new ArrayList<>();
        try {
            File dir = new File(folder);
            File[] list = dir.listFiles();
            if (list == null) return files;
            for (File f : list) {
                files.add(f.getName());
            }
        } catch (Exception e) {
            logDebug(TAG, "FetchFiles error for folder: " + folder + ", " + e.getMessage());
        }
        return files;
    }

    private void publishProgress(String message) {
        lastMessage = message;
        progressHandler.removeCallbacks(progressRunnable);
        // Update quickly for the user; slight debounce to avoid spamming UI when many updates arrive
        progressHandler.postDelayed(progressRunnable, 200);
    }

    private void onPostExecute(String objects) {
        this.objects = objects;
        AlertDialog progressDialog = progressDialogRef.get();
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        if (listener != null) {
            listener.onExecutorFinished(result);
        }
        // Prevent thread leaks
        executor.shutdown();
    }

    public void setListener(CopyBootFilesExecutorListener listener) {
        this.listener = listener;
    }
    public String getObjects() {
        return objects;
    }
    public void setObjects(String objects) {
        this.objects = objects;
    }
    public Activity getActivity() {
        return activity;
    }

    public interface CopyBootFilesExecutorListener {
        void onExecutorPrepare();
        void onExecutorFinished(Object result);
    }

    private void disableMagiskNotification() {
        if (exe.RunAsRootReturnValue("[ -f " + NhPaths.MAGISK_DB_PATH + " ]") == 0) {
            logDebug(TAG, "Disabling Magisk notification and log for nethunter app.");
            String pkg = getPackageNameSafe();
            if (exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_BIN_PATH +
                    "/sqlite3 " + NhPaths.MAGISK_DB_PATH + " \"SELECT * from policies\" | grep " +
                    pkg).startsWith(pkg)) {
                exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_BIN_PATH +
                        "/sqlite3 " + NhPaths.MAGISK_DB_PATH + " \"UPDATE policies SET logging='0',notification='0' WHERE package_name='" +
                        pkg + "';\"");
                logDebug(TAG, "Updated magisk db successfully.");
            } else {
                exe.RunAsRootOutput(NhPaths.APP_SCRIPTS_BIN_PATH + "/sqlite3 " +
                        NhPaths.MAGISK_DB_PATH + " \"UPDATE policies SET logging='0',notification='0' WHERE uid='$(stat -c %u /data/data/" +
                        pkg + ")';\"");
            }
        }
    }

    private String getPackageNameSafe() {
        Context c = context.get();
        return c != null ? c.getPackageName() : "";
    }

    @SuppressWarnings("deprecation")
    private int getVersionCodeSafe() {
        try {
            Context c = context.get();
            if (c == null) return 0;
            PackageManager pm = c.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            } else {
                return pi.versionCode;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    // Helper to run and log a command inside the chroot, capturing exit code/stdout/stderr
    private void chrootExec(String cmd, String description) {
        ShellExecuter.ShellResult res = exe.RunAsChrootWithResult(cmd);
        String prefix = (description == null || description.isEmpty()) ? "ChrootExec" : description;
        logDebug(prefix + " => exit=" + res.exitCode);
        if (!res.stdout.isEmpty()) {
            logDebug(prefix + " stdout: " + res.stdout);
        }
        if (!res.stderr.isEmpty()) {
            logDebug(prefix + " stderr: " + res.stderr);
        }
    }

    // Mirror /data/data/com.offsec.nethunter/nh_files to /sdcard/nh_files without overwriting user changes
    private void syncNhFilesToSdcard() {
        try {
            final String src = NhPaths.APP_NHFILES_PATH;
            final String dst = NhPaths.SD_PATH + "/nh_files";

            // Ensure destination exists
            int mkres = exe.RunAsRootReturnValue("mkdir -p '" + dst + "'");
            if (mkres != 0) {
                logDebug(TAG, "Failed to create destination nh_files directory on SD card");
                return;
            }

            // Prefer busybox cp -au to copy only missing/newer files and preserve attrs
            String bb = NhPaths.BUSYBOX != null ? NhPaths.BUSYBOX.trim() : "";
            String cmd;
            if (!bb.isEmpty()) {
                cmd = bb + " cp -au '" + src + "/.' '" + dst + "/'";
            } else {
                // Fallback to toolbox cp -rn; do not overwrite existing files
                cmd = "sh -c 'cp -rn " + src + "/. " + dst + "/'";
            }
            int rc = exe.RunAsRootReturnValue(cmd);
            if (rc != 0) {
                logDebug(TAG, "syncNhFilesToSdcard: copy command failed rc=" + rc);
            } else {
                logDebug(TAG, "syncNhFilesToSdcard: SD card nh_files synced.");
            }
        } catch (Exception e) {
            logDebug(TAG, "syncNhFilesToSdcard() error: " + e.getMessage(), e);
        }
    }

    // Update the message text in the progress dialog if it's showing
    private void updateProgressDialogMessage(String message) {
        TextView tv = progressMessageRef.get();
        if (tv != null) {
            tv.setText(message);
            return;
        }
        AlertDialog dialog = progressDialogRef.get();
        if (dialog != null) {
            TextView tv2 = dialog.findViewById(com.offsec.nethunter.R.id.progress_message);
            if (tv2 != null) {
                tv2.setText(message);
                progressMessageRef = new WeakReference<>(tv2);
            }
        }
    }
}
