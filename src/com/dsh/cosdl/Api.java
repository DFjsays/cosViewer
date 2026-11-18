package com.dsh.cosdl;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** cosav 接口客户端：AES-256-ECB 加密 JSON + 游客 token。 */
public final class Api {

    /** 接口密钥与地址由 build.sh 从 secret.properties 注入（见 Secret.java，不入库）。 */
    private static final String COS_KEY = Secret.API_KEY;
    private static final String DEFAULT_API = Secret.API_HOST;
    private static final String CLOUD_TXT = Secret.API_CLOUD;
    private static final String UA = Secret.UA;

    private static String apiUrl;
    private static String deviceId;

    private Api() {
    }

    public static void init(Context ctx) {
        if (deviceId != null) return;
        SharedPreferences sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        deviceId = sp.getString("did", null);
        if (deviceId == null || deviceId.length() == 0) {
            deviceId = UUID.randomUUID().toString();
            sp.edit().putString("did", deviceId).apply();
        }
    }

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String decrypt(String b64) throws Exception {
        byte[] key = md5Hex(COS_KEY).getBytes("UTF-8"); // 32 bytes -> AES-256
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return new String(c.doFinal(Base64.decode(b64, Base64.DEFAULT)), "UTF-8");
    }

    private static String rawGet(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(25000);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Tokenparam",
                Secret.TOKEN_PREFIX + "," + (System.currentTimeMillis() / 1000L));
        con.setRequestProperty("userParams", "");
        con.setRequestProperty("X-Device-Id", deviceId);
        con.setRequestProperty("User-Agent", UA);
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
        }
        con.disconnect();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 接口地址走云端下发，失败回落默认。 */
    private static synchronized String api() {
        if (apiUrl != null) return apiUrl;
        apiUrl = DEFAULT_API;
        try {
            String txt = rawGet(CLOUD_TXT).trim();
            String plain = decrypt(txt).trim().replace("\"", "");
            if (plain.startsWith("http")) apiUrl = plain;
        } catch (Exception ignored) {
        }
        return apiUrl;
    }

    /** 拿到解密后的 data 字符串（JSON object 或 array）。 */
    private static String apiData(String path) throws Exception {
        String body = rawGet(api() + path);
        JSONObject j = new JSONObject(body);
        int code = j.optInt("code", -1);
        String data = j.optString("data", "");
        if ((code == 0 || code == 200 || code == 201) && data.length() > 0) {
            return decrypt(data);
        }
        String msg = j.optString("errorMsg", "");
        throw new Exception("接口错误 code=" + code + (msg.length() > 0 ? " " + msg : ""));
    }

    public static List<VideoItem> recommend() throws Exception {
        JSONArray arr = new JSONArray(apiData("/video/recommend"));
        List<VideoItem> out = new ArrayList<VideoItem>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(VideoItem.from(o));
        }
        return out;
    }

    public static List<VideoItem> search(String kw, int page, int limit) throws Exception {
        String path = "/video/lists?kw=" + URLEncoder.encode(kw, "UTF-8")
                + "&page=" + page + "&limit=" + limit;
        JSONObject data = new JSONObject(apiData(path));
        JSONArray arr = data.optJSONArray("list");
        List<VideoItem> out = new ArrayList<VideoItem>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(VideoItem.from(o));
            }
        }
        return out;
    }

    public static List<Category> categories() throws Exception {
        JSONArray arr = new JSONArray(apiData("/video/categories"));
        List<Category> out = new ArrayList<Category>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Category(o.optString("CHID", ""), o.optString("name", ""),
                    o.optBoolean("has_sub", false)));
        }
        return out;
    }

    public static List<Category> subCategories(String ct) throws Exception {
        JSONArray arr = new JSONArray(apiData("/video/categories_sub?ct=" + ct));
        List<Category> out = new ArrayList<Category>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Category(o.optString("CHID", ""), o.optString("name", ""), false));
        }
        return out;
    }

    /** 视频列表分页：query 形如 "ct=1&order=mv" 或 "kw=xx&order=mr"。 */
    public static VideoPage videos(String query, int page, int limit) throws Exception {
        StringBuilder sb = new StringBuilder("/video/lists?");
        if (query != null && query.length() > 0) sb.append(query).append("&");
        sb.append("page=").append(page).append("&limit=").append(limit);
        JSONObject data = new JSONObject(apiData(sb.toString()));
        VideoPage vp = new VideoPage();
        JSONArray arr = data.optJSONArray("list");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) vp.items.add(VideoItem.from(o));
            }
        }
        vp.hasMore = data.optInt("lastpage", 0) == 1;
        vp.totalCnt = data.optInt("totalCnt", 0);
        return vp;
    }

    /** 相册列表。 */
    public static List<AlbumItem> albums(int page, int limit) throws Exception {
        return albums("", page, limit);
    }

    public static List<AlbumItem> albums(String kw, int page, int limit) throws Exception {
        String path = "/albums/lists?";
        if (kw != null && kw.length() > 0) {
            path += "kw=" + URLEncoder.encode(kw, "UTF-8") + "&";
        }
        path += "page=" + page + "&limit=" + limit;
        JSONObject data = new JSONObject(apiData(path));
        List<AlbumItem> out = new ArrayList<AlbumItem>();
        JSONArray arr = data.optJSONArray("list");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(AlbumItem.from(o));
            }
        }
        return out;
    }

    /** 相册内图片 URL 列表。 */
    public static List<String> albumPhotos(String id, int page, int limit) throws Exception {
        JSONObject data = new JSONObject(apiData("/albums/album_content?id=" + id
                + "&page=" + page + "&limit=" + limit));
        List<String> out = new ArrayList<String>();
        JSONArray arr = data.optJSONArray("latest");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (u.length() > 0) out.add(u);
            }
        }
        return out;
    }

    /** 拉取 m3u8 播放列表，返回分片 URL 列表（绝对地址）。 */
    public static List<String> playlistSegments(String m3u8Url) throws Exception {
        java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                new java.net.URL(m3u8Url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", UA);
        java.io.InputStream in = con.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        con.disconnect();
        String text = new String(bos.toByteArray(), "UTF-8");
        String base = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1);
        List<String> out = new ArrayList<String>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.length() == 0 || l.startsWith("#")) continue;
            if (l.startsWith("http://") || l.startsWith("https://")) out.add(l);
            else out.add(base + l);
        }
        return out;
    }

    public static JSONObject videoInfo(String id) throws Exception {
        return new JSONObject(apiData("/video/videoinfo?id=" + id));
    }

    public static List<String> hlsUrls(JSONObject info) {
        List<String> out = new ArrayList<String>();
        String[] keys = {"video_url", "video_url_vip"};
        for (String k : keys) {
            JSONArray a = info.optJSONArray(k);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                String u = a.optString(i, "");
                if (u.length() > 0 && !out.contains(u)) out.add(u);
            }
        }
        return out;
    }

    public static List<String> mp4Urls(JSONObject info) {
        List<String> out = new ArrayList<String>();
        String[] keys = {"video_url_mp4_vip", "video_url_mp4"};
        for (String k : keys) {
            JSONArray a = info.optJSONArray(k);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                String u = a.optString(i, "");
                if (u.length() > 0 && !out.contains(u)) out.add(u);
            }
        }
        return out;
    }
}
