package com.fongmi.android.tv.api.hk;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.InputStream;

import okhttp3.Call;

/**
 * GlideUrl → InputStream 的 ModelLoader（8.83 OkHttpLibraryGlideModule 注册同款），
 * 数据抓取走 {@link HkOkHttpStreamFetcher}（@js= 流式解密语义）。
 */
public class HkGlideUrlLoader implements ModelLoader<GlideUrl, InputStream> {

    private final Call.Factory client;

    public HkGlideUrlLoader(Call.Factory client) {
        this.client = client;
    }

    @Override
    public LoadData<InputStream> buildLoadData(@NonNull GlideUrl model, int width, int height, @NonNull Options options) {
        return new LoadData<>(new ObjectKey(model), new HkOkHttpStreamFetcher(client, model));
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
