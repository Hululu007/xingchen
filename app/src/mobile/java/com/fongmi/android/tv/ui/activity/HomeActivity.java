package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.node.NodeRuntime;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.emby.EmbyMode;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.receiver.ShortcutReceiver;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.ModuleFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.ui.lab.LabHomeActivity;
import com.fongmi.android.tv.ui.lab.LabSettingActivity;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.TxtParser;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.ai.AiFloatManager;
import com.fongmi.android.tv.web.WebHomeChrome;
import com.fongmi.android.tv.web.WebHomeChromeStartup;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.github.catvod.net.OkHttp;
import com.google.android.material.navigation.NavigationBarView;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.Subscribe;
import org.json.JSONArray;
import org.json.JSONObject;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HomeActivity extends BaseActivity implements NavigationBarView.OnItemSelectedListener, WebHomeChromeController.Host, com.fongmi.android.tv.ui.minibar.MiniBarHost, AiFloatManager.AiFloatHost {

    public static final String EXTRA_NAV_POSITION = "nav_position";
    private static final String STATE_CURRENT_POSITION = "currentPosition";

    // 在场计数（上游同名口径）：NodeRuntime.releaseIfUnused 靠它判首页
    // 还在不在；主题切换 recreate 时新实例先建、旧实例后销，计数不归零，
    // 不会误触释放。
    private static volatile int instanceCount;

    public static boolean isRunning() {
        return instanceCount > 0;
    }

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private WebHomeChromeController mChrome;
    private Config mStartupConfig;
    private boolean wideWindow;
    private int currentPosition;

    // 首页顶是壁纸/网页深色区，现白图标看得见，保持原样（BaseActivity 默认已改深色图标，这里盖回）。
    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        instanceCount++;
        androidx.core.splashscreen.SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        wideWindow = MobileWindow.isWide(this);
        currentPosition = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_CURRENT_POSITION, 0);
        mStartupConfig = Config.vod();
        mChrome = new WebHomeChromeController(this, mBinding, this, savedInstanceState, WebHomeChromeStartup.restore(mStartupConfig));
        mBinding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> checkWindowShape(right - left, bottom - top));
        mBinding.navigation.setOnItemSelectedListener(this);
        initNavigationInsets();
        PermissionUtil.requestFile(this, allGranted -> PermissionUtil.requestNotify(this));
        initFragment(savedInstanceState);
        // 实验室自启登记必须抢在 initConfig 之前：schedule 的同步段先把
        // LabNodeHost 宿主判据登记好（配置加载随时可能调 RuntimeModule
        // .NODEJS.require，登记晚于首读会误判没装）；延迟 boot 段随后拉起
        // 服务。此前排在 initConfig 之后，App.onCreate 的反射 kick 又被
        // R8 改名打死（proguard 已补 LabBoot 保名），冷启只剩这条腿还迟到。
        try {
            com.fongmi.android.tv.ui.lab.LabBoot.schedule(this);
        } catch (Exception ignored) {
        }
        initConfig();
        // Emby 盖子重挂：进程重启/recreate 后 active 标志还在但盖子视图随旧实例没了，
        // 按 isActive 走 EmbyMode 同一条 attach 路重挂（失败会回滚标志，见 EmbyMode）。
        EmbyMode.reattachIfActive(this);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_CURRENT_POSITION, currentPosition);
        if (mChrome != null) mChrome.save(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setNavigation();
        // 同 initView 的 Emby 盖子重挂兜底：盖子已在展示时内部直接返回。
        EmbyMode.reattachIfActive(this);
    }

    @Override
    protected void initEvent() {
        View liveView = mBinding.navigation.findViewById(R.id.live);
        if (liveView != null) liveView.setOnLongClickListener(this::addShortcut);
    }

    private void checkAction(Intent intent) {
        if (intent.hasExtra(EXTRA_NAV_POSITION)) {
            change(intent.getIntExtra(EXTRA_NAV_POSITION, 0));
            intent.removeExtra(EXTRA_NAV_POSITION);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            String url = com.fongmi.android.tv.utils.Sniffer.getUrl(sharedText);
            if (url == null || url.isEmpty()) url = sharedText;
            if (url != null && !url.isEmpty()) {
                String lower = url.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("magnet:") || lower.startsWith("ed2k:") || lower.startsWith("thunder:")) {
                    VideoActivity.push(this, sharedText);
                } else {
                    try {
                        com.fongmi.android.tv.utils.download.DownloadManager.get().add(url, null);
                        com.fongmi.android.tv.ui.activity.DownloadActivity.start(this);
                    } catch (IllegalArgumentException e) {
                        // Emby 总闸：Emby 内容不允许下载
                        com.fongmi.android.tv.utils.Notify.show(e.getMessage());
                    }
                }
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        String path = UrlUtil.path(intent.getData());
        if ("text/plain".equals(intent.getType()) || path.endsWith(".m3u") || path.endsWith(".txt")) {
            // txt 混合链接导入播放：含 magnet:/ed2k:/thunder:/ftp: 即解析成选集列表播放
            // （ed2k 只认 scheme 前缀、不要求 //；纯 thunder／纯 ftp txt 同样进迅雷线）；
            // 纯 http 的 txt 仍走直播列表（兼容旧行为）。需求（2026-10-10）。
            String text = readText(intent.getData());
            if (text != null && TxtParser.isMixedLinks(text)) {
                List<TxtParser.Link> links = TxtParser.parse(text);
                if (links.isEmpty()) {
                    Notify.show("未找到可播放链接");
                } else {
                    playTxtLinks(intent.getData(), links);
                }
                return;
            }
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    private String readText(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 512 * 1024) break;
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void playTxtLinks(Uri uri, List<TxtParser.Link> links) {
        try {
            JSONArray arr = new JSONArray();
            for (TxtParser.Link link : links) {
                JSONObject obj = new JSONObject();
                obj.put("name", link.name);
                obj.put("url", link.url);
                arr.put(obj);
            }
            String filePath = FileChooser.getPathFromUri(uri);
            String name = filePath == null ? "TXT播放列表" : new File(filePath).getName();
            VideoActivity.startTxt(this, name, arr.toString());
        } catch (Exception e) {
            Notify.show("解析失败");
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> SettingPlayerFragment.newInstance();
            case 3 -> SettingDanmakuFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(0);
        else restorePosition(currentPosition);
    }

    private void restorePosition(int position) {
        setNavigation();
        syncNavigationSelection();
        changeFragment(position <= 0 ? 0 : Math.min(position, 3));
    }

    private void initConfig() {
        VodConfig.get().config(mStartupConfig == null ? Config.vod() : mStartupConfig).load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                resetVodChrome();
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        };
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    private void setNavigation() {
        mBinding.navigation.getMenu().findItem(R.id.vod).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.setting).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.live).setVisible(LiveConfig.hasUrl());
        mBinding.navigation.getMenu().findItem(R.id.music).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.reading).setVisible(true);
        syncOptionalTabs();
        syncNavigationSelection();
    }

    /** 底部导航菜单总数上限：Material 库写死，加第 7 个直接崩，1.3.53 就是这么死的。 */
    private static final int MAX_BOTTOM_TABS = 6;

    private void syncOptionalTabs() {
        // 可选格（实验室／小程序）与 6 格上限的关系，先把结论写死：
        // XML 本身已占 6 项（含不可见的小程序格），基础格（点播／直播／音乐／阅读）＋设置格
        // 已占 5 项，所以可选格永远只能二选一——实验室＋小程序同时上底栏就是 7 项，必崩，
        // 共存做不了，不是没做。实验室优先是旧口径，保持不变。
        // 限制做扎实：每次加格前都对总数守门，绝不加出第 7 项；小程序丢格时不丢数据：
        // 开关状态仍存着（FeaturesActivity），实验室关掉后底栏自动回来；且功能页小程序卡
        // 点开就能进小程序首页（见 FeaturesActivity），不靠底栏也能到。
        Menu menu = mBinding.navigation.getMenu();
        boolean wantLab = LabSettingActivity.isTabEnabled(this);
        menu.removeItem(R.id.lab);
        menu.removeItem(R.id.miniapp);
        menu.removeItem(R.id.setting);
        if (wantLab && menu.size() < MAX_BOTTOM_TABS) {
            menu.add(Menu.NONE, R.id.lab, Menu.NONE, getString(R.string.nav_lab)).setIcon(R.drawable.ic_nav_lab);
        } else if (FeaturesActivity.isMiniAppEnabled(this) && menu.size() < MAX_BOTTOM_TABS) {
            menu.add(Menu.NONE, R.id.miniapp, Menu.NONE, getString(R.string.nav_miniapp)).setIcon(R.drawable.ic_nav_miniapp);
        }
        if (menu.size() < MAX_BOTTOM_TABS) {
            menu.add(Menu.NONE, R.id.setting, Menu.NONE, getString(R.string.nav_setting)).setIcon(R.drawable.ic_nav_setting);
        }
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return true;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, LiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        setNavigationVisible(true);
        if (position < 2) selectNavigation(position);
        else changeFragment(position);
    }

    private void initNavigationInsets() {
        int baseMargin = getResources().getDimensionPixelSize(R.dimen.xc_bottombar_margin_bottom);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.navigation, (v, insets) -> {
            int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int target = baseMargin + navBar;
            if (lp.bottomMargin != target) {
                lp.bottomMargin = target;
                v.setLayoutParams(lp);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(mBinding.navigation);
    }

    /** MiniBarHost：迷你条贴在底栏上方，多留底栏高度。 */
    @Override
    public int miniBarBottomExtraPx() {
        try {
            int h = mBinding.navigation.getHeight();
            if (h > 0) return h;
            return Math.round(72 * getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return 0;
        }
    }

    public void setNavigationVisible(boolean visible) {
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mBinding.container.getLayoutParams();
        if (visible) {
            params.addRule(RelativeLayout.ABOVE, R.id.navigation);
            mBinding.navigation.setVisibility(View.VISIBLE);
        } else {
            params.removeRule(RelativeLayout.ABOVE);
            mBinding.navigation.setVisibility(View.GONE);
        }
        mBinding.container.setLayoutParams(params);
        mBinding.getRoot().requestApplyInsets();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case LIVE:
                setNavigation();
                break;
            case COMMON:
                setNavigation();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) recreate();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        EmbyMode.exit(); // 任何底部 tab 被点都先撤 Emby 专属首页的全屏盖子（没进时空转）
        setNavigationVisible(true);
        if (item.getItemId() == R.id.setting) return changeFragment(1);
        if (item.getItemId() == R.id.vod) return changeFragment(0);
        if (item.getItemId() == R.id.live) return openLive();
        if (item.getItemId() == R.id.music) {
            MusicActivity.start(this);
            return true;
        }
        if (item.getItemId() == R.id.reading) {
            ReadingShelfActivity.start(this);
            return true;
        }
        if (item.getItemId() == R.id.miniapp) {
            HkPageActivity.start(this);
            return true;
        }
        if (item.getItemId() == R.id.lab) {
            LabHomeActivity.start(this);
            return true;
        }
        return false;
    }

    private void selectNavigation(int position) {
        int itemId = position == 0 ? R.id.vod : R.id.setting;
        if (mBinding.navigation.getSelectedItemId() == itemId) changeFragment(position);
        else mBinding.navigation.setSelectedItemId(itemId);
    }

    private void syncNavigationSelection() {
        int itemId = currentPosition == 0 ? R.id.vod : R.id.setting;
        if (mBinding.navigation.getSelectedItemId() == itemId) return;
        mBinding.navigation.setOnItemSelectedListener(null);
        mBinding.navigation.setSelectedItemId(itemId);
        mBinding.navigation.setOnItemSelectedListener(this);
    }

    private boolean changeFragment(int position) {
        closeModuleFragment();
        boolean changed = mManager.change(position);
        if (changed) currentPosition = position;
        refreshWebHomeChromeLayout();
        refreshAiFloat();
        return changed;
    }

    // 手机端模块页入口：ModuleFragment 只能宿在 HomeActivity（它把 activity 强转
    // HomeActivity），所以不另起 Activity，直接以覆盖方式加在首页容器上。
    // 不走 FragmentStateManager 的页签位：模块页不是第五个页签，不动首页导航；
    // 切页签／返回时先把模块页收掉，底下页签原样恢复。
    public void showModuleFragment() {
        Fragment module = getSupportFragmentManager().findFragmentByTag("module");
        if (module != null && module.isVisible()) return;
        androidx.fragment.app.FragmentTransaction ft = getSupportFragmentManager().beginTransaction().setCustomAnimations(R.anim.xc_frag_fade_in, R.anim.xc_frag_fade_out);
        Fragment primary = getSupportFragmentManager().getPrimaryNavigationFragment();
        if (primary != null && primary != module) ft.hide(primary);
        if (module == null) {
            module = ModuleFragment.newInstance();
            ft.add(mBinding.container.getId(), module, "module");
        } else {
            ft.show(module);
        }
        ft.setPrimaryNavigationFragment(module).setReorderingAllowed(true).commit();
    }

    public boolean isModuleFragmentShown() {
        Fragment module = getSupportFragmentManager().findFragmentByTag("module");
        return module != null && module.isVisible();
    }

    private void closeModuleFragment() {
        Fragment module = getSupportFragmentManager().findFragmentByTag("module");
        if (module == null || !module.isVisible()) return;
        Fragment primary = getSupportFragmentManager().getPrimaryNavigationFragment();
        if (primary == module) {
            // 模块页就是主导航页：交给页签管理器切回当前页签，它会顺手藏起模块页。
            mManager.change(currentPosition);
            return;
        }
        androidx.fragment.app.FragmentTransaction ft = getSupportFragmentManager().beginTransaction().setCustomAnimations(R.anim.xc_frag_fade_in, R.anim.xc_frag_fade_out);
        ft.hide(module);
        if (primary != null) ft.show(primary).setPrimaryNavigationFragment(primary);
        ft.setReorderingAllowed(true).commit();
        if (primary == null) mManager.change(currentPosition);
    }

    /** D-02 页签信号：只在点播页签可见、非 Emby 专属首页、非网页沉浸态时挂球。
     * 挂载口径：BaseActivity.onResume 调 AiFloatManager.onResume（壳内页签切换不走 onResume，
     * 所以 changeFragment/onWebHomeChromeChanged 里另调 refreshAiFloat 补一次）。 */
    @Override
    public boolean isAiFloatHomePage() {
        if (mManager == null || !mManager.isVisible(0)) return false;
        if (EmbyMode.isActive()) return false;
        return !isWebHomeChromeActive() || !WebHomeChrome.IMMERSIVE.equals(getWebHomeChromeMode());
    }

    private void refreshAiFloat() {
        refreshAiFloatNow();
        // Emby 退出是 App.post 异步的：立即刷一次管页签切换，再排队刷一次管退出完成后的恢复
        //（退出先排队，刷新后排队，顺序上刷新一定在退出完成后跑）。
        try {
            App.post(this::refreshAiFloatNow);
        } catch (Exception ignored) {
        }
    }

    private void refreshAiFloatNow() {
        try {
            AiFloatManager.get().refresh(this);
        } catch (Exception ignored) {
        }
    }

    private void refreshWebHomeChromeLayout() {
        if (mChrome != null) mChrome.refreshLayout();
    }

    private void resetVodChrome() {
        if (mChrome != null) mChrome.setLegacyToolbar(true);
    }

    public void applyWebHomeDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.applyDefault(WebHomeChromeStartup.resolve(VodConfig.get().getConfig(), site));
    }

    public void setWebHomeChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (isStartupChrome(payload)) WebHomeChromeStartup.remember(VodConfig.get().getConfig(), VodConfig.get().getHome(), payload);
        if (mChrome != null) mChrome.setChrome(payload);
    }

    private boolean isStartupChrome(JsonObject payload) {
        try {
            return payload != null && payload.has("startup") && payload.get("startup").getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    public void restoreWebHomeChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.restore();
    }

    public void setWebHomeLegacyToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.setLegacyToolbar(visible);
    }

    public void refreshWebHomeChromeState() {
        onWebHomeChromeChanged(getWebHomeChromeMode());
    }

    private JsonObject normalWebHomeChrome() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", "normal");
        return object;
    }

    public void openVod() {
        resetVodChrome();
        setNavigationVisible(true);
        mBinding.navigation.setSelectedItemId(R.id.vod);
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.openVodHome();
    }

    public String getWebHomeChromeMode() {
        return mChrome == null ? "normal" : mChrome.getMode();
    }

    public WebHomeViewport getWebHomeViewport() {
        return mChrome == null ? WebHomeViewport.EMPTY : mChrome.getViewport();
    }

    @Override
    public boolean isWebHomeChromeActive() {
        return mManager != null && mManager.isVisible(0);
    }

    @Override
    public void onWebHomeChromeChanged(String mode) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeChrome(mode);
        refreshAiFloatNow();
    }

    @Override
    public void onWebHomeViewportChanged(WebHomeViewport viewport) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeViewport(viewport);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mChrome != null) mChrome.onConfigurationChanged();
        App.post(this::checkWindowShape, 100);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mChrome != null) mChrome.onWindowFocusChanged(hasFocus);
    }

    private void checkWindowShape() {
        checkWindowShape(MobileWindow.getWidth(this), MobileWindow.getHeight(this));
    }

    private void checkWindowShape(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean wide = width > height;
        if (wideWindow != wide) {
            wideWindow = wide;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (isModuleFragmentShown()) {
            closeModuleFragment();
            return;
        }
        if (mChrome != null && mChrome.consumeBack()) {
            return;
        } else if (mManager.isVisible(2) || mManager.isVisible(3)) {
            change(1);
        } else if (mManager.isVisible(1)) {
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        // 最后一个首页实例销毁时给 Node 桥一次闲置回收机会（上游 releaseIfUnused
        // 钩）：其内部还要复查首页/播放服务都不在才真正清，不与下面 isFinishing
        // 的全清冲突——recreate 计数不归零、不会走到这里。
        if (--instanceCount == 0) NodeRuntime.get().releaseIfUnused();
        if (mChrome != null) mChrome.destroy();
        // 主题切换 recreate 也走 onDestroy：此时不是真 finish，配置与服务留给新实例接手，
        // 不清 Vod/Live 配置、不停本地服务，否则重建后首页要重拉配置、后台播放也被掐断。
        // 真 finish 时仍按原顺序全清；其它行（备份、OkHttp 清理）与原行为一致。
        if (isFinishing()) LiveConfig.get().clear();
        if (isFinishing()) VodConfig.get().clear();
        AppDatabase.backup();
        OkHttp.get().clear();
        if (isFinishing()) Source.get().exit();
        if (isFinishing()) Server.get().stop();
        super.onDestroy();
    }
}
