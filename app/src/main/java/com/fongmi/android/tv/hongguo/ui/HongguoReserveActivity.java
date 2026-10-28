package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 红果「我的预约」页（我的页功能位进入）：构成对照他发的官方预约页实拍＋
 * 国内版包口径——顶行标题＋编辑、计数筹（已上线·N／待上线·N）、列表行
 * （方形圆角封面＋剧名＋标签行＋简介两行＋右侧去看剧钮）。
 * 数据：本地预约镜像先显，登录后拉服务端预约列表刷新（以服务端为准）。
 * 「新剧预约」一面官方有，但其推荐列表数据口未抠到，本页不摆假面（他的
 * 不摆占位令），抠到再加。编辑态可多选取消预约（走预约开关的服务端同步）。
 */
public class HongguoReserveActivity extends BaseActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, HongguoReserveActivity.class));
    }

    private static final int FILTER_ONLINE = 0;
    private static final int FILTER_UPCOMING = 1;

    private final List<Row> all = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private ReserveAdapter adapter;
    private TextView chipOnline;
    private TextView chipUpcoming;
    private TextView editBtn;
    private TextView emptyView;
    private View editBar;
    private TextView editCount;
    private int filter = FILTER_ONLINE;
    private boolean editing;

    private static final class Row {
        String id;
        String title;
        String cover;
        String desc;
        String tagsText;
        String dueText;
        int count;
        boolean online;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        loadLocal();
        if (LoginManager.isHongguoLoggedIn()) refreshFromServer();
    }

    private LinearLayout buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setPadding(0, dp(24), 0, 0);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView title = new TextView(this);
        title.setText("我的预约");
        title.setTextSize(18);
        title.setTextColor(0xFF000000);
        title.setTypeface(null, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        editBtn = new TextView(this);
        editBtn.setText("编辑");
        editBtn.setTextSize(14);
        editBtn.setTextColor(0xFF000000);
        editBtn.setTypeface(null, Typeface.BOLD);
        editBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        editBtn.setOnClickListener(v -> setEditing(!editing));
        titleRow.addView(editBtn);
        root.addView(titleRow);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(16), dp(4), dp(16), dp(8));
        chipOnline = makeChip(v -> {
            filter = FILTER_ONLINE;
            render();
        });
        chipUpcoming = makeChip(v -> {
            filter = FILTER_UPCOMING;
            render();
        });
        chips.addView(chipOnline);
        chips.addView(chipUpcoming);
        root.addView(chips);

        emptyView = new TextView(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(0xFF999999);
        emptyView.setTextSize(14);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReserveAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        editBar = buildEditBar();
        root.addView(editBar);
        return root;
    }

    private TextView makeChip(View.OnClickListener click) {
        TextView chip = new TextView(this);
        chip.setTextSize(13);
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(click);
        return chip;
    }

    private void paintChips() {
        int online = 0;
        for (Row r : all) if (r.online) online++;
        chipOnline.setText("已上线·" + online);
        chipUpcoming.setText("待上线·" + (all.size() - online));
        paintChip(chipOnline, filter == FILTER_ONLINE);
        paintChip(chipUpcoming, filter == FILTER_UPCOMING);
    }

    private void paintChip(TextView chip, boolean on) {
        chip.setTextColor(on ? 0xFFFF7A1A : 0xFF333333);
        chip.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(on ? 0xFFFFF3E8 : 0xFFF5F5F5);
        chip.setBackground(bg);
    }

    private View buildEditBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(10));
        bar.setVisibility(View.GONE);
        editCount = new TextView(this);
        editCount.setTextColor(0xFF666666);
        editCount.setTextSize(13);
        bar.addView(editCount, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView del = new TextView(this);
        del.setText("取消预约");
        del.setTextColor(0xFFE5484D);
        del.setTextSize(14);
        del.setTypeface(null, Typeface.BOLD);
        del.setPadding(dp(6), dp(6), dp(6), dp(6));
        del.setOnClickListener(v -> cancelSelected());
        bar.addView(del);
        return bar;
    }

    private void setEditing(boolean on) {
        editing = on;
        if (!on) selected.clear();
        editBtn.setText(on ? "完成" : "编辑");
        editBar.setVisibility(on ? View.VISIBLE : View.GONE);
        updateEditCount();
        adapter.notifyDataSetChanged();
    }

    private void updateEditCount() {
        editCount.setText(selected.isEmpty() ? "未选择" : "已选 " + selected.size() + " 部");
    }

    private void cancelSelected() {
        if (selected.isEmpty()) return;
        for (String id : new ArrayList<>(selected)) {
            if (HgStore.isReserved(id)) {
                HgFeedSession.get().remoteInteractions.toggleReserve(id);
            } else {
                HgStore.setReserved(id, false);
            }
        }
        selected.clear();
        updateEditCount();
        loadLocal();
    }

    private void loadLocal() {
        all.clear();
        for (String id : HgStore.reservedIds()) {
            String[] meta = HgStore.metaOf(id);
            if (meta == null) continue;
            Row r = new Row();
            r.id = id;
            r.title = meta[0];
            r.cover = meta[1];
            try {
                r.count = Integer.parseInt(meta[2]);
            } catch (Exception ignored) {
                r.count = -1;
            }
            r.online = r.count > 0;
            all.add(r);
        }
        render();
    }

    private void refreshFromServer() {
        HgFeedSession.get().client.fetchMyReservations(new HgCallback<List<HgSeriesItem>>() {
            @Override
            public void onSuccess(List<HgSeriesItem> list) {
                runOnUiThread(() -> {
                    if (list == null || list.isEmpty()) return;
                    all.clear();
                    for (HgSeriesItem s : list) {
                        if (s.getSeriesId() == null) continue;
                        HgStore.setReserved(s.getSeriesId(), true);
                        HgStore.putMeta(s.getSeriesId(), s.getTitle(), s.getCoverUrl(),
                                s.getEpisodeCount(), s.getBadge());
                        Row r = new Row();
                        r.id = s.getSeriesId();
                        r.title = s.getTitle();
                        r.cover = s.getCoverUrl();
                        r.desc = s.getDesc();
                        r.count = s.getEpisodeCount();
                        r.online = r.count > 0;
                        r.dueText = s.getDueStartText();
                        if (s.getTags() != null && !s.getTags().isEmpty()) {
                            r.tagsText = String.join(" · ", s.getTags());
                        }
                        all.add(r);
                    }
                    render();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 本地镜像已先显，失败不打扰
            }
        });
    }

    private void render() {
        paintChips();
        List<Row> shown = new ArrayList<>();
        for (Row r : all) {
            if (filter == FILTER_ONLINE && r.online) shown.add(r);
            if (filter == FILTER_UPCOMING && !r.online) shown.add(r);
        }
        adapter.setRows(shown);
        emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (shown.isEmpty()) {
            emptyView.setText(all.isEmpty()
                    ? (LoginManager.isHongguoLoggedIn() ? "还没有预约的剧" : "登录后这里显示你预约的剧")
                    : (filter == FILTER_ONLINE ? "还没有已上线的预约" : "没有待上线的预约"));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class ReserveAdapter extends RecyclerView.Adapter<ReserveAdapter.Holder> {

        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> next) {
            rows = next;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(HongguoReserveActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView cover = new ImageView(HongguoReserveActivity.this);
            GradientDrawable round = new GradientDrawable();
            round.setColor(0xFFF5F5F5);
            round.setCornerRadius(dp(6));
            cover.setBackground(round);
            cover.setClipToOutline(true);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(cover, new LinearLayout.LayoutParams(dp(64), dp(80)));

            LinearLayout mid = new LinearLayout(HongguoReserveActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), dp(2), dp(8), 0);
            TextView title = new TextView(HongguoReserveActivity.this);
            title.setTextSize(15);
            title.setTextColor(0xFF000000);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(title);
            TextView meta = new TextView(HongguoReserveActivity.this);
            meta.setTextSize(12);
            meta.setTextColor(0xFF888888);
            meta.setMaxLines(1);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(4);
            mid.addView(meta, mlp);
            TextView desc = new TextView(HongguoReserveActivity.this);
            desc.setTextSize(12);
            desc.setTextColor(0xFF888888);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(4);
            mid.addView(desc, dlp);
            row.addView(mid, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            LinearLayout right = new LinearLayout(HongguoReserveActivity.this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView action = new TextView(HongguoReserveActivity.this);
            action.setTextSize(13);
            action.setPadding(dp(12), dp(6), dp(12), dp(6));
            right.addView(action);
            ImageView check = new ImageView(HongguoReserveActivity.this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(22), dp(22));
            clp.topMargin = dp(8);
            right.addView(check, clp);
            row.addView(right);
            return new Holder(row, cover, title, meta, desc, action, check);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row r = rows.get(position);
            ImgUtil.load(r.title == null ? "" : r.title, r.cover, h.cover);
            h.title.setText(r.title == null ? "" : r.title);
            StringBuilder meta = new StringBuilder();
            if (r.tagsText != null) meta.append(r.tagsText);
            if (r.dueText != null && !r.dueText.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(r.dueText);
            }
            h.meta.setText(meta.toString());
            h.desc.setText(r.desc == null ? "" : r.desc);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setCornerRadius(dp(14));
            if (r.online) {
                btnBg.setColor(0xFFFFF3E8);
                h.action.setText("去看剧");
                h.action.setTextColor(0xFFFF7A1A);
            } else {
                btnBg.setColor(0xFFF5F5F5);
                h.action.setText("已预约");
                h.action.setTextColor(0xFF999999);
            }
            h.action.setBackground(btnBg);
            h.check.setVisibility(editing ? View.VISIBLE : View.GONE);
            if (editing) {
                h.check.setImageResource(selected.contains(r.id)
                        ? R.drawable.ic_hg_check_on : R.drawable.bg_hg_check_off);
            }
            View.OnClickListener open = v -> {
                if (editing) {
                    if (!selected.remove(r.id)) selected.add(r.id);
                    updateEditCount();
                    notifyItemChanged(h.getBindingAdapterPosition());
                } else if (r.online) {
                    HongguoFeedActivity.startSeries(HongguoReserveActivity.this,
                            r.id, r.title, r.cover);
                }
            };
            h.itemView.setOnClickListener(open);
            h.action.setOnClickListener(open);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView meta;
            final TextView desc;
            final TextView action;
            final ImageView check;

            Holder(View itemView, ImageView cover, TextView title, TextView meta,
                   TextView desc, TextView action, ImageView check) {
                super(itemView);
                this.cover = cover;
                this.title = title;
                this.meta = meta;
                this.desc = desc;
                this.action = action;
                this.check = check;
            }
        }
    }
}
