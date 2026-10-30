package liquidbounce.DroidBridge;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pages themselves: creating one, showing it and taking it down again.
 */
final class Pages {

    private static Handler main;

    private static String dataSuffix;

    private static volatile float clickguiScale = 1f;

    private static volatile float hudScale = 1f;

    private static volatile float settingsScale = 1f;

    static Handler main() {
        return main;
    }

    static void configure(final float clickgui, final float hud, final float settings) {
        clickguiScale = clickgui;
        hudScale = hud;
        settingsScale = settings;
        if (main == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                String script = Scripts.config(clickguiScale, hudScale, settingsScale)
                        + ";window.__wryScale&&window.__wryScale()";
                for (Page page : Stack.pages().values()) {
                    if (page.view != null) {
                        page.view.evaluateJavascript(script, null);
                    }
                }
            }
        });
    }

    static void start(Application app) {
        main = new Handler(Looper.getMainLooper());
    }

    private static final Pattern VIEWPORT = Pattern.compile(
            "(?i)<meta[^>]*name\\s*=\\s*[\"']viewport[\"'][^>]*>");

    /**
     * A desktop browser lays a page out in pixels of the framebuffer, which is the unit the theme
     * writes. The web view of Android lays it out in density independent pixels instead, so a panel
     * the theme makes 250 px wide arrives as many device pixels wide as the density says, the hud
     * components grow by that factor and the coordinates the theme derives from the viewport shrink
     * by it. Telling the page that it is as wide as the screen is what gives the theme back the unit
     * it was written for, and it is the only place where size and position can be fixed together.
     */
    private static int framebufferWidth(Activity activity) {
        try {
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            return width > 0 ? width : 1600;
        } catch (Throwable error) {
            return 1600;
        }
    }

    /**
     * Reads the page the game is about to show and hands it to the web view with the viewport of
     * the screen. Anything unexpected makes this load the page itself, which is what the web view
     * does without this hook, so a page that cannot be read still arrives unchanged.
     */
    private static WebResourceResponse withViewport(WebResourceRequest request, int viewport) {
        HttpURLConnection connection = null;
        try {
            String url = request.getUrl() == null ? null : request.getUrl().toString();
            if (url == null || !"GET".equalsIgnoreCase(request.getMethod())) {
                return null;
            }
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept-Encoding", "identity");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && cookie.length() > 0) {
                connection.setRequestProperty("Cookie", cookie);
            }
            if (connection.getResponseCode() != 200) {
                return null;
            }
            String type = connection.getContentType();
            if (type == null || type.toLowerCase().indexOf("html") < 0) {
                return null;
            }
            if (connection.getContentEncoding() != null) {
                return null;
            }
            keepResponseCookies(connection);
            String html = read(connection.getInputStream());
            String meta = "<meta name=\"viewport\" content=\"width=" + viewport
                    + ",user-scalable=no\">";
            Matcher matcher = VIEWPORT.matcher(html);
            String page;
            if (matcher.find()) {
                page = matcher.replaceFirst(Matcher.quoteReplacement(meta));
            } else {
                int head = html.toLowerCase().indexOf("<head>");
                if (head < 0) {
                    return null;
                }
                page = html.substring(0, head + 6) + meta + html.substring(head + 6);
            }
            return new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(page.getBytes("UTF-8")));
        } catch (Throwable error) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * The page is fetched here instead of by the web view, so the cookie the server sets on the way
     * has to be handed to the store as well, or the calls the page makes afterwards are anonymous.
     */
    private static void keepResponseCookies(HttpURLConnection connection) {
        try {
            CookieManager manager = CookieManager.getInstance();
            for (java.util.Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null || !"Set-Cookie".equalsIgnoreCase(header.getKey())) {
                    continue;
                }
                for (String value : header.getValue()) {
                    if (value != null && value.length() > 0) {
                        manager.setCookie(origin(connection.getURL().toString()), value);
                    }
                }
            }
            manager.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String read(InputStream stream) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            return buffer.toString("UTF-8");
        } finally {
            stream.close();
        }
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
            BridgeView view = new BridgeView(activity);
            view.setBackgroundColor(0x00000000);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setSupportZoom(false);
            view.getSettings().setUseWideViewPort(true);
            view.getSettings().setLoadWithOverviewMode(true);
            final int viewport = framebufferWidth(activity);
            view.setWebViewClient(new WebViewClient() {

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView target, WebResourceRequest request) {
                    if (request == null || !request.isForMainFrame()) {
                        return null;
                    }
                    return withViewport(request, viewport);
                }

                @Override
                public void onPageFinished(WebView target, String page) {
                    Page entry = Stack.page(id);
                    if (entry != null) {
                        entry.loaded = true;
                        entry.sent = false;
                    }
                    target.evaluateJavascript(
                            Scripts.config(clickguiScale, hudScale, settingsScale), null);
                    target.evaluateJavascript(Scripts.width(target.getWidth()), null);
                    target.evaluateJavascript(Scripts.DISPATCH_SETUP, null);
                    target.evaluateJavascript(Scripts.MASKS, null);
                    target.evaluateJavascript(Scripts.SCALE, null);
                    if (page != null && (page.contains("/hud") || page.contains("/clickgui"))) {
                        target.evaluateJavascript(Scripts.VIEWPORT, new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                android.util.Log.i("DroidBridge", "viewport " + value
                                        + " of " + target.getWidth());
                            }
                        });
                    }
                    target.evaluateJavascript(Scripts.FONTS, null);
                    Stack.awaitVisualState(target);
                }

                @Override
                public void onPageCommitVisible(WebView target, String page) {
                    Stack.awaitVisualState(target);
                }
            });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            activity.addContentView(view, params);
            int layer = Page.layerOfUrl(url);
            Stack.tag(view, id, layer);
            Page created = new Page(id, view, layer);
            Stack.put(created);
            String stacking = Stack.place(view);
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
                    + ", " + metrics(activity, viewport)
                    + ", url " + url;
        } catch (Throwable error) {
            Stack.put(new Page(id, null));
            return "browser " + id + " failed: " + error;
        }
    }

    /**
     * The width the theme is written in against the width the page lays itself out in. Printed with
     * every browser so the log says whether a page had to be compensated and by how much.
     */
    private static String metrics(Activity activity, int viewport) {
        try {
            float density = activity.getResources().getDisplayMetrics().density;
            return "framebuffer " + viewport + " density " + density;
        } catch (Throwable error) {
            return "framebuffer " + viewport + " density unknown";
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
            CookieManager manager = CookieManager.getInstance();
            manager.setAcceptCookie(true);
            manager.setCookie(origin(url), "lb_auth=" + code + "; path=/");
            manager.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String origin(String url) {
        if (url == null) {
            return "";
        }
        int mark = url.indexOf("://");
        if (mark < 0) {
            return url;
        }
        int slash = url.indexOf('/', mark + 3);
        return slash < 0 ? url : url.substring(0, slash);
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
        Page page = Stack.take(id);
        if (page == null || page.view == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) page.view.getParent();
        if (parent != null) {
            parent.removeView(page.view);
        }
        PointerInput.forget(page.view);
        page.view.destroy();
    }

    static void removeAll() {
        for (Long id : new ArrayList<>(Stack.pages().keySet())) {
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

    private Pages() {
    }
}
