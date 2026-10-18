//! A navigation delegate in front of Wry's, for the HTTP status and the failures Wry doesn't report.

use crate::api::{self, BrowserId, EventKind};
use block2::Block;
use objc2::rc::Retained;
use objc2::runtime::{NSObject, ProtocolObject};
use objc2::{define_class, msg_send, DefinedClass, MainThreadMarker, MainThreadOnly};
use objc2_foundation::{NSError, NSHTTPURLResponse, NSObjectProtocol, NSString};
use objc2_web_kit::{WKDownload, WKNavigation, WKNavigationAction, WKNavigationActionPolicy, WKNavigationDelegate,
    WKNavigationResponse, WKNavigationResponsePolicy, WKWebView};
use std::cell::Cell;

pub struct Ivars {
    browser: BrowserId,
    inner: Retained<ProtocolObject<dyn WKNavigationDelegate>>,
    status: Cell<i32>,
}

fn url(webview: &WKWebView) -> String {
    unsafe { webview.URL() }.and_then(|url| url.absoluteString()).map(|url| url.to_string()).unwrap_or_default()
}

define_class!(
    #[unsafe(super(NSObject))]
    #[thread_kind = MainThreadOnly]
    #[ivars = Ivars]
    #[name = "LiquidBounceWryNavigationDelegate"]
    pub struct NavigationDelegate;

    unsafe impl NSObjectProtocol for NavigationDelegate {}

    unsafe impl WKNavigationDelegate for NavigationDelegate {
        #[unsafe(method(webView:decidePolicyForNavigationAction:decisionHandler:))]
        fn navigation_policy(&self, webview: &WKWebView, action: &WKNavigationAction,
            handler: &Block<dyn Fn(WKNavigationActionPolicy)>) {
            let inner = &*self.ivars().inner;
            unsafe { msg_send![inner, webView: webview, decidePolicyForNavigationAction: action, decisionHandler: handler] }
        }

        #[unsafe(method(webView:decidePolicyForNavigationResponse:decisionHandler:))]
        fn navigation_response(&self, webview: &WKWebView, response: &WKNavigationResponse,
            handler: &Block<dyn Fn(WKNavigationResponsePolicy)>) {
            if unsafe { response.isForMainFrame() } {
                let status = unsafe { response.response() }.downcast::<NSHTTPURLResponse>()
                    .map_or(0, |http| http.statusCode() as i32);
                self.ivars().status.set(status);
            }
            let inner = &*self.ivars().inner;
            unsafe { msg_send![inner, webView: webview, decidePolicyForNavigationResponse: response, decisionHandler: handler] }
        }

        #[unsafe(method(webView:didStartProvisionalNavigation:))]
        fn did_start(&self, webview: &WKWebView, _navigation: Option<&WKNavigation>) {
            self.ivars().status.set(0);
            api::push_event(self.ivars().browser, EventKind::Loading, 0, url(webview), "");
        }

        #[unsafe(method(webView:didCommitNavigation:))]
        fn did_commit(&self, webview: &WKWebView, navigation: Option<&WKNavigation>) {
            let inner = &*self.ivars().inner;
            unsafe { msg_send![inner, webView: webview, didCommitNavigation: navigation] }
        }

        #[unsafe(method(webView:didFinishNavigation:))]
        fn did_finish(&self, webview: &WKWebView, navigation: Option<&WKNavigation>) {
            api::push_event(self.ivars().browser, EventKind::Loaded, self.ivars().status.get(), url(webview), "");
            let inner = &*self.ivars().inner;
            unsafe { msg_send![inner, webView: webview, didFinishNavigation: navigation] }
        }

        #[unsafe(method(webView:didFailProvisionalNavigation:withError:))]
        fn did_fail_provisional(&self, webview: &WKWebView, _navigation: Option<&WKNavigation>, error: &NSError) {
            self.failed(webview, error);
        }

        #[unsafe(method(webView:didFailNavigation:withError:))]
        fn did_fail(&self, webview: &WKWebView, _navigation: Option<&WKNavigation>, error: &NSError) {
            self.failed(webview, error);
        }

        #[unsafe(method(webView:navigationAction:didBecomeDownload:))]
        fn action_download(&self, webview: &WKWebView, action: &WKNavigationAction, download: &WKDownload) {
            let inner = &*self.ivars().inner;
            unsafe { msg_send![inner, webView: webview, navigationAction: action, didBecomeDownload: download] }
        }

        #[unsafe(method(webView:navigationResponse:didBecomeDownload:))]
        fn response_download(&self, webview: &WKWebView, response: &WKNavigationResponse, download: &WKDownload) {
            let inner = &*self.ivars().inner;
            unsafe { msg_send![inner, webView: webview, navigationResponse: response, didBecomeDownload: download] }
        }

        #[unsafe(method(webViewWebContentProcessDidTerminate:))]
        fn process_terminated(&self, webview: &WKWebView) {
            api::push_event(self.ivars().browser, EventKind::Failed, -2, url(webview), "The page's process ended");
            let inner = &*self.ivars().inner;
            unsafe { msg_send![inner, webViewWebContentProcessDidTerminate: webview] }
        }
    }
);

impl NavigationDelegate {
    pub fn new(browser: BrowserId, inner: Retained<ProtocolObject<dyn WKNavigationDelegate>>, mtm: MainThreadMarker)
        -> Retained<Self> {
        let this = Self::alloc(mtm).set_ivars(Ivars { browser, inner, status: Cell::new(0) });
        unsafe { msg_send![super(this), init] }
    }

    fn failed(&self, webview: &WKWebView, error: &NSError) {
        const NSURL_ERROR_CANCELLED: isize = -999;
        // A navigation that replaced this one
        const FRAME_LOAD_INTERRUPTED: isize = 102;
        let code = error.code();
        if code == NSURL_ERROR_CANCELLED || code == FRAME_LOAD_INTERRUPTED {
            return;
        }
        let failing = error.userInfo().objectForKey(&NSString::from_str("NSErrorFailingURLStringKey"))
            .and_then(|value| value.downcast::<NSString>().ok())
            .map(|value| value.to_string())
            .unwrap_or_else(|| url(webview));
        api::push_event(self.ivars().browser, EventKind::Failed, code as i32, failing,
            error.localizedDescription().to_string());
    }
}
