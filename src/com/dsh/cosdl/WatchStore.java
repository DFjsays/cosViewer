package com.dsh.cosdl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/** 观看记录：精确到分，按天分组。 */
public final class WatchStore {

    private static final String PREF = "watch";
    private static final String KEY = "records";
    private static final int MAX = 800;

    public static class Record {
        public String videoId = "";
        public String title = "";
        public String photo = "";
        public long time = 0;       // 最后观看时间（毫秒）
        public long position = 0;   // 播放位置（秒）

        public String dateKey() {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(time);
            return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        }

        public String timeText() {
            return new SimpleDateFormat("HH:mm").format(new Date(time));
        }
    }

    private WatchStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void touch(Context ctx, String videoId, String title, String photo, long position) {
        List<Record> list = all(ctx);
        Record found = null;
        for (Record r : list) {
            if (r.videoId.equals(videoId)) {
                found = r;
                break;
            }
        }
        if (found == null) {
            found = new Record();
            found.videoId = videoId;
            list.add(0, found);
        } else {
            // 已存在的记录：提到最前面，保证"继续播放"显示最近看的那个
            list.remove(found);
            list.add(0, found);
        }
        found.title = title == null ? found.title : title;
        found.photo = photo == null ? found.photo : photo;
        found.time = System.currentTimeMillis();
        found.position = position;
        // 去重排序
        List<Record> sorted = new ArrayList<Record>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Record r : list) {
            if (r.videoId.length() > 0 && seen.add(r.videoId)) sorted.add(r);
        }
        if (sorted.size() > MAX) sorted = sorted.subList(0, MAX);
        save(ctx, sorted);
    }

    public static long position(Context ctx, String videoId) {
        for (Record r : all(ctx)) {
            if (r.videoId.equals(videoId)) return r.position;
        }
        return 0;
    }

    public static List<Record> all(Context ctx) {
        List<Record> out = new ArrayList<Record>();
        try {
            String json = sp(ctx).getString(KEY, "");
            if (json == null || json.length() == 0) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Record r = new Record();
                r.videoId = o.optString("videoId", "");
                r.title = o.optString("title", "");
                r.photo = o.optString("photo", "");
                r.time = o.optLong("time", 0);
                r.position = o.optLong("position", 0);
                if (r.videoId.length() > 0) out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context ctx, List<Record> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Record r : list) {
                JSONObject o = new JSONObject();
                o.put("videoId", r.videoId);
                o.put("title", r.title);
                o.put("photo", r.photo);
                o.put("time", r.time);
                o.put("position", r.position);
                arr.put(o);
            }
            sp(ctx).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context ctx) {
        sp(ctx).edit().remove(KEY).apply();
    }

    /** 按天分组后的展示列表：元素是 String（日期标题）或 Record。 */
    public static List<Object> grouped(Context ctx) {
        List<Object> out = new ArrayList<Object>();
        List<Record> list = all(ctx);
        java.util.Collections.sort(list, new java.util.Comparator<Record>() {
            @Override
            public int compare(Record a, Record b) {
                return Long.compare(b.time, a.time);
            }
        });
        String last = null;
        Calendar now = Calendar.getInstance();
        String today = String.format("%04d-%02d-%02d", now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        now.add(Calendar.DAY_OF_YEAR, -1);
        String yesterday = String.format("%04d-%02d-%02d", now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        for (Record r : list) {
            String d = r.dateKey();
            if (!d.equals(last)) {
                last = d;
                String label;
                if (d.equals(today)) label = "今天 · " + d;
                else if (d.equals(yesterday)) label = "昨天 · " + d;
                else label = d;
                out.add(label);
            }
            out.add(r);
        }
        return out;
    }
}
