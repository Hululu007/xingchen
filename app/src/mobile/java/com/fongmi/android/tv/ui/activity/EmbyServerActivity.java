package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityEmbyServerBinding;
import com.fongmi.android.tv.emby.EmbyApi;
import com.fongmi.android.tv.emby.EmbyMode;
import com.fongmi.android.tv.emby.EmbyStore.EmbyServer;
import com.fongmi.android.tv.emby.EmbyStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.view.EmbyHomeView;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 个性功能里的「Emby 服务器管理」页：加服 / 登录 / 切换 / 删除 / 首页栏目显隐。
 * 服务器增删只在这里，与点播线路两套存储互不影响。
 */
public class EmbyServerActivity extends BaseActivity {

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, EmbyServerActivity.class));
    }

    private ActivityEmbyServerBinding binding;
    /** 正在编辑（重登）的服务器 id；null = 新增 */
    private String editingId;
    private boolean logining;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityEmbyServerBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.ivBack.setOnClickListener(v -> finish());
        binding.btnAdd.setOnClickListener(v -> showForm(null));
        binding.btnCancel.setOnClickListener(v -> hideForm());
        binding.btnLogin.setOnClickListener(v -> doLogin());
        refreshServers();
        refreshSections();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServers();
        refreshSections();
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
            TextView tvAlias = row.findViewById(R.id.tv_alias);
            TextView tvUrl = row.findViewById(R.id.tv_url);
            TextView tvUser = row.findViewById(R.id.tv_user);
            TextView tvCurrent = row.findViewById(R.id.tv_current);
            tvAlias.setText(TextUtils.isEmpty(s.alias) ? s.baseUrl : s.alias);
            tvUrl.setText(s.baseUrl == null ? "" : s.baseUrl);
            boolean login = !TextUtils.isEmpty(s.token);
            tvUser.setText(login ? ("账号：" + (TextUtils.isEmpty(s.userName) ? s.userId : s.userName)) : "未登录");
            boolean isCurrent = actId != null && actId.equals(s.id);
            tvCurrent.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
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
                    // 改开关的唯一入口：写 EmbyStore + 镜像框架表，禁直写
                    EmbyStore.setProxyEnabled(this, sid, checked);
                } catch (Throwable ignored) {
                }
            });
            row.findViewById(R.id.btn_switch).setOnClickListener(v -> onSwitch(s));
            row.findViewById(R.id.btn_login).setOnClickListener(v -> showForm(s));
            row.findViewById(R.id.btn_delete).setOnClickListener(v -> onDelete(s));
            binding.containerServers.addView(row);
        }
    }

    private void onSwitch(EmbyServer s) {
        try {
            EmbyStore.setActive(s.id);
        } catch (Throwable t) {
            Notify.show("切换失败");
            return;
        }
        // 已在专属首页时，换服要让首页重绑新服
        try {
            if (EmbyHomeView.isShowing()) EmbyMode.enter(s.id);
        } catch (Throwable ignored) {
        }
        refreshServers();
        refreshSections();
        Notify.show(getString(R.string.emby_switch_ok, TextUtils.isEmpty(s.alias) ? s.baseUrl : s.alias));
    }

    private void onDelete(EmbyServer s) {
        String name = TextUtils.isEmpty(s.alias) ? s.baseUrl : s.alias;
        new AlertDialog.Builder(this)
                .setTitle(R.string.emby_delete_title)
                .setMessage(getString(R.string.emby_delete_msg, name))
                .setPositiveButton(R.string.emby_btn_delete, (d, w) -> {
                    boolean wasShowing = EmbyHomeView.isShowing();
                    EmbyServer act = active();
                    boolean wasActive = act != null && act.id != null && act.id.equals(s.id);
                    try {
                        EmbyStore.removeServer(s.id);
                    } catch (Throwable t) {
                        Notify.show("删除失败");
                        return;
                    }
                    if (wasShowing && wasActive) {
                        try {
                            EmbyMode.exit();
                        } catch (Throwable ignored) {
                        }
                    }
                    hideForm();
                    refreshServers();
                    refreshSections();
                    Notify.show(R.string.emby_delete_ok);
                })
                .setNegativeButton(R.string.emby_btn_cancel, null)
                .show();
    }

    // ---------- 加服 / 登录表单 ----------

    private void showForm(EmbyServer s) {
        editingId = s == null ? null : s.id;
        binding.etAlias.setText(s == null ? "" : (s.alias == null ? "" : s.alias));
        binding.etUrl.setText(s == null ? "" : (s.baseUrl == null ? "" : s.baseUrl));
        binding.etUser.setText(s == null ? "" : (s.userName == null ? "" : s.userName));
        binding.etPass.setText("");
        binding.tvError.setVisibility(View.GONE);
        binding.formCard.setVisibility(View.VISIBLE);
        binding.formCard.requestFocus();
    }

    private void hideForm() {
        editingId = null;
        binding.formCard.setVisibility(View.GONE);
        binding.etPass.setText("");
    }

    private String normUrl(String u) {
        u = u == null ? "" : u.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private void doLogin() {
        if (logining) return;
        String alias = binding.etAlias.getText().toString().trim();
        String url = normUrl(binding.etUrl.getText().toString());
        String user = binding.etUser.getText().toString().trim();
        String pass = binding.etPass.getText().toString();
        if (url.isEmpty()) {
            showError(getString(R.string.emby_url_empty));
            return;
        }
        if (user.isEmpty()) {
            showError(getString(R.string.emby_user_empty));
            return;
        }
        logining = true;
        binding.tvError.setVisibility(View.GONE);
        binding.btnLogin.setText(R.string.emby_login_doing);
        final String editId = editingId;
        new Thread(() -> {
            String err = null;
            String pwd = pass; // 方法内局部引用，用完即丢，不落盘
            try {
                // 先 ping 再登录，报错中文直接展示
                EmbyApi.ServerInfo info = EmbyApi.ping(url);
                EmbyApi.LoginResult r = EmbyApi.login(url, user, pwd);
                EmbyServer s;
                if (editId != null) {
                    s = findServer(editId);
                    if (s == null) s = newServer();
                } else {
                    s = newServer();
                }
                s.alias = alias.isEmpty() ? (info == null ? "" : info.serverName) : alias;
                s.baseUrl = url;
                s.userId = r.userId;
                s.userName = r.userName;
                s.token = r.token;
                s.lastUsed = System.currentTimeMillis();
                EmbyStore.saveServer(s);
                EmbyStore.setActive(s.id);
            } catch (Exception e) {
                err = e.getMessage();
                if (err == null || err.isEmpty()) err = "登录失败";
            } finally {
                pwd = null;
            }
            final String msg = err;
            App.post(() -> {
                logining = false;
                binding.btnLogin.setText(R.string.emby_btn_ping_login);
                if (msg != null) {
                    showError(msg);
                    return;
                }
                hideForm();
                refreshServers();
                refreshSections();
                Notify.show(R.string.emby_login_ok);
            });
        }).start();
    }

    private EmbyServer newServer() {
        EmbyServer s = new EmbyServer();
        s.id = UUID.randomUUID().toString();
        return s;
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

    // ---------- 首页栏目显隐（按服存） ----------

    private void refreshSections() {
        binding.containerSections.removeAllViews();
        EmbyServer act;
        try {
            act = EmbyStore.active();
        } catch (Throwable t) {
            act = null;
        }
        if (act == null || act.id == null) {
            TextView hint = new TextView(this);
            hint.setText("先添加并登录服务器");
            hint.setTextColor(0xFF1B2431);
            hint.setTextSize(13);
            hint.setPadding(4, 8, 4, 8);
            binding.containerSections.addView(hint);
            return;
        }
        final String sid = act.id;
        String[] keys = {EmbyHomeView.SEC_RESUME, EmbyHomeView.SEC_VIEWS, EmbyHomeView.SEC_FAV, EmbyHomeView.SEC_LATEST, EmbyHomeView.SEC_NEXTUP};
        int[] titles = {R.string.emby_rail_resume, R.string.emby_rail_views, R.string.emby_rail_fav, R.string.emby_rail_latest, R.string.emby_rail_nextup};
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            View row = inflater.inflate(R.layout.item_emby_section, binding.containerSections, false);
            TextView tvName = row.findViewById(R.id.tv_name);
            Switch sw = row.findViewById(R.id.sw);
            tvName.setText(titles[i]);
            boolean visible = true;
            try {
                visible = EmbyStore.sectionVisible(sid, key);
            } catch (Throwable ignored) {
            }
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(visible);
            sw.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
                try {
                    EmbyStore.setSectionVisible(sid, key, checked);
                } catch (Throwable ignored) {
                }
                EmbyHomeView.refreshCurrent();
            });
            binding.containerSections.addView(row);
        }
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
