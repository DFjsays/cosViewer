package com.dsh.cosdl;

import android.app.Activity;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/** 系统 MediaPlayer 内核（稳定兜底）。 */
public class SystemPlayer implements PlayerCore {

    private MediaPlayer mp;
    private Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean prepared = false;
    private boolean released = false;
    private float speed = 1.0f;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            if (prepared && mp != null && cb != null) {
                try {
                    cb.onProgress(mp.getCurrentPosition(), mp.getDuration());
                } catch (Exception ignored) {
                }
            }
            ui.postDelayed(this, 400);
        }
    };

    @Override
    public void init(Activity activity, Surface surface, String url, final Callback callback) {
        cb = callback;
        released = false;
        mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        try {
            if (surface != null) mp.setSurface(surface);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    try {
                        if (speed != 1.0f) {
                            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
                        }
                    } catch (Exception ignored) {
                    }
                    if (cb != null) cb.onPrepared(mp.getDuration());
                    mp.start();
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                    if (cb != null) cb.onVideoSize(width, height);
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (cb != null) cb.onCompletion();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (cb != null) cb.onError("系统播放器错误 what=" + what + " extra=" + extra);
                    return true;
                }
            });
            mp.setDataSource(url);
            mp.prepareAsync();
        } catch (Exception e) {
            if (cb != null) cb.onError(String.valueOf(e.getMessage()));
        }
        ui.post(ticker);
    }

    @Override
    public void play() {
        try {
            if (mp != null && prepared) mp.start();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void pause() {
        try {
            if (mp != null && prepared && mp.isPlaying()) mp.pause();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void seekTo(long positionMs) {
        try {
            if (mp != null && prepared) mp.seekTo((int) positionMs);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setSpeed(float s) {
        speed = s;
        try {
            if (mp != null && prepared) {
                mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(s));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setScale(String mode) {
        // 由 Activity 层调整 Surface 尺寸完成
    }

    @Override
    public void release() {
        released = true;
        ui.removeCallbacks(ticker);
        try {
            if (mp != null) {
                mp.stop();
                mp.release();
            }
        } catch (Exception ignored) {
        }
        mp = null;
        prepared = false;
    }

    @Override
    public boolean isPlaying() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long position() {
        try {
            return mp == null || !prepared ? 0 : mp.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long duration() {
        try {
            return mp == null || !prepared ? 0 : mp.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }
}
