package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoLibraryBinding;
import com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.ui.base.BaseActivity;

/**
 * 红果「我的」集合页：刷剧页左上 ☰ 的落点。只挂已经做好的面——
 * 登录状态行（壳子统一登录托管的红果提供方）＋追更/收藏/观看历史三页入口。
 * 预约在样本与数据里没有实证（见四阶段回报未定项），不摆入口、不占位。
 * 剧场等后续阶段的面将来在此加行，本页不预留假项。
 */
public class HgLibraryActivity extends BaseActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, HgLibraryActivity.class));
    }

    private ActivityHongguoLibraryBinding binding;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoLibraryBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        refreshAccountRow();
    }

    @Override
    protected void initEvent() {
        binding.hgLibBack.setOnClickListener(v -> finish());
        binding.hgLibFollow.setOnClickListener(v -> HgListActivity.start(this, HgListActivity.KIND_FOLLOW));
        binding.hgLibFav.setOnClickListener(v -> HgListActivity.start(this, HgListActivity.KIND_FAV));
        binding.hgLibHistory.setOnClickListener(v -> HgListActivity.start(this, HgListActivity.KIND_HISTORY));
        binding.hgLibAccountBtn.setOnClickListener(v -> {
            if (HgLoginAuthProvider.isLoggedIn()) {
                LoginManager.logoutHongguo();
                refreshAccountRow();
            } else {
                boolean delivered = LoginManager.loginHongguoDelivered();
                if (!delivered) {
                    android.widget.Toast.makeText(this, "登录界面暂时打不开，稍后再试",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccountRow();
    }

    private void refreshAccountRow() {
        if (binding == null) return;
        boolean loggedIn = HgLoginAuthProvider.isLoggedIn();
        binding.hgLibAccountText.setText(loggedIn ? "红果账号已登录" : "未登录红果");
        binding.hgLibAccountBtn.setText(loggedIn ? "退出登录" : "去登录");
        binding.hgLibAccount.setVisibility(View.VISIBLE);
    }
}
