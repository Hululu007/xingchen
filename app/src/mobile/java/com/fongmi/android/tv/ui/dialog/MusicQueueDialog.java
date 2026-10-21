package com.fongmi.android.tv.ui.dialog;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.DialogMusicQueueBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放队列面板（GZ PlayQueueSheet 证据 redo2-gz-queue-evidence 逐项转写）：
 * 头部 44dp＝[‹ 圆钮][标题 17sp/800][随机筹（仅随机模式）][计数筹·纯数字][清空·红 13sp][✕ 圆钮]，‹ 与 ✕ 均关面板；
 * 来源行＝开面板时按 track.platform 统计队列级来源，全队一致才显示（星辰无 GZ 的 route 跳转，不可点、无 ›）；
 * 提示行「长按拖动调整顺序」；队列行＝序号 %02d＋封面 36dp＋歌名 13sp/700＋歌手 11sp＋三钮常显（30dp/图标 18dp），
 * 无时长/播放指示/手柄，长按整行拖动排序，被拖行白底圆角 12；当前行歌名红 #FA243C、歌手红 0.72、序号红 0.7；
 * 清空＝清队并关面板（无二次确认）；空态照 GZ。上一版的「中性深代红」口径无用户原话、与本体证据冲突，本版废止。
 */
public class MusicQueueDialog extends BaseBottomSheetDialog {

    private static final int COLOR_TEXT = 0xFF1C1C1E;
    private static final int COLOR_LOW = 0xFF9A9AA0;
    private static final int COLOR_RED = 0xFFFA243C; // GZ AccentRed（PlayQueueSheetKt:130 实证）
    private static final int COLOR_CURRENT_ARTIST = 0xB8FA243C; // 当前行歌手＝AccentRed × 0.72
    private static final int COLOR_CURRENT_INDEX = 0xB3FA243C; // 当前行序号＝AccentRed × 0.7

    private DialogMusicQueueBinding binding;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private QueueAdapter adapter;
    private ItemTouchHelper touchHelper;

    public static void show(FragmentActivity activity) {
        new MusicQueueDialog().show(activity.getSupportFragmentManager(), "music_queue");
    }

    // 内容自带浅色底（bg_sheet_panel #F3F3F3）：弹窗容器/窗口必须透明，
    // 否则 Material 底部弹窗默认的浅色层会从顶部漏出一条白带。
    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicQueueBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        // 底部锚定修复：旧代码把窗口压成 WRAP_CONTENT 高，CoordinatorLayout 跟着缩、行为层失去底部锚点，
        // 面板贴到屏顶（实拍：屏顶约 100px 到 62% 高）。改回窗口全高＋重力 BOTTOM，让行为层把面板钉在底部；
        // 宽仍 MATCH_PARENT（右缘防裁切原意保留）；面板自身最大高 0.68 屏高见下面（内容短时不撑满）。
        // 只改队列（基类与其他面板不动，工具面板同款调用未改、真机核其位置时一并看）。
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                getDialog().getWindow().setGravity(Gravity.BOTTOM);
            }
        } catch (Throwable ignored) {
        }
        // 队列面板最大高度＝屏高 0.68（GZ PlayQueueSheet 口径：钳整面板，不是只钳列表；内容短时不留白，空态时整面板撑到 0.68 以便空态 weight 1f 居中）
        binding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        // 行 pitch 56＋行间 2dp＝58dp（GZ spacedBy 2dp）：最后一行后不加间隔
        binding.recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                if (pos >= 0 && parent.getAdapter() != null && pos < parent.getAdapter().getItemCount() - 1) {
                    outRect.bottom = Math.round(2 * parent.getContext().getResources().getDisplayMetrics().density);
                }
            }
        });
        // 行移位动画（GZ queueRowShift：spring(1.0, 400) 临界阻尼弹簧，400ms）
        binding.recycler.setItemAnimator(new QueueShiftAnimator());
        binding.recycler.setAdapter(adapter = new QueueAdapter());
        binding.btnClear.setOnClickListener(v -> {
            MusicController.get().clearQueue();
            dismissAllowingStateLoss();
        });
        attachPress(binding.btnClear, 0.96f);
        // GZ SheetSubPanelHeader：‹ 与 ✕ 传的是同一个关闭回调——两个都是关面板，不是返回上一级
        binding.btnBack.setOnClickListener(v -> dismissAllowingStateLoss());
        attachPress(binding.btnBack, 0.9f);
        binding.btnClose.setOnClickListener(v -> dismissAllowingStateLoss());
        attachPress(binding.btnClose, 0.9f);
        touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            private int lastDragTarget = -1;

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from < 0 || to < 0 || from >= tracks.size() || to >= tracks.size()) return false;
                MusicController.get().move(from, to);
                Collections.swap(tracks, from, to);
                adapter.notifyItemMoved(from, to);
                // 拖动触觉（GZ：目标下标变更时 a(9)＝TEXT_HANDLE_MOVE）
                if (to != lastDragTarget) {
                    vh.itemView.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE);
                    lastDragTarget = to;
                }
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // GZ：长按整行任意位置触发拖动（detectDragGesturesAfterLongPress），无独立手柄
                return true;
            }

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int actionState) {
                super.onSelectedChanged(vh, actionState);
                // 被拖行：白底圆角 12dp 不动（旧证据 8dp 系误读，8 是阴影高）＋缩放 0.97＋阴影 8dp（仅被拖行）
                if (vh != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh.itemView.setBackgroundResource(R.drawable.bg_sheet_row_drag);
                    vh.itemView.setScaleX(0.97f);
                    vh.itemView.setScaleY(0.97f);
                    float density = vh.itemView.getContext().getResources().getDisplayMetrics().density;
                    vh.itemView.setElevation(Math.round(8 * density));
                    // 拖动触觉（GZ：开始 performHapticFeedback(0)＝LONG_PRESS）
                    vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    lastDragTarget = vh.getBindingAdapterPosition();
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setBackground(null);
                vh.itemView.setScaleX(1f);
                vh.itemView.setScaleY(1f);
                vh.itemView.setElevation(0f);
                lastDragTarget = -1;
                refresh();
            }
        });
        touchHelper.attachToRecyclerView(binding.recycler);
        refresh();
        // 列表初始滚动（GZ：current−3，clamp 到 [0, size−1]）
        binding.recycler.post(() -> {
            if (binding == null || tracks.isEmpty()) return;
            int idx = MusicController.get().index();
            int target = Math.min(Math.max(idx - 3, 0), tracks.size() - 1);
            RecyclerView.LayoutManager lm = binding.recycler.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(target, 0);
            }
        });
    }

    /** 整面板钳高（GZ fillMaxHeight 0.68）：根高＝min(内容高, 0.68×屏高)，多余高度从列表里扣；
     * 空态时根高直接撑到 0.68×屏高，空态盒 weight 1f 才能占满居中；内容短时根高回 wrap_content，不留白 */
    private void applyPanelHeight() {
        if (binding == null || getContext() == null) return;
        int maxH = Math.round(getContext().getResources().getDisplayMetrics().heightPixels * 0.68f);
        View root = binding.getRoot();
        root.post(() -> {
            if (binding == null) return;
            if (tracks.isEmpty()) {
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                if (lp.height != maxH) {
                    lp.height = maxH;
                    root.setLayoutParams(lp);
                }
                return;
            }
            ViewGroup.LayoutParams rootLp = root.getLayoutParams();
            ViewGroup.LayoutParams recLp = binding.recycler.getLayoutParams();
            if (rootLp.height != ViewGroup.LayoutParams.WRAP_CONTENT
                    || recLp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                recLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                root.setLayoutParams(rootLp);
                binding.recycler.setLayoutParams(recLp);
                root.post(this::applyPanelHeight);
                return;
            }
            int rootH = root.getHeight();
            int recH = binding.recycler.getHeight();
            if (rootH > maxH && recH > 0) {
                int newRecH = maxH - (rootH - recH);
                if (newRecH > 0 && newRecH < recH) {
                    recLp.height = newRecH;
                    binding.recycler.setLayoutParams(recLp);
                }
            }
        });
    }

    /** GZ 通用按压反馈（MotionKt：150ms；缩放值由调用方传） */
    private void attachPress(View view, float scale) {
        view.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            }
            return false;
        });
    }

    /** 行移位动画（GZ queueRowShift：spring(1.0, 400)）：临界阻尼弹簧插值器＋400ms，其余动画走 DefaultItemAnimator */
    private static class QueueShiftAnimator extends DefaultItemAnimator {
        private final List<RecyclerView.ViewHolder> moving = new ArrayList<>();

        QueueShiftAnimator() {
            setMoveDuration(400);
        }

        @Override
        public boolean animateMove(RecyclerView.ViewHolder holder, int fromX, int fromY, int toX, int toY) {
            if (fromX == toX && fromY == toY) {
                dispatchMoveFinished(holder);
                return false;
            }
            View view = holder.itemView;
            view.setTranslationX(fromX - toX);
            view.setTranslationY(fromY - toY);
            moving.add(holder);
            view.animate().translationX(0f).translationY(0f).setDuration(400)
                    .setInterpolator(new CriticallyDampedSpringInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            view.setTranslationX(0f);
                            view.setTranslationY(0f);
                            moving.remove(holder);
                            dispatchMoveFinished(holder);
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            view.setTranslationX(0f);
                            view.setTranslationY(0f);
                            moving.remove(holder);
                            dispatchMoveFinished(holder);
                        }
                    }).start();
            return true;
        }

        @Override
        public boolean isRunning() {
            return super.isRunning() || !moving.isEmpty();
        }

        @Override
        public void endAnimation(RecyclerView.ViewHolder holder) {
            super.endAnimation(holder);
            if (moving.remove(holder)) {
                holder.itemView.animate().cancel();
                holder.itemView.setTranslationX(0f);
                holder.itemView.setTranslationY(0f);
                dispatchMoveFinished(holder);
            }
        }

        @Override
        public void endAnimations() {
            super.endAnimations();
            List<RecyclerView.ViewHolder> copy = new ArrayList<>(moving);
            moving.clear();
            for (RecyclerView.ViewHolder h : copy) {
                h.itemView.animate().cancel();
                h.itemView.setTranslationX(0f);
                h.itemView.setTranslationY(0f);
                dispatchMoveFinished(h);
            }
        }
    }

    /** 临界阻尼弹簧（dampingRatio 1.0，无超调）：进度＝1−(1+ωt)·e^(−ωt)，ω 取 6.64 使 400ms 末约 0.99 */
    private static class CriticallyDampedSpringInterpolator implements Interpolator {
        @Override
        public float getInterpolation(float t) {
            double w = 6.64;
            return (float) (1 - (1 + w * t) * Math.exp(-w * t));
        }
    }

    private void refresh() {
        tracks.clear();
        tracks.addAll(MusicController.get().queue());
        // GZ 计数筹＝纯数字（PlayQueueSheetKt:724 String.valueOf(size)），无「首」
        binding.tvCount.setText(String.valueOf(tracks.size()));
        bindSourceRow();
        binding.badgeShuffle.setVisibility(
                MusicController.get().getMode() == MusicQueue.MODE_SHUFFLE ? View.VISIBLE : View.GONE);
        boolean empty = tracks.isEmpty();
        binding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.tvHint.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.btnClear.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        applyPanelHeight();
    }

    /**
     * 来源行（GZ Q02）：优先读入队时写的队列级来源／route（B7：MusicQueue.source／route，
     * 榜单／随心听／列表页起播时写入，断点恢复时同存同取）；老断点队列无此信息时回落旧反查口径。
     * 有可解析 route 才给 › 且可点跳来源页；无 route／route 无落点时只展示、不可点、无 ›（与 GZ 无 route 形态一致）。
     */
    private void bindSourceRow() {
        String label = MusicController.get().queueSource();
        String route = MusicController.get().queueRoute();
        if (label == null || label.isEmpty()) {
            boolean uniform = !tracks.isEmpty();
            for (MusicTrack t : tracks) {
                String l = sourceLabel(t);
                if (l == null || (label != null && !label.equals(l))) {
                    uniform = false;
                    break;
                }
                label = l;
            }
            if (!uniform) label = null;
            route = null;
        }
        if (label == null || label.isEmpty()) {
            binding.sourceRow.setVisibility(View.GONE);
            return;
        }
        binding.sourceName.setText(label);
        android.content.Intent intent = queueSourceIntent(route, MusicController.get().queueRouteParams());
        android.view.View chevron = binding.sourceRow.findViewWithTag("queueSourceChevron");
        if (chevron == null) {
            TextView cv = new TextView(getContext());
            cv.setTag("queueSourceChevron");
            cv.setText("›");
            cv.setTextSize(16);
            cv.setTextColor(COLOR_LOW);
            binding.sourceRow.addView(cv, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            chevron = cv;
        }
        if (intent != null) {
            chevron.setVisibility(View.VISIBLE);
            binding.sourceRow.setOnClickListener(v -> {
                FragmentActivity host = getActivity();
                dismissAllowingStateLoss();
                if (host != null) host.startActivity(intent);
            });
        } else {
            chevron.setVisibility(View.GONE);
            binding.sourceRow.setOnClickListener(null);
            binding.sourceRow.setClickable(false);
        }
        binding.sourceRow.setVisibility(View.VISIBLE);
    }

    /**
     * B7：route → 来源页落点。只认入队时写入的两条真实路由，其余一律返回空（只展示不可点）：
     * home＝首页（榜单卡／随心听卡都在首页）；list＝列表页（参数与 MusicListActivity onCreate 读的
     * intent extras 同名同值，可重建本页；无标题／关键词／歌单 id 三者时无落点，不跳空页）。
     */
    private android.content.Intent queueSourceIntent(String route, java.util.Map<String, String> params) {
        FragmentActivity host = getActivity();
        if (host == null || route == null || route.isEmpty()) return null;
        if ("home".equals(route)) {
            android.content.Intent i = new android.content.Intent(host,
                    com.fongmi.android.tv.ui.activity.MusicActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            return i;
        }
        if ("list".equals(route)) {
            if (params == null) return null;
            String title = params.get("title");
            String keyword = params.get("keyword");
            String playlistId = params.get("playlistId");
            boolean hasTarget = (playlistId != null && !playlistId.isEmpty() && !"-1".equals(playlistId))
                    || (keyword != null && !keyword.isEmpty())
                    || (title != null && !title.isEmpty());
            if (!hasTarget) return null;
            long pid = -1;
            try {
                pid = Long.parseLong(playlistId);
            } catch (Throwable ignored) {
            }
            android.content.Intent i = new android.content.Intent(host,
                    com.fongmi.android.tv.ui.activity.MusicListActivity.class);
            i.putExtra("mode", params.get("mode"));
            i.putExtra("keyword", keyword == null ? "" : keyword);
            i.putExtra("sitePlatform", params.get("sitePlatform"));
            i.putExtra("playlistId", pid);
            i.putExtra("title", title == null ? "" : title);
            return i;
        }
        return null;
    }

    private String sourceLabel(MusicTrack t) {
        if (t == null || t.platform == null || t.platform.isEmpty()) return null;
        if ("device".equals(t.platform)) return getString(R.string.home_device);
        try {
            for (MusicSite site : MusicRepository.sites()) {
                if (t.platform.equals(site.platform)) return site.name;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 加歌单（GZ：关队列面板＋开加歌单面板）：先取宿主再关面板，回调里不再用 isAdded/getContext（面板已关） */
    private void pickPlaylist(MusicTrack track) {
        FragmentActivity host = getActivity();
        if (host == null || track == null) return;
        String newPlaylist = getString(R.string.music_new_playlist);
        String addTitle = getString(R.string.music_add_to_playlist);
        dismissAllowingStateLoss();
        MusicLibrary.playlists(list -> {
            if (host.isFinishing() || host.isDestroyed()) return;
            List<String> names = new ArrayList<>();
            for (MusicPlaylist p : list) names.add(p.getName());
            names.add(newPlaylist);
            MusicOptionsDialog.show(host, addTitle, names, -1, idx -> {
                if (idx < list.size()) {
                    MusicLibrary.addToPlaylist(list.get(idx).getId(), track, added ->
                            Notify.show(added ? R.string.music_added : R.string.music_already_in));
                } else {
                    inputPlaylistName(host, name -> {
                        if (name == null || name.trim().isEmpty()) return;
                        MusicLibrary.createPlaylist(name.trim(), p ->
                                MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                        Notify.show(added ? R.string.music_added : R.string.music_already_in)));
                    });
                }
            });
        });
    }

    private interface NameInput {
        void onResult(String name);
    }

    private void inputPlaylistName(FragmentActivity host, NameInput cb) {
        if (host == null) return;
        EditText et = new EditText(host);
        et.setHint(R.string.music_playlist_name_hint);
        et.setSingleLine(true);
        int pad = Math.round(16 * host.getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music_queue, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            MusicTrack t = tracks.get(position);
            boolean current = position == MusicController.get().index();
            h.itemView.setBackground(null);
            h.index.setText(String.format(java.util.Locale.US, "%02d", position + 1));
            h.index.setTextColor(current ? COLOR_CURRENT_INDEX : COLOR_LOW);
            h.title.setText(t.displayTitle());
            h.title.setTextColor(current ? COLOR_RED : COLOR_TEXT);
            String artist = t.displayArtist();
            h.artist.setText(artist == null || artist.isEmpty() ? getString(R.string.music_unknown_artist) : artist);
            h.artist.setTextColor(current ? COLOR_CURRENT_ARTIST : COLOR_LOW);
            ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
            h.itemView.setOnClickListener(v -> {
                MusicController.get().playAt(position);
                refresh();
            });
            // 收藏态（异步查库回填图标＋染色：已收藏实心红、未收藏线框灰，GZ QueueRowActionButton）
            paintFav(h, false);
            MusicLibrary.isFavorite(t.id, fav -> {
                if (h.getBindingAdapterPosition() == position) paintFav(h, fav);
            });
            h.btnFav.setOnClickListener(v -> MusicLibrary.isFavorite(t.id, fav ->
                    MusicLibrary.setFavorite(t, !fav, () -> {
                        Notify.show(!fav ? R.string.music_favorited : R.string.music_unfavorited);
                        notifyDataSetChanged();
                    })));
            h.btnAdd.setOnClickListener(v -> pickPlaylist(t));
            h.btnRemove.setOnClickListener(v -> {
                MusicController.get().removeAt(position);
                refresh();
            });
        }

        private void paintFav(Holder h, boolean fav) {
            h.btnFav.setImageResource(fav ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            h.btnFav.setImageTintList(ColorStateList.valueOf(fav ? COLOR_RED : COLOR_LOW));
        }

        @Override
        public int getItemCount() {
            return tracks.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView index;
            final ImageView cover;
            final TextView title;
            final TextView artist;
            final ImageView btnFav;
            final ImageView btnAdd;
            final ImageView btnRemove;

            Holder(View v) {
                super(v);
                index = v.findViewById(R.id.index);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                artist = v.findViewById(R.id.artist);
                btnFav = v.findViewById(R.id.btnFav);
                btnAdd = v.findViewById(R.id.btnAdd);
                btnRemove = v.findViewById(R.id.btnRemove);
                btnAdd.setImageTintList(ColorStateList.valueOf(COLOR_LOW));
                btnRemove.setImageTintList(ColorStateList.valueOf(COLOR_LOW));
                attachPress(btnFav, 0.88f);
                attachPress(btnAdd, 0.88f);
                attachPress(btnRemove, 0.88f);
            }
        }
    }
}
