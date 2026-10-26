package com.fongmi.android.tv.ui.fragment;
import com.fongmi.android.tv.utils.ToastUtil;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.cloud.CloudFile;
import com.fongmi.android.tv.cloud.drive115.Drive115;
import com.fongmi.android.tv.cloud.drive115.Pan115Cover;
import com.fongmi.android.tv.databinding.FragmentCollectBinding;
import com.fongmi.android.tv.model.SearchProgress;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.activity.CloudDriveActivity;
import com.fongmi.android.tv.ui.activity.FolderActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.CollectAdapter;
import com.fongmi.android.tv.ui.adapter.SearchAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.CoverRatioCache;
import com.fongmi.android.tv.utils.ShortDramaHelper;

import java.util.ArrayList;
import java.util.List;

public class CollectFragment extends BaseFragment implements MenuProvider, CollectAdapter.OnClickListener, SearchAdapter.OnClickListener, CustomScroller.Callback {

    private static final int GRID_ITEM_MARGIN_DP = 4;
    private static final int GRID_TOP_PADDING_DP = 8;
    /** 首页搜索里 115 网盘结果的虚拟源 key（分组名「115网盘」，点击走网盘自己的路，不进站点详情）。 */
    private static final String PAN115_SITE_KEY = "pan115";

    private FragmentCollectBinding mBinding;
    private CollectAdapter mCollectAdapter;
    private SearchAdapter mSearchAdapter;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private final List<Collect> mCollects = new ArrayList<>();
    private final List<Vod> mAllResults = new ArrayList<>();
    /** 115 搜索结果的原始文件（按 fid 索引，点击时按类型分流用）。 */
    private final java.util.Map<String, CloudFile> mPan115Files = new java.util.concurrent.ConcurrentHashMap<>();
    private int collectWidth;

    public static CollectFragment newInstance(String keyword) {
        return newInstance(keyword, null);
    }

    public static CollectFragment newInstance(String keyword, String siteKey) {
        return newInstance(keyword, siteKey, null, null);
    }

    public static CollectFragment newInstance(String keyword, String siteKey, String pic, String wallPic) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        args.putString("siteKey", siteKey);
        args.putString("pic", pic);
        args.putString("wallPic", wallPic);
        CollectFragment fragment = new CollectFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return getArguments().getString("keyword");
    }

    private String getSiteKey() {
        return getArguments().getString("siteKey");
    }

    private String getPic() {
        return getArguments().getString("pic");
    }

    private String getWallPic() {
        return getArguments().getString("wallPic");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initMenu() {
        if (isHidden()) return;
        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        activity.setSupportActionBar(mBinding.toolbar);
        activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        activity.addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        activity.setTitle(getKeyword());
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        setSites();
        setWidth();
        setRecyclerView();
        setViewModel();
        search();
    }

    @Override
    protected void initEvent() {
        mBinding.toolbar.setOnClickListener(v -> {
            Bundle result = new Bundle();
            result.putBoolean("edit", true);
            getParentFragmentManager().setFragmentResult("result", result);
            getParentFragmentManager().popBackStack();
        });
    }

    private void setRecyclerView() {
        mBinding.collect.setItemAnimator(null);
        mBinding.collect.setHasFixedSize(true);
        mBinding.collect.setAdapter(mCollectAdapter = new CollectAdapter(this));
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.addOnScrollListener(mScroller);
        mBinding.recycler.setAdapter(mSearchAdapter = new SearchAdapter(this));
        setResultLayout(false);
        mBinding.recycler.post(() -> setResultLayout(false));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class).init();
        mViewModel.getSearch().observe(getViewLifecycleOwner(), this::setCollect);
        mViewModel.getSearchProgress().observe(getViewLifecycleOwner(), this::setSearchProgress);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setSearch);
    }

    private void setSites() {
        String siteKey = getSiteKey();
        mSites = new ArrayList<>();
        java.util.Set<String> selected = com.fongmi.android.tv.ui.dialog.SearchSourceDialog.hasSelection()
                ? com.fongmi.android.tv.ui.dialog.SearchSourceDialog.getSelectedKeys() : null;
        // Include home site (user-selected PY script via source popup, or VOD source)
        // setHome() only sets the home field without adding to sites list,
        // so we must add it here explicitly, otherwise search finds nothing.
        Site home = VodConfig.get().getHome();
        if (home != null && home.isSearchable()) {
            if (TextUtils.isEmpty(siteKey) || home.getKey().equals(siteKey)) {
                if (selected == null || selected.contains(home.getKey())) {
                    if (isJsSite(home)) {
                        App.post(() -> ToastUtil.show(requireContext(), "JS 暂未支持", android.widget.Toast.LENGTH_SHORT));
                    } else {
                        mSites.add(home);
                    }
                }
            }
        }
        for (Site site : VodConfig.get().getSites()) {
            if (!site.isSearchable()) continue;
            if (!TextUtils.isEmpty(siteKey) && !site.getKey().equals(siteKey)) continue;
            if (isJsSite(site)) continue;
            if (selected != null && !selected.contains(site.getKey())) continue;
            boolean exists = false;
            for (Site s : mSites) {
                if (s.getKey().equals(site.getKey())) { exists = true; break; }
            }
            if (!exists) mSites.add(site);
        }
        // Add PY scripts from plugins/py/
        if (selected != null) {
            // User confirmed selection in dialog: only add selected PY scripts
            for (String key : selected) {
                if (!key.startsWith("py_")) continue;
                boolean exists = false;
                for (Site s : mSites) {
                    if (s.getKey().equals(key)) { exists = true; break; }
                }
                if (exists) continue;
                Site pySite = createPySite(key.substring(3));
                if (pySite != null && pySite.isSearchable()) mSites.add(pySite);
            }
        } else {
            // No dialog selection: scan all PY scripts in plugins/py/ (align with VOD "search all" behavior)
            java.io.File pyDir = new java.io.File(requireContext().getFilesDir(), "plugins/py");
            java.io.File[] pyFiles = pyDir.listFiles((dir, name) -> name.endsWith(".py"));
            if (pyFiles != null) {
                for (java.io.File f : pyFiles) {
                    String fileName = f.getName();
                    String baseName = fileName.substring(0, fileName.length() - 3);
                    String key = "py_" + baseName;
                    boolean exists = false;
                    for (Site s : mSites) {
                        if (s.getKey().equals(key)) { exists = true; break; }
                    }
                    if (exists) continue;
                    Site pySite = createPySite(baseName);
                    if (pySite != null && pySite.isSearchable()) mSites.add(pySite);
                }
            }
        }
        // Emby 聚合搜索·层1：按已登录服逐个注册虚拟源（key "emby:<serverId>"，api "csp_Emby"，名 "Emby·服别名"）。
        // 失败静默降级：EmbySpider.searchContent 未登录抛中文异常，被搜索框架按单源失败隔离，
        // 不影响其它源；结果分组名即 "Emby·服别名"，点击走既有详情入口。
        addEmbySearchSites(siteKey, selected);
        SiteHealthStore.sortSites(mSites);
    }

    /**
     * Emby 聚合搜索·层1：按已登录服逐个注册虚拟源（key "emby:&lt;serverId&gt;"，api "csp_Emby"，名 "Emby·服别名"），
     * 走主搜索页既有多源并发框架，一服一组同屏。EmbySpider.searchContent 按 siteKey 选服。
     * 失败静默降级：未登录的服不注册；蜘蛛内异常按单源失败隔离，不影响其它源。
     */
    private void addEmbySearchSites(String siteKey, java.util.Set<String> selected) {
        try {
            // 限定单源搜索时：siteKey 是某个 emby 服 key 或老口径 csp_Emby 才挂 Emby 源
            boolean wantAll = TextUtils.isEmpty(siteKey);
            boolean wantEmby = wantAll || com.fongmi.android.tv.emby.EmbyNavigator.SITE_KEY.equals(siteKey)
                    || siteKey.startsWith("emby:");
            if (!wantEmby) return;
            for (Site emby : com.fongmi.android.tv.emby.EmbyNavigator.getSearchSites()) {
                if (emby == null || !emby.isSearchable()) continue;
                // 搜索源选择对 Emby 同样生效：有选择时只挂被选中的（弹窗从不产出 emby key，
                // 即有选择时 Emby 不混入，与 VOD/PY 的过滤口径一致，深挖 F-03a）
                if (selected != null && !selected.contains(emby.getKey())) continue;
                if (!wantAll && !siteKey.equals(emby.getKey())
                        && !com.fongmi.android.tv.emby.EmbyNavigator.SITE_KEY.equals(siteKey)) continue;
                boolean exists = false;
                for (Site s : mSites) if (s.getKey().equals(emby.getKey())) { exists = true; break; }
                if (!exists) mSites.add(emby);
            }
        } catch (Throwable ignored) {
        }
    }

    private Site createPySite(String baseName) {
        try {
            java.io.File file = new java.io.File(requireContext().getFilesDir(), "plugins/py/" + baseName + ".py");
            if (!file.exists()) return null;
            Site site = new Site();
            site.setKey("py_" + baseName);
            site.setName(baseName);
            site.setApi(file.getAbsolutePath());
            site.setType(3);
            String extKey = "py_" + baseName;
            String savedExt = com.fongmi.android.tv.utils.PyExtConfig.load(requireContext(), extKey);
            site.setExt(savedExt == null || savedExt.isEmpty() ? "{}" : savedExt);
            site.setJar("");
            // Register in VodConfig so detail/playback can resolve this dynamic source via getSite()
            // (search result click passes only the key; without registration detail would get an empty Site and finish()).
            VodConfig.get().registerDynamicSite(site);
            return site;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isJsSite(Site site) {
        if (site == null) return false;
        String key = site.getKey();
        String api = site.getApi();
        return (key != null && key.startsWith("js_")) || (api != null && (api.endsWith(".js") || api.endsWith(".wv")));
    }

    private void setWidth() {
        int width = 0;
        int space = ResUtil.dp2px(48);
        int maxWidth = ResUtil.getScreenWidth() / 2 - ResUtil.dp2px(40);
        for (Site site : mSites) width = Math.max(width, ResUtil.getTextWidth(site.getName(), 14));
        int contentWidth = width + space;
        int minWidth = ResUtil.dp2px(120);
        int finalWidth = Math.max(minWidth, Math.min(contentWidth, maxWidth));
        collectWidth = finalWidth;
        ViewGroup.LayoutParams params = mBinding.collect.getLayoutParams();
        params.width = finalWidth;
        mBinding.collect.setLayoutParams(params);
    }

    private void search() {
        if (mSites.isEmpty()) {
            // 无源可搜必须可见，不许静默空白（深挖 F-03b：只勾 JS 等情形会走到这）
            ToastUtil.show(requireContext(), "没有可搜索的源", android.widget.Toast.LENGTH_SHORT);
            return;
        }
        mCollects.clear();
        mAllResults.clear();
        mCollects.add(Collect.all());
        mCollectAdapter.setItems(new ArrayList<>(mCollects), () -> mViewModel.searchContent(mSites, getKeyword(), false));
        searchPan115();
    }

    /**
     * 115 网盘并入首页搜索（他定死：能搜 115 的只有网盘页内与首页这两处）。
     * 只在全量搜索时挂这一路：单源搜索、源选择会话不混入（与 Emby 口径一致）；
     * 未登录 115 或搜索失败一律静默，不弹登录、不打扰站点搜索。
     */
    private void searchPan115() {
        if (!TextUtils.isEmpty(getSiteKey())) return;
        if (com.fongmi.android.tv.ui.dialog.SearchSourceDialog.hasSelection()) return;
        String keyword = getKeyword();
        if (TextUtils.isEmpty(keyword) || keyword.trim().isEmpty()) return;
        if (!com.fongmi.android.tv.utils.PanAuth.is115LoggedIn()) return;
        new Thread(() -> {
            try {
                Drive115 drive = new Drive115();
                List<CloudFile> found = drive.searchFiles(keyword, 0, 30);
                if (found == null || found.isEmpty()) return;
                Site site = Site.get(PAN115_SITE_KEY, "115网盘");
                List<Vod> items = new ArrayList<>();
                for (CloudFile f : found) {
                    if (TextUtils.isEmpty(f.getId())) continue;
                    mPan115Files.put(f.getId(), f);
                    Vod vod = new Vod();
                    vod.setId(f.getId());
                    vod.setName(f.getName());
                    vod.setSite(site);
                    // 封面口径同他的铁规：服务端给了缩略图（thumbUrl）就填 pic；
                    // 没给的视频走截帧兜底，实在没有才留空走素图。
                    if (!TextUtils.isEmpty(f.getThumbUrl())) vod.setPic(f.getThumbUrl());
                    else fillFrameCover(drive, f, vod);
                    items.add(vod);
                }
                if (items.isEmpty()) return;
                App.post(() -> addPan115Collect(site, items));
            } catch (Exception ignored) {
                // 网盘这一路失败静默：站点搜索结果照常
            }
        }).start();
    }

    /**
     * 115 搜索结果封面兜底（与网盘页 bindRowCover 同口径的第二路）：服务端没给缩略图的
     * 视频走 Pan115Cover 截帧——已有缓存直接把本地路径填进 pic（ImgUtil 对真实存在的
     * 本地文件原样放行给 Glide）；没有就请求截帧，截好后回主线程补 pic 并刷列表
     * （Vod 是同一对象引用，两个列表共用，刷一次即可）。文件夹/非视频/无 pickcode 不走。
     */
    private void fillFrameCover(Drive115 drive, CloudFile f, Vod vod) {
        if (f.isDir() || !f.isVideo() || TextUtils.isEmpty(f.getPickCode())) return;
        java.io.File cover = Pan115Cover.coverFile(App.get(), f.getPickCode());
        if (cover != null) {
            vod.setPic(cover.getAbsolutePath());
            return;
        }
        Pan115Cover.requestCover(App.get(), drive, f.getPickCode(), (pc, ok) -> {
            if (!ok) return;
            java.io.File done = Pan115Cover.coverFile(App.get(), pc);
            if (done == null) return;
            App.post(() -> {
                if (!isAdded() || mBinding == null) return;
                vod.setPic(done.getAbsolutePath());
                if (mSearchAdapter != null) mSearchAdapter.notifyDataSetChanged();
            });
        });
    }

    /** 115 结果到达后并入：与 setCollect 同口径（进「全部」＋单开一组，分组名即站点名「115网盘」）。 */
    private void addPan115Collect(Site site, List<Vod> items) {
        if (!isAdded() || mBinding == null) return;
        if (mCollects.isEmpty()) mCollects.add(Collect.all());
        mAllResults.addAll(items);
        mCollects.get(0).getList().addAll(items);
        mCollects.add(new Collect(site, items));
        mCollectAdapter.setItems(new ArrayList<>(mCollects));
        if (mCollectAdapter.getPosition() == 0) mSearchAdapter.setItems(new ArrayList<>(mAllResults));
    }

    /** 115 结果点击：不走站点详情——文件夹直达网盘页对应目录、视频走网盘直达起播、其它文件进它所在目录。 */
    private void onPan115ItemClick(Vod item) {
        CloudFile f = mPan115Files.get(item.getId());
        if (f == null) return;
        if (f.isDir()) {
            CloudDriveActivity.startAt(requireActivity(), Drive115.ID, f.getId(), f.getName(), f.getParentCid());
        } else if (f.isVideo()) {
            CloudDriveActivity.play115File(requireActivity(), f);
        } else {
            String parent = f.getParentCid();
            if (TextUtils.isEmpty(parent) || "0".equals(parent)) CloudDriveActivity.start(requireActivity(), Drive115.ID);
            else CloudDriveActivity.startAt(requireActivity(), Drive115.ID, parent, "所在目录");
        }
    }

    private int getCount() {
        return Setting.getSearchColumn();
    }

    private boolean isGrid() {
        return getCount() == 2;
    }

    private int getSpanCount() {
        if (!isGrid()) return 1;
        if (!MobileWindow.isWide(requireActivity())) return 2;
        int column = Product.getColumn(requireActivity());
        int targetWidth = Product.getSpec(requireActivity(), column)[0];
        int available = getResultWidth() - getResultPadding();
        int span = targetWidth > 0 ? available / targetWidth : 2;
        return Math.max(2, Math.min(column, span));
    }

    private int getResultWidth() {
        int width = mBinding.recycler.getWidth();
        return width > 0 ? width : ResUtil.getScreenWidth(requireActivity()) - collectWidth;
    }

    private int getResultPadding() {
        return mBinding.recycler.getPaddingStart() + mBinding.recycler.getPaddingEnd();
    }

    private int[] getGridSize() {
        int span = getSpanCount();
        int margin = ResUtil.dp2px(GRID_ITEM_MARGIN_DP);
        int space = getResultPadding() + margin * 2 * span;
        int width = (getResultWidth() - space) / span;
        width = Math.max(ResUtil.dp2px(96), width);
        return new int[]{width, (int) (width / 0.75f), margin};
    }

    private void setResultLayout(boolean scrollTop) {
        setWidth();
        int span = getSpanCount();
        ((GridLayoutManager) (mBinding.recycler.getLayoutManager())).setSpanCount(span);
        setResultPadding();
        mSearchAdapter.setGrid(isGrid(), getGridSize());
        if (scrollTop) mBinding.recycler.scrollToPosition(0);
    }

    private void setResultPadding() {
        int top = isGrid() ? ResUtil.dp2px(GRID_TOP_PADDING_DP) : 0;
        mBinding.recycler.setPadding(mBinding.recycler.getPaddingStart(), top, mBinding.recycler.getPaddingEnd(), mBinding.recycler.getPaddingBottom());
    }

    private void onColumnToggle() {
        Setting.putSearchColumn(getCount() == 1 ? 2 : 1);
        setResultLayout(true);
        requireActivity().invalidateOptionsMenu();
    }

    private void setCollect(Result result) {
        if (result == null || result.getList().isEmpty()) return;
        List<Vod> items = new ArrayList<>(result.getList());
        mAllResults.addAll(items);
        mCollects.get(0).getList().addAll(items);
        mCollects.add(Collect.create(items));
        mCollectAdapter.setItems(new ArrayList<>(mCollects));
        if (mCollectAdapter.getPosition() == 0) mSearchAdapter.setItems(new ArrayList<>(mAllResults));
    }

    private void setSearchProgress(SearchProgress progress) {
        if (progress != null) mCollectAdapter.setProgress(progress.current(), progress.total());
    }

    private void setSearch(Result result) {
        if (result == null) return;
        mScroller.endLoading(result);
        // getActivated() 在列表为空时返 null（修根后口径）：无激活源就按不同源处理，不解引用
        Collect activated = mCollectAdapter.getActivated();
        boolean same = activated != null && activated.getSite() != null && !result.getList().isEmpty() && activated.getSite().equals(result.getVod().getSite());
        if (same) activated.getList().addAll(result.getList());
        if (same) mSearchAdapter.setItems(new ArrayList<>(activated.getList()));
        // 音乐搜索续载同步：同源又续了一页时，这页的歌若已从本搜索起播成队列，追进队尾。
        if (same) com.fongmi.android.tv.ui.MusicVodRouter.appendListPage(
                com.fongmi.android.tv.ui.MusicVodRouter.searchSession(activated.getSite().getKey(), getKeyword()),
                activated.getSite().getKey(), result.getList());
    }

    @Override
    public void onItemClick(int position, Collect item) {
        mSearchAdapter.setItems(item.getList(), () -> mBinding.recycler.scrollToPosition(0));
        mCollectAdapter.setSelected(position);
        mScroller.setPage(item.getPage());
    }

    @Override
    public void onItemClick(Vod item, android.view.View coverView) {
        if (PAN115_SITE_KEY.equals(item.getSiteKey())) {
            onPan115ItemClick(item);
            return;
        }
        if (item.isFolder()) FolderActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else {
            String pic = item.getPic().isEmpty() ? getPic() : item.getPic();
            if (com.fongmi.android.tv.ui.MusicVodRouter.routeVodList(requireActivity(), item.getSiteKey(), mSearchAdapter.getItems(), item,
                    com.fongmi.android.tv.ui.MusicVodRouter.searchSession(item.getSiteKey(), getKeyword()))) {}
            else if (ShortDramaHelper.isPluginShortDramaBySite(item.getSiteKey()) || CoverRatioCache.isShortDramaCover(pic)) VideoActivity.startShortDrama(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), pic);
            else if (ShortDramaHelper.isLiveShortDramaCover(coverView, pic)) VideoActivity.startShortDrama(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), pic);
            else VideoActivity.collect(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), pic, getWallPic());
        }
    }

    @Override
    public boolean onLoadMore(String page) {
        Collect activated = mCollectAdapter.getActivated();
        // 无激活源（列表为空）时没有可翻页的源，直接收手
        if (activated == null || activated.getSite() == null) return false;
        if ("all".equals(activated.getSite().getKey())) return false;
        // 115 网盘组只取一页（30 条），不走站点翻页
        if (PAN115_SITE_KEY.equals(activated.getSite().getKey())) return false;
        mViewModel.searchContent(activated.getSite(), getKeyword(), false, page);
        activated.setPage(Integer.parseInt(page));
        return true;
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.menu_collect, menu);
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        MenuItem item = menu.findItem(R.id.action_column);
        if (item == null) return;
        Drawable icon = ContextCompat.getDrawable(requireContext(), getCount() == 1 ? R.drawable.ic_site_double_column : R.drawable.ic_site_single_column);
        if (icon == null) return;
        icon = icon.mutate();
        icon.setTint(Color.WHITE);
        item.setIcon(icon);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) requireActivity().getOnBackPressedDispatcher().onBackPressed();
        if (menuItem.getItemId() == R.id.action_column) onColumnToggle();
        return true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) requireActivity().removeMenuProvider(this);
        else initMenu();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mViewModel.stopSearch();
        SiteHealthStore.flush();
        requireActivity().removeMenuProvider(this);
        mBinding = null;
    }
}
