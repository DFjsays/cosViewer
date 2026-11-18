package com.dsh.cosdl;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 1001;
    private static final int PAGE_SIZE = 30;

    private EditText input;
    private TextView status;
    private GridView grid;
    private LinearLayout tabsBar;
    private LinearLayout filtersBar;

    private VideoAdapter adapter;
    private final List<VideoItem> items = new ArrayList<VideoItem>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Category> cats = new ArrayList<Category>();
    private final List<Category> subs = new ArrayList<Category>();

    private String curTabId = "new";   // new / hot / exclusive / search / 分类 CHID
    private String curCt = null;       // 分类 CHID
    private String curSubCt = null;    // 子分类 CHID
    private String curTag = null;      // 独家
    private String curOrder = "mr";    // "" / "mr" / "mv"
    private String curKw = null;       // 搜索词
    private String curTags = "";       // 标签筛选
    private boolean searchActive = false;
    private String searchMode = "name";   // name / tag
    private String searchKind = "video";  // video / album
    private final java.util.List<TagGroup> tagGroups = new ArrayList<TagGroup>();

    private static class TagGroup {
        final java.util.List<String> req = new ArrayList<String>();
        final java.util.List<String> exc = new ArrayList<String>();

        boolean matches(VideoItem it) {
            for (String r : req) {
                if (!it.tags.contains(r)) return false;
            }
            for (String e : exc) {
                if (it.tags.contains(e)) return false;
            }
            return true;
        }
    }

    private boolean selecting = false;
    private final java.util.Set<String> selected = new java.util.HashSet<String>();
    private View resumeCard;
    private View batchBar;
    private TextView batchCount;
    private boolean loading = false;
    private boolean hasMore = true;
    private int curPage = 0;
    private int serverCursor = 0;
    private final java.util.List<Integer> pageStarts = new java.util.ArrayList<Integer>();
    private Button btnPrevPage, btnNextPage;
    private TextView pageText;
    private int page = 0;
    private int loaded = 0;
    private volatile int totalCnt = 0;
    private int loadSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Settings.init(this);
        Api.init(this);
        bindViews();
        requestStorage();
        cleanOldLogs();
        applyTheme();
        updateResumeCard();
        loadCategories();
        handleSearchIntent(getIntent());
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                UpdateChecker.autoCheck(MainActivity.this);
            }
        }, 4000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSearchIntent(intent);
    }

    /** 收藏夹 / 记录页点标签跳回主界面搜索。 */
    private void handleSearchIntent(Intent it) {
        if (it == null) return;
        final String tag = it.getStringExtra(VideoDetail.EXTRA_TAG_SEARCH);
        if (tag == null || tag.length() == 0) return;
        it.removeExtra(VideoDetail.EXTRA_TAG_SEARCH);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                searchByTag(tag);
            }
        }, 250);
    }

    /** 删除旧版本遗留在下载目录里的日志文件。 */
    private void cleanOldLogs() {
        try {
            new java.io.File("/sdcard/Download/DSH-Go/mpv.log").delete();
            new java.io.File("/sdcard/Download/DSH-Go/ui.log").delete();
        } catch (Exception ignored) {
        }
    }

    private void applyTheme() {
        int accent = Settings.accentColor();
        ((Button) findViewById(R.id.btnSearch)).setBackgroundColor(accent);
        if (pageText != null) pageText.setTextColor(accent);
        android.widget.LinearLayout root = (android.widget.LinearLayout) findViewById(R.id.mainRoot);
        String bg = Settings.bgImage();
        if (bg != null && bg.length() > 0 && new File(bg).exists()) {
            try {
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(bg);
                if (bm != null) {
                    android.graphics.drawable.BitmapDrawable bd =
                            new android.graphics.drawable.BitmapDrawable(getResources(), bm);
                    bd.setAlpha((int) (Settings.bgAlpha() * 255 / 100f));
                    android.graphics.drawable.LayerDrawable ld =
                            new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                                    bd, new android.graphics.drawable.ColorDrawable(0xCC0E0E0E)});
                    root.setBackground(ld);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        root.setBackgroundColor(0xFF0E0E0E);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cleanOldLogs();
        applyTheme();
        updateResumeCard();
    }

    private void updateResumeCard() {
        if (resumeCard == null) return;
        java.util.List<WatchStore.Record> list = WatchStore.all(this);
        if (list.isEmpty()) {
            resumeCard.setVisibility(View.GONE);
            return;
        }
        final WatchStore.Record r = list.get(0);
        resumeCard.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.resumeTitle)).setText(r.title);
        String meta = "继续播放";
        if (r.position > 0) {
            long s = r.position;
            meta += " · " + String.format("%02d:%02d", (s % 3600) / 60, s % 60);
        }
        meta += " · " + r.timeText();
        ((TextView) findViewById(R.id.resumeMeta)).setText(meta);
        ImageLoader.get().load(r.photo, (ImageView) findViewById(R.id.resumeCover), 240, 240);
    }

    private void openLastWatched() {
        java.util.List<WatchStore.Record> list = WatchStore.all(this);
        if (list.isEmpty()) return;
        WatchStore.Record r = list.get(0);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ID, r.videoId);
        i.putExtra(PlayerActivity.EXTRA_TITLE, r.title);
        i.putExtra(PlayerActivity.EXTRA_PHOTO, r.photo);
        startActivity(i);
    }

    // ------------------------------------------------------------ 批量操作

    private void enterSelect(String videoId) {
        selecting = true;
        selected.clear();
        selected.add(videoId);
        batchBar.setVisibility(View.VISIBLE);
        updateBatchBar();
        adapter.setSelection(true, selected);
    }

    private void exitSelect() {
        selecting = false;
        selected.clear();
        batchBar.setVisibility(View.GONE);
        adapter.setSelection(false, selected);
    }

    private void updateBatchBar() {
        if (batchCount != null) batchCount.setText("已选 " + selected.size());
        batchBar.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        if (selected.isEmpty()) {
            selecting = false;
            adapter.setSelection(false, selected);
        }
    }

    private List<VideoItem> selectedItems() {
        List<VideoItem> out = new ArrayList<VideoItem>();
        for (VideoItem it : items) {
            if (selected.contains(it.id)) out.add(it);
        }
        return out;
    }

    private void batchDownload() {
        List<VideoItem> sel = selectedItems();
        if (sel.isEmpty()) return;
        int n = 0;
        for (VideoItem it : sel) {
            if (TaskManager.get(this).add(it)) n++;
        }
        Toast.makeText(this, "已加入下载队列 " + n + " 个", Toast.LENGTH_SHORT).show();
        exitSelect();
    }

    private void batchFav() {
        List<VideoItem> sel = selectedItems();
        if (sel.isEmpty()) return;
        int n = 0;
        for (VideoItem it : sel) {
            if (CollectionStore.add(this, it.id, it.title, it.photo, "")) n++;
        }
        Toast.makeText(this, "已收藏 " + n + " 个到根目录", Toast.LENGTH_SHORT).show();
        exitSelect();
    }

    private void batchLater() {
        List<VideoItem> sel = selectedItems();
        if (sel.isEmpty()) return;
        int n = 0;
        for (VideoItem it : sel) {
            if (WatchLaterStore.add(this, it.id, it.title, it.photo)) n++;
        }
        Toast.makeText(this, "已加入稍后再看 " + n + " 个", Toast.LENGTH_SHORT).show();
        exitSelect();
    }

    private void batchHide() {
        List<VideoItem> sel = selectedItems();
        if (sel.isEmpty()) return;
        for (VideoItem it : sel) {
            Settings.addHiddenId(it.id);
            items.remove(it);
        }
        adapter.notifyDataSetChanged();
        adapter.setSelection(false, selected);
        exitSelect();
        Toast.makeText(this, "已屏蔽，可在菜单里清除", Toast.LENGTH_SHORT).show();
        loadPage(curPage);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void bindViews() {
        input = (EditText) findViewById(R.id.input);
        status = (TextView) findViewById(R.id.status);
        grid = (GridView) findViewById(R.id.grid);
        tabsBar = (LinearLayout) findViewById(R.id.tabs);
        filtersBar = (LinearLayout) findViewById(R.id.filters);

        adapter = new VideoAdapter(this, items);
        grid.setAdapter(adapter);

        resumeCard = findViewById(R.id.resumeCard);
        batchBar = findViewById(R.id.batchBar);
        batchCount = (TextView) findViewById(R.id.batchCount);
        resumeCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLastWatched();
            }
        });
        findViewById(R.id.resumePlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLastWatched();
            }
        });
        findViewById(R.id.batchDownload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batchDownload();
            }
        });
        findViewById(R.id.batchFav).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batchFav();
            }
        });
        findViewById(R.id.batchLater).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batchLater();
            }
        });
        findViewById(R.id.batchHide).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batchHide();
            }
        });
        findViewById(R.id.batchCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitSelect();
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) {
                    enterSelect(items.get(position).id);
                }
                return true;
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= items.size()) return;
                if (selecting) {
                    String vid = items.get(position).id;
                    if (selected.contains(vid)) selected.remove(vid);
                    else selected.add(vid);
                    updateBatchBar();
                    adapter.setSelection(true, selected);
                } else {
                    showVideo(items.get(position));
                }
            }
        });
        btnPrevPage = (Button) findViewById(R.id.btnPrevPage);
        btnNextPage = (Button) findViewById(R.id.btnNextPage);
        pageText = (TextView) findViewById(R.id.pageText);
        btnPrevPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (curPage > 0) loadPage(curPage - 1);
            }
        });
        btnNextPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasMore) loadPage(curPage + 1);
            }
        });
        pageText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpToPage();
            }
        });

        findViewById(R.id.btnSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        });
        findViewById(R.id.btnDownloads).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DownloadsActivity.class));
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        input.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (input.getText().toString().trim().length() == 0) showSearchHistory();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });
    }

    private void requestStorage() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
        }
    }

    // ------------------------------------------------------------ 分类 tabs

    private void loadCategories() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Category> list = Api.categories();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cats.clear();
                            cats.addAll(list);
                            renderTabs();
                            renderFilters();
                            loadPage(0);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            renderTabs();
                            renderFilters();
                            status.setText("分类加载失败：" + e.getMessage());
                            loadPage(0);
                        }
                    });
                }
            }
        }).start();
    }

    private TextView makeChip(String text, boolean on) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setBackground(ThemeUtil.chipBg(this, on));
        tv.setTextColor(on ? ThemeUtil.onColor(Settings.accentColor()) : 0xFFCCCCCC);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        tv.setLayoutParams(lp);
        tv.setGravity(android.view.Gravity.CENTER);
        return tv;
    }

    private void renderTabs() {
        tabsBar.removeAllViews();
        if (searchActive) {
            addTab("视频", "__video__");
            addTab("相片", "__album__");
            return;
        }
        addTab("最新", "new");
        addTab("热门", "hot");
        addTab("独家", "exclusive");
        for (Category c : cats) addTab(c.name, c.id);
    }

    private void addTab(String label, final String id) {
        TextView chip = makeChip(label, id.equals(curTabId));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(id);
            }
        });
        tabsBar.addView(chip);
    }

    private void selectTab(String id) {
        if ("__video__".equals(id)) {
            searchKind = "video";
            loadPage(0);
            return;
        }
        if ("__album__".equals(id)) {
            Intent i = new Intent(this, AlbumActivity.class);
            i.putExtra("kw", curKw == null ? "" : curKw);
            startActivity(i);
            return;
        }
        searchActive = false;
        curTabId = id;
        curKw = null;
        input.setText("");
        curSubCt = null;
        subs.clear();
        if ("new".equals(id)) {
            curOrder = "mr";
            curTag = null;
            curCt = null;
        } else if ("hot".equals(id)) {
            curOrder = "mv";
            curTag = null;
            curCt = null;
        } else if ("exclusive".equals(id)) {
            curOrder = "";
            curTag = "独家";
            curCt = null;
        } else {
            curCt = id;
            curTag = null;
            curOrder = "";
        }
        renderTabs();
        renderFilters();
        if (curCt != null) loadSubs(curCt);
        loadPage(0);
    }

    private void loadSubs(final String ct) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Category> list = Api.subCategories(ct);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!ct.equals(curCt)) return;
                            subs.clear();
                            subs.addAll(list);
                            renderFilters();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void renderFilters() {
        filtersBar.removeAllViews();
        if (searchActive) {
            addFilter("按名称", "name".equals(searchMode), new Runnable() {
                @Override
                public void run() {
                    searchMode = "name";
                    renderTabs();
                    renderFilters();
                    loadPage(0);
                }
            });
            addFilter("按标签", "tag".equals(searchMode), new Runnable() {
                @Override
                public void run() {
                    searchMode = "tag";
                    parseTagExpr(input.getText().toString().trim());
                    renderTabs();
                    renderFilters();
                    loadPage(0);
                }
            });
            addFilter("清除标签", !tagGroups.isEmpty(), new Runnable() {
                @Override
                public void run() {
                    tagGroups.clear();
                    input.setText("");
                    searchActive = false;
                    renderTabs();
                    renderFilters();
                    loadPage(0);
                }
            });
            addFilter("返回主页", false, new Runnable() {
                @Override
                public void run() {
                    exitSearch();
                }
            });
            return;
        }
        addFilter("默认排序", curOrder.length() == 0, new Runnable() {
            @Override
            public void run() {
                setOrder("");
            }
        });
        addFilter("最新", "mr".equals(curOrder), new Runnable() {
            @Override
            public void run() {
                setOrder("mr");
            }
        });
        addFilter("热门", "mv".equals(curOrder), new Runnable() {
            @Override
            public void run() {
                setOrder("mv");
            }
        });
        if (!subs.isEmpty()) {
            addFilter("全部", curSubCt == null, new Runnable() {
                @Override
                public void run() {
                    setSub(null);
                }
            });
            for (final Category c : subs) {
                addFilter(c.name, c.id.equals(curSubCt), new Runnable() {
                    @Override
                    public void run() {
                        setSub(c.id);
                    }
                });
            }
        }
    }

    private void addFilter(String label, boolean on, final Runnable action) {
        TextView chip = makeChip(label, on);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        filtersBar.addView(chip);
    }

    private void setOrder(String order) {
        curOrder = order == null ? "" : order;
        renderFilters();
        loadPage(0);
    }

    private void setSub(String subId) {
        curSubCt = subId;
        renderFilters();
        loadPage(0);
    }

    // ------------------------------------------------------------ 列表

    private String buildQuery() {
        List<String> parts = new ArrayList<String>();
        try {
            if (searchActive && "tag".equals(searchMode)) {
                String first = firstTagForQuery();
                if (first.length() == 0) first = curKw;
                if (first != null && first.length() > 0) parts.add("tags=" + URLEncoder.encode(first, "UTF-8"));
            } else if (curKw != null) {
                parts.add("kw=" + URLEncoder.encode(curKw, "UTF-8"));
            }
            if (curCt != null) {
                parts.add("ct=" + (curSubCt != null ? curSubCt : curCt));
            } else if (curTag != null) {
                parts.add("tags=" + URLEncoder.encode(curTag, "UTF-8"));
            }
            if (curTags.length() > 0) parts.add("tags=" + URLEncoder.encode(curTags, "UTF-8"));
            if (curOrder.length() > 0) parts.add("order=" + curOrder);
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void doSearch() {
        String kw = input.getText().toString().trim();
        if (kw.length() == 0) return;
        curKw = kw;
        searchActive = true;
        searchKind = "video";
        curTabId = "search";
        curCt = null;
        curSubCt = null;
        curTag = null;
        subs.clear();
        SearchStore.add(this, kw);
        parseTagExpr(kw);
        renderTabs();
        renderFilters();
        loadPage(0);
    }

    /**
     * 标签表达式：
     *   a+b  = 同时含 a、b
     *   a-b  = 含 a 且不含 b
     *   "a b"（空格）= a 或 b
     */
    private void parseTagExpr(String s) {
        tagGroups.clear();
        if (s == null) return;
        String[] parts = s.trim().split("\\s+");
        for (String part : parts) {
            if (part.length() == 0) continue;
            TagGroup g = new TagGroup();
            StringBuilder cur = new StringBuilder();
            boolean exclude = false;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c == '+' || c == '-') {
                    String t = cur.toString().trim();
                    if (t.length() > 0) {
                        if (exclude) g.exc.add(t);
                        else g.req.add(t);
                    }
                    cur.setLength(0);
                    exclude = (c == '-');
                } else {
                    cur.append(c);
                }
            }
            String t = cur.toString().trim();
            if (t.length() > 0) {
                if (exclude) g.exc.add(t);
                else g.req.add(t);
            }
            if (!g.req.isEmpty() || !g.exc.isEmpty()) tagGroups.add(g);
        }
        if (tagGroups.isEmpty() && s.trim().length() > 0) {
            TagGroup g = new TagGroup();
            g.req.add(s.trim());
            tagGroups.add(g);
        }
    }

    private boolean matchTagExpr(VideoItem it) {
        if (tagGroups.isEmpty()) return true;
        for (TagGroup g : tagGroups) {
            if (g.matches(it)) return true;
        }
        return false;
    }

    private String firstTagForQuery() {
        for (TagGroup g : tagGroups) {
            if (!g.req.isEmpty()) return g.req.get(0);
        }
        return "";
    }

    private void searchByTag(String tag) {
        searchMode = "tag";
        input.setText(tag);
        doSearch();
    }

    @Override
    public void onBackPressed() {
        if (searchActive) {
            exitSearch();
            return;
        }
        super.onBackPressed();
    }

    private void exitSearch() {
        searchActive = false;
        curKw = null;
        input.setText("");
        tagGroups.clear();
        curTabId = "new";
        curOrder = "mr";
        renderTabs();
        renderFilters();
        loadPage(0);
    }

    private void showSearchHistory() {
        final java.util.List<String> list = SearchStore.all(this);
        if (list.isEmpty()) return;
        final String[] arr = list.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle("搜索记录")
                .setItems(arr, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        input.setText(arr[which]);
                        doSearch();
                    }
                })
                .setNeutralButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        SearchStore.clear(MainActivity.this);
                    }
                })
                .show();
    }

    /** 单条是否通过过滤（VIP/手动屏蔽/标签屏蔽/标签表达式）。 */
    private boolean passVideo(VideoItem it) {
        if (Settings.hideVip() && (it.vip || it.exclusive)) return false;
        if (Settings.isHidden(it.id)) return false;
        if (hasBlockedTag(it)) return false;
        if (searchActive && "tag".equals(searchMode) && !matchTagExpr(it)) return false;
        return true;
    }

    /**
     * 分页：客户端每页固定 PAGE_SIZE 条（过滤后）；
     * 服务端页数据被过滤掉的部分会自动继续往后取，保证每页尽量满 30 条。
     */
    private void loadPage(final int targetPage) {
        if (loading) {
            loadSeq++;
            loading = false;
        }
        loading = true;
        final int seq = ++loadSeq;
        final int target = Math.max(0, targetPage);
        if (target == 0) totalCnt = 0;
        status.setText("加载中…");
        final String query = buildQuery();
        // 顺序翻到下一页时增量取；回退/跳页则从头重建
        final boolean incremental = (target == curPage + 1 && !pageStarts.isEmpty());
        final int startCursor = incremental ? serverCursor : 0;
        final int startIdx = incremental ? target : 0;
        final java.util.List<Integer> startsBefore = new java.util.ArrayList<Integer>(pageStarts);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<VideoItem> result = new ArrayList<VideoItem>();
                final java.util.List<Integer> starts = new ArrayList<Integer>();
                int cursor = startCursor;
                int pageIdx = startIdx;
                boolean more = true;
                int serverTotal = 0;
                try {
                    if (!incremental) {
                        starts.clear();
                        starts.add(cursor);
                    } else {
                        starts.addAll(startsBefore);
                        starts.add(cursor);
                    }
                    int guard = 0;
                    // 先跳过前面的页（仅重建时）
                    while (pageIdx < target && more && guard < 40) {
                        guard++;
                        VideoPage vp = Api.videos(query, cursor, PAGE_SIZE);
                        cursor++;
                        pageIdx++;
                        starts.add(cursor);
                        if (vp.totalCnt > 0) serverTotal = vp.totalCnt;
                        more = vp.hasMore && vp.items.size() > 0;
                    }
                    // 填充当前页直到满 PAGE_SIZE 条
                    guard = 0;
                    while (result.size() < PAGE_SIZE && more && guard < 40) {
                        guard++;
                        VideoPage vp = Api.videos(query, cursor, PAGE_SIZE);
                        cursor++;
                        if (vp.totalCnt > 0) serverTotal = vp.totalCnt;
                        for (VideoItem it : vp.items) {
                            if (result.size() >= PAGE_SIZE) break;
                            if (passVideo(it) && !containsItem(result, it.id)) result.add(it);
                        }
                        more = vp.hasMore && vp.items.size() > 0;
                    }
                    final boolean fMore = more;
                    final int fCursor = cursor;
                    final java.util.List<Integer> fStarts = starts;
                    final int fTotal = serverTotal;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != loadSeq) return;
                            loading = false;
                            items.clear();
                            items.addAll(result);
                            adapter.notifyDataSetChanged();
                            loaded = items.size();
                            hasMore = fMore;
                            curPage = target;
                            if (fTotal > 0) totalCnt = fTotal;
                            serverCursor = fCursor;
                            pageStarts.clear();
                            pageStarts.addAll(fStarts);
                            updatePager();
                            status.setText(statusText());
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != loadSeq) return;
                            loading = false;
                            status.setText("加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private boolean containsItem(java.util.List<VideoItem> list, String id) {
        for (VideoItem it : list) {
            if (it.id.equals(id)) return true;
        }
        return false;
    }

    private void jumpToPage() {
        int totalPages = totalCnt > 0 ? (totalCnt + PAGE_SIZE - 1) / PAGE_SIZE : 0;
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint(totalPages > 0 ? ("1 - " + totalPages) : "页码");
        et.setText(String.valueOf(curPage + 1));
        et.setSelection(et.getText().length());
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF888888);
        new android.app.AlertDialog.Builder(this)
                .setTitle("跳转到第几页" + (totalPages > 0 ? "（约 " + totalPages + " 页）" : ""))
                .setView(et)
                .setPositiveButton("跳转", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            int n = Integer.parseInt(et.getText().toString().trim());
                            if (n >= 1) loadPage(n - 1);
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updatePager() {
        if (pageText == null) return;
        int totalPages = totalCnt > 0 ? (totalCnt + PAGE_SIZE - 1) / PAGE_SIZE : 0;
        pageText.setText(totalPages > 0
                ? ("第 " + (curPage + 1) + " / 约 " + totalPages + " 页")
                : ("第 " + (curPage + 1) + " 页"));
        if (btnPrevPage != null) {
            btnPrevPage.setEnabled(curPage > 0);
            btnPrevPage.setAlpha(curPage > 0 ? 1f : 0.4f);
        }
        if (btnNextPage != null) {
            btnNextPage.setEnabled(hasMore);
            btnNextPage.setAlpha(hasMore ? 1f : 0.4f);
        }
    }

    private boolean hasBlockedTag(VideoItem it) {
        java.util.Set<String> blocked = Settings.blockedTags();
        if (blocked.isEmpty()) return false;
        for (String t : it.tags) {
            if (blocked.contains(t)) return true;
        }
        return false;
    }

    private boolean containsId(String id) {
        for (VideoItem it : items) {
            if (it.id.equals(id)) return true;
        }
        return false;
    }

    private String statusText() {
        if (items.isEmpty()) return "没有结果";
        StringBuilder sb = new StringBuilder();
        if (curKw != null) sb.append("搜索「").append(curKw).append("」 · ");
        if (curCt != null) {
            if (curSubCt != null) {
                for (Category c : subs) {
                    if (c.id.equals(curSubCt)) sb.append(c.name).append(" · ");
                }
            }
            if (!subs.isEmpty() && curSubCt == null) sb.append("全部 · ");
        }
        sb.append(loaded).append(" 条");
        if (totalCnt > 0) sb.append(" / 服务端共 ").append(totalCnt);
        sb.append(hasMore ? "  ·  还有下一页" : "  ·  已到底");
        return sb.toString();
    }

    private void showMenu() {
        final String[] items = {
                "下载任务",
                "收藏夹",
                "观看记录",
                "稍后再看",
                "相册（COS图）",
                Settings.hideVip() ? "VIP 内容：已屏蔽（点击显示）" : "VIP 内容：显示中（点击屏蔽）",
                "主题设置",
                "标签屏蔽",
                "清除手动屏蔽",
                "检查更新"
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            startActivity(new Intent(MainActivity.this, DownloadsActivity.class));
                        } else if (which == 1) {
                            startActivity(new Intent(MainActivity.this, CollectionActivity.class));
                        } else if (which == 2) {
                            startActivity(new Intent(MainActivity.this, HistoryActivity.class));
                        } else if (which == 3) {
                            startActivity(new Intent(MainActivity.this, WatchLaterActivity.class));
                        } else if (which == 4) {
                            startActivity(new Intent(MainActivity.this, AlbumActivity.class));
                        } else if (which == 5) {
                            Settings.setHideVip(!Settings.hideVip());
                            Toast.makeText(MainActivity.this,
                                    Settings.hideVip() ? "已屏蔽 VIP 内容" : "已显示 VIP 内容",
                                    Toast.LENGTH_SHORT).show();
                            loadPage(0);
                        } else if (which == 6) {
                            startActivity(new Intent(MainActivity.this, ThemeSettingsActivity.class));
                        } else if (which == 7) {
                            startActivity(new Intent(MainActivity.this, TagBlockActivity.class));
                        } else if (which == 8) {
                            Settings.clearHidden();
                            Toast.makeText(MainActivity.this, "手动屏蔽已清空", Toast.LENGTH_SHORT).show();
                            loadPage(0);
                        } else {
                            UpdateChecker.manualCheck(MainActivity.this);
                        }
                    }
                })
                .show();
    }

    private void fillTagBar(final Dialog d, java.util.List<String> tags) {
        final LinearLayout tagBar = (LinearLayout) d.findViewById(R.id.dlgTags);
        android.view.View tagScroll = d.findViewById(R.id.dlgTagsScroll);
        if (tagBar == null || tagScroll == null) return;
        if (tags == null || tags.isEmpty()) {
            tagScroll.setVisibility(android.view.View.GONE);
            return;
        }
        tagScroll.setVisibility(android.view.View.VISIBLE);
        tagBar.removeAllViews();
        for (final String t : tags) {
            TextView chip = new TextView(this);
            chip.setText(t);
            chip.setTextSize(12);
            chip.setTextColor(Settings.accentColor());
            chip.setBackground(ThemeUtil.chipBg(this, false));
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), 0, dp(4), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    searchByTag(t);
                }
            });
            tagBar.addView(chip);
        }
    }


    // ------------------------------------------------------------ 设置

    private void showSettings() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_settings);
        final EditText et = (EditText) d.findViewById(R.id.setDir);
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
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("dir", p));
                Toast.makeText(MainActivity.this, "路径已复制", Toast.LENGTH_SHORT).show();
            }
        });
        d.findViewById(R.id.setSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = et.getText().toString().trim();
                if (p.length() == 0) p = Settings.DEFAULT_DIR;
                File f = new File(p);
                if (!f.exists() && !f.mkdirs()) {
                    Toast.makeText(MainActivity.this, "目录无法创建，检查路径或权限", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!f.isDirectory() || !f.canWrite()) {
                    Toast.makeText(MainActivity.this, "目录不可写", Toast.LENGTH_LONG).show();
                    return;
                }
                Settings.setDir(p);
                d.dismiss();
                Toast.makeText(MainActivity.this, "下载目录已设为:\n" + p, Toast.LENGTH_LONG).show();
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

    // ------------------------------------------------------------ 详情与下载

    private void showVideo(final VideoItem item) {
        VideoDetail.show(this, item, new VideoDetail.TagClicker() {
            @Override
            public void onTag(String tag) {
                searchByTag(tag);
            }
        });
    }

}
