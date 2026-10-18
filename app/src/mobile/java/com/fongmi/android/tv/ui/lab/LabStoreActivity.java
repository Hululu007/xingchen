package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.utils.Notify;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 添加服务页（定稿 lab-store.png）：
 * 三卡：从文件安装 / 粘贴 / 链接（输入框＋导入钮，链接预填进粘贴导入页）；
 * 「可安装」列表＝本机扫到的 .lab 包（上游无商店，不编造远端数据），
 * 每行 名字＋大小＋安装钮；正在装的行露进度态。
 * 顶栏只留标题，走系统返回。
 */
public class LabStoreActivity extends AppCompatActivity {

    private LinearLayout listBox;
    private EditText linkInput;
    private final Set<String> installing = new HashSet<>();

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LabStoreActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LabSkin.edgeToEdge(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LabInstallActivity.REQ_PICK && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            LabInstallActivity.startWithUri(this, data.getData());
        }
    }

    private int dp(int v) {
        return LabSkin.dp(this, v);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackground(LabScrim.bar(this));
        nav.setPadding(dp(18), dp(14) + LabSkin.statusBarHeight(this), dp(18), dp(6));
        TextView title = new TextView(this);
        title.setText("添加服务");
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        nav.addView(title);
        root.addView(nav);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(30) + LabSkin.navBarHeight(this));

        body.addView(entryCard("从文件安装", "选择文件", () -> LabInstallActivity.pickFile(this)));
        body.addView(entryCard("粘贴", "粘贴导入", () -> LabPasteImportActivity.start(this)));
        body.addView(linkCard());

        TextView head = new TextView(this);
        head.setText("可安装");
        head.setTextSize(14);
        head.setTextColor(LabSkin.TEXT_DIM);
        head.setBackground(LabScrim.bar(this));
        head.setPadding(dp(18), dp(10), dp(18), dp(8));
        body.addView(head);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(listBox);

        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private interface Tap {
        void run();
    }

    /** 三卡之一：标题＋黄色大钮 */
    private View entryCard(String title, String btnText, Tap tap) {
        LinearLayout card = cardBox();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTextColor(LabSkin.TEXT_MAIN);
        t.getPaint().setFakeBoldText(true);
        t.setPadding(0, 0, 0, dp(12));
        card.addView(t);
        TextView b = bigButton(btnText);
        b.setOnClickListener(v -> tap.run());
        card.addView(b);
        return card;
    }

    /** 链接卡：输入框＋导入钮 */
    private View linkCard() {
        LinearLayout card = cardBox();
        TextView t = new TextView(this);
        t.setText("链接");
        t.setTextSize(18);
        t.setTextColor(LabSkin.TEXT_MAIN);
        t.getPaint().setFakeBoldText(true);
        t.setPadding(0, 0, 0, dp(12));
        card.addView(t);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        linkInput = new EditText(this);
        linkInput.setHint("https://");
        linkInput.setTextSize(15);
        linkInput.setTextColor(LabSkin.TEXT_MAIN);
        linkInput.setHintTextColor(LabSkin.TEXT_DIM);
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkInput.setBackground(LabSkin.cardBackground(this, false));
        linkInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        linkInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(linkInput);

        TextView go = new TextView(this);
        go.setText("导入");
        go.setTextSize(16);
        go.getPaint().setFakeBoldText(true);
        go.setTextColor(LabSkin.ON_YELLOW);
        go.setBackground(LabSkin.buttonYellow(this));
        go.setPadding(dp(28), dp(14), dp(28), dp(14));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(10);
        go.setLayoutParams(glp);
        go.setOnClickListener(v -> {
            String url = linkInput.getText() == null ? "" : linkInput.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                Notify.show("先粘个链接进来");
                return;
            }
            LabPasteImportActivity.startWithUrl(this, url);
        });
        row.addView(go);
        card.addView(row);
        return card;
    }

    private void reload() {
        listBox.removeAllViews();
        List<PkgFile> pkgs = scanLocal();
        if (pkgs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("本机没扫到可装的包，下载 .lab 包丢进下载目录就行");
            empty.setTextSize(13);
            empty.setTextColor(LabSkin.TEXT_DIM);
            empty.setBackground(LabScrim.bar(this));
            empty.setPadding(dp(18), dp(8), dp(18), dp(8));
            listBox.addView(empty);
            return;
        }
        for (PkgFile p : pkgs) {
            listBox.addView(pkgCard(p));
        }
    }

    private static class PkgFile {
        File file;
        String name;
        String id;
        long size;
    }

    /** 本机扫描：下载目录等常见位置的 .lab/.zip，按包说明取名；已装的跳过 */
    private List<PkgFile> scanLocal() {
        List<PkgFile> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<File> dirs = new ArrayList<>();
        dirs.add(new File("/storage/emulated/0/Download"));
        dirs.add(new File(LabPaths.root(this), "Download"));
        dirs.add(new File("/storage/emulated/0/EnvFiles"));
        dirs.add(LabPaths.root(this));
        for (File dir : dirs) {
            File[] files;
            try {
                files = dir.listFiles();
            } catch (Exception e) {
                continue;
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (!n.endsWith(".lab") && !n.endsWith(".zip")) continue;
                String key = f.getAbsolutePath();
                if (!seen.add(key)) continue;
                try {
                    LabManifest m = LabPackageInstaller.peekManifest(f);
                    if (m == null || TextUtils.isEmpty(m.id)) continue;
                    if (LabRegistry.get(this).svc(m.id) != null) continue; // 已装跳过
                    PkgFile p = new PkgFile();
                    p.file = f;
                    p.id = m.id;
                    p.name = TextUtils.isEmpty(m.name) ? f.getName() : m.name;
                    p.size = f.length();
                    out.add(p);
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** 可安装行：名字＋大小＋安装钮；正在装：进度文案＋细进度条 */
    private View pkgCard(PkgFile p) {
        LinearLayout card = cardBox();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView name = new TextView(this);
        name.setText(p.name);
        name.setTextSize(17);
        name.setTextColor(LabSkin.TEXT_MAIN);
        name.getPaint().setFakeBoldText(true);
        left.addView(name);

        TextView sub = new TextView(this);
        boolean busy = installing.contains(p.id);
        sub.setText(busy ? "正在安装…" : fmtSize(p.size));
        sub.setTextSize(14);
        sub.setTextColor(busy ? LabSkin.AMBER : LabSkin.TEXT_DIM);
        sub.setPadding(0, dp(4), 0, 0);
        left.addView(sub);
        row.addView(left);

        if (!busy) {
            TextView btn = new TextView(this);
            btn.setText("安装");
            btn.setTextSize(15);
            btn.getPaint().setFakeBoldText(true);
            btn.setTextColor(LabSkin.ON_YELLOW);
            btn.setBackground(LabSkin.buttonYellow(this));
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(28), dp(12), dp(28), dp(12));
            btn.setMinWidth(dp(96));
            btn.setOnClickListener(v -> installPkg(p));
            row.addView(btn);
        }
        card.addView(row);

        if (busy) {
            ProgressBar bar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
            blp.topMargin = dp(10);
            bar.setLayoutParams(blp);
            card.addView(bar);
        }
        return card;
    }

    private void installPkg(PkgFile p) {
        installing.add(p.id);
        reload();
        LabPackageInstaller.installFromFile(this, p.file, new LabPackageInstaller.Listener() {
            @Override
            public void onProgress(String text) {
            }

            @Override
            public void onOk(LabRegistry.Svc pkg) {
                runOnUiThread(() -> {
                    installing.remove(p.id);
                    Notify.show("装好了");
                    reload();
                });
            }

            @Override
            public void onFail(String reason) {
                runOnUiThread(() -> {
                    installing.remove(p.id);
                    Notify.show(reason);
                    reload();
                });
            }
        });
    }

    private String fmtSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        long mb = bytes / (1024 * 1024);
        long rem = (bytes % (1024 * 1024)) * 10 / (1024 * 1024);
        return mb + (rem > 0 ? "." + rem : "") + " MB";
    }

    private LinearLayout cardBox() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LabSkin.cardBackground(this, false));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), 0, dp(18), dp(14));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView bigButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(17);
        b.getPaint().setFakeBoldText(true);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(LabSkin.ON_YELLOW);
        b.setBackground(LabSkin.buttonYellow(this));
        b.setPadding(0, dp(15), 0, dp(15));
        return b;
    }
}
