package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoTheaterBinding;
import com.fongmi.android.tv.databinding.ItemHongguoTheaterBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 红果剧场（找剧页）：海报网格目录浏览，入口在「我的」集合页（☰ 侧口）。
 *
 * <p>数据来源（一阶段 HgApiClient 的剧场面，字段口径未定版、容忍解析＋raw 兜底，
 * 与 HgFeedSources/HgParser 同口径，如实注）：</p>
 * <ul>
 *   <li>首屏：GET /reading/bookapi/bookmall/tab（剧场页签面）容忍抽出的剧条目；</li>
 *   <li>首屏为空时兜底：GET /reading/bookapi/new_category/front（分类面）同口径抽条目；</li>
 *   <li>「换一批」：GET /reading/bookapi/bookmall/cell/change——翻页/换批参数
 *       （游标字段名）未在样本里定版，现透传空参数，服务端给什么拼什么，按剧 id
 *       去重追加；回包没有新剧时如实提示「没有更多了」，不编造下一批。</li>
 *   <li>分类筛选行不做：new_category 的分类参数（类目 id 的字段名与取值）未定版，
 *       不在此编口径；定版后在本页加一行筛选即可，网格与进剧链不用动。</li>
 * </ul>
 * <p>条目点中统一走 {@link HongguoFeedActivity#startSeries} 进刷剧流（与搜索/
 * 列表页同口），顺手 HgStore.putMeta 落元信息供追更/收藏/历史列表显示。
 * 空态/失败态诚实：没抽出可显示的剧就一句文案，不拿 raw 里的不明字段凑条目。</p>
 */
public class HgTheaterActivity extends BaseActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, HgTheaterActivity.class));
    }

    private ActivityHongguoTheaterBinding binding;
    private HgFeedSession session;
    private final List<HgSeriesItem> items = new ArrayList<>();
    private final Set<String> seenIds = new HashSet<>();
    private RecyclerView.Adapter<Holder> adapter;
    private boolean loading;
    private boolean destroyed;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoTheaterBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        session = HgFeedSession.get();
        adapter = new RecyclerView.Adapter<Holder>() {
            @NonNull
            @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new Holder(ItemHongguoTheaterBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull Holder holder, int position) {
                HgSeriesItem item = items.get(position);
                ItemHongguoTheaterBinding b = holder.binding;
                ImgUtil.load(item.getTitle() == null ? "" : item.getTitle(), item.getCoverUrl(), b.hgTCover);
                b.hgTTitle.setText(item.getTitle() == null ? "" : item.getTitle());
                b.hgTMeta.setText(item.getEpisodeCount() > 0 ? "全" + item.getEpisodeCount() + "集" : "");
                // 预约钮：只对回包带 appointment 的未上线剧亮，态走互动镜像（服务端为准）
                if (item.hasAppointment() && item.getSeriesId() != null) {
                    boolean reserved = session.interactions.isReserved(item.getSeriesId());
                    b.hgTReserve.setVisibility(View.VISIBLE);
                    b.hgTReserve.setText(reserved ? "已预约" : "预约");
                    b.hgTReserve.setAlpha(reserved ? 0.7f : 1f);
                    b.hgTReserve.setOnClickListener(v -> toggleReserve(item, holder.getBindingAdapterPosition()));
                } else {
                    b.hgTReserve.setVisibility(View.GONE);
                    b.hgTReserve.setOnClickListener(null);
                }
                b.getRoot().setOnClickListener(v -> {
                    if (item.getSeriesId() == null) return;
                    HgStore.putMeta(item.getSeriesId(), item.getTitle(), item.getCoverUrl(), item.getEpisodeCount());
                    HongguoFeedActivity.startSeries(HgTheaterActivity.this,
                            item.getSeriesId(), item.getTitle(), item.getCoverUrl());
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        };
        binding.hgTheaterGrid.setLayoutManager(new GridLayoutManager(this, 3));
        binding.hgTheaterGrid.setAdapter(adapter);
        loadFirst();
    }

    @Override
    protected void initEvent() {
        binding.hgTheaterBack.setOnClickListener(v -> finish());
        binding.hgTheaterMore.setOnClickListener(v -> loadMore());
    }

    /** 首屏：bookmall/tab 抽条目；为空退 category/front；两面都空则诚实空态。 */
    private void loadFirst() {
        if (loading) return;
        loading = true;
        showState("加载中…");
        session.client.bookmallTab(new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                List<HgSeriesItem> got = page == null ? null : page.getItems();
                if (got != null && !got.isEmpty()) {
                    finishLoad(got, false);
                } else {
                    session.client.categoryFront(new HgCallback<HgCellPage>() {
                        @Override
                        public void onSuccess(HgCellPage front) {
                            finishLoad(front == null ? null : front.getItems(), false);
                        }

                        @Override
                        public void onError(int httpCode, String message) {
                            finishLoad(null, true);
                        }
                    });
                }
            }

            @Override
            public void onError(int httpCode, String message) {
                // 首面失败同样给分类面一次机会，两面都失败才报失败态
                session.client.categoryFront(new HgCallback<HgCellPage>() {
                    @Override
                    public void onSuccess(HgCellPage front) {
                        finishLoad(front == null ? null : front.getItems(), false);
                    }

                    @Override
                    public void onError(int code2, String message2) {
                        finishLoad(null, true);
                    }
                });
            }
        });
    }

    /** 换一批：bookmall/cell/change（参数未定版透传空表），新剧去重追加。 */
    private void loadMore() {
        if (loading) return;
        loading = true;
        session.client.bookmallCellChange(new HashMap<>(), new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                runOnUiThread(() -> {
                    loading = false;
                    if (destroyed || isFinishing()) return;
                    int added = appendItems(page == null ? null : page.getItems());
                    if (added <= 0) {
                        Toast.makeText(HgTheaterActivity.this, "没有更多了", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    loading = false;
                    if (destroyed || isFinishing()) return;
                    Toast.makeText(HgTheaterActivity.this, "加载失败，稍后再试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void finishLoad(List<HgSeriesItem> got, boolean failed) {
        runOnUiThread(() -> {
            loading = false;
            if (destroyed || isFinishing()) return;
            appendItems(got);
            if (!items.isEmpty()) {
                binding.hgTheaterState.setVisibility(View.GONE);
            } else if (failed) {
                showState("加载失败，稍后再试");
            } else {
                showState("暂时没有内容");
            }
        });
    }

    /** 点预约：未登录先拉壳子统一登录（与刷剧页同口径），登录后走互动真接口。 */
    private void toggleReserve(HgSeriesItem item, int position) {
        if (item.getSeriesId() == null) return;
        if (session.interactions.requiresLogin()) {
            boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
            if (!delivered) {
                Toast.makeText(this, "登录界面暂时打不开，稍后再试", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        boolean now = session.interactions.toggleReserve(item.getSeriesId());
        Toast.makeText(this, now ? "预约成功，上线后会通知你" : "预约已取消",
                Toast.LENGTH_SHORT).show();
        if (position >= 0) adapter.notifyItemChanged(position);
    }

    /** 追加条目（跳过无剧 id 的与已在网格里的），返回真正新增的条数。 */
    private int appendItems(List<HgSeriesItem> got) {
        if (got == null) return 0;
        int before = items.size();
        for (HgSeriesItem s : got) {
            if (s == null || s.getSeriesId() == null) continue;
            if (!seenIds.add(s.getSeriesId())) continue;
            // 回包带的服务端预约态同步进镜像（与关注页签同步追剧架同口径）
            if (s.hasAppointment()) HgStore.setReserved(s.getSeriesId(), s.isAppointmentSubscribed());
            items.add(s);
        }
        int added = items.size() - before;
        if (added > 0) adapter.notifyItemRangeInserted(before, added);
        return added;
    }

    private void showState(String text) {
        binding.hgTheaterState.setVisibility(View.VISIBLE);
        binding.hgTheaterState.setText(text);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ItemHongguoTheaterBinding binding;

        Holder(ItemHongguoTheaterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
