package io.github.nitsuya.donottryaccessibility.hook;

import android.content.Context;
import android.os.SystemClock;

import java.lang.reflect.Method;

/** Resolves the application context inside an injected app process. */
public final class AppCtx {

    private static volatile Context sContext;

    private AppCtx() {
    }

    public static Context get() {
        Context cached = sContext;
        if (cached != null) {
            return cached;
        }
        Context context = resolve();
        if (context != null) {
            sContext = context;
        }
        return context;
    }

    public static Context awaitContext() {
        for (int i = 0; i < 400; i++) {
            Context context = get();
            if (context != null) {
                return context;
            }
            SystemClock.sleep(50);
        }
        return null;
    }

    private static Context resolve() {
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
