package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import android.app.AlertDialog;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoAuthorBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

/**
 * 红果·作者主页（官方原生轨 SeriesGuestProfileFragment 构成，dig-mine-profile
 * §2.8／dig-mine2 §3：KMP 轨本地缺省关、缺省走原生轨——本页照原生轨）。
 *
 * <p>构成：置顶 chip 行（22dp 头像＋名）＋头像姓名行（58dp／20sp 粗）＋
 * 关注钮（PugcFollowButton 定版：未关注橙渐变「关注」、已关注浅底
 * 「已关注」、本人隐藏）＋数据行＋「作品」页签。数据行（UserRelation
 * fans_num/follow_user_num）的承载 RPC 与作者维度作品口都未在包内抠到
 * 定版——两处宁缺不编：数据行无源时整行 GONE、作品区只留空态文案
 * （详见 fix-author-page-20261028 账）。</p>
 *
 * <p>关注走真接口双路（HgCommentClient.doFollowAction：主路 commentapi
 * do_action Subscribe/CancelSubscribe，备路 follow/ 端点主路非 0 才试）；
 * 状态单一事实源＝HgStore 作者关注集（与 feed 头像徽标同读，回页即一致）。
 * 取关先弹官方同款确认框（文案「不再关注该作者？」逐字）。</p>
 */
public class HongguoAuthorActivity extends BaseActivity {

    private static final String EXTRA_UID = "hg_author_uid";
    private static final String EXTRA_NAME = "hg_author_name";
    private static final String EXTRA_AVATAR = "hg_author_avatar";
    private static final String EXTRA_DESC = "hg_author_desc";

    public static void start(Context context, String uid, String name, String avatar, String desc) {
        Intent intent = new Intent(context, HongguoAuthorActivity.class);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_AVATAR, avatar);
        intent.putExtra(EXTRA_DESC, desc);
        context.startActivity(intent);
    }

    private ActivityHongguoAuthorBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String uid;
    private boolean followBusy;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoAuthorBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return true;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        uid = getIntent().getStringExtra(EXTRA_UID);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String avatar = getIntent().getStringExtra(EXTRA_AVATAR);
        String desc = getIntent().getStringExtra(EXTRA_DESC);
        if (uid == null || uid.isEmpty()) {
            finish();
            return;
        }
        binding.hgAuthorChipName.setText(name == null ? "" : name);
        binding.hgAuthorName.setText(name == null ? "" : name);
        bindAvatar(binding.hgAuthorChipAvatar, avatar, name);
        bindAvatar(binding.hgAuthorAvatar, avatar, name);
        if (desc != null && !desc.isEmpty()) {
            binding.hgAuthorDesc.setText(desc);
            binding.hgAuthorDesc.setVisibility(View.VISIBLE);
        }
        // 本人门禁的页内侧：自己看自己不亮关注钮（入口侧已拦，这里兜底）
        if (uid.equals(HgStore.profileUid())) binding.hgAuthorFollow.setVisibility(View.GONE);
        binding.hgAuthorFollow.setOnClickListener(v -> onFollowClicked());
        refreshFollowButton();
    }

    /** 头像：有址圆图，没址用名首字圆牌（与 feed 作者行同一口径）。 */
    private void bindAvatar(android.widget.ImageView view, String avatar, String name) {
        if (avatar != null && !avatar.isEmpty()) {
            ImgUtil.loadCircle(avatar, view);
        } else if (name != null && !name.isEmpty()) {
            view.setImageDrawable(new HgInitialAvatarDrawable(name.substring(0, 1)));
        }
    }

    private void refreshFollowButton() {
        boolean followed = HgStore.isAuthorFollowed(uid);
        binding.hgAuthorFollow.setText(followed ? "已关注" : "关注");
        binding.hgAuthorFollow.setBackgroundResource(followed
                ? com.fongmi.android.tv.R.drawable.bg_hg_followed_btn
                : com.fongmi.android.tv.R.drawable.bg_hg_follow_btn);
        binding.hgAuthorFollow.setTextColor(followed ? 0xFF4E5969 : 0xFFFFFFFF);
    }

    private void onFollowClicked() {
        if (followBusy) return;
        // 未登录先拉壳子统一登录（与 feed 互动同门），不落状态不假成功
        if (HgFeedSession.get().interactions.requiresLogin()) {
            boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
            if (!delivered) Toast.makeText(this, "登录界面暂时打不开，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (HgStore.isAuthorFollowed(uid)) {
            // 取关确认框：官方 PugcFollowButton$onClick$1$1 文案逐字
            new AlertDialog.Builder(this)
                    .setMessage("不再关注该作者？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("不再关注", (d, w) -> applyFollow(false))
                    .show();
        } else {
            applyFollow(true);
        }
    }

    /** 乐观先行＋真接口收口：失败回滚镜像并如实提示（互动层同口径）。 */
    private void applyFollow(boolean follow) {
        followBusy = true;
        HgStore.setAuthorFollowed(uid, follow);
        refreshFollowButton();
        HgFeedSession.get().fqClient.doFollowAction(follow, uid, new HgCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                mainHandler.post(() -> {
                    followBusy = false;
                    refreshFollowButton();
                });
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    followBusy = false;
                    HgStore.setAuthorFollowed(uid, !follow);
                    refreshFollowButton();
                    Toast.makeText(HongguoAuthorActivity.this,
                            follow ? "关注没成功，再试试" : "取关没成功，再试试",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
}
