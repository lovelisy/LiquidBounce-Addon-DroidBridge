package liquidbounce.DroidBridge;

/**
 * The pieces of javascript the bridge puts into a page. They stay in the page, so an interaction
 * only sends its coordinates or its text instead of the whole handler.
 */
final class WryScripts {

    /**
     * Chrome 116 drops the unprefixed mask properties, which the theme uses to paint the menu icons.
     * The icons are drawn as a masked background, while an invisible image inside the same element
     * already carries the resolved source, so that source is what the prefixed form is built from.
     */
    static final String MASKS =
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

    /**
     * Design size of the LiquidBounce theme (desktop). Fit = min(viewport/design).
     * On a phone the same CSS pixel sizes occupy far more of the screen; fitting the
     * viewport back to this design is what keeps ClickGUI / HUD from looking blown up.
     */
    private static final int DESKTOP_WIDTH = 1600;

    private static final int DESKTOP_HEIGHT = 900;

    private static final float MIN_FIT = 0.25f;

    /**
     * HUD uses style.zoom = scaleFactor*50% (see Hud.svelte). Fit is multiplied on top.
     */
    private static final float HUD_FIT = 1f;

    private static final int MENU_HEIGHT = 1125;

    private static final float MENU_FIT = 0.25f;

    /**
     * Theme layout reference (LiquidBounce src-theme):
     * - ScaledClickGuiContent.svelte: .scaled-clickgui-content { transform: scale(scaleFactor*50%) }
     * - Search.svelte: .search { position:fixed; left:50%; top:70px; transform:translateX(-50%) }
     * - Hud.svelte: .hud { zoom: scaleFactor*50% }
     *
     * Stock SCALE only zoomed tabs/search/drawer chrome, so panels stayed desktop-sized on a
     * phone. Panels live under .scaled-clickgui-content; that node must take the fit as well.
     * Search is moved from top:70px to the bottom of the viewport.
     */
    static final String SCALE =
            "(function(){"
                    + "var W=" + DESKTOP_WIDTH + ";"
                    + "var H=" + DESKTOP_HEIGHT + ";"
                    + "var MIN=" + MIN_FIT + ";"
                    + "var HUD=" + HUD_FIT + ";"
                    + "var MH=" + MENU_HEIGHT + ";"
                    + "var MF=" + MENU_FIT + ";"
                    + "function fit(){"
                    + "var w=window.innerWidth||W;"
                    + "var h=window.innerHeight||H;"
                    + "var f=Math.min(w/W,h/H);"
                    + "if(!isFinite(f)||f<=0){return 1;}"
                    + "return Math.min(1,Math.max(MIN,f));"
                    + "}"
                    + "function ratio(raw){"
                    + "var v=parseFloat(raw);"
                    + "if(!isFinite(v)||v<=0){return 0;}"
                    + "return raw.indexOf('%')>=0?v/100:v;"
                    + "}"
                    + "function hudWant(f){"
                    + "var z=2;"
                    + "var el=document.querySelector('.hud');"
                    + "if(el&&el.style&&el.style.zoom){"
                    + "var got=ratio(el.style.zoom);"
                    + "if(got){z=got;}"
                    + "}"
                    + "return z*f*HUD;"
                    + "}"
                    + "function clickgui(f){"
                    + "var css='';"
                    + "css+='.available-tabs{zoom:'+f+';}';"
                    + "css+='.component-drawer{zoom:'+f+';}';"
                    + "css+='.search{"
                    + "position:fixed!important;"
                    + "left:50%!important;"
                    + "right:auto!important;"
                    + "top:auto!important;"
                    + "bottom:20px!important;"
                    + "transform:translateX(-50%)!important;"
                    + "zoom:'+f+';"
                    + "z-index:9999999999!important;"
                    + "}';"
                    + "var hw=hudWant(f);"
                    + "css+='.hud{zoom:'+hw.toFixed(3)+'!important;}';"
                    + "css+='.scaled-clickgui-content .hud{"
                    + "transform:scale(1)!important;"
                    + "transform-origin:top left!important;"
                    + "}';"
                    /*
                     * Theme ScaledClickGuiContent uses scaleFactor*50% and ClickGUI Scale loads
                     * async — first paint is oversized for a few seconds. Hide until two frames
                     * after enter, and keep re-applying fit while the theme settles.
                     */
                    + "return {css:css,log:'clickgui chrome '+f+' hud '+hw.toFixed(3)};"
                    + "}"
                    + "function hud(f){"
                    + "var want=hudWant(f);"
                    + "return {css:'.hud{zoom:'+want.toFixed(3)+'!important;}',"
                    + "log:'hud '+want.toFixed(3)};"
                    + "}"
                    + "function menu(){"
                    + "var base=window.__wryMenuBase;"
                    + "if(!base){"
                    + "var me=document.querySelector('.menu');"
                    + "var read=me?ratio(getComputedStyle(me).zoom):0;"
                    + "if(read){base=read;window.__wryMenuBase=base;}"
                    + "else{return {css:'',log:'menu pending'};}"
                    + "}"
                    + "var h=window.innerHeight||H;"
                    + "var room=h/MH;"
                    + "var want=Math.min(base,Math.max(MF,room));"
                    + "if(want>=base){return {css:'',log:'menu '+base+' fits'};}"
                    + "return {css:'.menu{zoom:'+want.toFixed(3)+'!important;}',"
                    + "log:'menu '+base+'->'+want.toFixed(3)};"
                    + "}"
                    + "window.__wryScale=function(){"
                    + "var route=location.hash||'';"
                    + "var f=fit();"
                    + "var result;"
                    + "if(route.indexOf('/clickgui')>=0){result=clickgui(f);}"
                    + "else if(route.indexOf('/hud')>=0||route.indexOf('/inventory')>=0){result=hud(f);}"
                    + "else{result=menu();}"
                    + "var style=document.getElementById('wry-scale');"
                    + "if(!style){style=document.createElement('style');style.id='wry-scale';"
                    + "document.head.appendChild(style);}"
                    + "style.textContent=result.css;"
                    + "return route+' '+result.log;"
                    + "};"
                    + "if(!window.__wryScaleHooked){"
                    + "window.__wryScaleHooked=true;"
                    + "window.addEventListener('hashchange',function(){window.__wryScale();});"
                    + "window.addEventListener('resize',function(){window.__wryScale();});"
                    + "}"
                    + "return window.__wryScale();"
                    + "})()";

    /**
     * ClickGUI module descriptions pick left/right with a 300px right-margin test
     * (Module.svelte). On a phone that test almost always fails, so the tooltip is
     * stuck on the left. Reposition .description-wrapper to the side with more room
     * and keep it on-screen.
     */
    static final String DESCRIPTION_FIX =
            "(function(){"
                    + "if(window.__wryDescFix){return 'already';}"
                    + "window.__wryDescFix=true;"
                    + "function place(el){"
                    + "if(!el||!el.style){return;}"
                    + "var w=el.offsetWidth||160;"
                    + "var h=el.offsetHeight||40;"
                    + "var vw=window.innerWidth||0;"
                    + "var vh=window.innerHeight||0;"
                    + "if(vw<1){return;}"
                    + "var left=parseFloat(el.style.left);"
                    + "var top=parseFloat(el.style.top);"
                    + "if(!isFinite(left)){left=0;}"
                    + "if(!isFinite(top)){top=vh/2;}"
                    + "var desc=el.querySelector('.description');"
                    /* Prefer the side with more free space around the tooltip midpoint. */
                    + "var moduleGuess=left;"
                    + "if(desc&&desc.classList.contains('right')){moduleGuess=left+w+20;}"
                    + "else{moduleGuess=left-20;}"
                    + "var spaceRight=vw-moduleGuess;"
                    + "var spaceLeft=moduleGuess;"
                    + "var preferRight=spaceRight>=spaceLeft;"
                    + "var newLeft;"
                    + "if(preferRight){"
                    + "newLeft=moduleGuess+12;"
                    + "if(desc){desc.classList.remove('right');}"
                    + "}else{"
                    + "newLeft=moduleGuess-w-12;"
                    + "if(desc){desc.classList.add('right');}"
                    + "}"
                    + "if(newLeft<4){newLeft=4;}"
                    + "if(newLeft+w>vw-4){newLeft=Math.max(4,vw-w-4);}"
                    + "if(top<h/2+4){top=h/2+4;}"
                    + "if(top>vh-h/2-4){top=vh-h/2-4;}"
                    + "el.style.left=newLeft+'px';"
                    + "el.style.top=top+'px';"
                    + "}"
                    + "function scan(){"
                    + "document.querySelectorAll('.description-wrapper').forEach(place);"
                    + "}"
                    + "var obs=new MutationObserver(function(){scan();});"
                    + "obs.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class']});"
                    + "window.addEventListener('resize',scan);"
                    + "setInterval(scan,400);"
                    + "scan();"
                    + "return 'desc-fix';"
                    + "})()";

    /**
     * The theme draws its text with the Inter fonts, which it fetches over http at 2.8 MB. A page
     * that paints before they arrive lays the text out with a fallback font, and the swap to the real
     * font leaves the white blocks of the previous frame behind. Waiting for the fonts before the
     * first frame becomes visible is what keeps those blocks off the screen.
     */
    static final String FONTS =
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
     * Puts text into the field the page has focused, the way typing would.
     */
    static final String INSERT =
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

    /**
     * Enter is raised in the page, because the fields that wait for it listen to the dom event and a
     * key event of a web view without an input connection never becomes one.
     */
    static final String ENTER =
            "var e=document.activeElement||document.body;"
                    + "var k={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,"
                    + "view:window};"
                    + "e.dispatchEvent(new KeyboardEvent('keydown',k));"
                    + "e.dispatchEvent(new KeyboardEvent('keypress',k));"
                    + "e.dispatchEvent(new KeyboardEvent('keyup',k));"
                    + "return e.tagName;";

    /**
     * Backspace and delete are done in the page, because a key event does not edit a field of a web
     * view that the system has never given an input connection.
     */
    static String edit(boolean backspace) {
        return "var e=document.activeElement;"
                + "if(!e||e===document.body){return 'no field';}"
                + "var editable=e.tagName==='INPUT'||e.tagName==='TEXTAREA'"
                + "||e.isContentEditable||e.getAttribute('contenteditable')==='true';"
                + "if(!editable){return 'not editable';}"
                + "if(e.isContentEditable||e.getAttribute('contenteditable')==='true'){"
                + (backspace
                    ? "var s=String(e.textContent||'');if(!s.length){return 'empty';}e.textContent=s.slice(0,-1);"
                    : "return 'delete in rich text';")
                + "}else{"
                + "var v=e.value||'';"
                + "var start=e.selectionStart;"
                + "var end=e.selectionEnd;"
                + "if(typeof start!=='number'){start=v.length;end=v.length;}"
                + (backspace
                    ? "if(start===end){if(start===0){return 'at start';}start=start-1;}"
                    : "if(start===end){if(end>=v.length){return 'at end';}end=end+1;}")
                + "e.value=v.slice(0,start)+v.slice(end);"
                + "e.selectionStart=start;"
                + "e.selectionEnd=start;"
                + "}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'edited';";
    }

    private WryScripts() {
    }
}
