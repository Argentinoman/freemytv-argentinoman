package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free My TV's file explorer, in place of the board's (Amlogic's FileBrower): the pen drive and the projector's memory,
 * folders first. A file opens in the app that plays it (VLC), handed over read-only through FilesProvider; an APK
 * installs through the projector's ADB. Holding OK on an item opens its options (copy, move, rename, delete, select
 * several, a picture as the home background); the bar above the list pastes, makes folders and handles a selection.
 */
public class FilesActivity extends Activity {
    /** The folder to start in (the pen drive's root, from the USB button); without it, the list of drives. */
    static final String EXTRA_PATH = "path";

    private static final String APP_NAME = "Explorador de archivos";

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

    /** A line of the options panel. */
    private static final class Option {
        final String label;
        final Runnable action;
        final boolean danger;

        Option(String label, boolean danger, Runnable action) {
            this.label = label;
            this.danger = danger;
            this.action = action;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** The folder on screen (null: the list of drives). */
    private File dir;
    private String title = APP_NAME, symbol = "folder";
    private List<Entry> entries = Collections.emptyList();
    private final Map<String, Integer> focusIn = new HashMap<>();
    /** The row with the focus; -1 is the bar above the list (barFocus picks its button). */
    private int focus, barFocus, generation;
    private boolean loading, busy;
    private String status;
    private ListView view;

    /** Selecting several: the marked files (OK marks and unmarks). */
    private boolean selecting;
    private final Set<File> marked = new LinkedHashSet<>();
    /** Copied or cut, waiting for "Pegar aquí". */
    private final List<File> clip = new ArrayList<>();
    private boolean clipMove;

    /** The options panel: its title, lines and focus (null: closed). */
    private List<Option> menu;
    private String menuTitle;
    /** Lines under the panel's title (what a confirmation is about), or none. */
    private String[] menuNote = {};
    private int menuFocus;
    /** OK held long enough opened the options: its release does nothing else. */
    private boolean swallowOk;

    private final BroadcastReceiver media = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (dir != null && !dir.isDirectory()) {   // the pen drive went: back to the drives
                closeMenu();
                selecting = false;
                marked.clear();
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
        File top = f != null && f.isDirectory() ? f : null;
        focusIn.clear();
        status = null;
        selecting = false;
        marked.clear();
        closeMenu();
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

    // MARK: - Listing

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
                if (folder == null || !folder.equals(dir)) barFocus = 0;
                dir = folder;
                entries = list;
                title = h[0];
                symbol = h[1];
                focus = list.isEmpty() ? (folder == null ? 0 : -1) : Math.max(at < 0 ? -1 : 0, Math.min(at, list.size() - 1));
                if (folder == null) focus = Math.max(0, focus);
                barFocus = Math.min(barFocus, Math.max(0, bar().size() - 1));
                loading = false;
                view.invalidate();
            });
        });
    }

    private void reload() {
        show(dir, focus);
    }

    private List<Entry> drives(String[] head) {
        head[0] = APP_NAME;
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

    /** The drive's name at a drive's root, else the folder's own name. */
    private String[] heading(File folder) {
        for (Entry d : drives(new String[2])) {
            if (d.file.equals(folder)) return new String[] {d.name, d.symbol};
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
            boolean isFolder = f.isDirectory();
            String detail = isFolder ? date.format(new Date(f.lastModified()))
                    : Formatter.formatShortFileSize(this, f.length()) + " · " + date.format(new Date(f.lastModified()));
            list.add(new Entry(f, n, detail, isFolder ? "folder" : image(f) ? "image" : "file", isFolder));
        }
        Collections.sort(list, (a, b) -> a.folder != b.folder ? (a.folder ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return list;
    }

    private static boolean image(File f) {
        return FilesProvider.type(f).startsWith("image/");
    }

    private static boolean apk(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    // MARK: - Opening

    private void open(Entry e) {
        if (e.folder) {
            focusIn.put(dir == null ? "" : dir.getPath(), focus);
            show(e.file, 0);
            return;
        }
        if (apk(e.file)) {
            confirm("¿Instalar?", new String[] {e.name}, "Instalar", false, () -> install(e.file));
            return;
        }
        if (image(e.file)) {   // pictures in our own viewer (← → through the folder)
            startActivity(new Intent(this, ImageViewerActivity.class).putExtra(ImageViewerActivity.EXTRA_PATH, e.file.getPath()));
            status = null;
            view.invalidate();
            return;
        }
        if (PlayerActivity.isMedia(e.file)) {   // videos and songs in our own player (↑ ↓ through the folder)
            startActivity(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_PATH, e.file.getPath()));
            status = null;
            view.invalidate();
            return;
        }
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FilesProvider.uri(e.file), FilesProvider.type(e.file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(open);
            status = null;
        } catch (ActivityNotFoundException | SecurityException x) {
            status = image(e.file) ? "Ninguna app abre fotos. Mantené OK y elegí «Usar como fondo» para verla en el inicio."
                    : "Ninguna app abre este archivo. Para videos y música, instalá VLC.";
        }
        view.invalidate();
    }

    private void install(File f) {
        String path = "'" + f.getAbsolutePath().replace("'", "'\\''") + "'";
        work("Instalando " + f.getName() + "…", () -> {
            String answer;
            try (Adb adb = Adb.connect(this, 3000, null)) {
                answer = adb.shell("pm install -r -g " + path + " 2>&1").trim();
            } catch (IOException x) {
                answer = x.getMessage();
            }
            return answer != null && answer.contains("Success") ? "Listo: " + f.getName() + " quedó instalada."
                    : "No se pudo instalar: " + (answer == null || answer.isEmpty() ? "sin respuesta" : answer);
        });
    }

    /** Runs a change on the worker with its message below, then the folder again and the result. */
    private void work(String doing, java.util.concurrent.Callable<String> job) {
        busy = true;
        status = doing;
        view.invalidate();
        worker.execute(() -> {
            String result;
            try {
                result = job.call();
            } catch (Exception x) {
                result = "No se pudo: " + x.getMessage();
            }
            final String r = result;
            runOnUiThread(() -> {
                busy = false;
                status = r;
                reload();
            });
        });
    }

    private void progress(String text) {
        runOnUiThread(() -> {
            status = text;
            view.invalidate();
        });
    }

    // MARK: - The options

    /** The items an action works on: the marked ones while selecting, else the focused one. */
    private List<File> targets() {
        List<File> l = new ArrayList<>();
        if (selecting) l.addAll(marked);
        else if (focus >= 0 && focus < entries.size()) l.add(entries.get(focus).file);
        return l;
    }

    private String count(List<File> l) {
        return l.size() == 1 ? "«" + l.get(0).getName() + "»" : l.size() + " elementos";
    }

    /** Holding OK on a row: what can be done with it (or with the selection). */
    private void openOptions() {
        if (dir == null || entries.isEmpty() || focus < 0) return;
        Entry e = entries.get(focus);
        List<Option> o = new ArrayList<>();
        if (selecting) {
            if (!marked.contains(e.file)) marked.add(e.file);
            List<File> t = targets();
            o.add(new Option("Copiar", false, () -> toClip(t, false)));
            o.add(new Option("Mover a otra carpeta", false, () -> toClip(t, true)));
            o.add(new Option("Borrar", true, () -> askDelete(t)));
            o.add(new Option("Seleccionar todo", false, this::selectAll));
            o.add(new Option("Terminar la selección", false, this::endSelection));
            openMenu(marked.size() == 1 ? e.name : marked.size() + " seleccionados", o);
            return;
        }
        List<File> t = Collections.singletonList(e.file);
        o.add(new Option(e.folder ? "Abrir" : apk(e.file) ? "Instalar" : "Abrir", false, () -> open(e)));
        if (!e.folder && image(e.file)) {
            o.add(new Option("Usar como fondo", false, () -> toBackground(e.file, true)));
            o.add(new Option("Sumar a fondos favoritos", false, () -> toBackground(e.file, false)));
        }
        o.add(new Option("Copiar", false, () -> toClip(t, false)));
        o.add(new Option("Mover a otra carpeta", false, () -> toClip(t, true)));
        o.add(new Option("Cambiar el nombre", false, () -> rename(e.file)));
        o.add(new Option("Seleccionar varios", false, () -> {
            selecting = true;
            marked.clear();
            marked.add(e.file);
            view.invalidate();
        }));
        o.add(new Option("Borrar", true, () -> askDelete(t)));
        openMenu(e.name, o);
    }

    private void openMenu(String title, List<Option> options) {
        menuTitle = title;
        menuNote = new String[0];
        menu = options;
        menuFocus = 0;
        view.invalidate();
    }

    private void closeMenu() {
        menu = null;
        if (view != null) view.invalidate();
    }

    private void confirm(String question, String[] note, String yes, boolean danger, Runnable action) {
        List<Option> o = new ArrayList<>();
        o.add(new Option("Cancelar", false, () -> { }));
        o.add(new Option(yes, danger, action));
        openMenu(question, o);
        menuNote = note;
    }

    private void selectAll() {
        selecting = true;
        for (Entry e : entries) marked.add(e.file);
        view.invalidate();
    }

    private void endSelection() {
        selecting = false;
        marked.clear();
        view.invalidate();
    }

    private void toClip(List<File> files, boolean move) {
        clip.clear();
        clip.addAll(files);
        clipMove = move;
        endSelection();
        status = (move ? "Para mover: " : "Para copiar: ") + count(clip) + ". Andá a la carpeta y elegí «Pegar aquí» arriba.";
        focus = -1;
        barFocus = 0;
        view.invalidate();
    }

    private void toBackground(File f, boolean show) {
        work("Guardando la foto…", () -> {
            Gallery.addOwn(this, f, show);
            return show ? "Listo: es el fondo del inicio, y quedó en tus favoritas." : "Listo: quedó en tus fondos favoritos.";
        });
    }

    private void askDelete(List<File> files) {
        if (files.isEmpty()) return;
        confirm("¿Borrar?", new String[] {count(files), "No se puede deshacer."}, "Borrar", true, () -> {
            List<File> list = new ArrayList<>(files);
            endSelection();
            work("Borrando…", () -> {
                int failed = 0;
                for (File f : list) if (!deleteAll(f)) failed++;
                clip.removeAll(list);
                return failed == 0 ? "Listo: " + (list.size() == 1 ? "borrado." : list.size() + " elementos borrados.")
                        : "No se pudieron borrar " + failed + " de " + list.size() + ".";
            });
        });
    }

    private static boolean deleteAll(File f) {
        File[] inside = f.isDirectory() ? f.listFiles() : null;
        boolean ok = true;
        if (inside != null) for (File c : inside) ok &= deleteAll(c);
        return f.delete() && ok;
    }

    private interface Named {
        String run(String name) throws IOException;
    }

    /** A name typed with the remote's keyboard (the system's), for a new folder or a rename. */
    private void askName(String title, String start, Named done) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(start);
        int dot = start.lastIndexOf('.');
        field.setSelection(0, dot > 0 ? dot : start.length());
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(field)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Listo", (dialog, which) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty() || name.equals(start)) return;
                    if (name.contains("/") || name.equals(".") || name.equals("..")) {
                        status = "Ese nombre no sirve: no puede llevar «/».";
                        view.invalidate();
                        return;
                    }
                    work("Un momento…", () -> done.run(name));
                })
                .create();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void rename(File f) {
        askName("Cambiar el nombre", f.getName(), name -> {
            File to = new File(f.getParentFile(), name);
            if (to.exists()) return "Ya hay algo que se llama «" + name + "».";
            if (!f.renameTo(to)) return "No se pudo cambiar el nombre.";
            clip.remove(f);
            return "Listo: ahora se llama «" + name + "».";
        });
    }

    private void newFolder() {
        File in = dir;
        askName("Nueva carpeta", "Carpeta nueva", name -> {
            File f = new File(in, name);
            if (f.exists()) return "Ya hay algo que se llama «" + name + "».";
            return f.mkdir() ? "Listo: carpeta «" + name + "» creada." : "No se pudo crear la carpeta.";
        });
    }

    // MARK: - Copying and moving

    private void paste() {
        if (clip.isEmpty() || dir == null) return;
        List<File> files = new ArrayList<>(clip);
        boolean move = clipMove;
        File into = dir;
        for (File f : files) {
            String from = f.getAbsolutePath() + "/";
            if (f.isDirectory() && (into.getAbsolutePath() + "/").startsWith(from)) {
                status = "No se puede " + (move ? "mover" : "copiar") + " una carpeta adentro de sí misma.";
                view.invalidate();
                return;
            }
        }
        clip.clear();
        work(move ? "Moviendo…" : "Copiando…", () -> {
            long total = 0;
            for (File f : files) total += size(f);
            if (!move && into.getUsableSpace() < total) {
                return "No hay lugar: hacen falta " + Formatter.formatShortFileSize(this, total) + " y quedan "
                        + Formatter.formatShortFileSize(this, into.getUsableSpace()) + ".";
            }
            long[] done = {0, 0};   // bytes copied, when the message last changed
            int failed = 0;
            for (File f : files) {
                if (!f.exists()) {
                    failed++;
                    continue;
                }
                if (move && into.equals(f.getParentFile())) continue;   // already here
                File to = free(into, f.getName());
                try {
                    if (move && f.renameTo(to)) continue;   // the same drive: a rename, at once
                    copyAll(f, to, total, done, move);
                    if (move && !deleteAll(f)) failed++;
                } catch (IOException x) {
                    AppLog.w("files: " + x.getMessage());
                    deleteAll(to);   // not a half copy left behind
                    failed++;
                }
            }
            String what = files.size() == 1 ? "«" + files.get(0).getName() + "»" : files.size() + " elementos";
            return failed == 0 ? "Listo: " + what + (move ? " movido" : " copiado") + (files.size() == 1 ? "." : "s.")
                    : "Fallaron " + failed + " de " + files.size() + ".";
        });
    }

    private static long size(File f) {
        if (!f.isDirectory()) return f.length();
        long s = 0;
        File[] inside = f.listFiles();
        if (inside != null) for (File c : inside) s += size(c);
        return s;
    }

    /** A name that isn't taken in the folder: "foto.jpg", then "foto (2).jpg"… */
    private static File free(File folder, String name) {
        File f = new File(folder, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(folder, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private void copyAll(File from, File to, long total, long[] done, boolean move) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
        if (from.isDirectory()) {
            if (!to.mkdir()) throw new IOException("couldn't make " + to);
            File[] inside = from.listFiles();
            if (inside != null) for (File c : inside) copyAll(c, new File(to, c.getName()), total, done, move);
            return;
        }
        byte[] buffer = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done[0] += n;
                if (total > 0 && done[0] - done[1] > 4 * 1024 * 1024) {
                    done[1] = done[0];
                    progress((move ? "Moviendo " : "Copiando ") + from.getName() + " · " + (int) (100 * done[0] / total) + " %");
                }
            }
            if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();   // all on the pen drive before it's pulled
        }
        //noinspection ResultOfMethodCallIgnored
        to.setLastModified(from.lastModified());
    }

    // MARK: - The bar above the list

    private static final int BAR_SELECT = 0, BAR_FOLDER = 1, BAR_PASTE = 2, BAR_CANCEL = 3, BAR_ACTIONS = 4, BAR_ALL = 5, BAR_DONE = 6;

    private final List<Integer> barButtons = new ArrayList<>();

    /** The bar's buttons now: what fits the folder, the selection and what's waiting to be pasted. */
    private List<Integer> bar() {
        barButtons.clear();
        if (dir == null) return barButtons;
        if (selecting) {
            barButtons.add(BAR_ACTIONS);
            barButtons.add(BAR_ALL);
            barButtons.add(BAR_DONE);
            return barButtons;
        }
        if (!clip.isEmpty()) {
            barButtons.add(BAR_PASTE);
            barButtons.add(BAR_CANCEL);
        }
        barButtons.add(BAR_SELECT);
        barButtons.add(BAR_FOLDER);
        return barButtons;
    }

    private String barLabel(int b) {
        switch (b) {
            case BAR_SELECT: return "Seleccionar varios";
            case BAR_FOLDER: return "Nueva carpeta";
            case BAR_PASTE: return "Pegar aquí (" + clip.size() + ")";
            case BAR_CANCEL: return clipMove ? "No mover" : "No copiar";
            case BAR_ACTIONS: return "Opciones (" + marked.size() + ")";
            case BAR_ALL: return "Seleccionar todo";
            default: return "Terminar";
        }
    }

    private void barPress(int b) {
        switch (b) {
            case BAR_SELECT:
                selecting = true;
                marked.clear();
                focus = entries.isEmpty() ? -1 : 0;
                status = "OK marca cada uno. Después, «Opciones» arriba (o mantené OK).";
                break;
            case BAR_FOLDER:
                newFolder();
                break;
            case BAR_PASTE:
                paste();
                break;
            case BAR_CANCEL:
                clip.clear();
                status = null;
                break;
            case BAR_ACTIONS:
                if (marked.isEmpty()) {
                    status = "Marcá algo primero con OK.";
                    break;
                }
                List<File> t = targets();
                List<Option> o = new ArrayList<>();
                o.add(new Option("Copiar", false, () -> toClip(t, false)));
                o.add(new Option("Mover a otra carpeta", false, () -> toClip(t, true)));
                o.add(new Option("Borrar", true, () -> askDelete(t)));
                openMenu(marked.size() + " seleccionados", o);
                return;
            case BAR_ALL:
                selectAll();
                break;
            default:
                endSelection();
                status = null;
                break;
        }
        barFocus = Math.min(barFocus, Math.max(0, bar().size() - 1));
        view.invalidate();
    }

    /**
     * ← or Back: out of the selection, else one level up: the folder above, the list of drives above a drive's root
     * (also when the USB button opened the pen drive). The list of drives is the top: there ← stays, Back leaves.
     */
    private void up(boolean leave) {
        if (selecting) {
            endSelection();
            status = null;
            return;
        }
        status = clip.isEmpty() ? null : status;
        if (dir == null) {
            if (leave) finish();
            return;
        }
        File parent = dir.getParentFile();
        List<Entry> drives = drives(new String[2]);
        int drive = -1;
        for (int i = 0; i < drives.size(); i++) if (drives.get(i).file.equals(dir)) drive = i;
        File back = drive >= 0 || parent == null ? null : parent;
        Integer at = focusIn.get(back == null ? "" : back.getPath());
        show(back, at != null ? at : Math.max(0, drive));   // on the drive it came from
    }

    // MARK: - The remote

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean ours = ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!ours) return super.dispatchKeyEvent(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;

        if (menu != null) {
            if (ok) {
                // On release, like the list: the release of the hold that opened the panel does nothing.
                if (down) return true;
                if (swallowOk) {
                    swallowOk = false;
                    return true;
                }
                Option o = menu.get(menuFocus);
                closeMenu();
                o.action.run();
                return true;
            }
            if (!down) return true;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: menuFocus = Math.max(0, menuFocus - 1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: menuFocus = Math.min(menu.size() - 1, menuFocus + 1); break;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (first) closeMenu();
                    break;
                default:
                    break;
            }
            view.invalidate();
            return true;
        }

        if (ok) {
            // A tap acts on release; held, the options open (and the release is swallowed).
            if (down) {
                if (!swallowOk && e.getRepeatCount() > 0 && ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 || e.getRepeatCount() >= 10)) {
                    swallowOk = true;
                    if (!busy && !loading && focus >= 0) openOptions();
                }
                return true;
            }
            if (swallowOk) {
                swallowOk = false;
                return true;
            }
            if (busy || loading) return true;
            if (focus < 0) {
                List<Integer> b = bar();
                if (!b.isEmpty()) barPress(b.get(Math.min(barFocus, b.size() - 1)));
                return true;
            }
            if (entries.isEmpty()) return true;
            Entry it = entries.get(focus);
            if (selecting) {
                if (!marked.remove(it.file)) marked.add(it.file);
                view.invalidate();
            } else {
                open(it);
            }
            return true;
        }

        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (first && !loading) up(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focus < 0 && barFocus > 0) barFocus--;
                else if (first && !loading) up(false);
                view.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focus < 0) {
                    barFocus = Math.min(bar().size() - 1, barFocus + 1);
                } else if (first && !busy && !loading && !selecting && focus < entries.size() && entries.get(focus).folder) {
                    open(entries.get(focus));
                }
                view.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (loading) return true;
                if (focus > 0) focus--;
                else if (focus == 0 && !bar().isEmpty()) focus = -1;
                view.invalidate();
                return true;
            default:   // down
                if (loading) return true;
                if (focus < entries.size() - 1) focus++;
                view.invalidate();
                return true;
        }
    }

    // MARK: - Drawing

    /** The list, in the projection panel's rows: the focused one white, the others glass; the options on the right. */
    private final class ListView extends View {
        private final float k;
        private final TextPaint heading, path, name, detail, hint, chip, menuHead, menuLine;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
                tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path check = new Path();
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
            chip = new TextPaint(Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF));
            menuHead = new TextPaint(Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF));
            menuLine = new TextPaint(Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f * k);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(4 * k);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
            setBackgroundColor(0xFF0E0E10);
        }

        private void row(Canvas c, boolean on, float radius) {
            fill.setColor(on ? 0xFFF5F5F7 : 0x1AFFFFFF);
            c.drawRoundRect(r, radius, radius, fill);
            if (!on) {
                rim.setColor(0x1FFFFFFF);
                c.drawRoundRect(r, radius, radius, rim);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), x = 120 * k, right = w - 120 * k;
            Symbols.draw(getContext(), c, symbol, x + 22 * k, 92 * k, 34 * k, 0xEBFFFFFF);
            String head = selecting ? marked.size() + (marked.size() == 1 ? " seleccionado" : " seleccionados") : title;
            c.drawText(head, x + 64 * k, 108 * k, heading);
            if (dir != null) c.drawText(dir.getPath(), x, 148 * k, path);

            // The bar: a row of buttons above the list.
            float listTop = 186 * k;
            List<Integer> b = bar();
            if (!b.isEmpty()) {
                float bx = x, by = 178 * k, bh = 56 * k;
                for (int i = 0; i < b.size(); i++) {
                    String label = barLabel(b.get(i));
                    float bw = chip.measureText(label) + 48 * k;
                    r.set(bx, by, bx + bw, by + bh);
                    boolean on = focus < 0 && i == barFocus && menu == null;
                    row(c, on, bh / 2);
                    chip.setColor(on ? 0xFF1C1C1E : 0xE6FFFFFF);
                    c.drawText(label, bx + 24 * k, by + bh / 2 + 9 * k, chip);
                    bx += bw + 14 * k;
                }
                listTop = 258 * k;
            }

            float rh = 80 * k, gap = 10 * k, bottom = h - 110 * k;
            if (loading && entries.isEmpty()) {
                c.drawText("Cargando…", x, listTop + 50 * k, hint);
            } else if (entries.isEmpty()) {
                c.drawText(dir == null ? "No hay ninguna memoria conectada." : "Carpeta vacía", x, listTop + 50 * k, hint);
            } else {
                int rows = Math.max(1, (int) ((bottom - listTop + gap) / (rh + gap)));
                int f = Math.max(0, focus);
                if (f < first) first = f;
                if (f >= first + rows) first = f - rows + 1;
                first = Math.max(0, Math.min(first, Math.max(0, entries.size() - rows)));
                for (int i = first; i < Math.min(entries.size(), first + rows); i++) {
                    Entry it = entries.get(i);
                    float y = listTop + (i - first) * (rh + gap);
                    r.set(x, y, right, y + rh);
                    boolean on = i == focus && menu == null, mark = selecting && marked.contains(it.file);
                    if (on) r.inset(-6 * k, -4 * k);
                    row(c, on, 22 * k);
                    if (!on && mark) {   // marked: a lighter glass
                        fill.setColor(0x26FFFFFF);
                        c.drawRoundRect(r, 22 * k, 22 * k, fill);
                    }
                    int ink = on ? 0xFF1C1C1E : 0xFFFFFFFF;
                    float gx = r.left + 50 * k, cy = r.centerY();
                    if (selecting) {
                        float rad = 15 * k;
                        tick.setColor(on ? 0xFF1C1C1E : 0xCCFFFFFF);
                        if (mark) {
                            fill.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
                            c.drawCircle(gx, cy, rad, fill);
                            check.reset();
                            check.moveTo(gx - 7 * k, cy);
                            check.lineTo(gx - 2 * k, cy + 6 * k);
                            check.lineTo(gx + 8 * k, cy - 6 * k);
                            tick.setColor(on ? 0xFFF5F5F7 : 0xFF1C1C1E);
                            c.drawPath(check, tick);
                        } else {
                            tick.setStrokeWidth(2.5f * k);
                            c.drawCircle(gx, cy, rad, tick);
                            tick.setStrokeWidth(4 * k);
                        }
                    } else {
                        Symbols.draw(getContext(), c, it.symbol, gx, cy, 26 * k, on ? 0xD91C1C1E : 0xCCFFFFFF);
                    }
                    float base = cy + 10 * k;
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
                if (first + rows < entries.size()) {
                    c.drawText("⌄  " + (entries.size() - first - rows) + " más", right - 160 * k, bottom + 30 * k, path);
                }
            }
            String foot = status != null ? status : dir == null ? "OK abre · Atrás sale de la app"
                    : selecting ? "OK marca · mantené OK para las opciones · ← termina"
                    : "OK abre · mantené OK para más opciones · ← vuelve";
            hint.setColor(0xB3FFFFFF);
            c.drawText(TextUtils.ellipsize(foot, hint, right - x, TextUtils.TruncateAt.END).toString(), x, h - 56 * k, hint);

            if (menu != null) drawMenu(c, w, h);
        }

        /** The options: a panel on the right over a darkened list, like the projection panel. */
        private void drawMenu(Canvas c, float w, float h) {
            fill.setColor(0x99000000);
            c.drawRect(0, 0, w, h, fill);
            float pw = 600 * k, x0 = w - pw;
            r.set(x0, 0, w + 60 * k, h);
            fill.setColor(0xF51B1B1E);
            c.drawRoundRect(r, 44 * k, 44 * k, fill);
            rim.setColor(0x0FFFFFFF);
            c.drawRoundRect(r, 44 * k, 44 * k, rim);
            float x = x0 + 48 * k, right = w - 48 * k;
            String t = TextUtils.ellipsize(menuTitle, menuHead, right - x, TextUtils.TruncateAt.MIDDLE).toString();
            c.drawText(t, x, 120 * k, menuHead);
            float top = 172 * k, rh = 80 * k, gap = 12 * k;
            for (String line : menuNote) {
                c.drawText(TextUtils.ellipsize(line, hint, right - x, TextUtils.TruncateAt.MIDDLE).toString(), x, top, hint);
                top += 38 * k;
            }
            if (menuNote.length > 0) top += 12 * k;
            for (int i = 0; i < menu.size(); i++) {
                Option o = menu.get(i);
                r.set(x, top + i * (rh + gap), right, top + i * (rh + gap) + rh);
                boolean on = i == menuFocus;
                if (on) r.inset(-6 * k, -4 * k);
                row(c, on, 24 * k);
                menuLine.setColor(o.danger ? (on ? 0xFFD70015 : 0xFFFF6961) : on ? 0xFF1C1C1E : 0xFFFFFFFF);
                c.drawText(o.label, r.left + 26 * k, r.centerY() + 10 * k, menuLine);
            }
        }
    }
}
