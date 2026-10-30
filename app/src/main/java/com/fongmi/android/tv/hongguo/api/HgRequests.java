package com.fongmi.android.tv.hongguo.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 红果请求构造（请求体部分）：取址链三体照 openhongguo internal/protocol/request.go
 * 的 PlaybackBody / SeriesBody / SeriesBatchBody 逐字口径（Go map 序列化＝键字典序，
 * 本类按同序插入 Gson 对象，签名覆盖 body MD5、发送字节与签名体一致）。
 *
 * <p>旧 multi 口径（mixed_video_id_map＋platform=1024＋content_type=1004）已在
 * openhongguo-dig-20261028.md 定罪废弃：无签名＋随机假指纹恒回 200 空体；
 * 取址链改走 HgDevice 注册身份＋HgSign 签名（见 HgApiClient.postSigned）。
 * freshQuery 供小说书城等已验链路在用；短剧登录全套已随 2026-10-30
 * 重建删除，本类只留小说与通用 pairs 构造。</p>
 */
public final class HgRequests {

    public static final int VIDEO_PLATFORM_FQ = 3;

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


    
                                        // ---------- bookmall 频道面（feed 三页签，openhongguo request.go 逐字口径） ----------

    /** 频道开页请求 tab_type：推荐走 -1（响应频道 16）；漫剧 36 / 真人剧 39 请求响应同值。 */
    public static final int TAB_REQUEST_RECOMMEND = -1;
    public static final int TAB_RESPONSE_RECOMMEND = 16;
    public static final int TAB_COMIC = 36;
    public static final int TAB_LIVE = 39;
        /** 短剧商城底栏（bottom_tab_type，三频道同值 7）。 */
    public static final String BOTTOM_TAB_SHORT_DRAMA = "7";
    public static final String BOTTOM_TAB_TYPE_LIST = "7,0,1,2,3,4";

    /**
     * 频道开页有序参数（openhongguo SignedDiscoverPairsIn 逐字）：
     * 设备公共参（31 对）＋ tab_type ＋ offset=0 ＋ client_req_type=3 ＋
     * bottom_tab_type ＋ bottom_tab_type_list。ts / _rticket 由签名器末尾追加。
     */
    public static java.util.List<String[]> discoverPairs(int requestTabType) {
        return discoverPairs(requestTabType, BOTTOM_TAB_SHORT_DRAMA);
    }

    /** 书频道变体：bottom_tab_type 用书城的 0（短剧是 7，规格 dig-audio-novel-source §3）。 */
    public static java.util.List<String[]> discoverPairs(int requestTabType, String bottomTabType) {
        java.util.List<String[]> pairs = HgDevice.deviceQueryPairs();
        pairs.add(new String[]{"tab_type", String.valueOf(requestTabType)});
        pairs.add(new String[]{"offset", "0"});
        pairs.add(new String[]{"client_req_type", "3"});
        pairs.add(new String[]{"bottom_tab_type", bottomTabType});
        pairs.add(new String[]{"bottom_tab_type_list", BOTTOM_TAB_TYPE_LIST});
        return pairs;
    }

    /**
     * 频道续页有序参数（openhongguo SignedCellChangePairs 逐字）：
     * 设备公共参 ＋ cell_id ＋ offset ＋ limit=20 ＋ change_type=0 ＋ tab_type
     * （响应频道值）＋ show_type ＋ client_req_type=2 ＋ session_id；
     * client_template 仅在开页回包带了才回传。
     */
    public static java.util.List<String[]> cellChangePairs(
            com.fongmi.android.tv.hongguo.model.HgContinuation cont) {
        java.util.List<String[]> pairs = HgDevice.deviceQueryPairs();
        pairs.add(new String[]{"cell_id", cont.getCellId()});
        pairs.add(new String[]{"offset", cont.getOffset()});
        pairs.add(new String[]{"limit", "20"});
        pairs.add(new String[]{"change_type", "0"});
        pairs.add(new String[]{"tab_type", cont.getTabType()});
        pairs.add(new String[]{"show_type", cont.getShowType()});
        pairs.add(new String[]{"client_req_type", "2"});
        pairs.add(new String[]{"session_id", cont.getSessionId()});
        if (cont.getClientTemplate() != null && !cont.getClientTemplate().isEmpty()) {
            pairs.add(new String[]{"client_template", cont.getClientTemplate()});
        }
        return pairs;
    }

    /**
     * 搜索结果有序参数（openhongguo SignedSearchPairs 逐字，tab=短剧 11）：
     * 设备公共参 ＋ query（参数名是 query 不是 keyword——旧 App 侧 keyword
     * 写法从未定版）＋ tab_type/count/offset ＋ use_correct ＋
     * bookshelf_search_plan ＋ search_source=1 ＋ tab_name=feed ＋
     * bookstore_tab=16 ＋ user_is_login=0。本机探针实证：此形签名 GET 回
     * 63,856 字节真结果；旧无签名＋假指纹＋keyword 形回 200 空体（与取模型
     * 口同一道签名门，2026-10-28 A/B 实证）。
     */
    public static java.util.List<String[]> searchPairs(String keyword) {
        java.util.List<String[]> pairs = HgDevice.searchQueryPairs();
        pairs.add(new String[]{"query", keyword == null ? "" : keyword});
        pairs.add(new String[]{"tab_type", "11"});
        pairs.add(new String[]{"count", "20"});
        pairs.add(new String[]{"offset", "0"});
        pairs.add(new String[]{"use_correct", "true"});
        pairs.add(new String[]{"bookshelf_search_plan", "4"});
        pairs.add(new String[]{"search_source", "1"});
        pairs.add(new String[]{"tab_name", "feed"});
        pairs.add(new String[]{"bookstore_tab", "16"});
        pairs.add(new String[]{"user_is_login", "0"});
        return pairs;
    }

    /** 换页签：同翻页口径再换 tab_type（回包 search tabs 的 tab_type 与请求同键）。 */
    public static java.util.List<String[]> searchPairs(String keyword, int offset, int tabType) {
        java.util.List<String[]> base = searchPairs(keyword, offset);
        java.util.List<String[]> out = new java.util.ArrayList<>(base.size());
        for (String[] kv : base) {
            out.add("tab_type".equals(kv[0])
                    ? new String[]{"tab_type", String.valueOf(tabType)} : kv);
        }
        return out;
    }

    /** 翻页：同 searchPairs、offset 换成已取条数（openhongguo SearchOffset 口径）。 */
    public static java.util.List<String[]> searchPairs(String keyword, int offset) {
        java.util.List<String[]> base = searchPairs(keyword);
        if (offset <= 0) return base;
        java.util.List<String[]> out = new java.util.ArrayList<>(base.size());
        for (String[] kv : base) {
            out.add("offset".equals(kv[0])
                    ? new String[]{"offset", String.valueOf(offset)} : kv);
        }
        return out;
    }
}
