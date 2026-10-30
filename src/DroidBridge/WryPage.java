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

    volatile float pendingX;

    volatile float pendingY;

    volatile boolean hoverPosted;

    long lastPress;

    float lastX;

    float lastY;

    int lastButton;

    int detail;

    boolean doubleClick;

    float sentX;

    float sentY;

    boolean sent;

    /**
     * Whether the page is drawn on a texture of the game instead of being stacked above it.
     */
    volatile boolean texture;

    /**
     * Whether the frames of the page are being read.
     */
    volatile boolean ticking;

    /**
     * The last frame that was read, the header of [WrySnapshot] first.
     */
    volatile byte[] frame;

    WryPage(long id, WryView view) {
        this(id, view, LAYER_HUD);
    }

    WryPage(long id, WryView view, int layer) {
        this.id = id;
        this.view = view;
        this.layer = layer;
    }

    boolean sameSpot(float x, float y) {
        return sent && sentX == x && sentY == y;
    }

    void remember(float x, float y) {
        sent = true;
        sentX = x;
        sentY = y;
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
