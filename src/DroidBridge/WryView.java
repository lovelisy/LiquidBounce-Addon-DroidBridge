package liquidbounce.DroidBridge;

import android.content.Context;
import android.view.MotionEvent;
import android.webkit.WebView;

/**
 * A page that only takes the events the bridge hands to it, so the game keeps seeing everything
 * else.
 */
final class WryView extends WebView {

    private boolean injecting;

    WryView(Context context) {
        super(context);
    }

    boolean inject(MotionEvent event) {
        injecting = true;
        try {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) {
                return dispatchGenericMotionEvent(event);
            }
            return dispatchTouchEvent(event);
        } finally {
            injecting = false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return injecting && super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return injecting && super.dispatchGenericMotionEvent(event);
    }
}
