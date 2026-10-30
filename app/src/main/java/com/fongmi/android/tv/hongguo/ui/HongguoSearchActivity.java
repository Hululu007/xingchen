package com.fongmi.android.tv.hongguo.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.book.HgBookClient;
import com.fongmi.android.tv.hongguo.drama.DramaAuthorHit;
import com.fongmi.android.tv.hongguo.drama.DramaCellPage;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 搜索页（2026-10-30 从零重建，简单口径）：标题栏＋搜索框＋取消，
 * 结果三签——短剧（新层搜索口）／小说（小说线既有 HgBookClient，
 * 进小说详情）／用户（进作者页）；落地态只显搜索历史。一页一件事。
 */
public class HongguoSearchActivity extends AppCompatActivity {

    private static final int TAB_DRAMA = 0;
    private static final int TAB_NOVEL = 1;
    private static final int TAB_USER = 2;

    private EditText input;
    private LinearLayout historyBox;
    private LinearLayout tabRow;
    private TextView[] tabViews;
    private RecyclerView list;
    private ResultAdapter adapter;
    private TextView stateText;

    private final List<Object> rows = new ArrayList<>();
    private String keyword = "";
    private int tab = TAB_DRAMA;
    private boolean loading;
    private boolean hasMore;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hg-search");
        t.setDaemon(true);
        return t;
    });

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

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111114);
        root.setPadding(0, statusBarHeight(), 0, navBarHeight());

        // 搜索栏：返回＋框＋搜索
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_drama_back);
        back.setColorFilter(Color.WHITE);
        back.setPadding(dp(6), dp(8), dp(10), dp(8));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        input = new EditText(this);
        input.setHint("搜剧名、作者…");
        input.setHintTextColor(0xFF77777F);
        input.setTextColor(Color.WHITE);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(roundBg(0xFF232328, dp(19)));
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, dp(38), 1f));
        TextView go = new TextView(this);
        go.setText("搜索");
        go.setTextColor(0xFFFF5A4E);
        go.setTextSize(15.5f);
        go.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        go.setPadding(dp(12), dp(8), dp(2), dp(8));
        go.setOnClickListener(v -> doSearch());
        bar.addView(go);
        root.addView(bar);

        // 页签（有结果后显）
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(12), 0, dp(12), dp(4));
        tabRow.setVisibility(View.GONE);
        String[] names = {"短剧", "小说", "用户"};
        tabViews = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(14.5f);
            t.setPadding(dp(12), dp(6), dp(12), dp(6));
            t.setOnClickListener(v -> {
                if (tab != idx) {
                    tab = idx;
                    styleTabs();
                    runSearch(true);
                }
            });
            tabViews[i] = t;
            tabRow.addView(t);
        }
        root.addView(tabRow);

        // 历史
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(historyBox);
        bindHistory();

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultAdapter();
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && dy > 0 && hasMore && !loading
                        && lm.findLastVisibleItemPosition() >= rows.size() - 3) {
                    runSearch(false);
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        stateText = new TextView(this);
        stateText.setTextColor(0xFF9E9EA7);
        stateText.setTextSize(13.5f);
        stateText.setGravity(Gravity.CENTER);
        stateText.setPadding(0, dp(14), 0, dp(14));
        stateText.setVisibility(View.GONE);
        root.addView(stateText);

        setContentView(root);
        input.requestFocus();
    }

    private void styleTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == tab;
            tabViews[i].setTextColor(on ? Color.WHITE : 0xFF9E9EA7);
            tabViews[i].setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            tabViews[i].setBackground(roundBg(on ? 0xFF2C2C30 : Color.TRANSPARENT, dp(13)));
        }
    }

    private void bindHistory() {
        historyBox.removeAllViews();
        List<String> history = HgStore.searchHistory();
        if (history.isEmpty()) {
            historyBox.setVisibility(View.GONE);
            return;
        }
        historyBox.setVisibility(View.VISIBLE);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("搜索历史");
        label.setTextColor(0xFF9E9EA7);
        label.setTextSize(13);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextColor(0xFF77777F);
        clear.setTextSize(12.5f);
        clear.setOnClickListener(v -> {
            HgStore.clearSearchHistory();
            bindHistory();
        });
        head.addView(clear);
        historyBox.addView(head);
        // 词条两列流式排布（简单行列）
        LinearLayout row = null;
        for (int i = 0; i < history.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = dp(8);
                historyBox.addView(row, rlp);
            }
            final String word = history.get(i);
            TextView chip = new TextView(this);
            chip.setText(word);
            chip.setTextColor(0xFFE8E8EA);
            chip.setTextSize(13);
            chip.setMaxLines(1);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setBackground(roundBg(0xFF232328, dp(14)));
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setOnClickListener(v -> {
                input.setText(word);
                input.setSelection(word.length());
                doSearch();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            row.addView(chip, lp);
        }
    }

    private void doSearch() {
        String kw = input.getText() == null ? "" : input.getText().toString().trim();
        if (kw.isEmpty()) return;
        keyword = kw;
        HgStore.addSearchHistory(kw);
        bindHistory();
        historyBox.setVisibility(View.GONE);
        tabRow.setVisibility(View.VISIBLE);
        styleTabs();
        runSearch(true);
    }

    private void runSearch(boolean first) {
        if (loading || keyword.isEmpty()) return;
        if (first) {
            rows.clear();
            adapter.notifyDataSetChanged();
            hasMore = false;
            stateText.setText("搜索中…");
            stateText.setVisibility(View.VISIBLE);
        }
        loading = true;
        if (tab == TAB_DRAMA) {
            DramaClient.searchTab(keyword, rows.size(), 11, new HgCallback<DramaCellPage>() {
                @Override
                public void onSuccess(DramaCellPage page) {
                    runOnUiThread(() -> {
                        loading = false;
                        for (DramaItem it : page.getItems()) {
                            if (it.getSeriesId() != null) rows.add(it);
                        }
                        hasMore = page.isHasMore();
                        afterRows();
                    });
                }

                @Override
                public void onError(int code, String msg) {
                    runOnUiThread(() -> {
                        loading = false;
                        afterRows();
                    });
                }
            });
        } else if (tab == TAB_NOVEL) {
            final int offset = rows.size();
            worker.execute(() -> {
                List<HgBookClient.Entry> found;
                try {
                    found = new HgBookClient().search(false, keyword, offset);
                } catch (Exception e) {
                    found = null;
                }
                final List<HgBookClient.Entry> result = found;
                runOnUiThread(() -> {
                    loading = false;
                    if (result != null) {
                        rows.addAll(result);
                        hasMore = result.size() >= 20;
                    }
                    afterRows();
                });
            });
        } else {
            DramaClient.searchUsers(keyword, new HgCallback<List<DramaAuthorHit>>() {
                @Override
                public void onSuccess(List<DramaAuthorHit> hits) {
                    runOnUiThread(() -> {
                        loading = false;
                        rows.addAll(hits);
                        hasMore = false;
                        afterRows();
                    });
                }

                @Override
                public void onError(int code, String msg) {
                    runOnUiThread(() -> {
                        loading = false;
                        afterRows();
                    });
                }
            });
        }
    }

    private void afterRows() {
        adapter.notifyDataSetChanged();
        stateText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (rows.isEmpty()) stateText.setText("没有找到相关内容");
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.H> {

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView meta;

            H(LinearLayout row) {
                super(row);
                cover = row.findViewWithTag("cover");
                title = row.findViewWithTag("title");
                meta = row.findViewWithTag("meta");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(HongguoSearchActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(9), dp(16), dp(9));
            ImageView cover = new ImageView(HongguoSearchActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0xFF232328, dp(6)));
            row.addView(cover, new LinearLayout.LayoutParams(dp(52), dp(70)));
            LinearLayout col = new LinearLayout(HongguoSearchActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            clp.leftMargin = dp(12);
            TextView title = new TextView(HongguoSearchActivity.this);
            title.setTag("title");
            title.setTextColor(0xFFF2F2F4);
            title.setTextSize(15);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(title);
            TextView meta = new TextView(HongguoSearchActivity.this);
            meta.setTag("meta");
            meta.setTextColor(0xFF8E8E96);
            meta.setTextSize(12);
            meta.setMaxLines(2);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
            mlp.topMargin = dp(4);
            col.addView(meta, mlp);
            row.addView(col, clp);
            return new H(row);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            Object o = rows.get(position);
            if (o instanceof DramaItem) {
                DramaItem item = (DramaItem) o;
                bindCover(h, item.getCoverUrl(), false);
                h.title.setText(item.getTitle() == null ? "" : item.getTitle());
                StringBuilder m = new StringBuilder();
                if (item.getAuthorName() != null) m.append(item.getAuthorName());
                if (item.getEpisodeCount() > 0) {
                    if (m.length() > 0) m.append(" · ");
                    m.append(item.isFinished() ? "全" + item.getEpisodeCount() + "集"
                            : "更新至" + item.getEpisodeCount() + "集");
                }
                h.meta.setText(m.toString());
                h.itemView.setOnClickListener(v -> HongguoDetailActivity.start(
                        HongguoSearchActivity.this, item.getSeriesId(), item.getTitle(),
                        item.isMultiSeason() ? item.getAlbumId() : null));
            } else if (o instanceof HgBookClient.Entry) {
                HgBookClient.Entry e = (HgBookClient.Entry) o;
                bindCover(h, e.coverUrl, false);
                h.title.setText(e.title == null ? "" : e.title);
                h.meta.setText(e.author == null ? "" : e.author);
                h.itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(HongguoSearchActivity.this,
                            com.fongmi.android.tv.hongguo.ui.HongguoNovelDetailActivity.class);
                    intent.putExtra(com.fongmi.android.tv.hongguo.ui.HongguoNovelDetailActivity.EXTRA_BOOK_ID, e.bookId);
                    intent.putExtra(com.fongmi.android.tv.hongguo.ui.HongguoNovelDetailActivity.EXTRA_TITLE, e.title);
                    startActivity(intent);
                });
            } else if (o instanceof DramaAuthorHit) {
                DramaAuthorHit hit = (DramaAuthorHit) o;
                bindCover(h, hit.avatar, true);
                h.title.setText(hit.name == null ? "" : hit.name);
                h.meta.setText(hit.workCount > 0 ? hit.workCount + " 部作品" : "");
                h.itemView.setOnClickListener(v -> HongguoAuthorActivity.start(
                        HongguoSearchActivity.this, hit.uid, hit.name, hit.avatar));
            }
        }

        private void bindCover(H h, String url, boolean circle) {
            if (url == null || url.isEmpty()) {
                h.cover.setImageDrawable(null);
                return;
            }
            if (circle) Glide.with(HongguoSearchActivity.this).load(url).circleCrop().into(h.cover);
            else Glide.with(HongguoSearchActivity.this).load(url)
                    .transform(new RoundedCorners(dp(6))).into(h.cover);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
