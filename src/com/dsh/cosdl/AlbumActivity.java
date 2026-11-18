package com.dsh.cosdl;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 相册（COS图）列表。 */
public class AlbumActivity extends Activity {

    private GridView grid;
    private TextView status;
    private final List<AlbumItem> items = new ArrayList<AlbumItem>();
    private Adapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean loading = false;
    private boolean hasMore = true;
    private int page = 0;
    private int curPage = 0;
    private TextView pageText;
    private Button btnPrevPage, btnNextPage;
    private String kw = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        Api.init(this);
        setContentView(R.layout.activity_album);
        grid = (GridView) findViewById(R.id.grid);
        status = (TextView) findViewById(R.id.status);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        kw = getIntent().getStringExtra("kw");
        if (kw == null) kw = "";
        if (kw.length() > 0) {
            ((TextView) findViewById(R.id.title)).setText("相册：" + kw);
        }
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AlbumItem a = items.get(position);
                Intent i = new Intent(AlbumActivity.this, AlbumDetailActivity.class);
                i.putExtra("id", a.id);
                i.putExtra("title", a.title);
                startActivity(i);
            }
        });
        pageText = (TextView) findViewById(R.id.pageText);
        btnPrevPage = (Button) findViewById(R.id.btnPrevPage);
        btnNextPage = (Button) findViewById(R.id.btnNextPage);
        btnPrevPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (curPage > 0) loadPageAt(curPage - 1);
            }
        });
        btnNextPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasMore) loadPageAt(curPage + 1);
            }
        });
        pageText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpPage();
            }
        });
        findViewById(R.id.btnFav).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFavAlbums();
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                albumMenu(items.get(position));
                return true;
            }
        });
        loadPageAt(0);
    }

    private void loadPageAt(final int targetPage) {
        if (loading) return;
        loading = true;
        status.setText("加载中…");
        final int pg = Math.max(0, targetPage);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<AlbumItem> list = Api.albums(kw, pg, 30);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            items.clear();
                            items.addAll(list);
                            adapter.notifyDataSetChanged();
                            page = pg + 1;
                            curPage = pg;
                            hasMore = !list.isEmpty() && list.size() >= 30;
                            updatePager();
                            status.setText("共 " + (curPage + 1) + " 页");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            status.setText("加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void updatePager() {
        if (pageText == null) return;
        pageText.setText("第 " + (curPage + 1) + " 页");
        if (btnPrevPage != null) {
            btnPrevPage.setEnabled(curPage > 0);
            btnPrevPage.setAlpha(curPage > 0 ? 1f : 0.4f);
        }
        if (btnNextPage != null) {
            btnNextPage.setEnabled(hasMore);
            btnNextPage.setAlpha(hasMore ? 1f : 0.4f);
        }
    }

    private void jumpPage() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(curPage + 1));
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF888888);
        new android.app.AlertDialog.Builder(this)
                .setTitle("跳转到第几页")
                .setView(et)
                .setPositiveButton("跳转", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            int n = Integer.parseInt(et.getText().toString().trim());
                            if (n >= 1) loadPageAt(n - 1);
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void albumMenu(final AlbumItem a) {
        final boolean fav = AlbumStore.has(this, a.id);
        new android.app.AlertDialog.Builder(this)
                .setTitle(a.title)
                .setItems(new String[]{fav ? "取消收藏" : "收藏相册", "打开"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                if (which == 0) {
                                    if (fav) AlbumStore.remove(AlbumActivity.this, a.id);
                                    else AlbumStore.add(AlbumActivity.this, a);
                                    Toast.makeText(AlbumActivity.this,
                                            fav ? "已取消收藏" : "已收藏相册", Toast.LENGTH_SHORT).show();
                                } else {
                                    Intent i = new Intent(AlbumActivity.this, AlbumDetailActivity.class);
                                    i.putExtra("id", a.id);
                                    i.putExtra("title", a.title);
                                    startActivity(i);
                                }
                            }
                        })
                .show();
    }

    private void showFavAlbums() {
        final List<AlbumStore.Item> favs = AlbumStore.all(this);
        if (favs.isEmpty()) {
            Toast.makeText(this, "还没有收藏的相册（长按相册可收藏）", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[favs.size()];
        for (int i = 0; i < favs.size(); i++) labels[i] = favs.get(i).title;
        new android.app.AlertDialog.Builder(this)
                .setTitle("收藏的相册")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        AlbumStore.Item it = favs.get(which);
                        Intent i = new Intent(AlbumActivity.this, AlbumDetailActivity.class);
                        i.putExtra("id", it.id);
                        i.putExtra("title", it.title);
                        startActivity(i);
                    }
                })
                .show();
    }

    private class Adapter extends BaseAdapter {
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

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_album, parent, false);
            AlbumItem a = items.get(position);
            ((TextView) v.findViewById(R.id.aTitle)).setText(a.title);
            ((TextView) v.findViewById(R.id.aCount)).setText(a.totalPhotos + " P");
            ImageLoader.get().load(a.photo, (ImageView) v.findViewById(R.id.aCover), 500, 380);
            return v;
        }
    }
}
