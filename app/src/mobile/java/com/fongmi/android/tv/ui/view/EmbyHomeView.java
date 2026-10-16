package com.fongmi.android.tv.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.emby.EmbyApi;
import com.fongmi.android.tv.emby.EmbyItem;
import com.fongmi.android.tv.emby.EmbyNavigator;
import com.fongmi.android.tv.emby.EmbyStore.EmbyServer;
import com.fongmi.android.tv.emby.EmbyStore;
import com.fongmi.android.tv.ui.activity.EmbyLibraryActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emby 专属首页视图：继续观看（真横行）/ 媒体库入口行 / 最新添加 / 接着看下一集。
 * 常驻不重建：集成层只做显隐切换。bind 先渲染本地缓存首屏，再后台差量刷新。
 *
 * 对外 API（集成子代理在 VodFragment 切换里调用）：
 * bind(serverId) / refresh() / setActive(boolean) / release()
 */
public class EmbyHomeView extends FrameLayout {

    public static final String SEC_RESUME = "resume";
    public static final String SEC_VIEWS = "views";
    public static final String SEC_FAV = "fav";
    public static final String SEC_LATEST = "latest";
    public static final String SEC_NEXTUP = "nextup";

    private static final String[] SEC_ORDER = {SEC_RESUME, SEC_VIEWS, SEC_FAV, SEC_LATEST, SEC_NEXTUP};

    private static volatile boolean sShowing = false;
    private static WeakReference<EmbyHomeView> sCurrent;

    /** 专属首页当前是否在展示（给线路弹窗做互斥选中态用）。 */
    public static boolean isShowing() {
        return sShowing;
    }

    /** 管理页改了栏目开关后，让当前绑定的首页立即重排。 */
    public static void refreshCurrent() {
        EmbyHomeView v = sCurrent == null ? null : sCurrent.get();
        if (v != null) v.refresh();
    }

    /**
     * 当前实例直接显隐（给 EmbyMode 用）：exit()/换服时前景页可能不是 HomeActivity，
     * 按 Fragment 找首页视图会扑空，这里兜底保证 setActive 一定被调用。
     */
    public static void hideCurrent() {
        EmbyHomeView v = sCurrent == null ? null : sCurrent.get();
        if (v != null) v.setActive(false);
    }

    /** 当前实例直接重绑新服（换服时前景页不是 HomeActivity 的兜底）。 */
    public static void bindCurrent(String serverId) {
        EmbyHomeView v = sCurrent == null ? null : sCurrent.get();
        if (v != null) v.bind(serverId);
    }

    private ViewGroup container;
    private View pageEmpty;
    private String serverId;
    private EmbyApi api;
    private int gen;
    private final Map<String, Rail> rails = new LinkedHashMap<>();

    public EmbyHomeView(Context context) {
        super(context);
        init(context);
    }

    public EmbyHomeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_emby_home, this, true);
        container = findViewById(R.id.container);
        pageEmpty = findViewById(R.id.tv_page_empty);
    }

    /** 绑定服务器：先出缓存首屏，再后台拉新。 */
    public void bind(String serverId) {
        this.serverId = serverId;
        start();
    }

    /** 手动刷新：按当前栏目开关重排，后台重拉全部栏目。 */
    public void refresh() {
        if (serverId == null) return;
        start();
    }

    private void start() {
        gen++;
        sCurrent = new WeakReference<>(this);
        rails.clear();
        container.removeAllViews();
        EmbyServer server = findServer(serverId);
        if (server == null) {
            pageEmpty.setVisibility(View.VISIBLE);
            return;
        }
        pageEmpty.setVisibility(View.GONE);
        api = newApi();
        buildRails();
        renderCache();
        loadFresh();
    }

    private EmbyApi newApi() {
        EmbyServer s = findServer(serverId);
        if (s == null) return null;
        return new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
    }

    /** 集成层切换首页时调用：只做显隐，不重建。 */
    public void setActive(boolean active) {
        sShowing = active;
        setVisibility(active ? View.VISIBLE : View.GONE);
    }

    /** 释放：停掉后台加载，清图片。 */
    public void release() {
        gen++;
        sShowing = false;
        if (sCurrent != null && sCurrent.get() == this) sCurrent.clear();
        for (Rail r : rails.values()) r.release();
        rails.clear();
        container.removeAllViews();
        serverId = null;
    }

    // ---------- 栏目搭建 ----------

    private void buildRails() {
        for (String key : SEC_ORDER) {
            if (!EmbyStore.sectionVisible(serverId, key)) continue;
            Rail rail = new Rail(getContext(), key);
            rails.put(key, rail);
            container.addView(rail.root);
        }
    }

    private EmbyServer findServer(String id) {
        try {
            List<EmbyServer> all = EmbyStore.servers();
            if (all != null) for (EmbyServer s : all) {
                if (s != null && id != null && id.equals(s.id)) return s;
            }
            return EmbyStore.active();
        } catch (Throwable t) {
            return null;
        }
    }

    private EmbyApi newApi() {
        EmbyServer s = findServer(serverId);
        if (s == null) return null;
        return new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
    }

    // ---------- 缓存首屏 ----------

    private void renderCache() {
        String json;
        try {
            json = EmbyStore.homeCache(serverId);
        } catch (Throwable t) {
            return;
        }
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            for (String key : SEC_ORDER) {
                Rail rail = rails.get(key);
                if (rail == null) continue;
                if (SEC_VIEWS.equals(key)) {
                    rail.setViews(parseItems(root.optJSONArray(key)));
                } else {
                    rail.setItems(parseItems(root.optJSONArray(key)));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private List<EmbyItem> parseItems(JSONArray arr) {
        List<EmbyItem> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                EmbyItem it = new EmbyItem();
                it.id = o.optString("id", "");
                it.name = o.optString("name", "");
                it.type = o.optString("type", "");
                it.overview = o.optString("overview", "");
                it.imageTag = o.optString("imageTag", "");
                it.backdropTag = o.optString("backdropTag", "");
                it.positionTicks = o.optLong("positionTicks", 0);
                it.runTimeTicks = o.optLong("runTimeTicks", 0);
                it.played = o.optBoolean("played", false);
                it.favorite = o.optBoolean("favorite", false);
                it.communityRating = (float) o.optDouble("communityRating", 0);
                if (!it.id.isEmpty()) list.add(it);
            } catch (Throwable ignored) {
            }
        }
        return list;
    }

    private JSONObject itemJson(EmbyItem it) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", it.id);
            o.put("name", it.name);
            o.put("type", it.type);
            o.put("overview", it.overview);
            o.put("imageTag", it.imageTag);
            o.put("backdropTag", it.backdropTag);
            o.put("positionTicks", it.positionTicks);
            o.put("runTimeTicks", it.runTimeTicks);
            o.put("played", it.played);
            o.put("favorite", it.favorite);
            o.put("communityRating", it.communityRating);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ---------- 后台差量刷新 ----------

    private void loadFresh() {
        final int myGen = gen;
        final String sid = serverId;
        final EmbyApi api = this.api;
        if (api == null) return;
        new Thread(() -> {
            Map<String, List<EmbyItem>> fresh = new LinkedHashMap<>();
            for (String key : SEC_ORDER) {
                if (myGen != gen) return;
                if (!isVisibleSection(sid, key)) continue;
                try {
                    List<EmbyItem> list;
                    switch (key) {
                        case SEC_RESUME: list = api.resume(); break;
                        case SEC_VIEWS: list = api.views(); break;
                        case SEC_FAV: list = api.favoriteItems(); break;
                        case SEC_LATEST: list = api.latest(); break;
                        case SEC_NEXTUP: list = api.nextUp(); break;
                        default: list = new ArrayList<>();
                    }
                    fresh.put(key, list == null ? new ArrayList<>() : list);
                    final String k = key;
                    final List<EmbyItem> data = fresh.get(key);
                    App.post(() -> {
                        if (myGen != gen) return;
                        Rail rail = rails.get(k);
                        if (rail == null) return;
                        if (SEC_VIEWS.equals(k)) rail.setViews(data);
                        else rail.setItems(data);
                    });
                } catch (Throwable e) {
                    final String k = key;
                    App.post(() -> {
                        if (myGen != gen) return;
                        Rail rail = rails.get(k);
                        if (rail != null) rail.setError();
                    });
                }
            }
            if (myGen != gen) return;
            saveCache(sid, fresh);
        }).start();
    }

    private boolean isVisibleSection(String sid, String key) {
        try {
            return EmbyStore.sectionVisible(sid, key);
        } catch (Throwable t) {
            return true;
        }
    }

    private void saveCache(String sid, Map<String, List<EmbyItem>> fresh) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, List<EmbyItem>> e : fresh.entrySet()) {
                JSONArray arr = new JSONArray();
                for (EmbyItem it : e.getValue()) arr.put(itemJson(it));
                root.put(e.getKey(), arr);
            }
            EmbyStore.saveHomeCache(sid, root.toString());
        } catch (Throwable ignored) {
        }
    }

    // ---------- 栏目行 ----------

    private class Rail {
        final View root;
        final String key;
        final RecyclerView rv;
        final View loading;
        final TextView tvEmpty;
        final TextView tvTitle;
        final TextView tvAll;
        PosterAdapter posterAdapter;
        LibAdapter libAdapter;
        boolean loaded;

        Rail(Context ctx, String key) {
            this.key = key;
            root = LayoutInflater.from(ctx).inflate(R.layout.item_emby_rail, container, false);
            tvTitle = root.findViewById(R.id.tv_title);
            tvAll = root.findViewById(R.id.tv_all);
            rv = root.findViewById(R.id.rv);
            loading = root.findViewById(R.id.loading);
            tvEmpty = root.findViewById(R.id.tv_empty);
            tvTitle.setText(titleOf(key));
            tvEmpty.setText(emptyOf(key));
            rv.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
            if (SEC_VIEWS.equals(key)) {
                libAdapter = new LibAdapter();
                rv.setAdapter(libAdapter);
                tvAll.setVisibility(View.GONE);
            } else {
                posterAdapter = new PosterAdapter(this);
                rv.setAdapter(posterAdapter);
                tvAll.setOnClickListener(v -> openAll());
            }
            root.findViewById(R.id.header).setOnLongClickListener(v -> {
                confirmHide();
                return true;
            });
            showLoading();
        }

        void setItems(List<EmbyItem> items) {
            loaded = true;
            loading.setVisibility(View.GONE);
            tvEmpty.setOnClickListener(null);
            tvEmpty.setText(emptyOf(key));
            if (posterAdapter != null) posterAdapter.setItems(items);
            boolean empty = items == null || items.isEmpty();
            rv.setVisibility(empty ? View.GONE : View.VISIBLE);
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }

        /** 卡片态变更后刷新该卡（已看/收藏角标）。 */
        void refreshCard(EmbyItem it) {
            if (posterAdapter == null || it == null) return;
            int idx = posterAdapter.items.indexOf(it);
            if (idx >= 0) posterAdapter.notifyItemChanged(idx);
        }

        /** 收藏 rail 里取消收藏：卡片直接抽掉，空了就显示空文案。 */
        void removeCard(EmbyItem it) {
            if (posterAdapter == null || it == null) return;
            int idx = posterAdapter.items.indexOf(it);
            if (idx < 0) return;
            posterAdapter.items.remove(idx);
            posterAdapter.notifyItemRemoved(idx);
            if (posterAdapter.getItemCount() == 0) setItems(new ArrayList<>());
        }

        void setViews(List<EmbyItem> views) {
            loaded = true;
            loading.setVisibility(View.GONE);
            tvEmpty.setOnClickListener(null);
            tvEmpty.setText(emptyOf(key));
            if (libAdapter != null) libAdapter.setItems(views);
            boolean empty = views == null || views.isEmpty();
            rv.setVisibility(empty ? View.GONE : View.VISIBLE);
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }

        void setError() {
            // 有缓存就不打扰；没数据才显示可点重试
            if (loaded) return;
            loading.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.emby_load_fail);
            tvEmpty.setOnClickListener(v -> {
                tvEmpty.setOnClickListener(null);
                showLoading();
                refresh();
            });
        }

        void showLoading() {
            loaded = false;
            rv.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.GONE);
            loading.setVisibility(View.VISIBLE);
        }

        void release() {
            if (posterAdapter != null) posterAdapter.setItems(null);
            if (libAdapter != null) libAdapter.setItems(null);
        }

        void openAll() {
            String mode = EmbyLibraryActivity.MODE_RESUME;
            if (SEC_FAV.equals(key)) mode = EmbyLibraryActivity.MODE_FAV;
            else if (SEC_LATEST.equals(key)) mode = EmbyLibraryActivity.MODE_LATEST;
            else if (SEC_NEXTUP.equals(key)) mode = EmbyLibraryActivity.MODE_NEXTUP;
            EmbyLibraryActivity.start(getContext(), serverId, mode, null, titleOf(key));
        }

        void confirmHide() {
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.emby_hide_section_title)
                    .setMessage(getContext().getString(R.string.emby_hide_section_msg, titleOf(key)))
                    .setPositiveButton(R.string.emby_hide, (d, w) -> {
                        try {
                            EmbyStore.setSectionVisible(serverId, key, false);
                        } catch (Throwable ignored) {
                        }
                        rails.remove(key);
                        container.removeView(root);
                        Notify.show("已隐藏");
                    })
                    .setNegativeButton(R.string.emby_keep, null)
                    .show();
        }
    }

    private String titleOf(String key) {
        Context ctx = getContext();
        switch (key) {
            case SEC_RESUME: return ctx.getString(R.string.emby_rail_resume);
            case SEC_VIEWS: return ctx.getString(R.string.emby_rail_views);
            case SEC_FAV: return ctx.getString(R.string.emby_rail_fav);
            case SEC_LATEST: return ctx.getString(R.string.emby_rail_latest);
            default: return ctx.getString(R.string.emby_rail_nextup);
        }
    }

    private String emptyOf(String key) {
        Context ctx = getContext();
        switch (key) {
            case SEC_RESUME: return ctx.getString(R.string.emby_empty_resume);
            case SEC_VIEWS: return ctx.getString(R.string.emby_empty_views);
            case SEC_FAV: return ctx.getString(R.string.emby_empty_fav);
            case SEC_LATEST: return ctx.getString(R.string.emby_empty_latest);
            default: return ctx.getString(R.string.emby_empty_nextup);
        }
    }

    // ---------- 海报适配器 ----------

    private class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
        private final Rail rail;
        private final String section;
        private List<EmbyItem> items = new ArrayList<>();

        PosterAdapter(Rail rail) {
            this.rail = rail;
            this.section = rail.key;
        }

        void setItems(List<EmbyItem> list) {
            items = list == null ? new ArrayList<>() : list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_poster, parent, false);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            lp.leftMargin = com.fongmi.android.tv.utils.ResUtil.dp2px(6);
            lp.rightMargin = com.fongmi.android.tv.utils.ResUtil.dp2px(6);
            v.setLayoutParams(lp);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyItem it = items.get(position);
            h.name.setText(it.name == null ? "" : it.name);
            String url = "";
            try {
                if (api != null) url = api.imageUrl(it.id, it.imageTag, 400);
            } catch (Throwable ignored) {
            }
            // 无海报直接占位（服务端会 404），error/fallback 由图片层兜底，不闪白块
            ImgUtil.loadRounded(it.name, url, h.poster);
            // 角标与进度
            h.badge.setVisibility(View.GONE);
            h.progress.setVisibility(View.GONE);
            h.playedBadge.setVisibility(View.GONE);
            if (SEC_RESUME.equals(section) && it.runTimeTicks > 0 && it.positionTicks > 0) {
                h.progress.setVisibility(View.VISIBLE);
                h.progress.setProgress((int) (it.positionTicks * 1000L / it.runTimeTicks));
                long remainTicks = it.runTimeTicks - it.positionTicks;
                if (remainTicks > 0) {
                    h.badge.setVisibility(View.VISIBLE);
                    h.badge.setText(getContext().getString(R.string.emby_remain, fmtRemain(remainTicks)));
                }
            } else if (SEC_NEXTUP.equals(section) && it.indexNumber > 0) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(getContext().getString(R.string.emby_episode, it.indexNumber));
            } else {
                if (it.played) h.playedBadge.setVisibility(View.VISIBLE);
                if (it.communityRating > 0) {
                    h.badge.setVisibility(View.VISIBLE);
                    h.badge.setText(String.format("%.1f", it.communityRating));
                }
            }
            h.itemView.setOnClickListener(v -> {
                try {
                    EmbyNavigator.openDetail(getContext(), serverId, it.id);
                } catch (Throwable t) {
                    Notify.show("打不开详情");
                }
            });
            h.itemView.setOnLongClickListener(v -> {
                showCardMenu(it, rail);
                return true;
            });
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            Glide.with(h.poster).clear(h.poster);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView poster;
            final TextView name;
            final TextView badge;
            final TextView playedBadge;
            final ProgressBar progress;

            Holder(View v) {
                super(v);
                poster = v.findViewById(R.id.poster);
                name = v.findViewById(R.id.name);
                badge = v.findViewById(R.id.badge);
                playedBadge = v.findViewById(R.id.played_badge);
                progress = v.findViewById(R.id.progress);
            }
        }
    }

    // ---------- 媒体库入口适配器 ----------

    private class LibAdapter extends RecyclerView.Adapter<LibAdapter.Holder> {
        private List<EmbyItem> items = new ArrayList<>();

        void setItems(List<EmbyItem> list) {
            items = list == null ? new ArrayList<>() : list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_lib, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyItem it = items.get(position);
            h.name.setText(it.name == null ? "" : it.name);
            h.itemView.setOnClickListener(v ->
                    EmbyLibraryActivity.start(getContext(), serverId, EmbyLibraryActivity.MODE_VIEW, it.id, it.name));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
            }
        }
    }

    private String fmtRemain(long ticks) {
        long min = ticks / 600_000_000L;
        if (min < 60) return min + "分钟";
        return (min / 60) + "小时" + (min % 60) + "分";
    }

    // ---------- 卡片长按菜单（与 EmbyLibraryActivity 同文案） ----------

    private void showCardMenu(EmbyItem it, Rail rail) {
        if (it == null || rail == null) return;
        Context ctx = getContext();
        String[] titles = {
                it.played ? ctx.getString(R.string.emby_menu_unmark_played) : ctx.getString(R.string.emby_menu_mark_played),
                it.favorite ? ctx.getString(R.string.emby_menu_unfavorite) : ctx.getString(R.string.emby_menu_favorite)
        };
        new AlertDialog.Builder(ctx).setItems(titles, (d, which) -> {
            if (which == 0) togglePlayed(it, rail);
            else toggleFavorite(it, rail);
        }).show();
    }

    private void togglePlayed(EmbyItem it, Rail rail) {
        final EmbyApi a = api;
        if (a == null) {
            Notify.show(getContext().getString(R.string.emby_login_first));
            return;
        }
        final boolean toPlayed = !it.played;
        new Thread(() -> {
            boolean ok = false;
            try {
                if (toPlayed) a.markPlayed(it.id);
                else a.unmarkPlayed(it.id);
                ok = true;
            } catch (Exception ignored) {
            }
            final boolean r = ok;
            App.post(() -> {
                if (!r) {
                    Notify.show(getContext().getString(R.string.emby_action_fail));
                    return;
                }
                it.played = toPlayed;
                rail.refreshCard(it);
                Notify.show(getContext().getString(toPlayed ? R.string.emby_action_mark_played_ok : R.string.emby_action_unmark_played_ok));
            });
        }).start();
    }

    private void toggleFavorite(EmbyItem it, Rail rail) {
        final EmbyApi a = api;
        if (a == null) {
            Notify.show(getContext().getString(R.string.emby_login_first));
            return;
        }
        final boolean toFav = !it.favorite;
        new Thread(() -> {
            boolean ok = false;
            try {
                if (toFav) a.favorite(it.id);
                else a.unfavorite(it.id);
                ok = true;
            } catch (Exception ignored) {
            }
            final boolean r = ok;
            App.post(() -> {
                if (!r) {
                    Notify.show(getContext().getString(R.string.emby_action_fail));
                    return;
                }
                it.favorite = toFav;
                // 收藏 rail 里取消收藏：卡片直接抽掉
                if (!toFav && SEC_FAV.equals(rail.key)) rail.removeCard(it);
                else rail.refreshCard(it);
                Notify.show(getContext().getString(toFav ? R.string.emby_action_fav_ok : R.string.emby_action_unfav_ok));
            });
        }).start();
    }
}
