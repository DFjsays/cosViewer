package com.dsh.cosdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 前台服务：保证离开页面/切后台后下载继续跑。 */
public class DownloadService extends Service {

    private static final String CHANNEL = "cosdl_download";
    private static final int NOTIF_ID = 2001;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            TaskManager tm = TaskManager.get(DownloadService.this);
            if (!tm.hasActive()) {
                stopForeground(true);
                stopSelf();
                return;
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(tm));
            handler.postDelayed(tick, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "视频下载",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(TaskManager tm) {
        java.util.List<DownloadTask> active = tm.activeTasks();
        DownloadTask running = null;
        int queued = 0;
        for (DownloadTask t : active) {
            if (DownloadTask.RUNNING.equals(t.status)) running = t;
            else if (DownloadTask.QUEUED.equals(t.status)) queued++;
        }
        String title;
        String text;
        int progress = 0;
        if (running != null) {
            title = "正在下载：" + shortTitle(running.title);
            StringBuilder sb = new StringBuilder();
            sb.append(Downloader.human(running.done));
            if (running.total > 0) {
                sb.append(" / ").append(Downloader.human(running.total));
                progress = running.percent();
            }
            if (running.speed > 0) sb.append(" · ").append(Downloader.human(running.speed)).append("/s");
            if (queued > 0) sb.append(" · 队列 ").append(queued);
            text = sb.toString();
        } else {
            title = "下载队列等待中";
            text = "共 " + active.size() + " 个任务";
        }

        Intent i = new Intent(this, DownloadsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        if (progress > 0) {
            b.setProgress(100, progress, false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private String shortTitle(String s) {
        if (s == null) return "";
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TaskManager tm = TaskManager.get(this);
        startForeground(NOTIF_ID, buildNotification(tm));
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
