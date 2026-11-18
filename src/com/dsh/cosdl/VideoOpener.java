package com.dsh.cosdl;

import android.app.Activity;
import android.content.Intent;

import java.io.File;

/** 统一使用内置播放器播放本地文件。 */
public final class VideoOpener {

    private VideoOpener() {
    }

    public static void open(Activity a, File f) {
        if (f == null || !f.exists()) {
            android.widget.Toast.makeText(a, "文件不存在", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(a, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_URL, f.getAbsolutePath());
        i.putExtra(PlayerActivity.EXTRA_TITLE, f.getName());
        a.startActivity(i);
    }
}
