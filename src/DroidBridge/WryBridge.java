package liquidbounce.DroidBridge;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
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
                    + "canvases:canvas.length,webgl2:gl,"
                    + "innerWidth:window.innerWidth,innerHeight:window.innerHeight,"
                    + "outerWidth:window.outerWidth,outerHeight:window.outerHeight,"
                    + "dpr:window.devicePixelRatio,"
                    + "screenWidth:screen.width,screenHeight:screen.height,"
                    + "screenAvail:screen.availWidth+'x'+screen.availHeight,"
                    + "visual:window.visualViewport?(visualViewport.width+'x'+visualViewport.height+'@'+visualViewport.scale):'none',"
                    + "docClient:document.documentElement.clientWidth+'x'+document.documentElement.clientHeight,"
                    + "bodyScroll:document.body.scrollWidth+'x'+document.body.scrollHeight,"
                    + "scrollbar:bodyscrollbar(),"
                    + "panels:document.getElementsByClassName('panel').length,"
                    + "expanded:document.getElementsByClassName('modules expanded').length,"
                    + "scrollable:scrollables()};"
                    + "function bodyscrollbar(){return 'w'+Math.max(0,window.innerWidth-document.documentElement.clientWidth);}"
                    + "function scrollables(){"
                    + "var out=[];"
                    + "var all=document.querySelectorAll('*');"
                    + "for(var i=0;i<all.length&&out.length<4;i++){"
                    + "var el=all[i];"
                    + "var st=getComputedStyle(el);"
                    + "if((st.overflowY==='auto'||st.overflowY==='scroll')&&el.scrollHeight>el.clientHeight+1){"
                    + "out.push((el.className||el.tagName)+':'+el.clientHeight+'/'+el.scrollHeight+'@'+el.scrollTop);}}"
                    + "return out.join('|');}"
                    + "})())";

    /**
     * Everything of the theme is drawn at this fraction of the size it asks for.
     */
    private static final float FIT = 0.6f;

    /**
     * The pages are laid out for a desktop, where a pixel of the viewport is a pixel on the screen.
     * Android gives the page 805 css pixels for a 1610 pixel wide screen, so the fixed sizes the
     * theme uses - a 250 pixel wide panel, a 200% hud - end up far larger than the screen can show
     * comfortably. The page is scaled down again here, per route, because every route of the theme
     * is sized differently.
     */
    private static final String SCALE =
            "(function(){"
                    + "if(window.__wryScale){return 'already';}"
                    + "window.__wryScale=true;"
                    + "var FIT=" + FIT + ";"
                    + "var route=location.hash||'';"
                    + "var css='';"
                    + "if(route.indexOf('/clickgui')>=0){"
                    + "css+='.panel{width:'+Math.round(250*FIT)+'px!important;}';"
                    + "css+='.scaled-clickgui-content{transform:scale('+Math.round(FIT*100)+'%)!important;"
                    + "width:'+Math.round(100/FIT)+'vw!important;height:'+Math.round(100/FIT)+'vh!important;}';"
                    + "css+='.clickgui .panel .modules{max-height:'+Math.round(545*FIT)+'px!important;}';"
                    + "}else if(route.indexOf('/hud')>=0||route.indexOf('/inventory')>=0){"
                    + "css+='.hud{zoom:'+Math.round(FIT*100)+'%!important;}';"
                    + "}else{"
                    + "css+='.menu{zoom:'+Math.round(FIT*100)+'%!important;}';"
                    + "}"
                    + "var style=document.createElement('style');"
                    + "style.id='wry-scale';"
                    + "style.textContent=css;"
                    + "document.head.appendChild(style);"
                    + "return route+' '+Math.round(FIT*100)+'% '+css.length;"
                    + "})()";

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
     * The theme draws its text with the Inter fonts, which it fetches over http at 2.8 MB. A page
     * that paints before they arrive lays the text out with a fallback font, and the swap to the real
     * font leaves the white blocks of the previous frame behind. Waiting for the fonts before the
     * first frame becomes visible is what keeps those blocks off the screen.
     */
    private static final String FONTS =
            "(function(){"
                    + "if(!document.fonts||!document.fonts.ready){return 'unsupported';}"
                    + "var pending=[];"
                    + "['300','400','500','600'].forEach(function(weight){"
                    + "pending.push(document.fonts.load(weight+' 14px Inter'));"
                    + "});"
                    + "window.__wryFonts=false;"
                    + "Promise.all(pending).then(function(){window.__wryFonts=true;})"
                    + ".catch(function(){window.__wryFonts=true;});"
                    + "return 'loading';"
                    + "})()";

    /**
     * A page that is only shown once the fonts are there still shows nothing for a moment, so the
     * text is kept invisible until then instead of being painted and swapped.
     */
    private static final int TAG_ID = 0x7f0d0001;
    private static final int TAG_LAYER = 0x7f0d0002;

    /**
     * One wheel step in pixels of a page, the rough distance a mouse wheel covers in a browser.
     */
    private static final float WHEEL_STEP = 100f;

    /**
     * The pages are separate views of the system that are stacked above the surface of the game, and
     * the one that was shown last used to end up at the bottom. A screen sits above the hud, so the
     * order is kept here rather than in the order the pages happen to be shown in.
     */
    private static final int LAYER_HUD = 0;
    private static final int LAYER_SCREEN = 1;

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
                    target.evaluateJavascript(MASKS, null);
                    target.evaluateJavascript(SCALE, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            log("scale " + value);
                        }
                    });
                    target.evaluateJavascript(PROBE, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            log("page probe " + value);
                        }
                    });
                    target.evaluateJavascript(FONTS, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            log("fonts " + value);
                        }
                    });
                    awaitVisualState(target);
                }

                @Override
                public void onPageCommitVisible(WebView target, String page) {
                    awaitVisualState(target);
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
            int layer = layerOfUrl(url);
            view.setTag(TAG_LAYER, Integer.valueOf(layer));
            view.setTag(TAG_ID, Long.valueOf(id));
            Page created = new Page(id, view, layer);
            PAGES.put(id, created);
            String stacking = place(view);
            view.setVisibility(View.INVISIBLE);
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setOnFocusChangeListener(null);
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            view.setOnClickListener(null);
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
            if (!(child instanceof OverlayWebView)) {
                continue;
            }
            if (layerOf(child) > layer) {
                break;
            }
            slot = index + 1;
        }
        return slot;
    }

    private static int layerOf(View view) {
        Object tag = view.getTag(TAG_LAYER);
        return tag instanceof Integer ? ((Integer) tag).intValue() : LAYER_HUD;
    }

    /**
     * The hud is the only page of the theme that belongs to the world. Every other page is a screen
     * and covers it, so the url is what tells them apart.
     */
    private static int layerOfUrl(String url) {
        if (url == null) {
            return LAYER_HUD;
        }
        if (url.contains("/hud") || url.contains("/inventory")) {
            return LAYER_HUD;
        }
        return LAYER_SCREEN;
    }

    /**
     * A post after the page finished loading runs on the next loop turn, which is before the system
     * has drawn the page: the first frame that appears is then a half composed one, with the white
     * blocks of elements that have a color but no masked image yet. The visual state callback is
     * raised by the compositor once the page is ready to be shown.
     */
    private static void awaitVisualState(WebView view) {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            markPainted(view);
            return;
        }
        view.postVisualStateCallback(System.nanoTime(), new WebView.VisualStateCallback() {
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
    private static void awaitFonts(final WebView view) {
        pollFonts(view, 0);
    }

    private static void pollFonts(final WebView view, final int attempt) {
        if (attempt > 40) {
            markPainted(view);
            return;
        }
        view.evaluateJavascript(
                "window.__wryFonts===true||!document.fonts",
                new ValueCallback<String>() {
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

    private static void markPainted(WebView view) {
        Object tag = view.getTag(TAG_ID);
        if (!(tag instanceof Long)) {
            return;
        }
        Page entry = PAGES.get((Long) tag);
        if (entry == null || entry.drawn) {
            return;
        }
        entry.drawn = true;
        log("browser " + entry.id + " painted");
        show(entry);
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
                page.wanted = visible;
                if (!visible) {
                    page.view.setVisibility(View.INVISIBLE);
                    return;
                }
                if (!page.loaded || !page.drawn) {
                    log("browser " + id + " waits for the first draw (loaded " + page.loaded
                            + ", drawn " + page.drawn + ")");
                    return;
                }
                show(page);
            }
        });
        return "browser " + id + " visibility " + visible;
    }

    /**
     * Shows the page only once it has painted, so the frame that appears is a page and not a blank view.
     */
    private static void show(Page page) {
        if (!page.wanted || page.view == null || page.view.getVisibility() == View.VISIBLE) {
            return;
        }
        page.view.setVisibility(View.VISIBLE);
        String stacking = place(page.view);
        if (!stacking.startsWith("already")) {
            log("restacked browser " + page.id + " layer " + page.layer + ": " + stacking);
        }
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
        mouse(view, MotionEvent.ACTION_HOVER_MOVE, x, y);
    }

    /**
     * The axis of a scroll event does not carry the steps the game reports, so the wheel is raised in
     * the page instead, where the delta is what the page expects.
     */
    private static void scroll(Page page, OverlayWebView view, float x, float y, float amount) {
        float delta = -amount * WHEEL_STEP;
        if (delta == 0f) {
            return;
        }
        page.remember(x, y);
        String body = "var bx=" + x + "*window.innerWidth,by=" + y + "*window.innerHeight;"
                + "var t=document.elementFromPoint(bx,by);"
                + "var dy=" + delta + ";"
                + "var moved=null;"
                + "var found=false;"
                + "while(t&&t!==document.body&&t!==document.documentElement){"
                + "var style=getComputedStyle(t);"
                + "var scrollable=(style.overflowY==='auto'||style.overflowY==='scroll')"
                + "&&t.scrollHeight>t.clientHeight;"
                + "if(scrollable){"
                + "found=true;"
                + "var before=t.scrollTop;"
                + "var smooth=getComputedStyle(t).scrollBehavior;"
                + "if(smooth!=='auto'){t.style.scrollBehavior='auto';}"
                + "t.scrollTop=before+dy;"
                + "if(smooth!=='auto'){t.style.scrollBehavior=smooth;}"
                + "if(t.scrollTop!==before){moved=t.tagName+' '+before+'->'+t.scrollTop;break;}"
                + "var room=dy>0?(t.scrollHeight-t.clientHeight-before):before;"
                + "if(room<=0){moved=t.tagName+' at '+(dy>0?'end':'start');}"
                + "break;}"
                + "t=t.parentElement;}"
                + "if(!moved&&!found){"
                + "if(document.scrollingElement){"
                + "var root=document.scrollingElement;"
                + "var was=root.scrollTop;"
                + "root.scrollTop=was+dy;"
                + "moved='root '+was+'->'+root.scrollTop;"
                + "}else{window.scrollBy(0,dy);moved='window';}}"
                + "var chain=[];"
                + "var p=document.elementFromPoint(bx,by);"
                + "while(p&&p!==document.documentElement){"
                + "var s2=getComputedStyle(p);"
                + "chain.push((p.className||p.tagName)+'[ov'+s2.overflowY+' '+p.clientHeight+'/'+p.scrollHeight+']');"
                + "p=p.parentElement;}"
                + "return (moved||'nothing')+' | hit '+chain.slice(0,4).join(' < ');";
        view.evaluateJavascript("(function(){" + body + "})()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                log("wheel browser " + page.id + " amount " + amount + " delta " + delta + " -> " + value);
            }
        });
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
                if (code == 0 || !pressed) {
                    return;
                }
                if (code == ENTER) {
                    enter(page);
                    return;
                }
                edit(page, keycode);
            }
        });
    }

    /**
     * Enter is raised in the page, because the fields that wait for it listen to the dom event and a
     * key event of a web view without an input connection never becomes one.
     */
    private static void enter(Page page) {
        String body = "var e=document.activeElement||document.body;"
                + "var k={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,"
                + "view:window};"
                + "e.dispatchEvent(new KeyboardEvent('keydown',k));"
                + "e.dispatchEvent(new KeyboardEvent('keypress',k));"
                + "e.dispatchEvent(new KeyboardEvent('keyup',k));"
                + "return e.tagName;";
        page.view.evaluateJavascript("(function(){" + body + "})()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                log("enter browser " + page.id + " -> " + value);
            }
        });
    }

    /**
     * Backspace and delete are done in the page, because a key event does not edit a field of a web
     * view that the system has never given an input connection.
     */
    private static void edit(Page page, int glfw) {
        String body = "var e=document.activeElement;"
                + "if(!e||e===document.body){return 'no field';}"
                + "var editable=e.tagName==='INPUT'||e.tagName==='TEXTAREA'"
                + "||e.isContentEditable||e.getAttribute('contenteditable')==='true';"
                + "if(!editable){return 'not editable';}"
                + "if(e.isContentEditable||e.getAttribute('contenteditable')==='true'){"
                + (glfw == 259
                    ? "var s=String(e.textContent||'');if(!s.length){return 'empty';}e.textContent=s.slice(0,-1);"
                    : "return 'delete in rich text';")
                + "}else{"
                + "var v=e.value||'';"
                + "var start=e.selectionStart;"
                + "var end=e.selectionEnd;"
                + "if(typeof start!=='number'){start=v.length;end=v.length;}"
                + (glfw == 259
                    ? "if(start===end){if(start===0){return 'at start';}start=start-1;}"
                    : "if(start===end){if(end>=v.length){return 'at end';}end=end+1;}")
                + "e.value=v.slice(0,start)+v.slice(end);"
                + "e.selectionStart=start;"
                + "e.selectionEnd=start;"
                + "}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'edited';";
        page.view.evaluateJavascript("(function(){" + body + "})()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                log("edit browser " + page.id + " glfw " + glfw + " -> " + value);
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
                if ("\b".equals(value)) {
                    log("text browser " + id + " backspace via char");
                    edit(page, 259);
                    return;
                }
                String quoted = "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                        .replace("\n", "\\n").replace("\r", "\\r") + "'";
                page.view.evaluateJavascript("(function(text){" + INSERT_BODY + "})(" + quoted + ")",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String result) {
                                log("text browser " + id + " '" + value + "' -> " + result);
                            }
                        });
            }
        });
    }

    public static void enter(final long id) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Page page = PAGES.get(id);
                if (page == null || page.view == null || page.view.getVisibility() != View.VISIBLE) {
                    return;
                }
                log("enter browser " + id + " from the text input");
                enter(page);
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

    /**
     * The keys the bridge handles itself, everything else goes to the input method of the system.
     */
    private static final int EDIT = -1;
    private static final int ENTER = -2;

    private static int keycode(int glfw) {
        switch (glfw) {
            case 257:
                return ENTER;
            case 259:
                return EDIT;
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
        final int layer;
        volatile boolean loaded;
        volatile boolean drawn;
        volatile boolean wanted;
        boolean pressed;
        int pressedButton;
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
            this(id, view, LAYER_HUD);
        }

        Page(long id, OverlayWebView view, int layer) {
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
