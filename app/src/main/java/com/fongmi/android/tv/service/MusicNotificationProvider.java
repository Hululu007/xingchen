package com.fongmi.android.tv.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicLyricCache;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.utils.Notify;
import com.google.common.collect.ImmutableList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自绘音乐通知（2026-10-24 定案：通知栏／锁屏／灵动岛都走自绘，不用系统模板卡）：
 * 通知面是自家布局（深烟熏玻璃卡：封面、歌名歌手、三键、细进度），锁屏公开可见
 * 同一张。自绘归自绘，媒体身份必须齐：通知要挂 MediaStyle 并带上会话
 * （MediaStyleNotificationHelper 把 session 的 legacy token 塞进通知）、用
 * ActionFactory 补真通知动作、标题/歌手/大封面照常填——系统与荣耀灵动岛、锁屏
 * 音乐界面正是按「MediaStyle＋有效 MediaSession token」认领媒体通知的，只挂
 * DecoratedCustomViewStyle 的自绘卡在系统眼里只是普通自定义通知，岛与锁屏全丢
 * （1.4.43 上线实证，此前类头误写「本类不碰」是根源）。自绘卡内按钮另走显式
 * 服务命令（ACTION_* → MusicPlaybackService.onStartCommand 路由到
 * toggle/next/prev），与通知动作双路同义并存。封面位图异步取（http 直连／
 * content 走解析器），取到后经保存的 Callback 重发一版带封面的通知
 * （同 media3 默认 provider 的做法）。
 * 展开大卡带歌词面（《灵动歌词》演示定案，2026-10-24 他点「按他这个做」）：
 * 当前句原词（加粗白字）＋紧贴其下小一号翻译＋下一句同结构压暗；锁屏就是同一张
 * 通知的收起/展开两态（收起只有播放卡、点开才出歌词卡），不另做锁屏页。词从
 * 播放页同源取（MusicLyricCache＋LyricsParser＋RichLyrics.align，不另造管线），
 * 刷新只跟换行走：按下一行行首时刻定点唤醒＋播放器事件兜底，行没变绝不重发；
 * 没词／纯文本无时间轴／当前行还没到，歌词组整组不露，不摆空壳。
 */
public class MusicNotificationProvider implements MediaNotification.Provider {

    public static final String ACTION_TOGGLE = "com.fongmi.android.tv.music.NOTIF_TOGGLE";
    public static final String ACTION_NEXT = "com.fongmi.android.tv.music.NOTIF_NEXT";
    public static final String ACTION_PREV = "com.fongmi.android.tv.music.NOTIF_PREV";

    private final Context context;
    private final int notificationId;
    private final PendingIntent contentIntent;
    private final ExecutorService coverExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, Bitmap> coverCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> coverLoading = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile MediaSession lastSession;
    private volatile Callback lastCallback;
    private volatile MediaNotification.ActionFactory lastFactory;
    private volatile ImmutableList<androidx.media3.session.CommandButton> lastButtons = ImmutableList.of();

    /** 当前歌供给：服务注入（queue.current()），provider 据此读歌词缓存，不自己认歌。 */
    public interface TrackSupplier {
        MusicTrack currentTrack();
    }

    private volatile TrackSupplier trackSupplier;

    // 歌词面状态（只在主线程读写）：已解析的缓存实例与曲目键、展示行（滤空行后）、
    // 当前已发出的歌词面签名（MIN_VALUE＝无词面；否则＝当前行下标，-1＝词已到但还没唱到首行）
    private MusicLyric parsedLyric;
    private String parsedTrackKey;
    private List<RichLyrics.Line> lyricLines;
    private int shownFaceSignature = Integer.MIN_VALUE;
    private Player attachedPlayer;
    private Bitmap circleSrc;
    private Bitmap circleOut;

    private final Runnable lyricTick = () -> {
        try {
            evaluateLyricFace();
        } catch (Throwable ignored) {
        }
    };

    private final Player.Listener lyricListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            mainHandler.post(lyricTick);
        }

        @Override
        public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
            mainHandler.post(lyricTick);
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying) mainHandler.post(lyricTick);
            else mainHandler.removeCallbacks(lyricTick);
        }
    };

    public void setTrackSupplier(TrackSupplier supplier) {
        this.trackSupplier = supplier;
    }

    /** 服务侧取词到达（仍是当前歌）时叫一声：词面可能从无到有，重新评估一次。 */
    public void onLyricReady() {
        mainHandler.post(lyricTick);
    }

    public MusicNotificationProvider(Context context, int notificationId, PendingIntent contentIntent) {
        this.context = context.getApplicationContext();
        this.notificationId = notificationId;
        this.contentIntent = contentIntent;
        try {
            NotificationManagerCompat.from(this.context).createNotificationChannel(
                    new NotificationChannelCompat.Builder(Notify.MEDIA_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                            .setName("音乐播放").build());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public MediaNotification createNotification(MediaSession mediaSession, ImmutableList<androidx.media3.session.CommandButton> mediaButtonPreferences, MediaNotification.ActionFactory actionFactory, Callback onNotificationChangedCallback) {
        lastSession = mediaSession;
        lastCallback = onNotificationChangedCallback;
        lastFactory = actionFactory;
        lastButtons = mediaButtonPreferences == null ? ImmutableList.of() : mediaButtonPreferences;
        Player player = mediaSession.getPlayer();
        if (player == null || player.getCurrentMediaItem() == null) return null;
        MediaMetadata md = player.getCurrentMediaItem().mediaMetadata;
        String title = md.title == null || md.title.length() == 0 ? "音乐播放" : md.title.toString();
        String artist = md.artist == null ? "" : md.artist.toString();
        boolean playing = player.isPlaying();
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (pos == C.TIME_UNSET) pos = 0;
        String coverUrl = md.artworkUri == null ? null : md.artworkUri.toString();
        Bitmap cover = coverUrl == null ? null : coverCache.get(coverUrl);
        if (coverUrl != null && cover == null) loadCoverAsync(coverUrl);

        attachLyricListener(player);
        ensureLyricLines();
        int faceIdx = lineIndexAt(pos);
        shownFaceSignature = faceSignature(faceIdx);
        LyricFace face = faceAt(faceIdx);
        scheduleLyricTick(player);

        RemoteViews small = buildViews(R.layout.notification_music, title, artist, playing, cover, pos, dur, null);
        RemoteViews big = buildViews(R.layout.notification_music_big, title, artist, playing, cover, pos, dur, face);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, Notify.MEDIA_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setContentTitle(title)
                .setContentText(artist)
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true);
        if (cover != null) builder.setLargeIcon(cover);
        // 媒体身份：MediaStyle 挂会话（token 随通知走）＋真通知动作——岛与锁屏认的是这套，
        // 自绘 RemoteViews 只是通知栏里的脸，两者不冲突（同 media3 官方 provider 的组法）
        androidx.media3.session.MediaStyleNotificationHelper.MediaStyle mediaStyle =
                new androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(mediaSession);
        int[] compact = addMediaActions(builder, mediaSession, actionFactory, playing);
        if (compact.length > 0) mediaStyle.setShowActionsInCompactView(compact);
        builder.setStyle(mediaStyle);
        return new MediaNotification(notificationId, builder.build());
    }

    /** 用 ActionFactory 把按钮偏好翻成真通知动作；偏好为空时按上一首/播放暂停/下一首补三枚。
     * 返回紧凑视图要露的动作下标（按后/中/前槽位取，取不到就按顺序前三枚）。 */
    private int[] addMediaActions(NotificationCompat.Builder builder, MediaSession session,
                                  MediaNotification.ActionFactory actionFactory, boolean playing) {
        java.util.List<Integer> compact = new java.util.ArrayList<>();
        int index = 0;
        if (lastButtons != null && !lastButtons.isEmpty()) {
            int back = -1, central = -1, forward = -1;
            for (androidx.media3.session.CommandButton button : lastButtons) {
                try {
                    if (button.sessionCommand != null) {
                        builder.addAction(actionFactory.createCustomActionFromCustomCommandButton(session, button));
                    } else if (button.playerCommand != Player.COMMAND_INVALID) {
                        builder.addAction(actionFactory.createMediaAction(session,
                                androidx.core.graphics.drawable.IconCompat.createWithResource(context, button.iconResId),
                                button.displayName, button.playerCommand));
                    } else {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                if (button.slots != null && button.slots.length() > 0) {
                    if (button.slots.get(0) == androidx.media3.session.CommandButton.SLOT_BACK && back < 0) back = index;
                    else if (button.slots.get(0) == androidx.media3.session.CommandButton.SLOT_CENTRAL && central < 0) central = index;
                    else if (button.slots.get(0) == androidx.media3.session.CommandButton.SLOT_FORWARD && forward < 0) forward = index;
                }
                index++;
            }
            if (back >= 0) compact.add(back);
            if (central >= 0) compact.add(central);
            if (forward >= 0) compact.add(forward);
            if (compact.isEmpty()) for (int i = 0; i < Math.min(3, index); i++) compact.add(i);
        } else {
            try {
                builder.addAction(actionFactory.createMediaAction(session,
                        androidx.core.graphics.drawable.IconCompat.createWithResource(context, R.drawable.ic_notif_prev),
                        "上一首", Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM));
                builder.addAction(actionFactory.createMediaAction(session,
                        androidx.core.graphics.drawable.IconCompat.createWithResource(context, playing ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play),
                        playing ? "暂停" : "播放", Player.COMMAND_PLAY_PAUSE));
                builder.addAction(actionFactory.createMediaAction(session,
                        androidx.core.graphics.drawable.IconCompat.createWithResource(context, R.drawable.ic_notif_next),
                        "下一首", Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM));
                compact.add(0);
                compact.add(1);
                compact.add(2);
            } catch (Throwable ignored) {
                compact.clear();
            }
        }
        int[] result = new int[compact.size()];
        for (int i = 0; i < compact.size(); i++) result[i] = compact.get(i);
        return result;
    }

    private RemoteViews buildViews(int layout, String title, String artist, boolean playing, Bitmap cover, long pos, long dur, LyricFace face) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setTextViewText(R.id.notifTitle, title);
        views.setTextViewText(R.id.notifArtist, artist);
        if (cover != null) {
            // 大卡封面按演示摆法是圆的（小卡维持圆角方，同定案脸）
            views.setImageViewBitmap(R.id.notifCover,
                    layout == R.layout.notification_music_big ? circleCover(cover) : cover);
        }
        views.setImageViewResource(R.id.notifPlay, playing ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play);
        views.setOnClickPendingIntent(R.id.notifPrev, commandIntent(ACTION_PREV, 41));
        views.setOnClickPendingIntent(R.id.notifPlay, commandIntent(ACTION_TOGGLE, 42));
        views.setOnClickPendingIntent(R.id.notifNext, commandIntent(ACTION_NEXT, 43));
        if (layout == R.layout.notification_music_big) {
            long safeDur = dur == C.TIME_UNSET || dur < 0 ? 0 : dur;
            views.setProgressBar(R.id.notifProgress, (int) Math.min(safeDur, Integer.MAX_VALUE), (int) Math.min(pos, Integer.MAX_VALUE), false);
            views.setTextViewText(R.id.notifDuration, formatTime(safeDur));
            views.setChronometer(R.id.notifElapsed, SystemClock.elapsedRealtime() - Math.max(pos, 0), null, playing);
            if (!playing) views.setTextViewText(R.id.notifElapsed, formatTime(pos));
            if (face == null) {
                views.setViewVisibility(R.id.notifLyricGroup, View.GONE);
            } else {
                views.setViewVisibility(R.id.notifLyricGroup, View.VISIBLE);
                views.setTextViewText(R.id.notifLyricCur, face.cur);
                setTextOrGone(views, R.id.notifLyricCurTrans, face.curTrans);
                setTextOrGone(views, R.id.notifLyricNext, face.next);
                setTextOrGone(views, R.id.notifLyricNextTrans, face.nextTrans);
            }
        }
        return views;
    }

    private static void setTextOrGone(RemoteViews views, int id, String text) {
        if (text == null || text.isEmpty()) {
            views.setViewVisibility(id, View.GONE);
        } else {
            views.setViewVisibility(id, View.VISIBLE);
            views.setTextViewText(id, text);
        }
    }

    /** 大卡要展示的歌词面：当前句原词＋翻译、下一句原词＋翻译（缺哪项哪项为空、视图侧藏掉）。 */
    private static final class LyricFace {
        String cur;
        String curTrans;
        String next;
        String nextTrans;
    }

    private void attachLyricListener(Player player) {
        if (player == attachedPlayer) return;
        if (attachedPlayer != null) {
            try {
                attachedPlayer.removeListener(lyricListener);
            } catch (Throwable ignored) {
            }
        }
        attachedPlayer = player;
        if (player != null) {
            try {
                player.addListener(lyricListener);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把当前歌的词按播放页同口径备好（缓存→正文解析→翻译对齐→滤空行）。
     * 缓存实例没换就直接用上次的解析，不重复跑；纯文本无时间轴（多行全 0 时刻）
     * 同步不了当前行，整组不露——不拿第一行冒充当前句。
     */
    private void ensureLyricLines() {
        MusicTrack track = null;
        try {
            TrackSupplier supplier = trackSupplier;
            if (supplier != null) track = supplier.currentTrack();
        } catch (Throwable ignored) {
        }
        if (track == null) {
            parsedLyric = null;
            parsedTrackKey = null;
            lyricLines = null;
            return;
        }
        String key = MusicLyricCache.key(track);
        MusicLyric lyric = null;
        try {
            lyric = MusicLyricCache.get(track);
        } catch (Throwable ignored) {
        }
        if (lyric == parsedLyric && (key == null ? parsedTrackKey == null : key.equals(parsedTrackKey))) return;
        parsedLyric = lyric;
        parsedTrackKey = key;
        lyricLines = null;
        if (lyric == null || !lyric.hasLyric()) return;
        try {
            List<LyricsLine> main = LyricsParser.parseWordLyric(lyric.rawLrc);
            if (main == null || main.isEmpty()) return;
            List<LyricsLine> trans = TextUtils.isEmpty(lyric.translation)
                    ? null : LyricsParser.parseTimed(lyric.translation);
            List<RichLyrics.Line> aligned = RichLyrics.align(main, trans, null);
            List<RichLyrics.Line> display = new ArrayList<>();
            for (RichLyrics.Line line : aligned) {
                if (line != null && line.main != null && !line.main.getText().isEmpty()) display.add(line);
            }
            if (display.isEmpty()) return;
            boolean anyTimed = false;
            for (RichLyrics.Line line : display) {
                if (line.main.getTimeMs() > 0) {
                    anyTimed = true;
                    break;
                }
            }
            if (!anyTimed && display.size() > 1) return;
            lyricLines = display;
        } catch (Throwable ignored) {
            lyricLines = null;
        }
    }

    /** 当前行下标：最后一行行首时刻 ≤ pos 的行；还没唱到首行回 -1。展示行已按时间升序。 */
    private int lineIndexAt(long pos) {
        if (lyricLines == null) return -1;
        int idx = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i).main.getTimeMs() <= pos) idx = i;
            else break;
        }
        return idx;
    }

    private int faceSignature(int faceIdx) {
        return lyricLines == null ? Integer.MIN_VALUE : faceIdx;
    }

    private LyricFace faceAt(int idx) {
        if (lyricLines == null || idx < 0) return null;
        RichLyrics.Line cur = lyricLines.get(idx);
        LyricFace face = new LyricFace();
        face.cur = cur.main.getText();
        face.curTrans = emptyToNull(cur.translation);
        if (idx + 1 < lyricLines.size()) {
            RichLyrics.Line next = lyricLines.get(idx + 1);
            face.next = next.main.getText();
            face.nextTrans = emptyToNull(next.translation);
        }
        return face;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * 换行评估：词面签名变了才重发（同 id 原地替换，同封面到达那条路），
     * 没变只续排下一次唤醒。播放器事件与定点唤醒共用这一口，不逐帧、不轮询刷通知。
     */
    private void evaluateLyricFace() {
        Player player = attachedPlayer;
        if (player == null) return;
        ensureLyricLines();
        long pos = player.getCurrentPosition();
        if (pos == C.TIME_UNSET) pos = 0;
        int signature = faceSignature(lineIndexAt(pos));
        if (signature != shownFaceSignature) {
            repostNotification();
            return;
        }
        scheduleLyricTick(player);
    }

    /** 下一次唤醒排在下一行行首（上限 5 秒回看一次防位置漂移）；暂停/无词/唱到末行就不排。 */
    private void scheduleLyricTick(Player player) {
        mainHandler.removeCallbacks(lyricTick);
        if (player == null || lyricLines == null) return;
        try {
            if (!player.isPlaying()) return;
            long pos = player.getCurrentPosition();
            if (pos == C.TIME_UNSET) pos = 0;
            int idx = lineIndexAt(pos);
            if (idx + 1 >= lyricLines.size()) return;
            long delay = lyricLines.get(idx + 1).main.getTimeMs() - pos;
            if (delay < 150) delay = 150;
            if (delay > 5000) delay = 5000;
            mainHandler.postDelayed(lyricTick, delay);
        } catch (Throwable ignored) {
        }
    }

    /** 同封面到达的重发路：拿保存的会话与回调重建一版通知、原地替换。 */
    private void repostNotification() {
        MediaSession session = lastSession;
        Callback callback = lastCallback;
        if (session == null || callback == null) return;
        MediaNotification again = createNotification(session, lastButtons, lastFactory, callback);
        if (again != null) callback.onNotificationChanged(again);
    }

    /** 圆封面（大卡用）：源位图已是居中裁方，这里只罩圆；按源实例缓存一份，换行重发不重算。 */
    private Bitmap circleCover(Bitmap src) {
        if (src == circleSrc && circleOut != null) return circleOut;
        int size = Math.min(src.getWidth(), src.getHeight());
        if (size <= 0) return src;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = size / 2f;
        canvas.drawCircle(r, r, r, paint);
        circleSrc = src;
        circleOut = out;
        return out;
    }

    private PendingIntent commandIntent(String action, int requestCode) {
        Intent intent = new Intent(context, MusicPlaybackService.class).setAction(action);
        return PendingIntent.getService(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public boolean handleCustomCommand(MediaSession session, String action, Bundle extras) {
        return false;
    }

    @Override
    public NotificationChannelInfo getNotificationChannelInfo() {
        return new NotificationChannelInfo(Notify.MEDIA_CHANNEL_ID, "音乐播放");
    }

    private void loadCoverAsync(String url) {
        if (coverLoading.putIfAbsent(url, Boolean.TRUE) != null) return;
        coverExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = fetchCover(url);
            } catch (Throwable ignored) {
            }
            coverLoading.remove(url);
            if (bitmap == null) return;
            // 缓存只留最近几张，免得长队列听下来内存涨
            if (coverCache.size() > 4) coverCache.clear();
            coverCache.put(url, bitmap);
            mainHandler.post(() -> {
                MediaSession session = lastSession;
                Callback callback = lastCallback;
                if (session == null || callback == null) return;
                Player player = session.getPlayer();
                if (player == null || player.getCurrentMediaItem() == null) return;
                Uri current = player.getCurrentMediaItem().mediaMetadata.artworkUri;
                if (current == null || !url.equals(current.toString())) return;
                MediaNotification again = createNotification(session, lastButtons, lastFactory, callback);
                if (again != null) callback.onNotificationChanged(again);
            });
        });
    }

    private Bitmap fetchCover(String url) throws Exception {
        InputStream in = null;
        try {
            if (url.startsWith("content://")) {
                in = context.getContentResolver().openInputStream(Uri.parse(url));
            } else if (url.startsWith("http")) {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.connect();
                if (conn.getResponseCode() / 100 != 2) return null;
                in = conn.getInputStream();
            } else {
                return null;
            }
            if (in == null) return null;
            Bitmap raw = BitmapFactory.decodeStream(in);
            if (raw == null) return null;
            return roundCover(raw, 256, 28);
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 居中裁方＋圆角：通知里封面是圆角小图，直角原图贴上去四角会戳出卡面 */
    private static Bitmap roundCover(Bitmap raw, int size, int radius) {
        int w = raw.getWidth(), h = raw.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2, y = (h - side) / 2;
        Bitmap square = Bitmap.createBitmap(raw, x, y, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(0, 0, size, size), radius, radius, paint);
        return out;
    }

    private static String formatTime(long ms) {
        if (ms <= 0) return "0:00";
        long totalSec = ms / 1000;
        long hour = totalSec / 3600;
        long min = (totalSec % 3600) / 60;
        long sec = totalSec % 60;
        if (hour > 0) return hour + ":" + two(min) + ":" + two(sec);
        return min + ":" + two(sec);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
