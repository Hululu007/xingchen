package com.fongmi.android.tv.ui.dialog;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.DialogMusicLyricVersionsBinding;
import com.fongmi.android.tv.databinding.ItemMusicLyricVersionBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词版本面板（第 13 项，GZ 歌词版本底卡形态）：标题＋X、头行封面＋歌名歌手＋版本数、
 * 筛选签（全部／逐字／逐行／翻译／音译／文本）、版本行卡（来源＋行数＋歌名·歌手＋类型签＋匹配度％，
 * 选中行粉底＋红色百分比）。点行即切换版本（回调播放页 applyLyricVersion）并关面板。
 * 匹配度公式 GZ 未抠到：此处按 [ti:]/[ar:] 标签与当前曲名／歌手比对（两项各 50，无标签按 100），
 * 表里标未对齐，不冒充瓜子公式。行数按带时间戳的行计（不含 [ti:] 等元信息行）。
 */
public class MusicLyricVersionDialog extends BaseBottomSheetDialog {

    public interface OnVersionSelected {
        void onVersionSelected(LyricVersion version);
    }

    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:]\\d{1,3})?\\]");
    private static final Pattern TITLE_TAG = Pattern.compile("(?m)^\\[ti:(.*?)\\]");
    private static final Pattern ARTIST_TAG = Pattern.compile("(?m)^\\[ar:(.*?)\\]");

    private DialogMusicLyricVersionsBinding binding;
    private MusicTrack track;
    private List<LyricVersion> versions = new ArrayList<>();
    private String currentKey;
    private OnVersionSelected onSelect;
    private final List<LyricVersion> shown = new ArrayList<>();
    private final List<TextView> filterChips = new ArrayList<>();
    private VersionAdapter adapter;
    private int filter = 0;

    public static void show(FragmentActivity activity, MusicTrack track, List<LyricVersion> versions,
                            String currentKey, OnVersionSelected onSelect) {
        if (activity == null || activity.isFinishing()) return;
        MusicLyricVersionDialog d = new MusicLyricVersionDialog();
        d.track = track;
        if (versions != null) d.versions.addAll(versions);
        d.currentKey = currentKey;
        d.onSelect = onSelect;
        d.show(activity.getSupportFragmentManager(), "music_lyric_versions");
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @NonNull
    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicLyricVersionsBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.btnClose.setOnClickListener(v -> dismissAllowingStateLoss());
        if (track != null) {
            ImgUtil.loadRounded(track.displayTitle(), track.cover, binding.ivCover);
            binding.tvSong.setText(track.displayTitle());
            binding.tvArtist.setText(track.displayArtist());
        }
        binding.tvCount.setText(versions.size() + " 个版本 · 按匹配度排列");
        buildFilters();
        binding.rvVersions.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new VersionAdapter();
        binding.rvVersions.setAdapter(adapter);
        applyFilter();
        // 列表高钳制：行高按 108dp 估（行卡＋12dp 间距），最多半屏高、可滚（GZ 面板露 3 行、余下滚）。
        binding.rvVersions.post(() -> {
            if (!isAdded() || binding == null) return;
            int row = Math.round(108 * getResources().getDisplayMetrics().density);
            int want = shown.size() * row;
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.5f);
            ViewGroup.LayoutParams lp = binding.rvVersions.getLayoutParams();
            lp.height = Math.min(want, max);
            binding.rvVersions.setLayoutParams(lp);
        });
    }

    private void buildFilters() {
        int[] labels = {R.string.music_all, R.string.music_lyric_chip_word, R.string.music_lyric_chip_line,
                R.string.music_primary_translation, R.string.music_primary_romanization,
                R.string.music_lyric_chip_plain};
        binding.filterBar.removeAllViews();
        filterChips.clear();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = new TextView(getContext());
            chip.setText(labels[i]);
            chip.setTextSize(14);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                filter = index;
                paintFilters();
                applyFilter();
            });
            binding.filterBar.addView(chip);
            filterChips.add(chip);
        }
        paintFilters();
    }

    private void paintFilters() {
        for (int i = 0; i < filterChips.size(); i++) {
            TextView chip = filterChips.get(i);
            boolean sel = i == filter;
            chip.setTextColor(sel ? 0xFFFA243C : 0xFF8A8A8E);
            chip.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void applyFilter() {
        shown.clear();
        List<LyricVersion> sorted = new ArrayList<>(versions);
        sorted.sort((a, b) -> Integer.compare(matchPercent(b), matchPercent(a)));
        for (LyricVersion v : sorted) {
            if (filter == 0) shown.add(v);
            else if (filter == 1 && v.isWordTimed()) shown.add(v);
            else if (filter == 2 && v.isLineTimed() && !v.isWordTimed()) shown.add(v);
            else if (filter == 3 && hasText(v.getLyric() == null ? null : v.getLyric().translation)) shown.add(v);
            else if (filter == 4 && hasText(v.getLyric() == null ? null : v.getLyric().romanization)) shown.add(v);
            else if (filter == 5 && !v.isWordTimed() && !v.isLineTimed()) shown.add(v);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private boolean isSelected(LyricVersion v) {
        if (currentKey == null || v.getLyric() == null || v.getLyric().rawLrc == null) return false;
        return currentKey.equals(v.getLyric().rawLrc.trim());
    }

    private int lineCount(LyricVersion v) {
        if (v.getLyric() == null || v.getLyric().rawLrc == null) return 0;
        int n = 0;
        Matcher m = TIME_TAG.matcher(v.getLyric().rawLrc);
        while (m.find()) n++;
        return n;
    }

    private int matchPercent(LyricVersion v) {
        if (track == null) return 100;
        if (v.getLyric() == null || v.getLyric().rawLrc == null) return 100;
        String raw = v.getLyric().rawLrc;
        String ti = tagValue(TITLE_TAG, raw);
        String ar = tagValue(ARTIST_TAG, raw);
        if (ti == null && ar == null) return 100;
        int score = 0;
        if (ti != null && containsIgnoreCase(ti, track.displayTitle())) score += 50;
        if (ar != null && containsIgnoreCase(ar, track.displayArtist())) score += 50;
        return score;
    }

    private static String tagValue(Pattern p, String raw) {
        Matcher m = p.matcher(raw);
        if (!m.find()) return null;
        String s = m.group(1);
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean containsIgnoreCase(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        String x = a.toLowerCase();
        String y = b.toLowerCase();
        return x.contains(y) || y.contains(x);
    }

    private static boolean hasText(String s) {
        return !TextUtils.isEmpty(s) && !s.trim().isEmpty();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemMusicLyricVersionBinding b = ItemMusicLyricVersionBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new Holder(b);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            LyricVersion v = shown.get(position);
            boolean sel = isSelected(v);
            h.b.root.setBackgroundResource(sel ? R.drawable.bg_version_row_sel : R.drawable.bg_version_row);
            h.b.ivIcon.setBackgroundResource(sel ? R.drawable.bg_version_icon_sel : R.drawable.bg_version_icon);
            h.b.ivIcon.setImageTintList(ColorStateList.valueOf(sel ? 0xFFFA243C : 0xFF8A8A8E));
            String source = v.getSource();
            h.b.tvSource.setText(source == null || source.isEmpty() ? "" : source);
            h.b.tvLines.setText(lineCount(v) + " 行");
            if (track != null) {
                h.b.tvSong.setText(track.displayTitle() + " · " + track.displayArtist());
            }
            h.b.chipRow.removeAllViews();
            addChip(h, v.kindLabel());
            if (v.getLyric() != null) {
                if (hasText(v.getLyric().translation)) addChip(h, getString(R.string.music_primary_translation));
                if (hasText(v.getLyric().romanization)) addChip(h, getString(R.string.music_primary_romanization));
            }
            h.b.tvPct.setText(matchPercent(v) + "%");
            h.b.tvPct.setTextColor(sel ? 0xFFFA243C : 0xFF1A1A1A);
            h.b.root.setOnClickListener(view -> {
                if (onSelect != null) onSelect.onVersionSelected(v);
                dismissAllowingStateLoss();
            });
        }

        private void addChip(Holder h, String label) {
            TextView chip = new TextView(h.itemView.getContext());
            chip.setText(label);
            chip.setTextSize(11);
            chip.setTextColor(0xFFFA243C);
            chip.setBackgroundResource(R.drawable.bg_version_chip);
            chip.setPadding(dp(6), dp(3), dp(6), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            chip.setLayoutParams(lp);
            h.b.chipRow.addView(chip);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemMusicLyricVersionBinding b;

            Holder(ItemMusicLyricVersionBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}
