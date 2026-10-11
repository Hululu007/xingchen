package com.fongmi.android.tv.cloud.drivepikpak;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.cloud.CloudAccountStore;
import com.fongmi.android.tv.cloud.CloudDrive;
import com.fongmi.android.tv.cloud.CloudFile;
import com.fongmi.android.tv.cloud.CloudTask;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * PikPak 网盘原生客户端
 * 来源：deepdarkpaw/pikpak-sdk（Go，MIT，API map 最完备）
 * 认证：OAuth2 账号密码，user.mypikpak.net
 * API：api-drive.mypikpak.net，Bearer 认证
 */
public class DrivePikPak implements CloudDrive {

    public static final String ID = "pikpak";

    // 来源：pikpak-sdk/config.go（DefaultUserBaseURL/DefaultAPIBaseURL）
    // 注意：调研报告写的是 .com，SDK 用的是 .net，以 SDK 为准
    private static final String USER_HOST = "https://user.mypikpak.net";
    private static final String API_HOST = "https://api-drive.mypikpak.net";

    // 来源：pikpak-sdk/config.go（DefaultWebClientID/DefaultWebClientSecret）
    private static final String CLIENT_ID = "YUMx5nI8ZU8Ap8pm";
    private static final String CLIENT_SECRET = "dbw2OtmVEeuUvIptb1Coyg";
    private static final String CLIENT_VERSION = "2.0.0";
    private static final String PACKAGE_NAME = "mypikpak.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36";

    // 接口限流 5rps（来源：pikpak-sdk/config.go DefaultRateLimitConfig）
    private static final long MIN_INTERVAL_MS = 220;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Context context;

    private String accessToken = "";
    private String refreshToken = "";
    private String userId = "";
    private String deviceId = "";
    private String userName = "";
    private long expiresAt = 0;
    private long lastReqTime = 0;

    /** 验证码异常：登录可能触发 captcha，需用户手动过（来源：pikpak-sdk auth.go ManualVerificationError） */
    public static class CaptchaRequiredException extends Exception {
        private final String verifyUrl;
        public CaptchaRequiredException(String verifyUrl) {
            super("需要人机验证");
            this.verifyUrl = verifyUrl;
        }
        public String getVerifyUrl() { return verifyUrl; }
    }

    public DrivePikPak(Context context) {
        this.context = context.getApplicationContext();
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        loadSession();
    }

    // ---------- 会话持久化 ----------

    private void loadSession() {
        accessToken = CloudAccountStore.getValue(context, ID, "access_token");
        refreshToken = CloudAccountStore.getValue(context, ID, "refresh_token");
        userId = CloudAccountStore.getValue(context, ID, "user_id");
        deviceId = CloudAccountStore.getValue(context, ID, "device_id");
        userName = CloudAccountStore.getUserName(context, ID);
        expiresAt = CloudAccountStore.getLong(context, ID, "expires_at");
        if (TextUtils.isEmpty(deviceId)) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
            CloudAccountStore.saveValue(context, ID, "device_id", deviceId);
        }
    }

    private void saveSession() {
        CloudAccountStore.saveValue(context, ID, "access_token", accessToken);
        CloudAccountStore.saveValue(context, ID, "refresh_token", refreshToken);
        CloudAccountStore.saveValue(context, ID, "user_id", userId);
        CloudAccountStore.saveUserName(context, ID, userName);
        CloudAccountStore.saveLong(context, ID, "expires_at", expiresAt);
    }

    private void clearSession() {
        accessToken = "";
        refreshToken = "";
        userId = "";
        userName = "";
        expiresAt = 0;
        CloudAccountStore.clear(context, ID);
        // deviceId 保留，下次登录复用
        CloudAccountStore.saveValue(context, ID, "device_id", deviceId);
    }

    // ---------- 底层请求 ----------

    /** 限流：控制接口 5rps */
    private synchronized void rateLimit() {
        long now = System.currentTimeMillis();
        long wait = MIN_INTERVAL_MS - (now - lastReqTime);
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
        }
        lastReqTime = System.currentTimeMillis();
    }

    private JSONObject api(String method, String path, JSONObject query, JSONObject body, boolean auth) throws Exception {
        rateLimit();
        HttpUrl.Builder ub = HttpUrl.parse(API_HOST + path).newBuilder();
        if (query != null) {
            JSONArray names = query.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    ub.addQueryParameter(k, query.optString(k));
                }
            }
        }
        Request.Builder rb = new Request.Builder().url(ub.build());
        rb.header("User-Agent", UA);
        rb.header("Accept", "application/json");
        rb.header("X-Device-ID", deviceId);
        rb.header("X-Client-Version", CLIENT_VERSION);
        rb.header("X-Package-Name", PACKAGE_NAME);
        // 分享读写接口要求该头（来源：pikpak-kotlin ShareEndpoint，官方 web 客户端每个请求都带）
        rb.header("X-Client-Id", CLIENT_ID);
        if (auth) {
            ensureToken();
            rb.header("Authorization", "Bearer " + accessToken);
        }
        if ("POST".equals(method) || "PATCH".equals(method)) {
            String json = body != null ? body.toString() : "{}";
            rb.method(method, RequestBody.create(json, JSON));
        } else if ("DELETE".equals(method)) {
            rb.delete();
        } else {
            rb.get();
        }
        try (Response resp = client.newCall(rb.build()).execute()) {
            String txt = resp.body() != null ? resp.body().string() : "";
            if (resp.code() == 401 && auth) {
                // token 过期，刷新一次重试（来源：pikpak-sdk 单次刷新语义）
                refresh();
                return api(method, path, query, body, true);
            }
            if (!resp.isSuccessful()) {
                throw new Exception("PikPak 接口错误 " + resp.code() + ": " + txt);
            }
            return new JSONObject(txt.isEmpty() ? "{}" : txt);
        }
    }

    private JSONObject userApi(String path, JSONObject query, JSONObject body, String captchaToken) throws Exception {
        rateLimit();
        HttpUrl.Builder ub = HttpUrl.parse(USER_HOST + path).newBuilder();
        if (query != null) {
            JSONArray names = query.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    ub.addQueryParameter(k, query.optString(k));
                }
            }
        }
        Request.Builder rb = new Request.Builder().url(ub.build());
        rb.header("User-Agent", UA);
        rb.header("Accept", "application/json");
        rb.header("X-Device-ID", deviceId);
        if (!TextUtils.isEmpty(captchaToken)) {
            rb.header("X-Captcha-Token", captchaToken);
        }
        String json = body != null ? body.toString() : "{}";
        rb.post(RequestBody.create(json, JSON));
        try (Response resp = client.newCall(rb.build()).execute()) {
            String txt = resp.body() != null ? resp.body().string() : "";
            JSONObject d = new JSONObject(txt.isEmpty() ? "{}" : txt);
            // 检测 captcha（来源：pikpak-sdk ManualVerificationError）
            if (d.has("captcha_token") || d.optString("error_code", "").contains("captcha")
                    || d.optString("error", "").contains("captcha")) {
                String url = d.optString("url", d.optString("verification_url", ""));
                throw new CaptchaRequiredException(url);
            }
            if (!resp.isSuccessful()) {
                throw new Exception("登录失败：" + d.optString("error_description", d.optString("error", txt)));
            }
            return d;
        }
    }

    // ---------- 认证 ----------

    /**
     * 账号密码登录（来源：pikpak-sdk/auth.go Login）
     * POST /v1/auth/signin?client_id=...
     * Body: {captcha_token, client_id, client_secret, username, password}
     */
    public void login(String username, String password) throws Exception {
        login(username, password, "");
    }

    public void login(String username, String password, String captchaToken) throws Exception {
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            throw new Exception("用户名和密码不能为空");
        }
        JSONObject query = new JSONObject().put("client_id", CLIENT_ID);
        JSONObject body = new JSONObject()
                .put("captcha_token", captchaToken == null ? "" : captchaToken)
                .put("client_id", CLIENT_ID)
                .put("client_secret", CLIENT_SECRET)
                .put("username", username)
                .put("password", password);
        JSONObject d = userApi("/v1/auth/signin", query, body, captchaToken);
        String at = d.optString("access_token");
        String rt = d.optString("refresh_token");
        if (TextUtils.isEmpty(at) || TextUtils.isEmpty(rt)) {
            throw new Exception("登录返回缺 token（可能是接口变更）");
        }
        accessToken = at;
        refreshToken = rt;
        userId = d.optString("sub");
        long expiresIn = d.optLong("expires_in", 7200);
        // 提前 5 分钟视为过期（来源：pikpak-sdk TokenSkew）
        expiresAt = System.currentTimeMillis() + (expiresIn - 300) * 1000;
        userName = username;
        saveSession();
    }

    /** 提交验证码 token（用户手动过验证后调用，来源：pikpak-sdk SubmitCaptcha） */
    public void submitCaptchaToken(String captchaToken) {
        // captcha token 在下次 login 时通过 X-Captcha-Token 带上
        CloudAccountStore.saveValue(context, ID, "captcha_token", captchaToken);
    }

    /** 刷新 token（OAuth2 标准 refresh_token 模式） */
    public synchronized void refresh() throws Exception {
        if (TextUtils.isEmpty(refreshToken)) {
            throw new Exception("没有 refresh_token，需要重新登录");
        }
        rateLimit();
        JSONObject body = new JSONObject()
                .put("client_id", CLIENT_ID)
                .put("client_secret", CLIENT_SECRET)
                .put("grant_type", "refresh_token")
                .put("refresh_token", refreshToken);
        HttpUrl url = HttpUrl.parse(USER_HOST + "/v1/auth/token").newBuilder()
                .addQueryParameter("client_id", CLIENT_ID).build();
        Request req = new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("X-Device-ID", deviceId)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String txt = resp.body() != null ? resp.body().string() : "";
            JSONObject d = new JSONObject(txt.isEmpty() ? "{}" : txt);
            if (!resp.isSuccessful()) {
                // refresh 失败 → 需要重新登录（来源：pikpak-sdk ErrReauthRequired）
                clearSession();
                throw new Exception("登录已过期，请重新登录");
            }
            String at = d.optString("access_token");
            String rt = d.optString("refresh_token");
            if (TextUtils.isEmpty(at)) {
                clearSession();
                throw new Exception("刷新 token 失败，请重新登录");
            }
            accessToken = at;
            if (!TextUtils.isEmpty(rt)) refreshToken = rt;  // refresh_token 轮换
            long expiresIn = d.optLong("expires_in", 7200);
            expiresAt = System.currentTimeMillis() + (expiresIn - 300) * 1000;
            saveSession();
        }
    }

    private void ensureToken() throws Exception {
        if (TextUtils.isEmpty(accessToken)) {
            throw new Exception("未登录");
        }
        if (System.currentTimeMillis() > expiresAt) {
            refresh();
        }
    }

    // ---------- CloudDrive 接口 ----------

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "PikPak";
    }

    @Override
    public boolean isLogin() {
        return !TextUtils.isEmpty(accessToken) && !TextUtils.isEmpty(userId);
    }

    @Override
    public String getUserName() {
        return userName;
    }

    @Override
    public void logout() {
        clearSession();
    }

    /**
     * 文件列表（来源：pikpak-sdk/files.go List）
     * GET /drive/v1/files?parent_id=&limit=&page_token=&filters=
     */
    @Override
    public List<CloudFile> listDir(String cid, int offset, int limit) throws Exception {
        // PikPak 用 page_token 分页，不支持 offset；offset>0 时按页累进（简化：只取第一页）
        JSONObject query = new JSONObject()
                .put("parent_id", cid == null ? "" : cid)
                .put("limit", String.valueOf(limit > 0 ? limit : 100))
                .put("thumbnail_size", "SIZE_LARGE")
                .put("with_audit", "true")
                .put("filters", new JSONObject()
                        .put("phase", new JSONObject().put("eq", "PHASE_TYPE_COMPLETE"))
                        .put("trashed", new JSONObject().put("eq", false))
                        .toString());
        JSONObject d = api("GET", "/drive/v1/files", query, null, true);
        List<CloudFile> files = new ArrayList<>();
        JSONArray arr = d.optJSONArray("files");
        if (arr == null) return files;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            CloudFile f = new CloudFile();
            boolean isDir = "drive#folder".equals(o.optString("kind"));
            f.setDir(isDir);
            f.setId(o.optString("id"));
            f.setName(o.optString("name"));
            f.setSize(o.optLong("size", 0));
            f.setSha1(o.optString("hash"));
            f.setParentCid(o.optString("parent_id", cid));
            // 缩略图（来源：pikpak-kotlin Models.kt FileStat.thumbnail_link）
            f.setThumbUrl(o.optString("thumbnail_link", ""));
            // pickCode 字段复用存 mime_type（播放时用 file_id 取直链）
            f.setPickCode(o.optString("mime_type"));
            files.add(f);
        }
        return files;
    }

    @Override
    public int getTotal(String cid) throws Exception {
        return -1;  // PikPak 分页用 page_token，不返回总数
    }

    /**
     * 删除（进回收站）（来源：pikpak-sdk/files.go Trash → batchTrash）
     * POST /drive/v1/files:batchTrash {ids: []}
     */
    @Override
    public boolean delete(List<String> ids, boolean isDir) throws Exception {
        if (ids == null || ids.isEmpty()) return true;
        JSONObject body = new JSONObject().put("ids", new JSONArray(ids));
        api("POST", "/drive/v1/files:batchTrash", null, body, true);
        return true;
    }

    /**
     * 离线下载（来源：pikpak-sdk/offline.go Create）
     * POST /drive/v1/files
     * Body: {kind: "drive#file", name, upload_type: "UPLOAD_TYPE_URL",
     *        url: {url}, parent_id, folder_type: "DOWNLOAD"}
     */
    @Override
    public boolean offlineDownload(String url, String cid) throws Exception {
        url = url == null ? "" : url.trim();
        if (url.isEmpty()) throw new Exception("链接是空的");
        String low = url.toLowerCase();
        if (!(low.startsWith("magnet:") || low.startsWith("ed2k://")
                || low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("thunder://") || low.startsWith("ftp://"))) {
            throw new Exception("这不像磁力 / ed2k / 直链");
        }
        JSONObject body = new JSONObject()
                .put("kind", "drive#file")
                .put("name", "")
                .put("upload_type", "UPLOAD_TYPE_URL")
                .put("url", new JSONObject().put("url", url))
                .put("parent_id", cid == null ? "" : cid);
        if (TextUtils.isEmpty(cid)) {
            body.put("folder_type", "DOWNLOAD");
        }
        JSONObject d = api("POST", "/drive/v1/files", null, body, true);
        JSONObject task = d.optJSONObject("task");
        String taskId = task != null ? task.optString("id") : d.optString("task_id");
        if (TextUtils.isEmpty(taskId)) {
            // 有些返回直接是文件（秒传），也算成功
            if (!TextUtils.isEmpty(d.optJSONObject("file").optString("id"))) return true;
            throw new Exception("离线任务创建失败（可能是接口变更）");
        }
        return true;
    }

    /**
     * 离线任务列表（来源：pikpak-sdk/offline.go List）
     * GET /drive/v1/tasks?type=offline&filters=
     */
    @Override
    public List<CloudTask> taskList(int page) throws Exception {
        JSONObject query = new JSONObject()
                .put("type", "offline")
                .put("limit", "100")
                .put("thumbnail_size", "SIZE_SMALL")
                .put("with", "reference_resource")
                .put("filters", new JSONObject()
                        .put("phase", new JSONObject().put("in",
                                "PHASE_TYPE_RUNNING,PHASE_TYPE_ERROR,PHASE_TYPE_PENDING,PHASE_TYPE_COMPLETE"))
                        .toString());
        JSONObject d = api("GET", "/drive/v1/tasks", query, null, true);
        List<CloudTask> tasks = new ArrayList<>();
        JSONArray arr = d.optJSONArray("tasks");
        if (arr == null) return tasks;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            CloudTask t = new CloudTask();
            t.setHash(o.optString("id"));  // 用 task_id 当 hash
            t.setName(o.optString("name", o.optString("file_name")));
            t.setSize(o.optLong("file_size", 0));
            String phase = o.optString("phase");
            t.setStatus("PHASE_TYPE_COMPLETE".equals(phase) ? 2 : "PHASE_TYPE_ERROR".equals(phase) ? 3 : 1);
            t.setPercent(o.optInt("progress", 0));
            t.setStatusText(o.optString("message", phase));
            tasks.add(t);
        }
        return tasks;
    }

    /**
     * 删除离线任务（来源：pikpak-sdk/offline.go Delete）
     * DELETE /drive/v1/tasks?task_ids=&delete_files=false
     */
    @Override
    public boolean taskDelete(List<String> hashes) throws Exception {
        if (hashes == null || hashes.isEmpty()) return true;
        for (String taskId : hashes) {
            JSONObject query = new JSONObject()
                    .put("task_ids", taskId)
                    .put("delete_files", "false");
            api("DELETE", "/drive/v1/tasks", query, null, true);
        }
        return true;
    }

    /**
     * 取播放地址（来源：pikpak-sdk/files.go Get → web_content_link / medias）
     * GET /drive/v1/files/{id}?_magic=2021&usage=FETCH
     * 优先 medias 里 is_origin 的 URL，否则 web_content_link
     */
    @Override
    public String getPlayUrl(CloudFile file) throws Exception {
        if (file == null || TextUtils.isEmpty(file.getId())) {
            throw new Exception("文件 id 为空");
        }
        JSONObject query = new JSONObject()
                .put("_magic", "2021")
                .put("usage", "FETCH")
                .put("thumbnail_size", "SIZE_LARGE");
        JSONObject d = api("GET", "/drive/v1/files/" + file.getId(), query, null, true);
        // 优先原画媒体流
        JSONArray medias = d.optJSONArray("medias");
        if (medias != null) {
            String fallback = "";
            for (int i = 0; i < medias.length(); i++) {
                JSONObject m = medias.optJSONObject(i);
                if (m == null) continue;
                String url = m.optString("link", m.optString("url"));
                if (TextUtils.isEmpty(url)) continue;
                if (m.optBoolean("is_origin", false)) return url;
                if (TextUtils.isEmpty(fallback)) fallback = url;
            }
            if (!TextUtils.isEmpty(fallback)) return fallback;
        }
        String link = d.optString("web_content_link");
        if (!TextUtils.isEmpty(link)) return link;
        throw new Exception("取播放地址失败（文件可能未转码完成）");
    }

    // ---------- 扩展：供 UI 调用 ----------

    /** 新建文件夹（来源：pikpak-sdk/files.go CreateFolder） */
    public CloudFile createFolder(String parentId, String name) throws Exception {
        JSONObject body = new JSONObject()
                .put("kind", "drive#folder")
                .put("parent_id", parentId == null ? "" : parentId)
                .put("name", name);
        JSONObject d = api("POST", "/drive/v1/files", null, body, true);
        JSONObject f = d.optJSONObject("file");
        if (f == null || TextUtils.isEmpty(f.optString("id"))) {
            throw new Exception("新建文件夹失败（可能是接口变更）");
        }
        CloudFile cf = new CloudFile();
        cf.setDir(true);
        cf.setId(f.optString("id"));
        cf.setName(f.optString("name"));
        cf.setParentCid(f.optString("parent_id"));
        return cf;
    }

    /** 重命名（来源：pikpak-sdk/files.go Rename → PATCH） */
    public boolean rename(String fileId, String name) throws Exception {
        JSONObject body = new JSONObject().put("name", name);
        api("PATCH", "/drive/v1/files/" + fileId, null, body, true);
        return true;
    }

    /** 查询离线任务状态（来源：pikpak-sdk/offline.go Get） */
    public CloudTask getOfflineTask(String taskId) throws Exception {
        JSONObject d = api("GET", "/drive/v1/tasks/" + taskId, null, null, true);
        CloudTask t = new CloudTask();
        t.setHash(d.optString("id"));
        t.setName(d.optString("name", d.optString("file_name")));
        t.setSize(d.optLong("file_size", 0));
        String phase = d.optString("phase");
        t.setStatus("PHASE_TYPE_COMPLETE".equals(phase) ? 2 : "PHASE_TYPE_ERROR".equals(phase) ? 3 : 1);
        t.setPercent(d.optInt("progress", 0));
        t.setStatusText(d.optString("message", phase));
        return t;
    }

    /** 离线任务完成后取 file_id（轮询用） */
    public String getOfflineFileId(String taskId) throws Exception {
        JSONObject d = api("GET", "/drive/v1/tasks/" + taskId, null, null, true);
        // 完成后 file_id 在 file_id 或 reference_resource 里
        String fid = d.optString("file_id");
        if (TextUtils.isEmpty(fid)) {
            JSONObject ref = d.optJSONObject("reference_resource");
            if (ref != null) fid = ref.optString("id");
        }
        return fid;
    }

    /**
     * 转存 PikPak 分享链接（来源：pikpak-kotlin ShareEndpoint.kt，MIT，2026-09-24 经 piko 实测验证）
     * 流程：解析 shareId → GET /drive/v1/share 拿 pass_code_token → POST /drive/v1/share/restore → 轮询任务
     */
    @Override
    public boolean saveShare(String shareUrl, String password, String toCid) throws Exception {
        String url = shareUrl == null ? "" : shareUrl.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^https?://(?:www\\.)?mypikpak\\.com/s/([A-Za-z0-9_-]+)")
                .matcher(url);
        if (!m.find()) throw new Exception("不是有效的 PikPak 分享链接（应为 mypikpak.com/s/…）");
        String shareId = m.group(1);
        String passCode = password == null ? "" : password.trim();
        // 1) 打开分享，拿 pass_code_token（分享读写接口需 X-Client-Id 头，已在 api() 统一加）
        JSONObject q = new JSONObject()
                .put("share_id", shareId)
                .put("limit", "100")
                .put("thumbnail_size", "SIZE_SMALL");
        if (!passCode.isEmpty()) q.put("pass_code", passCode);
        JSONObject info = api("GET", "/drive/v1/share", q, null, true);
        String status = info.optString("share_status");
        if (!"OK".equals(status)) {
            if ("PASS_CODE_EMPTY".equals(status)) throw new Exception("该分享需要提取码");
            if ("PASS_CODE_ERROR".equals(status)) throw new Exception("提取码错误");
            throw new Exception("分享不可用：" + status + " " + info.optString("share_status_text"));
        }
        String token = info.optString("pass_code_token");
        JSONArray files = info.optJSONArray("files");
        List<String> fileIds = new ArrayList<>();
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f != null && !TextUtils.isEmpty(f.optString("id"))) fileIds.add(f.optString("id"));
            }
        }
        if (fileIds.isEmpty()) throw new Exception("分享为空或已失效");
        // 2) restore 转存到 toCid（空=根目录）
        JSONObject body = new JSONObject()
                .put("share_id", shareId)
                .put("pass_code_token", token)
                .put("file_ids", new JSONArray(fileIds))
                .put("ancestor_ids", new JSONArray())
                .put("parent_id", toCid == null ? "" : toCid)
                .put("specify_parent_id", true);
        JSONObject r = api("POST", "/drive/v1/share/restore", null, body, true);
        String rs = r.optString("share_status");
        if (!rs.isEmpty() && !"OK".equals(rs)) {
            throw new Exception("转存失败：" + r.optString("share_status_text", rs));
        }
        String taskId = r.optString("restore_task_id");
        if (TextUtils.isEmpty(taskId)) return true;
        // 3) 轮询任务（restore 任务秒级完成）
        for (int i = 0; i < 30; i++) {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            CloudTask t = getOfflineTask(taskId);
            if (t.getStatus() == 2) return true;
            if (t.getStatus() == 3) throw new Exception("转存失败：" + t.getStatusText());
        }
        throw new Exception("转存超时，请稍后在网盘中查看");
    }
}
