package com.fongmi.android.tv.api.hk;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fongmi.android.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 书签/收藏编辑弹窗：星辰原生重实现，对照 8.83 BookmarkEditPopup。
 * 8.83：XPopup CenterPopupView（layout pop_input_bookmark_add），字段：
 * 标题(必填)/链接/图标/分组（分组点击弹文件夹选择器，无文件夹时 toast"还没有文件夹，先新建一个吧"），
 * 确认回调 ok(title, url, icon, group)；groupEditable(true) 允许编辑分组，hideIconView() 隐藏图标行。
 * 星辰无 XPopup 依赖，用原生 Dialog 居中实现，语义一致。
 */
public class HkBookmarkEditPopup {

    public interface OkListener {
        void ok(String title, String url, String icon, String group);
    }

    private final Activity activity;
    private String title = "新增收藏";
    private String titleDefault = "";
    private String titleHint = "请输入书签标题";
    private String urlDefault = "";
    private boolean isAllowUrlSet = true;
    private String iconDefault = "";
    private boolean iconHide = false;
    private boolean isAllowGroupSet = true;
    private boolean groupEditable = false;
    private String groupDefault = "";
    private List<String> groups = new ArrayList<>();
    private OkListener okListener;

    private Dialog dialog;
    private EditText etTitle;
    private EditText etUrl;
    private EditText etIcon;
    private EditText etGroup;

    public HkBookmarkEditPopup(Activity activity) {
        this.activity = activity;
    }

    public static HkBookmarkEditPopup with(Activity activity) {
        return new HkBookmarkEditPopup(activity);
    }

    /**
     * 8.83 bind(title, titleDefault, titleHint, isAllowUrlSet, urlDefault,
     * isAllowGroupSet, groupDefault, groups, okListener) 对齐。
     */
    public HkBookmarkEditPopup bind(String title, String titleDefault, String titleHint,
                                    boolean isAllowUrlSet, String urlDefault,
                                    boolean isAllowGroupSet, String groupDefault,
                                    List<String> groups, OkListener okListener) {
        this.title = title == null ? "" : title;
        this.titleDefault = titleDefault == null ? "" : titleDefault;
        this.titleHint = titleHint == null ? "" : titleHint;
        this.isAllowUrlSet = isAllowUrlSet;
        this.urlDefault = urlDefault == null ? "" : urlDefault;
        this.isAllowGroupSet = isAllowGroupSet;
        this.groupDefault = groupDefault == null ? "" : groupDefault;
        this.groups = groups == null ? new ArrayList<>() : groups;
        this.okListener = okListener;
        return this;
    }

    /** 8.83 bind(title, url, icon, group, groups, okListener) 简化版对齐。 */
    public HkBookmarkEditPopup bind(String title, String url, String icon, String group,
                                    List<String> groups, OkListener okListener) {
        this.title = title == null ? "" : title;
        this.titleDefault = "";
        this.urlDefault = url == null ? "" : url;
        this.iconDefault = icon == null ? "" : icon;
        this.groupDefault = group == null ? "" : group;
        this.groups = groups == null ? new ArrayList<>() : groups;
        this.okListener = okListener;
        return this;
    }

    public HkBookmarkEditPopup title(String title) {
        this.title = title == null ? "" : title;
        return this;
    }

    public HkBookmarkEditPopup titleDefault(String titleDefault) {
        this.titleDefault = titleDefault == null ? "" : titleDefault;
        return this;
    }

    public HkBookmarkEditPopup urlDefault(String urlDefault) {
        this.urlDefault = urlDefault == null ? "" : urlDefault;
        return this;
    }

    public HkBookmarkEditPopup groupDefault(String groupDefault) {
        this.groupDefault = groupDefault == null ? "" : groupDefault;
        return this;
    }

    public HkBookmarkEditPopup groups(List<String> groups) {
        this.groups = groups == null ? new ArrayList<>() : groups;
        return this;
    }

    /** 8.83 groupEditable(Z) 对齐：分组文本框可手动编辑。 */
    public HkBookmarkEditPopup groupEditable(boolean editable) {
        this.groupEditable = editable;
        return this;
    }

    /** 8.83 hideIconView() 对齐：隐藏图标输入行。 */
    public HkBookmarkEditPopup hideIconView() {
        this.iconHide = true;
        return this;
    }

    public HkBookmarkEditPopup withIcon(String icon) {
        this.iconDefault = icon == null ? "" : icon;
        return this;
    }

    public HkBookmarkEditPopup okListener(OkListener okListener) {
        this.okListener = okListener;
        return this;
    }

    /** 8.83 updateGroups(List) 对齐。 */
    public void updateGroups(List<String> groups) {
        this.groups = groups == null ? new ArrayList<>() : groups;
    }

    /** 8.83 updateUrl(String) 对齐。 */
    public void updateUrl(String url) {
        if (etUrl != null) etUrl.setText(url == null ? "" : url);
        else this.urlDefault = url == null ? "" : url;
    }

    public void show() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_hk_bookmark_edit, null);
        dialog.setContentView(view);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.86);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }

        TextView tvTitle = view.findViewById(R.id.hk_title);
        etTitle = view.findViewById(R.id.hk_edit_title);
        etUrl = view.findViewById(R.id.hk_edit_url);
        etIcon = view.findViewById(R.id.hk_edit_icon);
        etGroup = view.findViewById(R.id.hk_group_edit_text);
        LinearLayout groupView = view.findViewById(R.id.hk_group_view);
        View groupImg = view.findViewById(R.id.hk_group_img);

        tvTitle.setText(title);
        etTitle.setHint(titleHint);
        etTitle.setText(titleDefault);
        etUrl.setText(urlDefault);
        etUrl.setEnabled(isAllowUrlSet);
        etIcon.setText(iconDefault);
        etIcon.setVisibility(iconHide ? View.GONE : View.VISIBLE);
        groupView.setVisibility(isAllowGroupSet ? View.VISIBLE : View.GONE);
        etGroup.setText(groupDefault);
        etGroup.setEnabled(groupEditable);

        // 8.83 lambda$onCreate$4：图标填了 http 链接且链接为空时，自动填入链接
        etIcon.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String icon = s == null ? "" : s.toString();
                if (!TextUtils.isEmpty(icon) && icon.startsWith("http")
                        && TextUtils.isEmpty(etUrl.getText())) {
                    etUrl.setText(icon);
                }
            }
        });

        View.OnClickListener pickGroup = v -> showGroupPicker();
        groupImg.setOnClickListener(pickGroup);
        etGroup.setOnClickListener(pickGroup);

        view.findViewById(R.id.hk_tv_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.hk_tv_confirm).setOnClickListener(v -> {
            dialog.dismiss();
            if (okListener != null) {
                okListener.ok(etTitle.getText().toString(),
                        etUrl.getText().toString(),
                        etIcon.getText().toString(),
                        etGroup.getText().toString());
            }
        });
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    /**
     * 8.83 lambda$onCreate$2 对齐：分组选择器。
     * 无分组时 toast"还没有文件夹，先新建一个吧"；否则弹"选择文件夹"列表，
     * 展示用 getTree 的树形缩进，点击后填入原始分组路径。
     */
    private void showGroupPicker() {
        if (groups == null || groups.isEmpty()) {
            Toast.makeText(activity, "还没有文件夹，先新建一个吧", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> rawGroups = new ArrayList<>(groups);
        final String[] display = getTree(rawGroups);

        Dialog picker = new Dialog(activity);
        picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.hk_update_log_bg);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(activity);
        titleView.setText("选择文件夹");
        titleView.setTextSize(17);
        titleView.setTextColor(0xff222222);
        titleView.getPaint().setFakeBoldText(true);
        titleView.setGravity(android.view.Gravity.CENTER);
        root.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.ListView listView = new android.widget.ListView(activity);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                activity, android.R.layout.simple_list_item_1, display);
        listView.setAdapter(adapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        lp.topMargin = dp(12);
        root.addView(listView, lp);

        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (etGroup != null) etGroup.setText(rawGroups.get(position));
            picker.dismiss();
        });

        picker.setContentView(root);
        Window window = picker.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.8);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        picker.setCanceledOnTouchOutside(true);
        picker.show();
    }

    /**
     * 8.83 BookmarkEditPopup.getTree(List) 对齐：含 "/" 的多级分组路径，
     * 除最后一级外每级替换为 4 空格缩进，形成树形展示。
     */
    static String[] getTree(List<String> groups) {
        List<String> list = new ArrayList<>(groups);
        for (int i = 0; i < list.size(); i++) {
            String g = list.get(i);
            if (g == null || "/".equals(g)) continue;
            String[] parts = g.split("/");
            if (parts.length > 1) {
                for (int j = 0; j < parts.length - 1; j++) parts[j] = "    ";
                StringBuilder sb = new StringBuilder();
                for (String p : parts) sb.append(p);
                list.set(i, sb.toString());
            }
        }
        return list.toArray(new String[0]);
    }

    private int dp(int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 供 HkDetailFragment 快捷调用：8.83 collectOrUpdate 的弹窗参数组装。 */
    public static void showForCollect(Activity activity, String dialogTitle, String titleDefault,
                                     String urlDefault, String groupDefault, List<String> groups,
                                     OkListener okListener) {
        with(activity)
                .bind(dialogTitle, titleDefault, "请输入标题，不能为空",
                        true, urlDefault, true, groupDefault, groups, okListener)
                .groupEditable(true)
                .hideIconView()
                .show();
    }
}
