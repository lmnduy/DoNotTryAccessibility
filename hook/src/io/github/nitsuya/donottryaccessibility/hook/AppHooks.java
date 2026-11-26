package io.github.nitsuya.donottryaccessibility.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Client-side ADB hiding for blocked applications.
 *
 * <p>Faking {@code Settings.Global.adb_enabled} is not enough for a detector such as
 * Duck Detector: it cross-checks {@code init.svc.adbd}, {@code sys.usb.state}, the
 * USB sticky broadcast, {@code /dev/socket/adbd} and adbd's JDWP control socket.
 * This class is installed in each user-app process and rewrites those surfaces
 * when the current package is in the block list.</p>
 */
public final class AppHooks {

    private static final String CONFIG_KEY = "dnta_config";
    private static final String USB_STATE_ACTION = "android.hardware.usb.action.USB_STATE";
    private static final long CONFIG_TTL_MS = 3000L;

    private static final ThreadLocal<Boolean> LOADING = new ThreadLocal<>();

    private static volatile String sPackage;
    private static volatile JSONObject sConfig;
    private static volatile long sConfigAt;

    private AppHooks() {
    }

    public static void install(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        sPackage = packageName;
        try {
            hookSystemProperties();
            hookSettings();
            hookOsStat();
            hookFileList();
            hookUsbReceiver();
            if (packageName.contains("duckdetector")) {
                watchDuckJdwp();
            }
            Logx.i("app hooks installed for " + packageName);
        } catch (Throwable t) {
            Logx.e("cannot install app hooks", t);
        }
    }

    /** Used before installing hooks: only blocked packages need the hooks at all. */
    public static boolean isBlocked(Context context, String pkg) {
        if (context == null || pkg == null) {
            return false;
        }
        try {
            String raw = Settings.Global.getString(context.getContentResolver(), CONFIG_KEY);
            JSONObject config = raw == null || raw.trim().isEmpty()
                    ? new JSONObject() : new JSONObject(raw);
            if (!config.optBoolean("enabled", true) || !config.optBoolean("hideAdb", true)) {
                return false;
            }
            if (contains(config, "allow", pkg)) {
                return false;
            }
            return contains(config, "block", pkg) || contains(config, "auto", pkg);
        } catch (Throwable t) {
            return false;
        }
    }
    /* ------------------------------ config ------------------------------ */

    private static JSONObject config() {
        Context context = AppCtx.get();
        if (context == null) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        JSONObject cached = sConfig;
        if (cached != null && now - sConfigAt < CONFIG_TTL_MS) {
            return cached;
        }
        if (Boolean.TRUE.equals(LOADING.get())) {
            return cached;
        }
        LOADING.set(Boolean.TRUE);
        try {
            String raw = Settings.Global.getString(context.getContentResolver(), CONFIG_KEY);
            cached = raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
            sConfig = cached;
            sConfigAt = now;
        } catch (Throwable t) {
            cached = sConfig;
        } finally {
            LOADING.remove();
        }
        return cached;
    }

    private static boolean blocked() {
        String pkg = sPackage;
        if (pkg == null) {
            return false;
        }
        JSONObject config = config();
        if (config == null || !config.optBoolean("enabled", true)
                || !config.optBoolean("hideAdb", true)) {
            return false;
        }
        if (contains(config, "allow", pkg)) {
            return false;
        }
        return contains(config, "block", pkg) || contains(config, "auto", pkg);
    }

    private static boolean contains(JSONObject json, String key, String value) {
        JSONArray array = json.optJSONArray(key);
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) {
                return true;
            }
        }
        return false;
    }

    /* -------------------------- system properties ----------------------- */

    private static void hookSystemProperties() throws Throwable {
        Class<?> clazz = Class.forName("android.os.SystemProperties");
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"get".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1 || params.length > 2 || params[0] != String.class) {
                continue;
            }
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    if (!blocked()) {
                        return;
                    }
                    Object keyObject = callFrame.args[0];
                    if (!(keyObject instanceof String)) {
                        return;
                    }
                    String fake = fakeProperty((String) keyObject);
                    if (fake != null) {
                        callFrame.setResult(fake);
                    }
                }
            }, false);
        }
    }

    private static String fakeProperty(String key) {
        switch (key) {
            case "init.svc.adbd":
                return "stopped";
            case "sys.usb.state":
                return "none";
            case "sys.usb.config":
            case "persist.sys.usb.config":
                return "mtp";
            case "persist.sys.test_harness":
            case "service.adb.root":
                return "0";
            case "service.adb.tcp.port":
                return "";
            default:
                return null;
        }
    }

    /* ------------------------------ settings ---------------------------- */

    private static void hookSettings() throws Throwable {
        Class<?> clazz = Class.forName("android.provider.Settings$Global");
        Method getString = clazz.getDeclaredMethod("getString",
                android.content.ContentResolver.class, String.class);
        Pine.hook(getString, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                if (Boolean.TRUE.equals(LOADING.get()) || !blocked()) {
                    return;
                }
                Object key = callFrame.args[1];
                if ("adb_enabled".equals(key) || "adb_wifi_enabled".equals(key)
                        || "development_settings_enabled".equals(key)) {
                    callFrame.setResult("0");
                }
            }
        }, false);
    }

    /* ------------------------------- stat ------------------------------- */

    private static void hookOsStat() throws Throwable {
        Class<?> clazz = Class.forName("android.system.Os");
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"stat".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1 || params[0] != String.class) {
                continue;
            }
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    if (!blocked() || !"/dev/socket/adbd".equals(callFrame.args[0])) {
                        return;
                    }
                    callFrame.setThrowable(new ErrnoException("stat", OsConstants.ENOENT));
                }
            }, false);
        }
    }

    /* ------------------------------ File.list --------------------------- */

    private static void hookFileList() throws Throwable {
        Method list = File.class.getDeclaredMethod("list");
        Pine.hook(list, new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame callFrame) {
                if (!blocked() || !(callFrame.thisObject instanceof File)) {
                    return;
                }
                File file = (File) callFrame.thisObject;
                if (!"/dev/socket".equals(file.getPath())) {
                    return;
                }
                Object result = callFrame.getResult();
                if (!(result instanceof String[])) {
                    return;
                }
                String[] values = (String[]) result;
                int count = 0;
                for (String value : values) {
                    if (!"adbd".equals(value)) {
                        count++;
                    }
                }
                String[] filtered = new String[count];
                int index = 0;
                for (String value : values) {
                    if (!"adbd".equals(value)) {
                        filtered[index++] = value;
                    }
                }
                callFrame.setResult(filtered);
            }
        }, false);
    }

    /* --------------------------- USB broadcast -------------------------- */

    private static void hookUsbReceiver() throws Throwable {
        Class<?> clazz = Class.forName("android.content.ContextWrapper");
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"registerReceiver".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 2 || params[0] != BroadcastReceiver.class
                    || params[1] != IntentFilter.class) {
                continue;
            }
            Pine.hook(method, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    if (!blocked()) {
                        return;
                    }
                    Object result = callFrame.getResult();
                    if (!(result instanceof Intent)) {
                        return;
                    }
                    Intent intent = (Intent) result;
                    if (USB_STATE_ACTION.equals(intent.getAction())) {
                        intent.putExtra("adb", false);
                    }
                }
            }, false);
        }
    }

    /* ------------------------- Duck JDWP listener ----------------------- */

    private static void watchDuckJdwp() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 600; i++) {
                    try {
                        Context context = AppCtx.get();
                        ClassLoader loader = context == null
                                ? AppHooks.class.getClassLoader()
                                : context.getClassLoader();
                        Class<?> bridge = Class.forName(
                                "com.eltavine.duckdetector.features.adbruntime.data.repository.AdbJdwpSocketBridge",
                                false, loader);
                        Method connectErrno = bridge.getDeclaredMethod(
                                "nativeConnectErrno", String.class, boolean.class);
                        Pine.hook(connectErrno, new MethodHook() {
                            @Override
                            public void beforeCall(Pine.CallFrame callFrame) {
                                if (blocked() && "jdwp-control".equals(callFrame.args[0])) {
                                    callFrame.setResult(-OsConstants.ECONNREFUSED);
                                }
                            }
                        }, false);
                        Logx.i("Duck JDWP bridge hooked");
                        return;
                    } catch (ClassNotFoundException e) {
                        SystemClock.sleep(100);
                    } catch (Throwable t) {
                        Logx.w("cannot hook Duck JDWP bridge", t);
                        return;
                    }
                }
            }
        }, "dnta-duck-jdwp");
        thread.setDaemon(true);
        thread.start();
    }
}
