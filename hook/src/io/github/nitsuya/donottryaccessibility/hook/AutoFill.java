package io.github.nitsuya.donottryaccessibility.hook;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.UserManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Automatic selection of newly installed user applications.
 *
 * <p>Ported from OhMyKeymint's {@code injector/src/auto_targets.rs}:</p>
 * <ul>
 *     <li>Enabling records a baseline of every installed user package without
 *     selecting anything.</li>
 *     <li>Only packages that appear after the baseline are selected; app
 *     updates and unchanged packages never re-add a deselected app.</li>
 *     <li>If several new packages appear in one scan, only the one with the
 *     newest install time is selected; the others stay in the baseline.</li>
 *     <li>Disabling leaves existing selections alone and forgets the baseline,
 *     so the next enable starts from a fresh snapshot (apps installed while
 *     disabled are not added in bulk).</li>
 *     <li>An unreadable user profile keeps its previous baseline.</li>
 *     <li>A newly discovered profile is baselined on its first successful
 *     scan.</li>
 * </ul>
 */
public final class AutoFill {

    private static final long SCAN_INTERVAL_MS = 60_000L;

    private static volatile long sLastScan;
    private static volatile boolean sScanning;
    private static volatile boolean sBaselineRequested;
    private static volatile boolean sClearRequested;
    private static volatile boolean sReceiverRegistered;

    private AutoFill() {
    }

    public static void onConfig(Config config) {
        if (!config.autoFill) {
            sLastScan = 0L;
            if (config.autoUsers.length() > 0 && !sClearRequested) {
                sClearRequested = true;
                Config.clearAutoUsers();
            }
            return;
        }
        sClearRequested = false;
        if (config.autoUsers.length() == 0) {
            if (!sBaselineRequested) {
                sBaselineRequested = true;
                runAsync(true);
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sLastScan < SCAN_INTERVAL_MS) {
            return;
        }
        sLastScan = now;
        runAsync(false);
    }

    /** Called from the package broadcast; scan again as soon as possible. */
    public static void onPackageAdded() {
        Config config = Config.get();
        if (config.autoFill && config.autoUsers.length() > 0) {
            sLastScan = 0L;
            runAsync(false);
        }
    }

    private static void runAsync(final boolean baselineOnly) {
        if (sScanning) {
            return;
        }
        sScanning = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (baselineOnly) {
                        if (!captureBaseline()) {
                            sBaselineRequested = false;
                        }
                    } else {
                        poll();
                    }
                } finally {
                    sScanning = false;
                }
            }
        }, "dnta-autofill");
        thread.setDaemon(true);
        thread.start();
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
        Logx.i("auto-fill baseline recorded for users " + snapshot.readableUsers());
        return true;
    }

    private static void poll() {
        Config config = Config.get();
        if (!config.autoFill) {
            return;
        }
        Snapshot snapshot = snapshot();
        if (!snapshot.hasReadable()) {
            return;
        }
        // Abort a scan that raced with the user switching the feature off.
        if (!stillEnabled()) {
            return;
        }

        String selected = null;
        long newestTime = Long.MIN_VALUE;
        JSONObject newBaseline = new JSONObject();

        for (Map.Entry<Integer, Set<String>> entry : snapshot.users.entrySet()) {
            int userId = entry.getKey();
            Set<String> current = entry.getValue();
            String key = String.valueOf(userId);
            if (current == null) {
                // Unreadable profile: keep its previous baseline untouched.
                JSONArray previous = config.autoUsers.optJSONArray(key);
                if (previous != null) {
                    try {
                        newBaseline.put(key, previous);
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }
            try {
                newBaseline.put(key, toArray(current));
            } catch (Throwable ignored) {
            }
            JSONArray previous = config.autoUsers.optJSONArray(key);
            if (previous == null) {
                // First successful scan for this profile: baseline only.
                continue;
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

        if (selected != null
                && (config.auto.contains(selected) || config.block.contains(selected))) {
            selected = null;
        }

        boolean baselineChanged = !newBaseline.toString().equals(config.autoUsers.toString());
        if (selected != null || baselineChanged) {
            Config.applyAutoState(newBaseline, selected);
        }
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

    /** Register for package install broadcasts so new apps are picked up quickly. */
    public static void registerReceiver() {
        if (sReceiverRegistered) {
            return;
        }
        Context context = Ctx.get();
        if (context == null) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                        onPackageAdded();
                    }
                }
            }, filter);
            sReceiverRegistered = true;
            Logx.i("package receiver registered");
        } catch (Throwable t) {
            Logx.w("cannot register package receiver", t);
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
}
