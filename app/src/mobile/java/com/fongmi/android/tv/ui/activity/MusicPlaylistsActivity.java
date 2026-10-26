package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.ui.music.MusicGlassWire;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.AccountPlaylistFetcher;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.LxPlatformSearch;
import com.fongmi.android.tv.api.music.PlaylistImporter;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.AccountPlaylistDialog;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicPlaylistImportDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.XcFadeTransition;
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;
import com.fongmi.android.tv.ui.music.MusicGlassDialogs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 我的歌单列表独立页（拆页 p8）：GZ 页头（YOUR PLAYLISTS＋大标题＋多选／全部 pill）
 * ＋操作行（新建／导入／搜索）＋歌单行（马赛克封面＋名＋曲目数＋更新时间＋播放圆钮＋⋮）；
 * 数据走 MusicLibrary 歌单口径（存量救援读在 MusicLibrary／存储层，本页只消费）；
 * 行点击进歌单详情独立页 MusicPlaylistActivity；
 * 导入歌单面板／登录导入／.lxmc 文件导入的接线只在本页可达，随本页自带。
 * 顶栏在旧页 sheets 下恒隐藏、本页不带；无返回箭头，走系统返回。
 */
public class MusicPlaylistsActivity extends BaseActivity {

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
    private View searchBar;
    private EditText etKeyword;
    private TextView sheetsTitle;
    private TextView btnSheetsMulti;
    private View toolbar;
    private View btnPlayAll;
    private ImageView ivPlayAllIcon;
    private TextView tvPlayAll;
    private ImageView btnSort;
    private TextView tvSortCurrent;
    private LinearLayout sheetsEmpty;
    private ImageView sheetsEmptyIcon;
    private TextView sheetsEmptyTitle;
    private TextView sheetsEmptySub;
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

    private final List<MusicPlaylist> cards = new ArrayList<>();
    private PlaylistsAdapter adapter;
    private String bigTitle = "";
    // 歌单页态：多选批量删除＋歌单名搜索过滤（星辰歌单全是本地自建，无平台属性，筛选只有“全部”）
    private boolean sheetsMulti;
    private final Set<Long> sheetsSelected = new java.util.HashSet<>();
    private final List<MusicPlaylist> allSheets = new ArrayList<>();
    /** 歌单 id→前 4 张不重复曲目封面（马赛克用；loadSheets 时随曲目数一并取，行绑只读缓存，滚动不重复查）。 */
    private final java.util.Map<Long, java.util.List<String>> sheetCovers = new java.util.HashMap<>();
    /** onResume 首过标记：首次由 initView 的 loadSheets 负责，之后回页才重拉。 */
    private boolean resumedOnce = false;
    private android.os.Parcelable pendingListState;

    /** 通用排序第二段升降序弹层的 post（页销毁取消，见 onDestroy） */
    private final android.os.Handler sortDialogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sortDirRunnable;

    private static final int REQ_PICK_LXMC = 4214;

    public static void startSheets(Activity activity) {
        activity.startActivity(new Intent(activity, MusicPlaylistsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        // 只 inflate 一次：BaseActivity 先取根 setContentView、initView 再取根走 glassifyTree，
        // 两次必须是同一棵树——重复 inflate 会让接线落在游离树上、显示的那棵永不生效。
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_playlists, null);
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
        searchBar = findViewById(R.id.searchBar);
        etKeyword = findViewById(R.id.etKeyword);
        sheetsTitle = findViewById(R.id.sheetsTitle);
        btnSheetsMulti = findViewById(R.id.btnSheetsMulti);
        toolbar = findViewById(R.id.toolbar);
        btnPlayAll = findViewById(R.id.btnPlayAll);
        ivPlayAllIcon = findViewById(R.id.ivPlayAllIcon);
        tvPlayAll = findViewById(R.id.tvPlayAll);
        btnSort = findViewById(R.id.btnSort);
        tvSortCurrent = findViewById(R.id.tvSortCurrent);
        sheetsEmpty = findViewById(R.id.sheetsEmpty);
        sheetsEmptyIcon = findViewById(R.id.sheetsEmptyIcon);
        sheetsEmptyTitle = findViewById(R.id.sheetsEmptyTitle);
        sheetsEmptySub = findViewById(R.id.sheetsEmptySub);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        bigTitle = getString(R.string.music_playlist);
        // 重建恢复：过滤词回填输入框（loadSheets 完成时 applySheetsFilter 按框内词过滤，口径同旧页）
        if (savedInstanceState != null) {
            String savedKw = savedInstanceState.getString("ml_keyword");
            if (savedKw != null && !savedKw.isEmpty()) etKeyword.setText(savedKw);
        }
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new PlaylistsAdapter());
        btnPlayAll.setOnClickListener(v -> shufflePlay());
        findViewById(R.id.btnPlaySeq).setOnClickListener(v -> playAllSequential());
        btnSort.setOnClickListener(v -> showGenericSortDialog());
        // 歌单页头／操作行
        findViewById(R.id.btnSheetsNew).setOnClickListener(v -> inputPlaylistName(name ->
                MusicLibrary.createPlaylist(name, p -> loadSheets())));
        findViewById(R.id.btnSheetsImport).setOnClickListener(v -> showImportPlaylistDialog());
        findViewById(R.id.btnSheetsSearch).setOnClickListener(v -> {
            boolean show = searchBar.getVisibility() != View.VISIBLE;
            searchBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) applySheetsFilter();
        });
        btnSheetsMulti.setOnClickListener(v -> setSheetsMulti(!sheetsMulti));
        findViewById(R.id.btnSheetsFilter).setOnClickListener(v ->
                MusicOptionsDialog.show(this, "歌单筛选",
                        java.util.Collections.singletonList("全部"), 0, idx -> applySheetsFilter()));
        TextView searchCancel = findViewById(R.id.searchCancel);
        if (searchCancel != null) searchCancel.setOnClickListener(v -> finish());
        // 歌单页搜索走键盘搜索键提交（与旧页 etKeyword 编辑动作口径一致）
        etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                applySheetsFilter();
                return true;
            }
            return false;
        });
        loadSheets();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putString("ml_keyword", etKeyword != null && etKeyword.getText() != null
                    ? etKeyword.getText().toString() : "");
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
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        // 回页重拉本地歌单（旧 onResume 的 sheets 支）
        loadSheets();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().unbind();
        if (sortDialogHandler != null) sortDialogHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_LXMC) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                runLxmcImport(data.getData());
            }
        }
    }

    // ================= 数据加载 =================

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    private void loadSheets() {
        // 空态点击监听是共享 overlay 的口径：本页空态无点击行为，进页先清
        empty.setOnClickListener(null);
        showLoading();
        MusicLibrary.playlists(list -> {
            allSheets.clear();
            sheetCovers.clear();
            allSheets.addAll(list);
            runOnUiThread(() -> {
                sheetsTitle.setText(bigTitle);
                applySheetsFilter();
                refreshSheetsEmpty();
                // 异步补每个歌单的曲目数（按 id 在当前过滤后列表里定位，不依赖加载时下标）
                for (MusicPlaylist p : list) {
                    MusicLibrary.playlistTrackCount(p.getId(), n -> {
                        p.trackCount = n;
                        for (int i = 0; i < cards.size(); i++) {
                            if (cards.get(i).getId() == p.getId()) {
                                adapter.notifyItemChanged(i);
                                break;
                            }
                        }
                    });
                    // 同循环取前 4 张封面进马赛克缓存（与曲目数同定位口径；空歌单记空表，行绑回落图标）
                    MusicLibrary.playlistTracks(p.getId(), tracks -> {
                        java.util.List<String> covers = new java.util.ArrayList<>();
                        if (tracks != null) {
                            for (MusicTrack t : tracks) {
                                if (t != null && t.cover != null && !t.cover.isEmpty() && !covers.contains(t.cover)) {
                                    covers.add(t.cover);
                                    if (covers.size() >= 4) break;
                                }
                            }
                        }
                        sheetCovers.put(p.getId(), covers);
                        runOnUiThread(() -> {
                            for (int i = 0; i < cards.size(); i++) {
                                if (cards.get(i).getId() == p.getId()) {
                                    adapter.notifyItemChanged(i);
                                    break;
                                }
                            }
                        });
                    });
                }
            });
        });
    }

    /** 歌单名过滤（操作行搜索钮切出的搜索框内关键字，空则全量；新建／导入已由操作行替代，不再进列表） */
    private void applySheetsFilter() {
        String q = etKeyword.getText() == null ? ""
                : etKeyword.getText().toString().trim();
        cards.clear();
        for (MusicPlaylist p : allSheets) {
            if (q.isEmpty() || (p.getName() != null && p.getName().contains(q))) cards.add(p);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshSheetsEmpty();
    }

    /** 歌单页空态（瓜子 EmptyPlaylistsHint 口径）：歌单总数为 0 时显图标＋两行空态；
     * 搜索过滤无匹配时同块改纯文本态（无图标、副行“换个关键词试试”）；
     * 有歌单时整块藏。瓜子多选 pill 空列表时置灰禁用（enabled＝过滤后列表非空，禁用 alpha 0.4）。 */
    private void refreshSheetsEmpty() {
        fadeListInOnce();
        sheetsEmpty.setVisibility(View.GONE);
        XcFadeTransition.fadeOutThenGone(empty);
        String q = etKeyword.getText() == null ? ""
                : etKeyword.getText().toString().trim();
        boolean noSheets = allSheets.isEmpty() && q.isEmpty();
        if (noSheets) {
            sheetsEmptyIcon.setVisibility(View.VISIBLE);
            sheetsEmptyTitle.setText(R.string.music_no_playlist);
            sheetsEmptySub.setVisibility(View.VISIBLE);
            sheetsEmpty.setVisibility(View.VISIBLE);
        } else if (cards.isEmpty()) {
            sheetsEmptyIcon.setVisibility(View.GONE);
            sheetsEmptyTitle.setText(R.string.music_no_match_playlist);
            // 搜索无匹配补引导半句（瓜子后半句“或切到其他平台”是跨平台歌单搜索口径，
            // 星辰歌单搜索只搜本地歌单、无平台可切，故只取前半句）
            sheetsEmptySub.setVisibility(View.VISIBLE);
            sheetsEmptySub.setText("换个关键词试试");
            sheetsEmpty.setVisibility(View.VISIBLE);
        }
        boolean multiEnabled = !cards.isEmpty();
        btnSheetsMulti.setEnabled(multiEnabled);
        btnSheetsMulti.setAlpha(multiEnabled ? 1f : 0.4f);
    }

    /** 多选开关——开则行点按变勾选、工具条变“删除所选 (N)”；关则清空勾选恢复正常 */
    private void setSheetsMulti(boolean on) {
        sheetsMulti = on;
        if (!on) sheetsSelected.clear();
        btnSheetsMulti.setText(on ? "取消多选" : "多选");
        btnSheetsMulti.setTextColor(on ? 0xFFFA243C : 0xFFF1F4F5);
        toolbar.setVisibility(on ? View.VISIBLE : View.GONE);
        btnSort.setVisibility(on ? View.GONE : View.VISIBLE);
        tvPlayAll.setText("删除所选 (" + sheetsSelected.size() + ")");
        ivPlayAllIcon.setImageResource(on ? R.drawable.ic_music_delete
                : R.drawable.ic_music_shuffle);
        btnPlayAll.setOnClickListener(on ? v -> deleteSheetsSelected()
                : v -> shufflePlay());
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void deleteSheetsSelected() {
        if (sheetsSelected.isEmpty()) {
            Notify.show("请先选歌单");
            return;
        }
        List<Long> ids = new ArrayList<>(sheetsSelected);
        sheetsSelected.clear();
        for (Long id : ids) MusicLibrary.deletePlaylist(id, null);
        setSheetsMulti(false);
        loadSheets();
    }

    // ================= 工具条播放（歌单页无歌曲行，照旧口径只弹空提示；整单播放入口在行内播放钮） =================

    private void shufflePlay() {
        Notify.show(R.string.music_no_track);
    }

    private void playAllSequential() {
        Notify.show(R.string.music_no_track);
    }

    /** 通用排序档常显（工具条旁） */
    private void refreshSortLabel() {
        if (tvSortCurrent == null) return;
        String[] keys = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        int s = MusicStore.getListSort();
        String label = keys[s < 0 || s >= keys.length ? 0 : s] + (MusicStore.isListSortDesc() ? " ↓" : " ↑");
        tvSortCurrent.setText(label);
        tvSortCurrent.setVisibility(View.VISIBLE);
        btnSort.setContentDescription("排序：" + label);
    }

    /** 通用排序改对话框（默认／标题／歌手／时长＋升降）；歌单页是卡片页，落点即重拉本页（同旧 isCardMode 支） */
    private void showGenericSortDialog() {
        String[] keys = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        MusicOptionsDialog.show(this, "排序", java.util.Arrays.asList(keys), MusicStore.getListSort(), idx -> {
            MusicStore.setListSort(idx);
            refreshSortLabel();
            loadSheets();
            sortDirRunnable = () -> {
                if (isFinishing() || isDestroyed()) return;
                MusicOptionsDialog.show(this, "升序 / 降序",
                        java.util.Arrays.asList("升序", "降序"), MusicStore.isListSortDesc() ? 1 : 0, dir -> {
                            MusicStore.setListSortDesc(dir == 1);
                            refreshSortLabel();
                            loadSheets();
                        });
            };
            sortDialogHandler.post(sortDirRunnable);
        });
    }

    /** 行内整单播放的入队来源参数：播的是歌单 p 本人，身份照歌单详情页口径
     * （mode=sheet＋playlistId=p 的 id＋title=p 名）——旧写法报 mode=sheets＋
     * playlistId=-1，队列来源跳转只能落歌单列表、落不回该歌单（扫查待裁 3）。
     * 本页自身不再有整页入队点，此参数只服务行内播放入口。 */
    private java.util.Map<String, String> sheetRouteParams(MusicPlaylist p) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "sheet");
        params.put("keyword", "");
        params.put("sitePlatform", "");
        params.put("playlistId", String.valueOf(p.getId()));
        params.put("title", p.getName() == null ? "" : p.getName());
        return params;
    }

    // ================= 建歌单弹窗 =================

    private interface NameInput {
        void onResult(String name);
    }

    private void inputPlaylistName(NameInput cb) {
        // 照 GZ CreatePlaylistDialog：红图标＋标题＋副标题＋圆角输入框＋大创建钮。
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
        final android.app.Dialog dialog = new MusicGlassDialogBuilder(this)
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= 导入歌单 =================

    /** GZ 导入歌单大面板（分享链接导入与 .lxmc 导入、登录导入复用现有逻辑） */
    private void showImportPlaylistDialog() {
        MusicPlaylistImportDialog.show(this, new MusicPlaylistImportDialog.Listener() {
            @Override
            public void onImportText(String text, MusicPlaylistImportDialog dialog) {
                runPlaylistImportNew(text, dialog);
            }

            @Override
            public void onLxmc() {
                pickLxmcFile();
            }

            @Override
            public void onAccountImport(String platform) {
                showAccountLoginDialog(platform);
            }
        });
    }

    private void runPlaylistImportNew(String text, MusicPlaylistImportDialog dialog) {
        String input = text == null ? "" : text.trim();
        if (input.isEmpty()) {
            dialog.setError(R.string.music_import_err_paste_empty);
            return;
        }
        dialog.setBusy(true);
        Task.execute(() -> {
            PlaylistImporter.Result result = null;
            PlaylistImporter.Fail fail = null;
            boolean lxOk = false;
            try {
                result = PlaylistImporter.importFromText(input);
                lxOk = PlaylistImporter.hasLxSupport(result.platformKey);
            } catch (PlaylistImporter.ImportException e) {
                fail = e.fail;
            } catch (Throwable t) {
                fail = PlaylistImporter.Fail.NETWORK;
            }
            PlaylistImporter.Result r = result;
            PlaylistImporter.Fail f = fail;
            boolean lx = lxOk;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (f != null || r == null) {
                    dialog.setBusy(false);
                    dialog.setError(importErrorText(f == null ? PlaylistImporter.Fail.NETWORK : f));
                    return;
                }
                // 无落雪支撑时导入前二次确认（歌单仍照常落库前先问）
                Runnable doImport = () -> MusicLibrary.importPlaylist(r.name, r.tracks, res -> {
                    if (res.inserted <= 0) {
                        if (res.playlist != null) MusicLibrary.deletePlaylist(res.playlist.getId(), null);
                        dialog.setBusy(false);
                        dialog.setError(R.string.music_import_err_empty);
                        return;
                    }
                    dialog.dismissAllowingStateLoss();
                    loadSheets();
                    String msg;
                    if (r.total > res.inserted) {
                        msg = getString(R.string.music_import_partial, res.inserted, r.total - res.inserted);
                    } else {
                        msg = getString(R.string.music_imported, res.playlist.getName(), res.inserted);
                    }
                    if (!lx) {
                        msg += "，" + getString(R.string.music_import_need_lx,
                                LxPlatformSearch.platformName(r.platformKey));
                    }
                    Notify.show(msg);
                });
                if (!lx) {
                    dialog.setBusy(false);
                    new MusicGlassDialogBuilder(MusicPlaylistsActivity.this)
                            .setTitle("需要落雪脚本")
                            .setMessage("这些歌需要装支持"
                                    + LxPlatformSearch.platformName(r.platformKey)
                                    + "的落雪源才能播，当前没装，仍要导入吗？")
                            .setPositiveButton("仍要导入", (d, w) -> doImport.run())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    doImport.run();
                }
            });
        });
    }

    /**
     * 登录账号导入——只放开这一处。登录页照 GZ：WebView 载平台站、开第三方 Cookie、
     * GZ 提示原文、“完成，获取Cookie”钮。Cookie 读到后：手机直接调平台公开接口拉“我的歌单”列表
     * （AccountPlaylistFetcher，Cookie 不出手机、不走任何第三方服务器），点某张走 PlaylistImporter
     * 现有导入链进歌单页；失败报真实原因，不编假导入。
     * 已登录账号行＋退出入口（登录入口即管理入口，树内无独立账号页）：
     * CookieManager 是 WebView 的持久 Cookie 存贮：此前登过且 Cookie 还在时，不再弹
     * WebView，直接给“已登录”态＋[拉取歌单／退出账号]；退出只清对应域 Cookie（按
     * Cookie 串里的名逐个写过期值，不动另一平台），随后 flush 落盘。
     */
    private void showAccountLoginDialog(String platform) {
        boolean netease = "netease".equals(platform);
        String url = netease ? "https://music.163.com/" : "https://y.qq.com/";
        String title = "登录 " + (netease ? "网易云音乐" : "QQ音乐");
        String existing = null;
        try {
            existing = android.webkit.CookieManager.getInstance().getCookie(url);
        } catch (Throwable ignored) {
        }
        if (existing != null && !existing.trim().isEmpty()) {
            String cookie = existing;
            new MusicGlassDialogBuilder(this)
                    .setTitle("已登录账号（" + (netease ? "网易云音乐" : "QQ音乐") + "）")
                    .setMessage("登录 Cookie 已保存在本机，可直接拉取歌单；退出将只清该平台的 Cookie。")
                    .setPositiveButton("拉取歌单", (d, w) -> fetchAccountSheets(platform, cookie))
                    .setNeutralButton("退出账号", (d, w) -> {
                        try {
                            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                            for (String part : cookie.split(";")) {
                                String name = part.trim().split("=", 2)[0].trim();
                                if (!name.isEmpty()) cm.setCookie(url, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                            }
                            cm.flush();
                        } catch (Throwable ignored) {
                        }
                        Notify.show("已退出" + (netease ? "网易云音乐" : "QQ音乐") + "账号");
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        android.webkit.WebView web = new android.webkit.WebView(this);
        android.webkit.WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.loadUrl(url);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView tip = new TextView(this);
        tip.setText("请在网页中使用桌面版登录（可用手机端 App 扫码，或账号密码登录），登录完成后点下方按钮");
        tip.setTextSize(13);
        tip.setTextColor(0xFF8E8E93);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);
        box.addView(tip);
        box.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        AlertDialog dialog = new MusicGlassDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("完成，获取Cookie", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // 本站自带 OnShowListener 会覆盖玻璃壳在 create 里挂的显示回调，故在监听首行手动补壳的整片收口
        dialog.setOnShowListener(d -> {
            MusicGlassDialogs.apply(dialog);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                    if (cookie == null || cookie.isEmpty()) {
                        Notify.show("未读到登录 Cookie，请先在网页里登录");
                        return;
                    }
                    try {
                        android.webkit.CookieManager.getInstance().flush();
                    } catch (Throwable ignored) {
                    }
                    dialog.dismiss();
                    fetchAccountSheets(platform, cookie);
                });
        });
        dialog.show();
    }

    /** 登录账号导入：Cookie 读到后，手机直接调平台公开接口拉“我的歌单”列表，点某张走现有导入链 */
    private void fetchAccountSheets(String platform, String cookie) {
        boolean netease = "netease".equals(platform);
        String platformKey = netease ? LxPlatformSearch.WY : LxPlatformSearch.QQ;
        String platformName = netease ? "网易云音乐" : "QQ音乐";
        Notify.show("正在拉取" + platformName + "歌单…");
        Task.execute(() -> {
            List<AccountPlaylistFetcher.Sheet> sheets = null;
            String reason = null;
            try {
                sheets = netease ? AccountPlaylistFetcher.fetchNetease(cookie) : AccountPlaylistFetcher.fetchQq(cookie);
            } catch (AccountPlaylistFetcher.FetchException e) {
                reason = e.getMessage();
            } catch (Throwable t) {
                reason = "网络错误";
            }
            List<AccountPlaylistFetcher.Sheet> result = sheets;
            String failReason = reason;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (failReason != null) {
                    Notify.show(failReason);
                    return;
                }
                if (result == null || result.isEmpty()) {
                    Notify.show("该账号下没有歌单");
                    return;
                }
                AccountPlaylistDialog.show(this, platformName + "歌单（" + result.size() + "）", result,
                        sheet -> importAccountSheet(platformKey, sheet));
            });
        });
    }

    /** 登录账号导入：点某张“我的歌单”，走 PlaylistImporter 现有导入链进歌单页 */
    private void importAccountSheet(String platformKey, AccountPlaylistFetcher.Sheet sheet) {
        String displayName = sheet.name == null || sheet.name.isEmpty() ? sheet.id : sheet.name;
        Notify.show("正在导入“" + displayName + "”…");
        Task.execute(() -> {
            PlaylistImporter.Result r = null;
            PlaylistImporter.Fail f = null;
            boolean lxOk = false;
            try {
                r = PlaylistImporter.importFromId(platformKey, sheet.id);
                lxOk = PlaylistImporter.hasLxSupport(r.platformKey);
            } catch (PlaylistImporter.ImportException e) {
                f = e.fail;
            } catch (Throwable t) {
                f = PlaylistImporter.Fail.NETWORK;
            }
            PlaylistImporter.Result result = r;
            PlaylistImporter.Fail fail = f;
            boolean lx = lxOk;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fail != null || result == null) {
                    Notify.show(importErrorText(fail == null ? PlaylistImporter.Fail.NETWORK : fail));
                    return;
                }
                MusicLibrary.importPlaylist(result.name, result.tracks, res -> {
                    if (res.inserted <= 0) {
                        if (res.playlist != null) MusicLibrary.deletePlaylist(res.playlist.getId(), null);
                        Notify.show(importErrorText(PlaylistImporter.Fail.EMPTY));
                        return;
                    }
                    loadSheets();
                    String baseMsg;
                    if (result.total > res.inserted) {
                        baseMsg = getString(R.string.music_import_partial, res.inserted, result.total - res.inserted);
                    } else {
                        baseMsg = getString(R.string.music_imported, res.playlist.getName(), res.inserted);
                    }
                    if (!lx) {
                        baseMsg += "，" + getString(R.string.music_import_need_lx,
                                LxPlatformSearch.platformName(result.platformKey));
                    }
                    // 收藏音乐一步：AccountPlaylistFetcher 没有“我喜欢”独立接口，
                    // 收藏只能落在已拉到的歌单里：当导入的正是平台惯用的喜欢歌单
                    // （网易云“我喜欢的音乐”／QQ“我喜欢”）时，把同批曲目按收藏落库口径
                    // （MusicLibrary.setFavorite，与行内收藏同一 dao）批量写入收藏；
                    // 其它歌单不触发本步。喜欢歌单若不在拉到的列表里，本步无从触发。
                    String likedName = sheet.name == null ? "" : sheet.name.trim();
                    boolean likedSheet = "我喜欢的音乐".equals(likedName) || "我喜欢".equals(likedName);
                    if (!likedSheet || result.tracks == null || result.tracks.isEmpty()) {
                        Notify.show(baseMsg);
                        return;
                    }
                    String finalMsg = baseMsg;
                    java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
                    int total = result.tracks.size();
                    for (MusicTrack favTrack : result.tracks) {
                        MusicLibrary.setFavorite(favTrack, true, () -> {
                            // total 是导入源全量，去重后真实入库数可能更少，不许拿全量冒充入库数；
                            // 不按 insert 返回值重算（不改 dao 口径），提示去掉数字。
                            if (done.incrementAndGet() >= total) {
                                Notify.show(finalMsg + "，已同步收藏");
                            }
                        });
                    }
                });
            });
        });
    }

    private int importErrorText(PlaylistImporter.Fail fail) {
        switch (fail) {
            case UNRECOGNIZED:
                return R.string.music_import_err_unrecognized;
            case NOT_FOUND:
                return R.string.music_import_err_notfound;
            case EMPTY:
                return R.string.music_import_err_empty;
            default:
                return R.string.music_import_err_network;
        }
    }

    // ================= 洛雪 .lxmc 文件导入 =================

    /** .lxmc 解析出的一个歌单（瓜子 LxPlaylistParser 的两种结构：备份多歌单／单歌单） */
    private static class LxmcPlaylist {
        final String name;
        final List<MusicTrack> tracks;

        LxmcPlaylist(String name, List<MusicTrack> tracks) {
            this.name = name;
            this.tracks = tracks;
        }
    }

    private void pickLxmcFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, "从洛雪 .lxmc 文件导入"), REQ_PICK_LXMC);
    }

    private void runLxmcImport(Uri uri) {
        Task.execute(() -> {
            List<LxmcPlaylist> lists;
            try {
                byte[] bytes = readAllCappedLxmc(uri);
                lists = parseLxmc(bytes);
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show(e.getMessage() == null ? "不是有效的 LX 歌单文件" : e.getMessage()));
                return;
            }
            // 落雪源支撑情况在子线程先算好（hasLxSupport 要枚举落雪插件，不上主线程）
            Set<String> unsupported = new java.util.LinkedHashSet<>();
            for (LxmcPlaylist p : lists) {
                for (MusicTrack t : p.tracks) {
                    String key = lxmcPlatformKey(t);
                    if (!key.isEmpty() && !unsupported.contains(key) && !PlaylistImporter.hasLxSupport(key)) {
                        unsupported.add(key);
                    }
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (unsupported.isEmpty()) {
                    importLxmcPlaylists(lists, 0, 0, 0, unsupported);
                    return;
                }
                // .lxmc 无支撑平台时导入前二次确认
                java.util.List<String> names = new java.util.ArrayList<>();
                for (String key : unsupported) names.add(LxPlatformSearch.platformName(key));
                new MusicGlassDialogBuilder(MusicPlaylistsActivity.this)
                        .setTitle("需要落雪脚本")
                        .setMessage("这些歌需要装支持" + android.text.TextUtils.join("、", names)
                                + "的落雪源才能播，当前没装，仍要导入吗？")
                        .setPositiveButton("仍要导入", (d, w) -> importLxmcPlaylists(lists, 0, 0, 0, unsupported))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    /** 从落雪歌 id 反推平台键：id 形如 lx:<平台键>:<原生 id> */
    private static String lxmcPlatformKey(MusicTrack t) {
        if (t == null || t.id == null) return "";
        String[] parts = t.id.split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    /** 逐个落库（MusicLibrary.importPlaylist 自带主线程回调，串行避免提示乱序） */
    private void importLxmcPlaylists(List<LxmcPlaylist> lists, int idx, int imported, int inserted, Set<String> unsupported) {
        if (idx >= lists.size()) {
            if (inserted <= 0) {
                Notify.show(R.string.music_import_err_empty);
                return;
            }
            loadSheets();
            String msg = "已导入 " + imported + " 个歌单，共 " + inserted + " 首";
            if (!unsupported.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (String key : unsupported) names.add(LxPlatformSearch.platformName(key));
                StringBuilder joined = new StringBuilder();
                for (String n : names) {
                    if (joined.length() > 0) joined.append("、");
                    joined.append(n);
                }
                msg += "，" + getString(R.string.music_import_need_lx, joined.toString());
            }
            Notify.show(msg);
            return;
        }
        LxmcPlaylist p = lists.get(idx);
        if (p.tracks.isEmpty()) {
            importLxmcPlaylists(lists, idx + 1, imported, inserted, unsupported);
            return;
        }
        MusicLibrary.importPlaylist(p.name, p.tracks, res ->
                importLxmcPlaylists(lists, idx + 1, imported + 1, inserted + res.inserted, unsupported));
    }

    /** 带上限的整读（.lxmc 原文件不大，64MB 封顶；解压后上限另在解析里按瓜子 64MB 卡） */
    private byte[] readAllCappedLxmc(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("读不到这个文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > 64 * 1024 * 1024) throw new Exception("文件太大（超 64MB），跳过");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * .lxmc 格式（瓜子 LxPlaylistParser.parse／decodeText／parseBackup／parsePart 原样）：
     * 文件体＝明文 UTF-8 JSON，或首两字节 0x1F8B 的 GZIP（解压后超 64MB 拒收）；
     * 根对象须有 data 对象；data.lists 在＝备份形（defaultList／loveList／tempList 三固定歌单＋
     * userList 自建歌单数组）；否则 data.list／name 在＝单歌单形；其余结构拒收。
     * 曲目字段：id／name／singer／source／interval（mm:ss）／meta（albumName／picUrl／平台原生 id）；
     * source 只认 wy／tx／kg／kw／mg（与星辰落雪平台键同串），soda 等无星辰源的按瓜子「无映射源丢弃」处理。
     */
    private static List<LxmcPlaylist> parseLxmc(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) throw new Exception("文件为空");
        String text;
        if (bytes.length >= 2 && (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF)) == 0x1F8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = gzip.read(buf)) > 0) {
                    total += n;
                    if (total > 64 * 1024 * 1024) throw new IllegalArgumentException("文件解压后过大，暂不支持");
                    bos.write(buf, 0, n);
                }
                text = new String(bos.toByteArray(), "UTF-8");
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("文件解压失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        } else {
            text = new String(bytes, "UTF-8");
        }
        if (text.trim().isEmpty()) throw new Exception("文件内容为空");
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            throw new Exception("不是有效的 LX 歌单文件（JSON 解析失败）");
        }
        if (!root.has("data") || !root.get("data").isJsonObject()) {
            throw new Exception("不是有效的 LX 歌单文件（缺少 data）");
        }
        JsonObject data = root.getAsJsonObject("data");
        List<LxmcPlaylist> out = new ArrayList<>();
        if (data.has("lists") && data.get("lists").isJsonObject()) {
            JsonObject lists = data.getAsJsonObject("lists");
            addLxmcList(out, lists, "defaultList", "试听列表");
            addLxmcList(out, lists, "loveList", "我的收藏");
            addLxmcList(out, lists, "tempList", "临时列表");
            if (lists.has("userList") && lists.get("userList").isJsonArray()) {
                for (JsonElement el : lists.getAsJsonArray("userList")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String name = lxmcStr(o, "name");
                    if (name.isEmpty()) name = lxmcStr(o, "id");
                    if (name.isEmpty()) name = "LX 自建歌单";
                    out.add(new LxmcPlaylist(name, lxmcTracks(lxmcArr(o, "list"))));
                }
            }
            return out;
        }
        if (data.has("list") || data.has("name")) {
            String name = lxmcStr(data, "name");
            if (name.isEmpty()) name = "LX 歌单";
            out.add(new LxmcPlaylist(name, lxmcTracks(lxmcArr(data, "list"))));
            return out;
        }
        throw new Exception("暂不支持的 LX 歌单文件结构");
    }

    private static void addLxmcList(List<LxmcPlaylist> out, JsonObject lists, String key, String name) {
        if (lists.has(key) && lists.get(key).isJsonArray()) {
            out.add(new LxmcPlaylist(name, lxmcTracks(lists.getAsJsonArray(key))));
        }
    }

    private static JsonArray lxmcArr(JsonObject o, String key) {
        if (o != null && o.has(key) && o.get(key).isJsonArray()) return o.getAsJsonArray(key);
        return new JsonArray();
    }

    private static List<MusicTrack> lxmcTracks(JsonArray arr) {
        List<MusicTrack> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            MusicTrack t = lxmcTrack(el.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    /**
     * 单曲映射（瓜子 toSearchSong／resolvePlatformId 原样）：
     * 名空或源无映射直接丢弃；平台原生 id 按源取首个非空候选，meta 里都没有时回退曲目 id
     * （带「平台键_」前缀的剥掉前缀）；_item＝meta 全字段＋按星辰搜索条目惯例补的规范 id 字段，
     * 取链时落雪脚本按各自习惯读，通用字段由 LxMusicSource.musicInfo 另补。
     */
    private static MusicTrack lxmcTrack(JsonObject o) {
        String name = lxmcStr(o, "name");
        if (name.isEmpty()) return null;
        String source = lxmcStr(o, "source");
        if (!"wy".equals(source) && !"tx".equals(source) && !"kg".equals(source)
                && !"kw".equals(source) && !"mg".equals(source)) {
            return null;
        }
        JsonObject meta = o.has("meta") && o.get("meta").isJsonObject()
                ? o.getAsJsonObject("meta") : new JsonObject();
        String rawId = lxmcPlatformId(source, meta);
        if (rawId.isEmpty()) {
            String id = lxmcStr(o, "id");
            if (id.startsWith(source + "_")) id = id.substring(source.length() + 1);
            rawId = id;
        }
        if (rawId.isEmpty()) return null;
        MusicTrack t = new MusicTrack();
        t.id = "lx:" + source + ":" + rawId;
        t.title = name;
        t.artist = lxmcStr(o, "singer");
        t.album = lxmcStr(meta, "albumName");
        t.cover = lxmcStr(meta, "picUrl");
        t.duration = lxmcIntervalMs(lxmcStr(o, "interval"));
        t.platform = LxMusicSource.PLATFORM;
        JsonObject item = meta.deepCopy();
        switch (source) {
            case "tx":
                if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                break;
            case "kw":
                if (lxmcStr(item, "rid").isEmpty()) item.addProperty("rid", rawId);
                if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                break;
            case "kg":
                if (lxmcStr(item, "hash").isEmpty()) item.addProperty("hash", rawId);
                if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                break;
            case "wy":
                if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                if (lxmcStr(item, "id").isEmpty()) item.addProperty("id", rawId);
                break;
            default: // mg
                if (rawId.contains(":")) {
                    String[] parts = rawId.split(":", 2);
                    if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", parts[0]);
                    if (lxmcStr(item, "copyrightId").isEmpty()) item.addProperty("copyrightId", parts[1]);
                    if (lxmcStr(item, "id").isEmpty()) item.addProperty("id", rawId);
                } else {
                    if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                    if (lxmcStr(item, "id").isEmpty()) item.addProperty("id", rawId);
                }
                break;
        }
        JsonObject wrap = new JsonObject();
        wrap.addProperty("_platform", source);
        wrap.add("_item", item);
        t.rawJson = wrap.toString();
        return t;
    }

    /** 平台原生 id 候选顺序（瓜子 resolvePlatformId 原样） */
    private static String lxmcPlatformId(String source, JsonObject meta) {
        switch (source) {
            case "tx": {
                String songId = lxmcStr(meta, "songId");
                // 瓜子：songId 含字母才进候选，全数字时跳过它
                boolean songIdOk = false;
                for (int i = 0; i < songId.length(); i++) {
                    if (Character.isLetter(songId.charAt(i))) {
                        songIdOk = true;
                        break;
                    }
                }
                return lxmcFirst(lxmcStr(meta, "songmid"), lxmcStr(meta, "mid"),
                        songIdOk ? songId : "", lxmcStr(meta, "strMediaMid"));
            }
            case "kw":
                return lxmcFirst(lxmcStr(meta, "rid"), lxmcStr(meta, "hash"), lxmcFirstQualityHash(meta));
            case "kg":
                return lxmcFirst(lxmcStr(meta, "hash"), lxmcFirstQualityHash(meta));
            case "wy":
                return lxmcFirst(lxmcStr(meta, "songId"), lxmcStr(meta, "id"));
            default: { // mg
                String contentId = lxmcStr(meta, "contentId");
                String copyrightId = lxmcStr(meta, "copyrightId");
                if (!contentId.isEmpty() && !copyrightId.isEmpty()) return contentId + ":" + copyrightId;
                return lxmcFirst(contentId, copyrightId);
            }
        }
    }

    /** 瓜子 firstQualityHash：qualitys 数组里优先 type＝128k 的 hash，否则首个非空 hash */
    private static String lxmcFirstQualityHash(JsonObject meta) {
        if (!meta.has("qualitys") || !meta.get("qualitys").isJsonArray()) return "";
        String first = "";
        for (JsonElement el : meta.getAsJsonArray("qualitys")) {
            if (!el.isJsonObject()) continue;
            JsonObject q = el.getAsJsonObject();
            String hash = lxmcStr(q, "hash");
            if (hash.isEmpty()) continue;
            if ("128k".equals(lxmcStr(q, "type"))) return hash;
            if (first.isEmpty()) first = hash;
        }
        return first;
    }

    /** 瓜子 parseIntervalMs：取首个 mm:ss 匹配，转毫秒；无匹配为 0 */
    private static long lxmcIntervalMs(String interval) {
        if (interval == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3}):(\\d{1,2})").matcher(interval);
        if (!m.find()) return 0;
        try {
            long min = Long.parseLong(m.group(1));
            long sec = Long.parseLong(m.group(2));
            return (min * 60 + sec) * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String lxmcFirst(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String lxmcStr(JsonObject o, String key) {
        try {
            if (o != null && o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    // ================= 列表 =================

    private void sheetLongPress(MusicPlaylist p) {
        List<String> opts = new ArrayList<>();
        opts.add("重命名");
        opts.add("编辑简介");
        opts.add("删除歌单");
        MusicOptionsDialog.show(this, p.getName(), opts, -1, idx -> {
            if (idx == 0) {
                inputPlaylistName(name -> MusicLibrary.renamePlaylist(p.getId(), name, () -> loadSheets()));
            } else if (idx == 1) {
                editSheetDesc(p);
            } else {
                MusicLibrary.deletePlaylist(p.getId(), () -> loadSheets());
            }
        });
    }

    /** 编辑歌单简介（扫描 M20：MusicStore.setSheetDesc 此前全树零调用、英雄卡简介永空，
     *  写入口就挂在歌单长按菜单、与重命名并列；清空即删简介，英雄卡回到不显示） */
    private void editSheetDesc(MusicPlaylist p) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(false);
        input.setText(MusicStore.getSheetDesc(p.getId()));
        input.setHint("歌单简介");
        input.setSelection(input.getText().length());
        new com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder(this)
                .setTitle("编辑简介")
                .setView(input)
                .setPositiveButton("保存", (d, w) ->
                        MusicStore.setSheetDesc(p.getId(), input.getText() == null ? "" : input.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private class PlaylistsAdapter extends RecyclerView.Adapter<PlaylistsAdapter.PlaylistHolder> {

        @NonNull
        @Override
        public PlaylistHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PlaylistHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_music_playlist_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PlaylistHolder h, int position) {
            bindPlaylistRow(h, position);
        }

        @Override
        public int getItemCount() {
            return cards.size();
        }

        private GradientDrawable circle(int color) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            return d;
        }

        /** 歌单马赛克格加载：纯铺满裁切，不套圆角（圆角由 coverBox 的 clipToOutline 一次裁）。 */
        private void loadMosaicCover(String url, ImageView view) {
            try {
                com.bumptech.glide.Glide.with(view).clear(view);
                com.bumptech.glide.Glide.with(view).load(url).centerCrop().into(view);
            } catch (Throwable ignored) {
            }
        }

        /** GZ 歌单行绑定（多选态下行点按变勾选，播放钮播整单，⋮走重命名／删除） */
        private void bindPlaylistRow(PlaylistHolder h, int position) {
            MusicPlaylist p = cards.get(position);
            boolean sel = sheetsSelected.contains(p.getId());
            h.name.setText(p.getName());
            String count = p.trackCount >= 0 ? p.trackCount + " 首" : "";
            h.status.setText(count + (sel ? " · 已选" : ""));
            // 第三行：最近更新时间（真数据；旧存量无值时整行 GONE，不编日期）
            if (h.meta != null) {
                long upd = p.getUpdateTime();
                if (upd > 0) {
                    h.meta.setVisibility(View.VISIBLE);
                    h.meta.setText("更新于 " + new java.text.SimpleDateFormat("yyyy-MM-dd",
                            java.util.Locale.getDefault()).format(new java.util.Date(upd)));
                } else {
                    h.meta.setVisibility(View.GONE);
                    h.meta.setText("");
                }
            }
            // 封面＝瓜子 CoverMosaic 口径：缓存有封面走马赛克（1 张铺满、2 张以上 2×2，
            // 缺格露底灰）；无封面（空歌单／缓存未到）回落原彩色圆盘图标。回收行先清在途加载，防旧图迟到。
            java.util.List<String> covers = sheetCovers.get(p.getId());
            boolean mosaic = covers != null && !covers.isEmpty();
            h.coverBox.setBackgroundResource(mosaic ? R.drawable.bg_playlist_mosaic : 0);
            h.coverBox.setClipToOutline(mosaic);
            if (!mosaic) {
                h.icon.setVisibility(View.VISIBLE);
                h.mosaicSingle.setVisibility(View.GONE);
                h.mosaicGrid.setVisibility(View.GONE);
                h.icon.setImageResource(R.drawable.ic_music_queue);
                h.icon.setBackground(circle(sel ? 0xFFFA243C : 0xFF3B82F6));
            } else {
                h.icon.setVisibility(View.GONE);
                if (covers.size() == 1) {
                    h.mosaicSingle.setVisibility(View.VISIBLE);
                    h.mosaicGrid.setVisibility(View.GONE);
                    loadMosaicCover(covers.get(0), h.mosaicSingle);
                } else {
                    h.mosaicSingle.setVisibility(View.GONE);
                    h.mosaicGrid.setVisibility(View.VISIBLE);
                    ImageView[] qs = {h.mosaic1, h.mosaic2, h.mosaic3, h.mosaic4};
                    for (int qi = 0; qi < 4; qi++) {
                        if (qi < covers.size()) loadMosaicCover(covers.get(qi), qs[qi]);
                        else {
                            try {
                                com.bumptech.glide.Glide.with(qs[qi]).clear(qs[qi]);
                            } catch (Throwable ignored) {
                            }
                            qs[qi].setImageDrawable(null);
                        }
                    }
                }
            }
            h.rowPlay.setBackground(circle(0xFFFA243C));
            h.rowPlay.setVisibility(sheetsMulti ? View.GONE : View.VISIBLE);
            h.rowMore.setVisibility(sheetsMulti ? View.GONE : View.VISIBLE);
            View.OnClickListener toggle = v -> {
                if (sheetsSelected.contains(p.getId())) sheetsSelected.remove(p.getId());
                else sheetsSelected.add(p.getId());
                tvPlayAll.setText("删除所选 (" + sheetsSelected.size() + ")");
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos);
            };
            h.itemView.setOnClickListener(sheetsMulti ? toggle
                    : v -> MusicPlaylistActivity.startSheet(MusicPlaylistsActivity.this, p.getId(), p.getName()));
            h.itemView.setOnLongClickListener(v -> {
                if (sheetsMulti) return false;
                sheetLongPress(p);
                return true;
            });
            h.rowPlay.setOnClickListener(v -> MusicLibrary.playlistTracks(p.getId(), list -> {
                if (list == null || list.isEmpty()) {
                    Notify.show(R.string.music_no_track);
                    return;
                }
                MusicController.get().play(new ArrayList<>(list), 0, p.getName(), "list", sheetRouteParams(p));
            }));
            h.rowMore.setOnClickListener(v -> sheetLongPress(p));
        }

        class PlaylistHolder extends RecyclerView.ViewHolder {
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
            final TextView meta;
            final ImageView rowPlay;
            final ImageView rowMore;

            PlaylistHolder(View v) {
                super(v);
                MusicGlassWire.glassifyTree(v); // 行一框：行内玻璃件同源接线（根或内卡按资源识别）
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
                meta = v.findViewById(R.id.meta);
                rowPlay = v.findViewById(R.id.rowPlay);
                rowMore = v.findViewById(R.id.rowMore);
            }
        }
    }
}
