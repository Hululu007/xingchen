package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityKtvBinding;
import com.fongmi.android.tv.ktv.KtvServer;
import com.fongmi.android.tv.ktv.KtvSong;
import com.fongmi.android.tv.ktv.KtvStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KTV 点歌台（独立页）：双歌源——他的点播源（主）/ 点歌服务器（/v2/ 协议）。
 * 翻歌、搜歌、点歌进队列；队列在这管（置顶/删/清），开唱去唱歌页。
 */
public class KtvActivity extends BaseActivity {

    private static final int BG = 0xFF0B0D12;
    private static final int PANEL = 0xFF171B26;
    private static final int LINE = 0xFF2A3040;
    private static final int YELLOW = 0xFFFFD34D;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int SUB = 0xFF9AA3B2;
    private static final int BODY = 0xFFC7CEDA;

    private enum Tab {HOT, BROWSE, SEARCH, SINGERS, SINGER_SONGS, VOD_RESULTS, VOD_SONGS, HISTORY, FAVORITES, QUEUE}

    private ActivityKtvBinding binding;
    private RecyclerView list;
    private TextView statusText;
    private TextView queueInfo;
    private TextView modeBtn;
    private TextView serverBar;
    private TextView vodBar;
    private LinearLayout chipRow;
    private final SongAdapter adapter = new SongAdapter();

    private Tab tab = Tab.HOT;
    private final List<KtvSong> songs = new ArrayList<>();
    private final List<String> singers = new ArrayList<>();
    private List<KtvServer.Category> languages = new ArrayList<>();
    private String curLanguage = "";
    private String curSinger = "";
    private int page = 1;
    private boolean loading;
    private boolean noMore;
    private String lastQuery = "";
    private Vod curVod;
    private String vodFlag = "";
    private String autoOrderQuery; // 语音「点歌X」带进来的：搜到后自动点上第一首

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KtvActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityKtvBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        buildUi();
        applyMode();
        showTab(KtvStore.useServer() ? Tab.HOT : Tab.VOD_RESULTS);
        String oq = getIntent().getStringExtra("ktv_order_query");
        if (oq != null && !oq.trim().isEmpty()) {
            autoOrderQuery = oq.trim();
            lastQuery = autoOrderQuery;
            doSearch(autoOrderQuery);
        }
    }

    private void finishAutoOrder(KtvSong s) {
        autoOrderQuery = null;
        boolean wasEmpty = KtvStore.queue().isEmpty();
        KtvStore.addToQueue(s);
        toast("已点上：" + s.title());
        refreshQueueInfo();
        adapter.notifyDataSetChanged();
        if (wasEmpty) KtvSingActivity.start(this);
    }

    // ================= UI 骨架 =================

    private void buildUi() {
        FrameLayout root = binding.ktvRoot;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(10));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 26, WHITE);
        back.setPadding(dp(2), dp(2), dp(14), dp(2));
        back.setOnClickListener(v -> onBack());
        top.addView(back);
        TextView title = text("KTV 点歌台", 20, WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        modeBtn = pill(KtvStore.useServer() ? "歌源：点歌服务器" : "歌源：点播源", YELLOW, 0xFF232838);
        modeBtn.setOnClickListener(v -> toggleMode());
        top.addView(modeBtn);
        col.addView(top);

        serverBar = text("", 13, BODY);
        serverBar.setPadding(dp(2), dp(10), dp(2), dp(2));
        serverBar.setOnClickListener(v -> editServer());
        col.addView(serverBar);

        vodBar = text("", 13, BODY);
        vodBar.setPadding(dp(2), dp(10), dp(2), dp(2));
        vodBar.setOnClickListener(v -> pickVodSite());
        col.addView(vodBar);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(0, dp(8), 0, dp(4));
        EditText input = new EditText(this);
        input.setHint("搜歌名、歌手");
        input.setHintTextColor(SUB);
        input.setTextColor(WHITE);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(round(PANEL, 12, true));
        input.setPadding(dp(14), dp(9), dp(14), dp(9));
        searchRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = pill("搜索", 0xFF14161C, YELLOW);
        go.setPadding(dp(20), dp(10), dp(20), dp(10));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.leftMargin = dp(10);
        searchRow.addView(go, glp);
        go.setOnClickListener(v -> doSearch(input.getText().toString().trim()));
        input.setOnEditorActionListener((v, actionId, e) -> {
            doSearch(input.getText().toString().trim());
            return true;
        });
        col.addView(searchRow);

        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chipRow);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(6);
        col.addView(chipScroll, clp);

        statusText = text("", 13, SUB);
        statusText.setPadding(dp(2), dp(8), dp(2), dp(4));
        statusText.setOnClickListener(v -> {
            if (tab == Tab.BROWSE && !languages.isEmpty()) pickLanguage();
        });
        col.addView(statusText);

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && !loading && !noMore && isPagedTab() && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3) {
                    loadPage(page + 1, true);
                }
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(8), 0, dp(2));
        queueInfo = text("", 14, BODY);
        bottom.addView(queueInfo, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = pill("清空", BODY, PANEL);
        clear.setOnClickListener(v -> {
            KtvStore.clearQueue();
            refreshQueueInfo();
            if (tab == Tab.QUEUE) showTab(Tab.QUEUE);
        });
        bottom.addView(clear);
        TextView sing = pill("开唱 ›", 0xFF14161C, YELLOW);
        sing.setPadding(dp(24), dp(11), dp(24), dp(11));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(10);
        bottom.addView(sing, slp);
        sing.setOnClickListener(v -> {
            if (KtvStore.queue().isEmpty()) {
                toast("先点几首歌再开唱");
                return;
            }
            KtvSingActivity.start(this);
        });
        col.addView(bottom);
    }

    private void onBack() {
        if (tab == Tab.SINGER_SONGS) {
            showTab(Tab.SINGERS);
        } else if (tab == Tab.VOD_SONGS) {
            showTab(Tab.VOD_RESULTS);
        } else {
            finish();
        }
    }

    // ================= 模式与歌源 =================

    private void toggleMode() {
        boolean toServer = !KtvStore.useServer();
        if (toServer && KtvStore.baseUrl().isEmpty()) {
            editServer();
            toast("先填点歌服务器地址");
            return;
        }
        KtvStore.setUseServer(toServer);
        applyMode();
        showTab(toServer ? Tab.HOT : Tab.VOD_RESULTS);
    }

    private void applyMode() {
        boolean server = KtvStore.useServer();
        modeBtn.setText(server ? "歌源：点歌服务器" : "歌源：点播源");
        serverBar.setVisibility(server ? View.VISIBLE : View.GONE);
        vodBar.setVisibility(server ? View.GONE : View.VISIBLE);
        if (server) {
            String u = KtvStore.baseUrl();
            serverBar.setText("服务器：" + (u.isEmpty() ? "未设置（点这里填）" : u) + "　✎");
        } else {
            Site s = vodSite();
            vodBar.setText("点播源：" + (s == null ? "还没选（点这里选）" : s.getName()) + "　换 ›");
        }
        buildChips();
    }

    private void editServer() {
        EditText input = new EditText(this);
        input.setText(KtvStore.baseUrl());
        input.setHint("例如 http://192.168.1.10:8080");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("点歌服务器地址")
                .setView(input)
                .setNegativeButton("取消", null)
                .setNeutralButton("测试", (d, w) -> {
                    String u = input.getText().toString().trim();
                    KtvStore.setBaseUrl(u);
                    applyMode();
                    testServer();
                })
                .setPositiveButton("保存", (d, w) -> {
                    KtvStore.setBaseUrl(input.getText().toString().trim());
                    applyMode();
                    if (KtvStore.useServer()) showTab(Tab.HOT);
                })
                .show();
    }

    private void testServer() {
        String u = KtvStore.baseUrl();
        if (u.isEmpty()) {
            toast("先填地址");
            return;
        }
        toast("正在试连…");
        Task.execute(() -> {
            boolean ok = new KtvServer(u).ping();
            runOnUiThread(() -> toast(ok ? "通了，这个地址能用" : "没连上，检查地址和网络"));
        });
    }

    private Site vodSite() {
        try {
            String key = com.github.catvod.utils.Prefers.getString("ktv_vod_site", "");
            List<Site> all = VodConfig.get().getSites();
            for (Site s : all) if (s.getKey() != null && s.getKey().equals(key)) return s;
            for (Site s : all) if (MusicActivity.looksMusic(s)) return s;
            Site home = VodConfig.get().getHome();
            return home;
        } catch (Throwable t) {
            return null;
        }
    }

    private void pickVodSite() {
        List<Site> all;
        try {
            all = VodConfig.get().getSites();
        } catch (Throwable t) {
            all = new ArrayList<>();
        }
        if (all.isEmpty()) {
            toast("还没有点播源");
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).getName();
        List<Site> finalAll = all;
        new AlertDialog.Builder(this)
                .setTitle("选个源当歌源")
                .setItems(names, (d, w) -> {
                    com.github.catvod.utils.Prefers.put("ktv_vod_site", finalAll.get(w).getKey());
                    applyMode();
                    showTab(Tab.VOD_RESULTS);
                })
                .show();
    }

    // ================= 分页签 =================

    private void buildChips() {
        chipRow.removeAllViews();
        boolean server = KtvStore.useServer();
        if (server) {
            addChip("热门", Tab.HOT);
            addChip("分类", Tab.BROWSE);
            addChip("歌手", Tab.SINGERS);
        }
        addChip("常唱", Tab.HISTORY);
        addChip("收藏", Tab.FAVORITES);
        addChip("已点", Tab.QUEUE);
    }

    private void addChip(String label, Tab t) {
        TextView c = pill(label, tab == t ? 0xFF14161C : BODY, tab == t ? YELLOW : PANEL);
        c.setPadding(dp(16), dp(7), dp(16), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        chipRow.addView(c, lp);
        c.setOnClickListener(v -> showTab(t));
    }

    private boolean isPagedTab() {
        return tab == Tab.HOT || tab == Tab.BROWSE || tab == Tab.SEARCH || tab == Tab.SINGER_SONGS || tab == Tab.VOD_RESULTS;
    }

    private void showTab(Tab t) {
        tab = t;
        buildChips();
        songs.clear();
        singers.clear();
        page = 1;
        noMore = false;
        adapter.notifyDataSetChanged();
        refreshQueueInfo();
        switch (t) {
            case HISTORY:
                songs.addAll(KtvStore.history());
                status(songs.isEmpty() ? "还没唱过歌" : "唱过的都在这，点一首再来");
                adapter.notifyDataSetChanged();
                break;
            case FAVORITES:
                songs.addAll(KtvStore.favorites());
                status(songs.isEmpty() ? "还没收藏，点歌行右边那颗星就是收藏" : "");
                adapter.notifyDataSetChanged();
                break;
            case QUEUE:
                songs.addAll(KtvStore.queue());
                status(songs.isEmpty() ? "队列是空的，去点几首" : "排队中，置顶的那首唱完接下一首");
                adapter.notifyDataSetChanged();
                break;
            case SINGERS:
                loadSingers(1, "");
                break;
            case VOD_RESULTS:
                status(lastQuery.isEmpty() ? "在上面搜歌，或从点播页翻到想唱的、详情页点开唱" : "");
                if (!lastQuery.isEmpty()) loadPage(1, false);
                break;
            default:
                loadPage(1, false);
        }
    }

    // ================= 数据加载（全在后台线程） =================

    private KtvServer server() {
        return new KtvServer(KtvStore.baseUrl());
    }

    private void loadPage(int p, boolean append) {
        if (loading) return;
        if (KtvStore.useServer() && !server().ready()) {
            status("先填点歌服务器地址");
            return;
        }
        loading = true;
        if (!append) status("加载中…");
        Tab t = tab;
        String q = lastQuery;
        String lang = curLanguage;
        String singer = curSinger;
        Site site = vodSite();
        Task.execute(() -> {
            List<KtvSong> got = new ArrayList<>();
            String err = "";
            try {
                switch (t) {
                    case HOT:
                        got = server().hot(p);
                        break;
                    case BROWSE:
                        if (languages.isEmpty()) languages = server().languages();
                        got = server().browse(p, lang, "");
                        break;
                    case SEARCH:
                        got = server().search(p, q);
                        break;
                    case SINGER_SONGS:
                        got = server().singerSongs(p, singer);
                        break;
                    case VOD_RESULTS:
                        if (site == null) throw new IllegalStateException("还没选歌源");
                        if (q.isEmpty()) break;
                        Result r = SiteApi.searchContent(site, q, false, String.valueOf(p));
                        for (Vod v : r.getList()) got.add(fromVod(site, v));
                        break;
                    default:
                        break;
                }
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            List<KtvSong> finalGot = got;
            String finalErr = err;
            runOnUiThread(() -> {
                loading = false;
                if (tab != t) return;
                if (!finalErr.isEmpty()) {
                    status("没拉到：" + finalErr);
                    return;
                }
                if (!append) songs.clear();
                if (finalGot.isEmpty()) {
                    noMore = true;
                    status(append ? "没有更多了" : (t == Tab.VOD_RESULTS ? "这个源里没搜到" : "这里是空的"));
                } else {
                    songs.addAll(finalGot);
                    page = p;
                    status(t == Tab.BROWSE ? browseHint() : "");
                }
                adapter.notifyDataSetChanged();
                if (autoOrderQuery != null && !songs.isEmpty()) {
                    if (t == Tab.SEARCH) {
                        finishAutoOrder(songs.get(0));
                    } else if (t == Tab.VOD_RESULTS) {
                        KtvSong first = songs.get(0);
                        Site autoSite = vodSite();
                        if (autoSite != null) {
                            Vod autoVod = new Vod();
                            autoVod.setId(first.vodId);
                            autoVod.setName(first.name);
                            openVod(autoVod, autoSite);
                        } else {
                            autoOrderQuery = null;
                        }
                    }
                }
            });
        });
    }

    private void pickLanguage() {
        String[] names = new String[languages.size() + 1];
        names[0] = "全部语种";
        for (int i = 0; i < languages.size(); i++) names[i + 1] = languages.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("选语种")
                .setItems(names, (d, w) -> {
                    curLanguage = w == 0 ? "" : languages.get(w - 1).id;
                    songs.clear();
                    page = 1;
                    noMore = false;
                    adapter.notifyDataSetChanged();
                    loadPage(1, false);
                })
                .show();
    }

    private String browseHint() {
        if (languages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("语种：");
        for (KtvServer.Category c : languages) sb.append(c.name.equals(curLanguage) || c.id.equals(curLanguage) ? "【" + c.name + "】" : c.name).append(" ");
        return sb.toString().trim();
    }

    private void loadSingers(int p, String letter) {
        loading = true;
        status("加载歌手…");
        Task.execute(() -> {
            List<String> got = new ArrayList<>();
            String err = "";
            try {
                got = letter.isEmpty() ? server().hotSingers(p) : server().singers(p, letter);
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            List<String> finalGot = got;
            String finalErr = err;
            runOnUiThread(() -> {
                loading = false;
                if (tab != Tab.SINGERS) return;
                singers.clear();
                singers.addAll(finalGot);
                status(finalErr.isEmpty() ? "点歌手看他的歌" + (letter.isEmpty() ? "" : "（字母 " + letter + "，点「歌手」回热门）") : "没拉到：" + finalErr);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void openSinger(String name) {
        curSinger = name;
        tab = Tab.SINGER_SONGS;
        buildChips();
        songs.clear();
        page = 1;
        noMore = false;
        adapter.notifyDataSetChanged();
        status("「" + name + "」的歌");
        loadPage(1, false);
    }

    private void openVod(Vod vod, Site site) {
        status("正在翻「" + vod.getName() + "」的歌…");
        Task.execute(() -> {
            List<KtvSong> got = new ArrayList<>();
            String err = "";
            try {
                Result r = SiteApi.detailContent(site.getKey(), vod.getId());
                Vod full = r.getVod();
                List<Flag> flags = full.getFlags();
                if (!flags.isEmpty()) {
                    Flag f = flags.get(0);
                    vodFlag = f.getFlag();
                    for (Episode ep : f.getEpisodes()) {
                        KtvSong s = new KtvSong();
                        s.origin = "vod";
                        s.siteKey = site.getKey();
                        s.siteName = site.getName();
                        s.vodId = vod.getId();
                        s.flag = f.getFlag();
                        s.name = ep.getName();
                        s.episode = ep.getName();
                        s.episodeUrl = ep.getUrl();
                        s.singer = full.getActor() != null && !full.getActor().isEmpty() ? full.getActor() : full.getName();
                        s.pic = full.getPic();
                        got.add(s);
                    }
                }
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            List<KtvSong> finalGot = got;
            String finalErr = err;
            runOnUiThread(() -> {
                if (!finalErr.isEmpty()) {
                    status("没打开：" + finalErr);
                    return;
                }
                curVod = vod;
                tab = Tab.VOD_SONGS;
                buildChips();
                songs.clear();
                songs.addAll(finalGot);
                status("「" + vod.getName() + "」共 " + finalGot.size() + " 首，点歌就开唱");
                adapter.notifyDataSetChanged();
                if (autoOrderQuery != null && !finalGot.isEmpty()) finishAutoOrder(finalGot.get(0));
            });
        });
    }

    private static KtvSong fromVod(Site site, Vod v) {
        KtvSong s = new KtvSong();
        s.origin = "vod_result";
        s.siteKey = site.getKey();
        s.siteName = site.getName();
        s.vodId = v.getId();
        s.name = v.getName();
        s.singer = v.getActor() == null ? "" : v.getActor();
        s.pic = v.getPic();
        return s;
    }

    private void doSearch(String q) {
        lastQuery = q;
        if (q.isEmpty()) {
            toast("先打歌名或歌手");
            return;
        }
        if (KtvStore.useServer()) {
            showTab(Tab.SEARCH);
        } else {
            showTab(Tab.VOD_RESULTS);
        }
    }

    // ================= 列表 =================

    private class SongAdapter extends RecyclerView.Adapter<SongAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(11), dp(10), dp(11));
            row.setBackground(round(PANEL, 12, true));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);
            LinearLayout texts = new LinearLayout(parent.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            TextView title = text("", 15, WHITE);
            TextView sub = text("", 12, SUB);
            texts.addView(title);
            texts.addView(sub);
            row.addView(texts);
            TextView act1 = pill("", BODY, 0xFF232838);
            TextView act2 = pill("", BODY, 0xFF232838);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.leftMargin = dp(8);
            row.addView(act1, alp);
            row.addView(act2, new LinearLayout.LayoutParams(-2, -2) {{
                leftMargin = dp(8);
            }});
            return new Holder(row, title, sub, act1, act2);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.act1.setVisibility(View.GONE);
            h.act2.setVisibility(View.GONE);
            if (tab == Tab.SINGERS) {
                String name = singers.get(position);
                h.title.setText(name);
                h.sub.setText("歌手");
                h.act1.setText("看歌 ›");
                h.act1.setVisibility(View.VISIBLE);
                h.itemView.setOnClickListener(v -> openSinger(name));
                h.act1.setOnClickListener(v -> openSinger(name));
                return;
            }
            KtvSong s = songs.get(position);
            h.title.setText(s.title());
            if ("vod_result".equals(s.origin)) {
                h.sub.setText((s.singer == null || s.singer.isEmpty() ? "专辑/歌手" : s.singer) + " · 点开看歌");
                h.itemView.setOnClickListener(v -> {
                    Site site = vodSite();
                    if (site == null) {
                        toast("歌源没了，重新选一个");
                        return;
                    }
                    Vod vod = new Vod();
                    vod.setId(s.vodId);
                    vod.setName(s.name);
                    openVod(vod, site);
                });
                return;
            }
            h.sub.setText(s.sub());
            if (tab == Tab.QUEUE) {
                h.act1.setText("置顶");
                h.act1.setVisibility(View.VISIBLE);
                h.act1.setOnClickListener(v -> {
                    KtvStore.moveToTop(position);
                    showTab(Tab.QUEUE);
                });
                h.act2.setText("删");
                h.act2.setVisibility(View.VISIBLE);
                h.act2.setOnClickListener(v -> {
                    KtvStore.removeFromQueue(position);
                    showTab(Tab.QUEUE);
                });
                h.itemView.setOnClickListener(null);
                return;
            }
            // 普通歌曲行：收藏 + 点歌
            int qi = queueIndexOf(s);
            h.act1.setText(KtvStore.isFavorite(s) ? "★" : "☆");
            h.act1.setVisibility(View.VISIBLE);
            h.act1.setOnClickListener(v -> {
                KtvStore.toggleFavorite(s);
                notifyItemChanged(position);
            });
            h.act2.setText(qi >= 0 ? "已点·" + (qi + 1) : "点歌");
            stylePill(h.act2, qi >= 0 ? BODY : 0xFF14161C, qi >= 0 ? 0xFF232838 : YELLOW);
            h.act2.setVisibility(View.VISIBLE);
            h.act2.setOnClickListener(v -> orderSong(s));
            h.itemView.setOnClickListener(v -> orderSong(s));
        }

        @Override
        public int getItemCount() {
            return tab == Tab.SINGERS ? singers.size() : songs.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView sub;
            final TextView act1;
            final TextView act2;

            Holder(View itemView, TextView title, TextView sub, TextView act1, TextView act2) {
                super(itemView);
                this.title = title;
                this.sub = sub;
                this.act1 = act1;
                this.act2 = act2;
            }
        }
    }

    private int queueIndexOf(KtvSong s) {
        List<KtvSong> q = KtvStore.queue();
        for (int i = 0; i < q.size(); i++) if (q.get(i).identity().equals(s.identity())) return i;
        return -1;
    }

    private void orderSong(KtvSong s) {
        KtvStore.addToQueue(s);
        toast("已点上：" + s.title());
        refreshQueueInfo();
        adapter.notifyDataSetChanged();
    }

    private void refreshQueueInfo() {
        int n = KtvStore.queue().size();
        if (queueInfo != null) queueInfo.setText(n > 0 ? "已点 " + n + " 首" : "还没点歌");
    }

    private void status(String s) {
        if (statusText != null) statusText.setText(s == null ? "" : s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================= 小件 =================

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp, boolean stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke) g.setStroke(1, LINE);
        return g;
    }

    private TextView pill(String s, int fg, int bg) {
        TextView t = text(s, 13, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        stylePill(t, fg, bg);
        return t;
    }

    private void stylePill(TextView t, int fg, int bg) {
        t.setTextColor(fg);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(999));
        t.setBackground(g);
    }
}
