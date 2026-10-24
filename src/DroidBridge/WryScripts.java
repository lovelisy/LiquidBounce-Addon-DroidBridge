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
     * The width and the height the theme was drawn for, which is a desktop window.
     *
     * The theme writes its sizes in css pixels: the menu pads itself by 50, the drawer of the hud
     * editor is 600 wide, the text of the buttons at the top of the click gui is 14 tall. A phone
     * hands the page a viewport of 805x360, so those same numbers take up two and a half times the
     * share of the screen that they take on a desktop, and the whole page looks zoomed in. Sizing
     * the page from the viewport instead is what brings it back to the size of a desktop.
     */
    private static final int DESKTOP_WIDTH = 1600;

    private static final int DESKTOP_HEIGHT = 900;

    /**
     * The narrowest the page is allowed to become, so that a small screen stays readable.
     */
    private static final float MIN_FIT = 0.2f;

    /**
     * The hud asks for scaleFactor*50%, which is 100% when the game reports a factor of two, and the
     * fit is applied to that.
     */
    private static final float HUD_FIT = 1f;

    /**
     * The height the menu needs at zoom 1, taken from the media queries of the theme: they pick .8
     * for a window of 900, so the content is 900/.8 = 1125 tall in css pixels.
     */
    private static final int MENU_HEIGHT = 1125;

    /**
     * The theme sizes the menu by the width alone, so a narrow screen gets a zoom that is too large
     * for its height and the bottom bar falls below the screen. The zoom is therefore capped by what
     * the height can hold, while a window that is tall enough keeps the value of the theme.
     */
    private static final float MENU_FIT = 0.2f;

    /**
     * The theme is a single page app, so the route changes without the page being loaded again.
     * The routine therefore stays in the page and is called again whenever the hash changes.
     *
     * Only the chrome that has no setting of its own is sized here. The panels and the global
     * settings already scale themselves by the factor the game reports, so those are left to the
     * Scale setting of the ClickGUI module. The buttons above the panels, the search box, the drawer
     * of the hud editor and the hud itself are drawn at a fixed css size instead, so those are the
     * ones brought down to the width of a phone.
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
                    + "var f=w/W;"
                    + "if(!isFinite(f)||f<=0){return 1;}"
                    + "return Math.min(1,Math.max(MIN,f));"
                    + "}"
                    + "function ratio(raw){"
                    + "var v=parseFloat(raw);"
                    + "if(!isFinite(v)||v<=0){return 0;}"
                    + "return raw.indexOf('%')>=0?v/100:v;"
                    + "}"
                    + "function clickgui(f){"
                    + "var css='';"
                    + "css+='.available-tabs.svelte-8tpz8n{zoom:'+f+';}';"
                    + "css+='.search{zoom:'+f+';}';"
                    + "css+='.component-drawer{zoom:'+f+';}';"
                    + "return {css:css,log:'clickgui chrome '+f};"
                    + "}"
                    + "function hud(f){"
                    + "var z=2;"
                    + "var el=document.querySelector('.hud');"
                    + "if(el&&el.style&&el.style.zoom){"
                    + "var got=ratio(el.style.zoom);"
                    + "if(got){z=got;}"
                    + "}"
                    + "var want=z*f*HUD;"
                    + "return {css:'.hud{zoom:'+want.toFixed(3)+'!important;}',"
                    + "log:'hud '+z+'x'+f.toFixed(3)+'='+want.toFixed(3)};"
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
