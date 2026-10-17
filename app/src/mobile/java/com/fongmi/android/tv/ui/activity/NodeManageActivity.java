package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.clash.ClashSettings;
import com.fongmi.android.tv.clash.KernelDownloader;
import com.fongmi.android.tv.clash.MihomoManager;
import com.fongmi.android.tv.clash.NodeStore;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.fongmi.android.tv.clash.SubParser;
import com.fongmi.android.tv.databinding.ActivityNodeManageBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.List;

/** 代理页：总开关、内核状态、订阅、粘贴导入、节点列表入口（黄色卡片，列表本体在 NodeListActivity）、全部走节点、按源定向入口。 */
public class NodeManageActivity extends BaseActivity {

    private ActivityNodeManageBinding binding;
    private boolean suppressSwitch;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, NodeManageActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityNodeManageBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.etSubUrl.setText(ClashSettings.getSubUrl(this));
        binding.swGlobal.setChecked(ClashSettings.isGlobalMode(this));
        binding.swGlobal.setOnCheckedChangeListener((btn, checked) -> {
            ClashSettings.setGlobalMode(this, checked);
            Notify.show(checked ? "全部走节点已开启" : "全部走节点已关闭");
        });
        binding.btnSaveSub.setOnClickListener(v -> {
            ClashSettings.setSubUrl(this, binding.etSubUrl.getText().toString());
            Notify.show("订阅地址已保存");
        });
        binding.btnRefreshSub.setOnClickListener(v -> refreshSub());
        binding.btnImport.setOnClickListener(v -> importLinks());
        binding.cardNodeList.setOnClickListener(v -> NodeListActivity.start(this));
        binding.cardPolicy.setOnClickListener(v -> SourcePolicyActivity.start(this));
        suppressSwitch = true;
        binding.swNode.setChecked(ClashSettings.isEnabled(this));
        suppressSwitch = false;
        binding.swNode.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressSwitch) return;
            setNodeEnabled(checked);
        });
        MihomoManager.get().setListener(new MihomoManager.Listener() {
            @Override
            public void onStarted() {
                runOnUiThread(() -> {
                    binding.tvNodeStatus.setText(MihomoManager.get().isRunning() ? "运行中" : "未启用");
                    refreshKernel();
                });
            }

            @Override
            public void onStopped() {
                runOnUiThread(() -> binding.tvNodeStatus.setText("已停止"));
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> binding.tvNodeStatus.setText("启动失败：" + msg));
            }
        });
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override
    protected void onDestroy() {
        MihomoManager.get().setListener(null);
        super.onDestroy();
    }

    private void setNodeEnabled(boolean checked) {
        if (checked && !KernelDownloader.isReady(this)) {
            binding.tvNodeStatus.setText("正在下载内核，请稍候…");
        } else {
            binding.tvNodeStatus.setText(checked ? "正在启动…" : "正在停止…");
        }
        MihomoManager.get().setEnabled(this, checked);
    }

    private void refreshAll() {
        suppressSwitch = true;
        binding.swNode.setChecked(ClashSettings.isEnabled(this));
        suppressSwitch = false;
        refreshKernel();
        refreshPolicySub();
        refreshNodeListSub();
        if (!ClashSettings.isEnabled(this)) binding.tvNodeStatus.setText("未启用");
        else binding.tvNodeStatus.setText(MihomoManager.get().isRunning() ? "运行中" : "未启动");
    }

    private void refreshKernel() {
        // 随包内置的内核在位时直接认它：内置版不写下载记录，绝不能误报「未下载」。
        if (KernelDownloader.bundledBinary(this) != null) {
            binding.tvKernelStatus.setText("内置内核 v" + KernelDownloader.wantVersion(this) + "，随 App 版本更新");
            return;
        }
        // 内置真不在位才走下载记录的老文案。
        String have = ClashSettings.getKernelVersion(this);
        String want = KernelDownloader.wantVersion(this);
        if (have == null || have.isEmpty()) {
            binding.tvKernelStatus.setText("未下载，打开节点开关后自动下载");
        } else if (have.equals(want)) {
            binding.tvKernelStatus.setText("当前版本 " + have + " / 最新 " + want);
        } else {
            binding.tvKernelStatus.setText("当前版本 " + have + " / 最新 " + want + "，有新版，打开开关后自动更新");
        }
    }

    private void refreshPolicySub() {
        int n = SourceProxyPolicy.listEnabled(this).size();
        binding.tvPolicySub.setText(n == 0 ? "默认全部直连" : "已开 " + n + " 个源走节点");
    }

    /** 黄色卡片副标题：节点总数 + 当前节点名。列表本体在 NodeListActivity。 */
    private void refreshNodeListSub() {
        List<NodeStore.Node> all = NodeStore.get(this).getAll();
        if (all.isEmpty()) {
            binding.tvNodeListSub.setText("还没有节点");
            return;
        }
        NodeStore.Node n = NodeStore.get(this).getSelected(this);
        String cur = n == null || n.name == null || n.name.isEmpty() ? "未选节点" : n.name;
        binding.tvNodeListSub.setText("共 " + all.size() + " 个 · 当前：" + cur);
    }

    private void refreshSub() {
        String url = ClashSettings.getSubUrl(this);
        if (url == null || url.isEmpty()) {
            Notify.show("先填写并保存订阅地址");
            return;
        }
        Notify.show("正在刷新订阅…");
        Task.execute(() -> {
            try {
                String content = OkHttp.string(url);
                List<SubParser.NodeDraft> drafts = SubParser.parse(content);
                if (drafts.isEmpty()) {
                    runOnUiThread(() -> Notify.show("订阅内容为空或解析失败"));
                    return;
                }
                List<NodeStore.Node> nodes = new ArrayList<>();
                for (SubParser.NodeDraft d : drafts) nodes.add(NodeStore.fromDraft(d, NodeStore.SRC_SUB));
                // 整批替换订阅节点：优选订阅每次 IP 不同，只累加会每次多出一批重复
                NodeStore.get(this).replaceSource(this, NodeStore.SRC_SUB, nodes);
                int count = drafts.size();
                int stored = NodeStore.get(this).size();
                com.github.catvod.crawler.SpiderDebug.log("clash", "sub parse total=%d ok=%d failed=%d schemes=%s stored=%d",
                        SubParser.lastTotal, count, SubParser.lastFailed, SubParser.lastFailedSchemes, stored);
                runOnUiThread(() -> {
                    refreshNodeListSub();
                    refreshKernel();
                    Notify.show("订阅已更新 " + count + " 个节点（列表共 " + stored + " 个）");
                });
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show("订阅刷新失败"));
            }
        });
    }

    private void importLinks() {
        String text = binding.etImport.getText().toString();
        if (text.trim().isEmpty()) {
            Notify.show("先粘贴节点链接");
            return;
        }
        Task.execute(() -> {
            List<SubParser.NodeDraft> drafts = SubParser.parse(text);
            if (drafts.isEmpty()) {
                runOnUiThread(() -> Notify.show("没有解析出可用节点"));
                return;
            }
            List<NodeStore.Node> nodes = new ArrayList<>();
            for (SubParser.NodeDraft d : drafts) nodes.add(NodeStore.fromDraft(d, NodeStore.SRC_MANUAL));
            NodeStore.get(this).addAll(nodes);
            int count = drafts.size();
            com.github.catvod.crawler.SpiderDebug.log("clash", "import parse total=%d ok=%d failed=%d schemes=%s stored=%d",
                    SubParser.lastTotal, count, SubParser.lastFailed, SubParser.lastFailedSchemes, NodeStore.get(this).size());
            runOnUiThread(() -> {
                binding.etImport.setText("");
                refreshNodeListSub();
                Notify.show("已导入 " + count + " 个节点");
            });
        });
    }

}
