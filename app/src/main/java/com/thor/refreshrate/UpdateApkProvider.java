package com.thor.refreshrate;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Exposes only the verified update APK to Android's installer, read-only. */
public final class UpdateApkProvider extends ContentProvider {
    private static final String PATH = "/update.apk";
    private static final String MIME = "application/vnd.android.package-archive";

    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        if (!PATH.equals(uri.getPath()) || getContext() == null)
            throw new FileNotFoundException("Unknown update URI");
        File apk = new File(new File(getContext().getCacheDir(), "updates"), "update.apk");
        if (!apk.isFile()) throw new FileNotFoundException("Update no longer exists");
        return apk;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only update");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        return PATH.equals(uri.getPath()) ? MIME : null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File apk = file(uri);
            String[] columns = projection == null
                    ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i]))
                    values[i] = "ThorRefreshRateControl-update.apk";
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = apk.length();
            }
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
}
