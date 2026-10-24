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
public final class WryBridge {

    public static String start(Application app, String suffix) {
        WryPages.start(app);
        return WryPages.prepareDataDirectory(app, suffix);
    }

    public static String create(final Activity activity, final long id, final String url) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return WryPages.add(activity, id, url);
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        WryPages.main().post(new Runnable() {
            @Override
            public void run() {
                outcome[0] = WryPages.add(activity, id, url);
                latch.countDown();
            }
        });
        return WryPages.await(latch, outcome);
    }

    public static boolean loaded(final long id) {
        WryPage page = WryStack.page(id);
        return page != null && (page.loaded || page.view == null);
    }

    public static String setVisible(final long id, final boolean visible) {
        WryPages.main().post(new Runnable() {
            @Override
            public void run() {
                WryPage page = WryStack.page(id);
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
                WryStack.show(page);
            }
        });
        return "browser " + id + " visibility " + visible;
    }

    public static void pointer(final long id, final float x, final float y, final int action,
                               final int button, final float amount) {
        final WryPage page = WryStack.page(id);
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
            WryPages.main().post(new Runnable() {
                @Override
                public void run() {
                    page.hoverPosted = false;
                    WryInput.pointer(page, page.pendingX, page.pendingY, 0, 0, 0f);
                }
            });
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            WryInput.pointer(page, x, y, action, button, amount);
            return;
        }
        WryPages.main().post(new Runnable() {
            @Override
            public void run() {
                WryInput.pointer(page, x, y, action, button, amount);
            }
        });
    }

    public static void key(final long id, final int keycode, final boolean pressed) {
        WryPages.main().post(new Runnable() {
            @Override
            public void run() {
                WryPage page = WryStack.page(id);
                if (page == null || page.view == null) {
                    return;
                }
                WryInput.key(page, keycode, pressed);
            }
        });
    }

    public static void text(final long id, final String value) {
        WryPages.main().post(new Runnable() {
            @Override
            public void run() {
                WryPage page = WryStack.page(id);
                if (page == null || page.view == null) {
                    return;
                }
                WryInput.text(page, value);
            }
        });
    }

    public static void enter(final long id) {
        WryPages.main().post(new Runnable() {
            @Override
            public void run() {
                WryPage page = WryStack.page(id);
                if (page == null || page.view == null || page.view.getVisibility() != android.view.View.VISIBLE) {
                    return;
                }
                WryInput.enter(page);
            }
        });
    }

    public static String close(final long id) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            WryPages.remove(id);
            return "browser " + id + " closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        WryPages.main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    WryPages.remove(id);
                    outcome[0] = "browser " + id + " closed";
                } catch (Throwable error) {
                    outcome[0] = "closing browser " + id + " failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return WryPages.await(latch, outcome);
    }

    public static String closeAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            WryPages.removeAll();
            return "all browsers closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        WryPages.main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    WryPages.removeAll();
                    outcome[0] = "all browsers closed";
                } catch (Throwable error) {
                    outcome[0] = "closing the browsers failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return WryPages.await(latch, outcome);
    }

    public static String drainLogs() {
        return WryLog.drain();
    }

    private WryBridge() {
    }
}
