package com.dsh.cosdl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 收藏夹：多级文件夹 + 视频索引（只存索引，不复制文件）。 */
public final class CollectionStore {

    private static final String PREF = "collections";
    private static final String KEY = "data";

    public static class Folder {
        public String id = "";
        public String name = "";
        public String parentId = "";   // 空 = 根
        public long createdAt = 0;
    }

    public static class Entry {
        public String videoId = "";
        public String title = "";
        public String photo = "";
        public String folderId = "";
        public long addedAt = 0;
    }

    private CollectionStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<Folder> folders(Context ctx) {
        List<Folder> out = new ArrayList<Folder>();
        try {
            String json = sp(ctx).getString(KEY, "");
            if (json.length() == 0) return out;
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("folders");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Folder f = new Folder();
                f.id = o.optString("id", "");
                f.name = o.optString("name", "");
                f.parentId = o.optString("parentId", "");
                f.createdAt = o.optLong("createdAt", 0);
                out.add(f);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static List<Entry> entries(Context ctx) {
        List<Entry> out = new ArrayList<Entry>();
        try {
            String json = sp(ctx).getString(KEY, "");
            if (json.length() == 0) return out;
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.videoId = o.optString("videoId", "");
                e.title = o.optString("title", "");
                e.photo = o.optString("photo", "");
                e.folderId = o.optString("folderId", "");
                e.addedAt = o.optLong("addedAt", 0);
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context ctx, List<Folder> folders, List<Entry> entries) {
        try {
            JSONObject root = new JSONObject();
            JSONArray fa = new JSONArray();
            for (Folder f : folders) {
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("parentId", f.parentId);
                o.put("createdAt", f.createdAt);
                fa.put(o);
            }
            JSONArray ea = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("videoId", e.videoId);
                o.put("title", e.title);
                o.put("photo", e.photo);
                o.put("folderId", e.folderId);
                o.put("addedAt", e.addedAt);
                ea.put(o);
            }
            root.put("folders", fa);
            root.put("entries", ea);
            sp(ctx).edit().putString(KEY, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 直接建一个根层级文件夹。 */
    public static Folder create(Context ctx, String name, String parentId) {
        List<Folder> fs = folders(ctx);
        Folder f = new Folder();
        f.id = "f" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000);
        f.name = name;
        f.parentId = parentId == null ? "" : parentId;
        f.createdAt = System.currentTimeMillis();
        fs.add(f);
        save(ctx, fs, entries(ctx));
        return f;
    }

    public static void rename(Context ctx, String id, String name) {
        List<Folder> fs = folders(ctx);
        for (Folder f : fs) {
            if (f.id.equals(id)) f.name = name;
        }
        save(ctx, fs, entries(ctx));
    }

    /** 删除文件夹（子文件夹一并删除，条目一起清除）。 */
    public static void delete(Context ctx, String id) {
        List<Folder> fs = folders(ctx);
        List<Entry> es = entries(ctx);
        List<String> ids = new ArrayList<String>();
        collectDelete(fs, id, ids);
        List<Folder> keep = new ArrayList<Folder>();
        for (Folder f : fs) {
            if (!ids.contains(f.id)) keep.add(f);
        }
        List<Entry> keepE = new ArrayList<Entry>();
        for (Entry e : es) {
            if (!ids.contains(e.folderId)) keepE.add(e);
        }
        save(ctx, keep, keepE);
    }

    private static void collectDelete(List<Folder> fs, String id, List<String> out) {
        if (out.contains(id)) return;
        out.add(id);
        for (Folder f : fs) {
            if (f.parentId.equals(id)) collectDelete(fs, f.id, out);
        }
    }

    public static List<Folder> children(Context ctx, String parentId) {
        String p = parentId == null ? "" : parentId;
        List<Folder> out = new ArrayList<Folder>();
        for (Folder f : folders(ctx)) {
            if (f.parentId.equals(p)) out.add(f);
        }
        return out;
    }

    public static List<Entry> entriesIn(Context ctx, String folderId) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : entries(ctx)) {
            if (e.folderId.equals(folderId)) out.add(e);
        }
        return out;
    }

    public static boolean has(Context ctx, String videoId, String folderId) {
        for (Entry e : entries(ctx)) {
            if (e.videoId.equals(videoId) && e.folderId.equals(folderId)) return true;
        }
        return false;
    }

    public static boolean add(Context ctx, String videoId, String title, String photo, String folderId) {
        List<Folder> fs = folders(ctx);
        List<Entry> es = entries(ctx);
        for (Entry e : es) {
            if (e.videoId.equals(videoId) && e.folderId.equals(folderId)) return false;
        }
        Entry e = new Entry();
        e.videoId = videoId;
        e.title = title == null ? "" : title;
        e.photo = photo == null ? "" : photo;
        e.folderId = folderId;
        e.addedAt = System.currentTimeMillis();
        es.add(e);
        save(ctx, fs, es);
        return true;
    }

    public static void removeEntry(Context ctx, String videoId, String folderId) {
        List<Entry> es = entries(ctx);
        List<Entry> keep = new ArrayList<Entry>();
        for (Entry e : es) {
            if (!(e.videoId.equals(videoId) && e.folderId.equals(folderId))) keep.add(e);
        }
        save(ctx, folders(ctx), keep);
    }

    /** 把一批视频索引移动/复制到目标文件夹。 */
    public static void transfer(Context ctx, List<Entry> source, String fromFolder,
                                String toFolder, boolean move) {
        List<Folder> fs = folders(ctx);
        List<Entry> es = entries(ctx);
        java.util.Set<String> moved = new java.util.HashSet<String>();
        for (Entry e : source) {
            boolean exists = false;
            for (Entry x : es) {
                if (x.videoId.equals(e.videoId) && x.folderId.equals(toFolder)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                Entry n = new Entry();
                n.videoId = e.videoId;
                n.title = e.title;
                n.photo = e.photo;
                n.folderId = toFolder;
                n.addedAt = System.currentTimeMillis();
                es.add(n);
            }
            if (move) moved.add(e.videoId);
        }
        if (move) {
            List<Entry> keep = new ArrayList<Entry>();
            for (Entry x : es) {
                if (x.folderId.equals(fromFolder) && moved.contains(x.videoId)) continue;
                keep.add(x);
            }
            es = keep;
        }
        save(ctx, fs, es);
    }

    /** 列出所有文件夹（带层级缩进），用于选择目标文件夹。 */
    public static List<String[]> tree(Context ctx, String excludeId) {
        List<Folder> all = folders(ctx);
        List<String[]> out = new ArrayList<String[]>();
        java.util.List<String> exclude = new ArrayList<String>();
        if (excludeId != null) collectDelete(all, excludeId, exclude);
        appendTree(all, "", 0, out, exclude);
        return out;
    }

    private static void appendTree(List<Folder> all, String parent, int depth,
                                   List<String[]> out, List<String> exclude) {
        for (Folder f : all) {
            if (!f.parentId.equals(parent)) continue;
            if (exclude.contains(f.id)) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) sb.append("   ");
            sb.append(f.name);
            out.add(new String[]{f.id, sb.toString()});
            appendTree(all, f.id, depth + 1, out, exclude);
        }
    }
}
