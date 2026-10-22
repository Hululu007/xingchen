package com.fongmi.android.tv.ui.dialog;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.FreeJsMusicSource;
import com.fongmi.android.tv.api.music.LxBridge;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 播放音质面板（GZ 播放音质面板形态）：标题“播放音质”＋X、歌行（封面＋歌名歌手＋“N 档可选 · 当前 X”）、
 * 档行自上而下（高→低），每行带图标，选中行粉底红字。
 * Q2：行名与已定四档名统一（母带／FLAC／320k／128k），不再与面板旧名（Hi-Res／无损／极高／标准）两套并存；
 * SUPER 行规格按本批指令为“24bit / 192kHz · FLAC”（不要贴 HIRES 的 96kHz）；FLAC 行规格只写编码名“FLAC”，
 * 采样率（赫兹）按已定口径不另列一行；播放页音质格口径不动（本面板只改面板内行名与规格）。
 * 按歌列档（不许写死 4 行）：只列当前歌／源实际提供的档——落雪取各启用插件声明的 qualitys 并集
 * （桥加载阻塞，走后台线程，算出前先以 4 档占位、算出后刷新）；FreeJS 取条目 qualities 键；
 * 本地／已下载／未知来源无源档信息，保持 4 档（选档仍走请求上限回落，不冒充源档）。
 * 当前档一定在列（源档里没有当前档时把它补进原位，保证选中态可见；属星辰侧规则，已在对照表标明）。
 * Q3 文件大小：不再用时长×标称码率估算（1000／2500 标称值作废）——后台逐档取链得真实地址后 HEAD
 * 取 Content-Length 回填；取不到真实地址／无长度时该行写“未知”，不写估算数、不显示估算列。
 * 点行即回选档值并关面板（与 MusicOptionsDialog 同款行为，调用方判是否变化再切档）。
 */
public class MusicQualityPanelDialog extends BaseBottomSheetDialog {

    public interface OnQualityPick {
        void onPick(int quality);
    }

    private static final int RED = 0xFFFA243C;
    private static final int NAME = 0xFF1C1C1E;
    private static final int GRAY = 0xFF8E8E93;

    private static class Row {
        final int quality;
        final int iconRes;
        final String name;
        final String spec;

        Row(int quality, int iconRes, String name, String spec) {
            this.quality = quality;
            this.iconRes = iconRes;
            this.name = name;
            this.spec = spec;
        }
    }

    private static final Row[] ROWS = {
            new Row(MusicStore.QUALITY_SUPER, R.drawable.ic_quality_hires, "母带", "24bit / 192kHz · FLAC"),
            new Row(MusicStore.QUALITY_HIGH, R.drawable.ic_quality_lossless, "FLAC", "FLAC"),
            new Row(MusicStore.QUALITY_STANDARD, R.drawable.ic_quality_high, "320k", "320 kbps"),
            new Row(MusicStore.QUALITY_LOW, R.drawable.ic_quality_standard, "128k", "128 kbps"),
    };

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
    private LinearLayout rowsContainer;
    private TextView subView;
    private MusicTrack track;
    private int current = MusicStore.QUALITY_STANDARD;
    private OnQualityPick callback;
    private List<Row> shownRows = new ArrayList<>();
    private List<TextView> sizeViews = new ArrayList<>();

    public static void show(FragmentActivity activity, MusicTrack track, int current, OnQualityPick callback) {
        if (activity == null || activity.isFinishing()) return;
        MusicQualityPanelDialog d = new MusicQualityPanelDialog();
        d.track = track;
        d.current = current;
        d.callback = callback;
        d.show(activity.getSupportFragmentManager(), "music_quality_panel");
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @NonNull
    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        root = inflater.inflate(R.layout.dialog_music_quality, container, false);
        return new Binding(root);
    }

    @Override
    protected void initView() {
        if (root == null) return;
        View close = root.findViewById(R.id.qp_close);
        if (close != null) close.setOnClickListener(v -> dismissAllowingStateLoss());
        rowsContainer = root.findViewById(R.id.qp_rows);
        subView = root.findViewById(R.id.qp_sub);
        if (track != null) {
            ImageView cover = root.findViewById(R.id.qp_cover);
            if (cover != null) ImgUtil.loadRounded(track.displayTitle(), track.cover, cover);
            TextView title = root.findViewById(R.id.qp_title);
            if (title != null) title.setText(track.displayTitle());
            TextView artist = root.findViewById(R.id.qp_artist);
            if (artist != null) {
                if (TextUtils.isEmpty(track.artist)) artist.setVisibility(View.GONE);
                else artist.setText(track.artist);
            }
        }
        List<Row> initial = resolveTiersSync();
        if (initial == null) {
            // 落雪需后台算档：先铺 4 档占位、算出后刷新，不空着面板等
            buildRows(allRows());
            resolveTiersAsync();
        } else {
            buildRows(initial);
        }
    }

    /**
     * 同步可算的档：FreeJS（条目 qualities 键）与本地／未知来源（保持 4 档）；
     * 落雪需加载桥（阻塞），返回 null 走后台算。
     */
    private List<Row> resolveTiersSync() {
        if (track == null) return allRows();
        if (LxMusicSource.PLATFORM.equals(track.platform)) return null;
        if (FreeJsMusicSource.PLATFORM.equals(track.platform)) {
            Set<Integer> tiers = freeJsTiers(track);
            if (tiers != null && !tiers.isEmpty()) return filterRows(tiers);
        }
        return allRows();
    }

    private void resolveTiersAsync() {
        Task.execute(() -> {
            Set<Integer> tiers = lxTiers(track);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded() || tiers == null || tiers.isEmpty()) return;
                buildRows(filterRows(tiers));
            });
        });
    }

    /** 落雪：各启用插件声明的 qualitys 并集（桥加载阻塞，只许后台调）。 */
    private static Set<Integer> lxTiers(MusicTrack track) {
        try {
            String key = lxPlatformKey(track);
            if (key == null || key.isEmpty()) return null;
            Set<Integer> tiers = new LinkedHashSet<>();
            List<MusicSite> sites = MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_LX);
            if (sites == null) return null;
            for (MusicSite site : sites) {
                try {
                    LxBridge bridge = MusicSourceManager.get().lxBridgeOf(site);
                    if (bridge == null) continue;
                    LxBridge.LxSource source = bridge.sources().get(key);
                    if (source == null || source.qualitys == null) continue;
                    for (String q : source.qualitys) {
                        MusicQuality mq = MusicQuality.fromSourceKey(q);
                        if (mq != null) tiers.add(mq.toInt());
                    }
                } catch (Throwable ignored) {
                }
            }
            return tiers.isEmpty() ? null : tiers;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** FreeJS：条目 qualities 键（对象键或数组元素）。 */
    private static Set<Integer> freeJsTiers(MusicTrack track) {
        try {
            if (track == null || TextUtils.isEmpty(track.rawJson)) return null;
            JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
            if (!wrap.has("_item") || !wrap.get("_item").isJsonObject()) return null;
            JsonObject item = wrap.getAsJsonObject("_item");
            if (!item.has("qualities") || item.get("qualities").isJsonNull()) return null;
            JsonElement q = item.get("qualities");
            Set<Integer> tiers = new LinkedHashSet<>();
            if (q.isJsonObject()) {
                for (String key : q.getAsJsonObject().keySet()) {
                    JsonElement v = q.getAsJsonObject().get(key);
                    if (v == null || v.isJsonNull()) continue;
                    if (v.isJsonPrimitive() && v.getAsString().isEmpty()) continue;
                    MusicQuality mq = MusicQuality.fromSourceKey(key);
                    if (mq != null) tiers.add(mq.toInt());
                }
            } else if (q.isJsonArray()) {
                for (JsonElement el : q.getAsJsonArray()) {
                    if (el == null || !el.isJsonPrimitive()) continue;
                    MusicQuality mq = MusicQuality.fromSourceKey(el.getAsString());
                    if (mq != null) tiers.add(mq.toInt());
                }
            }
            return tiers.isEmpty() ? null : tiers;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 落雪平台键：rawJson._platform，取不到时从 id（lx:<平台>:<原始 id>）拆。 */
    private static String lxPlatformKey(MusicTrack track) {
        try {
            if (track == null) return null;
            if (!TextUtils.isEmpty(track.rawJson)) {
                JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
                String p = wrap.has("_platform") ? wrap.get("_platform").getAsString() : null;
                if (!TextUtils.isEmpty(p)) return p;
            }
            if (!TextUtils.isEmpty(track.id)) {
                String[] parts = track.id.split(":", 3);
                if (parts.length >= 2) return parts[1];
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static List<Row> allRows() {
        List<Row> rows = new ArrayList<>();
        for (Row r : ROWS) rows.add(r);
        return rows;
    }

    /** 按主档顺序（高→低）过滤；当前档不在源档里时补进原位，保证选中态可见。 */
    private List<Row> filterRows(Set<Integer> tiers) {
        List<Row> rows = new ArrayList<>();
        for (Row r : ROWS) {
            if (tiers.contains(r.quality) || r.quality == current) rows.add(r);
        }
        return rows.isEmpty() ? allRows() : rows;
    }

    private void buildRows(List<Row> rows) {
        if (rowsContainer == null || rows == null || rows.isEmpty()) return;
        rowsContainer.removeAllViews();
        shownRows = rows;
        sizeViews = new ArrayList<>();
        LayoutInflater inflater = LayoutInflater.from(rowsContainer.getContext());
        for (int i = 0; i < rows.size(); i++) {
            View row = inflater.inflate(R.layout.item_music_quality_row, rowsContainer, false);
            if (i > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
                if (lp != null) lp.topMargin = dp(8);
                row.setLayoutParams(lp);
            }
            rowsContainer.addView(row);
            bindRow(row, rows.get(i), rows.get(i).quality == current);
        }
        if (subView != null && track != null) {
            subView.setText(rows.size() + " 档可选 · 当前 " + gzName(current));
        }
        loadSizesAsync();
    }

    private void bindRow(View row, Row r, boolean selected) {
        row.setBackgroundResource(selected ? R.drawable.bg_music_quality_row_sel : R.drawable.bg_music_quality_row);
        View iconBox = row.findViewById(R.id.qr_icon_box);
        if (iconBox != null) {
            iconBox.setBackgroundResource(selected ? R.drawable.bg_music_quality_icon_sel : R.drawable.bg_music_quality_icon);
        }
        ImageView icon = row.findViewById(R.id.qr_icon);
        if (icon != null) {
            icon.setImageResource(r.iconRes);
            icon.setImageTintList(ColorStateList.valueOf(selected ? RED : NAME));
        }
        TextView name = row.findViewById(R.id.qr_name);
        if (name != null) {
            name.setText(r.name);
            name.setTextColor(selected ? RED : NAME);
        }
        TextView spec = row.findViewById(R.id.qr_spec);
        if (spec != null) spec.setText(r.spec);
        TextView size = row.findViewById(R.id.qr_size);
        if (size != null) {
            // Q3：无实测前写“未知”，不写估算数；后台 HEAD 到长度后再回填。
            size.setText("未知");
            size.setTextColor(selected ? RED : GRAY);
            sizeViews.add(size);
        }
        final int quality = r.quality;
        row.setOnClickListener(v -> {
            if (callback != null) callback.onPick(quality);
            dismissAllowingStateLoss();
        });
    }

    /** Q2：面板行名＝已定四档名（MusicStore.qualityName），与选档／存档同名，不再两套并存。 */
    private static String gzName(int quality) {
        return MusicStore.qualityName(quality);
    }

    /** 必-7：HEAD 复用单例客户端（连接池复用，不许每档新建；落雪单档总超时 20 秒＋HEAD 5 秒，串行已够久）。 */
    private static final okhttp3.OkHttpClient HEAD_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /**
     * Q3 文件大小实测（后台逐档算、到一行填一行）：每档经仓库取链得真实地址，
     * 有地址时 HEAD 取 Content-Length 回填；取不到地址／无长度／HEAD 失败时保持“未知”。
     * 注意：取链走探链口径（probe＝true，只读地址、不记死），面板探链偶发失败不许污染
     * 同曲真实点播；仅读地址、不改播放状态。串行不并行（避免与探链通道冲突）；
     * 循环内每档前查存续条件：面板已关／档已刷新就提前退出，不再继续探链（旧口径只靠
     * isAdded 拦写 UI，关闭后 Task 照跑、4 档串行最坏远超可接受等待）。
     * 面板关闭或档刷新后旧任务结果不许写进新行。
     */
    private void loadSizesAsync() {
        if (track == null) return;
        final List<Row> rows = shownRows;
        final List<TextView> views = sizeViews;
        final MusicTrack t = track;
        Task.execute(() -> {
            for (int i = 0; i < rows.size(); i++) {
                // 必-7：关闭／刷新后提前退出，不再继续探链（不只拦写 UI）。
                if (!isAdded() || sizeViews != views) return;
                final int idx = i;
                String label = null;
                try {
                    String url = com.fongmi.android.tv.music.MusicRepository.playUrl(
                            t, rows.get(i).quality, false, true);
                    if (!isAdded() || sizeViews != views) return;
                    if (url != null && !url.isEmpty()) {
                        long len = headContentLength(url);
                        if (len > 0) label = formatSize(len);
                    }
                } catch (Throwable ignored) {
                }
                final String text = label;
                if (text == null || getActivity() == null) continue;
                getActivity().runOnUiThread(() -> {
                    // 档刷新后旧任务的结果不许写进新行（占位 4 档→实际档时行数／顺序会变）
                    if (!isAdded() || sizeViews != views) return;
                    if (idx < views.size() && views.get(idx) != null) views.get(idx).setText(text);
                });
            }
        });
    }

    /** HEAD 取 Content-Length（跟随跳转，5 秒超时，复用单例客户端）；无长度／失败返回 -1。 */
    private static long headContentLength(String url) {
        okhttp3.OkHttpClient client = HEAD_CLIENT;
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).head().build();
        try (okhttp3.Response resp = client.newCall(req).execute()) {
            if (resp == null || !resp.isSuccessful()) return -1;
            String len = resp.header("Content-Length");
            if (len == null) return -1;
            return Long.parseLong(len.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String formatSize(double bytes) {
        double mb = bytes / 1000000.0;
        if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%d KB", Math.max(1, Math.round(bytes / 1000.0)));
    }

    private int dp(int v) {
        if (getContext() == null) return v;
        return Math.round(v * getContext().getResources().getDisplayMetrics().density);
    }
}
