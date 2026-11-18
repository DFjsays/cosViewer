package com.dsh.cosdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 基于 GitHub Releases 的检查更新。
 *
 * 约定：发布时打 tag（如 v10.4），并把签名 APK 作为 release 附件上传。
 * 版本比较用 tag 里的数字（10.4）与本地 versionName 比较，逐段数值比较。
 */
public final class UpdateChecker {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final String API = "https://api.github.com/repos/%s/%s/releases/latest";
    private static final String UA = "cosViewer-updater";
    private static final String APK_DIR = "/sdcard/Download/DSH-Go";
    private static boolean checking = false;

    private UpdateChecker() {
    }

    public static void autoCheck(final Activity act) {
        check(act, true);
    }

    public static void manualCheck(final Activity act) {
        check(act, false);
    }

    private static boolean configured() {
        String o = Secret.GH_OWNER == null ? "" : Secret.GH_OWNER.trim();
        return o.length() > 0 && !o.startsWith("YOUR_")
                && Secret.GH_REPO != null && Secret.GH_REPO.trim().length() > 0;
    }

    private static void check(final Activity act, final boolean silent) {
        if (act == null || act.isFinishing()) return;
        if (checking) return;
        if (!configured()) {
            if (!silent) {
                Toast.makeText(act,
                        "未配置更新仓库（secret.properties 里的 update.owner / update.repo）",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        checking = true;
        if (!silent) Toast.makeText(act, "正在检查更新…", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject rel = fetchLatest();
                    final String tag = rel.optString("tag_name", "");
                    final String body = rel.optString("body", "");
                    final long assetId = findApkAssetId(rel);
                    final String apkUrl = findApkBrowserUrl(rel);
                    final long size = findApkSize(rel);
                    final String remote = normalizeVersion(tag);
                    final String local = localVersion(act);
                    if (remote == null) {
                        throw new Exception("release 标签不是版本号：" + tag);
                    }
                    if (compare(remote, local) <= 0) {
                        if (!silent) {
                            UI.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(act, "已是最新版本 " + local, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        return;
                    }
                    if (assetId == 0 && apkUrl == null) {
                        if (!silent) {
                            UI.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(act, "新版本 " + remote + " 没有 APK 附件", Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                        return;
                    }
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            prompt(act, remote, local, body, assetId, apkUrl, size);
                        }
                    });
                } catch (final Exception e) {
                    if (!silent) {
                        UI.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(act, "检查更新失败：" + msgOf(e), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } finally {
                    checking = false;
                }
            }
        }, "update-check").start();
    }

    // ------------------------------------------------------------ 网络

    private static JSONObject fetchLatest() throws Exception {
        String url = String.format(API, Secret.GH_OWNER.trim(), Secret.GH_REPO.trim());
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(12000);
        con.setReadTimeout(15000);
        con.setRequestProperty("User-Agent", UA);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        int code = con.getResponseCode();
        if (code == 404) throw new Exception("仓库或 release 不存在（404）");
        if (code == 403) throw new Exception("GitHub 接口限流，稍后再试");
        if (code != 200) throw new Exception("GitHub 返回 " + code);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        InputStream in = con.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        con.disconnect();
        return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
    }

    /** 返回 APK 附件的 asset id（下载走 api.github.com，国内网络下 github.com 往往不通）。 */
    private static long findApkAssetId(JSONObject rel) {
        JSONArray arr = rel.optJSONArray("assets");
        if (arr == null) return 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (name.toLowerCase().endsWith(".apk")) return a.optLong("id", 0);
        }
        return 0;
    }

    private static String findApkBrowserUrl(JSONObject rel) {
        JSONArray arr = rel.optJSONArray("assets");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (name.toLowerCase().endsWith(".apk")) return a.optString("browser_download_url", null);
        }
        return null;
    }

    private static long findApkSize(JSONObject rel) {
        JSONArray arr = rel.optJSONArray("assets");
        if (arr == null) return 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (name.toLowerCase().endsWith(".apk")) return a.optLong("size", 0);
        }
        return 0;
    }

    // ------------------------------------------------------------ 版本号

    private static String normalizeVersion(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c) || c == '.') sb.append(c);
            else break;
        }
        String v = sb.toString();
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        return v.length() == 0 ? null : v;
    }

    private static String localVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 逐段数值比较：a>b 返回正数。 */
    private static int compare(String a, String b) {
        String[] xs = a.split("\\.");
        String[] ys = b.split("\\.");
        int n = Math.max(xs.length, ys.length);
        for (int i = 0; i < n; i++) {
            int x = i < xs.length ? parseInt(xs[i]) : 0;
            int y = i < ys.length ? parseInt(ys[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 界面

    private static void prompt(final Activity act, final String remote, final String local,
                               final String body, final long assetId, final String apkUrl, long size) {
        if (act.isFinishing()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("当前版本：").append(local).append("\n");
        sb.append("最新版本：").append(remote);
        if (size > 0) sb.append("（").append(Downloader.human(size)).append("）");
        String notes = body == null ? "" : body.trim();
        if (notes.length() > 0) {
            if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
            sb.append("\n\n").append(notes);
        }
        new AlertDialog.Builder(act)
                .setTitle("发现新版本")
                .setMessage(sb.toString())
                .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        download(act, remote, assetId, apkUrl);
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    private static void download(final Activity act, final String version,
                                 final long assetId, final String fallbackUrl) {
        if (!UpdateChecker.hasStorage(act)) {
            Toast.makeText(act, "先允许存储权限", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean[] aborted = {false};
        File dir = new File(APK_DIR);
        if (!dir.exists()) dir.mkdirs();
        final File out = new File(dir, "cosViewer-" + version + ".apk");
        if (out.exists() && out.length() > 0) {
            out.delete();
        }

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        final TextView txt = new TextView(act);
        txt.setText("0%");
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(txt);
        box.addView(bar);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("正在下载 " + version)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        aborted[0] = true;
                    }
                })
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection con = null;
                InputStream in = null;
                FileOutputStream fos = null;
                try {
                    String url = assetId > 0
                            ? String.format("https://api.github.com/repos/%s/%s/releases/assets/%d",
                                    Secret.GH_OWNER.trim(), Secret.GH_REPO.trim(), assetId)
                            : fallbackUrl;
                    if (url == null) throw new Exception("没有可下载的 APK");
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(30000);
                    con.setInstanceFollowRedirects(true);
                    con.setRequestProperty("User-Agent", UA);
                    if (assetId > 0) con.setRequestProperty("Accept", "application/octet-stream");
                    long total = con.getContentLengthLong();
                    in = con.getInputStream();
                    fos = new FileOutputStream(out);
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    long lastUi = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (aborted[0]) throw new Exception("已取消");
                        fos.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 200) {
                            lastUi = now;
                            final int pct = total > 0 ? (int) (got * 100 / total) : 0;
                            final String text = total > 0
                                    ? pct + "%  " + Downloader.human(got) + " / " + Downloader.human(total)
                                    : Downloader.human(got);
                            UI.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (aborted[0]) return;
                                    bar.setProgress(pct);
                                    txt.setText(text);
                                }
                            });
                        }
                    }
                    fos.close();
                    fos = null;
                    in.close();
                    in = null;
                    con.disconnect();
                    con = null;
                    if (got <= 0) throw new Exception("下载为空");
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            dlg.dismiss();
                            install(act, out);
                        }
                    });
                } catch (final Exception e) {
                    out.delete();
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            dlg.dismiss();
                            Toast.makeText(act, "下载失败：" + msgOf(e), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    try {
                        if (fos != null) fos.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        if (in != null) in.close();
                    } catch (Exception ignored) {
                    }
                    if (con != null) con.disconnect();
                }
            }
        }, "update-download").start();
    }

    private static void install(Activity act, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26
                    && !act.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(act, "请先允许「安装未知应用」，然后手动安装 " + apk.getName(),
                        Toast.LENGTH_LONG).show();
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName()));
                act.startActivity(i);
                return;
            }
            Uri uri = Uri.parse("content://" + FileProvider.AUTHORITY + apk.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "启动安装失败，APK 在 " + apk.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ 杂项

    private static boolean hasStorage(Activity act) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return act.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String msgOf(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }
}
