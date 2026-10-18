//! Drives the engine the way the game does and checks what comes back: `selftest [out dir]`.

use liquidbounce_wry::api::{self, BrowserId, BrowserOptions, Event, EventKind, Frame, Key, MouseButton, Pointer,
    StartOptions, MOD_CTRL, MOD_GUI};
use liquidbounce_wry::Engine;
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::time::{Duration, Instant};

const PAGE: &str = r##"<!doctype html>
<html><head><title>selftest</title><style>
html, body { margin: 0; background: transparent; font: 16px sans-serif; }
#marker { position: absolute; left: 0; top: 0; width: 64px; height: 64px; background: #f00; }
#text { position: absolute; left: 40px; top: 300px; width: 300px; height: 40px; }
#link { position: absolute; left: 40px; top: 400px; font-size: 32px; }
#panel { position: absolute; left: 400px; top: 100px; width: 200px; height: 100px; background: rgba(0, 0, 255, 0.5); }
</style></head><body>
<div id="marker"></div><div id="panel"></div>
<input id="text"><a id="link" href="#linked">a link</a>
<script>
console.log(`ready ${innerWidth}x${innerHeight}`);
console.log(`active ${document.hasFocus()} ${document.visibilityState}`);
let moves = 0;
addEventListener('mousemove', (e) => { if (moves++ < 3) console.log(`mousemove:${e.clientX}:${e.clientY}`); });
text.addEventListener('input', () => console.log('input:' + text.value));
addEventListener('keydown', (e) => console.log(`keydown:${e.key}:${e.code}:${e.ctrlKey ? 'ctrl' : ''}${e.metaKey ? 'meta' : ''}`));
addEventListener('mousedown', (e) => console.log(`mousedown:${e.button}:${e.clientX}:${e.clientY}`));
addEventListener('wheel', (e) => console.log('wheel:' + Math.sign(e.deltaY)));
addEventListener('resize', () => console.log(`resize ${innerWidth}x${innerHeight}`));
console.warn('a warning');
</script></body></html>"##;

fn serve() -> String {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        // A connection each, browsers open some ahead of time that never send anything
        for stream in listener.incoming().flatten() {
            std::thread::spawn(move || {
                let mut reader = BufReader::new(&stream);
                let mut request = String::new();
                reader.read_line(&mut request).ok();
                while reader.read_line(&mut String::new()).map(|n| n > 2).unwrap_or(false) {}
                let (status, body) = if request.starts_with("GET /missing") { ("404 Not Found", "missing") } else { ("200 OK", PAGE) };
                let mut stream = &stream;
                write!(stream, "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()).ok();
            });
        }
    });
    format!("http://{address}")
}

struct Test {
    engine: Engine,
    events: Vec<Event>,
    failures: Vec<String>,
    out: PathBuf,
    frames: u64,
}

impl Test {
    fn pump(&mut self) {
        self.engine.update();
        for event in api::take_events() {
            match event.kind {
                EventKind::Log => println!("  log[{}] {}", event.code, event.text),
                _ => println!("  event {:?} browser={} code={} value={} text={:?} detail={:?}", event.kind, event.browser,
                    event.code, event.value, event.text, event.detail),
            }
            self.events.push(event);
        }
        std::thread::sleep(Duration::from_millis(5));
    }

    /// Waits for an event and removes the events up to it.
    fn wait_for(&mut self, what: &str, timeout: Duration, matches: impl Fn(&Event) -> bool) -> Option<Event> {
        let started = Instant::now();
        loop {
            if let Some(index) = self.events.iter().position(&matches) {
                let event = self.events.remove(index);
                println!("ok: {what} after {} ms", started.elapsed().as_millis());
                return Some(event);
            }
            if started.elapsed() > timeout {
                self.fail(format!("{what}: nothing within {} s", timeout.as_secs()));
                return None;
            }
            self.pump();
        }
    }

    fn console(&mut self, text: &str) -> bool {
        let text = text.to_string();
        self.wait_for(&format!("console '{text}'"), Duration::from_secs(5),
            move |e| e.kind == EventKind::Console && e.text == text).is_some()
    }

    fn fail(&mut self, message: String) {
        println!("FAILED: {message}");
        self.failures.push(message);
    }

    fn check(&mut self, ok: bool, message: impl Into<String>) {
        let message = message.into();
        if ok {
            println!("ok: {message}");
        } else {
            self.fail(message);
        }
    }

    /// Waits for a frame that shows what `ready` looks for, and returns the last one seen otherwise.
    fn frame_where(&mut self, browser: BrowserId, width: u32, height: u32, ready: impl Fn(&Image) -> bool) -> Option<Image> {
        let started = Instant::now();
        let mut last = None;
        while started.elapsed() < Duration::from_secs(10) {
            let Some(image) = self.frame(browser, width, height) else { break };
            if ready(&image) {
                return Some(image);
            }
            last = Some(image);
        }
        last
    }

    /// Waits for a frame of the given size, or a multiple of it on a screen that scales, as BGRA rows.
    fn frame(&mut self, browser: BrowserId, width: u32, height: u32) -> Option<Image> {
        let started = Instant::now();
        let fits = |w: u32, h: u32| w >= width && w % width == 0 && h * width == w * height;
        while started.elapsed() < Duration::from_secs(10) {
            if let Some(frame) = self.engine.take_frame(browser) {
                self.frames += 1;
                let image = match frame {
                    Frame::Pixels { data, len, width: w, height: h, stride, bgra, flipped } if fits(w, h) => {
                        let data = unsafe { std::slice::from_raw_parts(data, len) };
                        Some(Image::from_rows(data, w, h, stride as usize, bgra, flipped))
                    }
                    Frame::DmaBuf { width, height, fourcc, modifier, .. } => {
                        println!("ok: dmabuf frame {width}x{height} fourcc {fourcc:#x} modifier {modifier:#x}");
                        return None;
                    }
                    Frame::SharedTexture { handle, width: w, height: h } if fits(w, h) => gpu::read_shared(handle, w, h),
                    Frame::IoSurface { surface, width: w, height: h, flipped } if fits(w, h) =>
                        gpu::read_surface(surface, w, h, flipped),
                    _ => None,
                };
                if let Some(image) = image {
                    println!("ok: {}x{} {} frame after {} ms", image.width, image.height, frame_kind(&frame),
                        started.elapsed().as_millis());
                    return Some(image.scaled_to(width));
                }
            }
            self.pump();
        }
        self.fail(format!("no {width}x{height} frame within 10 s"));
        None
    }

    fn save(&self, name: &str, image: &Image) {
        let mut rgba = image.pixels.clone();
        rgba.chunks_exact_mut(4).for_each(|p| p.swap(0, 2));
        let path = self.out.join(format!("{name}.png"));
        let file = std::fs::File::create(&path).unwrap();
        let mut encoder = png::Encoder::new(std::io::BufWriter::new(file), image.width, image.height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder.write_header().unwrap().write_image_data(&rgba).unwrap();
        println!("saved {}", path.display());
    }

    fn input(&mut self, browser: BrowserId, pointer: Pointer, x: f64, y: f64) {
        self.engine.pointer(browser, x, y, pointer);
        let until = Instant::now() + Duration::from_millis(50);
        while Instant::now() < until {
            self.pump();
        }
    }

    fn key(&mut self, browser: BrowserId, keycode: i32, scancode: i32, modifiers: i32) {
        for pressed in [true, false] {
            self.engine.key(browser, Key { pressed, keycode, scancode, modifiers });
        }
    }
}

fn frame_kind(frame: &Frame) -> &'static str {
    match frame {
        Frame::Pixels { .. } => "memory",
        Frame::DmaBuf { .. } => "dmabuf",
        Frame::SharedTexture { .. } => "shared texture",
        Frame::IoSurface { .. } => "IOSurface",
    }
}

/// BGRA rows from the top, and how many of its pixels make one pixel of the page.
struct Image {
    pixels: Vec<u8>,
    width: u32,
    height: u32,
    scale: u32,
}

impl Image {
    fn from_rows(data: &[u8], width: u32, height: u32, stride: usize, bgra: bool, flipped: bool) -> Self {
        let mut pixels = Vec::with_capacity((width * height * 4) as usize);
        for row in 0..height {
            let row = if flipped { height - 1 - row } else { row };
            let start = row as usize * stride;
            pixels.extend_from_slice(&data[start..start + (width * 4) as usize]);
        }
        if !bgra {
            pixels.chunks_exact_mut(4).for_each(|p| p.swap(0, 2));
        }
        Self { pixels, width, height, scale: 1 }
    }

    fn scaled_to(mut self, width: u32) -> Self {
        self.scale = self.width / width;
        self
    }
}

fn pixel(image: &Image, x: u32, y: u32) -> [u8; 4] {
    let i = (((y * image.scale) * image.width + x * image.scale) * 4) as usize;
    [image.pixels[i], image.pixels[i + 1], image.pixels[i + 2], image.pixels[i + 3]]
}

/// Reads frames that stay on the GPU back into memory.
mod gpu {
    #[allow(unused_imports)]
    use super::Image;

    #[cfg(target_os = "windows")]
    pub fn read_shared(handle: i64, width: u32, height: u32) -> Option<Image> {
        use windows::core::Interface;
        use windows::Win32::Foundation::{HANDLE, HMODULE};
        use windows::Win32::Graphics::Direct3D::*;
        use windows::Win32::Graphics::Direct3D11::*;
        use windows::Win32::Graphics::Dxgi::Common::*;
        unsafe {
            let (mut device, mut context) = (None, None);
            D3D11CreateDevice(None, D3D_DRIVER_TYPE_HARDWARE, HMODULE::default(), D3D11_CREATE_DEVICE_BGRA_SUPPORT, None,
                D3D11_SDK_VERSION, Some(&mut device), None, Some(&mut context))
                .or_else(|_| D3D11CreateDevice(None, D3D_DRIVER_TYPE_WARP, HMODULE::default(),
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT, None, D3D11_SDK_VERSION, Some(&mut device), None, Some(&mut context)))
                .ok()?;
            let (device, context) = (device?, context?);
            let shared: ID3D11Texture2D = device.cast::<ID3D11Device1>().ok()?
                .OpenSharedResource1(HANDLE(handle as _)).ok()?;
            let desc = D3D11_TEXTURE2D_DESC {
                Width: width,
                Height: height,
                MipLevels: 1,
                ArraySize: 1,
                Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                Usage: D3D11_USAGE_STAGING,
                BindFlags: 0,
                CPUAccessFlags: D3D11_CPU_ACCESS_READ.0 as u32,
                MiscFlags: 0,
            };
            let mut staging = None;
            device.CreateTexture2D(&desc, None, Some(&mut staging)).ok()?;
            let staging = staging?;
            context.CopyResource(&staging, &shared);
            let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
            context.Map(&staging, 0, D3D11_MAP_READ, 0, Some(&mut mapped)).ok()?;
            let data = std::slice::from_raw_parts(mapped.pData as *const u8, mapped.RowPitch as usize * height as usize);
            let image = Image::from_rows(data, width, height, mapped.RowPitch as usize, true, false);
            context.Unmap(&staging, 0);
            Some(image)
        }
    }

    #[cfg(target_os = "macos")]
    pub fn read_surface(surface: i64, width: u32, height: u32, flipped: bool) -> Option<Image> {
        use objc2_io_surface::{IOSurfaceLockOptions, IOSurfaceRef};
        let surface = unsafe { &*(surface as *const IOSurfaceRef) };
        unsafe {
            surface.lock(IOSurfaceLockOptions::ReadOnly, std::ptr::null_mut());
            let stride = surface.bytes_per_row();
            let data = std::slice::from_raw_parts(surface.base_address().as_ptr() as *const u8, stride * height as usize);
            let image = Image::from_rows(data, width, height, stride, true, flipped);
            surface.unlock(IOSurfaceLockOptions::ReadOnly, std::ptr::null_mut());
            Some(image)
        }
    }

    #[cfg(not(target_os = "windows"))]
    pub fn read_shared(_handle: i64, _width: u32, _height: u32) -> Option<Image> {
        None
    }

    #[cfg(not(target_os = "macos"))]
    pub fn read_surface(_surface: i64, _width: u32, _height: u32, _flipped: bool) -> Option<Image> {
        None
    }
}

fn main() {
    let out = PathBuf::from(std::env::args().nth(1).unwrap_or_else(|| "selftest-out".into()));
    let gpu_frames = std::env::args().nth(2).as_deref() == Some("gpu");
    std::fs::create_dir_all(&out).unwrap();
    #[cfg(target_os = "macos")]
    {
        // What SDL does for the game
        use objc2_app_kit::{NSApplication, NSApplicationActivationPolicy};
        let app = NSApplication::sharedApplication(objc2::MainThreadMarker::new().unwrap());
        app.setActivationPolicy(NSApplicationActivationPolicy::Accessory);
        app.finishLaunching();
    }
    let base = serve();
    let data_dir = std::env::temp_dir().join(format!("liquidbounce-wry-selftest-{}", std::process::id()));

    let started = Instant::now();
    let engine = match Engine::start(StartOptions { data_dir: data_dir.clone(), gpu_frames, render_node: None, formats: Vec::new() }) {
        Ok(engine) => engine,
        Err(error) => {
            println!("FAILED: start: {error}");
            std::process::exit(1);
        }
    };
    println!("ok: started in {} ms", started.elapsed().as_millis());
    let mut test = Test { engine, events: Vec::new(), failures: Vec::new(), out, frames: 0 };

    let (width, height) = (800, 600);
    let browser = test.engine.create_browser(BrowserOptions {
        url: format!("{base}/"), width, height, zoom: 1.0, incognito: false, fps: 60,
    }).unwrap();
    if let Some(loaded) = test.wait_for("page loaded", Duration::from_secs(30), |e| e.kind == EventKind::Loaded) {
        test.check(loaded.code == 200, format!("HTTP status {}", loaded.code));
    }
    test.console(&format!("ready {width}x{height}"));
    test.wait_for("console warning", Duration::from_secs(5), |e| e.kind == EventKind::Console && e.code == 2);

    let painted = |image: &Image| pixel(image, 10, 10) == [0, 0, 255, 255] && pixel(image, 500, 150)[3] > 0;
    if let Some(image) = test.frame_where(browser, width, height, painted) {
        test.save("frame", &image);
        let marker = pixel(&image, 10, 10);
        test.check(marker == [0, 0, 255, 255], format!("marker pixel is red: {marker:?}"));
        let background = pixel(&image, 700, 500);
        test.check(background[3] == 0, format!("background is transparent: {background:?}"));
        let panel = pixel(&image, 500, 150);
        test.check((120..=136).contains(&panel[3]) && panel[0] >= 120 && panel[2] == 0,
            format!("half transparent panel is premultiplied blue: {panel:?}"));
    }

    // Measures how many frames arrive at 60 fps while the page is idle
    let started = Instant::now();
    let before = test.frames;
    while started.elapsed() < Duration::from_secs(2) {
        if test.engine.take_frame(browser).is_some() {
            test.frames += 1;
        }
        test.pump();
    }
    println!("info: {} frames in 2 s while idle", test.frames - before);

    test.input(browser, Pointer::Move, 60.0, 415.0);
    test.wait_for("pointer cursor over the link", Duration::from_secs(5),
        |e| e.kind == EventKind::Cursor && e.text == "pointer");
    test.input(browser, Pointer::Move, 100.0, 320.0);
    test.wait_for("text cursor over the input", Duration::from_secs(5), |e| e.kind == EventKind::Cursor && e.text == "text");
    test.input(browser, Pointer::Button(MouseButton::Left, true), 100.0, 320.0);
    test.input(browser, Pointer::Button(MouseButton::Left, false), 100.0, 320.0);
    test.console("mousedown:0:100:320");
    test.engine.focus(browser);

    test.engine.text(browser, "héllo wörld".into());
    test.console("input:héllo wörld");
    // Backspace, then Ctrl+A and a replacement
    test.key(browser, 8, 42, 0);
    test.console("keydown:Backspace:Backspace:");
    test.console("input:héllo wörl");
    // Select all is Cmd+A on macOS, where Ctrl+A goes to the start of the line
    let (select_all, name) = if cfg!(target_os = "macos") { (MOD_GUI & 0x400, "meta") } else { (MOD_CTRL & 0x40, "ctrl") };
    test.key(browser, 'a' as i32, 4, select_all);
    test.console(&format!("keydown:a:KeyA:{name}"));
    test.engine.text(browser, "x".into());
    test.console("input:x");
    test.key(browser, 0x4000_0050, 80, 0);
    test.console("keydown:ArrowLeft:ArrowLeft:");
    // A numpad digit comes as a key and as text, and must only be typed once
    test.key(browser, 0x4000_0059, 89, 0);
    test.engine.text(browser, "1".into());
    test.console("input:1x");

    test.input(browser, Pointer::Scroll(1.0), 400.0, 300.0);
    test.console("wheel:-1");
    test.input(browser, Pointer::Scroll(-1.0), 400.0, 300.0);
    test.console("wheel:1");
    test.input(browser, Pointer::Button(MouseButton::Right, true), 200.0, 200.0);
    test.input(browser, Pointer::Button(MouseButton::Right, false), 200.0, 200.0);
    test.console("mousedown:2:200:200");

    // Half the texture at half the zoom keeps the layout, once a frame painted at the new zoom arrives
    test.engine.resize(browser, 400, 300, 0.5);
    test.console("resize 800x600");
    let zoomed = |image: &Image| pixel(image, 20, 20) == [0, 0, 255, 255] && pixel(image, 40, 40)[3] == 0;
    if let Some(image) = test.frame_where(browser, 400, 300, zoomed) {
        test.save("resized", &image);
        let marker = pixel(&image, 20, 20);
        test.check(marker == [0, 0, 255, 255], format!("marker at half size: {marker:?}"));
        let outside = pixel(&image, 40, 40);
        test.check(outside[3] == 0, format!("marker ends at 32 px: {outside:?}"));
    }
    test.engine.resize(browser, width, height, 1.0);

    test.engine.navigate(browser, format!("{base}/missing"));
    if let Some(loaded) = test.wait_for("missing page loaded", Duration::from_secs(10), |e| e.kind == EventKind::Loaded) {
        test.check(loaded.code == 404, format!("HTTP status of the missing page {}", loaded.code));
    }
    test.engine.go_back(browser);
    let home = format!("{base}/");
    test.wait_for("back to the first page", Duration::from_secs(10), move |e| e.kind == EventKind::Url && e.text == home);
    test.wait_for("first page loaded again", Duration::from_secs(10), |e| e.kind == EventKind::Loaded);
    test.events.clear();
    test.engine.reload(browser, true);
    test.wait_for("reloading", Duration::from_secs(10), |e| e.kind == EventKind::Loading);
    test.wait_for("reloaded", Duration::from_secs(10), |e| e.kind == EventKind::Loaded);

    let closed = TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap();
    test.engine.navigate(browser, format!("http://{closed}/"));
    test.wait_for("unreachable page fails", Duration::from_secs(10), |e| e.kind == EventKind::Failed);
    let until = Instant::now() + Duration::from_millis(500);
    while Instant::now() < until {
        test.pump();
    }
    let loaded = test.events.iter().any(|e| e.kind == EventKind::Loaded);
    test.check(!loaded, "a failed page stays failed");

    let incognito = test.engine.create_browser(BrowserOptions {
        url: format!("{base}/"), width: 320, height: 240, zoom: 1.0, incognito: true, fps: 30,
    }).unwrap();
    test.wait_for("incognito page loaded", Duration::from_secs(20),
        move |e| e.kind == EventKind::Loaded && e.browser == incognito);
    test.frame(incognito, 320, 240);
    test.engine.close_browser(incognito);
    test.engine.close_browser(browser);

    let started = Instant::now();
    test.engine.stop();
    println!("ok: stopped in {} ms", started.elapsed().as_millis());
    std::fs::remove_dir_all(&data_dir).ok();

    if test.failures.is_empty() {
        println!("== PASSED ==");
    } else {
        println!("== FAILED: {} ==", test.failures.len());
        for failure in &test.failures {
            println!("- {failure}");
        }
        std::process::exit(1);
    }
}
