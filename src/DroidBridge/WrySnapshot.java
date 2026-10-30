package liquidbounce.DroidBridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Draws a page into pixels so the game can put it on a texture of its own.
 *
 * A page that is stacked below the surface of the game is covered by it and therefore never seen,
 * which is exactly what the hud wants: the game then covers it the way it covers its own hud. The
 * view is still visible to the system, so it keeps painting, and the frames are read here on the
 * main thread and kept for the game to pick up.
 *
 * @see WryPage#texture
 */
final class WrySnapshot {

    /**
     * How often a page is read, in milliseconds. Drawing a page is a software draw of the whole
     * view, which costs more than a frame of the game, and the hud is mostly numbers that change
     * slowly.
     */
    private static final long INTERVAL_MS = 50L;

    /**
     * The width and the height as two little-endian shorts, the milliseconds the draw took, the number
     * of pixels that are not transparent and a little-endian read counter, then four bytes per texel
     * in RGBA order.
     */
    static final byte[] NONE = new byte[0];

    private static final int HEADER = 16;

    private static long reads = 0;

    private static Handler handler;

    private WrySnapshot() {
    }

    static void start() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
    }

    /**
     * Starts reading the page of [page] while [page] stays a texture page.
     */
    static void begin(final WryPage page) {
        if (page == null || !page.texture || page.ticking) {
            return;
        }
        page.ticking = true;
        start();
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!page.ticking) {
                    return;
                }
                page.frame = read(page);
                handler.postDelayed(this, INTERVAL_MS);
            }
        });
    }

    static void end(WryPage page) {
        if (page == null) {
            return;
        }
        page.ticking = false;
        page.frame = NONE;
    }

    /**
     * The last frame of [page], the header first.
     */
    static byte[] latest(WryPage page) {
        if (page == null || page.frame == null) {
            return NONE;
        }
        return page.frame;
    }

    private static byte[] read(WryPage page) {
        WryView view = page.view;
        if (view == null) {
            return NONE;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return NONE;
        }
        long begin = SystemClock.uptimeMillis();
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] rgba = new byte[width * height * 4 + HEADER];
            int painted = 0;
            for (int texel = 0; texel < pixels.length; texel++) {
                int argb = pixels[texel];
                if (argb != 0) {
                    painted++;
                }
                int base = HEADER + texel * 4;
                rgba[base] = (byte) ((argb >> 16) & 0xff);
                rgba[base + 1] = (byte) ((argb >> 8) & 0xff);
                rgba[base + 2] = (byte) (argb & 0xff);
                rgba[base + 3] = (byte) ((argb >> 24) & 0xff);
            }
            reads++;
            int took = (int) (SystemClock.uptimeMillis() - begin);
            rgba[0] = (byte) (width & 0xff);
            rgba[1] = (byte) ((width >> 8) & 0xff);
            rgba[2] = (byte) (height & 0xff);
            rgba[3] = (byte) ((height >> 8) & 0xff);
            long counter = reads;
            rgba[4] = (byte) (took & 0xff);
            rgba[5] = (byte) ((took >> 8) & 0xff);
            rgba[6] = (byte) ((took >> 16) & 0xff);
            rgba[7] = (byte) ((took >> 24) & 0xff);
            rgba[8] = (byte) (painted & 0xff);
            rgba[9] = (byte) ((painted >> 8) & 0xff);
            rgba[10] = (byte) ((painted >> 16) & 0xff);
            rgba[11] = (byte) ((painted >> 24) & 0xff);
            rgba[12] = (byte) (counter & 0xff);
            rgba[13] = (byte) ((counter >> 8) & 0xff);
            rgba[14] = (byte) ((counter >> 16) & 0xff);
            rgba[15] = (byte) ((counter >> 24) & 0xff);
            WryPages.metric(width, height, took, painted);
            return rgba;
        } catch (Throwable error) {
            return NONE;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

}
