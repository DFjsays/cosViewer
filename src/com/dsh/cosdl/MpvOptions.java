package com.dsh.cosdl;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** mpv 原生设置项（对照 mpv-android 的 preferences 精简映射到 mpv 选项）。 */
public final class MpvOptions {

    public static class Opt {
        public String cat;
        public String key;      // mpv 选项名，或特殊 key（gesture 等）
        public String title;
        public String[] values;
        public String[] labels;

        public Opt(String cat, String key, String title, String[] values, String[] labels) {
            this.cat = cat;
            this.key = key;
            this.title = title;
            this.values = values;
            this.labels = labels;
        }
    }

    public static final String CAT_GENERAL = "常规";
    public static final String CAT_VIDEO = "视频";
    public static final String CAT_UI = "用户界面";
    public static final String CAT_GESTURE = "触摸屏手势";
    public static final String CAT_ADV = "高级";

    public static final String[] CATEGORIES = {CAT_GENERAL, CAT_VIDEO, CAT_UI, CAT_GESTURE, CAT_ADV};

    private MpvOptions() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("mpv_opts", Context.MODE_PRIVATE);
    }

    public static List<Opt> all() {
        List<Opt> list = new ArrayList<Opt>();
        list.add(new Opt(CAT_GENERAL, "alang", "默认音频语言",
                new String[]{"", "zh", "ja", "en"}, new String[]{"跟随默认", "中文", "日文", "英文"}));
        list.add(new Opt(CAT_GENERAL, "slang", "默认字幕语言",
                new String[]{"", "zh", "ja", "en"}, new String[]{"跟随默认", "中文", "日文", "英文"}));

        list.add(new Opt(CAT_VIDEO, "hwdec", "硬件解码",
                new String[]{"auto-safe", "mediacodec", "no"}, new String[]{"自动（推荐）", "强制硬解", "软解"}));
        list.add(new Opt(CAT_VIDEO, "vo", "视频输出 vo",
                new String[]{"gpu", "gpu-next", "mediacodec_embed"}, new String[]{"gpu", "gpu-next", "mediacodec_embed"}));
        list.add(new Opt(CAT_VIDEO, "deband", "去色带",
                new String[]{"", "gpu", "gradfun"}, new String[]{"关闭", "gpu 去色带", "gradfun"}));
        list.add(new Opt(CAT_VIDEO, "video-sync", "音视频同步",
                new String[]{"audio", "display-resample", "display-vdrop"}, new String[]{"音频优先", "画面重采样", "丢帧"}));
        list.add(new Opt(CAT_VIDEO, "interpolation", "插帧（运动补偿）",
                new String[]{"no", "yes"}, new String[]{"关闭", "开启"}));
        list.add(new Opt(CAT_VIDEO, "scale", "缩放算法",
                new String[]{"bilinear", "bicubic", "lanczos", "spline36"},
                new String[]{"bilinear", "bicubic", "lanczos", "spline36"}));

        list.add(new Opt(CAT_UI, "gesture", "触摸手势",
                new String[]{"1", "0"}, new String[]{"开启", "关闭"}));
        list.add(new Opt(CAT_UI, "controls_timeout", "控制条自动隐藏",
                new String[]{"3000", "5000", "0"}, new String[]{"3 秒", "5 秒", "不隐藏"}));

        list.add(new Opt(CAT_GESTURE, "smooth_seek", "更平滑的播放定位",
                new String[]{"0", "1"}, new String[]{"关闭", "开启"}));
        list.add(new Opt(CAT_GESTURE, "hscroll_action", "水平拖拽",
                new String[]{"seek", "none"}, new String[]{"定位", "无"}));
        list.add(new Opt(CAT_GESTURE, "vscroll_left", "垂直拖拽（左侧）",
                new String[]{"brightness", "none"}, new String[]{"亮度", "无"}));
        list.add(new Opt(CAT_GESTURE, "vscroll_right", "垂直拖拽（右侧）",
                new String[]{"volume", "none"}, new String[]{"音量", "无"}));
        list.add(new Opt(CAT_GESTURE, "dblclick_left", "双击（左侧）",
                new String[]{"rew10", "none", "custom"}, new String[]{"快退 10 秒", "无", "自定义（input.conf 0x10001）"}));
        list.add(new Opt(CAT_GESTURE, "dblclick_center", "双击（中间）",
                new String[]{"pause", "none", "custom"}, new String[]{"播放/暂停", "无", "自定义（input.conf 0x10002）"}));
        list.add(new Opt(CAT_GESTURE, "dblclick_right", "双击（右侧）",
                new String[]{"fwd10", "none", "custom"}, new String[]{"快进 10 秒", "无", "自定义（input.conf 0x10003）"}));
        list.add(new Opt(CAT_GESTURE, "longpress_speed", "长按加速倍率",
                new String[]{"2.0", "3.0", "0"}, new String[]{"2x", "3x", "关闭"}));

        list.add(new Opt(CAT_ADV, "cache-secs", "网络缓存秒数",
                new String[]{"10", "30", "60", "120"}, new String[]{"10 秒", "30 秒", "60 秒", "120 秒"}));
        list.add(new Opt(CAT_ADV, "ao", "音频输出",
                new String[]{"audiotrack,opensles", "audiotrack", "opensles"},
                new String[]{"自动", "audiotrack", "opensles"}));
        list.add(new Opt(CAT_ADV, "tls-verify", "TLS 证书校验",
                new String[]{"no", "yes"}, new String[]{"关闭（兼容差证书源）", "开启"}));
        return list;
    }

    public static List<Opt> ofCategory(String cat) {
        List<Opt> out = new ArrayList<Opt>();
        for (Opt o : all()) {
            if (o.cat.equals(cat)) out.add(o);
        }
        return out;
    }

    public static String value(Context c, Opt o) {
        String v = sp(c).getString(o.key, null);
        return v == null ? o.values[0] : v;
    }

    public static String value(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    public static void set(Context c, Opt o, String v) {
        sp(c).edit().putString(o.key, v).apply();
    }

    public static int indexOf(Opt o, String v) {
        for (int i = 0; i < o.values.length; i++) {
            if (o.values[i].equals(v)) return i;
        }
        return 0;
    }

    public static String label(Context c, Opt o) {
        String v = value(c, o);
        int i = indexOf(o, v);
        return o.labels[i];
    }

    /** 把设置项写进 mpv（必须在 mpv init() 之前调用）。 */
    public static void apply(Context c, is.xyz.mpv.MPVLib mpv) {
        for (Opt o : all()) {
            String v = value(c, o);
            if (v == null || v.length() == 0) continue;
            if (o.key.equals("gesture") || o.key.equals("controls_timeout")
                    || o.key.equals("dblclick_center") || o.key.equals("longpress_speed")) {
                continue; // 这些是 App 层行为，不写 mpv
            }
            if (o.key.equals("vo")) {
                // vo 由 player 自己按渲染方式设置，这里允许覆盖
                mpv.setOptionString("vo", v);
                if ("gpu".equals(v) || "gpu-next".equals(v)) {
                    mpv.setOptionString("gpu-context", "android");
                    mpv.setOptionString("opengl-es", "yes");
                }
                continue;
            }
            if (o.key.equals("interpolation")) {
                mpv.setOptionString("interpolation", v);
                continue;
            }
            mpv.setOptionString(o.key, v);
        }
    }
}
