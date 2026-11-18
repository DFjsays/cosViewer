package com.dsh.cosdl;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 下载管理：下载中 / 已下载 统一管理。 */
public class DownloadsActivity extends Activity implements DownloadAdapter.Listener {

    private ListView list;
    private TextView empty;
    private TextView tabActive;
    private TextView tabDone;

    private DownloadAdapter adapter;
    private TaskManager tm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean showDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);
        tm = TaskManager.get(this);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        tabActive = (TextView) findViewById(R.id.tabActive);
        tabDone = (TextView) findViewById(R.id.tabDone);

        adapter = new DownloadAdapter(this, this);
        list.setAdapter(adapter);

        tabActive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDone = false;
                refresh();
            }
        });
        tabDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDone = true;
                refresh();
            }
        });
        findViewById(R.id.btnDir).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStorageMenu();
            }
        });
        findViewById(R.id.btnPauseAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tm.pauseAll();
                Toast.makeText(DownloadsActivity.this, "已暂停全部任务", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        findViewById(R.id.btnStartAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tm.startAll();
                refresh();
            }
        });
    }

    /** 存储菜单：目录设置 / 缓存清理。 */
    private void showStorageMenu() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("存储")
                .setItems(new String[]{"下载目录设置", "下载设置（模式/格式）", "缓存清理"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                if (which == 0) showDirSettings();
                                else if (which == 1) showDownloadSettings();
                                else showCleanup();
                            }
                        })
                .show();
    }

    private java.io.File taskFile(com.dsh.cosdl.DownloadTask t) {
        VideoItem v = new VideoItem();
        v.id = t.videoId;
        v.title = t.title;
        return new java.io.File(Downloader.downloadDir(), Downloader.fileNameFor(v));
    }

    private void showDownloadSettings() {
        String modeLabel = "hls".equals(Settings.downloadMode()) ? "仅 HLS 分片"
                : ("mp4".equals(Settings.downloadMode()) ? "仅 MP4 直链" : "自动（HLS 优先）");
        String[] items = {
                "下载模式：" + modeLabel,
                "TS 转 MP4：" + (Settings.tsToMp4() ? "开" : "关")
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("下载设置")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) chooseMode();
                        else {
                            Settings.setTsToMp4(!Settings.tsToMp4());
                            Toast.makeText(DownloadsActivity.this,
                                    Settings.tsToMp4() ? "已开启 TS 转 MP4" : "已关闭，保存为 .ts",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void chooseMode() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("下载模式")
                .setItems(new String[]{"自动（HLS 分片优先，推荐）", "仅 MP4 直链（兼容性最好）", "仅 HLS 分片（最快）"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                Settings.setDownloadMode(which == 0 ? "auto" : (which == 1 ? "mp4" : "hls"));
                                Toast.makeText(DownloadsActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                            }
                        })
                .show();
    }

    private void showCleanup() {
        java.util.List<DownloadTask> tasks = tm.tasks();
        long tmpSize = 0, doneSize = 0;
        int tmpCount = 0, doneCount = 0;
        for (DownloadTask t : tasks) {
            java.io.File base = taskFile(t);
            java.io.File parts = new java.io.File(base.getAbsolutePath() + ".parts");
            if (parts.exists()) {
                tmpSize += dirSize(parts);
                tmpCount++;
            }
            if (base.exists() && base.getName().endsWith(".mp4")) {
                doneSize += base.length();
                doneCount++;
            }
        }
        String msg = "只清理本 App 下载的文件（不动同目录下其他内容）\n\n"
                + "临时分块：" + Downloader.human(tmpSize) + "（" + tmpCount + " 个任务）\n"
                + "已完成视频：" + Downloader.human(doneSize) + "（" + doneCount + " 个）";
        new android.app.AlertDialog.Builder(this)
                .setTitle("缓存清理")
                .setMessage(msg)
                .setItems(new String[]{"清理临时分块（断点续传数据）", "删除已完成的视频", "清理临时+已完成"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                if (which == 0) cleanParts();
                                else if (which == 1) cleanDone();
                                else {
                                    cleanParts();
                                    cleanDone();
                                }
                                refresh();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private long dirSize(java.io.File d) {
        long n = 0;
        java.io.File[] fs = d.listFiles();
        if (fs != null) for (java.io.File f : fs) {
            n += f.isDirectory() ? dirSize(f) : f.length();
        }
        return n;
    }

    private void cleanParts() {
        for (DownloadTask t : tm.tasks()) {
            java.io.File base = taskFile(t);
            deleteRec(new java.io.File(base.getAbsolutePath() + ".parts"));
        }
        Toast.makeText(this, "临时分块已清理", Toast.LENGTH_SHORT).show();
    }

    private void cleanDone() {
        int n = 0;
        for (DownloadTask t : tm.tasks()) {
            if (t.isDone()) {
                java.io.File f = taskFile(t);
                if (f.exists() && f.delete()) n++;
            }
        }
        Toast.makeText(this, "已删除 " + n + " 个视频文件", Toast.LENGTH_SHORT).show();
    }

    private void deleteRec(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] fs = f.listFiles();
            if (fs != null) for (java.io.File x : fs) deleteRec(x);
        }
        f.delete();
    }

    /** 下载目录设置（从主菜单移到这里）。 */
    private void showDirSettings() {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_settings);
        final android.widget.EditText et = (android.widget.EditText) d.findViewById(R.id.setDir);
        et.setText(Settings.dir());
        d.findViewById(R.id.setDefault).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                et.setText(Settings.DEFAULT_DIR);
            }
        });
        d.findViewById(R.id.setOpen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = et.getText().toString().trim();
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dir", p));
                Toast.makeText(DownloadsActivity.this, "路径已复制", Toast.LENGTH_SHORT).show();
            }
        });
        d.findViewById(R.id.setSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = et.getText().toString().trim();
                if (p.length() == 0) p = Settings.DEFAULT_DIR;
                java.io.File f = new java.io.File(p);
                if (!f.exists() && !f.mkdirs()) {
                    Toast.makeText(DownloadsActivity.this, "目录无法创建", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!f.isDirectory() || !f.canWrite()) {
                    Toast.makeText(DownloadsActivity.this, "目录不可写", Toast.LENGTH_LONG).show();
                    return;
                }
                Settings.setDir(p);
                d.dismiss();
                Toast.makeText(DownloadsActivity.this, "下载目录已设为:\n" + p, Toast.LENGTH_LONG).show();
            }
        });
        android.view.Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF151515));
        d.show();
        if (w != null) w.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void styleTabs() {
        int on = ThemeUtil.onColor(Settings.accentColor());
        tabDone.setBackground(ThemeUtil.chipBg(this, showDone));
        tabDone.setTextColor(showDone ? on : 0xFFCCCCCC);
        tabActive.setBackground(ThemeUtil.chipBg(this, !showDone));
        tabActive.setTextColor(!showDone ? on : 0xFFCCCCCC);
    }

    private void refresh() {
        styleTabs();
        List<DownloadTask> all = tm.tasks();
        List<DownloadTask> show = new ArrayList<DownloadTask>();
        for (DownloadTask t : all) {
            if (showDone ? t.isDone() : !t.isDone()) show.add(t);
        }
        adapter.update(show);
        empty.setText(showDone ? "还没有已下载的视频" : "没有正在下载的任务\n在视频详情里点「下载到本地」即可加入");
        empty.setVisibility(show.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(show.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void scheduleRefresh() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
                scheduleRefresh();
            }
        }, 500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        scheduleRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------ 列表事件

    @Override
    public void onItemClick(final DownloadTask t) {
        if (!t.isDone()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(t.title)
                .setItems(new String[]{"播放本地文件", "查看详情"}, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            openTask(t);
                        } else {
                            VideoDetail.show(DownloadsActivity.this,
                                    VideoDetail.simple(t.videoId, t.title, t.photo));
                        }
                    }
                })
                .show();
    }

    @Override
    public void onAction(DownloadTask t) {
        if (DownloadTask.RUNNING.equals(t.status) || DownloadTask.QUEUED.equals(t.status)) {
            tm.pause(t);
        } else if (DownloadTask.PAUSED.equals(t.status) || DownloadTask.ERROR.equals(t.status)) {
            tm.resume(t);
        } else if (t.isDone()) {
            openTask(t);
        }
        refresh();
    }

    @Override
    public void onDelete(DownloadTask t) {
        tm.remove(t);
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void openTask(DownloadTask t) {
        if (t.filePath == null || t.filePath.length() == 0) {
            Toast.makeText(this, "文件路径缺失", Toast.LENGTH_SHORT).show();
            return;
        }
        VideoOpener.open(this, new File(t.filePath));
    }
}
