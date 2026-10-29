package com.fongmi.android.tv.login;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgDouyinAuth;
import com.fongmi.android.tv.hongguo.api.HgPassportClient;

/**
 * 红果登录页（官方形态整页，壳子统一登录托管的红果入口）。
 *
 * <p>构成照官方登录页实拍（2026-10-28 他亲发 113559_12_phn7.jpg 并亲令
 * 「换呗，既然都通了」）：全屏浅色柔光底、左上 ×、两行大标题「登录／
 * 发现更多精彩短剧」、橙色大钮「抖音一键登录」（主入口）、协议勾选行、
 * 底部居中手机图标圆钮（点开切换手机号登录）、页底授权说明两行
 * （获得你的公开信息／获得你的抖音手机号）。三面页内切换：主页／
 * 手机号页／抖音授权页；系统返回在子面先回主页、主页才关页。</p>
 *
 * <p>抖音路走 web 授权（探针定版，证据账
 * hongguo/douyin-login-probe-20261028.md）：点大钮→受控 WebView 只装
 * 抖音授权页本体（{@link HgDouyinAuth#buildAuthorizeUrl}，身份参数全套
 * 出官方包、本机实拉发页与探针逐字节同长）→用户在抖音侧登录并同意→
 * 回跳 api.snssdk.com/oauth/authorize/callback/ 在加载前拦截取 code
 * （拦截契约照官方 SDK BaseWebAuthorizeActivity；回调端点裸拉 504、
 * 本就不供直开）→原生 POST /passport/auth/login/ 兑换会话
 * （{@link HgPassportClient#douyinAuthLogin}，探针 P4 证兑换口只判码
 * 本身）。授权页之外的任何登录表单都不 WebView 化：手机号路是原生
 * passport 链（发码/码登，HgPassportClient 整套复用）。授权页里若跳
 * 非 http 协议（拉抖音 App 的深链），尝试交给系统、无人接就吞掉留在
 * 页内——此跳行为待首登观察。can_aweme_quick_login 快捷资格口不进
 * 主流程（无在机抖音会话时恒 false，web 路不依赖它）。</p>
 *
 * <p>两路成功都经 LoginManager 落同一托管口（HongguoLoginProvider），
 * 并照 SMS 路同样跑登录后第一验（HgLoginSessionCheck，只记形状）。
 * 未验（如实）：抖音真同意→真 code→兑换成功回包、手机号真号首登，
 * 都等他本人走完一次定论；失败人话原因显示在页内，不假成功。</p>
 */
public class HongguoLoginPage extends DialogFragment {

    private static final int PANE_HOME = 0;
    private static final int PANE_PHONE = 1;
    private static final int PANE_WEB = 2;

    private final HgPassportClient client = new HgPassportClient();

    private View rootView;
    private View paneHome;
    private View panePhone;
    private View paneWeb;
    private int pane = PANE_HOME;

    // 主页件
    private View btnDouyin;
    private TextView tvDouyinLabel;
    private TextView tvHomeStatus;

    // 手机号页件（SMS 链整套自旧弹窗迁入，行为不变）
    private EditText etPhone;
    private EditText etCode;
    private TextView btnSend;
    private TextView btnLogin;
    private TextView tvResend;
    private TextView tvPhoneStatus;
    private View step1;
    private View step2;
    private TextView tvSentTo;

    // 协议勾选（主页/手机号页两行同读同写一份状态）
    private final java.util.List<TextView> agreeDots = new java.util.ArrayList<>();
    private boolean agreed;

    // 抖音授权页件
    private WebView webView;
    private FrameLayout webHolder;
    private ProgressBar webProgress;
    private String authState;
    private boolean exchanging;

    private LoginManager.LoginCallback callback;
    private boolean callbackFired;
    private volatile boolean stopped;
    private int countdown;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            countdown--;
            if (countdown <= 0) {
                tvResend.setText("重新发送");
                tvResend.setTextColor(Color.parseColor("#FF8C4A"));
                tvResend.setClickable(true);
            } else {
                tvResend.setText("重新发送（" + countdown + "s）");
                App.post(this, 1000);
            }
        }
    };

    public static HongguoLoginPage create() {
        return new HongguoLoginPage();
    }

    public HongguoLoginPage callback(LoginManager.LoginCallback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
            if (f instanceof HongguoLoginPage) return;
        }
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = new Dialog(requireContext());
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.HkDialogFade);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(createContentView());
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP
                    && pane != PANE_HOME) {
                showPane(PANE_HOME);
                return true;
            }
            return false;
        });
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        stopped = false;
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.CENTER;
        window.setAttributes(params);
        applyImmersiveStatusBar(window);
    }

    /**
     * 状态栏沉浸（照全应用既有口径）：裸 Dialog 的窗口走默认 dialog 主题，
     * 没继承 App 主题（Theme.Base）的透明状态栏，状态栏落主题默认灰、且
     * 内容被系统窗框收在状态栏下方——柔光渐变铺不上去、顶上留一条灰带。
     * 这里给本页窗口单独落：edge-to-edge＋状态栏透明（渐变直接铺到状态栏
     * 底下连续一色）、浅底配深色状态栏图标；底部按导航区真实高度留距
     * （手势让位全应用口径），根视图顶距不动（页内列自带 54dp 顶内距已
     * 让开状态栏高度）。
     */
    private void applyImmersiveStatusBar(Window window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        WindowCompat.setDecorFitsSystemWindows(window, false);
        new WindowInsetsControllerCompat(window, window.getDecorView())
                .setAppearanceLightStatusBars(true);
        View content = rootView;
        if (content == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            if (v.getPaddingBottom() != nav) {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), nav);
            }
            return insets;
        });
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        stopped = true;
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        fireCancel();
        super.onDismiss(dialog);
    }

    private void fireCancel() {
        if (callbackFired) return;
        callbackFired = true;
        if (callback != null) callback.onCancel();
    }

    private void fireSuccess(String cookie) {
        if (callbackFired) return;
        callbackFired = true;
        if (callback != null) callback.onSuccess(HongguoLoginProvider.ID, cookie);
    }

    private void showPane(int target) {
        pane = target;
        paneHome.setVisibility(target == PANE_HOME ? View.VISIBLE : View.GONE);
        panePhone.setVisibility(target == PANE_PHONE ? View.VISIBLE : View.GONE);
        paneWeb.setVisibility(target == PANE_WEB ? View.VISIBLE : View.GONE);
        if (target != PANE_WEB && webView != null) webView.stopLoading();
        if (target == PANE_HOME) setDouyinBusy(false);
    }

    // ---------------- 页骨架 ----------------

    private View createContentView() {
        FrameLayout root = new FrameLayout(requireContext());
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#8FE8D8"), Color.parseColor("#FFE7C4"),
                        Color.parseColor("#FFFBF3")});
        root.setBackground(bg);
        paneHome = buildHomePane();
        panePhone = buildPhonePane();
        paneWeb = buildWebPane();
        root.addView(paneHome, matchParent());
        root.addView(panePhone, matchParent());
        root.addView(paneWeb, matchParent());
        panePhone.setVisibility(View.GONE);
        paneWeb.setVisibility(View.GONE);
        rootView = root;
        return root;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout pageColumn() {
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(54), dp(28), dp(24));
        return content;
    }

    private TextView closeButton() {
        TextView close = new TextView(requireContext());
        close.setText("×");
        close.setTextColor(Color.parseColor("#111111"));
        close.setTextSize(30);
        close.setOnClickListener(v -> dismiss());
        return close;
    }

    private TextView bigTitle(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#111111"));
        tv.setTextSize(33);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private void addTitles(LinearLayout content) {
        content.addView(closeButton(), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title1 = bigTitle("登录");
        LinearLayout.LayoutParams t1 = wrapWrap();
        t1.setMargins(dp(10), dp(52), 0, 0);
        content.addView(title1, t1);
        TextView title2 = bigTitle("发现更多精彩短剧");
        LinearLayout.LayoutParams t2 = wrapWrap();
        t2.setMargins(dp(10), dp(6), 0, 0);
        content.addView(title2, t2);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    // ---------------- 主页（官方构成） ----------------

    private View buildHomePane() {
        LinearLayout content = pageColumn();
        addTitles(content);

        // 橙色大钮：抖音音符标＋「一键登录」（主入口）
        LinearLayout douyin = new LinearLayout(requireContext());
        douyin.setOrientation(LinearLayout.HORIZONTAL);
        douyin.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#FF7A29"));
        gd.setCornerRadius(dp(14));
        douyin.setBackground(gd);
        ImageView note = new ImageView(requireContext());
        note.setImageResource(R.drawable.ic_hg_douyin);
        douyin.addView(note, new LinearLayout.LayoutParams(dp(24), dp(24)));
        tvDouyinLabel = new TextView(requireContext());
        tvDouyinLabel.setText("一键登录");
        tvDouyinLabel.setTextColor(Color.WHITE);
        tvDouyinLabel.setTextSize(17);
        tvDouyinLabel.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams dl = wrapWrap();
        dl.setMargins(dp(8), 0, 0, 0);
        douyin.addView(tvDouyinLabel, dl);
        LinearLayout.LayoutParams db = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        db.setMargins(0, dp(64), 0, 0);
        content.addView(douyin, db);
        btnDouyin = douyin;
        douyin.setOnClickListener(v -> onDouyinClicked());

        content.addView(buildAgreementRow(), matchWrap(dp(22)));

        tvHomeStatus = statusText();
        LinearLayout.LayoutParams hs = matchWrap(dp(14));
        hs.setMargins(dp(10), 0, 0, 0);
        content.addView(tvHomeStatus, hs);

        // 弹性空白：把手机圆钮与授权说明压到页底区（照实拍层级）
        View spacer = new View(requireContext());
        content.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部手机图标圆钮：点开切换手机号登录
        FrameLayout phoneBtn = new FrameLayout(requireContext());
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(1), Color.parseColor("#E7E1D6"));
        phoneBtn.setBackground(circle);
        ImageView phoneIcon = new ImageView(requireContext());
        phoneIcon.setImageResource(R.drawable.ic_hg_phone);
        int pad = dp(17);
        phoneIcon.setPadding(pad, pad, pad, pad);
        phoneBtn.addView(phoneIcon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(dp(58), dp(58));
        pb.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(phoneBtn, pb);
        phoneBtn.setOnClickListener(v -> showPane(PANE_PHONE));

        // 页底授权说明（照实拍两行权限）
        TextView notesTitle = new TextView(requireContext());
        notesTitle.setText("授权后将获得以下权限");
        notesTitle.setTextColor(Color.parseColor("#666666"));
        notesTitle.setTextSize(13);
        LinearLayout.LayoutParams nt = matchWrap(dp(46));
        content.addView(notesTitle, nt);
        content.addView(permissionRow("获得你的公开信息（头像、昵称）"), matchWrap(dp(10)));
        content.addView(permissionRow("获得你的抖音手机号"), matchWrap(dp(6)));
        return content;
    }

    private LinearLayout permissionRow(String text) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView check = new TextView(requireContext());
        check.setText("✓");
        check.setTextColor(Color.parseColor("#FF7A29"));
        check.setTextSize(13);
        check.setTypeface(null, Typeface.BOLD);
        row.addView(check);
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#666666"));
        tv.setTextSize(13);
        LinearLayout.LayoutParams p = wrapWrap();
        p.setMargins(dp(8), 0, 0, 0);
        row.addView(tv, p);
        return row;
    }

    private void onDouyinClicked() {
        if (exchanging) return;
        if (!agreed) {
            Toast.makeText(requireContext(), "请先阅读并同意用户协议和隐私政策", Toast.LENGTH_SHORT).show();
            return;
        }
        tvHomeStatus.setText("");
        authState = HgDouyinAuth.newState();
        showPane(PANE_WEB);
        ensureWebView();
        webView.loadUrl(HgDouyinAuth.buildAuthorizeUrl(authState));
    }

    private void setDouyinBusy(boolean busy) {
        exchanging = busy;
        if (tvDouyinLabel != null) tvDouyinLabel.setText(busy ? "登录中…" : "一键登录");
        if (btnDouyin != null) btnDouyin.setAlpha(busy ? 0.6f : 1f);
    }

    /** 抖音回跳拿到 code：回主页走原生兑换，成功落托管（与 SMS 路同口）。 */
    private void onDouyinCode(String code) {
        showPane(PANE_HOME);
        setDouyinBusy(true);
        client.douyinAuthLogin(code, new HgCallback<String>() {
            @Override
            public void onSuccess(String cookie) {
                App.post(() -> {
                    if (stopped) return;
                    fireSuccess(cookie);
                    dismiss();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                App.post(() -> {
                    if (stopped) return;
                    setDouyinBusy(false);
                    tvHomeStatus.setText(message == null || message.isEmpty()
                            ? "登录没成功，请稍后再试" : message);
                });
            }
        });
    }

    private void onDouyinDenied() {
        showPane(PANE_HOME);
        tvHomeStatus.setText("授权没有完成，登录未成功");
    }

    // ---------------- 抖音授权页（受控 WebView，只装授权页本体） ----------------

    private View buildWebPane() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(requireContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView cancel = new TextView(requireContext());
        cancel.setText("取消");
        cancel.setTextColor(Color.parseColor("#666666"));
        cancel.setTextSize(15);
        cancel.setOnClickListener(v -> showPane(PANE_HOME));
        bar.addView(cancel);
        TextView title = new TextView(requireContext());
        title.setText("抖音授权");
        title.setTextColor(Color.parseColor("#111111"));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tp = wrapWrap();
        tp.setMargins(dp(14), 0, 0, 0);
        bar.addView(title, tp);
        box.addView(bar);

        webProgress = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        webProgress.setMax(100);
        webProgress.setVisibility(View.GONE);
        box.addView(webProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        webHolder = new FrameLayout(requireContext());
        box.addView(webHolder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    private void ensureWebView() {
        if (webView != null) return;
        WebView wv = new WebView(requireContext());
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (webProgress == null) return;
                webProgress.setProgress(newProgress);
                webProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleAuthNavigation(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleAuthNavigation(url);
            }
        });
        webHolder.addView(wv, matchParent());
        webView = wv;
    }

    /**
     * 授权页导航分流：回跳地址（redirect_uri 前缀）在加载前拦截取 code
     * （照官方 SDK 契约）；非 http 协议（拉抖音 App 的深链）尝试交系统、
     * 无人接就吞掉；其余放行页内导航。
     */
    private boolean handleAuthNavigation(String url) {
        if (url == null) return false;
        HgDouyinAuth.Callback cb = HgDouyinAuth.parseCallback(url);
        if (cb != null) {
            if (cb.hasCode()) onDouyinCode(cb.code);
            else onDouyinDenied();
            return true;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                // 无 App 接此协议：留在授权页内，不让 WebView 报裸错误
            }
            return true;
        }
        return false;
    }

    // ---------------- 手机号页（SMS 原生链，整套复用 HgPassportClient） ----------------

    private View buildPhonePane() {
        LinearLayout content = pageColumn();
        addTitles(content);
        content.addView(buildStep1(), matchWrap(dp(56)));
        content.addView(buildStep2(), matchWrap(dp(56)));
        content.addView(buildAgreementRow(), matchWrap(dp(22)));

        tvPhoneStatus = statusText();
        LinearLayout.LayoutParams st = matchWrap(dp(14));
        st.setMargins(dp(10), 0, 0, 0);
        content.addView(tvPhoneStatus, st);

        View spacer = new View(requireContext());
        content.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView back = new TextView(requireContext());
        back.setText("抖音一键登录");
        back.setTextColor(Color.parseColor("#FF7A29"));
        back.setTextSize(14);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> showPane(PANE_HOME));
        content.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return content;
    }

    private View buildStep1() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        step1 = box;

        LinearLayout phoneRow = new LinearLayout(requireContext());
        phoneRow.setOrientation(LinearLayout.HORIZONTAL);
        phoneRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView prefix = new TextView(requireContext());
        prefix.setText("+86 ⌄");
        prefix.setTextColor(Color.parseColor("#111111"));
        prefix.setTextSize(17);
        phoneRow.addView(prefix, wrapWrap());
        etPhone = new EditText(requireContext());
        etPhone.setHint("请输入您的手机号");
        etPhone.setTextSize(17);
        etPhone.setTextColor(Color.parseColor("#111111"));
        etPhone.setHintTextColor(Color.parseColor("#A8A8A8"));
        etPhone.setBackground(null);
        etPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        etPhone.setSingleLine(true);
        etPhone.setPadding(dp(14), 0, 0, 0);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, dp(52), 1f);
        phoneRow.addView(etPhone, ep);
        box.addView(phoneRow);
        box.addView(divider());

        btnSend = peachButton("获取验证码");
        LinearLayout.LayoutParams bs = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        bs.setMargins(0, dp(26), 0, 0);
        box.addView(btnSend, bs);
        refreshSendButton();
        etPhone.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                refreshSendButton();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        btnSend.setOnClickListener(v -> onSendClicked());
        return box;
    }

    private View buildStep2() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        step2 = box;

        tvSentTo = new TextView(requireContext());
        tvSentTo.setTextColor(Color.parseColor("#666666"));
        tvSentTo.setTextSize(14);
        box.addView(tvSentTo);

        etCode = new EditText(requireContext());
        etCode.setHint("请输入验证码");
        etCode.setTextSize(17);
        etCode.setTextColor(Color.parseColor("#111111"));
        etCode.setHintTextColor(Color.parseColor("#A8A8A8"));
        etCode.setBackground(null);
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCode.setSingleLine(true);
        LinearLayout.LayoutParams ec = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        ec.setMargins(0, dp(10), 0, 0);
        box.addView(etCode, ec);
        box.addView(divider());

        btnLogin = peachButton("登录");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        bl.setMargins(0, dp(26), 0, 0);
        box.addView(btnLogin, bl);
        btnLogin.setOnClickListener(v -> onLoginClicked());

        tvResend = new TextView(requireContext());
        tvResend.setTextColor(Color.parseColor("#999999"));
        tvResend.setTextSize(13);
        tvResend.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tr = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tr.setMargins(0, dp(18), 0, 0);
        box.addView(tvResend, tr);
        tvResend.setOnClickListener(v -> {
            if (countdown > 0) return;
            sendCode(true);
        });
        return box;
    }

    private void onSendClicked() {
        if (!agreed) {
            Toast.makeText(requireContext(), "请先阅读并同意用户协议和隐私政策", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!phone().matches("^1\\d{10}$")) {
            Toast.makeText(requireContext(), "请输入正确的手机号", Toast.LENGTH_SHORT).show();
            return;
        }
        sendCode(false);
    }

    private void sendCode(boolean resend) {
        tvPhoneStatus.setText("");
        btnSend.setEnabled(false);
        btnSend.setText(resend ? "重新发送中…" : "发送中…");
        client.sendSmsCode(phone(), new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                App.post(() -> {
                    if (stopped) return;
                    btnSend.setEnabled(true);
                    btnSend.setText("获取验证码");
                    step1.setVisibility(View.GONE);
                    step2.setVisibility(View.VISIBLE);
                    tvSentTo.setText("验证码已发送至 +86 " + maskPhone(phone()));
                    startCountdown();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                App.post(() -> {
                    if (stopped) return;
                    btnSend.setEnabled(true);
                    btnSend.setText("获取验证码");
                    tvPhoneStatus.setText(message == null || message.isEmpty() ? "验证码发送失败，请稍后再试" : message);
                });
            }
        });
    }

    private void onLoginClicked() {
        if (code().length() < 4) {
            Toast.makeText(requireContext(), "请输入验证码", Toast.LENGTH_SHORT).show();
            return;
        }
        tvPhoneStatus.setText("");
        btnLogin.setEnabled(false);
        btnLogin.setText("登录中…");
        client.smsLogin(phone(), code(), new HgCallback<String>() {
            @Override
            public void onSuccess(String cookie) {
                App.post(() -> {
                    if (stopped) return;
                    fireSuccess(cookie);
                    dismiss();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                App.post(() -> {
                    if (stopped) return;
                    btnLogin.setEnabled(true);
                    btnLogin.setText("登录");
                    tvPhoneStatus.setText(message == null || message.isEmpty() ? "登录没成功，请稍后再试" : message);
                });
            }
        });
    }

    private void startCountdown() {
        countdown = 60;
        tvResend.setText("重新发送（60s）");
        tvResend.setTextColor(Color.parseColor("#999999"));
        tvResend.setClickable(false);
        App.post(tick, 1000);
    }

    // ---------------- 共享小件 ----------------

    /** 协议勾选行：圆点＋「已阅读并同意红果免费短剧 用户协议 和 隐私政策」（照官方实拍文案，整行点按切换，主页/手机号页同状态）。 */
    private View buildAgreementRow() {
        LinearLayout agreeRow = new LinearLayout(requireContext());
        agreeRow.setOrientation(LinearLayout.HORIZONTAL);
        agreeRow.setGravity(Gravity.CENTER);
        TextView dot = new TextView(requireContext());
        dot.setText("○");
        dot.setTextColor(Color.parseColor("#666666"));
        dot.setTextSize(16);
        agreeRow.addView(dot);
        agreeDots.add(dot);
        TextView agreeText = new TextView(requireContext());
        agreeText.setText(agreementText());
        agreeText.setTextSize(13);
        agreeText.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams at = wrapWrap();
        at.setMargins(dp(6), 0, 0, 0);
        agreeRow.addView(agreeText, at);
        agreeRow.setOnClickListener(v -> {
            agreed = !agreed;
            for (TextView d : agreeDots) {
                d.setText(agreed ? "●" : "○");
                d.setTextColor(Color.parseColor(agreed ? "#FF7A29" : "#666666"));
            }
        });
        return agreeRow;
    }

    private SpannableString agreementText() {
        String text = "已阅读并同意红果免费短剧 用户协议 和 隐私政策";
        SpannableString ss = new SpannableString(text);
        int a = text.indexOf("用户协议");
        ss.setSpan(new UnderlineSpan(), a, a + 4, 0);
        int b = text.indexOf("隐私政策");
        ss.setSpan(new UnderlineSpan(), b, b + 4, 0);
        return ss;
    }

    private TextView statusText() {
        TextView tv = new TextView(requireContext());
        tv.setTextColor(Color.parseColor("#D93025"));
        tv.setTextSize(13);
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, topMargin, 0, 0);
        return p;
    }

    private View divider() {
        View v = new View(requireContext());
        v.setBackgroundColor(Color.parseColor("#E2DED6"));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private TextView peachButton(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(17);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#FFCF9E"), Color.parseColor("#FFB37E")});
        gd.setCornerRadius(dp(16));
        tv.setBackground(gd);
        return tv;
    }

    private void refreshSendButton() {
        boolean ok = phone().length() == 11;
        btnSend.setAlpha(ok ? 1f : 0.45f);
    }

    private String phone() {
        return etPhone == null || etPhone.getText() == null ? "" : etPhone.getText().toString().trim();
    }

    private String code() {
        return etCode == null || etCode.getText() == null ? "" : etCode.getText().toString().trim();
    }

    private String maskPhone(String p) {
        return p.length() == 11 ? p.substring(0, 3) + "****" + p.substring(7) : p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
