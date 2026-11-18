package com.dsh.cosdl;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import is.xyz.mpv.MPVLib;

/** 内置 mpv 内核（libmpv，支持更多格式与解码器；通过 JNI 库渲染到 Surface）。 */
public class MpvPlayer implements PlayerCore {

    /** 私有目录（App 内部），日志与崩溃标记都不再写到用户可见的 Download 目录。 */
    static String privateDir = null;

    static void setPrivateDir(android.content.Context c) {
        try {
            privateDir = c.getFilesDir().getAbsolutePath();
        } catch (Exception ignored) {
        }
    }

    static java.io.File tryFlagFile(android.content.Context c) {
        return new java.io.File(c.getFilesDir(), ".mpv_try");
    }

    static void log(String s) {
        if (privateDir == null) return;
        try {
            java.io.File f = new java.io.File(privateDir, "mpv.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }

    private MPVLib mpv;
    private final Object mpvLock = new Object();
    private Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean released = false;
    private float speed = 1.0f;
    private long durationMs = 0;
    private long positionMs = 0;
    private boolean paused = false;
    private String url;
    private String vo = "gpu";

    private final MPVLib.EventObserver observer = new MPVLib.EventObserver() {
        @Override
        public void event(int eventId) {
            if (eventId == MPVLib.EVENT_END_FILE) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null && !released) cb.onCompletion();
                    }
                });
            }
        }

        @Override
        public void eventProperty(String name) {
        }

        @Override
        public void eventProperty(String name, boolean value) {
            if ("pause".equals(name)) {
                paused = value;
            }
        }

        @Override
        public void eventProperty(String name, long value) {
            if ("width".equals(name) || "height".equals(name)) {
                // 由 video-params 回调为准，这里忽略
            }
        }

        @Override
        public void eventProperty(String name, double value) {
            if ("time-pos".equals(name)) {
                positionMs = (long) (value * 1000);
                final long p = positionMs;
                final long d = durationMs;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null && !released) cb.onProgress(p, d);
                    }
                });
            } else if ("duration".equals(name)) {
                durationMs = (long) (value * 1000);
                final long d = durationMs;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null && !released) cb.onPrepared(d);
                    }
                });
            }
        }

        @Override
        public void eventProperty(String name, String value) {
        }
    };

    private final MPVLib.LogObserver logObserver = new MPVLib.LogObserver() {
        @Override
        public void logMessage(String prefix, int level, String text) {
            log("mpv[" + prefix + "][" + level + "] " + text);
            if (level <= 1 && text != null
                    && (text.contains("error") || text.contains("Error"))) {
                final String msg = text;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null && !released) cb.onError(msg);
                    }
                });
            }
        }
    };

    /** 轮询 mpv 时间属性，保证进度条真实同步（不依赖事件回调）。 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            try {
                if (mpv != null) {
                    Double pos;
                    Double dur;
                    synchronized (mpvLock) {
                        pos = mpv.getPropertyDouble("time-pos");
                        dur = mpv.getPropertyDouble("duration");
                    }
                    if (pos != null) positionMs = (long) (pos * 1000);
                    if (dur != null && dur > 0) durationMs = (long) (dur * 1000);
                    if (cb != null && !released) cb.onProgress(positionMs, durationMs);
                }
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 400);
        }
    };

    /** 横竖屏切换时通知 mpv 新的 surface 尺寸。 */
    public void onSurfaceSize(int w, int h) {
        try {
            if (mpv != null && w > 0 && h > 0) {
                mpv.setPropertyString("android-surface-size", w + "x" + h);
            }
        } catch (Exception ignored) {
        }
    }

    private final Runnable resizer = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            try {
                Integer w = mpv == null ? null : mpv.getPropertyInt("width");
                Integer h = mpv == null ? null : mpv.getPropertyInt("height");
                if (w != null && h != null && w > 0 && h > 0) {
                    final int fw = w, fh = h;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (cb != null && !released) cb.onVideoSize(fw, fh);
                        }
                    });
                }
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void init(Activity activity, Surface surface, String url, Callback callback) {
        this.cb = callback;
        this.url = url;
        released = false;
        setPrivateDir(activity);
        log("=== init start url=" + url);
        try {
            java.io.File flag = tryFlagFile(activity);
            if (flag.getParentFile() != null) flag.getParentFile().mkdirs();
            flag.createNewFile();
        } catch (Exception ignored) {
        }
        mpv = MPVLib.get();
        MPVLib.addObserver(observer);
        MPVLib.addLogObserver(logObserver);
        try {
            log("create()");
            mpv.create(activity.getApplicationContext());
            log("create ok");
            // 官方默认选项（见 MPVView.kt initOptions）
            mpv.setOptionString("config", "yes");
            java.io.File cfgDir = new java.io.File(activity.getFilesDir(), "mpv");
            if (!cfgDir.exists()) cfgDir.mkdirs();
            java.io.File cacheDir = new java.io.File(activity.getCacheDir(), "mpv");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            mpv.setOptionString("config-dir", cfgDir.getAbsolutePath());
            mpv.setOptionString("gpu-shader-cache-dir", cacheDir.getAbsolutePath());
            mpv.setOptionString("icc-cache-dir", cacheDir.getAbsolutePath());
            mpv.setOptionString("profile", "fast");
            // 基础选项
            mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
            mpv.setOptionString("cache", "yes");
            mpv.setOptionString("user-agent", Secret.UA);
            mpv.setOptionString("keep-open", "no");
            // 用户在「mpv 设置」里改的原生选项（含 vo/hwdec/ao/scale 等）
            MpvOptions.apply(activity, mpv);
            log("options applied");
            try {
                android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                mpv.setOptionString("display-fps-override", String.valueOf(dm.densityDpi > 0 ? 60 : 60));
            } catch (Exception ignored) {
            }
            log("init()");
            mpv.init();
            log("init ok");
            mpv.setOptionString("force-window", "no");
            mpv.setOptionString("idle", "once");
            boolean valid = surface != null && surface.isValid();
            log("attachSurface valid=" + valid);
            if (valid) mpv.attachSurface(surface);
            mpv.observeProperty("time-pos", MPVLib.FORMAT_DOUBLE);
            mpv.observeProperty("duration", MPVLib.FORMAT_DOUBLE);
            mpv.observeProperty("pause", MPVLib.FORMAT_FLAG);
            if (speed != 1.0f) mpv.setPropertyDouble("speed", speed);
            log("loadfile");
            mpv.command(new String[]{"loadfile", url});
            try {
                tryFlagFile(activity).delete();
            } catch (Exception ignored) {
            }
            log("=== init done");
        } catch (Throwable e) {
            log("EXCEPTION " + e);
            if (cb != null) cb.onError("mpv 初始化失败：" + e.getMessage());
        }
        ui.postDelayed(resizer, 800);
        ui.postDelayed(ticker, 500);
    }

    /** 给 mpv 发送按键事件（配合 input.conf 自定义手势绑定）。 */
    public void keyPress(String key) {
        try {
            if (mpv != null) mpv.command(new String[]{"keypress", key});
        } catch (Exception ignored) {
        }
    }

    public void screenshot() {
        try {
            if (mpv != null) {
                String path = "/sdcard/Pictures/cosViewer_" + System.currentTimeMillis() + ".jpg";
                mpv.command(new String[]{"screenshot-to-file", path, "video"});
            }
        } catch (Exception ignored) {
        }
    }

    public void cycleAudio() {
        try {
            if (mpv != null) mpv.command(new String[]{"cycle", "audio"});
        } catch (Exception ignored) {
        }
    }

    public void toggleSubs() {
        try {
            if (mpv != null) {
                Boolean vis = mpv.getPropertyBoolean("sub-visibility");
                mpv.setPropertyBoolean("sub-visibility", vis == null || !vis);
            }
        } catch (Exception ignored) {
        }
    }

    /** 只换片源，不销毁 mpv 实例（切换 CDN 节点时用，避免反复 create/destroy）。 */
    public void loadFile(String url) {
        try {
            if (mpv != null) mpv.command(new String[]{"loadfile", url});
        } catch (Exception ignored) {
        }
    }

    /** 通用属性设置（音量/亮度/对比度/饱和度/延迟等）。 */
    public void setPropDouble(String name, double value) {
        try {
            if (mpv != null) mpv.setPropertyDouble(name, value);
        } catch (Exception ignored) {
        }
    }

    public void setPropString(String name, String value) {
        try {
            if (mpv != null) mpv.setPropertyString(name, value);
        } catch (Exception ignored) {
        }
    }

    public Double getPropDouble(String name) {
        try {
            return mpv == null ? null : mpv.getPropertyDouble(name);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void play() {
        synchronized (mpvLock) {
            try {
                if (mpv != null) mpv.setPropertyBoolean("pause", false);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void pause() {
        synchronized (mpvLock) {
            try {
                if (mpv != null) mpv.setPropertyBoolean("pause", true);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void seekTo(long positionMs) {
        synchronized (mpvLock) {
            try {
                if (mpv != null) mpv.setPropertyDouble("time-pos", positionMs / 1000.0);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void setSpeed(float s) {
        speed = s;
        synchronized (mpvLock) {
            try {
                if (mpv != null) mpv.setPropertyDouble("speed", s);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void setScale(String mode) {
        try {
            if ("stretch".equals(mode)) {
                mpv.setPropertyBoolean("keepaspect", false);
                mpv.setPropertyDouble("panscan", 0);
            } else if ("crop".equals(mode)) {
                mpv.setPropertyBoolean("keepaspect", true);
                mpv.setPropertyDouble("panscan", 1.0);
            } else {
                mpv.setPropertyBoolean("keepaspect", true);
                mpv.setPropertyDouble("panscan", 0);
            }
        } catch (Exception ignored) {
        }
    }

    /** surface 消失：按官方做法先停 VO 再 detach，避免原生层用已失效的 Surface。 */
    public void onSurfaceDestroyedSafe() {
        try {
            if (mpv != null) {
                mpv.setPropertyString("vo", "null");
                mpv.setPropertyString("force-window", "no");
                mpv.detachSurface();
            }
        } catch (Exception ignored) {
        }
    }

    /** surface 回来后重新挂上。 */
    public void onSurfaceCreatedSafe(Surface s) {
        try {
            if (mpv != null && s != null && s.isValid()) {
                mpv.attachSurface(s);
                mpv.setPropertyString("vo", Settings.mpvVo());
                mpv.setPropertyString("force-window", "yes");
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void release() {
        if (released) return;
        released = true;
        ui.removeCallbacks(resizer);
        ui.removeCallbacks(ticker);
        try {
            if (mpv != null) {
                // 官方顺序：vo=null → force-window=no → detach → stop → destroy
                mpv.setPropertyString("vo", "null");
                mpv.setPropertyString("force-window", "no");
                mpv.detachSurface();
                mpv.command(new String[]{"stop"});
                MPVLib.removeObserver(observer);
                MPVLib.removeLogObserver(logObserver);
                mpv.destroy();
            }
        } catch (Exception ignored) {
        }
        mpv = null;
    }

    @Override
    public boolean isPlaying() {
        return !paused;
    }

    @Override
    public long position() {
        return positionMs;
    }

    @Override
    public long duration() {
        return durationMs;
    }
}
