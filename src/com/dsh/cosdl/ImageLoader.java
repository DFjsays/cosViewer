package com.dsh.cosdl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简图片加载器：内存 LRU + 4 线程 + 采样解码 + 复用保护。 */
public class ImageLoader {

    private static ImageLoader sInstance;

    public static synchronized ImageLoader get() {
        if (sInstance == null) sInstance = new ImageLoader();
        return sInstance;
    }

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageLoader() {
        int max = (int) (Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    private byte[] fetch(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(25000);
        con.setRequestProperty("User-Agent", Secret.UA);
        con.setRequestProperty("Referer", "https://cosav-cos.cc/");
        InputStream in = con.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        con.disconnect();
        return bos.toByteArray();
    }

    private static Bitmap decode(byte[] data, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        if (o.outWidth > 0 && o.outHeight > 0) {
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) {
                sample *= 2;
            }
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    public void load(final String url, final ImageView iv, final int reqW, final int reqH) {
        if (url == null || url.length() == 0) return;
        iv.setTag(url);
        Bitmap cached = cache.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bm = decode(fetch(url), Math.max(1, reqW), Math.max(1, reqH));
                    if (bm != null) cache.put(url, bm);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (bm != null && url.equals(iv.getTag())) {
                                iv.setImageBitmap(bm);
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }
}
