package io.github.nitsuya.donottryaccessibility.hook;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event-driven automatic selection of newly installed user applications.
 *
 * <p>Semantics are ported from OhMyKeymint's {@code injector/src/auto_targets.rs}:</p>
 * <ul>
 *     <li>Enabling records a baseline of every installed user package without
 *     selecting anything.</li>
 *     <li>Only packages that appear after the baseline are selected; app
 *     updates and unchanged packages never re-add a deselected app.</li>
 *     <li>If several new packages appear in one short window, only the one
 *     with the newest install time is selected; the others stay in the
 *     baseline.</li>
 *     <li>Disabling leaves selections alone and forgets the baseline; a later
 *     enable starts with a fresh snapshot.</li>
 *     <li>An unreadable user profile keeps its previous baseline; a newly
 *     discovered profile is baselined on its first successful scan.</li>
 * </ul>
 *
 * <p>New packages are discovered through the system's own
 * {@code PackageManagerInternal.PackageListObserver} and the
 * {@code ACTION_PACKAGE_ADDED} broadcast, not by polling.  Package scans only
 * happen once when auto-fill is enabled and once after boot to catch installs
 * that happened while the hook was not running.</p>
 */
public final class AutoFill {

    /** Batch window for install events so simultaneous installs behave like one scan. */
    private static final long EVENT_DEBOUNCE_MS = 2000L;

    private static final ScheduledExecutorService WORKER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "dnta-autofill");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static final Set<String> PENDING_ADD = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> PENDING_REMOVE = new ConcurrentHashMap<>();

    private static final AtomicBoolean sEventScheduled = new AtomicBoolean();
    private static final AtomicBoolean sScanning = new AtomicBoolean();

    private static volatile boolean sBaselineRequested;
    private static volatile boolean sBootReconciled;
    private static volatile boolean sClearRequested;
    private static volatile boolean sReceiverRegistered;
    private static volatile boolean sPackageObserverRegistered;
    private static volatile Object sPackageObserver;

    private AutoFill() {
    }

    public static void onConfig(Config config) {
        if (!config.autoFill) {
            if (config.autoUsers.length() > 0 && !sClearRequested) {
                sClearRequested = true;
                Config.clearAutoUsers();
            }
            return;
        }
        sClearRequested = false;
        registerObservers();

        if (config.autoUsers.length() == 0) {
            if (!sBaselineRequested) {
                sBaselineRequested = true;
                boolean submitted = submit(new Runnable() {
                    @Override
                    public void run() {
                        if (!captureBaseline()) {
                            sBaselineRequested = false;
                        }
                    }
                });
                if (!submitted) {
                    sBaselineRequested = false;
                }
            }
            return;
        }

        if (!sBootReconciled) {
            sBootReconciled = true;
            boolean submitted = submit(new Runnable() {
                @Override
                public void run() {
                    if (!reconcileOnce()) {
                        sBootReconciled = false;
                    }
                }
            });
            if (!submitted) {
                sBootReconciled = false;
            }
        }
    }

    private static boolean submit(Runnable task) {
        if (!sScanning.compareAndSet(false, true)) {
            return false;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } finally {
                    sScanning.set(false);
                }
            }
        });
        return true;
    }

    private static boolean captureBaseline() {
        Snapshot snapshot = snapshot();
        if (!snapshot.hasReadable()) {
            Logx.w("auto-fill: no readable user package snapshot yet");
            return false;
        }
        if (!stillEnabled()) {
            return true;
        }
        JSONObject baseline = new JSONObject();
        for (Map.Entry<Integer, Set<String>> entry : snapshot.users.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            try {
                baseline.put(String.valueOf(entry.getKey()), toArray(entry.getValue()));
            } catch (Throwable ignored) {
            }
        }
        Config.applyAutoState(baseline, null);
        sBootReconciled = true;
        Logx.i("auto-fill baseline recorded for users " + snapshot.readableUsers());
        return true;
    }

    /** One reconciliation after boot, for installs that happened while we were not running. */
    private static boolean reconcileOnce() {
        Config config = Config.get();
        if (!config.autoFill) {
            return true;
        }
        Snapshot snapshot = snapshot();
        if (!snapshot.hasReadable()) {
            return false;
        }
        if (!stillEnabled()) {
            return true;
        }
        Selection selection = selectNewPackages(config, snapshot);
        if (selection.changed) {
            Config.applyAutoState(selection.baseline, selection.selected);
        }
        return true;
    }

    private static void onPackageAdded(String pkg, int uid) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        Config config = Config.get();
        if (!config.autoFill || config.autoUsers.length() == 0) {
            return;
        }
        PENDING_ADD.add(pkg + "@" + uid);
        scheduleEventProcessing();
    }

    private static void onPackageRemoved(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        Config config = Config.get();
        if (!config.autoFill || config.autoUsers.length() == 0) {
            return;
        }
        PENDING_REMOVE.put(pkg, 1);
        scheduleEventProcessing();
    }

    private static void scheduleEventProcessing() {
        if (!sEventScheduled.compareAndSet(false, true)) {
            return;
        }
        WORKER.schedule(new Runnable() {
            @Override
            public void run() {
                sEventScheduled.set(false);
                processEvents();
            }
        }, EVENT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private static void processEvents() {
        Config config = Config.get();
        if (!config.autoFill || config.autoUsers.length() == 0) {
            PENDING_ADD.clear();
            PENDING_REMOVE.clear();
            return;
        }

        Set<String> added = new HashSet<>(PENDING_ADD);
        Set<String> removed = new HashSet<>(PENDING_REMOVE.keySet());
        PENDING_ADD.clear();
        PENDING_REMOVE.clear();
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        if (!stillEnabled()) {
            return;
        }

        try {
            JSONObject baseline = new JSONObject(config.autoUsers.toString());
            for (String pkg : removed) {
                removeFromAllUsers(baseline, pkg);
            }

            String selected = null;
            long newestTime = Long.MIN_VALUE;
            boolean changed = false;

            for (String value : added) {
                int at = value.lastIndexOf('@');
                String pkg = at > 0 ? value.substring(0, at) : value;
                int uid;
                try {
                    uid = Integer.parseInt(value.substring(at + 1));
                } catch (Throwable t) {
                    uid = 0;
                }
                int userId = uid / 100000; // AID_USER_OFFSET
                String key = String.valueOf(userId);
                JSONArray array = baseline.optJSONArray(key);
                if (array == null) {
                    // A profile we have not baselined yet: capture a fresh
                    // baseline for all users instead of bulk-selecting.
                    Logx.i("auto-fill: new profile " + userId + ", capturing baseline");
                    sBootReconciled = true;
                    submit(new Runnable() {
                        @Override
                        public void run() {
                            captureBaseline();
                        }
                    });
                    continue;
                }
                PackageInfo info = packageInfo(pkg, userId);
                if (info == null || info.applicationInfo == null) {
                    continue;
                }
                if (isSystem(info.applicationInfo)) {
                    continue;
                }
                if (!contains(array, pkg)) {
                    array.put(pkg);
                    changed = true;
                }
                if (config.allow.contains(pkg) || config.block.contains(pkg)
                        || config.auto.contains(pkg)) {
                    continue;
                }
                long installedAt = Math.max(info.firstInstallTime, 0L);
                if (installedAt > newestTime
                        || (installedAt == newestTime && selected != null
                        && pkg.compareTo(selected) > 0)) {
                    newestTime = installedAt;
                    selected = pkg;
                }
            }

            if (selected != null || changed || !removed.isEmpty()) {
                Config.applyAutoState(baseline, selected);
            }
        } catch (Throwable t) {
            Logx.w("auto-fill event processing failed", t);
        }
    }

    private static Selection selectNewPackages(Config config, Snapshot snapshot) {
        Selection selection = new Selection();
        selection.baseline = new JSONObject();
        String selected = null;
        long newestTime = Long.MIN_VALUE;
        boolean changed = false;

        for (Map.Entry<Integer, Set<String>> entry : snapshot.users.entrySet()) {
            int userId = entry.getKey();
            Set<String> current = entry.getValue();
            String key = String.valueOf(userId);
            if (current == null) {
                JSONArray previous = config.autoUsers.optJSONArray(key);
                if (previous != null) {
                    try {
                        selection.baseline.put(key, previous);
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }
            try {
                selection.baseline.put(key, toArray(current));
            } catch (Throwable ignored) {
            }
            JSONArray previous = config.autoUsers.optJSONArray(key);
            if (previous == null) {
                changed = true;
                continue;
            }
            if (!toSet(previous).equals(current)) {
                changed = true;
            }
            Set<String> old = toSet(previous);
            for (String pkg : current) {
                if (old.contains(pkg)) {
                    continue;
                }
                if (config.allow.contains(pkg)) {
                    continue;
                }
                Long installedAt = snapshot.installTime.get(pkg);
                long time = installedAt == null ? 0L : installedAt;
                if (time > newestTime
                        || (time == newestTime && selected != null
                        && pkg.compareTo(selected) > 0)) {
                    newestTime = time;
                    selected = pkg;
                }
            }
        }
        selection.selected = selected;
        selection.changed = changed || selected != null;
        return selection;
    }

    private static boolean stillEnabled() {
        Context context = Ctx.get();
        if (context == null) {
            return false;
        }
        try {
            return Config.readJson(context).optBoolean("autoFill", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        Context context = Ctx.get();
        if (context == null) {
            return snapshot;
        }
        PackageManager packageManager = context.getPackageManager();
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        Method asUser = null;
        try {
            asUser = PackageManager.class.getDeclaredMethod(
                    "getInstalledPackagesAsUser", int.class, int.class);
            asUser.setAccessible(true);
        } catch (Throwable ignored) {
        }

        for (int userId : userIds(userManager)) {
            List<PackageInfo> packages = null;
            if (asUser != null) {
                try {
                    //noinspection unchecked
                    packages = (List<PackageInfo>) asUser.invoke(packageManager, 0, userId);
                } catch (Throwable ignored) {
                }
            }
            if (packages == null && userId == 0) {
                try {
                    packages = packageManager.getInstalledPackages(0);
                } catch (Throwable ignored) {
                }
            }
            if (packages == null) {
                snapshot.users.put(userId, null);
                continue;
            }
            Set<String> userPackages = new HashSet<>();
            for (PackageInfo info : packages) {
                if (info == null || info.applicationInfo == null) {
                    continue;
                }
                if (isSystem(info.applicationInfo)) {
                    continue;
                }
                userPackages.add(info.packageName);
                long installedAt = Math.max(info.firstInstallTime, 0L);
                Long previous = snapshot.installTime.get(info.packageName);
                if (previous == null || installedAt > previous) {
                    snapshot.installTime.put(info.packageName, installedAt);
                }
            }
            snapshot.users.put(userId, userPackages);
        }
        return snapshot;
    }

    private static PackageInfo packageInfo(String pkg, int userId) {
        Context context = Ctx.get();
        if (context == null) {
            return null;
        }
        PackageManager packageManager = context.getPackageManager();
        try {
            Method method = PackageManager.class.getDeclaredMethod(
                    "getPackageInfoAsUser", String.class, int.class, int.class);
            method.setAccessible(true);
            return (PackageInfo) method.invoke(packageManager, pkg, 0, userId);
        } catch (Throwable ignored) {
        }
        if (userId == 0) {
            try {
                return packageManager.getPackageInfo(pkg, 0);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void removeFromAllUsers(JSONObject baseline, String pkg) {
        JSONArray keys = baseline.names();
        if (keys == null) {
            return;
        }
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.optString(i);
            JSONArray array = baseline.optJSONArray(key);
            if (array == null) {
                continue;
            }
            JSONArray replaced = new JSONArray();
            boolean changed = false;
            for (int j = 0; j < array.length(); j++) {
                String value = array.optString(j);
                if (pkg.equals(value)) {
                    changed = true;
                } else {
                    replaced.put(value);
                }
            }
            if (changed) {
                try {
                    baseline.put(key, replaced);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean contains(JSONArray array, String pkg) {
        for (int i = 0; i < array.length(); i++) {
            if (pkg.equals(array.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> userIds(UserManager userManager) {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(0);
        try {
            Method getCurrentUser = ActivityManager.class.getDeclaredMethod("getCurrentUser");
            getCurrentUser.setAccessible(true);
            Object current = getCurrentUser.invoke(null);
            if (current instanceof Integer) {
                ids.add((Integer) current);
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getUsers = UserManager.class.getDeclaredMethod("getUsers");
            getUsers.setAccessible(true);
            Object users = getUsers.invoke(userManager);
            if (users instanceof Iterable) {
                for (Object user : (Iterable<?>) users) {
                    if (user == null) {
                        continue;
                    }
                    try {
                        Object id = user.getClass().getField("id").get(user);
                        if (id instanceof Integer) {
                            ids.add((Integer) id);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>(ids);
    }

    private static JSONArray toArray(Set<String> packages) {
        JSONArray array = new JSONArray();
        for (String pkg : packages) {
            array.put(pkg);
        }
        return array;
    }

    private static Set<String> toSet(JSONArray array) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i);
            if (value != null && !value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static boolean isSystem(ApplicationInfo info) {
        return (info.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    /** Register the system package observer and the broadcast fallback. */
    public static void registerObservers() {
        registerPackageObserver();
        registerReceiver();
    }

    private static void registerPackageObserver() {
        if (sPackageObserverRegistered) {
            return;
        }
        try {
            Class<?> localServices = Class.forName("com.android.server.LocalServices");
            Class<?> pmInternalClass = Class.forName("android.content.pm.PackageManagerInternal");
            Method getService = localServices.getDeclaredMethod("getService", Class.class);
            getService.setAccessible(true);
            Object pmInternal = getService.invoke(null, pmInternalClass);
            if (pmInternal == null) {
                return;
            }
            Class<?> observerClass = Class.forName(
                    "android.content.pm.PackageManagerInternal$PackageListObserver");
            Object observer = Proxy.newProxyInstance(
                    observerClass.getClassLoader(),
                    new Class<?>[]{observerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onPackageAdded".equals(name) && args != null && args.length >= 2) {
                                onPackageAdded((String) args[0], ((Number) args[1]).intValue());
                            } else if ("onPackageRemoved".equals(name)
                                    && args != null && args.length >= 2) {
                                onPackageRemoved((String) args[0]);
                            } else if ("equals".equals(name)) {
                                return proxy == args[0];
                            } else if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            } else if ("toString".equals(name)) {
                                return "DNTA-PackageListObserver";
                            }
                            return null;
                        }
                    });
            Method getPackageList = pmInternalClass.getMethod("getPackageList", observerClass);
            getPackageList.setAccessible(true);
            sPackageObserver = observer;
            getPackageList.invoke(pmInternal, observer);
            sPackageObserverRegistered = true;
            Logx.i("PackageManagerInternal observer registered");
        } catch (Throwable t) {
            Logx.w("cannot register PackageManagerInternal observer", t);
        }
    }

    /** Broadcast fallback; harmless if the internal observer is already active. */
    private static void registerReceiver() {
        if (sReceiverRegistered) {
            return;
        }
        Context context = Ctx.get();
        if (context == null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent.getData() == null) {
                        return;
                    }
                    String pkg = intent.getData().getSchemeSpecificPart();
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
                    if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                        onPackageAdded(pkg, uid);
                    } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                            && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        onPackageRemoved(pkg);
                    }
                }
            }, filter);
            sReceiverRegistered = true;
            Logx.i("package broadcast receiver registered");
        } catch (Throwable t) {
            Logx.w("cannot register package broadcast receiver", t);
        }
    }

    private static final class Snapshot {
        final Map<Integer, Set<String>> users = new LinkedHashMap<>();
        final Map<String, Long> installTime = new LinkedHashMap<>();

        boolean hasReadable() {
            for (Set<String> value : users.values()) {
                if (value != null) {
                    return true;
                }
            }
            return false;
        }

        List<Integer> readableUsers() {
            List<Integer> result = new ArrayList<>();
            for (Map.Entry<Integer, Set<String>> entry : users.entrySet()) {
                if (entry.getValue() != null) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }
    }

    private static final class Selection {
        JSONObject baseline;
        String selected;
        boolean changed;
    }
}
