package io.github.nitsuya.donottryaccessibility.hook;

import java.io.FileOutputStream;

/**
 * Small logging helper.  All log lines use a single tag so that they can be
 * filtered easily with {@code adb logcat -s DNTA-Zygisk}.
 *
 * <p>Inside system_server the same lines are appended to
 * {@code /data/system/dnta_java.log}; early boot logcat may be rotated away,
 * which makes this file useful when diagnosing Zygisk injection problems.</p>
 */
public final class Logx {

    public static final String TAG = "DNTA-Zygisk";
    private static final String FILE = "/data/system/dnta_java.log";

    private Logx() {
    }

    private static void file(String level, String message, Throwable throwable) {
        try {
            StringBuilder builder = new StringBuilder();
            builder.append(level).append(' ').append(message);
            if (throwable != null) {
                builder.append('\n').append(android.util.Log.getStackTraceString(throwable));
            }
            builder.append('\n');
            FileOutputStream out = new FileOutputStream(FILE, true);
            try {
                out.write(builder.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void d(String message) {
        android.util.Log.d(TAG, message);
        file("D", message, null);
    }

    public static void i(String message) {
        android.util.Log.i(TAG, message);
        file("I", message, null);
    }

    public static void w(String message) {
        android.util.Log.w(TAG, message);
        file("W", message, null);
    }

    public static void w(String message, Throwable t) {
        android.util.Log.w(TAG, message, t);
        file("W", message, t);
    }

    public static void e(String message, Throwable t) {
        android.util.Log.e(TAG, message, t);
        file("E", message, t);
    }
}
