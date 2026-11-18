package com.dsh.cosdl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 稍后再看播放队列（本地索引，按顺序连播）。 */
public final class WatchLaterStore {

    public static class Item {
        public String videoId = "";
        public String title = "";
        public String photo = "";
    }

    private static final String PREF = "watch_later";
    private static final String KEY = "items";

    private WatchLaterStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<Item> all(Context ctx) {
        List<Item> out = new ArrayList<Item>();
        try {
            String json = sp(ctx).getString(KEY, "");
            if (json == null || json.length() == 0) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.videoId = o.optString("videoId", "");
                it.title = o.optString("title", "");
                it.photo = o.optString("photo", "");
                if (it.videoId.length() > 0) out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context ctx, List<Item> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : list) {
                JSONObject o = new JSONObject();
                o.put("videoId", it.videoId);
                o.put("title", it.title);
                o.put("photo", it.photo);
                arr.put(o);
            }
            sp(ctx).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static boolean has(Context ctx, String videoId) {
        for (Item it : all(ctx)) {
            if (it.videoId.equals(videoId)) return true;
        }
        return false;
    }

    /** 追加到队尾（已存在则忽略）。 */
    public static boolean add(Context ctx, String videoId, String title, String photo) {
        List<Item> list = all(ctx);
        for (Item it : list) {
            if (it.videoId.equals(videoId)) return false;
        }
        Item it = new Item();
        it.videoId = videoId;
        it.title = title == null ? "" : title;
        it.photo = photo == null ? "" : photo;
        list.add(it);
        save(ctx, list);
        return true;
    }

    public static void remove(Context ctx, String videoId) {
        List<Item> list = all(ctx);
        List<Item> keep = new ArrayList<Item>();
        for (Item it : list) {
            if (!it.videoId.equals(videoId)) keep.add(it);
        }
        save(ctx, keep);
    }

    /** dir = -1 上移 / +1 下移。 */
    public static void move(Context ctx, String videoId, int dir) {
        List<Item> list = all(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).videoId.equals(videoId)) {
                int j = i + dir;
                if (j < 0 || j >= list.size()) return;
                Item tmp = list.get(i);
                list.set(i, list.get(j));
                list.set(j, tmp);
                save(ctx, list);
                return;
            }
        }
    }

    public static void clear(Context ctx) {
        sp(ctx).edit().remove(KEY).apply();
    }
}
