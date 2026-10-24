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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WryBridge {

    private static Application application;
    private static Handler main;
    private static OverlayWebView webView;
    private static String dataSuffix;
    private static long downTime;
    private static volatile boolean pressed;
    private static volatile float pendingX;
    private static volatile float pendingY;
    private static volatile boolean hoverPosted;
    private static volatile boolean loaded;

    private WryBridge() {
    }

    public static String start(Application app, String suffix) {
        application = app;
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

    public static String create(final Activity activity, final String url) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return add(activity, url);
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                outcome[0] = add(activity, url);
                latch.countDown();
            }
        });
        return await(latch, outcome);
    }

    private static String add(Activity activity, String url) {
        try {
            detach();
            loaded = false;
            long begin = System.nanoTime();
            OverlayWebView view = new OverlayWebView(activity);
            view.setBackgroundColor(0x00000000);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setSupportZoom(false);
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView target, String page) {
                    loaded = true;
                }
            });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            activity.addContentView(view, params);
            view.bringToFront();
            view.setVisibility(View.INVISIBLE);
            view.setFocusableInTouchMode(false);
            view.loadUrl(url);
            webView = view;
            long took = (System.nanoTime() - begin) / 1_000_000L;
            ViewGroup parent = (ViewGroup) view.getParent();
            return "webview added in " + took + " ms on " + Thread.currentThread().getName()
                    + ", provider " + WebView.getCurrentWebViewPackage()
                    + ", parent " + (parent == null ? "none" : parent.getClass().getName())
                    + ", url " + url;
        } catch (Throwable error) {
            return "webview failed: " + error;
        }
    }

    public static String setVisible(final boolean visible) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                }
            }
        });
        return "visibility " + visible;
    }

    public static void pointer(final float x, final float y, final int action, final int button, final float amount) {
        if (action == 0 && !pressed) {
            pendingX = x;
            pendingY = y;
            if (!hoverPosted) {
                hoverPosted = true;
                main.post(HOVER);
            }
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                apply(x, y, action, button, amount);
            }
        });
    }

    private static final Runnable HOVER = new Runnable() {
        @Override
        public void run() {
            hoverPosted = false;
            apply(pendingX, pendingY, 0, 0, 0f);
        }
    };

    private static void apply(float x, float y, int action, int button, float amount) {
        OverlayWebView view = webView;
        if (view == null || view.getVisibility() != View.VISIBLE
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        float sx = x * view.getWidth();
        float sy = y * view.getHeight();
        long now = SystemClock.uptimeMillis();
        int type;
        if (action == 1) {
            pressed = true;
            downTime = now;
            type = MotionEvent.ACTION_DOWN;
        } else if (action == 2) {
            pressed = false;
            type = MotionEvent.ACTION_UP;
        } else if (action == 3) {
            type = MotionEvent.ACTION_SCROLL;
        } else {
            type = pressed ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;
        }
        boolean touch = type == MotionEvent.ACTION_DOWN
                || type == MotionEvent.ACTION_MOVE
                || type == MotionEvent.ACTION_UP;
        long eventDownTime = touch ? downTime : now;
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
        event.recycle();
    }

    public static void key(final int keycode, final boolean pressed) {
        main.post(new Runnable() {
            @Override
            public void run() {
                OverlayWebView view = webView;
                if (view == null || view.getVisibility() != View.VISIBLE) {
                    return;
                }
                int code = keycode(keycode);
                if (code == 0) {
                    return;
                }
                view.dispatchKeyEvent(new KeyEvent(
                        pressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                        code));
            }
        });
    }

    public static boolean loaded() {
        return loaded;
    }

    public static String close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detach();
            return "webview closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    detach();
                    outcome[0] = "webview closed";
                } catch (Throwable error) {
                    outcome[0] = "closing the webview failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return await(latch, outcome);
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

    private static void detach() {
        OverlayWebView view = webView;
        webView = null;
        pressed = false;
        hoverPosted = false;
        loaded = false;
        if (view == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }
        view.destroy();
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
