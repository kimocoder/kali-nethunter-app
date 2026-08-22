package com.offsec.nethunter.models;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LootArchive {
    private final File file;
    private final String filename;
    private final long size;
    private final long timestamp;

    public LootArchive(File file) {
        this.file = file;
        this.filename = file.getName();
        this.size = file.length();
        this.timestamp = file.lastModified();
    }

    public File getFile() {
        return file;
    }

    public String getFilename() {
        return filename;
    }

    public long getSize() {
        return size;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedSize() {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
    }

    public String getFormattedDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(timestamp));
    }
}
