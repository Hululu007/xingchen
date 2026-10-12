package com.fongmi.android.tv.api.hk;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.signature.ObjectKey;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Call;
import okhttp3.HttpUrl;

/**
 * GlideUrl → InputStream 的 ModelLoader（8.83 OkHttpLibraryGlideModule 注册同款），
 * 数据抓取走 {@link HkOkHttpStreamFetcher}（@js= 流式解密语义）。
 */
public class HkGlideUrlLoader implements ModelLoader<GlideUrl, InputStream> {

    /**
     * 易变签名参数：只从缓存键里去掉，不影响实际请求（请求仍用原 URL，签名照发）。
     * 注意不要加 w/h 这类选图参数，它们影响图片内容。
     */
    private static final Set<String> VOLATILE_PARAMS = new HashSet<>(Arrays.asList(
            "t", "time", "timestamp", "ts",
            "sign", "signature", "sig",
            "auth_key", "token",
            "expires", "exp",
            "nonce", "rand"
    ));

    private final Call.Factory client;

    public HkGlideUrlLoader(Call.Factory client) {
        this.client = client;
    }

    @Override
    public LoadData<InputStream> buildLoadData(@NonNull GlideUrl model, int width, int height, @NonNull Options options) {
        return new LoadData<>(normalizeKey(model), new HkOkHttpStreamFetcher(client, model));
    }

    /**
     * 缓存键归一化：只改键，不改实际请求 URL（fetcher 拿到的仍是原 model）。
     * - 127.0.0.1（PY 本地图片代理）：端口随机，每次重载都变；键只取 u= 参数，扔掉端口。
     * - 通用：去掉易变签名参数（t/sign/token/expires 等），保留 w/h 等选图参数。
     */
    private static Key normalizeKey(GlideUrl model) {
        String url = model.toStringUrl();
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed == null) return new ObjectKey(model);
            if ("127.0.0.1".equals(parsed.host())) {
                String u = parsed.queryParameter("u");
                if (u != null && !u.isEmpty()) {
                    return new ObjectKey("localimg:" + u);
                }
                return new ObjectKey("localimg:" + parsed.encodedPath());
            }
            HttpUrl.Builder b = parsed.newBuilder();
            boolean changed = false;
            for (String name : VOLATILE_PARAMS) {
                if (parsed.queryParameter(name) != null) {
                    b.removeAllQueryParameters(name);
                    changed = true;
                }
            }
            if (changed) {
                return new ObjectKey(b.build().toString());
            }
        } catch (Throwable ignored) {
        }
        return new ObjectKey(model);
    }

    @Override
    public boolean handles(@NonNull GlideUrl model) {
        return true;
    }

    public static class Factory implements ModelLoaderFactory<GlideUrl, InputStream> {

        private final Call.Factory client;

        public Factory(Call.Factory client) {
            this.client = client;
        }

        @NonNull
        @Override
        public ModelLoader<GlideUrl, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new HkGlideUrlLoader(client);
        }

        @Override
        public void teardown() {
        }
    }
}
