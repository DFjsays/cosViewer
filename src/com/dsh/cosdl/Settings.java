package com.dsh.cosdl;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** 本地设置：下载目录等。 */
public final class Settings {

    public static final String DEFAULT_DIR = "/sdcard/Download/DSH-Go/cosav";
    private static final String PREF = "cfg";
    private static final String KEY_DIR = "download_dir";
    private static final String KEY_ENGINE = "player_engine";     // system / mpv
    private static final String KEY_SPEED = "player_speed";
    private static final String KEY_SCALE = "player_scale";       // fit / stretch / crop
    private static final String KEY_LOOP = "player_loop";
    private static final String KEY_RESUME = "player_resume";
    private static final String KEY_NODE = "cdn_node";            // 空 = 自动
    private static final String KEY_HWDEC = "mpv_hwdec";
    private static final String KEY_MPV_VO = "mpv_vo";
    private static final String KEY_HIDE_VIP = "hide_vip";
    private static final String KEY_BLOCKED_TAGS = "blocked_tags";
    private static final String KEY_DL_MODE = "download_mode";   // auto / mp4 / hls
    private static final String KEY_TS_TO_MP4 = "ts_to_mp4";
    private static final String KEY_ACCENT = "accent_color";
    private static final String KEY_BG_IMAGE = "theme_bg_image";
    private static final String KEY_BG_ALPHA = "theme_bg_alpha";

    private static Context ctx;

    private Settings() {
    }

    public static void init(Context c) {
        ctx = c.getApplicationContext();
    }

    public static String dir() {
        if (ctx == null) return DEFAULT_DIR;
        String d = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_DIR, DEFAULT_DIR);
        if (d == null || d.trim().length() == 0) return DEFAULT_DIR;
        return d.trim();
    }

    public static void setDir(String d) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (d == null || d.trim().length() == 0) {
            sp.edit().remove(KEY_DIR).apply();
        } else {
            sp.edit().putString(KEY_DIR, d.trim()).apply();
        }
    }

    private static SharedPreferences sp() {
        if (ctx == null) return null;
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String engine() {
        return sp() == null ? "mpv" : sp().getString(KEY_ENGINE, "mpv");
    }

    public static void setEngine(String v) {
        if (sp() != null) sp().edit().putString(KEY_ENGINE, v).apply();
    }

    public static float speed() {
        return sp() == null ? 1.0f : sp().getFloat(KEY_SPEED, 1.0f);
    }

    public static void setSpeed(float v) {
        if (sp() != null) sp().edit().putFloat(KEY_SPEED, v).apply();
    }

    public static String scale() {
        return sp() == null ? "fit" : sp().getString(KEY_SCALE, "fit");
    }

    public static void setScale(String v) {
        if (sp() != null) sp().edit().putString(KEY_SCALE, v).apply();
    }

    public static boolean loop() {
        return sp() != null && sp().getBoolean(KEY_LOOP, false);
    }

    public static void setLoop(boolean v) {
        if (sp() != null) sp().edit().putBoolean(KEY_LOOP, v).apply();
    }

    public static boolean resume() {
        return sp() == null || sp().getBoolean(KEY_RESUME, true);
    }

    public static void setResume(boolean v) {
        if (sp() != null) sp().edit().putBoolean(KEY_RESUME, v).apply();
    }

    public static String node() {
        return sp() == null ? "" : sp().getString(KEY_NODE, "");
    }

    public static void setNode(String v) {
        if (sp() != null) sp().edit().putString(KEY_NODE, v == null ? "" : v).apply();
    }

    public static String hwdec() {
        return sp() == null ? "auto-safe" : sp().getString(KEY_HWDEC, "auto-safe");
    }

    public static void setHwdec(String v) {
        if (sp() != null) sp().edit().putString(KEY_HWDEC, v).apply();
    }

    public static String mpvVo() {
        return sp() == null ? "gpu" : sp().getString(KEY_MPV_VO, "gpu");
    }

    public static void setMpvVo(String v) {
        if (sp() != null) sp().edit().putString(KEY_MPV_VO, v).apply();
    }

    public static java.util.Set<String> hiddenIds() {
        if (sp() == null) return new java.util.HashSet<String>();
        java.util.Set<String> set = sp().getStringSet("hidden_ids", null);
        return set == null ? new java.util.HashSet<String>() : new java.util.HashSet<String>(set);
    }

    public static void addHiddenId(String id) {
        if (sp() == null || id == null || id.length() == 0) return;
        java.util.Set<String> set = hiddenIds();
        set.add(id);
        sp().edit().putStringSet("hidden_ids", set).apply();
    }

    public static boolean isHidden(String id) {
        return id != null && hiddenIds().contains(id);
    }

    public static void clearHidden() {
        if (sp() != null) sp().edit().remove("hidden_ids").apply();
    }

    public static java.util.Set<String> blockedTags() {
        if (sp() == null) return new java.util.HashSet<String>();
        java.util.Set<String> set = sp().getStringSet(KEY_BLOCKED_TAGS, null);
        return set == null ? new java.util.HashSet<String>() : new java.util.HashSet<String>(set);
    }

    public static void addBlockedTag(String tag) {
        if (sp() == null || tag == null || tag.trim().length() == 0) return;
        java.util.Set<String> set = blockedTags();
        set.add(tag.trim());
        sp().edit().putStringSet(KEY_BLOCKED_TAGS, set).apply();
    }

    public static void removeBlockedTag(String tag) {
        if (sp() == null) return;
        java.util.Set<String> set = blockedTags();
        set.remove(tag);
        sp().edit().putStringSet(KEY_BLOCKED_TAGS, set).apply();
    }

    public static boolean isBlockedTag(String tag) {
        return tag != null && blockedTags().contains(tag);
    }

    public static String downloadMode() {
        return sp() == null ? "auto" : sp().getString(KEY_DL_MODE, "auto");
    }

    public static void setDownloadMode(String v) {
        if (sp() != null) sp().edit().putString(KEY_DL_MODE, v).apply();
    }

    public static boolean tsToMp4() {
        return sp() == null || sp().getBoolean(KEY_TS_TO_MP4, true);
    }

    public static void setTsToMp4(boolean v) {
        if (sp() != null) sp().edit().putBoolean(KEY_TS_TO_MP4, v).apply();
    }

    public static int accentColor() {
        return sp() == null ? 0xFFFFD166 : sp().getInt(KEY_ACCENT, 0xFFFFD166);
    }

    public static void setAccentColor(int c) {
        if (sp() != null) sp().edit().putInt(KEY_ACCENT, c).apply();
    }

    public static String bgImage() {
        return sp() == null ? "" : sp().getString(KEY_BG_IMAGE, "");
    }

    public static void setBgImage(String path) {
        if (sp() != null) sp().edit().putString(KEY_BG_IMAGE, path == null ? "" : path).apply();
    }

    /** 底图透明度 0-100 */
    public static int bgAlpha() {
        return sp() == null ? 40 : sp().getInt(KEY_BG_ALPHA, 40);
    }

    public static void setBgAlpha(int a) {
        if (sp() != null) sp().edit().putInt(KEY_BG_ALPHA, Math.max(0, Math.min(100, a))).apply();
    }

    public static boolean hideVip() {
        return sp() != null && sp().getBoolean(KEY_HIDE_VIP, false);
    }

    public static void setHideVip(boolean v) {
        if (sp() != null) sp().edit().putBoolean(KEY_HIDE_VIP, v).apply();
    }

    public static File dirFile() {
        File f = new File(dir());
        if (!f.exists()) f.mkdirs();
        return f;
    }
}
