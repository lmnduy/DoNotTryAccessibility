package io.github.nitsuya.donottryaccessibility.hook;

/**
 * Small logging helper.  All log lines use a single tag so that they can be
 * filtered easily with {@code adb logcat -s DNTA-Zygisk}.
 */
public final class Logx {

    public static final String TAG = "DNTA-Zygisk";

    private Logx() {
    }

    public static void d(String message) {
        android.util.Log.d(TAG, message);
    }

    public static void i(String message) {
        android.util.Log.i(TAG, message);
    }

    public static void w(String message) {
        android.util.Log.w(TAG, message);
    }

    public static void w(String message, Throwable t) {
        android.util.Log.w(TAG, message, t);
    }

    public static void e(String message, Throwable t) {
        android.util.Log.e(TAG, message, t);
    }
}
