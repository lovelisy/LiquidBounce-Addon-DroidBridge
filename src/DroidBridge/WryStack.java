package liquidbounce.DroidBridge;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a page sits in the view tree of the launcher.
 *
 * The pages are separate views of the system that are stacked above the surface of the game, and
 * the one that was shown last used to end up at the bottom. A screen sits above the hud, so the
 * order is kept here rather than in the order the pages happen to be shown in.
 */
final class WryStack {

    private static final int TAG_ID = 0x7f0d0001;

    private static final int TAG_LAYER = 0x7f0d0002;

    private static final Map<Long, WryPage> PAGES = new ConcurrentHashMap<>();

    static Map<Long, WryPage> pages() {
        return PAGES;
    }

    static WryPage page(long id) {
        return PAGES.get(id);
    }

    static void put(WryPage page) {
        PAGES.put(page.id, page);
    }

    static WryPage take(long id) {
        return PAGES.remove(id);
    }

    static void tag(View view, long id, int layer) {
        view.setTag(TAG_ID, Long.valueOf(id));
        view.setTag(TAG_LAYER, Integer.valueOf(layer));
    }

    static int layerOf(View view) {
        Object tag = view.getTag(TAG_LAYER);
        return tag instanceof Integer ? ((Integer) tag).intValue() : WryPage.LAYER_HUD;
    }

    /**
     * Keeps the page below the launcher's own overlays (cursor, buttons, menu) by stacking it right
     * above the game surface. The surface lives deep inside the Compose tree, so the whole tree is
     * searched and the page is inserted into the same container, right after it.
     */
    static String place(View view) {
        ViewParent parent = view.getParent();
        if (!(parent instanceof ViewGroup)) {
            return "not attached";
        }
        View surface = findSurface((ViewGroup) parent, view);
        if (surface == null) {
            return "no game surface, kept in " + parent.getClass().getName();
        }
        ViewParent surfaceParent = surface.getParent();
        if (!(surfaceParent instanceof ViewGroup)) {
            return "surface has no container";
        }
        ViewGroup holder = (ViewGroup) surfaceParent;
        int wanted = slotFor(holder, surface, view);
        if (holder == parent && holder.indexOfChild(view) == wanted) {
            return "already above the surface";
        }
        if (holder != parent) {
            ((ViewGroup) parent).removeView(view);
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        holder.removeView(view);
        holder.addView(view, Math.min(wanted, holder.getChildCount()), params);
        fit(holder, view);
        return "stacked at " + holder.indexOfChild(view) + " of " + holder.getChildCount()
                + " in " + holder.getClass().getName();
    }

    /**
     * A page belongs above the surface and above every page of a lower layer, but below every page
     * of a higher one, so the hud never covers a screen and a screen never covers its own input.
     */
    private static int slotFor(ViewGroup holder, View surface, View view) {
        int layer = layerOf(view);
        int slot = holder.indexOfChild(surface) + 1;
        for (int index = slot; index < holder.getChildCount(); index++) {
            View child = holder.getChildAt(index);
            if (child == view) {
                continue;
            }
            if (!(child instanceof WryView)) {
                continue;
            }
            if (layerOf(child) > layer) {
                break;
            }
            slot = index + 1;
        }
        return slot;
    }

    private static View findSurface(ViewGroup group, View skip) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child == skip) {
                continue;
            }
            String name = child.getClass().getName();
            if (name.contains("TextureView") || name.contains("SurfaceView")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findSurface((ViewGroup) child, skip);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Compose only lays out the views it knows about, so the page has to size itself after whatever
     * container it ends up in.
     */
    private static void fit(final ViewGroup holder, final View view) {
        if (view.getTag() == holder) {
            return;
        }
        view.setTag(holder);
        holder.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View target, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (view.getParent() == holder) {
                    resize(view, right - left, bottom - top);
                }
            }
        });
        resize(view, holder.getWidth(), holder.getHeight());
    }

    private static void resize(View view, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (view.getWidth() == width && view.getHeight() == height) {
            return;
        }
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    /**
     * A page that is only shown once the fonts are there still shows nothing for a moment, so the
     * text is kept invisible until then instead of being painted and swapped.
     *
     * A post after the page finished loading runs on the next loop turn, which is before the system
     * has drawn the page: the first frame that appears is then a half composed one, with the white
     * blocks of elements that have a color but no masked image yet. The visual state callback is
     * raised by the compositor once the page is ready to be shown.
     */
    static void awaitVisualState(final android.webkit.WebView view) {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            markPainted(view);
            return;
        }
        view.postVisualStateCallback(System.nanoTime(), new android.webkit.WebView.VisualStateCallback() {
            @Override
            public void onComplete(long requestId) {
                awaitFonts(view);
            }
        });
    }

    /**
     * The compositor reports a frame as drawn even while the fonts are still on their way, and the
     * text of that frame is then laid out with a fallback font. The swap leaves the white blocks of
     * the old text behind, so the fonts have to be in before the page is allowed on screen. The page
     * loads them on its own, this only waits for the flag that the injection sets.
     */
    private static void awaitFonts(final android.webkit.WebView view) {
        pollFonts(view, 0);
    }

    private static void pollFonts(final android.webkit.WebView view, final int attempt) {
        if (attempt > 40) {
            markPainted(view);
            return;
        }
        view.evaluateJavascript(
                "window.__wryFonts===true||!document.fonts",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equals(value)) {
                            markPainted(view);
                            return;
                        }
                        view.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                pollFonts(view, attempt + 1);
                            }
                        }, 50);
                    }
                });
    }

    private static void markPainted(android.webkit.WebView view) {
        Object tag = view.getTag(TAG_ID);
        if (!(tag instanceof Long)) {
            return;
        }
        WryPage entry = PAGES.get((Long) tag);
        if (entry == null || entry.drawn) {
            return;
        }
        entry.drawn = true;
        show(entry);
    }

    /**
     * Shows the page only once it has painted, so the frame that appears is a page and not a blank
     * view.
     */
    static void show(WryPage page) {
        if (!page.wanted || page.view == null || page.view.getVisibility() == View.VISIBLE) {
            return;
        }
        page.view.setVisibility(View.VISIBLE);
        place(page.view);
    }

    private WryStack() {
    }
}
