package com.dsh.cosdl;

import android.app.Activity;
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

/** 稍后再看队列：按顺序连播。 */
public class WatchLaterActivity extends Activity {

    private ListView list;
    private TextView empty;
    private final List<WatchLaterStore.Item> rows = new ArrayList<WatchLaterStore.Item>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        setContentView(R.layout.activity_watch_later);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        adapter = new Adapter();
        list.setAdapter(adapter);
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        ((Button) findViewById(R.id.btnPlayAll)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playFrom(0);
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= rows.size()) return;
                WatchLaterStore.Item it = rows.get(position);
                VideoDetail.show(WatchLaterActivity.this,
                        VideoDetail.simple(it.videoId, it.title, it.photo));
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                itemMenu(position);
                return true;
            }
        });
    }

    private void playFrom(int position) {
        if (position < 0 || position >= rows.size()) {
            Toast.makeText(this, "队列是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        WatchLaterStore.Item it = rows.get(position);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ID, it.videoId);
        i.putExtra(PlayerActivity.EXTRA_TITLE, it.title);
        i.putExtra(PlayerActivity.EXTRA_PHOTO, it.photo);
        i.putExtra(PlayerActivity.EXTRA_QUEUE_POS, position);
        startActivity(i);
    }

    private void itemMenu(final int position) {
        final WatchLaterStore.Item it = rows.get(position);
        new android.app.AlertDialog.Builder(this)
                .setTitle(it.title)
                .setItems(new String[]{"查看详情", "从这里连播", "上移", "下移", "移出队列"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                if (which == 0) {
                                    VideoDetail.show(WatchLaterActivity.this,
                                            VideoDetail.simple(it.videoId, it.title, it.photo));
                                } else if (which == 1) playFrom(position);
                                else if (which == 2) {
                                    WatchLaterStore.move(WatchLaterActivity.this, it.videoId, -1);
                                    reload();
                                } else if (which == 3) {
                                    WatchLaterStore.move(WatchLaterActivity.this, it.videoId, 1);
                                    reload();
                                } else {
                                    WatchLaterStore.remove(WatchLaterActivity.this, it.videoId);
                                    reload();
                                }
                            }
                        })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        rows.clear();
        rows.addAll(WatchLaterStore.all(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
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
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_history, parent, false);
            WatchLaterStore.Item it = rows.get(position);
            ((TextView) v.findViewById(R.id.hTitle)).setText((position + 1) + ". " + it.title);
            ((TextView) v.findViewById(R.id.hMeta)).setText("点击连播 · 长按更多");
            ImageLoader.get().load(it.photo, (ImageView) v.findViewById(R.id.hCover), 200, 200);
            return v;
        }
    }
}
