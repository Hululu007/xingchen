package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.cloud.CloudDrive;
import com.fongmi.android.tv.cloud.CloudFile;
import com.fongmi.android.tv.cloud.drive115.Drive115;
import com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak;
import com.fongmi.android.tv.cloud.drivequark.DriveQuark;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.music.CloudMusicSource;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.PanLoginDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PanAuth;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网盘搜索页（单独一页，不用现有网盘登录页）。
 * 只做搜索，不做文件浏览／文件管理：页里 115、夸克两家；
 * 点一家就是搜这家的音频（Drive 列表筛音频＋关键词搜索），搜到点播，
 * 经代理递音乐播放器（115 走本机代理，与 115 视频一个路子）。
 * 别的网盘以后再加。登录态是搜索的前提：未登录先走各家现有登录法。
 */
public class MusicCloudActivity extends BaseActivity {

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicCloudActivity.class));
    }

    /** 程序化布局的 ViewBinding 壳（BaseActivity 要求 getBinding，不用布局 XML 就不用等生成类）。 */
    private static final class SimpleBinding implements ViewBinding {
        private final View root;

        SimpleBinding(View root) {
            this.root = root;
        }

        @Override
        public View getRoot() {
            return root;
        }
    }

    /** 搜索命中：文件＋所在文件夹名（同名文件靠文件夹区分）。 */
    private static final class Hit {
        final CloudFile file;
        final String parent;

        Hit(CloudFile file, String parent) {
            this.file = file;
            this.parent = parent;
        }
    }

    private static final String[] AUDIO_EXT = {
            "mp3", "flac", "wav", "aac", "ogg", "oga", "m4a", "opus", "ape", "wma", "aif", "aiff"
    };
    private static final int PAGE_LIMIT = 100;
    private static final int MAX_DIRS = 300;
    private static final int MAX_FILES = 10000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout pickerView;
    private LinearLayout searchView;
    private TextView tvSub115;
    private TextView tvSubQuark;
    private TextView tvSubPikPak;
    private TextView tvDriveName;
    private EditText etKeyword;
    private ImageView ivCloudClear;
    private LinearLayout loadingView;
    private TextView tvLoadingCount;
    private TextView tvEmptyState;
    private LinearLayout errorView;
    private TextView tvError;
    private TextView tvTail;
    private ListView lvResults;
    private ResultAdapter adapter;

    private final List<Hit> results = new ArrayList<>();
    private String currentDrive;
    private int searchGen;
    private volatile boolean destroyed;
    private int qrLoginGen;

    @Override
    protected ViewBinding getBinding() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFAFAFC);
        int side = ResUtil.dp2px(16);
        root.setPadding(side, getStatusBarHeight() + ResUtil.dp2px(8), side, ResUtil.dp2px(16));

        TextView title = new TextView(this);
        title.setText(R.string.music_cloud_drive);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF0C0E12);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMinimumHeight(ResUtil.dp2px(48));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pickerView = new LinearLayout(this);
        pickerView.setOrientation(LinearLayout.VERTICAL);
        root.addView(pickerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pickerView.addView(buildDriveCard(Drive115.ID, "115"));
        pickerView.addView(buildDriveCard(DriveQuark.ID, "夸克"));
        pickerView.addView(buildDriveCard(DrivePikPak.ID, "PikPak"));

        searchView = new LinearLayout(this);
        searchView.setOrientation(LinearLayout.VERTICAL);
        searchView.setVisibility(View.GONE);
        root.addView(searchView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        tvDriveName = new TextView(this);
        tvDriveName.setTextSize(16);
        tvDriveName.setTypeface(Typeface.DEFAULT_BOLD);
        tvDriveName.setTextColor(0xFF0C0E12);
        tvDriveName.setPadding(0, ResUtil.dp2px(4), 0, ResUtil.dp2px(12));
        searchView.addView(tvDriveName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第三波 B⑤：搜索页顶栏文字入口（不放自带返回箭头，铁律）：返回选盘／切换盘／退出登录
        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.bottomMargin = ResUtil.dp2px(8);
        searchView.addView(topActions, topLp);
        TextView backPicker = new TextView(this);
        backPicker.setText("返回选盘");
        backPicker.setTextSize(13);
        backPicker.setTextColor(0xFFFA243C);
        backPicker.setPadding(0, ResUtil.dp2px(4), ResUtil.dp2px(16), ResUtil.dp2px(4));
        backPicker.setOnClickListener(v -> showPicker());
        topActions.addView(backPicker);
        TextView switchDrive = new TextView(this);
        switchDrive.setText("切换盘");
        switchDrive.setTextSize(13);
        switchDrive.setTextColor(0xFFFA243C);
        switchDrive.setPadding(0, ResUtil.dp2px(4), ResUtil.dp2px(16), ResUtil.dp2px(4));
        switchDrive.setOnClickListener(v -> showPicker());
        topActions.addView(switchDrive);
        TextView logout = new TextView(this);
        logout.setText("退出登录");
        logout.setTextSize(13);
        logout.setTextColor(0xFFFA243C);
        logout.setPadding(0, ResUtil.dp2px(4), 0, ResUtil.dp2px(4));
        logout.setOnClickListener(v -> {
            if (currentDrive == null) return;
            String name = tvDriveName.getText() == null ? "" : tvDriveName.getText().toString();
            String id = currentDrive;
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage("退出当前 " + name + " 登录？")
                    .setPositiveButton("退出登录", (d, w) -> {
                        if (Drive115.ID.equals(id)) PanAuth.clear115();
                        else PanAuth.clearQuark();
                        refreshSub();
                        Notify.show("已退出 " + name + " 登录");
                        showPicker();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        topActions.addView(logout);

        // 第三波 B①：搜索条按首页胶囊——48dp 圆角 24dp 白卡＋图标＋清除键，系统 Button 已去掉
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackgroundResource(R.drawable.home_bg_search);
        searchRow.setPadding(ResUtil.dp2px(12), 0, ResUtil.dp2px(8), 0);
        searchView.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(48)));
        ImageView ivSearch = new ImageView(this);
        ivSearch.setImageResource(R.drawable.ic_music_search);
        ivSearch.setLayoutParams(new LinearLayout.LayoutParams(ResUtil.dp2px(20), ResUtil.dp2px(20)));
        ivSearch.setOnClickListener(v -> runSearch());
        searchRow.addView(ivSearch);
        etKeyword = new EditText(this);
        etKeyword.setHint("输入歌名搜索，不填列出全部音频");
        etKeyword.setSingleLine(true);
        etKeyword.setInputType(InputType.TYPE_CLASS_TEXT);
        etKeyword.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        etKeyword.setTextSize(15);
        etKeyword.setBackground(null);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        etLp.leftMargin = ResUtil.dp2px(8);
        searchRow.addView(etKeyword, etLp);
        ivCloudClear = new ImageView(this);
        ivCloudClear.setImageResource(R.drawable.ic_music_close);
        ivCloudClear.setLayoutParams(new LinearLayout.LayoutParams(ResUtil.dp2px(20), ResUtil.dp2px(20)));
        ivCloudClear.setVisibility(View.GONE);
        ivCloudClear.setOnClickListener(v -> etKeyword.setText(""));
        searchRow.addView(ivCloudClear);
        etKeyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ivCloudClear.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 第三波 B③：三态拆独立视图——加载（转圈＋已扫项数）／空态／失败（＋重试）
        loadingView = new LinearLayout(this);
        loadingView.setOrientation(LinearLayout.HORIZONTAL);
        loadingView.setGravity(Gravity.CENTER_VERTICAL);
        loadingView.setPadding(0, ResUtil.dp2px(10), 0, ResUtil.dp2px(6));
        loadingView.setVisibility(View.GONE);
        ProgressBar pb = new ProgressBar(this);
        loadingView.addView(pb, new LinearLayout.LayoutParams(ResUtil.dp2px(20), ResUtil.dp2px(20)));
        tvLoadingCount = new TextView(this);
        tvLoadingCount.setTextSize(13);
        tvLoadingCount.setTextColor(0xFF8E8E93);
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lcLp.leftMargin = ResUtil.dp2px(8);
        loadingView.addView(tvLoadingCount, lcLp);
        searchView.addView(loadingView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvEmptyState = new TextView(this);
        tvEmptyState.setTextSize(14);
        tvEmptyState.setTextColor(0xFF8E8E93);
        tvEmptyState.setGravity(Gravity.CENTER);
        tvEmptyState.setPadding(0, ResUtil.dp2px(24), 0, 0);
        tvEmptyState.setVisibility(View.GONE);
        searchView.addView(tvEmptyState, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        errorView = new LinearLayout(this);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER_HORIZONTAL);
        errorView.setPadding(0, ResUtil.dp2px(24), 0, 0);
        errorView.setVisibility(View.GONE);
        tvError = new TextView(this);
        tvError.setTextSize(14);
        tvError.setTextColor(0xFFFF3B30);
        tvError.setGravity(Gravity.CENTER);
        errorView.addView(tvError);
        TextView btnRetry = new TextView(this);
        btnRetry.setText("重试");
        btnRetry.setTextSize(14);
        btnRetry.setTextColor(0xFFFA243C);
        btnRetry.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(8), ResUtil.dp2px(16), ResUtil.dp2px(8));
        btnRetry.setOnClickListener(v -> runSearch());
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryLp.topMargin = ResUtil.dp2px(12);
        errorView.addView(btnRetry, retryLp);
        searchView.addView(errorView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        lvResults = new ListView(this);
        lvResults.setDivider(null);
        adapter = new ResultAdapter();
        tvTail = new TextView(this);
        tvTail.setTextSize(12);
        tvTail.setTextColor(0xFF8E8E93);
        tvTail.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(12), ResUtil.dp2px(8));
        lvResults.addFooterView(tvTail);
        lvResults.setAdapter(adapter);
        searchView.addView(lvResults, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                runSearch();
                return true;
            }
            return false;
        });
        lvResults.setOnItemClickListener((parent, view, position, id) -> onResultClick(position));

        return new SimpleBinding(root);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        CloudMusicSource.ensureRegistered();
        refreshSub();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSub();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        searchGen++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onBackInvoked() {
        if (searchView.getVisibility() == View.VISIBLE) {
            showPicker();
        } else {
            super.onBackInvoked();
        }
    }

    // ================= 选盘 =================

    private LinearLayout buildDriveCard(String driveId, String driveName) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        // 第三波 B④：选盘卡改实色白卡＋盘图标
        card.setBackgroundResource(R.drawable.card_music);
        card.setClickable(true);
        card.setFocusable(true);
        try {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (tv.resourceId != 0 && androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId) != null) {
                card.setForeground(androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId));
            }
        } catch (Throwable ignored) {
        }
        card.setMinimumHeight(ResUtil.dp2px(64));
        card.setPadding(ResUtil.dp2px(15), ResUtil.dp2px(15), ResUtil.dp2px(15), ResUtil.dp2px(15));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = ResUtil.dp2px(12);
        card.setLayoutParams(cardLp);

        ImageView driveIcon = new ImageView(this);
        driveIcon.setImageResource(R.drawable.ic_music_note);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                ResUtil.dp2px(40), ResUtil.dp2px(40));
        iconLp.rightMargin = ResUtil.dp2px(12);
        driveIcon.setLayoutParams(iconLp);
        card.addView(driveIcon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView name = new TextView(this);
        name.setText(driveName);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(0xFF0C0E12);
        TextView sub = new TextView(this);
        sub.setTextSize(13);
        sub.setTextColor(0xFF1B2431);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = ResUtil.dp2px(3);
        sub.setLayoutParams(subLp);
        texts.addView(name);
        texts.addView(sub);
        card.addView(texts);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chev_v2);
        chev.setLayoutParams(new LinearLayout.LayoutParams(ResUtil.dp2px(18), ResUtil.dp2px(18)));
        card.addView(chev);

        if (Drive115.ID.equals(driveId)) {
            tvSub115 = sub;
        } else if (DriveQuark.ID.equals(driveId)) {
            tvSubQuark = sub;
        } else {
            tvSubPikPak = sub;
        }
        card.setOnClickListener(v -> onDriveClick(driveId, driveName));
        card.setOnLongClickListener(v -> onDriveLongClick(driveId, driveName));
        return card;
    }

    private void refreshSub() {
        if (tvSub115 != null) {
            if (PanAuth.is115LoggedIn()) {
                String vip = PanAuth.get115Vip();
                tvSub115.setText(vip.isEmpty() ? "已登录，点击进入搜索" : "已登录 · " + vip);
            } else {
                tvSub115.setText("未登录");
            }
        }
        if (tvSubQuark != null) {
            if (PanAuth.isQuarkLoggedIn()) {
                String vip = PanAuth.getQuarkVip();
                tvSubQuark.setText(vip.isEmpty() ? "已登录，点击进入搜索" : "已登录 · " + vip);
            } else {
                tvSubQuark.setText("未登录");
            }
        }
        if (tvSubPikPak != null) {
            if (PanAuth.isPikPakLoggedIn()) {
                String vip = PanAuth.getPikPakVip();
                tvSubPikPak.setText(vip.isEmpty() ? "已登录，点击进入搜索" : "已登录 · " + vip);
            } else {
                tvSubPikPak.setText("未登录");
            }
        }
    }

    /** 网盘音乐队列平台戳：与 CloudDriveActivity 音频起播口径一致（pan + 家 + : + fid）。 */
    private static String drivePrefix(String driveId) {
        if (Drive115.ID.equals(driveId)) return "pan115:";
        if (DriveQuark.ID.equals(driveId)) return "panquark:";
        return "panpikpak:";
    }

    private static boolean isDriveLoggedIn(String driveId) {
        if (Drive115.ID.equals(driveId)) return PanAuth.is115LoggedIn();
        if (DriveQuark.ID.equals(driveId)) return PanAuth.isQuarkLoggedIn();
        return PanAuth.isPikPakLoggedIn();
    }

    private void onDriveClick(String driveId, String driveName) {
        boolean loggedIn = isDriveLoggedIn(driveId);
        if (loggedIn) {
            enterSearch(driveId, driveName);
            return;
        }
        // PikPak 没有 115／夸克那套扫码／网页／Cookie 登录法（它的登录在网盘登录页走网页／账号密码），
        // 未登录时直接进现有网盘登录页，回来时 onResume 会刷新副文案，不在这里另造一套登录 UI。
        if (DrivePikPak.ID.equals(driveId)) {
            PanLoginActivity.start(this);
            return;
        }
        showLoginMethods(driveName, new String[]{"扫码登录", "网页登录（支持账号密码）", "导入 Cookie"}, which -> {
            if (which == 0) {
                if (Drive115.ID.equals(driveId)) showQrLogin115(driveId, driveName);
                else showQrLoginQuark(driveId, driveName);
            } else if (which == 1) {
                if (Drive115.ID.equals(driveId)) base115Dialog(driveId, driveName).showLogin(this);
                else baseQuarkDialog(driveId, driveName).showLogin(this);
            } else {
                showCookieImport(driveId, driveName);
            }
        });
    }

    private boolean onDriveLongClick(String driveId, String driveName) {
        boolean loggedIn = isDriveLoggedIn(driveId);
        if (!loggedIn) return false;
        new AlertDialog.Builder(this)
                .setTitle(driveName)
                .setMessage("退出当前 " + driveName + " 登录？")
                .setPositiveButton("退出登录", (d, w) -> {
                    if (Drive115.ID.equals(driveId)) PanAuth.clear115();
                    else if (DriveQuark.ID.equals(driveId)) PanAuth.clearQuark();
                    else PanAuth.clearPikPak();
                    refreshSub();
                    Notify.show("已退出 " + driveName + " 登录");
                })
                .setNegativeButton("取消", null)
                .show();
        return true;
    }

    // ================= 登录（复用各家现有登录法，不重造） =================

    private void showLoginMethods(String title, String[] methods, OnMethodSelected cb) {
        new AlertDialog.Builder(this)
                .setTitle(title + "登录方式")
                .setItems(methods, (d, which) -> cb.onSelect(which))
                .setNegativeButton("取消", null)
                .show();
    }

    private interface OnMethodSelected {
        void onSelect(int which);
    }

    private void showCookieImport(String driveId, String driveName) {
        // PikPak 不走 Cookie 导入（误走到这里会把它的串存成夸克 Cookie），直接拦回网盘登录页。
        if (DrivePikPak.ID.equals(driveId)) {
            Notify.show("PikPak 请在网盘登录页登录");
            PanLoginActivity.start(this);
            return;
        }
        EditText et = new EditText(this);
        et.setHint("粘贴 Cookie 字符串");
        et.setMinLines(4);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad = ResUtil.dp2px(24);
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("导入 " + driveName + " Cookie")
                .setView(layout)
                .setPositiveButton("导入", (d, w) -> {
                    String cookie = et.getText().toString().trim();
                    if (TextUtils.isEmpty(cookie)) {
                        Notify.show("Cookie 是空的");
                        return;
                    }
                    if (Drive115.ID.equals(driveId)) {
                        if (!PanAuth.is115CookieValid(cookie)) {
                            Notify.show("Cookie 无效：缺少 115 登录关键字段");
                            return;
                        }
                        PanAuth.put115Cookie(cookie);
                    } else {
                        PanAuth.putQuarkCookie(cookie);
                    }
                    refreshSub();
                    Notify.show(driveName + " Cookie 已导入");
                    enterSearch(driveId, driveName);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private PanLoginDialog base115Dialog(String driveId, String driveName) {
        return PanLoginDialog.create()
                .title("115")
                .loginUrl("https://115.com/")
                .cookieDomain("115.com")
                .userAgent(PanLoginDialog.DESKTOP_UA)
                .hint("请在下方页面完成 115 登录（支持账号密码），然后点「完成登录」")
                .cookieValidator(PanAuth::is115CookieValid)
                .onLogin(cookie -> {
                    PanAuth.put115Cookie(cookie);
                    refreshSub();
                    Notify.show("115 登录成功");
                    enterSearch(driveId, driveName);
                });
    }

    private PanLoginDialog baseQuarkDialog(String driveId, String driveName) {
        return PanLoginDialog.create()
                .title("夸克")
                .loginUrl("https://pan.quark.cn/")
                .cookieDomain("quark.cn")
                .userAgent(PanLoginDialog.DESKTOP_UA)
                .hint("请在下方页面完成夸克登录（支持账号密码），然后点「完成登录」")
                .onLogin(cookie -> {
                    PanAuth.putQuarkCookie(cookie);
                    refreshSub();
                    Notify.show("夸克登录成功");
                    enterSearch(driveId, driveName);
                });
    }

    private void showQrLogin115(String driveId, String driveName) {
        LoginManager.get().login115(this, new LoginManager.LoginCallback() {
            @Override
            public void onSuccess(String providerId, String cookie) {
                refreshSub();
                Notify.show("115 登录成功");
                enterSearch(driveId, driveName);
            }

            @Override
            public void onError(String msg) {
                Notify.show("登录失败：" + msg);
            }

            @Override
            public void onCancel() {
            }
        });
    }

    private void showQrLoginQuark(String driveId, String driveName) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = ResUtil.dp2px(24);
        layout.setPadding(pad, pad, pad, pad);
        ImageView qrView = new ImageView(this);
        int size = ResUtil.dp2px(220);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        layout.addView(qrView);
        TextView tvStatusQr = new TextView(this);
        tvStatusQr.setText("正在获取二维码…");
        tvStatusQr.setTextSize(14);
        tvStatusQr.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ResUtil.dp2px(12);
        tvStatusQr.setLayoutParams(lp);
        layout.addView(tvStatusQr);

        final int qrGen = ++qrLoginGen;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("夸克扫码登录")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .create();
        dialog.setOnDismissListener(d -> {
            if (qrGen == qrLoginGen) qrLoginGen++;
            handler.removeCallbacksAndMessages(null);
        });
        dialog.show();

        DriveQuark drive = new DriveQuark();
        new Thread(() -> {
            try {
                DriveQuark.QrSession session = drive.qrStart();
                Bitmap bmp = QRCode.getBitmap(session.qrUrl, 220, 1);
                runOnUiThread(() -> {
                    if (qrGen != qrLoginGen || destroyed) return;
                    if (bmp != null) qrView.setImageBitmap(bmp);
                    tvStatusQr.setText("请用夸克 App 内扫一扫（不要用系统相机/微信扫）");
                });
                Runnable poll = new Runnable() {
                    @Override
                    public void run() {
                        if (qrGen != qrLoginGen || destroyed) return;
                        new Thread(() -> {
                            try {
                                int r = drive.qrPoll(session);
                                if (qrGen != qrLoginGen || destroyed) return;
                                if (r == 2) {
                                    boolean ok = drive.qrLogin(session);
                                    if (qrGen != qrLoginGen || destroyed) return;
                                    runOnUiThread(() -> {
                                        if (qrGen != qrLoginGen || destroyed || isFinishing()) return;
                                        dialog.dismiss();
                                        if (ok) {
                                            PanAuth.putQuarkCookie(drive.getCookie());
                                            refreshSub();
                                            Notify.show("夸克登录成功");
                                            enterSearch(driveId, driveName);
                                        } else {
                                            Notify.show("登录失败，请重试");
                                        }
                                    });
                                } else if (r == -1) {
                                    runOnUiThread(() -> {
                                        if (qrGen == qrLoginGen && !destroyed) tvStatusQr.setText("二维码已失效，请关闭重试");
                                    });
                                } else {
                                    runOnUiThread(() -> {
                                        if (qrGen == qrLoginGen && !destroyed) tvStatusQr.setText("等待扫码…");
                                    });
                                    handler.postDelayed(this, 2500);
                                }
                            } catch (Exception e) {
                                if (qrGen != qrLoginGen || destroyed) return;
                                runOnUiThread(() -> {
                                    if (qrGen == qrLoginGen && !destroyed) tvStatusQr.setText("网络异常，稍后重试");
                                });
                                handler.postDelayed(this, 2500);
                            }
                        }).start();
                    }
                };
                if (qrGen == qrLoginGen && !destroyed) handler.postDelayed(poll, 2500);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (qrGen == qrLoginGen && !destroyed) tvStatusQr.setText("获取二维码失败：" + e.getMessage());
                });
            }
        }).start();
    }

    // ================= 搜索 =================

    private void enterSearch(String driveId, String driveName) {
        currentDrive = driveId;
        searchGen++;
        results.clear();
        adapter.notifyDataSetChanged();
        tvDriveName.setText(driveName);
        hideCloudStates();
        if (tvTail != null) tvTail.setVisibility(View.GONE);
        pickerView.setVisibility(View.GONE);
        searchView.setVisibility(View.VISIBLE);
    }

    private void hideCloudStates() {
        if (loadingView != null) loadingView.setVisibility(View.GONE);
        if (tvEmptyState != null) tvEmptyState.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
    }

    private void showPicker() {
        searchGen++;
        currentDrive = null;
        searchView.setVisibility(View.GONE);
        pickerView.setVisibility(View.VISIBLE);
        refreshSub();
    }

    private void runSearch() {
        if (currentDrive == null) return;
        final int gen = ++searchGen;
        final String driveId = currentDrive;
        final String kw = etKeyword.getText().toString().trim().toLowerCase();
        results.clear();
        adapter.notifyDataSetChanged();
        hideCloudStates();
        if (tvTail != null) tvTail.setVisibility(View.GONE);
        loadingView.setVisibility(View.VISIBLE);
        tvLoadingCount.setText("已扫 0 项");
        new Thread(() -> {
            CloudDrive drive;
            if (Drive115.ID.equals(driveId)) drive = new Drive115();
            else if (DriveQuark.ID.equals(driveId)) drive = new DriveQuark();
            else drive = new DrivePikPak(MusicCloudActivity.this);
            ArrayDeque<String> queue = new ArrayDeque<>();
            Map<String, String> dirNames = new HashMap<>();
            String root = Drive115.ID.equals(driveId) ? "0" : "";
            queue.add(root);
            dirNames.put(root, "根目录");
            dirNames.put("0", "根目录");
            int dirsVisited = 0;
            int filesScanned = 0;
            // 截断口径：filesScanned 计全部扫描条目（文件夹＋文件、音频＋非音频），
            // 是遍历预算不是音频首数；文案写“项”不写“首”，与 filesScanned 口径对齐。
            boolean dirsTruncated = false;
            boolean filesTruncated = false;
            List<Hit> batch = new ArrayList<>();
            try {
                while (!queue.isEmpty() && gen == searchGen && !destroyed) {
                    if (dirsVisited >= MAX_DIRS) {
                        dirsTruncated = true;
                        break;
                    }
                    if (filesScanned >= MAX_FILES) {
                        filesTruncated = true;
                        break;
                    }
                    String cid = queue.poll();
                    dirsVisited++;
                    int offset = 0;
                    while (gen == searchGen && !destroyed) {
                        List<CloudFile> page = drive.listDir(cid, offset, PAGE_LIMIT);
                        if (page == null || page.isEmpty()) break;
                        for (CloudFile f : page) {
                            if (gen != searchGen || destroyed) break;
                            filesScanned++;
                            if (f.isDir()) {
                                if (dirsVisited + queue.size() < MAX_DIRS) {
                                    queue.add(f.getId());
                                    dirNames.put(f.getId(), f.getName());
                                } else {
                                    // 超上限的文件夹直接不进队列：结果可能不全，立截断标志，
                                    // 收尾文案如实写，不许再只报“找到 N 首”。
                                    dirsTruncated = true;
                                }
                            } else if (isAudio(f.getName())
                                    && (kw.isEmpty() || f.getName().toLowerCase().contains(kw))) {
                                String parent = dirNames.get(f.getParentCid());
                                batch.add(new Hit(f, parent == null ? "" : parent));
                                if (batch.size() >= 25) {
                                    flushBatch(gen, batch);
                                    batch = new ArrayList<>();
                                }
                            }
                            if (filesScanned >= MAX_FILES) {
                                filesTruncated = true;
                                break;
                            }
                        }
                        if (filesTruncated) break;
                        if (page.size() < PAGE_LIMIT) break;
                        offset += page.size();
                    }
                }
                if (!batch.isEmpty()) flushBatch(gen, batch);
                StringBuilder trunc = new StringBuilder();
                if (dirsTruncated) trunc.append("前 ").append(MAX_DIRS).append(" 个文件夹");
                if (filesTruncated) {
                    if (trunc.length() > 0) trunc.append("、");
                    trunc.append("前 ").append(MAX_FILES).append(" 项");
                }
                final String truncSuffix = trunc.length() == 0 ? ""
                        : "（仅搜了" + trunc + "，结果可能不全）";
                runOnUiThread(() -> {
                    if (gen != searchGen) return;
                    hideCloudStates();
                    if (results.isEmpty()) {
                        tvEmptyState.setVisibility(View.VISIBLE);
                        tvEmptyState.setText("没找到" + truncSuffix + "\n换个歌名试试");
                        if (tvTail != null) tvTail.setVisibility(View.GONE);
                    } else {
                        // 截断后缀保留在结果尾（ListView 尾行），不在状态行里挤
                        if (tvTail != null && !truncSuffix.isEmpty()) {
                            tvTail.setText(truncSuffix);
                            tvTail.setVisibility(View.VISIBLE);
                        } else if (tvTail != null) {
                            tvTail.setVisibility(View.GONE);
                        }
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen != searchGen) return;
                    hideCloudStates();
                    tvError.setText("搜索失败：" + e.getMessage());
                    errorView.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private void flushBatch(int gen, List<Hit> batch) {
        List<Hit> copy = new ArrayList<>(batch);
        runOnUiThread(() -> {
            if (gen != searchGen) return;
            results.addAll(copy);
            adapter.notifyDataSetChanged();
            if (loadingView != null && loadingView.getVisibility() == View.VISIBLE) {
                tvLoadingCount.setText("已扫 " + results.size() + " 首");
            }
        });
    }

    private static boolean isAudio(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        for (String e : AUDIO_EXT) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    private static String stripExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void onResultClick(int position) {
        if (position < 0 || position >= results.size() || currentDrive == null) return;
        List<MusicTrack> queue = new ArrayList<>();
        for (Hit h : results) {
            String name = h.file.getName();
            String id = drivePrefix(currentDrive) + h.file.getId();
            MusicTrack t = new MusicTrack(id, stripExt(name), "", "", 0, null);
            t.platform = CloudMusicSource.PLATFORM;
            try {
                JSONObject o = new JSONObject();
                o.put("drive", currentDrive);
                o.put("fid", h.file.getId());
                o.put("name", name);
                o.put("pickCode", h.file.getPickCode() == null ? "" : h.file.getPickCode());
                t.rawJson = o.toString();
            } catch (Exception ignored) {
            }
            queue.add(t);
        }
        MusicController.get().play(queue, position);
        MusicPlayerActivity.start(this);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etKeyword.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return ResUtil.dp2px(24);
    }

    // ================= 结果列表 =================

    private final class ResultAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return results.size();
        }

        @Override
        public Hit getItem(int position) {
            return results.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // 第三波 B②：结果行按 item_music_track.xml——封面 50dp、名 14sp／行高 20sp、
            // 第二行 11sp、按压反馈＋分隔线、格式角标；在播行红 #FA243C（可选口径已做）。
            // CloudFile.thumbUrl 有数据时画出来（115／夸克回包无此字段时走素图占位，见 ImgUtil）。
            ImageView coverView;
            TextView nameView;
            TextView subView;
            TextView badgeView;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MusicCloudActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                try {
                    android.util.TypedValue tv = new android.util.TypedValue();
                    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                    if (tv.resourceId != 0 && androidx.core.content.ContextCompat.getDrawable(
                            MusicCloudActivity.this, tv.resourceId) != null) {
                        row.setForeground(androidx.core.content.ContextCompat.getDrawable(
                                MusicCloudActivity.this, tv.resourceId));
                    }
                } catch (Throwable ignored) {
                }
                LinearLayout content = new LinearLayout(MusicCloudActivity.this);
                content.setOrientation(LinearLayout.HORIZONTAL);
                content.setGravity(Gravity.CENTER_VERTICAL);
                int pad = ResUtil.dp2px(14);
                content.setPadding(pad, ResUtil.dp2px(10), pad, ResUtil.dp2px(10));
                coverView = new ImageView(MusicCloudActivity.this);
                coverView.setLayoutParams(new LinearLayout.LayoutParams(
                        ResUtil.dp2px(50), ResUtil.dp2px(50)));
                coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                content.addView(coverView);
                LinearLayout texts = new LinearLayout(MusicCloudActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                textsLp.leftMargin = ResUtil.dp2px(14);
                content.addView(texts, textsLp);
                nameView = new TextView(MusicCloudActivity.this);
                nameView.setTextSize(14);
                nameView.setTextColor(0xFF0C0E12);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                if (android.os.Build.VERSION.SDK_INT >= 28) nameView.setLineHeight(ResUtil.sp2px(20));
                else nameView.setLineSpacing(0, 20f / 14f);
                texts.addView(nameView);
                LinearLayout second = new LinearLayout(MusicCloudActivity.this);
                second.setOrientation(LinearLayout.HORIZONTAL);
                second.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                secondLp.topMargin = ResUtil.dp2px(4);
                texts.addView(second, secondLp);
                subView = new TextView(MusicCloudActivity.this);
                subView.setTextSize(11);
                subView.setTextColor(0xFF9AA7B8);
                subView.setSingleLine(true);
                subView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                second.addView(subView);
                badgeView = new TextView(MusicCloudActivity.this);
                badgeView.setTextSize(11);
                badgeView.setTextColor(0xFFFA243C);
                badgeView.setBackgroundResource(R.drawable.badge_music);
                badgeView.setPadding(ResUtil.dp2px(6), ResUtil.dp2px(2),
                        ResUtil.dp2px(6), ResUtil.dp2px(2));
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                badgeLp.leftMargin = ResUtil.dp2px(8);
                second.addView(badgeView, badgeLp);
                row.addView(content);
                View divider = new View(MusicCloudActivity.this);
                divider.setBackgroundColor(0xFFE5E5EA);
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                divLp.leftMargin = ResUtil.dp2px(78);
                divLp.rightMargin = ResUtil.dp2px(14);
                row.addView(divider, divLp);
                row.setTag(new Object[]{coverView, nameView, subView, badgeView});
                convertView = row;
            } else {
                Object[] holder = (Object[]) convertView.getTag();
                coverView = (ImageView) holder[0];
                nameView = (TextView) holder[1];
                subView = (TextView) holder[2];
                badgeView = (TextView) holder[3];
            }
            Hit hit = getItem(position);
            String name = hit.file.getName();
            nameView.setText(name);
            String sub = (hit.parent == null || hit.parent.isEmpty() ? "" : hit.parent + " · ")
                    + hit.file.getFormatSize();
            subView.setText(sub);
            String ext = "";
            if (name != null) {
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && dot + 1 < name.length()) ext = name.substring(dot + 1).toUpperCase();
            }
            if (ext.isEmpty()) {
                badgeView.setVisibility(View.GONE);
            } else {
                badgeView.setVisibility(View.VISIBLE);
                badgeView.setText(ext);
            }
            String thumb = hit.file.getThumbUrl();
            ImgUtil.loadRounded(name == null ? "" : name, thumb == null ? "" : thumb, coverView);
            // 在播行红：与播放器当前曲 id 比对（drivePrefix + fid，与点播入队口径一致）
            boolean playing = false;
            try {
                com.fongmi.android.tv.bean.music.MusicTrack cur = MusicController.get().current();
                String want = drivePrefix(currentDrive) + hit.file.getId();
                playing = cur != null && want.equals(cur.id);
            } catch (Throwable ignored) {
            }
            nameView.setTextColor(playing ? 0xFFFA243C : 0xFF0C0E12);
            return convertView;
        }
    }
}
