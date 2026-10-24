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
                    + "if(!el.classList||!el.classList.contains('title-button-icon')){return false;}"
                    + "if(el.__wryMasked){return false;}"
                    + "var img=el.querySelector('img');"
                    + "if(!img||!img.src){return false;}"
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
                    + "var seen={};"
                    + "while((match=pattern.exec(raw))!==null){"
                    + "var value=match[3].trim().replace(/([^:])\\/{2,}/g,'$1/');"
                    + "el.style.setProperty('-webkit-mask-'+match[2],value);"
                    + "seen[match[2]]=true;"
                    + "found=true;"
                    + "}"
                    + "if(found){"
                    + "if(!seen.repeat){el.style.setProperty('-webkit-mask-repeat','no-repeat');}"
                    + "if(!seen.size){el.style.setProperty('-webkit-mask-size','contain');}"
                    + "if(!seen.position){el.style.setProperty('-webkit-mask-position','center');}"
                    + "el.__wryMasked=true;"
                    + "}"
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
                    + "console.log('[DroidBridge] mask rewrite on '+fixed+' elements');"
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

    /**
     * Stays in the page, so a pointer event only sends its coordinates instead of the whole handler.
     * Coordinates are fractions of the viewport, as everywhere else in the bridge.
     */
    private static final String DISPATCH_DEFINE =
            "window.__wry=function(phase,fx,fy,btn,detail,bit){"
                    + "var bx=fx*window.innerWidth,by=fy*window.innerHeight;"
                    + "var t=document.elementFromPoint(bx,by)||document.body;"
                    + "var o={bubbles:true,cancelable:true,view:window,button:btn,buttons:bit,"
                    + "clientX:bx,clientY:by,screenX:bx,screenY:by,detail:detail,"
                    + "pointerId:1,pointerType:'mouse',isPrimary:true,width:1,height:1,"
                    + "pressure:phase==='up'?0:0.5};"
                    + "if(phase==='down'){"
                    + "var target=t;"
                    + "while(target&&target!==document.body){"
                    + "if(target.tagName==='INPUT'||target.tagName==='TEXTAREA'" 
                    + "||target.isContentEditable||target.getAttribute('contenteditable')==='true'){break;}"
                    + "target=target.parentElement;}"
                    + "if(target&&target!==document.body&&target.focus){"
                    + "try{target.focus();}catch(e){}}"
                    + "t.dispatchEvent(new PointerEvent('pointerdown',o));"
                    + "t.dispatchEvent(new MouseEvent('mousedown',o));"
                    + "}else if(phase==='up'){"
                    + "t.dispatchEvent(new PointerEvent('pointerup',o));"
                    + "t.dispatchEvent(new MouseEvent('mouseup',o));"
                    + "if(btn===2){t.dispatchEvent(new MouseEvent('contextmenu',o));}"
                    + "else{t.dispatchEvent(new MouseEvent('click',o));"
                    + "if(detail>1){t.dispatchEvent(new MouseEvent('dblclick',o));}}"
                    + "}else{"
                    + "t.dispatchEvent(new PointerEvent('pointermove',o));"
                    + "t.dispatchEvent(new MouseEvent('mousemove',o));}"
                    + "return t.tagName;};";

    private static final String DISPATCH =
            "(function(){"
                    + "if(window.__wry){return 'already';}"
                    + DISPATCH_DEFINE
                    + "return 'ready';})()";

    /**
     * A wheel event of its own, because the axis of a scroll event means something else to the web view.
     */
    private static final String WHEEL_DEFINE =
            "window.__wryWheel=function(fx,fy,dy){"
                    + "var bx=fx*window.innerWidth,by=fy*window.innerHeight;"
                    + "var t=document.elementFromPoint(bx,by)||document.body;"
                    + "var o={bubbles:true,cancelable:true,view:window,deltaX:0,deltaY:dy,deltaMode:0,"
                    + "clientX:bx,clientY:by,screenX:bx,screenY:by};"
                    + "try{t.dispatchEvent(new WheelEvent('wheel',o));}catch(e){return 'no wheel';}"
                    + "return t.tagName;};";

    private static final String WHEEL =
            "(function(){"
                    + "if(window.__wryWheel){return 'already';}"
                    + WHEEL_DEFINE
                    + "return 'ready';})()";

    /**
     * The body of the typing handler, called with the text that was typed.
     */
    private static final String INSERT_BODY =
            "var e=document.activeElement;"
                    + "if(!e||e===document.body){return 'no field';}"
                    + "var editable=e.tagName==='INPUT'||e.tagName==='TEXTAREA'"
                    + "||e.isContentEditable||e.getAttribute('contenteditable')==='true';"
                    + "if(!editable){return 'not editable';}"
                    + "if(e.isContentEditable||e.getAttribute('contenteditable')==='true'){e.textContent+=text;}"
                    + "else{"
                    + "var start=e.selectionStart;"
                    + "var end=e.selectionEnd;"
                    + "if(typeof start==='number'){"
                    + "e.value=e.value.slice(0,start)+text+e.value.slice(end);"
                    + "e.selectionStart=start+text.length;"
                    + "e.selectionEnd=start+text.length;"
                    + "}else{e.value+=text;}}"
                    + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "return e.tagName;";

    private static final Map<Long, Page> PAGES = new ConcurrentHashMap<>();
    private static final java.util.List<String> LOGS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * One wheel step in pixels of a page, the rough distance a mouse wheel covers in a browser.
     */
    private static final float WHEEL_STEP = 100f;

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
                        entry.sent = false;
                    }
                    target.evaluateJavascript(DISPATCH, null);
                    target.evaluateJavascript(WHEEL, null);
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
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            view.setOnClickListener(null);
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
        boolean accepted = mouse(view, MotionEvent.ACTION_HOVER_MOVE, x, y);
        if (page.hovers > 3) {
            return;
        }
        page.hovers++;
        log("pointer browser " + page.id + " hover accepted " + accepted
                + " at " + (x * view.getWidth()) + "," + (y * view.getHeight())
                + " size " + view.getWidth() + "x" + view.getHeight());
    }

    /**
     * The axis of a scroll event does not carry the steps the game reports, so the wheel is raised in
     * the page instead, where the delta is what the page expects.
     */
    private static void scroll(Page page, OverlayWebView view, float x, float y, float amount) {
        if (!view.isFocused()) {
            view.requestFocus();
        }
        float delta = -amount * WHEEL_STEP;
        if (delta == 0f) {
            return;
        }
        page.remember(x, y);
        view.evaluateJavascript("if(!window.__wryWheel){" + WHEEL_DEFINE + "}"
                + "window.__wryWheel(" + x + "," + y + "," + delta + ")", null);
    }

    private static boolean mouse(OverlayWebView view, int type, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x * view.getWidth();
        coords.y = y * view.getHeight();
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

        int detail = 1;
        boolean twice = false;
        if (down) {
            long now = SystemClock.uptimeMillis();
            twice = page.lastPress != 0L && domButton == 0 && page.lastButton == 0
                    && now - page.lastPress < 500L
                    && Math.abs(x - page.lastX) < 0.01f && Math.abs(y - page.lastY) < 0.01f;
            detail = twice ? 2 : 1;
            page.lastPress = now;
            page.lastX = x;
            page.lastY = y;
            page.lastButton = domButton;
            page.detail = detail;
            page.doubleClick = twice;
        } else if (up) {
            detail = page.detail;
            twice = page.doubleClick;
            page.doubleClick = false;
        } else if (page.sameSpot(x, y)) {
            return;
        }
        page.remember(x, y);

        StringBuilder script = new StringBuilder("if(!window.__wry){")
                .append(DISPATCH_DEFINE)
                .append("}window.__wry('")
                .append(phase).append("',").append(x).append(',').append(y)
                .append(',').append(domButton).append(',').append(detail)
                .append(',').append(up ? 0 : bit).append(')');

        view.evaluateJavascript(script.toString(), null);
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

    /**
     * Puts text into the field the page has focused, the way typing would.
     */
    public static void text(final long id, final String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                Page page = PAGES.get(id);
                if (page == null || page.view == null || page.view.getVisibility() != View.VISIBLE) {
                    return;
                }
                String quoted = "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                        .replace("\n", "\\n").replace("\r", "\\r") + "'";
                page.view.evaluateJavascript("(function(text){" + INSERT_BODY + "})(" + quoted + ")", null);
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
        long lastPress;
        float lastX;
        float lastY;
        int lastButton;
        int detail;
        boolean doubleClick;
        float sentX;
        float sentY;
        boolean sent;

        Page(long id, OverlayWebView view) {
            this.id = id;
            this.view = view;
        }

        boolean sameSpot(float x, float y) {
            return sent && sentX == x && sentY == y;
        }

        void remember(float x, float y) {
            sent = true;
            sentX = x;
            sentY = y;
        }
    }

    private static final class OverlayWebView extends WebView {

        private boolean injecting;

        OverlayWebView(Context context) {
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
}
