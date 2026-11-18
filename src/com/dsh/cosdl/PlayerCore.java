package com.dsh.cosdl;

import android.app.Activity;
import android.view.Surface;

/** 播放器内核抽象：系统 MediaPlayer 与内置 mpv 两套实现。 */
public interface PlayerCore {

    interface Callback {
        void onPrepared(long durationMs);

        void onProgress(long positionMs, long durationMs);

        void onVideoSize(int width, int height);

        void onCompletion();

        void onError(String message);
    }

    void init(Activity activity, Surface surface, String url, Callback callback);

    void play();

    void pause();

    void seekTo(long positionMs);

    void setSpeed(float speed);

    /** fit / stretch / crop */
    void setScale(String mode);

    void release();

    boolean isPlaying();

    long position();

    long duration();
}
