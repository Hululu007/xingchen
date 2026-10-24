package com.fongmi.android.tv.ui.dialog;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
import com.fongmi.android.tv.music.MusicStore;
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
 * L9：选中与非选中同底、只用透明度区分（当前 0.95／非当前 0.58），不再粉底红字）。
 * 点行即切换版本（回调播放页 applyLyricVersion）并关面板。
 * 匹配度公式 GZ 未抠到：此处按 [ti:]/[ar:] 标签与当前曲名／歌手比对（两项各 50，无标签按 100），
 * 表里标未对齐，不冒充瓜子公式。行数按带时间戳的行计（不含 [ti:] 等元信息行）。
 * 第三波残留 L9 分工（定死，不两处语义打架）：全屏页内条＝版本切换（在已聚合的版本
 * 间直接切，旧页内条已随播放页重建下线、归批 2 全屏歌词页），本对话框＝类型筛（先按
 * 全部／逐字／逐行／翻译／音译／文本筛候选，再点行选版）；页内不另加类型筛选条，
 * 以免同一页两套筛语义互相覆盖选中态。
 */
public class MusicLyricVersionDialog extends BaseBottomSheetDialog {

    public interface OnVersionSelected {
        void onVersionSelected(LyricVersion version);
    }

    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:]\\d{1,3})?\\]");
    private static final Pattern TITLE_TAG = Pattern.compile("(?m)^\\[ti:(.*?)\\]");
    private static final Pattern ARTIST_TAG = Pattern.compile("(?m)^\\[ar:(.*?)\\]");

    public interface OnSearchLyric {
        void onSearchLyric();
    }

    private DialogMusicLyricVersionsBinding binding;
    private MusicTrack track;
    private List<LyricVersion> versions = new ArrayList<>();
    private String currentKey;
    private OnVersionSelected onSelect;
    private OnSearchLyric onSearch;
    private final List<LyricVersion> shown = new ArrayList<>();
    private final List<TextView> filterChips = new ArrayList<>();
    private VersionAdapter adapter;
    private View filterIndicator;
    private int filter = 0;

    public static void show(FragmentActivity activity, MusicTrack track, List<LyricVersion> versions,
                            String currentKey, OnVersionSelected onSelect) {
        showWithSearch(activity, track, versions, currentKey, onSelect, null);
    }

    /** L9：0／1 版时也允许开面板（空态文案＋搜索入口）；搜索回调可空，空时搜索钮只关面板。 */
    public static void showWithSearch(FragmentActivity activity, MusicTrack track, List<LyricVersion> versions,
                            String currentKey, OnVersionSelected onSelect, OnSearchLyric onSearch) {
        if (activity == null || activity.isFinishing()) return;
        MusicLyricVersionDialog d = new MusicLyricVersionDialog();
        d.track = track;
        if (versions != null) d.versions.addAll(versions);
        d.currentKey = currentKey;
        d.onSelect = onSelect;
        d.onSearch = onSearch;
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
        // L9 空态：0 版时列表区改空态文案＋搜索入口（瓜子“当前音源和其他平台都没有找到可用候选”口径），
        // 不再以“无版本”把用户挡在面板外；1 版时正常列一行。
        if (versions.isEmpty()) {
            binding.rvVersions.setVisibility(View.GONE);
            ViewGroup root = (ViewGroup) binding.getRoot();
            int idx = root.indexOfChild(binding.rvVersions);
            LinearLayout empty = new LinearLayout(getContext());
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            TextView tip = new TextView(getContext());
            tip.setText("当前音源和其他平台都没有找到可用候选");
            tip.setTextSize(13);
            tip.setTextColor(0xFF8A8A8E);
            tip.setGravity(android.view.Gravity.CENTER);
            empty.addView(tip);
            TextView search = new TextView(getContext());
            search.setText("搜索歌词");
            search.setTextSize(14);
            search.setTextColor(0xFF1A1A1A);
            search.setGravity(android.view.Gravity.CENTER);
            search.setPadding(dp(24), dp(10), dp(24), dp(10));
            search.setBackgroundResource(R.drawable.bg_sheet_card);
            search.setOnClickListener(v -> {
                dismissAllowingStateLoss();
                if (onSearch != null) onSearch.onSearchLyric();
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(16);
            empty.addView(search, slp);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(16);
            elp.bottomMargin = dp(8);
            root.addView(empty, idx < 0 ? root.getChildCount() : idx, elp);
        }
        // 列表高钳制：行高按 80dp 估（行 68dp＋12dp 间距），最多半屏高、可滚（GZ 面板露 3 行、余下滚）。
        binding.rvVersions.post(() -> {
            if (!isAdded() || binding == null) return;
            int row = Math.round(80 * getResources().getDisplayMetrics().density);
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
            // L9：筛条 11sp／行高 16sp（API 28+ 直接设行高，低版本以 padding 近似），按压 0.96，不再用红字。
            chip.setTextSize(11);
            if (android.os.Build.VERSION.SDK_INT >= 28) chip.setLineHeight(dp(16));
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            chip.setLayoutParams(lp);
            chip.setOnTouchListener((v, e) -> {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(150).start();
                } else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                        || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                }
                return false;
            });
            chip.setOnClickListener(v -> {
                filter = index;
                paintFilters();
                applyFilter();
            });
            binding.filterBar.addView(chip);
            filterChips.add(chip);
        }
        // L9 滑动指示：宽＝筛条宽／6、高 9dp（对照表值），选中切换时平移动画跟随。
        if (filterIndicator == null) {
            View ind = new View(getContext());
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(4.5f));
            bg.setColor(0xFF1A1A1A);
            ind.setBackground(bg);
            ViewGroup root = (ViewGroup) binding.getRoot();
            int idx = root.indexOfChild(binding.filterBar);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(9));
            ilp.topMargin = dp(4);
            ilp.gravity = android.view.Gravity.START;
            root.addView(ind, idx + 1, ilp);
            filterIndicator = ind;
        }
        paintFilters();
        binding.filterBar.post(() -> {
            if (!isAdded() || filterIndicator == null) return;
            int w = binding.filterBar.getWidth();
            if (w <= 0) return;
            ViewGroup.LayoutParams lp = filterIndicator.getLayoutParams();
            lp.width = w / 6;
            filterIndicator.setLayoutParams(lp);
            filterIndicator.setTranslationX(filter * (w / 6f));
        });
    }

    private void paintFilters() {
        for (int i = 0; i < filterChips.size(); i++) {
            TextView chip = filterChips.get(i);
            boolean sel = i == filter;
            chip.setTextColor(sel ? 0xFF1A1A1A : 0xFF8A8A8E);
            chip.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (filterIndicator != null && binding != null && binding.filterBar != null) {
            int w = binding.filterBar.getWidth();
            if (w > 0) filterIndicator.animate().translationX(filter * (w / 6f)).setDuration(200).start();
        }
    }

    /**
     * 排序：匹配度主序、在线歌词优先级次序。匹配度仍优先（错歌的词不许靠优先级排到对歌的词前面）；
     * 无 [ti:]/[ar:] 标签时匹配度全是 100，次序即优先级序，与 lyricRichAll 的分区一致，面板与
     * 首屏不再各排各的。跟随来源模式下优先级次序全相等，等于只按匹配度排、保全量列表收集序。
     * 逐行＝行时间且无词时间；AMLL 只认 getSource()＝"AMLL"，不判 format（Worker 版 format 是 TTML）。
     */
    private void applyFilter() {
        shown.clear();
        List<LyricVersion> sorted = new ArrayList<>(versions);
        sorted.sort((a, b) -> {
            int byMatch = Integer.compare(matchPercent(b), matchPercent(a));
            if (byMatch != 0) return byMatch;
            return Integer.compare(lyricPriorityRank(a), lyricPriorityRank(b));
        });
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

    /** 在线歌词优先级序位（越小越前；跟随来源全 0，即不参与次序） */
    private int lyricPriorityRank(LyricVersion v) {
        String p = MusicStore.getLyricPriority();
        if (MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE.equals(p)) return 0;
        boolean word = v.isWordTimed();
        boolean line = v.isLineTimed() && !word;
        if (MusicStore.LYRIC_PRIORITY_AMLL_FIRST.equals(p)) {
            if ("AMLL".equals(v.getSource())) return 0;
            if (word) return 1;
            if (line) return 2;
            return 3;
        }
        if (MusicStore.LYRIC_PRIORITY_LINE_BY_LINE.equals(p)) {
            if (line) return 0;
            if (word) return 1;
            return 2;
        }
        if (word) return 0;
        if (line) return 1;
        return 2;
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
            // L9：行不再粉底红字——选中与非选中同底（圆角 10dp，见 bg_version_row），只用透明度区分
            // （当前 0.95／非当前 0.58），图标与百分比同步去红。
            h.b.root.setBackgroundResource(R.drawable.bg_version_row);
            h.b.root.setAlpha(sel ? 0.95f : 0.58f);
            h.b.ivIcon.setBackgroundResource(R.drawable.bg_version_icon);
            h.b.ivIcon.setImageTintList(ColorStateList.valueOf(sel ? 0xFF1A1A1A : 0xFF8A8A8E));
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
            h.b.tvPct.setTextColor(0xFF1A1A1A);
            h.b.root.setOnClickListener(view -> {
                if (onSelect != null) onSelect.onVersionSelected(v);
                dismissAllowingStateLoss();
            });
        }

        private void addChip(Holder h, String label) {
            TextView chip = new TextView(h.itemView.getContext());
            chip.setText(label);
            chip.setTextSize(11);
            chip.setTextColor(0xFF1A1A1A);
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
