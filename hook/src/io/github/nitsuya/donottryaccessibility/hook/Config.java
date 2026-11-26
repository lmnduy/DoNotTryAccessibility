package io.github.nitsuya.donottryaccessibility.hook;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configuration shared between the WebUI and the system_server hook.
 *
 * <p>The configuration lives in {@link Settings.Global} so that system_server
 * can read/write it without file permission tricks and the KernelSU/APatch
 * WebUI can change it with a plain {@code settings put} command.</p>
 */
public final class Config {

    private static final long REFRESH_INTERVAL_MS = 3000L;

    private static final Object LOCK = new Object();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "dnta-config-writer");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static volatile Config sCached;
    private static volatile boolean sRefresherStarted;

    private static final Config DEFAULT = defaults();

    public final boolean enabled;
    public final boolean hideAccessibility;
    public final boolean hideAdb;
    public final boolean autoFill;
    /**
     * Per-user baseline of installed user packages.  The first scan for a user
     * is a baseline and never selects anything; later scans only select
     * packages that were not in the baseline.
     */
    public final JSONObject autoUsers;
    public final Set<String> block;
    public final Set<String> auto;
    public final Set<String> allow;

    private Config(boolean enabled, boolean hideAccessibility, boolean hideAdb, boolean autoFill,
                   JSONObject autoUsers, Set<String> block, Set<String> auto, Set<String> allow) {
        this.enabled = enabled;
        this.hideAccessibility = hideAccessibility;
        this.hideAdb = hideAdb;
        this.autoFill = autoFill;
        this.autoUsers = autoUsers;
        this.block = block;
        this.auto = auto;
        this.allow = allow;
    }

    public static void startRefresher() {
        if (sRefresherStarted) {
            return;
        }
        sRefresherStarted = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        if (Ctx.get() != null) {
                            Config loaded = load();
                            synchronized (LOCK) {
                                sCached = loaded;
                            }
                            AutoFill.onConfig(loaded);
                        }
                    } catch (Throwable t) {
                        Logx.w("config refresh failed", t);
                    }
                    android.os.SystemClock.sleep(REFRESH_INTERVAL_MS);
                }
            }
        }, "dnta-config-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    public static Config get() {
        Config cached = sCached;
        return cached != null ? cached : DEFAULT;
    }

    public static boolean isBlocked(String pkg) {
        Config config = get();
        return config.block.contains(pkg) || config.auto.contains(pkg);
    }

    /** Persist a new per-user baseline and optionally select one new package. */
    public static void applyAutoState(final JSONObject baseline, final String addPkg) {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                Context context = Ctx.get();
                if (context == null) {
                    return;
                }
                synchronized (LOCK) {
                    try {
                        JSONObject json = readJson(context);
                        json.put("autoUsers", baseline);
                        if (addPkg != null && !addPkg.isEmpty()) {
                            JSONArray autoArray = json.optJSONArray("auto");
                            if (autoArray == null) {
                                autoArray = new JSONArray();
                                json.put("auto", autoArray);
                            }
                            boolean exists = false;
                            for (int i = 0; i < autoArray.length(); i++) {
                                if (addPkg.equals(autoArray.optString(i))) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                autoArray.put(addPkg);
                                Logx.i("auto-selected new package: " + addPkg);
                            }
                        }
                        writeJson(context, json);
                    } catch (Throwable t) {
                        Logx.w("cannot persist auto selection state", t);
                    }
                }
            }
        });
    }

    /** Forget the baseline so that the next enable starts from a fresh snapshot. */
    public static void clearAutoUsers() {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                Context context = Ctx.get();
                if (context == null) {
                    return;
                }
                synchronized (LOCK) {
                    try {
                        JSONObject json = readJson(context);
                        json.put("autoUsers", new JSONObject());
                        writeJson(context, json);
                    } catch (Throwable t) {
                        Logx.w("cannot clear auto baseline", t);
                    }
                }
            }
        });
    }

    static JSONObject readJson(Context context) throws Exception {
        String raw = Settings.Global.getString(context.getContentResolver(), HookConfig.KEY_CONFIG);
        return raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
    }

    static void writeJson(Context context, JSONObject json) {
        Settings.Global.putString(context.getContentResolver(), HookConfig.KEY_CONFIG, json.toString());
    }

    private static Config load() {
        Context context = Ctx.get();
        if (context == null) {
            return defaults();
        }
        try {
            JSONObject json = readJson(context);
            if (json.length() == 0) {
                return defaults();
            }
            JSONObject autoUsers = json.optJSONObject("autoUsers");
            return new Config(
                    json.optBoolean("enabled", true),
                    json.optBoolean("hideAccessibility", true),
                    json.optBoolean("hideAdb", true),
                    json.optBoolean("autoFill", false),
                    autoUsers == null ? new JSONObject() : new JSONObject(autoUsers.toString()),
                    readSet(json, "block"),
                    readSet(json, "auto"),
                    readSet(json, "allow"));
        } catch (Throwable t) {
            Logx.w("cannot parse config, using defaults", t);
            return defaults();
        }
    }

    private static Set<String> readSet(JSONObject json, String name) {
        Set<String> result = new HashSet<>();
        JSONArray array = json.optJSONArray(name);
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i);
                if (value != null && !value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static Config defaults() {
        return new Config(true, true, true, false, new JSONObject(),
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    }
}
