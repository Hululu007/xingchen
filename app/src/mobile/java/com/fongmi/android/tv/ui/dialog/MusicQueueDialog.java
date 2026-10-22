package com.fongmi.android.tv.ui.dialog;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.WindowCompat;
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
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放队列面板（GZ PlayQueueSheet 证据 redo2-gz-queue-evidence 逐项转写）：
 * 头部 44dp＝[标题 17sp/800][随机筹（仅随机模式）][计数筹·纯数字][清空·红 13sp][✕ 圆钮]，头部不放自己的返回箭头，✕ 关闭钮保留；
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
    private BottomSheetBehavior<FrameLayout> sheetBehavior;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private QueueAdapter adapter;
    private ItemTouchHelper touchHelper;
    /** Q1：弹窗开着时自动切歌／队列变化要跟随（旧版无任何监听，红行停旧行）。
     * 拖拽中不刷新（本弹窗自己的 move 也会触发 onQueueChanged，刷新会打断 ItemTouchHelper 拖拽）。 */
    private boolean dragging;
    private final MusicController.Listener queueListener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            if (isAdded() && !dragging) refresh();
        }

        @Override
        public void onQueueChanged() {
            if (isAdded() && !dragging) refresh();
        }
    };

    public static void show(FragmentActivity activity) {
        new MusicQueueDialog().show(activity.getSupportFragmentManager(), "music_queue");
    }

    // 内容自带浅色底（bg_sheet_panel #F3F3F3）：弹窗容器/窗口必须透明，
    // 否则 Material 底部弹窗默认的浅色层会从顶部漏出一条白带。
    @Override
    protected boolean transparent() {
        return true;
    }

    // 队列面板重做结构（GZ PlayQueueSheet，不打补丁）：
    // 行为一律不置空（1.4.6 教训：Material 1.14 在分发 insets 时必调 BottomSheetBehavior.from，
    // 行为一空开面板即崩）。行为：fitToContents 关、不可拖拽、跳过折叠、展开态；
    // 高度与偏移只由 updateSheetHeight 定（单一 owner），不挂布局监听、不另起重锚链；
    // 窗口沉浸到底（GZ 队列白到底边），面板底边到屏底盖住活动层迷你进度条。
    // GZ 对照：PlayQueueSheet 是标准底部弹窗（从底下上来、底部贴屏底、顶部圆角、上方遮罩），
    // 面板高钳 0.68 屏高，行 pitch 56＋行间 2dp。✕／点外／系统返回关闭不受影响。
    @Override
    protected void setBehavior(BottomSheetDialog dialog) {
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        sheet.setClickable(true);
        sheet.setFocusable(true);
        if (transparent()) {
            int color = ResUtil.getColor(R.color.transparent);
            sheet.setBackgroundColor(color);
            if (sheet.getParent() instanceof View) ((View) sheet.getParent()).setBackgroundColor(color);
            android.view.Window window = dialog.getWindow();
            if (window != null) window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        if (lp instanceof CoordinatorLayout.LayoutParams) {
            CoordinatorLayout.LayoutParams clp = (CoordinatorLayout.LayoutParams) lp;
            clp.gravity = Gravity.BOTTOM;
            sheet.setLayoutParams(clp);
        }
        BottomSheetBehavior<FrameLayout> behavior = behaviorOf(sheet);
        sheetBehavior = behavior;
        if (behavior == null) return;
        behavior.setFitToContents(false);
        behavior.setDraggable(false);
        behavior.setSkipCollapsed(true);
        // 首帧先按最大高锚定（避免未设偏移时满高闪一下），数据到后 updateSheetHeight 再按实高定。
        if (getContext() != null) {
            int maxH = Math.round(getContext().getResources().getDisplayMetrics().heightPixels * 0.68f);
            behavior.setPeekHeight(maxH);
            behavior.setExpandedOffset(Math.max(0, getContext().getResources().getDisplayMetrics().heightPixels - maxH));
        }
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicQueueBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        MusicController.get().addListener(queueListener);
        // 窗口全高＋重力 BOTTOM（1.4.5）：注意，此举本身钉不住面板——1.4.5 实拍仍贴顶（屏顶约 100px 到 62% 高），
        // 行为层在布局时没有把 sheet 偏移到底部，sheet 留在 CoordinatorLayout 默认的顶部位置；
        // 满高窗口下重力无意义。真正的贴底锚定在下面的 setBehavior 重写里（sheet 重力 BOTTOM＋行为保留、expandedOffset 锚定），
        // 这里只保留窗口全高（遮罩覆盖整屏）与宽 MATCH_PARENT（右缘防裁切原意保留）；
        // 面板自身最大高 0.68 屏高见下面（内容短时不撑满）。只改队列（基类与其他面板不动）。
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                getDialog().getWindow().setGravity(Gravity.BOTTOM);
                // 重做结构里的沉浸到底（不是往旧逻辑上贴的补丁）：窗口内容铺进导航栏区、导航栏透明，
                // 面板白到屏底盖住活动层迷你进度条；锚定用的 screenH 本就是全屏高，沉浸后坐标系与之一致。
                WindowCompat.setDecorFitsSystemWindows(getDialog().getWindow(), false);
                getDialog().getWindow().setNavigationBarColor(Color.TRANSPARENT);
            }
        } catch (Throwable ignored) {
        }
        // 队列面板最大高度＝屏高 0.68（GZ PlayQueueSheet 口径：钳整面板，不是只钳列表；内容短时不留白，空态时整面板撑到 0.68 以便空态 weight 1f 居中）
        binding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        // 沉浸后末行不藏手势区后：列表底垫导航栏高（clipToPadding 关，末行可滑进垫区看全）
        try {
            int navH = 0;
            int navId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (navId > 0) navH = getResources().getDimensionPixelSize(navId);
            binding.recycler.setPadding(0, 0, 0, navH);
            binding.recycler.setClipToPadding(false);
        } catch (Throwable ignored) {
        }
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
                // 复核 B5：随机模式下 MusicQueue.move 只动 shuffleOrder、from／to 是原队列序号，
                // 面板 tracks 仍是原队列顺序；本地再 Collections.swap 就会让面板顺序脱离原队列，
                // 同一次拖动的下一步／之后的删除再按面板位置传序号就动错歌。随机模式下本地面板
                // 一律不动（也不 notifyItemMoved，否则 RecyclerView 的适配器位置同样漂移，
                // 下一步 from／to 就不再是原队列序号）；只保留队列 move 的 shuffleOrder 换算，
                // 拖完 clearView 的 refresh() 会按原队列重载面板。非随机模式照旧本地换位＋动画。
                boolean shuffle = MusicController.get().getMode() == MusicQueue.MODE_SHUFFLE;
                MusicController.get().move(from, to);
                if (!shuffle) {
                    Collections.swap(tracks, from, to);
                    adapter.notifyItemMoved(from, to);
                }
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
                    dragging = true;
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
                dragging = false;
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

    @Override
    public void onDestroyView() {
        MusicController.get().removeListener(queueListener);
        binding = null;
        super.onDestroyView();
    }

    /** 队列面板高度与偏移的唯一 owner（重做结构）：面板高＝min(内容高, 0.68×屏高)，多余高度从列表里扣；
     * 空态直接撑 0.68 以便空态盒居中；内容短时回 wrap_content，不留白。
     * 偏移一次定准：peekHeight＝面板高、expandedOffset＝屏高－面板高、展开态；不挂布局监听、不另起重锚链。
     * 沉浸后坐标系是全屏高，与 screenH 口径一致。 */
    private void updateSheetHeight() {
        if (binding == null || sheetBehavior == null || getContext() == null) return;
        int screenH = getContext().getResources().getDisplayMetrics().heightPixels;
        int maxH = Math.round(screenH * 0.68f);
        View root = binding.getRoot();
        root.post(() -> {
            if (binding == null || sheetBehavior == null) return;
            if (tracks.isEmpty()) {
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                if (lp.height != maxH) {
                    lp.height = maxH;
                    root.setLayoutParams(lp);
                }
                setSheetOffsets(maxH);
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
                root.post(this::updateSheetHeight);
                return;
            }
            int rootH = root.getHeight();
            int recH = binding.recycler.getHeight();
            int finalH = rootH;
            if (rootH > maxH && recH > 0) {
                int newRecH = maxH - (rootH - recH);
                if (newRecH > 0 && newRecH < recH) {
                    recLp.height = newRecH;
                    binding.recycler.setLayoutParams(recLp);
                    finalH = maxH;
                }
            }
            if (finalH > 0) setSheetOffsets(finalH);
        });
    }

    private void setSheetOffsets(int panelH) {
        if (sheetBehavior == null || getContext() == null || panelH <= 0) return;
        int screenH = getContext().getResources().getDisplayMetrics().heightPixels;
        sheetBehavior.setPeekHeight(panelH);
        sheetBehavior.setExpandedOffset(Math.max(0, screenH - panelH));
        sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
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
        if (binding == null || adapter == null) return;
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
        binding.tvHint.setVisibility(View.VISIBLE);
        binding.btnClear.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        updateSheetHeight();
    }

    /**
     * 来源行（GZ Q02）：优先读入队时写的队列级来源／route（B7：MusicQueue.source／route，
     * 榜单／随心听／列表页起播时写入，断点恢复时同存同取）；老断点队列无此信息时回落旧反查口径。
     * D14：route 非空时一律可点回来源页——可解析的跳落点，认不出的 route 不静默隐藏 › 且不可点，
     * 点了给提示；无 route 时只展示、不可点、无 ›（与 GZ 无 route 形态一致）。标签空时兜底“未知来源”。
     */
    private void bindSourceRow() {
        String label = MusicController.get().queueSource();
        String route = MusicController.get().queueRoute();
        // T1：源对象存在即显示——队列级来源非空＝存在；否则回落“全队一致”（平台标签一致＝存在），
        // 标签空不等于无来源：一致但标签空时显示“未知来源”，不再整行隐藏；真正无来源（不一致／空队）才隐藏。
        // D14 兜底补全：标签查不到（平台未知）时也按“全队同平台”算一致，标签空走未知来源，不整行隐藏。
        boolean sourceKnown = label != null && !label.isEmpty();
        if (!sourceKnown) {
            boolean labelUniform = !tracks.isEmpty();
            boolean platformUniform = !tracks.isEmpty();
            String common = null;
            String commonPlatform = null;
            String firstLabel = null;
            for (MusicTrack t : tracks) {
                String l = sourceLabel(t);
                String plat = t == null || t.platform == null ? "" : t.platform;
                if (common == null && l != null) common = l;
                else if (l == null || !l.equals(common)) labelUniform = false;
                if (commonPlatform == null) {
                    commonPlatform = plat;
                    firstLabel = l;
                } else if (!plat.equals(commonPlatform)) {
                    platformUniform = false;
                }
            }
            if (labelUniform && common != null) {
                sourceKnown = true;
                label = common;
            } else if (platformUniform) {
                sourceKnown = true;
                label = firstLabel;
            }
            route = null;
        }
        if (!sourceKnown) {
            binding.sourceRow.setVisibility(View.GONE);
            return;
        }
        if (label == null || label.isEmpty()) label = getString(R.string.music_unknown_source);
        binding.sourceName.setText(label);
        android.content.Intent intent = queueSourceIntent(route, MusicController.get().queueRouteParams());
        android.view.View chevron = binding.sourceRow.findViewWithTag("queueSourceChevron");
        if (chevron == null) {
            // GZ 来源行尾标是 18dp 矢量 › 图标（PlayQueueSheet: zh.v()，内容描述“查看来源”），不是文字 ›；
            // 星辰原先用 16sp 文字 ›，改成 18dp 矢量图标＋低色 tint，与 GZ 同形同色。
            ImageView cv = new ImageView(getContext());
            cv.setTag("queueSourceChevron");
            cv.setImageResource(R.drawable.ic_player_chevron_right);
            cv.setImageTintList(ColorStateList.valueOf(COLOR_LOW));
            int s = Math.round(18 * getContext().getResources().getDisplayMetrics().density);
            binding.sourceRow.addView(cv, new ViewGroup.LayoutParams(s, s));
            chevron = cv;
        }
        // D14：route 非空时一律可点——落点可解析就跳，认不出／参数不全时点了给提示，不静默隐藏 › 且不可点。
        // 无 route 时才是 GZ 无 route 形态：无 ›、不可点（intent 恒为空，走本分支的 else）。
        if ((route != null && !route.isEmpty()) || intent != null) {
            chevron.setVisibility(View.VISIBLE);
            binding.sourceRow.setClickable(true);
            binding.sourceRow.setOnClickListener(v -> {
                if (intent != null) {
                    FragmentActivity host = getActivity();
                    dismissAllowingStateLoss();
                    if (host != null) host.startActivity(intent);
                } else {
                    Notify.show("该来源暂无法直接打开");
                }
            });
        } else {
            chevron.setVisibility(View.GONE);
            binding.sourceRow.setOnClickListener(null);
            binding.sourceRow.setClickable(false);
        }
        binding.sourceRow.setVisibility(View.VISIBLE);
    }

    /**
     * B7／D14：route → 来源页落点。树内入队来源的落点表（只改本对话框，不动入队侧）：
     * home＝首页（榜单卡／随心听／心情电台都在首页）；list＝列表页（参数与 MusicListActivity onCreate 读的
     * intent extras 同名同值，可重建本页；无标题／关键词／歌单 id 三者时无落点，不跳空页，调用方给提示）；
     * 其余非空 route（树内目前没有第三条入队 route，未来入队侧新增 route 时先在这里补落点再发）
     * 返回空，调用方按 D14 给提示，不静默隐藏；route 空＝无跳转落点（云盘／无来源入队等），只展示。
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
            // 删错行修：同歌两份时按键删只删第一个命中，点第二份会删掉第一份。
            // 本行位置就是服务队列序号（点歌 playAt(position) 同口径）：点击时取当前
            // 绑定位置，先核对服务队列该位置的 key 与本行一致才按序号删；位置已漂移
            // （bind 后队列变了）或取不到位置时回落按键删，不删错行。共享层
            // removeByKey 本身不动（另一工调用口径：按 key 删，方法名定死）。
            h.btnRemove.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    try {
                        java.util.List<MusicTrack> cur = MusicController.get().queue();
                        if (cur != null && pos >= 0 && pos < cur.size()
                                && MusicQueue.trackKey(cur.get(pos)).equals(MusicQueue.trackKey(t))) {
                            MusicController.get().removeAt(pos);
                            refresh();
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                MusicController.get().removeByKey(MusicQueue.trackKey(t));
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
