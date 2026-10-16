package com.fongmi.android.tv.ui.view;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.emby.EmbyApi;
import com.fongmi.android.tv.emby.EmbyItem;
import com.fongmi.android.tv.emby.EmbyMode;
import com.fongmi.android.tv.emby.EmbyNavigator;
import com.fongmi.android.tv.emby.EmbySearchHelper;
import com.fongmi.android.tv.emby.EmbyStore;
import com.fongmi.android.tv.emby.EmbyStore.EmbyServer;
import com.fongmi.android.tv.ui.activity.EmbyLibraryActivity;
import com.fongmi.android.tv.ui.activity.EmbyServerActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Emby 专属首页视图（简洁派重做版）。
 *
 * 版式：顶栏（左服切换 / 中服名 / 右搜索+分类）→ 继续观看（小横卡）
 * → 勾选的媒体库内容行 → 我的收藏 → 最新添加 → 接着看下一集（细长行）。
 * 空栏目自动不显示；栏目显隐偏好不再使用。
 *
 * 流畅硬指标落点：
 * 1. 视图树只建一次：bind 幂等（同服直接返回），切换只做显隐；
 * 2. 缓存读+解析全在后台线程，主线程只做 setItems；
 * 3. 五路请求并行，到齐后一次 post 更新；DiffUtil 最小刷新；
 * 4. setActive(false) 清掉在飞的 Glide 请求；
 * 5. 图片一律服务端缩图 maxWidth<=400。
 *
 * 对外 API（EmbyMode / ConfigHistoryDialog / EmbyServerActivity 调用，语义不变）：
 * bind(serverId) / refresh() / setActive(boolean) / release()
 * isShowing() / bindCurrent() / hideCurrent() / refreshCurrent()
 */
public class EmbyHomeView extends FrameLayout {

    public static final String SEC_RESUME = "resume";
    public static final String SEC_VIEWS = "views";
    public static final String SEC_FAV = "fav";
    public static final String SEC_LATEST = "latest";
    public static final String SEC_NEXTUP = "nextup";

    private static final int LIB_LIMIT = 24;

    private static volatile boolean sShowing = false;
    private static WeakReference<EmbyHomeView> sCurrent;

    /** 专属首页当前是否在展示（给线路弹窗做互斥选中态用）。 */
    public static boolean isShowing() {
        return sShowing;
    }

    public static void refreshCurrent() {
        EmbyHomeView v = sCurrent == null ? null : sCurrent.get();
        if (v != null) v.refresh();
    }

    public static void hideCurrent() {
        EmbyHomeView v = sCurrent == null ? null : sCurrent.get();
        if (v != null) v.setActive(false);
    }

    public static void bindCurrent(String serverId) {
        EmbyHomeView v = sCurrent == null ? null : sCurrent.get();
        if (v != null) v.bind(serverId);
    }

    private View btnSwitch;
    private TextView tvServer;
    private View btnCats;
    private View btnSearch;
    private ViewGroup container;
    private ViewGroup libContainer;
    private View pageEmpty;
    private String serverId;
    private EmbyApi api;
    private int gen;
    private boolean built;
    private ExecutorService pool;
    /** 本轮网络拉取是否遇到登录失效（P0-4 调用方引导侧，失败分支专用，不碰加载主逻辑）。 */
    private final AtomicBoolean authExpiredRun = new AtomicBoolean(false);

    private Section resumeSec;
    private Section favSec;
    private Section latestSec;
    private Section nextupSec;
    private final Map<String, Section> libSecs = new LinkedHashMap<>();

    private List<EmbyItem> viewsData = new ArrayList<>();
    private final Map<String, List<EmbyItem>> libData = new HashMap<>();
    private List<EmbyItem> resumeItems = new ArrayList<>();
    private List<EmbyItem> favItems = new ArrayList<>();
    private List<EmbyItem> latestItems = new ArrayList<>();
    private List<EmbyItem> nextupItems = new ArrayList<>();

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
        btnSwitch = findViewById(R.id.btn_switch);
        tvServer = findViewById(R.id.tv_server);
        btnCats = findViewById(R.id.btn_cats);
        btnSearch = findViewById(R.id.btn_search);
        container = findViewById(R.id.container);
        pageEmpty = findViewById(R.id.tv_page_empty);
        btnSwitch.setOnClickListener(v -> openServerSwitcher());
        btnCats.setOnClickListener(v -> openCategories());
        btnSearch.setOnClickListener(v -> openSearch());
        applyWindowInsets(context);
    }

    /**
     * 视图盖满整窗：顶栏顶部让出状态栏高度，滚动区底部让出胶囊导航高度+间距。
     */
    private void applyWindowInsets(Context context) {
        try {
            int statusH = 0;
            int sid = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (sid > 0) statusH = context.getResources().getDimensionPixelSize(sid);
            int navH = context.getResources().getDimensionPixelSize(R.dimen.xc_bottombar_height);
            int navMargin = context.getResources().getDimensionPixelSize(R.dimen.xc_bottombar_margin_bottom);
            int extra = (int) (24 * context.getResources().getDisplayMetrics().density + 0.5f);
            View topbar = findViewById(R.id.topbar);
            if (topbar != null) topbar.setPadding(topbar.getPaddingLeft(), statusH, topbar.getPaddingRight(), topbar.getPaddingBottom());
            if (container != null) {
                container.setPadding(container.getPaddingLeft(), container.getPaddingTop(),
                        container.getPaddingRight(), navH + navMargin + extra);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 绑定服务器：幂等——同服且已建好直接返回（EmbyMode.enter 的双调 bind 在此收敛为一次）。
     * 换服才重建视图树；同服重进只做显隐，由调用方 setActive 控制。
     */
    public void bind(String serverId) {
        if (TextUtils.isEmpty(serverId)) return;
        if (serverId.equals(this.serverId) && built) return;
        gen++;
        this.serverId = serverId;
        sCurrent = new WeakReference<>(this);
        EmbyServer server = findServer(serverId);
        updateHeader(server);
        if (server == null) {
            pageEmpty.setVisibility(View.VISIBLE);
            clearSections();
            built = false;
            return;
        }
        pageEmpty.setVisibility(View.GONE);
        api = newApi();
        if (pool == null || pool.isShutdown()) pool = Executors.newFixedThreadPool(4);
        buildSections();
        built = true;
        loadFresh();
    }

    /** 手动刷新：不重建视图，只重拉数据。 */
    public void refresh() {
        if (TextUtils.isEmpty(serverId) || !built) return;
        loadFresh();
    }

    /** 集成层切换首页时调用：只做显隐，不重建；隐藏时清掉在飞的图片请求。 */
    public void setActive(boolean active) {
        sShowing = active;
        setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) clearImages();
    }

    /** 释放：停掉后台加载，清图片，拆视图树。 */
    public void release() {
        gen++;
        sShowing = false;
        if (sCurrent != null && sCurrent.get() == this) sCurrent.clear();
        if (pool != null) pool.shutdownNow();
        pool = null;
        clearSections();
        serverId = null;
        built = false;
    }

    private void clearImages() {
        try {
            for (Section s : allSections()) s.clearImages();
        } catch (Throwable ignored) {
        }
    }

    private List<Section> allSections() {
        List<Section> all = new ArrayList<>();
        if (resumeSec != null) all.add(resumeSec);
        all.addAll(libSecs.values());
        if (favSec != null) all.add(favSec);
        if (latestSec != null) all.add(latestSec);
        if (nextupSec != null) all.add(nextupSec);
        return all;
    }

    private void clearSections() {
        resumeSec = null;
        favSec = null;
        latestSec = null;
        nextupSec = null;
        libSecs.clear();
        libData.clear();
        viewsData = new ArrayList<>();
        resumeItems = new ArrayList<>();
        favItems = new ArrayList<>();
        latestItems = new ArrayList<>();
        nextupItems = new ArrayList<>();
        if (container != null) container.removeAllViews();
        libContainer = null;
    }

    private void updateHeader(EmbyServer server) {
        try {
            tvServer.setText(server == null ? "" : server.displayName());
        } catch (Throwable ignored) {
        }
    }

    // ---------- 栏目搭建（每服只建一次） ----------

    private void buildSections() {
        container.removeAllViews();
        libSecs.clear();
        Context ctx = getContext();
        resumeSec = new Section(SEC_RESUME, ctx.getString(R.string.emby_rail_resume), true,
                new ResumeAdapter(), (Runnable) () -> openAll(EmbyLibraryActivity.MODE_RESUME, null, ctx.getString(R.string.emby_rail_resume)));
        container.addView(resumeSec.root);
        libContainer = new LinearLayout(ctx);
        libContainer.setOrientation(LinearLayout.VERTICAL);
        libContainer.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(libContainer);
        favSec = new Section(SEC_FAV, ctx.getString(R.string.emby_rail_fav), true,
                new PosterAdapter(SEC_FAV), (Runnable) () -> openAll(EmbyLibraryActivity.MODE_FAV, null, ctx.getString(R.string.emby_rail_fav)));
        container.addView(favSec.root);
        latestSec = new Section(SEC_LATEST, ctx.getString(R.string.emby_rail_latest), true,
                new PosterAdapter(SEC_LATEST), (Runnable) () -> openAll(EmbyLibraryActivity.MODE_LATEST, null, ctx.getString(R.string.emby_rail_latest)));
        container.addView(latestSec.root);
        nextupSec = new Section(SEC_NEXTUP, ctx.getString(R.string.emby_rail_nextup), false,
                new NextUpAdapter(), (Runnable) () -> openAll(EmbyLibraryActivity.MODE_NEXTUP, null, ctx.getString(R.string.emby_rail_nextup)));
        container.addView(nextupSec.root);
        rebuildLibRails();
    }

    /** 按分类勾选重建媒体库内容行：复用已有 rail 与内存数据，只补拉新增的库。
     * 返回本轮新建的 rail 的 viewId（调用方做 Diff 更新时跳过它们，避免二次 dispatch）。 */
    private Set<String> rebuildLibRails() {
        Set<String> created = new HashSet<>();
        if (libContainer == null) return created;
        Set<String> checked = checkedViews(viewsData);
        // 撤掉未勾选的
        for (String id : new ArrayList<>(libSecs.keySet())) {
            if (!checked.contains(id)) {
                Section s = libSecs.remove(id);
                if (s != null) libContainer.removeView(s.root);
                libData.remove(id);
            }
        }
        // 按 views 顺序补齐
        List<String> needFetch = new ArrayList<>();
        for (EmbyItem v : viewsData) {
            if (v == null || TextUtils.isEmpty(v.id) || !checked.contains(v.id)) continue;
            Section s = libSecs.get(v.id);
            if (s == null) {
                final String vid = v.id;
                final String vname = v.name;
                s = new Section("lib:" + vid, vname, true, new PosterAdapter("lib:" + vid),
                        (Runnable) () -> EmbyLibraryActivity.start(getContext(), serverId, EmbyLibraryActivity.MODE_VIEW, vid, vname));
                s.tvAll.setText(R.string.emby_more);
                libSecs.put(vid, s);
                libContainer.addView(s.root);
                created.add(vid);
                List<EmbyItem> mem = libData.get(vid);
                if (mem != null) s.setData(mem, null);
                else {
                    s.showLoading();
                    needFetch.add(vid);
                }
            }
        }
        if (!needFetch.isEmpty() && api != null && built) {
            final int myGen = gen;
            final String sid = serverId;
            final EmbyApi a = api;
            final List<String> ids = new ArrayList<>(needFetch);
            bg().execute(() -> {
                Map<String, List<EmbyItem>> got = new LinkedHashMap<>();
                for (String vid : ids) {
                    if (myGen != gen) return;
                    got.put(vid, quietLibItems(a, vid));
                }
                if (myGen != gen) return;
                App.post(() -> {
                    if (myGen != gen || !sid.equals(serverId)) return;
                    for (Map.Entry<String, List<EmbyItem>> e : got.entrySet()) {
                        libData.put(e.getKey(), e.getValue());
                        Section s = libSecs.get(e.getKey());
                        if (s != null) s.setData(e.getValue(), null);
                    }
                    saveCacheBg(sid);
                });
            });
        }
        return created;
    }

    private void openAll(String mode, String parentId, String title) {
        try {
            EmbyLibraryActivity.start(getContext(), serverId, mode, parentId, title);
        } catch (Throwable t) {
            Notify.show("打不开列表");
        }
    }

    private EmbyApi newApi() {
        EmbyServer s = findServer(serverId);
        if (s == null) return null;
        return new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
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

    private ExecutorService bg() {
        if (pool == null || pool.isShutdown()) pool = Executors.newFixedThreadPool(4);
        return pool;
    }

    // ---------- 后台拉新（并行→一次更新） ----------

    private void loadFresh() {
        final int myGen = ++gen;
        final String sid = serverId;
        final EmbyApi a = api;
        if (a == null || TextUtils.isEmpty(sid)) return;
        authExpiredRun.set(false);
        resetPageEmpty();
        showSkeletons();
        bg().execute(() -> {
            // 1) 缓存先出：读+解析都在后台
            FreshData cache = loadCacheBg(sid);
            if (myGen != gen) return;
            if (cache != null && cache.hasAny()) {
                App.post(() -> {
                    if (myGen == gen) applyData(cache, false);
                });
            }
            // 2) 并行拉新
            ExecutorService fp = Executors.newFixedThreadPool(4);
            try {
                Future<List<EmbyItem>> fResume = fp.submit(() -> quiet(() -> a.resume()));
                Future<List<EmbyItem>> fFav = fp.submit(() -> quiet(() -> a.favoriteItems()));
                Future<List<EmbyItem>> fLatest = fp.submit(() -> quiet(() -> a.latest()));
                Future<List<EmbyItem>> fNext = fp.submit(() -> quiet(() -> a.nextUp()));
                Future<List<EmbyItem>> fViews = fp.submit(() -> quiet(() -> a.views()));
                List<EmbyItem> resume = get(fResume);
                List<EmbyItem> fav = get(fFav);
                List<EmbyItem> latest = get(fLatest);
                List<EmbyItem> nextup = get(fNext);
                List<EmbyItem> views = get(fViews);
                if (myGen != gen) return;
                Set<String> checked = checkedViews(views);
                Map<String, Future<List<EmbyItem>>> fLibs = new LinkedHashMap<>();
                for (EmbyItem v : views) {
                    if (v == null || TextUtils.isEmpty(v.id) || !checked.contains(v.id)) continue;
                    final String vid = v.id;
                    fLibs.put(vid, fp.submit(() -> quietLibItems(a, vid)));
                }
                Map<String, List<EmbyItem>> libs = new LinkedHashMap<>();
                for (Map.Entry<String, Future<List<EmbyItem>>> e : fLibs.entrySet()) libs.put(e.getKey(), get(e.getValue()));
                if (myGen != gen) return;
                FreshData fd = new FreshData();
                fd.resume = resume;
                fd.fav = fav;
                fd.latest = latest;
                fd.nextup = nextup;
                fd.views = views;
                fd.libs = libs;
                // 3) Diff 在后台算好
                fd.dResume = diff(resumeItems, resume);
                fd.dFav = diff(favItems, fav);
                fd.dLatest = diff(latestItems, latest);
                fd.dNextup = diff(nextupItems, nextup);
                fd.dLibs = new HashMap<>();
                for (Map.Entry<String, List<EmbyItem>> e : libs.entrySet()) {
                    List<EmbyItem> old = libData.get(e.getKey());
                    fd.dLibs.put(e.getKey(), diff(old == null ? new ArrayList<>() : old, e.getValue()));
                }
                if (myGen != gen) return;
                saveCacheBg(sid, fd);
                App.post(() -> {
                    if (myGen == gen) applyData(fd, true);
                });
            } finally {
                fp.shutdown();
            }
        });
    }

    private List<EmbyItem> quiet(Callable<List<EmbyItem>> c) {
        try {
            List<EmbyItem> r = c.call();
            return r == null ? new ArrayList<>() : r;
        } catch (Throwable t) {
            if (isAuthExpired(t)) authExpiredRun.set(true);
            return new ArrayList<>();
        }
    }

    private List<EmbyItem> quietLibItems(EmbyApi a, String viewId) {
        try {
            EmbyApi.ItemPage p = a.items(viewId, 0, LIB_LIMIT);
            return (p == null || p.items == null) ? new ArrayList<>() : p.items;
        } catch (Throwable t) {
            if (isAuthExpired(t)) authExpiredRun.set(true);
            return new ArrayList<>();
        }
    }

    /** 401 判定：文案来自 EmbyApi.throwIfAuthExpired，与全量页 isAuthExpired 同口径（只认这一种走引导）。 */
    private static boolean isAuthExpired(Throwable t) {
        while (t != null) {
            String m = t.getMessage();
            if (m != null && m.contains("登录已失效")) return true;
            t = t.getCause();
        }
        return false;
    }

    /** 整页提示位复位（每轮 loadFresh 主线程入口清掉上一轮的引导态）。 */
    private void resetPageEmpty() {
        try {
            pageEmpty.setVisibility(View.GONE);
            pageEmpty.setOnClickListener(null);
            if (pageEmpty instanceof TextView) ((TextView) pageEmpty).setText(R.string.emby_login_first);
        } catch (Throwable ignored) {
        }
    }

    private List<EmbyItem> get(Future<List<EmbyItem>> f) {
        try {
            List<EmbyItem> r = f.get();
            return r == null ? new ArrayList<>() : r;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private DiffUtil.DiffResult diff(List<EmbyItem> oldList, List<EmbyItem> newList) {
        try {
            return DiffUtil.calculateDiff(new ItemDiff(oldList, newList));
        } catch (Throwable t) {
            return null;
        }
    }

    private void showSkeletons() {
        try {
            for (Section s : allSections()) {
                if (!s.loaded) s.showLoading();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 缓存/新数据统一渲染：固定栏直接设值，媒体库行按勾选重排。 */
    private void applyData(FreshData fd, boolean fromNet) {
        if (!built) return;
        resumeItems = fd.resume;
        favItems = fd.fav;
        latestItems = fd.latest;
        nextupItems = fd.nextup;
        viewsData = fd.views == null ? new ArrayList<>() : fd.views;
        for (Map.Entry<String, List<EmbyItem>> e : fd.libs.entrySet()) libData.put(e.getKey(), e.getValue());
        resumeSec.setData(fd.resume, fromNet ? fd.dResume : null);
        favSec.setData(fd.fav, fromNet ? fd.dFav : null);
        latestSec.setData(fd.latest, fromNet ? fd.dLatest : null);
        nextupSec.setData(fd.nextup, fromNet ? fd.dNextup : null);
        Set<String> created = rebuildLibRails();
        // 补上 diff（缓存路径 diff 为 null，走全量刷新；本轮新建的 rail 已是新数据，跳过二次 dispatch）
        if (fromNet) {
            for (Map.Entry<String, DiffUtil.DiffResult> e : fd.dLibs.entrySet()) {
                if (created.contains(e.getKey())) continue;
                Section s = libSecs.get(e.getKey());
                List<EmbyItem> items = libData.get(e.getKey());
                if (s != null && items != null) s.setData(items, e.getValue());
            }
        }
        // P0-4 调用方引导：网络回刷里遇到登录失效、且所有栏目都空时，整页提示引导重登（文案与全量页一致）；
        // 其它失败照旧（空栏目自动不显示），不打扰。
        if (fromNet && authExpiredRun.get() && allSectionsEmpty(fd)) showAuthExpired();
    }

    /** 所有栏目都空（含媒体库行）才算整页空——登录失效时各栏同服同 token 会一起 401。 */
    private boolean allSectionsEmpty(FreshData fd) {
        if (fd == null) return true;
        if (!isEmpty(fd.resume) || !isEmpty(fd.fav) || !isEmpty(fd.latest) || !isEmpty(fd.nextup)) return false;
        if (!isEmpty(fd.views)) return false;
        if (fd.libs != null) {
            for (List<EmbyItem> l : fd.libs.values()) if (!isEmpty(l)) return false;
        }
        return true;
    }

    private static boolean isEmpty(List<EmbyItem> l) {
        return l == null || l.isEmpty();
    }

    /** 登录失效引导：复用整页提示位，点我去服务器管理页重登。 */
    private void showAuthExpired() {
        try {
            if (pageEmpty instanceof TextView) ((TextView) pageEmpty).setText(R.string.emby_auth_expired_go);
            pageEmpty.setOnClickListener(v -> {
                try {
                    Context ctx = getContext();
                    if (ctx instanceof Activity) EmbyServerActivity.start((Activity) ctx);
                } catch (Throwable ignored) {
                }
            });
            pageEmpty.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    private static class FreshData {
        List<EmbyItem> resume = new ArrayList<>();
        List<EmbyItem> fav = new ArrayList<>();
        List<EmbyItem> latest = new ArrayList<>();
        List<EmbyItem> nextup = new ArrayList<>();
        List<EmbyItem> views = new ArrayList<>();
        Map<String, List<EmbyItem>> libs = new LinkedHashMap<>();
        DiffUtil.DiffResult dResume, dFav, dLatest, dNextup;
        Map<String, DiffUtil.DiffResult> dLibs = new HashMap<>();

        boolean hasAny() {
            return !resume.isEmpty() || !fav.isEmpty() || !latest.isEmpty() || !nextup.isEmpty() || !libs.isEmpty();
        }
    }

    // ---------- 缓存（瘦身：去掉 overview 长文本） ----------

    private FreshData loadCacheBg(String sid) {
        FreshData fd = new FreshData();
        try {
            String json = EmbyStore.homeCache(sid);
            if (TextUtils.isEmpty(json)) return fd;
            JSONObject root = new JSONObject(json);
            fd.resume = parseItems(root.optJSONArray(SEC_RESUME));
            fd.fav = parseItems(root.optJSONArray(SEC_FAV));
            fd.latest = parseItems(root.optJSONArray(SEC_LATEST));
            fd.nextup = parseItems(root.optJSONArray(SEC_NEXTUP));
            fd.views = parseItems(root.optJSONArray(SEC_VIEWS));
            JSONObject libs = root.optJSONObject("libs");
            if (libs != null) {
                for (EmbyItem v : fd.views) {
                    if (v == null || TextUtils.isEmpty(v.id)) continue;
                    JSONArray arr = libs.optJSONArray(v.id);
                    if (arr != null) fd.libs.put(v.id, parseItems(arr));
                }
            }
        } catch (Throwable ignored) {
        }
        return fd;
    }

    private void saveCacheBg(String sid) {
        saveCacheBg(sid, null);
    }

    private void saveCacheBg(String sid, FreshData fd) {
        try {
            JSONObject root = new JSONObject();
            List<EmbyItem> r = fd != null ? fd.resume : resumeItems;
            List<EmbyItem> f = fd != null ? fd.fav : favItems;
            List<EmbyItem> l = fd != null ? fd.latest : latestItems;
            List<EmbyItem> n = fd != null ? fd.nextup : nextupItems;
            List<EmbyItem> v = fd != null ? fd.views : viewsData;
            root.put(SEC_RESUME, toJson(r));
            root.put(SEC_FAV, toJson(f));
            root.put(SEC_LATEST, toJson(l));
            root.put(SEC_NEXTUP, toJson(n));
            root.put(SEC_VIEWS, toJson(v));
            JSONObject libs = new JSONObject();
            Map<String, List<EmbyItem>> lm = (fd != null && fd.libs != null) ? fd.libs : libData;
            for (Map.Entry<String, List<EmbyItem>> e : lm.entrySet()) libs.put(e.getKey(), toJson(e.getValue()));
            root.put("libs", libs);
            EmbyStore.saveHomeCache(sid, root.toString());
        } catch (Throwable ignored) {
        }
    }

    private JSONArray toJson(List<EmbyItem> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (EmbyItem it : list) {
            if (it == null || TextUtils.isEmpty(it.id)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("name", it.name);
                o.put("type", it.type);
                o.put("imageTag", it.imageTag);
                o.put("backdropTag", it.backdropTag);
                o.put("positionTicks", it.positionTicks);
                o.put("runTimeTicks", it.runTimeTicks);
                o.put("played", it.played);
                o.put("favorite", it.favorite);
                o.put("indexNumber", it.indexNumber);
                o.put("communityRating", it.communityRating);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        return arr;
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
                it.imageTag = o.optString("imageTag", "");
                it.backdropTag = o.optString("backdropTag", "");
                it.positionTicks = o.optLong("positionTicks", 0);
                it.runTimeTicks = o.optLong("runTimeTicks", 0);
                it.played = o.optBoolean("played", false);
                it.favorite = o.optBoolean("favorite", false);
                it.indexNumber = o.optInt("indexNumber", 0);
                it.communityRating = o.optDouble("communityRating", 0);
                if (!it.id.isEmpty()) list.add(it);
            } catch (Throwable ignored) {
            }
        }
        return list;
    }

    // ---------- 分类勾选（按服存，新库默认勾上） ----------

    private SharedPreferences catPrefs() {
        return getContext().getSharedPreferences("emby_home_cats", Context.MODE_PRIVATE);
    }

    private Set<String> checkedViews(List<EmbyItem> views) {
        Set<String> all = new HashSet<>();
        if (views != null) for (EmbyItem v : views) {
            if (v != null && !TextUtils.isEmpty(v.id)) all.add(v.id);
        }
        if (TextUtils.isEmpty(serverId)) return all;
        String raw = catPrefs().getString("cats_" + serverId, null);
        if (raw == null) {
            saveCats(all, all);
            return all;
        }
        try {
            JSONObject o = new JSONObject(raw);
            Set<String> checked = toSet(o.optJSONArray("checked"));
            Set<String> known = toSet(o.optJSONArray("known"));
            for (String id : all) if (!known.contains(id)) checked.add(id); // 新发现的库默认显示
            checked.retainAll(all);
            saveCats(checked, all);
            return checked;
        } catch (Throwable t) {
            return all;
        }
    }

    private void saveCats(Set<String> checked, Set<String> known) {
        try {
            JSONObject o = new JSONObject();
            o.put("checked", new JSONArray(new ArrayList<>(checked)));
            o.put("known", new JSONArray(new ArrayList<>(known)));
            catPrefs().edit().putString("cats_" + serverId, o.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private Set<String> toSet(JSONArray arr) {
        Set<String> s = new HashSet<>();
        if (arr == null) return s;
        for (int i = 0; i < arr.length(); i++) {
            String id = arr.optString(i, "");
            if (!id.isEmpty()) s.add(id);
        }
        return s;
    }

    // ---------- 顶栏：切服 / 分类 / 搜索 ----------

    private void openServerSwitcher() {
        Context ctx = getContext();
        List<EmbyServer> servers = new ArrayList<>();
        try {
            List<EmbyServer> all = EmbyStore.servers();
            if (all != null) for (EmbyServer s : all) if (s != null) servers.add(s);
        } catch (Throwable ignored) {
        }
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_emby_servers, null);
        RecyclerView rv = v.findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        AlertDialog d = new AlertDialog.Builder(ctx).setView(v).create();
        ServerAdapter adapter = new ServerAdapter(servers, s -> {
            d.dismiss();
            if (s.isLogin() && !s.id.equals(serverId)) EmbyMode.enter(s.id);
            else if (!s.isLogin()) Notify.show(ctx.getString(R.string.emby_login_first));
        });
        rv.setAdapter(adapter);
        v.findViewById(R.id.btn_manage).setOnClickListener(x -> {
            d.dismiss();
            try {
                if (ctx instanceof Activity) EmbyServerActivity.start((Activity) ctx);
            } catch (Throwable t) {
                Notify.show("打不开服务器管理");
            }
        });
        showDialog(d);
    }

    private void openCategories() {
        Context ctx = getContext();
        if (viewsData.isEmpty()) {
            Notify.show("媒体库还在加载，稍后再试");
            return;
        }
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_emby_categories, null);
        RecyclerView rv = v.findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        AlertDialog d = new AlertDialog.Builder(ctx).setView(v).create();
        Set<String> checked = new HashSet<>(checkedViews(viewsData));
        CatAdapter adapter = new CatAdapter(viewsData, checked, () -> {
            saveCats(checked, idsOf(viewsData));
            rebuildLibRails();
        });
        rv.setAdapter(adapter);
        v.findViewById(R.id.btn_done).setOnClickListener(x -> d.dismiss());
        showDialog(d);
    }

    private Set<String> idsOf(List<EmbyItem> views) {
        Set<String> s = new HashSet<>();
        for (EmbyItem v : views) if (v != null && !TextUtils.isEmpty(v.id)) s.add(v.id);
        return s;
    }

    private void openSearch() {
        Context ctx = getContext();
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_emby_search, null);
        EditText et = v.findViewById(R.id.et_key);
        RecyclerView rv = v.findViewById(R.id.rv);
        TextView tvEmpty = v.findViewById(R.id.tv_empty);
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        AlertDialog d = new AlertDialog.Builder(ctx).setView(v).create();
        Runnable go = () -> {
            String kw = et.getText() == null ? "" : et.getText().toString().trim();
            if (kw.isEmpty()) {
                Notify.show(ctx.getString(R.string.emby_need_keyword));
                return;
            }
            doSearch(kw, rv, tvEmpty);
        };
        v.findViewById(R.id.btn_go).setOnClickListener(x -> go.run());
        et.setOnEditorActionListener((tv, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                go.run();
                return true;
            }
            return false;
        });
        showDialog(d);
    }

    /** 全局搜索：全部已登录服并查（EmbySearchHelper 现有链），结果按服分组展示。 */
    private void doSearch(String kw, RecyclerView rv, TextView tvEmpty) {
        Context ctx = getContext();
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(R.string.emby_searching);
        rv.setAdapter(null);
        new Thread(() -> {
            List<EmbySearchHelper.ServerResult> rs;
            try {
                rs = EmbySearchHelper.searchAll(kw);
            } catch (Throwable t) {
                rs = new ArrayList<>();
            }
            final List<EmbySearchHelper.ServerResult> results = rs;
            App.post(() -> {
                int total = 0;
                for (EmbySearchHelper.ServerResult r : results) total += r.items.size();
                if (total == 0) {
                    tvEmpty.setText(R.string.emby_search_empty);
                    return;
                }
                tvEmpty.setVisibility(View.GONE);
                rv.setAdapter(new SearchGroupAdapter(ctx, results));
            });
        }).start();
    }

    private void showDialog(AlertDialog d) {
        try {
            d.show();
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------- 栏目行 ----------

    private interface Diffable {
        void apply(List<EmbyItem> items, DiffUtil.DiffResult diff);

        void clearImages();
    }

    private class Section {
        final String key;
        final View root;
        final TextView tvTitle;
        final TextView tvAll;
        final TextView tvEmpty;
        final View loading;
        final RecyclerView rv;
        final RecyclerView.Adapter<?> adapter;
        boolean loaded;

        Section(String key, String title, boolean horizontal, RecyclerView.Adapter<?> adapter, Runnable allClick) {
            this.key = key;
            this.adapter = adapter;
            Context ctx = getContext();
            root = LayoutInflater.from(ctx).inflate(R.layout.item_emby_rail, container, false);
            tvTitle = root.findViewById(R.id.tv_title);
            tvAll = root.findViewById(R.id.tv_all);
            tvEmpty = root.findViewById(R.id.tv_empty);
            loading = root.findViewById(R.id.loading);
            rv = root.findViewById(R.id.rv);
            tvTitle.setText(title);
            if (allClick == null) {
                tvAll.setVisibility(View.GONE);
            } else {
                tvAll.setOnClickListener(v -> allClick.run());
            }
            rv.setLayoutManager(new LinearLayoutManager(ctx,
                    horizontal ? LinearLayoutManager.HORIZONTAL : LinearLayoutManager.VERTICAL, false));
            if (!horizontal) rv.setNestedScrollingEnabled(false);
            rv.setAdapter(adapter);
            showLoading();
        }

        void showLoading() {
            loaded = false;
            root.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.GONE);
            loading.setVisibility(View.VISIBLE);
        }

        void setData(List<EmbyItem> items, DiffUtil.DiffResult diff) {
            loaded = true;
            loading.setVisibility(View.GONE);
            List<EmbyItem> list = items == null ? new ArrayList<>() : items;
            if (list.isEmpty()) {
                root.setVisibility(View.GONE);
                return;
            }
            root.setVisibility(View.VISIBLE);
            rv.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            ((Diffable) adapter).apply(list, diff);
        }

        void setError() {
            if (loaded) return;
            loading.setVisibility(View.GONE);
            root.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.emby_load_fail);
            tvEmpty.setOnClickListener(v -> {
                tvEmpty.setOnClickListener(null);
                showLoading();
                refresh();
            });
        }

        void clearImages() {
            try {
                ((Diffable) adapter).clearImages();
            } catch (Throwable ignored) {
            }
        }
    }

    private static class ItemDiff extends DiffUtil.Callback {
        private final List<EmbyItem> o;
        private final List<EmbyItem> n;

        ItemDiff(List<EmbyItem> o, List<EmbyItem> n) {
            this.o = o == null ? new ArrayList<>() : o;
            this.n = n == null ? new ArrayList<>() : n;
        }

        @Override
        public int getOldListSize() {
            return o.size();
        }

        @Override
        public int getNewListSize() {
            return n.size();
        }

        @Override
        public boolean areItemsTheSame(int op, int np) {
            return TextUtils.equals(o.get(op).id, n.get(np).id);
        }

        @Override
        public boolean areContentsTheSame(int op, int np) {
            EmbyItem a = o.get(op);
            EmbyItem b = n.get(np);
            return TextUtils.equals(a.name, b.name)
                    && TextUtils.equals(a.imageTag, b.imageTag)
                    && TextUtils.equals(a.backdropTag, b.backdropTag)
                    && a.played == b.played && a.favorite == b.favorite
                    && a.positionTicks == b.positionTicks && a.runTimeTicks == b.runTimeTicks
                    && a.indexNumber == b.indexNumber && a.communityRating == b.communityRating;
        }
    }

    // ---------- 海报适配器（收藏/最新/媒体库行） ----------

    private class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> implements Diffable {
        private final String section;
        private List<EmbyItem> items = new ArrayList<>();

        PosterAdapter(String section) {
            this.section = section;
        }

        @Override
        public void apply(List<EmbyItem> next, DiffUtil.DiffResult diff) {
            List<EmbyItem> list = next == null ? new ArrayList<>() : new ArrayList<>(next);
            if (diff != null) {
                items = list;
                diff.dispatchUpdatesTo(this);
            } else {
                items = list;
                notifyDataSetChanged();
            }
        }

        @Override
        public void clearImages() {
            try {
                RecyclerView rv = findRv(this);
                if (rv == null) return;
                for (int i = 0; i < rv.getChildCount(); i++) {
                    RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
                    if (h instanceof Holder) Glide.with(((Holder) h).poster).clear(((Holder) h).poster);
                }
            } catch (Throwable ignored) {
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_poster, parent, false);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            int m = (int) (6 * parent.getContext().getResources().getDisplayMetrics().density + 0.5f);
            lp.leftMargin = m;
            lp.rightMargin = m;
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
            ImgUtil.loadRounded(it.name, url, h.poster);
            h.badge.setVisibility(View.GONE);
            h.playedBadge.setVisibility(View.GONE);
            h.progress.setVisibility(View.GONE);
            if (it.played) h.playedBadge.setVisibility(View.VISIBLE);
            if (it.communityRating > 0) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(String.format("%.1f", it.communityRating));
            }
            h.itemView.setOnClickListener(v -> {
                try {
                    EmbyNavigator.openDetail(getContext(), serverId, it.id);
                } catch (Throwable t) {
                    Notify.show("打不开详情");
                }
            });
            h.itemView.setOnLongClickListener(v -> {
                showCardMenu(it, section);
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

    private RecyclerView findRv(RecyclerView.Adapter<?> adapter) {
        for (Section s : allSections()) {
            if (s.adapter == adapter) return s.rv;
        }
        return null;
    }

    // ---------- 继续观看小横卡 ----------

    private class ResumeAdapter extends RecyclerView.Adapter<ResumeAdapter.Holder> implements Diffable {
        private List<EmbyItem> items = new ArrayList<>();

        @Override
        public void apply(List<EmbyItem> next, DiffUtil.DiffResult diff) {
            List<EmbyItem> list = next == null ? new ArrayList<>() : new ArrayList<>(next);
            if (diff != null) {
                items = list;
                diff.dispatchUpdatesTo(this);
            } else {
                items = list;
                notifyDataSetChanged();
            }
        }

        @Override
        public void clearImages() {
            try {
                RecyclerView rv = findRv(this);
                if (rv == null) return;
                for (int i = 0; i < rv.getChildCount(); i++) {
                    RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
                    if (h instanceof Holder) Glide.with(((Holder) h).thumb).clear(((Holder) h).thumb);
                }
            } catch (Throwable ignored) {
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_resume_card, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyItem it = items.get(position);
            h.name.setText(it.name == null ? "" : it.name);
            int pct = it.runTimeTicks > 0 ? (int) (it.positionTicks * 100L / it.runTimeTicks) : 0;
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;
            if (EmbyItem.TYPE_EPISODE.equals(it.type) && it.indexNumber > 0) {
                h.sub.setText(getContext().getString(R.string.emby_resume_episode, it.indexNumber, pct));
            } else {
                h.sub.setText(getContext().getString(R.string.emby_resume_movie, pct));
            }
            h.progress.setProgress(pct * 10);
            String url = "";
            try {
                if (api != null) {
                    url = !TextUtils.isEmpty(it.backdropTag) ? api.backdropUrl(it.id, 400)
                            : api.imageUrl(it.id, it.imageTag, 400);
                }
            } catch (Throwable ignored) {
            }
            ImgUtil.loadRounded(it.name, url, h.thumb);
            h.itemView.setOnClickListener(v -> {
                try {
                    EmbyNavigator.openDetail(getContext(), serverId, it.id);
                } catch (Throwable t) {
                    Notify.show("打不开详情");
                }
            });
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            Glide.with(h.thumb).clear(h.thumb);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView name;
            final TextView sub;
            final ProgressBar progress;

            Holder(View v) {
                super(v);
                thumb = v.findViewById(R.id.thumb);
                name = v.findViewById(R.id.name);
                sub = v.findViewById(R.id.sub);
                progress = v.findViewById(R.id.progress);
            }
        }
    }

    // ---------- 接着看下一集细长行 ----------

    private class NextUpAdapter extends RecyclerView.Adapter<NextUpAdapter.Holder> implements Diffable {
        private List<EmbyItem> items = new ArrayList<>();

        @Override
        public void apply(List<EmbyItem> next, DiffUtil.DiffResult diff) {
            List<EmbyItem> list = next == null ? new ArrayList<>() : new ArrayList<>(next);
            if (diff != null) {
                items = list;
                diff.dispatchUpdatesTo(this);
            } else {
                items = list;
                notifyDataSetChanged();
            }
        }

        @Override
        public void clearImages() {
            try {
                RecyclerView rv = findRv(this);
                if (rv == null) return;
                for (int i = 0; i < rv.getChildCount(); i++) {
                    RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
                    if (h instanceof Holder) Glide.with(((Holder) h).thumb).clear(((Holder) h).thumb);
                }
            } catch (Throwable ignored) {
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_nextup_row, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyItem it = items.get(position);
            h.name.setText(it.name == null ? "" : it.name);
            String sub = it.indexNumber > 0
                    ? getContext().getString(R.string.emby_nextup_sub, it.indexNumber, fmtMin(it.runTimeTicks))
                    : fmtMin(it.runTimeTicks);
            h.sub.setText(sub);
            String url = "";
            try {
                if (api != null) url = api.imageUrl(it.id, it.imageTag, 400);
            } catch (Throwable ignored) {
            }
            ImgUtil.loadRounded(it.name, url, h.thumb);
            View.OnClickListener open = v -> {
                try {
                    EmbyNavigator.openDetail(getContext(), serverId, it.id);
                } catch (Throwable t) {
                    Notify.show("打不开详情");
                }
            };
            h.itemView.setOnClickListener(open);
            h.play.setOnClickListener(v -> {
                try {
                    EmbyNavigator.play(getContext(), serverId, it.id, it.name);
                } catch (Throwable t) {
                    open.onClick(v);
                }
            });
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            Glide.with(h.thumb).clear(h.thumb);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView name;
            final TextView sub;
            final View play;

            Holder(View v) {
                super(v);
                thumb = v.findViewById(R.id.thumb);
                name = v.findViewById(R.id.name);
                sub = v.findViewById(R.id.sub);
                play = v.findViewById(R.id.btn_play);
            }
        }
    }

    private String fmtMin(long ticks) {
        long min = ticks / 600_000_000L;
        if (min <= 0) return "";
        return min + "分钟";
    }

    // ---------- 切服弹窗 ----------

    private static class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.Holder> {
        interface Pick {
            void onPick(EmbyServer s);
        }

        private final List<EmbyServer> servers;
        private final Pick pick;
        private final String currentId;

        ServerAdapter(List<EmbyServer> servers, Pick pick) {
            this.servers = servers;
            this.pick = pick;
            String cur = "";
            try {
                cur = EmbyMode.activeServerId();
            } catch (Throwable ignored) {
            }
            this.currentId = cur;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_server_row, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyServer s = servers.get(position);
            h.name.setText(s.displayName());
            h.user.setText(s.userName == null ? "" : s.userName);
            h.current.setVisibility(s.id.equals(currentId) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> pick.onPick(s));
        }

        @Override
        public int getItemCount() {
            return servers.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView user;
            final TextView current;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                user = v.findViewById(R.id.user);
                current = v.findViewById(R.id.current);
            }
        }
    }

    // ---------- 分类面板 ----------

    private static class CatAdapter extends RecyclerView.Adapter<CatAdapter.Holder> {
        interface Changed {
            void onChanged();
        }

        private final List<EmbyItem> views;
        private final Set<String> checked;
        private final Changed changed;

        CatAdapter(List<EmbyItem> views, Set<String> checked, Changed changed) {
            this.views = views;
            this.checked = checked;
            this.changed = changed;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_category_row, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyItem v = views.get(position);
            h.name.setText(v.name == null ? "" : v.name);
            h.kind.setText(h.itemView.getContext().getString(R.string.emby_cats_kind_lib));
            boolean on = checked.contains(v.id);
            h.check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            h.itemView.setOnClickListener(x -> {
                if (checked.contains(v.id)) checked.remove(v.id);
                else checked.add(v.id);
                notifyItemChanged(position);
                changed.onChanged();
            });
        }

        @Override
        public int getItemCount() {
            return views.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView kind;
            final TextView check;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                kind = v.findViewById(R.id.kind);
                check = v.findViewById(R.id.check);
            }
        }
    }

    // ---------- 全局搜索结果（按服分组） ----------

    private static class SearchGroupAdapter extends RecyclerView.Adapter<SearchGroupAdapter.Holder> {
        private final Context ctx;
        private final List<EmbySearchHelper.ServerResult> groups;

        SearchGroupAdapter(Context ctx, List<EmbySearchHelper.ServerResult> groups) {
            this.ctx = ctx;
            this.groups = new ArrayList<>();
            for (EmbySearchHelper.ServerResult r : groups) {
                if (r != null && r.items != null && !r.items.isEmpty()) this.groups.add(r);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_rail, parent, false);
            // 复用 rail 布局：标题=服名，无"全部"，网格海报
            v.findViewById(R.id.tv_all).setVisibility(View.GONE);
            v.findViewById(R.id.loading).setVisibility(View.GONE);
            v.findViewById(R.id.tv_empty).setVisibility(View.GONE);
            v.findViewById(R.id.rv).setVisibility(View.VISIBLE);
            RecyclerView rv = v.findViewById(R.id.rv);
            rv.setLayoutManager(new GridLayoutManager(parent.getContext(), 3));
            rv.setNestedScrollingEnabled(false);
            return new Holder(v, rv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbySearchHelper.ServerResult g = groups.get(position);
            h.title.setText(g.server == null ? "" : g.server.displayName());
            ResultAdapter adapter = new ResultAdapter(ctx, g);
            h.rv.setAdapter(adapter);
        }

        @Override
        public int getItemCount() {
            return groups.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final RecyclerView rv;

            Holder(View v, RecyclerView rv) {
                super(v);
                title = v.findViewById(R.id.tv_title);
                this.rv = rv;
            }
        }
    }

    private static class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.Holder> {
        private final Context ctx;
        private final EmbySearchHelper.ServerResult group;

        ResultAdapter(Context ctx, EmbySearchHelper.ServerResult group) {
            this.ctx = ctx;
            this.group = group;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_poster, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyItem it = group.items.get(position);
            h.name.setText(it.name == null ? "" : it.name);
            h.badge.setVisibility(View.GONE);
            h.playedBadge.setVisibility(View.GONE);
            h.progress.setVisibility(View.GONE);
            String url = "";
            try {
                EmbyServer s = group.server;
                if (s != null) url = new EmbyApi(s.baseUrl, s.token, s.userId, s.id).imageUrl(it.id, it.imageTag, 400);
            } catch (Throwable ignored) {
            }
            ImgUtil.loadRounded(it.name, url, h.poster);
            h.itemView.setOnClickListener(v -> {
                try {
                    EmbyNavigator.openDetail(ctx, group.server.id, it.id);
                } catch (Throwable t) {
                    Notify.show("打不开详情");
                }
            });
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            Glide.with(h.poster).clear(h.poster);
        }

        @Override
        public int getItemCount() {
            return group.items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
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

    // ---------- 卡片长按菜单（标记已看/收藏，与列表页同文案） ----------

    private void showCardMenu(EmbyItem it, String section) {
        if (it == null) return;
        Context ctx = getContext();
        String[] titles = {
                it.played ? ctx.getString(R.string.emby_menu_unmark_played) : ctx.getString(R.string.emby_menu_mark_played),
                it.favorite ? ctx.getString(R.string.emby_menu_unfavorite) : ctx.getString(R.string.emby_menu_favorite)
        };
        new AlertDialog.Builder(ctx).setItems(titles, (d, which) -> {
            if (which == 0) togglePlayed(it, section);
            else toggleFavorite(it, section);
        }).show();
    }

    private Section sectionOf(String key) {
        if (SEC_FAV.equals(key)) return favSec;
        if (SEC_LATEST.equals(key)) return latestSec;
        if (key != null && key.startsWith("lib:")) return libSecs.get(key.substring(4));
        return null;
    }

    private void togglePlayed(EmbyItem it, String section) {
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
                Section s = sectionOf(section);
                if (s != null && s.adapter instanceof PosterAdapter) {
                    PosterAdapter pa = (PosterAdapter) s.adapter;
                    int idx = pa.items.indexOf(it);
                    if (idx >= 0) pa.notifyItemChanged(idx);
                }
                Notify.show(getContext().getString(toPlayed ? R.string.emby_action_mark_played_ok : R.string.emby_action_unmark_played_ok));
            });
        }).start();
    }

    private void toggleFavorite(EmbyItem it, String section) {
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
                Section s = sectionOf(section);
                if (s != null && s.adapter instanceof PosterAdapter) {
                    PosterAdapter pa = (PosterAdapter) s.adapter;
                    int idx = pa.items.indexOf(it);
                    if (idx >= 0) {
                        // 收藏栏里取消收藏：卡片直接抽掉
                        if (!toFav && SEC_FAV.equals(section)) {
                            pa.items.remove(idx);
                            pa.notifyItemRemoved(idx);
                            if (pa.getItemCount() == 0) s.setData(new ArrayList<>(), null);
                        } else {
                            pa.notifyItemChanged(idx);
                        }
                    }
                }
                Notify.show(getContext().getString(toFav ? R.string.emby_action_fav_ok : R.string.emby_action_unfav_ok));
            });
        }).start();
    }
}
