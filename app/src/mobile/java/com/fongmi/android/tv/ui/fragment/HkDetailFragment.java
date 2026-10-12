package com.fongmi.android.tv.ui.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.hk.HkDetail;
import com.fongmi.android.tv.api.hk.HkDetailItem;
import com.fongmi.android.tv.api.hk.HkErrorUtil;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkPlay;
import com.fongmi.android.tv.api.hk.HkRouter;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.databinding.FragmentHkDetailBinding;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 小程序详情页（V4）。
 * 从 HkPageActivity 拆出：详情页走通用列表渲染器（60 列栅格 + ContentAdapter），
 * 详情条目按 col_type 直接渲染（detail_header/long_text/flex_button 等），不做线路/选集分类。
 * <p>
 * 与 Activity 的协作约定（Activity 实现 {@link HkPageHost}）：
 * <ul>
 * <li>Activity 在 {@code hostOpenDetail(item)} 中把本 Fragment 显示到前台；是否触发重新加载
 * 由 Fragment 判断——Activity 可用 {@link #getDetailItem()} 对比，item 相同且无加载进行时
 * 不要重复调用 {@link #openDetail}（lazy 预检补推场景 Fragment 已持有结果，会自行渲染）。</li>
 * <li>Fragment 内部需要导航/重载/点击管线时回调 host：{@code hostNavigateBack}、
 * {@code hostLoadContent}、{@code hostOnContentItemClick}、{@code hostOpenDetail}。</li>
 * </ul>
 * 浅色主题、深色文字；布局见 fragment_hk_detail.xml（10dp 白卡片规范）。
 */
public class HkDetailFragment extends Fragment {

    private FragmentHkDetailBinding binding;

    /** 详情页字段（原 HkPageActivity 详情区）。 */
    private HkItem detailItem;
    private boolean detailFromSearch;
    private boolean detailLoading;
    /** 点击管线往返保护：hiker://page/ 与 @rule= 交给 Activity 消费，管线回调用 openDetail 时不再二次分流。 */
    private boolean inHostClick;
    private Toast loadingHint;
    private HkDetail currentDetail;
    /** 详情实际抓取页 URL（图片 Referer 用；切规则时 Activity 侧 hkPageUrl 失效，本字段仅记录详情页）。 */
    private volatile String hkPageUrl;
    // 8.83 原版：详情页走通用列表渲染器，不再用专用线路/选集模型
    private final List<HkItem> detailItems = new ArrayList<>();
    private ContentAdapter detailAdapter;
    private GridLayoutManager detailGrid;

    /** 与 Activity 协作的统一入口（Activity 实现 HkPageHost）。 */
    private HkPageHost host() {
        return (HkPageHost) requireActivity();
    }

    private HkRule hostRule() {
        HkPageHost h = getActivity() instanceof HkPageHost ? (HkPageHost) getActivity() : null;
        return h == null ? null : h.getCurrentRule();
    }

    private HkRouter router() throws Exception {
        Object r = host().getHkRouter();
        return (HkRouter) r;
    }

    /** 当前规则的图片 Referer URL（优先详情实际抓取页，回退规则静态 URL）。 */
    private String hkRuleUrl() {
        if (!TextUtils.isEmpty(hkPageUrl)) return hkPageUrl;
        HkRule rule = hostRule();
        return rule == null ? null : rule.getUrl();
    }

    /** 当前加载/已展示的条目（供 Activity 的 hostOpenDetail 判断是否需要重新加载）。 */
    public HkItem getDetailItem() {
        return detailItem;
    }

    /** 是否正在加载详情。 */
    public boolean isDetailLoading() {
        return detailLoading;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHkDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initDetailView();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        hideLoadingHint();
        binding = null;
    }

    private void initDetailView() {
        binding.btnBackDetail.setOnClickListener(v -> host().hostNavigateBack());
        binding.tvDetailTitle.setSelected(true);
        // 8.83 原版：详情页走通用列表渲染器（60 列栅格 + ContentAdapter），不用专用布局
        detailGrid = new GridLayoutManager(requireContext(), 60);
        detailGrid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return detailAdapter == null ? 60 : detailAdapter.spanFor(position);
            }
        });
        binding.rvDetailContent.setLayoutManager(detailGrid);
        binding.rvDetailContent.addItemDecoration(new GridSpace());
        // 网页级顺滑：尺寸固定（不随内容变）+ 增大视图缓存 + 关闭 change 动画防闪烁
        binding.rvDetailContent.setHasFixedSize(true);
        binding.rvDetailContent.setItemViewCacheSize(24);
        RecyclerView.ItemAnimator anim = binding.rvDetailContent.getItemAnimator();
        if (anim instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) anim).setSupportsChangeAnimations(false);
        }
        detailAdapter = new ContentAdapter(detailItems);
        binding.rvDetailContent.setAdapter(detailAdapter);
        binding.tvDetailEmpty.setOnClickListener(v -> {
            if (detailItem != null) openDetail(detailItem, detailFromSearch);
        });
    }

    public void openDetail(HkItem item, boolean fromSearch) {
        openDetail(item, fromSearch, false);
    }

    /**
     * V4 详情。
     *
     * @param lazyPrecheck true=条目 URL 自带 {@code @lazyRule=}（如粉嫩小BB点封面即播）：
     *                     先后台求值，不预推详情页；若判定为直接播放则根本不展示本页，
     *                     避免详情 loading 闪一下。非直接播放时再补推展示（复用已算出的结果）。
     */
    public void openDetail(HkItem item, boolean fromSearch, boolean lazyPrecheck) {
        if (!isAdded() || binding == null) return;
        if (item != null && !TextUtils.isEmpty(item.getUrl())) {
            String u = item.getUrl().trim();
            // 官方 clickItem：@rule= 条目进子列表页（dealRule），hiker://page/ 走子页面，均不进详情。
            // 顺序与官方一致：只看 @rule= 前段是否含 @lazyRule=，含则走 lazy 预检不进子列表。
            int ar = u.indexOf("@rule=");
            boolean hasLazyBeforeRule = ar >= 0 && u.substring(0, ar).contains("@lazyRule=");
            if (u.startsWith("hiker://page/") || (ar >= 0 && !hasLazyBeforeRule)) {
                if (!inHostClick) {
                    // 交给 Activity 统一点击管线（openPage/openSubList 在其内部消费）；
                    // 管线未消费回调用 openDetail 时 inHostClick 为 true，不再二次分流，直接走详情。
                    inHostClick = true;
                    try {
                        onContentItemClick(item);
                    } finally {
                        inHostClick = false;
                    }
                    return;
                }
            }
        }
        detailItem = item;
        detailFromSearch = fromSearch;
        currentDetail = null;
        detailItems.clear();
        if (detailAdapter != null) detailAdapter.notifyDataSetChanged();
        detailLoading = true;
        if (!lazyPrecheck) {
            binding.tvDetailTitle.setText(item.getTitle());
            binding.rvDetailContent.setVisibility(View.GONE);
            binding.tvDetailEmpty.setVisibility(View.GONE);
            binding.tvDetailLoading.setVisibility(View.VISIBLE);
        } else {
            // 直接播放预检（条目 URL 自带 @lazyRule=）：后台求值，下方小字"加载中"提示
            // （海阔原版 toast 风格，不挡封面）；求值完成后直接进播放器
            showLoadingHint();
        }
        new Thread(() -> {
            HkDetail detail;
            try {
                HkRouter r = router();
                detail = r.detail(item.getUrl(), item, fromSearch);
                // 记录详情页实际抓取 URL：详情封面/推荐图 Referer 用
                try {
                    String du = r.getEngine().getJsRuntime().getLastUrl();
                    if (!TextUtils.isEmpty(du) && (du.startsWith("http://") || du.startsWith("https://"))) {
                        hkPageUrl = du;
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable e) {
                detail = null;
            }
            final HkDetail result = detail;
            App.post(() -> {
                detailLoading = false;
                hideLoadingHint();
                if (!isAdded() || binding == null) return;
                if (lazyPrecheck) host().hostHideLoading();
                else binding.tvDetailLoading.setVisibility(View.GONE);
                // 纯文本结果（文本按钮的 js: 求值返回文本）：弹窗显示，不进详情/播放
                String textResult = result == null ? "" : result.getTextResult();
                if (!TextUtils.isEmpty(textResult)) {
                    if (!lazyPrecheck) host().hostNavigateBack(); // 弹出已推的详情空 loading
                    else host().hostHideLoading();
                    new AlertDialog.Builder(requireContext())
                            .setMessage(textResult)
                            .setPositiveButton("确定", (d, w) -> d.dismiss())
                            .show();
                    return;
                }
                // 分类切换（官方 refreshPage 语义：putMyVar 后重刷列表，不进详情）
                if (result != null && result.isTabSwitch()) {
                    if (!lazyPrecheck) host().hostNavigateBack(); // 弹出已推的详情空 loading
                    host().hostLoadContent(true);
                    return;
                }
                // lazyRule 解析失败（@lazyRule= 求值无结果）：不推空详情页，直接报错
                // （避免"0条线路 · 共0集"的空详情页；用户点的是视频，期望播放而非空详情）
                if (result != null && result.isLazyParseFailed()) {
                    Toast.makeText(requireContext(), "解析失败，换一条试试", Toast.LENGTH_SHORT).show();
                    return;
                }
                String direct = result == null ? "" : result.getDirectPlayUrl();
                if (!TextUtils.isEmpty(direct)) {
                    // 直接播放：若已推详情先弹出，保证播放器返回时回到列表
                    if (!lazyPrecheck) host().hostNavigateBack();
                    // 预热播放路由：后台提前做 QuickJS 初始化+preRule，
                    // 播放器调 playerContent 时直接命中缓存，不用再等几秒初始化
                    HkRule rule = hostRule();
                    SiteApi.warmHkRouter(rule == null ? "" : rule.getTitle());
                    startHkDirectPlay(item, direct);
                    return;
                }
                // dealWithUrl 分流（官方：@lazyRule= 解析后不进详情）
                String dealKind = result == null ? "" : result.getDealKind();
                if (!TextUtils.isEmpty(dealKind)) {
                    if (!lazyPrecheck) host().hostNavigateBack();
                    else host().hostHideLoading();
                    handleDealUrl(dealKind, result.getDealUrl(), item);
                    return;
                }
                if (lazyPrecheck) {
                    // 预检非直接播放：补推详情页再展示（复用已算出的 result，不二次求值）。
                    // Activity 的 hostOpenDetail 实现约定：仅把本 Fragment 显示到前台，
                    // 用 getDetailItem() 判断是否需要重新加载，不要重复调用 openDetail。
                    binding.tvDetailTitle.setText(item.getTitle());
                    binding.rvDetailContent.setVisibility(View.GONE);
                    binding.tvDetailEmpty.setVisibility(View.GONE);
                    binding.tvDetailLoading.setVisibility(View.GONE);
                    host().hostOpenDetail(item);
                }
                showDetailResult(result);
            });
        }).start();
    }

    /**
     * 点封面后的下方小字"加载中"提示（海阔原版 toast 风格，不挡封面）。
     * 进播放器/推详情/报错时自动消失。
     */
    private void showLoadingHint() {
        hideLoadingHint();
        loadingHint = Toast.makeText(requireContext(), "加载中…", Toast.LENGTH_LONG);
        loadingHint.show();
    }

    private void hideLoadingHint() {
        if (loadingHint != null) {
            loadingHint.cancel();
            loadingHint = null;
        }
    }

    /**
     * 直接播放：只把被点击的这 1 条目作为选集传给播放器。
     * 列表页其他视频不是当前视频的选集，不能伪装成多集（否则出现 1-20/21-40 等假选集）。
     */
    private void startHkDirectPlay(HkItem item, String directUrl) {
        JSONArray arr = new JSONArray();
        try {
            if (item != null && !TextUtils.isEmpty(item.getUrl())) {
                JSONObject o = new JSONObject();
                o.put("name", stripHtml(item.getTitle()));
                o.put("url", item.getUrl());
                o.put("pic", item.getPic() == null ? "" : item.getPic());
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        HkRule rule = hostRule();
        VideoActivity.startHkPlay(requireActivity(),
                rule == null ? "" : rule.getTitle(), "默认",
                directUrl, item.getTitle(), item.getTitle(), item.getPic(),
                arr.toString(), 0);
    }

    /**
     * dealWithUrl 分流处理（官方 ArticleListFragment.dealWithUrl）：
     * pics=漫画图片列表 / x5=webview规则 / web=网页 / image=图片查看 / magnet=分享。
     */
    private void handleDealUrl(String kind, String url, HkItem item) {
        if (!isAdded() || TextUtils.isEmpty(url)) return;
        try {
            switch (kind) {
                case "pics": {
                    // pics://url1&&url2... → 图片列表查看
                    String raw = url.replaceFirst("(?i)^pics://", "");
                    String[] parts = raw.split("&&");
                    java.util.ArrayList<String> urls = new java.util.ArrayList<>();
                    for (String p : parts) {
                        if (!TextUtils.isEmpty(p.trim())) urls.add(p.trim());
                    }
                    if (urls.isEmpty()) {
                        Toast.makeText(requireContext(), "无图片", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    android.widget.ImageView iv = new android.widget.ImageView(requireContext());
                    ImgUtil.load(item.getTitle(), urls.get(0), iv, false, 0, 0, hkRuleUrl());
                    new AlertDialog.Builder(requireContext())
                            .setTitle(stripHtml(item.getTitle()))
                            .setView(iv)
                            .setPositiveButton("关闭", (d, w) -> d.dismiss())
                            .show();
                    return;
                }
                case "x5": {
                    // x5://url → 内嵌 webview 规则页
                    String target = url.replaceFirst("(?i)^x5://", "").trim();
                    if (!target.isEmpty()) showWebViewDialog(target);
                    return;
                }
                case "web":
                    showWebViewDialog(url);
                    return;
                case "image": {
                    android.widget.ImageView iv = new android.widget.ImageView(requireContext());
                    ImgUtil.load(item.getTitle(), url, iv, false, 0, 0, hkRuleUrl());
                    new AlertDialog.Builder(requireContext())
                            .setView(iv)
                            .setPositiveButton("关闭", (d, w) -> d.dismiss())
                            .show();
                    return;
                }
                case "magnet": {
                    android.content.Intent it2 = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url));
                    try {
                        requireActivity().startActivity(android.content.Intent.createChooser(it2, "选择应用打开"));
                    } catch (Throwable e) {
                        Toast.makeText(requireContext(), "无应用可打开", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                default:
                    break;
            }
        } catch (Throwable e) {
            android.util.Log.d("HkPage", "handleDealUrl failed: " + e.getMessage());
        }
        // 未知种类：回退详情页展示错误态
        host().hostOpenDetail(item);
        showDetailResult(null);
    }

    private void showWebViewDialog(String url) {
        if (!isAdded()) return;
        try {
            android.webkit.WebView wv = new android.webkit.WebView(requireContext());
            wv.getSettings().setJavaScriptEnabled(true);
            wv.getSettings().setDomStorageEnabled(true);
            wv.setWebViewClient(new android.webkit.WebViewClient());
            wv.loadUrl(url);
            new AlertDialog.Builder(requireContext())
                    .setView(wv)
                    .setPositiveButton("关闭", (d, w) -> {
                        try {
                            wv.destroy();
                        } catch (Throwable ignored) {
                        }
                        d.dismiss();
                    })
                    .show();
        } catch (Throwable e) {
            Toast.makeText(requireContext(), "打开网页失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 8.83 dealLongMenuClick：内容条目长按菜单。
     * 菜单项对照 8.83：复制链接 / 访问链接 / 下载资源 / 自由复制。
     */
    private void showContentLongMenu(HkItem item) {
        if (item == null || !isAdded()) return;
        String url = item.getUrl() == null ? "" : item.getUrl().trim();
        String title = stripHtml(item.getTitle() == null ? "" : item.getTitle());
        String[] labels = {"复制链接", "访问链接", "下载资源", "自由复制"};
        new AlertDialog.Builder(requireContext())
                .setTitle(title.isEmpty() ? "操作" : (title.length() > 20 ? title.substring(0, 20) + "…" : title))
                .setItems(labels, (d, which) -> {
                    switch (which) {
                        case 0: { // 复制链接
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("hk", url));
                            Toast.makeText(requireContext(), "已复制链接", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case 1: { // 访问链接：在系统浏览器打开 http(s) 链接
                            if (url.startsWith("http")) {
                                try {
                                    requireActivity().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                                } catch (Throwable t) {
                                    Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                Toast.makeText(requireContext(), "当前页面不支持此操作", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        }
                        case 2: { // 下载资源：走 download:// 动作（经 Activity 统一点击管线）
                            if (!url.isEmpty()) {
                                HkItem nav = new HkItem();
                                nav.setTitle(title);
                                nav.setUrl("download://" + url);
                                onContentItemClick(nav);
                            }
                            break;
                        }
                        case 3: { // 自由复制：复制标题+链接
                            android.content.ClipboardManager cm2 = (android.content.ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm2 != null) cm2.setPrimaryClip(android.content.ClipData.newPlainText("hk", title + "\n" + url));
                            Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        default:
                            break;
                    }
                })
                .show();
    }

    /**
     * 详情结果展示：8.83 原版走通用列表渲染器（FilmListActivity + ArticleListAdapter），
     * 详情条目按 col_type 直接渲染，不做线路/选集分类。
     */
    private void showDetailResult(HkDetail result) {
        if (!isAdded() || binding == null) return;
        if (result == null || (result.getRawItems().isEmpty() && !result.hasBasicInfo())) {
            binding.rvDetailContent.setVisibility(View.GONE);
            binding.tvDetailEmpty.setVisibility(View.VISIBLE);
            // 空态：8.83 式规则错误弹窗（HkErrorUtil），对等 DebugUtil.showErrorMsg。
            try {
                String hkErr = router().getEngine().getError();
                HkRule rule = hostRule();
                String ruleTitle = rule == null ? "" : rule.getTitle();
                HkErrorUtil.showErrorMsg(requireActivity(), "规则执行过程中出现错误", ruleTitle, hkErr, null);
                binding.tvDetailEmpty.setText("加载失败，点我重试");
            } catch (Throwable ignored) {
                binding.tvDetailEmpty.setText("加载失败，点我重试");
            }
        } else {
            bindDetailItems(result);
        }
    }

    /**
     * 8.83 原版：详情原始条目直接丢进通用 ContentAdapter 按 col_type 渲染。
     * - 头部：首条不是 header 型时，用点击条目的标题/封面合成 detail_header（标题左+封面右）
     * - 简介：无文本条目但有 content 时，补 long_text
     * - 播放按钮：原始条目原样渲染，点击在详情页内直接播放（不套娃进详情）
     */
    private void bindDetailItems(HkDetail detail) {
        currentDetail = detail;
        // 详情已展示：用户很可能马上点选集，提前预热播放路由，省掉播放时的初始化等待
        HkRule rule = hostRule();
        SiteApi.warmHkRouter(rule == null ? "" : rule.getTitle());
        List<HkItem> items = buildDetailHkItems(detail);
        detailItems.clear();
        detailItems.addAll(items);
        detailAdapter.refreshGroups();
        detailAdapter.notifyDataSetChanged();
        binding.rvDetailContent.setVisibility(View.VISIBLE);
        binding.tvDetailEmpty.setVisibility(View.GONE);
        binding.tvDetailTitle.setText(detail.getTitle());
        binding.rvDetailContent.scrollToPosition(0);
    }

    /** 详情原始条目 → 通用列表条目（8.83 原版：不做线路/选集分类，原样渲染）。 */
    private List<HkItem> buildDetailHkItems(HkDetail detail) {
        List<HkItem> out = new ArrayList<>();
        List<HkDetailItem> raw = detail.getRawItems();
        // 1. 头部：首条不是 header 型时合成
        boolean hasHeader = !raw.isEmpty() && isHeaderColType(raw.get(0).getColType());
        if (!hasHeader && (!TextUtils.isEmpty(detail.getTitle()) || !TextUtils.isEmpty(detail.getPic()))) {
            HkItem header = new HkItem();
            header.setColType("detail_header");
            header.setTitle(detail.getTitle());
            header.setPic(detail.getPic());
            out.add(header);
        }
        // 2. 原始条目原样转换
        boolean hasText = false;
        for (HkDetailItem di : raw) {
            HkItem hi = new HkItem();
            hi.setTitle(di.getTitle());
            hi.setUrl(di.getUrl());
            hi.setPic(di.getPic());
            hi.setDesc(di.getDesc());
            hi.setColType(di.getColType());
            hi.setLine(di.getLine());
            out.add(hi);
            String ct = di.getColType() == null ? "" : di.getColType().toLowerCase();
            if (ct.contains("long_text") || ct.startsWith("text_") || ct.contains("rich_text")) hasText = true;
        }
        // 3. 简介回退：无文本条目但有 content 时补 long_text
        if (!hasText && !TextUtils.isEmpty(detail.getContent())) {
            HkItem syn = new HkItem();
            syn.setColType("long_text");
            syn.setDesc(detail.getContent());
            out.add(out.isEmpty() ? 0 : 1, syn);
        }
        return out;
    }

    /** 详情头型 col_type：已有头部条目时不再合成。 */
    private static boolean isHeaderColType(String colType) {
        if (colType == null) return false;
        String c = colType.trim().toLowerCase();
        return c.equals("detail_header") || c.contains("movie_1_vertical_pic")
                || c.equals("movie_1") || c.equals("movie_1_left_pic");
    }

    /**
     * 8.83 原版 dealWithUrl：详情页内点可播 URL 直接播放，不套娃进详情。
     * 先过 HkRouter.play() 解析（处理 @lazyRule=/@rule=js: 等），再进播放器；
     * 选集列表取当前详情所有可播条目，支持播放器内切集。
     */
    private void playDetailEpisode(HkItem it, String url) {
        new Thread(() -> {
            String playUrl = url;
            try {
                if (url.contains("@lazyRule=") || url.contains("@rule=") || url.startsWith("js:")) {
                    HkPlay play = router().play(url);
                    if (!play.getUrl().isEmpty()) playUrl = play.getUrl();
                }
            } catch (Throwable ignored) {
            }
            // 8.83 getChapters：按被点条目的 BaseExtra.cls 分组只收同组；跳过 header 型、
            // 空 URL、URL 含 @rule=/ignoreVideo/ignoreMusic 的条目；播放器内切集不出组
            String clickedCls = it.getExtra("cls");
            JSONArray arr = new JSONArray();
            int selIdx = 0;
            int idx = 0;
            for (HkItem di : detailItems) {
                String du = di.getUrl() == null ? "" : di.getUrl().trim();
                String dl = du.toLowerCase();
                boolean isClicked = (di == it);
                if (!isClicked) {
                    if (du.isEmpty()) continue;
                    if (dl.startsWith("javascript:") || dl.equals("hiker://empty")) continue;
                    String dct = di.getColType() == null ? "" : di.getColType().toLowerCase();
                    if (dct.contains("header")) continue;
                    if (du.contains("@rule=")) continue;
                    if (dl.contains("ignorevideo") || dl.contains("ignoremusic")) continue;
                    if (!clickedCls.isEmpty() && !clickedCls.equals(di.getExtra("cls"))) continue;
                }
                // 只收可播条目：http 链接或含 @lazyRule=/js: 的解析型地址（@rule= 已在上排除）
                boolean playable = isClicked || dl.startsWith("http")
                        || du.contains("@lazyRule=") || du.startsWith("js:");
                if (!playable) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", stripHtml(di.getTitle()));
                    o.put("url", du);
                    o.put("pic", di.getPic() == null ? "" : di.getPic());
                    arr.put(o);
                    if (di == it) selIdx = idx;
                    idx++;
                } catch (Throwable ignored) {
                }
            }
            final String pu = playUrl;
            final String eps = arr.toString();
            final int sel = selIdx;
            App.post(() -> {
                if (!isAdded()) return;
                if (TextUtils.isEmpty(pu)) {
                    Toast.makeText(requireContext(), "解析失败，换一条试试", Toast.LENGTH_SHORT).show();
                    return;
                }
                String title = detailItem != null && !TextUtils.isEmpty(detailItem.getTitle())
                        ? detailItem.getTitle() : "";
                String pic = detailItem != null ? detailItem.getPic() : "";
                String lineName = it.getLine() == null || it.getLine().isEmpty() ? "默认" : it.getLine();
                HkRule rule = hostRule();
                VideoActivity.startHkPlay(requireActivity(),
                        rule == null ? "" : rule.getTitle(), lineName,
                        pu, it.getTitle(), title, pic, eps, sel);
            });
        }).start();
    }

    /** 详情列表条目点击：走 Activity 统一点击管线（其内部按 V_DETAIL 做播放/分流/套娃详情）。 */
    private void onContentItemClick(HkItem it) {
        HkPageHost h = getActivity() instanceof HkPageHost ? (HkPageHost) getActivity() : null;
        if (h != null) h.hostOnContentItemClick(it);
    }

    /** 内容重载（header tab 点击后）：走 Activity。 */
    private void hostLoadContent(boolean reset) {
        HkPageHost h = getActivity() instanceof HkPageHost ? (HkPageHost) getActivity() : null;
        if (h != null) h.hostLoadContent(reset);
    }

    private int dp(int v) {
        return (int) (v * requireContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    /** col_type 小写形式（js 结果里填充，未设置则空串）。 */
    private static String colTypeOf(HkItem it) {
        return it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
    }

    /** 去 HTML 标签（col_type 标题里常带 <font> 等），逻辑同 buildDynamicTabs。 */
    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").replace("‘", "").replace("’", "")
                .replace("“", "").replace("”", "").trim()
                // 规则里常把标题包在引号里（"""最新上传"""），去掉首尾引号
                .replaceAll("^\"+|\"+$", "").replaceAll("^'+|'+$", "").trim();
    }

    /**
     * 标题标记变色：规则用 ““xxx”” / """xxx""" / ‘‘xxx’’ 包裹的片段标红/标橙，
     * split 内联处理，标题中间也能变色；返回带 <font> 标签的 HTML，无标记返回 null。
     */
    private String applyTitleMarkHtml(String raw) {
        if (raw == null) return null;
        String r = applyMark(raw, "““", "””", "#FF0000");
        if (r != null) return r;
        // 兼容 ASCII 三引号 """xxx"""（部分规则用直引号，如口令弹窗标题）
        r = applyMark(raw, "\"\"\"", "\"\"\"", "#FF0000");
        if (r != null) return r;
        return applyMark(raw, "‘‘", "’’", "#f0983c");
    }

    /** 对文本做开/合标记的 split 内联变色，找到完整标记返回 HTML，否则返回 null。 */
    private String applyMark(String text, String open, String close, String color) {
        if (text == null || !text.contains(open)) return null;
        String[] parts = text.split(java.util.regex.Pattern.quote(open), -1);
        if (parts.length < 2) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(parts[0]);
        boolean marked = false;
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i];
            int idx = seg.indexOf(close);
            if (idx >= 0) {
                marked = true;
                sb.append("<font color=\"").append(color).append("\">")
                        .append(seg, 0, idx)
                        .append("</font>")
                        .append(seg.substring(idx + close.length()));
            } else {
                // 无闭合标记：把 open 还原，避免吞字
                sb.append(open).append(seg);
            }
        }
        return marked ? sb.toString() : null;
    }

    private CharSequence titleSpan(String raw) {
        if (raw == null) raw = "";
        String markedHtml = applyTitleMarkHtml(raw);
        if (markedHtml != null) {
            try {
                return Html.fromHtml(markedHtml, Html.FROM_HTML_MODE_LEGACY);
            } catch (Throwable t) {
                // fallback 到 strip
            }
        }
        return stripHtml(raw);
    }

    /**
     * 内容网格间距：标准均分公式，保证 N 列总宽严格等于 RecyclerView 宽、不溢出。
     * 每列 i（0-based）：left = i*S/N，right = S-(i+1)*S/N；列间距 S=12dp。
     * 外边距由父容器 16dp 提供，decoration 不再加外边距。
     * 兼容 60-span 详情网格。
     */
    private class GridSpace extends RecyclerView.ItemDecoration {
        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int pos = parent.getChildAdapterPosition(view);
            if (pos < 0) return;
            int total = 60, span = 60, spanIndex = 0;
            if (parent.getLayoutManager() instanceof GridLayoutManager) {
                total = ((GridLayoutManager) parent.getLayoutManager()).getSpanCount();
            }
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) view.getLayoutParams();
            if (lp instanceof GridLayoutManager.LayoutParams) {
                GridLayoutManager.LayoutParams glp = (GridLayoutManager.LayoutParams) lp;
                span = glp.getSpanSize();
                if (glp.getSpanIndex() >= 0) spanIndex = glp.getSpanIndex();
            }
            if (span >= total) {
                outRect.left = 0;
                outRect.right = 0;
                if (pos > 0) outRect.top = dp(12);
                return;
            }
            int perRow = Math.max(1, total / Math.max(1, span));
            int col = span > 0 ? spanIndex / span : 0;
            if (col < 0) col = 0;
            if (col >= perRow) col = perRow - 1;
            int s = dp(12);
            outRect.left = col * s / perRow;
            outRect.right = s - (col + 1) * s / perRow;
            if (pos >= perRow) outRect.top = dp(12);
        }
    }

    private class ContentAdapter extends RecyclerView.Adapter<ContentAdapter.Holder> {
        static final int T_VIDEO = 0;
        static final int T_TEXT = 1;
        static final int T_RICH = 2;
        static final int T_COLS = 3;
        static final int T_AVATAR = 4;
        static final int T_ICON = 5;
        static final int T_PIC = 6;
        static final int T_MOVIE = 7;
        static final int T_INPUT = 8;
        static final int T_WEB = 10;
        static final int T_BUTTONS = 11;
        static final int T_ICON_FILL = 12;
        static final int T_TEXT_ICON = 13;
        static final int T_CARD = 14;
        static final int T_MOVIE_LEFT_PIC = 15;
        static final int T_ICON_2_ROUND = 16;
        // 图标子类型（对照 8.83 ArticleListAdapter 各 icon_* 独立布局）：
        static final int T_ICON_4_SMALL = 17;   // icon_small_4/icon_5/icon_5_no_crop：32dp 图上文下
        static final int T_ICON_ROUND = 18;     // icon_round_4：42dp 图上文下 + 圆形裁剪
        static final int T_ICON_ROUND_SMALL = 19; // icon_round_small_4：32dp 图上文下 + 圆形裁剪
        static final int T_ICON_2 = 20;         // icon_2：左 28dp 图右文字横向卡片
        static final int T_ICON_4_CARD = 21;    // icon_4_card：44dp 圆角卡片图标 + 文字
        static final int T_ICON_3_SMALL = 22;   // icon_small_3：图标左文字右同行
        static final int T_ICON_1_SEARCH = 23;  // icon_1_search：搜索条
        static final int T_ICON_1_LEFT_PIC = 24; // icon_1_left_pic：左 60dp 图右文全宽行
        static final int T_MOVIE_VERTICAL = 25; // movie_1_vertical_pic：全宽左竖海报右文（8.83 item_movie_one_col_vertical_pic）
        static final int T_MOVIE_VERTICAL_BLUR = 26; // movie_1_vertical_pic_blur：毛玻璃背景 + 竖海报
        static final int T_EMPTY = 27; // 8.83 原版：poly 分组内非首个条目占位的空行（getItemViewType 返回 -1）
        // 8.83 原版：以下条目类型各自独立 viewType（对照 ArticleColTypeEnum），不合并
        static final int T_TEXT_1 = 28;
        static final int T_TEXT_CENTER_1 = 29;
        static final int T_LONG_TEXT = 30;
        static final int T_HEADER = 31;
        static final int T_FOOTER = 32;
        static final int T_TEXT_2 = 33;
        static final int T_TEXT_3 = 34;
        static final int T_TEXT_4 = 35;
        static final int T_TEXT_5 = 36;
        static final int T_CARD_PIC_1 = 37;
        static final int T_CARD_PIC_2 = 38;
        static final int T_CARD_PIC_2_2 = 39;
        static final int T_CARD_PIC_2_2_LEFT = 40;
        static final int T_CARD_PIC_3 = 41;
        static final int T_CARD_PIC_3_CENTER = 42;
        static final int T_PIC_1 = 43;
        static final int T_PIC_1_CARD = 44;
        static final int T_PIC_1_CENTER = 45;
        static final int T_PIC_1_FULL = 46;
        static final int T_PIC_2 = 47;
        static final int T_PIC_2_CARD = 48;
        static final int T_PIC_3 = 49;
        static final int T_PIC_3_SQUARE = 50;
        static final int T_DETAIL_HEADER = 51; // 8.83 原版详情页头部：movie_one_col（标题左+封面右）
        static final int T_HEADER_TABS = 52; // 8.83 bindForHeaderHolder：规则 header 条目横向分类 tab（title/url 按 & 切分）
        // 8.83 第一批补齐（对照 ArticleListAdapter 46 种 ViewHolder）：
        static final int T_NOPIC = 53;        // movie_one_col_nopic：无图影片条目（text_1 用）
        static final int T_NOPIC_CENTER = 54; // movie_one_col_nopic_center：无图居中（text_center_1 用）
        static final int T_LINE = 55;         // line/line_blank：分隔线/空白块（LineHolder）
        static final int T_SCROLL_BUTTON = 56; // scroll_button：横向滚动按钮组（ScrollButtonHolder）
        static final int T_FLEX_BUTTON = 57;   // flex_button：流式按钮组（FlexButtonHolder）
        static final int T_CHANNEL = 58;       // text_2：矩形频道条目（ChannelHolder，item_card_rect）
        static final int T_TOOLS = 59;         // movie_3/movie_3_marquee：影片工具卡片（ToolsHolder）
        static final int T_ICON_FIVE = 60;     // icon_5/icon_5_no_crop：五列图标（IconFiveHolder）

        private final List<HkItem> source;
        /** 展示列表（与 source 同一引用；8.83 原版 getItemCount 直接返回 list.size()，不做快照）。 */
        private List<HkItem> items;
        /** 8.83 updatePolyList：连续相同 col_type 条目的分组。 */
        private final java.util.List<java.util.List<HkItem>> polyList = new java.util.ArrayList<>();
        /** 8.83 polyPositionMap：条目下标 → 所属分组下标。 */
        private final java.util.Map<Integer, Integer> polyPositionMap = new java.util.HashMap<>();
        private int videoImgH;
        private final int picH;

        ContentAdapter(List<HkItem> items) {
            this.source = items;
            this.items = items;
            updatePolyList();
            DisplayMetrics dm = requireContext().getResources().getDisplayMetrics();
            picH = (dm.widthPixels - dp(16) * 2) * 9 / 16;
            updateVideoImgH();
        }

        /**
         * 视频卡片图高：对齐 8.83 item_movie_tools，封面容器固定 150dp。
         * （之前用列宽*3/2≈180dp，比 8.83 高 20%，centerCrop 过度放大导致封面显"歪"。）
         */
        void updateVideoImgH() {
            videoImgH = dp(150);
        }

        /**
         * videos 增删后重算 poly 分组（对照 8.83 updatePolyList 调用点）。
         */
        void refreshGroups() {
            updatePolyList();
        }

        /**
         * 对照 8.83 ArticleListAdapter.updatePolyList()（反编译第 190-213 行）：
         * 连续相同 col_type 的条目自动合并为一组，polyPositionMap 记录条目下标→分组下标。
         * 不造假 col_type，不合成条目。
         */
        private void updatePolyList() {
            polyList.clear();
            polyPositionMap.clear();
            if (source == null || source.isEmpty()) return;
            for (int i = 0; i < source.size(); i++) {
                if (polyList.isEmpty()) polyList.add(new ArrayList<>());
                List<HkItem> last = polyList.get(polyList.size() - 1);
                if (last.isEmpty()) {
                    last.add(source.get(i));
                    polyPositionMap.put(i, 0);
                } else if (colTypeOf(last.get(0)).equals(colTypeOf(source.get(i)))) {
                    last.add(source.get(i));
                    polyPositionMap.put(i, polyList.size() - 1);
                } else {
                    List<HkItem> g = new ArrayList<>();
                    g.add(source.get(i));
                    polyList.add(g);
                    polyPositionMap.put(i, polyList.size() - 1);
                }
            }
        }

        /**
         * 官方 60 列栅格 span（8.83 ArticleColTypeEnum.spanCount 反编译实测）：
         * text_2/pic_2/icon_2(2/行)=30；text_3/pic_3/icon_3(3/行)=20；
         * text_4/icon_4(4/行)=15；text_5/icon_5(5/行)=12；其余全宽=60。
         */
        int spanFor(int position) {
            if (position < 0 || position >= items.size()) return 60;
            HkItem it = items.get(position);
            int t = getItemViewType(position);
            String ct = it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
            switch (t) {
                case T_VIDEO:
                    // 官方：movie_2 span=30（2/行）；movie_3 span=20（3/行）
                    if ("movie_2".equals(ct)) return 30;
                    return 20;
                case T_COLS:
                case T_TEXT_2:
                    // text_2=30（2/行）
                    if (ct.endsWith("_2")) return 30;
                    if (ct.endsWith("_3")) return 20;
                    if (ct.endsWith("_4")) return 15;
                    if (ct.endsWith("_5")) return 12;
                    return 30;
                case T_TEXT_3:
                    return 20; // text_3=20（3/行）
                case T_TEXT_4:
                    return 15; // text_4=15（4/行）
                case T_TEXT_5:
                    return 12; // text_5=12（5/行）
                case T_ICON:
                case T_ICON_ROUND:
                    return 15; // icon_4 / icon_round_4：4/行（官方 spanCount=15）
                case T_ICON_4_SMALL:
                case T_ICON_ROUND_SMALL:
                    if ("icon_5".equals(ct) || "icon_5_no_crop".equals(ct)) return 12; // 官方 spanCount=12
                    return 15; // icon_small_4 / icon_round_small_4：4/行
                case T_ICON_4_CARD:
                    return 15; // icon_4_card：4/行（官方 spanCount=15）
                case T_ICON_FILL:
                    return 20; // icon_3_fill / icon_3_round_fill：3/行
                case T_ICON_2:
                case T_ICON_2_ROUND:
                    return 30; // icon_2 / icon_2_round：2/行（官方 spanCount=30）
                case T_ICON_3_SMALL:
                    return 20; // icon_small_3：3/行（官方 spanCount=20）
                case T_ICON_1_SEARCH:
                case T_ICON_1_LEFT_PIC:
                    return 60; // icon_1_search / icon_1_left_pic 全宽（官方 spanCount=60）
                case T_TEXT_ICON:
                    return 60; // text_icon 全宽
                case T_CARD:
                    if ("card_pic_2".equals(ct) || "card_pic_2_2".equals(ct)
                            || "card_pic_2_2_left".equals(ct)) return 30;
                    if ("card_pic_3".equals(ct) || "card_pic_3_center".equals(ct)) return 20;
                    return 60; // card_pic_1 全宽
                case T_CARD_PIC_1:
                    return 60; // card_pic_1 全宽
                case T_CARD_PIC_2:
                case T_CARD_PIC_2_2:
                case T_CARD_PIC_2_2_LEFT:
                    return 30; // 2/行
                case T_CARD_PIC_3:
                case T_CARD_PIC_3_CENTER:
                    return 20; // 3/行
                case T_MOVIE_LEFT_PIC:
                    return 60; // movie_1 左图右文全宽
                case T_MOVIE_VERTICAL:
                case T_MOVIE_VERTICAL_BLUR:
                    return 60; // movie_1_vertical_pic(_blur) 全宽（D3）
                case T_PIC:
                    if (ct.startsWith("pic_2") || "card_pic_2".equals(ct)
                            || "card_pic_2_2".equals(ct) || "card_pic_2_2_left".equals(ct)) return 30;
                    if (ct.startsWith("pic_3") || "card_pic_3".equals(ct) || "card_pic_3_center".equals(ct)) return 20;
                    return 60; // pic_1 / pic_1_full / pic_1_center / pic_1_card / card_pic_1
                case T_PIC_1:
                case T_PIC_1_CARD:
                case T_PIC_1_CENTER:
                case T_PIC_1_FULL:
                    return 60; // pic_1 系列全宽
                case T_PIC_2:
                case T_PIC_2_CARD:
                    return 30; // pic_2 系列 2/行
                case T_PIC_3:
                case T_PIC_3_SQUARE:
                    return 20; // pic_3 系列 3/行
                case T_DETAIL_HEADER:
                    return 60; // 详情页头部全宽
                case T_NOPIC:
                case T_NOPIC_CENTER:
                case T_LINE:
                case T_SCROLL_BUTTON:
                case T_FLEX_BUTTON:
                case T_CHANNEL:
                    return 60; // 全宽
                case T_TOOLS:
                    return 20; // movie_3：3/行（官方 spanCount=20）
                case T_ICON_FIVE:
                    return 12; // icon_5：5/行（官方 spanCount=12）
                default:
                    return 60;
            }
        }

        class Holder extends RecyclerView.ViewHolder {
            ImageView cover;
            TextView title, desc, overlay;
            LinearLayout cols;
            EditText input;
            android.webkit.WebView web;
            // 8.83 第一批补齐新增：
            android.widget.HorizontalScrollView hsvButtons; // T_SCROLL_BUTTON
            LinearLayout llButtons;                          // T_SCROLL_BUTTON
            TextView tvExpand;                               // T_SCROLL_BUTTON
            com.google.android.flexbox.FlexboxLayout flexbox; // T_FLEX_BUTTON

            Holder(View v, int type) {
                super(v);
                switch (type) {
                    case T_VIDEO:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        overlay = v.findViewById(R.id.tv_overlay);
                        View containerV = v.findViewById(R.id.cover_container);
                        if (containerV != null) {
                            ViewGroup.LayoutParams vlp = containerV.getLayoutParams();
                            vlp.height = videoImgH;
                            containerV.setLayoutParams(vlp);
                        } else {
                            ViewGroup.LayoutParams vlp = cover.getLayoutParams();
                            vlp.height = videoImgH;
                            cover.setLayoutParams(vlp);
                        }
                        break;
                    case T_TEXT:
                    case T_TEXT_1:
                    case T_TEXT_CENTER_1:
                    case T_LONG_TEXT:
                    case T_HEADER:
                    case T_FOOTER:
                    case T_RICH:
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_COLS:
                    case T_TEXT_2:
                    case T_TEXT_3:
                    case T_TEXT_4:
                    case T_TEXT_5:
                    case T_BUTTONS:
                    case T_HEADER_TABS:
                        cols = v.findViewById(R.id.cols_container);
                        break;
                    case T_AVATAR:
                        cover = v.findViewById(R.id.iv_avatar);
                        title = v.findViewById(R.id.tv_title);
                        cover.setClipToOutline(true);
                        cover.setOutlineProvider(new ViewOutlineProvider() {
                            @Override
                            public void getOutline(View view, Outline outline) {
                                outline.setOval(0, 0, view.getWidth(), view.getHeight());
                            }
                        });
                        break;
                    case T_ICON:
                    case T_ICON_FILL:
                    case T_ICON_2_ROUND:
                    case T_ICON_4_SMALL:
                    case T_ICON_ROUND:
                    case T_ICON_ROUND_SMALL:
                    case T_ICON_2:
                    case T_ICON_4_CARD:
                    case T_ICON_3_SMALL:
                    case T_ICON_1_SEARCH:
                        cover = v.findViewById(R.id.iv_icon);
                        title = v.findViewById(R.id.tv_title);
                        break;
                    case T_ICON_1_LEFT_PIC:
                        cover = v.findViewById(R.id.iv_icon);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_TEXT_ICON:
                        cover = v.findViewById(R.id.iv_icon);
                        title = v.findViewById(R.id.tv_title);
                        break;
                    case T_CARD:
                    case T_CARD_PIC_1:
                    case T_CARD_PIC_2:
                    case T_CARD_PIC_2_2:
                    case T_CARD_PIC_2_2_LEFT:
                    case T_CARD_PIC_3:
                    case T_CARD_PIC_3_CENTER:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        overlay = v.findViewById(R.id.tv_overlay);
                        break;
                    case T_MOVIE_LEFT_PIC:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_MOVIE_VERTICAL:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_MOVIE_VERTICAL_BLUR:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_PIC:
                    case T_PIC_1:
                    case T_PIC_1_CARD:
                    case T_PIC_1_CENTER:
                    case T_PIC_1_FULL:
                    case T_PIC_2:
                    case T_PIC_2_CARD:
                    case T_PIC_3:
                    case T_PIC_3_SQUARE:
                        cover = v.findViewById(R.id.iv_pic);
                        ViewGroup.LayoutParams plp = cover.getLayoutParams();
                        plp.height = picH;
                        cover.setLayoutParams(plp);
                        break;
                    case T_MOVIE:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_INPUT:
                        input = v.findViewById(R.id.et_input);
                        break;
                    case T_WEB:
                        web = v.findViewById(R.id.wv_page);
                        web.getSettings().setJavaScriptEnabled(true);
                        web.getSettings().setDomStorageEnabled(true);
                        web.setWebViewClient(new android.webkit.WebViewClient());
                        break;
                    case T_DETAIL_HEADER:
                        // 8.83 原版详情页头部（item_movie_one_col）：标题左 + 封面右
                        cover = v.findViewById(R.id.dh_cover);
                        title = v.findViewById(R.id.dh_title);
                        desc = v.findViewById(R.id.dh_desc);
                        break;
                    case T_NOPIC:
                    case T_NOPIC_CENTER:
                        // 8.83 item_movie_one_col_nopic(_center)：无图影片条目
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_LINE:
                        // 8.83 LineHolder：分隔线/空白块，无需绑定子 view
                        break;
                    case T_SCROLL_BUTTON:
                        // 8.83 ScrollButtonHolder：横向滚动按钮组
                        hsvButtons = v.findViewById(R.id.hsv_buttons);
                        llButtons = v.findViewById(R.id.ll_buttons);
                        tvExpand = v.findViewById(R.id.tv_expand);
                        break;
                    case T_FLEX_BUTTON:
                        // 8.83 FlexButtonHolder：流式按钮组
                        flexbox = v.findViewById(R.id.flexboxLayout);
                        break;
                    case T_CHANNEL:
                        // 8.83 ChannelHolder（item_card_rect）：矩形频道条目
                        title = v.findViewById(R.id.tv_title);
                        break;
                    case T_TOOLS:
                        // 8.83 ToolsHolder（item_movie_tools）：影片工具卡片
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_ICON_FIVE:
                        // 8.83 IconFiveHolder：五列图标
                        cover = v.findViewById(R.id.iv_icon);
                        title = v.findViewById(R.id.tv_title);
                        break;
                }
            }
        }

        /**
         * colType → T_* 常量映射。对照 8.83 ArticleColTypeEnum.getItemTypeByCode
         * 及 HkPageActivity.contentTypeOf（原版逻辑），不可 stub。
         */
        private int contentTypeOf(HkItem it) {
            String ct = it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
            switch (ct) {
                case "text_1":
                    return T_TEXT_1;
                case "text_center_1":
                    return T_TEXT_CENTER_1;
                case "long_text":
                    return T_LONG_TEXT;
                case "detail_header":
                    return T_DETAIL_HEADER;
                case "header":
                    return T_HEADER_TABS;
                case "footer":
                    return T_FOOTER;
                case "text_icon":
                    return T_TEXT_ICON;
                case "rich_text":
                    return T_RICH;
                case "text_2":
                    return T_CHANNEL;
                case "text_3":
                    return T_TEXT_3;
                case "text_4":
                    return T_TEXT_4;
                case "text_5":
                    return T_TEXT_5;
                case "avatar":
                    return T_AVATAR;
                case "input":
                    return T_INPUT;
                case "line":
                case "line_blank":
                    return T_LINE;
                case "x5_webview_single":
                case "video":
                    return T_WEB;
                case "scroll_button":
                    return T_SCROLL_BUTTON;
                case "flex_button":
                    return T_FLEX_BUTTON;
                case "card_pic_1":
                    return T_CARD_PIC_1;
                case "card_pic_2":
                    return T_CARD_PIC_2;
                case "card_pic_2_2":
                    return T_CARD_PIC_2_2;
                case "card_pic_2_2_left":
                    return T_CARD_PIC_2_2_LEFT;
                case "card_pic_3":
                    return T_CARD_PIC_3;
                case "card_pic_3_center":
                    return T_CARD_PIC_3_CENTER;
                case "":
                case "movie_3":
                    return T_TOOLS;
                case "movie_1":
                    return T_MOVIE;
                case "movie_1_left_pic":
                    return T_MOVIE_LEFT_PIC;
                case "movie_1_vertical_pic":
                    return T_MOVIE_VERTICAL;
                case "movie_1_vertical_pic_blur":
                    return T_MOVIE_VERTICAL_BLUR;
                case "movie_3_marquee":
                    return T_TOOLS;
                case "movie_2":
                    return T_VIDEO;
                default:
                    if (ct.startsWith("icon")) {
                        if ("icon_3_fill".equals(ct) || "icon_3_round_fill".equals(ct)) return T_ICON_FILL;
                        if ("icon_2_round".equals(ct)) return T_ICON_2_ROUND;
                        if ("icon_2".equals(ct)) return T_ICON_2;
                        if ("icon_4_card".equals(ct)) return T_ICON_4_CARD;
                        if ("icon_small_3".equals(ct)) return T_ICON_3_SMALL;
                        if ("icon_1_search".equals(ct)) return T_ICON_1_SEARCH;
                        if ("icon_1_left_pic".equals(ct)) return T_ICON_1_LEFT_PIC;
                        if ("icon_round_4".equals(ct)) return T_ICON_ROUND;
                        if ("icon_round_small_4".equals(ct)) return T_ICON_ROUND_SMALL;
                        if ("icon_small_4".equals(ct)) return T_ICON_4_SMALL;
                        if ("icon_5".equals(ct) || "icon_5_no_crop".equals(ct)) return T_ICON_FIVE;
                        return T_ICON;
                    }
                    if ("pic_1".equals(ct)) return T_PIC_1;
                    if ("pic_1_card".equals(ct)) return T_PIC_1_CARD;
                    if ("pic_1_center".equals(ct)) return T_PIC_1_CENTER;
                    if ("pic_1_full".equals(ct)) return T_PIC_1_FULL;
                    if ("pic_2".equals(ct)) return T_PIC_2;
                    if ("pic_2_card".equals(ct)) return T_PIC_2_CARD;
                    if ("pic_3".equals(ct)) return T_PIC_3;
                    if ("pic_3_square".equals(ct)) return T_PIC_3_SQUARE;
                    if (ct.startsWith("pic")) return T_PIC_1;
                    if (ct.startsWith("movie")) return T_VIDEO;
                    return T_VIDEO;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) return T_VIDEO;
            int t = contentTypeOf(items.get(position));
            // 对照 8.83 getItemViewType（反编译第 309-321 行）：
            // scroll_button/flex_button 连续组内非首个条目 → 空行（8.83 返回 -1）
            if (t == T_BUTTONS) {
                Integer gi = polyPositionMap.get(position);
                if (gi != null && gi >= 0 && gi < polyList.size()) {
                    List<HkItem> g = polyList.get(gi);
                    if (g != null && !g.isEmpty() && g.get(0) != items.get(position)) {
                        return T_EMPTY;
                    }
                }
            }
            return t;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 8.83 原版：poly 分组内非首个条目返回 -1，对应空行占位
            if (viewType == T_EMPTY) {
                android.widget.Space v = new android.widget.Space(parent.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0));
                return new Holder(v, viewType);
            }
            int layout;
            switch (viewType) {
                case T_TEXT:
                case T_TEXT_1:
                case T_TEXT_CENTER_1:
                case T_LONG_TEXT:
                case T_HEADER:
                case T_FOOTER:
                case T_RICH:
                    layout = R.layout.item_hk_text;
                    break;
                case T_COLS:
                case T_TEXT_2:
                case T_TEXT_3:
                case T_TEXT_4:
                case T_TEXT_5:
                    layout = R.layout.item_hk_cols;
                    break;
                case T_BUTTONS:
                    layout = R.layout.item_hk_cols;
                    break;
                case T_HEADER_TABS:
                    layout = R.layout.item_hk_cols;
                    break;
                case T_AVATAR:
                    layout = R.layout.item_hk_avatar;
                    break;
                case T_ICON:
                    layout = R.layout.item_hk_icon_four_col; // icon_4：42dp 图上文下（对照 8.83 item_icon_four_col）
                    break;
                case T_ICON_4_SMALL:
                    layout = R.layout.item_hk_icon_four_small; // icon_small_4/icon_5：32dp 图上文下
                    break;
                case T_ICON_ROUND:
                    layout = R.layout.item_hk_icon_four_col; // icon_round_4：42dp，bind 时圆形裁剪
                    break;
                case T_ICON_ROUND_SMALL:
                    layout = R.layout.item_hk_icon_four_small; // icon_round_small_4：32dp，bind 时圆形裁剪
                    break;
                case T_ICON_2:
                    layout = R.layout.item_hk_icon_two_col; // icon_2：左 28dp 图右文字横向卡片
                    break;
                case T_ICON_4_CARD:
                    layout = R.layout.item_hk_icon_four_card; // icon_4_card：44dp 圆角图标 + 文字
                    break;
                case T_ICON_3_SMALL:
                    layout = R.layout.item_hk_icon_three_small; // icon_small_3：图标左文字右同行
                    break;
                case T_ICON_1_SEARCH:
                    layout = R.layout.item_hk_icon_search_col; // icon_1_search：搜索条
                    break;
                case T_ICON_1_LEFT_PIC:
                    layout = R.layout.item_hk_icon_left_pic; // icon_1_left_pic：左 60dp 图右文全宽
                    break;
                case T_ICON_FILL:
                    layout = R.layout.item_hk_icon_3_fill;
                    break;
                case T_ICON_2_ROUND:
                    layout = R.layout.item_hk_icon_2_round;
                    break;
                case T_TEXT_ICON:
                    layout = R.layout.item_hk_text_icon;
                    break;
                case T_CARD:
                case T_CARD_PIC_1:
                case T_CARD_PIC_2:
                case T_CARD_PIC_2_2:
                case T_CARD_PIC_2_2_LEFT:
                case T_CARD_PIC_3:
                case T_CARD_PIC_3_CENTER:
                    layout = R.layout.item_hk_card_rect;
                    break;
                case T_MOVIE_LEFT_PIC:
                    layout = R.layout.item_hk_movie_left_pic;
                    break;
                case T_MOVIE_VERTICAL:
                    layout = R.layout.item_hk_movie_vertical;
                    break;
                case T_MOVIE_VERTICAL_BLUR:
                    layout = R.layout.item_hk_movie_vertical_blur;
                    break;
                case T_PIC:
                case T_PIC_1:
                case T_PIC_1_CARD:
                case T_PIC_1_CENTER:
                case T_PIC_1_FULL:
                case T_PIC_2:
                case T_PIC_2_CARD:
                case T_PIC_3:
                case T_PIC_3_SQUARE:
                    layout = R.layout.item_hk_pic;
                    break;
                case T_MOVIE:
                    layout = R.layout.item_hk_movie;
                    break;
                case T_INPUT:
                    layout = R.layout.item_hk_input;
                    break;
                case T_WEB:
                    layout = R.layout.item_hk_web;
                    break;
                case T_DETAIL_HEADER:
                    layout = R.layout.item_hk_detail_header;
                    break;
                case T_NOPIC:
                    layout = R.layout.item_hk_movie_nopic;
                    break;
                case T_NOPIC_CENTER:
                    layout = R.layout.item_hk_movie_nopic_center;
                    break;
                case T_LINE:
                    // line 用细线，line_blank 用空白块——按 col_type 区分在 bind 时处理，
                    // 此处默认细线布局
                    layout = R.layout.item_hk_line;
                    break;
                case T_SCROLL_BUTTON:
                    layout = R.layout.item_hk_scroll_button;
                    break;
                case T_FLEX_BUTTON:
                    layout = R.layout.item_hk_flex_button;
                    break;
                case T_CHANNEL:
                    layout = R.layout.item_hk_channel;
                    break;
                case T_TOOLS:
                    layout = R.layout.item_hk_tools;
                    break;
                case T_ICON_FIVE:
                    layout = R.layout.item_hk_icon_five;
                    break;
                default:
                    layout = R.layout.item_hk_video;
                    break;
            }
            View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new Holder(v, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            if (position < 0 || position >= items.size()) return;
            HkItem item = items.get(position);
            // 按 Holder 创建时的 viewType 来 bind：布局过程中若 viewType 发生变化，
            // 用 position 查到的新 viewType 去 bind 会与 Holder 已初始化的 view 不一致，
            // 导致 TextView 为 null 崩溃（movie_1/movie_2 改 span 后复现）。
            switch (h.getItemViewType()) {
                case T_VIDEO:
                    bindVideo(h, item);
                    break;
                case T_TEXT:
                case T_TEXT_1:
                case T_TEXT_CENTER_1:
                case T_LONG_TEXT:
                case T_HEADER:
                case T_FOOTER:
                    bindText(h, item);
                    break;
                case T_RICH:
                    bindRich(h, item);
                    break;
                case T_COLS:
                case T_TEXT_2:
                case T_TEXT_3:
                case T_TEXT_4:
                case T_TEXT_5:
                    bindCols(h, item);
                    break;
                case T_BUTTONS:
                    bindButtons(h, position);
                    break;
                case T_HEADER_TABS:
                    bindHeaderTabs(h, item);
                    break;
                case T_AVATAR:
                    bindAvatar(h, item);
                    break;
                case T_ICON:
                case T_ICON_FILL:
                case T_ICON_2_ROUND:
                case T_ICON_4_SMALL:
                case T_ICON_ROUND:
                case T_ICON_ROUND_SMALL:
                case T_ICON_2:
                case T_ICON_4_CARD:
                case T_ICON_3_SMALL:
                case T_ICON_1_SEARCH:
                case T_ICON_1_LEFT_PIC:
                    bindIcon(h, item);
                    break;
                case T_TEXT_ICON:
                    bindTextIcon(h, item);
                    break;
                case T_CARD:
                case T_CARD_PIC_1:
                case T_CARD_PIC_2:
                case T_CARD_PIC_2_2:
                case T_CARD_PIC_2_2_LEFT:
                case T_CARD_PIC_3:
                case T_CARD_PIC_3_CENTER:
                    bindCard(h, item);
                    break;
                case T_MOVIE_LEFT_PIC:
                    bindMovie(h, item);
                    break;
                case T_MOVIE_VERTICAL:
                    bindMovieVertical(h, item, false);
                    break;
                case T_MOVIE_VERTICAL_BLUR:
                    bindMovieVertical(h, item, true);
                    break;
                case T_PIC:
                case T_PIC_1:
                case T_PIC_1_CARD:
                case T_PIC_1_CENTER:
                case T_PIC_1_FULL:
                case T_PIC_2:
                case T_PIC_2_CARD:
                case T_PIC_3:
                case T_PIC_3_SQUARE:
                    bindPic(h, item);
                    break;
                case T_MOVIE:
                    bindMovie(h, item);
                    break;
                case T_INPUT:
                    bindInput(h, item);
                    break;
                case T_EMPTY:
                    // poly 分组内非首个条目的空行占位，无需绑定
                    break;
                case T_WEB:
                    bindWeb(h, item);
                    break;
                case T_DETAIL_HEADER:
                    bindDetailHeader(h, item);
                    break;
                case T_NOPIC:
                case T_NOPIC_CENTER:
                    // 8.83 无图影片条目：标题+描述
                    bindText(h, item);
                    break;
                case T_LINE:
                    // 8.83 LineHolder：分隔线/空白块，无需绑定
                    break;
                case T_SCROLL_BUTTON:
                    bindScrollButtons(h, position);
                    break;
                case T_FLEX_BUTTON:
                    bindFlexButtons(h, position);
                    break;
                case T_CHANNEL:
                    // 8.83 ChannelHolder：矩形频道条目，标题居中
                    if (h.title != null) {
                        h.title.setText(titleSpan(item.getTitle()));
                    }
                    break;
                case T_TOOLS:
                    // 8.83 ToolsHolder：影片工具卡片，复用 bindVideo（cover/title/desc）
                    bindVideo(h, item);
                    break;
                case T_ICON_FIVE:
                    // 8.83 IconFiveHolder：五列图标，复用 bindIcon
                    bindIcon(h, item);
                    break;
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        /** 视频卡片：保持原 VideoAdapter 行为；desc 压在封面底部（原版 overlay 样式）。 */
        private void bindVideo(Holder h, HkItem item) {
            if (h.title == null || h.cover == null) return; // 防 ViewHolder 类型错配
            h.title.setText(titleSpan(item.getTitle()));
            // movie_3_marquee：标题跑马灯（对照 8.83 item_movie_tools_marquee，marqueeRepeatLimit=3）；
            // 非 marquee 时复位（防复用串台）
            String ct = item.getColType() == null ? "" : item.getColType().trim().toLowerCase();
            if ("movie_3_marquee".equals(ct)) {
                h.title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                h.title.setSingleLine(true);
                h.title.setMarqueeRepeatLimit(3);
                h.title.setSelected(true);
            } else {
                // 8.83 原版：标题单行截断（item_movie_tools reult_tools_title maxLines=1）
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                h.title.setSingleLine(true);
                h.title.setSelected(false);
            }
            // desc 压在封面上（原版样式），不再放标题下面
            String d = stripHtml(item.getDesc());
            if (h.overlay != null) {
                h.overlay.setText(d);
                h.overlay.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
            h.desc.setVisibility(View.GONE);
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            // 8.83：所有条目类型走同一条 onClick→clickItem→dealWithUrl 链，
            // video 也不单开直调详情（否则动作协议/@rule=分流/详情内直播等都被跳过）
            h.itemView.setOnClickListener(v -> {
                try {
                    int pos = h.getAdapterPosition();
                    if (pos >= 0 && pos < items.size() && hostRule() != null) {
                        onContentItemClick(items.get(pos));
                    }
                } catch (Exception e) {
                    android.util.Log.e("HkPage", "bindVideo click failed", e);
                }
            });
        }

        /** 文本行：text_center_1 居中灰字；text_1 去标签标题+描述；long_text 多行。 */
        private void bindText(Holder h, HkItem item) {
            String ct = colTypeOf(item);
            if ("text_center_1".equals(ct)) {
                h.title.setGravity(Gravity.CENTER);
                h.title.setTextColor(0xFF101216);
                h.title.setTextSize(13);
                h.title.setMaxLines(4);
                h.title.setText(titleSpan(item.getTitle()));
                h.desc.setVisibility(View.GONE);
            } else {
                boolean longText = "long_text".equals(ct);
                h.title.setGravity(Gravity.START);
                h.title.setTextColor(0xFF1A1D24);
                h.title.setTextSize(14);
                h.title.setMaxLines(longText ? 30 : 3);
                h.title.setText(titleSpan(item.getTitle()));
                String rawD = item.getDesc();
                if (TextUtils.isEmpty(rawD)) {
                    h.desc.setVisibility(View.GONE);
                } else {
                    h.desc.setVisibility(View.VISIBLE);
                    if (longText) {
                        try {
                            String htmlD = rawD.replace("\n", "<br>");
                            h.desc.setText(Html.fromHtml(htmlD, Html.FROM_HTML_MODE_LEGACY));
                        } catch (Throwable t) {
                            h.desc.setText(stripHtml(rawD));
                        }
                    } else {
                        h.desc.setText(stripHtml(rawD));
                    }
                }
                h.desc.setMaxLines(longText ? 60 : 5);
            }
            setContentClick(h, item);
        }

        /** 富文本行：Html.fromHtml 显示，失败回退去标签；标题颜色标记（弯引号 ""红/''橙）同样生效。 */
        private void bindRich(Holder h, HkItem item) {
            h.title.setGravity(Gravity.START);
            h.title.setTextColor(0xFF1A1D24);
            h.title.setTextSize(14);
            h.title.setMaxLines(30);
            String rawTitle = item.getTitle() == null ? "" : item.getTitle();
            String markedHtml = applyTitleMarkHtml(rawTitle);
            try {
                CharSequence cs = Html.fromHtml(markedHtml != null ? markedHtml : rawTitle, Html.FROM_HTML_MODE_LEGACY);
                h.title.setText(cs);
            } catch (Throwable t) {
                h.title.setText(titleSpan(item.getTitle()));
            }
            String d = item.getDesc();
            if (TextUtils.isEmpty(d)) {
                h.desc.setVisibility(View.GONE);
            } else {
                h.desc.setVisibility(View.VISIBLE);
                try {
                    h.desc.setText(Html.fromHtml(d, Html.FROM_HTML_MODE_LEGACY));
                } catch (Throwable t) {
                    h.desc.setText(stripHtml(d));
                }
            }
            setContentClick(h, item);
        }

        /**
         * 多列文本格：官方语义是一个条目占一格，N 个条目一行（text_2=2/行 … text_5=5/行，
         * span 由 spanFor 按 60 列栅格给出）。标题居中显示一格，不再切分标题。
         */
        private void bindCols(Holder h, HkItem item) {
            h.cols.removeAllViews();
            Context ctx = h.itemView.getContext();
            TextView tv = new TextView(ctx);
            tv.setText(titleSpan(item.getTitle()));
            tv.setTextColor(0xFF1A1D24);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            int pad = dp(5);
            tv.setPadding(pad, dp(10), pad, dp(10));
            // 8.83 text_2~text_5 用 item_card_rect 系列布局，带 tab_item_default 底框（分类格子有框）
            try {
                tv.setBackgroundResource(R.drawable.tab_item_default);
            } catch (Throwable ignored) {}
            tv.setMinHeight(dp(40));
            h.cols.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setContentClick(h, item);
        }

        /**
         * 流式胶囊按钮组：对照 8.83 bindForScrollButtonHolder，
         * 从 polyList 取整组渲染（position 为组内首个条目）。
         */
        private void bindButtons(Holder h, int position) {
            h.cols.removeAllViews();
            Integer gi = polyPositionMap.get(position);
            List<HkItem> subs = (gi == null || gi < 0 || gi >= polyList.size()) ? null : polyList.get(gi);
            if (subs == null || subs.isEmpty()) return;
            Context ctx = h.itemView.getContext();
            int parentW = h.cols.getWidth();
            if (parentW <= 0) {
                parentW = ctx.getResources().getDisplayMetrics().widthPixels - dp(16) * 2;
            }
            LinearLayout flow = new LinearLayout(ctx);
            flow.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = newButtonRow(ctx);
            int rowW = 0;
            int padH = dp(14), padV = dp(7);
            for (HkItem sub : subs) {
                TextView tv = new TextView(ctx);
                tv.setText(titleSpan(sub.getTitle()));
                tv.setTextColor(0xFF1A1D24);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(padH, padV, padH, padV);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFFF1F3F6);
                bg.setCornerRadius(dp(14));
                tv.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(8);
                lp.bottomMargin = dp(8);
                tv.setLayoutParams(lp);
                tv.setOnClickListener(v -> onContentItemClick(sub));
                tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int w = tv.getMeasuredWidth() + dp(8);
                if (rowW + w > parentW && rowW > 0) {
                    flow.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row = newButtonRow(ctx);
                    rowW = 0;
                }
                row.addView(tv);
                rowW += w;
            }
            flow.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            h.cols.addView(flow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private LinearLayout newButtonRow(Context ctx) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            return row;
        }

        /**
         * 8.83 ScrollButtonHolder：横向滚动按钮组。
         * 从 poly 分组取同组条目，横向排列在 HorizontalScrollView 内。
         */
        private void bindScrollButtons(Holder h, int position) {
            if (h.llButtons == null) return;
            h.llButtons.removeAllViews();
            Integer gi = polyPositionMap.get(position);
            List<HkItem> subs = (gi == null || gi < 0 || gi >= polyList.size()) ? null : polyList.get(gi);
            if (subs == null || subs.isEmpty()) {
                // 非分组场景：只渲染当前条目
                subs = new ArrayList<>();
                subs.add(items.get(position));
            }
            Context ctx = h.itemView.getContext();
            int padH = dp(14), padV = dp(7);
            for (HkItem sub : subs) {
                TextView tv = new TextView(ctx);
                tv.setText(titleSpan(sub.getTitle()));
                tv.setTextColor(0xFF1A1D24);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(padH, padV, padH, padV);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFFF1F3F6);
                bg.setCornerRadius(dp(14));
                tv.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(8);
                tv.setLayoutParams(lp);
                tv.setOnClickListener(v -> onContentItemClick(sub));
                h.llButtons.addView(tv);
            }
        }

        /**
         * 8.83 FlexButtonHolder：流式按钮组（FlexboxLayout 自动换行）。
         */
        private void bindFlexButtons(Holder h, int position) {
            if (h.flexbox == null) return;
            h.flexbox.removeAllViews();
            Integer gi = polyPositionMap.get(position);
            List<HkItem> subs = (gi == null || gi < 0 || gi >= polyList.size()) ? null : polyList.get(gi);
            if (subs == null || subs.isEmpty()) {
                subs = new ArrayList<>();
                subs.add(items.get(position));
            }
            Context ctx = h.itemView.getContext();
            int padH = dp(14), padV = dp(7);
            for (HkItem sub : subs) {
                TextView tv = new TextView(ctx);
                tv.setText(titleSpan(sub.getTitle()));
                tv.setTextColor(0xFF1A1D24);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(padH, padV, padH, padV);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFFF1F3F6);
                bg.setCornerRadius(dp(14));
                tv.setBackground(bg);
                com.google.android.flexbox.FlexboxLayout.LayoutParams lp =
                        new com.google.android.flexbox.FlexboxLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(8);
                lp.bottomMargin = dp(8);
                tv.setLayoutParams(lp);
                tv.setOnClickListener(v -> onContentItemClick(sub));
                h.flexbox.addView(tv);
            }
        }

        /**
         * 规则 header 横向分类 tab：对照 8.83 bindForHeaderHolder。
         * title 按 "&" 切分 tab 名，url 按 "&" 切分对应链接；点 tab → evalTab 求值后重刷；
         * ">" 按钮弹"请选择分类"弹窗。选中态：rule.url 含 tabUrl 且 firstHeader == desc。
         */
        private void bindHeaderTabs(Holder h, HkItem item) {
            h.cols.removeAllViews();
            Context ctx = h.itemView.getContext();
            String title = item.getTitle() == null ? "" : item.getTitle();
            String url = item.getUrl() == null ? "" : item.getUrl();
            String[] names = title.split("&");
            String[] urls = url.split("&");
            if (names.length == 0) return;
            final String desc = item.getDesc() == null ? "" : item.getDesc();
            final String ruleUrl = hostRule() == null || hostRule().getUrl() == null ? "" : hostRule().getUrl();
            final String firstHeader = hostRule() == null ? "" : hostRule().getFirstHeader();
            final List<String[]> pairs = new ArrayList<>();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < names.length; i++) {
                final String name = stripHtml(names[i]).trim();
                final String tabUrl = i < urls.length ? urls[i].trim() : "";
                if (name.isEmpty()) continue;
                pairs.add(new String[]{name, tabUrl});
                TextView tv = new TextView(ctx);
                tv.setText(name);
                tv.setTextSize(14);
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                // 8.83 选中态：rule.url 含 tabUrl 且 firstHeader == desc → tab_item_selected
                boolean selected = !tabUrl.isEmpty() && ruleUrl.contains(tabUrl) && firstHeader.equals(desc);
                tv.setTextColor(selected ? 0xFFE8590C : 0xFF1A1D24);
                try {
                    tv.setBackgroundResource(selected ? R.drawable.tab_item_selected : R.drawable.tab_item_default);
                } catch (Throwable ignored) {}
                int padH = dp(12), padV = dp(8);
                tv.setPadding(padH, padV, padH, padV);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                final int idx = pairs.size() - 1;
                tv.setOnClickListener(v -> onHeaderTabClick(pairs.get(idx)[1]));
                row.addView(tv, lp);
            }
            h.cols.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 8.83 ">" → "请选择分类"弹窗
            if (names.length > 1) {
                TextView btnExpand = new TextView(ctx);
                btnExpand.setText(">");
                btnExpand.setTextSize(16);
                btnExpand.setTextColor(0xFF666666);
                btnExpand.setGravity(Gravity.CENTER);
                try {
                    btnExpand.setBackgroundResource(R.drawable.tab_item_default);
                } catch (Throwable ignored) {}
                btnExpand.setPadding(dp(10), dp(4), dp(10), dp(4));
                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                btnLp.leftMargin = dp(3);
                final List<String[]> finalPairs = pairs;
                btnExpand.setOnClickListener(v -> showCategoryPopup(finalPairs, -1, value -> onHeaderTabClick(value)));
                h.cols.addView(btnExpand, btnLp);
            }
            // header 条目本身不可点（8.83 点不动），只响应 tab 点击
            h.itemView.setOnClickListener(null);
            h.itemView.setClickable(false);
            h.itemView.setFocusable(false);
        }

        /**
         * "请选择分类"弹窗。对照 8.83（HkPageActivity.showCategoryPopup），
         * 用普通 AlertDialog 列表实现，点击后回调 onPick。
         */
        private void showCategoryPopup(java.util.List<String[]> pairs, int selected,
                                       java.util.function.Consumer<String> onPick) {
            android.content.Context ctx = requireContext();
            String[] names = new String[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                names[i] = pairs.get(i)[0];
            }
            new AlertDialog.Builder(ctx)
                    .setTitle("请选择分类")
                    .setItems(names, (d, which) -> {
                        if (which >= 0 && which < pairs.size()) {
                            onPick.accept(pairs.get(which)[1]);
                        }
                    })
                    .show();
        }

        /** header tab 点击：8.83 onClassClick 等价 → evalTab 求值分类变量后重刷列表。 */
        private void onHeaderTabClick(String tabUrl) {
            if (tabUrl == null || tabUrl.isEmpty() || hostRule() == null) return;
            new Thread(() -> {
                try {
                    router().evalTab(tabUrl);
                } catch (Throwable ignored) {
                }
                App.post(() -> hostLoadContent(true));
            }).start();
        }

        /** 头像行：圆形图 + 标题横向。 */
        private void bindAvatar(Holder h, HkItem item) {
            h.title.setText(titleSpan(item.getTitle()));
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /** 图标：各 icon_* 子类型按 8.83 走独立布局；round 系列图标圆形裁剪（对照 8.83 bindCircleCropImageView）。 */
        private void bindIcon(Holder h, HkItem item) {
            if (h.title == null || h.cover == null) return; // 防 ViewHolder 类型错配
            h.title.setText(titleSpan(item.getTitle()));
            int vt = h.getItemViewType();
            if (vt == T_ICON_ROUND || vt == T_ICON_ROUND_SMALL || vt == T_ICON_2_ROUND) {
                ImgUtil.loadCircle(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            } else {
                ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            }
            if (vt == T_ICON_1_LEFT_PIC && h.desc != null) {
                String d = stripHtml(item.getDesc());
                h.desc.setText(d);
                h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
            setContentClick(h, item);
        }

        /** 图文行：text_icon，左文字右图标（对照 8.83 item_text_icon）。 */
        private void bindTextIcon(Holder h, HkItem item) {
            h.title.setText(titleSpan(item.getTitle()));
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /** 卡片：card_pic_* 系列，封面+标题+底部 overlay（对照 8.83 item_card_rect）。 */
        private void bindCard(Holder h, HkItem item) {
            h.title.setText(titleSpan(item.getTitle()));
            String d = stripHtml(item.getDesc());
            if (h.overlay != null) {
                h.overlay.setText(d);
                h.overlay.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /** 图片：pic_1系列全宽16:9；pic_2半宽16:9；pic_3/pic_3_square按列宽正方形。 */
        private void bindPic(Holder h, HkItem item) {
            String ct = colTypeOf(item);
            ViewGroup.LayoutParams plp = h.cover.getLayoutParams();
            if (plp != null) {
                DisplayMetrics dm = h.itemView.getContext().getResources().getDisplayMetrics();
                int W = dm.widthPixels - dp(16) * 2;
                if (ct.startsWith("pic_3")) {
                    plp.height = (W - dp(12) * 2) / 3; // 3列正方形
                } else if (ct.startsWith("pic_2")) {
                    int itemW = (W - dp(12)) / 2;
                    plp.height = itemW * 9 / 16;
                } else {
                    plp.height = picH; // 16:9 全宽
                }
                h.cover.setLayoutParams(plp);
            }
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            h.cover.setContentDescription(stripHtml(item.getTitle()));
            setContentClick(h, item);
        }

        /** 横向图文：左图右文。 */
        private void bindMovie(Holder h, HkItem item) {
            if (h.title == null || h.desc == null || h.cover == null) return; // 防 ViewHolder 类型错配
            h.title.setText(titleSpan(item.getTitle()));
            String d = stripHtml(item.getDesc());
            h.desc.setText(d);
            h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /**
         * 竖海报行（D3）：对照 8.83 item_movie_one_col_vertical_pic(_blur)。
         * 全宽左 95x130dp 竖海报右文；blur=true 时背景加毛玻璃（8.83 的 blur 头图）。
         */
        private void bindMovieVertical(Holder h, HkItem item, boolean blur) {
            if (h.title == null || h.desc == null || h.cover == null) return;
            h.title.setText(titleSpan(item.getTitle()));
            String d = stripHtml(item.getDesc());
            h.desc.setText(d);
            h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            if (blur) {
                View bg = h.itemView.findViewById(R.id.iv_blur_bg);
                if (bg instanceof android.widget.ImageView) {
                    ImgUtil.load(item.getTitle(), item.getPic(), (android.widget.ImageView) bg, hkRuleUrl());
                    try {
                        com.xingchen.tv.theme.FrostedGlass.blurBackground(bg);
                    } catch (Throwable ignored) {
                    }
                }
            }
            setContentClick(h, item);
        }

        /**
         * 8.83 原版详情页头部（item_movie_one_col）：标题左 + 封面右（130×90dp，圆角 8dp）。
         * 标题 16sp 最多 2 行省略，描述最多 2 行底部对齐。点击走通用条目点击。
         */
        private void bindDetailHeader(Holder h, HkItem item) {
            if (h.title == null || h.cover == null) return;
            h.title.setText(titleSpan(item.getTitle()));
            String d = stripHtml(item.getDesc());
            if (h.desc != null) {
                h.desc.setText(d);
                h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /** 输入框行：hint 取标题；搜索键触发且有 url 时走点击处理。 */
        private void bindInput(Holder h, HkItem item) {
            h.input.setHint(stripHtml(item.getTitle()));
            h.input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    if (!TextUtils.isEmpty(item.getUrl())) {
                        // 官方语义（ArticleListFragment.clickItem）：input 条目把用户输入的文本
                        // 作为 input 全局变量注入后求值，而非 pageUrl。否则口令验证永远失败。
                        String key = h.input.getText() == null ? "" : h.input.getText().toString();
                        try {
                            router().getEngine().getJsRuntime().setNextInputOverride(key);
                        } catch (Throwable ignored) {
                        }
                        onContentItemClick(item);
                    }
                    return true;
                }
                return false;
            });
        }

        /** P2：x5_webview_single 内嵌网页（JRKAN直播这类网页版小程序），点击整行用外部 WebView 打开。 */
        private void bindWeb(Holder h, HkItem item) {
            String url = item.getUrl() == null ? "" : item.getUrl().trim();
            // url 可能带选择器后缀，只取纯 http 部分
            int sp = url.indexOf(' ');
            if (sp > 0) url = url.substring(0, sp).trim();
            int semi = url.indexOf(';');
            if (semi > 0) url = url.substring(0, semi).trim();
            int hash = url.indexOf('#');
            if (hash > 0) url = url.substring(0, hash).trim();
            if (url.startsWith("http") && h.web != null) {
                h.web.loadUrl(url);
            }
            final String target = url;
            h.itemView.setOnClickListener(v -> {
                if (target.startsWith("http")) showWebViewDialog(target);
            });
        }

        /**
         * 8.83：无 URL 的纯静态文本（如"使用前须知"）不可点击，无任何反馈。
         * 有 URL 的条目才绑点击（含空链接的异常情况由 onContentItemClick 处 toast）。
         * 点击时按 position 重取条目（8.83 clickItem src 2859 同款，防复用错位）；
         * 越界检查照抄 8.83 clickItem(src 2914)：position<0 或 >=size 直接 return。
         */
        private void setContentClick(Holder h, HkItem item) {
            String u = item == null || item.getUrl() == null ? "" : item.getUrl().trim();
            if (u.isEmpty()) {
                h.itemView.setOnClickListener(null);
                h.itemView.setOnLongClickListener(null);
                h.itemView.setClickable(false);
                h.itemView.setFocusable(false);
                return;
            }
            h.itemView.setClickable(true);
            h.itemView.setFocusable(true);
            h.itemView.setOnClickListener(v -> {
                try {
                    int pos = h.getBindingAdapterPosition();
                    if (pos < 0 || pos >= items.size() || hostRule() == null) return;
                    onContentItemClick(items.get(pos));
                } catch (Exception e) {
                    android.util.Log.e("HkPage", "setContentClick failed", e);
                }
            });
            // 8.83 ArticleListFragment 长按菜单（dealLongMenuClick）：复制链接/访问链接/下载资源等
            h.itemView.setOnLongClickListener(v -> {
                try {
                    int pos = h.getBindingAdapterPosition();
                    if (pos < 0 || pos >= items.size()) return true;
                    showContentLongMenu(items.get(pos));
                } catch (Exception e) {
                    android.util.Log.e("HkPage", "longClick failed", e);
                }
                return true;
            });
        }
    }
}
