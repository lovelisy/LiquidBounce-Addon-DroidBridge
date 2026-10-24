package liquidbounce.DroidBridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The lines the bridge has to say. They are kept until the client asks for them, because the
 * render thread and the web view do not share a clock.
 */
final class WryLog {

    private static final int LIMIT = 200;

    private static final List<String> LINES = new CopyOnWriteArrayList<>();

    static void log(String text) {
        android.util.Log.i("WryBridge", text);
        if (LINES.size() < LIMIT) {
            LINES.add(text);
        }
    }

    static String drain() {
        if (LINES.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String line : LINES) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
        }
        LINES.clear();
        return text.toString();
    }

    private WryLog() {
    }
}
