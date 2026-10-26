package com.fongmi.android.tv.ui.activity;

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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.XcFadeTransition;
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地音乐独立页（拆页 p1）：设备媒体库＋导入目录的本地歌曲平铺一视图
 * （分类排钮已按令整排摘除，分组卡视图随之清净）＋排序分组＋页内搜索＋扫描设置
 * （过滤／文件夹排除／已移除恢复）＋导入加密歌曲；行点击走 MusicController 播放，
 * 队列 route=list、参数 mode=device，队列面板来源跳转落回本页。
 * 顶栏只留标题，无返回箭头，走系统返回。
 */
public class MusicDeviceActivity extends BaseActivity {

    private static final int REQ_AUDIO_PERMISSION = 4312;
    private static final int REQ_IMPORT_MUSIC = 4313;

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
    private TextView tvBigTitle;
    private TextView tvBigCount;
    private EditText etDeviceSearch;
    private TextView tvDeviceScanSettings;
    private TextView tvDeviceRemoved;
    private View btnPlayAll;
    private View btnPlaySeq;
    private ImageView btnSort;
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
    private DeviceAdapter adapter;

    private int deviceSort = 0; // 0 默认 1 最近添加 2 时长 3 文件大小
    private boolean deviceAsc = false; // 默认降序；升降序在分组区块头右侧切换
    private String deviceSearchKw = "";
    private boolean deviceSearchWired;
    private final List<MusicTrack> deviceAll = new ArrayList<>();
    private final List<Integer> deviceSectionPos = new ArrayList<>();
    private final List<String> deviceSectionTitles = new ArrayList<>();
    private final List<Integer> deviceSectionCounts = new ArrayList<>();

    private String bigTitle = "";
    private boolean permAutoAsked;
    private boolean resumedOnce;
    private android.os.Parcelable pendingListState;
    private String lastCurrentId;

    public static void startDevice(Activity activity) {
        activity.startActivity(new Intent(activity, MusicDeviceActivity.class));
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
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_device, null);
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
            deviceSort = savedInstanceState.getInt("ml_deviceSort", deviceSort);
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        // 顶栏小标题、右上角搜索钮、分类排钮（歌曲/专辑/歌手/文件夹）与来源筛选入口已按令整排摘除，
        // 本页恒平铺全部来源一视图：视图／来源状态不再存取，分组卡死代码已一并清净。
        tvBigTitle = findViewById(R.id.tvBigTitle);
        tvBigCount = findViewById(R.id.tvBigCount);
        etDeviceSearch = findViewById(R.id.etDeviceSearch);
        tvDeviceScanSettings = findViewById(R.id.tvDeviceScanSettings);
        tvDeviceRemoved = findViewById(R.id.tvDeviceRemoved);
        btnPlayAll = findViewById(R.id.btnPlayAll);
        btnPlaySeq = findViewById(R.id.btnPlaySeq);
        btnSort = findViewById(R.id.btnSort);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);

        btnPlayAll.setOnClickListener(v -> shufflePlay());
        btnPlaySeq.setOnClickListener(v -> playAllSequential());
        btnSort.setOnClickListener(v -> showDeviceSortDialog());
        if (tvDeviceScanSettings != null) tvDeviceScanSettings.setOnClickListener(v -> showDeviceScanDialog());
        if (tvDeviceRemoved != null) tvDeviceRemoved.setOnClickListener(v -> showDeviceRemovedDialog());
        if (!deviceSearchWired) {
            deviceSearchWired = true;
            etDeviceSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    deviceSearchKw = s == null ? "" : s.toString().trim();
                    refreshDeviceDisplay();
                }
            });
        }
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new DeviceAdapter());
        MusicController.get().addListener(controllerListener);
        loadDevice();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putInt("ml_deviceSort", deviceSort);
            if (recycler != null && recycler.getLayoutManager() != null) {
                outState.putParcelable("ml_listState", recycler.getLayoutManager().onSaveInstanceState());
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
        loadDevice();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION) loadDevice();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_MUSIC && resultCode == RESULT_OK && data != null) runImport(data);
    }

    // ================= 数据加载 =================

    private String audioPermission() {
        return android.os.Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_AUDIO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** 还能弹系统授权框就弹；已被永久拒绝就跳本应用详情设置页一键去开，不许点了没反应。 */
    private void requestAudioPermissionOrSettings() {
        boolean canAsk = !permAutoAsked
                || androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, audioPermission());
        if (canAsk) {
            requestPermissions(new String[]{audioPermission()}, REQ_AUDIO_PERMISSION);
            return;
        }
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable ignored) {
            try {
                requestPermissions(new String[]{audioPermission()}, REQ_AUDIO_PERMISSION);
            } catch (Throwable ignored2) {
            }
        }
    }

    private void loadDevice() {
        bigTitle = getString(R.string.music_device);
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, audioPermission())
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            tracks.clear();
            adapter.notifyDataSetChanged();
            tvBigTitle.setText(bigTitle);
            tvBigCount.setText("");
            XcFadeTransition.fadeIn(empty);
            empty.setText(R.string.music_device_need_permission);
            empty.setOnClickListener(v -> requestAudioPermissionOrSettings());
            if (!permAutoAsked) {
                permAutoAsked = true;
                requestPermissions(new String[]{audioPermission()}, REQ_AUDIO_PERMISSION);
            }
            return;
        }
        empty.setOnClickListener(null);
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
        Task.execute(() -> {
            List<MusicTrack> list = com.fongmi.android.tv.music.DeviceMusicStore.scan(this);
            runOnUiThread(() -> {
                java.util.Set<String> ignored = MusicStore.loadIgnoredDeviceIds();
                deviceAll.clear();
                for (MusicTrack t : list) {
                    if (t != null && t.id != null && !ignored.contains(t.id)) deviceAll.add(t);
                }
                refreshDeviceDisplay();
            });
        });
    }

    // ================= 排序／扫描 =================

    private void showDeviceSortDialog() {
        String[] items = {"默认", "最近添加", "时长", "文件大小"};
        new MusicGlassDialogBuilder(this)
                .setTitle("选择排序方式")
                .setMessage("切换后列表将按该方式分组展示，升/降序在区块头右侧切换\n\n"
                        + "默认：按添加时间新→旧平铺\n"
                        + "最近添加：按 今天/昨天/本周/更早 分组\n"
                        + "时长：按 短/中/长/超长 分组\n"
                        + "文件大小：按 小/中/大/超大 分组")
                .setSingleChoiceItems(items, deviceSort, (d, which) -> {
                    deviceSort = which;
                    d.dismiss();
                    refreshDeviceDisplay();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeviceScanDialog() {
        long min = MusicStore.getDeviceMinDuration();
        String filterLabel = min == 30000L ? "30 秒" : min == 60000L ? "1 分钟" : min == 120000L ? "2 分钟" : "不过滤";
        int excludedN = MusicStore.getDeviceExcludedFolders().size();
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.wave2_device_scan_filter) + "：当前" + filterLabel);
        opts.add(getString(R.string.wave2_device_exclude) + (excludedN > 0 ? "（" + excludedN + " 个）" : ""));
        MusicOptionsDialog.show(this, getString(R.string.wave2_device_scan_settings), opts, -1, idx -> {
            if (idx == 0) showDeviceFilterDialog();
            else if (idx == 1) showDeviceExcludeDialog();
        });
    }

    private void showDeviceFilterDialog() {
        String[] items = {"不过滤", "30 秒", "1 分钟", "2 分钟"};
        long[] values = {0L, 30000L, 60000L, 120000L};
        long cur = MusicStore.getDeviceMinDuration();
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) checked = i;
        MusicOptionsDialog.show(this, getString(R.string.wave2_device_scan_filter),
                java.util.Arrays.asList(items), checked, idx -> {
                    MusicStore.setDeviceMinDuration(values[idx]);
                    loadDevice();
                });
    }

    private void showDeviceExcludeDialog() {
        // 数据源必须是真全集：deviceAll 是扫描已剔除排除目录后的结果，拿它列文件夹
        // 时被完全排除的文件夹根本不在表里、弹窗里勾不回来（排除只有进没有出）。
        // 改走 scanAll（不应用排除／时长过滤）现扫一遍全集再列目录。
        Task.execute(() -> {
            List<MusicTrack> all = com.fongmi.android.tv.music.DeviceMusicStore.scanAll(this);
            java.util.TreeSet<String> folders = new java.util.TreeSet<>();
            for (MusicTrack t : all) {
                if (t == null || t.localPath == null || t.localPath.isEmpty()) continue;
                String dir = new java.io.File(t.localPath).getParent();
                if (dir != null && !dir.isEmpty()) folders.add(dir);
            }
            List<String> list = new ArrayList<>(folders);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (list.isEmpty()) {
                    Notify.show("暂无文件夹");
                    return;
                }
                java.util.Set<String> excluded = new java.util.HashSet<>(MusicStore.getDeviceExcludedFolders());
                boolean[] checked = new boolean[list.size()];
                for (int i = 0; i < list.size(); i++) checked[i] = excluded.contains(list.get(i));
                new MusicGlassDialogBuilder(this)
                        .setTitle(R.string.wave2_device_exclude)
                        .setMultiChoiceItems(list.toArray(new String[0]), checked, (d, which, isChecked) -> {
                            if (isChecked) excluded.add(list.get(which));
                            else excluded.remove(list.get(which));
                        })
                        .setPositiveButton("保存", (d, w) -> {
                            MusicStore.setDeviceExcludedFolders(excluded);
                            loadDevice();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private void showDeviceRemovedDialog() {
        int n = MusicStore.loadIgnoredDeviceIds().size();
        if (n <= 0) {
            Notify.show("没有已移除的歌曲");
            return;
        }
        new MusicGlassDialogBuilder(this)
                .setTitle("已移除歌曲")
                .setMessage("共 " + n + " 首被移除（文件未删），恢复后重新出现在本地音乐里。")
                .setPositiveButton(R.string.wave2_device_restore_all, (d, w) -> {
                    MusicStore.clearIgnoredDeviceIds();
                    Notify.show(R.string.wave2_device_restored);
                    loadDevice();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ================= 显示刷新（过滤→排序／分组→列表） =================

    private void refreshDeviceDisplay() {
        if (etDeviceSearch != null) etDeviceSearch.setVisibility(View.VISIBLE);
        if (tvDeviceRemoved != null) {
            int removed = MusicStore.loadIgnoredDeviceIds().size();
            tvDeviceRemoved.setVisibility(removed > 0 ? View.VISIBLE : View.GONE);
            if (removed > 0) tvDeviceRemoved.setText(getString(R.string.wave2_device_removed, removed));
        }
        List<MusicTrack> base = new ArrayList<>();
        String dkw = deviceSearchKw.toLowerCase(java.util.Locale.ROOT);
        for (MusicTrack t : deviceAll) {
            if (!dkw.isEmpty()) {
                String hay = ((t.title == null ? "" : t.title) + " " + (t.artist == null ? "" : t.artist)
                        + " " + (t.album == null ? "" : t.album)).toLowerCase(java.util.Locale.ROOT);
                if (!hay.contains(dkw)) continue;
            }
            base.add(t);
        }
        deviceSectionPos.clear();
        deviceSectionTitles.clear();
        deviceSectionCounts.clear();
        List<MusicTrack> ordered;
        if (deviceSort == 0) ordered = sortDeviceFlat(base);
        else ordered = groupDevice(base);
        tracks.clear();
        tracks.addAll(ordered);
        tvBigTitle.setText(bigTitle);
        tvBigCount.setText(tracks.size() + " " + getString(R.string.music_tracks));
        fadeListInOnce();
        if (tracks.isEmpty()) XcFadeTransition.fadeIn(empty);
        else XcFadeTransition.fadeOutThenGone(empty);
        if (tracks.isEmpty()) {
            if (!deviceSearchKw.isEmpty()) {
                empty.setText("没有找到本地音乐");
                empty.setOnClickListener(null);
            } else {
                // 空态文案如实：本页没有选文件夹入口，点击就是重新扫描，不许再许诺不存在的入口
                empty.setText("尚未添加本地音乐\n点此重新扫描");
                empty.setOnClickListener(v -> loadDevice());
            }
        }
        adapter.notifyDataSetChanged();
    }

    private List<MusicTrack> sortDeviceFlat(List<MusicTrack> base) {
        List<MusicTrack> out = new ArrayList<>(base);
        java.util.Comparator<MusicTrack> cmp;
        if (deviceSort == 2) cmp = java.util.Comparator.comparingLong(t -> t.duration);
        else if (deviceSort == 3) cmp = java.util.Comparator.comparingLong(MusicDeviceActivity::fileSizeOf);
        else cmp = java.util.Comparator.comparingLong(MusicDeviceActivity::fileMtime);
        if (!deviceAsc) cmp = cmp.reversed();
        Collections.sort(out, cmp);
        return out;
    }

    private List<MusicTrack> groupDevice(List<MusicTrack> base) {
        java.util.Map<String, List<MusicTrack>> groups = new java.util.LinkedHashMap<>();
        for (MusicTrack t : base) {
            String key;
            if (deviceSort == 1) key = recentBucket(fileMtime(t));
            else if (deviceSort == 2) key = durationBucket(t.duration);
            else key = sizeBucket(fileSizeOf(t));
            List<MusicTrack> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(t);
        }
        List<String> order;
        if (deviceSort == 1) order = java.util.Arrays.asList("今天", "昨天", "本周", "更早");
        else if (deviceSort == 2) order = java.util.Arrays.asList("短", "中", "长", "超长");
        else order = java.util.Arrays.asList("小", "中", "大", "超大");
        if (!deviceAsc) Collections.reverse(order);
        List<MusicTrack> flat = new ArrayList<>();
        int pos = 1; // 首行是导入行
        for (String key : order) {
            List<MusicTrack> g = groups.get(key);
            if (g == null || g.isEmpty()) continue;
            java.util.Comparator<MusicTrack> cmp;
            if (deviceSort == 1) cmp = java.util.Comparator.comparingLong(MusicDeviceActivity::fileMtime);
            else if (deviceSort == 2) cmp = java.util.Comparator.comparingLong(t -> t.duration);
            else cmp = java.util.Comparator.comparingLong(MusicDeviceActivity::fileSizeOf);
            if (!deviceAsc) cmp = cmp.reversed();
            Collections.sort(g, cmp);
            deviceSectionPos.add(pos);
            deviceSectionTitles.add(key);
            deviceSectionCounts.add(g.size());
            pos += 1 + g.size();
            flat.addAll(g);
        }
        return flat;
    }

    private static String recentBucket(long mtime) {
        if (mtime <= 0) return "更早";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(mtime);
        boolean sameDay = now.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) return "今天";
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        boolean isYesterday = yesterday.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR)
                && yesterday.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR);
        if (isYesterday) return "昨天";
        long diff = System.currentTimeMillis() - mtime;
        if (diff < 7L * 24 * 60 * 60 * 1000) return "本周";
        return "更早";
    }

    private static String durationBucket(long ms) {
        if (ms < 180 * 1000) return "短";
        if (ms < 360 * 1000) return "中";
        if (ms < 600 * 1000) return "长";
        return "超长";
    }

    private static String sizeBucket(long bytes) {
        if (bytes < 5L * 1024 * 1024) return "小";
        if (bytes < 20L * 1024 * 1024) return "中";
        if (bytes < 100L * 1024 * 1024) return "大";
        return "超大";
    }

    private static long fileMtime(MusicTrack t) {
        try {
            if (t != null && t.localPath != null && !t.localPath.isEmpty()) {
                return new java.io.File(t.localPath).lastModified();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static long fileSizeOf(MusicTrack t) {
        try {
            if (t != null && t.localPath != null && !t.localPath.isEmpty()) {
                return new java.io.File(t.localPath).length();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 行尾音质徽标：只显真实格式编码名（flac＝FLAC，其余显扩展名大写），取不到不显。 */
    private static String deviceBadge(MusicTrack t) {
        String path = t == null ? null : t.localPath;
        if (path == null || path.isEmpty()) path = t == null ? null : t.title;
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot >= path.length() - 1) return "";
        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.US);
        if (ext.equals("flac")) return "FLAC";
        return ext.toUpperCase(java.util.Locale.US);
    }

    // ================= 播放 =================

    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "device");
        params.put("keyword", "");
        params.put("sitePlatform", "");
        params.put("playlistId", "-1");
        params.put("title", bigTitle == null ? "" : bigTitle);
        return params;
    }

    private void playTrack(int position) {
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
    }

    private void playAllSequential() {
        if (tracks.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        playTrack(0);
    }

    private void shufflePlay() {
        if (tracks.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        MusicController.get().play(shuffled, 0, bigTitle, "list", listRouteParams());
    }

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

    private int adapterPositionForTrack(int trackIdx) {
        if (adapter == null || trackIdx < 0 || trackIdx >= tracks.size()) return -1;
        int seen = -1;
        for (int p = 1; p < adapter.getItemCount(); p++) {
            if (deviceSectionPos.contains(p)) continue;
            if (++seen == trackIdx) return p;
        }
        return -1;
    }

    private int indexOfTrackId(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            MusicTrack t = tracks.get(i);
            if (t != null && id.equals(t.id)) return i;
        }
        return -1;
    }

    // ================= 行菜单 =================

    private void onMore(MusicTrack track, int position) {
        FragmentActivity act = this;
        boolean local = MusicDownloadStore.isLocal(track);
        List<String> opts = new ArrayList<>();
        opts.add("播放");
        opts.add(getString(R.string.music_play_next));
        opts.add(getString(R.string.music_add_to_playlist));
        MusicLibrary.isFavorite(track, fav -> {
            opts.add(fav ? getString(R.string.music_unfavorite) : getString(R.string.music_favorite));
            int deleteLocalIdx = -1;
            int removeDeviceIdx = -1;
            int deleteFileIdx = -1;
            if (local) {
                deleteLocalIdx = opts.size();
                opts.add(getString(R.string.music_download_delete));
            } else {
                removeDeviceIdx = opts.size();
                opts.add("移除");
                deleteFileIdx = opts.size();
                opts.add("删除文件");
            }
            int delIdx = deleteLocalIdx;
            int rmDevIdx = removeDeviceIdx;
            int delFileIdx = deleteFileIdx;
            MusicOptionsDialog.show(act, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) pickPlaylist(track);
                else if (idx == 3) {
                    boolean toFav = opts.get(3).equals(getString(R.string.music_favorite));
                    MusicLibrary.setFavorite(track, toFav, () ->
                            Notify.show(toFav ? R.string.music_favorited : R.string.music_unfavorited));
                } else if (idx == rmDevIdx) {
                    MusicStore.ignoreDeviceId(track.id);
                    Notify.show("已移出本地库");
                    loadDevice();
                } else if (idx == delFileIdx) {
                    new MusicGlassDialogBuilder(act)
                            .setTitle(track.displayTitle())
                            .setMessage("确定删除这个文件吗？文件将从手机中彻底删除，同名歌词与封面文件一并删除。")
                            .setPositiveButton("删除", (d, w) -> {
                                // 真回报：音频文件真删掉才报已删除（旧口径弃 delete() 返回值、
                                // 文件还在也照报已删除）；旁路同名文件仍一并删、不计成败
                                boolean deleted = false;
                                try {
                                    if (track.localPath != null && !track.localPath.isEmpty()) {
                                        java.io.File audio = new java.io.File(track.localPath);
                                        String name = audio.getName();
                                        int dot = name.lastIndexOf('.');
                                        deleted = !audio.isFile() || audio.delete();
                                        if (deleted && dot > 0) {
                                            String base = name.substring(0, dot);
                                            java.io.File parent = audio.getParentFile();
                                            if (parent != null) {
                                                for (String e : new String[]{"lrc", "jpg", "png", "jpeg"}) {
                                                    new java.io.File(parent, base + "." + e).delete();
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                Notify.show(deleted ? getString(R.string.music_deleted) : "删除失败，文件可能被占用");
                                if (deleted) loadDevice();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else if (idx == delIdx) {
                    new MusicGlassDialogBuilder(act)
                            .setTitle(track.displayTitle())
                            .setMessage(getString(R.string.music_download_delete_confirm))
                            .setPositiveButton(getString(R.string.music_download_delete), (d, w) ->
                                    MusicDownloadStore.deleteLocal(track, ok ->
                                            Notify.show(ok ? getString(R.string.music_deleted) : "删除失败，文件可能被占用")))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
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
                    inputPlaylistName(name -> MusicLibrary.createPlaylist(name, p ->
                            MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                    Notify.show(added ? R.string.music_added : R.string.music_already_in))));
                }
            });
        });
    }

    private interface NameInput {
        void onResult(String name);
    }

    private void inputPlaylistName(NameInput cb) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setPadding(0, dp(8), 0, dp(8));
        TextView icon = new TextView(this);
        icon.setText("≡+");
        icon.setTextSize(22);
        icon.setTextColor(0xFFFFFFFF);
        icon.setGravity(android.view.Gravity.CENTER);
        GradientDrawable iconBg = new GradientDrawable();
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
        GradientDrawable createBg = new GradientDrawable();
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

    // ================= 导入加密歌曲 =================

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
                if (isFinishing() || isDestroyed()) return;
                if (!fFailed.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    if (fOk > 0) sb.append("成功 ").append(fOk).append(" 首\n\n");
                    for (String f : fFailed) sb.append(f).append("\n");
                    new MusicGlassDialogBuilder(this)
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

    // ================= 工具 =================

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private void loadCoverRounded(String text, String url, ImageView view, int radiusDp) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            com.bumptech.glide.Glide.with(view).clear(view);
            com.bumptech.glide.Glide.with(view).load(ImgUtil.getUrl(url))
                    // 封面占位定案（扫描 M22）：有地址永远透明——加载中/失败都不许变素图，
                    // 只有真没地址才上素图（那条路在绑定处直设 CoverTextDrawable，不走本 helper）
                    .placeholder(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    .error(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                            new com.bumptech.glide.load.resource.bitmap.RoundedCorners(dp(radiusDp)))
                    .into(view);
        } catch (Throwable ignored) {
        }
    }

    // ================= 列表 =================

    private class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_TRACK = 0;
        private static final int TYPE_HEADER = 3;
        private static final int TYPE_SECTION = 6;

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_HEADER;
            if (deviceSectionPos.contains(position)) return TYPE_SECTION;
            return TYPE_TRACK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_SECTION) {
                return new SectionHolder(inf.inflate(R.layout.item_music_device_section, parent, false));
            }
            if (viewType == TYPE_HEADER) {
                return new CardHolder(inf.inflate(R.layout.item_music_nav, parent, false));
            }
            return new TrackHolder(inf.inflate(R.layout.item_music_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (position == 0) {
                bindImportRow((CardHolder) h);
                return;
            }
            if (deviceSectionPos.contains(position)) {
                bindDeviceSection((SectionHolder) h, deviceSectionPos.indexOf(position));
                return;
            }
            bindTrack((TrackHolder) h, trackIndex(position));
        }

        @Override
        public int getItemCount() {
            return tracks.size() + deviceSectionPos.size() + 1;
        }

        private int trackIndex(int position) {
            int sectionsBefore = 0;
            for (int p : deviceSectionPos) if (p < position) sectionsBefore++;
            return position - 1 - sectionsBefore;
        }

        private void bindImportRow(CardHolder h) {
            h.icon.setVisibility(View.VISIBLE);
            h.mosaicSingle.setVisibility(View.GONE);
            h.mosaicGrid.setVisibility(View.GONE);
            h.coverBox.setBackgroundResource(0);
            h.coverBox.setClipToOutline(false);
            h.name.setText(R.string.music_import_encrypted);
            h.status.setText("");
            h.icon.setImageResource(R.drawable.ic_music_download);
            h.icon.setBackground(circle(0xFF06B6D4));
            h.icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            h.itemView.setOnClickListener(v -> pickImportFiles());
        }

        private void bindDeviceSection(SectionHolder h, int sectionIdx) {
            h.name.setText(deviceSectionTitles.get(sectionIdx));
            h.count.setText(deviceSectionCounts.get(sectionIdx) + " 首");
            h.direction.setText(deviceAsc ? "升序" : "降序");
            h.direction.setOnClickListener(v -> {
                deviceAsc = !deviceAsc;
                refreshDeviceDisplay();
            });
        }

        private void bindTrack(TrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1A1A1A);
            MusicGlassWire.frameRow(h.itemView); // 行一框：液态玻璃（原口径非当前行裸底、当前行白卡；当前行另有红名＋在播字样作标识）
            String badge = deviceBadge(t);
            if (!badge.isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(badge);
                h.badge.setTextSize(9);
                int p4 = dp(4);
                int p1 = dp(1);
                h.badge.setPadding(p4, p1, p4, p1);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            String second = t.displayArtist();
            if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(second)) {
                second = getString(R.string.music_unknown_artist);
            }
            if (t.album != null && !t.album.trim().isEmpty()) second += " · " + t.album.trim();
            h.artist.setText(second);
            if (h.path != null) {
                String dir = null;
                if (t.localPath != null && !t.localPath.isEmpty()) {
                    java.io.File pf = new java.io.File(t.localPath).getParentFile();
                    if (pf != null) dir = pf.getAbsolutePath();
                }
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
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t.displayTitle(), false, 8 * h.cover.getResources().getDisplayMetrics().density));
            } else {
                loadCoverRounded(t.displayTitle(), t.cover, h.cover, 10);
            }
            h.itemView.setOnClickListener(v -> playTrack(position));
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

        class SectionHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView count;
            final TextView direction;

            SectionHolder(View v) {
                super(v);
                MusicGlassWire.glassifyTree(v); // 行一框：行内玻璃件同源接线（根或内卡按资源识别）
                name = v.findViewById(R.id.name);
                count = v.findViewById(R.id.count);
                direction = v.findViewById(R.id.direction);
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
            }
        }
    }
}
