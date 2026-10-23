package net.ccbluex.liquidbounce.wry.android;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WryBridge {

    private static Application application;
    private static Handler main;
    private static WebView webView;
    private static String dataSuffix;

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
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    detach();
                    long begin = System.nanoTime();
                    WebView view = new WebView(activity);
                    view.setBackgroundColor(0xFF000000);
                    view.getSettings().setJavaScriptEnabled(true);
                    view.getSettings().setDomStorageEnabled(true);
                    view.getSettings().setSupportZoom(false);
                    view.setWebViewClient(new WebViewClient());
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                    activity.addContentView(view, params);
                    view.bringToFront();
                    view.loadUrl(url);
                    view.requestFocus();
                    webView = view;
                    long took = (System.nanoTime() - begin) / 1_000_000L;
                    ViewGroup parent = (ViewGroup) view.getParent();
                    outcome[0] = "webview added in " + took + " ms on " + Thread.currentThread().getName()
                            + ", provider " + WebView.getCurrentWebViewPackage()
                            + ", parent " + (parent == null ? "none" : parent.getClass().getName())
                            + ", url " + url;
                } catch (Throwable error) {
                    outcome[0] = "webview failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return await(latch, outcome);
    }

    public static String close() {
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

    private static void detach() {
        WebView view = webView;
        webView = null;
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
}
