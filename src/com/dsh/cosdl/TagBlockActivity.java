package com.dsh.cosdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 标签屏蔽管理：屏蔽的标签会自动从列表隐藏。 */
public class TagBlockActivity extends Activity {

    private ListView list;
    private TextView empty;
    private final List<String> rows = new ArrayList<String>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        setContentView(R.layout.activity_tag_block);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        adapter = new Adapter();
        list.setAdapter(adapter);
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addTag();
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                removeTag(rows.get(position));
            }
        });
        reload();
    }

    private void addTag() {
        final EditText et = new EditText(this);
        et.setHint("标签名（如：美乳、校服）");
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF888888);
        new AlertDialog.Builder(this)
                .setTitle("添加屏蔽标签")
                .setView(et)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String t = et.getText().toString().trim();
                        if (t.length() == 0) return;
                        Settings.addBlockedTag(t);
                        Toast.makeText(TagBlockActivity.this, "已屏蔽：" + t, Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void removeTag(final String tag) {
        new AlertDialog.Builder(this)
                .setTitle("移除屏蔽「" + tag + "」？")
                .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Settings.removeBlockedTag(tag);
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void reload() {
        rows.clear();
        rows.addAll(Settings.blockedTags());
        java.util.Collections.sort(rows);
        adapter.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
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
            ((TextView) v.findViewById(R.id.oTitle)).setText(rows.get(position));
            TextView val = (TextView) v.findViewById(R.id.oValue);
            val.setText("移除");
            val.setTextColor(0xFFFF8A80);
            return v;
        }
    }
}
