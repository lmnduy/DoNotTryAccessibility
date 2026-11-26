package io.github.nitsuya.donottryaccessibility.hook;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Method;

/**
 * Resolves the system {@link Context} inside {@code system_server}.
 *
 * <p>The Zygisk server hook runs before {@code ActivityThread.systemMain()};
 * the content providers and the services class loader are not ready at that
 * point.  {@link #awaitSystemReady()} waits until the settings provider can be
 * queried and {@code AccessibilityManagerService} is loadable, then caches the
 * context for all later hooks.</p>
 */
public final class Ctx {

    private static volatile Context sContext;

    private Ctx() {
    }

    public static Context get() {
        return sContext;
    }

    public static ClassLoader systemClassLoader() {
        Context context = sContext;
        return context == null ? null : context.getClassLoader();
    }

    public static Context awaitSystemReady() {
        for (int i = 0; i < 1200; i++) {
            Context context = resolve();
            if (context != null && isReady(context)) {
                sContext = context;
                return context;
            }
            SystemClock.sleep(250);
        }
        return null;
    }

    private static boolean isReady(Context context) {
        try {
            // SettingsProvider is one of the first providers; if it answers,
            // system_server is far enough along for our hooks and heartbeat.
            Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
            // services.jar must be visible through the system class loader.
            Class.forName("com.android.server.accessibility.AccessibilityManagerService",
                    false, context.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Context resolve() {
        // ActivityThread.currentActivityThread().getSystemContext()
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread != null) {
                Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
                getSystemContext.setAccessible(true);
                Object context = getSystemContext.invoke(thread);
                if (context instanceof Context) {
                    return (Context) context;
                }
            }
        } catch (Throwable ignored) {
        }
        // ActivityThread.currentApplication()
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object context = currentApplication.invoke(null);
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
        }
        // AppGlobals.getInitialApplication()
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Method getInitialApplication = appGlobals.getDeclaredMethod("getInitialApplication");
            getInitialApplication.setAccessible(true);
            Object context = getInitialApplication.invoke(null);
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
