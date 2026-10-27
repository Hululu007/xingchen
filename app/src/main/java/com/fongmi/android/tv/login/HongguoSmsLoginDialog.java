package com.fongmi.android.tv.login;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgPassportClient;

/**
 * 红果原生短信登录页（壳子统一登录托管的红果入口，不再走网页框）。
 *
 * <p>构成照潇洒哥定的谷歌版登录页样张（2026-10-27 他亲点）：全屏浅色
 * 柔光底、左上 ×、两行大标题「登录／发现更多精彩短剧」、+86 手机号行
 * （细下划线）、桃色大钮「获取验证码」、协议勾选行；只有手机号验证码
 * 一条路，不摆第三方登录。发码后同页换第二步：验证码行＋「登录」钮＋
 * 倒计时重发（样张只给第一步，第二步沿同一视觉语言补齐）。</p>
 *
 * <p>链路走 {@link HgPassportClient}（passport 原生短信链，证据见
 * passport-login-dig.md）；成功把 Cookie 交回 LoginManager 落托管，
 * 红果接口随即带态。未定版如实：passport 的 TTNet 签名头咱们没有，
 * 能否过校验待真机回包；失败时服务端原话直接显示在页内，不假成功。</p>
 */
public class HongguoSmsLoginDialog extends DialogFragment {

    private final HgPassportClient client = new HgPassportClient();

    private EditText etPhone;
    private EditText etCode;
    private TextView btnSend;
    private TextView btnLogin;
    private TextView tvResend;
    private TextView tvStatus;
    private TextView tvAgreeDot;
    private View step1;
    private View step2;
    private TextView tvSentTo;

    private LoginManager.LoginCallback callback;
    private boolean agreed;
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

    public static HongguoSmsLoginDialog create() {
        return new HongguoSmsLoginDialog();
    }

    public HongguoSmsLoginDialog callback(LoginManager.LoginCallback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
            if (f instanceof HongguoSmsLoginDialog) return;
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
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        stopped = true;
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

    // ---------------- UI（照样张构成，数值按样张比例落） ----------------

    private View createContentView() {
        FrameLayout root = new FrameLayout(requireContext());
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#8FE8D8"), Color.parseColor("#FFE7C4"),
                        Color.parseColor("#FFFBF3")});
        root.setBackground(bg);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(54), dp(28), dp(24));
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView close = new TextView(requireContext());
        close.setText("×");
        close.setTextColor(Color.parseColor("#111111"));
        close.setTextSize(30);
        close.setOnClickListener(v -> dismiss());
        content.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title1 = bigTitle("登录");
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        t1.setMargins(dp(10), dp(52), 0, 0);
        content.addView(title1, t1);
        TextView title2 = bigTitle("发现更多精彩短剧");
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        t2.setMargins(dp(10), dp(6), 0, 0);
        content.addView(title2, t2);

        content.addView(buildStep1(), matchWrap(dp(56)));
        content.addView(buildStep2(), matchWrap(dp(56)));

        tvStatus = new TextView(requireContext());
        tvStatus.setTextColor(Color.parseColor("#D93025"));
        tvStatus.setTextSize(13);
        LinearLayout.LayoutParams st = matchWrap(dp(16));
        st.setMargins(dp(10), 0, 0, 0);
        content.addView(tvStatus, st);
        return root;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, topMargin, 0, 0);
        return p;
    }

    private TextView bigTitle(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#111111"));
        tv.setTextSize(33);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
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
        phoneRow.addView(prefix, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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

        // 协议勾选行：圆点＋「已阅读并同意 用户协议 和 隐私政策」（协议名为下划线样式，整行点按切换勾选）
        LinearLayout agreeRow = new LinearLayout(requireContext());
        agreeRow.setOrientation(LinearLayout.HORIZONTAL);
        agreeRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ar = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ar.setMargins(0, dp(22), 0, 0);
        tvAgreeDot = new TextView(requireContext());
        tvAgreeDot.setText("○");
        tvAgreeDot.setTextColor(Color.parseColor("#666666"));
        tvAgreeDot.setTextSize(16);
        agreeRow.addView(tvAgreeDot);
        TextView agreeText = new TextView(requireContext());
        agreeText.setText(agreementText());
        agreeText.setTextSize(13);
        agreeText.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        at.setMargins(dp(6), 0, 0, 0);
        agreeRow.addView(agreeText, at);
        agreeRow.setOnClickListener(v -> {
            agreed = !agreed;
            tvAgreeDot.setText(agreed ? "●" : "○");
            tvAgreeDot.setTextColor(Color.parseColor(agreed ? "#FF8C4A" : "#666666"));
        });
        box.addView(agreeRow, ar);
        return box;
    }

    private SpannableString agreementText() {
        String text = "已阅读并同意 用户协议 和 隐私政策";
        SpannableString ss = new SpannableString(text);
        int a = text.indexOf("用户协议");
        ss.setSpan(new UnderlineSpan(), a, a + 4, 0);
        int b = text.indexOf("隐私政策");
        ss.setSpan(new UnderlineSpan(), b, b + 4, 0);
        return ss;
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

    // ---------------- 流程 ----------------

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
        tvStatus.setText("");
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
                    tvStatus.setText(message == null || message.isEmpty() ? "验证码发送失败，请稍后再试" : message);
                });
            }
        });
    }

    private void onLoginClicked() {
        if (code().length() < 4) {
            Toast.makeText(requireContext(), "请输入验证码", Toast.LENGTH_SHORT).show();
            return;
        }
        tvStatus.setText("");
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
                    tvStatus.setText(message == null || message.isEmpty() ? "登录没成功，请稍后再试" : message);
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

    private String maskPhone(String p) {
        return p.length() == 11 ? p.substring(0, 3) + "****" + p.substring(7) : p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
