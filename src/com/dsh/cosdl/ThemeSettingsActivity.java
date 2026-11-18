package com.dsh.cosdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 主题设置：按键颜色 / 底图 / 底图透明度。 */
public class ThemeSettingsActivity extends Activity {

    private static final int REQ_PICK_BG = 2001;

    private static final String[] COLOR_NAMES = {
            "琥珀黄", "orange", "樱花粉", "玫红", "紫罗兰", "天蓝", "青碧", "薄荷绿"
    };
    private static final int[] COLOR_VALUES = {
            0xFFFFD166, 0xFFFF9B42, 0xFFFF8FB1, 0xFFFF5C8A,
            0xFFB388FF, 0xFF64B5F6, 0xFF4DD0E1, 0xFF7ED957
    };

    private ListView list;
    private final List<String> rows = new ArrayList<String>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        setContentView(R.layout.activity_theme);
        list = (ListView) findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) chooseColor();
                else if (position == 1) customColor();
                else if (position == 2) pickImage();
                else if (position == 3) chooseAlpha();
                else resetAll();
            }
        });
        refresh();
    }

    private void refresh() {
        rows.clear();
        rows.add("按键颜色");
        rows.add("自定义颜色（RGB）");
        rows.add("底图");
        rows.add("底图透明度");
        rows.add("恢复默认主题");
        adapter.notifyDataSetChanged();
        findViewById(R.id.mainRoot).setBackgroundColor(0xFF0E0E0E);
    }

    private void chooseColor() {
        new AlertDialog.Builder(this)
                .setTitle("按键颜色")
                .setSingleChoiceItems(COLOR_NAMES, -1, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Settings.setAccentColor(COLOR_VALUES[which]);
                        dialog.dismiss();
                        Toast.makeText(ThemeSettingsActivity.this, "已应用", Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 自定义颜色：输入 #RRGGBB 或 R,G,B。 */
    private void customColor() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(String.format("#%06X", 0xFFFFFF & Settings.accentColor()));
        et.setHint("#RRGGBB 或 R,G,B");
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF888888);
        new AlertDialog.Builder(this)
                .setTitle("自定义颜色")
                .setMessage("支持 #RRGGBB 或 R,G,B（0-255）")
                .setView(et)
                .setPositiveButton("应用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Settings.setAccentColor(ThemeUtil.parseColor(et.getText().toString()));
                            Toast.makeText(ThemeSettingsActivity.this, "已应用", Toast.LENGTH_SHORT).show();
                            refresh();
                        } catch (Exception e) {
                            Toast.makeText(ThemeSettingsActivity.this, "格式不对，示例：255,100,50", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseAlpha() {
        final String[] labels = {"0%（不显示）", "20%", "40%", "60%", "80%", "100%"};
        final int[] vals = {0, 20, 40, 60, 80, 100};
        int cur = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == Settings.bgAlpha()) cur = i;
        new AlertDialog.Builder(this)
                .setTitle("底图透明度")
                .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Settings.setBgAlpha(vals[which]);
                        dialog.dismiss();
                        Toast.makeText(ThemeSettingsActivity.this, "已应用", Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, "选择底图"), REQ_PICK_BG);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_BG && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                File dst = new File(getFilesDir(), "theme_bg.img");
                FileOutputStream out = new FileOutputStream(dst);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                Settings.setBgImage(dst.getAbsolutePath());
                if (Settings.bgAlpha() == 0) Settings.setBgAlpha(40);
                Toast.makeText(this, "底图已设置", Toast.LENGTH_SHORT).show();
                refresh();
            } catch (Exception e) {
                Toast.makeText(this, "设置失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void resetAll() {
        Settings.setAccentColor(0xFFFFD166);
        Settings.setBgImage("");
        Settings.setBgAlpha(40);
        Toast.makeText(this, "已恢复默认主题", Toast.LENGTH_SHORT).show();
        refresh();
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
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_option, parent, false);
            TextView t = (TextView) v.findViewById(R.id.oTitle);
            TextView val = (TextView) v.findViewById(R.id.oValue);
            t.setText(rows.get(position));
            if (position == 0) {
                val.setText("■");
                val.setTextColor(Settings.accentColor());
            } else if (position == 1) {
                val.setText(String.format("#%06X", 0xFFFFFF & Settings.accentColor()));
                val.setTextColor(Settings.accentColor());
            } else if (position == 2) {
                val.setText(Settings.bgImage().length() > 0 ? "已设置" : "未设置");
                val.setTextColor(Settings.accentColor());
            } else if (position == 3) {
                val.setText(Settings.bgAlpha() + "%");
                val.setTextColor(Settings.accentColor());
            } else {
                val.setText("");
            }
            return v;
        }
    }
}
