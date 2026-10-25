package com.fongmi.android.tv.ui.music;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicLibrary;

import java.util.List;

/** 加到歌单选择器（工具面板／队列共用）：深色玻璃风底板，歌单行＋新建歌单。 */
public final class MusicPlaylistPicker {

    private MusicPlaylistPicker() {
    }

    public static void show(AppCompatActivity activity, MusicTrack track) {
        if (activity == null || track == null) return;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (16 * density), (int) (12 * density), (int) (16 * density), (int) (16 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1C1E);
        bg.setCornerRadii(new float[]{16 * density, 16 * density, 16 * density, 16 * density, 0, 0, 0, 0});
        root.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("添加到歌单");
        title.setTextColor(0xFFF2F2F7);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * density)));

        ScrollView scroll = new ScrollView(activity);
        LinearLayout listBox = new LinearLayout(activity);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        Runnable load = () -> MusicLibrary.playlists(new MusicLibrary.DataCallback<List<MusicPlaylist>>() {
            @Override
            public void onResult(List<MusicPlaylist> sheets) {
                listBox.removeAllViews();
                // 新建歌单行
                TextView create = row(activity, "＋ 新建歌单", null);
                create.setOnClickListener(v -> showCreate(activity, track, dialog));
                listBox.addView(create);
                if (sheets != null) {
                    for (MusicPlaylist sheet : sheets) {
                        TextView row = row(activity, sheet.getName(), null);
                        row.setOnClickListener(v -> MusicLibrary.addToPlaylist(sheet.getId(), track,
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
                                }));
                        listBox.addView(row);
                    }
                }
            }
        });
        load.run();
        dialog.show();
    }

    private static TextView row(AppCompatActivity activity, String text, String sub) {
        float density = activity.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFFF2F2F7);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding((int) (16 * density), 0, (int) (16 * density), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2C2C2E);
        bg.setCornerRadius(16 * density);
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (56 * density));
        lp.topMargin = (int) (8 * density);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static void showCreate(AppCompatActivity activity, MusicTrack track, Dialog parent) {
        float density = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * density), (int) (16 * density), (int) (20 * density), (int) (16 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF22C2C2E);
        bg.setCornerRadius(14 * density);
        box.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("新建歌单");
        title.setTextColor(0xFFF2F2F7);
        title.setTextSize(16);
        box.addView(title);

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFFF2F2F7);
        input.setHintTextColor(0x668E8E93);
        input.setHint("歌单名");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * density));
        ilp.topMargin = (int) (12 * density);
        box.addView(input, ilp);

        TextView ok = new TextView(activity);
        ok.setText("创建并添加");
        ok.setTextColor(0xFFFFFFFF);
        ok.setTextSize(14);
        ok.setGravity(Gravity.CENTER);
        GradientDrawable obg = new GradientDrawable();
        obg.setColor(0xFFFA243C);
        obg.setCornerRadius(8 * density);
        ok.setBackground(obg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * density));
        olp.topMargin = (int) (12 * density);
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
                                Toast.makeText(activity, "已添加", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    dialog.dismiss();
                    parent.dismiss();
                }
            });
        });

        dialog.setContentView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (300 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }
}
