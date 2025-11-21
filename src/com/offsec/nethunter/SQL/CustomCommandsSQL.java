package com.offsec.nethunter.SQL;

import android.content.ContentValues;
import android.os.Environment;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.offsec.nethunter.BuildConfig;
import com.offsec.nethunter.models.CustomCommandsModel;
import com.offsec.nethunter.utils.NhPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class CustomCommandsSQL extends SQLiteOpenHelper {
    private static CustomCommandsSQL instance;
    private static final String DATABASE_NAME = "CustomCommandsFragment";
    private final Context appContext;
    private static final String TAG = "CustomCommandsSQL";
    private static final String TABLE_NAME = DATABASE_NAME;
    private static final ArrayList<String> COLUMNS = new ArrayList<>();
    private static final String[][] customcommandsData = {
            {"1", "Update Kali Metapackages",
                    "echo -ne \"\\033]0;Updating Kali\\007\" && clear;apt update && apt -y upgrade",
                    "kali", "interactive", "0"},
            {"2", "Launch Wifite",
                    "echo -ne \"\\033]0;Wifite\\007\" && clear;wifite",
                    "kali", "interactive", "0"},
            {"3", "Launch hcxdumptool",
                    "echo -ne \"\\033]0;hcxdumptool\\007\" && clear;hcxdumptool -i wlan1 -w $HOME/$(date +\"%Y-%m-%d_%H-%M-%S\").pcapng",
                    "kali", "interactive", "0"},
            {"4", "Start wlan1 in monitor mode",
                    "echo -ne \"\\033]0;Wlan1 monitor mode\\007\" && clear;ip link set wlan1 down && iw wlan1 set monitor control && ip link set wlan1 up;sleep 2 && exit",
                    "kali", "interactive", "0"},
            {"5", "Start wlan0 in monitor mode",
                    "echo -ne \"\\033]0;Wlan0 Monitor Mode\\007\" && clear; su -c 'CON_MODE_PATH=$(find /sys/module/*/parameters/con_mode 2>/dev/null | head -n 1); if [ -f \"$CON_MODE_PATH\" ]; then CURRENT_MODE=$(cat \"$CON_MODE_PATH\"); if [ \"$CURRENT_MODE\" = \"4\" ]; then echo -e \"\\033[31mMonitor mode enabled already\\033[0m. Exiting..\"; else echo 4 > \"$CON_MODE_PATH\"; ip link set wlan0 down; ip link set wlan0 up; echo -e \"\\033[32mDone!\\033[0m Exiting..\"; fi; else echo -e \"\\033[31mYour device is not QCACLD3.0 or does not support monitor mode!\\033[0m Exiting..\"; fi' && sleep 2 && exit",
                    "android", "interactive", "0"},
            {"6", "Stop wlan0 monitor mode",
                    "echo -ne \"\\033]0;Stopping Wlan0 Mon Mode\\007\" && clear; su -c 'CON_MODE_PATH=$(find /sys/module/*/parameters/con_mode 2>/dev/null | head -n 1); if [ -f \"$CON_MODE_PATH\" ]; then CURRENT_MODE=$(cat \"$CON_MODE_PATH\"); if [ \"$CURRENT_MODE\" = \"0\" ]; then echo -e \"\\033[31mMonitor mode disabled already\\033[0m. Exiting..\"; else ip link set wlan0 down; echo 0 > \"$CON_MODE_PATH\"; ip link set wlan0 up; svc wifi enable; echo -e \"\\033[32mDone!\\033[0m Exiting..\"; fi; else echo -e \"\\033[31mYour device is not QCACLD3.0 or does not support monitor mode!\\033[0m Exiting..\"; fi' && sleep 2 && exit",
                    "android", "interactive", "0"},
    };

    public static synchronized CustomCommandsSQL getInstance(Context context){
        if (instance == null) {
            instance = new CustomCommandsSQL(context.getApplicationContext());
        }
        return instance;
    }

    private CustomCommandsSQL(Context context) {
        super(context, DATABASE_NAME, null, 3);
        this.appContext = context.getApplicationContext();
        COLUMNS.add("id");
        COLUMNS.add("CommandLabel");
        COLUMNS.add("Command");
        COLUMNS.add("RuntimeEnv");
        COLUMNS.add("ExecutionMode");
        COLUMNS.add("RunOnBoot");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_NAME + " (" + COLUMNS.get(0) + " INTEGER, " +
                COLUMNS.get(1) + " TEXT, " + COLUMNS.get(2) +  " TEXT, " +
                COLUMNS.get(3) + " TEXT, " + COLUMNS.get(4) + " TEXT, " +
                COLUMNS.get(5) + " INTEGER)");
        if (new File(NhPaths.APP_DATABASE_PATH + "/KaliLaunchers").exists()) {
            convertOldDBtoNewDB(db);
        } else {
            ContentValues initialValues = new ContentValues();
            db.beginTransaction();
            for (String[] data : customcommandsData) {
                initialValues.put(COLUMNS.get(0), data[0]);
                initialValues.put(COLUMNS.get(1), data[1]);
                initialValues.put(COLUMNS.get(2), data[2]);
                initialValues.put(COLUMNS.get(3), data[3]);
                initialValues.put(COLUMNS.get(4), data[4]);
                initialValues.put(COLUMNS.get(5), data[5]);
                db.insert(TABLE_NAME, null, initialValues);
            }
            db.setTransactionSuccessful();
            db.endTransaction();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public List<CustomCommandsModel> bindData(List<CustomCommandsModel> list) {
        try (SQLiteDatabase db = getWritableDatabase();
             Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME + " ORDER BY " + COLUMNS.get(0) + ";", null)) {
            while (cursor.moveToNext()) {
                int i1 = cursor.getColumnIndex(COLUMNS.get(1));
                int i2 = cursor.getColumnIndex(COLUMNS.get(2));
                int i3 = cursor.getColumnIndex(COLUMNS.get(3));
                int i4 = cursor.getColumnIndex(COLUMNS.get(4));
                int i5 = cursor.getColumnIndex(COLUMNS.get(5));
                if (i1 >= 0 && i2 >= 0 && i3 >= 0 && i4 >= 0 && i5 >= 0) {
                    list.add(new CustomCommandsModel(
                            cursor.getString(i1),
                            cursor.getString(i2),
                            cursor.getString(i3),
                            cursor.getString(i4),
                            cursor.getString(i5)
                    ));
                }
            }
        }
        return list;
    }

    public void addData(int targetPositionId, @NonNull List<String> data) {
        try (SQLiteDatabase db = this.getWritableDatabase()) {
            ContentValues values = new ContentValues();
            db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(0) + " = " + COLUMNS.get(0) + " + 1 WHERE " + COLUMNS.get(0) + " >= " + targetPositionId + ";");
            values.put(COLUMNS.get(0), targetPositionId);
            values.put(COLUMNS.get(1), data.get(0));
            values.put(COLUMNS.get(2), data.get(1));
            values.put(COLUMNS.get(3), data.get(2));
            values.put(COLUMNS.get(4), data.get(3));
            values.put(COLUMNS.get(5), data.get(4));
            db.beginTransaction();
            try {
                db.insert(TABLE_NAME, null, values);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    public void deleteData(List<Integer> ids){
        try (SQLiteDatabase db = this.getWritableDatabase()) {
            db.execSQL("DELETE FROM " + TABLE_NAME + " WHERE " + COLUMNS.get(0) + " in (" + TextUtils.join(",", ids) + ");");
            try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME + " ORDER BY " + COLUMNS.get(0) + ";", null)) {
                while (cursor.moveToNext()) {
                    int i = cursor.getColumnIndex(COLUMNS.get(0));
                    if (i >= 0) {
                        db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(0) + " = " + cursor.getInt(i) + " WHERE " + COLUMNS.get(0) + " > " + cursor.getInt(i) + ";");
                    }
                }
            }
        }
    }

    public void moveData(Integer originalPosition, Integer targetPosition){
        try (SQLiteDatabase db = this.getWritableDatabase()) {
            db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(0) + " = 0 - 1 WHERE " + COLUMNS.get(0) + " = " + (originalPosition + 1) + ";");
            if (originalPosition < targetPosition){
                db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(0) + " = " + COLUMNS.get(0) + " - 1 WHERE " + COLUMNS.get(0) + " > " + originalPosition + " AND " + COLUMNS.get(0) + " <= " + targetPosition + ";");
            } else {
                db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(0) + " = " + COLUMNS.get(0) + " + 1 WHERE " + COLUMNS.get(0) + " < " + originalPosition + " AND " + COLUMNS.get(0) + " >= " + targetPosition + ";");
            }
            db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(0) + " = " + (targetPosition + 1) + " WHERE " + COLUMNS.get(0) + " = -1;");
        }
    }

    public void editData(Integer targetPosition, List<String> editData){
        try (SQLiteDatabase db = this.getWritableDatabase()) {
            db.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(1) + " = '" + editData.get(0).replace("'", "''") + "', " +
                    COLUMNS.get(2) + " = '" + editData.get(1).replace("'", "''") + "', " +
                    COLUMNS.get(3) + " = '" + editData.get(2).replace("'", "''") + "', " +
                    COLUMNS.get(4) + " = '" + editData.get(3).replace("'", "''") + "', " +
                    COLUMNS.get(5) + " = '" + editData.get(4).replace("'", "''") + "' WHERE " + COLUMNS.get(0) + " = " + targetPosition + ";");
        }
    }

    public void resetData(){
        try (SQLiteDatabase db = this.getWritableDatabase()) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            db.execSQL("CREATE TABLE " + TABLE_NAME + " (" + COLUMNS.get(0) + " INTEGER, " +
                    COLUMNS.get(1) + " TEXT, " +
                    COLUMNS.get(2) + " TEXT, " +
                    COLUMNS.get(3) + " TEXT, " +
                    COLUMNS.get(4) + " TEXT, " +
                    COLUMNS.get(5) + " TEXT);");
            ContentValues values = new ContentValues();
            db.beginTransaction();
            try {
                for (String[] data: customcommandsData){
                    values.put(COLUMNS.get(0), data[0]);
                    values.put(COLUMNS.get(1), data[1]);
                    values.put(COLUMNS.get(2), data[2]);
                    values.put(COLUMNS.get(3), data[3]);
                    values.put(COLUMNS.get(4), data[4]);
                    values.put(COLUMNS.get(5), data[5]);
                    db.insert(TABLE_NAME, null, values);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    public String backupData(String storedDBpath) {
        try {
            File data = Environment.getDataDirectory();
            String currentDBPath = data.getAbsolutePath() + "/data/" + BuildConfig.APPLICATION_ID + "/databases/" + DATABASE_NAME;
            File currentDB = new File(currentDBPath);
            File backupDB = new File(storedDBpath);
            if (currentDB.exists()) {
                try (FileInputStream fis = new FileInputStream(currentDB);
                     FileOutputStream fos = new FileOutputStream(backupDB);
                     FileChannel src = fis.getChannel();
                     FileChannel dst = fos.getChannel()) {
                    dst.transferFrom(src, 0, src.size());
                } catch (IOException e) {
                    Log.e(TAG, e.toString());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
        return null;
    }

    public String restoreData(String storedDBpath) {
        if (!new File(storedDBpath).exists()) return null;
        try (SQLiteDatabase checkDb = SQLiteDatabase.openDatabase(storedDBpath, null, SQLiteDatabase.OPEN_READONLY)) {
            if (checkDb.getVersion() != 3) return null;
        } catch (Exception e) {
            Log.e(TAG, "Error reading DB version: " + e);
            return null;
        }
        if (!verifyDB(storedDBpath)) return null;
        try {
            File sd = appContext.getExternalFilesDir(null);
            File data = Environment.getDataDirectory();
            if (sd != null && sd.canWrite()) {
                String currentDBPath = data.getAbsolutePath() + "/data/" + BuildConfig.APPLICATION_ID + "/databases/" + DATABASE_NAME;
                File currentDB = new File(currentDBPath);
                File backupDB = new File(storedDBpath);
                if (currentDB.exists()) {
                    try (FileInputStream fis = new FileInputStream(backupDB);
                         FileOutputStream fos = new FileOutputStream(currentDB);
                         java.nio.channels.FileChannel src = fis.getChannel();
                         java.nio.channels.FileChannel dst = fos.getChannel()) {
                        dst.transferFrom(src, 0, src.size());
                    } catch (IOException e) {
                        Log.e(TAG, "Error during file transfer: " + e.getMessage());
                    }
                }
            }
        } catch (IllegalStateException | SecurityException e) {
            Log.e(TAG, "Unexpected error: " + e.getMessage());
        }
        return null;
    }

    private boolean verifyDB(String storedDBpath){
        try (SQLiteDatabase tempDB = SQLiteDatabase.openDatabase(storedDBpath, null, SQLiteDatabase.OPEN_READWRITE)) {
            return ifTableExists(tempDB, TABLE_NAME);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
    }

    private boolean isOldDB(String storedDBpath) {
        try (SQLiteDatabase tempDB = SQLiteDatabase.openDatabase(storedDBpath, null, SQLiteDatabase.OPEN_READWRITE)) {
            String oldDBTableName = "LAUNCHERS";
            return ifTableExists(tempDB, oldDBTableName);
        }
    }

    private boolean restoreOldDBtoNewDB(String storedDBpath) {
        try (SQLiteDatabase tempDB = SQLiteDatabase.openDatabase(storedDBpath, null, SQLiteDatabase.OPEN_READWRITE)) {
            convertOldDBtoNewDB(tempDB);
            return true;
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }
    }

    private void convertOldDBtoNewDB(SQLiteDatabase currentDB) {
        currentDB.execSQL("ATTACH DATABASE ? AS oldDB", new String[]{NhPaths.APP_DATABASE_PATH + "/KaliLaunchers"});
        currentDB.execSQL("INSERT INTO " + TABLE_NAME + "(" + COLUMNS.get(0) + "," +
                COLUMNS.get(1) + "," + COLUMNS.get(2) + "," + COLUMNS.get(3) + "," +
                COLUMNS.get(4) + "," + COLUMNS.get(5) + ")" +
                " SELECT id, CommandLabel, Command, RuntimeEnv, ExecutionMode, RunOnBoot FROM oldDB.LAUNCHERS;");
        currentDB.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(3) + " = LOWER(" + COLUMNS.get(3) + ");");
        currentDB.execSQL("UPDATE " + TABLE_NAME + " SET " + COLUMNS.get(4) + " = LOWER(" + COLUMNS.get(4) + ");");
        SQLiteDatabase.deleteDatabase(new File(NhPaths.APP_DATABASE_PATH + "/KaliLaunchers"));
    }

    private boolean ifTableExists (SQLiteDatabase tempDB, String tableName) {
        boolean exists = false;
        try {
            Cursor c = tempDB.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + tableName + "'", null);
            exists = c.getCount() == 1;
            c.close();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
        return exists;
    }
}
