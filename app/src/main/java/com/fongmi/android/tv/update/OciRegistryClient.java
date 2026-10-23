package com.fongmi.android.tv.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class OciRegistryClient {

    static final String MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";
    static final String ARTIFACT_TYPE = "application/vnd.webhtv.apk.v1";
    static final String CONFIG_MEDIA_TYPE = "application/vnd.oci.empty.v1+json";
    static final String APK_MEDIA_TYPE = "application/vnd.android.package-archive";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private final OkHttpClient client;

    public OciRegistryClient(OkHttpClient client) {
        this.client = client;
    }

    // 唯一入口：身份必须由调用方显式给出（生产恒传本次更新目标的身份串）。
    // 旧的四参重载传 identity=null 会绕过续传的身份门，已删除——不许再有
    // 「不带身份也能拉」的顺手入口，测试也要显式传 null 表明无意走身份门。
    public void pull(String endpoint, OciArtifact artifact, File file, Observer observer, String identity) throws Exception {
        if (artifact == null || !artifact.isValid()) throw new IOException("Invalid OCI artifact descriptor");
        HttpUrl manifestUrl = artifactUrl(endpoint, artifact.repository, "manifests", artifact.manifestDigest);
        Request manifestRequest = new Request.Builder()
                .url(manifestUrl)
                .header("Accept", MANIFEST_MEDIA_TYPE)
                .header("Accept-Encoding", "identity")
                .build();
        byte[] manifest;
        try (Response response = executeAuthorized(manifestRequest, artifact, observer)) {
            requireSuccess(response, "OCI manifest");
            String contentType = response.header("Content-Type", "");
            if (!contentType.isEmpty() && !contentType.startsWith(MANIFEST_MEDIA_TYPE)) throw new IOException("Unexpected OCI manifest content type");
            manifest = readLimited(response.body(), MAX_MANIFEST_BYTES);
        }
        verifyDigest(manifest, artifact.manifestDigest, "OCI manifest");
        validateManifest(manifest, artifact);
        downloadBlob(endpoint, artifact, file, observer, identity);
    }

    // OCI 续传：本链的形态与直链同理——清单校验过「恰好一层」且层摘要与
    // 更新清单 sha256 全等（Updater.parseDownloads），整包就是这一个 blob，
    // 不是多层拼接。故同法续传：本地半截带 Range 拉余下部分；摘要计算先把
    // 已有半截喂进 MessageDigest 再接着算，终值对层摘要即证明整档连续无缺。
    private void downloadBlob(String endpoint, OciArtifact artifact, File file, Observer observer, String identity) throws Exception {
        long existing = resumableLength(artifact, file, observer, identity);
        if (existing == artifact.size) {
            // resumableLength 已对整档验过摘要：档已完整，直接交回。
            observer.onProgress(100, artifact.size, artifact.size, 0, 0);
            return;
        }
        HttpUrl blobUrl = artifactUrl(endpoint, artifact.repository, "blobs", artifact.layerDigest);
        Request.Builder builder = new Request.Builder().url(blobUrl).header("Accept-Encoding", "identity");
        if (existing > 0) builder.header("Range", "bytes=" + existing + "-");
        try (Response response = executeAuthorized(builder.build(), artifact, observer)) {
            int code = response.code();
            if (code == 416 && existing > 0) {
                // 偏移不可续：半截已不是可续前缀，删档报错，下次从零拉
                // （与直链路同口径：协商证明不可用才删，不是失败即删）。
                deleteQuietly(file);
                throw new IOException("OCI blob range not satisfiable");
            }
            requireSuccess(response, "OCI blob");
            ResponseBody body = response.body();
            if (body == null) throw new IOException("OCI blob is empty");
            long responseLength = body.contentLength();
            boolean append;
            long start;
            if (code == 206) {
                String contentRange = response.header("Content-Range");
                long rangeStart = parseRangeStart(contentRange);
                long rangeTotal = parseRangeTotal(contentRange);
                if (rangeStart >= 0 && rangeStart != existing) throw new IOException("OCI blob range mismatch");
                if (rangeTotal > 0 && rangeTotal != artifact.size) throw new IOException("OCI blob size mismatch");
                if (responseLength > 0 && existing + responseLength != artifact.size) throw new IOException("OCI blob size mismatch");
                append = existing > 0;
                start = existing;
            } else {
                // 回 200＝注册表／回源无视 Range 发全量：截断重写，不拼接。
                if (responseLength > 0 && responseLength != artifact.size) throw new IOException("OCI blob size mismatch");
                append = false;
                start = 0;
            }
            streamBlob(body.byteStream(), artifact, file, observer, start, append);
        }
    }

    // 半截长度口径同直链路：先过身份门——带了身份（生产路恒带）时旁车
    // （UpdateIdentity）对不上本次更新目标就不认这半截，哪怕长度恰好整档、
    // 摘要恰好对得上别的包，也不当本次的前缀；身份缺省（测试直调）才跳过。
    // 过门后再按长度定：长于层大小按 0（落盘截断重写）；恰好整档时先本地
    // 验摘要，对上才认完整，对不上按 0 重拉——不把坏档当成品交回。
    private long resumableLength(OciArtifact artifact, File file, Observer observer, String identity) throws Exception {
        if (file == null || !file.exists()) return 0;
        if (identity != null && !identity.isEmpty() && !UpdateIdentity.matches(file, identity)) return 0;
        long length = file.length();
        if (length > artifact.size) return 0;
        if (length == artifact.size) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            feedFile(digest, file, length, observer);
            return ("sha256:" + hex(digest.digest())).equals(artifact.layerDigest) ? length : 0;
        }
        return length;
    }

    private void feedFile(MessageDigest digest, File file, long bytes, Observer observer) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[16384];
            long fed = 0;
            int count;
            while (fed < bytes && (count = input.read(buffer, 0, (int) Math.min(buffer.length, bytes - fed))) != -1) {
                checkCanceled(observer);
                digest.update(buffer, 0, count);
                fed += count;
            }
            if (fed != bytes) throw new IOException("OCI blob partial file changed while resuming");
        }
    }

    private void streamBlob(InputStream source, OciArtifact artifact, File file, Observer observer, long start, boolean append) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Unable to create update directory");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // 续传时摘要从半截开头算起：先把已有部分喂进去，再接网络流。
        if (append && start > 0) feedFile(digest, file, start, observer);
        try (BufferedInputStream input = new BufferedInputStream(source); FileOutputStream output = new FileOutputStream(file, append)) {
            byte[] buffer = new byte[16384];
            long bytes = start;
            long runStart = System.currentTimeMillis();
            long lastTime = runStart;
            long lastBytes = bytes;
            int lastProgress = -1;
            observer.onProgress(artifact.size > 0 ? (int) (bytes * 100 / artifact.size) : 0, bytes, artifact.size, 0, 0);
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCanceled(observer);
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                bytes += count;
                if (bytes > artifact.size) throw new IOException("OCI blob exceeds expected size");
                long now = System.currentTimeMillis();
                int progress = (int) (bytes * 100 / artifact.size);
                if (progress == lastProgress && now - lastTime < 1000) continue;
                long speed = (bytes - lastBytes) * 1000 / Math.max(1, now - lastTime);
                observer.onProgress(progress, bytes, artifact.size, speed, now - runStart);
                lastProgress = progress;
                lastTime = now;
                lastBytes = bytes;
            }
            if (bytes != artifact.size) throw new IOException("OCI blob download is incomplete");
            String actual = "sha256:" + hex(digest.digest());
            if (!actual.equals(artifact.layerDigest)) {
                // 整档已下满但摘要定案对不上：这不是半截、续传救不回来，
                // 当场删档，防它被下次当「已完整」或被直链路接去续传。
                deleteQuietly(file);
                throw new IOException("OCI blob digest mismatch");
            }
        }
    }

    // Content-Range: bytes <start>-<end>/<total|*>；解析失败返回 -1。
    private long parseRangeStart(String header) {
        if (header == null) return -1;
        String value = header.trim();
        if (value.startsWith("bytes")) value = value.substring(5).trim();
        int dash = value.indexOf('-');
        if (dash <= 0) return -1;
        try {
            return Long.parseLong(value.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private long parseRangeTotal(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0) return -1;
        String tail = header.substring(slash + 1).trim();
        if (tail.isEmpty() || "*".equals(tail)) return -1;
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void deleteQuietly(File file) {
        try {
            // 删失败不静默：留痕到文件日志，否则坏档留盘下次还会撞同一处。
            // 身份旁车一并摘掉（与 Updater 校验失败处的口径对齐），不留孤旁车。
            if (file != null && file.exists() && !file.delete()) {
                com.github.catvod.crawler.SpiderDebug.log("update", "oci delete failed path=%s", file.getAbsolutePath());
            }
            if (file != null && !UpdateIdentity.clearSidecar(file)) {
                com.github.catvod.crawler.SpiderDebug.log("update", "oci sidecar delete failed path=%s", file.getAbsolutePath());
            }
        } catch (Exception ignored) {
        }
    }

    private Response executeAuthorized(Request request, OciArtifact artifact, Observer observer) throws Exception {
        Response response = execute(request, observer);
        if (response.code() != 401) return response;
        String header = response.header("WWW-Authenticate");
        Request challengedRequest = response.request();
        response.close();
        OciAuthChallenge challenge = OciAuthChallenge.parse(header);
        String token = token(challenge, artifact, challengedRequest.url().host(), observer);
        return execute(challengedRequest.newBuilder().header("Authorization", "Bearer " + token).build(), observer);
    }

    private String token(OciAuthChallenge challenge, OciArtifact artifact, String requestHost, Observer observer) throws Exception {
        String realm = UpdateUrl.requireHttpsUrl(challenge.realm);
        String realmHost = UpdateUrl.host(realm);
        if (!allowedAuthHost(realmHost, requestHost, artifact.registry)) throw new IOException("Untrusted Registry authentication host");
        HttpUrl parsed = HttpUrl.parse(realm);
        if (parsed == null) throw new IOException("Invalid Registry authentication URL");
        HttpUrl.Builder builder = parsed.newBuilder();
        String expectedScope = "repository:" + artifact.repository + ":pull";
        if (!challenge.scope.isEmpty() && !expectedScope.equals(challenge.scope)) throw new IOException("Unexpected Registry authentication scope");
        if (!challenge.service.isEmpty()) builder.addQueryParameter("service", challenge.service);
        builder.addQueryParameter("scope", expectedScope);
        Request request = new Request.Builder().url(builder.build()).header("Accept", "application/json").header("Accept-Encoding", "identity").build();
        try (Response response = execute(request, observer)) {
            requireSuccess(response, "Registry token");
            byte[] bytes = readLimited(response.body(), MAX_MANIFEST_BYTES);
            JsonObject object = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String token = string(object, "token");
            if (token.isEmpty()) token = string(object, "access_token");
            if (token.isEmpty()) throw new IOException("Registry token is missing");
            return token;
        }
    }

    private Response execute(Request initial, Observer observer) throws Exception {
        Request request = initial;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            checkCanceled(observer);
            Call call = client.newCall(request);
            observer.onCall(call);
            Response response = call.execute();
            if (!isRedirect(response.code())) return response;
            String location = response.header("Location");
            HttpUrl next = location == null ? null : response.request().url().resolve(location);
            response.close();
            if (next == null || !next.isHttps()) throw new IOException("Unsafe OCI redirect");
            boolean sameOrigin = sameOrigin(request.url(), next);
            Request.Builder builder = request.newBuilder().url(next);
            if (!sameOrigin) builder.removeHeader("Authorization");
            request = builder.build();
        }
        throw new IOException("Too many OCI redirects");
    }

    void validateManifest(byte[] bytes, OciArtifact artifact) throws IOException {
        try {
            JsonObject object = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (number(object, "schemaVersion") != 2) throw new IOException("Unsupported OCI schema version");
            if (!MANIFEST_MEDIA_TYPE.equals(string(object, "mediaType"))) throw new IOException("Unexpected OCI manifest media type");
            if (!ARTIFACT_TYPE.equals(string(object, "artifactType"))) throw new IOException("Unexpected OCI artifact type");
            JsonObject config = object.getAsJsonObject("config");
            if (config == null || !CONFIG_MEDIA_TYPE.equals(string(config, "mediaType"))) throw new IOException("Unexpected OCI config type");
            JsonArray layers = object.getAsJsonArray("layers");
            if (layers == null || layers.size() != 1) throw new IOException("OCI artifact must contain exactly one APK layer");
            JsonObject layer = layers.get(0).getAsJsonObject();
            if (!APK_MEDIA_TYPE.equals(string(layer, "mediaType"))) throw new IOException("Unexpected OCI layer media type");
            if (!artifact.layerDigest.equals(string(layer, "digest").toLowerCase(Locale.ROOT))) throw new IOException("OCI layer digest mismatch");
            if (longNumber(layer, "size") != artifact.size) throw new IOException("OCI layer size mismatch");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid OCI manifest", e);
        }
    }

    private HttpUrl artifactUrl(String endpoint, String repository, String operation, String value) throws IOException {
        HttpUrl base = HttpUrl.parse(UpdateUrl.requireHttpsOrigin(endpoint));
        if (base == null) throw new IOException("Invalid OCI endpoint");
        return base.newBuilder().addPathSegment("v2").addPathSegments(repository).addPathSegment(operation).addPathSegment(value).build();
    }

    private byte[] readLimited(ResponseBody body, int limit) throws IOException {
        if (body == null) throw new IOException("Response body is empty");
        long length = body.contentLength();
        if (length > limit) throw new IOException("Response body is too large");
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream(length > 0 ? (int) length : 8192)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("Response body is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void verifyDigest(byte[] bytes, String expected, String label) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = "sha256:" + hex(digest.digest(bytes));
        if (!actual.equals(expected)) throw new IOException(label + " digest mismatch");
    }

    private void requireSuccess(Response response, String label) throws IOException {
        if (!response.isSuccessful()) throw new IOException(label + " failed: HTTP " + response.code());
    }

    private void checkCanceled(Observer observer) throws InterruptedIOException {
        if (observer.isCanceled() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
    }

    private boolean allowedAuthHost(String realm, String request, String registry) {
        return realm.equalsIgnoreCase(request) || realm.equalsIgnoreCase(registry) || "auth.docker.io".equals(realm) || "token.docker.io".equals(realm);
    }

    private boolean sameOrigin(HttpUrl first, HttpUrl second) {
        return first.scheme().equals(second.scheme()) && first.host().equals(second.host()) && first.port() == second.port();
    }

    private boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private String string(JsonObject object, String name) {
        return object != null && object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private int number(JsonObject object, String name) {
        return object != null && object.has(name) ? object.get(name).getAsInt() : 0;
    }

    private long longNumber(JsonObject object, String name) {
        return object != null && object.has(name) ? object.get(name).getAsLong() : 0;
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.ROOT, "%02x", value));
        return builder.toString();
    }

    public interface Observer {

        boolean isCanceled();

        void onCall(Call call);

        void onProgress(int progress, long bytes, long total, long speed, long elapsed);
    }
}
