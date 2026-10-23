package net.ccbluex.liquidbounce.wry.android;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WryBridge {

    private static Application application;
    private static Handler main;

    private WryBridge() {
    }

    public static String start(Application app) {
        application = app;
        main = new Handler(Looper.getMainLooper());
        return "handler ready on " + Thread.currentThread().getName() + ", package " + app.getPackageName();
    }

    public static String probe() {
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                WebView view = null;
                try {
                    long begin = System.nanoTime();
                    view = new WebView(application);
                    long took = (System.nanoTime() - begin) / 1_000_000L;
                    view.layout(0, 0, 320, 240);
                    view.loadDataWithBaseURL(
                            "https://wry.invalid/",
                            "<html><body>wry</body></html>",
                            "text/html",
                            "UTF-8",
                            null);
                    outcome[0] = "webview created in " + took + " ms on " + Thread.currentThread().getName()
                            + ", package " + WebView.getCurrentWebViewPackage();
                } catch (Throwable error) {
                    outcome[0] = "webview failed: " + error;
                } finally {
                    if (view != null) {
                        try {
                            view.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                    latch.countDown();
                }
            }
        });
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
