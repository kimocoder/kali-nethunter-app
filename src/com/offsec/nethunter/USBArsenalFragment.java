package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.offsec.nethunter.HandlerThread.USBArsenalHandlerThread;
import com.offsec.nethunter.SQL.USBArsenalSQL;
import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.models.USBArsenalUSBNetworkModel;
import com.offsec.nethunter.models.USBArsenalUSBSwitchModel;
import com.offsec.nethunter.utils.NhPaths;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class USBArsenalFragment extends Fragment {
    private static final String ARG_SECTION_NUMBER = "section_number";
    private static final String TAG = "USBArsenalFragment";
    private Context context;
    private Activity activity;
    private final USBArsenalHandlerThread usbArsenalHandlerThread = new USBArsenalHandlerThread();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static boolean is_init_exists = true;
    //private Message msg = new Message();
    private TextView usbStatusTextView;
    private TextView mountedImageTextView;
    private TextView mountedImageHintTextView;

    private TextView usbNetworkTetheringHintTextView;
    private ImageButton reloadUSBStateImageButton;
    private ImageButton reloadMountStateButton;
    private LinearLayout imageMounterLL;
    private LinearLayout usbNetworkTetheringLL;
    private Button setUSBIfaceButton;
    private Button mountImgButton;
    private Button unmountImgButton;
    private Button setUSBNetworkTetheringButton;
    private Button saveUSBFunctionConfigButton;
    private Button saveUSBNetworkTetheringConfigButton;
    private Spinner targetOSSpinner;
    private Spinner imgFileSpinner;
    private Spinner usbFuncSpinner;
    private Spinner usbNetworkAttackModeSpinner;
    private Spinner adbSpinner;

    private EditText[] usbSwitchInfoEditTextGroup = new EditText[5];
    private EditText[] usbNetworkInfoEditTextGroup = new EditText[5];
    private String usbStorageFunctionName;

    public static USBArsenalFragment newInstance(int sectionNumber) {
        USBArsenalFragment fragment = new USBArsenalFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context     = getContext();
        activity    = getActivity();
        usbArsenalHandlerThread.start();
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.usbarsenal, container, false);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        EditText inquiryStringEditText;
        TextView currentInquiryHintTextView;
        super.onViewCreated(view, savedInstanceState);
        targetOSSpinner                     = view.findViewById(R.id.f_usbarsenal_spr_targetplatform);
        usbFuncSpinner                      = view.findViewById(R.id.f_usbarsenal_spr_usbfunctions);
        adbSpinner                          = view.findViewById(R.id.f_usbarsenal_spr_adb);
        imgFileSpinner                      = view.findViewById(R.id.f_usbarsenal_spr_img_files);
        usbNetworkAttackModeSpinner         = view.findViewById(R.id.f_usbarsenal_spr_networkattackmode);
        setUSBIfaceButton                   = view.findViewById(R.id.f_usbarsenal_btn_setusbinterface);
        setUSBNetworkTetheringButton        = view.findViewById(R.id.f_usbarsenal_btn_runusbnetworktethering);
        mountImgButton                      = view.findViewById(R.id.f_usbarsenal_btn_mountImage);
        unmountImgButton                    = view.findViewById(R.id.f_usbarsenal_btn_unmountImage);
        Button      changeInquiryButton     = view.findViewById(R.id.f_usbarsenal_btn_changeInquiryString);
        reloadUSBStateImageButton           = view.findViewById(R.id.f_usbarsenal_imgbtn_reloadUSBStatus);
        reloadMountStateButton              = view.findViewById(R.id.f_usbarsenal_imgbtn_reloadMountStatus);
        saveUSBFunctionConfigButton         = view.findViewById(R.id.f_usbarsenal_btn_saveusbfuncswitch);
        saveUSBNetworkTetheringConfigButton = view.findViewById(R.id.f_usbarsenal_btn_saveusbnetworktethering);
        CheckBox    readOnlyCheckBox        = view.findViewById(R.id.f_usbarsenal_chkbox_ReadOrWrite);
        usbStatusTextView                   = view.findViewById(R.id.f_usbarsenal_tv_current_usb_state);
        mountedImageTextView                = view.findViewById(R.id.f_usbarsenal_tv_mount_state);
        currentInquiryHintTextView          = view.findViewById(R.id.f_usbarsenal_tv_current_inquiry_string);
        mountedImageHintTextView            = view.findViewById(R.id.f_usbarsenal_ll_tv_imagemounter_hint);
        usbNetworkTetheringHintTextView     = view.findViewById(R.id.f_usbarsenal_ll_tv_usbnetworktethering_hint);
        imageMounterLL                      = view.findViewById(R.id.f_usbarsenal_ll_imageMounter_sub2);
        usbNetworkTetheringLL               = view.findViewById(R.id.f_usbarsenal_ll_usbnetworktethering_sub2);
        inquiryStringEditText               = view.findViewById(R.id.f_usbarsenal_et_inquiryString);

        usbSwitchInfoEditTextGroup[0] = view.findViewById(R.id.f_usbarsenal_et_idvendor);
        usbSwitchInfoEditTextGroup[1] = view.findViewById(R.id.f_usbarsenal_et_idproduct);
        usbSwitchInfoEditTextGroup[2] = view.findViewById(R.id.f_usbarsenal_et_manufacturer);
        usbSwitchInfoEditTextGroup[3] = view.findViewById(R.id.f_usbarsenal_et_product);
        usbSwitchInfoEditTextGroup[4] = view.findViewById(R.id.f_usbarsenal_et_serialnumber);
        usbNetworkInfoEditTextGroup[0] = view.findViewById(R.id.f_usbarsenal_et_usbnetwork_upstreamiface);
        usbNetworkInfoEditTextGroup[1] = view.findViewById(R.id.f_usbarsenal_et_usbnetwork_usbfunc);
        usbNetworkInfoEditTextGroup[2] = view.findViewById(R.id.f_usbarsenal_et_usbnetwork_targetip);
        usbNetworkInfoEditTextGroup[3] = view.findViewById(R.id.f_usbarsenal_et_usbnetwork_gatewayip);
        usbNetworkInfoEditTextGroup[4] = view.findViewById(R.id.f_usbarsenal_et_usbnetwork_ipsubnetmask);

        { Message msg = new Message();
            msg.what = USBArsenalHandlerThread.IS_INIT_EXIST;
            msg.obj = "[ -f /init.nethunter.rc ]";
            usbArsenalHandlerThread.getHandler().sendMessage(msg); }

        { Message msg = new Message();
            msg.what = USBArsenalHandlerThread.GET_STORAGE_FUNC_FOLDER_NAME;
            msg.obj = "find /config/usb_gadget/g1/functions/ -name \"mass_storage.*\" -maxdepth 1 -type d -exec basename {} \\; | head -n1";
            usbArsenalHandlerThread.getHandler().sendMessage(msg); }

        ArrayAdapter<String> usbFuncWinArrayAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, new ArrayList<>());
        ArrayAdapter<String> usbFuncMACArrayAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, new ArrayList<>());
        ArrayAdapter<String> usbNetworkAttackModeArrayAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, getResources().getStringArray(R.array.usbarsenal_usb_network_attack_mode));
        usbFuncWinArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        usbFuncMACArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        usbNetworkAttackModeArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        targetOSSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 2){
                    usbFuncSpinner.setAdapter(usbFuncMACArrayAdapter);
                } else {
                    usbFuncSpinner.setAdapter(usbFuncWinArrayAdapter);
                }
                refreshUSBSwitchInfos(gettargetOSSpinnerString(), getusbFuncSpinnerString());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // TODO document why this method is empty
            }
        });

        usbFuncSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0 || is_init_exists) {
                    adbSpinner.setSelection(1);
                    adbSpinner.setEnabled(false);
                } else {
                    adbSpinner.setSelection(0);
                    adbSpinner.setEnabled(true);
                }
                refreshUSBSwitchInfos(gettargetOSSpinnerString(), getusbFuncSpinnerString());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // TODO document why this method is empty
            }
        });

        adbSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshUSBSwitchInfos(gettargetOSSpinnerString(), getusbFuncSpinnerString());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // TODO document why this method is empty
            }
        });

        usbNetworkAttackModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshUSBNetworkInfos(getusbNetWorkModeSpinnerPosition());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // TODO document why this method is empty
            }
        });

        setUSBIfaceButton.setOnClickListener(this::onClick);

        setUSBNetworkTetheringButton.setOnClickListener(v -> run_cmd_android("usbtethering -o " + usbNetworkInfoEditTextGroup[0].getText().toString() +
                " -i " + usbNetworkInfoEditTextGroup[1].getText().toString() +
                " -A " + usbNetworkInfoEditTextGroup[2].getText().toString() +
                " -B " + usbNetworkInfoEditTextGroup[2].getText().toString() +
                " -C " + usbNetworkInfoEditTextGroup[3].getText().toString() +
                " -D " + usbNetworkInfoEditTextGroup[4].getText().toString()));

        reloadUSBStateImageButton.setOnClickListener(v -> {
            Message msg = new Message();
            msg.what = USBArsenalHandlerThread.RELOAD_USBIFACE;
            msg.obj = "find /config/usb_gadget/g1/configs/b.1 -type l -exec readlink -e {} \\; | xargs echo";
            usbArsenalHandlerThread.getHandler().sendMessage(msg);
        });

        reloadMountStateButton.setOnClickListener(v -> {
            Message msg = new Message();
            msg.what = USBArsenalHandlerThread.RELOAD_MOUNTSTATUS;
            msg.obj = "cat /config/usb_gadget/g1/functions/" + usbStorageFunctionName + "/lun.0/file";
            usbArsenalHandlerThread.getHandler().sendMessage(msg);
            getImageFiles();
        });

        mountImgButton.setOnClickListener(v -> {
            if (imgFileSpinner.getSelectedItem() == null){
                NhPaths.showMessage(context, "No image file is selected.");
            } else {
                mountImgButton.setEnabled(false);
                unmountImgButton.setEnabled(false);
                Message msg = new Message();
                msg.what = USBArsenalHandlerThread.MOUNT_IMAGE;
                if (readOnlyCheckBox.isChecked())
                    msg.obj = String.format("%s%s && echo '%s/%s' > /config/usb_gadget/g1/functions/" + usbStorageFunctionName + "/lun.0/file",
                            "echo '1' > /config/usb_gadget/g1/functions/" + usbStorageFunctionName + "/lun.0/ro",
                            imgFileSpinner.getSelectedItem().toString().contains(".iso") ? " && echo '1' > /config/usb_gadget/g1/functions/" + usbStorageFunctionName + "/lun.0/cdrom" : " && echo '0' > /config/usb_gadget/g1/functions/mass_storage.gs6/lun.0/cdrom",
                            NhPaths.APP_SD_FILES_IMG_PATH,
                            imgFileSpinner.getSelectedItem().toString());
                else
                    msg.obj = String.format("%s%s && echo '%s/%s' > /config/usb_gadget/g1/functions/" + usbStorageFunctionName + "/lun.0/file",
                            "echo '0' > /config/usb_gadget/g1/functions/" + usbStorageFunctionName + "/lun.0/ro",
                            imgFileSpinner.getSelectedItem().toString().contains(".iso") ? " && echo '1' > /config/usb_gadget/g1/functions/" + usbStorageFunctionName + "/lun.0/cdrom" : " && echo '0' > /config/usb_gadget/g1/functions/mass_storage.gs6/lun.0/cdrom",
                            NhPaths.APP_SD_FILES_IMG_PATH,
                            imgFileSpinner.getSelectedItem().toString());
                usbArsenalHandlerThread.getHandler().sendMessage(msg);
            }
        });

        unmountImgButton.setOnClickListener(v -> {
            mountImgButton.setEnabled(false);
            unmountImgButton.setEnabled(false);
            Message msg = new Message();
            msg.what = USBArsenalHandlerThread.UNMOUNT_IMAGE;
            msg.obj = String.format("echo '' > /config/usb_gadget/g1/functions/%s/lun.0/file" +
                                    " && echo '0' > /config/usb_gadget/g1/functions/%s/lun.0/ro" +
                                    " && echo '0' > /config/usb_gadget/g1/functions/%s/lun.0/cdrom",
                      usbStorageFunctionName, usbStorageFunctionName, usbStorageFunctionName);
            usbArsenalHandlerThread.getHandler().sendMessage(msg);
        });

        changeInquiryButton.setOnClickListener(v -> {
            Message msg = new Message();
            msg.what = USBArsenalHandlerThread.CHANGE_INQUIRY_STRING;
            msg.obj = String.format("echo '%s' > /config/usb_gadget/g1/functions/%s/lun.0/inquiry_string", inquiryStringEditText.getText().toString(), usbStorageFunctionName);
            usbArsenalHandlerThread.getHandler().sendMessage(msg);
        });

        saveUSBFunctionConfigButton.setOnClickListener(v -> {
            if (!usbSwitchInfoEditTextGroup[0].getText().toString().matches("0x[0-9a-fA-F]{4}") ||
                    !usbSwitchInfoEditTextGroup[1].getText().toString().matches("0x[0-9a-fA-F]{4}")) {
                new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Invalid Format").setMessage("The regex must be 0x[0-9a-fA-F]{4}").create().show();
            } else if (!usbSwitchInfoEditTextGroup[2].getText().toString().matches("\\w+|^$") ||
                    !usbSwitchInfoEditTextGroup[3].getText().toString().matches("\\w+|^$")) {
                new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Invalid Format").setMessage("The regex must be \\w*|^$").create().show();
            } else if (!usbSwitchInfoEditTextGroup[4].getText().toString().matches("[0-9A-Z]{10}|^$")) {
                new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Invalid Format").setMessage("The regex must be [0-9A-Z]{10}|^$").create().show();
            } else {
                for (int i = 0; i < usbSwitchInfoEditTextGroup.length; i++) {
                    if (!USBArsenalSQL.getInstance(context).setUSBSwitchColumnData(
                            getusbFuncSpinnerString(),
                            i + 2,
                            targetOSSpinner.getSelectedItem().toString(),
                            usbSwitchInfoEditTextGroup[i].getText().toString().toLowerCase())){
                        NhPaths.showMessage(context, "Something's wrong when processing " + usbSwitchInfoEditTextGroup[i].getText().toString().toLowerCase());
                    } else {
                        NhPaths.showMessage(context, "Saved.");
                    }
                }
            }
        });

        saveUSBNetworkTetheringConfigButton.setOnClickListener(v -> {
            USBArsenalUSBNetworkModel usbArsenalUSBNetworkModel = new USBArsenalUSBNetworkModel(
                    usbNetworkInfoEditTextGroup[0].getText().toString(),
                    usbNetworkInfoEditTextGroup[1].getText().toString(),
                    usbNetworkInfoEditTextGroup[2].getText().toString(),
                    usbNetworkInfoEditTextGroup[3].getText().toString(),
                    usbNetworkInfoEditTextGroup[4].getText().toString()
            );
            if(!USBArsenalSQL.getInstance(context).setUSBNetworkColumnData(getusbNetWorkModeSpinnerPosition(), usbArsenalUSBNetworkModel)){
                NhPaths.showMessage(context, "Failed saving configs to DB, please check if your input is valid.");
            } else {
                NhPaths.showMessage(context, "Saved.");
            }
        });

        usbArsenalHandlerThread.setOnShellExecuterFinishedListener((resultObject, actionCode) -> {
            if (getView() != null) {
                switch (actionCode){
                    case USBArsenalHandlerThread.IS_INIT_EXIST:
                        if ((int)resultObject == 0) {
                            is_init_exists = true;
                            { Message msg = new Message();
                                msg.what = USBArsenalHandlerThread.RETRIEVE_USB_FUNCS;
                                msg.obj = "cat /init.nethunter.rc | grep -E -o 'sys.usb.config=([a-zA-Z,_]+)' | sed 's/sys.usb.config=//' | sort | uniq";
                                usbArsenalHandlerThread.getHandler().sendMessage(msg); }
                        } else {
                            is_init_exists = false;
                            uiHandler.post(() -> {
                                usbFuncWinArrayAdapter.clear();
                                usbFuncWinArrayAdapter.addAll(getResources().getStringArray(R.array.usbarsenal_usb_states_win_lin));
                                usbFuncMACArrayAdapter.clear();
                                usbFuncMACArrayAdapter.addAll(getResources().getStringArray(R.array.usbarsenal_usb_states_mac));
                            });
                        }
                        break;
                    case USBArsenalHandlerThread.RETRIEVE_USB_FUNCS:
                        uiHandler.post(() -> {
                            ArrayList<String> usbFuncArray = new ArrayList<>(Arrays.asList(resultObject.toString().split("\\n")));
                            List<String> usbFuncWinArray = Lists.newArrayList(Collections2.filter(usbFuncArray,
                                    Predicates.containsPattern("win")));
                            List<String> usbFuncMacArray = Lists.newArrayList(Collections2.filter(usbFuncArray,
                                    Predicates.containsPattern("mac")));
                            usbFuncWinArrayAdapter.clear();
                            usbFuncWinArrayAdapter.addAll(usbFuncWinArray);
                            usbFuncMACArrayAdapter.clear();
                            usbFuncMACArrayAdapter.addAll(usbFuncMacArray);
                            for (EditText infoEditText: usbSwitchInfoEditTextGroup) {
                                infoEditText.setEnabled(false);
                            }
                            saveUSBFunctionConfigButton.setEnabled(false);
                            adbSpinner.setEnabled(false);
                        });
                        break;
                    case USBArsenalHandlerThread.SETUSBIFACE:
                        uiHandler.post(() -> {
                            if ((int)resultObject != 0){
                                NhPaths.showMessage(context, "Failed to set USB function.");
                            } else {
                                NhPaths.showMessage(context, "USB function set successfully.");
                                reloadUSBStateImageButton.performClick();
                            }
                            setUSBIfaceButton.setEnabled(true);
                        });
                        break;
                    case USBArsenalHandlerThread.RELOAD_USBIFACE:
                        uiHandler.post(() -> {
                            if (resultObject.toString().isEmpty()) {
                                usbStatusTextView.setText("No USB function has been enabled");
                                imageMounterLL.setVisibility(View.GONE);
                                mountedImageHintTextView.setVisibility(View.VISIBLE);
                            } else {
                                usbStatusTextView.setText(resultObject.toString()
                                        .replace("/config/usb_gadget/g1/functions/", "")
                                        .replace("/config/usb_gadget/g1/functions","gsi.rndis")
                                        .replace(" ", "\n"));
                                if (usbStatusTextView.getText().toString().contains("mass_storage")){
                                    imageMounterLL.setVisibility(View.VISIBLE);
                                    mountedImageHintTextView.setVisibility(View.GONE);
                                    getImageFiles();
                                } else {
                                    imageMounterLL.setVisibility(View.GONE);
                                    mountedImageHintTextView.setVisibility(View.VISIBLE);
                                }
                                if (usbStatusTextView.getText().toString().contains("rndis") ||
                                        usbStatusTextView.getText().toString().contains("acm")){
                                    usbNetworkTetheringLL.setVisibility(View.VISIBLE);
                                    usbNetworkTetheringHintTextView.setVisibility(View.GONE);
                                    refreshUSBNetworkInfos(getusbNetWorkModeSpinnerPosition());
                                } else {
                                    usbNetworkTetheringLL.setVisibility(View.GONE);
                                    usbNetworkTetheringHintTextView.setVisibility(View.VISIBLE);
                                }
                            }
                        });
                        break;
                    case USBArsenalHandlerThread.GET_STORAGE_FUNC_FOLDER_NAME:
                        String folder_name = resultObject.toString().split("\\n")[0];
                        // fallback to mass_storage.0 if other not found
                        if (folder_name.isEmpty()) folder_name = "mass_storage.0";
                        usbStorageFunctionName = folder_name;
                        break;
                    case USBArsenalHandlerThread.RELOAD_MOUNTSTATUS:
                        uiHandler.post(() -> {
                            if (resultObject.toString().isEmpty()){ mountedImageTextView.setText("No image is mounted."); }
                            else {mountedImageTextView.setText(resultObject.toString());}
                        });
                        break;
                    case USBArsenalHandlerThread.MOUNT_IMAGE:
                        uiHandler.post(() -> {
                            if ((int)resultObject == 0){
                                NhPaths.showMessage(context, imgFileSpinner.getSelectedItem().toString() + " has been mounted.");
                            } else {
                                NhPaths.showMessage(context, "Failed to mount image " + imgFileSpinner.getSelectedItem().toString());
                            }
                            reloadMountStateButton.performClick();
                            mountImgButton.setEnabled(true);
                            unmountImgButton.setEnabled(true);
                        });
                        break;
                    case USBArsenalHandlerThread.UNMOUNT_IMAGE:
                        uiHandler.post(() -> {
                            if ((int)resultObject == 0){
                                NhPaths.showMessage(context, imgFileSpinner.getSelectedItem().toString() + " has been unmounted.");
                                reloadMountStateButton.performClick();
                            } else {
                                NhPaths.showMessage_long(context, "Failed to unmount image " + imgFileSpinner.getSelectedItem().toString() +
                                        ". Your drive may be still be in use by the host, please eject your drive on the host first," +
                                        "and then try to umount the image again.");
                            }
                            reloadMountStateButton.performClick();
                            mountImgButton.setEnabled(true);
                            unmountImgButton.setEnabled(true);
                        });
                        break;
                    case USBArsenalHandlerThread.CHANGE_INQUIRY_STRING:
                        String newInquiry = inquiryStringEditText.getText().toString();
                        if ((int)resultObject == 0){
                            if (newInquiry.isEmpty()) {
                                NhPaths.showMessage(context, "Inquiry string reset to default successfully.");
                                currentInquiryHintTextView.setText("Linux File-CD/Stor gadget (kernel default)");
                            } else {
                                NhPaths.showMessage(context, "Inquiry string changed to '" + newInquiry + "' successfully.");
                                currentInquiryHintTextView.setText(newInquiry);
                            }
                        } else {
                            NhPaths.showMessage_long(context, "Failed to change inquiry string to '" + newInquiry +
                                    "'.");
                        }
                        break;
                    case USBArsenalHandlerThread.GET_USBSWITCH_SQL_DATA:
                        uiHandler.post(() -> {
                            usbSwitchInfoEditTextGroup[0].setText(((USBArsenalUSBSwitchModel)resultObject).getidVendor());
                            usbSwitchInfoEditTextGroup[1].setText(((USBArsenalUSBSwitchModel)resultObject).getidProduct());
                            usbSwitchInfoEditTextGroup[2].setText(((USBArsenalUSBSwitchModel)resultObject).getmanufacturer());
                            usbSwitchInfoEditTextGroup[3].setText(((USBArsenalUSBSwitchModel)resultObject).getproduct());
                            usbSwitchInfoEditTextGroup[4].setText(((USBArsenalUSBSwitchModel)resultObject).getserialnumber());
                        });
                        break;
                    case USBArsenalHandlerThread.GET_USBNETWORK_SQL_DATA:
                        uiHandler.post(() -> {
                            usbNetworkInfoEditTextGroup[0].setText(((USBArsenalUSBNetworkModel)resultObject).getupstream_iface());
                            usbNetworkInfoEditTextGroup[1].setText(((USBArsenalUSBNetworkModel)resultObject).getusb_iface());
                            usbNetworkInfoEditTextGroup[2].setText(((USBArsenalUSBNetworkModel)resultObject).getip_address_for_target());
                            usbNetworkInfoEditTextGroup[3].setText(((USBArsenalUSBNetworkModel)resultObject).getip_gateway());
                            usbNetworkInfoEditTextGroup[4].setText(((USBArsenalUSBNetworkModel)resultObject).getip_subnetmask());
                        });
                        break;
                }
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        reloadUSBStateImageButton.performClick();
        if (imageMounterLL.getVisibility() == View.VISIBLE) {
            reloadMountStateButton.performClick();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.usbarsenal, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @SuppressLint({"NonConstantResourceId", "SetTextI18n"})
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        final LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        assert inflater != null;
        final View promptView = inflater.inflate(R.layout.kaliservices_custom_dialog_view, null);
        final TextView titleTextView = promptView.findViewById(R.id.f_kaliservices_adb_tv_title1);
        final EditText storedpathEditText = promptView.findViewById(R.id.f_kaliservices_adb_et_storedpath);

        switch (item.getItemId()){
            case R.id.f_usbarsenal_menu_backupDB:
                titleTextView.setText("Full path to where you want to save the database:");
                storedpathEditText.setText(NhPaths.APP_SD_SQLBACKUP_PATH + "/FragmentUSBArsenal");
                MaterialAlertDialogBuilder adbBackup = new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat);
                adbBackup.setView(promptView);
                adbBackup.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
                adbBackup.setPositiveButton("OK", (dialog, which) -> { });
                final AlertDialog adBackup = adbBackup.create();
                adBackup.setOnShowListener(dialog -> {
                    final Button buttonOK = adBackup.getButton(DialogInterface.BUTTON_POSITIVE);
                    buttonOK.setOnClickListener(v -> {
                        String returnedResult = USBArsenalSQL.getInstance(context).backupData(storedpathEditText.getText().toString());
                        if (returnedResult == null){
                            NhPaths.showMessage(context, "db successfully backed up to " + storedpathEditText.getText().toString());
                        } else {
                            dialog.dismiss();
                            new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Failed to backup the DB.").setMessage(returnedResult).create().show();
                        }
                        dialog.dismiss();
                    });
                });
                adBackup.show();
                break;
            case R.id.f_usbarsenal_menu_restoreDB:
                titleTextView.setText("Full path of the db file from where you want to restore:");
                storedpathEditText.setText(NhPaths.APP_SD_SQLBACKUP_PATH + "/FragmentUSBArsenal");
                MaterialAlertDialogBuilder adbRestore = new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat);
                adbRestore.setView(promptView);
                adbRestore.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
                adbRestore.setPositiveButton("OK", (dialog, which) -> { });
                final AlertDialog adRestore = adbRestore.create();
                adRestore.setOnShowListener(dialog -> {
                    final Button buttonOK = adRestore.getButton(DialogInterface.BUTTON_POSITIVE);
                    buttonOK.setOnClickListener(v -> {
                        String returnedResult = USBArsenalSQL.getInstance(context).restoreData(storedpathEditText.getText().toString());
                        if (returnedResult == null) {
                            NhPaths.showMessage(context, "db is successfully restored to " + storedpathEditText.getText().toString());
                            refreshUSBSwitchInfos(gettargetOSSpinnerString(), getusbFuncSpinnerString());
                            refreshUSBNetworkInfos(getusbNetWorkModeSpinnerPosition());
                        } else {
                            dialog.dismiss();
                            new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Failed to restore the DB.").setMessage(returnedResult).create().show();
                        }
                        dialog.dismiss();
                    });
                });
                adRestore.show();
                break;
            case R.id.f_usbarsenal_menu_ResetToDefault:
                if (USBArsenalSQL.getInstance(context).resetData()) {
                    NhPaths.showMessage(context, "db is successfully reset to default.");
                    refreshUSBSwitchInfos(gettargetOSSpinnerString(), getusbFuncSpinnerString());
                    refreshUSBNetworkInfos(getusbNetWorkModeSpinnerPosition());
                } else {
                    NhPaths.showMessage_long(context, "Failed to reset the db to default.");
                }
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        usbStatusTextView = null;
        mountedImageTextView = null;
        mountedImageHintTextView = null;
        usbNetworkTetheringHintTextView = null;
        reloadUSBStateImageButton = null;
        reloadMountStateButton = null;
        imageMounterLL = null;
        usbNetworkTetheringLL = null;
        setUSBIfaceButton = null;
        mountImgButton = null;
        unmountImgButton = null;
        setUSBNetworkTetheringButton = null;
        saveUSBFunctionConfigButton = null;
        saveUSBNetworkTetheringConfigButton = null;
        targetOSSpinner = null;
        imgFileSpinner = null;
        usbFuncSpinner = null;
        usbNetworkAttackModeSpinner = null;
        adbSpinner = null;
        Arrays.fill(usbSwitchInfoEditTextGroup, null);
        usbSwitchInfoEditTextGroup = null;
        Arrays.fill(usbNetworkInfoEditTextGroup, null);
        usbNetworkInfoEditTextGroup = null;
    }

    private void getImageFiles() {
        mountImgButton.setEnabled(false);
        unmountImgButton.setEnabled(false);
        ArrayList<String> result = new ArrayList<>();
        File image_folder = new File(NhPaths.APP_SD_FILES_IMG_PATH);
        if (!image_folder.exists()) {
            NhPaths.showMessage(context, "Creating directory for storing image files..");
            try {
                image_folder.mkdir();
            } catch (Exception e) {
                e.printStackTrace();
                NhPaths.showMessage(context, "Failed to get images files from " + NhPaths.APP_SD_FILES_IMG_PATH);
                return;
            }
        }
        try {
            File[] filesInFolder = image_folder.listFiles();
            assert filesInFolder != null;
            for (File file : filesInFolder) {
                if (!file.isDirectory() && (file.getName().contains(".img") || file.getName().contains(".iso"))) {
                        result.add(file.getName());
                    }
            }
        } catch (NullPointerException e) {
            e.printStackTrace();
            Log.e(TAG, e.toString());
        }
        ArrayAdapter<String> imageAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, result);
        imageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        imgFileSpinner.setAdapter(imageAdapter);
        mountImgButton.setEnabled(true);
        unmountImgButton.setEnabled(true);
    }

    private String getusbFuncSpinnerString() {
        if (usbFuncSpinner.getSelectedItem() != null) {
            return usbFuncSpinner.getSelectedItem().toString() +
                    (adbSpinner.getSelectedItem().equals("Enable")?",adb":"");
        }
        return "reset";
    }

    private String gettargetOSSpinnerString() {
        return targetOSSpinner.getSelectedItem().toString();
    }

    private int getusbNetWorkModeSpinnerPosition() {
        return usbNetworkAttackModeSpinner.getSelectedItemPosition();
    }

    private boolean isAllUSBInfosValid() {
        if (!is_init_exists){
            if (!usbSwitchInfoEditTextGroup[0].getText().toString().matches("^0x[0-9a-fA-F]{4}$")) {
                new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Invalid Format").setMessage("The regex must be ^0x[0-9a-fA-F]{4}$").create().show();
                return false;
            }
            if (!usbSwitchInfoEditTextGroup[1].getText().toString().matches("^0x[0-9a-fA-F]{4}$")) {
                new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Invalid Format").setMessage("The regex must be ^0x[0-9a-fA-F]{4}$").create().show();
                return false;
            }
            if (!usbSwitchInfoEditTextGroup[2].getText().toString().matches("^\\w+$|^$")) {
                new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Invalid Format").setMessage("The regex must be ^\\w+$|^$").create().show();
                return false;
            }
            if (!usbSwitchInfoEditTextGroup[3].getText().toString().matches("^\\w+$|^$")) {
                new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Invalid Format").setMessage("The regex must be ^\\w+$|^$").create().show();
                return false;
            }
            if (!usbSwitchInfoEditTextGroup[4].getText().toString().matches("^[0-9A-Z]{10}$|^$")) {
                new MaterialAlertDialogBuilder(context, R.style.DialogStyleCompat).setTitle("Invalid Format").setMessage("The regex must be ^[0-9A-Z]{10}$|^$").create().show();
                return false;
            }
        }
        return true;
    }

    private void refreshUSBSwitchInfos(String targetOSName, String functionName) {
        Message msg = new Message();
        Bundle bundle = new Bundle();
        bundle.putString("targetOSName", targetOSName);
        bundle.putString("functionName", functionName);
        msg.what = USBArsenalHandlerThread.GET_USBSWITCH_SQL_DATA;
        msg.obj = context;
        msg.setData(bundle);
        usbArsenalHandlerThread.getHandler().sendMessage(msg);
    }

    private void refreshUSBNetworkInfos(int attackModePosition) {
        Message msg = new Message();
        msg.what = USBArsenalHandlerThread.GET_USBNETWORK_SQL_DATA;
        msg.obj = context;
        msg.arg1 = attackModePosition;
        usbArsenalHandlerThread.getHandler().sendMessage(msg);
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd_android(String cmd) {
        Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/android-su", cmd);
        context.startActivity(intent);
    }

    private void onClick(View v) {
        if (isAllUSBInfosValid()) {
            setUSBIfaceButton.setEnabled(false);
            String target = targetOSSpinner.getSelectedItem().toString().equals("Windows") ? "win" :
                    targetOSSpinner.getSelectedItem().toString().equals("Linux") ? "lnx" :
                            targetOSSpinner.getSelectedItem().toString().equals("Mac OS") ? "mac" : "";
            String functions = usbFuncSpinner.getSelectedItem().toString();
            String adbEnable = adbSpinner.getSelectedItem().toString().equals("Enable") ? ",adb" : "";
            String idVendor = " -v '" + usbSwitchInfoEditTextGroup[0].getText().toString() + "'";
            String idProduct = " -p '" + usbSwitchInfoEditTextGroup[1].getText().toString() + "'";
            String manufacturer = usbSwitchInfoEditTextGroup[2].getText().toString().isEmpty() ? "" : " -m '" + usbSwitchInfoEditTextGroup[2].getText().toString() + "'";
            String product = usbSwitchInfoEditTextGroup[3].getText().toString().isEmpty() ? "" : " -P '" + usbSwitchInfoEditTextGroup[3].getText().toString() + "'";
            String serialnumber = usbSwitchInfoEditTextGroup[4].getText().toString().isEmpty() ? "" : " -s '" + usbSwitchInfoEditTextGroup[4].getText().toString() + "'";

            Message msg = new Message();
            msg.what = USBArsenalHandlerThread.SETUSBIFACE;
            msg.obj = "[ -f /init.nethunter.rc ] && setprop sys.usb.config " + functions + " || " + NhPaths.APP_SCRIPTS_PATH + "/usbarsenal -t '" + target + "' -f '" + functions + "'" + adbEnable + idVendor + idProduct + manufacturer + product + serialnumber;
            usbArsenalHandlerThread.getHandler().sendMessage(msg);
        }
    }
}