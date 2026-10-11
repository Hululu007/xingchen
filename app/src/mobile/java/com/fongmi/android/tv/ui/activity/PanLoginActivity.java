package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.cloud.drive115.Drive115;
import com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak;
import com.fongmi.android.tv.cloud.drivequark.DriveQuark;
import com.fongmi.android.tv.databinding.ActivityPanLoginBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.PanLoginDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PanAuth;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;

public class PanLoginActivity extends BaseActivity {

    private ActivityPanLoginBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, PanLoginActivity.class));
    }

    public static String loginSummary(Activity activity) {
        return PanAuth.loginSummary();
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityPanLoginBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        refreshSub();
        binding.cardPan115.setOnClickListener(v -> on115Click());
        binding.cardPanQuark.setOnClickListener(v -> onQuarkClick());
        binding.cardPanPikpak.setOnClickListener(v -> onPikPakClick());
        binding.cardPan115.setOnLongClickListener(v -> on115LongClick());
        binding.cardPanQuark.setOnLongClickListener(v -> onQuarkLongClick());
        binding.cardPanPikpak.setOnLongClickListener(v -> onPikPakLongClick());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSub();
    }

    private void refreshSub() {
        if (PanAuth.is115LoggedIn()) {
            String vip = PanAuth.get115Vip();
            binding.tvPan115Sub.setText(vip.isEmpty() ? "已登录，点击进入文件" : "已登录 · " + vip);
        } else {
            binding.tvPan115Sub.setText("未登录");
        }
        if (PanAuth.isQuarkLoggedIn()) {
            String vip = PanAuth.getQuarkVip();
            binding.tvPanQuarkSub.setText(vip.isEmpty() ? "已登录，点击进入文件" : "已登录 · " + vip);
        } else {
            binding.tvPanQuarkSub.setText("未登录");
        }
        if (PanAuth.isPikPakLoggedIn()) {
            String name = PanAuth.getPikPakUserName();
            binding.tvPanPikpakSub.setText(name.isEmpty() ? "已登录，点击进入文件" : "已登录 · " + name);
        } else {
            binding.tvPanPikpakSub.setText("未登录");
        }
    }

    // ================= 115 =================

    private void on115Click() {
        if (PanAuth.is115LoggedIn()) {
            CloudDriveActivity.start(this, "115");
        } else {
            showLoginMethods("115", new String[]{"扫码登录", "网页登录（支持账号密码）", "导入 Cookie"},
                    which -> {
                        if (which == 0) showQrLogin115();
                        else if (which == 1) base115Dialog().showLogin(this);
                        else showCookieImport("115");
                    });
        }
    }

    private boolean on115LongClick() {
        if (!PanAuth.is115LoggedIn()) return false;
        new AlertDialog.Builder(this)
                .setTitle("115")
                .setMessage("退出当前 115 登录？")
                .setPositiveButton("退出登录", (d, w) -> {
                    PanAuth.clear115();
                    refreshSub();
                    Notify.show("已退出 115 登录");
                })
                .setNegativeButton("取消", null)
                .show();
        return true;
    }

    private PanLoginDialog base115Dialog() {
        return PanLoginDialog.create()
                .title("115")
                .loginUrl("https://115.com/")
                .cookieDomain("115.com")
                .userAgent(PanLoginDialog.DESKTOP_UA)
                .hint("请在下方页面完成 115 登录（支持账号密码），然后点「完成登录」")
                .cookieValidator(PanAuth::is115CookieValid)
                .onLogin(cookie -> {
                    PanAuth.put115Cookie(cookie);
                    refreshSub();
                    Notify.show("115 登录成功");
                });
    }

    /** 115 原生扫码登录（对照 PY QrLogin，真机验证过的流程） */
    private void showQrLogin115() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = ResUtil.dp2px(24);
        layout.setPadding(pad, pad, pad, pad);
        ImageView qrView = new ImageView(this);
        int size = ResUtil.dp2px(220);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        layout.addView(qrView);
        TextView tvStatus = new TextView(this);
        tvStatus.setText("正在获取二维码…");
        tvStatus.setTextSize(14);
        tvStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ResUtil.dp2px(12);
        tvStatus.setLayoutParams(lp);
        layout.addView(tvStatus);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("115 扫码登录")
                .setView(layout)
                .setNegativeButton("取消", (d, w) -> handler.removeCallbacksAndMessages(null))
                .setCancelable(true)
                .create();
        dialog.setOnCancelListener(d -> handler.removeCallbacksAndMessages(null));
        dialog.show();

        Drive115 drive = new Drive115();
        new Thread(() -> {
            try {
                Drive115.QrSession session = drive.qrStart();
                Bitmap bmp = QRCode.getBitmap(session.qrcodeUrl, 220, 1);
                runOnUiThread(() -> {
                    if (bmp != null) qrView.setImageBitmap(bmp);
                    tvStatus.setText("请用 115 App 扫码");
                });
                Runnable poll = new Runnable() {
                    @Override
                    public void run() {
                        new Thread(() -> {
                            try {
                                int r = drive.qrPoll(session);
                                if (r == 2) {
                                    boolean ok = drive.qrLogin(session);
                                    runOnUiThread(() -> {
                                        dialog.dismiss();
                                        if (ok) {
                                            PanAuth.put115Cookie(drive.getCookie());
                                            refreshSub();
                                            Notify.show("115 登录成功");
                                        } else {
                                            Notify.show("登录失败，请重试");
                                        }
                                    });
                                } else if (r == -1) {
                                    runOnUiThread(() -> tvStatus.setText("二维码已失效，请关闭重试"));
                                } else {
                                    runOnUiThread(() -> tvStatus.setText("等待扫码…"));
                                    handler.postDelayed(this, 2500);
                                }
                            } catch (Exception e) {
                                runOnUiThread(() -> tvStatus.setText("网络异常，稍后重试"));
                            }
                        }).start();
                    }
                };
                handler.postDelayed(poll, 2500);
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("获取二维码失败：" + e.getMessage()));
            }
        }).start();
    }

    // ================= 夸克 =================

    private void onQuarkClick() {
        if (PanAuth.isQuarkLoggedIn()) {
            CloudDriveActivity.start(this, "quark");
        } else {
            showLoginMethods("夸克", new String[]{"扫码登录", "网页登录（支持账号密码）", "导入 Cookie"},
                    which -> {
                        if (which == 0) showQrLoginQuark();
                        else if (which == 1) baseQuarkDialog().showLogin(this);
                        else showCookieImport("夸克");
                    });
        }
    }

    private boolean onQuarkLongClick() {
        if (!PanAuth.isQuarkLoggedIn()) return false;
        new AlertDialog.Builder(this)
                .setTitle("夸克")
                .setMessage("退出当前夸克登录？")
                .setPositiveButton("退出登录", (d, w) -> {
                    PanAuth.clearQuark();
                    refreshSub();
                    Notify.show("已退出夸克登录");
                })
                .setNegativeButton("取消", null)
                .show();
        return true;
    }

    private PanLoginDialog baseQuarkDialog() {
        return PanLoginDialog.create()
                .title("夸克")
                .loginUrl("https://pan.quark.cn/")
                .cookieDomain("quark.cn")
                .userAgent(PanLoginDialog.DESKTOP_UA)
                .hint("请在下方页面完成夸克登录（支持账号密码），然后点「完成登录」")
                .onLogin(cookie -> {
                    PanAuth.putQuarkCookie(cookie);
                    refreshSub();
                    Notify.show("夸克登录成功");
                });
    }

    /** 夸克原生扫码登录（来源：h1d3rone/quarkmanager 二维码流程，已验证） */
    private void showQrLoginQuark() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = ResUtil.dp2px(24);
        layout.setPadding(pad, pad, pad, pad);
        ImageView qrView = new ImageView(this);
        int size = ResUtil.dp2px(220);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        layout.addView(qrView);
        TextView tvStatus = new TextView(this);
        tvStatus.setText("正在获取二维码…");
        tvStatus.setTextSize(14);
        tvStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ResUtil.dp2px(12);
        tvStatus.setLayoutParams(lp);
        layout.addView(tvStatus);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("夸克扫码登录")
                .setView(layout)
                .setNegativeButton("取消", (d, w) -> handler.removeCallbacksAndMessages(null))
                .setCancelable(true)
                .create();
        dialog.setOnCancelListener(d -> handler.removeCallbacksAndMessages(null));
        dialog.show();

        DriveQuark drive = new DriveQuark();
        new Thread(() -> {
            try {
                DriveQuark.QrSession session = drive.qrStart();
                Bitmap bmp = QRCode.getBitmap(session.qrUrl, 220, 1);
                runOnUiThread(() -> {
                    if (bmp != null) qrView.setImageBitmap(bmp);
                    tvStatus.setText("请用夸克 App 扫码");
                });
                Runnable poll = new Runnable() {
                    @Override
                    public void run() {
                        new Thread(() -> {
                            try {
                                int r = drive.qrPoll(session);
                                if (r == 2) {
                                    boolean ok = drive.qrLogin(session);
                                    runOnUiThread(() -> {
                                        dialog.dismiss();
                                        if (ok) {
                                            PanAuth.putQuarkCookie(drive.getCookie());
                                            refreshSub();
                                            Notify.show("夸克登录成功");
                                        } else {
                                            Notify.show("登录失败，请重试");
                                        }
                                    });
                                } else if (r == -1) {
                                    runOnUiThread(() -> tvStatus.setText("二维码已失效，请关闭重试"));
                                } else {
                                    runOnUiThread(() -> tvStatus.setText("等待扫码…"));
                                    handler.postDelayed(this, 2500);
                                }
                            } catch (Exception e) {
                                runOnUiThread(() -> tvStatus.setText("网络异常，稍后重试"));
                            }
                        }).start();
                    }
                };
                handler.postDelayed(poll, 2500);
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("获取二维码失败：" + e.getMessage()));
            }
        }).start();
    }

    // ================= PikPak =================

    private void onPikPakClick() {
        if (PanAuth.isPikPakLoggedIn()) {
            CloudDriveActivity.start(this, "pikpak");
        } else {
            showPikPakLogin();
        }
    }

    private boolean onPikPakLongClick() {
        if (!PanAuth.isPikPakLoggedIn()) return false;
        new AlertDialog.Builder(this)
                .setTitle("PikPak")
                .setMessage("退出当前 PikPak 登录？")
                .setPositiveButton("退出登录", (d, w) -> {
                    PanAuth.clearPikPak();
                    refreshSub();
                    Notify.show("已退出 PikPak 登录");
                })
                .setNegativeButton("取消", null)
                .show();
        return true;
    }

    /** PikPak 账号密码登录（原生 API，来源：pikpak-sdk/auth.go Login） */
    private void showPikPakLogin() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = ResUtil.dp2px(24);
        layout.setPadding(pad, pad, pad, pad);
        EditText etUser = new EditText(this);
        etUser.setHint("邮箱 / 手机号");
        etUser.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        layout.addView(etUser);
        EditText etPass = new EditText(this);
        etPass.setHint("密码");
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ResUtil.dp2px(12);
        etPass.setLayoutParams(lp);
        layout.addView(etPass);
        TextView tvTip = new TextView(this);
        tvTip.setText("大陆 IP 访问 PikPak 受限，请先开系统 VPN");
        tvTip.setTextSize(12);
        tvTip.setLayoutParams(lp);
        layout.addView(tvTip);

        new AlertDialog.Builder(this)
                .setTitle("PikPak 登录")
                .setView(layout)
                .setPositiveButton("登录", (d, w) -> {
                    String u = etUser.getText().toString().trim();
                    String p = etPass.getText().toString();
                    if (TextUtils.isEmpty(u) || TextUtils.isEmpty(p)) {
                        Notify.show("用户名和密码不能为空");
                        return;
                    }
                    Notify.show("正在登录…");
                    new Thread(() -> {
                        try {
                            new DrivePikPak(this).login(u, p);
                            runOnUiThread(() -> {
                                PanAuth.invalidatePikPakCache();
                                refreshSub();
                                Notify.show("PikPak 登录成功");
                            });
                        } catch (DrivePikPak.CaptchaRequiredException ce) {
                            runOnUiThread(() -> Notify.show("需要人机验证，请用官方 App 登录一次后再试"));
                        } catch (Exception e) {
                            runOnUiThread(() -> Notify.show("登录失败：" + e.getMessage()));
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 通用 =================

    private interface OnMethodSelected {
        void onSelect(int which);
    }

    private void showLoginMethods(String title, String[] methods, OnMethodSelected cb) {
        new AlertDialog.Builder(this)
                .setTitle(title + "登录方式")
                .setItems(methods, (d, which) -> cb.onSelect(which))
                .setNegativeButton("取消", null)
                .show();
    }

    /** Cookie 导入（Alist 风格：直接粘贴 Cookie 字符串） */
    private void showCookieImport(String title) {
        EditText et = new EditText(this);
        et.setHint("粘贴 Cookie 字符串");
        et.setMinLines(4);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad = ResUtil.dp2px(24);
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("导入 " + title + " Cookie")
                .setView(layout)
                .setPositiveButton("导入", (d, w) -> {
                    String cookie = et.getText().toString().trim();
                    if (TextUtils.isEmpty(cookie)) {
                        Notify.show("Cookie 是空的");
                        return;
                    }
                    if ("115".equals(title)) {
                        if (!PanAuth.is115CookieValid(cookie)) {
                            Notify.show("Cookie 无效：缺少 115 登录关键字段");
                            return;
                        }
                        PanAuth.put115Cookie(cookie);
                    } else {
                        PanAuth.putQuarkCookie(cookie);
                    }
                    refreshSub();
                    Notify.show(title + " Cookie 已导入");
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
