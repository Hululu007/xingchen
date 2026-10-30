package com.fongmi.android.tv.hongguo.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaCache;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的页（2026-10-30 从零重建）：入口在首页抽屉顶部一行（他亲定）。
 * 只放三格——观看历史/我的收藏（本地 HgStore）/小说历史（小说线既有
 * 本地口径）——加两件必要设置：刷新设备档案、清红果缓存。无登录、
 * 无账号区。点历史/收藏条目带断点回竖屏流；点小说条目进小说详情。
 *
 * <p>历史/收藏两页的呈现（2026-10-30 海报格批，照官方实图 113705/
 * 113706）：白底、三列 3:4 海报格（圆角、右上类型角标、海报下剧名
 * 一行＋灰字子行）、顶行筹单选过滤（历史＝全部/已看完/未看完，
 * 收藏＝全部/真人剧/漫剧/AI剧）、右上「编辑」进独立编辑页
 * （HongguoMineEditActivity），长按任意一格同落点且该格预勾。
 * 小说历史维持原列表行与深底，不在本批范围。</p>
 */
public class HongguoMineActivity extends AppCompatActivity {

    private static final int MODE_HOME = 0;
    static final int MODE_HISTORY = 1;
    static final int MODE_FAV = 2;
    private static final int MODE_NOVEL = 3;

    /** 直达入口（定稿：历史页/收藏页是两个独立页）：带此附加打开即落列表、返回即退页。 */
    public static final String EXTRA_INITIAL = "hg_mine_initial";

    private static final int REQ_EDIT = 41;

    public static void startHistory(android.content.Context context) {
        android.content.Intent intent = new android.content.Intent(context, HongguoMineActivity.class);
        intent.putExtra(EXTRA_INITIAL, MODE_HISTORY);
        HgTransition.push(context, intent);
    }

    public static void startFav(android.content.Context context) {
        android.content.Intent intent = new android.content.Intent(context, HongguoMineActivity.class);
        intent.putExtra(EXTRA_INITIAL, MODE_FAV);
        HgTransition.push(context, intent);
    }

    @Override
    public void finish() {
        super.finish();
        HgTransition.applyPushExit(this);
    }

    private LinearLayout root;
    private TextView backBtn;
    private TextView titleText;
    private LinearLayout homeBox;
    private LinearLayout chipsBar;
    private TextView editBtn;
    private RecyclerView list;
    private RowAdapter adapter;
    private GridAdapter gridAdapter;
    private TextView emptyText;
    private int mode = MODE_HOME;
    /** 直达列表模式（抽屉两行进入）：返回直接退页，不回「我的」格。 */
    private boolean directList;
    private final List<Row> rows = new ArrayList<>();
    /** 海报格全量行（未过滤）与当前过滤下标（历史/收藏共用一套筹机制）。 */
    private final List<GridRow> allRows = new ArrayList<>();
    private final List<GridRow> viewRows = new ArrayList<>();
    private final List<TextView> chipViews = new ArrayList<>();
    private int filterIndex = 0;

    private static final class Row {
        String id;
        String title;
        String sub;
        String cover;
    }

    /** 海报格一行：数据全走本地 HgStore；badge/type 本地没存就不显不分档。 */
    private static final class GridRow {
        String id;
        String title;
        String sub;
        String cover;
        String badge;
        String typeCode;
        boolean finished;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HgWindowStyle.apply(this, false, false);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111114);
        root.setPadding(0, 0, 0, navBarHeight());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), statusBarHeight() + dp(6), dp(16), dp(8));
        backBtn = new TextView(this);
        backBtn.setText("‹");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(26);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setOnClickListener(v -> onBackRow());
        top.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        titleText = new TextView(this);
        titleText.setText("我的");
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(18);
        titleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(titleText);
        root.addView(top);

        chipsBar = new LinearLayout(this);
        chipsBar.setOrientation(LinearLayout.HORIZONTAL);
        chipsBar.setGravity(Gravity.CENTER_VERTICAL);
        chipsBar.setPadding(dp(16), dp(8), dp(16), dp(2));
        chipsBar.setVisibility(View.GONE);
        root.addView(chipsBar);

        homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setPadding(dp(16), dp(6), dp(16), 0);
        homeBox.addView(entryRow("观看历史", v -> HgTransition.fadeSwap(
                () -> openList(MODE_HISTORY, "观看历史"), homeBox, list, emptyText)));
        homeBox.addView(entryRow("我的收藏", v -> HgTransition.fadeSwap(
                () -> openList(MODE_FAV, "我的收藏"), homeBox, list, emptyText)));
        homeBox.addView(entryRow("小说历史", v -> HgTransition.fadeSwap(
                () -> openList(MODE_NOVEL, "小说历史"), homeBox, list, emptyText)));
        TextView settingsLabel = new TextView(this);
        settingsLabel.setText("设置");
        settingsLabel.setTextColor(0xFF8E8E96);
        settingsLabel.setTextSize(12.5f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(22);
        slp.bottomMargin = dp(2);
        homeBox.addView(settingsLabel, slp);
        homeBox.addView(entryRow("刷新设备档案", v -> refreshDevice()));
        homeBox.addView(entryRow("清红果缓存", v -> {
            DramaCache.clear();
            Toast.makeText(this, "缓存已清", Toast.LENGTH_SHORT).show();
        }));
        root.addView(homeBox);

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter();
        gridAdapter = new GridAdapter();
        list.setAdapter(adapter);
        list.setVisibility(View.GONE);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        emptyText = new TextView(this);
        emptyText.setTextColor(0xFF9E9EA7);
        emptyText.setTextSize(13.5f);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        int initial = getIntent().getIntExtra(EXTRA_INITIAL, MODE_HOME);
        if (initial == MODE_HISTORY) {
            directList = true;
            openList(MODE_HISTORY, "观看历史");
        } else if (initial == MODE_FAV) {
            directList = true;
            openList(MODE_FAV, "我的收藏");
        }
    }

    private View entryRow(String label, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(15), dp(2), dp(15));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFF2F2F4);
        t.setTextSize(15);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(0xFF6E6E76);
        arrow.setTextSize(17);
        row.addView(arrow);
        row.setOnClickListener(click);
        return row;
    }

    private void onBackRow() {
        if (mode != MODE_HOME && directList) {
            finish();
        } else if (mode != MODE_HOME) {
            mode = MODE_HOME;
            titleText.setText("我的");
            HgTransition.fadeSwap(() -> {
                applyChrome(false);
                chipsBar.setVisibility(View.GONE);
                list.setVisibility(View.GONE);
                emptyText.setVisibility(View.GONE);
                homeBox.setVisibility(View.VISIBLE);
            }, homeBox, list, emptyText);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (mode != MODE_HOME) onBackRow();
        else super.onBackPressed();
    }

    /** 页面明暗切换：历史/收藏＝官方白底黑字＋深色系统栏图标；其余维持深底。 */
    private void applyChrome(boolean light) {
        root.setBackgroundColor(light ? 0xFFFFFFFF : 0xFF111114);
        int fg = light ? 0xFF1B1B1E : Color.WHITE;
        backBtn.setTextColor(fg);
        titleText.setTextColor(fg);
        emptyText.setTextColor(light ? 0xFF8E8E96 : 0xFF9E9EA7);
        HgWindowStyle.apply(this, light, light);
    }

    private void openList(int target, String title) {
        mode = target;
        titleText.setText(title);
        homeBox.setVisibility(View.GONE);
        if (target == MODE_NOVEL) {
            applyChrome(false);
            chipsBar.setVisibility(View.GONE);
            rows.clear();
            for (String id : HgStore.novelHistoryIds()) {
                String[] meta = HgStore.novelMetaOf(id);
                Row row = new Row();
                row.id = id;
                row.title = meta == null ? "" : meta[0];
                row.sub = meta == null ? "" : meta[1];
                row.cover = meta == null ? null : meta[2];
                rows.add(row);
            }
            list.setPadding(0, 0, 0, 0);
            list.setLayoutManager(new LinearLayoutManager(this));
            list.setAdapter(adapter);
            adapter.notifyDataSetChanged();
            list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
            emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            if (rows.isEmpty()) emptyText.setText("还没有小说记录");
            return;
        }
        // 历史/收藏：白底海报格＋筹行
        applyChrome(true);
        filterIndex = 0;
        loadGridRows(target);
        buildChips(target);
        chipsBar.setVisibility(View.VISIBLE);
        list.setPadding(dp(12), dp(6), dp(12), dp(16));
        list.setLayoutManager(new GridLayoutManager(this, 3));
        list.setAdapter(gridAdapter);
        applyFilter();
    }

    // ---------- 海报格（历史/收藏） ----------

    private void loadGridRows(int target) {
        allRows.clear();
        if (target == MODE_HISTORY) {
            for (HgStore.HistoryRecord r : HgStore.historyRecords()) {
                GridRow row = new GridRow();
                row.id = r.seriesId;
                row.title = r.title == null ? "" : r.title;
                row.cover = r.cover;
                row.typeCode = HgStore.typeOf(r.seriesId);
                row.badge = HgStore.badgeTextOf(row.typeCode);
                int ep = Math.max(1, r.episodeIndex);
                row.finished = r.episodeCount > 0 && r.episodeIndex >= r.episodeCount;
                row.sub = progressText(ep, r.episodeCount);
                allRows.add(row);
            }
        } else {
            for (String id : HgStore.favedIds()) {
                String[] meta = HgStore.metaOf(id);
                GridRow row = new GridRow();
                row.id = id;
                row.title = meta == null ? "" : meta[0];
                row.cover = meta == null ? null : meta[1];
                int count = -1;
                if (meta != null) {
                    try {
                        count = Integer.parseInt(meta[2]);
                    } catch (Exception ignored) {
                    }
                }
                row.typeCode = HgStore.typeOf(id);
                row.badge = HgStore.badgeTextOf(row.typeCode);
                HgStore.Progress p = HgStore.progressOf(id);
                if (p != null) {
                    row.sub = progressText(Math.max(1, p.episodeIndex), count);
                } else {
                    row.sub = count > 0 ? "共" + count + "集" : "";
                }
                allRows.add(row);
            }
        }
    }

    /** 子行进度文案（官方格口径「N集/M集」）；总数未知只写看到哪集。 */
    private static String progressText(int ep, int total) {
        return total > 0 ? ep + "集/" + total + "集" : "看到第" + ep + "集";
    }

    private void buildChips(int target) {
        chipsBar.removeAllViews();
        chipViews.clear();
        String[] labels = target == MODE_HISTORY
                ? new String[]{"全部", "已看完", "未看完"}
                : new String[]{"全部", "真人剧", "漫剧", "AI剧"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(13.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(13), dp(6), dp(13), dp(6));
            chip.setOnClickListener(v -> {
                if (filterIndex == index) return;
                filterIndex = index;
                styleChips();
                HgTransition.fadeSwap(this::applyFilter, list, emptyText);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = dp(8);
            chipsBar.addView(chip, lp);
            chipViews.add(chip);
        }
        View spacer = new View(this);
        chipsBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        editBtn = new TextView(this);
        editBtn.setText("编辑");
        editBtn.setTextColor(0xFF1B1B1E);
        editBtn.setTextSize(14.5f);
        editBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        editBtn.setGravity(Gravity.CENTER);
        editBtn.setPadding(dp(10), dp(6), dp(2), dp(6));
        editBtn.setOnClickListener(v -> openEditPage(null));
        chipsBar.addView(editBtn);
        styleChips();
    }

    private void styleChips() {
        for (int i = 0; i < chipViews.size(); i++) {
            TextView chip = chipViews.get(i);
            boolean on = i == filterIndex;
            chip.setBackground(roundBg(on ? 0xFFFFF0DB : 0xFFF4F4F5, dp(16)));
            chip.setTextColor(on ? 0xFFF07D13 : 0xFF3A3A3E);
            chip.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
        }
        if (editBtn != null) {
            editBtn.setVisibility(allRows.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void applyFilter() {
        viewRows.clear();
        for (GridRow row : allRows) {
            if (mode == MODE_HISTORY) {
                if (filterIndex == 1 && !row.finished) continue;
                if (filterIndex == 2 && row.finished) continue;
            } else if (mode == MODE_FAV && filterIndex > 0) {
                String want = filterIndex == 1 ? HgStore.TYPE_LIVE
                        : filterIndex == 2 ? HgStore.TYPE_COMIC : HgStore.TYPE_AI;
                if (!want.equals(row.typeCode)) continue;
            }
            viewRows.add(row);
        }
        gridAdapter.notifyDataSetChanged();
        list.setVisibility(viewRows.isEmpty() ? View.GONE : View.VISIBLE);
        emptyText.setVisibility(viewRows.isEmpty() ? View.VISIBLE : View.GONE);
        if (viewRows.isEmpty()) {
            emptyText.setText(mode == MODE_HISTORY ? "暂无历史" : "暂无收藏");
        }
    }

    /** 进独立编辑页（点「编辑」与长按一格同一个落点，长按的那格预勾）。 */
    private void openEditPage(@Nullable String preselectId) {
        Intent intent = new Intent(this, HongguoMineEditActivity.class);
        intent.putExtra(HongguoMineEditActivity.EXTRA_MODE, mode);
        if (preselectId != null) {
            intent.putExtra(HongguoMineEditActivity.EXTRA_PRESELECT, preselectId);
        }
        startActivityForResult(intent, REQ_EDIT);
        overridePendingTransition(R.anim.hg_push_in, R.anim.hg_hold);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 编辑页删完回来：本地记录已变，整表按现行过滤重读（删的要消失）
        if (requestCode == REQ_EDIT && (mode == MODE_HISTORY || mode == MODE_FAV)) {
            loadGridRows(mode);
            styleChips();
            applyFilter();
        }
    }

    private void refreshDevice() {
        HgApiClient.getInstance().resetDeviceProfile(new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                runOnUiThread(() -> Toast.makeText(HongguoMineActivity.this,
                        "设备档案已刷新", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> Toast.makeText(HongguoMineActivity.this,
                        "刷新失败，请重试", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void openRow(Row row) {
        Intent intent = new Intent(this, HongguoNovelDetailActivity.class);
        intent.putExtra(HongguoNovelDetailActivity.EXTRA_BOOK_ID, row.id);
        intent.putExtra(HongguoNovelDetailActivity.EXTRA_TITLE, row.title);
        HgTransition.push(this, intent);
    }

    private void openGridRow(GridRow row) {
        HgStore.Progress p = mode == MODE_HISTORY ? HgStore.progressOf(row.id) : null;
        int ep = p == null ? 0 : p.episodeIndex;
        HongguoFeedActivity.startSeries(this, row.id, row.title, ep);
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** 海报格适配器：三列 3:4、等比裁切、右上角标、海报下剧名＋灰字子行。 */
    private class GridAdapter extends RecyclerView.Adapter<GridAdapter.H> {

        private final int posterW;
        private final int posterH;

        GridAdapter() {
            posterW = (getResources().getDisplayMetrics().widthPixels - dp(48)) / 3;
            posterH = posterW * 4 / 3;
        }

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView badge;
            final TextView title;
            final TextView sub;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                badge = cell.findViewWithTag("badge");
                title = cell.findViewWithTag("title");
                sub = cell.findViewWithTag("sub");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoMineActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(4), dp(6), dp(4), dp(8));
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout posterBox = new FrameLayout(HongguoMineActivity.this);
            posterBox.setBackground(roundBg(0xFFEDEDEF, dp(8)));
            ImageView cover = new ImageView(HongguoMineActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            posterBox.addView(cover, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = new TextView(HongguoMineActivity.this);
            badge.setTag("badge");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(10);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            badge.setBackground(roundBg(0x8C000000, dp(4)));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
            blp.gravity = Gravity.TOP | Gravity.END;
            blp.topMargin = dp(5);
            blp.rightMargin = dp(5);
            posterBox.addView(badge, blp);
            cell.addView(posterBox, new LinearLayout.LayoutParams(posterW, posterH));
            TextView title = new TextView(HongguoMineActivity.this);
            title.setTag("title");
            title.setTextColor(0xFF1B1B1E);
            title.setTextSize(14);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(6);
            cell.addView(title, tlp);
            TextView sub = new TextView(HongguoMineActivity.this);
            sub.setTag("sub");
            sub.setTextColor(0xFF9B9BA1);
            sub.setTextSize(11.5f);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(-1, -2);
            slp2.topMargin = dp(2);
            cell.addView(sub, slp2);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            GridRow row = viewRows.get(position);
            if (row.cover != null && !row.cover.isEmpty()) {
                Glide.with(HongguoMineActivity.this).load(row.cover)
                        .apply(RequestOptions.bitmapTransform(new RoundedCorners(dp(8))))
                        .into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            if (row.badge != null && !row.badge.isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(row.badge);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            h.title.setText(row.title);
            h.sub.setText(row.sub == null ? "" : row.sub);
            h.itemView.setOnClickListener(v -> openGridRow(row));
            h.itemView.setOnLongClickListener(v -> {
                openEditPage(row.id);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return viewRows.size();
        }
    }

    private class RowAdapter extends RecyclerView.Adapter<RowAdapter.H> {

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView sub;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                title = cell.findViewWithTag("title");
                sub = cell.findViewWithTag("sub");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoMineActivity.this);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(16), dp(9), dp(16), dp(9));
            ImageView cover = new ImageView(HongguoMineActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0xFF232328, dp(6)));
            cell.addView(cover, new LinearLayout.LayoutParams(dp(46), dp(62)));
            LinearLayout texts = new LinearLayout(HongguoMineActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(HongguoMineActivity.this);
            title.setTag("title");
            title.setTextColor(0xFFF2F2F4);
            title.setTextSize(14.5f);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(title);
            TextView sub = new TextView(HongguoMineActivity.this);
            sub.setTag("sub");
            sub.setTextColor(0xFF8E8E96);
            sub.setTextSize(12);
            sub.setMaxLines(1);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = dp(4);
            texts.addView(sub, slp);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
            tlp.leftMargin = dp(12);
            cell.addView(texts, tlp);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            Row row = rows.get(position);
            if (row.cover != null && !row.cover.isEmpty()) {
                Glide.with(HongguoMineActivity.this).load(row.cover).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            h.title.setText(row.title);
            h.sub.setText(row.sub == null ? "" : row.sub);
            h.itemView.setOnClickListener(v -> openRow(row));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
