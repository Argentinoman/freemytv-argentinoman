package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The arrows inside Netflix's phone build (HY300: Netflix's TV build needs a certified device), in place of the
 * vendor's "NetflixMouse": no pointer, the arrows jump between the things on screen that can be clicked (posters,
 * buttons), a white ring shows where, and OK clicks it. Reading the screen is limited to Netflix (packageNames in
 * res/xml/netflix_nav_service.xml); in any other app every key goes on untouched.
 */
public class NetflixNavService extends AccessibilityService {
    static final String NETFLIX = "com.netflix.mediaclient";

    /**
     * Something the ring can land on: a clickable node, or a card Netflix doesn't expose (its "Continue Watching" row
     * shows to accessibility as an empty list), placed where the posters of the other rows are; OK taps its centre.
     */
    private static final class Target {
        final Rect r = new Rect();
        AccessibilityNodeInfo node;   // null: a card worked out from the row's bounds
        String label = "";   // what it shows (a title), to find it again once its list has moved
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Rect focus = new Rect();
    private String focusLabel = "";
    private boolean hasFocus;
    private Ring ring;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        hideRing();
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (code == KeyEvent.KEYCODE_BACK) {   // Netflix goes back itself; the ring finds its place again after
            hasFocus = false;
            main.postDelayed(this::refresh, 500);
            return false;
        }
        if (dx == 0 && dy == 0 && !ok) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) {
            hideRing();   // not Netflix: the key goes on as if we weren't here
            return false;
        }
        if (keyboardShown()) {   // typing (the e-mail, a search): the arrows are the keyboard's
            hideRing();
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        List<Target> targets = targets(root);
        if (targets.isEmpty()) return false;   // nothing to land on (the video playing): Netflix takes the key
        boolean shown = ring != null && ring.getVisibility() == View.VISIBLE;
        if (!hasFocus || !onScreen(targets) || !shown) {   // the first key only shows where the ring is
            if (!hasFocus || !onScreen(targets)) pick(first(targets));
            showRing();
            return true;
        }
        if (ok) {
            if (e.getRepeatCount() == 0) click(targets);
        } else {
            move(targets, dx, dy, root);
        }
        showRing();
        return true;
    }

    private boolean keyboardShown() {
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        }
        return false;
    }

    // MARK: - What the ring can land on

    /**
     * The clickable things on screen (the innermost of each nested group: a poster, not its row), then the cards of
     * any row Netflix hides, sized and spaced like the posters it does show.
     */
    private List<Target> targets(AccessibilityNodeInfo root) {
        List<Target> out = new ArrayList<>();
        List<Rect> hiddenRows = new ArrayList<>();
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        collect(root, out, hiddenRows, null);
        if (!hiddenRows.isEmpty()) addHiddenCards(out, hiddenRows);
        return out;
    }

    /**
     * Walks the tree. Inside a row (`row`: its bounds) a card counts even when Netflix marks it not visible (its
     * "Continue Watching" cards: real bounds and titles, but flagged hidden), as long as it's within the row. A
     * clickable that holds other clickables (that card, with its ⓘ and ⋮ under the picture) counts too, as the part
     * above them.
     */
    private static int screenWidth, screenHeight;

    /** Cut off by the screen's edge: a card or button that only peeks in (a sliver at a side or the bottom). */
    private static boolean peeking(Rect r) {
        return ((r.left <= 0 || r.right >= screenWidth - 1) && r.width() * 2 < r.height())
                || ((r.top <= 0 || r.bottom >= screenHeight - 1) && r.height() < screenHeight / 12);
    }

    private static boolean collect(AccessibilityNodeInfo n, List<Target> out, List<Rect> hiddenRows, Rect row) {
        if (n == null) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (row == null ? !n.isVisibleToUser() : !Rect.intersects(row, r) || r.width() <= 0) return false;
        boolean list = n.isScrollable() && r.width() > r.height() && r.height() > r.width() / 6;   // a row, not a bar
        Rect inRow = list ? r : row;
        int from = out.size();
        boolean inner = false;
        for (int i = 0; i < n.getChildCount(); i++) inner |= collect(n.getChild(i), out, hiddenRows, inRow);
        if (n.isClickable() && n.isEnabled() && r.width() > 8 && r.height() > 8) {
            Rect part = new Rect(r);
            if (inner) {   // only the part above what it holds, if that's most of it (a picture over its buttons)
                int top = r.bottom;
                for (int i = from; i < out.size(); i++) top = Math.min(top, out.get(i).r.top);
                part.bottom = top;
                if (part.height() < r.height() * 4 / 10) part.setEmpty();
            }
            if (row != null) part.intersect(row);
            if (!part.isEmpty() && part.width() > 8 && part.height() > 8 && !peeking(part)) {
                Target t = new Target();
                t.r.set(part);
                t.node = n;
                t.label = label(n);
                out.add(t);
                return true;
            }
        }
        // A row with nothing clickable inside it, even counting hidden ones: its cards are worked out.
        if (!inner && list) hiddenRows.add(r);
        return inner;
    }

    /** What a node shows: its own description or text, or its first child's (Netflix puts a poster's title there). */
    private static String label(AccessibilityNodeInfo n) {
        CharSequence s = n.getContentDescription() != null ? n.getContentDescription() : n.getText();
        for (int i = 0; (s == null || s.length() == 0) && i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) s = c.getContentDescription() != null ? c.getContentDescription() : c.getText();
        }
        return s == null ? "" : s.toString();
    }

    /** The cards of the hidden rows, at the posters' width and spacing (worked out from the rows that show). */
    private void addHiddenCards(List<Target> out, List<Rect> hiddenRows) {
        int width = 0, pitch = 0, margin = 0;
        List<Integer> widths = new ArrayList<>();
        for (Target t : out) {
            if (t.node != null && t.r.height() > t.r.width() && t.r.width() > 40) widths.add(t.r.width());
        }
        if (!widths.isEmpty()) {
            Collections.sort(widths);
            width = widths.get(widths.size() / 2);
            for (Target a : out) {   // the gap between two posters side by side in a row
                if (a.node == null || a.r.width() != width) continue;
                for (Target b : out) {
                    if (b != a && b.node != null && b.r.top == a.r.top && b.r.left > a.r.left
                            && (pitch == 0 || b.r.left - a.r.left < pitch)) pitch = b.r.left - a.r.left;
                }
                if (margin == 0 || a.r.left < margin) margin = a.r.left;
            }
        }
        int button = Math.round(getResources().getDisplayMetrics().heightPixels / 600f * 44);   // 44 px at 600 px tall
        for (Rect row : hiddenRows) {
            int w = width > 0 ? width : Math.round(row.height() * 0.57f);
            int step = pitch > w ? pitch : Math.round(w * 1.04f);
            int x = row.left + (margin > 0 ? margin : Math.round(w * 0.08f));
            // The card as Netflix lays it out when it does expose it: the picture (2:2.8) on top, and under it the
            // ⓘ (more info) and ⋮ (menu) buttons.
            int imageBottom = Math.min(row.bottom, row.top + Math.round(w * 1.4f));
            int buttons = row.bottom - imageBottom, size = Math.min(button, buttons - 4), cy = imageBottom + buttons / 2;
            for (; x + w / 2 < row.right; x += step) {
                Target t = new Target();
                t.r.set(x, row.top, Math.min(x + w, row.right), imageBottom);
                out.add(t);
                if (size < 24) continue;
                for (float at : new float[] {0.36f, 0.78f}) {
                    int cx = x + Math.round(w * at);
                    if (cx + size / 2 > row.right) continue;
                    Target b = new Target();
                    b.r.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
                    out.add(b);
                }
            }
        }
    }

    /**
     * Whether the ring is still on something: the same thing, or one a few pixels off (Netflix's rows shift a little
     * and come and go from what it exposes), which the ring then takes.
     */
    private boolean onScreen(List<Target> targets) {
        int slack = Math.max(12, focus.width() / 8);
        for (Target t : targets) {
            if (Math.abs(t.r.centerX() - focus.centerX()) <= slack && Math.abs(t.r.centerY() - focus.centerY()) <= slack
                    && Math.abs(t.r.width() - focus.width()) <= 2 * slack) {
                focus.set(t.r);
                return true;
            }
        }
        return false;
    }

    /**
     * The first place on a new screen: the main thing, not the bar at the top (Back, Close). Among the biggest ones
     * (a title's Play button, a row of posters), the top-left-most.
     */
    private Target first(List<Target> targets) {
        int topBar = getResources().getDisplayMetrics().heightPixels / 10;
        List<Target> body = new ArrayList<>();
        long maxArea = 0;
        for (Target t : targets) {
            if (t.r.centerY() < topBar) continue;
            body.add(t);
            maxArea = Math.max(maxArea, (long) t.r.width() * t.r.height());
        }
        if (body.isEmpty()) body = targets;
        Target best = body.get(0);
        int bestScore = Integer.MAX_VALUE;
        for (Target t : body) {
            if ((long) t.r.width() * t.r.height() * 10 < maxArea * 6) continue;   // less than 60% of the biggest
            int score = t.r.top * 4 + t.r.left;
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    private void pick(Target t) {
        focus.set(t.r);
        focusLabel = t.label;
        hasFocus = true;
        AppLog.w("netflix nav: " + (t.node != null ? t.node.getClassName() : "hidden card") + " "
                + focus.toShortString());   // TEMP: while tuning
    }

    // MARK: - Moving and clicking

    /**
     * The nearest thing in that direction, as Android's own focus finder picks it: what lines up with the ring wins,
     * then 13 × the distance that way² + the sideways offset². None: the list it's in scrolls one step that way, and
     * the ring finds the nearest once it has moved.
     */
    private void move(List<Target> targets, int dx, int dy, AccessibilityNodeInfo root) {
        // The bars floating over the page (Downloads and Notifications on top, Home, Clips, Search at the bottom) wait
        // until the page can't scroll any further; sideways, only what's on the same line, or the row scrolls.
        if (step(targets, dx, dy, true, dx != 0)) return;
        AccessibilityNodeInfo list = scroller(root, dx != 0);
        if (list != null && list.performAction(dx + dy > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            final int fx = dx, fy = dy;
            main.postDelayed(() -> afterScroll(fx, fy), 600);   // once Netflix's scroll has settled
        } else {
            step(targets, dx, dy, false, false);
        }
    }

    /** A bar floating over the page: short things in the screen's first sixteenth or last eighth. */
    private boolean inBar(Rect r) {
        int h = getResources().getDisplayMetrics().heightPixels;
        boolean bottom = r.top > h * 7 / 8 - r.height() && r.centerY() > h * 7 / 8 && r.height() < h / 8;
        return bottom || (r.top < h / 16 && r.height() < h / 8);
    }

    /** One step that way among what's on screen; false when there's nothing that way. */
    private boolean step(List<Target> targets, int dx, int dy, boolean skipBar, boolean beamOnly) {
        Target best = null;
        long bestScore = Long.MAX_VALUE;
        boolean bestInBeam = false;
        for (Target t : targets) {
            Rect r = t.r;
            if (r.equals(focus) || (skipBar && inBar(r) && !inBar(focus))) continue;
            boolean ahead = dx > 0 ? r.left >= focus.centerX() : dx < 0 ? r.right <= focus.centerX()
                    : dy > 0 ? r.top >= focus.centerY() : r.bottom <= focus.centerY();
            if (!ahead) continue;
            long major = Math.max(0, dx > 0 ? r.left - focus.right : dx < 0 ? focus.left - r.right
                    : dy > 0 ? r.top - focus.bottom : focus.top - r.bottom);
            long minor = dx != 0 ? r.centerY() - focus.centerY() : r.centerX() - focus.centerX();
            boolean inBeam = dx != 0 ? r.bottom > focus.top && r.top < focus.bottom
                    : r.right > focus.left && r.left < focus.right;
            if (beamOnly && !inBeam) continue;
            long score = 13 * major * major + minor * minor;
            if ((inBeam && !bestInBeam) || (inBeam == bestInBeam && score < bestScore)) {
                bestScore = score;
                bestInBeam = inBeam;
                best = t;
            }
        }
        if (best == null) return false;
        pick(best);
        return true;
    }

    /**
     * After a scroll: the thing the ring was on, found again by what it shows where it moved to, and then the step
     * that way (so nothing in between is skipped, a poster's Share button under it say). Not found: the nearest thing
     * to where the ring was, a step that way.
     */
    private void afterScroll(int dx, int dy) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !NETFLIX.contentEquals(root.getPackageName())) return;
        List<Target> targets = targets(root);
        if (targets.isEmpty()) return;
        if (!focusLabel.isEmpty()) {   // the same title may be in two rows: the one nearest to where the ring was
            Target same = null;
            long sameD = Long.MAX_VALUE;
            for (Target t : targets) {
                if (!t.label.equals(focusLabel)) continue;
                long ddx = t.r.centerX() - focus.centerX(), ddy = t.r.centerY() - focus.centerY();
                if (ddx * ddx + ddy * ddy < sameD) {
                    sameD = ddx * ddx + ddy * ddy;
                    same = t;
                }
            }
            if (same != null) {
                pick(same);
                step(targets, dx, dy, dy > 0, false);
                showRing();
                return;
            }
        }
        int cx = focus.centerX() + dx * focus.width() / 2, cy = focus.centerY() + dy * focus.height() / 2;
        Target best = null;
        long bestD = Long.MAX_VALUE;
        for (Target t : targets) {
            long ddx = t.r.centerX() - cx, ddy = t.r.centerY() - cy;
            long d = ddx * ddx + ddy * ddy;
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        pick(best);
        showRing();
    }

    /**
     * The list holding the ring that scrolls the right way: for ← →, the innermost one wider than tall (a row of
     * posters); for ↑ ↓, the outermost one (the page).
     */
    private AccessibilityNodeInfo scroller(AccessibilityNodeInfo n, boolean sideways) {
        if (n == null || !n.isVisibleToUser()) return null;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.contains(focus.centerX(), focus.centerY())) return null;
        boolean scrolls = n.isScrollable();
        if (!sideways && scrolls && r.height() > r.width() / 3) return n;   // outermost first
        AccessibilityNodeInfo found = null;
        for (int i = 0; i < n.getChildCount() && found == null; i++) found = scroller(n.getChild(i), sideways);
        if (found != null) return found;
        return sideways && scrolls && r.width() > r.height() ? n : null;
    }

    /** OK: the thing under the ring clicks; a hidden card (or one that won't click) gets a tap at its centre. */
    private void click(List<Target> targets) {
        for (Target t : targets) {
            if (!t.r.equals(focus) || t.node == null) continue;
            boolean clicked = t.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AppLog.w("netflix nav: click " + focus.toShortString() + " -> " + clicked);   // TEMP: while tuning
            if (clicked) {
                hasFocus = false;
                main.postDelayed(this::refresh, 700);   // a new screen: the ring finds its first place
                return;
            }
        }
        Path p = new Path();
        p.moveTo(focus.centerX(), focus.centerY());
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
        AppLog.w("netflix nav: tap " + focus.toShortString());   // TEMP: while tuning
        hasFocus = false;
        main.postDelayed(this::refresh, 700);
    }

    /** After a click or Back: the ring on the new screen's first thing, if Netflix is still there. */
    private void refresh() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) {
            hideRing();
            return;
        }
        List<Target> targets = targets(root);
        if (targets.isEmpty()) {
            hideRing();
            return;
        }
        if (!hasFocus || !onScreen(targets)) pick(first(targets));
        showRing();
    }

    // MARK: - The ring: a white rounded outline around the focused thing, drawn over Netflix

    private void showRing() {
        if (ring == null) {
            ring = new Ring(this);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(ring, p);
            } catch (RuntimeException e) {
                AppLog.w("netflix ring: " + e.getMessage());
                ring = null;
                return;
            }
        }
        ring.at.set(focus);
        ring.setVisibility(View.VISIBLE);
        ring.invalidate();
    }

    private void hideRing() {
        if (ring != null) ring.setVisibility(View.GONE);
    }

    private static final class Ring extends View {
        final Rect at = new Rect();
        private final RectF r = new RectF();
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float k;

        Ring(Context c) {
            super(c);
            k = c.getResources().getDisplayMetrics().heightPixels / 1080f;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(6 * k);
            stroke.setColor(0xFFF5F5F7);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(14 * k);
            glow.setColor(0x59000000);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (at.isEmpty()) return;
            r.set(at);
            r.inset(-6 * k, -6 * k);
            float rad = 14 * k;
            c.drawRoundRect(r, rad, rad, glow);   // a dark halo: the ring shows on light posters too
            c.drawRoundRect(r, rad, rad, stroke);
        }
    }
}
