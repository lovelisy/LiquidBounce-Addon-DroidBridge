package liquidbounce.DroidBridge;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns the input of the game into events for a page.
 *
 * The game reports its pointer and its wheel in fractions of the screen, which is what the page
 * takes as well, so nothing here has to know the size of the view. Keys are different: a web view
 * that the system has never given an input connection drops them, so enter, backspace and typed
 * text are mirrored inside the page instead.
 */
final class WryInput {

    /**
     * One wheel step in pixels of a page, the rough distance a mouse wheel covers in a browser.
     */
    private static final float WHEEL_STEP = 100f;

    /**
     * The keys the bridge handles itself, everything else goes to the input method of the system.
     */
    private static final int EDIT_BACK = -1;

    private static final int EDIT_DELETE = -2;

    private static final int ENTER = -3;

    /**
     * The game reports GLFW keycodes, but the launcher forwards the character code instead: a
     * backspace arrives as 8 and a letter as the code of the letter, with the SDL scancode next to
     * it. Both spellings are listed here, whichever one a launcher uses.
     */
    private static final int KEY_GLFW_ENTER = 257;

    private static final int KEY_GLFW_BACKSPACE = 259;

    private static final int KEY_GLFW_DELETE = 261;

    private static final int KEY_ASCII_ENTER = 13;

    private static final int KEY_ASCII_LINEFEED = 10;

    private static final int KEY_ASCII_BACKSPACE = 8;

    private static final int KEY_ASCII_DELETE = 127;

    static void pointer(WryPage page, float x, float y, int action, int button, float amount) {
        WryView view = page.view;
        if (view == null) {
            return;
        }
        if (view.getVisibility() != android.view.View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        if (action == 1) {
            page.pressed = true;
            page.pressedButton = button;
            dom(page, view, x, y, "down", button);
            return;
        }
        if (action == 2) {
            int released = page.pressedButton != 0 ? page.pressedButton : button;
            page.pressed = false;
            page.pressedButton = 0;
            dom(page, view, x, y, "up", released);
            return;
        }
        if (action == 3) {
            wheel(page, view, x, y, amount);
            return;
        }
        if (page.pressed) {
            dom(page, view, x, y, "move", page.pressedButton);
            return;
        }
        mouse(view, MotionEvent.ACTION_HOVER_MOVE, x, y);
    }

    static void key(WryPage page, int keycode, boolean pressed) {
        if (page.view == null || page.view.getVisibility() != android.view.View.VISIBLE) {
            return;
        }
        int code = keycode(keycode);
        if (code == 0 || !pressed) {
            return;
        }
        if (code == ENTER) {
            enter(page);
            return;
        }
        edit(page, code == EDIT_BACK);
    }

    static void text(WryPage page, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (page.view == null || page.view.getVisibility() != android.view.View.VISIBLE) {
            return;
        }
        if ("\b".equals(value)) {
            edit(page, true);
            return;
        }
        String quoted = "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r") + "'";
        page.view.evaluateJavascript("(function(text){" + WryScripts.INSERT + "})(" + quoted + ")", null);
    }

    static void enter(WryPage page) {
        page.view.evaluateJavascript("(function(){" + WryScripts.ENTER + "})()", null);
    }

    static void edit(final WryPage page, final boolean backspace) {
        page.view.evaluateJavascript("(function(){" + WryScripts.edit(backspace) + "})()", null);
    }

    private static int keycode(int code) {
        switch (code) {
            case KEY_GLFW_ENTER:
            case KEY_ASCII_ENTER:
            case KEY_ASCII_LINEFEED:
                return ENTER;
            case KEY_GLFW_BACKSPACE:
            case KEY_ASCII_BACKSPACE:
                return EDIT_BACK;
            case KEY_GLFW_DELETE:
            case KEY_ASCII_DELETE:
                return EDIT_DELETE;
            default:
                return 0;
        }
    }

    /**
     * The axis of a scroll event does not carry the steps the game reports, so the wheel is raised in
     * the page instead, where the delta is what the page expects.
     */
    private static void wheel(final WryPage page, WryView view, float x, float y, float amount) {
        final float delta = -amount * WHEEL_STEP;
        if (delta == 0f) {
            return;
        }
        page.remember(x, y);
        view.evaluateJavascript("(function(){" + WryScripts.wheel(x, y, delta) + "})()", null);
    }

    /**
     * A hover is a move of the mouse over a page that does not take a click, so the same spot does
     * not have to be sent twice. The objects of the event are kept instead of being built for every
     * move, which is what the page receives on every frame while the pointer is over it.
     */
    private static final class Hover {

        private final MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();

        private final MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();

        private final MotionEvent.PointerProperties[] singleProperties = {properties};

        private final MotionEvent.PointerCoords[] singleCoords = {coords};

        private float lastX;

        private float lastY;

        private boolean sent;

        private Hover() {
            properties.id = 0;
            properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        }
    }

    private static final Map<WryView, Hover> HOVERS = new HashMap<>();

    static void forget(WryView view) {
        HOVERS.remove(view);
    }

    private static boolean mouse(WryView view, int type, float x, float y) {
        Hover hover = HOVERS.get(view);
        if (hover == null) {
            hover = new Hover();
            HOVERS.put(view, hover);
        }
        int width = view.getWidth();
        int height = view.getHeight();
        float px = x * width;
        float py = y * height;
        if (hover.sent && px == hover.lastX && py == hover.lastY) {
            return false;
        }
        hover.sent = true;
        hover.lastX = px;
        hover.lastY = py;
        hover.coords.x = px;
        hover.coords.y = py;
        if (!view.isFocused()) {
            view.requestFocus();
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, type, 1,
                hover.singleProperties, hover.singleCoords,
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        try {
            return view.inject(event);
        } finally {
            event.recycle();
        }
    }

    /**
     * A motion event cannot carry the button that was released, so Chromium never reports a click for
     * one. The page is driven directly instead, where the coordinates are taken as fractions of the
     * viewport and the target is resolved by hit testing.
     */
    private static void dom(WryPage page, WryView view, final float x, final float y,
                            final String phase, int button) {
        if (!view.isFocused()) {
            view.requestFocus();
        }
        int domButton = button == 2 ? 2 : (button == 3 ? 1 : 0);
        int bit = domButton == 2 ? 2 : (domButton == 1 ? 4 : 1);
        boolean down = "down".equals(phase);
        boolean up = "up".equals(phase);

        int detail = 1;
        boolean twice = false;
        if (down) {
            long now = SystemClock.uptimeMillis();
            twice = page.lastPress != 0L && domButton == 0 && page.lastButton == 0
                    && now - page.lastPress < 500L
                    && Math.abs(x - page.lastX) < 0.01f && Math.abs(y - page.lastY) < 0.01f;
            detail = twice ? 2 : 1;
            page.lastPress = now;
            page.lastX = x;
            page.lastY = y;
            page.lastButton = domButton;
            page.detail = detail;
            page.doubleClick = twice;
        } else if (up) {
            detail = page.detail;
            twice = page.doubleClick;
            page.doubleClick = false;
        } else if (page.sameSpot(x, y)) {
            return;
        }
        page.remember(x, y);

        StringBuilder script = new StringBuilder(80);
        script.append("window.__wry&&window.__wry('").append(phase).append("',")
                .append(x).append(',').append(y).append(',')
                .append(domButton).append(',').append(detail).append(',')
                .append(up ? 0 : bit).append(')');

        view.evaluateJavascript(script.toString(), null);
    }

    private WryInput() {
    }
}
