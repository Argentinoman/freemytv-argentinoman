package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, in the same style as the home screen:
 *  - the recommendations' photos in full screen, one every 30 s, with a slow zoom and pan (the "Ken Burns" effect of
 *    the Apple TV photos) and a crossfade between them; each photo is loaded 10% larger than the screen, so the zoom
 *    never upscales it;
 *  - top right, like the iPhone lock screen: the date with the weather, the big Liquid Glass clock (the photo shows
 *    through the digits, bent at the edges; Shaders.GLASS_TEXT) and the football game;
 *  - bottom left, the photo's caption: the time-of-day label ("TONIGHT"), the title, and genre · runtime · service.
 * Dark scrims only where there is text, stronger where the photo is bright. Any key exits (as on Apple TV).
 * Without photos (no TMDB key, or before the first fetch): the big clock over the night aurora.
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    private static final long FADE_MS = 3_000, FIRST_MS = 700;   // the first photo comes up quickly from black
    /** How long each photo stays (Home Settings > Backgrounds); → on the remote brings the next one now. */
    private long showMs = 30_000;
    private boolean skip;
    private static final float ZOOM = 0.1f;
    /** Photos 10% larger than the screen (the zoom goes up to 1.1 without upscaling). */
    private static final int PW = 2112, PH = 1188;
    private static final float MARGIN = 80, RIGHT = 1840, TITLE_W = 900, META_BASE = 1008;
    private static final float DATE_BASE = 92, CLOCK_TOP = 120, CLOCK_SIZE = 110, QUOTES_GAP = 52;
    private static final double DRIFT_PERIOD_S = 600;
    // The high and low, made again only when they change (drawn on every frame).
    private String range = "";
    private HomeData.Weather rangeWeather;
    private int rangeGeneration = -1, rangePrefs = -1;

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its caption. */
    private static final class Slide {
        final Recs.Pick pick;
        final Bitmap image, frost;
        final BitmapShader shader;    // the frosted version, for the clock's glass
        final float lightCaption, lightClock;
        final boolean zoomIn;
        final float panX, panY;
        final Recording caption = Recording.create("caption");
        long startedAt;

        Slide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock, int n) {
            this.pick = pick;
            this.image = image;
            this.frost = frost;
            shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Frost.filter(shader, BitmapShader.FILTER_MODE_LINEAR);
            this.lightCaption = lightCaption;
            this.lightClock = lightClock;
            zoomIn = n % 2 == 0;
            // Toward one of the corners, alternating, without repeating the previous one.
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    /** The glass clock's digits, rendered once a minute in the background. */
    private static final class Glass {
        final String text;
        final Bitmap slope, cover, shadow;
        final int x, y;
        final float inkTop, inkHeight;

        Glass(String text, Bitmap slope, Bitmap cover, Bitmap shadow, int x, int y, float inkTop, float inkHeight) {
            this.text = text;
            this.slope = slope;
            this.cover = cover;
            this.shadow = shadow;
            this.x = x;
            this.y = y;
            this.inkTop = inkTop;
            this.inkHeight = inkHeight;
        }
    }

    private final Scene scene = new Scene(false);
    private final HomeData data;
    private final Recs recs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recording content = Recording.create("clock");     // without photos: the clock over the aurora
    private final Recording chrome = Recording.create("corner");     // date, weather, the game and the clock's shadow
    private final float timeOffset = (float) (Math.random() * 300);
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glassPaint = new Paint();
    private final Set<String> failed = new HashSet<>();     // Recs.key: a movie and a series can have the same ID
    private RuntimeShader glassShader;
    private boolean running, dirty = true, chromeDirty = true;
    private boolean fallback;       // the aurora with the clock (no photos)
    /** Home Settings > Screensaver: Gallery.LANDSCAPES or Gallery.ART passing by, or "clock". */
    private String source = Gallery.LANDSCAPES;
    private volatile List<Gallery.Item> gallery = new java.util.ArrayList<>();
    private int galleryNext;
    /** Bumped when ← or a new choice of pictures makes the photo being decoded the wrong one. */
    private int galleryTurn;
    private boolean decoding;
    private int generation, slides;
    private Slide current, incoming, prepared;
    private Glass glass;
    private String glassFor = "";
    private long startedAt, firstAt, lastFrame, lastSky;
    private int w, h;
    private float k = 1;
    private long minute = -1;
    private String clock = "", date = "";
    private Paint datePaint, clockPaint, weatherPaint, weatherDim, quotePaint;
    private Paint cornerDate, cornerQuote, cornerClock, labelPaint, metaPaint;
    private TextPaint titlePaint;
    private TextPaint captionLabel, captionTitle, captionAuthor;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            recs.prefetchAll();
            dirty = true;
            chromeDirty = true;
            prepareNext();
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        recs = Recs.get(c);
        Shaders.dream(scene.palette);
        scene.setMotion(0.4f, 0.32f);
        scene.applyPalette();
        content.setUseCompositingLayer(true, null);
        try {
            glassShader = new RuntimeShader(Shaders.GLASS_TEXT);
            glassPaint.setShader(glassShader);
        } catch (RuntimeException | LinkageError e) {   // Android 8 to 12: no AGSL
            AppLog.w("glass clock unavailable: " + e.getMessage());
            glassShader = null;
        }
        black.setColor(0xFF000000);
        shadowPaint.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedAt = clock();
        lastFrame = 0;
        failed.clear();
        source = Prefs.screensaverShown(getContext());
        hintUntil = 0;   // the keys' hint shows once per screensaver, on the first ← or →
        hinted = false;
        showMs = Prefs.screensaverSeconds(getContext()) * 1000L;
        // The clock over the dark sky while the first photo loads; the photo then fades in over it (no black wait).
        fallback = true;
        galleryNext = readNext();   // where it left off: that photo was downloaded while the last one showed
        data.addListener(this);
        recs.addListener(this);
        if (nowPlaying == null) nowPlaying = new NowPlaying(getContext(), () -> {
            dirty = true;          // the clock-only screen redraws its recording
            chromeDirty = true;    // and so does the photo corner
            invalidate();
        });
        nowPlaying.start();
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.prefetchAll();
        prepareNext();
        handler.postDelayed(tick, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    private String notice;
    private long noticeUntil;
    private Paint noticePaint;

    /** ↑ on the remote: the photo on screen into the favourites (or out of them). False without a gallery photo. */
    boolean favorite() {
        Slide s = incoming != null ? incoming : current;
        if (s == null || !"gallery".equals(s.pick.kind)) return false;
        Gallery.Item it = new Gallery.Item();
        it.url = s.pick.backdrop;
        it.title = s.pick.title == null ? "" : s.pick.title;
        it.author = s.pick.meta == null ? "" : s.pick.meta;
        boolean now = Gallery.toggleFavorite(getContext(), it);
        showNotice(now ? "♥  Guardada en favoritas" : "Quitada de favoritas");
        invalidate();
        return true;
    }

    /** "Saved to favourites": a short line over the caption, fading out. */
    private void drawNotice(Canvas c, long up) {
        if (notice == null) return;
        if (up >= noticeUntil) {
            notice = null;
            return;
        }
        if (noticePaint == null) {
            noticePaint = Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF);
            noticePaint.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        }
        noticePaint.setAlpha(Math.round(255 * Ui.clamp((noticeUntil - up) / 600f)));
        noticePaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(notice, w / 2f, (META_BASE - 12) * k, noticePaint);
    }

    // MARK: - The remote over the photos: ↑ favourite, ↓ which pictures, → the next one

    private static final int MENU_NONE = 0, MENU_FAVORITE = 1, MENU_SOURCE = 2;
    private static final String[] SOURCES = {Gallery.FAVORITES, Gallery.ART, Gallery.ABSTRACT, Gallery.ANIMALS, Gallery.LANDSCAPES};
    private static final String[] SOURCE_NAMES = {"Favoritas", "Arte", "Abstracto", "Animales", "Paisajes"};
    private int menu = MENU_NONE, menuIndex, consumed = -1;
    private float hush;   // 1 while a menu, a hint or a notice shows: the corners fade out
    private long menuUntil, hintUntil;
    private boolean hinted;
    private Paint pillText;
    private final Paint pillFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF pill = new android.graphics.RectF();

    /**
     * A key while the photos show. ↑ offers "add to favourites" and ↓ the choice of pictures (OK confirms, Back or
     * the other arrow closes); → and ← bring the next and the previous photo. False: the screensaver ends, as with any
     * other key.
     */
    boolean key(android.view.KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) {
            boolean mine = code == consumed;   // the release of a key handled here
            if (mine) consumed = -1;
            return mine;
        }
        if (current == null || fallback || !"gallery".equals(current.pick.kind)) return false;
        switch (code) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                menu = menu == MENU_SOURCE ? MENU_NONE : MENU_FAVORITE;
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                if (menu == MENU_FAVORITE) {
                    menu = MENU_NONE;
                } else {
                    menu = MENU_SOURCE;
                    menuIndex = Math.max(0, java.util.Arrays.asList(SOURCES).indexOf(source));
                }
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: {
                boolean right = code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                if (menu == MENU_SOURCE) menuIndex = Math.max(0, Math.min(SOURCES.length - 1, menuIndex + (right ? 1 : -1)));
                else if (menu == MENU_NONE) {
                    if (right) skip();
                    else previous();
                    if (!hinted) {   // the first time: what each key does, for a moment
                        hinted = true;
                        hintUntil = clock() + 4000;
                    }
                }
                break;
            }
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (menu == MENU_FAVORITE) favorite();
                else if (menu == MENU_SOURCE) applySource(SOURCES[menuIndex]);
                else return false;
                menu = MENU_NONE;
                break;
            case android.view.KeyEvent.KEYCODE_BACK:
                if (menu == MENU_NONE) return false;
                menu = MENU_NONE;
                break;
            default:
                return false;
        }
        consumed = code;
        menuUntil = clock() + 8000;   // a menu left alone closes by itself
        invalidate();
        return true;
    }

    /** The photo after the one just prepared is downloaded now, so its turn (or the next screensaver) starts at once. */
    private void prefetch(Context ctx, List<Gallery.Item> list, int next) {
        if (list.isEmpty()) return;
        final Gallery.Item it = list.get(next % list.size());
        worker.execute(() -> {
            try {
                Gallery.image(ctx, it);
            } catch (Exception ignored) {
            }
        });
    }

    private int readNext() {
        try {
            java.io.File f = new java.io.File(getContext().getFilesDir(), "screensaver-next.txt");
            return Integer.parseInt(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim());
        } catch (Exception e) {
            return (int) (System.currentTimeMillis() / 60_000 % 997);
        }
    }

    private void saveNext(int next) {
        final int value = next;
        worker.execute(() -> {
            try {
                java.nio.file.Files.write(new java.io.File(getContext().getFilesDir(), "screensaver-next.txt").toPath(),
                        String.valueOf(value).getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
        });
    }

    /** ← : the photo before this one, as soon as it's ready. */
    private void previous() {
        Slide s = incoming != null ? incoming : current;
        List<Gallery.Item> list = gallery;
        if (s == null || list.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).url.equals(s.pick.backdrop)) at = i;
        if (at < 0) return;
        galleryNext = (at - 1 + list.size()) % list.size();
        dropPrepared();
        skip = true;
        prepareNext();
    }

    /** The photo waiting for its turn goes (another one is wanted), and so does the one being decoded. */
    private void dropPrepared() {
        galleryTurn++;
        if (prepared != null) {
            final Slide old = prepared;
            prepared = null;
            handler.postDelayed(() -> { old.image.recycle(); old.frost.recycle(); }, 1000);
        }
    }

    /** ↓ then OK: other pictures from the next photo on (and remembered in Home Settings > Backgrounds). */
    private void applySource(String kind) {
        if (kind.equals(source)) return;
        if (kind.equals(Gallery.FAVORITES) && Gallery.favorites(getContext()).isEmpty()) {
            showNotice("Todavía no hay favoritas: ↑ y OK sobre una foto");
            return;
        }
        source = kind;
        Prefs.setScreensaver(getContext(), kind);
        gallery = new java.util.ArrayList<>();
        dropPrepared();
        skip = true;
        prepareNext();
        showNotice(SOURCE_NAMES[java.util.Arrays.asList(SOURCES).indexOf(kind)] + " desde la próxima foto");
    }

    private void showNotice(String text) {
        notice = text;
        noticeUntil = clock() + 2500;
    }

    /** The open menu as pills, centred; after the first ← or →, which keys do what (centred), for a few seconds. */
    private void drawMenu(Canvas c, long up) {
        if (menu != MENU_NONE && up >= menuUntil) menu = MENU_NONE;
        if (pillText == null) pillText = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
        float h = 56 * k, y = META_BASE * k - h + 8 * k, pad = 28 * k, cx = w / 2f;   // at the bottom, centred
        if (menu == MENU_NONE) {
            if (up < hintUntil && notice == null) {
                cornerQuote.setAlpha(Math.round(200 * Ui.clamp((hintUntil - up) / 800f)));
                cornerQuote.setTextAlign(Paint.Align.CENTER);
                c.drawText("↑ favorita   ·   ↓ cambiar fondos   ·   ← → anterior y siguiente", cx, y + h * 0.65f, cornerQuote);
                cornerQuote.setTextAlign(Paint.Align.LEFT);
                cornerQuote.setAlpha(255);
            }
            return;
        }
        String[] options = menu == MENU_FAVORITE
                ? new String[] {current != null && Gallery.isFavorite(getContext(), current.pick.backdrop)
                        ? "Quitar de favoritas" : "♥  Agregar a favoritas"}
                : SOURCE_NAMES;
        float total = -14 * k;
        for (String o : options) total += pillText.measureText(o) + 2 * pad + 14 * k;
        float x = cx - total / 2;
        for (int i = 0; i < options.length; i++) {
            boolean on = menu == MENU_FAVORITE || i == menuIndex;
            float w = pillText.measureText(options[i]) + 2 * pad;
            pill.set(x, y, x + w, y + h);
            pillFill.setColor(on ? 0xFFF5F5F7 : 0x8C2C2C2E);
            c.drawRoundRect(pill, h / 2, h / 2, pillFill);
            pillText.setColor(on ? 0xFF1C1C1E : 0xF2FFFFFF);
            c.drawText(options[i], x + pad, pill.centerY() + Ui.halfCap(pillText), pillText);
            x += w + 14 * k;
        }
        String hint = menu == MENU_FAVORITE ? "OK confirma · ↓ cierra" : "← → elegir · OK confirma · ↑ cierra";
        cornerQuote.setTextAlign(Paint.Align.CENTER);
        c.drawText(hint, cx, y - 22 * k, cornerQuote);
        cornerQuote.setTextAlign(Paint.Align.LEFT);
    }

    /** The next photo as soon as it's ready (→ on the remote). False without photos: the key then ends the screensaver. */
    boolean skip() {
        if (current == null || fallback) return false;
        skip = true;
        prepareNext();
        invalidate();
        return true;
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        if (nowPlaying != null) nowPlaying.stop();
        // The photos stay in GPU memory until the garbage collector runs: release them now (after the last frame).
        final java.util.Set<Bitmap> gone = new java.util.HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s == null) continue;
            gone.add(s.image);
            gone.add(s.frost);
        }
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
        current = incoming = prepared = null;
        glass = null;
        slides = 0;
        glassFor = "";
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onData() {
        dirty = true;
        chromeDirty = true;
        invalidate();
    }

    @Override public void onRecs() {
        if (running) prepareNext();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        scene.setSize(w, h);
        content.setPosition(0, 0, w, h);
        chrome.setPosition(0, 0, w, h);
        // Without photos (the clock over the aurora).
        datePaint = Ui.text(Ui.semibold, 36 * k, 0xD9FFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        clockPaint = Ui.numbers(Ui.thin, 280 * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setLetterSpacing(-0.01f);
        weatherPaint = Ui.numbers(Ui.medium, 30 * k, 0xC7FFFFFF);
        weatherDim = Ui.numbers(Ui.regular, 28 * k, 0x80FFFFFF);
        quotePaint = Ui.numbers(Ui.medium, 24 * k, 0x99FFFFFF);
        // With photos: the corner (date, clock, the game) and the caption.
        cornerDate = Ui.numbers(Ui.semibold, 30 * k, 0xEBFFFFFF);
        cornerQuote = Ui.numbers(Ui.regular, 23 * k, 0xC7FFFFFF);
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * k, 0xF2FFFFFF);
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xB8FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xC7FFFFFF);
        // The gallery's credit and the song, small, in the bottom corners.
        captionLabel = new TextPaint(Ui.text(Ui.medium, 18 * k, 0xB3FFFFFF));
        captionLabel.setLetterSpacing(0.1f);
        captionTitle = new TextPaint(Ui.text(Ui.medium, 24 * k, 0xEBFFFFFF));
        captionAuthor = new TextPaint(Ui.numbers(Ui.regular, 19 * k, 0xA6FFFFFF));
        for (Paint p : new Paint[] {captionLabel, captionTitle, captionAuthor}) p.setShadowLayer(14 * k, 0, 2 * k, 0x80000000);
        metaPaint.setLetterSpacing(0.014f);
        for (Paint p : new Paint[] {cornerDate, cornerQuote, cornerClock, labelPaint, titlePaint, metaPaint}) {
            p.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        }
        glassFor = "";
        dirty = true;
        chromeDirty = true;
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) recordCaption(s);
    }

    // MARK: - Photos

    /** Loads the next photo in the background (one at a time); once ready, it comes in at the right moment. */
    private void prepareNext() {
        if (!running || decoding || prepared != null) return;
        if (source.equals("clock")) return;
        if (Gallery.photos(source)) {
            prepareGallery();
            return;
        }
        List<Recs.Pick> list = recs.showcase();
        if (list.isEmpty()) return;
        Slide last = incoming != null ? incoming : current;
        int start = 0;
        if (last != null) {
            for (int i = 0; i < list.size(); i++) {
                if (Recs.key(list.get(i)).equals(Recs.key(last.pick))) {
                    start = i + 1;
                    break;
                }
            }
        }
        Recs.Pick pick = null;
        for (int i = 0; i < list.size() && pick == null; i++) {
            Recs.Pick p = list.get((start + i) % list.size());
            if (!failed.contains(Recs.key(p))) pick = p;
        }
        if (pick == null) {
            if (current == null) fallback = true;
            return;
        }
        if (last != null && Recs.key(pick).equals(Recs.key(last.pick))) {
            // Only one photo: the same one again, with a different motion.
            prepared = newSlide(pick, last.image, last.frost, last.lightCaption, last.lightClock);
            return;
        }
        File file = recs.readyImage(pick);
        if (file == null) {
            failed.add(Recs.key(pick));   // the image is gone from disk: try the next one
            prepareNext();
            return;
        }
        decoding = true;
        final int gen = generation;
        final Recs.Pick chosen = pick;
        worker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            try {
                images = Backdrop.photo(file, PW, PH, light);
                images[0] = Backdrop.gpu(withScrims(images[0], light[0], light[1]));
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver: photo for “" + chosen.title + "”: " + e.getMessage());
            }
            final Bitmap[] ready = images;
            handler.post(() -> {
                if (gen != generation) {
                    // The screensaver closed while the photo was loading: free the memory right away.
                    if (ready != null) for (Bitmap b : ready) b.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) {
                    failed.add(Recs.key(chosen));
                    prepareNext();
                    return;
                }
                prepared = newSlide(chosen, ready[0], ready[1], light[0], light[1]);
                invalidate();
            });
        });
    }

    /** The next landscape or painting, downloaded if needed and decoded in the background. */
    private void prepareGallery() {
        final Context ctx = getContext().getApplicationContext();
        final String kind = source;
        final int gen = generation, turn = galleryTurn;
        decoding = true;
        worker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            Gallery.Item item = null;
            List<Gallery.Item> list = gallery;
            if (list.isEmpty()) gallery = list = Gallery.list(ctx, kind);
            // One that won't download or decode doesn't stop the show: up to three tries.
            for (int tries = 0; tries < 3 && images == null && !list.isEmpty(); tries++) {
                item = list.get(galleryNext++ % list.size());
                try {
                    images = Backdrop.photo(Gallery.image(ctx, item), PW, PH, light);
                    images[0] = Backdrop.gpu(withScrims(images[0], light[0], light[1]));
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("screensaver: " + item.title + ": " + e.getMessage());
                    images = null;
                }
            }
            final Bitmap[] ready = images;
            final Gallery.Item shown = item;
            handler.post(() -> {
                if (gen != generation) {
                    if (ready != null) for (Bitmap b : ready) b.recycle();
                    return;
                }
                decoding = false;
                if (turn != galleryTurn) {   // not the one wanted any more: the right one now
                    if (ready != null) for (Bitmap b : ready) b.recycle();
                    prepareNext();
                    return;
                }
                if (ready == null) {
                    if (current == null) fallback = true;   // offline: the clock until the next try (every minute)
                    return;
                }
                Recs.Pick p = new Recs.Pick();
                p.kind = "gallery";
                p.id = shown.url.hashCode();
                p.title = Gallery.displayTitle(shown);
                p.meta = shown.author;
                p.backdrop = shown.url;
                prepared = newSlide(p, ready[0], ready[1], light[0], light[1]);
                saveNext(galleryNext);
                prefetch(ctx, gallery, galleryNext);
                invalidate();
            });
        });
    }

    private Slide newSlide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock) {
        Slide s = new Slide(pick, image, frost, lightCaption, lightClock, slides++);
        if (w > 0) recordCaption(s);
        return s;
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every 30 s). */
    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            bindPhotos();
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && (skip || up >= current.startedAt + showMs)) {
            skip = false;
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            bindPhotos();
            return;
        }
        if (incoming != null && up >= incoming.startedAt + FADE_MS) {
            Slide old = current;
            current = incoming;
            incoming = null;
            bindPhotos();
            if (old.image != current.image) {
                // Once the last frame that uses them has been drawn.
                Bitmap gone = old.image, goneFrost = old.frost;
                handler.postDelayed(() -> {
                    gone.recycle();
                    goneFrost.recycle();
                }, 1000);
            }
            prepareNext();
        }
    }

    /** The photos the clock's glass shows (the current one and, during a crossfade, the incoming one). */
    private void bindPhotos() {
        if (glassShader == null || current == null) return;
        glassShader.setInputShader("imageA", current.shader);
        glassShader.setInputShader("imageB", incoming != null ? incoming.shader : current.shader);
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        float u = Ui.clamp((up - s.startedAt) / (float) (showMs + FADE_MS));
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 20 per second, locked to every 3rd display frame (60 Hz)
    // Motion is slow (under half a pixel per frame). Measured on the TV: ~4 ms of GPU per frame of ours, but drawing
    // every frame, it only reaches ~50 fps (the system also composites the UI on the GPU); at 20 there's room to spare.

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (frameTimeNanos - lastFrame >= 45_000_000L) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The clock that drives the animations. */
    private long clock() {
        long up = SystemClock.uptimeMillis();
        return up;
    }

    /** The home screen's brightness (quick panel), read once a minute: the screensaver is dimmed the same. */
    private int dimColor = -1;

    @Override public void draw(Canvas canvas) {
        super.draw(canvas);
        if (dimColor == -1) dimColor = Ui.alpha(0xFF000000, Prefs.launcherDim(getContext()) / 100f);
        if (dimColor != 0) canvas.drawColor(dimColor);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = clock();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            dimColor = -1;
            clock = Ui.clock(now);
            date = Ui.date(now);
            dirty = true;
            chromeDirty = true;
        }
        if (running) advance(up);
        if (!glassFor.equals(clock)) buildGlass(clock);

        float first = current == null ? 0 : fadeIn(up - firstAt, FIRST_MS);
        if (current == null || first < 1) {
            if (fallback) drawFallback(canvas, up, current == null ? 1 : 1 - first);
            else canvas.drawRect(0, 0, w, h, black);
        }
        if (current == null) return;
        if (fallback && first >= 1) fallback = false;

        place(current, up, placeA);
        float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, FADE_MS);
        drawPhoto(canvas, current, placeA, first);
        if (incoming != null) {
            place(incoming, up, placeB);
            drawPhoto(canvas, incoming, placeB, e);
        }

        if (chromeDirty) recordChrome();
        // The keys' hints and menus take the bottom: the credit and the time step aside meanwhile.
        float hushTo = menu != MENU_NONE || notice != null || (up < hintUntil && current != null) ? 1 : 0;
        hush += (hushTo - hush) * 0.18f;
        if (Math.abs(hushTo - hush) < 0.01f) hush = hushTo;
        chrome.setAlpha(first * (1 - hush));
        chrome.draw(canvas);
        drawGlass(canvas, first, e);
        drawCaptions(canvas, up);
        drawNotice(canvas, up);
        drawMenu(canvas, up);
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) duration);
        return t * t * (3 - 2 * t);
    }

    /**
     * Black scrims where there is text, stronger where the photo is bright, painted once onto the photo itself (in the
     * background): each frame draws only the photo (the TV's GPU feels every full-screen layer). With the slow zoom,
     * they move a little along with the photo, which makes no difference to the text.
     */
    private static Bitmap withScrims(Bitmap photo, float lightCaption, float lightClock) {
        int w = photo.getWidth(), h = photo.getHeight();
        Canvas c = new Canvas(photo);
        Paint p = new Paint(Paint.DITHER_FLAG);
        float caption = scrimStrength(lightCaption, 0.5f), corner = scrimStrength(lightClock, 0.42f);
        p.setShader(new LinearGradient(0, h, 0, h * 0.42f,
                new int[] {black(0.55f * caption), black(0.33f * caption), black(0.11f * caption), 0},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.42f, w, h, p);
        RadialGradient around = new RadialGradient(0, h, w * 0.7f,
                new int[] {black(caption), black(0.55f * caption), black(0.15f * caption), 0},
                new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.62f, 0, h);
        around.setLocalMatrix(m);
        p.setShader(around);
        c.drawRect(0, h - w * 0.7f * 0.62f, w * 0.7f, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.3f, new int[] {black(0.3f), black(0.075f), 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.3f, p);
        float cx = w * 0.86f, cy = h * 0.15f;
        RadialGradient clockSide = new RadialGradient(cx, cy, w * 0.36f,
                new int[] {black(corner), black(0.6f * corner), black(0.15f * corner), 0},
                new float[] {0, 0.42f, 0.75f, 1}, Shader.TileMode.CLAMP);
        Matrix mc = new Matrix();
        mc.setScale(1, 0.72f, cx, cy);
        clockSide.setLocalMatrix(mc);
        p.setShader(clockSide);
        c.drawRect(w * 0.5f, 0, w, cy + w * 0.36f * 0.72f, p);
        return photo;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    // MARK: - Corner: date and weather, glass clock, football

    private void recordChrome() {
        Canvas c = chrome.beginRecording(w, h);
        try {
            if (gallerySource()) {
                drawAmbientCorner(c);
                return;
            }
            float right = RIGHT * k;
            float x = right;
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                String temp = HomeData.degrees(wx.temp);
                float tw = cornerDate.measureText(temp);
                c.drawText(temp, x - tw, DATE_BASE * k, cornerDate);
                x -= tw + 10 * k;
                String symbol = Symbols.weather(wx.code, wx.isDay);
                float size = 30 * k, sw = Symbols.width(getContext(), symbol, size);
                Symbols.draw(getContext(), c, symbol, x - sw / 2, DATE_BASE * k - Ui.halfCap(cornerDate), size, 0xEBFFFFFF);
                x -= sw + 26 * k;
            }
            c.drawText(date, x - cornerDate.measureText(date), DATE_BASE * k, cornerDate);

            Glass g = glass;
            if (g != null && glassShader != null) {
                // The shadow under the glass: sets the digits apart from a bright photo.
                shadowPaint.setAlpha(140);
                c.drawBitmap(g.shadow, g.x, g.y + 6 * k, shadowPaint);
            } else {
                Rect ink = new Rect();
                cornerClock.getTextBounds(clock, 0, clock.length(), ink);
                c.drawText(clock, right - ink.right, CLOCK_TOP * k - ink.top, cornerClock);
            }

            // The favourite team's game (or the big game being played) under the clock, right-aligned.
            float base = (CLOCK_TOP + Ui.cap(cornerClock) / k + QUOTES_GAP) * k;
            drawGames(c, cornerQuote, 0xA6FFFFFF, right, base, 1, false);
            drawMusicCorner(c);
        } finally {
            chrome.endRecording();
        }
        chromeDirty = false;
    }

    private void drawGlass(Canvas c, float alpha, float e) {
        Glass g = glass;
        if (g == null || glassShader == null || alpha <= 0.004f || gallerySource()) return;
        // The glass samples the frosted version (1/6 of the photo): same position, with the scale 6 times larger.
        float f = Backdrop.PHOTO_FROST;
        glassShader.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f);
        if (incoming != null) glassShader.setFloatUniform("placeB", placeB[0], placeB[1], placeB[2] * f);
        else glassShader.setFloatUniform("placeB", placeA[0], placeA[1], placeA[2] * f);
        glassShader.setFloatUniform("mixB", incoming == null ? 0 : e);
        glassShader.setFloatUniform("origin", g.x, g.y);
        glassShader.setFloatUniform("ink", g.inkTop, g.inkHeight);
        glassShader.setFloatUniform("refraction", 14 * k);
        glassShader.setFloatUniform("specular", 1);
        glassShader.setFloatUniform("alpha", alpha);
        c.drawRect(g.x, g.y, g.x + g.cover.getWidth(), g.y + g.cover.getHeight(), glassPaint);
    }

    /** Builds the glass clock's digits in the background (once a minute) and swaps them in when they're ready. */
    private void buildGlass(String text) {
        glassFor = text;
        if (glassShader == null || text.isEmpty()) {
            chromeDirty = true;
            return;
        }
        final Paint paint = new Paint(cornerClock);
        paint.clearShadowLayer();
        paint.setColor(0xFFFFFFFF);
        final float right = RIGHT * k, top = CLOCK_TOP * k, scale = k;
        final int gen = generation;
        worker.execute(() -> {
            Glass g = makeGlass(text, paint, right, top, scale);
            handler.post(() -> {
                if (gen != generation || !text.equals(glassFor)) return;
                glass = g;
                glassShader.setInputShader("slope", nearest(g.slope));
                glassShader.setInputShader("cover", nearest(g.cover));
                chromeDirty = true;
                invalidate();
            });
        });
    }

    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Frost.filter(s, BitmapShader.FILTER_MODE_NEAREST);
        return s;
    }

    /**
     * The sharp rendering of the digits, the surface height (the blurred rendering: 0.5 at the edge, 1 in the middle of
     * the strokes), its slope (for the lens and the highlight) and the shadow.
     */
    private static Glass makeGlass(String text, Paint paint, float right, float top, float k) {
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int w = ink.width() + 2 * pad, h = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int[] height = blur(alpha, w, h, Math.max(2, Math.round(6 * k)), 3);
        int[] soft = blur(alpha, w, h, Math.max(3, Math.round(12 * k)), 3);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        int[] slope = new int[px.length], shadow = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
                int b = Math.min(255, height[i] / 16);
                slope[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                shadow[i] = Math.min(255, soft[i] / 16) << 24;
            }
        }
        int x = Math.round(right - ink.width()) - pad, y = Math.round(top) - pad;
        return new Glass(text, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888), cover,
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), x, y, pad, Math.max(1, ink.height()));
    }

    /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
    private static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }

    // MARK: - Photo caption (bottom left)

    private void recordCaption(Slide s) {
        Canvas c = s.caption.beginRecording(w, h);
        try {
            if ("gallery".equals(s.pick.kind)) {
                drawGalleryCaption(c, s);
                return;
            }
            float x = MARGIN * k, metaBase = META_BASE * k, titleBase = metaBase - 58 * k;
            String title = s.pick.title == null ? "" : s.pick.title;
            StaticLayout t = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(TITLE_W * k))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).build();
            float originY = titleBase - t.getLineBaseline(t.getLineCount() - 1);
            float capTop = originY + t.getLineBaseline(0) - Ui.cap(titlePaint);
            c.drawText(Recs.periodLabel(), x, capTop - 30 * k, labelPaint);
            c.save();
            c.translate(x - Ui.leftBearing(titlePaint, title), originY);
            t.draw(c);
            c.restore();
            if (s.pick.meta != null && !s.pick.meta.isEmpty()) c.drawText(s.pick.meta, x, metaBase, metaPaint);
        } finally {
            s.caption.endRecording();
            s.caption.setPosition(0, 0, w, h);
        }
    }

    /** A landscape's or a painting's credit, small, bottom left (like Google TV's ambient mode): label, title, author. */
    private void drawGalleryCaption(Canvas c, Slide s) {
        float x = MARGIN * k, base = META_BASE * k, max = 760 * k;
        String title = s.pick.title == null ? "" : s.pick.title;
        boolean fav = Gallery.isFavorite(getContext(), s.pick.backdrop);
        c.drawText(Gallery.label(source) + (fav && !source.equals(Gallery.FAVORITES) ? "  ♥" : ""), x, base - 62 * k, captionLabel);
        c.drawText(TextUtils.ellipsize(title, captionTitle, max, TextUtils.TruncateAt.END).toString(), x, base - 28 * k, captionTitle);
        if (s.pick.meta != null && !s.pick.meta.isEmpty()) {
            c.drawText(TextUtils.ellipsize(s.pick.meta, captionAuthor, max, TextUtils.TruncateAt.END).toString(), x, base, captionAuthor);
        }
    }

    /** The caption fades out fast; the new one rises in once the photo has partly changed (like the home screen). */
    private void drawCaptions(Canvas c, long up) {
        if (incoming == null) {
            float a = current.startedAt == firstAt ? fadeIn(up - firstAt - 700, 900) : 1;
            current.caption.setAlpha(a * (1 - hush));
            current.caption.setTranslationY(14 * k * (1 - a));
            current.caption.draw(c);
            return;
        }
        long t = up - incoming.startedAt;
        float out = 1 - Ui.clamp(t / 500f);
        if (out > 0.004f) {
            current.caption.setAlpha(out * (1 - hush));
            current.caption.setTranslationY(0);
            current.caption.draw(c);
        }
        float in = fadeIn(t - 1300, 900);
        if (in > 0.004f) {
            incoming.caption.setAlpha(in * (1 - hush));
            incoming.caption.setTranslationY(14 * k * (1 - in));
            incoming.caption.draw(c);
        }
    }

    // MARK: - Without photos: the big clock over the aurora

    private void drawFallback(Canvas canvas, long up, float alpha) {
        float t = (up - startedAt) / 1000f;
        if (up - lastSky >= 100) {
            lastSky = up;
            scene.advance(timeOffset + t);
        }
        scene.drawBackground(canvas);
        canvas.drawColor(0xB3000000);   // the aurora as a faint glow on near-black: the person's TV is OLED
        if (dirty) {
            Canvas rc = content.beginRecording(w, h);
            try {
                drawContent(rc);
            } finally {
                content.endRecording();
            }
            dirty = false;
        }
        double phase = 2 * Math.PI * t / DRIFT_PERIOD_S;
        content.setTranslationX((float) (40 * k * Math.sin(phase)));
        content.setTranslationY((float) (26 * k * Math.sin(2 * phase + 0.7)));
        content.setAlpha(alpha);
        content.draw(canvas);
    }

    private void drawContent(Canvas c) {
        float cx = w / 2f;
        float clockCap = Ui.cap(clockPaint);
        float clockBase = h * 0.5f + clockCap / 2 - 10 * k;
        c.drawText(date, cx, clockBase - clockCap - 34 * k, datePaint);
        c.drawText(clock, cx, clockBase, clockPaint);

        // Weather: symbol, temperature, high and low, centered under the clock.
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp) && Prefs.weather()) {
            float base = clockBase + 76 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            String now = HomeData.degrees(wx.temp);
            if (wx != rangeWeather || Ui.generation != rangeGeneration || Prefs.version != rangePrefs) {
                range = HomeData.highLow(wx, "  ");
                rangeWeather = wx;
                rangeGeneration = Ui.generation;
                rangePrefs = Prefs.version;
            }
            float sw = Symbols.width(getContext(), symbol, 32 * k);
            float width = sw + 12 * k + weatherPaint.measureText(now) + 18 * k + weatherDim.measureText(range);
            float x = cx - width / 2;
            Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(weatherPaint), 32 * k, 0xC7FFFFFF);
            x += sw + 12 * k;
            c.drawText(now, x, base, weatherPaint);
            x += weatherPaint.measureText(now) + 18 * k;
            c.drawText(range, x, base, weatherDim);
        }

        // What's playing, under the weather.
        drawCenteredMusic(c, cx, clockBase + 200 * k);

        // The game near the bottom edge, centred.
        drawGames(c, quotePaint, 0x80FFFFFF, cx, h - 92 * k, 0, true);
    }

    /** The games of the moment (Futbol.shownNow), one per line; `upward`: the last line sits at `base`. */
    private void drawGames(Canvas c, Paint p, int color, float x, float base, int align, boolean upward) {
        if (!Prefs.futbol() || data.matches.isEmpty()) return;
        List<Futbol.Match> shown = Futbol.shownNow(data.matches, System.currentTimeMillis());
        float step = Ui.cap(p) * 2.4f;
        if (upward) base -= (shown.size() - 1) * step;
        for (Futbol.Match m : shown) {
            Futbol.drawLine(c, m, p, color, x, base, align);
            base += step;
        }
    }

    private boolean gallerySource() {
        return Gallery.photos(source);
    }

    private TextPaint ambientClock, ambientTemp, ambientDate;

    /**
     * Over landscapes and art, like Google TV's ambient mode: bottom right, the temperature, the weather and the time
     * on one small line, the date under them; top right, the song playing and, under it, the game (when one is on or
     * close). The picture's credit is bottom left (drawGalleryCaption).
     */
    private void drawAmbientCorner(Canvas c) {
        if (ambientClock == null || ambientClock.getTextSize() != 68 * k) {
            ambientClock = new TextPaint(Ui.numbers(Ui.light, 68 * k, 0xF2FFFFFF));
            ambientTemp = new TextPaint(Ui.numbers(Ui.regular, 40 * k, 0xE6FFFFFF));
            ambientDate = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xB3FFFFFF));
            for (Paint p : new Paint[] {ambientClock, ambientTemp, ambientDate}) {
                p.setShadowLayer(16 * k, 0, 2 * k, 0x80000000);
                p.setTextAlign(Paint.Align.RIGHT);
            }
        }
        float right = RIGHT * k, base = META_BASE * k;
        NowPlaying np = nowPlaying;
        boolean music = np != null && np.visible();
        float dateBase = base, clockBase = dateBase - 36 * k;

        c.drawText(clock, right, clockBase, ambientClock);
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp) && Prefs.weather()) {
            float x = right - ambientClock.measureText(clock) - 24 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            float size = 36 * k, sw = Symbols.width(getContext(), symbol, size);
            Symbols.draw(getContext(), c, symbol, x - sw / 2, clockBase - Ui.halfCap(ambientTemp), size, 0xE6FFFFFF);
            c.drawText(HomeData.degrees(wx.temp), x - sw - 12 * k, clockBase, ambientTemp);
        }
        c.drawText(date, right, dateBase, ambientDate);
        float top = 56 * k, art = 62 * k;
        if (music) drawAmbientMusic(c, right, top + art - 6 * k, np);

        drawGames(c, cornerQuote, 0xA6FFFFFF, right, music ? top + art + 44 * k : DATE_BASE * k, 1, false);
    }

    /**
     * The clock screen's song, in the photos' style: no capsule, the cover with the title and the artist beside it,
     * the pair centred on `cx`, its second line at `base`.
     */
    private void drawCenteredMusic(Canvas c, float cx, float base) {
        NowPlaying np = nowPlaying;
        if (np == null || !np.visible() || captionTitle == null) return;
        float art = 62 * k, gap = 18 * k, max = 520 * k;
        String t = TextUtils.ellipsize(np.title, captionTitle, max, TextUtils.TruncateAt.END).toString();
        String a = TextUtils.ellipsize(np.artist, captionAuthor, max, TextUtils.TruncateAt.END).toString();
        float text = Math.max(captionTitle.measureText(t), captionAuthor.measureText(a));
        float left = cx - ((np.art != null ? art + gap : 0) + text) / 2;
        if (np.art != null) {
            android.graphics.RectF cover = new android.graphics.RectF(left, base - art + 6 * k, left + art, base + 6 * k);
            BitmapShader sh = new BitmapShader(np.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(art / np.art.getWidth(), art / np.art.getHeight());
            m.postTranslate(cover.left, cover.top);
            sh.setLocalMatrix(m);
            musicArt.setShader(sh);
            musicArt.clearShadowLayer();   // on black a glow around the cover only looks blurry
            c.drawRoundRect(cover, 10 * k, 10 * k, musicArt);
            left += art + gap;
        }
        captionTitle.setAlpha(np.playing ? 235 : 150);
        c.drawText(t, left, base - 28 * k, captionTitle);
        c.drawText(a, left, base, captionAuthor);
        captionTitle.setAlpha(235);
    }

    /** The song under the time: the cover and, beside it, the title and the artist (two lines ending at `base`). */
    private void drawAmbientMusic(Canvas c, float right, float base, NowPlaying np) {
        float art = 62 * k, max = 460 * k;
        android.graphics.RectF cover = new android.graphics.RectF(right - art, base - art + 6 * k, right, base + 6 * k);
        if (np.art != null) {
            BitmapShader sh = new BitmapShader(np.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(art / np.art.getWidth(), art / np.art.getHeight());
            m.postTranslate(cover.left, cover.top);
            sh.setLocalMatrix(m);
            musicArt.setShader(sh);
            musicArt.clearShadowLayer();   // the cover crisp, without a glow
            c.drawRoundRect(cover, 10 * k, 10 * k, musicArt);
        }
        float tx = np.art != null ? cover.left - 18 * k : right;
        captionTitle.setTextAlign(Paint.Align.RIGHT);
        captionAuthor.setTextAlign(Paint.Align.RIGHT);
        captionTitle.setAlpha(np.playing ? 235 : 150);
        c.drawText(TextUtils.ellipsize(np.title, captionTitle, max, TextUtils.TruncateAt.END).toString(), tx, base - 28 * k, captionTitle);
        c.drawText(TextUtils.ellipsize(np.artist, captionAuthor, max, TextUtils.TruncateAt.END).toString(), tx, base, captionAuthor);
        captionTitle.setAlpha(235);
        captionTitle.setTextAlign(Paint.Align.LEFT);
        captionAuthor.setTextAlign(Paint.Align.LEFT);
    }

    // MARK: - Now playing

    /**
     * Over the photos, the song as a credit like the picture's (bottom right, mirroring it): the cover, and beside it
     * "Sonando", the title and the artist, small, with a soft shadow instead of a capsule.
     */
    private void drawMusicCorner(Canvas c) {
        NowPlaying np = nowPlaying;
        if (np == null || !np.visible() || captionTitle == null) return;
        float right = RIGHT * k, base = META_BASE * k, art = 84 * k, max = 520 * k;
        android.graphics.RectF cover = new android.graphics.RectF(right - art, base - art + 4 * k, right, base + 4 * k);
        if (np.art != null) {
            BitmapShader sh = new BitmapShader(np.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(art / np.art.getWidth(), art / np.art.getHeight());
            m.postTranslate(cover.left, cover.top);
            sh.setLocalMatrix(m);
            musicArt.setShader(sh);
            musicArt.clearShadowLayer();   // the cover crisp, without a glow
            c.drawRoundRect(cover, 12 * k, 12 * k, musicArt);
        }
        float tx = np.art != null ? cover.left - 22 * k : right;
        for (Paint p : new Paint[] {captionLabel, captionTitle, captionAuthor}) p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(np.playing ? "SONANDO" : "EN PAUSA", tx, base - 62 * k, captionLabel);
        c.drawText(TextUtils.ellipsize(np.title, captionTitle, max, TextUtils.TruncateAt.END).toString(), tx, base - 28 * k, captionTitle);
        c.drawText(TextUtils.ellipsize(np.artist, captionAuthor, max, TextUtils.TruncateAt.END).toString(), tx, base, captionAuthor);
        for (Paint p : new Paint[] {captionLabel, captionTitle, captionAuthor}) p.setTextAlign(Paint.Align.LEFT);
    }

    private NowPlaying nowPlaying;
    private final Paint musicFill = new Paint(Paint.ANTI_ALIAS_FLAG), musicRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint musicArt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private android.text.TextPaint musicTitle, musicArtist;

    /**
     * The song playing (Spotify, YouTube Music...), as on the home screen: a smoked-glass capsule with the cover, the
     * title and the artist. align: 0 centres it on x, 1 ends it at x. Drawn into the recorded layers, so it costs
     * nothing per frame; a new song or play/pause records them again.
     */
    private void drawMusic(Canvas c, float x, float top, int align) {
        NowPlaying np = nowPlaying;
        if (np == null || !np.visible()) return;
        if (musicTitle == null) {
            musicTitle = new android.text.TextPaint(Ui.text(Ui.displaySemibold, 24 * k, 0xF2FFFFFF));
            musicArtist = new android.text.TextPaint(Ui.text(Ui.regular, 19 * k, 0x99FFFFFF));
            musicRim.setStyle(Paint.Style.STROKE);
            musicRim.setStrokeWidth(1.5f * k);
        }
        float h = 70 * k, pad = 10 * k, art = h - 2 * pad, gap = 16 * k, max = 420 * k;
        CharSequence t = android.text.TextUtils.ellipsize(np.title, musicTitle, max, android.text.TextUtils.TruncateAt.END);
        CharSequence a = android.text.TextUtils.ellipsize(np.artist, musicArtist, max, android.text.TextUtils.TruncateAt.END);
        float text = Math.max(musicTitle.measureText(t, 0, t.length()), musicArtist.measureText(a, 0, a.length()));
        float width = pad + art + gap + text + h * 0.5f;
        float left = align == 0 ? x - width / 2 : x - width;
        android.graphics.RectF r = new android.graphics.RectF(left, top, left + width, top + h);
        musicFill.setColor(0x2EFFFFFF);
        c.drawRoundRect(r, h / 2, h / 2, musicFill);
        musicRim.setColor(0x33FFFFFF);
        c.drawRoundRect(r, h / 2, h / 2, musicRim);
        android.graphics.RectF cover = new android.graphics.RectF(left + pad, top + pad, left + pad + art, top + pad + art);
        if (np.art != null) {
            android.graphics.BitmapShader sh = new android.graphics.BitmapShader(np.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(art / np.art.getWidth(), art / np.art.getHeight());
            m.postTranslate(cover.left, cover.top);
            sh.setLocalMatrix(m);
            musicArt.setShader(sh);
            c.drawRoundRect(cover, art * 0.28f, art * 0.28f, musicArt);
        } else {
            musicFill.setColor(0x24FFFFFF);
            c.drawRoundRect(cover, art * 0.28f, art * 0.28f, musicFill);
        }
        float tx = cover.right + gap, t1 = Ui.cap(musicTitle), t2 = Ui.cap(musicArtist), block = t1 + 11 * k + t2;
        float b1 = r.centerY() - block / 2 + t1;
        musicTitle.setAlpha(np.playing ? 242 : 160);
        c.drawText(t, 0, t.length(), tx, b1, musicTitle);
        c.drawText(a, 0, a.length(), tx, b1 + 11 * k + t2, musicArtist);
    }
}
