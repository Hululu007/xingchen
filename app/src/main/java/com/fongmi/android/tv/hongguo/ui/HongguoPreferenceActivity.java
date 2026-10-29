package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoPreferenceBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgPreference;
import com.fongmi.android.tv.ui.base.BaseActivity;

/**
 * 红果·看剧偏好页（官方「我的」功能位 y93.l0 的落点 PreferenceActivity
 * 同位同义）：选项全由服务端偏好口下发（GET /reading/user/preference/
 * info/，scene=my_read_preference、content_type=category，见
 * HgRequests.preferenceInfoParams），分区渲染（性别区 common_data／
 * 资料区 preference_list／题材区 genre_preference，模型见 HgPreference），
 * 保存把与回包原态不同的选项增量提交（POST /reading/user/preference/
 * set/）——服务端推荐系统是消费方，本地不存镜像、不假装存上。
 *
 * <p>状态口径：回包真容与保存收否待新版首登实拉验；拉不到/回包无分区
 * 时只显状态文案，不编造选项。标题左对齐、无自带返回箭头（走系统返回）。</p>
 */
public class HongguoPreferenceActivity extends BaseActivity {

    private ActivityHongguoPreferenceBinding binding;
    private HgPreference page;
    private boolean saving;
    private boolean errorState;

    public static void start(Context context) {
        context.startActivity(new Intent(context, HongguoPreferenceActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoPreferenceBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return true;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.hgPrefSave.setOnClickListener(v -> onSave());
        binding.hgPrefState.setOnClickListener(v -> {
            if (errorState) load();
        });
        load();
    }

    private void load() {
        errorState = false;
        showState("正在加载…");
        HgFeedSession.get().client.fetchWatchPreference(new HgCallback<HgPreference>() {
            @Override
            public void onSuccess(HgPreference p) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    page = p;
                    render();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    errorState = true;
                    showState("加载失败，点这里重试");
                });
            }
        });
    }

    private void showState(String text) {
        binding.hgPrefState.setText(text);
        binding.hgPrefState.setVisibility(View.VISIBLE);
        binding.hgPrefScroll.setVisibility(View.GONE);
        binding.hgPrefBottom.setVisibility(View.GONE);
    }

    private void render() {
        binding.hgPrefSections.removeAllViews();
        if (page == null || page.isEmpty()) {
            showState("暂时没有可设置的偏好");
            return;
        }
        binding.hgPrefState.setVisibility(View.GONE);
        binding.hgPrefScroll.setVisibility(View.VISIBLE);
        binding.hgPrefBottom.setVisibility(View.VISIBLE);
        boolean first = true;
        for (HgPreference.Section section : page.sections) {
            if (section.title != null && !section.title.isEmpty()) {
                TextView title = new TextView(this);
                title.setText(section.title);
                title.setTextSize(16);
                title.setTextColor(0xFF000000);
                title.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(first ? 4 : 22);
                title.setLayoutParams(lp);
                binding.hgPrefSections.addView(title);
            }
            if (section.subText != null && !section.subText.isEmpty()) {
                TextView sub = new TextView(this);
                sub.setText(section.subText);
                sub.setTextSize(12);
                sub.setTextColor(0x66000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                sub.setLayoutParams(lp);
                binding.hgPrefSections.addView(sub);
            }
            FlowLayout flow = new FlowLayout(this);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.topMargin = dp(10);
            flow.setLayoutParams(flp);
            for (HgPreference.Option option : section.options) {
                flow.addView(makeChip(section, option));
            }
            binding.hgPrefSections.addView(flow);
            first = false;
        }
        refreshSaveState();
    }

    /** 选项筹：样式与「我的」页二级筹同款（bb8 口径），选中态随工作态重绑。 */
    private TextView makeChip(HgPreference.Section section, HgPreference.Option option) {
        TextView chip = new TextView(this);
        chip.setText(option.text);
        chip.setTextSize(14);
        chip.setPadding(dp(14), dp(5), dp(14), dp(5));
        chip.setMinWidth(dp(56));
        chip.setMinimumHeight(dp(32));
        chip.setGravity(android.view.Gravity.CENTER);
        FlowLayout.LayoutParams lp = new FlowLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        lp.bottomMargin = dp(8);
        chip.setLayoutParams(lp);
        bindChip(chip, option.selected);
        chip.setOnClickListener(v -> {
            if (section.multi) {
                option.selected = !option.selected;
            } else {
                for (HgPreference.Option o : section.options) o.selected = o == option;
            }
            // 整区重绑选中态（单选互斥要连带刷别人）
            ViewGroup parent = (ViewGroup) chip.getParent();
            if (parent != null) {
                for (int i = 0; i < parent.getChildCount() && i < section.options.size(); i++) {
                    bindChip((TextView) parent.getChildAt(i), section.options.get(i).selected);
                }
            }
            refreshSaveState();
        });
        return chip;
    }

    private void bindChip(TextView chip, boolean on) {
        // 与「我的」子筛筹同一定版（bb8＋selector）：选中字 #FFFA7705、
        // 未选字 #66000000，底 bg_hg_mine_chip_on/off（圆角 8dp、
        // #1AFA7705／#08000000）
        chip.setTextColor(on ? 0xFFFA7705 : 0x66000000);
        chip.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        chip.setBackgroundResource(on ? com.fongmi.android.tv.R.drawable.bg_hg_mine_chip_on
                : com.fongmi.android.tv.R.drawable.bg_hg_mine_chip_off);
    }

    private void refreshSaveState() {
        boolean ready = page != null && page.hasChanges() && !saving;
        binding.hgPrefSave.setEnabled(ready);
        binding.hgPrefSave.setAlpha(ready ? 1f : 0.4f);
    }

    private void onSave() {
        if (page == null || saving || !page.hasChanges()) return;
        saving = true;
        refreshSaveState();
        HgFeedSession.get().client.saveWatchPreference(page, new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    // 存成功才把工作态提为新原态（与服务端已存状态对齐）
                    for (HgPreference.Section s : page.sections) {
                        for (HgPreference.Option o : s.options) o.fetchedSelected = o.selected;
                    }
                    Toast.makeText(HongguoPreferenceActivity.this,
                            "偏好设置成功，将为你优化推荐内容", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    saving = false;
                    refreshSaveState();
                    Toast.makeText(HongguoPreferenceActivity.this,
                            "保存失败，请稍后再试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 最小流式容器：子件从左到右排、放不下换行（选项筹专用，无第三方依赖）。 */
    private static final class FlowLayout extends ViewGroup {

        public static class LayoutParams extends MarginLayoutParams {
            public LayoutParams(int width, int height) {
                super(width, height);
            }

            public LayoutParams(ViewGroup.LayoutParams source) {
                super(source);
            }
        }

        FlowLayout(Context context) {
            super(context);
        }

        @Override
        protected LayoutParams generateDefaultLayoutParams() {
            return new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        @Override
        protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
            return new LayoutParams(p);
        }

        @Override
        protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
            return p instanceof LayoutParams;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0;
            int rowHeight = 0;
            int totalHeight = getPaddingTop();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (x > 0 && x + childWidth > width) {
                    totalHeight += rowHeight;
                    x = 0;
                    rowHeight = 0;
                }
                x += childWidth;
                rowHeight = Math.max(rowHeight, childHeight);
            }
            totalHeight += rowHeight + getPaddingBottom();
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                    resolveSize(totalHeight, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l - getPaddingLeft() - getPaddingRight();
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (x > getPaddingLeft() && x - getPaddingLeft() + childWidth > width) {
                    y += rowHeight;
                    x = getPaddingLeft();
                    rowHeight = 0;
                }
                child.layout(x + lp.leftMargin, y + lp.topMargin,
                        x + lp.leftMargin + child.getMeasuredWidth(),
                        y + lp.topMargin + child.getMeasuredHeight());
                x += childWidth;
                rowHeight = Math.max(rowHeight, childHeight);
            }
        }
    }
}
