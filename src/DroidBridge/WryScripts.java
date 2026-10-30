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
                    + "return 'masked '+fixed;"
                    + "})()";

    /**
     * The theme is written for the desktop browser, which lays a page out in pixels of the
     * framebuffer. The web view of Android lays it out in density independent pixels instead, so
     * the same css pixel covers as many device pixels as the density says: panels grow, the hud
     * components grow and the settings window keeps its pixel width on a screen that has more.
     *
     * WryPages declares the page as wide as the screen before it loads, which turns one css pixel
     * back into one framebuffer pixel and lets the theme behave as it does on the desktop. The
     * values below are therefore in the same unit they have on the desktop and need no fit of
     * their own.
     *
     * Theme layout reference (LiquidBounce src-theme):
     * - ScaledClickGuiContent.svelte: .scaled-clickgui-content { transform: scale(scaleFactor*50%) }
     * - Search.svelte: .search { position:fixed; left:50%; top:70px; transform:translateX(-50%) }
     * - Hud.svelte: .hud { zoom: scaleFactor*50% }
     *
     * Panels live under .scaled-clickgui-content and are sized in those pixels, so that node keeps
     * the scale of the theme and is only widened when the panels need more room than the viewport
     * has. Search is moved from top:70px to the bottom of the viewport, where the thumbs are.
     */
    static final String SCALE =
            "(function(){"
                    + "function cfg(name,fallback){"
                    + "var c=window.__wryCfg;"
                    + "if(!c){return fallback;}"
                    + "var v=c[name];"
                    + "if(!isFinite(v)||v<=0){return fallback;}"
                    + "return v;"
                    + "}"
                    + "function ratio(raw){"
                    + "var v=parseFloat(raw);"
                    + "if(!isFinite(v)||v<=0){return 0;}"
                    + "return raw.indexOf('%')>=0?v/100:v;"
                    + "}"
                    + "function unit(){"
                    + "var d=window.devicePixelRatio||1;"
                    + "return d>1.01?d:1;"
                    + "}"
                    + "function clickgui(){"
                    + "var css='';"
                    + "var c=cfg('clickgui',1);"
                    + "var s=cfg('settings',1);"
                    + "var u=1/unit();"
                    + "css+='.available-tabs{zoom:'+(c*u)+';}';"
                    + "css+='.component-drawer{zoom:'+(c*u)+';}';"
                    + "var hudResult=null;"
                    + "if(document.querySelector('.hud')){"
                    + "hudResult=hud();css+=hudResult.css;"
                    + "}"
                    + "var sc=document.querySelector('.scaled-clickgui-content');"
                    + "if(sc){"
                    + "var raw=sc.style.transform||'';"
                    + "var mm=raw.match(/scale\\(\\s*([\\d.]+)(%?)\\s*\\)/);"
                    + "var base=mm?parseFloat(mm[1])/(mm[2]?100:1):0.5;"
                    + "var sf=base*2;"
                    + "if(sf>0){"
                    + "var right=0;"
                    + "var bottom=0;"
                    + "var ps=sc.querySelectorAll('.panel');"
                    + "for(var i=0;i<ps.length;i++){"
                    + "var p=ps[i];"
                    + "var r=p.offsetLeft+p.offsetWidth;"
                    + "var b=p.offsetTop+p.offsetHeight;"
                    + "if(r>right){right=r;}"
                    + "if(b>bottom){bottom=b;}"
                    + "}"
                    + "var vw=window.innerWidth||0;"
                    + "var vh=window.innerHeight||0;"
                    + "var zoom=Math.min(sf/2,vw/(right+20),vh/(bottom+20))*c;"
                    + "if(!(zoom>0)){zoom=sf/2;}"
                    + "var w=Math.max(right+20,vw/zoom);"
                    + "var h=Math.max(bottom+20,vh/zoom);"
                    + "css+='.scaled-clickgui-content{width:'+w+'px!important;height:'+h"
                    + "+'px!important;transform:scale('+zoom+')!important;}';"
                    + "}"
                    + "}"
                    + "css+='.window{zoom:'+s+';}';"
                    + "css+='.search{"
                    + "position:fixed!important;"
                    + "left:50%!important;"
                    + "right:auto!important;"
                    + "top:auto!important;"
                    + "bottom:20px!important;"
                    + "transform:translateX(-50%)!important;"
                    + "zoom:'+(c*u)+';"
                    + "z-index:9999999999!important;"
                    + "}';"
                    + "return {css:css,log:'clickgui x'+c};"
                    + "}"
                    + "function hud(){"
                    + "var el=document.querySelector('.hud');"
                    + "if(!el){return {css:'',log:'hud pending'};}"
                    + "var z=ratio(el.style?el.style.zoom:'');"
                    + "if(!z){return {css:'',log:'hud native'};}"
                    + "var dpr=window.devicePixelRatio||1;"
                    + "var c=cfg('hud',1);"
                    + "if(dpr<=1.01&&c===1){return {css:'',log:'hud native '+z};}"
                    + "var css='';"
                    + "if(dpr>1.01){"
                    + "css+='.hud .draggable-element{zoom:'+(1/dpr).toFixed(6)+'!important;}';"
                    + "}"
                    + "if(c!==1){"
                    + "css+='.hud{zoom:'+(z*c).toFixed(6)+'!important;}';"
                    + "}"
                    + "return {css:css,log:'hud dpr'+dpr+' x'+c};"
                    + "}"
                    + "function guessBase(w,h){"
                    + "var z=1;"
                    + "if(w<=1366){z=0.8;}"
                    + "if(w<=1200){z=0.5;}"
                    + "if(h<=1100){z=0.8;}"
                    + "if(h<=700){z=0.5;}"
                    + "if(h<=540){z=0.4;}"
                    + "return z;"
                    + "}"
                    + "function menu(){"
                    + "var u=unit();"
                    + "var w=(window.innerWidth||0)*u;"
                    + "var h=(window.innerHeight||0)*u;"
                    + "var base=guessBase(w,h);"
                    + "if(u<=1.01){return {css:'',log:'menu native '+base};}"
                    + "return {css:'.menu{zoom:'+(base/u).toFixed(4)+'!important;}',"
                    + "log:'menu '+base+'/'+u};"
                    + "}"
                    + "window.__wryScale=function(){"
                    + "var route=location.hash||'';"
                    + "var result;"
                    + "if(route.indexOf('/clickgui')>=0){result=clickgui();}"
                    + "else if(route.indexOf('/hud')>=0||route.indexOf('/inventory')>=0){result=hud();}"
                    + "else{result=menu();}"
                    + "var style=document.getElementById('wry-scale');"
                    + "if(!style){style=document.createElement('style');style.id='wry-scale';"
                    + "document.head.appendChild(style);}"
                    + "if(style.textContent!==result.css){style.textContent=result.css;}"
                    + "return route+' '+result.log;"
                    + "};"
                    + "if(!window.__wryScaleHooked){"
                    + "window.__wryScaleHooked=true;"
                    + "window.addEventListener('hashchange',function(){window.__wryScale();});"
                    + "window.addEventListener('resize',function(){window.__wryScale();});"
                    + "window.__wryPanelTimer=setInterval(function(){"
                    + "var hash=location.hash||'';"
                    + "if(hash.indexOf('/clickgui')<0&&hash.indexOf('/hud')<0"
                    + "&&hash.indexOf('/inventory')<0){return;}"
                    + "var sc=document.querySelector('.scaled-clickgui-content');"
                    + "var hud=document.querySelector('.hud');"
                    + "var now=(sc?sc.style.transform:'')+'|'+(hud?hud.style.zoom:'');"
                    + "if(now===window.__wryPanelLast){return;}"
                    + "window.__wryPanelLast=now;"
                    + "if(window.__wryScale){window.__wryScale();}"
                    + "},400);"
                    + "window.__wryHudFitObserver=new MutationObserver(function(){"
                    + "if((location.hash||'').indexOf('/clickgui')<0){return;}"
                    + "var current=document.querySelector('.hud');"
                    + "if(current!==window.__wryHudFitLast){"
                    + "window.__wryHudFitLast=current;"
                    + "if(current&&window.__wryScale){window.__wryScale();}"
                    + "}"
                    + "});"
                    + "window.__wryHudFitObserver.observe(document.documentElement,{childList:true,subtree:true});"
                    + "}"
                    + "return window.__wryScale();"
                    + "})()";

    static String config(float clickgui, float hud, float settings) {
        return "window.__wryCfg={clickgui:" + clickgui + ",hud:" + hud + ",settings:" + settings + "}";
    }

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
     * A pointer event is fired from the page itself, so the target is resolved by hit testing and
     * the coordinates are taken as fractions of the viewport.
     */
    static final String DISPATCH =
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

    static final String DISPATCH_SETUP =
            "(function(){"
                    + "if(window.__wry){return 'already';}"
                    + DISPATCH
                    + "return 'ready';})()";

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

    /**
     * The axis of a scroll event does not carry the steps the game reports, so the wheel is raised in
     * the page instead, where the delta is what the page expects.
     */
    static String wheel(float x, float y, float delta) {
        return "var bx=" + x + "*window.innerWidth,by=" + y + "*window.innerHeight;"
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
    }

    private WryScripts() {
    }
}
