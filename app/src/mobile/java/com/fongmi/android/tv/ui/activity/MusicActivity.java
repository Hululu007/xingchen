package com.fongmi.android.tv.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.api.music.KwChartSource;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.databinding.ActivityMusicBinding;
import com.fongmi.android.tv.databinding.HomeDialogRecentBinding;
import com.fongmi.android.tv.music.DeviceMusicStore;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音乐首页（v7 定稿，2026-10-21 用户逐版改定）：
 * ① 搜索胶囊条（占位「搜索歌曲、歌手、专辑」）＋右侧竖三点 ⋮ 菜单（顶上滑下的白色圆角卡，非全屏）；
 * ② 白色圆角卡内五个快捷横排：音乐收藏／我的歌单／本地音乐（正中）／播放历史／下载音乐，
 *    彩色图标＋名字＋下方真实数量（异步回填，取不到显示 0）；
 * ③ 三张渐变入口卡垫底：猜你喜欢·每日精选（粉）／排行榜·此刻热门（蓝绿）／随心听（绿黄，
 *    副题动态：未播随心听队列时「随机漫游」，正在播随心听队列时「正在播放」，见 refreshShuffleSubtitle）。
 * 底部迷你条是 ListenMiniBar 悬浮条（只在音乐首页显示，白名单机制在迷你条侧），本页不内嵌。
 * 源列表不放首页；顶栏无返回箭头；界面只放名字和状态，无介绍文字。
 */
public class MusicActivity extends BaseActivity {

    private ActivityMusicBinding binding;

    // 榜单卡（06:20 令）：榜签只映射酷我＋QQ 真榜，按关键词对齐参照图四类
    private final List<MusicChart> homeChartTabs = new ArrayList<>();
    private List<MusicTrack> homeChartTracks = new ArrayList<>();
    /** homeChartTracks 归属签（平台+id）：切签后旧签曲目不算新签「已有内容」，防同名不同榜错把旧签内容当新签早退 */
    private String homeChartTracksKey;
    private int homeChartSel = -1;
    private int homeChartLoadSeq = 0;
    private boolean homeChartLoaded;
    /** 当前签拉空已显空态：onResume 不再重试转圈，只点重试／切签重拉（空态与「有内容」拆标志） */
    private boolean homeChartEmpty;
    /** 第二波：首页榜单卡选中签下划线色＝当前榜首首封面语气色（瓜子 HomeRankingCardKt 选中取封面语气色）；取色失败回落红 */
    private int homeChartTone = 0xFFFA243C;
    private String homeChartToneCover = "";

    /** X-08（批 5 F2）：重建恢复暂存——心情选中（initMoodCard 消费）与首页滚动位置（onResume 消费）。 */
    private String savedMood;
    private int savedScrollY = -1;
    private boolean scrollRestoreDone;

    // 新歌榜每次进都会动（2026-10-22）：根因是每次进页都是新实例全量重拉，状态全是实例字段。
    // 静态缓存签表／选中签／曲目／tone＋cover：新实例先显缓存；10 分钟内不再联网，过期则静默刷新、内容不变不重刷。
    private static final long HOME_CHART_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static List<MusicChart> sHomeChartTabs;
    private static String sHomeChartSelName;
    private static String sHomeChartSelKey;
    /** 插件增删／启停／更新／导入后置位：回首页时同实例也要重拉，不只新实例不走缓存 */
    private static boolean sHomeChartInvalidated;
    private static List<MusicTrack> sHomeChartTracks;
    private static int sHomeChartTone = 0xFFFA243C;
    private static String sHomeChartToneCover = "";
    private static boolean sHomeChartToneSet;
    private static long sHomeChartCacheAt;

    /** 曲目深拷贝：分片会改实例曲目的 cover，静态缓存必须与实例曲目脱钩，内容比对才有效 */
    private static List<MusicTrack> copyHomeChartTracks(List<MusicTrack> src) {
        List<MusicTrack> out = new ArrayList<>();
        if (src == null) return out;
        for (MusicTrack t : src) {
            if (t == null) continue;
            MusicTrack c = new MusicTrack(t.id, t.title, t.artist, t.album, t.duration, t.cover);
            c.platform = t.platform;
            c.localPath = t.localPath;
            c.qualityTag = t.qualityTag;
            c.rawJson = t.rawJson;
            c.playlistAddedTime = t.playlistAddedTime;
            c.favoriteAddedTime = t.favoriteAddedTime;
            out.add(c);
        }
        return out;
    }

    /** 签表一致性：名＋平台＋id 全对才算一致，一致只 restyle、不 removeAllViews 重建 */
    private static boolean sameHomeChartTabs(List<MusicChart> a, List<MusicChart> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            MusicChart x = a.get(i), y = b.get(i);
            if (x == null || y == null) return false;
            if (!eq(x.name, y.name) || !eq(x.platform, y.platform) || !eq(x.id, y.id)) return false;
        }
        return true;
    }

    /** 曲目一致性：行文案＋封面全对才算不变，不变不重刷（封面 Glide 不重发，逐张弹出不再发生） */
    private static boolean sameHomeChartTracks(List<MusicTrack> a, List<MusicTrack> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            MusicTrack x = a.get(i), y = b.get(i);
            if (x == null || y == null) return false;
            if (!eq(x.id, y.id) || !eq(x.title, y.title) || !eq(x.artist, y.artist) || !eq(x.cover, y.cover)) return false;
        }
        return true;
    }

    /** 榜单归属键：平台+id（不只比名字；同名不同榜是两张榜）。 */
    private static String homeChartKey(MusicChart c) {
        if (c == null) return "";
        return (c.platform == null ? "" : c.platform) + "|" + (c.id == null ? "" : c.id);
    }

    /** M8：清榜单静态缓存（插件增删／启停／更新／导入处调；回首页路径见 onResume 的失效位消费）。 */
    public static void clearHomeChartCache() {
        sHomeChartTabs = null;
        sHomeChartSelName = null;
        sHomeChartSelKey = null;
        sHomeChartTracks = null;
        sHomeChartTone = 0xFFFA243C;
        sHomeChartToneCover = "";
        sHomeChartToneSet = false;
        sHomeChartCacheAt = 0;
        sHomeChartInvalidated = true;
        try {
            java.io.File f = new java.io.File(com.fongmi.android.tv.App.get().getFilesDir(), "music_home_chart.json");
            if (f.isFile()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 榜单磁盘快照（2026-10-22 新歌榜冷启动像贴上去根因：静态缓存只活进程内，冷启动必重拉；落盘后冷启动先显快照、后台静默刷）。 */
    private static class HomeChartSnapshot {
        public java.util.List<MusicChart> tabs;
        public String selName;
        public String selKey;
        public java.util.List<MusicTrack> tracks;
        public int tone;
        public String toneCover;
        public boolean toneSet;
        public long cacheAt;
    }

    private static void writeHomeChartDisk() {
        try {
            HomeChartSnapshot s = new HomeChartSnapshot();
            s.tabs = sHomeChartTabs;
            s.selName = sHomeChartSelName;
            s.selKey = sHomeChartSelKey;
            s.tracks = sHomeChartTracks;
            s.tone = sHomeChartTone;
            s.toneCover = sHomeChartToneCover;
            s.toneSet = sHomeChartToneSet;
            s.cacheAt = sHomeChartCacheAt;
            String json = com.fongmi.android.tv.App.gson().toJson(s);
            // D-22（批 5 F2）：原子写——先写临时文件再改名，写一半被杀也不会把旧快照写成半截；
            // 改名失败（个别文件系统跨目录限制不适用同目录、极少发生）退回直写兜底，仍失败留一行日志不静默丢。
            java.io.File dir = com.fongmi.android.tv.App.get().getFilesDir();
            java.io.File f = new java.io.File(dir, "music_home_chart.json");
            java.io.File tmp = new java.io.File(dir, "music_home_chart.json.tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                java.io.FileOutputStream direct = new java.io.FileOutputStream(f);
                try {
                    direct.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    direct.close();
                }
                if (tmp.isFile()) tmp.delete();
            }
        } catch (Throwable t) {
            com.github.catvod.crawler.SpiderDebug.log("music-home-chart", "快照写盘失败: " + t);
        }
    }

    /** 冷启动时把磁盘快照灌回静态缓存（静态为空时才读，读到坏文件当无缓存）。 */
    private static void loadHomeChartDisk() {
        if (sHomeChartTabs != null && !sHomeChartTabs.isEmpty()) return;
        try {
            java.io.File f = new java.io.File(com.fongmi.android.tv.App.get().getFilesDir(), "music_home_chart.json");
            if (!f.isFile() || f.length() == 0) return;
            byte[] bytes = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int read = 0;
            try {
                // 单次 read 不保证读满：循环读到满或 EOF，半截 JSON 进 Gson 只会抛错被吞、
                // 快照白白丢失、冷启动又回空屏转圈——这正是单次读的坑。
                int n;
                while (read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0) read += n;
            } finally {
                in.close();
            }
            if (read <= 0) return;
            // 崩溃根修（1.4.8 真机 ClassCastException LinkedTreeMap→MusicChart）：此前整包
            // fromJson(HomeChartSnapshot.class)，R8 混淆抹掉 List 字段泛型签名，tabs/tracks 元素
            // 读回成 LinkedTreeMap，恢复时赋值到 MusicChart 当场崩。改按 MusicStore 同款 TypeToken
            // 逐项显式定型读（tabs/tracks 两列）；标量字段按名取、缺失给默认；旧快照文件结构不变，
            // 修后直接读通，不用清缓存。
            com.google.gson.Gson gson = com.fongmi.android.tv.App.gson();
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(
                    new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            java.lang.reflect.Type chartListType = new com.google.gson.reflect.TypeToken<java.util.List<MusicChart>>() {
            }.getType();
            java.lang.reflect.Type trackListType = new com.google.gson.reflect.TypeToken<java.util.List<com.fongmi.android.tv.bean.music.MusicTrack>>() {
            }.getType();
            java.util.List<MusicChart> tabs = gson.fromJson(obj.get("tabs"), chartListType);
            java.util.List<com.fongmi.android.tv.bean.music.MusicTrack> tracks = gson.fromJson(obj.get("tracks"), trackListType);
            if (tabs == null || tabs.isEmpty() || tracks == null || tracks.isEmpty()) return;
            sHomeChartTabs = tabs;
            sHomeChartSelName = obj.has("selName") && !obj.get("selName").isJsonNull() ? obj.get("selName").getAsString() : null;
            sHomeChartSelKey = obj.has("selKey") && !obj.get("selKey").isJsonNull() ? obj.get("selKey").getAsString() : null;
            sHomeChartTracks = tracks;
            sHomeChartTone = obj.has("tone") ? obj.get("tone").getAsInt() : 0;
            sHomeChartToneCover = obj.has("toneCover") && !obj.get("toneCover").isJsonNull() ? obj.get("toneCover").getAsString() : "";
            sHomeChartToneSet = obj.has("toneSet") && obj.get("toneSet").getAsBoolean();
            sHomeChartCacheAt = obj.has("cacheAt") ? obj.get("cacheAt").getAsLong() : 0;
        } catch (Throwable t) {
            // D-22（批 5 F2）：坏快照读失败留一行日志，不再静默吞——冷启动回空屏重拉时有据可查
            com.github.catvod.crawler.SpiderDebug.log("music-home-chart", "快照读回失败（按无缓存处理）: " + t);
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 成功拿到内容／分片补封面后写静态缓存；空结果不写（空态不进缓存，下次进页仍正常拉） */
    private void cacheHomeChartState() {
        if (homeChartTabs.isEmpty() || homeChartTracks.isEmpty()
                || homeChartSel < 0 || homeChartSel >= homeChartTabs.size()) return;
        sHomeChartTabs = new ArrayList<>(homeChartTabs);
        sHomeChartSelName = homeChartTabs.get(homeChartSel).name;
        sHomeChartSelKey = homeChartKey(homeChartTabs.get(homeChartSel));
        sHomeChartTracks = copyHomeChartTracks(homeChartTracks);
        sHomeChartTone = homeChartTone;
        sHomeChartToneCover = homeChartToneCover;
        sHomeChartToneSet = true;
        sHomeChartCacheAt = System.currentTimeMillis();
        writeHomeChartDisk();
    }

    /** 新实例先用缓存还原：签行首建（新实例视图本来就是空的，不是重建）、tone 先取缓存值画下划线、内容直接填，
     * 全程不走加载圈；返回 false 表示无可用缓存，走原联网路。 */
    private boolean restoreHomeChartCache() {
        if (sHomeChartTabs == null || sHomeChartTabs.isEmpty()
                || sHomeChartTracks == null || sHomeChartTracks.isEmpty()) return false;
        homeChartTabs.clear();
        homeChartTabs.addAll(sHomeChartTabs);
        int sel = -1;
        for (int i = 0; i < homeChartTabs.size(); i++) {
            if (sHomeChartSelKey != null && homeChartKey(homeChartTabs.get(i)).equals(sHomeChartSelKey)) {
                sel = i;
                break;
            }
        }
        if (sel < 0) {
            for (int i = 0; i < homeChartTabs.size(); i++) {
                if (eq(homeChartTabs.get(i).name, sHomeChartSelName)) {
                    sel = i;
                    break;
                }
            }
        }
        if (sel < 0) sel = 0;
        homeChartSel = sel;
        if (sHomeChartToneSet) {
            homeChartTone = sHomeChartTone;
            homeChartToneCover = sHomeChartToneCover;
        }
        homeChartTracks = copyHomeChartTracks(sHomeChartTracks);
        homeChartTracksKey = homeChartKey(homeChartTabs.get(sel));
        homeChartEmpty = false;
        homeChartLoaded = true;
        buildHomeChartTabs();
        restyleHomeChartTabs();
        fillHomeChart(homeChartTabs.get(sel), homeChartTracks);
        binding.homeChartCard.setVisibility(View.VISIBLE);
        applyShuffleTone();
        return true;
    }

    // 随心听副题不再用本页粘性字段判定：旧 homeQueueSourceType／shuffleQueueKeys 从未被写，
    // 是副题恒「随机漫游」的根因，已删；改读服务队列来源字段，见 refreshShuffleSubtitle。

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicBinding.inflate(getLayoutInflater());
        return binding;
    }

    /** X-08（批 5 F2）：音乐首页半状态保存——心情选中／榜单选中签／滚动位置随重建带回；
     * 榜单选中签本有静态缓存＋磁盘快照两层（selKey），这里再入 savedInstanceState 与它们对齐兜底。 */
    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            if (moodSelected != null) outState.putString("ma_mood", moodSelected);
            if (homeChartSel >= 0 && homeChartSel < homeChartTabs.size()) {
                outState.putString("ma_chartSelKey", homeChartKey(homeChartTabs.get(homeChartSel)));
            }
            if (binding != null && binding.homeScroll != null) {
                outState.putInt("ma_scrollY", binding.homeScroll.getScrollY());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            savedMood = savedInstanceState.getString("ma_mood");
            savedScrollY = savedInstanceState.getInt("ma_scrollY", -1);
            String selKey = savedInstanceState.getString("ma_chartSelKey");
            if (selKey != null && sHomeChartSelKey == null) sHomeChartSelKey = selKey;
        }
        // 首页离边 16dp：边距只打在滚动层（homeScroll）上。背景层（homeGlassBg）与滚动层
        // 是根 FrameLayout 的两个并列子 View，背景层不吃这层 padding、全屏铺满。
        // 旧法把 padding 打在共享根上、再用负边距给背景层抵消——子 View 的布局框不可能
        // 溢出父布局框，抵消在真机不生效：1.4.27 真机截图里背景被框进边距内、四边露出
        // 窗口壁纸，整页像一张带边距的白纸（断点实证），故废弃旧法。
        // 圆角不动（搜索 24dp、三彩卡 20dp、五快捷 16dp）；三段上距见布局（32/40/40dp）。
        int sbHome = 0;
        int sbRes = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbRes > 0) sbHome = getResources().getDimensionPixelSize(sbRes);
        float homeDensity = getResources().getDisplayMetrics().density;
        int sideHome = Math.round(16 * homeDensity);
        binding.homeScroll.setPadding(sideHome, sbHome + Math.round(12 * homeDensity),
                sideHome, Math.round(16 * homeDensity));
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        // ⓪ 首页标题斜度：歌词同款 skewX(-0.12f)（他 16:42 令，幅度只要一点；
        // TextView 无 setTextSkewX，照歌词斜度口径用 getPaint().setTextSkewX(-0.12f)）
        binding.homeTitle.getPaint().setTextSkewX(-0.12f);
        // 批 5 F1：标题渐变字（粉→紫，第十稿标题口径；shader 宽按文字实测宽走，量不到回落纯色不崩）
        try {
            float tw = binding.homeTitle.getPaint().measureText("星辰音乐");
            if (tw > 0) binding.homeTitle.getPaint().setShader(new android.graphics.LinearGradient(
                    0, 0, tw, 0, 0xFFFF5C8A, 0xFF8E7BFF, android.graphics.Shader.TileMode.CLAMP));
            binding.homeTitle.invalidate();
        } catch (Throwable ignored) {
        }
        // 批 5 F2：继续播放白卡——封面点＝播该首（历史作队列），卡面其余处点＝有会话进播放页、无会话播最近一首
        binding.homeContinueCard.setOnClickListener(v -> {
            if (MusicController.get().isActive()) MusicPlayerActivity.start(this);
            else playContinueAt(0);
        });
        binding.homeContinuePlay.setOnClickListener(v -> onContinuePlay());
        // ① 搜索胶囊：整条点按进现行搜索页；⋮ 单独点开小菜单（stop 冒泡靠独立点击区）
        binding.homeSearchBar.setOnClickListener(v -> MusicListActivity.startSearch(this, ""));
        binding.homeBtnMore.setOnClickListener(this::showMoreMenu);
        // 通知权限提示条的「去开启」钮（显隐由 onResume 的 refreshNotifPermStrip 按实读权限定）
        binding.notifPermGo.setOnClickListener(v -> openAppNotificationSettings());
        // ② 五快捷真跳
        binding.homeQuickFavorite.setOnClickListener(v -> MusicListActivity.startFavorite(this));
        binding.homeQuickSheets.setOnClickListener(v -> MusicListActivity.startSheets(this));
        binding.homeQuickDevice.setOnClickListener(v -> MusicListActivity.startDevice(this));
        binding.homeQuickRecent.setOnClickListener(v -> showRecent());
        binding.homeQuickDownload.setOnClickListener(v -> MusicListActivity.startDownload(this));
        // ③ 底部三卡（批 5 F2 按第十稿改回定稿构成，压过 F1 的歌单广场卡）：
        // 猜你喜欢·每日精选（封面堆）→猜你喜欢页；排行榜·此刻热门（前三名小列表）→榜单页；随心听·随机漫游（发光播放盘）→随心听页。
        // 歌单广场入口不在此卡——歌单库入口在五快捷「我的歌单」（homeQuickSheets），歌单广场页本体照常可达。
        binding.homeCardGuess.setOnClickListener(v -> MusicListActivity.startGuess(this));
        binding.homeCardCharts.setOnClickListener(v -> MusicListActivity.startCharts(this));
        binding.homeCardShuffle.setOnClickListener(v -> MusicRandomActivity.start(this));
        // GZ 三彩卡按压缩放 0.98（MotionKt；返回 false 不吞点击，只做缩放反馈）
        pressScale(binding.homeCardGuess);
        pressScale(binding.homeCardCharts);
        pressScale(binding.homeCardShuffle);
        // 心情电台卡（GZ MoodRadioPanel 移植，见布局注释与本文件 mood 段）
        initMoodCard();
        // ④ 榜单卡：完整榜单进现有榜单页；主封面播放从头播该榜；点行从该曲开播整榜
        binding.homeChartMore.setOnClickListener(v -> MusicListActivity.startCharts(this));
        binding.homeChartMoreIcon.setOnClickListener(v -> MusicListActivity.startCharts(this));
        // 第二波：榜单卡空态「点击重试」→重拉当前签（签行保留，换签也能走 selectHomeChart）
        binding.homeChartEmptyRetry.setOnClickListener(v -> {
            if (homeChartSel >= 0 && homeChartSel < homeChartTabs.size()) selectHomeChart(homeChartSel, true);
        });
        binding.homeChartPlay.setOnClickListener(v -> playHomeChartFrom(0));
        binding.homeChartRow1.setOnClickListener(v -> playHomeChartFrom(0));
        binding.homeChartRow2.setOnClickListener(v -> playHomeChartFrom(1));
        binding.homeChartRow3.setOnClickListener(v -> playHomeChartFrom(2));
        attachCardPress(binding.homeChartRow1, 0.98f);
        attachCardPress(binding.homeChartRow2, 0.98f);
        attachCardPress(binding.homeChartRow3, 0.98f);
        // 卡片按压反馈：三彩卡按瓜子 Motion 0.98 微缩；搜索条／五快捷／榜单卡保持现行 0.96
        attachCardPress(binding.homeSearchBar);
        attachCardPress(binding.homeQuickFavorite);
        attachCardPress(binding.homeQuickSheets);
        attachCardPress(binding.homeQuickDevice);
        attachCardPress(binding.homeQuickRecent);
        attachCardPress(binding.homeQuickDownload);
        attachCardPress(binding.homeCardGuess, 0.98f);
        attachCardPress(binding.homeCardCharts, 0.98f);
        attachCardPress(binding.homeCardShuffle, 0.98f);
        // 三彩卡光晕（瓜子 HomeEntryHalo，浅色分支 only：星辰首页固定浅色）：色按卡取，
        // 常驻静态层（脉动门控在 refreshShuffleSubtitle）；84dp 半出环（C10）保留不动，光晕是另加的一层。
        binding.homeHaloGuess.setHaloColor(0xFFFFE8F3);
        binding.homeHaloCharts.setHaloColor(0xFF9DF3EE);
        binding.homeHaloShuffle.setHaloColor(0xFFEAF989);
        attachCardPress(binding.homeChartCard);
        // 第三波 H4：聆听报告卡可点进完整明细（播放历史页，见 openFullReport）。
        attachCardPress(binding.homeReportCard);
        binding.homeReportCard.setOnClickListener(v -> openFullReport());
        // 榜单封面圆角 12dp：ImgUtil.loadRounded 固定 8dp 且该文件不在本摊可改范围，
        // 这里用视图轮廓裁切补到 12dp（12dp 形含于 8dp 形内，交集即 12dp 圆角）
        applyChartCoverCorners();
        refreshCounts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // X-08 滚动恢复：内容高度随榜单卡显隐变化，等本帧布局后一次性回滚，滚完即弃不反复拽
        if (!scrollRestoreDone && savedScrollY > 0 && binding != null && binding.homeScroll != null) {
            scrollRestoreDone = true;
            final int y = savedScrollY;
            binding.homeScroll.post(() -> {
                try {
                    if (binding != null && binding.homeScroll != null) binding.homeScroll.scrollTo(0, y);
                } catch (Throwable ignored) {
                }
            });
        }
        // 首页浅底：状态栏图标改深色（06:17 令「背景把状态栏盖住了」）；离开本页在 onPause 恢复浅色图标
        ReadingSkin.setSystemBarIconAppearance(this, false);
        // M8 回首页路径：插件侧清过缓存时，同实例不许守着旧内容，必须重拉。
        if (sHomeChartInvalidated) {
            sHomeChartInvalidated = false;
            homeChartLoaded = false;
            homeChartEmpty = false;
        }
        if (!homeChartLoaded && !homeChartEmpty) loadHomeChart();
        refreshCounts();
        refreshShuffleSubtitle();
        refreshMoodState();
        refreshNotifPermStrip();
        refreshContinue();
        // 听书迷你条关闭路径 3：进入音乐页，听书真关闭（音乐会话自身不受影响）
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).musicPageEnter();
        } catch (Throwable ignored) {
        }
        // 迷你条再注册（1.4.27 查修批）：会话被策略层暂停自动关闭摘掉后，服务队列还在
        // 也不会有新事件把条带回来；回首页按现队列主动对一次，有可续播内容条必须在。
        try {
            MusicController.get().refreshMiniBar();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        // 离开音乐首页恢复浅色状态栏图标，避免把深色图标带去深底的播放页/歌词页
        ReadingSkin.setSystemBarIconAppearance(this, true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().unbind();
        super.onDestroy();
    }

    /**
     * 通知权限明示（2026-10-23 令：查问题不推给用户，App 自己摆明面）：
     * POST_NOTIFICATIONS 未授权时，失败/定时通知在服务侧直接 return、前台通知被系统藏起，
     * 用户只会觉得「通知没了」。这里在音乐首页实读权限：没授权就挂提示条（深底白字＋去开启钮），
     * 已授权（或安卓 13 以下无此运行时权限）永远不出现、不占位。从系统设置页回来
     * onResume 会再实读一次，开好即自动撤下。
     */
    private void refreshNotifPermStrip() {
        boolean granted = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        binding.notifPermStrip.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    /** 跳系统「应用通知」设置页（本应用那页）；个别系统打不开时退到应用详情页。 */
    private void openAppNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable ignored) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable ignored2) {
            }
        }
    }

    // ================= 榜单卡（数据只用酷我＋QQ 真榜） =================

    /**
     * 榜签映射（参照图四类 → 星辰真实榜，逐个核过）：
     * 飙升榜＝QQ topId 62「飙升榜」；新歌榜＝QQ topId 27「新歌榜」；热歌榜＝QQ topId 26「热歌榜」；
     * 原创榜＝QQ 全表无此名，落酷我榜表按「原创」匹配（元力KW 插件运行时榜表）。
     * 解析序固定先 QQ 后酷我；某签两边都匹配不到就不出该签；全签落空或榜内无曲 → 整卡隐藏。
     */
    private void loadHomeChart() {
        // 缓存快路：新实例先显缓存；新鲜（10 分钟内）直接停，过期则静默刷新（先验签表、再刷当前签曲目）。
        // 冷启动静态缓存是空的，先从磁盘快照灌回再走同一快路（快照新鲜直接停、过期先显后静默刷，不再空屏转圈再贴上去）。
        loadHomeChartDisk();
        if (restoreHomeChartCache()) {
            if (System.currentTimeMillis() - sHomeChartCacheAt < HOME_CHART_CACHE_TTL_MS) return;
            refreshHomeChartSilently();
            return;
        }
        Task.execute(() -> {
            List<MusicChart> tabs = collectHomeChartTabs();
            int def = -1;
            for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).name.contains("新歌")) def = i;
            if (def < 0 && !tabs.isEmpty()) def = 0;
            final int sel = def;
            runOnUiThread(() -> {
                // 有缓存 tone 先用它画下划线，不先写死红再跳变（无缓存时仍是旧红兜底）。
                if (sHomeChartToneSet) {
                    homeChartTone = sHomeChartTone;
                    homeChartToneCover = sHomeChartToneCover;
                }
                boolean same = sameHomeChartTabs(homeChartTabs, tabs);
                homeChartTabs.clear();
                homeChartTabs.addAll(tabs);
                if (tabs.isEmpty()) {
                    // 签表拉空整卡隐藏、无重试钮：同实例 onResume 仍可重试（无转圈可见），不置空态标志。
                    binding.homeChartCard.setVisibility(View.GONE);
                    return;
                }
                // 签表一致只 restyle，不 removeAllViews 重建（同实例重进不再闪签行）。
                if (same && binding.homeChartTabs.getChildCount() == tabs.size()) restyleHomeChartTabs();
                else buildHomeChartTabs();
                int target = (same && homeChartSel >= 0 && homeChartSel < tabs.size()) ? homeChartSel : sel;
                selectHomeChart(target);
            });
        });
    }

    /** 签表采集（原 loadHomeChart 内联逻辑抽出，静默刷新与正常加载共用；解析序先 QQ 后酷我，不动） */
    private static List<MusicChart> collectHomeChartTabs() {
        List<MusicChart> tabs = new ArrayList<>();
        try {
            List<MusicSite> sites = new ArrayList<>(MusicRepository.sites());
            sites.sort((a, b) -> Integer.compare(chartSiteRank(a), chartSiteRank(b)));
            String[] keys = {"飙升", "新歌", "原创", "热歌"};
            for (String key : keys) {
                MusicChart hit = null;
                for (MusicSite site : sites) {
                    for (MusicChart chart : MusicRepository.charts(site)) {
                        if (chart != null && chart.name != null && chart.name.contains(key)) {
                            hit = chart;
                            break;
                        }
                    }
                    if (hit != null) break;
                }
                if (hit != null) tabs.add(hit);
            }
        } catch (Throwable ignored) {
        }
        return tabs;
    }

    /** 过期缓存的静默刷新：后台先验签表——一致只 restyle 再静默刷当前签曲目（内容不变不重刷）；
     * 不一致（装／删插件改了签表）才重建签行并走默认签；签表拉空则保住缓存内容不动。 */
    private void refreshHomeChartSilently() {
        final int seq = ++homeChartLoadSeq;
        Task.execute(() -> {
            List<MusicChart> tabs = collectHomeChartTabs();
            runOnUiThread(() -> {
                if (binding == null || seq != homeChartLoadSeq) return;
                // M8：签表拉空＝静默刷新失败，缓存时间戳清零标记过期，下次新实例不再直接用旧缓存返回，
                // 而是先显旧内容再走静默刷新重试；旧内容本身仍保住，不翻空态。
                if (tabs.isEmpty()) {
                    sHomeChartCacheAt = 0;
                    return;
                }
                if (!sameHomeChartTabs(homeChartTabs, tabs)) {
                    homeChartTabs.clear();
                    homeChartTabs.addAll(tabs);
                    buildHomeChartTabs();
                    int def = -1;
                    for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).name.contains("新歌")) def = i;
                    if (def < 0) def = 0;
                    selectHomeChart(def, true);
                    return;
                }
                restyleHomeChartTabs();
                selectHomeChart(homeChartSel, true);
            });
        });
    }

    private static int chartSiteRank(MusicSite site) {
        String tag = ((site.platform == null ? "" : site.platform) + (site.name == null ? "" : site.name)).toLowerCase();
        if (tag.contains("qq") || tag.contains("tx")) return 0;
        if (tag.contains("kw") || tag.contains("酷我")) return 1;
        return 2;
    }

    private void buildHomeChartTabs() {
        LinearLayout box = binding.homeChartTabs;
        box.removeAllViews();
        for (int i = 0; i < homeChartTabs.size(); i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView label = new TextView(this);
            // 第二波：签只改显示名（瓜子首页榜单卡签为短名）；homeChartTabs 内 name 不动，标题／选榜仍用全名
            label.setText(homeChartShortName(homeChartTabs.get(i).name));
            label.setTextSize(11);
            label.setSingleLine(true);
            View bar = new View(this);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ResUtil.dp2px(18), ResUtil.dp2px(3));
            barLp.topMargin = ResUtil.dp2px(5);
            bar.setLayoutParams(barLp);
            cell.addView(label);
            cell.addView(bar);
            cell.setOnClickListener(v -> selectHomeChart(idx));
            box.addView(cell);
        }
    }

    /** 第二波：首页榜单卡签短名映射（只改显示：名含「飙升／热歌／新歌／原创」→对应「×榜」，其余沿用全名） */
    private static String homeChartShortName(String name) {
        if (name == null) return "";
        if (name.contains("飙升")) return "飙升榜";
        if (name.contains("热歌")) return "热歌榜";
        if (name.contains("新歌")) return "新歌榜";
        if (name.contains("原创")) return "原创榜";
        return name;
    }

    private void restyleHomeChartTabs() {
        LinearLayout box = binding.homeChartTabs;
        for (int i = 0; i < box.getChildCount(); i++) {
            LinearLayout cell = (LinearLayout) box.getChildAt(i);
            TextView label = (TextView) cell.getChildAt(0);
            View bar = cell.getChildAt(1);
            boolean on = i == homeChartSel;
            label.setTextColor(on ? 0xFF141414 : 0xFF8A8A8A);
            // 瓜子榜签：选中 800、未选中 500；API 28+ 按字重精确取，低版本退粗体／常规近似
            // （取法同 KaraokeLyricsView.mainTypeface 的 API 28 分支口径）
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                label.setTypeface(android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, on ? 800 : 500, false));
            } else {
                label.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(ResUtil.dp2px(2));
            // 第二波：选中下划线取封面语气色（瓜子 HomeRankingCardKt），不再固定红
            bg.setColor(on ? homeChartTone : Color.TRANSPARENT);
            bar.setBackground(bg);
        }
    }

    private void selectHomeChart(int idx) {
        selectHomeChart(idx, false);
    }

    /** force＝重试／过期静默刷新：绕过同签早退，但已有内容时仍不藏内容不显圈、内容不变不重刷 */
    private void selectHomeChart(int idx, boolean force) {
        if (idx < 0 || idx >= homeChartTabs.size()) return;
        MusicChart chart = homeChartTabs.get(idx);
        // 同签且该签已有曲目：早退，只 restyle，不藏内容、不显加载圈、不重拉。
        boolean keepContent = !homeChartTracks.isEmpty() && homeChartKey(chart).equals(homeChartTracksKey);
        if (!force && idx == homeChartSel && keepContent) {
            restyleHomeChartTabs();
            return;
        }
        homeChartSel = idx;
        homeChartEmpty = false;
        restyleHomeChartTabs();
        // 第二波：切签先显加载态（瓜子 HomeRankingCardKt「正在加载榜单」＋16dp 转圈）；结果回来了在回调里切空态／内容；
        // 渐进路（kw 分片／直连／封面按片补）原样不动，只在外层包加载／空态显隐。
        // 已有内容（强制刷新／静默刷新）时不藏内容、不显加载圈：旧内容一直留在屏上，结果不变则全程不动。
        binding.homeChartTitle.setText(chart.name);
        if (!keepContent) {
            if (binding.homeChartContent != null) binding.homeChartContent.setVisibility(View.GONE);
            if (binding.homeChartEmpty != null) binding.homeChartEmpty.setVisibility(View.GONE);
            if (binding.homeChartLoading != null) binding.homeChartLoading.setVisibility(View.VISIBLE);
        }
        binding.homeChartCard.setVisibility(View.VISIBLE);
        final int loadSeq = ++homeChartLoadSeq;
        Task.execute(() -> {
            List<MusicTrack> tracks = new ArrayList<>();
            boolean progressive = false;
            try {
                // kw 渐进路：先启动分片（与直连并行），直连文字＋时长先出，封面按片后补；非 kw 两调皆空走旧路
                MusicRepository.startKwChartSlices(chart);
                List<MusicTrack> direct = MusicRepository.chartTracksDirect(chart);
                if (direct != null && !direct.isEmpty()) {
                    tracks.addAll(direct);
                    progressive = true;
                } else {
                    List<MusicTrack> got = MusicRepository.chartTracks(chart);
                    if (got != null) tracks.addAll(got);
                }
            } catch (Throwable ignored) {
            }
            final List<MusicTrack> result = tracks;
            final boolean prog = progressive;
            runOnUiThread(() -> {
                if (idx != homeChartSel || loadSeq != homeChartLoadSeq) return;
                if (result.isEmpty()) {
                    // 已有内容时刷新拉空：保住旧内容，不翻空态、不清曲目（断网一次不把卡刷没）。
                    if (keepContent) return;
                    // 第二波：榜单拉空时卡体保留，显瓜子空态（「暂未获取到榜单」＋「点击重试」），签行保留可切榜；
                    // 空态与「有内容」拆标志：homeChartEmpty 置位后 onResume 不再重试转圈，只点重试／切签重拉。
                    homeChartTracks = result;
                    homeChartTracksKey = null;
                    homeChartLoaded = false;
                    homeChartEmpty = true;
                    if (binding.homeChartContent != null) binding.homeChartContent.setVisibility(View.GONE);
                    if (binding.homeChartLoading != null) binding.homeChartLoading.setVisibility(View.GONE);
                    if (binding.homeChartEmpty != null) binding.homeChartEmpty.setVisibility(View.VISIBLE);
                    binding.homeChartCard.setVisibility(View.VISIBLE);
                    return;
                }
                // 已有内容且内容不变：不重刷（不行文案、不重刷封面、不重取 tone），只续缓存有效期
                //（本次实拉验证过内容，静默刷新路也已验过签表一致，续期不会把旧签表冻住）。
                if (keepContent && sameHomeChartTracks(homeChartTracks, result)) {
                    sHomeChartCacheAt = System.currentTimeMillis();
                    return;
                }
                homeChartTracks = result;
                homeChartTracksKey = homeChartKey(chart);
                homeChartLoaded = true;
                homeChartEmpty = false;
                fillHomeChart(chart, result);
                cacheHomeChartState();
                binding.homeChartCard.setVisibility(View.VISIBLE);
                applyShuffleTone();
                if (!prog) return;
                // kw 封面按片陆续补：先回先上，不等另一片；只刷变了的封面
                MusicRepository.fetchKwChartCovers(chart, new KwChartSource.CoverListener() {
                    @Override
                    public void onShard(int pn, List<MusicTrack> shard) {
                        runOnUiThread(() -> {
                            if (binding == null || isFinishing() || isDestroyed()) return;
                            if (idx != homeChartSel || loadSeq != homeChartLoadSeq) return;
                            List<Integer> changed = applyHomeChartCovers(shard);
                            if (changed.isEmpty()) return;
                            fillHomeChartCoversAt(changed);
                            // 分片补上榜首封面后补一次 tone：下划线跟新榜首封面语气色走
                            if (changed.contains(0)) refreshHomeChartTone(homeChartTracks);
                            applyShuffleTone();
                            cacheHomeChartState();
                        });
                    }
                });
            });
        });
    }

    /** 底部三卡数据填充（批 5 F2）：猜卡封面堆取前 3 张有封面的；榜卡小列表取前 3 名「名次 歌名」。
     * 两处都用真实数据填，无数据整块不显、不编假条目；榜单数据与历史数据谁后到谁刷新（都是真封面／真榜名）。 */
    private void fillEntryCardData(List<MusicTrack> tracks, boolean chartList) {
        if (binding == null || tracks == null) return;
        try {
            List<String> covers = new ArrayList<>();
            for (MusicTrack t : tracks) {
                if (t != null && t.cover != null && !t.cover.isEmpty()) covers.add(t.cover);
                if (covers.size() >= 3) break;
            }
            if (!covers.isEmpty()) {
                ImageView[] stack = {binding.homeGuessCover1, binding.homeGuessCover2, binding.homeGuessCover3};
                binding.homeGuessStack.setVisibility(View.VISIBLE);
                for (int i = 0; i < stack.length; i++) {
                    if (i < covers.size()) {
                        stack[i].setVisibility(View.VISIBLE);
                        ImgUtil.loadRounded("", covers.get(i), stack[i]);
                    } else stack[i].setVisibility(View.GONE);
                }
            }
            if (chartList && !tracks.isEmpty()) {
                TextView[] lines = {binding.homeCardChart1, binding.homeCardChart2, binding.homeCardChart3};
                binding.homeCardChartList.setVisibility(View.VISIBLE);
                for (int i = 0; i < lines.length; i++) {
                    if (i < tracks.size() && tracks.get(i) != null) {
                        lines[i].setVisibility(View.VISIBLE);
                        lines[i].setText((i + 1) + ". " + tracks.get(i).displayTitle());
                    } else lines[i].setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void fillHomeChart(MusicChart chart, List<MusicTrack> tracks) {
        binding.homeChartTitle.setText(chart.name);
        fillHomeChartCovers();
        fillEntryCardData(tracks, true);
        TextView[] names = {binding.homeChartName1, binding.homeChartName2, binding.homeChartName3};
        TextView[] artists = {binding.homeChartArtist1, binding.homeChartArtist2, binding.homeChartArtist3};
        View[] rows = {binding.homeChartRow1, binding.homeChartRow2, binding.homeChartRow3};
        for (int i = 0; i < rows.length; i++) {
            if (i < tracks.size()) {
                rows[i].setVisibility(View.VISIBLE);
                names[i].setText(tracks.get(i).title);
                artists[i].setText(tracks.get(i).artist);
            } else rows[i].setVisibility(View.GONE);
        }
        // 第二波：内容回来了，内容区显、加载／空态隐；选中签下划线色跟榜首首封面语气色
        if (binding.homeChartContent != null) binding.homeChartContent.setVisibility(View.VISIBLE);
        if (binding.homeChartLoading != null) binding.homeChartLoading.setVisibility(View.GONE);
        if (binding.homeChartEmpty != null) binding.homeChartEmpty.setVisibility(View.GONE);
        refreshHomeChartTone(tracks);
    }

    /** 第二波：首页榜单卡选中签下划线色＝榜首首封面语气色（同 applyShuffleTone 取色口径；同一封面不重复取） */
    private void refreshHomeChartTone(List<MusicTrack> tracks) {
        if (binding == null || tracks == null || tracks.isEmpty()) return;
        String cover = tracks.get(0).cover;
        if (cover == null || cover.isEmpty() || cover.equals(homeChartToneCover)) return;
        homeChartToneCover = cover;
        try {
            ImgUtil.load(cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
                @Override
                public void onResourceReady(android.graphics.Bitmap bitmap,
                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                    if (binding == null || bitmap == null) return;
                    try {
                        // D-09（批 5 F2）：迟到的取色回调只许染「仍是当前签榜首封面」的色——
                        // 切签后旧签封面才取完色时，整段丢弃：不改 tone、不写缓存、不 restyle，
                        // 否则新签下划线会被旧签色染上（新签取色再失败就一直残留到下次成功）。
                        if (homeChartTracks.isEmpty() || !cover.equals(homeChartTracks.get(0).cover)) return;
                        homeChartTone = CoverColor.dominantColor(bitmap);
                        // tone 落袋即写静态缓存：下次新实例先用此 tone 画下划线，不先红再跳变
                        sHomeChartTone = homeChartTone;
                        sHomeChartToneCover = homeChartToneCover;
                        sHomeChartToneSet = true;
                        restyleHomeChartTabs();
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 首页卡只刷 3 张封面（名／歌手行不动，首屏已出；封面到了再补调取色） */
    private void fillHomeChartCovers() {
        ImageView[] covers = {binding.homeChartCover1, binding.homeChartCover2, binding.homeChartCover3};
        for (int i = 0; i < covers.length; i++) {
            if (i < homeChartTracks.size()) ImgUtil.loadRounded(homeChartTracks.get(i).title, homeChartTracks.get(i).cover, covers[i]);
            else covers[i].setImageDrawable(null);
        }
    }

    /** 首页卡封面按片补：按 id 把一片 Worker 封面贴到已显示的直连列表上，只动封面字段；
     * 返回卡面（前 3 张）里变了的位置，调用方只刷这些位置，不全量重刷 3 张。 */
    private List<Integer> applyHomeChartCovers(List<MusicTrack> shard) {
        List<Integer> changed = new ArrayList<>();
        if (shard == null || shard.isEmpty() || homeChartTracks.isEmpty()) return changed;
        Map<String, String> shardCover = new HashMap<>();
        for (MusicTrack s : shard) {
            if (s == null || s.id == null || s.cover == null || s.cover.isEmpty()) continue;
            shardCover.put(s.id, s.cover);
        }
        for (int i = 0; i < homeChartTracks.size(); i++) {
            MusicTrack cur = homeChartTracks.get(i);
            if (cur == null || cur.id == null) continue;
            String nc = shardCover.get(cur.id);
            if (nc == null || nc.equals(cur.cover)) continue;
            cur.cover = nc;
            if (i < 3) changed.add(i);
        }
        return changed;
    }

    /** 只刷指定位置的卡面封面（分片补封面用；不变的位置不重发 Glide，不逐张弹出） */
    private void fillHomeChartCoversAt(List<Integer> positions) {
        ImageView[] covers = {binding.homeChartCover1, binding.homeChartCover2, binding.homeChartCover3};
        for (int pos : positions) {
            if (pos < covers.length && pos < homeChartTracks.size()) {
                ImgUtil.loadRounded(homeChartTracks.get(pos).title, homeChartTracks.get(pos).cover, covers[pos]);
            }
        }
    }

    private void playHomeChartFrom(int index) {
        if (homeChartTracks.isEmpty() || index >= homeChartTracks.size()) return;
        // 榜单起播不是随心听队列：入队时服务队列来源写成榜单名，下次 onResume 副题
        // 读服务来源即自动回落「随机漫游」，不用再清粘性字段（已删）
        // B7：入队时写队列级来源＝榜单名、route＝home（榜单卡在首页，落点＝首页）
        MusicChart chart = (homeChartSel >= 0 && homeChartSel < homeChartTabs.size())
                ? homeChartTabs.get(homeChartSel) : null;
        MusicController.get().play(new ArrayList<>(homeChartTracks), index,
                chart == null ? null : chart.name, "home", null);
        MusicPlayerActivity.start(this);
    }

    // ================= ⋮ 菜单（顶上滑下的白色圆角卡，非全屏） =================
    // 行形态照瓜子菜单行（ProfileScreenKt m282MenuRow）：行内边距 16×14dp、图标框 32dp／圆角 9dp／图标色 12% 底、
    // 图标到标题 14dp、粗标题、右 12sp 灰字、按压 0.98；图标色插件管理 #AF52DE、软件设置 #FF2D55（瓜子代码值）。
    // 插件行右字不抄瓜子「LX 音源插件」（星辰还有 FreeJS）：显示实际插件数。

    private void showMoreMenu(View anchor) {
        if (isFinishing() || isDestroyed()) return;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setDimAmount(0f);
            dialog.getWindow().setGravity(Gravity.TOP);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.home_bg_menu);
        card.setElevation(dp(6));
        card.setClickable(true);
        final LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = dp(16);
        cardLp.leftMargin = side;
        cardLp.rightMargin = side;
        cardLp.topMargin = getStatusBarHeight() + dp(8);
        card.setVisibility(View.INVISIBLE);
        root.addView(card, cardLp);

        final boolean[] closing = new boolean[]{false};
        final java.util.function.Consumer<Runnable> dismissThen = (after) -> {
            if (closing[0]) return;
            closing[0] = true;
            card.animate().translationY(-(card.getHeight() + cardLp.topMargin)).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> {
                        try {
                            dialog.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (after != null) after.run();
                    }).start();
        };

        int pluginCount = 0;
        try {
            pluginCount = MusicSourceManager.get().listSites().size();
        } catch (Throwable ignored) {
        }
        // 插件行：紫色拼图＋实际插件数；设置行：红色齿轮＋「播放／下载／外观」（批 5 F1 起菜单只此两行）
        card.addView(menuRow(R.drawable.ic_music_puzzle, 0xFFAF52DE,
                getString(R.string.music_plugin_manage),
                pluginCount + " " + getString(R.string.music_plugins),
                () -> dismissThen.accept(() -> MusicListActivity.startSites(this))));
        // 批 5 F1：⋮ 菜单按任务口径只留两项（插件管理／设置）；原网盘行从本菜单下线，
        // 网盘音乐页本体未删（MusicCloudActivity 仍在，别线入口不受影响），报告已注明。
        View divider2 = new View(this);
        divider2.setBackgroundColor(0x14000000);
        LinearLayout.LayoutParams dividerLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerLp2.leftMargin = dp(62);
        card.addView(divider2, dividerLp2);
        card.addView(menuRow(R.drawable.ic_control_setting, 0xFFFF2D55,
                getString(R.string.music_setting),
                getString(R.string.music_menu_setting_sub),
                () -> dismissThen.accept(() -> MusicSettingsActivity.start(this))));

        root.setOnClickListener(v -> dismissThen.accept(null));
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                dismissThen.accept(null);
                return true;
            }
            return false;
        });
        dialog.setContentView(root);
        try {
            dialog.show();
        } catch (Throwable ignored) {
            return;
        }
        // py 脚本弹窗同款：decor 内容区点按即收（SourceScriptsDialog 在 onCreate 末尾给 android.R.id.content 挂 dismiss）。
        // ⋮菜单原有根布局点击收起保留（220ms 滑收）；decor 点击只补“根点击链路未触发”的漏网点按，不改动画形态。
        // 返回键仍走上面 OnKeyListener 的滑收（监听器先消费 BACK，不触发默认直接 dismiss）。
        try {
            View decorContent = dialog.findViewById(android.R.id.content);
            if (decorContent != null) decorContent.setOnClickListener(v -> dismissThen.accept(null));
        } catch (Throwable ignored) {
        }
        // 入场：先量高再从顶上滑下（INVISIBLE 起手，不闪一下）
        card.post(() -> {
            if (closing[0]) return;
            int h = card.getHeight() + cardLp.topMargin;
            card.setTranslationY(-h);
            card.setVisibility(View.VISIBLE);
            card.animate().translationY(0).setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        });
    }

    /** ⋮ 菜单行：浅色圆角方块图标＋粗标题＋右灰小字（瓜子菜单行形态，见 showMoreMenu 处注释）。 */
    private LinearLayout menuRow(int iconRes, int iconColor, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        box.setCornerRadius(dp(9));
        box.setColor((0x1F << 24) | (iconColor & 0x00FFFFFF));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(iconColor));
        icon.setBackground(box);
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(0xFF1A1A1A);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = dp(14);
        row.addView(tvTitle, titleLp);
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView tvSub = new TextView(this);
        tvSub.setText(sub);
        tvSub.setTextSize(12);
        tvSub.setTextColor(0xFF9A9AA0);
        tvSub.setSingleLine(true);
        row.addView(tvSub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        attachCardPress(row, 0.98f);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return dp(24);
    }

    // ================= 继续播放（批 5 F2：白卡四封面横排） =================

    /** 本次刷新用的继续播放列表快照（封面点击按下标取，与填充时同一份，防刷新间隙里历史变了点错首）。 */
    private List<MusicTrack> continueTracks = new ArrayList<>();

    /**
     * 继续播放白卡数据（批 5 F2 定稿口径）：走现行历史（MusicStore.loadRecent，新→旧），
     * 前 4 首封面横排；不足 4 首按实数显、零首整卡 GONE 不留空洞、不编假数据。
     * 历史为空但有活会话／断点队列时（极少数：历史被清而队列还在），回落显当前曲 1 张，
     * 不让正在播的歌在首页失踪——回落只显 1 张，语义仍是「继续播放」。
     * 点封面＝以历史整列为队列从该首播；圆键＝F1 口径续播（有会话 toggle、无会话按断点 playFrom）。
     */
    private void refreshContinue() {
        if (binding == null) return;
        try {
            List<MusicTrack> recent = MusicStore.loadRecent();
            List<MusicTrack> show = new ArrayList<>();
            if (recent != null) {
                for (MusicTrack t : recent) {
                    if (t != null) show.add(t);
                    if (show.size() >= 4) break;
                }
            }
            if (show.isEmpty()) {
                MusicTrack cur = null;
                try {
                    if (MusicController.get().isActive()) cur = MusicController.get().current();
                } catch (Throwable ignored) {
                }
                if (cur == null) {
                    List<MusicTrack> q = MusicStore.loadQueue();
                    if (q != null && !q.isEmpty()) {
                        int idx = MusicStore.loadIndex();
                        if (idx < 0 || idx >= q.size()) idx = 0;
                        cur = q.get(idx);
                    }
                }
                if (cur != null) show.add(cur);
            }
            continueTracks = show;
            if (show.isEmpty()) {
                binding.homeContinueCard.setVisibility(View.GONE);
                return;
            }
            // 猜卡封面堆回落数据源：榜单封面未到时先用历史封面顶上（同为真实封面，榜单到了会再刷）
            fillEntryCardData(show, false);
            binding.homeContinueCard.setVisibility(View.VISIBLE);
            binding.homeContinueTitle.setText(show.get(0).displayTitle());
            // 封面横排：每次刷新重建（最多 4 张，成本可忽略）；等宽 weight 铺满、张间 8dp
            LinearLayout row = binding.homeContinueCovers;
            row.removeAllViews();
            float density = getResources().getDisplayMetrics().density;
            for (int i = 0; i < show.size(); i++) {
                final int idx = i;
                MusicTrack t = show.get(i);
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, Math.round(72 * density), 1f);
                if (i > 0) lp.leftMargin = Math.round(8 * density);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setClickable(true);
                iv.setFocusable(true);
                iv.setContentDescription(t.displayTitle());
                if (t.cover == null || t.cover.isEmpty()) {
                    iv.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                            t.displayTitle(), false, 10 * density));
                } else {
                    ImgUtil.loadRounded(t.displayTitle(), t.cover, iv);
                }
                iv.setOnClickListener(v -> playContinueAt(idx));
                row.addView(iv);
            }
            boolean playing = false;
            try {
                playing = MusicController.get().isActive() && MusicController.get().isPlaying();
            } catch (Throwable ignored) {
            }
            binding.homeContinuePlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        } catch (Throwable ignored) {
            try {
                binding.homeContinueCard.setVisibility(View.GONE);
            } catch (Throwable ignored2) {
            }
        }
    }

    /** 点继续卡封面：以现行历史整列为队列、从该首开播并进播放页（队列来源＝最近播放，落点首页）。 */
    private void playContinueAt(int idx) {
        try {
            List<MusicTrack> recent = MusicStore.loadRecent();
            if (recent == null || recent.isEmpty() || idx < 0 || idx >= recent.size()) {
                // 历史与填充快照不一致（刚被清）时退回快照本身，不点空
                if (continueTracks.isEmpty() || idx < 0 || idx >= continueTracks.size()) return;
                recent = new ArrayList<>(continueTracks);
                MusicController.get().play(recent, idx, "最近播放", "home", null);
            } else {
                MusicController.get().play(new ArrayList<>(recent), idx, "最近播放", "home", null);
            }
            MusicPlayerActivity.start(this);
        } catch (Throwable ignored) {
        }
    }

    /** 继续播放圆键：有会话＝切换播放/暂停；只有断点＝按存盘队列/序号/进度续播并进播放页。 */
    private void onContinuePlay() {
        try {
            if (MusicController.get().isActive()) {
                MusicController.get().toggle();
                refreshContinue();
                return;
            }
            List<MusicTrack> q = MusicStore.loadQueue();
            if (q == null || q.isEmpty()) return;
            int idx = MusicStore.loadIndex();
            if (idx < 0 || idx >= q.size()) idx = 0;
            long pos = MusicStore.loadPosition();
            MusicController.get().playFrom(new ArrayList<>(q), idx, pos,
                    MusicStore.loadQueueSource(), MusicStore.loadQueueRoute(), MusicStore.loadQueueRouteParams());
            MusicPlayerActivity.start(this);
        } catch (Throwable ignored) {
        }
    }

    // ================= 五快捷数量（真数据，异步回填） =================

    // 第三波 H1/H2：五快捷数字口径统一标注（选定口径，卡下数字自带单位，不再裸数混排）：
    // 收藏＝首数、歌单＝个歌单（选了“个歌单”，不是单内总歌数）、本地＝首数、
    // 历史＝近 50 条内（loadRecent 上限 50 条，满 50 时只标“近50条”，不冒充总数；
    // 未满 50 时就是现有记录条数）、下载＝首数（下载登记条数）。
    private void refreshCounts() {
        if (binding == null) return;
        MusicLibrary.favoriteCount(n -> {
            if (binding != null && !isFinishing() && !isDestroyed()) binding.homeCountFavorite.setText(n + "首");
        });
        MusicLibrary.playlists(list -> {
            if (binding != null && !isFinishing() && !isDestroyed()) binding.homeCountSheets.setText((list == null ? 0 : list.size()) + "个歌单");
        });
        MusicDownloadStore.count(n -> {
            if (binding != null && !isFinishing() && !isDestroyed()) binding.homeCountDownload.setText(n + "首");
        });
        // 历史数：loadRecent 同步读（上限 50 条的小 JSON）；满 50 只标“近50条”，不冒充总数
        try {
            int recentN = MusicStore.loadRecent().size();
            binding.homeCountRecent.setText(recentN >= 50 ? "近50条" : recentN + "条");
        } catch (Throwable ignored) {
            binding.homeCountRecent.setText("0条");
        }
        // 本地数：DeviceMusicStore.scan 是阻塞扫描（MediaStore＋目录），走后台线程回填
        Task.execute(() -> {
            int n = 0;
            try {
                n = DeviceMusicStore.scan(this).size();
            } catch (Throwable ignored) {
            }
            int count = n;
            runOnUiThread(() -> {
                if (binding != null) binding.homeCountDevice.setText(count + "首");
            });
        });
        // 第二波：聆听报告（与五快捷计数同批刷新，不另起定时任务）
        refreshReport();
    }

    /** 第三波 H4：完整报告落点——树内无独立统计／报告页，卡与 ⋮菜单“听歌统计”行
     * 都落到播放历史页（报告数据的完整明细：最近 50 条内全部记录）；卡上摘要不变。
     * 搜索条“听歌识曲”未做：树内无识曲能力（只有 AiChatDialog 的语音转文字，不是
     * 听歌识曲／音频指纹），不硬做空壳按钮，见交回清单。
     * 首页歌单分类推荐区未做、心情 pill 点按跳页未改：分类区无现成推荐源可接，
     * 心情 pill 回调语义瓜子侧未抠实（只切选态还是建台跳页未证实），不默认改动。 */
    private void openFullReport() {
        MusicListActivity.startHistory(this);
    }

    /** 第二波：聆听报告卡——只用本地数据算：播放记录数（loadRecent 上限 50 条，口径即「最近 50 条内」）、
     * 收藏数、最爱歌手（最近播放里出现次数最多的非空歌手；并列取先出现的）。
     * 最近播放为空则整卡隐藏，不编假数据；瓜子 ListeningReportSection 的具体内容与数值未抠到，星辰侧口径如上。
     * 第三波：周聚合未做——loadRecent 存的 MusicTrack 没有播放时间戳（pushRecent 只按 id 去重、
     * 截断到 50 条，不写时间），算不出本周播放／总时长／峰值时段／活跃天；卡名与内容保持
     * 现有口径（最近 50 条内），不顶着报告名编周数据；卡本身可点进完整明细（见 openFullReport）。 */
    private void refreshReport() {
        if (binding == null) return;
        List<MusicTrack> recent;
        try {
            recent = MusicStore.loadRecent();
        } catch (Throwable ignored) {
            recent = null;
        }
        if (recent == null || recent.isEmpty()) {
            binding.homeReportCard.setVisibility(View.GONE);
            return;
        }
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (MusicTrack t : recent) {
            // 复核必-14：占位歌手（未知／未知歌手等）不计入最爱统计，空歌手本就跳过；
            // isPlaceholderArtist 对空串也返回 true，两者合一。
            if (t == null || com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(t.artist)) continue;
            String a = t.artist.trim();
            counts.put(a, counts.containsKey(a) ? counts.get(a) + 1 : 1);
        }
        String top = "";
        int topN = 0;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > topN) {
                topN = e.getValue();
                top = e.getKey();
            }
        }
        final int recentN = recent.size();
        final String topArtist = top;
        MusicLibrary.favoriteCount(n -> {
            if (binding == null || isFinishing() || isDestroyed()) return;
            // 建议项：照快捷口径，满 50 才加“近50条”限定；未满 50 时 recentN 就是实数，不加限定。
            binding.homeReportStats.setText("最近播放 " + recentN + " 首"
                    + (recentN >= 50 ? "（近50条内）" : "") + " · 收藏 " + n + " 首");
            if (topArtist.isEmpty()) {
                binding.homeReportTop.setVisibility(View.GONE);
            } else {
                binding.homeReportTop.setVisibility(View.VISIBLE);
                binding.homeReportTop.setText("最爱歌手：" + topArtist);
            }
            binding.homeReportCard.setVisibility(View.VISIBLE);
        });
    }

    // ================= 播放历史（树内无 recent 列表页，按现行「列表点歌」模式承接） =================

    /**
     * MusicListActivity 没有 recent 模式、树内也没有最近播放的展示页，
     * 这里按 MusicListActivity 的现行模式承接：列出 MusicStore.loadRecent() 的曲目，
     * 点一行＝以整份历史为队列从该行开播并进播放页（与 MusicListActivity.playTrack 同写法）。
     */
    /** 卡片按压反馈（只加触感，不吞点击）：默认 0.96 微缩、回弹同长 */
    private void attachCardPress(android.view.View v) {
        attachCardPress(v, 0.96f);
    }

    /** 卡片按压反馈：按压缩放由调用方给（瓜子三彩卡 0.98，其余现行 0.96） */
    private void attachCardPress(android.view.View v, float scale) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(scale).scaleY(scale).setDuration(150)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            }
            return false;
        });
    }

    /** 榜单三封面按 12dp 圆角裁切（见 initView 处注释：不动 ImgUtil 的前提下实现瓜子圆角） */
    private void applyChartCoverCorners() {
        android.view.ViewOutlineProvider provider = new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ResUtil.dp2px(12));
            }
        };
        ImageView[] covers = {binding.homeChartCover1, binding.homeChartCover2, binding.homeChartCover3};
        for (ImageView cover : covers) {
            if (cover == null) continue;
            cover.setOutlineProvider(provider);
            cover.setClipToOutline(true);
        }
    }

    /** 播放历史：走历史页（英雄卡＋排序＋续播卡＋多选＋清空），旧的首页对话框不再用 */
    private void showRecent() {
        MusicListActivity.startHistory(this);
    }

    /**
     * 随心听副题（瓜子 HomeScreenKt:2848：z5 ? "正在播放" : "随机漫游"）：
     * 改读服务队列来源字段——随心听起播时服务队列来源就是 home_shuffle（「随心听」），
     * 全树只有随心听页起播传这个来源；现队列来源是它＋服务正在播即显「正在播放」，
     * 其它一律回落「随机漫游」。旧粘性字段＋快照方案已删：字段从未被写是副题死文案
     * 的根因，服务来源是现队列的实时值，换队／暂停／重建都自然回落，不留假态；
     * 别处恰好播了与随心听同来源的队列时仍显「正在播放」——那确实是随心听队列在播，不算假态。
     * 刷新时机：onResume（回首页时重读现队列来源）。
     */
    private void refreshShuffleSubtitle() {
        if (binding == null) return;
        boolean playingShuffle = getString(R.string.home_shuffle).equals(MusicController.get().queueSource())
                && MusicController.get().isPlaying();
        binding.homeShuffleSub.setText(playingShuffle
                ? R.string.home_shuffle_playing : R.string.home_shuffle_sub);
        // 光晕脉动与副题同用 playingShuffle（oa3 z5），不分叉：只有随心听卡动，另两卡恒为静态层；
        // 系统动画开关在 HomeEntryHaloView 内再判，关时落回静态 0.5。
        binding.homeHaloShuffle.setPulsing(playingShuffle);
    }

    // ================= 心情电台卡（GZ MoodRadioPanel 移植，链路按他令走星辰落雪插件搜索） =================
    // GZ 原生链走网易云分类歌单服务端（o5→me3.j→me3.p→bd5.i，失败文案含「网易云分类歌单请求失败」，
    // jp6 source 写死 netease；me3 虽持有 DataPluginManager 但心情链无其调用点，且该管理器只有
    // getState/markAccessDenied/refresh，无搜歌入口）——星辰移植链是用户令定的自家落雪插件搜索方案，
    // 不是 GZ 原文方法；不走 GZ 服务器红线不变。GZ 链路实证与字段见对照表。
    // 12 心情数据照 GZ HomeScreenKt:314（名＋文案＋tone 色；渐变三段原始值见 moodGradient()，
    // 卡渐变向白混合 0.08/0.20/0.32，播放钮渐变终色向白混合 0.22）：
    // 怀旧 #D88445、清新 #20B996、浪漫 #DB5C98、伤感 #7388DB、治愈 #EAAF4B、放松 #9476D8、
    // 夜晚 #747AD1、清晨 #F5AC4D、散步 #5AAF7B、运动 #ED795F、民谣 #D99353、电子 #8874E7。
    // 点 pill：选中并建台（GZ 行为）；点播放钮：当前队列即本心情只 toggle，否则建台。
    // “是本心情”判定＝ prefs 快照与现队列同序同曲（同随心听副题口径）；状态文案映射：
    // 建台中→「正在连接」，本心情且正在播→「暂停收听」，本心情且已暂停→「继续收听」，否则→「开始收听」。
    // 状态刷新只在 onResume 与本地动作后（与随心听副题同口径；别页切播后回首页前状态可能滞后）。
    private String moodSelected;
    private boolean moodBuilding;
    private final List<View> moodPillViews = new ArrayList<>();

    /** 心情表：{名，文案，tone}，顺序即 GZ HomeScreenKt:314 代码顺序 */
    private static String[][] moodTable() {
        return new String[][]{
                {"怀旧", "让旧时光重新播放", "#D88445"}, {"清新", "给耳朵一阵清风", "#20B996"},
                {"浪漫", "把心动写进旋律", "#DB5C98"}, {"伤感", "把没说完的话听完", "#7388DB"},
                {"治愈", "让旋律轻轻接住你", "#EAAF4B"}, {"放松", "把紧绷慢慢放下", "#9476D8"},
                {"夜晚", "留一盏灯给深夜", "#747AD1"}, {"清晨", "用好心情叫醒今天", "#F5AC4D"},
                {"散步", "跟着晚风慢慢走", "#5AAF7B"}, {"运动", "让节拍推你向前", "#ED795F"},
                {"民谣", "听见故事里的远方", "#D99353"}, {"电子", "让脉冲点亮此刻", "#8874E7"},
        };
    }

    /** 心情卡渐变三段原始值（左→右，HomeScreenKt:314 前后渐变组；电子无专属组走默认） */
    private static String[] moodGradient(String mood) {
        switch (mood) {
            case "伤感": return new String[]{"#E8ECFF", "#D6DEFF", "#C6D3FA"};
            case "夜晚": return new String[]{"#E5E4FF", "#CDCFFA", "#BABFF2"};
            case "怀旧": return new String[]{"#FFEDD5", "#FFDDBA", "#FFCAA5"};
            case "放松": return new String[]{"#EFE7FF", "#E2D5FF", "#CCDFFF"};
            case "散步": return new String[]{"#E6F7DE", "#D1F0C9", "#BFEADB"};
            case "治愈": return new String[]{"#FFF3D6", "#FFE9B9", "#FFD9B5"};
            case "民谣": return new String[]{"#FFF0DA", "#FFE2C0", "#FAD2AF"};
            case "浪漫": return new String[]{"#FFE7F2", "#FFCFE8", "#F4BBE6"};
            case "清新": return new String[]{"#DDF9F1", "#C5F3E3", "#DAF6AC"};
            case "清晨": return new String[]{"#FFF5D9", "#FFE8AB", "#FFD3B4"};
            case "运动": return new String[]{"#FFE7DA", "#FFCFBB", "#FFBFAE"};
            default: return new String[]{"#EAE5FF", "#D7CEFF", "#C3D8FF"};
        }
    }

    /** tone 色：GZ 12 心情 tone 值（moodTable 第三列，恒为 #RRGGBB） */
    private static int moodTone(String mood) {
        for (String[] m : moodTable()) if (m[0].equals(mood)) return Color.parseColor(m[2]);
        return Color.parseColor("#D88445");
    }

    /** 文案：GZ 12 心情文案（moodTable 第二列） */
    private static String moodCopy(String mood) {
        for (String[] m : moodTable()) if (m[0].equals(mood)) return m[1];
        return "";
    }

    /** 颜色向白混合：mix = c + (255-c)*ratio（GZ 渐变／播放钮混白公式） */
    private static int mixWhite(int color, float ratio) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return Color.rgb(Math.round(r + (255 - r) * ratio),
                Math.round(g + (255 - g) * ratio), Math.round(b + (255 - b) * ratio));
    }

    /** 两色线性混合（ratio=0 取 a，=1 取 b）。 */
    private static int mixColors(int a, int b, float ratio) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * ratio),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * ratio),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * ratio));
    }

    /** 随心听渐变跟封面取色混（GZ：基色混向取色向白 0.75 后的色，混合比＝取色 alpha×0.1f；
     * 取色 alpha 未抠到，按 1.0 计即混合比 0.1；取色源是星辰侧口径：首页榜单首首封面（GZ 是首张每日歌曲封面，星辰无每日堆叠）；
     * 无榜单／无封面／取色失败时保持基色原样；混后无固定值。只算一次，榜单首首不变则不重算。 */
    private boolean shuffleToneApplied;

    private void applyShuffleTone() {
        if (binding == null || shuffleToneApplied || homeChartTracks.isEmpty()) return;
        String cover = homeChartTracks.get(0).cover;
        if (cover == null || cover.isEmpty()) return;
        shuffleToneApplied = true;
        try {
            ImgUtil.load(cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
                @Override
                public void onResourceReady(android.graphics.Bitmap bitmap,
                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                    if (binding == null || bitmap == null) return;
                    try {
                        int tone = CoverColor.dominantColor(bitmap);
                        int lightTone = mixWhite(tone, 0.75f);
                        int[] base = {0xFFB8EFF9, 0xFFBCEEDD, 0xFFE8FAA8};
                        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                new int[]{mixColors(base[0], lightTone, 0.1f),
                                        mixColors(base[1], lightTone, 0.1f),
                                        mixColors(base[2], lightTone, 0.1f)});
                        bg.setCornerRadius(dp(20));
                        binding.homeCardShuffle.setBackground(bg);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static int alphaColor(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** 默认选中心情按 GZ：当前队列标签是“心情电台 · XX”时取 XX（须在 12 心情表内），否则取怀旧。 */
    private String moodFromQueueSource() {
        String src = MusicController.get().queueSource();
        if (src != null && src.startsWith("心情电台 · ")) {
            String name = src.substring("心情电台 · ".length());
            for (String[] m : moodTable()) if (m[0].equals(name)) return name;
        }
        return "怀旧";
    }

    private void initMoodCard() {
        // 默认选中按 GZ：从当前队列标签反推（剥“心情电台 · ”前缀），反推不到取怀旧；不用 prefs 上次选择。
        moodSelected = moodFromQueueSource();
        // X-08（批 5 F2）：重建恢复优先于反推——用户刚点选还没成队列时反推不到，靠 savedInstanceState 把选中态带回来
        if (savedMood != null) {
            for (String[] m : moodTable()) {
                if (m[0].equals(savedMood)) {
                    moodSelected = savedMood;
                    break;
                }
            }
        }
        for (String[] m : moodTable()) {
            final String name = m[0];
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMarginEnd(dp(8));
            pill.setLayoutParams(lp);
            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dlp.setMarginEnd(dp(6));
            dot.setLayoutParams(dlp);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(moodTone(name));
            dot.setBackground(dotBg);
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextSize(12);
            tv.setLineHeight(dp(16));
            tv.setTextColor(Color.parseColor("#1C1C1E"));
            pill.addView(dot);
            pill.addView(tv);
            pill.setTag(name);
            pill.setOnClickListener(v -> {
                moodSelected = name;
                updateMoodUI();
                buildMood(name);
            });
            binding.moodPills.addView(pill);
            moodPillViews.add(pill);
        }
        pressScale(binding.btnMoodPlay, 0.9f);
        binding.btnMoodPlay.setOnClickListener(v -> {
            if (isThisMoodQueue()) {
                MusicController.get().toggle();
                refreshMoodState();
            } else {
                buildMood(moodSelected);
            }
        });
        updateMoodUI();
    }

    /** 当前队列是否为本心情建台队列：prefs 快照非空且与现队列同序同曲 */
    private boolean isThisMoodQueue() {
        String snap = MusicStore.getMoodQueueKeys();
        if (snap == null || snap.isEmpty()) return false;
        List<String> cur = trackKeys(MusicController.get().queue());
        if (cur.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        for (String k : cur) sb.append(k).append('\n');
        return sb.toString().equals(snap);
    }

    private void refreshMoodState() {
        if (binding == null) return;
        updateMoodUI();
    }

    private void updateMoodUI() {
        if (binding == null || moodSelected == null) return;
        int tone = moodTone(moodSelected);
        // 卡渐变：横向三段（左→右），原始值向白混合 0.08/0.20/0.32，圆角 20dp
        String[] g = moodGradient(moodSelected);
        GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{mixWhite(Color.parseColor(g[0]), 0.08f),
                        mixWhite(Color.parseColor(g[1]), 0.20f),
                        mixWhite(Color.parseColor(g[2]), 0.32f)});
        cardBg.setCornerRadius(dp(20));
        binding.homeMoodCard.setBackground(cardBg);
        binding.tvMoodName.setText(moodSelected);
        binding.tvMoodCopy.setText(moodCopy(moodSelected));
        // pills：选中 tone 0.18 底＋字重 700，未选中白 50% 底＋字重 500（sans-serif-medium 近似）
        for (View pill : moodPillViews) {
            boolean sel = moodSelected.equals(pill.getTag());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(sel ? alphaColor(tone, 0.18f) : 0x80FFFFFF);
            pill.setBackground(bg);
            TextView tv = (TextView) ((ViewGroup) pill).getChildAt(1);
            tv.setTypeface(sel ? android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    : android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }
        // 播放钮：横向渐变 tone→tone 向白混合 0.22，圆；播放中暂停图标 18dp（内边距 11dp），未播放播放图标 24dp（内边距 8dp）
        boolean playing = isThisMoodQueue() && MusicController.get().isPlaying();
        GradientDrawable btnBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{tone, mixWhite(tone, 0.22f)});
        btnBg.setShape(GradientDrawable.OVAL);
        binding.btnMoodPlay.setBackground(btnBg);
        binding.btnMoodPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        binding.btnMoodPlay.setPadding(dp(playing ? 11 : 8), dp(playing ? 11 : 8), dp(playing ? 11 : 8), dp(playing ? 11 : 8));
        binding.btnMoodPlay.setColorFilter(Color.WHITE);
        // 波形：tone 0.7，播放振幅 1.0＋动画，暂停 0.35 静态
        binding.moodWave.setTone(tone);
        binding.moodWave.setPlaying(playing);
        // 钮旁状态文案（瓜子条件：本心情播／停＝暂停／继续收听；非本心情时正在播别的队列＝暂停收听，其余才＝开始收听）
        binding.tvMoodState.setText(moodBuilding ? "正在连接"
                : isThisMoodQueue() ? (MusicController.get().isPlaying() ? "暂停收听" : "继续收听")
                : MusicController.get().isPlaying() ? "暂停收听" : "开始收听");
    }

    /** 按心情名走星辰落雪插件搜索拼歌单播（不走 GZ 服务器）；搜不到 toast 同款文案 */
    private void buildMood(String mood) {
        if (moodBuilding) return;
        moodBuilding = true;
        updateMoodUI();
        Task.execute(() -> {
            List<MusicTrack> out = new ArrayList<>();
            try {
                for (MusicSite site : MusicRepository.searchableSites()) {
                    if (!MusicSourceManager.isLx(site)) continue;
                    try {
                        List<MusicTrack> hits = MusicRepository.searchSite(site, mood);
                        if (hits != null) out.addAll(hits);
                    } catch (Throwable ignored) {
                    }
                    if (out.size() >= 30) break;
                }
            } catch (Throwable ignored) {
            }
            final List<MusicTrack> tracks = out.size() > 30 ? new ArrayList<>(out.subList(0, 30)) : out;
            runOnUiThread(() -> {
                moodBuilding = false;
                if (tracks.isEmpty()) {
                    Notify.show("暂时没找到这份心情音乐，请稍后重试");
                    updateMoodUI();
                    return;
                }
                List<String> keys = trackKeys(tracks);
                StringBuilder sb = new StringBuilder();
                for (String k : keys) sb.append(k).append('\n');
                MusicStore.setMoodQueueKeys(sb.toString());
                MusicController.get().play(new ArrayList<>(tracks), 0, "心情电台 · " + mood, "home", null);
                updateMoodUI();
                MusicPlayerActivity.start(this);
            });
        });
    }

    /** 曲目键：优先 id，无 id 时用标题＋歌手拼键（同曲不同源时 id 不同即判为不同队列）。 */
    private static List<String> trackKeys(List<MusicTrack> tracks) {
        List<String> keys = new ArrayList<>();
        if (tracks == null) return keys;
        for (MusicTrack t : tracks) {
            if (t == null) keys.add("");
            else if (t.id != null && !t.id.isEmpty()) keys.add(t.id);
            else keys.add((t.title == null ? "" : t.title) + "\n" + (t.artist == null ? "" : t.artist));
        }
        return keys;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** GZ 卡片按压缩放（入口卡 0.98、心情播放钮 0.9）：按下缩、抬起／取消回弹；返回 false 不吞点击 */
    private void pressScale(android.view.View v, float scale) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                view.setScaleX(scale);
                view.setScaleY(scale);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
            return false;
        });
    }

    private void pressScale(android.view.View v) {
        pressScale(v, 0.98f);
    }
}
