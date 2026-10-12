package com.fongmi.android.tv.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.GranularRoundedCorners;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;
import com.orhanobut.logger.Logger;

import org.json.JSONObject;

import java.io.File;
import java.util.Map;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    private static final String TAG = "ImgUtil";

    public static void logo(ImageView view) {
        try {
            Glide.with(view).load(UrlUtil.convert(VodConfig.get().getConfig().getLogo())).circleCrop().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL).error(R.drawable.ic_logo).into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String url, CustomTarget<Bitmap> target) {
        try {
            Glide.with(App.get()).asBitmap().load(getUrl(url)).override(ResUtil.dp2px(96), ResUtil.dp2px(96)).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, int width, int height, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(width, height).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void preload(Context context, String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).preload();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String text, String url, ImageView view) {
        load(text, url, view, true);
    }

    public static void load(String text, String url, ImageView view, boolean vod) {
        load(text, url, view, vod, 0, 0);
    }

    public static void load(String text, String url, ImageView view, int width, int height) {
        load(text, url, view, true, width, height);
    }

    public static void load(String text, String url, ImageView view, boolean vod, int width, int height) {
        load(text, url, view, vod, width, height, null);
    }

    /** 海阔列表：ruleUrl 为当前规则页 URL，用于 8.83 同款自动 Referer（同域防盗链）。 */
    public static void load(String text, String url, ImageView view, String ruleUrl) {
        load(text, url, view, true, 0, 0, ruleUrl);
    }

    public static void load(String text, String url, ImageView view, boolean vod, int width, int height, String ruleUrl) {
        view.setScaleType(vod ? CENTER_CROP : FIT_CENTER);
        if (!vod) view.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(url)) view.setImageDrawable(getTextDrawable(text, vod));
        else try {
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url, ruleUrl)).listener(getListener(text, url, view, vod));
            if (width > 0 && height > 0) builder.override(width, height);
            builder.placeholder(getTextDrawable(text, vod));
            if (vod) builder.centerCrop().into(view);
            else builder.fitCenter().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 圆形裁剪加载（对照 8.83 icon_round_4 / icon_round_small_4 的 bindCircleCropImageView）。
     */
    public static void loadCircle(String text, String url, ImageView view) {
        loadCircle(text, url, view, null);
    }

    /** 海阔列表：ruleUrl 为当前规则页 URL，用于 8.83 同款自动 Referer。 */
    public static void loadCircle(String text, String url, ImageView view, String ruleUrl) {
        view.setScaleType(CENTER_CROP);
        if (TextUtils.isEmpty(url)) view.setImageDrawable(getTextDrawable(text, true));
        else try {
            Glide.with(view).load(getUrl(url, ruleUrl)).listener(getListener(text, url, view, true)).circleCrop().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 顶部圆角加载（Vod.Rect 网格：上 8dp，下直角）。
     * 二期优化：替代 ShapeableImageView 的 GPU 实时裁剪，解码时一次裁好。
     */
    public static void loadTopRounded(String text, String url, ImageView view, int width, int height) {
        view.setScaleType(CENTER_CROP);
        if (TextUtils.isEmpty(url)) view.setImageDrawable(getTextDrawable(text, true));
        else try {
            int r = ResUtil.dp2px(8);
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url)).listener(getListener(text, url, view, true));
            if (width > 0 && height > 0) builder.override(width, height);
            builder.placeholder(getTextDrawable(text, true));
            builder.transform(new CenterCrop(), new GranularRoundedCorners(r, r, 0, 0));
            builder.into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 全圆角加载（Vod.List 列表：四角 8dp）。
     * 二期优化：替代 ShapeableImageView 的 GPU 实时裁剪，解码时一次裁好。
     */
    public static void loadRounded(String text, String url, ImageView view) {
        view.setScaleType(CENTER_CROP);
        if (TextUtils.isEmpty(url)) view.setImageDrawable(getTextDrawable(text, true));
        else try {
            int r = ResUtil.dp2px(8);
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url)).listener(getListener(text, url, view, true));
            builder.placeholder(getTextDrawable(text, true));
            builder.transform(new CenterCrop(), new RoundedCorners(r));
            builder.into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 小程序列表图标：URL 图标加载。对照 8.83
     * ArticleListRuleAdapter.smali:194（icon_mini_program_icon）：失败时用默认图标占位，
     * 不永久拉黑，重新绑定即可重试。
     */
    public static void loadRuleIcon(String title, String url, ImageView view) {
        view.setScaleType(CENTER_CROP);
        view.setContentDescription(title);
        if (TextUtils.isEmpty(url)) {
            view.setImageDrawable(defaultMiniIcon(view));
            return;
        }
        try {
            Glide.with(view).load(getUrl(url)).listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                    view.setImageDrawable(defaultMiniIcon(view));
                    return true;
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    return false;
                }
            }).centerCrop().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
            view.setImageDrawable(defaultMiniIcon(view));
        }
    }

    /** 8.83 icon_mini_program_icon 同款默认图标。 */
    private static Drawable defaultMiniIcon(ImageView view) {
        try {
            return androidx.appcompat.content.res.AppCompatResources.getDrawable(view.getContext(), R.drawable.icon_mini_program_icon);
        } catch (Throwable e) {
            return new ColorDrawable(Color.TRANSPARENT);
        }
    }

    /**
     * 壳子行为对标默影视（照抄）：解析 @Headers=/@Cookie=/@Referer=/@User-Agent= 后缀，
     * 剥离后组装 GlideUrl。之前走 getUrl(url, null) 直接返回原串，@后缀留在 URL 里发给服务器导致 404。
     */
    public static Object getUrl(String url) {
        String param = null;
        url = UrlUtil.convert(url);
        if (url.startsWith("data:")) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        if (url.contains("@Headers=")) addHeader(builder, param = url.split("@Headers=")[1].split("@")[0]);
        if (url.contains("@Cookie=")) builder.addHeader(HttpHeaders.COOKIE, param = url.split("@Cookie=")[1].split("@")[0]);
        if (url.contains("@Referer=")) builder.addHeader(HttpHeaders.REFERER, param = url.split("@Referer=")[1].split("@")[0]);
        if (url.contains("@User-Agent=")) builder.addHeader(HttpHeaders.USER_AGENT, param = url.split("@User-Agent=")[1].split("@")[0]);
        url = param == null ? url : url.split("@")[0];
        return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
    }

    private static void addHeader(LazyHeaders.Builder builder, String header) {
        Map<String, String> map = Json.toMap(Json.parse(header));
        for (Map.Entry<String, String> entry : map.entrySet()) builder.addHeader(UrlUtil.fixHeader(entry.getKey()), entry.getValue());
    }

    /**
     * 8.83 {@code GlideUtil.getGlideUrl(ruleUrl, picUrl)} 语义移植。
     *
     * <p>处理顺序（对照反编译行号）：hiker://images/ → drawable（宿主无 8.83 的
     * ImageUrlMapEnum R 表，返回 null 走文字占位，不猜映射）；#rrggbb → ColorDrawable；
     * 非 http：hiker://files/ → file://、去 #originalSize#；http(s)：去 #originalSize#、
     * gitee 自动 Referer、同域自动 Referer、默认 UA，解析 @js=/@headers=/@Cookie=/
     * @Referer=/@User-Agent= 后缀组装 GlideUrl。@js= 由 {@code HkOkHttpStreamFetcher}
     * 做 8.83 同款流式解密（header "js" 不发给服务器）。</p>
     */
    public static Object getUrl(String picUrl, String ruleUrl) {
        if (picUrl == null) return null;
        // 壳子路径（ruleUrl 为空）：走默影视逻辑（@后缀解析剥离），不走 8.83 的"原串返回"
        if (TextUtils.isEmpty(ruleUrl)) return getUrl(picUrl);
        try {
            // 1. hiker://images/* → 8.83 查 ImageUrlMapEnum 得 drawable id；
            //    宿主无法还原 8.83 的 R 表映射，返回 null 走文字占位（不猜）。
            if (picUrl.startsWith("hiker://images/")) return null;
            // 2. # 开头 → 纯色块
            if (!picUrl.isEmpty() && picUrl.startsWith("#")) {
                try {
                    return new ColorDrawable(Color.parseColor(picUrl));
                } catch (Throwable t) {
                    Logger.t(TAG).d("parseColor failed: %s", picUrl);
                }
            }
            String url = UrlUtil.convert(picUrl);
            if (url.startsWith("data:")) return url;
            boolean http = url.startsWith("http");
            // 3. 非 http 分支（8.83 line 80-89）
            if (!http) {
                if (!TextUtils.isEmpty(url) && url.startsWith("hiker://files/")) {
                    // 8.83: "file://" + SettingConfig.rootDir(=getExternalFilesDir(DOCUMENTS)) + rest，
                    // 与 HkJsRuntime.getFilePath / fetchByHiker 保持同一目录（之前误用内部 filesDir 导致找不到文件）
                    String rest = url.replace("hiker://files/", "");
                    File docs = App.get().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                    String root = (docs != null) ? docs.getAbsolutePath() : App.get().getFilesDir().getAbsolutePath();
                    return "file://" + root + File.separator + rest;
                }
                try {
                    if (!TextUtils.isEmpty(url)) url = url.replace("#originalSize#", "");
                } catch (Throwable t) {
                    Logger.t(TAG).d("strip originalSize failed");
                }
                return url;
            }
            // 4. http(s) 主分支
            url = url.replace("#originalSize#", "");
            if (url.contains("gitee.com") && !url.contains("@Referer=")) url += "@Referer=https://gitee.com";
            LazyHeaders.Builder builder = new LazyHeaders.Builder();
            // 自动 Referer（8.83 line 103-116）
            String ruleDom = getDom(ruleUrl);
            String picDom = getDom(url);
            String autoReferer;
            if (TextUtils.equals(ruleDom, picDom)
                    || (!TextUtils.isEmpty(ruleDom) && !TextUtils.isEmpty(picDom)
                        && (ruleDom.contains(picDom) || picDom.contains(ruleDom)))) {
                autoReferer = (ruleUrl.startsWith("https") ? "https://" : "http://") + ruleDom + "/";
            } else {
                autoReferer = "";
            }
            String ua = defaultUa();
            // @js=（小写，8.83 line 119-124）：只取第 2 段
            String[] jsParts = url.split("@js=");
            if (jsParts.length > 1) {
                url = jsParts[0];
                builder.addHeader("js", jsParts[1]);
            }
            // @Headers=（大写，TVBox/PY 约定；壳子 getUrl(String) 已支持，小程序路径补上——
            // 否则后缀留在 URL 里发给服务器导致 404。JSON 体取到下一个 @ 或结尾，兼容组合后缀）
            if (url.contains("@Headers=")) {
                String after = url.split("@Headers=", 2)[1];
                // 取平衡的 JSON 对象（兼容值内含 @ 的极端情况）
                int brace = after.indexOf('{');
                if (brace >= 0) {
                    int depth = 0, end = -1;
                    for (int i = brace; i < after.length(); i++) {
                        char c = after.charAt(i);
                        if (c == '{') depth++;
                        else if (c == '}') { depth--; if (depth == 0) { end = i; break; } }
                    }
                    if (end > brace) {
                        String json = after.substring(brace, end + 1);
                        url = url.split("@Headers=")[0] + after.substring(end + 1);
                        try {
                            JSONObject o = new JSONObject(json);
                            java.util.Iterator<String> it = o.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                builder.addHeader(UrlUtil.fixHeader(k), o.optString(k, ""));
                            }
                        } catch (Throwable t) {
                            Logger.t(TAG).d("parse @Headers= failed");
                        }
                    }
                }
            }
            // @headers=（8.83 line 124-138）：[1] 须以 { 开头 } 结尾
            String[] hParts = url.split("@headers=");
            if (hParts.length > 1 && hParts[1].startsWith("{") && hParts[1].endsWith("}")) {
                url = hParts[0];
                try {
                    JSONObject o = new JSONObject(hParts[1]);
                    java.util.Iterator<String> it = o.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        builder.addHeader(k, o.optString(k, ""));
                    }
                } catch (Throwable t) {
                    Logger.t(TAG).d("parse @headers= failed");
                }
            }
            // @Cookie=（8.83 line 139-145）
            String[] cParts = url.split("@Cookie=");
            if (cParts.length > 1) {
                url = cParts[0];
                builder.addHeader(HttpHeaders.COOKIE, cParts[1]);
            }
            // @Referer= / @User-Agent= 组合（8.83 line 146-171）
            // C6：split 用 -1 保留尾空串，显式 @Referer=（空值）清掉自动 Referer
            String referer;
            String finalUa;
            String[] rParts = url.split("@Referer=", -1);
            if (rParts.length > 1) {
                url = rParts[0];
                if (url.contains("@User-Agent=")) {
                    // @User-Agent= 在 @Referer= 之前
                    referer = rParts[1];
                    String[] uParts = url.split("@User-Agent=");
                    url = uParts[0];
                    finalUa = uParts[1];
                } else if (rParts[1].contains("@User-Agent=")) {
                    // @User-Agent= 在 @Referer= 之后
                    String[] uParts = rParts[1].split("@User-Agent=");
                    referer = uParts[0];
                    finalUa = uParts[1];
                } else {
                    referer = rParts[1];
                    finalUa = ua;
                }
            } else {
                if (url.contains("@User-Agent=")) {
                    String[] uParts = url.split("@User-Agent=");
                    finalUa = uParts[1];
                    url = uParts[0];
                } else {
                    finalUa = ua;
                }
                referer = autoReferer;
            }
            // 边界（8.83 line 173）：最终 URL == ruleUrl 且原串无 @Referer= → Referer 置空
            if (TextUtils.equals(ruleUrl, url) && !url.contains("@Referer=")) referer = "";
            // Referer 非空但不是 http 开头 → 置空（8.83 line 176）
            String refererHeader = (!TextUtils.isEmpty(referer) && referer.startsWith("http")) ? referer : "";
            // 8.83：glideUA 为空时不拼 User-Agent 头（之前无条件拼空 UA）
            if (!TextUtils.isEmpty(finalUa)) builder.addHeader(HttpHeaders.USER_AGENT, finalUa);
            if (!TextUtils.isEmpty(refererHeader)) builder.addHeader(HttpHeaders.REFERER, refererHeader);
            return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
        } catch (Throwable t) {
            Logger.t(TAG).d("getUrl failed: %s", t.getMessage());
            return null;
        }
    }

    /** 8.83 默认 UA（SettingConfig.getGlideUA 同款语义：默认为 null，不发 UA 头）。 */
    private static String defaultUa() {
        try {
            String ua = Setting.getUa();
            return ua == null ? "" : ua;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 8.83 StringUtil.getDom 语义：取 host（含 getRealUrl 预处理；特殊 scheme 直接返回原串）。 */
    private static String getDom(String url) {
        if (TextUtils.isEmpty(url)) return url;
        try {
            String u = getRealUrl(url);
            // 8.83 getDom0：thunder/magnet/ftp/ed2k/file 直接返回原串，不取 host
            if (u.startsWith("thunder://") || u.startsWith("magnet:") || u.startsWith("ftp://")
                    || u.startsWith("ed2k:") || u.startsWith("file://")) {
                return u;
            }
            String[] parts = u.split("://");
            if (parts.length > 1) return parts[1].split("/")[0];
            parts = u.split("//");
            if (parts.length > 1) return parts[1].split("/")[0];
            return u.replaceFirst("http://", "").replaceFirst("https://", "").replaceFirst("hiker://", "").split("/")[0];
        } catch (Throwable t) {
            return "";
        }
    }

    /** 8.83 StringUtil.getRealUrl 简化版：去 @lazyRule=/@rule= 后缀与 hiker://empty 前缀。 */
    private static String getRealUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        try {
            String u = url.split("@lazyRule=")[0].split("@rule=")[0];
            if (u.startsWith("hiker://empty#http")) u = replaceOnce(u, "hiker://empty#", "");
            else if (u.startsWith("webview://")) u = u.replaceFirst("webview://", "");
            else if (u.startsWith("hiker://empty##http") || u.startsWith("hiker://empty##https%3A%2F%2F") || u.startsWith("hiker://empty##http%3A%2F%2F"))
                u = replaceOnce(u, "hiker://empty##", "");
            else if (u.startsWith("hiker://empty$http")) u = replaceOnce(u, "hiker://empty$", "");
            else if (u.startsWith("hiker://empty$$http")) u = replaceOnce(u, "hiker://empty$$", "");
            int d = u.indexOf("$$$");
            if (d >= 0) u = u.substring(0, d);
            return u;
        } catch (Throwable t) {
            return url;
        }
    }

    private static String replaceOnce(String s, String from, String to) {
        int i = s.indexOf(from);
        return i < 0 ? s : s.substring(0, i) + to + s.substring(i + from.length());
    }

    private static Drawable getTextDrawable(String text, boolean vod) {
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "！" : text.substring(0, 1);
        if (vod) builder.buildRect(text, ColorGenerator.get400(text));
        return builder.buildRoundRect(text, ColorGenerator.get400(text), ResUtil.dp2px(4));
    }

    private static RequestListener<Drawable> getListener(String text, String url, ImageView view, boolean vod) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                view.setImageDrawable(getTextDrawable(text, vod));
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                if (resource != null) CoverRatioCache.put(url, resource.getIntrinsicWidth(), resource.getIntrinsicHeight());
                return false;
            }
        };
    }
}
