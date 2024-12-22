package com.offsec.nethunter.HandlerThread;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;

import androidx.annotation.NonNull;

import com.offsec.nethunter.SQL.USBArsenalSQL;
import com.offsec.nethunter.utils.ShellExecuter;

public class USBArsenalHandlerThread extends HandlerThread {
    private Handler handler;
    public static final int IS_INIT_EXIST = 1;
    public static final int RETRIEVE_USB_FUNCS = 2;
    public static final int SETUSBIFACE = 3;
    public static final int RELOAD_USBIFACE = 4;
    public static final int GET_STORAGE_FUNC_FOLDER_NAME = 5;
    public static final int RELOAD_MOUNTSTATUS = 6;
    public static final int MOUNT_IMAGE = 7;
    public static final int UNMOUNT_IMAGE = 8;
    public static final int CHANGE_INQUIRY_STRING = 9;
    public static final int GET_USBSWITCH_SQL_DATA = 10;
    public static final int GET_USBNETWORK_SQL_DATA = 11;
    private USBArsenalListener listener;
    private Object resultObject = new Object();
    private final ShellExecuter exe = new ShellExecuter();

    public USBArsenalHandlerThread() {
        super("USBArsenalHandlerThread", Process.THREAD_PRIORITY_DEFAULT);
    }

    @SuppressLint("HandlerLeak")
    @Override
    protected void onLooperPrepared() {
        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case IS_INIT_EXIST:
                    case SETUSBIFACE:
                    case MOUNT_IMAGE:
                    case UNMOUNT_IMAGE:
                    case CHANGE_INQUIRY_STRING:
                        resultObject = exe.RunAsRootReturnValue(msg.obj.toString());
                        break;
                    case RETRIEVE_USB_FUNCS:
                    case RELOAD_USBIFACE:
                    case GET_STORAGE_FUNC_FOLDER_NAME:
                    case RELOAD_MOUNTSTATUS:
                        resultObject = exe.RunAsRootOutput(msg.obj.toString());
                        break;
                    case GET_USBSWITCH_SQL_DATA:
                        resultObject = USBArsenalSQL.getInstance((Context) msg.obj)
                                .getUSBSwitchColumnData(msg.getData().getString("targetOSName"),
                                        msg.getData().getString("functionName"));
                        break;
                    case GET_USBNETWORK_SQL_DATA:
                        resultObject = USBArsenalSQL.getInstance((Context) msg.obj).getUSBNetworkColumnData(msg.arg1);
                        break;
                    default:
                        break;
                }
                if (listener != null) {
                    listener.onShellExecuterFinished(resultObject, msg.what);
                }
            }
        };
    }

    public Handler getHandler() {
        return handler;
    }

    public void setOnShellExecuterFinishedListener(USBArsenalListener listener) {
        this.listener = listener;
    }

    public interface USBArsenalListener {
        void onShellExecuterFinished(Object resultObject, int actionCode);
    }
}