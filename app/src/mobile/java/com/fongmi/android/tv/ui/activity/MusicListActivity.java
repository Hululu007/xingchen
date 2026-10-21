package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.LxPlatformSearch;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.api.music.PlaylistImporter;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicChart;
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
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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

    private String mode = "tracks";
    private String keyword = "";
    private String bigTitle = "";
    private String sitePlatform = "";
    private long playlistId = -1;

    // 搜索模式：源横排＋按源分开的搜索结果（切排只换显示、不重搜）
    private final List<MusicSite> searchTabs = new ArrayList<>();
    private final Map<String, List<MusicTrack>> siteResults = new ConcurrentHashMap<>();
    private final Set<String> siteLoading = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> siteFailed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private TabAdapter tabAdapter;
    private int selectedTab = -1;
    private int searchSeq = 0;

    public static void startSites(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "sites");
        activity.startActivity(i);
    }

    public static void startDownload(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "download");
        activity.startActivity(i);
    }

    public static void startDevice(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "device");
        activity.startActivity(i);
    }

    public static void startSearch(Activity activity, String keyword) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "search");
        i.putExtra("keyword", keyword == null ? "" : keyword);
        activity.startActivity(i);
    }

    public static void startSheets(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "sheets");
        activity.startActivity(i);
    }

    public static void startFavorite(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "favorite");
        activity.startActivity(i);
    }

    public static void startCharts(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "charts");
        activity.startActivity(i);
    }

    public static void startSite(Activity activity, MusicSite site) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "site");
        i.putExtra("sitePlatform", site.platform);
        i.putExtra("title", site.name);
        activity.startActivity(i);
    }

    public static void startSheet(Activity activity, long playlistId, String name) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "sheet");
        i.putExtra("playlistId", playlistId);
        i.putExtra("title", name);
        activity.startActivity(i);
    }

    private final MusicController.Listener controllerListener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
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
        if (mode == null) mode = "tracks";
        keyword = i.getStringExtra("keyword");
        if (keyword == null) keyword = "";
        sitePlatform = i.getStringExtra("sitePlatform");
        if (sitePlatform == null) sitePlatform = "";
        playlistId = i.getLongExtra("playlistId", -1);
        bigTitle = i.getStringExtra("title");
        if (bigTitle == null) bigTitle = "";

        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter = new ListAdapter());
        binding.tabRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.tabRow.setAdapter(tabAdapter = new TabAdapter());
        binding.btnPlayAll.setOnClickListener(v -> shufflePlay());
        binding.btnSort.setOnClickListener(v -> cycleSort());
        binding.btnSearch.setOnClickListener(v -> startSearch(this, ""));
        binding.btnDoSearch.setOnClickListener(v -> doSearch());
        binding.etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        MusicController.get().addListener(controllerListener);
        load();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    private boolean isCardMode() {
        return mode.equals("sheets") || mode.equals("charts") || mode.equals("sites");
    }

    private void load() {
        binding.tvTitle.setText(titleForMode());
        binding.toolbar.setVisibility(mode.equals("sites") ? View.GONE : View.VISIBLE);
        binding.searchBar.setVisibility(mode.equals("search") ? View.VISIBLE : View.GONE);
        binding.btnSearch.setVisibility((mode.equals("search") || mode.equals("sites")) ? View.GONE : View.VISIBLE);
        if (!keyword.isEmpty()) {
            binding.etKeyword.setText(keyword);
            doSearch();
            return;
        }
        switch (mode) {
            case "sheets":
                bigTitle = getString(R.string.music_playlist);
                loadSheets();
                break;
            case "charts":
                bigTitle = getString(R.string.music_charts);
                loadCharts();
                break;
            case "favorite":
                bigTitle = getString(R.string.music_favorite);
                loadFavorites();
                break;
            case "site":
                loadSiteTracks();
                break;
            case "sheet":
                loadSheetTracks();
                break;
            case "download":
                bigTitle = getString(R.string.music_downloaded);
                loadDownloads();
                break;
            case "device":
                loadDevice();
                break;
            case "sites":
                bigTitle = getString(R.string.music_plugin_manage);
                loadSites();
                break;
            case "search":
                bigTitle = getString(R.string.music_search);
                showTracks(new ArrayList<>());
                break;
            default:
                showTracks(new ArrayList<>());
                break;
        }
    }

    private String titleForMode() {
        switch (mode) {
            case "search":
                return getString(R.string.music_search);
            case "sheets":
                return getString(R.string.music_playlist);
            case "favorite":
                return getString(R.string.music_favorite);
            case "charts":
                return getString(R.string.music_charts);
            case "download":
                return getString(R.string.music_downloaded);
            case "device":
                return getString(R.string.music_device);
            case "sites":
                return getString(R.string.music_plugin_manage);
            default:
                return getString(R.string.music_playlist);
        }
    }

    /**
     * 搜索：横排只放支持搜歌的源（歌词型插件不进排），所有源各自并发开搜、
     * 结果按源分开存；点横排切源只换下面列表，已搜回的切回来不重搜。
     * 某个源报错只影响它自己那一格，其它源照常出结果。
     */
    private void doSearch() {
        keyword = binding.etKeyword.getText().toString().trim();
        if (keyword.isEmpty()) {
            Notify.show(R.string.music_search_hint);
            return;
        }
        bigTitle = keyword;
        final int seq = ++searchSeq;
        final String kw = keyword;
        searchTabs.clear();
        siteResults.clear();
        siteLoading.clear();
        siteFailed.clear();
        selectedTab = -1;
        tabAdapter.notifyDataSetChanged();
        binding.tabRow.setVisibility(View.GONE);
        binding.tvTitle.setText(titleForMode());
        tracks.clear();
        cards.clear();
        adapter.notifyDataSetChanged();
        showLoading();
        Task.execute(() -> {
            List<MusicSite> tabs = MusicRepository.searchableSites();
            // 先全部标"在搜"再刷 UI，否则首项的刷新可能跑在标记之前，误显示无结果
            for (MusicSite site : tabs) siteLoading.add(site.srcUrl);
            runOnUiThread(() -> {
                if (seq != searchSeq) return;
                searchTabs.addAll(tabs);
                binding.tabRow.setVisibility(tabs.isEmpty() ? View.GONE : View.VISIBLE);
                selectedTab = tabs.isEmpty() ? -1 : 0;
                tabAdapter.notifyDataSetChanged();
                if (tabs.isEmpty()) {
                    binding.tvBigTitle.setText(bigTitle);
                    binding.tvBigCount.setText("");
                    binding.empty.setVisibility(View.VISIBLE);
                    binding.empty.setText(R.string.music_no_result);
                    return;
                }
                showSiteResults(selectedTab);
            });
            // 每个源各自一趟并发搜，失败互相隔离
            for (MusicSite site : tabs) {
                final String key = site.srcUrl;
                Task.execute(() -> {
                    try {
                        List<MusicTrack> r = MusicRepository.searchSite(site, kw);
                        if (seq != searchSeq) return; // 迟到的旧搜索不许覆盖新结果
                        siteResults.put(key, r == null ? new ArrayList<>() : r);
                    } catch (Throwable t) {
                        if (seq == searchSeq) siteFailed.add(key);
                    } finally {
                        // 旧搜索的迟到回调不许动新搜索的状态
                        if (seq == searchSeq) {
                            siteLoading.remove(key);
                            runOnUiThread(() -> {
                                if (seq != searchSeq) return;
                                // 只有正看着的这一格需要立刻刷新，别的不动
                                if (selectedTab >= 0 && selectedTab < searchTabs.size()
                                        && key.equals(searchTabs.get(selectedTab).srcUrl)) {
                                    showSiteResults(selectedTab);
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void selectTab(int idx) {
        if (idx < 0 || idx >= searchTabs.size()) return;
        selectedTab = idx;
        tabAdapter.notifyDataSetChanged();
        binding.tabRow.smoothScrollToPosition(idx);
        showSiteResults(idx);
    }

    /** 把某一源的状态（还在搜/失败/无结果/有结果）显示到下面的列表区 */
    private void showSiteResults(int idx) {
        if (idx < 0 || idx >= searchTabs.size()) return;
        MusicSite site = searchTabs.get(idx);
        List<MusicTrack> r = siteResults.get(site.srcUrl);
        if (r != null) {
            // 拷一份给列表：showTracks 会就地排序，别把存好的结果弄乱
            showTracks(new ArrayList<>(r));
            return;
        }
        tracks.clear();
        cards.clear();
        adapter.notifyDataSetChanged();
        binding.tvBigTitle.setText(bigTitle);
        binding.tvBigCount.setText("");
        binding.empty.setVisibility(View.VISIBLE);
        if (siteFailed.contains(site.srcUrl)) {
            binding.empty.setText(site.name + " 搜索失败");
        } else if (siteLoading.contains(site.srcUrl)) {
            binding.empty.setText("正在加载…");
        } else {
            binding.empty.setText(R.string.music_no_result);
        }
    }

    private void loadSheets() {
        showLoading();
        MusicLibrary.playlists(list -> {
            cards.clear();
            cards.add(new NewPlaylistCard());
            cards.add(new ImportPlaylistCard());
            for (MusicPlaylist p : list) cards.add(p);
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                binding.tvBigTitle.setText(bigTitle);
                binding.tvBigCount.setText(list.size() + " 个歌单");
                binding.empty.setVisibility(View.GONE);
                // 异步补每首歌单的曲目数
                for (int idx = 0; idx < cards.size(); idx++) {
                    Object o = cards.get(idx);
                    if (o instanceof MusicPlaylist) {
                        int pos = idx;
                        MusicPlaylist p = (MusicPlaylist) o;
                        MusicLibrary.playlistTrackCount(p.getId(), n -> {
                            p.trackCount = n;
                            adapter.notifyItemChanged(pos);
                        });
                    }
                }
            });
        });
    }

    private void loadCharts() {
        showLoading();
        Task.execute(() -> {
            List<MusicSite> sites = MusicRepository.sites();
            List<MusicChart> charts = new ArrayList<>();
            for (MusicSite s : sites) charts.addAll(MusicRepository.charts(s));
            runOnUiThread(() -> {
                cards.clear();
                cards.addAll(charts);
                adapter.notifyDataSetChanged();
                binding.tvBigTitle.setText(bigTitle);
                binding.tvBigCount.setText(charts.size() + " 个榜单");
                binding.empty.setVisibility(charts.isEmpty() ? View.VISIBLE : View.GONE);
                if (charts.isEmpty()) binding.empty.setText(R.string.music_no_result);
            });
        });
    }

    private void loadFavorites() {
        showLoading();
        MusicLibrary.favorites(this::showTracks);
    }

    private void loadSiteTracks() {
        showLoading();
        Task.execute(() -> {
            MusicSite target = null;
            for (MusicSite s : MusicRepository.sites()) {
                if (s.platform.equals(sitePlatform)) {
                    target = s;
                    break;
                }
            }
            List<MusicTrack> r = target == null ? new ArrayList<>() : MusicRepository.tracks(target);
            runOnUiThread(() -> showTracks(r));
        });
    }

    private void loadSheetTracks() {
        showLoading();
        MusicLibrary.playlistTracks(playlistId, this::showTracks);
    }

    private void loadDownloads() {
        showLoading();
        MusicDownloadStore.all(this::showTracks);
    }

    // ================= 本地音乐（设备音频） =================

    private static final int REQ_AUDIO_PERMISSION = 4312;
    private static final int REQ_IMPORT_MUSIC = 4313;
    /** 自动弹授权框只弹一次：永久拒绝后系统会立即回 denied，不护栏就会回调套回调死循环 */
    private boolean permAutoAsked;

    private boolean deviceHeader() {
        return mode.equals("device");
    }

    private String audioPermission() {
        return android.os.Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_AUDIO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void loadDevice() {
        bigTitle = getString(R.string.music_device);
        binding.tvTitle.setText(titleForMode());
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, audioPermission())
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // 没权限：不白屏不崩，给提示行＋点一行重新授权，第一次直接弹系统授权框
            tracks.clear();
            cards.clear();
            adapter.notifyDataSetChanged();
            binding.tvBigTitle.setText(bigTitle);
            binding.tvBigCount.setText("");
            binding.empty.setVisibility(View.VISIBLE);
            binding.empty.setText(R.string.music_device_need_permission);
            binding.empty.setOnClickListener(v ->
                    requestPermissions(new String[]{audioPermission()}, REQ_AUDIO_PERMISSION));
            if (!permAutoAsked) {
                permAutoAsked = true;
                requestPermissions(new String[]{audioPermission()}, REQ_AUDIO_PERMISSION);
            }
            return;
        }
        binding.empty.setOnClickListener(null);
        showLoading();
        Task.execute(() -> {
            List<MusicTrack> list = com.fongmi.android.tv.music.DeviceMusicStore.scan(this);
            runOnUiThread(() -> showTracks(list));
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION && deviceHeader()) {
            // 无论授权与否都刷新：给了就加载，拒了就留提示行（不许静默）
            loadDevice();
        }
    }

    /** 导入加密歌曲：系统文件选择器多选（QQ 的 mflac 系、酷狗 kgm 系），解密落 music_library/ */
    private void pickImportFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, getString(R.string.music_import_encrypted)), REQ_IMPORT_MUSIC);
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        }
        String p = uri.getPath();
        if (p == null) return "未知文件";
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private void runImport(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        Notify.show(R.string.music_device_importing);
        Task.execute(() -> {
            java.io.File dir = com.fongmi.android.tv.music.DeviceMusicStore.libraryDir(this);
            dir.mkdirs();
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (Uri u : uris) {
                String name = displayName(u);
                try {
                    byte[] bytes = readAllCapped(u, 200L * 1024 * 1024);
                    com.fongmi.android.tv.music.MusicDecrypt.Result r =
                            com.fongmi.android.tv.music.MusicDecrypt.decrypt(name, bytes);
                    java.io.File out = uniqueFile(dir, safeName(name) + "." + r.ext);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        fos.write(r.data);
                    }
                    ok++;
                } catch (Exception e) {
                    failed.add(name + "：" + (e.getMessage() == null ? "失败" : e.getMessage()));
                }
            }
            final int fOk = ok;
            final List<String> fFailed = failed;
            runOnUiThread(() -> {
                if (!fFailed.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    if (fOk > 0) sb.append("成功 ").append(fOk).append(" 首\n\n");
                    for (String f : fFailed) sb.append(f).append("\n");
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.music_import_encrypted)
                            .setMessage(sb.toString().trim())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    Notify.show("导入成功 " + fOk + " 首");
                }
                loadDevice();
            });
        });
    }

    private static String safeName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return base.isEmpty() ? "imported" : base;
    }

    private static java.io.File uniqueFile(java.io.File dir, String name) {
        java.io.File f = new java.io.File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            java.io.File c = new java.io.File(dir, base + "_" + i + ext);
            if (!c.exists()) return c;
        }
    }

    private void bindImportRow(ListAdapter.CardHolder h) {
        h.name.setText(R.string.music_import_encrypted);
        h.status.setText("");
        h.icon.setImageResource(R.drawable.ic_music_download);
        h.icon.setBackground(circle(0xFF06B6D4));
        h.icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        h.itemView.setOnClickListener(v -> pickImportFiles());
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 插件管理：全量列表（停用的也得在，不然关了就找不回来）＋添加卡＋开关/菜单 */
    private void loadSites() {
        showLoading();
        Task.execute(() -> {
            List<MusicSite> sites = MusicSourceManager.get().listSites();
            runOnUiThread(() -> {
                cards.clear();
                cards.add(new NewSiteCard());
                cards.addAll(sites);
                adapter.notifyDataSetChanged();
                binding.tvBigTitle.setText(bigTitle);
                binding.tvBigCount.setText(sites.size() + " " + getString(R.string.music_plugins));
                binding.empty.setVisibility(sites.isEmpty() ? View.VISIBLE : View.GONE);
                if (sites.isEmpty()) binding.empty.setText(R.string.music_plugin_none);
            });
        });
    }

    /** 添加插件二选一：在线地址（贴 URL 下载安装）／本地文件（系统文件选择器选 .js） */
    private void showAddPluginSheet() {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.music_plugin_online));
        opts.add(getString(R.string.music_plugin_local));
        MusicOptionsDialog.show(this, getString(R.string.music_plugin_add), opts, -1, idx -> {
            if (idx == 0) inputSiteUrl();
            else if (idx == 1) pickLocalFile();
        });
    }

    private static final int REQ_PICK_PLUGIN = 4213;
    private static final int REQ_PICK_LXMC = 4214;

    private void pickLocalFile() {
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
        if (requestCode == REQ_IMPORT_MUSIC) {
            if (resultCode == RESULT_OK && data != null) runImport(data);
            return;
        }
        if (requestCode == REQ_PICK_LXMC) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                runLxmcImport(data.getData());
            }
            return;
        }
        if (requestCode != REQ_PICK_PLUGIN || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Task.execute(() -> {
            try {
                byte[] bytes = readAll(uri);
                runOnUiThread(() -> confirmPluginImport(() -> Task.execute(() -> {
                    try {
                        MusicSourceManager.get().addLocalSite(bytes);
                        runOnUiThread(() -> {
                            Notify.show(R.string.music_site_added);
                            loadSites();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Notify.show(e.getMessage() == null
                                ? getString(R.string.music_plugin_add_failed) : e.getMessage()));
                    }
                })));
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show(e.getMessage() == null
                        ? getString(R.string.music_plugin_add_failed) : e.getMessage()));
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

    /** 带上限的整读（加密导入用）：边读边判，超上限立刻放弃，不许把大文件全灌进内存 */
    private byte[] readAllCapped(Uri uri, long max) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("读不到这个文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > max) throw new Exception("文件太大（超 200MB），跳过");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** 插件行 ⋮ 菜单：在线装的才有「更新」；删除连插件文件一起删 */
    private void pluginMenu(MusicSite site) {
        boolean online = !MusicSourceManager.isLocal(site);
        List<String> opts = new ArrayList<>();
        if (online) opts.add(getString(R.string.music_plugin_update));
        opts.add(getString(R.string.music_delete));
        MusicOptionsDialog.show(this, site.name == null ? "" : site.name, opts, -1, idx -> {
            if (online && idx == 0) {
                Task.execute(() -> {
                    String v = MusicSourceManager.get().checkUpdate(site);
                    runOnUiThread(() -> {
                        if (v == null) {
                            Notify.show(R.string.music_plugin_latest);
                            return;
                        }
                        // 瓜子更新弹窗：标题「音源插件发现新版本」＋「立即更新」／「忽略」
                        new MaterialAlertDialogBuilder(MusicListActivity.this)
                                .setTitle("音源插件发现新版本")
                                .setMessage((site.name == null ? "" : site.name) + "：" + v)
                                .setPositiveButton("立即更新", (d, w) -> {
                                    MusicSourceManager.get().applyUpdate(site, v);
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
            } else {
                // 瓜子移除弹窗（仅本机文案；云端备份选项属排除项，不出）
                new MaterialAlertDialogBuilder(this)
                        .setTitle("移除「" + (site.name == null ? "" : site.name) + "」")
                        .setMessage("该插件只存在于本机，移除后需要重新导入脚本才能恢复。")
                        .setPositiveButton("移除", (d, w) -> Task.execute(() -> {
                            MusicSourceManager.get().removeSite(site.srcUrl);
                            runOnUiThread(() -> {
                                Notify.show(R.string.music_deleted);
                                loadSites();
                            });
                        }))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
    }

    /**
     * 插件导入确认（瓜子「第三方插件导入确认」：标题＋导语照瓜子；风险条目前两条照瓜子，
     * 第三条「先试运行一次、确认能提供音源才会保留」是星辰安装链本就有的行为（probe 先行再落盘），
     * 故写成通用表述；瓜子第三条自家声明「不发布、不内置、不推荐任何第三方插件」与星辰预置口径
     * 相反，未照抄）。
     */
    private void confirmPluginImport(Runnable onAgree) {
        String msg = "请仔细阅读以下提示，点击「同意并导入」即代表你已同意\n\n"
                + "1. 该脚本来自第三方，非星辰提供。请自行判断其用途与潜在风险（内容、播放链接等），相关责任由脚本作者和使用者自行承担；\n"
                + "2. 脚本会在本应用内执行代码，并可发起网络请求，请仅导入你信任的来源；\n"
                + "3. 安装前会先试运行一次，确认能正常提供音源才会保留。";
        new MaterialAlertDialogBuilder(this)
                .setTitle("第三方插件导入确认")
                .setMessage(msg)
                .setPositiveButton("同意并导入", (d, w) -> onAgree.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void inputSiteUrl() {
        EditText et = new EditText(this);
        et.setHint(R.string.music_site_url_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_plugin_add)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    confirmPluginImport(() -> Task.execute(() -> {
                        try {
                            MusicSourceManager.get().addSite(url);
                            runOnUiThread(() -> {
                                Notify.show(R.string.music_site_added);
                                loadSites();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Notify.show(e.getMessage() == null ? "添加失败" : e.getMessage()));
                        }
                    }));
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
            binding.empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
            if (tracks.isEmpty() && !mode.equals("search")) binding.empty.setText(R.string.music_no_track);
            else if (tracks.isEmpty()) binding.empty.setText(R.string.music_no_result);
        });
    }

    private void sortTracks(List<MusicTrack> list) {
        int sort = MusicStore.getListSort();
        if (sort == 1) Collections.sort(list, Comparator.comparing(t -> t.title == null ? "" : t.title));
        else if (sort == 2) Collections.sort(list, Comparator.comparing(t -> t.artist == null ? "" : t.artist));
        else if (sort == 3) Collections.sort(list, Comparator.comparingLong(t -> t.duration));
    }

    private void cycleSort() {
        int s = (MusicStore.getListSort() + 1) % 4;
        MusicStore.setListSort(s);
        String[] names = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        Notify.show(names[s]);
        if (isCardMode()) load();
        else showTracks(new ArrayList<>(tracks));
    }

    private void shufflePlay() {
        if (tracks.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        MusicController.get().play(shuffled, 0);
        MusicPlayerActivity.start(this);
    }

    private void playTrack(int position) {
        MusicController.get().play(new ArrayList<>(tracks), position);
        MusicPlayerActivity.start(this);
    }

    private void refreshCurrentRow() {
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private static class NewPlaylistCard {
    }

    private static class ImportPlaylistCard {
    }

    private static class NewSiteCard {
    }

    // ================= ⋮ 菜单 =================

    private void onMore(MusicTrack track, int position) {
        FragmentActivity act = this;
        boolean local = MusicDownloadStore.isLocal(track);
        boolean device = com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform);
        List<String> opts = new ArrayList<>();
        opts.add("播放");
        opts.add(getString(R.string.music_play_next));
        opts.add(getString(R.string.music_add_to_playlist));
        MusicLibrary.isFavorite(track.id, fav -> {
            opts.add(fav ? getString(R.string.music_unfavorite) : getString(R.string.music_favorite));
            int downloadIdx = -1;
            int removeIdx = -1;
            int deleteLocalIdx = -1;
            if (local) {
                // 已下载歌曲：不给"下载"（已是本地），给"删除本地文件"
                deleteLocalIdx = opts.size();
                opts.add(getString(R.string.music_download_delete));
            } else if (device) {
                // 设备歌曲：本来就在手机里，不给"下载"
                if (mode.equals("sheet")) {
                    removeIdx = opts.size();
                    opts.add(getString(R.string.music_remove));
                }
            } else {
                if (mode.equals("sheet")) {
                    removeIdx = opts.size();
                    opts.add(getString(R.string.music_remove));
                }
                downloadIdx = opts.size();
                opts.add(getString(R.string.music_download));
            }
            int dIdx = downloadIdx;
            int rIdx = removeIdx;
            int delIdx = deleteLocalIdx;
            MusicOptionsDialog.show(act, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) pickPlaylist(track);
                else if (idx == 3) toggleFavorite(track, opts.get(3));
                else if (idx == dIdx) {
                    if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
                        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                                MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
                        List<String> qopts = new ArrayList<>();
                        for (int v : values) qopts.add(MusicStore.qualityName(v));
                        int fixed = MusicStore.getDownloadQualityFixed();
                        int checked = 1;
                        for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
                        MusicOptionsDialog.show(act, getString(R.string.music_set_download_quality), qopts, checked,
                                qidx -> MusicDownloadStore.downloadWithQuality(track, values[qidx]));
                    } else {
                        MusicDownloadStore.download(track);
                    }
                }
                else if (idx == rIdx) removeFromSheet(track);
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
                            if (mode.equals("download")) loadDownloads();
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleFavorite(MusicTrack track, String label) {
        boolean toFav = label.equals(getString(R.string.music_favorite));
        MusicLibrary.setFavorite(track, toFav, () ->
                Notify.show(toFav ? R.string.music_favorited : R.string.music_unfavorited));
        if (mode.equals("favorite") && !toFav) loadFavorites();
    }

    private void removeFromSheet(MusicTrack track) {
        Task.execute(() -> {
            // 按 trackId 删
            try {
                com.fongmi.android.tv.db.AppDatabase.get().getMusicPlaylistItemDao()
                        .delete(playlistId, track.id);
            } catch (Throwable ignored) {
            }
            runOnUiThread(this::loadSheetTracks);
        });
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
                        if (mode.equals("sheets")) loadSheets();
                    }));
                }
            });
        });
    }

    private void inputPlaylistName(NameInput cb) {
        EditText et = new EditText(this);
        et.setHint(R.string.music_playlist_name_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ================= 导入歌单 =================

    /** 粘贴弹窗：点导入后不关窗，后台拉取，失败原因就显示在输入框下方 */
    private void showImportPlaylistDialog() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * density), pad, 0);
        EditText et = new EditText(this);
        et.setHint(R.string.music_import_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setMinLines(3);
        et.setGravity(android.view.Gravity.TOP);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView status = new TextView(this);
        status.setTextSize(13);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (10 * density);
        box.addView(status, slp);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_import_playlist)
                .setView(box)
                .setPositiveButton(R.string.music_import_action, null)
                .setNeutralButton("从洛雪 .lxmc 文件导入", (d, w) -> pickLxmcFile())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> runPlaylistImport(dialog, et, status)));
        dialog.show();
    }

    private void runPlaylistImport(AlertDialog dialog, EditText et, TextView status) {
        String text = et.getText().toString();
        android.widget.Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        android.widget.Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        setImportBusy(et, status, ok, cancel, true);
        Task.execute(() -> {
            PlaylistImporter.Result result = null;
            PlaylistImporter.Fail fail = null;
            boolean lxOk = false;
            try {
                result = PlaylistImporter.importFromText(text);
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
                    setImportBusy(et, status, ok, cancel, false);
                    status.setTextColor(0xFFFF8A80);
                    status.setText(importErrorText(f == null ? PlaylistImporter.Fail.NETWORK : f));
                    return;
                }
                MusicLibrary.importPlaylist(r.name, r.tracks, res -> {
                    if (res.inserted <= 0) {
                        // 一首都没落库（全是重复歌）不留空歌单，按空单报
                        if (res.playlist != null) MusicLibrary.deletePlaylist(res.playlist.getId(), null);
                        setImportBusy(et, status, ok, cancel, false);
                        status.setTextColor(0xFFFF8A80);
                        status.setText(R.string.music_import_err_empty);
                        return;
                    }
                    dialog.dismiss();
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
            });
        });
    }

    private void setImportBusy(EditText et, TextView status, android.widget.Button ok,
                               android.widget.Button cancel, boolean busy) {
        et.setEnabled(!busy);
        if (ok != null) ok.setEnabled(!busy);
        if (cancel != null) cancel.setEnabled(!busy);
        if (busy) {
            status.setVisibility(View.VISIBLE);
            status.setTextColor(0xFF9AA7B8);
            status.setText(R.string.music_importing);
        }
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
            runOnUiThread(() -> importLxmcPlaylists(lists, 0, 0, 0, unsupported));
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

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_TRACK = 0;
        private static final int TYPE_CARD = 1;
        private static final int TYPE_PLUGIN = 2;
        private static final int TYPE_HEADER = 3;

        /** 本地音乐页首行是「导入加密歌曲」头，歌曲行整体下移一位 */
        private int headerCount() {
            return deviceHeader() ? 1 : 0;
        }

        @Override
        public int getItemViewType(int position) {
            if (deviceHeader() && position == 0) return TYPE_HEADER;
            if (isCardMode()) {
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
            if (deviceHeader() && position == 0) {
                bindImportRow((CardHolder) h);
                return;
            }
            if (h instanceof PluginHolder) bindPlugin((PluginHolder) h, position);
            else if (h instanceof CardHolder) bindCard((CardHolder) h, position);
            else bindTrack((TrackHolder) h, position - headerCount());
        }

        /** 插件行：名＋版本号小字＋格式标签；右侧金开关（真接线启停）＋⋮菜单 */
        private void bindPlugin(PluginHolder h, int position) {
            MusicSite s = (MusicSite) cards.get(position);
            h.name.setText(s.name == null ? "" : s.name);
            boolean lx = MusicSourceManager.isLx(s);
            h.formatTag.setText(lx ? "落雪" : "FreeJS");
            h.formatTag.setTextColor(lx ? 0xFFFA243C : 0xFF9AA7B8);
            if (s.version != null && !s.version.isEmpty()) {
                h.version.setVisibility(View.VISIBLE);
                h.version.setText(s.version);
            } else {
                h.version.setVisibility(View.GONE);
            }
            h.pluginSwitch.setOnCheckedChangeListener(null);
            h.pluginSwitch.setChecked(s.enabled);
            h.pluginSwitch.setOnCheckedChangeListener((v, checked) ->
                    Task.execute(() -> MusicSourceManager.get().setEnabled(s.srcUrl, checked)));
            View.OnClickListener menu = v -> pluginMenu(s);
            h.pluginMore.setOnClickListener(menu);
            h.itemView.setOnClickListener(menu);
        }

        private void bindCard(CardHolder h, int position) {
            Object o = cards.get(position);
            if (o instanceof NewPlaylistCard) {
                h.name.setText(R.string.music_new_playlist);
                h.status.setText("");
                h.icon.setImageResource(R.drawable.ic_music_plus);
                h.icon.setBackground(circle(0xFF22C55E));
                h.itemView.setOnClickListener(v -> inputPlaylistName(name ->
                        MusicLibrary.createPlaylist(name, p -> loadSheets())));
            } else if (o instanceof ImportPlaylistCard) {
                h.name.setText(R.string.music_import_playlist);
                h.status.setText("");
                h.icon.setImageResource(R.drawable.ic_music_download);
                h.icon.setBackground(circle(0xFFFA243C));
                h.itemView.setOnClickListener(v -> showImportPlaylistDialog());
            } else if (o instanceof MusicPlaylist) {
                MusicPlaylist p = (MusicPlaylist) o;
                h.name.setText(p.getName());
                h.status.setText(p.trackCount >= 0 ? p.trackCount + " 首" : "");
                h.icon.setImageResource(R.drawable.ic_music_queue);
                h.icon.setBackground(circle(0xFF3B82F6));
                h.itemView.setOnClickListener(v -> startSheet(MusicListActivity.this, p.getId(), p.getName()));
                h.itemView.setOnLongClickListener(v -> {
                    sheetLongPress(p);
                    return true;
                });
            } else if (o instanceof MusicChart) {
                MusicChart c = (MusicChart) o;
                h.name.setText(c.name);
                h.status.setText("");
                h.icon.setImageResource(R.drawable.ic_music_eq);
                h.icon.setBackground(circle(0xFFA855F7));
                h.itemView.setOnClickListener(v -> openChart(c));
            } else if (o instanceof NewSiteCard) {
                h.name.setText(R.string.music_plugin_add);
                h.status.setText("");
                h.icon.setImageResource(R.drawable.ic_music_plus);
                h.icon.setBackground(circle(0xFF22C55E));
                h.itemView.setOnClickListener(v -> showAddPluginSheet());
            }
            h.icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        }

        private void openChart(MusicChart chart) {
            bigTitle = chart.name;
            mode = "tracks";
            showLoading();
            Task.execute(() -> {
                List<MusicTrack> r = MusicRepository.chartTracks(chart);
                runOnUiThread(() -> showTracks(r));
            });
        }

        private void sheetLongPress(MusicPlaylist p) {
            List<String> opts = new ArrayList<>();
            opts.add("重命名");
            opts.add("删除歌单");
            MusicOptionsDialog.show(MusicListActivity.this, p.getName(), opts, -1, idx -> {
                if (idx == 0) {
                    inputPlaylistName(name -> MusicLibrary.renamePlaylist(p.getId(), name, () -> loadSheets()));
                } else {
                    MusicLibrary.deletePlaylist(p.getId(), () -> loadSheets());
                }
            });
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
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFFFFFFFF);
            h.title.getPaint().setFakeBoldText(isCurrent);
            h.itemView.setBackgroundResource(isCurrent ? R.drawable.card_music_current : 0);
            if (t.qualityTag != null && !t.qualityTag.isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(t.qualityTag);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            h.artist.setText(t.displayArtist());
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
                ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
            }
            h.itemView.setOnClickListener(v -> playTrack(position));
            h.more.setOnClickListener(v -> onMore(t, position));
        }

        private String fmt(long ms) {
            if (ms <= 0) return "--:--";
            long s = ms / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }

        @Override
        public int getItemCount() {
            return isCardMode() ? cards.size() : tracks.size() + headerCount();
        }

        class TrackHolder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView badge;
            final TextView artist;
            final TextView duration;
            final ImageView more;

            TrackHolder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                badge = v.findViewById(R.id.badge);
                artist = v.findViewById(R.id.artist);
                duration = v.findViewById(R.id.duration);
                more = v.findViewById(R.id.more);
            }
        }

        class CardHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView status;

            CardHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                status = v.findViewById(R.id.status);
            }
        }

        class PluginHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView formatTag;
            final TextView version;
            final SwitchCompat pluginSwitch;
            final ImageView pluginMore;

            PluginHolder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                formatTag = v.findViewById(R.id.formatTag);
                version = v.findViewById(R.id.version);
                pluginSwitch = v.findViewById(R.id.pluginSwitch);
                pluginMore = v.findViewById(R.id.pluginMore);
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

    /** 搜索页源横排：选中红字＋红下划线，未选中灰字 */
    private class TabAdapter extends RecyclerView.Adapter<TabAdapter.TabHolder> {

        @NonNull
        @Override
        public TabHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_music_search_tab, parent, false);
            return new TabHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull TabHolder h, int position) {
            MusicSite s = searchTabs.get(position);
            boolean sel = position == selectedTab;
            h.name.setText(s.name);
            h.name.setTextColor(sel ? 0xFFFA243C : 0xFF9AA7B8);
            h.name.getPaint().setFakeBoldText(sel);
            h.bar.setBackgroundColor(sel ? 0xFFFA243C : 0x00000000);
            h.itemView.setOnClickListener(v -> selectTab(h.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return searchTabs.size();
        }

        class TabHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final View bar;

            TabHolder(View v) {
                super(v);
                name = v.findViewById(R.id.tabName);
                bar = v.findViewById(R.id.tabBar);
            }
        }
    }

    /** 单参回调 */
    private interface NameInput {
        void onResult(String name);
    }
}
