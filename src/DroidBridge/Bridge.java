package liquidbounce.DroidBridge;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;

/**
 * The entry point of the native library.
 *
 * The library finds this class by name and calls the public static methods below, so their names
 * and signatures are part of its interface. Everything else lives in the classes next to it.
 */
public final class Bridge {

    public static String start(Application app, String suffix) {
        Pages.start(app);
        return Pages.prepareDataDirectory(app, suffix);
    }

    public static void configure(final float clickgui, final float hud, final float settings) {
        Pages.configure(clickgui, hud, settings);
    }

    public static String create(final Activity activity, final long id, final String url) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Pages.add(activity, id, url);
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Pages.main().post(new Runnable() {
            @Override
            public void run() {
                outcome[0] = Pages.add(activity, id, url);
                latch.countDown();
            }
        });
        return Pages.await(latch, outcome);
    }

    public static boolean loaded(final long id) {
        Page page = Stack.page(id);
        return page != null && (page.loaded || page.view == null);
    }

    public static String setVisible(final long id, final boolean visible) {
        Pages.main().post(new Runnable() {
            @Override
            public void run() {
                Page page = Stack.page(id);
                if (page == null || page.view == null) {
                    return;
                }
                page.wanted = visible;
                if (!visible) {
                    page.view.setVisibility(android.view.View.INVISIBLE);
                    return;
                }
                if (!page.loaded || !page.drawn) {
                    return;
                }
                Stack.show(page);
            }
        });
        return "browser " + id + " visibility " + visible;
    }

    public static void pointer(final long id, final float x, final float y, final int action,
                               final int button, final float amount) {
        final Page page = Stack.page(id);
        if (page == null || page.view == null) {
            return;
        }
        if (action == 0) {
            page.pendingX = x;
            page.pendingY = y;
            if (page.hoverPosted) {
                return;
            }
            page.hoverPosted = true;
            Pages.main().post(new Runnable() {
                @Override
                public void run() {
                    page.hoverPosted = false;
                    PointerInput.pointer(page, page.pendingX, page.pendingY, 0, 0, 0f);
                }
            });
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            PointerInput.pointer(page, x, y, action, button, amount);
            return;
        }
        Pages.main().post(new Runnable() {
            @Override
            public void run() {
                PointerInput.pointer(page, x, y, action, button, amount);
            }
        });
    }

    public static void key(final long id, final int keycode, final boolean pressed) {
        Pages.main().post(new Runnable() {
            @Override
            public void run() {
                Page page = Stack.page(id);
                if (page == null || page.view == null) {
                    return;
                }
                PointerInput.key(page, keycode, pressed);
            }
        });
    }

    public static void text(final long id, final String value) {
        Pages.main().post(new Runnable() {
            @Override
            public void run() {
                Page page = Stack.page(id);
                if (page == null || page.view == null) {
                    return;
                }
                PointerInput.text(page, value);
            }
        });
    }

    public static void enter(final long id) {
        Pages.main().post(new Runnable() {
            @Override
            public void run() {
                Page page = Stack.page(id);
                if (page == null || page.view == null || page.view.getVisibility() != android.view.View.VISIBLE) {
                    return;
                }
                PointerInput.enter(page);
            }
        });
    }

    public static String close(final long id) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Pages.remove(id);
            return "browser " + id + " closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Pages.main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Pages.remove(id);
                    outcome[0] = "browser " + id + " closed";
                } catch (Throwable error) {
                    outcome[0] = "closing browser " + id + " failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return Pages.await(latch, outcome);
    }

    public static String closeAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Pages.removeAll();
            return "all browsers closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Pages.main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Pages.removeAll();
                    outcome[0] = "all browsers closed";
                } catch (Throwable error) {
                    outcome[0] = "closing the browsers failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return Pages.await(latch, outcome);
    }

    private Bridge() {
    }
}
