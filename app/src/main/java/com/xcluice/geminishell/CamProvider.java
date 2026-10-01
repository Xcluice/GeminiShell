package com.xcluice.geminishell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class CamProvider extends ContentProvider {

    static final String AUTH = "com.xcluice.geminishell.cam";

    static Uri uriFor(String name) {
        return new Uri.Builder().scheme("content").authority(AUTH).appendPath(name).build();
    }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "cam");
        d.mkdirs();
        return d;
    }

    private File fileFor(Uri u) throws FileNotFoundException {
        String n = u.getLastPathSegment();
        if (n == null || n.contains("/") || n.contains("..")) throw new FileNotFoundException();
        return new File(dir(getContext()), n);
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        try {
            File f = fileFor(u);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getType(Uri u) { return "image/jpeg"; }

    @Override
    public Uri insert(Uri u, ContentValues v) { return null; }

    @Override
    public int delete(Uri u, String s, String[] a) { return 0; }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        int m = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(fileFor(u), m);
    }
}
