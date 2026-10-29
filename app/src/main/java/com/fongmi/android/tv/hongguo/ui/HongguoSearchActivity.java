package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityHongguoSearchBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSearchBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSearchBookBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSearchPostBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSearchUserBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgAuthorHit;
import com.fongmi.android.tv.hongguo.model.HgAuthorWork;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgHotBoard;
import com.fongmi.android.tv.hongguo.model.HgHotItem;
import com.fongmi.android.tv.hongguo.model.HgHotSearch;
import com.fongmi.android.tv.hongguo.model.HgHotTopic;
import com.fongmi.android.tv.hongguo.model.HgSearchRow;
import com.fongmi.android.tv.hongguo.model.HgSearchTab;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.hongguo.model.HgSuggestion;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果搜索面：三态——落地/清空时显搜索历史（本地 HgStore，最近在前，
 * 块头带清除位）＋热搜面（plan/v1 search_input_page：猜你想搜行＋
 * 热搜榜逐板，历史段之后同列续排，有数据才显、取数失败静默不显，
 * 账 hongguo-hotsearch-dig-20261030）；输入中 300ms 防抖拉联想（search/suggest 已签名口，
 * word_type=2 的词条带剧 id 可直达进剧，其余回填再搜）；提交后走
 * search/tab 结果（offset 翻页，滑到底续拉）。
 * 2026-10-28 逐项重对补全：此前联想接口在客户端里是旧无签名死形、
 * 界面从没调用，历史与翻页同样缺失，整个搜索只有第一页结果。
 * 2026-10-28 整页形态对齐官方：页面底白、搜索栏照 cqh/c8m 原值、历史块
 * 照 as1（「搜索历史」＋右端清除位）、联想行照 b7z、结果改双列瀑布卡
 * 照 b7l 构成（详见 hongguo/search-page-align-20261028.md）。
 */
public class HongguoSearchActivity extends BaseActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, HongguoSearchActivity.class));
    }

    private static final int MODE_HISTORY = 0;
    private static final int MODE_SUGGEST = 1;
    private static final int MODE_RESULT = 2;

    private static final int TYPE_RESULT = 0;
    private static final int TYPE_TEXT = 1;
    private static final int TYPE_HEADER = 2;
    private static final int TYPE_SUG = 3;
    // 综合签按型分形（批三 C 卡 18）：用户/帖/小说三型整行卡
    private static final int TYPE_USER = 4;
    private static final int TYPE_POST = 5;
    private static final int TYPE_BOOK = 6;
    // 落地态热搜行（猜行/板行/话题行同布局，账 hongguo-hotsearch-dig §4.4）
    private static final int TYPE_HOT = 7;

    // 落地态段表行型（MODE_HISTORY 专用：历史段＋猜段＋板段扁平化）
    private static final int SEC_HISTORY_HEADER = 0;
    private static final int SEC_HISTORY_WORD = 1;
    private static final int SEC_HOT_HEADER = 2;
    private static final int SEC_HOT_ITEM = 3;
    private static final int SEC_HOT_TOPIC = 4;

    private ActivityHongguoSearchBinding binding;
    private HgFeedSession session;
    private LinearLayoutManager linearManager;
    private StaggeredGridLayoutManager gridManager;
    private int mode = MODE_HISTORY;
    private final List<HgSeriesItem> results = new ArrayList<>();
    /** 综合签（tabType＝11）的按型行表：服务端原序、多型同列（解析层
     * HgParser 定域收录）；剧集/视频签仍走 results 一型到底。 */
    private final List<HgSearchRow> rows = new ArrayList<>();
    private final List<HgSuggestion> suggestions = new ArrayList<>();
    private final List<String> history = new ArrayList<>();
    /** 落地态段表（MODE_HISTORY 的唯一数据源）：历史段（头＋词行，
     * 无历史整段不出）之后接热搜面猜段与逐板段，构成照回包原序。 */
    private final List<LandingRow> landingRows = new ArrayList<>();
    /** 热搜面整包（plan/v1 回包）；未拉到/空面为 null，落地态只显历史。 */
    private HgHotSearch hotSearch;
    private boolean hotLoaded;
    private int hotSeq;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSuggest;
    private int suggestSeq;
    private String currentKeyword = "";
    // 搜索结果页签（服务端 search_tabs 下发，视频面三页签）：当前页签与页签表
    private int searchTabType = 11;
    private java.util.List<HgSearchTab> searchTabs = new java.util.ArrayList<>();
    private boolean loadingMore;
    // 续拉共享态（全签一处，不分签）：hasMore/nextOffset 以回包签段的
    // 服务端下发为准（HgParser 已抬进 HgCellPage）；服务端未下发
    // nextOffset 时 nextOffset=-1，回落旧口径（整页≥20 条才续、offset＝
    // 已取剧条数），剧集/视频/综合签在存档回包下与旧口径逐值等价
    private boolean hasMore;
    private int nextOffset = -1;
    private final List<String> cues = new ArrayList<>();
    private int cueIndex;
    private final Runnable cueTicker = new Runnable() {
        @Override
        public void run() {
            rotateCueHint();
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoSearchBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        // 整页浅底（官方白底）后系统图标转深色，与官方浅面一致
        return true;
    }

    /** 结果态双列瀑布（StaggeredGrid）、其余态单列；网格时列表两侧各让 5dp 与卡 margin 拼成 10dp 边距。 */
    private void useGrid(boolean grid) {
        RecyclerView.LayoutManager want = grid ? gridManager : linearManager;
        if (binding.hgSearchList.getLayoutManager() != want) {
            binding.hgSearchList.setLayoutManager(want);
        }
        int side = grid ? Math.round(5 * getResources().getDisplayMetrics().density) : 0;
        if (binding.hgSearchList.getPaddingStart() != side) {
            binding.hgSearchList.setPadding(side, binding.hgSearchList.getPaddingTop(),
                    side, binding.hgSearchList.getPaddingBottom());
        }
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 顶部搜索行留距按状态栏真实高度走 insets，不写死数值（与刷剧页同口径）
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) binding.hgSearchTop.getLayoutParams();
            if (lp.topMargin != top) {
                lp.topMargin = top;
                binding.hgSearchTop.setLayoutParams(lp);
            }
            return insets;
        });
        session = HgFeedSession.get();
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public int getItemViewType(int position) {
                if (mode == MODE_RESULT) {
                    if (useRows()) {
                        switch (rows.get(position).kind) {
                            case HgSearchRow.KIND_USER:
                                return TYPE_USER;
                            case HgSearchRow.KIND_POST:
                                return TYPE_POST;
                            case HgSearchRow.KIND_BOOK:
                                return TYPE_BOOK;
                            default:
                                return TYPE_RESULT;
                        }
                    }
                    return TYPE_RESULT;
                }
                if (mode == MODE_SUGGEST) return TYPE_SUG;
                // 落地态按段表分发（历史头/热搜段头同 TYPE_HEADER，靠段型分清清除位）
                switch (landingRows.get(position).sec) {
                    case SEC_HISTORY_HEADER:
                    case SEC_HOT_HEADER:
                        return TYPE_HEADER;
                    case SEC_HOT_ITEM:
                    case SEC_HOT_TOPIC:
                        return TYPE_HOT;
                    default:
                        return TYPE_TEXT;
                }
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                LayoutInflater inflater = LayoutInflater.from(parent.getContext());
                if (viewType == TYPE_RESULT) {
                    return new ResultHolder(ItemHongguoSearchBinding.inflate(inflater, parent, false));
                }
                if (viewType == TYPE_USER) {
                    return new UserHolder(ItemHongguoSearchUserBinding.inflate(inflater, parent, false));
                }
                if (viewType == TYPE_POST) {
                    return new PostHolder(ItemHongguoSearchPostBinding.inflate(inflater, parent, false));
                }
                if (viewType == TYPE_BOOK) {
                    return new BookHolder(ItemHongguoSearchBookBinding.inflate(inflater, parent, false));
                }
                if (viewType == TYPE_HEADER) {
                    return new HeaderHolder(inflater.inflate(R.layout.item_hongguo_search_header, parent, false));
                }
                if (viewType == TYPE_SUG) {
                    return new SugHolder(inflater.inflate(R.layout.item_hongguo_search_sug, parent, false));
                }
                if (viewType == TYPE_HOT) {
                    return new HotHolder(inflater.inflate(R.layout.item_hongguo_search_hot, parent, false));
                }
                TextView tv = (TextView) inflater.inflate(R.layout.item_hongguo_search_text, parent, false);
                return new TextHolder(tv);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (mode == MODE_RESULT) {
                    if (useRows() && rows.get(position).kind != HgSearchRow.KIND_SERIES) {
                        bindTypedRow(holder, rows.get(position));
                        return;
                    }
                    HgSeriesItem item = useRows() ? rows.get(position).series : results.get(position);
                    ItemHongguoSearchBinding b = ((ResultHolder) holder).binding;
                    ImgUtil.load(item.getTitle() == null ? "" : item.getTitle(), item.getCoverUrl(), b.hgSCover);
                    b.hgSTitle.setText(item.getTitle() == null ? "" : item.getTitle());
                    // 角标（tag_info）压封面顶部渐变带，有才显（官方 b7l TagView 缺省 gone 同口径）
                    if (item.getBadge() != null && !item.getBadge().isEmpty()) {
                        b.hgSBadge.setVisibility(View.VISIBLE);
                        b.hgSBadge.setText(item.getBadge());
                    } else {
                        b.hgSBadge.setVisibility(View.GONE);
                    }
                    // 封面左下文案位（官方双列卡 play_cnt_desc 同位）：优先热度文案，其次全X集；
                    // UGC 单集条两者皆无、官方同位是「♥N次赞」（digged_count），照此兜底
                    String overlay = item.getRecText();
                    if ((overlay == null || overlay.isEmpty()) && item.getEpisodeCount() > 0) {
                        overlay = "全" + item.getEpisodeCount() + "集";
                    }
                    if ((overlay == null || overlay.isEmpty()) && item.getDiggCount() > 0) {
                        overlay = "♥" + formatCount(item.getDiggCount()) + "次赞";
                    }
                    if (overlay != null && !overlay.isEmpty()) {
                        b.hgSOverlay.setVisibility(View.VISIBLE);
                        b.hgSOverlay.setText(overlay);
                    } else {
                        b.hgSOverlay.setVisibility(View.GONE);
                    }
                    // 作者行（12dp 圆头像＋名字）：搜索 cell 带作者才显（官方 b7l 作者位同构）；
                    // 点头像/名进作者页（与流页同一去处，uid 有才挂——没 uid 不挂假入口）
                    if (item.getAuthorName() != null && !item.getAuthorName().isEmpty()) {
                        b.hgSAuthorAvatar.setVisibility(View.VISIBLE);
                        b.hgSAuthorRing.setVisibility(View.VISIBLE);
                        b.hgSAuthorName.setVisibility(View.VISIBLE);
                        ImgUtil.load(item.getAuthorName(), item.getAuthorAvatar(), b.hgSAuthorAvatar);
                        b.hgSAuthorName.setText(item.getAuthorName());
                        if (item.getAuthorUid() != null && !item.getAuthorUid().isEmpty()) {
                            android.view.View.OnClickListener goAuthor = v ->
                                    HongguoAuthorActivity.start(HongguoSearchActivity.this,
                                            item.getAuthorUid(), item.getAuthorName(),
                                            item.getAuthorAvatar(), item.getAuthorDesc());
                            b.hgSAuthorAvatar.setOnClickListener(goAuthor);
                            b.hgSAuthorName.setOnClickListener(goAuthor);
                        } else {
                            b.hgSAuthorAvatar.setOnClickListener(null);
                            b.hgSAuthorName.setOnClickListener(null);
                        }
                    } else {
                        b.hgSAuthorAvatar.setVisibility(View.GONE);
                        b.hgSAuthorRing.setVisibility(View.GONE);
                        b.hgSAuthorName.setVisibility(View.GONE);
                    }
                    // 点赞数位（作者行右端）：有 digged_count 才显
                    if (item.getDiggCount() > 0) {
                        b.hgSDigg.setVisibility(View.VISIBLE);
                        b.hgSDigg.setText("♡" + formatCount(item.getDiggCount()));
                    } else {
                        b.hgSDigg.setVisibility(View.GONE);
                    }
                    b.getRoot().setOnClickListener(v -> onResultClick(item));
                    return;
                }
                int type = getItemViewType(position);
                if (type == TYPE_HEADER) {
                    HeaderHolder hh = (HeaderHolder) holder;
                    LandingRow r = landingRows.get(position);
                    hh.title.setText(r.headerText == null ? "" : r.headerText);
                    // 清除位只属历史头；热搜段头同布局复用、此位隐去且不挂点击
                    boolean hist = r.sec == SEC_HISTORY_HEADER;
                    hh.clear.setVisibility(hist ? View.VISIBLE : View.GONE);
                    hh.clear.setOnClickListener(hist ? v -> {
                        HgStore.clearSearchHistory();
                        showHistory();
                    } : null);
                } else if (type == TYPE_HOT) {
                    bindHotRow((HotHolder) holder, landingRows.get(position));
                } else if (type == TYPE_SUG) {
                    HgSuggestion s = suggestions.get(position);
                    SugHolder h = (SugHolder) holder;
                    h.text.setText(s.getText());
                    h.itemView.setOnClickListener(v -> onSuggestionClick(s));
                    // 右端箭头＝只回填不搜（官方 SugItemHolder G2 口径）
                    h.arrow.setOnClickListener(v -> {
                        binding.hgSearchInput.setText(s.getText());
                        binding.hgSearchInput.setSelection(s.getText().length());
                    });
                } else {
                    TextView tv = ((TextHolder) holder).text;
                    String word = landingRows.get(position).word;
                    tv.setText(word);
                    tv.setTextColor(0xFF303030);
                    tv.setOnClickListener(v -> {
                        binding.hgSearchInput.setText(word);
                        binding.hgSearchInput.setSelection(word.length());
                        doSearch();
                    });
                }
            }

            @Override
            public int getItemCount() {
                if (mode == MODE_RESULT) return useRows() ? rows.size() : results.size();
                if (mode == MODE_SUGGEST) return suggestions.size();
                return landingRows.size();
            }
        };
        linearManager = new LinearLayoutManager(this);
        gridManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        binding.hgSearchList.setLayoutManager(linearManager);
        binding.hgSearchList.setAdapter(adapter);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            binding.hgSearchInput.setTextCursorDrawable(R.drawable.bg_hg_search_cursor);
        }
        binding.hgSearchList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (mode != MODE_RESULT || dy <= 0) return;
                int last = -1;
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                if (lm instanceof StaggeredGridLayoutManager) {
                    int[] pos = ((StaggeredGridLayoutManager) lm).findLastVisibleItemPositions(null);
                    for (int p : pos) last = Math.max(last, p);
                } else if (lm instanceof LinearLayoutManager) {
                    last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                }
                int total = useRows() ? rows.size() : results.size();
                if (last >= total - 4) loadMore();
            }
        });
        showHistory();
        loadCues();
    }

    /** 提示词：拉一次 cue 词表，进框占位轮播（4 秒一条，只在输入为空时转）。 */
    private void loadCues() {
        session.client.searchCues(new HgCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> list) {
                runOnUiThread(() -> {
                    if (list == null || list.isEmpty()) return;
                    cues.clear();
                    cues.addAll(list);
                    cueIndex = 0;
                    rotateCueHint();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 提示词拉不到不打扰：占位保持布局自带文案
            }
        });
    }

    private void rotateCueHint() {
        mainHandler.removeCallbacks(cueTicker);
        if (isFinishing() || isDestroyed() || cues.isEmpty()) return;
        Editable cur = binding.hgSearchInput.getText();
        if (cur == null || cur.toString().isEmpty()) {
            binding.hgSearchInput.setHint(cues.get(cueIndex % cues.size()));
            cueIndex++;
        }
        mainHandler.postDelayed(cueTicker, 4000);
    }

    @Override
    protected void initEvent() {
        binding.hgSearchGo.setOnClickListener(v -> doSearch());
        // 框内清除钮（官方 c8m hgs 同位）：有字才显，点按清空回落地态
        binding.hgSearchClear.setOnClickListener(v -> binding.hgSearchInput.setText(""));
        binding.hgSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        binding.hgSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.hgSearchClear.setVisibility(
                        s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
                if (pendingSuggest != null) mainHandler.removeCallbacks(pendingSuggest);
                String text = s == null ? "" : s.toString().trim();
                if (text.isEmpty()) {
                    suggestSeq++;
                    showHistory();
                    return;
                }
                pendingSuggest = () -> fetchSuggest(text);
                mainHandler.postDelayed(pendingSuggest, 300);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(cueTicker);
        if (pendingSuggest != null) mainHandler.removeCallbacks(pendingSuggest);
        super.onDestroy();
    }

    private void showHistory() {
        binding.hgSearchTabs.setVisibility(View.GONE);
        mode = MODE_HISTORY;
        useGrid(false);
        history.clear();
        history.addAll(HgStore.searchHistory());
        binding.hgSearchState.setVisibility(View.GONE);
        rebuildLandingRows();
        adapter.notifyDataSetChanged();
        loadHotSearch();
    }

    /**
     * 落地态段表组装（构成照账 §2/§4.4，同一列表线性单列、序照回包）：
     * 历史头＋历史词行（无历史整段不出，维持原口径）→ 猜你想搜头
     * （文案取回包 cell_name 原文）＋猜行 → 逐板（板头取 cell_name
     * 原文＋板行／话题行）。热搜面没拉到/空面时段表只有历史段。
     */
    private void rebuildLandingRows() {
        landingRows.clear();
        if (!history.isEmpty()) {
            landingRows.add(LandingRow.historyHeader());
            for (String w : history) landingRows.add(LandingRow.historyWord(w));
        }
        if (hotSearch == null) return;
        if (!hotSearch.getGuesses().isEmpty()) {
            String name = hotSearch.getGuessName();
            landingRows.add(LandingRow.hotHeader(
                    name == null || name.isEmpty() ? "猜你想搜" : name));
            for (HgHotItem it : hotSearch.getGuesses()) {
                landingRows.add(LandingRow.hotItem(it, false));
            }
        }
        for (HgHotBoard board : hotSearch.getBoards()) {
            if (board.getName() != null && !board.getName().isEmpty()) {
                landingRows.add(LandingRow.hotHeader(board.getName()));
            }
            for (HgHotItem it : board.getItems()) {
                landingRows.add(LandingRow.hotItem(it, true));
            }
            for (HgHotTopic t : board.getTopics()) {
                landingRows.add(LandingRow.hotTopic(t));
            }
        }
    }

    /**
     * 热搜面拉取（进页/清空输入回到落地态触发，本页只拉一次）：成功且
     * 非空才并进段表；失败或两面皆空静默只显历史（与 fetchSuggest
     * 失败「不打扰」同口径，不摆假行、不弹错）。失败不置已拉旗，
     * 下次回到落地态再试。
     */
    private void loadHotSearch() {
        if (hotLoaded) return;
        final int seq = ++hotSeq;
        session.client.fetchHotSearch(new HgCallback<HgHotSearch>() {
            @Override
            public void onSuccess(HgHotSearch page) {
                hotLoaded = true;
                runOnUiThread(() -> {
                    if (seq != hotSeq || page == null || page.isEmpty()) return;
                    hotSearch = page;
                    if (mode != MODE_HISTORY) return;
                    rebuildLandingRows();
                    adapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 静默：落地态保持只有历史
            }
        });
    }

    /**
     * 热搜行绑卡（照官方 bi0 构成，账 §4.4）：序号=position（仅板行
     * 显，猜行/话题行隐序号位）、标题=title、角标=tag_info.text（有才
     * 显，底色取 tag_info.bg_color[0]）、副行=card_tips 为空回落
     * rec_text（§4.6-1 适配口径：card_tips 是榜单自身数值、语义近
     * 副行，非定版，待真机对照官方图再校）；话题行副行装 heat。
     * 猜行专属布局未抠到，与板行同布局隐序号位承接（§4.6-2 适配）。
     */
    private void bindHotRow(HotHolder h, LandingRow r) {
        if (r.sec == SEC_HOT_TOPIC) {
            HgHotTopic t = r.topic;
            h.pos.setVisibility(View.INVISIBLE);
            h.pos.setText("");
            h.title.setText(t.getTitle() == null ? "" : t.getTitle());
            h.tag.setVisibility(View.GONE);
            setHotSub(h, t.getHeat());
            h.itemView.setOnClickListener(v -> onHotTopicClick(t));
            return;
        }
        HgHotItem it = r.item;
        if (r.showPos) {
            h.pos.setVisibility(View.VISIBLE);
            h.pos.setText(String.valueOf(it.getPosition()));
        } else {
            h.pos.setVisibility(View.INVISIBLE);
            h.pos.setText("");
        }
        h.title.setText(it.getTitle() == null ? "" : it.getTitle());
        if (it.getTag() != null && !it.getTag().isEmpty()) {
            h.tag.setVisibility(View.VISIBLE);
            h.tag.setText(it.getTag());
            android.graphics.drawable.Drawable bg = h.tag.getBackground();
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                android.graphics.drawable.GradientDrawable gd =
                        (android.graphics.drawable.GradientDrawable) bg.mutate();
                gd.setColor(parseTagColor(it.getTagBg()));
            }
        } else {
            h.tag.setVisibility(View.GONE);
        }
        String sub = it.getSearchScore();
        if (sub == null || sub.isEmpty()) sub = it.getHeat();
        setHotSub(h, sub);
        h.itemView.setOnClickListener(v -> onHotItemClick(it));
    }

    private void setHotSub(HotHolder h, String text) {
        if (text != null && !text.isEmpty()) {
            h.sub.setVisibility(View.VISIBLE);
            h.sub.setText(text);
        } else {
            h.sub.setVisibility(View.GONE);
        }
    }

    /** 角标底色解析（tag_info.bg_color 原文如「#00AE83」）；解不出回落布局缺省同值。 */
    private static int parseTagColor(String raw) {
        if (raw != null && !raw.isEmpty()) {
            try {
                return android.graphics.Color.parseColor(raw);
            } catch (Exception ignored) {
            }
        }
        return 0xFF00AE83;
    }

    /**
     * 热搜作品行点击分发（复用现行落点，账 §4.5）：组 HgSeriesItem 走
     * 现行 onResultClick（其余型→集流 startSeries）。一个新增分支：
     * content_type＝1003 合集行——热搜行无 album_info/album_id_str，
     * 以 series_id 直作 albumId 进合集详情页（ohg 模型里该行唯一身份
     * 就是 series_id；此分支属推定、列首验项 §4.6-3，验不过回落
     * startSeries 并记账）。
     */
    private void onHotItemClick(HgHotItem item) {
        if (item == null || item.getSeriesId() == null || item.getSeriesId().isEmpty()) return;
        if (item.getContentType() == 1003) {
            HongguoAlbumActivity.start(this, item.getSeriesId(), item.getTitle(), item.getCoverUrl());
            return;
        }
        onResultClick(HgSeriesItem.of(item.getSeriesId(), item.getTitle(), item.getCoverUrl(),
                null, item.getEpisodeCount(), false, false, null));
    }

    /** 话题行点击：以 query 发起一次搜索（与历史词点击同形，doSearch 自带记历史）；query 解不出时用标题兜底。 */
    private void onHotTopicClick(HgHotTopic topic) {
        String q = topic.getQuery();
        if (q == null || q.isEmpty()) q = topic.getTitle();
        if (q == null || q.isEmpty()) return;
        binding.hgSearchInput.setText(q);
        binding.hgSearchInput.setSelection(q.length());
        doSearch();
    }

    private void fetchSuggest(String prefix) {
        int seq = ++suggestSeq;
        session.client.searchSuggest(prefix, new HgCallback<List<HgSuggestion>>() {
            @Override
            public void onSuccess(List<HgSuggestion> list) {
                runOnUiThread(() -> {
                    if (seq != suggestSeq) return;
                    Editable cur = binding.hgSearchInput.getText();
                    if (cur == null || !prefix.equals(cur.toString().trim())) return;
                    suggestions.clear();
                    if (list != null) suggestions.addAll(list);
                    if (suggestions.isEmpty()) return;
                    binding.hgSearchTabs.setVisibility(View.GONE);
                    mode = MODE_SUGGEST;
                    useGrid(false);
                    binding.hgSearchState.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 联想失败不打扰：保持当前列表（历史/已打字状态），提交搜索仍可用
            }
        });
    }

    /**
     * 综合/讨论/用户/小说签走按型行表（讨论签只有帖行、用户签只有用户行、
     * 小说签只有书行，解析层定域收录同口）；剧集/视频签的回包只有剧行、
     * 仍走 results。
     */
    private boolean useRows() {
        return searchTabType == 11 || searchTabType == 31 || searchTabType == 27
                || searchTabType == 1;
    }

    /**
     * 综合签非剧行的分型绑卡（批三 C 卡 18）：三型都是整行卡（瀑布里
     * fullSpan）。落点——用户卡进作者页、帖卡进帖子页（料由回包自带组装、
     * 与作者页帖子同一承接）；小说卡进小说详情页（2026-10-30 落点打通，
     * 见 openNovel）。
     */
    private void bindTypedRow(RecyclerView.ViewHolder holder, HgSearchRow row) {
        if (holder.itemView.getLayoutParams()
                instanceof StaggeredGridLayoutManager.LayoutParams) {
            ((StaggeredGridLayoutManager.LayoutParams) holder.itemView.getLayoutParams())
                    .setFullSpan(true);
        }
        if (holder instanceof UserHolder) {
            ItemHongguoSearchUserBinding b = ((UserHolder) holder).binding;
            HgAuthorHit hit = row.user;
            ImgUtil.loadCircle(hit.name, hit.avatar, b.hgSuAvatar);
            b.hgSuName.setText(hit.name);
            b.hgSuSub.setText(hit.workCount > 0 ? "作者 · 作品" + hit.workCount : "作者");
            b.getRoot().setOnClickListener(v ->
                    HongguoAuthorActivity.start(HongguoSearchActivity.this,
                            hit.uid, hit.name, hit.avatar, null));
            // 关注钮（批四卡 23）：复用作者关注统一口（作者页同链）——
            // 状态读 HgStore 作者集镜像、文案随态翻转；本人不挂钮
            boolean self = hit.uid != null && hit.uid.equals(HgStore.profileUid());
            b.hgSuFollow.setVisibility(self ? View.GONE : View.VISIBLE);
            paintUserFollow(b, hit.uid);
            b.hgSuFollow.setOnClickListener(v -> onUserFollowClicked(b, hit.uid));
            return;
        }
        if (holder instanceof PostHolder) {
            ItemHongguoSearchPostBinding b = ((PostHolder) holder).binding;
            HgAuthorWork work = row.post;
            ImgUtil.load(work.title, work.cover, b.hgSpCover);
            b.hgSpTitle.setText(work.title);
            String text = work.postBody == null ? "" : work.postBody.text;
            b.hgSpText.setText(text);
            b.hgSpText.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            b.hgSpAuthor.setText(work.authorName == null ? "" : work.authorName);
            StringBuilder counts = new StringBuilder();
            if (work.diggCount > 0) counts.append("♡").append(formatCount(work.diggCount));
            long replies = work.postBody == null ? 0 : work.postBody.commentCount;
            if (replies > 0) {
                if (counts.length() > 0) counts.append("  ");
                counts.append("评论").append(formatCount(replies));
            }
            b.hgSpCounts.setText(counts.toString());
            b.getRoot().setOnClickListener(v ->
                    HongguoPostActivity.start(HongguoSearchActivity.this, work, row.postAuthorUid));
            return;
        }
        if (holder instanceof BookHolder) {
            ItemHongguoSearchBookBinding b = ((BookHolder) holder).binding;
            ImgUtil.load(row.bookTitle, row.bookCover, b.hgSbCover);
            b.hgSbTitle.setText(row.bookTitle);
            b.hgSbAuthor.setText(row.bookAuthor);
            b.hgSbAuthor.setVisibility(
                    row.bookAuthor.isEmpty() ? View.GONE : View.VISIBLE);
            b.hgSbAbstract.setText(row.bookAbstract);
            b.hgSbAbstract.setVisibility(
                    row.bookAbstract.isEmpty() ? View.GONE : View.VISIBLE);
            b.getRoot().setOnClickListener(v -> openNovel(row));
        }
    }

    /**
     * 小说卡落点（2026-10-30 打通）：进小说详情页 HongguoNovelDetailActivity
     * （mobile 源集的红果页，主源集按类名字符串拉起——横屏实例 HongguoLandEntry
     * 同法；详情页在红果包内、朋友版整套剥离）。类不在（剥离版/异味构建）时
     * 才回落诚实提示，不假跳。
     */
    private void openNovel(HgSearchRow row) {
        if (row.bookId == null || row.bookId.isEmpty()) {
            android.widget.Toast.makeText(this,
                    "这本书的信息不完整，打不开", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(this,
                    "com.fongmi.android.tv.hongguo.ui.HongguoNovelDetailActivity");
            intent.putExtra("hg_novel_book_id", row.bookId);
            intent.putExtra("hg_novel_title", row.bookTitle);
            startActivity(intent);
        } catch (Throwable e) {
            android.widget.Toast.makeText(this,
                    "红果里暂不能打开小说", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 用户卡关注钮两态（读作者集镜像）：未关注橙渐变白字、已关注浅灰深字。 */
    private void paintUserFollow(ItemHongguoSearchUserBinding b, String uid) {
        boolean followed = uid != null && HgStore.isAuthorFollowed(uid);
        b.hgSuFollow.setText(followed ? "已关注" : "关注");
        b.hgSuFollow.setBackgroundResource(followed
                ? R.drawable.bg_hg_followed_btn : R.drawable.bg_hg_follow_btn);
        b.hgSuFollow.setTextColor(followed ? 0xFF333333 : 0xFFFFFFFF);
    }

    /**
     * 用户卡关注/取关（批四卡 23）：与作者页同一条链——未登录先拉壳子
     * 统一登录、不落状态；乐观先行写作者集镜像＋真口（fqClient
     * doFollowAction）收口，失败回滚镜像并如实提示。回包
     * celebrity_user_list 不带关系态字段（parseUserTab 字段集实证），
     * 钮态初值只能读本地镜像——与作者页资料回源前的初值口径一致，
     * 差记批四账残项。
     */
    private void onUserFollowClicked(ItemHongguoSearchUserBinding b, String uid) {
        if (uid == null || uid.isEmpty()) return;
        if (session.interactions.requiresLogin()) {
            boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
            if (!delivered) {
                android.widget.Toast.makeText(this, "登录界面暂时打不开，稍后再试",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        boolean follow = !HgStore.isAuthorFollowed(uid);
        HgStore.setAuthorFollowed(uid, follow);
        paintUserFollow(b, uid);
        session.fqClient.doFollowAction(follow, uid, new HgCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    HgStore.setAuthorFollowed(uid, !follow);
                    paintUserFollow(b, uid);
                    android.widget.Toast.makeText(HongguoSearchActivity.this,
                            follow ? "关注没成功，再试试" : "取关没成功，再试试",
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 计数文案：万以下原数，万起缩「X.X万」（与 rec_text 的万制文风一致）。 */
    private static String formatCount(long n) {
        if (n < 10000) return String.valueOf(n);
        long w = n / 1000;
        String s = (w / 10) + "." + (w % 10);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "万";
    }

    /**
     * 搜索结果点击分发：普通剧/UGC 单集直进集流；合集条（content_type
     * 1003、身份是 album id）进合集详情页——拿 album id 直当 series id
     * 拉详情必空（账 hongguo-search-parity）。批四起落点改独立合集
     * 详情页 HongguoAlbumActivity（头部＋集列表＋点集起播），与流页
     * 合集条同一落点；此前拉名单弹系列单的承接路退役（批一残项 2）。
     */
    private void onResultClick(HgSeriesItem item) {
        String albumId = item.getAlbumId();
        // 合集判别只读解析入口定下的 kind（批一三型分家），不再回头比
        // content_type 原值；kind＝ALBUM 与原 1003 判别同物（解析定型
        // 口径见 HgParser.kindOf），行为不变
        if (item.kind != com.fongmi.android.tv.hongguo.model.HgItemKind.ALBUM
                || albumId == null || albumId.isEmpty()) {
            HongguoFeedActivity.startSeries(this, item.getSeriesId(), item.getTitle(), item.getCoverUrl());
            return;
        }
        HongguoAlbumActivity.start(this, albumId, item.getTitle(), item.getCoverUrl());
    }

    private void onSuggestionClick(HgSuggestion s) {
        if (s.isSeriesLink()) {
            HgStore.addSearchHistory(s.getText());
            HongguoFeedActivity.startSeries(this, s.getKeyword(), s.getText(), null);
            return;
        }
        binding.hgSearchInput.setText(s.getText());
        binding.hgSearchInput.setSelection(s.getText().length());
        doSearch();
    }

    private void doSearch() {
        String keyword = binding.hgSearchInput.getText() == null
                ? "" : binding.hgSearchInput.getText().toString().trim();
        if (keyword.isEmpty()) return;
        if (pendingSuggest != null) mainHandler.removeCallbacks(pendingSuggest);
        suggestSeq++;
        HgStore.addSearchHistory(keyword);
        currentKeyword = keyword;
        mode = MODE_RESULT;
        searchTabType = 11;
        searchTabs = new java.util.ArrayList<>();
        binding.hgSearchTabs.setVisibility(View.GONE);
        loadSearchFirstPage();
    }

    /** 回包落续拉共享态：服务端下发了 next_offset 就全听服务端的
     * （has_more 定触发、next_offset 定下页 offset）；没下发才回落旧口径。 */
    private void applyPaging(HgCellPage page) {
        String no = page.getNextOffset();
        if (no != null) {
            try {
                nextOffset = Integer.parseInt(no.trim());
            } catch (NumberFormatException e) {
                nextOffset = -1;
            }
            hasMore = page.isHasMore() && nextOffset >= 0;
        } else {
            nextOffset = -1;
            hasMore = page.getItems() != null && page.getItems().size() >= 20;
        }
    }

    /** 结果第一页（提交搜索与换页签共用）：按当前 searchTabType 重查。 */
    private void loadSearchFirstPage() {
        loadingMore = false;
        hasMore = false;
        nextOffset = -1;
        useGrid(true);
        results.clear();
        rows.clear();
        adapter.notifyDataSetChanged();
        binding.hgSearchState.setVisibility(View.VISIBLE);
        binding.hgSearchState.setText("搜索中…");
        session.client.searchTab(currentKeyword, 0, searchTabType, new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                runOnUiThread(() -> {
                    if (mode != MODE_RESULT) return;
                    results.clear();
                    if (page.getItems() != null) results.addAll(page.getItems());
                    rows.clear();
                    if (page.getSearchRows() != null) rows.addAll(page.getSearchRows());
                    applyPaging(page);
                    if (page.getTabs() != null && !page.getTabs().isEmpty()) {
                        searchTabs = page.getTabs();
                        renderSearchTabs();
                    }
                    adapter.notifyDataSetChanged();
                    if (useRows() ? rows.isEmpty() : results.isEmpty()) {
                        binding.hgSearchState.setVisibility(View.VISIBLE);
                        // 空态文案按签分化（官方 u2.java 实证）：讨论签走
                        // Community 分支「没有找到相关内容」，其余沿
                        // 短剧类分支「没有找到相关短剧」；小说签专属文案
                        // 未抠到，借非短剧的通用分支「没有找到相关内容」
                        // （书不是短剧，不许套短剧文案）
                        binding.hgSearchState.setText(searchTabType == 31
                                || searchTabType == 1
                                ? "没有找到相关内容" : "没有找到相关短剧");
                    } else {
                        binding.hgSearchState.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    binding.hgSearchState.setVisibility(View.VISIBLE);
                    binding.hgSearchState.setText("搜索失败，稍后再试");
                });
            }
        });
    }

    /** 页签排：服务端下发几页签摆几页签（不足两页签不摆），选中态白字加粗。 */
    private void renderSearchTabs() {
        android.widget.LinearLayout strip = binding.hgSearchTabs;
        strip.removeAllViews();
        if (searchTabs.size() < 2) {
            strip.setVisibility(View.GONE);
            return;
        }
        strip.setVisibility(View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        for (HgSearchTab tab : searchTabs) {
            TextView tv = new TextView(this);
            tv.setText(tab.title);
            tv.setTextSize(15);
            boolean on = tab.type == searchTabType;
            // 页签字色随整页浅底翻转（官方页签条材料值未抠到，此为适配值非真值）
            tv.setTextColor(on ? 0xFF000000 : 0x99000000);
            tv.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setPadding(Math.round(12 * density), Math.round(8 * density),
                    Math.round(12 * density), Math.round(8 * density));
            // 页签按压反馈走系统原生水波纹（质感层；官方页签条材料未抠到，不自创选中指示器）
            android.util.TypedValue ripple = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                tv.setBackgroundResource(ripple.resourceId);
            }
            tv.setOnClickListener(v -> switchSearchTab(tab.type));
            strip.addView(tv);
        }
    }

    private void switchSearchTab(int type) {
        if (type == searchTabType || mode != MODE_RESULT) return;
        searchTabType = type;
        renderSearchTabs();
        loadSearchFirstPage();
    }

    /**
     * 结果翻页（全签共享）：offset＝服务端下发的 next_offset（回落时为
     * 已取剧条数）；has_more 说停就停，本页零新增或游标未推进同样收口
     * 防空转重拉；失败安静停（已显结果不丢）。
     */
    private void loadMore() {
        if (loadingMore || !hasMore || currentKeyword.isEmpty()) return;
        loadingMore = true;
        final int reqOffset = nextOffset >= 0 ? nextOffset : results.size();
        session.client.searchTab(currentKeyword, reqOffset, searchTabType, new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                runOnUiThread(() -> {
                    loadingMore = false;
                    if (mode != MODE_RESULT) return;
                    List<HgSeriesItem> more = page.getItems();
                    List<HgSearchRow> moreRows = page.getSearchRows();
                    int appended = 0;
                    if (useRows() && moreRows != null && !moreRows.isEmpty()) {
                        // 行表签翻页按行表续（剧子集仍随 results 累积）
                        int at = rows.size();
                        if (more != null) results.addAll(more);
                        rows.addAll(moreRows);
                        appended = moreRows.size();
                        adapter.notifyItemRangeInserted(at, moreRows.size());
                    } else if (more != null && !more.isEmpty()) {
                        int at = results.size();
                        results.addAll(more);
                        appended = more.size();
                        if (!useRows()) adapter.notifyItemRangeInserted(at, more.size());
                    }
                    applyPaging(page);
                    if (appended == 0 || (nextOffset >= 0 && nextOffset <= reqOffset)) {
                        hasMore = false;
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> loadingMore = false);
            }
        });
    }

    private static final class ResultHolder extends RecyclerView.ViewHolder {
        final ItemHongguoSearchBinding binding;

        ResultHolder(ItemHongguoSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final class UserHolder extends RecyclerView.ViewHolder {
        final ItemHongguoSearchUserBinding binding;

        UserHolder(ItemHongguoSearchUserBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final class PostHolder extends RecyclerView.ViewHolder {
        final ItemHongguoSearchPostBinding binding;

        PostHolder(ItemHongguoSearchPostBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final class BookHolder extends RecyclerView.ViewHolder {
        final ItemHongguoSearchBookBinding binding;

        BookHolder(ItemHongguoSearchBookBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final class TextHolder extends RecyclerView.ViewHolder {
        final TextView text;

        TextHolder(TextView text) {
            super(text);
            this.text = text;
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final View clear;
        final TextView title;

        HeaderHolder(View root) {
            super(root);
            this.clear = root.findViewById(R.id.hg_hist_clear);
            this.title = root.findViewById(R.id.hg_section_title);
        }
    }

    private static final class HotHolder extends RecyclerView.ViewHolder {
        final TextView pos;
        final TextView title;
        final TextView tag;
        final TextView sub;

        HotHolder(View root) {
            super(root);
            this.pos = root.findViewById(R.id.hg_hot_pos);
            this.title = root.findViewById(R.id.hg_hot_title);
            this.tag = root.findViewById(R.id.hg_hot_tag);
            this.sub = root.findViewById(R.id.hg_hot_sub);
        }
    }

    /** 落地态段表一行（段型＋该行料，见 rebuildLandingRows）。 */
    private static final class LandingRow {
        final int sec;
        final String headerText;
        final String word;
        final HgHotItem item;
        final HgHotTopic topic;
        final boolean showPos;

        private LandingRow(int sec, String headerText, String word,
                           HgHotItem item, HgHotTopic topic, boolean showPos) {
            this.sec = sec;
            this.headerText = headerText;
            this.word = word;
            this.item = item;
            this.topic = topic;
            this.showPos = showPos;
        }

        static LandingRow historyHeader() {
            return new LandingRow(SEC_HISTORY_HEADER, "搜索历史", null, null, null, false);
        }

        static LandingRow historyWord(String word) {
            return new LandingRow(SEC_HISTORY_WORD, null, word, null, null, false);
        }

        static LandingRow hotHeader(String name) {
            return new LandingRow(SEC_HOT_HEADER, name, null, null, null, false);
        }

        static LandingRow hotItem(HgHotItem item, boolean showPos) {
            return new LandingRow(SEC_HOT_ITEM, null, null, item, null, showPos);
        }

        static LandingRow hotTopic(HgHotTopic topic) {
            return new LandingRow(SEC_HOT_TOPIC, null, null, null, topic, false);
        }
    }

    private static final class SugHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final View arrow;

        SugHolder(View root) {
            super(root);
            this.text = root.findViewById(R.id.hg_sug_text);
            this.arrow = root.findViewById(R.id.hg_sug_arrow);
        }
    }
}
