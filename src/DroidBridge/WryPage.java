package liquidbounce.DroidBridge;

/**
 * A page in the window of the game. It never leaves the main thread, except for the flags the
 * render thread reads to decide whether the page may be shown.
 */
final class WryPage {

    static final int LAYER_HUD = 0;

    static final int LAYER_SCREEN = 1;

    final WryView view;

    final long id;

    final int layer;

    volatile boolean loaded;

    volatile boolean drawn;

    volatile boolean wanted;

    boolean pressed;

    int pressedButton;

    long pressedDownTime;

    volatile float pendingX;

    volatile float pendingY;

    volatile boolean hoverPosted;

    WryPage(long id, WryView view) {
        this(id, view, LAYER_HUD);
    }

    WryPage(long id, WryView view, int layer) {
        this.id = id;
        this.view = view;
        this.layer = layer;
    }

    /**
     * The hud is the only page of the theme that belongs to the world. Every other page is a screen
     * and covers it, so the url is what tells them apart.
     */
    static int layerOfUrl(String url) {
        if (url == null) {
            return LAYER_HUD;
        }
        if (url.contains("/hud") || url.contains("/inventory")) {
            return LAYER_HUD;
        }
        return LAYER_SCREEN;
    }
}
