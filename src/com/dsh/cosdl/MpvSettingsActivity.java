package com.dsh.cosdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** mpv 原生设置页面：常规 / 视频 / 用户界面 / 手势 / 高级。 */
public class MpvSettingsActivity extends Activity {

    private ListView list;
    private TextView title, hint;
    private static final String EDIT_INPUT = "__edit_input__";
    private String curCat = null;
    private final List<Object> rows = new ArrayList<Object>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        setContentView(R.layout.activity_mpv_settings);
        list = (ListView) findViewById(R.id.list);
        title = (TextView) findViewById(R.id.title);
        hint = (TextView) findViewById(R.id.hint);
        adapter = new Adapter();
        list.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object o = rows.get(position);
                if (o instanceof String) {
                    if (EDIT_INPUT.equals(o)) editInputConf();
                    else showCategory((String) o);
                } else if (o instanceof MpvOptions.Opt) {
                    choose((MpvOptions.Opt) o);
                }
            }
        });
        showCategories();
    }

    private void showCategories() {
        curCat = null;
        title.setText("mpv 设置");
        hint.setText("这些是 mpv 原生选项，修改后对之后打开的播放生效");
        rows.clear();
        for (String c : MpvOptions.CATEGORIES) rows.add(c);
        adapter.notifyDataSetChanged();
    }

    private void showCategory(String cat) {
        curCat = cat;
        title.setText(cat);
        hint.setText("");
        rows.clear();
        rows.addAll(MpvOptions.ofCategory(cat));
        if (MpvOptions.CAT_GESTURE.equals(cat)) rows.add(EDIT_INPUT);
        adapter.notifyDataSetChanged();
    }

    /** 编辑 mpv 的 input.conf（可自定义 0x10001/0x10002/0x10003 等触摸键绑定）。 */
    private void editInputConf() {
        final java.io.File dir = new java.io.File(getFilesDir(), "mpv");
        if (!dir.exists()) dir.mkdirs();
        final java.io.File f = new java.io.File(dir, "input.conf");
        String content = "";
        try {
            if (f.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                content = sb.toString();
            }
        } catch (Exception ignored) {
        }
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(content);
        et.setHint("# 例如：\n# 0x10003 seek 6\n# 0x10001 seek -6");
        et.setHintTextColor(0xFF888888);
        et.setTextColor(0xFFFFFFFF);
        et.setTextSize(12);
        et.setMinLines(8);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        new AlertDialog.Builder(this)
                .setTitle("input.conf（手势按键绑定）")
                .setView(et)
                .setMessage("保存后下次播放生效；0x10001=左 0x10002=中 0x10003=右")
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            java.io.FileWriter w = new java.io.FileWriter(f, false);
                            w.write(et.getText().toString());
                            w.close();
                            Toast.makeText(MpvSettingsActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(MpvSettingsActivity.this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void choose(final MpvOptions.Opt o) {
        String cur = MpvOptions.value(this, o);
        int curIdx = MpvOptions.indexOf(o, cur);
        new AlertDialog.Builder(this)
                .setTitle(o.title)
                .setSingleChoiceItems(o.labels, curIdx, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        MpvOptions.set(MpvSettingsActivity.this, o, o.values[which]);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(MpvSettingsActivity.this,
                                o.title + " → " + o.labels[which] + "（下次播放生效）", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (curCat != null) {
            showCategories();
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
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_option, parent, false);
            }
            Object o = rows.get(position);
            TextView t = (TextView) convertView.findViewById(R.id.oTitle);
            TextView v = (TextView) convertView.findViewById(R.id.oValue);
            if (o instanceof String) {
                if (EDIT_INPUT.equals(o)) {
                    t.setText("编辑 input.conf（自定义按键绑定）");
                    v.setText("打开");
                } else {
                    t.setText((String) o);
                    v.setText("");
                }
            } else {
                MpvOptions.Opt opt = (MpvOptions.Opt) o;
                t.setText(opt.title);
                v.setText(MpvOptions.label(MpvSettingsActivity.this, opt));
            }
            return convertView;
        }
    }
}
