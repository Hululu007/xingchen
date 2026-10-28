package com.fongmi.android.tv.hongguo.api;

import android.util.Log;

import com.fongmi.android.tv.hongguo.model.HgAccountProfile;
import com.fongmi.android.tv.hongguo.model.HgCellPage;

/**
 * 登录后第一验（定根报告 login-route-dig-20261028.md 的判读设计）：短信
 * 登录落托管后立刻拉两口会话接口——passport account/info（账号资料）与
 * bookshelf/video/list（收藏/追更书架）——把回包「形状」打进日志
 * （标签 HgLoginCheck）：资料侧只记三字段有无、书架侧只记条数与成败，
 * 不记任何字段值与 Cookie 内容。
 *
 * <p>用途：本机无真号，登录成功回包与原生会话认不认账只能等他真号首登
 * 一次定论；这一验让首登当场留下可判读的证据（成＝两口回结构化数据；
 * 败＝回包错误码/信息原样进日志），而不是再现 1.4.55 那次「全空且全
 * 静默、无从分辨」的局面。纯旁路：失败只记日志，不回滚登录、不打扰
 * 用户（登录本身已由 passport 回包判成）。</p>
 */
public final class HgLoginSessionCheck {

    private static final String TAG = "HgLoginCheck";

    private HgLoginSessionCheck() {
    }

    /** 落托管后调用：两口并行验，只记形状。绝不抛异常。 */
    public static void runAfterLogin() {
        try {
            HgApiClient client = new HgApiClient();
            client.setAuthProvider(new HgLoginAuthProvider());
            client.fetchAccountInfo(new HgCallback<HgAccountProfile>() {
                @Override
                public void onSuccess(HgAccountProfile p) {
                    Log.d(TAG, "account/info 成功：昵称有无=" + nonEmpty(p.displayName)
                            + " 头像有无=" + nonEmpty(p.avatarUrl)
                            + " 账号id有无=" + nonEmpty(p.userId));
                }

                @Override
                public void onError(int httpCode, String message) {
                    Log.d(TAG, "account/info 失败：code=" + httpCode + " msg=" + message);
                }
            });
            client.bookshelfVideoList(new HgCallback<HgCellPage>() {
                @Override
                public void onSuccess(HgCellPage page) {
                    Log.d(TAG, "bookshelf/video/list 成功：条数="
                            + (page.getItems() == null ? -1 : page.getItems().size())
                            + " 回包code=" + page.getCode());
                }

                @Override
                public void onError(int httpCode, String message) {
                    Log.d(TAG, "bookshelf/video/list 失败：code=" + httpCode + " msg=" + message);
                }
            });
        } catch (Throwable t) {
            Log.d(TAG, "第一验发起失败：" + t.getClass().getSimpleName());
        }
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
