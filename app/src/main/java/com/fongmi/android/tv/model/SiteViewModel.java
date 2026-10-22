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
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class SiteViewModel extends ViewModel {

    private final MutableLiveData<Result> result;
    private final MutableLiveData<Result> search;
    private final MutableLiveData<SearchProgress> searchProgress;
    private final MutableLiveData<Result> action;

    private final List<Future<?>> searchFuture;
    private final AtomicInteger searchEpoch;
    private KaraokeResult karaokeResult;
    private int karaokeResultAction;

    public SiteViewModel() {
        result = new MutableLiveData<>();
        search = new MutableLiveData<>();
        searchProgress = new MutableLiveData<>();
        action = new MutableLiveData<>();
        searchEpoch = new AtomicInteger(0);
        searchFuture = new CopyOnWriteArrayList<>();
    }

    public LiveData<Result> getResult() {
        return result;
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
        // 冷启动约 200ms 内 viewmodel execute failed type=RESULT site=（空）、URL 为空：
        // 未配置首页源时 home 是空 Site（getHome() 永不返回 null，空 Site 的 key/api 都是 ""），
        // SiteApi.homeContent 一路走到 OkHttp.newCall("") 才抛 no-scheme。execute 入口的空 key
        // 拦截只看得到 key，看不到 api，所以这里先把 home 为 null／key 为空／api 为空三
        // 种情况都判出来：siteKey 传 "" 走 execute 的空 key 拦截，可见错由 execute 统一发，
        // 不再把空 key/api 的 lambda 交给 SiteApi（lambda 在拦截下根本不会被执行）。
        boolean homeBad = home == null || home.getKey().isEmpty() || home.getApi().isEmpty();
        // 冷启动第一次 py 首页加载：外层给 90 秒预算（冷链：蜘蛛首次加载＋依赖下载＋站点取数），
        // 热了之后一律 30 秒不动。预算由 SiteApi.homeTimeoutFor 按冷热判定。
        execute(TaskType.RESULT, result, () -> SiteApi.homeContent(home, forceRefresh), null,
                // 超时/被取消：外层只给了空列表，子任务还在池里继续跑、继续打站点，
                // 用户一重试就再叠一层——连带作废。正常慢加载（没超时没取消）不受影响。
                error -> {
                    if (error instanceof TimeoutException || error instanceof CancellationException)
                        SiteApi.cancelHomeLoads();
                },
                SiteApi.homeTimeoutFor(home), homeBad ? "" : home.getKey());
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
        // 上游形（e5ec0c60f 对照）：没有 PLAYER 类型、没有 taskId／futures 取消环、
        // 没有按 taskId 丢弃旧结果——任务只跑、结果只投递，错误只投 Result.error。
        // 播放结果不再走这里：VideoActivity 直接起播路（playDirect）跑 SiteApi.playerContent。
        // 空 key 执行前拦截是 fork 保留项（冷启动空源不进线程池）：key 为空＝没有可用的源，
        // 绝不把 lambda 交给线程池，否则 SiteApi 里空 Site 一路走到 OkHttp.newCall("") 才抛 no-scheme。
        if (siteKey == null || siteKey.isEmpty()) {
            SpiderDebug.log("viewmodel", "execute rejected type=%s site empty: no home source configured", type);
            liveData.postValue(Result.error("未配置首页源"));
            return;
        }
        FluentFuture<Result> future = FluentFuture.from(Task.executor().submit(callable)).withTimeout(timeoutMs, TimeUnit.MILLISECONDS, Task.scheduler());
        future.addCallback(Task.callback(
                result -> {
                    if (onSuccess != null) onSuccess.accept(result);
                    liveData.postValue(result);
                },
                error -> {
                    if (onError != null) onError.accept(error);
                    SpiderDebug.log("viewmodel", "execute failed type=%s site=%s error=%s", type, siteKey, error);
                    // 上游形：所有错误都投 Result.error，不吞成空结果；RESULT 再补一条 Notify。
                    String msg;
                    if (error instanceof ExtractException) msg = error.getMessage();
                    else if (error instanceof TimeoutException) msg = ResUtil.getString(R.string.error_play_timeout);
                    else if (error instanceof SiteApi.ColdHomeTimeoutException) msg = error.getMessage();
                    else msg = error.getMessage() != null ? error.getMessage() : "播放失败，请重试";
                    if (type == TaskType.RESULT) Notify.show(msg);
                    liveData.postValue(Result.error(msg));
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
    }

    private enum TaskType {RESULT, ACTION}
}
