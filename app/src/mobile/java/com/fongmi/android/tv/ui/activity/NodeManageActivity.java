package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
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

/** 节点管理页：总开关、内核状态、订阅、粘贴导入、节点列表（多选/改名/删除/设当前）、全部走节点、按源定向入口。 */
public class NodeManageActivity extends BaseActivity {

    private ActivityNodeManageBinding binding;
    private NodeAdapter adapter;
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
        adapter = new NodeAdapter();
        binding.rvNodes.setLayoutManager(new LinearLayoutManager(this));
        binding.rvNodes.setAdapter(adapter);
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
        binding.btnCheckAll.setOnClickListener(v -> {
            NodeStore.get(this).checkAll();
            adapter.refresh();
        });
        binding.btnUncheckAll.setOnClickListener(v -> {
            NodeStore.get(this).clearChecked();
            adapter.refresh();
        });
        binding.btnDeleteChecked.setOnClickListener(v -> {
            NodeStore store = NodeStore.get(this);
            int n = store.getChecked().size();
            if (n == 0) {
                Notify.show("先勾选要删除的节点");
                return;
            }
            store.removeAll(this, store.getChecked());
            adapter.refresh();
            Notify.show("已删除 " + n + " 个节点");
        });
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
        adapter.refresh();
        if (!ClashSettings.isEnabled(this)) binding.tvNodeStatus.setText("未启用");
        else binding.tvNodeStatus.setText(MihomoManager.get().isRunning() ? "运行中" : "未启动");
    }

    private void refreshKernel() {
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
                NodeStore.get(this).addAll(nodes);
                int count = drafts.size();
                runOnUiThread(() -> {
                    adapter.refresh();
                    refreshKernel();
                    Notify.show("订阅已更新 " + count + " 个节点");
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
            runOnUiThread(() -> {
                binding.etImport.setText("");
                adapter.refresh();
                Notify.show("已导入 " + count + " 个节点");
            });
        });
    }

    private void showRename(NodeStore.Node node) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(node.name);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("改名")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    if (NodeStore.get(this).rename(node.id, input.getText().toString())) {
                        adapter.refresh();
                        Notify.show("已改名");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class NodeAdapter extends RecyclerView.Adapter<NodeAdapter.Holder> {

        private final List<NodeStore.Node> data = new ArrayList<>();

        void refresh() {
            data.clear();
            data.addAll(NodeStore.get(NodeManageActivity.this).getAll());
            boolean empty = data.isEmpty();
            binding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            binding.rvNodes.setVisibility(empty ? View.GONE : View.VISIBLE);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_node, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            NodeStore.Node node = data.get(position);
            NodeStore store = NodeStore.get(NodeManageActivity.this);
            String selected = ClashSettings.getSelectedNode(NodeManageActivity.this);
            boolean isSel = node.id.equals(selected);
            h.name.setText(isSel ? node.name + "（当前）" : node.name);
            h.proto.setText(node.proto + " · " + node.server());
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(store.getChecked().contains(node.id));
            h.check.setOnCheckedChangeListener((btn, checked) -> store.setChecked(node.id, checked));
            h.name.setOnClickListener(v -> {
                store.setSelected(NodeManageActivity.this, node.id);
                refresh();
                if (MihomoManager.get().isRunning()) {
                    Notify.show("已切换节点，正在重启内核…");
                    Task.execute(() -> {
                        try {
                            MihomoManager m = MihomoManager.get();
                            m.stopSync();
                            m.startSync(getApplicationContext());
                        } catch (Exception e) {
                            runOnUiThread(() -> Notify.show("内核重启失败"));
                        }
                    });
                } else {
                    Notify.show("已设为当前节点");
                }
            });
            h.rename.setOnClickListener(v -> showRename(node));
            h.delete.setOnClickListener(v -> {
                store.remove(NodeManageActivity.this, node.id);
                refresh();
                Notify.show("已删除");
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final CheckBox check;
            final TextView name;
            final TextView proto;
            final TextView rename;
            final TextView delete;

            Holder(View v) {
                super(v);
                check = v.findViewById(R.id.cb_node);
                name = v.findViewById(R.id.tv_name);
                proto = v.findViewById(R.id.tv_proto);
                rename = v.findViewById(R.id.tv_rename);
                delete = v.findViewById(R.id.tv_delete);
            }
        }
    }
}
