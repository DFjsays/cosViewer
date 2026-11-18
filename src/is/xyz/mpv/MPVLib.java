package is.xyz.mpv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * libmpv 的 Java 绑定，严格对照 mpv-android 官方 MPVLib.kt 实现：
 *  - Kotlin object → Java 单例 + INSTANCE 静态字段
 *  - native 方法在 object 中是实例方法
 *  - event / eventProperty / logMessage 等回调是 @JvmStatic 静态方法（JNI 按静态方法查找）
 */
public final class MPVLib {

    static {
        // 官方顺序：先 mpv，再 player
        System.loadLibrary("mpv");
        System.loadLibrary("player");
    }

    /** 对应 Kotlin object 的 INSTANCE 字段，JNI 可能通过它取单例。 */
    public static final MPVLib INSTANCE = new MPVLib();

    public interface EventObserver {
        void event(int eventId);

        void eventProperty(String name);

        void eventProperty(String name, boolean value);

        void eventProperty(String name, long value);

        void eventProperty(String name, double value);

        void eventProperty(String name, String value);
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    // 与官方 MpvFormat / MpvEvent 常量对齐
    public static final int FORMAT_NONE = 0;
    public static final int FORMAT_STRING = 1;
    public static final int FORMAT_OSD_STRING = 2;
    public static final int FORMAT_FLAG = 3;
    public static final int FORMAT_INT64 = 4;
    public static final int FORMAT_DOUBLE = 5;

    public static final int EVENT_NONE = 0;
    public static final int EVENT_SHUTDOWN = 1;
    public static final int EVENT_LOG_MESSAGE = 2;
    public static final int EVENT_START_FILE = 6;
    public static final int EVENT_END_FILE = 7;
    public static final int EVENT_FILE_LOADED = 8;
    public static final int EVENT_PROPERTY_CHANGE = 22;

    private static final List<EventObserver> observers = new CopyOnWriteArrayList<EventObserver>();
    private static final List<LogObserver> logObservers = new CopyOnWriteArrayList<LogObserver>();

    private MPVLib() {
    }

    public static MPVLib get() {
        return INSTANCE;
    }

    // ---------------- native（实例方法） ----------------
    public native void create(Context appctx);

    public native void init();

    public native void destroy();

    public native void attachSurface(Surface surface);

    public native void detachSurface();

    public native void command(String[] cmd);

    public native int setOptionString(String name, String value);

    public native void observeProperty(String name, int format);

    public native void setPropertyInt(String name, int value);

    public native void setPropertyDouble(String name, double value);

    public native void setPropertyBoolean(String name, boolean value);

    public native void setPropertyString(String name, String value);

    public native Integer getPropertyInt(String name);

    public native Double getPropertyDouble(String name);

    public native Boolean getPropertyBoolean(String name);

    public native String getPropertyString(String name);

    public native Bitmap grabThumbnail(int dimension);

    // ---------------- 回调（静态，对应 @JvmStatic） ----------------
    public static void addObserver(EventObserver o) {
        if (o != null && !observers.contains(o)) observers.add(o);
    }

    public static void removeObserver(EventObserver o) {
        observers.remove(o);
    }

    public static void addLogObserver(LogObserver o) {
        if (o != null && !logObservers.contains(o)) logObservers.add(o);
    }

    public static void removeLogObserver(LogObserver o) {
        logObservers.remove(o);
    }

    public static void event(int eventId) {
        for (EventObserver o : observers) o.event(eventId);
    }

    public static void eventProperty(String name) {
        for (EventObserver o : observers) o.eventProperty(name);
    }

    public static void eventProperty(String name, boolean value) {
        for (EventObserver o : observers) o.eventProperty(name, value);
    }

    public static void eventProperty(String name, long value) {
        for (EventObserver o : observers) o.eventProperty(name, value);
    }

    public static void eventProperty(String name, double value) {
        for (EventObserver o : observers) o.eventProperty(name, value);
    }

    public static void eventProperty(String name, String value) {
        for (EventObserver o : observers) o.eventProperty(name, value);
    }

    public static void logMessage(String prefix, int level, String text) {
        for (LogObserver o : logObservers) o.logMessage(prefix, level, text);
    }
}
