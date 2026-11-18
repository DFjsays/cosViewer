package com.dsh.cosdl;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

public class VideoAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<VideoItem> items;
    private boolean selecting = false;
    private java.util.Set<String> selected = new java.util.HashSet<String>();

    public VideoAdapter(Context ctx, List<VideoItem> items) {
        this.inflater = LayoutInflater.from(ctx);
        this.items = items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void setSelection(boolean selecting, java.util.Set<String> selected) {
        this.selecting = selecting;
        this.selected = selected == null ? new java.util.HashSet<String>() : selected;
        notifyDataSetChanged();
    }

    public boolean isSelecting() {
        return selecting;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_video, parent, false);
        }
        VideoItem item = items.get(position);
        ImageView cover = (ImageView) v.findViewById(R.id.cover);
        TextView title = (TextView) v.findViewById(R.id.title);
        TextView dur = (TextView) v.findViewById(R.id.dur);
        TextView vip = (TextView) v.findViewById(R.id.vip);
        title.setText(item.title);
        String d = item.durationText();
        dur.setText(d);
        dur.setVisibility(d.length() > 0 ? View.VISIBLE : View.GONE);
        if (item.exclusive) {
            vip.setVisibility(View.VISIBLE);
            vip.setText("VIP抢先看");
        } else if (item.vip) {
            vip.setVisibility(View.VISIBLE);
            vip.setText("VIP");
        } else {
            vip.setVisibility(View.GONE);
        }
        if (vip.getVisibility() == View.VISIBLE) {
            int accent = Settings.accentColor();
            vip.setBackgroundColor(accent);
            vip.setTextColor(ThemeUtil.onColor(accent));
        }
        ImageLoader.get().load(item.photo, cover, 420, 400);
        if (selecting) {
            int accent = Settings.accentColor();
            v.setBackgroundColor(selected.contains(item.id) ? (accent & 0x00FFFFFF) | 0x66000000 : 0x00000000);
        } else {
            v.setBackgroundColor(0x00000000);
        }
        return v;
    }
}
