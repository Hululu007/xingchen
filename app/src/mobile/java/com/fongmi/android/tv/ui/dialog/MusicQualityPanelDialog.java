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
 * 星辰四档对进 GZ 四行：母带→Hi-Res、FLAC→无损、320k→极高、128k→标准。
 * 面板规格文案照 GZ 抄（含位深／赫兹：24bit／96kHz、16bit／44.1kHz · FLAC），与播放页音质格
 * 旧口径“采样率不许显示、无损只显编码名”表面冲突——面板按最新令“跟他一样做”照抄，格子按最新令
 * 改短码，冲突行见对照表（music-quality-note.md），不擅自改。
 * 按歌列档（不许写死 4 行）：只列当前歌／源实际提供的档——落雪取各启用插件声明的 qualitys 并集
 * （桥加载阻塞，走后台线程，算出前先以 4 档占位、算出后刷新）；FreeJS 取条目 qualities 键；
 * 本地／已下载／未知来源无源档信息，保持 4 档（选档仍走请求上限回落，不冒充源档）。
 * 当前档一定在列（源档里没有当前档时把它补进原位，保证选中态可见；属星辰侧规则，已在对照表标明）。
 * 文件大小：星辰源不给分档文件大小，此处按 时长×档位标称码率 估算（标准 128／极高 320／无损 1000／
 * Hi-Res 2500 kbps），不是服务器实测大小；时长取不到时该行保持“读取中”。估算路径走后台线程，
 * 面板先显“读取中”再填数，读取中态是真实异步槽位，不是摆设。
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
        final int kbps;

        Row(int quality, int iconRes, String name, String spec, int kbps) {
            this.quality = quality;
            this.iconRes = iconRes;
            this.name = name;
            this.spec = spec;
            this.kbps = kbps;
        }
    }

    private static final Row[] ROWS = {
            new Row(MusicStore.QUALITY_SUPER, R.drawable.ic_quality_hires, "Hi-Res", "24bit / 96kHz", 2500),
            new Row(MusicStore.QUALITY_HIGH, R.drawable.ic_quality_lossless, "无损", "16bit / 44.1kHz · FLAC", 1000),
            new Row(MusicStore.QUALITY_STANDARD, R.drawable.ic_quality_high, "极高", "320 kbps", 320),
            new Row(MusicStore.QUALITY_LOW, R.drawable.ic_quality_standard, "标准", "128 kbps", 128),
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
            size.setText("读取中");
            size.setTextColor(selected ? RED : GRAY);
            sizeViews.add(size);
        }
        final int quality = r.quality;
        row.setOnClickListener(v -> {
            if (callback != null) callback.onPick(quality);
            dismissAllowingStateLoss();
        });
    }

    /** 星辰档位 → 面板行名（仅面板内显示用，不改选档值与存档）。 */
    private static String gzName(int quality) {
        switch (quality) {
            case MusicStore.QUALITY_SUPER:
                return "Hi-Res";
            case MusicStore.QUALITY_HIGH:
                return "无损";
            case MusicStore.QUALITY_LOW:
                return "标准";
            default:
                return "极高";
        }
    }

    /**
     * 文件大小估算（后台算、主线程填）：时长×档位标称码率。时长取不到（<=0）时不填数，
     * 该行保持“读取中”，不编一个数出来。
     */
    private void loadSizesAsync() {
        if (track == null || track.duration <= 0) return;
        final long durationMs = track.duration;
        final List<Row> rows = shownRows;
        final List<TextView> views = sizeViews;
        Task.execute(() -> {
            final String[] sizes = new String[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                double bytes = durationMs / 1000.0 * rows.get(i).kbps * 1000.0 / 8.0;
                sizes[i] = formatSize(bytes);
            }
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                // 档刷新后旧任务的结果不许写进新行（占位 4 档→实际档时行数／顺序会变）
                if (!isAdded() || sizeViews != views) return;
                for (int i = 0; i < views.size() && i < sizes.length; i++) {
                    if (views.get(i) != null) views.get(i).setText(sizes[i]);
                }
            });
        });
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
