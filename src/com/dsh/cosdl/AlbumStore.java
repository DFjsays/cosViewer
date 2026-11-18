package com.dsh.cosdl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 收藏的相册（本地索引）。 */
public final class AlbumStore {

    public static class Item {
        public String id = "";
        public String title = "";
        public String photo = "";
        public int totalPhotos = 0;
    }

    private static final String PREF = "album_fav";
    private static final String KEY = "items";

    private AlbumStore() {
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
                it.id = o.optString("id", "");
                it.title = o.optString("title", "");
                it.photo = o.optString("photo", "");
                it.totalPhotos = o.optInt("totalPhotos", 0);
                if (it.id.length() > 0) out.add(it);
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
                o.put("id", it.id);
                o.put("title", it.title);
                o.put("photo", it.photo);
                o.put("totalPhotos", it.totalPhotos);
                arr.put(o);
            }
            sp(ctx).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static boolean has(Context ctx, String id) {
        for (Item it : all(ctx)) {
            if (it.id.equals(id)) return true;
        }
        return false;
    }

    public static void add(Context ctx, AlbumItem a) {
        List<Item> list = all(ctx);
        for (Item it : list) {
            if (it.id.equals(a.id)) return;
        }
        Item it = new Item();
        it.id = a.id;
        it.title = a.title;
        it.photo = a.photo;
        it.totalPhotos = a.totalPhotos;
        list.add(0, it);
        save(ctx, list);
    }

    public static void remove(Context ctx, String id) {
        List<Item> list = all(ctx);
        List<Item> keep = new ArrayList<Item>();
        for (Item it : list) {
            if (!it.id.equals(id)) keep.add(it);
        }
        save(ctx, keep);
    }
}
