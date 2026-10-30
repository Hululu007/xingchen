package com.fongmi.android.tv.hongguo.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.hongguo.drama.DramaDetail;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 选集单（2026-10-30 从零重建）：底部弹窗，头部剧名＋完结状态，
 * 集号网格（当前集红底高亮），超过 50 集时带区间页签。点集即播、
 * 关单——竖屏与横屏（阶段8）共用同一事件链（回调回宿主切集）。
 */
public final class HgEpisodeSheet {

    public interface OnPick {
        void onPick(int episodeIndex);
    }

    private static final int RANGE = 50;

    private HgEpisodeSheet() {
    }

    public static void show(AppCompatActivity activity, DramaDetail detail,
                            int currentEpisode, OnPick onPick) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        java.util.function.IntUnaryOperator dp = v -> (int) (v * density + 0.5f);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundBg(0xFF1C1C1E, dp.applyAsInt(18)));
        root.setPadding(dp.applyAsInt(18), dp.applyAsInt(16), dp.applyAsInt(18), dp.applyAsInt(14));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(detail.getTitle() == null ? "" : detail.getTitle());
        title.setTextColor(Color.WHITE);
        title.setTextSize(16.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.ImageView close = new android.widget.ImageView(activity);
        close.setImageResource(com.fongmi.android.tv.R.drawable.ic_drama_close);
        close.setColorFilter(0xFFAAAAAA);
        close.setPadding(dp.applyAsInt(10), dp.applyAsInt(6), dp.applyAsInt(2), dp.applyAsInt(6));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp.applyAsInt(34), dp.applyAsInt(34)));
        root.addView(head);

        int total = detail.getEpisodes().size();
        TextView sub = new TextView(activity);
        boolean finished = detail.getSeriesStatus() != null && detail.getSeriesStatus() == 1;
        sub.setText(finished ? "已完结 · 全" + total + "集" : "更新至第" + total + "集");
        sub.setTextColor(0xFF9E9EA7);
        sub.setTextSize(12.5f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp.applyAsInt(4);
        root.addView(sub, subLp);

        RecyclerView grid = new RecyclerView(activity);
        grid.setLayoutManager(new GridLayoutManager(activity, 5));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp.applyAsInt(12);
        root.addView(grid, glp);

        List<int[]> ranges = new ArrayList<>();
        for (int start = 1; start <= total; start += RANGE) {
            ranges.add(new int[]{start, Math.min(total, start + RANGE - 1)});
        }
        LinearLayout rangeRow = new LinearLayout(activity);
        rangeRow.setOrientation(LinearLayout.HORIZONTAL);
        if (ranges.size() > 1) {
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.topMargin = dp.applyAsInt(10);
            root.addView(rangeRow, 2, rlp);
        }

        final int[] activeRange = {Math.max(0, (currentEpisode - 1) / RANGE)};
        EpAdapter adapter = new EpAdapter(activity, detail, currentEpisode, idx -> {
            dialog.dismiss();
            onPick.onPick(idx);
        }, ranges.isEmpty() ? new int[]{1, total} : ranges.get(activeRange[0]), dp);
        grid.setAdapter(adapter);

        if (ranges.size() > 1) {
            for (int i = 0; i < ranges.size(); i++) {
                final int ri = i;
                int[] r = ranges.get(i);
                TextView chip = new TextView(activity);
                chip.setText(r[0] + "-" + r[1]);
                chip.setTextSize(12.5f);
                chip.setPadding(dp.applyAsInt(11), dp.applyAsInt(5), dp.applyAsInt(11), dp.applyAsInt(5));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                clp.rightMargin = dp.applyAsInt(8);
                rangeRow.addView(chip, clp);
                chip.setOnClickListener(v -> {
                    activeRange[0] = ri;
                    adapter.setRange(ranges.get(ri));
                    styleRangeChips(rangeRow, ri, dp);
                });
            }
            styleRangeChips(rangeRow, activeRange[0], dp);
            // 初始定位到当前集所在区间
            adapter.setRange(ranges.get(activeRange[0]));
        }

        dialog.setContentView(root);
        dialog.show();
        // 限高：网格最多占半屏
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private static void styleRangeChips(LinearLayout row, int active, java.util.function.IntUnaryOperator dp) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView chip = (TextView) row.getChildAt(i);
            boolean on = i == active;
            chip.setTextColor(on ? Color.WHITE : 0xFFB8B8BE);
            chip.setBackground(roundBg(on ? 0xFFFF5A4E : 0xFF2C2C30, dp.applyAsInt(13)));
        }
    }

    private static GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static final class EpAdapter extends RecyclerView.Adapter<EpAdapter.H> {
        private final AppCompatActivity activity;
        private final DramaDetail detail;
        private final int current;
        private final OnPick onPick;
        private final java.util.function.IntUnaryOperator dp;
        private int from;
        private int to;

        EpAdapter(AppCompatActivity activity, DramaDetail detail, int current, OnPick onPick,
                  int[] range, java.util.function.IntUnaryOperator dp) {
            this.activity = activity;
            this.detail = detail;
            this.current = current;
            this.onPick = onPick;
            this.dp = dp;
            this.from = range[0];
            this.to = range[1];
        }

        void setRange(int[] range) {
            from = range[0];
            to = range[1];
            notifyDataSetChanged();
        }

        class H extends RecyclerView.ViewHolder {
            final TextView text;

            H(TextView t) {
                super(t);
                text = t;
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView t = new TextView(activity);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(14.5f);
            GridLayoutManager.LayoutParams lp = new GridLayoutManager.LayoutParams(-1, dp.applyAsInt(46));
            lp.setMargins(dp.applyAsInt(4), dp.applyAsInt(4), dp.applyAsInt(4), dp.applyAsInt(4));
            t.setLayoutParams(lp);
            return new H(t);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            int ep = from + position;
            boolean on = ep == current;
            h.text.setText(String.valueOf(ep));
            h.text.setTextColor(on ? Color.WHITE : 0xFFE8E8EA);
            h.text.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            h.text.setBackground(roundBg(on ? 0xFFFF5A4E : 0xFF2C2C30, dp.applyAsInt(10)));
            h.text.setOnClickListener(v -> onPick.onPick(ep));
        }

        @Override
        public int getItemCount() {
            return Math.max(0, to - from + 1);
        }
    }
}
