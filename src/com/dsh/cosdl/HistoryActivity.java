package com.dsh.cosdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 观看记录：按天分组，精确到分钟。 */
public class HistoryActivity extends Activity {

    private ListView list;
    private TextView empty;
    private final List<Object> rows = new ArrayList<Object>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        setContentView(R.layout.activity_history);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        adapter = new Adapter();
        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(HistoryActivity.this)
                        .setTitle("清空观看记录？")
                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                WatchStore.clear(HistoryActivity.this);
                                reload();
                                Toast.makeText(HistoryActivity.this, "已清空", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object o = rows.get(position);
                if (!(o instanceof WatchStore.Record)) return;
                WatchStore.Record r = (WatchStore.Record) o;
                VideoDetail.show(HistoryActivity.this,
                        VideoDetail.simple(r.videoId, r.title, r.photo));
            }
        });
        reload();
    }

    private void reload() {
        rows.clear();
        rows.addAll(WatchStore.grouped(this));
        adapter.notifyDataSetChanged();
        boolean has = false;
        for (Object o : rows) {
            if (o instanceof WatchStore.Record) has = true;
        }
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        list.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = rows.get(position);
            if (o instanceof String) {
                TextView tv = new TextView(HistoryActivity.this);
                tv.setText((String) o);
                tv.setTextColor(0xFFFFD166);
                tv.setTextSize(12);
                tv.setPadding(dp(14), dp(12), dp(14), dp(6));
                return tv;
            }
            View v = convertView;
            if (v == null || v instanceof TextView) {
                v = getLayoutInflater().inflate(R.layout.item_history, parent, false);
            }
            WatchStore.Record r = (WatchStore.Record) o;
            ((TextView) v.findViewById(R.id.hTitle)).setText(r.title);
            StringBuilder meta = new StringBuilder();
            meta.append(r.timeText());
            if (r.position > 0) {
                long s = r.position;
                meta.append("   ·   看到 ");
                if (s >= 3600) meta.append(s / 3600).append(":");
                meta.append(String.format("%02d:%02d", (s % 3600) / 60, s % 60));
            }
            ((TextView) v.findViewById(R.id.hMeta)).setText(meta.toString());
            ImageLoader.get().load(r.photo, (ImageView) v.findViewById(R.id.hCover), 220, 220);
            return v;
        }

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}
