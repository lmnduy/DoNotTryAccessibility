package io.github.nitsuya.donottryaccessibility.hook;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;

/**
 * Writes the "module is alive" marker used by the WebUI.  The marker contains
 * the current {@code boot_count}; a stale value from a previous boot is ignored.
 */
public final class Status {

    private Status() {
    }

    public static void write() {
        Context context = Ctx.get();
        if (context == null) {
            return;
        }
        try {
            int bootCount = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            JSONObject json = new JSONObject();
            json.put("version", HookConfig.VERSION);
            json.put("boot", bootCount);
            json.put("bootId", bootId());
            json.put("time", System.currentTimeMillis());
            Settings.Global.putString(context.getContentResolver(), HookConfig.KEY_STATUS, json.toString());
            Logx.i("status written: " + json);
        } catch (Throwable t) {
            Logx.w("cannot write status", t);
        }
    }

    private static String bootId() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * {@code BOOT_COUNT} is incremented by system_server after our early hook
     * has already run, so the first heartbeat can contain the previous boot's
     * number.  Write it again once {@code sys.boot_completed} is set; this is a
     * one-shot wait, not a periodic poll.
     */
    public static void writeAfterBootCompleted() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 240; i++) {
                    try {
                        Class<?> systemProperties = Class.forName("android.os.SystemProperties");
                        Method get = systemProperties.getMethod("get", String.class);
                        Object completed = get.invoke(null, "sys.boot_completed");
                        if ("1".equals(completed)) {
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                    SystemClock.sleep(500);
                }
                write();
            }
        }, "dnta-status-boot");
        thread.setDaemon(true);
        thread.start();
    }
}
