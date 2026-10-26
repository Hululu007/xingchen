package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.cloud.CloudDrive;
import com.fongmi.android.tv.cloud.CloudFile;
import com.fongmi.android.tv.cloud.CloudTask;
import com.fongmi.android.tv.cloud.drive115.Drive115;
import com.fongmi.android.tv.cloud.drive115.PlayUrlBuilder115;
import com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak;
import com.fongmi.android.tv.cloud.drivequark.DriveQuark;
import com.fongmi.android.tv.databinding.ActivityCloudDriveBinding;
import com.fongmi.android.tv.databinding.ItemCloudFileBinding;
import com.fongmi.android.tv.music.CloudMusicSource;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.music.MusicGlassDialogs;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.utils.Notify;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 网盘文件浏览器（115 / 夸克 / PikPak 共用）
 * 入口：CloudDriveActivity.start(activity, "115" | "quark" | "pikpak")
 */
public class CloudDriveActivity extends BaseActivity {

    private static final String TAG = "CloudDriveActivity";
    private static final String EXTRA_DRIVE_ID = "drive_id";
    private static final int PAGE_LIMIT = 100;

    public static void start(Activity activity, String driveId) {
        Intent intent = new Intent(activity, CloudDriveActivity.class);
        intent.putExtra(EXTRA_DRIVE_ID, driveId);
        activity.startActivity(intent);
    }

    private ActivityCloudDriveBinding binding;
    private CloudDrive drive;
    private final List<Crumb> crumbs = new ArrayList<>();
    private final List<CloudFile> files = new ArrayList<>();
    private FileAdapter adapter;
    private String rootCid = "";
    /** 分页状态：hasMore 按“本页是否取满 PAGE_LIMIT”判定（115／PikPak 的 getTotal 返回 -1，不可用）。 */
    private boolean loadingMore;
    private boolean hasMore = true;
    private int fetchGen;
    /** onResume 刷新守门：initView 成功走完才置真；紧跟 initView 的首个 onResume 跳过，避免同一次进页重复拉取。 */
    private boolean viewReady;
    private boolean firstResume = true;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityCloudDriveBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String driveId = getIntent().getStringExtra(EXTRA_DRIVE_ID);
        drive = createDrive(driveId);
        if (drive == null) {
            Notify.show("网盘驱动不可用");
            finish();
            return;
        }
        if (!drive.isLogin()) {
            Notify.show("请先登录" + drive.getName());
            PanLoginActivity.start(this);
            finish();
            return;
        }
        rootCid = Drive115.ID.equals(drive.getId()) ? "0" : "";
        binding.toolbar.setTitle(drive.getName());
        // 顶栏只留标题，不放自带返回箭头（布局里 navigationIcon 已删）。
        // 子目录回退走系统返回：BaseActivity 把 OnBackPressedDispatcher 接到 onBackInvoked()，
        // 本页 onBackInvoked() 有子目录时退目录、否则 finish，不再依赖顶栏图标点击。
        updateDriveInfo();
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        binding.recycler.setAdapter(adapter);
        binding.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loadingMore || !hasMore) return;
                RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                if (!(lm instanceof LinearLayoutManager)) return;
                int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                if (last >= files.size() - 3) loadMore();
            }
        });
        binding.refresh.setOnRefreshListener(this::fetchFiles);
        binding.fab.setOnClickListener(v -> showFabMenu());
        if (fabActions().isEmpty()) binding.fab.setVisibility(View.GONE);
        crumbs.add(new Crumb(rootCid, "根目录"));
        viewReady = true;
        fetchFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        // L-15：从播放/子页回来刷新目录与会员信息。
        // 未登录那条路在 initView 里已 start PanLogin＋finish，viewReady 不会置真，走不到这里；
        // 已 finish 的实例也不会再有 onResume，不搅乱现行登录跳转。
        if (!viewReady || drive == null || isFinishing()) return;
        updateDriveInfo();
        fetchFiles();
    }

    /** 顶部显示会员状态 + 容量（用户明确要求） */
    private void updateDriveInfo() {
        String vip = "";
        if (Drive115.ID.equals(drive.getId())) vip = com.fongmi.android.tv.utils.PanAuth.get115Vip();
        else if (DriveQuark.ID.equals(drive.getId())) vip = com.fongmi.android.tv.utils.PanAuth.getQuarkVip();
        String vipText = vip.isEmpty() ? "普通用户" : vip;
        binding.tvDriveInfo.setText(vipText);
        new Thread(() -> {
            try {
                String summary = drive.storageSummary();
                String text = vipText + (summary.isEmpty() ? "" : " · " + summary);
                runOnUiThread(() -> binding.tvDriveInfo.setText(text));
            } catch (Exception ignored) {
            }
        }).start();
    }

    private CloudDrive createDrive(String driveId) {
        if (Drive115.ID.equals(driveId)) return new Drive115();
        if (DrivePikPak.ID.equals(driveId)) return new DrivePikPak(this);
        if (DriveQuark.ID.equals(driveId)) return new DriveQuark();
        return null;
    }

    @Override
    protected void onBackInvoked() {
        if (crumbs.size() > 1) {
            crumbs.remove(crumbs.size() - 1);
            fetchFiles();
        } else {
            super.onBackInvoked();
        }
    }

    private String currentCid() {
        return crumbs.get(crumbs.size() - 1).cid;
    }

    private void setLoading(boolean loading) {
        if (!binding.refresh.isRefreshing() && loading) binding.refresh.setRefreshing(true);
        if (binding.refresh.isRefreshing() && !loading) binding.refresh.setRefreshing(false);
    }

    private void fetchFiles() {
        fetchGen++;
        hasMore = true;
        loadingMore = false;
        fetchPage(0, false, fetchGen);
    }

    private void loadMore() {
        if (loadingMore || !hasMore) return;
        loadingMore = true;
        fetchPage(files.size(), true, fetchGen);
    }

    /** 分页续取：offset 翻页；PikPak 的 listDir 内部用 page_token 兑 offset（见 DrivePikPak）。 */
    private void fetchPage(int offset, boolean append, int gen) {
        if (!append) setLoading(true);
        String cid = currentCid();
        new Thread(() -> {
            try {
                List<CloudFile> result = drive.listDir(cid, offset, PAGE_LIMIT);
                runOnUiThread(() -> {
                    if (gen != fetchGen || !cid.equals(currentCid())) return;
                    if (!append) setLoading(false);
                    loadingMore = false;
                    if (!append) files.clear();
                    if (result != null) files.addAll(result);
                    // 取满一页＝可能还有下一页；不满一页＝已到头。恰好整 100 倍数时多取一页空页收尾，不丢文件。
                    hasMore = result != null && result.size() >= PAGE_LIMIT;
                    adapter.notifyDataSetChanged();
                    if (!append) renderBreadcrumb();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    // A5：失败分支补与成功路同款 gen／cid 丢弃校验，过期请求失败不误弹“加载失败”。
                    if (gen != fetchGen || !cid.equals(currentCid())) return;
                    if (!append) setLoading(false);
                    loadingMore = false;
                    if (!append) Notify.show("加载失败：" + msg(e));
                    else Notify.show("加载更多失败：" + msg(e));
                });
            }
        }).start();
    }

    private void renderBreadcrumb() {
        binding.breadcrumb.removeAllViews();
        for (int i = 0; i < crumbs.size(); i++) {
            Crumb c = crumbs.get(i);
            if (i > 0) addCrumbSeparator();
            TextView tv = new TextView(this);
            tv.setText(c.name);
            tv.setTextSize(13);
            tv.setSingleLine(true);
            boolean last = (i == crumbs.size() - 1);
            tv.setTextColor(last ? 0xFF0C0E12 : 0xFF5A6675);
            if (!last) {
                final int index = i;
                tv.setClickable(true);
                tv.setFocusable(true);
                tv.setOnClickListener(v -> {
                    while (crumbs.size() > index + 1) crumbs.remove(crumbs.size() - 1);
                    fetchFiles();
                });
            }
            binding.breadcrumb.addView(tv);
        }
    }

    private void addCrumbSeparator() {
        TextView sep = new TextView(this);
        sep.setText("  ›  ");
        sep.setTextSize(13);
        sep.setTextColor(0xFF9AA3AF);
        binding.breadcrumb.addView(sep);
    }

    private void openDir(CloudFile dir) {
        crumbs.add(new Crumb(dir.getId(), dir.getName()));
        fetchFiles();
    }

    private void playFile(CloudFile file) {
        Notify.show("正在获取播放地址…");
        new Thread(() -> {
            try {
                // 确保 115 代理已启动（播放器只认本机代理地址，不直连 115）
                // 用 startProxy()（优选端口 32115+），不要裸调 NanoHTTPD.start()
                if (Drive115.ID.equals(drive.getId()) && drive instanceof Drive115) {
                    Drive115 d115 = (Drive115) drive;
                    com.fongmi.android.tv.cloud.drive115.Proxy115
                            .getInstance(d115.getClient(), d115.getCookieJar())
                            .startProxy();
                }
                if (Drive115.ID.equals(drive.getId()) && drive instanceof Drive115) {
                    // 115 走网盘自己的播放会话：清单（原画＋转码多档＋自动画质）组装成
                    // 直达选集载荷，线路归「115网盘」，不再把地址交进推送链合成详情。
                    PlayUrlBuilder115.PlayList pl = ((Drive115) drive).playList(file);
                    if (pl == null || pl.entries.isEmpty()) throw new Exception("取播放地址失败");
                    String episodesJson = pan115EpisodesJson(pl);
                    String firstUrl = pl.entries.get(pl.defaultIndex).proxyUrl;
                    int selIdx = pl.defaultIndex;
                    runOnUiThread(() -> VideoActivity.startPan115(this, firstUrl, file.getName(), episodesJson, selIdx));
                    return;
                }
                String url = drive.getPlayUrl(file);
                runOnUiThread(() -> {
                    if (!TextUtils.isEmpty(url)) {
                        // 带上文件名做标题：此前只传 url，详情标题只能拿代理 URL 本身充数
                        VideoActivity.start(this, SiteApi.PUSH, url, file.getName());
                    } else {
                        Notify.show("获取播放地址失败");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "115 播放失败", e); // 完整堆栈打到 Logcat
                String m = msg(e);
                String toast;
                if (m.contains("代理启动失败")) {
                    toast = "代理启动失败，请重试";
                } else if (m.contains("pick_code")) {
                    toast = "文件 pick_code 为空";
                } else {
                    toast = "获取播放地址失败：" + m;
                }
                final String ft = toast;
                runOnUiThread(() -> Notify.show(ft));
            }
        }).start();
    }

    /** 115 播放清单 → 直达选集载荷（单线路「115网盘」，选集即画质档，形态同 startLocal） */
    private static String pan115EpisodesJson(PlayUrlBuilder115.PlayList pl) throws Exception {
        org.json.JSONArray eps = new org.json.JSONArray();
        for (PlayUrlBuilder115.PlayEntry e : pl.entries) {
            JSONObject ep = new JSONObject();
            ep.put("name", e.label);
            ep.put("url", e.proxyUrl);
            eps.put(ep);
        }
        JSONObject flag = new JSONObject();
        flag.put("name", "115网盘");
        flag.put("episodes", eps);
        JSONObject root = new JSONObject();
        root.put("flags", new org.json.JSONArray().put(flag));
        return root.toString();
    }

    private void confirmDelete(CloudFile file) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定删除「" + file.getName() + "」吗？")
                .setPositiveButton("删除", (d, w) -> doDelete(file))
                .setNegativeButton("取消", null)
                .create();
        MusicGlassDialogs.attach(dialog);
        dialog.show();
    }

    private void doDelete(CloudFile file) {
        new Thread(() -> {
            try {
                boolean ok = drive.delete(Collections.singletonList(file.getId()), file.isDir());
                runOnUiThread(() -> {
                    Notify.show(ok ? "已删除" : "删除失败");
                    fetchFiles();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show("删除失败：" + msg(e)));
            }
        }).start();
    }

    /**
     * FAB 菜单按各家 drive 的真实能力建，不支持的入口直接不显示，不再先给入口、点了才弹失败：
     * 离线下载：115／PikPak 实现，夸克恒抛（DriveQuark:338）→ 夸克不显示；
     * 转存分享链接：115（未全验证但已实现）／PikPak 实现，夸克恒抛（DriveQuark:400）→ 夸克不显示；
     * 新建文件夹：只有 PikPak 覆盖 createFolder（DrivePikPak:731），115／夸克走 CloudDrive 默认实现恒抛 → 只 PikPak 显示；
     * 离线任务：115／PikPak 的 taskList 已实现，夸克 taskList 返回空表、taskDelete 恒抛 → 夸克不显示。
     */
    private List<String> fabActions() {
        List<String> actions = new ArrayList<>();
        String id = drive == null ? "" : drive.getId();
        boolean isQuark = DriveQuark.ID.equals(id);
        boolean isPikPak = DrivePikPak.ID.equals(id);
        if (!isQuark) actions.add("离线下载");
        if (!isQuark) actions.add("转存分享链接");
        if (isPikPak) actions.add("新建文件夹");
        if (!isQuark) actions.add("离线任务");
        return actions;
    }

    private void showFabMenu() {
        List<String> actions = fabActions();
        if (actions.isEmpty()) return;
        String[] items = actions.toArray(new String[0]);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    String action = actions.get(which);
                    if ("离线下载".equals(action)) showOfflineDialog();
                    else if ("转存分享链接".equals(action)) showSaveShareDialog();
                    else if ("新建文件夹".equals(action)) showCreateFolderDialog();
                    else if ("离线任务".equals(action)) showTaskListDialog();
                })
                .create();
        MusicGlassDialogs.attach(dialog);
        dialog.show();
    }

    /** 离线任务弹窗的会话状态：已拼接的全部任务、已拉页数、第 1 页条数（满页判定基准）、是否还有下一页。 */
    private static class TaskListState {
        final List<CloudTask> tasks = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        int loadedPages;
        int pageSize;
        boolean hasMore;
        boolean loading;
    }

    private static String taskLabel(CloudTask t) {
        String st = t.getStatusText();
        return t.getName() + "  " + t.getPercent() + "%"
                + (TextUtils.isEmpty(st) ? "" : "  " + st);
    }

    /** 离线任务列表入口（FAB）：从第 1 页拉起，弹窗内可继续翻页拼接。不自动轮询。 */
    private void showTaskListDialog() {
        showTaskListDialog(1);
    }

    /**
     * 拉够 targetPages 页（或到尾页为止）再开弹窗。
     * 删任务后按已拉页数走这里重拉，已翻到的页不丢（旧口径只重拉第 1 页，第 2 页起的任务删完就看不见）。
     * 满页判定与文件列表同口径：第 1 页条数作页大小，某页取满＝可能还有下一页、不满＝已到尾页；
     * 恰好整页数倍数时靠下一次取到空页收尾，不丢任务。
     */
    private void showTaskListDialog(int targetPages) {
        Notify.show("正在加载离线任务…");
        new Thread(() -> {
            try {
                TaskListState st = new TaskListState();
                for (int p = 1; p <= Math.max(1, targetPages); p++) {
                    List<CloudTask> pageTasks = drive.taskList(p);
                    if (pageTasks == null || pageTasks.isEmpty()) break;
                    if (p == 1) st.pageSize = pageTasks.size();
                    st.tasks.addAll(pageTasks);
                    st.loadedPages = p;
                    st.hasMore = st.pageSize > 0 && pageTasks.size() >= st.pageSize;
                    if (!st.hasMore) break;
                }
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (st.tasks.isEmpty()) {
                        Notify.show("暂无离线任务");
                        return;
                    }
                    openTaskListDialog(st);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show("加载离线任务失败：" + msg(e)));
            }
        }).start();
    }

    private void openTaskListDialog(TaskListState st) {
        for (CloudTask t : st.tasks) st.labels.add(taskLabel(t));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, st.labels);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("离线任务")
                .setAdapter(adapter, (d, which) -> confirmDeleteTask(st.tasks.get(which), st.loadedPages))
                .setNegativeButton("关闭", null);
        if (st.hasMore) builder.setNeutralButton("加载更多", null);
        AlertDialog dialog = builder.create();
        dialog.show();
        if (st.hasMore) {
            // 覆盖按钮的默认点击（默认会关弹窗）：改成不关弹窗、就地续拉下一页往后拼接。
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> loadMoreTasks(dialog, adapter, st));
        }
    }

    private void loadMoreTasks(AlertDialog dialog, ArrayAdapter<String> adapter, TaskListState st) {
        if (st.loading || !st.hasMore) return;
        st.loading = true;
        int next = st.loadedPages + 1;
        new Thread(() -> {
            try {
                List<CloudTask> pageTasks = drive.taskList(next);
                runOnUiThread(() -> {
                    st.loading = false;
                    if (!dialog.isShowing()) return;
                    if (pageTasks == null || pageTasks.isEmpty()) {
                        st.hasMore = false;
                        markNoMoreTasks(dialog);
                        Notify.show("没有更多了");
                        return;
                    }
                    for (CloudTask t : pageTasks) {
                        st.tasks.add(t);
                        st.labels.add(taskLabel(t));
                    }
                    st.loadedPages = next;
                    st.hasMore = st.pageSize > 0 && pageTasks.size() >= st.pageSize;
                    adapter.notifyDataSetChanged();
                    if (!st.hasMore) markNoMoreTasks(dialog);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    st.loading = false;
                    Notify.show("加载更多失败：" + msg(e));
                });
            }
        }).start();
    }

    private void markNoMoreTasks(AlertDialog dialog) {
        android.widget.Button btn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (btn == null) return;
        btn.setText("没有更多了");
        btn.setEnabled(false);
    }

    private void confirmDeleteTask(CloudTask task, int loadedPages) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除离线任务")
                .setMessage("确定删除「" + task.getName() + "」吗？")
                .setPositiveButton("删除", (d, w) -> {
                    new Thread(() -> {
                        try {
                            boolean ok = drive.taskDelete(Collections.singletonList(task.getHash()));
                            // A8＋F-14：删成功当场按已拉页数重拉刷新对话框列表，不要求关闭重进；删失败不刷新。
                            runOnUiThread(() -> {
                                Notify.show(ok ? "已删除离线任务" : "删除离线任务失败");
                                if (ok) showTaskListDialog(loadedPages);
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Notify.show("删除离线任务失败：" + msg(e)));
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .create();
        MusicGlassDialogs.attach(dialog);
        dialog.show();
    }

    /**
     * 网盘音频进音乐播放器：复用音乐侧已有的起播路径与文件直链口径——
     * 队列＝当前目录全部音频文件（点中那首起播，前后可切），曲目 id 戳与 MusicCloudActivity 一致
     * （pan115:/panquark:/panpikpak: + fid），rawJson 递 drive/fid/name/pickCode，
     * 取链由 CloudMusicSource.playUrl 按 drive 调 drive.getPlayUrl（115 走本机代理，夸克取下载直链）。
     * PikPak 段需 CloudMusicSource 加 PikPak 分支后才通（跨路依赖，见交回），115／夸克本路已通。
     */
    private void playAudio(CloudFile clicked) {
        List<CloudFile> audios = new ArrayList<>();
        int position = 0;
        for (CloudFile f : files) {
            if (!f.isAudio()) continue;
            if (f == clicked) position = audios.size();
            audios.add(f);
        }
        if (audios.isEmpty()) {
            Notify.show("暂不支持打开该文件类型");
            return;
        }
        String prefix;
        if (Drive115.ID.equals(drive.getId())) prefix = "pan115:";
        else if (DriveQuark.ID.equals(drive.getId())) prefix = "panquark:";
        else prefix = "panpikpak:";
        List<MusicTrack> queue = new ArrayList<>();
        for (CloudFile f : audios) {
            String name = f.getName() == null ? "" : f.getName();
            int dot = name.lastIndexOf('.');
            String title = dot > 0 ? name.substring(0, dot) : name;
            MusicTrack t = new MusicTrack(prefix + f.getId(), title, "", "", 0, null);
            t.platform = CloudMusicSource.PLATFORM;
            try {
                JSONObject o = new JSONObject();
                o.put("drive", drive.getId());
                o.put("fid", f.getId());
                o.put("name", name);
                o.put("pickCode", f.getPickCode() == null ? "" : f.getPickCode());
                t.rawJson = o.toString();
            } catch (Exception ignored) {
            }
            queue.add(t);
        }
        CloudMusicSource.ensureRegistered();
        MusicController.get().play(queue, position);
        MusicPlayerActivity.start(this);
    }

    private EditText makeInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(0xFF0C0E12);
        input.setHintTextColor(0xFF9AA3AF);
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        return input;
    }

    private void showOfflineDialog() {
        EditText input = makeInput("粘贴 magnet / ed2k / http 链接");
        new AlertDialog.Builder(this)
                .setTitle("离线下载")
                .setView(input)
                .setPositiveButton("开始", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        Notify.show("链接不能为空");
                        return;
                    }
                    doOfflineDownload(url);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doOfflineDownload(String url) {
        String cid = currentCid();
        new Thread(() -> {
            try {
                boolean ok = drive.offlineDownload(url, cid);
                runOnUiThread(() -> {
                    Notify.show(ok ? "离线任务已提交" : "提交失败");
                    fetchFiles();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show("提交失败：" + msg(e)));
            }
        }).start();
    }

    private void showSaveShareDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        EditText link = makeInput("分享链接");
        EditText pwd = makeInput("提取码（可选）");
        layout.addView(link);
        layout.addView(pwd);
        new AlertDialog.Builder(this)
                .setTitle("转存分享链接")
                .setView(layout)
                .setPositiveButton("转存", (d, w) -> {
                    String shareUrl = link.getText().toString().trim();
                    if (shareUrl.isEmpty()) {
                        Notify.show("分享链接不能为空");
                        return;
                    }
                    doSaveShare(shareUrl, pwd.getText().toString().trim());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSaveShare(String shareUrl, String password) {
        String cid = currentCid();
        new Thread(() -> {
            try {
                boolean ok = drive.saveShare(shareUrl, password, cid);
                runOnUiThread(() -> {
                    Notify.show(ok ? "转存成功" : "转存失败");
                    fetchFiles();
                });
            } catch (Exception e) {
                String m = msg(e);
                runOnUiThread(() -> Notify.show(TextUtils.isEmpty(m) ? "暂不支持" : m));
            }
        }).start();
    }

    private void showCreateFolderDialog() {
        EditText input = makeInput("文件夹名称");
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setView(input)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Notify.show("名称不能为空");
                        return;
                    }
                    doCreateFolder(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doCreateFolder(String name) {
        String cid = currentCid();
        new Thread(() -> {
            try {
                drive.createFolder(cid, name);
                runOnUiThread(() -> {
                    Notify.show("已创建");
                    fetchFiles();
                });
            } catch (Exception e) {
                String m = msg(e);
                runOnUiThread(() -> Notify.show(TextUtils.isEmpty(m) ? "暂不支持" : m));
            }
        }).start();
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null ? "" : m;
    }

    private static class Crumb {
        final String cid;
        final String name;

        Crumb(String cid, String name) {
            this.cid = cid;
            this.name = name;
        }
    }

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemCloudFileBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            CloudFile file = files.get(position);
            h.binding.tvName.setText(file.getName());
            h.binding.tvSize.setText(file.isDir() ? "文件夹" : file.getFormatSize());
            h.binding.ivArrow.setVisibility(file.isDir() ? View.VISIBLE : View.GONE);
            bindThumb(h, file);
            h.itemView.setOnClickListener(v -> {
                if (file.isDir()) openDir(file);
                else if (file.isVideo()) playFile(file);
                else if (file.isAudio()) playAudio(file);
                else Notify.show("暂不支持打开该文件类型");
            });
            h.itemView.setOnLongClickListener(v -> {
                confirmDelete(file);
                return true;
            });
        }

        private void bindThumb(Holder h, CloudFile file) {
            if (TextUtils.isEmpty(file.getThumbUrl())) {
                h.binding.ivThumb.setImageTintList(ColorStateList.valueOf(Color.parseColor("#0C0E12")));
                h.binding.ivThumb.setImageResource(file.isDir() ? R.drawable.ic_folder : R.drawable.ic_file);
            } else {
                h.binding.ivThumb.setImageTintList(null);
                Glide.with(h.itemView)
                        .load(file.getThumbUrl())
                        .placeholder(file.isDir() ? R.drawable.ic_folder : R.drawable.ic_file)
                        .error(file.isDir() ? R.drawable.ic_folder : R.drawable.ic_file)
                        .into(h.binding.ivThumb);
            }
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemCloudFileBinding binding;

            Holder(ItemCloudFileBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
