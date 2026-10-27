package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.legado.LegadoCookies;
import com.fongmi.android.tv.legado.LegadoVerify;
import com.fongmi.android.tv.utils.ToastUtil;
import com.fongmi.android.tv.utils.WebViewUtil;

/**
 * 书源人机验证页：书源请求撞上验证时由 LegadoVerify 拉起（引擎按类名启动）。
 * 加载站方被挡的那个地址原样，他在真网页里手动过验证；
 * 完成信号只有一条定死的路：点「完成验证」→ 从 CookieManager 收该站 Cookie
 * 存进 LegadoCookies → 回报 LegadoVerify.RESULT_OK → 引擎带证自动重试原请求。
 * 取消/返回退出/页面被销毁＝回报 RESULT_CANCELLED，引擎如实报错，不循环弹。
 * UA 与书源请求严格一致（LegadoHttpAndroid.USER_AGENT 经 extra 传入），
 * 否则 Cloudflare 的通行证不认（过盾同口径）。
 */
public class LegadoVerifyActivity extends Activity {

    private WebView webView;
    private String targetUrl = "";
    private String host = "";
    /** 引擎会话令牌：回报时带回，防迟报串进别人的会话。 */
    private long sessionId;
    /** 是否已回报过结果（防 onDestroy 与按钮双报）。 */
    private boolean reported;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReadingSkin.enableEdgeToEdge(this, true);
        targetUrl = getIntent().getStringExtra("verify_url");
        if (targetUrl == null) targetUrl = "";
        String ua = getIntent().getStringExtra("verify_ua");
        sessionId = getIntent().getLongExtra("verify_session", 0L);
        host = LegadoCookies.hostOf(targetUrl);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ReadingSkin.pageBackground(this));

        // 顶栏：标题左对齐、无自带返回箭头（星辰铁律），关页走系统返回/底部取消
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(16), dp(12), dp(12));
        ReadingSkin.applyTopInset(topBar, this, 16);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(8);
        titles.setLayoutParams(tlp);
        TextView title = new TextView(this);
        title.setText("人机验证");
        title.setTextSize(20);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(ReadingSkin.TEXT);
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText(host + " 需要验证，过完会自动继续刚才的操作");
        sub.setTextSize(12);
        sub.setTextColor(ReadingSkin.SUB);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        topBar.addView(titles);
        root.addView(topBar);

        webView = new WebView(this);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        webView.setLayoutParams(wlp);
        root.addView(webView);

        // 底栏：左边一句提示，右边取消＋完成验证
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        ReadingSkin.applyBottomInset(bottomBar, this, 10);
        TextView hint = new TextView(this);
        hint.setText("在页面里完成验证后，点「完成验证」");
        hint.setTextSize(12);
        hint.setTextColor(ReadingSkin.SUB);
        hint.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bottomBar.addView(hint);
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setTextColor(ReadingSkin.SUB);
        cancel.setPadding(dp(12), dp(8), dp(12), dp(8));
        cancel.setOnClickListener(v -> finishWith(LegadoVerify.RESULT_CANCELLED));
        bottomBar.addView(cancel);
        TextView done = new TextView(this);
        done.setText("完成验证");
        done.setTextSize(15);
        done.getPaint().setFakeBoldText(true);
        done.setTextColor(ReadingSkin.GOLD_BTN_TEXT);
        done.setBackground(ReadingSkin.goldCapsule(this));
        done.setPadding(dp(20), dp(8), dp(20), dp(8));
        done.setOnClickListener(v -> onDone());
        bottomBar.addView(done);
        root.addView(bottomBar);

        setContentView(root);
        setupWebView(ua);
        if (!targetUrl.isEmpty()) webView.loadUrl(targetUrl);
    }

    private void setupWebView(String ua) {
        WebViewUtil.configureBase(webView, "legado-verify");
        WebSettings s = webView.getSettings();
        if (ua != null && !ua.isEmpty()) s.setUserAgentString(ua);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }
        // 已有旧通行证先种进去：续验时不用从零过
        String saved = LegadoCookies.cookieHeader(host);
        if (!saved.isEmpty()) {
            for (String part : saved.split(";")) {
                String p = part.trim();
                if (!p.isEmpty()) cm.setCookie(targetUrl, p);
            }
            try {
                cm.flush();
            } catch (Throwable ignored) {
            }
        }
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
    }

    /** 完成信号：收 Cookie 存证成功才算过；没收到证不关页，让他继续过。 */
    private void onDone() {
        CookieManager cm = CookieManager.getInstance();
        try {
            cm.flush();
        } catch (Throwable ignored) {
        }
        String current = webView == null ? null : webView.getUrl();
        String cookie = "";
        try {
            cookie = cm.getCookie(current != null && !current.isEmpty() ? current : targetUrl);
        } catch (Throwable ignored) {
        }
        if (cookie == null || cookie.isEmpty()) {
            ToastUtil.show(this, "还没拿到验证结果，请先完成页面上的验证");
            return;
        }
        LegadoCookies.save(host, cookie);
        String currentHost = LegadoCookies.hostOf(current == null ? "" : current);
        if (!currentHost.isEmpty() && !currentHost.equals(host)) {
            LegadoCookies.save(currentHost, cookie);
        }
        ToastUtil.show(this, "验证完成，正在继续");
        finishWith(LegadoVerify.RESULT_OK);
    }

    private void finishWith(int result) {
        if (!reported) {
            reported = true;
            LegadoVerify.onFinished(sessionId, result);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        finishWith(LegadoVerify.RESULT_CANCELLED);
    }

    @Override
    protected void onDestroy() {
        // 页面没走完就被销毁＝放弃，回报取消，免得引擎线程干等
        if (!reported) {
            reported = true;
            LegadoVerify.onFinished(sessionId, LegadoVerify.RESULT_CANCELLED);
        }
        try {
            if (webView != null) {
                webView.stopLoading();
                webView.setWebViewClient(null);
                webView.setWebChromeClient(null);
                if (webView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) webView.getParent()).removeView(webView);
                }
                webView.destroy();
                webView = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return ReadingSkin.dp(this, v);
    }
}
