package liquidbounce.DroidBridge;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.ValueCallback;

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
    private static final int EDIT = -1;

    private static final int ENTER = -2;

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
        edit(page, true);
    }

    static void text(WryPage page, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (page.view == null || page.view.getVisibility() != android.view.View.VISIBLE) {
            return;
        }
        if ("\b".equals(value)) {
            WryLog.log("text browser " + page.id + " backspace via char");
            edit(page, true);
            return;
        }
        String quoted = "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r") + "'";
        page.view.evaluateJavascript("(function(text){" + WryScripts.INSERT + "})(" + quoted + ")",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String result) {
                        WryLog.log("text browser " + page.id + " '" + value + "' -> " + result);
                    }
                });
    }

    static void enter(WryPage page) {
        page.view.evaluateJavascript("(function(){" + WryScripts.ENTER + "})()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                WryLog.log("enter browser " + page.id + " -> " + value);
            }
        });
    }

    static void edit(final WryPage page, boolean backspace) {
        page.view.evaluateJavascript("(function(){" + WryScripts.edit(backspace) + "})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        WryLog.log("edit browser " + page.id + " backspace " + value);
                    }
                });
    }

    private static int keycode(int glfw) {
        switch (glfw) {
            case 257:
                return ENTER;
            case 259:
                return EDIT;
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
        view.evaluateJavascript("(function(){" + WryScripts.wheel(x, y, delta) + "})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        WryLog.log("wheel browser " + page.id + " amount " + amount + " delta " + delta
                                + " -> " + value);
                    }
                });
    }

    private static boolean mouse(WryView view, int type, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x * view.getWidth();
        coords.y = y * view.getHeight();
        MotionEvent event = MotionEvent.obtain(now, now, type, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        if (!view.isFocused()) {
            view.requestFocus();
        }
        boolean accepted = view.inject(event);
        event.recycle();
        return accepted;
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

        StringBuilder script = new StringBuilder("if(!window.__wry){")
                .append(WryScripts.DISPATCH)
                .append("}window.__wry('")
                .append(phase).append("',").append(x).append(',').append(y)
                .append(',').append(domButton).append(',').append(detail)
                .append(',').append(up ? 0 : bit).append(')');

        view.evaluateJavascript(script.toString(), null);
    }

    private WryInput() {
    }
}
