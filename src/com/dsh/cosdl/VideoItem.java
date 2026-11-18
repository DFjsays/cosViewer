package com.dsh.cosdl;

import org.json.JSONObject;

public class VideoItem {
    public String id = "";
    public String title = "";
    public String photo = "";
    public double duration = 0;
    public boolean vip = false;
    public boolean exclusive = false;
    public java.util.List<String> tags = new java.util.ArrayList<String>();
    public String views = "";
    public String works = "";

    public static VideoItem from(JSONObject o) {
        VideoItem v = new VideoItem();
        v.id = o.optString("id", "");
        v.title = o.optString("title", "");
        v.photo = o.optString("photo", "");
        v.views = o.optString("viewnumber", "");
        v.works = o.optString("cos_works", "");
        try {
            v.duration = Double.parseDouble(o.optString("duration", "0"));
        } catch (Exception e) {
            v.duration = 0;
        }
        v.vip = "1".equals(o.optString("is_vip", "0"));
        v.exclusive = o.optBoolean("is_exclusive", false);
        org.json.JSONArray ta = o.optJSONArray("tags");
        if (ta != null) {
            for (int i = 0; i < ta.length(); i++) {
                String t = ta.optString(i, "");
                if (t != null && t.length() > 0) v.tags.add(t);
            }
        }
        return v;
    }

    public String durationText() {
        if (duration <= 0) return "";
        long s = (long) duration;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%d:%02d", m, sec);
    }
}
