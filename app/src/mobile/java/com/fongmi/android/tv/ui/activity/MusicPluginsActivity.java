package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.ui.music.MusicGlassWire;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.LxPlatformSearch;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.music.MusicPluginTest;
import com.fongmi.android.tv.bean.music.MusicRuntimeStatus;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.XcFadeTransition;
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 插件管理独立页（拆页 p13，本工程收口页）：旧列表壳的 sites 模式。
 * 构成：头卡（总数／运行／音源·歌词计数＋运行状态徽章）＋本地文件／在线地址两入口
 * ＋已安装头（工作计数＋排序胶囊）＋双轨分组插件行（落雪 JS／FreeJS 各自成列）
 * ＋专区段（播放路由／智能切换／自动屏蔽／运行时状态）。
 * 布局单滚动流写死（见 activity_music_plugins.xml 头注），无运行时重排。
 * 无返回箭头，走系统返回。
 */
public class MusicPluginsActivity extends BaseActivity {

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
    private TextView empty;
    private View pluginHeaderCard;
    private ImageView pluginHeaderIcon;
    private TextView pluginHeaderTitle;
    private TextView pluginHeaderSub;
    private TextView pluginHeaderBadge;
    private View pluginAddRow;
    private TextView pluginLocalTitle;
    private View pluginInstalledHeader;
    private TextView pluginInstalledCount;
    private TextView btnPluginSort;
    private View pluginEmptyCard;
    private LinearLayout pluginSections;

    private final List<Object> cards = new ArrayList<>();
    private PluginAdapter adapter;
    /** 插件导入进行中（头卡标题变「正在导入插件」、本地按钮标题变「导入中…」） */
    private boolean pluginImporting;
    /** X-08：重建时待回滚的列表滚动状态，数据到位后在 onResume 一次性回滚。 */
    private android.os.Parcelable pendingListState;

    /** 入口同名迁移：无 extras（插件管理是自足页），契约与旧壳 startSites 逐字同。
     * （扫查待裁 5：原先多写一件 mode 无人读、与本注释自相矛盾，已摘。） */
    public static void startSites(Activity activity) {
        Intent i = new Intent(activity, MusicPluginsActivity.class);
        activity.startActivity(i);
    }

    @Override
    protected ViewBinding getBinding() {
        // 只 inflate 一次：BaseActivity 先取根 setContentView、initView 再取根走 glassifyTree，
        // 两次必须是同一棵树——重复 inflate 会让接线落在游离树上、显示的那棵永不生效。
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_plugins, null);
        return new SimpleBinding(rootView);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicGlassWire.glassifyTree(getBinding().getRoot()); // 全页玻璃件同源接线：共用件按资源识别统一换液态玻璃（MusicGlassWire）
        // 本页 Manifest 没声明 windowSoftInputMode，默认键盘一弹窗口就 resize：
        // 内容区被压到键盘上方，状态文字（覆盖层里的 empty）会被带着乱跑。
        // 锁成 adjustPan：窗口不缩放、不重排；状态文字位置只跟页面有关、跟键盘无关。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        MusicRepository.ensureDebugFake();
        // X-08（批 5 F2）：重建恢复——滚动位置存布局管理器状态、数据到位后在 onResume 一次性回滚；
        // ml_keyword 键名不变（旧壳在 sites 下写的是恒隐藏的搜索框文本、恒空，本页无搜索框、写空串，
        // 恢复语义一致：sites 不吃 keyword）。
        if (savedInstanceState != null) {
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        pluginHeaderCard = findViewById(R.id.pluginHeaderCard);
        pluginHeaderIcon = findViewById(R.id.pluginHeaderIcon);
        pluginHeaderTitle = findViewById(R.id.pluginHeaderTitle);
        pluginHeaderSub = findViewById(R.id.pluginHeaderSub);
        pluginHeaderBadge = findViewById(R.id.pluginHeaderBadge);
        pluginAddRow = findViewById(R.id.pluginAddRow);
        pluginLocalTitle = findViewById(R.id.pluginLocalTitle);
        pluginInstalledHeader = findViewById(R.id.pluginInstalledHeader);
        pluginInstalledCount = findViewById(R.id.pluginInstalledCount);
        btnPluginSort = findViewById(R.id.btnPluginSort);
        pluginEmptyCard = findViewById(R.id.pluginEmptyCard);
        pluginSections = findViewById(R.id.pluginSections);
        // 本页对照修：空态点击监听是共享 overlay 的口径，本页空态无点击行为，进页先清
        empty.setOnClickListener(null);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new PluginAdapter());
        // 页内两入口按钮直连现有 pickLocalFile／inputSiteUrl，不再走二选一弹窗
        findViewById(R.id.btnPluginLocal).setOnClickListener(v -> pickLocalFile());
        findViewById(R.id.btnPluginOnline).setOnClickListener(v -> inputSiteUrl());
        btnPluginSort.setOnClickListener(v -> showPluginSortDialog());
        loadSites();
    }

    /** X-08（批 5 F2）：滚动位置存布局管理器状态；保存的只是本页 UI 态，不碰任何用户数据。 */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putString("ml_keyword", "");
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
        // X-08 滚动回滚：等本帧布局后一次性应用（数据异步未到时布局管理器会钳到现有行数，不崩；应用后即清，不反复拽）
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
        // 旧口径：sites 回页不重拉（旧 onResume 的 switch 无 sites 重拉支）
    }

    @Override
    protected void onDestroy() {
        if (pluginHeaderSpin != null) {
            pluginHeaderSpin.cancel();
            pluginHeaderSpin = null;
        }
        super.onDestroy();
    }

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    /** 插件分组头占位（adapter 内插项，非插件行；标题取 wave2 串） */
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

    /** 插件管理：全量列表（停用的也得在，不然关了就找不回来）＋头卡／两按钮／已安装头／排序后列表 */
    private void loadSites() {
        showLoading();
        Task.execute(() -> {
            List<MusicSite> sites = MusicSourceManager.get().listSites();
            List<MusicSite> ordered = sortSites(sites);
            runOnUiThread(() -> {
                pluginImporting = false;
                cards.clear();
                // 批 6 双轨分区（定案：落雪 JS＋FreeJS 双轨各自成列，空轨不渲染；组内保持排序后顺序）。
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
                refreshPluginHeader(sites);
                refreshPluginSections();
                // 空态改为空态卡，替代原 empty 文本；非空时两者都隐藏
                XcFadeTransition.fadeOutThenGone(empty);
                if (sites.isEmpty()) XcFadeTransition.fadeIn(pluginEmptyCard);
                else XcFadeTransition.fadeOutThenGone(pluginEmptyCard);
            });
        });
    }

    /** 头卡图标导入中旋转指示（导入结束即停并归零）。 */
    private android.animation.ObjectAnimator pluginHeaderSpin;

    private void refreshPluginHeaderSpin() {
        try {
            if (pluginHeaderIcon == null) return;
            if (pluginImporting) {
                if (pluginHeaderSpin == null) {
                    pluginHeaderSpin = android.animation.ObjectAnimator.ofFloat(
                            pluginHeaderIcon, "rotation", 0f, 360f);
                    pluginHeaderSpin.setDuration(1000);
                    pluginHeaderSpin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    pluginHeaderSpin.start();
                }
            } else if (pluginHeaderSpin != null) {
                pluginHeaderSpin.cancel();
                pluginHeaderSpin = null;
                pluginHeaderIcon.setRotation(0f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 运行状态面板（徽章点开；与专区状态卡同源数据：已加载／已启用／当前单选状态／异常名表／进行中态）。
     * 分平台检测行不在此：检测层 MusicPluginTest 未存分平台结果，不编分平台行。 */
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
        new MusicGlassDialogBuilder(this)
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

    /** 头卡标题／副标题／徽章（图标用现有 ic_music_note 近似，星辰无拼图图标） */
    private void refreshPluginHeader(List<MusicSite> sites) {
        pluginHeaderCard.setVisibility(View.VISIBLE);
        pluginAddRow.setVisibility(View.VISIBLE);
        pluginInstalledHeader.setVisibility(View.VISIBLE);
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
            pluginHeaderTitle.setText(R.string.music_plugin_importing);
        } else if (total == 0) {
            pluginHeaderTitle.setText(R.string.music_plugin_no_plugin);
        } else {
            pluginHeaderTitle.setText(total + " " + getString(R.string.music_plugins));
        }
        if (total == 0) {
            pluginHeaderSub.setText(R.string.music_plugin_import_desc);
        } else if (enabled == 0) {
            pluginHeaderSub.setText(R.string.music_plugin_all_disabled);
        } else {
            pluginHeaderSub.setText(getString(R.string.music_plugin_running) + "：" + enabled
                    + " 音源：" + audioCount + " 歌词：" + lyricCount);
        }
        pluginLocalTitle.setText(pluginImporting ? "导入中…" : getString(R.string.music_plugin_local_title));
        // 已安装头计数：N / M 正在工作（M=启用且检测态非 FAILED；未检测计入工作数）
        int working = 0;
        for (MusicSite s : sites) {
            if (!s.enabled) continue;
            MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (t == null || t.state != MusicPluginTest.State.FAILED) working++;
        }
        // GZ 计数顺序：字面是“工作数 / 总数 正在工作”，不是总数在前。
        // 未全检测时后缀检测覆盖率——行全是“未检测”时头再称“正在工作”而不提覆盖率，就是实拍的自相矛盾。
        String installed = working + " / " + total + " " + getString(R.string.music_plugin_working);
        if (tested < total) installed += " · 已检测 " + tested + " 个";
        pluginInstalledCount.setText(installed);
        btnPluginSort.setText(pluginSortLabel());
        // 排序钮补 16dp 下拉箭头（drawableEnd，不再用文案里的 ▾ 充数）
        try {
            android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_chev_v2);
            if (arrow != null) {
                arrow.setBounds(0, 0, dp(16), dp(16));
                btnPluginSort.setCompoundDrawables(null, null, arrow, null);
                btnPluginSort.setCompoundDrawablePadding(dp(4));
            }
        } catch (Throwable ignored) {
        }
        // 运行状态徽章可点，出状态面板
        pluginHeaderBadge.setOnClickListener(v -> showRuntimeStatusPanel());
        refreshPluginHeaderSpin();
        // 头卡徽章（仅展示，不弹面板）：优先级 隔离＞失败＞启动中＞无本地＞未检测＞正常。
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
        pluginHeaderBadge.setText(badge);
        pluginHeaderBadge.setTextColor(badgeColor);
        // 瓜子头卡徽章＝同色淡底药丸（非描边框）：底色＝徽章色 12% 透明度、满圆角
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(dp(100));
        badgeBg.setColor((badgeColor & 0x00FFFFFF) | 0x1F000000);
        pluginHeaderBadge.setBackground(badgeBg);
    }

    /** 平台排序真做——按 site.platform 分组排序（同平台内保原顺序，稳定排序），不再是点了没反应的假选项。 */
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

    /** 手动排序模式下相邻换位＋落盘＋刷新（存盘顺序即现序） */
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
        // 现状：单文件选择（未开 EXTRA_ALLOW_MULTIPLE），一次只装一个插件文件
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

    // ================= 检测 =================

    /** 行内钮启动检测（唯一检测入口）；回调回主线程后刷新行态与专区，完成后出分检测面板 */
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

    /** 分段检测面板：搜索／取播放／取歌词三段逐段显状态（通过／失败＋原因短文）。
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
        new MusicGlassDialogBuilder(this)
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

    /** 行内三段文案（搜索／取播放／取歌词一段一节，失败带原因短文；未检测返回空串＝不显行） */
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

    // ================= 专区段 =================

    /** 开关行＋自动屏蔽行＋运行时状态区＋播放路由卡（仅本页） */
    private void refreshPluginSections() {
        if (pluginSections == null) return;
        pluginSections.setVisibility(View.VISIBLE);
        pluginSections.removeAllViews();
        // ---- 播放路由卡（瓜子 LxPluginScreen：区头「播放路由／高级设置」18sp＋取链策略 15sp／副文 12sp＋指定平台插件 15sp／副文 12sp；
        // 取链策略两档照瓜子 LxPluginPriority：fallback 自动切换／pluginOnly 仅插件；指定平台插件行点进平台-插件指派表。
        // 注意：策略与指派只做存取＋UI，取链解析侧消费不在本页（MusicRepository），见全账交卷待办。 ----
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
        pluginSections.addView(routeCard);
        // ---- 多插件智能切换行（标题 15sp；说明句按忌介绍文字铁律去掉，只留名字＋开关，功能不动） ----
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
                        new MusicGlassDialogBuilder(MusicPluginsActivity.this)
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
        // ---- 自动屏蔽行（另一类行，开关缩放 0.72，与插件行 0.75 区分；说明句同上按铁律去掉。
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
        pluginSections.addView(switchCard);
        // ---- 运行时状态区（异常项＋重新加载钮；总数／启用数与头卡重复，已删） ----
        MusicRuntimeStatus st = MusicSourceManager.get().runtimeStatus();
        LinearLayout card = sectionCard();
        TextView title = new TextView(this);
        // GZ 面板标题“插件运行状态”18sp；星辰是页内卡不是独立面板，标题照 GZ 取名，说明段按铁律全删、只留名字＋状态行
        title.setText("插件运行状态");
        title.setTextSize(18);
        title.setTextColor(0xFF1C1C1E);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);
        // 状态卡补行——副文（已加载／已启用）＋当前单选状态（徽章同口径五选一取一）＋进行中态
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
        // 状态卡只留头卡没有的异常项——已加载总数／已启用数与头卡重复，删掉，不再两处对不上
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
        pluginSections.addView(card);
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

    /** 路由区文字行（标题 15sp 半粗＋副文 12sp 灰；瓜子取链策略／指定平台插件行口径） */
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

    /** 指定平台插件指派表（瓜子 AssignOptionRow 口径：默认（自动选择）＋各启用插件；
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
        MaterialAlertDialogBuilder builder = new MusicGlassDialogBuilder(this)
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

    // ================= 行菜单／删除／屏蔽 =================

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
        // 仅手动排序模式给上移／下移（首尾不给对应方向）
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
                        if (isFinishing() || isDestroyed()) return;
                        if (v == null) {
                            Notify.show(R.string.music_plugin_latest);
                            return;
                        }
                        // 瓜子更新弹窗：标题「音源插件发现新版本」＋「立即更新」／「忽略」＋副文
                        new MusicGlassDialogBuilder(MusicPluginsActivity.this)
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

    /** 删除插件确认（瓜子移除弹窗，仅本机文案；云端备份选项属排除项，不出）。
     * ⋮ 菜单删除与行内删除钮共用这一份（removeSite 连本地文件一起删）。 */
    private void confirmDeletePlugin(MusicSite site) {
        new MusicGlassDialogBuilder(this)
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

    /** 单插件屏蔽平台弹层（GZ PlatformBlockSheet：标题 18sp、说明 12sp、平台行 15sp＋行尾已屏蔽态、
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
        new MusicGlassDialogBuilder(this).setView(root)
                .setPositiveButton("完成", null)
                .show();
    }

    // ================= 导入 =================

    /** 导入进度弹窗。阶段／完成态／失败重试都在此；
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
        AlertDialog dialog = new MusicGlassDialogBuilder(this)
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

    /**
     * 插件导入确认（瓜子「第三方插件导入确认」：标题＋导语＋前两条照瓜子；
     * 第 3 条改写为星辰版，probe 先行再落盘仍是安装链行为，只是对话框文案不再单列）。
     */
    private void confirmPluginImport(Runnable onAgree) {
        String msg = "请仔细阅读以下提示，点击「同意并导入」即代表你已同意\n\n"
                + "1. 该脚本来自第三方，非星辰提供。请自行判断其用途与潜在风险（内容、播放链接等），相关责任由脚本作者和使用者自行承担；\n"
                + "2. 脚本会在本应用内执行代码，并可发起网络请求，请仅导入你信任的来源；\n"
                + "3. 星辰不发布、不内置、不推荐任何第三方插件，在线播放与歌词由用户自行导入的脚本提供。\n"
                + "4. 安装前会先试运行一次，确认能正常提供音源才会保留。";
        new MusicGlassDialogBuilder(this)
                .setTitle("第三方插件导入确认")
                .setMessage(msg)
                .setPositiveButton("同意并导入", (d, w) -> onAgree.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void inputSiteUrl() {
        // 标题「从链接导入」＋说明行「粘贴脚本地址，支持 raw / latest.js」；空值直接返回保留
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
        new MusicGlassDialogBuilder(this)
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

    // ================= 列表 =================

    private class PluginAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_PLUGIN = 2;
        /** 插件分组头（列表内插项） */
        private static final int TYPE_PLUGIN_GROUP = 20;

        @Override
        public int getItemViewType(int position) {
            if (cards.get(position) instanceof PluginGroup) return TYPE_PLUGIN_GROUP;
            return TYPE_PLUGIN;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_PLUGIN_GROUP) {
                // 瓜子分组头：无卡底、蓝点＋组名＋右端计数；分组头不可折叠（旧箭头仅装饰），无点击行为。
                LinearLayout row = new LinearLayout(parent.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                View dot = new View(parent.getContext());
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setColor(0xFF42A5F5);
                dot.setBackground(dotBg);
                row.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(14);
                tv.setTextColor(0xFFF1F4F5);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.leftMargin = dp(8);
                row.addView(tv, tlp);
                row.addView(new View(parent.getContext()),
                        new LinearLayout.LayoutParams(0, 0, 1f));
                TextView count = new TextView(parent.getContext());
                count.setTextSize(13);
                count.setTextColor(0x99FFFFFF);
                row.addView(count, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(12);
                row.setLayoutParams(lp);
                return new GroupHolder(row, tv, count);
            }
            return new PluginHolder(inf.inflate(R.layout.item_music_plugin, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (h instanceof GroupHolder) {
                // 插件分组头（cards 内插项，位置即 cards 下标）；右端计数＝其后连续插件卡数
                Object o = cards.get(position);
                ((GroupHolder) h).title.setText(o instanceof PluginGroup ? ((PluginGroup) o).title : "");
                int n = 0;
                for (int j = position + 1; j < cards.size(); j++) {
                    if (cards.get(j) instanceof MusicSite) n++;
                    else break;
                }
                ((GroupHolder) h).count.setText(String.valueOf(n));
            } else if (h instanceof PluginHolder) {
                bindPlugin((PluginHolder) h, position);
            }
        }

        @Override
        public int getItemCount() {
            return cards.size();
        }

        /** 插件是否官方预置（按播种地址认，用户改名／改地址后不算官方）。 */
        private boolean isOfficialSite(MusicSite s) {
            if (s == null || s.srcUrl == null) return false;
            for (MusicSite b : MusicSourceManager.builtinSites()) {
                if (s.srcUrl.equals(b.srcUrl)) return true;
            }
            return false;
        }

        /** 插件图标色：色盘收口到 MusicSourceManager.stableColorForKey/siteColor
         * （同源恒同色、全页共用一份），本页不自备色盘；键与旧实现一致（源地址、改名不变），存量颜色不变。 */
        private int pluginColor(MusicSite s) {
            return MusicSourceManager.siteColor(s);
        }

        /** 插件行：名＋版本号小字＋格式标签；右侧开关（真接线启停）＋⋮菜单 */
        private void bindPlugin(PluginHolder h, int position) {
            // cards 是混装列表（PluginGroup＋MusicSite）：绑定前先验型，
            // 不靠视图分型的间接不变量兜底，混入第三类时不许强转崩列表（大扫查防崩同型命中）。
            Object card = cards.get(position);
            if (!(card instanceof MusicSite)) return;
            MusicSite s = (MusicSite) card;
            h.name.setText(s.name == null ? "" : s.name);
            if (h.pluginIcon != null) {
                h.pluginIcon.setImageResource(R.drawable.ic_music_puzzle);
                int c = pluginColor(s);
                h.pluginIcon.setImageTintList(ColorStateList.valueOf(c));
                // 瓜子行图标＝彩色淡底方块＋同色拼图：底色＝行色 12% 透明度、圆角 12dp
                GradientDrawable iconBg = new GradientDrawable();
                iconBg.setShape(GradientDrawable.RECTANGLE);
                iconBg.setCornerRadius(dp(12));
                iconBg.setColor((c & 0x00FFFFFF) | 0x1F000000);
                h.pluginIcon.setBackground(iconBg);
            }
            boolean lx = MusicSourceManager.isLx(s);
            h.formatTag.setText(lx ? "落雪" : "FreeJS");
            h.formatTag.setTextColor(lx ? 0xFFFA243C : 0xFF9AA7B8);
            // 副文案串单行“官方 · 版本 · 作者 · 耗时”（只有真实数据的段才拼；
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
            // 四态显示＋检测中态（行内钮定为唯一检测入口，pluginMenu 不另加检测项）
            MusicPluginTest test = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (MusicSourceManager.get().isTesting(s.srcUrl)) {
                h.testStatus.setText("检测中…");
                h.testStatus.setTextColor(0x99FFFFFF);
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
            // 检测失败且 message 非空时行内另起一行显 message（11sp，色与状态色一致）
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
            // 三段行（已检测才显；检测中收起，避免旧段结果与「检测中…」打架）
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

        /** 状态圆点着色（mutate 后改色，不污染其它行的共用 drawable）。 */
        private void setStatusDot(View dot, int color) {
            try {
                android.graphics.drawable.Drawable d = dot.getBackground();
                if (d == null) return;
                d = d.mutate();
                if (d instanceof GradientDrawable) {
                    ((GradientDrawable) d).setColor(color);
                }
            } catch (Throwable ignored) {
            }
        }

        /** 插件分组头 holder（程序化 TextView） */
        class GroupHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView count;

            GroupHolder(View itemView, TextView title, TextView count) {
                super(itemView);
                this.title = title;
                this.count = count;
            }
        }

        class PluginHolder extends RecyclerView.ViewHolder {
            final ImageView pluginIcon;
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
                MusicGlassWire.glassifyTree(v); // 行一框：行内玻璃件同源接线（根或内卡按资源识别）
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
                // 插件行开关 M3 Switch 缩放 0.75（与自动屏蔽行 0.72 区分，不混）
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
}
