package net.ccbluex.liquidbounce.wry.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WryBridge {

    /**
     * Chrome 116 drops the unprefixed mask properties, which the theme uses to paint the menu icons.
     * The icons are drawn as a masked background, while an invisible image inside the same element
     * already carries the resolved source, so that source is what the prefixed form is built from.
     */
    private static final String MASKS =
            "(function(){"
                    + "if(window.__wryMask){return 'already';}"
                    + "window.__wryMask=true;"
                    + "var style=document.createElement('style');"
                    + "style.textContent='.title-button-icon{-webkit-mask-position:center!important;"
                    + "-webkit-mask-repeat:no-repeat!important;-webkit-mask-size:contain!important;}';"
                    + "document.head.appendChild(style);"
                    + "var fixed=0;"
                    + "function fromImage(el){"
                    + "var img=el.querySelector('img');"
                    + "if(!img||!img.src){return false;}"
                    + "if(el.__wryMasked){return false;}"
                    + "el.style.webkitMaskImage='url(\"'+img.src+'\")';"
                    + "el.__wryMasked=true;"
                    + "return true;"
                    + "}"
                    + "function fromStyle(el){"
                    + "if(el.__wryMasked){return false;}"
                    + "var raw=el.getAttribute('style');"
                    + "if(!raw||raw.indexOf('mask')<0){return false;}"
                    + "var pattern=/(^|;)\\s*mask-(image|position|repeat|size|origin|clip)\\s*:\\s*([^;]+)/g;"
                    + "var match;"
                    + "var found=false;"
                    + "while((match=pattern.exec(raw))!==null){"
                    + "var value=match[3].trim().replace(/([^:])\\/{2,}/g,'$1/');"
                    + "el.style.setProperty('-webkit-mask-'+match[2],value);"
                    + "found=true;"
                    + "}"
                    + "if(found){el.__wryMasked=true;}"
                    + "return found;"
                    + "}"
                    + "function mirror(el){"
                    + "if(!el||el.nodeType!==1){return;}"
                    + "var done=fromImage(el)||fromStyle(el);"
                    + "if(done){fixed++;}"
                    + "}"
                    + "function scan(root){"
                    + "if(!root.querySelectorAll){return;}"
                    + "var icons=root.querySelectorAll('.title-button-icon');"
                    + "for(var i=0;i<icons.length;i++){mirror(icons[i]);}"
                    + "var styled=root.querySelectorAll('[style*=\"mask\"]');"
                    + "for(var j=0;j<styled.length;j++){mirror(styled[j]);}"
                    + "}"
                    + "var observer=new MutationObserver(function(records){"
                    + "for(var i=0;i<records.length;i++){"
                    + "var record=records[i];"
                    + "if(record.type==='attributes'){mirror(record.target);continue;}"
                    + "var added=record.addedNodes;"
                    + "for(var j=0;j<added.length;j++){"
                    + "if(added[j].nodeType===1){mirror(added[j]);scan(added[j]);}"
                    + "}"
                    + "}"
                    + "});"
                    + "observer.observe(document.body,{childList:true,subtree:true,"
                    + "attributes:true,attributeFilter:['style']});"
                    + "scan(document.body);"
                    + "console.log('[Wry] mask rewrite on '+fixed+' elements');"
                    + "return 'masked '+fixed;"
                    + "})()";

    private static final String PROBE =
            "JSON.stringify((function(){"
                    + "var icons=document.getElementsByClassName('title-button-icon');"
                    + "var first=icons.length?icons[0]:null;"
                    + "var computed=first?getComputedStyle(first):null;"
                    + "var inline=first?first.getAttribute('style'):null;"
                    + "var canvas=document.getElementsByTagName('canvas');"
                    + "var gl=null;"
                    + "try{gl=!!document.createElement('canvas').getContext('webgl2');}catch(e){gl='error';}"
                    + "return {ua:navigator.userAgent,icons:icons.length,"
                    + "inline:inline,"
                    + "computedMask:computed?computed.maskImage:'',"
                    + "computedWebkitMask:computed?computed.webkitMaskImage:'',"
                    + "size:computed?computed.maskSize+'/'+computed.webkitMaskSize:'',"
                    + "bg:computed?computed.backgroundColor:'',"
                    + "rect:first?JSON.stringify(first.getBoundingClientRect()):'',"
                    + "canvases:canvas.length,webgl2:gl};"
                    + "})())";

    private static final Map<Long, Page> PAGES = new ConcurrentHashMap<>();
    private static final java.util.List<String> LOGS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static Handler main;
    private static String dataSuffix;

    static void log(String text) {
        android.util.Log.i("WryBridge", text);
        if (LOGS.size() < 200) {
            LOGS.add(text);
        }
    }

    public static String drainLogs() {
        if (LOGS.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String line : LOGS) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
        }
        LOGS.clear();
        return text.toString();
    }

    private WryBridge() {
    }

    public static String start(Application app, String suffix) {
        main = new Handler(Looper.getMainLooper());
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

    public static String create(final Activity activity, final long id, final String url) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return add(activity, id, url);
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                outcome[0] = add(activity, id, url);
                latch.countDown();
            }
        });
        return await(latch, outcome);
    }

    private static String add(Activity activity, long id, String url) {
        try {
            remove(id);
            long begin = System.nanoTime();
            OverlayWebView view = new OverlayWebView(activity);
            view.setBackgroundColor(0x00000000);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setSupportZoom(false);
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView target, String page) {
                    Page entry = PAGES.get(id);
                    if (entry != null) {
                        entry.loaded = true;
                    }
                    target.evaluateJavascript(MASKS, null);
                    target.evaluateJavascript(PROBE, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            log("page probe " + value);
                        }
                    });
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView target, android.webkit.WebResourceRequest request) {
                    return false;
                }
            });
            view.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                    if (message.messageLevel() != android.webkit.ConsoleMessage.MessageLevel.LOG) {
                        log("console " + message.messageLevel() + " " + message.message()
                                + " @" + message.sourceId() + ":" + message.lineNumber());
                    }
                    return true;
                }
            });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            activity.addContentView(view, params);
            String stacking = place(view);
            view.setVisibility(View.INVISIBLE);
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setOnFocusChangeListener(null);
            PAGES.put(id, new Page(id, view));
            view.loadUrl(url);
            long took = (System.nanoTime() - begin) / 1_000_000L;
            ViewGroup parent = (ViewGroup) view.getParent();
            return "browser " + id + " added in " + took + " ms on " + Thread.currentThread().getName()
                    + ", provider " + WebView.getCurrentWebViewPackage()
                    + ", parent " + (parent == null ? "none" : parent.getClass().getName())
                    + ", order " + (parent == null ? -1 : parent.indexOfChild(view)) + " of " + (parent == null ? -1 : parent.getChildCount())
                    + ", stacking " + stacking
                    + ", url " + url;
        } catch (Throwable error) {
            PAGES.put(id, new Page(id, null));
            return "browser " + id + " failed: " + error;
        }
    }

    /**
     * Keeps the page below the launcher's own overlays (cursor, buttons, menu) by stacking it right above
     * the game surface. The surface lives deep inside the Compose tree, so the whole tree is searched and
     * the page is inserted into the same container, right after it.
     */
    private static String place(View view) {
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
        int wanted = holder.indexOfChild(surface) + 1;
        if (holder == parent) {
            if (holder.indexOfChild(view) == wanted) {
                return "already above the surface";
            }
        } else {
            ((ViewGroup) parent).removeView(view);
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        holder.removeView(view);
        holder.addView(view, Math.min(wanted, holder.getChildCount()), params);
        fit(holder, view);
        return "stacked at " + holder.indexOfChild(view) + " of " + holder.getChildCount()
                + " in " + holder.getClass().getName();
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

    public static boolean loaded(final long id) {
        Page page = PAGES.get(id);
        return page != null && (page.loaded || page.view == null);
    }

    public static String setVisible(final long id, final boolean visible) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Page page = PAGES.get(id);
                if (page == null || page.view == null) {
                    return;
                }
                page.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                if (visible) {
                    String stacking = place(page.view);
                    if (!"already above the surface".equals(stacking)) {
                        log("restacked browser " + id + ": " + stacking);
                    }
                    page.view.requestFocus();
                }
            }
        });
        return "browser " + id + " visibility " + visible;
    }

    public static void pointer(final long id, final float x, final float y, final int action,
                               final int button, final float amount) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Page page = PAGES.get(id);
                if (page == null || page.view == null) {
                    return;
                }
                if (action == 0 && !page.pressed) {
                    page.pendingX = x;
                    page.pendingY = y;
                    if (!page.hoverPosted) {
                        page.hoverPosted = true;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                Page current = PAGES.get(id);
                                if (current == null || current.view == null) {
                                    return;
                                }
                                current.hoverPosted = false;
                                apply(current, current.pendingX, current.pendingY, 0, 0, 0f);
                            }
                        });
                    }
                    return;
                }
                apply(page, x, y, action, button, amount);
            }
        });
    }

    private static void apply(Page page, float x, float y, int action, int button, float amount) {
        OverlayWebView view = page.view;
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        if (action == 1) {
            page.pressed = true;
            page.pressedButton = button;
            page.hovers = 0;
            dom(page, view, x, y, "down", button);
            return;
        }
        if (action == 2) {
            int released = page.pressedButton != 0 ? page.pressedButton : button;
            page.pressed = false;
            page.pressedButton = 0;
            dom(page, view, x, y, "up", released);
            return;
        }
        if (action == 3) {
            scroll(page, view, x, y, amount);
            return;
        }
        if (page.pressed) {
            dom(page, view, x, y, "move", page.pressedButton);
            return;
        }
        hover(page, view, x, y);
    }

    private static void hover(Page page, OverlayWebView view, float x, float y) {
        boolean accepted = mouse(view, MotionEvent.ACTION_HOVER_MOVE, x, y, 0f);
        if (page.hovers > 3) {
            return;
        }
        page.hovers++;
        log("pointer browser " + page.id + " hover accepted " + accepted
                + " at " + (x * view.getWidth()) + "," + (y * view.getHeight())
                + " size " + view.getWidth() + "x" + view.getHeight());
    }

    private static void scroll(Page page, OverlayWebView view, float x, float y, float amount) {
        boolean accepted = mouse(view, MotionEvent.ACTION_SCROLL, x, y, amount);
        log("pointer browser " + page.id + " scroll " + amount + " accepted " + accepted);
    }

    private static boolean mouse(OverlayWebView view, int type, float x, float y, float amount) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x * view.getWidth();
        coords.y = y * view.getHeight();
        if (type == MotionEvent.ACTION_SCROLL) {
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, -amount);
        }
        MotionEvent event = MotionEvent.obtain(now, now, type, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        if (!view.isFocused()) {
            view.requestFocus();
        }
        boolean accepted = view.inject(event);
        event.recycle();
        return accepted;
    }

    /**
     * A motion event cannot carry the button that was released, so Chromium never reports a click for
     * one. The page is driven directly instead, where the coordinates are taken as fractions of the
     * viewport and the target is resolved by hit testing.
     */
    private static void dom(Page page, OverlayWebView view, final float x, final float y,
                            final String phase, int button) {
        if (!view.isFocused()) {
            view.requestFocus();
        }
        int domButton = button == 2 ? 2 : (button == 3 ? 1 : 0);
        int bit = domButton == 2 ? 2 : (domButton == 1 ? 4 : 1);
        boolean down = "down".equals(phase);
        boolean up = "up".equals(phase);

        StringBuilder script = new StringBuilder("(function(){")
                .append("var x=").append(x).append("*window.innerWidth,")
                .append("y=").append(y).append("*window.innerHeight;")
                .append("var t=document.elementFromPoint(x,y)||document.body;")
                .append("var o={bubbles:true,cancelable:true,view:window,button:").append(domButton)
                .append(",buttons:").append(up ? 0 : bit)
                .append(",clientX:x,clientY:y,screenX:x,screenY:y")
                .append(",pointerId:1,pointerType:'mouse',isPrimary:true,width:1,height:1,pressure:")
                .append(up ? "0" : "0.5").append("};");
        if (down) {
            script.append("if(t.focus&&(t.tagName=='INPUT'||t.tagName=='TEXTAREA'||t.isContentEditable)){t.focus();}");
            script.append("t.dispatchEvent(new PointerEvent('pointerdown',o));")
                    .append("t.dispatchEvent(new MouseEvent('mousedown',o));");
        } else if (up) {
            script.append("t.dispatchEvent(new PointerEvent('pointerup',o));")
                    .append("t.dispatchEvent(new MouseEvent('mouseup',o));");
            if (domButton == 2) {
                script.append("t.dispatchEvent(new MouseEvent('contextmenu',o));");
            } else {
                script.append("t.dispatchEvent(new MouseEvent('click',o));");
            }
        } else {
            script.append("t.dispatchEvent(new PointerEvent('pointermove',o));")
                    .append("t.dispatchEvent(new MouseEvent('mousemove',o));");
        }
        script.append("return t.tagName;})( )");

        view.evaluateJavascript(script.toString(), new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                log("pointer browser " + page.id + " " + phase + " at "
                        + (x * view.getWidth()) + "," + (y * view.getHeight())
                        + " on " + value + " button " + button);
            }
        });
    }

    public static void key(final long id, final int keycode, final boolean pressed) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Page page = PAGES.get(id);
                if (page == null || page.view == null || page.view.getVisibility() != View.VISIBLE) {
                    return;
                }
                int code = keycode(keycode);
                if (code == 0) {
                    return;
                }
                page.view.dispatchKeyEvent(new KeyEvent(
                        pressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                        code));
            }
        });
    }

    public static String close(final long id) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove(id);
            return "browser " + id + " closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    remove(id);
                    outcome[0] = "browser " + id + " closed";
                } catch (Throwable error) {
                    outcome[0] = "closing browser " + id + " failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return await(latch, outcome);
    }

    public static String closeAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeAll();
            return "all browsers closed";
        }
        final String[] outcome = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    removeAll();
                    outcome[0] = "all browsers closed";
                } catch (Throwable error) {
                    outcome[0] = "closing the browsers failed: " + error;
                } finally {
                    latch.countDown();
                }
            }
        });
        return await(latch, outcome);
    }

    private static void remove(long id) {
        Page page = PAGES.remove(id);
        if (page == null || page.view == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) page.view.getParent();
        if (parent != null) {
            parent.removeView(page.view);
        }
        page.view.destroy();
    }

    private static void removeAll() {
        for (Long id : new ArrayList<>(PAGES.keySet())) {
            remove(id);
        }
    }

    private static int keycode(int glfw) {
        switch (glfw) {
            case 256:
                return KeyEvent.KEYCODE_ESCAPE;
            case 262:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 263:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case 264:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case 265:
                return KeyEvent.KEYCODE_DPAD_UP;
            case 266:
                return KeyEvent.KEYCODE_PAGE_UP;
            case 267:
                return KeyEvent.KEYCODE_PAGE_DOWN;
            default:
                return 0;
        }
    }

    private static String await(CountDownLatch latch, String[] outcome) {
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

    private static final class Page {

        final OverlayWebView view;
        final long id;
        volatile boolean loaded;
        boolean pressed;
        int pressedButton;
        int hovers;
        float pendingX;
        float pendingY;
        boolean hoverPosted;

        Page(long id, OverlayWebView view) {
            this.id = id;
            this.view = view;
        }
    }

    private static final class OverlayWebView extends WebView {

        private boolean injecting;

        OverlayWebView(Context context) {
            super(context);
        }

        void inject(MotionEvent event) {
            injecting = true;
            try {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) {
                    dispatchGenericMotionEvent(event);
                } else {
                    dispatchTouchEvent(event);
                }
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
}
