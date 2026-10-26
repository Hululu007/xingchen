package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.GzLyricMatch;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.ui.music.MusicGlassDrawable;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词版本面板（瓜子 PlayerVersionSheet 构成）：头「歌词版本」＋关闭、歌行（封面/歌名/歌手/
 * 「N 个版本 · 按匹配度排列」）、筛选签（全部/逐字/逐行/翻译/音译/文本）、版本卡（来源＋行数、
 * 候选歌名·歌手、逐字/翻译/音译标签、匹配度）。数据走 MusicRepository.lyricRichAll 全量聚合，
 * 点选即生效并存手动选择（saveManualLyricChoice，下次同曲恢复）。底走自家液态玻璃（不用白底）。
 * 当前版判定：正文与在播歌词正文一致（同文版本聚合时已去重，只留首个）。
 */
public class MusicLyricVersionsDialog extends BaseBottomSheetDialog {

    public interface OnVersionPick {
        void onPick(LyricVersion version);
    }

    private static final int RED = 0xFFFA243C;
    private static final String[] FILTERS = {"全部", "逐字", "逐行", "翻译", "音译", "文本"};

    private static class Binding implements ViewBinding {
        private final View root;

        Binding(View root) {
            this.root = root;
        }

        @NonNull
        @Override
        public View getRoot() {
            return root;
        }
    }

    private View root;
    private LinearLayout filterBar;
    private LinearLayout rowsContainer;
    private ScrollView scroll;
    private TextView statusView;
    private TextView subView;
    private MusicTrack track;
    private String currentRaw;
    private OnVersionPick callback;
    private List<LyricVersion> all = new ArrayList<>();
    private List<Integer> lineCounts = new ArrayList<>();
    private int filter = 0;
    private boolean loaded;

    public static void show(FragmentActivity activity, MusicTrack track, String currentRaw, OnVersionPick callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || track == null) return;
        MusicLyricVersionsDialog d = new MusicLyricVersionsDialog();
        d.track = track;
        d.currentRaw = currentRaw;
        d.callback = callback;
        d.show(activity.getSupportFragmentManager(), "music_lyric_versions");
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @NonNull
    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        root = inflater.inflate(R.layout.dialog_music_lyric_versions, container, false);
        // 面板底＝全 App 同源液态玻璃：瓜子 PlayerVersionSheet 顶圆角 28、贴底无缺口
        root.setBackground(new MusicGlassDrawable(inflater.getContext(), 28, true));
        MusicGlassWire.glassifyTree(root);
        return new Binding(root);
    }

    @Override
    protected void initView() {
        if (root == null) return;
        View close = root.findViewById(R.id.lv_close);
        if (close != null) close.setOnClickListener(v -> dismissAllowingStateLoss());
        filterBar = root.findViewById(R.id.lv_filter);
        rowsContainer = root.findViewById(R.id.lv_rows);
        scroll = root.findViewById(R.id.lv_scroll);
        statusView = root.findViewById(R.id.lv_status);
        subView = root.findViewById(R.id.lv_sub);
        if (track != null) {
            ImageView cover = root.findViewById(R.id.lv_cover);
            if (cover != null) ImgUtil.loadRounded(track.displayTitle(), track.cover, cover);
            TextView title = root.findViewById(R.id.lv_title);
            if (title != null) title.setText(track.displayTitle());
            TextView artist = root.findViewById(R.id.lv_artist);
            if (artist != null) {
                if (TextUtils.isEmpty(track.artist)) artist.setVisibility(View.GONE);
                else artist.setText(track.artist);
            }
        }
        buildFilter();
        loadVersions();
    }

    private void buildFilter() {
        if (filterBar == null) return;
        filterBar.removeAllViews();
        for (int i = 0; i < FILTERS.length; i++) {
            final int idx = i;
            TextView tab = new TextView(filterBar.getContext());
            tab.setText(FILTERS[i]);
            tab.setTextSize(11);
            tab.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            tab.setLayoutParams(lp);
            tab.setOnClickListener(v -> {
                filter = idx;
                buildFilter();
                renderRows();
            });
            if (i == filter) {
                MusicGlassDrawable g = new MusicGlassDrawable(tab.getContext(), 9);
                g.setToneColor(RED, 0.16f);
                tab.setBackground(g);
                tab.setTextColor(0xFFF1F4F5);
                tab.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                tab.setTextColor(0x99FFFFFF);
            }
            filterBar.addView(tab);
        }
    }

    private void loadVersions() {
        final MusicTrack t = track;
        Task.execute(() -> {
            List<LyricVersion> versions;
            try {
                versions = MusicRepository.lyricRichAll(t, false);
            } catch (Throwable e) {
                versions = null;
            }
            final List<LyricVersion> vs = versions == null ? new ArrayList<>() : versions;
            final List<Integer> counts = new ArrayList<>();
            for (LyricVersion v : vs) {
                int n = 0;
                try {
                    MusicLyric l = v.getLyric();
                    if (l != null && !TextUtils.isEmpty(l.rawLrc)) n = LyricsParser.parseTimed(l.rawLrc).size();
                } catch (Throwable ignored) {
                }
                counts.add(n);
            }
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded() || track != t) return;
                all = vs;
                lineCounts = counts;
                loaded = true;
                if (subView != null) subView.setText(vs.size() + " 个版本 · 按匹配度排列");
                renderRows();
            });
        });
    }

    private boolean passes(LyricVersion v) {
        MusicLyric l = v.getLyric();
        switch (filter) {
            case 1:
                return v.isWordTimed();
            case 2:
                return v.isLineTimed() && !v.isWordTimed();
            case 3:
                return l != null && !TextUtils.isEmpty(l.translation);
            case 4:
                return l != null && !TextUtils.isEmpty(l.romanization);
            case 5:
                return !v.isWordTimed() && !v.isLineTimed();
            default:
                return true;
        }
    }

    private boolean isCurrent(LyricVersion v) {
        if (currentRaw == null || v.getLyric() == null || v.getLyric().rawLrc == null) return false;
        return currentRaw.trim().equals(v.getLyric().rawLrc.trim());
    }

    private void renderRows() {
        if (rowsContainer == null) return;
        rowsContainer.removeAllViews();
        List<LyricVersion> shown = new ArrayList<>();
        List<Integer> shownCounts = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (passes(all.get(i))) {
                shown.add(all.get(i));
                shownCounts.add(i < lineCounts.size() ? lineCounts.get(i) : 0);
            }
        }
        if (!loaded) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText("正在搜索歌词候选");
            scroll.setVisibility(View.GONE);
            return;
        }
        if (shown.isEmpty()) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(all.isEmpty() ? "没有找到可用歌词" : "该类暂无版本，可切换全部查看");
            scroll.setVisibility(View.GONE);
            return;
        }
        statusView.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        // 列表高封顶（约 6 张卡），多了滚动（瓜子列表区 weight 自适应，此处定高近似）
        ViewGroup.LayoutParams slp = scroll.getLayoutParams();
        slp.height = dp(6 * 74);
        scroll.setLayoutParams(slp);
        LayoutInflater inflater = LayoutInflater.from(rowsContainer.getContext());
        for (int i = 0; i < shown.size(); i++) {
            View row = inflater.inflate(R.layout.item_music_lyric_version, rowsContainer, false);
            MusicGlassWire.glassify(row);
            View iconBox0 = row.findViewById(R.id.vr_icon_box);
            if (iconBox0 != null) MusicGlassWire.glassify(iconBox0);
            if (i > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
                if (lp != null) lp.topMargin = dp(6);
                row.setLayoutParams(lp);
            }
            rowsContainer.addView(row);
            bindRow(row, shown.get(i), shownCounts.get(i));
        }
    }

    private void bindRow(View row, LyricVersion v, int lines) {
        boolean selected = isCurrent(v);
        if (row.getBackground() instanceof MusicGlassDrawable) {
            MusicGlassDrawable g = (MusicGlassDrawable) row.getBackground();
            if (selected) g.setToneColor(RED, 0.12f);
            else g.setToneColor(0, 0f);
        }
        View iconBox = row.findViewById(R.id.vr_icon_box);
        if (iconBox != null && iconBox.getBackground() instanceof MusicGlassDrawable) {
            MusicGlassDrawable g = (MusicGlassDrawable) iconBox.getBackground();
            if (selected) g.setToneColor(RED, 0.13f);
            else g.setToneColor(0, 0f);
        }
        ImageView icon = row.findViewById(R.id.vr_icon);
        if (icon != null) {
            icon.setImageResource(v.isWordTimed() ? R.drawable.ic_lyric_wave : R.drawable.ic_music_note);
            icon.setColorFilter(selected ? RED : 0xFFF1F4F5);
        }
        TextView source = row.findViewById(R.id.vr_source);
        if (source != null) source.setText(TextUtils.isEmpty(v.getSource()) ? "歌词" : v.getSource());
        TextView linesView = row.findViewById(R.id.vr_lines);
        if (linesView != null) linesView.setText(lines > 0 ? lines + " 行" : "");
        TextView candidate = row.findViewById(R.id.vr_candidate);
        if (candidate != null) {
            String ct = v.getCandidateTitle();
            String ca = v.getCandidateArtist();
            if (TextUtils.isEmpty(ct) && track != null) {
                ct = track.displayTitle();
                ca = track.displayArtist();
            }
            candidate.setText(TextUtils.isEmpty(ca) ? (ct == null ? "" : ct) : ct + " · " + ca);
        }
        LinearLayout tags = row.findViewById(R.id.vr_tags);
        if (tags != null) {
            tags.removeAllViews();
            addTag(tags, v.kindLabel(), v.isWordTimed());
            MusicLyric l = v.getLyric();
            if (l != null && !TextUtils.isEmpty(l.translation)) addTag(tags, "翻译", false);
            if (l != null && !TextUtils.isEmpty(l.romanization)) addTag(tags, "音译", false);
            if (v.getDurationDeltaSec() > 0) addTag(tags, "偏差 " + v.getDurationDeltaSec() + "s", false);
        }
        TextView percent = row.findViewById(R.id.vr_percent);
        TextView percentLabel = row.findViewById(R.id.vr_percent_label);
        if (percent != null && percentLabel != null) {
            if (v.hasGzScore()) {
                percent.setVisibility(View.VISIBLE);
                percentLabel.setVisibility(View.VISIBLE);
                percent.setText(GzLyricMatch.percentForLevel(v.getGzLevel()) + "%");
                percent.setTextColor(selected ? RED : 0xFFF1F4F5);
            } else {
                percent.setVisibility(View.GONE);
                percentLabel.setVisibility(View.GONE);
            }
        }
        row.setOnClickListener(x -> {
            if (callback != null) callback.onPick(v);
            dismissAllowingStateLoss();
        });
    }

    private void addTag(LinearLayout parent, String text, boolean accent) {
        TextView tag = new TextView(parent.getContext());
        tag.setText(text);
        tag.setTextSize(9);
        tag.setTextColor(accent ? RED : 0xB3FFFFFF);
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        MusicGlassDrawable g = new MusicGlassDrawable(parent.getContext(), 6);
        if (accent) g.setToneColor(RED, 0.14f);
        tag.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(4);
        tag.setLayoutParams(lp);
        parent.addView(tag);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
