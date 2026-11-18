package com.dsh.cosdl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 搜索历史（最近 20 条）。 */
public final class SearchStore {

    private static final String PREF = "search";
    private static final String KEY = "history";

    private SearchStore() {
    }

    public static List<String> all(Context c) {
        List<String> out = new ArrayList<String>();
        try {
            String json = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "");
            if (json == null || json.length() == 0) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (s.length() > 0) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void add(Context c, String kw) {
        if (kw == null) return;
        String t = kw.trim();
        if (t.length() == 0) return;
        List<String> list = all(c);
        list.remove(t);
        list.add(0, t);
        while (list.size() > 20) list.remove(list.size() - 1);
        try {
            JSONArray arr = new JSONArray();
            for (String s : list) arr.put(s);
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
