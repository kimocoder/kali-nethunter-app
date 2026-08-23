package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.*;
import android.nfc.tech.*;
import android.os.*;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.ShellExecuter;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NFC Arsenal
 */
public class NFCFragment extends Fragment {

    private static final String ARG_SECTION_NUMBER = "section_number";

    public static NFCFragment newInstance(int sectionNumber) {
        NFCFragment f = new NFCFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_SECTION_NUMBER, sectionNumber);
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.nfc, menu);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle b) {
        View root = inflater.inflate(R.layout.nfc, container, false);

        ViewPager2 pager = root.findViewById(R.id.nfcPager);
        pager.setAdapter(new NFCPagerAdapter(this));

        TabLayout tabs = root.findViewById(R.id.nfcTabs);
        new TabLayoutMediator(tabs, pager, (tab, pos) -> {
            switch (pos) {
                case 0: tab.setText("Main"); break;
                case 1: tab.setText("Read / Write"); break;
                case 2: tab.setText("Block Editor"); break;
                case 3: tab.setText("Dump Viewer"); break;
                case 4: tab.setText("Advanced"); break;
            }
        }).attach();

        return root;
    }

    static String inferType2Tag(byte[] atqa, short sak) {
        int atqaVal = ((atqa[1] & 0xFF) << 8) | (atqa[0] & 0xFF);

        if (sak == 0x00) {
            if (atqaVal == 0x0044) return "MIFARE Ultralight / NTAG21x";
            if (atqaVal == 0x0004) return "MIFARE Ultralight";
        }

        if ((sak & 0x08) != 0) return "MIFARE Classic (Crypto1)";
        if ((sak & 0x20) != 0) return "ISO-DEP (Type 4)";

        return "Unknown ISO14443-A Tag";
    }

    static String inferMemory(byte[] atqa, short sak) {
        int atqaVal = ((atqa[1] & 0xFF) << 8) | (atqa[0] & 0xFF);

        /* ================= TYPE 2 TAGS ================= */
        if (sak == 0x00) {
            switch (atqaVal) {
                case 0x0044:
                    return "NTAG21x family (144–888 bytes)";
                case 0x0004:
                    return "MIFARE Ultralight (64 bytes)";
                default:
                    return "Type 2 (Unknown size)";
            }
        }

        /* ================= MIFARE CLASSIC ================= */
        if ((sak & 0x08) != 0) {
            // MIFARE Classic family
            if ((sak & 0x10) != 0) {
                return "MIFARE Classic 4K (40 sectors)";
            }
            return "MIFARE Classic 1K (16 sectors)";
        }

        /* ================= ISO-DEP ================= */
        if ((sak & 0x20) != 0) {
            return "ISO-DEP (APDU-based, variable size)";
        }

        return "Unknown";
    }

    /* ========================================================= */
    /* SMART NFC CAPABILITY ENGINE                               */
    /* ========================================================= */
    static class NfcCapabilityEngine {

        enum InterfaceType {
            NONE,
            INTERNAL,
            EXTERNAL_USB,
            INTERNAL_AND_EXTERNAL
        }

        enum ExternalAdapter {
            NONE,
            PROXMARK3,
            PN532,
            ACR122U,
            LIBNFC,
            UNKNOWN_SERIAL
        }

        enum CapabilityLevel {
            NONE,              // No NFC
            INTERNAL_LIMITED,  // Phone NFC only
            EXTERNAL_READER,   // PN532 / ACR122U / libnfc readers
            EXTERNAL_FULL,     // Proxmark-class tools
            MIXED              // Internal NFC plus external reader
        }

        static class Capability {
            InterfaceType interfaceType = InterfaceType.NONE;
            ExternalAdapter adapter = ExternalAdapter.NONE;
            CapabilityLevel level = CapabilityLevel.NONE;
            boolean internalAvailable;
            boolean internalEnabled;
            boolean externalAvailable;
            boolean libnfcInstalled;
            boolean mfocInstalled;
            boolean proxmarkInstalled;
            String externalDevicePath = "";
            String serialDevices = "";
            String usbDevices = "";
            String externalProduct = "";

            boolean canUseInternalTags() { return internalAvailable && internalEnabled; }
            boolean canUseExternalTools() { return externalAvailable; }
            boolean canSpoofUID() { return adapter == ExternalAdapter.PROXMARK3 && proxmarkInstalled; }
            boolean canReplayRF() { return adapter == ExternalAdapter.PROXMARK3 && proxmarkInstalled; }
            boolean canRelay()    { return externalAvailable; }
            boolean canBlockEdit(){ return internalAvailable || (externalAvailable && mfocInstalled); }

            String adapterLabel() {
                switch (adapter) {
                    case PROXMARK3:
                        return "Proxmark3";
                    case PN532:
                        return "PN532 / PN53x";
                    case ACR122U:
                        return "ACR122U / ACS CCID";
                    case LIBNFC:
                        return "libnfc-compatible reader";
                    case UNKNOWN_SERIAL:
                        return "Unknown USB serial adapter";
                    default:
                        return "None";
                }
            }

            String proxmarkDeviceArg() {
                return externalDevicePath.startsWith("/dev/") ? " " + externalDevicePath : "";
            }

            String describeExternal() {
                if (!externalAvailable) {
                    return "No external NFC hardware detected";
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Adapter        : ").append(adapterLabel()).append("\n");
                if (!externalProduct.isEmpty()) {
                    sb.append("USB Device     : ").append(externalProduct).append("\n");
                }
                if (!externalDevicePath.isEmpty()) {
                    sb.append("Device Path    : ").append(externalDevicePath).append("\n");
                }
                if (!usbDevices.isEmpty()) {
                    sb.append("\nUSB Inventory:\n").append(usbDevices.trim()).append("\n");
                }
                if (!serialDevices.isEmpty()) {
                    sb.append("\nSerial Nodes:\n").append(serialDevices.trim()).append("\n");
                }
                sb.append("\nKali Tools     : ");
                List<String> tools = new ArrayList<>();
                if (libnfcInstalled) tools.add("nfc-list");
                if (mfocInstalled) tools.add("mfoc");
                if (proxmarkInstalled) tools.add("proxmark3");
                sb.append(tools.isEmpty() ? "not installed" : joinLines(tools, ", "));
                return sb.toString();
            }
        }

        private static Capability cached;

        static synchronized Capability get(Context ctx) {
            return get(ctx, false);
        }

        static synchronized Capability get(Context ctx, boolean forceRefresh) {
            if (!forceRefresh && cached != null) return cached;
            cached = detect(ctx.getApplicationContext());
            return cached;
        }

        static synchronized void invalidate() {
            cached = null;
        }

        private static Capability detect(Context ctx) {
            Capability cap = new Capability();
            NfcAdapter internal = NfcAdapter.getDefaultAdapter(ctx);
            cap.internalAvailable = internal != null;
            cap.internalEnabled = internal != null && internal.isEnabled();

            detectUsbManager(ctx, cap);

            ShellExecuter exe = new ShellExecuter();
            detectRootUsb(exe, cap);
            detectTooling(exe, cap);

            if (cap.externalAvailable && cap.internalAvailable) {
                cap.interfaceType = InterfaceType.INTERNAL_AND_EXTERNAL;
                cap.level = CapabilityLevel.MIXED;
            } else if (cap.externalAvailable) {
                cap.interfaceType = InterfaceType.EXTERNAL_USB;
                cap.level = cap.adapter == ExternalAdapter.PROXMARK3
                        ? CapabilityLevel.EXTERNAL_FULL
                        : CapabilityLevel.EXTERNAL_READER;
            } else if (cap.internalAvailable) {
                cap.interfaceType = InterfaceType.INTERNAL;
                cap.level = CapabilityLevel.INTERNAL_LIMITED;
            }

            return cap;
        }

        private static void detectUsbManager(Context ctx, Capability cap) {
            UsbManager manager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (manager == null) return;

            StringBuilder devices = new StringBuilder();
            for (Map.Entry<String, UsbDevice> entry : manager.getDeviceList().entrySet()) {
                UsbDevice device = entry.getValue();
                String line = String.format(
                        Locale.US,
                        "%04x:%04x %s %s",
                        device.getVendorId(),
                        device.getProductId(),
                        nullSafe(device.getManufacturerName()),
                        nullSafe(device.getProductName())
                ).trim();
                devices.append(line).append("\n");
            }
            cap.usbDevices = appendLines(cap.usbDevices, devices.toString());
            classifyExternal(cap, cap.usbDevices);
        }

        private static void detectRootUsb(ShellExecuter exe, Capability cap) {
            String serial = safeRoot(exe,
                    "for d in /dev/ttyACM* /dev/ttyUSB*; do [ -e \"$d\" ] && echo \"$d\"; done"
            );
            cap.serialDevices = appendLines(cap.serialDevices, serial);
            cap.externalDevicePath = firstNonEmpty(cap.externalDevicePath, firstLine(serial));

            String usb = safeRoot(exe,
                    "for d in /sys/bus/usb/devices/*; do " +
                            "[ -r \"$d/idVendor\" ] || continue; " +
                            "vid=$(cat \"$d/idVendor\" 2>/dev/null); " +
                            "pid=$(cat \"$d/idProduct\" 2>/dev/null); " +
                            "manufacturer=$(cat \"$d/manufacturer\" 2>/dev/null); " +
                            "product=$(cat \"$d/product\" 2>/dev/null); " +
                            "echo \"$vid:$pid $manufacturer $product\"; " +
                            "done"
            );
            cap.usbDevices = appendLines(cap.usbDevices, usb);

            classifyExternal(cap, cap.serialDevices + "\n" + cap.usbDevices);

            if (!cap.serialDevices.trim().isEmpty() && cap.adapter == ExternalAdapter.NONE) {
                cap.adapter = ExternalAdapter.UNKNOWN_SERIAL;
                cap.externalAvailable = true;
            }
        }

        private static void detectTooling(ShellExecuter exe, Capability cap) {
            String tools = safeChroot(exe,
                    "for t in nfc-list mfoc proxmark3; do command -v $t >/dev/null 2>&1 && echo $t; done"
            );
            cap.libnfcInstalled = hasLine(tools, "nfc-list");
            cap.mfocInstalled = hasLine(tools, "mfoc");
            cap.proxmarkInstalled = hasLine(tools, "proxmark3");
        }

        private static void classifyExternal(Capability cap, String raw) {
            String data = lower(raw);
            if (data.isEmpty()) return;

            if (containsAny(data, "proxmark", "9ac4:4b8f")) {
                cap.adapter = ExternalAdapter.PROXMARK3;
            } else if (containsAny(data, "acr122", "acr 122", "072f:2200", "advanced card", "acs")) {
                cap.adapter = ExternalAdapter.ACR122U;
            } else if (containsAny(data, "pn532", "pn533", "pn531", "pn53")) {
                cap.adapter = ExternalAdapter.PN532;
            } else if (containsAny(data, "nfc", "contactless", "smart card", "ccid")) {
                cap.adapter = ExternalAdapter.LIBNFC;
            } else if (containsAny(data, "ttyacm", "ttyusb")) {
                cap.adapter = ExternalAdapter.UNKNOWN_SERIAL;
            } else {
                return;
            }

            cap.externalAvailable = cap.adapter != ExternalAdapter.NONE;
            cap.externalProduct = firstNonEmpty(cap.externalProduct, firstDescriptiveLine(raw));
        }

        private static boolean containsAny(String value, String... needles) {
            for (String needle : needles) {
                if (value.contains(needle)) return true;
            }
            return false;
        }

        private static String safeRoot(ShellExecuter exe, String command) {
            try {
                String out = exe.RunAsRootOutput(command);
                return out == null ? "" : out.trim();
            } catch (Exception ignored) {
                return "";
            }
        }

        private static String safeChroot(ShellExecuter exe, String command) {
            try {
                String out = exe.RunAsChrootOutput(command);
                return out == null ? "" : out.trim();
            } catch (Exception ignored) {
                return "";
            }
        }

        private static boolean hasLine(String lines, String wanted) {
            for (String line : nullSafe(lines).split("\n")) {
                if (line.trim().equals(wanted)) return true;
            }
            return false;
        }

        private static String firstLine(String value) {
            for (String line : nullSafe(value).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) return trimmed;
            }
            return "";
        }

        private static String firstDescriptiveLine(String value) {
            for (String line : nullSafe(value).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("/dev/")) return trimmed;
            }
            return "";
        }

        private static String firstNonEmpty(String... values) {
            for (String value : values) {
                String trimmed = nullSafe(value).trim();
                if (!trimmed.isEmpty()) return trimmed;
            }
            return "";
        }

        private static String appendLines(String first, String second) {
            first = nullSafe(first).trim();
            second = nullSafe(second).trim();
            if (first.isEmpty()) return second;
            if (second.isEmpty()) return first;
            return first + "\n" + second;
        }

        private static String joinLines(List<String> values, String separator) {
            StringBuilder sb = new StringBuilder();
            for (String value : values) {
                if (sb.length() > 0) sb.append(separator);
                sb.append(value);
            }
            return sb.toString();
        }

        private static String lower(String value) {
            return nullSafe(value).toLowerCase(Locale.US);
        }

        private static String nullSafe(String value) {
            return value == null ? "" : value;
        }
    }

    /* ========================================================= */
    /* Pager Adapter                                             */
    /* ========================================================= */
    static class NFCPagerAdapter extends FragmentStateAdapter {
        NFCPagerAdapter(@NonNull Fragment f) { super(f); }
        @Override public int getItemCount() { return 5; }

        @NonNull
        @Override
        public Fragment createFragment(int pos) {
            switch (pos) {
                case 0: return new NFCMainFragment();
                case 1:
                    return new NFCReadWriteFragment();
                case 2: return new NFCBlockEditorFragment();
                case 3: return new NFCDumpViewerFragment();
                default: return new NFCAdvancedFragment();
            }
        }
    }

    /* ========================================================= */
    /* Main Fragment                                             */
    /* ========================================================= */
    public static class NFCMainFragment extends Fragment {

        TextView status, features, limits, hardware, ext_hardware, dev;

        @Override
        public View onCreateView(LayoutInflater i, ViewGroup c, Bundle b) {
            View v = i.inflate(R.layout.nfc_main, c, false);

            status = v.findViewById(R.id.nfcStatus);
            features = v.findViewById(R.id.nfcFeatures);
            limits   = v.findViewById(R.id.nfcLimits);
            hardware = v.findViewById(R.id.nfcHardware);
            ext_hardware =  v.findViewById(R.id.nfcExtHardware);
            dev      = v.findViewById(R.id.nfcDev);

            Button extRefresh = v.findViewById(R.id.nfcExtRefresh);
            extRefresh.setOnClickListener(x -> refreshExternalHardware());

            Button refresh = v.findViewById(R.id.nfcRefresh);
            refresh.setOnClickListener(x -> refreshStatus());

            refreshStatus();
            return v;
        }

        private void refreshExternalHardware() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);

            if (!cap.externalAvailable) {
                ext_hardware.setTextColor(Color.GRAY);
                ext_hardware.setText("No external NFC hardware detected");
                return;
            }

            ext_hardware.setTextColor(Color.parseColor("#2E7D32"));
            ext_hardware.setText(cap.describeExternal());
        }

        private String calculateConfidence(NfcHwSignals s) {
            if (s.sysfs != null && !s.sysfs.trim().isEmpty()) {
                return "High";
            }
            if (s.kernelDriver != null && s.kernelDriver.toLowerCase().contains("nfc")) {
                return "Medium";
            }
            if (s.halBinary != null && !s.halBinary.isEmpty()) {
                return "Low";
            }
            return "None";
        }

        private String discoverHalBinary(ShellExecuter exe) {
            String[] cmds = {
                    // Standard HAL locations
                    "ls /vendor/bin/hw 2>/dev/null | grep -i nfc",
                    "ls /system/bin/hw 2>/dev/null | grep -i nfc",
                    "ls /odm/bin/hw 2>/dev/null | grep -i nfc",

                    // Flat vendor/system bins
                    "ls /vendor/bin 2>/dev/null | grep -i nfc",
                    "ls /system/bin 2>/dev/null | grep -i nfc",

                    // Init services (most reliable)
                    "getprop | grep -E 'init\\.svc.*nfc'"
            };

            for (String cmd : cmds) {
                String out = exe.RunAsRootOutput(cmd);
                if (out != null && !out.trim().isEmpty()) {
                    return out.split("\n")[0].trim();
                }
            }
            return "";
        }

        private String normalizeController(NfcHwSignals s) {
            String data = (
                    nullSafe(s.kernelDriver) +
                            nullSafe(s.halBinary) +
                            nullSafe(s.initServices)
            ).toLowerCase();

            if (data.contains("nxp") || data.contains("pn5") || data.contains("sn100")) {
                return "NXP NFC Controller";
            }

            if (data.contains("bcm") || data.contains("broadcom")) {
                return "Broadcom NFC Controller";
            }

            if (data.contains("st21") || data.contains("st54")) {
                return "STMicroelectronics NFC Controller";
            }

            if (data.contains("qti") || data.contains("qualcomm")) {
                return "Qualcomm NFC Interface";
            }

            return "Unknown NFC controller (OEM masked)";
        }

        private String nullSafe(String s) {
            return s == null ? "" : s;
        }

        static class NfcHwSignals {
            String sysfs;
            String kernelDriver;
            String halBinary;
            String initServices;
            String transport;
        }

        private NfcHwSignals collectNfcSignals() {
            ShellExecuter exe = new ShellExecuter();
            NfcHwSignals s = new NfcHwSignals();

            // Sysfs exposure
            s.sysfs = exe.RunAsRootOutput(
                    "ls -d /sys/class/nfc 2>/dev/null || " +
                            "ls -d /sys/bus/i2c/devices/*nfc* 2>/dev/null || " +
                            "ls -d /sys/bus/spi/devices/*nfc* 2>/dev/null"
            );

            // Kernel driver (module or built-in hint)
            s.kernelDriver = exe.RunAsRootOutput(
                    "grep -i nfc /proc/modules 2>/dev/null | awk '{print $1}'"
            );

            if (s.kernelDriver == null || s.kernelDriver.trim().isEmpty()) {
                s.kernelDriver = exe.RunAsRootOutput(
                        "dmesg | grep -i nfc | head -n 1"
                );
            }

            // HAL binary discovery (NO hardcoding)
            s.halBinary = discoverHalBinary(exe);

            // Init services (dynamic)
            s.initServices = exe.RunAsRootOutput(
                    "getprop | grep -E '\\.svc.*nfc' | awk '{print $1}'"
            );

            // Transport
            s.transport = firstNonEmpty(
                    getSystemProperty("ro.nfc.port"),
                    getSystemProperty("persist.nfc.port"),
                    getSystemProperty("vendor.nfc.port")
            );

            return s;
        }

        private String extractHalInterface(String bin) {
            if (bin == null || bin.trim().isEmpty()) {
                return "Unknown";
            }

            // Strip init property prefix
            if (bin.startsWith("init.svc.")) {
                bin = bin.substring("init.svc.".length());
            }

            // Remove "=running" or similar
            int eq = bin.indexOf('=');
            if (eq != -1) {
                bin = bin.substring(0, eq);
            }

            // Remove -service suffix
            if (bin.endsWith("-service")) {
                bin = bin.substring(0, bin.length() - "-service".length());
            }

            return bin.trim();
        }

        static class NfcHwSummary {
            String controller;
            String hal;
            String transport;
            String kernelAccess;
            String confidence;
        }

        private NfcHwSummary analyzeNfcHardware(NfcHwSignals s) {
            NfcHwSummary hw = new NfcHwSummary();

            // Controller
            hw.controller = normalizeController(s);
            if (hw.controller == null) {
                hw.controller = "Unknown NFC controller (OEM masked)";
            }

            // HAL
            hw.hal = extractHalInterface(s.halBinary);

            // Transport
            hw.transport = (s.transport == null || s.transport.isEmpty())
                    ? "Unknown"
                    : s.transport;

            // Kernel exposure
            hw.kernelAccess = (s.sysfs != null && !s.sysfs.trim().isEmpty())
                    ? "Exposed"
                    : "Restricted (OEM masked)";

            // Confidence
            hw.confidence = calculateConfidence(s);

            return hw;
        }

        private String renderChipsetInfo(NfcCapabilityEngine.Capability cap) {
            if (!cap.internalAvailable) {
                return "Android Adapter : Not present\n" +
                        "Adapter State   : External-only mode\n\n" +
                        "Onboard NFC is not exposed through the Android NFC stack on this device.\n";
            }

            NfcHwSignals signals = collectNfcSignals();
            NfcHwSummary hw = analyzeNfcHardware(signals);

            StringBuilder sb = new StringBuilder();

            sb.append("Android Adapter : Present\n");
            sb.append("Adapter State   : ").append(cap.internalEnabled ? "Enabled" : "Disabled").append("\n");
            sb.append("Controller      : ").append(hw.controller).append("\n");
            sb.append("HAL Interface   : ").append(hw.hal).append("\n");
            sb.append("Transport       : ").append(hw.transport).append("\n");
            sb.append("Kernel Access   : ").append(hw.kernelAccess).append("\n");
            sb.append("Detection Level : ").append(hw.confidence).append(" confidence\n\n");

            sb.append("ℹ Vendor, model or RF capabilities may be masked by OEM firmware.\n");

            return sb.toString();
        }

        private void setStatus(String symbol, int symbolColor, String text) {
            SpannableString ss = new SpannableString(symbol + " " + text);
            // Color ONLY the symbol
            ss.setSpan(
                    new ForegroundColorSpan(symbolColor),
                    0,
                    symbol.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            status.setText(ss);
        }

        private void refreshStatus() {
            if (!isAdded()) return;

            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);

            if (cap.internalAvailable && cap.internalEnabled && cap.externalAvailable) {
                setStatus("OK", Color.parseColor("#2E7D32"), "Onboard NFC enabled + external adapter detected");
            } else if (cap.internalAvailable && cap.internalEnabled) {
                setStatus("OK", Color.parseColor("#2E7D32"), "Onboard NFC enabled");
            } else if (cap.internalAvailable) {
                setStatus("!", Color.parseColor("#FFA000"), "Onboard NFC disabled");
            } else if (cap.externalAvailable) {
                setStatus("OK", Color.parseColor("#2E7D32"), "External NFC adapter detected");
            } else {
                setStatus("X", Color.RED, "No NFC hardware detected");
            }

            features.setText(
                    "- Onboard tag discovery, NDEF read/write, and Android tag-tech enumeration\n" +
                            "- MIFARE Classic sector/block editor for Android-supported tags\n" +
                            "- External reader discovery through USB, sysfs, and Kali tooling\n" +
                            "- libnfc/mfoc workflows for PN532, ACR122U, and compatible readers\n" +
                            "- Proxmark3 command routing for full external attack workflows\n" +
                            "- Tag metadata export to nh_files/NFC"
            );

            if (cap.externalAvailable) {
                limits.setText(
                        "- Onboard NFC uses Android's public tag APIs.\n" +
                                "- External reader actions run inside the Kali chroot.\n" +
                                "- UID spoofing and raw RF replay require Proxmark3 support.\n" +
                                "- PN532/ACR122U/libnfc readers support scan and dump workflows."
                );
            } else {
                limits.setText(
                        "- UID spoofing is not available with onboard NFC.\n" +
                                "- Raw RF replay is not available with onboard NFC.\n" +
                                "- Relay/MITM workflows require external readers.\n\n" +
                                "Connect a Proxmark3, PN532, ACR122U, or libnfc-compatible reader for external mode."
                );
            }

            hardware.setText(renderChipsetInfo(cap));

            hardware.append("\n\nInterface Mode : " + cap.interfaceType);
            hardware.append("\nExternal       : " + cap.adapterLabel());
            hardware.append("\nCapability     : " + cap.level);

            if (cap.externalAvailable) {
                ext_hardware.setTextColor(Color.parseColor("#2E7D32"));
                ext_hardware.setText(cap.describeExternal());
            } else {
                ext_hardware.setTextColor(Color.GRAY);
                ext_hardware.setText("No external NFC hardware detected");
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dev.setText(
                        Html.fromHtml(
                                "Developed By: <a href=\"https://iamcod3x.dev/\">IamCOD3X</a><br/><br/>" +
                                        "“Tools don’t break systems — people do.”",
                                Html.FROM_HTML_MODE_LEGACY
                        )
                );
            }
            dev.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            dev.setLinkTextColor(Color.RED);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_install_nfc_tools) {
            showInstallToolsDialog();
            return true;
        }

        if (id == R.id.action_nfc_info) {
            showNfcInfoDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /* ========================================================= */
    /* Read / Write Fragment                                     */
    /* ========================================================= */
    public static class NFCReadWriteFragment extends Fragment {

        private static final long NFC_TIMEOUT_MS = 15_000;
        private static final int COUNTDOWN_INTERVAL = 1000;

        private enum NfcOperation {
            IDLE,
            READ_ARMED,
            WRITE_ARMED
        }

        private NfcAdapter adapter;
        private TextView output;
        private EditText writeInput;
        private TextView externalReaderStatus;
        private NfcOperation currentOp = NfcOperation.IDLE;
        private Handler handler;
        private Runnable timeoutRunnable;
        private Runnable countdownRunnable;
        private long remainingTime;
        private TextView countdown;

        @Override
        public View onCreateView(LayoutInflater i, ViewGroup c, Bundle b) {
            View v = i.inflate(R.layout.nfc_readwrite, c, false);

            output = v.findViewById(R.id.nfcOutput);
            countdown = v.findViewById(R.id.nfcCountdown);
            writeInput = v.findViewById(R.id.writeData);
            externalReaderStatus = v.findViewById(R.id.externalReaderStatus);
            Button read = v.findViewById(R.id.readTag);
            Button write = v.findViewById(R.id.writeTag);
            Button externalScan = v.findViewById(R.id.externalScan);
            Button externalDump = v.findViewById(R.id.externalDump);

            adapter = NfcAdapter.getDefaultAdapter(requireContext());
            handler = new Handler(Looper.getMainLooper());

            /* ================= SMART CAPABILITY HINT ================= */

            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext());

            if (cap.internalAvailable) {
                writeInput.setHint("Onboard NFC -> NDEF text only");
            } else {
                read.setEnabled(false);
                write.setEnabled(false);
                writeInput.setHint("No onboard NFC adapter detected");
            }

            configureExternalControls(cap, externalScan, externalDump);

            read.setOnClickListener(x -> armRead());
            write.setOnClickListener(x -> armWrite());
            externalScan.setOnClickListener(x -> runExternalScan());
            externalDump.setOnClickListener(x -> runExternalDump());

            return v;
        }

        @Override
        public void onDestroyView() {
            clearState();
            super.onDestroyView();
        }

        private void configureExternalControls(
                NfcCapabilityEngine.Capability cap,
                Button externalScan,
                Button externalDump
        ) {
            if (!cap.externalAvailable) {
                externalReaderStatus.setText("No external NFC adapter detected.");
                externalScan.setEnabled(false);
                externalDump.setEnabled(false);
                return;
            }

            externalReaderStatus.setText(cap.describeExternal());

            boolean canScan = cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3
                    ? cap.proxmarkInstalled
                    : cap.libnfcInstalled;
            boolean canDump = cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3
                    ? cap.proxmarkInstalled
                    : cap.mfocInstalled;

            externalScan.setEnabled(canScan);
            externalDump.setEnabled(canDump);

            if (!canScan || !canDump) {
                output.setText("External adapter detected. Install NFC tools from the menu if scan/dump buttons are disabled.");
            }
        }

        private void runExternalScan() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);

            if (!cap.externalAvailable) {
                output.setText("No external NFC adapter detected.");
                return;
            }

            if (cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3) {
                if (!cap.proxmarkInstalled) {
                    output.setText("proxmark3 is not installed in the Kali chroot.");
                    return;
                }
                runKali("proxmark3" + cap.proxmarkDeviceArg() + " -c 'hw status; hf search'");
                return;
            }

            if (!cap.libnfcInstalled) {
                output.setText("nfc-list is not installed in the Kali chroot.");
                return;
            }
            runKali("nfc-list");
        }

        private void runExternalDump() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);

            if (!cap.externalAvailable) {
                output.setText("No external NFC adapter detected.");
                return;
            }

            if (cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3) {
                if (!cap.proxmarkInstalled) {
                    output.setText("proxmark3 is not installed in the Kali chroot.");
                    return;
                }
                runKali("mkdir -p /sdcard/nh_files/NFC; proxmark3" +
                        cap.proxmarkDeviceArg() +
                        " -c 'hf mf autopwn; hf mf dump'");
                return;
            }

            if (!cap.mfocInstalled) {
                output.setText("mfoc is not installed in the Kali chroot.");
                return;
            }
            runKali("mkdir -p /sdcard/nh_files/NFC; mfoc -O /sdcard/nh_files/NFC/mfoc_$(date +%Y%m%d_%H%M%S).mfd");
        }

        private boolean ensureInternalAdapter() {
            if (adapter == null) {
                output.setText("No onboard NFC adapter detected. Use an external adapter workflow instead.");
                return false;
            }
            if (!adapter.isEnabled()) {
                output.setText("Onboard NFC is disabled in Android settings.");
                return false;
            }
            return true;
        }

        private void runKali(String cmd) {
            startActivity(Bridge.createExecuteIntent(
                    "/data/data/com.offsec.nhterm/files/usr/bin/kali",
                    cmd
            ));
        }

        /* ================= Entry Points ================= */

        public void handleTag(Intent intent) {
            if (currentOp == NfcOperation.IDLE) return;

            if (timeoutRunnable != null) handler.removeCallbacks(timeoutRunnable);
            if (countdownRunnable != null) handler.removeCallbacks(countdownRunnable);
            if (countdown != null) countdown.setText("");

            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag == null) {
                currentOp = NfcOperation.IDLE;
                return;
            }

            if (currentOp == NfcOperation.READ_ARMED)
                readTag(tag);
            else if (currentOp == NfcOperation.WRITE_ARMED)
                writeTag(tag);

            currentOp = NfcOperation.IDLE;
        }

        /* ================= Arm Operations ================= */

        private void armRead() {
            if (!ensureInternalAdapter()) return;
            clearState();
            currentOp = NfcOperation.READ_ARMED;
            output.setText("📡 Waiting for NFC tag...");
            toast("Tap NFC tag to READ");
            startTimeout();
        }

        private void armWrite() {
            if (!ensureInternalAdapter()) return;
            clearState();
            currentOp = NfcOperation.WRITE_ARMED;
            output.setText("✍ Waiting for NFC tag...");
            toast("Tap NFC tag to WRITE");
            startTimeout();
        }

        /* ================= Timeout ================= */

        private void startTimeout() {
            remainingTime = NFC_TIMEOUT_MS;

            countdownRunnable = new Runnable() {
                @Override
                public void run() {
                    if (currentOp == NfcOperation.IDLE) return;

                    countdown.setText("⏳ Time left: " + (remainingTime / 1000) + "s");

                    remainingTime -= COUNTDOWN_INTERVAL;

                    if (remainingTime > 0) {
                        handler.postDelayed(this, COUNTDOWN_INTERVAL);
                    }
                }
            };

            timeoutRunnable = () -> {
                countdown.setText("");
                output.setText("⏱ NFC operation timed out");
                toast("NFC operation timed out");
                currentOp = NfcOperation.IDLE;
            };

            handler.post(countdownRunnable);
            handler.postDelayed(timeoutRunnable, NFC_TIMEOUT_MS);
        }

        private void clearState() {
            if (timeoutRunnable != null)
                handler.removeCallbacks(timeoutRunnable);
            if (countdownRunnable != null)
                handler.removeCallbacks(countdownRunnable);

            if (countdown != null) countdown.setText("");
            currentOp = NfcOperation.IDLE;
        }

        /* ================= Read ================= */

        @SuppressLint("SetTextI18n")
        private void readTag(Tag tag) {
            handler.post(() -> {
                try {
                    StringBuilder info = new StringBuilder();

                    /* ================= UID ================= */
                    byte[] uidBytes = tag.getId();
                    String uid = TagDump.bytes(uidBytes);
                    info.append("UID / Serial : ").append(uid).append("\n\n");

                    /* ================= TECH LIST ================= */
                    info.append("Technologies:\n");
                    for (String tech : tag.getTechList()) {
                        info.append("• ")
                                .append(tech.replace("android.nfc.tech.", ""))
                                .append("\n");
                    }
                    info.append("\n");

                    /* ================= NFC-A (RF LAYER) ================= */
                    byte[] atqa = null;
                    short sak = 0;

                    NfcA nfcA = NfcA.get(tag);
                    if (nfcA != null) {
                        nfcA.connect();
                        atqa = nfcA.getAtqa();
                        sak = nfcA.getSak();

                        info.append("ATQA : ")
                                .append(String.format("%02X %02X", atqa[1], atqa[0]))
                                .append("\n");

                        info.append("SAK  : ")
                                .append(String.format("0x%02X", sak))
                                .append("\n\n");

                        nfcA.close();
                    }

                    /* ================= TAG TYPE ================= */
                    if (atqa != null) {
                        info.append("Tag Type : ")
                                .append(inferType2Tag(atqa, sak))
                                .append("\n");
                    }

                    /* ================= MEMORY INFO ================= */

                    boolean memoryShown = false;

                    // ---- MIFARE Classic (authoritative) ----
                    MifareClassic mfc = MifareClassic.get(tag);
                    if (mfc != null) {
                        mfc.connect();

                        info.append("Memory   : ")
                                .append(mfc.getSize())
                                .append(" bytes\n");

                        info.append("Layout   : ")
                                .append(mfc.getSectorCount())
                                .append(" sectors × ")
                                .append(mfc.getBlockCount() / mfc.getSectorCount())
                                .append(" blocks\n");

                        mfc.close();
                        memoryShown = true;
                    }

                    // ---- Type 2 / heuristic fallback ----
                    if (!memoryShown && atqa != null) {
                        info.append("Memory   : ")
                                .append(inferMemory(atqa, sak))
                                .append("\n");
                    }

                    // ---- NDEF info (what NFC Tools shows) ----
                    Ndef ndef = Ndef.get(tag);
                    if (ndef != null) {
                        ndef.connect();

                        info.append("\nNDEF Info:\n");
                        info.append("• Writable : ")
                                .append(ndef.isWritable() ? "Yes" : "No")
                                .append("\n");

                        info.append("• Max Size : ")
                                .append(ndef.getMaxSize())
                                .append(" bytes\n");

                        ndef.close();
                    }

                    /* ================= SAVE DUMP ================= */
                    TagDump dump = TagDump.fromTag(tag);
                    NfcDumpWriter.save(dump);

                    output.setText(
                            "✅ Tag Read Successful\n" +
                                    "────────────────────\n\n" +
                                    info.toString()
                    );

                } catch (Exception e) {
                    output.setText("❌ Read failed\n" + e.getMessage());
                }
            });
        }

        /* ================= Write ================= */

        @SuppressLint("SetTextI18n")
        private void writeTag(Tag tag) {
            handler.post(() -> {
                try {
                    Ndef ndef = Ndef.get(tag);
                    if (ndef == null) {
                        output.setText("❌ Tag not NDEF compatible");
                        return;
                    }

                    ndef.connect();

                    if (!ndef.isWritable()) {
                        output.setText("❌ Tag is read-only");
                        return;
                    }

                    String text = writeInput.getText().toString().trim();
                    if (text.isEmpty()) {
                        output.setText("❌ No data to write");
                        return;
                    }

                    NdefMessage msg = new NdefMessage(
                            NdefRecord.createTextRecord("en", text)
                    );

                    if (msg.toByteArray().length > ndef.getMaxSize()) {
                        output.setText("❌ Data exceeds tag capacity");
                        return;
                    }
                    ndef.writeNdefMessage(msg);
                    ndef.close();
                    output.setText("✅ Write successful");
                } catch (Exception e) {
                    output.setText("❌ Write failed\n" + e.getMessage());
                }
            });
        }

        /* ================= Utils ================= */

        private void toast(String s) {
            Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
        }
    }

    /* ========================================================= */
    /* Block Editor Fragment                                     */
    /* ========================================================= */
    public static class NFCBlockEditorFragment extends Fragment {

        Spinner keyTypeSpin, sectorSpin, blockSpin;
        EditText keyInput, blockData;
        TextView blockMeta, result;
        CheckBox allowTrailerWrite;
        Button authBtn, readBtn, writeBtn;

        MifareClassic mfc;
        int authenticatedSector = -1;
        String authenticatedKeyType = "";

        private static final String KEY_AUTO = "Auto key A/B";
        private static final String KEY_A = "Key A";
        private static final String KEY_B = "Key B";

        @Override
        public View onCreateView(LayoutInflater i, ViewGroup c, Bundle b) {
            View v = i.inflate(R.layout.nfc_block_editor, c, false);

            keyTypeSpin = v.findViewById(R.id.keyTypeSpin);
            sectorSpin = v.findViewById(R.id.sectorSpin);
            blockSpin = v.findViewById(R.id.blockSpin);
            keyInput = v.findViewById(R.id.keyInput);
            blockData = v.findViewById(R.id.blockData);
            blockMeta = v.findViewById(R.id.blockMeta);
            result = v.findViewById(R.id.blockResult);
            allowTrailerWrite = v.findViewById(R.id.allowTrailerWrite);

            authBtn = v.findViewById(R.id.authBtn);
            readBtn = v.findViewById(R.id.readBtn);
            writeBtn = v.findViewById(R.id.writeBtn);

            authBtn.setOnClickListener(x -> authenticate());
            readBtn.setOnClickListener(x -> readBlock());
            writeBtn.setOnClickListener(x -> writeBlock());

            initSpinners();
            keyInput.setText(bytesToHex(MifareClassic.KEY_DEFAULT));
            setOperationButtonsEnabled(false);

            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext());

            if (!cap.internalAvailable) {
                result.setText("No onboard NFC adapter detected. Use external scan/dump actions from the Read / Write tab.");
            } else if (!cap.internalEnabled) {
                result.setText("Onboard NFC is disabled. Enable NFC, then tap a MIFARE Classic tag.");
            } else {
                result.setText("Tap a MIFARE Classic tag to enable sector operations.");
            }

            return v;
        }

        private void initSpinners() {
            String[] keyTypes = {KEY_AUTO, KEY_A, KEY_B};
            keyTypeSpin.setAdapter(new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item, keyTypes));

            populateSectors(16);
            sectorSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    clearAuthentication();
                    populateBlocksForSelectedSector();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
            blockSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    updateBlockMeta();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
            keyTypeSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    clearAuthentication();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
            populateBlocksForSelectedSector();
        }

        private void populateSectors(int count) {
            Integer[] sectors = new Integer[count];
            for (int i = 0; i < count; i++) sectors[i] = i;
            sectorSpin.setAdapter(new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item, sectors));
        }

        private void populateBlocksForSelectedSector() {
            int sector = getSelectedSector();
            int blockCount = mfc != null ? mfc.getBlockCountInSector(sector) : (sector >= 32 ? 16 : 4);
            Integer[] blocks = new Integer[blockCount];
            for (int i = 0; i < blockCount; i++) blocks[i] = i;
            blockSpin.setAdapter(new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item, blocks));
            updateBlockMeta();
        }

        private int getSelectedSector() {
            Object selected = sectorSpin.getSelectedItem();
            return selected instanceof Integer ? (Integer) selected : 0;
        }

        private int getSelectedBlockOffset() {
            Object selected = blockSpin.getSelectedItem();
            return selected instanceof Integer ? (Integer) selected : 0;
        }

        private boolean ensureTagConnected() {
            if (mfc == null || !mfc.isConnected()) {
                result.setText("Tap a MIFARE Classic tag first.");
                return false;
            }
            return true;
        }

        private int getAbsoluteBlock() {
            return mfc.sectorToBlock(getSelectedSector()) + getSelectedBlockOffset();
        }

        private boolean isSelectedTrailerBlock() {
            if (mfc == null) return getSelectedBlockOffset() == 3;
            return getSelectedBlockOffset() == mfc.getBlockCountInSector(getSelectedSector()) - 1;
        }

        private String getSelectedKeyType() {
            Object selected = keyTypeSpin.getSelectedItem();
            return selected == null ? KEY_AUTO : selected.toString();
        }

        private void clearAuthentication() {
            authenticatedSector = -1;
            authenticatedKeyType = "";
            updateBlockMeta();
        }

        private void updateBlockMeta() {
            if (blockMeta == null) return;
            if (mfc == null) {
                blockMeta.setText("No tag connected");
                return;
            }

            int sector = getSelectedSector();
            int offset = getSelectedBlockOffset();
            int absolute = mfc.sectorToBlock(sector) + offset;
            boolean trailer = isSelectedTrailerBlock();

            blockMeta.setText(
                    "Sector " + sector +
                            ", block " + offset +
                            " (absolute block " + absolute + ")" +
                            (trailer ? "\nSector trailer: keys/access bits live here." : "")
            );
        }

        private boolean authenticateSelectedSector() {
            if (!ensureTagConnected()) return false;

            byte[] key;
            try {
                key = hexToBytes(keyInput.getText().toString());
            } catch (Exception e) {
                result.setText("Invalid key: " + e.getMessage());
                return false;
            }

            if (key.length != MifareClassic.KEY_DEFAULT.length) {
                result.setText("Key must be 6 bytes / 12 hex characters.");
                return false;
            }

            int sector = getSelectedSector();
            String keyType = getSelectedKeyType();

            try {
                if ((KEY_A.equals(keyType) || KEY_AUTO.equals(keyType))
                        && mfc.authenticateSectorWithKeyA(sector, key)) {
                    authenticatedSector = sector;
                    authenticatedKeyType = KEY_A;
                    result.setText("Authenticated sector " + sector + " with Key A");
                    return true;
                }

                if ((KEY_B.equals(keyType) || KEY_AUTO.equals(keyType))
                        && mfc.authenticateSectorWithKeyB(sector, key)) {
                    authenticatedSector = sector;
                    authenticatedKeyType = KEY_B;
                    result.setText("Authenticated sector " + sector + " with Key B");
                    return true;
                }
            } catch (Exception e) {
                result.setText("Authentication failed: " + e.getMessage());
                return false;
            }

            authenticatedSector = -1;
            authenticatedKeyType = "";
            result.setText("Authentication failed for sector " + sector);
            return false;
        }

        private boolean ensureAuthenticatedForSelectedSector() {
            if (authenticatedSector == getSelectedSector() && !authenticatedKeyType.isEmpty()) {
                return true;
            }
            return authenticateSelectedSector();
        }

        private void setOperationButtonsEnabled(boolean enabled) {
            if (authBtn != null) authBtn.setEnabled(enabled);
            if (readBtn != null) readBtn.setEnabled(enabled);
            if (writeBtn != null) writeBtn.setEnabled(enabled);
        }

        private void authenticate() {
            authenticateSelectedSector();
        }

        private void readBlock() {
            try {
                if (!ensureAuthenticatedForSelectedSector()) return;
                int block = getAbsoluteBlock();
                byte[] data = mfc.readBlock(block);
                blockData.setText(bytesToHex(data));
                result.setText("Read absolute block " + block + " using " + authenticatedKeyType);
            } catch (Exception e) {
                result.setText(e.getMessage());
            }
        }

        private void writeBlock() {
            try {
                if (!ensureAuthenticatedForSelectedSector()) return;
                if (isSelectedTrailerBlock() && !allowTrailerWrite.isChecked()) {
                    result.setText("Sector trailer writes are blocked. Enable the trailer checkbox to continue.");
                    return;
                }
                byte[] data = hexToBytes(blockData.getText().toString());
                if (data.length != MifareClassic.BLOCK_SIZE) {
                    result.setText("Block data must be 16 bytes / 32 hex characters.");
                    return;
                }
                int block = getAbsoluteBlock();
                mfc.writeBlock(block, data);
                result.setText("Block " + block + " written using " + authenticatedKeyType);
            } catch (Exception e) {
                result.setText(e.getMessage());
            }
        }

        /* ================= TAG BIND ================= */

        public void handleTag(Intent intent) {

            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag == null) return;

            MifareClassic newMfc = MifareClassic.get(tag);

            if (newMfc == null) {
                if (result != null) {
                    result.setText("❌ Not a MIFARE Classic tag");
                }
                setOperationButtonsEnabled(false);
                closeTag();
                return;
            }

            try {
                closeTag();
                newMfc.connect();
                this.mfc = newMfc;
                populateSectors(newMfc.getSectorCount());
                populateBlocksForSelectedSector();
                clearAuthentication();
                setOperationButtonsEnabled(true);

                if (result != null) {
                    result.setText("✅ Tag connected\n" +
                            newMfc.getSize() + " bytes, " +
                            newMfc.getSectorCount() + " sectors, " +
                            newMfc.getBlockCount() + " blocks");
                }

            } catch (Exception e) {
                setOperationButtonsEnabled(false);
                if (result != null) {
                    result.setText("Connection failed: " + e.getMessage());
                }
            }
        }

        @Override
        public void onDestroyView() {
            closeTag();
            super.onDestroyView();
        }

        private void closeTag() {
            try {
                if (mfc != null) {
                    mfc.close();
                }
            } catch (Exception ignored) {
            } finally {
                mfc = null;
            }
        }
    }

    /* ========================================================= */
    /* Dump Viewer Fragment                                 */
    /* ========================================================= */
    public static class NFCDumpViewerFragment extends Fragment {

        private static final int MAX_DUMP_CARDS = 25;

        TextView summary;
        LinearLayout dumpList;

        @Override
        public View onCreateView(LayoutInflater i, ViewGroup c, Bundle b) {
            View v = i.inflate(R.layout.nfc_dump_viewer, c, false);
            summary = v.findViewById(R.id.dumpSummary);
            dumpList = v.findViewById(R.id.dumpList);
            Button refresh = v.findViewById(R.id.dumpRefresh);
            refresh.setOnClickListener(x -> loadDumpHistory());
            loadDumpHistory();
            return v;
        }

        private void loadDumpHistory() {
            try {
                File dir = new File(
                        Environment.getExternalStorageDirectory(),
                        "nh_files/NFC"
                );
                if (!dir.exists()) {
                    summary.setText("No dumps found.\n\nFolder: " + dir.getAbsolutePath());
                    dumpList.removeAllViews();
                    return;
                }

                File[] dumps = dir.listFiles((d, name) -> isDumpFile(name));
                if (dumps == null || dumps.length == 0) {
                    summary.setText("No dumps found.\n\nFolder: " + dir.getAbsolutePath());
                    dumpList.removeAllViews();
                    return;
                }

                Arrays.sort(dumps, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

                dumpList.removeAllViews();
                int shown = Math.min(dumps.length, MAX_DUMP_CARDS);
                summary.setText(
                        dumps.length + " dump file" + (dumps.length == 1 ? "" : "s") +
                                " found. Showing " + shown + " most recent.\n\n" +
                                "Folder: " + dir.getAbsolutePath()
                );

                for (int index = 0; index < shown; index++) {
                    dumpList.addView(createDumpCard(dumps[index]));
                }
            } catch (Exception e) {
                summary.setText("Failed to load dump history: " + e.getMessage());
                if (dumpList != null) dumpList.removeAllViews();
            }
        }

        private boolean isDumpFile(String name) {
            String lower = name.toLowerCase(Locale.US);
            return lower.endsWith(".json")
                    || lower.endsWith(".mfd")
                    || lower.endsWith(".dump")
                    || lower.endsWith(".bin")
                    || lower.endsWith(".eml")
                    || lower.endsWith(".nfc");
        }

        private View createDumpCard(File file) {
            MaterialCardView card = new MaterialCardView(requireContext());
            card.setRadius(dp(8));
            card.setStrokeWidth(1);
            card.setStrokeColor(Color.parseColor("#555555"));
            card.setUseCompatPadding(true);
            card.setContentPadding(dp(12), dp(10), dp(12), dp(10));

            LinearLayout body = new LinearLayout(requireContext());
            body.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(requireContext());
            title.setText(buildDumpTitle(file));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextSize(15);
            body.addView(title);

            TextView meta = new TextView(requireContext());
            meta.setText(formatDumpMeta(file));
            meta.setTextSize(12);
            meta.setTextColor(Color.GRAY);
            body.addView(meta);

            TextView preview = new TextView(requireContext());
            preview.setText(buildDumpPreview(file));
            preview.setTextSize(12);
            preview.setPadding(0, dp(8), 0, 0);
            body.addView(preview);

            card.addView(body);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, 0, dp(10));
            card.setLayoutParams(lp);
            card.setOnClickListener(v -> showDumpDetails(file));
            return card;
        }

        private String buildDumpTitle(File file) {
            String uid = extractUid(file);
            if (!uid.isEmpty()) {
                return "UID " + uid;
            }
            return file.getName();
        }

        private String formatDumpMeta(File file) {
            return file.getName() +
                    "\n" + formatFileSize(file.length()) +
                    " • " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(file.lastModified()));
        }

        private String buildDumpPreview(File file) {
            if (file.getName().toLowerCase(Locale.US).endsWith(".json")) {
                try {
                    JSONObject json = new JSONObject(readTextPreview(file, 8192));
                    String source = json.optString("source", "internal");
                    String techs = json.optString("techs", "");
                    return "Source: " + source + "\nTechs: " + trimForCard(techs);
                } catch (Exception ignored) {
                    return trimForCard(readTextPreview(file, 512));
                }
            }

            return "External/tool dump\nTap to view path and metadata.";
        }

        private String extractUid(File file) {
            if (!file.getName().toLowerCase(Locale.US).endsWith(".json")) return "";
            try {
                JSONObject json = new JSONObject(readTextPreview(file, 8192));
                return json.optString("uid", "");
            } catch (Exception ignored) {
                return "";
            }
        }

        private void showDumpDetails(File file) {
            String details = formatDumpMeta(file) +
                    "\n\nPath:\n" + file.getAbsolutePath() +
                    "\n\nPreview:\n" + buildDialogPreview(file);

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(buildDumpTitle(file))
                    .setMessage(details)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy Path", (dialog, which) -> copyPath(file))
                    .show();
        }

        private String buildDialogPreview(File file) {
            if (file.getName().toLowerCase(Locale.US).endsWith(".json")) {
                return readTextPreview(file, 6000);
            }
            return "Binary or tool-generated dump. Use the path above from Kali or Android storage.";
        }

        private void copyPath(File file) {
            ClipboardManager clipboard =
                    (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("NFC dump path", file.getAbsolutePath()));
                Toast.makeText(requireContext(), "Path copied", Toast.LENGTH_SHORT).show();
            }
        }

        private String readTextPreview(File file, int maxChars) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null && sb.length() < maxChars) {
                    sb.append(line).append("\n");
                }
            } catch (Exception e) {
                return "Preview unavailable: " + e.getMessage();
            }

            if (sb.length() > maxChars) {
                return sb.substring(0, maxChars) + "\n...";
            }
            return sb.toString().trim();
        }

        private String trimForCard(String value) {
            if (value == null || value.trim().isEmpty()) return "Unknown";
            String normalized = value.replace("\n", " ").trim();
            return normalized.length() > 160 ? normalized.substring(0, 160) + "..." : normalized;
        }

        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) {
                return String.format(Locale.US, "%.1f KB", bytes / 1024f);
            }
            return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    /* ========================================================= */
    /* Advanced Attacks Fragment                                 */
    /* ========================================================= */
    public static class NFCAdvancedFragment extends Fragment {

        @Override
        public View onCreateView(LayoutInflater i, ViewGroup c, Bundle b) {
            View v = i.inflate(R.layout.nfc_advanced, c, false);

            configureAdvanced(v);

            v.findViewById(R.id.advRefreshHardware).setOnClickListener(x -> {
                NfcCapabilityEngine.invalidate();
                configureAdvanced(v);
            });
            v.findViewById(R.id.readerDiagnostics).setOnClickListener(x -> runDiagnostics());
            v.findViewById(R.id.hfSearch).setOnClickListener(x -> runHfSearch());
            v.findViewById(R.id.mfClassicDump).setOnClickListener(x -> runClassicDump());
            v.findViewById(R.id.mfKeyRecovery).setOnClickListener(x -> runKeyRecovery());
            v.findViewById(R.id.uidSpoof).setOnClickListener(x -> runUidSpoof());
            v.findViewById(R.id.replayAttack).setOnClickListener(x -> runReplay());
            v.findViewById(R.id.relayAttack).setOnClickListener(x ->
                    showRelayWarning(NfcCapabilityEngine.get(requireContext(), true)));

            return v;
        }

        private void configureAdvanced(View v) {
            TextView status = v.findViewById(R.id.hwStatus);
            TextView matrix = v.findViewById(R.id.advCapabilityMatrix);
            Button diagnostics = v.findViewById(R.id.readerDiagnostics);
            Button search = v.findViewById(R.id.hfSearch);
            Button dump = v.findViewById(R.id.mfClassicDump);
            Button recovery = v.findViewById(R.id.mfKeyRecovery);
            Button uid = v.findViewById(R.id.uidSpoof);
            Button replay = v.findViewById(R.id.replayAttack);
            Button relay = v.findViewById(R.id.relayAttack);

            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);

            boolean proxmarkReady = cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3
                    && cap.proxmarkInstalled;
            boolean libnfcReady = cap.externalAvailable && cap.libnfcInstalled;
            boolean mfocReady = cap.externalAvailable && cap.mfocInstalled;
            boolean canSearch = proxmarkReady || libnfcReady;
            boolean canDump = proxmarkReady || mfocReady;

            diagnostics.setEnabled(canSearch);
            search.setEnabled(canSearch);
            dump.setEnabled(canDump);
            recovery.setEnabled(canDump);
            uid.setEnabled(cap.canSpoofUID());
            replay.setEnabled(cap.canReplayRF());
            relay.setEnabled(cap.canRelay());

            if (!cap.externalAvailable) {
                status.setText(
                        "External NFC adapter required\n\n" +
                                "Onboard Android NFC supports tag reads/writes, but advanced workflows require USB hardware."
                );
            } else {
                status.setText("External adapter detected\n\n" + cap.describeExternal());
            }

            matrix.setText(
                    "Diagnostics : " + yesNo(canSearch) + "\n" +
                            "HF search   : " + yesNo(canSearch) + "\n" +
                            "MIFARE dump : " + yesNo(canDump) + "\n" +
                            "Key recovery: " + yesNo(canDump) + "\n" +
                            "UID spoof   : " + yesNo(cap.canSpoofUID()) + "\n" +
                            "RF replay   : " + yesNo(cap.canReplayRF()) + "\n" +
                            "Relay plan  : " + yesNo(cap.canRelay())
            );
        }

        private void runDiagnostics() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);
            if (cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3 && cap.proxmarkInstalled) {
                run("proxmark3" + cap.proxmarkDeviceArg() + " -c 'hw status'");
            } else if (cap.libnfcInstalled) {
                run("nfc-list -v");
            } else {
                toast("Install NFC tools first");
            }
        }

        private void runHfSearch() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);
            if (cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3 && cap.proxmarkInstalled) {
                run("proxmark3" + cap.proxmarkDeviceArg() + " -c 'hf search'");
            } else if (cap.libnfcInstalled) {
                run("nfc-list");
            } else {
                toast("No external scan tool available");
            }
        }

        private void runClassicDump() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);
            if (cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3 && cap.proxmarkInstalled) {
                run("mkdir -p /sdcard/nh_files/NFC; proxmark3" +
                        cap.proxmarkDeviceArg() +
                        " -c 'hf mf autopwn; hf mf dump'");
            } else if (cap.mfocInstalled) {
                run("mkdir -p /sdcard/nh_files/NFC; mfoc -O /sdcard/nh_files/NFC/mfoc_$(date +%Y%m%d_%H%M%S).mfd");
            } else {
                toast("No MIFARE dump tool available");
            }
        }

        private void runKeyRecovery() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);
            if (cap.adapter == NfcCapabilityEngine.ExternalAdapter.PROXMARK3 && cap.proxmarkInstalled) {
                run("mkdir -p /sdcard/nh_files/NFC; proxmark3" +
                        cap.proxmarkDeviceArg() +
                        " -c 'hf mf autopwn'");
            } else if (cap.mfocInstalled) {
                run("mkdir -p /sdcard/nh_files/NFC; mfoc -O /sdcard/nh_files/NFC/mfoc_keys_$(date +%Y%m%d_%H%M%S).mfd");
            } else {
                toast("No key recovery tool available");
            }
        }

        private void runUidSpoof() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);
            if (!cap.canSpoofUID()) {
                toast("UID spoofing requires Proxmark3");
                return;
            }
            run("proxmark3" + cap.proxmarkDeviceArg() + " -c 'hf 14a sim'");
        }

        private void runReplay() {
            NfcCapabilityEngine.Capability cap =
                    NfcCapabilityEngine.get(requireContext(), true);
            if (!cap.canReplayRF()) {
                toast("RF replay requires Proxmark3");
                return;
            }
            run("proxmark3" + cap.proxmarkDeviceArg() + " -c 'hf 14a replay'");
        }

        private void run(String cmd) {
            startActivity(Bridge.createExecuteIntent(
                    "/data/data/com.offsec.nhterm/files/usr/bin/kali",
                    cmd
            ));
        }

        private void showRelayWarning(NfcCapabilityEngine.Capability cap) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Relay Attack")
                    .setMessage(
                            "Relay attacks require:\n\n" +
                                    "• Two NFC readers\n" +
                                    "• One near the victim card\n" +
                                    "• One near the reader terminal\n\n" +
                                    "Detected adapter: " + cap.adapterLabel() + "\n\n" +
                                    "Android internal NFC cannot perform this by itself."
                    )
                    .setPositiveButton("OK", null)
                    .show();
        }

        private String yesNo(boolean value) {
            return value ? "available" : "unavailable";
        }

        private void toast(String message) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }



    /* ===================== */
    /* Tools Fragment        */
    /* ===================== */
    private void showInstallToolsDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Install NFC Tools")
                .setMessage(
                        "This will install the following tools inside Kali NetHunter:\n\n" +
                                "• libnfc-bin\n" +
                                "• mfoc\n" +
                                "• mfcuk\n" +
                                "• libfreefare\n" +
                                "• pcscd / pcsc-tools\n" +
                                "• proxmark3\n\n" +
                                "Proceed?"
                )
                .setPositiveButton("Install", (d, w) ->
                        startActivity(
                                Bridge.createExecuteIntent(
                                        "/data/data/com.offsec.nhterm/files/usr/bin/kali",
                                        "apt update && apt install libnfc-bin mfoc mfcuk libfreefare-bin pcscd pcsc-tools proxmark3 -y"
                                )
                        )
                )
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showNfcInfoDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("NFC Arsenal Info")
                .setMessage(
                        "NFC Arsenal — NetHunter\n\n" +
                                "Onboard NFC:\n" +
                                "• Android tag discovery\n" +
                                "• NDEF read/write\n" +
                                "• MIFARE Classic access when supported by the device\n\n" +
                                "External NFC:\n" +
                                "• libnfc scan/dump workflows\n" +
                                "• Proxmark3 UID spoofing and RF replay\n" +
                                "• Relay workflows with multiple readers\n\n" +
                                "Supported adapters:\n" +
                                "• Proxmark3\n" +
                                "• PN532 / PN53x\n" +
                                "• ACR122U / ACS CCID\n" +
                                "• libnfc-compatible USB readers"
                )
                .setPositiveButton("OK", null)
                .show();
    }

    /* ========================================================= */
    /* Tag Dump Helpers                                          */
    /* ========================================================= */
    static class TagDump {
        String uid;
        String[] techs;
        String source;
        String createdAt;

        static TagDump fromTag(Tag tag) {
            TagDump d = new TagDump();
            d.uid = bytes(tag.getId());
            d.techs = tag.getTechList();
            d.source = "internal";
            d.createdAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            return d;
        }

        String pretty() {
            return "UID: " + uid + "\n\nTechs:\n" + String.join("\n", techs);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("uid", uid);
                o.put("techs", Arrays.toString(techs));
                o.put("source", source);
                o.put("createdAt", createdAt);
            } catch (Exception ignored) {}
            return o;
        }

        static String bytes(byte[] b) {
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02X", x));
            return sb.toString();
        }
    }

    static class NfcDumpWriter {
        static void save(TagDump d) {
            try {
                File dir = new File(
                        Environment.getExternalStorageDirectory(),
                        "nh_files/NFC");
                dir.mkdirs();
                File f = new File(dir,
                        "tag_" + new SimpleDateFormat("yyyyMMdd_HHmmss")
                                .format(new Date()) + ".json");
                FileWriter w = new FileWriter(f);
                w.write(d.toJson().toString(2));
                w.close();
            } catch (Exception ignored) {}
        }
    }

    /* ========================================================= */
    /* Activity Bridge                                           */
    /* ========================================================= */
    public static void dispatchIntent(FragmentActivity activity, Intent intent) {

        if (activity == null || intent == null) return;

        Fragment f = activity
                .getSupportFragmentManager()
                .findFragmentById(R.id.container);

        if (!(f instanceof NFCFragment)) return;

        NFCFragment nfc = (NFCFragment) f;

        /* ================= READ/WRITE FRAGMENT ================= */

        Fragment rw =
                nfc.getChildFragmentManager()
                        .findFragmentByTag("f1");

        if (rw instanceof NFCReadWriteFragment) {
            ((NFCReadWriteFragment) rw).handleTag(intent);
        }

        /* ================= BLOCK EDITOR FRAGMENT ================= */

        Fragment block =
                nfc.getChildFragmentManager()
                        .findFragmentByTag("f2");

        if (block instanceof NFCBlockEditorFragment) {
            ((NFCBlockEditorFragment) block).handleTag(intent);
        }
    }

    static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        if (hex.length() % 2 != 0)
            throw new IllegalArgumentException("Invalid HEX length");

        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid HEX character");
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
            sb.append(String.format("%02X", b));
        return sb.toString();
    }

    static String decodeAccessBits(byte[] trailer) {
        int c1 = (trailer[7] >> 4) & 0xF;
        int c2 = trailer[8] & 0xF;
        int c3 = (trailer[8] >> 4) & 0xF;
        return "Access Bits:\nC1=" + c1 + " C2=" + c2 + " C3=" + c3;
    }

    static String getSystemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Exception e) {
            return "";
        }
    }

    static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "Unknown";
    }
}
