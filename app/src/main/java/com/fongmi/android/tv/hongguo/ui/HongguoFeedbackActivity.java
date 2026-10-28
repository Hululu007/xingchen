package com.fongmi.android.tv.hongguo.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoFeedbackBinding;
import com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider;
import com.fongmi.android.tv.ui.base.BaseActivity;

/**
 * 红果·反馈与帮助页（官方功能位 y93.i 点击逐字：x93.k.a.m →
 * appNavigator().openUrl(feedbackEntryUrl)，即应用内网页承载
 * WebUrlManager.getFeedbackEntryUrl() 的缺省内层页
 * https://reading.snssdk.com/reading_offline/drweb/page/feedback-entry.html
 * ——服务端配置可换址，本地无配置源，照缺省原样）。
 *
 * <p>已登录时把壳子托管的红果会话 Cookie 注入本域（与官方在自家网页
 * 容器里带账号会话同义），反馈提交能认账号；未登录不拦、照样打开
 * （帮助内容不依赖登录）。标题左对齐、无自带返回箭头：系统返回先在
 * 网页内后退，无可退再关页。</p>
 */
public class HongguoFeedbackActivity extends BaseActivity {

    /** 官方缺省反馈入口页（WebUrlManager.feedbackEntryUrl 的内层 url 逐字）。 */
    private static final String FEEDBACK_URL =
            "https://reading.snssdk.com/reading_offline/drweb/page/feedback-entry.html";

    private ActivityHongguoFeedbackBinding binding;

    public static void start(Context context) {
        context.startActivity(new Intent(context, HongguoFeedbackActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoFeedbackBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void initView(Bundle savedInstanceState) {
        WebView web = binding.hgFeedbackWeb;
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        injectSessionCookie();
        web.loadUrl(FEEDBACK_URL);
    }

    /** 已登录才注入：把托管 Cookie 逐项写进 WebView 的本域 Cookie 罐。 */
    private void injectSessionCookie() {
        if (!HgLoginAuthProvider.isLoggedIn()) return;
        String cookie = new HgLoginAuthProvider().cookie();
        if (cookie == null || cookie.isEmpty()) return;
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        for (String part : cookie.split(";")) {
            String pair = part.trim();
            if (pair.contains("=")) manager.setCookie(FEEDBACK_URL, pair);
        }
        manager.flush();
    }

    @Override
    protected void onDestroy() {
        if (binding != null) {
            binding.hgFeedbackWeb.stopLoading();
            binding.hgFeedbackWeb.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (binding != null && binding.hgFeedbackWeb.canGoBack()) {
            binding.hgFeedbackWeb.goBack();
            return;
        }
        super.onBackPressed();
    }
}
