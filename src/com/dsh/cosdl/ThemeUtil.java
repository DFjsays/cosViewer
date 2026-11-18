package com.dsh.cosdl;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** 主题色工具：把强调色应用到胶囊/按钮。 */
public final class ThemeUtil {

    private ThemeUtil() {
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 胶囊背景：选中用主题色，未选中用深灰。 */
    public static Drawable chipBg(Context c, boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, 16));
        if (on) {
            d.setColor(Settings.accentColor());
        } else {
            d.setColor(0xFF242424);
            d.setStroke(dp(c, 1), 0xFF333333);
        }
        return d;
    }

    /** 根据底色亮度给出可读的文字色。 */
    public static int onColor(int accent) {
        int r = (accent >> 16) & 0xff;
        int g = (accent >> 8) & 0xff;
        int b = accent & 0xff;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return lum > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }

    /** 解析 "#RRGGBB" 或 "R,G,B"。 */
    public static int parseColor(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("#")) {
            String h = t.substring(1);
            if (h.length() == 6) return (int) (Long.parseLong(h, 16) | 0xFF000000L);
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
        }
        String[] p = t.split("[,，\\s]+");
        if (p.length >= 3) {
            int r = clamp(Integer.parseInt(p[0]));
            int g = clamp(Integer.parseInt(p[1]));
            int b = clamp(Integer.parseInt(p[2]));
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        throw new IllegalArgumentException("颜色格式不对");
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
