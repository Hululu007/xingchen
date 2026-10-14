package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.update.OciArtifact;
import com.fongmi.android.tv.utils.AppVersion;

public class Update {

    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";

    public String channel;
    public String name;
    public String versionName;
    public String desc;
    public String notes;
    public String apk;
    public String apkUrl;
    public String githubUrl;
    public String error;
    public String sha256;
    public OciArtifact oci;
    public int code;
    public long size;

    public static Update empty(String channel) {
        Update update = new Update();
        update.channel = channel;
        return update;
    }

    public boolean isBeta() {
        return CHANNEL_BETA.equals(channel);
    }

    public boolean hasManifest() {
        return !TextUtils.isEmpty(name) && (!TextUtils.isEmpty(githubUrl) || oci != null && oci.isValid());
    }

    public boolean hasUpdate() {
        if (!hasManifest()) return false;
        // 只按版本号/版本名判断：清单 name 是发布标签（如 v1.3.1），与本机
        // 「版本名-构建时间」形式的 fullName 永远对不上，原判断会把同版本误报为可更新，
        // 且版本号更低时还会误报降级。一律以 code 为准，code 相同再比 versionName。
        if (code > BuildConfig.VERSION_CODE) return true;
        if (code < BuildConfig.VERSION_CODE) return false;
        if (!TextUtils.isEmpty(versionName)) return !versionName.equals(BuildConfig.VERSION_NAME);
        return !AppVersion.isCurrent(name);
    }

    public String getText() {
        if (!TextUtils.isEmpty(notes)) return notes;
        if (!TextUtils.isEmpty(desc)) return desc;
        return "";
    }
}
