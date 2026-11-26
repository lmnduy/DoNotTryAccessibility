package io.github.nitsuya.donottryaccessibility.hook;

import android.content.Context;
import android.os.SystemClock;

import java.lang.reflect.Method;

/**
 * Lazily resolves the system {@link Context} inside {@code system_server}.
 *
 * <p>The hook is installed from {@code postServerSpecialize}, which runs before
 * {@code ActivityThread.systemMain()}; therefore the context is not available
 * immediately.  A small daemon thread polls until it shows up and then writes
 * the initial status heartbeat.</p>
 */
public final class Ctx {

    private static volatile Context sContext;

    private Ctx() {
    }

    public static Context get() {
        Context cached = sContext;
        if (cached != null) {
            return cached;
        }
        Context resolved = resolve();
        if (resolved != null) {
            sContext = resolved;
        }
        return resolved;
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

    public static void startPolling() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1200; i++) {
                    if (get() != null) {
                        break;
                    }
                    SystemClock.sleep(100);
                }
                Context context = get();
                if (context == null) {
                    Logx.w("system context never appeared, auto-fill persistence disabled");
                    return;
                }
                Logx.i("system context ready");
                Status.write();
                Config.get();
                AutoFill.registerReceiver();
            }
        }, "dnta-ctx");
        thread.setDaemon(true);
        thread.start();
    }
}
