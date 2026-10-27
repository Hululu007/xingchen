package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.fongmi.android.tv.App;

import org.greenrobot.eventbus.EventBus;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import android.util.Log;

/**
 * 照 8.83 IArticleWebHolder + ArticleWebkitHolder（含内部类 $1 WebChromeClient / $2 WebViewClient /
 * $3 WebViewWrapper）合并转写，系统 WebView 版（对应 WebkitParser 的 holder；HkX5WebViewParser 亦共用，
 * 理由见该类注释）。
 * 桥：initWebView 里经 Wrapper 注册 fy_bridge_app（原版 JsBridgeHolder 注册 VideoInterface），
 * 本转写只实现嗅探链必需的 finishParse（投递 HkDestroyEvent）；VideoInterface 其余浏览器桥方法未移植。
 * 子系统接缝（默认等价/空实现，宿主阶段可换）：JsPluginHelper.loadMyJs 与 AdBlockModel.getBlockJs 走
 * pluginHooks；BrowserProxy.proxy 走 browserProxyHook；UAModel 走 uaHook；dealLoadUrl 的
 * folder/history/download/func 分支走 dealLoadHooks；setUrl 的 home 域名→IP 替换是 8.83 自有服务
 * （home.haikuoshijie.cn→159.75.120.248），星辰无此资源与场景，未转写（见 setUrl 注释）。
 */
public class HkWebkitHolder implements HkWebViewHelper.ServiceWorkerInterceptor {
    private static final String TAG = "HkWebkitHolder";

    // —— 子系统接缝 ——

    /** JsPluginHelper.loadMyJs + AdBlockModel.getBlockJs 的等价接缝。 */
    public interface WebPluginHooks {
        void loadMyJs(Context context, Consumer<String> jsConsumer, String url,
                      Supplier<String> videoUrlsProvider, boolean onPageEnd);

        String getBlockJs(String url);
    }

    /** BrowserProxy.proxy 的等价接缝：返回非 null 即以该响应接管请求。 */
    public interface BrowserProxyHook {
        WebResourceResponse proxy(String myUrl, WebResourceRequest request,
                                  Map<String, Map<String, String>> requestHeaderMap);
    }

    /** UAModel 的等价接缝（按域名调整 UA 的浏览器子系统）。 */
    public interface UaHook {
        String getAdjustUa(String url);

        String getUseUa();
    }

    /** dealLoadUrl 中 folder://、history://、download://、func://background 四支的宿主接缝。 */
    public interface DealLoadHooks {
        void openFolder(Activity activity);

        void openHistory(Activity activity);

        void openDownloads(Activity activity);

        void background(Activity activity);
    }

    public static volatile WebPluginHooks pluginHooks;
    public static volatile BrowserProxyHook browserProxyHook;
    public static volatile UaHook uaHook;
    public static volatile DealLoadHooks dealLoadHooks;

    public interface ProgressListener {
        void onPageStarted(String url);

        void onPageFinished(String url);

        void onProgressChanged(String url, int progress);
    }

    // —— IArticleWebHolder 字段 ——
    protected String extra;
    protected Consumer<String> finishPageConsumer;
    protected FyBridge jsBridgeHolder;
    protected String lastDom = "";
    protected Consumer<String> lazyRuleConsumer;
    protected WeakReference<Activity> reference;
    protected String refererPolicy;
    protected int setSystemUiVisibility;
    protected String systemUA = "";
    protected HkX5Extra x5Extra;

    /** 8.83 shouldOverrideUrlLoading 同款：x5Extra.urlInterceptor 的求值器（JSEngine.evalJS(code, url)）。 */
    public interface UrlInterceptorEvaluator {
        String eval(String code, String url);
    }

    private UrlInterceptorEvaluator urlInterceptorEvaluator;

    public void setUrlInterceptorEvaluator(UrlInterceptorEvaluator e) {
        this.urlInterceptorEvaluator = e;
    }

    /** 原版 BridgeListener.open 同款：fy_bridge_app.open({title,url}) 的页面打开接缝。 */
    public interface BridgeOpenHandler {
        void openBridgePage(String title, String url);
    }

    private BridgeOpenHandler bridgeOpenHandler;

    public void setBridgeOpenHandler(BridgeOpenHandler h) {
        this.bridgeOpenHandler = h;
        if (jsBridgeHolder != null) jsBridgeHolder.setOpenHandler(h);
    }

    // —— ArticleWebkitHolder 字段 ——
    private final AtomicBoolean hasLoadJsOnPageEnd = new AtomicBoolean(false);
    private int height;
    private String myUrl = "";
    private ProgressListener progressListener;
    private final Map<String, Map<String, String>> requestHeaderMap = new HashMap<>();
    private String url;
    private List<String> urls = Collections.synchronizedList(new ArrayList<>());
    private WebView webView;

    public HkWebkitHolder() {
    }

    public HkWebkitHolder(WebView webView) {
        this.webView = webView;
    }

    // —— IArticleWebHolder 方法 ——

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public Consumer<String> getFinishPageConsumer() {
        return finishPageConsumer;
    }

    public void setFinishPageConsumer(Consumer<String> finishPageConsumer) {
        this.finishPageConsumer = finishPageConsumer;
    }

    public Consumer<String> getLazyRuleConsumer() {
        return lazyRuleConsumer;
    }

    public void setLazyRuleConsumer(Consumer<String> lazyRuleConsumer) {
        this.lazyRuleConsumer = lazyRuleConsumer;
    }

    public HkX5Extra getX5Extra() {
        return x5Extra;
    }

    public void setX5Extra(HkX5Extra x5Extra) {
        this.x5Extra = x5Extra;
    }

    protected String getExtraUa() {
        return x5Extra == null ? null : x5Extra.getUa();
    }

    protected String getUserAgentString() {
        if (webView != null) return webView.getSettings().getUserAgentString();
        return "";
    }

    protected void setUserAgentString(String ua) {
        if (webView != null) webView.getSettings().setUserAgentString(ua);
    }

    /** 照 IArticleWebHolder.checkUa：域名变化且 extra 带 ua 时，按 UAModel 调整 UA，否则回落 useUa/systemUA。 */
    protected void checkUa(String url) {
        String dom = HkStringUtil.getDom(url);
        if (dom != null && !dom.equals(lastDom) && !HkStringUtil.isEmpty(getExtraUa())) {
            UaHook hook = uaHook;
            String adjustUa = hook == null ? null : hook.getAdjustUa(url);
            if (!TextUtils.isEmpty(adjustUa)) {
                if (!adjustUa.equals(getUserAgentString())) {
                    Log.d(TAG, "onPageStarted: getAdjustUa");
                    setUserAgentString(adjustUa);
                }
            } else {
                String useUa = hook == null ? "" : hook.getUseUa();
                if (!TextUtils.isEmpty(useUa)) {
                    if (!useUa.equals(getUserAgentString())) {
                        Log.d(TAG, "onPageStarted: getUseUa");
                        setUserAgentString(useUa);
                    }
                } else if (HkStringUtil.isNotEmpty(systemUA) && !systemUA.equals(getUserAgentString())) {
                    Log.d(TAG, "onPageStarted: systemUA");
                    setUserAgentString(systemUA);
                }
            }
        }
        lastDom = dom;
    }

    /** 照 IArticleWebHolder.dealLoadUrl：非 http 链接在 WebView 内的分发。 */
    protected void dealLoadUrl(String url) {
        if (url.contains("@lazyRule=")) {
            if (lazyRuleConsumer != null) lazyRuleConsumer.accept(url);
            return;
        }
        if (url.startsWith("hiker://")) {
            String route = url.replace("hiker://", "");
            Activity act = reference == null ? null : reference.get();
            if (route.startsWith("home") || route.startsWith("search")) {
                String decoded = url;
                try {
                    decoded = URLDecoder.decode(url, "UTF-8");
                } catch (UnsupportedEncodingException ignored) {
                }
                HkRouteBlocker.isRoute(act, decoded);
            } else {
                HkRouteBlocker.isRoute(act, url);
            }
            return;
        }
        Activity act = reference == null ? null : reference.get();
        DealLoadHooks hooks = dealLoadHooks;
        if (url.equals("folder://")) {
            if (hooks != null && act != null) hooks.openFolder(act);
            return;
        }
        if (url.equals("history://")) {
            if (hooks != null && act != null) hooks.openHistory(act);
            return;
        }
        if (url.equals("download://")) {
            if (hooks != null && act != null) hooks.openDownloads(act);
            return;
        }
        if (url.startsWith("web://")) {
            Context ctx = act != null ? act : App.get();
            HkFuck.GoWebHook hook = HkFuck.goWebHook;
            if (hook != null) hook.goWeb(ctx, HkStringUtil.replaceOnce(url, "web://", ""));
            return;
        }
        if (url.startsWith("func://background")) {
            if (hooks != null && act != null) hooks.background(act);
        }
    }

    // —— ArticleWebkitHolder 方法 ——

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public ProgressListener getProgressListener() {
        return progressListener;
    }

    public void setProgressListener(ProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    public String getUrl() {
        return url;
    }

    /**
     * 照 ArticleWebkitHolder.setUrl。原版在赋值前有一段自有服务处理：当前 Activity 资源串
     * home_domain(home.haikuoshijie.cn) 与 url 域名不等时……实为相等时把域名替换为 home_ip(159.75.120.248)，
     * 属 8.83 官网域名直连 IP 的处理，星辰无此资源/场景，此处只保留赋值本体。
     */
    public void setUrl(String url) {
        this.url = url;
    }

    public List<String> getUrls() {
        return urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }

    public WebView getWebView() {
        return webView;
    }

    public void setWebView(WebView webView) {
        this.webView = webView;
    }

    public void initWebView(Activity activity) {
        if (reference != null) reference.clear();
        reference = new WeakReference<>(activity);
        if (Build.VERSION.SDK_INT >= 28) {
            String processName = android.app.Application.getProcessName();
            if (!activity.getPackageName().equals(processName)) {
                WebView.setDataDirectorySuffix(processName);
            }
        }
        initWebSettings(webView.getSettings());
        systemUA = webView.getSettings().getUserAgentString();
        webView.setWebChromeClient(new ChromeClient());
        webView.setWebViewClient(new Client());
        jsBridgeHolder = new FyBridge(reference, new Wrapper());
        if (bridgeOpenHandler != null) jsBridgeHolder.setOpenHandler(bridgeOpenHandler);
    }

    public void onDestroy() {
        HkWebViewHelper.removeServiceWorkerClient(this);
        if (getWebView() == null) return;
        getWebView().onPause();
        getWebView().destroy();
    }

    private void initWebSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(-1);
        settings.setMixedContentMode(0);
        HkWebViewHelper.addServiceWorkerClient(this);
    }

    /** 照 ArticleWebkitHolder.loadAllJs：JsPluginHelper.loadMyJs（接缝） + AdBlockModel.getBlockJs（接缝）注入。 */
    private void loadAllJs(WebView view, String url, boolean onPageEnd) {
        Context context = reference == null ? null : reference.get();
        Consumer<String> consumer = js -> view.evaluateJavascript(js, null);
        Supplier<String> provider = () -> "";
        WebPluginHooks hooks = pluginHooks;
        if (hooks != null) hooks.loadMyJs(context, consumer, url, provider, onPageEnd);
        String blockJs = hooks == null ? null : hooks.getBlockJs(url);
        if (!TextUtils.isEmpty(blockJs)) {
            view.evaluateJavascript(blockJs, null);
        }
    }

    /** 照 ArticleWebkitHolder.shouldInterceptRequest（同时是 ServiceWorker 拦截入口）。 */
    @Override
    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
        try {
            Activity act = reference == null ? null : reference.get();
            if (webView == null || act == null || act.isFinishing()) {
                return HkWebViewHelper.RESPONSE_IGNORE;
            }
            String reqUrl = request.getUrl().toString();
            requestHeaderMap.put(reqUrl, request.getRequestHeaders());
            urls.add(reqUrl);
            if (getX5Extra() != null && com.fongmi.android.tv.api.hk.HkParserKit.isNotEmpty(getX5Extra().getBlockRules())) {
                if (HkAdUrlBlocker.shouldBlock(getX5Extra().getBlockRules(), lastDom, reqUrl)) {
                    return new WebResourceResponse(null, null, (java.io.InputStream) null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "hk error", e);
        }
        BrowserProxyHook hook = browserProxyHook;
        if (hook != null) return hook.proxy(myUrl, request, requestHeaderMap);
        // 网页代理规则的默认消费面（8.83 BrowserProxy.proxy 等价）：规则 JS 经 addWebProxyRule
        // 注册的规则由 api/hk/browser/HkBrowserProxy 维护，hook 未被外部接管时回落到此；
        // 命中规则（match 全串正则、静态资源只认 force）由宿主抓取改写，未命中回 null 走原生加载。
        return com.fongmi.android.tv.api.hk.browser.HkBrowserProxy
                .proxyRequest(myUrl, request, requestHeaderMap);
    }

    // —— $2 WebViewClient ——

    private class Client extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            myUrl = url;
            if (progressListener != null) progressListener.onPageStarted(url);
            requestHeaderMap.clear();
            refererPolicy = null;
            urls.clear();
            hasLoadJsOnPageEnd.set(false);
            checkUa(url);
            loadAllJs(view, view.getUrl(), false);
            if (x5Extra != null && HkStringUtil.isNotEmpty(x5Extra.getJs()) && x5Extra.isJsLoadingInject()) {
                view.evaluateJavascript(x5Extra.getJs(), null);
            }
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (progressListener != null) progressListener.onPageFinished(url);
            if (!hasLoadJsOnPageEnd.get()) {
                hasLoadJsOnPageEnd.set(true);
                loadAllJs(view, url, true);
            }
            if (x5Extra != null && HkStringUtil.isNotEmpty(x5Extra.getJs())) {
                view.evaluateJavascript(x5Extra.getJs(), null);
            }
            if (finishPageConsumer != null) finishPageConsumer.accept(url);
            super.onPageFinished(view, url);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.proceed();
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            String username = null;
            String password = null;
            if (handler.useHttpAuthUsernamePassword() && view != null) {
                String[] creds = view.getHttpAuthUsernamePassword(host, realm);
                if (creds != null && creds.length == 2) {
                    username = creds[0];
                    password = creds[1];
                }
            }
            if (username != null && password != null) {
                handler.proceed(username, password);
                return;
            }
            Activity act = reference == null ? null : reference.get();
            if (view != null && act != null && !act.isFinishing()) {
                showHttpAuthDialog(view, handler, host, realm, act);
            } else {
                handler.cancel();
            }
        }

        /** 原版 InputPopup("网页登录", 用户名/密码) 的原生等价：确定 → 存凭据并 proceed，取消 → handler.cancel()。 */
        private void showHttpAuthDialog(WebView view, HttpAuthHandler handler, String host, String realm, Activity act) {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, 0);
            EditText userEdit = new EditText(act);
            userEdit.setHint("用户名");
            userEdit.setInputType(InputType.TYPE_CLASS_TEXT);
            EditText passEdit = new EditText(act);
            passEdit.setHint("密码");
            passEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            box.addView(userEdit);
            box.addView(passEdit);
            new HkDialogs(act)
                    .setTitle("网页登录")
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton("确定", (dialog, which) -> {
                        String user = userEdit.getText().toString();
                        String pass = passEdit.getText().toString();
                        view.setHttpAuthUsernamePassword(host, realm, user, pass);
                        handler.proceed(user, pass);
                    })
                    .setNegativeButton("取消", (dialog, which) -> handler.cancel())
                    .show();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse response = HkWebkitHolder.this.shouldInterceptRequest(request);
            if (response != null) return response;
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            // 8.83 ArticleWebViewHolder.shouldOverrideUrlLoading：x5Extra.urlInterceptor 优先
            String x5Code = x5Extra != null ? x5Extra.getUrlInterceptor() : null;
            if (x5Code != null && !x5Code.isEmpty()) {
                String result = "";
                try {
                    if (urlInterceptorEvaluator != null) result = urlInterceptorEvaluator.eval(x5Code, url);
                } catch (Throwable ignored) {
                }
                if (result != null && !result.isEmpty() && !"null".equals(result)
                        && !"undefined".equals(result) && !"false".equals(result)) {
                    if (!"true".equals(result)) {
                        // 有代码执行
                        view.evaluateJavascript(result, null);
                    }
                    return true;
                }
            }
            if (url.startsWith("http")) {
                if (request.isRedirect() && x5Extra != null && !x5Extra.isRedirect()) {
                    return true;
                }
                if (Build.VERSION.SDK_INT < 26) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }
            dealLoadUrl(url);
            return true;
        }
    }

    // —— $1 WebChromeClient ——

    private class ChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (progressListener != null) progressListener.onProgressChanged(view.getUrl(), newProgress);
            super.onProgressChanged(view, newProgress);
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            Activity act = reference == null ? null : reference.get();
            if (act == null || act.isFinishing() || message == null
                    || message.contains("彩票") || message.contains("野战")) {
                result.cancel();
                return true;
            }
            if (message.contains("当前网页已弹出prompt弹窗") || message.contains("是否忽略后续该弹窗")) {
                result.confirm();
                return true;
            }
            showJsConfirmDialog(act, message, result);
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            Activity act = reference == null ? null : reference.get();
            if (act == null || act.isFinishing() || message == null) {
                result.cancel();
                return true;
            }
            if (message.contains("当前网页已弹出prompt弹窗") || message.contains("是否忽略后续该弹窗")) {
                result.confirm();
                return true;
            }
            showJsConfirmDialog(act, message, result);
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            Activity act = reference == null ? null : reference.get();
            if (act == null || act.isFinishing() || message == null) {
                result.cancel();
                return true;
            }
            EditText input = new EditText(act);
            if (defaultValue != null) {
                input.setText(defaultValue);
                input.setSelection(input.getText().length());
            }
            int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
            input.setPadding(pad, pad / 2, pad, 0);
            new HkDialogs(act)
                    .setTitle("来自网页的输入请求")
                    .setMessage(message)
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("确定", (dialog, which) -> result.confirm(input.getText().toString()))
                    .setNegativeButton("取消", (dialog, which) -> result.cancel())
                    .show();
            return true;
        }

        /** 原版 XPopup asConfirm("网页提示", message)（16dp 圆角、不可返回/点外关闭）的原生等价。 */
        private void showJsConfirmDialog(Activity act, String message, JsResult result) {
            new HkDialogs(act)
                    .setTitle("网页提示")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("确定", (dialog, which) -> result.confirm())
                    .setNegativeButton("取消", (dialog, which) -> result.cancel())
                    .show();
        }
    }

    // —— $3 WebViewWrapper ——

    /** WebView 方法只能在主线程调：已在主线程直接执行，否则投递主线队列；webView 未就绪时静默放弃。 */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN_HANDLER.post(r);
    }

    private class Wrapper implements HkWebViewWrapper {

        @Override
        public void addJavascriptInterface(Object object, String name) {
            WebView wv = webView;
            if (wv == null) return;
            runOnMain(() -> wv.addJavascriptInterface(object, name));
        }

        @Override
        public void evaluateJavascript(String js) {
            WebView wv = webView;
            if (wv == null) return;
            runOnMain(() -> wv.evaluateJavascript(js, null));
        }

        @Override
        public String getCookie(String url) {
            try {
                return CookieManager.getInstance().getCookie(url);
            } catch (Exception e) {
                Log.e(TAG, "hk error", e);
                return null;
            }
        }

        @Override
        public String getMyUrl() {
            return getStrOnUiThread(holder -> holder.accept(HkWebkitHolder.this.myUrl));
        }

        @Override
        public String getRefererPolicy() {
            return refererPolicy;
        }

        @Override
        public Map<String, Map<String, String>> getRequestHeaderMap() {
            return requestHeaderMap;
        }

        @Override
        public String getSystemUa() {
            return systemUA;
        }

        @Override
        public String getTitle() {
            WebView wv = webView;
            if (wv == null) return null;
            return getStrOnUiThread(holder -> holder.accept(wv.getTitle()));
        }

        @Override
        public String getUrl() {
            WebView wv = webView;
            if (wv == null) return null;
            return getStrOnUiThread(holder -> holder.accept(wv.getUrl()));
        }

        @Override
        public List<String> getUrls() {
            return urls;
        }

        @Override
        public String getUserAgentString() {
            return getStrOnUiThread(holder ->
                    holder.accept(webView == null ? null : webView.getSettings().getUserAgentString()));
        }

        @Override
        public boolean isOnPause() {
            Activity act = reference == null ? null : reference.get();
            return act != null && act.isFinishing();
        }

        @Override
        public void loadUrl(String url) {
            WebView wv = webView;
            if (wv == null) return;
            runOnMain(() -> wv.loadUrl(url));
        }

        @Override
        public void openThirdApp(String url) {
        }

        @Override
        public void reload() {
            WebView wv = webView;
            if (wv == null) return;
            runOnMain(() -> wv.reload());
        }

        @Override
        public void setAppBarColor(String color1, String color2) {
        }

        @Override
        public void setUserAgentString(String ua) {
            WebView wv = webView;
            if (wv == null) return;
            runOnMain(() -> wv.getSettings().setUserAgentString(ua));
        }

        @Override
        public void storeFaviconUrl(String url) {
        }

        @Override
        public void storeRefererPolicy(String policy) {
            refererPolicy = policy;
        }

        @Override
        public void updateLastDom(String dom) {
            lastDom = dom;
        }
    }

    /** ThreadTool.getStrOnUIThread 的等价：主线程直取，子线程投递主线程并阻塞等结果。 */
    private static String getStrOnUiThread(Consumer<Consumer<String>> task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            String[] box = new String[1];
            task.accept(v -> box[0] = v);
            return box[0];
        }
        String[] box = new String[1];
        boolean[] done = new boolean[1];
        Object lock = new Object();
        MAIN_HANDLER.post(() -> {
            try {
                task.accept(v -> box[0] = v);
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            try {
                // 带完成旗等待：主线程先跑完时立刻返回，不再白等满 2 秒；只有主线程真卡住才等到超时
                if (!done[0]) lock.wait(2000);
            } catch (InterruptedException ignored) {
            }
        }
        return box[0];
    }

    // —— fy_bridge_app 桥（原版 JsBridgeHolder + VideoInterface 的嗅探必需子集） ——

    /**
     * 原版 JsBridgeHolder 在构造时把 VideoInterface 以 "fy_bridge_app" 名注册到页面；
     * 生成脚本只依赖 finishParse(url, type, ticket)——经 VideoInterface$BridgeListener
     * （JsBridgeHolder$1）投递 DestroyEvent 到 EventBus。此处照该链转写。
     */
    public static class FyBridge {

        private final WeakReference<Activity> reference;
        private final HkWebViewWrapper wrapper;
        private BridgeOpenHandler openHandler;

        public FyBridge(WeakReference<Activity> reference, HkWebViewWrapper wrapper) {
            this.reference = reference;
            this.wrapper = wrapper;
            wrapper.addJavascriptInterface(new BridgeObject(), "fy_bridge_app");
        }

        public void setOpenHandler(BridgeOpenHandler h) {
            this.openHandler = h;
        }

        public HkWebViewWrapper getWrapper() {
            return wrapper;
        }

        public WeakReference<Activity> getReference() {
            return reference;
        }

        public class BridgeObject {

            @JavascriptInterface
            public void finishParse(String url, String type, String ticket) {
                EventBus.getDefault().post(new HkDestroyEvent(url, type, ticket));
            }

            /** 原版 VideoInterface.log 同款。 */
            @JavascriptInterface
            public void log(String msg) {
                android.util.Log.d("HkJs", msg == null ? "" : msg);
            }

            /** 原版 VideoInterface.openThirdApp 同款：唤起第三方应用。 */
            @JavascriptInterface
            public void openThirdApp(String url) {
                try {
                    Activity act = reference.get();
                    if (act == null || TextUtils.isEmpty(url)) return;
                    android.content.Intent intent = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(intent);
                } catch (Throwable ignored) {
                }
            }

            /** 原版 VideoInterface.open 同款：{title,url} 打开规则页面。 */
            @JavascriptInterface
            public void open(String json) {
                try {
                    if (TextUtils.isEmpty(json) || openHandler == null) return;
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    String url = o.optString("url");
                    if (TextUtils.isEmpty(url)) return;
                    String title = o.optString("title");
                    final BridgeOpenHandler h = openHandler;
                    Activity act = reference.get();
                    if (act == null) return;
                    act.runOnUiThread(() -> h.openBridgePage(title, url));
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
