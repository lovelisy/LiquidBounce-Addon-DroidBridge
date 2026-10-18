//! A Wayland compositor only this process talks to. GTK shows each browser's window on it, and every frame the
//! window commits arrives here as a buffer: shared memory, or a dmabuf when the game's GPU can import it.

use super::{DmaBufFrame, ShmFrame, Slots, TITLE_PREFIX};
use crate::api::{self, BrowserId, EventKind, MouseButton, Pointer};
use smithay::backend::allocator::{dmabuf::Dmabuf, Format, Fourcc, Modifier};
use smithay::backend::input::{Axis, AxisSource, ButtonState};
use smithay::input::keyboard::XkbConfig;
use smithay::input::pointer::{AxisFrame, ButtonEvent, CursorImageStatus, MotionEvent};
use smithay::input::{Seat, SeatHandler, SeatState};
use smithay::output::{Output, PhysicalProperties, Subpixel};
use smithay::reexports::calloop::{channel, generic::Generic, timer::{TimeoutAction, Timer}, EventLoop, Interest, LoopSignal, Mode, PostAction};
use smithay::reexports::wayland_protocols::xdg::shell::server::xdg_toplevel;
use smithay::reexports::wayland_server::backend::{ClientData, ClientId, DisconnectReason, ObjectId};
use smithay::reexports::wayland_server::protocol::{wl_buffer::WlBuffer, wl_seat::WlSeat, wl_surface::WlSurface};
use smithay::reexports::wayland_server::{Client, Display, DisplayHandle, ListeningSocket, Resource};
use smithay::utils::{Serial, Transform, SERIAL_COUNTER};
use smithay::wayland::buffer::BufferHandler;
use smithay::wayland::compositor::{with_states, BufferAssignment, CompositorClientState, CompositorHandler, CompositorState, SurfaceAttributes};
use smithay::wayland::dmabuf::{get_dmabuf, DmabufFeedbackBuilder, DmabufGlobal, DmabufHandler, DmabufState, ImportNotifier};
use smithay::wayland::output::{OutputHandler, OutputManagerState};
use smithay::wayland::selection::data_device::{ClientDndGrabHandler, DataDeviceHandler, DataDeviceState, ServerDndGrabHandler};
use smithay::wayland::selection::SelectionHandler;
use smithay::wayland::shell::xdg::{PopupSurface, PositionerState, ToplevelSurface, XdgShellHandler, XdgShellState, XdgToplevelSurfaceData};
use smithay::wayland::shm::{with_buffer_contents, ShmHandler, ShmState};
use smithay::{delegate_compositor, delegate_data_device, delegate_dmabuf, delegate_output, delegate_seat, delegate_shm, delegate_xdg_shell};
use std::collections::HashMap;
use std::os::fd::{AsRawFd, OwnedFd};
use std::path::{Path, PathBuf};
use std::sync::{mpsc, Arc};
use std::time::{Duration, Instant};

pub enum Command {
    Size { browser: BrowserId, width: u32, height: u32 },
    Fps { browser: BrowserId, fps: u32 },
    Pointer { browser: BrowserId, x: f64, y: f64, pointer: Pointer },
    Focus(BrowserId),
    Release(WlBuffer),
    Remove(BrowserId),
}

pub struct Compositor {
    commands: channel::Sender<Command>,
    stop: LoopSignal,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Compositor {
    pub fn start(socket: &Path, slots: Slots, render_node: Option<&Path>, formats: Vec<(u32, u64)>) -> api::Result<Self> {
        let socket = socket.to_path_buf();
        let render_node = render_node.map(Path::to_path_buf);
        let (ready_tx, ready_rx) = mpsc::channel();
        let thread = std::thread::Builder::new()
            .name("wry-compositor".into())
            .spawn(move || run(socket, slots, render_node, formats, ready_tx))
            .map_err(|e| e.to_string())?;
        let (commands, stop) = ready_rx.recv_timeout(Duration::from_secs(10))
            .map_err(|_| "The compositor did not start".to_string())??;
        Ok(Self { commands, stop, thread: Some(thread) })
    }

    pub fn send(&self, command: Command) {
        self.commands.send(command).ok();
    }

    pub fn stop(&mut self) {
        self.stop.stop();
        self.stop.wakeup();
        if let Some(thread) = self.thread.take() {
            thread.join().ok();
        }
    }
}

#[derive(Default)]
struct ClientState {
    compositor: CompositorClientState,
}

impl ClientData for ClientState {
    fn initialized(&self, _client_id: ClientId) {}
    fn disconnected(&self, _client_id: ClientId, _reason: DisconnectReason) {}
}

struct State {
    display: DisplayHandle,
    compositor: CompositorState,
    xdg_shell: XdgShellState,
    shm: ShmState,
    dmabuf: DmabufState,
    _dmabuf_global: Option<DmabufGlobal>,
    seat_state: SeatState<State>,
    data_device: DataDeviceState,
    _output_manager: OutputManagerState,
    seat: Seat<State>,
    slots: Slots,
    toplevels: HashMap<BrowserId, ToplevelSurface>,
    sizes: HashMap<BrowserId, (u32, u32)>,
    fps: HashMap<BrowserId, u32>,
    last_frame_callbacks: HashMap<BrowserId, Instant>,
    buffer_ids: HashMap<ObjectId, u64>,
    next_buffer_id: u64,
    started: Instant,
}

impl State {
    fn browser_of(&self, surface: &WlSurface) -> Option<BrowserId> {
        self.toplevels.iter().find(|(_, toplevel)| toplevel.wl_surface() == surface).map(|(id, _)| *id)
    }

    fn time(&self) -> u32 {
        self.started.elapsed().as_millis() as u32
    }

    fn configure(&self, browser: BrowserId) {
        let (Some(toplevel), Some(&(width, height))) = (self.toplevels.get(&browser), self.sizes.get(&browser)) else {
            return;
        };
        toplevel.with_pending_state(|state| {
            state.size = Some((width as i32, height as i32).into());
            // Activated keeps GTK drawing the focus and the caret
            state.states.set(xdg_toplevel::State::Activated);
        });
        if toplevel.is_initial_configure_sent() {
            toplevel.send_pending_configure();
        } else {
            toplevel.send_configure();
        }
    }

    fn buffer_id(&mut self, buffer: &WlBuffer) -> u64 {
        let next = &mut self.next_buffer_id;
        *self.buffer_ids.entry(buffer.id()).or_insert_with(|| {
            *next += 1;
            *next
        })
    }

    fn command(&mut self, command: Command) {
        match command {
            Command::Size { browser, width, height } => {
                self.sizes.insert(browser, (width, height));
                self.configure(browser);
            }
            Command::Fps { browser, fps } => {
                self.fps.insert(browser, fps.max(1));
            }
            Command::Pointer { browser, x, y, pointer } => self.pointer(browser, x, y, pointer),
            Command::Focus(browser) => {
                if let Some(surface) = self.toplevels.get(&browser).map(|t| t.wl_surface().clone()) {
                    let keyboard = self.seat.get_keyboard().unwrap();
                    if keyboard.current_focus().as_ref() != Some(&surface) {
                        keyboard.set_focus(self, Some(surface), SERIAL_COUNTER.next_serial());
                    }
                }
            }
            Command::Release(buffer) => buffer.release(),
            Command::Remove(browser) => {
                self.toplevels.remove(&browser);
                self.sizes.remove(&browser);
                self.fps.remove(&browser);
                self.last_frame_callbacks.remove(&browser);
            }
        }
    }

    fn pointer(&mut self, browser: BrowserId, x: f64, y: f64, input: Pointer) {
        let Some(surface) = self.toplevels.get(&browser).map(|t| t.wl_surface().clone()) else { return };
        let pointer = self.seat.get_pointer().unwrap();
        let time = self.time();
        pointer.motion(self, Some((surface, (0.0, 0.0).into())), &MotionEvent {
            location: (x, y).into(),
            serial: SERIAL_COUNTER.next_serial(),
            time,
        });
        // GTK drops scrolling that shares a frame with motion
        pointer.frame(self);
        match input {
            Pointer::Move => return,
            Pointer::Button(button, pressed) => {
                let code = match button {
                    MouseButton::Left => 0x110,
                    MouseButton::Right => 0x111,
                    MouseButton::Middle => 0x112,
                };
                pointer.button(self, &ButtonEvent {
                    serial: SERIAL_COUNTER.next_serial(),
                    time,
                    button: code,
                    state: if pressed { ButtonState::Pressed } else { ButtonState::Released },
                });
            }
            Pointer::Scroll(steps) => {
                // One wheel step is 10 units and 120 in high resolution, down is positive
                pointer.axis(self, AxisFrame::new(time)
                    .source(AxisSource::Wheel)
                    .value(Axis::Vertical, -steps * 10.0)
                    .v120(Axis::Vertical, (-steps * 120.0) as i32));
            }
        }
        pointer.frame(self);
    }

    /// Lets each browser's window draw its next frame at the rate it was given.
    fn frame_callbacks(&mut self) {
        let now = Instant::now();
        let time = self.time();
        for (browser, toplevel) in &self.toplevels {
            let interval = Duration::from_secs_f64(1.0 / *self.fps.get(browser).unwrap_or(&60) as f64);
            let last = self.last_frame_callbacks.entry(*browser).or_insert(now - interval);
            if now.duration_since(*last) < interval {
                continue;
            }
            *last = now;
            with_states(toplevel.wl_surface(), |states| {
                for callback in states.cached_state.get::<SurfaceAttributes>().current().frame_callbacks.drain(..) {
                    callback.done(time);
                }
            });
        }
    }
}

impl BufferHandler for State {
    fn buffer_destroyed(&mut self, buffer: &WlBuffer) {
        if let Some(id) = self.buffer_ids.remove(&buffer.id()) {
            api::push(api::Event { browser: 0, kind: EventKind::BufferGone, code: 0, value: id as i64, text: String::new(),
                detail: String::new() });
        }
    }
}

impl CompositorHandler for State {
    fn compositor_state(&mut self) -> &mut CompositorState {
        &mut self.compositor
    }

    fn client_compositor_state<'a>(&self, client: &'a Client) -> &'a CompositorClientState {
        &client.get_data::<ClientState>().unwrap().compositor
    }

    fn commit(&mut self, surface: &WlSurface) {
        if let Some(toplevel) = self.xdg_shell.toplevel_surfaces().iter().find(|t| t.wl_surface() == surface).cloned() {
            if !toplevel.is_initial_configure_sent() {
                // The window's title names the browser it belongs to
                let title = with_states(surface, |states| {
                    states.data_map.get::<XdgToplevelSurfaceData>().and_then(|d| d.lock().unwrap().title.clone())
                });
                if let Some(browser) = title.and_then(|t| t.strip_prefix(TITLE_PREFIX)?.parse::<BrowserId>().ok()) {
                    self.toplevels.insert(browser, toplevel.clone());
                    self.configure(browser);
                }
                if !toplevel.is_initial_configure_sent() {
                    toplevel.send_configure();
                }
            }
        }

        let buffer = with_states(surface, |states| {
            match states.cached_state.get::<SurfaceAttributes>().current().buffer.take() {
                Some(BufferAssignment::NewBuffer(buffer)) => Some(buffer),
                _ => None,
            }
        });
        let Some(buffer) = buffer else { return };
        let Some(browser) = self.browser_of(surface) else {
            buffer.release();
            return;
        };

        if let Ok(dmabuf) = get_dmabuf(&buffer).cloned() {
            let id = self.buffer_id(&buffer);
            let mut slots = self.slots.lock().unwrap();
            let Some(slot) = slots.get_mut(&browser) else {
                buffer.release();
                return;
            };
            let replaced = slot.dmabuf.replace(DmaBufFrame { id, buffer, dmabuf });
            // A frame the game never took is free again right away
            if let Some(replaced) = replaced {
                if replaced.id != id {
                    replaced.buffer.release();
                }
            }
            if let Some(old) = slot.shm.take() {
                slot.spare = Some(old.data);
            }
            return;
        }

        let slots = self.slots.clone();
        let copied = with_buffer_contents(&buffer, |ptr, len, data| {
            let start = data.offset as usize;
            let size = data.stride as usize * data.height as usize;
            if start + size > len {
                return;
            }
            let pixels = unsafe { std::slice::from_raw_parts(ptr.add(start), size) };
            let mut slots = slots.lock().unwrap();
            let Some(slot) = slots.get_mut(&browser) else { return };
            let mut data_buffer = slot.spare.take().unwrap_or_default();
            data_buffer.clear();
            data_buffer.extend_from_slice(pixels);
            if let Some(old) = slot.shm.replace(ShmFrame {
                data: data_buffer,
                width: data.width as u32,
                height: data.height as u32,
                stride: data.stride as u32,
            }) {
                slot.spare = Some(old.data);
            }
            if let Some(old) = slot.dmabuf.take() {
                old.buffer.release();
            }
        });
        if copied.is_err() {
            api::warn("A window committed a buffer that is neither shared memory nor a dmabuf");
        }
        buffer.release();
    }
}

impl XdgShellHandler for State {
    fn xdg_shell_state(&mut self) -> &mut XdgShellState {
        &mut self.xdg_shell
    }

    fn new_toplevel(&mut self, _surface: ToplevelSurface) {}

    fn new_popup(&mut self, surface: PopupSurface, _positioner: PositionerState) {
        // Menus and tooltips have nowhere to show, they are closed right away
        surface.send_popup_done();
    }

    fn grab(&mut self, surface: PopupSurface, _seat: WlSeat, _serial: Serial) {
        surface.send_popup_done();
    }

    fn reposition_request(&mut self, _surface: PopupSurface, _positioner: PositionerState, _token: u32) {}
}

impl ShmHandler for State {
    fn shm_state(&self) -> &ShmState {
        &self.shm
    }
}

impl DmabufHandler for State {
    fn dmabuf_state(&mut self) -> &mut DmabufState {
        &mut self.dmabuf
    }

    fn dmabuf_imported(&mut self, _global: &DmabufGlobal, _dmabuf: Dmabuf, notifier: ImportNotifier) {
        // The game imports it on its own context once it is shown
        let _ = notifier.successful::<State>();
    }
}

impl SeatHandler for State {
    type KeyboardFocus = WlSurface;
    type PointerFocus = WlSurface;
    type TouchFocus = WlSurface;

    fn seat_state(&mut self) -> &mut SeatState<Self> {
        &mut self.seat_state
    }

    fn cursor_image(&mut self, _seat: &Seat<Self>, _image: CursorImageStatus) {}
}

impl SelectionHandler for State {
    type SelectionUserData = ();
}

impl DataDeviceHandler for State {
    fn data_device_state(&self) -> &DataDeviceState {
        &self.data_device
    }
}

impl ClientDndGrabHandler for State {}

impl ServerDndGrabHandler for State {
    fn send(&mut self, _mime_type: String, _fd: OwnedFd, _seat: Seat<Self>) {}
}

impl OutputHandler for State {}

delegate_compositor!(State);
delegate_xdg_shell!(State);
delegate_shm!(State);
delegate_dmabuf!(State);
delegate_seat!(State);
delegate_data_device!(State);
delegate_output!(State);

fn device_of(path: &Path) -> Option<libc::dev_t> {
    let path = std::ffi::CString::new(path.as_os_str().as_encoded_bytes()).ok()?;
    let mut stat: libc::stat = unsafe { std::mem::zeroed() };
    (unsafe { libc::stat(path.as_ptr(), &mut stat) } == 0).then_some(stat.st_rdev)
}

type Ready = api::Result<(channel::Sender<Command>, LoopSignal)>;

fn run(socket: PathBuf, slots: Slots, render_node: Option<PathBuf>, formats: Vec<(u32, u64)>, ready: mpsc::Sender<Ready>) {
    let setup = || -> api::Result<(EventLoop<'static, State>, State)> {
        let event_loop: EventLoop<State> = EventLoop::try_new().map_err(|e| e.to_string())?;
        let display: Display<State> = Display::new().map_err(|e| e.to_string())?;
        let dh = display.handle();

        let mut dmabuf = DmabufState::new();
        let dmabuf_global = render_node.as_deref().and_then(|node| {
            let Some(device) = device_of(node) else {
                api::warn(format!("Render node {} not found, the page is copied through memory", node.display()));
                return None;
            };
            let mut formats: Vec<Format> = formats.iter()
                .filter_map(|&(code, modifier)| Some(Format { code: Fourcc::try_from(code).ok()?, modifier: modifier.into() }))
                .collect();
            if formats.is_empty() {
                formats = [Fourcc::Argb8888, Fourcc::Xrgb8888, Fourcc::Abgr8888, Fourcc::Xbgr8888].into_iter()
                    .flat_map(|code| [Modifier::Linear, Modifier::Invalid].map(|modifier| Format { code, modifier }))
                    .collect();
            }
            let feedback = DmabufFeedbackBuilder::new(device, formats).build().ok()?;
            api::info(format!("Offering dmabufs on {}", node.display()));
            Some(dmabuf.create_global_with_default_feedback::<State>(&dh, &feedback))
        });

        let mut seat_state = SeatState::new();
        let mut seat = seat_state.new_wl_seat(&dh, "seat0");
        seat.add_keyboard(XkbConfig::default(), 400, 30).map_err(|e| e.to_string())?;
        seat.add_pointer();

        let output = Output::new("liquidbounce".into(), PhysicalProperties {
            size: (0, 0).into(),
            subpixel: Subpixel::Unknown,
            make: "LiquidBounce".into(),
            model: "Wry".into(),
        });
        output.create_global::<State>(&dh);
        let mode = smithay::output::Mode { size: (1920, 1080).into(), refresh: 60_000 };
        output.change_current_state(Some(mode), Some(Transform::Normal), None, Some((0, 0).into()));
        output.set_preferred(mode);

        let state = State {
            display: dh.clone(),
            compositor: CompositorState::new::<State>(&dh),
            xdg_shell: XdgShellState::new::<State>(&dh),
            shm: ShmState::new::<State>(&dh, vec![]),
            dmabuf,
            _dmabuf_global: dmabuf_global,
            seat_state,
            data_device: DataDeviceState::new::<State>(&dh),
            _output_manager: OutputManagerState::new_with_xdg_output::<State>(&dh),
            seat,
            slots,
            toplevels: HashMap::new(),
            sizes: HashMap::new(),
            fps: HashMap::new(),
            last_frame_callbacks: HashMap::new(),
            buffer_ids: HashMap::new(),
            next_buffer_id: 0,
            started: Instant::now(),
        };

        std::fs::remove_file(&socket).ok();
        let listener = ListeningSocket::bind_absolute(socket.clone()).map_err(|e| format!("{}: {e}", socket.display()))?;
        let handle = event_loop.handle();
        handle.insert_source(Generic::new(listener, Interest::READ, Mode::Level), |_, listener, state: &mut State| {
            while let Ok(Some(stream)) = listener.accept() {
                state.display.insert_client(stream, Arc::new(ClientState::default())).ok();
            }
            Ok(PostAction::Continue)
        }).map_err(|e| e.to_string())?;
        handle.insert_source(Generic::new(display, Interest::READ, Mode::Level), |_, display, state| {
            unsafe { display.get_mut().dispatch_clients(state).ok() };
            Ok(PostAction::Continue)
        }).map_err(|e| e.to_string())?;
        handle.insert_source(Timer::from_duration(Duration::from_millis(4)), |_, _, state| {
            state.frame_callbacks();
            TimeoutAction::ToDuration(Duration::from_millis(4))
        }).map_err(|e| e.to_string())?;
        Ok((event_loop, state))
    };

    let (mut event_loop, mut state) = match setup() {
        Ok(setup) => setup,
        Err(error) => {
            ready.send(Err(error)).ok();
            return;
        }
    };
    let (commands, receiver) = channel::channel::<Command>();
    event_loop.handle().insert_source(receiver, |event, _, state| {
        if let channel::Event::Msg(command) = event {
            state.command(command);
        }
    }).ok();
    ready.send(Ok((commands, event_loop.get_signal()))).ok();

    let display = state.display.clone();
    event_loop.run(Some(Duration::from_millis(16)), &mut state, |_| {
        display.clone().flush_clients().ok();
    }).ok();
    std::fs::remove_file(&socket).ok();
}

/// Borrowed file descriptors of a dmabuf, valid while its buffer is.
pub fn raw_fds(dmabuf: &Dmabuf) -> Vec<i32> {
    dmabuf.handles().map(|fd| fd.as_raw_fd()).collect()
}
