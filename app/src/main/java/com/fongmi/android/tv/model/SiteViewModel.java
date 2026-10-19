package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.extractor.Thunder;
import com.fongmi.android.tv.player.karaoke.KaraokeResult;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class SiteViewModel extends ViewModel {

    private final MutableLiveData<Result> result;
    private final MutableLiveData<Result> player;
    private final MutableLiveData<Result> search;
    private final MutableLiveData<SearchProgress> searchProgress;
    private final MutableLiveData<Result> action;

    private final Map<TaskType, ListenableFuture<?>> futures;
    private final Map<TaskType, AtomicInteger> taskIds;
    private final List<Future<?>> searchFuture;
    private final ListeningExecutorService playerExecutor;
    private final AtomicInteger searchEpoch;
    private KaraokeResult karaokeResult;
    private int karaokeResultAction;

    public SiteViewModel() {
        result = new MutableLiveData<>();
        player = new MutableLiveData<>();
        search = new MutableLiveData<>();
        searchProgress = new MutableLiveData<>();
        action = new MutableLiveData<>();
        searchEpoch = new AtomicInteger(0);
        searchFuture = new CopyOnWriteArrayList<>();
        // Player spiders can share a loopback proxy and may ignore interruption.
        // Keep resolutions serial so a canceled source fully exits before the next starts.
        playerExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        futures = new EnumMap<>(TaskType.class);
        taskIds = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) taskIds.put(type, new AtomicInteger(0));
    }

    public LiveData<Result> getResult() {
        return result;
    }

    public LiveData<Result> getPlayer() {
        return player;
    }

    public LiveData<Result> getSearch() {
        return search;
    }

    public LiveData<SearchProgress> getSearchProgress() {
        return searchProgress;
    }

    public LiveData<Result> getAction() {
        return action;
    }

    public KaraokeResult getKaraokeResult() {
        return karaokeResult;
    }

    public int getKaraokeResultAction() {
        return karaokeResultAction;
    }

    public void setKaraokeResult(KaraokeResult result, int action) {
        karaokeResult = result;
        karaokeResultAction = action;
    }

    public void clearKaraokeResult() {
        karaokeResult = null;
        karaokeResultAction = 0;
    }

    public SiteViewModel init() {
        search.setValue(null);
        searchProgress.setValue(null);
        result.setValue(null);
        player.setValue(null);
        action.setValue(null);
        return this;
    }

    public void homeContent() {
        homeContent(false);
    }

    public void homeContent(boolean forceRefresh) {
        com.fongmi.android.tv.bean.Site home = VodConfig.get().getHome();
        // 作废旧首页任务只留给家页取数：进详情/分类/搜索不再掐首页加载。
        // 否则慢网下进一次详情、返回首页就"打不开"（1.3.30 回归）；切源时新源的
        // homeContent 同样会作废旧源任务，原"切源不等旧源"意图不受影响。
        SiteApi.cancelHomeLoads();
        // 冷启动第一次 py 首页加载：外层给 90 秒预算（冷链：蜘蛛首次加载＋依赖下载＋站点取数），
        // 热了之后一律 30 秒不动。预算由 SiteApi.homeTimeoutFor 按冷热判定。
        execute(TaskType.RESULT, result, () -> SiteApi.homeContent(home, forceRefresh), null,
                // 超时/被取消：外层只给了空列表，子任务还在池里继续跑、继续打站点，
                // 用户一重试就再叠一层——连带作废。正常慢加载（没超时没取消）不受影响。
                error -> {
                    if (error instanceof TimeoutException || error instanceof CancellationException)
                        SiteApi.cancelHomeLoads();
                },
                SiteApi.homeTimeoutFor(home), home == null ? "" : home.getKey());
    }

    public void categoryContent(String key, String tid, String page, boolean filter, HashMap<String, String> extend) {
        execute(TaskType.RESULT, result, () -> SiteApi.categoryContent(key, tid, page, filter, extend), null, null, Constant.TIMEOUT_VOD, key);
    }

    public void action(String key, String act) {
        execute(TaskType.ACTION, action, () -> SiteApi.action(key, act), null, null, Constant.TIMEOUT_VOD, key);
    }

    public void detailContent(String key, String id) {
        detailContent(key, id, false);
    }

    /**
     * 详情页轻量取数：跳过 Source.parse 的选集展开（磁力 DHT 最长 60 秒），
     * 主数据一到界面先画线路/选集，展开由界面层在后台跑完再补。播放链不受影响。
     */
    public void detailContentLight(String key, String id) {
        execute(TaskType.RESULT, result, () -> SiteApi.detailContent(key, id, false, false), null, null, Constant.TIMEOUT_VOD, key);
    }

    public void detailContent(String key, String id, boolean forceRefresh) {
        // 推送/磁力详情要在 Source.parse 里拉种子元数据（parse 侧上限 60 秒），
        // 外层沿用 30 秒会先把任务掐掉——首次粘贴、DHT 稍慢必失败。对齐到 75 秒。
        boolean p2p = SiteApi.PUSH.equals(key) || (id != null && Thunder.Parser.match(id));
        execute(TaskType.RESULT, result, () -> SiteApi.detailContent(key, id, forceRefresh), null, null,
                p2p ? Constant.TIMEOUT_PUSH_DETAIL : Constant.TIMEOUT_VOD, key);
    }

    public void playerContent(String key, String flag, String id) {
        execute(TaskType.PLAYER, player, () -> SiteApi.playerContent(key, flag, id), null, null, Constant.TIMEOUT_VOD, key);
    }

    public void searchContent(Site site, String keyword, boolean quick, String page) {
        long start = System.currentTimeMillis();
        execute(TaskType.RESULT, result, SearchTask.create(site, keyword, quick, page),
                result -> SiteHealthStore.recordSearch(site, true, result.getList().size(), System.currentTimeMillis() - start, ""),
                error -> SiteHealthStore.recordSearch(site, false, 0, System.currentTimeMillis() - start, error.getMessage()),
                Constant.TIMEOUT_VOD, site == null ? "" : site.getKey());
    }

    public void searchContent(List<Site> sites, String keyword, boolean quick) {
        int epoch = stopSearch();
        List<Site> tasks = new ArrayList<>();
        for (Site site : sites) {
            if (quick && !site.isQuickSearch()) continue;
            tasks.add(site);
        }
        int total = tasks.size();
        AtomicInteger completed = new AtomicInteger();
        searchProgress.postValue(SearchProgress.start(total));
        for (Site site : tasks) {
            long start = System.currentTimeMillis();
            FluentFuture<Result> future = FluentFuture.from(Task.largeExecutor().submit(SearchTask.create(site, keyword, quick))).withTimeout(Constant.TIMEOUT_SEARCH, TimeUnit.MILLISECONDS, Task.scheduler());
            searchFuture.add(future);
            future.addCallback(Task.callback(
                    result -> {
                        if (searchEpoch.get() != epoch) return;
                        SiteHealthStore.recordSearch(site, true, result.getList().size(), System.currentTimeMillis() - start, "");
                        postSearchResult(epoch, result);
                        postSearchProgress(epoch, completed, total);
                    },
                    error -> {
                        if (searchEpoch.get() != epoch) return;
                        if (error instanceof CancellationException) return;
                        SiteHealthStore.recordSearch(site, false, 0, System.currentTimeMillis() - start, error.getMessage());
                        postSearchProgress(epoch, completed, total);
                        error.printStackTrace();
                    }
            ), MoreExecutors.directExecutor());
        }
    }

    private void postSearchResult(int epoch, Result result) {
        App.post(() -> {
            if (searchEpoch.get() == epoch) search.setValue(result);
        });
    }

    private void postSearchProgress(int epoch, AtomicInteger completed, int total) {
        if (searchEpoch.get() != epoch) return;
        searchProgress.postValue(SearchProgress.of(completed.incrementAndGet(), total));
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable) {
        execute(type, liveData, callable, null, null);
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable, Consumer<Result> onSuccess, Consumer<Throwable> onError) {
        execute(type, liveData, callable, onSuccess, onError, Constant.TIMEOUT_VOD);
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable, Consumer<Result> onSuccess, Consumer<Throwable> onError, long timeoutMs) {
        execute(type, liveData, callable, onSuccess, onError, timeoutMs, "");
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable, Consumer<Result> onSuccess, Consumer<Throwable> onError, long timeoutMs, String siteKey) {
        AtomicInteger taskId = Objects.requireNonNull(taskIds.get(type));
        int currentId = taskId.incrementAndGet();
        ListenableFuture<?> old = futures.get(type);
        if (old != null) old.cancel(true);
        ListeningExecutorService executor = type == TaskType.PLAYER ? playerExecutor : Task.executor();
        FluentFuture<Result> future = FluentFuture.from(executor.submit(callable)).withTimeout(timeoutMs, TimeUnit.MILLISECONDS, Task.scheduler());
        futures.put(type, future);
        future.addCallback(Task.callback(
                result -> {
                    if (taskId.get() != currentId) return;
                    if (onSuccess != null) onSuccess.accept(result);
                    liveData.postValue(result);
                },
                error -> {
                    if (taskId.get() != currentId) return;
                    if (error instanceof CancellationException) return;
                    if (onError != null) onError.accept(error);
                    // 失败原因必须留痕：以前非 ExtractException 一律吞成空结果，界面只剩通用
                    // 提示、调试日志里也没有原文，推送磁力/ed2k 的超时就是这样一直查不到
                    SpiderDebug.log("viewmodel", "execute failed type=%s site=%s error=%s", type, siteKey, error);
                    if (error instanceof ExtractException) liveData.postValue(Result.error(error.getMessage()));
                    else if (type == TaskType.PLAYER && error instanceof TimeoutException) liveData.postValue(Result.error(ResUtil.getString(R.string.error_play_timeout)));
                    // 冷启动首次 py 首页加载超时：给明确错误，不许再吞成空结果。
                    else if (error instanceof SiteApi.ColdHomeTimeoutException) liveData.postValue(Result.error(error.getMessage()));
                    else liveData.postValue(Result.empty());
                    error.printStackTrace();
                }
        ), MoreExecutors.directExecutor());
    }

    public int stopSearch() {
        int epoch = searchEpoch.incrementAndGet();
        searchFuture.forEach(future -> future.cancel(true));
        searchFuture.clear();
        return epoch;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        stopSearch();
        SiteApi.cancelHomeLoads();
        futures.values().forEach(future -> future.cancel(true));
        playerExecutor.shutdownNow();
    }

    private enum TaskType {RESULT, PLAYER, ACTION}
}
