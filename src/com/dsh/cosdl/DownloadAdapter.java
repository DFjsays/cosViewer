package com.dsh.cosdl;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadAdapter extends BaseAdapter {

    public interface Listener {
        void onItemClick(DownloadTask t);

        void onAction(DownloadTask t);

        void onDelete(DownloadTask t);
    }

    private final LayoutInflater inflater;
    private final List<DownloadTask> data = new ArrayList<DownloadTask>();
    private final Listener listener;

    public DownloadAdapter(Context ctx, Listener listener) {
        this.inflater = LayoutInflater.from(ctx);
        this.listener = listener;
    }

    public void update(List<DownloadTask> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private String human(long n) {
        return Downloader.human(n);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = inflater.inflate(R.layout.item_download, parent, false);
        final DownloadTask t = data.get(position);

        ImageView cover = (ImageView) v.findViewById(R.id.dlCover);
        TextView title = (TextView) v.findViewById(R.id.dlTitle);
        TextView info = (TextView) v.findViewById(R.id.dlInfo);
        ProgressBar bar = (ProgressBar) v.findViewById(R.id.dlBar);
        TextView action = (TextView) v.findViewById(R.id.dlAction);
        TextView del = (TextView) v.findViewById(R.id.dlDelete);

        title.setText(t.title);
        ImageLoader.get().load(t.photo, cover, 220, 220);

        String infoText;
        int pct = t.percent();
        if (DownloadTask.DONE.equals(t.status)) {
            infoText = "已完成 · " + human(t.total > 0 ? t.total : t.done);
            bar.setProgress(100);
            action.setText("打开");
        } else if (DownloadTask.RUNNING.equals(t.status)) {
            if (t.sizeText != null && t.sizeText.length() > 0) {
                infoText = "下载中 · " + t.sizeText + "（" + pct + "%）";
            } else {
                infoText = "下载中 · " + human(t.done);
                if (t.total > 0) infoText += " / " + human(t.total) + "（" + pct + "%）";
            }
            if (t.speed > 0) infoText += " · " + human(t.speed) + "/s";
            bar.setProgress(pct);
            action.setText("暂停");
        } else if (DownloadTask.QUEUED.equals(t.status)) {
            infoText = "排队中" + (t.sizeText != null && t.sizeText.length() > 0
                    ? " · " + t.sizeText
                    : (t.total > 0 ? " · 已有 " + human(t.done) : ""));
            bar.setProgress(pct);
            action.setText("暂停");
        } else if (DownloadTask.PAUSED.equals(t.status)) {
            infoText = "已暂停 · " + (t.sizeText != null && t.sizeText.length() > 0
                    ? t.sizeText : human(t.done));
            if (t.total > 0) infoText += " / " + human(t.total) + "（" + pct + "%）";
            bar.setProgress(pct);
            action.setText("继续");
        } else {
            infoText = "失败：" + t.error;
            bar.setProgress(pct);
            action.setText("重试");
        }
        info.setText(infoText);

        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) listener.onAction(t);
            }
        });
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) listener.onDelete(t);
            }
        });
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) listener.onItemClick(t);
            }
        });
        return v;
    }
}
