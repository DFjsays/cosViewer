package com.dsh.cosdl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分块并发下载 + 断点续传 + 取消。
 *
 * 调度策略（节点测速 + 吞吐反馈 + 动态派发）：
 *  1. 下载前对每个 CDN 节点并行测速（384KB），得到初始节点评分；
 *  2. 分块任务走共享队列，worker 每次取块时选择「评分最高」的节点
 *     （评分 = 节点吞吐 / 当前并发数），快的节点自然多拿任务；
 *  3. 每个分块完成后用真实吞吐做滑动平均，慢节点（< 最快节点 40%）出池，
 *     不再派发新任务；失败分块立刻迁回队列由其他节点重试；
 *  4. 每 15 秒对「出池」节点做一次 64KB 轻量复测，恢复则重新入池；
 *  5. 分块乱序写块文件，完成后再按序拼接，天然支持断点续传。
 */
public final class Downloader {

    private static final String UA = Secret.UA;
    private static final long CHUNK = 4L * 1024 * 1024;
    private static final int TOTAL_WORKERS = 12;
    private static final int PROBE_BYTES = 384 * 1024;

    public interface Progress {
        /** HLS 分片时 done/total 为分片数；mp4 时为字节数。speed 为 bytes/s（未知传 0）。 */
        void onProgress(long done, long total, long speed);
    }

    public interface CancelSignal {
        boolean isCancelled();
    }

    public static class Cancelled extends Exception {
        public Cancelled() {
            super("已取消");
        }
    }

    private Downloader() {
    }

    public static File downloadDir() {
        return Settings.dirFile();
    }

    public static String human(long n) {
        if (n < 1024) return n + "B";
        double k = n / 1024.0;
        if (k < 1024) return String.format("%.1fKB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format("%.1fMB", m);
        return String.format("%.2fGB", m / 1024.0);
    }

    private static String safeName(String s, int max) {
        String t = s == null ? "" : s;
        t = t.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_").trim();
        if (t.length() > max) t = t.substring(0, max);
        if (t.length() == 0) t = "video";
        return t;
    }

    public static String fileNameFor(VideoItem item) {
        return safeName(item.id + "-" + item.title, 70) + ".mp4";
    }

    // ---------------------------------------------------------------- 探测

    private static long probeTotal(String host, String pathQuery) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL("https://" + host + pathQuery).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(12000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Range", "bytes=0-0");
            String cr = con.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            }
        } catch (Exception ignored) {
        } finally {
            if (con != null) con.disconnect();
        }
        return -1;
    }

    private static long probeSpeed(String host, String pathQuery, int idx) {
        return probeSpeed(host, pathQuery, idx, PROBE_BYTES);
    }

    /** 单节点测速：读若干字节或 6 秒，返回 bytes/s。 */
    private static long probeSpeed(String host, String pathQuery, int idx, int bytes) {
        HttpURLConnection con = null;
        long got = 0;
        long t0 = System.currentTimeMillis();
        try {
            long start = 5000000L + idx * 3000000L;
            con = (HttpURLConnection) new URL("https://" + host + pathQuery).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Range", "bytes=" + start + "-" + (start + bytes - 1));
            InputStream in = con.getInputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                got += n;
                if (got >= bytes) break;
                if (System.currentTimeMillis() - t0 > 6000) break;
            }
            in.close();
        } catch (Exception ignored) {
        } finally {
            if (con != null) con.disconnect();
        }
        long dt = Math.max(1, System.currentTimeMillis() - t0);
        return got * 1000L / dt;
    }

    /** 并行测速所有节点。 */
    private static Map<String, Long> measureSpeeds(List<String> hosts, final String pathQuery) {
        final Map<String, Long> out = new ConcurrentHashMap<String, Long>();
        List<Thread> ts = new ArrayList<Thread>();
        for (int i = 0; i < hosts.size(); i++) {
            final String host = hosts.get(i);
            final int idx = i;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    out.put(host, probeSpeed(host, pathQuery, idx));
                }
            });
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        for (Thread t : ts) {
            try {
                t.join(8000);
            } catch (InterruptedException ignored) {
            }
        }
        for (String h : hosts) {
            if (!out.containsKey(h)) out.put(h, 0L);
        }
        return out;
    }

    // ---------------------------------------------------------------- 分块

    private static void fetchChunk(String host, String pathQuery, long start, long end,
                                   File path, AtomicLong counter, Progress progress,
                                   long total, CancelSignal sig) throws Exception {
        HttpURLConnection con = null;
        long got = 0;
        try {
            if (sig != null && sig.isCancelled()) throw new Cancelled();
            con = (HttpURLConnection) new URL("https://" + host + pathQuery).openConnection();
            con.setConnectTimeout(12000);
            con.setReadTimeout(45000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Range", "bytes=" + start + "-" + end);
            InputStream in = con.getInputStream();
            File tmp = new File(path.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (sig != null && sig.isCancelled()) throw new Cancelled();
                fos.write(buf, 0, n);
                got += n;
                if (progress != null) progress.onProgress(counter.addAndGet(n), total, 0);
            }
            fos.close();
            in.close();
            if (got != (end - start + 1)) throw new Exception("分块长度不符");
            if (!tmp.renameTo(path)) throw new Exception("块写入失败");
        } catch (Cancelled c) {
            if (got > 0) counter.addAndGet(-got);
            throw c;
        } catch (Exception e) {
            if (got > 0) counter.addAndGet(-got);
            throw e;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** 对一批视频地址涉及的 CDN 节点并行测速，返回 host -> bytes/s。 */
    public static Map<String, Long> measureUrls(List<String> urls) {
        CDN cdn = parseCdn(urls);
        if (cdn.hosts.isEmpty() || cdn.pathQuery == null) {
            return new java.util.HashMap<String, Long>();
        }
        return measureSpeeds(cdn.hosts, cdn.pathQuery);
    }

    private static class CDN {
        final List<String> hosts = new ArrayList<String>();
        String pathQuery;
    }

    private static CDN parseCdn(List<String> urls) {
        CDN cdn = new CDN();
        for (String u : urls) {
            try {
                URI uri = URI.create(u);
                String host = uri.getHost();
                if (host == null) continue;
                String pq = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                if (cdn.pathQuery == null) {
                    cdn.pathQuery = pq;
                    cdn.hosts.add(host);
                } else if (pq.equals(cdn.pathQuery) && !cdn.hosts.contains(host)) {
                    cdn.hosts.add(host);
                }
            } catch (Exception ignored) {
            }
        }
        return cdn;
    }

    private static CDN singleHost(String url) {
        CDN one = new CDN();
        try {
            URI uri = URI.create(url);
            one.pathQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            one.hosts.add(uri.getHost());
        } catch (Exception ignored) {
        }
        return one;
    }

    // ---------------------------------------------------------------- 调度

    private static class NodeStat {
        final String host;
        volatile long speed;
        volatile boolean inPool = true;
        volatile long lastProbe = 0;
        final AtomicInteger active = new AtomicInteger(0);
        final AtomicInteger samples = new AtomicInteger(0);

        NodeStat(String host, long speed) {
            this.host = host;
            this.speed = speed;
        }

        /** 调度评分：吞吐 / 当前并发（出池节点垫底）。 */
        double score() {
            return inPool ? speed / (active.get() + 1.0) : -1;
        }
    }

    /** 选「每 worker 可用速率」最高的节点：速度 / (并发+1)。 */
    private static NodeStat pickBest(List<NodeStat> nodes) {
        NodeStat best = null;
        double bestScore = -1;
        for (NodeStat n : nodes) {
            if (!n.inPool) continue;
            double score = n.speed / (double) (n.active.get() + 1);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best == null) {
            // 全部出池：强制启用最快的一个，避免任务卡死
            for (NodeStat n : nodes) {
                n.inPool = true;
                if (best == null || n.speed > best.speed) best = n;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 主流程

    public static File download(List<String> urls, VideoItem item, Progress progress)
            throws Exception {
        return download(urls, item, progress, null);
    }

    public static File download(List<String> urls, VideoItem item, Progress progress,
                               CancelSignal sig) throws Exception {
        if (urls == null || urls.isEmpty()) throw new Exception("没有可用的下载地址");

        File dir = downloadDir();
        String name = fileNameFor(item);
        File out = new File(dir, name);
        if (out.exists() && out.length() > 0) return out;

        CDN cdn = parseCdn(urls);
        if (cdn.hosts.isEmpty() || cdn.pathQuery == null) {
            Exception last = null;
            for (String u : urls) {
                try {
                    return downloadWithHosts(item, dir, name, progress, singleHost(u), sig);
                } catch (Cancelled c) {
                    throw c;
                } catch (Exception e) {
                    last = e;
                }
            }
            throw new Exception(last == null ? "所有下载地址都失败" : String.valueOf(last.getMessage()));
        }
        return downloadWithHosts(item, dir, name, progress, cdn, sig);
    }

    private static File downloadWithHosts(VideoItem item, File dir, String name,
                                          Progress progress, CDN cdn, final CancelSignal sig)
            throws Exception {
        long total = 0;
        for (String h : cdn.hosts) {
            if (sig != null && sig.isCancelled()) throw new Cancelled();
            total = probeTotal(h, cdn.pathQuery);
            if (total > 0) break;
        }
        if (total <= 0) throw new Exception("无法获取文件大小");

        File out = new File(dir, name);
        File partDir = new File(dir, name + ".parts");
        if (!partDir.exists()) partDir.mkdirs();

        final List<long[]> todo = new ArrayList<long[]>();
        long already = 0;
        long pos = 0;
        while (pos < total) {
            long end = Math.min(pos + CHUNK - 1, total - 1);
            File cf = new File(partDir, String.valueOf(pos));
            if (cf.exists() && cf.length() == (end - pos + 1)) {
                already += (end - pos + 1);
            } else {
                todo.add(new long[]{pos, end});
            }
            pos = end + 1;
        }
        if (todo.isEmpty()) {
            return merge(out, partDir, total, sig);
        }

        final AtomicLong counter = new AtomicLong(already);
        if (progress != null) progress.onProgress(already, total, 0);

        // 初始节点评分
        Map<String, Long> speeds = measureSpeeds(cdn.hosts, cdn.pathQuery);
        if (sig != null && sig.isCancelled()) throw new Cancelled();
        long best = 1;
        for (Long v : speeds.values()) {
            if (v != null && v > best) best = v;
        }

        final List<NodeStat> nodes = new ArrayList<NodeStat>();
        for (String h : cdn.hosts) {
            Long sp = speeds.get(h);
            nodes.add(new NodeStat(h, Math.max(1, sp == null ? 1 : sp)));
        }
        final AtomicLong bestSpeed = new AtomicLong(best);
        final long probeThreshold = Math.max(60 * 1024L, (long) (best * 0.4));

        final BlockingQueue<long[]> queue = new LinkedBlockingQueue<long[]>(todo);
        final long ftotal = total;
        final CDN c = cdn;
        final CountDownLatch latch = new CountDownLatch(TOTAL_WORKERS);

        for (int w = 0; w < TOTAL_WORKERS; w++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            if (sig != null && sig.isCancelled()) return;
                            long[] span = queue.poll();
                            if (span == null) return;
                            NodeStat node = pickBest(nodes);
                            if (node == null) {
                                queue.offer(span);
                                return;
                            }
                            node.active.incrementAndGet();
                            long t0 = System.currentTimeMillis();
                            boolean ok = false;
                            try {
                                File cf = new File(partDirOf(name), String.valueOf(span[0]));
                                fetchChunk(node.host, c.pathQuery, span[0], span[1], cf,
                                        counter, progress, ftotal, sig);
                                ok = true;
                            } catch (Cancelled cc) {
                                return;
                            } catch (Exception ex) {
                                queue.offer(span); // 迁回队列，交给其他节点
                            } finally {
                                node.active.decrementAndGet();
                                if (ok) {
                                    long dt = Math.max(1, System.currentTimeMillis() - t0);
                                    long bytes = span[1] - span[0] + 1;
                                    long sp = bytes * 1000L / dt;
                                    synchronized (node) {
                                        node.speed = (node.speed * 2 + sp) / 3;
                                    }
                                    node.samples.incrementAndGet();
                                    if (node.samples.get() >= 2 && node.speed < bestSpeed.get() * 0.4) {
                                        node.inPool = false;
                                        node.lastProbe = System.currentTimeMillis();
                                    }
                                    long b = 1;
                                    for (NodeStat n : nodes) {
                                        if (n.inPool && n.speed > b) b = n.speed;
                                    }
                                    bestSpeed.set(b);
                                }
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        // 轻量复测：只对出池节点，每 15 秒 64KB
        Thread prober = new Thread(new Runnable() {
            @Override
            public void run() {
                int idx = 0;
                while (latch.getCount() > 0) {
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (sig != null && sig.isCancelled()) return;
                    for (NodeStat n : nodes) {
                        if (n.inPool) continue;
                        long sp = probeSpeed(n.host, c.pathQuery, idx++, 64 * 1024);
                        if (sp >= probeThreshold && sp > bestSpeed.get() * 0.5) {
                            synchronized (n) {
                                n.speed = sp;
                            }
                            n.inPool = true;
                        }
                    }
                }
            }
        });
        prober.setDaemon(true);
        prober.start();

        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }

        if (sig != null && sig.isCancelled()) throw new Cancelled();
        if (!queue.isEmpty()) {
            throw new Exception("部分分块下载失败（节点不稳定），已暂停，可继续续传");
        }
        return merge(out, partDir, total, sig);
    }

    private static File merge(File out, File partDir, long total, CancelSignal sig) throws Exception {
        FileOutputStream fos = new FileOutputStream(out);
        long p = 0;
        while (p < total) {
            if (sig != null && sig.isCancelled()) {
                fos.close();
                out.delete();
                throw new Cancelled();
            }
            File cf = new File(partDir, String.valueOf(p));
            FileInputStream in = new FileInputStream(cf);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            in.close();
            p += cf.length();
        }
        fos.close();
        if (out.length() != total) {
            out.delete();
            throw new Exception("拼接后大小不符");
        }
        deleteDir(partDir);
        return out;
    }

    private static File partDirOf(String name) {
        return new File(downloadDir(), name + ".parts");
    }

    // ================================================================ HLS 分片下载

    /** 分片并行下载 → 拼接 .ts →（可选）转封装 mp4。
     *  先用同一个分片给各 CDN 测速（含 TLS 握手），只保留快的节点；
     *  worker 每片按「吞吐 / 当前并发」挑最快节点，慢节点自动出池。 */
    public static File downloadHls(List<String> hlsUrls, VideoItem item, Progress progress,
                                   CancelSignal sig, boolean convertMp4) throws Exception {
        File dir = downloadDir();
        String base = safeName(item.id + "-" + item.title, 70);
        File mp4Out = new File(dir, base + ".mp4");
        File tsOut = new File(dir, base + ".ts");
        if (mp4Out.exists() && mp4Out.length() > 0) return mp4Out;
        if (!convertMp4 && tsOut.exists() && tsOut.length() > 0) return tsOut;

        CDN cdn = parseCdn(hlsUrls);
        if (cdn.hosts.isEmpty() || cdn.pathQuery == null) throw new Exception("HLS 地址无效");

        // 1) 解析 playlist
        List<String> segs = null;
        for (String h : cdn.hosts) {
            if (sig != null && sig.isCancelled()) throw new Cancelled();
            try {
                segs = Api.playlistSegments("https://" + h + cdn.pathQuery);
                if (segs != null && !segs.isEmpty()) break;
            } catch (Exception ignored) {
            }
        }
        if (segs == null || segs.isEmpty()) throw new Exception("m3u8 解析失败");

        final List<String> segments = segs;
        final int total = segments.size();
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final File segDir = new File(dir, base + ".hls");
        if (!segDir.exists()) segDir.mkdirs();

        final BlockingQueue<Integer> queue = new LinkedBlockingQueue<Integer>();
        for (int i = 0; i < total; i++) {
            File sf = new File(segDir, "seg_" + i);
            if (sf.exists() && sf.length() > 0) {
                done.incrementAndGet();
            } else {
                queue.offer(i);
            }
        }
        final AtomicLong gotBytes = new AtomicLong(0);
        final AtomicLong lastBytes = new AtomicLong(0);
        final AtomicLong lastMs = new AtomicLong(System.currentTimeMillis());
        final AtomicLong speed = new AtomicLong(0);

        // 2) 节点测速（单分片，含连接建立成本）
        final List<NodeStat> nodes = measureSegmentHosts(cdn.hosts, segPathQuery(segments.get(0)));
        if (sig != null && sig.isCancelled()) throw new Cancelled();
        if (progress != null) progress.onProgress(done.get(), total, 0);

        // 3) 并行下载分片
        final int threads = Math.max(2, Math.min(16, total));
        final CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            if (sig != null && sig.isCancelled()) return;
                            Integer idx = queue.poll();
                            if (idx == null) return;
                            if (fetchSegment(idx, segments, nodes, segDir, gotBytes)) {
                                done.incrementAndGet();
                                long now = System.currentTimeMillis();
                                long lm = lastMs.get();
                                if (now - lm >= 500 && lastMs.compareAndSet(lm, now)) {
                                    speed.set((gotBytes.get() - lastBytes.get()) * 1000 / Math.max(1, now - lm));
                                    lastBytes.set(gotBytes.get());
                                }
                                if (progress != null) progress.onProgress(done.get(), total, speed.get());
                            } else {
                                errors.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
            th.setDaemon(true);
            th.start();
        }
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        if (sig != null && sig.isCancelled()) throw new Cancelled();
        if (errors.get() > 0) throw new Exception("分片失败 " + errors.get() + "/" + total + "（可继续续传）");

        // 4) 拼接
        File tsTmp = new File(dir, base + ".ts.part");
        FileOutputStream fos = new FileOutputStream(tsTmp);
        byte[] buf = new byte[64 * 1024];
        for (int i = 0; i < total; i++) {
            if (sig != null && sig.isCancelled()) {
                fos.close();
                tsTmp.delete();
                throw new Cancelled();
            }
            FileInputStream in = new FileInputStream(new File(segDir, "seg_" + i));
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            in.close();
        }
        fos.close();
        if (tsOut.exists()) tsOut.delete();
        tsTmp.renameTo(tsOut);
        deleteDir(segDir);

        // 5) 可选转封装 mp4
        if (convertMp4) {
            File tmpMp4 = new File(dir, base + ".mp4.part");
            if (remuxTsToMp4(tsOut, tmpMp4)) {
                if (mp4Out.exists()) mp4Out.delete();
                tmpMp4.renameTo(mp4Out);
                tsOut.delete();
                return mp4Out;
            }
            tmpMp4.delete();
        }
        return tsOut;
    }

    /** 一个分片尝试下载：按节点评分依次试，成功返回 true。 */
    private static boolean fetchSegment(int idx, List<String> segments, List<NodeStat> nodes,
                                        File segDir, AtomicLong gotBytes) {
        final String pathQuery = segPathQuery(segments.get(idx));
        File out = new File(segDir, "seg_" + idx);
        List<NodeStat> ranked = new ArrayList<NodeStat>(nodes);
        java.util.Collections.sort(ranked, new java.util.Comparator<NodeStat>() {
            @Override
            public int compare(NodeStat a, NodeStat b) {
                return Double.compare(b.score(), a.score());
            }
        });
        for (NodeStat n : ranked) {
            n.active.incrementAndGet();
            long t0 = System.currentTimeMillis();
            try {
                fetchToFile("https://" + n.host + pathQuery, out);
                long dt = Math.max(1, System.currentTimeMillis() - t0);
                long sp = out.length() * 1000 / dt;
                gotBytes.addAndGet(out.length());
                synchronized (n) {
                    n.speed = n.samples.getAndIncrement() < 2 ? sp : (n.speed * 2 + sp) / 3;
                    n.inPool = true;
                    n.lastProbe = System.currentTimeMillis();
                }
                return true;
            } catch (Exception e) {
                synchronized (n) {
                    if (n.samples.incrementAndGet() > 2) {
                        n.speed = n.speed / 2;
                        if (n.speed < 40000) n.inPool = false;
                    }
                }
            } finally {
                n.active.decrementAndGet();
            }
        }
        return false;
    }

    /** 各 CDN 用同一个分片测吞吐（Range 前 256KB），剔除明显慢的节点。 */
    private static List<NodeStat> measureSegmentHosts(List<String> hosts, final String segPathQuery) {
        final Map<String, Long> speeds = new ConcurrentHashMap<String, Long>();
        List<Thread> ts = new ArrayList<Thread>();
        for (final String h : hosts) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    speeds.put(h, probeSegment(h, segPathQuery));
                }
            });
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        for (Thread t : ts) {
            try {
                t.join(7000);
            } catch (InterruptedException ignored) {
            }
        }
        long best = 0;
        List<NodeStat> all = new ArrayList<NodeStat>();
        for (String h : hosts) {
            Long v = speeds.get(h);
            long sp = v == null ? 0 : v;
            if (sp > best) best = sp;
            all.add(new NodeStat(h, sp));
        }
        if (best <= 0) return all;
        List<NodeStat> keep = new ArrayList<NodeStat>();
        for (NodeStat n : all) {
            if (n.speed >= best * 3 / 10) keep.add(n);
        }
        return keep.isEmpty() ? all : keep;
    }

    private static long probeSegment(String host, String segPathQuery) {
        HttpURLConnection con = null;
        long got = 0;
        long t0 = System.currentTimeMillis();
        try {
            con = (HttpURLConnection) new URL("https://" + host + segPathQuery).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Range", "bytes=0-262143");
            InputStream in = con.getInputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                got += n;
                if (got >= 262144) break;
                if (System.currentTimeMillis() - t0 > 6000) break;
            }
            in.close();
        } catch (Exception ignored) {
        } finally {
            if (con != null) con.disconnect();
        }
        long dt = Math.max(1, System.currentTimeMillis() - t0);
        return got * 1000 / dt;
    }

    private static String segPathQuery(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        } catch (Exception e) {
            return url;
        }
    }

    private static void fetchToFile(String url, File out) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(45000);
        con.setRequestProperty("User-Agent", UA);
        InputStream in = con.getInputStream();
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
        con.disconnect();
        if (tmp.length() == 0) throw new Exception("空分片");
        if (!tmp.renameTo(out)) throw new Exception("分片写入失败");
    }

    /** TS → MP4 转封装（视频/音频两路 extractor 按时间交错写入）。失败返回 false。 */
    public static boolean remuxTsToMp4(File ts, File mp4) {
        android.media.MediaExtractor exV = null;
        android.media.MediaExtractor exA = null;
        android.media.MediaMuxer mux = null;
        try {
            exV = new android.media.MediaExtractor();
            exV.setDataSource(ts.getAbsolutePath());
            int vIdx = -1, aIdx = -1;
            for (int i = 0; i < exV.getTrackCount(); i++) {
                android.media.MediaFormat f = exV.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && vIdx < 0) vIdx = i;
                else if (mime.startsWith("audio/") && aIdx < 0) aIdx = i;
            }
            if (vIdx < 0 && aIdx < 0) return false;
            if (vIdx >= 0) exV.selectTrack(vIdx);
            if (aIdx >= 0) {
                exA = new android.media.MediaExtractor();
                exA.setDataSource(ts.getAbsolutePath());
                exA.selectTrack(aIdx);
            }
            mux = new android.media.MediaMuxer(mp4.getAbsolutePath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = vIdx >= 0 ? mux.addTrack(exV.getTrackFormat(vIdx)) : -1;
            int aOut = aIdx >= 0 ? mux.addTrack(exA.getTrackFormat(aIdx)) : -1;
            mux.start();
            java.nio.ByteBuffer vBuf = java.nio.ByteBuffer.allocateDirect(4 * 1024 * 1024);
            java.nio.ByteBuffer aBuf = java.nio.ByteBuffer.allocateDirect(1024 * 1024);
            android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
            long vTime = vIdx >= 0 ? exV.getSampleTime() : Long.MAX_VALUE;
            long aTime = (aIdx >= 0 && exA != null) ? exA.getSampleTime() : Long.MAX_VALUE;
            int guard = 0;
            while (guard++ < 20_000_000) {
                if (vTime == Long.MAX_VALUE && aTime == Long.MAX_VALUE) break;
                if (vIdx >= 0 && vTime <= aTime) {
                    int size = exV.readSampleData(vBuf, 0);
                    if (size < 0) {
                        vTime = Long.MAX_VALUE;
                        continue;
                    }
                    bi.offset = 0;
                    bi.size = size;
                    bi.presentationTimeUs = vTime;
                    bi.flags = exV.getSampleFlags();
                    mux.writeSampleData(vOut, vBuf, bi);
                    exV.advance();
                    vTime = exV.getSampleTime();
                } else if (aIdx >= 0 && exA != null) {
                    int size = exA.readSampleData(aBuf, 0);
                    if (size < 0) {
                        aTime = Long.MAX_VALUE;
                        continue;
                    }
                    bi.offset = 0;
                    bi.size = size;
                    bi.presentationTimeUs = aTime;
                    bi.flags = exA.getSampleFlags();
                    mux.writeSampleData(aOut, aBuf, bi);
                    exA.advance();
                    aTime = exA.getSampleTime();
                } else {
                    break;
                }
            }
            try {
                mux.stop();
            } catch (Exception ignored) {
            }
            return mp4.length() > 1024;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (mux != null) mux.release();
            } catch (Exception ignored) {
            }
            try {
                if (exV != null) exV.release();
            } catch (Exception ignored) {
            }
            try {
                if (exA != null) exA.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }
}
