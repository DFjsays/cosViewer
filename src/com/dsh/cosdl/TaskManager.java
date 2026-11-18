package com.dsh.cosdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 全局下载任务队列：串行下载、断点续传、持久化。 */
public final class TaskManager {

    private static final String PREF = "downloads";
    private static final String KEY = "tasks";

    private static TaskManager sInstance;

    public static synchronized TaskManager get(Context c) {
        if (sInstance == null) sInstance = new TaskManager(c.getApplicationContext());
        return sInstance;
    }

    private final Context ctx;
    private final List<DownloadTask> tasks = new ArrayList<DownloadTask>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private DownloadTask current;
    private volatile boolean cancelFlag = false;
    private long lastSave = 0;

    private TaskManager(Context c) {
        ctx = c;
        load();
    }

    public List<DownloadTask> tasks() {
        synchronized (tasks) {
            return new ArrayList<DownloadTask>(tasks);
        }
    }

    public List<DownloadTask> activeTasks() {
        List<DownloadTask> out = new ArrayList<DownloadTask>();
        for (DownloadTask t : tasks()) {
            if (!t.isDone()) out.add(t);
        }
        return out;
    }

    public List<DownloadTask> doneTasks() {
        List<DownloadTask> out = new ArrayList<DownloadTask>();
        for (DownloadTask t : tasks()) {
            if (t.isDone()) out.add(t);
        }
        return out;
    }

    private DownloadTask findInternal(String videoId) {
        for (DownloadTask t : tasks) {
            if (t.videoId.equals(videoId)) return t;
        }
        return null;
    }

    public DownloadTask find(String videoId) {
        synchronized (tasks) {
            return findInternal(videoId);
        }
    }

    public boolean hasActive() {
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (t.isActive()) return true;
            }
        }
        return false;
    }

    /** 新建下载任务（已在队列/已下载则忽略）。 */
    public boolean add(VideoItem item) {
        synchronized (tasks) {
            DownloadTask exist = findInternal(item.id);
            if (exist != null && (exist.isActive() || exist.isDone())) return false;
            if (exist != null) {
                exist.status = DownloadTask.QUEUED;
                exist.error = "";
            } else {
                DownloadTask t = new DownloadTask();
                t.videoId = item.id;
                t.title = item.title;
                t.photo = item.photo;
                t.status = DownloadTask.QUEUED;
                t.createdAt = System.currentTimeMillis();
                tasks.add(0, t);
            }
        }
        save();
        ensureService();
        pump();
        return true;
    }

    public void startAll() {
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (DownloadTask.PAUSED.equals(t.status) || DownloadTask.ERROR.equals(t.status)) {
                    t.status = DownloadTask.QUEUED;
                    t.error = "";
                }
            }
        }
        save();
        ensureService();
        pump();
    }

    public void pauseAll() {
        cancelFlag = true;
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (DownloadTask.QUEUED.equals(t.status)) t.status = DownloadTask.PAUSED;
            }
        }
        save();
    }

    public void pause(DownloadTask t) {
        synchronized (tasks) {
            if (t == current) {
                cancelFlag = true;
            } else if (DownloadTask.QUEUED.equals(t.status)) {
                t.status = DownloadTask.PAUSED;
            }
        }
        save();
    }

    public void resume(DownloadTask t) {
        synchronized (tasks) {
            if (DownloadTask.RUNNING.equals(t.status)) return;
            t.status = DownloadTask.QUEUED;
            t.error = "";
        }
        save();
        ensureService();
        pump();
    }

    public void remove(DownloadTask t) {
        synchronized (tasks) {
            if (t == current) cancelFlag = true;
            tasks.remove(t);
        }
        try {
            File f = t.filePath != null && t.filePath.length() > 0
                    ? new File(t.filePath)
                    : new File(Downloader.downloadDir(), Downloader.fileNameFor(toItem(t)));
            deleteRec(f);
            deleteRec(new File(Downloader.downloadDir(), Downloader.fileNameFor(toItem(t)) + ".parts"));
            deleteRec(new File(Downloader.downloadDir(), Downloader.fileNameFor(toItem(t)) + ".tmp"));
        } catch (Exception ignored) {
        }
        save();
    }

    private void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File x : fs) deleteRec(x);
        }
        f.delete();
    }

    private void ensureService() {
        try {
            ctx.startService(new Intent(ctx, DownloadService.class));
        } catch (Exception ignored) {
        }
    }

    private void pump() {
        DownloadTask next = null;
        synchronized (this) {
            if (current != null) return;
            synchronized (tasks) {
                for (DownloadTask t : tasks) {
                    if (DownloadTask.QUEUED.equals(t.status)) {
                        next = t;
                        break;
                    }
                }
            }
            if (next == null) return;
            current = next;
            cancelFlag = false;
            next.status = DownloadTask.RUNNING;
        }
        save();
        final DownloadTask job = next;
        new Thread(new Runnable() {
            @Override
            public void run() {
                doTask(job);
            }
        }, "dl-" + job.videoId).start();
    }

    private VideoItem toItem(DownloadTask t) {
        VideoItem v = new VideoItem();
        v.id = t.videoId;
        v.title = t.title;
        v.photo = t.photo;
        return v;
    }

    private void doTask(final DownloadTask job) {
        try {
            JSONObject info = Api.videoInfo(job.videoId);
            final List<String> mp4Urls = Api.mp4Urls(info);
            final List<String> hlsUrls = Api.hlsUrls(info);
            String mode = Settings.downloadMode();
            boolean useHls = "hls".equals(mode) || ("auto".equals(mode) && !hlsUrls.isEmpty());
            if (!useHls && mp4Urls.isEmpty()) throw new Exception("没有可用直链");
            final long startMs = System.currentTimeMillis();
            final Downloader.Progress progress = new Downloader.Progress() {
                private long lastUi = 0;
                private long lastBytes = 0;
                private long lastMs = startMs;

                @Override
                public void onProgress(final long done, final long total, final long speedBps) {
                    long now = System.currentTimeMillis();
                    synchronized (this) {
                        if (now - lastUi < 300) return;
                        lastUi = now;
                        if (total > 0 && total < 20000) {
                            // HLS：done/total 是分片个数
                            job.percent = (int) Math.min(100, done * 100 / total);
                            job.speed = speedBps;
                            job.sizeText = "分片 " + done + "/" + total;
                            job.done = done;
                            job.total = total;
                        } else {
                            long sp = speedBps > 0 ? speedBps
                                    : (done - lastBytes) * 1000 / Math.max(1, now - lastMs);
                            job.speed = sp;
                            job.percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                            job.sizeText = Downloader.human(done) + " / " + Downloader.human(total);
                            job.done = done;
                            job.total = total;
                        }
                        lastBytes = done;
                        lastMs = now;
                    }
                    saveThrottled();
                }
            };
            final Downloader.CancelSignal sig = new Downloader.CancelSignal() {
                @Override
                public boolean isCancelled() {
                    return cancelFlag;
                }
            };
            File out;
            if (useHls && !hlsUrls.isEmpty()) {
                try {
                    out = Downloader.downloadHls(hlsUrls, toItem(job), progress, sig, Settings.tsToMp4());
                } catch (Exception hlsError) {
                    if ("hls".equals(mode)) throw hlsError;
                    if (mp4Urls.isEmpty()) throw hlsError;
                    out = Downloader.download(mp4Urls, toItem(job), progress, sig);
                }
            } else {
                out = Downloader.download(mp4Urls, toItem(job), progress, sig);
            }
            job.filePath = out.getAbsolutePath();
            job.done = out.length();
            job.total = out.length();
            job.speed = 0;
            job.status = DownloadTask.DONE;
            job.sizeText = Downloader.human(out.length());
            MediaScannerConnection.scanFile(ctx, new String[]{out.getAbsolutePath()}, null, null);
            notifyDone(job);
        } catch (Downloader.Cancelled c) {
            job.status = DownloadTask.PAUSED;
            job.speed = 0;
        } catch (Exception e) {
            job.status = DownloadTask.ERROR;
            job.speed = 0;
            job.error = e.getMessage() == null ? "下载失败" : e.getMessage();
        } finally {
            current = null;
            cancelFlag = false;
            save();
            pump();
        }
    }

    private void notifyDone(DownloadTask job) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("cosdl_done", "下载完成",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(ctx, DownloadsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, i, flags);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(ctx, "cosdl_done");
            else b = new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("下载完成")
                    .setContentText(job.title)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify((int) (job.createdAt % 100000), b.build());
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 持久化

    private void load() {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String json = sp.getString(KEY, "");
            if (json == null || json.length() == 0) return;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) tasks.add(DownloadTask.fromJson(o));
            }
        } catch (Exception ignored) {
        }
    }

    public void save() {
        lastSave = System.currentTimeMillis();
        try {
            JSONArray arr = new JSONArray();
            synchronized (tasks) {
                for (DownloadTask t : tasks) arr.put(t.toJson());
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void saveThrottled() {
        if (System.currentTimeMillis() - lastSave > 1500) save();
    }
}
