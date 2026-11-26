package io.github.nitsuya.donottryaccessibility.hook;

import android.content.Context;
import android.os.Binder;
import android.os.Process;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-package blocking policy.
 *
 * <p>Every check is performed server side: the caller of a binder transaction
 * is resolved to its package name through {@link Binder#getCallingUid()}, the
 * same mechanism the original LSPosed module used.</p>
 */
public final class Policy {

    public static final int FEATURE_ACCESSIBILITY = 1;
    public static final int FEATURE_ADB = 2;

    private static final ThreadLocal<Boolean> IN_HOOK = new ThreadLocal<>();

    private static final Map<Integer, String> UID_PACKAGE = new ConcurrentHashMap<>();

    private Policy() {
    }

    /**
     * Re-entrancy guard.  Reading the configuration from within a hook ends up
     * calling the settings provider again; the nested invocation returns
     * immediately instead of recursively consulting the configuration.
     */
    public static boolean enterHook() {
        if (Boolean.TRUE.equals(IN_HOOK.get())) {
            return false;
        }
        IN_HOOK.set(Boolean.TRUE);
        return true;
    }

    public static void exitHook() {
        IN_HOOK.remove();
    }

    public static boolean shouldBlock(int uid, int feature) {
        Config config = Config.get();
        if (!config.enabled) {
            return false;
        }
        if (feature == FEATURE_ACCESSIBILITY && !config.hideAccessibility) {
            return false;
        }
        if (feature == FEATURE_ADB && !config.hideAdb) {
            return false;
        }
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) {
            return false;
        }
        String pkg = packageForUid(uid);
        if (pkg == null) {
            return false;
        }
        if (HookConfig.MANAGER_PACKAGE.equals(pkg) || "android".equals(pkg)
                || "com.android.settings".equals(pkg)) {
            return false;
        }
        if (config.allow.contains(pkg)) {
            return false;
        }
        return Config.isBlocked(pkg);
    }

    private static String packageForUid(int uid) {
        String cached = UID_PACKAGE.get(uid);
        if (cached != null) {
            return cached;
        }
        Context context = Ctx.get();
        if (context == null) {
            return null;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                String pkg = packages[0];
                UID_PACKAGE.put(uid, pkg);
                return pkg;
            }
        } catch (Throwable t) {
            Logx.w("cannot resolve uid " + uid, t);
        }
        return null;
    }
}
