package com.dsh.cosdl;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 视频详情弹窗（主界面 / 收藏夹 / 观看记录 / 稍后再看 共用）：
 * 封面、标题、完整信息、标签跳搜索、播放、下载、稍后再看、收藏。
 */
public final class VideoDetail {

    /** 其它页面点标签时带回主界面搜索用。 */
    public static final String EXTRA_TAG_SEARCH = "tag_search";

    private static final int REQ_STORAGE_DETAIL = 2002;

    /** 主界面自己处理标签跳转时用；不传则启动主界面搜索。 */
    public interface TagClicker {
        void onTag(String tag);
    }

    private VideoDetail() {
    }

    /** 用收藏 / 记录里的最小字段构造条目。 */
    public static VideoItem simple(String id, String title, String photo) {
        VideoItem v = new VideoItem();
        v.id = id == null ? "" : id;
        v.title = title == null ? "" : title;
        v.photo = photo == null ? "" : photo;
        return v;
    }

    public static void show(final Activity act, final VideoItem item) {
        show(act, item, null);
    }

    public static void show(final Activity act, final VideoItem item, final TagClicker tagClicker) {
        if (act == null || item == null || act.isFinishing()) return;
        Settings.init(act);
        Api.init(act);
        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_video);

        final ImageView cover = (ImageView) d.findViewById(R.id.dlgCover);
        final TextView title = (TextView) d.findViewById(R.id.dlgTitle);
        final TextView meta = (TextView) d.findViewById(R.id.dlgMeta);
        final Button dl = (Button) d.findViewById(R.id.dlgDl);

        ImageLoader.get().load(item.photo, cover, 900, 480);
        title.setText(item.title);
        meta.setText(metaFromList(item));
        fillTagBar(act, d, item.tags, item, tagClicker);

        // 异步补全详情：作者、日期、点赞、完整标签
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject info = Api.videoInfo(item.id);
                    final java.util.List<String> tags = new java.util.ArrayList<String>();
                    org.json.JSONArray ta = info.optJSONArray("tags");
                    if (ta != null) {
                        for (int i = 0; i < ta.length(); i++) {
                            String t = ta.optString(i, "");
                            if (t.length() > 0) tags.add(t);
                        }
                    }
                    final String metaText = metaFromInfo(info, item);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (act.isFinishing()) return;
                            meta.setText(metaText);
                            fillTagBar(act, d, tags, item, tagClicker);
                        }
                    });
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!act.isFinishing() && meta.getText().length() == 0) {
                                meta.setText("详情加载失败");
                            }
                        }
                    });
                }
            }
        }, "detail-" + item.id).start();

        ((Button) d.findViewById(R.id.dlgPlay)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                Intent i = new Intent(act, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_ID, item.id);
                i.putExtra(PlayerActivity.EXTRA_TITLE, item.title);
                i.putExtra(PlayerActivity.EXTRA_PHOTO, item.photo);
                act.startActivity(i);
            }
        });

        dl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasStorage(act)) {
                    requestStorage(act);
                    Toast.makeText(act, "先允许存储权限", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean added = TaskManager.get(act).add(item);
                Toast.makeText(act,
                        added ? "已加入下载队列，可在「↓」里查看" : "该视频已在队列中或已下载",
                        Toast.LENGTH_SHORT).show();
                d.dismiss();
            }
        });
        ((Button) d.findViewById(R.id.dlgLater)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = WatchLaterStore.add(act, item.id, item.title, item.photo);
                Toast.makeText(act, ok ? "已加入稍后再看" : "已在稍后再看里", Toast.LENGTH_SHORT).show();
                d.dismiss();
            }
        });
        ((Button) d.findViewById(R.id.dlgFav)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                showFavPicker(act, item);
            }
        });

        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xFF151515));
        d.show();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    // ------------------------------------------------------------ 标签

    private static void fillTagBar(final Activity act, Dialog d, java.util.List<String> tags,
                                   final VideoItem item, final TagClicker tagClicker) {
        final LinearLayout tagBar = (LinearLayout) d.findViewById(R.id.dlgTags);
        View tagScroll = d.findViewById(R.id.dlgTagsScroll);
        if (tagBar == null || tagScroll == null) return;
        if (tags == null || tags.isEmpty()) {
            tagScroll.setVisibility(View.GONE);
            return;
        }
        tagScroll.setVisibility(View.VISIBLE);
        tagBar.removeAllViews();
        for (final String t : tags) {
            TextView chip = new TextView(act);
            chip.setText(t);
            chip.setTextSize(12);
            chip.setTextColor(Settings.accentColor());
            chip.setBackground(ThemeUtil.chipBg(act, false));
            chip.setPadding(dp(act, 10), dp(act, 5), dp(act, 10), dp(act, 5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(act, 6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openTag(act, tagClicker, t);
                }
            });
            tagBar.addView(chip);
        }
    }

    private static void openTag(Activity act, TagClicker tagClicker, String tag) {
        if (tagClicker != null) {
            tagClicker.onTag(tag);
            return;
        }
        Intent i = new Intent(act, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(EXTRA_TAG_SEARCH, tag);
        act.startActivity(i);
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------ 信息文本

    private static String metaFromList(VideoItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.durationText().length() > 0) sb.append("时长 ").append(item.durationText());
        if (item.works != null && item.works.length() > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(item.works);
        }
        if (item.exclusive) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("VIP 抢先看");
        }
        return sb.length() == 0 ? "正在加载详情…" : sb.toString();
    }

    private static String metaFromInfo(JSONObject info, VideoItem item) {
        StringBuilder sb = new StringBuilder();
        String author = info.optString("author", "");
        if (author.length() > 0) sb.append(author);
        try {
            double dv = Double.parseDouble(info.optString("duration", "0"));
            long s = (long) dv;
            long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
            String t = h > 0 ? String.format("%d:%02d:%02d", h, m, sec)
                    : String.format("%d:%02d", m, sec);
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("时长 ").append(t);
        } catch (Exception ignored) {
        }
        String date = info.optString("adddate", "");
        if (date.length() > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(date);
        }
        String works = info.optString("cos_works", "");
        if (works.length() == 0) works = info.optString("series", "");
        if (works.length() > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(works);
        }
        String views = info.optString("viewnumber", "");
        if (views.length() > 0 && !"0".equals(views)) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("浏览 ").append(views);
        }
        String likes = info.optString("likes", "");
        if (likes.length() > 0 && !"0".equals(likes)) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("赞 ").append(likes);
        }
        if (item.exclusive) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("VIP 抢先看");
        }
        return sb.length() == 0 ? "无更多信息" : sb.toString();
    }

    // ------------------------------------------------------------ 收藏夹选择

    public static void showFavPicker(final Activity act, final VideoItem item) {
        final java.util.List<String[]> tree = new java.util.ArrayList<String[]>();
        tree.add(new String[]{"", "（根目录）"});
        tree.addAll(CollectionStore.tree(act, null));
        tree.add(new String[]{"__new__", "＋ 新建收藏夹"});
        String[] labels = new String[tree.size()];
        for (int i = 0; i < tree.size(); i++) labels[i] = tree.get(i)[1];
        new android.app.AlertDialog.Builder(act)
                .setTitle("收藏到")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        final String id = tree.get(which)[0];
                        if ("__new__".equals(id)) {
                            final EditText et = new EditText(act);
                            et.setHint("收藏夹名称");
                            new android.app.AlertDialog.Builder(act)
                                    .setTitle("新建收藏夹")
                                    .setView(et)
                                    .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface dialog2, int w2) {
                                            String name = et.getText().toString().trim();
                                            if (name.length() == 0) return;
                                            CollectionStore.Folder f = CollectionStore.create(act, name, "");
                                            CollectionStore.add(act, item.id, item.title, item.photo, f.id);
                                            Toast.makeText(act, "已收藏到「" + name + "」", Toast.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                            return;
                        }
                        boolean ok = CollectionStore.add(act, item.id, item.title, item.photo, id);
                        Toast.makeText(act, ok ? "已加入收藏夹" : "已在收藏夹中", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ 权限

    private static boolean hasStorage(Activity act) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return act.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestStorage(Activity act) {
        if (Build.VERSION.SDK_INT < 23) return;
        act.requestPermissions(new String[]{
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
        }, REQ_STORAGE_DETAIL);
    }
}
