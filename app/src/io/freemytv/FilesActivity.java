package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free My TV's own file explorer, in place of the board's (Amlogic's FileBrower): the pen drive and the projector's
 * memory, folders first. A file opens in the app that plays it (VLC), handed over read-only through FilesProvider;
 * an APK installs through the projector's ADB after a second OK. It never writes to the drives.
 */
public class FilesActivity extends Activity {
    /** The folder to start in (the pen drive's root, from the USB button); without it, the list of drives. */
    static final String EXTRA_PATH = "path";

    private static final class Entry {
        final File file;
        final String name, detail, symbol;
        final boolean folder;
        String shown;
        float shownWidth;

        Entry(File file, String name, String detail, String symbol, boolean folder) {
            this.file = file;
            this.name = name;
            this.detail = detail;
            this.symbol = symbol;
            this.folder = folder;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** The folder on screen (null: the drives), and the one Back closes at. */
    private File dir, top;
    private String title = "Archivos", symbol = "folder";
    private List<Entry> entries = Collections.emptyList();
    private final Map<String, Integer> focusIn = new HashMap<>();
    private int focus, generation;
    private boolean loading, installing;
    private Entry armed;   // the APK waiting for its second OK
    private String status;
    private ListView view;

    private final BroadcastReceiver media = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (dir != null && !dir.isDirectory()) {   // the pen drive went: back to the drives
                top = null;
                show(null, 0);
            } else {
                show(dir, focus);
            }
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        view = new ListView(this);
        setContentView(view);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addDataScheme("file");
        registerReceiver(media, f);
        start(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        start(intent);
    }

    private void start(Intent intent) {
        String path = intent.getStringExtra(EXTRA_PATH);
        File f = path == null ? null : new File(path);
        top = f != null && f.isDirectory() ? f : null;
        focusIn.clear();
        status = null;
        armed = null;
        loading = true;
        view.invalidate();
        worker.execute(this::allowStorage);   // first in the queue, so the first listing can read
        show(top, 0);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(media);
        worker.shutdownNow();
        super.onDestroy();
    }

    /** Reading the drives needs "all files" access: granted once through the projector's ADB, like its other switches. */
    private void allowStorage() {
        boolean allowed = Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
                : checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (allowed) return;
        try (Adb adb = Adb.connect(this, 3000, null)) {
            adb.shell(Build.VERSION.SDK_INT >= 30 ? "appops set --uid " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow"
                    : "pm grant " + getPackageName() + " android.permission.READ_EXTERNAL_STORAGE");
        } catch (IOException e) {
            AppLog.w("files: " + e.getMessage());
        }
    }

    /** Lists a folder (or the drives) off the UI thread, then puts it on screen with the focus on `at`. */
    private void show(File folder, int at) {
        final int ticket = ++generation;
        loading = true;
        view.invalidate();
        worker.execute(() -> {
            String[] head = new String[2];
            List<Entry> list = folder == null ? drives(head) : list(folder);
            if (folder != null) head = heading(folder);
            final String[] h = head;
            runOnUiThread(() -> {
                if (ticket != generation) return;
                dir = folder;
                entries = list;
                title = h[0];
                symbol = h[1];
                focus = Math.max(0, Math.min(at, list.size() - 1));
                loading = false;
                view.invalidate();
            });
        });
    }

    private List<Entry> drives(String[] head) {
        head[0] = "Archivos";
        head[1] = "folder";
        List<Entry> list = new ArrayList<>();
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return list;
        try {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                File root = root(v);
                if (root == null) continue;
                String name = v.isRemovable() ? usbName(v) : "Memoria del proyector";
                list.add(new Entry(root, name, space(root), v.isRemovable() ? "usb" : "folder", true));
            }
        } catch (RuntimeException ignored) {
        }
        return list;
    }

    private static File root(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30 && v.getDirectory() != null) return v.getDirectory();
        if (v.isPrimary()) return Environment.getExternalStorageDirectory();
        return v.getUuid() == null ? null : new File("/storage/" + v.getUuid());
    }

    private String usbName(StorageVolume v) {
        String d = v.getDescription(this);
        return d == null || d.isEmpty() || d.toLowerCase(Locale.ROOT).contains("usb") ? "Pendrive" : d;
    }

    private String space(File root) {
        long free = root.getUsableSpace(), total = root.getTotalSpace();
        return total <= 0 ? "" : Formatter.formatShortFileSize(this, free) + " libres de " + Formatter.formatShortFileSize(this, total);
    }

    /** The title and symbol for a folder: its drive's name at the drive's root, else the folder's own name. */
    private String[] heading(File folder) {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        try {
            for (StorageVolume v : sm.getStorageVolumes()) {
                File root = root(v);
                if (root != null && root.equals(folder)) {
                    return new String[] {v.isRemovable() ? usbName(v) : "Memoria del proyector", v.isRemovable() ? "usb" : "folder"};
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new String[] {folder.getName(), "folder"};
    }

    private List<Entry> list(File folder) {
        File[] files = folder.listFiles();
        List<Entry> list = new ArrayList<>();
        if (files == null) return list;
        DateFormat date = DateFormat.getDateInstance(DateFormat.MEDIUM, Ui.locale());
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".") || n.equals("System Volume Information") || n.equals("LOST.DIR") || n.equals("$RECYCLE.BIN")) continue;
            boolean folderToo = f.isDirectory();
            String detail = folderToo ? date.format(new Date(f.lastModified()))
                    : Formatter.formatShortFileSize(this, f.length()) + " · " + date.format(new Date(f.lastModified()));
            list.add(new Entry(f, n, detail, folderToo ? "folder" : "file", folderToo));
        }
        Collections.sort(list, (a, b) -> a.folder != b.folder ? (a.folder ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return list;
    }

    private void open(Entry e) {
        if (e.folder) {
            focusIn.put(dir == null ? "" : dir.getPath(), focus);
            show(e.file, 0);
            return;
        }
        if (e.name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            if (armed != e) {
                armed = e;
                status = "Apretá OK otra vez para instalar " + e.name;
            } else {
                install(e);
            }
            view.invalidate();
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FilesProvider.uri(e.file), FilesProvider.type(e.file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            status = null;
        } catch (ActivityNotFoundException | SecurityException x) {
            status = "Ninguna app abre este archivo. Para videos y música, instalá VLC.";
        }
        this.view.invalidate();
    }

    private void install(Entry e) {
        armed = null;
        installing = true;
        status = "Instalando " + e.name + "…";
        String path = "'" + e.file.getAbsolutePath().replace("'", "'\\''") + "'";
        worker.execute(() -> {
            String answer;
            try (Adb adb = Adb.connect(this, 3000, null)) {
                answer = adb.shell("pm install -r -g " + path + " 2>&1").trim();
            } catch (IOException x) {
                answer = x.getMessage();
            }
            final String result = answer != null && answer.contains("Success") ? "Listo: " + e.name + " quedó instalada."
                    : "No se pudo instalar: " + (answer == null || answer.isEmpty() ? "sin respuesta" : answer);
            runOnUiThread(() -> {
                installing = false;
                status = result;
                view.invalidate();
            });
        });
    }

    /** ← or Back: the folder above, the drives above a drive's root, and out at the top. */
    private void up() {
        status = null;
        armed = null;
        if (dir == null || dir.equals(top)) {
            finish();
            return;
        }
        File parent = dir.getParentFile();
        boolean driveRoot = false;
        for (Entry d : drives(new String[2])) if (d.file.equals(dir)) driveRoot = true;
        File back = driveRoot || parent == null ? null : parent;
        Integer at = focusIn.get(back == null ? "" : back.getPath());
        show(back, at == null ? 0 : at);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean ours = ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!ours) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (installing && code != KeyEvent.KEYCODE_BACK) return true;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (e.getRepeatCount() == 0 && !loading) up();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (loading || entries.isEmpty()) return true;
                int next = Math.max(0, Math.min(entries.size() - 1, focus + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                if (next != focus) {
                    focus = next;
                    armed = null;
                    if (status != null && status.startsWith("Apretá")) status = null;
                    view.invalidate();
                }
                return true;
            default:   // OK or →
                if (e.getRepeatCount() > 0 || loading || entries.isEmpty()) return true;
                Entry it = entries.get(focus);
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && !it.folder) return true;
                open(it);
                return true;
        }
    }

    /** The list, in the projection panel's rows: the focused one white, the others glass. */
    private final class ListView extends View {
        private final float k;
        private final TextPaint heading, path, name, detail, hint;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int first;   // the top row on screen

        ListView(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            heading = new TextPaint(Ui.text(Ui.semibold, 44 * k, 0xFFFFFFFF));
            path = new TextPaint(Ui.text(Ui.regular, 22 * k, 0x80FFFFFF));
            name = new TextPaint(Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF));
            detail = new TextPaint(Ui.text(Ui.regular, 24 * k, 0x99FFFFFF));
            detail.setTextAlign(Paint.Align.RIGHT);
            hint = new TextPaint(Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f * k);
            setBackgroundColor(0xFF0E0E10);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), x = 120 * k, right = w - 120 * k;
            Symbols.draw(getContext(), c, symbol, x + 22 * k, 104 * k, 34 * k, 0xEBFFFFFF);
            c.drawText(title, x + 64 * k, 120 * k, heading);
            if (dir != null) c.drawText(dir.getPath(), x, 162 * k, path);
            float listTop = 196 * k, rh = 80 * k, gap = 10 * k, bottom = h - 110 * k;
            if (loading && entries.isEmpty()) {
                c.drawText("Cargando…", x, listTop + 50 * k, hint);
            } else if (entries.isEmpty()) {
                c.drawText(dir == null ? "No hay ninguna memoria conectada." : "Carpeta vacía", x, listTop + 50 * k, hint);
            } else {
                int rows = Math.max(1, (int) ((bottom - listTop + gap) / (rh + gap)));
                if (focus < first) first = focus;
                if (focus >= first + rows) first = focus - rows + 1;
                first = Math.max(0, Math.min(first, Math.max(0, entries.size() - rows)));
                for (int i = first; i < Math.min(entries.size(), first + rows); i++) {
                    Entry it = entries.get(i);
                    float y = listTop + (i - first) * (rh + gap);
                    r.set(x, y, right, y + rh);
                    boolean on = i == focus;
                    if (on) r.inset(-6 * k, -4 * k);
                    fill.setColor(on ? 0xFFF5F5F7 : 0x1AFFFFFF);
                    c.drawRoundRect(r, 22 * k, 22 * k, fill);
                    if (!on) {
                        rim.setColor(0x1FFFFFFF);
                        c.drawRoundRect(r, 22 * k, 22 * k, rim);
                    }
                    int ink = on ? 0xFF1C1C1E : 0xFFFFFFFF;
                    Symbols.draw(getContext(), c, it.symbol, r.left + 50 * k, r.centerY(), 26 * k, on ? 0xD91C1C1E : 0xCCFFFFFF);
                    float base = r.centerY() + 10 * k;
                    detail.setColor(on ? 0xB31C1C1E : 0x99FFFFFF);
                    c.drawText(it.detail, r.right - 28 * k, base, detail);
                    float room = r.width() - 110 * k - detail.measureText(it.detail) - 56 * k;
                    if (it.shown == null || it.shownWidth != room) {
                        it.shown = TextUtils.ellipsize(it.name, name, room, TextUtils.TruncateAt.MIDDLE).toString();
                        it.shownWidth = room;
                    }
                    name.setColor(ink);
                    c.drawText(it.shown, r.left + 90 * k, base, name);
                }
                if (first + rows < entries.size()) c.drawText("⌄  " + (entries.size() - first - rows) + " más", right - 160 * k, bottom + 30 * k, path);
            }
            c.drawText(status != null ? status : "OK abre · ← vuelve", x, h - 56 * k, hint);
        }
    }
}
