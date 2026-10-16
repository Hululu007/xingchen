package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityEmbyServerBinding;
import com.fongmi.android.tv.emby.EmbyApi;
import com.fongmi.android.tv.emby.EmbyStore.EmbyServer;
import com.fongmi.android.tv.emby.EmbyStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 个性功能里的「Emby 服务器管理」页：纯管理（加服 / 编辑 / 修改密码 / 自定义图标 /
 * 显示开关 / 退出登录 / 删除）。切服只在 Emby 首页左上角做，这页不切服。
 * 卡片一行一个服：图标＋服名＋绿点账号行（可关）＋上次播放行（可开）＋走节点开关＋右侧 ⋮ 菜单。
 */
public class EmbyServerActivity extends BaseActivity {

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, EmbyServerActivity.class));
    }

    private ActivityEmbyServerBinding binding;
    /** 正在编辑（重登）的服务器 id；null = 新增 */
    private String editingId;
    /** 纯编辑模式：只改资料不走登录线程（editingId 非空时才有效） */
    private boolean editOnly;
    /** 修改密码模式：重登表单，除密码外全预填、密码留空并聚焦 */
    private boolean reloginMode;
    /** 新增时表单一打开就定下的服务器 id：登录前先挂「走节点」策略要用它。 */
    private String pendingId;
    /** 协议选择（学 Yamby 的拆分填法）：true=HTTPS（默认），端口随协议给默认值。 */
    private boolean protoHttps = true;
    private boolean logining;
    /** 正在给哪个服选自定义图标 */
    private String iconPickId;

    private final ActivityResultLauncher<String> iconPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::onIconPicked);

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityEmbyServerBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.ivBack.setOnClickListener(v -> finish());
        binding.btnAdd.setOnClickListener(v -> showForm(null, false, false));
        binding.btnCancel.setOnClickListener(v -> hideForm());
        binding.btnLogin.setOnClickListener(v -> doLogin());
        binding.tvProtoHttps.setOnClickListener(v -> setProto(true, true));
        binding.tvProtoHttp.setOnClickListener(v -> setProto(false, true));
        binding.btnAltAdd.setOnClickListener(v -> addAltRow(""));
        binding.btnIconReset.setOnClickListener(v -> onIconReset());
        refreshServers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServers();
    }

    // ---------- 服务器列表 ----------

    private List<EmbyServer> servers() {
        try {
            List<EmbyServer> all = EmbyStore.servers();
            return all == null ? new ArrayList<>() : all;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private EmbyServer active() {
        try {
            return EmbyStore.active();
        } catch (Throwable t) {
            return null;
        }
    }

    private void refreshServers() {
        binding.containerServers.removeAllViews();
        List<EmbyServer> all = servers();
        EmbyServer act = active();
        String actId = act == null ? null : act.id;
        binding.tvNoServer.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (EmbyServer s : all) {
            View row = inflater.inflate(R.layout.item_emby_server, binding.containerServers, false);
            ImageView ivIcon = row.findViewById(R.id.iv_icon);
            TextView tvAlias = row.findViewById(R.id.tv_alias);
            TextView tvUser = row.findViewById(R.id.tv_user);
            View dotUser = row.findViewById(R.id.dot_user);
            TextView tvLastPlayed = row.findViewById(R.id.tv_lastplayed);
            TextView tvCurrent = row.findViewById(R.id.tv_current);
            tvAlias.setText(TextUtils.isEmpty(s.alias) ? s.baseUrl : s.alias);
            // 图标：自定义图优先，没有就显示服名首字
            bindIcon(ivIcon, row.findViewById(R.id.tv_icon_text), s);
            // 用户名行：绿点＋账号，受 showUser 开关控制
            boolean login = s.isLogin();
            if (s.showUser) {
                row.findViewById(R.id.row_user).setVisibility(View.VISIBLE);
                dotUser.setVisibility(View.VISIBLE);
                tvUser.setText(login
                        ? (TextUtils.isEmpty(s.userName) ? s.userId : s.userName)
                        : getString(R.string.emby_not_login).replace("Emby：", ""));
            } else {
                row.findViewById(R.id.row_user).setVisibility(View.GONE);
            }
            // 上次播放行：开关开才显示
            if (s.showLastPlayed) {
                tvLastPlayed.setVisibility(View.VISIBLE);
                tvLastPlayed.setText(s.lastPlayedAt > 0
                        ? getString(R.string.emby_last_played, fmtTime(s.lastPlayedAt))
                        : getString(R.string.emby_last_played_none));
            } else {
                tvLastPlayed.setVisibility(View.GONE);
            }
            boolean isCurrent = actId != null && actId.equals(s.id);
            tvCurrent.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            // 走节点开关放回卡片上：直接切本服走不走节点，开了就生效（按需拉起内核，不用管总开关）
            Switch swProxy = row.findViewById(R.id.sw_proxy);
            swProxy.setOnCheckedChangeListener(null);
            try {
                swProxy.setChecked(EmbyStore.isProxyEnabled(s.id));
            } catch (Throwable ignored) {
                swProxy.setChecked(false);
            }
            final String sid = s.id;
            swProxy.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
                try {
                    EmbyStore.setProxyEnabled(this, sid, checked);
                } catch (Throwable ignored) {
                }
            });
            // ⋮ 菜单
            row.findViewById(R.id.btn_more).setOnClickListener(v -> showServerMenu(v, s));
            // 卡片点击不切服：这页纯管理，切服只在 Emby 首页左上角
            binding.containerServers.addView(row);
        }
    }

    private void bindIcon(ImageView iv, TextView tvText, EmbyServer s) {
        try {
            if (EmbyStore.hasCustomIcon(this, s.id)) {
                tvText.setVisibility(View.GONE);
                iv.setVisibility(View.VISIBLE);
                Glide.with(this).load(EmbyStore.iconFile(this, s.id)).into(iv);
                return;
            }
        } catch (Throwable ignored) {
        }
        iv.setVisibility(View.GONE);
        tvText.setVisibility(View.VISIBLE);
        String name = TextUtils.isEmpty(s.alias) ? s.baseUrl : s.alias;
        tvText.setText(!TextUtils.isEmpty(name) ? name.substring(0, 1) : "服");
    }

    private static String fmtTime(long ts) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------- ⋮ 深色菜单 ----------

    /** 深色独立卡片菜单（不用系统白底菜单）：编辑 / 修改密码 / 自定义图标 / 显示用户名✓ / 显示上次播放时间✓ / 退出登录 / 删除 */
    private void showServerMenu(View anchor, EmbyServer s) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_glass_dark);
        int pad = ResUtil.dp2px(6);
        root.setPadding(pad, pad, pad, pad);
        PopupWindow pw = new PopupWindow(root,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        addMenuRow(root, pw, getString(R.string.emby_btn_edit), false, () -> showForm(s, true, false));
        addMenuRow(root, pw, getString(R.string.emby_menu_change_pass), false, () -> showForm(s, false, true));
        addMenuRow(root, pw, getString(R.string.emby_menu_custom_icon), false, () -> pickIcon(s.id));
        addMenuRow(root, pw, getString(R.string.emby_menu_show_user), s.showUser, () -> {
            s.showUser = !s.showUser;
            try {
                EmbyStore.saveServer(s);
            } catch (Throwable ignored) {
            }
            refreshServers();
        });
        addMenuRow(root, pw, getString(R.string.emby_menu_show_last_played), s.showLastPlayed, () -> {
            s.showLastPlayed = !s.showLastPlayed;
            try {
                EmbyStore.saveServer(s);
            } catch (Throwable ignored) {
            }
            refreshServers();
        });
        if (s.isLogin()) {
            addMenuRow(root, pw, getString(R.string.emby_btn_logout), false, () -> onLogout(s));
        }
        addMenuRow(root, pw, getString(R.string.emby_btn_delete), false, () -> onDelete(s));
        try {
            pw.showAsDropDown(anchor, -ResUtil.dp2px(160), 0, Gravity.END);
        } catch (Throwable t) {
            pw.showAsDropDown(anchor, 0, 0);
        }
    }

    private void addMenuRow(LinearLayout root, PopupWindow pw, String text, boolean checked, Runnable action) {
        TextView tv = new TextView(this);
        tv.setText((checked ? "✓ " : "") + text);
        tv.setTextColor(0xFFF2F4F8);
        tv.setTextSize(14);
        int hp = ResUtil.dp2px(14);
        int vp = ResUtil.dp2px(11);
        tv.setPadding(hp, vp, hp, vp);
        tv.setBackground(getSelectableBg());
        tv.setOnClickListener(v -> {
            try {
                pw.dismiss();
            } catch (Throwable ignored) {
            }
            try {
                action.run();
            } catch (Throwable t) {
                Notify.show("操作失败");
            }
        });
        root.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private android.graphics.drawable.Drawable getSelectableBg() {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            return getDrawable(tv.resourceId);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- 自定义图标 ----------

    private void pickIcon(String serverId) {
        iconPickId = serverId;
        try {
            iconPicker.launch("image/*");
        } catch (Throwable t) {
            Notify.show(R.string.emby_icon_pick_fail);
            iconPickId = null;
        }
    }

    private void onIconPicked(Uri uri) {
        String sid = iconPickId;
        iconPickId = null;
        if (sid == null || uri == null) return;
        boolean ok;
        try {
            ok = EmbyStore.saveCustomIcon(this, sid, uri);
        } catch (Throwable t) {
            ok = false;
        }
        Notify.show(ok ? getString(R.string.emby_icon_saved) : getString(R.string.emby_icon_pick_fail));
        if (ok) refreshServers();
    }

    /** 编辑表单里的「恢复默认图标」。 */
    private void onIconReset() {
        if (editingId == null) return;
        try {
            EmbyStore.clearCustomIcon(this, editingId);
        } catch (Throwable ignored) {
        }
        binding.btnIconReset.setVisibility(View.GONE);
        refreshServers();
        Notify.show(R.string.emby_icon_saved);
    }

    private void onLogout(EmbyServer s) {
        try {
            EmbyStore.logout(s.id);
        } catch (Throwable t) {
            Notify.show("退出失败");
            return;
        }
        refreshServers();
        Notify.show(R.string.emby_logout_ok);
    }

    private void onDelete(EmbyServer s) {
        String name = TextUtils.isEmpty(s.alias) ? s.baseUrl : s.alias;
        new AlertDialog.Builder(this)
                .setTitle(R.string.emby_delete_title)
                .setMessage(getString(R.string.emby_delete_msg, name))
                .setPositiveButton(R.string.emby_btn_delete, (d, w) -> {
                    try {
                        EmbyStore.removeServer(s.id);
                    } catch (Throwable t) {
                        Notify.show("删除失败");
                        return;
                    }
                    hideForm();
                    refreshServers();
                    Notify.show(R.string.emby_delete_ok);
                })
                .setNegativeButton(R.string.emby_btn_cancel, null)
                .show();
    }

    // ---------- 加服 / 登录表单 ----------

    /** 协议切换：选中黄卡、未选输入底；端口还是旧协议默认值时顺手换成新默认值，用户改过的端口不动。 */
    private void setProto(boolean https, boolean adjustPort) {
        protoHttps = https;
        binding.tvProtoHttps.setBackgroundResource(https ? R.drawable.xc_capsule_yellow : R.drawable.xc_input_bg);
        binding.tvProtoHttp.setBackgroundResource(https ? R.drawable.xc_input_bg : R.drawable.xc_capsule_yellow);
        if (adjustPort) {
            String p = binding.etPort.getText().toString().trim();
            String oldDef = https ? "80" : "443";
            if (p.isEmpty() || p.equals(oldDef)) binding.etPort.setText(https ? "443" : "80");
        }
    }

    /** 把存量完整地址拆回四段回填表单：[协议, 主机, 端口, 路径]。 */
    private static String[] splitBase(String url) {
        String proto = "https", host = "", port = "", path = "";
        String u = url == null ? "" : url.trim();
        int p = u.indexOf("://");
        if (p >= 0) {
            proto = u.substring(0, p).toLowerCase();
            u = u.substring(p + 3);
        }
        int slash = u.indexOf('/');
        if (slash >= 0) {
            path = u.substring(slash + 1);
            u = u.substring(0, slash);
        }
        int colon = u.lastIndexOf(':');
        if (colon >= 0 && u.substring(colon + 1).matches("\\d+")) {
            port = u.substring(colon + 1);
            host = u.substring(0, colon);
        } else {
            host = u;
        }
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return new String[]{proto, host, port, path};
    }

    /**
     * @param editOnly true=纯编辑（只改资料不登录），按钮文案变为「保存」
     * @param relogin  true=修改密码模式：重登表单，除密码外全预填、密码留空并聚焦
     */
    private void showForm(EmbyServer s, boolean editOnly, boolean relogin) {
        editingId = s == null ? null : s.id;
        this.editOnly = editOnly && s != null;
        this.reloginMode = relogin && s != null && !editOnly;
        pendingId = s == null ? UUID.randomUUID().toString() : null;
        binding.btnLogin.setText(editOnly && s != null ? R.string.emby_btn_save : R.string.emby_btn_ping_login);
        int userVis = (editOnly && s != null) ? View.GONE : View.VISIBLE;
        binding.etUser.setVisibility(userVis);
        binding.etPass.setVisibility(userVis);
        binding.etAlias.setText(s == null ? "" : (s.alias == null ? "" : s.alias));
        if (s == null || s.baseUrl == null || s.baseUrl.isEmpty()) {
            setProto(true, false);
            binding.etHost.setText("");
            binding.etPort.setText("443");
            binding.etPath.setText("");
        } else {
            String[] parts = splitBase(s.baseUrl);
            setProto("https".equals(parts[0]), false);
            binding.etHost.setText(parts[1]);
            binding.etPort.setText(parts[2].isEmpty() ? (protoHttps ? "443" : "80") : parts[2]);
            binding.etPath.setText(parts[3]);
        }
        binding.etUser.setText(s == null ? "" : (s.userName == null ? "" : s.userName));
        binding.etPass.setText("");
        boolean proxy = false;
        if (s != null) {
            try {
                proxy = EmbyStore.isProxyEnabled(s.id);
            } catch (Throwable ignored) {
            }
        }
        binding.swFormProxy.setOnCheckedChangeListener(null);
        binding.swFormProxy.setChecked(proxy);
        // 备选地址行
        binding.containerAlt.removeAllViews();
        if (s != null) for (String alt : s.altUrls) addAltRow(alt);
        // 恢复默认图标：编辑模式且有自定义图标才露脸
        boolean hasIcon = false;
        if (s != null) {
            try {
                hasIcon = EmbyStore.hasCustomIcon(this, s.id);
            } catch (Throwable ignored) {
            }
        }
        binding.btnIconReset.setVisibility(editOnly && s != null && hasIcon ? View.VISIBLE : View.GONE);
        binding.tvError.setVisibility(View.GONE);
        binding.formCard.setVisibility(View.VISIBLE);
        binding.formCard.requestFocus();
        if (reloginMode) {
            binding.etPass.post(() -> {
                binding.etPass.requestFocus();
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(binding.etPass, 0);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /** 备选地址加一行（可删）。 */
    private void addAltRow(String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ResUtil.dp2px(8);
        row.setLayoutParams(lp);
        EditText et = new EditText(this);
        et.setHint(R.string.emby_alt_url_hint);
        et.setText(url == null ? "" : url);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        et.setBackgroundResource(R.drawable.xc_input_bg);
        et.setTextColor(0xFF0C0E12);
        et.setHintTextColor(0xFF8E97A8);
        et.setTextSize(14);
        et.setPadding(ResUtil.dp2px(14), ResUtil.dp2px(14), ResUtil.dp2px(14), ResUtil.dp2px(14));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row.addView(et, elp);
        TextView del = new TextView(this);
        del.setText("×");
        del.setTextColor(0xFFE5484D);
        del.setTextSize(22);
        del.setPadding(ResUtil.dp2px(12), 0, ResUtil.dp2px(4), 0);
        del.setOnClickListener(v -> binding.containerAlt.removeView(row));
        row.addView(del);
        binding.containerAlt.addView(row);
    }

    /** 从备选地址行收集非空地址。 */
    private List<String> collectAltUrls() {
        List<String> out = new ArrayList<>();
        int n = binding.containerAlt.getChildCount();
        for (int i = 0; i < n; i++) {
            View row = binding.containerAlt.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            for (int j = 0; j < ((LinearLayout) row).getChildCount(); j++) {
                View c = ((LinearLayout) row).getChildAt(j);
                if (c instanceof EditText) {
                    String u = ((EditText) c).getText().toString().trim();
                    while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    if (!u.isEmpty() && !out.contains(u)) out.add(u);
                }
            }
        }
        return out;
    }

    private void hideForm() {
        editingId = null;
        editOnly = false;
        reloginMode = false;
        pendingId = null;
        binding.formCard.setVisibility(View.GONE);
        binding.etPass.setText("");
        binding.containerAlt.removeAllViews();
    }

    /**
     * 表单四段拼回完整地址：协议 + 主机 + 端口（空则协议默认）+ 路径（可选）。
     * 地址框里要是粘了整条链接（含 ://），原样交出去，后面的壳子识别会接管。
     */
    private String buildRawUrl() {
        String host = binding.etHost.getText().toString().trim();
        if (host.contains("://")) return host;
        String port = binding.etPort.getText().toString().trim();
        if (port.isEmpty()) port = protoHttps ? "443" : "80";
        String path = binding.etPath.getText().toString().trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (host.isEmpty()) return "";
        return (protoHttps ? "https" : "http") + "://" + host + ":" + port
                + (path.isEmpty() ? "" : "/" + path);
    }

    /** 纯编辑保存：只更新资料（别名/地址/备选地址/走节点），不走登录线程、不碰凭证。 */
    private void doSaveEdit() {
        String editId = editingId;
        if (editId == null) return;
        String alias = binding.etAlias.getText().toString().trim();
        String url = buildRawUrl();
        boolean viaNode = binding.swFormProxy.isChecked();
        if (url.isEmpty()) {
            showError(getString(R.string.emby_url_empty));
            return;
        }
        EmbyServer s = findServer(editId);
        if (s == null) return;
        boolean urlChanged = !normForCompare(s.baseUrl).equals(normForCompare(url));
        s.alias = alias;
        s.baseUrl = url;
        s.altUrls.clear();
        s.altUrls.addAll(collectAltUrls());
        try {
            EmbyStore.saveServer(s);
            EmbyStore.setProxyEnabled(this, editId, viaNode);
        } catch (Throwable t) {
            Notify.show("保存失败");
            return;
        }
        hideForm();
        refreshServers();
        Notify.show(getString(urlChanged ? R.string.emby_edit_url_changed : R.string.emby_edit_saved));
    }

    /** 地址比对归一：去尾斜杠、抹掉协议默认端口，避免「只补了 :443」被误判为改了地址。 */
    private static String normForCompare(String u) {
        u = u == null ? "" : u.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        u = u.replaceAll("^(https?://[^/]+):443(?=/|$)", "$1");
        u = u.replaceAll("^(https?://[^/]+):80(?=/|$)", "$1");
        return u;
    }

    private void doLogin() {
        if (logining) return;
        if (editOnly && editingId != null) {
            doSaveEdit();
            return;
        }
        String alias = binding.etAlias.getText().toString().trim();
        String url = buildRawUrl();
        String user = binding.etUser.getText().toString().trim();
        String pass = binding.etPass.getText().toString();
        boolean viaNode = binding.swFormProxy.isChecked();
        if (url.isEmpty()) {
            showError(getString(R.string.emby_url_empty));
            return;
        }
        if (user.isEmpty()) {
            showError(getString(R.string.emby_user_empty));
            return;
        }
        // 走节点不再要求总开关先开：登录线程里按需静默拉起内核，拉起失败才中文提示
        logining = true;
        binding.tvError.setVisibility(View.GONE);
        binding.btnLogin.setText(R.string.emby_login_doing);
        final String editId = editingId;
        final boolean isNew = editId == null;
        final String sid = isNew
                ? (pendingId == null ? UUID.randomUUID().toString() : pendingId)
                : editId;
        // 先把服（占位）入库并挂上「走节点」策略，登录请求才有归属可打点；
        // 只通节点的服就靠这一步在登录阶段走上节点。登录失败且是新增服，占位会被清掉。
        try {
            EmbyServer pre = isNew ? null : findServer(sid);
            if (pre == null) {
                pre = new EmbyServer();
                pre.id = sid;
            }
            pre.alias = alias;
            pre.baseUrl = url;
            EmbyStore.saveServer(pre);
            EmbyStore.setProxyEnabled(this, sid, viaNode);
        } catch (Throwable ignored) {
        }
        new Thread(() -> {
            String err = null;
            String pwd = pass; // 方法内局部引用，用完即丢，不落盘
            try {
                // 本服开了走节点：先静默拉起内核（不用管总开关），拉起失败才拦
                if (viaNode && !EmbyApi.ensureNodeForServer(sid))
                    throw new Exception(App.get().getString(R.string.emby_node_start_fail));
                // 短地址由壳子自己认：http/https 与 8096 端口逐个试，通的即最终地址；
                // 备选地址跟在主地址后面一起试，通的回写为 baseUrl
                List<String> alts = null;
                try {
                    EmbyServer cur = findServer(sid);
                    if (cur != null && !cur.altUrls.isEmpty()) alts = new ArrayList<>(cur.altUrls);
                } catch (Throwable ignored) {
                }
                EmbyApi.ResolvedLogin res = EmbyApi.resolveLogin(url, user, pwd, sid, alts);
                EmbyServer s = findServer(sid);
                if (s == null) {
                    s = new EmbyServer();
                    s.id = sid;
                }
                s.alias = alias.isEmpty() ? (res.info == null ? "" : res.info.serverName) : alias;
                s.baseUrl = res.baseUrl;
                s.userId = res.login.userId;
                s.userName = res.login.userName;
                s.token = res.login.token;
                s.lastUsed = System.currentTimeMillis();
                EmbyStore.saveServer(s);
                EmbyStore.setActive(s.id);
            } catch (Exception e) {
                err = e.getMessage();
                if (err == null || err.isEmpty()) err = "登录失败";
                if (isNew) {
                    // 新增服没登上：清占位与策略镜像，不留垃圾服
                    try {
                        EmbyStore.setProxyEnabled(this, sid, false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        EmbyStore.removeServer(sid);
                    } catch (Throwable ignored) {
                    }
                }
            } finally {
                pwd = null;
            }
            final String msg = err;
            App.post(() -> {
                logining = false;
                binding.btnLogin.setText(R.string.emby_btn_ping_login);
                if (msg != null) {
                    showError(msg);
                    refreshServers();
                    return;
                }
                hideForm();
                refreshServers();
                Notify.show(R.string.emby_login_ok);
            });
        }).start();
    }

    private EmbyServer findServer(String id) {
        for (EmbyServer s : servers()) {
            if (s != null && id.equals(s.id)) return s;
        }
        return null;
    }

    private void showError(String msg) {
        binding.tvError.setText(msg);
        binding.tvError.setVisibility(View.VISIBLE);
    }

    /** 给设置页副标题用的登录态文案。 */
    public static String loginSummary() {
        try {
            if (!EmbyStore.hasLogin()) return null;
            List<EmbyServer> all = EmbyStore.servers();
            int n = all == null ? 0 : all.size();
            return "Emby：已登录 " + n + " 服";
        } catch (Throwable t) {
            return null;
        }
    }
}
