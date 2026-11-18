package com.dsh.cosdl;

import org.json.JSONObject;

/** 一个下载任务。 */
public class DownloadTask {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String PAUSED = "PAUSED";
    public static final String DONE = "DONE";
    public static final String ERROR = "ERROR";

    public String videoId = "";
    public String title = "";
    public String photo = "";
    public long total = 0;
    public long done = 0;
    public long speed = 0;
    public String status = QUEUED;
    public String error = "";
    public String filePath = "";
    public int percent = 0;
    public String sizeText = "";
    public long createdAt = System.currentTimeMillis();

    public boolean isActive() {
        return RUNNING.equals(status) || QUEUED.equals(status);
    }

    public boolean isDone() {
        return DONE.equals(status);
    }

    public int percent() {
        if (DONE.equals(status)) return 100;
        if (total > 0) return (int) Math.min(100, done * 100 / total);
        return percent;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("videoId", videoId);
            o.put("title", title);
            o.put("photo", photo);
            o.put("total", total);
            o.put("done", done);
            o.put("status", status);
            o.put("error", error);
            o.put("filePath", filePath);
            o.put("createdAt", createdAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static DownloadTask fromJson(JSONObject o) {
        DownloadTask t = new DownloadTask();
        t.videoId = o.optString("videoId", "");
        t.title = o.optString("title", "");
        t.photo = o.optString("photo", "");
        t.total = o.optLong("total", 0);
        t.done = o.optLong("done", 0);
        t.status = o.optString("status", QUEUED);
        t.error = o.optString("error", "");
        t.filePath = o.optString("filePath", "");
        t.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        if (RUNNING.equals(t.status)) t.status = PAUSED;
        return t;
    }
}
