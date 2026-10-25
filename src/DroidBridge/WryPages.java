package liquidbounce.DroidBridge;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The pages themselves: creating one, showing it and taking it down again.
 */
final class WryPages {

    private static Handler main;

    private static String dataSuffix;

    static Handler main() {
        return main;
    }

    static void start(Application app) {
        main = new Handler(Looper.getMainLooper());
    }

    /**
     * The web view keeps one data directory per process, and the game shares its process with the
     * launcher, so the bridge asks for a directory of its own before the first web view exists.
     */
    static String prepareDataDirectory(Application app, String suffix) {
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

    static String add(Activity activity, long id, String url) {
        try {
            remove(id);
            long begin = System.nanoTime();
            WryView view = new WryView(activity);
            view.setBackgroundColor(0x00000000);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setSupportZoom(false);
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView target, String page) {
                    WryPage entry = WryStack.page(id);
                    if (entry != null) {
                        entry.loaded = true;
                    }
                    target.evaluateJavascript(WryScripts.MASKS, null);
                    target.evaluateJavascript(WryScripts.SCALE, null);
                    target.evaluateJavascript(WryScripts.DESCRIPTION_FIX, null);
                    target.evaluateJavascript(WryScripts.FONTS, null);
                    target.evaluateJavascript(WryScripts.PROBE, null);
                    WryStack.awaitVisualState(target);
                }

                @Override
                public void onPageCommitVisible(WebView target, String page) {
                    WryStack.awaitVisualState(target);
                }
            });
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                    if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        WryLog.log("console " + message.message() + " @" + message.sourceId()
                                + ":" + message.lineNumber());
                    }
                    return true;
                }
            });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            activity.addContentView(view, params);
            int layer = WryPage.layerOfUrl(url);
            WryStack.tag(view, id, layer);
            WryPage created = new WryPage(id, view, layer);
            WryStack.put(created);
            String stacking = WryStack.place(view);
            view.setVisibility(View.INVISIBLE);
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setOnFocusChangeListener(null);
            view.setOnClickListener(null);
            keepAuthCookie(url);
            view.loadUrl(url);
            long took = (System.nanoTime() - begin) / 1_000_000L;
            ViewGroup parent = (ViewGroup) view.getParent();
            return "browser " + id + " added in " + took + " ms on " + Thread.currentThread().getName()
                    + ", provider " + WebView.getCurrentWebViewPackage()
                    + ", parent " + (parent == null ? "none" : parent.getClass().getName())
                    + ", order " + (parent == null ? -1 : parent.indexOfChild(view)) + " of " + (parent == null ? -1 : parent.getChildCount())
                    + ", stacking " + stacking
                    + ", url " + url;
        } catch (Throwable error) {
            WryStack.put(new WryPage(id, null));
            return "browser " + id + " failed: " + error;
        }
    }

    /**
     * The theme authorises itself with a cookie that the server sets when it first answers with the
     * auth code in the query string. The fetch calls that toggle a module and the socket that reports
     * the change both carry that cookie, so it is read from the url and given to the web view before
     * the page loads, which keeps a web view that drops the server cookie from failing those calls.
     */
    private static void keepAuthCookie(String url) {
        try {
            String code = query(url, "lb_code");
            if (code == null || code.isEmpty()) {
                return;
            }
            CookieManager.getInstance().setCookie(url, "lb_auth=" + code + "; path=/");
        } catch (Throwable ignored) {
        }
    }

    private static String query(String url, String name) {
        if (url == null) {
            return null;
        }
        int mark = url.indexOf('?');
        int end = url.indexOf('#');
        if (end < 0) {
            end = url.length();
        }
        if (mark < 0 || mark >= end) {
            return null;
        }
        String query = url.substring(mark + 1, end);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (name.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    static void remove(long id) {
        WryPage page = WryStack.take(id);
        if (page == null || page.view == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) page.view.getParent();
        if (parent != null) {
            parent.removeView(page.view);
        }
        WryInput.forget(page.view);
        page.view.destroy();
    }

    static void removeAll() {
        for (Long id : new ArrayList<>(WryStack.pages().keySet())) {
            remove(id);
        }
    }

    static String await(CountDownLatch latch, String[] outcome) {
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

    private WryPages() {
    }
}
