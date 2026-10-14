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
import com.fongmi.android.tv.cloud.CloudDrive;
import com.fongmi.android.tv.cloud.CloudFile;
import com.fongmi.android.tv.cloud.drive115.Drive115;
import com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak;
import com.fongmi.android.tv.cloud.drivequark.DriveQuark;
import com.fongmi.android.tv.databinding.ActivityCloudDriveBinding;
import com.fongmi.android.tv.databinding.ItemCloudFileBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

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
        binding.toolbar.setNavigationOnClickListener(v -> onBackInvoked());
        updateDriveInfo();
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        binding.recycler.setAdapter(adapter);
        binding.refresh.setOnRefreshListener(this::fetchFiles);
        binding.fab.setOnClickListener(v -> showFabMenu());
        crumbs.add(new Crumb(rootCid, "根目录"));
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
        setLoading(true);
        String cid = currentCid();
        new Thread(() -> {
            try {
                List<CloudFile> result = drive.listDir(cid, 0, PAGE_LIMIT);
                runOnUiThread(() -> {
                    setLoading(false);
                    files.clear();
                    if (result != null) files.addAll(result);
                    adapter.notifyDataSetChanged();
                    renderBreadcrumb();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Notify.show("加载失败：" + msg(e));
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

    private void confirmDelete(CloudFile file) {
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定删除「" + file.getName() + "」吗？")
                .setPositiveButton("删除", (d, w) -> doDelete(file))
                .setNegativeButton("取消", null)
                .show();
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

    private void showFabMenu() {
        String[] items = {"离线下载", "转存分享链接", "新建文件夹"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) showOfflineDialog();
                    else if (which == 1) showSaveShareDialog();
                    else showCreateFolderDialog();
                })
                .show();
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
