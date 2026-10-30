package com.fongmi.android.tv.hongguo.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.drama.DramaAlbumDetail;
import com.fongmi.android.tv.hongguo.drama.DramaItem;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * 视频页合集列表单（2026-10-30 UI 同步批，定稿第 7 条）：底部合集条
 * 点开后的同系列列表——序号＋封面＋标题，当前条高亮，点行跳播。
 * 与选集单（HgEpisodeSheet）分工：那是正剧集号网格，这是合集视频行。
 */
public final class HgAlbumSheet {

    public interface OnPick {
        void onPick(int index);
    }

    private HgAlbumSheet() {
    }

    public static void show(AppCompatActivity activity, DramaAlbumDetail album,
                            int currentIndex, OnPick onPick) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        java.util.function.IntUnaryOperator dp = v -> (int) (v * density + 0.5f);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundBg(0xFF1C1C1E, dp.applyAsInt(18)));
        root.setPadding(dp.applyAsInt(18), dp.applyAsInt(16), dp.applyAsInt(18), dp.applyAsInt(10));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        boolean finished = album.seriesStatus != null && album.seriesStatus == 1;
        int total = album.episodeCount > 0 ? album.episodeCount : album.rows.size();
        String count = HgPanelLogic.countText(finished, total);
        title.setText("合集 · " + (album.title == null ? "" : album.title)
                + (count.isEmpty() ? "" : " · " + count));
        title.setTextColor(Color.WHITE);
        title.setTextSize(15.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView close = new ImageView(activity);
        close.setImageResource(R.drawable.ic_drama_close);
        close.setColorFilter(0xFFAAAAAA);
        close.setPadding(dp.applyAsInt(10), dp.applyAsInt(6), dp.applyAsInt(2), dp.applyAsInt(6));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp.applyAsInt(34), dp.applyAsInt(34)));
        root.addView(head);

        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp.applyAsInt(8);
        root.addView(list, llp);
        list.setAdapter(new RecyclerView.Adapter<RowHolder>() {
            @NonNull
            @Override
            public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp.applyAsInt(6), dp.applyAsInt(8), dp.applyAsInt(6), dp.applyAsInt(8));
                TextView num = new TextView(activity);
                num.setTag("num");
                num.setTextColor(0xFF9E9EA7);
                num.setTextSize(13);
                num.setGravity(Gravity.CENTER);
                row.addView(num, new LinearLayout.LayoutParams(dp.applyAsInt(30), -2));
                ImageView cover = new ImageView(activity);
                cover.setTag("cover");
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackground(roundBg(0xFF232328, dp.applyAsInt(6)));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        dp.applyAsInt(64), dp.applyAsInt(40));
                clp.leftMargin = dp.applyAsInt(6);
                row.addView(cover, clp);
                TextView name = new TextView(activity);
                name.setTag("name");
                name.setTextColor(0xFFE8E8EA);
                name.setTextSize(13.5f);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
                nlp.leftMargin = dp.applyAsInt(10);
                row.addView(name, nlp);
                return new RowHolder(row);
            }

            @Override
            public void onBindViewHolder(@NonNull RowHolder h, int position) {
                DramaItem item = album.rows.get(position);
                h.num.setText(String.valueOf(position + 1));
                boolean on = position == currentIndex;
                h.name.setText(item.getTitle() == null ? "" : item.getTitle());
                h.name.setTextColor(on ? Color.WHITE : 0xFFE8E8EA);
                h.name.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                        : android.graphics.Typeface.DEFAULT);
                if (item.getCoverUrl() != null && !item.getCoverUrl().isEmpty()) {
                    Glide.with(activity).load(item.getCoverUrl())
                            .transform(new RoundedCorners(dp.applyAsInt(6))).into(h.cover);
                } else {
                    h.cover.setImageDrawable(null);
                }
                h.itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    onPick.onPick(position);
                });
            }

            @Override
            public int getItemCount() {
                return album.rows.size();
            }
        });
        if (currentIndex > 0) list.scrollToPosition(currentIndex);

        dialog.setContentView(root);
        dialog.show();
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView num;
        final ImageView cover;
        final TextView name;

        RowHolder(LinearLayout row) {
            super(row);
            num = row.findViewWithTag("num");
            cover = row.findViewWithTag("cover");
            name = row.findViewWithTag("name");
        }
    }

    private static GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }
}
