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
import com.fongmi.android.tv.music.LyricVersionsCache;
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
 * 候选歌名·歌手、逐字/翻译/音译标签、匹配度）。数据走 MusicRepository.lyricVersions
 * （共用层 LyricVersionsCache：同歌缓存＋在飞合并＋全量总时限收口，底层仍是阶段一 Worker
 * ＋阶段二 lyricRichAll 全量聚合），点选即生效并存手动选择（saveManualLyricChoice，下次同曲恢复）。底走自家液态玻璃（不用白底）。
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
    /** 在用词条（正在播的那份词本身）：聚合没回来/回空时面板的保底行，聚合有同文版时以聚合版为准 */
    private LyricVersion seedVersion;
    private OnVersionPick callback;
    private List<LyricVersion> all = new ArrayList<>();
    private List<Integer> lineCounts = new ArrayList<>();
    private int filter = 0;
    private boolean loaded;
    /** 阶段二（全量聚合）是否已收口：空态文案据此区分「还在找」与「找完为空＋真因」 */
    private boolean loadDone;

    public static void show(FragmentActivity activity, MusicTrack track, MusicLyric currentLyric, OnVersionPick callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || track == null) return;
        MusicLyricVersionsDialog d = new MusicLyricVersionsDialog();
        d.track = track;
        d.currentRaw = currentLyric == null ? null : currentLyric.rawLrc;
        // 在用词条种子：displayedVersion 是仓库里备好的「在播词即一个版本」口径，此前全 App
        // 零调用——面板只拿 currentRaw 标当前行，聚合链慢/空时播着词面板照样空（真机实证）。
        String seedName = currentLyric == null ? null : currentLyric.sourceName;
        if ((seedName == null || seedName.isEmpty())) {
            seedName = MusicRepository.sourceDisplayName(track.platform);
        }
        d.seedVersion = MusicRepository.displayedVersion(currentLyric, seedName);
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
        if (filterBar != null) MusicGlassWire.glassifyView(filterBar, 14f); // 筛选条直换玻璃（圆角照 bg_music_glass_seg 的 14dp，不走树查表——截图里它也是白条）
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
        // 种子先上桌：不等任何网络链，打开即有在播的那一份；后续阶段发布按同文去重合并。
        if (seedVersion != null) {
            List<LyricVersion> seed = new ArrayList<>();
            seed.add(seedVersion);
            publishVersions(track, seed, false);
        }
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

    /** 本次打开已见的阶段一最佳列表（全量比它还少时不回退丢版本，与旧两阶段口径一致） */
    private volatile List<LyricVersion> partialBest;

    private void loadVersions() {
        final MusicTrack t = track;
        // 取数走仓库共用层（LyricVersionsCache）：同歌已结算直接回账、在飞只登记等结算、
        // 阶段二有总时限收口——不再每次打开都从头发一整轮全量聚合（他点名「总是重复加载」）。
        // 阶段一/结算的发布口径不变：阶段一回来先上桌，全量收完替换为全量结果。
        Task.execute(() -> MusicRepository.lyricVersions(t, false, new LyricVersionsCache.Callback() {
            @Override
            public void onPartial(List<LyricVersion> vs) {
                if (vs != null && !vs.isEmpty()) {
                    partialBest = vs;
                    publishVersions(t, vs, false);
                } else {
                    publishStageFallback(t);
                }
            }

            @Override
            public void onSettled(List<LyricVersion> vs, boolean complete) {
                List<LyricVersion> use = vs == null ? new ArrayList<>() : vs;
                List<LyricVersion> first = partialBest;
                if (first != null && use.size() < first.size()) use = first;
                publishVersions(t, use, true);
            }
        }));
    }

    /** 阶段一（主来源 Worker）没回来时的中间态：把状态说清，别与「还在搜」同形无信息，
     * 免得主来源慢/堵时面板看着像死在「正在搜索歌词候选」（1.4.44 真机实证）。 */
    private void publishStageFallback(final MusicTrack t) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || track != t || loaded) return;
            if (subView != null) subView.setText("主来源没回来，正在从其它来源找");
            if (statusView != null) statusView.setText("正在从其它来源找歌词");
        });
    }

    private void publishVersions(final MusicTrack t, final List<LyricVersion> vs0, final boolean done) {
        // 在用词条合并：聚合结果里没有同文版本时种子保留（它就是在播的那份，是真版本）；
        // 有同文版以聚合版为准（带真实定级），不重复列。
        List<LyricVersion> vs = vs0;
        if (seedVersion != null && seedVersion.getLyric() != null && seedVersion.getLyric().rawLrc != null) {
            String seedKey = seedVersion.getLyric().rawLrc.trim();
            boolean hasSame = false;
            for (LyricVersion v : vs) {
                if (v.getLyric() != null && v.getLyric().rawLrc != null
                        && seedKey.equals(v.getLyric().rawLrc.trim())) {
                    hasSame = true;
                    break;
                }
            }
            if (!hasSame) {
                vs = new ArrayList<>(vs.size() + 1);
                vs.add(seedVersion);
                vs.addAll(vs0);
            }
        }
        final List<LyricVersion> merged = vs;
        final List<Integer> counts = new ArrayList<>();
        for (LyricVersion v : merged) {
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
            all = merged;
            lineCounts = counts;
            loaded = true;
            loadDone = done;
            if (subView != null) subView.setText(done
                    ? merged.size() + " 个版本 · 按匹配度排列"
                    : "已找到 " + merged.size() + " 个版本 · 继续搜索中");
            renderRows();
        });
    }

    /** 全量收口后仍为空：把主来源的结局分类带出来，不再只剩一句无信息的「没有找到」——
     * 他下次打开面板看状态行就能定案（连接失败/应答异常/主来源无此歌版本）。 */
    private String emptyText() {
        if (!loadDone) return "没有找到可用歌词";
        String note = MusicRepository.lastCfNote();
        if (com.fongmi.android.tv.api.music.CfLyricClient.NOTE_TRANSPORT.equals(note)) {
            return "没有找到可用歌词（主来源连接失败）";
        }
        if (com.fongmi.android.tv.api.music.CfLyricClient.NOTE_NON_JSON.equals(note)) {
            return "没有找到可用歌词（主来源应答异常）";
        }
        if (com.fongmi.android.tv.api.music.CfLyricClient.NOTE_OK.equals(note)) {
            return "没有找到可用歌词（主来源暂无此歌版本）";
        }
        return "没有找到可用歌词";
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
            statusView.setText(all.isEmpty() ? emptyText() : "该类暂无版本，可切换全部查看");
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
            MusicGlassWire.glassifyView(row, 14f); // 版本行直换玻璃（圆角照 bg_music_quality_row 的 14dp，不走识别表——与音质行同病）
            View iconBox0 = row.findViewById(R.id.vr_icon_box);
            if (iconBox0 != null) MusicGlassWire.glassifyView(iconBox0, 10f); // 图标盒直换玻璃（圆角照 bg_music_quality_icon 的 10dp）
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
