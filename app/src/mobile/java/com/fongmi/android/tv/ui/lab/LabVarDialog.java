package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令变量填写弹窗（上游 LabVariableDialog 口径，星辰皮）：
 * 变量类型 text / text_multiline / number / select / switch / file / directory；
 * 渲染只留名字＋必填标记，hint 只做输入框 placeholder，不单独成行（忌介绍文字）。
 * select 点行弹出选项选；file/directory 带「选」钮调系统文件选择器。
 */
public final class LabVarDialog {

    public interface Callback {
        void onConfirm(Map<String, String> values);
    }

    private static final int REQ_VAR_PICK = 0x2107;
    /** 文件选择回填：file/directory 类型用，Activity 在 onActivityResult 里转交 */
    private static PickTarget pendingPick;

    private static class PickTarget {
        EditText edit;
    }

    /** Activity 侧在 onActivityResult 里调这一行即可 */
    public static boolean onPickResult(int requestCode, int resultCode, Intent data, Activity activity) {
        if (requestCode != REQ_VAR_PICK || pendingPick == null) return false;
        PickTarget t = pendingPick;
        pendingPick = null;
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && t.edit != null) {
            Uri uri = data.getData();
            String path = uri.getPath();
            t.edit.setText(path == null ? uri.toString() : path);
        }
        return true;
    }

    private LabVarDialog() {
    }

    /**
     * @param title    标题（命令名/「包设置」）
     * @param vars     变量表
     * @param cached   已缓存的值（变量值复用）
     * @param okText   确认钮文案（「运行」/「保存」）
     */
    public static void show(Activity activity, String title, List<LabManifest.LabVar> vars,
                            Map<String, String> cached, String okText, Callback callback) {
        if (vars == null || vars.isEmpty()) {
            if (callback != null) callback.onConfirm(new HashMap<>());
            return;
        }
        int dp8 = LabSkin.dp(activity, 8);
        int dp16 = LabSkin.dp(activity, 16);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp16, dp16, dp16, dp16);
        scroll.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        List<EditText> edits = new ArrayList<>();
        List<Switch> switches = new ArrayList<>();
        List<TextView> picks = new ArrayList<>();
        for (LabManifest.LabVar v : vars) {
            TextView label = new TextView(activity);
            label.setText(v.name + (v.required ? " *" : ""));
            label.setTextSize(14);
            label.setTextColor(LabSkin.TEXT_MAIN);
            label.getPaint().setFakeBoldText(true);
            box.addView(label);

            String type = v.type == null ? "text" : v.type;
            String cachedVal = cached == null ? null : cached.get(v.key);
            if ("switch".equals(type)) {
                Switch sw = new Switch(activity);
                boolean on = cachedVal != null ? !"false".equalsIgnoreCase(cachedVal)
                        : !"false".equalsIgnoreCase(v.defaultValue);
                sw.setChecked(on);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.bottomMargin = dp16;
                box.addView(sw, slp);
                switches.add(sw);
                edits.add(null);
                picks.add(null);
                continue;
            }
            switches.add(null);

            if ("select".equals(type) && v.options != null && !v.options.isEmpty()) {
                TextView pick = new TextView(activity);
                pick.setTextSize(15);
                pick.setTextColor(LabSkin.TEXT_MAIN);
                pick.setBackground(LabSkin.buttonGray(activity));
                pick.setPadding(dp16, dp8, dp16, dp8);
                String cur = cachedVal != null ? cachedVal : v.defaultValue;
                pick.setText(labelOf(v, cur));
                pick.setTag(cur == null ? "" : cur);
                pick.setOnClickListener(vv -> {
                    String[] labels = new String[v.options.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = v.options.get(i).label;
                    new android.app.AlertDialog.Builder(activity)
                            .setItems(labels, (d, which) -> {
                                LabManifest.LabOption op = v.options.get(which);
                                pick.setText(op.label);
                                pick.setTag(op.value);
                            }).show();
                });
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.bottomMargin = dp16;
                box.addView(pick, plp);
                picks.add(pick);
                edits.add(null);
                continue;
            }
            picks.add(null);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            EditText et = new EditText(activity);
            et.setTextSize(15);
            et.setTextColor(LabSkin.TEXT_MAIN);
            et.setHintTextColor(LabSkin.TEXT_DIM);
            if (v.hint != null) et.setHint(v.hint);
            String init = cachedVal != null ? cachedVal : (v.defaultValue == null ? "" : v.defaultValue);
            et.setText(init);
            if ("number".equals(type)) {
                et.setInputType(InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            } else if ("text_multiline".equals(type)) {
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                et.setMinLines(2);
                et.setMaxLines(5);
            } else {
                et.setInputType(InputType.TYPE_CLASS_TEXT);
            }
            et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(et);
            if ("file".equals(type) || "directory".equals(type)) {
                TextView sel = new TextView(activity);
                sel.setText("选");
                sel.setTextSize(14);
                sel.setTextColor(LabSkin.ON_YELLOW);
                sel.setBackground(LabSkin.buttonYellow(activity));
                sel.setPadding(dp16, dp8, dp16, dp8);
                boolean dir = "directory".equals(type);
                sel.setOnClickListener(vv -> {
                    pendingPick = new PickTarget();
                    pendingPick.edit = et;
                    Intent i = dir
                            ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            : new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    if (!dir) {
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        if (v.filter != null && !v.filter.isEmpty()) {
                            String f = v.filter.trim();
                            if (f.startsWith(".")) i.setType("*/*");
                        }
                    }
                    try {
                        activity.startActivityForResult(i, REQ_VAR_PICK);
                    } catch (Exception e) {
                        Notify.show("打不开文件选择");
                    }
                });
                row.addView(sel);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp16;
            box.addView(row, rlp);
            edits.add(et);
        }

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("算了", null)
                .setPositiveButton(okText == null ? "运行" : okText, null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Map<String, String> values = new HashMap<>();
                    for (int i = 0; i < vars.size(); i++) {
                        LabManifest.LabVar var = vars.get(i);
                        Switch sw = switches.get(i);
                        if (sw != null) {
                            values.put(var.key, String.valueOf(sw.isChecked()));
                            continue;
                        }
                        TextView pick = picks.get(i);
                        if (pick != null) {
                            Object val = pick.getTag();
                            String s = val == null ? "" : String.valueOf(val);
                            if (var.required && s.isEmpty()) {
                                Notify.show("请选：" + var.name);
                                return;
                            }
                            values.put(var.key, s);
                            continue;
                        }
                        EditText et = edits.get(i);
                        String text = et.getText() == null ? "" : et.getText().toString().trim();
                        String err = check(var, text);
                        if (err != null) {
                            Notify.show(err);
                            return;
                        }
                        values.put(var.key, text);
                    }
                    dlg.dismiss();
                    if (callback != null) callback.onConfirm(values);
                }));
        dlg.show();
    }

    private static String labelOf(LabManifest.LabVar v, String value) {
        if (value != null && v.options != null) {
            for (LabManifest.LabOption op : v.options) {
                if (value.equals(op.value)) return op.label;
            }
        }
        return value == null ? "点我选择" : value;
    }

    /** 必填/min/max/数字校验，人话一句 */
    private static String check(LabManifest.LabVar var, String text) {
        if (var.required && TextUtils.isEmpty(text)) return "请填：" + var.name;
        if (TextUtils.isEmpty(text)) return null;
        if ("number".equals(var.type)) {
            double n;
            try {
                n = Double.parseDouble(text);
            } catch (Exception e) {
                return var.name + "得是数字";
            }
            if (var.min != null) {
                try {
                    if (n < Double.parseDouble(var.min)) return var.name + "不能小于 " + var.min;
                } catch (Exception ignored) {
                }
            }
            if (var.max != null) {
                try {
                    if (n > Double.parseDouble(var.max)) return var.name + "不能大于 " + var.max;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}
