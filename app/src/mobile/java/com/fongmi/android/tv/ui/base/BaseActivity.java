package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;

import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public abstract class BaseActivity extends AppCompatActivity {

    protected abstract ViewBinding getBinding();

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Setting.wrapDisplay(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdge();
        // // enableDynamicColor(); // Disabled for wallpaper // Disabled for wallpaper
        super.onCreate(savedInstanceState);
        { getWindow().setStatusBarColor(0x00000000); getWindow().getDecorView().post(() -> { com.xingchen.tv.theme.ThemeManager.get().apply(this); }); }
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        setBackCallback();
        initEvent();
        // 精修 P4：全 App 页面进出统一渐入渐出（只用 alpha，200ms）
        overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
    }

    @Override
    public void finish() {
        super.finish();
        // 精修 P4：finish 路径统一淡出；recreate()/正常返回都走这里，视觉一致
        overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
    }


    protected FragmentActivity getActivity() {
        return this;
    }



    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void setPadding(ViewGroup layout) {
        setPadding(layout, false);
    }

    protected void setPadding(ViewGroup layout, boolean leftOnly) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        DisplayCutout cutout = ResUtil.getDisplay(this).getCutout();
        if (cutout == null) return;
        int top = cutout.getSafeInsetTop();
        int left = cutout.getSafeInsetLeft();
        int right = cutout.getSafeInsetRight();
        int bottom = cutout.getSafeInsetBottom();
        int padding = left | right | top | bottom;
        layout.setPadding(padding, 0, leftOnly ? 0 : padding, 0);
    }

    protected void noPadding(ViewGroup layout) {
        layout.setPadding(0, 0, 0, 0);
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private void enableEdgeToEdge() {
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    private void enableDynamicColor() {
        int color = Setting.getDynamicColor();
        if (color != 0) DynamicColors.applyToActivityIfAvailable(this, new DynamicColorsOptions.Builder().setContentBasedSource(color).build());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
        if (o instanceof RefreshEvent event && event.getType() == RefreshEvent.Type.LANGUAGE) recreate();
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onResume() {
        { getWindow().getDecorView().post(() -> { com.xingchen.tv.theme.ThemeManager.get().apply(this); }); } // xc_onResume
        super.onResume();
        Updater.create().resume(this);
        try {
            com.fongmi.android.tv.ai.AiFloatManager.get().onResume(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        try {
            com.fongmi.android.tv.ai.AiFloatManager.get().onPause();
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
