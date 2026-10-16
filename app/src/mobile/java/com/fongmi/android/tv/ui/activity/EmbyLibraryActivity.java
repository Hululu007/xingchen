package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityEmbyLibraryBinding;
import com.fongmi.android.tv.emby.EmbyApi;
import com.fongmi.android.tv.emby.EmbyItem;
import com.fongmi.android.tv.emby.EmbyNavigator;
import com.fongmi.android.tv.emby.EmbyStore.EmbyServer;
import com.fongmi.android.tv.emby.EmbyStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Emby 栏目"全部"全量列表页：专属首页每个栏目头点开进这里。
 * view 模式走服务端 StartIndex/Limit 全量分页；resume/latest/nextup 为服务端 bounded 列表，一次拉完。
 */
public class EmbyLibraryActivity extends BaseActivity {

    public static final String MODE_VIEW = "view";
    public static final String MODE_RESUME = "resume";
    public static final String MODE_LATEST = "latest";
    public static final String MODE_NEXTUP = "nextup";
    /** 演员作品模式：复用本页展示，parentId 里放 personId，标题=演员名。 */
    public static final String MODE_PERSON = "person";
    /** 收藏模式：favoriteItems() 取数，标题=我的收藏。 */
    public static final String MODE_FAV = "fav";

    private static final int PAGE = 30;

    /**
     * 进演员作品：start(context, serverId, MODE_PERSON, personId, actorName)。
     */
    public static void start(Context context, String serverId, String mode, String parentId, String title) {
        Intent it = new Intent(context, EmbyLibraryActivity.class);
        it.putExtra("serverId", serverId);
        it.putExtra("mode", mode);
        it.putExtra("parentId", parentId);
        it.putExtra("title", title);
        if (!(context instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(it);
    }

    private ActivityEmbyLibraryBinding binding;
    private final List<EmbyItem> items = new ArrayList<>();
    private Adapter adapter;
    private EmbyApi api;
    private String serverId;
    private String mode;
    private String parentId;
    private int start;
    private boolean loading;
    private boolean noMore;
    private int gen;
    /** 当前选中的类型（null=全部），只在 view 模式用。 */
    private String genre;
    private final List<String> genres = new ArrayList<>();
    private ChipAdapter chipAdapter;
    /** 401 标记：登录已失效时空态点我去重登（P0-4 调用方引导侧；首页 rail 在禁区不做）。 */
    private boolean authExpired;
    /** 本库是不是音乐库（CollectionType=music）：是则走 Audio 条目。 */
    private boolean isMusic;
    private boolean musicChecked;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityEmbyLibraryBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        serverId = getIntent().getStringExtra("serverId");
        mode = getIntent().getStringExtra("mode");
        parentId = getIntent().getStringExtra("parentId");
        binding.tvTitle.setText(getIntent().getStringExtra("title"));
        binding.ivBack.setOnClickListener(v -> finish());
        binding.rv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new Adapter();
        binding.rv.setAdapter(adapter);
        binding.rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!MODE_VIEW.equals(mode) || loading || noMore) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 6) loadMore();
            }
        });
        binding.refresh.setOnRefreshListener(this::reload);
        binding.tvEmpty.setOnClickListener(v -> {
            if (authExpired) {
                try {
                    EmbyServerActivity.start(EmbyLibraryActivity.this);
                } catch (Throwable ignored) {
                }
                return;
            }
            reload();
        });
        EmbyServer s = findServer();
        if (s != null) api = new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
        boolean isView = MODE_VIEW.equals(mode);
        // 筛选栏 + 演员入口只在媒体库全量模式出现
        binding.btnPerson.setVisibility(isView ? View.VISIBLE : View.GONE);
        binding.btnPerson.setOnClickListener(v -> openPersonDialog());
        binding.filterBar.setVisibility(View.GONE);
        binding.sortBarScroll.setVisibility(View.GONE);
        if (isView) {
            binding.filterBar.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            chipAdapter = new ChipAdapter();
            binding.filterBar.setAdapter(chipAdapter);
            binding.sortBarScroll.setVisibility(View.VISIBLE);
            binding.btnSort.setOnClickListener(v -> openSortDialog());
            binding.btnState.setOnClickListener(v -> openStateDialog());
            binding.btnYear.setOnClickListener(v -> openYearDialog());
            binding.btnRating.setOnClickListener(v -> openRatingDialog());
            binding.btnTag.setOnClickListener(v -> openTagDialog());
            refreshFilterLabels();
            loadGenres();
            checkMusic();
        }
        reload();
    }

    private EmbyServer findServer() {
        try {
            List<EmbyServer> all = EmbyStore.servers();
            if (all != null) for (EmbyServer s : all) {
                if (s != null && serverId != null && serverId.equals(s.id)) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 拉类型列表，失败就藏掉筛选栏（不挡正常浏览）。 */
    private void loadGenres() {
        if (api == null) return;
        final int myGen = gen;
        new Thread(() -> {
            List<String> list = null;
            try {
                list = api.genres();
            } catch (Exception ignored) {
            }
            final List<String> data = list;
            App.post(() -> {
                if (myGen != gen) return;
                if (data == null || data.isEmpty()) {
                    binding.filterBar.setVisibility(View.GONE);
                    return;
                }
                genres.clear();
                genres.addAll(data);
                chipAdapter.notifyDataSetChanged();
                binding.filterBar.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    private void reload() {
        gen++;
        start = 0;
        noMore = false;
        authExpired = false;
        items.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        binding.tvEmpty.setVisibility(View.GONE);
        if (MODE_VIEW.equals(mode)) loadMore();
        else loadSingle();
    }

    /** 查本库是不是音乐库：是则类型/演员筛选栏藏掉（只留排序），走 Audio 条目。 */
    private void checkMusic() {
        if (api == null || parentId == null) return;
        final int myGen = gen;
        new Thread(() -> {
            boolean music = false;
            try {
                music = api.isMusicCollection(parentId);
            } catch (Throwable ignored) {
            }
            final boolean m = music;
            App.post(() -> {
                if (myGen != gen) return;
                isMusic = m;
                musicChecked = true;
                if (m) {
                    binding.filterBar.setVisibility(View.GONE);
                    binding.btnPerson.setVisibility(View.GONE);
                }
                reload();
            });
        }).start();
    }

    /** "SortBy:SortOrder" 拆成两段。 */
    private static String[] parseSort(String sort) {
        String[] out = {"SortName", "Ascending"};
        if (sort != null) {
            int p = sort.indexOf(':');
            if (p > 0) {
                out[0] = sort.substring(0, p);
                out[1] = sort.substring(p + 1);
            }
        }
        return out;
    }

    /** 状态/分级/标签拼成 Filters/OfficialRatings/Tags 参数（Years 单独走）。 */
    private String buildFilters() {
        StringBuilder sb = new StringBuilder();
        String played = EmbyStore.libPlayedFilter();
        if (!played.isEmpty()) sb.append(played);
        return sb.toString();
    }

    /** 排序/筛选按钮文案刷新。 */
    private void refreshFilterLabels() {
        String[] sort = parseSort(EmbyStore.libSort());
        binding.btnSort.setText(getString(R.string.emby_sort) + "：" + sortNameOf(sort[0])
                + ("Ascending".equals(sort[1]) ? "↑" : "↓"));
        String played = EmbyStore.libPlayedFilter();
        binding.btnState.setText(getString(R.string.emby_filter_state) + "：" + ("".equals(played)
                ? getString(R.string.emby_state_all)
                : "IsPlayed".equals(played) ? getString(R.string.emby_state_played)
                : "IsUnplayed".equals(played) ? getString(R.string.emby_state_unplayed)
                : getString(R.string.emby_state_fav)));
        String year = EmbyStore.libYear();
        binding.btnYear.setText(getString(R.string.emby_filter_year) + (year.isEmpty() ? "" : "：" + year));
        String rating = EmbyStore.libRating();
        binding.btnRating.setText(getString(R.string.emby_filter_rating) + (rating.isEmpty() ? "" : "：" + rating));
        String tag = EmbyStore.libTag();
        binding.btnTag.setText(getString(R.string.emby_filter_tag) + (tag.isEmpty() ? "" : "：" + tag));
    }

    private String sortNameOf(String sortBy) {
        if ("DateCreated".equals(sortBy)) return getString(R.string.emby_sort_date);
        if ("ProductionYear".equals(sortBy)) return getString(R.string.emby_sort_year);
        if ("CommunityRating".equals(sortBy)) return getString(R.string.emby_sort_rating);
        return getString(R.string.emby_sort_name);
    }

    /** 排序弹窗：4 种字段 × 升/降序。 */
    private void openSortDialog() {
        String[] labels = {
                getString(R.string.emby_sort_name) + getString(R.string.emby_asc),
                getString(R.string.emby_sort_name) + getString(R.string.emby_desc),
                getString(R.string.emby_sort_date) + getString(R.string.emby_desc),
                getString(R.string.emby_sort_date) + getString(R.string.emby_asc),
                getString(R.string.emby_sort_year) + getString(R.string.emby_desc),
                getString(R.string.emby_sort_year) + getString(R.string.emby_asc),
                getString(R.string.emby_sort_rating) + getString(R.string.emby_desc),
                getString(R.string.emby_sort_rating) + getString(R.string.emby_asc),
        };
        String[] values = {
                "SortName:Ascending", "SortName:Descending",
                "DateCreated:Descending", "DateCreated:Ascending",
                "ProductionYear:Descending", "ProductionYear:Ascending",
                "CommunityRating:Descending", "CommunityRating:Ascending",
        };
        String cur = EmbyStore.libSort();
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.emby_sort)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    EmbyStore.setLibSort(values[which]);
                    refreshFilterLabels();
                    reload();
                })
                .setNegativeButton(R.string.emby_btn_cancel, null)
                .show();
    }

    /** 状态弹窗：全部 / 已看 / 未看 / 收藏。 */
    private void openStateDialog() {
        String[] labels = {
                getString(R.string.emby_state_all),
                getString(R.string.emby_state_played),
                getString(R.string.emby_state_unplayed),
                getString(R.string.emby_state_fav),
        };
        String[] values = {"", "IsPlayed", "IsUnplayed", "IsFavorite"};
        String cur = EmbyStore.libPlayedFilter();
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.emby_filter_state)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    EmbyStore.setLibPlayedFilter(values[which]);
                    refreshFilterLabels();
                    reload();
                })
                .setNegativeButton(R.string.emby_btn_cancel, null)
                .show();
    }

    /** 年份弹窗：/Years 拉不到（部分老服 500）就直接提示，不造假数据。 */
    private void openYearDialog() {
        if (api == null) return;
        Notify.show(R.string.emby_loading);
        new Thread(() -> {
            List<String> data = null;
            try {
                data = api.years();
            } catch (Throwable ignored) {
            }
            final List<String> years = data;
            App.post(() -> {
                if (years == null || years.isEmpty()) {
                    Notify.show(R.string.emby_empty_list);
                    return;
                }
                List<String> labels = new ArrayList<>();
                List<String> values = new ArrayList<>();
                labels.add(getString(R.string.emby_state_all));
                values.add("");
                labels.addAll(years);
                values.addAll(years);
                String cur = EmbyStore.libYear();
                int checked = 0;
                for (int i = 0; i < values.size(); i++) if (values.get(i).equals(cur)) checked = i;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.emby_filter_year)
                        .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                            d.dismiss();
                            EmbyStore.setLibYear(values.get(which));
                            refreshFilterLabels();
                            reload();
                        })
                        .setNegativeButton(R.string.emby_btn_cancel, null)
                        .show();
            });
        }).start();
    }

    /** 分级弹窗：拿不到就隐藏提示，不造假数据。 */
    private void openRatingDialog() {
        if (api == null) return;
        Notify.show(R.string.emby_loading);
        new Thread(() -> {
            List<String> data = null;
            try {
                data = api.officialRatings();
            } catch (Throwable ignored) {
            }
            final List<String> ratings = data;
            App.post(() -> {
                if (ratings == null || ratings.isEmpty()) {
                    Notify.show(R.string.emby_empty_list);
                    return;
                }
                showStringChoice(R.string.emby_filter_rating, ratings, EmbyStore.libRating(), v -> {
                    EmbyStore.setLibRating(v);
                    refreshFilterLabels();
                    reload();
                });
            });
        }).start();
    }

    /** 标签弹窗：拿不到就隐藏提示，不造假数据。 */
    private void openTagDialog() {
        if (api == null) return;
        Notify.show(R.string.emby_loading);
        new Thread(() -> {
            List<String> data = null;
            try {
                data = api.tags();
            } catch (Throwable ignored) {
            }
            final List<String> tags = data;
            App.post(() -> {
                if (tags == null || tags.isEmpty()) {
                    Notify.show(R.string.emby_empty_list);
                    return;
                }
                showStringChoice(R.string.emby_filter_tag, tags, EmbyStore.libTag(), v -> {
                    EmbyStore.setLibTag(v);
                    refreshFilterLabels();
                    reload();
                });
            });
        }).start();
    }

    private interface StringChoice {
        void onPick(String value);
    }

    private void showStringChoice(int title, List<String> items, String cur, StringChoice cb) {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add(getString(R.string.emby_state_all));
        values.add("");
        labels.addAll(items);
        values.addAll(items);
        int checked = 0;
        for (int i = 0; i < values.size(); i++) if (values.get(i).equals(cur)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    d.dismiss();
                    cb.onPick(values.get(which));
                })
                .setNegativeButton(R.string.emby_btn_cancel, null)
                .show();
    }

    private void loadMore() {
        if (api == null || parentId == null) {
            showEmpty(getString(R.string.emby_login_first));
            return;
        }
        loading = true;
        if (start == 0) binding.loading.setVisibility(View.VISIBLE);
        final int myGen = gen;
        final int offset = start;
        final String[] sort = parseSort(EmbyStore.libSort());
        final String filters = buildFilters();
        final String years = EmbyStore.libYear();
        final String ratings = EmbyStore.libRating();
        final String tags = EmbyStore.libTag();
        final String genreNow = genre;
        new Thread(() -> {
            List<EmbyItem> list = null;
            int total = -1;
            String err = null;
            try {
                // 音乐库自动走 Audio 条目；排序/筛选/年份/分级/标签全进服务端参数
                EmbyApi.ItemPage page = api.libraryItems(parentId, offset, PAGE, genreNow,
                        sort[0], sort[1], filters, years, ratings, tags);
                if (page != null) {
                    list = page.items;
                    total = page.total;
                }
            } catch (Exception e) {
                list = null;
                err = e.getMessage();
            }
            final List<EmbyItem> data = list;
            final int totalCount = total;
            final String error = err;
            App.post(() -> {
                if (myGen != gen) return;
                loading = false;
                binding.loading.setVisibility(View.GONE);
                binding.refresh.setRefreshing(false);
                if (data == null) {
                    if (isAuthExpired(error)) {
                        authExpired = true;
                        showEmpty(getString(R.string.emby_auth_expired_go));
                    } else if (items.isEmpty()) {
                        showEmpty(getString(R.string.emby_load_fail));
                    } else {
                        Notify.show("加载失败");
                    }
                    return;
                }
                if (totalCount >= 0) noMore = start + data.size() >= totalCount;
                else if (data.size() < PAGE) noMore = true;
                start += data.size();
                int from = items.size();
                items.addAll(data);
                adapter.notifyItemRangeInserted(from, data.size());
                updateEmpty();
            });
        }).start();
    }

    private void loadSingle() {
        if (api == null) {
            showEmpty(getString(R.string.emby_login_first));
            return;
        }
        if (MODE_PERSON.equals(mode) && parentId == null) {
            showEmpty(getString(R.string.emby_filter_person_no_work));
            return;
        }
        binding.loading.setVisibility(View.VISIBLE);
        final int myGen = gen;
        new Thread(() -> {
            List<EmbyItem> list = null;
            String err = null;
            try {
                if (MODE_RESUME.equals(mode)) list = api.resume();
                else if (MODE_LATEST.equals(mode)) list = api.latest();
                else if (MODE_NEXTUP.equals(mode)) list = api.nextUp();
                else if (MODE_FAV.equals(mode)) list = api.favoriteItems();
                else if (MODE_PERSON.equals(mode)) list = api.personItems(parentId);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<EmbyItem> data = list;
            final String error = err;
            App.post(() -> {
                if (myGen != gen) return;
                binding.loading.setVisibility(View.GONE);
                binding.refresh.setRefreshing(false);
                if (data == null) {
                    if (isAuthExpired(error)) {
                        authExpired = true;
                        showEmpty(getString(R.string.emby_auth_expired_go));
                    } else {
                        showEmpty(getString(R.string.emby_load_fail));
                    }
                    return;
                }
                items.clear();
                items.addAll(data);
                adapter.notifyDataSetChanged();
                updateEmpty();
            });
        }).start();
    }

    /** 401 = token 失效：异常文案来自 EmbyApi.throwIfAuthExpired，直接展示并引导重登。 */
    private static boolean isAuthExpired(String err) {
        return err != null && err.contains("登录已失效");
    }

    private void updateEmpty() {
        binding.tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        int text = MODE_PERSON.equals(mode) ? R.string.emby_filter_person_no_work
                : MODE_FAV.equals(mode) ? R.string.emby_empty_fav
                : (MODE_VIEW.equals(mode) && isMusic) ? R.string.emby_audio_empty
                : R.string.emby_empty_list;
        binding.tvEmpty.setText(text);
    }

    private void showEmpty(String text) {
        binding.loading.setVisibility(View.GONE);
        binding.refresh.setRefreshing(false);
        binding.tvEmpty.setVisibility(View.VISIBLE);
        binding.tvEmpty.setText(text);
    }

    /** 海报卡长按菜单：标记已看/取消已看、收藏/取消收藏（全应用统一文案）。 */
    private void showCardMenu(EmbyItem it) {
        if (it == null) return;
        String[] titles = {
                it.played ? getString(R.string.emby_menu_unmark_played) : getString(R.string.emby_menu_mark_played),
                it.favorite ? getString(R.string.emby_menu_unfavorite) : getString(R.string.emby_menu_favorite)
        };
        new AlertDialog.Builder(this).setItems(titles, (d, which) -> {
            if (which == 0) togglePlayed(it);
            else toggleFavorite(it);
        }).show();
    }

    private void togglePlayed(EmbyItem it) {
        if (api == null) {
            Notify.show(getString(R.string.emby_login_first));
            return;
        }
        final boolean toPlayed = !it.played;
        new Thread(() -> {
            boolean ok = false;
            try {
                if (toPlayed) api.markPlayed(it.id);
                else api.unmarkPlayed(it.id);
                ok = true;
            } catch (Exception ignored) {
            }
            final boolean r = ok;
            App.post(() -> {
                int idx = items.indexOf(it);
                if (!r) {
                    Notify.show(getString(R.string.emby_action_fail));
                    return;
                }
                it.played = toPlayed;
                if (idx >= 0) adapter.notifyItemChanged(idx);
                Notify.show(getString(toPlayed ? R.string.emby_action_mark_played_ok : R.string.emby_action_unmark_played_ok));
            });
        }).start();
    }

    private void toggleFavorite(EmbyItem it) {
        if (api == null) {
            Notify.show(getString(R.string.emby_login_first));
            return;
        }
        final boolean toFav = !it.favorite;
        new Thread(() -> {
            boolean ok = false;
            try {
                if (toFav) api.favorite(it.id);
                else api.unfavorite(it.id);
                ok = true;
            } catch (Exception ignored) {
            }
            final boolean r = ok;
            App.post(() -> {
                int idx = items.indexOf(it);
                if (!r) {
                    Notify.show(getString(R.string.emby_action_fail));
                    return;
                }
                it.favorite = toFav;
                // 收藏页里取消收藏：卡片直接抽掉
                if (!toFav && MODE_FAV.equals(mode) && idx >= 0) {
                    items.remove(idx);
                    adapter.notifyItemRemoved(idx);
                    updateEmpty();
                } else if (idx >= 0) {
                    adapter.notifyItemChanged(idx);
                }
                Notify.show(getString(toFav ? R.string.emby_action_fav_ok : R.string.emby_action_unfav_ok));
            });
        }).start();
    }

    /** 网格列宽自适应：海报保持 2:3（主列表与演员弹窗共用）。 */
    private static void fitPoster(View v) {
        int col = (ResUtil.getScreenWidth() - ResUtil.dp2px(32)) / 3;
        int w = col - ResUtil.dp2px(12);
        int h = w * 3 / 2;
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ResUtil.dp2px(6);
        lp.rightMargin = ResUtil.dp2px(6);
        lp.bottomMargin = ResUtil.dp2px(12);
        v.setLayoutParams(lp);
        View frame = (View) v.findViewById(R.id.poster).getParent();
        ViewGroup.LayoutParams glp = frame.getLayoutParams();
        glp.width = w;
        glp.height = h;
        frame.setLayoutParams(glp);
        v.findViewById(R.id.name).getLayoutParams().width = w;
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_poster, parent, false);
            fitPoster(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyItem it = items.get(position);
            h.name.setText(it.name == null ? "" : it.name);
            String url = "";
            try {
                url = api.imageUrl(it.id, it.imageTag, 400);
            } catch (Throwable ignored) {
            }
            ImgUtil.loadRounded(it.name, url, h.poster);
            h.badge.setVisibility(View.GONE);
            h.playedBadge.setVisibility(View.GONE);
            h.progress.setVisibility(View.GONE);
            if (it.played) h.playedBadge.setVisibility(View.VISIBLE);
            if (it.communityRating > 0) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(String.format("%.1f", it.communityRating));
            }
            h.itemView.setOnClickListener(v -> {
                try {
                    EmbyNavigator.openDetail(EmbyLibraryActivity.this, serverId, it.id);
                } catch (Throwable t) {
                    Notify.show("打不开详情");
                }
            });
            h.itemView.setOnLongClickListener(v -> {
                showCardMenu(it);
                return true;
            });
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            Glide.with(h.poster).clear(h.poster);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView poster;
            final TextView name;
            final TextView badge;
            final TextView playedBadge;
            final ProgressBar progress;

            Holder(View v) {
                super(v);
                poster = v.findViewById(R.id.poster);
                name = v.findViewById(R.id.name);
                badge = v.findViewById(R.id.badge);
                playedBadge = v.findViewById(R.id.played_badge);
                progress = v.findViewById(R.id.progress);
            }
        }
    }

    /** 类型筛选 chips：0=全部，其余=类型名；样式对齐现有 filters 横栏。 */
    private class ChipAdapter extends RecyclerView.Adapter<ChipAdapter.Holder> {

        private int selected = 0;

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_chip, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String label = position == 0 ? getString(R.string.emby_filter_all) : genres.get(position - 1);
            h.text.setText(label);
            h.text.setSelected(position == selected);
            h.text.setOnClickListener(v -> {
                if (selected == position) return;
                int old = selected;
                selected = position;
                notifyItemChanged(old);
                notifyItemChanged(selected);
                genre = position == 0 ? null : genres.get(position - 1);
                reload();
            });
        }

        @Override
        public int getItemCount() {
            return genres.size() + 1;
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(View v) {
                super(v);
                text = v.findViewById(R.id.text);
            }
        }
    }

    /** 演员入口弹窗：搜演员名字，点演员进其作品（复用本页 MODE_PERSON）。 */
    private void openPersonDialog() {
        if (api == null) {
            Notify.show(getString(R.string.emby_login_first));
            return;
        }
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_emby_person, null);
        EditText et = v.findViewById(R.id.et_search);
        RecyclerView rv = v.findViewById(R.id.rv);
        TextView tvEmpty = v.findViewById(R.id.tv_empty);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        PersonAdapter pa = new PersonAdapter(tvEmpty);
        rv.setAdapter(pa);
        AlertDialog d = new AlertDialog.Builder(this).setView(v).create();
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                pa.filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        pa.setOnPick(person -> {
            d.dismiss();
            EmbyLibraryActivity.start(EmbyLibraryActivity.this, serverId, MODE_PERSON, person.id,
                    person.name == null || person.name.isEmpty() ? getString(R.string.emby_filter_person) : person.name);
        });
        d.show();
        new Thread(() -> {
            List<EmbyItem> list = null;
            try {
                list = api.persons();
            } catch (Exception ignored) {
            }
            final List<EmbyItem> data = list;
            App.post(() -> pa.setAll(data));
        }).start();
    }

    private class PersonAdapter extends RecyclerView.Adapter<PersonAdapter.Holder> {

        private final TextView tvEmpty;
        private final List<EmbyItem> all = new ArrayList<>();
        private final List<EmbyItem> shown = new ArrayList<>();
        private OnPick pick;

        interface OnPick {
            void onPick(EmbyItem person);
        }

        PersonAdapter(TextView tvEmpty) {
            this.tvEmpty = tvEmpty;
        }

        void setOnPick(OnPick pick) {
            this.pick = pick;
        }

        void setAll(List<EmbyItem> data) {
            all.clear();
            if (data != null) all.addAll(data);
            filter("");
        }

        void filter(String q) {
            shown.clear();
            String key = q == null ? "" : q.trim().toLowerCase();
            for (EmbyItem it : all) {
                String n = it.name == null ? "" : it.name;
                if (key.isEmpty() || n.toLowerCase().contains(key)) shown.add(it);
            }
            notifyDataSetChanged();
            tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emby_poster, parent, false);
            fitPoster(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            EmbyItem it = shown.get(position);
            h.name.setText(it.name == null ? "" : it.name);
            String url = "";
            try {
                url = api.imageUrl(it.id, it.imageTag, 400);
            } catch (Throwable ignored) {
            }
            ImgUtil.loadRounded(it.name, url, h.poster);
            h.badge.setVisibility(View.GONE);
            h.playedBadge.setVisibility(View.GONE);
            h.progress.setVisibility(View.GONE);
            h.itemView.setOnClickListener(v -> {
                if (pick != null) pick.onPick(it);
            });
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            Glide.with(h.poster).clear(h.poster);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView poster;
            final TextView name;
            final TextView badge;
            final TextView playedBadge;
            final ProgressBar progress;

            Holder(View v) {
                super(v);
                poster = v.findViewById(R.id.poster);
                name = v.findViewById(R.id.name);
                badge = v.findViewById(R.id.badge);
                playedBadge = v.findViewById(R.id.played_badge);
                progress = v.findViewById(R.id.progress);
            }
        }
    }
}
