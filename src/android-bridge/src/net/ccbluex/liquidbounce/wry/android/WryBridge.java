package net.ccbluex.liquidbounce.wry.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WryBridge {

    private static final Map<Long, Page> PAGES = new ConcurrentHashMap<>();

    private static Handler main;
    private static String dataSuffix;

    private WryBridge() {
    }

    public static String start(Application app, String suffix) {
        main = new Handler(Looper.getMainLooper());
        if (suffix != null && !suffix.isEmpty() && dataSuffix == null) {
            try {
                WebView.setDataDirectorySuffix(suffix);
                dataSuffix = suffix;
            } catch (Throwable error) {
                return "handler ready on " + Thread.currentThread().getName() + ", package " + app.getPackageName()
                        + " | data directory suffix failed: " + error;
            }
        }
        return "handler ready on " + Thread.currentThread().getName() + ", package " + app.getPackageName()
                + ", web data " + (dataSuffix == null ? "default" : dataSuffix);
    }

    public static String create(final Activity activity, final long id, final String url) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return add(activity, id, url);
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                outcome[0] = add(activity, id, url);
                latch.countDown();
            }
        });
        return await(latch, outcome);
    }

    private static String add(Activity activity, long id, String url) {
        try {
            remove(id);
            long begin = System.nanoTime();
            OverlayWebView view = new OverlayWebView(activity);
            view.setBackgroundColor(0x00000000);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setSupportZoom(false);
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView target, String page) {
                    Page entry = PAGES.get(id);
                    if (entry != null) {
                        entry.loaded = true;
                    }
                }
            });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            activity.addContentView(view, params);
            view.bringToFront();
            view.setVisibility(View.INVISIBLE);
            view.setFocusableInTouchMode(false);
            PAGES.put(id, new Page(view));
            view.loadUrl(url);
            long took = (System.nanoTime() - begin) / 1_000_000L;
            ViewGroup parent = (ViewGroup) view.getParent();
            return "browser " + id + " added in " + took + " ms on " + Thread.currentThread().getName()
                    + ", provider " + WebView.getCurrentWebViewPackage()
                    + ", parent " + (parent == null ? "none" : parent.getClass().getName())
                    + ", url " + url;
        } catch (Throwable error) {
            PAGES.put(id, new Page(null));
            return "browser " + id + " failed: " + error;
        }
    }

    public static boolean loaded(final long id) {
        Page page = PAGES.get(id);
        return page != null && (page.loaded || page.view == null);
    }

    public static String setVisible(final long id, final boolean visible) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Page page = PAGES.get(id);
                if (page == null || page.view == null) {
                    return;
                }
                page.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                if (visible) {
                    page.view.bringToFront();
                    page.view.requestFocus();
                }
            }
        });
        return "browser " + id + " visibility " + visible;
    }

    public static void pointer(final long id, final float x, final float y, final int action,
                               final int button, final float amount) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Page page = PAGES.get(id);
                if (page == null || page.view == null) {
                    return;
                }
                if (action == 0 && !page.pressed) {
                    page.pendingX = x;
                    page.pendingY = y;
                    if (!page.hoverPosted) {
                        page.hoverPosted = true;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                Page current = PAGES.get(id);
                                if (current == null || current.view == null) {
                                    return;
                                }
                                current.hoverPosted = false;
                                apply(current, current.pendingX, current.pendingY, 0, 0, 0f);
                            }
                        });
                    }
                    return;
                }
                apply(page, x, y, action, button, amount);
            }
        });
    }

    private static void apply(Page page, float x, float y, int action, int button, float amount) {
        OverlayWebView view = page.view;
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        float sx = x * view.getWidth();
        float sy = y * view.getHeight();
        long now = SystemClock.uptimeMillis();
        int type;
        if (action == 1) {
            page.pressed = true;
            page.downTime = now;
            type = MotionEvent.ACTION_DOWN;
        } else if (action == 2) {
            page.pressed = false;
            type = MotionEvent.ACTION_UP;
        } else if (action == 3) {
            type = MotionEvent.ACTION_SCROLL;
        } else {
            type = page.pressed ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;
        }
        boolean touch = type == MotionEvent.ACTION_DOWN
                || type == MotionEvent.ACTION_MOVE
                || type == MotionEvent.ACTION_UP;
        long eventDownTime = touch ? page.downTime : now;
        int buttonState = touch ? buttonState(button) : 0;
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = sx;
        coords.y = sy;
        if (type == MotionEvent.ACTION_SCROLL) {
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, -amount);
        }
        MotionEvent event = MotionEvent.obtain(eventDownTime, now, type, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords},
                0, buttonState, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        view.inject(event);
    }

    public static void key(final long id, final int keycode, final boolean pressed) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Page page = PAGES.get(id);
                if (page == null || page.view == null || page.view.getVisibility() != View.VISIBLE) {
                    return;
                }
                int code = keycode(keycode);
                if (code == 0) {
                    return;
                }
                page.view.dispatchKeyEvent(new KeyEvent(
                        pressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                        code));
            }
        });
    }

    public static String close(final long id) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove(id);
            return "browser " + id + " closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    remove(id);
                    outcome[0] = "browser " + id + " closed";
                } catch (Throwable error) {
                    outcome[0] = "closing browser " + id + " failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return await(latch, outcome);
    }

    public static String closeAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeAll();
            return "all browsers closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    removeAll();
                    outcome[0] = "all browsers closed";
                } catch (Throwable error) {
                    outcome[0] = "closing the browsers failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return await(latch, outcome);
    }

    private static void remove(long id) {
        Page page = PAGES.remove(id);
        if (page == null || page.view == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) page.view.getParent();
        if (parent != null) {
            parent.removeView(page.view);
        }
        page.view.destroy();
    }

    private static void removeAll() {
        for (Long id : new ArrayList<>(PAGES.keySet())) {
            remove(id);
        }
    }

    private static int buttonState(int button) {
        if (button == 2) {
            return MotionEvent.BUTTON_SECONDARY;
        }
        if (button == 3) {
            return MotionEvent.BUTTON_TERTIARY;
        }
        return MotionEvent.BUTTON_PRIMARY;
    }

    private static int keycode(int glfw) {
        switch (glfw) {
            case 256:
                return KeyEvent.KEYCODE_ESCAPE;
            case 262:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 263:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case 264:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case 265:
                return KeyEvent.KEYCODE_DPAD_UP;
            case 266:
                return KeyEvent.KEYCODE_PAGE_UP;
            case 267:
                return KeyEvent.KEYCODE_PAGE_DOWN;
            default:
                return 0;
        }
    }

    private static String await(CountDownLatch latch, String[] outcome) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                return "the main thread did not answer in 20 s";
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "interrupted while waiting: " + error;
        }
        return outcome[0];
    }

    private static final class Page {

        final OverlayWebView view;
        volatile boolean loaded;
        boolean pressed;
        long downTime;
        float pendingX;
        float pendingY;
        boolean hoverPosted;

        Page(OverlayWebView view) {
            this.view = view;
        }
    }

    private static final class OverlayWebView extends WebView {

        private boolean injecting;

        OverlayWebView(Context context) {
            super(context);
        }

        void inject(MotionEvent event) {
            injecting = true;
            try {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) {
                    dispatchGenericMotionEvent(event);
                } else {
                    dispatchTouchEvent(event);
                }
            } finally {
                injecting = false;
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return injecting && super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return injecting && super.dispatchGenericMotionEvent(event);
        }
    }
}
