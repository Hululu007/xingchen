package com.fongmi.android.tv.api.hk;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.util.ContentLengthInputStream;
import com.github.catvod.crawler.SpiderDebug;
import com.orhanobut.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 8.83 {@code OkHttpStreamFetcher} 语义的 Glide 图片抓取器。
 *
 * <p>请求头中 {@code js}/{@code timeout} 不发给服务器（8.83 loadData line 55 同款）。
 * {@code @js=} 求值规则（8.83 onResponse/onFailure 同款）：</p>
 * <ul>
 *   <li>HTTP 2xx 且有 js：{@code input} = 响应流；</li>
 *   <li>HTTP 非 2xx 或请求异常且有 js：{@code input} = null，给规则一次自救机会；</li>
 *   <li>JS 返回 InputStream 才采用，非流/异常 → 用原流；无 js 时走原逻辑。</li>
 * </ul>
 */
public class HkOkHttpStreamFetcher implements DataFetcher<InputStream>, okhttp3.Callback {

    private static final String TAG = "HkImageFetch";

    private static final int MAX_ATTEMPTS = 3;

    private final Call.Factory client;
    private final GlideUrl url;
    private int attempts;
    private volatile boolean cancelled;
    private volatile Call call;
    private InputStream stream;
    private ResponseBody responseBody;
    private DataCallback<? super InputStream> callback;

    public HkOkHttpStreamFetcher(Call.Factory client, GlideUrl url) {
        this.client = client;
        this.url = url;
    }

    @Override
    public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
        Request.Builder builder = new Request.Builder().url(url.toStringUrl());
        for (Map.Entry<String, String> entry : url.getHeaders().entrySet()) {
            String key = entry.getKey();
            if ("js".equals(key) || "timeout".equals(key)) continue;
            builder.addHeader(key, entry.getValue());
        }
        Request request = builder.build();
        this.callback = callback;
        attempts++;
        call = client.newCall(request);
        SpiderDebug.log("hkimage", "图片请求发出 host=%s 带解密头=%s", hostOf(), jsBody() != null);
        call.enqueue(this);
    }

    /** 重新组装并发出同一图片请求（loadData 的请求构建逻辑复用一遍）。 */
    private void refire() {
        Request.Builder builder = new Request.Builder().url(url.toStringUrl());
        for (Map.Entry<String, String> entry : url.getHeaders().entrySet()) {
            String key = entry.getKey();
            if ("js".equals(key) || "timeout".equals(key)) continue;
            builder.addHeader(key, entry.getValue());
        }
        attempts++;
        call = client.newCall(builder.build());
        SpiderDebug.log("hkimage", "图片请求重试 host=%s 第%d次", hostOf(), attempts);
        call.enqueue(this);
    }

    /** GlideUrl headers 中的 js 体（8.83 以 header "js" 传递 @js= 代码）。 */
    private String jsBody() {
        String js = url.getHeaders().get("js");
        return TextUtils.isEmpty(js) ? null : js;
    }

    /** 只记元信息（主机名等），不记内容：用于可导出日志断链。 */
    private String hostOf() {
        try {
            okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(url.toStringUrl());
            return u != null ? u.host() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 调规则当前 HkJsRuntime 求值；拿不到 runtime 时退化为原 input（8.83 无此分支，宿主多规则所需）。 */
    private InputStream evalImageJs(String jsBody, InputStream input, Map<String, List<String>> headers) {
        try {
            HkJsRuntime rt = HkImage.currentRuntime();
            if (rt == null) {
                SpiderDebug.log("hkimage", "图片求值无可用脚本环境 host=%s（退化为原流）", hostOf());
                return input;
            }
            return rt.evalImageJs(jsBody, input, headers, url.toString());
        } catch (Throwable t) {
            String cls = t.getClass().getSimpleName();
            String msg = t.getMessage();
            SpiderDebug.log("hkimage", "图片求值异常 host=%s 类型=%s 信息=%s", hostOf(), cls, msg == null ? "" : msg);
            Logger.t(TAG).d("evalImageJs failed: %s", t.getMessage());
            return input;
        }
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
        String cls = e.getClass().getSimpleName();
        String msg = e.getMessage();
        SpiderDebug.log("hkimage", "图片请求失败 host=%s 类型=%s 信息=%s", hostOf(), cls, msg == null ? "" : msg);
        // 瞬时网络故障（连接被掐/重置/超时）先自重试：换新连接再试，试满才走下面的自救/失败。
        // 图床类站点在直连下首连常被掐断，重试一两次就能拿到图（真机日志实证：同批图前几张
        // Connection reset、随后成功）。Canceled 是 Glide 主动取消，不重试。
        if (!cancelled && attempts < MAX_ATTEMPTS && !"Canceled".equals(msg)) {
            try {
                refire();
                return;
            } catch (Throwable ignored) {
            }
        }
        String js = jsBody();
        if (js != null) {
            InputStream s = evalImageJs(js, null, Collections.emptyMap());
            if (s != null) {
                stream = s;
                SpiderDebug.log("hkimage", "图片就绪(失败自救) host=%s 流=%s", hostOf(),
                        s.getClass().getSimpleName());
                callback.onDataReady(s);
                return;
            }
        }
        callback.onLoadFailed(e);
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) {
        responseBody = response.body();
        long len = -1;
        try {
            if (responseBody != null) len = responseBody.contentLength();
        } catch (Throwable ignored) {
        }
        SpiderDebug.log("hkimage", "图片响应 host=%s 状态=%d 声明字节=%d 带解密头=%s",
                hostOf(), response.code(), len, jsBody() != null);
        String js = jsBody();
        try {
            if (response.isSuccessful()) {
                if (js != null && responseBody != null) {
                    InputStream input = responseBody.byteStream();
                    InputStream s = evalImageJs(js, input, response.headers().toMultimap());
                    stream = s;
                    SpiderDebug.log("hkimage", "图片就绪 host=%s 流=%s", hostOf(),
                            s == null ? "null" : s.getClass().getSimpleName());
                    callback.onDataReady(s);
                    return;
                }
                InputStream s = responseBody == null ? null
                        : ContentLengthInputStream.obtain(responseBody.byteStream(), responseBody.contentLength());
                stream = s;
                SpiderDebug.log("hkimage", "图片就绪(无解密) host=%s 流=%s", hostOf(),
                        s == null ? "null" : s.getClass().getSimpleName());
                callback.onDataReady(s);
                return;
            }
            if (js != null) {
                InputStream s = evalImageJs(js, null, response.headers().toMultimap());
                if (s != null) {
                    stream = s;
                    callback.onDataReady(s);
                    return;
                }
            }
            callback.onLoadFailed(new HttpException(response.message(), response.code()));
        } catch (Throwable t) {
            callback.onLoadFailed(new IOException("Hk image fetch failed: " + t.getMessage(), t));
        }
    }

    @Override
    public void cleanup() {
        try {
            if (stream != null) stream.close();
        } catch (Throwable ignored) {
        }
        try {
            if (responseBody != null) responseBody.close();
        } catch (Throwable ignored) {
        }
        stream = null;
        responseBody = null;
        callback = null;
    }

    @Override
    public void cancel() {
        cancelled = true;
        Call c = call;
        if (c != null) c.cancel();
    }

    @NonNull
    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @NonNull
    @Override
    public DataSource getDataSource() {
        return DataSource.REMOTE;
    }
}
