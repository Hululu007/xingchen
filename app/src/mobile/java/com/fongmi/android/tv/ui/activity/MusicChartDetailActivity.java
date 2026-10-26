package com.fongmi.android.tv.ui.activity;

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
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.XcFadeTransition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 榜单详情独立页（拆页 p6）：英雄卡＋排名行；
 * extras 契约照旧 openChartDetail（title/chartPlatform/chartId/chartCover/chartDesc/chartFreq）；
 * 数据源＝MusicRepository.chartTracks，酷我平台走专路（直连快出＋封面按片补＋直连空回落旧路）；
 * 播放全部／行点击走 MusicController，队列 route=list、参数照旧口径
 *（mode=chartDetail＋keyword/sitePlatform 空＋playlistId=-1＋title）。
 * 顶栏只留标题，无返回箭头，走系统返回。
 */
public class MusicChartDetailActivity extends BaseActivity {

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
    private TextView tvTitle;
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

    private final List<MusicTrack> tracks = new ArrayList<>();
    private ChartDetailAdapter adapter;
    private String chartPlatform = "";
    private String chartId = "";
    private String chartCover = "";
    private String chartDesc = "";
    private String chartFreq = "";
    private String bigTitle = "";
    private String lastCurrentId;
    private android.os.Parcelable pendingListState;

    /** 榜单详情开新页（参数组照旧 openChartDetail：整个 chart 的名／平台／id／封面＋描述／频率透传） */
    public static void startChartDetail(Activity activity, MusicChart chart) {
        Intent i = new Intent(activity, MusicChartDetailActivity.class);
        i.putExtra("title", chart.name);
        i.putExtra("chartPlatform", chart.platform);
        i.putExtra("chartId", chart.id);
        i.putExtra("chartCover", chart.cover);
        // 英雄卡描述／更新频率透传（源没给则空，详情页不编）
        i.putExtra("chartDesc", chart.description == null ? "" : chart.description);
        i.putExtra("chartFreq", chart.updateFrequency == null ? "" : chart.updateFrequency);
        activity.startActivity(i);
    }

    private final MusicController.Listener controllerListener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            refreshCurrentRow();
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            refreshCurrentRow();
        }
    };

    @Override
    protected ViewBinding getBinding() {
        // 只 inflate 一次：BaseActivity 先取根 setContentView、initView 再取根走 glassifyTree，
        // 两次必须是同一棵树——重复 inflate 会让接线落在游离树上、显示的那棵永不生效。
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_chart_detail, null);
        return new SimpleBinding(rootView);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicGlassWire.glassifyTree(getBinding().getRoot()); // 全页玻璃件同源接线：共用件按资源识别统一换液态玻璃（MusicGlassWire）
        // 与旧列表页同口径：锁 adjustPan，键盘不缩放窗口、不带乱空态位置。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        Intent i = getIntent();
        chartPlatform = i.getStringExtra("chartPlatform");
        chartId = i.getStringExtra("chartId");
        chartCover = i.getStringExtra("chartCover");
        chartDesc = i.getStringExtra("chartDesc");
        chartFreq = i.getStringExtra("chartFreq");
        if (chartPlatform == null) chartPlatform = "";
        if (chartId == null) chartId = "";
        if (chartCover == null) chartCover = "";
        if (chartDesc == null) chartDesc = "";
        if (chartFreq == null) chartFreq = "";
        bigTitle = i.getStringExtra("title");
        if (bigTitle == null) bigTitle = "";
        if (savedInstanceState != null) {
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        tvTitle = findViewById(R.id.tvTitle);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        // 顶栏搜索钮照旧落点：壳搜索模式（搜索页尚未拆）
        ImageView btnSearch = findViewById(R.id.btnSearch);
        btnSearch.setOnClickListener(v -> MusicSearchActivity.startSearch(this, ""));
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new ChartDetailAdapter());
        MusicController.get().addListener(controllerListener);
        loadChartDetail();
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
        // 旧口径：榜单详情是网络模式，回页不重拉（onResume 的重拉只给本地数据模式）
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    // ================= 数据加载 =================

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    private void loadChartDetail() {
        showLoading();
        empty.setOnClickListener(null);
        tvTitle.setText(bigTitle);
        MusicChart chart = new MusicChart(chartPlatform, chartId, bigTitle);
        chart.cover = chartCover;
        if (KwChartSource.PLATFORM.equals(chartPlatform)) {
            loadKwChartDetail(chart);
            return;
        }
        Task.execute(() -> {
            List<MusicTrack> r = MusicRepository.chartTracks(chart);
            runOnUiThread(() -> {
                tracks.clear();
                if (r != null) tracks.addAll(r);
                adapter.notifyDataSetChanged();
                fadeListInOnce();
                if (tracks.isEmpty()) XcFadeTransition.fadeIn(empty);
                else XcFadeTransition.fadeOutThenGone(empty);
                if (tracks.isEmpty()) {
                    // 榜单详情空态走榜单口径；点空态重试（失败与空未区分，源不给失败态）
                    empty.setText(R.string.wave2_chart_empty);
                    empty.setOnClickListener(v -> loadChartDetail());
                }
            });
        });
    }

    /** 酷我详情首屏不再等整榜：先直连快出文字＋时长（这部分快），封面按片陆续补（先回的片先上图，不等另一片）。
     * 直连都空时走旧路（Worker 分片＋整榜直连回落），行为与改动前一致；QQ 等其它源走上面旧路不动。 */
    private void loadKwChartDetail(MusicChart chart) {
        final String detailId = chartId;
        Task.execute(() -> {
            MusicRepository.startKwChartSlices(chart);
            List<MusicTrack> direct = MusicRepository.chartTracksDirect(chart);
            if (direct == null || direct.isEmpty()) {
                List<MusicTrack> r = MusicRepository.chartTracks(chart);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !detailId.equals(chartId)) return;
                    tracks.clear();
                    if (r != null) tracks.addAll(r);
                    adapter.notifyDataSetChanged();
                    fadeListInOnce();
                    if (tracks.isEmpty()) XcFadeTransition.fadeIn(empty);
                    else XcFadeTransition.fadeOutThenGone(empty);
                    if (tracks.isEmpty()) {
                        empty.setText(R.string.wave2_chart_empty);
                        empty.setOnClickListener(v -> loadChartDetail());
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !detailId.equals(chartId)) return;
                tracks.clear();
                tracks.addAll(direct);
                adapter.notifyDataSetChanged();
                XcFadeTransition.fadeOutThenGone(empty);
            });
            MusicRepository.fetchKwChartCovers(chart, new KwChartSource.CoverListener() {
                @Override
                public void onShard(int pn, List<MusicTrack> shard) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || !detailId.equals(chartId)) return;
                        applyShardCovers(shard);
                    });
                }
            });
        });
    }

    /** 按 rid 把一片 Worker 封面贴到已显示的直连列表上，只刷封面变了的行（先回的片先上图，不等另一片）；
     * 封面为空的片内曲目留素图（与旧按片回落一致）；chartDetail 行 0 号是头，曲目行下标＝曲目号＋1 */
    private void applyShardCovers(List<MusicTrack> shard) {
        if (shard == null || shard.isEmpty() || tracks.isEmpty()) return;
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < tracks.size(); i++) {
            String id = tracks.get(i).id;
            if (id != null && !id.isEmpty()) index.put(id, i);
        }
        for (MusicTrack s : shard) {
            if (s == null || s.id == null || s.cover == null || s.cover.isEmpty()) continue;
            Integer idx = index.get(s.id);
            if (idx == null) continue;
            MusicTrack cur = tracks.get(idx);
            if (s.cover.equals(cur.cover)) continue;
            cur.cover = s.cover;
            adapter.notifyItemChanged(idx + 1);
        }
    }

    // ================= 播放 =================

    /** list 路由参数：mode/keyword/sitePlatform/playlistId/title 五件照旧口径，
     * 另补 chartPlatform/chartId 两键——队列来源跳转靠这两键重建本页（只有 title 时
     * 队列侧无从知道是哪张榜，只能落空页；扫查待裁 1 修复） */
    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "chartDetail");
        params.put("keyword", "");
        params.put("sitePlatform", "");
        params.put("playlistId", "-1");
        params.put("title", bigTitle == null ? "" : bigTitle);
        params.put("chartPlatform", chartPlatform == null ? "" : chartPlatform);
        params.put("chartId", chartId == null ? "" : chartId);
        return params;
    }

    private void playTrack(int position) {
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    /** 切歌只刷旧在播行＋新在播行，不全量重绑。 */
    private void refreshCurrentRow() {
        if (adapter == null) return;
        MusicTrack cur = MusicController.get().current();
        String nowId = cur == null ? null : cur.id;
        int oldPos = adapterPositionForTrack(indexOfTrackId(lastCurrentId));
        int newPos = adapterPositionForTrack(indexOfTrackId(nowId));
        if (oldPos >= 0) adapter.notifyItemChanged(oldPos);
        if (newPos >= 0 && newPos != oldPos) adapter.notifyItemChanged(newPos);
        lastCurrentId = nowId;
    }

    /** tracks 下标 → adapter 位置：榜单详情行 0 号是英雄卡头，曲目行下标＝曲目号＋1（旧共享 adapter 口径） */
    private int adapterPositionForTrack(int trackIdx) {
        if (adapter == null || trackIdx < 0 || trackIdx >= tracks.size()) return -1;
        return trackIdx + 1;
    }

    private int indexOfTrackId(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            MusicTrack t = tracks.get(i);
            if (t != null && id.equals(t.id)) return i;
        }
        return -1;
    }

    // ================= 取色与工具 =================

    /** 平台胶囊（半粗、accent 字、accent 0.14 底；详情英雄卡内边距 8/3dp＋圆角 8dp） */
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

    /** 显示用歌手：占位（空／<unknown>／unknown 类）一律显统一文案，不只空值。 */
    private String artistDisplay(String raw) {
        return com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(raw)
                ? getString(R.string.music_unknown_artist) : raw;
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

    private class ChartDetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_CHART_HERO = 14;
        private static final int TYPE_CHART_TRACK = 15;

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_CHART_HERO;
            return TYPE_CHART_TRACK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_CHART_HERO) {
                return new ChartHeroHolder(inf.inflate(R.layout.item_music_chart_hero, parent, false));
            }
            return new ChartTrackHolder(inf.inflate(R.layout.item_music_chart_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (h instanceof ChartHeroHolder) bindChartHero((ChartHeroHolder) h);
            else bindChartTrack((ChartTrackHolder) h, trackIndex(position));
        }

        /** 曲目行在 tracks 里的下标：前面有英雄卡 1 行 */
        private int trackIndex(int position) {
            return position - 1;
        }

        @Override
        public int getItemCount() {
            return 1 + tracks.size();
        }

        class ChartHeroHolder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView name;
            final TextView capsule;
            final TextView desc;
            final TextView freq;
            final View btnPlayAll;
            final View btnRefresh;

            ChartHeroHolder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                name = v.findViewById(R.id.name);
                capsule = v.findViewById(R.id.chartHeroCapsule);
                desc = v.findViewById(R.id.chartHeroDesc);
                freq = v.findViewById(R.id.chartHeroFreq);
                btnPlayAll = v.findViewById(R.id.btnChartPlayAll);
                btnRefresh = v.findViewById(R.id.btnChartRefresh);
                cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(android.view.View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                    }
                });
                cover.setClipToOutline(true);
            }
        }

        class ChartTrackHolder extends RecyclerView.ViewHolder {
            final TextView rank;
            final ImageView stateMark;
            final ImageView cover;
            final TextView title;
            final TextView artist;
            final TextView duration;

            ChartTrackHolder(View v) {
                super(v);
                rank = v.findViewById(R.id.rank);
                stateMark = v.findViewById(R.id.stateMark);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                artist = v.findViewById(R.id.artist);
                duration = v.findViewById(R.id.duration);
                cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(android.view.View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                    }
                });
                cover.setClipToOutline(true);
            }
        }

        private void bindChartHero(ChartHeroHolder h) {
            // 详情英雄卡（榜名 24sp／800／字距 −0.4sp＋平台胶囊 10sp＋描述／更新行＋48dp 高「播放全部」钮；
            // 刷新照瓜子详情顶栏刷新落点——星辰详情页无自带顶栏刷新位，刷新钮放英雄卡操作行右端）
            h.name.setText(bigTitle);
            h.name.getPaint().setFakeBoldText(true);
            h.name.setLetterSpacing(-0.017f);
            String platName = LxPlatformSearch.platformName(chartPlatform);
            if (h.capsule != null) {
                if (platName == null || platName.isEmpty()) {
                    h.capsule.setVisibility(View.GONE);
                } else {
                    h.capsule.setVisibility(View.VISIBLE);
                    h.capsule.setText(platName);
                    setSemiBold(h.capsule);
                    MusicChart heroChart = new MusicChart(chartPlatform, chartId, bigTitle);
                    heroChart.cover = chartCover;
                    tintChartCapsule(heroChart, h.capsule, 8);
                }
            }
            // 描述／更新频率来自列表页 intent 透传（源没给则留空隐藏；详情现取的曲目数不冒充榜总曲数，故英雄卡不显「N 首」）
            if (h.desc != null) {
                if (chartDesc == null || chartDesc.isEmpty()) h.desc.setVisibility(View.GONE);
                else {
                    h.desc.setVisibility(View.VISIBLE);
                    h.desc.setText(chartDesc);
                }
            }
            if (h.freq != null) {
                if (chartFreq == null || chartFreq.isEmpty()) h.freq.setVisibility(View.GONE);
                else {
                    h.freq.setVisibility(View.VISIBLE);
                    h.freq.setText(chartFreq);
                }
            }
            if (chartCover == null || chartCover.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(null);
                h.cover.setBackgroundColor(0xFFE5E5EA);
            } else {
                h.cover.setBackgroundColor(0x00000000);
                ImgUtil.loadRounded16Fade(bigTitle, chartCover, h.cover);
            }
            if (h.btnPlayAll != null) {
                h.btnPlayAll.setOnClickListener(v -> {
                    if (tracks.isEmpty()) {
                        Notify.show(R.string.music_no_track);
                        return;
                    }
                    playTrack(0);
                });
            }
            if (h.btnRefresh != null) {
                h.btnRefresh.setOnClickListener(v -> loadChartDetail());
            }
        }

        private void bindChartTrack(ChartTrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            // 排名始终可见：在播行取封面语气色，其余默认灰
            h.rank.setVisibility(View.VISIBLE);
            h.rank.setText(String.valueOf(position + 1));
            h.rank.setTextColor(0xA6FFFFFF);
            h.title.setText(t.displayTitle());
            h.title.setTextColor(0xFFF1F4F5);
            h.artist.setText(artistDisplay(t.displayArtist()));
            h.duration.setText(fmt(t.duration));
            String trackKey = t.id == null ? "" : t.id;
            h.rank.setTag(trackKey);
            h.stateMark.setTag(trackKey);
            if (isCurrent) {
                // 在播标记按瓜子图标版：播放态 14dp／暂停态 12dp 图标，tint 同取封面语气色；
                // 无障碍文案沿用瓜子「正在播放／已暂停」
                boolean playing = MusicController.get().isPlaying();
                h.stateMark.setVisibility(View.VISIBLE);
                h.stateMark.setImageResource(playing ? R.drawable.ic_music_eq : R.drawable.ic_music_pause);
                h.stateMark.setContentDescription(playing ? "正在播放" : "已暂停");
                android.view.ViewGroup.LayoutParams slp = h.stateMark.getLayoutParams();
                int sz = dp(playing ? 14 : 12);
                slp.width = sz;
                slp.height = sz;
                h.stateMark.setLayoutParams(slp);
                if (t.cover == null || t.cover.isEmpty()) {
                    h.rank.setTextColor(0xFFFA243C);
                    h.stateMark.setColorFilter(0xFFFA243C);
                } else {
                    h.rank.setTextColor(0xFFFA243C);
                    h.stateMark.setColorFilter(0xFFFA243C);
                    ImgUtil.load(t.cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                                    com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                            if (!trackKey.equals(h.rank.getTag())) return;
                            Task.execute(() -> {
                                int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                                runOnUiThread(() -> {
                                    if (!trackKey.equals(h.rank.getTag())) return;
                                    h.rank.setTextColor(dom);
                                    h.stateMark.setColorFilter(dom);
                                });
                            });
                        }

                        @Override
                        public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                        }
                    });
                }
            } else {
                h.stateMark.setVisibility(View.GONE);
            }
            if (t.cover == null || t.cover.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t.displayTitle(), false, 10 * h.cover.getResources().getDisplayMetrics().density));
            } else {
                ImgUtil.loadRounded10(t.displayTitle(), t.cover, h.cover);
            }
            h.itemView.setOnClickListener(v -> playTrack(position));
            MusicGlassWire.frameRow(h.itemView); // 行一框：液态玻璃（原口径非当前行裸底、当前行白卡；当前行另有红名＋在播字样作标识）
        }

        private String fmt(long ms) {
            if (ms <= 0) return "--:--";
            long s = ms / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }
    }
}
