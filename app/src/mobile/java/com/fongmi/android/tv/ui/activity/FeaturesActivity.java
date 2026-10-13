package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityFeaturesBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

public class FeaturesActivity extends BaseActivity {

    private static final String PREFS = "xingchen";
    private static final String KEY_MINIAPP = "xingchen.miniapp_enabled";

    private ActivityFeaturesBinding binding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, FeaturesActivity.class));
    }

    public static boolean isMiniAppEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MINIAPP, false);
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityFeaturesBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        refreshAdBlock();
        refreshTmdb();
        refreshPanLogin();
        refreshMiniApp();
        refreshAi();
        binding.swMiniapp.setOnCheckedChangeListener((btn, checked) -> {
            getPrefs().edit().putBoolean(KEY_MINIAPP, checked).apply();
            refreshMiniApp();
            Notify.show(checked ? "小程序已启用" : "小程序已关闭");
        });
        binding.cardAdblock.setOnClickListener(v -> AdBlockActivity.start(this));
        binding.cardTmdb.setOnClickListener(v -> TmdbActivity.start(this));
        binding.cardPanlogin.setOnClickListener(v -> PanLoginActivity.start(this));
        binding.cardAi.setOnClickListener(v -> AiSettingsActivity.start(this));
        binding.cardAiRecommend.setOnClickListener(v -> AiRecommendActivity.start(this));
        binding.cardAiAsr.setOnClickListener(v -> {
            if (AiAsrSettingsActivity.isSupported()) {
                AiAsrSettingsActivity.start(this);
            } else {
                com.fongmi.android.tv.utils.Notify.show("当前设备不支持本地语音识别");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAdBlock();
        refreshTmdb();
        refreshPanLogin();
        refreshMiniApp();
        refreshAi();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void refreshAdBlock() {
        boolean enabled = AdBlockActivity.isEnabled(this);
        binding.tvAdblockSub.setText(enabled ? "已启用" : "未启用");
    }

    private void refreshTmdb() {
        String key = TmdbActivity.getApiKey(this);
        binding.tvTmdbSub.setText(key == null || key.isEmpty() ? "未配置" : "已配置");
    }

    private void refreshPanLogin() {
        binding.tvPanloginSub.setText(PanLoginActivity.loginSummary(this));
    }

    private void refreshMiniApp() {
        boolean enabled = isMiniAppEnabled(this);
        binding.swMiniapp.setChecked(enabled);
        binding.tvMiniappSub.setText(enabled ? "已启用" : "未启用");
    }

    private void refreshAi() {
        binding.tvAiSub.setText(com.fongmi.android.tv.ai.AiStore.summary(this));
        // 未配置 AI 时隐藏推荐入口
        boolean hasAi = com.fongmi.android.tv.ai.AiStore.hasAny(this);
        binding.cardAiRecommend.setVisibility(hasAi ? View.VISIBLE : View.GONE);
        refreshAiAsr();
    }

    private void refreshAiAsr() {
        // 本地字幕入口：只在支持的设备上显示
        boolean supported = AiAsrSettingsActivity.isSupported();
        binding.cardAiAsr.setVisibility(supported ? View.VISIBLE : View.GONE);
        if (supported) {
            boolean modelReady = com.fongmi.android.tv.ai.asr.AiAsrModelManager.isModelReady(this);
            boolean enabled = com.fongmi.android.tv.ai.asr.AiAsrController.get().isEnabled(this);
            if (enabled) {
                binding.tvAiAsrSub.setText("已开启 · 本地识别中");
            } else if (modelReady) {
                binding.tvAiAsrSub.setText("模型已下载 · 点击管理");
            } else {
                binding.tvAiAsrSub.setText("无字幕时本地识别");
            }
        }
    }
}
