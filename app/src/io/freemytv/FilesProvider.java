package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Hands a file from the explorer to the app that opens it (VLC, a picture viewer), read-only and only for that open:
 * Android no longer lets apps pass file:// paths. content://io.freemytv.files/<absolute path>.
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "io.freemytv.files";

    static Uri uri(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(f.getAbsolutePath()).build();
    }

    /** The file's type from its extension ("video/mp4"), or "any type" when unknown. */
    static String type(File f) {
        String name = f.getName(), ext = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (t == null && (ext.equals("mkv") || ext.equals("ts") || ext.equals("m2ts"))) t = "video/x-matroska";
        return t == null ? "*/*" : t;
    }

    private static File file(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null || path.contains("/../")) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(path);
        if (!f.isFile() || !(path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/"))) {
            throw new FileNotFoundException(path);   // only what the explorer shows: the storage volumes
        }
        return f;
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try {
            return type(file(uri));
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = file(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[] {f.getName(), f.length()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
