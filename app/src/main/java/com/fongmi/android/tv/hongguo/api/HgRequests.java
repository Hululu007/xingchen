package com.fongmi.android.tv.hongguo.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 红果请求构造：请求体与查询串，照 verify-one-episode-20261027.md 第八节第 1 条
 * （smali 摸全的官方构造）与 lib_hg_lib.js 的 hgApiOnce 口径。
 *
 * <p>要点：mixed_video_id_map 是 Map&lt;Short, List&lt;String&gt;&gt;，键为平台值
 * Mixed=1024；biz_param.video_platform 同为 1024；content_type=1004；
 * dr_scene 开时走 V2 请求体（biz_param / dr_scene / mixed_video_id_map /
 * NovelCommonParam，无 content_type 与 video_id）。</p>
 *
 * <p>设备指纹口径（海阔线实测）：同一指纹连发会被限速，每次请求生成全新指纹。</p>
 */
public final class HgRequests {

    public static final int VIDEO_PLATFORM_MIXED = 1024;
    public static final int VIDEO_PLATFORM_OUTER = 3;
    public static final int CONTENT_TYPE = 1004;

    public static final String UA =
            "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 2509FPN0BC; Build/UQ1A.240205.09211156;tt-ok/3.12.13.20)";

    private static final String QS_TAIL =
            "&ac=wifi&channel=update_64&aid=8662&app_name=novelread&version_code=73932" +
            "&version_name=7.3.9.32&device_platform=android&os=android&ssmix=a" +
            "&device_type=2509FPN0BC&device_brand=XIAOMI&language=zh&os_api=34&os_version=14" +
            "&manifest_version_code=73932&resolution=1080*1920&dpi=480&update_version_code=73932" +
            "&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=73932&compliance_status=0" +
            "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0" +
            "&rom_version=UQ1A.240205.09211156+release-keys";

    private static final SecureRandom RANDOM = new SecureRandom();

    private HgRequests() {
    }

    /** 每次请求一套全新指纹查询串（iid / device_id / _rticket / cdid + 固定尾）。 */
    public static String freshQuery() {
        return "iid=" + rndDigits(15) + "&device_id=" + rndDigits(15)
                + "&_rticket=" + System.currentTimeMillis() + QS_TAIL
                + "&cdid=" + UUID.randomUUID();
    }

    private static String rndDigits(int n) {
        StringBuilder sb = new StringBuilder(n);
        sb.append(1 + RANDOM.nextInt(9));
        for (int i = 1; i < n; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    /** GetVideoBizParam（第八节抄录字段，video_platform 由入参定）。 */
    public static JsonObject bizParam(int videoPlatform) {
        JsonObject b = new JsonObject();
        b.addProperty("detail_page_version", 0);
        b.addProperty("device_level", 3);
        b.addProperty("disable_digg_stat", false);
        b.addProperty("disable_video_relate_book", false);
        b.addProperty("from_video_id", "");
        b.addProperty("need_all_video_definition", true);
        b.addProperty("need_mp4_align", false);
        b.addProperty("use_os_player", false);
        b.addProperty("use_server_dns", true);
        b.addProperty("use_server_dns_scene", "cold_start_stage");
        b.addProperty("video_platform", videoPlatform);
        return b;
    }

    /**
     * NovelCommonParam：官方约 25 字段（设备/应用公共参数）。
     * 本类用有序表承载，默认带应用与设备身份字段；其余字段由上层按需 put，
     * 不写死猜测值。字段清单未在存档材料里逐字定版，见交付回报的未定项。
     */
    public static final class NovelCommonParam {
        private final LinkedHashMap<String, Object> fields = new LinkedHashMap<>();

        public NovelCommonParam() {
            fields.put("aid", 8662);
            fields.put("app_name", "novelread");
            fields.put("version_code", 73932);
            fields.put("version_name", "7.3.9.32");
            fields.put("device_platform", "android");
            fields.put("os", "android");
            fields.put("language", "zh");
            fields.put("channel", "update_64");
        }

        public NovelCommonParam put(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number) o.addProperty(e.getKey(), (Number) v);
                else if (v instanceof Boolean) o.addProperty(e.getKey(), (Boolean) v);
                else if (v != null) o.addProperty(e.getKey(), String.valueOf(v));
            }
            return o;
        }
    }

    /** mixed_video_id_map：{ "1024": [集id...] }（键为 Short 平台值的字符串形态）。 */
    public static JsonObject mixedVideoIdMap(Collection<String> videoIds, int platform) {
        JsonArray ids = new JsonArray();
        for (String id : videoIds) ids.add(id);
        JsonObject map = new JsonObject();
        map.add(String.valueOf(platform), ids);
        return map;
    }

    /** 老口请求体（GetVideoModelRequest）：批量取模型。 */
    public static JsonObject multiVideoModelBody(Collection<String> videoIds, NovelCommonParam common) {
        JsonObject body = new JsonObject();
        body.add("biz_param", bizParam(VIDEO_PLATFORM_MIXED));
        body.add("mixed_video_id_map", mixedVideoIdMap(videoIds, VIDEO_PLATFORM_MIXED));
        if (common != null) body.add("NovelCommonParam", common.toJson());
        body.addProperty("content_type", CONTENT_TYPE);
        if (videoIds.size() == 1) body.addProperty("video_id", videoIds.iterator().next());
        return body;
    }

    /** V2 请求体（MGetVideoModelV2Request，dr_scene 开时）：无 content_type / video_id。 */
    public static JsonObject multiVideoModelV2Body(Collection<String> videoIds, String drScene, NovelCommonParam common) {
        JsonObject body = new JsonObject();
        body.add("biz_param", bizParam(VIDEO_PLATFORM_MIXED));
        body.addProperty("dr_scene", drScene == null || drScene.isEmpty() ? "default" : drScene);
        body.add("mixed_video_id_map", mixedVideoIdMap(videoIds, VIDEO_PLATFORM_MIXED));
        if (common != null) body.add("NovelCommonParam", common.toJson());
        return body;
    }

    /** 单集 video_model 请求体（Outer=3，海阔线实证口径，作批量失败时的单集对照路）。 */
    public static JsonObject singleVideoModelBody(String videoId) {
        JsonObject body = new JsonObject();
        body.add("biz_param", bizParam(VIDEO_PLATFORM_OUTER));
        body.addProperty("content_type", CONTENT_TYPE);
        body.addProperty("video_id", videoId);
        return body;
    }
}
