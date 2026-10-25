package com.fongmi.android.tv.ui.music;

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
import com.fongmi.android.tv.music.GzLyricMatch;
import com.fongmi.android.tv.music.LyricVersionOrdering;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.databinding.DialogMusicLyricVersionsBinding;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;
import com.fongmi.android.tv.databinding.ItemMusicLyricVersionBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词版本面板（批 2 迁入 ui/music 包替代旧 ui/dialog 实现，行为与回调语义不变；
 * 面板底照现行弹窗口径 bg_sheet_panel（GZ sheetPanelBg #F3F3F3），不再用纯白底）：
 * 标题＋X、头行封面＋歌名歌手＋版本数、
 * 筛选签（全部／逐字／逐行／翻译／音译／文本）、版本行卡（来源＋行数＋歌名·歌手＋类型签＋匹配度％，
 * L9：选中与非选中同底、只用透明度区分（当前 0.95／非当前 0.58），不再粉底红字）。
 * 点行即切换版本（回调播放页 applyLyricVersion）并关面板。
 * 匹配度照瓜子真算法（GzLyricMatch 移植瓜子 sq4.t 分级＋gq4 分数映射＋
 * LyricsCandidate.getMatchPercentText 显示口径）：分数在聚合产出版本时按目标曲与
 * 候选歌的标题／歌手／时长定级算好、随版本带下来，弹窗只显示不重算；未定级与被
 * 瓜子门淘汰（0 级）的版本不显分数，不冒充。行数按带时间戳的行计（不含 [ti:] 等元信息行）。
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
     * 排序：LyricVersionOrdering 口径——对歌级别第一层（瓜子定级优先，错歌的词不许靠
     * 偏好排到对歌的词前面）、逐字／逐行偏好第二层（读他既有「在线歌词优先级」设置，
     * 默认逐字优先：同级里逐字版排在逐行版前面）、星辰级别第三层、末层保收集序。
     */
    private void applyFilter() {
        shown.clear();
        List<LyricVersion> sorted = new ArrayList<>(versions);
        String priority = MusicStore.getLyricPriority();
        sorted.sort((a, b) -> LyricVersionOrdering.compare(a, b, priority));
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

    /** 匹配度显示（瓜子口径）：有瓜子定级且过门才显百分数；未定级／0 级留空，不冒充 */
    private String percentText(LyricVersion v) {
        if (!v.hasGzScore()) return "";
        return GzLyricMatch.percentForLevel(v.getGzLevel()) + "%";
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
            addChip(h, kindTag(v));
            if (v.getLyric() != null) {
                if (hasText(v.getLyric().translation)) addChip(h, getString(R.string.music_primary_translation));
                if (hasText(v.getLyric().romanization)) addChip(h, getString(R.string.music_primary_romanization));
            }
            h.b.tvPct.setText(percentText(v));
            h.b.tvPct.setTextColor(0xFF1A1A1A);
            h.b.root.setOnClickListener(view -> {
                if (onSelect != null) onSelect.onVersionSelected(v);
                dismissAllowingStateLoss();
            });
        }

        /** 类型签文案（瓜子口径，全账三-56：纯文本版只写「文本」）。只改弹窗展示——
         * LyricVersion.kindLabel 本体不许动，它进持久化的手动选版 key（lyricVersionKey），改了会让存量选版失效。 */
        private static String kindTag(com.fongmi.android.tv.bean.music.LyricVersion v) {
            if (v.isWordTimed()) return "逐字";
            if (v.isLineTimed()) return "逐行";
            return "文本";
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
