package com.webtvshell.app.browser;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.webtvshell.app.model.AppConfig;
import com.webtvshell.app.model.ChannelSource;
import com.webtvshell.app.model.SiteProfile;

import org.json.JSONArray;
import org.json.JSONObject;

public final class BrowserController {
    private static final String LOG_TAG = "WebTvShell.WebView";

    public interface Listener {
        void onFullscreenChanged(boolean fullscreen);

        void onMainFrameLoadFailed();
    }

    private final WebView webView;
    private final FrameLayout customViewContainer;
    private final Listener listener;
    private final String defaultUserAgent;
    private final boolean debug;
    private AppConfig config;
    private ChannelSource source;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private boolean cinemaMode;

    @SuppressLint("SetJavaScriptEnabled")
    public BrowserController(WebView webView, FrameLayout customViewContainer, Listener listener) {
        this.webView = webView;
        this.customViewContainer = customViewContainer;
        this.listener = listener;

        debug = (webView.getContext().getApplicationInfo().flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debug) WebView.setWebContentsDebuggingEnabled(true);

        WebSettings settings = webView.getSettings();
        defaultUserAgent = settings.getUserAgentString();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);

        webView.setBackgroundColor(0xff000000);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.setWebViewClient(Build.VERSION.SDK_INT >= 26
                ? new Api26Client()
                : Build.VERSION.SDK_INT >= 23 ? new Api23Client() : new Client());
        webView.setWebChromeClient(new ChromeClient());
    }

    public void load(AppConfig config, ChannelSource source) {
        if (source == null) return;
        hideCustomView();
        cinemaMode = false;
        this.config = config;
        this.source = source;
        SiteProfile profile = config.findProfile(source, source.url);
        webView.getSettings().setUserAgentString(resolveUserAgent(profile));
        webView.getSettings().setBlockNetworkImage(profile != null && profile.blockImages);
        CookieManager.getInstance().setAcceptThirdPartyCookies(
                webView, profile != null && profile.thirdPartyCookies);
        webView.loadUrl(source.url);
    }

    public void handleConfirmGesture() {
        SiteProfile profile = currentProfile(webView.getUrl());
        if (profile == null) {
            evaluate(genericConfirmScript("video", "", new JSONArray()));
        } else {
            evaluate(genericConfirmScript(profile.videoSelector, profile.playButtonSelector,
                    new JSONArray(profile.fullscreenSelectors)));
        }
        cinemaMode = true;
        listener.onFullscreenChanged(true);
    }

    public void enterCinemaMode() {
        SiteProfile profile = currentProfile(webView.getUrl());
        String selector = profile == null ? "video" : profile.videoSelector;
        evaluate(cinemaScript(selector));
        cinemaMode = true;
        listener.onFullscreenChanged(true);
    }

    public boolean isFullscreen() {
        return customView != null || cinemaMode;
    }

    public void exitFullscreen() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (cinemaMode) {
            evaluate("(function(){if(window.__webTvShellCinemaTimer){clearInterval(window.__webTvShellCinemaTimer);window.__webTvShellCinemaTimer=0;}"
                    + "var e=document.querySelector('[data-webtvshell-cinema]');"
                    + "if(e){e.removeAttribute('data-webtvshell-cinema');e.style.cssText=e.dataset.webtvshellStyle||'';}"
                    + "Array.prototype.forEach.call(document.querySelectorAll('[data-webtvshell-media]'),function(m){m.style.cssText=m.dataset.webtvshellMediaStyle||'';m.removeAttribute('data-webtvshell-media');m.removeAttribute('data-webtvshell-media-style');});"
                    + "Array.prototype.forEach.call(document.querySelectorAll('[data-webtvshell-hidden]'),function(h){h.style.cssText=h.dataset.webtvshellHiddenStyle||'';h.removeAttribute('data-webtvshell-hidden');h.removeAttribute('data-webtvshell-hidden-style');});"
                    + "document.documentElement.style.overflow='';document.body.style.overflow='';})();");
            cinemaMode = false;
            listener.onFullscreenChanged(false);
        }
    }

    public void pause() {
        webView.onPause();
    }

    public void resume() {
        webView.onResume();
    }

    public void destroy() {
        hideCustomView();
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.removeAllViews();
        webView.destroy();
    }

    private SiteProfile currentProfile(String url) {
        return config == null ? null : config.findProfile(source, url == null ? source.url : url);
    }

    private String resolveUserAgent(SiteProfile profile) {
        if (profile == null || profile.userAgent == null || profile.userAgent.isEmpty()) {
            return defaultUserAgent;
        }
        if ("desktop-chrome".equals(profile.userAgent)) {
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
        }
        if ("default".equals(profile.userAgent) || "mobile".equals(profile.userAgent)
                || "system".equals(profile.userAgent)) {
            return defaultUserAgent;
        }
        return profile.userAgent;
    }

    private void injectFor(String url) {
        SiteProfile profile = currentProfile(url);
        String video = profile == null ? "video" : profile.videoSelector;
        String play = profile == null ? "" : profile.playButtonSelector;
        JSONArray hide = new JSONArray(profile == null ? java.util.Collections.emptyList() : profile.hideSelectors);
        JSONArray fullscreen = new JSONArray(profile == null ? java.util.Collections.emptyList() : profile.fullscreenSelectors);
        String custom = profile == null ? "" : profile.customJs;
        evaluate(buildBootstrapScript(video, play, hide, fullscreen, custom));
    }

    private void evaluate(String script) {
        webView.evaluateJavascript(script, null);
    }

    private String buildBootstrapScript(String videoSelector, String playSelector,
                                        JSONArray hideSelectors, JSONArray fullscreenSelectors,
                                        String customJs) {
        return "(function(){try{"
                + "if(window.__webTvShellTimer)clearInterval(window.__webTvShellTimer);"
                + "var videoSelector=" + JSONObject.quote(emptyDefault(videoSelector, "video")) + ";"
                + "var playSelector=" + JSONObject.quote(playSelector == null ? "" : playSelector) + ";"
                + "var hide=" + hideSelectors + ";var full=" + fullscreenSelectors + ";var tries=0;"
                + "function first(s){try{return s?document.querySelector(s):null;}catch(e){return null;}}"
                + "function cinema(v){if(!v)return;" + cinemaBody("v") + "}"
                + "function run(){tries++;hide.forEach(function(s){Array.prototype.forEach.call(document.querySelectorAll(s),function(e){e.style.display='none';});});"
                + "var p=first(playSelector);if(p)try{p.click();}catch(e){}var v=first(videoSelector)||first('video');"
                + "if(v){v.autoplay=true;v.playsInline=false;var promise=v.play();"
                + "if(promise&&promise.catch)promise.catch(function(){v.muted=true;v.play();});"
                + "var clicked=false;full.some(function(s){var b=first(s);if(b){try{b.click();clicked=true;}catch(e){}return true;}return false;});"
                + "if(!clicked){try{var f=v.requestFullscreen||v.webkitRequestFullscreen;if(f)f.call(v);}catch(e){}}"
                + "setTimeout(function(){if(!document.fullscreenElement&&!document.webkitFullscreenElement)cinema(v);},700);return true;}return false;}"
                + "run();window.__webTvShellTimer=setInterval(function(){if(run()||tries>20){clearInterval(window.__webTvShellTimer);window.__webTvShellTimer=0;}},500);"
                + (customJs == null || customJs.trim().isEmpty() ? ""
                : "try{(new Function(" + JSONObject.quote(customJs) + "))();}catch(customError){}")
                + "}catch(error){}})();";
    }

    private String genericConfirmScript(String videoSelector, String playSelector, JSONArray fullscreenSelectors) {
        return "(function(){function q(s){try{return s?document.querySelector(s):null;}catch(e){return null;}}"
                + "var p=q(" + JSONObject.quote(playSelector == null ? "" : playSelector) + ");"
                + "var v=q(" + JSONObject.quote(emptyDefault(videoSelector, "video")) + ")||q('video');"
                + "if(p&&(!v||v.paused))p.click();if(v){v.muted=false;if(v.paused)v.play();}"
                + "var selectors=" + fullscreenSelectors + ";var done=false;selectors.some(function(s){var b=q(s);if(b){b.click();done=true;return true;}return false;});"
                + "if(!done&&v){try{var f=v.requestFullscreen||v.webkitRequestFullscreen;if(f)f.call(v);}catch(e){}}"
                + "setTimeout(function(){if(!document.fullscreenElement&&!document.webkitFullscreenElement){"
                + cinemaBody("v") + "}},350);})();";
    }

    private String cinemaScript(String videoSelector) {
        return "(function(){var tries=0,t=setInterval(function(){var v;try{v=document.querySelector("
                + JSONObject.quote(emptyDefault(videoSelector, "video"))
                + ")||document.querySelector('video');}catch(e){}if(v){clearInterval(t);"
                + cinemaBody("v")
                + "}else if(++tries>20)clearInterval(t);},250);})();";
    }

    private static String cinemaBody(String variable) {
        return "var media=" + variable + ";var target=media.parentElement||media;"
                + "if(!target.hasAttribute('data-webtvshell-cinema')){target.dataset.webtvshellStyle=target.getAttribute('style')||'';"
                + "media.dataset.webtvshellMediaStyle=media.getAttribute('style')||'';}"
                + "target.setAttribute('data-webtvshell-cinema','1');media.setAttribute('data-webtvshell-media','1');"
                + "function isolate(){var n=target;while(n&&n.parentElement){var p=n.parentElement;"
                + "Array.prototype.forEach.call(p.children,function(c){if(c!==n&&!c.hasAttribute('data-webtvshell-hidden')){"
                + "c.dataset.webtvshellHiddenStyle=c.getAttribute('style')||'';c.setAttribute('data-webtvshell-hidden','1');"
                + "c.style.setProperty('display','none','important');}});n=p;if(n===document.body)break;}}"
                + "function applyCinema(){isolate();var s=target.style;"
                + "s.setProperty('position','fixed','important');s.setProperty('top','0','important');"
                + "s.setProperty('right','0','important');s.setProperty('bottom','0','important');s.setProperty('left','0','important');"
                + "s.setProperty('width','100vw','important');s.setProperty('height','100vh','important');"
                + "s.setProperty('max-width','none','important');s.setProperty('max-height','none','important');"
                + "s.setProperty('z-index','2147483647','important');s.setProperty('background','#000','important');"
                + "media.style.setProperty('width','100%','important');media.style.setProperty('height','100%','important');"
                + "media.style.setProperty('object-fit','contain','important');document.documentElement.style.overflow='hidden';"
                + "document.body.style.overflow='hidden';}applyCinema();"
                + "if(window.__webTvShellCinemaTimer)clearInterval(window.__webTvShellCinemaTimer);"
                + "window.__webTvShellCinemaTimer=setInterval(function(){if(!document.documentElement.contains(media)){"
                + "clearInterval(window.__webTvShellCinemaTimer);window.__webTvShellCinemaTimer=0;}else applyCinema();},500);";
    }

    private static String emptyDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        cinemaMode = false;
        customViewCallback = callback;
        customViewContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        customViewContainer.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
        listener.onFullscreenChanged(true);
    }

    private void hideCustomView() {
        if (customView == null) return;
        customViewContainer.removeView(customView);
        customViewContainer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customView = null;
        customViewCallback = null;
        listener.onFullscreenChanged(false);
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri uri = request.getUrl();
            String scheme = uri == null ? "" : uri.getScheme();
            return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            cinemaMode = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectFor(url);
            // Bootstrap always falls back to CSS cinema when native fullscreen is unavailable.
            cinemaMode = true;
            listener.onFullscreenChanged(true);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode,
                                    String description, String failingUrl) {
            listener.onMainFrameLoadFailed();
        }

    }

    /** Keeps API 23-only error details out of the client class loaded on Android 5. */
    @TargetApi(23)
    private class Api23Client extends Client {
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame()) listener.onMainFrameLoadFailed();
        }
    }

    /** Keeps the API 26-only renderer callback out of the client class loaded on Android 5-7. */
    @TargetApi(26)
    private final class Api26Client extends Api23Client {
        @Override
        public boolean onRenderProcessGone(WebView view,
                                           android.webkit.RenderProcessGoneDetail detail) {
            listener.onMainFrameLoadFailed();
            return true;
        }
    }

    private final class ChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            if (debug) {
                Log.d(LOG_TAG, message.message() + " (" + message.sourceId() + ":"
                        + message.lineNumber() + ")");
            }
            return super.onConsoleMessage(message);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            showCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            return false;
        }
    }
}
