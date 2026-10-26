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
import android.widget.RemoteViews;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.google.common.collect.ImmutableList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自绘音乐通知（2026-10-24 定案：通知栏／锁屏／灵动岛都走自绘，不用系统模板卡）：
 * 通知面是自家布局（深烟熏玻璃卡：封面、歌名歌手、三键、细进度），锁屏公开可见
 * 同一张；灵动岛那侧靠会话元数据喂料（歌名／歌手／封面在 MediaItem 元数据里，
 * 本类不碰）。按钮走显式服务命令（ACTION_* → MusicPlaybackService.onStartCommand
 * 路由到 toggle/next/prev）。封面位图异步取（http 直连／content 走解析器），
 * 取到后经保存的 Callback 重发一版带封面的通知（同 media3 默认 provider 的做法）。
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
    private volatile ActionFactory lastFactory;
    private volatile ImmutableList<androidx.media3.session.CommandButton> lastButtons = ImmutableList.of();

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
    public MediaNotification createNotification(MediaSession mediaSession, ImmutableList<androidx.media3.session.CommandButton> mediaButtonPreferences, ActionFactory actionFactory, Callback onNotificationChangedCallback) {
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

        RemoteViews small = buildViews(R.layout.notification_music, title, artist, playing, cover, pos, dur);
        RemoteViews big = buildViews(R.layout.notification_music_big, title, artist, playing, cover, pos, dur);

        Notification notification = new NotificationCompat.Builder(context, Notify.MEDIA_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .build();
        return new MediaNotification(notificationId, notification);
    }

    private RemoteViews buildViews(int layout, String title, String artist, boolean playing, Bitmap cover, long pos, long dur) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setTextViewText(R.id.notifTitle, title);
        views.setTextViewText(R.id.notifArtist, artist);
        if (cover != null) views.setImageViewBitmap(R.id.notifCover, cover);
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
        }
        return views;
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
