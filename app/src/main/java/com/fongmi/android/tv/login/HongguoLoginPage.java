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
    private final java.util.List<FrameLayout> agreeDots = new java.util.ArrayList<>();
    private boolean agreed;

    // 抖音授权页件
    private WebView webView;
    private LinearLayout webBox;
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
                tvResend.setTextColor(Color.parseColor("#FA6725"));
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
     * （手势让位全应用口径），根视图顶距不动（主页/手机号页的页内列自带
     * 54dp 顶内距已让开状态栏高度；抖音授权面没有页内列，由同一 insets
     * 监听给它的内容根补状态栏顶距，见下方监听内 webBox 分支）。
     *
     * <p>2026-10-29 补根（1.4.59 装机截图 113591 定罪）：上一版只落了颜色
     * （状态栏透明＋decorFits=false＋MATCH_PARENT），真机上带仍在——像素
     * 实测带高恰＝状态栏高、带色＝宿主页顶渐变被 Dialog 的 dim 压暗后的
     * 色（左橙右灰、横向匀变、纵向恒定）。真断点＝Dialog 窗口的「帧」本身
     * 没盖住状态栏区：MATCH_PARENT 只定尺寸不定帧位，dialog 窗默认帧被
     * 系统收在状态栏下方，状态栏区露出的是宿主窗口（再被 dim 压暗）。
     * 补 FLAG_LAYOUT_IN_SCREEN 让窗口帧盖满整屏（含状态栏底下），柔光底
     * 这才真铺到屏幕顶；颜色那套保留不动。</p>
     */
    private void applyImmersiveStatusBar(Window window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
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
            // 抖音授权面没有页内列的 54dp 顶内距托底：它的内容根（webBox）
            // 顶部按状态栏真实高度让位，WebView 本体从状态栏下方起画
            // （box 白底罩住让位区、与网页白底连续）
            if (webBox != null) {
                int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                if (webBox.getPaddingTop() != top) {
                    webBox.setPadding(webBox.getPaddingLeft(), top,
                            webBox.getPaddingRight(), webBox.getPaddingBottom());
                }
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
        // 柔光底照官方实拍采样换成专属 drawable（两团径向柔光＋平坦下半），
        // 不再用斜向三段线性渐变（官方底下半是平坦的 #F4F4F4、青绿团只在左上）
        root.setBackgroundResource(R.drawable.bg_hg_login_soft);
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
        // 横向基准照官方：× 左距 16dp、标题/大钮左距 63dp、协议行 39dp、
        // 授权说明 40dp——列内距取 16dp，各件再用自身左边距补到官方绝对位
        content.setPadding(dp(16), dp(54), dp(16), dp(24));
        return content;
    }

    /** 关闭钮（官方 dao 定版：24dp 图标位、左距 16dp；不用字形「×」冒充图标）。 */
    private View closeButton() {
        ImageView close = new ImageView(requireContext());
        close.setImageResource(R.drawable.ic_hg_login_close);
        close.setOnClickListener(v -> dismiss());
        return close;
    }

    private TextView bigTitle(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        // 官方标题：30sp、纯黑（skin_color_login3_text_light=#FF000000）
        tv.setTextColor(Color.parseColor("#000000"));
        tv.setTextSize(30);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private void addTitles(LinearLayout content) {
        content.addView(closeButton(), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView title1 = bigTitle("登录");
        LinearLayout.LayoutParams t1 = wrapWrap();
        // 标题左距 63dp（列内距 16＋47）、标题顶位照 dao：× 顶 57＋高 24＋距 69
        t1.setMargins(dp(47), dp(72), 0, 0);
        content.addView(title1, t1);
        TextView title2 = bigTitle("发现更多精彩短剧");
        LinearLayout.LayoutParams t2 = wrapWrap();
        // 两行行距照官方单 TextView 的 lineSpacingExtra=10dp 口径
        t2.setMargins(dp(47), dp(8), 0, 0);
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
        // 官方 c7e 定版：高 48dp、左右距 63dp、距标题区 107dp；底是橙色
        // 渐变（官方色值 #FF9A62→#FA6725、实拍色族与圆角约 12dp 对此系）、
        // 字 16sp 白、图标约 22dp、图标与字距 8dp
        LinearLayout douyin = new LinearLayout(requireContext());
        douyin.setOrientation(LinearLayout.HORIZONTAL);
        douyin.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#FF9A62"), Color.parseColor("#FA6725")});
        gd.setCornerRadius(dp(12));
        douyin.setBackground(gd);
        ImageView note = new ImageView(requireContext());
        note.setImageResource(R.drawable.ic_hg_douyin);
        douyin.addView(note, new LinearLayout.LayoutParams(dp(22), dp(22)));
        tvDouyinLabel = new TextView(requireContext());
        tvDouyinLabel.setText("一键登录");
        tvDouyinLabel.setTextColor(Color.WHITE);
        tvDouyinLabel.setTextSize(16);
        LinearLayout.LayoutParams dl = wrapWrap();
        dl.setMargins(dp(8), 0, 0, 0);
        douyin.addView(tvDouyinLabel, dl);
        LinearLayout.LayoutParams db = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        db.setMargins(dp(47), dp(107), dp(47), 0);
        content.addView(douyin, db);
        btnDouyin = douyin;
        douyin.setOnClickListener(v -> onDouyinClicked());

        content.addView(buildAgreementRow(), matchWrap(dp(20)));

        tvHomeStatus = statusText();
        LinearLayout.LayoutParams hs = matchWrap(dp(14));
        hs.setMargins(dp(47), 0, 0, 0);
        content.addView(tvHomeStatus, hs);

        // 弹性空白：把手机圆钮与授权说明压到页底区（照实拍层级）
        View spacer = new View(requireContext());
        content.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部手机图标圆钮：点开切换手机号登录
        // 官方 e1c 定版：整件 48dp（内图 24dp＋四周 12dp）、白底圆、
        // 描边 0.5dp 黑 10%（#1A000000）；内图是手机轮廓、不是电话听筒
        FrameLayout phoneBtn = new FrameLayout(requireContext());
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        int stroke = Math.max(1, Math.round(0.5f * getResources().getDisplayMetrics().density));
        circle.setStroke(stroke, Color.parseColor("#1A000000"));
        phoneBtn.setBackground(circle);
        ImageView phoneIcon = new ImageView(requireContext());
        phoneIcon.setImageResource(R.drawable.ic_hg_login_phone);
        int pad = dp(12);
        phoneIcon.setPadding(pad, pad, pad, pad);
        phoneBtn.addView(phoneIcon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(dp(48), dp(48));
        pb.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(phoneBtn, pb);
        phoneBtn.setOnClickListener(v -> showPane(PANE_PHONE));

        // 页底授权说明（官方 DouyinAuthScopeView／c7_ 定版）：顶部一条
        // 0.5dp 黑 10% 分隔线（左右距 40dp）、标题 12sp 黑 40%、每行
        // 10dp 橙勾圆（#FA7705、首行 40% 透明）＋12sp 黑 40% 文、行距 14dp
        View notesDivider = new View(requireContext());
        notesDivider.setBackgroundColor(Color.parseColor("#1A000000"));
        LinearLayout.LayoutParams nd = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, stroke);
        nd.setMargins(dp(24), dp(30), dp(24), 0);
        content.addView(notesDivider, nd);
        TextView notesTitle = new TextView(requireContext());
        notesTitle.setText("授权后将获得以下权限");
        notesTitle.setTextColor(Color.parseColor("#66000000"));
        notesTitle.setTextSize(12);
        LinearLayout.LayoutParams nt = matchWrap(dp(16));
        nt.setMargins(dp(24), dp(16), dp(24), 0);
        content.addView(notesTitle, nt);
        content.addView(permissionRow("获得你的公开信息（头像、昵称）", true), scopeRowParams());
        content.addView(permissionRow("获得你的抖音手机号", false), scopeRowParams());
        return content;
    }

    private LinearLayout.LayoutParams scopeRowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(24), dp(14), dp(24), 0);
        return p;
    }

    private LinearLayout permissionRow(String text, boolean dimmed) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 勾件照官方：10dp 橙实心圆＋白勾（与协议圈同族），不用「✓」字形凑图标
        FrameLayout dot = new FrameLayout(requireContext());
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(Color.parseColor("#FA7705"));
        dot.setBackground(gd);
        TextView check = new TextView(requireContext());
        check.setText("✓");
        check.setTextColor(Color.WHITE);
        check.setTextSize(7);
        check.setTypeface(null, Typeface.BOLD);
        check.setGravity(Gravity.CENTER);
        dot.addView(check, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (dimmed) dot.setAlpha(0.4f);
        row.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#66000000"));
        tv.setTextSize(12);
        LinearLayout.LayoutParams p = wrapWrap();
        p.setMargins(dp(6), 0, 0, 0);
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
        // 不再自搭「取消／抖音授权」标题栏：授权页本体自带完整页头，这行
        // 是咱家多余件（2026-10-29 他装 1.4.60 后点名「那字就是多余的」），
        // 且在全屏帧下它正好顶进状态栏区。退出授权面走系统返回（子面
        // 先回主页，onCreateDialog 的按键监听已处理），不靠这行字。
        // 顶部让位由 applyImmersiveStatusBar 的 insets 监听给 webBox 补
        // 状态栏真实高度，WebView 从状态栏下方起画。
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        webBox = box;

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
        // 输入区左右距照官方全页手机号面（dar）40dp：列内距 16＋件内距 24
        content.addView(buildStep1(), phoneStepParams());
        content.addView(buildStep2(), phoneStepParams());
        content.addView(buildAgreementRow(), matchWrap(dp(20)));

        tvPhoneStatus = statusText();
        LinearLayout.LayoutParams st = matchWrap(dp(14));
        st.setMargins(dp(24), 0, 0, 0);
        content.addView(tvPhoneStatus, st);

        View spacer = new View(requireContext());
        content.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView back = new TextView(requireContext());
        back.setText("抖音一键登录");
        back.setTextColor(Color.parseColor("#FA6725"));
        back.setTextSize(14);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> showPane(PANE_HOME));
        content.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return content;
    }

    private LinearLayout.LayoutParams phoneStepParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(24), dp(56), dp(24), 0);
        return p;
    }

    private View buildStep1() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        step1 = box;

        LinearLayout phoneRow = new LinearLayout(requireContext());
        phoneRow.setOrientation(LinearLayout.HORIZONTAL);
        phoneRow.setGravity(Gravity.CENTER_VERTICAL);
        // 区号件照官方 dct 定版：「+86」20sp 纯黑＋12dp 下箭头图（距 6dp），
        // 不用「⌄」字形拼在文字里
        TextView prefix = new TextView(requireContext());
        prefix.setText("+86");
        prefix.setTextColor(Color.parseColor("#000000"));
        prefix.setTextSize(20);
        phoneRow.addView(prefix, wrapWrap());
        ImageView arrow = new ImageView(requireContext());
        arrow.setImageResource(R.drawable.ic_hg_login_chevron);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(12), dp(12));
        ap.setMargins(dp(6), 0, 0, 0);
        phoneRow.addView(arrow, ap);
        etPhone = new EditText(requireContext());
        etPhone.setHint("请输入您的手机号");
        // 输入字照官方 cde 定版：20sp 粗黑、提示字黑 45%（#73000000）、左距 12dp
        etPhone.setTextSize(20);
        etPhone.setTypeface(null, Typeface.BOLD);
        etPhone.setTextColor(Color.parseColor("#000000"));
        etPhone.setHintTextColor(Color.parseColor("#73000000"));
        etPhone.setBackground(null);
        etPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        etPhone.setSingleLine(true);
        etPhone.setPadding(dp(12), 0, 0, 0);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, dp(44), 1f);
        phoneRow.addView(etPhone, ep);
        box.addView(phoneRow);
        box.addView(divider());

        btnSend = peachButton("获取验证码");
        LinearLayout.LayoutParams bs = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
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
        // 「验证码已发送至」行照官方验证码面标签口径：14sp、黑 45%（#73000000）
        tvSentTo.setTextColor(Color.parseColor("#73000000"));
        tvSentTo.setTextSize(14);
        box.addView(tvSentTo);

        etCode = new EditText(requireContext());
        etCode.setHint("请输入验证码");
        etCode.setTextSize(20);
        etCode.setTypeface(null, Typeface.BOLD);
        etCode.setTextColor(Color.parseColor("#000000"));
        etCode.setHintTextColor(Color.parseColor("#73000000"));
        etCode.setBackground(null);
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCode.setSingleLine(true);
        LinearLayout.LayoutParams ec = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        ec.setMargins(0, dp(10), 0, 0);
        box.addView(etCode, ec);
        box.addView(divider());

        btnLogin = peachButton("登录");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
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

    /** 协议勾选行：单选圈＋「已阅读并同意红果免费短剧 用户协议 和 隐私政策」（照官方实拍文案，整行点按切换，主页/手机号页同状态）。 */
    private View buildAgreementRow() {
        LinearLayout agreeRow = new LinearLayout(requireContext());
        agreeRow.setOrientation(LinearLayout.HORIZONTAL);
        agreeRow.setGravity(Gravity.CENTER);
        agreeRow.addView(agreementDot());
        TextView agreeText = new TextView(requireContext());
        agreeText.setText(agreementText());
        // 协议字照官方 bqb 定版：12sp、黑 40%（#66000000）
        agreeText.setTextSize(12);
        agreeText.setTextColor(Color.parseColor("#66000000"));
        LinearLayout.LayoutParams at = wrapWrap();
        at.setMargins(dp(6), 0, 0, 0);
        agreeRow.addView(agreeText, at);
        agreeRow.setOnClickListener(v -> {
            agreed = !agreed;
            refreshAgreementDots();
        });
        return agreeRow;
    }

    /**
     * 协议单选圈（照官方包 7.2.5.32 定版）：官方登录页协议行用
     * fqbase_bg_checkbox_agreement——圈直径＝其 oval 定版尺寸
     * {@code @dimen/le}＝10dp，未选＝透明底＋0.8dp 黑 40%（#66000000）
     * 描边，选中＝品牌橙实心＋白色勾；圈与文字间距＝登录页该行
     * CheckBox 的 layout_marginEnd＝6dp（咱家文字左距同为 6dp）。
     * 旧实现是 16sp 的「○/●」字形，可见墨迹只约 7dp，比官方小一圈。
     */
    private View agreementDot() {
        FrameLayout dot = new FrameLayout(requireContext());
        TextView check = new TextView(requireContext());
        check.setText("✓");
        check.setTextColor(Color.WHITE);
        check.setTextSize(7);
        check.setTypeface(null, Typeface.BOLD);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(View.GONE);
        dot.addView(check, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        dot.setBackground(agreementCircleDrawable(false));
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(10), dp(10)));
        agreeDots.add(dot);
        return dot;
    }

    private GradientDrawable agreementCircleDrawable(boolean checked) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        if (checked) {
            gd.setColor(Color.parseColor("#FF7A29"));
        } else {
            gd.setColor(Color.TRANSPARENT);
            int stroke = Math.max(1, Math.round(0.8f * getResources().getDisplayMetrics().density));
            gd.setStroke(stroke, Color.parseColor("#66000000"));
        }
        return gd;
    }

    private void refreshAgreementDots() {
        for (FrameLayout dot : agreeDots) {
            dot.setBackground(agreementCircleDrawable(agreed));
            dot.getChildAt(0).setVisibility(agreed ? View.VISIBLE : View.GONE);
        }
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
        // 报错红字：官方登录面未抠到行内报错的定版（官方多走浮层提示），
        // 此色此号为咱家自留口径、不冒充官方值，待有官方报错实拍再对
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
        // 输入行分隔线照官方 cde 定版：0.5dp、黑 20%（#33000000）
        v.setBackgroundColor(Color.parseColor("#33000000"));
        int h = Math.max(1, Math.round(0.5f * getResources().getDisplayMetrics().density));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private TextView peachButton(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        // 与主页抖音大钮同底：官方橙渐变 #FF9A62→#FA6725、圆角 12dp
        // （旧浅桃渐变在浅底上发飘、是廉价感来源之一）
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#FF9A62"), Color.parseColor("#FA6725")});
        gd.setCornerRadius(dp(12));
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
