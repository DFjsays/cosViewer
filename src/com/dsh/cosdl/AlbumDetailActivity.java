package com.dsh.cosdl;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** 相册内图片浏览：网格 + 全屏查看 + 保存。 */
public class AlbumDetailActivity extends Activity {

    private GridView grid;
    private TextView title, status;
    private final List<String> photos = new ArrayList<String>();
    private Adapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String albumId = "";
    private String albumTitle = "";
    private boolean loading = false;
    private boolean hasMore = true;
    private int page = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        Api.init(this);
        setContentView(R.layout.activity_album_detail);
        grid = (GridView) findViewById(R.id.grid);
        title = (TextView) findViewById(R.id.title);
        status = (TextView) findViewById(R.id.status);
        albumId = getIntent().getStringExtra("id");
        albumTitle = getIntent().getStringExtra("title");
        if (albumTitle == null) albumTitle = "相册";
        title.setText(albumTitle);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        ((Button) findViewById(R.id.btnDownloadAll)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAll();
            }
        });
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showFullscreen(position);
            }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 6 && hasMore && !loading) loadPage();
            }
        });
        loadPage();
    }

    private void loadPage() {
        if (loading || albumId == null) return;
        loading = true;
        status.setText("加载中…");
        final int pg = page;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<String> list = Api.albumPhotos(albumId, pg, 30);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            photos.addAll(list);
                            adapter.notifyDataSetChanged();
                            page = pg + 1;
                            hasMore = !list.isEmpty();
                            status.setText(photos.size() + " 张");
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

    /** 批量下载整本相册到 Download/DSH-Go/albums/<相册名>/ */
    private void downloadAll() {
        Toast.makeText(this, "开始下载全部图片…", Toast.LENGTH_SHORT).show();
        final String safe = (albumTitle == null ? "album" : albumTitle)
                .replaceAll("[\\\\/:*?\"<>|]+", "_");
        final java.io.File dir = new java.io.File("/sdcard/Download/DSH-Go/albums/" + safe);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int saved = 0, failed = 0;
                try {
                    if (!dir.exists()) dir.mkdirs();
                    int pg = 0;
                    while (true) {
                        List<String> list = Api.albumPhotos(albumId, pg, 30);
                        if (list == null || list.isEmpty()) break;
                        for (String u : list) {
                            try {
                                saveOne(u, dir);
                                saved++;
                            } catch (Exception e) {
                                failed++;
                            }
                        }
                        pg++;
                        if (pg > 200) break;
                    }
                    android.media.MediaScannerConnection.scanFile(AlbumDetailActivity.this,
                            new String[]{dir.getAbsolutePath()}, null, null);
                } catch (Exception ignored) {
                }
                final int s2 = saved, f2 = failed;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(AlbumDetailActivity.this,
                                "下载完成：成功 " + s2 + " 张，失败 " + f2 + " 张\n保存到 " + dir.getAbsolutePath(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void saveOne(String url, java.io.File dir) throws Exception {
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (name.length() == 0) name = "img_" + System.currentTimeMillis() + ".jpg";
        java.io.File f = new java.io.File(dir, name);
        if (f.exists() && f.length() > 0) return;
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", Secret.UA);
        InputStream in = con.getInputStream();
        FileOutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
        con.disconnect();
    }

    private void showFullscreen(final int index) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView pos = new TextView(this);
        pos.setText((index + 1) + " / " + photos.size());
        pos.setTextColor(0xFFFFFFFF);
        pos.setTextSize(13);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        plp.topMargin = 40;
        root.addView(pos, plp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(0x99000000);
        bar.setPadding(8, 8, 8, 8);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bar, blp);

        final int[] current = new int[]{index};

        Button prev = miniButton("上一张");
        Button next = miniButton("下一张");
        Button save = miniButton("保存");
        Button close = miniButton("关闭");
        bar.addView(prev);
        bar.addView(next);
        bar.addView(save);
        bar.addView(close);

        final Runnable show = new Runnable() {
            @Override
            public void run() {
                ImageLoader.get().load(photos.get(current[0]), iv, 1080, 1920);
                pos.setText((current[0] + 1) + " / " + photos.size());
            }
        };
        show.run();

        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current[0] > 0) {
                    current[0]--;
                    show.run();
                }
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current[0] < photos.size() - 1) {
                    current[0]++;
                    show.run();
                }
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveImage(photos.get(current[0]));
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });

        d.setContentView(root);
        d.show();
    }

    private Button miniButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void saveImage(final String url) {
        Toast.makeText(this, "正在保存…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(30000);
                    con.setRequestProperty("User-Agent", Secret.UA);
                    InputStream in = con.getInputStream();
                    File dir = new File("/sdcard/Pictures");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, "cosViewer_album_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    con.disconnect();
                    android.media.MediaScannerConnection.scanFile(AlbumDetailActivity.this,
                            new String[]{f.getAbsolutePath()}, null, null);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(AlbumDetailActivity.this,
                                    "已保存到 /sdcard/Pictures/", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(AlbumDetailActivity.this,
                                    "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return photos.size();
        }

        @Override
        public Object getItem(int position) {
            return photos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_photo, parent, false);
            ImageLoader.get().load(photos.get(position), (ImageView) v.findViewById(R.id.photo), 360, 360);
            return v;
        }
    }
}
