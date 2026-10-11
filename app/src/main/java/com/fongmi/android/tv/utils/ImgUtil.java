package com.fongmi.android.tv.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.setting.Setting;
import com.google.common.net.HttpHeaders;
import com.orhanobut.logger.Logger;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    private static final String TAG = "ImgUtil";
    private static final Set<String> failed = Collections.synchronizedSet(new HashSet<>());

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
        if (TextUtils.isEmpty(url) || failed.contains(url)) view.setImageDrawable(getTextDrawable(text, vod));
        else try {
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url, ruleUrl)).listener(getListener(text, url, view, vod));
            if (width > 0 && height > 0) builder.override(width, height);
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
        if (TextUtils.isEmpty(url) || failed.contains(url)) view.setImageDrawable(getTextDrawable(text, true));
        else try {
            Glide.with(view).load(getUrl(url, ruleUrl)).listener(getListener(text, url, view, true)).circleCrop().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static Object getUrl(String url) {
        return getUrl(url, null);
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
            // 3. 非 http 分支（8.83 line 80-89）：ruleUrl 为空也走这里
            if (TextUtils.isEmpty(ruleUrl) || !http) {
                if (!TextUtils.isEmpty(url) && url.startsWith("hiker://files/")) {
                    String rest = url.replace("hiker://files/", "");
                    return "file://" + App.get().getFilesDir().getAbsolutePath() + File.separator + rest;
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
            String referer;
            String finalUa;
            String[] rParts = url.split("@Referer=");
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
            builder.addHeader(HttpHeaders.USER_AGENT, finalUa);
            if (!TextUtils.isEmpty(refererHeader)) builder.addHeader(HttpHeaders.REFERER, refererHeader);
            return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
        } catch (Throwable t) {
            Logger.t(TAG).d("getUrl failed: %s", t.getMessage());
            return null;
        }
    }

    /** 8.83 默认 UA（SettingConfig.getGlideUA 同款语义：用户 UA 优先，否则播放器默认 UA）。 */
    private static String defaultUa() {
        try {
            String ua = Setting.getUa();
            return TextUtils.isEmpty(ua) ? PlayerHelper.getDefaultUa() : ua;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 8.83 StringUtil.getDom 语义：取 host（含 getRealUrl 预处理）。 */
    private static String getDom(String url) {
        if (TextUtils.isEmpty(url)) return url;
        try {
            String u = getRealUrl(url);
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
                failed.add(url);
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                return false;
            }
        };
    }
}
