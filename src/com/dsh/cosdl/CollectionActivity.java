package com.dsh.cosdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 收藏夹：多级文件夹、自由命名、多选移动/复制（只存索引）。 */
public class CollectionActivity extends Activity {

    private ListView list;
    private TextView empty, pathText;
    private LinearLayout selectBar;
    private final List<Object> rows = new ArrayList<Object>();
    private Adapter adapter;

    private static final int REQ_IMPORT = 3001;
    private String curFolderId = "";
    private boolean selecting = false;
    private String searchKey = null;
    private final Set<String> selected = new HashSet<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        setContentView(R.layout.activity_collection);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        pathText = (TextView) findViewById(R.id.pathText);
        selectBar = (LinearLayout) findViewById(R.id.selectBar);
        adapter = new Adapter();
        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        findViewById(R.id.btnNewFolder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newFolder();
            }
        });
        findViewById(R.id.btnMore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreMenu();
            }
        });
        findViewById(R.id.btnSelect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSelectMode();
            }
        });
        findViewById(R.id.btnCancelSel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSelectMode();
            }
        });
        findViewById(R.id.btnMove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                transfer(true);
            }
        });
        findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                transfer(false);
            }
        });
        findViewById(R.id.btnRemove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeSelected();
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = rows.get(position);
                if (o instanceof CollectionStore.Folder) {
                    load(((CollectionStore.Folder) o).id);
                } else if (o instanceof CollectionStore.Entry) {
                    CollectionStore.Entry e = (CollectionStore.Entry) o;
                    if (selecting) {
                        if (selected.contains(e.videoId)) selected.remove(e.videoId);
                        else selected.add(e.videoId);
                        adapter.notifyDataSetChanged();
                        updateSelectBar();
                    } else {
                        openVideo(e);
                    }
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = rows.get(position);
                if (o instanceof CollectionStore.Folder) {
                    folderMenu((CollectionStore.Folder) o);
                    return true;
                }
                if (o instanceof CollectionStore.Entry) {
                    CollectionStore.Entry e = (CollectionStore.Entry) o;
                    if (!selecting) {
                        selecting = true;
                        selected.clear();
                        selected.add(e.videoId);
                        updateSelectBar();
                        adapter.notifyDataSetChanged();
                    }
                    return true;
                }
                return false;
            }
        });
        load("");
    }

    private void load(String folderId) {
        curFolderId = folderId == null ? "" : folderId;
        selecting = false;
        selected.clear();
        rows.clear();
        rows.addAll(CollectionStore.children(this, curFolderId));
        java.util.List<CollectionStore.Entry> es = CollectionStore.entriesIn(this, curFolderId);
        for (CollectionStore.Entry e : es) {
            if (searchKey != null && searchKey.length() > 0
                    && (e.title == null || !e.title.toLowerCase().contains(searchKey.toLowerCase()))) {
                continue;
            }
            rows.add(e);
        }
        adapter.notifyDataSetChanged();
        String name = "收藏夹";
        if (curFolderId.length() > 0) {
            for (CollectionStore.Folder f : CollectionStore.folders(this)) {
                if (f.id.equals(curFolderId)) name = f.name;
            }
        }
        pathText.setText(name);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        updateSelectBar();
    }

    private void showMoreMenu() {
        new AlertDialog.Builder(this)
                .setTitle("收藏夹")
                .setItems(new String[]{"搜索当前收藏夹", "清除搜索", "导入收藏（JSON）", "导出当前收藏夹", "导出全部收藏"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) search();
                                else if (which == 1) {
                                    searchKey = null;
                                    load(curFolderId);
                                } else if (which == 2) pickImport();
                                else if (which == 3) exportFolder(false);
                                else exportFolder(true);
                            }
                        })
                .show();
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(i, "选择收藏 JSON"), REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "没有文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importJson(data.getData());
        }
    }

    private void importJson(android.net.Uri uri) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            org.json.JSONObject root = new org.json.JSONObject(bos.toString("UTF-8"));
            // 先建文件夹（按名称匹配现有，根层）
            java.util.Map<String, String> idMap = new java.util.HashMap<String, String>();
            org.json.JSONArray fa = root.optJSONArray("folders");
            if (fa != null) {
                for (int i = 0; i < fa.length(); i++) {
                    org.json.JSONObject o = fa.optJSONObject(i);
                    if (o == null) continue;
                    String oldId = o.optString("id", "");
                    String name = o.optString("name", "导入");
                    String parent = o.optString("parentId", "");
                    // 同名文件夹直接复用
                    String target = null;
                    for (CollectionStore.Folder f : CollectionStore.folders(this)) {
                        if (f.name.equals(name) && f.parentId.equals("")) {
                            target = f.id;
                            break;
                        }
                    }
                    if (target == null) target = CollectionStore.create(this, name, "").id;
                    idMap.put(oldId, target);
                    if (parent.length() > 0) idMap.put(oldId + "_parent", parent);
                }
            }
            int added = 0;
            org.json.JSONArray ea = root.optJSONArray("entries");
            if (ea != null) {
                for (int i = 0; i < ea.length(); i++) {
                    org.json.JSONObject o = ea.optJSONObject(i);
                    if (o == null) continue;
                    String folder = idMap.containsKey(o.optString("folderId", ""))
                            ? idMap.get(o.optString("folderId", "")) : "";
                    if (CollectionStore.add(this, o.optString("videoId", ""),
                            o.optString("title", ""), o.optString("photo", ""), folder)) {
                        added++;
                    }
                }
            }
            Toast.makeText(this, "导入完成，新增 " + added + " 个收藏", Toast.LENGTH_LONG).show();
            load(curFolderId);
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void search() {
        final EditText et = new EditText(this);
        et.setHint("输入标题关键词");
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF888888);
        if (searchKey != null) et.setText(searchKey);
        new AlertDialog.Builder(this)
                .setTitle("搜索")
                .setView(et)
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        searchKey = et.getText().toString().trim();
                        load(curFolderId);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导出收藏 JSON 到 /sdcard/Download/DSH-Go/。 */
    private void exportFolder(boolean all) {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            org.json.JSONArray fa = new org.json.JSONArray();
            for (CollectionStore.Folder f : CollectionStore.folders(this)) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("parentId", f.parentId);
                fa.put(o);
            }
            org.json.JSONArray ea = new org.json.JSONArray();
            for (CollectionStore.Entry e : CollectionStore.entries(this)) {
                if (!all && !e.folderId.equals(curFolderId)) continue;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("videoId", e.videoId);
                o.put("title", e.title);
                o.put("photo", e.photo);
                o.put("folderId", e.folderId);
                ea.put(o);
            }
            root.put("folders", fa);
            root.put("entries", ea);
            java.io.File dir = new java.io.File("/sdcard/Download/DSH-Go");
            if (!dir.exists()) dir.mkdirs();
            java.io.File out = new java.io.File(dir,
                    "collections-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                            .format(new java.util.Date()) + ".json");
            java.io.FileWriter w = new java.io.FileWriter(out, false);
            w.write(root.toString(2));
            w.close();
            Toast.makeText(this, "已导出:\n" + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateSelectBar() {
        selectBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.btnSelect)).setText(selecting ? "完成" : "多选");
    }

    private void toggleSelectMode() {
        selecting = !selecting;
        selected.clear();
        adapter.notifyDataSetChanged();
        updateSelectBar();
    }

    private void newFolder() {
        final EditText et = new EditText(this);
        et.setHint("收藏夹名称");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF888888);
        new AlertDialog.Builder(this)
                .setTitle("新建收藏夹")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        CollectionStore.create(CollectionActivity.this, name, curFolderId);
                        load(curFolderId);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void folderMenu(final CollectionStore.Folder f) {
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(new String[]{"重命名", "删除（含子文件夹）", "进入"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) rename(f);
                        else if (which == 1) delete(f);
                        else load(f.id);
                    }
                })
                .show();
    }

    private void rename(final CollectionStore.Folder f) {
        final EditText et = new EditText(this);
        et.setText(f.name);
        et.setSelection(f.name.length());
        et.setTextColor(0xFFFFFFFF);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        CollectionStore.rename(CollectionActivity.this, f.id, name);
                        load(curFolderId);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void delete(final CollectionStore.Folder f) {
        new AlertDialog.Builder(this)
                .setTitle("删除「" + f.name + "」？")
                .setMessage("子文件夹和其中的收藏索引都会删除（不会删视频文件）")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        CollectionStore.delete(CollectionActivity.this, f.id);
                        load(curFolderId);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openVideo(CollectionStore.Entry e) {
        VideoDetail.show(this, VideoDetail.simple(e.videoId, e.title, e.photo));
    }

    private List<CollectionStore.Entry> selectedEntries() {
        List<CollectionStore.Entry> out = new ArrayList<CollectionStore.Entry>();
        for (CollectionStore.Entry e : CollectionStore.entriesIn(this, curFolderId)) {
            if (selected.contains(e.videoId)) out.add(e);
        }
        return out;
    }

    private void transfer(final boolean move) {
        final List<CollectionStore.Entry> sel = selectedEntries();
        if (sel.isEmpty()) {
            Toast.makeText(this, "先选中视频", Toast.LENGTH_SHORT).show();
            return;
        }
        showTransferTarget(move, sel, curFolderId);
    }

    /** 目标文件夹选择：完整树 + 新建文件夹，实时读取（包含刚建的）。 */
    private void showTransferTarget(final boolean move, final List<CollectionStore.Entry> sel,
                                    final String fromFolder) {
        final List<String[]> tree = new ArrayList<String[]>();
        tree.add(new String[]{"", "（根目录）"});
        for (String[] t : CollectionStore.tree(this, null)) {
            if (!t[0].equals(fromFolder)) tree.add(t);
        }
        tree.add(new String[]{"__new__", "＋ 新建文件夹"});
        final String[] labels = new String[tree.size()];
        for (int i = 0; i < tree.size(); i++) labels[i] = tree.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle(move ? "移动到…" : "复制到…")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String target = tree.get(which)[0];
                        if ("__new__".equals(target)) {
                            newFolderForTransfer(move, sel, fromFolder);
                            return;
                        }
                        if (target.equals(fromFolder)) {
                            Toast.makeText(CollectionActivity.this, "目标就是当前收藏夹", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        CollectionStore.transfer(CollectionActivity.this, sel, fromFolder, target, move);
                        Toast.makeText(CollectionActivity.this,
                                move ? "已移动 " + sel.size() + " 个" : "已复制 " + sel.size() + " 个",
                                Toast.LENGTH_SHORT).show();
                        load(curFolderId);
                    }
                })
                .show();
    }

    /** 新建一个目标文件夹（建在根目录），建完继续移动。 */
    private void newFolderForTransfer(final boolean move, final List<CollectionStore.Entry> sel,
                                      final String fromFolder) {
        final EditText et = new EditText(this);
        et.setHint("新收藏夹名称");
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF888888);
        new AlertDialog.Builder(this)
                .setTitle("新建收藏夹")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        CollectionStore.Folder f = CollectionStore.create(CollectionActivity.this, name, "");
                        CollectionStore.transfer(CollectionActivity.this, sel, fromFolder, f.id, move);
                        Toast.makeText(CollectionActivity.this,
                                (move ? "已移动到「" : "已复制到「") + name + "」", Toast.LENGTH_SHORT).show();
                        load(curFolderId);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void removeSelected() {
        final List<CollectionStore.Entry> sel = selectedEntries();
        if (sel.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("从收藏夹移除 " + sel.size() + " 个？")
                .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        for (CollectionStore.Entry e : sel) {
                            CollectionStore.removeEntry(CollectionActivity.this, e.videoId, curFolderId);
                        }
                        load(curFolderId);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            toggleSelectMode();
            return;
        }
        if (curFolderId.length() > 0) {
            String parent = "";
            for (CollectionStore.Folder f : CollectionStore.folders(this)) {
                if (f.id.equals(curFolderId)) parent = f.parentId;
            }
            load(parent);
            return;
        }
        super.onBackPressed();
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
            return rows.get(position) instanceof CollectionStore.Folder ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = rows.get(position);
            if (o instanceof CollectionStore.Folder) {
                CollectionStore.Folder f = (CollectionStore.Folder) o;
                View v = convertView;
                if (v == null || v.findViewById(R.id.fName) == null) {
                    v = getLayoutInflater().inflate(R.layout.item_folder, parent, false);
                }
                ((TextView) v.findViewById(R.id.fName)).setText(f.name);
                int count = CollectionStore.children(CollectionActivity.this, f.id).size()
                        + CollectionStore.entriesIn(CollectionActivity.this, f.id).size();
                ((TextView) v.findViewById(R.id.fCount)).setText(count + " 项");
                return v;
            }
            CollectionStore.Entry e = (CollectionStore.Entry) o;
            View v = convertView;
            if (v == null || v.findViewById(R.id.cTitle) == null) {
                v = getLayoutInflater().inflate(R.layout.item_collection_video, parent, false);
            }
            ((TextView) v.findViewById(R.id.cTitle)).setText(e.title);
            TextView mark = (TextView) v.findViewById(R.id.cMark);
            boolean on = selected.contains(e.videoId);
            mark.setText(selecting ? (on ? "✓ 已选中" : "点击选择") : "");
            v.setBackgroundColor(on && selecting ? 0x22FFD166 : 0x00000000);
            ImageLoader.get().load(e.photo, (ImageView) v.findViewById(R.id.cCover), 200, 200);
            return v;
        }
    }
}
