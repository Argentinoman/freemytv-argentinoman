package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the football game
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and the week's football games); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    private static final float SHELF_PAD = 28, TILE_GAP = 34;   // compact shelf for a 65" set (was 40, 48)
    private static final float TILE_RADIUS = 17;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 6, VISIBLE_ROWS = 4;   // compact grid for a 65" set (was 5 × 3)
    private static final float GRID_W = 232, GRID_H = 130, GRID_GAP = 34, GRID_ROW = 196, GRID_TOP = 196, GRID_RADIUS = 17;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, WATCH = 8;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 850, L_TILE_H = 84;   // smaller shelf tiles (was 814, 126); same bottom margin
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final float RECENT_W = 196, RECENT_GAP = 24, RECENT_TOP = 368;   // compact (was 268)
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;
    /** "Seguir en…" (the recent apps, without TMDB picks) and the now-playing widget. */
    private static final int Z_RECENT = 6, Z_PLAYER = 7, Z_AVATAR = 8, Z_PLAYER_CTRL = 9, Z_FUTBOL = 10, Z_WEATHER = 11;
    /** Nothing focused (up from the top row, or Back on the shelf): any arrow brings the focus back where it was. */
    private static final int Z_IDLE = 12;
    private int idleFrom = Z_SHELF;
    /** The floating "Continuar viendo" widget at the centre right (↑ from the shelf enters it). */
    private static final int Z_WATCH = 13;
    /** The drawer's weather card: page 0 the next hours, page 1 the next seven days. */
    private final Spring[] weatherFocus = {new Spring(0.26f, 0.85f, 0)};
    private int weatherPage;
    private final String[] dayText = new String[7], dayMaxText = new String[7], dayMinText = new String[7];
    /** The drawer's football card: focused, ←/→ turn its pages by hand (and it stops turning on its own for a while). */
    private final Spring[] futbolFocus = {new Spring(0.26f, 0.85f, 0)};
    private int cardPage;
    private long cardManualAt;
    /** The now-playing controls under the widget: previous, play/pause, next, stop. */
    private final Spring[] ctrlFocus = new Spring[4];
    /** No shuffle or repeat: Spotify ignored both from other apps (see NowPlaying). */
    private static final int C_PREV = 0, C_PLAY = 1, C_NEXT = 2, C_STOP = 6;
    private final int[] ctrlKinds = {C_PREV, C_PLAY, C_NEXT, C_STOP};
    private final Spring[] ctrlShow = {new Spring(0.28f, 0.8f, 0)};
    private final RectF ctrlRect = new RectF();
    private int ctrlIndex = 1;
    /** The person's choice (OK on the widget): the full capsule or just the cover. */
    private boolean npCollapsed;
    /** The account circle at the top right: the person's photo (assets/profile.jpg) or a silhouette. */
    private final Spring[] avatarFocus = new Spring[1];
    /** Off: the account is in the TV's settings, reached from the quick settings panel. */
    private static final boolean SHOW_AVATAR = false;
    private final RectF avatarRect = new RectF();
    private Bitmap avatar;
    private boolean avatarLoaded;
    private static final int RECENT_MAX = 3;
    private List<HomeApps.Item> recent = new ArrayList<>();
    private final RectF[] recentRects = new RectF[RECENT_MAX];
    private final Spring[] recentFocus = new Spring[RECENT_MAX];
    private final Spring[] playerFocus = new Spring[1];
    private int recentIndex;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, watchLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or football (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    private final Spring[] topFocus = new Spring[6];
    private final RectF[] topRects = new RectF[6];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lMatch, lTip, lDate, lClock, lWeather, lClockMatch;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF161616, 0xFF000000, 0});   // HBO Max since 2025: white on black
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) holdAction(); };

    /** OK held: on the shelf the app lifts (to move it, ↓ for its options); in All Apps its options open. */
    private void holdAction() {
        if (sheetOpen) openGridMenu();
        else startReorder();
    }

    // MARK: - App options (Info, Remove, Uninstall)

    private static final String[] SHELF_ALL = {"Información", "Reemplazar", "Quitar del estante", "Desinstalar"};
    private static final String[] GRID_ALL = {"Mover", "Agregar al estante", "Información", "Desinstalar"};
    /** The options of the app whose menu is open: without Uninstall for the TV's own (system) apps. */
    private String[] shelfOptions = SHELF_ALL, gridOptions = GRID_ALL;

    private String[] optionsFor(String pkg, String[] all) {
        boolean system = true;
        try {
            int flags = getContext().getPackageManager().getApplicationInfo(pkg, 0).flags;
            system = (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception ignored) {
        }
        if (!system) return all;
        return java.util.Arrays.copyOf(all, all.length - 1);   // Uninstall is always the last one
    }
    private boolean appMenu, gridMenu;
    private int menuIndex;
    private final RectF menuPill = new RectF();
    private Paint menuText;

    private void openGridMenu() {
        if (replacing != null) return;   // choosing a replacement: OK picks, there's no menu
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (gridIndex >= others.size() || others.get(gridIndex).pkg == null) return;
        gridOptions = optionsFor(others.get(gridIndex).pkg, GRID_ALL);
        gridMenu = true;
        okHeld = true;   // the release of the press that opened it opens nothing
        menuIndex = 0;
        gridDirty = true;
        click.open();
        wake();
    }

    /** Keys while the grid's options show. */
    private boolean gridMenuKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    menuIndex = Math.max(0, Math.min(gridOptions.length - 1, menuIndex + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    gridDirty = true;
                    click.move();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    okDown = false;
                    String pkg = others.get(gridIndex).pkg;
                    gridMenu = false;
                    gridDirty = true;
                    appAction(gridOptions[menuIndex], pkg);
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    gridMenu = false;
                    gridDirty = true;
                    click.move();
                }
                return true;
            default:
                return false;
        }
    }

    // MARK: - Moving an app in All Apps (hold OK > Move): the arrows carry it anywhere, OK drops it, Back undoes

    private boolean gridMove;
    private List<HomeApps.Item> gridBefore;

    private void startGridMove() {
        gridMove = true;
        gridBefore = new ArrayList<>(others);
        gridDirty = true;
        showToast("← → ↑ ↓ mover · OK soltar · Atrás cancelar");
        click.open();
    }

    private boolean gridMoveKey(KeyEvent e, boolean down) {
        int code = e.getKeyCode();
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx != 0 || dy != 0) {
            if (!down) return true;
            int to = Math.max(0, Math.min(others.size() - 1, gridIndex + dx + dy * COLUMNS));
            if (to == gridIndex) return true;
            List<HomeApps.Item> list = new ArrayList<>(others);
            list.add(to, list.remove(gridIndex));
            others = list;
            gridLabels = null;   // the names follow their cards
            gridIndex = to;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && e.getRepeatCount() == 0) {
                gridMove = false;
                List<String> order = new ArrayList<>();
                for (HomeApps.Item it : others) if (it.pkg != null) order.add(it.pkg);
                Prefs.setGridOrder(order);
                gridDirty = true;
                click.open();
            }
            okDown = okHeld = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                gridMove = false;
                others = gridBefore;   // as it was
                gridLabels = null;
                gridIndex = Math.min(gridIndex, others.size() - 1);
                retargetGrid(false);
                gridDirty = true;
                click.move();
            }
            return true;
        }
        return true;
    }

    // MARK: - Replacing a shelf app (hold OK > ↓ > Replace): All Apps opens to choose the one that takes its place

    /** The shelf app being replaced while All Apps is open to choose; null otherwise. */
    private String replacing;

    private void startReplace(String pkg) {
        List<String> onShelf = HomeApps.shelfPackages(getContext());
        List<HomeApps.Item> choices = new ArrayList<>();
        for (HomeApps.Item it : others) if (it.pkg != null && !onShelf.contains(it.pkg)) choices.add(it);   // no repeats
        if (choices.isEmpty()) {
            showToast("No hay otras apps para poner");
            return;
        }
        replacing = pkg;
        clearToasts();   // the moving notice would sit over the chooser's title
        setOthers(choices);
        openSheet();
    }

    /** OK on an app while choosing: it takes the replaced one's place on the shelf. */
    private void finishReplace(String chosen) {
        List<String> list = new ArrayList<>(HomeApps.shelfPackages(getContext()));
        int at = list.indexOf(replacing);
        if (at >= 0 && chosen != null && !list.contains(chosen)) {
            list.set(at, chosen);
            Prefs.setShelf(list);
            showToast("Listo, reemplazada");
        }
        replacing = null;
        closeSheet();
        reload();
    }

    /** What an option does; the shelf's own changes (order, removal) are saved first. */
    private void appAction(String option, String pkg) {
        Context c = getContext();
        try {
            switch (option) {
                case "Mover":
                    startGridMove();
                    break;
                case "Información":
                    c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + pkg)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case "Desinstalar":
                    c.startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE,
                            android.net.Uri.parse("package:" + pkg)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case "Reemplazar":
                    startReplace(pkg);
                    break;
                case "Quitar del estante": {
                    List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
                    list.remove(pkg);
                    Prefs.setShelf(list);
                    reload();
                    break;
                }
                default: {   // "Agregar al estante"
                    List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
                    if (!list.contains(pkg)) list.add(pkg);
                    Prefs.setShelf(list);
                    reload();
                    showToast("Agregada al estante");
                    break;
                }
            }
        } catch (RuntimeException ex) {
            AppLog.w("app option " + option + ": " + ex.getMessage());
        }
    }

    /** The options as glass pills under `anchor`, the chosen one white (the tvOS focus). */
    private void drawAppMenu(Canvas c, RectF anchor, String[] options) {
        if (menuText == null || menuText.getTextSize() != 22 * k) menuText = Ui.text(Ui.medium, 22 * k, 0xFFFFFFFF);
        float h = 52 * k, gap = 12 * k, pad = 26 * k, total = -gap;
        for (String o : options) total += menuText.measureText(o) + 2 * pad + gap;
        float x = Math.max(MARGIN * k, Math.min(CONTENT_R * k - total, anchor.centerX() - total / 2));
        float top = Math.min(h() - h - 16 * k, anchor.bottom + 22 * k);
        for (int i = 0; i < options.length; i++) {
            float w = menuText.measureText(options[i]) + 2 * pad;
            menuPill.set(x, top, x + w, top + h);
            boolean on = i == menuIndex;
            tmp.set(menuPill);
            tmp.offset(0, 6 * k);
            shadow.setColor(0x66000000);
            shadows.draw(c, tmp, h / 2, SHADOW_BLUR * k, shadow);
            fill.setShader(null);
            fill.setColor(on ? 0xFFF5F5F7 : 0xE62C2C2E);
            c.drawRoundRect(menuPill, h / 2, h / 2, fill);
            menuText.setColor(on ? 0xFF1C1C1E : 0xF2FFFFFF);
            c.drawText(options[i], menuPill.left + pad, menuPill.centerY() + Ui.halfCap(menuText), menuText);
            x += w + gap;
        }
    }

    private float h() {
        return getHeight();
    }

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), futbolCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private HomeData.Weather textWeather;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText;
    private final String[] hourText = new String[6], hourTempText = new String[6];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case WATCH: if (!picksOn()) drawWatch(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }

        /** The widget's layer fades by its own alpha: each draw takes it (no offscreen copy of a full-screen layer). */
        @Override public boolean hasOverlappingRendering() {
            return kind != WATCH;
        }
    }

    private final Runnable reloadSoon = this::reload;

    /** An app installed, removed, turned on or off while Home is on screen: the shelf and All Apps follow. */
    private final android.content.BroadcastReceiver appsChanged = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) {
            reloadSoon();
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updateWallpaper();
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // The "Continuar viendo" island: its own layer, so fading it in and out (on every move along the shelf) only
        // changes the layer's alpha, without drawing the glass, the clock or the island again on each frame.
        watchLayer = new Layer(a, WATCH);
        watchLayer.setVisibility(INVISIBLE);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        // (Measured again on a TCL with a Mali-G31, Android 11: with layers the drawer cost more GPU, 11-15 ms against
        // 8-14 ms without.)
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        dimLayer = new View(a);
        dimLayer.setBackgroundColor(0xFF000000);
        dimLayer.setAlpha(0);
        dimLayer.setVisibility(GONE);
        veilLayer = new View(a);
        veilLayer.setVisibility(GONE);
        for (View v : new View[] {sky, veilLayer, heroLayer, watchLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, dimLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < 6; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < RECENT_MAX; i++) {
            recentFocus[i] = new Spring(0.32f, 0.78f, 0);
            recentRects[i] = new RectF();
        }
        playerFocus[0] = new Spring(0.26f, 0.85f, 0);
        avatarFocus[0] = new Spring(0.26f, 0.85f, 0);
        for (int i = 0; i < ctrlFocus.length; i++) ctrlFocus[i] = new Spring(0.26f, 0.85f, 0);
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    /** Over everything: the brightness picked in the quick panel (Prefs.launcherDim). */
    private View dimLayer;

    private final android.content.BroadcastReceiver dimReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) {
            applyDim();
            updateWallpaper();
        }
    };

    /**
     * The home screen's brightness: the system's own dimming (what darkens the screen behind a dialog), asked for by
     * an empty window over Home. The compositor lays a plain black over the screen; Home itself doesn't draw it, so it
     * costs no GPU time per frame (a black layer drawn by Home cost 4 to 7 ms on every frame of an animation). The
     * window's brightness was tried first: TCL keeps its backlight on its own picture settings and ignores it.
     */
    private View dimWindow;

    /**
     * An app or an input opens from Home: the system's dim, a layer of its own that the transition doesn't carry along
     * (the brightness jumped midway), gives way to Home's own black layer, just as dark, which fades out with Home. The
     * system's dim goes one frame later, once that layer is on screen (never a bright frame). Back on Home, applyDim
     * brings it back. Not for the quick panel: Home stays in view behind it.
     */
    private void dimInHome() {
        final View w = dimWindow;
        if (w == null) return;
        float dim = ((android.view.WindowManager.LayoutParams) w.getLayoutParams()).dimAmount;
        dimLayer.animate().cancel();
        dimLayer.setAlpha(dim);
        dimLayer.setVisibility(VISIBLE);
        dimWindow = null;
        dimLayer.postOnAnimation(() -> dimLayer.postOnAnimation(() -> {
            try {
                activity.getWindowManager().removeViewImmediate(w);
            } catch (RuntimeException ignored) {
            }
        }));
    }

    private void applyDim() {
        float dim = Prefs.launcherDim(getContext()) / 100f;
        // Coming back (still hidden, or fading in from black): the return fade ends at this dim and hands over then.
        if (dimFading || (getWindowVisibility() != VISIBLE && shownBefore)) return;
        dimLayer.animate().cancel();
        dimLayer.setAlpha(0);
        dimLayer.setVisibility(GONE);
        android.view.WindowManager wm = activity.getWindowManager();
        if (dim <= 0 || getWindowToken() == null) {
            if (dimWindow != null) {
                try {
                    wm.removeView(dimWindow);
                } catch (RuntimeException ignored) {
                }
                dimWindow = null;
            }
            return;
        }
        android.view.WindowManager.LayoutParams p = dimWindow != null
                ? (android.view.WindowManager.LayoutParams) dimWindow.getLayoutParams()
                : new android.view.WindowManager.LayoutParams(1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                        android.graphics.PixelFormat.TRANSLUCENT);
        p.dimAmount = dim;
        p.token = getWindowToken();
        p.windowAnimations = 0;
        try {
            if (dimWindow == null) {
                dimWindow = new View(getContext());
                wm.addView(dimWindow, p);
            } else {
                wm.updateViewLayout(dimWindow, p);
            }
        } catch (RuntimeException e) {
            AppLog.w("home dim: " + e.getMessage());
            dimWindow = null;
        }
    }

    /**
     * Back from an app (the window was hidden; not after the quick panel, which leaves it shown): the screen comes up
     * from black in a quarter of a second. Only the black layer animates, so it costs next to nothing.
     */
    private boolean shownBefore, dimFading;

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE || dimLayer == null) return;
        if (!shownBefore) {   // the very first time (the home screen just started): no fade, it would only add waiting
            shownBefore = true;
            applyDim();
            return;
        }
        // From black down to the chosen brightness in Home's own layer, then the system's dim takes over unseen.
        final float to = Prefs.launcherDim(getContext()) / 100f;
        dimFading = true;
        dimLayer.animate().cancel();
        dimLayer.setVisibility(VISIBLE);
        dimLayer.setAlpha(1);
        dimLayer.animate().alpha(to).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    dimFading = false;
                    applyDim();
                }).start();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().registerReceiver(dimReceiver, new android.content.IntentFilter(Prefs.DIM_CHANGED));
        applyDim();
    }

    @Override protected void onDetachedFromWindow() {
        if (dimWindow != null) {
            try {
                activity.getWindowManager().removeViewImmediate(dimWindow);
            } catch (RuntimeException ignored) {
            }
            dimWindow = null;
        }
        getContext().unregisterReceiver(dimReceiver);
        super.onDetachedFromWindow();
    }

    // MARK: - Wallpaper (Home Settings > Home background)

    /** A landscape or a painting behind everything (null: the plain background). A new one as often as chosen. */
    private Bitmap wallpaper;
    private String wallpaperKey = "";
    private boolean wallpaperLoading;
    private float wallpaperAlpha = 1;
    private Bitmap wallpaperBefore;
    /** A picture is chosen as the background (it may still be loading): the screen keeps the dark look meanwhile. */
    private boolean wallpaperWanted;

    private void updateWallpaper() {
        final Context ctx = getContext().getApplicationContext();
        final String kind = Prefs.homeBackground(ctx);
        boolean wanted = kind.equals(Gallery.LANDSCAPES) || kind.equals(Gallery.ART) || kind.equals(Gallery.FAVORITES);
        if (wanted != wallpaperWanted) {
            wallpaperWanted = wanted;
            checkNight(false);   // a picture is coming: the dark look at once, not after it loads
        }
        if (!wanted) {
            if (wallpaper != null) {
                wallpaper = null;
                wallpaperKey = "";
                checkNight(true);
                sky.invalidate();
            }
            return;
        }
        final long slot = System.currentTimeMillis() / (Prefs.homeBackgroundMinutes(ctx) * 60_000L) + Prefs.homeBackgroundSkip(ctx);
        final String key = kind + "|" + slot;
        if (key.equals(wallpaperKey) || wallpaperLoading) return;
        wallpaperLoading = true;
        LOADER.execute(() -> {
            Bitmap b = null;
            // The finished background (cropped, darkened) is kept on disk: back on Home after the TV closed it (an
            // input, a big app), it's read in a moment instead of being made again from the original.
            java.io.File cache = new java.io.File(ctx.getCacheDir(), "wallpaper.jpg");
            java.io.File cacheKey = new java.io.File(ctx.getCacheDir(), "wallpaper.key");
            try {
                if (cache.exists() && key.equals(new String(java.nio.file.Files.readAllBytes(cacheKey.toPath()), "UTF-8"))) {
                    b = android.graphics.BitmapFactory.decodeFile(cache.getPath());
                    if (b != null) b = Backdrop.gpu(b);
                }
            } catch (Exception ignored) {
            }
            try {
                List<Gallery.Item> items = b != null ? java.util.Collections.emptyList() : Gallery.list(ctx, kind);
                if (!items.isEmpty()) {
                    Gallery.Item item = items.get((int) (slot % items.size()));
                    java.io.File f = Gallery.image(ctx, item);
                    Gallery.setCurrent(ctx, item);
                    Bitmap[] im = Backdrop.photo(f, Backdrop.W, Backdrop.H, null);
                    im[1].recycle();
                    Bitmap made = withWallpaperScrims(im[0]);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(cache)) {
                        made.compress(Bitmap.CompressFormat.JPEG, 92, out);
                        java.nio.file.Files.write(cacheKey.toPath(), key.getBytes("UTF-8"));
                    } catch (Exception e) {
                        AppLog.w("wallpaper cache: " + e.getMessage());
                    }
                    b = Backdrop.gpu(made);
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("wallpaper: " + e.getMessage());
            }
            final Bitmap ready = b;
            handler.post(() -> {
                wallpaperLoading = false;
                if (ready == null || !kind.equals(Prefs.homeBackground(ctx))) return;
                wallpaperBefore = wallpaper;   // a new picture fades in over the old one (not over black)
                wallpaper = ready;
                wallpaperKey = key;
                checkNight(true);   // a photo always takes the night look: white text over a darkened picture
                // It fades in (a third of a second), rather than popping in.
                android.animation.ValueAnimator in = android.animation.ValueAnimator.ofFloat(0, 1);
                in.setDuration(wallpaperBefore != null ? 450 : 260);
                in.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        wallpaperBefore = null;
                        sky.invalidate();
                    }
                });
                in.addUpdateListener(a -> {
                    wallpaperAlpha = (float) a.getAnimatedValue();
                    sky.invalidate();
                });
                in.start();
            });
        });
    }

    /** Darkened a little all over, more on the left (the clock) and at the bottom (the shelf): the text stays readable. */
    private static Bitmap withWallpaperScrims(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        Canvas c = new Canvas(b);
        c.drawColor(0x40000000);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w * 0.6f, 0, new int[] {0x99000000, 0x4D000000, 0x00000000},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, h, 0, h * 0.55f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.55f, w, h, p);
        return b;
    }

    void resume() {
        if (running) return;
        running = true;
        idleFrom = Z_SHELF;   // back on Home: nothing focused (the first arrow or OK brings the shelf back)
        if (!sheetOpen && !drawerOpen) {
            zone = Z_IDLE;
            retarget(true);
        }
        applyDim();
        updateWallpaper();
        restartTick();
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (nowPlaying == null) nowPlaying = new NowPlaying(getContext(), this::onNowPlaying);
        nowPlaying.start();
        // The island's data (the TV Provider, pictures) waits until Home is back on screen: reading it during the
        // return fought the animation for the TV's CPU. Nothing is focused on return, so nothing waits for it.
        handler.postDelayed(watchStart, WATCH_START_MS);
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        android.content.IntentFilter apps = new android.content.IntentFilter();
        apps.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        apps.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
        apps.addAction(android.content.Intent.ACTION_PACKAGE_CHANGED);   // turned on or off in the TV's settings
        apps.addDataScheme("package");
        getContext().registerReceiver(appsChanged, apps);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        if (nowPlaying != null) nowPlaying.stop();
        handler.removeCallbacks(npTick);
        handler.removeCallbacks(watchStart);
        WatchNext.get(getContext()).stop();
        MediaHistory.get(getContext()).setListener(null);
        handler.removeCallbacks(watchDebounce);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        try {
            getContext().unregisterReceiver(appsChanged);
        } catch (IllegalArgumentException ignored) {
        }
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_IDLE;   // nothing focused; the first arrow or OK brings back the first app
        idleFrom = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("light|Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("HDMI Inputs"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.QUICK_SETTINGS, "settings", Ui.tr("light|Settings"), "settings"));
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        veilLayer.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        watchLayer.layout(0, 0, width, height);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        dimLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lMatch = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockMatch = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // The football line: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 21 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt) | step(playerFocus, dt) | step(avatarFocus, dt) | step(npExpand, dt) | step(ctrlFocus, dt) | step(ctrlShow, dt), cardsMoving = step(cardFocus, dt) | step(recentFocus, dt);
        boolean watchMoving = step(watchFocus, dt), watchFading = step(watchShow, dt) | step(watchActive, dt);
        if (watchSwap && watchShow[0].value < 0.03f) showWatch(watchWaiting);   // the old one faded: the new one comes in
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt) | step(futbolFocus, dt) | step(weatherFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (watchMoving || watchDirty) watchLayer.invalidate();
        if (watchFading || watchDirty || drawerMoving || sheetMoving || motionDirty) placeWatch();
        if (bgFading) sky.invalidate();
        if (drawerMoving || bgFading) updateVeil();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = watchDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || watchMoving || watchFading
                || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    /** The island's fade and rise, and the drawer's lift, as the layer's alpha and position (nothing redrawn). */
    private void placeWatch() {
        float show = Ui.clamp(watchShow[0].value), eased = show * show * (3 - 2 * show);
        float d = drawer.value, top = (1 - Ui.clamp(sheet.value)) * (1 - smoothstep(0, 0.32f, Ui.clamp(d)));
        float alpha = watch == null ? 0 : top * eased * (0.8f + 0.2f * Ui.clamp(watchActive[0].value));
        show(watchLayer, alpha, -d * SHIFT * k + (1 - eased) * 14 * k);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis()) || wallpaper != null || wallpaperWanted;
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    /**
     * The drawer's veil over the background (lighter by day, darker at night): a plain layer whose transparency
     * follows the drawer. The background itself stays as drawn (re-filtering a full-screen picture on every frame of
     * the drawer's slide made it stutter).
     */
    private View veilLayer;

    private void updateVeil() {
        float a = tone(0.32f, 0.42f) * Ui.clamp(drawer.value);
        veilLayer.setBackgroundColor(blend(0xFFFFFFFF, 0xFF000000, night));
        veilLayer.setAlpha(a);
        veilLayer.setVisibility(a > 0.002f ? VISIBLE : GONE);
    }

    private void nightChanged() {
        updateVeil();
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lMatch.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockMatch.setColor(tone(L_DIM, 0xFF98989D));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    // MARK: - Football

    private static final int LIVE_RED = 0xFFFF453A;
    private long futbolAt = -1, futbolMinute = -1;
    private float futbolSizes = -1;
    private List<Futbol.Match> futbolShown = new ArrayList<>();
    private String[] futbolLine = new String[0], futbolShort = new String[0], futbolStatus = new String[0];
    private float[] wLineClock = new float[0], wLineCard = new float[0], wShort = new float[0];
    private float[] wStatusClock = new float[0], wStatusTab = new float[0];
    private final Snapshot futbolSnapshot = new Snapshot();

    /** Text and widths for the matches, made again only when the data, the minute or the text size change. */
    private void refreshFutbolText() {
        long minute = System.currentTimeMillis() / 60_000;
        float sizes = lClockMatch.getTextSize() + lMatch.getTextSize() * 7 + cardText.getTextSize() * 13;
        if (data.matchesAt == futbolAt && minute == futbolMinute && sizes == futbolSizes) return;
        futbolAt = data.matchesAt;
        futbolMinute = minute;
        futbolSizes = sizes;
        List<Futbol.Match> ms = data.matches;
        int n = ms.size();
        long now = System.currentTimeMillis();
        String[] line = new String[n], shrt = new String[n], status = new String[n];
        float[] wlc = new float[n], wlk = new float[n], ws = new float[n], wsc = new float[n], wst = new float[n];
        for (int i = 0; i < n; i++) {
            Futbol.Match m = ms.get(i);
            boolean scored = m.live() || m.done();
            line[i] = m.home.shortName + (scored ? "  " + Futbol.score(m) + "  " : "  vs  ") + m.away.shortName;
            shrt[i] = m.home.abbr + (scored ? "  " + Futbol.score(m) + "  " : "  vs  ") + m.away.abbr;
            status[i] = Futbol.status(m, now);
            wlc[i] = lClockMatch.measureText(line[i]);
            wlk[i] = cardText.measureText(line[i]);
            ws[i] = lMatch.measureText(shrt[i]);
            wsc[i] = lClockMatch.measureText(status[i]);
            wst[i] = lMatch.measureText(status[i]);
        }
        futbolShown = ms;
        futbolLine = line;
        futbolShort = shrt;
        futbolStatus = status;
        wLineClock = wlc;
        wLineCard = wlk;
        wShort = ws;
        wStatusClock = wsc;
        wStatusTab = wst;
    }

    private void drawCrest(Canvas c, Bitmap b, float x, float mid, float size, float alpha) {
        if (b == null) return;
        float h = size * b.getHeight() / Math.max(1f, b.getWidth());
        tmp.set(x, mid - h / 2, x + size, mid + h / 2);
        bitmapPaint.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(b, null, tmp, bitmapPaint);
        bitmapPaint.setAlpha(255);
    }

    /**
     * The games under the clock (or at the right of the tab row with picks on). Two or three being played at once show
     * together, one under the other, a little smaller; four or more, three at a time, taking turns. Otherwise one game
     * at a time, taking turns slowly. No dots.
     */
    private static final long ROTATE_MS = 12_000;
    private static final int LIVE_AT_ONCE = 3;
    private final Runnable rotateFutbol = () -> {
        heroDirty = true;
        wake();
    };

    private void drawFutbolRow(Canvas c) {
        refreshFutbolText();
        int n = futbolShown.size();
        if (n == 0) return;
        boolean clock = !picksOn();
        applyTone();
        Paint text = clock ? lClockMatch : lMatch;
        float fade = 1;
        for (int i = 3; i < 6 && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        long now = System.currentTimeMillis();
        int live = 0;
        while (live < n && (futbolShown.get(live).live() || futbolShown.get(live).kickedOff(now))) live++;   // the live ones come first
        boolean stack = clock && live >= 2;
        // Games being played always win: while any is live, the others (later, finished) wait their turn.
        int turns = stack ? (live + LIVE_AT_ONCE - 1) / LIVE_AT_ONCE : live > 0 ? live : n;
        int turn = (int) ((now / ROTATE_MS) % turns);
        if (turns > 1) {
            handler.removeCallbacks(rotateFutbol);
            handler.postDelayed(rotateFutbol, ROTATE_MS - now % ROTATE_MS + 20);
        }
        int textColor = text.getColor();
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + Ui.halfCap(text);
        if (!stack) {
            drawMatchLine(c, turn, text, textColor, clock, clock ? MARGIN * k : -1, base, fade, 1);
            text.setColor(textColor);
            return;
        }
        // Live together: smaller, one per line.
        float size = text.getTextSize();
        text.setTextSize(size * 0.82f);
        float step = Ui.cap(text) * 2.1f;   // three lines still end above the shelf (the layer is 790 tall)
        for (int j = 0; j < LIVE_AT_ONCE; j++) {
            int i = turn * LIVE_AT_ONCE + j;
            if (i >= live) break;
            drawMatchLine(c, i, text, textColor, true, MARGIN * k, base + j * step, fade, 0.82f);
        }
        text.setTextSize(size);
        text.setColor(textColor);
    }

    /** One game: crest, teams and score, crest, status. x < 0: ending at the content's right edge (the tab row). */
    private void drawMatchLine(Canvas c, int i, Paint text, int textColor, boolean clock, float x, float base, float fade,
            float scale) {
        float cap = Ui.cap(text), crest = cap * 1.9f, gap = 10 * k * scale;
        float mid = base - cap / 2;
        float wLine = clock ? wLineClock[i] * scale : wShort[i], wStatus = clock ? wStatusClock[i] * scale : wStatusTab[i];
        if (x < 0) x = CONTENT_R * k - (2 * crest + 4 * gap + wLine + wStatus);
        int dim = clock ? Ui.alpha(textColor, 0.75f * fade) : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        Futbol.Match m = futbolShown.get(i);
        drawCrest(c, m.home.crest, x, mid, crest, fade);
        x += crest + gap;
        text.setColor(Ui.alpha(textColor, fade));
        c.drawText(clock ? futbolLine[i] : futbolShort[i], x, base, text);
        x += wLine + gap;
        drawCrest(c, m.away.crest, x, mid, crest, fade);
        x += crest + 3 * gap;
        text.setColor(m.live() ? Ui.alpha(LIVE_RED, fade) : dim);
        c.drawText(futbolStatus[i], x, base, text);
    }

    /**
     * The drawer card: the week's games as a calendar, live ones first, under a heading per round ("Fecha 15 · Liga
     * Profesional", "Semifinal · vuelta · Libertadores"); four rows a page, turning every few seconds. A page that
     * starts mid-round repeats its heading.
     */
    private static final int CARD_ROWS = 4;
    private static final long PAGE_MS = 6000;
    private long upcomingAt = -1, upcomingMinute = -1;
    private float upcomingWidth = -1;
    /** A page is a list of rows; a row is a heading (match == null) or a game. */
    private static final class CardRow {
        Futbol.Match match;
        String text, status;
        float width;
    }
    private List<List<CardRow>> cardPages = new ArrayList<>();

    private void refreshUpcomingText(float width) {
        long minute = System.currentTimeMillis() / 60_000;
        if (data.matchesAt == upcomingAt && minute == upcomingMinute && width == upcomingWidth) return;
        upcomingAt = data.matchesAt;
        upcomingMinute = minute;
        upcomingWidth = width;
        long now = System.currentTimeMillis();
        float crest = Ui.cap(cardText) * 1.55f;
        List<List<CardRow>> pages = new ArrayList<>();
        List<CardRow> page = new ArrayList<>();
        String group = null;
        for (Futbol.Match m : data.upcoming) {
            String g = m.live() ? "EN VIVO" : m.round;
            boolean heading = !g.equals(group) || page.isEmpty();
            if (page.size() + (heading ? 2 : 1) > CARD_ROWS) {
                pages.add(page);
                page = new ArrayList<>();
                heading = true;
            }
            if (heading) {
                CardRow h = new CardRow();
                h.text = g;
                page.add(h);
            }
            CardRow r = new CardRow();
            r.match = m;
            r.status = Futbol.status(m, now);
            boolean scored = m.live() || m.done();
            float room = width - cardDimRight.measureText(r.status) - 2 * crest - 48 * k;
            r.text = m.home.shortName + (scored ? "  " + Futbol.score(m) + "  " : "  vs  ") + m.away.shortName;
            if (cardText.measureText(r.text) > room) {
                r.text = m.home.abbr + (scored ? "  " + Futbol.score(m) + "  " : "  vs  ") + m.away.abbr;
            }
            r.width = cardText.measureText(r.text);
            page.add(r);
            group = g;
        }
        if (!page.isEmpty()) pages.add(page);
        cardPages = pages;
    }

    private void drawFutbolCard(Canvas c, RectF r) {
        drawerCard(c, r);
        refreshUpcomingText(r.width() - 68 * k);
        long now = System.currentTimeMillis();
        int pages = Math.max(1, cardPages.size());
        boolean manual = zone == Z_FUTBOL || now - cardManualAt < 20_000;
        if (!manual) cardPage = (int) ((now / PAGE_MS) % pages);   // by hand, it carries on from the page shown
        cardPage = Math.min(cardPage, pages - 1);
        int page = cardPage;
        if (pages > 1) postInvalidateDelayed(manual ? 20_000 : PAGE_MS - now % PAGE_MS + 20);
        String key = data.matchesAt + "|" + now / 60_000 + "|" + page + "|" + Float.floatToIntBits(dark()) + "|" + Ui.language;
        if (Ui.LEGACY) futbolSnapshot.draw(c, r, key, cc -> drawFutbolRows(cc, r, page, pages));
        else drawFutbolRows(c, r, page, pages);
        drawCardFocus(c, r, Ui.clamp(futbolFocus[0].value), page, pages);
    }

    private Paint cardRim;

    /**
     * One small dot per page under the list, the current one larger and brighter. Past seven pages, a window of seven
     * slides along with the page, its end dots smaller where more pages lie beyond (as on a phone's home screen).
     */
    private void drawPageDots(Canvas c, RectF r, int page, int pages, float alpha) {
        if (pages < 2) return;
        int n = Math.min(7, pages), first = Math.max(0, Math.min(pages - n, page - n / 2));
        float small = 4.5f * k, big = 7 * k, tiny = 3 * k, step = 12 * k, y = r.bottom - 18 * k;
        float x = r.centerX() - step * (n - 1) / 2f;
        fill.setShader(null);
        for (int i = 0; i < n; i++) {
            int p = first + i;
            boolean edge = (i == 0 && first > 0) || (i == n - 1 && first + n < pages);
            float size = p == page ? big : edge ? tiny : small;
            fill.setColor(Ui.alpha(p == page ? cardText.getColor() : Ui.alpha(cardDimRight.getColor(), 0.55f), alpha));
            c.drawCircle(x + i * step, y, size / 2, fill);
        }
    }

    private Paint roundHeading;

    private void drawFutbolRows(Canvas c, RectF r, int page, int pages) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        c.drawText("Próximos partidos", x, r.top + 54 * k, cardTitle);
        if (cardPages.isEmpty()) {
            c.drawText(data.matchesAt == 0 ? "Buscando partidos…" : "No hay partidos esta semana", x, r.top + 110 * k, cardDim);
            return;
        }
        if (roundHeading == null || roundHeading.getTextSize() != 19 * k) {
            roundHeading = Ui.text(Ui.semibold, 19 * k, 0xFFFFFFFF);
            roundHeading.setLetterSpacing(0.06f);
        }
        float cap = Ui.cap(cardText), crest = cap * 1.55f;
        int statusColor = cardDimRight.getColor();
        List<CardRow> rows = cardPages.get(Math.min(page, cardPages.size() - 1));
        for (int j = 0; j < rows.size(); j++) {
            CardRow row = rows.get(j);
            float base = r.top + (110 + 52 * j) * k;
            if (row.match == null) {
                roundHeading.setColor(statusColor);   // only the games' own "EN VIVO" is red
                c.drawText(row.text.toUpperCase(Ui.locale()), x, base - 6 * k, roundHeading);
                continue;
            }
            Futbol.Match m = row.match;
            float mid = base - cap / 2;
            drawCrest(c, m.home.crest, x, mid, crest, 1);
            float tx = x + crest + 12 * k;
            c.drawText(row.text, tx, base, cardText);
            drawCrest(c, m.away.crest, tx + row.width + 12 * k, mid, crest, 1);
            cardDimRight.setColor(m.live() ? LIVE_RED : statusColor);
            c.drawText(row.status, right, base, cardDimRight);
        }
        cardDimRight.setColor(statusColor);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (appMenu && reordering && liftIndex >= 0 && liftIndex < tileRects.length) drawAppMenu(c, tileRects[liftIndex], shelfOptions);
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = (1 + FOCUS_GROW * f + LIFT_GROW * l);
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        if (fc > 0.01f && it.tile != null) drawAmbilight(c, it.tile, cell, r, fc);
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else if (it.tile != null) {
            // One copy, at the focused size: at rest the GPU shrinks it by a tenth, which looks the same. Two copies
            // (resting, focused) swapped resolution visibly as the focus arrived.
            roundImage(c, cardImage(it, bw * (1 + FOCUS_GROW), bh * (1 + FOCUS_GROW)), cell, r);
        }
    }

    private final android.util.LruCache<String, Bitmap> crispTiles = new android.util.LruCache<>(32);

    /**
     * An app's card at w x h: the sharp render when its banner allows one, else its card filtered down. The sharp one
     * is made in the background (opening each app's banner took most of a second on the first frame); until it's
     * ready the card shows filtered down, then it swaps in.
     */
    private Bitmap cardImage(HomeApps.Item it, float w, float h) {
        if (it.kind == HomeApps.APP && it.pkg != null) {
            int iw = Math.round(w), ih = Math.round(h);
            Bitmap sharp = HomeApps.sharpTileCached(it.pkg, iw, ih);
            if (sharp != null) return sharp;
            requestSharp(it.pkg, iw, ih);
        }
        return it.tile == null ? null : crispTile(it.tile, w, h);   // All Apps' cards are still loading
    }

    private final java.util.Set<String> sharpAsked = new java.util.HashSet<>();

    /** Memory is short while Home is hidden: the scaled cards and the background go; they're made again on return. */
    void trimImages() {
        if (running) return;
        crispTiles.evictAll();
        WatchNext.get(getContext()).trim();
        imageShaders.clear();
        // The shelf's sharp tiles stay (half a MB): made again on return, YouTube's and Prime Video's rebuilt logos
        // showed their stock banner first, and their lettering visibly changed size as the sharp one swapped in.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (HomeApps.Item it : shelf) if (it.pkg != null) keep.add(it.pkg);
        HomeApps.trimSharpExcept(keep);
        java.util.Iterator<String> asked = sharpAsked.iterator();
        while (asked.hasNext()) {
            String key = asked.next();
            int at = key.indexOf('@');
            if (at < 0 || !keep.contains(key.substring(0, at))) asked.remove();
        }
        Symbols.trim();
        wallpaper = null;    // kept on disk, ready: read again in a moment when Home comes back
        wallpaperKey = "";
    }

    private void requestSharp(final String pkg, final int w, final int h) {
        if (!sharpAsked.add(pkg + "@" + w + "x" + h)) return;   // asked once: made, or it has no sharp version
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            if (HomeApps.sharpTile(c, pkg, w, h) == null) return;
            handler.post(() -> {
                tilesDirty = gridDirty = true;
                wake();
            });
        });
    }
    private final Map<Bitmap, Integer> ambilightColors = new WeakHashMap<>();
    private Paint ambilight;

    /**
     * Ambilight: the focused app's colour glowing out from behind its tile, like the lights on the back of a TV. The
     * colour is the most vivid one in its banner (Netflix's red on its white card, Disney+'s teal).
     */
    private void drawAmbilight(Canvas c, Bitmap tile, RectF cell, float r, float f) {
        Integer color = ambilightColors.get(tile);
        if (color == null) {
            color = vivid(tile);
            ambilightColors.put(tile, color);
        }
        float blur = 38 * k;
        if (ambilight == null || ambilightBlur != blur) {
            ambilight = new Paint(Paint.ANTI_ALIAS_FLAG);
            ambilight.setMaskFilter(new android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL));
            ambilightBlur = blur;
        }
        ambilight.setColor(Ui.alpha(color, tone(0.3f, 0.45f) * f));   // a hint of light, not a halo
        tmp.set(cell);
        tmp.inset(-8 * k, -4 * k);
        tmp.offset(0, 6 * k);
        shadows.draw(c, tmp, r + 10 * k, blur, ambilight);
    }

    private float ambilightBlur;

    /** The banner's most saturated light, brightened enough to glow; a grey banner glows soft white. */
    private static int vivid(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 32, 18, true);
        int[] px = new int[32 * 18];
        s.getPixels(px, 0, 32, 0, 0, 32, 18);
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, w = 0;
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            android.graphics.Color.colorToHSV(p, hsv);
            double weight = Math.pow(hsv[1], 3) * hsv[2] + 1e-4;
            r += ((p >> 16) & 255) * weight;
            g += ((p >> 8) & 255) * weight;
            bl += (p & 255) * weight;
            w += weight;
        }
        int c = w == 0 ? 0xFFFFFFFF : android.graphics.Color.rgb((int) (r / w), (int) (g / w), (int) (bl / w));
        android.graphics.Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.2f) return 0xFFE8ECF4;
        hsv[1] = Math.max(hsv[1], 0.75f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** `src` filtered down to w x h (halving first, no aliasing); kept, since cards redraw on every focus frame. */
    private Bitmap crispTile(Bitmap src, float w, float h) {
        int iw = Math.round(w), ih = Math.round(h);
        if (iw <= 0 || ih <= 0 || iw >= src.getWidth()) return src;
        String key = System.identityHashCode(src) + "@" + iw + "x" + ih;
        Bitmap b = crispTiles.get(key);
        if (b == null) {
            b = downscale(src, iw, ih);
            crispTiles.put(key, b);
        }
        return b;
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 8 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        // Between the two copies, while the focus grows or shrinks, they cross-fade: swapping at the midpoint made
        // thin lettering (YouTube's, Prime Video's) visibly jump.
        float t = smoothstep(0.3f, 0.7f, (s - 1) / FOCUS_GROW);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        if (t < 0.999f) drawLogoCopy(c, it, box, s, mode, true, 1 - t);
        if (t > 0.001f) drawLogoCopy(c, it, box, s, mode, false, t);
        bitmapPaint.setColorFilter(null);
        bitmapPaint.setAlpha(255);
    }

    private void drawLogoCopy(Canvas c, HomeApps.Item it, RectF box, float s, int mode, boolean rest, float alpha) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(logo, null, tmp, bitmapPaint);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.futbol();
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        futbolCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        drawerGlass(c, r, radius, lightTint, shadowColor, drop, 1);
    }

    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop, float alpha) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, (1 - dk) * alpha));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, (1 - dk) * alpha);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk * alpha);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.futbol()) drawFutbolCard(c, futbolCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        int page = weatherPage;
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey() + "|" + page, cc -> {
            if (page == 0) drawWeather(cc, r);
            else drawWeek(cc, r);
        });
        else if (page == 0) drawWeather(c, r);
        else drawWeek(c, r);
        drawCardFocus(c, r, Ui.clamp(weatherFocus[0].value), page, 2);
    }

    /** The next seven days: day, symbol, high and low, in seven columns. */
    private void drawWeek(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle + " · 7 días", x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        int n = Math.min(7, wx.days.size());
        if (n == 0) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        float cw = (r.width() - 68 * k) / 7;
        for (int i = 0; i < n; i++) {
            float cx = x + cw * (i + 0.5f);
            c.drawText(dayText[i], cx, r.top + 104 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), cx, r.top + 152 * k, 32 * k, drawerInk(0.9f));
            c.drawText(dayMaxText[i], cx, r.top + 206 * k, hourTemp);
            c.drawText(dayMinText[i], cx, r.top + 246 * k, hourPaint);
        }
    }

    /** A focused drawer card: a white rim, and its page dots (dots only while it has focus). */
    /**
     * The drawer's cards: the arrows move between them; OK on one turns its pages with ← → (its page dots brighten), and
     * OK again or Back goes back to moving between the cards.
     */
    private boolean cardEngaged;

    private void drawCardFocus(Canvas c, RectF r, float f, int page, int pages) {
        if (f <= 0.01f) return;
        if (cardRim == null) {
            cardRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardRim.setStyle(Paint.Style.STROKE);
        }
        cardRim.setStrokeWidth(3 * k);
        cardRim.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * f));
        float h = 1.5f * k;
        c.drawRoundRect(r.left + h, r.top + h, r.right - h, r.bottom - h, 30 * k - h, 30 * k - h, cardRim);
        // On focus too, softer, so it shows there are more pages; brighter once OK lets the arrows turn them.
        drawPageDots(c, r, page, pages, f * (cardEngaged ? 1 : 0.55f));
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            for (int i = 0; i < wx.days.size() && i < 7; i++) {
                String day = Ui.datePattern("EEE").format(wx.days.get(i)).replace(".", "");
                dayText[i] = i == 0 ? "Hoy" : Character.toUpperCase(day.charAt(0)) + day.substring(1);
                dayMaxText[i] = HomeData.degrees(wx.dayMax.get(i));
                dayMinText[i] = HomeData.degrees(wx.dayMin.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot();

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        drawerChanged |= aim(futbolFocus, !sheetOpen && zone == Z_FUTBOL ? 0 : -1, snap);
        drawerChanged |= aim(weatherFocus, !sheetOpen && zone == Z_WEATHER ? 0 : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(recentFocus, !sheetOpen && zone == Z_RECENT ? recentIndex : -1, snap);
        heroChanged |= aim(watchFocus, !sheetOpen && zone == Z_WATCH ? watchIndex : -1, snap);
        heroChanged |= aim(watchActive, !sheetOpen && zone == Z_WATCH ? 0 : -1, snap);
        followWatch();
        statusChanged |= aim(playerFocus, !sheetOpen && zone == Z_PLAYER ? 0 : -1, snap);
        statusChanged |= aim(avatarFocus, !sheetOpen && zone == Z_AVATAR ? 0 : -1, snap);
        statusChanged |= aim(ctrlFocus, !sheetOpen && zone == Z_PLAYER_CTRL ? ctrlIndex : -1, snap);
        float show = !sheetOpen && !npCollapsed && (zone == Z_PLAYER || zone == Z_PLAYER_CTRL) ? 1 : 0;
        if (ctrlShow[0].target != show) {
            ctrlShow[0].target = show;
            statusChanged = true;
        }
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        final List<HomeApps.Item> list = others;
        final Context ctx = getContext().getApplicationContext();
        final boolean choosing = replacing != null;
        // The installed apps are read again on their own thread (not behind the backgrounds and logos on LOADER):
        // an app just installed or turned on shows, and the grid never waits on other work to fill.
        new Thread(() -> {
            List<HomeApps.Item> fresh = list;
            if (!choosing) {
                try {
                    fresh = HomeApps.others(ctx);
                    AppLog.w("apps: " + fresh.size() + " in All Apps");
                } catch (RuntimeException e) {
                    AppLog.w("apps: couldn't list them: " + e);
                }
            }
            HomeApps.loadTiles(ctx, fresh);
            final List<HomeApps.Item> ready = fresh;
            handler.post(() -> {
                if (sheetOpen && ready != list && replacing == null && !gridMove && !gridMenu) setOthers(ready);
                gridDirty = true;
                wake();
            });
        }, "all-apps").start();
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        if (replacing != null) {   // left without choosing: All Apps gets back its whole list
            replacing = null;
            reload();
        }
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        // Once it has slid away, its cards go (they're loaded again next time it opens).
        handler.removeCallbacks(dropGrid);
        handler.postDelayed(dropGrid, 1500);
    }

    private final Runnable dropGrid = () -> {
        if (sheetOpen) return;
        final List<HomeApps.Item> list = others;
        final Context ctx = getContext().getApplicationContext();
        crispTiles.evictAll();
        imageShaders.clear();
        LOADER.execute(() -> HomeApps.dropTiles(ctx, list));
        tilesDirty = true;   // the shelf's copies are made again (from tiles still kept)
        wake();
    };

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(replacing != null ? "Elegí la app para el estante" : Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            // One copy at the focused size (see the shelf): no change of resolution as the focus moves.
            float lift = gridMove && i == gridIndex ? 0.16f : 0.08f;   // being moved: lifted a little higher
            drawCard(c, cardImage(others.get(i), GRID_W * k * 1.08f, GRID_H * k * 1.08f), gridCell, GRID_RADIUS * k, f, lift);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
        if (gridMenu && sheetOpen) drawAppMenu(c, focusedCell, gridOptions);
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) {
            roundImage(c, image, cell, r);   // one copy at the focused size: no change of resolution with focus
        } else {
            fill.setShader(null);
            fill.setColor(0x26FFFFFF);   // still loading: a faint card where it will be
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    /** Any notice on screen or waiting goes (another screen takes its place). */
    private void clearToasts() {
        toasts.clear();
        if (toast != null) {
            toast = null;
            toastLayer.invalidate();
        }
    }

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k;
        float tw = toast.getWidth(), th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        c.translate(tmp.left + padX, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            try {
                final List<HomeApps.Item> s = HomeApps.shelf(c, true);
                final List<HomeApps.Item> st = statusList(c);
                final List<HomeApps.Item> d = drawerList(c);
                final List<HomeApps.Item> o = HomeApps.others(c);
                AppLog.w("apps: " + s.size() + " on the shelf, " + o.size() + " in All Apps");
                handler.post(() -> {
                    setShelf(s);
                    setStatusItems(st);
                    setDrawerItems(d);
                    if (!sheetOpen) setOthers(o);
                });
            } catch (RuntimeException e) {
                AppLog.w("apps: couldn't list them: " + e);
            }
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (gridMove) return gridMoveKey(e, down);
        if (gridMenu) return gridMenuKey(e, down);
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held on a shelf app (~0.5 s): move mode. A timer, since not every remote repeats a held key, or the
                // long-press repeat, whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canReorder() || (sheetOpen && gridIndex < others.size());
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        holdAction();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (cardEngaged && (zone == Z_FUTBOL || zone == Z_WEATHER)) {
            cardEngaged = false;   // out of the card's pages, still on the card
            drawerDirty = true;
            click.move();
            return;
        }
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS || zone == Z_FUTBOL || zone == Z_WEATHER) {
            closeDrawer();
            retarget(false);
        } else if (zone == Z_IDLE) {
            return;
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            idleFrom = Z_SHELF;   // Back on the shelf: nothing focused
            zone = Z_IDLE;
            retarget(false);
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case Z_FUTBOL: {
                if (dy < 0) {
                    zone = Z_DRAWER;
                    cardEngaged = false;
                } else if (dx < 0 && !cardEngaged && Prefs.weather()) {
                    zone = Z_WEATHER;   // ← : the weather card (OK first to turn this one's pages)
                    drawerDirty = true;
                } else if (dx != 0 && cardEngaged) {
                    int pages = Math.max(1, cardPages.size()), next = Math.max(0, Math.min(pages - 1, cardPage + dx));
                    if (next == cardPage) return;
                    cardPage = next;
                    cardManualAt = System.currentTimeMillis();
                    drawerDirty = true;
                } else {
                    return;
                }
                break;
            }
            case Z_WEATHER: {
                if (dy < 0) {
                    zone = Z_DRAWER;
                    cardEngaged = false;
                } else if (dx > 0 && !cardEngaged) {
                    if (!Prefs.futbol()) return;
                    zone = Z_FUTBOL;   // → : the football card (OK first to turn this one's pages)
                } else if (dx != 0 && cardEngaged) {
                    int next = Math.max(0, Math.min(1, weatherPage + dx));
                    if (next == weatherPage) return;
                    weatherPage = next;
                } else {
                    return;
                }
                drawerDirty = true;
                break;
            }
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if ((Prefs.futbol() || Prefs.weather()) && !talkBack()) {
                    // Down onto the card under the focused button.
                    boolean weatherSide = Prefs.weather() && (!Prefs.futbol()
                            || drawerRects[drawerIndex].centerX() < futbolCard.left);
                    zone = weatherSide ? Z_WEATHER : Z_FUTBOL;
                    cardEngaged = false;
                    if (weatherSide) weatherPage = 0;
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (zone == Z_IDLE) {   // nothing focused: OK brings the focus back
            zone = idleFrom;
            retarget(false);
            return;
        }
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (!sheetOpen && (zone == Z_FUTBOL || zone == Z_WEATHER)) {
            cardEngaged = !cardEngaged;
            drawerDirty = true;
            click.open();
            return;
        }
        if (sheetOpen && replacing != null) {
            if (gridIndex < others.size()) finishReplace(others.get(gridIndex).pkg);
            click.open();
            return;
        }
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_AVATAR) {
            click.open();
            try {
                getContext().startActivity(RemoteKeys.accounts(getContext()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                getContext().startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return;
        } else if (zone == Z_PLAYER) {
            npCollapsed = !npCollapsed;
            ctrlShow[0].target = npCollapsed ? 0 : 1;
            npExpand[0].target = npCollapsed ? 0 : 1;
            statusDirty = true;
            click.open();
            return;
        } else if (zone == Z_PLAYER_CTRL) {
            if (nowPlaying != null && ctrlIndex < ctrlKinds.length) {
                switch (ctrlKinds[ctrlIndex]) {
                    case C_PREV: nowPlaying.previous(); break;
                    case C_PLAY: nowPlaying.toggle(); break;
                    case C_NEXT: nowPlaying.next(); break;
                    default: nowPlaying.stopPlayback(); break;
                }
            }
            click.open();
            return;
        } else if (zone == Z_WATCH) {
            if (watch == null || watchIndex >= watch.items.size()) return;
            WatchNext.Entry e = watch.items.get(watchIndex);
            String label = shelfLabel(e.pkg);
            it = e.uri != null ? HomeApps.app(e.pkg, label, e.uri) : HomeApps.app(e.pkg, label);
            r = watchRects[watchIndex];
        } else if (zone == Z_RECENT) {
            if (recentIndex >= recent.size()) return;
            it = recent.get(recentIndex);
            r = recentRects[recentIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        final HomeApps.Item item = it;
        final RectF from = new RectF(r);
        if (item.kind != HomeApps.QUICK_SETTINGS) dimInHome();   // the panel leaves Home in view: nothing to hand over
        boolean opened = HomeApps.open(activity, item, this, Math.round(from.left), Math.round(from.top),
                Math.round(from.width()), Math.round(from.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", item.label));
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast("← → mover · ↓ opciones · OK soltar · Atrás cancelar");
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!down) return true;
                if (appMenu) {
                    menuIndex = Math.max(0, Math.min(shelfOptions.length - 1, menuIndex + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    tilesDirty = true;
                    click.move();
                } else {
                    shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && !appMenu) {
                    shelfOptions = optionsFor(shelf.get(shelfIndex).pkg, SHELF_ALL);
                    appMenu = true;   // ↓ while moving: the app's options
                    menuIndex = 0;
                    tilesDirty = true;
                    click.move();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && appMenu) {
                    appMenu = false;
                    tilesDirty = true;
                    click.move();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it
                // (or runs the chosen option).
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    String pkg = shelf.get(shelfIndex).pkg;
                    boolean menu = appMenu;
                    appMenu = false;
                    dropReorder();
                    if (menu) appAction(shelfOptions[menuIndex], pkg);
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        if (appMenu) {
                            appMenu = false;
                            tilesDirty = true;
                        } else {
                            cancelReorder(false);
                        }
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        appMenu = false;
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and football (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.futbol()) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : futbolText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : futbolCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 football), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.futbol()) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3 && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The football card as TalkBack reads it: each game with its round and when. */
    private String futbolText() {
        StringBuilder s = new StringBuilder("Próximos partidos.");
        long now = System.currentTimeMillis();
        for (Futbol.Match m : data.upcoming) {
            s.append(' ').append(m.home.name).append(m.live() || m.done() ? " " + Futbol.score(m) + ", " : " contra ")
                    .append(m.away.name).append(", ").append(m.round).append(", ").append(Futbol.status(m, now)).append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        if (SHOW_AVATAR) {
            avatarRect.set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        for (int i = 2; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        // "Seguir en…": three 16:9 cards in the empty right half, level with the date and the clock.
        float rw = RECENT_W * k, rh = rw * 9f / 16, rg = RECENT_GAP * k;
        float rx = CONTENT_R * k - RECENT_MAX * rw - (RECENT_MAX - 1) * rg;
        for (int i = 0; i < RECENT_MAX; i++) {
            recentRects[i].set(rx + i * (rw + rg), RECENT_TOP * k, rx + i * (rw + rg) + rw, RECENT_TOP * k + rh);
        }
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private Paint grain;

    /**
     * A fine grain (±1 level, random black and white specks at 2% at most) over the background: it breaks the steps of
     * the dark gradients and of the small blurred photo scaled up to the screen, which a big 4K panel makes visible as
     * bands. Too faint to see as noise at TV distance.
     */
    private void drawGrain(Canvas c) {
        if (grain == null) {
            int n = 128;
            int[] px = new int[n * n];
            java.util.Random r = new java.util.Random(20261022);
            for (int i = 0; i < px.length; i++) {
                int a = r.nextInt(6);
                px[i] = r.nextBoolean() ? (a << 24) | 0xFFFFFF : a << 24;
            }
            Bitmap tile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
            grain = new Paint();
            grain.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        c.drawRect(screen, grain);
    }

    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // The drawer's veil is its own layer above (updateVeil): the background is drawn as it is.
        android.graphics.ColorFilter v = null;
        if (bgTo == null || (fading && bgFrom == null)) {
            // Flat, not a gradient: a dark gradient has only ~17 grays to spread over 1080 lines, and a 65" 4K panel
            // shows the steps as bands even dithered. One tone can't band.
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFEDEDF0, 0xFFEDEDF0, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF000000, 0xFF000000, Shader.TileMode.CLAMP);   // OLED: black
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            fill.setDither(true);   // 17 grays over 1080 lines: undithered, a 65" panel shows them as bands
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
            fill.setDither(false);
            if (wallpaper != null) {
                imagePaint.setColorFilter(v);
                if (wallpaperBefore != null && wallpaperAlpha < 1) {
                    imagePaint.setAlpha(255);
                    c.drawBitmap(wallpaperBefore, null, screen, imagePaint);
                }
                imagePaint.setAlpha(Math.round(255 * wallpaperAlpha));
                c.drawBitmap(wallpaper, null, screen, imagePaint);
                imagePaint.setAlpha(255);
            }
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
        if (bgTo != null || bgFrom != null) drawGrain(c);   // only a photo background has steps left to break up
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (Prefs.futbol()) drawFutbolRow(c);
            return;
        }
        String hello = Ui.tr("For You");
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < 3; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (Prefs.futbol()) drawFutbolRow(c);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    // MARK: - "Seguir en…" (recent apps, right half, when there are no TMDB picks)

    private long recentMinute = -1;
    private Paint recentName, recentWhen;
    private String[] recentAgo = new String[0];

    /** Off: apps can't share what was being watched, and a row that only reopens apps added nothing. */
    private static final boolean RECENT_ROW = false;

    private boolean recentShown() {
        return RECENT_ROW && !picksOn() && !recent.isEmpty();
    }

    private void loadRecent() {
        final Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            List<HomeApps.Item> list = HomeApps.recent(ctx, RECENT_MAX);
            handler.post(() -> {
                recent = list;
                recentIndex = Math.min(recentIndex, Math.max(0, list.size() - 1));
                recentMinute = -1;
                if (zone == Z_RECENT && list.isEmpty()) zone = Z_SHELF;
                heroDirty = true;
                retarget(true);
                wake();
            });
        }, "recent").start();
    }

    /** "hace 5 min", "hace 2 h", "ayer", "hace 3 días": made again once a minute. */
    private void refreshRecentAgo() {
        long minute = System.currentTimeMillis() / 60_000;
        if (minute == recentMinute && recentAgo.length == recent.size()) return;
        recentMinute = minute;
        String[] out = new String[recent.size()];
        for (int i = 0; i < out.length; i++) {
            long m = Math.max(0, (System.currentTimeMillis() - recent.get(i).lastUsed) / 60_000);
            out[i] = m < 1 ? "recién" : m < 60 ? "hace " + m + " min" : m < 24 * 60 ? "hace " + (m / 60) + " h"
                    : m < 48 * 60 ? "ayer" : "hace " + (m / (24 * 60)) + " días";
        }
        recentAgo = out;
    }

    private void drawRecent(Canvas c) {
        if (!recentShown()) return;
        refreshRecentAgo();
        String title = "Seguir en…";
        c.drawText(title, recentRects[0].left - Ui.leftBearing(lDate, title), L_DATE_BASE * k, lDate);
        for (int i = 0; i < recent.size() && i < RECENT_MAX; i++) {
            float f = recentFocus[i].value;
            RectF r = recentRects[i];
            drawCard(c, recent.get(i).tile, r, 14 * k, f, 0.08f);
            float lift = 5 * k * Ui.clamp(f);
            if (recentName == null || recentName.getTextSize() != 22 * k) {
                recentName = Ui.text(Ui.medium, 22 * k, L_INK);
                recentWhen = Ui.text(Ui.regular, 18 * k, L_DIM);
            }
            recentName.setColor(tone(L_INK, 0xF2FFFFFF));
            recentWhen.setColor(tone(L_DIM, 0x99FFFFFF));
            c.drawText(recent.get(i).label, r.left, r.bottom + 32 * k + lift, recentName);
            c.drawText(recentAgo[i], r.left, r.bottom + 56 * k + lift, recentWhen);
        }
    }

    // MARK: - "Continuar viendo" (centre right, for the focused app of the shelf)

    private static final long WATCH_DEBOUNCE_MS = 220;
    private static final float WATCH_PAD = 22, WATCH_HEADER = 38, WATCH_TOP = 54;
    // Every app gets the same island: up to three small cards (the latest three to resume, seen or heard).
    private static final float WATCH_COL = 188, WATCH_COL_GAP = 16, WATCH_THUMB_H = 106;
    /** Vertical centre: midway between the buttons at the top and the shelf. */
    private static final float WATCH_CY = 540;
    private WatchNext.Content watch, watchWaiting;
    private boolean watchSwap;
    /** The app it follows: the focused shelf app (kept while the focus is up in the widget or the top row). */
    private String watchPkg;
    private final Spring[] watchShow = {new Spring(0.3f, 0.92f, 0)};
    private final Spring[] watchFocus = {new Spring(0.32f, 0.8f, 0), new Spring(0.32f, 0.8f, 0), new Spring(0.32f, 0.8f, 0)};
    /** Dimmed a little while the focus is elsewhere, so it never competes with the shelf. */
    private final Spring[] watchActive = {new Spring(0.3f, 0.9f, 0)};
    private final RectF watchCard = new RectF();
    private final RectF[] watchRects = {new RectF(), new RectF(), new RectF()};
    private int watchIndex;
    private final String[] watchTitles = new String[3], watchSubs = new String[3];
    /** How much of each card's title was seen (0: no bar), in the app's colour. */
    private final float[] watchProgress = new float[3];
    private int watchColor;
    private TextPaint watchHead, watchTitle, watchSub;
    private Paint watchRim;
    private boolean watchDirty;
    private static final long WATCH_START_MS = 1200;
    private final Runnable watchStart = () -> {
        if (!running) return;
        MediaHistory.get(getContext()).start();
        MediaHistory.get(getContext()).setListener(this::refreshWatch);
        if (Prefs.watchNext()) WatchNext.get(getContext()).start(this::refreshWatch);
        else pickWatch();   // turned off in Home Settings: it goes
    };
    private final Runnable watchDebounce = this::pickWatch;
    private final Runnable watchImageReady = () -> {
        if (watch != null) loadWatchImages(watch);
        watchDirty = true;
        wake();
    };

    private boolean watchShown() {
        return !picksOn() && watch != null && !watchSwap && watchShow[0].target > 0.5f;
    }

    /** Called on every focus change: which app the widget should follow now, picked 220 ms later (no flicker). */
    private void followWatch() {
        String pkg;
        if (sheetOpen || zone == Z_DRAWER || zone == Z_FUTBOL || zone == Z_WEATHER || zone == Z_IDLE) {
            pkg = null;
        } else if (zone == Z_SHELF) {
            HomeApps.Item it = shelfIndex < shelf.size() ? shelf.get(shelfIndex) : null;
            pkg = it != null && it.kind == HomeApps.APP ? it.pkg : null;
        } else {
            return;   // up in the widget or the top row: it stays as it is
        }
        if (pkg == null ? watchPkg == null : pkg.equals(watchPkg)) return;
        watchPkg = pkg;
        handler.removeCallbacks(watchDebounce);
        if (pkg == null) pickWatch();
        else handler.postDelayed(watchDebounce, WATCH_DEBOUNCE_MS);
    }

    /** New data (the TV Provider changed, something was saved to the history, a picture arrived). */
    private void refreshWatch() {
        if (watchPkg != null) {
            handler.removeCallbacks(watchDebounce);
            pickWatch();
        }
        if (!Prefs.watchNext()) return;
        // Pictures fetched ahead for the other shelf apps: moving to one shows it at once.
        WatchNext wn = WatchNext.get(getContext());
        for (HomeApps.Item it : shelf) {
            if (it.kind != HomeApps.APP || it.pkg == null || it.pkg.equals(watchPkg)) continue;
            WatchNext.Content c = wn.content(it.pkg, it.label);
            if (c != null) loadWatchImages(c);
        }
    }

    private void pickWatch() {
        WatchNext.Content next = picksOn() || watchPkg == null || !Prefs.watchNext() ? null
                : WatchNext.get(getContext()).content(watchPkg, shelfLabel(watchPkg));
        if (next != null) loadWatchImages(next);
        WatchNext.Content now = watchSwap ? watchWaiting : watch;
        if (next != null && now != null && next.key().equals(now.key())) {   // the same: only its pictures may be new
            if (watchSwap) watchWaiting = next;
            else {
                watch = next;
                layoutWatch();
            }
            return;
        }
        if (next == null && now == null) return;
        if (watch == null || watchShow[0].value < 0.03f) {
            showWatch(next);
        } else {
            watchWaiting = next;   // the one on screen fades out first
            watchSwap = true;
            watchShow[0].target = 0;
        }
        watchDirty = true;
        wake();
    }

    private void showWatch(WatchNext.Content c) {
        watchSwap = false;
        watchWaiting = null;
        watch = c;
        watchIndex = 0;
        if (c == null) {
            watchShow[0].target = 0;
            if (zone == Z_WATCH) {
                zone = Z_SHELF;
                retarget(false);
            }
        } else {
            layoutWatch();
            watchShow[0].target = 1;
        }
        watchDirty = true;
        wake();
    }

    private String shelfLabel(String pkg) {
        for (HomeApps.Item it : shelf) if (pkg.equals(it.pkg)) return it.label;
        return pkg;
    }

    private void loadWatchImages(WatchNext.Content c) {
        int[] size = watchImageSize(c);
        WatchNext wn = WatchNext.get(getContext());
        for (WatchNext.Entry e : c.items) {
            if (e.bitmap == null) e.bitmap = wn.image(e, size[0], size[1], watchImageReady);
        }
    }

    private int[] watchImageSize(WatchNext.Content c) {
        int t = Math.round(WATCH_THUMB_H * k);
        return c.variant == WatchNext.ROW_WIDE ? new int[] {Math.round(WATCH_COL * k), t} : new int[] {t, t};
    }

    /** Where everything goes, and the text cut to fit, done once per content (never while drawing). */
    private void layoutWatch() {
        WatchNext.Content c = watch;
        if (c == null) return;
        if (watchHead == null || watchHead.getTextSize() != 18 * k) {
            watchHead = new TextPaint(Ui.text(Ui.semibold, 18 * k, 0xB3FFFFFF));
            watchHead.setLetterSpacing(0.08f);
            watchTitle = new TextPaint(Ui.text(Ui.semibold, 20 * k, 0xF2FFFFFF));
            watchSub = new TextPaint(Ui.text(Ui.medium, 17 * k, 0xADFFFFFF));
        }
        float right = CONTENT_R * k, pad = WATCH_PAD * k, col = WATCH_COL * k, gap = WATCH_COL_GAP * k;
        // Always three columns wide, however many cards: every app's island is the same size.
        float w = Math.max(3 * col + 2 * gap, watchHead.measureText(c.header)) + 2 * pad;
        float h = (WATCH_TOP + WATCH_THUMB_H + 86) * k, t = WATCH_THUMB_H * k;
        watchCard.set(right - w, WATCH_CY * k - h / 2, right, WATCH_CY * k + h / 2);
        int brand = HomeApps.brandColor(c.pkg);
        watchColor = brand != 0 ? brand : 0xFFFFFFFF;
        for (int i = 0; i < Math.min(3, c.items.size()); i++) {
            float x = watchCard.left + pad + i * (col + gap), top = watchCard.top + WATCH_TOP * k;
            if (c.variant == WatchNext.ROW_WIDE) watchRects[i].set(x, top, x + col, top + t);
            else watchRects[i].set(x + (col - t) / 2, top, x + (col + t) / 2, top + t);   // centred, its text too
            WatchNext.Entry e = c.items.get(i);
            boolean seen = e.duration > 0 && e.position > 0;
            watchProgress[i] = seen ? Ui.clamp(e.position / (float) e.duration) : 0;
            String sub = e.sub;
            if (sub.isEmpty() && seen) {   // a film: the time left instead of an episode
                long left = (e.duration - e.position) / 60_000;
                sub = left < 1 ? "Queda menos de 1 min" : left < 60 ? "Quedan " + left + " min" : "Quedan " + (left / 60) + " h " + (left % 60) + " min";
            }
            watchTitles[i] = TextUtils.ellipsize(e.title, watchTitle, col, TextUtils.TruncateAt.END).toString();
            watchSubs[i] = TextUtils.ellipsize(sub, watchSub, col, TextUtils.TruncateAt.END).toString();
        }
        watchDirty = true;
    }

    private void drawWatch(Canvas c) {
        WatchNext.Content wc = watch;
        if (wc == null) return;
        float eased = 1, a = 1;   // the layer's alpha fades it (placeWatch)
        c.save();
        drawerGlass(c, watchCard, 30 * k, 0.72f, 0x1A000000, 10 * k, 1);
        watchHead.setColor(Ui.alpha(tone(L_DIM, 0xB3FFFFFF), a));
        c.drawText(wc.header, watchCard.left + WATCH_PAD * k, watchCard.top + WATCH_HEADER * k, watchHead);
        int ink = tone(L_INK, 0xFFFFFFFF), dim = tone(L_DIM, 0xADFFFFFF);
        boolean round = wc.variant == WatchNext.ROW_ROUND;
        for (int i = 0; i < Math.min(3, wc.items.size()); i++) {
            RectF r = watchRects[i];
            float f = Ui.clamp(watchFocus[i].value), s = 1 + 0.06f * f;
            cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2 - 3 * k * f,
                    r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2 - 3 * k * f);
            float rad = round ? cell.width() / 2 : 12 * k * s;
            drawWatchImage(c, wc.items.get(i), cell, rad, a);
            if (watchProgress[i] > 0) {   // a thin bar along the picture's foot, in the app's colour
                float in = 9 * k * s, bh = 4 * k, y = cell.bottom - in;
                fill.setShader(null);
                fill.setColor(Ui.alpha(0x8C000000, a));
                tmp.set(cell.left + in, y - bh, cell.right - in, y);
                c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
                fill.setColor(Ui.alpha(watchColor, a));
                tmp.right = tmp.left + Math.max(bh, (cell.width() - 2 * in) * watchProgress[i]);
                c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            }
            if (f > 0.01f) {
                if (watchRim == null) {
                    watchRim = new Paint(Paint.ANTI_ALIAS_FLAG);
                    watchRim.setStyle(Paint.Style.STROKE);
                }
                watchRim.setStrokeWidth(3 * k);
                watchRim.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * f * eased));
                float h = 1.5f * k;
                c.drawRoundRect(cell.left + h, cell.top + h, cell.right - h, cell.bottom - h, rad - h, rad - h, watchRim);
            }
            float tx = watchCard.left + WATCH_PAD * k + i * (WATCH_COL + WATCH_COL_GAP) * k;
            Paint.Align align = Paint.Align.LEFT;
            if (wc.variant != WatchNext.ROW_WIDE) {   // square covers sit centred: their text centres under them
                tx += WATCH_COL * k / 2;
                align = Paint.Align.CENTER;
            }
            float base = r.bottom + 34 * k;
            watchTitle.setTextAlign(align);
            watchSub.setTextAlign(align);
            watchTitle.setColor(Ui.alpha(ink, (0.85f + 0.15f * f) * a));
            c.drawText(watchTitles[i], tx, base, watchTitle);
            watchSub.setColor(Ui.alpha(dim, a));
            c.drawText(watchSubs[i], tx, base + 26 * k, watchSub);
            watchTitle.setTextAlign(Paint.Align.LEFT);
            watchSub.setTextAlign(Paint.Align.LEFT);
        }
        c.restore();
    }

    private void drawWatchImage(Canvas c, WatchNext.Entry e, RectF r, float rad, float a) {
        if (e.bitmap != null) {
            int alpha = Math.round(255 * a);
            bitmapPaint.setAlpha(alpha);
            imageFill.setAlpha(alpha);
            roundImage(c, e.bitmap, r, rad);
            bitmapPaint.setAlpha(255);
            imageFill.setAlpha(255);
        } else {
            fill.setShader(null);
            fill.setColor(Ui.alpha(tone(0x14000000, 0x24FFFFFF), a));
            c.drawRoundRect(r, rad, rad, fill);
            // No picture (YouTube sometimes sends none): a play sign, so it reads as a video rather than a hole.
            Symbols.draw(getContext(), c, "play", r.centerX(), r.centerY(), Math.min(r.height() * 0.32f, 34 * k),
                    Ui.alpha(tone(L_DIM, 0xB3FFFFFF), a));
        }
    }

    // MARK: - Now playing (top right, left of the round buttons)

    private NowPlaying nowPlaying;
    private TextPaint npTitle, npArtist;
    private int npVersion = -1;
    private float npWidth = -1;
    private CharSequence npTitleText = "", npArtistText = "";
    private final RectF npRect = new RectF();
    /** 1 = the full capsule (playing), 0 = collapsed to a round cover (paused). */
    private final Spring[] npExpand = {new Spring(0.3f, 0.82f, 1)};
    private static final float[] EQ_SPEED = {5.3f, 7.1f, 4.4f}, EQ_PHASE = {0f, 1.7f, 0.9f};

    /**
     * A new song or play/pause: the buttons' layer is drawn again; while a song plays, ~12 times a second for the
     * equalizer (only that layer, and only while Home is on screen).
     */
    private static final long EQ_FRAME = 83;
    private final Runnable npTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            boolean eq = nowPlaying != null && nowPlaying.playing && nowPlaying.visible();
            if (nowPlaying != null) {
                statusDirty = true;
                wake();
            }
            if (eq) handler.postDelayed(this, EQ_FRAME);
        }
    };

    private void restartTick() {
        handler.removeCallbacks(npTick);
        handler.post(npTick);
    }

    private void onNowPlaying() {
        npExpand[0].target = npCollapsed ? 0 : 1;
        if ((zone == Z_PLAYER || zone == Z_PLAYER_CTRL) && (nowPlaying == null || !nowPlaying.visible())) {
            zone = Z_TOP;
            topIndex = 3;
            retarget(false);
        }
        restartTick();
    }

    /** Cover, title and artist, in a glass capsule like the buttons beside it. */
    private void drawNowPlaying(Canvas c) {
        NowPlaying np = nowPlaying;
        if (np == null || !np.visible()) return;
        RectF first = topRects[3];
        float h = first.height(), right = first.left - 22 * k, rad = h / 2;
        // Paused, it collapses to a circle the size of the buttons; playing again, it opens back up.
        float e = Ui.clamp(npExpand[0].value), full = 440 * k;
        npRect.set(right - (h + (full - h) * e), first.top, right, first.bottom);
        RectF r = npRect;
        tmp.set(r);
        tmp.offset(0, 6 * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f)));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        scene.liquid(c, r, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k, tone(0.6f, 0.45f), 1);
        // Focused: white, like the round buttons beside it (OK then plays or pauses).
        float pf = Ui.clamp(playerFocus[0].value);
        if (pf > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, pf));
            c.drawRoundRect(r, rad, rad, fill);
        }
        // Inset enough that the cover's corners clear the capsule's round end.
        float pad = 10 * k, as = h - 2 * pad, ax = r.left + pad + 3 * k * e, ar = as * (0.5f - 0.22f * e);
        cell.set(ax, r.top + pad, ax + as, r.top + pad + as);
        if (np.art != null) {
            roundImage(c, np.art, cell, ar);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0x14000000, 0x24FFFFFF));
            c.drawRoundRect(cell, ar, ar, fill);
            Symbols.draw(getContext(), c, "play", cell.centerX(), cell.centerY(), 22 * k, tone(L_DIM, 0xB3FFFFFF));
        }
        // The equalizer: moving while it plays, still at its lowest when paused.
        float eqW = 22 * k;
        // Text and equalizer fade in once the capsule is mostly open, clipped to it while it moves.
        float te = Ui.clamp((e - 0.4f) / 0.6f);
        if (te <= 0.01f) return;
        float tx = cell.right + 16 * k, tr = r.right - rad * 0.75f - (eqW > 0 ? eqW + 14 * k : 0);
        float avail = full - (pad + 3 * k + as + 16 * k) - (rad * 0.75f + eqW + 14 * k);   // the open width: stable text
        c.save();
        c.clipRect(r);
        if (npTitle == null || npTitle.getTextSize() != 24 * k) {
            npTitle = new TextPaint(Ui.text(Ui.displaySemibold, 24 * k, 0xFFFFFFFF));
            npTitle.setLetterSpacing(-0.01f);
            npArtist = new TextPaint(Ui.text(Ui.regular, 19 * k, 0xFFFFFFFF));
            npArtist.setLetterSpacing(0.01f);
            npVersion = -1;
        }
        if (np.version != npVersion || avail != npWidth) {
            npVersion = np.version;
            npWidth = avail;
            npTitleText = android.text.TextUtils.ellipsize(np.title, npTitle, avail, android.text.TextUtils.TruncateAt.END);
            npArtistText = android.text.TextUtils.ellipsize(np.artist, npArtist, avail, android.text.TextUtils.TruncateAt.END);
        }
        npTitle.setColor(Ui.alpha(blend(tone(L_INK, 0xFAFFFFFF), L_INK, pf), te));
        npArtist.setColor(Ui.alpha(blend(tone(L_DIM, 0x99FFFFFF), L_DIM, pf), te));
        // Two lines centred as a block on the capsule.
        float t1 = Ui.cap(npTitle), t2 = Ui.cap(npArtist), lineGap = 11 * k, block = t1 + lineGap + t2;
        float base1 = r.centerY() - block / 2 + t1, base2 = base1 + lineGap + t2;
        c.drawText(npTitleText, 0, npTitleText.length(), tx, base1, npTitle);
        c.drawText(npArtistText, 0, npArtistText.length(), tx, base2, npArtist);
        if (eqW > 0) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(blend(tone(L_INK, 0xE6FFFFFF), L_INK, pf), te));
            float bw = 4 * k, gap = (eqW - 3 * bw) / 2, x = r.right - rad * 0.75f - eqW, bottom = r.centerY() + 9 * k;
            float t = SystemClock.uptimeMillis() / 1000f;
            for (int b = 0; b < 3; b++) {
                // Each bar bounces at its own pace (an animation: measuring the sound cost the TV too much); paused,
                // three still dots.
                float bh = !np.playing ? bw : (8 + 11 * Math.abs((float) Math.sin(t * EQ_SPEED[b] + EQ_PHASE[b]))) * k;
                tmp.set(x + b * (bw + gap), bottom - bh, x + b * (bw + gap) + bw, bottom);
                c.drawRoundRect(tmp, bw / 2, bw / 2, fill);
            }
        }
        c.restore();
    }

    /**
     * Previous, play/pause and next: three glass circles floating under the widget, like the round buttons, that rise
     * into place while the widget (or one of them) has focus. Focused: white with dark ink.
     */
    private void drawPlayerControls(Canvas c) {
        float show = Ui.clamp(ctrlShow[0].value);
        NowPlaying np = nowPlaying;
        if (show <= 0.01f || np == null || !np.visible()) return;
        int n = ctrlKinds.length;
        // Anchored where the open capsule's centre is (it shrinks to the right as it collapses: following it dragged the
        // buttons sideways); they rise into it as they shrink and fade, the outer ones a little ahead of the middle one.
        float dia = 58 * k, gap = 16 * k, cx = npRect.right - 440 * k / 2, base = npRect.bottom + 22 * k + dia / 2;
        for (int i = 0; i < n; i++) {
            float side = Math.abs(i - (n - 1) / 2f) / Math.max(1, (n - 1) / 2f);   // 0 middle, 1 the ends
            float own = Ui.clamp((show - 0.25f * side) / (1 - 0.25f * side));
            if (own <= 0.01f) continue;
            float eased = own * own * (3 - 2 * own);
            float f = Ui.clamp(ctrlFocus[i].value), s = (0.6f + 0.4f * eased) * (1 + 0.1f * f), rad = dia / 2 * s;
            float x = cx + (i - (n - 1) / 2f) * (dia + gap);
            float cy = base - (1 - eased) * 34 * k;
            float showWas = show;
            show = eased * eased;   // the fade runs ahead of the motion: gone before it reaches the capsule
            cell.set(x - rad, cy - rad, x + rad, cy + rad);
            tmp.set(cell);
            tmp.offset(0, (5 + 7 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, (tone(0.1f, 0.3f) + tone(0.12f, 0.18f) * f) * show));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 10 * k, 7 * k,
                    tone(0.6f, 0.45f), show * (1 - f));
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f * show));
                c.drawCircle(x, cy, rad, fill);
            }
            fill.setShader(null);
            fill.setColor(Ui.alpha(blend(tone(L_INK, 0xEBFFFFFF), L_INK, f), show));
            int kind = ctrlKinds[i];
            drawControlGlyph(c, kind == C_PLAY ? (np.playing ? 3 : 1) : kind, x, cy, rad * 0.36f);
            show = showWas;
        }
    }

    private int indexOfKind(int kind) {
        for (int i = 0; i < ctrlKinds.length; i++) if (ctrlKinds[i] == kind) return i;
        return 0;
    }

    /** 0 previous, 1 play, 2 next, 3 pause, 6 stop (filled): centred on (x, y), `u` about half their height, in `fill`'s colour. */
    private void drawControlGlyph(Canvas c, int which, float x, float y, float u) {
        glyphPath.reset();
        if (which == C_STOP) {   // stop: a rounded square
            tmp.set(x - u * 0.72f, y - u * 0.72f, x + u * 0.72f, y + u * 0.72f);
            c.drawRoundRect(tmp, u * 0.18f, u * 0.18f, fill);
            return;
        }
        switch (which) {
            case 1:
                glyphPath.moveTo(x - u * 0.7f, y - u);
                glyphPath.lineTo(x + u * 0.95f, y);
                glyphPath.lineTo(x - u * 0.7f, y + u);
                glyphPath.close();
                break;
            case 3:
                tmp.set(x - u * 0.8f, y - u, x - u * 0.22f, y + u);
                c.drawRoundRect(tmp, u * 0.15f, u * 0.15f, fill);
                tmp.set(x + u * 0.22f, y - u, x + u * 0.8f, y + u);
                c.drawRoundRect(tmp, u * 0.15f, u * 0.15f, fill);
                return;
            default: {
                float dir = which == 0 ? -1 : 1;   // previous points left, next points right
                glyphPath.moveTo(x - dir * u * 0.75f, y - u * 0.85f);
                glyphPath.lineTo(x + dir * u * 0.55f, y);
                glyphPath.lineTo(x - dir * u * 0.75f, y + u * 0.85f);
                glyphPath.close();
                float bx = x + dir * u * 0.62f;
                tmp.set(Math.min(bx, bx + dir * u * 0.28f), y - u * 0.85f, Math.max(bx, bx + dir * u * 0.28f), y + u * 0.85f);
                c.drawRoundRect(tmp, u * 0.1f, u * 0.1f, fill);
                break;
            }
        }
        c.drawPath(glyphPath, fill);
    }

    private final Path glyphPath = new Path();

    /** The account circle: glass like the buttons, the photo inside; focused, a white ring and the label. */
    private void drawAvatar(Canvas c) {
        if (!SHOW_AVATAR) return;
        if (!avatarLoaded) {
            avatarLoaded = true;
            avatar = loadAvatar();
        }
        RectF r = avatarRect;
        float f = Ui.clamp(avatarFocus[0].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
        cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
        tmp.set(cell);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
        shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k, tone(0.6f, 0.45f), 1);
        float in = 4 * k * s;
        tmp.set(cell.left + in, cell.top + in, cell.right - in, cell.bottom - in);
        if (avatar != null) {
            roundImage(c, crispTile(avatar, tmp.width(), tmp.height()), tmp, tmp.width() / 2);
        } else {
            // A person: head and shoulders, in the buttons' ink.
            fill.setShader(null);
            fill.setColor(tone(L_INK, 0xEBFFFFFF));
            float cx = cell.centerX(), u = rad / 3.4f;
            c.drawCircle(cx, cell.centerY() - u * 0.55f, u * 0.82f, fill);
            tmp.set(cx - u * 1.55f, cell.centerY() + u * 0.55f, cx + u * 1.55f, cell.centerY() + u * 3.4f);
            c.drawArc(tmp, 180, 180, true, fill);
        }
        if (f > 0.01f) {
            rimPaint().setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawCircle(cell.centerX(), cell.centerY(), rad - 1.5f * k, rimPaint());
            String label = "Tu cuenta";
            float half = lTip.measureText(label) / 2;
            lTip.setAlpha(Math.round(255 * f));
            c.drawText(label, Math.min(r.centerX(), CONTENT_R * k - half), r.bottom + (40 + 4 * f) * k, lTip);
        }
    }

    private Paint avatarRim;

    private Paint rimPaint() {
        if (avatarRim == null) {
            avatarRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            avatarRim.setStyle(Paint.Style.STROKE);
        }
        avatarRim.setStrokeWidth(3 * k);
        return avatarRim;
    }

    /** assets/profile.jpg, centre-cropped to a square: the person's own photo, since apps can't see the Google one. */
    private Bitmap loadAvatar() {
        try (java.io.InputStream in = getContext().getAssets().open("profile.jpg")) {
            Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            if (b == null) return null;
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            return side > 256 ? Bitmap.createScaledBitmap(sq, 256, 256, true) : sq;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void drawLightButtons(Canvas c) {
        drawAvatar(c);
        drawNowPlaying(c);
        drawPlayerControls(c);
        applyTone();
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, lightButtons.get(i).symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = lightButtons.get(i).label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (watchShown()) {
                    zone = Z_WATCH;
                    watchIndex = 0;
                } else if (cards.isEmpty() && recentShown()) {
                    zone = Z_RECENT;
                    recentIndex = nearest(recentRects, recent.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                } else if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_WATCH:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(watch.items.size() - 1, watchIndex + dx));
                    if (next == watchIndex) return true;
                    watchIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = 3;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_RECENT:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(recent.size() - 1, recentIndex + dx));
                    if (next == recentIndex) return true;
                    recentIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = 3;
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, recentRects[recentIndex].centerX());
                }
                break;
            case Z_PLAYER:
                if (dx > 0) {
                    zone = Z_TOP;
                    topIndex = 3;
                } else if (dx < 0 && picksOn()) {
                    zone = Z_TOP;
                    topIndex = 2;
                    selectTab(2);
                } else if (dy > 0) {
                    if (npCollapsed) {
                        zone = cards.isEmpty() ? Z_SHELF : Z_CARDS;
                    } else {
                        zone = Z_PLAYER_CTRL;   // down into the controls under it, on play/pause
                        ctrlIndex = indexOfKind(C_PLAY);
                    }
                } else if (dy < 0) {
                    idleFrom = Z_PLAYER;
                    zone = Z_IDLE;
                } else {
                    return true;
                }
                break;
            case Z_PLAYER_CTRL:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(ctrlKinds.length - 1, ctrlIndex + dx));
                    if (next == ctrlIndex) return true;
                    ctrlIndex = next;
                } else if (dy < 0) {
                    zone = Z_PLAYER;
                } else {
                    zone = cards.isEmpty() ? Z_SHELF : Z_CARDS;
                }
                break;
            case Z_AVATAR:
                if (dx < 0) {
                    zone = Z_TOP;
                    topIndex = 5;
                } else if (dy > 0) {
                    zone = cards.isEmpty() ? Z_SHELF : Z_CARDS;
                } else {
                    return true;
                }
                break;
            case Z_TOP:
                if (dx > 0 && topIndex == 5) {
                    if (!SHOW_AVATAR) return true;
                    zone = Z_AVATAR;
                    break;
                }
                // Left from the first round button: the now-playing widget, when it shows.
                if (dx < 0 && topIndex == 3 && nowPlaying != null && nowPlaying.visible()) {
                    zone = Z_PLAYER;
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(picksOn() ? 0 : 3, Math.min(5, topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty() && watchShown()) {
                        zone = Z_WATCH;
                        watchIndex = 0;
                    } else if (cards.isEmpty() && recentShown()) {
                        zone = Z_RECENT;
                        recentIndex = nearest(recentRects, recent.size(), topRects[topIndex].centerX());
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else if (dy < 0) {
                    idleFrom = Z_TOP;
                    zone = Z_IDLE;
                } else {
                    return true;
                }
                break;
            case Z_IDLE:
                if (dy < 0 && idleFrom == Z_SHELF) {   // nothing focused, ↑: straight to the buttons at the top
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = idleFrom;   // otherwise the first arrow only brings the focus back
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
