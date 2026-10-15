package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.fongmi.android.tv.clash.MihomoManager;
import com.fongmi.android.tv.clash.NodeStore;
import com.fongmi.android.tv.databinding.ActivityNodeListBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 节点列表页（2026-10-15 他拍板从代理页拆出）：订阅动辄几百个节点，不在代理页里长翻。
 * 带搜索（按名字过滤）；全选只勾当前显示的；点名字设为当前节点（内核在跑时重启内核）；
 * 每行可改名、删除；勾选后可批量删除。
 */
public class NodeListActivity extends BaseActivity {

    private ActivityNodeListBinding binding;
    private NodeAdapter adapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, NodeListActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityNodeListBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        adapter = new NodeAdapter();
        binding.rvNodes.setLayoutManager(new LinearLayoutManager(this));
        binding.rvNodes.setAdapter(adapter);
        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.refresh();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        binding.btnCheckAll.setOnClickListener(v -> {
            NodeStore store = NodeStore.get(this);
            for (NodeStore.Node n : adapter.data) store.setChecked(n.id, true);
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
        adapter.refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.refresh();
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
            String kw = binding.etSearch.getText() == null ? ""
                    : binding.etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<NodeStore.Node> all = NodeStore.get(NodeListActivity.this).getAll();
            for (NodeStore.Node n : all) {
                if (kw.isEmpty() || (n.name != null && n.name.toLowerCase(Locale.ROOT).contains(kw))) {
                    data.add(n);
                }
            }
            boolean empty = data.isEmpty();
            binding.tvEmpty.setText(all.isEmpty() ? "还没有节点，先订阅或粘贴导入" : "没有匹配的节点");
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
            NodeStore store = NodeStore.get(NodeListActivity.this);
            String selected = ClashSettings.getSelectedNode(NodeListActivity.this);
            boolean isSel = node.id.equals(selected);
            h.name.setText(isSel ? node.name + "（当前）" : node.name);
            h.proto.setText(node.proto + " · " + node.server());
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(store.getChecked().contains(node.id));
            h.check.setOnCheckedChangeListener((btn, checked) -> store.setChecked(node.id, checked));
            h.name.setOnClickListener(v -> {
                store.setSelected(NodeListActivity.this, node.id);
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
                store.remove(NodeListActivity.this, node.id);
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
