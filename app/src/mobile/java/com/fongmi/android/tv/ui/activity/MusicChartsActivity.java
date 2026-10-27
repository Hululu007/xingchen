package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.ui.music.MusicGlassWire;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.KwChartSource;
import com.fongmi.android.tv.api.music.LxPlatformSearch;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.MusicBaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.XcFadeTransition;

import java.util.ArrayList;
import java.util.List;

/**
 * 榜单列表独立页（拆页 p5）：焦点卡＋分区头＋普通卡；
 * 数据源＝MusicRepository 榜单（只留酷我＋QQ 的站点），卡内预览逐榜拉取，
 * 卡面统一玻璃本色、不跟封面染色（2026-10-26 收口，见 neutralChartCard）；
 * 点卡进榜单详情独立页 MusicChartDetailActivity。
 * 顶栏无返回箭头，走系统返回。2026-10-26 他亲点：计数行/刷新钮/排序箭头整带已删，
 * 本页只在进页时加载一次（无任何刷新入口），标题行保持他截图的样子不增不减。
 */
public class MusicChartsActivity extends MusicBaseActivity {

    /** 布局壳（BaseActivity 要求 getBinding；本页不用生成 Binding，视图一律 findViewById）。 */
    private static final class SimpleBinding implements ViewBinding {
        private final View root;

        SimpleBinding(View root) {
            this.root = root;
        }

        @Override
        public View getRoot() {
            return root;
        }
    }

    private View rootView;
    private RecyclerView recycler;
    private boolean listIntroDone;

    /** 列表首屏：数据第一次到达时整表淡入 200ms（统一口径），后续刷新不再重播。 */
    private void fadeListInOnce() {
        if (listIntroDone || recycler == null) return;
        listIntroDone = true;
        recycler.setAlpha(0f);
        XcFadeTransition.fadeIn(recycler);
    }
    private TextView empty;

    private final List<MusicChart> charts = new ArrayList<>();
    private ChartsAdapter adapter;
    private final java.util.Map<String, List<MusicTrack>> chartPreviews = new java.util.HashMap<>();
    private android.os.Parcelable pendingListState;
    /** loadCharts 轮次守卫：刷新入口已按他令删净、本页只进页加载，但进页加载本身
     * 仍可能与重建/重入并发，旧轮的主结果与预览回调一律作废，不许覆写新轮。 */
    private int chartsSeq;

    public static void startCharts(Activity activity) {
        activity.startActivity(new Intent(activity, MusicChartsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        // 只 inflate 一次：BaseActivity 先取根 setContentView、initView 再取根走 glassifyTree，
        // 两次必须是同一棵树——重复 inflate 会让接线落在游离树上、显示的那棵永不生效。
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_charts, null);
        return new SimpleBinding(rootView);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicGlassWire.glassifyTree(getBinding().getRoot()); // 全页玻璃件同源接线：共用件按资源识别统一换液态玻璃（MusicGlassWire）
        // 与旧列表页同口径：锁 adjustPan，键盘不缩放窗口、不带乱空态位置。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        if (savedInstanceState != null) {
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);

        // 顶栏搜索钮照旧落点：壳搜索模式（搜索页尚未拆）
        ImageView btnSearch = findViewById(R.id.btnSearch);
        btnSearch.setOnClickListener(v -> MusicSearchActivity.startSearch(this, ""));
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new ChartsAdapter());
        loadCharts();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            if (recycler != null && recycler.getLayoutManager() != null) {
                outState.putParcelable("ml_listState",
                        recycler.getLayoutManager().onSaveInstanceState());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingListState != null && recycler != null) {
            final android.os.Parcelable state = pendingListState;
            pendingListState = null;
            recycler.post(() -> {
                try {
                    if (recycler != null && recycler.getLayoutManager() != null) {
                        recycler.getLayoutManager().onRestoreInstanceState(state);
                    }
                } catch (Throwable ignored) {
                }
            });
        }
        // 旧口径：榜单是网络模式，回页不重拉（onResume 的重拉只给本地数据模式）
    }

    @Override
    protected void onDestroy() {
        MusicController.get().unbind();
        super.onDestroy();
    }

    // ================= 数据加载 =================

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    /** 榜单只留酷我＋QQ（他定）：其它源（哔哩哔哩等）的榜一律不列，与首页榜单卡 QQ＞酷我口径一致。 */
    private static boolean isChartSite(MusicSite s) {
        if (s == null) return false;
        String tag = ((s.platform == null ? "" : s.platform) + (s.name == null ? "" : s.name)).toLowerCase();
        return tag.contains("qq") || tag.contains("tx") || tag.contains("kw") || tag.contains("酷我");
    }

    private void loadCharts() {
        final int seq = ++chartsSeq;
        showLoading();
        empty.setOnClickListener(null);
        Task.execute(() -> {
            List<MusicSite> sites = MusicRepository.sites();
            List<MusicChart> result = new ArrayList<>();
            boolean anyFailure = false;
            for (MusicSite s : sites) {
                if (!isChartSite(s)) continue;
                // 失败可辨版：任一源抛错回 null 记失败，页面层才能分失败与真空
                List<MusicChart> cs = MusicRepository.chartsOrNull(s);
                if (cs == null) {
                    anyFailure = true;
                    continue;
                }
                result.addAll(cs);
            }
            final boolean failed = anyFailure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != chartsSeq) return;
                charts.clear();
                charts.addAll(result);
                chartPreviews.clear();
                adapter.notifyDataSetChanged();
                fadeListInOnce();
                if (result.isEmpty()) {
                    XcFadeTransition.fadeIn(empty);
                    // 失败与真空分开说：有源抛错＝加载失败，没源抛错＝真没榜；
                    // 刷新入口已按他令删净，文案不许再许诺「点击重试/刷新」
                    empty.setText(failed ? "榜单加载失败" : "暂无榜单数据");
                } else {
                    XcFadeTransition.fadeOutThenGone(empty);
                }
                // 榜单条目 rawJson 同名字段映射（有源给值、无源留空；详情曲目数不冒充榜总曲数）
                for (MusicChart c : result) fillChartMetaFromRaw(c);
                // 详情图库预热：列表页一露面就从手机侧发请求把各酷我榜的详情图库热好
                //（必须手机侧发才热到用户那个 colo）；预热不阻塞、不解析结果，
                // 点进详情时多半已经热了；非酷我榜在路由层直接跳过
                for (int i = 0; i < result.size(); i++) {
                    MusicRepository.prewarmKwChart(result.get(i));
                }
                // 逐榜拉卡内预览（焦点 3 条、普通 2 条），到一条刷一条，轮次守卫防旧轮覆写；
                // 预览只取所需行数级别：酷我走 Worker 首片（40 首级、不拉整榜两片），
                // 其余源单发请求量由服务端定、页内截断只存前 3 条；失败的卡预览区留空，不编假数据
                for (int i = 0; i < result.size(); i++) {
                    final MusicChart c = result.get(i);
                    final int idx = i;
                    final String key = c.platform + ":" + c.id;
                    if (KwChartSource.PLATFORM.equals(c.platform)) {
                        MusicRepository.fetchKwChartCovers(c, (pn, shard) -> {
                            if (pn != 0) return;
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed() || seq != chartsSeq) return;
                                chartPreviews.put(key, previewOf(shard));
                                adapter.notifyItemChanged(idx == 0 ? 0 : idx + 1);
                            });
                        });
                    } else {
                        Task.execute(() -> {
                            List<MusicTrack> r = MusicRepository.chartTracks(c);
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed() || seq != chartsSeq) return;
                                chartPreviews.put(key, previewOf(r));
                                adapter.notifyItemChanged(idx == 0 ? 0 : idx + 1);
                            });
                        });
                    }
                }
            });
        });
    }

    /** 卡内预览只留前 3 条（焦点卡 3 条/普通卡 2 条的所需上限），整榜不进预览表。 */
    private static List<MusicTrack> previewOf(List<MusicTrack> r) {
        if (r == null || r.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(r.subList(0, Math.min(3, r.size())));
    }

    /** 榜单详情开新页（系统返回回榜单列表；本页不放自己的返回箭头）。
     * p5 先照旧 intent 打到壳，p6 起落点换新页 MusicChartDetailActivity（extras 契约不变）。 */
    private void openChartDetail(MusicChart chart) {
        MusicChartDetailActivity.startChartDetail(this, chart);
    }

    // ================= 取色与封面 =================

    /** 榜单卡面统一玻璃本色（2026-10-26 他亲点收口）：卡面不许跟封面染色——
     * 此前 tintChartCard 把 CoverColor.dominantColor 的主色向白混 85% 成近白淡染，
     * 以 tone 0.45 平铺进卡面（染色），背景不是玻璃时还整卡换成近白实底
     * GradientDrawable（变白），且该实底在复用绑定时从不清、串到下一张卡。
     * 染色链整条已摘：绑定时只做两件事——卡底还不是玻璃就按识别表补换玻璃
     * （Holder 构造已接一遍，这里是绑定口兜底，赶在上屏前）；已是玻璃就清 tone
     * 回本色。封面没到/取色失败没有任何卡面分支，回落恒为玻璃本色。
     * accent 文字与平台胶囊的语气色不是卡面染色，不在此列（仍照旧）。 */
    private void neutralChartCard(View cardRoot) {
        if (cardRoot == null) return;
        MusicGlassWire.glassifyView(cardRoot);
        if (cardRoot.getBackground() instanceof com.fongmi.android.tv.ui.music.MusicFrostDrawable) {
            ((com.fongmi.android.tv.ui.music.MusicFrostDrawable) cardRoot.getBackground()).setToneColor(0);
        }
    }

    /** 榜单 accent 色（封面语气色；取色失败回落红 #FA243C；holder 复用以 tag 守卫） */
    private void tintChartAccent(MusicChart chart, android.widget.TextView... views) {
        String key = chart.platform + ":" + chart.id;
        for (android.widget.TextView v : views) {
            if (v == null) continue;
            v.setTag(key);
            v.setTextColor(0xFFFA243C);
        }
        if (chart.cover == null || chart.cover.isEmpty()) return;
        ImgUtil.load(chart.cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
            @Override
            public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                Task.execute(() -> {
                    int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                    runOnUiThread(() -> {
                        for (android.widget.TextView v : views) {
                            if (v != null && key.equals(v.getTag())) v.setTextColor(dom);
                        }
                    });
                });
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
            }
        });
    }

    /** 平台胶囊（半粗、accent 字、accent 0.14 底；普通卡内边距 7/3dp＋圆角 7dp） */
    private void tintChartCapsule(MusicChart chart, android.widget.TextView capsule, int cornerDp) {
        if (capsule == null) return;
        String key = chart.platform + ":" + chart.id;
        capsule.setTag(key);
        capsule.setTextColor(0xFFFA243C);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(cornerDp));
        bg.setColor(0x24FA243C);
        capsule.setBackground(bg);
        if (chart.cover == null || chart.cover.isEmpty()) return;
        ImgUtil.load(chart.cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
            @Override
            public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                Task.execute(() -> {
                    int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                    runOnUiThread(() -> {
                        if (!key.equals(capsule.getTag())) return;
                        capsule.setTextColor(dom);
                        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                        d.setCornerRadius(dp(cornerDp));
                        d.setColor((dom & 0x00FFFFFF) | 0x24000000);
                        capsule.setBackground(d);
                    });
                });
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
            }
        });
    }

    /** 卡内预览行（「序号. 歌名 · 歌手」；歌手空时只剩序号＋歌名，不编空歌手） */
    private static String previewText(MusicTrack t, int index) {
        String title = t == null ? "" : t.displayTitle();
        String artist = t == null ? "" : t.displayArtist();
        String s = (index + 1) + ". " + title;
        if (artist != null && !artist.isEmpty()) s += " · " + artist;
        return s;
    }

    /** 榜单条目 rawJson 里同名字段映射（瓜子 TopListItem 字段名；只有源给了同名字段才填，无则留空） */
    private static void fillChartMetaFromRaw(MusicChart c) {
        if (c == null || c.rawJson == null || c.rawJson.isEmpty()) return;
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(c.rawJson).getAsJsonObject();
            if (c.trackCount <= 0 && o.has("trackCount") && !o.get("trackCount").isJsonNull()) {
                int n = o.get("trackCount").getAsInt();
                if (n > 0) c.trackCount = n;
            }
            if ((c.updateFrequency == null || c.updateFrequency.isEmpty())
                    && o.has("updateFrequency") && !o.get("updateFrequency").isJsonNull()) {
                String s = o.get("updateFrequency").getAsString();
                if (s != null && !s.isEmpty()) c.updateFrequency = s;
            }
            if ((c.description == null || c.description.isEmpty())) {
                String d = null;
                if (o.has("description") && !o.get("description").isJsonNull()) d = o.get("description").getAsString();
                else if (o.has("desc") && !o.get("desc").isJsonNull()) d = o.get("desc").getAsString();
                if (d != null && !d.isEmpty()) c.description = d;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 指定圆角加载（ImgUtil.loadRounded 写死 8dp；榜单卡 14dp 在此另起；
     * 本 helper 只在有封面地址时被调用，占位/失败一律透明，见 helper 内注） */
    private void loadCoverRounded(String text, String url, ImageView view, int radiusDp) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            com.bumptech.glide.Glide.with(view).clear(view);
            com.bumptech.glide.Glide.with(view).load(ImgUtil.getUrl(url))
                    // 封面占位定案（扫描 M22）：有地址永远透明——加载中/失败都不许变素图，
                    // 只有真没地址才上素图（那条路在绑定处直设 CoverTextDrawable，不走本 helper）
                    .placeholder(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    .error(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                            new com.bumptech.glide.load.resource.bitmap.RoundedCorners(dp(radiusDp)))
                    .into(view);
        } catch (Throwable ignored) {
        }
    }

    /** 半粗近似：API 28+ 用 600，低版本退伪粗体 */
    private static void setSemiBold(TextView tv) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false));
        } else {
            tv.getPaint().setFakeBoldText(true);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= 列表 =================

    private class ChartsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_CHART_FOCUS = 11;
        private static final int TYPE_CHART_SECTION = 12;
        private static final int TYPE_CHART_CARD = 13;

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_CHART_FOCUS;
            if (position == 1) return TYPE_CHART_SECTION;
            return TYPE_CHART_CARD;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_CHART_FOCUS) {
                return new ChartFocusHolder(inf.inflate(R.layout.item_music_chart_focus, parent, false));
            }
            if (viewType == TYPE_CHART_SECTION) {
                return new ChartSectionHolder(inf.inflate(R.layout.item_music_chart_section, parent, false));
            }
            return new ChartCardHolder(inf.inflate(R.layout.item_music_chart_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (h instanceof ChartFocusHolder) bindChartFocus((ChartFocusHolder) h, charts.get(0));
            else if (h instanceof ChartSectionHolder) bindChartSection((ChartSectionHolder) h);
            else bindChartCard((ChartCardHolder) h, charts.get(position - 1));
        }

        @Override
        public int getItemCount() {
            return charts.isEmpty() ? 0 : 2 + (charts.size() - 1);
        }

        private void openChart(MusicChart chart) {
            openChartDetail(chart);
        }

        class ChartFocusHolder extends RecyclerView.ViewHolder {
            final View cardRoot;
            final ImageView cover;
            final TextView name;
            final TextView preview0;
            final TextView preview1;
            final TextView preview2;
            final TextView btnExplore;
            final TextView focusLabel;
            final TextView freq;

            ChartFocusHolder(View v) {
                super(v);
                MusicGlassWire.glassifyTree(v); // 行一框：行内玻璃件同源接线（根或内卡按资源识别）
                cardRoot = v.findViewById(R.id.chartCardRoot);
                cover = v.findViewById(R.id.cover);
                name = v.findViewById(R.id.name);
                preview0 = v.findViewById(R.id.preview0);
                preview1 = v.findViewById(R.id.preview1);
                preview2 = v.findViewById(R.id.preview2);
                btnExplore = v.findViewById(R.id.btnExplore);
                focusLabel = v.findViewById(R.id.focusLabel);
                freq = v.findViewById(R.id.chartFreq);
            }
        }

        class ChartSectionHolder extends RecyclerView.ViewHolder {
            final TextView count;

            ChartSectionHolder(View v) {
                super(v);
                count = v.findViewById(R.id.count);
            }
        }

        class ChartCardHolder extends RecyclerView.ViewHolder {
            final View cardRoot;
            final ImageView cover;
            final TextView name;
            final TextView preview0;
            final TextView preview1;
            final TextView capsule;
            final TextView meta;
            final TextView metaFallback;

            ChartCardHolder(View v) {
                super(v);
                MusicGlassWire.glassifyTree(v); // 行一框：行内玻璃件同源接线（根或内卡按资源识别）
                cardRoot = v.findViewById(R.id.chartCardRoot);
                cover = v.findViewById(R.id.cover);
                name = v.findViewById(R.id.name);
                preview0 = v.findViewById(R.id.preview0);
                preview1 = v.findViewById(R.id.preview1);
                capsule = v.findViewById(R.id.chartCapsule);
                meta = v.findViewById(R.id.chartMeta);
                metaFallback = v.findViewById(R.id.chartMetaFallback);
            }
        }

        private void bindChartFocus(ChartFocusHolder h, MusicChart chart) {
            h.name.setText(chart.name);
            // 焦点榜名 24sp／800／字距 −0.6sp（24sp × −0.025em）；封面圆角 14dp
            h.name.getPaint().setFakeBoldText(true);
            h.name.setLetterSpacing(-0.025f);
            if (chart.cover == null || chart.cover.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                // 真没封面才上素图（榜名排字），与行级 CoverTextDrawable 口径一致；
                // 此前是一块空白浅灰 #E5E5EA，没任何信息
                h.cover.setBackgroundColor(0x00000000);
                h.cover.setImageDrawable(new CoverTextDrawable(chart.name, false, dp(14)));
            } else {
                h.cover.setBackgroundColor(0x00000000);
                loadCoverRounded(chart.name, chart.cover, h.cover, 14);
            }
            neutralChartCard(h.cardRoot);
            // 标签行顺序照瓜子（「此刻焦点」accent 色在前＋弹性空位＋「CHART SPOTLIGHT」在后）；
            // 更新频率行 12sp 仅源给了值才显，无源留空不编。
            if (h.focusLabel != null) {
                h.focusLabel.setText("此刻焦点");
                tintChartAccent(chart, h.focusLabel, h.btnExplore);
            }
            if (h.freq != null) {
                if (chart.updateFrequency != null && !chart.updateFrequency.isEmpty()) {
                    h.freq.setVisibility(View.VISIBLE);
                    h.freq.setText(chart.updateFrequency);
                } else {
                    h.freq.setVisibility(View.GONE);
                }
            }
            List<MusicTrack> prev = chartPreviews.get(chart.platform + ":" + chart.id);
            TextView[] views = {h.preview0, h.preview1, h.preview2};
            for (int i = 0; i < views.length; i++) {
                if (prev != null && i < prev.size() && prev.get(i) != null) {
                    views[i].setVisibility(View.VISIBLE);
                    views[i].setText(previewText(prev.get(i), i));
                } else {
                    views[i].setVisibility(View.GONE);
                }
            }
            // 「探索榜单」accent 色文字 12sp 半粗
            if (h.btnExplore != null) {
                h.btnExplore.setText("探索榜单");
                setSemiBold(h.btnExplore);
            }
            h.btnExplore.setOnClickListener(v -> openChart(chart));
            h.itemView.setOnClickListener(v -> openChart(chart));
        }

        private void bindChartSection(ChartSectionHolder h) {
            h.count.setText(charts.size() + " 个榜单");
        }

        private void bindChartCard(ChartCardHolder h, MusicChart chart) {
            h.name.setText(chart.name);
            // 普通榜名 17sp／800；封面圆角 14dp
            h.name.getPaint().setFakeBoldText(true);
            if (chart.cover == null || chart.cover.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setBackgroundColor(0x00000000);
                h.cover.setImageDrawable(new CoverTextDrawable(chart.name, false, dp(14)));
            } else {
                h.cover.setBackgroundColor(0x00000000);
                loadCoverRounded(chart.name, chart.cover, h.cover, 14);
            }
            neutralChartCard(h.cardRoot);
            List<MusicTrack> prev = chartPreviews.get(chart.platform + ":" + chart.id);
            TextView[] views = {h.preview0, h.preview1};
            for (int i = 0; i < views.length; i++) {
                if (prev != null && i < prev.size() && prev.get(i) != null) {
                    views[i].setVisibility(View.VISIBLE);
                    views[i].setText(previewText(prev.get(i), i));
                } else {
                    views[i].setVisibility(View.GONE);
                }
            }
            // 元信息行（平台胶囊 11sp 半粗＋accent 字／0.14 底＋内边距 7/3dp＋圆角 7dp；
            //「N 首」＋更新频率仅源给了值才显，无源时末行沿用瓜子无曲文案「实时更新热门歌曲」）
            if (h.capsule != null) {
                String platName = LxPlatformSearch.platformName(chart.platform);
                if (platName == null || platName.isEmpty()) platName = chart.platform == null ? "" : chart.platform;
                if (platName.isEmpty()) {
                    h.capsule.setVisibility(View.GONE);
                } else {
                    h.capsule.setVisibility(View.VISIBLE);
                    h.capsule.setText(platName);
                    setSemiBold(h.capsule);
                    tintChartCapsule(chart, h.capsule, 7);
                }
            }
            if (h.meta != null) {
                String m = "";
                if (chart.trackCount > 0) m = chart.trackCount + " 首";
                if (chart.updateFrequency != null && !chart.updateFrequency.isEmpty())
                    m += (m.isEmpty() ? "" : " · ") + chart.updateFrequency;
                h.meta.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);
                h.meta.setText(m);
            }
            if (h.metaFallback != null) h.metaFallback.setVisibility(chart.trackCount > 0 ? View.GONE : View.VISIBLE);
            h.itemView.setOnClickListener(v -> openChart(chart));
        }
    }
}
