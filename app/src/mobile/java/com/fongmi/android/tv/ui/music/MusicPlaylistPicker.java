package com.fongmi.android.tv.ui.music;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicLibrary;

import java.util.List;

/**
 * 加到歌单面板（瓜子 AddToPlaylistSheet 规格，数值照抠值全表第十八节）：
 * 横内边距 16、底内边距 10；头「添加到歌单（N 首）」17sp；
 * 提示 11sp sheetTextLow、内边距 (0,12,0,10)；新建行盒 52、圆角 16、红渐变、图标 28、
 * 「新建歌单」18sp/800/字距 −0.3；输入框高 48、圆角 14、字 14sp；
 * 列表区最大高 320；歌单行高 56、横内边距 16、封面 36 圆角 10。
 * 星辰口径：不放介绍文字；材料按全玻璃口径——底板同源液态玻璃（仅顶角 16）、
 * 歌单行一行一框、输入框玻璃；瓜子的深色实底只借版式数值、不借实底（2026-10-26 全扫改）。
 */
public final class MusicPlaylistPicker {

    private static final int TEXT_HIGH = 0xFFF2F2F7;
    private static final int TEXT_LOW = 0x99FFFFFF;
    private static final int ACCENT = 0xFFFA243C;

    private MusicPlaylistPicker() {
    }

    private static int dp(AppCompatActivity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    public static void show(AppCompatActivity activity, MusicTrack track) {
        if (activity == null || track == null) return;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 10));
        // 面板根底换同源液态玻璃（仅顶角 16，与其它音乐面板同款）：原是瓜子深色实底平铺，
        // 在全玻璃口径下是整块实底漏网（2026-10-26 全扫）。
        root.setBackground(new MusicGlassDrawable(activity, 16, true));

        // 头：标题居中（带首数）＋右侧 × 关（他的铁律：不放返回箭头，× 可以）
        FrameLayout header = new FrameLayout(activity);
        TextView title = new TextView(activity);
        title.setText("添加到歌单（1 首）");
        title.setTextColor(TEXT_HIGH);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.3f / 17f);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44)));
        TextView close = new TextView(activity);
        close.setText("×");
        close.setTextColor(TEXT_HIGH);
        close.setTextSize(18);
        close.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(activity, 40), dp(activity, 40));
        clp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        header.addView(close, clp);
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(header);

        // 新建歌单行（点开变输入区）；瓜子原面板有一行 11sp 提示位，星辰不放介绍文字、不摆
        LinearLayout createBox = new LinearLayout(activity);
        createBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cblp.topMargin = dp(activity, 12);
        root.addView(createBox, cblp);
        showCreateRow(activity, createBox, track, dialog);

        // 歌单列表区：不再套实底盒（原 #2C2C2E 整盒），行各自一框（见 playlistRow），容器只作纵向排布
        ScrollView scroll = new ScrollView(activity);
        LinearLayout listBox = new LinearLayout(activity);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(activity, 12);
        scroll.setLayoutParams(slp);
        root.addView(scroll);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        MusicLibrary.playlists(new MusicLibrary.DataCallback<List<MusicPlaylist>>() {
            @Override
            public void onResult(List<MusicPlaylist> sheets) {
                listBox.removeAllViews();
                if (sheets == null) return;
                boolean first = true;
                for (MusicPlaylist sheet : sheets) {
                    if (!first) listBox.addView(divider(activity));
                    first = false;
                    listBox.addView(playlistRow(activity, sheet, v ->
                            MusicLibrary.addToPlaylist(sheet.getId(), track, 
                                    new MusicLibrary.DataCallback<Boolean>() {
                                        @Override
                                        public void onResult(Boolean ok) {
                                            Toast.makeText(activity, Boolean.TRUE.equals(ok) ? "已添加" : "添加失败", Toast.LENGTH_SHORT).show();
                                            dialog.dismiss();
                                        }

                                        @Override
                                        public void onError(Throwable t) {
                                            Toast.makeText(activity, "添加失败", Toast.LENGTH_SHORT).show();
                                        }
                                    })));
                }
                // 列表框最大高 320dp（瓜子规格）
                scroll.post(() -> {
                    if (listBox.getHeight() > dp(activity, 320)) {
                        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                        lp.height = dp(activity, 320);
                        scroll.setLayoutParams(lp);
                    }
                });
            }
        });
        MusicGlassWire.glassifyTree(root); // 整树接线：给 ScrollView 挂滚动钩子，行玻璃滚动时跟着重裁
        dialog.show();
    }

    /** 行间垫高：一行一框后以 8dp 间距代替分隔线（与选项行 4+4 的行距口径一致）。 */
    private static View divider(AppCompatActivity a) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 8)));
        return v;
    }

    private static LinearLayout playlistRow(AppCompatActivity a, MusicPlaylist sheet, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 56)));
        row.setOnClickListener(onClick);

        FrameLayout cover = new FrameLayout(a);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0x1F8E8E93);
        cg.setCornerRadius(dp(a, 10));
        cover.setBackground(cg);
        TextView glyph = new TextView(a);
        glyph.setText("♪");
        glyph.setTextColor(TEXT_HIGH);
        glyph.setTextSize(16);
        glyph.setGravity(Gravity.CENTER);
        cover.addView(glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(cover, new LinearLayout.LayoutParams(dp(a, 36), dp(a, 36)));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(a);
        name.setText(sheet.getName());
        name.setTextColor(TEXT_HIGH);
        name.setTextSize(15);
        name.setMaxLines(1);
        texts.addView(name);
        if (sheet.trackCount >= 0) {
            TextView count = new TextView(a);
            count.setText(sheet.trackCount + " 首");
            count.setTextColor(TEXT_LOW);
            count.setTextSize(12);
            texts.addView(count);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(a, 12);
        row.addView(texts, tlp);
        MusicGlassWire.frameRow(row); // 一行一框：与其它音乐列表行同口径
        return row;
    }

    private static void showCreateRow(AppCompatActivity a, LinearLayout box, MusicTrack track, Dialog dialog) {
        box.removeAllViews();
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT, 0x38FA243C});
        g.setCornerRadius(dp(a, 16));
        row.setBackground(g);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52)));
        TextView plus = new TextView(a);
        plus.setText("＋");
        plus.setTextColor(0xFFFFFFFF);
        plus.setTextSize(20);
        plus.setGravity(Gravity.CENTER);
        row.addView(plus, new LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)));
        TextView label = new TextView(a);
        label.setText("新建歌单");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(18);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(-0.3f / 18f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = dp(a, 10);
        row.addView(label, llp);
        row.setOnClickListener(v -> showCreateInput(a, box, track, dialog));
        box.addView(row);
    }

    private static void showCreateInput(AppCompatActivity a, LinearLayout box, MusicTrack track, Dialog dialog) {
        box.removeAllViews();
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(TEXT_HIGH);
        input.setHintTextColor(0x99FFFFFF);
        input.setHint("歌单名");
        input.setTextSize(14);
        input.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        MusicGlassWire.glassifyView(input, 14f); // 输入框同换玻璃（原是白 0.10 平铺）
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48)));

        TextView ok = new TextView(a);
        ok.setText("创建并添加");
        ok.setTextColor(0xFFFFFFFF);
        ok.setTextSize(14);
        ok.setGravity(Gravity.CENTER);
        GradientDrawable obg = new GradientDrawable();
        obg.setColor(ACCENT);
        obg.setCornerRadius(dp(a, 8));
        ok.setBackground(obg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 44));
        olp.topMargin = dp(a, 10);
        box.addView(ok, olp);
        ok.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) return;
            MusicLibrary.createPlaylist(name, new MusicLibrary.DataCallback<MusicPlaylist>() {
                @Override
                public void onResult(MusicPlaylist sheet) {
                    if (sheet != null) {
                        MusicLibrary.addToPlaylist(sheet.getId(), track, new MusicLibrary.DataCallback<Boolean>() {
                            @Override
                            public void onResult(Boolean added) {
                                Toast.makeText(a, "已添加", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    dialog.dismiss();
                }
            });
        });
    }
}
