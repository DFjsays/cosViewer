package com.dsh.cosdl;

import org.json.JSONObject;

public class AlbumItem {
    public String id = "";
    public String title = "";
    public String photo = "";
    public String tags = "";
    public int totalPhotos = 0;

    public static AlbumItem from(JSONObject o) {
        AlbumItem a = new AlbumItem();
        a.id = o.optString("id", "");
        a.title = o.optString("title", "");
        a.photo = o.optString("photo", "");
        a.tags = o.optString("tags", "");
        try {
            a.totalPhotos = Integer.parseInt(o.optString("total_photos", "0"));
        } catch (Exception e) {
            a.totalPhotos = 0;
        }
        return a;
    }
}
