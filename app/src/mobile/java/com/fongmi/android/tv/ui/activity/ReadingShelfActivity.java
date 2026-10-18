package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.read.ReadingRouter;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.utils.ImgUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 书架：在看的书＋进度＋继续读。全新设计，干净列表。
 * 点条目继续读（按记录重建会话）；长按移除。
 */
public class ReadingShelfActivity extends AppCompatActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, ReadingShelfActivity.class));
    }

    private final List<ReadingStore.Record> records = new ArrayList<>();
    private ShelfAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        // AI 口令"继续读"中转：直接进最近一本，不在书架停留
        if (getIntent().getBooleanExtra("auto_continue", false)) {
            ReadingRouter.continueReading(this);
            finish();
            return;
        }
        buildUi();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F3EF);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        topBar.setPadding(pad, dp(28), pad, pad);
        topBar.setBackgroundColor(Color.WHITE);

        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setColorFilter(0xFF333333);
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        TextView title = new TextView(this);
        title.setText("书架");
        title.setTextSize(18);
        title.setTextColor(0xFF222222);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(8);
        title.setLayoutParams(tlp);
        topBar.addView(title);

        TextView srcBtn = new TextView(this);
        srcBtn.setText("书源");
        srcBtn.setTextSize(15);
        srcBtn.setTextColor(0xFFB8860B);
        srcBtn.setPadding(dp(12), dp(8), dp(4), dp(8));
        srcBtn.setOnClickListener(v -> LegadoActivity.start(ReadingShelfActivity.this));
        topBar.addView(srcBtn);
        root.addView(topBar);

        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        content.setLayoutParams(clp);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ShelfAdapter();
        rv.setAdapter(adapter);
        content.addView(rv);

        tvEmpty = new TextView(this);
        tvEmpty.setText("还没有在看的书\n去看一本漫画或小说，它会出现在这里");
        tvEmpty.setTextSize(15);
        tvEmpty.setTextColor(0xFF999999);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setLineSpacing(dp(6), 1f);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tvEmpty.setLayoutParams(elp);
        tvEmpty.setGravity(Gravity.CENTER);
        content.addView(tvEmpty);
        root.addView(content);

        setContentView(root);
    }

    private void refresh() {
        records.clear();
        records.addAll(ReadingStore.list());
        tvEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView tvTitle;
            final TextView tvSub;
            final TextView tvProgress;

            Holder(View itemView, ImageView cover, TextView tvTitle, TextView tvSub, TextView tvProgress) {
                super(itemView);
                this.cover = cover;
                this.tvTitle = tvTitle;
                this.tvSub = tvSub;
                this.tvProgress = tvProgress;
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout item = new LinearLayout(parent.getContext());
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(12);
            item.setPadding(pad, pad, pad, pad);
            item.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            item.setLayoutParams(lp);

            ImageView cover = new ImageView(parent.getContext());
            int cw = dp(56);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cw, (int) (cw * 1.3));
            cover.setLayoutParams(clp);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(0xFFE8E2D9);
            item.addView(cover);

            LinearLayout mid = new LinearLayout(parent.getContext());
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            mlp.leftMargin = dp(12);
            mid.setLayoutParams(mlp);

            TextView tvTitle = new TextView(parent.getContext());
            tvTitle.setTextSize(16);
            tvTitle.setTextColor(0xFF222222);
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(tvTitle);

            TextView tvSub = new TextView(parent.getContext());
            tvSub.setTextSize(13);
            tvSub.setTextColor(0xFF888888);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            tvSub.setPadding(0, dp(4), 0, 0);
            mid.addView(tvSub);

            TextView tvProgress = new TextView(parent.getContext());
            tvProgress.setTextSize(13);
            tvProgress.setTextColor(0xFFB8860B);
            tvProgress.setPadding(0, dp(4), 0, 0);
            mid.addView(tvProgress);

            item.addView(mid);
            return new Holder(item, cover, tvTitle, tvSub, tvProgress);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            ReadingStore.Record r = records.get(position);
            h.tvTitle.setText(r.bookTitle);
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(r.updatedAt));
            h.tvSub.setText(r.sourceName + " · " + r.chapterName + " · " + time);
            if ("NOVEL".equals(r.kind)) {
                h.tvProgress.setText("已读 " + Math.round(r.position / 100f) + "%");
            } else {
                h.tvProgress.setText(r.position > 0 ? "看到第 " + (r.position + 1) + " 张" : "开始看");
            }
            if (!TextUtils.isEmpty(r.coverUrl)) {
                ImgUtil.load(r.bookTitle, r.coverUrl, h.cover, true);
            } else {
                h.cover.setImageDrawable(null);
            }
            h.itemView.setOnClickListener(v -> {
                ReadingRouter.openRecord(ReadingShelfActivity.this, r);
            });
            h.itemView.setOnLongClickListener(v -> {
                ReadingStore.remove(r.kind, r.sourceName, r.bookTitle);
                Toast.makeText(ReadingShelfActivity.this, "已从书架移除", Toast.LENGTH_SHORT).show();
                refresh();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return records.size();
        }
    }
}
