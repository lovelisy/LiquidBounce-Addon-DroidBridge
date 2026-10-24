package liquidbounce.DroidBridge;

import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the scale patch into the page before the theme boots.
 *
 * The patch has to run before the theme reads the window api, so it cannot be evaluated after the
 * page has loaded: the theme is already running by then. The html of the theme is therefore read
 * here and the patch is written into its head, which the browser runs first.
 */
final class WryWindow {

    private static final int TIMEOUT = 5000;

    /**
     * The html of the theme with the patch in its head, or nothing when the request is not the one
     * of the page or the page cannot be read.
     */
    static WebResourceResponse rewrite(String url) {
        if (url == null || !url.startsWith("http")) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            String html = inject(read(connection.getInputStream()));
            if (html == null) {
                return null;
            }
            byte[] data = html.getBytes("UTF-8");
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/html");
            headers.put("Cache-Control", "no-store");
            keepCookie(connection, url, headers);
            return new WebResourceResponse("text/html", "utf-8", HttpURLConnection.HTTP_OK,
                    "OK", headers, new ByteArrayInputStream(data));
        } catch (Throwable error) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * The patch goes right after the head tag, so it runs before any script of the theme.
     */
    private static String inject(String html) {
        int head = html.indexOf("<head");
        if (head < 0) {
            return null;
        }
        int end = html.indexOf('>', head);
        if (end < 0) {
            return null;
        }
        return html.substring(0, end + 1)
                + "<script>" + WryScripts.PATCH + "</script>"
                + html.substring(end + 1);
    }

    /**
     * The theme authorises the page with a cookie that the server sets on the document request. The
     * cookie is read here and handed to the web view, once through the response and once through the
     * cookie manager, because a web view that rereads a page on its own must still send it on every
     * later request for the assets.
     */
    private static void keepCookie(HttpURLConnection connection, String url, Map<String, String> headers) {
        try {
            String cookie = firstCookie(connection);
            if (cookie == null) {
                return;
            }
            headers.put("Set-Cookie", cookie);
            CookieManager manager = CookieManager.getInstance();
            manager.setCookie(url, cookie);
        } catch (Throwable ignored) {
        }
    }

    private static String firstCookie(HttpURLConnection connection) {
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                return values.get(0);
            }
        }
        return null;
    }

    private static String read(InputStream stream) throws Exception {
        try {
            byte[] buffer = new byte[4096];
            StringBuilder text = new StringBuilder();
            int read;
            while ((read = stream.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, "UTF-8"));
            }
            return text.toString();
        } finally {
            stream.close();
        }
    }

    private WryWindow() {
    }
}
