package com.dsh.cosdl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 内置播放器：系统内核 / mpv 内核，可在设置里切换。 */
public class PlayerActivity extends Activity implements PlayerCore.Callback {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PHOTO = "photo";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_QUEUE_POS = "queue_pos";

    private SurfaceView surfaceView;
    private FrameLayout root;
    private SeekBar seek;
    private TextView timePos, timeDur, statusText, loadingText, gestureHint;
    private Runnable hintHide;
    private boolean lockMode = false;
    private View lockFloat;
    private Runnable lockFloatHide;
    private View loadingBox, topBar, bottomBar;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private PlayerCore core;

    private String videoId, title, photo, directUrl;
    private final List<String> urls = new ArrayList<String>();
    private final List<String> mp4Fallback = new ArrayList<String>();
    private boolean usingHls = false;
    private boolean triedFallback = false;
    private final List<String> hosts = new ArrayList<String>();

    private long durationMs = 0;
    private long resumePos = 0;
    private boolean prepared = false;
    private boolean dragging = false;
    private boolean surfaceReady = false;
    private boolean startCalled = false;
    private int videoW = 0, videoH = 0;

    private WebView webView;
    private GestureDetector gesture;
    private int gestureMode = 0; // 1=seek 2=亮度 3=音量
    private float gDownX, gDownY, gStartVol = 50, gStartBri = 50;
    private long gSeekTarget = 0;
    private boolean controlsVisible = true;
    private float tempSpeedBackup = -1;
    private boolean webMode = false;
    private String engineOverride = null;
    private int queuePos = -1;
    private boolean webPlaying = false;
    private boolean webReady = false;
    private long webPosMs = 0;
    private String lastUrl = null;

    private final Runnable watchTicker = new Runnable() {
        @Override
        public void run() {
            if (core != null && prepared && videoId != null && videoId.length() > 0) {
                long p = core.position();
                if (p > 0) WatchStore.touch(PlayerActivity.this, videoId, title, photo, p / 1000);
            }
            ui.postDelayed(this, 10000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        Api.init(this);
        // 一次性迁移：把之前崩溃保护写进去的 web 改回默认 mpv
        android.content.SharedPreferences cfgs = getSharedPreferences("cfg", MODE_PRIVATE);
        if (!cfgs.getBoolean("engine_migrated_481", false)) {
            if ("web".equals(Settings.engine()) || "system".equals(Settings.engine())) {
                Settings.setEngine("mpv");
            }
            cfgs.edit().putBoolean("engine_migrated_481", true).apply();
        }
        java.io.File tryFlag = MpvPlayer.tryFlagFile(this);
        if (tryFlag.exists()) {
            tryFlag.delete();
            engineOverride = "web";
            android.widget.Toast.makeText(this, "上次 mpv 内核崩溃，本次先用 Web 播放",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterFullscreen();

        videoId = getIntent().getStringExtra(EXTRA_ID);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        photo = getIntent().getStringExtra(EXTRA_PHOTO);
        directUrl = getIntent().getStringExtra(EXTRA_URL);
        queuePos = getIntent().getIntExtra(EXTRA_QUEUE_POS, -1);
        if (title == null) title = "";

        root = (FrameLayout) findViewById(R.id.playerRoot);
        surfaceView = (SurfaceView) findViewById(R.id.surface);
        webView = (WebView) findViewById(R.id.web);
        setupWeb();
        seek = (SeekBar) findViewById(R.id.seek);
        timePos = (TextView) findViewById(R.id.timePos);
        timeDur = (TextView) findViewById(R.id.timeDur);
        statusText = (TextView) findViewById(R.id.statusText);
        loadingText = (TextView) findViewById(R.id.loadingText);
        gestureHint = (TextView) findViewById(R.id.gestureHint);
        lockFloat = findViewById(R.id.lockFloat);
        if (lockFloat != null) {
            lockFloat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    unlockScreen();
                }
            });
        }
        setupQuickBar();
        loadingBox = findViewById(R.id.loadingBox);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        ((TextView) findViewById(R.id.title)).setText(title);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnPlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        findViewById(R.id.btnBack10).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webMode) js("jSeek(Math.max(0," + (currentPosMs() - 10000) / 1000.0 + "))");
                else if (core != null) core.seekTo(Math.max(0, core.position() - 10000));
            }
        });
        findViewById(R.id.btnFwd10).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webMode) js("jSeek(Math.min(" + durationMs / 1000.0 + "," + (currentPosMs() + 10000) / 1000.0 + "))");
                else if (core != null) core.seekTo(Math.min(core.duration(), core.position() + 10000));
            }
        });
        findViewById(R.id.btnSet).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0) {
                    timePos.setText(fmt(durationMs * progress / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (durationMs <= 0) return;
                long target = durationMs * bar.getProgress() / 1000;
                if (webMode) js("jSeek(" + (target / 1000.0) + ")");
                else if (core != null) core.seekTo(target);
            }
        });

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (core instanceof MpvPlayer) {
                    ((MpvPlayer) core).onSurfaceCreatedSafe(holder.getSurface());
                    prepared = true;
                    loadingBox.setVisibility(View.GONE);
                } else if (readyToStart()) {
                    startCore();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (core instanceof MpvPlayer) {
                    ((MpvPlayer) core).onSurfaceSize(width, height);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                if (core instanceof MpvPlayer) {
                    ((MpvPlayer) core).onSurfaceDestroyedSafe();
                } else if (core != null) {
                    core.release();
                    core = null;
                }
                prepared = false;
            }
        });

        if (directUrl != null && directUrl.length() > 0) {
            urls.add(directUrl);
            if (videoId == null) videoId = "local:" + directUrl.hashCode();
            resumePos = Settings.resume() ? WatchStore.position(this, videoId) : 0;
            if (surfaceReady) startCore();
        } else {
            loadOnline();
        }
        ui.postDelayed(watchTicker, 10000);
        setupGestures();
    }

    // ------------------------------------------------------------ 手势

    private void setupGestures() {
        if ("0".equals(MpvOptions.value(this, "gesture", "1"))) return;
        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float w = root.getWidth();
                if (e.getX() < w * 0.35f) {
                    String act = MpvOptions.value(PlayerActivity.this, "dblclick_left", "rew10");
                    if ("rew10".equals(act)) skip(-10000);
                    else if ("custom".equals(act)) mpvKey("0x10001");
                } else if (e.getX() > w * 0.65f) {
                    String act = MpvOptions.value(PlayerActivity.this, "dblclick_right", "fwd10");
                    if ("fwd10".equals(act)) skip(10000);
                    else if ("custom".equals(act)) mpvKey("0x10003");
                } else {
                    String act = MpvOptions.value(PlayerActivity.this, "dblclick_center", "pause");
                    if ("pause".equals(act)) togglePlay();
                    else if ("custom".equals(act)) mpvKey("0x10002");
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                String lp = MpvOptions.value(PlayerActivity.this, "longpress_speed", "2.0");
                if ("0".equals(lp)) return;
                if (tempSpeedBackup < 0) {
                    tempSpeedBackup = Settings.speed();
                    setPlaybackSpeed(Float.parseFloat(lp));
                    showHint(lp + "x 加速中");
                }
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null) return false;
                if (gestureMode == 0) {
                    boolean left = e1.getX() < root.getWidth() / 2f;
                    if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 12) {
                        if ("none".equals(MpvOptions.value(PlayerActivity.this, "hscroll_action", "seek"))) return false;
                        gestureMode = 1;
                    } else if (Math.abs(dy) > 12) {
                        String key = left ? "vscroll_left" : "vscroll_right";
                        String def = left ? "brightness" : "volume";
                        String act = MpvOptions.value(PlayerActivity.this, key, def);
                        if ("none".equals(act)) return false;
                        gestureMode = "brightness".equals(act) ? 2 : 3;
                        gStartVol = currentVolume();
                        gStartBri = currentBrightness();
                    } else {
                        return false;
                    }
                    gDownX = e1.getX();
                    gDownY = e1.getY();
                    gSeekTarget = currentPosMs();
                }
                int w = Math.max(1, root.getWidth());
                int h = Math.max(1, root.getHeight());
                if (gestureMode == 1) {
                    float ratio = (e2.getX() - gDownX) / (w * 0.6f);
                    long base = currentPosMs();
                    gSeekTarget = base + (long) (ratio * 60000);
                    if (durationMs > 0 && gSeekTarget > durationMs) gSeekTarget = durationMs;
                    if (gSeekTarget < 0) gSeekTarget = 0;
                    if (durationMs > 0) seek.setProgress((int) (1000L * gSeekTarget / durationMs));
                    timePos.setText(fmt(gSeekTarget));
                    showHint(fmt(gSeekTarget) + " / " + fmt(durationMs));
                } else if (gestureMode == 2) {
                    setBrightness(gStartBri + (gDownY - e2.getY()) / (h * 0.6f) * 100f);
                } else if (gestureMode == 3) {
                    setVolume(gStartVol + (gDownY - e2.getY()) / (h * 0.6f) * 100f);
                }
                return true;
            }
        });
        View.OnTouchListener tl = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (lockMode) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) showLockFloat();
                    return true;
                }
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (gestureMode == 1) {
                        seekTo(gSeekTarget);
                    }
                    gestureMode = 0;
                    if (tempSpeedBackup >= 0) {
                        setPlaybackSpeed(tempSpeedBackup);
                        tempSpeedBackup = -1;
                    }
                }
                gesture.onTouchEvent(e);
                return true;
            }
        };
        surfaceView.setOnTouchListener(tl);
        if (webView != null) webView.setOnTouchListener(tl);
    }

    private void setupQuickBar() {
        LinearLayout bar = (LinearLayout) findViewById(R.id.quickBar);
        if (bar == null) return;
        addQuick(bar, R.drawable.ic_lock, new Runnable() {
            @Override
            public void run() {
                lockScreen();
            }
        });
        addQuick(bar, R.drawable.ic_repeat, new Runnable() {
            @Override
            public void run() {
                Settings.setLoop(!Settings.loop());
                showHint("循环播放 " + (Settings.loop() ? "开" : "关"));
            }
        });
        addQuick(bar, R.drawable.ic_aspect, new Runnable() {
            @Override
            public void run() {
                cycleScale();
            }
        });
        addQuick(bar, R.drawable.ic_camera, new Runnable() {
            @Override
            public void run() {
                screenshot();
            }
        });
        addQuick(bar, R.drawable.ic_subtitles, new Runnable() {
            @Override
            public void run() {
                if (core instanceof MpvPlayer) {
                    ((MpvPlayer) core).toggleSubs();
                    showHint("字幕开关");
                } else {
                    showHint("字幕仅 mpv 内核支持");
                }
            }
        });
        addQuick(bar, R.drawable.ic_music, new Runnable() {
            @Override
            public void run() {
                if (core instanceof MpvPlayer) {
                    ((MpvPlayer) core).cycleAudio();
                    showHint("切换音轨");
                } else {
                    showHint("音轨仅 mpv 内核支持");
                }
            }
        });
        addQuick(bar, R.drawable.ic_fullscreen, new Runnable() {
            @Override
            public void run() {
                if (getRequestedOrientation() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                } else {
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                }
            }
        });
        addQuick(bar, R.drawable.ic_close, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }

    private ImageView addQuick(LinearLayout bar, int iconRes, final Runnable action) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(Settings.accentColor());
        iv.setPadding(dp(12), dp(8), dp(12), dp(8));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(40)));
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        bar.addView(iv);
        return iv;
    }

    private void lockScreen() {
        lockMode = true;
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        if (lockFloat != null) lockFloat.setVisibility(View.GONE);
        showHint("已锁定：单击画面显示解锁按钮");
    }

    /** 锁定状态下，点画面时在左侧短暂显示解锁按钮，2 秒后自动隐去。 */
    private void showLockFloat() {
        if (lockFloat == null) return;
        lockFloat.setVisibility(View.VISIBLE);
        if (lockFloatHide != null) ui.removeCallbacks(lockFloatHide);
        lockFloatHide = new Runnable() {
            @Override
            public void run() {
                if (lockFloat != null) lockFloat.setVisibility(View.GONE);
            }
        };
        ui.postDelayed(lockFloatHide, 2000);
    }

    private void unlockScreen() {
        lockMode = false;
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        if (lockFloat != null) lockFloat.setVisibility(View.GONE);
    }

    private void cycleScale() {
        String cur = Settings.scale();
        String next = "fit".equals(cur) ? "stretch" : ("stretch".equals(cur) ? "crop" : "fit");
        Settings.setScale(next);
        if (webMode) js("jScale('" + next + "')");
        else if (core != null) core.setScale(next);
        applyScale();
        showHint("画面：" + ("fit".equals(next) ? "适应" : ("stretch".equals(next) ? "拉伸" : "裁剪")));
    }

    private void screenshot() {
        if (core instanceof MpvPlayer) {
            ((MpvPlayer) core).screenshot();
            showHint("已截图到 Pictures");
            Toast.makeText(this, "截图已保存到 /sdcard/Pictures/", Toast.LENGTH_SHORT).show();
        } else {
            showHint("截图仅 mpv 内核支持");
        }
    }

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        int v = controlsVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v);
        bottomBar.setVisibility(v);
    }

    private void mpvKey(String key) {
        if (core instanceof MpvPlayer) ((MpvPlayer) core).keyPress(key);
    }

    private void skip(long delta) {
        seekTo(currentPosMs() + delta);
        showHint((delta > 0 ? "快进 " : "快退 ") + Math.abs(delta) / 1000 + " 秒");
    }

    private void seekTo(long ms) {
        if (durationMs > 0) {
            if (ms < 0) ms = 0;
            if (ms > durationMs) ms = durationMs;
        }
        if (webMode) js("jSeek(" + (ms / 1000.0) + ")");
        else if (core != null) core.seekTo(ms);
    }

    private void setPlaybackSpeed(float s) {
        if (webMode) js("jSpeed(" + s + ")");
        else if (core != null) core.setSpeed(s);
    }

    private AudioManager am() {
        return (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    private float currentVolume() {
        try {
            AudioManager a = am();
            int max = Math.max(1, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            return a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max;
        } catch (Exception e) {
            return 50;
        }
    }

    private void setVolume(float pct) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        try {
            AudioManager a = am();
            int max = Math.max(1, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            a.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), 0);
        } catch (Exception ignored) {
        }
        showHint("音量 " + (int) pct + "%");
    }

    private float currentBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        return lp.screenBrightness < 0 ? 50 : lp.screenBrightness * 100;
    }

    private void setBrightness(float pct) {
        if (pct < 5) pct = 5;
        if (pct > 100) pct = 100;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = pct / 100f;
        getWindow().setAttributes(lp);
        showHint("亮度 " + (int) pct + "%");
    }

    private void setMpvProp(String prop, double v) {
        if (core instanceof MpvPlayer) ((MpvPlayer) core).setPropDouble(prop, v);
    }

    private void chooseMpvProp(final Dialog d, String title, final String prop, final double[] values) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = String.valueOf((int) values[i]);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setMpvProp(prop, values[which]);
                        d.dismiss();
                        showSettings();
                    }
                })
                .show();
    }

    private boolean readyToStart() {
        return surfaceReady && !urls.isEmpty() && !startCalled;
    }

    private void loadOnline() {
        loadingText.setText("测速选节点…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject info = Api.videoInfo(videoId);
                    final List<String> hls = Api.hlsUrls(info);
                    final List<String> mp4 = Api.mp4Urls(info);
                    // 用 mp4 的 4 个 CDN 测速（与 HLS 同域名池），选出最快节点
                    final java.util.Map<String, Long> speeds = Downloader.measureUrls(mp4);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            urls.clear();
                            mp4Fallback.clear();
                            mp4Fallback.addAll(mp4);
                            hosts.clear();
                            for (String u : (hls.isEmpty() ? mp4 : hls)) {
                                try {
                                    String h = java.net.URI.create(u).getHost();
                                    if (h != null && !hosts.contains(h)) hosts.add(h);
                                } catch (Exception ignored) {
                                }
                            }
                            String node = Settings.node();
                            String usedHost = "";
                            long usedSpeed = 0;
                            String best = null;
                            long bs = 0;
                            for (java.util.Map.Entry<String, Long> e : speeds.entrySet()) {
                                if (e.getValue() != null && e.getValue() > bs) {
                                    bs = e.getValue();
                                    best = e.getKey();
                                }
                            }
                            if (node.length() > 0) {
                                usedHost = node;
                                usedSpeed = speeds.containsKey(node) && speeds.get(node) != null ? speeds.get(node) : 0;
                            } else if (best != null) {
                                usedHost = best;
                                usedSpeed = bs;
                            }
                            // 优先 HLS（原生同款路径，速度高 3~8 倍）
                            String chosen = pickByHost(hls, usedHost);
                            usingHls = chosen != null;
                            if (chosen == null) chosen = pickByHost(mp4, usedHost);
                            if (chosen == null) {
                                chosen = hls.isEmpty() ? (mp4.isEmpty() ? null : mp4.get(0)) : hls.get(0);
                                usingHls = !hls.isEmpty();
                                if (chosen != null) {
                                    try {
                                        usedHost = java.net.URI.create(chosen).getHost();
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                            if (chosen == null) {
                                loadingText.setText("没有可用播放地址");
                                return;
                            }
                            urls.add(chosen);
                            StringBuilder st = new StringBuilder("节点 ").append(usedHost);
                            if (usedSpeed > 0) st.append(" · ").append(Downloader.human(usedSpeed)).append("/s");
                            st.append(" · ").append(usingHls ? "HLS" : "MP4");
                            statusText.setText(st.toString());
                            resumePos = Settings.resume() ? WatchStore.position(PlayerActivity.this, videoId) : 0;
                            WatchStore.touch(PlayerActivity.this, videoId, title, photo, resumePos / 1000);
                            if (surfaceReady) startCore();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            loadingText.setText("加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private String pickByHost(List<String> list, String host) {
        if (list == null || host == null || host.length() == 0) return null;
        for (String u : list) {
            if (u.contains("//" + host + "/")) return u;
        }
        return null;
    }

    private String currentUrl() {
        String node = Settings.node();
        if (node.length() > 0 && core != null) {
            for (String u : urls) {
                if (u.contains("//" + node + "/")) return u;
            }
        }
        return urls.isEmpty() ? null : urls.get(0);
    }

    private void startCore() {
        if (urls.isEmpty()) return;
        String url = currentUrl();
        if (url == null) return;
        startCalled = true;
        loadingBox.setVisibility(View.VISIBLE);
        loadingText.setText("缓冲中…");
        String engine = effectiveEngine();
        if ("mpv".equals(engine)) {
            webMode = false;
            if (webView != null) webView.setVisibility(View.GONE);
            surfaceView.setVisibility(View.VISIBLE);
            core = new MpvPlayer();
            try {
                core.init(this, surfaceView.getHolder().getSurface(), url, this);
                core.setSpeed(Settings.speed());
                core.setScale(Settings.scale());
            } catch (Throwable t) {
                loadingText.setText("mpv 启动失败：" + t.getMessage());
                core = null;
            }
        } else if ("system".equals(engine)) {
            webMode = false;
            if (webView != null) webView.setVisibility(View.GONE);
            surfaceView.setVisibility(View.VISIBLE);
            core = new SystemPlayer();
            try {
                core.init(this, surfaceView.getHolder().getSurface(), url, this);
                core.setSpeed(Settings.speed());
                core.setScale(Settings.scale());
            } catch (Throwable t) {
                loadingText.setText("播放器启动失败：" + t.getMessage());
                core = null;
            }
        } else {
            // WebView + hls.js：与原生 App 相同的播放路径
            webMode = true;
            core = null;
            prepared = false;
            webReady = false;
            webPlaying = true;
            surfaceView.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            lastUrl = url;
            webView.loadUrl("file:///android_asset/player.html");
        }
    }

    private void setupWeb() {
        if (webView == null) return;
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        webView.setBackgroundColor(0xFF000000);
        webView.addJavascriptInterface(new WebBridge(), "AndroidPlayer");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                if (lastUrl != null) {
                    js("start('" + lastUrl.replace("'", "\\'") + "')");
                    js("jScale('" + Settings.scale() + "')");
                    js("jSpeed(" + Settings.speed() + ")");
                }
            }
        });
    }

    private void js(String code) {
        try {
            if (webView != null) webView.evaluateJavascript(code, null);
        } catch (Exception ignored) {
        }
    }

    public class WebBridge {
        @JavascriptInterface
        public void onProgress(final double pos, final double dur) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    updateProgress((long) (pos * 1000), (long) (dur * 1000));
                }
            });
        }

        @JavascriptInterface
        public void onReady(final double dur) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!webReady) {
                        webReady = true;
                        prepared = true;
                        loadingBox.setVisibility(View.GONE);
                        if (dur > 0) durationMs = (long) (dur * 1000);
                        timeDur.setText(fmt(durationMs));
                        if (resumePos > 1000 && durationMs > 0 && resumePos < durationMs - 3000) {
                            js("jSeek(" + (resumePos / 1000.0) + ")");
                            Toast.makeText(PlayerActivity.this, "已从上次位置继续", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void onEnded() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (Settings.loop()) {
                        js("jSeek(0)");
                        js("jPlay()");
                    } else if (queuePos >= 0 && playNextInQueue()) {
                        // 已切下一项
                    } else {
                        statusText.setText("播放完毕");
                    }
                }
            });
        }

        @JavascriptInterface
        public void onEvent(final String msg) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (msg != null && (msg.startsWith("hls:") || msg.startsWith("video-error")
                            || msg.startsWith("start:") || msg.startsWith("play:"))) {
                        onError(msg);
                    }
                }
            });
        }
    }

    private void updateProgress(long position, long duration) {
        if (webMode) webPosMs = position;
        if (duration > 0) durationMs = duration;
        if (!dragging && durationMs > 0) {
            seek.setProgress((int) (1000L * position / durationMs));
            timePos.setText(fmt(position));
            timeDur.setText(fmt(durationMs));
        }
        updatePlayButton();
    }

    @Override
    public void onPrepared(long duration) {
        durationMs = duration;
        prepared = true;
        loadingBox.setVisibility(View.GONE);
        timeDur.setText(fmt(durationMs));
        if (resumePos > 1000 && resumePos < durationMs - 3000) {
            core.seekTo(resumePos);
            Toast.makeText(this, "已从上次位置继续", Toast.LENGTH_SHORT).show();
        }
        updatePlayButton();
    }

    @Override
    public void onProgress(long position, long duration) {
        updateProgress(position, duration);
    }

    @Override
    public void onVideoSize(int width, int height) {
        if (width > 0 && height > 0) {
            videoW = width;
            videoH = height;
            applyScale();
        }
    }

    @Override
    public void onCompletion() {
        if (Settings.loop()) {
            core.seekTo(0);
            core.play();
        } else if (queuePos >= 0 && playNextInQueue()) {
            return;
        } else {
            statusText.setText("播放完毕");
            updatePlayButton();
        }
    }

    /** 稍后再看队列：播完自动下一项。 */
    private boolean playNextInQueue() {
        java.util.List<WatchLaterStore.Item> list = WatchLaterStore.all(this);
        int next = queuePos + 1;
        if (next >= list.size()) return false;
        WatchLaterStore.Item it = list.get(next);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(EXTRA_ID, it.videoId);
        i.putExtra(EXTRA_TITLE, it.title);
        i.putExtra(EXTRA_PHOTO, it.photo);
        i.putExtra(EXTRA_QUEUE_POS, next);
        startActivity(i);
        finish();
        return true;
    }

    @Override
    public void onError(String message) {
        if (usingHls && !triedFallback && !mp4Fallback.isEmpty()) {
            triedFallback = true;
            usingHls = false;
            if (core != null) {
                core.release();
                core = null;
            }
            prepared = false;
            startCalled = false;
            urls.clear();
            String host = "";
            try {
                host = java.net.URI.create(mp4Fallback.get(0)).getHost();
            } catch (Exception ignored) {
            }
            String chosen = pickByHost(mp4Fallback, Settings.node());
            if (chosen == null) chosen = mp4Fallback.get(0);
            urls.add(chosen);
            loadingText.setText("HLS 播不动，改用 MP4 重试…");
            statusText.setText("节点 " + host + " · MP4");
            if (surfaceReady) startCore();
            return;
        }
        loadingBox.setVisibility(View.VISIBLE);
        loadingText.setText("播放失败：" + message);
        statusText.setText("错误");
    }

    private void togglePlay() {
        if (webMode) {
            webPlaying = !webPlaying;
            js(webPlaying ? "jPlay()" : "jPause()");
            updatePlayButton();
            return;
        }
        if (core == null) return;
        if (core.isPlaying()) core.pause();
        else core.play();
        updatePlayButton();
    }

    private void updatePlayButton() {
        boolean playing = webMode ? webPlaying : (core != null && core.isPlaying());
        Button pb = (Button) findViewById(R.id.btnPlay);
        pb.setText(playing ? "❚❚" : "▶");
        pb.setTextColor(Settings.accentColor());
        if (prepared && playing) statusText.setText("");
    }

    private void applyScale() {
        if (webMode) {
            js("jScale('" + Settings.scale() + "')");
            return;
        }
        if (core == null || videoW <= 0 || videoH <= 0) return;
        if (core instanceof MpvPlayer) {
            core.setScale(Settings.scale());
            return;
        }
        int cw = root.getWidth();
        int ch = root.getHeight();
        if (cw <= 0 || ch <= 0) return;
        String mode = Settings.scale();
        int w, h;
        if ("stretch".equals(mode)) {
            w = cw;
            h = ch;
        } else {
            double vRatio = (double) videoW / videoH;
            double cRatio = (double) cw / ch;
            if ("crop".equals(mode)) {
                if (vRatio > cRatio) {
                    h = ch;
                    w = (int) (ch * vRatio);
                } else {
                    w = cw;
                    h = (int) (cw / vRatio);
                }
            } else {
                if (vRatio > cRatio) {
                    w = cw;
                    h = (int) (cw / vRatio);
                } else {
                    h = ch;
                    w = (int) (ch * vRatio);
                }
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(lp);
    }

    // ------------------------------------------------------------ 设置

    private String engineLabel() {
        return "mpv".equals(Settings.engine()) ? "mpv（内置）" : "系统";
    }

    private String nodeLabel() {
        String n = Settings.node();
        return n.length() == 0 ? "自动（默认节点）" : n;
    }

    private String scaleLabel() {
        String s = Settings.scale();
        if ("stretch".equals(s)) return "拉伸铺满";
        if ("crop".equals(s)) return "裁剪铺满";
        return "适应屏幕";
    }

    /** 是否安装了某个包。 */
    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把当前 URL 交给外部 mpv-android 播放。 */
    private void playWithMpv() {
        String u = lastUrl != null ? lastUrl : (urls.isEmpty() ? "" : urls.get(0));
        if (u == null || u.length() == 0) {
            Toast.makeText(this, "还没有可播放的地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isInstalled("is.xyz.mpv")) {
            new AlertDialog.Builder(this)
                    .setTitle("未安装 mpv-android")
                    .setMessage("安装包已放在：\n/sdcard/Download/DSH-Go/mpv-android.apk\n\n装好后回到这里再点一次即可。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(android.net.Uri.parse(u), "video/*");
            i.setPackage("is.xyz.mpv");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "调起 mpv 失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showSettings() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF151515);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView titleView = new TextView(this);
        titleView.setText("播放器设置");
        titleView.setTextColor(0xFFFFD166);
        titleView.setTextSize(16);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(titleView);

        addRow(box, d, "播放内核", "web".equals(effectiveEngine()) ? "Web（原生同款）"
                : ("mpv".equals(effectiveEngine()) ? "mpv（内置）" : "系统"), new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(PlayerActivity.this)
                        .setTitle("播放内核")
                        .setItems(new String[]{"Web + hls.js（原生同款，推荐）", "系统播放器（MediaPlayer）", "mpv（内置，实验性）"},
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        engineOverride = null;
                                        Settings.setEngine(which == 0 ? "web" : (which == 1 ? "system" : "mpv"));
                                        d.dismiss();
                                        reload();
                                    }
                                })
                        .show();
            }
        });
        addRow(box, d, "用 mpv 播放", "交给外部 mpv-android", new Runnable() {
            @Override
            public void run() {
                d.dismiss();
                playWithMpv();
            }
        });
        addRow(box, d, "复制播放链接", "当前节点地址", new Runnable() {
            @Override
            public void run() {
                String u = lastUrl != null ? lastUrl : (urls.isEmpty() ? "" : urls.get(0));
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", u));
                Toast.makeText(PlayerActivity.this, "播放链接已复制", Toast.LENGTH_SHORT).show();
                d.dismiss();
            }
        });
        if ("mpv".equals(effectiveEngine())) {
            addRow(box, d, "mpv 设置（原生项）", "进入", new Runnable() {
                @Override
                public void run() {
                    d.dismiss();
                    startActivity(new Intent(PlayerActivity.this, MpvSettingsActivity.class));
                }
            });
            addRow(box, d, "画面亮度", "调节", new Runnable() {
                @Override
                public void run() {
                    chooseMpvProp(d, "亮度", "brightness", new double[]{-50, -20, 0, 20, 50});
                }
            });
            addRow(box, d, "对比度", "调节", new Runnable() {
                @Override
                public void run() {
                    chooseMpvProp(d, "对比度", "contrast", new double[]{-50, -20, 0, 20, 50});
                }
            });
            addRow(box, d, "饱和度", "调节", new Runnable() {
                @Override
                public void run() {
                    chooseMpvProp(d, "饱和度", "saturation", new double[]{-50, -20, 0, 20, 50});
                }
            });
            addRow(box, d, "字幕延迟(秒)", "调节", new Runnable() {
                @Override
                public void run() {
                    chooseMpvProp(d, "字幕延迟", "sub-delay", new double[]{-2, -1, 0, 1, 2});
                }
            });
            addRow(box, d, "音频延迟(秒)", "调节", new Runnable() {
                @Override
                public void run() {
                    chooseMpvProp(d, "音频延迟", "audio-delay", new double[]{-2, -1, 0, 1, 2});
                }
            });
        }
        addRow(box, d, "CDN 节点", nodeLabel(), new Runnable() {
            @Override
            public void run() {
                chooseNode(d);
            }
        });
        addRow(box, d, "播放速度", Settings.speed() + "x", new Runnable() {
            @Override
            public void run() {
                chooseSpeed(d);
            }
        });
        addRow(box, d, "画面缩放", scaleLabel(), new Runnable() {
            @Override
            public void run() {
                chooseScale(d);
            }
        });
        addRow(box, d, "循环播放", Settings.loop() ? "开" : "关", new Runnable() {
            @Override
            public void run() {
                Settings.setLoop(!Settings.loop());
                d.dismiss();
                showSettings();
            }
        });
        addRow(box, d, "记忆播放进度", Settings.resume() ? "开" : "关", new Runnable() {
            @Override
            public void run() {
                Settings.setResume(!Settings.resume());
                d.dismiss();
                showSettings();
            }
        });
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(0xFF151515);
        sv.addView(box, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xFF151515));
        d.show();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addRow(LinearLayout box, final Dialog d, String label, String value,
                        final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFE8E8E8);
        l.setTextSize(14);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(0xFFFFD166);
        v.setTextSize(13);
        row.addView(l);
        row.addView(v);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });
        box.addView(row);
    }

    private void chooseNode(final Dialog d) {
        if (hosts.isEmpty()) {
            Toast.makeText(this, "还没有获取到节点", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> items = new ArrayList<String>();
        items.add("自动（默认节点）");
        items.addAll(hosts);
        new AlertDialog.Builder(this)
                .setTitle("CDN 节点")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String node = which == 0 ? "" : items.get(which);
                        Settings.setNode(node);
                        d.dismiss();
                        reload();
                    }
                })
                .show();
    }

    private void chooseSpeed(final Dialog d) {
        final String[] labels = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
        final float[] values = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        new AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Settings.setSpeed(values[which]);
                        if (core != null) core.setSpeed(values[which]);
                        d.dismiss();
                        showSettings();
                    }
                })
                .show();
    }

    private void chooseScale(final Dialog d) {
        new AlertDialog.Builder(this)
                .setTitle("画面缩放")
                .setItems(new String[]{"适应屏幕", "拉伸铺满", "裁剪铺满"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Settings.setScale(which == 1 ? "stretch" : which == 2 ? "crop" : "fit");
                        if (webMode) js("jScale('" + Settings.scale() + "')");
                        else if (core != null) core.setScale(Settings.scale());
                        applyScale();
                        d.dismiss();
                        showSettings();
                    }
                })
                .show();
    }

    private void chooseHwdec(final Dialog d) {
        new AlertDialog.Builder(this)
                .setTitle("mpv 硬件解码")
                .setItems(new String[]{"自动（auto-safe）", "强制 mediacodec", "关闭（纯软解）"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Settings.setHwdec(which == 0 ? "auto-safe" : which == 1 ? "mediacodec" : "no");
                                d.dismiss();
                                reload();
                            }
                        })
                .show();
    }

    /** 切换节点/内核后按当前位置重新加载。 */
    private String effectiveEngine() {
        return engineOverride != null ? engineOverride : Settings.engine();
    }

    private void reload() {
        long pos = core == null ? resumePos : core.position();
        // mpv 内核且只是换节点：不重建实例，直接 loadfile
        if (core instanceof MpvPlayer && "mpv".equals(effectiveEngine())) {
            String nu = currentUrl();
            if (nu != null) {
                ((MpvPlayer) core).loadFile(nu);
                final long p2 = pos;
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (core != null) core.seekTo(p2);
                    }
                }, 800);
                resumePos = pos;
                loadingBox.setVisibility(View.GONE);
                statusText.setText("已切换节点");
                return;
            }
        }
        if (core != null) {
            core.release();
            core = null;
        }
        prepared = false;
        startCalled = false;
        resumePos = pos;
        loadingBox.setVisibility(View.VISIBLE);
        loadingText.setText("切换中…");
        if (surfaceReady) startCore();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    private void enterFullscreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                enterFullscreen();
                applyScale();
                if (webMode) js("jScale('" + Settings.scale() + "')");
            }
        }, 350);
    }

    private void showHint(String text) {
        if (gestureHint == null) return;
        gestureHint.setText(text);
        gestureHint.setVisibility(View.VISIBLE);
        if (hintHide != null) ui.removeCallbacks(hintHide);
        hintHide = new Runnable() {
            @Override
            public void run() {
                if (gestureHint != null) gestureHint.setVisibility(View.GONE);
            }
        };
        ui.postDelayed(hintHide, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webMode) {
            webPlaying = false;
            js("jPause()");
            if (videoId != null) {
                long p = currentPosMs();
                if (p > 0) WatchStore.touch(this, videoId, title, photo, p / 1000);
            }
            return;
        }
        if (core != null && prepared) {
            long p = core.position();
            if (videoId != null && p > 0) WatchStore.touch(this, videoId, title, photo, p / 1000);
            core.pause();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (webMode && webView != null) js("jStop()");
        if (core != null) core.release();
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("AndroidPlayer");
                webView.destroy();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private long currentPosMs() {
        return webMode ? webPosMs : (core == null ? 0 : core.position());
    }

    private String fmt(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }
}
