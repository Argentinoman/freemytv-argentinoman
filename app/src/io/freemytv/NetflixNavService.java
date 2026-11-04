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

import java.util.ArrayList;
import java.util.List;

/**
 * The arrows inside Netflix's phone build (HY300: Netflix's TV build needs a certified device), in place of the
 * vendor's "NetflixMouse": no pointer, the arrows jump between the things on screen that can be clicked (posters,
 * buttons), a white ring shows where, and OK clicks it. Reading the screen is limited to Netflix (packageNames in
 * res/xml/netflix_nav_service.xml); in any other app every key goes on untouched.
 */
public class NetflixNavService extends AccessibilityService {
    static final String NETFLIX = "com.netflix.mediaclient";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Rect focus = new Rect();
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
            main.postDelayed(this::refresh, 400);
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
        List<AccessibilityNodeInfo> nodes = clickables(root);
        if (nodes.isEmpty()) return false;   // nothing to land on (the video playing): Netflix takes the key
        if (!hasFocus || !onScreen(nodes)) pick(first(nodes));
        if (ok) {
            if (e.getRepeatCount() == 0) click(nodes);
        } else {
            move(nodes, dx, dy, root);
        }
        showRing();
        return true;
    }

    private boolean keyboardShown() {
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        }
        return false;
    }

    /** What can be clicked and is on screen: the innermost clickable of each nested group (a poster, not its row). */
    private static List<AccessibilityNodeInfo> clickables(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static boolean collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || !n.isVisibleToUser()) return false;
        boolean inner = false;
        for (int i = 0; i < n.getChildCount(); i++) inner |= collect(n.getChild(i), out);
        if (!inner && n.isClickable() && n.isEnabled()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 8 && r.height() > 8) {
                out.add(n);
                return true;
            }
        }
        return inner;
    }

    private boolean onScreen(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.equals(focus)) return true;
        }
        return false;
    }

    /** The first place: the top-left-most thing. */
    private static AccessibilityNodeInfo first(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = nodes.get(0);
        int bestScore = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int score = r.top * 4 + r.left;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private void pick(AccessibilityNodeInfo n) {
        n.getBoundsInScreen(focus);
        hasFocus = true;
        AppLog.w("netflix nav: " + n.getClassName() + " " + focus.toShortString());   // TEMP: while tuning
    }

    /**
     * The nearest thing in that direction, as Android's own focus finder picks it: what lines up with the ring wins,
     * then 13 × the distance that way² + the sideways offset². None: the list it's in scrolls one step that way, and
     * the ring finds the nearest once it has moved.
     */
    private void move(List<AccessibilityNodeInfo> nodes, int dx, int dy, AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        boolean bestInBeam = false;
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.equals(focus)) continue;
            boolean ahead = dx > 0 ? r.left >= focus.centerX() : dx < 0 ? r.right <= focus.centerX()
                    : dy > 0 ? r.top >= focus.centerY() : r.bottom <= focus.centerY();
            if (!ahead) continue;
            long major = Math.max(0, dx > 0 ? r.left - focus.right : dx < 0 ? focus.left - r.right
                    : dy > 0 ? r.top - focus.bottom : focus.top - r.bottom);
            long minor = dx != 0 ? r.centerY() - focus.centerY() : r.centerX() - focus.centerX();
            boolean inBeam = dx != 0 ? r.bottom > focus.top && r.top < focus.bottom
                    : r.right > focus.left && r.left < focus.right;
            long score = 13 * major * major + minor * minor;
            if ((inBeam && !bestInBeam) || (inBeam == bestInBeam && score < bestScore)) {
                bestScore = score;
                bestInBeam = inBeam;
                best = n;
            }
        }
        if (best != null) {
            pick(best);
            return;
        }
        AccessibilityNodeInfo list = scroller(root, dx != 0);
        if (list != null && list.performAction(dx + dy > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            final int fx = dx, fy = dy;
            main.postDelayed(() -> afterScroll(fx, fy), 350);
        }
    }

    /** After a scroll: the nearest thing to where the ring was, a step that way if there is one. */
    private void afterScroll(int dx, int dy) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !NETFLIX.contentEquals(root.getPackageName())) return;
        List<AccessibilityNodeInfo> nodes = clickables(root);
        if (nodes.isEmpty()) return;
        int cx = focus.centerX() + dx * focus.width() / 2, cy = focus.centerY() + dy * focus.height() / 2;
        AccessibilityNodeInfo best = null;
        long bestD = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long d = (long) (r.centerX() - cx) * (r.centerX() - cx) + (long) (r.centerY() - cy) * (r.centerY() - cy);
            if (d < bestD) {
                bestD = d;
                best = n;
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

    /** OK: the thing under the ring clicks; if it won't, a tap at its centre. */
    private void click(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!r.equals(focus)) continue;
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                hasFocus = false;
                main.postDelayed(this::refresh, 500);   // a new screen: the ring finds its first place
                return;
            }
        }
        Path p = new Path();
        p.moveTo(focus.centerX(), focus.centerY());
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
        hasFocus = false;
        main.postDelayed(this::refresh, 500);
    }

    /** After a click or Back: the ring on the new screen's first thing, if Netflix is still there. */
    private void refresh() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) {
            hideRing();
            return;
        }
        List<AccessibilityNodeInfo> nodes = clickables(root);
        if (nodes.isEmpty()) {
            hideRing();
            return;
        }
        if (!hasFocus || !onScreen(nodes)) pick(first(nodes));
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
