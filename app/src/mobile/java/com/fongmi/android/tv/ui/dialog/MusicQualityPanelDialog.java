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
import com.fongmi.android.tv.ui.music.MusicGlassDrawable;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
import com.fongmi.android.tv.api.music.FreeJsMusicSource;
import com.fongmi.android.tv.api.music.LxBridge;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicAudioInfo;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 播放音质面板（GZ 播放音质面板形态）：标题“播放音质”＋X、歌行（封面＋歌名歌手＋“N 档可选 · 当前 X”）、
 * 档行自上而下（高→低），每行带图标，选中行粉底红字。
 * Q2：行名不在本类硬编码——一律走共享定义 MusicStore.qualityName（Hi-Res／无损／极高／标准，照瓜子面板），
 * 面板与设置等处同源同名，不许两套并存；
 * 副标口径（2026-10-26 他亲定，盖过早先照瓜子全规格的写法）：只写位深＋编码，采样率（赫兹）
 * 在面板同样不许出现（与播放页音质格同一禁令）——Hi-Res 行只写“24bit”，无损行写“16bit · FLAC”，
 * 极高／标准仍写码率。瓜子副标的“24bit 96kHz”那截采样率按此口径砍掉，不许再贴回来。
 * 请求档冒领实测的处置：用户选的请求档（current）与实测归类（actualTier）不一致时，
 * 请求档那一行的副标改写为在播文件的实测规格（“实测 16bit · FLAC”式，位深＋编码、无采样率），
 * 不许让它顶着档位定义副标装作真有该档——《来不及爱你》案：歌本身只有无损 FLAC
 * （平台目录 hr 档不存在、maxBrLevel=lossless），请求 Hi-Res 回来的还是这份 FLAC。
 * 按歌列档（不许写死 4 行）：只列当前歌在其源实际有的档（2026-10-26 深夜他亲定
 * 「音质面板跟着源跑」）——目录证据按真值强度取：①条目自带每档大小键（壳子直连
 * tx／wy／kg 搜索回包的真字段，解析时只在该档真存在时才写入，在＝有档）；
 * ②QQ 详情 file 对象（四档齐全、含 Hi-Res，拉到后以它为准重列，盖过搜索证据）；
 * ③FreeJS 条目 qualities 键；④落雪各启用插件声明的 qualitys 并集（桥加载阻塞、
 * 走后台线程——脚本路能给的“目录”只有声明集，取链时逐脚本 pickQuality 也是照它挑）。
 * kw 的目录走条目 MINFO（真酷我插件 parseKuwoQualityInfo 解析同字段、本机实证；
 * 只有档没有可信字节数，大小保持“未知”）；mg 搜索回包没有本机可验的每档目录
 * 字段（audioFormats 无样本可证，不编），落到④或回落。目录拿不到时不摆 4 档装样子：只列实测在播这一档
 * （还没测到时先列请求档一行占位、无“当前播放”标记，测到即换真行）；
 * 本地／已下载同理只列在播档——磁盘上就这一个文件，摆别的档是骗人。
 * 在播档一定在列（源档里没有在播档时把它补进原位，保证“当前播放”标记可见）。
 * “当前播放”只认实测真值：与播放页音质筹同源——服务从 ExoPlayer 当前 Format 采的
 * MusicAudioInfo（编码／真实码率／采样率／位深），按档归类后落行；实测不到就不标、
 * 头部不写“当前 X”。请求档（用户所选上限）永不进显示：不标行、不进头部（他的死口径；
 * 此前拿请求档标“当前播放”就是面板说谎的根——实际播的是回落后的低档文件）。
 * Q3 文件大小：不再用时长×标称码率估算（1000／2500 标称值作废）——只用源真返回的每档数据：
 * ①源数据直带：QQ／酷狗搜索回包本身带每档字节数，解析时已存进条目 _item（size128/size320/sizeFlac），
 * 面板开即有数；②QQ 详情补：QQ 歌再拉一次歌曲详情 file 对象，补 Hi-Res 档大小与位深规格；
 * ③本地／已下载曲：在播文件就是磁盘上这一个文件，其长度只填在播档行。
 * 原第③层“取链＋HEAD”已删：取链对未声明档会静默回落低档（LxMusicSource.pickQuality），
 * HEAD 到的是回落文件的长度、不是该档的数据——曾把 128k 文件的 3.7MB 贴到 Hi-Res 行。
 * 都没有时该行保持“未知”，不写估算数、不拿别档的数顶。
 * 点行即回选档值并关面板（与 MusicOptionsDialog 同款行为，调用方判是否变化再切档）。
 * 批 4：只换皮——面板／行／图标盒／关闭钮换液态玻璃（bg_music_quality_* 半透明白＋白描边高光，底不许纯白），
 * 四档构成、按歌列档、大小实测与选档回逻辑一字不动；选中态仍粉底红字（与现行一致）。
 */
public class MusicQualityPanelDialog extends BaseBottomSheetDialog {

    public interface OnQualityPick {
        void onPick(int quality);
    }

    private static final int RED = 0xFFFA243C;
    private static final int NAME = 0xFFF1F4F5;

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
            new Row(MusicStore.QUALITY_SUPER, R.drawable.ic_quality_hires, MusicStore.qualityName(MusicStore.QUALITY_SUPER), "24bit"),
            new Row(MusicStore.QUALITY_HIGH, R.drawable.ic_quality_lossless, MusicStore.qualityName(MusicStore.QUALITY_HIGH), "16bit · FLAC"),
            new Row(MusicStore.QUALITY_STANDARD, R.drawable.ic_quality_high, MusicStore.qualityName(MusicStore.QUALITY_STANDARD), "320 kbps"),
            new Row(MusicStore.QUALITY_LOW, R.drawable.ic_quality_standard, MusicStore.qualityName(MusicStore.QUALITY_LOW), "128 kbps"),
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
    /** 请求档（用户所选上限）：只作打开面板的上下文，永不进显示（他的死口径）。 */
    private int current = MusicStore.QUALITY_STANDARD;
    /** 在播档（实测真值归类，-1＝实测不到）：只有它配标“当前播放”与头部“当前 X”。 */
    private int actualTier = -1;
    /** 请求档行的实测注记（请求档与实测归类不一致时贴在请求档行副标处），null＝不注记。 */
    private String measuredSpec;
    /** 副标已被实测注记锁定的行下标（与 shownRows 同序）：QQ 详情规格不许盖掉实测注记。 */
    private boolean[] specLocks = new boolean[0];
    /** 在播实测监听：位深探针异步回填，面板开着时实测一变就重算归类，不许拿打开瞬间的旧值标到底。 */
    private MusicController.Listener audioInfoListener;
    /** 当前歌在其源的实际目录（档集合）：null＝还没拿到／源给不出，行按回落口径列（见 rowsForDisplay）。 */
    private Set<Integer> catalogTiers;
    private OnQualityPick callback;
    private List<Row> shownRows = new ArrayList<>();
    private List<TextView> sizeViews = new ArrayList<>();
    private List<TextView> specViews = new ArrayList<>();

    public static void show(FragmentActivity activity, MusicTrack track, int current, OnQualityPick callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
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
        // 面板底＝全 App 同源液态玻璃（实白底作废）：瓜子 PlayerVersionSheet 顶圆角 28、贴底无缺口
        root.setBackground(new MusicGlassDrawable(inflater.getContext(), 28, true));
        MusicGlassWire.glassifyTree(root); // 面板内件同源接线
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
        MusicAudioInfo info0 = currentAudioInfo();
        actualTier = actualTierOf(info0);
        measuredSpec = measuredSpecOf(info0);
        registerAudioInfoListener();
        initCatalog();
        buildRows(rowsForDisplay());
    }

    /** 订阅在播实测变化：位深探针回填／换歌都会走这里，归类或注记变了就整行重画。 */
    private void registerAudioInfoListener() {
        audioInfoListener = new MusicController.Listener() {
            @Override
            public void onAudioInfoChanged() {
                if (!isAdded() || rowsContainer == null) return;
                MusicAudioInfo info = currentAudioInfo();
                int tier = actualTierOf(info);
                String spec = measuredSpecOf(info);
                if (tier == actualTier && (spec == null ? measuredSpec == null : spec.equals(measuredSpec))) return;
                actualTier = tier;
                measuredSpec = spec;
                if (!shownRows.isEmpty()) buildRows(rowsForDisplay());
            }
        };
        try {
            MusicController.get().addListener(audioInfoListener);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (audioInfoListener != null) {
            try {
                MusicController.get().removeListener(audioInfoListener);
            } catch (Throwable ignored) {
            }
            audioInfoListener = null;
        }
        super.onDismiss(dialog);
    }

    /**
     * 目录定案（同步能算的先算）：条目自带证据（FreeJS qualities 键／直连平台每档
     * 大小键）开面板即有；落雪脚本声明集要加载桥（阻塞），同步算不到走后台补。
     * QQ 详情目录在 loadSizesAsync 里拉到后盖过先行目录（见 applyDetailCatalog）。
     */
    private void initCatalog() {
        catalogTiers = itemCatalogTiers(track);
        if (catalogTiers == null && track != null && LxMusicSource.PLATFORM.equals(track.platform)) {
            Task.execute(() -> {
                Set<Integer> tiers = lxTiers(track);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    // 详情目录已先行定案时不许被声明集盖回去
                    if (!isAdded() || tiers == null || tiers.isEmpty() || catalogTiers != null) return;
                    catalogTiers = tiers;
                    buildRows(rowsForDisplay());
                });
            });
        }
    }

    /** 条目自带目录证据：有就返回档集合，没有返回 null（不许拿别的口径冒充）。 */
    private static Set<Integer> itemCatalogTiers(MusicTrack track) {
        if (track == null) return null;
        if (FreeJsMusicSource.PLATFORM.equals(track.platform)) return freeJsTiers(track);
        return sizeEvidenceTiers(track);
    }

    /**
     * 直连平台目录证据：_item 的每档大小键（size128/size320/sizeFlac/sizeHires）——
     * 解析侧（LxPlatformSearch）只在该档真存在时才写入（&gt;0），键在＝歌有这一档；
     * 另并酷我的 catalogTiers（搜索侧从条目 MINFO 映射好的档集合，MINFO 只有档
     * 没有可信字节数，所以 kw 走这个键而不是大小键）。都没有＝没带目录证据，返回 null。
     */
    private static Set<Integer> sizeEvidenceTiers(MusicTrack track) {
        try {
            if (track == null || TextUtils.isEmpty(track.rawJson)) return null;
            JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
            if (!wrap.has("_item") || !wrap.get("_item").isJsonObject()) return null;
            JsonObject item = wrap.getAsJsonObject("_item");
            Set<Integer> tiers = new LinkedHashSet<>();
            if (itemSizeOf(item, "size128") > 0) tiers.add(MusicStore.QUALITY_LOW);
            if (itemSizeOf(item, "size320") > 0) tiers.add(MusicStore.QUALITY_STANDARD);
            if (itemSizeOf(item, "sizeFlac") > 0) tiers.add(MusicStore.QUALITY_HIGH);
            if (itemSizeOf(item, "sizeHires") > 0) tiers.add(MusicStore.QUALITY_SUPER);
            JsonElement ct = item.get("catalogTiers");
            if (ct != null && ct.isJsonPrimitive()) {
                for (String p : ct.getAsString().split(",")) {
                    try {
                        int q = Integer.parseInt(p.trim());
                        if (q >= MusicStore.QUALITY_LOW && q <= MusicStore.QUALITY_SUPER) tiers.add(q);
                    } catch (Exception ignored) {
                    }
                }
            }
            return tiers.isEmpty() ? null : tiers;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long itemSizeOf(JsonObject item, String key) {
        try {
            JsonElement v = item.get(key);
            if (v == null || !v.isJsonPrimitive()) return -1;
            return v.getAsLong();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 要列的行：有目录按目录列（在播档照旧补进原位）；没目录不摆 4 档装样子——
     * 只列实测在播这一档；实测还没到时先列请求档一行（不标“当前播放”，
     * onAudioInfoChanged 一到就按真值重列）。
     */
    private List<Row> rowsForDisplay() {
        if (catalogTiers != null && !catalogTiers.isEmpty()) return filterRows(catalogTiers);
        int tier = actualTier >= 0 ? actualTier : current;
        List<Row> rows = new ArrayList<>();
        rows.add(rowForTier(tier));
        return rows;
    }

    private static Row rowForTier(int quality) {
        for (Row r : ROWS) if (r.quality == quality) return r;
        return ROWS[1];
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

    /** 按主档顺序（高→低）过滤；在播档不在源档里时补进原位，保证“当前播放”标记可见。 */
    private List<Row> filterRows(Set<Integer> tiers) {
        List<Row> rows = new ArrayList<>();
        for (Row r : ROWS) {
            if (tiers.contains(r.quality) || r.quality == actualTier) rows.add(r);
        }
        return rows.isEmpty() ? allRows() : rows;
    }

    /** 在播真值：与播放页音质筹同源（服务从 ExoPlayer 当前 Format 实测）。 */
    private static MusicAudioInfo currentAudioInfo() {
        try {
            return MusicController.get().audioInfo();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 实测参数 → 在播所属档（阈值与 MusicAudioInfo.shortCode 同口径）：Hi-Res 只认
     * 实测位深 ≥24 且采样率高于 CD 的 44100——44.1kHz 是 CD 采样率，这个采样率的
     * 文件就是无损档，容器位深再高也不是 Hi-Res（瓜子 HIRES 规格 24bit/96kHz、
     * 平台 hr 档同理不设在 44.1k；《来不及爱你》案就是 44.1k FLAC 被冒领 Hi-Res）；
     * 位深没实测到（-1）不许凭采样率单独升 Hi-Res。有损按真实码率分极高（≥192k）
     * ／标准；实测不到（无信息、有损码率未知）返回 -1——面板不标当前播放、
     * 头部不写当前档，请求档不许顶上。
     */
    private static int actualTierOf(MusicAudioInfo info) {
        if (info == null || info.codec == null || info.codec.isEmpty()) return -1;
        if (info.lossless) {
            return (info.bitDepth >= 24 && info.sampleRateHz > 44100)
                    ? MusicStore.QUALITY_SUPER : MusicStore.QUALITY_HIGH;
        }
        if (info.bitrateKbps >= 192) return MusicStore.QUALITY_STANDARD;
        if (info.bitrateKbps > 0) return MusicStore.QUALITY_LOW;
        return -1;
    }

    /**
     * 在播文件的实测规格串（请求档冒领时贴到请求档行）：位深＋编码，无采样率
     * （禁显口径）；位深未知就只写编码。实测不到返回 null（不注记、不编数）。
     */
    private static String measuredSpecOf(MusicAudioInfo info) {
        if (info == null || info.codec == null || info.codec.isEmpty()) return null;
        return "实测 " + (info.bitDepth > 0 ? info.bitDepth + "bit · " : "") + info.codec;
    }

    private void buildRows(List<Row> rows) {
        if (rowsContainer == null || rows == null || rows.isEmpty()) return;
        rowsContainer.removeAllViews();
        shownRows = rows;
        sizeViews = new ArrayList<>();
        specViews = new ArrayList<>();
        specLocks = new boolean[rows.size()];
        LayoutInflater inflater = LayoutInflater.from(rowsContainer.getContext());
        for (int i = 0; i < rows.size(); i++) {
            View row = inflater.inflate(R.layout.item_music_quality_row, rowsContainer, false);
            MusicGlassWire.glassifyView(row, 14f); // 音质行直换玻璃（圆角照原 bg_music_quality_row 的 14dp，不走识别表——弹窗主题换后表查不到就是 1.4.43 白板）
            View iconBox0 = row.findViewById(R.id.qr_icon_box);
            if (iconBox0 != null) MusicGlassWire.glassifyView(iconBox0, 10f); // 图标盒直换玻璃（圆角照原 bg_music_quality_icon 的 10dp）
            if (i > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
                if (lp != null) lp.topMargin = dp(6);
                row.setLayoutParams(lp);
            }
            rowsContainer.addView(row);
            // 请求档与实测归类不一致：请求档行副标改贴实测注记并锁定，不许它顶着档位定义装真档。
            String specOverride = null;
            if (measuredSpec != null && actualTier >= 0 && current != actualTier
                    && rows.get(i).quality == current) {
                specOverride = measuredSpec;
                specLocks[i] = true;
            }
            bindRow(row, rows.get(i), rows.get(i).quality == actualTier, specOverride);
        }
        if (subView != null && track != null) {
            // 头部同标真值：在播档实测不到就不写“当前 X”，请求档不许垫场。
            subView.setText(actualTier >= 0
                    ? rows.size() + " 档可选 · 当前 " + gzName(actualTier)
                    : rows.size() + " 档可选");
        }
        loadSizesAsync();
    }

    /** playing＝该行就是实测在播档：红字红 tone＋亮“当前播放”签，只认真值不认请求档。
     * specOverride＝请求档行的实测注记（非 null 时盖掉档位定义副标）。 */
    private void bindRow(View row, Row r, boolean playing, String specOverride) {
        // 背景归玻璃（buildRows 已 glassify）：这里只切 tone——在播行＝红 tone 玻璃行，
        // 不许再 setBackgroundResource 把白板/粉板盖回来（1.4.42 真机就是这么被盖掉的）。
        if (row.getBackground() instanceof MusicGlassDrawable) {
            MusicGlassDrawable g = (MusicGlassDrawable) row.getBackground();
            if (playing) g.setToneColor(RED, 0.12f); // 瓜子选中底＝Accent 渐变 .12→.04，平面 tone 取 .12 近似
            else g.setToneColor(0, 0f);
        }
        View iconBox = row.findViewById(R.id.qr_icon_box);
        if (iconBox != null && iconBox.getBackground() instanceof MusicGlassDrawable) {
            MusicGlassDrawable g = (MusicGlassDrawable) iconBox.getBackground();
            if (playing) g.setToneColor(RED, 0.13f); // 瓜子选中图标盒底 Accent .13
            else g.setToneColor(0, 0f);
        }
        ImageView icon = row.findViewById(R.id.qr_icon);
        if (icon != null) {
            icon.setImageResource(r.iconRes);
            icon.setImageTintList(ColorStateList.valueOf(playing ? RED : NAME));
        }
        TextView name = row.findViewById(R.id.qr_name);
        if (name != null) {
            name.setText(r.name);
            name.setTextColor(playing ? RED : NAME);
        }
        TextView spec = row.findViewById(R.id.qr_spec);
        if (spec != null) {
            spec.setText(specOverride != null ? specOverride : r.spec);
            spec.setTextColor(playing ? 0xB8FFFFFF : 0x85FFFFFF); // 瓜子 QualityRow 码率色 .72/.52
            specViews.add(spec); // 与 sizeViews 同序登记，供后台按真数据改规格（QQ Hi-Res 位深）
        }
        TextView currentTag = row.findViewById(R.id.qr_current);
        if (currentTag != null) currentTag.setVisibility(playing ? View.VISIBLE : View.GONE);
        TextView size = row.findViewById(R.id.qr_size);
        if (size != null) {
            // Q3：无源真数据前写“未知”，不写估算数；后台按源数据回填。
            size.setText("未知");
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

    /**
     * Q3 文件大小（后台逐档算、到一行填一行）：只填源真返回的每档数据——
     * ①条目 _item 直带的每档字节数；②QQ 详情 file 对象的每档字节数（顺带 Hi-Res 真规格）；
     * ③本地／已下载曲的在播文件长度（只填在播档行）。三层都没有的档保持“未知”。
     * 原“取链＋HEAD”层已删（理由见类头 Q3）：取链对未声明档静默回落，量到的是别档文件的长度。
     * 循环内每档前查存续条件：面板已关／档已刷新就提前退出；旧任务结果不许写进新行。
     */
    private void loadSizesAsync() {
        if (track == null) return;
        final List<Row> rows = shownRows;
        final List<TextView> views = sizeViews;
        final List<TextView> specs = specViews;
        final MusicTrack t = track;
        final int playing = actualTier;
        Task.execute(() -> {
            boolean[] filled = new boolean[rows.size()];
            // 本地／已下载曲：在播文件就是磁盘上这一个文件，长度只填在播档行，其余档没有文件不许顶。
            if (playing >= 0 && t.localPath != null && !t.localPath.isEmpty()) {
                try {
                    long len = new File(t.localPath).length();
                    if (len > 0) {
                        for (int i = 0; i < rows.size(); i++) {
                            if (rows.get(i).quality != playing) continue;
                            filled[i] = true;
                            postSize(views, i, formatSize(len));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // 第一层：源数据直带（QQ／酷狗搜索回包的每档字节数，解析时已存 _item），开面板即填。
            for (int i = 0; i < rows.size(); i++) {
                if (!isAdded() || sizeViews != views) return;
                if (filled[i]) continue;
                long len = sourceSizeOf(t, rows.get(i).quality);
                if (len > 0) {
                    filled[i] = true;
                    postSize(views, i, formatSize(len));
                }
            }
            // 第二层：QQ 详情——先按 file 对象定案目录（见上方），再补 Hi-Res 档大小＋位深规格
            // （其余档大小与第一层同源同值，只补缺）。
            if ("tx".equals(lxPlatformKey(t))) {
                try {
                    String mid = null;
                    if (!TextUtils.isEmpty(t.rawJson)) {
                        JsonObject wrap = JsonParser.parseString(t.rawJson).getAsJsonObject();
                        if (wrap.has("_item") && wrap.get("_item").isJsonObject()) {
                            JsonElement sm = wrap.getAsJsonObject("_item").get("songmid");
                            if (sm != null && !sm.isJsonNull()) mid = sm.getAsString();
                        }
                    }
                    if (mid == null && !TextUtils.isEmpty(t.id)) {
                        String[] parts = t.id.split(":", 3);
                        if (parts.length >= 3) mid = parts[2];
                    }
                    if (mid != null && !mid.isEmpty()) {
                        JsonObject file = com.fongmi.android.tv.api.music.LxPlatformSearch.qqFileInfo(mid);
                        if (file != null) {
                            // 详情目录定案：file 对象四档大小就是这首歌的真实目录（含 Hi-Res），
                            // 以它为准——与现行目录不同就重列并收工，大小回填交给重列后的
                            // 新一轮 loadSizesAsync（新一轮算出的详情目录与现行相同、自然收敛）。
                            final Set<Integer> detail = detailCatalogTiers(file);
                            if (detail != null && !detail.equals(catalogTiers)) {
                                if (getActivity() == null) return;
                                getActivity().runOnUiThread(() -> {
                                    if (isAdded()) applyDetailCatalog(detail);
                                });
                                return;
                            }
                            for (int i = 0; i < rows.size(); i++) {
                                if (!isAdded() || sizeViews != views) return;
                                if (filled[i]) continue;
                                long len = fileSizeOf(file, rows.get(i).quality);
                                if (len > 0) {
                                    filled[i] = true;
                                    postSize(views, i, formatSize(len));
                                }
                            }
                            for (int i = 0; i < rows.size(); i++) {
                                if (rows.get(i).quality != MusicStore.QUALITY_SUPER) continue;
                                String spec = hiresSpecOf(file);
                                if (spec != null) postSpec(specs, views, i, spec);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // 没有第三层：取链会对未声明档静默回落低档文件，HEAD 量到的不是该档数据（见类头 Q3），
            // 源没给每档数据的档就保持“未知”，不许拿回落文件的长度顶。
        });
    }

    private void postSize(List<TextView> views, int idx, String text) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            // 档刷新后旧任务的结果不许写进新行（占位 4 档→实际档时行数／顺序会变）
            if (!isAdded() || sizeViews != views) return;
            if (idx < views.size() && views.get(idx) != null) views.get(idx).setText(text);
        });
    }

    private void postSpec(List<TextView> specs, List<TextView> views, int idx, String text) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || sizeViews != views) return;
            // 实测注记锁定的行不许被源目录规格盖掉（在播实测优先于目录标称）
            if (idx < specLocks.length && specLocks[idx]) return;
            if (idx < specs.size() && specs.get(idx) != null) specs.get(idx).setText(text);
        });
    }

    /** 源数据直带大小：条目 _item 的 size128/size320/sizeFlac/sizeHires（字节），没有返回 -1。 */
    private static long sourceSizeOf(MusicTrack t, int quality) {
        try {
            if (t == null || TextUtils.isEmpty(t.rawJson)) return -1;
            JsonObject wrap = JsonParser.parseString(t.rawJson).getAsJsonObject();
            if (!wrap.has("_item") || !wrap.get("_item").isJsonObject()) return -1;
            JsonObject item = wrap.getAsJsonObject("_item");
            String key;
            switch (quality) {
                case MusicStore.QUALITY_LOW:
                    key = "size128";
                    break;
                case MusicStore.QUALITY_HIGH:
                    key = "sizeFlac";
                    break;
                case MusicStore.QUALITY_SUPER:
                    key = "sizeHires";
                    break;
                default:
                    key = "size320";
                    break;
            }
            JsonElement v = item.get(key);
            if (v == null || !v.isJsonPrimitive()) return -1;
            long len = v.getAsLong();
            return len > 0 ? len : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** QQ 详情 file 对象的每档大小（size_128mp3/size_320mp3/size_flac/size_hires），没有返回 -1。 */
    private static long fileSizeOf(JsonObject file, int quality) {
        try {
            String key;
            switch (quality) {
                case MusicStore.QUALITY_LOW:
                    key = "size_128mp3";
                    break;
                case MusicStore.QUALITY_HIGH:
                    key = "size_flac";
                    break;
                case MusicStore.QUALITY_SUPER:
                    key = "size_hires";
                    break;
                default:
                    key = "size_320mp3";
                    break;
            }
            JsonElement v = file.get(key);
            if (v == null || !v.isJsonPrimitive()) return -1;
            long len = v.getAsLong();
            return len > 0 ? len : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** QQ 详情 file 对象 → 该歌真实目录：四档大小键（size_128mp3/320mp3/flac/hires）&gt;0 的档；
     * 四键全无返回 null（没信息，不许编目录）。 */
    private static Set<Integer> detailCatalogTiers(JsonObject file) {
        if (file == null) return null;
        Set<Integer> tiers = new LinkedHashSet<>();
        if (itemSizeOf(file, "size_128mp3") > 0) tiers.add(MusicStore.QUALITY_LOW);
        if (itemSizeOf(file, "size_320mp3") > 0) tiers.add(MusicStore.QUALITY_STANDARD);
        if (itemSizeOf(file, "size_flac") > 0) tiers.add(MusicStore.QUALITY_HIGH);
        if (itemSizeOf(file, "size_hires") > 0) tiers.add(MusicStore.QUALITY_SUPER);
        return tiers.isEmpty() ? null : tiers;
    }

    /** 详情目录落定（只许主线程调）：与现行目录不同才整行重列。 */
    private void applyDetailCatalog(Set<Integer> detail) {
        if (detail == null || detail.equals(catalogTiers)) return;
        catalogTiers = detail;
        buildRows(rowsForDisplay());
    }

    /** QQ 详情的 Hi-Res 真规格：只取位深（“24bit”式）；采样率不进面板（禁显口径，
     * hires_sample 不再读），位深没有返回 null（不编）。 */
    private static String hiresSpecOf(JsonObject file) {
        try {
            JsonElement b = file.get("hires_bitdepth");
            if (b == null || !b.isJsonPrimitive()) return null;
            long bit = b.getAsLong();
            return bit > 0 ? bit + "bit" : null;
        } catch (Throwable ignored) {
            return null;
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
