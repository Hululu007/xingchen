package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.fongmi.android.tv.bean.music.MusicPluginTest;
import com.fongmi.android.tv.bean.music.MusicRuntimeStatus;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.LxPlatformSearch;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicListBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;


import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 歌曲列表页：大标题＋工具条，行＝封面左＋两行字＋徽章＋时长/⋮，
 * 当前播行高亮＋歌名变金。兼做搜索、我的歌单、榜单列表。
 */
public class MusicListActivity extends BaseActivity {

    private ActivityMusicListBinding binding;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private final List<Object> cards = new ArrayList<>();
    private ListAdapter adapter;
    private String mode = "sites";
    /** onResume 首过标记（中-03）：首次由 initView 的 load() 负责，之后回页才重拉。 */
    private boolean resumedOnce = false;

    private String keyword = "";
    private String bigTitle = "";
    /** 对照表 P2：插件导入进行中（头卡标题变「正在导入插件」、本地按钮标题变「导入中…」） */
    private boolean pluginImporting;
    private String sitePlatform = "";
    private long playlistId = -1;

    public static void startSites(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "sites");
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
        binding = ActivityMusicListBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 本页 Manifest 没声明 windowSoftInputMode，默认键盘一弹窗口就 resize：
        // 内容区被压到键盘上方，状态文字（覆盖层里的 empty）会被带着乱跑。
        // 锁成 adjustPan：窗口不缩放、不重排；本页唯一输入框在顶部，键盘遮不到它，平移量恒为 0，
        // 状态文字位置只跟页面有关、跟键盘无关；搜索框可见可用、列表滚动行为不变。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        Intent i = getIntent();
        mode = i.getStringExtra("mode");
        // 拆页 p11/p12：tracks 与 site 已先后独立成页（MusicTracksActivity／MusicSiteActivity），
        // 本壳只剩 sites 一 mode；落到本壳的任何 mode 一律归 sites
        // （插件管理是无需 extras 的自足页），不许再落已删的分支把壳改瘫。
        if (!"sites".equals(mode)) mode = "sites";
        keyword = i.getStringExtra("keyword");
        if (keyword == null) keyword = "";
        sitePlatform = i.getStringExtra("sitePlatform");
        if (sitePlatform == null) sitePlatform = "";
        // X-08（批 5 F2）：重建恢复——已输入与选择态从 savedInstanceState 回填（字段在 load() 之前就位，
        // 恢复路径与正常进页同一条加载链）；滚动位置存布局管理器状态、数据到位后在 onResume 一次性回滚。
        if (savedInstanceState != null) {
            String savedKw = savedInstanceState.getString("ml_keyword");
            if (savedKw != null) keyword = savedKw;
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        bigTitle = i.getStringExtra("title");
        if (bigTitle == null) bigTitle = "";

        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter = new ListAdapter());
        // 插件页单滚动流：插件行与下方专区同页滚动，行再多也不会被专区盖住、不用在列表内盲滑找行。
        // 仅 sites 模式（mode 本页实例内只由 intent 定一次，不会中途切模式，其它模式不受影响）；
        // 框架 ScrollView 足够——列表已关嵌套滚动、一次性量出全部行，不需要 NestedScrollView 的嵌套协调。
        if (mode.equals("sites") && binding.recycler.getParent() instanceof LinearLayout) {
            LinearLayout content = (LinearLayout) binding.recycler.getParent();
            ViewGroup root = (ViewGroup) binding.getRoot();
            root.removeView(content);
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content.setLayoutParams(new android.widget.ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.addView(content);
            // 2026-10-22 插件管理打开是空的根因：背景层是不透明底色（BASE 0xFFFAFAFC＋drawColor），
            // 原来插 0 号位把滚动层垫到背景底下，整页被盖住。改插到背景层后面一位，恢复原来层级（内容在背景上、空态覆盖层仍在最上）。
            // 批 5 F2：背景已收口到全域统一 MusicGlassBackgroundView（原 AuroraBackgroundView 摘除），锚点随之换它。
            int auroraIndex = binding.musicGlassBg == null ? -1 : root.indexOfChild(binding.musicGlassBg);
            root.addView(scroll, auroraIndex >= 0 ? auroraIndex + 1 : 0);
            binding.recycler.setNestedScrollingEnabled(false);
            ViewGroup.LayoutParams rlp = binding.recycler.getLayoutParams();
            rlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            binding.recycler.setLayoutParams(rlp);
            binding.recycler.setPadding(binding.recycler.getPaddingLeft(), binding.recycler.getPaddingTop(),
                    binding.recycler.getPaddingRight(), 0);
        }
        binding.btnPlayAll.setOnClickListener(v -> shufflePlay());
        // 通用“播放全部”顺序入口
        binding.btnPlaySeq.setOnClickListener(v -> playAllSequential());
        binding.btnSort.setOnClickListener(v -> showGenericSortDialog());
        // 对照表 P4：页内两入口按钮直连现有 pickLocalFile／inputSiteUrl，不再走二选一弹窗
        binding.btnPluginLocal.setOnClickListener(v -> pickLocalFile());
        binding.btnPluginOnline.setOnClickListener(v -> inputSiteUrl());
        binding.btnPluginSort.setOnClickListener(v -> showPluginSortDialog());
        binding.btnSearch.setOnClickListener(v -> MusicSearchActivity.startSearch(this, ""));
        MusicController.get().addListener(controllerListener);
        load();
    }

    /**
     * 回页刷新本地数据（中-03）：此前本页没有 onResume，从播放页/别页改了收藏、
     * 删了下载、移了歌单回来都还是旧列表，只能杀页重进。只重拉本地数据模式，
     * 网络模式（榜单/站点/搜索/猜你喜欢）不回页重拉，免得白耗流量与接口。
     */
    /** X-08（批 5 F2）：切源／旋转／主题重建回来不丢已输入与滚动位置——输入与选择态走字段回填（见 initView），
     * 滚动位置存布局管理器状态；保存的只是本页 UI 态，不碰任何用户数据与队列。 */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putString("ml_keyword", binding != null && binding.etKeyword != null
                    && binding.etKeyword.getText() != null
                    ? binding.etKeyword.getText().toString() : keyword);
            if (binding != null && binding.recycler != null
                    && binding.recycler.getLayoutManager() != null) {
                outState.putParcelable("ml_listState",
                        binding.recycler.getLayoutManager().onSaveInstanceState());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // X-08 滚动回滚：等本帧布局后一次性应用（数据异步未到时布局管理器会钳到现有行数，不崩；应用后即清，不反复拽）
        if (pendingListState != null && binding != null && binding.recycler != null) {
            final android.os.Parcelable state = pendingListState;
            pendingListState = null;
            binding.recycler.post(() -> {
                try {
                    if (binding != null && binding.recycler != null
                            && binding.recycler.getLayoutManager() != null) {
                        binding.recycler.getLayoutManager().onRestoreInstanceState(state);
                    }
                } catch (Throwable ignored) {
                }
            });
        }
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        switch (mode) {
            default:
                break;
        }
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        // 复核必-10：第二段升降序弹层的 post 必须随页销毁取消，不许页死后还弹。
        if (sortDialogHandler != null) sortDialogHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private boolean isCardMode() {
        return mode.equals("sites");
    }

    private void load() {
        // 本页对照修：空态点击监听是共享 overlay 的，换模式先清掉，防本地页的重扫监听漏到别页
        binding.empty.setOnClickListener(null);
        if (binding.pluginSections != null) binding.pluginSections.setVisibility(View.GONE);
        // 对照表 P2/P4/P7/P22：sites 专属视图默认 GONE，非 sites 模式必须保持不可见
        if (binding.pluginHeaderCard != null) binding.pluginHeaderCard.setVisibility(View.GONE);
        if (binding.pluginAddRow != null) binding.pluginAddRow.setVisibility(View.GONE);
        if (binding.pluginInstalledHeader != null) binding.pluginInstalledHeader.setVisibility(View.GONE);
        if (binding.pluginEmptyCard != null) binding.pluginEmptyCard.setVisibility(View.GONE);
        binding.tvTitle.setText(titleForMode());
        // 顶栏恒显（歌单列表／详情已拆独立页，本页无隐藏顶栏的模式）
        binding.headerBar.setVisibility(View.VISIBLE);
        // 歌单页空态块已随歌单页拆出，本页只复位藏起。
        // 批 5 F2：背景全模式收口——原 AuroraBackgroundView（仅 sheets/sites 可见）摘除，
        // 列表族各模式（收藏／歌单／歌单详情／本地／历史／下载／榜单／歌单广场／搜索／插件）一律用
        // 全域统一 MusicGlassBackgroundView（与首页同一份实现），内容层透明、根底 home_bg_page 只作兜底。
        if (binding.musicGlassBg != null) binding.musicGlassBg.setVisibility(View.VISIBLE);
        binding.sheetsEmpty.setVisibility(View.GONE);
        binding.bigTitleRow.setVisibility(View.VISIBLE);
        binding.toolbar.setVisibility(mode.equals("sites") ? View.GONE : View.VISIBLE);
        // 搜索框行已随搜索页拆出：本页 searchBar 恒 GONE（布局默认 gone，此处不显）
        binding.btnSearch.setVisibility(mode.equals("sites") ? View.GONE : View.VISIBLE);
        switch (mode) {
            case "sites":
            default:
                bigTitle = getString(R.string.music_plugin_manage);
                loadSites();
                break;
        }
    }

    private String titleForMode() {
        switch (mode) {
            case "sites":
                return getString(R.string.music_plugin_manage);
            default:
                return getString(R.string.music_playlist);
        }
    }

    /** 显示用歌手：占位（空／<unknown>／unknown 类，见 LocalTrackMeta）一律显统一文案，不只空值。 */
    private String artistDisplay(String raw) {
        return com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(raw)
                ? getString(R.string.music_unknown_artist) : raw;
    }

    /** 插件管理：全量列表（停用的也得在，不然关了就找不回来）＋头卡／两按钮／已安装头／排序后列表 */
    /** 第二波：插件分组头占位（adapter 内插项，非插件行；标题取 wave2 串） */
    private static class PluginGroup {
        final String title;

        PluginGroup(String title) {
            this.title = title;
        }
    }

    /** 歌词型判定：复用头卡现有歌词三档（检测证实纯歌词／搜歌不支持／内置歌词预置——预置另归内置组，此处只判前两档） */
    private boolean isLyricPluginSite(MusicSite s) {
        if (s == null) return false;
        MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
        return (t != null && t.lyricOk && !t.audioOk) || s.musicSearch == 2;
    }

    private void loadSites() {
        showLoading();
        Task.execute(() -> {
            List<MusicSite> sites = MusicSourceManager.get().listSites();
            List<MusicSite> ordered = sortSites(sites);
            runOnUiThread(() -> {
                pluginImporting = false;
                cards.clear();
                // 批 6 双轨分区（定案：落雪 JS＋FreeJS 双轨各自成列，空轨不渲染；组内保持排序后顺序）。
                // 旧三组（音源／其他／内置歌词）按能力分，与双轨口径冲突，本批起改按脚本格式分轨；
                // 内置歌词预置（歌词网／歌词千寻）是 FreeJS 种子，归 FreeJS 轨，行内「官方」段标识不变。
                List<MusicSite> lxTrack = new ArrayList<>();
                List<MusicSite> freeTrack = new ArrayList<>();
                for (MusicSite s : ordered) {
                    if (MusicSourceManager.isLx(s)) lxTrack.add(s);
                    else freeTrack.add(s);
                }
                if (!lxTrack.isEmpty()) {
                    cards.add(new PluginGroup(getString(R.string.wave2_plugin_group_lx)));
                    cards.addAll(lxTrack);
                }
                if (!freeTrack.isEmpty()) {
                    cards.add(new PluginGroup(getString(R.string.wave2_plugin_group_freejs)));
                    cards.addAll(freeTrack);
                }
                adapter.notifyDataSetChanged();
                // 第 8 项去双标题：sites 模式只留顶部居中“插件管理”，下方大标题行整行隐藏
                // （GZ 页内只有头卡标题“ N 个插件”，没有第二个“插件管理 N 个插件”大标题）
                binding.bigTitleRow.setVisibility(View.GONE);
                refreshPluginHeader(sites);
                refreshPluginSections();
                // P22：sites 模式空态改为空态卡，替代原 empty 文本；非空时两者都隐藏
                binding.empty.setVisibility(View.GONE);
                binding.pluginEmptyCard.setVisibility(sites.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** 第三波 B⑦：头卡图标导入中旋转指示（导入结束即停并归零）。 */
    private android.animation.ObjectAnimator pluginHeaderSpin;

    private void refreshPluginHeaderSpin() {
        try {
            if (binding.pluginHeaderIcon == null) return;
            if (pluginImporting) {
                if (pluginHeaderSpin == null) {
                    pluginHeaderSpin = android.animation.ObjectAnimator.ofFloat(
                            binding.pluginHeaderIcon, "rotation", 0f, 360f);
                    pluginHeaderSpin.setDuration(1000);
                    pluginHeaderSpin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    pluginHeaderSpin.start();
                }
            } else if (pluginHeaderSpin != null) {
                pluginHeaderSpin.cancel();
                pluginHeaderSpin = null;
                binding.pluginHeaderIcon.setRotation(0f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 第三波 B③：运行状态面板（徽章点开；与 C10 卡同源数据：已加载／已启用／当前单选状态／异常名表／进行中态）。
     * 分平台检测行不在此：检测层 MusicPluginTest 未存分平台结果（见交卷），不编分平台行。 */
    private void showRuntimeStatusPanel() {
        MusicRuntimeStatus st = MusicSourceManager.get().runtimeStatus();
        List<MusicSite> sites = MusicSourceManager.get().listSites();
        boolean hasUntested = false;
        for (MusicSite site : sites) {
            if (site.enabled && MusicSourceManager.get().loadPluginTest(site.srcUrl) == null) {
                hasUntested = true;
                break;
            }
        }
        String state;
        if (st.quarantinedRunners > 0) state = "运行异常";
        else if (st.failedPlugins > 0) state = "启动失败";
        else if (st.initializing) state = "启动中";
        else if (st.enabledLocalPlugins == 0) state = "无本地插件";
        else if (hasUntested) state = "未检测";
        else state = "运行正常";
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        box.addView(runtimePanelLine("当前状态：" + state));
        box.addView(runtimePanelLine("已加载 " + st.liveRunners + " 个 · 已启用 "
                + st.enabledLocalPlugins + " 个"));
        box.addView(runtimePanelLine(st.initializing ? "正在启动…"
                : st.reloading ? "正在重新加载…" : "进行中：无"));
        if (!st.quarantinedNames.isEmpty()) {
            box.addView(runtimePanelLine("暂时停用 " + st.quarantinedRunners + " 个："
                    + android.text.TextUtils.join("、", st.quarantinedNames)));
        }
        if (!st.failedNames.isEmpty()) {
            box.addView(runtimePanelLine("启动失败 " + st.failedPlugins + " 个："
                    + android.text.TextUtils.join("、", st.failedNames)));
        }
        TextView reload = new TextView(this);
        reload.setText("重新加载插件");
        reload.setTextSize(14);
        reload.setTextColor(0xFFFA243C);
        reload.setPadding(dp(12), dp(8), dp(12), dp(8));
        reload.setOnClickListener(v -> {
            MusicSourceManager.get().reloadBridges();
            refreshPluginSections();
            if (adapter != null) adapter.notifyDataSetChanged();
            Notify.show("已重新加载");
        });
        box.addView(reload);
        new MaterialAlertDialogBuilder(this)
                .setTitle("插件运行状态")
                .setView(box)
                .setPositiveButton("知道了", null)
                .show();
    }

    private TextView runtimePanelLine(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF1C1C1E);
        return tv;
    }

    /** 对照表 P2/P3/P23：头卡标题／副标题／徽章（仅 sites 模式；图标用现有 ic_music_note 近似，星辰无拼图图标） */
    private void refreshPluginHeader(List<MusicSite> sites) {
        if (!mode.equals("sites")) return;
        binding.pluginHeaderCard.setVisibility(View.VISIBLE);
        binding.pluginAddRow.setVisibility(View.VISIBLE);
        binding.pluginInstalledHeader.setVisibility(View.VISIBLE);
        int total = sites.size();
        int enabled = 0;
        int lyricCount = 0;
        int tested = 0;
        for (MusicSite s : sites) {
            if (s.enabled) enabled++;
            MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (t != null) tested++;
            // 歌词归类三档：只看检测结果会把未检测的歌词插件全算成音源（实拍“歌词：0”的根因）。
            // ① 检测已证实纯歌词；② 搜歌流已判据该插件不支持搜歌（musicSearch=2，桥声明能力，非空结果推断）；
            // ③ 应用自带歌词预置（歌词网／歌词千寻，种子身份即歌词型）。
            // 用户自加的歌词插件在未检测、未搜歌探查前仍暂计音源——那是未知，不是误标。
            if ((t != null && t.lyricOk && !t.audioOk)
                    || s.musicSearch == 2
                    || MusicSourceManager.isBuiltinLyricPreset(s.srcUrl)) lyricCount++;
        }
        int audioCount = total - lyricCount;
        if (pluginImporting) {
            binding.pluginHeaderTitle.setText(R.string.music_plugin_importing);
        } else if (total == 0) {
            binding.pluginHeaderTitle.setText(R.string.music_plugin_no_plugin);
        } else {
            binding.pluginHeaderTitle.setText(total + " " + getString(R.string.music_plugins));
        }
        if (total == 0) {
            binding.pluginHeaderSub.setText(R.string.music_plugin_import_desc);
        } else if (enabled == 0) {
            binding.pluginHeaderSub.setText(R.string.music_plugin_all_disabled);
        } else {
            binding.pluginHeaderSub.setText(getString(R.string.music_plugin_running) + "：" + enabled
                    + " 音源：" + audioCount + " 歌词：" + lyricCount);
        }
        binding.pluginLocalTitle.setText(pluginImporting ? "导入中…" : getString(R.string.music_plugin_local_title));
        // P7 已安装头计数：N / M 正在工作（M=启用且检测态非 FAILED；未检测计入工作数）
        int working = 0;
        for (MusicSite s : sites) {
            if (!s.enabled) continue;
            MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (t == null || t.state != MusicPluginTest.State.FAILED) working++;
        }
        // GZ 计数顺序（复核纠偏）：字面是“工作数 / 总数 正在工作”，不是总数在前。
        // 未全检测时后缀检测覆盖率——行全是“未检测”时头再称“正在工作”而不提覆盖率，就是实拍的自相矛盾。
        String installed = working + " / " + total + " " + getString(R.string.music_plugin_working);
        if (tested < total) installed += " · 已检测 " + tested + " 个";
        binding.pluginInstalledCount.setText(installed);
        binding.btnPluginSort.setText(pluginSortLabel());
        // 第三波 B⑦：排序钮补 16dp 下拉箭头（drawableEnd，不再用文案里的 ▾ 充数）
        try {
            android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_chev_v2);
            if (arrow != null) {
                arrow.setBounds(0, 0, dp(16), dp(16));
                binding.btnPluginSort.setCompoundDrawables(null, null, arrow, null);
                binding.btnPluginSort.setCompoundDrawablePadding(dp(4));
            }
        } catch (Throwable ignored) {
        }
        // 第三波 B③：运行状态徽章可点，出状态面板
        binding.pluginHeaderBadge.setOnClickListener(v -> showRuntimeStatusPanel());
        refreshPluginHeaderSpin();
        // P23 头卡徽章（仅展示，不弹面板）：优先级 隔离＞失败＞启动中＞无本地＞未检测＞正常。
        // “未检测”指已有启用插件但一个检测结果都没有——此时称“运行正常”与行态打架，改称未检测。
        MusicRuntimeStatus st = MusicSourceManager.get().runtimeStatus();
        boolean hasUntested = false;
        for (MusicSite s : sites) {
            if (s.enabled && MusicSourceManager.get().loadPluginTest(s.srcUrl) == null) {
                hasUntested = true;
                break;
            }
        }
        String badge;
        int badgeColor;
        if (st.quarantinedRunners > 0) {
            badge = "运行异常";
            badgeColor = 0xFFFF3B30;
        } else if (st.failedPlugins > 0) {
            badge = "启动失败";
            badgeColor = 0xFFFF3B30;
        } else if (st.initializing) {
            badge = "启动中";
            badgeColor = 0xFF8E8E93;
        } else if (st.enabledLocalPlugins == 0) {
            badge = "无本地插件";
            badgeColor = 0xFF8E8E93;
        } else if (hasUntested) {
            badge = "未检测";
            badgeColor = 0xFF8E8E93;
        } else {
            badge = "运行正常";
            badgeColor = 0xFF34C759;
        }
        binding.pluginHeaderBadge.setText(badge);
        binding.pluginHeaderBadge.setTextColor(badgeColor);
        // 瓜子头卡徽章＝同色淡底药丸（非描边框）：底色＝徽章色 12% 透明度、满圆角
        android.graphics.drawable.GradientDrawable badgeBg =
                new android.graphics.drawable.GradientDrawable();
        badgeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(dp(100));
        badgeBg.setColor((badgeColor & 0x00FFFFFF) | 0x1F000000);
        binding.pluginHeaderBadge.setBackground(badgeBg);
    }

    /** 第三波 B⑤：平台排序真做——按 site.platform 分组排序（同平台内保原顺序，稳定排序），
     * 不再是点了没反应的假选项。 */
    // （文案口径见 pluginSortLabel）
    private String pluginSortLabel() {
        switch (MusicStore.getPluginSort()) {
            case MusicStore.PLUGIN_SORT_PLATFORM_COUNT:
                return getString(R.string.music_plugin_sort_platform);
            case MusicStore.PLUGIN_SORT_SPEED:
                return getString(R.string.music_plugin_sort_speed);
            case MusicStore.PLUGIN_SORT_AVAILABLE:
                return getString(R.string.music_plugin_sort_available);
            default:
                return getString(R.string.music_plugin_sort_manual);
        }
    }

    private List<MusicSite> sortSites(List<MusicSite> sites) {
        List<MusicSite> out = new ArrayList<>(sites);
        String sort = MusicStore.getPluginSort();
        if (MusicStore.PLUGIN_SORT_AVAILABLE.equals(sort)) {
            Collections.sort(out, (a, b) -> {
                MusicPluginTest ta = MusicSourceManager.get().loadPluginTest(a.srcUrl);
                MusicPluginTest tb = MusicSourceManager.get().loadPluginTest(b.srcUrl);
                int va = (ta != null && ta.state == MusicPluginTest.State.AVAILABLE) ? 0 : 1;
                int vb = (tb != null && tb.state == MusicPluginTest.State.AVAILABLE) ? 0 : 1;
                return va - vb;
            });
        } else if (MusicStore.PLUGIN_SORT_SPEED.equals(sort)) {
            Collections.sort(out, (a, b) -> Long.compare(pluginSpeedMs(a), pluginSpeedMs(b)));
        }
        if (MusicStore.PLUGIN_SORT_PLATFORM_COUNT.equals(sort)) {
            Collections.sort(out, (a, b) -> {
                String pa = a.platform == null ? "" : a.platform;
                String pb = b.platform == null ? "" : b.platform;
                return pa.compareTo(pb);
            });
        }
        // MANUAL 保持存盘顺序
        return out;
    }

    /** SPEED 拟映射：检测取链耗时升序，未检测（无表或耗时 ≤0）排后 */
    private long pluginSpeedMs(MusicSite s) {
        MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
        if (t == null || t.latencyMs <= 0) return Long.MAX_VALUE;
        return t.latencyMs;
    }

    private void showPluginSortDialog() {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.music_plugin_sort_manual));
        opts.add(getString(R.string.music_plugin_sort_platform));
        opts.add(getString(R.string.music_plugin_sort_speed));
        opts.add(getString(R.string.music_plugin_sort_available));
        String cur = MusicStore.getPluginSort();
        int checked = MusicStore.PLUGIN_SORT_PLATFORM_COUNT.equals(cur) ? 1
                : MusicStore.PLUGIN_SORT_SPEED.equals(cur) ? 2
                : MusicStore.PLUGIN_SORT_AVAILABLE.equals(cur) ? 3 : 0;
        String[] values = {MusicStore.PLUGIN_SORT_MANUAL, MusicStore.PLUGIN_SORT_PLATFORM_COUNT,
                MusicStore.PLUGIN_SORT_SPEED, MusicStore.PLUGIN_SORT_AVAILABLE};
        MusicOptionsDialog.show(this, getString(R.string.music_plugin_installed), opts, checked, idx -> {
            MusicStore.setPluginSort(values[idx]);
            loadSites();
        });
    }

    /** 对照表 P14：手动排序模式下相邻换位＋落盘＋刷新（仅 sites 模式内，存盘顺序即现序） */
    private void movePlugin(MusicSite site, int delta) {
        List<MusicSite> sites = MusicSourceManager.get().listSites();
        int idx = -1;
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i) != null && site.srcUrl != null && site.srcUrl.equals(sites.get(i).srcUrl)) {
                idx = i;
                break;
            }
        }
        int to = idx + delta;
        if (idx < 0 || to < 0 || to >= sites.size()) return;
        Collections.swap(sites, idx, to);
        MusicSourceManager.get().saveSites(sites);
        loadSites();
    }

    private static final int REQ_PICK_PLUGIN = 4213;

    private void pickLocalFile() {
        // 第三波 B①现状：单文件选择（未开 EXTRA_ALLOW_MULTIPLE），一次只装一个插件文件
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/javascript", "text/javascript", "text/plain"});
        startActivityForResult(Intent.createChooser(i, getString(R.string.music_plugin_local)), REQ_PICK_PLUGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_PLUGIN || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Task.execute(() -> {
            try {
                byte[] bytes = readAll(uri);
                runOnUiThread(() -> confirmPluginImport(() -> {
                    pluginImporting = true;
                    refreshPluginHeader(MusicSourceManager.get().listSites());
                    runPluginImportWithProgress("正在安装（1 个文件）…",
                            () -> MusicSourceManager.get().addLocalSite(bytes));
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show(e.getMessage() == null || e.getMessage().isEmpty()
                        ? getString(R.string.music_import_failed_retry)
                        : "导入失败：" + e.getMessage()));
            }
        });
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("读不到这个文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }


    // ================= C4/C5/C10 插件管理专区 =================

    /** C4：行内钮启动检测（唯一检测入口）；回调回主线程后刷新行态与专区，完成后出分检测面板 */
    private void startPluginTest(MusicSite site) {
        startPluginTest(site, true);
    }

    private void startPluginTest(MusicSite site, boolean showDetail) {
        MusicSourceManager.get().testPlugin(site, new MusicSourceManager.PluginTestCallback() {
            @Override
            public void onTesting() {
                runOnUiThread(() -> {
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onResult(MusicPluginTest result) {
                runOnUiThread(() -> {
                    if (adapter != null) adapter.notifyDataSetChanged();
                    refreshPluginHeader(MusicSourceManager.get().listSites());
                    refreshPluginSections();
                    if (showDetail) showTestDetail(site, result);
                });
            }
        });
    }

    /** 批 6 分段检测面板：搜索／取播放／取歌词三段逐段显状态（通过／失败＋原因短文）。
     * 数据取 MusicPluginTest 三段字段；批 6 前的旧结果无段字段，按旧口径兜底
     * （AVAILABLE 必经搜索成功；失败原因取总 message）。 */
    private void showTestDetail(MusicSite site, MusicPluginTest result) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        boolean searchOk = result.searchOk || result.state == MusicPluginTest.State.AVAILABLE;
        String searchMsg = result.searchMessage != null && !result.searchMessage.isEmpty()
                ? result.searchMessage : result.message;
        addStageRow(box, "搜索", searchOk ? 0xFF34C759 : 0xFFFF3B30,
                searchOk ? "通过" : "失败" + stageReason(searchMsg), false);
        boolean lyricOnly = !result.audioOk && result.lyricOk && searchOk;
        addStageRow(box, "取播放", lyricOnly ? 0xFF8E8E93 : result.audioOk ? 0xFF34C759 : 0xFFFF3B30,
                lyricOnly ? "歌词型插件，无取播放段"
                        : result.audioOk ? "通过（取链耗时 " + result.latencyMs + "ms）"
                        : "失败" + stageReason(result.playMessage != null && !result.playMessage.isEmpty()
                        ? result.playMessage : result.message), true);
        String lyricText;
        int lyricColor;
        if (!result.lyricCapable) {
            lyricText = "该插件无歌词能力，只看音源与取播放";
            lyricColor = 0xFF8E8E93;
        } else if (result.lyricOk) {
            lyricText = "通过" + (result.preview == null || result.preview.isEmpty() ? "" : "：" + result.preview);
            lyricColor = 0xFF34C759;
        } else {
            lyricText = "失败" + stageReason(result.lyricMessage != null && !result.lyricMessage.isEmpty()
                    ? result.lyricMessage : result.message);
            lyricColor = 0xFFFF3B30;
        }
        TextView lyricTitle = new TextView(this);
        lyricTitle.setText("取歌词");
        lyricTitle.setTextSize(18);
        lyricTitle.setTextColor(0xFFFFFFFF);
        lyricTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.addView(lyricTitle, lp);
        TextView lyricLine = new TextView(this);
        lyricLine.setText(lyricText);
        lyricLine.setTextSize(14);
        lyricLine.setTextColor(lyricColor);
        box.addView(lyricLine);
        new MaterialAlertDialogBuilder(this)
                .setTitle(site.name == null ? "" : site.name)
                .setView(box)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static String stageReason(String msg) {
        return msg == null || msg.isEmpty() ? "" : "：" + msg;
    }

    private void addStageRow(LinearLayout box, String title, int color, String text, boolean marginTop) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        if (marginTop) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            box.addView(t, lp);
        } else {
            box.addView(t);
        }
        TextView line = new TextView(this);
        line.setText(text);
        line.setTextSize(14);
        line.setTextColor(color);
        box.addView(line);
    }

    /** 批 6：行内三段文案（搜索／取播放／取歌词一段一节，失败带原因短文；未检测返回空串＝不显行） */
    private static String pluginStageText(MusicPluginTest test) {
        if (test == null || test.state == MusicPluginTest.State.UNTESTED) return "";
        boolean searchOk = test.searchOk || test.state == MusicPluginTest.State.AVAILABLE;
        StringBuilder sb = new StringBuilder();
        sb.append("搜索 ").append(searchOk ? "通过" : "失败" + stageReason(
                test.searchMessage != null && !test.searchMessage.isEmpty() ? test.searchMessage : test.message));
        sb.append(" · 取播放 ");
        if (!test.audioOk && test.lyricOk && searchOk) sb.append("—");
        else if (test.audioOk) sb.append("通过 ").append(test.latencyMs).append("ms");
        else sb.append("失败").append(stageReason(
                test.playMessage != null && !test.playMessage.isEmpty() ? test.playMessage : test.message));
        sb.append(" · 取歌词 ");
        if (!test.lyricCapable) sb.append("无此能力");
        else if (test.lyricOk) sb.append("通过");
        else sb.append("失败").append(stageReason(
                test.lyricMessage != null && !test.lyricMessage.isEmpty() ? test.lyricMessage : test.message));
        return sb.toString();
    }

    /** C5 开关行＋C4 自动屏蔽行＋C10 运行时状态区（仅插件管理页 sites 模式） */
    private void refreshPluginSections() {
        if (binding.pluginSections == null) return;
        if (!mode.equals("sites")) {
            binding.pluginSections.setVisibility(View.GONE);
            return;
        }
        binding.pluginSections.setVisibility(View.VISIBLE);
        binding.pluginSections.removeAllViews();
        // ---- 第二波：播放路由卡（瓜子 LxPluginScreen：区头「播放路由／高级设置」18sp＋取链策略 15sp／副文 12sp＋指定平台插件 15sp／副文 12sp；
        // 取链策略两档照瓜子 LxPluginPriority：fallback 自动切换／pluginOnly 仅插件；指定平台插件行点进平台-插件指派表。
        // 注意：策略与指派只做存取＋UI，取链解析侧消费不在本批文件内（MusicRepository 禁动），见交卷待办。 ----
        LinearLayout routeCard = sectionCard();
        LinearLayout routeHead = new LinearLayout(this);
        routeHead.setOrientation(LinearLayout.HORIZONTAL);
        routeHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView routeTitle = new TextView(this);
        routeTitle.setText(R.string.wave2_route_title);
        routeTitle.setTextSize(18);
        routeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        routeTitle.setLetterSpacing(-0.017f);
        routeTitle.setTextColor(0xFF1C1C1E);
        routeHead.addView(routeTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView routeSub = new TextView(this);
        routeSub.setText(R.string.wave2_route_sub);
        routeSub.setTextSize(10);
        routeSub.setTextColor(0xFF8E8E93);
        routeHead.addView(routeSub);
        routeCard.addView(routeHead);
        boolean audioExists = false;
        for (MusicSite s : MusicSourceManager.get().listSites()) {
            if (s != null && !MusicSourceManager.isBuiltinLyricPreset(s.srcUrl) && !isLyricPluginSite(s)) {
                audioExists = true;
                break;
            }
        }
        final boolean fAudioExists = audioExists;
        String policy = MusicStore.getLinkPolicy();
        String policySub = MusicStore.LINK_POLICY_PLUGIN_ONLY.equals(policy)
                ? (fAudioExists ? getString(R.string.wave2_route_policy_only_desc) : getString(R.string.wave2_route_policy_no_plugin_only))
                : (fAudioExists ? getString(R.string.wave2_route_policy_auto_desc) : getString(R.string.wave2_route_policy_no_plugin));
        LinearLayout policyRow = sectionTextRow(getString(R.string.wave2_route_policy), policySub, v -> {
            List<String> opts = new ArrayList<>();
            opts.add(getString(R.string.wave2_route_policy_auto));
            opts.add(getString(R.string.wave2_route_policy_only));
            int checked = MusicStore.LINK_POLICY_PLUGIN_ONLY.equals(MusicStore.getLinkPolicy()) ? 1 : 0;
            MusicOptionsDialog.show(this, getString(R.string.wave2_route_policy), opts, checked, idx -> {
                MusicStore.setLinkPolicy(idx == 1 ? MusicStore.LINK_POLICY_PLUGIN_ONLY : MusicStore.LINK_POLICY_FALLBACK);
                refreshPluginSections();
            });
        });
        routeCard.addView(policyRow);
        View routeDivider = new View(this);
        routeDivider.setBackgroundColor(0xFFE5E5EA);
        LinearLayout.LayoutParams rdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        rdlp.topMargin = dp(10);
        rdlp.bottomMargin = dp(10);
        routeCard.addView(routeDivider, rdlp);
        int assignedCount = MusicStore.getAssignedPlatformCount();
        String assignSub = assignedCount > 0
                ? getString(R.string.wave2_route_assign_some, assignedCount)
                : getString(R.string.wave2_route_assign_none);
        LinearLayout assignRow = sectionTextRow(getString(R.string.wave2_route_assign), assignSub,
                v -> showPlatformAssignDialog());
        routeCard.addView(assignRow);
        binding.pluginSections.addView(routeCard);
        // ---- C5 多插件智能切换行（标题 15sp；说明句按忌介绍文字铁律去掉，只留名字＋开关，功能不动。
        // GZ 本体该行有 12sp 副文，用户令高于瓜子值，此处不照抄） ----
        // 两行开关并进一张卡（原先每行一张卡，底下卡堆是实拍“一堆卡”的来源之一；功能与缩放区分不动）
        boolean smartOn = MusicStore.isSmartSwitch();
        LinearLayout switchCard = sectionCard();
        LinearLayout smartRow = sectionSwitchRow("多插件智能切换", smartOn, 0.75f,
                (sw, on) -> {
                    if (!on) {
                        MusicStore.setSmartSwitch(false);
                        refreshPluginSections();
                        return;
                    }
                    if (MusicSourceManager.get().tryEnableSmartSwitch()) {
                        refreshPluginSections();
                    } else {
                        sw.setOnCheckedChangeListener(null);
                        sw.setChecked(false);
                        sw.setOnCheckedChangeListener((v, c) -> {
                        });
                        int n = MusicSourceManager.get().testedPluginCount();
                        new MaterialAlertDialogBuilder(MusicListActivity.this)
                                .setTitle("暂时无法开启")
                                .setMessage("多插件智能切换需要至少 2 个音源插件完成过「检测」（且至少一个平台可用）。当前已检测 "
                                        + n + " 个，请先在插件上执行「检测」后再开启。")
                                .setPositiveButton("知道了", null)
                                .show();
                        refreshPluginSections();
                    }
                });
        switchCard.addView(smartRow);
        View switchDivider = new View(this);
        switchDivider.setBackgroundColor(0xFFE5E5EA);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(10);
        dlp.bottomMargin = dp(10);
        switchCard.addView(switchDivider, dlp);
        // ---- C4 自动屏蔽行（另一类行，开关缩放 0.72，与插件行 0.75 区分；说明句同上按铁律去掉。
        // 注意 GZ 没有页级全局自动屏蔽行，只有单插件屏蔽面板内开关——星辰这行是自有功能，只动呈现不删） ----
        boolean blockOn = MusicStore.isAutoBlockInvalid();
        LinearLayout blockRow = sectionSwitchRow("自动屏蔽无效平台", blockOn, 0.72f,
                (sw, on) -> {
                    MusicStore.setAutoBlockInvalid(on);
                    if (on) {
                        for (MusicSite site : MusicSourceManager.get().enabledSites()) {
                            startPluginTest(site, false);
                        }
                    }
                    refreshPluginSections();
                });
        switchCard.addView(blockRow);
        binding.pluginSections.addView(switchCard);
        // ---- C10 运行时状态区（异常项＋重新加载钮；总数／启用数与头卡重复，已删） ----
        MusicRuntimeStatus st = MusicSourceManager.get().runtimeStatus();
        LinearLayout card = sectionCard();
        TextView title = new TextView(this);
        // GZ 面板标题“插件运行状态”18sp；星辰是页内卡不是独立面板，标题照 GZ 取名，说明段按铁律全删、只留名字＋状态行
        title.setText("插件运行状态");
        title.setTextSize(18);
        title.setTextColor(0xFF1C1C1E);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);
        // 第三波 B③：C10 卡补行——副文（已加载／已启用）＋当前单选状态（徽章同口径五选一取一）＋进行中态
        card.addView(sectionLine("已加载 " + st.liveRunners + " 个 · 已启用 "
                + st.enabledLocalPlugins + " 个"));
        {
            boolean hasUntested = false;
            for (MusicSite site : MusicSourceManager.get().listSites()) {
                if (site.enabled && MusicSourceManager.get().loadPluginTest(site.srcUrl) == null) {
                    hasUntested = true;
                    break;
                }
            }
            String state;
            if (st.quarantinedRunners > 0) state = "运行异常";
            else if (st.failedPlugins > 0) state = "启动失败";
            else if (st.initializing) state = "启动中";
            else if (st.enabledLocalPlugins == 0) state = "无本地插件";
            else if (hasUntested) state = "未检测";
            else state = "运行正常";
            card.addView(sectionLine("当前状态：" + state));
        }
        card.addView(sectionLine(st.initializing ? "正在启动…"
                : st.reloading ? "正在重新加载…" : "进行中：无"));
        // P24：C10 卡只留头卡没有的异常项——已加载总数／已启用数与头卡重复，删掉，不再两处对不上
        card.addView(sectionLine("暂时停用 " + st.quarantinedRunners + " 个"
                + (st.quarantinedNames.isEmpty() ? "" : "：" + android.text.TextUtils.join("、", st.quarantinedNames))));
        card.addView(sectionLine("启动失败 " + st.failedPlugins + " 个"
                + (st.failedNames.isEmpty() ? "" : "：" + android.text.TextUtils.join("、", st.failedNames))));
        TextView reloadBtn = new TextView(this);
        reloadBtn.setText("重新加载插件");
        reloadBtn.setTextSize(14);
        reloadBtn.setTextColor(0xFFFA243C);
        reloadBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        reloadBtn.setOnClickListener(v -> {
            MusicSourceManager.get().reloadBridges();
            refreshPluginSections();
            if (adapter != null) adapter.notifyDataSetChanged();
            Notify.show("已重新加载");
        });
        card.addView(reloadBtn);
        binding.pluginSections.addView(card);
    }

    private LinearLayout sectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_music_glass_24);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionLine(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF1C1C1E);
        return tv;
    }

    private interface SectionSwitchListener {
        void onChanged(com.google.android.material.switchmaterial.SwitchMaterial sw, boolean on);
    }

    private LinearLayout sectionSwitchRow(String titleText, boolean checked,
                                          float scale, SectionSwitchListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(15);
        title.setTextColor(0xFF1C1C1E);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, tlp);
        com.google.android.material.switchmaterial.SwitchMaterial sw =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        sw.setScaleX(scale);
        sw.setScaleY(scale);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((v, on) -> listener.onChanged(sw, on));
        row.addView(sw);
        return row;
    }

    /** 第二波：路由区文字行（标题 15sp 半粗＋副文 12sp 灰；瓜子取链策略／指定平台插件行口径） */
    private LinearLayout sectionTextRow(String titleText, String subText, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(15);
        title.setTextColor(0xFF1C1C1E);
        setSemiBold(title);
        row.addView(title);
        TextView sub = new TextView(this);
        sub.setText(subText);
        sub.setTextSize(12);
        sub.setTextColor(0xFF8E8E93);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        row.addView(sub, slp);
        row.setOnClickListener(listener);
        return row;
    }

    /** 半粗近似：瓜子 sq2.p（半粗）确切字重未解码，API 28+ 用 600，低版本退伪粗体 */
    private static void setSemiBold(TextView tv) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false));
        } else {
            tv.getPaint().setFakeBoldText(true);
        }
    }

    /** 第二波：指定平台插件指派表（瓜子 AssignOptionRow 口径：默认（自动选择）＋各启用插件；
     * 平台全集＝全部站点 platform 去重＋已指定残留平台；已停用插件不进选项，当前指派已不可用时顶部贴回落提示） */
    private void showPlatformAssignDialog() {
        List<String> plats = new ArrayList<>();
        for (MusicSite s : MusicRepository.sites()) {
            if (s != null && s.platform != null && !s.platform.isEmpty() && !plats.contains(s.platform)) plats.add(s.platform);
        }
        for (String p : MusicStore.getAssignedPlatforms()) {
            if (!plats.contains(p)) plats.add(p);
        }
        if (plats.isEmpty()) {
            Notify.show("暂无平台");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (String p : plats) labels.add(LxPlatformSearch.platformName(p));
        MusicOptionsDialog.show(this, getString(R.string.wave2_route_assign), labels, -1, idx -> {
            if (idx < 0 || idx >= plats.size()) return;
            showSinglePlatformAssign(plats.get(idx));
        });
    }

    private void showSinglePlatformAssign(String platform) {
        List<MusicSite> enabled = new ArrayList<>();
        for (MusicSite s : MusicSourceManager.get().listSites()) {
            if (s != null && s.enabled) enabled.add(s);
        }
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.wave2_route_assign_default));
        for (MusicSite s : enabled) opts.add(s.name == null ? "" : s.name);
        String cur = MusicStore.getPlatformPlugin(platform);
        int checked = 0;
        boolean curMissing = false;
        if (cur != null) {
            checked = -1;
            for (int i = 0; i < enabled.size(); i++) {
                if (cur.equals(enabled.get(i).srcUrl)) {
                    checked = i + 1;
                    break;
                }
            }
            if (checked < 0) curMissing = true;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(LxPlatformSearch.platformName(platform) + " · 取链插件")
                .setSingleChoiceItems(opts.toArray(new String[0]), checked, (d, which) -> {
                    if (which == 0) MusicStore.setPlatformPlugin(platform, null);
                    else if (which - 1 < enabled.size()) MusicStore.setPlatformPlugin(platform, enabled.get(which - 1).srcUrl);
                    d.dismiss();
                    refreshPluginSections();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (curMissing) builder.setMessage(R.string.wave2_route_assign_fallback_note);
        builder.show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** GZ 按压缩放（与音乐首页同款：按下缩、抬起／取消回弹；返回 false 不吞点击）。下载页重做补齐：进行中任务行两圆钮此前无按压反馈。 */
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

    /** 插件行 ⋮ 菜单：在线装的才有「更新」；手动排序模式下加「上移」「下移」；删除连插件文件一起删 */
    private void pluginMenu(MusicSite site) {
        boolean online = !MusicSourceManager.isLocal(site);
        List<String> opts = new ArrayList<>();
        int updateIdx = -1;
        int upIdx = -1;
        int downIdx = -1;
        if (online) {
            updateIdx = opts.size();
            opts.add(getString(R.string.music_plugin_update));
        }
        // P14：仅手动排序模式给上移／下移（首尾不给对应方向）
        if (MusicStore.PLUGIN_SORT_MANUAL.equals(MusicStore.getPluginSort())) {
            List<MusicSite> all = MusicSourceManager.get().listSites();
            int pos = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i) != null && site.srcUrl != null && site.srcUrl.equals(all.get(i).srcUrl)) {
                    pos = i;
                    break;
                }
            }
            if (pos > 0) {
                upIdx = opts.size();
                opts.add(getString(R.string.music_plugin_move_up));
            }
            if (pos >= 0 && pos < all.size() - 1) {
                downIdx = opts.size();
                opts.add(getString(R.string.music_plugin_move_down));
            }
        }
        int blockIdx = opts.size();
        opts.add("屏蔽平台（" + MusicStore.getBlockedPlatforms(site.srcUrl).size() + "）");
        int deleteIdx = opts.size();
        opts.add(getString(R.string.music_delete));
        final int fUpdateIdx = updateIdx;
        final int fUpIdx = upIdx;
        final int fDownIdx = downIdx;
        final int fBlockIdx = blockIdx;
        final int fDeleteIdx = deleteIdx;
        MusicOptionsDialog.show(this, site.name == null ? "" : site.name, opts, -1, idx -> {
            if (idx == fUpdateIdx) {
                Task.execute(() -> {
                    String v = MusicSourceManager.get().checkUpdate(site);
                    runOnUiThread(() -> {
                        if (v == null) {
                            Notify.show(R.string.music_plugin_latest);
                            return;
                        }
                        // 瓜子更新弹窗：标题「音源插件发现新版本」＋「立即更新」／「忽略」＋P20 副文
                        new MaterialAlertDialogBuilder(MusicListActivity.this)
                                .setTitle("音源插件发现新版本")
                                .setMessage((site.name == null ? "" : site.name) + "：" + v
                                        + "\n\n" + getString(R.string.music_plugin_update_desc))
                                .setPositiveButton("立即更新", (d, w) -> {
                                    MusicSourceManager.get().applyUpdate(site, v);
                                    MusicActivity.clearHomeChartCache();
                                    Notify.show(R.string.music_plugin_updated);
                                    loadSites();
                                })
                                .setNegativeButton("忽略", (d, w) -> {
                                    MusicSourceManager.get().ignoreUpdate(site.srcUrl, v);
                                    Notify.show("已忽略这个版本");
                                })
                                .show();
                    });
                });
            } else if (idx == fUpIdx) {
                movePlugin(site, -1);
            } else if (idx == fDownIdx) {
                movePlugin(site, 1);
            } else if (idx == fBlockIdx) {
                showPlatformBlockSheet(site);
            } else if (idx == fDeleteIdx) {
                confirmDeletePlugin(site);
            }
        });
    }

    /** 批 6：删除插件确认（瓜子移除弹窗，仅本机文案；云端备份选项属排除项，不出）。
     * ⋮ 菜单删除与行内删除钮共用这一份，行为一字不变（removeSite 连本地文件一起删）。 */
    private void confirmDeletePlugin(MusicSite site) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("移除「" + (site.name == null ? "" : site.name) + "」")
                .setMessage("该插件只存在于本机，移除后需要重新导入脚本才能恢复。")
                .setPositiveButton("移除", (d, w) -> Task.execute(() -> {
                    MusicSourceManager.get().removeSite(site.srcUrl);
                    runOnUiThread(() -> {
                        MusicActivity.clearHomeChartCache();
                        Notify.show(R.string.music_deleted);
                        loadSites();
                    });
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 第 11 项②：单插件屏蔽平台弹层（GZ PlatformBlockSheet：标题 18sp、说明 12sp、平台行 15sp＋行尾已屏蔽态、
     * 自动屏蔽开关行 14sp；单插件屏蔽＝改该插件自身的 blockedPlatforms，不动其他插件；
     * 平台全集＝内容平台五家（kw/tx/wy/kg/mg）＋该插件已屏蔽的存量项（仍可见可恢复）；
     * 行点按即切换并立即写 prefs，无单独保存钮；自动屏蔽开关复用全局偏好，与页级开关同一实现） */
    private void showPlatformBlockSheet(MusicSite site) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(16), pad, dp(8));
        TextView title = new TextView(this);
        title.setText("屏蔽平台");
        title.setTextSize(18);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(0xFF1C1C1E);
        root.addView(title);
        TextView desc = new TextView(this);
        desc.setText("对『" + (site.name == null ? "" : site.name) + "』屏蔽的平台，将不再用于该插件的取链／换源／内容与歌词");
        desc.setTextSize(12);
        desc.setTextColor(0xFF9AA7B8);
        desc.setPadding(0, dp(8), 0, dp(12));
        root.addView(desc);
        // 平台全集＝内容平台五家（kw/tx/wy/kg/mg，与落雪/聚合插件的内容平台同层）＋
        // 该插件已屏蔽但不在五家里的存量项（仍可见可取消）。旧口径摆的是源层 platform
        // （freejs/lx/py…）：与消费端的内容平台判据对不上，屏蔽了也拦不住、拦住的又不是想屏的
        List<String> plats = new ArrayList<>();
        for (String p : new String[]{LxPlatformSearch.KW, LxPlatformSearch.QQ, LxPlatformSearch.WY, LxPlatformSearch.KG, LxPlatformSearch.MG}) {
            if (!plats.contains(p)) plats.add(p);
        }
        for (String p : MusicStore.getBlockedPlatforms(site.srcUrl)) {
            if (!plats.contains(p)) plats.add(p);
        }
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (String p : plats) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            TextView name = new TextView(this);
            name.setText(LxPlatformSearch.platformName(p));
            name.setTextSize(15);
            name.setTextColor(0xFF1C1C1E);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView state = new TextView(this);
            state.setTextSize(14);
            state.setTextColor(0xFFFA243C);
            row.addView(state);
            Runnable refresh = () -> state.setText(
                    MusicStore.getBlockedPlatforms(site.srcUrl).contains(p) ? "已屏蔽" : "");
            refresh.run();
            row.setOnClickListener(v -> {
                java.util.Set<String> cur = MusicStore.getBlockedPlatforms(site.srcUrl);
                if (cur.contains(p)) cur.remove(p);
                else cur.add(p);
                MusicStore.setBlockedPlatforms(site.srcUrl, cur);
                refresh.run();
            });
            rows.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        root.addView(scroll);
        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        autoRow.setPadding(0, dp(12), 0, dp(4));
        TextView autoName = new TextView(this);
        autoName.setText("自动屏蔽无效平台");
        autoName.setTextSize(14);
        autoName.setTextColor(0xFF1C1C1E);
        autoRow.addView(autoName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        com.google.android.material.switchmaterial.SwitchMaterial sw =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        sw.setChecked(MusicStore.isAutoBlockInvalid());
        sw.setOnCheckedChangeListener((v, on) -> {
            MusicStore.setAutoBlockInvalid(on);
            if (on) {
                for (MusicSite s : MusicSourceManager.get().enabledSites()) {
                    startPluginTest(s, false);
                }
            }
        });
        autoRow.addView(sw);
        root.addView(autoRow);
        new MaterialAlertDialogBuilder(this).setView(root)
                .setPositiveButton("完成", null)
                .show();
    }

    /**
     * 插件导入确认（瓜子「第三方插件导入确认」：标题＋导语＋前两条照瓜子；
     * P26：第 3 条改写为星辰版——原「先试运行一次」句按对照表落码口径替换，
     * probe 先行再落盘仍是安装链行为，只是对话框文案不再单列）。
     */
    /** 第三波 B①：导入进度弹窗。阶段／完成态／失败重试都在此；
     * 弹窗可关（离开不中断）：后台安装任务与弹窗无关，完成后若弹窗还在才刷新它，否则只发提示＋刷新列表。
     * 本地导入现状：单文件选择（ACTION_GET_CONTENT 未开多选），一次只装一个。 */
    private interface PluginInstaller {
        void install() throws Exception;
    }

    private void runPluginImportWithProgress(String stageLabel, PluginInstaller installer) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        TextView stage = new TextView(this);
        stage.setText(stageLabel);
        stage.setTextSize(14);
        stage.setTextColor(0xFF1C1C1E);
        box.addView(stage);
        TextView retry = new TextView(this);
        retry.setText("重试");
        retry.setTextSize(14);
        retry.setTextColor(0xFFFA243C);
        retry.setPadding(0, dp(12), 0, 0);
        retry.setVisibility(View.GONE);
        box.addView(retry);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("导入插件")
                .setView(box)
                .setPositiveButton("关闭", null)
                .setCancelable(true)
                .create();
        retry.setOnClickListener(v -> {
            retry.setVisibility(View.GONE);
            stage.setText(stageLabel);
            runImportTask(dialog, stage, retry, installer);
        });
        dialog.show();
        runImportTask(dialog, stage, retry, installer);
    }

    private void runImportTask(AlertDialog dialog, TextView stage, TextView retry, PluginInstaller installer) {
        Task.execute(() -> {
            try {
                installer.install();
                runOnUiThread(() -> {
                    if (dialog.isShowing()) stage.setText("已完成");
                    MusicActivity.clearHomeChartCache();
                    Notify.show(R.string.music_site_added);
                    loadSites();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pluginImporting = false;
                    refreshPluginHeader(MusicSourceManager.get().listSites());
                    String msg = e.getMessage() == null || e.getMessage().isEmpty()
                            ? getString(R.string.music_import_failed_retry)
                            : "导入失败：" + e.getMessage();
                    if (dialog.isShowing()) {
                        stage.setText(msg);
                        retry.setVisibility(View.VISIBLE);
                    } else {
                        Notify.show(msg);
                    }
                });
            }
        });
    }

    private void confirmPluginImport(Runnable onAgree) {
        String msg = "请仔细阅读以下提示，点击「同意并导入」即代表你已同意\n\n"
                + "1. 该脚本来自第三方，非星辰提供。请自行判断其用途与潜在风险（内容、播放链接等），相关责任由脚本作者和使用者自行承担；\n"
                + "2. 脚本会在本应用内执行代码，并可发起网络请求，请仅导入你信任的来源；\n"
                + "3. 星辰不发布、不内置、不推荐任何第三方插件，在线播放与歌词由用户自行导入的脚本提供。\n"
                + "4. 安装前会先试运行一次，确认能正常提供音源才会保留。";
        new MaterialAlertDialogBuilder(this)
                .setTitle("第三方插件导入确认")
                .setMessage(msg)
                .setPositiveButton("同意并导入", (d, w) -> onAgree.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void inputSiteUrl() {
        // P29：标题改为「从链接导入」＋说明行「粘贴脚本地址，支持 raw / latest.js」；空值直接返回保留
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView desc = new TextView(this);
        desc.setText(R.string.music_plugin_import_url_desc);
        desc.setTextSize(13);
        desc.setTextColor(0xFF8E8E93);
        box.addView(desc);
        EditText et = new EditText(this);
        et.setHint(R.string.music_site_url_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = pad / 2;
        box.addView(et, etLp);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_plugin_import_url)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    confirmPluginImport(() -> {
                        pluginImporting = true;
                        refreshPluginHeader(MusicSourceManager.get().listSites());
                        runPluginImportWithProgress("正在下载并安装…",
                                () -> MusicSourceManager.get().addSite(url));
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showLoading() {
        binding.empty.setVisibility(View.VISIBLE);
        binding.empty.setText("正在加载…");
    }

    private void showTracks(List<MusicTrack> list) {
        sortTracks(list);
        tracks.clear();
        tracks.addAll(list);
        cards.clear();
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            binding.tvBigTitle.setText(bigTitle);
            binding.tvBigCount.setText(tracks.size() + " " + getString(R.string.music_tracks));
            refreshSortLabel();
            boolean showEmpty = tracks.isEmpty();
            binding.empty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
            if (tracks.isEmpty()) binding.empty.setText(R.string.music_no_track);
        });
    }

    private void sortTracks(List<MusicTrack> list) {
        int sort = MusicStore.getListSort();
        if (sort == 1) Collections.sort(list, Comparator.comparing(t -> t.title == null ? "" : t.title));
        else if (sort == 2) Collections.sort(list, Comparator.comparing(t -> t.artist == null ? "" : t.artist));
        else if (sort == 3) Collections.sort(list, Comparator.comparingLong(t -> t.duration));
        if (MusicStore.isListSortDesc()) Collections.reverse(list);
    }

    /** 复核必-10：第二段升降序弹层的 post 用字段 Handler＋字段 Runnable，onDestroy 取消，
     * 不许匿名 Handler＋匿名 Runnable 丢引用、页死后还弹。 */
    private final android.os.Handler sortDialogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sortDirRunnable;

    /** 第三波 R4：通用排序改对话框（默认／标题／歌手／时长＋升降），不再盲切 cycleSort；
     * 当前档常显在工具条 tvSortCurrent 与 btnSort 无障碍文案上（见 refreshSortLabel）。
     * 收藏／历史已拆独立页，不进这里。 */
    private void showGenericSortDialog() {
        String[] keys = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        MusicOptionsDialog.show(this, "排序", java.util.Arrays.asList(keys), MusicStore.getListSort(), idx -> {
            MusicStore.setListSort(idx);
            // 复核必-10：第一段落点即刷新档位文案并按新档重排，不等第二段升降序选完才动；
            // 第二段弹窗延后一帧弹（第一段 dismiss 在回调返回后才走，同帧同 tag 再 show 会撞 FragmentManager）。
            refreshSortLabel();
            if (isCardMode()) load();
            else showTracks(new ArrayList<>(tracks));
            sortDirRunnable = () -> {
                if (isFinishing() || isDestroyed()) return;
                MusicOptionsDialog.show(this, "升序 / 降序",
                        java.util.Arrays.asList("升序", "降序"), MusicStore.isListSortDesc() ? 1 : 0, dir -> {
                            MusicStore.setListSortDesc(dir == 1);
                            refreshSortLabel();
                            if (isCardMode()) load();
                            else showTracks(new ArrayList<>(tracks));
                        });
            };
            sortDialogHandler.post(sortDirRunnable);
        });
    }

    /** 第三波 R4：工具条旁常显当前通用排序档。 */
    private void refreshSortLabel() {
        if (binding == null || binding.tvSortCurrent == null) return;
        String[] keys = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        int s = MusicStore.getListSort();
        String label = keys[s < 0 || s >= keys.length ? 0 : s] + (MusicStore.isListSortDesc() ? " ↓" : " ↑");
        binding.tvSortCurrent.setText(label);
        binding.tvSortCurrent.setVisibility(View.VISIBLE);
        binding.btnSort.setContentDescription("排序：" + label);
    }

    /** 第三波 R3：通用“播放全部”——顺序入队从头播（与“随机播放”分开）。歌曲行在场时走 tracks，无行时不播。 */
    private void playAllSequential() {
        if (!tracks.isEmpty()) {
            playTrack(0);
            return;
        }
        Notify.show(R.string.music_no_track);
    }

    private void shufflePlay() {
        if (tracks.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        // B7：入队时写队列级来源＝本页标题、route＝list（参数可重建本页，落点＝本列表页）
        MusicController.get().play(shuffled, 0, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    private void playTrack(int position) {
        // B7：入队时写队列级来源＝本页标题、route＝list（参数可重建本页，落点＝本列表页）
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    /** B7：list 路由参数——与本页 onCreate 读的 intent extras 同名同值，可重建本页 */
    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", mode == null ? "" : mode);
        params.put("keyword", keyword == null ? "" : keyword);
        params.put("sitePlatform", sitePlatform == null ? "" : sitePlatform);
        params.put("playlistId", String.valueOf(playlistId));
        params.put("title", bigTitle == null ? "" : bigTitle);
        return params;
    }

    /** 上次在播曲目 id（定点刷行用：切歌只刷旧行＋新行，不全量重绑）。 */
    private String lastCurrentId = null;

    /** X-08（批 5 F2）：重建时待回滚的列表滚动状态（布局管理器 Parcelable），数据到位后一次性应用即清。 */
    private android.os.Parcelable pendingListState;

    /**
     * 搜索页重做（字乱动根因之一）：此前切歌全量 notifyDataSetChanged，所有可见行重绑、
     * 封面重载，整列表闪一下。改只刷旧在播行＋新在播行；两首都不在本页时无行可刷，
     * 直接跳过（旧高亮只存在于本页行内，不在本页就无残留可清）。
     */
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

    /**
     * tracks 下标 → adapter 位置（trackIndex 的逆映射，见 ListAdapter.trackIndex）：
     * 其余模式＝下标。卡片视图下没有歌曲行，返回 -1 不刷行。
     */
    private int adapterPositionForTrack(int trackIdx) {
        if (adapter == null || trackIdx < 0 || trackIdx >= tracks.size()) return -1;
        return trackIdx;
    }

    private int indexOfTrackId(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            MusicTrack t = tracks.get(i);
            if (t != null && id.equals(t.id)) return i;
        }
        return -1;
    }

    // ================= ⋮ 菜单 =================

    /** 第 1 项：下载（从通用 ⋮ 菜单抽出来的同一段逻辑：每次询问档时先选档，否则直接下载；原在 ListAdapter 内，onMore 菜单调不到，挪到外层） */
    private void downloadTrack(MusicTrack track) {
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                    MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
            int fixed = MusicStore.getDownloadQualityFixed();
            int checked = 1;
            for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
            final int checkedFinal = checked;
            // 第三波 S-A2：选档前实测文件大小（取链＋HEAD；实测不到的档不显示大小、不估算）。
            // 复核堵-1：探查期间先给现成 Notify 提示，探查回来了页已死则不弹选档窗。
            Notify.show("正在查询大小…");
            MusicDownloadStore.probeQualitySizes(track, values, sizes -> {
                if (isFinishing() || isDestroyed()) return;
                List<String> qopts = new ArrayList<>();
                for (int i = 0; i < values.length; i++) {
                    String name = MusicStore.qualityName(values[i]);
                    qopts.add(sizes[i] > 0 ? name + " · " + com.fongmi.android.tv.music.MusicCache.formatSize(sizes[i]) : name);
                }
                MusicOptionsDialog.show(this, getString(R.string.music_set_download_quality), qopts, checkedFinal,
                        qidx -> MusicDownloadStore.downloadWithQuality(track, values[qidx]));
            });
        } else {
            MusicDownloadStore.download(track);
        }
    }

    private void onMore(MusicTrack track, int position) {
        FragmentActivity act = this;
        boolean local = MusicDownloadStore.isLocal(track);
        boolean device = com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform);
        List<String> opts = new ArrayList<>();
        opts.add("播放");
        opts.add(getString(R.string.music_play_next));
        opts.add(getString(R.string.music_add_to_playlist));
        MusicLibrary.isFavorite(track, fav -> {
            opts.add(fav ? getString(R.string.music_unfavorite) : getString(R.string.music_favorite));
            int downloadIdx = -1;
            int deleteLocalIdx = -1;
            if (local) {
                // 已下载歌曲：不给"下载"（已是本地），给"删除本地文件"
                deleteLocalIdx = opts.size();
                opts.add(getString(R.string.music_download_delete));
            } else if (device) {
                // 设备歌曲：本来就在手机里，不给"下载"
            } else {
                downloadIdx = opts.size();
                opts.add(getString(R.string.music_download));
            }
            int dIdx = downloadIdx;
            int delIdx = deleteLocalIdx;
            MusicOptionsDialog.show(act, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) pickPlaylist(track);
                else if (idx == 3) toggleFavorite(track, opts.get(3));
                else if (idx == dIdx) downloadTrack(track);
                else if (idx == delIdx) confirmDeleteLocal(track);
            });
        });
    }

    private void confirmDeleteLocal(MusicTrack track) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(track.displayTitle())
                .setMessage(getString(R.string.music_download_delete_confirm))
                .setPositiveButton(getString(R.string.music_download_delete), (d, w) ->
                        MusicDownloadStore.deleteLocal(track, () -> {
                            Notify.show(R.string.music_deleted);
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleFavorite(MusicTrack track, String label) {
        boolean toFav = label.equals(getString(R.string.music_favorite));
        MusicLibrary.setFavorite(track, toFav, () ->
                Notify.show(toFav ? R.string.music_favorited : R.string.music_unfavorited));
    }

    private void pickPlaylist(MusicTrack track) {
        MusicLibrary.playlists(list -> {
            List<String> names = new ArrayList<>();
            for (MusicPlaylist p : list) names.add(p.getName());
            names.add(getString(R.string.music_new_playlist));
            MusicOptionsDialog.show(this, getString(R.string.music_add_to_playlist), names, -1, idx -> {
                if (idx < list.size()) {
                    MusicLibrary.addToPlaylist(list.get(idx).getId(), track, added ->
                            Notify.show(added ? R.string.music_added : R.string.music_already_in));
                } else {
                    inputPlaylistName(name -> MusicLibrary.createPlaylist(name, p -> {
                        MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                Notify.show(added ? R.string.music_added : R.string.music_already_in));
                    }));
                }
            });
        });
    }

    private void inputPlaylistName(NameInput cb) {
        // 2026-10-22 歌单页对照 GZ CreatePlaylistDialog 重做：旧 dialog 是下划线裸输入框＋黄字取消／确定，
        // 且输入字是白色、在浅底上打出来看不见；新版照 GZ 结构（红图标＋标题＋副标题＋圆角输入框＋大创建钮）。
        // 封面选择与开通赞助不做——星辰歌单无封面字段、不要账号与付费项，不硬做空壳。
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setPadding(0, dp(8), 0, dp(8));
        TextView icon = new TextView(this);
        icon.setText("≡+");
        icon.setTextSize(22);
        icon.setTextColor(0xFFFFFFFF);
        icon.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
        iconBg.setColor(0xFFFA243C);
        iconBg.setCornerRadius(dp(16));
        icon.setBackground(iconBg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        iconLp.bottomMargin = dp(12);
        root.addView(icon, iconLp);
        TextView title = new TextView(this);
        title.setText(R.string.music_new_playlist);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF1C1C1E);
        title.setGravity(android.view.Gravity.CENTER);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("给自建歌单起个名字");
        sub.setTextSize(12);
        sub.setTextColor(0xFF8E8E93);
        sub.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(16);
        root.addView(sub, subLp);
        EditText et = new EditText(this);
        et.setHint("请输入歌单名称");
        et.setTextColor(0xFF1C1C1E);
        et.setHintTextColor(0xFF8E8E93);
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setBackgroundResource(R.drawable.home_bg_search);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView create = new TextView(this);
        create.setText("创建");
        create.setTextSize(16);
        create.setTextColor(0xFFFFFFFF);
        create.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable createBg = new android.graphics.drawable.GradientDrawable();
        createBg.setCornerRadius(dp(24));
        create.setBackground(createBg);
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        createLp.topMargin = dp(16);
        root.addView(create, createLp);
        TextView cancel = new TextView(this);
        cancel.setText(android.R.string.cancel);
        cancel.setTextSize(14);
        cancel.setTextColor(0xFF8E8E93);
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setPadding(0, dp(14), 0, 0);
        root.addView(cancel);
        final android.app.Dialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setCancelable(true)
                .create();
        Runnable refreshCreate = () -> {
            boolean hasName = et.getText() != null && !et.getText().toString().trim().isEmpty();
            createBg.setColor(hasName ? 0xFFFA243C : 0xFFF5A8B0);
            create.setEnabled(hasName);
        };
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshCreate.run();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        refreshCreate.run();
        create.setOnClickListener(v -> {
            String name = et.getText() == null ? "" : et.getText().toString();
            dialog.dismiss();
            cb.onResult(name);
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // ================= 列表 =================

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_TRACK = 0;
        private static final int TYPE_CARD = 1;
        private static final int TYPE_PLUGIN = 2;
        private static final int TYPE_HEADER = 3;
        /** 第二波：插件分组头（sites 列表内插项） */
        private static final int TYPE_PLUGIN_GROUP = 20;

        /** 本页无列表内头行（旧本地页的导入行已随本地页拆出）。 */
        private int headerCount() {
            return 0;
        }

        @Override
        public int getItemViewType(int position) {
            if (isCardMode()) {
                // 第二波：插件分组头（sites 列表内插项，非插件行）
                if (mode.equals("sites") && cards.get(position) instanceof PluginGroup) return TYPE_PLUGIN_GROUP;
                // 插件管理页里插件行用专用布局（名＋版本＋格式标签＋开关＋⋮），添加卡仍走通用卡
                if (mode.equals("sites") && cards.get(position) instanceof MusicSite) return TYPE_PLUGIN;
                return TYPE_CARD;
            }
            return TYPE_TRACK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_PLUGIN_GROUP) {
                // 瓜子分组头（图 112927）：无卡底、蓝点＋组名＋右端计数；旧卡片＋箭头形制与瓜子不符，已改；
                // 分组头不可折叠（旧箭头仅装饰），改后无点击行为。
                LinearLayout row = new LinearLayout(parent.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                View dot = new View(parent.getContext());
                android.graphics.drawable.GradientDrawable dotBg =
                        new android.graphics.drawable.GradientDrawable();
                dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dotBg.setColor(0xFF42A5F5);
                dot.setBackground(dotBg);
                row.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(14);
                tv.setTextColor(0xFF1C1C1E);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.leftMargin = dp(8);
                row.addView(tv, tlp);
                row.addView(new View(parent.getContext()),
                        new LinearLayout.LayoutParams(0, 0, 1f));
                TextView count = new TextView(parent.getContext());
                count.setTextSize(13);
                count.setTextColor(0xFF8E8E93);
                row.addView(count, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(12);
                row.setLayoutParams(lp);
                return new GroupHolder(row, tv, count);
            }
            if (viewType == TYPE_PLUGIN) {
                return new PluginHolder(inf.inflate(R.layout.item_music_plugin, parent, false));
            }
            if (viewType == TYPE_CARD || viewType == TYPE_HEADER) {
                return new CardHolder(inf.inflate(R.layout.item_music_nav, parent, false));
            }
            return new TrackHolder(inf.inflate(R.layout.item_music_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (h instanceof GroupHolder) {
                // 第二波：插件分组头（cards 内插项，位置即 cards 下标）；右端计数＝其后连续插件卡数
                Object o = cards.get(position);
                ((GroupHolder) h).title.setText(o instanceof PluginGroup ? ((PluginGroup) o).title : "");
                int n = 0;
                for (int j = position + 1; j < cards.size(); j++) {
                    if (cards.get(j) instanceof MusicSite) n++;
                    else break;
                }
                ((GroupHolder) h).count.setText(String.valueOf(n));
            }
            else if (h instanceof PluginHolder) bindPlugin((PluginHolder) h, position);
            else if (h instanceof CardHolder) bindCard((CardHolder) h, position);
            else bindTrack((TrackHolder) h, trackIndex(position));
        }

        /** 歌曲行在列表里的下标：减头行数 */
        private int trackIndex(int position) {
            return position - headerCount();
        }

        /** 第三波 B②：插件是否官方预置（按播种地址认，用户改名／改地址后不算官方）。 */
        private boolean isOfficialSite(MusicSite s) {
            if (s == null || s.srcUrl == null) return false;
            for (MusicSite b : MusicSourceManager.builtinSites()) {
                if (s.srcUrl.equals(b.srcUrl)) return true;
            }
            return false;
        }

        /** 插件图标色：批 5 F2／D-09 起色盘收口到 MusicSourceManager.stableColorForKey/siteColor
         * （同源恒同色、全页共用一份），本页不再自备色盘；键与旧实现一致（源地址、改名不变），存量颜色不变。 */
        private int pluginColor(MusicSite s) {
            return MusicSourceManager.siteColor(s);
        }

        /** 插件行：名＋版本号小字＋格式标签；右侧金开关（真接线启停）＋⋮菜单 */
        private void bindPlugin(PluginHolder h, int position) {
            MusicSite s = (MusicSite) cards.get(position);
            h.name.setText(s.name == null ? "" : s.name);
            if (h.pluginIcon != null) {
                h.pluginIcon.setImageResource(R.drawable.ic_music_puzzle);
                int c = pluginColor(s);
                h.pluginIcon.setImageTintList(ColorStateList.valueOf(c));
                // 瓜子行图标＝彩色淡底方块＋同色拼图：底色＝行色 12% 透明度、圆角 12dp
                android.graphics.drawable.GradientDrawable iconBg =
                        new android.graphics.drawable.GradientDrawable();
                iconBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                iconBg.setCornerRadius(dp(12));
                iconBg.setColor((c & 0x00FFFFFF) | 0x1F000000);
                h.pluginIcon.setBackground(iconBg);
            }
            boolean lx = MusicSourceManager.isLx(s);
            h.formatTag.setText(lx ? "落雪" : "FreeJS");
            h.formatTag.setTextColor(lx ? 0xFFFA243C : 0xFF9AA7B8);
            // M14：副文案串单行“官方 · 版本 · 作者 · 耗时”（只有真实数据的段才拼；
            // 官方按播种地址认（isOfficialSite，不读字段）；加密／配置项无数据来源，字段已删、不出段；
            // 耗时是单次检测耗时，均耗时未存检测层，不冒充平均值）
            StringBuilder sub = new StringBuilder();
            if (isOfficialSite(s)) sub.append("官方");
            if (s.version != null && !s.version.isEmpty()) {
                String v = s.version.trim();
                while (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
                if (sub.length() > 0) sub.append(" · ");
                sub.append("v").append(v);
            }
            if (s.author != null && !s.author.isEmpty()) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(s.author);
            }
            MusicPluginTest latencyTest = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (latencyTest != null && latencyTest.latencyMs > 0) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append("耗时 ").append(latencyTest.latencyMs).append("ms");
            }
            if (sub.length() > 0) {
                h.version.setVisibility(View.VISIBLE);
                h.version.setText(sub.toString());
            } else {
                h.version.setVisibility(View.GONE);
            }
            h.pluginSwitch.setOnCheckedChangeListener(null);
            h.pluginSwitch.setChecked(s.enabled);
            h.pluginSwitch.setOnCheckedChangeListener((v, checked) -> {
                MusicActivity.clearHomeChartCache();
                Task.execute(() -> MusicSourceManager.get().setEnabled(s.srcUrl, checked));
            });
            // C4：四态显示＋检测中态（行内钮定为唯一检测入口，pluginMenu 不另加检测项）
            MusicPluginTest test = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (MusicSourceManager.get().isTesting(s.srcUrl)) {
                h.testStatus.setText("检测中…");
                h.testStatus.setTextColor(0xFF8E8E93);
                if (h.statusDot != null) setStatusDot(h.statusDot, 0xFF8E8E93);
                h.pluginTest.setText("检测中…");
                h.pluginTest.setEnabled(false);
            } else {
                if (test == null) test = new MusicPluginTest();
                h.testStatus.setText(test.label());
                h.testStatus.setTextColor(test.labelColor());
                if (h.statusDot != null) setStatusDot(h.statusDot, test.labelColor());
                h.pluginTest.setText("检测");
                h.pluginTest.setEnabled(true);
            }
            // P12：检测失败且 message 非空时行内另起一行显 message（11sp，色与状态色一致）
            MusicPluginTest rowTest = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (rowTest == null) rowTest = new MusicPluginTest();
            if (!MusicSourceManager.get().isTesting(s.srcUrl)
                    && rowTest.state == MusicPluginTest.State.FAILED
                    && rowTest.message != null && !rowTest.message.isEmpty()) {
                h.pluginError.setVisibility(View.VISIBLE);
                h.pluginError.setText(rowTest.message);
                h.pluginError.setTextColor(rowTest.labelColor());
            } else {
                h.pluginError.setVisibility(View.GONE);
            }
            // 批 6：三段行（已检测才显；检测中收起，避免旧段结果与「检测中…」打架）
            String stages = MusicSourceManager.get().isTesting(s.srcUrl) ? "" : pluginStageText(rowTest);
            if (stages.isEmpty()) {
                h.pluginStages.setVisibility(View.GONE);
            } else {
                h.pluginStages.setVisibility(View.VISIBLE);
                h.pluginStages.setText(stages);
            }
            h.pluginTest.setOnClickListener(v -> startPluginTest(s));
            if (h.pluginDelete != null) h.pluginDelete.setOnClickListener(v -> confirmDeletePlugin(s));
            View.OnClickListener menu = v -> pluginMenu(s);
            h.pluginMore.setOnClickListener(menu);
            h.itemView.setOnClickListener(menu);
        }

        /** 第三波 B②：状态圆点着色（mutate 后改色，不污染其它行的共用 drawable）。 */
        private void setStatusDot(View dot, int color) {
            try {
                android.graphics.drawable.Drawable d = dot.getBackground();
                if (d == null) return;
                d = d.mutate();
                if (d instanceof android.graphics.drawable.GradientDrawable) {
                    ((android.graphics.drawable.GradientDrawable) d).setColor(color);
                }
            } catch (Throwable ignored) {
            }
        }

        private void bindCard(CardHolder h, int position) {
            // 本地页回炉：回收行先复位（图标显、马赛克位藏），各分支只在需要时开马赛克，防旧图残留到别的卡。
            // 搜索分组／歌单卡已随搜索页拆出，本方法在剩余模式下无卡分支（sites 走 Plugin／Group 行），只余复位口径。
            h.icon.setVisibility(View.VISIBLE);
            h.mosaicSingle.setVisibility(View.GONE);
            h.mosaicGrid.setVisibility(View.GONE);
            h.coverBox.setBackgroundResource(0);
            h.coverBox.setClipToOutline(false);
            h.icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        }

        private GradientDrawable circle(int color) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            return d;
        }

        private void bindTrack(TrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            // 共用行口径（批 5 F1/F2）：歌名 15sp 粗 #1A1A1A，在播行红名；此前代码侧写 #1C1C1E 与布局口径不一致，一并对齐
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1A1A1A);
            h.itemView.setBackgroundResource(isCurrent ? R.drawable.card_music_current : 0);
            if (t.qualityTag != null && !t.qualityTag.isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(t.qualityTag);
                h.badge.setTextSize(11);
                int p6 = dp(6);
                int p2 = dp(2);
                h.badge.setPadding(p6, p2, p6, p2);
            } else {
                h.badge.setVisibility(View.GONE);
            }
                // 第三波 R1/R2：通用行第二行统一拼“歌手 · 专辑”（有专辑才拼，与本地／歌单行
                // 口径一致）；当前行统一加在播前缀（正在播放／已暂停，与收藏／历史行口径一致，
                // 选了“前缀”口径，未做封面在播动效）。
                StringBuilder second = new StringBuilder();
                if (isCurrent) second.append(MusicController.get().isPlaying() ? "正在播放 · " : "已暂停 · ");
                second.append(artistDisplay(t.displayArtist()));
                if (t.album != null && !t.album.trim().isEmpty()) second.append(" · ").append(t.album.trim());
                h.artist.setText(second.toString());
            // 本地行路径行（批 5 F2）：仅本地模式且路径真实存在时显所在目录，其余一律 GONE（回收行防串显）
            if (h.path != null) {
                String dir = null;
                if (dir != null && !dir.isEmpty()) {
                    h.path.setVisibility(View.VISIBLE);
                    h.path.setText(dir);
                } else {
                    h.path.setVisibility(View.GONE);
                    h.path.setText("");
                }
            }
            h.duration.setText(fmt(t.duration));
            if (t.cover == null || t.cover.isEmpty()) {
                // 没封面地址才上素图（素灰底＋歌名排字）；有地址的一律走加载、
                // 失败保持透明。列表是回收行，先清掉上一行可能在途的加载请求，
                // 防旧封面迟到盖掉素图
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t.displayTitle(), false, 8 * h.cover.getResources().getDisplayMetrics().density));
            } else {
                // 第二波：共用行封面圆角 8→12dp（瓜子搜索／收藏／历史／下载／本地行封面均为 12dp；
                // ImgUtil.loadRounded 写死 8dp，走本页 12dp helper；横内边距 4→14dp 另见 item_music_track.xml）
                loadCoverRounded(t.displayTitle(), t.cover, h.cover, 10);
            }
            h.itemView.setOnClickListener(v -> playTrack(position));
            // 回收行复位：多选圆圈换回 ⋮ 图标
            h.more.setImageResource(R.drawable.ic_music_more);
            int p12 = dp(12);
            h.more.setPadding(p12, p12, p12, p12);
            h.more.setOnClickListener(v -> onMore(t, position));
        }

        private String fmt(long ms) {
            if (ms <= 0) return "--:--";
            long s = ms / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }

    /** 第二波：指定圆角加载（ImgUtil.loadRounded 写死 8dp；榜单卡 14dp／共用行 12dp 在此另起；
     * 占位／失败素图口径与行内 CoverTextDrawable 一致，圆角值取调用方 dp 数） */
    private void loadCoverRounded(String text, String url, android.widget.ImageView view, int radiusDp) {
        view.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        float r = radiusDp * view.getResources().getDisplayMetrics().density;
        try {
            com.bumptech.glide.Glide.with(view).clear(view);
            com.bumptech.glide.Glide.with(view).load(ImgUtil.getUrl(url))
                    .placeholder(new com.fongmi.android.tv.utils.CoverTextDrawable(text, false, r))
                    .error(new com.fongmi.android.tv.utils.CoverTextDrawable(text, false, r))
                    .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                            new com.bumptech.glide.load.resource.bitmap.RoundedCorners(dp(radiusDp)))
                    .into(view);
        } catch (Throwable ignored) {
        }
    }

        public int getItemCount() {
            if (isCardMode()) return cards.size();
            return tracks.size() + headerCount();
        }

        /** 第二波：插件分组头 holder（程序化 TextView） */
        class GroupHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView count;

            GroupHolder(View itemView, TextView title, TextView count) {
                super(itemView);
                this.title = title;
                this.count = count;
            }
        }

        class TrackHolder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView badge;
            final TextView artist;
            final TextView path;
            final TextView duration;
            final ImageView more;

            TrackHolder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                badge = v.findViewById(R.id.badge);
                artist = v.findViewById(R.id.artist);
                path = v.findViewById(R.id.path);
                duration = v.findViewById(R.id.duration);
                more = v.findViewById(R.id.more);
            }
        }

        class CardHolder extends RecyclerView.ViewHolder {
            final View coverBox;
            final ImageView icon;
            final ImageView mosaicSingle;
            final View mosaicGrid;
            final ImageView mosaic1;
            final ImageView mosaic2;
            final ImageView mosaic3;
            final ImageView mosaic4;
            final TextView name;
            final TextView status;

            CardHolder(View v) {
                super(v);
                coverBox = v.findViewById(R.id.coverBox);
                icon = v.findViewById(R.id.icon);
                mosaicSingle = v.findViewById(R.id.mosaicSingle);
                mosaicGrid = v.findViewById(R.id.mosaicGrid);
                mosaic1 = v.findViewById(R.id.mosaic1);
                mosaic2 = v.findViewById(R.id.mosaic2);
                mosaic3 = v.findViewById(R.id.mosaic3);
                mosaic4 = v.findViewById(R.id.mosaic4);
                name = v.findViewById(R.id.name);
                status = v.findViewById(R.id.status);
            }
        }

        class PluginHolder extends RecyclerView.ViewHolder {
            final android.widget.ImageView pluginIcon;
            final View statusDot;
            final TextView name;
            final TextView formatTag;
            final TextView version;
            final TextView testStatus;
            final TextView pluginError;
            final TextView pluginStages;
            final TextView pluginTest;
            final com.google.android.material.switchmaterial.SwitchMaterial pluginSwitch;
            final ImageView pluginMore;
            final ImageView pluginDelete;

            PluginHolder(View v) {
                super(v);
                pluginIcon = v.findViewById(R.id.pluginIcon);
                statusDot = v.findViewById(R.id.statusDot);
                name = v.findViewById(R.id.name);
                formatTag = v.findViewById(R.id.formatTag);
                version = v.findViewById(R.id.version);
                testStatus = v.findViewById(R.id.testStatus);
                pluginError = v.findViewById(R.id.pluginError);
                pluginStages = v.findViewById(R.id.pluginStages);
                pluginTest = v.findViewById(R.id.pluginTest);
                pluginSwitch = v.findViewById(R.id.pluginSwitch);
                pluginMore = v.findViewById(R.id.pluginMore);
                pluginDelete = v.findViewById(R.id.pluginDelete);
                // U4：插件行开关 M3 Switch 缩放 0.75（与自动屏蔽行 0.72 区分，不混）
                pluginSwitch.setScaleX(0.75f);
                pluginSwitch.setScaleY(0.75f);
                // 插件开关（选中红、未选灰）：音乐点缀统一 GZ 红，代旧金
                ColorStateList thumb = new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                        new int[]{0xFFFA243C, 0xFF5E6B7E});
                ColorStateList track = new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                        new int[]{0x66FA243C, 0x445E6B7E});
                pluginSwitch.setThumbTintList(thumb);
                pluginSwitch.setTrackTintList(track);
            }
        }
    }

    /** 单参回调 */
    private interface NameInput {
        void onResult(String name);
    }
}
